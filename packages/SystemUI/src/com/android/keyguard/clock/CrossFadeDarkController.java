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
 * limitations under the License.
 */
package com.android.keyguard.clock;

import android.view.View;

/**
 * Controls transition to dark state by cross fading between views.
 */
final class CrossFadeDarkController {

    private final View mFadeInView;
    private final View mFadeOutView;

    /**
     * Creates a new controller that fades between views.
     *
     * @param fadeInView View to fade in when transitioning to AOD.
     * @param fadeOutView View to fade out when transitioning to AOD.
     */
    CrossFadeDarkController(View fadeInView, View fadeOutView) {
        mFadeInView = fadeInView;
        mFadeOutView = fadeOutView;
    }

    /**
     * Sets the amount the system has transitioned to the dark state.
     *
     * @param darkAmount Amount of transition to dark state: 1f for AOD and 0f for lock screen.
     */
    void setDarkAmount(float darkAmount) {
        mFadeInView.setAlpha(Math.max(0f, 2f * darkAmount - 1f));
        if (darkAmount == 0f) {
            mFadeInView.setVisibility(View.GONE);
        } else {
            if (mFadeInView.getVisibility() == View.GONE) {
                mFadeInView.setVisibility(View.VISIBLE);
            }
        }
        mFadeOutView.setAlpha(Math.max(0f, 1f - 2f * darkAmount));
        if (darkAmount == 1f) {
            mFadeOutView.setVisibility(View.GONE);
        } else {
            if (mFadeOutView.getVisibility() == View.GONE) {
                mFadeOutView.setVisibility(View.VISIBLE);
            }
        }
    }
}
