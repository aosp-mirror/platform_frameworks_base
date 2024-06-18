/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Flags;
import com.android.systemui.ambient.touch.dagger.BouncerSwipeModule;
import com.android.systemui.ambient.touch.scrim.ScrimController;
import com.android.systemui.ambient.touch.scrim.ScrimManager;
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.wm.shell.animation.FlingAnimationUtils;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Monitor for tracking touches on the DreamOverlay to bring up the bouncer.
 */
public class BouncerSwipeTouchHandler implements TouchHandler {
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
    private final LockPatternUtils mLockPatternUtils;
    private final UserTracker mUserTracker;
    private final float mBouncerZoneScreenPercentage;
    private final float mMinBouncerZoneScreenPercentage;

    private final ScrimManager mScrimManager;
    private ScrimController mCurrentScrimController;
    private float mCurrentExpansion;
    private final Optional<CentralSurfaces> mCentralSurfaces;

    private VelocityTracker mVelocityTracker;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private final FlingAnimationUtils mFlingAnimationUtilsClosing;

    private Boolean mCapture;
    private Boolean mExpanded;

    private TouchSession mTouchSession;

    private final ValueAnimatorCreator mValueAnimatorCreator;

    private final VelocityTrackerFactory mVelocityTrackerFactory;

    private final UiEventLogger mUiEventLogger;

    private final ScrimManager.Callback mScrimManagerCallback = new ScrimManager.Callback() {
        @Override
        public void onScrimControllerChanged(ScrimController controller) {
            if (mCurrentScrimController != null) {
                mCurrentScrimController.reset();
            }

            mCurrentScrimController = controller;
        }
    };

