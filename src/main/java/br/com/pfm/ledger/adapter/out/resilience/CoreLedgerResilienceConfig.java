package br.com.pfm.ledger.adapter.out.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j Distributed Resilience Configuration (ADR-008):
 * - Timeout: 3 seconds hard limit ({@link TimeLimiter})
 * - Circuit Breaker: 50% failure threshold, sliding window of 10 calls, 15s wait in OPEN state
 * - Bulkhead: Semaphore / Virtual Thread pool isolation preventing cascading saturation
 */
@Configuration
public class CoreLedgerResilienceConfig {

    public static final String INSTANCE_NAME = "coreLedger";

    @Bean
    public TimeLimiter coreLedgerTimeLimiter() {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(3))
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of(INSTANCE_NAME, config);
    }

    @Bean
    public CircuitBreaker coreLedgerCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        return CircuitBreaker.of(INSTANCE_NAME, config);
    }

    @Bean
    public Bulkhead coreLedgerBulkhead() {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(64)
                .maxWaitDuration(Duration.ofMillis(100))
                .build();
        return Bulkhead.of(INSTANCE_NAME, config);
    }

    @Bean(destroyMethod = "close")
    public ExecutorService isolatedVirtualThreadBulkheadPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
