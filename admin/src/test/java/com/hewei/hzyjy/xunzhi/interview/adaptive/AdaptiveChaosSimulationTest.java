package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;
import static com.hewei.hzyjy.xunzhi.interview.adaptive.FaultInjectionHarness.*;

import static org.junit.jupiter.api.Assertions.*;

import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewAnswerRespDTO;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Plays many random interviews against the real service: answers, retries, conflicting
 * requests, refreshes, clock jumps, extensions and manual ends, with a crash injected at a random
 * point in about a third of the requests. After every step the stored session must satisfy the
 * invariants below. Every run is seeded; a failure prints the seed and the steps so far.
 *
 * <p>Reproduce one run with {@code -Dchaos.seed=<seed>}.
 */
class AdaptiveChaosSimulationTest {
    static final int RUNS = 200;
    static final int MAX_STEPS = 80;
    static final Map<String, String> QUESTIONS =
            Map.of(
                    "1", "volatile 能保证 i++ 的原子性吗？",
                    "2", "hashcode 和 equals 有什么关系？",
                    "3", "MySQL 默认的隔离级别是什么？",
                    "4", "缓存击穿和缓存穿透有什么区别？",
                    "5", "介绍一个你做过的项目");
    static final String[] ANSWERS = {"GOOD", "BAD", "HALF", "NONE"};
    static final Point[] ANSWER_CRASHES = Point.values();

    @Test
    void randomCrashesRetriesAndRefreshesKeepEveryInvariant() throws Exception {
        String only = System.getProperty("chaos.seed");
        if (only != null) {
            new Run(Long.parseLong(only)).play();
            return;
        }
        Map<String, Integer> crashes = new TreeMap<>();
        int completed = 0, followUps = 0, wasted = 0;
        for (long seed = 1; seed <= RUNS; seed++) {
            Run run = new Run(seed);
            run.play();
            run.h.store.fired.forEach(point -> crashes.merge(point, 1, Integer::sum));
            if ("COMPLETED".equals(run.h.session().getFinishReason())) completed++;
            followUps += run.h.session().getTotalFollowUps();
            wasted += run.wasted;
        }
        // Guard against the simulation quietly degenerating into happy paths.
        for (Point point : Point.values())
            assertTrue(crashes.getOrDefault(point.name(), 0) >= 10, "too few crashes at " + point + ": " + crashes);
        assertTrue(completed >= RUNS / 4, "too few completed interviews: " + completed);
        assertTrue(followUps >= RUNS / 2, "too few follow-ups: " + followUps);
        System.out.printf(
                "chaos: %d runs, %d completed, %d follow-ups, %d lost model results, crashes %s%n",
                RUNS, completed, followUps, wasted, crashes);
    }

    static final class Run {
        final long seed;
        final Random random;
        final List<String> steps = new ArrayList<>();
        final Map<String, InterviewAnswerRespDTO> firstResponses = new HashMap<>();
        final Map<String, String> bodies = new LinkedHashMap<>();
        FaultInjectionHarness h;
        int maxPerMain;
        int maxPerSession;
        List<MainQuestion> plan;
        String planHash;
        long startedAt;
        String last;
        int nextId;
        int wasted;

        Run(long seed) {
            this.seed = seed;
            this.random = new Random(seed);
        }

        void play() throws Exception {
            try {
                setup();
                for (int step = 0; step < MAX_STEPS && !h.session().isFinished(); step++) {
                    step();
                    check();
                }
                if (!h.session().isFinished()) finish(false);
                check();
                report();
            } catch (Throwable t) {
                throw new AssertionError(
                        "chaos run failed, seed="
                                + seed
                                + " (rerun with -Dchaos.seed="
                                + seed
                                + ")\n  "
                                + String.join("\n  ", steps),
                        t);
            }
        }

        void setup() throws Exception {
            Mode mode =
                    seed % 5 == 0 ? Mode.SHADOW : seed % 7 == 0 ? Mode.TIME_ONLY : Mode.ADAPTIVE;
            maxPerMain = random.nextInt(3);
            maxPerSession = new int[] {0, 2, 6}[random.nextInt(3)];
            int target = new int[] {1200, 1800, 2700}[random.nextInt(3)];
            steps.add(
                    "setup mode="
                            + mode
                            + " maxPerMain="
                            + maxPerMain
                            + " maxPerSession="
                            + maxPerSession
                            + " target="
                            + target);
            h =
                    new FaultInjectionHarness(
                                    new AdaptiveInterviewServiceTest.MemoryStore(),
                                    QUESTIONS,
                                    mode,
                                    maxPerMain,
                                    maxPerSession)
                            .start(target);
            Session s = h.session();
            plan = List.copyOf(s.getQuestions());
            planHash = s.getQuestionSetHash();
            startedAt = s.getStartedAt();
        }

