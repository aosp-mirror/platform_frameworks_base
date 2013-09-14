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
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.GradientDrawable.Orientation;
import android.os.ServiceManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonView;

public final class NavigationBarTransitions extends BarTransitions {
    private static final boolean ENABLE_GRADIENT = false;  // until we can smooth transition

    private final NavigationBarView mView;
    private final Drawable mTransparentBottom;
    private final Drawable mTransparentRight;
    private final int mTransparentColor;
    private final IStatusBarService mBarService;

    private boolean mLightsOut;

    public NavigationBarTransitions(NavigationBarView view) {
        super(view);
        mView = view;
        final Resources res = mView.getContext().getResources();
        final int[] gradientColors = new int[] {
                res.getColor(R.color.navigation_bar_background_transparent_start),
                res.getColor(R.color.navigation_bar_background_transparent_end)
        };
        mTransparentBottom = new GradientDrawable(Orientation.BOTTOM_TOP, gradientColors);
        mTransparentRight = new GradientDrawable(Orientation.RIGHT_LEFT, gradientColors);
        mTransparentColor = res.getColor(R.color.status_bar_background_transparent);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    public void setVertical(boolean isVertical) {
        if (!ENABLE_GRADIENT) return;
        mTransparent = isVertical ? mTransparentRight : mTransparentBottom;
    }

    @Override
    protected Integer getBackgroundColor(int mode) {
        if (!ENABLE_GRADIENT && mode == MODE_TRANSPARENT) return mTransparentColor;
        return super.getBackgroundColor(mode);
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyMode(newMode, animate, false /*force*/);
    }

    public void reapplyMode() {
        applyMode(getMode(), false /*animate*/, true /*force*/);
    }

    private void applyMode(int mode, boolean animate, boolean force) {
        // apply to key buttons
        final boolean isOpaque = mode == MODE_OPAQUE || mode == MODE_LIGHTS_OUT;
        final float alpha = isOpaque ? KeyButtonView.DEFAULT_QUIESCENT_ALPHA : 1f;
        setKeyButtonViewQuiescentAlpha(mView.getBackButton(), alpha, animate);
        setKeyButtonViewQuiescentAlpha(mView.getHomeButton(), alpha, animate);
        setKeyButtonViewQuiescentAlpha(mView.getRecentsButton(), alpha, animate);
        setKeyButtonViewQuiescentAlpha(mView.getMenuButton(), alpha, animate);

        // apply to lights out
        applyLightsOut(mode == MODE_LIGHTS_OUT, animate, force);
    }

    private void setKeyButtonViewQuiescentAlpha(View button, float alpha, boolean animate) {
        if (button instanceof KeyButtonView) {
            ((KeyButtonView) button).setQuiescentAlpha(alpha, animate);
        }
    }

    private void applyLightsOut(boolean lightsOut, boolean animate, boolean force) {
        if (!force && lightsOut == mLightsOut) return;

        mLightsOut = lightsOut;

        final View navButtons = mView.getCurrentView().findViewById(R.id.nav_buttons);
        final View lowLights = mView.getCurrentView().findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        final float navButtonsAlpha = lightsOut ? 0f : 1f;
        final float lowLightsAlpha = lightsOut ? 1f : 0f;

        if (!animate) {
            navButtons.setAlpha(navButtonsAlpha);
            lowLights.setAlpha(lowLightsAlpha);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            final int duration = lightsOut ? LIGHTS_OUT_DURATION : LIGHTS_IN_DURATION;
            navButtons.animate()
                .alpha(navButtonsAlpha)
                .setDuration(duration)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lowLightsAlpha)
                .setDuration(duration)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    private final View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                applyLightsOut(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };
}