/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.transitiontests;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;


public class HitRectBug extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(new TestDrawingView(this));
    }

    public static class TestDrawingView extends RelativeLayout
    {
        private Rect mRect = new Rect();
        private Paint mPaint;
        private ImageView mImageView;

        public TestDrawingView(Context context)
        {
            super(context);
            setWillNotDraw(false);

            mPaint = new Paint();
            mPaint.setColor(Color.RED);
            mPaint.setStyle(Paint.Style.STROKE);

            mImageView = new ImageView(context);
            mImageView.setLeft(100);
            mImageView.setRight(200);
            mImageView.setImageResource(R.drawable.self_portrait_square);
            mImageView.setScaleX(3);
            mImageView.setScaleY(3);
//            mImageView.setRotation(145);

            ObjectAnimator anim = ObjectAnimator.ofFloat(mImageView, View.ROTATION, 0, 360);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setDuration(5000);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    invalidate();
                }
            });
            anim.start();
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(128, 128);
            params.addRule(RelativeLayout.CENTER_IN_PARENT);
            addView(mImageView, params);
        }

        @Override
        protected void onDraw(Canvas canvas)
        {
            super.onDraw(canvas);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            mImageView.getHitRect(mRect);
            canvas.drawRect(mRect, mPaint);
        }
    }
}
