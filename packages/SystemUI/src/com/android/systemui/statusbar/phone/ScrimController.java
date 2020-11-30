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

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.app.AlarmManager;
import android.graphics.Color;
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
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
@Singleton
public class ScrimController implements ViewTreeObserver.OnPreDrawListener, OnColorsChangedListener,
        Dumpable {

    static final String TAG = "ScrimController";
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
    public static final int TRANSPARENT = 0;
    /**
     * When scrims aren't transparent (alpha 0) but also not opaque (alpha 1.)
     */
    public static final int SEMI_TRANSPARENT = 1;
    /**
     * When at least 1 scrim is fully opaque (alpha set to 1.)
     */
    public static final int OPAQUE = 2;

    @IntDef(prefix = {"VISIBILITY_"}, value = {
            TRANSPARENT,
            SEMI_TRANSPARENT,
            OPAQUE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScrimVisibility {
    }

    /**
     * Default alpha value for most scrims.
     */
    protected static final float KEYGUARD_SCRIM_ALPHA = 0.2f;
    /**
     * Scrim opacity when the phone is about to wake-up.
     */
    public static final float WAKE_SENSOR_SCRIM_ALPHA = 0.6f;

    /**
     * Scrim opacity when bubbles are expanded.
     */
    public static final float BUBBLE_SCRIM_ALPHA = 0.6f;

    /**
     * The default scrim under the shade and dialogs.
     * This should not be lower than 0.54, otherwise we won't pass GAR.
     */
    public static final float BUSY_SCRIM_ALPHA = 0.85f;

    /**
     * Same as above, but when blur is supported.
     */
    public static final float BLUR_SCRIM_ALPHA = 0.54f;

    static final int TAG_KEY_ANIM = R.id.scrim;
    private static final int TAG_START_ALPHA = R.id.scrim_alpha_start;
    private static final int TAG_END_ALPHA = R.id.scrim_alpha_end;
    private static final float NOT_INITIALIZED = -1;

    private ScrimState mState = ScrimState.UNINITIALIZED;

    private ScrimView mScrimInFront;
    private ScrimView mScrimBehind;
    private ScrimView mScrimForBubble;

    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DozeParameters mDozeParameters;
    private final DockManager mDockManager;
    private final AlarmTimeout mTimeTicker;
    private final KeyguardVisibilityCallback mKeyguardVisibilityCallback;
    private final Handler mHandler;

    private final SysuiColorExtractor mColorExtractor;
    private GradientColors mColors;
    private boolean mNeedsDrawableColorUpdate;

    private float mScrimBehindAlphaKeyguard = KEYGUARD_SCRIM_ALPHA;
    private final float mDefaultScrimAlpha;

    // Assuming the shade is expanded during initialization
    private float mExpansionFraction = 1f;

    private boolean mDarkenWhileDragging;
    private boolean mExpansionAffectsAlpha = true;
    private boolean mAnimateChange;
    private boolean mUpdatePending;
    private boolean mTracking;
    private long mAnimationDuration = -1;
    private long mAnimationDelay;
    private Animator.AnimatorListener mAnimatorListener;
    private final Interpolator mInterpolator = new DecelerateInterpolator();

    private float mInFrontAlpha = NOT_INITIALIZED;
    private float mBehindAlpha = NOT_INITIALIZED;
    private float mBubbleAlpha = NOT_INITIALIZED;

    private int mInFrontTint;
    private int mBehindTint;
    private int mBubbleTint;

    private boolean mWallpaperVisibilityTimedOut;
    private int mScrimsVisibility;
    private final TriConsumer<ScrimState, Float, GradientColors> mScrimStateListener;
    private Consumer<Integer> mScrimVisibleListener;
    private boolean mBlankScreen;
    private boolean mScreenBlankingCallbackCalled;
    private Callback mCallback;
    private boolean mWallpaperSupportsAmbientMode;
    private boolean mScreenOn;

    // Scrim blanking callbacks
    private Runnable mPendingFrameCallback;
    private Runnable mBlankingTransitionRunnable;

    private final WakeLock mWakeLock;
    private boolean mWakeLockHeld;
    private boolean mKeyguardOccluded;

    @Inject
    public ScrimController(LightBarController lightBarController, DozeParameters dozeParameters,
            AlarmManager alarmManager, KeyguardStateController keyguardStateController,
            DelayedWakeLock.Builder delayedWakeLockBuilder, Handler handler,
            KeyguardUpdateMonitor keyguardUpdateMonitor, SysuiColorExtractor sysuiColorExtractor,
            DockManager dockManager, BlurUtils blurUtils) {

        mScrimStateListener = lightBarController::setScrimState;
        mDefaultScrimAlpha = blurUtils.supportsBlursOnWindows()
                ? BLUR_SCRIM_ALPHA : BUSY_SCRIM_ALPHA;

        mKeyguardStateController = keyguardStateController;
        mDarkenWhileDragging = !mKeyguardStateController.canDismissLockScreen();
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardVisibilityCallback = new KeyguardVisibilityCallback();
        mHandler = handler;
        mTimeTicker = new AlarmTimeout(alarmManager, this::onHideWallpaperTimeout,
                "hide_aod_wallpaper", mHandler);
        mWakeLock = delayedWakeLockBuilder.setHandler(mHandler).setTag("Scrims").build();
        // Scrim alpha is initially set to the value on the resource but might be changed
        // to make sure that text on top of it is legible.
        mDozeParameters = dozeParameters;
        mDockManager = dockManager;
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardFadingAwayChanged() {
                setKeyguardFadingAway(keyguardStateController.isKeyguardFadingAway(),
                        keyguardStateController.getKeyguardFadingAwayDuration());
            }
        });

        mColorExtractor = sysuiColorExtractor;
        mColorExtractor.addOnColorsChangedListener(this);
        mColors = mColorExtractor.getNeutralColors();
        mNeedsDrawableColorUpdate = true;
    }

    /**
     * Attach the controller to the supplied views.
     */
    public void attachViews(
            ScrimView scrimBehind, ScrimView scrimInFront, ScrimView scrimForBubble) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        mScrimForBubble = scrimForBubble;

        final ScrimState[] states = ScrimState.values();
        for (int i = 0; i < states.length; i++) {
            states[i].init(mScrimInFront, mScrimBehind, mScrimForBubble, mDozeParameters,
                    mDockManager);
            states[i].setScrimBehindAlphaKeyguard(mScrimBehindAlphaKeyguard);
            states[i].setDefaultScrimAlpha(mDefaultScrimAlpha);
        }

        mScrimBehind.setDefaultFocusHighlightEnabled(false);
        mScrimInFront.setDefaultFocusHighlightEnabled(false);
        mScrimForBubble.setDefaultFocusHighlightEnabled(false);
        updateScrims();
        mKeyguardUpdateMonitor.registerCallback(mKeyguardVisibilityCallback);
    }

    void setScrimVisibleListener(Consumer<Integer> listener) {
        mScrimVisibleListener = listener;
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
        Trace.traceCounter(Trace.TRACE_TAG_APP, "scrim_state", mState.ordinal());

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

        mInFrontTint = state.getFrontTint();
        mBehindTint = state.getBehindTint();
        mBubbleTint = state.getBubbleTint();

        mInFrontAlpha = state.getFrontAlpha();
        mBehindAlpha = state.getBehindAlpha();
        mBubbleAlpha = state.getBubbleAlpha();
        if (isNaN(mBehindAlpha) || isNaN(mInFrontAlpha)) {
            throw new IllegalStateException("Scrim opacity is NaN for state: " + state + ", front: "
                    + mInFrontAlpha + ", back: " + mBehindAlpha);
        }
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
        if (mHandler.hasCallbacks(mBlankingTransitionRunnable)) {
            mHandler.removeCallbacks(mBlankingTransitionRunnable);
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

        // AOD wallpapers should fade away after a while.
        // Docking pulses may take a long time, wallpapers should also fade away after a while.
        mWallpaperVisibilityTimedOut = false;
        if (shouldFadeAwayWallpaper()) {
            DejankUtils.postAfterTraversal(() -> {
                mTimeTicker.schedule(mDozeParameters.getWallpaperAodDuration(),
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
            });
        } else {
            DejankUtils.postAfterTraversal(mTimeTicker::cancel);
        }

        if (mKeyguardUpdateMonitor.needsSlowUnlockTransition() && mState == ScrimState.UNLOCKED) {
            mAnimationDelay = StatusBar.FADE_KEYGUARD_START_DELAY;
            scheduleUpdate();
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

    private boolean shouldFadeAwayWallpaper() {
        if (!mWallpaperSupportsAmbientMode) {
            return false;
        }

        if (mState == ScrimState.AOD
                && (mDozeParameters.getAlwaysOn() || mDockManager.isDocked())) {
            return true;
        }

        return false;
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
        mDarkenWhileDragging = !mKeyguardStateController.canDismissLockScreen();
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
                mWakeLock.acquire(TAG);
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
        if (isNaN(fraction)) {
            throw new IllegalArgumentException("Fraction should not be NaN");
        }
        if (mExpansionFraction != fraction) {
            mExpansionFraction = fraction;

            boolean relevantState = (mState == ScrimState.UNLOCKED
                    || mState == ScrimState.KEYGUARD
                    || mState == ScrimState.PULSING
                    || mState == ScrimState.BUBBLE_EXPANDED);
            if (!(relevantState && mExpansionAffectsAlpha)) {
                return;
            }
            applyAndDispatchExpansion();
        }
    }

    private void setOrAdaptCurrentAnimation(View scrim) {
        float alpha = getCurrentScrimAlpha(scrim);
        if (isAnimating(scrim)) {
            // Adapt current animation.
            ValueAnimator previousAnimator = (ValueAnimator) scrim.getTag(TAG_KEY_ANIM);
            float previousEndValue = (Float) scrim.getTag(TAG_END_ALPHA);
            float previousStartValue = (Float) scrim.getTag(TAG_START_ALPHA);
            float relativeDiff = alpha - previousEndValue;
            float newStartValue = previousStartValue + relativeDiff;
            scrim.setTag(TAG_START_ALPHA, newStartValue);
            scrim.setTag(TAG_END_ALPHA, alpha);
            previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
        } else {
            // Set animation.
            updateScrimColor(scrim, alpha, getCurrentScrimTint(scrim));
        }
    }

    private void applyExpansionToAlpha() {
        if (!mExpansionAffectsAlpha) {
            return;
        }

        if (mState == ScrimState.UNLOCKED || mState == ScrimState.BUBBLE_EXPANDED) {
            // Darken scrim as you pull down the shade when unlocked
            float behindFraction = getInterpolatedFraction();
            behindFraction = (float) Math.pow(behindFraction, 0.8f);
            mBehindAlpha = behindFraction * mDefaultScrimAlpha;
            mInFrontAlpha = 0;
        } else if (mState == ScrimState.KEYGUARD || mState == ScrimState.PULSING) {
            // Either darken of make the scrim transparent when you
            // pull down the shade
            float interpolatedFract = getInterpolatedFraction();
            float alphaBehind = mState.getBehindAlpha();
            if (mDarkenWhileDragging) {
                mBehindAlpha = MathUtils.lerp(mDefaultScrimAlpha, alphaBehind,
                        interpolatedFract);
                mInFrontAlpha = mState.getFrontAlpha();
            } else {
                mBehindAlpha = MathUtils.lerp(0 /* start */, alphaBehind,
                        interpolatedFract);
                mInFrontAlpha = mState.getFrontAlpha();
            }
            mBehindTint = ColorUtils.blendARGB(ScrimState.BOUNCER.getBehindTint(),
                    mState.getBehindTint(), interpolatedFract);
        }
        if (isNaN(mBehindAlpha) || isNaN(mInFrontAlpha)) {
            throw new IllegalStateException("Scrim opacity is NaN for state: " + mState
                    + ", front: " + mInFrontAlpha + ", back: " + mBehindAlpha);
        }
    }

    private void applyAndDispatchExpansion() {
        applyExpansionToAlpha();
        if (mUpdatePending) {
            return;
        }
        setOrAdaptCurrentAnimation(mScrimBehind);
        setOrAdaptCurrentAnimation(mScrimInFront);
        setOrAdaptCurrentAnimation(mScrimForBubble);
        dispatchScrimState(mScrimBehind.getViewAlpha());

        // Reset wallpaper timeout if it's already timeout like expanding panel while PULSING
        // and docking.
        if (mWallpaperVisibilityTimedOut) {
            mWallpaperVisibilityTimedOut = false;
            DejankUtils.postAfterTraversal(() -> {
                mTimeTicker.schedule(mDozeParameters.getWallpaperAodDuration(),
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
            });
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
        if (mInFrontAlpha != alpha && shouldUpdateFrontScrimAlpha()) {
            mInFrontAlpha = alpha;
            updateScrims();
        }

        mState.AOD.setAodFrontScrimAlpha(alpha);
        mState.PULSING.setAodFrontScrimAlpha(alpha);
    }

    private boolean shouldUpdateFrontScrimAlpha() {
        if (mState == ScrimState.AOD
                && (mDozeParameters.getAlwaysOn() || mDockManager.isDocked())) {
            return true;
        }

        if (mState == ScrimState.PULSING) {
            return true;
        }

        return false;
    }

    /**
     * If the lock screen sensor is active.
     */
    public void setWakeLockScreenSensorActive(boolean active) {
        for (ScrimState state : ScrimState.values()) {
            state.setWakeLockScreenSensorActive(active);
        }

        if (mState == ScrimState.PULSING) {
            float newBehindAlpha = mState.getBehindAlpha();
            if (mBehindAlpha != newBehindAlpha) {
                mBehindAlpha = newBehindAlpha;
                if (isNaN(mBehindAlpha)) {
                    throw new IllegalStateException("Scrim opacity is NaN for state: " + mState
                            + ", back: " + mBehindAlpha);
                }
                updateScrims();
            }
        }
    }

    protected void scheduleUpdate() {
        if (mUpdatePending || mScrimBehind == null) return;

        // Make sure that a frame gets scheduled.
        mScrimBehind.invalidate();
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    protected void updateScrims() {
        // Make sure we have the right gradients and their opacities will satisfy GAR.
        if (mNeedsDrawableColorUpdate) {
            mNeedsDrawableColorUpdate = false;
            // Only animate scrim color if the scrim view is actually visible
            boolean animateScrimInFront = mScrimInFront.getViewAlpha() != 0 && !mBlankScreen;
            boolean animateScrimBehind = mScrimBehind.getViewAlpha() != 0 && !mBlankScreen;
            boolean animateScrimForBubble = mScrimForBubble.getViewAlpha() != 0 && !mBlankScreen;

            mScrimInFront.setColors(mColors, animateScrimInFront);
            mScrimBehind.setColors(mColors, animateScrimBehind);
            mScrimForBubble.setColors(mColors, animateScrimForBubble);

            // Calculate minimum scrim opacity for white or black text.
            int textColor = mColors.supportsDarkText() ? Color.BLACK : Color.WHITE;
            int mainColor = mColors.getMainColor();
            float minOpacity = ColorUtils.calculateMinimumBackgroundAlpha(textColor, mainColor,
                    4.5f /* minimumContrast */) / 255f;
            dispatchScrimState(mScrimBehind.getViewAlpha());
        }

        // We want to override the back scrim opacity for the AOD state
        // when it's time to fade the wallpaper away.
        boolean aodWallpaperTimeout = (mState == ScrimState.AOD || mState == ScrimState.PULSING)
                && mWallpaperVisibilityTimedOut;
        // We also want to hide FLAG_SHOW_WHEN_LOCKED activities under the scrim.
        boolean occludedKeyguard = (mState == ScrimState.PULSING || mState == ScrimState.AOD)
                && mKeyguardOccluded;
        if (aodWallpaperTimeout || occludedKeyguard) {
            mBehindAlpha = 1;
        }
        setScrimAlpha(mScrimInFront, mInFrontAlpha);
        setScrimAlpha(mScrimBehind, mBehindAlpha);
        setScrimAlpha(mScrimForBubble, mBubbleAlpha);
        // The animation could have all already finished, let's call onFinished just in case
        onFinished();
        dispatchScrimsVisible();
    }

    private void dispatchScrimState(float alpha) {
        mScrimStateListener.accept(mState, alpha, mScrimInFront.getColors());
    }

    private void dispatchScrimsVisible() {
        final int currentScrimVisibility;
        if (mScrimInFront.getViewAlpha() == 1 || mScrimBehind.getViewAlpha() == 1) {
            currentScrimVisibility = OPAQUE;
        } else if (mScrimInFront.getViewAlpha() == 0 && mScrimBehind.getViewAlpha() == 0) {
            currentScrimVisibility = TRANSPARENT;
        } else {
            currentScrimVisibility = SEMI_TRANSPARENT;
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
            return (float) (1f - 0.5f * (1f - Math.cos(3.14159f * Math.pow(1f - frac, 2f))));
        }
    }

    private void setScrimAlpha(ScrimView scrim, float alpha) {
        if (alpha == 0f) {
            scrim.setClickable(false);
        } else {
            // Eat touch events (unless dozing).
            scrim.setClickable(mState != ScrimState.AOD);
        }
        updateScrim(scrim, alpha);
    }

    private String getScrimName(ScrimView scrim) {
        if (scrim == mScrimInFront) {
            return "front_scrim";
        } else if (scrim == mScrimBehind) {
            return "back_scrim";
        } else if (scrim == mScrimForBubble) {
            return "bubble_scrim";
        }
        return "unknown_scrim";
    }

    private void updateScrimColor(View scrim, float alpha, int tint) {
        alpha = Math.max(0, Math.min(1.0f, alpha));
        if (scrim instanceof ScrimView) {
            ScrimView scrimView = (ScrimView) scrim;

            Trace.traceCounter(Trace.TRACE_TAG_APP, getScrimName(scrimView) + "_alpha",
                    (int) (alpha * 255));

            Trace.traceCounter(Trace.TRACE_TAG_APP, getScrimName(scrimView) + "_tint",
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
        if (mAnimatorListener != null) {
            anim.addListener(mAnimatorListener);
        }
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
            private Callback lastCallback = mCallback;

            @Override
            public void onAnimationEnd(Animator animation) {
                scrim.setTag(TAG_KEY_ANIM, null);
                onFinished(lastCallback);

                dispatchScrimsVisible();
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
            return mInFrontAlpha;
        } else if (scrim == mScrimBehind) {
            return mBehindAlpha;
        } else if (scrim == mScrimForBubble) {
            return mBubbleAlpha;
        } else {
            throw new IllegalArgumentException("Unknown scrim view");
        }
    }

    private int getCurrentScrimTint(View scrim) {
        if (scrim == mScrimInFront) {
            return mInFrontTint;
        } else if (scrim == mScrimBehind) {
            return mBehindTint;
        } else if (scrim == mScrimForBubble) {
            return mBubbleTint;
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
        return true;
    }

    private void onFinished() {
        onFinished(mCallback);
    }

    private void onFinished(Callback callback) {
        if (mPendingFrameCallback != null) {
            // No animations can finish while we're waiting on the blanking to finish
            return;

        }
        if (isAnimating(mScrimBehind)
            || isAnimating(mScrimInFront)
            || isAnimating(mScrimForBubble)) {
            if (callback != null && callback != mCallback) {
                // Since we only notify the callback that we're finished once everything has
                // finished, we need to make sure that any changing callbacks are also invoked
                callback.onFinished();
            }
            return;
        }
        if (mWakeLockHeld) {
            mWakeLock.release(TAG);
            mWakeLockHeld = false;
        }

        if (callback != null) {
            callback.onFinished();

            if (callback == mCallback) {
                mCallback = null;
            }
        }

        // When unlocking with fingerprint, we'll fade the scrims from black to transparent.
        // At the end of the animation we need to remove the tint.
        if (mState == ScrimState.UNLOCKED) {
            mInFrontTint = Color.TRANSPARENT;
            mBehindTint = Color.TRANSPARENT;
            mBubbleTint = Color.TRANSPARENT;
            updateScrimColor(mScrimInFront, mInFrontAlpha, mInFrontTint);
            updateScrimColor(mScrimBehind, mBehindAlpha, mBehindTint);
            updateScrimColor(mScrimForBubble, mBubbleAlpha, mBubbleTint);
        }
    }

    private boolean isAnimating(View scrim) {
        return scrim.getTag(TAG_KEY_ANIM) != null;
    }

    @VisibleForTesting
    void setAnimatorListener(Animator.AnimatorListener animatorListener) {
        mAnimatorListener = animatorListener;
    }

    private void updateScrim(ScrimView scrim, float alpha) {
        final float currentAlpha = scrim.getViewAlpha();

        ValueAnimator previousAnimator = ViewState.getChildTag(scrim, TAG_KEY_ANIM);
        if (previousAnimator != null) {
            // Previous animators should always be cancelled. Not doing so would cause
            // overlap, especially on states that don't animate, leading to flickering,
            // and in the worst case, an internal state that doesn't represent what
            // transitionTo requested.
            cancelAnimator(previousAnimator);
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
            }
        }
    }

    private void cancelAnimator(ValueAnimator previousAnimator) {
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
            mHandler.postDelayed(mBlankingTransitionRunnable, delay);
        };
        doOnTheNextFrame(mPendingFrameCallback);
    }

    /**
     * Executes a callback after the frame has hit the display.
     *
     * @param callback What to run.
     */
    @VisibleForTesting
    protected void doOnTheNextFrame(Runnable callback) {
        // Just calling View#postOnAnimation isn't enough because the frame might not have reached
        // the display yet. A timeout is the safest solution.
        mScrimBehind.postOnAnimationDelayed(callback, 32 /* delayMillis */);
    }

    public int getBackgroundColor() {
        int color = mColors.getMainColor();
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
        mColors = mColorExtractor.getNeutralColors();
        mNeedsDrawableColorUpdate = true;
        scheduleUpdate();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(" ScrimController: ");
        pw.print("  state: ");
        pw.println(mState);

        pw.print("  frontScrim:");
        pw.print(" viewAlpha=");
        pw.print(mScrimInFront.getViewAlpha());
        pw.print(" alpha=");
        pw.print(mInFrontAlpha);
        pw.print(" tint=0x");
        pw.println(Integer.toHexString(mScrimInFront.getTint()));

        pw.print("  backScrim:");
        pw.print(" viewAlpha=");
        pw.print(mScrimBehind.getViewAlpha());
        pw.print(" alpha=");
        pw.print(mBehindAlpha);
        pw.print(" tint=0x");
        pw.println(Integer.toHexString(mScrimBehind.getTint()));

        pw.print("  bubbleScrim:");
        pw.print(" viewAlpha=");
        pw.print(mScrimForBubble.getViewAlpha());
        pw.print(" alpha=");
        pw.print(mBubbleAlpha);
        pw.print(" tint=0x");
        pw.println(Integer.toHexString(mScrimForBubble.getTint()));

        pw.print("  mTracking=");
        pw.println(mTracking);
        pw.print("  mDefaultScrimAlpha=");
        pw.println(mDefaultScrimAlpha);
        pw.print("  mExpansionFraction=");
        pw.println(mExpansionFraction);
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
        if (mHandler.hasCallbacks(mBlankingTransitionRunnable)) {
            if (DEBUG) {
                Log.d(TAG, "Shorter blanking because screen turned on. All good.");
            }
            mHandler.removeCallbacks(mBlankingTransitionRunnable);
            mBlankingTransitionRunnable.run();
        }
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
    }

    public void setExpansionAffectsAlpha(boolean expansionAffectsAlpha) {
        mExpansionAffectsAlpha = expansionAffectsAlpha;
        if (expansionAffectsAlpha) {
            applyAndDispatchExpansion();
        }
    }

    public void setKeyguardOccluded(boolean keyguardOccluded) {
        mKeyguardOccluded = keyguardOccluded;
        updateScrims();
    }

    public void setHasBackdrop(boolean hasBackdrop) {
        for (ScrimState state : ScrimState.values()) {
            state.setHasBackdrop(hasBackdrop);
        }

        // Backdrop event may arrive after state was already applied,
        // in this case, back-scrim needs to be re-evaluated
        if (mState == ScrimState.AOD || mState == ScrimState.PULSING) {
            float newBehindAlpha = mState.getBehindAlpha();
            if (isNaN(newBehindAlpha)) {
                throw new IllegalStateException("Scrim opacity is NaN for state: " + mState
                        + ", back: " + mBehindAlpha);
            }
            if (mBehindAlpha != newBehindAlpha) {
                mBehindAlpha = newBehindAlpha;
                updateScrims();
            }
        }
    }

    private void setKeyguardFadingAway(boolean fadingAway, long duration) {
        for (ScrimState state : ScrimState.values()) {
            state.setKeyguardFadingAway(fadingAway, duration);
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

    /**
     * Simple keyguard callback that updates scrims when keyguard visibility changes.
     */
    private class KeyguardVisibilityCallback extends KeyguardUpdateMonitorCallback {

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mNeedsDrawableColorUpdate = true;
            scheduleUpdate();
        }
    }
}
