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
        private final Paint mPaint;
        private float mScale = 1.0f;

        public ScaledTextView(Context c) {
            super(c);

            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setTextSize(20.0f);
        }

        public float getTextScale() {
            return mScale;
        }

        public void setTextScale(float scale) {
            mScale = scale;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawARGB(255, 255, 255, 255);

            canvas.drawText("Hello libhwui!", 30.0f, 30.0f, mPaint);
            canvas.translate(0.0f, 50.0f);
            canvas.save();
            canvas.scale(mScale, mScale);
            canvas.drawText("Hello libhwui!", 30.0f, 30.0f, mPaint);
            canvas.restore();
        }
    }
}
