/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutMultithreadPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 8;  // Roughly, 8 words in a line.
    private static final boolean NO_STYLE_TEXT = false;

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    public StaticLayoutMultithreadPerfTest() {}

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private CountDownLatch mStartLatch;
    private AtomicBoolean mThreadState;  // True for running, False for stopped.

    private static final long TIMEOUT_MS = 5000;

    private Thread[] startBackgroundThread(int numOfThreads) {
        mStartLatch = new CountDownLatch(numOfThreads);
        mThreadState = new AtomicBoolean(true);

        Thread[] threads = new Thread[numOfThreads];
        for (int i = 0; i < numOfThreads; ++i) {
            final int seed = i + 1;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    final TextPerfUtils util = new TextPerfUtils();
                    util.resetRandom(seed);

                    mStartLatch.countDown();
                    while (mThreadState.get()) {
                        final CharSequence text = util.nextRandomParagraph(
                                WORD_LENGTH, NO_STYLE_TEXT);
                        StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                                .build();
                    }
                }
            });
        }

        for (int i = 0; i < numOfThreads; ++i) {
            threads[i].start();
        }

        try {
            mStartLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return threads;
    }

    private void finishThreads(Thread[] threads) {
        mThreadState.set(false);
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        mStartLatch = null;
        mThreadState = null;
    }

    private void runRandomTest(int numOfTotalThreads) {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final TextPerfUtils util = new TextPerfUtils();
        Thread[] threads = startBackgroundThread(numOfTotalThreads - 1);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = util.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
        finishThreads(threads);
    }

    @Test
    public void testCreate_RandomText_Thread_1() {
        runRandomTest(1);
    }

    @Test
    public void testCreate_RandomText_Thread_2() {
        runRandomTest(2);
    }

    @Test
    public void testCreate_RandomText_Thread_4() {
        runRandomTest(4);
    }
}
