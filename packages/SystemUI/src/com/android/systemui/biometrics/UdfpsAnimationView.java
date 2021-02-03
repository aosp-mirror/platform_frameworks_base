/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.statusbar.phone.ScrimController;

/**
 * Class that coordinates non-HBM animations (such as enroll, keyguard, BiometricPrompt,
 * FingerprintManager).
 */
public class UdfpsAnimationView extends View implements DozeReceiver,
        ScrimController.ScrimChangedListener {

    private static final String TAG = "UdfpsAnimationView";

    @NonNull private UdfpsView mParent;
    @Nullable private UdfpsAnimation mUdfpsAnimation;
    @NonNull private RectF mSensorRect;
    private int mNotificationPanelAlpha;


    public UdfpsAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mSensorRect = new RectF();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mUdfpsAnimation != null) {
            final int alpha = mParent.shouldPauseAuth() ? 255 - mNotificationPanelAlpha : 255;
            mUdfpsAnimation.setAlpha(alpha);
            mUdfpsAnimation.draw(canvas);
        }
    }

    void setParent(@NonNull UdfpsView parent) {
        mParent = parent;
    }

    void setAnimation(@Nullable UdfpsAnimation animation) {
        mUdfpsAnimation = animation;
    }

    void onSensorRectUpdated(@NonNull RectF sensorRect) {
        mSensorRect = sensorRect;
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.onSensorRectUpdated(mSensorRect);
        }
    }

    void updateColor() {
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.updateColor();
        }
    }

    @Override
    public void dozeTimeTick() {
        if (mUdfpsAnimation instanceof DozeReceiver) {
            ((DozeReceiver) mUdfpsAnimation).dozeTimeTick();
        }
    }

    @Override
    public void onAlphaChanged(float alpha) {
        mNotificationPanelAlpha = (int) (alpha * 255);
        postInvalidate();
    }

    void onEnrollmentProgress(int remaining) {
        if (mUdfpsAnimation instanceof UdfpsAnimationEnroll) {
            ((UdfpsAnimationEnroll) mUdfpsAnimation).onEnrollmentProgress(remaining);
        }
    }

    void onEnrollmentHelp() {
        if (mUdfpsAnimation instanceof UdfpsAnimationEnroll) {
            ((UdfpsAnimationEnroll) mUdfpsAnimation).onEnrollmentHelp();
        }
    }
}
