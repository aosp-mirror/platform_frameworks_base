/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.animations;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.recents.tv.views.TaskCardView;

public class ViewFocusAnimator implements View.OnFocusChangeListener {
    private final float mUnselectedScale;
    private final float mSelectedScale;
    private final float mSelectedScaleDelta;
    private final float mUnselectedZ;
    private final float mSelectedZDelta;
    private final float mUnselectedSpacing;
    private final float mSelectedSpacingDelta;
    private final float mDismissIconAlpha;
    private final int mAnimDuration;
    private final Interpolator mFocusInterpolator;

    protected TaskCardView mTargetView;
    private float mFocusProgress;

    ObjectAnimator mFocusAnimation;

    public ViewFocusAnimator(TaskCardView view) {
        mTargetView = view;
        final Resources res = view.getResources();

        mTargetView.setOnFocusChangeListener(this);

        TypedValue out = new TypedValue();
        res.getValue(R.integer.unselected_scale, out, true);
        mUnselectedScale = out.getFloat();
        res.getValue(R.integer.selected_scale, out, true);
        mSelectedScale = out.getFloat();
        mSelectedScaleDelta = mSelectedScale - mUnselectedScale;

        mUnselectedZ = res.getDimensionPixelOffset(R.dimen.recents_tv_unselected_item_z);
        mSelectedZDelta = res.getDimensionPixelOffset(R.dimen.recents_tv_selected_item_z_delta);

        mUnselectedSpacing = res.getDimensionPixelOffset(R.dimen.recents_tv_gird_card_spacing);
        mSelectedSpacingDelta = res.getDimensionPixelOffset(R.dimen.recents_tv_gird_focused_card_delta);

        mAnimDuration = res.getInteger(R.integer.item_scale_anim_duration);

        mFocusInterpolator = new AccelerateDecelerateInterpolator();

        mFocusAnimation = ObjectAnimator.ofFloat(this, "focusProgress", 0.0f);
        mFocusAnimation.setDuration(mAnimDuration);
        mFocusAnimation.setInterpolator(mFocusInterpolator);

        mDismissIconAlpha = res.getFloat(R.integer.dismiss_unselected_alpha);

        setFocusProgress(0.0f);

        mFocusAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mTargetView.setHasTransientState(true);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mTargetView.setHasTransientState(false);
            }
        });
    }

    private void setFocusProgress(float level) {
        mFocusProgress = level;

        float scale = mUnselectedScale + (level * mSelectedScaleDelta);
        float z = mUnselectedZ + (level * mSelectedZDelta);
        float spacing = mUnselectedSpacing + (level * mSelectedSpacingDelta);

        mTargetView.setScaleX(scale);
        mTargetView.setScaleY(scale);

        mTargetView.setPadding((int) spacing, mTargetView.getPaddingTop(),
                (int) spacing, mTargetView.getPaddingBottom());

        mTargetView.getDismissIconView().setAlpha(mDismissIconAlpha * level);
        mTargetView.getThumbnailView().setZ(z);
        mTargetView.getDismissIconView().setZ(z);
    }

    private void animateFocus(boolean focused) {
        if (mFocusAnimation.isStarted()) {
            mFocusAnimation.cancel();
        }

        float target = focused ? 1.0f : 0.0f;

        if (mFocusProgress != target) {
            mFocusAnimation.setFloatValues(mFocusProgress, target);
            mFocusAnimation.start();
        }
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (v != mTargetView) {
            return;
        }
        changeSize(hasFocus);
    }

    /**
     * Changes the size of the {@link TaskCardView} to show its focused state.
     */
    public void changeSize(boolean hasFocus) {
        ViewGroup.LayoutParams lp = mTargetView.getLayoutParams();
        int width = lp.width;
        int height = lp.height;

        if (width < 0 && height < 0) {
            mTargetView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        }

        if (mTargetView.isAttachedToWindow() && mTargetView.hasWindowFocus() &&
                mTargetView.getVisibility() == View.VISIBLE) {
            animateFocus(hasFocus);
        } else {
            // Set focus immediately.
            if (mFocusAnimation.isStarted()) {
                mFocusAnimation.cancel();
            }
            setFocusProgress(hasFocus ? 1.0f : 0.0f);
        }
    }
}
