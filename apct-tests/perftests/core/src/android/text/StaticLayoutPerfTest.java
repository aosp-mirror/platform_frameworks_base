/*
 * Copyright (C) 2016 The Android Open Source Project
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

import java.nio.CharBuffer;
import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutPerfTest {

    public StaticLayoutPerfTest() {
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final String FIXED_TEXT = "Lorem ipsum dolor sit amet, consectetur adipiscing "
            + "elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad "
            + "minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea "
            + "commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse "
            + "cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non "
            + "proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";
    private static final int FIXED_TEXT_LENGTH = FIXED_TEXT.length();

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = 20 * (int) PAINT.getTextSize();

    @Test
    public void testCreate() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            StaticLayout.Builder.obtain(FIXED_TEXT, 0, FIXED_TEXT_LENGTH, PAINT, TEXT_WIDTH)
                    .build();
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ALPHABET_LENGTH = ALPHABET.length();

    private static final int PARA_LENGTH = 500;
    private final char[] mBuffer = new char[PARA_LENGTH];
    private final Random mRandom = new Random(31415926535L);

    private CharSequence generateRandomParagraph(int wordLen) {
        for (int i = 0; i < PARA_LENGTH; i++) {
            if (i % (wordLen + 1) == wordLen) {
                mBuffer[i] = ' ';
            } else {
                mBuffer[i] = ALPHABET.charAt(mRandom.nextInt(ALPHABET_LENGTH));
            }
        }
        return CharBuffer.wrap(mBuffer);
    }

    // This tries to simulate the case where the cache hit rate is low, and most of the text is
    // new text.
    @Test
    public void testCreateRandom() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            final CharSequence text = generateRandomParagraph(9);
            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .build();
        }
    }
}
