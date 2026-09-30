package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptiveSessionDeletionService {
    private final AdaptiveSessionStore store;
    private final AdaptiveInterviewService interviews;
    private final InterviewSessionErasure erasure;
    private final Clock clock;

    public void delete(String id, Long userId) {
        interviews.locked(id, () -> {
            Session s = store.find(id);
            if (s == null || userId == null || !Objects.equals(s.getUserId(), userId))
                throw new ClientException("Session not found");
            if (!s.isDeleted()) {
                interviews.owned(id, userId);
                // Upload/extraction and legacy media jobs have their own lifecycles. The
                // report page deletes completed interviews; finish an active session first.
                if (!s.isFinished()) throw new ClientException("Finish the interview before deleting it");
                Session marker = new Session();
                marker.setId(id);
                marker.setUserId(userId);
                marker.setRevision(s.getRevision());
                marker.setDeleted(true);
                marker.setFinished(true);
                marker.setReportStatus("DELETED");
                s = store.save(marker); // Scrubs answers, catalog, pending input and report at once.
            }
            if (!s.isDeletionComplete()) clean(s);
            return null;
        });
    }

    private void clean(Session deleted) {
        String id = deleted.getId();
        Long userId = deleted.getUserId();
        for (Mistake candidate : store.mistakesForSession(id, userId)) {
            interviews.locked("mistake-" + candidate.getId(), () -> {
                Mistake m = store.findMistake(candidate.getId());
                if (m == null || !Objects.equals(m.getUserId(), userId)) return null;
                m.getSessionIds().remove(id);
                m.getEvidence().removeIf(e -> id.equals(e.sessionId()));
                boolean removedPractice = m.getPractices().removeIf(p -> p.sourceSessionId() == null
                        || id.equals(p.sourceSessionId()));
                if (removedPractice && "PRACTICE_VERIFIED".equals(m.getMasterySource())) {
                    m.setMasterySource(null);
                    m.setStatus("REVIEWING");
                }
                if (m.getSessionIds().isEmpty()) {
                    if (m.isDismissed()) {
                        // Preserve an explicit user dismissal without retaining personal text.
                        m.setQuestion(null);
                        m.setNote(null);
                        m.getPractices().clear();
                        m.getEvidence().clear();
                        store.saveMistake(m);
                    } else store.removeMistake(m.getId(), userId);
                } else {
                    // The display question may have come from the deleted interview. Use the
                    // matching turn from a surviving source, never its removed question text.
                    m.setQuestion("请解释并举例说明该知识点：" + m.getKnowledgePointId());
                    for (MistakeEvidence evidence : m.getEvidence()) {
                        Session source = store.find(evidence.sessionId());
                        if (source == null || source.isDeleted()) continue;
                        var turn = source.getTurns().stream()
                                .filter(t -> t.requestId().equals(evidence.requestId())).findFirst();
                        if (turn.isPresent()) { m.setQuestion(turn.get().question()); break; }
                    }
                    m.setUpdatedAt(clock.millis());
                    store.saveMistake(m);
                }
                return null;
            });
        }
        erasure.erase(id, userId);
        deleted.setDeletionComplete(true);
        store.save(deleted);
    }

    @Scheduled(fixedDelayString = "${xunzhi-agent.interview.adaptive.deletion-poll-ms:10000}")
    public void retryPending() {
        try {
            for (Session s : store.pendingDeletions()) {
                try { delete(s.getId(), s.getUserId()); }
                catch (Exception e) { log.warn("Session cleanup deferred, errorType={}", e.getClass().getSimpleName()); }
            }
        } catch (Exception e) { log.debug("Session cleanup store unavailable: {}", e.getClass().getSimpleName()); }
    }
}
