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
import android.graphics.Paint;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.filters.LargeTest;
import android.view.DisplayListCanvas;
import android.view.RenderNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * Performance test for single line measure and draw using {@link Paint} and {@link Canvas}.
 */
@LargeTest
@RunWith(Parameterized.class)
public class PaintMeasureDrawPerfTest {

    private static final boolean[] BOOLEANS = new boolean[]{false, true};

    @Parameterized.Parameters(name = "cached={1},{0}chars")
    public static Collection cases() {
        final List<Object[]> params = new ArrayList<>();
        for (int length : new int[]{128}) {
            for (boolean cached : BOOLEANS) {
                params.add(new Object[]{length, cached});
            }
        }
        return params;
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final int mLength;
    private final boolean mCached;
    private final TextPaint mTextPaint;


    public PaintMeasureDrawPerfTest(int length, boolean cached) {
        mLength = length;
        mCached = cached;
        mTextPaint = new TextPaint();
        mTextPaint.setTextSize(10);
    }

    /**
     * Measure the time for {@link Paint#measureText(String)}
     */
    @Test
    public void timeMeasure() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final String text = createRandomText();
        if (mCached) mTextPaint.measureText(text);
        state.resumeTiming();

        while (state.keepRunning()) {
            state.pauseTiming();
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            mTextPaint.measureText(text);
        }
    }

    /**
     * Measures the time for {@link Canvas#drawText(String, float, float, Paint)}
     */
    @Test
    public void timeDraw() throws Throwable {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final RenderNode node = RenderNode.create("benchmark", null);
        final String text = createRandomText();
        if (mCached) mTextPaint.measureText(text);
        state.resumeTiming();

        while (state.keepRunning()) {

            state.pauseTiming();
            final DisplayListCanvas canvas = node.start(1200, 200);
            final int save = canvas.save();
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            canvas.drawText(text, 0 /*x*/, 100 /*y*/, mTextPaint);

            state.pauseTiming();
            canvas.restoreToCount(save);
            node.end(canvas);
            state.resumeTiming();
        }
    }

    private String createRandomText() {
        return (String) new NonEditableTextGenerator(new Random(0))
                .setSequenceLength(mLength)
                .setCreateBoring(true)
                .setTextType(NonEditableTextGenerator.TextType.STRING)
                .build();
    }
}
