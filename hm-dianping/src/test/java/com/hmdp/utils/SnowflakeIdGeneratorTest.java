package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void shouldBindNodeIdsFromSpringProperties() {
        new ApplicationContextRunner()
                .withBean(SnowflakeIdGenerator.class)
                .withPropertyValues(
                        "hmdp.id-generator.worker-id=3",
                        "hmdp.id-generator.datacenter-id=2"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    SnowflakeIdGenerator generator =
                            context.getBean(SnowflakeIdGenerator.class);
                    assertTrue(generator.nextId() > 0);
                });
    }

    @Test
    void shouldGeneratePositiveIncreasingIds() {
        SnowflakeIdGenerator generator =
                new SnowflakeIdGenerator(1, 1);

        long previous = generator.nextId();
        assertTrue(previous > 0);

        for (int i = 0; i < 10_000; i++) {
            long current = generator.nextId();
            assertTrue(current > previous);
            previous = current;
        }
    }

    @Test
    void shouldGenerateUniqueIdsConcurrently()
            throws InterruptedException {
        SnowflakeIdGenerator generator =
                new SnowflakeIdGenerator(1, 1);
        int threadCount = 32;
        int idsPerThread = 2_000;
        int expectedCount = threadCount * idsPerThread;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicReference<Throwable> taskFailure =
                new AtomicReference<>();
        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < idsPerThread; j++) {
                            ids.add(generator.nextId());
                        }
                    } catch (Throwable throwable) {
                        taskFailure.compareAndSet(null, throwable);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertNull(taskFailure.get());
        assertEquals(expectedCount, ids.size());
        assertTrue(ids.stream().allMatch(id -> id > 0));
    }

    @Test
    void shouldNotCollideAcrossDifferentWorkers() {
        SnowflakeIdGenerator workerOne =
                new SnowflakeIdGenerator(1, 1);
        SnowflakeIdGenerator workerTwo =
                new SnowflakeIdGenerator(2, 1);
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            assertTrue(ids.add(workerOne.nextId()));
            assertTrue(ids.add(workerTwo.nextId()));
        }

        assertEquals(20_000, ids.size());
    }

    @Test
    void shouldRejectOutOfRangeNodeIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(-1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(32, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(1, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeIdGenerator(1, 32)
        );
    }
}
