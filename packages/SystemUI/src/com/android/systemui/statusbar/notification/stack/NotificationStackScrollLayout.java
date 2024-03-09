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

import static android.os.Trace.TRACE_TAG_APP;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_SCROLL_FLING;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_SHADE_CLEAR_ALL;
import static com.android.systemui.Flags.newAodTransition;
import static com.android.systemui.flags.Flags.UNCLEARED_TRANSIENT_HUN_FIX;
import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_SILENT;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_SWIPE;
import static com.android.systemui.util.DumpUtilsKt.println;
import static com.android.systemui.util.DumpUtilsKt.visibilityString;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Trace;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
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
import android.view.WindowInsetsAnimation;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.OverScroller;
import android.widget.ScrollView;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.policy.SystemBarUtils;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.keyguard.KeyguardSliceView;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.ExpandHelper;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.flags.RefactorFlag;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.TouchLogger;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.LaunchAnimationParameters;
import com.android.systemui.statusbar.notification.NotificationTransitionAnimatorController;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.notification.shared.NotificationsImprovedHunAnimation;
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.ScrollAdapter;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.util.Assert;
import com.android.systemui.util.ColorUtilKt;
import com.android.systemui.util.Compile;
import com.android.systemui.util.DumpUtilsKt;

import com.google.errorprone.annotations.CompileTimeConstant;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A layout which handles a dynamic amount of notifications and presents them in a scrollable stack.
 */
public class NotificationStackScrollLayout extends ViewGroup implements Dumpable {

    public static final float BACKGROUND_ALPHA_DIMMED = 0.7f;
    private static final String TAG = "StackScroller";
    private static final boolean SPEW = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG_UPDATE_SIDE_PADDING = Compile.IS_DEBUG;

    private boolean mShadeNeedsToClose = false;

    @VisibleForTesting
    static final float RUBBER_BAND_FACTOR_NORMAL = 0.1f;
    private static final float RUBBER_BAND_FACTOR_AFTER_EXPAND = 0.15f;
    private static final float RUBBER_BAND_FACTOR_ON_PANEL_EXPAND = 0.21f;
    /**
     * Sentinel value for no current active pointer. Used by {@link #mActivePointerId}.
     */
    private static final int INVALID_POINTER = -1;
    private boolean mKeyguardBypassEnabled;

    private final ExpandHelper mExpandHelper;
    private NotificationSwipeHelper mSwipeHelper;
    private int mCurrentStackHeight = Integer.MAX_VALUE;
    private boolean mHighPriorityBeforeSpeedBump;

    private float mExpandedHeight;
    private int mOwnScrollY;
    private int mMaxLayoutHeight;

    private VelocityTracker mVelocityTracker;
    private OverScroller mScroller;

    private Runnable mFinishScrollingCallback;
    private int mTouchSlop;
    private float mSlopMultiplier;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mOverflingDistance;
    private float mMaxOverScroll;
    private boolean mIsBeingDragged;
    private boolean mSendingTouchesToSceneFramework;
    private int mLastMotionY;
    private int mDownX;
    private int mActivePointerId = INVALID_POINTER;
    private boolean mTouchIsClick;
    private float mInitialTouchX;
    private float mInitialTouchY;

    private final boolean mDebugLines;
    private Paint mDebugPaint;
    /**
     * Used to track the Y positions that were already used to draw debug text labels.
     */
    private Set<Integer> mDebugTextUsedYPositions;
    private final boolean mDebugRemoveAnimation;
    private final boolean mSensitiveRevealAnimEndabled;
    private final RefactorFlag mAnimatedInsets;
    private int mContentHeight;
    private float mIntrinsicContentHeight;
    private int mPaddingBetweenElements;
    private int mMaxTopPadding;
    private int mTopPadding;
    private boolean mAnimateNextTopPaddingChange;
    private int mBottomPadding;
    @VisibleForTesting
    int mBottomInset = 0;
    private float mQsExpansionFraction;
    private final int mSplitShadeMinContentHeight;
    private String mLastUpdateSidePaddingDumpString;

    /**
     * The algorithm which calculates the properties for our children
     */
    private final StackScrollAlgorithm mStackScrollAlgorithm;
    private final AmbientState mAmbientState;

