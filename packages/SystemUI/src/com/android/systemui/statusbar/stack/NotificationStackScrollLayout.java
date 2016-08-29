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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.FloatRange;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Pair;
import android.util.Property;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.OverScroller;
import android.widget.ScrollView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.DismissView;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.NotificationGuts;
import com.android.systemui.statusbar.NotificationOverflowContainer;
import com.android.systemui.statusbar.NotificationSettingsIconRow;
import com.android.systemui.statusbar.NotificationSettingsIconRow.SettingsIconRowListener;
import com.android.systemui.statusbar.StackScrollerDecorView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.ScrollAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

/**
 * A layout which handles a dynamic amount of notifications and presents them in a scrollable stack.
 */
public class NotificationStackScrollLayout extends ViewGroup
        implements SwipeHelper.Callback, ExpandHelper.Callback, ScrollAdapter,
        ExpandableView.OnHeightChangedListener, NotificationGroupManager.OnGroupChangeListener,
        SettingsIconRowListener, ScrollContainer {

    public static final float BACKGROUND_ALPHA_DIMMED = 0.7f;
    private static final String TAG = "StackScroller";
    private static final boolean DEBUG = false;
    private static final float RUBBER_BAND_FACTOR_NORMAL = 0.35f;
    private static final float RUBBER_BAND_FACTOR_AFTER_EXPAND = 0.15f;
    private static final float RUBBER_BAND_FACTOR_ON_PANEL_EXPAND = 0.21f;
    /**
     * Sentinel value for no current active pointer. Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;

    private ExpandHelper mExpandHelper;
    private NotificationSwipeHelper mSwipeHelper;
    private boolean mSwipingInProgress;
    private int mCurrentStackHeight = Integer.MAX_VALUE;
    private final Paint mBackgroundPaint = new Paint();

    /**
     * mCurrentStackHeight is the actual stack height, mLastSetStackHeight is the stack height set
     * externally from {@link #setStackHeight}
     */
    private float mLastSetStackHeight;
    private int mOwnScrollY;
    private int mMaxLayoutHeight;

    private VelocityTracker mVelocityTracker;
    private OverScroller mScroller;
    private Runnable mFinishScrollingCallback;
    private int mTouchSlop;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mOverflingDistance;
    private float mMaxOverScroll;
    private boolean mIsBeingDragged;
    private int mLastMotionY;
    private int mDownX;
    private int mActivePointerId;
    private boolean mTouchIsClick;
    private float mInitialTouchX;
    private float mInitialTouchY;

    private Paint mDebugPaint;
    private int mContentHeight;
    private int mCollapsedSize;
    private int mBottomStackSlowDownHeight;
    private int mBottomStackPeekSize;
    private int mPaddingBetweenElements;
    private int mIncreasedPaddingBetweenElements;
    private int mTopPadding;
    private int mBottomInset = 0;

    /**
     * The algorithm which calculates the properties for our children
     */
    private final StackScrollAlgorithm mStackScrollAlgorithm;

    /**
     * The current State this Layout is in
     */
    private StackScrollState mCurrentStackScrollState = new StackScrollState(this);
    private AmbientState mAmbientState = new AmbientState();
    private NotificationGroupManager mGroupManager;
    private HashSet<View> mChildrenToAddAnimated = new HashSet<>();
    private ArrayList<View> mAddedHeadsUpChildren = new ArrayList<>();
    private ArrayList<View> mChildrenToRemoveAnimated = new ArrayList<>();
    private ArrayList<View> mSnappedBackChildren = new ArrayList<>();
    private ArrayList<View> mDragAnimPendingChildren = new ArrayList<>();
    private ArrayList<View> mChildrenChangingPositions = new ArrayList<>();
    private HashSet<View> mFromMoreCardAdditions = new HashSet<>();
    private ArrayList<AnimationEvent> mAnimationEvents = new ArrayList<>();
    private ArrayList<View> mSwipedOutViews = new ArrayList<>();
    private final StackStateAnimator mStateAnimator = new StackStateAnimator(this);
    private boolean mAnimationsEnabled;
    private boolean mChangePositionInProgress;
    private boolean mChildTransferInProgress;

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
    private OnEmptySpaceClickListener mOnEmptySpaceClickListener;
    private boolean mNeedsAnimation;
    private boolean mTopPaddingNeedsAnimation;
    private boolean mDimmedNeedsAnimation;
    private boolean mHideSensitiveNeedsAnimation;
    private boolean mDarkNeedsAnimation;
    private int mDarkAnimationOriginIndex;
    private boolean mActivateNeedsAnimation;
    private boolean mGoToFullShadeNeedsAnimation;
    private boolean mIsExpanded = true;
    private boolean mChildrenUpdateRequested;
    private boolean mIsExpansionChanging;
    private boolean mPanelTracking;
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
    private float mStackTranslation;
    private float mTopPaddingOverflow;
    private boolean mDontReportNextOverScroll;
    private boolean mDontClampNextScroll;
    private boolean mRequestViewResizeAnimationOnLayout;
    private boolean mNeedViewResizeAnimation;
    private View mExpandedGroupView;
    private boolean mEverythingNeedsAnimation;

    /**
     * The maximum scrollPosition which we are allowed to reach when a notification was expanded.
     * This is needed to avoid scrolling too far after the notification was collapsed in the same
     * motion.
     */
    private int mMaxScrollAfterExpand;
    private SwipeHelper.LongPressListener mLongPressListener;

    private NotificationSettingsIconRow mCurrIconRow;
    private View mTranslatingParentView;
    private View mGearExposedView;

    /**
     * Should in this touch motion only be scrolling allowed? It's true when the scroller was
     * animating.
     */
    private boolean mOnlyScrollingInThisMotion;
    private boolean mDisallowDismissInThisMotion;
    private boolean mInterceptDelegateEnabled;
    private boolean mDelegateToScrollView;
    private boolean mDisallowScrollingInThisMotion;
    private long mGoToFullShadeDelay;
    private ViewTreeObserver.OnPreDrawListener mChildrenUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            updateForcedScroll();
            updateChildren();
            mChildrenUpdateRequested = false;
            getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        }
    };
    private PhoneStatusBar mPhoneStatusBar;
    private int[] mTempInt2 = new int[2];
    private boolean mGenerateChildOrderChangedEvent;
    private HashSet<Runnable> mAnimationFinishedRunnables = new HashSet<>();
    private HashSet<View> mClearOverlayViewsWhenFinished = new HashSet<>();
    private HashSet<Pair<ExpandableNotificationRow, Boolean>> mHeadsUpChangeAnimations
            = new HashSet<>();
    private HeadsUpManager mHeadsUpManager;
    private boolean mTrackingHeadsUp;
    private ScrimController mScrimController;
    private boolean mForceNoOverlappingRendering;
    private NotificationOverflowContainer mOverflowContainer;
    private final ArrayList<Pair<ExpandableNotificationRow, Boolean>> mTmpList = new ArrayList<>();
    private FalsingManager mFalsingManager;
    private boolean mAnimationRunning;
    private ViewTreeObserver.OnPreDrawListener mBackgroundUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            // if it needs animation
            if (!mNeedsAnimation && !mChildrenUpdateRequested) {
                updateBackground();
            }
            return true;
        }
    };
    private Rect mBackgroundBounds = new Rect();
    private Rect mStartAnimationRect = new Rect();
    private Rect mEndAnimationRect = new Rect();
    private Rect mCurrentBounds = new Rect(-1, -1, -1, -1);
    private boolean mAnimateNextBackgroundBottom;
    private boolean mAnimateNextBackgroundTop;
    private ObjectAnimator mBottomAnimator = null;
    private ObjectAnimator mTopAnimator = null;
    private ActivatableNotificationView mFirstVisibleBackgroundChild = null;
    private ActivatableNotificationView mLastVisibleBackgroundChild = null;
    private int mBgColor;
    private float mDimAmount;
    private ValueAnimator mDimAnimator;
    private ArrayList<ExpandableView> mTmpSortedChildren = new ArrayList<>();
    private Animator.AnimatorListener mDimEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mDimAnimator = null;
        }
    };
    private ValueAnimator.AnimatorUpdateListener mDimUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            setDimAmount((Float) animation.getAnimatedValue());
        }
    };
    protected ViewGroup mQsContainer;
    private boolean mContinuousShadowUpdate;
    private ViewTreeObserver.OnPreDrawListener mShadowUpdater
            = new ViewTreeObserver.OnPreDrawListener() {

        @Override
        public boolean onPreDraw() {
            updateViewShadows();
            return true;
        }
    };
    private Comparator<ExpandableView> mViewPositionComparator = new Comparator<ExpandableView>() {
        @Override
        public int compare(ExpandableView view, ExpandableView otherView) {
            float endY = view.getTranslationY() + view.getActualHeight();
            float otherEndY = otherView.getTranslationY() + otherView.getActualHeight();
            if (endY < otherEndY) {
                return -1;
            } else if (endY > otherEndY) {
                return 1;
            } else {
                // The two notifications end at the same location
                return 0;
            }
        }
    };
    private PorterDuffXfermode mSrcMode = new PorterDuffXfermode(PorterDuff.Mode.SRC);
    private boolean mPulsing;
    private boolean mDrawBackgroundAsSrc;
    private boolean mFadingOut;
    private boolean mParentFadingOut;
    private boolean mGroupExpandedForMeasure;
    private boolean mScrollable;
    private View mForcedScroll;
    private float mBackgroundFadeAmount = 1.0f;
    private static final Property<NotificationStackScrollLayout, Float> BACKGROUND_FADE =
            new FloatProperty<NotificationStackScrollLayout>("backgroundFade") {
                @Override
                public void setValue(NotificationStackScrollLayout object, float value) {
                    object.setBackgroundFadeAmount(value);
                }

                @Override
                public Float get(NotificationStackScrollLayout object) {
                    return object.getBackgroundFadeAmount();
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
        mBgColor = context.getColor(R.color.notification_shade_background_color);
        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        mExpandHelper = new ExpandHelper(getContext(), this,
                minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setScrollAdapter(this);
        mSwipeHelper = new NotificationSwipeHelper(SwipeHelper.X, this, getContext());
        mSwipeHelper.setLongPressListener(mLongPressListener);
        mStackScrollAlgorithm = new StackScrollAlgorithm(context);
        initView(context);
        setWillNotDraw(false);
        if (DEBUG) {
            mDebugPaint = new Paint();
            mDebugPaint.setColor(0xffff0000);
            mDebugPaint.setStrokeWidth(2);
            mDebugPaint.setStyle(Paint.Style.STROKE);
        }
        mFalsingManager = FalsingManager.getInstance(context);
    }

    @Override
    public void onGearTouched(ExpandableNotificationRow row, int x, int y) {
        if (mLongPressListener != null) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_TOUCH_GEAR,
                    row.getStatusBarNotification().getPackageName());
            mLongPressListener.onLongPress(row, x, y);
        }
    }

    @Override
    public void onSettingsIconRowReset(ExpandableNotificationRow row) {
        if (mTranslatingParentView != null && row == mTranslatingParentView) {
            mSwipeHelper.setSnappedToGear(false);
            mGearExposedView = null;
            mTranslatingParentView = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, mCurrentBounds.top, getWidth(), mCurrentBounds.bottom, mBackgroundPaint);
        if (DEBUG) {
            int y = mTopPadding;
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

    private void updateBackgroundDimming() {
        float alpha = BACKGROUND_ALPHA_DIMMED + (1 - BACKGROUND_ALPHA_DIMMED) * (1.0f - mDimAmount);
        alpha *= mBackgroundFadeAmount;
        // We need to manually blend in the background color
        int scrimColor = mScrimController.getScrimBehindColor();
        // SRC_OVER blending Sa + (1 - Sa)*Da, Rc = Sc + (1 - Sa)*Dc
        float alphaInv = 1 - alpha;
        int color = Color.argb((int) (alpha * 255 + alphaInv * Color.alpha(scrimColor)),
                (int) (mBackgroundFadeAmount * Color.red(mBgColor)
                        + alphaInv * Color.red(scrimColor)),
                (int) (mBackgroundFadeAmount * Color.green(mBgColor)
                        + alphaInv * Color.green(scrimColor)),
                (int) (mBackgroundFadeAmount * Color.blue(mBgColor)
                        + alphaInv * Color.blue(scrimColor)));
        mBackgroundPaint.setColor(color);
        invalidate();
    }

    private void initView(Context context) {
        mScroller = new OverScroller(getContext());
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setClipChildren(false);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverflingDistance = configuration.getScaledOverflingDistance();
        mCollapsedSize = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_min_height);
        mBottomStackPeekSize = context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_stack_peek_amount);
        mStackScrollAlgorithm.initView(context);
        mPaddingBetweenElements = Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.notification_divider_height));
        mIncreasedPaddingBetweenElements = context.getResources()
                .getDimensionPixelSize(R.dimen.notification_divider_height_increased);
        mBottomStackSlowDownHeight = mStackScrollAlgorithm.getBottomStackSlowDownLength();
        mMinTopOverScrollToEscape = getResources().getDimensionPixelSize(
                R.dimen.min_top_overscroll_to_qs);
    }

    public void setDrawBackgroundAsSrc(boolean asSrc) {
        mDrawBackgroundAsSrc = asSrc;
        updateSrcDrawing();
    }

    private void updateSrcDrawing() {
        mBackgroundPaint.setXfermode(mDrawBackgroundAsSrc && (!mFadingOut && !mParentFadingOut)
                ? mSrcMode : null);
        invalidate();
    }

    private void notifyHeightChangeListener(ExpandableView view) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(view, false /* needsAnimation */);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // We need to measure all children even the GONE ones, such that the heights are calculated
        // correctly as they are used to calculate how many we can fit on the screen.
        final int size = getChildCount();
        for (int i = 0; i < size; i++) {
            measureChild(getChildAt(i), widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // we layout all our children centered on the top
        float centerX = getWidth() / 2.0f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
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
        if (mRequestViewResizeAnimationOnLayout) {
            requestAnimationOnViewResize(null);
            mRequestViewResizeAnimationOnLayout = false;
        }
        requestChildrenUpdate();
        updateFirstAndLastBackgroundViews();
    }

    private void requestAnimationOnViewResize(ExpandableNotificationRow row) {
        if (mAnimationsEnabled && (mIsExpanded || row != null && row.isPinned())) {
            mNeedViewResizeAnimation = true;
            mNeedsAnimation = true;
        }
    }

    public void updateSpeedBumpIndex(int newIndex) {
        mAmbientState.setSpeedBumpIndex(newIndex);
    }

    public void setChildLocationsChangedListener(OnChildLocationsChangedListener listener) {
        mListener = listener;
    }

    /**
     * Returns the location the given child is currently rendered at.
     *
     * @param child the child to get the location for
     * @return one of {@link StackViewState}'s <code>LOCATION_*</code> constants
     */
    public int getChildLocation(View child) {
        StackViewState childViewState = mCurrentStackScrollState.getViewStateForView(child);
        if (childViewState == null) {
            return StackViewState.LOCATION_UNKNOWN;
        }
        if (childViewState.gone) {
            return StackViewState.LOCATION_GONE;
        }
        return childViewState.location;
    }

    private void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
        updateAlgorithmHeightAndPadding();
    }

    private void updateAlgorithmHeightAndPadding() {
        mAmbientState.setLayoutHeight(getLayoutHeight());
        mAmbientState.setTopPadding(mTopPadding);
    }

    /**
     * Updates the children views according to the stack scroll algorithm. Call this whenever
     * modifications to {@link #mOwnScrollY} are performed to reflect it in the view layout.
     */
    private void updateChildren() {
        updateScrollStateForAddedChildren();
        mAmbientState.setScrollY(mOwnScrollY);
        mStackScrollAlgorithm.getStackScrollState(mAmbientState, mCurrentStackScrollState);
        if (!isCurrentlyAnimating() && !mNeedsAnimation) {
            applyCurrentState();
        } else {
            startAnimationToState();
        }
    }

    private void updateScrollStateForAddedChildren() {
        if (mChildrenToAddAnimated.isEmpty()) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (mChildrenToAddAnimated.contains(child)) {
                int startingPosition = getPositionInLinearLayout(child);
                int padding = child.getIncreasedPaddingAmount() == 1.0f
                        ? mIncreasedPaddingBetweenElements :
                        mPaddingBetweenElements;
                int childHeight = getIntrinsicHeight(child) + padding;
                if (startingPosition < mOwnScrollY) {
                    // This child starts off screen, so let's keep it offscreen to keep the others visible

                    mOwnScrollY += childHeight;
                }
            }
        }
        clampScrollPosition();
    }

    private void updateForcedScroll() {
        if (mForcedScroll != null && (!mForcedScroll.hasFocus()
                || !mForcedScroll.isAttachedToWindow())) {
            mForcedScroll = null;
        }
        if (mForcedScroll != null) {
            ExpandableView expandableView = (ExpandableView) mForcedScroll;
            int positionInLinearLayout = getPositionInLinearLayout(expandableView);
            int targetScroll = targetScrollForView(expandableView, positionInLinearLayout);
            int outOfViewScroll = positionInLinearLayout + expandableView.getIntrinsicHeight();

            targetScroll = Math.max(0, Math.min(targetScroll, getScrollRange()));

            // Only apply the scroll if we're scrolling the view upwards, or the view is so far up
            // that it is not visible anymore.
            if (mOwnScrollY < targetScroll || outOfViewScroll < mOwnScrollY) {
                mOwnScrollY = targetScroll;
            }
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
        mLastSetStackHeight = height;
        setIsExpanded(height > 0.0f);
        int newStackHeight = (int) height;
        int minStackHeight = getLayoutMinHeight();
        int stackHeight;
        float paddingOffset;
        boolean trackingHeadsUp = mTrackingHeadsUp || mHeadsUpManager.hasPinnedHeadsUp();
        int normalUnfoldPositionStart = trackingHeadsUp
                ? mHeadsUpManager.getTopHeadsUpPinnedHeight()
                : minStackHeight;
        if (newStackHeight - mTopPadding - mTopPaddingOverflow >= normalUnfoldPositionStart
                || getNotGoneChildCount() == 0) {
            paddingOffset = mTopPaddingOverflow;
            stackHeight = newStackHeight;
        } else {
            int translationY;
            translationY = newStackHeight - normalUnfoldPositionStart;
            paddingOffset = translationY - mTopPadding;
            stackHeight = (int) (height - (translationY - mTopPadding));
        }
        if (stackHeight != mCurrentStackHeight) {
            mCurrentStackHeight = stackHeight;
            updateAlgorithmHeightAndPadding();
            requestChildrenUpdate();
        }
        setStackTranslation(paddingOffset);
    }

    public float getStackTranslation() {
        return mStackTranslation;
    }

    private void setStackTranslation(float stackTranslation) {
        if (stackTranslation != mStackTranslation) {
            mStackTranslation = stackTranslation;
            mAmbientState.setStackTranslation(stackTranslation);
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

    public int getFirstItemMinHeight() {
        final ExpandableView firstChild = getFirstChildNotGone();
        return firstChild != null ? firstChild.getMinHeight() : mCollapsedSize;
    }

    public int getBottomStackPeekSize() {
        return mBottomStackPeekSize;
    }

    public int getBottomStackSlowDownHeight() {
        return mBottomStackSlowDownHeight;
    }

    public void setLongPressListener(SwipeHelper.LongPressListener listener) {
        mSwipeHelper.setLongPressListener(listener);
        mLongPressListener = listener;
    }

    public void setQsContainer(ViewGroup qsContainer) {
        mQsContainer = qsContainer;
    }

    @Override
    public void onChildDismissed(View v) {
        ExpandableNotificationRow row = (ExpandableNotificationRow) v;
        if (!row.isDismissed()) {
            handleChildDismissed(v);
        }
        ViewGroup transientContainer = row.getTransientContainer();
        if (transientContainer != null) {
            transientContainer.removeTransientView(v);
        }
    }

    private void handleChildDismissed(View v) {
        if (mDismissAllInProgress) {
            return;
        }
        setSwipingInProgress(false);
        if (mDragAnimPendingChildren.contains(v)) {
            // We start the swipe and finish it in the same frame, we don't want any animation
            // for the drag
            mDragAnimPendingChildren.remove(v);
        }
        mSwipedOutViews.add(v);
        mAmbientState.onDragFinished(v);
        updateContinuousShadowDrawing();
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            if (row.isHeadsUp()) {
                mHeadsUpManager.addSwipedOutNotification(row.getStatusBarNotification().getKey());
            }
        }
        performDismiss(v, mGroupManager, false /* fromAccessibility */);

        mFalsingManager.onNotificationDismissed();
        if (mFalsingManager.shouldEnforceBouncer()) {
            mPhoneStatusBar.executeRunnableDismissingKeyguard(null, null /* cancelAction */,
                    false /* dismissShade */, true /* afterKeyguardGone */, false /* deferred */);
        }
    }

    public static void performDismiss(View v, NotificationGroupManager groupManager,
            boolean fromAccessibility) {
        if (!(v instanceof ExpandableNotificationRow)) {
            return;
        }
        ExpandableNotificationRow row = (ExpandableNotificationRow) v;
        if (groupManager.isOnlyChildInGroup(row.getStatusBarNotification())) {
            ExpandableNotificationRow groupSummary =
                    groupManager.getLogicalGroupSummary(row.getStatusBarNotification());
            if (groupSummary.isClearable()) {
                performDismiss(groupSummary, groupManager, fromAccessibility);
            }
        }
        row.setDismissed(true, fromAccessibility);
        if (row.isClearable()) {
            row.performDismiss();
        }
        if (DEBUG) Log.v(TAG, "onChildDismissed: " + v);
    }

    @Override
    public void onChildSnappedBack(View animView, float targetLeft) {
        mAmbientState.onDragFinished(animView);
        updateContinuousShadowDrawing();
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
        if (mCurrIconRow != null && targetLeft == 0) {
            mCurrIconRow.resetState();
            mCurrIconRow = null;
        }
    }

    @Override
    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        if (!mIsExpanded && isPinnedHeadsUp(animView) && canChildBeDismissed(animView)) {
            mScrimController.setTopHeadsUpDragAmount(animView,
                    Math.min(Math.abs(swipeProgress / 2f - 1.0f), 1.0f));
        }
        return true; // Don't fade out the notification
    }

    @Override
    public void onBeginDrag(View v) {
        mFalsingManager.onNotificatonStartDismissing();
        setSwipingInProgress(true);
        mAmbientState.onBeginDrag(v);
        updateContinuousShadowDrawing();
        if (mAnimationsEnabled && (mIsExpanded || !isPinnedHeadsUp(v))) {
            mDragAnimPendingChildren.add(v);
            mNeedsAnimation = true;
        }
        requestChildrenUpdate();
    }

    public static boolean isPinnedHeadsUp(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            return row.isHeadsUp() && row.isPinned();
        }
        return false;
    }

    private boolean isHeadsUp(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            return row.isHeadsUp();
        }
        return false;
    }

    @Override
    public void onDragCancelled(View v) {
        mFalsingManager.onNotificatonStopDismissing();
        setSwipingInProgress(false);
    }

    @Override
    public float getFalsingThresholdFactor() {
        return mPhoneStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        View child = getChildAtPosition(ev.getX(), ev.getY());
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            ExpandableNotificationRow parent = row.getNotificationParent();
            if (parent != null && parent.areChildrenExpanded()
                    && (parent.areGutsExposed()
                        || mGearExposedView == parent
                        || (parent.getNotificationChildren().size() == 1
                                && parent.isClearable()))) {
                // In this case the group is expanded and showing the gear for the
                // group, further interaction should apply to the group, not any
                // child notifications so we use the parent of the child. We also do the same
                // if we only have a single child.
                child = parent;
            }
        }
        return child;
    }

    public ExpandableView getClosestChildAtRawPosition(float touchX, float touchY) {
        getLocationOnScreen(mTempInt2);
        float localTouchY = touchY - mTempInt2[1];

        ExpandableView closestChild = null;
        float minDist = Float.MAX_VALUE;

        // find the view closest to the location, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView slidingChild = (ExpandableView) getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE
                    || slidingChild instanceof StackScrollerDecorView) {
                continue;
            }
            float childTop = slidingChild.getTranslationY();
            float top = childTop + slidingChild.getClipTopAmount();
            float bottom = childTop + slidingChild.getActualHeight();

            float dist = Math.min(Math.abs(top - localTouchY), Math.abs(bottom - localTouchY));
            if (dist < minDist) {
                closestChild = slidingChild;
                minDist = dist;
            }
        }
        return closestChild;
    }

    @Override
    public ExpandableView getChildAtRawPosition(float touchX, float touchY) {
        getLocationOnScreen(mTempInt2);
        return getChildAtPosition(touchX - mTempInt2[0], touchY - mTempInt2[1]);
    }

    @Override
    public ExpandableView getChildAtPosition(float touchX, float touchY) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView slidingChild = (ExpandableView) getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE
                    || slidingChild instanceof StackScrollerDecorView) {
                continue;
            }
            float childTop = slidingChild.getTranslationY();
            float top = childTop + slidingChild.getClipTopAmount();
            float bottom = childTop + slidingChild.getActualHeight();

            // Allow the full width of this view to prevent gesture conflict on Keyguard (phone and
            // camera affordance).
            int left = 0;
            int right = getWidth();

            if (touchY >= top && touchY <= bottom && touchX >= left && touchX <= right) {
                if (slidingChild instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) slidingChild;
                    if (!mIsExpanded && row.isHeadsUp() && row.isPinned()
                            && mHeadsUpManager.getTopEntry().entry.row != row
                            && mGroupManager.getGroupSummary(
                                mHeadsUpManager.getTopEntry().entry.row.getStatusBarNotification())
                                != row) {
                        continue;
                    }
                    return row.getViewAtPosition(touchY - childTop);
                }
                return slidingChild;
            }
        }
        return null;
    }

    @Override
    public boolean canChildBeExpanded(View v) {
        return v instanceof ExpandableNotificationRow
                && ((ExpandableNotificationRow) v).isExpandable()
                && !((ExpandableNotificationRow) v).areGutsExposed()
                && (mIsExpanded || !((ExpandableNotificationRow) v).isPinned());
    }

    /* Only ever called as a consequence of an expansion gesture in the shade. */
    @Override
    public void setUserExpandedChild(View v, boolean userExpanded) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            row.setUserExpanded(userExpanded, true /* allowChildrenExpansion */);
            row.onExpandedByGesture(userExpanded);
        }
    }

    @Override
    public void setExpansionCancelled(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ((ExpandableNotificationRow) v).setGroupExpansionChanging(false);
        }
    }

    @Override
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

    @Override
    public int getMaxExpandHeight(ExpandableView view) {
        int maxContentHeight = view.getMaxContentHeight();
        if (view.isSummaryWithChildren()) {
            // Faking a measure with the group expanded to simulate how the group would look if
            // it was. Doing a calculation here would be highly non-trivial because of the
            // algorithm
            mGroupExpandedForMeasure = true;
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            mGroupManager.toggleGroupExpansion(row.getStatusBarNotification());
            row.setForceUnlocked(true);
            mAmbientState.setLayoutHeight(mMaxLayoutHeight);
            mStackScrollAlgorithm.getStackScrollState(mAmbientState, mCurrentStackScrollState);
            mAmbientState.setLayoutHeight(getLayoutHeight());
            mGroupManager.toggleGroupExpansion(
                    row.getStatusBarNotification());
            mGroupExpandedForMeasure = false;
            row.setForceUnlocked(false);
            int height = mCurrentStackScrollState.getViewStateForView(view).height;
            return Math.min(height, maxContentHeight);
        }
        return maxContentHeight;
    }

    public void setScrollingEnabled(boolean enable) {
        mScrollingEnabled = enable;
    }

    @Override
    public void lockScrollTo(View v) {
        if (mForcedScroll == v) {
            return;
        }
        mForcedScroll = v;
        scrollTo(v);
    }

    @Override
    public boolean scrollTo(View v) {
        ExpandableView expandableView = (ExpandableView) v;
        int positionInLinearLayout = getPositionInLinearLayout(v);
        int targetScroll = targetScrollForView(expandableView, positionInLinearLayout);
        int outOfViewScroll = positionInLinearLayout + expandableView.getIntrinsicHeight();

        // Only apply the scroll if we're scrolling the view upwards, or the view is so far up
        // that it is not visible anymore.
        if (mOwnScrollY < targetScroll || outOfViewScroll < mOwnScrollY) {
            mScroller.startScroll(mScrollX, mOwnScrollY, 0, targetScroll - mOwnScrollY);
            mDontReportNextOverScroll = true;
            postInvalidateOnAnimation();
            return true;
        }
        return false;
    }

    /**
     * @return the scroll necessary to make the bottom edge of {@param v} align with the top of
     *         the IME.
     */
    private int targetScrollForView(ExpandableView v, int positionInLinearLayout) {
        return positionInLinearLayout + v.getIntrinsicHeight() +
                getImeInset() - getHeight() + getTopPadding();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mBottomInset = insets.getSystemWindowInsetBottom();

        int range = getScrollRange();
        if (mOwnScrollY > range) {
            // HACK: We're repeatedly getting staggered insets here while the IME is
            // animating away. To work around that we'll wait until things have settled.
            removeCallbacks(mReclamp);
            postDelayed(mReclamp, 50);
        } else if (mForcedScroll != null) {
            // The scroll was requested before we got the actual inset - in case we need
            // to scroll up some more do so now.
            scrollTo(mForcedScroll);
        }
        return insets;
    }

    private Runnable mReclamp = new Runnable() {
        @Override
        public void run() {
            int range = getScrollRange();
            mScroller.startScroll(mScrollX, mOwnScrollY, 0, range - mOwnScrollY);
            mDontReportNextOverScroll = true;
            mDontClampNextScroll = true;
            postInvalidateOnAnimation();
        }
    };

    public void setExpandingEnabled(boolean enable) {
        mExpandHelper.setEnabled(enable);
    }

    private boolean isScrollingEnabled() {
        return mScrollingEnabled;
    }

    @Override
    public boolean canChildBeDismissed(View v) {
        return StackScrollAlgorithm.canChildBeDismissed(v);
    }

    @Override
    public boolean isAntiFalsingNeeded() {
        return mPhoneStatusBar.getBarState() == StatusBarState.KEYGUARD;
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
        mSwipeHelper.dismissChild(child, 0, endRunnable, delay, true, duration,
                true /* isDismissAll */);
    }

    public void snapViewIfNeeded(ExpandableNotificationRow child) {
        boolean animate = mIsExpanded || isPinnedHeadsUp(child);
        // If the child is showing the gear to go to settings, snap to that
        float targetLeft = child.getSettingsRow().isVisible() ? child.getTranslation() : 0;
        mSwipeHelper.snapChildIfNeeded(child, animate, targetLeft);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean isCancelOrUp = ev.getActionMasked() == MotionEvent.ACTION_CANCEL
                || ev.getActionMasked()== MotionEvent.ACTION_UP;
        handleEmptySpaceClick(ev);
        boolean expandWantsIt = false;
        if (mIsExpanded && !mSwipingInProgress && !mOnlyScrollingInThisMotion) {
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
        if (mIsExpanded && !mSwipingInProgress && !mExpandingNotification
                && !mDisallowScrollingInThisMotion) {
            scrollerWantsIt = onScrollTouch(ev);
        }
        boolean horizontalSwipeWantsIt = false;
        if (!mIsBeingDragged
                && !mExpandingNotification
                && !mExpandedInThisMotion
                && !mOnlyScrollingInThisMotion
                && !mDisallowDismissInThisMotion) {
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
        if (ev.getY() < mQsContainer.getBottom()) {
            return false;
        }
        mForcedScroll = null;
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

    public void setFinishScrollingCallback(Runnable runnable) {
        mFinishScrollingCallback = runnable;
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
                int range = getScrollRange();
                if (y < 0 && oldY >= 0 || y > range && oldY <= range) {
                    float currVelocity = mScroller.getCurrVelocity();
                    if (currVelocity >= mMinimumVelocity) {
                        mMaxOverScroll = Math.abs(currVelocity) / 1000 * mOverflingDistance;
                    }
                }

                if (mDontClampNextScroll) {
                    range = Math.max(range, oldY);
                }
                overScrollBy(x - oldX, y - oldY, oldX, oldY, 0, range,
                        0, (int) (mMaxOverScroll), false);
                onScrollChanged(mScrollX, mOwnScrollY, oldX, oldY);
            }

            // Keep on drawing until the animation has finished.
            postInvalidateOnAnimation();
        } else {
            mDontClampNextScroll = false;
            if (mFinishScrollingCallback != null) {
                mFinishScrollingCallback.run();
            }
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
        int contentHeight = getContentHeight();
        int scrollRange = Math.max(0, contentHeight - mMaxLayoutHeight + mBottomStackPeekSize
                + mBottomStackSlowDownHeight);
        int imeInset = getImeInset();
        scrollRange += Math.min(imeInset, Math.max(0,
                getContentHeight() - (getHeight() - imeInset)));
        return scrollRange;
    }

    private int getImeInset() {
        return Math.max(0, mBottomInset - (getRootView().getHeight() - getHeight()));
    }

    /**
     * @return the first child which has visibility unequal to GONE
     */
    public ExpandableView getFirstChildNotGone() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                return (ExpandableView) child;
            }
        }
        return null;
    }

    /**
     * @return the child before the given view which has visibility unequal to GONE
     */
    public ExpandableView getViewBeforeView(ExpandableView view) {
        ExpandableView previousView = null;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child == view) {
                return previousView;
            }
            if (child.getVisibility() != View.GONE) {
                previousView = (ExpandableView) child;
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
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() != View.GONE && !child.willBeGone()) {
                count++;
            }
        }
        return count;
    }

    public int getContentHeight() {
        return mContentHeight;
    }

    private void updateContentHeight() {
        int height = 0;
        float previousIncreasedAmount = 0.0f;
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView expandableView = (ExpandableView) getChildAt(i);
            if (expandableView.getVisibility() != View.GONE) {
                float increasedPaddingAmount = expandableView.getIncreasedPaddingAmount();
                if (height != 0) {
                    height += (int) NotificationUtils.interpolate(
                            mPaddingBetweenElements,
                            mIncreasedPaddingBetweenElements,
                            Math.max(previousIncreasedAmount, increasedPaddingAmount));
                }
                previousIncreasedAmount = increasedPaddingAmount;
                height += expandableView.getIntrinsicHeight();
            }
        }
        mContentHeight = height + mTopPadding;
        updateScrollability();
    }

    private void updateScrollability() {
        boolean scrollable = getScrollRange() > 0;
        if (scrollable != mScrollable) {
            mScrollable = scrollable;
            setFocusable(scrollable);
        }
    }

    private void updateBackground() {
        if (mAmbientState.isDark()) {
            return;
        }
        updateBackgroundBounds();
        if (!mCurrentBounds.equals(mBackgroundBounds)) {
            if (mAnimateNextBackgroundTop || mAnimateNextBackgroundBottom || areBoundsAnimating()) {
                startBackgroundAnimation();
            } else {
                mCurrentBounds.set(mBackgroundBounds);
                applyCurrentBackgroundBounds();
            }
        } else {
            if (mBottomAnimator != null) {
                mBottomAnimator.cancel();
            }
            if (mTopAnimator != null) {
                mTopAnimator.cancel();
            }
        }
        mAnimateNextBackgroundBottom = false;
        mAnimateNextBackgroundTop = false;
    }

    private boolean areBoundsAnimating() {
        return mBottomAnimator != null || mTopAnimator != null;
    }

    private void startBackgroundAnimation() {
        // left and right are always instantly applied
        mCurrentBounds.left = mBackgroundBounds.left;
        mCurrentBounds.right = mBackgroundBounds.right;
        startBottomAnimation();
        startTopAnimation();
    }

    private void startTopAnimation() {
        int previousEndValue = mEndAnimationRect.top;
        int newEndValue = mBackgroundBounds.top;
        ObjectAnimator previousAnimator = mTopAnimator;
        if (previousAnimator != null && previousEndValue == newEndValue) {
            return;
        }
        if (!mAnimateNextBackgroundTop) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                int previousStartValue = mStartAnimationRect.top;
                PropertyValuesHolder[] values = previousAnimator.getValues();
                values[0].setIntValues(previousStartValue, newEndValue);
                mStartAnimationRect.top = previousStartValue;
                mEndAnimationRect.top = newEndValue;
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                setBackgroundTop(newEndValue);
                return;
            }
        }
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(this, "backgroundTop",
                mCurrentBounds.top, newEndValue);
        Interpolator interpolator = Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        animator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mStartAnimationRect.top = -1;
                mEndAnimationRect.top = -1;
                mTopAnimator = null;
            }
        });
        animator.start();
        mStartAnimationRect.top = mCurrentBounds.top;
        mEndAnimationRect.top = newEndValue;
        mTopAnimator = animator;
    }

    private void startBottomAnimation() {
        int previousStartValue = mStartAnimationRect.bottom;
        int previousEndValue = mEndAnimationRect.bottom;
        int newEndValue = mBackgroundBounds.bottom;
        ObjectAnimator previousAnimator = mBottomAnimator;
        if (previousAnimator != null && previousEndValue == newEndValue) {
            return;
        }
        if (!mAnimateNextBackgroundBottom) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                values[0].setIntValues(previousStartValue, newEndValue);
                mStartAnimationRect.bottom = previousStartValue;
                mEndAnimationRect.bottom = newEndValue;
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                setBackgroundBottom(newEndValue);
                return;
            }
        }
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(this, "backgroundBottom",
                mCurrentBounds.bottom, newEndValue);
        Interpolator interpolator = Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        animator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mStartAnimationRect.bottom = -1;
                mEndAnimationRect.bottom = -1;
                mBottomAnimator = null;
            }
        });
        animator.start();
        mStartAnimationRect.bottom = mCurrentBounds.bottom;
        mEndAnimationRect.bottom = newEndValue;
        mBottomAnimator = animator;
    }

    private void setBackgroundTop(int top) {
        mCurrentBounds.top = top;
        applyCurrentBackgroundBounds();
    }

    public void setBackgroundBottom(int bottom) {
        mCurrentBounds.bottom = bottom;
        applyCurrentBackgroundBounds();
    }

    private void applyCurrentBackgroundBounds() {
        if (!mFadingOut) {
            mScrimController.setExcludedBackgroundArea(mCurrentBounds);
        }
        invalidate();
    }

    /**
     * Update the background bounds to the new desired bounds
     */
    private void updateBackgroundBounds() {
        mBackgroundBounds.left = (int) getX();
        mBackgroundBounds.right = (int) (getX() + getWidth());
        if (!mIsExpanded) {
            mBackgroundBounds.top = 0;
            mBackgroundBounds.bottom = 0;
        }
        ActivatableNotificationView firstView = mFirstVisibleBackgroundChild;
        int top = 0;
        if (firstView != null) {
            int finalTranslationY = (int) StackStateAnimator.getFinalTranslationY(firstView);
            if (mAnimateNextBackgroundTop
                    || mTopAnimator == null && mCurrentBounds.top == finalTranslationY
                    || mTopAnimator != null && mEndAnimationRect.top == finalTranslationY) {
                // we're ending up at the same location as we are now, lets just skip the animation
                top = finalTranslationY;
            } else {
                top = (int) firstView.getTranslationY();
            }
        }
        ActivatableNotificationView lastView = mLastVisibleBackgroundChild;
        int bottom = 0;
        if (lastView != null) {
            int finalTranslationY = (int) StackStateAnimator.getFinalTranslationY(lastView);
            int finalHeight = StackStateAnimator.getFinalActualHeight(lastView);
            int finalBottom = finalTranslationY + finalHeight;
            finalBottom = Math.min(finalBottom, getHeight());
            if (mAnimateNextBackgroundBottom
                    || mBottomAnimator == null && mCurrentBounds.bottom == finalBottom
                    || mBottomAnimator != null && mEndAnimationRect.bottom == finalBottom) {
                // we're ending up at the same location as we are now, lets just skip the animation
                bottom = finalBottom;
            } else {
                bottom = (int) (lastView.getTranslationY() + lastView.getActualHeight());
                bottom = Math.min(bottom, getHeight());
            }
        } else {
            top = (int) (mTopPadding + mStackTranslation);
            bottom = top;
        }
        if (mPhoneStatusBar.getBarState() != StatusBarState.KEYGUARD) {
            mBackgroundBounds.top = (int) Math.max(mTopPadding + mStackTranslation, top);
        } else {
            // otherwise the animation from the shade to the keyguard will jump as it's maxed
            mBackgroundBounds.top = Math.max(0, top);
        }
        mBackgroundBounds.bottom = Math.min(getHeight(), Math.max(bottom, top));
    }

    private ActivatableNotificationView getFirstPinnedHeadsUp() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE
                    && child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (row.isPinned()) {
                    return row;
                }
            }
        }
        return null;
    }

    private ActivatableNotificationView getLastChildWithBackground() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE
                    && child instanceof ActivatableNotificationView) {
                return (ActivatableNotificationView) child;
            }
        }
        return null;
    }

    private ActivatableNotificationView getFirstChildWithBackground() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE
                    && child instanceof ActivatableNotificationView) {
                return (ActivatableNotificationView) child;
            }
        }
        return null;
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
            int minScrollY = Math.max(0, scrollRange);
            if (mExpandedInThisMotion) {
                minScrollY = Math.min(minScrollY, mMaxScrollAfterExpand);
            }
            mScroller.fling(mScrollX, mOwnScrollY, 1, velocityY, 0, 0, 0,
                    minScrollY, 0, mExpandedInThisMotion && mOwnScrollY >= 0 ? 0 : Integer.MAX_VALUE / 2);

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

    /**
     * Updates the top padding of the notifications, taking {@link #getIntrinsicPadding()} into
     * account.
     *
     * @param qsHeight the top padding imposed by the quick settings panel
     * @param animate whether to animate the change
     * @param ignoreIntrinsicPadding if true, {@link #getIntrinsicPadding()} is ignored and
     *                               {@code qsHeight} is the final top padding
     */
    public void updateTopPadding(float qsHeight, boolean animate,
            boolean ignoreIntrinsicPadding) {
        float start = qsHeight;
        float stackHeight = getHeight() - start;
        int minStackHeight = getLayoutMinHeight();
        if (stackHeight <= minStackHeight) {
            float overflow = minStackHeight - stackHeight;
            stackHeight = minStackHeight;
            start = getHeight() - stackHeight;
            mTopPaddingOverflow = overflow;
        } else {
            mTopPaddingOverflow = 0;
        }
        setTopPadding(ignoreIntrinsicPadding ? (int) start : clampPadding((int) start),
                animate);
        setStackHeight(mLastSetStackHeight);
    }

    public int getLayoutMinHeight() {
        final ExpandableView firstChild = getFirstChildNotGone();
        int firstChildMinHeight = firstChild != null
                ? firstChild.getIntrinsicHeight()
                : mEmptyShadeView != null
                        ? mEmptyShadeView.getMinHeight()
                        : mCollapsedSize;
        if (mOwnScrollY > 0) {
            firstChildMinHeight = Math.max(firstChildMinHeight - mOwnScrollY, mCollapsedSize);
        }
        return Math.min(firstChildMinHeight + mBottomStackPeekSize + mBottomStackSlowDownHeight,
                mMaxLayoutHeight - mTopPadding);
    }

    public float getTopPaddingOverflow() {
        return mTopPaddingOverflow;
    }

    public int getPeekHeight() {
        final ExpandableView firstChild = getFirstChildNotGone();
        final int firstChildMinHeight = firstChild != null ? firstChild.getCollapsedHeight()
                : mCollapsedSize;
        return mIntrinsicPadding + firstChildMinHeight + mBottomStackPeekSize
                + mBottomStackSlowDownHeight;
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
        } else if (mIsExpansionChanging || mPanelTracking) {
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
        return !onTop || mExpandedInThisMotion || mIsExpansionChanging || mPanelTracking
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
        initDownStates(ev);
        handleEmptySpaceClick(ev);
        boolean expandWantsIt = false;
        if (!mSwipingInProgress && !mOnlyScrollingInThisMotion) {
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
                && !mOnlyScrollingInThisMotion
                && !mDisallowDismissInThisMotion) {
            swipeWantsIt = mSwipeHelper.onInterceptTouchEvent(ev);
        }
        return swipeWantsIt || scrollWantsIt || expandWantsIt || super.onInterceptTouchEvent(ev);
    }

    private void handleEmptySpaceClick(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (mTouchIsClick && (Math.abs(ev.getY() - mInitialTouchY) > mTouchSlop
                        || Math.abs(ev.getX() - mInitialTouchX) > mTouchSlop )) {
                    mTouchIsClick = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mPhoneStatusBar.getBarState() != StatusBarState.KEYGUARD && mTouchIsClick &&
                        isBelowLastNotification(mInitialTouchX, mInitialTouchY)) {
                    mOnEmptySpaceClickListener.onEmptySpaceClicked(mInitialTouchX, mInitialTouchY);
                }
                break;
        }
    }

    private void initDownStates(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mExpandedInThisMotion = false;
            mOnlyScrollingInThisMotion = !mScroller.isFinished();
            mDisallowScrollingInThisMotion = false;
            mDisallowDismissInThisMotion = false;
            mTouchIsClick = true;
            mInitialTouchX = ev.getX();
            mInitialTouchY = ev.getY();
        }
    }

    public void setChildTransferInProgress(boolean childTransferInProgress) {
        mChildTransferInProgress = childTransferInProgress;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        // we only call our internal methods if this is actually a removal and not just a
        // notification which becomes a child notification
        if (!mChildTransferInProgress) {
            onViewRemovedInternal(child, this);
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        if (disallowIntercept) {
            mSwipeHelper.removeLongPressCallback();
        }
    }

    private void onViewRemovedInternal(View child, ViewGroup container) {
        if (mChangePositionInProgress) {
            // This is only a position change, don't do anything special
            return;
        }
        ExpandableView expandableView = (ExpandableView) child;
        expandableView.setOnHeightChangedListener(null);
        mCurrentStackScrollState.removeViewStateForView(child);
        updateScrollStateForRemovedChild(expandableView);
        boolean animationGenerated = generateRemoveAnimation(child);
        if (animationGenerated) {
            if (!mSwipedOutViews.contains(child)) {
                container.getOverlay().add(child);
            } else if (Math.abs(expandableView.getTranslation()) != expandableView.getWidth()) {
                container.addTransientView(child, 0);
                expandableView.setTransientContainer(container);
            }
        } else {
            mSwipedOutViews.remove(child);
        }
        updateAnimationState(false, child);

        // Make sure the clipRect we might have set is removed
        expandableView.setClipTopAmount(0);

        focusNextViewIfFocused(child);
    }

    private void focusNextViewIfFocused(View view) {
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (row.shouldRefocusOnDismiss()) {
                View nextView = row.getChildAfterViewWhenDismissed();
                if (nextView == null) {
                    View groupParentWhenDismissed = row.getGroupParentWhenDismissed();
                    nextView = getFirstChildBelowTranlsationY(groupParentWhenDismissed != null
                            ? groupParentWhenDismissed.getTranslationY()
                            : view.getTranslationY());
                }
                if (nextView != null) {
                    nextView.requestAccessibilityFocus();
                }
            }
        }

    }

    private boolean isChildInGroup(View child) {
        return child instanceof ExpandableNotificationRow
                && mGroupManager.isChildInGroupWithSummary(
                        ((ExpandableNotificationRow) child).getStatusBarNotification());
    }

    /**
     * Generate a remove animation for a child view.
     *
     * @param child The view to generate the remove animation for.
     * @return Whether an animation was generated.
     */
    private boolean generateRemoveAnimation(View child) {
        if (removeRemovedChildFromHeadsUpChangeAnimations(child)) {
            mAddedHeadsUpChildren.remove(child);
            return false;
        }
        if (isClickedHeadsUp(child)) {
            // An animation is already running, add it to the Overlay
            mClearOverlayViewsWhenFinished.add(child);
            return true;
        }
        if (mIsExpanded && mAnimationsEnabled && !isChildInInvisibleGroup(child)) {
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

    private boolean isClickedHeadsUp(View child) {
        return HeadsUpManager.isClickedHeadsUpNotification(child);
    }

    /**
     * Remove a removed child view from the heads up animations if it was just added there
     *
     * @return whether any child was removed from the list to animate
     */
    private boolean removeRemovedChildFromHeadsUpChangeAnimations(View child) {
        boolean hasAddEvent = false;
        for (Pair<ExpandableNotificationRow, Boolean> eventPair : mHeadsUpChangeAnimations) {
            ExpandableNotificationRow row = eventPair.first;
            boolean isHeadsUp = eventPair.second;
            if (child == row) {
                mTmpList.add(eventPair);
                hasAddEvent |= isHeadsUp;
            }
        }
        if (hasAddEvent) {
            // This child was just added lets remove all events.
            mHeadsUpChangeAnimations.removeAll(mTmpList);
            ((ExpandableNotificationRow ) child).setHeadsupDisappearRunning(false);
        }
        mTmpList.clear();
        return hasAddEvent;
    }

    /**
     * @param child the child to query
     * @return whether a view is not a top level child but a child notification and that group is
     *         not expanded
     */
    private boolean isChildInInvisibleGroup(View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            ExpandableNotificationRow groupSummary =
                    mGroupManager.getGroupSummary(row.getStatusBarNotification());
            if (groupSummary != null && groupSummary != row) {
                return row.getVisibility() == View.INVISIBLE;
            }
        }
        return false;
    }

    /**
     * Updates the scroll position when a child was removed
     *
     * @param removedChild the removed child
     */
    private void updateScrollStateForRemovedChild(ExpandableView removedChild) {
        int startingPosition = getPositionInLinearLayout(removedChild);
        int padding = (int) NotificationUtils.interpolate(
                mPaddingBetweenElements,
                mIncreasedPaddingBetweenElements,
                removedChild.getIncreasedPaddingAmount());
        int childHeight = getIntrinsicHeight(removedChild) + padding;
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

    private int getPositionInLinearLayout(View requestedView) {
        ExpandableNotificationRow childInGroup = null;
        ExpandableNotificationRow requestedRow = null;
        if (isChildInGroup(requestedView)) {
            // We're asking for a child in a group. Calculate the position of the parent first,
            // then within the parent.
            childInGroup = (ExpandableNotificationRow) requestedView;
            requestedView = requestedRow = childInGroup.getNotificationParent();
        }
        int position = 0;
        float previousIncreasedAmount = 0.0f;
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            boolean notGone = child.getVisibility() != View.GONE;
            if (notGone) {
                float increasedPaddingAmount = child.getIncreasedPaddingAmount();
                if (position != 0) {
                    position += (int) NotificationUtils.interpolate(
                            mPaddingBetweenElements,
                            mIncreasedPaddingBetweenElements,
                            Math.max(previousIncreasedAmount, increasedPaddingAmount));
                }
                previousIncreasedAmount = increasedPaddingAmount;
            }
            if (child == requestedView) {
                if (requestedRow != null) {
                    position += requestedRow.getPositionOfChild(childInGroup);
                }
                return position;
            }
            if (notGone) {
                position += getIntrinsicHeight(child);
            }
        }
        return 0;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        onViewAddedInternal(child);
    }

    private void updateFirstAndLastBackgroundViews() {
        ActivatableNotificationView firstChild = getFirstChildWithBackground();
        ActivatableNotificationView lastChild = getLastChildWithBackground();
        if (mAnimationsEnabled && mIsExpanded) {
            mAnimateNextBackgroundTop = firstChild != mFirstVisibleBackgroundChild;
            mAnimateNextBackgroundBottom = lastChild != mLastVisibleBackgroundChild;
        } else {
            mAnimateNextBackgroundTop = false;
            mAnimateNextBackgroundBottom = false;
        }
        mFirstVisibleBackgroundChild = firstChild;
        mLastVisibleBackgroundChild = lastChild;
    }

    private void onViewAddedInternal(View child) {
        updateHideSensitiveForChild(child);
        ((ExpandableView) child).setOnHeightChangedListener(this);
        generateAddAnimation(child, false /* fromMoreCard */);
        updateAnimationState(child);
        updateChronometerForChild(child);
    }

    private void updateHideSensitiveForChild(View child) {
        if (child instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) child;
            expandableView.setHideSensitiveForIntrinsicHeight(mAmbientState.isHideSensitive());
        }
    }

    public void notifyGroupChildRemoved(View row, ViewGroup childrenContainer) {
        onViewRemovedInternal(row, childrenContainer);
    }

    public void notifyGroupChildAdded(View row) {
        onViewAddedInternal(row);
    }

    public void setAnimationsEnabled(boolean animationsEnabled) {
        mAnimationsEnabled = animationsEnabled;
        updateNotificationAnimationStates();
    }

    private void updateNotificationAnimationStates() {
        boolean running = mAnimationsEnabled || mPulsing;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            running &= mIsExpanded || isPinnedHeadsUp(child);
            updateAnimationState(running, child);
        }
    }

    private void updateAnimationState(View child) {
        updateAnimationState((mAnimationsEnabled || mPulsing)
                && (mIsExpanded || isPinnedHeadsUp(child)), child);
    }


    private void updateAnimationState(boolean running, View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            row.setIconAnimationRunning(running);
        }
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
        if (isHeadsUp(child) && !mChangePositionInProgress) {
            mAddedHeadsUpChildren.add(child);
            mChildrenToAddAnimated.remove(child);
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
            ((ExpandableView)child).setChangingPosition(true);
            removeView(child);
            addView(child, newIndex);
            ((ExpandableView)child).setChangingPosition(false);
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
            setAnimationRunning(true);
            mStateAnimator.startAnimationForEvents(mAnimationEvents, mCurrentStackScrollState,
                    mGoToFullShadeDelay);
            mAnimationEvents.clear();
            updateBackground();
            updateViewShadows();
        } else {
            applyCurrentState();
        }
        mGoToFullShadeDelay = 0;
    }

    private void generateChildHierarchyEvents() {
        generateHeadsUpAnimationEvents();
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
        generateGroupExpansionEvent();
        generateAnimateEverythingEvent();
        mNeedsAnimation = false;
    }

    private void generateHeadsUpAnimationEvents() {
        for (Pair<ExpandableNotificationRow, Boolean> eventPair : mHeadsUpChangeAnimations) {
            ExpandableNotificationRow row = eventPair.first;
            boolean isHeadsUp = eventPair.second;
            int type = AnimationEvent.ANIMATION_TYPE_HEADS_UP_OTHER;
            boolean onBottom = false;
            boolean pinnedAndClosed = row.isPinned() && !mIsExpanded;
            if (!mIsExpanded && !isHeadsUp) {
                type = row.wasJustClicked()
                        ? AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                        : AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR;
                if (row.isChildInGroup()) {
                    // We can otherwise get stuck in there if it was just isolated
                    row.setHeadsupDisappearRunning(false);
                }
            } else {
                StackViewState viewState = mCurrentStackScrollState.getViewStateForView(row);
                if (viewState == null) {
                    // A view state was never generated for this view, so we don't need to animate
                    // this. This may happen with notification children.
                    continue;
                }
                if (isHeadsUp && (mAddedHeadsUpChildren.contains(row) || pinnedAndClosed)) {
                    if (pinnedAndClosed || shouldHunAppearFromBottom(viewState)) {
                        // Our custom add animation
                        type = AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR;
                    } else {
                        // Normal add animation
                        type = AnimationEvent.ANIMATION_TYPE_ADD;
                    }
                    onBottom = !pinnedAndClosed;
                }
            }
            AnimationEvent event = new AnimationEvent(row, type);
            event.headsUpFromBottom = onBottom;
            mAnimationEvents.add(event);
        }
        mHeadsUpChangeAnimations.clear();
        mAddedHeadsUpChildren.clear();
    }

    private boolean shouldHunAppearFromBottom(StackViewState viewState) {
        if (viewState.yTranslation + viewState.height < mAmbientState.getMaxHeadsUpTranslation()) {
            return false;
        }
        return true;
    }

    private void generateGroupExpansionEvent() {
        // Generate a group expansion/collapsing event if there is such a group at all
        if (mExpandedGroupView != null) {
            mAnimationEvents.add(new AnimationEvent(mExpandedGroupView,
                    AnimationEvent.ANIMATION_TYPE_GROUP_EXPANSION_CHANGED));
            mExpandedGroupView = null;
        }
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
            mSwipedOutViews.remove(child);
        }
        mChildrenToRemoveAnimated.clear();
    }

    private void generatePositionChangeEvents() {
        for (View child : mChildrenChangingPositions) {
            mAnimationEvents.add(new AnimationEvent(child,
                    AnimationEvent.ANIMATION_TYPE_CHANGE_POSITION));
        }
        mChildrenChangingPositions.clear();
        if (mGenerateChildOrderChangedEvent) {
            mAnimationEvents.add(new AnimationEvent(null,
                    AnimationEvent.ANIMATION_TYPE_CHANGE_POSITION));
            mGenerateChildOrderChangedEvent = false;
        }
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

    private void generateAnimateEverythingEvent() {
        if (mEverythingNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_EVERYTHING));
        }
        mEverythingNeedsAnimation = false;
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
            AnimationEvent ev = new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_DARK);
            ev.darkAnimationOriginIndex = mDarkAnimationOriginIndex;
            mAnimationEvents.add(ev);
            startBackgroundFadeIn();
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
        * state and is moving their finger.  We want to intercept this
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
                 * whether the user has moved far enough from the original down touch.
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
                mScrolledToTopOnFirstDown = isScrolledToTop();
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
        return isInContentBounds(event.getY());
    }

    /**
     * @return Whether a y coordinate is inside the content.
     */
    public boolean isInContentBounds(float y) {
        return y < getHeight() - getEmptyBottomMargin();
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

    @Override
    public void clearChildFocus(View child) {
        super.clearChildFocus(child);
        if (mForcedScroll == child) {
            mForcedScroll = null;
        }
    }

    @Override
    public void requestDisallowLongPress() {
        removeLongPressCallback();
    }

    @Override
    public void requestDisallowDismiss() {
        mDisallowDismissInThisMotion = true;
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
        int emptyMargin = mMaxLayoutHeight - mContentHeight - mBottomStackPeekSize
                - mBottomStackSlowDownHeight;
        return Math.max(emptyMargin, 0);
    }

    public float getKeyguardBottomStackSize() {
        return mBottomStackPeekSize + getResources().getDimensionPixelSize(
                R.dimen.bottom_stack_slow_down_length);
    }

    public void onExpansionStarted() {
        mIsExpansionChanging = true;
    }

    public void onExpansionStopped() {
        mIsExpansionChanging = false;
        if (!mIsExpanded) {
            mOwnScrollY = 0;
            mPhoneStatusBar.resetUserExpandedStates();

            // lets make sure nothing is in the overlay / transient anymore
            clearTemporaryViews(this);
            for (int i = 0; i < getChildCount(); i++) {
                ExpandableView child = (ExpandableView) getChildAt(i);
                if (child instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                    clearTemporaryViews(row.getChildrenContainer());
                }
            }
        }
    }

    private void clearTemporaryViews(ViewGroup viewGroup) {
        while (viewGroup != null && viewGroup.getTransientViewCount() != 0) {
            viewGroup.removeTransientView(viewGroup.getTransientView(0));
        }
        if (viewGroup != null) {
            viewGroup.getOverlay().clear();
        }
    }

    public void onPanelTrackingStarted() {
        mPanelTracking = true;
    }
    public void onPanelTrackingStopped() {
        mPanelTracking = false;
    }

    public void resetScrollPosition() {
        mScroller.abortAnimation();
        mOwnScrollY = 0;
    }

    private void setIsExpanded(boolean isExpanded) {
        boolean changed = isExpanded != mIsExpanded;
        mIsExpanded = isExpanded;
        mStackScrollAlgorithm.setIsExpanded(isExpanded);
        if (changed) {
            if (!mIsExpanded) {
                mGroupManager.collapseAllGroups();
            }
            updateNotificationAnimationStates();
            updateChronometers();
        }
    }

    private void updateChronometers() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            updateChronometerForChild(getChildAt(i));
        }
    }

    private void updateChronometerForChild(View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            row.setChronometerRunning(mIsExpanded);
        }
    }

    @Override
    public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
        updateContentHeight();
        updateScrollPositionOnExpandInBottom(view);
        clampScrollPosition();
        notifyHeightChangeListener(view);
        if (needsAnimation) {
            ExpandableNotificationRow row = view instanceof ExpandableNotificationRow
                    ? (ExpandableNotificationRow) view
                    : null;
            requestAnimationOnViewResize(row);
        }
        requestChildrenUpdate();
    }

    @Override
    public void onReset(ExpandableView view) {
        if (mIsExpanded && mAnimationsEnabled) {
            mRequestViewResizeAnimationOnLayout = true;
        }
        updateAnimationState(view);
        updateChronometerForChild(view);
    }

    private void updateScrollPositionOnExpandInBottom(ExpandableView view) {
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (row.isUserLocked() && row != getFirstChildNotGone()) {
                if (row.isSummaryWithChildren()) {
                    return;
                }
                // We are actually expanding this view
                float endPosition = row.getTranslationY() + row.getActualHeight();
                if (row.isChildInGroup()) {
                    endPosition += row.getNotificationParent().getTranslationY();
                }
                int stackEnd = getStackEndPosition();
                if (endPosition > stackEnd) {
                    mOwnScrollY += endPosition - stackEnd;
                    mDisallowScrollingInThisMotion = true;
                }
            }
        }
    }

    private int getStackEndPosition() {
        return mMaxLayoutHeight - mBottomStackPeekSize - mBottomStackSlowDownHeight
                + mPaddingBetweenElements + (int) mStackTranslation;
    }

    public void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener mOnHeightChangedListener) {
        this.mOnHeightChangedListener = mOnHeightChangedListener;
    }

    public void setOnEmptySpaceClickListener(OnEmptySpaceClickListener listener) {
        mOnEmptySpaceClickListener = listener;
    }

    public void onChildAnimationFinished() {
        setAnimationRunning(false);
        requestChildrenUpdate();
        runAnimationFinishedRunnables();
        clearViewOverlays();
        clearHeadsUpDisappearRunning();
    }

    private void clearHeadsUpDisappearRunning() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                row.setHeadsupDisappearRunning(false);
                if (row.isSummaryWithChildren()) {
                    for (ExpandableNotificationRow child : row.getNotificationChildren()) {
                        child.setHeadsupDisappearRunning(false);
                    }
                }
            }
        }
    }

    private void clearViewOverlays() {
        for (View view : mClearOverlayViewsWhenFinished) {
            StackStateAnimator.removeFromOverlay(view);
        }
    }

    private void runAnimationFinishedRunnables() {
        for (Runnable runnable : mAnimationFinishedRunnables) {
            runnable.run();
        }
        mAnimationFinishedRunnables.clear();
    }

    /**
     * See {@link AmbientState#setDimmed}.
     */
    public void setDimmed(boolean dimmed, boolean animate) {
        mAmbientState.setDimmed(dimmed);
        if (animate && mAnimationsEnabled) {
            mDimmedNeedsAnimation = true;
            mNeedsAnimation =  true;
            animateDimmed(dimmed);
        } else {
            setDimAmount(dimmed ? 1.0f : 0.0f);
        }
        requestChildrenUpdate();
    }

    private void setDimAmount(float dimAmount) {
        mDimAmount = dimAmount;
        updateBackgroundDimming();
    }

    private void animateDimmed(boolean dimmed) {
        if (mDimAnimator != null) {
            mDimAnimator.cancel();
        }
        float target = dimmed ? 1.0f : 0.0f;
        if (target == mDimAmount) {
            return;
        }
        mDimAnimator = TimeAnimator.ofFloat(mDimAmount, target);
        mDimAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_DIMMED_ACTIVATED);
        mDimAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mDimAnimator.addListener(mDimEndListener);
        mDimAnimator.addUpdateListener(mDimUpdateListener);
        mDimAnimator.start();
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
        runAnimationFinishedRunnables();
        setAnimationRunning(false);
        updateBackground();
        updateViewShadows();
    }

    private void updateViewShadows() {
        // we need to work around an issue where the shadow would not cast between siblings when
        // their z difference is between 0 and 0.1

        // Lefts first sort by Z difference
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() != GONE) {
                mTmpSortedChildren.add(child);
            }
        }
        Collections.sort(mTmpSortedChildren, mViewPositionComparator);

        // Now lets update the shadow for the views
        ExpandableView previous = null;
        for (int i = 0; i < mTmpSortedChildren.size(); i++) {
            ExpandableView expandableView = mTmpSortedChildren.get(i);
            float translationZ = expandableView.getTranslationZ();
            float otherZ = previous == null ? translationZ : previous.getTranslationZ();
            float diff = otherZ - translationZ;
            if (diff <= 0.0f || diff >= FakeShadowView.SHADOW_SIBLING_TRESHOLD) {
                // There is no fake shadow to be drawn
                expandableView.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
            } else {
                float yLocation = previous.getTranslationY() + previous.getActualHeight() -
                        expandableView.getTranslationY() - previous.getExtraBottomPadding();
                expandableView.setFakeShadowIntensity(
                        diff / FakeShadowView.SHADOW_SIBLING_TRESHOLD,
                        previous.getOutlineAlpha(), (int) yLocation,
                        previous.getOutlineTranslation());
            }
            previous = expandableView;
        }

        mTmpSortedChildren.clear();
    }

    public void goToFullShade(long delay) {
        mDismissView.setInvisible();
        mEmptyShadeView.setInvisible();
        mGoToFullShadeNeedsAnimation = true;
        mGoToFullShadeDelay = delay;
        mNeedsAnimation = true;
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
        return mTopPadding + getStackTranslation();
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    /**
     * See {@link AmbientState#setDark}.
     */
    public void setDark(boolean dark, boolean animate, @Nullable PointF touchWakeUpScreenLocation) {
        mAmbientState.setDark(dark);
        if (animate && mAnimationsEnabled) {
            mDarkNeedsAnimation = true;
            mDarkAnimationOriginIndex = findDarkAnimationOriginIndex(touchWakeUpScreenLocation);
            mNeedsAnimation =  true;
            setBackgroundFadeAmount(0.0f);
        } else if (!dark) {
            setBackgroundFadeAmount(1.0f);
        }
        requestChildrenUpdate();
        if (dark) {
            setWillNotDraw(!DEBUG);
            mScrimController.setExcludedBackgroundArea(null);
        } else {
            updateBackground();
            setWillNotDraw(false);
        }
    }

    private void setBackgroundFadeAmount(float fadeAmount) {
        mBackgroundFadeAmount = fadeAmount;
        updateBackgroundDimming();
    }

    public float getBackgroundFadeAmount() {
        return mBackgroundFadeAmount;
    }

    private void startBackgroundFadeIn() {
        ObjectAnimator fadeAnimator = ObjectAnimator.ofFloat(this, BACKGROUND_FADE, 0f, 1f);
        int maxLength;
        if (mDarkAnimationOriginIndex == AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_ABOVE
                || mDarkAnimationOriginIndex == AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_BELOW) {
            maxLength = getNotGoneChildCount() - 1;
        } else {
            maxLength = Math.max(mDarkAnimationOriginIndex,
                    getNotGoneChildCount() - mDarkAnimationOriginIndex - 1);
        }
        maxLength = Math.max(0, maxLength);
        long delay = maxLength * StackStateAnimator.ANIMATION_DELAY_PER_ELEMENT_DARK;
        fadeAnimator.setStartDelay(delay);
        fadeAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        fadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        fadeAnimator.start();
    }

    private int findDarkAnimationOriginIndex(@Nullable PointF screenLocation) {
        if (screenLocation == null || screenLocation.y < mTopPadding + mTopPaddingOverflow) {
            return AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_ABOVE;
        }
        if (screenLocation.y > getBottomMostNotificationBottom()) {
            return AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_BELOW;
        }
        View child = getClosestChildAtRawPosition(screenLocation.x, screenLocation.y);
        if (child != null) {
            return getNotGoneIndex(child);
        } else {
            return AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_ABOVE;
        }
    }

    private int getNotGoneIndex(View child) {
        int count = getChildCount();
        int notGoneIndex = 0;
        for (int i = 0; i < count; i++) {
            View v = getChildAt(i);
            if (child == v) {
                return notGoneIndex;
            }
            if (v.getVisibility() != View.GONE) {
                notGoneIndex++;
            }
        }
        return -1;
    }

    public void setDismissView(DismissView dismissView) {
        int index = -1;
        if (mDismissView != null) {
            index = indexOfChild(mDismissView);
            removeView(mDismissView);
        }
        mDismissView = dismissView;
        addView(mDismissView, index);
    }

    public void setEmptyShadeView(EmptyShadeView emptyShadeView) {
        int index = -1;
        if (mEmptyShadeView != null) {
            index = indexOfChild(mEmptyShadeView);
            removeView(mEmptyShadeView);
        }
        mEmptyShadeView = emptyShadeView;
        addView(mEmptyShadeView, index);
    }

    public void updateEmptyShadeView(boolean visible) {
        int oldVisibility = mEmptyShadeView.willBeGone() ? GONE : mEmptyShadeView.getVisibility();
        int newVisibility = visible ? VISIBLE : GONE;
        if (oldVisibility != newVisibility) {
            if (newVisibility != GONE) {
                if (mEmptyShadeView.willBeGone()) {
                    mEmptyShadeView.cancelAnimation();
                } else {
                    mEmptyShadeView.setInvisible();
                }
                mEmptyShadeView.setVisibility(newVisibility);
                mEmptyShadeView.setWillBeGone(false);
                updateContentHeight();
                notifyHeightChangeListener(mEmptyShadeView);
            } else {
                Runnable onFinishedRunnable = new Runnable() {
                    @Override
                    public void run() {
                        mEmptyShadeView.setVisibility(GONE);
                        mEmptyShadeView.setWillBeGone(false);
                        updateContentHeight();
                        notifyHeightChangeListener(mEmptyShadeView);
                    }
                };
                if (mAnimationsEnabled) {
                    mEmptyShadeView.setWillBeGone(true);
                    mEmptyShadeView.performVisibilityAnimation(false, onFinishedRunnable);
                } else {
                    mEmptyShadeView.setInvisible();
                    onFinishedRunnable.run();
                }
            }
        }
    }

    public void setOverflowContainer(NotificationOverflowContainer overFlowContainer) {
        int index = -1;
        if (mOverflowContainer != null) {
            index = indexOfChild(mOverflowContainer);
            removeView(mOverflowContainer);
        }
        mOverflowContainer = overFlowContainer;
        addView(mOverflowContainer, index);
    }

    public void updateOverflowContainerVisibility(boolean visible) {
        int oldVisibility = mOverflowContainer.willBeGone() ? GONE
                : mOverflowContainer.getVisibility();
        final int newVisibility = visible ? VISIBLE : GONE;
        if (oldVisibility != newVisibility) {
            Runnable onFinishedRunnable = new Runnable() {
                @Override
                public void run() {
                    mOverflowContainer.setVisibility(newVisibility);
                    mOverflowContainer.setWillBeGone(false);
                    updateContentHeight();
                    notifyHeightChangeListener(mOverflowContainer);
                }
            };
            if (!mAnimationsEnabled || !mIsExpanded) {
                mOverflowContainer.cancelAppearDrawing();
                onFinishedRunnable.run();
            } else if (newVisibility != GONE) {
                mOverflowContainer.performAddAnimation(0,
                        StackStateAnimator.ANIMATION_DURATION_STANDARD);
                mOverflowContainer.setVisibility(newVisibility);
                mOverflowContainer.setWillBeGone(false);
                updateContentHeight();
                notifyHeightChangeListener(mOverflowContainer);
            } else {
                mOverflowContainer.performRemoveAnimation(
                        StackStateAnimator.ANIMATION_DURATION_STANDARD,
                        0.0f,
                        onFinishedRunnable);
                mOverflowContainer.setWillBeGone(true);
            }
        }
    }

    public void updateDismissView(boolean visible) {
        int oldVisibility = mDismissView.willBeGone() ? GONE : mDismissView.getVisibility();
        int newVisibility = visible ? VISIBLE : GONE;
        if (oldVisibility != newVisibility) {
            if (newVisibility != GONE) {
                if (mDismissView.willBeGone()) {
                    mDismissView.cancelAnimation();
                } else {
                    mDismissView.setInvisible();
                }
                mDismissView.setVisibility(newVisibility);
                mDismissView.setWillBeGone(false);
                updateContentHeight();
                notifyHeightChangeListener(mDismissView);
            } else {
                Runnable dimissHideFinishRunnable = new Runnable() {
                    @Override
                    public void run() {
                        mDismissView.setVisibility(GONE);
                        mDismissView.setWillBeGone(false);
                        updateContentHeight();
                        notifyHeightChangeListener(mDismissView);
                    }
                };
                if (mDismissView.isButtonVisible() && mIsExpanded && mAnimationsEnabled) {
                    mDismissView.setWillBeGone(true);
                    mDismissView.performVisibilityAnimation(false, dimissHideFinishRunnable);
                } else {
                    dimissHideFinishRunnable.run();
                }
            }
        }
    }

    public void setDismissAllInProgress(boolean dismissAllInProgress) {
        mDismissAllInProgress = dismissAllInProgress;
        mAmbientState.setDismissAllInProgress(dismissAllInProgress);
        handleDismissAllClipping();
    }

    private void handleDismissAllClipping() {
        final int count = getChildCount();
        boolean previousChildWillBeDismissed = false;
        for (int i = 0; i < count; i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (mDismissAllInProgress && previousChildWillBeDismissed) {
                child.setMinClipTopAmount(child.getClipTopAmount());
            } else {
                child.setMinClipTopAmount(0);
            }
            previousChildWillBeDismissed = canChildBeDismissed(child);
        }
    }

    public boolean isDismissViewNotGone() {
        return mDismissView.getVisibility() != View.GONE && !mDismissView.willBeGone();
    }

    public boolean isDismissViewVisible() {
        return mDismissView.isVisible();
    }

    public int getDismissViewHeight() {
        return mDismissView.getHeight() + mPaddingBetweenElements;
    }

    public int getEmptyShadeViewHeight() {
        return mEmptyShadeView.getHeight();
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
        return max + getStackTranslation();
    }

    public void setPhoneStatusBar(PhoneStatusBar phoneStatusBar) {
        this.mPhoneStatusBar = phoneStatusBar;
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        this.mGroupManager = groupManager;
    }

    public void onGoToKeyguard() {
        requestAnimateEverything();
    }

    private void requestAnimateEverything() {
        if (mIsExpanded && mAnimationsEnabled) {
            mEverythingNeedsAnimation = true;
            mNeedsAnimation = true;
            requestChildrenUpdate();
        }
    }

    public boolean isBelowLastNotification(float touchX, float touchY) {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                float childTop = child.getY();
                if (childTop > touchY) {
                    // we are above a notification entirely let's abort
                    return false;
                }
                boolean belowChild = touchY > childTop + child.getActualHeight();
                if (child == mDismissView) {
                    if(!belowChild && !mDismissView.isOnEmptySpace(touchX - mDismissView.getX(),
                                    touchY - childTop)) {
                        // We clicked on the dismiss button
                        return false;
                    }
                } else if (child == mEmptyShadeView) {
                    // We arrived at the empty shade view, for which we accept all clicks
                    return true;
                } else if (!belowChild){
                    // We are on a child
                    return false;
                }
            }
        }
        return touchY > mTopPadding + mStackTranslation;
    }

    @Override
    public void onGroupExpansionChanged(ExpandableNotificationRow changedRow, boolean expanded) {
        boolean animated = !mGroupExpandedForMeasure && mAnimationsEnabled
                && (mIsExpanded || changedRow.isPinned());
        if (animated) {
            mExpandedGroupView = changedRow;
            mNeedsAnimation = true;
        }
        changedRow.setChildrenExpanded(expanded, animated);
        if (!mGroupExpandedForMeasure) {
            onHeightChanged(changedRow, false /* needsAnimation */);
        }
        runAfterAnimationFinished(new Runnable() {
            @Override
            public void run() {
                changedRow.onFinishedExpansionChange();
            }
        });
    }

    @Override
    public void onGroupCreatedFromChildren(NotificationGroupManager.NotificationGroup group) {
        mPhoneStatusBar.requestNotificationUpdate();
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        event.setScrollable(mScrollable);
        event.setScrollX(mScrollX);
        event.setScrollY(mOwnScrollY);
        event.setMaxScrollX(mScrollX);
        event.setMaxScrollY(getScrollRange());
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        final int scrollRange = getScrollRange();
        if (scrollRange > 0) {
            info.setScrollable(true);
            if (mScrollY > 0) {
                info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
            }
            if (mScrollY < scrollRange) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
            }
        }
        // Talkback only listenes to scroll events of certain classes, let's make us a scrollview
        info.setClassName(ScrollView.class.getName());
    }

    /** @hide */
    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        if (!isEnabled()) {
            return false;
        }
        int direction = -1;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
                // fall through
            case android.R.id.accessibilityActionScrollDown:
                direction = 1;
                // fall through
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
                // fall through
            case android.R.id.accessibilityActionScrollUp:
                final int viewportHeight = getHeight() - mPaddingBottom - mTopPadding - mPaddingTop
                        - mBottomStackPeekSize - mBottomStackSlowDownHeight;
                final int targetScrollY = Math.max(0,
                        Math.min(mOwnScrollY + direction * viewportHeight, getScrollRange()));
                if (targetScrollY != mOwnScrollY) {
                    mScroller.startScroll(mScrollX, mOwnScrollY, 0, targetScrollY - mOwnScrollY);
                    postInvalidateOnAnimation();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public void onGroupsChanged() {
        mPhoneStatusBar.requestNotificationUpdate();
    }

    public void generateChildOrderChangedEvent() {
        if (mIsExpanded && mAnimationsEnabled) {
            mGenerateChildOrderChangedEvent = true;
            mNeedsAnimation = true;
            requestChildrenUpdate();
        }
    }

    public void runAfterAnimationFinished(Runnable runnable) {
        mAnimationFinishedRunnables.add(runnable);
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
        mAmbientState.setHeadsUpManager(headsUpManager);
    }

    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        if (mAnimationsEnabled) {
            mHeadsUpChangeAnimations.add(new Pair<>(row, isHeadsUp));
            mNeedsAnimation = true;
            if (!mIsExpanded && !isHeadsUp) {
                row.setHeadsupDisappearRunning(true);
            }
            requestChildrenUpdate();
        }
    }

    public void setShadeExpanded(boolean shadeExpanded) {
        mAmbientState.setShadeExpanded(shadeExpanded);
        mStateAnimator.setShadeExpanded(shadeExpanded);
    }

    /**
     * Set the boundary for the bottom heads up position. The heads up will always be above this
     * position.
     *
     * @param height the height of the screen
     * @param bottomBarHeight the height of the bar on the bottom
     */
    public void setHeadsUpBoundaries(int height, int bottomBarHeight) {
        mAmbientState.setMaxHeadsUpTranslation(height - bottomBarHeight);
        mStateAnimator.setHeadsUpAppearHeightBottom(height);
        requestChildrenUpdate();
    }

    public void setTrackingHeadsUp(boolean trackingHeadsUp) {
        mTrackingHeadsUp = trackingHeadsUp;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
        mScrimController.setScrimBehindChangeRunnable(new Runnable() {
            @Override
            public void run() {
                updateBackgroundDimming();
            }
        });
    }

    public void forceNoOverlappingRendering(boolean force) {
        mForceNoOverlappingRendering = force;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return !mForceNoOverlappingRendering && super.hasOverlappingRendering();
    }

    public void setAnimationRunning(boolean animationRunning) {
        if (animationRunning != mAnimationRunning) {
            if (animationRunning) {
                getViewTreeObserver().addOnPreDrawListener(mBackgroundUpdater);
            } else {
                getViewTreeObserver().removeOnPreDrawListener(mBackgroundUpdater);
            }
            mAnimationRunning = animationRunning;
            updateContinuousShadowDrawing();
        }
    }

    public boolean isExpanded() {
        return mIsExpanded;
    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        updateNotificationAnimationStates();
    }

    public void setFadingOut(boolean fadingOut) {
        if (fadingOut != mFadingOut) {
            mFadingOut = fadingOut;
            updateFadingState();
        }
    }

    public void setParentFadingOut(boolean fadingOut) {
        if (fadingOut != mParentFadingOut) {
            mParentFadingOut = fadingOut;
            updateFadingState();
        }
    }

    private void updateFadingState() {
        if (mFadingOut || mParentFadingOut || mAmbientState.isDark()) {
            mScrimController.setExcludedBackgroundArea(null);
        } else {
            applyCurrentBackgroundBounds();
        }
        updateSrcDrawing();
    }

    @Override
    public void setAlpha(@FloatRange(from = 0.0, to = 1.0) float alpha) {
        super.setAlpha(alpha);
        setFadingOut(alpha != 1.0f);
    }

    /**
     * Remove the a given view from the viewstate. This is currently used when the children are
     * kept in the parent artificially to have a nicer animation.
     * @param view the view to remove
     */
    public void removeViewStateForView(View view) {
        mCurrentStackScrollState.removeViewStateForView(view);
    }

    /**
     * A listener that is notified when some child locations might have changed.
     */
    public interface OnChildLocationsChangedListener {
        public void onChildLocationsChanged(NotificationStackScrollLayout stackScrollLayout);
    }

    /**
     * A listener that is notified when the empty space below the notifications is clicked on
     */
    public interface OnEmptySpaceClickListener {
        public void onEmptySpaceClicked(float x, float y);
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

    private class NotificationSwipeHelper extends SwipeHelper {
        private static final long SHOW_GEAR_DELAY = 60;
        private static final long COVER_GEAR_DELAY = 4000;
        private CheckForDrag mCheckForDrag;
        private Runnable mFalsingCheck;
        private Handler mHandler;
        private boolean mGearSnappedTo;
        private boolean mGearSnappedOnLeft;

        public NotificationSwipeHelper(int swipeDirection, Callback callback, Context context) {
            super(swipeDirection, callback, context);
            mHandler = new Handler();
            mFalsingCheck = new Runnable() {
                @Override
                public void run() {
                    resetExposedGearView(true /* animate */, true /* force */);
                }
            };
        }

        @Override
        public void onDownUpdate(View currView) {
            // Set the active view
            mTranslatingParentView = currView;

            // Reset check for drag gesture
            cancelCheckForDrag();
            if (mCurrIconRow != null) {
                mCurrIconRow.setSnapping(false);
            }
            mCheckForDrag = null;
            mCurrIconRow = null;
            mHandler.removeCallbacks(mFalsingCheck);

            // Slide back any notifications that might be showing a gear
            resetExposedGearView(true /* animate */, false /* force */);

            if (currView instanceof ExpandableNotificationRow) {
                // Set the listener for the current row's gear
                mCurrIconRow = ((ExpandableNotificationRow) currView).getSettingsRow();
                mCurrIconRow.setGearListener(NotificationStackScrollLayout.this);
            }
        }

        @Override
        public void onMoveUpdate(View view, float translation, float delta) {
            mHandler.removeCallbacks(mFalsingCheck);

            if (mCurrIconRow != null) {
                mCurrIconRow.setSnapping(false); // If we're moving, we're not snapping.

                // If the gear is visible and the movement is towards it it's not a location change.
                boolean onLeft = mGearSnappedTo ? mGearSnappedOnLeft : mCurrIconRow.isIconOnLeft();
                boolean locationChange = isTowardsGear(translation, onLeft)
                        ? false : mCurrIconRow.isIconLocationChange(translation);
                if (locationChange) {
                    // Don't consider it "snapped" if location has changed.
                    setSnappedToGear(false);

                    // Changed directions, make sure we check to fade in icon again.
                    if (!mHandler.hasCallbacks(mCheckForDrag)) {
                        // No check scheduled, set null to schedule a new one.
                        mCheckForDrag = null;
                    } else {
                        // Check scheduled, reset alpha and update location; check will fade it in
                        mCurrIconRow.setGearAlpha(0f);
                        mCurrIconRow.setIconLocation(translation > 0 /* onLeft */);
                    }
                }
            }

            final boolean gutsExposed = (view instanceof ExpandableNotificationRow)
                    && ((ExpandableNotificationRow) view).areGutsExposed();

            if (!isPinnedHeadsUp(view) && !gutsExposed) {
                // Only show the gear if we're not a heads up view and guts aren't exposed.
                checkForDrag();
            }
        }

        @Override
        public void dismissChild(final View view, float velocity,
                boolean useAccelerateInterpolator) {
            super.dismissChild(view, velocity, useAccelerateInterpolator);
            if (mIsExpanded) {
                // We don't want to quick-dismiss when it's a heads up as this might lead to closing
                // of the panel early.
                handleChildDismissed(view);
            }
            handleGearCoveredOrDismissed();
        }

        @Override
        public void snapChild(final View animView, final float targetLeft, float velocity) {
            super.snapChild(animView, targetLeft, velocity);
            onDragCancelled(animView);
            if (targetLeft == 0) {
                handleGearCoveredOrDismissed();
            }
        }

        private void handleGearCoveredOrDismissed() {
            cancelCheckForDrag();
            setSnappedToGear(false);
            if (mGearExposedView != null && mGearExposedView == mTranslatingParentView) {
                mGearExposedView = null;
            }
        }

        @Override
        public boolean handleUpEvent(MotionEvent ev, View animView, float velocity,
                float translation) {
            if (mCurrIconRow == null) {
                cancelCheckForDrag();
                return false; // Let SwipeHelper handle it.
            }

            boolean gestureTowardsGear = isTowardsGear(velocity, mCurrIconRow.isIconOnLeft());
            boolean gestureFastEnough = Math.abs(velocity) > getEscapeVelocity();

            if (mGearSnappedTo && mCurrIconRow.isVisible()) {
                if (mGearSnappedOnLeft == mCurrIconRow.isIconOnLeft()) {
                    boolean coveringGear =
                            Math.abs(getTranslation(animView)) <= getSpaceForGear(animView) * 0.6f;
                    if (gestureTowardsGear || coveringGear) {
                        // Gesture is towards or covering the gear
                        snapChild(animView, 0 /* leftTarget */, velocity);
                    } else if (isDismissGesture(ev)) {
                        // Gesture is a dismiss that's not towards the gear
                        dismissChild(animView, velocity,
                                !swipedFastEnough() /* useAccelerateInterpolator */);
                    } else {
                        // Didn't move enough to dismiss or cover, snap to the gear
                        snapToGear(animView, velocity);
                    }
                } else if ((!gestureFastEnough && swipedEnoughToShowGear(animView))
                        || (gestureTowardsGear && !swipedFarEnough())) {
                    // The gear has been snapped to previously, however, the gear is now on the
                    // other side. If gesture is towards gear and not too far snap to the gear.
                    snapToGear(animView, velocity);
                } else {
                    dismissOrSnapBack(animView, velocity, ev);
                }
            } else if ((!gestureFastEnough && swipedEnoughToShowGear(animView))
                    || gestureTowardsGear) {
                // Gear has not been snapped to previously and this is gear revealing gesture
                snapToGear(animView, velocity);
            } else {
                dismissOrSnapBack(animView, velocity, ev);
            }
            return true;
        }

        private void dismissOrSnapBack(View animView, float velocity, MotionEvent ev) {
            if (isDismissGesture(ev)) {
                dismissChild(animView, velocity,
                        !swipedFastEnough() /* useAccelerateInterpolator */);
            } else {
                snapChild(animView, 0 /* leftTarget */, velocity);
            }
        }

        private void snapToGear(View animView, float velocity) {
            final float snapBackThreshold = getSpaceForGear(animView);
            final float target = mCurrIconRow.isIconOnLeft() ? snapBackThreshold
                    : -snapBackThreshold;
            mGearExposedView = mTranslatingParentView;
            if (animView instanceof ExpandableNotificationRow) {
                MetricsLogger.action(mContext, MetricsEvent.ACTION_REVEAL_GEAR,
                        ((ExpandableNotificationRow) animView).getStatusBarNotification()
                                .getPackageName());
            }
            if (mCurrIconRow != null) {
                mCurrIconRow.setSnapping(true);
                setSnappedToGear(true);
            }
            onDragCancelled(animView);

            // If we're on the lockscreen we want to false this.
            if (mPhoneStatusBar.getBarState() == StatusBarState.KEYGUARD) {
                mHandler.removeCallbacks(mFalsingCheck);
                mHandler.postDelayed(mFalsingCheck, COVER_GEAR_DELAY);
            }
            super.snapChild(animView, target, velocity);
        }

        private boolean swipedEnoughToShowGear(View animView) {
            if (mTranslatingParentView == null) {
                return false;
            }
            // If the notification can't be dismissed then how far it can move is
            // restricted -- reduce the distance it needs to move in this case.
            final float multiplier = canChildBeDismissed(animView) ? 0.4f : 0.2f;
            final float snapBackThreshold = getSpaceForGear(animView) * multiplier;
            final float translation = getTranslation(animView);
            final boolean fromLeft = translation > 0;
            final float absTrans = Math.abs(translation);
            final float notiThreshold = getSize(mTranslatingParentView) * 0.4f;

            return mCurrIconRow.isVisible() && (mCurrIconRow.isIconOnLeft()
                    ? (translation > snapBackThreshold && translation <= notiThreshold)
                    : (translation < -snapBackThreshold && translation >= -notiThreshold));
        }

        @Override
        public Animator getViewTranslationAnimator(View v, float target,
                AnimatorUpdateListener listener) {
            if (v instanceof ExpandableNotificationRow) {
                return ((ExpandableNotificationRow) v).getTranslateViewAnimator(target, listener);
            } else {
                return super.getViewTranslationAnimator(v, target, listener);
            }
        }

        @Override
        public void setTranslation(View v, float translate) {
            ((ExpandableView) v).setTranslation(translate);
        }

        @Override
        public float getTranslation(View v) {
            return ((ExpandableView) v).getTranslation();
        }

        public void closeControlsIfOutsideTouch(MotionEvent ev) {
            NotificationGuts guts = mPhoneStatusBar.getExposedGuts();
            View view = null;
            int height = 0;
            if (guts != null) {
                // Checking guts
                view = guts;
                height = guts.getActualHeight();
            } else if (mCurrIconRow != null && mCurrIconRow.isVisible()
                    && mTranslatingParentView != null) {
                // Checking gear
                view = mTranslatingParentView;
                height = ((ExpandableView) mTranslatingParentView).getActualHeight();
            }
            if (view != null) {
                final int rx = (int) ev.getRawX();
                final int ry = (int) ev.getRawY();

                getLocationOnScreen(mTempInt2);
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                final int x = location[0] - mTempInt2[0];
                final int y = location[1] - mTempInt2[1];
                Rect rect = new Rect(x, y, x + view.getWidth(), y + height);
                if (!rect.contains((int) rx, (int) ry)) {
                    // Touch was outside visible guts / gear notification, close what's visible
                    mPhoneStatusBar.dismissPopups(-1, -1, true /* resetGear */, true /* animate */);
                }
            }
        }

        /**
         * Returns whether the gesture is towards the gear location or not.
         */
        private boolean isTowardsGear(float velocity, boolean onLeft) {
            if (mCurrIconRow == null) {
                return false;
            }
            return mCurrIconRow.isVisible()
                    && ((onLeft && velocity <= 0) || (!onLeft && velocity >= 0));
        }

        /**
         * Indicates the the gear has been snapped to.
         */
        private void setSnappedToGear(boolean snapped) {
            mGearSnappedOnLeft = (mCurrIconRow != null) ? mCurrIconRow.isIconOnLeft() : false;
            mGearSnappedTo = snapped && mCurrIconRow != null;
        }

        /**
         * Returns the horizontal space in pixels required to display the gear behind a
         * notification.
         */
        private float getSpaceForGear(View view) {
            if (view instanceof ExpandableNotificationRow) {
                return ((ExpandableNotificationRow) view).getSpaceForGear();
            }
            return 0;
        }

        private void checkForDrag() {
            if (mCheckForDrag == null || !mHandler.hasCallbacks(mCheckForDrag)) {
                mCheckForDrag = new CheckForDrag();
                mHandler.postDelayed(mCheckForDrag, SHOW_GEAR_DELAY);
            }
        }

        private void cancelCheckForDrag() {
            if (mCurrIconRow != null) {
                mCurrIconRow.cancelFadeAnimator();
            }
            mHandler.removeCallbacks(mCheckForDrag);
        }

        private final class CheckForDrag implements Runnable {
            @Override
            public void run() {
                if (mTranslatingParentView == null) {
                    return;
                }
                final float translation = getTranslation(mTranslatingParentView);
                final float absTransX = Math.abs(translation);
                final float bounceBackToGearWidth = getSpaceForGear(mTranslatingParentView);
                final float notiThreshold = getSize(mTranslatingParentView) * 0.4f;
                if ((mCurrIconRow != null && (!mCurrIconRow.isVisible()
                        || mCurrIconRow.isIconLocationChange(translation)))
                        && absTransX >= bounceBackToGearWidth * 0.4
                        && absTransX < notiThreshold) {
                    // Fade in the gear
                    mCurrIconRow.fadeInSettings(translation > 0 /* fromLeft */, translation,
                            notiThreshold);
                }
            }
        }

        public void resetExposedGearView(boolean animate, boolean force) {
            if (mGearExposedView == null
                    || (!force && mGearExposedView == mTranslatingParentView)) {
                // If no gear is showing or it's showing for this view we do nothing.
                return;
            }
            final View prevGearExposedView = mGearExposedView;
            if (animate) {
                Animator anim = getViewTranslationAnimator(prevGearExposedView,
                        0 /* leftTarget */, null /* updateListener */);
                if (anim != null) {
                    anim.start();
                }
            } else if (mGearExposedView instanceof ExpandableNotificationRow) {
                ((ExpandableNotificationRow) mGearExposedView).resetTranslation();
            }
            mGearExposedView = null;
            mGearSnappedTo = false;
        }
    }

    private void updateContinuousShadowDrawing() {
        boolean continuousShadowUpdate = mAnimationRunning
                || !mAmbientState.getDraggedViews().isEmpty();
        if (continuousShadowUpdate != mContinuousShadowUpdate) {
            if (continuousShadowUpdate) {
                getViewTreeObserver().addOnPreDrawListener(mShadowUpdater);
            } else {
                getViewTreeObserver().removeOnPreDrawListener(mShadowUpdater);
            }
            mContinuousShadowUpdate = continuousShadowUpdate;
        }
    }

    public void resetExposedGearView(boolean animate, boolean force) {
        mSwipeHelper.resetExposedGearView(animate, force);
    }

    public void closeControlsIfOutsideTouch(MotionEvent ev) {
        mSwipeHelper.closeControlsIfOutsideTouch(ev);
    }

    static class AnimationEvent {

        static AnimationFilter[] FILTERS = new AnimationFilter[] {

                // ANIMATION_TYPE_ADD
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_REMOVE
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_REMOVE_SWIPED_OUT
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_TOP_PADDING_CHANGED
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateDimmed()
                        .animateZ(),

                // ANIMATION_TYPE_START_DRAG
                new AnimationFilter()
                        .animateShadowAlpha(),

                // ANIMATION_TYPE_SNAP_BACK
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight(),

                // ANIMATION_TYPE_ACTIVATED_CHILD
                new AnimationFilter()
                        .animateZ(),

                // ANIMATION_TYPE_DIMMED
                new AnimationFilter()
                        .animateDimmed(),

                // ANIMATION_TYPE_CHANGE_POSITION
                new AnimationFilter()
                        .animateAlpha() // maybe the children change positions
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_DARK
                new AnimationFilter()
                        .animateDark()
                        .hasDelays(),

                // ANIMATION_TYPE_GO_TO_FULL_SHADE
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateDimmed()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_HIDE_SENSITIVE
                new AnimationFilter()
                        .animateHideSensitive(),

                // ANIMATION_TYPE_VIEW_RESIZE
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_GROUP_EXPANSION_CHANGED
                new AnimationFilter()
                        .animateAlpha()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_HEADS_UP_APPEAR
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_HEADS_UP_DISAPPEAR
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_HEADS_UP_OTHER
                new AnimationFilter()
                        .animateShadowAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_EVERYTHING
                new AnimationFilter()
                        .animateAlpha()
                        .animateShadowAlpha()
                        .animateDark()
                        .animateDimmed()
                        .animateHideSensitive()
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

                // ANIMATION_TYPE_GROUP_EXPANSION_CHANGED
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_HEADS_UP_APPEAR
                StackStateAnimator.ANIMATION_DURATION_HEADS_UP_APPEAR,

                // ANIMATION_TYPE_HEADS_UP_DISAPPEAR
                StackStateAnimator.ANIMATION_DURATION_HEADS_UP_DISAPPEAR,

                // ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                StackStateAnimator.ANIMATION_DURATION_HEADS_UP_DISAPPEAR,

                // ANIMATION_TYPE_HEADS_UP_OTHER
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_EVERYTHING
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
        static final int ANIMATION_TYPE_GROUP_EXPANSION_CHANGED = 13;
        static final int ANIMATION_TYPE_HEADS_UP_APPEAR = 14;
        static final int ANIMATION_TYPE_HEADS_UP_DISAPPEAR = 15;
        static final int ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK = 16;
        static final int ANIMATION_TYPE_HEADS_UP_OTHER = 17;
        static final int ANIMATION_TYPE_EVERYTHING = 18;

        static final int DARK_ANIMATION_ORIGIN_INDEX_ABOVE = -1;
        static final int DARK_ANIMATION_ORIGIN_INDEX_BELOW = -2;

        final long eventStartTime;
        final View changingView;
        final int animationType;
        final AnimationFilter filter;
        final long length;
        View viewAfterChangingView;
        int darkAnimationOriginIndex;
        boolean headsUpFromBottom;

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