        void step() {
            // Like the real client: after a refresh it resubmits the answer the server kept.
            Pending pending = h.session().getPending();
            if (pending != null && random.nextInt(3) == 0) {
                send(pending.getRequestId(), pending.getAnswer(), maybeCrash(25));
                return;
            }
            int roll = random.nextInt(100);
            if (roll < 45) answerNew();
            else if (roll < 65 && last != null) send(last, bodies.get(last), maybeCrash(25));
            else if (roll < 70) conflictingBody();
            else if (roll < 75) wrongQuestionNumber();
            else if (roll < 85) refresh();
            else if (roll < 92) clockJump();
            else if (roll < 95) extend();
            else if (roll < 97) finish(random.nextInt(3) == 0);
            else replayCommitted();
        }

        Point maybeCrash(int percent) {
            return random.nextInt(100) < percent
                    ? ANSWER_CRASHES[random.nextInt(ANSWER_CRASHES.length)]
                    : null;
        }

        void answerNew() {
            String id = "q" + nextId++;
            String body = ANSWERS[random.nextInt(ANSWERS.length)] + " answer " + id;
            bodies.put(id, body);
            last = id;
            send(id, body, maybeCrash(35));
        }

        void send(String id, String body, Point crash) {
            Session before = h.session();
            String number = before.isFinished() ? "1" : before.currentNumber();
            boolean committed = before.getTurns().stream().anyMatch(t -> t.requestId().equals(id));
            if (committed)
                number =
                        before.getTurns().stream()
                                .filter(t -> t.requestId().equals(id))
                                .findFirst()
                                .orElseThrow()
                                .questionNumber();
            String label = "answer " + id + " #" + number + " " + body.split(" ")[0];
            if (crash != null) h.arm(crash);
            int fired = h.store.fired.size();
            try {
                InterviewAnswerRespDTO response = h.answer(id, number, body);
                steps.add(label + (crash == null ? "" : " armed=" + crash) + " -> ok");
                InterviewAnswerRespDTO first = firstResponses.putIfAbsent(id, response);
                if (first != null) assertEquals(first, response, "replay of " + id + " changed");
                checkProjection();
            } catch (Crash c) {
                String point = h.store.fired.get(fired);
                steps.add(label + " -> crash " + point);
                if (point.equals(Point.BEFORE_EVALUATION_SAVE.name())) wasted++;
            } catch (ClientException e) {
                steps.add(label + " -> rejected: " + e.getMessage());
                Pending pending = before.getPending();
                boolean blockedByOtherPending =
                        pending != null && !pending.getRequestId().equals(id);
                assertTrue(
                        blockedByOtherPending || before.isFinished(),
                        "unexpected rejection: " + e.getMessage());
            } finally {
                h.disarm();
            }
        }

        void conflictingBody() {
            Session s = h.session();
            if (s.getTurns().isEmpty()) return;
            Turn t = s.getTurns().get(random.nextInt(s.getTurns().size()));
            steps.add("same id " + t.requestId() + " with a different body");
            assertThrows(
                    ClientException.class,
                    () -> h.answer(t.requestId(), t.questionNumber(), "GOOD changed body"));
        }

        void wrongQuestionNumber() {
            Session s = h.session();
            String id = "q" + nextId++;
            String number = s.getMainIndex() > 0 ? plan.get(s.getMainIndex() - 1).number() : "99";
            steps.add("answer " + id + " for stale #" + number);
            assertThrows(ClientException.class, () -> h.answer(id, number, "GOOD stale"));
        }

        void refresh() {
            boolean crash = random.nextInt(5) == 0;
            if (crash) h.arm(Point.DURING_PROJECTION);
            try {
                var restored = h.service.current(SESSION, USER);
                steps.add("refresh -> ok");
                Session s = h.session();
                if (s.getPending() != null) {
                    assertEquals(
                            s.getPending().getRequestId(),
                            restored.getPendingAnswer().get("requestId"));
                    assertEquals(
                            s.getPending().getAnswer(), restored.getPendingAnswer().get("answer"));
                } else assertNull(restored.getPendingAnswer());
            } catch (Crash c) {
                steps.add("refresh -> crash during projection");
            } finally {
                h.disarm();
            }
        }

        void clockJump() {
            int seconds = 30 + random.nextInt(370);
            h.clock.advanceSeconds(seconds);
            steps.add("clock +" + seconds + "s");
        }

        void extend() {
            String id = "e" + nextId++;
            try {
                h.service.extend(SESSION, USER, id, 300);
                steps.add("extend " + id + " -> ok");
            } catch (ClientException e) {
                steps.add("extend " + id + " -> rejected: " + e.getMessage());
            }
        }

        void finish(boolean crash) {
            Session before = h.session();
            boolean pendingResult =
                    before.getPending() != null && before.getPending().getEvaluation() != null;
            boolean after = random.nextBoolean();
            if (crash) h.store.crashOnSave(Session::isFinished, after);
            try {
                h.service.finish(SESSION, USER);
                steps.add("finish -> ok");
            } catch (Crash c) {
                steps.add("finish -> crash " + (after ? "after" : "before") + " save");
            } finally {
                h.disarm();
            }
            // A manual end drops an uncommitted answer, including a model result already paid for.
            if (pendingResult && h.session().getPending() == null) wasted++;
        }

