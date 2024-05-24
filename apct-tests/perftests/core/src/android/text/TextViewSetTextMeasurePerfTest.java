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

import static android.view.View.MeasureSpec.AT_MOST;
import static android.view.View.MeasureSpec.UNSPECIFIED;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.text.NonEditableTextGenerator.TextType;
import android.view.View;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Performance test for {@link TextView} measure/draw.
 */
@LargeTest
@RunWith(Parameterized.class)
public class TextViewSetTextMeasurePerfTest {

    private static final boolean[] BOOLEANS = new boolean[]{false, true};

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameters(name = "cached {3} {1}chars {0}")
    public static Collection cases() {
        final List<Object[]> params = new ArrayList<>();
        for (int length : new int[]{128}) {
            for (boolean cached : BOOLEANS) {
                for (TextType textType : new TextType[]{TextType.STRING,
                        TextType.SPANNABLE_BUILDER}) {
                    params.add(new Object[]{textType.name(), length, textType, cached});
                }
            }
        }
        return params;
    }

    private final int mLineWidth;
    private final int mLength;
    private final TextType mTextType;
    private final boolean mCached;
    private final TextPaint mTextPaint;

    public TextViewSetTextMeasurePerfTest(String label, int length, TextType textType,
            boolean cached) {
        mLength = length;
        mTextType = textType;
        mCached = cached;
        mTextPaint = new TextPaint();
        mTextPaint.setTextSize(10);
        mLineWidth = 2048;
    }

    /**
     * Measures the time to setText and measure for a {@link TextView}.
     */
    @Test
    public void timeCreate() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final CharSequence text = createRandomText(mLength);
        final TextView textView = new TextView(InstrumentationRegistry.getTargetContext());
        textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);

        textView.setText(text);
        state.resumeTiming();

        while (state.keepRunning()) {
            state.pauseTiming();
            textView.setTextLocale(Locale.UK);
            textView.setTextLocale(Locale.US);
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.setText(text);
            textView.measure(
                    View.MeasureSpec.makeMeasureSpec(mLineWidth, AT_MOST),
                    UNSPECIFIED);
        }
    }

    /**
     * Measures the time to draw for a {@link TextView}.
     */
    @Test
    public void timeDraw() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final RenderNode node = RenderNode.create("benchmark", null);
        final CharSequence text = createRandomText(mLength);
        final TextView textView = new TextView(InstrumentationRegistry.getTargetContext());
        textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        textView.setText(text);
        state.resumeTiming();

        while (state.keepRunning()) {

            state.pauseTiming();
            textView.setTextLocale(Locale.UK);
            textView.setTextLocale(Locale.US);
            textView.measure(
                    View.MeasureSpec.makeMeasureSpec(mLineWidth, AT_MOST),
                    UNSPECIFIED);
            RectF bounds = textView.getLayout().computeDrawingBoundingBox();
            final RecordingCanvas canvas = node.start(
                    (int) Math.ceil(bounds.width()),
                    (int) Math.ceil(bounds.height()));
            int save = canvas.save();
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.draw(canvas);

            state.pauseTiming();
            canvas.restoreToCount(save);
            node.end(canvas);
            state.resumeTiming();
        }
    }

    private CharSequence createRandomText(int length) {
        return new NonEditableTextGenerator(new Random(0))
                .setSequenceLength(length)
                .setCreateBoring(false)
                .setTextType(mTextType)
                .build();
    }
}
