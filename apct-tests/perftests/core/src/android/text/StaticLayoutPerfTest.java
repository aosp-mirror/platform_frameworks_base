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
public class StaticLayoutPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 8;  // Roughly, 8 words in a line.
    private static final boolean NO_STYLE_TEXT = false;
    private static final boolean STYLE_TEXT = true;

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    public StaticLayoutPerfTest() {}

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    private PrecomputedText makeMeasured(CharSequence text, TextPaint paint) {
        PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint).build();
        return PrecomputedText.create(text, param);
    }

    private PrecomputedText makeMeasured(CharSequence text, TextPaint paint, int strategy,
                                      int frequency) {
        PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setHyphenationFrequency(frequency).setBreakStrategy(strategy).build();
        return PrecomputedText.create(text, param);
    }

    @Test
    public void testCreate_FixedText_NoStyle_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
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
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
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
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
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
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
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
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
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
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NONE);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Greedy_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NORMAL);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Balanced_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_BALANCED, Layout.HYPHENATION_FREQUENCY_NONE);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Balanced_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_BALANCED, Layout.HYPHENATION_FREQUENCY_NORMAL);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_Styled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NONE);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testDraw_FixedText_NoStyled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_RandomText_Styled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_RandomText_NoStyled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_RandomText_Styled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_RandomText_NoStyled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_PrecomputedText_Styled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_PrecomputedText_NoStyled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_PrecomputedText_Styled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
        }
    }

    @Test
    public void testDraw_PrecomputedText_NoStyled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final DisplayListCanvas c = node.start(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
        }
    }

}
