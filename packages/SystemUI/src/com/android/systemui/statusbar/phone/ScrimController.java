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
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.ColorExtractor.OnColorsChangedListener;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.function.TriConsumer;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.statusbar.ScrimView;
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
public class ScrimController implements ViewTreeObserver.OnPreDrawListener, OnColorsChangedListener,
        Dumpable {

    private static final String TAG = "ScrimController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * General scrim animation duration.
     */
    public static final long ANIMATION_DURATION = 220;
    /**
     * Longer duration, currently only used when going to AOD.
     */
    public static final long ANIMATION_DURATION_LONG = 1000;
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

    static final int TAG_KEY_ANIM = R.id.scrim;
    private static final int TAG_START_ALPHA = R.id.scrim_alpha_start;
    private static final int TAG_END_ALPHA = R.id.scrim_alpha_end;
    private static final float NOT_INITIALIZED = -1;

    private ScrimState mState = ScrimState.UNINITIALIZED;
    private final Context mContext;
    protected final ScrimView mScrimBehind;
    protected final ScrimView mScrimInFront;
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

    // Assuming the shade is expanded during initialization
    private float mExpansionFraction = 1f;

    private boolean mDarkenWhileDragging;
    private boolean mExpansionAffectsAlpha = true;
    protected boolean mAnimateChange;
    private boolean mUpdatePending;
    private boolean mTracking;
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
    private int mScrimsVisibility;
    private final TriConsumer<ScrimState, Float, GradientColors> mScrimStateListener;
    private final Consumer<Integer> mScrimVisibleListener;
    private boolean mBlankScreen;
    private boolean mScreenBlankingCallbackCalled;
    private Callback mCallback;
    private boolean mWallpaperSupportsAmbientMode;
    private boolean mScreenOn;
    private float mNotificationDensity;

    // Scrim blanking callbacks
    private Runnable mPendingFrameCallback;
    private Runnable mBlankingTransitionRunnable;

    private final WakeLock mWakeLock;
    private boolean mWakeLockHeld;
    private boolean mKeyguardOccluded;

    public ScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
            TriConsumer<ScrimState, Float, GradientColors> scrimStateListener,
            Consumer<Integer> scrimVisibleListener, DozeParameters dozeParameters,
            AlarmManager alarmManager) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        mScrimStateListener = scrimStateListener;
        mScrimVisibleListener = scrimVisibleListener;
        mContext = scrimBehind.getContext();
        mUnlockMethodCache = UnlockMethodCache.getInstance(mContext);
        mDarkenWhileDragging = !mUnlockMethodCache.canSkipBouncer();
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
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

        mScrimBehind.setDefaultFocusHighlightEnabled(false);
        mScrimInFront.setDefaultFocusHighlightEnabled(false);

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
        mCurrentBehindAlpha = state.getBehindAlpha(mNotificationDensity);
        applyExpansionToAlpha();

        // Scrim might acquire focus when user is navigating with a D-pad or a keyboard.
        // We need to disable focus otherwise AOD would end up with a gray overlay.
        mScrimInFront.setFocusable(!state.isLowPowerState());
        mScrimBehind.setFocusable(!state.isLowPowerState());

        // Cancel blanking transitions that were pending before we requested a new state
        if (mPendingFrameCallback != null) {
            mScrimBehind.removeCallbacks(mPendingFrameCallback);
            mPendingFrameCallback = null;
        }
        if (getHandler().hasCallbacks(mBlankingTransitionRunnable)) {
            getHandler().removeCallbacks(mBlankingTransitionRunnable);
            mBlankingTransitionRunnable = null;
        }

        // Showing/hiding the keyguard means that scrim colors have to be switched, not necessary
        // to do the same when you're just showing the brightness mirror.
        mNeedsDrawableColorUpdate = state != ScrimState.BRIGHTNESS_MIRROR;

        // The device might sleep if it's entering AOD, we need to make sure that
        // the animation plays properly until the last frame.
        // It's important to avoid holding the wakelock unless necessary because
        // WakeLock#aqcuire will trigger an IPC and will cause jank.
        if (mState.isLowPowerState()) {
            holdWakeLock();
        }

        // AOD wallpapers should fade away after a while
        if (mWallpaperSupportsAmbientMode && mDozeParameters.getAlwaysOn()
                && mState == ScrimState.AOD) {
            if (!mWallpaperVisibilityTimedOut) {
                mTimeTicker.schedule(mDozeParameters.getWallpaperAodDuration(),
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
            }
        // Do not re-schedule timeout when pulsing, let's save some extra battery.
        } else if (mState != ScrimState.PULSING) {
            mTimeTicker.cancel();
            mWallpaperVisibilityTimedOut = false;
        }

        if (mKeyguardUpdateMonitor.needsSlowUnlockTransition() && mState == ScrimState.UNLOCKED) {
            // In case the user isn't unlocked, make sure to delay a bit because the system is hosed
            // with too many things at this case, in order to not skip the initial frames.
            mScrimInFront.postOnAnimationDelayed(this::scheduleUpdate, 16);
            mAnimationDelay = StatusBar.FADE_KEYGUARD_START_DELAY;
        } else if ((!mDozeParameters.getAlwaysOn() && oldState == ScrimState.AOD)
                || (mState == ScrimState.AOD && !mDozeParameters.getDisplayNeedsBlanking())) {
            // Scheduling a frame isn't enough when:
            //  • Leaving doze and we need to modify scrim color immediately
            //  • ColorFade will not kick-in and scrim cannot wait for pre-draw.
            onPreDraw();
        } else {
            scheduleUpdate();
        }

        dispatchScrimState(mScrimBehind.getViewAlpha());
    }

    public ScrimState getState() {
        return mState;
    }

    protected void setScrimBehindValues(float scrimBehindAlphaKeyguard) {
        mScrimBehindAlphaKeyguard = scrimBehindAlphaKeyguard;
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
        if (mState != ScrimState.AOD) {
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
     * @param fraction From 0 to 1 where 0 means collapsed and 1 expanded.
     */
    public void setPanelExpansion(float fraction) {
        if (mExpansionFraction != fraction) {
            mExpansionFraction = fraction;

            final boolean keyguardOrUnlocked = mState == ScrimState.UNLOCKED
                    || mState == ScrimState.KEYGUARD;
            if (!keyguardOrUnlocked || !mExpansionAffectsAlpha) {
                return;
            }

            applyExpansionToAlpha();

            if (mUpdatePending) {
                return;
            }

            setOrAdaptCurrentAnimation(mScrimBehind);
            setOrAdaptCurrentAnimation(mScrimInFront);

            dispatchScrimState(mScrimBehind.getViewAlpha());
        }
    }

    private void setOrAdaptCurrentAnimation(View scrim) {
        if (!isAnimating(scrim)) {
            updateScrimColor(scrim, getCurrentScrimAlpha(scrim), getCurrentScrimTint(scrim));
        } else {
            ValueAnimator previousAnimator = (ValueAnimator) scrim.getTag(TAG_KEY_ANIM);
            float alpha = getCurrentScrimAlpha(scrim);
            float previousEndValue = (Float) scrim.getTag(TAG_END_ALPHA);
            float previousStartValue = (Float) scrim.getTag(TAG_START_ALPHA);
            float relativeDiff = alpha - previousEndValue;
            float newStartValue = previousStartValue + relativeDiff;
            scrim.setTag(TAG_START_ALPHA, newStartValue);
            scrim.setTag(TAG_END_ALPHA, alpha);
            previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
        }
    }

    private void applyExpansionToAlpha() {
        if (!mExpansionAffectsAlpha) {
            return;
        }

        if (mState == ScrimState.UNLOCKED) {
            // Darken scrim as you pull down the shade when unlocked
            float behindFraction = getInterpolatedFraction();
            behindFraction = (float) Math.pow(behindFraction, 0.8f);
            mCurrentBehindAlpha = behindFraction * GRADIENT_SCRIM_ALPHA_BUSY;
            mCurrentInFrontAlpha = 0;
        } else if (mState == ScrimState.KEYGUARD) {
            // Either darken of make the scrim transparent when you
            // pull down the shade
            float interpolatedFract = getInterpolatedFraction();
            float alphaBehind = mState.getBehindAlpha(mNotificationDensity);
            if (mDarkenWhileDragging) {
                mCurrentBehindAlpha = MathUtils.lerp(GRADIENT_SCRIM_ALPHA_BUSY, alphaBehind,
                        interpolatedFract);
                mCurrentInFrontAlpha = 0;
            } else {
                mCurrentBehindAlpha = MathUtils.lerp(0 /* start */, alphaBehind,
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
        if (mNotificationDensity == notificationDensity) {
            return;
        }
        mNotificationDensity = notificationDensity;

        if (mState == ScrimState.KEYGUARD) {
            applyExpansionToAlpha();
            scheduleUpdate();
        }
    }

    /**
     * Sets the given drawable as the background of the scrim that shows up behind the
     * notifications.
     */
    public void setScrimBehindDrawable(Drawable drawable) {
        mScrimBehind.setDrawable(drawable);
    }

    /**
     * Sets the front scrim opacity in AOD so it's not as bright.
     * <p>
     * Displays usually don't support multiple dimming settings when in low power mode.
     * The workaround is to modify the front scrim opacity when in AOD, so it's not as
     * bright when you're at the movies or lying down on bed.
     * <p>
     * This value will be lost during transitions and only updated again after the the
     * device is dozing when the light sensor is on.
     */
    public void setAodFrontScrimAlpha(float alpha) {
        if (mState == ScrimState.AOD && mDozeParameters.getAlwaysOn()
                && mCurrentInFrontAlpha != alpha) {
            mCurrentInFrontAlpha = alpha;
            scheduleUpdate();
        }

        mState.AOD.setAodFrontScrimAlpha(alpha);
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
            boolean isKeyguard = mKeyguardUpdateMonitor.isKeyguardVisible() && !mKeyguardOccluded;
            GradientColors currentScrimColors = isKeyguard ? mLockColors : mSystemColors;
            // Only animate scrim color if the scrim view is actually visible
            boolean animateScrimInFront = mScrimInFront.getViewAlpha() != 0 && !mBlankScreen;
            boolean animateScrimBehind = mScrimBehind.getViewAlpha() != 0 && !mBlankScreen;
            mScrimInFront.setColors(currentScrimColors, animateScrimInFront);
            mScrimBehind.setColors(currentScrimColors, animateScrimBehind);

            // Calculate minimum scrim opacity for white or black text.
            int textColor = currentScrimColors.supportsDarkText() ? Color.BLACK : Color.WHITE;
            int mainColor = currentScrimColors.getMainColor();
            float minOpacity = ColorUtils.calculateMinimumBackgroundAlpha(textColor, mainColor,
                    4.5f /* minimumContrast */) / 255f;
            mScrimBehindAlpha = Math.max(mScrimBehindAlphaResValue, minOpacity);
            dispatchScrimState(mScrimBehind.getViewAlpha());
        }

        // We want to override the back scrim opacity for the AOD state
        // when it's time to fade the wallpaper away.
        boolean aodWallpaperTimeout = mState == ScrimState.AOD && mWallpaperVisibilityTimedOut;
        // We also want to hide FLAG_SHOW_WHEN_LOCKED activities under the scrim.
        boolean occludedKeyguard = (mState == ScrimState.PULSING || mState == ScrimState.AOD)
                && mKeyguardOccluded;
        if (aodWallpaperTimeout || occludedKeyguard) {
            mCurrentBehindAlpha = 1;
        }

        setScrimInFrontAlpha(mCurrentInFrontAlpha);
        setScrimBehindAlpha(mCurrentBehindAlpha);

        dispatchScrimsVisible();
    }

    private void dispatchScrimState(float alpha) {
        mScrimStateListener.accept(mState, alpha, mScrimInFront.getColors());
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

    private void setScrimAlpha(ScrimView scrim, float alpha) {
        if (alpha == 0f) {
            scrim.setClickable(false);
        } else {
            // Eat touch events (unless dozing or pulsing).
            scrim.setClickable(mState != ScrimState.AOD && mState != ScrimState.PULSING);
        }
        updateScrim(scrim, alpha);
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

    private void startScrimAnimation(final View scrim, float current) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        final int initialScrimTint = scrim instanceof ScrimView ? ((ScrimView) scrim).getTint() :
                Color.TRANSPARENT;
        anim.addUpdateListener(animation -> {
            final float startAlpha = (Float) scrim.getTag(TAG_START_ALPHA);
            final float animAmount = (float) animation.getAnimatedValue();
            final int finalScrimTint = getCurrentScrimTint(scrim);
            final float finalScrimAlpha = getCurrentScrimAlpha(scrim);
            float alpha = MathUtils.lerp(startAlpha, finalScrimAlpha, animAmount);
            alpha = MathUtils.constrain(alpha, 0f, 1f);
            int tint = ColorUtils.blendARGB(initialScrimTint, finalScrimTint, animAmount);
            updateScrimColor(scrim, alpha, tint);
            dispatchScrimsVisible();
        });
        anim.setInterpolator(mInterpolator);
        anim.setStartDelay(mAnimationDelay);
        anim.setDuration(mAnimationDuration);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                onFinished();

                scrim.setTag(TAG_KEY_ANIM, null);
                dispatchScrimsVisible();

                if (!mDeferFinishedListener && mOnAnimationFinished != null) {
                    mOnAnimationFinished.run();
                    mOnAnimationFinished = null;
                }
            }
        });

        // Cache alpha values because we might want to update this animator in the future if
        // the user expands the panel while the animation is still running.
        scrim.setTag(TAG_START_ALPHA, current);
        scrim.setTag(TAG_END_ALPHA, getCurrentScrimAlpha(scrim));

        scrim.setTag(TAG_KEY_ANIM, anim);
        anim.start();
    }

    private float getCurrentScrimAlpha(View scrim) {
        if (scrim == mScrimInFront) {
            return mCurrentInFrontAlpha;
        } else if (scrim == mScrimBehind) {
            return mCurrentBehindAlpha;
        } else {
            throw new IllegalArgumentException("Unknown scrim view");
        }
    }

    private int getCurrentScrimTint(View scrim) {
        if (scrim == mScrimInFront) {
            return mCurrentInFrontTint;
        } else if (scrim == mScrimBehind) {
            return mCurrentBehindTint;
        } else {
            throw new IllegalArgumentException("Unknown scrim view");
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
        if (mOnAnimationFinished != null && !isAnimating(mScrimInFront)
                && !isAnimating(mScrimBehind)) {
            mOnAnimationFinished.run();
            mOnAnimationFinished = null;
        }
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

    private boolean isAnimating(View scrim) {
        return scrim.getTag(TAG_KEY_ANIM) != null;
    }

    public void setDrawBehindAsSrc(boolean asSrc) {
        mScrimBehind.setDrawAsSrc(asSrc);
    }

    @VisibleForTesting
    void setOnAnimationFinished(Runnable onAnimationFinished) {
        mOnAnimationFinished = onAnimationFinished;
    }

    private void updateScrim(ScrimView scrim, float alpha) {
        final float currentAlpha = scrim.getViewAlpha();

        ValueAnimator previousAnimator = ViewState.getChildTag(scrim, TAG_KEY_ANIM);
        if (previousAnimator != null) {
            if (mAnimateChange) {
                // We are not done yet! Defer calling the finished listener.
                mDeferFinishedListener = true;
            }
            // Previous animators should always be cancelled. Not doing so would cause
            // overlap, especially on states that don't animate, leading to flickering,
            // and in the worst case, an internal state that doesn't represent what
            // transitionTo requested.
            cancelAnimator(previousAnimator);
            mDeferFinishedListener = false;
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

        if (scrim == mScrimBehind) {
            dispatchScrimState(alpha);
        }

        final boolean wantsAlphaUpdate = alpha != currentAlpha;
        final boolean wantsTintUpdate = scrim.getTint() != getCurrentScrimTint(scrim);

        if (wantsAlphaUpdate || wantsTintUpdate) {
            if (mAnimateChange) {
                startScrimAnimation(scrim, currentAlpha);
            } else {
                // update the alpha directly
                updateScrimColor(scrim, alpha, getCurrentScrimTint(scrim));
                onFinished();
            }
        } else {
            onFinished();
        }
    }

    @VisibleForTesting
    protected void cancelAnimator(ValueAnimator previousAnimator) {
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
    }

    private void blankDisplay() {
        updateScrimColor(mScrimInFront, 1, Color.BLACK);

        // Notify callback that the screen is completely black and we're
        // ready to change the display power mode
        mPendingFrameCallback = () -> {
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
            final int delay = mScreenOn ? 32 : 500;
            if (DEBUG) {
                Log.d(TAG, "Fading out scrims with delay: " + delay);
            }
            getHandler().postDelayed(mBlankingTransitionRunnable, delay);
        };
        doOnTheNextFrame(mPendingFrameCallback);
    }

    /**
     * Executes a callback after the frame has hit the display.
     * @param callback What to run.
     */
    @VisibleForTesting
    protected void doOnTheNextFrame(Runnable callback) {
        // Just calling View#postOnAnimation isn't enough because the frame might not have reached
        // the display yet. A timeout is the safest solution.
        mScrimBehind.postOnAnimationDelayed(callback, 32 /* delayMillis */);
    }

    @VisibleForTesting
    protected Handler getHandler() {
        return Handler.getMain();
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
                WakeLock.createPartial(mContext, "Scrims"));
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

    public void setExpansionAffectsAlpha(boolean expansionAffectsAlpha) {
        mExpansionAffectsAlpha = expansionAffectsAlpha;
    }

    public void setKeyguardOccluded(boolean keyguardOccluded) {
        mKeyguardOccluded = keyguardOccluded;
        updateScrims();
    }

    public void setHasBackdrop(boolean hasBackdrop) {
        for (ScrimState state : ScrimState.values()) {
            state.setHasBackdrop(hasBackdrop);
        }
    }

    public void setLaunchingAffordanceWithPreview(boolean launchingAffordanceWithPreview) {
        for (ScrimState state : ScrimState.values()) {
            state.setLaunchingAffordanceWithPreview(launchingAffordanceWithPreview);
        }
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
