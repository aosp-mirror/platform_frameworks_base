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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.view.View;

import com.android.systemui.R;

public final class PhoneStatusBarTransitions extends BarTransitions {
    private static final float ICON_ALPHA_WHEN_NOT_OPAQUE = 1;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK = 0.5f;
    private static final float ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK = 0;

    private final float mIconAlphaWhenOpaque;

    private boolean mIsHeadsUp;

    private View mStartSide, mStatusIcons, mBattery;
    private Animator mCurrentAnimation;

    /**
     * @param backgroundView view to apply the background drawable
     */
    public PhoneStatusBarTransitions(PhoneStatusBarView statusBarView, View backgroundView) {
        super(backgroundView, R.drawable.status_background);
        final Resources res = statusBarView.getContext().getResources();
        mIconAlphaWhenOpaque = res.getFraction(R.dimen.status_bar_icon_drawing_alpha, 1, 1);
        mStartSide = statusBarView.findViewById(R.id.status_bar_start_side_except_heads_up);
        mStatusIcons = statusBarView.findViewById(R.id.statusIcons);
        mBattery = statusBarView.findViewById(R.id.battery);
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyMode(getMode(), false /*animate*/);
    }

    public ObjectAnimator animateTransitionTo(View v, float toAlpha) {
        return ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), toAlpha);
    }

    private float getStatusIconsAlphaFor(int mode) {
        return getDefaultAlphaFor(mode);
    }

    private float getStartSideAlphaFor(int mode) {
        // When there's a heads up notification, we need the start side icons to show regardless of
        // lights out mode.
        if (mIsHeadsUp) {
            return getIconAlphaBasedOnOpacity(mode);
        }
        return getDefaultAlphaFor(mode);
    }

    private float getBatteryClockAlpha(int mode) {
        return isLightsOut(mode) ? ICON_ALPHA_WHEN_LIGHTS_OUT_BATTERY_CLOCK
                : getIconAlphaBasedOnOpacity(mode);
    }

    private float getDefaultAlphaFor(int mode) {
        return isLightsOut(mode) ? ICON_ALPHA_WHEN_LIGHTS_OUT_NON_BATTERY_CLOCK
                : getIconAlphaBasedOnOpacity(mode);
    }

    private float getIconAlphaBasedOnOpacity(int mode) {
        return !isOpaque(mode) ? ICON_ALPHA_WHEN_NOT_OPAQUE
                : mIconAlphaWhenOpaque;
    }

    private boolean isOpaque(int mode) {
        return !(mode == MODE_SEMI_TRANSPARENT || mode == MODE_TRANSLUCENT
                || mode == MODE_TRANSPARENT || mode == MODE_LIGHTS_OUT_TRANSPARENT);
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyMode(newMode, animate);
    }

    /** Informs this controller that the heads up notification state has changed. */
    public void onHeadsUpStateChanged(boolean isHeadsUp) {
        mIsHeadsUp = isHeadsUp;
        // We want the icon to be fully visible when the HUN appears, so just immediately change the
        // icon visibility and don't animate.
        applyMode(getMode(), /* animate= */ false);
    }

    private void applyMode(int mode, boolean animate) {
        if (mStartSide == null) return; // pre-init
        float newStartSideAlpha = getStartSideAlphaFor(mode);
        float newStatusIconsAlpha = getStatusIconsAlphaFor(mode);
        float newBatteryAlpha = getBatteryClockAlpha(mode);
        if (mCurrentAnimation != null) {
            mCurrentAnimation.cancel();
        }
        if (animate) {
            AnimatorSet anims = new AnimatorSet();
            anims.playTogether(
                    animateTransitionTo(mStartSide, newStartSideAlpha),
                    animateTransitionTo(mStatusIcons, newStatusIconsAlpha),
                    animateTransitionTo(mBattery, newBatteryAlpha)
                    );
            if (isLightsOut(mode)) {
                anims.setDuration(LIGHTS_OUT_DURATION);
            }
            anims.start();
            mCurrentAnimation = anims;
        } else {
            mStartSide.setAlpha(newStartSideAlpha);
            mStatusIcons.setAlpha(newStatusIconsAlpha);
            mBattery.setAlpha(newBatteryAlpha);
        }
    }
}
