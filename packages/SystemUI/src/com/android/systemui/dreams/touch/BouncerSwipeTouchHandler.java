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

import android.animation.ValueAnimator;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.StatusBar;
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
    private final StatusBar mStatusBar;

    private VelocityTracker mVelocityTracker;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private final FlingAnimationUtils mFlingAnimationUtilsClosing;

    private Boolean mCapture;

    private TouchSession mTouchSession;

    private ValueAnimatorCreator mValueAnimatorCreator;

    private VelocityTrackerFactory mVelocityTrackerFactory;

    private final GestureDetector.OnGestureListener mOnGestureListener =
            new  GestureDetector.SimpleOnGestureListener() {
                boolean mTrack;
                boolean mBouncerPresent;

                @Override
                public boolean onDown(MotionEvent e) {
                    // We only consider gestures that originate from the lower portion of the
                    // screen.
                    final float displayHeight = mStatusBar.getDisplayHeight();

                    mBouncerPresent = mStatusBar.isBouncerShowing();

                    // The target zone is either at the top or bottom of the screen, dependent on
                    // whether the bouncer is present.
                    final float zonePercentage =
                            Math.abs(e.getY() - (mBouncerPresent ? 0 : displayHeight))
                                    / displayHeight;

                    mTrack =  zonePercentage < mBouncerZoneScreenPercentage;

                    // Never capture onDown. While this might lead to some false positive touches
                    // being sent to other windows/layers, this is necessary to make sure the
                    // proper touch event sequence is received by others in the event we do not
                    // consume the sequence here.
                    return false;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                        float distanceY) {
                    // Do not handle scroll gestures if not tracking touch events.
                    if (!mTrack) {
                        return false;
                    }

                    if (mCapture == null) {
                        // If the user scrolling favors a vertical direction, begin capturing
                        // scrolls.
                        mCapture = Math.abs(distanceY) > Math.abs(distanceX);

                        if (mCapture) {
                            // Since the user is dragging the bouncer up, set scrimmed to false.
                            mStatusBarKeyguardViewManager.showBouncer(false);
                        }
                    }

                    if (!mCapture) {
                        return false;
                    }

                    // For consistency, we adopt the expansion definition found in the
                    // PanelViewController. In this case, expansion refers to the view above the
                    // bouncer. As that view's expansion shrinks, the bouncer appears. The bouncer
                    // is fully hidden at full expansion (1) and fully visible when fully collapsed
                    // (0).
                    final float screenTravelPercentage =
                            Math.abs((e1.getY() - e2.getY()) / mStatusBar.getDisplayHeight());
                    setPanelExpansion(
                            mBouncerPresent ? screenTravelPercentage : 1 - screenTravelPercentage);

                    return true;
                }
            };

    private void setPanelExpansion(float expansion) {
        mCurrentExpansion = expansion;
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(mCurrentExpansion, false, true);
    }

    @Inject
    public BouncerSwipeTouchHandler(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBar statusBar,
            NotificationShadeWindowController notificationShadeWindowController,
            ValueAnimatorCreator valueAnimatorCreator,
            VelocityTrackerFactory velocityTrackerFactory,
            @Named(SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING)
                    FlingAnimationUtils flingAnimationUtils,
            @Named(SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING)
                    FlingAnimationUtils flingAnimationUtilsClosing,
            @Named(SWIPE_TO_BOUNCER_START_REGION) float swipeRegionPercentage) {
        mStatusBar = statusBar;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mBouncerZoneScreenPercentage = swipeRegionPercentage;
        mFlingAnimationUtils = flingAnimationUtils;
        mFlingAnimationUtilsClosing = flingAnimationUtilsClosing;
        mValueAnimatorCreator = valueAnimatorCreator;
        mVelocityTrackerFactory = velocityTrackerFactory;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        mVelocityTracker = mVelocityTrackerFactory.obtain();
        mTouchSession = session;
        mVelocityTracker.clear();
        mNotificationShadeWindowController.setForcePluginOpen(true, this);
        session.registerGestureListener(mOnGestureListener);
        session.registerInputListener(ev -> onMotionEvent(ev));

    }

    @Override
    public void onSessionEnd(TouchSession session) {
        mVelocityTracker.recycle();
        mCapture = null;
        mNotificationShadeWindowController.setForcePluginOpen(false, this);
    }

    private void onMotionEvent(InputEvent event) {
        if (!(event instanceof MotionEvent)) {
            Log.e(TAG, "non MotionEvent received:" + event);
            return;
        }

        final MotionEvent motionEvent = (MotionEvent) event;

        switch(motionEvent.getAction()) {
            case MotionEvent.ACTION_UP:
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
                flingToExpansion(verticalVelocity, expansion);

                if (expansion == KeyguardBouncer.EXPANSION_HIDDEN) {
                    mStatusBarKeyguardViewManager.reset(false);
                }
                mTouchSession.pop();
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
        return animator;
    }

    protected boolean flingRevealsOverlay(float velocity, float velocityVector) {
        // Fully expand if the user has expanded the bouncer less than halfway or final velocity was
        // positive, indicating an downward direction.
        if (Math.abs(velocityVector) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return mCurrentExpansion > FLING_PERCENTAGE_THRESHOLD;
        } else {
            return velocity > 0;
        }
    }

    protected void flingToExpansion(float velocity, float expansion) {
        final float viewHeight = mStatusBar.getDisplayHeight();
        final float currentHeight = viewHeight * mCurrentExpansion;
        final float targetHeight = viewHeight * expansion;

        final ValueAnimator animator = createExpansionAnimator(expansion);
        if (expansion == KeyguardBouncer.EXPANSION_HIDDEN) {
            // The animation utils deal in pixel units, rather than expansion height.
            mFlingAnimationUtils.apply(animator, currentHeight, targetHeight, velocity, viewHeight);
        } else {
            mFlingAnimationUtilsClosing.apply(
                    animator, mCurrentExpansion, currentHeight, targetHeight, viewHeight);
        }

        animator.start();
    }
}
