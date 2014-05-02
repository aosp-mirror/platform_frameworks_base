/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.systemui.R;

/**
 * A helper class used by both {@link com.android.systemui.statusbar.ExpandableNotificationRow} and
 * {@link com.android.systemui.statusbar.NotificationOverflowIconsView} to make a notification look
 * active after tapping it once on the Keyguard.
 */
public class NotificationActivator {

    private static final int ANIMATION_LENGTH_MS = 220;
    private static final float INVERSE_ALPHA = 0.9f;
    private static final float DIMMED_SCALE = 0.95f;

    /**
     * Normal state. Notification is fully interactable.
     */
    private static final int STATE_NORMAL = 0;

    /**
     * Dimmed state. Neutral state when on the lockscreen, with slight transparency and scaled down
     * a bit.
     */
    private static final int STATE_DIMMED = 1;

    /**
     * Activated state. Used after tapping a notification on the lockscreen. Normal transparency and
     * normal scale.
     */
    private static final int STATE_ACTIVATED = 2;

    /**
     * Inverse activated state. Used for the other notifications on the lockscreen when tapping on
     * one.
     */
    private static final int STATE_ACTIVATED_INVERSE = 3;

    private final View mTargetView;
    private final View mHotspotView;
    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private final int mTranslationZ;

    private int mState;

    public NotificationActivator(View targetView, View hotspotView) {
        mTargetView = targetView;
        mHotspotView = hotspotView;
        Context ctx = targetView.getContext();
        mFastOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(ctx, android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(ctx, android.R.interpolator.linear_out_slow_in);
        mTranslationZ =
                ctx.getResources().getDimensionPixelSize(R.dimen.z_distance_between_notifications);
        mTargetView.animate().setDuration(ANIMATION_LENGTH_MS);
    }

    public void activateInverse() {
        if (mState == STATE_ACTIVATED_INVERSE) {
            return;
        }
        mTargetView.animate().cancel();
        mTargetView.animate().withLayer().alpha(INVERSE_ALPHA);
        mState = STATE_ACTIVATED_INVERSE;
    }

    public void addHotspot() {
        mHotspotView.getBackground().setHotspot(
                0, mHotspotView.getWidth()/2, mHotspotView.getHeight()/2);
    }

    public void activate() {
        if (mState == STATE_ACTIVATED) {
            return;
        }
        mTargetView.animate().cancel();
        mTargetView.animate()
                .setInterpolator(mLinearOutSlowInInterpolator)
                .scaleX(1)
                .scaleY(1)
                .translationZBy(mTranslationZ);
        mState = STATE_ACTIVATED;
    }

    public void reset() {
        if (mState == STATE_DIMMED) {
            return;
        }
        mTargetView.animate().cancel();
        mTargetView.animate()
                .setInterpolator(mFastOutSlowInInterpolator)
                .scaleX(DIMMED_SCALE)
                .scaleY(DIMMED_SCALE)
                .translationZBy(-mTranslationZ);
        if (mTargetView.getAlpha() != 1.0f) {
            mTargetView.animate().withLayer().alpha(1);
        }
        mHotspotView.getBackground().removeHotspot(0);
        mState = STATE_DIMMED;
    }

    public void setDimmed(boolean dimmed) {
        if (dimmed) {
            mTargetView.animate().cancel();
            mTargetView.setScaleX(DIMMED_SCALE);
            mTargetView.setScaleY(DIMMED_SCALE);
            mState = STATE_DIMMED;
        } else {
            mTargetView.animate().cancel();
            mTargetView.setScaleX(1);
            mTargetView.setScaleY(1);
            mState = STATE_NORMAL;
        }
    }
}
