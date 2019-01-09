/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.widget;

import static android.widget.TextView.UNKNOWN_BORING;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.PrecomputedText;
import android.text.TextPaint;
import android.text.TextPerfUtils;
import android.view.View.MeasureSpec;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class TextViewPrecomputedTextPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 8;  // Roughly, 8 words in a line.
    private static final boolean NO_STYLE_TEXT = false;
    private static final boolean STYLE_TEXT = true;

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    public TextViewPrecomputedTextPerfTest() {}

    private static class TestableTextView extends TextView {
        public TestableTextView(Context ctx) {
            super(ctx);
        }

        public void onMeasure(int w, int h) {
            super.onMeasure(w, h);
        }

        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
        }
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    private static Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void testNewLayout_RandomText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TextView textView = new TextView(getContext());
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            textView.setText(text);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.makeNewLayout(TEXT_WIDTH, TEXT_WIDTH, UNKNOWN_BORING, UNKNOWN_BORING,
                TEXT_WIDTH, false);
        }
    }

    @Test
    public void testNewLayout_RandomText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TextView textView = new TextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            textView.setText(text);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.makeNewLayout(TEXT_WIDTH, TEXT_WIDTH, UNKNOWN_BORING, UNKNOWN_BORING,
                TEXT_WIDTH, false);
        }
    }

    @Test
    public void testNewLayout_PrecomputedText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TextView textView = new TextView(getContext());
            textView.setTextMetricsParams(params);
            textView.setText(text);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.makeNewLayout(TEXT_WIDTH, TEXT_WIDTH, UNKNOWN_BORING, UNKNOWN_BORING,
                TEXT_WIDTH, false);
        }
    }

    @Test
    public void testNewLayout_PrecomputedText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TextView textView = new TextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setTextMetricsParams(params);
            textView.setText(text);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.makeNewLayout(TEXT_WIDTH, TEXT_WIDTH, UNKNOWN_BORING, UNKNOWN_BORING,
                TEXT_WIDTH, false);
        }
    }

    @Test
    public void testSetText_RandomText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TextView textView = new TextView(getContext());
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.setText(text);
        }
    }

    @Test
    public void testSetText_RandomText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TextView textView = new TextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.setText(text);
        }
    }

    @Test
    public void testSetText_PrecomputedText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TextView textView = new TextView(getContext());
            textView.setTextMetricsParams(params);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.setText(text);
        }
    }

    @Test
    public void testSetText_PrecomputedText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        BoringLayout.Metrics metrics = new BoringLayout.Metrics();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TextView textView = new TextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setTextMetricsParams(params);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.setText(text);
        }
    }

    @Test
    public void testOnMeasure_RandomText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            textView.setText(text);
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onMeasure(width, height);
        }
    }

    @Test
    public void testOnMeasure_RandomText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            textView.setText(text);
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onMeasure(width, height);
        }
    }

    @Test
    public void testOnMeasure_PrecomputedText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setTextMetricsParams(params);
            textView.setText(text);
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onMeasure(width, height);
        }
    }

    @Test
    public void testOnMeasure_PrecomputedText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setTextMetricsParams(params);
            textView.setText(text);
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onMeasure(width, height);
        }
    }

    @Test
    public void testOnDraw_RandomText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            textView.setText(text);
            textView.measure(width, height);
            textView.layout(0, 0, textView.getMeasuredWidth(), textView.getMeasuredHeight());
            final RecordingCanvas c = node.startRecording(
                textView.getMeasuredWidth(), textView.getMeasuredHeight());
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onDraw(c);
            node.endRecording();
        }
    }

    @Test
    public void testOnDraw_RandomText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
            textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
            textView.setText(text);
            textView.measure(width, height);
            textView.layout(0, 0, textView.getMeasuredWidth(), textView.getMeasuredHeight());
            final RecordingCanvas c = node.startRecording(
                textView.getMeasuredWidth(), textView.getMeasuredHeight());
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onDraw(c);
            node.endRecording();
        }
    }

    @Test
    public void testOnDraw_PrecomputedText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(TEXT_WIDTH, MeasureSpec.AT_MOST);
        int height = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setTextMetricsParams(params);
            textView.setText(text);
            textView.measure(width, height);
            textView.layout(0, 0, textView.getMeasuredWidth(), textView.getMeasuredHeight());
            final RecordingCanvas c = node.startRecording(
                textView.getMeasuredWidth(), textView.getMeasuredHeight());
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onDraw(c);
            node.endRecording();
        }
    }

    @Test
    public void testOnDraw_PrecomputedText_Selectable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int width = MeasureSpec.makeMeasureSpec(MeasureSpec.AT_MOST, TEXT_WIDTH);
        int height = MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText.Params params = new PrecomputedText.Params.Builder(PAINT)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build();
            final CharSequence text = PrecomputedText.create(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), params);
            final TestableTextView textView = new TestableTextView(getContext());
            textView.setTextIsSelectable(true);
            textView.setTextMetricsParams(params);
            textView.setText(text);
            textView.measure(width, height);
            textView.layout(0, 0, textView.getMeasuredWidth(), textView.getMeasuredHeight());
            final RecordingCanvas c = node.startRecording(
                textView.getMeasuredWidth(), textView.getMeasuredHeight());
            textView.nullLayouts();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            textView.onDraw(c);
            node.endRecording();
        }
    }
}
