package com.hewei.hzyjy.xunzhi.interview.adaptive;

import static com.hewei.hzyjy.xunzhi.interview.adaptive.AdaptiveModels.*;

import java.util.List;

public interface AdaptiveSessionStore {
    Session find(String id);

    Session save(Session session);

    List<Session> work(long now);

    Mistake findMistake(String id);

    Mistake saveMistake(Mistake mistake);

    List<Mistake> mistakes(Long userId, int offset, int size, String status, String search);
}
