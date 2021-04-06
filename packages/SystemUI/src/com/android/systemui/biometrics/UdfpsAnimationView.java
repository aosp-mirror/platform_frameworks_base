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

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Base class for views containing UDFPS animations. Note that this is a FrameLayout so that we
 * can support multiple child views drawing in the same region around the sensor location.
 *
 * - hides animation view when pausing auth
 * - sends illumination events to fingerprint drawable
 * - sends sensor rect updates to fingerprint drawable
 * - optionally can override dozeTimeTick to adjust views for burn-in mitigation
 */
abstract class UdfpsAnimationView extends FrameLayout {
    // mAlpha takes into consideration the status bar expansion amount to fade out icon when
    // the status bar is expanded
    private int mAlpha;
    boolean mPauseAuth;

    public UdfpsAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Fingerprint drawable
     */
    abstract UdfpsDrawable getDrawable();

    void onSensorRectUpdated(RectF bounds) {
        getDrawable().onSensorRectUpdated(bounds);
    }

    void onIlluminationStarting() {
        getDrawable().setIlluminationShowing(true);
        getDrawable().invalidateSelf();
    }

    void onIlluminationStopped() {
        getDrawable().setIlluminationShowing(false);
        getDrawable().invalidateSelf();
    }

    /**
     * @return true if changed
     */
    boolean setPauseAuth(boolean pauseAuth) {
        if (pauseAuth != mPauseAuth) {
            mPauseAuth = pauseAuth;
            updateAlpha();
            return true;
        }
        return false;
    }

    protected void updateAlpha() {
        getDrawable().setAlpha(calculateAlpha());
    }

    int calculateAlpha() {
        return mPauseAuth ? mAlpha : 255;
    }

    boolean isPauseAuth() {
        return mPauseAuth;
    }

    private int expansionToAlpha(float expansion) {
        // Fade to 0 opacity when reaching this expansion amount
        final float maxExpansion = 0.4f;

        if (expansion >= maxExpansion) {
            return 0; // transparent
        }

        final float percent = expansion / maxExpansion;
        return (int) ((1 - percent) * 255);
    }

    public void onExpansionChanged(float expansion, boolean expanded) {
        mAlpha = expansionToAlpha(expansion);
        updateAlpha();
    }

    /**
     * @return true if handled
     */
    boolean dozeTimeTick() {
        return false;
    }
}
