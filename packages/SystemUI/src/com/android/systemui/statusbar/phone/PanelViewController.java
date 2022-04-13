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

package com.android.systemui.statusbar.phone;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;
import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.GENERIC;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.UNLOCK;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.Log;
import android.util.MathUtils;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.DejankUtils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.animation.FlingAnimationUtils;

import java.io.PrintWriter;

public abstract class PanelViewController {
    public static final boolean DEBUG = PanelView.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();
    public static final float FLING_MAX_LENGTH_SECONDS = 0.6f;
    public static final float FLING_SPEED_UP_FACTOR = 0.6f;
    public static final float FLING_CLOSING_MAX_LENGTH_SECONDS = 0.6f;
    public static final float FLING_CLOSING_SPEED_UP_FACTOR = 0.6f;
    private static final int NO_FIXED_DURATION = -1;
    private static final long SHADE_OPEN_SPRING_OUT_DURATION = 350L;
    private static final long SHADE_OPEN_SPRING_BACK_DURATION = 400L;

    /**
     * The factor of the usual high velocity that is needed in order to reach the maximum overshoot
     * when flinging. A low value will make it that most flings will reach the maximum overshoot.
     */
    private static final float FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT = 0.5f;

    protected long mDownTime;
    protected boolean mTouchSlopExceededBeforeDown;
    private float mMinExpandHeight;
    private boolean mPanelUpdateWhenAnimatorEnds;
    private boolean mVibrateOnOpening;
    protected boolean mIsLaunchAnimationRunning;
    private int mFixedDuration = NO_FIXED_DURATION;
    protected float mOverExpansion;

    /**
     * The overshoot amount when the panel flings open
     */
    private float mPanelFlingOvershootAmount;

    /**
     * The amount of pixels that we have overexpanded the last time with a gesture
     */
    private float mLastGesturedOverExpansion = -1;

    /**
     * Is the current animator the spring back animation?
     */
    private boolean mIsSpringBackAnimation;

    private void logf(String fmt, Object... args) {
        Log.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
    }

    protected CentralSurfaces mCentralSurfaces;
    protected HeadsUpManagerPhone mHeadsUpManager;
    protected final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;

    private float mHintDistance;
    private float mInitialOffsetOnTouch;
    private boolean mCollapsedAndHeadsUpOnDown;
    private float mExpandedFraction = 0;
    protected float mExpandedHeight = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mClosing;
    protected boolean mTracking;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    private int mTouchSlop;
    private float mSlopMultiplier;
    protected boolean mHintAnimationRunning;
    private boolean mOverExpandedBeforeFling;
    private boolean mTouchAboveFalsingThreshold;
    private int mUnlockFalsingThreshold;
    private boolean mTouchStartedInEmptyArea;
    private boolean mMotionAborted;
    private boolean mUpwardsWhenThresholdReached;
    private boolean mAnimatingOnDown;
    private boolean mHandlingPointerUp;

    private ValueAnimator mHeightAnimator;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private FlingAnimationUtils mFlingAnimationUtils;
    private FlingAnimationUtils mFlingAnimationUtilsClosing;
    private FlingAnimationUtils mFlingAnimationUtilsDismissing;
    private final LatencyTracker mLatencyTracker;
    private final FalsingManager mFalsingManager;
    private final DozeLog mDozeLog;
    private final VibratorHelper mVibratorHelper;

    /**
     * Whether an instant expand request is currently pending and we are just waiting for layout.
     */
    private boolean mInstantExpanding;
    private boolean mAnimateAfterExpanding;
    private boolean mIsFlinging;

    private String mViewName;
    private float mInitialTouchY;
    private float mInitialTouchX;
    private boolean mTouchDisabled;

    /**
     * Whether or not the PanelView can be expanded or collapsed with a drag.
     */
    private boolean mNotificationsDragEnabled;

    private Interpolator mBounceInterpolator;
    protected KeyguardBottomAreaView mKeyguardBottomArea;

    /**
     * Speed-up factor to be used when {@link #mFlingCollapseRunnable} runs the next time.
     */
    private float mNextCollapseSpeedUpFactor = 1.0f;

    protected boolean mExpanding;
    private boolean mGestureWaitForTouchSlop;
    private boolean mIgnoreXTouchSlop;
    private boolean mExpandLatencyTracking;
    private final PanelView mView;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    protected final Resources mResources;
    protected final KeyguardStateController mKeyguardStateController;
    protected final SysuiStatusBarStateController mStatusBarStateController;
    protected final AmbientState mAmbientState;
    protected final LockscreenGestureLogger mLockscreenGestureLogger;
    private final PanelExpansionStateManager mPanelExpansionStateManager;
    private final TouchHandler mTouchHandler;
    private final InteractionJankMonitor mInteractionJankMonitor;

    protected abstract void onExpandingFinished();

    protected void onExpandingStarted() {
    }

    protected void notifyExpandingStarted() {
        if (!mExpanding) {
            mExpanding = true;
            onExpandingStarted();
        }
    }

