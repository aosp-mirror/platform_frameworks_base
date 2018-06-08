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

import android.app.Activity;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
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
public class PrecomputedTextMemoryUsageTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final boolean NO_STYLE_TEXT = false;

    private static TextPaint PAINT = new TextPaint();

    private static int TRIAL_COUNT = 100;

    public PrecomputedTextMemoryUsageTest() {}

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    private void reportMemoryUsage(int memoryUsage, String key) {
        Bundle status = new Bundle();
        status.putInt(key + "_median", memoryUsage);
        InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    private int median(int[] values) {
        return values.length % 2 == 0 ?
                (values[values.length / 2] + values[values.length / 2 - 1]) / 2:
                values[values.length / 2];
    }

    @Test
    public void testMemoryUsage_NoHyphenation() {
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), param)
                .getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_NoHyphenation");
    }

    @Test
    public void testMemoryUsage_Hyphenation() {
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), param)
                .getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Hyphenation");
    }

    @Test
    public void testMemoryUsage_NoHyphenation_WidthOnly() {
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            CharSequence cs = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            PrecomputedText.ParagraphInfo[] paragraphInfo =
                    PrecomputedText.createMeasuredParagraphs(cs, param, 0, cs.length(), false);
            memories[i] = 0;
            for (PrecomputedText.ParagraphInfo info : paragraphInfo) {
                memories[i] += info.measured.getMemoryUsage();
            }
        }
        reportMemoryUsage(median(memories), "MemoryUsage_NoHyphenation_WidthOnly");
    }

    @Test
    public void testMemoryUsage_Hyphenatation_WidthOnly() {
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(PAINT)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            CharSequence cs = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            PrecomputedText.ParagraphInfo[] paragraphInfo =
                    PrecomputedText.createMeasuredParagraphs(cs, param, 0, cs.length(), false);
            memories[i] = 0;
            for (PrecomputedText.ParagraphInfo info : paragraphInfo) {
                memories[i] += info.measured.getMemoryUsage();
            }
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Hyphenation_WidthOnly");
    }
}
