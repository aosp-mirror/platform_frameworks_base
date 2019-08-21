/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics.ui;

import android.content.Context;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

public class AuthBiometricFaceView extends AuthBiometricView {

    // Delay before dismissing after being authenticated/confirmed.
    private static final int HIDE_DELAY_MS = 500;

    public static class IconController extends Animatable2.AnimationCallback {
        Context mContext;
        ImageView mIconView;
        Handler mHandler;
        boolean mLastPulseLightToDark; // false = dark to light, true = light to dark
        @State int mState;

        IconController(Context context, ImageView iconView) {
            mContext = context;
            mIconView = iconView;
            mHandler = new Handler(Looper.getMainLooper());
            showIcon(R.drawable.face_dialog_pulse_dark_to_light);
        }

        void showIcon(int iconRes) {
            final Drawable drawable = mContext.getDrawable(iconRes);
            mIconView.setImageDrawable(drawable);
        }

        void animateIcon(int iconRes, boolean repeat) {
            final AnimatedVectorDrawable icon =
                    (AnimatedVectorDrawable) mContext.getDrawable(iconRes);
            mIconView.setImageDrawable(icon);
            icon.forceAnimationOnUI();
            if (repeat) {
                icon.registerAnimationCallback(this);
            }
            icon.start();
        }

        void startPulsing() {
            mLastPulseLightToDark = false;
            animateIcon(R.drawable.face_dialog_pulse_dark_to_light, true);
        }

        void pulseInNextDirection() {
            int iconRes = mLastPulseLightToDark ? R.drawable.face_dialog_pulse_dark_to_light
                    : R.drawable.face_dialog_pulse_light_to_dark;
            animateIcon(iconRes, true /* repeat */);
            mLastPulseLightToDark = !mLastPulseLightToDark;
        }

        @Override
        public void onAnimationEnd(Drawable drawable) {
            super.onAnimationEnd(drawable);
            if (mState == STATE_AUTHENTICATING) {
                pulseInNextDirection();
            }
        }

        public void updateState(int newState) {
            if (newState == STATE_AUTHENTICATING) {
                startPulsing();
            }
            mState = newState;
        }
    }

    @VisibleForTesting IconController mIconController;

    public AuthBiometricFaceView(Context context) {
        this(context, null);
    }

    public AuthBiometricFaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return HIDE_DELAY_MS;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIconController = new IconController(mContext, mIconView);
    }

    @Override
    public void updateState(@State int newState) {
        super.updateState(newState);
        mIconController.updateState(newState);
    }
}