    protected final void notifyExpandingFinished() {
        endClosing();
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    protected AmbientState getAmbientState() {
        return mAmbientState;
    }

    private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;

    public PanelViewController(
            PanelView view,
            FalsingManager falsingManager,
            DozeLog dozeLog,
            KeyguardStateController keyguardStateController,
            SysuiStatusBarStateController statusBarStateController,
            NotificationShadeWindowController notificationShadeWindowController,
            VibratorHelper vibratorHelper,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            LatencyTracker latencyTracker,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            LockscreenGestureLogger lockscreenGestureLogger,
            PanelExpansionStateManager panelExpansionStateManager,
            AmbientState ambientState,
            InteractionJankMonitor interactionJankMonitor,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController) {
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardFadingAwayChanged() {
                requestPanelHeightUpdate();
            }
        });
        mAmbientState = ambientState;
        mView = view;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mPanelExpansionStateManager = panelExpansionStateManager;
        mTouchHandler = createTouchHandler();
        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mViewName = mResources.getResourceName(mView.getId());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });

        mView.addOnLayoutChangeListener(createLayoutChangeListener());
        mView.setOnTouchListener(mTouchHandler);
        mView.setOnConfigurationChangedListener(createOnConfigurationChangedListener());

        mResources = mView.getResources();
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mFlingAnimationUtils = flingAnimationUtilsBuilder
                .reset()
                .setMaxLengthSeconds(FLING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsClosing = flingAnimationUtilsBuilder
                .reset()
                .setMaxLengthSeconds(FLING_CLOSING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_CLOSING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsDismissing = flingAnimationUtilsBuilder
                .reset()
                .setMaxLengthSeconds(0.5f)
                .setSpeedUpFactor(0.6f)
                .setX2(0.6f)
                .setY2(0.84f)
                .build();
        mLatencyTracker = latencyTracker;
        mBounceInterpolator = new BounceInterpolator();
        mFalsingManager = falsingManager;
        mDozeLog = dozeLog;
        mNotificationsDragEnabled = mResources.getBoolean(
                R.bool.config_enableNotificationShadeDrag);
        mVibratorHelper = vibratorHelper;
        mVibrateOnOpening = mResources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mInteractionJankMonitor = interactionJankMonitor;
    }

    protected void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(mView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mHintDistance = mResources.getDimension(R.dimen.hint_move_distance);
        mPanelFlingOvershootAmount = mResources.getDimension(R.dimen.panel_overshoot_amount);
        mUnlockFalsingThreshold = mResources.getDimensionPixelSize(
                R.dimen.unlock_falsing_threshold);
    }

    protected float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mSlopMultiplier
                : mTouchSlop;
    }

    private void addMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    public void setTouchAndAnimationDisabled(boolean disabled) {
        mTouchDisabled = disabled;
        if (mTouchDisabled) {
            cancelHeightAnimator();
            if (mTracking) {
                onTrackingStopped(true /* expanded */);
            }
            notifyExpandingFinished();
        }
    }

    public void startExpandLatencyTracking() {
        if (mLatencyTracker.isEnabled()) {
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_EXPAND_PANEL);
            mExpandLatencyTracking = true;
        }
    }

    private void startOpening(MotionEvent event) {
        updatePanelExpansionAndVisibility();
        maybeVibrateOnOpening();

        //TODO: keyguard opens QS a different way; log that too?

        // Log the position of the swipe that opened the panel
        float width = mCentralSurfaces.getDisplayWidth();
        float height = mCentralSurfaces.getDisplayHeight();
        int rot = mCentralSurfaces.getRotation();

        mLockscreenGestureLogger.writeAtFractionalPosition(MetricsEvent.ACTION_PANEL_VIEW_EXPAND,
                (int) (event.getX() / width * 100), (int) (event.getY() / height * 100), rot);
        mLockscreenGestureLogger
                .log(LockscreenUiEvent.LOCKSCREEN_UNLOCKED_NOTIFICATION_PANEL_EXPAND);
    }

    protected void maybeVibrateOnOpening() {
        if (mVibrateOnOpening) {
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
        }
    }

    protected abstract float getOpeningHeight();

    /**
     * @return whether the swiping direction is upwards and above a 45 degree angle compared to the
     * horizontal direction
     */
    private boolean isDirectionUpwards(float x, float y) {
        float xDiff = x - mInitialTouchX;
        float yDiff = y - mInitialTouchY;
        if (yDiff >= 0) {
            return false;
        }
        return Math.abs(yDiff) >= Math.abs(xDiff);
    }

    protected void startExpandMotion(float newX, float newY, boolean startTracking,
            float expandedHeight) {
        if (!mHandlingPointerUp && !mStatusBarStateController.isDozing()) {
            beginJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
        }
        mInitialOffsetOnTouch = expandedHeight;
        mInitialTouchY = newY;
        mInitialTouchX = newX;
        if (startTracking) {
            mTouchSlopExceeded = true;
            setExpandedHeight(mInitialOffsetOnTouch);
            onTrackingStarted();
        }
    }

    private void endMotionEvent(MotionEvent event, float x, float y, boolean forceCancel) {
        mTrackingPointer = -1;
        mAmbientState.setSwipingUp(false);
        if ((mTracking && mTouchSlopExceeded) || Math.abs(x - mInitialTouchX) > mTouchSlop
                || Math.abs(y - mInitialTouchY) > mTouchSlop
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vel = mVelocityTracker.getYVelocity();
            float vectorVel = (float) Math.hypot(
                    mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

            final boolean onKeyguard =
                    mStatusBarStateController.getState() == StatusBarState.KEYGUARD;

            final boolean expand;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
                // If the keyguard is fading away, don't expand it again. This can happen if you're
                // swiping to unlock, the app below the keyguard is in landscape, and the screen
                // rotates while your finger is still down after the swipe to unlock.
                if (mKeyguardStateController.isKeyguardFadingAway()) {
                    expand = false;
                } else if (onKeyguard) {
                    expand = true;
                } else if (mKeyguardStateController.isKeyguardFadingAway()) {
                    // If we're in the middle of dismissing the keyguard, don't expand due to the
                    // cancelled gesture. Gesture cancellation during an unlock is expected in some
                    // situations, such keeping your finger down while swiping to unlock to an app
                    // that is locked in landscape (the rotation will cancel the touch event).
                    expand = false;
                } else {
                    // If we get a cancel, put the shade back to the state it was in when the
                    // gesture started
                    expand = !mPanelClosedOnDown;
                }
            } else {
                expand = flingExpands(vel, vectorVel, x, y);
            }

            mDozeLog.traceFling(expand, mTouchAboveFalsingThreshold,
                    mCentralSurfaces.isFalsingThresholdNeeded(),
                    mCentralSurfaces.isWakeUpComingFromTouch());
            // Log collapse gesture if on lock screen.
            if (!expand && onKeyguard) {
                float displayDensity = mCentralSurfaces.getDisplayDensity();
                int heightDp = (int) Math.abs((y - mInitialTouchY) / displayDensity);
                int velocityDp = (int) Math.abs(vel / displayDensity);
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_UNLOCK, heightDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_UNLOCK);
            }
            @Classifier.InteractionType int interactionType = vel == 0 ? GENERIC
                    : y - mInitialTouchY > 0 ? QUICK_SETTINGS
                            : (mKeyguardStateController.canDismissLockScreen()
                                    ? UNLOCK : BOUNCER_UNLOCK);

            fling(vel, expand, isFalseTouch(x, y, interactionType));
            onTrackingStopped(expand);
            mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
            if (mUpdateFlingOnLayout) {
                mUpdateFlingVelocity = vel;
            }
        } else if (!mCentralSurfaces.isBouncerShowing()
                && !mStatusBarKeyguardViewManager.isShowingAlternateAuthOrAnimating()
                && !mKeyguardStateController.isKeyguardGoingAway()) {
            boolean expands = onEmptySpaceClick(mInitialTouchX);
            onTrackingStopped(expands);
        }
        mVelocityTracker.clear();
    }

    protected float getCurrentExpandVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private int getFalsingThreshold() {
        float factor = mCentralSurfaces.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        return (int) (mUnlockFalsingThreshold * factor);
    }

    protected abstract boolean shouldGestureWaitForTouchSlop();

    protected abstract boolean shouldGestureIgnoreXTouchSlop(float x, float y);

    protected void onTrackingStopped(boolean expand) {
        mTracking = false;
        mCentralSurfaces.onTrackingStopped(expand);
        updatePanelExpansionAndVisibility();
    }

    protected void onTrackingStarted() {
        endClosing();
        mTracking = true;
        mCentralSurfaces.onTrackingStarted();
        notifyExpandingStarted();
        updatePanelExpansionAndVisibility();
    }

    /**
     * @return Whether a pair of coordinates are inside the visible view content bounds.
     */
    protected abstract boolean isInContentBounds(float x, float y);

    protected void cancelHeightAnimator() {
        if (mHeightAnimator != null) {
            if (mHeightAnimator.isRunning()) {
                mPanelUpdateWhenAnimatorEnds = false;
            }
            mHeightAnimator.cancel();
        }
        endClosing();
    }

    private void endClosing() {
        if (mClosing) {
            setIsClosing(false);
            onClosingFinished();
        }
    }

    protected boolean canCollapsePanelOnTouch() {
        return true;
    }

    protected float getContentHeight() {
        return mExpandedHeight;
    }

    /**
     * @param vel       the current vertical velocity of the motion
     * @param vectorVel the length of the vectorial velocity
     * @return whether a fling should expands the panel; contracts otherwise
     */
    protected boolean flingExpands(float vel, float vectorVel, float x, float y) {
        if (mFalsingManager.isUnlockingDisabled()) {
            return true;
        }

        @Classifier.InteractionType int interactionType = y - mInitialTouchY > 0
                ? QUICK_SETTINGS : (
                        mKeyguardStateController.canDismissLockScreen() ? UNLOCK : BOUNCER_UNLOCK);

        if (isFalseTouch(x, y, interactionType)) {
            return true;
        }
        if (Math.abs(vectorVel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return shouldExpandWhenNotFlinging();
        } else {
            return vel > 0;
        }
    }

    protected boolean shouldExpandWhenNotFlinging() {
        return getExpandedFraction() > 0.5f;
    }

    /**
     * @param x the final x-coordinate when the finger was lifted
     * @param y the final y-coordinate when the finger was lifted
     * @return whether this motion should be regarded as a false touch
     */
    private boolean isFalseTouch(float x, float y,
            @Classifier.InteractionType int interactionType) {
        if (!mCentralSurfaces.isFalsingThresholdNeeded()) {
            return false;
        }
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(interactionType);
        }
        if (!mTouchAboveFalsingThreshold) {
            return true;
        }
        if (mUpwardsWhenThresholdReached) {
            return false;
        }
        return !isDirectionUpwards(x, y);
    }

    protected void fling(float vel, boolean expand) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, false);
    }

    protected void fling(float vel, boolean expand, boolean expandBecauseOfFalsing) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, expandBecauseOfFalsing);
    }

    protected void fling(float vel, boolean expand, float collapseSpeedUpFactor,
            boolean expandBecauseOfFalsing) {
        float target = expand ? getMaxPanelHeight() : 0;
        if (!expand) {
            setIsClosing(true);
        }
        flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    protected void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        if (target == mExpandedHeight && mOverExpansion == 0.0f) {
            // We're at the target and didn't fling and there's no overshoot
            endJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
            mKeyguardStateController.notifyPanelFlingEnd();
            notifyExpandingFinished();
            return;
        }
        mIsFlinging = true;
        // we want to perform an overshoot animation when flinging open
        final boolean addOverscroll = expand
                && mStatusBarStateController.getState() != StatusBarState.KEYGUARD
                && mOverExpansion == 0.0f
                && vel >= 0;
        final boolean shouldSpringBack = addOverscroll || (mOverExpansion != 0.0f && expand);
        float overshootAmount = 0.0f;
        if (addOverscroll) {
            // Let's overshoot depending on the amount of velocity
            overshootAmount = MathUtils.lerp(
                    0.2f,
                    1.0f,
                    MathUtils.saturate(vel
                            / (mFlingAnimationUtils.getHighVelocityPxPerSecond()
                                    * FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT)));
            overshootAmount += mOverExpansion / mPanelFlingOvershootAmount;
        }
        ValueAnimator animator = createHeightAnimator(target, overshootAmount);
        if (expand) {
            if (expandBecauseOfFalsing && vel < 0) {
                vel = 0;
            }
            mFlingAnimationUtils.apply(animator, mExpandedHeight,
                    target + overshootAmount * mPanelFlingOvershootAmount, vel, mView.getHeight());
            if (vel == 0) {
                animator.setDuration(SHADE_OPEN_SPRING_OUT_DURATION);
            }
        } else {
            if (shouldUseDismissingAnimation()) {
                if (vel == 0) {
                    animator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
                    long duration = (long) (200 + mExpandedHeight / mView.getHeight() * 100);
                    animator.setDuration(duration);
                } else {
                    mFlingAnimationUtilsDismissing.apply(animator, mExpandedHeight, target, vel,
                            mView.getHeight());
                }
            } else {
                mFlingAnimationUtilsClosing.apply(
                        animator, mExpandedHeight, target, vel, mView.getHeight());
            }

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / collapseSpeedUpFactor));
            }
            if (mFixedDuration != NO_FIXED_DURATION) {
                animator.setDuration(mFixedDuration);
            }
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                if (!mStatusBarStateController.isDozing()) {
                    beginJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (shouldSpringBack && !mCancelled) {
                    // After the shade is flinged open to an overscrolled state, spring back
                    // the shade by reducing section padding to 0.
                    springBack();
                } else {
                    onFlingEnd(mCancelled);
                }
            }
        });
        setAnimator(animator);
        animator.start();
    }

    private void springBack() {
        if (mOverExpansion == 0) {
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsSpringBackAnimation = true;
        ValueAnimator animator = ValueAnimator.ofFloat(mOverExpansion, 0);
        animator.addUpdateListener(
                animation -> {
                    setOverExpansionInternal((float) animation.getAnimatedValue(),
                            false /* isFromGesture */);
                });
        animator.setDuration(SHADE_OPEN_SPRING_BACK_DURATION);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;
            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                mIsSpringBackAnimation = false;
                onFlingEnd(mCancelled);
            }
        });
        setAnimator(animator);
        animator.start();
    }

    void onFlingEnd(boolean cancelled) {
        mIsFlinging = false;
        // No overshoot when the animation ends
        setOverExpansionInternal(0, false /* isFromGesture */);
        setAnimator(null);
        mKeyguardStateController.notifyPanelFlingEnd();
        if (!cancelled) {
            endJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
            notifyExpandingFinished();
        } else {
            cancelJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
        }
        updatePanelExpansionAndVisibility();
    }

    protected abstract boolean shouldUseDismissingAnimation();

    public String getName() {
        return mViewName;
    }

    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        setExpandedHeightInternal(height);
    }

    protected void requestPanelHeightUpdate() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        if (isFullyCollapsed()) {
            return;
        }

        if (currentMaxPanelHeight == mExpandedHeight) {
            return;
        }

        if (mTracking && !isTrackingBlocked()) {
            return;
        }

        if (mHeightAnimator != null && !mIsSpringBackAnimation) {
            mPanelUpdateWhenAnimatorEnds = true;
            return;
        }

        setExpandedHeight(currentMaxPanelHeight);
    }

    private float getStackHeightFraction(float height) {
        final float gestureFraction = height / getMaxPanelHeight();
        final float stackHeightFraction = Interpolators.ACCELERATE_DECELERATE
                .getInterpolation(gestureFraction);
        return stackHeightFraction;
    }

    public void setExpandedHeightInternal(float h) {
        if (isNaN(h)) {
            Log.wtf(TAG, "ExpandedHeight set to NaN");
        }
        mNotificationShadeWindowController.batchApplyWindowLayoutParams(()-> {
            if (mExpandLatencyTracking && h != 0f) {
                DejankUtils.postAfterTraversal(
                        () -> mLatencyTracker.onActionEnd(LatencyTracker.ACTION_EXPAND_PANEL));
                mExpandLatencyTracking = false;
            }
            float maxPanelHeight = getMaxPanelHeight();
            if (mHeightAnimator == null) {
                if (mTracking) {
                    float overExpansionPixels = Math.max(0, h - maxPanelHeight);
                    setOverExpansionInternal(overExpansionPixels, true /* isFromGesture */);
                }
                mExpandedHeight = Math.min(h, maxPanelHeight);
            } else {
                mExpandedHeight = h;
            }

            // If we are closing the panel and we are almost there due to a slow decelerating
            // interpolator, abort the animation.
            if (mExpandedHeight < 1f && mExpandedHeight != 0f && mClosing) {
                mExpandedHeight = 0f;
                if (mHeightAnimator != null) {
                    mHeightAnimator.end();
                }
            }
            mExpandedFraction = Math.min(1f,
                    maxPanelHeight == 0 ? 0 : mExpandedHeight / maxPanelHeight);
            mAmbientState.setExpansionFraction(mStatusBarKeyguardViewManager.bouncerIsInTransit()
                    ? BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(mExpandedFraction)
                    : mExpandedFraction);
            onHeightUpdated(mExpandedHeight);
            updatePanelExpansionAndVisibility();
        });
    }

    /**
     * @return true if the panel tracking should be temporarily blocked; this is used when a
     * conflicting gesture (opening QS) is happening
     */
    protected abstract boolean isTrackingBlocked();

    protected void setOverExpansion(float overExpansion) {
        mOverExpansion = overExpansion;
    }

    /**
     * Set the current overexpansion
     *
     * @param overExpansion the amount of overexpansion to apply
     * @param isFromGesture is this amount from a gesture and needs to be rubberBanded?
     */
    private void setOverExpansionInternal(float overExpansion, boolean isFromGesture) {
        if (!isFromGesture) {
            mLastGesturedOverExpansion = -1;
            setOverExpansion(overExpansion);
        } else if (mLastGesturedOverExpansion != overExpansion) {
            mLastGesturedOverExpansion = overExpansion;
            final float heightForFullOvershoot = mView.getHeight() / 3.0f;
            float newExpansion = MathUtils.saturate(overExpansion / heightForFullOvershoot);
            newExpansion = Interpolators.getOvershootInterpolation(newExpansion);
            setOverExpansion(newExpansion * mPanelFlingOvershootAmount * 2.0f);
        }
    }

    protected abstract void onHeightUpdated(float expandedHeight);

    /**
     * This returns the maximum height of the panel. Children should override this if their
     * desired height is not the full height.
     *
     * @return the default implementation simply returns the maximum height.
     */
    protected abstract int getMaxPanelHeight();

    public void setExpandedFraction(float frac) {
        setExpandedHeight(getMaxPanelHeight() * frac);
    }

    public float getExpandedHeight() {
        return mExpandedHeight;
    }

    public float getExpandedFraction() {
        return mExpandedFraction;
    }

    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelHeight();
    }

    public boolean isFullyCollapsed() {
        return mExpandedFraction <= 0.0f;
    }

    public boolean isCollapsing() {
        return mClosing || mIsLaunchAnimationRunning;
    }

    public boolean isFlinging() {
        return mIsFlinging;
    }

    public boolean isTracking() {
        return mTracking;
    }

    public void collapse(boolean delayed, float speedUpFactor) {
        if (DEBUG) logf("collapse: " + this);
        if (canPanelBeCollapsed()) {
            cancelHeightAnimator();
            notifyExpandingStarted();

            // Set after notifyExpandingStarted, as notifyExpandingStarted resets the closing state.
            setIsClosing(true);
            if (delayed) {
                mNextCollapseSpeedUpFactor = speedUpFactor;
                mView.postDelayed(mFlingCollapseRunnable, 120);
            } else {
                fling(0, false /* expand */, speedUpFactor, false /* expandBecauseOfFalsing */);
            }
        }
    }

    public boolean canPanelBeCollapsed() {
        return !isFullyCollapsed() && !mTracking && !mClosing;
    }

    private final Runnable mFlingCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            fling(0, false /* expand */, mNextCollapseSpeedUpFactor,
                    false /* expandBecauseOfFalsing */);
        }
    };

    public void expand(final boolean animate) {
        if (!isFullyCollapsed() && !isCollapsing()) {
            return;
        }

        mInstantExpanding = true;
        mAnimateAfterExpanding = animate;
        mUpdateFlingOnLayout = false;
        abortAnimations();
        if (mTracking) {
            onTrackingStopped(true /* expands */); // The panel is expanded after this call.
        }
        if (mExpanding) {
            notifyExpandingFinished();
        }
        updatePanelExpansionAndVisibility();

        // Wait for window manager to pickup the change, so we know the maximum height of the panel
        // then.
        mView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!mInstantExpanding) {
                            mView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            return;
                        }
                        if (mCentralSurfaces.getNotificationShadeWindowView().isVisibleToUser()) {
                            mView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            if (mAnimateAfterExpanding) {
                                notifyExpandingStarted();
                                beginJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
                                fling(0, true /* expand */);
                            } else {
                                setExpandedFraction(1f);
                            }
                            mInstantExpanding = false;
                        }
                    }
                });

        // Make sure a layout really happens.
        mView.requestLayout();
    }

    public void instantCollapse() {
        abortAnimations();
        setExpandedFraction(0f);
        if (mExpanding) {
            notifyExpandingFinished();
        }
        if (mInstantExpanding) {
            mInstantExpanding = false;
            updatePanelExpansionAndVisibility();
        }
    }

    private void abortAnimations() {
        cancelHeightAnimator();
        mView.removeCallbacks(mFlingCollapseRunnable);
    }

    protected abstract void onClosingFinished();

    protected void startUnlockHintAnimation() {

        // We don't need to hint the user if an animation is already running or the user is changing
        // the expansion.
        if (mHeightAnimator != null || mTracking) {
            return;
        }
        notifyExpandingStarted();
        startUnlockHintAnimationPhase1(() -> {
            notifyExpandingFinished();
            onUnlockHintFinished();
            mHintAnimationRunning = false;
        });
        onUnlockHintStarted();
        mHintAnimationRunning = true;
    }

    protected void onUnlockHintFinished() {
        mCentralSurfaces.onHintFinished();
    }

    protected void onUnlockHintStarted() {
        mCentralSurfaces.onUnlockHintStarted();
    }

    public boolean isUnlockHintRunning() {
        return mHintAnimationRunning;
    }

    /**
     * Phase 1: Move everything upwards.
     */
    private void startUnlockHintAnimationPhase1(final Runnable onAnimationFinished) {
        float target = Math.max(0, getMaxPanelHeight() - mHintDistance);
        ValueAnimator animator = createHeightAnimator(target);
        animator.setDuration(250);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    setAnimator(null);
                    onAnimationFinished.run();
                } else {
                    startUnlockHintAnimationPhase2(onAnimationFinished);
                }
            }
        });
        animator.start();
        setAnimator(animator);

        View[] viewsToAnimate = {
                mKeyguardBottomArea.getIndicationArea(),
                mCentralSurfaces.getAmbientIndicationContainer()};
        for (View v : viewsToAnimate) {
            if (v == null) {
                continue;
            }
            v.animate().translationY(-mHintDistance).setDuration(250).setInterpolator(
                    Interpolators.FAST_OUT_SLOW_IN).withEndAction(() -> v.animate().translationY(
                    0).setDuration(450).setInterpolator(mBounceInterpolator).start()).start();
        }
    }

    private void setAnimator(ValueAnimator animator) {
        mHeightAnimator = animator;
        if (animator == null && mPanelUpdateWhenAnimatorEnds) {
            mPanelUpdateWhenAnimatorEnds = false;
            requestPanelHeightUpdate();
        }
    }

    /**
     * Phase 2: Bounce down.
     */
    private void startUnlockHintAnimationPhase2(final Runnable onAnimationFinished) {
        ValueAnimator animator = createHeightAnimator(getMaxPanelHeight());
        animator.setDuration(450);
        animator.setInterpolator(mBounceInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setAnimator(null);
                onAnimationFinished.run();
                updatePanelExpansionAndVisibility();
            }
        });
        animator.start();
        setAnimator(animator);
    }

    private ValueAnimator createHeightAnimator(float targetHeight) {
        return createHeightAnimator(targetHeight, 0.0f /* performOvershoot */);
    }

    /**
     * Create an animator that can also overshoot
     *
     * @param targetHeight the target height
     * @param overshootAmount the amount of overshoot desired
     */
    private ValueAnimator createHeightAnimator(float targetHeight, float overshootAmount) {
        float startExpansion = mOverExpansion;
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        animator.addUpdateListener(
                animation -> {
                    if (overshootAmount > 0.0f
                            // Also remove the overExpansion when collapsing
                            || (targetHeight == 0.0f && startExpansion != 0)) {
                        final float expansion = MathUtils.lerp(
                                startExpansion,
                                mPanelFlingOvershootAmount * overshootAmount,
                                Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                        animator.getAnimatedFraction()));
                        setOverExpansionInternal(expansion, false /* isFromGesture */);
                    }
                    setExpandedHeightInternal((float) animation.getAnimatedValue());
                });
        return animator;
    }

    /** Update the visibility of {@link PanelView} if necessary. */
    public void updateVisibility() {
        mView.setVisibility(shouldPanelBeVisible() ? VISIBLE : INVISIBLE);
    }

    /** Returns true if {@link PanelView} should be visible. */
    abstract boolean shouldPanelBeVisible();

    /**
     * Updates the panel expansion and {@link PanelView} visibility if necessary.
     *
     * TODO(b/200063118): Could public calls to this method be replaced with calls to
     *   {@link #updateVisibility()}? That would allow us to make this method private.
     */
    public void updatePanelExpansionAndVisibility() {
        mPanelExpansionStateManager.onPanelExpansionChanged(
                mExpandedFraction, isExpanded(), mTracking);
        updateVisibility();
    }

    public boolean isExpanded() {
        return mExpandedFraction > 0f
                || mInstantExpanding
                || isPanelVisibleBecauseOfHeadsUp()
                || mTracking
                || mHeightAnimator != null
                && !mIsSpringBackAnimation;
    }

    protected abstract boolean isPanelVisibleBecauseOfHeadsUp();

    /**
     * Gets called when the user performs a click anywhere in the empty area of the panel.
     *
     * @return whether the panel will be expanded after the action performed by this method
     */
    protected boolean onEmptySpaceClick(float x) {
        if (mHintAnimationRunning) {
            return true;
        }
        return onMiddleClicked();
    }

    protected abstract boolean onMiddleClicked();

    protected abstract boolean isDozing();

    public void dump(PrintWriter pw, String[] args) {
        pw.println(String.format("[PanelView(%s): expandedHeight=%f maxPanelHeight=%d closing=%s"
                        + " tracking=%s timeAnim=%s%s "
                        + "touchDisabled=%s" + "]",
                this.getClass().getSimpleName(), getExpandedHeight(), getMaxPanelHeight(),
                mClosing ? "T" : "f", mTracking ? "T" : "f", mHeightAnimator,
                ((mHeightAnimator != null && mHeightAnimator.isStarted()) ? " (started)" : ""),
                mTouchDisabled ? "T" : "f"));
    }

    public abstract void resetViews(boolean animate);

    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public void setIsLaunchAnimationRunning(boolean running) {
        mIsLaunchAnimationRunning = running;
    }

    protected void setIsClosing(boolean isClosing) {
        mClosing = isClosing;
    }

    protected boolean isClosing() {
        return mClosing;
    }

    public void collapseWithDuration(int animationDuration) {
        mFixedDuration = animationDuration;
        collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        mFixedDuration = NO_FIXED_DURATION;
    }

    public ViewGroup getView() {
        // TODO: remove this method, or at least reduce references to it.
        return mView;
    }

    public OnLayoutChangeListener createLayoutChangeListener() {
        return new OnLayoutChangeListener();
    }

    protected abstract TouchHandler createTouchHandler();

    protected OnConfigurationChangedListener createOnConfigurationChangedListener() {
        return new OnConfigurationChangedListener();
    }

    public class TouchHandler implements View.OnTouchListener {

        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (mInstantExpanding || !mNotificationsDragEnabled || mTouchDisabled || (mMotionAborted
                    && event.getActionMasked() != MotionEvent.ACTION_DOWN)) {
                return false;
            }

            /*
             * If the user drags anywhere inside the panel we intercept it if the movement is
             * upwards. This allows closing the shade from anywhere inside the panel.
             *
             * We only do this if the current content is scrolled to the bottom,
             * i.e canCollapsePanelOnTouch() is true and therefore there is no conflicting scrolling
             * gesture
             * possible.
             */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            boolean canCollapsePanel = canCollapsePanelOnTouch();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mCentralSurfaces.userActivity();
                    mAnimatingOnDown = mHeightAnimator != null && !mIsSpringBackAnimation;
                    mMinExpandHeight = 0.0f;
                    mDownTime = SystemClock.uptimeMillis();
                    if (mAnimatingOnDown && mClosing && !mHintAnimationRunning) {
                        cancelHeightAnimator();
                        mTouchSlopExceeded = true;
                        return true;
                    }
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mTouchStartedInEmptyArea = !isInContentBounds(x, y);
                    mTouchSlopExceeded = mTouchSlopExceededBeforeDown;
                    mMotionAborted = false;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mCollapsedAndHeadsUpOnDown = false;
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mTouchAboveFalsingThreshold = false;
                    addMovement(event);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        mTrackingPointer = event.getPointerId(newIndex);
                        mInitialTouchX = event.getX(newIndex);
                        mInitialTouchY = event.getY(newIndex);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        mVelocityTracker.clear();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float h = y - mInitialTouchY;
                    addMovement(event);
                    final boolean openShadeWithoutHun =
                            mPanelClosedOnDown && !mCollapsedAndHeadsUpOnDown;
                    if (canCollapsePanel || mTouchStartedInEmptyArea || mAnimatingOnDown
                            || openShadeWithoutHun) {
                        float hAbs = Math.abs(h);
                        float touchSlop = getTouchSlop(event);
                        if ((h < -touchSlop
                                || ((openShadeWithoutHun || mAnimatingOnDown) && hAbs > touchSlop))
                                && hAbs > Math.abs(x - mInitialTouchX)) {
                            cancelHeightAnimator();
                            startExpandMotion(x, y, true /* startTracking */, mExpandedHeight);
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mVelocityTracker.clear();
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mInstantExpanding || (mTouchDisabled
                    && event.getActionMasked() != MotionEvent.ACTION_CANCEL) || (mMotionAborted
                    && event.getActionMasked() != MotionEvent.ACTION_DOWN)) {
                return false;
            }

            // If dragging should not expand the notifications shade, then return false.
            if (!mNotificationsDragEnabled) {
                if (mTracking) {
                    // Turn off tracking if it's on or the shade can get stuck in the down position.
                    onTrackingStopped(true /* expand */);
                }
                return false;
            }

            // On expanding, single mouse click expands the panel instead of dragging.
            if (isFullyCollapsed() && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    expand(true);
                }
                return true;
            }

            /*
             * We capture touch events here and update the expand height here in case according to
             * the users fingers. This also handles multi-touch.
             *
             * Flinging is also enabled in order to open or close the shade.
             */

            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mGestureWaitForTouchSlop = shouldGestureWaitForTouchSlop();
                mIgnoreXTouchSlop = isFullyCollapsed() || shouldGestureIgnoreXTouchSlop(x, y);
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                    mMinExpandHeight = 0.0f;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mMotionAborted = false;
                    mDownTime = SystemClock.uptimeMillis();
                    mTouchAboveFalsingThreshold = false;
                    mCollapsedAndHeadsUpOnDown =
                            isFullyCollapsed() && mHeadsUpManager.hasPinnedHeadsUp();
                    addMovement(event);
                    boolean regularHeightAnimationRunning = mHeightAnimator != null
                            && !mHintAnimationRunning && !mIsSpringBackAnimation;
                    if (!mGestureWaitForTouchSlop || regularHeightAnimationRunning) {
                        mTouchSlopExceeded = regularHeightAnimationRunning
                                        || mTouchSlopExceededBeforeDown;
                        cancelHeightAnimator();
                        onTrackingStarted();
                    }
                    if (isFullyCollapsed() && !mHeadsUpManager.hasPinnedHeadsUp()
                            && !mCentralSurfaces.isBouncerShowing()) {
                        startOpening(event);
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        final float newY = event.getY(newIndex);
                        final float newX = event.getX(newIndex);
                        mTrackingPointer = event.getPointerId(newIndex);
                        mHandlingPointerUp = true;
                        startExpandMotion(newX, newY, true /* startTracking */, mExpandedHeight);
                        mHandlingPointerUp = false;
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        endMotionEvent(event, x, y, true /* forceCancel */);
                        return false;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    addMovement(event);
                    float h = y - mInitialTouchY;

                    // If the panel was collapsed when touching, we only need to check for the
                    // y-component of the gesture, as we have no conflicting horizontal gesture.
                    if (Math.abs(h) > getTouchSlop(event)
                            && (Math.abs(h) > Math.abs(x - mInitialTouchX)
                            || mIgnoreXTouchSlop)) {
                        mTouchSlopExceeded = true;
                        if (mGestureWaitForTouchSlop && !mTracking && !mCollapsedAndHeadsUpOnDown) {
                            if (mInitialOffsetOnTouch != 0f) {
                                startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                                h = 0;
                            }
                            cancelHeightAnimator();
                            onTrackingStarted();
                        }
                    }
                    float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                    newHeight = Math.max(newHeight, mMinExpandHeight);
                    if (-h >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                        mUpwardsWhenThresholdReached = isDirectionUpwards(x, y);
                    }
                    if ((!mGestureWaitForTouchSlop || mTracking) && !isTrackingBlocked()) {
                        // Count h==0 as part of swipe-up,
                        // otherwise {@link NotificationStackScrollLayout}
                        // wrongly enables stack height updates at the start of lockscreen swipe-up
                        mAmbientState.setSwipingUp(h <= 0);
                        setExpandedHeightInternal(newHeight);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    addMovement(event);
                    endMotionEvent(event, x, y, false /* forceCancel */);
                    // mHeightAnimator is null, there is no remaining frame, ends instrumenting.
                    if (mHeightAnimator == null) {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            endJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
                        } else {
                            cancelJankMonitoring(CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
                        }
                    }
                    break;
            }
            return !mGestureWaitForTouchSlop || mTracking;
        }
    }

    public class OnLayoutChangeListener implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            requestPanelHeightUpdate();
            mHasLayoutedSinceDown = true;
            if (mUpdateFlingOnLayout) {
                abortAnimations();
                fling(mUpdateFlingVelocity, true /* expands */);
                mUpdateFlingOnLayout = false;
            }
        }
    }

    public class OnConfigurationChangedListener implements
            PanelView.OnConfigurationChangedListener {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            loadDimens();
        }
    }

    private void beginJankMonitoring(int cuj) {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withView(cuj, mView)
                        .setTag(isFullyCollapsed() ? "Expand" : "Collapse");
        mInteractionJankMonitor.begin(builder);
    }

    private void endJankMonitoring(int cuj) {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.getInstance().end(cuj);
    }

    private void cancelJankMonitoring(int cuj) {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.getInstance().cancel(cuj);
    }

    protected float getExpansionFraction() {
        return mExpandedFraction;
    }

    protected PanelExpansionStateManager getPanelExpansionStateManager() {
        return mPanelExpansionStateManager;
    }
}
