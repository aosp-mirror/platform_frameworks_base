/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class ShapesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new ShapesView(this));
    }

    static class ShapesView extends View {
        private final Paint mNormalPaint;
        private final Paint mStrokePaint;
        private final Paint mFillPaint;
        private final RectF mRect;
        private final RectF mOval;
        private final RectF mArc;
        private final Path mTriangle;

        ShapesView(Context c) {
            super(c);

            mRect = new RectF(0.0f, 0.0f, 160.0f, 90.0f);

            mNormalPaint = new Paint();
            mNormalPaint.setAntiAlias(true);
            mNormalPaint.setColor(0xff0000ff);
            mNormalPaint.setStrokeWidth(6.0f);
            mNormalPaint.setStyle(Paint.Style.FILL_AND_STROKE);

            mStrokePaint = new Paint();
            mStrokePaint.setAntiAlias(true);
            mStrokePaint.setColor(0xff0000ff);
            mStrokePaint.setStrokeWidth(6.0f);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            
            mFillPaint = new Paint();
            mFillPaint.setAntiAlias(true);
            mFillPaint.setColor(0xff0000ff);
            mFillPaint.setStyle(Paint.Style.FILL);

            mOval = new RectF(0.0f, 0.0f, 80.0f, 45.0f);
            mArc = new RectF(0.0f, 0.0f, 100.0f, 120.0f);

            mTriangle = new Path();
            mTriangle.moveTo(0.0f, 90.0f);
            mTriangle.lineTo(45.0f, 0.0f);
            mTriangle.lineTo(90.0f, 90.0f);
            mTriangle.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.save();
            canvas.translate(50.0f, 50.0f);
            canvas.drawRoundRect(mRect, 6.0f, 6.0f, mNormalPaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawRoundRect(mRect, 6.0f, 6.0f, mStrokePaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawRoundRect(mRect, 6.0f, 6.0f, mFillPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(250.0f, 50.0f);
            canvas.drawCircle(80.0f, 45.0f, 45.0f, mNormalPaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawCircle(80.0f, 45.0f, 45.0f, mStrokePaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawCircle(80.0f, 45.0f, 45.0f, mFillPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(450.0f, 50.0f);
            canvas.drawOval(mOval, mNormalPaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawOval(mOval, mStrokePaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawOval(mOval, mFillPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(625.0f, 50.0f);
            canvas.drawRect(0.0f, 0.0f, 160.0f, 90.0f, mNormalPaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawRect(0.0f, 0.0f, 160.0f, 90.0f, mStrokePaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawRect(0.0f, 0.0f, 160.0f, 90.0f, mFillPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(825.0f, 50.0f);
            canvas.drawArc(mArc, -30.0f, 70.0f, true, mNormalPaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawArc(mArc, -30.0f, 70.0f, true, mStrokePaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawArc(mArc, -30.0f, 70.0f, true, mFillPaint);
            canvas.restore();
            
            canvas.save();
            canvas.translate(950.0f, 50.0f);
            canvas.drawArc(mArc, 30.0f, 100.0f, false, mNormalPaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawArc(mArc, 30.0f, 100.0f, false, mStrokePaint);

            canvas.translate(0.0f, 110.0f);
            canvas.drawArc(mArc, 30.0f, 100.0f, false, mFillPaint);
            canvas.restore();

            canvas.save();
            canvas.translate(50.0f, 400.0f);
            canvas.drawPath(mTriangle, mNormalPaint);

            canvas.translate(110.0f, 0.0f);
            canvas.drawPath(mTriangle, mStrokePaint);

            canvas.translate(110.0f, 0.0f);
            canvas.drawPath(mTriangle, mFillPaint);
            canvas.restore();
        }
    }
}
