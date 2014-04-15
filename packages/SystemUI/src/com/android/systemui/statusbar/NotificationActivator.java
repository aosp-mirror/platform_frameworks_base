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

    private final View mTargetView;

    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private final int mTranslationZ;

    public NotificationActivator(View targetView) {
        mTargetView = targetView;
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
        mTargetView.animate().withLayer().alpha(INVERSE_ALPHA);
    }

    public void activate() {
        mTargetView.animate()
                .setInterpolator(mLinearOutSlowInInterpolator)
                .scaleX(1)
                .scaleY(1)
                .translationZBy(mTranslationZ);
    }

    public void reset() {
        mTargetView.animate()
                .setInterpolator(mFastOutSlowInInterpolator)
                .scaleX(DIMMED_SCALE)
                .scaleY(DIMMED_SCALE)
                .translationZBy(-mTranslationZ);
        if (mTargetView.getAlpha() != 1.0f) {
            mTargetView.animate().withLayer().alpha(1);
        }
    }

    public void setDimmed(boolean dimmed) {
        if (dimmed) {
            mTargetView.setScaleX(DIMMED_SCALE);
            mTargetView.setScaleY(DIMMED_SCALE);
        } else {
            mTargetView.setScaleX(1);
            mTargetView.setScaleY(1);
        }
    }
}
