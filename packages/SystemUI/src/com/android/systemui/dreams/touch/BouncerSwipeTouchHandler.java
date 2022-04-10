/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.android.systemui.dreams.touch.dagger.BouncerSwipeModule.SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING;
import static com.android.systemui.dreams.touch.dagger.BouncerSwipeModule.SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING;
import static com.android.systemui.dreams.touch.dagger.BouncerSwipeModule.SWIPE_TO_BOUNCER_START_REGION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.wm.shell.animation.FlingAnimationUtils;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Monitor for tracking touches on the DreamOverlay to bring up the bouncer.
 */
public class BouncerSwipeTouchHandler implements DreamTouchHandler {
    /**
     * An interface for creating ValueAnimators.
     */
    public interface ValueAnimatorCreator {
        /**
         * Creates {@link ValueAnimator}.
         */
        ValueAnimator create(float start, float finish);
    }

    /**
     * An interface for obtaining VelocityTrackers.
     */
    public interface VelocityTrackerFactory {
        /**
         * Obtains {@link VelocityTracker}.
         */
        VelocityTracker obtain();
    }

    public static final float FLING_PERCENTAGE_THRESHOLD = 0.5f;

    private static final String TAG = "BouncerSwipeTouchHandler";
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final float mBouncerZoneScreenPercentage;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private float mCurrentExpansion;
    private final CentralSurfaces mCentralSurfaces;

    private VelocityTracker mVelocityTracker;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private final FlingAnimationUtils mFlingAnimationUtilsClosing;

    private final DisplayMetrics mDisplayMetrics;

    private Boolean mCapture;

    private boolean mBouncerInitiallyShowing;

    private TouchSession mTouchSession;

    private ValueAnimatorCreator mValueAnimatorCreator;

    private VelocityTrackerFactory mVelocityTrackerFactory;

    private final UiEventLogger mUiEventLogger;

