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
import android.view.View;
import android.view.ViewGroup;
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
public abstract class UdfpsAnimationView extends FrameLayout {
    private float mDialogSuggestedAlpha = 1f;
    private float mNotificationShadeExpansion = 0f;

    // Used for Udfps ellipse detection when flag is true, set by AnimationViewController
    boolean mUseExpandedOverlay = false;

    // mAlpha takes into consideration the status bar expansion amount and dialog suggested alpha
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

    void onDisplayConfiguring() {
        getDrawable().setDisplayConfigured(true);
        getDrawable().invalidateSelf();
    }

    void onDisplayUnconfigured() {
        getDrawable().setDisplayConfigured(false);
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

    /**
     * @return current alpha
     */
    protected int updateAlpha() {
        int alpha = calculateAlpha();
        getDrawable().setAlpha(alpha);

        // this is necessary so that touches won't be intercepted if udfps is paused:
        if (mPauseAuth && alpha == 0 && getParent() != null) {
            ((ViewGroup) getParent()).setVisibility(View.INVISIBLE);
        } else {
            ((ViewGroup) getParent()).setVisibility(View.VISIBLE);
        }

        return alpha;
    }

    int calculateAlpha() {
        int alpha = expansionToAlpha(mNotificationShadeExpansion);
        alpha *= mDialogSuggestedAlpha;
        mAlpha = alpha;

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

    /**
     * Converts coordinates of RectF relative to the screen to coordinates relative to this view.
     *
     * @param bounds RectF based off screen coordinates in current orientation
     */
    RectF getBoundsRelativeToView(RectF bounds) {
        int[] pos = getLocationOnScreen();

        RectF output = new RectF(
                bounds.left - pos[0],
                bounds.top - pos[1],
                bounds.right - pos[0],
                bounds.bottom - pos[1]
        );

        return output;
    }

    /**
     * Set the suggested alpha based on whether a dialog was recently shown or hidden.
     * @param dialogSuggestedAlpha value from 0f to 1f.
     */
    public void setDialogSuggestedAlpha(float dialogSuggestedAlpha) {
        mDialogSuggestedAlpha = dialogSuggestedAlpha;
        updateAlpha();
    }

    public float getDialogSuggestedAlpha() {
        return mDialogSuggestedAlpha;
    }

    /**
     * Sets the amount the notification shade is expanded. This will influence the opacity of the
     * this visual affordance.
     * @param expansion amount the shade has expanded from 0f to 1f.
     */
    public void onExpansionChanged(float expansion) {
        mNotificationShadeExpansion = expansion;
        updateAlpha();
    }

    /**
     * @return true if handled
     */
    boolean dozeTimeTick() {
        return false;
    }
}
