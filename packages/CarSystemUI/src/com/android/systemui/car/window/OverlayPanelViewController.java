/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.window;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.CallSuper;

import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.FlingAnimationUtils;

/**
 * The {@link OverlayPanelViewController} provides additional dragging animation capabilities to
 * {@link OverlayViewController}.
 */
public abstract class OverlayPanelViewController extends OverlayViewController {

    private static final boolean DEBUG = true;
    private static final String TAG = "OverlayPanelViewController";

    // used to calculate how fast to open or close the window
    protected static final float DEFAULT_FLING_VELOCITY = 0;
    // max time a fling animation takes
    protected static final float FLING_ANIMATION_MAX_TIME = 0.5f;
    // acceleration rate for the fling animation
    protected static final float FLING_SPEED_UP_FACTOR = 0.6f;

    protected static final int SWIPE_DOWN_MIN_DISTANCE = 25;
    protected static final int SWIPE_MAX_OFF_PATH = 75;
    protected static final int SWIPE_THRESHOLD_VELOCITY = 200;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final View.OnTouchListener mDragOpenTouchListener;
    private final View.OnTouchListener mDragCloseTouchListener;

    private final int mSettleClosePercentage;
    private int mPercentageFromBottom;

    private boolean mPanelVisible;
    private boolean mPanelExpanded;

    private float mOpeningVelocity = DEFAULT_FLING_VELOCITY;
    private float mClosingVelocity = DEFAULT_FLING_VELOCITY;

    private boolean mIsAnimating;
    private boolean mIsTracking;

    public OverlayPanelViewController(
            Context context,
            @Main Resources resources,
            int stubId,
            OverlayViewGlobalStateController overlayViewGlobalStateController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            CarDeviceProvisionedController carDeviceProvisionedController
    ) {
        super(stubId, overlayViewGlobalStateController);

        mFlingAnimationUtils = flingAnimationUtilsBuilder
                .setMaxLengthSeconds(FLING_ANIMATION_MAX_TIME)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mCarDeviceProvisionedController = carDeviceProvisionedController;

        mSettleClosePercentage = resources.getInteger(
                R.integer.notification_settle_close_percentage);

        // Attached to the top navigation bar (i.e. status bar) to detect pull down of the
        // notification shade.
        GestureDetector openGestureDetector = new GestureDetector(context,
                new OpenGestureListener() {
                    @Override
                    protected void open() {
                        animateExpandPanel();
                    }
                });

        // Attached to the NavBars to close the notification shade
        GestureDetector navBarCloseNotificationGestureDetector = new GestureDetector(context,
                new SystemBarCloseGestureListener() {
                    @Override
                    protected void close() {
                        if (isPanelExpanded()) {
                            animateCollapsePanel();
                        }
                    }
                });

        mDragOpenTouchListener = (v, event) -> {
            if (!mCarDeviceProvisionedController.isCurrentUserFullySetup()) {
                return true;
            }
            if (!isInflated()) {
                getOverlayViewGlobalStateController().inflateView(this);
            }

            boolean consumed = openGestureDetector.onTouchEvent(event);
            if (consumed) {
                return true;
            }
            maybeCompleteAnimation(event);
            return true;
        };

        mDragCloseTouchListener = (v, event) -> {
            if (!isInflated()) {
                return true;
            }
            boolean consumed = navBarCloseNotificationGestureDetector.onTouchEvent(event);
            if (consumed) {
                return true;
            }
            maybeCompleteAnimation(event);
            return true;
        };
    }

    /** Toggles the visibility of the panel. */
    public void toggle() {
        if (!isInflated()) {
            getOverlayViewGlobalStateController().inflateView(this);
        }
        if (isPanelExpanded()) {
            animateCollapsePanel();
        } else {
            animateExpandPanel();
        }
    }

    /* ***************************************************************************************** *
     * Panel Animation
     * ***************************************************************************************** */

    /** Animates the closing of the panel. */
    protected void animateCollapsePanel() {
        if (!shouldAnimateCollapsePanel()) {
            return;
        }

        if (!isPanelExpanded() || !isPanelVisible()) {
            return;
        }

        onAnimateCollapsePanel();
        getOverlayViewGlobalStateController().setWindowFocusable(false);
        animatePanel(mClosingVelocity, /* isClosing= */ true);
    }

    /** Determines whether {@link #animateCollapsePanel()} should collapse the panel. */
    protected abstract boolean shouldAnimateCollapsePanel();

    /** Called when the panel is beginning to collapse. */
    protected abstract void onAnimateCollapsePanel();

    /** Animates the expansion of the panel. */
    protected void animateExpandPanel() {
        if (!shouldAnimateExpandPanel()) {
            return;
        }

        if (!mCarDeviceProvisionedController.isCurrentUserFullySetup()) {
            return;
        }

        onAnimateExpandPanel();
        setPanelVisible(true);
        animatePanel(mOpeningVelocity, /* isClosing= */ false);

        setPanelExpanded(true);
    }

