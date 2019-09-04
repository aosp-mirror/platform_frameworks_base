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

import android.graphics.Paint;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.StubActivity;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@LargeTest
@RunWith(Parameterized.class)
public class PaintHasGlyphPerfTest {
    @Parameters(name = "{0}")
    public static Collection glyphStrings() {
        return Arrays.asList(new Object[][] {
            { "Latin", "A" },
            { "Ligature", "fi" },
            { "SurrogatePair", "\uD83D\uDE00" },  // U+1F600
            { "Flags", "\uD83C\uDDFA\uD83C\uDDF8" },  // US
            { "Ideograph_VariationSelector", "\u3402\uDB40\uDD00" },  // U+3402 U+E0100
            { "Emoji_VariationSelector", "\u00A9\uFE0F" },
            { "EmojiSequence",
              // U+1F468 U+200D U+2764 U+FE0F U+200D U+1F48B U+200D U+1F468
              "\uD83D\uDC68\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68" },
        });
    }

    private final String mQuery;

    public PaintHasGlyphPerfTest(String metricKey, String query) {
        mQuery = query;
    }

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule = new ActivityTestRule(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testHasGlyph() {
        Paint paint = new Paint();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            paint.hasGlyph(mQuery);
        }
    }
}
