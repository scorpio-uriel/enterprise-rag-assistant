package com.acme.rag.common;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Active {@code @Async} et fournit le pool dédié à l'indexation. Borné (2 threads, file de 50) pour
 * que 30 imports simultanés ne saturent ni Ollama ni la mémoire. Nommé {@code ingestionExecutor}
 * pour ne pas remplacer l'{@code applicationTaskExecutor} auto-configuré par Spring Boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

  public static final String INGESTION_EXECUTOR = "ingestionExecutor";

  @Bean(name = INGESTION_EXECUTOR)
  Executor ingestionExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("ingestion-");
    // À l'arrêt, laisse finir les indexations en cours plutôt que de les couper net
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }
}