    /** Determines whether {@link #animateExpandPanel()}} should expand the panel. */
    protected abstract boolean shouldAnimateExpandPanel();

    /** Called when the panel is beginning to expand. */
    protected abstract void onAnimateExpandPanel();

    /**
     * Depending on certain conditions, determines whether to fully expand or collapse the panel.
     */
    protected void maybeCompleteAnimation(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && isPanelVisible()) {
            if (mSettleClosePercentage < mPercentageFromBottom) {
                animatePanel(DEFAULT_FLING_VELOCITY, false);
            } else {
                animatePanel(DEFAULT_FLING_VELOCITY, true);
            }
        }
    }

    /**
     * Animates the panel from one position to other. This is used to either open or
     * close the panel completely with a velocity. If the animation is to close the
     * panel this method also makes the view invisible after animation ends.
     */
    protected void animatePanel(float velocity, boolean isClosing) {
        float to = 0;
        if (!isClosing) {
            to = getLayout().getHeight();
        }

        Rect rect = getLayout().getClipBounds();
        if (rect != null && rect.bottom != to) {
            float from = rect.bottom;
            animate(from, to, velocity, isClosing);
            return;
        }

        // We will only be here if the shade is being opened programmatically or via button when
        // height of the layout was not calculated.
        ViewTreeObserver notificationTreeObserver = getLayout().getViewTreeObserver();
        notificationTreeObserver.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ViewTreeObserver obs = getLayout().getViewTreeObserver();
                        obs.removeOnGlobalLayoutListener(this);
                        float to = getLayout().getHeight();
                        animate(/* from= */ 0, to, velocity, isClosing);
                    }
                });
    }

    private void animate(float from, float to, float velocity, boolean isClosing) {
        if (mIsAnimating) {
            return;
        }
        mIsAnimating = true;
        mIsTracking = true;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.addUpdateListener(
                animation -> {
                    float animatedValue = (Float) animation.getAnimatedValue();
                    setViewClipBounds((int) animatedValue);
                });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mIsAnimating = false;
                mIsTracking = false;
                mOpeningVelocity = DEFAULT_FLING_VELOCITY;
                mClosingVelocity = DEFAULT_FLING_VELOCITY;
                if (isClosing) {
                    setPanelVisible(false);
                    getLayout().setClipBounds(null);
                    onCollapseAnimationEnd();
                    setPanelExpanded(false);
                } else {
                    onExpandAnimationEnd();
                    setPanelExpanded(true);
                }
            }
        });
        getFlingAnimationUtils().apply(animator, from, to, Math.abs(velocity));
        animator.start();
    }

    /**
     * Called in {@link Animator.AnimatorListener#onAnimationEnd(Animator)} when the panel is
     * closing.
     */
    protected abstract void onCollapseAnimationEnd();

    /**
     * Called in {@link Animator.AnimatorListener#onAnimationEnd(Animator)} when the panel is
     * opening.
     */
    protected abstract void onExpandAnimationEnd();

    /* ***************************************************************************************** *
     * Panel Visibility
     * ***************************************************************************************** */

    /** Set the panel view to be visible. */
    protected final void setPanelVisible(boolean visible) {
        mPanelVisible = visible;
        onPanelVisible(visible);
    }

    /** Returns {@code true} if panel is visible. */
    public final boolean isPanelVisible() {
        return mPanelVisible;
    }

    /** Business logic run when panel visibility is set. */
    @CallSuper
    protected void onPanelVisible(boolean visible) {
        if (DEBUG) {
            Log.e(TAG, "onPanelVisible: " + visible);
        }

        if (visible && !getOverlayViewGlobalStateController().isWindowVisible()) {
            getOverlayViewGlobalStateController().setWindowVisible(true);
        }
        if (!visible && getOverlayViewGlobalStateController().isWindowVisible()) {
            getOverlayViewGlobalStateController().setWindowVisible(false);
        }
        getLayout().setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        getOverlayViewGlobalStateController().setWindowFocusable(visible);
    }

    /* ***************************************************************************************** *
     * Panel Expansion
     * ***************************************************************************************** */

    /**
     * Set the panel state to expanded. This will expand or collapse the overlay window if
     * necessary.
     */
    protected final void setPanelExpanded(boolean expand) {
        mPanelExpanded = expand;
        onPanelExpanded(expand);
    }

    /** Returns {@code true} if panel is expanded. */
    public final boolean isPanelExpanded() {
        return mPanelExpanded;
    }

    @CallSuper
    protected void onPanelExpanded(boolean expand) {
        if (DEBUG) {
            Log.e(TAG, "onPanelExpanded: " + expand);
        }
    }

    /* ***************************************************************************************** *
     * Misc
     * ***************************************************************************************** */

    protected void calculatePercentageFromBottom(float height) {
        if (getLayout().getHeight() > 0) {
            mPercentageFromBottom = (int) Math.abs(
                    height / getLayout().getHeight() * 100);
        }
    }

    protected void setViewClipBounds(int height) {
        if (height > getLayout().getHeight()) {
            height = getLayout().getHeight();
        }
        Rect clipBounds = new Rect();
        clipBounds.set(0, 0, getLayout().getWidth(), height);
        getLayout().setClipBounds(clipBounds);
        onScroll(height);
    }

    /** Called while scrolling. */
    protected abstract void onScroll(int height);

    /* ***************************************************************************************** *
     * Getters
     * ***************************************************************************************** */

    /** Returns the open touch listener. */
    public final View.OnTouchListener getDragOpenTouchListener() {
        return mDragOpenTouchListener;
    }

    /** Returns the close touch listener. */
    public final View.OnTouchListener getDragCloseTouchListener() {
        return mDragCloseTouchListener;
    }

    /** Gets the fling animation utils used for animating this panel. */
    protected final FlingAnimationUtils getFlingAnimationUtils() {
        return mFlingAnimationUtils;
    }

    /** Returns {@code true} if the panel is currently tracking. */
    protected final boolean isTracking() {
        return mIsTracking;
    }

    /** Returns {@code true} if the panel is currently animating. */
    protected final boolean isAnimating() {
        return mIsAnimating;
    }

    /** Returns the percentage of the panel that is open from the bottom. */
    protected final int getPercentageFromBottom() {
        return mPercentageFromBottom;
    }

    /** Returns the percentage at which we've determined whether to open or close the panel. */
    protected final int getSettleClosePercentage() {
        return mSettleClosePercentage;
    }

    /* ***************************************************************************************** *
     * Gesture Listeners
     * ***************************************************************************************** */

    /** Called when the user is beginning to scroll down the panel. */
    protected abstract void onOpenScrollStart();

    /**
     * Only responsible for open hooks. Since once the panel opens it covers all elements
     * there is no need to merge with close.
     */
    protected abstract class OpenGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {

            if (!isPanelVisible()) {
                onOpenScrollStart();
            }
            setPanelVisible(true);

            // clips the view for the notification shade when the user scrolls to open.
            setViewClipBounds((int) event2.getRawY());

            // Initially the scroll starts with height being zero. This checks protects from divide
            // by zero error.
            calculatePercentageFromBottom(event2.getRawY());

            mIsTracking = true;
            return true;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            if (velocityY > SWIPE_THRESHOLD_VELOCITY) {
                mOpeningVelocity = velocityY;
                open();
                return true;
            }
            animatePanel(DEFAULT_FLING_VELOCITY, true);

            return false;
        }

        protected abstract void open();
    }

    /** Determines whether the scroll event should allow closing of the panel. */
    protected abstract boolean shouldAllowClosingScroll();

    protected abstract class CloseGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapUp(MotionEvent motionEvent) {
            if (isPanelExpanded()) {
                animatePanel(DEFAULT_FLING_VELOCITY, true);
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            // should not clip while scroll to the bottom of the list.
            if (!shouldAllowClosingScroll()) {
                return false;
            }
            float actualNotificationHeight =
                    getLayout().getHeight() - (event1.getRawY() - event2.getRawY());
            if (actualNotificationHeight > getLayout().getHeight()) {
                actualNotificationHeight = getLayout().getHeight();
            }
            if (getLayout().getHeight() > 0) {
                mPercentageFromBottom = (int) Math.abs(
                        actualNotificationHeight / getLayout().getHeight() * 100);
                boolean isUp = distanceY > 0;

                // This check is to figure out if onScroll was called while swiping the card at
                // bottom of the list. At that time we should not allow notification shade to
                // close. We are also checking for the upwards swipe gesture here because it is
                // possible if a user is closing the notification shade and while swiping starts
                // to open again but does not fling. At that time we should allow the
                // notification shade to close fully or else it would stuck in between.
                if (Math.abs(getLayout().getHeight() - actualNotificationHeight)
                        > SWIPE_DOWN_MIN_DISTANCE && isUp) {
                    setViewClipBounds((int) actualNotificationHeight);
                    mIsTracking = true;
                } else if (!isUp) {
                    setViewClipBounds((int) actualNotificationHeight);
                }
            }
            // if we return true the items in RV won't be scrollable.
            return false;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            // should not fling if the touch does not start when view is at the bottom of the list.
            if (!shouldAllowClosingScroll()) {
                return false;
            }
            if (Math.abs(event1.getX() - event2.getX()) > SWIPE_MAX_OFF_PATH
                    || Math.abs(velocityY) < SWIPE_THRESHOLD_VELOCITY) {
                // swipe was not vertical or was not fast enough
                return false;
            }
            boolean isUp = velocityY < 0;
            if (isUp) {
                close();
                return true;
            } else {
                // we should close the shade
                animatePanel(velocityY, false);
            }
            return false;
        }

        protected abstract void close();
    }

    protected abstract class SystemBarCloseGestureListener extends CloseGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            mClosingVelocity = DEFAULT_FLING_VELOCITY;
            if (isPanelExpanded()) {
                close();
            }
            return super.onSingleTapUp(e);
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            setViewClipBounds((int) event2.getRawY());
            return true;
        }
    }
}