    private final GestureDetector.OnGestureListener mOnGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX,
                        float distanceY) {
                    if (mCapture == null) {
                        if (Flags.dreamOverlayBouncerSwipeDirectionFiltering()) {
                            mCapture = Math.abs(distanceY) > Math.abs(distanceX)
                                    && distanceY > 0;
                        } else {
                            // If the user scrolling favors a vertical direction, begin capturing
                            // scrolls.
                            mCapture = Math.abs(distanceY) > Math.abs(distanceX);
                        }
                        if (mCapture) {
                            // reset expanding
                            mExpanded = false;
                            // Since the user is dragging the bouncer up, set scrimmed to false.
                            mCurrentScrimController.show();
                        }
                    }

                    if (!mCapture) {
                        return false;
                    }

                    // Don't set expansion for downward scroll.
                    if (e1.getY() < e2.getY()) {
                        return true;
                    }

                    if (!mCentralSurfaces.isPresent()) {
                        return true;
                    }

                    // If scrolling up and keyguard is not locked, dismiss the dream since there's
                    // no bouncer to show.
                    if (e1.getY() > e2.getY()
                            && !mLockPatternUtils.isSecure(mUserTracker.getUserId())) {
                        mCentralSurfaces.get().awakenDreams();
                        return true;
                    }

                    // For consistency, we adopt the expansion definition found in the
                    // PanelViewController. In this case, expansion refers to the view above the
                    // bouncer. As that view's expansion shrinks, the bouncer appears. The bouncer
                    // is fully hidden at full expansion (1) and fully visible when fully collapsed
                    // (0).
                    final float dragDownAmount = e2.getY() - e1.getY();
                    final float screenTravelPercentage = Math.abs(e1.getY() - e2.getY())
                            / mTouchSession.getBounds().height();
                    setPanelExpansion(1 - screenTravelPercentage);
                    return true;
                }
            };

    private void setPanelExpansion(float expansion) {
        mCurrentExpansion = expansion;
        ShadeExpansionChangeEvent event =
                new ShadeExpansionChangeEvent(
                        /* fraction= */ mCurrentExpansion,
                        /* expanded= */ mExpanded,
                        /* tracking= */ true);
        mCurrentScrimController.expand(event);
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
            ScrimManager scrimManager,
            Optional<CentralSurfaces> centralSurfaces,
            NotificationShadeWindowController notificationShadeWindowController,
            ValueAnimatorCreator valueAnimatorCreator,
            VelocityTrackerFactory velocityTrackerFactory,
            LockPatternUtils lockPatternUtils,
            UserTracker userTracker,
            @Named(BouncerSwipeModule.SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_OPENING)
            FlingAnimationUtils flingAnimationUtils,
            @Named(BouncerSwipeModule.SWIPE_TO_BOUNCER_FLING_ANIMATION_UTILS_CLOSING)
            FlingAnimationUtils flingAnimationUtilsClosing,
            @Named(BouncerSwipeModule.SWIPE_TO_BOUNCER_START_REGION) float swipeRegionPercentage,
            @Named(BouncerSwipeModule.MIN_BOUNCER_ZONE_SCREEN_PERCENTAGE) float minRegionPercentage,
            UiEventLogger uiEventLogger) {
        mCentralSurfaces = centralSurfaces;
        mScrimManager = scrimManager;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mLockPatternUtils = lockPatternUtils;
        mUserTracker = userTracker;
        mBouncerZoneScreenPercentage = swipeRegionPercentage;
        mMinBouncerZoneScreenPercentage = minRegionPercentage;
        mFlingAnimationUtils = flingAnimationUtils;
        mFlingAnimationUtilsClosing = flingAnimationUtilsClosing;
        mValueAnimatorCreator = valueAnimatorCreator;
        mVelocityTrackerFactory = velocityTrackerFactory;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    public void getTouchInitiationRegion(Rect bounds, Region region, Rect exclusionRect) {
        final int width = bounds.width();
        final int height = bounds.height();
        final int minAllowableBottom = Math.round(height * (1 - mMinBouncerZoneScreenPercentage));

        final Rect normalRegion = new Rect(0,
                Math.round(height * (1 - mBouncerZoneScreenPercentage)),
                width, height);

        if (exclusionRect != null) {
            int lowestBottom = Math.min(Math.max(0, exclusionRect.bottom), minAllowableBottom);
            normalRegion.top = Math.max(normalRegion.top, lowestBottom);
        }
        region.union(normalRegion);
    }


    @Override
    public void onSessionStart(TouchSession session) {
        mVelocityTracker = mVelocityTrackerFactory.obtain();
        mTouchSession = session;
        mVelocityTracker.clear();

        if (!Flags.communalBouncerDoNotModifyPluginOpen()) {
            mNotificationShadeWindowController.setForcePluginOpen(true, this);
        }

        mScrimManager.addCallback(mScrimManagerCallback);
        mCurrentScrimController = mScrimManager.getCurrentController();

        session.registerCallback(() -> {
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
            mScrimManager.removeCallback(mScrimManagerCallback);
            mCapture = null;
            mTouchSession = null;

            if (!Flags.communalBouncerDoNotModifyPluginOpen()) {
                mNotificationShadeWindowController.setForcePluginOpen(false, this);
            }
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

                mExpanded = !flingRevealsOverlay(verticalVelocity, velocityVector);
                final float expansion = mExpanded
                        ? KeyguardBouncerConstants.EXPANSION_VISIBLE
                        : KeyguardBouncerConstants.EXPANSION_HIDDEN;

                // Log the swiping up to show Bouncer event.
                if (expansion == KeyguardBouncerConstants.EXPANSION_VISIBLE) {
                    mUiEventLogger.log(DreamEvent.DREAM_SWIPED);
                }

                flingToExpansion(verticalVelocity, expansion);
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
                    float expansionFraction = (float) animation.getAnimatedValue();
                    setPanelExpansion(expansionFraction);
                });
        if (targetExpansion == KeyguardBouncerConstants.EXPANSION_VISIBLE) {
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
        if (!mCentralSurfaces.isPresent()) {
            return;
        }

        // Don't set expansion if the user doesn't have a pin/password set.
        if (!mLockPatternUtils.isSecure(mUserTracker.getUserId())) {
            return;
        }

        // The animation utils deal in pixel units, rather than expansion height.
        final float viewHeight = mTouchSession.getBounds().height();
        final float currentHeight = viewHeight * mCurrentExpansion;
        final float targetHeight = viewHeight * expansion;
        final ValueAnimator animator = createExpansionAnimator(expansion);
        if (expansion == KeyguardBouncerConstants.EXPANSION_HIDDEN) {
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
