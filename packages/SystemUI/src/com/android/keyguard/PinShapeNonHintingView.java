/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.keyguard;

import static com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.ColorId.PIN_SHAPES;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionValues;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.graphics.drawable.DrawableCompat;

import com.android.app.animation.Interpolators;
import com.android.settingslib.Utils;
import com.android.systemui.R;

/**
 * This class contains implementation for methods that will be used when user has set a
 * non six digit pin on their device
 */
public class PinShapeNonHintingView extends LinearLayout implements PinShapeInput {

    private int mColor = Utils.getColorAttr(getContext(), PIN_SHAPES).getDefaultColor();
    private int mPosition = 0;
    private final PinShapeAdapter mPinShapeAdapter;
    private ValueAnimator mValueAnimator = ValueAnimator.ofFloat(1f, 0f);
    private Rect mFirstChildVisibleRect = new Rect();
    public PinShapeNonHintingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPinShapeAdapter = new PinShapeAdapter(context);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (getChildCount() > 0) {
            View firstChild = getChildAt(0);
            boolean isVisible = firstChild.getLocalVisibleRect(mFirstChildVisibleRect);
            boolean clipped = mFirstChildVisibleRect.left > 0
                    || mFirstChildVisibleRect.right < firstChild.getWidth();
            if (!isVisible || clipped) {
                setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                return;
            }
        }

        setGravity(Gravity.CENTER);
    }

    @Override
    public void append() {
        int size = getResources().getDimensionPixelSize(R.dimen.password_shape_size);
        ImageView pinDot = new ImageView(getContext());
        pinDot.setLayoutParams(new LayoutParams(size, size));
        pinDot.setImageResource(mPinShapeAdapter.getShape(mPosition));
        if (pinDot.getDrawable() != null) {
            Drawable wrappedDrawable = DrawableCompat.wrap(pinDot.getDrawable());
            DrawableCompat.setTint(wrappedDrawable, mColor);
        }
        if (pinDot.getDrawable() instanceof AnimatedVectorDrawable) {
            ((AnimatedVectorDrawable) pinDot.getDrawable()).start();
        }
        TransitionManager.beginDelayedTransition(this, new PinShapeViewTransition());
        addView(pinDot);
        mPosition++;
    }

    @Override
    public void delete() {
        if (mPosition == 0) {
            Log.e(getClass().getName(), "Trying to delete a non-existent char");
            return;
        }
        if (mValueAnimator.isRunning()) {
            mValueAnimator.end();
        }
        mPosition--;
        ImageView pinDot = (ImageView) getChildAt(mPosition);
        mValueAnimator.addUpdateListener(valueAnimator -> {
            float value = (float) valueAnimator.getAnimatedValue();
            pinDot.setScaleX(value);
            pinDot.setScaleY(value);
        });
        mValueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                TransitionManager.beginDelayedTransition(
                        PinShapeNonHintingView.this,
                        new PinShapeViewTransition());
                removeView(pinDot);
            }
        });
        mValueAnimator.setDuration(PasswordTextView.DISAPPEAR_DURATION);
        mValueAnimator.start();
    }

    @Override
    public void setDrawColor(int color) {
        this.mColor = color;
    }

    @Override
    public void reset() {
        final int position = mPosition;
        for (int i = 0; i < position; i++) {
            delete();
        }
    }

    @Override
    public View getView() {
        return this;
    }

    class PinShapeViewTransition extends Transition {
        private static final String PROP_BOUNDS = "PinShapeViewTransition:bounds";

        @Override
        public void captureEndValues(TransitionValues transitionValues) {
            if (transitionValues != null) {
                captureValues(transitionValues);
            }
        }

        @Override
        public void captureStartValues(TransitionValues transitionValues) {
            if (transitionValues != null) {
                captureValues(transitionValues);
            }
        }

        private void captureValues(TransitionValues values) {
            Rect boundsRect = new Rect();
            boundsRect.left = values.view.getLeft();
            boundsRect.top = values.view.getTop();
            boundsRect.right = values.view.getRight();
            boundsRect.bottom = values.view.getBottom();
            values.values.put(PROP_BOUNDS, boundsRect);
        }

        @Override
        public String[] getTransitionProperties() {
            return new String[] { PROP_BOUNDS };
        }

        @Override
        public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues,
                TransitionValues endValues) {
            if (sceneRoot == null || startValues == null || endValues == null) {
                return null;
            }

            Rect startRect = (Rect) startValues.values.get(PROP_BOUNDS);
            Rect endRect = (Rect) endValues.values.get(PROP_BOUNDS);
            View v = startValues.view;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(PasswordTextView.APPEAR_DURATION);
            animator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            animator.addUpdateListener(valueAnimator -> {
                float value = (float) valueAnimator.getAnimatedValue();
                int diff = startRect.left - endRect.left;
                int currentTranslation = (int) ((diff) * value);
                v.setLeftTopRightBottom(
                        startRect.left - currentTranslation,
                        startRect.top,
                        startRect.right - currentTranslation,
                        startRect.bottom
                );
            });
            animator.start();
            return animator;
        }
    }
}
