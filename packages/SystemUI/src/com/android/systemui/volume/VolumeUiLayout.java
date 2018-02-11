/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.volume;

import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.util.leak.RotationUtils;

public class VolumeUiLayout extends FrameLayout  {

    private View mChild;
    private int mRotation = ROTATION_NONE;
    @Nullable
    private DisplayCutout mDisplayCutout;

    public VolumeUiLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDisplayCutout = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mChild == null) {
            if (getChildCount() != 0) {
                mChild = getChildAt(0);
                updateRotation();
            } else {
                return;
            }
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotation();
    }

    private void setDisplayCutout() {
        if (mDisplayCutout == null && getRootWindowInsets() != null) {
            DisplayCutout cutout = getRootWindowInsets().getDisplayCutout();
            if (cutout != null) {
                mDisplayCutout = cutout;
            }
        }
    }

    public void updateRotation() {
        setDisplayCutout();
        if (mChild == null) {
            if (getChildCount() != 0) {
                mChild = getChildAt(0);
            }
        }
        int rotation = RotationUtils.getRotation(getContext());
        if (rotation != mRotation) {
            updateSafeInsets(rotation);
            mRotation = rotation;
        }
    }

    private void updateSafeInsets(int rotation) {
        // Depending on our rotation, we may have to work around letterboxing from the right
        // side from the navigation bar or a cutout.

        MarginLayoutParams lp = (MarginLayoutParams) mChild.getLayoutParams();

        int margin = (int) getResources().getDimension(R.dimen.volume_dialog_base_margin);
        switch (rotation) {
            /*
             * Landscape: <-|. Have to deal with the nav bar
             * Seascape:  |->. Have to deal with the cutout
             */
            case RotationUtils.ROTATION_LANDSCAPE:
                margin += getNavBarHeight();
                break;
            case RotationUtils.ROTATION_SEASCAPE:
                margin += getDisplayCutoutHeight();
                break;
            default:
                break;
        }

        lp.rightMargin = margin;
        mChild.setLayoutParams(lp);
    }

    private int getNavBarHeight() {
        return (int) getResources().getDimension(R.dimen.navigation_bar_size);
    }

    //TODO: Find a better way
    private int getDisplayCutoutHeight() {
        if (mDisplayCutout == null || mDisplayCutout.isEmpty()) {
            return 0;
        }

        Rect r = mDisplayCutout.getBoundingRect();
        return r.bottom - r.top;
    }

    @Override
    public ViewOutlineProvider getOutlineProvider() {
        return super.getOutlineProvider();
    }

    public static VolumeUiLayout get(View v) {
        if (v instanceof VolumeUiLayout) return (VolumeUiLayout) v;
        if (v.getParent() instanceof View) {
            return get((View) v.getParent());
        }
        return null;
    }
}
