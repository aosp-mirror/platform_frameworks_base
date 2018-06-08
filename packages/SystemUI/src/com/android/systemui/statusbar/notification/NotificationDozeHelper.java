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

package com.android.systemui.statusbar.notification;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.function.Consumer;

public class NotificationDozeHelper {
    private static final int DOZE_ANIMATOR_TAG = R.id.doze_intensity_tag;
    private final ColorMatrix mGrayscaleColorMatrix = new ColorMatrix();

    public void fadeGrayscale(final ImageView target, final boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateGrayscale(target, (float) animation.getAnimatedValue());
            }
        }, dark, delay, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!dark) {
                    target.setColorFilter(null);
                }
            }
        });
    }

    public void updateGrayscale(ImageView target, boolean dark) {
        updateGrayscale(target, dark ? 1 : 0);
    }

    public void updateGrayscale(ImageView target, float darkAmount) {
        if (darkAmount > 0) {
            updateGrayscaleMatrix(darkAmount);
            target.setColorFilter(new ColorMatrixColorFilter(mGrayscaleColorMatrix));
        } else {
            target.setColorFilter(null);
        }
    }

    public void startIntensityAnimation(ValueAnimator.AnimatorUpdateListener updateListener,
            boolean dark, long delay, Animator.AnimatorListener listener) {
        float startIntensity = dark ? 0f : 1f;
        float endIntensity = dark ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(startIntensity, endIntensity);
        animator.addUpdateListener(updateListener);
        animator.setDuration(NotificationPanelView.DOZE_ANIMATION_DURATION);
        animator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        animator.setStartDelay(delay);
        if (listener != null) {
            animator.addListener(listener);
        }
        animator.start();
    }

    public void setIntensityDark(Consumer<Float> listener, boolean dark,
            boolean animate, long delay, View view) {
        if (animate) {
            startIntensityAnimation(a -> listener.accept((Float) a.getAnimatedValue()), dark, delay,
                    new AnimatorListenerAdapter() {

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            view.setTag(DOZE_ANIMATOR_TAG, null);
                        }

                        @Override
                        public void onAnimationStart(Animator animation) {
                            view.setTag(DOZE_ANIMATOR_TAG, animation);
                        }
                    } /* listener */);
        } else {
            Animator animator = (Animator) view.getTag(DOZE_ANIMATOR_TAG);
            if (animator != null) {
                animator.cancel();
            }
            listener.accept(dark ? 1f : 0f);
        }
    }

    public void updateGrayscaleMatrix(float intensity) {
        mGrayscaleColorMatrix.setSaturation(1 - intensity);
    }

    public ColorMatrix getGrayscaleColorMatrix() {
        return mGrayscaleColorMatrix;
    }
}
