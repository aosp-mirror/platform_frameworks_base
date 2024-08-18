/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.text;

import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class VariableFontPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final boolean NO_STYLE_TEXT = false;

    private static final TextPaint PAINT = new TextPaint();

    public VariableFontPerfTest() {}

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    @Test
    public void testDraw_SetVariationOnce() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        final Paint paint = new Paint(PAINT);
        paint.setFontVariationSettings("'wght' 700");
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            state.resumeTiming();

            c.drawText(text, 0, text.length(), 0, 100, paint);

            state.pauseTiming();
            node.endRecording();
            state.resumeTiming();

        }
    }

    @Test
    public void testDraw_SetVariationEachDraw() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        final Paint paint = new Paint(PAINT);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            paint.setFontVariationSettings("'wght' 700");
            state.resumeTiming();

            c.drawText(text, 0, text.length(), 0, 100, paint);

            state.pauseTiming();
            node.endRecording();
            state.resumeTiming();

        }
    }

    @Test
    public void testDraw_SetDifferentVariationEachDraw() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        final Paint paint = new Paint(PAINT);
        final RenderNode node = RenderNode.create("benchmark", null);
        final Random random = new Random(0);
        while (state.keepRunning()) {
            state.pauseTiming();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            int weight = random.nextInt(1000);
            paint.setFontVariationSettings("'wght' " + weight);
            state.resumeTiming();

            c.drawText(text, 0, text.length(), 0, 100, paint);

            state.pauseTiming();
            node.endRecording();
            state.resumeTiming();
        }
    }

    @Test
    public void testSetFontVariationSettings() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Paint paint = new Paint(PAINT);
        while (state.keepRunning()) {
            state.pauseTiming();
            paint.setTypeface(null);
            paint.setFontVariationSettings(null);
            Typeface.clearTypefaceCachesForTestingPurpose();
            state.resumeTiming();

            paint.setFontVariationSettings("'wght' 450");
        }
        Typeface.clearTypefaceCachesForTestingPurpose();
    }

    @Test
    public void testSetFontVariationSettings_Cached() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Paint paint = new Paint(PAINT);
        Typeface.clearTypefaceCachesForTestingPurpose();

        while (state.keepRunning()) {
            state.pauseTiming();
            paint.setTypeface(null);
            paint.setFontVariationSettings(null);
            state.resumeTiming();

            paint.setFontVariationSettings("'wght' 450");
        }

        Typeface.clearTypefaceCachesForTestingPurpose();
    }

}
