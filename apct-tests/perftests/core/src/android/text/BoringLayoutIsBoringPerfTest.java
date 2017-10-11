/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.graphics.Canvas;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.filters.LargeTest;
import android.text.NonEditableTextGenerator.TextType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * Performance test for {@link BoringLayout#isBoring(CharSequence, TextPaint)}.
 */
@LargeTest
@RunWith(Parameterized.class)
public class BoringLayoutIsBoringPerfTest {

    private static final boolean[] BOOLEANS = new boolean[]{false, true};

    @Parameterized.Parameters(name = "cached={4},{1}chars,{0}")
    public static Collection cases() {
        final List<Object[]> params = new ArrayList<>();
        for (int length : new int[]{128}) {
            for (boolean boring : BOOLEANS) {
                for (boolean cached : BOOLEANS) {
                    for (TextType textType : new TextType[]{TextType.STRING,
                            TextType.SPANNABLE_BUILDER}) {
                        params.add(new Object[]{
                                (boring ? "Boring" : "NotBoring") + "," + textType.name(),
                                length, boring, textType, cached});
                    }
                }
            }
        }
        return params;
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final int mLength;
    private final TextType mTextType;
    private final boolean mCreateBoring;
    private final boolean mCached;
    private final TextPaint mTextPaint;

    public BoringLayoutIsBoringPerfTest(String label, int length, boolean boring, TextType textType,
            boolean cached) {
        mLength = length;
        mCreateBoring = boring;
        mCached = cached;
        mTextType = textType;
        mTextPaint = new TextPaint();
        mTextPaint.setTextSize(10);
    }

    /**
     * Measure the time for the {@link BoringLayout#isBoring(CharSequence, TextPaint)}.
     */
    @Test
    public void timeIsBoring() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final CharSequence text = createRandomText();
        if (mCached) BoringLayout.isBoring(text, mTextPaint);
        state.resumeTiming();

        while (state.keepRunning()) {
            state.pauseTiming();
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            BoringLayout.isBoring(text, mTextPaint);
        }
    }

    private CharSequence createRandomText() {
        return new NonEditableTextGenerator(new Random(0))
                .setSequenceLength(mLength)
                .setCreateBoring(mCreateBoring)
                .setTextType(mTextType)
                .build();
    }
}
