package com.hs.memory.vector;

import com.hs.memory.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(Settings settings) {
        return new QdrantClient(settings);
    }
}
