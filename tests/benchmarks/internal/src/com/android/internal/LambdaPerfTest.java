/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.internal;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.filters.LargeTest;

import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares the performance of regular lambda and pooled lambda. */
@LargeTest
public class LambdaPerfTest {
    private static final boolean DEBUG = false;
    private static final String TAG = LambdaPerfTest.class.getSimpleName();

    private static final String LAMBDA_FORM_REGULAR = "regular";
    private static final String LAMBDA_FORM_POOLED = "pooled";

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int TEST_ITERATIONS = 3000000;
    private static final int TASK_COUNT = 10;
    private static final long DELAY_AFTER_BENCH_MS = 1000;

    private String mMethodName;

    private final Bundle mTestResults = new Bundle();
    private final ArrayList<Task> mTasks = new ArrayList<>();

    // The member fields are used to ensure lambda capturing. They don't have the actual meaning.
    private final Task mTask = new Task();
    private final Rect mBounds = new Rect();
    private int mTaskId;
    private long mTime;
    private boolean mTop;

    @Rule
    public final TestRule mRule = (base, description) -> new Statement() {
        @Override
        public void evaluate() throws Throwable {
            mMethodName = description.getMethodName();
            mTasks.clear();
            for (int i = 0; i < TASK_COUNT; i++) {
                final Task t = new Task();
                mTasks.add(t);
            }
            base.evaluate();

            getInstrumentation().sendStatus(Activity.RESULT_OK, mTestResults);
        }
    };

    @Test
    public void test1ParamConsumer() {
        evaluate(LAMBDA_FORM_REGULAR, () -> forAllTask(t -> t.doSomething(mTask)));
        evaluate(LAMBDA_FORM_POOLED, () -> {
            final PooledConsumer c = PooledLambda.obtainConsumer(Task::doSomething,
                    PooledLambda.__(Task.class), mTask);
            forAllTask(c);
            c.recycle();
        });
    }

    @Test
    public void test2PrimitiveParamsConsumer() {
        // Not in Integer#IntegerCache (-128~127) for autoboxing, that will create new object.
        mTaskId = 12345;
        mTime = 54321;

        evaluate(LAMBDA_FORM_REGULAR, () -> forAllTask(t -> t.doSomething(mTaskId, mTime)));
        evaluate(LAMBDA_FORM_POOLED, () -> {
            final PooledConsumer c = PooledLambda.obtainConsumer(Task::doSomething,
                    PooledLambda.__(Task.class), mTaskId, mTime);
            forAllTask(c);
            c.recycle();
        });
    }

    @Test
    public void test3ParamsPredicate() {
        mTop = true;
        // In Integer#IntegerCache.
        mTaskId = 10;

        evaluate(LAMBDA_FORM_REGULAR, () -> handleTask(t -> t.doSomething(mBounds, mTop, mTaskId)));
        evaluate(LAMBDA_FORM_POOLED, () -> {
            final PooledPredicate c = PooledLambda.obtainPredicate(Task::doSomething,
                    PooledLambda.__(Task.class), mBounds, mTop, mTaskId);
            handleTask(c);
            c.recycle();
        });
    }

    @Test
    public void testMessage() {
        evaluate(LAMBDA_FORM_REGULAR, () -> {
            final Message m = Message.obtain().setCallback(() -> mTask.doSomething(mTaskId, mTime));
            m.getCallback().run();
            m.recycle();
        });
        evaluate(LAMBDA_FORM_POOLED, () -> {
            final Message m = PooledLambda.obtainMessage(Task::doSomething, mTask, mTaskId, mTime);
            m.getCallback().run();
            m.recycle();
        });
    }

    @Test
    public void testRunnable() {
        evaluate(LAMBDA_FORM_REGULAR, () -> {
            final Runnable r = mTask::doSomething;
            r.run();
        });
        evaluate(LAMBDA_FORM_POOLED, () -> {
            final Runnable r = PooledLambda.obtainRunnable(Task::doSomething, mTask).recycleOnUse();
            r.run();
        });
    }

    @Test
    public void testMultiThread() {
        final int numThread = 3;

        final Runnable regularAction = () -> forAllTask(t -> t.doSomething(mTask));
        final Runnable[] regularActions = new Runnable[numThread];
        Arrays.fill(regularActions, regularAction);
        evaluateMultiThread(LAMBDA_FORM_REGULAR, regularActions);

        final Runnable pooledAction = () -> {
            final PooledConsumer c = PooledLambda.obtainConsumer(Task::doSomething,
                    PooledLambda.__(Task.class), mTask);
            forAllTask(c);
            c.recycle();
        };
        final Runnable[] pooledActions = new Runnable[numThread];
        Arrays.fill(pooledActions, pooledAction);
        evaluateMultiThread(LAMBDA_FORM_POOLED, pooledActions);
    }

