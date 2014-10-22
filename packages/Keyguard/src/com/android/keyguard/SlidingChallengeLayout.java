/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Property;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Interpolator;
import android.widget.Scroller;

/**
 * This layout handles interaction with the sliding security challenge views
 * that overlay/resize other keyguard contents.
 */
public class SlidingChallengeLayout extends ViewGroup implements ChallengeLayout {
    private static final String TAG = "SlidingChallengeLayout";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    // The drag handle is measured in dp above & below the top edge of the
    // challenge view; these parameters change based on whether the challenge
    // is open or closed.
    private static final int DRAG_HANDLE_CLOSED_ABOVE = 8; // dp
    private static final int DRAG_HANDLE_CLOSED_BELOW = 0; // dp
    private static final int DRAG_HANDLE_OPEN_ABOVE = 8; // dp
    private static final int DRAG_HANDLE_OPEN_BELOW = 0; // dp

    private static final int HANDLE_ANIMATE_DURATION = 250; // ms

    // Drawn to show the drag handle in closed state; crossfades to the challenge view
    // when challenge is fully visible
    private boolean mEdgeCaptured;

    private DisplayMetrics mDisplayMetrics;

    // Initialized during measurement from child layoutparams
    private View mExpandChallengeView;
    private KeyguardSecurityContainer mChallengeView;
    private View mScrimView;
    private View mWidgetsView;

    // Range: 0 (fully hidden) to 1 (fully visible)
    private float mChallengeOffset = 1.f;
    private boolean mChallengeShowing = true;
    private boolean mChallengeShowingTargetState = true;
    private boolean mWasChallengeShowing = true;
    private boolean mIsBouncing = false;

    private final Scroller mScroller;
    private ObjectAnimator mFader;
    private int mScrollState;
    private OnChallengeScrolledListener mScrollListener;
    private OnBouncerStateChangedListener mBouncerListener;
    private boolean mEnableChallengeDragging;

    public static final int SCROLL_STATE_IDLE = 0;
    public static final int SCROLL_STATE_DRAGGING = 1;
    public static final int SCROLL_STATE_SETTLING = 2;
    public static final int SCROLL_STATE_FADING = 3;

    public static final int CHALLENGE_FADE_OUT_DURATION = 100;
    public static final int CHALLENGE_FADE_IN_DURATION = 160;

    private static final int MAX_SETTLE_DURATION = 600; // ms

    // ID of the pointer in charge of a current drag
    private int mActivePointerId = INVALID_POINTER;
    private static final int INVALID_POINTER = -1;

    // True if the user is currently dragging the slider
    private boolean mDragging;
    // True if the user may not drag until a new gesture begins
    private boolean mBlockDrag;

    private VelocityTracker mVelocityTracker;
    private int mMinVelocity;
    private int mMaxVelocity;
    private float mGestureStartX, mGestureStartY; // where did you first touch the screen?
    private int mGestureStartChallengeBottom; // where was the challenge at that time?

    private int mDragHandleClosedBelow; // handle hitrect extension into the challenge view
    private int mDragHandleClosedAbove; // extend the handle's hitrect this far above the line
    private int mDragHandleOpenBelow; // handle hitrect extension into the challenge view
    private int mDragHandleOpenAbove; // extend the handle's hitrect this far above the line

    private int mDragHandleEdgeSlop;
    private int mChallengeBottomBound; // Number of pixels from the top of the challenge view
                                       // that should remain on-screen

    private int mTouchSlop;
    private int mTouchSlopSquare;

    float mHandleAlpha;
    float mFrameAlpha;
    float mFrameAnimationTarget = Float.MIN_VALUE;
    private ObjectAnimator mHandleAnimation;
    private ObjectAnimator mFrameAnimation;

    private boolean mHasGlowpad;
    private final Rect mInsets = new Rect();

    // We have an internal and external version, and we and them together.
    private boolean mChallengeInteractiveExternal = true;
    private boolean mChallengeInteractiveInternal = true;

    static final Property<SlidingChallengeLayout, Float> HANDLE_ALPHA =
            new FloatProperty<SlidingChallengeLayout>("handleAlpha") {
        @Override
        public void setValue(SlidingChallengeLayout view, float value) {
            view.mHandleAlpha = value;
            view.invalidate();
        }

        @Override
        public Float get(SlidingChallengeLayout view) {
            return view.mHandleAlpha;
        }
    };

    // True if at least one layout pass has happened since the view was attached.
    private boolean mHasLayout;

