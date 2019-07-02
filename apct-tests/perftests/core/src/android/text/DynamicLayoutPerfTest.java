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

import static android.text.Layout.Alignment.ALIGN_NORMAL;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.StubActivity;
import android.text.style.ReplacementSpan;
import android.util.ArraySet;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

@LargeTest
@RunWith(Parameterized.class)
public class DynamicLayoutPerfTest {

    @Parameters(name = "{0}")
    public static Collection cases() {
        return Arrays.asList(new Object[][] {
            { "0%", 0.0f},
            { "1%", 0.01f},
            { "5%", 0.05f},
            { "30%", 0.3f},
            { "100%", 1.0f},
        });
    }

    private final String mMetricKey;
    private final float mProbability;
    public DynamicLayoutPerfTest(String metricKey, float probability) {
        mMetricKey = metricKey;
        mProbability = probability;
    }

    private static class MockReplacementSpan extends ReplacementSpan {
        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, FontMetricsInt fm) {
            return 10;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                int y, int bottom, Paint paint) {
        }
    }

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule = new ActivityTestRule(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();


    private final static String ALPHABETS = "abcdefghijklmnopqrstuvwxyz";

    private SpannableStringBuilder getText() {
        final long seed = 1234567890;
        final Random r = new Random(seed);
        final SpannableStringBuilder builder = new SpannableStringBuilder();

        final int paragraphCount = 100;
        for (int i = 0; i < paragraphCount; i++) {
            final int wordCount = 5 + r.nextInt(20);
            final boolean containsReplacementSpan = r.nextFloat() < mProbability;
            final int replacedWordIndex = containsReplacementSpan ? r.nextInt(wordCount) : -1;
            for (int j = 0; j < wordCount; j++) {
                final int startIndex = builder.length();
                final int wordLength = 1 + r.nextInt(10);
                for (int k = 0; k < wordLength; k++) {
                    char c = ALPHABETS.charAt(r.nextInt(ALPHABETS.length()));
                    builder.append(c);
                }
                if (replacedWordIndex == j) {
                    builder.setSpan(new MockReplacementSpan(), startIndex,
                            builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                builder.append(' ');
            }
            builder.append('\n');
        }
        return builder;
    }

    @Test
    public void testGetBlocksAlwaysNeedToBeRedrawn() {
        final SpannableStringBuilder text = getText();
        final DynamicLayout layout = new DynamicLayout(text, new TextPaint(), 1000,
                ALIGN_NORMAL, 0, 0, false);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final int steps = 10;
        while (state.keepRunning()) {
            for (int i = 0; i < steps; i++) {
                int offset = (text.length() * i) / steps;
                text.insert(offset, "\n");
                text.delete(offset, offset + 1);
                final ArraySet<Integer> set = layout.getBlocksAlwaysNeedToBeRedrawn();
                if (set != null) {
                    for (int j = 0; j < set.size(); j++) {
                        layout.getBlockIndex(set.valueAt(j));
                    }
                }
            }
        }
    }
}
