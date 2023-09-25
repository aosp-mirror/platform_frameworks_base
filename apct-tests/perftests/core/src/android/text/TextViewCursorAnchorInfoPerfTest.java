/*
 * Copyright (C) 2022 The Android Open Source Project
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


import android.app.Activity;
import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.CursorAnchorInfo;
import android.widget.TextView;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@LargeTest
@RunWith(Parameterized.class)
public class TextViewCursorAnchorInfoPerfTest {
    @Parameterized.Parameters(name = "mTextLength ({0}))")
    public static Collection<Integer> data() {
        return Arrays.asList(100, 300, 1000, 3000, 10000);
    }

    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 12;  // Roughly, 12 words in a line.


    private static final TextPaint PAINT = new TextPaint();
    // The width is an estimation. There might be lines with less or more words.
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();
    private static final int TEXT_HEIGHT = 1024;

    @Parameterized.Parameter(0)
    public int mTextLength;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public ActivityTestRule<PerfTestActivity> mActivityTestRule = new ActivityTestRule<>(
            PerfTestActivity.class);

    private TextPerfUtils mTextUtil = new TextPerfUtils();
    private PerfTextView mTextView;

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    public void setUpTextView() {
        Activity activity = mActivityTestRule.getActivity();

        mTextView = new PerfTextView(activity);

        mTextView.setHeight(TEXT_HEIGHT);
        mTextView.setWidth(TEXT_WIDTH);
        final ViewGroup.LayoutParams p = new ViewGroup.LayoutParams(TEXT_WIDTH, TEXT_HEIGHT);
        mTextView.setLayoutParams(p);
        activity.setContentView(mTextView);

        mTextView.setText(mTextUtil.nextRandomParagraph(WORD_LENGTH, mTextLength));
        mTextView.invalidate();
        mTextView.mySetFrame(TEXT_WIDTH, TEXT_HEIGHT);
        mTextView.measure(View.MeasureSpec.makeMeasureSpec(TEXT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(TEXT_HEIGHT, View.MeasureSpec.EXACTLY));
        mTextView.layout(0, 0, TEXT_WIDTH, TEXT_HEIGHT);
    }

    @Test
    public void testPopulateCharacterBounds() throws Throwable {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        mActivityTestRule.runOnUiThread(() -> {
            setUpTextView();
            while (state.keepRunning()) {
                final CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
                mTextView.populateCharacterBounds(builder, 0, mTextLength, 0f, 0f);
            }
        });
    }

    private static class PerfTextView extends TextView {

        PerfTextView(Context context) {
            super(context);
        }

        PerfTextView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        PerfTextView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        PerfTextView(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        void mySetFrame(int width, int height) {
            super.setFrame(0, 0, width, height);
        }
    }

}
