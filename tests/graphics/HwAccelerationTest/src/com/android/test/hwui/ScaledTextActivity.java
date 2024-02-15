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

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class ScaledTextActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ScaledTextView view = new ScaledTextView(this);
        setContentView(view);

        ObjectAnimator animation = ObjectAnimator.ofFloat(view, "textScale", 1.0f, 10.0f);
        animation.setDuration(3000);
        animation.setRepeatCount(ObjectAnimator.INFINITE);
        animation.setRepeatMode(ObjectAnimator.REVERSE);
        animation.start();

    }

    public static class ScaledTextView extends View {
        private static final String TEXT = "Hello libhwui! ";

        private final Paint mPaint;
        private final Paint mShadowPaint;
        private final Path mPath;

        private float mScale = 1.0f;

        public ScaledTextView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);

            mPath = makePath();

            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setTextSize(20.0f);

            mShadowPaint = new Paint();
            mShadowPaint.setAntiAlias(true);
            mShadowPaint.setShadowLayer(3.0f, 0.0f, 3.0f, 0xff000000);
            mShadowPaint.setTextSize(20.0f);
        }

        public float getTextScale() {
            return mScale;
        }

        public void setTextScale(float scale) {
            mScale = scale;
            invalidate();
        }

        private static Path makePath() {
            Path path = new Path();
            buildPath(path);
            return path;
        }

        private static void buildPath(Path path) {
            path.moveTo(0.0f, 0.0f);
            path.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
            path.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
            path.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawText(TEXT, 30.0f, 30.0f, mPaint);
            mPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(TEXT, 30.0f, 50.0f, mPaint);
            mPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(TEXT, 30.0f, 70.0f, mPaint);

            canvas.save();
            canvas.translate(400.0f, 0.0f);
            canvas.scale(3.0f, 3.0f);
            mPaint.setTextAlign(Paint.Align.LEFT);
            mPaint.setStrikeThruText(true);
            canvas.drawText(TEXT, 30.0f, 30.0f, mPaint);
            mPaint.setStrikeThruText(false);
            mPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(TEXT, 30.0f, 50.0f, mPaint);
            mPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(TEXT, 30.0f, 70.0f, mPaint);
            canvas.restore();

            mPaint.setTextAlign(Paint.Align.LEFT);
            canvas.translate(0.0f, 100.0f);

            canvas.save();
            canvas.scale(mScale, mScale);
            canvas.drawText(TEXT, 30.0f, 30.0f, mPaint);
            canvas.restore();

            canvas.translate(0.0f, 250.0f);
            canvas.save();
            canvas.scale(3.0f, 3.0f);
            canvas.drawText(TEXT, 30.0f, 30.0f, mShadowPaint);
            canvas.translate(100.0f, 0.0f);
//            canvas.drawTextOnPath(TEXT + TEXT + TEXT, mPath, 0.0f, 0.0f, mPaint);
            canvas.restore();

            float width = mPaint.measureText(TEXT);

            canvas.translate(500.0f, 0.0f);
            canvas.rotate(45.0f, width * 3.0f / 2.0f, 0.0f);
            canvas.scale(3.0f, 3.0f);
            canvas.drawText(TEXT, 30.0f, 30.0f, mPaint);
        }
    }
}
