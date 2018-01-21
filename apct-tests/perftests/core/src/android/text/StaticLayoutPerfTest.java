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

import static android.text.TextDirectionHeuristics.LTR;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.style.TextAppearanceSpan;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.CharBuffer;
import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutPerfTest {

    public StaticLayoutPerfTest() {}

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 8;  // Roughly, 8 words in a line.
    private static final int PARA_LENGTH = 500;  // Number of characters in a paragraph.

    private static final boolean NO_STYLE_TEXT = false;
    private static final boolean STYLE_TEXT = true;

    private Random mRandom;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ALPHABET_LENGTH = ALPHABET.length();

    private static final ColorStateList TEXT_COLOR = ColorStateList.valueOf(0x00000000);
    private static final String[] FAMILIES = { "sans-serif", "serif", "monospace" };
    private static final int[] STYLES = {
            Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC
    };

    private final char[] mBuffer = new char[PARA_LENGTH];

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    private CharSequence generateRandomParagraph(int wordLen, boolean applyRandomStyle) {
        for (int i = 0; i < PARA_LENGTH; i++) {
            if (i % (wordLen + 1) == wordLen) {
                mBuffer[i] = ' ';
            } else {
                mBuffer[i] = ALPHABET.charAt(mRandom.nextInt(ALPHABET_LENGTH));
            }
        }

        CharSequence cs = CharBuffer.wrap(mBuffer);
        if (!applyRandomStyle) {
            return cs;
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(cs);
        for (int i = 0; i < ssb.length(); i += WORD_LENGTH) {
            final int spanStart = i;
            final int spanEnd = (i + WORD_LENGTH) > ssb.length() ? ssb.length() : i + WORD_LENGTH;

            final TextAppearanceSpan span = new TextAppearanceSpan(
                  FAMILIES[mRandom.nextInt(FAMILIES.length)],
                  STYLES[mRandom.nextInt(STYLES.length)],
                  24 + mRandom.nextInt(32),  // text size. min 24 max 56
                  TEXT_COLOR, TEXT_COLOR);

            ssb.setSpan(span, spanStart, spanEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }
        return ssb;
    }

    @Before
    public void setUp() {
        mRandom = new Random(0);
    }

    @Test
    public void testCreate_FixedText_NoStyle_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        while (state.keepRunning()) {
            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Greedy_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Balanced_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Balanced_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_Styled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = generateRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_MeasuredText_NoStyled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final MeasuredText text = new MeasuredText.Builder(
                    generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build();
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_MeasuredText_NoStyled_Greedy_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final MeasuredText text = new MeasuredText.Builder(
                    generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build();
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_MeasuredText_NoStyled_Balanced_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final MeasuredText text = new MeasuredText.Builder(
                    generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build();
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_MeasuredText_NoStyled_Balanced_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final MeasuredText text = new MeasuredText.Builder(
                    generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build();
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_MeasuredText_Styled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final MeasuredText text = new MeasuredText.Builder(
                    generateRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build();
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }
}
