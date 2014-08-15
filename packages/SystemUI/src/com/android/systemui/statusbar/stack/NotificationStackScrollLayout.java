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
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.widget.OverScroller;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.DismissView;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.SpeedBumpView;
import com.android.systemui.statusbar.policy.ScrollAdapter;
import com.android.systemui.statusbar.stack.StackScrollState.ViewState;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * A layout which handles a dynamic amount of notifications and presents them in a scrollable stack.
 */
public class NotificationStackScrollLayout extends ViewGroup
        implements SwipeHelper.Callback, ExpandHelper.Callback, ScrollAdapter,
        ExpandableView.OnHeightChangedListener {

    private static final String TAG = "NotificationStackScrollLayout";
    private static final boolean DEBUG = false;
    private static final float RUBBER_BAND_FACTOR_NORMAL = 0.35f;
    private static final float RUBBER_BAND_FACTOR_AFTER_EXPAND = 0.15f;
    private static final float RUBBER_BAND_FACTOR_ON_PANEL_EXPAND = 0.21f;

    /**
     * Sentinel value for no current active pointer. Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    private ExpandHelper mExpandHelper;
    private SwipeHelper mSwipeHelper;
    private boolean mSwipingInProgress;
    private int mCurrentStackHeight = Integer.MAX_VALUE;
    private int mOwnScrollY;
    private int mMaxLayoutHeight;

    private VelocityTracker mVelocityTracker;
    private OverScroller mScroller;
    private int mTouchSlop;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mOverflingDistance;
    private float mMaxOverScroll;
    private boolean mIsBeingDragged;
    private int mLastMotionY;
    private int mDownX;
    private int mActivePointerId;

    private int mSidePaddings;
    private Paint mDebugPaint;
    private int mContentHeight;
    private int mCollapsedSize;
    private int mBottomStackSlowDownHeight;
    private int mBottomStackPeekSize;
    private int mPaddingBetweenElements;
    private int mPaddingBetweenElementsDimmed;
    private int mPaddingBetweenElementsNormal;
    private int mTopPadding;
    private int mCollapseSecondCardPadding;

    /**
     * The algorithm which calculates the properties for our children
     */
    private StackScrollAlgorithm mStackScrollAlgorithm;

    /**
     * The current State this Layout is in
     */
    private StackScrollState mCurrentStackScrollState = new StackScrollState(this);
    private AmbientState mAmbientState = new AmbientState();
    private ArrayList<View> mChildrenToAddAnimated = new ArrayList<View>();
    private ArrayList<View> mChildrenToRemoveAnimated = new ArrayList<View>();
    private ArrayList<View> mSnappedBackChildren = new ArrayList<View>();
    private ArrayList<View> mDragAnimPendingChildren = new ArrayList<View>();
    private ArrayList<View> mChildrenChangingPositions = new ArrayList<View>();
    private HashSet<View> mFromMoreCardAdditions = new HashSet<>();
    private ArrayList<AnimationEvent> mAnimationEvents
            = new ArrayList<AnimationEvent>();
    private ArrayList<View> mSwipedOutViews = new ArrayList<View>();
    private final StackStateAnimator mStateAnimator = new StackStateAnimator(this);
    private boolean mAnimationsEnabled;
    private boolean mChangePositionInProgress;

    /**
     * The raw amount of the overScroll on the top, which is not rubber-banded.
     */
    private float mOverScrolledTopPixels;

    /**
     * The raw amount of the overScroll on the bottom, which is not rubber-banded.
     */
    private float mOverScrolledBottomPixels;

    private OnChildLocationsChangedListener mListener;
    private OnOverscrollTopChangedListener mOverscrollTopChangedListener;
    private ExpandableView.OnHeightChangedListener mOnHeightChangedListener;
    private boolean mNeedsAnimation;
    private boolean mTopPaddingNeedsAnimation;
    private boolean mDimmedNeedsAnimation;
    private boolean mHideSensitiveNeedsAnimation;
    private boolean mDarkNeedsAnimation;
    private boolean mActivateNeedsAnimation;
    private boolean mGoToFullShadeNeedsAnimation;
    private boolean mIsExpanded = true;
    private boolean mChildrenUpdateRequested;
    private SpeedBumpView mSpeedBumpView;
    private boolean mIsExpansionChanging;
    private boolean mExpandingNotification;
    private boolean mExpandedInThisMotion;
    private boolean mScrollingEnabled;
    private DismissView mDismissView;
    private EmptyShadeView mEmptyShadeView;
    private boolean mDismissAllInProgress;

    /**
     * Was the scroller scrolled to the top when the down motion was observed?
     */
    private boolean mScrolledToTopOnFirstDown;

    /**
     * The minimal amount of over scroll which is needed in order to switch to the quick settings
     * when over scrolling on a expanded card.
     */
    private float mMinTopOverScrollToEscape;
    private int mIntrinsicPadding;
    private int mNotificationTopPadding;
    private float mTopPaddingOverflow;
    private boolean mDontReportNextOverScroll;
    private boolean mRequestViewResizeAnimationOnLayout;
    private boolean mNeedViewResizeAnimation;

    /**
     * The maximum scrollPosition which we are allowed to reach when a notification was expanded.
     * This is needed to avoid scrolling too far after the notification was collapsed in the same
     * motion.
     */
    private int mMaxScrollAfterExpand;
    private SwipeHelper.LongPressListener mLongPressListener;

    /**
     * Should in this touch motion only be scrolling allowed? It's true when the scroller was
     * animating.
     */
    private boolean mOnlyScrollingInThisMotion;
    private ViewGroup mScrollView;
    private boolean mInterceptDelegateEnabled;
    private boolean mDelegateToScrollView;
    private boolean mDisallowScrollingInThisMotion;
    private long mGoToFullShadeDelay;

    private ViewTreeObserver.OnPreDrawListener mChildrenUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            updateChildren();
            mChildrenUpdateRequested = false;
            getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        }
    };

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
        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        mExpandHelper = new ExpandHelper(getContext(), this,
                minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setScrollAdapter(this);

        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, getContext());
        mSwipeHelper.setLongPressListener(mLongPressListener);
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
            y = (int) (getLayoutHeight() - mBottomStackPeekSize
                    - mBottomStackSlowDownHeight);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = (int) (getLayoutHeight() - mBottomStackPeekSize);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = (int) getLayoutHeight();
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = getHeight() - getEmptyBottomMargin();
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
        }
    }

    private void initView(Context context) {
        mScroller = new OverScroller(getContext());
        setFocusable(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setClipChildren(false);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverflingDistance = configuration.getScaledOverflingDistance();

        mSidePaddings = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_side_padding);
        mCollapsedSize = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_min_height);
        mBottomStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_peek_amount);
        mStackScrollAlgorithm = new StackScrollAlgorithm(context);
        mStackScrollAlgorithm.setDimmed(mAmbientState.isDimmed());
        mPaddingBetweenElementsDimmed = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_padding_dimmed);
        mPaddingBetweenElementsNormal = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_padding);
        updatePadding(mAmbientState.isDimmed());
        mMinTopOverScrollToEscape = getResources().getDimensionPixelSize(
                R.dimen.min_top_overscroll_to_qs);
        mNotificationTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notifications_top_padding);
        mCollapseSecondCardPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_collapse_second_card_padding);
    }

    private void updatePadding(boolean dimmed) {
        mPaddingBetweenElements = dimmed && mStackScrollAlgorithm.shouldScaleDimmed()
                ? mPaddingBetweenElementsDimmed
                : mPaddingBetweenElementsNormal;
        mBottomStackSlowDownHeight = mStackScrollAlgorithm.getBottomStackSlowDownLength();
        updateContentHeight();
        notifyHeightChangeListener(null);
    }

    private void notifyHeightChangeListener(ExpandableView view) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(view);
        }
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
            child.layout((int) (centerX - width / 2.0f),
                    0,
                    (int) (centerX + width / 2.0f),
                    (int) height);
        }
        setMaxLayoutHeight(getHeight());
        updateContentHeight();
        clampScrollPosition();
        requestAnimationOnViewResize();
        requestChildrenUpdate();
    }

    private void requestAnimationOnViewResize() {
        if (mRequestViewResizeAnimationOnLayout && mIsExpanded && mAnimationsEnabled) {
            mNeedViewResizeAnimation = true;
            mNeedsAnimation = true;
        }
        mRequestViewResizeAnimationOnLayout = false;
    }

    public void updateSpeedBumpIndex(int newIndex) {
        int currentIndex = indexOfChild(mSpeedBumpView);

        // If we are currently layouted before the new speed bump index, we have to decrease it.
        boolean validIndex = newIndex > 0;
        if (newIndex > getChildCount() - 1) {
            validIndex = false;
            newIndex = -1;
        }
        if (validIndex && currentIndex != newIndex) {
            changeViewPosition(mSpeedBumpView, newIndex);
        }
        updateSpeedBump(validIndex);
        mAmbientState.setSpeedBumpIndex(newIndex);
    }

    public void setChildLocationsChangedListener(OnChildLocationsChangedListener listener) {
        mListener = listener;
    }

    /**
     * Returns the location the given child is currently rendered at.
     *
     * @param child the child to get the location for
     * @return one of {@link ViewState}'s <code>LOCATION_*</code> constants
     */
    public int getChildLocation(View child) {
        ViewState childViewState = mCurrentStackScrollState.getViewStateForView(child);
        if (childViewState == null) {
            return ViewState.LOCATION_UNKNOWN;
        }
        return childViewState.location;
    }

    private void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
        updateAlgorithmHeightAndPadding();
    }

    private void updateAlgorithmHeightAndPadding() {
        mStackScrollAlgorithm.setLayoutHeight(getLayoutHeight());
        mStackScrollAlgorithm.setTopPadding(mTopPadding);
    }

    /**
     * @return whether the height of the layout needs to be adapted, in order to ensure that the
     *         last child is not in the bottom stack.
     */
    private boolean needsHeightAdaption() {
        return getNotGoneChildCount() > 1;
    }

    /**
     * Updates the children views according to the stack scroll algorithm. Call this whenever
     * modifications to {@link #mOwnScrollY} are performed to reflect it in the view layout.
     */
    private void updateChildren() {
        mAmbientState.setScrollY(mOwnScrollY);
        mStackScrollAlgorithm.getStackScrollState(mAmbientState, mCurrentStackScrollState);
        if (!isCurrentlyAnimating() && !mNeedsAnimation) {
            applyCurrentState();
        } else {
            startAnimationToState();
        }
    }

    private void requestChildrenUpdate() {
        if (!mChildrenUpdateRequested) {
            getViewTreeObserver().addOnPreDrawListener(mChildrenUpdater);
            mChildrenUpdateRequested = true;
            invalidate();
        }
    }

    private boolean isCurrentlyAnimating() {
        return mStateAnimator.isRunning();
    }

    private void clampScrollPosition() {
        int scrollRange = getScrollRange();
        if (scrollRange < mOwnScrollY) {
            mOwnScrollY = scrollRange;
        }
    }

    public int getTopPadding() {
        return mTopPadding;
    }

    private void setTopPadding(int topPadding, boolean animate) {
        if (mTopPadding != topPadding) {
            mTopPadding = topPadding;
            updateAlgorithmHeightAndPadding();
            updateContentHeight();
            if (animate && mAnimationsEnabled && mIsExpanded) {
                mTopPaddingNeedsAnimation = true;
                mNeedsAnimation =  true;
            }
            requestChildrenUpdate();
            notifyHeightChangeListener(null);
        }
    }

    /**
     * Update the height of the stack to a new height.
     *
     * @param height the new height of the stack
     */
    public void setStackHeight(float height) {
        setIsExpanded(height > 0.0f);
        int newStackHeight = (int) height;
        int minStackHeight = getMinStackHeight();
        int stackHeight;
        if (newStackHeight - mTopPadding >= minStackHeight || getNotGoneChildCount() == 0) {
            setTranslationY(mTopPaddingOverflow);
            stackHeight = newStackHeight;
        } else {

            // We did not reach the position yet where we actually start growing,
            // so we translate the stack upwards.
            int translationY = (newStackHeight - minStackHeight);
            // A slight parallax effect is introduced in order for the stack to catch up with
            // the top card.
            float partiallyThere = (float) (newStackHeight - mTopPadding) / minStackHeight;
            partiallyThere = Math.max(0, partiallyThere);
            translationY += (1 - partiallyThere) * (mBottomStackPeekSize +
                    mCollapseSecondCardPadding);
            setTranslationY(translationY - mTopPadding);
            stackHeight = (int) (height - (translationY - mTopPadding));
        }
        if (stackHeight != mCurrentStackHeight) {
            mCurrentStackHeight = stackHeight;
            updateAlgorithmHeightAndPadding();
            requestChildrenUpdate();
        }
    }

    /**
     * Get the current height of the view. This is at most the msize of the view given by a the
     * layout but it can also be made smaller by setting {@link #mCurrentStackHeight}
     *
     * @return either the layout height or the externally defined height, whichever is smaller
     */
    private int getLayoutHeight() {
        return Math.min(mMaxLayoutHeight, mCurrentStackHeight);
    }

    public int getItemHeight() {
        return mCollapsedSize;
    }

    public int getBottomStackPeekSize() {
        return mBottomStackPeekSize;
    }

    public int getCollapseSecondCardPadding() {
        return mCollapseSecondCardPadding;
    }

    public void setLongPressListener(SwipeHelper.LongPressListener listener) {
        mSwipeHelper.setLongPressListener(listener);
        mLongPressListener = listener;
    }

    public void setScrollView(ViewGroup scrollView) {
        mScrollView = scrollView;
    }

    public void setInterceptDelegateEnabled(boolean interceptDelegateEnabled) {
        mInterceptDelegateEnabled = interceptDelegateEnabled;
    }

    public void onChildDismissed(View v) {
        if (mDismissAllInProgress) {
            return;
        }
        if (DEBUG) Log.v(TAG, "onChildDismissed: " + v);
        final View veto = v.findViewById(R.id.veto);
        if (veto != null && veto.getVisibility() != View.GONE) {
            veto.performClick();
        }
        setSwipingInProgress(false);
        if (mDragAnimPendingChildren.contains(v)) {
            // We start the swipe and finish it in the same frame, we don't want any animation
            // for the drag
            mDragAnimPendingChildren.remove(v);
        }
        mSwipedOutViews.add(v);
        mAmbientState.onDragFinished(v);
    }

    @Override
    public void onChildSnappedBack(View animView) {
        mAmbientState.onDragFinished(animView);
        if (!mDragAnimPendingChildren.contains(animView)) {
            if (mAnimationsEnabled) {
                mSnappedBackChildren.add(animView);
                mNeedsAnimation = true;
            }
            requestChildrenUpdate();
        } else {
            // We start the swipe and snap back in the same frame, we don't want any animation
            mDragAnimPendingChildren.remove(animView);
        }
    }

    @Override
    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        return false;
    }

    public void onBeginDrag(View v) {
        setSwipingInProgress(true);
        mAmbientState.onBeginDrag(v);
        if (mAnimationsEnabled) {
            mDragAnimPendingChildren.add(v);
            mNeedsAnimation = true;
        }
        requestChildrenUpdate();
    }

    public void onDragCancelled(View v) {
        setSwipingInProgress(false);
    }

    public View getChildAtPosition(MotionEvent ev) {
        return getChildAtPosition(ev.getX(), ev.getY());
    }

    public ExpandableView getChildAtRawPosition(float touchX, float touchY) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return getChildAtPosition(touchX - location[0], touchY - location[1]);
    }

    public ExpandableView getChildAtPosition(float touchX, float touchY) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView slidingChild = (ExpandableView) getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE) {
                continue;
            }
            float childTop = slidingChild.getTranslationY();
            float top = childTop + slidingChild.getClipTopAmount();
            float bottom = top + slidingChild.getActualHeight();

            // Allow the full width of this view to prevent gesture conflict on Keyguard (phone and
            // camera affordance).
            int left = 0;
            int right = getWidth();

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
        removeLongPressCallback();
        requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public void expansionStateChanged(boolean isExpanding) {
        mExpandingNotification = isExpanding;
        if (!mExpandedInThisMotion) {
            mMaxScrollAfterExpand = mOwnScrollY;
            mExpandedInThisMotion = true;
        }
    }

    public void setScrollingEnabled(boolean enable) {
        mScrollingEnabled = enable;
    }

    public void setExpandingEnabled(boolean enable) {
        mExpandHelper.setEnabled(enable);
    }

    private boolean isScrollingEnabled() {
        return mScrollingEnabled;
    }

    public View getChildContentView(View v) {
        return v;
    }

    public boolean canChildBeDismissed(View v) {
        final View veto = v.findViewById(R.id.veto);
        return (veto != null && veto.getVisibility() != View.GONE);
    }

    private void setSwipingInProgress(boolean isSwiped) {
        mSwipingInProgress = isSwiped;
        if(isSwiped) {
            requestDisallowInterceptTouchEvent(true);
        }
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

    public void dismissViewAnimated(View child, Runnable endRunnable, int delay, long duration) {
        child.setClipBounds(null);
        mSwipeHelper.dismissChild(child, 0, endRunnable, delay, true, duration);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean isCancelOrUp = ev.getActionMasked() == MotionEvent.ACTION_CANCEL
                || ev.getActionMasked()== MotionEvent.ACTION_UP;
        if (mDelegateToScrollView) {
            if (isCancelOrUp) {
                mDelegateToScrollView = false;
            }
            transformTouchEvent(ev, this, mScrollView);
            return mScrollView.onTouchEvent(ev);
        }
        boolean expandWantsIt = false;
        if (!mSwipingInProgress && !mOnlyScrollingInThisMotion && isScrollingEnabled()) {
            if (isCancelOrUp) {
                mExpandHelper.onlyObserveMovements(false);
            }
            boolean wasExpandingBefore = mExpandingNotification;
            expandWantsIt = mExpandHelper.onTouchEvent(ev);
            if (mExpandedInThisMotion && !mExpandingNotification && wasExpandingBefore
                    && !mDisallowScrollingInThisMotion) {
                dispatchDownEventToScroller(ev);
            }
        }
        boolean scrollerWantsIt = false;
        if (!mSwipingInProgress && !mExpandingNotification && !mDisallowScrollingInThisMotion) {
            scrollerWantsIt = onScrollTouch(ev);
        }
        boolean horizontalSwipeWantsIt = false;
        if (!mIsBeingDragged
                && !mExpandingNotification
                && !mExpandedInThisMotion
                && !mOnlyScrollingInThisMotion) {
            horizontalSwipeWantsIt = mSwipeHelper.onTouchEvent(ev);
        }
        return horizontalSwipeWantsIt || scrollerWantsIt || expandWantsIt || super.onTouchEvent(ev);
    }

    private void dispatchDownEventToScroller(MotionEvent ev) {
        MotionEvent downEvent = MotionEvent.obtain(ev);
        downEvent.setAction(MotionEvent.ACTION_DOWN);
        onScrollTouch(downEvent);
        downEvent.recycle();
    }

    private boolean onScrollTouch(MotionEvent ev) {
        if (!isScrollingEnabled()) {
            return false;
        }
        initVelocityTrackerIfNotExists();
        mVelocityTracker.addMovement(ev);

        final int action = ev.getAction();

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                if (getChildCount() == 0 || !isInContentBounds(ev)) {
                    return false;
                }
                boolean isBeingDragged = !mScroller.isFinished();
                setIsBeingDragged(isBeingDragged);

                /*
                 * If being flinged and user touches, stop the fling. isFinished
                 * will be false if being flinged.
                 */
                if (!mScroller.isFinished()) {
                    mScroller.forceFinished(true);
                }

                // Remember where the motion event started
                mLastMotionY = (int) ev.getY();
                mDownX = (int) ev.getX();
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
                final int x = (int) ev.getX(activePointerIndex);
                int deltaY = mLastMotionY - y;
                final int xDiff = Math.abs(x - mDownX);
                final int yDiff = Math.abs(deltaY);
                if (!mIsBeingDragged && yDiff > mTouchSlop && yDiff > xDiff) {
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
                    int range = getScrollRange();
                    if (mExpandedInThisMotion) {
                        range = Math.min(range, mMaxScrollAfterExpand);
                    }

                    float scrollAmount;
                    if (deltaY < 0) {
                        scrollAmount = overScrollDown(deltaY);
                    } else {
                        scrollAmount = overScrollUp(deltaY, range);
                    }

                    // Calling overScrollBy will call onOverScrolled, which
                    // calls onScrollChanged if applicable.
                    if (scrollAmount != 0.0f) {
                        // The scrolling motion could not be compensated with the
                        // existing overScroll, we have to scroll the view
                        overScrollBy(0, (int) scrollAmount, 0, mOwnScrollY,
                                0, range, 0, getHeight() / 2, true);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mIsBeingDragged) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    int initialVelocity = (int) velocityTracker.getYVelocity(mActivePointerId);

                    if (shouldOverScrollFling(initialVelocity)) {
                        onOverScrollFling(true, initialVelocity);
                    } else {
                        if (getChildCount() > 0) {
                            if ((Math.abs(initialVelocity) > mMinimumVelocity)) {
                                float currentOverScrollTop = getCurrentOverScrollAmount(true);
                                if (currentOverScrollTop == 0.0f || initialVelocity > 0) {
                                    fling(-initialVelocity);
                                } else {
                                    onOverScrollFling(false, initialVelocity);
                                }
                            } else {
                                if (mScroller.springBack(mScrollX, mOwnScrollY, 0, 0, 0,
                                        getScrollRange())) {
                                    postInvalidateOnAnimation();
                                }
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
                mDownX = (int) ev.getX(index);
                mActivePointerId = ev.getPointerId(index);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                mLastMotionY = (int) ev.getY(ev.findPointerIndex(mActivePointerId));
                mDownX = (int) ev.getX(ev.findPointerIndex(mActivePointerId));
                break;
        }
        return true;
    }

    private void onOverScrollFling(boolean open, int initialVelocity) {
        if (mOverscrollTopChangedListener != null) {
            mOverscrollTopChangedListener.flingTopOverscroll(initialVelocity, open);
        }
        mDontReportNextOverScroll = true;
        setOverScrollAmount(0.0f, true, false);
    }

    /**
     * Perform a scroll upwards and adapt the overscroll amounts accordingly
     *
     * @param deltaY The amount to scroll upwards, has to be positive.
     * @return The amount of scrolling to be performed by the scroller,
     *         not handled by the overScroll amount.
     */
    private float overScrollUp(int deltaY, int range) {
        deltaY = Math.max(deltaY, 0);
        float currentTopAmount = getCurrentOverScrollAmount(true);
        float newTopAmount = currentTopAmount - deltaY;
        if (currentTopAmount > 0) {
            setOverScrollAmount(newTopAmount, true /* onTop */,
                    false /* animate */);
        }
        // Top overScroll might not grab all scrolling motion,
        // we have to scroll as well.
        float scrollAmount = newTopAmount < 0 ? -newTopAmount : 0.0f;
        float newScrollY = mOwnScrollY + scrollAmount;
        if (newScrollY > range) {
            if (!mExpandedInThisMotion) {
                float currentBottomPixels = getCurrentOverScrolledPixels(false);
                // We overScroll on the top
                setOverScrolledPixels(currentBottomPixels + newScrollY - range,
                        false /* onTop */,
                        false /* animate */);
            }
            mOwnScrollY = range;
            scrollAmount = 0.0f;
        }
        return scrollAmount;
    }

    /**
     * Perform a scroll downward and adapt the overscroll amounts accordingly
     *
     * @param deltaY The amount to scroll downwards, has to be negative.
     * @return The amount of scrolling to be performed by the scroller,
     *         not handled by the overScroll amount.
     */
    private float overScrollDown(int deltaY) {
        deltaY = Math.min(deltaY, 0);
        float currentBottomAmount = getCurrentOverScrollAmount(false);
        float newBottomAmount = currentBottomAmount + deltaY;
        if (currentBottomAmount > 0) {
            setOverScrollAmount(newBottomAmount, false /* onTop */,
                    false /* animate */);
        }
        // Bottom overScroll might not grab all scrolling motion,
        // we have to scroll as well.
        float scrollAmount = newBottomAmount < 0 ? newBottomAmount : 0.0f;
        float newScrollY = mOwnScrollY + scrollAmount;
        if (newScrollY < 0) {
            float currentTopPixels = getCurrentOverScrolledPixels(true);
            // We overScroll on the top
            setOverScrolledPixels(currentTopPixels - newScrollY,
                    true /* onTop */,
                    false /* animate */);
            mOwnScrollY = 0;
            scrollAmount = 0.0f;
        }
        return scrollAmount;
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
                if (y < 0 && oldY >= 0 || y > range && oldY <= range) {
                    float currVelocity = mScroller.getCurrVelocity();
                    if (currVelocity >= mMinimumVelocity) {
                        mMaxOverScroll = Math.abs(currVelocity) / 1000 * mOverflingDistance;
                    }
                }

                overScrollBy(x - oldX, y - oldY, oldX, oldY, 0, range,
                        0, (int) (mMaxOverScroll), false);
                onScrollChanged(mScrollX, mOwnScrollY, oldX, oldY);
            }

            // Keep on drawing until the animation has finished.
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected boolean overScrollBy(int deltaX, int deltaY,
            int scrollX, int scrollY,
            int scrollRangeX, int scrollRangeY,
            int maxOverScrollX, int maxOverScrollY,
            boolean isTouchEvent) {

        int newScrollY = scrollY + deltaY;

        final int top = -maxOverScrollY;
        final int bottom = maxOverScrollY + scrollRangeY;

        boolean clampedY = false;
        if (newScrollY > bottom) {
            newScrollY = bottom;
            clampedY = true;
        } else if (newScrollY < top) {
            newScrollY = top;
            clampedY = true;
        }

        onOverScrolled(0, newScrollY, false, clampedY);

        return clampedY;
    }

    /**
     * Set the amount of overScrolled pixels which will force the view to apply a rubber-banded
     * overscroll effect based on numPixels. By default this will also cancel animations on the
     * same overScroll edge.
     *
     * @param numPixels The amount of pixels to overScroll by. These will be scaled according to
     *                  the rubber-banding logic.
     * @param onTop Should the effect be applied on top of the scroller.
     * @param animate Should an animation be performed.
     */
    public void setOverScrolledPixels(float numPixels, boolean onTop, boolean animate) {
        setOverScrollAmount(numPixels * getRubberBandFactor(onTop), onTop, animate, true);
    }

    /**
     * Set the effective overScroll amount which will be directly reflected in the layout.
     * By default this will also cancel animations on the same overScroll edge.
     *
     * @param amount The amount to overScroll by.
     * @param onTop Should the effect be applied on top of the scroller.
     * @param animate Should an animation be performed.
     */
    public void setOverScrollAmount(float amount, boolean onTop, boolean animate) {
        setOverScrollAmount(amount, onTop, animate, true);
    }

    /**
     * Set the effective overScroll amount which will be directly reflected in the layout.
     *
     * @param amount The amount to overScroll by.
     * @param onTop Should the effect be applied on top of the scroller.
     * @param animate Should an animation be performed.
     * @param cancelAnimators Should running animations be cancelled.
     */
    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators) {
        setOverScrollAmount(amount, onTop, animate, cancelAnimators, isRubberbanded(onTop));
    }

    /**
     * Set the effective overScroll amount which will be directly reflected in the layout.
     *
     * @param amount The amount to overScroll by.
     * @param onTop Should the effect be applied on top of the scroller.
     * @param animate Should an animation be performed.
     * @param cancelAnimators Should running animations be cancelled.
     * @param isRubberbanded The value which will be passed to
     *                     {@link OnOverscrollTopChangedListener#onOverscrollTopChanged}
     */
    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators, boolean isRubberbanded) {
        if (cancelAnimators) {
            mStateAnimator.cancelOverScrollAnimators(onTop);
        }
        setOverScrollAmountInternal(amount, onTop, animate, isRubberbanded);
    }

    private void setOverScrollAmountInternal(float amount, boolean onTop, boolean animate,
            boolean isRubberbanded) {
        amount = Math.max(0, amount);
        if (animate) {
            mStateAnimator.animateOverScrollToAmount(amount, onTop, isRubberbanded);
        } else {
            setOverScrolledPixels(amount / getRubberBandFactor(onTop), onTop);
            mAmbientState.setOverScrollAmount(amount, onTop);
            if (onTop) {
                notifyOverscrollTopListener(amount, isRubberbanded);
            }
            requestChildrenUpdate();
        }
    }

    private void notifyOverscrollTopListener(float amount, boolean isRubberbanded) {
        mExpandHelper.onlyObserveMovements(amount > 1.0f);
        if (mDontReportNextOverScroll) {
            mDontReportNextOverScroll = false;
            return;
        }
        if (mOverscrollTopChangedListener != null) {
            mOverscrollTopChangedListener.onOverscrollTopChanged(amount, isRubberbanded);
        }
    }

    public void setOverscrollTopChangedListener(
            OnOverscrollTopChangedListener overscrollTopChangedListener) {
        mOverscrollTopChangedListener = overscrollTopChangedListener;
    }

    public float getCurrentOverScrollAmount(boolean top) {
        return mAmbientState.getOverScrollAmount(top);
    }

    public float getCurrentOverScrolledPixels(boolean top) {
        return top? mOverScrolledTopPixels : mOverScrolledBottomPixels;
    }

    private void setOverScrolledPixels(float amount, boolean onTop) {
        if (onTop) {
            mOverScrolledTopPixels = amount;
        } else {
            mOverScrolledBottomPixels = amount;
        }
    }

    private void customScrollTo(int y) {
        mOwnScrollY = y;
        updateChildren();
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        // Treat animating scrolls differently; see #computeScroll() for why.
        if (!mScroller.isFinished()) {
            final int oldX = mScrollX;
            final int oldY = mOwnScrollY;
            mScrollX = scrollX;
            mOwnScrollY = scrollY;
            if (clampedY) {
                springBack();
            } else {
                onScrollChanged(mScrollX, mOwnScrollY, oldX, oldY);
                invalidateParentIfNeeded();
                updateChildren();
                float overScrollTop = getCurrentOverScrollAmount(true);
                if (mOwnScrollY < 0) {
                    notifyOverscrollTopListener(-mOwnScrollY, isRubberbanded(true));
                } else {
                    notifyOverscrollTopListener(overScrollTop, isRubberbanded(true));
                }
            }
        } else {
            customScrollTo(scrollY);
            scrollTo(scrollX, mScrollY);
        }
    }

    private void springBack() {
        int scrollRange = getScrollRange();
        boolean overScrolledTop = mOwnScrollY <= 0;
        boolean overScrolledBottom = mOwnScrollY >= scrollRange;
        if (overScrolledTop || overScrolledBottom) {
            boolean onTop;
            float newAmount;
            if (overScrolledTop) {
                onTop = true;
                newAmount = -mOwnScrollY;
                mOwnScrollY = 0;
                mDontReportNextOverScroll = true;
            } else {
                onTop = false;
                newAmount = mOwnScrollY - scrollRange;
                mOwnScrollY = scrollRange;
            }
            setOverScrollAmount(newAmount, onTop, false);
            setOverScrollAmount(0.0f, onTop, true);
            mScroller.forceFinished(true);
        }
    }

    private int getScrollRange() {
        int scrollRange = 0;
        ExpandableView firstChild = (ExpandableView) getFirstChildNotGone();
        if (firstChild != null) {
            int contentHeight = getContentHeight();
            int firstChildMaxExpandHeight = getMaxExpandHeight(firstChild);
            scrollRange = Math.max(0, contentHeight - mMaxLayoutHeight + mBottomStackPeekSize
                    + mBottomStackSlowDownHeight);
            if (scrollRange > 0) {
                View lastChild = getLastChildNotGone();
                // We want to at least be able collapse the first item and not ending in a weird
                // end state.
                scrollRange = Math.max(scrollRange, firstChildMaxExpandHeight - mCollapsedSize);
            }
        }
        return scrollRange;
    }

    /**
     * @return the first child which has visibility unequal to GONE
     */
    private View getFirstChildNotGone() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                return child;
            }
        }
        return null;
    }

    /**
     * @return The first child which has visibility unequal to GONE which is currently below the
     *         given translationY or equal to it.
     */
    private View getFirstChildBelowTranlsationY(float translationY) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE && child.getTranslationY() >= translationY) {
                return child;
            }
        }
        return null;
    }

    /**
     * @return the last child which has visibility unequal to GONE
     */
    public View getLastChildNotGone() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                return child;
            }
        }
        return null;
    }

    /**
     * @return the number of children which have visibility unequal to GONE
     */
    public int getNotGoneChildCount() {
        int childCount = getChildCount();
        int count = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                count++;
            }
        }
        if (mDismissView.willBeGone()) {
            count--;
        }
        if (mEmptyShadeView.willBeGone()) {
            count--;
        }
        return count;
    }

    private int getMaxExpandHeight(View view) {
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            return row.getIntrinsicHeight();
        }
        return view.getHeight();
    }

    public int getContentHeight() {
        return mContentHeight;
    }

    private void updateContentHeight() {
        int height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                if (height != 0) {
                    // add the padding before this element
                    height += mPaddingBetweenElements;
                }
                if (child instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                    height += row.getIntrinsicHeight();
                } else if (child instanceof ExpandableView) {
                    ExpandableView expandableView = (ExpandableView) child;
                    height += expandableView.getActualHeight();
                }
            }
        }
        mContentHeight = height + mTopPadding;
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
            int scrollRange = getScrollRange();

            float topAmount = getCurrentOverScrollAmount(true);
            float bottomAmount = getCurrentOverScrollAmount(false);
            if (velocityY < 0 && topAmount > 0) {
                mOwnScrollY -= (int) topAmount;
                mDontReportNextOverScroll = true;
                setOverScrollAmount(0, true, false);
                mMaxOverScroll = Math.abs(velocityY) / 1000f * getRubberBandFactor(true /* onTop */)
                        * mOverflingDistance + topAmount;
            } else if (velocityY > 0 && bottomAmount > 0) {
                mOwnScrollY += bottomAmount;
                setOverScrollAmount(0, false, false);
                mMaxOverScroll = Math.abs(velocityY) / 1000f
                        * getRubberBandFactor(false /* onTop */) * mOverflingDistance
                        +  bottomAmount;
            } else {
                // it will be set once we reach the boundary
                mMaxOverScroll = 0.0f;
            }
            mScroller.fling(mScrollX, mOwnScrollY, 1, velocityY, 0, 0, 0,
                    Math.max(0, scrollRange), 0, Integer.MAX_VALUE / 2);

            postInvalidateOnAnimation();
        }
    }

    /**
     * @return Whether a fling performed on the top overscroll edge lead to the expanded
     * overScroll view (i.e QS).
     */
    private boolean shouldOverScrollFling(int initialVelocity) {
        float topOverScroll = getCurrentOverScrollAmount(true);
        return mScrolledToTopOnFirstDown
                && !mExpandedInThisMotion
                && topOverScroll > mMinTopOverScrollToEscape
                && initialVelocity > 0;
    }

    public void updateTopPadding(float qsHeight, int scrollY, boolean animate) {
        float start = qsHeight - scrollY + mNotificationTopPadding;
        float stackHeight = getHeight() - start;
        int minStackHeight = getMinStackHeight();
        if (stackHeight <= minStackHeight) {
            float overflow = minStackHeight - stackHeight;
            stackHeight = minStackHeight;
            start = getHeight() - stackHeight;
            setTranslationY(overflow);
            mTopPaddingOverflow = overflow;
        } else {
            setTranslationY(0);
            mTopPaddingOverflow = 0;
        }
        setTopPadding(clampPadding((int) start), animate);
    }

    public int getNotificationTopPadding() {
        return mNotificationTopPadding;
    }

    public int getMinStackHeight() {
        return mCollapsedSize + mBottomStackPeekSize + mCollapseSecondCardPadding;
    }

    public float getTopPaddingOverflow() {
        return mTopPaddingOverflow;
    }

    public int getPeekHeight() {
        return mIntrinsicPadding + mCollapsedSize + mBottomStackPeekSize
                + mCollapseSecondCardPadding;
    }

    private int clampPadding(int desiredPadding) {
        return Math.max(desiredPadding, mIntrinsicPadding);
    }

    private float getRubberBandFactor(boolean onTop) {
        if (!onTop) {
            return RUBBER_BAND_FACTOR_NORMAL;
        }
        if (mExpandedInThisMotion) {
            return RUBBER_BAND_FACTOR_AFTER_EXPAND;
        } else if (mIsExpansionChanging) {
            return RUBBER_BAND_FACTOR_ON_PANEL_EXPAND;
        } else if (mScrolledToTopOnFirstDown) {
            return 1.0f;
        }
        return RUBBER_BAND_FACTOR_NORMAL;
    }

    /**
     * Accompanying function for {@link #getRubberBandFactor}: Returns true if the overscroll is
     * rubberbanded, false if it is technically an overscroll but rather a motion to expand the
     * overscroll view (e.g. expand QS).
     */
    private boolean isRubberbanded(boolean onTop) {
        return !onTop || mExpandedInThisMotion || mIsExpansionChanging
                || !mScrolledToTopOnFirstDown;
    }

    private void endDrag() {
        setIsBeingDragged(false);

        recycleVelocityTracker();

        if (getCurrentOverScrollAmount(true /* onTop */) > 0) {
            setOverScrollAmount(0, true /* onTop */, true /* animate */);
        }
        if (getCurrentOverScrollAmount(false /* onTop */) > 0) {
            setOverScrollAmount(0, false /* onTop */, true /* animate */);
        }
    }

    private void transformTouchEvent(MotionEvent ev, View sourceView, View targetView) {
        ev.offsetLocation(sourceView.getX(), sourceView.getY());
        ev.offsetLocation(-targetView.getX(), -targetView.getY());
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mInterceptDelegateEnabled) {
            transformTouchEvent(ev, this, mScrollView);
            if (mScrollView.onInterceptTouchEvent(ev)) {
                mDelegateToScrollView = true;
                removeLongPressCallback();
                return true;
            }
            transformTouchEvent(ev, mScrollView, this);
        }
        initDownStates(ev);
        boolean expandWantsIt = false;
        if (!mSwipingInProgress && !mOnlyScrollingInThisMotion && isScrollingEnabled()) {
            expandWantsIt = mExpandHelper.onInterceptTouchEvent(ev);
        }
        boolean scrollWantsIt = false;
        if (!mSwipingInProgress && !mExpandingNotification) {
            scrollWantsIt = onInterceptTouchEventScroll(ev);
        }
        boolean swipeWantsIt = false;
        if (!mIsBeingDragged
                && !mExpandingNotification
                && !mExpandedInThisMotion
                && !mOnlyScrollingInThisMotion) {
            swipeWantsIt = mSwipeHelper.onInterceptTouchEvent(ev);
        }
        return swipeWantsIt || scrollWantsIt || expandWantsIt || super.onInterceptTouchEvent(ev);
    }

    private void initDownStates(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mExpandedInThisMotion = false;
            mOnlyScrollingInThisMotion = !mScroller.isFinished();
            mDisallowScrollingInThisMotion = false;
        }
    }

    @Override
    protected void onViewRemoved(View child) {
        super.onViewRemoved(child);
        mStackScrollAlgorithm.notifyChildrenChanged(this);
        if (mChangePositionInProgress) {
            // This is only a position change, don't do anything special
            return;
        }
        ((ExpandableView) child).setOnHeightChangedListener(null);
        mCurrentStackScrollState.removeViewStateForView(child);
        updateScrollStateForRemovedChild(child);
        boolean animationGenerated = generateRemoveAnimation(child);
        if (animationGenerated && !mSwipedOutViews.contains(child)) {
            // Add this view to an overlay in order to ensure that it will still be temporary
            // drawn when removed
            getOverlay().add(child);
        }
    }

    /**
     * Generate a remove animation for a child view.
     *
     * @param child The view to generate the remove animation for.
     * @return Whether an animation was generated.
     */
    private boolean generateRemoveAnimation(View child) {
        if (mIsExpanded && mAnimationsEnabled) {
            if (!mChildrenToAddAnimated.contains(child)) {
                // Generate Animations
                mChildrenToRemoveAnimated.add(child);
                mNeedsAnimation = true;
                return true;
            } else {
                mChildrenToAddAnimated.remove(child);
                mFromMoreCardAdditions.remove(child);
                return false;
            }
        }
        return false;
    }

    /**
     * Updates the scroll position when a child was removed
     *
     * @param removedChild the removed child
     */
    private void updateScrollStateForRemovedChild(View removedChild) {
        int startingPosition = getPositionInLinearLayout(removedChild);
        int childHeight = getIntrinsicHeight(removedChild) + mPaddingBetweenElements;
        int endPosition = startingPosition + childHeight;
        if (endPosition <= mOwnScrollY) {
            // This child is fully scrolled of the top, so we have to deduct its height from the
            // scrollPosition
            mOwnScrollY -= childHeight;
        } else if (startingPosition < mOwnScrollY) {
            // This child is currently being scrolled into, set the scroll position to the start of
            // this child
            mOwnScrollY = startingPosition;
        }
    }

    private int getIntrinsicHeight(View view) {
        if (view instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) view;
            return expandableView.getIntrinsicHeight();
        }
        return view.getHeight();
    }

    private int getPositionInLinearLayout(View requestedChild) {
        int position = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == requestedChild) {
                return position;
            }
            if (child.getVisibility() != View.GONE) {
                position += child.getHeight();
                if (i < getChildCount()-1) {
                    position += mPaddingBetweenElements;
                }
            }
        }
        return 0;
    }

    @Override
    protected void onViewAdded(View child) {
        super.onViewAdded(child);
        mStackScrollAlgorithm.notifyChildrenChanged(this);
        ((ExpandableView) child).setOnHeightChangedListener(this);
        generateAddAnimation(child, false /* fromMoreCard */);
    }

    public void setAnimationsEnabled(boolean animationsEnabled) {
        mAnimationsEnabled = animationsEnabled;
    }

    public boolean isAddOrRemoveAnimationPending() {
        return mNeedsAnimation
                && (!mChildrenToAddAnimated.isEmpty() || !mChildrenToRemoveAnimated.isEmpty());
    }
    /**
     * Generate an animation for an added child view.
     *
     * @param child The view to be added.
     * @param fromMoreCard Whether this add is coming from the "more" card on lockscreen.
     */
    public void generateAddAnimation(View child, boolean fromMoreCard) {
        if (mIsExpanded && mAnimationsEnabled && !mChangePositionInProgress) {
            // Generate Animations
            mChildrenToAddAnimated.add(child);
            if (fromMoreCard) {
                mFromMoreCardAdditions.add(child);
            }
            mNeedsAnimation = true;
        }
    }

    /**
     * Change the position of child to a new location
     *
     * @param child the view to change the position for
     * @param newIndex the new index
     */
    public void changeViewPosition(View child, int newIndex) {
        int currentIndex = indexOfChild(child);
        if (child != null && child.getParent() == this && currentIndex != newIndex) {
            mChangePositionInProgress = true;
            removeView(child);
            addView(child, newIndex);
            mChangePositionInProgress = false;
            if (mIsExpanded && mAnimationsEnabled && child.getVisibility() != View.GONE) {
                mChildrenChangingPositions.add(child);
                mNeedsAnimation = true;
            }
        }
    }

    private void startAnimationToState() {
        if (mNeedsAnimation) {
            generateChildHierarchyEvents();
            mNeedsAnimation = false;
        }
        if (!mAnimationEvents.isEmpty() || isCurrentlyAnimating()) {
            mStateAnimator.startAnimationForEvents(mAnimationEvents, mCurrentStackScrollState,
                    mGoToFullShadeDelay);
            mAnimationEvents.clear();
        } else {
            applyCurrentState();
        }
        mGoToFullShadeDelay = 0;
    }

    private void generateChildHierarchyEvents() {
        generateChildRemovalEvents();
        generateChildAdditionEvents();
        generatePositionChangeEvents();
        generateSnapBackEvents();
        generateDragEvents();
        generateTopPaddingEvent();
        generateActivateEvent();
        generateDimmedEvent();
        generateHideSensitiveEvent();
        generateDarkEvent();
        generateGoToFullShadeEvent();
        generateViewResizeEvent();
        mNeedsAnimation = false;
    }

    private void generateViewResizeEvent() {
        if (mNeedViewResizeAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_VIEW_RESIZE));
        }
        mNeedViewResizeAnimation = false;
    }

    private void generateSnapBackEvents() {
        for (View child : mSnappedBackChildren) {
            mAnimationEvents.add(new AnimationEvent(child,
                    AnimationEvent.ANIMATION_TYPE_SNAP_BACK));
        }
        mSnappedBackChildren.clear();
    }

    private void generateDragEvents() {
        for (View child : mDragAnimPendingChildren) {
            mAnimationEvents.add(new AnimationEvent(child,
                    AnimationEvent.ANIMATION_TYPE_START_DRAG));
        }
        mDragAnimPendingChildren.clear();
    }

    private void generateChildRemovalEvents() {
        for (View child : mChildrenToRemoveAnimated) {
            boolean childWasSwipedOut = mSwipedOutViews.contains(child);
            int animationType = childWasSwipedOut
                    ? AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT
                    : AnimationEvent.ANIMATION_TYPE_REMOVE;
            AnimationEvent event = new AnimationEvent(child, animationType);

            // we need to know the view after this one
            event.viewAfterChangingView = getFirstChildBelowTranlsationY(child.getTranslationY());
            mAnimationEvents.add(event);
        }
        mSwipedOutViews.clear();
        mChildrenToRemoveAnimated.clear();
    }

    private void generatePositionChangeEvents() {
        for (View child : mChildrenChangingPositions) {
            mAnimationEvents.add(new AnimationEvent(child,
                    AnimationEvent.ANIMATION_TYPE_CHANGE_POSITION));
        }
        mChildrenChangingPositions.clear();
    }

    private void generateChildAdditionEvents() {
        for (View child : mChildrenToAddAnimated) {
            if (mFromMoreCardAdditions.contains(child)) {
                mAnimationEvents.add(new AnimationEvent(child,
                        AnimationEvent.ANIMATION_TYPE_ADD,
                        StackStateAnimator.ANIMATION_DURATION_STANDARD));
            } else {
                mAnimationEvents.add(new AnimationEvent(child,
                        AnimationEvent.ANIMATION_TYPE_ADD));
            }
        }
        mChildrenToAddAnimated.clear();
        mFromMoreCardAdditions.clear();
    }

    private void generateTopPaddingEvent() {
        if (mTopPaddingNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_TOP_PADDING_CHANGED));
        }
        mTopPaddingNeedsAnimation = false;
    }

    private void generateActivateEvent() {
        if (mActivateNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_ACTIVATED_CHILD));
        }
        mActivateNeedsAnimation = false;
    }

    private void generateDimmedEvent() {
        if (mDimmedNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_DIMMED));
        }
        mDimmedNeedsAnimation = false;
    }

    private void generateHideSensitiveEvent() {
        if (mHideSensitiveNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_HIDE_SENSITIVE));
        }
        mHideSensitiveNeedsAnimation = false;
    }

    private void generateDarkEvent() {
        if (mDarkNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_DARK));
        }
        mDarkNeedsAnimation = false;
    }

    private void generateGoToFullShadeEvent() {
        if (mGoToFullShadeNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_GO_TO_FULL_SHADE));
        }
        mGoToFullShadeNeedsAnimation = false;
    }

    private boolean onInterceptTouchEventScroll(MotionEvent ev) {
        if (!isScrollingEnabled()) {
            return false;
        }
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
                final int x = (int) ev.getX(pointerIndex);
                final int yDiff = Math.abs(y - mLastMotionY);
                final int xDiff = Math.abs(x - mDownX);
                if (yDiff > mTouchSlop && yDiff > xDiff) {
                    setIsBeingDragged(true);
                    mLastMotionY = y;
                    mDownX = x;
                    initVelocityTrackerIfNotExists();
                    mVelocityTracker.addMovement(ev);
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
                mDownX = (int) ev.getX();
                mActivePointerId = ev.getPointerId(0);
                mScrolledToTopOnFirstDown = isScrolledToTop();

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

    /**
     * @return Whether the specified motion event is actually happening over the content.
     */
    private boolean isInContentBounds(MotionEvent event) {
        return event.getY() < getHeight() - getEmptyBottomMargin();
    }

    private void setIsBeingDragged(boolean isDragged) {
        mIsBeingDragged = isDragged;
        if (isDragged) {
            requestDisallowInterceptTouchEvent(true);
            removeLongPressCallback();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            removeLongPressCallback();
        }
    }

    public void removeLongPressCallback() {
        mSwipeHelper.removeLongPressCallback();
    }

    @Override
    public boolean isScrolledToTop() {
        return mOwnScrollY == 0;
    }

    @Override
    public boolean isScrolledToBottom() {
        return mOwnScrollY >= getScrollRange();
    }

    @Override
    public View getHostView() {
        return this;
    }

    public int getEmptyBottomMargin() {
        int emptyMargin = mMaxLayoutHeight - mContentHeight - mBottomStackPeekSize;
        if (needsHeightAdaption()) {
            emptyMargin -= mBottomStackSlowDownHeight;
        } else {
            emptyMargin -= mCollapseSecondCardPadding;
        }
        return Math.max(emptyMargin, 0);
    }

    public void onExpansionStarted() {
        mIsExpansionChanging = true;
        mStackScrollAlgorithm.onExpansionStarted(mCurrentStackScrollState);
    }

    public void onExpansionStopped() {
        mIsExpansionChanging = false;
        mStackScrollAlgorithm.onExpansionStopped();
        if (!mIsExpanded) {
            mOwnScrollY = 0;
        }
    }

    private void setIsExpanded(boolean isExpanded) {
        mIsExpanded = isExpanded;
        mStackScrollAlgorithm.setIsExpanded(isExpanded);
    }

    @Override
    public void onHeightChanged(ExpandableView view) {
        updateContentHeight();
        updateScrollPositionOnExpandInBottom(view);
        clampScrollPosition();
        notifyHeightChangeListener(view);
        requestChildrenUpdate();
    }

    @Override
    public void onReset(ExpandableView view) {
        mRequestViewResizeAnimationOnLayout = true;
        mStackScrollAlgorithm.onReset(view);
    }

    private void updateScrollPositionOnExpandInBottom(ExpandableView view) {
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (row.isUserLocked()) {
                // We are actually expanding this view
                float endPosition = row.getTranslationY() + row.getActualHeight();
                int stackEnd = mMaxLayoutHeight - mBottomStackPeekSize -
                        mBottomStackSlowDownHeight;
                if (endPosition > stackEnd) {
                    mOwnScrollY += endPosition - stackEnd;
                    mDisallowScrollingInThisMotion = true;
                }
            }
        }
    }

    public void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener mOnHeightChangedListener) {
        this.mOnHeightChangedListener = mOnHeightChangedListener;
    }

    public void onChildAnimationFinished() {
        requestChildrenUpdate();
    }

    /**
     * See {@link AmbientState#setDimmed}.
     */
    public void setDimmed(boolean dimmed, boolean animate) {
        mStackScrollAlgorithm.setDimmed(dimmed);
        mAmbientState.setDimmed(dimmed);
        updatePadding(dimmed);
        if (animate && mAnimationsEnabled) {
            mDimmedNeedsAnimation = true;
            mNeedsAnimation =  true;
        }
        requestChildrenUpdate();
    }

    public void setHideSensitive(boolean hideSensitive, boolean animate) {
        if (hideSensitive != mAmbientState.isHideSensitive()) {
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                ExpandableView v = (ExpandableView) getChildAt(i);
                v.setHideSensitiveForIntrinsicHeight(hideSensitive);
            }
            mAmbientState.setHideSensitive(hideSensitive);
            if (animate && mAnimationsEnabled) {
                mHideSensitiveNeedsAnimation = true;
                mNeedsAnimation =  true;
            }
            requestChildrenUpdate();
        }
    }

    /**
     * See {@link AmbientState#setActivatedChild}.
     */
    public void setActivatedChild(ActivatableNotificationView activatedChild) {
        mAmbientState.setActivatedChild(activatedChild);
        if (mAnimationsEnabled) {
            mActivateNeedsAnimation = true;
            mNeedsAnimation =  true;
        }
        requestChildrenUpdate();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mAmbientState.getActivatedChild();
    }

    private void applyCurrentState() {
        mCurrentStackScrollState.apply();
        if (mListener != null) {
            mListener.onChildLocationsChanged(this);
        }
    }

    public void setSpeedBumpView(SpeedBumpView speedBumpView) {
        mSpeedBumpView = speedBumpView;
        addView(speedBumpView);
    }

    private void updateSpeedBump(boolean visible) {
        boolean notGoneBefore = mSpeedBumpView.getVisibility() != GONE;
        if (visible != notGoneBefore) {
            int newVisibility = visible ? VISIBLE : GONE;
            mSpeedBumpView.setVisibility(newVisibility);
            if (visible) {
                // Make invisible to ensure that the appear animation is played.
                mSpeedBumpView.setInvisible();
            } else {
                // TODO: This doesn't really work, because the view is already set to GONE above.
                generateRemoveAnimation(mSpeedBumpView);
            }
        }
    }

    public void goToFullShade(long delay) {
        updateSpeedBump(true /* visibility */);
        mDismissView.setInvisible();
        mEmptyShadeView.setInvisible();
        mGoToFullShadeNeedsAnimation = true;
        mGoToFullShadeDelay = delay;
        mNeedsAnimation =  true;
        requestChildrenUpdate();
    }

    public void cancelExpandHelper() {
        mExpandHelper.cancel();
    }

    public void setIntrinsicPadding(int intrinsicPadding) {
        mIntrinsicPadding = intrinsicPadding;
    }

    public int getIntrinsicPadding() {
        return mIntrinsicPadding;
    }

    /**
     * @return the y position of the first notification
     */
    public float getNotificationsTopY() {
        return mTopPadding + getTranslationY();
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    public void setScrimAlpha(float progress) {
        if (progress != mAmbientState.getScrimAmount()) {
            mAmbientState.setScrimAmount(progress);
            requestChildrenUpdate();
        }
    }

    /**
     * See {@link AmbientState#setDark}.
     */
    public void setDark(boolean dark, boolean animate) {
        mAmbientState.setDark(dark);
        if (animate && mAnimationsEnabled) {
            mDarkNeedsAnimation = true;
            mNeedsAnimation =  true;
        }
        requestChildrenUpdate();
    }

    public void setDismissView(DismissView dismissView) {
        mDismissView = dismissView;
        addView(mDismissView);
    }

    public void setEmptyShadeView(EmptyShadeView emptyShadeView) {
        mEmptyShadeView = emptyShadeView;
        addView(mEmptyShadeView);
    }

    public void updateEmptyShadeView(boolean visible) {
        int oldVisibility = mEmptyShadeView.willBeGone() ? GONE : mEmptyShadeView.getVisibility();
        int newVisibility = visible ? VISIBLE : GONE;
        if (oldVisibility != newVisibility) {
            if (oldVisibility == GONE) {
                if (mEmptyShadeView.willBeGone()) {
                    mEmptyShadeView.cancelAnimation();
                } else {
                    mEmptyShadeView.setInvisible();
                    mEmptyShadeView.setVisibility(newVisibility);
                }
                mEmptyShadeView.setWillBeGone(false);
                updateContentHeight();
            } else {
                mEmptyShadeView.setWillBeGone(true);
                mEmptyShadeView.performVisibilityAnimation(false, new Runnable() {
                    @Override
                    public void run() {
                        mEmptyShadeView.setVisibility(GONE);
                        mEmptyShadeView.setWillBeGone(false);
                        updateContentHeight();
                    }
                });
            }
        }
    }

    public void updateDismissView(boolean visible) {
        int oldVisibility = mDismissView.willBeGone() ? GONE : mDismissView.getVisibility();
        int newVisibility = visible ? VISIBLE : GONE;
        if (oldVisibility != newVisibility) {
            if (oldVisibility == GONE) {
                if (mDismissView.willBeGone()) {
                    mDismissView.cancelAnimation();
                } else {
                    mDismissView.setInvisible();
                    mDismissView.setVisibility(newVisibility);
                }
                mDismissView.setWillBeGone(false);
                updateContentHeight();
            } else {
                mDismissView.setWillBeGone(true);
                mDismissView.performVisibilityAnimation(false, new Runnable() {
                    @Override
                    public void run() {
                        mDismissView.setVisibility(GONE);
                        mDismissView.setWillBeGone(false);
                        updateContentHeight();
                    }
                });
            }
        }
    }

    public void setDismissAllInProgress(boolean dismissAllInProgress) {
        mDismissAllInProgress = dismissAllInProgress;
    }

    public boolean isDismissViewNotGone() {
        return mDismissView.getVisibility() != View.GONE && !mDismissView.willBeGone();
    }

    public boolean isDismissViewVisible() {
        return mDismissView.isVisible();
    }

    public int getDismissViewHeight() {
        return mDismissView.getHeight() + mPaddingBetweenElementsNormal;
    }

    public float getBottomMostNotificationBottom() {
        final int count = getChildCount();
        float max = 0;
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView child = (ExpandableView) getChildAt(childIdx);
            if (child.getVisibility() == GONE) {
                continue;
            }
            float bottom = child.getTranslationY() + child.getActualHeight();
            if (bottom > max) {
                max = bottom;
            }
        }
        return max + getTranslationY();
    }

    /**
     * @param qsMinHeight The minimum height of the quick settings including padding
     *                    See {@link StackScrollAlgorithm#updateIsSmallScreen}.
     */
    public void updateIsSmallScreen(int qsMinHeight) {
        mStackScrollAlgorithm.updateIsSmallScreen(mMaxLayoutHeight - qsMinHeight);
    }

    /**
     * A listener that is notified when some child locations might have changed.
     */
    public interface OnChildLocationsChangedListener {
        public void onChildLocationsChanged(NotificationStackScrollLayout stackScrollLayout);
    }

    /**
     * A listener that gets notified when the overscroll at the top has changed.
     */
    public interface OnOverscrollTopChangedListener {

        /**
         * Notifies a listener that the overscroll has changed.
         *
         * @param amount the amount of overscroll, in pixels
         * @param isRubberbanded if true, this is a rubberbanded overscroll; if false, this is an
         *                     unrubberbanded motion to directly expand overscroll view (e.g expand
         *                     QS)
         */
        public void onOverscrollTopChanged(float amount, boolean isRubberbanded);

        /**
         * Notify a listener that the scroller wants to escape from the scrolling motion and
         * start a fling animation to the expanded or collapsed overscroll view (e.g expand the QS)
         *
         * @param velocity The velocity that the Scroller had when over flinging
         * @param open Should the fling open or close the overscroll view.
         */
        public void flingTopOverscroll(float velocity, boolean open);
    }

    static class AnimationEvent {

        static AnimationFilter[] FILTERS = new AnimationFilter[] {

                // ANIMATION_TYPE_ADD
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_REMOVE
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_REMOVE_SWIPED_OUT
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_TOP_PADDING_CHANGED
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateDimmed()
                        .animateScale()
                        .animateZ(),

                // ANIMATION_TYPE_START_DRAG
                new AnimationFilter()
                        .animateAlpha(),

                // ANIMATION_TYPE_SNAP_BACK
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight(),

                // ANIMATION_TYPE_ACTIVATED_CHILD
                new AnimationFilter()
                        .animateScale()
                        .animateAlpha(),

                // ANIMATION_TYPE_DIMMED
                new AnimationFilter()
                        .animateY()
                        .animateScale()
                        .animateDimmed(),

                // ANIMATION_TYPE_CHANGE_POSITION
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_DARK
                new AnimationFilter()
                        .animateDark(),

                // ANIMATION_TYPE_GO_TO_FULL_SHADE
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateDimmed()
                        .animateScale()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_HIDE_SENSITIVE
                new AnimationFilter()
                        .animateHideSensitive(),

                // ANIMATION_TYPE_VIEW_RESIZE
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),
        };

        static int[] LENGTHS = new int[] {

                // ANIMATION_TYPE_ADD
                StackStateAnimator.ANIMATION_DURATION_APPEAR_DISAPPEAR,

                // ANIMATION_TYPE_REMOVE
                StackStateAnimator.ANIMATION_DURATION_APPEAR_DISAPPEAR,

                // ANIMATION_TYPE_REMOVE_SWIPED_OUT
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_TOP_PADDING_CHANGED
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_START_DRAG
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_SNAP_BACK
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_ACTIVATED_CHILD
                StackStateAnimator.ANIMATION_DURATION_DIMMED_ACTIVATED,

                // ANIMATION_TYPE_DIMMED
                StackStateAnimator.ANIMATION_DURATION_DIMMED_ACTIVATED,

                // ANIMATION_TYPE_CHANGE_POSITION
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_DARK
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_GO_TO_FULL_SHADE
                StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE,

                // ANIMATION_TYPE_HIDE_SENSITIVE
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_VIEW_RESIZE
                StackStateAnimator.ANIMATION_DURATION_STANDARD,
        };

        static final int ANIMATION_TYPE_ADD = 0;
        static final int ANIMATION_TYPE_REMOVE = 1;
        static final int ANIMATION_TYPE_REMOVE_SWIPED_OUT = 2;
        static final int ANIMATION_TYPE_TOP_PADDING_CHANGED = 3;
        static final int ANIMATION_TYPE_START_DRAG = 4;
        static final int ANIMATION_TYPE_SNAP_BACK = 5;
        static final int ANIMATION_TYPE_ACTIVATED_CHILD = 6;
        static final int ANIMATION_TYPE_DIMMED = 7;
        static final int ANIMATION_TYPE_CHANGE_POSITION = 8;
        static final int ANIMATION_TYPE_DARK = 9;
        static final int ANIMATION_TYPE_GO_TO_FULL_SHADE = 10;
        static final int ANIMATION_TYPE_HIDE_SENSITIVE = 11;
        static final int ANIMATION_TYPE_VIEW_RESIZE = 12;

        final long eventStartTime;
        final View changingView;
        final int animationType;
        final AnimationFilter filter;
        final long length;
        View viewAfterChangingView;

        AnimationEvent(View view, int type) {
            this(view, type, LENGTHS[type]);
        }

        AnimationEvent(View view, int type, long length) {
            eventStartTime = AnimationUtils.currentAnimationTimeMillis();
            changingView = view;
            animationType = type;
            filter = FILTERS[type];
            this.length = length;
        }

        /**
         * Combines the length of several animation events into a single value.
         *
         * @param events The events of the lengths to combine.
         * @return The combined length. Depending on the event types, this might be the maximum of
         *         all events or the length of a specific event.
         */
        static long combineLength(ArrayList<AnimationEvent> events) {
            long length = 0;
            int size = events.size();
            for (int i = 0; i < size; i++) {
                AnimationEvent event = events.get(i);
                length = Math.max(length, event.length);
                if (event.animationType == ANIMATION_TYPE_GO_TO_FULL_SHADE) {
                    return event.length;
                }
            }
            return length;
        }
    }

}
