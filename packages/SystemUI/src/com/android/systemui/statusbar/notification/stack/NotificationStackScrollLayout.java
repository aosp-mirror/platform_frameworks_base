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
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.stack;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_SCROLL_FLING;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_SILENT;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_SWIPE;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;
import static com.android.systemui.util.Utils.shouldUseSplitNotificationShade;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.OverScroller;
import android.widget.ScrollView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.keyguard.KeyguardSliceView;
import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.ExpandAnimationParameters;
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorController;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.ShadeViewRefactor;
import com.android.systemui.statusbar.notification.ShadeViewRefactor.RefactorComponent;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.FooterView;
import com.android.systemui.statusbar.notification.row.ForegroundServiceDungeonView;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.ScrollAdapter;
import com.android.systemui.util.Assert;
import com.android.systemui.util.leak.RotationUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A layout which handles a dynamic amount of notifications and presents them in a scrollable stack.
 */
public class NotificationStackScrollLayout extends ViewGroup implements Dumpable {

    public static final float BACKGROUND_ALPHA_DIMMED = 0.7f;
    private static final String TAG = "StackScroller";

    // Usage:
    // adb shell setprop persist.debug.nssl true && adb reboot
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.debug.nssl",
            false /* default */);
    // TODO(b/187291379) disable again before release
    private static final boolean DEBUG_REMOVE_ANIMATION = SystemProperties.getBoolean(
            "persist.debug.nssl.dismiss", false /* default */);

    // Delay in milli-seconds before shade closes for clear all.
    private final int DELAY_BEFORE_SHADE_CLOSE = 200;
    private boolean mShadeNeedsToClose = false;

    private static final float RUBBER_BAND_FACTOR_NORMAL = 0.35f;
    private static final float RUBBER_BAND_FACTOR_AFTER_EXPAND = 0.15f;
    private static final float RUBBER_BAND_FACTOR_ON_PANEL_EXPAND = 0.21f;
    /**
     * Sentinel value for no current active pointer. Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;
    /**
     * The distance in pixels between sections when the sections are directly adjacent (no visible
     * gap is drawn between them). In this case we don't want to round their corners.
     */
    private static final int DISTANCE_BETWEEN_ADJACENT_SECTIONS_PX = 1;
    private KeyguardBypassEnabledProvider mKeyguardBypassEnabledProvider;

    private ExpandHelper mExpandHelper;
    private NotificationSwipeHelper mSwipeHelper;
    private int mCurrentStackHeight = Integer.MAX_VALUE;
    private final Paint mBackgroundPaint = new Paint();
    private final boolean mShouldDrawNotificationBackground;
    private boolean mHighPriorityBeforeSpeedBump;
    private boolean mDismissRtl;

    private float mExpandedHeight;
    private int mOwnScrollY;
    private int mMaxLayoutHeight;

    private VelocityTracker mVelocityTracker;
    private OverScroller mScroller;
    /** Last Y position reported by {@link #mScroller}, used to calculate scroll delta. */
    private int mLastScrollerY;
    /**
     * True if the max position was set to a known position on the last call to {@link #mScroller}.
     */
    private boolean mIsScrollerBoundSet;
    private Runnable mFinishScrollingCallback;
    private int mTouchSlop;
    private float mSlopMultiplier;
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
    private int mMaxTopPadding;
    private int mTopPadding;
    private boolean mAnimateNextTopPaddingChange;
    private int mBottomMargin;
    private int mBottomInset = 0;
    private float mQsExpansionFraction;

    /**
     * The algorithm which calculates the properties for our children
     */
    private final StackScrollAlgorithm mStackScrollAlgorithm;
    private final AmbientState mAmbientState;

    private GroupMembershipManager mGroupMembershipManager;
    private GroupExpansionManager mGroupExpansionManager;
    private NotificationActivityStarter mNotificationActivityStarter;
    private HashSet<ExpandableView> mChildrenToAddAnimated = new HashSet<>();
    private ArrayList<View> mAddedHeadsUpChildren = new ArrayList<>();
    private ArrayList<ExpandableView> mChildrenToRemoveAnimated = new ArrayList<>();
    private ArrayList<ExpandableView> mChildrenChangingPositions = new ArrayList<>();
    private HashSet<View> mFromMoreCardAdditions = new HashSet<>();
    private ArrayList<AnimationEvent> mAnimationEvents = new ArrayList<>();
    private ArrayList<View> mSwipedOutViews = new ArrayList<>();
    private final StackStateAnimator mStateAnimator = new StackStateAnimator(this);
    private boolean mAnimationsEnabled;
    private boolean mChangePositionInProgress;
    private boolean mChildTransferInProgress;

    private int mSpeedBumpIndex = -1;
    private boolean mSpeedBumpIndexDirty = true;

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
    private boolean mActivateNeedsAnimation;
    private boolean mGoToFullShadeNeedsAnimation;
    private boolean mIsExpanded = true;
    private boolean mChildrenUpdateRequested;
    private boolean mIsExpansionChanging;
    private boolean mPanelTracking;
    private boolean mExpandingNotification;
    private boolean mExpandedInThisMotion;
    private boolean mShouldShowShelfOnly;
    protected boolean mScrollingEnabled;
    private boolean mIsCurrentUserSetup;
    protected FooterView mFooterView;
    protected EmptyShadeView mEmptyShadeView;
    private boolean mDismissAllInProgress;
    private FooterDismissListener mFooterDismissListener;
    private boolean mFlingAfterUpEvent;

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
    private ExpandableView mExpandedGroupView;
    private boolean mEverythingNeedsAnimation;

    /**
     * The maximum scrollPosition which we are allowed to reach when a notification was expanded.
     * This is needed to avoid scrolling too far after the notification was collapsed in the same
     * motion.
     */
    private int mMaxScrollAfterExpand;
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
    private HashSet<ExpandableView> mClearTransientViewsWhenFinished = new HashSet<>();
    private HashSet<Pair<ExpandableNotificationRow, Boolean>> mHeadsUpChangeAnimations
            = new HashSet<>();
    private boolean mTrackingHeadsUp;
    private boolean mForceNoOverlappingRendering;
    private final ArrayList<Pair<ExpandableNotificationRow, Boolean>> mTmpList = new ArrayList<>();
    private boolean mAnimationRunning;
    private ViewTreeObserver.OnPreDrawListener mRunningAnimationUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            onPreDrawDuringAnimation();
            return true;
        }
    };
    private NotificationSection[] mSections;
    private boolean mAnimateNextBackgroundTop;
    private boolean mAnimateNextBackgroundBottom;
    private boolean mAnimateNextSectionBoundsChange;
    private int mBgColor;
    private float mDimAmount;
    private ValueAnimator mDimAnimator;
    private ArrayList<ExpandableView> mTmpSortedChildren = new ArrayList<>();
    private final Animator.AnimatorListener mDimEndListener = new AnimatorListenerAdapter() {
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
    private boolean mContinuousBackgroundUpdate;
    private ViewTreeObserver.OnPreDrawListener mShadowUpdater
            = () -> {
                updateViewShadows();
                return true;
            };
    private ViewTreeObserver.OnPreDrawListener mBackgroundUpdater = () -> {
                updateBackground();
                return true;
            };
    private Comparator<ExpandableView> mViewPositionComparator = (view, otherView) -> {
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
    };
    private final ViewOutlineProvider mOutlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (mAmbientState.isHiddenAtAll()) {
                float xProgress = mHideXInterpolator.getInterpolation(
                        (1 - mLinearHideAmount) * mBackgroundXFactor);
                outline.setRoundRect(mBackgroundAnimationRect,
                        MathUtils.lerp(mCornerRadius / 2.0f, mCornerRadius,
                                xProgress));
                outline.setAlpha(1.0f - mAmbientState.getHideAmount());
            } else {
                ViewOutlineProvider.BACKGROUND.getOutline(view, outline);
            }
        }
    };
    private boolean mPulsing;
    private boolean mScrollable;
    private View mForcedScroll;

    /**
     * @see #setHideAmount(float, float)
     */
    private float mInterpolatedHideAmount = 0f;

    /**
     * @see #setHideAmount(float, float)
     */
    private float mLinearHideAmount = 0f;

    /**
     * How fast the background scales in the X direction as a factor of the Y expansion.
     */
    private float mBackgroundXFactor = 1f;

    private boolean mQsExpanded;
    private boolean mForwardScrollable;
    private boolean mBackwardScrollable;
    private NotificationShelf mShelf;
    private int mMaxDisplayedNotifications = -1;
    private int mStatusBarHeight;
    private int mMinInteractionHeight;
    private final Rect mClipRect = new Rect();
    private boolean mIsClipped;
    private Rect mRequestedClipBounds;
    private boolean mInHeadsUpPinnedMode;
    private boolean mHeadsUpAnimatingAway;
    private int mStatusBarState;
    private int mCachedBackgroundColor;
    private boolean mHeadsUpGoingAwayAnimationsAllowed = true;
    private Runnable mReflingAndAnimateScroll = () -> {
        animateScroll();
    };
    private int mCornerRadius;
    private int mMinimumPaddings;
    private int mQsTilePadding;
    private boolean mSkinnyNotifsInLandscape;
    private int mSidePaddings;
    private final Rect mBackgroundAnimationRect = new Rect();
    private ArrayList<BiConsumer<Float, Float>> mExpandedHeightListeners = new ArrayList<>();
    private int mHeadsUpInset;

    /**
     * The position of the scroll boundary relative to this view. This is where the notifications
     * stop scrolling and will start to clip instead.
     */
    private int mQsScrollBoundaryPosition;
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private final Rect mTmpRect = new Rect();
    private DismissListener mDismissListener;
    private DismissAllAnimationListener mDismissAllAnimationListener;
    private NotificationRemoteInputManager mRemoteInputManager;
    private ShadeController mShadeController;
    private Consumer<Boolean> mOnStackYChanged;

    protected boolean mClearAllEnabled;

    private Interpolator mHideXInterpolator = Interpolators.FAST_OUT_SLOW_IN;

    private final NotificationSectionsManager mSectionsManager;
    private ForegroundServiceDungeonView mFgsSectionView;
    private boolean mAnimateBottomOnLayout;
    private float mLastSentAppear;
    private float mLastSentExpandedHeight;
    private boolean mWillExpand;
    private int mGapHeight;

    /**
     * The extra inset during the full shade transition
     */
    private float mExtraTopInsetForFullShadeTransition;

    private int mWaterfallTopInset;
    private NotificationStackScrollLayoutController mController;

    private boolean mKeyguardMediaControllorVisible;

    /**
     * The clip path used to clip the view in a rounded way.
     */
    private final Path mRoundedClipPath = new Path();

    /**
     * The clip Path used to clip the launching notification. This may be different
     * from the normal path, as the views launch animation could start clipped.
     */
    private final Path mLaunchedNotificationClipPath = new Path();

    /**
     * Should we use rounded rect clipping right now
     */
    private boolean mShouldUseRoundedRectClipping = false;

    private int mRoundedRectClippingLeft;
    private int mRoundedRectClippingTop;
    private int mRoundedRectClippingBottom;
    private int mRoundedRectClippingRight;
    private float[] mBgCornerRadii = new float[8];

    /**
     * Whether stackY should be animated in case the view is getting shorter than the scroll
     * position and this scrolling will lead to the top scroll inset getting smaller.
     */
    private boolean mAnimateStackYForContentHeightChange = false;

    /**
     * Are we launching a notification right now
     */
    private boolean mLaunchingNotification;

    /**
     * Does the launching notification need to be clipped
     */
    private boolean mLaunchingNotificationNeedsToBeClipped;

    /**
     * The current launch animation params when launching a notification
     */
    private ExpandAnimationParameters mLaunchAnimationParams;

    /**
     * Corner radii of the launched notification if it's clipped
     */
    private float[] mLaunchedNotificationRadii = new float[8];

    /**
     * The notification that is being launched currently.
     */
    private ExpandableNotificationRow mExpandingNotificationRow;

    /**
     * Do notifications dismiss with normal transitioning
     */
    private boolean mDismissUsingRowTranslationX = true;
    private NotificationEntry mTopHeadsUpEntry;
    private long mNumHeadsUp;
    private NotificationStackScrollLayoutController.TouchHandler mTouchHandler;
    private final FeatureFlags mFeatureFlags;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private boolean mShouldUseSplitNotificationShade;

    private final ExpandableView.OnHeightChangedListener mOnChildHeightChangedListener =
            new ExpandableView.OnHeightChangedListener() {
                @Override
                public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
                    onChildHeightChanged(view, needsAnimation);
                }

                @Override
                public void onReset(ExpandableView view) {
                    onChildHeightReset(view);
                }
            };

    private Consumer<Integer> mScrollListener;
    private final ScrollAdapter mScrollAdapter = new ScrollAdapter() {
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
            return NotificationStackScrollLayout.this;
        }
    };

    @Inject
    public NotificationStackScrollLayout(
            @Named(VIEW_CONTEXT) Context context,
            AttributeSet attrs,
            NotificationSectionsManager notificationSectionsManager,
            GroupMembershipManager groupMembershipManager,
            GroupExpansionManager groupExpansionManager,
            AmbientState ambientState,
            FeatureFlags featureFlags,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController) {
        super(context, attrs, 0, 0);
        Resources res = getResources();
        mSectionsManager = notificationSectionsManager;
        mFeatureFlags = featureFlags;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        updateSplitNotificationShade();
        mSectionsManager.initialize(this, LayoutInflater.from(context));
        mSections = mSectionsManager.createSectionsForBuckets();

        mAmbientState = ambientState;
        mBgColor = Utils.getColorAttr(mContext, android.R.attr.colorBackgroundFloating)
                .getDefaultColor();
        int minHeight = res.getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = res.getDimensionPixelSize(R.dimen.notification_max_height);
        mExpandHelper = new ExpandHelper(getContext(), mExpandHelperCallback,
                minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setScrollAdapter(mScrollAdapter);

        mStackScrollAlgorithm = createStackScrollAlgorithm(context);
        mShouldDrawNotificationBackground =
                res.getBoolean(R.bool.config_drawNotificationBackground);
        setOutlineProvider(mOutlineProvider);

        boolean willDraw = mShouldDrawNotificationBackground || DEBUG;
        setWillNotDraw(!willDraw);
        mBackgroundPaint.setAntiAlias(true);
        if (DEBUG) {
            mDebugPaint = new Paint();
            mDebugPaint.setColor(0xffff0000);
            mDebugPaint.setStrokeWidth(2);
            mDebugPaint.setStyle(Paint.Style.STROKE);
            mDebugPaint.setTextSize(25f);
        }
        mClearAllEnabled = res.getBoolean(R.bool.config_enableNotificationsClearAll);
        mGroupMembershipManager = groupMembershipManager;
        mGroupExpansionManager = groupExpansionManager;
    }

    void initializeForegroundServiceSection(ForegroundServiceDungeonView fgsSectionView) {
        if (mFgsSectionView != null) {
            return;
        }
        mFgsSectionView = fgsSectionView;
        addView(mFgsSectionView, -1);
    }

    void updateDismissRtlSetting(boolean dismissRtl) {
        mDismissRtl = dismissRtl;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                ((ExpandableNotificationRow) child).setDismissRtl(dismissRtl);
            }
        }
    }

    /**
     * Set the overexpansion of the panel to be applied to the view.
     */
    void setOverExpansion(float margin) {
        mAmbientState.setOverExpansion(margin);
        updateStackPosition();
        requestChildrenUpdate();
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    protected void onFinishInflate() {
        super.onFinishInflate();

        inflateEmptyShadeView();
        inflateFooterView();
    }

    /**
     * @return the height at which we will wake up when pulsing
     */
    public float getWakeUpHeight() {
        ExpandableView firstChild = getFirstChildWithBackground();
        if (firstChild != null) {
            if (mKeyguardBypassEnabledProvider.getBypassEnabled()) {
                return firstChild.getHeadsUpHeightWithoutHeader();
            } else {
                return firstChild.getCollapsedHeight();
            }
        }
        return 0f;
    }

    void reinflateViews() {
        inflateFooterView();
        inflateEmptyShadeView();
        updateFooter();
        mSectionsManager.reinflateViews(LayoutInflater.from(mContext));
    }

    @VisibleForTesting
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void updateFooter() {
        if (mFooterView == null) {
            return;
        }
        // TODO: move this logic to controller, which will invoke updateFooterView directly
        boolean showDismissView = mClearAllEnabled &&
                mController.hasActiveClearableNotifications(ROWS_ALL);
        RemoteInputController remoteInputController = mRemoteInputManager.getController();
        boolean showFooterView = (showDismissView || getVisibleNotificationCount() > 0)
                && mIsCurrentUserSetup  // see: b/193149550
                && mStatusBarState != StatusBarState.KEYGUARD
                && !mUnlockedScreenOffAnimationController.isScreenOffAnimationPlaying()
                && (remoteInputController == null || !remoteInputController.isRemoteInputActive());
        boolean showHistory = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

        updateFooterView(showFooterView, showDismissView, showHistory);
    }

    /**
     * Return whether there are any clearable notifications
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    boolean hasActiveClearableNotifications(@SelectedRows int selection) {
        return mController.hasActiveClearableNotifications(selection);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public NotificationSwipeActionHelper getSwipeActionHelper() {
        return mSwipeHelper;
    }

    void updateBgColor() {
        mBgColor = Utils.getColorAttr(mContext, android.R.attr.colorBackgroundFloating)
                .getDefaultColor();
        updateBackgroundDimming();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ActivatableNotificationView) {
                ((ActivatableNotificationView) child).updateBackgroundColors();
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.DECORATOR)
    protected void onDraw(Canvas canvas) {
        if (mShouldDrawNotificationBackground
                && (mSections[0].getCurrentBounds().top
                < mSections[mSections.length - 1].getCurrentBounds().bottom
                || mAmbientState.isDozing())) {
            drawBackground(canvas);
        } else if (mInHeadsUpPinnedMode || mHeadsUpAnimatingAway) {
            drawHeadsUpBackground(canvas);
        }

        if (DEBUG) {
            int y = mTopPadding;
            mDebugPaint.setColor(Color.RED);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);

            y = getLayoutHeight();
            mDebugPaint.setColor(Color.YELLOW);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);

            y = getHeight() - getEmptyBottomMargin();
            mDebugPaint.setColor(Color.GREEN);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);

            y = (int) (mAmbientState.getStackY());
            mDebugPaint.setColor(Color.CYAN);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);

            y = (int) (mAmbientState.getStackY() + mAmbientState.getStackHeight());
            mDebugPaint.setColor(Color.BLUE);
            canvas.drawLine(0, y, getWidth(), y, mDebugPaint);
        }
    }

    @ShadeViewRefactor(RefactorComponent.DECORATOR)
    private void drawBackground(Canvas canvas) {
        int lockScreenLeft = mSidePaddings;
        int lockScreenRight = getWidth() - mSidePaddings;
        int lockScreenTop = mSections[0].getCurrentBounds().top;
        int lockScreenBottom = mSections[mSections.length - 1].getCurrentBounds().bottom;
        int hiddenLeft = getWidth() / 2;
        int hiddenTop = mTopPadding;

        float yProgress = 1 - mInterpolatedHideAmount;
        float xProgress = mHideXInterpolator.getInterpolation(
                (1 - mLinearHideAmount) * mBackgroundXFactor);

        int left = (int) MathUtils.lerp(hiddenLeft, lockScreenLeft, xProgress);
        int right = (int) MathUtils.lerp(hiddenLeft, lockScreenRight, xProgress);
        int top = (int) MathUtils.lerp(hiddenTop, lockScreenTop, yProgress);
        int bottom = (int) MathUtils.lerp(hiddenTop, lockScreenBottom, yProgress);
        mBackgroundAnimationRect.set(
                left,
                top,
                right,
                bottom);

        int backgroundTopAnimationOffset = top - lockScreenTop;
        // TODO(kprevas): this may not be necessary any more since we don't display the shelf in AOD
        boolean anySectionHasVisibleChild = false;
        for (NotificationSection section : mSections) {
            if (section.needsBackground()) {
                anySectionHasVisibleChild = true;
                break;
            }
        }
        boolean shouldDrawBackground;
        if (mKeyguardBypassEnabledProvider.getBypassEnabled() && onKeyguard()) {
            shouldDrawBackground = isPulseExpanding();
        } else {
            shouldDrawBackground = !mAmbientState.isDozing() || anySectionHasVisibleChild;
        }
        if (shouldDrawBackground) {
            drawBackgroundRects(canvas, left, right, top, backgroundTopAnimationOffset);
        }

        updateClipping();
    }

    /**
     * Draws round rects for each background section.
     *
     * We want to draw a round rect for each background section as defined by {@link #mSections}.
     * However, if two sections are directly adjacent with no gap between them (e.g. on the
     * lockscreen where the shelf can appear directly below the high priority section, or while
     * scrolling the shade so that the top of the shelf is right at the bottom of the high priority
     * section), we don't want to round the adjacent corners.
     *
     * Since {@link Canvas} doesn't provide a way to draw a half-rounded rect, this means that we
     * need to coalesce the backgrounds for adjacent sections and draw them as a single round rect.
     * This method tracks the top of each rect we need to draw, then iterates through the visible
     * sections.  If a section is not adjacent to the previous section, we draw the previous rect
     * behind the sections we've accumulated up to that point, then start a new rect at the top of
     * the current section.  When we're done iterating we will always have one rect left to draw.
     */
    private void drawBackgroundRects(Canvas canvas, int left, int right, int top,
            int animationYOffset) {
        int backgroundRectTop = top;
        int lastSectionBottom =
                mSections[0].getCurrentBounds().bottom + animationYOffset;
        int currentLeft = left;
        int currentRight = right;
        boolean first = true;
        for (NotificationSection section : mSections) {
            if (!section.needsBackground()) {
                continue;
            }
            int sectionTop = section.getCurrentBounds().top + animationYOffset;
            int ownLeft = Math.min(Math.max(left, section.getCurrentBounds().left), right);
            int ownRight = Math.max(Math.min(right, section.getCurrentBounds().right), ownLeft);
            // If sections are directly adjacent to each other, we don't want to draw them
            // as separate roundrects, as the rounded corners right next to each other look
            // bad.
            if (sectionTop - lastSectionBottom > DISTANCE_BETWEEN_ADJACENT_SECTIONS_PX
                    || ((currentLeft != ownLeft || currentRight != ownRight) && !first)) {
                canvas.drawRoundRect(currentLeft,
                        backgroundRectTop,
                        currentRight,
                        lastSectionBottom,
                        mCornerRadius, mCornerRadius, mBackgroundPaint);
                backgroundRectTop = sectionTop;
            }
            currentLeft = ownLeft;
            currentRight = ownRight;
            lastSectionBottom =
                    section.getCurrentBounds().bottom + animationYOffset;
            first = false;
        }
        canvas.drawRoundRect(currentLeft,
                backgroundRectTop,
                currentRight,
                lastSectionBottom,
                mCornerRadius, mCornerRadius, mBackgroundPaint);
    }

    private void drawHeadsUpBackground(Canvas canvas) {
        int left = mSidePaddings;
        int right = getWidth() - mSidePaddings;

        float top = getHeight();
        float bottom = 0;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE
                    && child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if ((row.isPinned() || row.isHeadsUpAnimatingAway()) && row.getTranslation() < 0
                        && row.getProvider().shouldShowGutsOnSnapOpen()) {
                    top = Math.min(top, row.getTranslationY());
                    bottom = Math.max(bottom, row.getTranslationY() + row.getActualHeight());
                }
            }
        }

        if (top < bottom) {
            canvas.drawRoundRect(
                    left, top, right, bottom,
                    mCornerRadius, mCornerRadius, mBackgroundPaint);
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void updateBackgroundDimming() {
        // No need to update the background color if it's not being drawn.
        if (!mShouldDrawNotificationBackground) {
            return;
        }
        // Interpolate between semi-transparent notification panel background color
        // and white AOD separator.
        float colorInterpolation = MathUtils.smoothStep(0.4f /* start */, 1f /* end */,
                mLinearHideAmount);
        int color = ColorUtils.blendARGB(mBgColor, Color.WHITE, colorInterpolation);

        if (mCachedBackgroundColor != color) {
            mCachedBackgroundColor = color;
            mBackgroundPaint.setColor(color);
            invalidate();
        }
    }

    private void reinitView() {
        initView(getContext(), mKeyguardBypassEnabledProvider, mSwipeHelper);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void initView(Context context,
            KeyguardBypassEnabledProvider keyguardBypassEnabledProvider,
            NotificationSwipeHelper swipeHelper) {
        mScroller = new OverScroller(getContext());
        mKeyguardBypassEnabledProvider = keyguardBypassEnabledProvider;
        mSwipeHelper = swipeHelper;

        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setClipChildren(false);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverflingDistance = configuration.getScaledOverflingDistance();

        Resources res = context.getResources();
        mCollapsedSize = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mGapHeight = res.getDimensionPixelSize(R.dimen.notification_section_divider_height);
        mStackScrollAlgorithm.initView(context);
        mAmbientState.reload(context);
        mPaddingBetweenElements = Math.max(1,
                res.getDimensionPixelSize(R.dimen.notification_divider_height));
        mMinTopOverScrollToEscape = res.getDimensionPixelSize(
                R.dimen.min_top_overscroll_to_qs);
        mStatusBarHeight = res.getDimensionPixelSize(R.dimen.status_bar_height);
        mBottomMargin = res.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom);
        mMinimumPaddings = res.getDimensionPixelSize(R.dimen.notification_side_paddings);
        mQsTilePadding = res.getDimensionPixelOffset(R.dimen.qs_tile_margin_horizontal);
        mSkinnyNotifsInLandscape = res.getBoolean(R.bool.config_skinnyNotifsInLandscape);
        mSidePaddings = mMinimumPaddings;  // Updated in onMeasure by updateSidePadding()
        mMinInteractionHeight = res.getDimensionPixelSize(
                R.dimen.notification_min_interaction_height);
        mCornerRadius = res.getDimensionPixelSize(R.dimen.notification_corner_radius);
        mHeadsUpInset = mStatusBarHeight + res.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mQsScrollBoundaryPosition = res.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height);
    }

    void updateSidePadding(int viewWidth) {
        if (viewWidth == 0 || !mSkinnyNotifsInLandscape) {
            mSidePaddings = mMinimumPaddings;
            return;
        }
        // Portrait is easy, just use the dimen for paddings
        if (RotationUtils.getRotation(mContext) == RotationUtils.ROTATION_NONE) {
            mSidePaddings = mMinimumPaddings;
            return;
        }
        final int innerWidth = viewWidth - mMinimumPaddings * 2;
        final int qsTileWidth = (innerWidth - mQsTilePadding * 3) / 4;
        mSidePaddings = mMinimumPaddings + qsTileWidth + mQsTilePadding;
    }

    void updateCornerRadius() {
        int newRadius = getResources().getDimensionPixelSize(R.dimen.notification_corner_radius);
        if (mCornerRadius != newRadius) {
            mCornerRadius = newRadius;
            invalidate();
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void notifyHeightChangeListener(ExpandableView view) {
        notifyHeightChangeListener(view, false /* needsAnimation */);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void notifyHeightChangeListener(ExpandableView view, boolean needsAnimation) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(view, needsAnimation);
        }
    }

    public boolean isPulseExpanding() {
        return mAmbientState.isPulseExpanding();
    }

    public int getSpeedBumpIndex() {
        if (mSpeedBumpIndexDirty) {
            mSpeedBumpIndexDirty = false;
            int speedBumpIndex = 0;
            int currentIndex = 0;
            final int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View view = getChildAt(i);
                if (view.getVisibility() == View.GONE
                        || !(view instanceof ExpandableNotificationRow)) {
                    continue;
                }
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                currentIndex++;
                boolean beforeSpeedBump;
                if (mHighPriorityBeforeSpeedBump) {
                    beforeSpeedBump = row.getEntry().getBucket() < BUCKET_SILENT;
                } else {
                    beforeSpeedBump = !row.getEntry().isAmbient();
                }
                if (beforeSpeedBump) {
                    speedBumpIndex = currentIndex;
                }
            }

            mSpeedBumpIndex = speedBumpIndex;
        }
        return mSpeedBumpIndex;
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        updateSidePadding(width);
        int childWidthSpec = MeasureSpec.makeMeasureSpec(width - mSidePaddings * 2,
                MeasureSpec.getMode(widthMeasureSpec));
        // Don't constrain the height of the children so we know how big they'd like to be
        int childHeightSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec),
                MeasureSpec.UNSPECIFIED);

        // We need to measure all children even the GONE ones, such that the heights are calculated
        // correctly as they are used to calculate how many we can fit on the screen.
        final int size = getChildCount();
        for (int i = 0; i < size; i++) {
            measureChild(getChildAt(i), childWidthSpec, childHeightSpec);
        }
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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
        updateOwnTranslationZ();

        // Once the layout has finished, we don't need to animate any scrolling clampings anymore.
        mAnimateStackYForContentHeightChange = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void requestAnimationOnViewResize(ExpandableNotificationRow row) {
        if (mAnimationsEnabled && (mIsExpanded || row != null && row.isPinned())) {
            mNeedViewResizeAnimation = true;
            mNeedsAnimation = true;
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void setChildLocationsChangedListener(
            NotificationLogger.OnChildLocationsChangedListener listener) {
        mListener = listener;
    }

    @ShadeViewRefactor(RefactorComponent.LAYOUT_ALGORITHM)
    private void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
        updateAlgorithmHeightAndPadding();
    }

    @ShadeViewRefactor(RefactorComponent.LAYOUT_ALGORITHM)
    private void updateAlgorithmHeightAndPadding() {
        mAmbientState.setLayoutHeight(getLayoutHeight());
        updateAlgorithmLayoutMinHeight();
        mAmbientState.setTopPadding(mTopPadding);
    }

    @ShadeViewRefactor(RefactorComponent.LAYOUT_ALGORITHM)
    private void updateAlgorithmLayoutMinHeight() {
        mAmbientState.setLayoutMinHeight(mQsExpanded || isHeadsUpTransition()
                ? getLayoutMinHeight() : 0);
    }

    /**
     * Updates the children views according to the stack scroll algorithm. Call this whenever
     * modifications to {@link #mOwnScrollY} are performed to reflect it in the view layout.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateChildren() {
        updateScrollStateForAddedChildren();
        mAmbientState.setCurrentScrollVelocity(mScroller.isFinished()
                ? 0
                : mScroller.getCurrVelocity());
        mStackScrollAlgorithm.resetViewStates(mAmbientState, getSpeedBumpIndex());
        if (!isCurrentlyAnimating() && !mNeedsAnimation) {
            applyCurrentState();
        } else {
            startAnimationToState();
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void onPreDrawDuringAnimation() {
        mShelf.updateAppearance();
        if (!mNeedsAnimation && !mChildrenUpdateRequested) {
            updateBackground();
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateScrollStateForAddedChildren() {
        if (mChildrenToAddAnimated.isEmpty()) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (mChildrenToAddAnimated.contains(child)) {
                final int startingPosition = getPositionInLinearLayout(child);
                final int childHeight = getIntrinsicHeight(child) + mPaddingBetweenElements;
                if (startingPosition < mOwnScrollY) {
                    // This child starts off screen, so let's keep it offscreen to keep the
                    // others visible

                    setOwnScrollY(mOwnScrollY + childHeight);
                }
            }
        }
        clampScrollPosition();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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
            // Only apply the scroll if we're scrolling the view upwards, or the view is so
            // far up that it is not visible anymore.
            if (mOwnScrollY < targetScroll || outOfViewScroll < mOwnScrollY) {
                setOwnScrollY(targetScroll);
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void requestChildrenUpdate() {
        if (!mChildrenUpdateRequested) {
            getViewTreeObserver().addOnPreDrawListener(mChildrenUpdater);
            mChildrenUpdateRequested = true;
            invalidate();
        }
    }

    /**
     * Returns best effort count of visible notifications.
     */
    public int getVisibleNotificationCount() {
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != View.GONE && child instanceof ExpandableNotificationRow) {
                count++;
            }
        }
        return count;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private boolean isCurrentlyAnimating() {
        return mStateAnimator.isRunning();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void clampScrollPosition() {
        int scrollRange = getScrollRange();
        if (scrollRange < mOwnScrollY && !mAmbientState.isDismissAllInProgress()) {
            boolean animateStackY = false;
            if (scrollRange < getScrollAmountToScrollBoundary()
                    && mAnimateStackYForContentHeightChange) {
                // if the scroll boundary updates the position of the stack,
                animateStackY = true;
            }
            setOwnScrollY(scrollRange, animateStackY);
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public int getTopPadding() {
        return mTopPadding;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void setTopPadding(int topPadding, boolean animate) {
        if (mTopPadding != topPadding) {
            boolean shouldAnimate = animate || mAnimateNextTopPaddingChange;
            mTopPadding = topPadding;
            updateAlgorithmHeightAndPadding();
            updateContentHeight();
            if (shouldAnimate && mAnimationsEnabled && mIsExpanded) {
                mTopPaddingNeedsAnimation = true;
                mNeedsAnimation = true;
            }
            updateStackPosition();
            requestChildrenUpdate();
            notifyHeightChangeListener(null, shouldAnimate);
            mAnimateNextTopPaddingChange = false;
        }
    }

    /**
     * Apply expansion fraction to the y position and height of the notifications panel.
     */
    private void updateStackPosition() {
        updateStackPosition(false /* listenerNeedsAnimation */);
    }

    /**
     * Apply expansion fraction to the y position and height of the notifications panel.
     * @param listenerNeedsAnimation does the listener need to animate?
     */
    private void updateStackPosition(boolean listenerNeedsAnimation) {
        // Consider interpolating from an mExpansionStartY for use on lockscreen and AOD
        float endTopPosition = mTopPadding + mExtraTopInsetForFullShadeTransition
                + mAmbientState.getOverExpansion()
                - getCurrentOverScrollAmount(false /* top */);
        final float fraction = mAmbientState.getExpansionFraction();
        final float stackY = MathUtils.lerp(0, endTopPosition, fraction);
        mAmbientState.setStackY(stackY);
        if (mOnStackYChanged != null) {
            mOnStackYChanged.accept(listenerNeedsAnimation);
        }
        if (mQsExpansionFraction <= 0) {
            final float stackEndHeight = Math.max(0f,
                    getHeight() - getEmptyBottomMargin() - mTopPadding);
            mAmbientState.setStackEndHeight(stackEndHeight);
            mAmbientState.setStackHeight(
                    MathUtils.lerp(stackEndHeight * StackScrollAlgorithm.START_FRACTION,
                            stackEndHeight, fraction));
        }
    }

    /**
     * Add a listener when the StackY changes. The argument signifies whether an animation is
     * needed.
     */
    void setOnStackYChanged(Consumer<Boolean> onStackYChanged) {
        mOnStackYChanged = onStackYChanged;
    }

    /**
     * Update the height of the panel.
     *
     * @param height the expanded height of the panel
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public void setExpandedHeight(float height) {
        final float shadeBottom = getHeight() - getEmptyBottomMargin();
        final float expansionFraction = MathUtils.saturate(height / shadeBottom);
        mAmbientState.setExpansionFraction(expansionFraction);
        updateStackPosition();

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
        boolean appearing = height < appearEndPosition;
        mAmbientState.setAppearing(appearing);
        if (!appearing) {
            translationY = 0;
            if (mShouldShowShelfOnly) {
                stackHeight = mTopPadding + mShelf.getIntrinsicHeight();
            } else if (mQsExpanded) {
                int stackStartPosition = mContentHeight - mTopPadding + mIntrinsicPadding;
                int stackEndPosition = mMaxTopPadding + mShelf.getIntrinsicHeight();
                if (stackStartPosition <= stackEndPosition) {
                    stackHeight = stackEndPosition;
                } else {
                    if (mShouldUseSplitNotificationShade) {
                        // This prevents notifications from being collapsed when QS is expanded.
                        stackHeight = (int) height;
                    } else {
                        stackHeight = (int) NotificationUtils.interpolate(stackStartPosition,
                                stackEndPosition, mQsExpansionFraction);
                    }
                }
            } else {
                stackHeight = (int) height;
            }
        } else {
            appearFraction = calculateAppearFraction(height);
            if (appearFraction >= 0) {
                translationY = NotificationUtils.interpolate(getExpandTranslationStart(), 0,
                        appearFraction);
            } else {
                // This may happen when pushing up a heads up. We linearly push it up from the
                // start
                translationY = height - appearStartPosition + getExpandTranslationStart();
            }
            stackHeight = (int) (height - translationY);
            if (isHeadsUpTransition()) {
                translationY = MathUtils.lerp(mHeadsUpInset - mTopPadding, 0, appearFraction);
            }
        }
        mAmbientState.setAppearFraction(appearFraction);
        if (stackHeight != mCurrentStackHeight) {
            mCurrentStackHeight = stackHeight;
            updateAlgorithmHeightAndPadding();
            requestChildrenUpdate();
        }
        setStackTranslation(translationY);
        notifyAppearChangedListeners();
    }

    private void notifyAppearChangedListeners() {
        float appear;
        float expandAmount;
        if (mKeyguardBypassEnabledProvider.getBypassEnabled() && onKeyguard()) {
            appear = calculateAppearFractionBypass();
            expandAmount = getPulseHeight();
        } else {
            appear = MathUtils.saturate(calculateAppearFraction(mExpandedHeight));
            expandAmount = mExpandedHeight;
        }
        if (appear != mLastSentAppear || expandAmount != mLastSentExpandedHeight) {
            mLastSentAppear = appear;
            mLastSentExpandedHeight = expandAmount;
            for (int i = 0; i < mExpandedHeightListeners.size(); i++) {
                BiConsumer<Float, Float> listener = mExpandedHeightListeners.get(i);
                listener.accept(expandAmount, appear);
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void setRequestedClipBounds(Rect clipRect) {
        mRequestedClipBounds = clipRect;
        updateClipping();
    }

    /**
     * Return the height of the content ignoring the footer.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public int getIntrinsicContentHeight() {
        return mIntrinsicContentHeight;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void updateClipping() {
        boolean clipped = mRequestedClipBounds != null && !mInHeadsUpPinnedMode
                && !mHeadsUpAnimatingAway;
        boolean clipToOutline = false;
        if (mIsClipped != clipped) {
            mIsClipped = clipped;
        }

        if (mAmbientState.isHiddenAtAll()) {
            clipToOutline = false;
            invalidateOutline();
            if (isFullyHidden()) {
                setClipBounds(null);
            }
        } else if (clipped) {
            setClipBounds(mRequestedClipBounds);
        } else {
            setClipBounds(null);
        }

        setClipToOutline(clipToOutline);
    }

    /**
     * @return The translation at the beginning when expanding.
     * Measured relative to the resting position.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private float getExpandTranslationStart() {
        return -mTopPadding + getMinExpansionHeight() - mShelf.getIntrinsicHeight();
    }

    /**
     * @return the position from where the appear transition starts when expanding.
     * Measured in absolute height.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private float getAppearStartPosition() {
        if (isHeadsUpTransition()) {
            final NotificationSection firstVisibleSection = getFirstVisibleSection();
            final int pinnedHeight = firstVisibleSection != null
                    ? firstVisibleSection.getFirstVisibleChild().getPinnedHeadsUpHeight()
                    : 0;
            return mHeadsUpInset + pinnedHeight;
        }
        return getMinExpansionHeight();
    }

    /**
     * @return the height of the top heads up notification when pinned. This is different from the
     * intrinsic height, which also includes whether the notification is system expanded and
     * is mainly used when dragging down from a heads up notification.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private int getTopHeadsUpPinnedHeight() {
        if (mTopHeadsUpEntry == null) {
            return 0;
        }
        ExpandableNotificationRow row = mTopHeadsUpEntry.getRow();
        if (row.isChildInGroup()) {
            final NotificationEntry groupSummary =
                    mGroupMembershipManager.getGroupSummary(row.getEntry());
            if (groupSummary != null) {
                row = groupSummary.getRow();
            }
        }
        return row.getPinnedHeadsUpHeight();
    }

    /**
     * @return the position from where the appear transition ends when expanding.
     * Measured in absolute height.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private float getAppearEndPosition() {
        int appearPosition = 0;
        int visibleNotifCount = getVisibleNotificationCount();
        if (mEmptyShadeView.getVisibility() == GONE && visibleNotifCount > 0) {
            if (isHeadsUpTransition()
                    || (mInHeadsUpPinnedMode && !mAmbientState.isDozing())) {
                if (mShelf.getVisibility() != GONE && visibleNotifCount > 1) {
                    appearPosition += mShelf.getIntrinsicHeight() + mPaddingBetweenElements;
                }
                appearPosition += getTopHeadsUpPinnedHeight()
                        + getPositionInLinearLayout(mAmbientState.getTrackedHeadsUpRow());
            } else if (mShelf.getVisibility() != GONE) {
                appearPosition += mShelf.getIntrinsicHeight();
            }
        } else {
            appearPosition = mEmptyShadeView.getHeight();
        }
        return appearPosition + (onKeyguard() ? mTopPadding : mIntrinsicPadding);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private boolean isHeadsUpTransition() {
        return mAmbientState.getTrackedHeadsUpRow() != null;
    }

    /**
     * @param height the height of the panel
     * @return the fraction of the appear animation that has been performed
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public float calculateAppearFraction(float height) {
        float appearEndPosition = getAppearEndPosition();
        float appearStartPosition = getAppearStartPosition();
        return (height - appearStartPosition)
                / (appearEndPosition - appearStartPosition);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public float getStackTranslation() {
        return mStackTranslation;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private int getLayoutHeight() {
        return Math.min(mMaxLayoutHeight, mCurrentStackHeight);
    }

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    public void setQsContainer(ViewGroup qsContainer) {
        mQsContainer = qsContainer;
    }

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    public static boolean isPinnedHeadsUp(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            return row.isHeadsUp() && row.isPinned();
        }
        return false;
    }

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    private boolean isHeadsUp(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            return row.isHeadsUp();
        }
        return false;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private ExpandableView getChildAtPosition(float touchX, float touchY) {
        return getChildAtPosition(
                touchX, touchY, true /* requireMinHeight */, true /* ignoreDecors */);
    }

    /**
     * Get the child at a certain screen location.
     *
     * @param touchX           the x coordinate
     * @param touchY           the y coordinate
     * @param requireMinHeight Whether a minimum height is required for a child to be returned.
     * @param ignoreDecors     Whether decors can be returned
     * @return the child at the given location.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    ExpandableView getChildAtPosition(float touchX, float touchY,
            boolean requireMinHeight, boolean ignoreDecors) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView slidingChild = (ExpandableView) getChildAt(childIdx);
            if (slidingChild.getVisibility() != VISIBLE
                    || (ignoreDecors && slidingChild instanceof StackScrollerDecorView)) {
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
                    NotificationEntry entry = row.getEntry();
                    if (!mIsExpanded && row.isHeadsUp() && row.isPinned()
                            && mTopHeadsUpEntry.getRow() != row
                            && mGroupMembershipManager.getGroupSummary(mTopHeadsUpEntry) != entry) {
                        continue;
                    }
                    return row.getViewAtPosition(touchY - childTop);
                }
                return slidingChild;
            }
        }
        return null;
    }

    public ExpandableView getChildAtRawPosition(float touchX, float touchY) {
        getLocationOnScreen(mTempInt2);
        return getChildAtPosition(touchX - mTempInt2[0], touchY - mTempInt2[1]);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setScrollingEnabled(boolean enable) {
        mScrollingEnabled = enable;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void lockScrollTo(View v) {
        if (mForcedScroll == v) {
            return;
        }
        mForcedScroll = v;
        scrollTo(v);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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
     * the IME.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private int targetScrollForView(ExpandableView v, int positionInLinearLayout) {
        return positionInLinearLayout + v.getIntrinsicHeight() +
                getImeInset() - getHeight()
                + ((!isExpanded() && isPinnedHeadsUp(v)) ? mHeadsUpInset : getTopPadding());
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mBottomInset = insets.getSystemWindowInsetBottom();

        mWaterfallTopInset = 0;
        final DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout != null) {
            mWaterfallTopInset = cutout.getWaterfallInsets().top;
        }

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

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setExpandingEnabled(boolean enable) {
        mExpandHelper.setEnabled(enable);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private boolean isScrollingEnabled() {
        return mScrollingEnabled;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    boolean onKeyguard() {
        return mStatusBarState == StatusBarState.KEYGUARD;
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Resources res = getResources();
        updateSplitNotificationShade();
        mStatusBarHeight = res.getDimensionPixelOffset(R.dimen.status_bar_height);
        float densityScale = res.getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
        reinitView();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void dismissViewAnimated(View child, Runnable endRunnable, int delay, long duration) {
        if (child instanceof SectionHeaderView) {
             ((StackScrollerDecorView) child).setContentVisible(
                     false /* visible */, true /* animate */, endRunnable);
             return;
        }
        mSwipeHelper.dismissChild(child, 0, endRunnable, delay, true, duration,
                true /* isDismissAll */);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void snapViewIfNeeded(NotificationEntry entry) {
        ExpandableNotificationRow child = entry.getRow();
        boolean animate = mIsExpanded || isPinnedHeadsUp(child);
        // If the child is showing the notification menu snap to that
        if (child.getProvider() != null) {
            float targetLeft = child.getProvider().isMenuVisible() ? child.getTranslation() : 0;
            mSwipeHelper.snapChildIfNeeded(child, animate, targetLeft);
        }
    }

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    public ViewGroup getViewParentForNotification(NotificationEntry entry) {
        return this;
    }

    /**
     * Perform a scroll upwards and adapt the overscroll amounts accordingly
     *
     * @param deltaY The amount to scroll upwards, has to be positive.
     * @return The amount of scrolling to be performed by the scroller,
     * not handled by the overScroll amount.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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
                // We overScroll on the bottom
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
     * not handled by the overScroll amount.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setFinishScrollingCallback(Runnable runnable) {
        mFinishScrollingCallback = runnable;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void animateScroll() {
        if (mScroller.computeScrollOffset()) {
            int oldY = mOwnScrollY;
            int y = mScroller.getCurrY();

            if (oldY != y) {
                int range = getScrollRange();
                if (y < 0 && oldY >= 0 || y > range && oldY <= range) {
                    // This frame takes us into overscroll, so set the max overscroll based on
                    // the current velocity
                    setMaxOverScrollFromCurrentVelocity();
                }

                if (mDontClampNextScroll) {
                    range = Math.max(range, oldY);
                }
                customOverScrollBy(y - oldY, oldY, range,
                        (int) (mMaxOverScroll));
            }
            postOnAnimation(mReflingAndAnimateScroll);
        } else {
            mDontClampNextScroll = false;
            if (mFinishScrollingCallback != null) {
                mFinishScrollingCallback.run();
            }
        }
    }

    private void setMaxOverScrollFromCurrentVelocity() {
        float currVelocity = mScroller.getCurrVelocity();
        if (currVelocity >= mMinimumVelocity) {
            mMaxOverScroll = Math.abs(currVelocity) / 1000 * mOverflingDistance;
        }
    }

    /**
     * Scrolls by the given delta, overscrolling if needed.  If called during a fling and the delta
     * would cause us to exceed the provided maximum overscroll, springs back instead.
     *
     * This method performs the determination of whether we're exceeding the overscroll and clamps
     * the scroll amount if so.  The actual scrolling/overscrolling happens in
     * {@link #onCustomOverScrolled(int, boolean)}
     * @param deltaY         The (signed) number of pixels to scroll.
     * @param scrollY        The current scroll position (absolute scrolling only).
     * @param scrollRangeY   The maximum allowable scroll position (absolute scrolling only).
     * @param maxOverScrollY The current (unsigned) limit on number of pixels to overscroll by.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void customOverScrollBy(int deltaY, int scrollY, int scrollRangeY, int maxOverScrollY) {
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
    }

    /**
     * Set the amount of overScrolled pixels which will force the view to apply a rubber-banded
     * overscroll effect based on numPixels. By default this will also cancel animations on the
     * same overScroll edge.
     *
     * @param numPixels The amount of pixels to overScroll by. These will be scaled according to
     *                  the rubber-banding logic.
     * @param onTop     Should the effect be applied on top of the scroller.
     * @param animate   Should an animation be performed.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void setOverScrolledPixels(float numPixels, boolean onTop, boolean animate) {
        setOverScrollAmount(numPixels * getRubberBandFactor(onTop), onTop, animate, true);
    }

    /**
     * Set the effective overScroll amount which will be directly reflected in the layout.
     * By default this will also cancel animations on the same overScroll edge.
     *
     * @param amount  The amount to overScroll by.
     * @param onTop   Should the effect be applied on top of the scroller.
     * @param animate Should an animation be performed.
     */

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void setOverScrollAmount(float amount, boolean onTop, boolean animate) {
        setOverScrollAmount(amount, onTop, animate, true);
    }

    /**
     * Set the effective overScroll amount which will be directly reflected in the layout.
     *
     * @param amount          The amount to overScroll by.
     * @param onTop           Should the effect be applied on top of the scroller.
     * @param animate         Should an animation be performed.
     * @param cancelAnimators Should running animations be cancelled.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators) {
        setOverScrollAmount(amount, onTop, animate, cancelAnimators, isRubberbanded(onTop));
    }

    /**
     * Set the effective overScroll amount which will be directly reflected in the layout.
     *
     * @param amount          The amount to overScroll by.
     * @param onTop           Should the effect be applied on top of the scroller.
     * @param animate         Should an animation be performed.
     * @param cancelAnimators Should running animations be cancelled.
     * @param isRubberbanded  The value which will be passed to
     *                        {@link OnOverscrollTopChangedListener#onOverscrollTopChanged}
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators, boolean isRubberbanded) {
        if (cancelAnimators) {
            mStateAnimator.cancelOverScrollAnimators(onTop);
        }
        setOverScrollAmountInternal(amount, onTop, animate, isRubberbanded);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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
            updateStackPosition();
            requestChildrenUpdate();
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public void setOverscrollTopChangedListener(
            OnOverscrollTopChangedListener overscrollTopChangedListener) {
        mOverscrollTopChangedListener = overscrollTopChangedListener;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public float getCurrentOverScrollAmount(boolean top) {
        return mAmbientState.getOverScrollAmount(top);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public float getCurrentOverScrolledPixels(boolean top) {
        return top ? mOverScrolledTopPixels : mOverScrolledBottomPixels;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void setOverScrolledPixels(float amount, boolean onTop) {
        if (onTop) {
            mOverScrolledTopPixels = amount;
        } else {
            mOverScrolledBottomPixels = amount;
        }
    }

    /**
     * Scrolls to the given position, overscrolling if needed.  If called during a fling and the
     * position exceeds the provided maximum overscroll, springs back instead.
     *
     * @param scrollY The target scroll position.
     * @param clampedY Whether this value was clamped by the calling method, meaning we've reached
     *                 the overscroll limit.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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

    /**
     * Springs back from an overscroll by stopping the {@link #mScroller} and animating the
     * overscroll amount back to zero.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private int getScrollRange() {
        // In current design, it only use the top HUN to treat all of HUNs
        // although there are more than one HUNs
        int contentHeight = mContentHeight;
        if (!isExpanded() && mInHeadsUpPinnedMode) {
            contentHeight = mHeadsUpInset + getTopHeadsUpPinnedHeight();
        }
        int scrollRange = Math.max(0, contentHeight - mMaxLayoutHeight);
        int imeInset = getImeInset();
        scrollRange += Math.min(imeInset, Math.max(0, contentHeight - (getHeight() - imeInset)));
        if (scrollRange > 0) {
            scrollRange = Math.max(getScrollAmountToScrollBoundary(), scrollRange);
        }
        return scrollRange;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private int getImeInset() {
        return Math.max(0, mBottomInset - (getRootView().getHeight() - getHeight()));
    }

    /**
     * @return the first child which has visibility unequal to GONE
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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
     * @return The first child which has visibility unequal to GONE which is currently below the
     * given translationY or equal to it.
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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
                            row.getAttachedChildren();
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
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public ExpandableView getLastChildNotGone() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE && child != mShelf) {
                return (ExpandableView) child;
            }
        }
        return null;
    }

    private ExpandableNotificationRow getLastRowNotGone() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child instanceof ExpandableNotificationRow && child.getVisibility() != View.GONE) {
                return (ExpandableNotificationRow) child;
            }
        }
        return null;
    }

    /**
     * @return the number of children which have visibility unequal to GONE
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateContentHeight() {
        final float scrimTopPadding = mAmbientState.isOnKeyguard() ? 0 : mMinimumPaddings;
        int height = (int) scrimTopPadding;
        float previousPaddingRequest = mPaddingBetweenElements;
        int numShownItems = 0;
        int numShownNotifs = 0;
        boolean finish = false;
        int maxDisplayedNotifications = mMaxDisplayedNotifications;
        ExpandableView previousView = null;

        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView expandableView = (ExpandableView) getChildAt(i);
            boolean footerViewOnLockScreen = expandableView == mFooterView && onKeyguard();

            if (expandableView.getVisibility() != View.GONE
                    && !expandableView.hasNoContentHeight() && !footerViewOnLockScreen) {

                boolean limitReached = maxDisplayedNotifications != -1
                        && numShownNotifs >= maxDisplayedNotifications;
                final float viewHeight;
                if (limitReached) {
                    viewHeight = mShelf.getIntrinsicHeight();
                    finish = true;
                } else {
                    viewHeight = expandableView.getIntrinsicHeight();
                }
                if (height != 0) {
                    height += mPaddingBetweenElements;
                }
                float gapHeight = calculateGapHeight(previousView, expandableView, numShownNotifs);
                height += gapHeight;
                height += viewHeight;

                numShownItems++;
                if (viewHeight > 0 || !(expandableView instanceof MediaHeaderView)) {
                    // Only count the media as a notification if it has a positive height.
                    numShownNotifs++;
                }
                previousView = expandableView;
                if (finish) {
                    break;
                }
            }
        }
        mIntrinsicContentHeight = height;

        // The topPadding can be bigger than the regular padding when qs is expanded, in that
        // state the maxPanelHeight and the contentHeight should be bigger
        mContentHeight = height + Math.max(mIntrinsicPadding, mTopPadding) + mBottomMargin;
        updateScrollability();
        clampScrollPosition();
        updateStackPosition();
        mAmbientState.setContentHeight(mContentHeight);
    }

    /**
     * Calculate the gap height between two different views
     *
     * @param previous the previousView
     * @param current the currentView
     * @param visibleIndex the visible index in the list
     *
     * @return the gap height needed before the current view
     */
    public float calculateGapHeight(
            ExpandableView previous,
            ExpandableView current,
            int visibleIndex
    ) {
       return mStackScrollAlgorithm.getGapHeightForChild(mSectionsManager, visibleIndex, current,
                previous);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean hasPulsingNotifications() {
        return mPulsing;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void updateScrollability() {
        boolean scrollable = !mQsExpanded && getScrollRange() > 0;
        if (scrollable != mScrollable) {
            mScrollable = scrollable;
            setFocusable(scrollable);
            updateForwardAndBackwardScrollability();
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void updateForwardAndBackwardScrollability() {
        boolean forwardScrollable = mScrollable && !mScrollAdapter.isScrolledToBottom();
        boolean backwardsScrollable = mScrollable && !mScrollAdapter.isScrolledToTop();
        boolean changed = forwardScrollable != mForwardScrollable
                || backwardsScrollable != mBackwardScrollable;
        mForwardScrollable = forwardScrollable;
        mBackwardScrollable = backwardsScrollable;
        if (changed) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void updateBackground() {
        // No need to update the background color if it's not being drawn.
        if (!mShouldDrawNotificationBackground) {
            return;
        }

        updateBackgroundBounds();
        if (didSectionBoundsChange()) {
            boolean animate = mAnimateNextSectionBoundsChange || mAnimateNextBackgroundTop
                    || mAnimateNextBackgroundBottom || areSectionBoundsAnimating();
            if (!isExpanded()) {
                abortBackgroundAnimators();
                animate = false;
            }
            if (animate) {
                startBackgroundAnimation();
            } else {
                for (NotificationSection section : mSections) {
                    section.resetCurrentBounds();
                }
                invalidate();
            }
        } else {
            abortBackgroundAnimators();
        }
        mAnimateNextBackgroundTop = false;
        mAnimateNextBackgroundBottom = false;
        mAnimateNextSectionBoundsChange = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void abortBackgroundAnimators() {
        for (NotificationSection section : mSections) {
            section.cancelAnimators();
        }
    }

    private boolean didSectionBoundsChange() {
        for (NotificationSection section : mSections) {
            if (section.didBoundsChange()) {
                return true;
            }
        }
        return false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private boolean areSectionBoundsAnimating() {
        for (NotificationSection section : mSections) {
            if (section.areBoundsAnimating()) {
                return true;
            }
        }
        return false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void startBackgroundAnimation() {
        // TODO(kprevas): do we still need separate fields for top/bottom?
        // or can each section manage its own animation state?
        NotificationSection firstVisibleSection = getFirstVisibleSection();
        NotificationSection lastVisibleSection = getLastVisibleSection();
        for (NotificationSection section : mSections) {
            section.startBackgroundAnimation(
                    section == firstVisibleSection
                            ? mAnimateNextBackgroundTop
                            : mAnimateNextSectionBoundsChange,
                    section == lastVisibleSection
                            ? mAnimateNextBackgroundBottom
                            : mAnimateNextSectionBoundsChange);
        }
    }

    /**
     * Update the background bounds to the new desired bounds
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void updateBackgroundBounds() {
        int left = mSidePaddings;
        int right = getWidth() - mSidePaddings;
        for (NotificationSection section : mSections) {
            section.getBounds().left = left;
            section.getBounds().right = right;
        }

        if (!mIsExpanded) {
            for (NotificationSection section : mSections) {
                section.getBounds().top = 0;
                section.getBounds().bottom = 0;
            }
            return;
        }
        int minTopPosition;
        NotificationSection lastSection = getLastVisibleSection();
        boolean onKeyguard = mStatusBarState == StatusBarState.KEYGUARD;
        if (!onKeyguard) {
            minTopPosition = (int) (mTopPadding + mStackTranslation);
        } else if (lastSection == null) {
            minTopPosition = mTopPadding;
        } else {
            // The first sections could be empty while there could still be elements in later
            // sections. The position of these first few sections is determined by the position of
            // the first visible section.
            NotificationSection firstVisibleSection = getFirstVisibleSection();
            firstVisibleSection.updateBounds(0 /* minTopPosition*/, 0 /* minBottomPosition */,
                    false /* shiftPulsingWithFirst */);
            minTopPosition = firstVisibleSection.getBounds().top;
        }
        boolean shiftPulsingWithFirst = mNumHeadsUp <= 1
                && (mAmbientState.isDozing()
                        || (mKeyguardBypassEnabledProvider.getBypassEnabled() && onKeyguard));
        for (NotificationSection section : mSections) {
            int minBottomPosition = minTopPosition;
            if (section == lastSection) {
                // We need to make sure the section goes all the way to the shelf
                minBottomPosition = (int) (ViewState.getFinalTranslationY(mShelf)
                        + mShelf.getIntrinsicHeight());
            }
            minTopPosition = section.updateBounds(minTopPosition, minBottomPosition,
                    shiftPulsingWithFirst);
            shiftPulsingWithFirst = false;
        }
    }

    private NotificationSection getFirstVisibleSection() {
        for (NotificationSection section : mSections) {
            if (section.getFirstVisibleChild() != null) {
                return section;
            }
        }
        return null;
    }

    private NotificationSection getLastVisibleSection() {
        for (int i = mSections.length - 1; i >= 0; i--) {
            NotificationSection section = mSections[i];
            if (section.getLastVisibleChild() != null) {
                return section;
            }
        }
        return null;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private ExpandableView getLastChildWithBackground() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() != View.GONE && !(child instanceof StackScrollerDecorView)
                    && child != mShelf) {
                return child;
            }
        }
        return null;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private ExpandableView getFirstChildWithBackground() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() != View.GONE && !(child instanceof StackScrollerDecorView)
                    && child != mShelf) {
                return child;
            }
        }
        return null;
    }

    //TODO: We shouldn't have to generate this list every time
    private List<ExpandableView> getChildrenWithBackground() {
        ArrayList<ExpandableView> children = new ArrayList<>();
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child.getVisibility() != View.GONE
                    && !(child instanceof StackScrollerDecorView)
                    && child != mShelf) {
                children.add(child);
            }
        }
        return children;
    }

    /**
     * Fling the scroll view
     *
     * @param velocityY The initial velocity in the Y direction. Positive
     *                  numbers mean that the finger/cursor is moving down the screen,
     *                  which means we want to scroll towards the top.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    protected void fling(int velocityY) {
        if (getChildCount() > 0) {
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
                        + bottomAmount;
            } else {
                // it will be set once we reach the boundary
                mMaxOverScroll = 0.0f;
            }
            int scrollRange = getScrollRange();
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
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private boolean shouldOverScrollFling(int initialVelocity) {
        float topOverScroll = getCurrentOverScrollAmount(true);
        return mScrolledToTopOnFirstDown
                && !mExpandedInThisMotion
                && (initialVelocity > mMinimumVelocity
                        || (topOverScroll > mMinTopOverScrollToEscape && initialVelocity > 0));
    }

    /**
     * Updates the top padding of the notifications, taking {@link #getIntrinsicPadding()} into
     * account.
     *
     * @param qsHeight               the top padding imposed by the quick settings panel
     * @param animate                whether to animate the change
     */
    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public void updateTopPadding(float qsHeight, boolean animate) {
        int topPadding = (int) qsHeight;
        int minStackHeight = getLayoutMinHeight();
        if (topPadding + minStackHeight > getHeight()) {
            mTopPaddingOverflow = topPadding + minStackHeight - getHeight();
        } else {
            mTopPaddingOverflow = 0;
        }
        setTopPadding(topPadding, animate && !mKeyguardBypassEnabledProvider.getBypassEnabled());
        setExpandedHeight(mExpandedHeight);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public void setMaxTopPadding(int maxTopPadding) {
        mMaxTopPadding = maxTopPadding;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public int getLayoutMinHeight() {
        if (isHeadsUpTransition()) {
            ExpandableNotificationRow trackedHeadsUpRow = mAmbientState.getTrackedHeadsUpRow();
            if (trackedHeadsUpRow.isAboveShelf()) {
                int hunDistance = (int) MathUtils.lerp(
                        0,
                        getPositionInLinearLayout(trackedHeadsUpRow),
                        mAmbientState.getAppearFraction());
                return getTopHeadsUpPinnedHeight() + hunDistance;
            } else {
                return getTopHeadsUpPinnedHeight();
            }
        }
        return mShelf.getVisibility() == GONE ? 0 : mShelf.getIntrinsicHeight();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public float getTopPaddingOverflow() {
        return mTopPaddingOverflow;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private int clampPadding(int desiredPadding) {
        return Math.max(desiredPadding, mIntrinsicPadding);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private boolean isRubberbanded(boolean onTop) {
        return !onTop || mExpandedInThisMotion || mIsExpansionChanging || mPanelTracking
                || !mScrolledToTopOnFirstDown;
    }



    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setChildTransferInProgress(boolean childTransferInProgress) {
        Assert.isMainThread();
        mChildTransferInProgress = childTransferInProgress;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        // we only call our internal methods if this is actually a removal and not just a
        // notification which becomes a child notification
        if (!mChildTransferInProgress) {
            onViewRemovedInternal((ExpandableView) child, this);
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void cleanUpViewStateForEntry(NotificationEntry entry) {
        View child = entry.getRow();
        if (child == mSwipeHelper.getTranslatingParentView()) {
            mSwipeHelper.clearTranslatingParentView();
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void onViewRemovedInternal(ExpandableView child, ViewGroup container) {
        if (mChangePositionInProgress) {
            // This is only a position change, don't do anything special
            return;
        }
        child.setOnHeightChangedListener(null);
        updateScrollStateForRemovedChild(child);
        boolean animationGenerated = generateRemoveAnimation(child);
        if (animationGenerated) {
            if (!mSwipedOutViews.contains(child) || !isFullySwipedOut(child)) {
                container.addTransientView(child, 0);
                child.setTransientContainer(container);
            }
        } else {
            mSwipedOutViews.remove(child);
        }
        updateAnimationState(false, child);

        focusNextViewIfFocused(child);
    }

    /**
     * Has this view been fully swiped out such that it's not visible anymore.
     */
    public boolean isFullySwipedOut(ExpandableView child) {
        return Math.abs(child.getTranslation()) >= Math.abs(getTotalTranslationLength(child));
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    private boolean isChildInGroup(View child) {
        return child instanceof ExpandableNotificationRow
                && mGroupMembershipManager.isChildInGroup(
                ((ExpandableNotificationRow) child).getEntry());
    }

    /**
     * Generate a remove animation for a child view.
     *
     * @param child The view to generate the remove animation for.
     * @return Whether an animation was generated.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    boolean generateRemoveAnimation(ExpandableView child) {
        String key = "";
        if (DEBUG_REMOVE_ANIMATION) {
            if (child instanceof ExpandableNotificationRow) {
                key = ((ExpandableNotificationRow) child).getEntry().getKey();
            }
            Log.d(TAG, "generateRemoveAnimation " + key);
        }
        if (removeRemovedChildFromHeadsUpChangeAnimations(child)) {
            if (DEBUG_REMOVE_ANIMATION) {
                Log.d(TAG, "removedBecauseOfHeadsUp " + key);
            }
            mAddedHeadsUpChildren.remove(child);
            return false;
        }
        if (isClickedHeadsUp(child)) {
            // An animation is already running, add it transiently
            mClearTransientViewsWhenFinished.add(child);
            return true;
        }
        if (DEBUG_REMOVE_ANIMATION) {
            Log.d(TAG, "generateRemove " + key
                    + "\nmIsExpanded " + mIsExpanded
                    + "\nmAnimationsEnabled " + mAnimationsEnabled
                    + "\n!invisible group " + !isChildInInvisibleGroup(child));
        }
        if (mIsExpanded && mAnimationsEnabled && !isChildInInvisibleGroup(child)) {
            if (!mChildrenToAddAnimated.contains(child)) {
                if (DEBUG_REMOVE_ANIMATION) {
                    Log.d(TAG, "needsAnimation = true " + key);
                }
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

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    private boolean isClickedHeadsUp(View child) {
        return HeadsUpUtil.isClickedHeadsUpNotification(child);
    }

    /**
     * Remove a removed child view from the heads up animations if it was just added there
     *
     * @return whether any child was removed from the list to animate and the view was just added
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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
            ((ExpandableNotificationRow) child).setHeadsUpAnimatingAway(false);
        }
        mTmpList.clear();
        return hasAddEvent && mAddedHeadsUpChildren.contains(child);
    }

    // TODO (b/162832756): remove since this won't happen in new pipeline (we prune groups in
    //  ShadeListBuilder)
    /**
     * @param child the child to query
     * @return whether a view is not a top level child but a child notification and that group is
     * not expanded
     */
    @ShadeViewRefactor(RefactorComponent.ADAPTER)
    private boolean isChildInInvisibleGroup(View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            NotificationEntry groupSummary =
                    mGroupMembershipManager.getGroupSummary(row.getEntry());
            if (groupSummary != null && groupSummary.getRow() != row) {
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
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateScrollStateForRemovedChild(ExpandableView removedChild) {
        final int startingPosition = getPositionInLinearLayout(removedChild);
        final int childHeight = getIntrinsicHeight(removedChild) + mPaddingBetweenElements;
        final int endPosition = startingPosition + childHeight;
        final int scrollBoundaryStart = getScrollAmountToScrollBoundary();
        mAnimateStackYForContentHeightChange = true;
        // This is reset onLayout
        if (endPosition <= mOwnScrollY - scrollBoundaryStart) {
            // This child is fully scrolled of the top, so we have to deduct its height from the
            // scrollPosition
            setOwnScrollY(mOwnScrollY - childHeight);
        } else if (startingPosition < mOwnScrollY - scrollBoundaryStart) {
            // This child is currently being scrolled into, set the scroll position to the
            // start of this child
            setOwnScrollY(startingPosition + scrollBoundaryStart);
        }
    }

    /**
     * @return the amount of scrolling needed to start clipping notifications.
     */
    private int getScrollAmountToScrollBoundary() {
        if (mShouldUseSplitNotificationShade) {
            return mSidePaddings;
        }
        return mTopPadding - mQsScrollBoundaryPosition;
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private int getIntrinsicHeight(View view) {
        if (view instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) view;
            return expandableView.getIntrinsicHeight();
        }
        return view.getHeight();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            boolean notGone = child.getVisibility() != View.GONE;
            if (notGone && !child.hasNoContentHeight()) {
                if (position != 0) {
                    position += mPaddingBetweenElements;
                }
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
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof ExpandableView) {
            onViewAddedInternal((ExpandableView) child);
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateFirstAndLastBackgroundViews() {
        NotificationSection firstSection = getFirstVisibleSection();
        NotificationSection lastSection = getLastVisibleSection();
        ExpandableView previousFirstChild =
                firstSection == null ? null : firstSection.getFirstVisibleChild();
        ExpandableView previousLastChild =
                lastSection == null ? null : lastSection.getLastVisibleChild();

        ExpandableView firstChild = getFirstChildWithBackground();
        ExpandableView lastChild = getLastChildWithBackground();
        boolean sectionViewsChanged = mSectionsManager.updateFirstAndLastViewsForAllSections(
                mSections, getChildrenWithBackground());

        if (mAnimationsEnabled && mIsExpanded) {
            mAnimateNextBackgroundTop = firstChild != previousFirstChild;
            mAnimateNextBackgroundBottom = lastChild != previousLastChild || mAnimateBottomOnLayout;
            mAnimateNextSectionBoundsChange = sectionViewsChanged;
        } else {
            mAnimateNextBackgroundTop = false;
            mAnimateNextBackgroundBottom = false;
            mAnimateNextSectionBoundsChange = false;
        }
        mAmbientState.setLastVisibleBackgroundChild(lastChild);
        // TODO: Refactor SectionManager and put the RoundnessManager there.
        mController.getNoticationRoundessManager().updateRoundedChildren(mSections);
        mAnimateBottomOnLayout = false;
        invalidate();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void onViewAddedInternal(ExpandableView child) {
        updateHideSensitiveForChild(child);
        child.setOnHeightChangedListener(mOnChildHeightChangedListener);
        generateAddAnimation(child, false /* fromMoreCard */);
        updateAnimationState(child);
        updateChronometerForChild(child);
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            row.setDismissRtl(mDismissRtl);
            row.setDismissUsingRowTranslationX(mDismissUsingRowTranslationX);

        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void updateHideSensitiveForChild(ExpandableView child) {
        child.setHideSensitiveForIntrinsicHeight(mAmbientState.isHideSensitive());
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void notifyGroupChildRemoved(ExpandableView row, ViewGroup childrenContainer) {
        onViewRemovedInternal(row, childrenContainer);
    }

    public void notifyGroupChildAdded(ExpandableView row) {
        onViewAddedInternal(row);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void setAnimationsEnabled(boolean animationsEnabled) {
        mAnimationsEnabled = animationsEnabled;
        updateNotificationAnimationStates();
        if (!animationsEnabled) {
            mSwipedOutViews.clear();
            mChildrenToRemoveAnimated.clear();
            clearTemporaryViewsInGroup(this);
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void updateAnimationState(View child) {
        updateAnimationState((mAnimationsEnabled || hasPulsingNotifications())
                && (mIsExpanded || isPinnedHeadsUp(child)), child);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setExpandingNotification(ExpandableNotificationRow row) {
        if (mExpandingNotificationRow != null && row == null) {
            // Let's unset the clip path being set during launch
            mExpandingNotificationRow.setExpandingClipPath(null);
            ExpandableNotificationRow parent = mExpandingNotificationRow.getNotificationParent();
            if (parent != null) {
                parent.setExpandingClipPath(null);
            }
        }
        mExpandingNotificationRow = row;
        updateLaunchedNotificationClipPath();
        requestChildrenUpdate();
    }

    public boolean containsView(View v) {
        return v.getParent() == this;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void applyExpandAnimationParams(ExpandAnimationParameters params) {
        // Modify the clipping for launching notifications
        mLaunchAnimationParams = params;
        setLaunchingNotification(params != null);
        updateLaunchedNotificationClipPath();
        requestChildrenUpdate();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateAnimationState(boolean running, View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            row.setIconAnimationRunning(running);
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    boolean isAddOrRemoveAnimationPending() {
        return mNeedsAnimation
                && (!mChildrenToAddAnimated.isEmpty() || !mChildrenToRemoveAnimated.isEmpty());
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void generateAddAnimation(ExpandableView child, boolean fromMoreCard) {
        if (mIsExpanded && mAnimationsEnabled && !mChangePositionInProgress && !isFullyHidden()) {
            // Generate Animations
            mChildrenToAddAnimated.add(child);
            if (fromMoreCard) {
                mFromMoreCardAdditions.add(child);
            }
            mNeedsAnimation = true;
        }
        if (isHeadsUp(child) && mAnimationsEnabled && !mChangePositionInProgress
                && !isFullyHidden()) {
            mAddedHeadsUpChildren.add(child);
            mChildrenToAddAnimated.remove(child);
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void changeViewPosition(ExpandableView child, int newIndex) {
        Assert.isMainThread();
        if (mChangePositionInProgress) {
            throw new IllegalStateException("Reentrant call to changeViewPosition");
        }

        int currentIndex = indexOfChild(child);

        if (currentIndex == -1) {
            boolean isTransient = false;
            if (child instanceof ExpandableNotificationRow
                    && child.getTransientContainer() != null) {
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
            child.setChangingPosition(true);
            removeView(child);
            addView(child, newIndex);
            child.setChangingPosition(false);
            mChangePositionInProgress = false;
            if (mIsExpanded && mAnimationsEnabled && child.getVisibility() != View.GONE) {
                mChildrenChangingPositions.add(child);
                mNeedsAnimation = true;
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void startAnimationToState() {
        if (mNeedsAnimation) {
            generateAllAnimationEvents();
            mNeedsAnimation = false;
        }
        if (!mAnimationEvents.isEmpty() || isCurrentlyAnimating()) {
            setAnimationRunning(true);
            mStateAnimator.startAnimationForEvents(mAnimationEvents, mGoToFullShadeDelay);
            mAnimationEvents.clear();
            updateBackground();
            updateViewShadows();
        } else {
            applyCurrentState();
        }
        mGoToFullShadeDelay = 0;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateAllAnimationEvents() {
        generateHeadsUpAnimationEvents();
        generateChildRemovalEvents();
        generateChildAdditionEvents();
        generatePositionChangeEvents();
        generateTopPaddingEvent();
        generateActivateEvent();
        generateDimmedEvent();
        generateHideSensitiveEvent();
        generateGoToFullShadeEvent();
        generateViewResizeEvent();
        generateGroupExpansionEvent();
        generateAnimateEverythingEvent();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateHeadsUpAnimationEvents() {
        for (Pair<ExpandableNotificationRow, Boolean> eventPair : mHeadsUpChangeAnimations) {
            ExpandableNotificationRow row = eventPair.first;
            boolean isHeadsUp = eventPair.second;
            if (isHeadsUp != row.isHeadsUp()) {
                // For cases where we have a heads up showing and appearing again we shouldn't
                // do the animations at all.
                continue;
            }
            int type = AnimationEvent.ANIMATION_TYPE_HEADS_UP_OTHER;
            boolean onBottom = false;
            boolean pinnedAndClosed = row.isPinned() && !mIsExpanded;
            boolean performDisappearAnimation = !mIsExpanded
                    // Only animate if we still have pinned heads up, otherwise we just have the
                    // regular collapse animation of the lock screen
                    || (mKeyguardBypassEnabledProvider.getBypassEnabled() && onKeyguard()
                            && mInHeadsUpPinnedMode);
            if (performDisappearAnimation && !isHeadsUp) {
                type = row.wasJustClicked()
                        ? AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                        : AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR;
                if (row.isChildInGroup()) {
                    // We can otherwise get stuck in there if it was just isolated
                    row.setHeadsUpAnimatingAway(false);
                    continue;
                }
            } else {
                ExpandableViewState viewState = row.getViewState();
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

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private boolean shouldHunAppearFromBottom(ExpandableViewState viewState) {
        if (viewState.yTranslation + viewState.height < mAmbientState.getMaxHeadsUpTranslation()) {
            return false;
        }
        return true;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateGroupExpansionEvent() {
        // Generate a group expansion/collapsing event if there is such a group at all
        if (mExpandedGroupView != null) {
            mAnimationEvents.add(new AnimationEvent(mExpandedGroupView,
                    AnimationEvent.ANIMATION_TYPE_GROUP_EXPANSION_CHANGED));
            mExpandedGroupView = null;
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateViewResizeEvent() {
        if (mNeedViewResizeAnimation) {
            boolean hasDisappearAnimation = false;
            for (AnimationEvent animationEvent : mAnimationEvents) {
                final int type = animationEvent.animationType;
                if (type == AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                        || type == AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR) {
                    hasDisappearAnimation = true;
                    break;
                }
            }

            if (!hasDisappearAnimation) {
                mAnimationEvents.add(
                        new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_VIEW_RESIZE));
            }
        }
        mNeedViewResizeAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateChildRemovalEvents() {
        for (ExpandableView child : mChildrenToRemoveAnimated) {
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
                childWasSwipedOut |= isFullySwipedOut(row);
            } else if (child instanceof MediaHeaderView) {
                childWasSwipedOut = true;
            }
            if (!childWasSwipedOut) {
                Rect clipBounds = child.getClipBounds();
                childWasSwipedOut = clipBounds != null && clipBounds.height() == 0;

                if (childWasSwipedOut) {
                    // Clean up any potential transient views if the child has already been swiped
                    // out, as we won't be animating it further (due to its height already being
                    // clipped to 0.
                    ViewGroup transientContainer = child.getTransientContainer();
                    if (transientContainer != null) {
                        transientContainer.removeTransientView(child);
                    }
                }
            }
            int animationType = childWasSwipedOut
                    ? AnimationEvent.ANIMATION_TYPE_REMOVE_SWIPED_OUT
                    : AnimationEvent.ANIMATION_TYPE_REMOVE;
            AnimationEvent event = new AnimationEvent(child, animationType);
            event.viewAfterChangingView = getFirstChildBelowTranlsationY(removedTranslation,
                    ignoreChildren);
            mAnimationEvents.add(event);
            mSwipedOutViews.remove(child);
            if (DEBUG_REMOVE_ANIMATION) {
                String key = "";
                if (child instanceof ExpandableNotificationRow) {
                    key = ((ExpandableNotificationRow) child).getEntry().getKey();
                }
                Log.d(TAG, "created Remove Event - SwipedOut: " + childWasSwipedOut + " " + key);
            }
        }
        mChildrenToRemoveAnimated.clear();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generatePositionChangeEvents() {
        for (ExpandableView child : mChildrenChangingPositions) {
            Integer duration = null;
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (row.getEntry().isMarkedForUserTriggeredMovement()) {
                    duration = StackStateAnimator.ANIMATION_DURATION_PRIORITY_CHANGE;
                    row.getEntry().markForUserTriggeredMovement(false);
                }
            }
            AnimationEvent animEvent = duration == null
                    ? new AnimationEvent(child, AnimationEvent.ANIMATION_TYPE_CHANGE_POSITION)
                    : new AnimationEvent(
                            child, AnimationEvent.ANIMATION_TYPE_CHANGE_POSITION, duration);
            mAnimationEvents.add(animEvent);
        }
        mChildrenChangingPositions.clear();
        if (mGenerateChildOrderChangedEvent) {
            mAnimationEvents.add(new AnimationEvent(null,
                    AnimationEvent.ANIMATION_TYPE_CHANGE_POSITION));
            mGenerateChildOrderChangedEvent = false;
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateChildAdditionEvents() {
        for (ExpandableView child : mChildrenToAddAnimated) {
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

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateTopPaddingEvent() {
        if (mTopPaddingNeedsAnimation) {
            AnimationEvent event;
            if (mAmbientState.isDozing()) {
                event = new AnimationEvent(null /* view */,
                        AnimationEvent.ANIMATION_TYPE_TOP_PADDING_CHANGED,
                        KeyguardSliceView.DEFAULT_ANIM_DURATION);
            } else {
                event = new AnimationEvent(null /* view */,
                        AnimationEvent.ANIMATION_TYPE_TOP_PADDING_CHANGED);
            }
            mAnimationEvents.add(event);
        }
        mTopPaddingNeedsAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateActivateEvent() {
        if (mActivateNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_ACTIVATED_CHILD));
        }
        mActivateNeedsAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateAnimateEverythingEvent() {
        if (mEverythingNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_EVERYTHING));
        }
        mEverythingNeedsAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateDimmedEvent() {
        if (mDimmedNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_DIMMED));
        }
        mDimmedNeedsAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateHideSensitiveEvent() {
        if (mHideSensitiveNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_HIDE_SENSITIVE));
        }
        mHideSensitiveNeedsAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void generateGoToFullShadeEvent() {
        if (mGoToFullShadeNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_GO_TO_FULL_SHADE));
        }
        mGoToFullShadeNeedsAnimation = false;
    }

    @ShadeViewRefactor(RefactorComponent.LAYOUT_ALGORITHM)
    protected StackScrollAlgorithm createStackScrollAlgorithm(Context context) {
        return new StackScrollAlgorithm(context, this);
    }

    /**
     * @return Whether a y coordinate is inside the content.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean isInContentBounds(float y) {
        return y < getHeight() - getEmptyBottomMargin();
    }

    private float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mSlopMultiplier
                : mTouchSlop;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mTouchHandler != null && mTouchHandler.onTouchEvent(ev)) {
            return true;
        }

        return super.onTouchEvent(ev);
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    void dispatchDownEventToScroller(MotionEvent ev) {
        MotionEvent downEvent = MotionEvent.obtain(ev);
        downEvent.setAction(MotionEvent.ACTION_DOWN);
        onScrollTouch(downEvent);
        downEvent.recycle();
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.INPUT)
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!isScrollingEnabled()
                || !mIsExpanded
                || mSwipeHelper.isSwiping()
                || mExpandingNotification
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

    @ShadeViewRefactor(RefactorComponent.INPUT)
    boolean onScrollTouch(MotionEvent ev) {
        if (!isScrollingEnabled()) {
            return false;
        }
        if (isInsideQsContainer(ev) && !mIsBeingDragged) {
            return false;
        }
        mForcedScroll = null;
        initVelocityTrackerIfNotExists();
        mVelocityTracker.addMovement(ev);

        final int action = ev.getActionMasked();
        if (ev.findPointerIndex(mActivePointerId) == -1 && action != MotionEvent.ACTION_DOWN) {
            // Incomplete gesture, possibly due to window swap mid-gesture. Ignore until a new
            // one starts.
            Log.e(TAG, "Invalid pointerId=" + mActivePointerId + " in onTouchEvent "
                    + MotionEvent.actionToString(ev.getActionMasked()));
            return true;
        }

        switch (action) {
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
                final float touchSlop = getTouchSlop(ev);
                if (!mIsBeingDragged && yDiff > touchSlop && yDiff > xDiff) {
                    setIsBeingDragged(true);
                    if (deltaY > 0) {
                        deltaY -= touchSlop;
                    } else {
                        deltaY += touchSlop;
                    }
                }
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    mLastMotionY = y;
                    float scrollAmount;
                    int range;
                    range = getScrollRange();
                    if (mExpandedInThisMotion) {
                        range = Math.min(range, mMaxScrollAfterExpand);
                    }
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
                        mController.checkSnoozeLeavebehind();
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
                                    mFlingAfterUpEvent = true;
                                    setFinishScrollingCallback(() -> {
                                        mFlingAfterUpEvent = false;
                                        InteractionJankMonitor.getInstance()
                                                .end(CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                                        setFinishScrollingCallback(null);
                                    });
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
                    if (mScroller.springBack(mScrollX, mOwnScrollY, 0, 0, 0,
                            getScrollRange())) {
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

    boolean isFlingAfterUpEvent() {
        return mFlingAfterUpEvent;
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    protected boolean isInsideQsContainer(MotionEvent ev) {
        return ev.getY() < mQsContainer.getBottom();
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    private void onOverScrollFling(boolean open, int initialVelocity) {
        if (mOverscrollTopChangedListener != null) {
            mOverscrollTopChangedListener.flingTopOverscroll(initialVelocity, open);
        }
        mDontReportNextOverScroll = true;
        setOverScrollAmount(0.0f, true, false);
    }


    @ShadeViewRefactor(RefactorComponent.INPUT)
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

    @ShadeViewRefactor(RefactorComponent.INPUT)
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

    @Override
    @ShadeViewRefactor(RefactorComponent.INPUT)
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mTouchHandler != null && mTouchHandler.onInterceptTouchEvent(ev)) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    void handleEmptySpaceClick(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                final float touchSlop = getTouchSlop(ev);
                if (mTouchIsClick && (Math.abs(ev.getY() - mInitialTouchY) > touchSlop
                        || Math.abs(ev.getX() - mInitialTouchX) > touchSlop)) {
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

    @ShadeViewRefactor(RefactorComponent.INPUT)
    void initDownStates(MotionEvent ev) {
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

    @Override
    @ShadeViewRefactor(RefactorComponent.INPUT)
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        if (disallowIntercept) {
            cancelLongPress();
        }
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    boolean onInterceptTouchEventScroll(MotionEvent ev) {
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
                if (yDiff > getTouchSlop(ev) && yDiff > xDiff) {
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
                mScrolledToTopOnFirstDown = mScrollAdapter.isScrolledToTop();
                final ExpandableView childAtTouchPos = getChildAtPosition(
                        ev.getX(), y, false /* requireMinHeight */, false /* ignoreDecors */);
                if (childAtTouchPos == null) {
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

    /**
     * @return Whether the specified motion event is actually happening over the content.
     */
    @ShadeViewRefactor(RefactorComponent.INPUT)
    private boolean isInContentBounds(MotionEvent event) {
        return isInContentBounds(event.getY());
    }


    @VisibleForTesting
    @ShadeViewRefactor(RefactorComponent.INPUT)
    void setIsBeingDragged(boolean isDragged) {
        mIsBeingDragged = isDragged;
        if (isDragged) {
            requestDisallowInterceptTouchEvent(true);
            cancelLongPress();
            resetExposedMenuView(true /* animate */, true /* force */);
        }
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    public void requestDisallowLongPress() {
        cancelLongPress();
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    public void requestDisallowDismiss() {
        mDisallowDismissInThisMotion = true;
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    public void cancelLongPress() {
        mSwipeHelper.cancelLongPress();
    }

    @ShadeViewRefactor(RefactorComponent.INPUT)
    public void setOnEmptySpaceClickListener(OnEmptySpaceClickListener listener) {
        mOnEmptySpaceClickListener = listener;
    }

    /** @hide */
    @Override
    @ShadeViewRefactor(RefactorComponent.INPUT)
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
                final int viewportHeight =
                        getHeight() - mPaddingBottom - mTopPadding - mPaddingTop
                                - mShelf.getIntrinsicHeight();
                final int targetScrollY = Math.max(0,
                        Math.min(mOwnScrollY + direction * viewportHeight, getScrollRange()));
                if (targetScrollY != mOwnScrollY) {
                    mScroller.startScroll(mScrollX, mOwnScrollY, 0,
                            targetScrollY - mOwnScrollY);
                    animateScroll();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            cancelLongPress();
        }
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void clearChildFocus(View child) {
        super.clearChildFocus(child);
        if (mForcedScroll == child) {
            mForcedScroll = null;
        }
    }

    boolean isScrolledToBottom() {
        return mScrollAdapter.isScrolledToBottom();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    int getEmptyBottomMargin() {
        return Math.max(mMaxLayoutHeight - mContentHeight, 0);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void onExpansionStarted() {
        mIsExpansionChanging = true;
        mAmbientState.setExpansionChanging(true);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void onExpansionStopped() {
        mIsExpansionChanging = false;
        mAmbientState.setExpansionChanging(false);
        if (!mIsExpanded) {
            resetScrollPosition();
            mStatusBar.resetUserExpandedStates();
            clearTemporaryViews();
            clearUserLockedViews();
            if (mSwipeHelper.isSwiping()) {
                mSwipeHelper.resetSwipeState();
                updateContinuousShadowDrawing();
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void clearUserLockedViews() {
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                row.setUserLocked(false);
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void clearTemporaryViews() {
        // lets make sure nothing is transient anymore
        clearTemporaryViewsInGroup(this);
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            if (child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                clearTemporaryViewsInGroup(row.getChildrenContainer());
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void clearTemporaryViewsInGroup(ViewGroup viewGroup) {
        while (viewGroup != null && viewGroup.getTransientViewCount() != 0) {
            viewGroup.removeTransientView(viewGroup.getTransientView(0));
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void onPanelTrackingStarted() {
        mPanelTracking = true;
        mAmbientState.setPanelTracking(true);
        resetExposedMenuView(true /* animate */, true /* force */);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void onPanelTrackingStopped() {
        mPanelTracking = false;
        mAmbientState.setPanelTracking(false);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void resetScrollPosition() {
        mScroller.abortAnimation();
        setOwnScrollY(0);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void setIsExpanded(boolean isExpanded) {
        boolean changed = isExpanded != mIsExpanded;
        mIsExpanded = isExpanded;
        mStackScrollAlgorithm.setIsExpanded(isExpanded);
        mAmbientState.setShadeExpanded(isExpanded);
        mStateAnimator.setShadeExpanded(isExpanded);
        mSwipeHelper.setIsExpanded(isExpanded);
        if (changed) {
            mWillExpand = false;
            if (!mIsExpanded) {
                mGroupExpansionManager.collapseGroups();
                mExpandHelper.cancelImmediately();
            }
            updateNotificationAnimationStates();
            updateChronometers();
            requestChildrenUpdate();
            updateUseRoundedRectClipping();
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void updateChronometers() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            updateChronometerForChild(getChildAt(i));
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    void updateChronometerForChild(View child) {
        if (child instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            row.setChronometerRunning(mIsExpanded);
        }
    }

    void onChildHeightChanged(ExpandableView view, boolean needsAnimation) {
        boolean previouslyNeededAnimation = mAnimateStackYForContentHeightChange;
        if (needsAnimation) {
            mAnimateStackYForContentHeightChange = true;
        }
        updateContentHeight();
        updateScrollPositionOnExpandInBottom(view);
        clampScrollPosition();
        notifyHeightChangeListener(view, needsAnimation);
        ExpandableNotificationRow row = view instanceof ExpandableNotificationRow
                ? (ExpandableNotificationRow) view
                : null;
        NotificationSection firstSection = getFirstVisibleSection();
        ExpandableView firstVisibleChild =
                firstSection == null ? null : firstSection.getFirstVisibleChild();
        if (row != null) {
            if (row == firstVisibleChild
                    || row.getNotificationParent() == firstVisibleChild) {
                updateAlgorithmLayoutMinHeight();
            }
        }
        if (needsAnimation) {
            requestAnimationOnViewResize(row);
        }
        requestChildrenUpdate();
        mAnimateStackYForContentHeightChange = previouslyNeededAnimation;
    }

    void onChildHeightReset(ExpandableView view) {
        updateAnimationState(view);
        updateChronometerForChild(view);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void updateScrollPositionOnExpandInBottom(ExpandableView view) {
        if (view instanceof ExpandableNotificationRow && !onKeyguard()) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            // TODO: once we're recycling this will need to check the adapter position of the child
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
                NotificationSection lastSection = getLastVisibleSection();
                ExpandableView lastVisibleChild =
                        lastSection == null ? null : lastSection.getLastVisibleChild();
                if (row != lastVisibleChild && mShelf.getVisibility() != GONE) {
                    layoutEnd -= mShelf.getIntrinsicHeight() + mPaddingBetweenElements;
                }
                if (endPosition > layoutEnd) {
                    setOwnScrollY((int) (mOwnScrollY + endPosition - layoutEnd));
                    mDisallowScrollingInThisMotion = true;
                }
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener onHeightChangedListener) {
        this.mOnHeightChangedListener = onHeightChangedListener;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void onChildAnimationFinished() {
        setAnimationRunning(false);
        requestChildrenUpdate();
        runAnimationFinishedRunnables();
        clearTransient();
        clearHeadsUpDisappearRunning();

        if (mAmbientState.isDismissAllInProgress()) {
            setDismissAllInProgress(false);

            if (mShadeNeedsToClose) {
                mShadeNeedsToClose = false;
                postDelayed(
                        () -> {
                            mShadeController.animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                        },
                        DELAY_BEFORE_SHADE_CLOSE /* delayMillis */);
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void clearHeadsUpDisappearRunning() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                row.setHeadsUpAnimatingAway(false);
                if (row.isSummaryWithChildren()) {
                    for (ExpandableNotificationRow child : row.getAttachedChildren()) {
                        child.setHeadsUpAnimatingAway(false);
                    }
                }
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void clearTransient() {
        for (ExpandableView view : mClearTransientViewsWhenFinished) {
            StackStateAnimator.removeTransientView(view);
        }
        mClearTransientViewsWhenFinished.clear();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void runAnimationFinishedRunnables() {
        for (Runnable runnable : mAnimationFinishedRunnables) {
            runnable.run();
        }
        mAnimationFinishedRunnables.clear();
    }

    /**
     * See {@link AmbientState#setDimmed}.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setDimmed(boolean dimmed, boolean animate) {
        dimmed &= onKeyguard();
        mAmbientState.setDimmed(dimmed);
        if (animate && mAnimationsEnabled) {
            mDimmedNeedsAnimation = true;
            mNeedsAnimation = true;
            animateDimmed(dimmed);
        } else {
            setDimAmount(dimmed ? 1.0f : 0.0f);
        }
        requestChildrenUpdate();
    }

    @VisibleForTesting
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    boolean isDimmed() {
        return mAmbientState.isDimmed();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void setDimAmount(float dimAmount) {
        mDimAmount = dimAmount;
        updateBackgroundDimming();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void updateSensitiveness(boolean animate, boolean hideSensitive) {
        if (hideSensitive != mAmbientState.isHideSensitive()) {
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                ExpandableView v = (ExpandableView) getChildAt(i);
                v.setHideSensitiveForIntrinsicHeight(hideSensitive);
            }
            mAmbientState.setHideSensitive(hideSensitive);
            if (animate && mAnimationsEnabled) {
                mHideSensitiveNeedsAnimation = true;
                mNeedsAnimation = true;
            }
            updateContentHeight();
            requestChildrenUpdate();
        }
    }

    /**
     * See {@link AmbientState#setActivatedChild}.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setActivatedChild(ActivatableNotificationView activatedChild) {
        mAmbientState.setActivatedChild(activatedChild);
        if (mAnimationsEnabled) {
            mActivateNeedsAnimation = true;
            mNeedsAnimation = true;
        }
        requestChildrenUpdate();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public ActivatableNotificationView getActivatedChild() {
        return mAmbientState.getActivatedChild();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    private void applyCurrentState() {
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            child.applyViewState();
        }

        if (mListener != null) {
            mListener.onChildLocationsChanged();
        }
        runAnimationFinishedRunnables();
        setAnimationRunning(false);
        updateBackground();
        updateViewShadows();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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
                        (int) (previous.getOutlineTranslation() + previous.getTranslation()));
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
    @ShadeViewRefactor(RefactorComponent.DECORATOR)
    void updateDecorViews() {
        final @ColorInt int textColor =
                Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
        mSectionsManager.setHeaderForegroundColor(textColor);
        mFooterView.updateColors();
        mEmptyShadeView.setTextColor(textColor);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void goToFullShade(long delay) {
        mGoToFullShadeNeedsAnimation = true;
        mGoToFullShadeDelay = delay;
        mNeedsAnimation = true;
        requestChildrenUpdate();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void cancelExpandHelper() {
        mExpandHelper.cancel();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    void setIntrinsicPadding(int intrinsicPadding) {
        mIntrinsicPadding = intrinsicPadding;
        mAmbientState.setIntrinsicPadding(intrinsicPadding);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    int getIntrinsicPadding() {
        return mIntrinsicPadding;
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    /**
     * See {@link AmbientState#setDozing}.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setDozing(boolean dozing, boolean animate,
            @Nullable PointF touchWakeUpScreenLocation) {
        if (mAmbientState.isDozing() == dozing) {
            return;
        }
        mAmbientState.setDozing(dozing);
        requestChildrenUpdate();
        notifyHeightChangeListener(mShelf);
    }

    /**
     * Sets the current hide amount.
     *
     * @param linearHideAmount       The hide amount that follows linear interpoloation in the
     *                               animation,
     *                               i.e. animates from 0 to 1 or vice-versa in a linear manner.
     * @param interpolatedHideAmount The hide amount that follows the actual interpolation of the
     *                               animation curve.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setHideAmount(float linearHideAmount, float interpolatedHideAmount) {
        mLinearHideAmount = linearHideAmount;
        mInterpolatedHideAmount = interpolatedHideAmount;
        boolean wasFullyHidden = mAmbientState.isFullyHidden();
        boolean wasHiddenAtAll = mAmbientState.isHiddenAtAll();
        mAmbientState.setHideAmount(interpolatedHideAmount);
        boolean nowFullyHidden = mAmbientState.isFullyHidden();
        boolean nowHiddenAtAll = mAmbientState.isHiddenAtAll();
        if (nowFullyHidden != wasFullyHidden) {
            updateVisibility();
        }
        if (!wasHiddenAtAll && nowHiddenAtAll) {
            resetExposedMenuView(true /* animate */, true /* animate */);
        }
        if (nowFullyHidden != wasFullyHidden || wasHiddenAtAll != nowHiddenAtAll) {
            invalidateOutline();
        }
        updateAlgorithmHeightAndPadding();
        updateBackgroundDimming();
        requestChildrenUpdate();
        updateOwnTranslationZ();
    }

    private void updateOwnTranslationZ() {
        // Since we are clipping to the outline we need to make sure that the shadows aren't
        // clipped when pulsing
        float ownTranslationZ = 0;
        if (mKeyguardBypassEnabledProvider.getBypassEnabled() && mAmbientState.isHiddenAtAll()) {
            ExpandableView firstChildNotGone = getFirstChildNotGone();
            if (firstChildNotGone != null && firstChildNotGone.showingPulsing()) {
                ownTranslationZ = firstChildNotGone.getTranslationZ();
            }
        }
        setTranslationZ(ownTranslationZ);
    }

    private void updateVisibility() {
        boolean shouldShow = !mAmbientState.isFullyHidden() || !onKeyguard();
        setVisibility(shouldShow ? View.VISIBLE : View.INVISIBLE);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void notifyHideAnimationStart(boolean hide) {
        // We only swap the scaling factor if we're fully hidden or fully awake to avoid
        // interpolation issues when playing with the power button.
        if (mInterpolatedHideAmount == 0 || mInterpolatedHideAmount == 1) {
            mBackgroundXFactor = hide ? 1.8f : 1.5f;
            mHideXInterpolator = hide
                    ? Interpolators.FAST_OUT_SLOW_IN_REVERSE
                    : Interpolators.FAST_OUT_SLOW_IN;
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setFooterView(@NonNull FooterView footerView) {
        int index = -1;
        if (mFooterView != null) {
            index = indexOfChild(mFooterView);
            removeView(mFooterView);
        }
        mFooterView = footerView;
        addView(mFooterView, index);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setEmptyShadeView(EmptyShadeView emptyShadeView) {
        int index = -1;
        if (mEmptyShadeView != null) {
            index = indexOfChild(mEmptyShadeView);
            removeView(mEmptyShadeView);
        }
        mEmptyShadeView = emptyShadeView;
        addView(mEmptyShadeView, index);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void updateEmptyShadeView(boolean visible, boolean notifVisibleInShade) {
        mEmptyShadeView.setVisible(visible, mIsExpanded && mAnimationsEnabled);

        int oldTextRes = mEmptyShadeView.getTextResource();
        int newTextRes = notifVisibleInShade
                ? R.string.dnd_suppressing_shade_text : R.string.empty_shade_text;
        if (oldTextRes != newTextRes) {
            mEmptyShadeView.setText(newTextRes);
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void updateFooterView(boolean visible, boolean showDismissView, boolean showHistory) {
        if (mFooterView == null) {
            return;
        }
        boolean animate = mIsExpanded && mAnimationsEnabled;
        mFooterView.setVisible(visible, animate);
        mFooterView.setSecondaryVisible(showDismissView, animate);
        mFooterView.showHistory(showHistory);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setDismissAllInProgress(boolean dismissAllInProgress) {
        mDismissAllInProgress = dismissAllInProgress;
        mAmbientState.setDismissAllInProgress(dismissAllInProgress);
        mController.getNoticationRoundessManager().setDismissAllInProgress(dismissAllInProgress);
        handleDismissAllClipping();
    }

    boolean getDismissAllInProgress() {
        return mDismissAllInProgress;
    }

    @ShadeViewRefactor(RefactorComponent.ADAPTER)
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

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean isFooterViewNotGone() {
        return mFooterView != null
                && mFooterView.getVisibility() != View.GONE
                && !mFooterView.willBeGone();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean isFooterViewContentVisible() {
        return mFooterView != null && mFooterView.isContentVisible();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public int getFooterViewHeightWithPadding() {
        return mFooterView == null ? 0 : mFooterView.getHeight()
                + mPaddingBetweenElements
                + mGapHeight;
    }

    /**
     * @return the padding after the media header on the lockscreen
     */
    public int getPaddingAfterMedia() {
        return mGapHeight + mPaddingBetweenElements;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public int getEmptyShadeViewHeight() {
        return mEmptyShadeView.getHeight();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setStatusBar(StatusBar statusBar) {
        this.mStatusBar = statusBar;
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void requestAnimateEverything() {
        if (mIsExpanded && mAnimationsEnabled) {
            mEverythingNeedsAnimation = true;
            mNeedsAnimation = true;
            requestChildrenUpdate();
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
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
                    if (!belowChild && !mFooterView.isOnEmptySpace(touchX - mFooterView.getX(),
                            touchY - childTop)) {
                        // We clicked on the dismiss button
                        return false;
                    }
                } else if (child == mEmptyShadeView) {
                    // We arrived at the empty shade view, for which we accept all clicks
                    return true;
                } else if (!belowChild) {
                    // We are on a child
                    return false;
                }
            }
        }
        return touchY > mTopPadding + mStackTranslation;
    }

    /** @hide */
    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        event.setScrollable(mScrollable);
        event.setMaxScrollX(mScrollX);
        event.setScrollY(mOwnScrollY);
        event.setMaxScrollY(getScrollRange());
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
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

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public void generateChildOrderChangedEvent() {
        if (mIsExpanded && mAnimationsEnabled) {
            mGenerateChildOrderChangedEvent = true;
            mNeedsAnimation = true;
            requestChildrenUpdate();
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public int getContainerChildCount() {
        return getChildCount();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public View getContainerChildAt(int i) {
        return getChildAt(i);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void removeContainerView(View v) {
        Assert.isMainThread();
        removeView(v);
        if (v instanceof ExpandableNotificationRow && !mController.isShowingEmptyShadeView()) {
            mController.updateShowEmptyShadeView();
            updateFooter();
        }

        updateSpeedBumpIndex();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void addContainerView(View v) {
        Assert.isMainThread();
        addView(v);
        if (v instanceof ExpandableNotificationRow && mController.isShowingEmptyShadeView()) {
            mController.updateShowEmptyShadeView();
            updateFooter();
        }

        updateSpeedBumpIndex();
    }

    public void addContainerViewAt(View v, int index) {
        Assert.isMainThread();
        addView(v, index);
        if (v instanceof ExpandableNotificationRow && mController.isShowingEmptyShadeView()) {
            mController.updateShowEmptyShadeView();
            updateFooter();
        }

        updateSpeedBumpIndex();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void runAfterAnimationFinished(Runnable runnable) {
        mAnimationFinishedRunnables.add(runnable);
    }

    public void generateHeadsUpAnimation(NotificationEntry entry, boolean isHeadsUp) {
        ExpandableNotificationRow row = entry.getHeadsUpAnimationView();
        generateHeadsUpAnimation(row, isHeadsUp);
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        if (mAnimationsEnabled && (isHeadsUp || mHeadsUpGoingAwayAnimationsAllowed)) {
            mHeadsUpChangeAnimations.add(new Pair<>(row, isHeadsUp));
            mNeedsAnimation = true;
            if (!mIsExpanded && !mWillExpand && !isHeadsUp) {
                row.setHeadsUpAnimatingAway(true);
            }
            requestChildrenUpdate();
        }
    }

    /**
     * Set the boundary for the bottom heads up position. The heads up will always be above this
     * position.
     *
     * @param height          the height of the screen
     * @param bottomBarHeight the height of the bar on the bottom
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setHeadsUpBoundaries(int height, int bottomBarHeight) {
        mAmbientState.setMaxHeadsUpTranslation(height - bottomBarHeight);
        mStateAnimator.setHeadsUpAppearHeightBottom(height);
        requestChildrenUpdate();
    }

    public void setWillExpand(boolean willExpand) {
        mWillExpand = willExpand;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setTrackingHeadsUp(ExpandableNotificationRow row) {
        mAmbientState.setTrackedHeadsUpRow(row);
        mTrackingHeadsUp = row != null;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void forceNoOverlappingRendering(boolean force) {
        mForceNoOverlappingRendering = force;
    }

    @Override
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean hasOverlappingRendering() {
        return !mForceNoOverlappingRendering && super.hasOverlappingRendering();
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
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

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean isExpanded() {
        return mIsExpanded;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setPulsing(boolean pulsing, boolean animated) {
        if (!mPulsing && !pulsing) {
            return;
        }
        mPulsing = pulsing;
        mAmbientState.setPulsing(pulsing);
        mSwipeHelper.setPulsing(pulsing);
        updateNotificationAnimationStates();
        updateAlgorithmHeightAndPadding();
        updateContentHeight();
        requestChildrenUpdate();
        notifyHeightChangeListener(null, animated);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setQsExpanded(boolean qsExpanded) {
        mQsExpanded = qsExpanded;
        updateAlgorithmLayoutMinHeight();
        updateScrollability();
    }

    boolean isQsExpanded() {
        return mQsExpanded;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setQsExpansionFraction(float qsExpansionFraction) {
        mQsExpansionFraction = qsExpansionFraction;
        updateUseRoundedRectClipping();

        // If notifications are scrolled,
        // clear out scrollY by the time we push notifications offscreen
        if (mOwnScrollY > 0) {
            setOwnScrollY((int) MathUtils.lerp(mOwnScrollY, 0, mQsExpansionFraction));
        }
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void setOwnScrollY(int ownScrollY) {
        setOwnScrollY(ownScrollY, false /* animateScrollChangeListener */);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    private void setOwnScrollY(int ownScrollY, boolean animateStackYChangeListener) {
        if (ownScrollY != mOwnScrollY) {
            // We still want to call the normal scrolled changed for accessibility reasons
            onScrollChanged(mScrollX, ownScrollY, mScrollX, mOwnScrollY);
            mOwnScrollY = ownScrollY;
            mAmbientState.setScrollY(mOwnScrollY);
            updateOnScrollChange();
            updateStackPosition(animateStackYChangeListener);
        }
    }

    private void updateOnScrollChange() {
        if (mScrollListener != null) {
            mScrollListener.accept(mOwnScrollY);
        }
        updateForwardAndBackwardScrollability();
        requestChildrenUpdate();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setShelfController(NotificationShelfController notificationShelfController) {
        int index = -1;
        if (mShelf != null) {
            index = indexOfChild(mShelf);
            removeView(mShelf);
        }
        mShelf = notificationShelfController.getView();
        addView(mShelf, index);
        mAmbientState.setShelf(mShelf);
        mStateAnimator.setShelf(mShelf);
        notificationShelfController.bind(mAmbientState, mController);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setMaxDisplayedNotifications(int maxDisplayedNotifications) {
        if (mMaxDisplayedNotifications != maxDisplayedNotifications) {
            mMaxDisplayedNotifications = maxDisplayedNotifications;
            updateContentHeight();
            notifyHeightChangeListener(mShelf);
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setShouldShowShelfOnly(boolean shouldShowShelfOnly) {
        mShouldShowShelfOnly = shouldShowShelfOnly;
        updateAlgorithmLayoutMinHeight();
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public int getMinExpansionHeight() {
        return mShelf.getIntrinsicHeight()
                - (mShelf.getIntrinsicHeight() - mStatusBarHeight + mWaterfallTopInset) / 2
                + mWaterfallTopInset;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setInHeadsUpPinnedMode(boolean inHeadsUpPinnedMode) {
        mInHeadsUpPinnedMode = inHeadsUpPinnedMode;
        updateClipping();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        updateClipping();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    @VisibleForTesting
    public void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
        mAmbientState.setStatusBarState(statusBarState);
        updateSpeedBumpIndex();
        updateDismissBehavior();
    }

    void onStatePostChange(boolean fromShadeLocked) {
        boolean onKeyguard = onKeyguard();

        mAmbientState.setActivatedChild(null);
        mAmbientState.setDimmed(onKeyguard);

        if (mHeadsUpAppearanceController != null) {
            mHeadsUpAppearanceController.onStateChanged();
        }

        setDimmed(onKeyguard, fromShadeLocked);
        setExpandingEnabled(!onKeyguard);
        ActivatableNotificationView activatedChild = getActivatedChild();
        setActivatedChild(null);
        if (activatedChild != null) {
            activatedChild.makeInactive(false /* animate */);
        }
        updateFooter();
        requestChildrenUpdate();
        onUpdateRowStates();
        updateVisibility();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setExpandingVelocity(float expandingVelocity) {
        mAmbientState.setExpandingVelocity(expandingVelocity);
    }

    @ShadeViewRefactor(RefactorComponent.COORDINATOR)
    public float getOpeningHeight() {
        if (mEmptyShadeView.getVisibility() == GONE) {
            return getMinExpansionHeight();
        } else {
            return getAppearEndPosition();
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setIsFullWidth(boolean isFullWidth) {
        mAmbientState.setPanelFullWidth(isFullWidth);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setUnlockHintRunning(boolean running) {
        mAmbientState.setUnlockHintRunning(running);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setQsCustomizerShowing(boolean isShowing) {
        mAmbientState.setQsCustomizerShowing(isShowing);
        requestChildrenUpdate();
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void setHeadsUpGoingAwayAnimationsAllowed(boolean headsUpGoingAwayAnimationsAllowed) {
        mHeadsUpGoingAwayAnimationsAllowed = headsUpGoingAwayAnimationsAllowed;
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("[%s: pulsing=%s qsCustomizerShowing=%s visibility=%s"
                        + " alpha=%f scrollY:%d maxTopPadding=%d showShelfOnly=%s"
                        + " qsExpandFraction=%f"
                        + " hideAmount=%f]",
                this.getClass().getSimpleName(),
                mPulsing ? "T" : "f",
                mAmbientState.isQsCustomizerShowing() ? "T" : "f",
                getVisibility() == View.VISIBLE ? "visible"
                        : getVisibility() == View.GONE ? "gone"
                                : "invisible",
                getAlpha(),
                mAmbientState.getScrollY(),
                mMaxTopPadding,
                mShouldShowShelfOnly ? "T" : "f",
                mQsExpansionFraction,
                mAmbientState.getHideAmount()));
        int childCount = getChildCount();
        pw.println("  Number of children: " + childCount);
        pw.println();

        for (int i = 0; i < childCount; i++) {
            ExpandableView child = (ExpandableView) getChildAt(i);
            child.dump(fd, pw, args);
            if (!(child instanceof ExpandableNotificationRow)) {
                pw.println("  " + child.getClass().getSimpleName());
                // Notifications dump it's viewstate as part of their dump to support children
                ExpandableViewState viewState = child.getViewState();
                if (viewState == null) {
                    pw.println("    no viewState!!!");
                } else {
                    pw.print("    ");
                    viewState.dump(fd, pw, args);
                    pw.println();
                    pw.println();
                }
            }
        }
        int transientViewCount = getTransientViewCount();
        pw.println("  Transient Views: " + transientViewCount);
        for (int i = 0; i < transientViewCount; i++) {
            ExpandableView child = (ExpandableView) getTransientView(i);
            child.dump(fd, pw, args);
        }
        View swipedView = mSwipeHelper.getSwipedView();
        pw.println("  Swiped view: " + swipedView);
        if (swipedView instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) swipedView;
            expandableView.dump(fd, pw, args);
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public boolean isFullyHidden() {
        return mAmbientState.isFullyHidden();
    }

    /**
     * Add a listener whenever the expanded height changes. The first value passed as an
     * argument is the expanded height and the second one is the appearFraction.
     *
     * @param listener the listener to notify.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void addOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mExpandedHeightListeners.add(listener);
    }

    /**
     * Stop a listener from listening to the expandedHeight.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public void removeOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mExpandedHeightListeners.remove(listener);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    void setHeadsUpAppearanceController(
            HeadsUpAppearanceController headsUpAppearanceController) {
        mHeadsUpAppearanceController = headsUpAppearanceController;
    }

    private boolean isVisible(View child) {
        boolean hasClipBounds = child.getClipBounds(mTmpRect);
        return child.getVisibility() == View.VISIBLE
                && (!hasClipBounds || mTmpRect.height() > 0);
    }

    private boolean shouldHideParent(View view, @SelectedRows int selection) {
        final boolean silentSectionWillBeGone =
                !mController.hasNotifications(ROWS_GENTLE, false /* clearable */);

        // The only SectionHeaderView we have is the silent section header.
        if (view instanceof SectionHeaderView && silentSectionWillBeGone) {
            return true;
        }
        if (view instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) view;
            if (isVisible(row) && includeChildInDismissAll(row, selection)) {
                return true;
            }
        }
        return false;
    }

    private boolean isChildrenVisible(ExpandableNotificationRow parent) {
        List<ExpandableNotificationRow> children = parent.getAttachedChildren();
        return isVisible(parent)
                && children != null
                && parent.areChildrenExpanded();
    }

    // Similar to #getRowsToDismissInBackend, but filters for visible views.
    private ArrayList<View> getVisibleViewsToAnimateAway(@SelectedRows int selection) {
        final int viewCount = getChildCount();
        final ArrayList<View> viewsToHide = new ArrayList<>(viewCount);

        for (int i = 0; i < viewCount; i++) {
            final View view = getChildAt(i);

            if (shouldHideParent(view, selection)) {
                viewsToHide.add(view);
            }
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow parent = (ExpandableNotificationRow) view;

                if (isChildrenVisible(parent)) {
                    for (ExpandableNotificationRow child : parent.getAttachedChildren()) {
                        if (isVisible(child) && includeChildInDismissAll(child, selection)) {
                            viewsToHide.add(child);
                        }
                    }
                }
            }
        }
        return viewsToHide;
    }

    private ArrayList<ExpandableNotificationRow> getRowsToDismissInBackend(
            @SelectedRows int selection) {
        final int childCount = getChildCount();
        final ArrayList<ExpandableNotificationRow> viewsToRemove = new ArrayList<>(childCount);

        for (int i = 0; i < childCount; i++) {
            final View view = getChildAt(i);
            if (!(view instanceof ExpandableNotificationRow)) {
                continue;
            }
            ExpandableNotificationRow parent = (ExpandableNotificationRow) view;
            if (includeChildInDismissAll(parent, selection)) {
                viewsToRemove.add(parent);
            }
            List<ExpandableNotificationRow> children = parent.getAttachedChildren();
            if (isVisible(parent) && children != null) {
                for (ExpandableNotificationRow child : children) {
                    if (includeChildInDismissAll(parent, selection)) {
                        viewsToRemove.add(child);
                    }
                }
            }
        }
        return viewsToRemove;
    }

    /**
     * Collects a list of visible rows, and animates them away in a staggered fashion as if they
     * were dismissed. Notifications are dismissed in the backend via onDismissAllAnimationsEnd.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    @VisibleForTesting
    void clearNotifications(@SelectedRows int selection, boolean closeShade) {
        // Animate-swipe all dismissable notifications, then animate the shade closed
        final ArrayList<View> viewsToAnimateAway = getVisibleViewsToAnimateAway(selection);
        final ArrayList<ExpandableNotificationRow> rowsToDismissInBackend =
                getRowsToDismissInBackend(selection);
        if (mDismissListener != null) {
            mDismissListener.onDismiss(selection);
        }
        final Runnable dismissInBackend = () -> {
            onDismissAllAnimationsEnd(rowsToDismissInBackend, selection);
        };
        if (viewsToAnimateAway.isEmpty()) {
            dismissInBackend.run();
            return;
        }
        // Disable normal animations
        setDismissAllInProgress(true);
        mShadeNeedsToClose = closeShade;

        // Decrease the delay for every row we animate to give the sense of
        // accelerating the swipes
        final int rowDelayDecrement = 5;
        int currentDelay = 60;
        int totalDelay = 0;
        final int numItems = viewsToAnimateAway.size();
        for (int i = numItems - 1; i >= 0; i--) {
            View view = viewsToAnimateAway.get(i);
            Runnable endRunnable = null;
            if (i == 0) {
                endRunnable = dismissInBackend;
            }
            dismissViewAnimated(view, endRunnable, totalDelay, ANIMATION_DURATION_SWIPE);
            currentDelay = Math.max(30, currentDelay - rowDelayDecrement);
            totalDelay += currentDelay;
        }
    }

    private boolean includeChildInDismissAll(
            ExpandableNotificationRow row,
            @SelectedRows int selection) {
        return canChildBeDismissed(row) && matchesSelection(row, selection);
    }

    public void setNotificationActivityStarter(
            NotificationActivityStarter notificationActivityStarter) {
        mNotificationActivityStarter = notificationActivityStarter;
    }

    @VisibleForTesting
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    protected void inflateFooterView() {
        FooterView footerView = (FooterView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_notification_footer, this, false);
        footerView.setDismissButtonClickListener(v -> {
            if (mFooterDismissListener != null) {
                mFooterDismissListener.onDismiss();
            }
            clearNotifications(ROWS_ALL, true /* closeShade */);
            footerView.setSecondaryVisible(false /* visible */, true /* animate */);
        });
        footerView.setManageButtonClickListener(v -> {
            mNotificationActivityStarter.startHistoryIntent(v, mFooterView.isHistoryShown());
        });
        setFooterView(footerView);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void inflateEmptyShadeView() {
        EmptyShadeView view = (EmptyShadeView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_no_notifications, this, false);
        view.setText(R.string.empty_shade_text);
        view.setOnClickListener(v -> {
            final boolean showHistory = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
            Intent intent = showHistory ? new Intent(
                    Settings.ACTION_NOTIFICATION_HISTORY) : new Intent(
                    Settings.ACTION_NOTIFICATION_SETTINGS);
            mStatusBar.startActivity(intent, true, true, Intent.FLAG_ACTIVITY_SINGLE_TOP);
        });
        setEmptyShadeView(view);
    }

    /**
     * Updates expanded, dimmed and locked states of notification rows.
     */
    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    public void onUpdateRowStates() {

        // The following views will be moved to the end of mStackScroller. This counter represents
        // the offset from the last child. Initialized to 1 for the very last position. It is post-
        // incremented in the following "changeViewPosition" calls so that its value is correct for
        // subsequent calls.
        int offsetFromEnd = 1;
        if (mFgsSectionView != null) {
            changeViewPosition(mFgsSectionView, getChildCount() - offsetFromEnd++);
        }
        changeViewPosition(mFooterView, getChildCount() - offsetFromEnd++);
        changeViewPosition(mEmptyShadeView, getChildCount() - offsetFromEnd++);

        // No post-increment for this call because it is the last one. Make sure to add one if
        // another "changeViewPosition" call is ever added.
        changeViewPosition(mShelf,
                getChildCount() - offsetFromEnd);
    }

    /**
     * Set how far the wake up is when waking up from pulsing. This is a height and will adjust the
     * notification positions accordingly.
     * @param height the new wake up height
     * @return the overflow how much the height is further than he lowest notification
     */
    public float setPulseHeight(float height) {
        float overflow;
        mAmbientState.setPulseHeight(height);
        if (mKeyguardBypassEnabledProvider.getBypassEnabled()) {
            notifyAppearChangedListeners();
            overflow = Math.max(0, height - getIntrinsicPadding());
        } else {
            overflow = Math.max(0, height
                    - mAmbientState.getInnerHeight(true /* ignorePulseHeight */));
        }
        requestChildrenUpdate();
        return overflow;
    }

    public float getPulseHeight() {
        return mAmbientState.getPulseHeight();
    }

    /**
     * Set the amount how much we're dozing. This is different from how hidden the shade is, when
     * the notification is pulsing.
     */
    public void setDozeAmount(float dozeAmount) {
        mAmbientState.setDozeAmount(dozeAmount);
        updateContinuousBackgroundDrawing();
        requestChildrenUpdate();
    }

    public boolean isFullyAwake() {
        return mAmbientState.isFullyAwake();
    }

    public void wakeUpFromPulse() {
        setPulseHeight(getWakeUpHeight());
        // Let's place the hidden views at the end of the pulsing notification to make sure we have
        // a smooth animation
        boolean firstVisibleView = true;
        float wakeUplocation = -1f;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            ExpandableView view = (ExpandableView) getChildAt(i);
            if (view.getVisibility() == View.GONE) {
                continue;
            }
            boolean isShelf = view == mShelf;
            if (!(view instanceof ExpandableNotificationRow) && !isShelf) {
                continue;
            }
            if (view.getVisibility() == View.VISIBLE && !isShelf) {
                if (firstVisibleView) {
                    firstVisibleView = false;
                    wakeUplocation = view.getTranslationY()
                            + view.getActualHeight() - mShelf.getIntrinsicHeight();
                }
            } else if (!firstVisibleView) {
                view.setTranslationY(wakeUplocation);
            }
        }
        mDimmedNeedsAnimation = true;
    }

    void setAnimateBottomOnLayout(boolean animateBottomOnLayout) {
        mAnimateBottomOnLayout = animateBottomOnLayout;
    }

    public void setOnPulseHeightChangedListener(Runnable listener) {
        mAmbientState.setOnPulseHeightChangedListener(listener);
    }

    public float calculateAppearFractionBypass() {
        float pulseHeight = getPulseHeight();
        // The total distance required to fully reveal the header
        float totalDistance = getIntrinsicPadding();
        return MathUtils.smoothStep(0, totalDistance, pulseHeight);
    }

    public void setController(
            NotificationStackScrollLayoutController notificationStackScrollLayoutController) {
        mController = notificationStackScrollLayoutController;
        mController.getNoticationRoundessManager().setAnimatedChildren(mChildrenToAddAnimated);
    }

    public NotificationStackScrollLayoutController getController() {
        return mController;
    }

    void addSwipedOutView(View v) {
        mSwipedOutViews.add(v);
    }

    void onSwipeBegin(View viewSwiped) {
        if (!(viewSwiped instanceof ExpandableNotificationRow)) {
            return;
        }
        final int indexOfSwipedView = indexOfChild(viewSwiped);
        if (indexOfSwipedView < 0) {
            return;
        }
        mSectionsManager.updateFirstAndLastViewsForAllSections(
                mSections, getChildrenWithBackground());
        View viewBefore = null;
        if (indexOfSwipedView > 0) {
            viewBefore = getChildAt(indexOfSwipedView - 1);
            if (mSectionsManager.beginsSection(viewSwiped, viewBefore)) {
                viewBefore = null;
            }
        }
        View viewAfter = null;
        if (indexOfSwipedView < getChildCount()) {
            viewAfter = getChildAt(indexOfSwipedView + 1);
            if (mSectionsManager.beginsSection(viewAfter, viewSwiped)) {
                viewAfter = null;
            }
        }
        mController.getNoticationRoundessManager()
                .setViewsAffectedBySwipe((ExpandableView) viewBefore,
                        (ExpandableView) viewSwiped,
                        (ExpandableView) viewAfter,
                        getResources().getBoolean(R.bool.flag_notif_updates));

        updateFirstAndLastBackgroundViews();
        requestDisallowInterceptTouchEvent(true);
        updateContinuousShadowDrawing();
        updateContinuousBackgroundDrawing();
        requestChildrenUpdate();
    }

    void onSwipeEnd() {
        updateFirstAndLastBackgroundViews();
        mController.getNoticationRoundessManager()
                .setViewsAffectedBySwipe(null, null, null,
                        getResources().getBoolean(R.bool.flag_notif_updates));
        // Round bottom corners for notification right before shelf.
        mShelf.updateAppearance();
    }

    void setTopHeadsUpEntry(NotificationEntry topEntry) {
        mTopHeadsUpEntry = topEntry;
    }

    void setNumHeadsUp(long numHeadsUp) {
        mNumHeadsUp = numHeadsUp;
        mAmbientState.setHasAlertEntries(numHeadsUp > 0);
    }

    public boolean getIsExpanded() {
        return mIsExpanded;
    }

    boolean getOnlyScrollingInThisMotion() {
        return mOnlyScrollingInThisMotion;
    }

    ExpandHelper getExpandHelper() {
        return mExpandHelper;
    }

    boolean isExpandingNotification() {
        return mExpandingNotification;
    }

    boolean getDisallowScrollingInThisMotion() {
        return mDisallowScrollingInThisMotion;
    }

    boolean isBeingDragged() {
        return mIsBeingDragged;
    }

    boolean getExpandedInThisMotion() {
        return mExpandedInThisMotion;
    }

    boolean getDisallowDismissInThisMotion() {
        return mDisallowDismissInThisMotion;
    }

    void setCheckForLeaveBehind(boolean checkForLeaveBehind) {
        mCheckForLeavebehind = checkForLeaveBehind;
    }

    void setTouchHandler(NotificationStackScrollLayoutController.TouchHandler touchHandler) {
        mTouchHandler = touchHandler;
    }

    boolean getCheckSnoozeLeaveBehind() {
        return mCheckForLeavebehind;
    }

    void setDismissListener (DismissListener listener) {
        mDismissListener = listener;
    }

    void setDismissAllAnimationListener(DismissAllAnimationListener dismissAllAnimationListener) {
        mDismissAllAnimationListener = dismissAllAnimationListener;
    }

    public void setHighPriorityBeforeSpeedBump(boolean highPriorityBeforeSpeedBump) {
        mHighPriorityBeforeSpeedBump = highPriorityBeforeSpeedBump;
    }

    void setFooterDismissListener(FooterDismissListener listener) {
        mFooterDismissListener = listener;
    }

    public void setRemoteInputManager(NotificationRemoteInputManager remoteInputManager) {
        mRemoteInputManager = remoteInputManager;
    }

    void setShadeController(ShadeController shadeController) {
        mShadeController = shadeController;
    }

    /**
     * Sets the extra top inset for the full shade transition. This moves notifications down
     * during the drag down.
     */
    public void setExtraTopInsetForFullShadeTransition(float inset) {
        mExtraTopInsetForFullShadeTransition = inset;
        updateStackPosition();
        requestChildrenUpdate();
    }

    /**
     * Set a listener to when scrolling changes.
     */
    public void setOnScrollListener(Consumer<Integer> listener) {
        mScrollListener = listener;
    }

    /**
     * Set rounded rect clipping bounds on this view.
     */
    public void setRoundedClippingBounds(int left, int top, int right, int bottom, int topRadius,
            int bottomRadius) {
        if (mRoundedRectClippingLeft == left && mRoundedRectClippingRight == right
                && mRoundedRectClippingBottom == bottom && mRoundedRectClippingTop == top
                && mBgCornerRadii[0] == topRadius && mBgCornerRadii[5] == bottomRadius) {
            return;
        }
        mRoundedRectClippingLeft = left;
        mRoundedRectClippingTop = top;
        mRoundedRectClippingBottom = bottom;
        mRoundedRectClippingRight = right;
        mBgCornerRadii[0] = topRadius;
        mBgCornerRadii[1] = topRadius;
        mBgCornerRadii[2] = topRadius;
        mBgCornerRadii[3] = topRadius;
        mBgCornerRadii[4] = bottomRadius;
        mBgCornerRadii[5] = bottomRadius;
        mBgCornerRadii[6] = bottomRadius;
        mBgCornerRadii[7] = bottomRadius;
        mRoundedClipPath.reset();
        mRoundedClipPath.addRoundRect(left, top, right, bottom, mBgCornerRadii, Path.Direction.CW);
        if (mShouldUseRoundedRectClipping) {
            invalidate();
        }
    }

    private void updateSplitNotificationShade() {
        boolean split = shouldUseSplitNotificationShade(mFeatureFlags, getResources());
        if (split != mShouldUseSplitNotificationShade) {
            mShouldUseSplitNotificationShade = split;
            updateDismissBehavior();
            updateUseRoundedRectClipping();
        }
    }

    private void updateDismissBehavior() {
        // On the split keyguard, dismissing with clipping without a visual boundary looks odd,
        // so let's use the content dismiss behavior instead.
        boolean dismissUsingRowTranslationX = !mShouldUseSplitNotificationShade
                || mStatusBarState != StatusBarState.KEYGUARD;
        if (mDismissUsingRowTranslationX != dismissUsingRowTranslationX) {
            mDismissUsingRowTranslationX = dismissUsingRowTranslationX;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof ExpandableNotificationRow) {
                    ((ExpandableNotificationRow) child).setDismissUsingRowTranslationX(
                            dismissUsingRowTranslationX);
                }
            }
        }
    }

    /**
     * Set if we're launching a notification right now.
     */
    private void setLaunchingNotification(boolean launching) {
        if (launching == mLaunchingNotification) {
            return;
        }
        mLaunchingNotification = launching;
        mLaunchingNotificationNeedsToBeClipped = mLaunchAnimationParams != null
                && (mLaunchAnimationParams.getStartRoundedTopClipping() > 0
                        || mLaunchAnimationParams.getParentStartRoundedTopClipping() > 0);
        if (!mLaunchingNotificationNeedsToBeClipped || !mLaunchingNotification) {
            mLaunchedNotificationClipPath.reset();
        }
        // When launching notifications, we're clipping the children individually instead of in
        // dispatchDraw
        invalidate();
    }

    /**
     * Should we use rounded rect clipping
     */
    private void updateUseRoundedRectClipping() {
        // We don't want to clip notifications when QS is expanded, because incoming heads up on
        // the bottom would be clipped otherwise
        boolean qsAllowsClipping = mQsExpansionFraction < 0.5f || mShouldUseSplitNotificationShade;
        boolean clip = mIsExpanded && qsAllowsClipping;
        if (clip != mShouldUseRoundedRectClipping) {
            mShouldUseRoundedRectClipping = clip;
            invalidate();
        }
    }

    /**
     * Update the clip path for launched notifications in case they were originally clipped
     */
    private void updateLaunchedNotificationClipPath() {
        if (!mLaunchingNotificationNeedsToBeClipped || !mLaunchingNotification
                || mExpandingNotificationRow == null) {
            return;
        }
        int left = Math.min(mLaunchAnimationParams.getLeft(), mRoundedRectClippingLeft);
        int right = Math.max(mLaunchAnimationParams.getRight(), mRoundedRectClippingRight);
        int bottom = Math.max(mLaunchAnimationParams.getBottom(), mRoundedRectClippingBottom);
        float expandProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                mLaunchAnimationParams.getProgress(0,
                        NotificationLaunchAnimatorController.ANIMATION_DURATION_TOP_ROUNDING));
        int top = (int) Math.min(MathUtils.lerp(mRoundedRectClippingTop,
                mLaunchAnimationParams.getTop(), expandProgress),
                mRoundedRectClippingTop);
        float topRadius = mLaunchAnimationParams.getTopCornerRadius();
        float bottomRadius = mLaunchAnimationParams.getBottomCornerRadius();
        mLaunchedNotificationRadii[0] = topRadius;
        mLaunchedNotificationRadii[1] = topRadius;
        mLaunchedNotificationRadii[2] = topRadius;
        mLaunchedNotificationRadii[3] = topRadius;
        mLaunchedNotificationRadii[4] = bottomRadius;
        mLaunchedNotificationRadii[5] = bottomRadius;
        mLaunchedNotificationRadii[6] = bottomRadius;
        mLaunchedNotificationRadii[7] = bottomRadius;
        mLaunchedNotificationClipPath.reset();
        mLaunchedNotificationClipPath.addRoundRect(left, top, right, bottom,
                mLaunchedNotificationRadii, Path.Direction.CW);
        // Offset into notification clip coordinates instead of parent ones.
        // This is needed since the notification changes in translationZ, where clipping via
        // canvas dispatching won't work.
        ExpandableNotificationRow expandingRow = mExpandingNotificationRow;
        if (expandingRow.getNotificationParent() != null) {
            expandingRow = expandingRow.getNotificationParent();
        }
        mLaunchedNotificationClipPath.offset(
                -expandingRow.getLeft() - expandingRow.getTranslationX(),
                -expandingRow.getTop() - expandingRow.getTranslationY());
        expandingRow.setExpandingClipPath(mLaunchedNotificationClipPath);
        if (mShouldUseRoundedRectClipping) {
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mShouldUseRoundedRectClipping && !mLaunchingNotification) {
            // When launching notifications, we're clipping the children individually instead of in
            // dispatchDraw
            // Let's clip rounded.
            canvas.clipPath(mRoundedClipPath);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (mShouldUseRoundedRectClipping && mLaunchingNotification) {
            // Let's clip children individually during notification launch
            canvas.save();
            ExpandableView expandableView = (ExpandableView) child;
            Path clipPath;
            if (expandableView.isExpandAnimationRunning()
                    || ((ExpandableView) child).hasExpandingChild()) {
                // When launching the notification, it is not clipped by this layout, but by the
                // view itself. This is because the view is Translating in Z, where this clipPath
                // wouldn't apply.
                clipPath = null;
            } else {
                clipPath = mRoundedClipPath;
            }
            if (clipPath != null) {
                canvas.clipPath(clipPath);
            }
            boolean result = super.drawChild(canvas, child, drawingTime);
            canvas.restore();
            return result;
        } else {
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    /**
     * Calculate the total translation needed when dismissing.
     */
    public float getTotalTranslationLength(View animView) {
        if (!mDismissUsingRowTranslationX) {
            return animView.getMeasuredWidth();
        }
        float notificationWidth = animView.getMeasuredWidth();
        int containerWidth = getMeasuredWidth();
        float padding = (containerWidth - notificationWidth) / 2.0f;
        return containerWidth - padding;
    }

    /**
     * @return the start location where we start clipping notifications.
     */
    public int getTopClippingStartLocation() {
        return mIsExpanded ? mQsScrollBoundaryPosition : 0;
    }

    /**
     * Request an animation whenever the toppadding changes next
     */
    public void animateNextTopPaddingChange() {
        mAnimateNextTopPaddingChange = true;
    }

    /**
     * Sets whether the current user is set up, which is required to show the footer (b/193149550)
     */
    public void setCurrentUserSetup(boolean isCurrentUserSetup) {
        if (mIsCurrentUserSetup != isCurrentUserSetup) {
            mIsCurrentUserSetup = isCurrentUserSetup;
            updateFooter();
        }
    }

    /**
     * A listener that is notified when the empty space below the notifications is clicked on
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public interface OnEmptySpaceClickListener {
        void onEmptySpaceClicked(float x, float y);
    }

    /**
     * A listener that gets notified when the overscroll at the top has changed.
     */
    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    public interface OnOverscrollTopChangedListener {

    /**
     * Notifies a listener that the overscroll has changed.
     *
     * @param amount         the amount of overscroll, in pixels
     * @param isRubberbanded if true, this is a rubberbanded overscroll; if false, this is an
     *                       unrubberbanded motion to directly expand overscroll view (e.g
     *                       expand
     *                       QS)
     */
    void onOverscrollTopChanged(float amount, boolean isRubberbanded);

    /**
     * Notify a listener that the scroller wants to escape from the scrolling motion and
     * start a fling animation to the expanded or collapsed overscroll view (e.g expand the QS)
     *
     * @param velocity The velocity that the Scroller had when over flinging
     * @param open     Should the fling open or close the overscroll view.
     */
    void flingTopOverscroll(float velocity, boolean open);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void updateSpeedBumpIndex() {
        mSpeedBumpIndexDirty = true;
    }

    /** Updates the indices of the boundaries between sections. */
    @ShadeViewRefactor(RefactorComponent.INPUT)
    public void updateSectionBoundaries(String reason) {
        mSectionsManager.updateSectionBoundaries(reason);
    }

    void updateContinuousBackgroundDrawing() {
        boolean continuousBackground = !mAmbientState.isFullyAwake()
                && mSwipeHelper.isSwiping();
        if (continuousBackground != mContinuousBackgroundUpdate) {
            mContinuousBackgroundUpdate = continuousBackground;
            if (continuousBackground) {
                getViewTreeObserver().addOnPreDrawListener(mBackgroundUpdater);
            } else {
                getViewTreeObserver().removeOnPreDrawListener(mBackgroundUpdater);
            }
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    void updateContinuousShadowDrawing() {
        boolean continuousShadowUpdate = mAnimationRunning
                || mSwipeHelper.isSwiping();
        if (continuousShadowUpdate != mContinuousShadowUpdate) {
            if (continuousShadowUpdate) {
                getViewTreeObserver().addOnPreDrawListener(mShadowUpdater);
            } else {
                getViewTreeObserver().removeOnPreDrawListener(mShadowUpdater);
            }
            mContinuousShadowUpdate = continuousShadowUpdate;
        }
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private void resetExposedMenuView(boolean animate, boolean force) {
        mSwipeHelper.resetExposedMenuView(animate, force);
    }

    static boolean matchesSelection(
            ExpandableNotificationRow row,
            @SelectedRows int selection) {
        switch (selection) {
            case ROWS_ALL:
                return true;
            case ROWS_HIGH_PRIORITY:
                return row.getEntry().getBucket() < BUCKET_SILENT;
            case ROWS_GENTLE:
                return row.getEntry().getBucket() == BUCKET_SILENT;
            default:
                throw new IllegalArgumentException("Unknown selection: " + selection);
        }
    }

    @ShadeViewRefactor(RefactorComponent.STATE_RESOLVER)
    static class AnimationEvent {

        static AnimationFilter[] FILTERS = new AnimationFilter[]{

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
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_TOP_PADDING_CHANGED
                new AnimationFilter()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateDimmed()
                        .animateZ(),

                // ANIMATION_TYPE_ACTIVATED_CHILD
                new AnimationFilter()
                        .animateZ(),

                // ANIMATION_TYPE_DIMMED
                new AnimationFilter()
                        .animateDimmed(),

                // ANIMATION_TYPE_CHANGE_POSITION
                new AnimationFilter()
                        .animateAlpha() // maybe the children change positions
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_GO_TO_FULL_SHADE
                new AnimationFilter()
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
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_GROUP_EXPANSION_CHANGED
                new AnimationFilter()
                        .animateAlpha()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_HEADS_UP_APPEAR
                new AnimationFilter()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_HEADS_UP_DISAPPEAR
                new AnimationFilter()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                new AnimationFilter()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ()
                        .hasDelays(),

                // ANIMATION_TYPE_HEADS_UP_OTHER
                new AnimationFilter()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),

                // ANIMATION_TYPE_EVERYTHING
                new AnimationFilter()
                        .animateAlpha()
                        .animateDimmed()
                        .animateHideSensitive()
                        .animateHeight()
                        .animateTopInset()
                        .animateY()
                        .animateZ(),
        };

        static int[] LENGTHS = new int[]{

                // ANIMATION_TYPE_ADD
                StackStateAnimator.ANIMATION_DURATION_APPEAR_DISAPPEAR,

                // ANIMATION_TYPE_REMOVE
                StackStateAnimator.ANIMATION_DURATION_APPEAR_DISAPPEAR,

                // ANIMATION_TYPE_REMOVE_SWIPED_OUT
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_TOP_PADDING_CHANGED
                StackStateAnimator.ANIMATION_DURATION_STANDARD,

                // ANIMATION_TYPE_ACTIVATED_CHILD
                StackStateAnimator.ANIMATION_DURATION_DIMMED_ACTIVATED,

                // ANIMATION_TYPE_DIMMED
                StackStateAnimator.ANIMATION_DURATION_DIMMED_ACTIVATED,

                // ANIMATION_TYPE_CHANGE_POSITION
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
        static final int ANIMATION_TYPE_ACTIVATED_CHILD = 4;
        static final int ANIMATION_TYPE_DIMMED = 5;
        static final int ANIMATION_TYPE_CHANGE_POSITION = 6;
        static final int ANIMATION_TYPE_GO_TO_FULL_SHADE = 7;
        static final int ANIMATION_TYPE_HIDE_SENSITIVE = 8;
        static final int ANIMATION_TYPE_VIEW_RESIZE = 9;
        static final int ANIMATION_TYPE_GROUP_EXPANSION_CHANGED = 10;
        static final int ANIMATION_TYPE_HEADS_UP_APPEAR = 11;
        static final int ANIMATION_TYPE_HEADS_UP_DISAPPEAR = 12;
        static final int ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK = 13;
        static final int ANIMATION_TYPE_HEADS_UP_OTHER = 14;
        static final int ANIMATION_TYPE_EVERYTHING = 15;

        final long eventStartTime;
        final ExpandableView mChangingView;
        final int animationType;
        final AnimationFilter filter;
        final long length;
        View viewAfterChangingView;
        boolean headsUpFromBottom;

        AnimationEvent(ExpandableView view, int type) {
            this(view, type, LENGTHS[type]);
        }

        AnimationEvent(ExpandableView view, int type, AnimationFilter filter) {
            this(view, type, LENGTHS[type], filter);
        }

        AnimationEvent(ExpandableView view, int type, long length) {
            this(view, type, length, FILTERS[type]);
        }

        AnimationEvent(ExpandableView view, int type, long length, AnimationFilter filter) {
            eventStartTime = AnimationUtils.currentAnimationTimeMillis();
            mChangingView = view;
            animationType = type;
            this.length = length;
            this.filter = filter;
        }

        /**
         * Combines the length of several animation events into a single value.
         *
         * @param events The events of the lengths to combine.
         * @return The combined length. Depending on the event types, this might be the maximum of
         * all events or the length of a specific event.
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

    static boolean canChildBeDismissed(View v) {
        if (v instanceof ExpandableNotificationRow) {
            ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            if (row.isBlockingHelperShowingAndTranslationFinished()) {
                return true;
            }
            if (row.areGutsExposed() || !row.getEntry().hasFinishedInitialization()) {
                return false;
            }
            return row.canViewBeDismissed();
        }
        if (v instanceof PeopleHubView) {
            return ((PeopleHubView) v).getCanSwipe();
        }
        return false;
    }

    // --------------------- NotificationEntryManager/NotifPipeline methods ------------------------

    void onEntryUpdated(NotificationEntry entry) {
        // If the row already exists, the user may have performed a dismiss action on the
        // notification. Since it's not clearable we should snap it back.
        if (entry.rowExists() && !entry.getSbn().isClearable()) {
            snapViewIfNeeded(entry);
        }
    }

    /**
     * Called after the animations for a "clear all notifications" action has ended.
     */
    private void onDismissAllAnimationsEnd(
            List<ExpandableNotificationRow> viewsToRemove,
            @SelectedRows int selectedRows) {
        if (mDismissAllAnimationListener != null) {
            mDismissAllAnimationListener.onAnimationEnd(viewsToRemove, selectedRows);
        }
    }

    void resetCheckSnoozeLeavebehind() {
        setCheckForLeaveBehind(true);
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private final HeadsUpTouchHelper.Callback mHeadsUpCallback = new HeadsUpTouchHelper.Callback() {
        @Override
        public ExpandableView getChildAtRawPosition(float touchX, float touchY) {
            return NotificationStackScrollLayout.this.getChildAtRawPosition(touchX, touchY);
        }

        @Override
        public boolean isExpanded() {
            return mIsExpanded;
        }

        @Override
        public Context getContext() {
            return mContext;
        }
    };

    public HeadsUpTouchHelper.Callback getHeadsUpCallback() { return mHeadsUpCallback; }

    void onGroupExpandChanged(ExpandableNotificationRow changedRow, boolean expanded) {
        boolean animated = mAnimationsEnabled && (mIsExpanded || changedRow.isPinned());
        if (animated) {
            mExpandedGroupView = changedRow;
            mNeedsAnimation = true;
        }
        changedRow.setChildrenExpanded(expanded, animated);
        onChildHeightChanged(changedRow, false /* needsAnimation */);

        runAfterAnimationFinished(new Runnable() {
            @Override
            public void run() {
                changedRow.onFinishedExpansionChange();
            }
        });
    }

    @ShadeViewRefactor(RefactorComponent.SHADE_VIEW)
    private ExpandHelper.Callback mExpandHelperCallback = new ExpandHelper.Callback() {
        @Override
        public ExpandableView getChildAtPosition(float touchX, float touchY) {
            return NotificationStackScrollLayout.this.getChildAtPosition(touchX, touchY);
        }

        @Override
        public ExpandableView getChildAtRawPosition(float touchX, float touchY) {
            return NotificationStackScrollLayout.this.getChildAtRawPosition(touchX, touchY);
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
    };

    public ExpandHelper.Callback getExpandHelperCallback() {
        return mExpandHelperCallback;
    }

    /** Enum for selecting some or all notification rows (does not included non-notif views). */
    @Retention(SOURCE)
    @IntDef({ROWS_ALL, ROWS_HIGH_PRIORITY, ROWS_GENTLE})
    @interface SelectedRows {}
    /** All rows representing notifs. */
    public static final int ROWS_ALL = 0;
    /** Only rows where entry.isHighPriority() is true. */
    public static final int ROWS_HIGH_PRIORITY = 1;
    /** Only rows where entry.isHighPriority() is false. */
    public static final int ROWS_GENTLE = 2;

    interface KeyguardBypassEnabledProvider {
        boolean getBypassEnabled();
    }

    interface DismissListener {
        void onDismiss(@SelectedRows int selectedRows);
    }

    interface FooterDismissListener {
        void onDismiss();
    }

    interface DismissAllAnimationListener {
        void onAnimationEnd(
                List<ExpandableNotificationRow> viewsToRemove, @SelectedRows int selectedRows);
    }
}
