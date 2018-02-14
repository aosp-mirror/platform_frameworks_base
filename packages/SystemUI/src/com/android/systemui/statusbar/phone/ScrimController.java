/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.AlarmManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.util.MathUtils;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.ColorExtractor.OnColorsChangedListener;
import com.android.internal.graphics.ColorUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.stack.ViewState;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
public class ScrimController implements ViewTreeObserver.OnPreDrawListener,
        OnHeadsUpChangedListener, OnColorsChangedListener, Dumpable {

    private static final String TAG = "ScrimController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final long ANIMATION_DURATION = 220;
    public static final Interpolator KEYGUARD_FADE_OUT_INTERPOLATOR
            = new PathInterpolator(0f, 0, 0.7f, 1f);
    public static final Interpolator KEYGUARD_FADE_OUT_INTERPOLATOR_LOCKED
            = new PathInterpolator(0.3f, 0f, 0.8f, 1f);

    /**
     * When both scrims have 0 alpha.
     */
    public static final int VISIBILITY_FULLY_TRANSPARENT = 0;
    /**
     * When scrims aren't transparent (alpha 0) but also not opaque (alpha 1.)
     */
    public static final int VISIBILITY_SEMI_TRANSPARENT = 1;
    /**
     * When at least 1 scrim is fully opaque (alpha set to 1.)
     */
    public static final int VISIBILITY_FULLY_OPAQUE = 2;
    /**
     * Default alpha value for most scrims.
     */
    public static final float GRADIENT_SCRIM_ALPHA = 0.45f;
    /**
     * A scrim varies its opacity based on a busyness factor, for example
     * how many notifications are currently visible.
     */
    public static final float GRADIENT_SCRIM_ALPHA_BUSY = 0.70f;
    /**
     * The most common scrim, the one under the keyguard.
     */
    protected static final float SCRIM_BEHIND_ALPHA_KEYGUARD = GRADIENT_SCRIM_ALPHA;
    /**
     * We fade out the bottom scrim when the bouncer is visible.
     */
    protected static final float SCRIM_BEHIND_ALPHA_UNLOCKING = 0.2f;
    /**
     * Opacity of the scrim behind the bouncer (the one doing actual background protection.)
     */
    protected static final float SCRIM_IN_FRONT_ALPHA_LOCKED = GRADIENT_SCRIM_ALPHA_BUSY;

    static final int TAG_KEY_ANIM = R.id.scrim;
    private static final int TAG_KEY_ANIM_TARGET = R.id.scrim_target;
    private static final int TAG_START_ALPHA = R.id.scrim_alpha_start;
    private static final int TAG_END_ALPHA = R.id.scrim_alpha_end;
    private static final float NOT_INITIALIZED = -1;

    private ScrimState mState = ScrimState.UNINITIALIZED;
    private final Context mContext;
    protected final ScrimView mScrimBehind;
    protected final ScrimView mScrimInFront;
    private final View mHeadsUpScrim;
    private final LightBarController mLightBarController;
    private final UnlockMethodCache mUnlockMethodCache;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DozeParameters mDozeParameters;
    private final AlarmTimeout mTimeTicker;

    private final SysuiColorExtractor mColorExtractor;
    private GradientColors mLockColors;
    private GradientColors mSystemColors;
    private boolean mNeedsDrawableColorUpdate;

    protected float mScrimBehindAlpha;
    protected float mScrimBehindAlphaResValue;
    protected float mScrimBehindAlphaKeyguard = SCRIM_BEHIND_ALPHA_KEYGUARD;
    protected float mScrimBehindAlphaUnlocking = SCRIM_BEHIND_ALPHA_UNLOCKING;

    // Assuming the shade is expanded during initialization
    private float mExpansionFraction = 1f;

    private boolean mDarkenWhileDragging;
    protected boolean mAnimateChange;
    private boolean mUpdatePending;
    private boolean mTracking;
    private boolean mAnimateKeyguardFadingOut;
    protected long mAnimationDuration = -1;
    private long mAnimationDelay;
    private Runnable mOnAnimationFinished;
    private boolean mDeferFinishedListener;
    private final Interpolator mInterpolator = new DecelerateInterpolator();
    private float mCurrentInFrontAlpha  = NOT_INITIALIZED;
    private float mCurrentBehindAlpha = NOT_INITIALIZED;
    private int mCurrentInFrontTint;
    private int mCurrentBehindTint;
    private boolean mWallpaperVisibilityTimedOut;
    private int mPinnedHeadsUpCount;
    private float mTopHeadsUpDragAmount;
    private View mDraggedHeadsUpView;
    private boolean mKeyguardFadingOutInProgress;
    private ValueAnimator mKeyguardFadeoutAnimation;
    private int mScrimsVisibility;
    private final Consumer<Integer> mScrimVisibleListener;
    private boolean mBlankScreen;
    private boolean mScreenBlankingCallbackCalled;
    private Callback mCallback;
    private boolean mWallpaperSupportsAmbientMode;
    private boolean mScreenOn;

    // Scrim blanking callbacks
    private Choreographer.FrameCallback mPendingFrameCallback;
    private Runnable mBlankingTransitionRunnable;

    private final WakeLock mWakeLock;
    private boolean mWakeLockHeld;

    public ScrimController(LightBarController lightBarController, ScrimView scrimBehind,
            ScrimView scrimInFront, View headsUpScrim, Consumer<Integer> scrimVisibleListener,
            DozeParameters dozeParameters, AlarmManager alarmManager) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        mHeadsUpScrim = headsUpScrim;
        mScrimVisibleListener = scrimVisibleListener;
        mContext = scrimBehind.getContext();
        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mLightBarController = lightBarController;
        mScrimBehindAlphaResValue = mContext.getResources().getFloat(R.dimen.scrim_behind_alpha);
        mTimeTicker = new AlarmTimeout(alarmManager, this::onHideWallpaperTimeout,
                "hide_aod_wallpaper", new Handler());
        mWakeLock = createWakeLock();
        // Scrim alpha is initially set to the value on the resource but might be changed
        // to make sure that text on top of it is legible.
        mScrimBehindAlpha = mScrimBehindAlphaResValue;
        mDozeParameters = dozeParameters;

        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        mColorExtractor.addOnColorsChangedListener(this);
        mLockColors = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK,
                ColorExtractor.TYPE_DARK, true /* ignoreVisibility */);
        mSystemColors = mColorExtractor.getColors(WallpaperManager.FLAG_SYSTEM,
                ColorExtractor.TYPE_DARK, true /* ignoreVisibility */);
        mNeedsDrawableColorUpdate = true;

        final ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].init(mScrimInFront, mScrimBehind, mDozeParameters);
            states[i].setScrimBehindAlphaKeyguard(mScrimBehindAlphaKeyguard);
        }
        mState = ScrimState.UNINITIALIZED;

        updateHeadsUpScrim(false);
        updateScrims();
    }

    public void transitionTo(ScrimState state) {
        transitionTo(state, null);
    }

    public void transitionTo(ScrimState state, Callback callback) {
        if (state == mState) {
            // Call the callback anyway, unless it's already enqueued
            if (callback != null && mCallback != callback) {
                callback.onFinished();
            }
            return;
        } else if (DEBUG) {
            Log.d(TAG, "State changed to: " + state);
        }

        if (state == ScrimState.UNINITIALIZED) {
            throw new IllegalArgumentException("Cannot change to UNINITIALIZED.");
        }

        final ScrimState oldState = mState;
        mState = state;
        Trace.traceCounter(Trace.TRACE_TAG_APP, "scrim_state", mState.getIndex());

        if (mCallback != null) {
            mCallback.onCancelled();
        }
        mCallback = callback;

        state.prepare(oldState);
        mScreenBlankingCallbackCalled = false;
        mAnimationDelay = 0;
        mBlankScreen = state.getBlanksScreen();
        mAnimateChange = state.getAnimateChange();
        mAnimationDuration = state.getAnimationDuration();
        mCurrentInFrontTint = state.getFrontTint();
        mCurrentBehindTint = state.getBehindTint();
        mCurrentInFrontAlpha = state.getFrontAlpha();
        mCurrentBehindAlpha = state.getBehindAlpha();
        applyExpansionToAlpha();

        // Cancel blanking transitions that were pending before we requested a new state
        if (mPendingFrameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(mPendingFrameCallback);
            mPendingFrameCallback = null;
        }
        if (getHandler().hasCallbacks(mBlankingTransitionRunnable)) {
            getHandler().removeCallbacks(mBlankingTransitionRunnable);
            mBlankingTransitionRunnable = null;
        }

        // Showing/hiding the keyguard means that scrim colors have to be switched, not necessary
        // to do the same when you're just showing the brightness mirror.
        mNeedsDrawableColorUpdate = state != ScrimState.BRIGHTNESS_MIRROR;

        if (mKeyguardFadeoutAnimation != null) {
            mKeyguardFadeoutAnimation.cancel();
        }

        // The device might sleep if it's entering AOD, we need to make sure that
        // the animation plays properly until the last frame.
        // It's important to avoid holding the wakelock unless necessary because
        // WakeLock#aqcuire will trigger an IPC and will cause jank.
        if (mState == ScrimState.AOD) {
            holdWakeLock();
        }

        // AOD wallpapers should fade away after a while
        if (mWallpaperSupportsAmbientMode && mDozeParameters.getAlwaysOn()
                && (mState == ScrimState.AOD || mState == ScrimState.PULSING)) {
            if (!mWallpaperVisibilityTimedOut) {
                mTimeTicker.schedule(mDozeParameters.getWallpaperAodDuration(),
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
            }
        } else {
            mTimeTicker.cancel();
            mWallpaperVisibilityTimedOut = false;
        }

        if (mKeyguardUpdateMonitor.needsSlowUnlockTransition() && mState == ScrimState.UNLOCKED) {
            // In case the user isn't unlocked, make sure to delay a bit because the system is hosed
            // with too many things at this case, in order to not skip the initial frames.
            mScrimInFront.postOnAnimationDelayed(this::scheduleUpdate, 16);
            mAnimationDelay = StatusBar.FADE_KEYGUARD_START_DELAY;
        } else if (!mDozeParameters.getAlwaysOn() && oldState == ScrimState.AOD) {
            // Execute first frame immediately when display was completely off.
            // Scheduling a frame isn't enough because the system may aggressively enter doze,
            // delaying callbacks or never triggering them until the power button is pressed.
            onPreDraw();
        } else {
            scheduleUpdate();
        }
    }

    public ScrimState getState() {
        return mState;
    }

    protected void setScrimBehindValues(float scrimBehindAlphaKeyguard,
            float scrimBehindAlphaUnlocking) {
        mScrimBehindAlphaKeyguard = scrimBehindAlphaKeyguard;
        mScrimBehindAlphaUnlocking = scrimBehindAlphaUnlocking;
        ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].setScrimBehindAlphaKeyguard(scrimBehindAlphaKeyguard);
        }
        scheduleUpdate();
    }

    public void onTrackingStarted() {
        mTracking = true;
        mDarkenWhileDragging = !mUnlockMethodCache.canSkipBouncer();
    }

    public void onExpandingFinished() {
        mTracking = false;
    }

    @VisibleForTesting
    protected void onHideWallpaperTimeout() {
        if (mState != ScrimState.AOD && mState != ScrimState.PULSING) {
            return;
        }

        holdWakeLock();
        mWallpaperVisibilityTimedOut = true;
        mAnimateChange = true;
        mAnimationDuration = mDozeParameters.getWallpaperFadeOutDuration();
        scheduleUpdate();
    }

    private void holdWakeLock() {
        if (!mWakeLockHeld) {
            if (mWakeLock != null) {
                mWakeLockHeld = true;
                mWakeLock.acquire();
            } else {
                Log.w(TAG, "Cannot hold wake lock, it has not been set yet");
            }
        }
    }

    /**
     * Current state of the shade expansion when pulling it from the top.
     * This value is 1 when on top of the keyguard and goes to 0 as the user drags up.
     *
     * The expansion fraction is tied to the scrim opacity.
     *
     * @param fraction From 0 to 1 where 0 means collapse and 1 expanded.
     */
    public void setPanelExpansion(float fraction) {
        if (mExpansionFraction != fraction) {
            mExpansionFraction = fraction;

            if (!(mState == ScrimState.UNLOCKED || mState == ScrimState.KEYGUARD)) {
                return;
            }

            applyExpansionToAlpha();

            if (mUpdatePending) {
                return;
            }

            if (mPinnedHeadsUpCount != 0) {
                updateHeadsUpScrim(false);
            }
            updateScrim(false /* animate */, mScrimInFront, mCurrentInFrontAlpha);
            updateScrim(false /* animate */, mScrimBehind, mCurrentBehindAlpha);
        }
    }

    private void applyExpansionToAlpha() {
        if (mState == ScrimState.UNLOCKED) {
            // Darken scrim as you pull down the shade when unlocked
            float behindFraction = getInterpolatedFraction();
            behindFraction = (float) Math.pow(behindFraction, 0.8f);
            mCurrentBehindAlpha = behindFraction * mScrimBehindAlphaKeyguard;
            mCurrentInFrontAlpha = 0;
        } else if (mState == ScrimState.KEYGUARD) {
            // Either darken of make the scrim transparent when you
            // pull down the shade
            float interpolatedFract = getInterpolatedFraction();
            if (mDarkenWhileDragging) {
                mCurrentBehindAlpha = MathUtils.lerp(mScrimBehindAlphaUnlocking,
                        mScrimBehindAlphaKeyguard, interpolatedFract);
                mCurrentInFrontAlpha = (1f - interpolatedFract) * SCRIM_IN_FRONT_ALPHA_LOCKED;
            } else {
                mCurrentBehindAlpha = MathUtils.lerp(0 /* start */, mScrimBehindAlphaKeyguard,
                        interpolatedFract);
                mCurrentInFrontAlpha = 0;
            }
        }
    }

    /**
     * Keyguard and shade scrim opacity varies according to how many notifications are visible.
     * @param notificationCount Number of visible notifications.
     */
    public void setNotificationCount(int notificationCount) {
        final float maxNotificationDensity = 3;
        float notificationDensity = Math.min(notificationCount / maxNotificationDensity, 1f);
        float newAlpha = MathUtils.map(0, 1,
                GRADIENT_SCRIM_ALPHA, GRADIENT_SCRIM_ALPHA_BUSY,
                notificationDensity);
        if (mScrimBehindAlphaKeyguard != newAlpha) {
            mScrimBehindAlphaKeyguard = newAlpha;

            if (mState == ScrimState.KEYGUARD || mState == ScrimState.BOUNCER) {
                scheduleUpdate();
            }
        }
    }

    /**
     * Sets the given drawable as the background of the scrim that shows up behind the
     * notifications.
     */
    public void setScrimBehindDrawable(Drawable drawable) {
        mScrimBehind.setDrawable(drawable);
    }

    protected void scheduleUpdate() {
        if (mUpdatePending) return;

        // Make sure that a frame gets scheduled.
        mScrimBehind.invalidate();
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    protected void updateScrims() {
        // Make sure we have the right gradients and their opacities will satisfy GAR.
        if (mNeedsDrawableColorUpdate) {
            mNeedsDrawableColorUpdate = false;
            final GradientColors currentScrimColors;
            if (mState == ScrimState.KEYGUARD || mState == ScrimState.BOUNCER) {
                // Always animate color changes if we're seeing the keyguard
                mScrimInFront.setColors(mLockColors, true /* animated */);
                mScrimBehind.setColors(mLockColors, true /* animated */);
                currentScrimColors = mLockColors;
            } else {
                // Only animate scrim color if the scrim view is actually visible
                boolean animateScrimInFront = mScrimInFront.getViewAlpha() != 0;
                boolean animateScrimBehind = mScrimBehind.getViewAlpha() != 0;
                mScrimInFront.setColors(mSystemColors, animateScrimInFront);
                mScrimBehind.setColors(mSystemColors, animateScrimBehind);
                currentScrimColors = mSystemColors;
            }

            // Calculate minimum scrim opacity for white or black text.
            int textColor = currentScrimColors.supportsDarkText() ? Color.BLACK : Color.WHITE;
            int mainColor = currentScrimColors.getMainColor();
            float minOpacity = ColorUtils.calculateMinimumBackgroundAlpha(textColor, mainColor,
                    4.5f /* minimumContrast */) / 255f;
            mScrimBehindAlpha = Math.max(mScrimBehindAlphaResValue, minOpacity);
            mLightBarController.setScrimColor(mScrimInFront.getColors());
        }

        // We want to override the back scrim opacity for AOD and PULSING
        // when it's time to fade the wallpaper away.
        boolean overrideBackScrimAlpha = (mState == ScrimState.PULSING || mState == ScrimState.AOD)
                && mWallpaperVisibilityTimedOut;
        if (overrideBackScrimAlpha) {
            mCurrentBehindAlpha = 1;
        }

        setScrimInFrontAlpha(mCurrentInFrontAlpha);
        setScrimBehindAlpha(mCurrentBehindAlpha);

        dispatchScrimsVisible();
    }

    private void dispatchScrimsVisible() {
        final int currentScrimVisibility;
        if (mScrimInFront.getViewAlpha() == 1 || mScrimBehind.getViewAlpha() == 1) {
            currentScrimVisibility = VISIBILITY_FULLY_OPAQUE;
        } else if (mScrimInFront.getViewAlpha() == 0 && mScrimBehind.getViewAlpha() == 0) {
            currentScrimVisibility = VISIBILITY_FULLY_TRANSPARENT;
        } else {
            currentScrimVisibility = VISIBILITY_SEMI_TRANSPARENT;
        }

        if (mScrimsVisibility != currentScrimVisibility) {
            mScrimsVisibility = currentScrimVisibility;
            mScrimVisibleListener.accept(currentScrimVisibility);
        }
    }

    private float getInterpolatedFraction() {
        float frac = mExpansionFraction;
        // let's start this 20% of the way down the screen
        frac = frac * 1.2f - 0.2f;
        if (frac <= 0) {
            return 0;
        } else {
            // woo, special effects
            return (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2f))));
        }
    }

    private void setScrimBehindAlpha(float alpha) {
        setScrimAlpha(mScrimBehind, alpha);
    }

    private void setScrimInFrontAlpha(float alpha) {
        setScrimAlpha(mScrimInFront, alpha);
    }

    private void setScrimAlpha(View scrim, float alpha) {
        if (alpha == 0f) {
            scrim.setClickable(false);
        } else {
            // Eat touch events (unless dozing).
            scrim.setClickable(!(mState == ScrimState.AOD));
        }
        updateScrim(mAnimateChange, scrim, alpha);
    }

    private void updateScrimColor(View scrim, float alpha, int tint) {
        alpha = Math.max(0, Math.min(1.0f, alpha));
        if (scrim instanceof ScrimView) {
            ScrimView scrimView = (ScrimView) scrim;

            Trace.traceCounter(Trace.TRACE_TAG_APP,
                    scrim == mScrimInFront ? "front_scrim_alpha" : "back_scrim_alpha",
                    (int) (alpha * 255));

            Trace.traceCounter(Trace.TRACE_TAG_APP,
                    scrim == mScrimInFront ? "front_scrim_tint" : "back_scrim_tint",
                    Color.alpha(tint));

            scrimView.setTint(tint);
            scrimView.setViewAlpha(alpha);
        } else {
            scrim.setAlpha(alpha);
        }
        dispatchScrimsVisible();
    }

    private int getCurrentScrimTint(View scrim) {
        return scrim == mScrimInFront ? mCurrentInFrontTint : mCurrentBehindTint;
    }

    private void startScrimAnimation(final View scrim, float current, float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        final int initialScrimTint = scrim instanceof ScrimView ? ((ScrimView) scrim).getTint() :
                Color.TRANSPARENT;
        anim.addUpdateListener(animation -> {
            final float animAmount = (float) animation.getAnimatedValue();
            final int finalScrimTint = scrim == mScrimInFront ?
                    mCurrentInFrontTint : mCurrentBehindTint;
            float alpha = MathUtils.lerp(current, target, animAmount);
            int tint = ColorUtils.blendARGB(initialScrimTint, finalScrimTint, animAmount);
            updateScrimColor(scrim, alpha, tint);
            dispatchScrimsVisible();
        });
        anim.setInterpolator(getInterpolator());
        anim.setStartDelay(mAnimationDelay);
        anim.setDuration(mAnimationDuration);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mKeyguardFadingOutInProgress) {
                    mKeyguardFadeoutAnimation = null;
                    mKeyguardFadingOutInProgress = false;
                }
                onFinished();

                scrim.setTag(TAG_KEY_ANIM, null);
                scrim.setTag(TAG_KEY_ANIM_TARGET, null);
                dispatchScrimsVisible();

                if (!mDeferFinishedListener && mOnAnimationFinished != null) {
                    mOnAnimationFinished.run();
                    mOnAnimationFinished = null;
                }
            }
        });
        anim.start();
        if (mAnimateKeyguardFadingOut) {
            mKeyguardFadingOutInProgress = true;
            mKeyguardFadeoutAnimation = anim;
        }
        scrim.setTag(TAG_KEY_ANIM, anim);
        scrim.setTag(TAG_KEY_ANIM_TARGET, target);
    }

    protected Interpolator getInterpolator() {
        if (mAnimateKeyguardFadingOut && mKeyguardUpdateMonitor.needsSlowUnlockTransition()) {
            return KEYGUARD_FADE_OUT_INTERPOLATOR_LOCKED;
        } else if (mAnimateKeyguardFadingOut) {
            return KEYGUARD_FADE_OUT_INTERPOLATOR;
        } else {
            return mInterpolator;
        }
    }

    @Override
    public boolean onPreDraw() {
        mScrimBehind.getViewTreeObserver().removeOnPreDrawListener(this);
        mUpdatePending = false;
        if (mCallback != null) {
            mCallback.onStart();
        }
        updateScrims();

        // Make sure that we always call the listener even if we didn't start an animation.
        endAnimateKeyguardFadingOut(false /* force */);
        return true;
    }

    private void onFinished() {
        if (mWakeLockHeld) {
            mWakeLock.release();
            mWakeLockHeld = false;
        }
        if (mCallback != null) {
            mCallback.onFinished();
            mCallback = null;
        }
        // When unlocking with fingerprint, we'll fade the scrims from black to transparent.
        // At the end of the animation we need to remove the tint.
        if (mState == ScrimState.UNLOCKED) {
            mCurrentInFrontTint = Color.TRANSPARENT;
            mCurrentBehindTint = Color.TRANSPARENT;
        }
    }

    private void endAnimateKeyguardFadingOut(boolean force) {
        mAnimateKeyguardFadingOut = false;
        if (force || (!isAnimating(mScrimInFront) && !isAnimating(mScrimBehind))) {
            if (mOnAnimationFinished != null) {
                mOnAnimationFinished.run();
                mOnAnimationFinished = null;
            }
            mKeyguardFadingOutInProgress = false;
        }
    }

    private boolean isAnimating(View scrim) {
        return scrim.getTag(TAG_KEY_ANIM) != null;
    }

    public void setDrawBehindAsSrc(boolean asSrc) {
        mScrimBehind.setDrawAsSrc(asSrc);
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
    }

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
        mPinnedHeadsUpCount++;
        updateHeadsUpScrim(true);
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {
        mPinnedHeadsUpCount--;
        if (headsUp == mDraggedHeadsUpView) {
            mDraggedHeadsUpView = null;
            mTopHeadsUpDragAmount = 0.0f;
        }
        updateHeadsUpScrim(true);
    }

    @Override
    public void onHeadsUpStateChanged(NotificationData.Entry entry, boolean isHeadsUp) {
    }

    private void updateHeadsUpScrim(boolean animate) {
        updateScrim(animate, mHeadsUpScrim, calculateHeadsUpAlpha());
    }

    @VisibleForTesting
    void setOnAnimationFinished(Runnable onAnimationFinished) {
        mOnAnimationFinished = onAnimationFinished;
    }

    private void updateScrim(boolean animate, View scrim, float alpha) {
        final float currentAlpha = scrim instanceof ScrimView ? ((ScrimView) scrim).getViewAlpha()
            : scrim.getAlpha();

        ValueAnimator previousAnimator = ViewState.getChildTag(scrim, TAG_KEY_ANIM);
        float animEndValue = -1;
        if (previousAnimator != null) {
            if (animate || alpha == currentAlpha) {
                // We are not done yet! Defer calling the finished listener.
                if (animate) {
                    mDeferFinishedListener = true;
                }
                previousAnimator.cancel();
                mDeferFinishedListener = false;
            } else {
                animEndValue = ViewState.getChildTag(scrim, TAG_END_ALPHA);
            }
        }

        if (mPendingFrameCallback != null) {
            // Display is off and we're waiting.
            return;
        } else if (mBlankScreen) {
            // Need to blank the display before continuing.
            blankDisplay();
            return;
        } else if (!mScreenBlankingCallbackCalled) {
            // Not blanking the screen. Letting the callback know that we're ready
            // to replace what was on the screen before.
            if (mCallback != null) {
                mCallback.onDisplayBlanked();
                mScreenBlankingCallbackCalled = true;
            }
        }

        // TODO factor mLightBarController out of this class
        if (scrim == mScrimBehind) {
            mLightBarController.setScrimAlpha(alpha);
        }

        final ScrimView scrimView = scrim instanceof  ScrimView ? (ScrimView) scrim : null;
        final boolean wantsAlphaUpdate = alpha != currentAlpha && alpha != animEndValue;
        final boolean wantsTintUpdate = scrimView != null
                && scrimView.getTint() != getCurrentScrimTint(scrimView);

        if (wantsAlphaUpdate || wantsTintUpdate) {
            if (animate) {
                final float fromAlpha = scrimView == null ? scrim.getAlpha()
                        : scrimView.getViewAlpha();
                startScrimAnimation(scrim, fromAlpha, alpha);
                scrim.setTag(TAG_START_ALPHA, currentAlpha);
                scrim.setTag(TAG_END_ALPHA, alpha);
            } else {
                if (previousAnimator != null) {
                    float previousStartValue = ViewState.getChildTag(scrim, TAG_START_ALPHA);
                    float previousEndValue = ViewState.getChildTag(scrim, TAG_END_ALPHA);
                    // we need to increase all animation keyframes of the previous animator by the
                    // relative change to the end value
                    PropertyValuesHolder[] values = previousAnimator.getValues();
                    float relativeDiff = alpha - previousEndValue;
                    float newStartValue = previousStartValue + relativeDiff;
                    newStartValue = Math.max(0, Math.min(1.0f, newStartValue));
                    values[0].setFloatValues(newStartValue, alpha);
                    scrim.setTag(TAG_START_ALPHA, newStartValue);
                    scrim.setTag(TAG_END_ALPHA, alpha);
                    previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                } else {
                    // update the alpha directly
                    updateScrimColor(scrim, alpha, getCurrentScrimTint(scrim));
                    onFinished();
                }
            }
        } else {
            onFinished();
        }
    }

    private void blankDisplay() {
        updateScrimColor(mScrimInFront, 1, Color.BLACK);

        // Notify callback that the screen is completely black and we're
        // ready to change the display power mode
        mPendingFrameCallback = frameTimeNanos -> {
            if (mCallback != null) {
                mCallback.onDisplayBlanked();
                mScreenBlankingCallbackCalled = true;
            }

            mBlankingTransitionRunnable = () -> {
                mBlankingTransitionRunnable = null;
                mPendingFrameCallback = null;
                mBlankScreen = false;
                // Try again.
                updateScrims();
            };

            // Setting power states can happen after we push out the frame. Make sure we
            // stay fully opaque until the power state request reaches the lower levels.
            final int delay = mScreenOn ? 16 : 500;
            if (DEBUG) {
                Log.d(TAG, "Fading out scrims with delay: " + delay);
            }
            getHandler().postDelayed(mBlankingTransitionRunnable, delay);
        };
        doOnTheNextFrame(mPendingFrameCallback);
    }

    @VisibleForTesting
    protected void doOnTheNextFrame(Choreographer.FrameCallback callback) {
        Choreographer.getInstance().postFrameCallback(callback);
    }

    @VisibleForTesting
    protected Handler getHandler() {
        return Handler.getMain();
    }

    /**
     * Set the amount the current top heads up view is dragged. The range is from 0 to 1 and 0 means
     * the heads up is in its resting space and 1 means it's fully dragged out.
     *
     * @param draggedHeadsUpView the dragged view
     * @param topHeadsUpDragAmount how far is it dragged
     */
    public void setTopHeadsUpDragAmount(View draggedHeadsUpView, float topHeadsUpDragAmount) {
        mTopHeadsUpDragAmount = topHeadsUpDragAmount;
        mDraggedHeadsUpView = draggedHeadsUpView;
        updateHeadsUpScrim(false);
    }

    private float calculateHeadsUpAlpha() {
        float alpha;
        if (mPinnedHeadsUpCount >= 2) {
            alpha = 1.0f;
        } else if (mPinnedHeadsUpCount == 0) {
            alpha = 0.0f;
        } else {
            alpha = 1.0f - mTopHeadsUpDragAmount;
        }
        float expandFactor = (1.0f - mExpansionFraction);
        expandFactor = Math.max(expandFactor, 0.0f);
        return alpha * expandFactor;
    }

    public void setExcludedBackgroundArea(Rect area) {
        mScrimBehind.setExcludedArea(area);
    }

    public int getBackgroundColor() {
        int color = mLockColors.getMainColor();
        return Color.argb((int) (mScrimBehind.getViewAlpha() * Color.alpha(color)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setScrimBehindChangeRunnable(Runnable changeRunnable) {
        mScrimBehind.setChangeRunnable(changeRunnable);
    }

    public void onDensityOrFontScaleChanged() {
        ViewGroup.LayoutParams layoutParams = mHeadsUpScrim.getLayoutParams();
        layoutParams.height = mHeadsUpScrim.getResources().getDimensionPixelSize(
                R.dimen.heads_up_scrim_height);
        mHeadsUpScrim.setLayoutParams(layoutParams);
    }

    public void setCurrentUser(int currentUser) {
        // Don't care in the base class.
    }

    @Override
    public void onColorsChanged(ColorExtractor colorExtractor, int which) {
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            mLockColors = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK,
                    ColorExtractor.TYPE_DARK, true /* ignoreVisibility */);
            mNeedsDrawableColorUpdate = true;
            scheduleUpdate();
        }
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            mSystemColors = mColorExtractor.getColors(WallpaperManager.FLAG_SYSTEM,
                    ColorExtractor.TYPE_DARK, mState != ScrimState.UNLOCKED);
            mNeedsDrawableColorUpdate = true;
            scheduleUpdate();
        }
    }

    @VisibleForTesting
    protected WakeLock createWakeLock() {
         return new DelayedWakeLock(getHandler(),
                WakeLock.createPartial(mContext, "Doze"));
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(" ScrimController: ");
        pw.print("  state: "); pw.println(mState);
        pw.print("  frontScrim:"); pw.print(" viewAlpha="); pw.print(mScrimInFront.getViewAlpha());
        pw.print(" alpha="); pw.print(mCurrentInFrontAlpha);
        pw.print(" tint=0x"); pw.println(Integer.toHexString(mScrimInFront.getTint()));

        pw.print("  backScrim:"); pw.print(" viewAlpha="); pw.print(mScrimBehind.getViewAlpha());
        pw.print(" alpha="); pw.print(mCurrentBehindAlpha);
        pw.print(" tint=0x"); pw.println(Integer.toHexString(mScrimBehind.getTint()));

        pw.print("   mTracking="); pw.println(mTracking);
    }

    public void setWallpaperSupportsAmbientMode(boolean wallpaperSupportsAmbientMode) {
        mWallpaperSupportsAmbientMode = wallpaperSupportsAmbientMode;
        ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].setWallpaperSupportsAmbientMode(wallpaperSupportsAmbientMode);
        }
    }

    /**
     * Interrupts blanking transitions once the display notifies that it's already on.
     */
    public void onScreenTurnedOn() {
        mScreenOn = true;
        final Handler handler = getHandler();
        if (handler.hasCallbacks(mBlankingTransitionRunnable)) {
            if (DEBUG) {
                Log.d(TAG, "Shorter blanking because screen turned on. All good.");
            }
            handler.removeCallbacks(mBlankingTransitionRunnable);
            mBlankingTransitionRunnable.run();
        }
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
    }

    public interface Callback {
        default void onStart() {
        }
        default void onDisplayBlanked() {
        }
        default void onFinished() {
        }
        default void onCancelled() {
        }
    }
}