    private static final Interpolator sMotionInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    private static final Interpolator sHandleFadeInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            return t * t;
        }
    };

    private final Runnable mEndScrollRunnable = new Runnable () {
        public void run() {
            completeChallengeScroll();
        }
    };

    private final OnClickListener mScrimClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            hideBouncer();
        }
    };

    private final OnClickListener mExpandChallengeClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!isChallengeShowing()) {
                showChallenge(true);
            }
        }
    };

    /**
     * Listener interface that reports changes in scroll state of the challenge area.
     */
    public interface OnChallengeScrolledListener {
        /**
         * The scroll state itself changed.
         *
         * <p>scrollState will be one of the following:</p>
         *
         * <ul>
         * <li><code>SCROLL_STATE_IDLE</code> - The challenge area is stationary.</li>
         * <li><code>SCROLL_STATE_DRAGGING</code> - The user is actively dragging
         * the challenge area.</li>
         * <li><code>SCROLL_STATE_SETTLING</code> - The challenge area is animating
         * into place.</li>
         * </ul>
         *
         * <p>Do not perform expensive operations (e.g. layout)
         * while the scroll state is not <code>SCROLL_STATE_IDLE</code>.</p>
         *
         * @param scrollState The new scroll state of the challenge area.
         */
        public void onScrollStateChanged(int scrollState);

        /**
         * The precise position of the challenge area has changed.
         *
         * <p>NOTE: It is NOT safe to modify layout or call any View methods that may
         * result in a requestLayout anywhere in your view hierarchy as a result of this call.
         * It may be called during drawing.</p>
         *
         * @param scrollPosition New relative position of the challenge area.
         *                       1.f = fully visible/ready to be interacted with.
         *                       0.f = fully invisible/inaccessible to the user.
         * @param challengeTop Position of the top edge of the challenge view in px in the
         *                     SlidingChallengeLayout's coordinate system.
         */
        public void onScrollPositionChanged(float scrollPosition, int challengeTop);
    }

    public SlidingChallengeLayout(Context context) {
        this(context, null);
    }

    public SlidingChallengeLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SlidingChallengeLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mScroller = new Scroller(context, sMotionInterpolator);

        final ViewConfiguration vc = ViewConfiguration.get(context);
        mMinVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxVelocity = vc.getScaledMaximumFlingVelocity();

        final Resources res = getResources();
        mDragHandleEdgeSlop = res.getDimensionPixelSize(R.dimen.kg_edge_swipe_region_size);

        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquare = mTouchSlop * mTouchSlop;

        mDisplayMetrics = res.getDisplayMetrics();
        final float density = mDisplayMetrics.density;

        // top half of the lock icon, plus another 25% to be sure
        mDragHandleClosedAbove = (int) (DRAG_HANDLE_CLOSED_ABOVE * density + 0.5f);
        mDragHandleClosedBelow = (int) (DRAG_HANDLE_CLOSED_BELOW * density + 0.5f);
        mDragHandleOpenAbove = (int) (DRAG_HANDLE_OPEN_ABOVE * density + 0.5f);
        mDragHandleOpenBelow = (int) (DRAG_HANDLE_OPEN_BELOW * density + 0.5f);

        // how much space to account for in the handle when closed
        mChallengeBottomBound = res.getDimensionPixelSize(R.dimen.kg_widget_pager_bottom_padding);

        setWillNotDraw(false);
        setSystemUiVisibility(SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    public void setEnableChallengeDragging(boolean enabled) {
        mEnableChallengeDragging = enabled;
    }

    public void setInsets(Rect insets) {
        mInsets.set(insets);
    }

    public void setHandleAlpha(float alpha) {
        if (mExpandChallengeView != null) {
            mExpandChallengeView.setAlpha(alpha);
        }
    }

    public void setChallengeInteractive(boolean interactive) {
        mChallengeInteractiveExternal = interactive;
        if (mExpandChallengeView != null) {
            mExpandChallengeView.setEnabled(interactive);
        }
    }

    void animateHandle(boolean visible) {
        if (mHandleAnimation != null) {
            mHandleAnimation.cancel();
            mHandleAnimation = null;
        }
        final float targetAlpha = visible ? 1.f : 0.f;
        if (targetAlpha == mHandleAlpha) {
            return;
        }
        mHandleAnimation = ObjectAnimator.ofFloat(this, HANDLE_ALPHA, targetAlpha);
        mHandleAnimation.setInterpolator(sHandleFadeInterpolator);
        mHandleAnimation.setDuration(HANDLE_ANIMATE_DURATION);
        mHandleAnimation.start();
    }

    private void sendInitialListenerUpdates() {
        if (mScrollListener != null) {
            int challengeTop = mChallengeView != null ? mChallengeView.getTop() : 0;
            mScrollListener.onScrollPositionChanged(mChallengeOffset, challengeTop);
            mScrollListener.onScrollStateChanged(mScrollState);
        }
    }

    public void setOnChallengeScrolledListener(OnChallengeScrolledListener listener) {
        mScrollListener = listener;
        if (mHasLayout) {
            sendInitialListenerUpdates();
        }
    }

    public void setOnBouncerStateChangedListener(OnBouncerStateChangedListener listener) {
        mBouncerListener = listener;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mHasLayout = false;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(mEndScrollRunnable);
        mHasLayout = false;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (mIsBouncing && child != mChallengeView) {
            // Clear out of the bouncer if the user tries to move focus outside of
            // the security challenge view.
            hideBouncer();
        }
        super.requestChildFocus(child, focused);
    }

    // We want the duration of the page snap animation to be influenced by the distance that
    // the screen has to travel, however, we don't want this duration to be effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect that the distance
    // of travel has on the overall snap duration.
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    void setScrollState(int state) {
        if (mScrollState != state) {
            mScrollState = state;

            animateHandle(state == SCROLL_STATE_IDLE && !mChallengeShowing);
            if (mScrollListener != null) {
                mScrollListener.onScrollStateChanged(state);
            }
        }
    }

    void completeChallengeScroll() {
        setChallengeShowing(mChallengeShowingTargetState);
        mChallengeOffset = mChallengeShowing ? 1.f : 0.f;
        setScrollState(SCROLL_STATE_IDLE);
        mChallengeInteractiveInternal = true;
        mChallengeView.setLayerType(LAYER_TYPE_NONE, null);
    }

    void setScrimView(View scrim) {
        if (mScrimView != null) {
            mScrimView.setOnClickListener(null);
        }
        mScrimView = scrim;
        if (mScrimView != null) {
            mScrimView.setVisibility(mIsBouncing ? VISIBLE : GONE);
            mScrimView.setFocusable(true);
            mScrimView.setOnClickListener(mScrimClickListener);
        }
    }

    /**
     * Animate the bottom edge of the challenge view to the given position.
     *
     * @param y desired final position for the bottom edge of the challenge view in px
     * @param velocity velocity in
     */
    void animateChallengeTo(int y, int velocity) {
        if (mChallengeView == null) {
            // Nothing to do.
            return;
        }

        cancelTransitionsInProgress();

        mChallengeInteractiveInternal = false;
        enableHardwareLayerForChallengeView();
        final int sy = mChallengeView.getBottom();
        final int dy = y - sy;
        if (dy == 0) {
            completeChallengeScroll();
            return;
        }

        setScrollState(SCROLL_STATE_SETTLING);

        final int childHeight = mChallengeView.getHeight();
        final int halfHeight = childHeight / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dy) / childHeight);
        final float distance = halfHeight + halfHeight *
                distanceInfluenceForSnapDuration(distanceRatio);

        int duration = 0;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float childDelta = (float) Math.abs(dy) / childHeight;
            duration = (int) ((childDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);

        mScroller.startScroll(0, sy, 0, dy, duration);
        postInvalidateOnAnimation();
    }

    private void setChallengeShowing(boolean showChallenge) {
        if (mChallengeShowing == showChallenge) {
            return;
        }
        mChallengeShowing = showChallenge;

        if (mExpandChallengeView == null || mChallengeView == null) {
            // These might not be here yet if we haven't been through layout.
            // If we haven't, the first layout pass will set everything up correctly
            // based on mChallengeShowing as set above.
            return;
        }

        if (mChallengeShowing) {
            mExpandChallengeView.setVisibility(View.INVISIBLE);
            mChallengeView.setVisibility(View.VISIBLE);
            if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                mChallengeView.requestAccessibilityFocus();
                mChallengeView.announceForAccessibility(mContext.getString(
                        R.string.keyguard_accessibility_unlock_area_expanded));
            }
        } else {
            mExpandChallengeView.setVisibility(View.VISIBLE);
            mChallengeView.setVisibility(View.INVISIBLE);
            if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                mExpandChallengeView.requestAccessibilityFocus();
                mChallengeView.announceForAccessibility(mContext.getString(
                        R.string.keyguard_accessibility_unlock_area_collapsed));
            }
        }
    }

    /**
     * @return true if the challenge is at all visible.
     */
    public boolean isChallengeShowing() {
        return mChallengeShowing;
    }

    @Override
    public boolean isChallengeOverlapping() {
        return mChallengeShowing;
    }

    @Override
    public boolean isBouncing() {
        return mIsBouncing;
    }

    @Override
    public int getBouncerAnimationDuration() {
        return HANDLE_ANIMATE_DURATION;
    }

    @Override
    public void showBouncer() {
        if (mIsBouncing) return;
        setSystemUiVisibility(getSystemUiVisibility() | STATUS_BAR_DISABLE_SEARCH);
        mWasChallengeShowing = mChallengeShowing;
        mIsBouncing = true;
        showChallenge(true);
        if (mScrimView != null) {
            Animator anim = ObjectAnimator.ofFloat(mScrimView, "alpha", 1f);
            anim.setDuration(HANDLE_ANIMATE_DURATION);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mScrimView.setVisibility(VISIBLE);
                }
            });
            anim.start();
        }
        if (mChallengeView != null) {
            mChallengeView.showBouncer(HANDLE_ANIMATE_DURATION);
        }

        if (mBouncerListener != null) {
            mBouncerListener.onBouncerStateChanged(true);
        }
    }

    @Override
    public void hideBouncer() {
        if (!mIsBouncing) return;
        setSystemUiVisibility(getSystemUiVisibility() & ~STATUS_BAR_DISABLE_SEARCH);
        if (!mWasChallengeShowing) showChallenge(false);
        mIsBouncing = false;

        if (mScrimView != null) {
            Animator anim = ObjectAnimator.ofFloat(mScrimView, "alpha", 0f);
            anim.setDuration(HANDLE_ANIMATE_DURATION);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mScrimView.setVisibility(GONE);
                }
            });
            anim.start();
        }
        if (mChallengeView != null) {
            mChallengeView.hideBouncer(HANDLE_ANIMATE_DURATION);
        }
        if (mBouncerListener != null) {
            mBouncerListener.onBouncerStateChanged(false);
        }
    }

    private int getChallengeMargin(boolean expanded) {
        return expanded && mHasGlowpad ? 0 : mDragHandleEdgeSlop;
    }

    private float getChallengeAlpha() {
        float x = mChallengeOffset - 1;
        return x * x * x + 1.f;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean allowIntercept) {
        // We'll intercept whoever we feel like! ...as long as it isn't a challenge view.
        // If there are one or more pointers in the challenge view before we take over
        // touch events, onInterceptTouchEvent will set mBlockDrag.
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mGestureStartX = ev.getX();
                mGestureStartY = ev.getY();
                mBlockDrag = false;
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                resetTouch();
                break;

            case MotionEvent.ACTION_MOVE:
                final int count = ev.getPointerCount();
                for (int i = 0; i < count; i++) {
                    final float x = ev.getX(i);
                    final float y = ev.getY(i);
                    if (!mIsBouncing && mActivePointerId == INVALID_POINTER
                                && (crossedDragHandle(x, y, mGestureStartY)
                                        && shouldEnableChallengeDragging()
                                        || (isInChallengeView(x, y) &&
                                        mScrollState == SCROLL_STATE_SETTLING))) {
                        mActivePointerId = ev.getPointerId(i);
                        mGestureStartX = x;
                        mGestureStartY = y;
                        mGestureStartChallengeBottom = getChallengeBottom();
                        mDragging = true;
                        enableHardwareLayerForChallengeView();
                    } else if (mChallengeShowing && isInChallengeView(x, y)
                            && shouldEnableChallengeDragging()) {
                        mBlockDrag = true;
                    }
                }
                break;
        }

        if (mBlockDrag || isChallengeInteractionBlocked()) {
            mActivePointerId = INVALID_POINTER;
            mDragging = false;
        }

        return mDragging;
    }

    private boolean shouldEnableChallengeDragging() {
        return mEnableChallengeDragging || !mChallengeShowing;
    }

    private boolean isChallengeInteractionBlocked() {
        return !mChallengeInteractiveExternal || !mChallengeInteractiveInternal;
    }

    private void resetTouch() {
        mVelocityTracker.recycle();
        mVelocityTracker = null;
        mActivePointerId = INVALID_POINTER;
        mDragging = mBlockDrag = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mBlockDrag = false;
                mGestureStartX = ev.getX();
                mGestureStartY = ev.getY();
                break;

            case MotionEvent.ACTION_CANCEL:
                if (mDragging && !isChallengeInteractionBlocked()) {
                    showChallenge(0);
                }
                resetTouch();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (mActivePointerId != ev.getPointerId(ev.getActionIndex())) {
                    break;
                }
            case MotionEvent.ACTION_UP:
                if (mDragging && !isChallengeInteractionBlocked()) {
                    mVelocityTracker.computeCurrentVelocity(1000, mMaxVelocity);
                    showChallenge((int) mVelocityTracker.getYVelocity(mActivePointerId));
                }
                resetTouch();
                break;

            case MotionEvent.ACTION_MOVE:
                if (!mDragging && !mBlockDrag && !mIsBouncing) {
                    final int count = ev.getPointerCount();
                    for (int i = 0; i < count; i++) {
                        final float x = ev.getX(i);
                        final float y = ev.getY(i);

                        if ((isInDragHandle(x, y) || crossedDragHandle(x, y, mGestureStartY) ||
                                (isInChallengeView(x, y) && mScrollState == SCROLL_STATE_SETTLING))
                                && mActivePointerId == INVALID_POINTER
                                && !isChallengeInteractionBlocked()) {
                            mGestureStartX = x;
                            mGestureStartY = y;
                            mActivePointerId = ev.getPointerId(i);
                            mGestureStartChallengeBottom = getChallengeBottom();
                            mDragging = true;
                            enableHardwareLayerForChallengeView();
                            break;
                        }
                    }
                }
                // Not an else; this can be set above.
                if (mDragging) {
                    // No-op if already in this state, but set it here in case we arrived
                    // at this point from either intercept or the above.
                    setScrollState(SCROLL_STATE_DRAGGING);

                    final int index = ev.findPointerIndex(mActivePointerId);
                    if (index < 0) {
                        // Oops, bogus state. We lost some touch events somewhere.
                        // Just drop it with no velocity and let things settle.
                        resetTouch();
                        showChallenge(0);
                        return true;
                    }
                    final float y = ev.getY(index);
                    final float pos = Math.min(y - mGestureStartY,
                            getLayoutBottom() - mChallengeBottomBound);

                    moveChallengeTo(mGestureStartChallengeBottom + (int) pos);
                }
                break;
        }
        return true;
    }

    /**
     * The lifecycle of touch events is subtle and it's very easy to do something
     * that will cause bugs that will be nasty to track when overriding this method.
     * Normally one should always override onInterceptTouchEvent instead.
     *
     * To put it another way, don't try this at home.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        boolean handled = false;
        if (action == MotionEvent.ACTION_DOWN) {
            // Defensive programming: if we didn't get the UP or CANCEL, reset anyway.
            mEdgeCaptured = false;
        }
        if (mWidgetsView != null && !mIsBouncing && (mEdgeCaptured || isEdgeSwipeBeginEvent(ev))) {
            // Normally we would need to do a lot of extra stuff here.
            // We can only get away with this because we haven't padded in
            // the widget pager or otherwise transformed it during layout.
            // We also don't support things like splitting MotionEvents.

            // We set handled to captured even if dispatch is returning false here so that
            // we don't send a different view a busted or incomplete event stream.
            handled = mEdgeCaptured |= mWidgetsView.dispatchTouchEvent(ev);
        }

        if (!handled && !mEdgeCaptured) {
            handled = super.dispatchTouchEvent(ev);
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mEdgeCaptured = false;
        }

        return handled;
    }

    private boolean isEdgeSwipeBeginEvent(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return false;
        }

        final float x = ev.getX();
        return x < mDragHandleEdgeSlop || x >= getWidth() - mDragHandleEdgeSlop;
    }

    /**
     * We only want to add additional vertical space to the drag handle when the panel is fully
     * closed.
     */
    private int getDragHandleSizeAbove() {
        return isChallengeShowing() ? mDragHandleOpenAbove : mDragHandleClosedAbove;
    }
    private int getDragHandleSizeBelow() {
        return isChallengeShowing() ? mDragHandleOpenBelow : mDragHandleClosedBelow;
    }

    private boolean isInChallengeView(float x, float y) {
        return isPointInView(x, y, mChallengeView);
    }

    private boolean isInDragHandle(float x, float y) {
        return isPointInView(x, y, mExpandChallengeView);
    }

    private boolean isPointInView(float x, float y, View view) {
        if (view == null) {
            return false;
        }
        return x >= view.getLeft() && y >= view.getTop()
                && x < view.getRight() && y < view.getBottom();
    }

    private boolean crossedDragHandle(float x, float y, float initialY) {

        final int challengeTop = mChallengeView.getTop();
        final boolean horizOk = x >= 0 && x < getWidth();

        final boolean vertOk;
        if (mChallengeShowing) {
            vertOk = initialY < (challengeTop - getDragHandleSizeAbove()) &&
                    y > challengeTop + getDragHandleSizeBelow();
        } else {
            vertOk = initialY > challengeTop + getDragHandleSizeBelow() &&
                    y < challengeTop - getDragHandleSizeAbove();
        }
        return horizOk && vertOk;
    }

    private int makeChildMeasureSpec(int maxSize, int childDimen) {
        final int mode;
        final int size;
        switch (childDimen) {
            case LayoutParams.WRAP_CONTENT:
                mode = MeasureSpec.AT_MOST;
                size = maxSize;
                break;
            case LayoutParams.MATCH_PARENT:
                mode = MeasureSpec.EXACTLY;
                size = maxSize;
                break;
            default:
                mode = MeasureSpec.EXACTLY;
                size = Math.min(maxSize, childDimen);
                break;
        }
        return MeasureSpec.makeMeasureSpec(size, mode);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.EXACTLY ||
                MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            throw new IllegalArgumentException(
                    "SlidingChallengeLayout must be measured with an exact size");
        }
        final int width = MeasureSpec.getSize(widthSpec);
        final int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);

        final int insetHeight = height - mInsets.top - mInsets.bottom;
        final int insetHeightSpec = MeasureSpec.makeMeasureSpec(insetHeight, MeasureSpec.EXACTLY);

        // Find one and only one challenge view.
        final View oldChallengeView = mChallengeView;
        final View oldExpandChallengeView = mChallengeView;
        mChallengeView = null;
        mExpandChallengeView = null;
        final int count = getChildCount();

        // First iteration through the children finds special children and sets any associated
        // state.
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.childType == LayoutParams.CHILD_TYPE_CHALLENGE) {
                if (mChallengeView != null) {
                    throw new IllegalStateException(
                            "There may only be one child with layout_isChallenge=\"true\"");
                }
                if (!(child instanceof KeyguardSecurityContainer)) {
                            throw new IllegalArgumentException(
                                    "Challenge must be a KeyguardSecurityContainer");
                }
                mChallengeView = (KeyguardSecurityContainer) child;
                if (mChallengeView != oldChallengeView) {
                    mChallengeView.setVisibility(mChallengeShowing ? VISIBLE : INVISIBLE);
                }
                // We're going to play silly games with the frame's background drawable later.
                if (!mHasLayout) {
                    // Set up the margin correctly based on our content for the first run.
                    mHasGlowpad = child.findViewById(R.id.keyguard_selector_view) != null;
                    lp.leftMargin = lp.rightMargin = getChallengeMargin(true);
                }
            } else if (lp.childType == LayoutParams.CHILD_TYPE_EXPAND_CHALLENGE_HANDLE) {
                if (mExpandChallengeView != null) {
                    throw new IllegalStateException(
                            "There may only be one child with layout_childType"
                            + "=\"expandChallengeHandle\"");
                }
                mExpandChallengeView = child;
                if (mExpandChallengeView != oldExpandChallengeView) {
                    mExpandChallengeView.setVisibility(mChallengeShowing ? INVISIBLE : VISIBLE);
                    mExpandChallengeView.setOnClickListener(mExpandChallengeClickListener);
                }
            } else if (lp.childType == LayoutParams.CHILD_TYPE_SCRIM) {
                setScrimView(child);
            } else if (lp.childType == LayoutParams.CHILD_TYPE_WIDGETS) {
                mWidgetsView = child;
            }
        }

        // We want to measure the challenge view first, since the KeyguardWidgetPager
        // needs to do things its measure pass that are dependent on the challenge view
        // having been measured.
        if (mChallengeView != null && mChallengeView.getVisibility() != View.GONE) {
            // This one's a little funny. If the IME is present - reported in the form
            // of insets on the root view - we only give the challenge the space it would
            // have had if the IME wasn't there in order to keep the rest of the layout stable.
            // We base this on the layout_maxHeight on the challenge view. If it comes out
            // negative or zero, either we didn't have a maxHeight or we're totally out of space,
            // so give up and measure as if this rule weren't there.
            int challengeHeightSpec = insetHeightSpec;
            final View root = getRootView();
            if (root != null) {
                final LayoutParams lp = (LayoutParams) mChallengeView.getLayoutParams();
                final int windowHeight = mDisplayMetrics.heightPixels
                        - root.getPaddingTop() - mInsets.top;
                final int diff = windowHeight - insetHeight;
                final int maxChallengeHeight = lp.maxHeight - diff;
                if (maxChallengeHeight > 0) {
                    challengeHeightSpec = makeChildMeasureSpec(maxChallengeHeight, lp.height);
                }
            }
            measureChildWithMargins(mChallengeView, widthSpec, 0, challengeHeightSpec, 0);
        }

        // Measure the rest of the children
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            // Don't measure the challenge view twice!
            if (child == mChallengeView) continue;

            // Measure children. Widget frame measures special, so that we can ignore
            // insets for the IME.
            int parentWidthSpec = widthSpec, parentHeightSpec = insetHeightSpec;
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.childType == LayoutParams.CHILD_TYPE_WIDGETS) {
                final View root = getRootView();
                if (root != null) {
                    // This calculation is super dodgy and relies on several assumptions.
                    // Specifically that the root of the window will be padded in for insets
                    // and that the window is LAYOUT_IN_SCREEN.
                    final int windowWidth = mDisplayMetrics.widthPixels;
                    final int windowHeight = mDisplayMetrics.heightPixels
                            - root.getPaddingTop() - mInsets.top;
                    parentWidthSpec = MeasureSpec.makeMeasureSpec(
                            windowWidth, MeasureSpec.EXACTLY);
                    parentHeightSpec = MeasureSpec.makeMeasureSpec(
                            windowHeight, MeasureSpec.EXACTLY);
                }
            } else if (lp.childType == LayoutParams.CHILD_TYPE_SCRIM) {
                // Allow scrim views to extend into the insets
                parentWidthSpec = widthSpec;
                parentHeightSpec = heightSpec;
            }
            measureChildWithMargins(child, parentWidthSpec, 0, parentHeightSpec, 0);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int paddingLeft = getPaddingLeft();
        final int paddingTop = getPaddingTop();
        final int paddingRight = getPaddingRight();
        final int paddingBottom = getPaddingBottom();
        final int width = r - l;
        final int height = b - t;

        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) continue;

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (lp.childType == LayoutParams.CHILD_TYPE_CHALLENGE) {
                // Challenge views pin to the bottom, offset by a portion of their height,
                // and center horizontally.
                final int center = (paddingLeft + width - paddingRight) / 2;
                final int childWidth = child.getMeasuredWidth();
                final int childHeight = child.getMeasuredHeight();
                final int left = center - childWidth / 2;
                final int layoutBottom = height - paddingBottom - lp.bottomMargin - mInsets.bottom;
                // We use the top of the challenge view to position the handle, so
                // we never want less than the handle size showing at the bottom.
                final int bottom = layoutBottom + (int) ((childHeight - mChallengeBottomBound)
                        * (1 - mChallengeOffset));
                child.setAlpha(getChallengeAlpha());
                child.layout(left, bottom - childHeight, left + childWidth, bottom);
            } else if (lp.childType == LayoutParams.CHILD_TYPE_EXPAND_CHALLENGE_HANDLE) {
                final int center = (paddingLeft + width - paddingRight) / 2;
                final int left = center - child.getMeasuredWidth() / 2;
                final int right = left + child.getMeasuredWidth();
                final int bottom = height - paddingBottom - lp.bottomMargin - mInsets.bottom;
                final int top = bottom - child.getMeasuredHeight();
                child.layout(left, top, right, bottom);
            } else if (lp.childType == LayoutParams.CHILD_TYPE_SCRIM) {
                // Scrim views use the entire area, including padding & insets
                child.layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
            } else {
                // Non-challenge views lay out from the upper left, layered.
                child.layout(paddingLeft + lp.leftMargin,
                        paddingTop + lp.topMargin + mInsets.top,
                        paddingLeft + child.getMeasuredWidth(),
                        paddingTop + child.getMeasuredHeight() + mInsets.top);
            }
        }

        if (!mHasLayout) {
            mHasLayout = true;
        }
    }

    @Override
    public void draw(Canvas c) {
        super.draw(c);
        if (DEBUG) {
            final Paint debugPaint = new Paint();
            debugPaint.setColor(0x40FF00CC);
            // show the isInDragHandle() rect
            c.drawRect(mDragHandleEdgeSlop,
                    mChallengeView.getTop() - getDragHandleSizeAbove(),
                    getWidth() - mDragHandleEdgeSlop,
                    mChallengeView.getTop() + getDragHandleSizeBelow(),
                    debugPaint);
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // Focus security fileds before widgets.
        if (mChallengeView != null &&
                mChallengeView.requestFocus(direction, previouslyFocusedRect)) {
            return true;
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    public void computeScroll() {
        super.computeScroll();

        if (!mScroller.isFinished()) {
            if (mChallengeView == null) {
                // Can't scroll if the view is missing.
                Log.e(TAG, "Challenge view missing in computeScroll");
                mScroller.abortAnimation();
                return;
            }

            mScroller.computeScrollOffset();
            moveChallengeTo(mScroller.getCurrY());

            if (mScroller.isFinished()) {
                post(mEndScrollRunnable);
            }
        }
    }

    private void cancelTransitionsInProgress() {
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
            completeChallengeScroll();
        }
        if (mFader != null) {
            mFader.cancel();
        }
    }

    public void fadeInChallenge() {
        fadeChallenge(true);
    }

    public void fadeOutChallenge() {
        fadeChallenge(false);
    }

    public void fadeChallenge(final boolean show) {
        if (mChallengeView != null) {

            cancelTransitionsInProgress();
            float alpha = show ? 1f : 0f;
            int duration = show ? CHALLENGE_FADE_IN_DURATION : CHALLENGE_FADE_OUT_DURATION;
            mFader = ObjectAnimator.ofFloat(mChallengeView, "alpha", alpha);
            mFader.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    onFadeStart(show);
                }
                @Override
                public void onAnimationEnd(Animator animation) {
                    onFadeEnd(show);
                }
            });
            mFader.setDuration(duration);
            mFader.start();
        }
    }

    private int getMaxChallengeBottom() {
        if (mChallengeView == null) return 0;
        final int layoutBottom = getLayoutBottom();
        final int challengeHeight = mChallengeView.getMeasuredHeight();

        return (layoutBottom + challengeHeight - mChallengeBottomBound);
    }

    private int getMinChallengeBottom() {
        return getLayoutBottom();
    }


    private void onFadeStart(boolean show) {
        mChallengeInteractiveInternal = false;
        enableHardwareLayerForChallengeView();

        if (show) {
            moveChallengeTo(getMinChallengeBottom());
        }

        setScrollState(SCROLL_STATE_FADING);
    }

    private void enableHardwareLayerForChallengeView() {
        if (mChallengeView.isHardwareAccelerated()) {
            mChallengeView.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }

    private void onFadeEnd(boolean show) {
        mChallengeInteractiveInternal = true;
        setChallengeShowing(show);

        if (!show) {
            moveChallengeTo(getMaxChallengeBottom());
        }

        mChallengeView.setLayerType(LAYER_TYPE_NONE, null);
        mFader = null;
        setScrollState(SCROLL_STATE_IDLE);
    }

    public int getMaxChallengeTop() {
        if (mChallengeView == null) return 0;

        final int layoutBottom = getLayoutBottom();
        final int challengeHeight = mChallengeView.getMeasuredHeight();
        return layoutBottom - challengeHeight - mInsets.top;
    }

    /**
     * Move the bottom edge of mChallengeView to a new position and notify the listener
     * if it represents a change in position. Changes made through this method will
     * be stable across layout passes. If this method is called before first layout of
     * this SlidingChallengeLayout it will have no effect.
     *
     * @param bottom New bottom edge in px in this SlidingChallengeLayout's coordinate system.
     * @return true if the challenge view was moved
     */
    private boolean moveChallengeTo(int bottom) {
        if (mChallengeView == null || !mHasLayout) {
            return false;
        }

        final int layoutBottom = getLayoutBottom();
        final int challengeHeight = mChallengeView.getHeight();

        bottom = Math.max(getMinChallengeBottom(),
                Math.min(bottom, getMaxChallengeBottom()));

        float offset = 1.f - (float) (bottom - layoutBottom) /
                (challengeHeight - mChallengeBottomBound);
        mChallengeOffset = offset;
        if (offset > 0 && !mChallengeShowing) {
            setChallengeShowing(true);
        }

        mChallengeView.layout(mChallengeView.getLeft(),
                bottom - mChallengeView.getHeight(), mChallengeView.getRight(), bottom);

        mChallengeView.setAlpha(getChallengeAlpha());
        if (mScrollListener != null) {
            mScrollListener.onScrollPositionChanged(offset, mChallengeView.getTop());
        }
        postInvalidateOnAnimation();
        return true;
    }

    /**
     * The bottom edge of this SlidingChallengeLayout's coordinate system; will coincide with
     * the bottom edge of mChallengeView when the challenge is fully opened.
     */
    private int getLayoutBottom() {
        final int bottomMargin = (mChallengeView == null)
                ? 0
                : ((LayoutParams) mChallengeView.getLayoutParams()).bottomMargin;
        final int layoutBottom = getMeasuredHeight() - getPaddingBottom() - bottomMargin
                - mInsets.bottom;
        return layoutBottom;
    }

    /**
     * The bottom edge of mChallengeView; essentially, where the sliding challenge 'is'.
     */
    private int getChallengeBottom() {
        if (mChallengeView == null) return 0;

        return mChallengeView.getBottom();
    }

    /**
     * Show or hide the challenge view, animating it if necessary.
     * @param show true to show, false to hide
     */
    public void showChallenge(boolean show) {
        showChallenge(show, 0);
        if (!show) {
            // Block any drags in progress so that callers can use this to disable dragging
            // for other touch interactions.
            mBlockDrag = true;
        }
    }

    private void showChallenge(int velocity) {
        boolean show = false;
        if (Math.abs(velocity) > mMinVelocity) {
            show = velocity < 0;
        } else {
            show = mChallengeOffset >= 0.5f;
        }
        showChallenge(show, velocity);
    }

    private void showChallenge(boolean show, int velocity) {
        if (mChallengeView == null) {
            setChallengeShowing(false);
            return;
        }

        if (mHasLayout) {
            mChallengeShowingTargetState = show;
            final int layoutBottom = getLayoutBottom();
            animateChallengeTo(show ? layoutBottom :
                    layoutBottom + mChallengeView.getHeight() - mChallengeBottomBound, velocity);
        }
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams ? new LayoutParams((LayoutParams) p) :
                p instanceof MarginLayoutParams ? new LayoutParams((MarginLayoutParams) p) :
                new LayoutParams(p);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    public static class LayoutParams extends MarginLayoutParams {
        public int childType = CHILD_TYPE_NONE;
        public static final int CHILD_TYPE_NONE = 0;
        public static final int CHILD_TYPE_CHALLENGE = 2;
        public static final int CHILD_TYPE_SCRIM = 4;
        public static final int CHILD_TYPE_WIDGETS = 5;
        public static final int CHILD_TYPE_EXPAND_CHALLENGE_HANDLE = 6;

        public int maxHeight;

        public LayoutParams() {
            this(MATCH_PARENT, WRAP_CONTENT);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(android.view.ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(LayoutParams source) {
            super(source);

            childType = source.childType;
        }

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            final TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.SlidingChallengeLayout_Layout);
            childType = a.getInt(R.styleable.SlidingChallengeLayout_Layout_layout_childType,
                    CHILD_TYPE_NONE);
            maxHeight = a.getDimensionPixelSize(
                    R.styleable.SlidingChallengeLayout_Layout_layout_maxHeight, 0);
            a.recycle();
        }
    }
}
