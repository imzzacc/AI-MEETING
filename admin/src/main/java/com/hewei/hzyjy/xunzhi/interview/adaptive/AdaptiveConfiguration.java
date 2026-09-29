package com.hewei.hzyjy.xunzhi.interview.adaptive;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

@Data
@Configuration
@EnableScheduling
@ConfigurationProperties(prefix = "xunzhi-agent.interview.adaptive")
public class AdaptiveConfiguration {
    private boolean enabled;
    private boolean emergencyDisableFollowUp;
    private boolean reportWorkerEnabled = true;
    private AdaptiveModels.Mode mode = AdaptiveModels.Mode.SHADOW;
    private int maxPerMain = 2;
    private int maxPerSession = 6;
    private Set<String> revokedCandidateIds = new HashSet<>();
    private String catalogLocation = "classpath:knowledge/interview-catalog-v1.json";

    @Bean
    public Clock interviewClock() {
        return Clock.systemUTC();
    }
}
