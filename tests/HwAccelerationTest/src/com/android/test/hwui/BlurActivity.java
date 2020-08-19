/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.graphics.BlurShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class BlurActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setClipChildren(false);
        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);


        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(500, 500);
        params.bottomMargin = 100;

        layout.addView(new BlurGradientView(this), params);
        layout.addView(new BlurView(this), params);

        setContentView(layout);
    }

    public static class BlurGradientView extends View {
        private BlurShader mBlurShader = null;
        private Paint mPaint;

        public BlurGradientView(Context c) {
            super(c);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (changed || mBlurShader == null) {
                LinearGradient gradient = new LinearGradient(
                        0f,
                        0f,
                        right - left,
                        bottom - top,
                        Color.CYAN,
                        Color.YELLOW,
                        Shader.TileMode.CLAMP
                );
                mBlurShader = new BlurShader(30f, 40f, gradient);
                mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                mPaint.setShader(mBlurShader);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        }
    }

    public static class BlurView extends View {

        private final BlurShader mBlurShader;
        private final Paint mPaint;

        public BlurView(Context c) {
            super(c);

            mBlurShader = new BlurShader(20f, 20f, null);
            mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaint.setShader(mBlurShader);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            mPaint.setColor(Color.BLUE);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);

            mPaint.setColor(Color.RED);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, 50f, mPaint);
        }
    }
}
