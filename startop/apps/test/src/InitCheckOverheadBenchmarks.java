/*
 * Copyright (C) 2019 The Android Open Source Project
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

/**
 *  A test of initialization check costs for AOT.
 */

package com.android.startop.test;

import android.app.Activity;

import java.util.Random;

public class InitCheckOverheadBenchmarks {
    public static int mSum;
    public static int mSum2;
    public static int mStep;
    public static int mStep2;
    public static int mStartingValue;

    static {
        Random random = new Random();
        mStep = random.nextInt();
        mStep2 = random.nextInt();
        mStartingValue = random.nextInt();
    };

    static class OtherClass {
        public static int mStep;
        public static int mStep2;
        public static int mStartingValue;
        static {
            Random random = new Random();
            mStep = random.nextInt();
            mStep2 = random.nextInt();
            mStartingValue = random.nextInt();
        };
    };

    public static void localStaticFor(int iterationCount) {
        for (int i = 0; i < iterationCount; ++i) {
            mSum += mStep;
        }
    }

    public static void nonLocalStaticFor(int iterationCount) {
        mSum = OtherClass.mStartingValue;
        for (int i = 0; i < iterationCount; ++i) {
            mSum += OtherClass.mStep;
        }
    }

    public static void localStaticForTwo(int iterationCount) {
        for (int i = 0; i < iterationCount; ++i) {
            mSum += mStep;
            mSum2 += mStep2;
        }
    }

    public static void nonLocalStaticForTwo(int iterationCount) {
        mSum = OtherClass.mStartingValue;
        for (int i = 0; i < iterationCount; ++i) {
            mSum += OtherClass.mStep;
            mSum2 += OtherClass.mStep2;
        }
    }

    public static void localStaticDoWhile(int iterationCount) {
        int i = 0;
        do {
            mSum += mStep;
            ++i;
        } while (i < iterationCount);
    }

    public static void nonLocalStaticDoWhile(int iterationCount) {
        mSum = OtherClass.mStartingValue;
        int i = 0;
        do {
            mSum += OtherClass.mStep;
            ++i;
        } while (i < iterationCount);
    }

    public static void doGC() {
        Runtime.getRuntime().gc();
    }

    // Time limit to run benchmarks in seconds
    public static final int TIME_LIMIT = 5;

    static void initializeBenchmarks(Activity parent, BenchmarkRunner benchmarks) {
        benchmarks.addBenchmark("GC", () -> {
            doGC();
        });

        benchmarks.addBenchmark("InitCheckFor (local)", () -> {
            localStaticFor(10000000);
        });

        benchmarks.addBenchmark("InitCheckFor (non-local)", () -> {
            nonLocalStaticFor(10000000);
        });

        benchmarks.addBenchmark("InitCheckForTwo (local)", () -> {
            localStaticForTwo(10000000);
        });

        benchmarks.addBenchmark("InitCheckForTwo (non-local)", () -> {
            nonLocalStaticForTwo(10000000);
        });

        benchmarks.addBenchmark("InitCheckDoWhile (local)", () -> {
            localStaticDoWhile(10000000);
        });

        benchmarks.addBenchmark("InitCheckDoWhile (non-local)", () -> {
            nonLocalStaticDoWhile(10000000);
        });
    }
}
