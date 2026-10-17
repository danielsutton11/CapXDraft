package com.halloween.draft.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget delays for turn warnings and timeouts. Each callback carries the turn token it
 * was scheduled for, and the draft ignores it if the turn has since moved on.
 */
@Service
public class TimerService {
    private static final Logger log = LoggerFactory.getLogger(TimerService.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "draft-timer");
        t.setDaemon(true);
        return t;
    });

    /** Runs a task repeatedly; exceptions are logged and don't stop the schedule. */
    public void every(long initialDelayMs, long periodMs, Runnable task) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Repeating task failed", e);
            }
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public void after(long delayMs, Runnable task) {
        scheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Timer task failed", e);
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }
}
