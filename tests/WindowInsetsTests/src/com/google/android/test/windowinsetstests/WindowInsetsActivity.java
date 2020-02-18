/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.google.android.test.windowinsetstests;

import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.util.Property;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowInsetsAnimationController;

import com.google.android.test.windowinsetstests.R;

import java.util.List;

public class WindowInsetsActivity extends Activity {

    private View mRoot;
    private View mButton;

    private static class InsetsProperty extends Property<WindowInsetsAnimationController, Insets> {

        private final View mViewToAnimate;
        private final Insets mShowingInsets;

        public InsetsProperty(View viewToAnimate, Insets showingInsets) {
            super(Insets.class, "Insets");
            mViewToAnimate = viewToAnimate;
            mShowingInsets = showingInsets;
        }

        @Override
        public Insets get(WindowInsetsAnimationController object) {
            return object.getCurrentInsets();
        }

        @Override
        public void set(WindowInsetsAnimationController object, Insets value) {
            object.setInsetsAndAlpha(value, 1.0f, 0.5f);
            if (mShowingInsets.bottom != 0) {
                mViewToAnimate.setTranslationY(mShowingInsets.bottom - value.bottom);
            } else if (mShowingInsets.right != 0) {
                mViewToAnimate.setTranslationX(mShowingInsets.right - value.right);
            } else if (mShowingInsets.left != 0) {
                mViewToAnimate.setTranslationX(value.left - mShowingInsets.left);
            }
        }
    };

    float startY;
    float endY;
    WindowInsetsAnimation imeAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.window_inset_activity);
        mRoot = findViewById(R.id.root);
        mButton = findViewById(R.id.button);
        mButton.setOnClickListener(v -> {
            if (!v.getRootWindowInsets().isVisible(Type.ime())) {
                v.getWindowInsetsController().show(Type.ime());
            } else {
                v.getWindowInsetsController().hide(Type.ime());
            }
        });
        mRoot.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                DISPATCH_MODE_STOP) {

            @Override
            public void onPrepare(WindowInsetsAnimation animation) {
                if ((animation.getTypeMask() & Type.ime()) != 0) {
                    imeAnim = animation;
                }
                startY = mButton.getTop();
            }

            @Override
            public WindowInsets onProgress(WindowInsets insets,
                    List<WindowInsetsAnimation> runningAnimations) {
                mButton.setY(startY + (endY - startY) * imeAnim.getInterpolatedFraction());
                return insets;
            }

            @Override
            public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation animation,
                    WindowInsetsAnimation.Bounds bounds) {
                endY = mButton.getTop();
                return bounds;
            }

            @Override
            public void onEnd(WindowInsetsAnimation animation) {
                imeAnim = null;
            }
        });
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        TypeEvaluator<Insets> evaluator = (fraction, startValue, endValue) -> Insets.of(
                (int)(startValue.left + fraction * (endValue.left - startValue.left)),
                (int)(startValue.top + fraction * (endValue.top - startValue.top)),
                (int)(startValue.right + fraction * (endValue.right - startValue.right)),
                (int)(startValue.bottom + fraction * (endValue.bottom - startValue.bottom)));

        WindowInsetsAnimationControlListener listener = new WindowInsetsAnimationControlListener() {
            @Override
            public void onReady(WindowInsetsAnimationController controller, int types) {
                ObjectAnimator animator = ObjectAnimator.ofObject(controller,
                        new InsetsProperty(findViewById(R.id.button),
                                controller.getShownStateInsets()),
                        evaluator, controller.getShownStateInsets(),
                        controller.getHiddenStateInsets());
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.start();
            }

            @Override
            public void onCancelled() {

            }
        };
    }
}
