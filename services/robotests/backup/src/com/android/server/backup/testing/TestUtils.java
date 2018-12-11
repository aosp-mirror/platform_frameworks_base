/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.backup.testing;

import static com.google.common.truth.Truth.assertThat;

import static org.robolectric.Shadows.shadowOf;

import static java.util.stream.Collectors.toSet;

import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;

import com.android.server.testing.shadows.ShadowEventLog;

import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class TestUtils {
    private static final long TIMEOUT_MS = 3000;
    private static final long STEP_MS = 50;

    /**
     * Counts the number of messages in the looper {@code looper} that satisfy {@code
     * messageFilter}.
     */
    public static int messagesInLooper(Looper looper, Predicate<Message> messageFilter) {
        MessageQueue queue = looper.getQueue();
        int i = 0;
        for (Message m = shadowOf(queue).getHead(); m != null; m = shadowOf(m).getNext()) {
            if (messageFilter.test(m)) {
                i += 1;
            }
        }
        return i;
    }

    public static void waitUntil(Supplier<Boolean> condition)
            throws InterruptedException, TimeoutException {
        waitUntil(condition, STEP_MS, TIMEOUT_MS);
    }

    public static void waitUntil(Supplier<Boolean> condition, long stepMs, long timeoutMs)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (condition.get()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new TimeoutException("Test timed-out waiting for condition");
            }
            Thread.sleep(stepMs);
        }
    }

    /** Version of {@link ShadowLooper#runToEndOfTasks()} that also advances the system clock. */
    public static void runToEndOfTasks(Looper looper) {
        ShadowLooper shadowLooper = shadowOf(looper);
        shadowLooper.runToEndOfTasks();
        // Handler instances have their own clock, so advancing looper (with runToEndOfTasks())
        // above does NOT advance the handlers' clock, hence whenever a handler post messages with
        // specific time to the looper the time of those messages will be before the looper's time.
        // To fix this we advance SystemClock as well since that is from where the handlers read
        // time.
        SystemClock.setCurrentTimeMillis(shadowLooper.getScheduler().getCurrentTime());
    }

    /**
     * Reset logcat with {@link ShadowLog#reset()} before the test case if you do anything that uses
     * logcat before that.
     */
    public static void assertLogcatAtMost(String tag, int level) {
        assertThat(ShadowLog.getLogsForTag(tag).stream().allMatch(logItem -> logItem.type <= level))
                .named("All logs <= " + level)
                .isTrue();
    }

    /**
     * Reset logcat with {@link ShadowLog#reset()} before the test case if you do anything that uses
     * logcat before that.
     */
    public static void assertLogcatAtLeast(String tag, int level) {
        assertThat(ShadowLog.getLogsForTag(tag).stream().anyMatch(logItem -> logItem.type >= level))
                .named("Any log >= " + level)
                .isTrue();
    }

    /**
     * Verifies that logcat has produced log items as specified per level in {@code logs} (with
     * repetition).
     *
     * <p>So, if you call {@code assertLogcat(TAG, Log.ERROR, Log.ERROR)}, you assert that there are
     * exactly 2 log items, each with level ERROR.
     *
     * <p>Reset logcat with {@link ShadowLog#reset()} before the test case if you do anything
     * that uses logcat before that.
     */
    public static void assertLogcat(String tag, int... logs) {
        assertThat(
                        ShadowLog.getLogsForTag(tag).stream()
                                .map(logItem -> logItem.type)
                                .collect(toSet()))
                .named("Log items (specified per level)")
                .containsExactly(IntStream.of(logs).boxed().toArray());
    }

    public static void assertLogcatContains(String tag, Predicate<ShadowLog.LogItem> predicate) {
        assertThat(ShadowLog.getLogsForTag(tag).stream().anyMatch(predicate)).isTrue();
    }

    /** Declare shadow {@link ShadowEventLog} to use this. */
    public static void assertEventLogged(int tag, Object... values) {
        assertThat(ShadowEventLog.getEntries())
                .named("Event logs")
                .contains(new ShadowEventLog.Entry(tag, Arrays.asList(values)));
    }

    /** Declare shadow {@link ShadowEventLog} to use this. */
    public static void assertEventNotLogged(int tag, Object... values) {
        assertThat(ShadowEventLog.getEntries())
                .named("Event logs")
                .doesNotContain(new ShadowEventLog.Entry(tag, Arrays.asList(values)));
    }

    /**
     * Calls {@link Runnable#run()} and returns if no exception is thrown. Otherwise, if the
     * exception is unchecked, rethrow it; if it's checked wrap in a {@link RuntimeException} and
     * throw.
     *
     * <p><b>Warning:</b>DON'T use this outside tests. A wrapped checked exception is just a failure
     * in a test.
     */
    public static void uncheck(ThrowingRunnable runnable) {
        try {
            runnable.runOrThrow();
        } catch (Exception e) {
            throw wrapIfChecked(e);
        }
    }

    /**
     * Calls {@link Callable#call()} and returns the value if no exception is thrown. Otherwise, if
     * the exception is unchecked, rethrow it; if it's checked wrap in a {@link RuntimeException}
     * and throw.
     *
     * <p><b>Warning:</b>DON'T use this outside tests. A wrapped checked exception is just a failure
     * in a test.
     */
    public static <T> T uncheck(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw wrapIfChecked(e);
        }
    }

    /**
     * Wrap {@code e} in a {@link RuntimeException} only if it's not one already, in which case it's
     * returned.
     */
    public static RuntimeException wrapIfChecked(Exception e) {
        if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        }
        return new RuntimeException(e);
    }

    /** An equivalent of {@link Runnable} that allows throwing checked exceptions. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void runOrThrow() throws Exception;
    }

    private TestUtils() {}
}
