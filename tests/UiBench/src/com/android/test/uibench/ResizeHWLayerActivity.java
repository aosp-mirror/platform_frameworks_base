/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.test.uibench;

import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;

/**
 * Tests resizing of a View backed by a hardware layer.
 */
public class ResizeHWLayerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        View child = new View(this);
        child.setBackgroundColor(Color.BLUE);
        child.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        PropertyValuesHolder pvhWidth = PropertyValuesHolder.ofInt("width", width, 1);
        PropertyValuesHolder pvhHeight = PropertyValuesHolder.ofInt("height", height, 1);
        final LayoutParams params = child.getLayoutParams();
        ValueAnimator animator = ValueAnimator.ofPropertyValuesHolder(pvhWidth, pvhHeight);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                params.width = (Integer)valueAnimator.getAnimatedValue("width");
                params.height = (Integer)valueAnimator.getAnimatedValue("height");
                child.requestLayout();
            }
        });
        animator.start();
        setContentView(child);
    }
}
