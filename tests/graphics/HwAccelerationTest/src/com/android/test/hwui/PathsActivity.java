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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class PathsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final PathsView view = new PathsView(this);
        setContentView(view);
    }

    public static class PathsView extends View {
        private final Bitmap mBitmap1;
        private final Paint mSmallPaint;
        private final Paint mMediumPaint;
        private final Paint mLargePaint;
        private final BitmapShader mShader;
        private final Path mPath;
        private final RectF mPathBounds;
        private final Paint mBoundsPaint;
        private final Bitmap mBitmap;
        private final float mOffset;
        private final Paint mLinePaint;

        public PathsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);

            mSmallPaint = new Paint();
            mSmallPaint.setAntiAlias(true);
            mSmallPaint.setColor(0xffff0000);
            mSmallPaint.setStrokeWidth(1.0f);
            mSmallPaint.setStyle(Paint.Style.STROKE);

            mLinePaint = new Paint();
            mLinePaint.setAntiAlias(true);
            mLinePaint.setColor(0xffff00ff);
            mLinePaint.setStrokeWidth(1.0f);
            mLinePaint.setStyle(Paint.Style.STROKE);

            mMediumPaint = new Paint();
            mMediumPaint.setAntiAlias(true);
            mMediumPaint.setColor(0xe00000ff);
            mMediumPaint.setStrokeWidth(10.0f);
            mMediumPaint.setStyle(Paint.Style.STROKE);

            mLargePaint = new Paint();
            mLargePaint.setAntiAlias(true);
            mLargePaint.setColor(0x7f00ff00);
            mLargePaint.setStrokeWidth(15.0f);
            mLargePaint.setStyle(Paint.Style.FILL);

            mShader = new BitmapShader(mBitmap1, BitmapShader.TileMode.MIRROR,
                    BitmapShader.TileMode.MIRROR);

            mPath = new Path();
            mPath.moveTo(0.0f, 0.0f);
            mPath.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
            mPath.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
            mPath.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);

            mPathBounds = new RectF();
            mPath.computeBounds(mPathBounds, true);

            mBoundsPaint = new Paint();
            mBoundsPaint.setColor(0x4000ff00);

            mOffset = mMediumPaint.getStrokeWidth();
            final int width = (int) (mPathBounds.width() + mOffset * 3.0f + 0.5f);
            final int height = (int) (mPathBounds.height() + mOffset * 3.0f + 0.5f);
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            Canvas canvas = new Canvas(mBitmap);
            canvas.translate(-mPathBounds.left + mOffset * 1.5f, -mPathBounds.top + mOffset * 1.5f);
            canvas.drawPath(mPath, mMediumPaint);
            canvas.setBitmap(null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            canvas.save();
            canvas.translate(200.0f, 60.0f);
            canvas.drawPath(mPath, mSmallPaint);

            canvas.translate(350.0f, 0.0f);
            canvas.drawPath(mPath, mMediumPaint);

            mLargePaint.setShader(mShader);
            canvas.translate(350.0f, 0.0f);
            canvas.drawPath(mPath, mLargePaint);
            mLargePaint.setShader(null);
            canvas.restore();

            canvas.save();
            canvas.translate(200.0f, 360.0f);
            canvas.drawPath(mPath, mSmallPaint);
            canvas.drawRect(mPathBounds, mBoundsPaint);

            canvas.translate(350.0f, 0.0f);
            canvas.drawBitmap(mBitmap, mPathBounds.left - mOffset * 1.5f,
                    mPathBounds.top - mOffset * 1.5f, null);
            canvas.drawRect(mPathBounds, mBoundsPaint);
            canvas.drawLine(0.0f, -360.0f, 0.0f, 500.0f, mLinePaint);

            mLargePaint.setShader(mShader);
            canvas.translate(350.0f, 0.0f);
            canvas.drawPath(mPath, mLargePaint);
            canvas.drawRect(mPathBounds, mBoundsPaint);
            mLargePaint.setShader(null);
            canvas.restore();
        }
    }
}
