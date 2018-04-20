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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.notification.ActivityLaunchAnimator.ExpandAnimationParameters;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.PowerManager;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.DejankUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.stack.AnimationProperties;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class NotificationPanelView extends PanelView implements
        ExpandableView.OnHeightChangedListener,
        View.OnClickListener, NotificationStackScrollLayout.OnOverscrollTopChangedListener,
        KeyguardAffordanceHelper.Callback, NotificationStackScrollLayout.OnEmptySpaceClickListener,
        OnHeadsUpChangedListener, QS.HeightListener {

    private static final boolean DEBUG = false;

    // Cap and total height of Roboto font. Needs to be adjusted when font for the big clock is
    // changed.
    private static final int CAP_HEIGHT = 1456;
    private static final int FONT_HEIGHT = 2163;

    private static final float LOCK_ICON_ACTIVE_SCALE = 1.2f;

    static final String COUNTER_PANEL_OPEN = "panel_open";
    static final String COUNTER_PANEL_OPEN_QS = "panel_open_qs";
    private static final String COUNTER_PANEL_OPEN_PEEK = "panel_open_peek";

    private static final Rect mDummyDirtyRect = new Rect(0, 0, 1, 1);

    public static final long DOZE_ANIMATION_DURATION = 700;

    private static final AnimationProperties CLOCK_ANIMATION_PROPERTIES = new AnimationProperties()
            .setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
    private static final FloatProperty<NotificationPanelView> SET_DARK_AMOUNT_PROPERTY =
            new FloatProperty<NotificationPanelView>("mDarkAmount") {
                @Override
                public void setValue(NotificationPanelView object, float value) {
                    object.setDarkAmount(value);
                }

                @Override
                public Float get(NotificationPanelView object) {
                    return object.mDarkAmount;
                }
            };
    private final PowerManager mPowerManager;
    private final AccessibilityManager mAccessibilityManager;

    private KeyguardAffordanceHelper mAffordanceHelper;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private KeyguardStatusBarView mKeyguardStatusBar;
    private QS mQs;
    private FrameLayout mQsFrame;
    private KeyguardStatusView mKeyguardStatusView;
    private View mQsNavbarScrim;
    protected NotificationsQuickSettingsContainer mNotificationContainerParent;
    protected NotificationStackScrollLayout mNotificationStackScroller;
    private boolean mAnimateNextPositionUpdate;

    private int mTrackingPointer;
    private VelocityTracker mQsVelocityTracker;
    private boolean mQsTracking;

    /**
     * If set, the ongoing touch gesture might both trigger the expansion in {@link PanelView} and
     * the expansion for quick settings.
     */
    private boolean mConflictingQsExpansionGesture;

    /**
     * Whether we are currently handling a motion gesture in #onInterceptTouchEvent, but haven't
     * intercepted yet.
     */
    private boolean mIntercepting;
    private boolean mPanelExpanded;
    private boolean mQsExpanded;
    private boolean mQsExpandedWhenExpandingStarted;
    private boolean mQsFullyExpanded;
    private boolean mKeyguardShowing;
    private boolean mDozing;
    private boolean mDozingOnDown;
    protected int mStatusBarState;
    private float mInitialHeightOnTouch;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mLastTouchX;
    private float mLastTouchY;
    protected float mQsExpansionHeight;
    protected int mQsMinExpansionHeight;
    protected int mQsMaxExpansionHeight;
    private int mQsPeekHeight;
    private int mBouncerTop;
    private boolean mStackScrollerOverscrolling;
    private boolean mQsExpansionFromOverscroll;
    private float mLastOverscroll;
    protected boolean mQsExpansionEnabled = true;
    private ValueAnimator mQsExpansionAnimator;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;
    private boolean mUnlockIconActive;
    private int mNotificationsHeaderCollideDistance;
    private int mUnlockMoveDistance;
    private float mEmptyDragAmount;

    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private KeyguardClockPositionAlgorithm.Result mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    private boolean mBlockTouches;
    // Used for two finger gesture as well as accessibility shortcut to QS.
    private boolean mQsExpandImmediate;
    private boolean mTwoFingerQsExpandPossible;

    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private boolean mQsAnimatorExpand;
    private boolean mIsLaunchTransitionFinished;
    private boolean mIsLaunchTransitionRunning;
    private Runnable mLaunchAnimationEndRunnable;
    private boolean mOnlyAffordanceInThisMotion;
    private boolean mKeyguardStatusViewAnimating;
    private ValueAnimator mQsSizeChangeAnimator;

    private boolean mShowEmptyShadeView;

    private boolean mQsScrimEnabled = true;
    private boolean mLastAnnouncementWasQuickSettings;
    private boolean mQsTouchAboveFalsingThreshold;
    private int mQsFalsingThreshold;

    private float mKeyguardStatusBarAnimateAlpha = 1f;
    private int mOldLayoutDirection;
    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private boolean mIsExpansionFromHeadsUp;
    private boolean mListenForHeadsUp;
    private int mNavigationBarBottomHeight;
    private boolean mExpandingFromHeadsUp;
    private boolean mCollapsedOnDown;
    private int mPositionMinSideMargin;
    private int mMaxFadeoutHeight;
    private int mLastOrientation = -1;
    private boolean mClosingWithAlphaFadeOut;
    private boolean mHeadsUpAnimatingAway;
    private boolean mLaunchingAffordance;
    private FalsingManager mFalsingManager;
    private String mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;

    private Runnable mHeadsUpExistenceChangedRunnable = new Runnable() {
        @Override
        public void run() {
            setHeadsUpAnimatingAway(false);
            notifyBarPanelExpansionChanged();
        }
    };
    private NotificationGroupManager mGroupManager;
    private boolean mShowIconsWhenExpanded;
    private int mIndicationBottomPadding;
    private int mAmbientIndicationBottomPadding;
    private boolean mIsFullWidth;
    private float mDarkAmount;
    private float mDarkAmountTarget;
    private boolean mPulsing;
    private LockscreenGestureLogger mLockscreenGestureLogger = new LockscreenGestureLogger();
    private boolean mNoVisibleNotifications = true;
    private ValueAnimator mDarkAnimator;
    private boolean mUserSetupComplete;
    private int mQsNotificationTopPadding;
    private float mExpandOffset;
    private boolean mHideIconsDuringNotificationLaunch = true;
    private int mStackScrollerMeasuringPass;
    private ArrayList<Consumer<ExpandableNotificationRow>> mTrackingHeadsUpListeners
            = new ArrayList<>();
    private ArrayList<Runnable> mVerticalTranslationListener = new ArrayList<>();
    private HeadsUpAppearanceController mHeadsUpAppearanceController;

    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(!DEBUG);
        mFalsingManager = FalsingManager.getInstance(context);
        mPowerManager = context.getSystemService(PowerManager.class);
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
    }

    public void setStatusBar(StatusBar bar) {
        mStatusBar = bar;
        mKeyguardBottomArea.setStatusBar(mStatusBar);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mKeyguardStatusBar = findViewById(R.id.keyguard_header);
        mKeyguardStatusView = findViewById(R.id.keyguard_status_view);

        mNotificationContainerParent = findViewById(R.id.notification_container_parent);
        mNotificationStackScroller = findViewById(R.id.notification_stack_scroller);
        mNotificationStackScroller.setOnHeightChangedListener(this);
        mNotificationStackScroller.setOverscrollTopChangedListener(this);
        mNotificationStackScroller.setOnEmptySpaceClickListener(this);
        addTrackingHeadsUpListener(mNotificationStackScroller::setTrackingHeadsUp);
        mKeyguardBottomArea = findViewById(R.id.keyguard_bottom_area);
        mQsNavbarScrim = findViewById(R.id.qs_navbar_scrim);
        mLastOrientation = getResources().getConfiguration().orientation;

        initBottomArea();

        mQsFrame = findViewById(R.id.qs_frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        FragmentHostManager.get(this).addTagListener(QS.TAG, mFragmentListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        FragmentHostManager.get(this).removeTagListener(QS.TAG, mFragmentListener);
    }

    @Override
    protected void loadDimens() {
        super.loadDimens();
        mFlingAnimationUtils = new FlingAnimationUtils(getContext(), 0.4f);
        mStatusBarMinHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mQsPeekHeight = getResources().getDimensionPixelSize(R.dimen.qs_peek_height);
        mNotificationsHeaderCollideDistance =
                getResources().getDimensionPixelSize(R.dimen.header_notifications_collide_distance);
        mUnlockMoveDistance = getResources().getDimensionPixelOffset(R.dimen.unlock_move_distance);
        mClockPositionAlgorithm.loadDimens(getResources());
        mQsFalsingThreshold = getResources().getDimensionPixelSize(
                R.dimen.qs_falsing_threshold);
        mPositionMinSideMargin = getResources().getDimensionPixelSize(
                R.dimen.notification_panel_min_side_margin);
        mMaxFadeoutHeight = getResources().getDimensionPixelSize(
                R.dimen.max_notification_fadeout_height);
        mIndicationBottomPadding = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_bottom_padding);
        mQsNotificationTopPadding = getResources().getDimensionPixelSize(
                R.dimen.qs_notification_padding);
    }

    public void updateResources() {
        Resources res = getResources();
        int qsWidth = res.getDimensionPixelSize(R.dimen.qs_panel_width);
        int panelGravity = getResources().getInteger(R.integer.notification_panel_layout_gravity);
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mQsFrame.getLayoutParams();
        if (lp.width != qsWidth || lp.gravity != panelGravity) {
            lp.width = qsWidth;
            lp.gravity = panelGravity;
            mQsFrame.setLayoutParams(lp);
        }

        int panelWidth = res.getDimensionPixelSize(R.dimen.notification_panel_width);
        lp = (FrameLayout.LayoutParams) mNotificationStackScroller.getLayoutParams();
        if (lp.width != panelWidth || lp.gravity != panelGravity) {
            lp.width = panelWidth;
            lp.gravity = panelGravity;
            mNotificationStackScroller.setLayoutParams(lp);
        }
    }

    public void onThemeChanged() {
        // Re-inflate the status view group.
        int index = indexOfChild(mKeyguardStatusView);
        removeView(mKeyguardStatusView);
        mKeyguardStatusView = (KeyguardStatusView) LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_status_view,
                this,
                false);
        addView(mKeyguardStatusView, index);

        // Update keyguard bottom area
        index = indexOfChild(mKeyguardBottomArea);
        removeView(mKeyguardBottomArea);
        mKeyguardBottomArea = (KeyguardBottomAreaView) LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_bottom_area,
                this,
                false);
        addView(mKeyguardBottomArea, index);
        initBottomArea();
        setDarkAmount(mDarkAmount);

        setKeyguardStatusViewVisibility(mStatusBarState, false, false);
        setKeyguardBottomAreaVisibility(mStatusBarState, false);
    }

    private void initBottomArea() {
        mAffordanceHelper = new KeyguardAffordanceHelper(this, getContext());
        mKeyguardBottomArea.setAffordanceHelper(mAffordanceHelper);
        mKeyguardBottomArea.setStatusBar(mStatusBar);
        mKeyguardBottomArea.setUserSetupComplete(mUserSetupComplete);
    }

    public void setKeyguardIndicationController(KeyguardIndicationController indicationController) {
        mKeyguardBottomArea.setKeyguardIndicationController(indicationController);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setIsFullWidth(mNotificationStackScroller.getWidth() == getWidth());

        // Update Clock Pivot
        mKeyguardStatusView.setPivotX(getWidth() / 2);
        mKeyguardStatusView.setPivotY((FONT_HEIGHT - CAP_HEIGHT) / 2048f *
                mKeyguardStatusView.getClockTextSize());

        // Calculate quick setting heights.
        int oldMaxHeight = mQsMaxExpansionHeight;
        if (mQs != null) {
            mQsMinExpansionHeight = mKeyguardShowing ? 0 : mQs.getQsMinExpansionHeight();
            mQsMaxExpansionHeight = mQs.getDesiredHeight();
        }
        positionClockAndNotifications();
        if (mQsExpanded && mQsFullyExpanded) {
            mQsExpansionHeight = mQsMaxExpansionHeight;
            requestScrollerTopPaddingUpdate(false /* animate */);
            requestPanelHeightUpdate();

            // Size has changed, start an animation.
            if (mQsMaxExpansionHeight != oldMaxHeight) {
                startQsSizeChangeAnimation(oldMaxHeight, mQsMaxExpansionHeight);
            }
        } else if (!mQsExpanded) {
            setQsExpansion(mQsMinExpansionHeight + mLastOverscroll);
        }
        updateExpandedHeight(getExpandedHeight());
        updateHeader();

        // If we are running a size change animation, the animation takes care of the height of
        // the container. However, if we are not animating, we always need to make the QS container
        // the desired height so when closing the QS detail, it stays smaller after the size change
        // animation is finished but the detail view is still being animated away (this animation
        // takes longer than the size change animation).
        if (mQsSizeChangeAnimator == null && mQs != null) {
            mQs.setHeightOverride(mQs.getDesiredHeight());
        }
        updateMaxHeadsUpTranslation();
    }

    private void setIsFullWidth(boolean isFullWidth) {
        mIsFullWidth = isFullWidth;
        mNotificationStackScroller.setIsFullWidth(isFullWidth);
    }

    private void startQsSizeChangeAnimation(int oldHeight, final int newHeight) {
        if (mQsSizeChangeAnimator != null) {
            oldHeight = (int) mQsSizeChangeAnimator.getAnimatedValue();
            mQsSizeChangeAnimator.cancel();
        }
        mQsSizeChangeAnimator = ValueAnimator.ofInt(oldHeight, newHeight);
        mQsSizeChangeAnimator.setDuration(300);
        mQsSizeChangeAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mQsSizeChangeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                requestScrollerTopPaddingUpdate(false /* animate */);
                requestPanelHeightUpdate();
                int height = (int) mQsSizeChangeAnimator.getAnimatedValue();
                mQs.setHeightOverride(height);
            }
        });
        mQsSizeChangeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mQsSizeChangeAnimator = null;
            }
        });
        mQsSizeChangeAnimator.start();
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    private void positionClockAndNotifications() {
        boolean animate = mNotificationStackScroller.isAddOrRemoveAnimationPending();
        boolean animateClock = animate || mAnimateNextPositionUpdate;
        int stackScrollerPadding;
        if (mStatusBarState != StatusBarState.KEYGUARD) {
            stackScrollerPadding = (mQs != null ? mQs.getHeader().getHeight() : 0) + mQsPeekHeight
            +  mQsNotificationTopPadding;
        } else {
            int totalHeight = getHeight();
            int bottomPadding = Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding);
            mClockPositionAlgorithm.setup(
                    mStatusBarMinHeight,
                    totalHeight - bottomPadding,
                    mNotificationStackScroller.getIntrinsicContentHeight(),
                    getExpandedFraction(),
                    totalHeight,
                    mKeyguardStatusView.getHeight(),
                    mDarkAmount,
                    mStatusBar.isKeyguardCurrentlySecure(),
                    mPulsing,
                    mBouncerTop);
            mClockPositionAlgorithm.run(mClockPositionResult);
            PropertyAnimator.setProperty(mKeyguardStatusView, AnimatableProperty.X,
                    mClockPositionResult.clockX, CLOCK_ANIMATION_PROPERTIES, animateClock);
            PropertyAnimator.setProperty(mKeyguardStatusView, AnimatableProperty.Y,
                    mClockPositionResult.clockY, CLOCK_ANIMATION_PROPERTIES, animateClock);
            updateClock();
            stackScrollerPadding = mClockPositionResult.stackScrollerPadding;
        }
        mNotificationStackScroller.setIntrinsicPadding(stackScrollerPadding);
        mNotificationStackScroller.setAntiBurnInOffsetX(mClockPositionResult.clockX);
        mKeyguardBottomArea.setBurnInXOffset(mClockPositionResult.clockX);

        mStackScrollerMeasuringPass++;
        requestScrollerTopPaddingUpdate(animate);
        mStackScrollerMeasuringPass = 0;
        mAnimateNextPositionUpdate = false;
    }

    /**
     * @param maximum the maximum to return at most
     * @return the maximum keyguard notifications that can fit on the screen
     */
    public int computeMaxKeyguardNotifications(int maximum) {
        float minPadding = mClockPositionAlgorithm.getMinStackScrollerPadding();
        int notificationPadding = Math.max(1, getResources().getDimensionPixelSize(
                R.dimen.notification_divider_height));
        NotificationShelf shelf = mNotificationStackScroller.getNotificationShelf();
        float shelfSize = shelf.getVisibility() == GONE ? 0
                : shelf.getIntrinsicHeight() + notificationPadding;
        float availableSpace = mNotificationStackScroller.getHeight() - minPadding - shelfSize
                - Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding)
                - mKeyguardStatusView.getLogoutButtonHeight();
        int count = 0;
        for (int i = 0; i < mNotificationStackScroller.getChildCount(); i++) {
            ExpandableView child = (ExpandableView) mNotificationStackScroller.getChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            boolean suppressedSummary = mGroupManager.isSummaryOfSuppressedGroup(
                    row.getStatusBarNotification());
            if (suppressedSummary) {
                continue;
            }
            if (!mStatusBar.getNotificationLockscreenUserManager().shouldShowOnKeyguard(
                    row.getStatusBarNotification())) {
                continue;
            }
            if (row.isRemoved()) {
                continue;
            }
            availableSpace -= child.getMinHeight(true /* ignoreTemporaryStates */)
                    + notificationPadding;
            if (availableSpace >= 0 && count < maximum) {
                count++;
            } else if (availableSpace > -shelfSize) {
                // if we are exactly the last view, then we can show us still!
                for (int j = i + 1; j < mNotificationStackScroller.getChildCount(); j++) {
                    if (mNotificationStackScroller.getChildAt(j)
                            instanceof ExpandableNotificationRow) {
                        return count;
                    }
                }
                count++;
                return count;
            } else {
                return count;
            }
        }
        return count;
    }

    public void setBouncerTop(int bouncerTop) {
        mBouncerTop = bouncerTop;
        positionClockAndNotifications();
    }

    private void updateClock() {
        if (!mKeyguardStatusViewAnimating) {
            mKeyguardStatusView.setAlpha(mClockPositionResult.clockAlpha);
        }
    }

    public void animateToFullShade(long delay) {
        mNotificationStackScroller.goToFullShade(delay);
        requestLayout();
        mAnimateNextPositionUpdate = true;
    }

    public void setQsExpansionEnabled(boolean qsExpansionEnabled) {
        mQsExpansionEnabled = qsExpansionEnabled;
        if (mQs == null) return;
        mQs.setHeaderClickable(qsExpansionEnabled);
    }

    @Override
    public void resetViews() {
        mIsLaunchTransitionFinished = false;
        mBlockTouches = false;
        mUnlockIconActive = false;
        if (!mLaunchingAffordance) {
            mAffordanceHelper.reset(false);
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;
        }
        closeQs();
        mStatusBar.getGutsManager().closeAndSaveGuts(true /* leavebehind */, true /* force */,
                true /* controls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
        mNotificationStackScroller.setOverScrollAmount(0f, true /* onTop */, false /* animate */,
                true /* cancelAnimators */);
        mNotificationStackScroller.resetScrollPosition();
    }

    public void closeQs() {
        cancelQsAnimation();
        setQsExpansion(mQsMinExpansionHeight);
    }

    public void animateCloseQs() {
        if (mQsExpansionAnimator != null) {
            if (!mQsAnimatorExpand) {
                return;
            }
            float height = mQsExpansionHeight;
            mQsExpansionAnimator.cancel();
            setQsExpansion(height);
        }
        flingSettings(0 /* vel */, false);
    }

    public void openQs() {
        cancelQsAnimation();
        if (mQsExpansionEnabled) {
            setQsExpansion(mQsMaxExpansionHeight);
        }
    }

    public void expandWithQs() {
        if (mQsExpansionEnabled) {
            mQsExpandImmediate = true;
        }
        expand(true /* animate */);
    }

    public void expandWithoutQs() {
        if (isQsExpanded()) {
            flingSettings(0 /* velocity */, false /* expand */);
        } else {
            expand(true /* animate */);
        }
    }

    @Override
    public void fling(float vel, boolean expand) {
        GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
        if (gr != null) {
            gr.tag("fling " + ((vel > 0) ? "open" : "closed"), "notifications,v=" + vel);
        }
        super.fling(vel, expand);
    }

    @Override
    protected void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        mHeadsUpTouchHelper.notifyFling(!expand);
        setClosingWithAlphaFadeout(!expand && getFadeoutAlpha() == 1.0f);
        super.flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mBlockTouches || mQsFullyExpanded && mQs.onInterceptTouchEvent(event)) {
            return false;
        }
        initDownStates(event);
        if (mBar.panelEnabled() && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
            mIsExpansionFromHeadsUp = true;
            MetricsLogger.count(mContext, COUNTER_PANEL_OPEN, 1);
            MetricsLogger.count(mContext, COUNTER_PANEL_OPEN_PEEK, 1);
            return true;
        }

        if (!isFullyCollapsed() && onQsIntercept(event)) {
            return true;
        }
        return super.onInterceptTouchEvent(event);
    }

    private boolean onQsIntercept(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mIntercepting = true;
                mInitialTouchY = y;
                mInitialTouchX = x;
                initVelocityTracker();
                trackMovement(event);
                if (shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, 0)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mQsExpansionAnimator != null) {
                    onQsExpansionStarted();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mQsTracking = true;
                    mIntercepting = false;
                    mNotificationStackScroller.cancelLongPress();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                trackMovement(event);
                if (mQsTracking) {

                    // Already tracking because onOverscrolled was called. We need to update here
                    // so we don't stop for a frame until the next touch event gets handled in
                    // onTouchEvent.
                    setQsExpansion(h + mInitialHeightOnTouch);
                    trackMovement(event);
                    mIntercepting = false;
                    return true;
                }
                if (Math.abs(h) > mTouchSlop && Math.abs(h) > Math.abs(x - mInitialTouchX)
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, h)) {
                    mQsTracking = true;
                    onQsExpansionStarted();
                    notifyExpandingFinished();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mIntercepting = false;
                    mNotificationStackScroller.cancelLongPress();
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mQsTracking) {
                    flingQsWithCurrentVelocity(y,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                    mQsTracking = false;
                }
                mIntercepting = false;
                break;
        }
        return false;
    }

    @Override
    protected boolean isInContentBounds(float x, float y) {
        float stackScrollerX = mNotificationStackScroller.getX();
        return !mNotificationStackScroller.isBelowLastNotification(x - stackScrollerX, y)
                && stackScrollerX < x && x < stackScrollerX + mNotificationStackScroller.getWidth();
    }

    private void initDownStates(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mOnlyAffordanceInThisMotion = false;
            mQsTouchAboveFalsingThreshold = mQsFullyExpanded;
            mDozingOnDown = isDozing();
            mCollapsedOnDown = isFullyCollapsed();
            mListenForHeadsUp = mCollapsedOnDown && mHeadsUpManager.hasPinnedHeadsUp();
        }
    }

    private void flingQsWithCurrentVelocity(float y, boolean isCancelMotionEvent) {
        float vel = getCurrentQSVelocity();
        final boolean expandsQs = flingExpandsQs(vel);
        if (expandsQs) {
            logQsSwipeDown(y);
        }
        flingSettings(vel, expandsQs && !isCancelMotionEvent);
    }

    private void logQsSwipeDown(float y) {
        float vel = getCurrentQSVelocity();
        final int gesture = mStatusBarState == StatusBarState.KEYGUARD
                ? MetricsEvent.ACTION_LS_QS
                : MetricsEvent.ACTION_SHADE_QS_PULL;
        mLockscreenGestureLogger.write(gesture,
                (int) ((y - mInitialTouchY) / mStatusBar.getDisplayDensity()),
                (int) (vel / mStatusBar.getDisplayDensity()));
    }

    private boolean flingExpandsQs(float vel) {
        if (isFalseTouch()) {
            return false;
        }
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return getQsExpansionFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    private boolean isFalseTouch() {
        if (!needsAntiFalsing()) {
            return false;
        }
        if (mFalsingManager.isClassiferEnabled()) {
            return mFalsingManager.isFalseTouch();
        }
        return !mQsTouchAboveFalsingThreshold;
    }

    private float getQsExpansionFraction() {
        return Math.min(1f, (mQsExpansionHeight - mQsMinExpansionHeight)
                / (mQsMaxExpansionHeight - mQsMinExpansionHeight));
    }

    @Override
    protected float getOpeningHeight() {
        return mNotificationStackScroller.getOpeningHeight();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mBlockTouches || (mQs != null && mQs.isCustomizing())) {
            return false;
        }
        initDownStates(event);
        if (mListenForHeadsUp && !mHeadsUpTouchHelper.isTrackingHeadsUp()
                && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
            mIsExpansionFromHeadsUp = true;
            MetricsLogger.count(mContext, COUNTER_PANEL_OPEN_PEEK, 1);
        }
        boolean handled = false;
        if ((!mIsExpanding || mHintAnimationRunning)
                && !mQsExpanded
                && mStatusBar.getBarState() != StatusBarState.SHADE
                && !mDozing) {
            handled |= mAffordanceHelper.onTouchEvent(event);
        }
        if (mOnlyAffordanceInThisMotion) {
            return true;
        }
        handled |= mHeadsUpTouchHelper.onTouchEvent(event);

        if (!mHeadsUpTouchHelper.isTrackingHeadsUp() && handleQsTouch(event)) {
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyCollapsed()) {
            MetricsLogger.count(mContext, COUNTER_PANEL_OPEN, 1);
            updateVerticalPanelPosition(event.getX());
            handled = true;
        }
        handled |= super.onTouchEvent(event);
        return mDozing ? handled : true;
    }

    private boolean handleQsTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && getExpandedFraction() == 1f
                && mStatusBar.getBarState() != StatusBarState.KEYGUARD && !mQsExpanded
                && mQsExpansionEnabled) {

            // Down in the empty area while fully expanded - go to QS.
            mQsTracking = true;
            mConflictingQsExpansionGesture = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getX();
            mInitialTouchX = event.getY();
        }
        if (!isFullyCollapsed()) {
            handleQsDown(event);
        }
        if (!mQsExpandImmediate && mQsTracking) {
            onQsTouch(event);
            if (!mConflictingQsExpansionGesture) {
                return true;
            }
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            mConflictingQsExpansionGesture = false;
        }
        if (action == MotionEvent.ACTION_DOWN && isFullyCollapsed()
                && mQsExpansionEnabled) {
            mTwoFingerQsExpandPossible = true;
        }
        if (mTwoFingerQsExpandPossible && isOpenQsEvent(event)
                && event.getY(event.getActionIndex()) < mStatusBarMinHeight) {
            MetricsLogger.count(mContext, COUNTER_PANEL_OPEN_QS, 1);
            mQsExpandImmediate = true;
            requestPanelHeightUpdate();

            // Normally, we start listening when the panel is expanded, but here we need to start
            // earlier so the state is already up to date when dragging down.
            setListening(true);
        }
        return false;
    }

    private boolean isInQsArea(float x, float y) {
        return (x >= mQsFrame.getX()
                && x <= mQsFrame.getX() + mQsFrame.getWidth())
                && (y <= mNotificationStackScroller.getBottomMostNotificationBottom()
                || y <= mQs.getView().getY() + mQs.getView().getHeight());
    }

    private boolean isOpenQsEvent(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        final int action = event.getActionMasked();

        final boolean twoFingerDrag = action == MotionEvent.ACTION_POINTER_DOWN
                && pointerCount == 2;

        final boolean stylusButtonClickDrag = action == MotionEvent.ACTION_DOWN
                && (event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
                        || event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY));

        final boolean mouseButtonClickDrag = action == MotionEvent.ACTION_DOWN
                && (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)
                        || event.isButtonPressed(MotionEvent.BUTTON_TERTIARY));

        return twoFingerDrag || stylusButtonClickDrag || mouseButtonClickDrag;
    }

    private void handleQsDown(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && shouldQuickSettingsIntercept(event.getX(), event.getY(), -1)) {
            mFalsingManager.onQsDown();
            mQsTracking = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getX();
            mInitialTouchX = event.getY();

            // If we interrupt an expansion gesture here, make sure to update the state correctly.
            notifyExpandingFinished();
        }
    }

    @Override
    protected boolean flingExpands(float vel, float vectorVel, float x, float y) {
        boolean expands = super.flingExpands(vel, vectorVel, x, y);

        // If we are already running a QS expansion, make sure that we keep the panel open.
        if (mQsExpansionAnimator != null) {
            expands = true;
        }
        return expands;
    }

    @Override
    protected boolean hasConflictingGestures() {
        return mStatusBar.getBarState() != StatusBarState.SHADE;
    }

    @Override
    protected boolean shouldGestureIgnoreXTouchSlop(float x, float y) {
        return !mAffordanceHelper.isOnAffordanceIcon(x, y);
    }

    private void onQsTouch(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);
        final float h = y - mInitialTouchY;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mQsTracking = true;
                mInitialTouchY = y;
                mInitialTouchX = x;
                onQsExpansionStarted();
                mInitialHeightOnTouch = mQsExpansionHeight;
                initVelocityTracker();
                trackMovement(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                setQsExpansion(h + mInitialHeightOnTouch);
                if (h >= getFalsingThreshold()) {
                    mQsTouchAboveFalsingThreshold = true;
                }
                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mQsTracking = false;
                mTrackingPointer = -1;
                trackMovement(event);
                float fraction = getQsExpansionFraction();
                if (fraction != 0f || y >= mInitialTouchY) {
                    flingQsWithCurrentVelocity(y,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                }
                if (mQsVelocityTracker != null) {
                    mQsVelocityTracker.recycle();
                    mQsVelocityTracker = null;
                }
                break;
        }
    }

    private int getFalsingThreshold() {
        float factor = mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        return (int) (mQsFalsingThreshold * factor);
    }

    @Override
    public void onOverscrollTopChanged(float amount, boolean isRubberbanded) {
        cancelQsAnimation();
        if (!mQsExpansionEnabled) {
            amount = 0f;
        }
        float rounded = amount >= 1f ? amount : 0f;
        setOverScrolling(rounded != 0f && isRubberbanded);
        mQsExpansionFromOverscroll = rounded != 0f;
        mLastOverscroll = rounded;
        updateQsState();
        setQsExpansion(mQsMinExpansionHeight + rounded);
    }

    @Override
    public void flingTopOverscroll(float velocity, boolean open) {
        mLastOverscroll = 0f;
        mQsExpansionFromOverscroll = false;
        setQsExpansion(mQsExpansionHeight);
        flingSettings(!mQsExpansionEnabled && open ? 0f : velocity, open && mQsExpansionEnabled,
                new Runnable() {
                    @Override
                    public void run() {
                        mStackScrollerOverscrolling = false;
                        setOverScrolling(false);
                        updateQsState();
                    }
                }, false /* isClick */);
    }

    private void setOverScrolling(boolean overscrolling) {
        mStackScrollerOverscrolling = overscrolling;
        if (mQs == null) return;
        mQs.setOverscrolling(overscrolling);
    }

    private void onQsExpansionStarted() {
        onQsExpansionStarted(0);
    }

    protected void onQsExpansionStarted(int overscrollAmount) {
        cancelQsAnimation();
        cancelHeightAnimator();

        // Reset scroll position and apply that position to the expanded height.
        float height = mQsExpansionHeight - overscrollAmount;
        setQsExpansion(height);
        requestPanelHeightUpdate();
        mNotificationStackScroller.checkSnoozeLeavebehind();
    }

    private void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
            requestPanelHeightUpdate();
            mFalsingManager.setQsExpanded(expanded);
            mStatusBar.setQsExpanded(expanded);
            mNotificationContainerParent.setQsExpanded(expanded);
        }
    }

    public void setBarState(int statusBarState, boolean keyguardFadingAway,
            boolean goingToFullShade) {
        int oldState = mStatusBarState;
        boolean keyguardShowing = statusBarState == StatusBarState.KEYGUARD;
        setKeyguardStatusViewVisibility(statusBarState, keyguardFadingAway, goingToFullShade);
        setKeyguardBottomAreaVisibility(statusBarState, goingToFullShade);

        mStatusBarState = statusBarState;
        mKeyguardShowing = keyguardShowing;
        if (mQs != null) {
            mQs.setKeyguardShowing(mKeyguardShowing);
        }

        if (oldState == StatusBarState.KEYGUARD
                && (goingToFullShade || statusBarState == StatusBarState.SHADE_LOCKED)) {
            animateKeyguardStatusBarOut();
            long delay = mStatusBarState == StatusBarState.SHADE_LOCKED
                    ? 0 : mStatusBar.calculateGoingToFullShadeDelay();
            mQs.animateHeaderSlidingIn(delay);
        } else if (oldState == StatusBarState.SHADE_LOCKED
                && statusBarState == StatusBarState.KEYGUARD) {
            animateKeyguardStatusBarIn(StackStateAnimator.ANIMATION_DURATION_STANDARD);
            mQs.animateHeaderSlidingOut();
        } else {
            mKeyguardStatusBar.setAlpha(1f);
            mKeyguardStatusBar.setVisibility(keyguardShowing ? View.VISIBLE : View.INVISIBLE);
            if (keyguardShowing && oldState != mStatusBarState) {
                mKeyguardBottomArea.onKeyguardShowingChanged();
                if (mQs != null) {
                    mQs.hideImmediately();
                }
            }
        }
        if (keyguardShowing) {
            updateDozingVisibilities(false /* animate */);
        }
        resetVerticalPanelPosition();
        updateQsState();
    }

    private final Runnable mAnimateKeyguardStatusViewInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusViewAnimating = false;
            mKeyguardStatusView.setVisibility(View.INVISIBLE);
        }
    };

    private final Runnable mAnimateKeyguardStatusViewGoneEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusViewAnimating = false;
            mKeyguardStatusView.setVisibility(View.GONE);
        }
    };

    private final Runnable mAnimateKeyguardStatusViewVisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusViewAnimating = false;
        }
    };

    private final Runnable mAnimateKeyguardStatusBarInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusBar.setVisibility(View.INVISIBLE);
            mKeyguardStatusBar.setAlpha(1f);
            mKeyguardStatusBarAnimateAlpha = 1f;
        }
    };

    private void animateKeyguardStatusBarOut() {
        ValueAnimator anim = ValueAnimator.ofFloat(mKeyguardStatusBar.getAlpha(), 0f);
        anim.addUpdateListener(mStatusBarAnimateAlphaListener);
        anim.setStartDelay(mStatusBar.isKeyguardFadingAway()
                ? mStatusBar.getKeyguardFadingAwayDelay()
                : 0);
        anim.setDuration(mStatusBar.isKeyguardFadingAway()
                ? mStatusBar.getKeyguardFadingAwayDuration() / 2
                : StackStateAnimator.ANIMATION_DURATION_STANDARD);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimateKeyguardStatusBarInvisibleEndRunnable.run();
            }
        });
        anim.start();
    }

    private final ValueAnimator.AnimatorUpdateListener mStatusBarAnimateAlphaListener =
            new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mKeyguardStatusBarAnimateAlpha = (float) animation.getAnimatedValue();
            updateHeaderKeyguardAlpha();
        }
    };

    private void animateKeyguardStatusBarIn(long duration) {
        mKeyguardStatusBar.setVisibility(View.VISIBLE);
        mKeyguardStatusBar.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.addUpdateListener(mStatusBarAnimateAlphaListener);
        anim.setDuration(duration);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.start();
    }

    private final Runnable mAnimateKeyguardBottomAreaInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardBottomArea.setVisibility(View.GONE);
        }
    };

    private void setKeyguardBottomAreaVisibility(int statusBarState,
            boolean goingToFullShade) {
        mKeyguardBottomArea.animate().cancel();
        if (goingToFullShade) {
            mKeyguardBottomArea.animate()
                    .alpha(0f)
                    .setStartDelay(mStatusBar.getKeyguardFadingAwayDelay())
                    .setDuration(mStatusBar.getKeyguardFadingAwayDuration() / 2)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .withEndAction(mAnimateKeyguardBottomAreaInvisibleEndRunnable)
                    .start();
        } else if (statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED) {
            mKeyguardBottomArea.setVisibility(View.VISIBLE);
            mKeyguardBottomArea.setAlpha(1f);
        } else {
            mKeyguardBottomArea.setVisibility(View.GONE);
            mKeyguardBottomArea.setAlpha(1f);
        }
    }

    private void setKeyguardStatusViewVisibility(int statusBarState, boolean keyguardFadingAway,
            boolean goingToFullShade) {
        mKeyguardStatusView.animate().cancel();
        mKeyguardStatusViewAnimating = false;
        if ((!keyguardFadingAway && mStatusBarState == StatusBarState.KEYGUARD
                && statusBarState != StatusBarState.KEYGUARD) || goingToFullShade) {
            mKeyguardStatusViewAnimating = true;
            mKeyguardStatusView.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .withEndAction(mAnimateKeyguardStatusViewGoneEndRunnable);
            if (keyguardFadingAway) {
                mKeyguardStatusView.animate()
                        .setStartDelay(mStatusBar.getKeyguardFadingAwayDelay())
                        .setDuration(mStatusBar.getKeyguardFadingAwayDuration()/2)
                        .start();
            }
        } else if (mStatusBarState == StatusBarState.SHADE_LOCKED
                && statusBarState == StatusBarState.KEYGUARD) {
            mKeyguardStatusView.setVisibility(View.VISIBLE);
            mKeyguardStatusViewAnimating = true;
            mKeyguardStatusView.setAlpha(0f);
            mKeyguardStatusView.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable);
        } else if (statusBarState == StatusBarState.KEYGUARD) {
            if (keyguardFadingAway) {
                mKeyguardStatusViewAnimating = true;
                mKeyguardStatusView.animate()
                        .alpha(0)
                        .translationYBy(-getHeight() * 0.05f)
                        .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                        .setDuration(125)
                        .setStartDelay(0)
                        .withEndAction(mAnimateKeyguardStatusViewInvisibleEndRunnable)
                        .start();
            } else {
                mKeyguardStatusView.setVisibility(View.VISIBLE);
                mKeyguardStatusView.setAlpha(1f);
            }
        } else {
            mKeyguardStatusView.setVisibility(View.GONE);
            mKeyguardStatusView.setAlpha(1f);
        }
    }

    private void updateQsState() {
        mNotificationStackScroller.setQsExpanded(mQsExpanded);
        mNotificationStackScroller.setScrollingEnabled(
                mStatusBarState != StatusBarState.KEYGUARD && (!mQsExpanded
                        || mQsExpansionFromOverscroll));
        updateEmptyShadeView();
        mQsNavbarScrim.setVisibility(mStatusBarState == StatusBarState.SHADE && mQsExpanded
                && !mStackScrollerOverscrolling && mQsScrimEnabled
                        ? View.VISIBLE
                        : View.INVISIBLE);
        if (mKeyguardUserSwitcher != null && mQsExpanded && !mStackScrollerOverscrolling) {
            mKeyguardUserSwitcher.hideIfNotSimple(true /* animate */);
        }
        if (mQs == null) return;
        mQs.setExpanded(mQsExpanded);
    }

    private void setQsExpansion(float height) {
        height = Math.min(Math.max(height, mQsMinExpansionHeight), mQsMaxExpansionHeight);
        mQsFullyExpanded = height == mQsMaxExpansionHeight && mQsMaxExpansionHeight != 0;
        if (height > mQsMinExpansionHeight && !mQsExpanded && !mStackScrollerOverscrolling) {
            setQsExpanded(true);
        } else if (height <= mQsMinExpansionHeight && mQsExpanded) {
            setQsExpanded(false);
        }
        mQsExpansionHeight = height;
        updateQsExpansion();
        requestScrollerTopPaddingUpdate(false /* animate */);
        if (mKeyguardShowing) {
            updateHeaderKeyguardAlpha();
        }
        if (mStatusBarState == StatusBarState.SHADE_LOCKED
                || mStatusBarState == StatusBarState.KEYGUARD) {
            updateKeyguardBottomAreaAlpha();
        }
        if (mStatusBarState == StatusBarState.SHADE && mQsExpanded
                && !mStackScrollerOverscrolling && mQsScrimEnabled) {
            mQsNavbarScrim.setAlpha(getQsExpansionFraction());
        }

        if (mAccessibilityManager.isEnabled()) {
            setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }

        if (mQsFullyExpanded && mFalsingManager.shouldEnforceBouncer()) {
            mStatusBar.executeRunnableDismissingKeyguard(null, null /* cancelAction */,
                    false /* dismissShade */, true /* afterKeyguardGone */, false /* deferred */);
        }
        if (DEBUG) {
            invalidate();
        }
    }

    protected void updateQsExpansion() {
        if (mQs == null) return;
        mQs.setQsExpansion(getQsExpansionFraction(), getHeaderTranslation());
    }

    private String determineAccessibilityPaneTitle() {
        if (mQs != null && mQs.isCustomizing()) {
            return getContext().getString(R.string.accessibility_desc_quick_settings_edit);
        } else if (mQsExpansionHeight != 0.0f && mQsFullyExpanded) {
            // Upon initialisation when we are not layouted yet we don't want to announce that we
            // are fully expanded, hence the != 0.0f check.
            return getContext().getString(R.string.accessibility_desc_quick_settings);
        } else if (mStatusBarState == StatusBarState.KEYGUARD) {
            return getContext().getString(R.string.accessibility_desc_lock_screen);
        } else {
            return getContext().getString(R.string.accessibility_desc_notification_shade);
        }
    }

    private float calculateQsTopPadding() {
        if (mKeyguardShowing
                && (mQsExpandImmediate || mIsExpanding && mQsExpandedWhenExpandingStarted)) {

            // Either QS pushes the notifications down when fully expanded, or QS is fully above the
            // notifications (mostly on tablets). maxNotificationPadding denotes the normal top
            // padding on Keyguard, maxQsPadding denotes the top padding from the quick settings
            // panel. We need to take the maximum and linearly interpolate with the panel expansion
            // for a nice motion.
            int maxNotificationPadding = mClockPositionResult.stackScrollerPadding;
            int maxQsPadding = mQsMaxExpansionHeight + mQsNotificationTopPadding;
            int max = mStatusBarState == StatusBarState.KEYGUARD
                    ? Math.max(maxNotificationPadding, maxQsPadding)
                    : maxQsPadding;
            return (int) interpolate(getExpandedFraction(),
                    mQsMinExpansionHeight, max);
        } else if (mQsSizeChangeAnimator != null) {
            return (int) mQsSizeChangeAnimator.getAnimatedValue();
        } else if (mKeyguardShowing) {

            // We can only do the smoother transition on Keyguard when we also are not collapsing
            // from a scrolled quick settings.
            return interpolate(getQsExpansionFraction(),
                    mNotificationStackScroller.getIntrinsicPadding(),
                    mQsMaxExpansionHeight + mQsNotificationTopPadding);
        } else {
            return mQsExpansionHeight + mQsNotificationTopPadding;
        }
    }

    protected void requestScrollerTopPaddingUpdate(boolean animate) {
        mNotificationStackScroller.updateTopPadding(calculateQsTopPadding(),
                animate, mKeyguardShowing
                        && (mQsExpandImmediate || mIsExpanding && mQsExpandedWhenExpandingStarted));
    }

    private void trackMovement(MotionEvent event) {
        if (mQsVelocityTracker != null) mQsVelocityTracker.addMovement(event);
        mLastTouchX = event.getX();
        mLastTouchY = event.getY();
    }

    private void initVelocityTracker() {
        if (mQsVelocityTracker != null) {
            mQsVelocityTracker.recycle();
        }
        mQsVelocityTracker = VelocityTracker.obtain();
    }

    private float getCurrentQSVelocity() {
        if (mQsVelocityTracker == null) {
            return 0;
        }
        mQsVelocityTracker.computeCurrentVelocity(1000);
        return mQsVelocityTracker.getYVelocity();
    }

    private void cancelQsAnimation() {
        if (mQsExpansionAnimator != null) {
            mQsExpansionAnimator.cancel();
        }
    }

    public void flingSettings(float vel, boolean expand) {
        flingSettings(vel, expand, null, false /* isClick */);
    }

    protected void flingSettings(float vel, boolean expand, final Runnable onFinishRunnable,
            boolean isClick) {
        float target = expand ? mQsMaxExpansionHeight : mQsMinExpansionHeight;
        if (target == mQsExpansionHeight) {
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
            return;
        }

        // If we move in the opposite direction, reset velocity and use a different duration.
        boolean oppositeDirection = false;
        if (vel > 0 && !expand || vel < 0 && expand) {
            vel = 0;
            oppositeDirection = true;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mQsExpansionHeight, target);
        if (isClick) {
            animator.setInterpolator(Interpolators.TOUCH_RESPONSE);
            animator.setDuration(368);
        } else {
            mFlingAnimationUtils.apply(animator, mQsExpansionHeight, target, vel);
        }
        if (oppositeDirection) {
            animator.setDuration(350);
        }
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setQsExpansion((Float) animation.getAnimatedValue());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mNotificationStackScroller.resetCheckSnoozeLeavebehind();
                mQsExpansionAnimator = null;
                if (onFinishRunnable != null) {
                    onFinishRunnable.run();
                }
            }
        });
        animator.start();
        mQsExpansionAnimator = animator;
        mQsAnimatorExpand = expand;
    }

    /**
     * @return Whether we should intercept a gesture to open Quick Settings.
     */
    private boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        if (!mQsExpansionEnabled || mCollapsedOnDown) {
            return false;
        }
        View header = mKeyguardShowing ? mKeyguardStatusBar : mQs.getHeader();
        final boolean onHeader = x >= mQsFrame.getX()
                && x <= mQsFrame.getX() + mQsFrame.getWidth()
                && y >= header.getTop() && y <= header.getBottom();
        if (mQsExpanded) {
            return onHeader || (yDiff < 0 && isInQsArea(x, y));
        } else {
            return onHeader;
        }
    }

    @Override
    protected boolean isScrolledToBottom() {
        if (!isInSettings()) {
            return mStatusBar.getBarState() == StatusBarState.KEYGUARD
                    || mNotificationStackScroller.isScrolledToBottom();
        } else {
            return true;
        }
    }

    @Override
    protected int getMaxPanelHeight() {
        int min = mStatusBarMinHeight;
        if (mStatusBar.getBarState() != StatusBarState.KEYGUARD
                && mNotificationStackScroller.getNotGoneChildCount() == 0) {
            int minHeight = (int) (mQsMinExpansionHeight + getOverExpansionAmount());
            min = Math.max(min, minHeight);
        }
        int maxHeight;
        if (mQsExpandImmediate || mQsExpanded || mIsExpanding && mQsExpandedWhenExpandingStarted) {
            maxHeight = calculatePanelHeightQsExpanded();
        } else {
            maxHeight = calculatePanelHeightShade();
        }
        maxHeight = Math.max(maxHeight, min);
        return maxHeight;
    }

    public boolean isInSettings() {
        return mQsExpanded;
    }

    public boolean isExpanding() {
        return mIsExpanding;
    }

    @Override
    protected void onHeightUpdated(float expandedHeight) {
        if (!mQsExpanded || mQsExpandImmediate || mIsExpanding && mQsExpandedWhenExpandingStarted) {
            // Updating the clock position will set the top padding which might
            // trigger a new panel height and re-position the clock.
            // This is a circular dependency and should be avoided, otherwise we'll have
            // a stack overflow.
            if (mStackScrollerMeasuringPass > 2) {
                if (DEBUG) Log.d(TAG, "Unstable notification panel height. Aborting.");
            } else {
                positionClockAndNotifications();
            }
        }
        if (mQsExpandImmediate || mQsExpanded && !mQsTracking && mQsExpansionAnimator == null
                && !mQsExpansionFromOverscroll) {
            float t;
            if (mKeyguardShowing) {

                // On Keyguard, interpolate the QS expansion linearly to the panel expansion
                t = expandedHeight / (getMaxPanelHeight());
            } else {

                // In Shade, interpolate linearly such that QS is closed whenever panel height is
                // minimum QS expansion + minStackHeight
                float panelHeightQsCollapsed = mNotificationStackScroller.getIntrinsicPadding()
                        + mNotificationStackScroller.getLayoutMinHeight();
                float panelHeightQsExpanded = calculatePanelHeightQsExpanded();
                t = (expandedHeight - panelHeightQsCollapsed)
                        / (panelHeightQsExpanded - panelHeightQsCollapsed);
            }
            setQsExpansion(mQsMinExpansionHeight
                    + t * (mQsMaxExpansionHeight - mQsMinExpansionHeight));
        }
        updateExpandedHeight(expandedHeight);
        updateHeader();
        updateUnlockIcon();
        updateNotificationTranslucency();
        updatePanelExpanded();
        mNotificationStackScroller.setShadeExpanded(!isFullyCollapsed());
        if (DEBUG) {
            invalidate();
        }
    }

    private void updatePanelExpanded() {
        boolean isExpanded = !isFullyCollapsed();
        if (mPanelExpanded != isExpanded) {
            mHeadsUpManager.setIsPanelExpanded(isExpanded);
            mStatusBar.setPanelExpanded(isExpanded);
            mPanelExpanded = isExpanded;
        }
    }

    private int calculatePanelHeightShade() {
        int emptyBottomMargin = mNotificationStackScroller.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScroller.getHeight() - emptyBottomMargin;
        maxHeight += mNotificationStackScroller.getTopPaddingOverflow();

        if (mStatusBarState == StatusBarState.KEYGUARD) {
            int minKeyguardPanelBottom = mClockPositionAlgorithm.getExpandedClockPosition()
                    + mKeyguardStatusView.getHeight()
                    + mNotificationStackScroller.getIntrinsicContentHeight();
            return Math.max(maxHeight, minKeyguardPanelBottom);
        } else {
            return maxHeight;
        }
    }

    private int calculatePanelHeightQsExpanded() {
        float notificationHeight = mNotificationStackScroller.getHeight()
                - mNotificationStackScroller.getEmptyBottomMargin()
                - mNotificationStackScroller.getTopPadding();

        // When only empty shade view is visible in QS collapsed state, simulate that we would have
        // it in expanded QS state as well so we don't run into troubles when fading the view in/out
        // and expanding/collapsing the whole panel from/to quick settings.
        if (mNotificationStackScroller.getNotGoneChildCount() == 0
                && mShowEmptyShadeView) {
            notificationHeight = mNotificationStackScroller.getEmptyShadeViewHeight();
        }
        int maxQsHeight = mQsMaxExpansionHeight;

        if (mKeyguardShowing) {
            maxQsHeight += mQsNotificationTopPadding;
        }

        // If an animation is changing the size of the QS panel, take the animated value.
        if (mQsSizeChangeAnimator != null) {
            maxQsHeight = (int) mQsSizeChangeAnimator.getAnimatedValue();
        }
        float totalHeight = Math.max(
                maxQsHeight, mStatusBarState == StatusBarState.KEYGUARD
                        ? mClockPositionResult.stackScrollerPadding : 0)
                + notificationHeight + mNotificationStackScroller.getTopPaddingOverflow();
        if (totalHeight > mNotificationStackScroller.getHeight()) {
            float fullyCollapsedHeight = maxQsHeight
                    + mNotificationStackScroller.getLayoutMinHeight();
            totalHeight = Math.max(fullyCollapsedHeight, mNotificationStackScroller.getHeight());
        }
        return (int) totalHeight;
    }

    private void updateNotificationTranslucency() {
        float alpha = 1f;
        if (mClosingWithAlphaFadeOut && !mExpandingFromHeadsUp &&
                !mHeadsUpManager.hasPinnedHeadsUp()) {
            alpha = getFadeoutAlpha();
        }
        mNotificationStackScroller.setAlpha(alpha);
    }

    private float getFadeoutAlpha() {
        float alpha = (getNotificationsTopY() + mNotificationStackScroller.getFirstItemMinHeight())
                / mQsMinExpansionHeight;
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    @Override
    protected float getOverExpansionAmount() {
        return mNotificationStackScroller.getCurrentOverScrollAmount(true /* top */);
    }

    @Override
    protected float getOverExpansionPixels() {
        return mNotificationStackScroller.getCurrentOverScrolledPixels(true /* top */);
    }

    private void updateUnlockIcon() {
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED) {
            boolean active = getMaxPanelHeight() - getExpandedHeight() > mUnlockMoveDistance;
            KeyguardAffordanceView lockIcon = mKeyguardBottomArea.getLockIcon();
            if (active && !mUnlockIconActive && mTracking) {
                lockIcon.setImageAlpha(1.0f, true, 150, Interpolators.FAST_OUT_LINEAR_IN, null);
                lockIcon.setImageScale(LOCK_ICON_ACTIVE_SCALE, true, 150,
                        Interpolators.FAST_OUT_LINEAR_IN);
            } else if (!active && mUnlockIconActive && mTracking) {
                lockIcon.setImageAlpha(lockIcon.getRestingAlpha(), true /* animate */,
                        150, Interpolators.FAST_OUT_LINEAR_IN, null);
                lockIcon.setImageScale(1.0f, true, 150,
                        Interpolators.FAST_OUT_LINEAR_IN);
            }
            mUnlockIconActive = active;
        }
    }

    /**
     * Hides the header when notifications are colliding with it.
     */
    private void updateHeader() {
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD) {
            updateHeaderKeyguardAlpha();
        }
        updateQsExpansion();
    }

    protected float getHeaderTranslation() {
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD) {
            return 0;
        }
        float translation = MathUtils.lerp(-mQsMinExpansionHeight, 0,
                Math.min(1.0f, mNotificationStackScroller.getAppearFraction(mExpandedHeight)))
                + mExpandOffset;
        return Math.min(0, translation);
    }

    /**
     * @return the alpha to be used to fade out the contents on Keyguard (status bar, bottom area)
     *         during swiping up
     */
    private float getKeyguardContentsAlpha() {
        float alpha;
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD) {

            // When on Keyguard, we hide the header as soon as the top card of the notification
            // stack scroller is close enough (collision distance) to the bottom of the header.
            alpha = getNotificationsTopY()
                    /
                    (mKeyguardStatusBar.getHeight() + mNotificationsHeaderCollideDistance);
        } else {

            // In SHADE_LOCKED, the top card is already really close to the header. Hide it as
            // soon as we start translating the stack.
            alpha = getNotificationsTopY() / mKeyguardStatusBar.getHeight();
        }
        alpha = MathUtils.constrain(alpha, 0, 1);
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    private void updateHeaderKeyguardAlpha() {
        float alphaQsExpansion = 1 - Math.min(1, getQsExpansionFraction() * 2);
        mKeyguardStatusBar.setAlpha(Math.min(getKeyguardContentsAlpha(), alphaQsExpansion)
                * mKeyguardStatusBarAnimateAlpha);
        mKeyguardStatusBar.setVisibility(mKeyguardStatusBar.getAlpha() != 0f
                && !mDozing ? VISIBLE : INVISIBLE);
    }

    private void updateKeyguardBottomAreaAlpha() {
        // There are two possible panel expansion behaviors:
        // • User dragging up to unlock: we want to fade out as quick as possible
        //   (ALPHA_EXPANSION_THRESHOLD) to avoid seeing the bouncer over the bottom area.
        // • User tapping on lock screen: bouncer won't be visible but panel expansion will
        //   change due to "unlock hint animation." In this case, fading out the bottom area
        //   would also hide the message that says "swipe to unlock," we don't want to do that.
        float expansionAlpha = MathUtils.map(isUnlockHintRunning()
                        ? 0 : KeyguardBouncer.ALPHA_EXPANSION_THRESHOLD, 1f,
                0f, 1f, getExpandedFraction());
        float alpha = Math.min(expansionAlpha, 1 - getQsExpansionFraction());
        mKeyguardBottomArea.setAlpha(alpha);
        mKeyguardBottomArea.setImportantForAccessibility(alpha == 0f
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        View ambientIndicationContainer = mStatusBar.getAmbientIndicationContainer();
        if (ambientIndicationContainer != null) {
            ambientIndicationContainer.setAlpha(alpha);
        }
    }

    private float getNotificationsTopY() {
        if (mNotificationStackScroller.getNotGoneChildCount() == 0) {
            return getExpandedHeight();
        }
        return mNotificationStackScroller.getNotificationsTopY();
    }

    @Override
    protected void onExpandingStarted() {
        super.onExpandingStarted();
        mNotificationStackScroller.onExpansionStarted();
        mIsExpanding = true;
        mQsExpandedWhenExpandingStarted = mQsFullyExpanded;
        if (mQsExpanded) {
            onQsExpansionStarted();
        }
        // Since there are QS tiles in the header now, we need to make sure we start listening
        // immediately so they can be up to date.
        if (mQs == null) return;
        mQs.setHeaderListening(true);
    }

    @Override
    protected void onExpandingFinished() {
        super.onExpandingFinished();
        mNotificationStackScroller.onExpansionStopped();
        mHeadsUpManager.onExpandingFinished();
        mIsExpanding = false;
        if (isFullyCollapsed()) {
            DejankUtils.postAfterTraversal(new Runnable() {
                @Override
                public void run() {
                    setListening(false);
                }
            });

            // Workaround b/22639032: Make sure we invalidate something because else RenderThread
            // thinks we are actually drawing a frame put in reality we don't, so RT doesn't go
            // ahead with rendering and we jank.
            postOnAnimation(new Runnable() {
                @Override
                public void run() {
                    getParent().invalidateChild(NotificationPanelView.this, mDummyDirtyRect);
                }
            });
        } else {
            setListening(true);
        }
        mQsExpandImmediate = false;
        mTwoFingerQsExpandPossible = false;
        mIsExpansionFromHeadsUp = false;
        notifyListenersTrackingHeadsUp(null);
        mExpandingFromHeadsUp = false;
        setPanelScrimMinFraction(0.0f);
    }

    private void notifyListenersTrackingHeadsUp(ExpandableNotificationRow pickedChild) {
        for (int i = 0; i < mTrackingHeadsUpListeners.size(); i++) {
            Consumer<ExpandableNotificationRow> listener
                    = mTrackingHeadsUpListeners.get(i);
            listener.accept(pickedChild);
        }
    }

    private void setListening(boolean listening) {
        mKeyguardStatusBar.setListening(listening);
        if (mQs == null) return;
        mQs.setListening(listening);
    }

    @Override
    public void expand(boolean animate) {
        super.expand(animate);
        setListening(true);
    }

    @Override
    protected void setOverExpansion(float overExpansion, boolean isPixels) {
        if (mConflictingQsExpansionGesture || mQsExpandImmediate) {
            return;
        }
        if (mStatusBar.getBarState() != StatusBarState.KEYGUARD) {
            mNotificationStackScroller.setOnHeightChangedListener(null);
            if (isPixels) {
                mNotificationStackScroller.setOverScrolledPixels(
                        overExpansion, true /* onTop */, false /* animate */);
            } else {
                mNotificationStackScroller.setOverScrollAmount(
                        overExpansion, true /* onTop */, false /* animate */);
            }
            mNotificationStackScroller.setOnHeightChangedListener(this);
        }
    }

    @Override
    protected void onTrackingStarted() {
        mFalsingManager.onTrackingStarted(mStatusBar.isKeyguardCurrentlySecure());
        super.onTrackingStarted();
        if (mQsFullyExpanded) {
            mQsExpandImmediate = true;
        }
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED) {
            mAffordanceHelper.animateHideLeftRightIcon();
        }
        mNotificationStackScroller.onPanelTrackingStarted();
    }

    @Override
    protected void onTrackingStopped(boolean expand) {
        mFalsingManager.onTrackingStopped();
        super.onTrackingStopped(expand);
        if (expand) {
            mNotificationStackScroller.setOverScrolledPixels(
                    0.0f, true /* onTop */, true /* animate */);
        }
        mNotificationStackScroller.onPanelTrackingStopped();
        if (expand && (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED)) {
            if (!mHintAnimationRunning) {
                mAffordanceHelper.reset(true);
            }
        }
        if (!expand && (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED)) {
            KeyguardAffordanceView lockIcon = mKeyguardBottomArea.getLockIcon();
            lockIcon.setImageAlpha(0.0f, true, 100, Interpolators.FAST_OUT_LINEAR_IN, null);
            lockIcon.setImageScale(2.0f, true, 100, Interpolators.FAST_OUT_LINEAR_IN);
        }
    }

    @Override
    public void onHeightChanged(ExpandableView view, boolean needsAnimation) {

        // Block update if we are in quick settings and just the top padding changed
        // (i.e. view == null).
        if (view == null && mQsExpanded) {
            return;
        }
        if (needsAnimation) {
            mAnimateNextPositionUpdate = true;
        }
        ExpandableView firstChildNotGone = mNotificationStackScroller.getFirstChildNotGone();
        ExpandableNotificationRow firstRow = firstChildNotGone instanceof ExpandableNotificationRow
                ? (ExpandableNotificationRow) firstChildNotGone
                : null;
        if (firstRow != null
                && (view == firstRow || (firstRow.getNotificationParent() == firstRow))) {
            requestScrollerTopPaddingUpdate(false /* animate */);
        }
        requestPanelHeightUpdate();
    }

    @Override
    public void onReset(ExpandableView view) {
    }

    public void onQsHeightChanged() {
        mQsMaxExpansionHeight = mQs != null ? mQs.getDesiredHeight() : 0;
        if (mQsExpanded && mQsFullyExpanded) {
            mQsExpansionHeight = mQsMaxExpansionHeight;
            requestScrollerTopPaddingUpdate(false /* animate */);
            requestPanelHeightUpdate();
        }
        if (mAccessibilityManager.isEnabled()) {
            setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mAffordanceHelper.onConfigurationChanged();
        if (newConfig.orientation != mLastOrientation) {
            resetVerticalPanelPosition();
        }
        mLastOrientation = newConfig.orientation;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mNavigationBarBottomHeight = insets.getStableInsetBottom();
        updateMaxHeadsUpTranslation();
        return insets;
    }

    private void updateMaxHeadsUpTranslation() {
        mNotificationStackScroller.setHeadsUpBoundaries(getHeight(), mNavigationBarBottomHeight);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        if (layoutDirection != mOldLayoutDirection) {
            mAffordanceHelper.onRtlPropertiesChanged();
            mOldLayoutDirection = layoutDirection;
        }
    }

    @Override
    public void onClick(View v) {
        onQsExpansionStarted();
        if (mQsExpanded) {
            flingSettings(0 /* vel */, false /* expand */, null, true /* isClick */);
        } else if (mQsExpansionEnabled) {
            mLockscreenGestureLogger.write(MetricsEvent.ACTION_SHADE_QS_TAP, 0, 0);
            flingSettings(0 /* vel */, true /* expand */, null, true /* isClick */);
        }
    }

    @Override
    public void onAnimationToSideStarted(boolean rightPage, float translation, float vel) {
        boolean start = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? rightPage : !rightPage;
        mIsLaunchTransitionRunning = true;
        mLaunchAnimationEndRunnable = null;
        float displayDensity = mStatusBar.getDisplayDensity();
        int lengthDp = Math.abs((int) (translation / displayDensity));
        int velocityDp = Math.abs((int) (vel / displayDensity));
        if (start) {
            mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_DIALER, lengthDp, velocityDp);

            mFalsingManager.onLeftAffordanceOn();
            if (mFalsingManager.shouldEnforceBouncer()) {
                mStatusBar.executeRunnableDismissingKeyguard(new Runnable() {
                    @Override
                    public void run() {
                        mKeyguardBottomArea.launchLeftAffordance();
                    }
                }, null, true /* dismissShade */, false /* afterKeyguardGone */,
                        true /* deferred */);
            }
            else {
                mKeyguardBottomArea.launchLeftAffordance();
            }
        } else {
            if (KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE.equals(
                    mLastCameraLaunchSource)) {
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_CAMERA, lengthDp, velocityDp);
            }
            mFalsingManager.onCameraOn();
            if (mFalsingManager.shouldEnforceBouncer()) {
                mStatusBar.executeRunnableDismissingKeyguard(new Runnable() {
                    @Override
                    public void run() {
                        mKeyguardBottomArea.launchCamera(mLastCameraLaunchSource);
                    }
                }, null, true /* dismissShade */, false /* afterKeyguardGone */,
                    true /* deferred */);
            }
            else {
                mKeyguardBottomArea.launchCamera(mLastCameraLaunchSource);
            }
        }
        mStatusBar.startLaunchTransitionTimeout();
        mBlockTouches = true;
    }

    @Override
    public void onAnimationToSideEnded() {
        mIsLaunchTransitionRunning = false;
        mIsLaunchTransitionFinished = true;
        if (mLaunchAnimationEndRunnable != null) {
            mLaunchAnimationEndRunnable.run();
            mLaunchAnimationEndRunnable = null;
        }
        mStatusBar.readyForKeyguardDone();
    }

    @Override
    protected void startUnlockHintAnimation() {
        if (mPowerManager.isPowerSaveMode()) {
            onUnlockHintStarted();
            onUnlockHintFinished();
            return;
        }
        super.startUnlockHintAnimation();
        startHighlightIconAnimation(getCenterIcon());
    }

    /**
     * Starts the highlight (making it fully opaque) animation on an icon.
     */
    private void startHighlightIconAnimation(final KeyguardAffordanceView icon) {
        icon.setImageAlpha(1.0f, true, KeyguardAffordanceHelper.HINT_PHASE1_DURATION,
                Interpolators.FAST_OUT_SLOW_IN, new Runnable() {
                    @Override
                    public void run() {
                        icon.setImageAlpha(icon.getRestingAlpha(),
                                true /* animate */, KeyguardAffordanceHelper.HINT_PHASE1_DURATION,
                                Interpolators.FAST_OUT_SLOW_IN, null);
                    }
                });
    }

    @Override
    public float getMaxTranslationDistance() {
        return (float) Math.hypot(getWidth(), getHeight());
    }

    @Override
    public void onSwipingStarted(boolean rightIcon) {
        mFalsingManager.onAffordanceSwipingStarted(rightIcon);
        boolean camera = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? !rightIcon
                : rightIcon;
        if (camera) {
            mKeyguardBottomArea.bindCameraPrewarmService();
        }
        requestDisallowInterceptTouchEvent(true);
        mOnlyAffordanceInThisMotion = true;
        mQsTracking = false;
    }

    @Override
    public void onSwipingAborted() {
        mFalsingManager.onAffordanceSwipingAborted();
        mKeyguardBottomArea.unbindCameraPrewarmService(false /* launched */);
    }

    @Override
    public void onIconClicked(boolean rightIcon) {
        if (mHintAnimationRunning) {
            return;
        }
        mHintAnimationRunning = true;
        mAffordanceHelper.startHintAnimation(rightIcon, new Runnable() {
            @Override
            public void run() {
                mHintAnimationRunning = false;
                mStatusBar.onHintFinished();
            }
        });
        rightIcon = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? !rightIcon : rightIcon;
        if (rightIcon) {
            mStatusBar.onCameraHintStarted();
        } else {
            if (mKeyguardBottomArea.isLeftVoiceAssist()) {
                mStatusBar.onVoiceAssistHintStarted();
            } else {
                mStatusBar.onPhoneHintStarted();
            }
        }
    }

    @Override
    protected void onUnlockHintFinished() {
        super.onUnlockHintFinished();
        mNotificationStackScroller.setUnlockHintRunning(false);
    }

    @Override
    protected void onUnlockHintStarted() {
        super.onUnlockHintStarted();
        mNotificationStackScroller.setUnlockHintRunning(true);
    }

    @Override
    public KeyguardAffordanceView getLeftIcon() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getRightView()
                : mKeyguardBottomArea.getLeftView();
    }

    @Override
    public KeyguardAffordanceView getCenterIcon() {
        return mKeyguardBottomArea.getLockIcon();
    }

    @Override
    public KeyguardAffordanceView getRightIcon() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getLeftView()
                : mKeyguardBottomArea.getRightView();
    }

    @Override
    public View getLeftPreview() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getRightPreview()
                : mKeyguardBottomArea.getLeftPreview();
    }

    @Override
    public View getRightPreview() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getLeftPreview()
                : mKeyguardBottomArea.getRightPreview();
    }

    @Override
    public float getAffordanceFalsingFactor() {
        return mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
    }

    @Override
    public boolean needsAntiFalsing() {
        return mStatusBarState == StatusBarState.KEYGUARD;
    }

    @Override
    protected float getPeekHeight() {
        if (mNotificationStackScroller.getNotGoneChildCount() > 0) {
            return mNotificationStackScroller.getPeekHeight();
        } else {
            return mQsMinExpansionHeight;
        }
    }

    @Override
    protected boolean shouldUseDismissingAnimation() {
        return mStatusBarState != StatusBarState.SHADE
                && (!mStatusBar.isKeyguardCurrentlySecure() || !isTracking());
    }

    @Override
    protected boolean fullyExpandedClearAllVisible() {
        return mNotificationStackScroller.isFooterViewNotGone()
                && mNotificationStackScroller.isScrolledToBottom() && !mQsExpandImmediate;
    }

    @Override
    protected boolean isClearAllVisible() {
        return mNotificationStackScroller.isFooterViewVisible();
    }

    @Override
    protected int getClearAllHeight() {
        return mNotificationStackScroller.getFooterViewHeight();
    }

    @Override
    protected boolean isTrackingBlocked() {
        return mConflictingQsExpansionGesture && mQsExpanded;
    }

    public boolean isQsExpanded() {
        return mQsExpanded;
    }

    public boolean isQsDetailShowing() {
        return mQs.isShowingDetail();
    }

    public void closeQsDetail() {
        mQs.closeDetail();
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    public boolean isLaunchTransitionFinished() {
        return mIsLaunchTransitionFinished;
    }

    public boolean isLaunchTransitionRunning() {
        return mIsLaunchTransitionRunning;
    }

    public void setLaunchTransitionEndRunnable(Runnable r) {
        mLaunchAnimationEndRunnable = r;
    }

    public void setEmptyDragAmount(float amount) {
        float factor = 0.8f;
        if (mNotificationStackScroller.getNotGoneChildCount() > 0) {
            factor = 0.4f;
        } else if (!mStatusBar.hasActiveNotifications()) {
            factor = 0.4f;
        }
        mEmptyDragAmount = amount * factor;
        positionClockAndNotifications();
    }

    private static float interpolate(float t, float start, float end) {
        return (1 - t) * start + t * end;
    }

    private void updateDozingVisibilities(boolean animate) {
        if (mDozing) {
            mKeyguardStatusBar.setVisibility(View.INVISIBLE);
            mKeyguardBottomArea.setDozing(mDozing, animate);
        } else {
            mKeyguardStatusBar.setVisibility(View.VISIBLE);
            mKeyguardBottomArea.setDozing(mDozing, animate);
            if (animate) {
                animateKeyguardStatusBarIn(DOZE_ANIMATION_DURATION);
            }
        }
    }

    @Override
    public boolean isDozing() {
        return mDozing;
    }

    public void showEmptyShadeView(boolean emptyShadeViewVisible) {
        mShowEmptyShadeView = emptyShadeViewVisible;
        updateEmptyShadeView();
    }

    private void updateEmptyShadeView() {

        // Hide "No notifications" in QS.
        mNotificationStackScroller.updateEmptyShadeView(mShowEmptyShadeView && !mQsExpanded);
    }

    public void setQsScrimEnabled(boolean qsScrimEnabled) {
        boolean changed = mQsScrimEnabled != qsScrimEnabled;
        mQsScrimEnabled = qsScrimEnabled;
        if (changed) {
            updateQsState();
        }
    }

    public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
    }

    public void onScreenTurningOn() {
        mKeyguardStatusView.refreshTime();
    }

    @Override
    public void onEmptySpaceClicked(float x, float y) {
        onEmptySpaceClick(x);
    }

    @Override
    protected boolean onMiddleClicked() {
        switch (mStatusBar.getBarState()) {
            case StatusBarState.KEYGUARD:
                if (!mDozingOnDown) {
                    mLockscreenGestureLogger.write(
                            MetricsEvent.ACTION_LS_HINT,
                            0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
                    startUnlockHintAnimation();
                }
                return true;
            case StatusBarState.SHADE_LOCKED:
                if (!mQsExpanded) {
                    mStatusBar.goToKeyguard();
                }
                return true;
            case StatusBarState.SHADE:

                // This gets called in the middle of the touch handling, where the state is still
                // that we are tracking the panel. Collapse the panel after this is done.
                post(mPostCollapseRunnable);
                return false;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (DEBUG) {
            Paint p = new Paint();
            p.setColor(Color.RED);
            p.setStrokeWidth(2);
            p.setStyle(Paint.Style.STROKE);
            canvas.drawLine(0, getMaxPanelHeight(), getWidth(), getMaxPanelHeight(), p);
            p.setColor(Color.BLUE);
            canvas.drawLine(0, getExpandedHeight(), getWidth(), getExpandedHeight(), p);
            p.setColor(Color.GREEN);
            canvas.drawLine(0, calculatePanelHeightQsExpanded(), getWidth(),
                    calculatePanelHeightQsExpanded(), p);
            p.setColor(Color.YELLOW);
            canvas.drawLine(0, calculatePanelHeightShade(), getWidth(),
                    calculatePanelHeightShade(), p);
            p.setColor(Color.MAGENTA);
            canvas.drawLine(0, calculateQsTopPadding(), getWidth(),
                    calculateQsTopPadding(), p);
            p.setColor(Color.CYAN);
            canvas.drawLine(0, mClockPositionResult.stackScrollerPadding, getWidth(),
                    mNotificationStackScroller.getTopPadding(), p);
            p.setColor(Color.GRAY);
            canvas.drawLine(0, mClockPositionResult.clockY, getWidth(),
                    mClockPositionResult.clockY, p);
        }
    }

    @Override
    public void onHeadsUpPinnedModeChanged(final boolean inPinnedMode) {
        mNotificationStackScroller.setInHeadsUpPinnedMode(inPinnedMode);
        if (inPinnedMode) {
            mHeadsUpExistenceChangedRunnable.run();
            updateNotificationTranslucency();
        } else {
            setHeadsUpAnimatingAway(true);
            mNotificationStackScroller.runAfterAnimationFinished(
                    mHeadsUpExistenceChangedRunnable);
        }
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        mNotificationStackScroller.setHeadsUpAnimatingAway(headsUpAnimatingAway);
    }

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
        mNotificationStackScroller.generateHeadsUpAnimation(headsUp, true);
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {

        // When we're unpinning the notification via active edge they remain heads-upped,
        // we need to make sure that an animation happens in this case, otherwise the notification
        // will stick to the top without any interaction.
        if (isFullyCollapsed() && headsUp.isHeadsUp()) {
            mNotificationStackScroller.generateHeadsUpAnimation(headsUp, false);
            headsUp.setHeadsUpIsVisible();
        }
    }

    @Override
    public void onHeadsUpStateChanged(NotificationData.Entry entry, boolean isHeadsUp) {
        mNotificationStackScroller.generateHeadsUpAnimation(entry.row, isHeadsUp);
    }

    @Override
    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        super.setHeadsUpManager(headsUpManager);
        mHeadsUpTouchHelper = new HeadsUpTouchHelper(headsUpManager, mNotificationStackScroller,
                this);
    }

    public void setTrackedHeadsUp(ExpandableNotificationRow pickedChild) {
        if (pickedChild != null) {
            notifyListenersTrackingHeadsUp(pickedChild);
            mExpandingFromHeadsUp = true;
        }
        // otherwise we update the state when the expansion is finished
    }

    @Override
    protected void onClosingFinished() {
        super.onClosingFinished();
        resetVerticalPanelPosition();
        setClosingWithAlphaFadeout(false);
    }

    private void setClosingWithAlphaFadeout(boolean closing) {
        mClosingWithAlphaFadeOut = closing;
        mNotificationStackScroller.forceNoOverlappingRendering(closing);
    }

    /**
     * Updates the vertical position of the panel so it is positioned closer to the touch
     * responsible for opening the panel.
     *
     * @param x the x-coordinate the touch event
     */
    protected void updateVerticalPanelPosition(float x) {
        if (mNotificationStackScroller.getWidth() * 1.75f > getWidth()) {
            resetVerticalPanelPosition();
            return;
        }
        float leftMost = mPositionMinSideMargin + mNotificationStackScroller.getWidth() / 2;
        float rightMost = getWidth() - mPositionMinSideMargin
                - mNotificationStackScroller.getWidth() / 2;
        if (Math.abs(x - getWidth() / 2) < mNotificationStackScroller.getWidth() / 4) {
            x = getWidth() / 2;
        }
        x = Math.min(rightMost, Math.max(leftMost, x));
        setVerticalPanelTranslation(x -
                (mNotificationStackScroller.getLeft() + mNotificationStackScroller.getWidth() / 2));
     }

    private void resetVerticalPanelPosition() {
        setVerticalPanelTranslation(0f);
    }

    protected void setVerticalPanelTranslation(float translation) {
        mNotificationStackScroller.setTranslationX(translation);
        mQsFrame.setTranslationX(translation);
        int size = mVerticalTranslationListener.size();
        for (int i = 0; i < size; i++) {
            mVerticalTranslationListener.get(i).run();
        }
    }

    protected void updateExpandedHeight(float expandedHeight) {
        if (mTracking) {
            mNotificationStackScroller.setExpandingVelocity(getCurrentExpandVelocity());
        }
        mNotificationStackScroller.setExpandedHeight(expandedHeight);
        updateKeyguardBottomAreaAlpha();
        updateStatusBarIcons();
    }

    /**
     * @return whether the notifications are displayed full width and don't have any margins on
     *         the side.
     */
    public boolean isFullWidth() {
        return mIsFullWidth;
    }

    private void updateStatusBarIcons() {
        boolean showIconsWhenExpanded = isFullWidth() && getExpandedHeight() < getOpeningHeight();
        if (showIconsWhenExpanded && mNoVisibleNotifications && isOnKeyguard()) {
            showIconsWhenExpanded = false;
        }
        if (showIconsWhenExpanded != mShowIconsWhenExpanded) {
            mShowIconsWhenExpanded = showIconsWhenExpanded;
            mStatusBar.recomputeDisableFlags(false);
        }
    }

    private boolean isOnKeyguard() {
        return mStatusBar.getBarState() == StatusBarState.KEYGUARD;
    }

    public void setPanelScrimMinFraction(float minFraction) {
        mBar.panelScrimMinFractionChanged(minFraction);
    }

    public void clearNotificationEffects() {
        mStatusBar.clearNotificationEffects();
    }

    @Override
    protected boolean isPanelVisibleBecauseOfHeadsUp() {
        return mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpAnimatingAway;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return !mDozing;
    }

    public void launchCamera(boolean animate, int source) {
        if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) {
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP;
        } else if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE) {
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_WIGGLE;
        } else if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER) {
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER;
        } else {

            // Default.
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;
        }

        // If we are launching it when we are occluded already we don't want it to animate,
        // nor setting these flags, since the occluded state doesn't change anymore, hence it's
        // never reset.
        if (!isFullyCollapsed()) {
            mLaunchingAffordance = true;
            setLaunchingAffordance(true);
        } else {
            animate = false;
        }
        mAffordanceHelper.launchAffordance(animate, getLayoutDirection() == LAYOUT_DIRECTION_RTL);
    }

    public void onAffordanceLaunchEnded() {
        mLaunchingAffordance = false;
        setLaunchingAffordance(false);
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        updateFullyVisibleState(false /* forceNotFullyVisible */);
    }

    /**
     * Must be called before starting a ViewPropertyAnimator alpha animation because those
     * do NOT call setAlpha and therefore don't properly update the fullyVisibleState.
     */
    public void notifyStartFading() {
        updateFullyVisibleState(true /* forceNotFullyVisible */);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        updateFullyVisibleState(false /* forceNotFullyVisible */);
    }

    private void updateFullyVisibleState(boolean forceNotFullyVisible) {
        mNotificationStackScroller.setParentNotFullyVisible(forceNotFullyVisible
                || getAlpha() != 1.0f
                || getVisibility() != VISIBLE);
    }

    /**
     * Set whether we are currently launching an affordance. This is currently only set when
     * launched via a camera gesture.
     */
    private void setLaunchingAffordance(boolean launchingAffordance) {
        getLeftIcon().setLaunchingAffordance(launchingAffordance);
        getRightIcon().setLaunchingAffordance(launchingAffordance);
        getCenterIcon().setLaunchingAffordance(launchingAffordance);
    }

    /**
     * Whether the camera application can be launched for the camera launch gesture.
     *
     * @param keyguardIsShowing whether keyguard is being shown
     */
    public boolean canCameraGestureBeLaunched(boolean keyguardIsShowing) {
        if (!mStatusBar.isCameraAllowedByAdmin()) {
            return false;
        }

        ResolveInfo resolveInfo = mKeyguardBottomArea.resolveCameraIntent();
        String packageToLaunch = (resolveInfo == null || resolveInfo.activityInfo == null)
                ? null : resolveInfo.activityInfo.packageName;
        return packageToLaunch != null &&
               (keyguardIsShowing || !isForegroundApp(packageToLaunch)) &&
               !mAffordanceHelper.isSwipingInProgress();
    }

    /**
     * Return true if the applications with the package name is running in foreground.
     *
     * @param pkgName application package name.
     */
    private boolean isForegroundApp(String pkgName) {
        ActivityManager am = getContext().getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
        return !tasks.isEmpty() && pkgName.equals(tasks.get(0).topActivity.getPackageName());
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        if (mLaunchingNotification) {
            return mHideIconsDuringNotificationLaunch;
        }
        if (mHeadsUpAppearanceController != null
                && mHeadsUpAppearanceController.shouldBeVisible()) {
            return false;
        }
        return !isFullWidth() || !mShowIconsWhenExpanded;
    }

    private final FragmentListener mFragmentListener = new FragmentListener() {
        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            mQs = (QS) fragment;
            mQs.setPanelView(NotificationPanelView.this);
            mQs.setExpandClickListener(NotificationPanelView.this);
            mQs.setHeaderClickable(mQsExpansionEnabled);
            mQs.setKeyguardShowing(mKeyguardShowing);
            mQs.setOverscrolling(mStackScrollerOverscrolling);

            // recompute internal state when qspanel height changes
            mQs.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height != oldHeight) {
                            onQsHeightChanged();
                        }
                    });
            mNotificationStackScroller.setQsContainer((ViewGroup) mQs.getView());
            updateQsExpansion();
        }

        @Override
        public void onFragmentViewDestroyed(String tag, Fragment fragment) {
            // Manual handling of fragment lifecycle is only required because this bridges
            // non-fragment and fragment code. Once we are using a fragment for the notification
            // panel, mQs will not need to be null cause it will be tied to the same lifecycle.
            if (fragment == mQs) {
                mQs = null;
            }
        }
    };

    @Override
    public void setTouchDisabled(boolean disabled) {
        super.setTouchDisabled(disabled);
        if (disabled && mAffordanceHelper.isSwipingInProgress() && !mIsLaunchTransitionRunning) {
            mAffordanceHelper.reset(false /* animate */);
        }
    }

    public void setDozing(boolean dozing, boolean animate) {
        if (dozing == mDozing) return;
        mDozing = dozing;

        if (mStatusBarState == StatusBarState.KEYGUARD
                || mStatusBarState == StatusBarState.SHADE_LOCKED) {
            updateDozingVisibilities(animate);
        }

        final float darkAmount = dozing ? 1 : 0;
        if (mDarkAnimator != null && mDarkAnimator.isRunning()) {
            if (animate && mDarkAmountTarget == darkAmount) {
                return;
            } else {
                mDarkAnimator.cancel();
            }
        }
        mDarkAmountTarget = darkAmount;
        if (animate) {
            mDarkAnimator = ObjectAnimator.ofFloat(this, SET_DARK_AMOUNT_PROPERTY, darkAmount);
            mDarkAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            mDarkAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP);
            mDarkAnimator.start();
        } else {
            setDarkAmount(darkAmount);
        }
    }

    private void setDarkAmount(float amount) {
        mDarkAmount = amount;
        mKeyguardStatusView.setDarkAmount(mDarkAmount);
        mKeyguardBottomArea.setDarkAmount(mDarkAmount);
        positionClockAndNotifications();
    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        mKeyguardStatusView.setPulsing(pulsing);
        positionClockAndNotifications();
        mNotificationStackScroller.setPulsing(pulsing, mKeyguardStatusView.getLocationOnScreen()[1]
                + mKeyguardStatusView.getClockBottom());
    }

    public void setAmbientIndicationBottomPadding(int ambientIndicationBottomPadding) {
        if (mAmbientIndicationBottomPadding != ambientIndicationBottomPadding) {
            mAmbientIndicationBottomPadding = ambientIndicationBottomPadding;
            mStatusBar.updateKeyguardMaxNotifications();
        }
    }

    public void dozeTimeTick() {
        mKeyguardStatusView.refreshTime();
        mKeyguardBottomArea.dozeTimeTick();
        if (mDarkAmount > 0) {
            positionClockAndNotifications();
        }
    }

    public void setStatusAccessibilityImportance(int mode) {
         mKeyguardStatusView.setImportantForAccessibility(mode);
    }

    /**
     * TODO: this should be removed.
     * It's not correct to pass this view forward because other classes will end up adding
     * children to it. Theme will be out of sync.
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mKeyguardBottomArea;
    }

    public void setUserSetupComplete(boolean userSetupComplete) {
        mUserSetupComplete = userSetupComplete;
        mKeyguardBottomArea.setUserSetupComplete(userSetupComplete);
    }

    public LockIcon getLockIcon() {
        return mKeyguardBottomArea.getLockIcon();
    }

    public void applyExpandAnimationParams(ExpandAnimationParameters params) {
        mExpandOffset = params != null ? params.getTopChange() : 0;
        updateQsExpansion();
        if (params != null) {
            boolean hideIcons = params.getProgress(
                    ActivityLaunchAnimator.ANIMATION_DELAY_ICON_FADE_IN, 100) == 0.0f;
            if (hideIcons != mHideIconsDuringNotificationLaunch) {
                mHideIconsDuringNotificationLaunch = hideIcons;
                if (!hideIcons) {
                    mStatusBar.recomputeDisableFlags(true /* animate */);
                }
            }
        }
    }

    public void addTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.add(listener);
    }

    public void removeTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.remove(listener);
    }

    public void addVerticalTranslationListener(Runnable verticalTranslationListener) {
        mVerticalTranslationListener.add(verticalTranslationListener);
    }

    public void removeVerticalTranslationListener(Runnable verticalTranslationListener) {
        mVerticalTranslationListener.remove(verticalTranslationListener);
    }

    public void setHeadsUpAppearanceController(
            HeadsUpAppearanceController headsUpAppearanceController) {
        mHeadsUpAppearanceController = headsUpAppearanceController;
    }

    /**
     * Starts the animation before we dismiss Keyguard, i.e. an disappearing animation on the
     * security view of the bouncer.
     */
    public void onBouncerPreHideAnimation() {
        setKeyguardStatusViewVisibility(mStatusBarState, true /* keyguardFadingAway */,
                false /* goingToFullShade */);
    }
}
