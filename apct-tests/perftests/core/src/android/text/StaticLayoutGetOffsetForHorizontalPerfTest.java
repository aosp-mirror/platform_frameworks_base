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

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutGetOffsetForHorizontalPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 64;  // Roughly, 64 words in a line.
    private static final boolean NO_STYLE_TEXT = false;
    private static final boolean STYLE_TEXT = true;

    private static TextPaint PAINT = new TextPaint();
    static {
        PAINT.setTextSize(16.0f);
    }
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    public StaticLayoutGetOffsetForHorizontalPerfTest() {}

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    @Test
    public void testGetOffsetForHorizontal_LTR() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT,
                    "[a-zA-Z]");
            StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            state.resumeTiming();

            layout.getOffsetForHorizontal(0, TEXT_WIDTH / 2.0f);
        }
    }

    @Test
    public void testGetOffsetForHorizontal_RTL() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT,
                    "[:Arabic:]");
            StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            state.resumeTiming();

            layout.getOffsetForHorizontal(0, TEXT_WIDTH / 2.0f);
        }
    }

    @Test
    public void testGetOffsetForHorizontal_BiDi() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT,
                    "[[a-zA-Z][:Arabic:]]");
            StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            state.resumeTiming();

            layout.getOffsetForHorizontal(0, TEXT_WIDTH / 2.0f);
        }
    }
}
