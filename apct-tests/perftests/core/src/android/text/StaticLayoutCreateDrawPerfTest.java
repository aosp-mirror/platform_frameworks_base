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

import static android.text.Layout.Alignment.ALIGN_NORMAL;

import android.graphics.Canvas;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.filters.LargeTest;
import android.text.NonEditableTextGenerator.TextType;
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
 * Performance test for multi line, single style {@link StaticLayout} creation/draw.
 */
@LargeTest
@RunWith(Parameterized.class)
public class StaticLayoutCreateDrawPerfTest {

    private static final boolean[] BOOLEANS = new boolean[]{false, true};

    private static final float SPACING_ADD = 10f;
    private static final float SPACING_MULT = 1.5f;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameters(name = "cached={3},{1}chars,{0}")
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

    public StaticLayoutCreateDrawPerfTest(String label, int length, TextType textType,
            boolean cached) {
        mLength = length;
        mTextType = textType;
        mCached = cached;
        mTextPaint = new TextPaint();
        mTextPaint.setTextSize(10);
        mLineWidth = Integer.MAX_VALUE;
    }

    /**
     * Measures the creation time for a multi line {@link StaticLayout}.
     */
    @Test
    public void timeCreate() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final CharSequence text = createRandomText(mLength);
        createLayout(text);
        state.resumeTiming();

        while (state.keepRunning()) {
            state.pauseTiming();
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            createLayout(text);
        }
    }

    /**
     * Measures the draw time for a multi line {@link StaticLayout}.
     */
    @Test
    public void timeDraw() throws Exception {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        state.pauseTiming();
        Canvas.freeTextLayoutCaches();
        final RenderNode node = RenderNode.create("benchmark", null);
        final CharSequence text = createRandomText(mLength);
        final Layout layout = createLayout(text);
        state.resumeTiming();

        while (state.keepRunning()) {

            state.pauseTiming();
            final DisplayListCanvas canvas = node.start(1200, 200);
            int save = canvas.save();
            if (!mCached) Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(canvas);

            state.pauseTiming();
            canvas.restoreToCount(save);
            node.end(canvas);
            state.resumeTiming();
        }
    }

    private Layout createLayout(CharSequence text) {
        return StaticLayout.Builder.obtain(text, 0 /*start*/, text.length() /*end*/, mTextPaint,
                mLineWidth)
                .setAlignment(ALIGN_NORMAL)
                .setIncludePad(true)
                .setLineSpacing(SPACING_ADD, SPACING_MULT)
                .build();
    }

    private CharSequence createRandomText(int length) {
        return new NonEditableTextGenerator(new Random(0))
                .setSequenceLength(length)
                .setTextType(mTextType)
                .build();
    }
}
