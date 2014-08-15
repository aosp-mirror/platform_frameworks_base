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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.MirrorView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackStateAnimator;

public class NotificationPanelView extends PanelView implements
        ExpandableView.OnHeightChangedListener, ObservableScrollView.Listener,
        View.OnClickListener, NotificationStackScrollLayout.OnOverscrollTopChangedListener,
        KeyguardAffordanceHelper.Callback {

    // Cap and total height of Roboto font. Needs to be adjusted when font for the big clock is
    // changed.
    private static final int CAP_HEIGHT = 1456;
    private static final int FONT_HEIGHT = 2163;

    private static final float HEADER_RUBBERBAND_FACTOR = 2.15f;
    private static final float LOCK_ICON_ACTIVE_SCALE = 1.2f;

    private KeyguardAffordanceHelper mAfforanceHelper;
    private StatusBarHeaderView mHeader;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private KeyguardStatusBarView mKeyguardStatusBar;
    private View mQsContainer;
    private QSPanel mQsPanel;
    private View mKeyguardStatusView;
    private ObservableScrollView mScrollView;
    private TextView mClockView;
    private View mReserveNotificationSpace;
    private MirrorView mSystemIconsCopy;
    private View mQsNavbarScrim;
    private View mNotificationContainerParent;
    private NotificationStackScrollLayout mNotificationStackScroller;
    private int mNotificationTopPadding;
    private boolean mAnimateNextTopPaddingChange;

    private int mTrackingPointer;
    private VelocityTracker mVelocityTracker;
    private boolean mQsTracking;

    /**
     * Handles launching the secure camera properly even when other applications may be using the
     * camera hardware.
     */
    private SecureCameraLaunchManager mSecureCameraLaunchManager;

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
    private boolean mQsExpanded;
    private boolean mQsExpandedWhenExpandingStarted;
    private boolean mQsFullyExpanded;
    private boolean mKeyguardShowing;
    private boolean mDozing;
    private boolean mKeyguardStatusBarTransparent;
    private int mStatusBarState;
    private float mInitialHeightOnTouch;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mLastTouchX;
    private float mLastTouchY;
    private float mQsExpansionHeight;
    private int mQsMinExpansionHeight;
    private int mQsMaxExpansionHeight;
    private int mQsPeekHeight;
    private boolean mStackScrollerOverscrolling;
    private boolean mQsExpansionFromOverscroll;
    private boolean mQsExpansionEnabled = true;
    private ValueAnimator mQsExpansionAnimator;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;
    private boolean mUnlockIconActive;
    private int mNotificationsHeaderCollideDistance;
    private int mUnlockMoveDistance;
    private float mEmptyDragAmount;

    private Interpolator mFastOutSlowInInterpolator;
    private Interpolator mFastOutLinearInterpolator;
    private ObjectAnimator mClockAnimator;
    private int mClockAnimationTarget = -1;
    private int mTopPaddingAdjustment;
    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private KeyguardClockPositionAlgorithm.Result mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    private boolean mBlockTouches;
    private int mNotificationScrimWaitDistance;
    private boolean mTwoFingerQsExpand;
    private boolean mTwoFingerQsExpandPossible;

    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private int mScrollYOverride = -1;
    private boolean mQsAnimatorExpand;
    private boolean mIsLaunchTransitionFinished;
    private boolean mIsLaunchTransitionRunning;
    private Runnable mLaunchAnimationEndRunnable;
    private boolean mOnlyAffordanceInThisMotion;
    private boolean mKeyguardStatusViewAnimating;
    private boolean mHeaderAnimatingIn;
    private ObjectAnimator mQsContainerAnimator;

    private boolean mShadeEmpty;

    private boolean mQsScrimEnabled = true;

    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSystemIconsCopy = new MirrorView(context);
    }

    public void setStatusBar(PhoneStatusBar bar) {
        mStatusBar = bar;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHeader = (StatusBarHeaderView) findViewById(R.id.header);
        mHeader.setOnClickListener(this);
        mKeyguardStatusBar = (KeyguardStatusBarView) findViewById(R.id.keyguard_header);
        mKeyguardStatusView = findViewById(R.id.keyguard_status_view);
        mQsContainer = findViewById(R.id.quick_settings_container);
        mQsPanel = (QSPanel) findViewById(R.id.quick_settings_panel);
        mClockView = (TextView) findViewById(R.id.clock_view);
        mScrollView = (ObservableScrollView) findViewById(R.id.scroll_view);
        mScrollView.setListener(this);
        mReserveNotificationSpace = findViewById(R.id.reserve_notification_space);
        mNotificationContainerParent = findViewById(R.id.notification_container_parent);
        mNotificationStackScroller = (NotificationStackScrollLayout)
                findViewById(R.id.notification_stack_scroller);
        mNotificationStackScroller.setOnHeightChangedListener(this);
        mNotificationStackScroller.setOverscrollTopChangedListener(this);
        mNotificationStackScroller.setScrollView(mScrollView);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
        mFastOutLinearInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_linear_in);
        mKeyguardBottomArea = (KeyguardBottomAreaView) findViewById(R.id.keyguard_bottom_area);
        mQsNavbarScrim = findViewById(R.id.qs_navbar_scrim);
        mAfforanceHelper = new KeyguardAffordanceHelper(this, getContext());
        mSecureCameraLaunchManager =
                new SecureCameraLaunchManager(getContext(), mKeyguardBottomArea);

        // recompute internal state when qspanel height changes
        mQsContainer.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight,
                    int oldBottom) {
                final int height = bottom - top;
                final int oldHeight = oldBottom - oldTop;
                if (height != oldHeight) {
                    onScrollChanged();
                }
            }
        });
    }

    @Override
    protected void loadDimens() {
        super.loadDimens();
        mNotificationTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notifications_top_padding);
        mFlingAnimationUtils = new FlingAnimationUtils(getContext(), 0.4f);
        mStatusBarMinHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mQsPeekHeight = getResources().getDimensionPixelSize(R.dimen.qs_peek_height);
        mNotificationsHeaderCollideDistance =
                getResources().getDimensionPixelSize(R.dimen.header_notifications_collide_distance);
        mUnlockMoveDistance = getResources().getDimensionPixelOffset(R.dimen.unlock_move_distance);
        mClockPositionAlgorithm.loadDimens(getResources());
        mNotificationScrimWaitDistance =
                getResources().getDimensionPixelSize(R.dimen.notification_scrim_wait_distance);
    }

    public void updateResources() {
        int panelWidth = getResources().getDimensionPixelSize(R.dimen.notification_panel_width);
        int panelGravity = getResources().getInteger(R.integer.notification_panel_layout_gravity);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mHeader.getLayoutParams();
        if (lp.width != panelWidth) {
            lp.width = panelWidth;
            lp.gravity = panelGravity;
            mHeader.setLayoutParams(lp);
            mHeader.post(mUpdateHeader);
        }

        lp = (FrameLayout.LayoutParams) mNotificationStackScroller.getLayoutParams();
        if (lp.width != panelWidth) {
            lp.width = panelWidth;
            lp.gravity = panelGravity;
            mNotificationStackScroller.setLayoutParams(lp);
        }

        lp = (FrameLayout.LayoutParams) mScrollView.getLayoutParams();
        if (lp.width != panelWidth) {
            lp.width = panelWidth;
            lp.gravity = panelGravity;
            mScrollView.setLayoutParams(lp);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Update Clock Pivot
        mKeyguardStatusView.setPivotX(getWidth() / 2);
        mKeyguardStatusView.setPivotY((FONT_HEIGHT - CAP_HEIGHT) / 2048f * mClockView.getTextSize());

        // Calculate quick setting heights.
        mQsMinExpansionHeight = mKeyguardShowing ? 0 : mHeader.getCollapsedHeight() + mQsPeekHeight;
        mQsMaxExpansionHeight = mHeader.getExpandedHeight() + mQsContainer.getHeight();
        if (mQsExpanded) {
            if (mQsFullyExpanded) {
                mQsExpansionHeight = mQsMaxExpansionHeight;
                requestScrollerTopPaddingUpdate(false /* animate */);
            }
        } else {
            if (!mStackScrollerOverscrolling) {
                setQsExpansion(mQsMinExpansionHeight);
            }
            positionClockAndNotifications();
            mNotificationStackScroller.setStackHeight(getExpandedHeight());
        }
        mNotificationStackScroller.updateIsSmallScreen(
                mHeader.getCollapsedHeight() + mQsPeekHeight);
    }

    @Override
    public void onAttachedToWindow() {
        mSecureCameraLaunchManager.create();
    }

    @Override
    public void onDetachedFromWindow() {
        mSecureCameraLaunchManager.destroy();
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    private void positionClockAndNotifications() {
        boolean animate = mNotificationStackScroller.isAddOrRemoveAnimationPending();
        int stackScrollerPadding;
        if (mStatusBarState != StatusBarState.KEYGUARD) {
            int bottom = mHeader.getCollapsedHeight();
            stackScrollerPadding = mStatusBarState == StatusBarState.SHADE
                    ? bottom + mQsPeekHeight + mNotificationTopPadding
                    : mKeyguardStatusBar.getHeight() + mNotificationTopPadding;
            mTopPaddingAdjustment = 0;
        } else {
            mClockPositionAlgorithm.setup(
                    mStatusBar.getMaxKeyguardNotifications(),
                    getMaxPanelHeight(),
                    getExpandedHeight(),
                    mNotificationStackScroller.getNotGoneChildCount(),
                    getHeight(),
                    mKeyguardStatusView.getHeight(),
                    mEmptyDragAmount);
            mClockPositionAlgorithm.run(mClockPositionResult);
            if (animate || mClockAnimator != null) {
                startClockAnimation(mClockPositionResult.clockY);
            } else {
                mKeyguardStatusView.setY(mClockPositionResult.clockY);
            }
            updateClock(mClockPositionResult.clockAlpha, mClockPositionResult.clockScale);
            stackScrollerPadding = mClockPositionResult.stackScrollerPadding;
            mTopPaddingAdjustment = mClockPositionResult.stackScrollerPaddingAdjustment;
        }
        mNotificationStackScroller.setIntrinsicPadding(stackScrollerPadding);
        requestScrollerTopPaddingUpdate(animate);
    }

    private void startClockAnimation(int y) {
        if (mClockAnimationTarget == y) {
            return;
        }
        mClockAnimationTarget = y;
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                if (mClockAnimator != null) {
                    mClockAnimator.removeAllListeners();
                    mClockAnimator.cancel();
                }
                mClockAnimator = ObjectAnimator
                        .ofFloat(mKeyguardStatusView, View.Y, mClockAnimationTarget);
                mClockAnimator.setInterpolator(mFastOutSlowInInterpolator);
                mClockAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                mClockAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mClockAnimator = null;
                        mClockAnimationTarget = -1;
                    }
                });
                mClockAnimator.start();
                return true;
            }
        });
    }

    private void updateClock(float alpha, float scale) {
        if (!mKeyguardStatusViewAnimating) {
            mKeyguardStatusView.setAlpha(alpha);
        }
        mKeyguardStatusView.setScaleX(scale);
        mKeyguardStatusView.setScaleY(scale);
    }

    public void animateToFullShade(long delay) {
        mAnimateNextTopPaddingChange = true;
        mNotificationStackScroller.goToFullShade(delay);
        requestLayout();
    }

    public void setQsExpansionEnabled(boolean qsExpansionEnabled) {
        mQsExpansionEnabled = qsExpansionEnabled;
    }

    @Override
    public void resetViews() {
        mIsLaunchTransitionFinished = false;
        mBlockTouches = false;
        mUnlockIconActive = false;
        mAfforanceHelper.reset(true);
        closeQs();
        mStatusBar.dismissPopups();
        mNotificationStackScroller.setOverScrollAmount(0f, true /* onTop */, false /* animate */,
                true /* cancelAnimators */);
    }

    public void closeQs() {
        cancelAnimation();
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
        cancelAnimation();
        if (mQsExpansionEnabled) {
            setQsExpansion(mQsMaxExpansionHeight);
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
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.getText()
                    .add(getContext().getString(R.string.accessibility_desc_notification_shade));
            return true;
        }

        return super.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mBlockTouches) {
            return false;
        }
        resetDownStates(event);
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
                    mNotificationStackScroller.removeLongPressCallback();
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
                    onQsExpansionStarted();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mQsTracking = true;
                    mIntercepting = false;
                    mNotificationStackScroller.removeLongPressCallback();
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mQsTracking) {
                    flingQsWithCurrentVelocity();
                    mQsTracking = false;
                }
                mIntercepting = false;
                break;
        }

        // Allow closing the whole panel when in SHADE state.
        if (mStatusBarState == StatusBarState.SHADE) {
            return super.onInterceptTouchEvent(event);
        } else {
            return !mQsExpanded && super.onInterceptTouchEvent(event);
        }
    }

    private void resetDownStates(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mOnlyAffordanceInThisMotion = false;
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {

        // Block request when interacting with the scroll view so we can still intercept the
        // scrolling when QS is expanded.
        if (mScrollView.isHandlingTouchEvent()) {
            return;
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    private void flingQsWithCurrentVelocity() {
        float vel = getCurrentVelocity();
        flingSettings(vel, flingExpandsQs(vel));
    }

    private boolean flingExpandsQs(float vel) {
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return getQsExpansionFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    private float getQsExpansionFraction() {
        return (mQsExpansionHeight - mQsMinExpansionHeight)
                / (getTempQsMaxExpansion() - mQsMinExpansionHeight);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mBlockTouches) {
            return false;
        }
        resetDownStates(event);
        if ((!mIsExpanding || mHintAnimationRunning)
                && !mQsExpanded
                && mStatusBar.getBarState() != StatusBarState.SHADE) {
            mAfforanceHelper.onTouchEvent(event);
        }
        if (mOnlyAffordanceInThisMotion) {
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getExpandedFraction() == 1f
                && mStatusBar.getBarState() != StatusBarState.KEYGUARD && !mQsExpanded) {

            // Down in the empty area while fully expanded - go to QS.
            mQsTracking = true;
            mConflictingQsExpansionGesture = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getX();
            mInitialTouchX = event.getY();
        }
        if (mExpandedHeight != 0) {
            handleQsDown(event);
        }
        if (!mTwoFingerQsExpand && mQsTracking) {
            onQsTouch(event);
            if (!mConflictingQsExpansionGesture) {
                return true;
            }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            mConflictingQsExpansionGesture = false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mExpandedHeight == 0) {
            mTwoFingerQsExpandPossible = true;
        }
        if (mTwoFingerQsExpandPossible && event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                && event.getPointerCount() == 2
                && event.getY(event.getActionIndex()) < mStatusBarMinHeight) {
            mTwoFingerQsExpand = true;
            requestPanelHeightUpdate();

            // Normally, we start listening when the panel is expanded, but here we need to start
            // earlier so the state is already up to date when dragging down.
            setListening(true);
        }
        super.onTouchEvent(event);
        return true;
    }

    private boolean isInQsArea(float x, float y) {
        return mStatusBarState != StatusBarState.SHADE
                || y <= mNotificationStackScroller.getBottomMostNotificationBottom()
                || y <= mQsContainer.getY() + mQsContainer.getHeight();
    }

    private void handleQsDown(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && shouldQuickSettingsIntercept(event.getX(), event.getY(), -1)) {
            mQsTracking = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getX();
            mInitialTouchX = event.getY();

            // If we interrupt an expansion gesture here, make sure to update the state correctly.
            if (mIsExpanding) {
                onExpandingFinished();
            }
        }
    }

    @Override
    protected boolean flingExpands(float vel, float vectorVel) {
        boolean expands = super.flingExpands(vel, vectorVel);

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

    private void onQsTouch(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);

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
                final float h = y - mInitialTouchY;
                setQsExpansion(h + mInitialHeightOnTouch);
                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mQsTracking = false;
                mTrackingPointer = -1;
                trackMovement(event);
                float fraction = getQsExpansionFraction();
                if ((fraction != 0f || y >= mInitialTouchY)
                        && (fraction != 1f || y <= mInitialTouchY)) {
                    flingQsWithCurrentVelocity();
                } else {
                    mScrollYOverride = -1;
                }
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
    }

    @Override
    public void onOverscrolled(float lastTouchX, float lastTouchY, int amount) {
        if (mIntercepting && shouldQuickSettingsIntercept(lastTouchX, lastTouchY,
                -1 /* yDiff: Not relevant here */)) {
            onQsExpansionStarted(amount);
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = mLastTouchY;
            mInitialTouchX = mLastTouchX;
            mQsTracking = true;
        }
    }

    @Override
    public void onOverscrollTopChanged(float amount, boolean isRubberbanded) {
        cancelAnimation();
        float rounded = amount >= 1f ? amount : 0f;
        mStackScrollerOverscrolling = rounded != 0f && isRubberbanded;
        mQsExpansionFromOverscroll = rounded != 0f;
        updateQsState();
        setQsExpansion(mQsMinExpansionHeight + rounded);
    }

    @Override
    public void flingTopOverscroll(float velocity, boolean open) {
        setQsExpansion(mQsExpansionHeight);
        flingSettings(velocity, open, new Runnable() {
            @Override
            public void run() {
                mStackScrollerOverscrolling = false;
                mQsExpansionFromOverscroll = false;
                updateQsState();
            }
        });
    }

    private void onQsExpansionStarted() {
        onQsExpansionStarted(0);
    }

    private void onQsExpansionStarted(int overscrollAmount) {
        cancelAnimation();

        // Reset scroll position and apply that position to the expanded height.
        float height = mQsExpansionHeight - mScrollView.getScrollY() - overscrollAmount;
        if (mScrollView.getScrollY() != 0) {
            mScrollYOverride = mScrollView.getScrollY();
        }
        mScrollView.scrollTo(0, 0);
        setQsExpansion(height);
    }

    private void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
            requestPanelHeightUpdate();
            mNotificationStackScroller.setInterceptDelegateEnabled(expanded);
            mStatusBar.setQsExpanded(expanded);
        }
    }

    public void setBarState(int statusBarState, boolean keyguardFadingAway,
            boolean goingToFullShade) {
        boolean keyguardShowing = statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED;
        if (!mKeyguardShowing && keyguardShowing) {
            setQsTranslation(mQsExpansionHeight);
            mHeader.setTranslationY(0f);
        }
        setKeyguardStatusViewVisibility(statusBarState, keyguardFadingAway, goingToFullShade);
        setKeyguardBottomAreaVisibility(statusBarState, goingToFullShade);
        if (goingToFullShade) {
            animateKeyguardStatusBarOut();
        } else {
            mKeyguardStatusBar.setAlpha(1f);
            mKeyguardStatusBar.setVisibility(keyguardShowing ? View.VISIBLE : View.INVISIBLE);
        }
        mStatusBarState = statusBarState;
        mKeyguardShowing = keyguardShowing;
        updateQsState();
        if (goingToFullShade) {
            animateHeaderSlidingIn();
        }
    }

    private final Runnable mAnimateKeyguardStatusViewInvisibleEndRunnable = new Runnable() {
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

    private final Animator.AnimatorListener mAnimateHeaderSlidingInListener
            = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mHeaderAnimatingIn = false;
            mQsContainerAnimator = null;
            mQsContainer.removeOnLayoutChangeListener(mQsContainerAnimatorUpdater);
        }
    };

    private final OnLayoutChangeListener mQsContainerAnimatorUpdater
            = new OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            int oldHeight = oldBottom - oldTop;
            int height = bottom - top;
            if (height != oldHeight && mQsContainerAnimator != null) {
                PropertyValuesHolder[] values = mQsContainerAnimator.getValues();
                float newEndValue = mHeader.getCollapsedHeight() + mQsPeekHeight - height - top;
                float newStartValue = -height - top;
                values[0].setFloatValues(newStartValue, newEndValue);
                mQsContainerAnimator.setCurrentPlayTime(mQsContainerAnimator.getCurrentPlayTime());
            }
        }
    };

    private final ViewTreeObserver.OnPreDrawListener mStartHeaderSlidingIn
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mHeader.setTranslationY(-mHeader.getCollapsedHeight() - mQsPeekHeight);
            mHeader.animate()
                    .translationY(0f)
                    .setStartDelay(mStatusBar.calculateGoingToFullShadeDelay())
                    .setDuration(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .start();
            mQsContainer.setY(-mQsContainer.getHeight());
            mQsContainerAnimator = ObjectAnimator.ofFloat(mQsContainer, View.TRANSLATION_Y,
                    mQsContainer.getTranslationY(),
                    mHeader.getCollapsedHeight() + mQsPeekHeight - mQsContainer.getHeight()
                            - mQsContainer.getTop());
            mQsContainerAnimator.setStartDelay(mStatusBar.calculateGoingToFullShadeDelay());
            mQsContainerAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE);
            mQsContainerAnimator.setInterpolator(mFastOutSlowInInterpolator);
            mQsContainerAnimator.addListener(mAnimateHeaderSlidingInListener);
            mQsContainerAnimator.start();
            mQsContainer.addOnLayoutChangeListener(mQsContainerAnimatorUpdater);
            return true;
        }
    };
    
    private void animateHeaderSlidingIn() {
        mHeaderAnimatingIn = true;
        getViewTreeObserver().addOnPreDrawListener(mStartHeaderSlidingIn);

    }

    private final Runnable mAnimateKeyguardStatusBarInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusBar.setVisibility(View.INVISIBLE);
        }
    };

    private void animateKeyguardStatusBarOut() {
        mKeyguardStatusBar.animate()
                .alpha(0f)
                .setStartDelay(mStatusBar.getKeyguardFadingAwayDelay())
                .setDuration(mStatusBar.getKeyguardFadingAwayDuration()/2)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                .withEndAction(mAnimateKeyguardStatusBarInvisibleEndRunnable)
                .start();
    }

    private final Runnable mAnimateKeyguardBottomAreaInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardBottomArea.setVisibility(View.GONE);
        }
    };

    private void setKeyguardBottomAreaVisibility(int statusBarState,
            boolean goingToFullShade) {
        if (goingToFullShade) {
            mKeyguardBottomArea.animate().cancel();
            mKeyguardBottomArea.animate()
                    .alpha(0f)
                    .setStartDelay(mStatusBar.getKeyguardFadingAwayDelay())
                    .setDuration(mStatusBar.getKeyguardFadingAwayDuration()/2)
                    .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                    .withEndAction(mAnimateKeyguardBottomAreaInvisibleEndRunnable)
                    .start();
        } else if (statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED) {
            mKeyguardBottomArea.animate().cancel();
            mKeyguardBottomArea.setVisibility(View.VISIBLE);
            mKeyguardBottomArea.setAlpha(1f);
        } else {
            mKeyguardBottomArea.animate().cancel();
            mKeyguardBottomArea.setVisibility(View.GONE);
            mKeyguardBottomArea.setAlpha(1f);
        }
    }

    private void setKeyguardStatusViewVisibility(int statusBarState, boolean keyguardFadingAway,
            boolean goingToFullShade) {
        if ((!keyguardFadingAway && mStatusBarState == StatusBarState.KEYGUARD
                && statusBarState != StatusBarState.KEYGUARD) || goingToFullShade) {
            mKeyguardStatusView.animate().cancel();
            mKeyguardStatusViewAnimating = true;
            mKeyguardStatusView.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(160)
                    .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                    .withEndAction(mAnimateKeyguardStatusViewInvisibleEndRunnable);
            if (keyguardFadingAway) {
                mKeyguardStatusView.animate()
                        .setStartDelay(mStatusBar.getKeyguardFadingAwayDelay())
                        .setDuration(mStatusBar.getKeyguardFadingAwayDuration()/2)
                        .start();
            }
        } else if (mStatusBarState == StatusBarState.SHADE_LOCKED
                && statusBarState == StatusBarState.KEYGUARD) {
            mKeyguardStatusView.animate().cancel();
            mKeyguardStatusView.setVisibility(View.VISIBLE);
            mKeyguardStatusViewAnimating = true;
            mKeyguardStatusView.setAlpha(0f);
            mKeyguardStatusView.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(320)
                    .setInterpolator(PhoneStatusBar.ALPHA_IN)
                    .withEndAction(mAnimateKeyguardStatusViewVisibleEndRunnable);
        } else if (statusBarState == StatusBarState.KEYGUARD) {
            mKeyguardStatusView.animate().cancel();
            mKeyguardStatusView.setVisibility(View.VISIBLE);
            mKeyguardStatusView.setAlpha(1f);
        } else {
            mKeyguardStatusView.animate().cancel();
            mKeyguardStatusView.setVisibility(View.GONE);
            mKeyguardStatusView.setAlpha(1f);
        }
    }

    private void updateQsState() {
        boolean expandVisually = mQsExpanded || mStackScrollerOverscrolling;
        mHeader.setVisibility((mQsExpanded || !mKeyguardShowing) ? View.VISIBLE : View.INVISIBLE);
        mHeader.setExpanded(mKeyguardShowing || (mQsExpanded && !mStackScrollerOverscrolling));
        mNotificationStackScroller.setScrollingEnabled(
                mStatusBarState != StatusBarState.KEYGUARD && (!mQsExpanded
                        || mQsExpansionFromOverscroll));
        mQsPanel.setVisibility(expandVisually ? View.VISIBLE : View.INVISIBLE);
        mQsContainer.setVisibility(
                mKeyguardShowing && !expandVisually ? View.INVISIBLE : View.VISIBLE);
        mScrollView.setTouchEnabled(mQsExpanded);
        updateEmptyShadeView();
        mQsNavbarScrim.setVisibility(mStatusBarState == StatusBarState.SHADE && mQsExpanded
                && !mStackScrollerOverscrolling && mQsScrimEnabled
                        ? View.VISIBLE
                        : View.INVISIBLE);
        if (mKeyguardUserSwitcher != null && mQsExpanded && !mStackScrollerOverscrolling) {
            mKeyguardUserSwitcher.hide(true /* animate */);
        }
    }

    private void setQsExpansion(float height) {
        height = Math.min(Math.max(height, mQsMinExpansionHeight), mQsMaxExpansionHeight);
        mQsFullyExpanded = height == mQsMaxExpansionHeight;
        if (height > mQsMinExpansionHeight && !mQsExpanded && !mStackScrollerOverscrolling) {
            setQsExpanded(true);
        } else if (height <= mQsMinExpansionHeight && mQsExpanded) {
            setQsExpanded(false);
        }
        mQsExpansionHeight = height;
        mHeader.setExpansion(getHeaderExpansionFraction());
        setQsTranslation(height);
        requestScrollerTopPaddingUpdate(false /* animate */);
        updateNotificationScrim(height);
        if (mKeyguardShowing) {
            float alpha = getQsExpansionFraction();
            alpha *= 2;
            alpha = Math.min(1, alpha);
            alpha = 1 - alpha;
            mKeyguardStatusBarTransparent = alpha == 0f;
            updateKeyguardStatusBarVisibility();
            if (!mKeyguardStatusBarTransparent) {
                mKeyguardStatusBar.setAlpha(alpha);
            }
        }
        if (mStatusBarState == StatusBarState.SHADE && mQsExpanded
                && !mStackScrollerOverscrolling && mQsScrimEnabled) {
            mQsNavbarScrim.setAlpha(getQsExpansionFraction());
        }
    }

    private void updateNotificationScrim(float height) {
        int startDistance = mQsMinExpansionHeight + mNotificationScrimWaitDistance;
        float progress = (height - startDistance) / (mQsMaxExpansionHeight - startDistance);
        progress = Math.max(0.0f, Math.min(progress, 1.0f));
        mNotificationStackScroller.setScrimAlpha(progress);
    }

    private float getHeaderExpansionFraction() {
        if (!mKeyguardShowing) {
            return getQsExpansionFraction();
        } else {
            return 1f;
        }
    }

    private void setQsTranslation(float height) {
        if (!mHeaderAnimatingIn) {
            mQsContainer.setY(height - mQsContainer.getHeight() + getHeaderTranslation());
        }
        if (mKeyguardShowing) {
            mHeader.setY(interpolate(getQsExpansionFraction(), -mHeader.getHeight(), 0));
        }
    }

    private float calculateQsTopPadding() {
        if (mKeyguardShowing) {
            return interpolate(getQsExpansionFraction(),
                    mNotificationStackScroller.getIntrinsicPadding() - mNotificationTopPadding,
                    mQsMaxExpansionHeight);
        } else {
            return mQsExpansionHeight;
        }
    }

    private void requestScrollerTopPaddingUpdate(boolean animate) {
        mNotificationStackScroller.updateTopPadding(calculateQsTopPadding(),
                mScrollView.getScrollY(),
                mAnimateNextTopPaddingChange || animate);
        mAnimateNextTopPaddingChange = false;
    }

    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
        mLastTouchX = event.getX();
        mLastTouchY = event.getY();
    }

    private void initVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = VelocityTracker.obtain();
    }

    private float getCurrentVelocity() {
        if (mVelocityTracker == null) {
            return 0;
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private void cancelAnimation() {
        if (mQsExpansionAnimator != null) {
            mQsExpansionAnimator.cancel();
        }
    }

    private void flingSettings(float vel, boolean expand) {
        flingSettings(vel, expand, null);
    }

    private void flingSettings(float vel, boolean expand, final Runnable onFinishRunnable) {
        float target = expand ? mQsMaxExpansionHeight : mQsMinExpansionHeight;
        if (target == mQsExpansionHeight) {
            mScrollYOverride = -1;
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mQsExpansionHeight, target);
        mFlingAnimationUtils.apply(animator, mQsExpansionHeight, target, vel);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setQsExpansion((Float) animation.getAnimatedValue());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mScrollYOverride = -1;
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
        if (!mQsExpansionEnabled) {
            return false;
        }
        View header = mKeyguardShowing ? mKeyguardStatusBar : mHeader;
        boolean onHeader = x >= header.getLeft() && x <= header.getRight()
                && y >= header.getTop() && y <= header.getBottom();
        if (mQsExpanded) {
            return onHeader || (mScrollView.isScrolledToBottom() && yDiff < 0) && isInQsArea(x, y);
        } else {
            return onHeader;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        int oldVisibility = getVisibility();
        super.setVisibility(visibility);
        if (visibility != oldVisibility) {
            reparentStatusIcons(visibility == VISIBLE);
        }
    }

    /**
     * When the notification panel gets expanded, we need to move the status icons in the header
     * card.
     */
    private void reparentStatusIcons(boolean toHeader) {
        if (mStatusBar == null) {
            return;
        }
        LinearLayout systemIcons = mStatusBar.getSystemIcons();
        ViewGroup parent = ((ViewGroup) systemIcons.getParent());
        if (toHeader) {
            int index = parent.indexOfChild(systemIcons);
            parent.removeView(systemIcons);
            mSystemIconsCopy.setMirroredView(
                    systemIcons, systemIcons.getWidth(), systemIcons.getHeight());
            parent.addView(mSystemIconsCopy, index);
            mHeader.attachSystemIcons(systemIcons);
        } else {
            ViewGroup newParent = mStatusBar.getSystemIconArea();
            int index = newParent.indexOfChild(mSystemIconsCopy);
            parent.removeView(systemIcons);
            mHeader.onSystemIconsDetached();
            mSystemIconsCopy.setMirroredView(null, 0, 0);
            newParent.removeView(mSystemIconsCopy);
            newParent.addView(systemIcons, index);
        }
    }

    @Override
    protected boolean isScrolledToBottom() {
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD) {
            return true;
        }
        if (!isInSettings()) {
            return mNotificationStackScroller.isScrolledToBottom();
        } else {
            return mScrollView.isScrolledToBottom();
        }
    }

    @Override
    protected int getMaxPanelHeight() {
        int min = mStatusBarMinHeight;
        if (mStatusBar.getBarState() != StatusBarState.KEYGUARD
                && mNotificationStackScroller.getNotGoneChildCount() == 0) {
            int minHeight = (int) ((mQsMinExpansionHeight + getOverExpansionAmount())
                    * HEADER_RUBBERBAND_FACTOR);
            min = Math.max(min, minHeight);
        }
        int maxHeight;
        if (mTwoFingerQsExpand || mQsExpanded || mIsExpanding && mQsExpandedWhenExpandingStarted) {
            maxHeight = Math.max(calculatePanelHeightQsExpanded(), calculatePanelHeightShade());
        } else {
            maxHeight = calculatePanelHeightShade();
        }
        maxHeight = Math.max(maxHeight, min);
        return maxHeight;
    }

    private boolean isInSettings() {
        return mQsExpanded;
    }

    @Override
    protected void onHeightUpdated(float expandedHeight) {
        if (!mQsExpanded) {
            positionClockAndNotifications();
        }
        if (mTwoFingerQsExpand || mQsExpanded && !mQsTracking && mQsExpansionAnimator == null
                && !mQsExpansionFromOverscroll) {
            float panelHeightQsCollapsed = mNotificationStackScroller.getIntrinsicPadding()
                    + mNotificationStackScroller.getMinStackHeight()
                    + mNotificationStackScroller.getNotificationTopPadding();
            float panelHeightQsExpanded = calculatePanelHeightQsExpanded();
            float t = (expandedHeight - panelHeightQsCollapsed)
                    / (panelHeightQsExpanded - panelHeightQsCollapsed);

            setQsExpansion(mQsMinExpansionHeight
                    + t * (getTempQsMaxExpansion() - mQsMinExpansionHeight));
        }
        mNotificationStackScroller.setStackHeight(expandedHeight);
        updateHeader();
        updateUnlockIcon();
        updateNotificationTranslucency();
    }

    /**
     * @return a temporary override of {@link #mQsMaxExpansionHeight}, which is needed when
     *         collapsing QS / the panel when QS was scrolled
     */
    private int getTempQsMaxExpansion() {
        int qsTempMaxExpansion = mQsMaxExpansionHeight;
        if (mScrollYOverride != -1) {
            qsTempMaxExpansion -= mScrollYOverride;
        }
        return qsTempMaxExpansion;
    }

    private int calculatePanelHeightShade() {
        int emptyBottomMargin = mNotificationStackScroller.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScroller.getHeight() - emptyBottomMargin
                - mTopPaddingAdjustment;
        maxHeight += mNotificationStackScroller.getTopPaddingOverflow();
        return maxHeight;
    }

    private int calculatePanelHeightQsExpanded() {
        float notificationHeight = mNotificationStackScroller.getHeight()
                - mNotificationStackScroller.getEmptyBottomMargin()
                - mNotificationStackScroller.getTopPadding();
        float totalHeight = mQsMaxExpansionHeight + notificationHeight
                + mNotificationStackScroller.getNotificationTopPadding();
        if (totalHeight > mNotificationStackScroller.getHeight()) {
            float fullyCollapsedHeight = mQsMaxExpansionHeight
                    + mNotificationStackScroller.getMinStackHeight()
                    + mNotificationStackScroller.getNotificationTopPadding()
                    - getScrollViewScrollY();
            totalHeight = Math.max(fullyCollapsedHeight, mNotificationStackScroller.getHeight());
        }
        return (int) totalHeight;
    }

    private int getScrollViewScrollY() {
        if (mScrollYOverride != -1) {
            return mScrollYOverride;
        } else {
            return mScrollView.getScrollY();
        }
    }
    private void updateNotificationTranslucency() {
        float alpha = (getNotificationsTopY() + mNotificationStackScroller.getItemHeight())
                / (mQsMinExpansionHeight + mNotificationStackScroller.getBottomStackPeekSize()
                        + mNotificationStackScroller.getCollapseSecondCardPadding());
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        if (alpha != 1f && mNotificationStackScroller.getLayerType() != LAYER_TYPE_HARDWARE) {
            mNotificationStackScroller.setLayerType(LAYER_TYPE_HARDWARE, null);
        } else if (alpha == 1f
                && mNotificationStackScroller.getLayerType() == LAYER_TYPE_HARDWARE) {
            mNotificationStackScroller.setLayerType(LAYER_TYPE_NONE, null);
        }
        mNotificationStackScroller.setAlpha(alpha);
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
                lockIcon.setImageAlpha(1.0f, true, 150, mFastOutLinearInterpolator, null);
                lockIcon.setImageScale(LOCK_ICON_ACTIVE_SCALE, true, 150,
                        mFastOutLinearInterpolator);
            } else if (!active && mUnlockIconActive && mTracking) {
                lockIcon.setImageAlpha(KeyguardAffordanceHelper.SWIPE_RESTING_ALPHA_AMOUNT, true,
                        150, mFastOutLinearInterpolator, null);
                lockIcon.setImageScale(1.0f, true, 150,
                        mFastOutLinearInterpolator);
            }
            mUnlockIconActive = active;
        }
    }

    /**
     * Hides the header when notifications are colliding with it.
     */
    private void updateHeader() {
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED) {
            updateHeaderKeyguard();
        } else {
            updateHeaderShade();
        }

    }

    private void updateHeaderShade() {
        if (!mHeaderAnimatingIn) {
            mHeader.setTranslationY(getHeaderTranslation());
        }
        setQsTranslation(mQsExpansionHeight);
    }

    private float getHeaderTranslation() {
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED) {
            return 0;
        }
        if (mNotificationStackScroller.getNotGoneChildCount() == 0) {
            if (mExpandedHeight / HEADER_RUBBERBAND_FACTOR >= mQsMinExpansionHeight) {
                return 0;
            } else {
                return mExpandedHeight / HEADER_RUBBERBAND_FACTOR - mQsMinExpansionHeight;
            }
        }
        return Math.min(0, mNotificationStackScroller.getTranslationY()) / HEADER_RUBBERBAND_FACTOR;
    }

    private void updateHeaderKeyguard() {
        float alpha;
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD) {

            // When on Keyguard, we hide the header as soon as the top card of the notification
            // stack scroller is close enough (collision distance) to the bottom of the header.
            alpha = getNotificationsTopY()
                    /
                    (mQsMinExpansionHeight + mNotificationsHeaderCollideDistance);

        } else {

            // In SHADE_LOCKED, the top card is already really close to the header. Hide it as
            // soon as we start translating the stack.
            alpha = getNotificationsTopY() / mQsMinExpansionHeight;
        }
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        if (!mQsExpanded) {
            mKeyguardStatusBar.setAlpha(alpha);
        }
        mKeyguardBottomArea.setAlpha(alpha);
        setQsTranslation(mQsExpansionHeight);
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
        mQsExpandedWhenExpandingStarted = mQsExpanded;
        if (mQsExpanded) {
            onQsExpansionStarted();
        }
    }

    @Override
    protected void onExpandingFinished() {
        super.onExpandingFinished();
        mNotificationStackScroller.onExpansionStopped();
        mIsExpanding = false;
        mScrollYOverride = -1;
        if (mExpandedHeight == 0f) {
            setListening(false);
        } else {
            setListening(true);
        }
        mTwoFingerQsExpand = false;
        mTwoFingerQsExpandPossible = false;
    }

    private void setListening(boolean listening) {
        mHeader.setListening(listening);
        mKeyguardStatusBar.setListening(listening);
        mQsPanel.setListening(listening);
    }

    @Override
    public void instantExpand() {
        super.instantExpand();
        setListening(true);
    }

    @Override
    protected void setOverExpansion(float overExpansion, boolean isPixels) {
        if (mConflictingQsExpansionGesture || mTwoFingerQsExpand) {
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
        super.onTrackingStarted();
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED) {
            mAfforanceHelper.animateHideLeftRightIcon();
        }
        if (mQsExpanded) {
            mTwoFingerQsExpand = true;
        }
    }

    @Override
    protected void onTrackingStopped(boolean expand) {
        super.onTrackingStopped(expand);
        if (expand) {
            mNotificationStackScroller.setOverScrolledPixels(
                    0.0f, true /* onTop */, true /* animate */);
        }
        if (expand && (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED)) {
            if (!mHintAnimationRunning) {
                mAfforanceHelper.reset(true);
            }
        }
        if (!expand && (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED)) {
            KeyguardAffordanceView lockIcon = mKeyguardBottomArea.getLockIcon();
            lockIcon.setImageAlpha(0.0f, true, 100, mFastOutLinearInterpolator, null);
            lockIcon.setImageScale(2.0f, true, 100, mFastOutLinearInterpolator);
        }
    }

    @Override
    public void onHeightChanged(ExpandableView view) {

        // Block update if we are in quick settings and just the top padding changed
        // (i.e. view == null).
        if (view == null && mQsExpanded) {
            return;
        }
        requestPanelHeightUpdate();
    }

    @Override
    public void onReset(ExpandableView view) {
    }

    @Override
    public void onScrollChanged() {
        if (mQsExpanded) {
            requestScrollerTopPaddingUpdate(false /* animate */);
            requestPanelHeightUpdate();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mAfforanceHelper.onConfigurationChanged();
    }

    @Override
    public void onClick(View v) {
        if (v == mHeader) {
            onQsExpansionStarted();
            if (mQsExpanded) {
                flingSettings(0 /* vel */, false /* expand */);
            } else if (mQsExpansionEnabled) {
                flingSettings(0 /* vel */, true /* expand */);
            }
        }
    }

    @Override
    public void onAnimationToSideStarted(boolean rightPage) {
        boolean start = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? rightPage : !rightPage;
        mIsLaunchTransitionRunning = true;
        mLaunchAnimationEndRunnable = null;
        if (start) {
            mKeyguardBottomArea.launchPhone();
        } else {
            mSecureCameraLaunchManager.startSecureCameraLaunch();
        }
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
    }

    @Override
    protected void onEdgeClicked(boolean right) {
        if ((right && getRightIcon().getVisibility() != View.VISIBLE)
                || (!right && getLeftIcon().getVisibility() != View.VISIBLE)) {
            return;
        }
        mHintAnimationRunning = true;
        mAfforanceHelper.startHintAnimation(right, new Runnable() {
            @Override
            public void run() {
                mHintAnimationRunning = false;
                mStatusBar.onHintFinished();
            }
        });
        boolean start = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? right : !right;
        if (start) {
            mStatusBar.onPhoneHintStarted();
        } else {
            mStatusBar.onCameraHintStarted();
        }
    }

    @Override
    protected void startUnlockHintAnimation() {
        super.startUnlockHintAnimation();
        startHighlightIconAnimation(getCenterIcon());
    }

    /**
     * Starts the highlight (making it fully opaque) animation on an icon.
     */
    private void startHighlightIconAnimation(final KeyguardAffordanceView icon) {
        icon.setImageAlpha(1.0f, true, KeyguardAffordanceHelper.HINT_PHASE1_DURATION,
                mFastOutSlowInInterpolator, new Runnable() {
                    @Override
                    public void run() {
                        icon.setImageAlpha(KeyguardAffordanceHelper.SWIPE_RESTING_ALPHA_AMOUNT,
                                true, KeyguardAffordanceHelper.HINT_PHASE1_DURATION,
                                mFastOutSlowInInterpolator, null);
                    }
                });
    }

    @Override
    public float getPageWidth() {
        return getWidth();
    }

    @Override
    public void onSwipingStarted() {
        mSecureCameraLaunchManager.onSwipingStarted();
        requestDisallowInterceptTouchEvent(true);
        mOnlyAffordanceInThisMotion = true;
    }

    @Override
    public KeyguardAffordanceView getLeftIcon() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getCameraView()
                : mKeyguardBottomArea.getPhoneView();
    }

    @Override
    public KeyguardAffordanceView getCenterIcon() {
        return mKeyguardBottomArea.getLockIcon();
    }

    @Override
    public KeyguardAffordanceView getRightIcon() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getPhoneView()
                : mKeyguardBottomArea.getCameraView();
    }

    @Override
    public View getLeftPreview() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getCameraPreview()
                : mKeyguardBottomArea.getPhonePreview();
    }

    @Override
    public View getRightPreview() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getPhonePreview()
                : mKeyguardBottomArea.getCameraPreview();
    }

    @Override
    protected float getPeekHeight() {
        if (mNotificationStackScroller.getNotGoneChildCount() > 0) {
            return mNotificationStackScroller.getPeekHeight();
        } else {
            return mQsMinExpansionHeight * HEADER_RUBBERBAND_FACTOR;
        }
    }

    @Override
    protected float getCannedFlingDurationFactor() {
        if (mQsExpanded) {
            return 0.7f;
        } else {
            return 0.6f;
        }
    }

    @Override
    protected boolean fullyExpandedClearAllVisible() {
        return mNotificationStackScroller.isDismissViewNotGone()
                && mNotificationStackScroller.isScrolledToBottom();
    }

    @Override
    protected boolean isClearAllVisible() {
        return mNotificationStackScroller.isDismissViewVisible();
    }

    @Override
    protected int getClearAllHeight() {
        return mNotificationStackScroller.getDismissViewHeight();
    }

    @Override
    protected boolean isTrackingBlocked() {
        return mConflictingQsExpansionGesture && mQsExpanded;
    }

    public void notifyVisibleChildrenChanged() {
        if (mNotificationStackScroller.getNotGoneChildCount() != 0) {
            mReserveNotificationSpace.setVisibility(View.VISIBLE);
        } else {
            mReserveNotificationSpace.setVisibility(View.GONE);
        }
    }

    public boolean isQsExpanded() {
        return mQsExpanded;
    }

    public boolean isQsDetailShowing() {
        return mQsPanel.isShowingDetail();
    }

    public void closeQsDetail() {
        mQsPanel.closeDetail();
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
        float factor = 1f;
        if (mNotificationStackScroller.getNotGoneChildCount() > 0) {
            factor = 0.6f;
        } else if (!mStatusBar.hasActiveNotifications()) {
            factor = 0.4f;
        }
        mEmptyDragAmount = amount * factor;
        positionClockAndNotifications();
    }

    private static float interpolate(float t, float start, float end) {
        return (1 - t) * start + t * end;
    }

    private void updateKeyguardStatusBarVisibility() {
        mKeyguardStatusBar.setVisibility(mKeyguardShowing && !mKeyguardStatusBarTransparent
                && !mDozing ? VISIBLE : INVISIBLE);
    }

    public void setDozing(boolean dozing) {
        if (dozing == mDozing) return;
        mDozing = dozing;
        if (mDozing) {
            setBackgroundColor(0xff000000);
        } else {
            setBackground(null);
        }
        updateKeyguardStatusBarVisibility();
    }

    public void setShadeEmpty(boolean shadeEmpty) {
        mShadeEmpty = shadeEmpty;
        updateEmptyShadeView();
    }

    private void updateEmptyShadeView() {

        // Hide "No notifications" in QS.
        mNotificationStackScroller.updateEmptyShadeView(mShadeEmpty && !mQsExpanded);
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

    private final Runnable mUpdateHeader = new Runnable() {
        @Override
        public void run() {
            mHeader.updateEverything();
        }
    };
}
