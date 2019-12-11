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
            mFrontTint = Color.BLACK;
            mBehindTint = Color.BLACK;
            mBubbleTint = Color.TRANSPARENT;

            mFrontAlpha = 0;
            mBehindAlpha = mScrimBehindAlphaKeyguard;
            mBubbleAlpha = 0;
        }
    },

    /**
     * Showing password challenge on the keyguard.
     */
    BOUNCER(1) {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;
            mFrontAlpha = 0f;
            mBubbleAlpha = 0f;
        }
    },

    /**
     * Showing password challenge on top of a FLAG_SHOW_WHEN_LOCKED activity.
     */
    BOUNCER_SCRIMMED(2) {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = 0;
            mBubbleAlpha = 0f;
            mFrontAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;
        }
    },

    /**
     * Changing screen brightness from quick settings.
     */
    BRIGHTNESS_MIRROR(3) {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = 0;
            mFrontAlpha = 0;
            mBubbleAlpha = 0;
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

            mFrontTint = Color.BLACK;
            mFrontAlpha = alwaysOnEnabled ? mAodFrontScrimAlpha : 1f;

            mBehindTint = Color.BLACK;
            mBehindAlpha = ScrimController.TRANSPARENT;

            mBubbleTint = Color.TRANSPARENT;
            mBubbleAlpha = ScrimController.TRANSPARENT;

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
            mFrontAlpha = mAodFrontScrimAlpha;
            mBubbleAlpha = 0f;
            mBehindTint = Color.BLACK;
            mFrontTint = Color.BLACK;
            mBlankScreen = mDisplayRequiresBlanking;
            mAnimationDuration = mWakeLockScreenSensorActive
                    ? ScrimController.ANIMATION_DURATION_LONG : ScrimController.ANIMATION_DURATION;

            // Wake sensor will show the wallpaper, let's fade from black. Otherwise it will
            // feel like the screen is flashing if the wallpaper is light.
            if (mWakeLockScreenSensorActive && previousState == AOD) {
                updateScrimColor(mScrimBehind, 1f /* alpha */, Color.BLACK);
            }
        }

        @Override
        public float getBehindAlpha() {
            return mWakeLockScreenSensorActive ? ScrimController.WAKE_SENSOR_SCRIM_ALPHA
                    : AOD.getBehindAlpha();
        }
    },

    /**
     * Unlocked on top of an app (launcher or any other activity.)
     */
    UNLOCKED(6) {
        @Override
        public void prepare(ScrimState previousState) {
            // State that UI will sync to.
            mBehindAlpha = 0;
            mFrontAlpha = 0;
            mBubbleAlpha = 0;

            mAnimationDuration = mKeyguardFadingAway
                    ? mKeyguardFadingAwayDuration
                    : StatusBar.FADE_KEYGUARD_DURATION;

            mAnimateChange = !mLaunchingAffordanceWithPreview;

            mFrontTint = Color.TRANSPARENT;
            mBehindTint = Color.TRANSPARENT;
            mBubbleTint = Color.TRANSPARENT;
            mBlankScreen = false;

            if (previousState == ScrimState.AOD) {
                // Set all scrims black, before they fade transparent.
                updateScrimColor(mScrimInFront, 1f /* alpha */, Color.BLACK /* tint */);
                updateScrimColor(mScrimBehind, 1f /* alpha */, Color.BLACK /* tint */);
                updateScrimColor(mScrimForBubble, 1f /* alpha */, Color.BLACK /* tint */);

                // Scrims should still be black at the end of the transition.
                mFrontTint = Color.BLACK;
                mBehindTint = Color.BLACK;
                mBubbleTint = Color.BLACK;
                mBlankScreen = true;
            }
        }
    },

    /**
     * Unlocked with a bubble expanded.
     */
    BUBBLE_EXPANDED(7) {
        @Override
        public void prepare(ScrimState previousState) {
            mFrontTint = Color.TRANSPARENT;
            mBehindTint = Color.TRANSPARENT;
            mBubbleTint = Color.TRANSPARENT;

            mFrontAlpha = ScrimController.TRANSPARENT;
            mBehindAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;
            mBubbleAlpha = ScrimController.GRADIENT_SCRIM_ALPHA_BUSY;

            mAnimationDuration = ScrimController.ANIMATION_DURATION;
            mBlankScreen = false;
        }
    };

    boolean mBlankScreen = false;
    long mAnimationDuration = ScrimController.ANIMATION_DURATION;
    int mFrontTint = Color.TRANSPARENT;
    int mBehindTint = Color.TRANSPARENT;
    int mBubbleTint = Color.TRANSPARENT;

    boolean mAnimateChange = true;
    float mAodFrontScrimAlpha;
    float mFrontAlpha;
    float mBehindAlpha;
    float mBubbleAlpha;

    float mScrimBehindAlphaKeyguard;
    ScrimView mScrimInFront;
    ScrimView mScrimBehind;
    ScrimView mScrimForBubble;

    DozeParameters mDozeParameters;
    boolean mDisplayRequiresBlanking;
    boolean mWallpaperSupportsAmbientMode;
    int mIndex;
    boolean mHasBackdrop;
    boolean mLaunchingAffordanceWithPreview;
    boolean mWakeLockScreenSensorActive;
    boolean mKeyguardFadingAway;
    long mKeyguardFadingAwayDuration;

    ScrimState(int index) {
        mIndex = index;
    }

    public void init(ScrimView scrimInFront, ScrimView scrimBehind, ScrimView scrimForBubble,
            DozeParameters dozeParameters) {
        mScrimInFront = scrimInFront;
        mScrimBehind = scrimBehind;
        mScrimForBubble = scrimForBubble;

        mDozeParameters = dozeParameters;
        mDisplayRequiresBlanking = dozeParameters.getDisplayNeedsBlanking();
    }

    /** Prepare state for transition. */
    public void prepare(ScrimState previousState) {
    }

    public int getIndex() {
        return mIndex;
    }

    public float getFrontAlpha() {
        return mFrontAlpha;
    }

    public float getBehindAlpha() {
        return mBehindAlpha;
    }

    public float getBubbleAlpha() {
        return mBubbleAlpha;
    }

    public int getFrontTint() {
        return mFrontTint;
    }

    public int getBehindTint() {
        return mBehindTint;
    }

    public int getBubbleTint() {
        return mBubbleTint;
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

    public void setWakeLockScreenSensorActive(boolean active) {
        mWakeLockScreenSensorActive = active;
    }

    public void setKeyguardFadingAway(boolean fadingAway, long duration) {
        mKeyguardFadingAway = fadingAway;
        mKeyguardFadingAwayDuration = duration;
    }
}