    private void forAllTask(Consumer<Task> callback) {
        for (int i = mTasks.size() - 1; i >= 0; i--) {
            callback.accept(mTasks.get(i));
        }
    }

    private void handleTask(Predicate<Task> callback) {
        for (int i = mTasks.size() - 1; i >= 0; i--) {
            final Task task = mTasks.get(i);
            if (callback.test(task)) {
                return;
            }
        }
    }

    private void evaluate(String title, Runnable action) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            action.run();
        }
        performGc();

        final GcStatus startGcStatus = getGcStatus();
        final long startTime = SystemClock.elapsedRealtime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            action.run();
        }
        evaluateResult(title, startGcStatus, startTime);
    }

    private void evaluateMultiThread(String title, Runnable[] actions) {
        performGc();

        final CountDownLatch latch = new CountDownLatch(actions.length);
        final GcStatus startGcStatus = getGcStatus();
        final long startTime = SystemClock.elapsedRealtime();
        for (Runnable action : actions) {
            new Thread() {
                @Override
                public void run() {
                    for (int i = 0; i < TEST_ITERATIONS; i++) {
                        action.run();
                    }
                    latch.countDown();
                };
            }.start();
        }
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        evaluateResult(title, startGcStatus, startTime);
    }

    private void evaluateResult(String title, GcStatus startStatus, long startTime) {
        final float elapsed = SystemClock.elapsedRealtime() - startTime;
        // Sleep a while to see if GC may happen.
        SystemClock.sleep(DELAY_AFTER_BENCH_MS);
        final GcStatus endStatus = getGcStatus();
        final GcInfo info = startStatus.calculateGcTime(endStatus, title, mTestResults);
        Log.i(TAG, mMethodName + "_" + title + " execution time: "
                + elapsed + "ms (avg=" + String.format("%.5f", elapsed / TEST_ITERATIONS) + "ms)"
                + " GC time: " + String.format("%.3f", info.mTotalGcTime) + "ms"
                + " GC paused time: " + String.format("%.3f", info.mTotalGcPausedTime) + "ms");
    }

    /** Cleans the test environment. */
    private static void performGc() {
        System.gc();
        System.runFinalization();
        System.gc();
    }

    private static GcStatus getGcStatus() {
        if (DEBUG) {
            Log.i(TAG, "===== Read GC dump =====");
        }
        final GcStatus status = new GcStatus();
        final List<String> vmDump = getVmDump();
        Assume.assumeFalse("VM dump is empty", vmDump.isEmpty());
        for (String line : vmDump) {
            status.visit(line);
            if (line.startsWith("DALVIK THREADS")) {
                break;
            }
        }
        return status;
    }

    private static List<String> getVmDump() {
        final int myPid = Process.myPid();
        // Another approach Debug#dumpJavaBacktraceToFileTimeout requires setenforce 0.
        Process.sendSignal(myPid, Process.SIGNAL_QUIT);
        // Give a chance to handle the signal.
        SystemClock.sleep(100);

        String dump = null;
        final String pattern = myPid + " written to: ";
        final List<String> logs = shell("logcat -v brief -d tombstoned:I *:S");
        for (int i = logs.size() - 1; i >= 0; i--) {
            final String log = logs.get(i);
            // Log pattern: Traces for pid 9717 written to: /data/anr/trace_07
            final int pos = log.indexOf(pattern);
            if (pos > 0) {
                dump = log.substring(pattern.length() + pos);
                break;
            }
        }

        Assume.assumeNotNull("Unable to find VM dump", dump);
        // It requires system or root uid to read the trace.
        return shell("cat " + dump);
    }

    private static List<String> shell(String command) {
        final ParcelFileDescriptor.AutoCloseInputStream stream =
                new ParcelFileDescriptor.AutoCloseInputStream(
                getInstrumentation().getUiAutomation().executeShellCommand(command));
        final ArrayList<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return lines;
    }

    /** An empty class which provides some methods with different type arguments. */
    static class Task {
        void doSomething() {
        }

        void doSomething(Task t) {
        }

        void doSomething(int taskId, long time) {
        }

        boolean doSomething(Rect bounds, boolean top, int taskId) {
            return false;
        }
    }

    static class ValPattern {
        static final int TYPE_COUNT = 0;
        static final int TYPE_TIME = 1;
        static final String PATTERN_COUNT = "(\\d+)";
        static final String PATTERN_TIME = "(\\d+\\.?\\d+)(\\w+)";
        final String mRawPattern;
        final Pattern mPattern;
        final int mType;

        int mIntValue;
        float mFloatValue;

        ValPattern(String p, int type) {
            mRawPattern = p;
            mPattern = Pattern.compile(
                    p + (type == TYPE_TIME ? PATTERN_TIME : PATTERN_COUNT) + ".*");
            mType = type;
        }

        boolean visit(String line) {
            final Matcher matcher = mPattern.matcher(line);
            if (!matcher.matches()) {
                return false;
            }
            final String value = matcher.group(1);
            if (value == null) {
                return false;
            }
            if (mType == TYPE_COUNT) {
                mIntValue = Integer.parseInt(value);
                return true;
            }
            final float time = Float.parseFloat(value);
            final String unit = matcher.group(2);
            if (unit == null) {
                return false;
            }
            // Refer to art/libartbase/base/time_utils.cc
            switch (unit) {
                case "s":
                    mFloatValue = time * 1000;
                    break;
                case "ms":
                    mFloatValue = time;
                    break;
                case "us":
                    mFloatValue = time / 1000;
                    break;
                case "ns":
                    mFloatValue = time / 1000 / 1000;
                    break;
                default:
                    throw new IllegalArgumentException();
            }

            return true;
        }

        @Override
        public String toString() {
            return mRawPattern + (mType == TYPE_TIME ? (mFloatValue + "ms") : mIntValue);
        }
    }

    /** Parses the dump pattern of Heap::DumpGcPerformanceInfo. */
    private static class GcStatus {
        private static final int TOTAL_GC_TIME_INDEX = 1;
        private static final int TOTAL_GC_PAUSED_TIME_INDEX = 5;

        // Refer to art/runtime/gc/heap.cc
        final ValPattern[] mPatterns = {
                new ValPattern("Total GC count: ", ValPattern.TYPE_COUNT),
                new ValPattern("Total GC time: ", ValPattern.TYPE_TIME),
                new ValPattern("Total time waiting for GC to complete: ", ValPattern.TYPE_TIME),
                new ValPattern("Total blocking GC count: ", ValPattern.TYPE_COUNT),
                new ValPattern("Total blocking GC time: ", ValPattern.TYPE_TIME),
                new ValPattern("Total mutator paused time: ", ValPattern.TYPE_TIME),
                new ValPattern("Total number of allocations ", ValPattern.TYPE_COUNT),
                new ValPattern("concurrent copying paused:  Sum: ", ValPattern.TYPE_TIME),
                new ValPattern("concurrent copying total time: ", ValPattern.TYPE_TIME),
                new ValPattern("concurrent copying freed: ", ValPattern.TYPE_COUNT),
                new ValPattern("Peak regions allocated ", ValPattern.TYPE_COUNT),
        };

        void visit(String dumpLine) {
            for (ValPattern p : mPatterns) {
                if (p.visit(dumpLine)) {
                    if (DEBUG) {
                        Log.i(TAG, "  " + p);
                    }
                }
            }
        }

        GcInfo calculateGcTime(GcStatus newStatus, String title, Bundle result) {
            Log.i(TAG, "===== GC status of " + title + " =====");
            final GcInfo info = new GcInfo();
            for (int i = 0; i < mPatterns.length; i++) {
                final ValPattern p = mPatterns[i];
                if (p.mType == ValPattern.TYPE_COUNT) {
                    final int diff = newStatus.mPatterns[i].mIntValue - p.mIntValue;
                    Log.i(TAG, "  " + p.mRawPattern + diff);
                    if (diff > 0) {
                        result.putInt("[" + title + "] " + p.mRawPattern, diff);
                    }
                    continue;
                }
                final float diff = newStatus.mPatterns[i].mFloatValue - p.mFloatValue;
                Log.i(TAG, "  " + p.mRawPattern + diff + "ms");
                if (diff > 0) {
                    result.putFloat("[" + title + "] " + p.mRawPattern + "(ms)", diff);
                }
                if (i == TOTAL_GC_TIME_INDEX) {
                    info.mTotalGcTime = diff;
                } else if (i == TOTAL_GC_PAUSED_TIME_INDEX) {
                    info.mTotalGcPausedTime = diff;
                }
            }
            return info;
        }
    }

    private static class GcInfo {
        float mTotalGcTime;
        float mTotalGcPausedTime;
    }
}
