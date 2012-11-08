/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Some common functions that are useful for KeyguardSecurityViews.
 */
public class KeyguardSecurityViewHelper {

    public static void showBouncer(SecurityMessageDisplay securityMessageDisplay,
            View ecaView, Drawable bouncerFrame, int duration) {
        if (securityMessageDisplay != null) {
            securityMessageDisplay.showBouncer(duration);
        }
        if (ecaView != null) {
            if (duration > 0) {
                Animator anim = ObjectAnimator.ofFloat(ecaView, "alpha", 0f);
                anim.setDuration(duration);
                anim.start();
            } else {
                ecaView.setAlpha(0f);
            }
        }
        if (bouncerFrame != null) {
            if (duration > 0) {
                Animator anim = ObjectAnimator.ofInt(bouncerFrame, "alpha", 0, 255);
                anim.setDuration(duration);
                anim.start();
            } else {
                bouncerFrame.setAlpha(255);
            }
        }
    }

    public static void hideBouncer(SecurityMessageDisplay securityMessageDisplay,
            View ecaView, Drawable bouncerFrame, int duration) {
        if (securityMessageDisplay != null) {
            securityMessageDisplay.hideBouncer(duration);
        }
        if (ecaView != null) {
            if (duration > 0) {
                Animator anim = ObjectAnimator.ofFloat(ecaView, "alpha", 1f);
                anim.setDuration(duration);
                anim.start();
            } else {
                ecaView.setAlpha(1f);
            }
        }
        if (bouncerFrame != null) {
            if (duration > 0) {
                Animator anim = ObjectAnimator.ofInt(bouncerFrame, "alpha", 255, 0);
                anim.setDuration(duration);
                anim.start();
            } else {
                bouncerFrame.setAlpha(0);
            }
        }
    }
}
