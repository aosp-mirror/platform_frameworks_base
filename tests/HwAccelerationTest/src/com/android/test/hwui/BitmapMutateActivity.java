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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class BitmapMutateActivity extends Activity {
    private static final int PATTERN_SIZE = 400;

    private ObjectAnimator mAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        final FrameLayout layout = new FrameLayout(this);

        layout.addView(view, new FrameLayout.LayoutParams(480, 800, Gravity.CENTER));

        setContentView(layout);

        mAnimator = ObjectAnimator.ofInt(view, "offset", 0, PATTERN_SIZE - 1);
        mAnimator.setDuration(1500);
        mAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        mAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        mAnimator.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAnimator.cancel();
    }

    static class BitmapsView extends View {
        private final Paint mBitmapPaint;
        private final Bitmap mBitmap1;
        private final int[] mPixels;

        private int mOffset;
        private int mSlice;
        private static final int[] mShifts = new int[] { 16, 8, 0 };

        BitmapsView(Context c) {
            super(c);

            mBitmap1 = Bitmap.createBitmap(PATTERN_SIZE, PATTERN_SIZE, Bitmap.Config.ARGB_8888);
            mBitmapPaint = new Paint();

            mPixels = new int[mBitmap1.getWidth() * mBitmap1.getHeight()];
            mSlice = mBitmap1.getWidth() / 3;
        }

        public void setOffset(int offset) {
            mOffset = offset;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = mBitmap1.getWidth();
            int height = mBitmap1.getHeight();

            canvas.translate((getWidth() - width) / 2, (getHeight() - height) / 2);

            for (int x = 0; x < width; x++) {
                int color = 0xff000000;
                int i = x == 0 ? 0 : x - 1;
                color |= (int) ((0xff * ((i + mOffset) % mSlice) / (float) mSlice)) <<
                        mShifts[i / mSlice];
                for (int y = 0; y < height; y++) {
                    mPixels[y * width + x] = color;
                }
            }

            mBitmap1.setPixels(mPixels, 0, width, 0, 0, width, height);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);
        }
    }
}
