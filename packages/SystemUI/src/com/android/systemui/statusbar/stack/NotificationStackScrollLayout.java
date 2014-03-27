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

package com.android.systemui.statusbar.stack;

import android.content.Context;
import android.content.res.Configuration;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.OverScroller;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;

/**
 * A layout which handles a dynamic amount of notifications and presents them in a scrollable stack.
 */
public class NotificationStackScrollLayout extends ViewGroup
        implements SwipeHelper.Callback, ExpandHelper.Callback {

    private static final String TAG = "NotificationStackScrollLayout";
    private static final boolean DEBUG = false;

    /**
     * Sentinel value for no current active pointer. Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    private SwipeHelper mSwipeHelper;
    private boolean mAllowScrolling = true;
    private int mCurrentStackHeight = Integer.MAX_VALUE;
    private int mOwnScrollY;
    private int mMaxLayoutHeight;

    private VelocityTracker mVelocityTracker;
    private OverScroller mScroller;
    private int mTouchSlop;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mOverscrollDistance;
    private int mOverflingDistance;
    private boolean mIsBeingDragged;
    private int mLastMotionY;
    private int mActivePointerId;

    private int mSidePaddings;
    private Paint mDebugPaint;
    private int mBackgroundRoundedRectCornerRadius;
    private int mContentHeight;
    private int mCollapsedSize;
    private int mBottomStackPeekSize;
    private int mEmptyMarginBottom;
    private int mPaddingBetweenElements;

    /**
     * The algorithm which calculates the properties for our children
     */
    private StackScrollAlgorithm mStackScrollAlgorithm;

    /**
     * The current State this Layout is in
     */
    private StackScrollState mCurrentStackScrollState;

    public NotificationStackScrollLayout(Context context) {
        this(context, null);
    }

    public NotificationStackScrollLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationStackScrollLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationStackScrollLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initView(context);
        if (DEBUG) {
            setWillNotDraw(false);
            mDebugPaint = new Paint();
            mDebugPaint.setColor(0xffff0000);
            mDebugPaint.setStrokeWidth(2);
            mDebugPaint.setStyle(Paint.Style.STROKE);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (DEBUG) {
            int y = mCollapsedSize;
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = (int) (getLayoutHeight() - mBottomStackPeekSize - mCollapsedSize);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = (int) getLayoutHeight();
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
        }
    }

    private void initView(Context context) {
        mScroller = new OverScroller(getContext());
        setFocusable(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverscrollDistance = configuration.getScaledOverscrollDistance();
        mOverflingDistance = configuration.getScaledOverflingDistance();
        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, pagingTouchSlop);

        mSidePaddings = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_side_padding);
        mBackgroundRoundedRectCornerRadius = context.getResources()
                .getDimensionPixelSize(
                        com.android.internal.R.dimen.notification_quantum_rounded_rect_radius);
        mCollapsedSize = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_row_min_height);
        mBottomStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_peek_amount);
        mEmptyMarginBottom = context.getResources().getDimensionPixelSize(
                R.dimen.notification_stack_margin_bottom);
        // currently the padding is in the elements themself
        mPaddingBetweenElements = 0;
        mStackScrollAlgorithm = new StackScrollAlgorithm(context);
        mCurrentStackScrollState = null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int size = MeasureSpec.getSize(widthMeasureSpec);
        int childMeasureSpec = MeasureSpec.makeMeasureSpec(size - 2 * mSidePaddings, mode);
        measureChildren(childMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

        // we layout all our children centered on the top
        float centerX = getWidth() / 2.0f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float width = child.getMeasuredWidth();
            float height = child.getMeasuredHeight();
            int oldWidth = child.getWidth();
            int oldHeight = child.getHeight();
            child.layout((int) (centerX - width / 2.0f),
                    0,
                    (int) (centerX + width / 2.0f),
                    (int) height);
            updateChildOutline(child, width, height, oldWidth, oldHeight);
        }
        setMaxLayoutHeight(getHeight() - mEmptyMarginBottom);
        updateScrollPositionIfNecessary();
        updateChildren();
        updateContentHeight();
    }

    private void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
        updateAlgorithmHeight();
    }

    private void updateAlgorithmHeight() {
        mStackScrollAlgorithm.setLayoutHeight(getLayoutHeight());
    }

    /**
     * Updates the children views according to the stack scroll algorithm. Call this whenever
     * modifications to {@link #mOwnScrollY} are performed to reflect it in the view layout.
     */
    private void updateChildren() {
        if (!isCurrentlyAnimating()) {
            if (mCurrentStackScrollState == null) {
                mCurrentStackScrollState = new StackScrollState(this);
            }
            mCurrentStackScrollState.setScrollY(mOwnScrollY);
            mStackScrollAlgorithm.getStackScrollState(mCurrentStackScrollState);
            mCurrentStackScrollState.apply();
            mOwnScrollY = mCurrentStackScrollState.getScrollY();
        } else {
            // TODO: handle animation
        }
    }

    private boolean isCurrentlyAnimating() {
        return false;
    }

    private void updateChildOutline(View child,
                                    float width,
                                    float height,
                                    int oldWidth,
                                    int oldHeight) {
        // The children currently have paddings inside themselfs because of the expansion
        // visualization. In order for the shadows to work correctly we have to set the correct
        // outline.
        View container = child.findViewById(R.id.container);
        if (container != null && (oldWidth != width || oldHeight != height)) {
            Outline outline = getOutlineForSize(container.getLeft(),
                    container.getTop(),
                    container.getWidth(),
                    container.getHeight());
            child.setOutline(outline);
        }
    }

    private Outline getOutlineForSize(int leftInset, int topInset, int width, int height) {
        Outline result = new Outline();
        result.setRoundRect(leftInset, topInset, leftInset + width, topInset + height,
                mBackgroundRoundedRectCornerRadius);
        return result;
    }

    private void updateScrollPositionIfNecessary() {
        int scrollRange = getScrollRange();
        if (scrollRange < mOwnScrollY) {
            mOwnScrollY = scrollRange;
        }
    }

    public void setCurrentStackHeight(int currentStackHeight) {
        this.mCurrentStackHeight = currentStackHeight;
        updateAlgorithmHeight();
        updateChildren();
    }

    /**
     * Get the current height of the view. This is at most the size of the view given by a the
     * layout but it can also be made smaller by setting {@link #mCurrentStackHeight}
     *
     * @return either the layout height or the externally defined height, whichever is smaller
     */
    private float getLayoutHeight() {
        return Math.min(mMaxLayoutHeight, mCurrentStackHeight);
    }

    public void setLongPressListener(View.OnLongClickListener listener) {
        mSwipeHelper.setLongPressListener(listener);
    }

    public void onChildDismissed(View v) {
        if (DEBUG) Log.v(TAG, "onChildDismissed: " + v);
        final View veto = v.findViewById(R.id.veto);
        if (veto != null && veto.getVisibility() != View.GONE) {
            veto.performClick();
        }
        allowScrolling(true);
    }

    public void onBeginDrag(View v) {
        allowScrolling(false);
    }

    public void onDragCancelled(View v) {
        allowScrolling(true);
    }

    public View getChildAtPosition(MotionEvent ev) {
        return getChildAtPosition(ev.getX(), ev.getY());
    }

    public View getChildAtRawPosition(float touchX, float touchY) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return getChildAtPosition(touchX - location[0],touchY - location[1]);
    }

    public View getChildAtPosition(float touchX, float touchY) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            View slidingChild = getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE) {
                continue;
            }
            float top = slidingChild.getTranslationY();
            float bottom = top + slidingChild.getMeasuredHeight();
            int left = slidingChild.getLeft();
            int right = slidingChild.getRight();

            if (touchY >= top && touchY <= bottom && touchX >= left && touchX <= right) {
                return slidingChild;
            }
        }
        return null;
    }

    public boolean canChildBeExpanded(View v) {
        return v instanceof ExpandableNotificationRow
                && ((ExpandableNotificationRow) v).isExpandable();
    }

    public void setUserExpandedChild(View v, boolean userExpanded) {
        if (v instanceof ExpandableNotificationRow) {
            ((ExpandableNotificationRow) v).setUserExpanded(userExpanded);
        }
    }

    public void setUserLockedChild(View v, boolean userLocked) {
        if (v instanceof ExpandableNotificationRow) {
            ((ExpandableNotificationRow) v).setUserLocked(userLocked);
        }
    }

    public View getChildContentView(View v) {
        return v;
    }

    public boolean canChildBeDismissed(View v) {
        final View veto = v.findViewById(R.id.veto);
        return (veto != null && veto.getVisibility() != View.GONE);
    }

    private void allowScrolling(boolean allow) {
        mAllowScrolling = allow;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
        initView(getContext());
    }

    public void dismissRowAnimated(View child, int vel) {
        mSwipeHelper.dismissChild(child, vel);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean scrollerWantsIt = false;
        if (mAllowScrolling) {
            scrollerWantsIt = onScrollTouch(ev);
        }
        boolean horizontalSwipeWantsIt = false;
        if (!mIsBeingDragged) {
            horizontalSwipeWantsIt = mSwipeHelper.onTouchEvent(ev);
        }
        return horizontalSwipeWantsIt || scrollerWantsIt || super.onTouchEvent(ev);
    }

    private boolean onScrollTouch(MotionEvent ev) {
        initVelocityTrackerIfNotExists();
        mVelocityTracker.addMovement(ev);

        final int action = ev.getAction();

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                if (getChildCount() == 0) {
                    return false;
                }
                boolean isBeingDragged = !mScroller.isFinished();
                setIsBeingDragged(isBeingDragged);
                if (isBeingDragged) {
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }

                /*
                 * If being flinged and user touches, stop the fling. isFinished
                 * will be false if being flinged.
                 */
                if (!mScroller.isFinished()) {
                    mScroller.abortAnimation();
                }

                // Remember where the motion event started
                mLastMotionY = (int) ev.getY();
                mActivePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                if (activePointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=" + mActivePointerId + " in onTouchEvent");
                    break;
                }

                final int y = (int) ev.getY(activePointerIndex);
                int deltaY = mLastMotionY - y;
                if (!mIsBeingDragged && Math.abs(deltaY) > mTouchSlop) {
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    setIsBeingDragged(true);
                    if (deltaY > 0) {
                        deltaY -= mTouchSlop;
                    } else {
                        deltaY += mTouchSlop;
                    }
                }
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    mLastMotionY = y;

                    final int oldX = mScrollX;
                    final int oldY = mOwnScrollY;
                    final int range = getScrollRange();
                    final int overscrollMode = getOverScrollMode();
                    final boolean canOverscroll = overscrollMode == OVER_SCROLL_ALWAYS ||
                            (overscrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && range > 0);

                    // Calling overScrollBy will call onOverScrolled, which
                    // calls onScrollChanged if applicable.
                    if (overScrollBy(0, deltaY, 0, mOwnScrollY,
                            0, range, 0, mOverscrollDistance, true)) {
                        // Break our velocity if we hit a scroll barrier.
                        mVelocityTracker.clear();
                    }
                    // TODO: Overscroll
//                    if (canOverscroll) {
//                        final int pulledToY = oldY + deltaY;
//                        if (pulledToY < 0) {
//                            mEdgeGlowTop.onPull((float) deltaY / getHeight());
//                            if (!mEdgeGlowBottom.isFinished()) {
//                                mEdgeGlowBottom.onRelease();
//                            }
//                        } else if (pulledToY > range) {
//                            mEdgeGlowBottom.onPull((float) deltaY / getHeight());
//                            if (!mEdgeGlowTop.isFinished()) {
//                                mEdgeGlowTop.onRelease();
//                            }
//                        }
//                        if (mEdgeGlowTop != null
//                                && (!mEdgeGlowTop.isFinished() || !mEdgeGlowBottom.isFinished())){
//                            postInvalidateOnAnimation();
//                        }
//                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mIsBeingDragged) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    int initialVelocity = (int) velocityTracker.getYVelocity(mActivePointerId);

                    if (getChildCount() > 0) {
                        if ((Math.abs(initialVelocity) > mMinimumVelocity)) {
                            fling(-initialVelocity);
                        } else {
                            if (mScroller.springBack(mScrollX, mOwnScrollY, 0, 0, 0,
                                    getScrollRange())) {
                                postInvalidateOnAnimation();
                            }
                        }
                    }

                    mActivePointerId = INVALID_POINTER;
                    endDrag();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mIsBeingDragged && getChildCount() > 0) {
                    if (mScroller.springBack(mScrollX, mOwnScrollY, 0, 0, 0, getScrollRange())) {
                        postInvalidateOnAnimation();
                    }
                    mActivePointerId = INVALID_POINTER;
                    endDrag();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mLastMotionY = (int) ev.getY(index);
                mActivePointerId = ev.getPointerId(index);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                mLastMotionY = (int) ev.getY(ev.findPointerIndex(mActivePointerId));
                break;
        }
        return true;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >>
                MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            // TODO: Make this decision more intelligent.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionY = (int) ev.getY(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    private void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            // This is called at drawing time by ViewGroup.
            int oldX = mScrollX;
            int oldY = mOwnScrollY;
            int x = mScroller.getCurrX();
            int y = mScroller.getCurrY();

            if (oldX != x || oldY != y) {
                final int range = getScrollRange();
                final int overscrollMode = getOverScrollMode();
                final boolean canOverscroll = overscrollMode == OVER_SCROLL_ALWAYS ||
                        (overscrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && range > 0);

                overScrollBy(x - oldX, y - oldY, oldX, oldY, 0, range,
                        0, mOverflingDistance, false);
                onScrollChanged(mScrollX, mOwnScrollY, oldX, oldY);

                if (canOverscroll) {
                    // TODO: Overscroll
//                    if (y < 0 && oldY >= 0) {
//                        mEdgeGlowTop.onAbsorb((int) mScroller.getCurrVelocity());
//                    } else if (y > range && oldY <= range) {
//                        mEdgeGlowBottom.onAbsorb((int) mScroller.getCurrVelocity());
//                    }
                }
                updateChildren();
            }

            // Keep on drawing until the animation has finished.
            postInvalidateOnAnimation();
        }
    }

    public void customScrollBy(int y) {
        mOwnScrollY += y;
        updateChildren();
    }

    public void customScrollTo(int y) {
        mOwnScrollY = y;
        updateChildren();
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY,
                                  boolean clampedX, boolean clampedY) {
        // Treat animating scrolls differently; see #computeScroll() for why.
        if (!mScroller.isFinished()) {
            final int oldX = mScrollX;
            final int oldY = mOwnScrollY;
            mScrollX = scrollX;
            mOwnScrollY = scrollY;
            invalidateParentIfNeeded();
            onScrollChanged(mScrollX, mOwnScrollY, oldX, oldY);
            if (clampedY) {
                mScroller.springBack(mScrollX, mOwnScrollY, 0, 0, 0, getScrollRange());
            }
            updateChildren();
        } else {
            customScrollTo(scrollY);
            scrollTo(scrollX, mScrollY);
        }
    }

    private int getScrollRange() {
        int scrollRange = 0;
        if (getChildCount() > 0) {
            int contentHeight = getContentHeight();
            scrollRange = Math.max(0,
                    contentHeight - mMaxLayoutHeight + mCollapsedSize);
        }
        return scrollRange;
    }

    private int getContentHeight() {
        return mContentHeight;
    }

    private void updateContentHeight() {
        int height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            height += child.getHeight();
            if (i < getChildCount()-1) {
                height += mPaddingBetweenElements;
            }
        }
        mContentHeight = height;
    }

    /**
     * Fling the scroll view
     *
     * @param velocityY The initial velocity in the Y direction. Positive
     *                  numbers mean that the finger/cursor is moving down the screen,
     *                  which means we want to scroll towards the top.
     */
    private void fling(int velocityY) {
        if (getChildCount() > 0) {
            int height = (int) getLayoutHeight();
            int bottom = getContentHeight();

            mScroller.fling(mScrollX, mOwnScrollY, 0, velocityY, 0, 0, 0,
                    Math.max(0, bottom - height), 0, height/2);

            postInvalidateOnAnimation();
        }
    }

    private void endDrag() {
        setIsBeingDragged(false);

        recycleVelocityTracker();

        // TODO: Overscroll
//        if (mEdgeGlowTop != null) {
//            mEdgeGlowTop.onRelease();
//            mEdgeGlowBottom.onRelease();
//        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean scrollWantsIt = false;
        if (mAllowScrolling) {
            scrollWantsIt = onInterceptTouchEventScroll(ev);
        }
        boolean swipeWantsIt = false;
        if (!mIsBeingDragged) {
            swipeWantsIt = mSwipeHelper.onInterceptTouchEvent(ev);
        }
        return swipeWantsIt || scrollWantsIt ||
                super.onInterceptTouchEvent(ev);
    }

    private boolean onInterceptTouchEventScroll(MotionEvent ev) {
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onMotionEvent will be called and we do the actual
         * scrolling there.
         */

        /*
        * Shortcut the most recurring case: the user is in the dragging
        * state and he is moving his finger.  We want to intercept this
        * motion.
        */
        final int action = ev.getAction();
        if ((action == MotionEvent.ACTION_MOVE) && (mIsBeingDragged)) {
            return true;
        }

        /*
         * Don't try to intercept touch if we can't scroll anyway.
         */
        if (mOwnScrollY == 0 && getScrollRange() == 0) {
            return false;
        }

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                /*
                 * mIsBeingDragged == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from his original down touch.
                 */

                /*
                * Locally do absolute value. mLastMotionY is set to the y value
                * of the down event.
                */
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    // If we don't have a valid id, the touch down wasn't on content.
                    break;
                }

                final int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=" + activePointerId
                            + " in onInterceptTouchEvent");
                    break;
                }

                final int y = (int) ev.getY(pointerIndex);
                final int yDiff = Math.abs(y - mLastMotionY);
                if (yDiff > mTouchSlop) {
                    setIsBeingDragged(true);
                    mLastMotionY = y;
                    initVelocityTrackerIfNotExists();
                    mVelocityTracker.addMovement(ev);
                    final ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                final int y = (int) ev.getY();
                if (getChildAtPosition(ev.getX(), y) == null) {
                    setIsBeingDragged(false);
                    recycleVelocityTracker();
                    break;
                }

                /*
                 * Remember location of down touch.
                 * ACTION_DOWN always refers to pointer index 0.
                 */
                mLastMotionY = y;
                mActivePointerId = ev.getPointerId(0);

                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                /*
                * If being flinged and user touches the screen, initiate drag;
                * otherwise don't.  mScroller.isFinished should be false when
                * being flinged.
                */
                boolean isBeingDragged = !mScroller.isFinished();
                setIsBeingDragged(isBeingDragged);
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                /* Release the drag */
                setIsBeingDragged(false);
                mActivePointerId = INVALID_POINTER;
                recycleVelocityTracker();
                if (mScroller.springBack(mScrollX, mOwnScrollY, 0, 0, 0, getScrollRange())) {
                    postInvalidateOnAnimation();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        /*
        * The only time we want to intercept motion events is if we are in the
        * drag mode.
        */
        return mIsBeingDragged;
    }

    private void setIsBeingDragged(boolean isDragged) {
        mIsBeingDragged = isDragged;
        if (isDragged) {
            mSwipeHelper.removeLongPressCallback();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            mSwipeHelper.removeLongPressCallback();
        }
    }
}
