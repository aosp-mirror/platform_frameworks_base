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

import static com.android.systemui.statusbar.notification.ActivityLaunchAnimator
        .ExpandAnimationParameters;

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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.graphics.ColorUtils;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Property;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
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
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.MenuItem;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.FooterView;
import com.android.systemui.statusbar.NotificationBlockingHelperManager;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationGuts;
import com.android.systemui.statusbar.NotificationListContainer;
import com.android.systemui.statusbar.NotificationLogger;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationSnooze;
import com.android.systemui.statusbar.StackScrollerDecorView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.ScrollAdapter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A layout which handles a dynamic amount of notifications and presents them in a scrollable stack.
 */
public class NotificationStackScrollLayout extends ViewGroup
        implements SwipeHelper.Callback, ExpandHelper.Callback, ScrollAdapter,
        ExpandableView.OnHeightChangedListener, NotificationGroupManager.OnGroupChangeListener,
        NotificationMenuRowPlugin.OnMenuEventListener, VisibilityLocationProvider,
        NotificationListContainer {

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
    private final Path mBackgroundPath = new Path();
    private final boolean mShouldDrawNotificationBackground;

    private float mExpandedHeight;
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
    private int mActivePointerId = INVALID_POINTER;
    private boolean mTouchIsClick;
    private float mInitialTouchX;
    private float mInitialTouchY;

    private Paint mDebugPaint;
    private int mContentHeight;
    private int mIntrinsicContentHeight;
    private int mCollapsedSize;
    private int mPaddingBetweenElements;
    private int mIncreasedPaddingBetweenElements;
    private int mRegularTopPadding;
    private int mDarkTopPadding;
    // Current padding, will be either mRegularTopPadding or mDarkTopPadding
    private int mTopPadding;
    // Distance between AOD separator and shelf
    private int mDarkSeparatorPadding;
    private int mBottomMargin;
    private int mBottomInset = 0;

    /**
     * The algorithm which calculates the properties for our children
     */
    protected final StackScrollAlgorithm mStackScrollAlgorithm;

    /**
     * The current State this Layout is in
     */
    private StackScrollState mCurrentStackScrollState = new StackScrollState(this);
    private final AmbientState mAmbientState;
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
    private NotificationLogger.OnChildLocationsChangedListener mListener;
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
    protected boolean mScrollingEnabled;
    protected FooterView mFooterView;
    protected EmptyShadeView mEmptyShadeView;
    private boolean mDismissAllInProgress;
    private boolean mFadeNotificationsOnDismiss;

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
    private boolean mNeedViewResizeAnimation;
    private View mExpandedGroupView;
    private boolean mEverythingNeedsAnimation;

    /**
     * The maximum scrollPosition which we are allowed to reach when a notification was expanded.
     * This is needed to avoid scrolling too far after the notification was collapsed in the same
     * motion.
     */
    private int mMaxScrollAfterExpand;
    private ExpandableNotificationRow.LongPressListener mLongPressListener;

    private NotificationMenuRowPlugin mCurrMenuRow;
    private View mTranslatingParentView;
    private View mMenuExposedView;
    boolean mCheckForLeavebehind;

    /**
     * Should in this touch motion only be scrolling allowed? It's true when the scroller was
     * animating.
     */
    private boolean mOnlyScrollingInThisMotion;
    private boolean mDisallowDismissInThisMotion;
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
    private StatusBar mStatusBar;
    private int[] mTempInt2 = new int[2];
    private boolean mGenerateChildOrderChangedEvent;
    private HashSet<Runnable> mAnimationFinishedRunnables = new HashSet<>();
    private HashSet<View> mClearOverlayViewsWhenFinished = new HashSet<>();
    private HashSet<Pair<ExpandableNotificationRow, Boolean>> mHeadsUpChangeAnimations
            = new HashSet<>();
    private HeadsUpManagerPhone mHeadsUpManager;
    private NotificationRoundnessManager mRoundnessManager = new NotificationRoundnessManager();
    private boolean mTrackingHeadsUp;
    private ScrimController mScrimController;
    private boolean mForceNoOverlappingRendering;
    private final ArrayList<Pair<ExpandableNotificationRow, Boolean>> mTmpList = new ArrayList<>();
    private FalsingManager mFalsingManager;
    private boolean mAnimationRunning;
    private ViewTreeObserver.OnPreDrawListener mRunningAnimationUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            onPreDrawDuringAnimation();
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
    private boolean mParentNotFullyVisible;
    private boolean mGroupExpandedForMeasure;
    private boolean mScrollable;
    private View mForcedScroll;
    private float mDarkAmount = 0f;
    private static final Property<NotificationStackScrollLayout, Float> DARK_AMOUNT =
            new FloatProperty<NotificationStackScrollLayout>("darkAmount") {
                @Override
                public void setValue(NotificationStackScrollLayout object, float value) {
                    object.setDarkAmount(value);
                }

                @Override
                public Float get(NotificationStackScrollLayout object) {
                    return object.getDarkAmount();
                }
            };
    private boolean mUsingLightTheme;
    private boolean mQsExpanded;
    private boolean mForwardScrollable;
    private boolean mBackwardScrollable;
    private NotificationShelf mShelf;
    private int mMaxDisplayedNotifications = -1;
    private int mStatusBarHeight;
    private int mMinInteractionHeight;
    private boolean mNoAmbient;
    private final Rect mClipRect = new Rect();
    private boolean mIsClipped;
    private Rect mRequestedClipBounds;
    private boolean mInHeadsUpPinnedMode;
    private boolean mHeadsUpAnimatingAway;
    private int mStatusBarState;
    private int mCachedBackgroundColor;
    private boolean mHeadsUpGoingAwayAnimationsAllowed = true;
    private Runnable mAnimateScroll = this::animateScroll;
    private int mCornerRadius;
    private int mSidePaddings;
    private final int mSeparatorWidth;
    private final int mSeparatorThickness;
    private final Rect mBackgroundAnimationRect = new Rect();
    private int mClockBottom;
    private int mAntiBurnInOffsetX;
    private ArrayList<BiConsumer<Float, Float>> mExpandedHeightListeners = new ArrayList<>();
    private int mHeadsUpInset;

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
        Resources res = getResources();

        mAmbientState = new AmbientState(context);
        mBgColor = context.getColor(R.color.notification_shade_background_color);
        int minHeight = res.getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = res.getDimensionPixelSize(R.dimen.notification_max_height);
        mExpandHelper = new ExpandHelper(getContext(), this,
                minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setScrollAdapter(this);
        mSwipeHelper = new NotificationSwipeHelper(SwipeHelper.X, this, getContext());
        mStackScrollAlgorithm = createStackScrollAlgorithm(context);
        initView(context);
        mFalsingManager = FalsingManager.getInstance(context);
        mShouldDrawNotificationBackground =
                res.getBoolean(R.bool.config_drawNotificationBackground);
        mFadeNotificationsOnDismiss =
                res.getBoolean(R.bool.config_fadeNotificationsOnDismiss);
        mSeparatorWidth = res.getDimensionPixelSize(R.dimen.widget_separator_width);
        mSeparatorThickness = res.getDimensionPixelSize(R.dimen.widget_separator_thickness);
        mDarkSeparatorPadding = res.getDimensionPixelSize(R.dimen.widget_bottom_separator_padding);
        mRoundnessManager.setAnimatedChildren(mChildrenToAddAnimated);
        mRoundnessManager.setOnRoundingChangedCallback(this::invalidate);
        addOnExpandedHeightListener(mRoundnessManager::setExpanded);

        // Blocking helper manager wants to know the expanded state, update as well.
        NotificationBlockingHelperManager blockingHelperManager =
                Dependency.get(NotificationBlockingHelperManager.class);
        addOnExpandedHeightListener((height, unused) -> {
            blockingHelperManager.setNotificationShadeExpanded(height);
        });

        updateWillNotDraw();
        mBackgroundPaint.setAntiAlias(true);
        if (DEBUG) {
            mDebugPaint = new Paint();
            mDebugPaint.setColor(0xffff0000);
            mDebugPaint.setStrokeWidth(2);
            mDebugPaint.setStyle(Paint.Style.STROKE);
        }
    }

    @Override
    public NotificationSwipeActionHelper getSwipeActionHelper() {
        return mSwipeHelper;
    }

    @Override
    public void onMenuClicked(View view, int x, int y, MenuItem item) {
        if (mLongPressListener == null) {
            return;
        }
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            MetricsLogger.action(mContext, MetricsEvent.ACTION_TOUCH_GEAR,
                    row.getStatusBarNotification().getPackageName());
        }
        mLongPressListener.onLongPress(view, x, y, item);
    }

    @Override
    public void onMenuReset(View row) {
        if (mTranslatingParentView != null && row == mTranslatingParentView) {
            mMenuExposedView = null;
            mTranslatingParentView = null;
        }
    }

    @Override
    public void onMenuShown(View row) {
        mMenuExposedView = mTranslatingParentView;
        if (row instanceof ExpandableNotificationRow) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_REVEAL_GEAR,
                    ((ExpandableNotificationRow) row).getStatusBarNotification()
                            .getPackageName());
        }
        mSwipeHelper.onMenuShown(row);
    }

    protected void onDraw(Canvas canvas) {
        if (mShouldDrawNotificationBackground
                && (mCurrentBounds.top < mCurrentBounds.bottom || mAmbientState.isDark())) {
            drawBackground(canvas);
        }

        if (DEBUG) {
            int y = mTopPadding;
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = getLayoutHeight();
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
            y = getHeight() - getEmptyBottomMargin();
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
        }
    }

    private void drawBackground(Canvas canvas) {
        final int lockScreenLeft = mSidePaddings;
        final int lockScreenRight = getWidth() - mSidePaddings;
        final int lockScreenTop = mCurrentBounds.top;
        final int lockScreenBottom = mCurrentBounds.bottom;
        final int darkLeft = getWidth() / 2 - mSeparatorWidth / 2;
        final int darkRight = darkLeft + mSeparatorWidth;
        final int darkTop = (int) (mRegularTopPadding + mSeparatorThickness / 2f);
        final int darkBottom = darkTop + mSeparatorThickness;

        if (mAmbientState.hasPulsingNotifications()) {
            // No divider, we have a notification icon instead
        } else if (mAmbientState.isFullyDark()) {
            // Only draw divider on AOD if we actually have notifications
            if (mFirstVisibleBackgroundChild != null) {
                canvas.drawRect(darkLeft, darkTop, darkRight, darkBottom, mBackgroundPaint);
            }
        } else {
            float inverseDark = 1 - mDarkAmount;
            float yProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(inverseDark);
            float xProgress = Interpolators.FAST_OUT_SLOW_IN
                    .getInterpolation(inverseDark * 2f);

            mBackgroundAnimationRect.set(
                    (int) MathUtils.lerp(darkLeft, lockScreenLeft, xProgress),
                    (int) MathUtils.lerp(darkTop, lockScreenTop, yProgress),
                    (int) MathUtils.lerp(darkRight, lockScreenRight, xProgress),
                    (int) MathUtils.lerp(darkBottom, lockScreenBottom, yProgress));
            if (!mAmbientState.isDark() || mFirstVisibleBackgroundChild != null) {
                canvas.drawRoundRect(mBackgroundAnimationRect.left, mBackgroundAnimationRect.top,
                        mBackgroundAnimationRect.right, mBackgroundAnimationRect.bottom,
                        mCornerRadius, mCornerRadius, mBackgroundPaint);
            }
        }
        updateClipping();
    }

    private void updateBackgroundDimming() {
        // No need to update the background color if it's not being drawn.
        if (!mShouldDrawNotificationBackground) {
            return;
        }

        float alpha =
                BACKGROUND_ALPHA_DIMMED + (1 - BACKGROUND_ALPHA_DIMMED) * (1.0f - mDimAmount);
        alpha *= 1f - mDarkAmount;
        // We need to manually blend in the background color.
        int scrimColor = mScrimController.getBackgroundColor();
        int awakeColor = ColorUtils.blendARGB(scrimColor, mBgColor, alpha);

        // Interpolate between semi-transparent notification panel background color
        // and white AOD separator.
        float colorInterpolation = Interpolators.DECELERATE_QUINT.getInterpolation(mDarkAmount);
        int color = ColorUtils.blendARGB(awakeColor, Color.WHITE, colorInterpolation);

        if (mCachedBackgroundColor != color) {
            mCachedBackgroundColor = color;
            mBackgroundPaint.setColor(color);
            invalidate();
        }
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

        Resources res = context.getResources();
        mCollapsedSize = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mStackScrollAlgorithm.initView(context);
        mAmbientState.reload(context);
        mPaddingBetweenElements = Math.max(1,
                res.getDimensionPixelSize(R.dimen.notification_divider_height));
        mIncreasedPaddingBetweenElements =
                res.getDimensionPixelSize(R.dimen.notification_divider_height_increased);
        mMinTopOverScrollToEscape = res.getDimensionPixelSize(
                R.dimen.min_top_overscroll_to_qs);
        mStatusBarHeight = res.getDimensionPixelSize(R.dimen.status_bar_height);
        mBottomMargin = res.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom);
        mSidePaddings = res.getDimensionPixelSize(R.dimen.notification_side_paddings);
        mMinInteractionHeight = res.getDimensionPixelSize(
                R.dimen.notification_min_interaction_height);
        mCornerRadius = res.getDimensionPixelSize(
                Utils.getThemeAttr(mContext, android.R.attr.dialogCornerRadius));
        mHeadsUpInset = mStatusBarHeight + res.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
    }

    public void setDrawBackgroundAsSrc(boolean asSrc) {
        mDrawBackgroundAsSrc = asSrc;
        updateSrcDrawing();
    }

    private void updateSrcDrawing() {
        if (!mShouldDrawNotificationBackground) {
            return;
        }

        mBackgroundPaint.setXfermode(mDrawBackgroundAsSrc && !mFadingOut && !mParentNotFullyVisible
                ? mSrcMode : null);
        invalidate();
    }

    private void notifyHeightChangeListener(ExpandableView view) {
        notifyHeightChangeListener(view, false /* needsAnimation */);
    }

    private void notifyHeightChangeListener(ExpandableView view, boolean needsAnimation) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(view, needsAnimation);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int childWidthSpec = MeasureSpec.makeMeasureSpec(width - mSidePaddings * 2,
                MeasureSpec.getMode(widthMeasureSpec));
        // We need to measure all children even the GONE ones, such that the heights are calculated
        // correctly as they are used to calculate how many we can fit on the screen.
        final int size = getChildCount();
        for (int i = 0; i < size; i++) {
            measureChild(getChildAt(i), childWidthSpec, heightMeasureSpec);
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
        requestChildrenUpdate();
        updateFirstAndLastBackgroundViews();
        updateAlgorithmLayoutMinHeight();
    }

    private void requestAnimationOnViewResize(ExpandableNotificationRow row) {
        if (mAnimationsEnabled && (mIsExpanded || row != null && row.isPinned())) {
            mNeedViewResizeAnimation = true;
            mNeedsAnimation = true;
        }
    }

    public void updateSpeedBumpIndex(int newIndex, boolean noAmbient) {
        mAmbientState.setSpeedBumpIndex(newIndex);
        mNoAmbient = noAmbient;
    }

    @Override
    public void setChildLocationsChangedListener(
            NotificationLogger.OnChildLocationsChangedListener listener) {
        mListener = listener;
    }

    @Override
    public boolean isInVisibleLocation(ExpandableNotificationRow row) {
        ExpandableViewState childViewState = mCurrentStackScrollState.getViewStateForView(row);
        if (childViewState == null) {
            return false;
        }
        if ((childViewState.location & ExpandableViewState.VISIBLE_LOCATIONS) == 0) {
            return false;
        }
        if (row.getVisibility() != View.VISIBLE) {
            return false;
        }
        return true;
    }

    private void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
        mShelf.setMaxLayoutHeight(maxLayoutHeight);
        updateAlgorithmHeightAndPadding();
    }

    private void updateAlgorithmHeightAndPadding() {
        if (mPulsing) {
            mTopPadding = mClockBottom;
        } else {
            mTopPadding = (int) MathUtils.lerp(mRegularTopPadding, mDarkTopPadding, mDarkAmount);
        }
        mAmbientState.setLayoutHeight(getLayoutHeight());
        updateAlgorithmLayoutMinHeight();
        mAmbientState.setTopPadding(mTopPadding);
    }

    private void updateAlgorithmLayoutMinHeight() {
        mAmbientState.setLayoutMinHeight(mQsExpanded && !onKeyguard() || isHeadsUpTransition()
                ? getLayoutMinHeight() : 0);
    }

    /**
     * Updates the children views according to the stack scroll algorithm. Call this whenever
     * modifications to {@link #mOwnScrollY} are performed to reflect it in the view layout.
     */
    private void updateChildren() {
        updateScrollStateForAddedChildren();
        mAmbientState.setCurrentScrollVelocity(mScroller.isFinished()
                ? 0
                : mScroller.getCurrVelocity());
        mAmbientState.setScrollY(mOwnScrollY);
        mStackScrollAlgorithm.getStackScrollState(mAmbientState, mCurrentStackScrollState);
        if (!isCurrentlyAnimating() && !mNeedsAnimation) {
            applyCurrentState();
        } else {
            startAnimationToState();
        }
    }

    private void onPreDrawDuringAnimation() {
        mShelf.updateAppearance();
        updateClippingToTopRoundedCorner();
        if (!mNeedsAnimation && !mChildrenUpdateRequested) {
            updateBackground();
        }
    }

    private void updateClippingToTopRoundedCorner() {
        Float clipStart = (float) mTopPadding + mAmbientState.getExpandAnimationTopChange();
        Float clipEnd = clipStart + mCornerRadius;
        boolean first = true;
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            float start = child.getTranslationY();
            float end = start + Math.max(child.getActualHeight() - child.getClipBottomAmount(),
                    0);
            boolean clip = clipStart > start && clipStart < end
                    || clipEnd >= start && clipEnd <= end;
            clip &= !(first && mOwnScrollY == 0);
            child.setDistanceToTopRoundness(clip ? Math.max(start - clipStart, 0) : -1);
            first = false;
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
                float increasedPaddingAmount = child.getIncreasedPaddingAmount();
                int padding = increasedPaddingAmount == 1.0f ? mIncreasedPaddingBetweenElements
                        : increasedPaddingAmount == -1.0f ? 0 : mPaddingBetweenElements;
                int childHeight = getIntrinsicHeight(child) + padding;
                if (startingPosition < mOwnScrollY) {
                    // This child starts off screen, so let's keep it offscreen to keep the others visible

                    setOwnScrollY(mOwnScrollY + childHeight);
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
                setOwnScrollY(targetScroll);
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
            setOwnScrollY(scrollRange);
        }
    }

    public int getTopPadding() {
        return mTopPadding;
    }

    private void setTopPadding(int topPadding, boolean animate) {
        if (mRegularTopPadding != topPadding) {
            mRegularTopPadding = topPadding;
            mDarkTopPadding = topPadding + mDarkSeparatorPadding;
            mAmbientState.setDarkTopPadding(mDarkTopPadding);
            updateAlgorithmHeightAndPadding();
            updateContentHeight();
            if (animate && mAnimationsEnabled && mIsExpanded) {
                mTopPaddingNeedsAnimation = true;
                mNeedsAnimation =  true;
            }
            requestChildrenUpdate();
            notifyHeightChangeListener(null, animate);
        }
    }

    /**
     * Update the height of the panel.
     *
     * @param height the expanded height of the panel
     */
    public void setExpandedHeight(float height) {
        mExpandedHeight = height;
        setIsExpanded(height > 0);
        int minExpansionHeight = getMinExpansionHeight();
        if (height < minExpansionHeight) {
            mClipRect.left = 0;
            mClipRect.right = getWidth();
            mClipRect.top = 0;
            mClipRect.bottom = (int) height;
            height = minExpansionHeight;
            setRequestedClipBounds(mClipRect);
        } else {
            setRequestedClipBounds(null);
        }
        int stackHeight;
        float translationY;
        float appearEndPosition = getAppearEndPosition();
        float appearStartPosition = getAppearStartPosition();
        float appearFraction = 1.0f;
        if (height >= appearEndPosition) {
            translationY = 0;
            stackHeight = (int) height;
        } else {
            appearFraction = getAppearFraction(height);
            if (appearFraction >= 0) {
                translationY = NotificationUtils.interpolate(getExpandTranslationStart(), 0,
                        appearFraction);
            } else {
                // This may happen when pushing up a heads up. We linearly push it up from the
                // start
                translationY = height - appearStartPosition + getExpandTranslationStart();
            }
            if (isHeadsUpTransition()) {
                stackHeight = mFirstVisibleBackgroundChild.getPinnedHeadsUpHeight();
                translationY = MathUtils.lerp(mHeadsUpInset - mTopPadding, 0, appearFraction);
            } else {
                stackHeight = (int) (height - translationY);
            }
        }
        if (stackHeight != mCurrentStackHeight) {
            mCurrentStackHeight = stackHeight;
            updateAlgorithmHeightAndPadding();
            requestChildrenUpdate();
        }
        setStackTranslation(translationY);
        for (int i = 0; i < mExpandedHeightListeners.size(); i++) {
            BiConsumer<Float, Float> listener = mExpandedHeightListeners.get(i);
            listener.accept(mExpandedHeight, appearFraction);
        }
    }

    private void setRequestedClipBounds(Rect clipRect) {
        mRequestedClipBounds = clipRect;
        updateClipping();
    }

    /**
     * Return the height of the content ignoring the footer.
     */
    public int getIntrinsicContentHeight() {
        return mIntrinsicContentHeight;
    }

    public void updateClipping() {
        boolean animatingClipping = mDarkAmount > 0 && mDarkAmount < 1;
        boolean clipped = mRequestedClipBounds != null && !mInHeadsUpPinnedMode
                && !mHeadsUpAnimatingAway;
        if (mIsClipped != clipped) {
            mIsClipped = clipped;
            updateFadingState();
        }

        if (animatingClipping) {
            setClipBounds(mBackgroundAnimationRect);
        } else if (clipped) {
            setClipBounds(mRequestedClipBounds);
        } else {
            setClipBounds(null);
        }
    }

    /**
     * @return The translation at the beginning when expanding.
     *         Measured relative to the resting position.
     */
    private float getExpandTranslationStart() {
        return - mTopPadding;
    }

    /**
     * @return the position from where the appear transition starts when expanding.
     *         Measured in absolute height.
     */
    private float getAppearStartPosition() {
        if (isHeadsUpTransition()) {
            return mHeadsUpInset + mFirstVisibleBackgroundChild.getPinnedHeadsUpHeight();
        }
        return getMinExpansionHeight();
    }

    /**
     * @return the height of the top heads up notification when pinned. This is different from the
     *         intrinsic height, which also includes whether the notification is system expanded and
     *         is mainly used when dragging down from a heads up notification.
     */
    private int getTopHeadsUpPinnedHeight() {
        NotificationData.Entry topEntry = mHeadsUpManager.getTopEntry();
        if (topEntry == null) {
            return 0;
        }
        ExpandableNotificationRow row = topEntry.row;
        if (row.isChildInGroup()) {
            final ExpandableNotificationRow groupSummary
                    = mGroupManager.getGroupSummary(row.getStatusBarNotification());
            if (groupSummary != null) {
                row = groupSummary;
            }
        }
        return row.getPinnedHeadsUpHeight();
    }

    /**
     * @return the position from where the appear transition ends when expanding.
     *         Measured in absolute height.
     */
    private float getAppearEndPosition() {
        int appearPosition;
        int notGoneChildCount = getNotGoneChildCount();
        if (mEmptyShadeView.getVisibility() == GONE && notGoneChildCount != 0) {
            if (isHeadsUpTransition()
                    || (mHeadsUpManager.hasPinnedHeadsUp() && !mAmbientState.isDark())) {
                appearPosition = getTopHeadsUpPinnedHeight();
            } else {
                appearPosition = 0;
                if (notGoneChildCount >= 1 && mShelf.getVisibility() != GONE) {
                    appearPosition += mShelf.getIntrinsicHeight();
                }
            }
        } else {
            appearPosition = mEmptyShadeView.getHeight();
        }
        return appearPosition + (onKeyguard() ? mTopPadding : mIntrinsicPadding);
    }

    private boolean isHeadsUpTransition() {
        return mTrackingHeadsUp && mFirstVisibleBackgroundChild != null
                && mAmbientState.isAboveShelf(mFirstVisibleBackgroundChild);
    }

    /**
     * @param height the height of the panel
     * @return the fraction of the appear animation that has been performed
     */
    public float getAppearFraction(float height) {
        float appearEndPosition = getAppearEndPosition();
        float appearStartPosition = getAppearStartPosition();
        return (height - appearStartPosition)
                / (appearEndPosition - appearStartPosition);
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

    public void setLongPressListener(ExpandableNotificationRow.LongPressListener listener) {
        mLongPressListener = listener;
    }

    public void setQsContainer(ViewGroup qsContainer) {
        mQsContainer = qsContainer;
    }

    /**
     * Handles cleanup after the given {@code view} has been fully swiped out (including
     * re-invoking dismiss logic in case the notification has not made its way out yet).
     */
    @Override
    public void onChildDismissed(View view) {
        ExpandableNotificationRow row = (ExpandableNotificationRow) view;
        if (!row.isDismissed()) {
            handleChildViewDismissed(view);
        }
        ViewGroup transientContainer = row.getTransientContainer();
        if (transientContainer != null) {
            transientContainer.removeTransientView(view);
        }
    }

    /**
     * Starts up notification dismiss and tells the notification, if any, to remove itself from
     * layout.
     *
     * @param view view (e.g. notification) to dismiss from the layout
     */
    private void handleChildViewDismissed(View view) {
        if (mDismissAllInProgress) {
            return;
        }

        boolean isBlockingHelperShown = false;

        setSwipingInProgress(false);
        if (mDragAnimPendingChildren.contains(view)) {
            // We start the swipe and finish it in the same frame; we don't want a drag animation.
            mDragAnimPendingChildren.remove(view);
        }
        mAmbientState.onDragFinished(view);
        updateContinuousShadowDrawing();

        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (row.isHeadsUp()) {
                mHeadsUpManager.addSwipedOutNotification(row.getStatusBarNotification().getKey());
            }
            isBlockingHelperShown =
                    row.performDismissWithBlockingHelper(false /* fromAccessibility */);
        }

        if (!isBlockingHelperShown) {
            mSwipedOutViews.add(view);
        }
        mFalsingManager.onNotificationDismissed();
        if (mFalsingManager.shouldEnforceBouncer()) {
            mStatusBar.executeRunnableDismissingKeyguard(
                    null,
                    null /* cancelAction */,
                    false /* dismissShade */,
                    true /* afterKeyguardGone */,
                    false /* deferred */);
        }
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
        if (mCurrMenuRow != null && targetLeft == 0) {
            mCurrMenuRow.resetMenu();
            mCurrMenuRow = null;
        }
    }

    @Override
    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        // Returning true prevents alpha fading.
        return !mFadeNotificationsOnDismiss;
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
        return mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        View child = getChildAtPosition(ev.getX(), ev.getY());
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            ExpandableNotificationRow parent = row.getNotificationParent();
            if (parent != null && parent.areChildrenExpanded()
                    && (parent.areGutsExposed()
                            || mMenuExposedView == parent
                        || (parent.getNotificationChildren().size() == 1
                                && parent.isClearable()))) {
                // In this case the group is expanded and showing the menu for the
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
            float bottom = childTop + slidingChild.getActualHeight()
                    - slidingChild.getClipBottomAmount();

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
        return getChildAtPosition(touchX, touchY, true /* requireMinHeight */);

    }

    /**
     * Get the child at a certain screen location.
     *
     * @param touchX the x coordinate
     * @param touchY the y coordinate
     * @param requireMinHeight Whether a minimum height is required for a child to be returned.
     * @return the child at the given location.
     */
    private ExpandableView getChildAtPosition(float touchX, float touchY,
            boolean requireMinHeight) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView slidingChild = (ExpandableView) getChildAt(childIdx);
            if (slidingChild.getVisibility() != VISIBLE
                    || slidingChild instanceof StackScrollerDecorView) {
                continue;
            }
            float childTop = slidingChild.getTranslationY();
            float top = childTop + slidingChild.getClipTopAmount();
            float bottom = childTop + slidingChild.getActualHeight()
                    - slidingChild.getClipBottomAmount();

            // Allow the full width of this view to prevent gesture conflict on Keyguard (phone and
            // camera affordance).
            int left = 0;
            int right = getWidth();

            if ((bottom - top >= mMinInteractionHeight || !requireMinHeight)
                    && touchY >= top && touchY <= bottom && touchX >= left && touchX <= right) {
                if (slidingChild instanceof ExpandableNotificationRow) {
                    ExpandableNotificationRow row = (ExpandableNotificationRow) slidingChild;
                    if (!mIsExpanded && row.isHeadsUp() && row.isPinned()
                            && mHeadsUpManager.getTopEntry().row != row
                            && mGroupManager.getGroupSummary(
                                mHeadsUpManager.getTopEntry().row.getStatusBarNotification())
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
            if (userExpanded && onKeyguard()) {
                // Due to a race when locking the screen while touching, a notification may be
                // expanded even after we went back to keyguard. An example of this happens if
                // you click in the empty space while expanding a group.

                // We also need to un-user lock it here, since otherwise the content height
                // calculated might be wrong. We also can't invert the two calls since
                // un-userlocking it will trigger a layout switch in the content view.
                row.setUserLocked(false);
                updateContentHeight();
                notifyHeightChangeListener(row);
                return;
            }
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
        cancelLongPress();
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
        return view.getMaxContentHeight();
    }

    public void setScrollingEnabled(boolean enable) {
        mScrollingEnabled = enable;
    }

    public void lockScrollTo(View v) {
        if (mForcedScroll == v) {
            return;
        }
        mForcedScroll = v;
        scrollTo(v);
    }

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
            animateScroll();
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
            animateScroll();
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
        return onKeyguard();
    }

    private boolean onKeyguard() {
        return mStatusBarState == StatusBarState.KEYGUARD;
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
        mStatusBarHeight = getResources().getDimensionPixelOffset(R.dimen.status_bar_height);
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

    @Override
    public void snapViewIfNeeded(ExpandableNotificationRow child) {
        boolean animate = mIsExpanded || isPinnedHeadsUp(child);
        // If the child is showing the notification menu snap to that
        float targetLeft = child.getProvider().isMenuVisible() ? child.getTranslation() : 0;
        mSwipeHelper.snapChildIfNeeded(child, animate, targetLeft);
    }

    @Override
    public ViewGroup getViewParentForNotification(NotificationData.Entry entry) {
        return this;
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

        // Check if we need to clear any snooze leavebehinds
        NotificationGuts guts = mStatusBar.getGutsManager().getExposedGuts();
        if (guts != null && !isTouchInView(ev, guts)
                && guts.getGutsContent() instanceof NotificationSnooze) {
            NotificationSnooze ns = (NotificationSnooze) guts.getGutsContent();
            if ((ns.isExpanded() && isCancelOrUp)
                    || (!horizontalSwipeWantsIt && scrollerWantsIt)) {
                // If the leavebehind is expanded we clear it on the next up event, otherwise we
                // clear it on the next non-horizontal swipe or expand event.
                checkSnoozeLeavebehind();
            }
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            mCheckForLeavebehind = true;
        }
        return horizontalSwipeWantsIt || scrollerWantsIt || expandWantsIt || super.onTouchEvent(ev);
    }

    private void dispatchDownEventToScroller(MotionEvent ev) {
        MotionEvent downEvent = MotionEvent.obtain(ev);
        downEvent.setAction(MotionEvent.ACTION_DOWN);
        onScrollTouch(downEvent);
        downEvent.recycle();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!isScrollingEnabled() || !mIsExpanded || mSwipingInProgress || mExpandingNotification
                || mDisallowScrollingInThisMotion) {
            return false;
        }
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_SCROLL: {
                    if (!mIsBeingDragged) {
                        final float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        if (vscroll != 0) {
                            final int delta = (int) (vscroll * getVerticalScrollFactor());
                            final int range = getScrollRange();
                            int oldScrollY = mOwnScrollY;
                            int newScrollY = oldScrollY - delta;
                            if (newScrollY < 0) {
                                newScrollY = 0;
                            } else if (newScrollY > range) {
                                newScrollY = range;
                            }
                            if (newScrollY != oldScrollY) {
                                setOwnScrollY(newScrollY);
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean onScrollTouch(MotionEvent ev) {
        if (!isScrollingEnabled()) {
            return false;
        }
        if (isInsideQsContainer(ev) && !mIsBeingDragged) {
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

                    // Calling customOverScrollBy will call onCustomOverScrolled, which
                    // sets the scrolling if applicable.
                    if (scrollAmount != 0.0f) {
                        // The scrolling motion could not be compensated with the
                        // existing overScroll, we have to scroll the view
                        customOverScrollBy((int) scrollAmount, mOwnScrollY,
                                range, getHeight() / 2);
                        // If we're scrolling, leavebehinds should be dismissed
                        checkSnoozeLeavebehind();
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
                                    animateScroll();
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
                        animateScroll();
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

    protected boolean isInsideQsContainer(MotionEvent ev) {
        return ev.getY() < mQsContainer.getBottom();
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
            setOwnScrollY(range);
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
            setOwnScrollY(0);
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

    private void animateScroll() {
        if (mScroller.computeScrollOffset()) {
            int oldY = mOwnScrollY;
            int y = mScroller.getCurrY();

            if (oldY != y) {
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
                customOverScrollBy(y - oldY, oldY, range,
                        (int) (mMaxOverScroll));
            }

            postOnAnimation(mAnimateScroll);
        } else {
            mDontClampNextScroll = false;
            if (mFinishScrollingCallback != null) {
                mFinishScrollingCallback.run();
            }
        }
    }

    private boolean customOverScrollBy(int deltaY, int scrollY, int scrollRangeY,
            int maxOverScrollY) {

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

        onCustomOverScrolled(newScrollY, clampedY);

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

    private void onCustomOverScrolled(int scrollY, boolean clampedY) {
        // Treat animating scrolls differently; see #computeScroll() for why.
        if (!mScroller.isFinished()) {
            setOwnScrollY(scrollY);
            if (clampedY) {
                springBack();
            } else {
                float overScrollTop = getCurrentOverScrollAmount(true);
                if (mOwnScrollY < 0) {
                    notifyOverscrollTopListener(-mOwnScrollY, isRubberbanded(true));
                } else {
                    notifyOverscrollTopListener(overScrollTop, isRubberbanded(true));
                }
            }
        } else {
            setOwnScrollY(scrollY);
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
                setOwnScrollY(0);
                mDontReportNextOverScroll = true;
            } else {
                onTop = false;
                newAmount = mOwnScrollY - scrollRange;
                setOwnScrollY(scrollRange);
            }
            setOverScrollAmount(newAmount, onTop, false);
            setOverScrollAmount(0.0f, onTop, true);
            mScroller.forceFinished(true);
        }
    }

    private int getScrollRange() {
        int contentHeight = getContentHeight();
        int scrollRange = Math.max(0, contentHeight - mMaxLayoutHeight);
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
            if (child.getVisibility() != View.GONE && child != mShelf) {
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
    private View getFirstChildBelowTranlsationY(float translationY, boolean ignoreChildren) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            float rowTranslation = child.getTranslationY();
            if (rowTranslation >= translationY) {
                return child;
            } else if (!ignoreChildren && child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (row.isSummaryWithChildren() && row.areChildrenExpanded()) {
                    List<ExpandableNotificationRow> notificationChildren =
                            row.getNotificationChildren();
                    for (int childIndex = 0; childIndex < notificationChildren.size();
                            childIndex++) {
                        ExpandableNotificationRow rowChild = notificationChildren.get(childIndex);
                        if (rowChild.getTranslationY() + rowTranslation >= translationY) {
                            return rowChild;
                        }
                    }
                }
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
            if (child.getVisibility() != View.GONE && child != mShelf) {
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
            if (child.getVisibility() != View.GONE && !child.willBeGone() && child != mShelf) {
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
        float previousPaddingRequest = mPaddingBetweenElements;
        float previousPaddingAmount = 0.0f;
        int numShownItems = 0;
        boolean finish = false;
        int maxDisplayedNotifications = mAmbientState.isFullyDark()
                ? (hasPulsingNotifications() ? 1 : 0)
                : mMaxDisplayedNotifications;

        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView expandableView = (ExpandableView) getChildAt(i);
            boolean footerViewOnLockScreen = expandableView == mFooterView && onKeyguard();
            if (expandableView.getVisibility() != View.GONE
                    && !expandableView.hasNoContentHeight() && !footerViewOnLockScreen) {
                boolean limitReached = maxDisplayedNotifications != -1
                        && numShownItems >= maxDisplayedNotifications;
                boolean notificationOnAmbientThatIsNotPulsing = mAmbientState.isFullyDark()
                        && hasPulsingNotifications()
                        && expandableView instanceof ExpandableNotificationRow
                        && !isPulsing(((ExpandableNotificationRow) expandableView).getEntry());
                if (limitReached || notificationOnAmbientThatIsNotPulsing) {
                    expandableView = mShelf;
                    finish = true;
                }
                float increasedPaddingAmount = expandableView.getIncreasedPaddingAmount();
                float padding;
                if (increasedPaddingAmount >= 0.0f) {
                    padding = (int) NotificationUtils.interpolate(
                            previousPaddingRequest,
                            mIncreasedPaddingBetweenElements,
                            increasedPaddingAmount);
                    previousPaddingRequest = (int) NotificationUtils.interpolate(
                            mPaddingBetweenElements,
                            mIncreasedPaddingBetweenElements,
                            increasedPaddingAmount);
                } else {
                    int ownPadding = (int) NotificationUtils.interpolate(
                            0,
                            mPaddingBetweenElements,
                            1.0f + increasedPaddingAmount);
                    if (previousPaddingAmount > 0.0f) {
                        padding = (int) NotificationUtils.interpolate(
                                ownPadding,
                                mIncreasedPaddingBetweenElements,
                                previousPaddingAmount);
                    } else {
                        padding = ownPadding;
                    }
                    previousPaddingRequest = ownPadding;
                }
                if (height != 0) {
                    height += padding;
                }
                previousPaddingAmount = increasedPaddingAmount;
                height += expandableView.getIntrinsicHeight();
                numShownItems++;
                if (finish) {
                    break;
                }
            }
        }
        mIntrinsicContentHeight = height;
        mContentHeight = height + mTopPadding + mBottomMargin;
        updateScrollability();
        clampScrollPosition();
        mAmbientState.setLayoutMaxHeight(mContentHeight);
    }

    private boolean isPulsing(NotificationData.Entry entry) {
        return mAmbientState.isPulsing(entry);
    }

    @Override
    public boolean hasPulsingNotifications() {
        return mPulsing;
    }

    private void updateScrollability() {
        boolean scrollable = getScrollRange() > 0;
        if (scrollable != mScrollable) {
            mScrollable = scrollable;
            setFocusable(scrollable);
            updateForwardAndBackwardScrollability();
        }
    }

    private void updateForwardAndBackwardScrollability() {
        boolean forwardScrollable = mScrollable && mOwnScrollY < getScrollRange();
        boolean backwardsScrollable = mScrollable && mOwnScrollY > 0;
        boolean changed = forwardScrollable != mForwardScrollable
                || backwardsScrollable != mBackwardScrollable;
        mForwardScrollable = forwardScrollable;
        mBackwardScrollable = backwardsScrollable;
        if (changed) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
    }

    private void updateBackground() {
        // No need to update the background color if it's not being drawn.
        if (!mShouldDrawNotificationBackground || mAmbientState.isFullyDark()) {
            return;
        }

        updateBackgroundBounds();
        if (!mCurrentBounds.equals(mBackgroundBounds)) {
            boolean animate = mAnimateNextBackgroundTop || mAnimateNextBackgroundBottom
                    || areBoundsAnimating();
            if (!isExpanded()) {
                abortBackgroundAnimators();
                animate = false;
            }
            if (animate) {
                startBackgroundAnimation();
            } else {
                mCurrentBounds.set(mBackgroundBounds);
                applyCurrentBackgroundBounds();
            }
        } else {
            abortBackgroundAnimators();
        }
        mAnimateNextBackgroundBottom = false;
        mAnimateNextBackgroundTop = false;
    }

    private void abortBackgroundAnimators() {
        if (mBottomAnimator != null) {
            mBottomAnimator.cancel();
        }
        if (mTopAnimator != null) {
            mTopAnimator.cancel();
        }
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
        // If the background of the notification is not being drawn, then there is no need to
        // exclude an area in the scrim. Rather, the scrim's color should serve as the background.
        if (!mShouldDrawNotificationBackground) {
            return;
        }

        final boolean awake = mDarkAmount != 0 || mAmbientState.isDark();
        mScrimController.setExcludedBackgroundArea(
                mFadingOut || mParentNotFullyVisible || awake || mIsClipped ? null
                        : mCurrentBounds);
        invalidate();
    }

    /**
     * Update the background bounds to the new desired bounds
     */
    private void updateBackgroundBounds() {
        if (mAmbientState.isPanelFullWidth()) {
            mBackgroundBounds.left = 0;
            mBackgroundBounds.right = getWidth();
        } else {
            getLocationInWindow(mTempInt2);
            mBackgroundBounds.left = mTempInt2[0];
            mBackgroundBounds.right = mTempInt2[0] + getWidth();
        }
        mBackgroundBounds.left += mSidePaddings;
        mBackgroundBounds.right -= mSidePaddings;
        if (!mIsExpanded) {
            mBackgroundBounds.top = 0;
            mBackgroundBounds.bottom = 0;
            return;
        }
        ActivatableNotificationView firstView = mFirstVisibleBackgroundChild;
        int top = 0;
        if (firstView != null) {
            // Round Y up to avoid seeing the background during animation
            int finalTranslationY = (int) Math.ceil(ViewState.getFinalTranslationY(firstView));
            if (mAnimateNextBackgroundTop
                    || mTopAnimator == null && mCurrentBounds.top == finalTranslationY
                    || mTopAnimator != null && mEndAnimationRect.top == finalTranslationY) {
                // we're ending up at the same location as we are now, lets just skip the animation
                top = finalTranslationY;
            } else {
                top = (int) Math.ceil(firstView.getTranslationY());
            }
        }
        ActivatableNotificationView lastView =
                mShelf.hasItemsInStableShelf() && mShelf.getVisibility() != GONE
                        ? mShelf
                        : mLastVisibleBackgroundChild;
        int bottom;
        if (lastView != null) {
            int finalTranslationY;
            if (lastView == mShelf) {
                finalTranslationY = (int) mShelf.getTranslationY();
            } else {
                finalTranslationY = (int) ViewState.getFinalTranslationY(lastView);
            }
            int finalHeight = ExpandableViewState.getFinalActualHeight(lastView);
            int finalBottom = finalTranslationY + finalHeight - lastView.getClipBottomAmount();
            finalBottom = Math.min(finalBottom, getHeight());
            if (mAnimateNextBackgroundBottom
                    || mBottomAnimator == null && mCurrentBounds.bottom == finalBottom
                    || mBottomAnimator != null && mEndAnimationRect.bottom == finalBottom) {
                // we're ending up at the same location as we are now, lets just skip the animation
                bottom = finalBottom;
            } else {
                bottom = (int) (lastView.getTranslationY() + lastView.getActualHeight()
                        - lastView.getClipBottomAmount());
                bottom = Math.min(bottom, getHeight());
            }
        } else {
            top = mTopPadding;
            bottom = top;
        }
        if (mStatusBarState != StatusBarState.KEYGUARD) {
            top = (int) Math.max(mTopPadding + mStackTranslation, top);
        } else {
            // otherwise the animation from the shade to the keyguard will jump as it's maxed
            top = Math.max(0, top);
        }
        mBackgroundBounds.top = top;
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
            if (child.getVisibility() != View.GONE && child instanceof ActivatableNotificationView
                    && child != mShelf) {
                return (ActivatableNotificationView) child;
            }
        }
        return null;
    }

    private ActivatableNotificationView getFirstChildWithBackground() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE && child instanceof ActivatableNotificationView
                    && child != mShelf) {
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
    protected void fling(int velocityY) {
        if (getChildCount() > 0) {
            int scrollRange = getScrollRange();

            float topAmount = getCurrentOverScrollAmount(true);
            float bottomAmount = getCurrentOverScrollAmount(false);
            if (velocityY < 0 && topAmount > 0) {
                setOwnScrollY(mOwnScrollY - (int) topAmount);
                mDontReportNextOverScroll = true;
                setOverScrollAmount(0, true, false);
                mMaxOverScroll = Math.abs(velocityY) / 1000f * getRubberBandFactor(true /* onTop */)
                        * mOverflingDistance + topAmount;
            } else if (velocityY > 0 && bottomAmount > 0) {
                setOwnScrollY((int) (mOwnScrollY + bottomAmount));
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
            mScroller.fling(mScrollX, mOwnScrollY, 1, velocityY, 0, 0, 0, minScrollY, 0,
                    mExpandedInThisMotion && mOwnScrollY >= 0 ? 0 : Integer.MAX_VALUE / 2);

            animateScroll();
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
        int topPadding = (int) qsHeight;
        int minStackHeight = getLayoutMinHeight();
        if (topPadding + minStackHeight > getHeight()) {
            mTopPaddingOverflow = topPadding + minStackHeight - getHeight();
        } else {
            mTopPaddingOverflow = 0;
        }
        setTopPadding(ignoreIntrinsicPadding ? topPadding : clampPadding(topPadding),
                animate);
        setExpandedHeight(mExpandedHeight);
    }

    public int getLayoutMinHeight() {
        if (isHeadsUpTransition()) {
            return getTopHeadsUpPinnedHeight();
        }
        return mShelf.getVisibility() == GONE ? 0 : mShelf.getIntrinsicHeight();
    }

    public int getFirstChildIntrinsicHeight() {
        final ExpandableView firstChild = getFirstChildNotGone();
        int firstChildMinHeight = firstChild != null
                ? firstChild.getIntrinsicHeight()
                : mEmptyShadeView != null
                        ? mEmptyShadeView.getIntrinsicHeight()
                        : mCollapsedSize;
        if (mOwnScrollY > 0) {
            firstChildMinHeight = Math.max(firstChildMinHeight - mOwnScrollY, mCollapsedSize);
        }
        return firstChildMinHeight;
    }

    public float getTopPaddingOverflow() {
        return mTopPaddingOverflow;
    }

    public int getPeekHeight() {
        final ExpandableView firstChild = getFirstChildNotGone();
        final int firstChildMinHeight = firstChild != null ? firstChild.getCollapsedHeight()
                : mCollapsedSize;
        int shelfHeight = 0;
        if (mLastVisibleBackgroundChild != null && mShelf.getVisibility() != GONE) {
            shelfHeight = mShelf.getIntrinsicHeight();
        }
        return mIntrinsicPadding + firstChildMinHeight + shelfHeight;
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
        // Check if we need to clear any snooze leavebehinds
        boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
        NotificationGuts guts = mStatusBar.getGutsManager().getExposedGuts();
        if (!isTouchInView(ev, guts) && isUp && !swipeWantsIt && !expandWantsIt
                && !scrollWantsIt) {
            mCheckForLeavebehind = false;
            mStatusBar.getGutsManager().closeAndSaveGuts(true /* removeLeavebehind */,
                    false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                    false /* resetMenu */);
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            mCheckForLeavebehind = true;
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
                if (mStatusBarState != StatusBarState.KEYGUARD && mTouchIsClick &&
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
    public void cleanUpViewState(View child) {
        if (child == mTranslatingParentView) {
            mTranslatingParentView = null;
        }
        mCurrentStackScrollState.removeViewStateForView(child);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        if (disallowIntercept) {
            cancelLongPress();
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
            if (!mSwipedOutViews.contains(child)
                    || Math.abs(expandableView.getTranslation()) != expandableView.getWidth()) {
                container.addTransientView(child, 0);
                expandableView.setTransientContainer(container);
            }
        } else {
            mSwipedOutViews.remove(child);
        }
        updateAnimationState(false, child);

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
                            : view.getTranslationY(), true /* ignoreChildren */);
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
        return HeadsUpUtil.isClickedHeadsUpNotification(child);
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
            ((ExpandableNotificationRow ) child).setHeadsUpAnimatingAway(false);
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
        float increasedPaddingAmount = removedChild.getIncreasedPaddingAmount();
        int padding;
        if (increasedPaddingAmount >= 0) {
            padding = (int) NotificationUtils.interpolate(
                    mPaddingBetweenElements,
                    mIncreasedPaddingBetweenElements,
                    increasedPaddingAmount);
        } else {
            padding = (int) NotificationUtils.interpolate(
                    0,
                    mPaddingBetweenElements,
                    1.0f + increasedPaddingAmount);
        }
        int childHeight = getIntrinsicHeight(removedChild) + padding;
        int endPosition = startingPosition + childHeight;
        if (endPosition <= mOwnScrollY) {
            // This child is fully scrolled of the top, so we have to deduct its height from the
            // scrollPosition
            setOwnScrollY(mOwnScrollY - childHeight);
        } else if (startingPosition < mOwnScrollY) {
            // This child is currently being scrolled into, set the scroll position to the start of
            // this child
            setOwnScrollY(startingPosition);
        }
    }

    private int getIntrinsicHeight(View view) {
        if (view instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) view;
            return expandableView.getIntrinsicHeight();
        }
        return view.getHeight();
    }

    public int getPositionInLinearLayout(View requestedView) {
        ExpandableNotificationRow childInGroup = null;
        ExpandableNotificationRow requestedRow = null;
        if (isChildInGroup(requestedView)) {
            // We're asking for a child in a group. Calculate the position of the parent first,
            // then within the parent.
            childInGroup = (ExpandableNotificationRow) requestedView;
            requestedView = requestedRow = childInGroup.getNotificationParent();
        }
        int position = 0;
        float previousPaddingRequest = mPaddingBetweenElements;
        float previousPaddingAmount = 0.0f;
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            boolean notGone = child.getVisibility() != View.GONE;
            if (notGone && !child.hasNoContentHeight()) {
                float increasedPaddingAmount = child.getIncreasedPaddingAmount();
                float padding;
                if (increasedPaddingAmount >= 0.0f) {
                    padding = (int) NotificationUtils.interpolate(
                            previousPaddingRequest,
                            mIncreasedPaddingBetweenElements,
                            increasedPaddingAmount);
                    previousPaddingRequest = (int) NotificationUtils.interpolate(
                            mPaddingBetweenElements,
                            mIncreasedPaddingBetweenElements,
                            increasedPaddingAmount);
                } else {
                    int ownPadding = (int) NotificationUtils.interpolate(
                            0,
                            mPaddingBetweenElements,
                            1.0f + increasedPaddingAmount);
                    if (previousPaddingAmount > 0.0f) {
                        padding = (int) NotificationUtils.interpolate(
                                ownPadding,
                                mIncreasedPaddingBetweenElements,
                                previousPaddingAmount);
                    } else {
                        padding = ownPadding;
                    }
                    previousPaddingRequest = ownPadding;
                }
                if (position != 0) {
                    position += padding;
                }
                previousPaddingAmount = increasedPaddingAmount;
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
        mAmbientState.setLastVisibleBackgroundChild(lastChild);
        mRoundnessManager.setFirstAndLastBackgroundChild(mFirstVisibleBackgroundChild,
                mLastVisibleBackgroundChild);
        invalidate();
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

    @Override
    public void notifyGroupChildRemoved(View row, ViewGroup childrenContainer) {
        onViewRemovedInternal(row, childrenContainer);
    }

    @Override
    public void notifyGroupChildAdded(View row) {
        onViewAddedInternal(row);
    }

    public void setAnimationsEnabled(boolean animationsEnabled) {
        mAnimationsEnabled = animationsEnabled;
        updateNotificationAnimationStates();
        if (!animationsEnabled) {
            mSwipedOutViews.clear();
            mChildrenToRemoveAnimated.clear();
            clearTemporaryViewsInGroup(this);
        }
    }

    private void updateNotificationAnimationStates() {
        boolean running = mAnimationsEnabled || hasPulsingNotifications();
        mShelf.setAnimationsEnabled(running);
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            running &= mIsExpanded || isPinnedHeadsUp(child);
            updateAnimationState(running, child);
        }
    }

    private void updateAnimationState(View child) {
        updateAnimationState((mAnimationsEnabled || hasPulsingNotifications())
                && (mIsExpanded || isPinnedHeadsUp(child)), child);
    }

    @Override
    public void setExpandingNotification(ExpandableNotificationRow row) {
        mAmbientState.setExpandingNotification(row);
        requestChildrenUpdate();
    }

    @Override
    public void bindRow(ExpandableNotificationRow row) {
        row.setHeadsUpAnimatingAwayListener(animatingAway
                -> mRoundnessManager.onHeadsupAnimatingAwayChanged(row, animatingAway));
    }

    @Override
    public void applyExpandAnimationParams(ExpandAnimationParameters params) {
        mAmbientState.setExpandAnimationTopChange(params == null ? 0 : params.getTopChange());
        requestChildrenUpdate();
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

    @Override
    public void generateAddAnimation(View child, boolean fromMoreCard) {
        if (mIsExpanded && mAnimationsEnabled && !mChangePositionInProgress) {
            // Generate Animations
            mChildrenToAddAnimated.add(child);
            if (fromMoreCard) {
                mFromMoreCardAdditions.add(child);
            }
            mNeedsAnimation = true;
        }
        if (isHeadsUp(child) && mAnimationsEnabled && !mChangePositionInProgress) {
            mAddedHeadsUpChildren.add(child);
            mChildrenToAddAnimated.remove(child);
        }
    }

    @Override
    public void changeViewPosition(View child, int newIndex) {
        int currentIndex = indexOfChild(child);

        if (currentIndex == -1) {
            boolean isTransient = false;
            if (child instanceof ExpandableNotificationRow
                    && ((ExpandableNotificationRow)child).getTransientContainer() != null) {
                isTransient = true;
            }
            Log.e(TAG, "Attempting to re-position "
                    + (isTransient ? "transient" : "")
                    + " view {"
                    + child
                    + "}");
            return;
        }

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
            updateClippingToTopRoundedCorner();
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
                    row.setHeadsUpAnimatingAway(false);
                }
            } else {
                ExpandableViewState viewState = mCurrentStackScrollState.getViewStateForView(row);
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

    private boolean shouldHunAppearFromBottom(ExpandableViewState viewState) {
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

            // we need to know the view after this one
            float removedTranslation = child.getTranslationY();
            boolean ignoreChildren = true;
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (row.isRemoved() && row.wasChildInGroupWhenRemoved()) {
                    removedTranslation = row.getTranslationWhenRemoved();
                    ignoreChildren = false;
                }
                childWasSwipedOut |= Math.abs(row.getTranslation()) == row.getWidth();
            }
            if (!childWasSwipedOut) {
                Rect clipBounds = child.getClipBounds();
                childWasSwipedOut = clipBounds != null && clipBounds.height() == 0;
            }
            int animationType = childWasSwipedOut
                    ? AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT
                    : AnimationEvent.ANIMATION_TYPE_REMOVE;
            AnimationEvent event = new AnimationEvent(child, animationType);
            event.viewAfterChangingView = getFirstChildBelowTranlsationY(removedTranslation,
                    ignoreChildren);
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
            AnimationEvent ev = new AnimationEvent(null,
                    AnimationEvent.ANIMATION_TYPE_DARK,
                    new AnimationFilter()
                            .animateDark()
                            .animateY(mShelf));
            ev.darkAnimationOriginIndex = mDarkAnimationOriginIndex;
            mAnimationEvents.add(ev);
            startBackgroundFade();
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
                if (getChildAtPosition(ev.getX(), y, false /* requireMinHeight */) == null) {
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
                    animateScroll();
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

    protected StackScrollAlgorithm createStackScrollAlgorithm(Context context) {
        return new StackScrollAlgorithm(context);
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
            cancelLongPress();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            cancelLongPress();
        }
    }

    @Override
    public void clearChildFocus(View child) {
        super.clearChildFocus(child);
        if (mForcedScroll == child) {
            mForcedScroll = null;
        }
    }

    public void requestDisallowLongPress() {
        cancelLongPress();
    }

    public void requestDisallowDismiss() {
        mDisallowDismissInThisMotion = true;
    }

    public void cancelLongPress() {
        mSwipeHelper.cancelLongPress();
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
        return Math.max(mMaxLayoutHeight - mContentHeight, 0);
    }

    public void checkSnoozeLeavebehind() {
        if (mCheckForLeavebehind) {
            mStatusBar.getGutsManager().closeAndSaveGuts(true /* removeLeavebehind */,
                    false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                    false /* resetMenu */);
            mCheckForLeavebehind = false;
        }
    }

    public void resetCheckSnoozeLeavebehind() {
        mCheckForLeavebehind = true;
    }

    public void onExpansionStarted() {
        mIsExpansionChanging = true;
        mAmbientState.setExpansionChanging(true);
        checkSnoozeLeavebehind();
    }

    public void onExpansionStopped() {
        mIsExpansionChanging = false;
        resetCheckSnoozeLeavebehind();
        mAmbientState.setExpansionChanging(false);
        if (!mIsExpanded) {
            setOwnScrollY(0);
            mStatusBar.resetUserExpandedStates();
            clearTemporaryViews();
            clearUserLockedViews();
        }
    }

    private void clearUserLockedViews() {
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                row.setUserLocked(false);
            }
        }
    }

    private void clearTemporaryViews() {
        // lets make sure nothing is in the overlay / transient anymore
        clearTemporaryViewsInGroup(this);
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                clearTemporaryViewsInGroup(row.getChildrenContainer());
            }
        }
    }

    private void clearTemporaryViewsInGroup(ViewGroup viewGroup) {
        while (viewGroup != null && viewGroup.getTransientViewCount() != 0) {
            viewGroup.removeTransientView(viewGroup.getTransientView(0));
        }
        if (viewGroup != null) {
            viewGroup.getOverlay().clear();
        }
    }

    public void onPanelTrackingStarted() {
        mPanelTracking = true;
        mAmbientState.setPanelTracking(true);
    }
    public void onPanelTrackingStopped() {
        mPanelTracking = false;
        mAmbientState.setPanelTracking(false);
    }

    public void resetScrollPosition() {
        mScroller.abortAnimation();
        setOwnScrollY(0);
    }

    private void setIsExpanded(boolean isExpanded) {
        boolean changed = isExpanded != mIsExpanded;
        mIsExpanded = isExpanded;
        mStackScrollAlgorithm.setIsExpanded(isExpanded);
        if (changed) {
            if (!mIsExpanded) {
                mGroupManager.collapseAllGroups();
                mExpandHelper.cancelImmediately();
            }
            updateNotificationAnimationStates();
            updateChronometers();
            requestChildrenUpdate();
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
        notifyHeightChangeListener(view, needsAnimation);
        ExpandableNotificationRow row = view instanceof ExpandableNotificationRow
                ? (ExpandableNotificationRow) view
                : null;
        if (row != null && (row == mFirstVisibleBackgroundChild
                || row.getNotificationParent() == mFirstVisibleBackgroundChild)) {
            updateAlgorithmLayoutMinHeight();
        }
        if (needsAnimation) {
            requestAnimationOnViewResize(row);
        }
        requestChildrenUpdate();
    }

    @Override
    public void onReset(ExpandableView view) {
        updateAnimationState(view);
        updateChronometerForChild(view);
    }

    private void updateScrollPositionOnExpandInBottom(ExpandableView view) {
        if (view instanceof ExpandableNotificationRow && !onKeyguard()) {
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
                int layoutEnd = mMaxLayoutHeight + (int) mStackTranslation;
                if (row != mLastVisibleBackgroundChild && mShelf.getVisibility() != GONE) {
                    layoutEnd -= mShelf.getIntrinsicHeight() + mPaddingBetweenElements;
                }
                if (endPosition > layoutEnd) {
                    setOwnScrollY((int) (mOwnScrollY + endPosition - layoutEnd));
                    mDisallowScrollingInThisMotion = true;
                }
            }
        }
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
                row.setHeadsUpAnimatingAway(false);
                if (row.isSummaryWithChildren()) {
                    for (ExpandableNotificationRow child : row.getNotificationChildren()) {
                        child.setHeadsUpAnimatingAway(false);
                    }
                }
            }
        }
    }

    private void clearViewOverlays() {
        for (View view : mClearOverlayViewsWhenFinished) {
            StackStateAnimator.removeFromOverlay(view);
        }
        mClearOverlayViewsWhenFinished.clear();
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
        dimmed &= onKeyguard();
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

    @VisibleForTesting
    boolean isDimmed() {
        return mAmbientState.isDimmed();
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
            updateContentHeight();
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
            mListener.onChildLocationsChanged();
        }
        runAnimationFinishedRunnables();
        setAnimationRunning(false);
        updateBackground();
        updateViewShadows();
        updateClippingToTopRoundedCorner();
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

    /**
     * Update colors of "dismiss" and "empty shade" views.
     *
     * @param lightTheme True if light theme should be used.
     */
    public void updateDecorViews(boolean lightTheme) {
        if (lightTheme == mUsingLightTheme) {
            return;
        }
        mUsingLightTheme = lightTheme;
        Context context = new ContextThemeWrapper(mContext,
                lightTheme ? R.style.Theme_SystemUI_Light : R.style.Theme_SystemUI);
        final int textColor = Utils.getColorAttr(context, R.attr.wallpaperTextColor);
        mFooterView.setTextColor(textColor);
        mEmptyShadeView.setTextColor(textColor);
    }

    public void goToFullShade(long delay) {
        if (mFooterView != null) {
            mFooterView.setInvisible();
        }
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
        mAmbientState.setIntrinsicPadding(intrinsicPadding);
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
        if (mAmbientState.isDark() == dark) {
            return;
        }
        mAmbientState.setDark(dark);
        if (animate && mAnimationsEnabled) {
            mDarkNeedsAnimation = true;
            mDarkAnimationOriginIndex = findDarkAnimationOriginIndex(touchWakeUpScreenLocation);
            mNeedsAnimation =  true;
        } else {
            setDarkAmount(dark ? 1f : 0f);
            updateBackground();
        }
        requestChildrenUpdate();
        applyCurrentBackgroundBounds();
        updateWillNotDraw();
        notifyHeightChangeListener(mShelf);
    }

    private void updateAntiBurnInTranslation() {
        setTranslationX(mAntiBurnInOffsetX * mDarkAmount);
    }

    /**
     * Updates whether or not this Layout will perform its own custom drawing (i.e. whether or
     * not {@link #onDraw(Canvas)} is called). This method should be called whenever the
     * {@link #mAmbientState}'s dark mode is toggled.
     */
    private void updateWillNotDraw() {
        boolean willDraw = mShouldDrawNotificationBackground || DEBUG;
        setWillNotDraw(!willDraw);
    }

    private void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        boolean wasFullyDark = mAmbientState.isFullyDark();
        mAmbientState.setDarkAmount(darkAmount);
        if (mAmbientState.isFullyDark() != wasFullyDark) {
            updateContentHeight();
            DozeParameters dozeParameters = DozeParameters.getInstance(mContext);
            if (mAmbientState.isFullyDark() && dozeParameters.shouldControlScreenOff()) {
                mShelf.fadeInTranslating();
            }
        }
        updateAlgorithmHeightAndPadding();
        updateBackgroundDimming();
        updateAntiBurnInTranslation();
        requestChildrenUpdate();
    }

    public float getDarkAmount() {
        return mDarkAmount;
    }

    private void startBackgroundFade() {
        ObjectAnimator fadeAnimator = ObjectAnimator.ofFloat(this, DARK_AMOUNT, mDarkAmount,
                mAmbientState.isDark() ? 1f : 0);
        fadeAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP);
        fadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        fadeAnimator.start();
    }

    private int findDarkAnimationOriginIndex(@Nullable PointF screenLocation) {
        if (screenLocation == null || screenLocation.y < mTopPadding) {
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

    public void setFooterView(@NonNull FooterView footerView) {
        int index = -1;
        if (mFooterView != null) {
            index = indexOfChild(mFooterView);
            removeView(mFooterView);
        }
        mFooterView = footerView;
        addView(mFooterView, index);
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
                if (mStatusBar.areNotificationsHidden()) {
                    mEmptyShadeView.setText(R.string.dnd_suppressing_shade_text);
                } else {
                    mEmptyShadeView.setText(R.string.empty_shade_text);
                }
                showFooterView(mEmptyShadeView);
            } else {
                hideFooterView(mEmptyShadeView, true);
            }
        }
    }

    public void updateFooterView(boolean visible, boolean showDismissView) {
        if (mFooterView == null) {
            return;
        }
        int oldVisibility = mFooterView.willBeGone() ? GONE : mFooterView.getVisibility();
        int newVisibility = visible ? VISIBLE : GONE;
        if (oldVisibility != newVisibility) {
            if (newVisibility != GONE) {
                showFooterView(mFooterView);
            } else {
                hideFooterView(mFooterView, mFooterView.isButtonVisible());
            }
        }
        if (mFooterView.isSecondaryVisible() != showDismissView) {
            mFooterView.performSecondaryVisibilityAnimation(showDismissView);
        }
    }

    private void showFooterView(StackScrollerDecorView footerView) {
        if (footerView.willBeGone()) {
            footerView.cancelAnimation();
        } else {
            footerView.setInvisible();
        }
        footerView.setVisibility(VISIBLE);
        footerView.setWillBeGone(false);
        updateContentHeight();
        notifyHeightChangeListener(footerView);
    }

    private void hideFooterView(StackScrollerDecorView footerView, boolean isButtonVisible) {
        Runnable onHideFinishRunnable = new Runnable() {
            @Override
            public void run() {
                footerView.setVisibility(GONE);
                footerView.setWillBeGone(false);
                updateContentHeight();
                notifyHeightChangeListener(footerView);
            }
        };
        if (isButtonVisible && mIsExpanded && mAnimationsEnabled) {
            footerView.setWillBeGone(true);
            footerView.performVisibilityAnimation(false, onHideFinishRunnable);
        } else {
            onHideFinishRunnable.run();
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

    public boolean isFooterViewNotGone() {
        return mFooterView != null
                && mFooterView.getVisibility() != View.GONE
                && !mFooterView.willBeGone();
    }

    public boolean isFooterViewVisible() {
        return mFooterView != null && mFooterView.isVisible();
    }

    public int getFooterViewHeight() {
        return mFooterView == null ? 0 : mFooterView.getHeight() + mPaddingBetweenElements;
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
            float bottom = child.getTranslationY() + child.getActualHeight()
                    - child.getClipBottomAmount();
            if (bottom > max) {
                max = bottom;
            }
        }
        return max + getStackTranslation();
    }

    public void setStatusBar(StatusBar statusBar) {
        this.mStatusBar = statusBar;
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
                boolean belowChild = touchY > childTop + child.getActualHeight()
                        - child.getClipBottomAmount();
                if (child == mFooterView) {
                    if(!belowChild && !mFooterView.isOnEmptySpace(touchX - mFooterView.getX(),
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
        mStatusBar.requestNotificationUpdate();
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
        if (mScrollable) {
            info.setScrollable(true);
            if (mBackwardScrollable) {
                info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
            }
            if (mForwardScrollable) {
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
                        - mShelf.getIntrinsicHeight();
                final int targetScrollY = Math.max(0,
                        Math.min(mOwnScrollY + direction * viewportHeight, getScrollRange()));
                if (targetScrollY != mOwnScrollY) {
                    mScroller.startScroll(mScrollX, mOwnScrollY, 0, targetScrollY - mOwnScrollY);
                    animateScroll();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public void onGroupsChanged() {
        mStatusBar.requestNotificationUpdate();
    }

    public void generateChildOrderChangedEvent() {
        if (mIsExpanded && mAnimationsEnabled) {
            mGenerateChildOrderChangedEvent = true;
            mNeedsAnimation = true;
            requestChildrenUpdate();
        }
    }

    @Override
    public int getContainerChildCount() {
        return getChildCount();
    }

    @Override
    public View getContainerChildAt(int i) {
        return getChildAt(i);
    }

    @Override
    public void removeContainerView(View v) {
        removeView(v);
    }

    @Override
    public void addContainerView(View v) {
        addView(v);
    }

    public void runAfterAnimationFinished(Runnable runnable) {
        mAnimationFinishedRunnables.add(runnable);
    }

    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        mHeadsUpManager = headsUpManager;
        mAmbientState.setHeadsUpManager(headsUpManager);
        mHeadsUpManager.addListener(mRoundnessManager);
    }

    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        if (mAnimationsEnabled && (isHeadsUp || mHeadsUpGoingAwayAnimationsAllowed)) {
            mHeadsUpChangeAnimations.add(new Pair<>(row, isHeadsUp));
            mNeedsAnimation = true;
            if (!mIsExpanded && !isHeadsUp) {
                row.setHeadsUpAnimatingAway(true);
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

    public void setTrackingHeadsUp(ExpandableNotificationRow row) {
        mTrackingHeadsUp = row != null;
        mRoundnessManager.setTrackingHeadsUp(row);
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
        mScrimController.setScrimBehindChangeRunnable(this::updateBackgroundDimming);
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
                getViewTreeObserver().addOnPreDrawListener(mRunningAnimationUpdater);
            } else {
                getViewTreeObserver().removeOnPreDrawListener(mRunningAnimationUpdater);
            }
            mAnimationRunning = animationRunning;
            updateContinuousShadowDrawing();
        }
    }

    public boolean isExpanded() {
        return mIsExpanded;
    }

    public void setPulsing(boolean pulsing, int clockBottom) {
        if (!mPulsing && !pulsing) {
            return;
        }
        mPulsing = pulsing;
        mClockBottom = clockBottom;
        mAmbientState.setPulsing(pulsing);
        updateNotificationAnimationStates();
        updateAlgorithmHeightAndPadding();
        updateContentHeight();
        notifyHeightChangeListener(mShelf);
        requestChildrenUpdate();
    }

    public void setFadingOut(boolean fadingOut) {
        if (fadingOut != mFadingOut) {
            mFadingOut = fadingOut;
            updateFadingState();
        }
    }

    public void setParentNotFullyVisible(boolean parentNotFullyVisible) {
        if (mScrimController == null) {
            // we're not set up yet.
            return;
        }
        if (parentNotFullyVisible != mParentNotFullyVisible) {
            mParentNotFullyVisible = parentNotFullyVisible;
            updateFadingState();
        }
    }

    private void updateFadingState() {
        applyCurrentBackgroundBounds();
        updateSrcDrawing();
    }

    @Override
    public void setAlpha(@FloatRange(from = 0.0, to = 1.0) float alpha) {
        super.setAlpha(alpha);
        setFadingOut(alpha != 1.0f);
    }

    public void setQsExpanded(boolean qsExpanded) {
        mQsExpanded = qsExpanded;
        updateAlgorithmLayoutMinHeight();
    }

    public void setOwnScrollY(int ownScrollY) {
        if (ownScrollY != mOwnScrollY) {
            // We still want to call the normal scrolled changed for accessibility reasons
            onScrollChanged(mScrollX, ownScrollY, mScrollX, mOwnScrollY);
            mOwnScrollY = ownScrollY;
            updateForwardAndBackwardScrollability();
            requestChildrenUpdate();
        }
    }

    public void setShelf(NotificationShelf shelf) {
        int index = -1;
        if (mShelf != null) {
            index = indexOfChild(mShelf);
            removeView(mShelf);
        }
        mShelf = shelf;
        addView(mShelf, index);
        mAmbientState.setShelf(shelf);
        mStateAnimator.setShelf(shelf);
        shelf.bind(mAmbientState, this);
    }

    public NotificationShelf getNotificationShelf() {
        return mShelf;
    }

    public void setMaxDisplayedNotifications(int maxDisplayedNotifications) {
        if (mMaxDisplayedNotifications != maxDisplayedNotifications) {
            mMaxDisplayedNotifications = maxDisplayedNotifications;
            updateContentHeight();
            notifyHeightChangeListener(mShelf);
        }
    }

    public int getMinExpansionHeight() {
        return mShelf.getIntrinsicHeight() - (mShelf.getIntrinsicHeight() - mStatusBarHeight) / 2;
    }

    public void setInHeadsUpPinnedMode(boolean inHeadsUpPinnedMode) {
        mInHeadsUpPinnedMode = inHeadsUpPinnedMode;
        updateClipping();
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        updateClipping();
    }

    public void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
        mAmbientState.setStatusBarState(statusBarState);
    }

    public void setExpandingVelocity(float expandingVelocity) {
        mAmbientState.setExpandingVelocity(expandingVelocity);
    }

    public float getOpeningHeight() {
        if (mEmptyShadeView.getVisibility() == GONE) {
            return getMinExpansionHeight();
        } else {
            return getAppearEndPosition();
        }
    }

    public void setIsFullWidth(boolean isFullWidth) {
        mAmbientState.setPanelFullWidth(isFullWidth);
    }

    public void setUnlockHintRunning(boolean running) {
        mAmbientState.setUnlockHintRunning(running);
    }

    public void setQsCustomizerShowing(boolean isShowing) {
        mAmbientState.setQsCustomizerShowing(isShowing);
        requestChildrenUpdate();
    }

    public void setHeadsUpGoingAwayAnimationsAllowed(boolean headsUpGoingAwayAnimationsAllowed) {
        mHeadsUpGoingAwayAnimationsAllowed = headsUpGoingAwayAnimationsAllowed;
    }

    public void setAntiBurnInOffsetX(int antiBurnInOffsetX) {
        mAntiBurnInOffsetX = antiBurnInOffsetX;
        updateAntiBurnInTranslation();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("[%s: pulsing=%s qsCustomizerShowing=%s visibility=%s"
                        + " alpha:%f scrollY:%d]",
                this.getClass().getSimpleName(),
                mPulsing ? "T":"f",
                mAmbientState.isQsCustomizerShowing() ? "T":"f",
                getVisibility() == View.VISIBLE ? "visible"
                        : getVisibility() == View.GONE ? "gone"
                                : "invisible",
                getAlpha(),
                mAmbientState.getScrollY()));
    }

    public boolean isFullyDark() {
        return mAmbientState.isFullyDark();
    }

    /**
     * Add a listener whenever the expanded height changes. The first value passed as an argument
     * is the expanded height and the second one is the appearFraction.
     *
     * @param listener the listener to notify.
     */
    public void addOnExpandedHeightListener(BiConsumer<Float, Float> listener) {
        mExpandedHeightListeners.add(listener);
    }

    /**
     * Stop a listener from listening to the expandedHeight.
     */
    public void removeOnExpandedHeightListener(BiConsumer<Float, Float> listener) {
        mExpandedHeightListeners.remove(listener);
    }

    /**
     * A listener that is notified when the empty space below the notifications is clicked on
     */
    public interface OnEmptySpaceClickListener {
        void onEmptySpaceClicked(float x, float y);
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
        void onOverscrollTopChanged(float amount, boolean isRubberbanded);

        /**
         * Notify a listener that the scroller wants to escape from the scrolling motion and
         * start a fling animation to the expanded or collapsed overscroll view (e.g expand the QS)
         *
         * @param velocity The velocity that the Scroller had when over flinging
         * @param open Should the fling open or close the overscroll view.
         */
        void flingTopOverscroll(float velocity, boolean open);
    }

    private class NotificationSwipeHelper extends SwipeHelper
            implements NotificationSwipeActionHelper {
        private static final long COVER_MENU_DELAY = 4000;
        private Runnable mFalsingCheck;
        private Handler mHandler;

        public NotificationSwipeHelper(int swipeDirection, Callback callback, Context context) {
            super(swipeDirection, callback, context);
            mHandler = new Handler();
            mFalsingCheck = new Runnable() {
                @Override
                public void run() {
                    resetExposedMenuView(true /* animate */, true /* force */);
                }
            };
        }

        @Override
        public void onDownUpdate(View currView, MotionEvent ev) {
            mTranslatingParentView = currView;
            if (mCurrMenuRow != null) {
                mCurrMenuRow.onTouchEvent(currView, ev, 0 /* velocity */);
            }
            mCurrMenuRow = null;
            mHandler.removeCallbacks(mFalsingCheck);

            // Slide back any notifications that might be showing a menu
            resetExposedMenuView(true /* animate */, false /* force */);

            if (currView instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) currView;
                mCurrMenuRow = row.createMenu();
                mCurrMenuRow.setSwipeActionHelper(NotificationSwipeHelper.this);
                mCurrMenuRow.setMenuClickListener(NotificationStackScrollLayout.this);
                mCurrMenuRow.onTouchEvent(currView, ev, 0 /* velocity */);
            }
        }

        @Override
        public void onMoveUpdate(View view, MotionEvent ev, float translation, float delta) {
            mHandler.removeCallbacks(mFalsingCheck);
            if (mCurrMenuRow != null) {
                mCurrMenuRow.onTouchEvent(view, ev, 0 /* velocity */);
            }
        }

        @Override
        public boolean handleUpEvent(MotionEvent ev, View animView, float velocity,
                float translation) {
            if (mCurrMenuRow != null) {
                return mCurrMenuRow.onTouchEvent(animView, ev, velocity);
            }
            return false;
        }

        @Override
        public void dismissChild(final View view, float velocity,
                boolean useAccelerateInterpolator) {
            super.dismissChild(view, velocity, useAccelerateInterpolator);
            if (mIsExpanded) {
                // We don't want to quick-dismiss when it's a heads up as this might lead to closing
                // of the panel early.
                handleChildViewDismissed(view);
            }
            mStatusBar.getGutsManager().closeAndSaveGuts(true /* removeLeavebehind */,
                    false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                    false /* resetMenu */);
            handleMenuCoveredOrDismissed();
        }

        @Override
        public void snapChild(final View animView, final float targetLeft, float velocity) {
            super.snapChild(animView, targetLeft, velocity);
            onDragCancelled(animView);
            if (targetLeft == 0) {
                handleMenuCoveredOrDismissed();
            }
        }

        @Override
        public void snooze(StatusBarNotification sbn, SnoozeOption snoozeOption) {
            mStatusBar.setNotificationSnoozed(sbn, snoozeOption);
        }

        public boolean isFalseGesture(MotionEvent ev) {
            return super.isFalseGesture(ev);
        }

        private void handleMenuCoveredOrDismissed() {
            if (mMenuExposedView != null && mMenuExposedView == mTranslatingParentView) {
                mMenuExposedView = null;
            }
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

        @Override
        public void dismiss(View animView, float velocity) {
            dismissChild(animView, velocity,
                    !swipedFastEnough(0, 0) /* useAccelerateInterpolator */);
        }

        @Override
        public void snap(View animView, float targetLeft, float velocity) {
            snapChild(animView, targetLeft, velocity);
        }

        @Override
        public boolean swipedFarEnough(float translation, float viewSize) {
            return swipedFarEnough();
        }

        @Override
        public boolean swipedFastEnough(float translation, float velocity) {
            return swipedFastEnough();
        }

        @Override
        public float getMinDismissVelocity() {
            return getEscapeVelocity();
        }

        public void onMenuShown(View animView) {
            onDragCancelled(animView);

            // If we're on the lockscreen we want to false this.
            if (isAntiFalsingNeeded()) {
                mHandler.removeCallbacks(mFalsingCheck);
                mHandler.postDelayed(mFalsingCheck, COVER_MENU_DELAY);
            }
        }

        public void closeControlsIfOutsideTouch(MotionEvent ev) {
            NotificationGuts guts = mStatusBar.getGutsManager().getExposedGuts();
            View view = null;
            if (guts != null && !guts.getGutsContent().isLeavebehind()) {
                // Only close visible guts if they're not a leavebehind.
                view = guts;
            } else if (mCurrMenuRow != null && mCurrMenuRow.isMenuVisible()
                    && mTranslatingParentView != null) {
                // Checking menu
                view = mTranslatingParentView;
            }
            if (view != null && !isTouchInView(ev, view)) {
                // Touch was outside visible guts / menu notification, close what's visible
                mStatusBar.getGutsManager().closeAndSaveGuts(false /* removeLeavebehind */,
                        false /* force */, true /* removeControls */, -1 /* x */, -1 /* y */,
                        false /* resetMenu */);
                resetExposedMenuView(true /* animate */, true /* force */);
            }
        }

        public void resetExposedMenuView(boolean animate, boolean force) {
            if (mMenuExposedView == null
                    || (!force && mMenuExposedView == mTranslatingParentView)) {
                // If no menu is showing or it's showing for this view we do nothing.
                return;
            }
            final View prevMenuExposedView = mMenuExposedView;
            if (animate) {
                Animator anim = getViewTranslationAnimator(prevMenuExposedView,
                        0 /* leftTarget */, null /* updateListener */);
                if (anim != null) {
                    anim.start();
                }
            } else if (mMenuExposedView instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) mMenuExposedView;
                if (!row.isRemoved()) {
                    row.resetTranslation();
                }
            }
            mMenuExposedView = null;
        }
    }

    private boolean isTouchInView(MotionEvent ev, View view) {
        if (view == null) {
            return false;
        }
        final int height = (view instanceof ExpandableView)
                ? ((ExpandableView) view).getActualHeight()
                : view.getHeight();
        final int rx = (int) ev.getRawX();
        final int ry = (int) ev.getRawY();
        view.getLocationOnScreen(mTempInt2);
        final int x = mTempInt2[0];
        final int y = mTempInt2[1];
        Rect rect = new Rect(x, y, x + view.getWidth(), y + height);
        boolean ret = rect.contains(rx, ry);
        return ret;
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

    @Override
    public void resetExposedMenuView(boolean animate, boolean force) {
        mSwipeHelper.resetExposedMenuView(animate, force);
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
                null, // Unused

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
                        .animateZ()
                        .hasDelays(),

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
                StackStateAnimator.ANIMATION_DURATION_WAKEUP,

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

        AnimationEvent(View view, int type, AnimationFilter filter) {
            this(view, type, LENGTHS[type], filter);
        }

        AnimationEvent(View view, int type, long length) {
            this(view, type, length, FILTERS[type]);
        }

        AnimationEvent(View view, int type, long length, AnimationFilter filter) {
            eventStartTime = AnimationUtils.currentAnimationTimeMillis();
            changingView = view;
            animationType = type;
            this.length = length;
            this.filter = filter;
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
