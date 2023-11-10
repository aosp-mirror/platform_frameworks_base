/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.test.tinyframework;

import org.junit.Test;

import java.text.DecimalFormat;

/**
 * Contains simple micro-benchmarks.
 */
@LargeTest
public class TinyFrameworkBenchmark {
    private static final int MINIMAL_ITERATION = 1000;
    private static final int MEASURE_SECONDS = 1;

    private static final DecimalFormat sFormatter = new DecimalFormat("#,###");

    private void doBenchmark(String name, Runnable r) {
        // Worm up
        for (int i = 0; i < MINIMAL_ITERATION; i++) {
            r.run();
        }

        // Start measuring.
        final long start = System.nanoTime();
        final long end = start + MEASURE_SECONDS * 1_000_000_000L;

        double iteration = 0;
        while (System.nanoTime() <= end) {
            for (int i = 0; i < MINIMAL_ITERATION; i++) {
                r.run();
            }
            iteration += MINIMAL_ITERATION;
        }

        final long realEnd = System.nanoTime();

        System.out.println(String.format("%s\t%s", name,
                sFormatter.format((((double) realEnd - start)) / iteration)));
    }

    /**
     * Micro-benchmark for a method without a non-stub caller check.
     */
    @Test
    public void benchNoCallerCheck() {
        doBenchmark("No caller check", TinyFrameworkCallerCheck::getOne_noCheck);
    }

    /**
     * Micro-benchmark for a method with a non-stub caller check.
     */
    @Test
    public void benchWithCallerCheck() {
        doBenchmark("With caller check", TinyFrameworkCallerCheck::getOne_withCheck);
    }
}
