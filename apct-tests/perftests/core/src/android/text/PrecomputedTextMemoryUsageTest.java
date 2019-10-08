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

import android.app.Activity;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class PrecomputedTextMemoryUsageTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final boolean NO_STYLE_TEXT = false;

    private static int TRIAL_COUNT = 10;

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
    public void testMemoryUsage_Latin_NoHyphenation() {
        TextPaint paint = new TextPaint();
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), param)
                .getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Latin_NoHyphenation");
    }

    @Test
    public void testMemoryUsage_Latin_Hyphenation() {
        TextPaint paint = new TextPaint();
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), param)
                .getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Latin_Hyphenation");
    }

    @Test
    public void testMemoryUsage_CJK_NoHyphenation() {
        TextPaint paint = new TextPaint();
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT, "[\\u4E00-\\u9FA0]"),
                    param).getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_CJK_NoHyphenation");
    }

    @Test
    public void testMemoryUsage_CJK_Hyphenation() {
        TextPaint paint = new TextPaint();
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT, "[\\u4E00-\\u9FA0]"),
                    param).getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_CJK_Hyphenation");
    }

    @Test
    public void testMemoryUsage_Arabic_NoHyphenation() {
        TextPaint paint = new TextPaint();
        paint.setTextLocale(Locale.forLanguageTag("ar"));
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT, "[\\u0600-\\u06FF]"),
                    param).getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Arabic_NoHyphenation");
    }

    @Test
    public void testMemoryUsage_Arabic_Hyphenation() {
        TextPaint paint = new TextPaint();
        paint.setTextLocale(Locale.forLanguageTag("ar"));
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT, "[\\u0600-\\u06FF]"),
                    param).getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Arabic_Hyphenation");
    }
    @Test
    public void testMemoryUsage_Emoji() {
        TextPaint paint = new TextPaint();
        int[] memories = new int[TRIAL_COUNT];
        final PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();

        // Report median of randomly generated PrecomputedText.
        for (int i = 0; i < TRIAL_COUNT; ++i) {
            memories[i] = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT, "[:emoji:]"),
                    param).getMemoryUsage();
        }
        reportMemoryUsage(median(memories), "MemoryUsage_Emoji_NoHyphenation");
    }
}
