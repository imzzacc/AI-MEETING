package com.hewei.hzyjy.xunzhi.interview.adaptive;

/** Idempotent cleanup of legacy projections after the durable deletion marker is committed. */
public interface InterviewSessionErasure {
    void erase(String sessionId, Long userId);
}
