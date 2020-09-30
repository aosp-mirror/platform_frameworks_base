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
import android.annotation.IntDef;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The {@link OverlayPanelViewController} provides additional dragging animation capabilities to
 * {@link OverlayViewController}.
 */
public abstract class OverlayPanelViewController extends OverlayViewController {

    /** @hide */
    @IntDef(flag = true, prefix = { "OVERLAY_" }, value = {
            OVERLAY_FROM_TOP_BAR,
            OVERLAY_FROM_BOTTOM_BAR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OverlayDirection {}

    /**
     * Indicates that the overlay panel should be opened from the top bar and expanded by dragging
     * towards the bottom bar.
     */
    public static final int OVERLAY_FROM_TOP_BAR = 0;

    /**
     * Indicates that the overlay panel should be opened from the bottom bar and expanded by
     * dragging towards the top bar.
     */
    public static final int OVERLAY_FROM_BOTTOM_BAR = 1;

    private static final boolean DEBUG = false;
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
    private static final int POSITIVE_DIRECTION = 1;
    private static final int NEGATIVE_DIRECTION = -1;

    private final FlingAnimationUtils mFlingAnimationUtils;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final View.OnTouchListener mDragOpenTouchListener;
    private final View.OnTouchListener mDragCloseTouchListener;

    protected int mAnimateDirection = POSITIVE_DIRECTION;

    private final int mSettleClosePercentage;
    private int mPercentageFromEndingEdge;

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

        // Attached to a navigation bar to open the overlay panel
        GestureDetector openGestureDetector = new GestureDetector(context,
                new OpenGestureListener() {
                    @Override
                    protected void open() {
                        animateExpandPanel();
                    }
                });

        // Attached to the other navigation bars to close the overlay panel
        GestureDetector closeGestureDetector = new GestureDetector(context,
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
            boolean consumed = closeGestureDetector.onTouchEvent(event);
            if (consumed) {
                return true;
            }
            maybeCompleteAnimation(event);
            return true;
        };
    }

    /** Sets the overlay panel animation direction along the x or y axis. */
    public void setOverlayDirection(@OverlayDirection int direction) {
        if (direction == OVERLAY_FROM_TOP_BAR) {
            mAnimateDirection = POSITIVE_DIRECTION;
        } else if (direction == OVERLAY_FROM_BOTTOM_BAR) {
            mAnimateDirection = NEGATIVE_DIRECTION;
        } else {
            throw new IllegalArgumentException("Direction not supported");
        }
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

    /** Checks if a {@link MotionEvent} is an action to open the panel.
     * @param e {@link MotionEvent} to check.
     * @return true only if opening action.
     */
    protected boolean isOpeningAction(MotionEvent e) {
        if (mAnimateDirection == POSITIVE_DIRECTION) {
            return e.getActionMasked() == MotionEvent.ACTION_DOWN;
        }

        if (mAnimateDirection == NEGATIVE_DIRECTION) {
            return e.getActionMasked() == MotionEvent.ACTION_UP;
        }

        return false;
    }

    /** Checks if a {@link MotionEvent} is an action to close the panel.
     * @param e {@link MotionEvent} to check.
     * @return true only if closing action.
     */
    protected boolean isClosingAction(MotionEvent e) {
        if (mAnimateDirection == POSITIVE_DIRECTION) {
            return e.getActionMasked() == MotionEvent.ACTION_UP;
        }

        if (mAnimateDirection == NEGATIVE_DIRECTION) {
            return e.getActionMasked() == MotionEvent.ACTION_DOWN;
        }

        return false;
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
        if (isClosingAction(event) && isPanelVisible()) {
            if (mSettleClosePercentage < mPercentageFromEndingEdge) {
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
        float to = getEndPosition(isClosing);

        Rect rect = getLayout().getClipBounds();
        if (rect != null) {
            float from = getCurrentStartPosition(rect);
            if (from != to) {
                animate(from, to, velocity, isClosing);
            }

            // If we swipe down the notification panel all the way to the bottom of the screen
            // (i.e. from == to), then we have finished animating the panel.
            return;
        }

        // We will only be here if the shade is being opened programmatically or via button when
        // height of the layout was not calculated.
        ViewTreeObserver panelTreeObserver = getLayout().getViewTreeObserver();
        panelTreeObserver.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ViewTreeObserver obs = getLayout().getViewTreeObserver();
                        obs.removeOnGlobalLayoutListener(this);
                        animate(
                                getDefaultStartPosition(),
                                getEndPosition(/* isClosing= */ false),
                                velocity,
                                isClosing
                        );
                    }
                });
    }

    /* Returns the start position if the user has not started swiping. */
    private int getDefaultStartPosition() {
        return mAnimateDirection > 0 ? 0 : getLayout().getHeight();
    }

    /** Returns the start position if we are in the middle of swiping. */
    private int getCurrentStartPosition(Rect clipBounds) {
        return mAnimateDirection > 0 ? clipBounds.bottom : clipBounds.top;
    }

    private int getEndPosition(boolean isClosing) {
        return (mAnimateDirection > 0 && !isClosing) || (mAnimateDirection == -1 && isClosing)
                ? getLayout().getHeight()
                : 0;
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
            getOverlayViewGlobalStateController().showView(/* panelViewController= */ this);
        }
        if (!visible && getOverlayViewGlobalStateController().isWindowVisible()) {
            getOverlayViewGlobalStateController().hideView(/* panelViewController= */ this);
        }
        getLayout().setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
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

    /**
     * Given the position of the pointer dragging the panel, return the percentage of its closeness
     * to the ending edge.
     */
    protected void calculatePercentageFromEndingEdge(float y) {
        if (getLayout().getHeight() > 0) {
            float height = getVisiblePanelHeight(y);
            mPercentageFromEndingEdge = (int) Math.abs(height / getLayout().getHeight() * 100);
        }
    }

    private float getVisiblePanelHeight(float y) {
        return mAnimateDirection > 0 ? y : getLayout().getHeight() - y;
    }

    /** Sets the boundaries of the overlay panel that can be seen based on pointer position. */
    protected void setViewClipBounds(int y) {
        // Bound the pointer position to be within the overlay panel.
        y = Math.max(0, Math.min(y, getLayout().getHeight()));
        Rect clipBounds = new Rect();
        int top, bottom;
        if (mAnimateDirection > 0) {
            top = 0;
            bottom = y;
        } else {
            top = y;
            bottom = getLayout().getHeight();
        }
        clipBounds.set(0, top, getLayout().getWidth(), bottom);
        getLayout().setClipBounds(clipBounds);
        onScroll(y);
    }

    /**
     * Called while scrolling, this passes the position of the clip boundary that is currently
     * changing.
     */
    protected abstract void onScroll(int y);

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

    /** Sets whether the panel is currently tracking or not. */
    protected final void setIsTracking(boolean isTracking) {
        mIsTracking = isTracking;
    }

    /** Returns {@code true} if the panel is currently animating. */
    protected final boolean isAnimating() {
        return mIsAnimating;
    }

    /** Returns the percentage of the panel that is open from the bottom. */
    protected final int getPercentageFromEndingEdge() {
        return mPercentageFromEndingEdge;
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

            // clips the view for the panel when the user scrolls to open.
            setViewClipBounds((int) event2.getRawY());

            // Initially the scroll starts with height being zero. This checks protects from divide
            // by zero error.
            calculatePercentageFromEndingEdge(event2.getRawY());

            mIsTracking = true;
            return true;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            if (mAnimateDirection * velocityY > SWIPE_THRESHOLD_VELOCITY) {
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
            if (!shouldAllowClosingScroll()) {
                return false;
            }
            float y = getYPositionOfPanelEndingEdge(event1, event2);
            if (getLayout().getHeight() > 0) {
                mPercentageFromEndingEdge = (int) Math.abs(
                        y / getLayout().getHeight() * 100);
                boolean isInClosingDirection = mAnimateDirection * distanceY > 0;

                // This check is to figure out if onScroll was called while swiping the card at
                // bottom of the panel. At that time we should not allow panel to
                // close. We are also checking for the upwards swipe gesture here because it is
                // possible if a user is closing the panel and while swiping starts
                // to open again but does not fling. At that time we should allow the
                // panel to close fully or else it would stuck in between.
                if (Math.abs(getLayout().getHeight() - y)
                        > SWIPE_DOWN_MIN_DISTANCE && isInClosingDirection) {
                    setViewClipBounds((int) y);
                    mIsTracking = true;
                } else if (!isInClosingDirection) {
                    setViewClipBounds((int) y);
                }
            }
            // if we return true the items in RV won't be scrollable.
            return false;
        }

        /**
         * To prevent the jump in the clip bounds while closing the panel we should calculate the y
         * position using the diff of event1 and event2. This will help the panel clip smoothly as
         * the event2 value changes while event1 value will be fixed.
         * @param event1 MotionEvent that contains the position of where the event2 started.
         * @param event2 MotionEvent that contains the position of where the user has scrolled to
         *               on the screen.
         */
        private float getYPositionOfPanelEndingEdge(MotionEvent event1, MotionEvent event2) {
            float diff = mAnimateDirection * (event1.getRawY() - event2.getRawY());
            float y = mAnimateDirection > 0 ? getLayout().getHeight() - diff : diff;
            y = Math.max(0, Math.min(y, getLayout().getHeight()));
            return y;
        }

        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            // should not fling if the touch does not start when view is at the end of the list.
            if (!shouldAllowClosingScroll()) {
                return false;
            }
            if (Math.abs(event1.getX() - event2.getX()) > SWIPE_MAX_OFF_PATH
                    || Math.abs(velocityY) < SWIPE_THRESHOLD_VELOCITY) {
                // swipe was not vertical or was not fast enough
                return false;
            }
            boolean isInClosingDirection = mAnimateDirection * velocityY < 0;
            if (isInClosingDirection) {
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
            calculatePercentageFromEndingEdge(event2.getRawY());
            setViewClipBounds((int) event2.getRawY());
            return true;
        }
    }
}
