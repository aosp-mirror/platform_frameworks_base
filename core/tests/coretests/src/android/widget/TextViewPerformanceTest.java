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
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.SpannedString;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class TextViewPerformanceTest extends AndroidTestCase {

    private String mString = "The quick brown fox";
    private Canvas mCanvas;
    private PerformanceTextView mTextView;
    private Paint mPaint;
    private PerformanceLabelView mLabelView;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        Bitmap mBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565);
        mCanvas = new Canvas(mBitmap);

        ViewGroup.LayoutParams p = new ViewGroup.LayoutParams(320, 240);

        mLabelView = new PerformanceLabelView(mContext);
        mLabelView.setText(mString);
        mLabelView.measure(View.MeasureSpec.AT_MOST | 320, View.MeasureSpec.AT_MOST | 240);
        mLabelView.mySetFrame(320, 240);
        mLabelView.setLayoutParams(p);
        mLabelView.myDraw(mCanvas);

        mPaint = new Paint();
        mCanvas.save();
        mTextView = new PerformanceTextView(mContext);
        mTextView.setLayoutParams(p);
        mTextView.setText(mString);
        mTextView.mySetFrame(320, 240);
        mTextView.measure(View.MeasureSpec.AT_MOST | 320, View.MeasureSpec.AT_MOST | 240);
    }

    @MediumTest
    public void testDrawTextViewLine() throws Exception {
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
    public void testSpan() throws Exception {
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
    public void testCanvasDrawText() throws Exception {
        mCanvas.drawText(mString, 30, 30, mPaint);
    }

    @SmallTest
    public void testLabelViewDraw() throws Exception {
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
