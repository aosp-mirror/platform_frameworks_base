/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.graphics.Color;
import android.os.Trace;

import com.android.systemui.statusbar.ScrimView;

/**
 * Possible states of the ScrimController state machine.
 */
public enum ScrimState {

    /**
     * Initial state.
     */
    UNINITIALIZED,

    /**
     * On the lock screen.
     */
    KEYGUARD {

        @Override
        public void prepare(ScrimState previousState) {
            // DisplayPowerManager will blank the screen, we'll just
            // set our scrim to black in this frame to avoid flickering and
            // fade it out afterwards.
            mBlankScreen = previousState == ScrimState.AOD;
            if (previousState == ScrimState.AOD) {
                updateScrimColor(mScrimInFront, 1, Color.BLACK);
            }
            mCurrentBehindAlpha = mScrimBehindAlphaKeyguard;
            mCurrentInFrontAlpha = 0;
        }
    },

    /**
     * Showing password challenge.
     */
    BOUNCER {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = ScrimController.SCRIM_BEHIND_ALPHA_UNLOCKING;
            mCurrentInFrontAlpha = ScrimController.SCRIM_IN_FRONT_ALPHA_LOCKED;
        }
    },

    /**
     * Changing screen brightness from quick settings.
     */
    BRIGHTNESS_MIRROR {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = 0;
            mCurrentInFrontAlpha = 0;
        }
    },

    /**
     * Always on display or screen off.
     */
    AOD {
        @Override
        public void prepare(ScrimState previousState) {
            if (previousState == ScrimState.PULSING) {
                updateScrimColor(mScrimInFront, 1, Color.BLACK);
            }
            final boolean alwaysOnEnabled = mDozeParameters.getAlwaysOn();
            mBlankScreen = previousState == ScrimState.PULSING;
            mCurrentBehindAlpha = 1;
            mCurrentInFrontAlpha = alwaysOnEnabled ? mAodFrontScrimAlpha : 1f;
            mCurrentInFrontTint = Color.BLACK;
            mCurrentBehindTint = Color.BLACK;
            // DisplayPowerManager will blank the screen for us, we just need
            // to set our state.
            mAnimateChange = false;
        }
    },

    /**
     * When phone wakes up because you received a notification.
     */
    PULSING {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = 1;
            mCurrentInFrontAlpha = 0;
            mCurrentInFrontTint = Color.BLACK;
            mCurrentBehindTint = Color.BLACK;
            mBlankScreen = true;
            updateScrimColor(mScrimInFront, 1, Color.BLACK);
        }
    },

    /**
     * Unlocked on top of an app (launcher or any other activity.)
     */
    UNLOCKED {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = 0;
            mCurrentInFrontAlpha = 0;
            mAnimationDuration = StatusBar.FADE_KEYGUARD_DURATION;

            if (previousState == ScrimState.AOD) {
                // Fade from black to transparent when coming directly from AOD
                updateScrimColor(mScrimInFront, 1, Color.BLACK);
                updateScrimColor(mScrimBehind, 1, Color.BLACK);
                // Scrims should still be black at the end of the transition.
                mCurrentInFrontTint = Color.BLACK;
                mCurrentBehindTint = Color.BLACK;
                mBlankScreen = true;
            } else {
                // Scrims should still be black at the end of the transition.
                mCurrentInFrontTint = Color.TRANSPARENT;
                mCurrentBehindTint = Color.TRANSPARENT;
                mBlankScreen = false;
            }
        }
    };

    boolean mBlankScreen = false;
    long mAnimationDuration = ScrimController.ANIMATION_DURATION;
    int mCurrentInFrontTint = Color.TRANSPARENT;
    int mCurrentBehindTint = Color.TRANSPARENT;
    boolean mAnimateChange = true;
    float mCurrentInFrontAlpha;
    float mCurrentBehindAlpha;
    float mAodFrontScrimAlpha;
    float mScrimBehindAlphaKeyguard;
    ScrimView mScrimInFront;
    ScrimView mScrimBehind;
    DozeParameters mDozeParameters;

    public void init(ScrimView scrimInFront, ScrimView scrimBehind, DozeParameters dozeParameters) {
        mScrimInFront = scrimInFront;
        mScrimBehind = scrimBehind;
        mDozeParameters = dozeParameters;
    }

    public void prepare(ScrimState previousState) {
    }

    public float getFrontAlpha() {
        return mCurrentInFrontAlpha;
    }

    public float getBehindAlpha() {
        return mCurrentBehindAlpha;
    }

    public int getFrontTint() {
        return mCurrentInFrontTint;
    }

    public int getBehindTint() {
        return mCurrentBehindTint;
    }

    public long getAnimationDuration() {
        return mAnimationDuration;
    }

    public boolean getBlanksScreen() {
        return mBlankScreen;
    }

    public void updateScrimColor(ScrimView scrim, float alpha, int tint) {
        Trace.traceCounter(Trace.TRACE_TAG_APP,
                scrim == mScrimInFront ? "front_scrim_alpha" : "back_scrim_alpha",
                (int) (alpha * 255));

        Trace.traceCounter(Trace.TRACE_TAG_APP,
                scrim == mScrimInFront ? "front_scrim_tint" : "back_scrim_tint",
                Color.alpha(tint));

        scrim.setTint(tint);
        scrim.setViewAlpha(alpha);
    }

    public boolean getAnimateChange() {
        return mAnimateChange;
    }

    public void setAodFrontScrimAlpha(float aodFrontScrimAlpha) {
        mAodFrontScrimAlpha = aodFrontScrimAlpha;
    }

    public void setScrimBehindAlphaKeyguard(float scrimBehindAlphaKeyguard) {
        mScrimBehindAlphaKeyguard = scrimBehindAlphaKeyguard;
    }
}