    private final GestureDetector.OnGestureListener mOnGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                        float distanceY) {
                    if (mCapture == null) {
                        // If the user scrolling favors a vertical direction, begin capturing
                        // scrolls.
                        mCapture = Math.abs(distanceY) > Math.abs(distanceX);
                        mBouncerInitiallyShowing = mCentralSurfaces.isBouncerShowing();

                        if (mCapture) {
                            // Since the user is dragging the bouncer up, set scrimmed to false.
                            mStatusBarKeyguardViewManager.showBouncer(false);
                        }
                    }

                    if (!mCapture) {
                        return false;
                    }

                    // Don't set expansion for downward scroll when the bouncer is hidden.
                    if (!mBouncerInitiallyShowing && (e1.getY() < e2.getY())) {
                        return true;
                    }

                    // Don't set expansion for upward scroll when the bouncer is shown.
                    if (mBouncerInitiallyShowing && (e1.getY() > e2.getY())) {
                        return true;
                    }

                    // For consistency, we adopt the expansion definition found in the
                    // PanelViewController. In this case, expansion refers to the view above the
                    // bouncer. As that view's expansion shrinks, the bouncer appears. The bouncer
                    // is fully hidden at full expansion (1) and fully visible when fully collapsed
                    // (0).
                    final float screenTravelPercentage = Math.abs(e1.getY() - e2.getY())
                            / mCentralSurfaces.getDisplayHeight();
                    setPanelExpansion(mBouncerInitiallyShowing
                            ? screenTravelPercentage : 1 - screenTravelPercentage);
                    return true;
                }
            };

    private void setPanelExpansion(float expansion) {
        mCurrentExpansion = expansion;

        mCentralSurfaces.setBouncerShowing(expansion != KeyguardBouncer.EXPANSION_HIDDEN);

        mStatusBarKeyguardViewManager.onPanelExpansionChanged(mCurrentExpansion, false, true);
    }

    @VisibleForTesting
    public enum DreamEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The screensaver has been swiped up.")
        DREAM_SWIPED(988),

        @UiEvent(doc = "The bouncer has become fully visible over dream.")
        DREAM_BOUNCER_FULLY_VISIBLE(1056);

        private final int mId;

        DreamEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    @Inject
    public BouncerSwipeTouchHandler(
            DisplayMetrics displayMetrics,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            CentralSurfaces centralSurfaces,
            NotificationShadeWindowController notificationShadeWindowController,
            ValueAnimatorCreator valueAnimatorCreator,
            VelocityTrackerFactory velocityTrackerFactory,
            @Named(SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING)
                    FlingAnimationUtils flingAnimationUtils,
            @Named(SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING)
                    FlingAnimationUtils flingAnimationUtilsClosing,
            @Named(SWIPE_TO_BOUNCER_START_REGION) float swipeRegionPercentage,
            UiEventLogger uiEventLogger) {
        mDisplayMetrics = displayMetrics;
        mCentralSurfaces = centralSurfaces;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mBouncerZoneScreenPercentage = swipeRegionPercentage;
        mFlingAnimationUtils = flingAnimationUtils;
        mFlingAnimationUtilsClosing = flingAnimationUtilsClosing;
        mValueAnimatorCreator = valueAnimatorCreator;
        mVelocityTrackerFactory = velocityTrackerFactory;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    public void getTouchInitiationRegion(Region region) {
        if (mCentralSurfaces.isBouncerShowing()) {
            region.op(new Rect(0, 0, mDisplayMetrics.widthPixels,
                            Math.round(
                                    mDisplayMetrics.heightPixels * mBouncerZoneScreenPercentage)),
                    Region.Op.UNION);
        } else {
            region.op(new Rect(0,
                            Math.round(
                                    mDisplayMetrics.heightPixels
                                            * (1 - mBouncerZoneScreenPercentage)),
                            mDisplayMetrics.widthPixels,
                            mDisplayMetrics.heightPixels),
                    Region.Op.UNION);
        }
    }

    @Override
    public void onSessionStart(TouchSession session) {
        mVelocityTracker = mVelocityTrackerFactory.obtain();
        mTouchSession = session;
        mVelocityTracker.clear();
        mNotificationShadeWindowController.setForcePluginOpen(true, this);

        session.registerCallback(() -> {
            mVelocityTracker.recycle();
            mCapture = null;
            mNotificationShadeWindowController.setForcePluginOpen(false, this);
        });

        session.registerGestureListener(mOnGestureListener);
        session.registerInputListener(ev -> onMotionEvent(ev));

    }

    private void onMotionEvent(InputEvent event) {
        if (!(event instanceof MotionEvent)) {
            Log.e(TAG, "non MotionEvent received:" + event);
            return;
        }

        final MotionEvent motionEvent = (MotionEvent) event;

        switch (motionEvent.getAction()) {
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mTouchSession.pop();
                // If we are not capturing any input, there is no need to consider animating to
                // finish transition.
                if (mCapture == null || !mCapture) {
                    break;
                }

                // We must capture the resulting velocities as resetMonitor() will clear these
                // values.
                mVelocityTracker.computeCurrentVelocity(1000);
                final float verticalVelocity = mVelocityTracker.getYVelocity();
                final float horizontalVelocity = mVelocityTracker.getXVelocity();

                final float velocityVector =
                        (float) Math.hypot(horizontalVelocity, verticalVelocity);

                final float expansion = flingRevealsOverlay(verticalVelocity, velocityVector)
                        ? KeyguardBouncer.EXPANSION_HIDDEN : KeyguardBouncer.EXPANSION_VISIBLE;

                // Log the swiping up to show Bouncer event.
                if (!mBouncerInitiallyShowing && expansion == KeyguardBouncer.EXPANSION_VISIBLE) {
                    mUiEventLogger.log(DreamEvent.DREAM_SWIPED);
                }

                flingToExpansion(verticalVelocity, expansion);

                if (expansion == KeyguardBouncer.EXPANSION_HIDDEN) {
                    mStatusBarKeyguardViewManager.reset(false);
                }
                break;
            default:
                mVelocityTracker.addMovement(motionEvent);
                break;
        }
    }

    private ValueAnimator createExpansionAnimator(float targetExpansion) {
        final ValueAnimator animator =
                mValueAnimatorCreator.create(mCurrentExpansion, targetExpansion);
        animator.addUpdateListener(
                animation -> {
                    setPanelExpansion((float) animation.getAnimatedValue());
                });
        if (!mBouncerInitiallyShowing && targetExpansion == KeyguardBouncer.EXPANSION_VISIBLE) {
            animator.addListener(
                    new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mUiEventLogger.log(DreamEvent.DREAM_BOUNCER_FULLY_VISIBLE);
                        }
                    });
        }
        return animator;
    }

    protected boolean flingRevealsOverlay(float velocity, float velocityVector) {
        // Fully expand the space above the bouncer, if the user has expanded the bouncer less
        // than halfway or final velocity was positive, indicating a downward direction.
        if (Math.abs(velocityVector) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return mCurrentExpansion > FLING_PERCENTAGE_THRESHOLD;
        } else {
            return velocity > 0;
        }
    }

    protected void flingToExpansion(float velocity, float expansion) {
        // The animation utils deal in pixel units, rather than expansion height.
        final float viewHeight = mCentralSurfaces.getDisplayHeight();
        final float currentHeight = viewHeight * mCurrentExpansion;
        final float targetHeight = viewHeight * expansion;

        final ValueAnimator animator = createExpansionAnimator(expansion);
        if (expansion == KeyguardBouncer.EXPANSION_HIDDEN) {
            // Hides the bouncer, i.e., fully expands the space above the bouncer.
            mFlingAnimationUtilsClosing.apply(animator, currentHeight, targetHeight, velocity,
                    viewHeight);
        } else {
            // Shows the bouncer, i.e., fully collapses the space above the bouncer.
            mFlingAnimationUtils.apply(
                    animator, currentHeight, targetHeight, velocity, viewHeight);
        }

        animator.start();
    }
}
