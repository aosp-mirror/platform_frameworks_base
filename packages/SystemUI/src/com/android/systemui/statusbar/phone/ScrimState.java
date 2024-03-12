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

import com.android.systemui.dock.DockManager;
import com.android.systemui.res.R;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

/**
 * Possible states of the ScrimController state machine.
 */
public enum ScrimState {

    /**
     * Initial state.
     */
    UNINITIALIZED,

    /**
     * When turned off by sensors (prox, presence.)
     */
    OFF {
        @Override
        public void prepare(ScrimState previousState) {
            mFrontTint = mBackgroundColor;
            mBehindTint = mBackgroundColor;

            mFrontAlpha = 1f;
            mBehindAlpha = 1f;

            mAnimationDuration = ScrimController.ANIMATION_DURATION_LONG;
        }

        @Override
        public boolean isLowPowerState() {
            return true;
        }
    },

    /**
     * On the lock screen.
     */
    KEYGUARD {
        @Override
        public void prepare(ScrimState previousState) {
            mBlankScreen = false;
            if (previousState == ScrimState.AOD) {
                mAnimationDuration = StackStateAnimator.ANIMATION_DURATION_WAKEUP_SCRIM;
                if (mDisplayRequiresBlanking) {
                    // DisplayPowerManager will blank the screen, we'll just
                    // set our scrim to black in this frame to avoid flickering and
                    // fade it out afterwards.
                    mBlankScreen = true;
                }
            } else if (previousState == ScrimState.KEYGUARD) {
                mAnimationDuration = StackStateAnimator.ANIMATION_DURATION_WAKEUP_SCRIM;
            } else {
                mAnimationDuration = ScrimController.ANIMATION_DURATION;
            }
            mFrontTint = mBackgroundColor;
            mBehindTint = mBackgroundColor;
            mNotifTint = mClipQsScrim ? mBackgroundColor : Color.TRANSPARENT;

            mFrontAlpha = 0;
            mBehindAlpha = mClipQsScrim ? 1 : mScrimBehindAlphaKeyguard;
            mNotifAlpha = mClipQsScrim ? mScrimBehindAlphaKeyguard : 0;
            if (mClipQsScrim) {
                updateScrimColor(mScrimBehind, 1f /* alpha */, mBackgroundColor);
            }
        }
    },

    AUTH_SCRIMMED_SHADE {
        @Override
        public void prepare(ScrimState previousState) {
            // notif scrim alpha values are determined by ScrimController#applyState
            // based on the shade expansion

            mFrontTint = mBackgroundColor;
            mFrontAlpha = .66f;

            mBehindTint = mBackgroundColor;
            mBehindAlpha = 1f;
        }
    },

    AUTH_SCRIMMED {
        @Override
        public void prepare(ScrimState previousState) {
            mNotifTint = previousState.mNotifTint;
            mNotifAlpha = previousState.mNotifAlpha;

            mBehindTint = previousState.mBehindTint;
            mBehindAlpha = previousState.mBehindAlpha;

            mFrontTint = mBackgroundColor;
            mFrontAlpha = .66f;
        }
    },

    /**
     * Showing password challenge on the keyguard.
     */
    BOUNCER {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = mClipQsScrim ? 1 : mDefaultScrimAlpha;
            mBehindTint = mClipQsScrim ? mBackgroundColor : mSurfaceColor;
            mNotifAlpha = mClipQsScrim ? mDefaultScrimAlpha : 0;
            mNotifTint = Color.TRANSPARENT;
            mFrontAlpha = 0f;
        }

