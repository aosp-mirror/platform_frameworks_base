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

import com.android.systemui.doze.DozeLog;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

/**
 * Possible states of the ScrimController state machine.
 */
public enum ScrimState {

    /**
     * Initial state.
     */
    UNINITIALIZED(-1),

    /**
     * On the lock screen.
     */
    KEYGUARD(0) {

        @Override
        public void prepare(ScrimState previousState) {
            mBlankScreen = false;
            if (previousState == ScrimState.AOD) {
                mAnimationDuration = StackStateAnimator.ANIMATION_DURATION_WAKEUP;
                if (mDisplayRequiresBlanking) {
                    // DisplayPowerManager will blank the screen, we'll just
                    // set our scrim to black in this frame to avoid flickering and
                    // fade it out afterwards.
                    mBlankScreen = true;
                }
            } else if (previousState == ScrimState.KEYGUARD) {
                mAnimationDuration = StackStateAnimator.ANIMATION_DURATION_WAKEUP;
            } else {
                mAnimationDuration = ScrimController.ANIMATION_DURATION;
            }
            mCurrentInFrontTint = Color.BLACK;
            mCurrentBehindTint = Color.BLACK;
            mCurrentBehindAlpha = mScrimBehindAlphaKeyguard;
            mCurrentInFrontAlpha = 0;
        }
    },

    /**
     * Showing password challenge on the keyguard.
     */
    BOUNCER(1) {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;
            mCurrentInFrontAlpha = 0f;
        }
    },

    /**
     * Showing password challenge on top of a FLAG_SHOW_WHEN_LOCKED activity.
     */
    BOUNCER_SCRIMMED(2) {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = 0;
            mCurrentInFrontAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;
        }
    },

    /**
     * Changing screen brightness from quick settings.
     */
    BRIGHTNESS_MIRROR(3) {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = 0;
            mCurrentInFrontAlpha = 0;
        }
    },

    /**
     * Always on display or screen off.
     */
    AOD(4) {
        @Override
        public void prepare(ScrimState previousState) {
            final boolean alwaysOnEnabled = mDozeParameters.getAlwaysOn();
            mBlankScreen = mDisplayRequiresBlanking;
            mCurrentInFrontAlpha = alwaysOnEnabled ? mAodFrontScrimAlpha : 1f;
            mCurrentInFrontTint = Color.BLACK;
            mCurrentBehindTint = Color.BLACK;
            mAnimationDuration = ScrimController.ANIMATION_DURATION_LONG;
            // DisplayPowerManager may blank the screen for us,
            // in this case we just need to set our state.
            mAnimateChange = mDozeParameters.shouldControlScreenOff();
        }

        @Override
        public float getBehindAlpha() {
            return mWallpaperSupportsAmbientMode && !mHasBackdrop ? 0f : 1f;
        }

        @Override
        public boolean isLowPowerState() {
            return true;
        }
    },

    /**
     * When phone wakes up because you received a notification.
     */
    PULSING(5) {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentInFrontAlpha = 0f;
            if (mPulseReason == DozeLog.PULSE_REASON_NOTIFICATION
                    || mPulseReason == DozeLog.PULSE_REASON_DOCKING
                    || mPulseReason == DozeLog.PULSE_REASON_INTENT) {
                mCurrentBehindAlpha = previousState.getBehindAlpha();
                mCurrentBehindTint = Color.BLACK;
            } else {
                mCurrentBehindAlpha = mScrimBehindAlphaKeyguard;
                mCurrentBehindTint = Color.TRANSPARENT;
            }
            mBlankScreen = mDisplayRequiresBlanking;
        }
    },

    /**
     * Unlocked on top of an app (launcher or any other activity.)
     */
    UNLOCKED(6) {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentBehindAlpha = 0;
            mCurrentInFrontAlpha = 0;
            mAnimationDuration = StatusBar.FADE_KEYGUARD_DURATION;
            mAnimateChange = !mLaunchingAffordanceWithPreview;

            if (previousState == ScrimState.AOD) {
                // Fade from black to transparent when coming directly from AOD
                updateScrimColor(mScrimInFront, 1, Color.BLACK);
                updateScrimColor(mScrimBehind, 1, Color.BLACK);
                // Scrims should still be black at the end of the transition.
                mCurrentInFrontTint = Color.BLACK;
                mCurrentBehindTint = Color.BLACK;
                mBlankScreen = true;
            } else {
                mCurrentInFrontTint = Color.TRANSPARENT;
                mCurrentBehindTint = Color.TRANSPARENT;
                mBlankScreen = false;
            }
        }
    },

    /**
     * Unlocked with a bubble expanded.
     */
    BUBBLE_EXPANDED(7) {
        @Override
        public void prepare(ScrimState previousState) {
            mCurrentInFrontTint = Color.TRANSPARENT;
            mCurrentBehindTint = Color.TRANSPARENT;
            mAnimationDuration = ScrimController.ANIMATION_DURATION;
            mCurrentBehindAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;
            mBlankScreen = false;
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
    boolean mDisplayRequiresBlanking;
    boolean mWallpaperSupportsAmbientMode;
    int mIndex;
    boolean mHasBackdrop;
    boolean mLaunchingAffordanceWithPreview;
    int mPulseReason;

    ScrimState(int index) {
        mIndex = index;
    }

    public void init(ScrimView scrimInFront, ScrimView scrimBehind, DozeParameters dozeParameters) {
        mScrimInFront = scrimInFront;
        mScrimBehind = scrimBehind;
        mDozeParameters = dozeParameters;
        mDisplayRequiresBlanking = dozeParameters.getDisplayNeedsBlanking();
    }

    public void prepare(ScrimState previousState) {
    }

    public int getIndex() {
        return mIndex;
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

    public void setPulseReason(int pulseReason) {
        mPulseReason = pulseReason;
    }

    public void setScrimBehindAlphaKeyguard(float scrimBehindAlphaKeyguard) {
        mScrimBehindAlphaKeyguard = scrimBehindAlphaKeyguard;
    }

    public void setWallpaperSupportsAmbientMode(boolean wallpaperSupportsAmbientMode) {
        mWallpaperSupportsAmbientMode = wallpaperSupportsAmbientMode;
    }

    public void setLaunchingAffordanceWithPreview(boolean launchingAffordanceWithPreview) {
        mLaunchingAffordanceWithPreview = launchingAffordanceWithPreview;
    }

    public boolean isLowPowerState() {
        return false;
    }

    public void setHasBackdrop(boolean hasBackdrop) {
        mHasBackdrop = hasBackdrop;
    }
}