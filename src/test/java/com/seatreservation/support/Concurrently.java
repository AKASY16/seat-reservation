package com.seatreservation.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class Concurrently {

    private Concurrently() {
    }

    /**
     작업들을 여러 스레드에서 최대한 같은 순간에 시작시킨다.
     모든 스레드가 준비될 때까지 start 래치에서 기다렸다가 한 번에 출발한다.
     결과는 작업 순서대로 돌려주고, 예외가 난 작업은 그 예외를 결과로 담는다.
     **/
    public static List<Object> run(List<Callable<?>> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    results.add(future.get(60, TimeUnit.SECONDS));
                } catch (Exception e) {
                    results.add(e);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