        void replayCommitted() {
            Session s = h.session();
            if (s.getTurns().isEmpty()) return;
            Turn t = s.getTurns().get(random.nextInt(s.getTurns().size()));
            steps.add("replay " + t.requestId());
            InterviewAnswerRespDTO response = h.answer(t.requestId(), t.questionNumber(), t.answer());
            InterviewAnswerRespDTO first = firstResponses.putIfAbsent(t.requestId(), response);
            if (first != null) assertEquals(first, response, "replay of " + t.requestId() + " changed");
            assertEquals(t.response(), response);
        }

        void checkProjection() {
            Session s = h.session();
            int sum =
                    s.getTurns().stream()
                            .filter(t -> !t.questionNumber().contains("-F"))
                            .mapToInt(t -> t.evaluation().score())
                            .sum();
            assertEquals(String.valueOf(sum), h.projectedScoreSum, "projected score sum");
            for (Turn t : s.getTurns())
                assertTrue(h.projected.contains(t.requestId()), "turn not projected: " + t.requestId());
        }

        void check() {
            Session s = h.session();
            // The main question plan never changes.
            assertEquals(plan, s.getQuestions());
            assertEquals(planHash, s.getQuestionSetHash());
            assertEquals(startedAt, s.getStartedAt());

            // Every answered main question is scored exactly once, in plan order.
            List<Turn> mains =
                    s.getTurns().stream().filter(t -> !t.questionNumber().contains("-F")).toList();
            int answeredMains = s.getMainIndex() + (s.getCurrentCandidate() != null ? 1 : 0);
            assertEquals(answeredMains, mains.size(), "main turns vs cursor");
            for (int i = 0; i < mains.size(); i++) {
                assertEquals(plan.get(i).number(), mains.get(i).questionNumber());
                assertEquals(
                        ScriptedEvaluator.scoreFor(mains.get(i).answer()),
                        mains.get(i).evaluation().score());
            }
            assertEquals(
                    s.getTurns().size(),
                    s.getTurns().stream().map(Turn::requestId).distinct().count(),
                    "a request was committed twice");

            // Follow-ups stay within both limits and never probe the same gap twice.
            long followUps = s.getTurns().size() - mains.size();
            assertEquals(
                    s.getTotalFollowUps() - (s.getCurrentCandidate() != null ? 1 : 0),
                    followUps,
                    "follow-up turns vs counter");
            assertTrue(s.getTotalFollowUps() <= maxPerSession, "session follow-up limit");
            assertTrue(s.getFollowUpCount() <= maxPerMain, "per-main follow-up limit");
            for (int i = 0; i < plan.size(); i++) {
                String prefix = plan.get(i).number() + "-F";
                long asked =
                        s.getTurns().stream().filter(t -> t.questionNumber().startsWith(prefix)).count()
                                + (s.getCurrentCandidate() != null && s.getMainIndex() == i ? 1 : 0);
                assertTrue(asked <= maxPerMain, "per-main follow-up limit for " + plan.get(i).number());
            }
            assertEquals(s.getTotalFollowUps(), s.getProbedGaps().size(), "a gap was probed twice");

            // Every model result that came back is committed, waiting in pending, or accounted
            // for as lost to a crash before it was saved or to a manual end.
            int inPending =
                    s.getPending() != null && s.getPending().getEvaluation() != null ? 1 : 0;
            assertEquals(
                    h.evaluator.returned.get(),
                    s.getTurns().size() + inPending + wasted,
                    "model results");

            if (s.getPending() != null)
                assertEquals(s.currentNumber(), s.getPending().getQuestionNumber());
            if (s.isFinished()) {
                assertNull(s.getPending());
                assertTrue(
                        Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED")
                                .contains(s.getReportStatus()),
                        "finished without a report job: " + s.getReportStatus());
            } else assertEquals("NOT_REQUESTED", s.getReportStatus());
        }

        void report() {
            var reviews = h.reviews();
            if (random.nextBoolean()) {
                h.store.crashOnSave(x -> "SUCCEEDED".equals(x.getReportStatus()), false);
                steps.add("report worker -> crash before publishing");
                assertThrows(Crash.class, () -> reviews.process(SESSION));
                h.clock.advanceSeconds(121);
            }
            reviews.process(SESSION);
            steps.add("report worker -> " + h.session().getReportStatus());
            Session s = h.session();
            assertEquals("SUCCEEDED", s.getReportStatus());
            assertEquals(1, h.store.publishedLeases.size(), "report published more than once");
            Set<String> committed = new HashSet<>();
            s.getTurns().forEach(t -> committed.add(t.requestId()));
            for (Mistake m :
                    ((AdaptiveInterviewServiceTest.MemoryStore) h.store.delegate).mistakes.values()) {
                List<String> sources =
                        m.getEvidence().stream()
                                .map(e -> e.sessionId() + "/" + e.requestId())
                                .toList();
                assertEquals(new HashSet<>(sources).size(), sources.size(), "duplicate evidence");
                m.getEvidence()
                        .forEach(e -> assertTrue(committed.contains(e.requestId()), "evidence from uncommitted answer"));
            }
        }
    }
}