        @Override
        public void setSurfaceColor(int surfaceColor) {
            super.setSurfaceColor(surfaceColor);
            if (!mClipQsScrim) {
                mBehindTint = mSurfaceColor;
            }
        }
    },

    /**
     * Showing password challenge on top of a FLAG_SHOW_WHEN_LOCKED activity.
     */
    BOUNCER_SCRIMMED {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = 0;
            mFrontAlpha = mDefaultScrimAlpha;
        }
    },

    SHADE_LOCKED {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = mClipQsScrim ? 1 : mDefaultScrimAlpha;
            mNotifAlpha = 1f;
            mFrontAlpha = 0f;
            mBehindTint = mClipQsScrim ? Color.TRANSPARENT : mBackgroundColor;

            if (mClipQsScrim) {
                updateScrimColor(mScrimBehind, 1f /* alpha */, mBackgroundColor);
            }
        }
    },

    /**
     * Changing screen brightness from quick settings.
     */
    BRIGHTNESS_MIRROR {
        @Override
        public void prepare(ScrimState previousState) {
            mBehindAlpha = 0;
            mFrontAlpha = 0;
        }
    },

    /**
     * Always on display or screen off.
     */
    AOD {
        @Override
        public void prepare(ScrimState previousState) {
            final boolean alwaysOnEnabled = mDozeParameters.getAlwaysOn();
            final boolean quickPickupEnabled = mDozeParameters.isQuickPickupEnabled();
            final boolean isDocked = mDockManager.isDocked();
            mBlankScreen = mDisplayRequiresBlanking;

            mFrontTint = mBackgroundColor;
            mFrontAlpha = (alwaysOnEnabled || isDocked || quickPickupEnabled)
                    ? mAodFrontScrimAlpha : 1f;

            mBehindTint = mBackgroundColor;
            mBehindAlpha = ScrimController.TRANSPARENT;

            mAnimationDuration = ScrimController.ANIMATION_DURATION_LONG;
            // DisplayPowerManager may blank the screen for us, or we might blank it for ourselves
            // by animating the screen off via the LightRevelScrim. In either case we just need to
            // set our state.
            mAnimateChange = mDozeParameters.shouldControlScreenOff()
                    && !mDozeParameters.shouldShowLightRevealScrim();
        }

        @Override
        public float getMaxLightRevealScrimAlpha() {
            return mWallpaperSupportsAmbientMode && !mHasBackdrop ? 0f : 1f;
        }

        @Override
        public boolean isLowPowerState() {
            return true;
        }

        @Override
        public boolean shouldBlendWithMainColor() {
            return false;
        }
    },

    /**
     * When phone wakes up because you received a notification.
     */
    PULSING {
        @Override
        public void prepare(ScrimState previousState) {
            mFrontAlpha = mAodFrontScrimAlpha;
            mBehindTint = mBackgroundColor;
            mFrontTint = mBackgroundColor;
            mBlankScreen = mDisplayRequiresBlanking;
            mAnimationDuration = mWakeLockScreenSensorActive
                    ? ScrimController.ANIMATION_DURATION_LONG : ScrimController.ANIMATION_DURATION;
        }
        @Override
        public float getMaxLightRevealScrimAlpha() {
            return mWakeLockScreenSensorActive ? ScrimController.WAKE_SENSOR_SCRIM_ALPHA
                    : AOD.getMaxLightRevealScrimAlpha();
        }
    },

    /**
     * Unlocked on top of an app (launcher or any other activity.)
     */
    UNLOCKED {
        @Override
        public void prepare(ScrimState previousState) {
            // State that UI will sync to.
            mBehindAlpha = mClipQsScrim ? 1 : 0;
            mNotifAlpha = 0;
            mFrontAlpha = 0;
            mAnimationDuration = mKeyguardFadingAway
                    ? mKeyguardFadingAwayDuration
                    : CentralSurfaces.FADE_KEYGUARD_DURATION;

            boolean fromAod = previousState == AOD || previousState == PULSING;
            // If launch/occlude animations were playing, they already animated the scrim
            // alpha to 0f as part of the animation. If we animate it now, we'll set it back
            // to 1f and animate it back to 0f, causing an unwanted scrim flash.
            mAnimateChange = !mLaunchingAffordanceWithPreview
                    && !mOccludeAnimationPlaying
                    && !fromAod;

            mFrontTint = Color.TRANSPARENT;
            mBehindTint = mBackgroundColor;
            mBlankScreen = false;

            if (mDisplayRequiresBlanking && previousState == ScrimState.AOD) {
                // Set all scrims black, before they fade transparent.
                updateScrimColor(mScrimInFront, 1f /* alpha */, mBackgroundColor /* tint */);
                updateScrimColor(mScrimBehind, 1f /* alpha */, mBackgroundColor /* tint */);

                // Scrims should still be black at the end of the transition.
                mFrontTint = mBackgroundColor;
                mBehindTint = mBackgroundColor;
                mBlankScreen = true;
            }

            if (mClipQsScrim) {
                updateScrimColor(mScrimBehind, 1f /* alpha */, mBackgroundColor);
            }
        }
    },

    DREAMING {
        @Override
        public void prepare(ScrimState previousState) {
            mFrontTint = Color.TRANSPARENT;
            mBehindTint = mBackgroundColor;
            mNotifTint = mClipQsScrim ? mBackgroundColor : Color.TRANSPARENT;

            mFrontAlpha = 0;
            mBehindAlpha = mClipQsScrim ? 1 : 0;
            mNotifAlpha = 0;

            mBlankScreen = false;

            if (mClipQsScrim) {
                updateScrimColor(mScrimBehind, 1f /* alpha */, mBackgroundColor);
            }
        }
    },

    /**
     * Device is on the lockscreen and user has swiped from the right edge to enter the glanceable
     * hub UI. From this state, the user can swipe from the left edge to go back to the lock screen,
     * as well as swipe down for the notifications and up for the bouncer.
     */
    GLANCEABLE_HUB {
        @Override
        public void prepare(ScrimState previousState) {
            // No scrims should be visible by default in this state.
            mBehindAlpha = 0;
            mNotifAlpha = 0;
            mFrontAlpha = 0;

            mFrontTint = Color.TRANSPARENT;
            mBehindTint = mBackgroundColor;
            mNotifTint = mClipQsScrim ? mBackgroundColor : Color.TRANSPARENT;
        }
    },

    /**
     * Device is dreaming and user has swiped from the right edge to enter the glanceable hub UI.
     * From this state, the user can swipe from the left edge to go back to the  dream, as well as
     * swipe down for the notifications and up for the bouncer.
     *
     * This is a separate state from {@link #GLANCEABLE_HUB} because the scrims behave differently
     * when the dream is running.
     */
    GLANCEABLE_HUB_OVER_DREAM {
        @Override
        public void prepare(ScrimState previousState) {
            GLANCEABLE_HUB.prepare(previousState);
        }
    };

    boolean mBlankScreen = false;
    long mAnimationDuration = ScrimController.ANIMATION_DURATION;
    int mFrontTint = Color.TRANSPARENT;
    int mBehindTint = Color.TRANSPARENT;
    int mNotifTint = Color.TRANSPARENT;
    int mSurfaceColor = Color.TRANSPARENT;

    boolean mAnimateChange = true;
    float mAodFrontScrimAlpha;
    float mFrontAlpha;
    float mBehindAlpha;
    float mNotifAlpha;

    float mScrimBehindAlphaKeyguard;
    float mDefaultScrimAlpha;
    ScrimView mScrimInFront;
    ScrimView mScrimBehind;

    DozeParameters mDozeParameters;
    DockManager mDockManager;
    boolean mDisplayRequiresBlanking;
    boolean mWallpaperSupportsAmbientMode;
    boolean mHasBackdrop;
    boolean mLaunchingAffordanceWithPreview;
    boolean mOccludeAnimationPlaying;
    boolean mWakeLockScreenSensorActive;
    boolean mKeyguardFadingAway;
    long mKeyguardFadingAwayDuration;
    boolean mClipQsScrim;
    int mBackgroundColor;

    public void init(ScrimView scrimInFront, ScrimView scrimBehind, DozeParameters dozeParameters,
            DockManager dockManager) {
        mBackgroundColor = scrimBehind.getContext().getColor(R.color.shade_scrim_background_dark);
        mScrimInFront = scrimInFront;
        mScrimBehind = scrimBehind;

        mDozeParameters = dozeParameters;
        mDockManager = dockManager;
        mDisplayRequiresBlanking = dozeParameters.getDisplayNeedsBlanking();
    }

    /** Prepare state for transition. */
    public void prepare(ScrimState previousState) {
    }

    /**
     * Whether a particular state should enable blending with extracted theme colors.
     */
    public boolean shouldBlendWithMainColor() {
        return true;
    }

    public float getFrontAlpha() {
        return mFrontAlpha;
    }

    public float getBehindAlpha() {
        return mBehindAlpha;
    }

    public float getMaxLightRevealScrimAlpha() {
        return 1f;
    }

    public float getNotifAlpha() {
        return mNotifAlpha;
    }

    public int getFrontTint() {
        return mFrontTint;
    }

    public int getBehindTint() {
        return mBehindTint;
    }

    public int getNotifTint() {
        return mNotifTint;
    }

    public long getAnimationDuration() {
        return mAnimationDuration;
    }

    public boolean getBlanksScreen() {
        return mBlankScreen;
    }

    public void updateScrimColor(ScrimView scrim, float alpha, int tint) {
        if (ScrimController.DEBUG_MODE) {
            tint = scrim == mScrimInFront ? ScrimController.DEBUG_FRONT_TINT
                    : ScrimController.DEBUG_BEHIND_TINT;
        }
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

    public void setDefaultScrimAlpha(float defaultScrimAlpha) {
        mDefaultScrimAlpha = defaultScrimAlpha;
    }

    public void setSurfaceColor(int surfaceColor) {
        mSurfaceColor = surfaceColor;
    }

    public void setWallpaperSupportsAmbientMode(boolean wallpaperSupportsAmbientMode) {
        mWallpaperSupportsAmbientMode = wallpaperSupportsAmbientMode;
    }

    public void setLaunchingAffordanceWithPreview(boolean launchingAffordanceWithPreview) {
        mLaunchingAffordanceWithPreview = launchingAffordanceWithPreview;
    }

    public void setOccludeAnimationPlaying(boolean occludeAnimationPlaying) {
        mOccludeAnimationPlaying = occludeAnimationPlaying;
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

    public void setClipQsScrim(boolean clipsQsScrim) {
        mClipQsScrim = clipsQsScrim;
    }
}
