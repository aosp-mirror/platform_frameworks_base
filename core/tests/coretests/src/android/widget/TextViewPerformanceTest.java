/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannedString;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TextViewPerformanceTest {

    private String mString = "The quick brown fox";
    private Canvas mCanvas;
    private PerformanceTextView mTextView;
    private Paint mPaint;
    private PerformanceLabelView mLabelView;

    @Before
    public void setUp() {
        Bitmap mBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565);
        mCanvas = new Canvas(mBitmap);

        ViewGroup.LayoutParams p = new ViewGroup.LayoutParams(320, 240);

        final Context context = InstrumentationRegistry.getContext();

        mLabelView = new PerformanceLabelView(context);
        mLabelView.setText(mString);
        mLabelView.measure(View.MeasureSpec.AT_MOST | 320, View.MeasureSpec.AT_MOST | 240);
        mLabelView.mySetFrame(320, 240);
        mLabelView.setLayoutParams(p);
        mLabelView.myDraw(mCanvas);

        mPaint = new Paint();
        mCanvas.save();
        mTextView = new PerformanceTextView(context);
        mTextView.setLayoutParams(p);
        mTextView.setText(mString);
        mTextView.mySetFrame(320, 240);
        mTextView.measure(View.MeasureSpec.AT_MOST | 320, View.MeasureSpec.AT_MOST | 240);
    }

    @MediumTest
    @Test
    public void testDrawTextViewLine() {
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
    }

    @SmallTest
    @Test
    public void testSpan() {
        CharSequence charSeq = new SpannedString(mString);
        mTextView.setText(charSeq);

        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
        mTextView.myDraw(mCanvas);
    }

    @SmallTest
    @Test
    public void testCanvasDrawText() {
        mCanvas.drawText(mString, 30, 30, mPaint);
    }

    @SmallTest
    @Test
    public void testLabelViewDraw() {
        mLabelView.myDraw(mCanvas);
    }

    private class PerformanceTextView extends TextView {
        public PerformanceTextView(Context context) {
            super(context);
        }

        final void myDraw(Canvas c) {
            super.onDraw(c);
        }

        final void mySetFrame(int w, int h) {
            super.setFrame(0, 0, w, h);
        }
    }

    private class PerformanceLabelView extends LabelView {
        public PerformanceLabelView(Context context) {
            super(context);
        }

        final void myDraw(Canvas c) {
            super.onDraw(c);
        }

        final void mySetFrame(int w, int h) {
            super.setFrame(0, 0, w, h);
        }
    }
}
