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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ScrollView;

@SuppressWarnings({"UnusedDeclaration"})
public class BackdropBlurActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ScrollView scrollView = new ScrollView(this);
        final FrameLayout innerFrame = new FrameLayout(this);
        final View backgroundView = new View(this);
        backgroundView.setBackgroundResource(R.drawable.robot_repeated);
        innerFrame.addView(backgroundView, ViewGroup.LayoutParams.MATCH_PARENT, 10000);
        scrollView.addView(innerFrame,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final FrameLayout contentView = new FrameLayout(this);
        contentView.addView(scrollView,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        contentView.addView(new BackdropBlurView(this), 300, 300);
        setContentView(contentView);
    }

    private static class BackdropBlurView extends View {
        private final float mBlurRadius = 60f;
        private final float mSaturation = 1.8f;

        private float mDownOffsetX;
        private float mDownOffsetY;

        BackdropBlurView(Context c) {
            super(c);

            // init RenderEffect.
            final RenderEffect blurEffect = RenderEffect.createBlurEffect(
                    mBlurRadius, mBlurRadius,
                    null, Shader.TileMode.MIRROR // TileMode.MIRROR is better for blur.
            );

            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(mSaturation);
            final RenderEffect effect = RenderEffect.createColorFilterEffect(
                    new ColorMatrixColorFilter(colorMatrix), blurEffect
            );
            setBackdropRenderEffect(effect);

            // clip to a round outline.
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            setClipToOutline(true);

            animate().setInterpolator(new DecelerateInterpolator(2.0f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawColor(0x99F0F0F0);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownOffsetX = event.getRawX() - getTranslationX();
                    mDownOffsetY = event.getRawY() - getTranslationY();
                    animate().scaleX(1.5f).scaleY(1.5f).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).start();
                    break;
                case MotionEvent.ACTION_MOVE:
                    setTranslationX(event.getRawX() - mDownOffsetX);
                    setTranslationY(event.getRawY() - mDownOffsetY);
                    break;
            }
            return true;
        }
    }
}
