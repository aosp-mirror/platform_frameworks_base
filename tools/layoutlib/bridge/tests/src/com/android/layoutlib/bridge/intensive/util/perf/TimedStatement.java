/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive.util.perf;

import com.android.layoutlib.bridge.intensive.util.TestUtils;

import org.junit.runners.model.Statement;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/**
 * JUnit {@link Statement} used to measure some statistics about the test method.
 */
public class TimedStatement extends Statement {
    private static final int CALIBRATION_WARMUP_ITERATIONS = 50;
    private static final int CALIBRATION_RUNS = 100;

    private static boolean sIsCalibrated;
    private static double sCalibrated;

    private final Statement mStatement;
    private final int mWarmUpIterations;
    private final int mRuns;
    private final Runtime mRuntime = Runtime.getRuntime();
    private final Consumer<TimedStatementResult> mCallback;

    TimedStatement(Statement statement, int warmUpIterations, int runs,
            Consumer<TimedStatementResult> finishedCallback) {
        mStatement = statement;
        mWarmUpIterations = warmUpIterations;
        mRuns = runs;
        mCallback = finishedCallback;
    }

    /**
     * The calibrate method tries to do some work that involves IO, memory allocations and some
     * operations on the randomly generated data to calibrate the speed of the machine with
     * something that resembles the execution of a test case.
     */
    private static void calibrateMethod() throws IOException {
        File tmpFile = File.createTempFile("test", "file");
        Random rnd = new Random();
        HashFunction hashFunction = Hashing.sha512();
        for (int i = 0; i < 5 + rnd.nextInt(5); i++) {
            FileOutputStream stream = new FileOutputStream(tmpFile);
            int bytes = 30000 + rnd.nextInt(60000);
            byte[] buffer = new byte[bytes];

            rnd.nextBytes(buffer);
            byte acc = 0;
            for (int j = 0; j < bytes; j++) {
                acc += buffer[i];
            }
            buffer[0] = acc;
            stream.write(buffer);
            System.gc();
            stream.close();
            FileInputStream input = new FileInputStream(tmpFile);
            byte[] readBuffer = new byte[bytes];
            //noinspection ResultOfMethodCallIgnored
            input.read(readBuffer);
            buffer = readBuffer;
            HashCode code1 = hashFunction.hashBytes(buffer);
            Arrays.sort(buffer);
            HashCode code2 = hashFunction.hashBytes(buffer);
            input.close();

            FileOutputStream hashStream = new FileOutputStream(tmpFile);
            hashStream.write(code1.asBytes());
            hashStream.write(code2.asBytes());
            hashStream.close();
        }
    }

    /**
     * Runs the calibration process and sets the calibration measure in {@link #sCalibrated}
     */
    private static void doCalibration() throws IOException {
        System.out.println("Calibrating ...");
        TestUtils.gc();
        for (int i = 0; i < CALIBRATION_WARMUP_ITERATIONS; i++) {
            calibrateMethod();
        }

        LongStatsCollector stats = new LongStatsCollector(CALIBRATION_RUNS);
        for (int i = 0; i < CALIBRATION_RUNS; i++) {
            TestUtils.gc();
            long start = System.currentTimeMillis();
            calibrateMethod();
            stats.accept(System.currentTimeMillis() - start);
        }

        sCalibrated = stats.getStats().getMedian();
        sIsCalibrated = true;
        System.out.printf("  DONE %fms\n", sCalibrated);
    }

    private long getUsedMemory() {
        return mRuntime.totalMemory() - mRuntime.freeMemory();
    }


    @Override
    public void evaluate() throws Throwable {
        if (!sIsCalibrated) {
            doCalibration();
        }

        for (int i = 0; i < mWarmUpIterations; i++) {
            mStatement.evaluate();
        }

        LongStatsCollector timeStats = new LongStatsCollector(mRuns);
        LongStatsCollector memoryUseStats = new LongStatsCollector(mRuns);
        AtomicBoolean collectSamples = new AtomicBoolean(false);

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        TestUtils.gc();
        executorService.scheduleAtFixedRate(() -> {
            if (!collectSamples.get()) {
                return;
            }
            memoryUseStats.accept(getUsedMemory());
        }, 0, 200, TimeUnit.MILLISECONDS);

        try {
            for (int i = 0; i < mRuns; i++) {
                TestUtils.gc();
                collectSamples.set(true);
                long startTimeMs = System.currentTimeMillis();
                mStatement.evaluate();
                long stopTimeMs = System.currentTimeMillis();
                collectSamples.set(true);
                timeStats.accept(stopTimeMs - startTimeMs);

            }
        } finally {
            executorService.shutdownNow();
        }

        TimedStatementResult result = new TimedStatementResult(
                mWarmUpIterations,
                mRuns,
                sCalibrated,
                timeStats.getStats(),
                memoryUseStats.getStats());
        mCallback.accept(result);
    }

}