    private final GroupMembershipManager mGroupMembershipManager;
    private final GroupExpansionManager mGroupExpansionManager;
    private final HashSet<ExpandableView> mChildrenToAddAnimated = new HashSet<>();
    private final ArrayList<View> mAddedHeadsUpChildren = new ArrayList<>();
    private final ArrayList<ExpandableView> mChildrenToRemoveAnimated = new ArrayList<>();
    private final ArrayList<ExpandableView> mChildrenChangingPositions = new ArrayList<>();
    private final HashSet<View> mFromMoreCardAdditions = new HashSet<>();
    private final ArrayList<AnimationEvent> mAnimationEvents = new ArrayList<>();
    private final ArrayList<View> mSwipedOutViews = new ArrayList<>();
    private NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    private final StackStateAnimator mStateAnimator;
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
    private OnNotificationLocationsChangedListener mLocationsChangedListener;
    private OnOverscrollTopChangedListener mOverscrollTopChangedListener;
    private ExpandableView.OnHeightChangedListener mOnHeightChangedListener;
    private Runnable mOnHeightChangedRunnable;
    private OnEmptySpaceClickListener mOnEmptySpaceClickListener;
    private boolean mNeedsAnimation;
    private boolean mTopPaddingNeedsAnimation;
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
    private boolean mClearAllInProgress;
    private FooterClearAllListener mFooterClearAllListener;
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
    private final ViewTreeObserver.OnPreDrawListener mChildrenUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (SceneContainerFlag.isEnabled() && !mChildrenUpdateRequested) {
                getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
            updateForcedScroll();
            updateChildren();
            mChildrenUpdateRequested = false;
            getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        }
    };
    private NotificationStackScrollLogger mLogger;
    private Runnable mResetUserExpandedStatesRunnable;
    private ActivityStarter mActivityStarter;
    private final int[] mTempInt2 = new int[2];
    private final HashSet<Runnable> mAnimationFinishedRunnables = new HashSet<>();
    private final HashSet<ExpandableView> mClearTransientViewsWhenFinished = new HashSet<>();
    private final HashSet<Pair<ExpandableNotificationRow, Boolean>> mHeadsUpChangeAnimations
            = new HashSet<>();
    private boolean mForceNoOverlappingRendering;
    private final ArrayList<Pair<ExpandableNotificationRow, Boolean>> mTmpList = new ArrayList<>();
    private boolean mAnimationRunning;
    private final ViewTreeObserver.OnPreDrawListener mRunningAnimationUpdater
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            onPreDrawDuringAnimation();
            return true;
        }
    };
    private final NotificationSection[] mSections;
    private final ArrayList<ExpandableView> mTmpSortedChildren = new ArrayList<>();
    protected ViewGroup mQsHeader;
    // Rect of QsHeader. Kept as a field just to avoid creating a new one each time.
    private final Rect mQsHeaderBound = new Rect();
    private boolean mContinuousShadowUpdate;
    private final ViewTreeObserver.OnPreDrawListener mShadowUpdater = () -> {
        updateViewShadows();
        return true;
    };
    private final Comparator<ExpandableView> mViewPositionComparator = (view, otherView) -> {
        float endY = view.getTranslationY() + view.getActualHeight();
        float otherEndY = otherView.getTranslationY() + otherView.getActualHeight();
        // Return zero when the two notifications end at the same location
        return Float.compare(endY, otherEndY);
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

    private final Callable<Map<String, Integer>> collectVisibleLocationsCallable =
            new Callable<>() {
                @Override
                public Map<String, Integer> call() {
                    return collectVisibleNotificationLocations();
                }
            };

    private boolean mPulsing;
    private boolean mScrollable;
    private View mForcedScroll;
    private boolean mIsInsetAnimationRunning;

    private final WindowInsetsAnimation.Callback mInsetsCallback =
            new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {

                @Override
                public void onPrepare(WindowInsetsAnimation animation) {
                    mIsInsetAnimationRunning = true;
                }

                @Override
                public WindowInsets onProgress(WindowInsets windowInsets,
                        List<WindowInsetsAnimation> list) {
                    updateBottomInset(windowInsets);
                    return windowInsets;
                }

                @Override
                public void onEnd(WindowInsetsAnimation animation) {
                    mIsInsetAnimationRunning = false;
                }
            };

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

    /**
     * Indicates QS are full screen and pushing notifications out of the screen.
     * It's different from QS just being expanded as in split shade QS can be expanded and
     * still don't take full screen nor influence notifications.
     */
    private boolean mQsFullScreen;
    private boolean mForwardScrollable;
    private boolean mBackwardScrollable;
    private NotificationShelf mShelf;
    /**
     * Limits the number of visible notifications. The remaining are collapsed in the notification
     * shelf. -1 when there is no limit.
     */
    private int mMaxDisplayedNotifications = -1;
    private float mKeyguardBottomPadding = -1;
    @VisibleForTesting
    int mStatusBarHeight;
    private int mMinInteractionHeight;
    private final Rect mClipRect = new Rect();
    private boolean mIsClipped;
    private Rect mRequestedClipBounds;
    private boolean mInHeadsUpPinnedMode;
    private boolean mHeadsUpAnimatingAway;
    private int mStatusBarState;
    private int mUpcomingStatusBarState;
    private boolean mHeadsUpGoingAwayAnimationsAllowed = true;
    private final Runnable mReflingAndAnimateScroll = this::animateScroll;
    private int mCornerRadius;
    private int mMinimumPaddings;
    private int mQsTilePadding;
    private boolean mSkinnyNotifsInLandscape;
    private int mSidePaddings;
    private final Rect mBackgroundAnimationRect = new Rect();
    private final ArrayList<BiConsumer<Float, Float>> mExpandedHeightListeners = new ArrayList<>();
    private int mHeadsUpInset;

    /**
     * The position of the scroll boundary relative to this view. This is where the notifications
     * stop scrolling and will start to clip instead.
     */
    private int mQsScrollBoundaryPosition;
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private final Rect mTmpRect = new Rect();
    private ClearAllListener mClearAllListener;
    private ClearAllAnimationListener mClearAllAnimationListener;
    private Runnable mClearAllFinishedWhilePanelExpandedRunnable;
    private Consumer<Boolean> mOnStackYChanged;

    private Interpolator mHideXInterpolator = Interpolators.FAST_OUT_SLOW_IN;

    private final NotificationSectionsManager mSectionsManager;
    private boolean mAnimateBottomOnLayout;
    private float mLastSentAppear;
    private float mLastSentExpandedHeight;
    private boolean mWillExpand;
    private int mGapHeight;
    private boolean mIsRemoteInputActive;

    /**
     * The extra inset during the full shade transition
     */
    private float mExtraTopInsetForFullShadeTransition;

    private int mWaterfallTopInset;
    private NotificationStackScrollLayoutController mController;

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
    private final float[] mBgCornerRadii = new float[8];

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
    private LaunchAnimationParameters mLaunchAnimationParams;

    /**
     * Corner radii of the launched notification if it's clipped
     */
    private final float[] mLaunchedNotificationRadii = new float[8];

    /**
     * The notification that is being launched currently.
     */
    private ExpandableNotificationRow mExpandingNotificationRow;

    /**
     * Do notifications dismiss with normal transitioning
     */
    private boolean mDismissUsingRowTranslationX = true;
    private ExpandableNotificationRow mTopHeadsUpRow;
    private NotificationStackScrollLayoutController.TouchHandler mTouchHandler;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private boolean mShouldUseSplitNotificationShade;
    private boolean mShouldSkipTopPaddingAnimationAfterFold = false;
    private boolean mHasFilteredOutSeenNotifications;
    @Nullable private SplitShadeStateController mSplitShadeStateController = null;
    private boolean mIsSmallLandscapeLockscreenEnabled = false;

    /** Pass splitShadeStateController to view and update split shade */
    public void passSplitShadeStateController(SplitShadeStateController splitShadeStateController) {
        mSplitShadeStateController = splitShadeStateController;
        updateSplitNotificationShade();
    }
    private final FeatureFlags mFeatureFlags;

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

    private final NotificationEntry.OnSensitivityChangedListener
            mOnChildSensitivityChangedListener =
            new NotificationEntry.OnSensitivityChangedListener() {
                @Override
                public void onSensitivityChanged(NotificationEntry entry) {
                    if (mAnimationsEnabled) {
                        mHideSensitiveNeedsAnimation = true;
                        requestChildrenUpdate();
                    }
                }
            };

    private Consumer<Integer> mScrollListener;
    private final ScrollAdapter mScrollAdapter = new ScrollAdapter() {
        @Override
        public boolean isScrolledToTop() {
            if (SceneContainerFlag.isEnabled()) {
                return mController.isPlaceholderScrolledToTop();
            } else {
                return mOwnScrollY == 0;
            }
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

    @Nullable
    private OnClickListener mManageButtonClickListener;
    @Nullable
    private OnNotificationRemovedListener mOnNotificationRemovedListener;

    public NotificationStackScrollLayout(Context context, AttributeSet attrs) {
        super(context, attrs, 0, 0);
        Resources res = getResources();
        mFeatureFlags = Dependency.get(FeatureFlags.class);
        mIsSmallLandscapeLockscreenEnabled = mFeatureFlags.isEnabled(
                Flags.LOCKSCREEN_ENABLE_LANDSCAPE);
        mDebugLines = mFeatureFlags.isEnabled(Flags.NSSL_DEBUG_LINES);
        mDebugRemoveAnimation = mFeatureFlags.isEnabled(Flags.NSSL_DEBUG_REMOVE_ANIMATION);
        mSensitiveRevealAnimEndabled = mFeatureFlags.isEnabled(Flags.SENSITIVE_REVEAL_ANIM);
        mAnimatedInsets =
                new RefactorFlag(mFeatureFlags, Flags.ANIMATED_NOTIFICATION_SHADE_INSETS);
        mSectionsManager = Dependency.get(NotificationSectionsManager.class);
        mScreenOffAnimationController =
                Dependency.get(ScreenOffAnimationController.class);
        mSectionsManager.initialize(this);
        mSections = mSectionsManager.createSectionsForBuckets();

        mAmbientState = Dependency.get(AmbientState.class);
        int minHeight = res.getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = res.getDimensionPixelSize(R.dimen.notification_max_height);
        mSplitShadeMinContentHeight = res.getDimensionPixelSize(
                R.dimen.nssl_split_shade_min_content_height);
        mExpandHelper = new ExpandHelper(getContext(), mExpandHelperCallback,
                minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setScrollAdapter(mScrollAdapter);

        mStackScrollAlgorithm = createStackScrollAlgorithm(context);
        mStateAnimator = new StackStateAnimator(context, this);
        setOutlineProvider(mOutlineProvider);

        // We could set this whenever we 'requestChildUpdate' much like the viewTreeObserver, but
        // that adds a bunch of complexity, and drawing nothing isn't *that* expensive.
        boolean willDraw = SceneContainerFlag.isEnabled() || mDebugLines;
        setWillNotDraw(!willDraw);
        if (mDebugLines) {
            mDebugPaint = new Paint();
            mDebugPaint.setColor(0xffff0000);
            mDebugPaint.setStrokeWidth(2);
            mDebugPaint.setStyle(Paint.Style.STROKE);
            mDebugPaint.setTextSize(25f);
        }
        mGroupMembershipManager = Dependency.get(GroupMembershipManager.class);
        mGroupExpansionManager = Dependency.get(GroupExpansionManager.class);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (mAnimatedInsets.isEnabled()) {
            setWindowInsetsAnimationCallback(mInsetsCallback);
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
    protected void onFinishInflate() {
        super.onFinishInflate();

        inflateEmptyShadeView();
        if (!FooterViewRefactor.isEnabled()) {
            inflateFooterView();
        }
    }

    /**
     * Sets whether keyguard bypass is enabled. If true, this layout will be rendered in bypass
     * mode when it is on the keyguard.
     */
    public void setKeyguardBypassEnabled(boolean isEnabled) {
        mKeyguardBypassEnabled = isEnabled;
    }

    /**
     * @return the height at which we will wake up when pulsing
     */
    public float getWakeUpHeight() {
        ExpandableView firstChild = getFirstChildWithBackground();
        if (firstChild != null) {
            if (mKeyguardBypassEnabled) {
                return firstChild.getHeadsUpHeightWithoutHeader();
            } else {
                return firstChild.getCollapsedHeight();
            }
        }
        return 0f;
    }

    protected void setLogger(NotificationStackScrollLogger logger) {
        mLogger = logger;
    }

    public float getNotificationSquishinessFraction() {
        return mStackScrollAlgorithm.getNotificationSquishinessFraction(mAmbientState);
    }

    void reinflateViews() {
        if (!FooterViewRefactor.isEnabled()) {
            inflateFooterView();
            updateFooter();
        }
        inflateEmptyShadeView();
        mSectionsManager.reinflateViews();
    }

    public void setIsRemoteInputActive(boolean isActive) {
        FooterViewRefactor.assertInLegacyMode();
        mIsRemoteInputActive = isActive;
        updateFooter();
    }

    /** Setter for filtered notifs, to be removed with the FooterViewRefactor flag. */
    public void setHasFilteredOutSeenNotifications(boolean hasFilteredOutSeenNotifications) {
        FooterViewRefactor.assertInLegacyMode();
        mHasFilteredOutSeenNotifications = hasFilteredOutSeenNotifications;
    }

    @VisibleForTesting
    public void updateFooter() {
        FooterViewRefactor.assertInLegacyMode();
        if (mFooterView == null || mController == null) {
            return;
        }
        final boolean showHistory = mController.isHistoryEnabled();
        final boolean showDismissView = shouldShowDismissView();

        updateFooterView(shouldShowFooterView(showDismissView)/* visible */,
                showDismissView /* showDismissView */,
                showHistory/* showHistory */);
    }

    private boolean shouldShowDismissView() {
        FooterViewRefactor.assertInLegacyMode();
        return mController.hasActiveClearableNotifications(ROWS_ALL);
    }

    private boolean shouldShowFooterView(boolean showDismissView) {
        FooterViewRefactor.assertInLegacyMode();
        return (showDismissView || mController.getVisibleNotificationCount() > 0)
                && mIsCurrentUserSetup // see: b/193149550
                && !onKeyguard()
                && mUpcomingStatusBarState != StatusBarState.KEYGUARD
                // quick settings don't affect notifications when not in full screen
                && (mQsExpansionFraction != 1 || !mQsFullScreen)
                && !mScreenOffAnimationController.shouldHideNotificationsFooter()
                && !mIsRemoteInputActive;
    }

    public NotificationSwipeActionHelper getSwipeActionHelper() {
        return mSwipeHelper;
    }

    void updateBgColor() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ActivatableNotificationView activatableView) {
                activatableView.updateBackgroundColors();
            }
        }
    }

    private void onJustBeforeDraw() {
        if (SceneContainerFlag.isEnabled()) {
            if (mChildrenUpdateRequested) {
                updateForcedScroll();
                updateChildren();
                mChildrenUpdateRequested = false;
            }
        }
    }

    protected void onDraw(Canvas canvas) {
        onJustBeforeDraw();

        if (mDebugLines) {
            onDrawDebug(canvas);
        }
    }

    private void logHunSkippedForUnexpectedState(ExpandableNotificationRow enr,
                                                 boolean expected, boolean actual) {
        if (mLogger == null) return;
        mLogger.hunSkippedForUnexpectedState(enr.getEntry(), expected, actual);
    }

    private void logHunAnimationSkipped(ExpandableNotificationRow enr, String reason) {
        if (mLogger == null) return;
        mLogger.hunAnimationSkipped(enr.getEntry(), reason);
    }

    private void logHunAnimationEventAdded(ExpandableNotificationRow enr, int type) {
        if (mLogger == null) return;
        mLogger.hunAnimationEventAdded(enr.getEntry(), type);
    }

    private void onDrawDebug(Canvas canvas) {
        if (mDebugTextUsedYPositions == null) {
            mDebugTextUsedYPositions = new HashSet<>();
        } else {
            mDebugTextUsedYPositions.clear();
        }
        int y = 0;
        drawDebugInfo(canvas, y, Color.RED, /* label= */ "y = " + y);

        y = mTopPadding;
        drawDebugInfo(canvas, y, Color.RED, /* label= */ "mTopPadding = " + y);

        y = getLayoutHeight();
        drawDebugInfo(canvas, y, Color.YELLOW, /* label= */ "getLayoutHeight() = " + y);

        y = mMaxLayoutHeight;
        drawDebugInfo(canvas, y, Color.MAGENTA, /* label= */ "mMaxLayoutHeight = " + y);

        // The space between mTopPadding and mKeyguardBottomPadding determines the available space
        // for notifications on keyguard.
        if (mKeyguardBottomPadding >= 0) {
            y = getHeight() - (int) mKeyguardBottomPadding;
            drawDebugInfo(canvas, y, Color.RED,
                    /* label= */ "getHeight() - mKeyguardBottomPadding = " + y);
        }

        y = getHeight() - getEmptyBottomMargin();
        drawDebugInfo(canvas, y, Color.GREEN,
                /* label= */ "getHeight() - getEmptyBottomMargin() = " + y);

        y = (int) (mAmbientState.getStackY());
        drawDebugInfo(canvas, y, Color.CYAN, /* label= */ "mAmbientState.getStackY() = " + y);

        y = (int) (mAmbientState.getStackY() + mAmbientState.getStackHeight());
        drawDebugInfo(canvas, y, Color.LTGRAY,
                /* label= */ "mAmbientState.getStackY() + mAmbientState.getStackHeight() = " + y);

        y = (int) mAmbientState.getStackY() + mContentHeight;
        drawDebugInfo(canvas, y, Color.MAGENTA,
                /* label= */ "mAmbientState.getStackY() + mContentHeight = " + y);

        y = (int) (mAmbientState.getStackY() + mIntrinsicContentHeight);
        drawDebugInfo(canvas, y, Color.YELLOW,
                /* label= */ "mAmbientState.getStackY() + mIntrinsicContentHeight = " + y);

        drawDebugInfo(canvas, mRoundedRectClippingBottom, Color.DKGRAY,
                /* label= */ "mRoundedRectClippingBottom) = " + y);
    }

    private void drawDebugInfo(Canvas canvas, int y, int color, String label) {
        mDebugPaint.setColor(color);
        canvas.drawLine(/* startX= */ 0, /* startY= */ y, /* stopX= */ getWidth(), /* stopY= */ y,
                mDebugPaint);
        canvas.drawText(label, /* x= */ 0, /* y= */ computeDebugYTextPosition(y), mDebugPaint);
    }

    private int computeDebugYTextPosition(int lineY) {
        int textY = lineY;
        while (mDebugTextUsedYPositions.contains(textY)) {
            textY = (int) (textY + mDebugPaint.getTextSize());
        }
        mDebugTextUsedYPositions.add(textY);
        return textY;
    }

    private void reinitView() {
        initView(getContext(), mSwipeHelper, mNotificationStackSizeCalculator);
    }

    void initView(Context context, NotificationSwipeHelper swipeHelper,
                  NotificationStackSizeCalculator notificationStackSizeCalculator) {
        mScroller = new OverScroller(getContext());
        mSwipeHelper = swipeHelper;
        mNotificationStackSizeCalculator = notificationStackSizeCalculator;

        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setClipChildren(false);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverflingDistance = configuration.getScaledOverflingDistance();

        Resources res = context.getResources();
        boolean useSmallLandscapeLockscreenResources = mIsSmallLandscapeLockscreenEnabled
                && res.getBoolean(R.bool.is_small_screen_landscape);
        // TODO (b/293252410) remove condition here when flag is launched
        //  Instead update the config_skinnyNotifsInLandscape to be false whenever
        //  is_small_screen_landscape is true. Then, only use the config_skinnyNotifsInLandscape.
        if (useSmallLandscapeLockscreenResources) {
            mSkinnyNotifsInLandscape = false;
        } else {
            mSkinnyNotifsInLandscape = res.getBoolean(
                    R.bool.config_skinnyNotifsInLandscape);
        }
        mGapHeight = res.getDimensionPixelSize(R.dimen.notification_section_divider_height);
        mStackScrollAlgorithm.initView(context);
        mStateAnimator.initView(context);
        mAmbientState.reload(context);
        mPaddingBetweenElements = Math.max(1,
                res.getDimensionPixelSize(R.dimen.notification_divider_height));
        mMinTopOverScrollToEscape = res.getDimensionPixelSize(
                R.dimen.min_top_overscroll_to_qs);
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        mBottomPadding = res.getDimensionPixelSize(R.dimen.notification_panel_padding_bottom);
        mMinimumPaddings = res.getDimensionPixelSize(R.dimen.notification_side_paddings);
        mQsTilePadding = res.getDimensionPixelOffset(R.dimen.qs_tile_margin_horizontal);
        mSidePaddings = mMinimumPaddings;  // Updated in onMeasure by updateSidePadding()
        mMinInteractionHeight = res.getDimensionPixelSize(
                R.dimen.notification_min_interaction_height);
        mCornerRadius = res.getDimensionPixelSize(R.dimen.notification_corner_radius);
        mHeadsUpInset = mStatusBarHeight + res.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mQsScrollBoundaryPosition = SystemBarUtils.getQuickQsOffsetHeight(mContext);
    }

    void updateSidePadding(int viewWidth) {
        final int orientation = getResources().getConfiguration().orientation;

        mLastUpdateSidePaddingDumpString = "viewWidth=" + viewWidth
                + " skinnyNotifsInLandscape=" + mSkinnyNotifsInLandscape
                + " orientation=" + orientation;

        if (DEBUG_UPDATE_SIDE_PADDING) {
            Log.v(TAG, "updateSidePadding: " + mLastUpdateSidePaddingDumpString);
        }

        if (viewWidth == 0 || !mSkinnyNotifsInLandscape) {
            mSidePaddings = mMinimumPaddings;
            return;
        }

        // Portrait is easy, just use the dimen for paddings
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
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

    private void notifyHeightChangeListener(ExpandableView view) {
        notifyHeightChangeListener(view, false /* needsAnimation */);
    }

    private void notifyHeightChangeListener(ExpandableView view, boolean needsAnimation) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(view, needsAnimation);
        }

        if (mOnHeightChangedRunnable != null) {
            mOnHeightChangedRunnable.run();
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
                        || !(view instanceof ExpandableNotificationRow row)) {
                    continue;
                }
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

    private boolean mSuppressChildrenMeasureAndLayout = false;

    /**
     * Similar to {@link ViewGroup#suppressLayout} but still performs layout of
     * the container itself and suppresses only measure and layout calls to children.
     */
    public void suppressChildrenMeasureAndLayout(boolean suppress) {
        mSuppressChildrenMeasureAndLayout = suppress;

        if (!suppress) {
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection("NotificationStackScrollLayout#onMeasure");
        if (SPEW) {
            Log.d(TAG, "onMeasure("
                    + "widthMeasureSpec=" + MeasureSpec.toString(widthMeasureSpec) + ", "
                    + "heightMeasureSpec=" + MeasureSpec.toString(heightMeasureSpec) + ")");
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        updateSidePadding(width);

        if (mSuppressChildrenMeasureAndLayout) {
            Trace.endSection();
            return;
        }

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
        Trace.endSection();
    }

    @Override
    public void requestLayout() {
        Trace.instant(TRACE_TAG_APP, "NotificationStackScrollLayout#requestLayout");
        super.requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (!mSuppressChildrenMeasureAndLayout) {
            // we layout all our children centered on the top
            float centerX = getWidth() / 2.0f;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                // We need to layout all children even the GONE ones, such that the heights are
                // calculated correctly as they are used to calculate how many we can fit on
                // the screen
                float width = child.getMeasuredWidth();
                float height = child.getMeasuredHeight();
                child.layout((int) (centerX - width / 2.0f),
                        0,
                        (int) (centerX + width / 2.0f),
                        (int) height);
            }
        }
        setMaxLayoutHeight(getHeight());
        updateContentHeight();
        clampScrollPosition();
        requestChildrenUpdate();
        updateFirstAndLastBackgroundViews();
        updateAlgorithmLayoutMinHeight();
        updateOwnTranslationZ();

        // Give The Algorithm information regarding the QS height so it can layout notifications
        // properly. Needed for some devices that grows notifications down-to-top
        mStackScrollAlgorithm.updateQSFrameTop(mQsHeader == null ? 0 : mQsHeader.getHeight());

        // Once the layout has finished, we don't need to animate any scrolling clampings anymore.
        mAnimateStackYForContentHeightChange = false;
    }

    private void requestAnimationOnViewResize(ExpandableNotificationRow row) {
        if (mAnimationsEnabled && (mIsExpanded || row != null && row.isPinned())) {
            mNeedViewResizeAnimation = true;
            mNeedsAnimation = true;
        }
    }

    /**
     * @param listener to be notified after the location of Notification children might have
     *                 changed.
     */
    public void setNotificationLocationsChangedListener(
            @Nullable OnNotificationLocationsChangedListener listener) {
        if (NotificationsLiveDataStoreRefactor.isUnexpectedlyInLegacyMode()) {
            return;
        }
        mLocationsChangedListener = listener;
    }

    public void setChildLocationsChangedListener(
            NotificationLogger.OnChildLocationsChangedListener listener) {
        NotificationsLiveDataStoreRefactor.assertInLegacyMode();
        mListener = listener;
    }

    private void setMaxLayoutHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
        updateAlgorithmHeightAndPadding();
    }

    private void updateAlgorithmHeightAndPadding() {
        mAmbientState.setLayoutHeight(getLayoutHeight());
        mAmbientState.setLayoutMaxHeight(mMaxLayoutHeight);
        updateAlgorithmLayoutMinHeight();
        mAmbientState.setTopPadding(mTopPadding);
    }

    private void updateAlgorithmLayoutMinHeight() {
        mAmbientState.setLayoutMinHeight(mQsFullScreen || isHeadsUpTransition()
                ? getLayoutMinHeight() : 0);
    }

    /**
     * Updates the children views according to the stack scroll algorithm. Call this whenever
     * modifications to {@link #mOwnScrollY} are performed to reflect it in the view layout.
     */
    private void updateChildren() {
        Trace.beginSection("NSSL#updateChildren");
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
        Trace.endSection();
    }

    private void onPreDrawDuringAnimation() {
        mShelf.updateAppearance();
    }

    private void updateScrollStateForAddedChildren() {
        if (mChildrenToAddAnimated.isEmpty()) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = getChildAtIndex(i);
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

    void requestChildrenUpdate() {
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
        if (scrollRange < mOwnScrollY && !mAmbientState.isClearAllInProgress()) {
            // if the scroll boundary updates the position of the stack,
            boolean animateStackY = scrollRange < getScrollAmountToScrollBoundary()
                    && mAnimateStackYForContentHeightChange;
            setOwnScrollY(scrollRange, animateStackY);
        }
    }

    public int getTopPadding() {
        return mTopPadding;
    }

    private void setTopPadding(int topPadding, boolean animate) {
        if (mTopPadding != topPadding) {
            boolean shouldAnimate = animate || mAnimateNextTopPaddingChange;
            mTopPadding = topPadding;
            updateAlgorithmHeightAndPadding();
            updateContentHeight();
            if (mAmbientState.isOnKeyguard()
                    && !mShouldUseSplitNotificationShade
                    && mShouldSkipTopPaddingAnimationAfterFold) {
                mShouldSkipTopPaddingAnimationAfterFold = false;
            } else if (shouldAnimate && mAnimationsEnabled && mIsExpanded) {
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
     * @return Whether we should skip stack height updates.
     * True when
     * 1) Unlock hint is running
     * 2) Swiping up on lockscreen or flinging down after swipe up
     */
    private boolean shouldSkipHeightUpdate() {
        return mAmbientState.isOnKeyguard()
                && (mAmbientState.isSwipingUp()
                || mAmbientState.isFlingingAfterSwipeUpOnLockscreen());
    }

    /**
     * Apply expansion fraction to the y position and height of the notifications panel.
     *
     * @param listenerNeedsAnimation does the listener need to animate?
     */
    private void updateStackPosition(boolean listenerNeedsAnimation) {
        float topOverscrollAmount = mShouldUseSplitNotificationShade
                ? getCurrentOverScrollAmount(true /* top */) : 0f;
        final float endTopPosition = mTopPadding + mExtraTopInsetForFullShadeTransition
                + mAmbientState.getOverExpansion()
                + topOverscrollAmount
                - getCurrentOverScrollAmount(false /* top */);
        float fraction = mAmbientState.getExpansionFraction();
        // If we are on quick settings, we need to quickly hide it to show the bouncer to avoid an
        // overlap. Otherwise, we maintain the normal fraction for smoothness.
        if (mAmbientState.isBouncerInTransit() && mQsExpansionFraction > 0f) {
            fraction = BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(fraction);
        }
        // TODO(b/322228881): Clean up scene container vs legacy behavior in NSSL
        if (SceneContainerFlag.isEnabled()) {
            // stackY should be driven by scene container, not NSSL
            mAmbientState.setStackY(mTopPadding);
        } else {
            final float stackY = MathUtils.lerp(0, endTopPosition, fraction);
            mAmbientState.setStackY(stackY);
        }

        if (mOnStackYChanged != null) {
            mOnStackYChanged.accept(listenerNeedsAnimation);
        }
        updateStackEndHeightAndStackHeight(fraction);
    }

    @VisibleForTesting
    public void updateStackEndHeightAndStackHeight(float fraction) {
        final float oldStackHeight = mAmbientState.getStackHeight();
        if (mQsExpansionFraction <= 0 && !shouldSkipHeightUpdate()) {
            final float endHeight = updateStackEndHeight(
                    getHeight(), getEmptyBottomMargin(), mTopPadding);
            updateStackHeight(endHeight, fraction);
        } else {
            // Always updateStackHeight to prevent jumps in the stack height when this fraction
            // suddenly reapplies after a freeze.
            final float endHeight = mAmbientState.getStackEndHeight();
            updateStackHeight(endHeight, fraction);
        }
        if (oldStackHeight != mAmbientState.getStackHeight()) {
            requestChildrenUpdate();
        }
    }

    private float updateStackEndHeight(float height, float bottomMargin, float topPadding) {
        final float stackEndHeight;
        if (mMaxDisplayedNotifications != -1) {
            // The stack intrinsic height already contains the correct value when there is a limit
            // in the max number of notifications (e.g. as in keyguard).
            stackEndHeight = mIntrinsicContentHeight;
        } else {
            stackEndHeight = Math.max(0f, height - bottomMargin - topPadding);
        }
        mAmbientState.setStackEndHeight(stackEndHeight);
        return stackEndHeight;
    }

    @VisibleForTesting
    public void updateStackHeight(float endHeight, float fraction) {
        if (!newAodTransition()) {
            // During the (AOD<=>LS) transition where dozeAmount is changing,
            // apply dozeAmount to stack height instead of expansionFraction
            // to unfurl notifications on AOD=>LS wakeup (and furl up on LS=>AOD sleep)
            final float dozeAmount = mAmbientState.getDozeAmount();
            if (0f < dozeAmount && dozeAmount < 1f) {
                fraction = 1f - dozeAmount;
            }
        }
        mAmbientState.setStackHeight(
                MathUtils.lerp(endHeight * StackScrollAlgorithm.START_FRACTION,
                        endHeight, fraction));
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
    public void setExpandedHeight(float height) {
        final boolean skipHeightUpdate = shouldSkipHeightUpdate();

        // when scene framework is enabled, updateStackPosition is already called by
        // updateTopPadding every time the stack moves, so skip it here to avoid flickering.
        if (!SceneContainerFlag.isEnabled()) {
            updateStackPosition();
        }

        if (!skipHeightUpdate) {
            mExpandedHeight = height;
            setIsExpanded(height > 0);
            int minExpansionHeight = getMinExpansionHeight();
            if (height < minExpansionHeight && !mShouldUseSplitNotificationShade) {
                mClipRect.left = 0;
                mClipRect.right = getWidth();
                mClipRect.top = 0;
                mClipRect.bottom = (int) height;
                height = minExpansionHeight;
                setRequestedClipBounds(mClipRect);
            } else {
                setRequestedClipBounds(null);
            }
        }
        int stackHeight;
        float translationY;
        float appearFraction = 1.0f;
        boolean appearing = calculateAppearFraction(height) < 1;
        mAmbientState.setAppearing(appearing);
        if (!appearing) {
            translationY = 0;
            if (mShouldShowShelfOnly) {
                stackHeight = mTopPadding + mShelf.getIntrinsicHeight();
            } else if (mQsFullScreen) {
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
                stackHeight = (int) (skipHeightUpdate ? mExpandedHeight : height);
            }
        } else {
            appearFraction = calculateAppearFraction(height);
            if (appearFraction >= 0) {
                translationY = NotificationUtils.interpolate(getExpandTranslationStart(), 0,
                        appearFraction);
            } else {
                // This may happen when pushing up a heads up. We linearly push it up from the
                // start
                translationY = height - getAppearStartPosition() + getExpandTranslationStart();
            }
            stackHeight = (int) (height - translationY);
            if (isHeadsUpTransition() && appearFraction >= 0) {
                int topSpacing = mShouldUseSplitNotificationShade
                        ? mAmbientState.getStackTopMargin() : mTopPadding;
                float startPos = mHeadsUpInset - topSpacing;
                translationY = MathUtils.lerp(startPos, 0, appearFraction);
            }
        }
        mAmbientState.setAppearFraction(appearFraction);
        if (stackHeight != mCurrentStackHeight && !skipHeightUpdate) {
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
        if (mKeyguardBypassEnabled && onKeyguard()) {
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

    private void setRequestedClipBounds(Rect clipRect) {
        mRequestedClipBounds = clipRect;
        updateClipping();
    }

    /**
     * Return the height of the content ignoring the footer.
     */
    public int getIntrinsicContentHeight() {
        return (int) mIntrinsicContentHeight;
    }

    public void updateClipping() {
        boolean clipped = mRequestedClipBounds != null && !mInHeadsUpPinnedMode
                && !mHeadsUpAnimatingAway;
        if (mIsClipped != clipped) {
            mIsClipped = clipped;
        }

        if (mAmbientState.isHiddenAtAll()) {
            invalidateOutline();
            if (isFullyHidden()) {
                setClipBounds(null);
            }
        } else if (clipped) {
            setClipBounds(mRequestedClipBounds);
        } else {
            setClipBounds(null);
        }

        setClipToOutline(false);
    }

    /**
     * @return The translation at the beginning when expanding.
     * Measured relative to the resting position.
     */
    private float getExpandTranslationStart() {
        return -mTopPadding + getMinExpansionHeight() - mShelf.getIntrinsicHeight();
    }

    /**
     * @return the position from where the appear transition starts when expanding.
     * Measured in absolute height.
     */
    private float getAppearStartPosition() {
        if (isHeadsUpTransition()) {
            final NotificationSection firstVisibleSection = getFirstVisibleSection();
            final int pinnedHeight = firstVisibleSection != null
                    ? firstVisibleSection.getFirstVisibleChild().getPinnedHeadsUpHeight()
                    : 0;
            return mHeadsUpInset - mAmbientState.getStackTopMargin() + pinnedHeight;
        }
        return getMinExpansionHeight();
    }

    /**
     * @return the height of the top heads up notification when pinned. This is different from the
     * intrinsic height, which also includes whether the notification is system expanded and
     * is mainly used when dragging down from a heads up notification.
     */
    private int getTopHeadsUpPinnedHeight() {
        if (mTopHeadsUpRow == null) {
            return 0;
        }
        ExpandableNotificationRow row = mTopHeadsUpRow;
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
     *
     * TODO(b/308591475): This entire logic can probably be improved as part of the empty shade
     *  refactor, but for now:
     *  - if the empty shade is visible, we can assume that the visible notif count is not 0;
     *  - if the shelf is visible, we can assume there are at least 2 notifications present (this
     *    is not true for AOD, but this logic refers to the expansion of the shade, where we never
     *    have the shelf on its own)
     */
    private float getAppearEndPosition() {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            return getAppearEndPositionLegacy();
        }

        int appearPosition = mAmbientState.getStackTopMargin();
        if (mEmptyShadeView.getVisibility() == GONE) {
            if (isHeadsUpTransition()
                    || (mInHeadsUpPinnedMode && !mAmbientState.isDozing())) {
                if (mShelf.getVisibility() != GONE) {
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

    /**
     * The version of {@code getAppearEndPosition} that uses the notif count. The view shouldn't
     * need to know about that, so we want to phase this out with the footer view refactor.
     */
    private float getAppearEndPositionLegacy() {
        FooterViewRefactor.assertInLegacyMode();

        int appearPosition = mAmbientState.getStackTopMargin();
        int visibleNotifCount = mController.getVisibleNotificationCount();
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

    private boolean isHeadsUpTransition() {
        return mAmbientState.getTrackedHeadsUpRow() != null;
    }

    /**
     * @param height the height of the panel
     * @return Fraction of the appear animation that has been performed. Normally follows expansion
     * fraction so goes from 0 to 1, the only exception is HUN where it can go negative, down to -1,
     * when HUN is swiped up.
     */
    @FloatRange(from = -1.0, to = 1.0)
    public float calculateAppearFraction(float height) {
        if (isHeadsUpTransition()) {
            // HUN is a special case because fraction can go negative if swiping up. And for now
            // it must go negative as other pieces responsible for proper translation up assume
            // negative value for HUN going up.
            // This can't use expansion fraction as that goes only from 0 to 1. Also when
            // appear fraction for HUN is 0, expansion fraction will be already around 0.2-0.3
            // and that makes translation jump immediately.
            float appearEndPosition = FooterViewRefactor.isEnabled() ? getAppearEndPosition()
                    : getAppearEndPositionLegacy();
            float appearStartPosition = getAppearStartPosition();
            float hunAppearFraction = (height - appearStartPosition)
                    / (appearEndPosition - appearStartPosition);
            return MathUtils.constrain(hunAppearFraction, -1, 1);
        } else {
            return mAmbientState.getExpansionFraction();
        }
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

    public void setQsHeader(ViewGroup qsHeader) {
        mQsHeader = qsHeader;
    }

    public static boolean isPinnedHeadsUp(View v) {
        if (v instanceof ExpandableNotificationRow row) {
            return row.isHeadsUp() && row.isPinned();
        }
        return false;
    }

    private boolean isHeadsUp(View v) {
        if (v instanceof ExpandableNotificationRow row) {
            return row.isHeadsUp();
        }
        return false;
    }

    private ExpandableView getChildAtPosition(float touchX, float touchY) {
        return getChildAtPosition(touchX, touchY, true /* requireMinHeight */,
                true /* ignoreDecors */, true /* ignoreWidth */);
    }

    /**
     * Get the child at a certain screen location.
     *
     * @param touchX           the x coordinate
     * @param touchY           the y coordinate
     * @param requireMinHeight Whether a minimum height is required for a child to be returned.
     * @param ignoreDecors     Whether decors can be returned
     * @param ignoreWidth      Whether we should ignore the width of the child
     * @return the child at the given location.
     */
    ExpandableView getChildAtPosition(float touchX, float touchY, boolean requireMinHeight,
            boolean ignoreDecors, boolean ignoreWidth) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView slidingChild = getChildAtIndex(childIdx);
            if (slidingChild.getVisibility() != VISIBLE
                    || (ignoreDecors && slidingChild instanceof StackScrollerDecorView)) {
                continue;
            }
            float childTop = slidingChild.getTranslationY();
            float top = childTop + Math.max(0, slidingChild.getClipTopAmount());
            float bottom = childTop + slidingChild.getActualHeight()
                    - slidingChild.getClipBottomAmount();

            // Allow the full width of this view to prevent gesture conflict on Keyguard (phone and
            // camera affordance).
            int left = ignoreWidth ? 0 : slidingChild.getLeft();
            int right = ignoreWidth ? getWidth() : slidingChild.getRight();

            if ((bottom - top >= mMinInteractionHeight || !requireMinHeight)
                    && touchY >= top && touchY <= bottom && touchX >= left && touchX <= right) {
                if (slidingChild instanceof ExpandableNotificationRow row) {
                    NotificationEntry entry = row.getEntry();
                    if (!mIsExpanded && row.isHeadsUp() && row.isPinned()
                            && mTopHeadsUpRow != row
                            && mGroupMembershipManager.getGroupSummary(mTopHeadsUpRow.getEntry())
                            != entry) {
                        continue;
                    }
                    return row.getViewAtPosition(touchY - childTop);
                }
                return slidingChild;
            }
        }
        return null;
    }

    private ExpandableView getChildAtIndex(int index) {
        return (ExpandableView) getChildAt(index);
    }

    public ExpandableView getChildAtRawPosition(float touchX, float touchY) {
        getLocationOnScreen(mTempInt2);
        return getChildAtPosition(touchX - mTempInt2[0], touchY - mTempInt2[1]);
    }

    public void setScrollingEnabled(boolean enable) {
        mScrollingEnabled = enable;
    }

    public void lockScrollTo(View v) {
        if (mForcedScroll == v) {
            return;
        }
        mForcedScroll = v;
        if (mAnimatedInsets.isEnabled()) {
            updateForcedScroll();
        } else {
            scrollTo(v);
        }
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
     * the IME.
     */
    private int targetScrollForView(ExpandableView v, int positionInLinearLayout) {
        return positionInLinearLayout + v.getIntrinsicHeight() +
                getImeInset() - getHeight()
                + ((!isExpanded() && isPinnedHeadsUp(v)) ? mHeadsUpInset : getTopPadding());
    }

    private void updateBottomInset(WindowInsets windowInsets) {
        mBottomInset = windowInsets.getInsets(WindowInsets.Type.ime()).bottom;

        if (mForcedScroll != null) {
            updateForcedScroll();
        }

        int range = getScrollRange();
        if (mOwnScrollY > range) {
            setOwnScrollY(range);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (!mAnimatedInsets.isEnabled()) {
            mBottomInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
        }
        mWaterfallTopInset = 0;
        final DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout != null) {
            mWaterfallTopInset = cutout.getWaterfallInsets().top;
        }
        if (mAnimatedInsets.isEnabled() && !mIsInsetAnimationRunning) {
            // update bottom inset e.g. after rotation
            updateBottomInset(insets);
        }
        if (!mAnimatedInsets.isEnabled()) {
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
        }
        return insets;
    }

    private final Runnable mReclamp = new Runnable() {
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

    boolean onKeyguard() {
        return mStatusBarState == StatusBarState.KEYGUARD;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Resources res = getResources();
        updateSplitNotificationShade();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        float densityScale = res.getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
        reinitView();
    }

    public void dismissViewAnimated(
            View child, Consumer<Boolean> endRunnable, int delay, long duration) {
        if (child instanceof SectionHeaderView) {
            ((StackScrollerDecorView) child).setContentVisible(
                    false /* visible */, true /* animate */, endRunnable);
            return;
        }
        mSwipeHelper.dismissChild(
                child,
                0 /* velocity */,
                endRunnable,
                delay,
                true /* useAccelerateInterpolator */,
                duration,
                true /* isClearAll */);
    }

    private void snapViewIfNeeded(NotificationEntry entry) {
        ExpandableNotificationRow child = entry.getRow();
        boolean animate = mIsExpanded || isPinnedHeadsUp(child);
        // If the child is showing the notification menu snap to that
        if (child.getProvider() != null) {
            float targetLeft = child.getProvider().isMenuVisible() ? child.getTranslation() : 0;
            mSwipeHelper.snapChildIfNeeded(child, animate, targetLeft);
        }
    }

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
     * <p>
     * This method performs the determination of whether we're exceeding the overscroll and clamps
     * the scroll amount if so.  The actual scrolling/overscrolling happens in
     * {@link #onCustomOverScrolled(int, boolean)}
     *
     * @param deltaY         The (signed) number of pixels to scroll.
     * @param scrollY        The current scroll position (absolute scrolling only).
     * @param scrollRangeY   The maximum allowable scroll position (absolute scrolling only).
     * @param maxOverScrollY The current (unsigned) limit on number of pixels to overscroll by.
     */
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
            updateStackPosition();
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
        return top ? mOverScrolledTopPixels : mOverScrolledBottomPixels;
    }

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
     * @param scrollY  The target scroll position.
     * @param clampedY Whether this value was clamped by the calling method, meaning we've reached
     *                 the overscroll limit.
     */
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

    private int getImeInset() {
        // The NotificationStackScrollLayout does not extend all the way to the bottom of the
        // display. Therefore, subtract that space from the mBottomInset, in order to only include
        // the portion of the bottom inset that actually overlaps the NotificationStackScrollLayout.
        return Math.max(0, mBottomInset
                - (getRootView().getHeight() - getHeight() - getLocationOnScreen()[1]));
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
     * @return The first child which has visibility unequal to GONE which is currently below the
     * given translationY or equal to it.
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
            } else if (!ignoreChildren && child instanceof ExpandableNotificationRow row) {
                if (row.isSummaryWithChildren() && row.areChildrenExpanded()) {
                    List<ExpandableNotificationRow> notificationChildren =
                            row.getAttachedChildren();
                    int childrenSize = notificationChildren.size();
                    for (int childIndex = 0; childIndex < childrenSize; childIndex++) {
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
    public int getNotGoneChildCount() {
        int childCount = getChildCount();
        int count = 0;
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = getChildAtIndex(i);
            if (child.getVisibility() != View.GONE && !child.willBeGone() && child != mShelf) {
                count++;
            }
        }
        return count;
    }

    private void updateContentHeight() {
        final float scrimTopPadding = mAmbientState.isOnKeyguard() ? 0 : mMinimumPaddings;
        final int shelfIntrinsicHeight = mShelf != null ? mShelf.getIntrinsicHeight() : 0;
        final float height =
                (int) scrimTopPadding + (int) mNotificationStackSizeCalculator.computeHeight(
                        /* notificationStackScrollLayout= */ this, mMaxDisplayedNotifications,
                        shelfIntrinsicHeight);
        mIntrinsicContentHeight = height;
        mController.setIntrinsicContentHeight(mIntrinsicContentHeight);

        // The topPadding can be bigger than the regular padding when qs is expanded, in that
        // state the maxPanelHeight and the contentHeight should be bigger
        mContentHeight = (int) (height + Math.max(mIntrinsicPadding, mTopPadding) + mBottomPadding);
        updateScrollability();
        clampScrollPosition();
        updateStackPosition();
        mAmbientState.setContentHeight(mContentHeight);
    }

    /**
     * Calculate the gap height between two different views
     *
     * @param previous     the previousView
     * @param current      the currentView
     * @param visibleIndex the visible index in the list
     * @return the gap height needed before the current view
     */
    public float calculateGapHeight(
            ExpandableView previous,
            ExpandableView current,
            int visibleIndex
    ) {
        return mStackScrollAlgorithm.getGapHeightForChild(mSectionsManager, visibleIndex, current,
                previous, mAmbientState.getFractionToShade(), mAmbientState.isOnKeyguard());
    }

    public boolean hasPulsingNotifications() {
        return mPulsing;
    }

    private void updateScrollability() {
        boolean scrollable = !mQsFullScreen && getScrollRange() > 0;
        if (scrollable != mScrollable) {
            mScrollable = scrollable;
            setFocusable(scrollable);
            updateForwardAndBackwardScrollability();
        }
    }

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

    private ExpandableView getLastChildWithBackground() {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableView child = getChildAtIndex(i);
            if (child.getVisibility() != View.GONE && !(child instanceof StackScrollerDecorView)
                    && child != mShelf) {
                return child;
            }
        }
        return null;
    }

    private ExpandableView getFirstChildWithBackground() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            ExpandableView child = getChildAtIndex(i);
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
            ExpandableView child = getChildAtIndex(i);
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
    protected void fling(int velocityY) {
        if (getChildCount() > 0) {
            float topAmount = getCurrentOverScrollAmount(true);
            float bottomAmount = getCurrentOverScrollAmount(false);
            if (velocityY < 0 && topAmount > 0) {
                setOwnScrollY(mOwnScrollY - (int) topAmount);
                if (!mShouldUseSplitNotificationShade) {
                    mDontReportNextOverScroll = true;
                    setOverScrollAmount(0, true, false);
                }
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
    private boolean shouldOverScrollFling(int initialVelocity) {
        float topOverScroll = getCurrentOverScrollAmount(true);
        return mScrolledToTopOnFirstDown
                && !mExpandedInThisMotion
                && !mShouldUseSplitNotificationShade
                && (initialVelocity > mMinimumVelocity
                || (topOverScroll > mMinTopOverScrollToEscape && initialVelocity > 0));
    }

    /**
     * Updates the top padding of the notifications, taking {@link #getIntrinsicPadding()} into
     * account.
     *
     * @param qsHeight the top padding imposed by the quick settings panel
     * @param animate  whether to animate the change
     */
    public void updateTopPadding(float qsHeight, boolean animate) {
        int topPadding = (int) qsHeight;
        int minStackHeight = getLayoutMinHeight();
        if (topPadding + minStackHeight > getHeight()) {
            mTopPaddingOverflow = topPadding + minStackHeight - getHeight();
        } else {
            mTopPaddingOverflow = 0;
        }
        setTopPadding(topPadding, animate && !mKeyguardBypassEnabled);
        setExpandedHeight(mExpandedHeight);
    }

    public void setMaxTopPadding(int maxTopPadding) {
        mMaxTopPadding = maxTopPadding;
    }

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

    public float getTopPaddingOverflow() {
        return mTopPaddingOverflow;
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
        } else if (mScrolledToTopOnFirstDown && !mShouldUseSplitNotificationShade) {
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


    public void setChildTransferInProgress(boolean childTransferInProgress) {
        Assert.isMainThread();
        mChildTransferInProgress = childTransferInProgress;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        // we only call our internal methods if this is actually a removal and not just a
        // notification which becomes a child notification
        ExpandableView expandableView = (ExpandableView) child;
        if (!mChildTransferInProgress) {
            onViewRemovedInternal(expandableView, this);
        }
        mShelf.requestRoundnessResetFor(expandableView);
    }

    public void cleanUpViewStateForEntry(NotificationEntry entry) {
        View child = entry.getRow();
        if (child == mSwipeHelper.getTranslatingParentView()) {
            mSwipeHelper.clearTranslatingParentView();
        }
    }

    private void onViewRemovedInternal(ExpandableView child, ViewGroup container) {
        if (mChangePositionInProgress) {
            // This is only a position change, don't do anything special
            return;
        }
        child.setOnHeightChangedListener(null);
        if (child instanceof ExpandableNotificationRow && mSensitiveRevealAnimEndabled) {
            NotificationEntry entry = ((ExpandableNotificationRow) child).getEntry();
            entry.removeOnSensitivityChangedListener(mOnChildSensitivityChangedListener);
        }
        updateScrollStateForRemovedChild(child);
        boolean animationGenerated = container != null && generateRemoveAnimation(child);
        if (animationGenerated) {
            if (!mSwipedOutViews.contains(child) || !isFullySwipedOut(child)) {
                logAddTransientChild(child, container);
                child.addToTransientContainer(container, 0);
            }
        } else {
            mSwipedOutViews.remove(child);

            if (child instanceof ExpandableNotificationRow) {
                ((ExpandableNotificationRow) child).removeChildrenWithKeepInParent();
            }
        }
        updateAnimationState(false, child);

        focusNextViewIfFocused(child);
    }

    private void logAddTransientChild(ExpandableView child, ViewGroup container) {
        if (mLogger == null) {
            return;
        }
        if (child instanceof ExpandableNotificationRow) {
            if (container instanceof NotificationChildrenContainer) {
                mLogger.addTransientChildNotificationToChildContainer(
                        ((ExpandableNotificationRow) child).getEntry(),
                        ((NotificationChildrenContainer) container)
                                .getContainingNotification().getEntry()
                );
            } else if (container instanceof NotificationStackScrollLayout) {
                mLogger.addTransientChildNotificationToNssl(
                        ((ExpandableNotificationRow) child).getEntry()
                );
            } else {
                mLogger.addTransientChildNotificationToViewGroup(
                        ((ExpandableNotificationRow) child).getEntry(),
                        container
                );
            }
        }
    }

    @Override
    public void addTransientView(View view, int index) {
        if (mLogger != null && view instanceof ExpandableNotificationRow) {
            mLogger.addTransientRow(((ExpandableNotificationRow) view).getEntry(), index);
        }
        super.addTransientView(view, index);
    }

    @Override
    public void removeTransientView(View view) {
        if (mLogger != null && view instanceof ExpandableNotificationRow) {
            mLogger.removeTransientRow(((ExpandableNotificationRow) view).getEntry());
        }
        super.removeTransientView(view);
    }

    /**
     * Has this view been fully swiped out such that it's not visible anymore.
     */
    public boolean isFullySwipedOut(ExpandableView child) {
        return Math.abs(child.getTranslation()) >= Math.abs(getTotalTranslationLength(child));
    }

    private void focusNextViewIfFocused(View view) {
        if (view instanceof ExpandableNotificationRow row) {
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
                && mGroupMembershipManager.isChildInGroup(
                ((ExpandableNotificationRow) child).getEntry());
    }

    /**
     * Generate a remove animation for a child view.
     *
     * @param child The view to generate the remove animation for.
     * @return Whether a new animation was generated or an existing animation was detected by this
     * method. We need this to determine if a transient view is needed.
     */
    boolean generateRemoveAnimation(ExpandableView child) {
        String key = "";
        if (mDebugRemoveAnimation) {
            if (child instanceof ExpandableNotificationRow) {
                key = ((ExpandableNotificationRow) child).getEntry().getKey();
            }
            Log.d(TAG, "generateRemoveAnimation " + key);
        }
        if (removeRemovedChildFromHeadsUpChangeAnimations(child)) {
            if (mDebugRemoveAnimation) {
                Log.d(TAG, "removedBecauseOfHeadsUp " + key);
            }
            mAddedHeadsUpChildren.remove(child);
            return false;
        }
        if (mFeatureFlags.isEnabled(UNCLEARED_TRANSIENT_HUN_FIX)) {
            // Skip adding animation for clicked heads up notifications when the
            // Shade is closed, because the animation event is generated in
            // generateHeadsUpAnimationEvents. Only report that an animation was
            // actually generated (thus requesting the transient view be added)
            // if a removal animation is in progress.
            if (!isExpanded() && isClickedHeadsUp(child)) {
                // An animation is already running, add it transiently
                mClearTransientViewsWhenFinished.add(child);
                return child.inRemovalAnimation();
            }
        } else {
            if (isClickedHeadsUp(child)) {
                // An animation is already running, add it transiently
                mClearTransientViewsWhenFinished.add(child);
                return true;
            }
        }
        if (mDebugRemoveAnimation) {
            Log.d(TAG, "generateRemove " + key
                    + "\nmIsExpanded " + mIsExpanded
                    + "\nmAnimationsEnabled " + mAnimationsEnabled);
        }
        if (mIsExpanded && mAnimationsEnabled) {
            if (!mChildrenToAddAnimated.contains(child)) {
                if (mDebugRemoveAnimation) {
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

    private boolean isClickedHeadsUp(View child) {
        return HeadsUpUtil.isClickedHeadsUpNotification(child);
    }

    /**
     * Remove a removed child view from the heads up animations if it was just added there
     *
     * @return whether any child was removed from the list to animate and the view was just added
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
            ((ExpandableNotificationRow) child).setHeadsUpAnimatingAway(false);
        }
        mTmpList.clear();
        return hasAddEvent && mAddedHeadsUpChildren.contains(child);
    }

    /**
     * Updates the scroll position when a child was removed
     *
     * @param removedChild the removed child
     */
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

    private int getIntrinsicHeight(View view) {
        if (view instanceof ExpandableView expandableView) {
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
        final float scrimTopPadding = mAmbientState.isOnKeyguard() ? 0 : mMinimumPaddings;
        int position = (int) scrimTopPadding;
        int visibleIndex = -1;
        ExpandableView lastVisibleChild = null;
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = getChildAtIndex(i);
            boolean notGone = child.getVisibility() != View.GONE;
            if (notGone) visibleIndex++;
            if (notGone && !child.hasNoContentHeight()) {
                if (position != scrimTopPadding) {
                    if (lastVisibleChild != null) {
                        position += calculateGapHeight(lastVisibleChild, child, visibleIndex);
                    }
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
                lastVisibleChild = child;
            }
        }
        return 0;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof ExpandableView) {
            onViewAddedInternal((ExpandableView) child);
        }
    }

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
        } else {
        }
        mAmbientState.setLastVisibleBackgroundChild(lastChild);
        mAnimateBottomOnLayout = false;
        invalidate();
    }

    private void onViewAddedInternal(ExpandableView child) {
        updateHideSensitiveForChild(child);
        child.setOnHeightChangedListener(mOnChildHeightChangedListener);
        if (child instanceof ExpandableNotificationRow && mSensitiveRevealAnimEndabled) {
            NotificationEntry entry = ((ExpandableNotificationRow) child).getEntry();
            entry.addOnSensitivityChangedListener(mOnChildSensitivityChangedListener);
        }
        generateAddAnimation(child, false /* fromMoreCard */);
        updateAnimationState(child);
        updateChronometerForChild(child);
        if (child instanceof ExpandableNotificationRow row) {
            row.setDismissUsingRowTranslationX(mDismissUsingRowTranslationX);

        }
    }

    private void updateHideSensitiveForChild(ExpandableView child) {
        child.setHideSensitiveForIntrinsicHeight(mAmbientState.isHideSensitive());
    }

    public void notifyGroupChildRemoved(ExpandableView row, ViewGroup childrenContainer) {
        onViewRemovedInternal(row, childrenContainer);
    }

    public void notifyGroupChildAdded(ExpandableView row) {
        onViewAddedInternal(row);
    }

    public void setAnimationsEnabled(boolean animationsEnabled) {
        mAnimationsEnabled = animationsEnabled;
        updateNotificationAnimationStates();
        if (!animationsEnabled) {
            mSwipedOutViews.clear();
            mChildrenToRemoveAnimated.clear();
            clearTemporaryViewsInGroup(
                    /* viewGroup = */ this,
                    /* reason = */ "setAnimationsEnabled");
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

    void updateAnimationState(View child) {
        updateAnimationState((mAnimationsEnabled || hasPulsingNotifications())
                && (mIsExpanded || isPinnedHeadsUp(child)), child);
    }

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

    public void applyLaunchAnimationParams(LaunchAnimationParameters params) {
        // Modify the clipping for launching notifications
        mLaunchAnimationParams = params;
        setLaunchingNotification(params != null);
        updateLaunchedNotificationClipPath();
        requestChildrenUpdate();
    }

    private void updateAnimationState(boolean running, View child) {
        if (child instanceof ExpandableNotificationRow row) {
            row.setAnimationRunning(running);
        }
    }

    boolean isAddOrRemoveAnimationPending() {
        return mNeedsAnimation
                && (!mChildrenToAddAnimated.isEmpty() || !mChildrenToRemoveAnimated.isEmpty());
    }

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

    public void changeViewPosition(ExpandableView child, int newIndex) {
        Assert.isMainThread();
        if (mChangePositionInProgress) {
            throw new IllegalStateException("Reentrant call to changeViewPosition");
        }

        int currentIndex = indexOfChild(child);

        if (currentIndex == -1) {
            boolean isTransient = child instanceof ExpandableNotificationRow
                    && child.getTransientContainer() != null;
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

    private void startAnimationToState() {
        if (mNeedsAnimation) {
            generateAllAnimationEvents();
            mNeedsAnimation = false;
        }
        if (!mAnimationEvents.isEmpty() || isCurrentlyAnimating()) {
            setAnimationRunning(true);
            mStateAnimator.startAnimationForEvents(mAnimationEvents, mGoToFullShadeDelay);
            mAnimationEvents.clear();
            updateViewShadows();
        } else {
            applyCurrentState();
        }
        mGoToFullShadeDelay = 0;
    }

    private void generateAllAnimationEvents() {
        generateHeadsUpAnimationEvents();
        generateChildRemovalEvents();
        generateChildAdditionEvents();
        generatePositionChangeEvents();
        generateTopPaddingEvent();
        generateActivateEvent();
        generateHideSensitiveEvent();
        generateGoToFullShadeEvent();
        generateViewResizeEvent();
        generateGroupExpansionEvent();
        generateAnimateEverythingEvent();
    }

    private void generateHeadsUpAnimationEvents() {
        for (Pair<ExpandableNotificationRow, Boolean> eventPair : mHeadsUpChangeAnimations) {
            ExpandableNotificationRow row = eventPair.first;
            boolean isHeadsUp = eventPair.second;
            if (isHeadsUp != row.isHeadsUp()) {
                // For cases where we have a heads up showing and appearing again we shouldn't
                // do the animations at all.
                logHunSkippedForUnexpectedState(row, isHeadsUp, row.isHeadsUp());
                continue;
            }
            int type = AnimationEvent.ANIMATION_TYPE_HEADS_UP_OTHER;
            boolean onBottom = false;
            boolean pinnedAndClosed = row.isPinned() && !mIsExpanded;
            boolean performDisappearAnimation = !mIsExpanded
                    // Only animate if we still have pinned heads up, otherwise we just have the
                    // regular collapse animation of the lock screen
                    || (mKeyguardBypassEnabled && onKeyguard()
                    && mInHeadsUpPinnedMode);
            if (performDisappearAnimation && !isHeadsUp) {
                type = row.wasJustClicked()
                        ? AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR_CLICK
                        : AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR;
                if (row.isChildInGroup()) {
                    // We can otherwise get stuck in there if it was just isolated
                    row.setHeadsUpAnimatingAway(false);
                    logHunAnimationSkipped(row, "row is child in group");
                    continue;
                }
            } else {
                ExpandableViewState viewState = row.getViewState();
                if (viewState == null) {
                    // A view state was never generated for this view, so we don't need to animate
                    // this. This may happen with notification children.
                    logHunAnimationSkipped(row, "row has no viewState");
                    continue;
                }
                boolean shouldHunAppearFromTheBottom =
                        mStackScrollAlgorithm.shouldHunAppearFromBottom(mAmbientState, viewState);
                if (isHeadsUp && (mAddedHeadsUpChildren.contains(row) || pinnedAndClosed)) {
                    if (pinnedAndClosed || shouldHunAppearFromTheBottom) {
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
            if (NotificationsImprovedHunAnimation.isEnabled()) {
                // TODO(b/283084712) remove this with the flag and update the HUN filters at
                //  creation
                event.filter.animateHeight = false;
            }
            mAnimationEvents.add(event);
            if (SPEW) {
                Log.v(TAG, "Generating HUN animation event: "
                        + " isHeadsUp=" + isHeadsUp
                        + " type=" + type
                        + " onBottom=" + onBottom
                        + " row=" + row.getEntry().getKey());
            }
            logHunAnimationEventAdded(row, type);
        }
        mHeadsUpChangeAnimations.clear();
        mAddedHeadsUpChildren.clear();
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

    private void generateChildRemovalEvents() {
        for (ExpandableView child : mChildrenToRemoveAnimated) {
            boolean childWasSwipedOut = mSwipedOutViews.contains(child);

            // we need to know the view after this one
            float removedTranslation = child.getTranslationY();
            boolean ignoreChildren = true;
            if (child instanceof ExpandableNotificationRow row) {
                if (row.isRemoved() && row.wasChildInGroupWhenRemoved()) {
                    removedTranslation = row.getTranslationWhenRemoved();
                    ignoreChildren = false;
                }
                childWasSwipedOut |= isFullySwipedOut(row);
            } else if (child instanceof MediaContainerView) {
                childWasSwipedOut = true;
            }
            if (!childWasSwipedOut) {
                Rect clipBounds = child.getClipBounds();
                childWasSwipedOut = clipBounds != null && clipBounds.height() == 0;

                if (childWasSwipedOut) {
                    // Clean up any potential transient views if the child has already been swiped
                    // out, as we won't be animating it further (due to its height already being
                    // clipped to 0.
                    child.removeFromTransientContainer();
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
            if (mDebugRemoveAnimation) {
                String key = "";
                if (child instanceof ExpandableNotificationRow) {
                    key = ((ExpandableNotificationRow) child).getEntry().getKey();
                }
                Log.d(TAG, "created Remove Event - SwipedOut: " + childWasSwipedOut + " " + key);
            }
        }
        mChildrenToRemoveAnimated.clear();
    }

    private void generatePositionChangeEvents() {
        for (ExpandableView child : mChildrenChangingPositions) {
            Integer duration = null;
            if (child instanceof ExpandableNotificationRow row) {
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
    }

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

    private void generateHideSensitiveEvent() {
        if (mHideSensitiveNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_HIDE_SENSITIVE));
        }
        mHideSensitiveNeedsAnimation = false;
    }

    private void generateGoToFullShadeEvent() {
        if (mGoToFullShadeNeedsAnimation) {
            mAnimationEvents.add(
                    new AnimationEvent(null, AnimationEvent.ANIMATION_TYPE_GO_TO_FULL_SHADE));
        }
        mGoToFullShadeNeedsAnimation = false;
    }

    protected StackScrollAlgorithm createStackScrollAlgorithm(Context context) {
        return new StackScrollAlgorithm(context, this);
    }

    /**
     * @return Whether a y coordinate is inside the content.
     */
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
        if (mTouchHandler != null) {
            boolean touchHandled = mTouchHandler.onTouchEvent(ev);
            if (SceneContainerFlag.isEnabled()) {
                if (getChildAtPosition(
                        mInitialTouchX, mInitialTouchY, true, true, false) == null) {
                    // If scene container is enabled, any touch that we are handling that is not on
                    // a child view should be handled by scene container instead.
                    return false;
                } else {
                    // If scene container is enabled, any touch that we are handling that is not on
                    // a child view should be handled by scene container instead.
                    return touchHandled;
                }
            } else if (touchHandled) {
                return true;
            }
        }

        return super.onTouchEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (SceneContainerFlag.isEnabled() && mIsBeingDragged) {
            int action = ev.getActionMasked();
            boolean isUpOrCancel = action == ACTION_UP || action == ACTION_CANCEL;
            if (mSendingTouchesToSceneFramework) {
                mController.sendTouchToSceneFramework(ev);
            } else if (!isUpOrCancel) {
                // if this is the first touch being sent to the scene framework,
                // convert it into a synthetic DOWN event.
                mSendingTouchesToSceneFramework = true;
                MotionEvent downEvent = MotionEvent.obtain(ev);
                downEvent.setAction(MotionEvent.ACTION_DOWN);
                mController.sendTouchToSceneFramework(downEvent);
                downEvent.recycle();
            }

            if (isUpOrCancel) {
                setIsBeingDragged(false);
            }
            return false;
        }
        return TouchLogger.logDispatchTouch(TAG, ev, super.dispatchTouchEvent(ev));
    }

    void dispatchDownEventToScroller(MotionEvent ev) {
        MotionEvent downEvent = MotionEvent.obtain(ev);
        downEvent.setAction(MotionEvent.ACTION_DOWN);
        onScrollTouch(downEvent);
        downEvent.recycle();
    }

    @Override
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

    boolean onScrollTouch(MotionEvent ev) {
        if (!isScrollingEnabled()) {
            return false;
        }
        if (isInsideQsHeader(ev) && !mIsBeingDragged) {
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

        // If the scene framework is enabled, ignore all non-move gestures if we are currently
        // dragging - they should be dispatched to the scene framework. Move gestures should be let
        // through to determine if we are still dragging or not.
        if (
                SceneContainerFlag.isEnabled()
                && mIsBeingDragged
                && action != MotionEvent.ACTION_MOVE
        ) {
            setIsBeingDragged(false);
            return false;
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
                    // Defer actual scrolling to the scene framework if enabled
                    if (SceneContainerFlag.isEnabled()) {
                        setIsBeingDragged(false);
                        return false;
                    }
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
            case ACTION_UP:
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
            case ACTION_CANCEL:
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

    protected boolean isInsideQsHeader(MotionEvent ev) {
        if (SceneContainerFlag.isEnabled()) {
            return ev.getY() < mController.getPlaceholderTop();
        }

        mQsHeader.getBoundsOnScreen(mQsHeaderBound);
        /**
         * One-handed mode defines a feature FEATURE_ONE_HANDED of DisplayArea {@link DisplayArea}
         * that will translate down the Y-coordinate whole window screen type except for
         * TYPE_NAVIGATION_BAR and TYPE_NAVIGATION_BAR_PANEL .{@link DisplayAreaPolicy}.
         *
         * So, to consider triggered One-handed mode would translate down the absolute Y-coordinate
         * of DisplayArea into relative coordinates for all windows, we need to correct the
         * QS Head bounds here.
         */
        final int xOffset = Math.round(ev.getRawX() - ev.getX() + mQsHeader.getLeft());
        final int yOffset = Math.round(ev.getRawY() - ev.getY());
        mQsHeaderBound.offsetTo(xOffset, yOffset);
        return mQsHeaderBound.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    private void onOverScrollFling(boolean open, int initialVelocity) {
        if (mOverscrollTopChangedListener != null) {
            mOverscrollTopChangedListener.flingTopOverscroll(initialVelocity, open);
        }
        mDontReportNextOverScroll = true;
        setOverScrollAmount(0.0f, true, false);
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
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mTouchHandler != null && mTouchHandler.onInterceptTouchEvent(ev)) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    void handleEmptySpaceClick(MotionEvent ev) {
        logEmptySpaceClick(ev, isBelowLastNotification(mInitialTouchX, mInitialTouchY),
                mStatusBarState, mTouchIsClick);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                final float touchSlop = getTouchSlop(ev);
                if (mTouchIsClick && (Math.abs(ev.getY() - mInitialTouchY) > touchSlop
                        || Math.abs(ev.getX() - mInitialTouchX) > touchSlop)) {
                    mTouchIsClick = false;
                }
                break;
            case ACTION_UP:
                if (mStatusBarState != StatusBarState.KEYGUARD && mTouchIsClick &&
                        isBelowLastNotification(mInitialTouchX, mInitialTouchY)) {
                    debugShadeLog("handleEmptySpaceClick: touch event propagated further");
                    mOnEmptySpaceClickListener.onEmptySpaceClicked(mInitialTouchX, mInitialTouchY);
                }
                break;
            default:
                debugShadeLog("handleEmptySpaceClick: MotionEvent ignored");
        }
    }

    private void debugShadeLog(@CompileTimeConstant final String s) {
        if (mLogger == null) {
            return;
        }
        mLogger.logShadeDebugEvent(s);
    }

    private void logEmptySpaceClick(MotionEvent ev, boolean isTouchBelowLastNotification,
                                    int statusBarState, boolean touchIsClick) {
        if (mLogger == null) {
            return;
        }
        mLogger.logEmptySpaceClick(
                isTouchBelowLastNotification,
                statusBarState,
                touchIsClick,
                MotionEvent.actionToString(ev.getActionMasked()));
    }

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
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        if (disallowIntercept) {
            cancelLongPress();
        }
    }

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
                        ev.getX(), y, false /* requireMinHeight */,
                        false /* ignoreDecors */, true /* ignoreWidth */);
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

            case ACTION_CANCEL:
            case ACTION_UP:
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
    private boolean isInContentBounds(MotionEvent event) {
        return isInContentBounds(event.getY());
    }


    @VisibleForTesting
    void setIsBeingDragged(boolean isDragged) {
        mIsBeingDragged = isDragged;
        if (isDragged) {
            requestDisallowInterceptTouchEvent(true);
            cancelLongPress();
            resetExposedMenuView(true /* animate */, true /* force */);
        } else {
            mSendingTouchesToSceneFramework = false;
        }
    }

    @VisibleForTesting
    boolean getIsBeingDragged() {
        return mIsBeingDragged;
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

    public void setOnEmptySpaceClickListener(OnEmptySpaceClickListener listener) {
        mOnEmptySpaceClickListener = listener;
    }

    /**
     * @hide
     */
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

    boolean isScrolledToBottom() {
        return mScrollAdapter.isScrolledToBottom();
    }

    int getEmptyBottomMargin() {
        int contentHeight;
        if (mShouldUseSplitNotificationShade) {
            // When in split shade and there are no notifications, the height can be too low, as
            // it is based on notifications bottom, which is lower on split shade.
            // Here we prefer to use at least a minimum height defined for split shade.
            // Otherwise the expansion motion is too fast.
            contentHeight = Math.max(mSplitShadeMinContentHeight, mContentHeight);
        } else {
            contentHeight = mContentHeight;
        }
        return Math.max(mMaxLayoutHeight - contentHeight, 0);
    }

    void onExpansionStarted() {
        mIsExpansionChanging = true;
        mAmbientState.setExpansionChanging(true);
    }

    void onExpansionStopped() {
        mIsExpansionChanging = false;
        mAmbientState.setExpansionChanging(false);
        if (!mIsExpanded) {
            resetScrollPosition();
            mResetUserExpandedStatesRunnable.run();
            clearTemporaryViews();
            clearUserLockedViews();
            resetAllSwipeState();
        }
    }

    private void clearUserLockedViews() {
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = getChildAtIndex(i);
            if (child instanceof ExpandableNotificationRow row) {
                row.setUserLocked(false);
            }
        }
    }

    private void clearTemporaryViews() {
        // lets make sure nothing is transient anymore
        clearTemporaryViewsInGroup(
                /* viewGroup = */ this,
                /* reason = */ "clearTemporaryViews"
        );
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = getChildAtIndex(i);
            if (child instanceof ExpandableNotificationRow row) {
                clearTemporaryViewsInGroup(
                        /* viewGroup = */ row.getChildrenContainer(),
                        /* reason = */ "clearTemporaryViewsInGroup(row.getChildrenContainer())"
                );
            }
        }
    }

    private void clearTemporaryViewsInGroup(ViewGroup viewGroup, String reason) {
        while (viewGroup != null && viewGroup.getTransientViewCount() != 0) {
            final View transientView = viewGroup.getTransientView(0);
            viewGroup.removeTransientView(transientView);
            if (transientView instanceof ExpandableView) {
                ((ExpandableView) transientView).setTransientContainer(null);
                if (transientView instanceof ExpandableNotificationRow) {
                    logTransientNotificationRowTraversalCleaned(
                            (ExpandableNotificationRow) transientView,
                            reason
                    );
                }
            }
        }
    }

    private void logTransientNotificationRowTraversalCleaned(
            ExpandableNotificationRow transientView,
            String reason
    ) {
        if (mLogger == null) {
            return;
        }
        mLogger.transientNotificationRowTraversalCleaned(transientView.getEntry(), reason);
    }

    void onPanelTrackingStarted() {
        mPanelTracking = true;
        mAmbientState.setPanelTracking(true);
        resetExposedMenuView(true /* animate */, true /* force */);
    }

    void onPanelTrackingStopped() {
        mPanelTracking = false;
        mAmbientState.setPanelTracking(false);
    }

    void resetScrollPosition() {
        mScroller.abortAnimation();
        setOwnScrollY(0);
    }

    @VisibleForTesting
    void setIsExpanded(boolean isExpanded) {
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
                if (!mIsExpansionChanging) {
                    resetAllSwipeState();
                }
                finalizeClearAllAnimation();
            }
            updateNotificationAnimationStates();
            updateChronometers();
            requestChildrenUpdate();
            updateUseRoundedRectClipping();
            updateDismissBehavior();
        }
    }

    private void updateChronometers() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            updateChronometerForChild(getChildAt(i));
        }
    }

    void updateChronometerForChild(View child) {
        if (child instanceof ExpandableNotificationRow row) {
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

    private void updateScrollPositionOnExpandInBottom(ExpandableView view) {
        if (view instanceof ExpandableNotificationRow row && !onKeyguard()) {
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
                    // if Scene Container is active, send bottom notification expansion delta
                    // to it so that it can scroll the stack and scrim accordingly.
                    if (SceneContainerFlag.isEnabled()) {
                        float diff = endPosition - layoutEnd;
                        mController.sendSyntheticScrollToSceneFramework(diff);
                    }
                    setOwnScrollY((int) (mOwnScrollY + endPosition - layoutEnd));
                    mDisallowScrollingInThisMotion = true;
                }
            }
        }
    }

    void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener onHeightChangedListener) {
        this.mOnHeightChangedListener = onHeightChangedListener;
    }

    void setOnHeightChangedRunnable(Runnable r) {
        this.mOnHeightChangedRunnable = r;
    }

    void onChildAnimationFinished() {
        setAnimationRunning(false);
        requestChildrenUpdate();
        runAnimationFinishedRunnables();
        clearTransient();
        clearHeadsUpDisappearRunning();
        finalizeClearAllAnimation();
    }

    private void finalizeClearAllAnimation() {
        if (mAmbientState.isClearAllInProgress()) {
            setClearAllInProgress(false);
            if (mShadeNeedsToClose) {
                mShadeNeedsToClose = false;
                if (mIsExpanded) {
                    mClearAllFinishedWhilePanelExpandedRunnable.run();
                }
            }
        }
    }

    private void clearHeadsUpDisappearRunning() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof ExpandableNotificationRow row) {
                row.setHeadsUpAnimatingAway(false);
                if (row.isSummaryWithChildren()) {
                    for (ExpandableNotificationRow child : row.getAttachedChildren()) {
                        child.setHeadsUpAnimatingAway(false);
                    }
                }
            }
        }
    }

    private void clearTransient() {
        for (ExpandableView view : mClearTransientViewsWhenFinished) {
            view.removeFromTransientContainer();
        }
        mClearTransientViewsWhenFinished.clear();
    }

    private void runAnimationFinishedRunnables() {
        for (Runnable runnable : mAnimationFinishedRunnables) {
            runnable.run();
        }
        mAnimationFinishedRunnables.clear();
    }

    void updateSensitiveness(boolean animate, boolean hideSensitive) {
        if (hideSensitive != mAmbientState.isHideSensitive()) {
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                ExpandableView v = getChildAtIndex(i);
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

    private void applyCurrentState() {
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = getChildAtIndex(i);
            child.applyViewState();
        }

        if (NotificationsLiveDataStoreRefactor.isEnabled()) {
            if (mLocationsChangedListener != null) {
                mLocationsChangedListener.onChildLocationsChanged(collectVisibleLocationsCallable);
            }
        } else {
            if (mListener != null) {
                mListener.onChildLocationsChanged();
            }
        }

        runAnimationFinishedRunnables();
        setAnimationRunning(false);
        updateViewShadows();
    }

    /**
     * Retrieves a map of visible [{@link ExpandableViewState#location}]s of the actively displayed
     * Notification children associated by their Notification keys.
     * Locations are collected recursively including locations from the child views of Notification
     * Groups, that are visible.
     */
    private Map<String, Integer> collectVisibleNotificationLocations() {
        Map<String, Integer> visibilities = new HashMap<>();
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            ExpandableView child = getChildAtIndex(i);
            if (child instanceof ExpandableNotificationRow row) {
                row.collectVisibleLocations(visibilities);
            }
        }
        return visibilities;
    }

    private void updateViewShadows() {
        // we need to work around an issue where the shadow would not cast between siblings when
        // their z difference is between 0 and 0.1

        // Lefts first sort by Z difference
        for (int i = 0; i < getChildCount(); i++) {
            ExpandableView child = getChildAtIndex(i);
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
                        expandableView.getTranslationY();
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
     * Update colors of section headers, shade footer, and empty shade views.
     */
    void updateDecorViews() {
        final @ColorInt int onSurface = Utils.getColorAttrDefaultColor(
                mContext, com.android.internal.R.attr.materialColorOnSurface);
        final @ColorInt int onSurfaceVariant = Utils.getColorAttrDefaultColor(
                mContext, com.android.internal.R.attr.materialColorOnSurfaceVariant);

        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logEvent("NSSL.updateDecorViews()",
                    "onSurface=" + ColorUtilKt.hexColorString(onSurface)
                            + " onSurfaceVariant=" + ColorUtilKt.hexColorString(onSurfaceVariant));
        }

        mSectionsManager.setHeaderForegroundColors(onSurface, onSurfaceVariant);

        if (mFooterView != null) {
            mFooterView.updateColors();
        }

        if (mEmptyShadeView != null) {
            mEmptyShadeView.setTextColors(onSurface, onSurfaceVariant);
        }
    }

    void goToFullShade(long delay) {
        mGoToFullShadeNeedsAnimation = true;
        mGoToFullShadeDelay = delay;
        mNeedsAnimation = true;
        requestChildrenUpdate();
    }

    public void cancelExpandHelper() {
        mExpandHelper.cancel();
    }

    void setIntrinsicPadding(int intrinsicPadding) {
        mIntrinsicPadding = intrinsicPadding;
    }

    int getIntrinsicPadding() {
        return mIntrinsicPadding;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    /**
     * See {@link AmbientState#setDozing}.
     */
    public void setDozing(boolean dozing, boolean animate) {
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
            resetAllSwipeState();
        }
        if (!wasHiddenAtAll && nowHiddenAtAll) {
            resetExposedMenuView(true /* animate */, true /* animate */);
        }
        if (nowFullyHidden != wasFullyHidden || wasHiddenAtAll != nowHiddenAtAll) {
            invalidateOutline();
        }
        updateAlgorithmHeightAndPadding();
        requestChildrenUpdate();
        updateOwnTranslationZ();
    }

    private void updateOwnTranslationZ() {
        // Since we are clipping to the outline we need to make sure that the shadows aren't
        // clipped when pulsing
        float ownTranslationZ = 0;
        if (mKeyguardBypassEnabled && mAmbientState.isHiddenAtAll()) {
            ExpandableView firstChildNotGone = getFirstChildNotGone();
            if (firstChildNotGone != null && firstChildNotGone.showingPulsing()) {
                ownTranslationZ = firstChildNotGone.getTranslationZ();
            }
        }
        setTranslationZ(ownTranslationZ);
    }

    private void updateVisibility() {
        mController.updateVisibility(!mAmbientState.isFullyHidden() || !onKeyguard());
    }

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

    /**
     * Returns whether or not a History button is shown in the footer. If there is no footer, then
     * this will return false.
     **/
    public boolean isHistoryShown() {
        FooterViewRefactor.assertInLegacyMode();
        return mFooterView != null && mFooterView.isHistoryShown();
    }

    /** Bind the {@link FooterView} to the NSSL. */
    public void setFooterView(@NonNull FooterView footerView) {
        int index = -1;
        if (mFooterView != null) {
            index = indexOfChild(mFooterView);
            removeView(mFooterView);
        }
        mFooterView = footerView;
        addView(mFooterView, index);
        if (!FooterViewRefactor.isEnabled()) {
            if (mManageButtonClickListener != null) {
                mFooterView.setManageButtonClickListener(mManageButtonClickListener);
            }
            mFooterView.setClearAllButtonClickListener(v -> {
                if (mFooterClearAllListener != null) {
                    mFooterClearAllListener.onClearAll();
                }
                clearNotifications(ROWS_ALL, true /* closeShade */);
                footerView.setClearAllButtonVisible(false /* visible */, true /* animate */);
            });
        }
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

    /** Legacy version, should be removed with the footer refactor flag. */
    public void updateEmptyShadeView(boolean visible, boolean areNotificationsHiddenInShade) {
        FooterViewRefactor.assertInLegacyMode();
        updateEmptyShadeView(visible, areNotificationsHiddenInShade,
                mHasFilteredOutSeenNotifications);
    }

    /** Trigger an update for the empty shade resources and visibility. */
    public void updateEmptyShadeView(boolean visible, boolean areNotificationsHiddenInShade,
            boolean hasFilteredOutSeenNotifications) {
        mEmptyShadeView.setVisible(visible, mIsExpanded && mAnimationsEnabled);

        if (areNotificationsHiddenInShade) {
            updateEmptyShadeView(R.string.dnd_suppressing_shade_text, 0, 0);
        } else if (hasFilteredOutSeenNotifications) {
            updateEmptyShadeView(
                    R.string.no_unseen_notif_text,
                    R.string.unlock_to_see_notif_text,
                    R.drawable.ic_friction_lock_closed);
        } else {
            updateEmptyShadeView(R.string.empty_shade_text, 0, 0);
        }
    }

    private void updateEmptyShadeView(
            @StringRes int newTextRes,
            @StringRes int newFooterTextRes,
            @DrawableRes int newFooterIconRes) {
        int oldTextRes = mEmptyShadeView.getTextResource();
        if (oldTextRes != newTextRes) {
            mEmptyShadeView.setText(newTextRes);
        }
        int oldFooterTextRes = mEmptyShadeView.getFooterTextResource();
        if (oldFooterTextRes != newFooterTextRes) {
            mEmptyShadeView.setFooterText(newFooterTextRes);
        }
        int oldFooterIconRes = mEmptyShadeView.getFooterIconResource();
        if (oldFooterIconRes != newFooterIconRes) {
            mEmptyShadeView.setFooterIcon(newFooterIconRes);
        }
        if (newFooterIconRes != 0 || newFooterTextRes != 0) {
            mEmptyShadeView.setFooterVisibility(View.VISIBLE);
        } else {
            mEmptyShadeView.setFooterVisibility(View.GONE);
        }
    }

    public boolean isEmptyShadeViewVisible() {
        return mEmptyShadeView.isVisible();
    }

    public void updateFooterView(boolean visible, boolean showDismissView, boolean showHistory) {
        FooterViewRefactor.assertInLegacyMode();
        if (mFooterView == null || mNotificationStackSizeCalculator == null) {
            return;
        }
        boolean animate = mIsExpanded && mAnimationsEnabled;
        mFooterView.setVisible(visible, animate);
        mFooterView.showHistory(showHistory);
        mFooterView.setClearAllButtonVisible(showDismissView, animate);
        mFooterView.setFooterLabelVisible(mHasFilteredOutSeenNotifications);
    }

    @VisibleForTesting
    public void setClearAllInProgress(boolean clearAllInProgress) {
        mClearAllInProgress = clearAllInProgress;
        mAmbientState.setClearAllInProgress(clearAllInProgress);
        mController.getNotificationRoundnessManager().setClearAllInProgress(clearAllInProgress);
    }

    boolean getClearAllInProgress() {
        return mClearAllInProgress;
    }

    /**
     * @return the padding after the media header on the lockscreen
     */
    public int getPaddingAfterMedia() {
        return mGapHeight + mPaddingBetweenElements;
    }

    public int getEmptyShadeViewHeight() {
        return mEmptyShadeView.getHeight();
    }

    public float getBottomMostNotificationBottom() {
        final int count = getChildCount();
        float max = 0;
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableView child = getChildAtIndex(childIdx);
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

    public void setResetUserExpandedStatesRunnable(Runnable runnable) {
        this.mResetUserExpandedStatesRunnable = runnable;
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    void requestAnimateEverything() {
        if (mIsExpanded && mAnimationsEnabled) {
            mEverythingNeedsAnimation = true;
            mNeedsAnimation = true;
            requestChildrenUpdate();
        }
    }

    public boolean isBelowLastNotification(float touchX, float touchY) {
        int childCount = getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableView child = getChildAtIndex(i);
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

    /**
     * @hide
     */
    @Override
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        event.setScrollable(mScrollable);
        event.setMaxScrollX(mScrollX);
        event.setScrollY(mOwnScrollY);
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

    public int getContainerChildCount() {
        return getChildCount();
    }

    public View getContainerChildAt(int i) {
        return getChildAt(i);
    }

    public void removeContainerView(View v) {
        Assert.isMainThread();
        removeView(v);
        updateSpeedBumpIndex();
    }

    public void addContainerView(View v) {
        Assert.isMainThread();
        addView(v);
        updateSpeedBumpIndex();
    }

    public void addContainerViewAt(View v, int index) {
        Assert.isMainThread();
        ensureRemovedFromTransientContainer(v);
        addView(v, index);
        updateSpeedBumpIndex();
    }

    private void ensureRemovedFromTransientContainer(View v) {
        if (v.getParent() != null && v instanceof ExpandableView) {
            // If the child is animating away, it will still have a parent, so detach it first
            // TODO: We should really cancel the active animations here. This will
            //  happen automatically when the view's intro animation starts, but
            //  it's a fragile link.
            ((ExpandableView) v).removeFromTransientContainerForAdditionTo(this);
        }
    }

    public void runAfterAnimationFinished(Runnable runnable) {
        mAnimationFinishedRunnables.add(runnable);
    }

    public void generateHeadsUpAnimation(NotificationEntry entry, boolean isHeadsUp) {
        ExpandableNotificationRow row = entry.getHeadsUpAnimationView();
        generateHeadsUpAnimation(row, isHeadsUp);
    }

    /**
     * Notifies the NSSL, that the given view would need a HeadsUp animation, when it is being
     * added to this container.
     *
     * @param row to animate
     * @param isHeadsUp true for appear, false for disappear animations
     */
    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        final boolean add = mAnimationsEnabled && (isHeadsUp || mHeadsUpGoingAwayAnimationsAllowed);
        if (SPEW) {
            Log.v(TAG, "generateHeadsUpAnimation:"
                    + " willAdd=" + add
                    + " isHeadsUp=" + isHeadsUp
                    + " row=" + row.getEntry().getKey());
        }
        if (add) {
            // If we're hiding a HUN we just started showing THIS FRAME, then remove that event,
            // and do not add the disappear event either.
            if (!isHeadsUp && mHeadsUpChangeAnimations.remove(new Pair<>(row, true))) {
                if (SPEW) {
                    Log.v(TAG, "generateHeadsUpAnimation: previous hun appear animation cancelled");
                }
                logHunAnimationSkipped(row, "previous hun appear animation cancelled");
                return;
            }
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
    public void setHeadsUpBoundaries(int height, int bottomBarHeight) {
        mAmbientState.setMaxHeadsUpTranslation(height - bottomBarHeight);
        mStackScrollAlgorithm.setHeadsUpAppearHeightBottom(height);
        mStateAnimator.setHeadsUpAppearHeightBottom(height);
        mStateAnimator.setStackTopMargin(mAmbientState.getStackTopMargin());
        requestChildrenUpdate();
    }

    public void setWillExpand(boolean willExpand) {
        mWillExpand = willExpand;
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow row) {
        mAmbientState.setTrackedHeadsUpRow(row);
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

    public void setQsFullScreen(boolean qsFullScreen) {
        mQsFullScreen = qsFullScreen;
        updateAlgorithmLayoutMinHeight();
        updateScrollability();
    }

    boolean isQsFullScreen() {
        return mQsFullScreen;
    }

    public void setQsExpansionFraction(float qsExpansionFraction) {
        boolean footerAffected = mQsExpansionFraction != qsExpansionFraction
                && (mQsExpansionFraction == 1 || qsExpansionFraction == 1);
        mQsExpansionFraction = qsExpansionFraction;
        updateUseRoundedRectClipping();

        // If notifications are scrolled,
        // clear out scrollY by the time we push notifications offscreen
        if (mOwnScrollY > 0) {
            setOwnScrollY((int) MathUtils.lerp(mOwnScrollY, 0, mQsExpansionFraction));
        }
        if (!FooterViewRefactor.isEnabled() && footerAffected) {
            updateFooter();
        }
    }

    @VisibleForTesting
    void setOwnScrollY(int ownScrollY) {
        setOwnScrollY(ownScrollY, false /* animateScrollChangeListener */);
    }

    private void setOwnScrollY(int ownScrollY, boolean animateStackYChangeListener) {
        // If scene container is active, NSSL should not control its own scrolling.
        if (SceneContainerFlag.isEnabled()) {
            return;
        }
        // Avoid Flicking during clear all
        // when the shade finishes closing, onExpansionStopped will call
        // resetScrollPosition to setOwnScrollY to 0
        if (mAmbientState.isClosing() || mAmbientState.isClearAllInProgress()) {
            return;
        }

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

    @Nullable
    public ExpandableView getShelf() {
        return mShelf;
    }

    public void setShelf(NotificationShelf shelf) {
        int index = -1;
        if (mShelf != null) {
            index = indexOfChild(mShelf);
            removeView(mShelf);
        }
        mShelf = shelf;
        addView(mShelf, index);
        mAmbientState.setShelf(mShelf);
        mStateAnimator.setShelf(mShelf);
        shelf.bind(mAmbientState, this, mController.getNotificationRoundnessManager());
    }

    public void setMaxDisplayedNotifications(int maxDisplayedNotifications) {
        if (mMaxDisplayedNotifications != maxDisplayedNotifications) {
            mMaxDisplayedNotifications = maxDisplayedNotifications;
            updateContentHeight();
            notifyHeightChangeListener(mShelf);
        }
    }

    /**
     * This is used for debugging only; it will be used to draw the otherwise invisible line which
     * NotificationPanelViewController treats as the bottom when calculating how many notifications
     * appear on the keyguard.
     * Setting a negative number will disable rendering this line.
     */
    public void setKeyguardBottomPadding(float keyguardBottomPadding) {
        mKeyguardBottomPadding = keyguardBottomPadding;
    }

    public void setShouldShowShelfOnly(boolean shouldShowShelfOnly) {
        mShouldShowShelfOnly = shouldShowShelfOnly;
        updateAlgorithmLayoutMinHeight();
    }

    public int getMinExpansionHeight() {
        // shelf height is defined in dp but status bar height can be defined in px, that makes
        // relation between them variable - sometimes one might be bigger than the other when
        // changing density. That’s why we need to ensure we’re not subtracting negative value below
        return mShelf.getIntrinsicHeight()
                - Math.max(0,
                (mShelf.getIntrinsicHeight() - mStatusBarHeight + mWaterfallTopInset) / 2)
                + mWaterfallTopInset;
    }

    public void setInHeadsUpPinnedMode(boolean inHeadsUpPinnedMode) {
        mInHeadsUpPinnedMode = inHeadsUpPinnedMode;
        updateClipping();
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        updateClipping();
    }

    @VisibleForTesting
    public void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
        mAmbientState.setStatusBarState(statusBarState);
        updateSpeedBumpIndex();
        updateDismissBehavior();
    }

    void setUpcomingStatusBarState(int upcomingStatusBarState) {
        FooterViewRefactor.assertInLegacyMode();
        mUpcomingStatusBarState = upcomingStatusBarState;
        if (mUpcomingStatusBarState != mStatusBarState) {
            updateFooter();
        }
    }

    void onStatePostChange(boolean fromShadeLocked) {
        boolean onKeyguard = onKeyguard();

        if (mHeadsUpAppearanceController != null) {
            mHeadsUpAppearanceController.onStateChanged();
        }

        setExpandingEnabled(!onKeyguard);
        if (!FooterViewRefactor.isEnabled()) {
            updateFooter();
        }
        requestChildrenUpdate();
        onUpdateRowStates();
        updateVisibility();
    }

    public void setExpandingVelocity(float expandingVelocity) {
        mAmbientState.setExpandingVelocity(expandingVelocity);
    }

    public float getOpeningHeight() {
        if (mEmptyShadeView.getVisibility() == GONE) {
            return getMinExpansionHeight();
        } else {
            return FooterViewRefactor.isEnabled() ? getAppearEndPosition()
                    : getAppearEndPositionLegacy();
        }
    }

    public void setIsFullWidth(boolean isFullWidth) {
        mAmbientState.setSmallScreen(isFullWidth);
    }

    public void setPanelFlinging(boolean flinging) {
        mAmbientState.setFlinging(flinging);
        if (!flinging) {
            // re-calculate the stack height which was frozen while flinging
            updateStackPosition();
        }
    }

    public void setHeadsUpGoingAwayAnimationsAllowed(boolean headsUpGoingAwayAnimationsAllowed) {
        mHeadsUpGoingAwayAnimationsAllowed = headsUpGoingAwayAnimationsAllowed;
    }

    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        pw.println("Internal state:");
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            println(pw, "pulsing", mPulsing);
            println(pw, "expanded", mIsExpanded);
            println(pw, "headsUpPinned", mInHeadsUpPinnedMode);
            println(pw, "qsClipping", mShouldUseRoundedRectClipping);
            println(pw, "qsClipDismiss", mDismissUsingRowTranslationX);
            println(pw, "visibility", visibilityString(getVisibility()));
            println(pw, "alpha", getAlpha());
            println(pw, "suppressChildrenMeasureLayout", mSuppressChildrenMeasureAndLayout);
            println(pw, "scrollY", mAmbientState.getScrollY());
            println(pw, "maxTopPadding", mMaxTopPadding);
            println(pw, "showShelfOnly", mShouldShowShelfOnly);
            println(pw, "qsExpandFraction", mQsExpansionFraction);
            println(pw, "isCurrentUserSetup", mIsCurrentUserSetup);
            println(pw, "hideAmount", mAmbientState.getHideAmount());
            println(pw, "ambientStateSwipingUp", mAmbientState.isSwipingUp());
            println(pw, "maxDisplayedNotifications", mMaxDisplayedNotifications);
            println(pw, "intrinsicContentHeight", mIntrinsicContentHeight);
            println(pw, "contentHeight", mContentHeight);
            println(pw, "intrinsicPadding", mIntrinsicPadding);
            println(pw, "topPadding", mTopPadding);
            println(pw, "bottomPadding", mBottomPadding);
            println(pw, "translationX", getTranslationX());
            println(pw, "translationY", getTranslationY());
            println(pw, "translationZ", getTranslationZ());
            println(pw, "skinnyNotifsInLandscape", mSkinnyNotifsInLandscape);
            println(pw, "minimumPaddings", mMinimumPaddings);
            println(pw, "qsTilePadding", mQsTilePadding);
            println(pw, "sidePaddings", mSidePaddings);
            println(pw, "lastUpdateSidePadding", mLastUpdateSidePaddingDumpString);
            mNotificationStackSizeCalculator.dump(pw, args);
        });
        pw.println();
        pw.println("Contents:");
        DumpUtilsKt.withIncreasedIndent(
                pw,
                () -> {
                    int childCount = getChildCount();
                    pw.println("Number of children: " + childCount);
                    pw.println();

                    for (int i = 0; i < childCount; i++) {
                        ExpandableView child = getChildAtIndex(i);
                        child.dump(pw, args);
                        if (!FooterViewRefactor.isEnabled()) {
                            if (child instanceof FooterView) {
                                DumpUtilsKt.withIncreasedIndent(pw,
                                        () -> dumpFooterViewVisibility(pw));
                            }
                        }
                        pw.println();
                    }
                    int transientViewCount = getTransientViewCount();
                    pw.println("Transient Views: " + transientViewCount);
                    for (int i = 0; i < transientViewCount; i++) {
                        ExpandableView child = (ExpandableView) getTransientView(i);
                        child.dump(pw, args);
                    }
                    View swipedView = mSwipeHelper.getSwipedView();
                    pw.println("Swiped view: " + swipedView);
                    if (swipedView instanceof ExpandableView expandableView) {
                        expandableView.dump(pw, args);
                    }
                });
    }

    private void dumpFooterViewVisibility(IndentingPrintWriter pw) {
        FooterViewRefactor.assertInLegacyMode();
        final boolean showDismissView = shouldShowDismissView();

        pw.println("showFooterView: " + shouldShowFooterView(showDismissView));
        DumpUtilsKt.withIncreasedIndent(
                pw,
                () -> {
                    pw.println("showDismissView: " + showDismissView);
                    DumpUtilsKt.withIncreasedIndent(
                            pw,
                            () -> {
                                pw.println(
                                        "hasActiveClearableNotifications: "
                                                + mController.hasActiveClearableNotifications(
                                                        ROWS_ALL));
                            });
                    pw.println();
                    pw.println("showHistory: " + mController.isHistoryEnabled());
                    pw.println();
                    pw.println(
                            "visibleNotificationCount: "
                                    + mController.getVisibleNotificationCount());
                    pw.println("mIsCurrentUserSetup: " + mIsCurrentUserSetup);
                    pw.println("onKeyguard: " + onKeyguard());
                    pw.println("mUpcomingStatusBarState: " + mUpcomingStatusBarState);
                    pw.println("mQsExpansionFraction: " + mQsExpansionFraction);
                    pw.println("mQsFullScreen: " + mQsFullScreen);
                    pw.println(
                            "mScreenOffAnimationController"
                                    + ".shouldHideNotificationsFooter: "
                                    + mScreenOffAnimationController
                                            .shouldHideNotificationsFooter());
                    pw.println("mIsRemoteInputActive: " + mIsRemoteInputActive);
                });
    }

    public boolean isFullyHidden() {
        return mAmbientState.isFullyHidden();
    }

    /**
     * Add a listener whenever the expanded height changes. The first value passed as an
     * argument is the expanded height and the second one is the appearFraction.
     *
     * @param listener the listener to notify.
     */
    public void addOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mExpandedHeightListeners.add(listener);
    }

    /**
     * Stop a listener from listening to the expandedHeight.
     */
    public void removeOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mExpandedHeightListeners.remove(listener);
    }

    void setHeadsUpAppearanceController(
            HeadsUpAppearanceController headsUpAppearanceController) {
        mHeadsUpAppearanceController = headsUpAppearanceController;
    }

    @VisibleForTesting
    public boolean isVisible(View child) {
        boolean hasClipBounds = child.getClipBounds(mTmpRect);
        return child.getVisibility() == View.VISIBLE
                && (!hasClipBounds || mTmpRect.height() > 0);
    }

    /** Whether the group is expanded to show the child notifications, and they are visible. */
    private boolean areChildrenVisible(ExpandableNotificationRow parent) {
        List<ExpandableNotificationRow> children = parent.getAttachedChildren();
        return isVisible(parent)
                && children != null
                && parent.areChildrenExpanded();
    }

    // Similar to #getRowsToDismissInBackend, but filters for visible views.
    private ArrayList<View> getVisibleViewsToAnimateAway(@SelectedRows int selection,
            boolean hideSilentSection) {
        final int viewCount = getChildCount();
        final ArrayList<View> viewsToHide = new ArrayList<>(viewCount);

        for (int i = 0; i < viewCount; i++) {
            final View view = getChildAt(i);

            if (view instanceof SectionHeaderView) {
                // The only SectionHeaderView we have is the silent section header.
                if (hideSilentSection) {
                    viewsToHide.add(view);
                }
            }

            if (view instanceof ExpandableNotificationRow parent) {
                if (isVisible(parent) && includeChildInClearAll(parent, selection)) {
                    viewsToHide.add(parent);
                }

                if (areChildrenVisible(parent)) {
                    for (ExpandableNotificationRow child : parent.getAttachedChildren()) {
                        if (isVisible(child) && includeChildInClearAll(child, selection)) {
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
            if (!(view instanceof ExpandableNotificationRow parent)) {
                continue;
            }
            if (includeChildInClearAll(parent, selection)) {
                viewsToRemove.add(parent);
            }
            List<ExpandableNotificationRow> children = parent.getAttachedChildren();
            if (isVisible(parent) && children != null) {
                for (ExpandableNotificationRow child : children) {
                    if (includeChildInClearAll(parent, selection)) {
                        viewsToRemove.add(child);
                    }
                }
            }
        }
        return viewsToRemove;
    }

    /** Clear all clearable notifications when the user requests it. */
    public void clearAllNotifications(boolean hideSilentSection) {
        clearNotifications(ROWS_ALL, /* closeShade = */ true, hideSilentSection);
    }

    /** Clear all clearable silent notifications when the user requests it. */
    public void clearSilentNotifications(boolean closeShade,
            boolean hideSilentSection) {
        clearNotifications(ROWS_GENTLE, closeShade, hideSilentSection);
    }

    /** Legacy version of clearNotifications below. Uses the old data source for notif stats. */
    void clearNotifications(@SelectedRows int selection, boolean closeShade) {
        FooterViewRefactor.assertInLegacyMode();
        final boolean hideSilentSection = !mController.hasNotifications(
                ROWS_GENTLE, false /* clearable */);
        clearNotifications(selection, closeShade, hideSilentSection);
    }

    /**
     * Collects a list of visible rows, and animates them away in a staggered fashion as if they
     * were dismissed. Notifications are dismissed in the backend via onClearAllAnimationsEnd.
     */
    void clearNotifications(@SelectedRows int selection, boolean closeShade,
            boolean hideSilentSection) {
        // Animate-swipe all dismissable notifications, then animate the shade closed
        final ArrayList<View> viewsToAnimateAway = getVisibleViewsToAnimateAway(selection,
                hideSilentSection);
        final ArrayList<ExpandableNotificationRow> rowsToDismissInBackend =
                getRowsToDismissInBackend(selection);
        if (mClearAllListener != null) {
            mClearAllListener.onClearAll(selection);
        }
        final Consumer<Boolean> dismissInBackend = (cancelled) -> {
            if (cancelled) {
                post(() -> onClearAllAnimationsEnd(rowsToDismissInBackend, selection));
            } else {
                onClearAllAnimationsEnd(rowsToDismissInBackend, selection);
            }
        };
        if (viewsToAnimateAway.isEmpty()) {
            dismissInBackend.accept(true);
            return;
        }
        // Disable normal animations
        setClearAllInProgress(true);
        mShadeNeedsToClose = closeShade;

        InteractionJankMonitor.getInstance().begin(this, CUJ_SHADE_CLEAR_ALL);
        // Decrease the delay for every row we animate to give the sense of
        // accelerating the swipes
        final int rowDelayDecrement = 5;
        int currentDelay = 60;
        int totalDelay = 0;
        final int numItems = viewsToAnimateAway.size();
        for (int i = numItems - 1; i >= 0; i--) {
            View view = viewsToAnimateAway.get(i);
            Consumer<Boolean> endRunnable = null;
            if (i == 0) {
                endRunnable = dismissInBackend;
            }
            dismissViewAnimated(view, endRunnable, totalDelay, ANIMATION_DURATION_SWIPE);
            currentDelay = Math.max(30, currentDelay - rowDelayDecrement);
            totalDelay += currentDelay;
        }
    }

    private boolean includeChildInClearAll(
            ExpandableNotificationRow row,
            @SelectedRows int selection) {
        return canChildBeCleared(row) && matchesSelection(row, selection);
    }

    /**
     * Register a {@link View.OnClickListener} to be invoked when the Manage button is clicked.
     */
    public void setManageButtonClickListener(@Nullable OnClickListener listener) {
        FooterViewRefactor.assertInLegacyMode();
        mManageButtonClickListener = listener;
        if (mFooterView != null) {
            mFooterView.setManageButtonClickListener(mManageButtonClickListener);
        }
    }

    @VisibleForTesting
    protected void inflateFooterView() {
        FooterViewRefactor.assertInLegacyMode();
        FooterView footerView = (FooterView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_notification_footer, this, false);
        setFooterView(footerView);
    }

    private void inflateEmptyShadeView() {
        EmptyShadeView oldView = mEmptyShadeView;
        EmptyShadeView view = (EmptyShadeView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_no_notifications, this, false);
        view.setOnClickListener(v -> {
            final boolean showHistory = mController.isHistoryEnabled();
            Intent intent = showHistory
                    ? new Intent(Settings.ACTION_NOTIFICATION_HISTORY)
                    : new Intent(Settings.ACTION_NOTIFICATION_SETTINGS);
            mActivityStarter.startActivity(intent, true, true, Intent.FLAG_ACTIVITY_SINGLE_TOP);
        });
        setEmptyShadeView(view);
        updateEmptyShadeView(
                oldView == null ? R.string.empty_shade_text : oldView.getTextResource(),
                oldView == null ? 0 : oldView.getFooterTextResource(),
                oldView == null ? 0 : oldView.getFooterIconResource());
    }

    /**
     * Updates expanded, dimmed and locked states of notification rows.
     */
    public void onUpdateRowStates() {

        // The following views will be moved to the end of mStackScroller. This counter represents
        // the offset from the last child. Initialized to 1 for the very last position. It is post-
        // incremented in the following "changeViewPosition" calls so that its value is correct for
        // subsequent calls.
        int offsetFromEnd = 1;
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
     *
     * @param height the new wake up height
     * @return the overflow how much the height is further than he lowest notification
     */
    public float setPulseHeight(float height) {
        float overflow;
        mAmbientState.setPulseHeight(height);
        if (mKeyguardBypassEnabled) {
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
        updateStackPosition();
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
            ExpandableView view = getChildAtIndex(i);
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
        mController.getNotificationRoundnessManager().setAnimatedChildren(mChildrenToAddAnimated);
    }

    void addSwipedOutView(View v) {
        mSwipedOutViews.add(v);
    }

    void onSwipeBegin(View viewSwiped) {
        if (!(viewSwiped instanceof ExpandableNotificationRow)) {
            return;
        }
        mSectionsManager.updateFirstAndLastViewsForAllSections(
                mSections,
                getChildrenWithBackground()
        );

        RoundableTargets targets = mController.getNotificationTargetsHelper().findRoundableTargets(
                (ExpandableNotificationRow) viewSwiped,
                this,
                mSectionsManager
        );

        mController.getNotificationRoundnessManager()
                .setViewsAffectedBySwipe(
                        targets.getBefore(),
                        targets.getSwiped(),
                        targets.getAfter());

        updateFirstAndLastBackgroundViews();
        requestDisallowInterceptTouchEvent(true);
        updateContinuousShadowDrawing();
        requestChildrenUpdate();
    }

    void onSwipeEnd() {
        updateFirstAndLastBackgroundViews();
        mController.getNotificationRoundnessManager()
                .setViewsAffectedBySwipe(null, null, null);
        // Round bottom corners for notification right before shelf.
        mShelf.updateAppearance();
    }

    /**
     * @param topHeadsUpRow the first headsUp row in z-order.
     */
    public void setTopHeadsUpRow(ExpandableNotificationRow topHeadsUpRow) {
        mTopHeadsUpRow = topHeadsUpRow;
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

    void setClearAllListener(ClearAllListener listener) {
        mClearAllListener = listener;
    }

    void setClearAllAnimationListener(ClearAllAnimationListener clearAllAnimationListener) {
        mClearAllAnimationListener = clearAllAnimationListener;
    }

    public void setHighPriorityBeforeSpeedBump(boolean highPriorityBeforeSpeedBump) {
        mHighPriorityBeforeSpeedBump = highPriorityBeforeSpeedBump;
    }

    void setFooterClearAllListener(FooterClearAllListener listener) {
        FooterViewRefactor.assertInLegacyMode();
        mFooterClearAllListener = listener;
    }

    void setClearAllFinishedWhilePanelExpandedRunnable(Runnable runnable) {
        mClearAllFinishedWhilePanelExpandedRunnable = runnable;
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
     * @param fraction Fraction of the lockscreen to shade transition. 0f for all other states.
     *                 Once the lockscreen to shade transition completes and the shade is 100% open
     *                 LockscreenShadeTransitionController resets fraction to 0
     *                 where it remains until the next lockscreen-to-shade transition.
     */
    public void setFractionToShade(float fraction) {
        mAmbientState.setFractionToShade(fraction);
        updateContentHeight();  // Recompute stack height with different section gap.
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

    @VisibleForTesting
    void updateSplitNotificationShade() {
        boolean split = mSplitShadeStateController.shouldUseSplitNotificationShade(getResources());
        if (split != mShouldUseSplitNotificationShade) {
            mShouldUseSplitNotificationShade = split;
            mShouldSkipTopPaddingAnimationAfterFold = true;
            mAmbientState.setUseSplitShade(split);
            updateDismissBehavior();
            updateUseRoundedRectClipping();
        }
    }

    private void updateDismissBehavior() {
        // On the split keyguard, dismissing with clipping without a visual boundary looks odd,
        // so let's use the content dismiss behavior instead.
        boolean dismissUsingRowTranslationX = !mShouldUseSplitNotificationShade
                || (mStatusBarState != StatusBarState.KEYGUARD && mIsExpanded);
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
        int[] absoluteCoords = new int[2];
        getLocationOnScreen(absoluteCoords);

        int left = Math.min(mLaunchAnimationParams.getLeft() - absoluteCoords[0],
                mRoundedRectClippingLeft);
        int right = Math.max(mLaunchAnimationParams.getRight() - absoluteCoords[0],
                mRoundedRectClippingRight);
        int bottom = Math.max(mLaunchAnimationParams.getBottom() - absoluteCoords[1],
                mRoundedRectClippingBottom);
        float expandProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                mLaunchAnimationParams.getProgress(0,
                        NotificationTransitionAnimatorController.ANIMATION_DURATION_TOP_ROUNDING));
        int top = (int) Math.min(MathUtils.lerp(mRoundedRectClippingTop,
                        mLaunchAnimationParams.getTop() - absoluteCoords[1], expandProgress),
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
        FooterViewRefactor.assertInLegacyMode();
        if (mIsCurrentUserSetup != isCurrentUserSetup) {
            mIsCurrentUserSetup = isCurrentUserSetup;
            updateFooter();
        }
    }

    /**
     * Sets a {@link StackStateLogger} which is notified as the {@link StackStateAnimator} updates
     * the views.
     */
    protected void setStackStateLogger(StackStateLogger logger) {
        mStateAnimator.setLogger(logger);
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

    /**
     * A listener that is notified when some ExpandableNotificationRow locations might have changed.
     */
    public interface OnNotificationLocationsChangedListener {
        /**
         * Called when the location of ExpandableNotificationRows might have changed.
         *
         * @param locations mapping of Notification keys to locations.
         */
        void onChildLocationsChanged(Callable<Map<String, Integer>> locations);
    }

    private void updateSpeedBumpIndex() {
        mSpeedBumpIndexDirty = true;
    }

    private void resetAllSwipeState() {
        Trace.beginSection("NSSL.resetAllSwipeState()");
        mSwipeHelper.resetTouchState();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            mSwipeHelper.forceResetSwipeState(child);
            if (child instanceof ExpandableNotificationRow childRow) {
                List<ExpandableNotificationRow> grandchildren = childRow.getAttachedChildren();
                if (grandchildren != null) {
                    for (ExpandableNotificationRow grandchild : grandchildren) {
                        mSwipeHelper.forceResetSwipeState(grandchild);
                    }
                }
            }
        }
        updateContinuousShadowDrawing();
        Trace.endSection();
    }

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
                        .animateZ(),

                // ANIMATION_TYPE_ACTIVATED_CHILD
                new AnimationFilter()
                        .animateZ(),

                // ANIMATION_TYPE_DIMMED
                new AnimationFilter(),

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
        if (v instanceof ExpandableNotificationRow row) {
            if (row.areGutsExposed() || !row.getEntry().hasFinishedInitialization()) {
                return false;
            }
            return row.canViewBeDismissed();
        }
        return false;
    }

    static boolean canChildBeCleared(View v) {
        if (v instanceof ExpandableNotificationRow row) {
            if (row.areGutsExposed() || !row.getEntry().hasFinishedInitialization()) {
                return false;
            }
            return row.canViewBeCleared();
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
    private void onClearAllAnimationsEnd(
            List<ExpandableNotificationRow> viewsToRemove,
            @SelectedRows int selectedRows) {
        InteractionJankMonitor.getInstance().end(CUJ_SHADE_CLEAR_ALL);
        if (mClearAllAnimationListener != null) {
            mClearAllAnimationListener.onAnimationEnd(viewsToRemove, selectedRows);
        }
    }

    void resetCheckSnoozeLeavebehind() {
        setCheckForLeaveBehind(true);
    }

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

    public HeadsUpTouchHelper.Callback getHeadsUpCallback() {
        return mHeadsUpCallback;
    }

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

    private final ExpandHelper.Callback mExpandHelperCallback = new ExpandHelper.Callback() {
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
            if (v instanceof ExpandableNotificationRow row) {
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

    float getAppearFraction() {
        return mLastSentAppear;
    }

    float getExpandedHeight() {
        return mLastSentExpandedHeight;
    }

    /**
     * Enum for selecting some or all notification rows (does not included non-notif views).
     */
    @Retention(SOURCE)
    @IntDef({ROWS_ALL, ROWS_HIGH_PRIORITY, ROWS_GENTLE})
    @interface SelectedRows {
    }

    /**
     * All rows representing notifs.
     */
    public static final int ROWS_ALL = 0;
    /**
     * Only rows where entry.isHighPriority() is true.
     */
    public static final int ROWS_HIGH_PRIORITY = 1;
    /**
     * Only rows where entry.isHighPriority() is false.
     */
    public static final int ROWS_GENTLE = 2;

    interface ClearAllListener {
        void onClearAll(@SelectedRows int selectedRows);
    }

    interface FooterClearAllListener {
        void onClearAll();
    }

    interface ClearAllAnimationListener {
        void onAnimationEnd(
                List<ExpandableNotificationRow> viewsToRemove, @SelectedRows int selectedRows);
    }

    /**
     *
     */
    public interface OnNotificationRemovedListener {
        /**
         *
         * @param child
         * @param isTransferInProgress
         */
        void onNotificationRemoved(ExpandableView child, boolean isTransferInProgress);
    }
}
