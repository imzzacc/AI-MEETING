package com.hewei.hzyjy.xunzhi.interview.api;

import com.hewei.hzyjy.xunzhi.common.convention.annotation.CurrentUser;
import com.hewei.hzyjy.xunzhi.common.convention.context.UserContext;
import com.hewei.hzyjy.xunzhi.common.convention.exception.ClientException;
import com.hewei.hzyjy.xunzhi.common.convention.result.Result;
import com.hewei.hzyjy.xunzhi.common.convention.result.Results;
import com.hewei.hzyjy.xunzhi.interview.adaptive.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/xunzhi/v1/interview")
@RequiredArgsConstructor
public class InterviewAdaptiveController {
    private final AdaptiveInterviewService interviews;
    private final InterviewReviewService reviews;

    public record PolicyRequest(
            @Min(1200) @Max(2700) int targetDurationSeconds,
            Long expectedRevision,
            AdaptiveModels.RetrievalScope retrievalScope) {}

    public record ExtensionRequest(
            @NotBlank @Size(max = 64) String requestId, @Min(300) @Max(300) int extensionSeconds) {}

    public record RetryRequest(@NotBlank @Size(max = 64) String requestId) {}

    public record MistakeUpdate(
            Long expectedRevision, String status, @Size(max = 2000) String note) {}

    public record PracticeRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 5000) String answerContent) {}

    @GetMapping("/adaptive-capabilities")
    public Result<Map<String, Object>> capabilities(@CurrentUser UserContext user) {
        return Results.success(
                Map.of("enabled", interviews.enabled(), "durations", List.of(1200, 1800, 2700)));
    }

    @PutMapping("/sessions/{id}/policy")
    public Result<Map<String, Object>> policy(
            @PathVariable String id,
            @Valid @RequestBody PolicyRequest request,
            @CurrentUser UserContext user) {
        var s =
                interviews.configure(
                        id,
                        user.getUserId(),
                        request.targetDurationSeconds(),
                        request.expectedRevision(),
                        request.retrievalScope());
        var response = new LinkedHashMap<String, Object>();
        response.put("revision", s.getRevision());
        response.put("targetDurationSeconds", s.getTargetDurationSeconds());
        response.put("mode", s.getMode());
        response.put("retrievalScope", s.getRetrievalScope());
        return Results.success(response);
    }

    @PostMapping("/sessions/{id}/extensions")
    public Result<AdaptiveModels.Budget> extend(
            @PathVariable String id,
            @Valid @RequestBody ExtensionRequest request,
            @CurrentUser UserContext user) {
        return Results.success(
                interviews.extend(
                        id, user.getUserId(), request.requestId(), request.extensionSeconds()));
    }

    @GetMapping("/sessions/{id}/report")
    public Result<Map<String, Object>> report(
            @PathVariable String id, @CurrentUser UserContext user) {
        return Results.success(reviews.report(id, user.getUserId()));
    }

    @PostMapping("/sessions/{id}/report/retry")
    public Result<Void> retry(
            @PathVariable String id,
            @Valid @RequestBody RetryRequest request,
            @CurrentUser UserContext user) {
        reviews.retry(id, user.getUserId(), request.requestId());
        return Results.success();
    }

    @GetMapping("/sessions/{id}/report/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String id,
            @RequestParam(defaultValue = "markdown") String format,
            @CurrentUser UserContext user) {
        var report = reviews.report(id, user.getUserId());
        if (!format.equals("markdown") || !report.get("status").equals("SUCCEEDED"))
            throw new ClientException("REPORT_NOT_READY");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=interview-review.md")
                .body(report.get("markdown").toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/mistakes")
    public Result<List<AdaptiveModels.Mistake>> mistakes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @CurrentUser UserContext user) {
        return Results.success(reviews.mistakes(user.getUserId(), page, size, status, search));
    }

    @PatchMapping("/mistakes/{id}")
    public Result<AdaptiveModels.Mistake> update(
            @PathVariable String id,
            @Valid @RequestBody MistakeUpdate request,
            @CurrentUser UserContext user) {
        return Results.success(
                reviews.update(
                        id,
                        user.getUserId(),
                        request.expectedRevision(),
                        request.status(),
                        request.note()));
    }

    @DeleteMapping("/mistakes/{id}")
    public Result<Void> delete(@PathVariable String id, @CurrentUser UserContext user) {
        reviews.delete(id, user.getUserId());
        return Results.success();
    }

    @PostMapping("/mistakes/{id}/reviews")
    public Result<AdaptiveModels.Mistake> practice(
            @PathVariable String id,
            @Valid @RequestBody PracticeRequest request,
            @CurrentUser UserContext user) {
        return Results.success(
                reviews.review(id, user.getUserId(), request.requestId(), request.answerContent()));
    }
}
