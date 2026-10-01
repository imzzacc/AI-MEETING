package com.hewei.hzyjy.xunzhi.interview.adaptive;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

/** MongoDB 5+ supports literal dots in field names; knowledge IDs must round-trip unchanged. */
@Configuration(proxyBeanMethods = false)
public class AdaptiveMongoMappingConfiguration {
    @Bean
    static BeanPostProcessor preserveKnowledgeMapKeys() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof MappingMongoConverter converter) {
                    // Replacing dots with underscores would collide with distinct valid IDs.
                    converter.preserveMapKeys(true);
                }
                return bean;
            }
        };
    }
}
