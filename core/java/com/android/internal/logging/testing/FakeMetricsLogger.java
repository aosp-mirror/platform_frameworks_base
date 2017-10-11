package com.android.internal.logging.testing;

import android.metrics.LogMaker;

import com.android.internal.logging.MetricsLogger;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Fake logger that queues up logged events for inspection.
 *
 * @hide.
 */
public class FakeMetricsLogger extends MetricsLogger {
    private Queue<LogMaker> logs = new LinkedList<>();

    @Override
    protected void saveLog(Object[] rep) {
        logs.offer(new LogMaker(rep));
    }

    public Queue<LogMaker> getLogs() {
        return logs;
    }

    public void reset() {
        logs.clear();
    }
}
