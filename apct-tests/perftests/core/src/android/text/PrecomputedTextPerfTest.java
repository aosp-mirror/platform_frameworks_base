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

import static android.text.TextDirectionHeuristics.LTR;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.style.TextAppearanceSpan;
import android.view.DisplayListCanvas;
import android.view.RenderNode;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.CharBuffer;
import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class PrecomputedTextPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 8;  // Roughly, 8 words in a line.
    private static final boolean NO_STYLE_TEXT = false;
    private static final boolean STYLE_TEXT = true;

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    public PrecomputedTextPerfTest() {}

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    @Test
    public void testCreate_NoStyled_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_NoStyled_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_NoStyled_Hyphenation_WidthOnly() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_NoStyled_NoHyphenation_WidthOnly() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_Styled_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_Styled_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_Styled_Hyphenation_WidthOnly() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }

    @Test
    public void testCreate_Styled_NoHyphenation_WidthOnly() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            PrecomputedText.create(text, param);
        }
    }
}
