/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.graphics.perftests;

import android.graphics.Canvas;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.text.TextPaint;

import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@LargeTest
@RunWith(Parameterized.class)
public class PaintMeasureTextTest {

    private static final int USE_CACHE = 0;
    private static final int DONT_USE_CACHE = 1;

    @Parameterized.Parameters(name = "{0}")
    public static Collection measureSpecs() {
        return Arrays.asList(new Object[][] {
                { "alphabet_cached", USE_CACHE, "a" },
                { "alphabet_not_cached", DONT_USE_CACHE, "a" },
                // U+4E80 is an ideograph.
                { "ideograph_cached", USE_CACHE, "\u4E80" },
                { "ideograph_not_cached", DONT_USE_CACHE, "\u4E80" },
                // U+20B9F(\uD842\uDF9F) is an ideograph.
                { "surrogate_pairs_cached", USE_CACHE, "\uD842\uDF9F" },
                { "surrogate_pairs_not_cached", DONT_USE_CACHE, "\uD842\uDF9F" },
                // U+303D is PART ALTERNATION MARK
                { "emoji_cached", USE_CACHE, "\u231A" },
                { "emoji_not_cached", DONT_USE_CACHE, "\u231A" },
                // U+1F368(\uD83C\uDF68) is ICE CREAM
                { "emoji_surrogate_pairs_cached", USE_CACHE, "\uD83C\uDF68" },
                { "emoji_surrogate_pairs_not_cached", DONT_USE_CACHE, "\uD83C\uDF68" },
        });
    }

    private final String mText;
    private final int mCacheMode;

    public PaintMeasureTextTest(String key, int cacheMode, String text) {
        mText = text;
        mCacheMode = cacheMode;
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testMeasureTextPerf() {
        TextPaint paint = new TextPaint();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        if (mCacheMode == USE_CACHE) {
            paint.measureText(mText);
        } else {
            Canvas.freeTextLayoutCaches();
        }

        while (state.keepRunning()) {
            if (mCacheMode == DONT_USE_CACHE) {
                state.pauseTiming();
                Canvas.freeTextLayoutCaches();
                state.resumeTiming();
            }

            paint.measureText(mText);
        }
    }
}
