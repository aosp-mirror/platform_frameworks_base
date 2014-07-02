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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.MirrorView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.util.ArrayList;

public class NotificationPanelView extends PanelView implements
        ExpandableView.OnHeightChangedListener, ObservableScrollView.Listener,
        View.OnClickListener, NotificationStackScrollLayout.OnOverscrollTopChangedListener,
        KeyguardPageSwipeHelper.Callback {

    // Cap and total height of Roboto font. Needs to be adjusted when font for the big clock is
    // changed.
    private static final int CAP_HEIGHT = 1456;
    private static final int FONT_HEIGHT = 2163;

    private static final float HEADER_RUBBERBAND_FACTOR = 2.15f;
    private static final float LOCK_ICON_ACTIVE_SCALE = 1.2f;

    private KeyguardPageSwipeHelper mPageSwiper;
    private StatusBarHeaderView mHeader;
    private View mQsContainer;
    private QSPanel mQsPanel;
    private View mKeyguardStatusView;
    private ObservableScrollView mScrollView;
    private TextView mClockView;

    private MirrorView mSystemIconsCopy;

    private NotificationStackScrollLayout mNotificationStackScroller;
    private int mNotificationTopPadding;
    private boolean mAnimateNextTopPaddingChange;

    private int mTrackingPointer;
    private VelocityTracker mVelocityTracker;
    private boolean mQsTracking;

    /**
     * Whether we are currently handling a motion gesture in #onInterceptTouchEvent, but haven't
     * intercepted yet.
     */
    private boolean mIntercepting;
    private boolean mQsExpanded;
    private boolean mQsFullyExpanded;
    private boolean mKeyguardShowing;
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

    private Interpolator mFastOutSlowInInterpolator;
    private Interpolator mFastOutLinearInterpolator;
    private Interpolator mLinearOutSlowInInterpolator;
    private ObjectAnimator mClockAnimator;
    private int mClockAnimationTarget = -1;
    private int mTopPaddingAdjustment;
    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private KeyguardClockPositionAlgorithm.Result mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    private boolean mBlockTouches;
    private ArrayList<View> mSwipeTranslationViews = new ArrayList<>();
    private int mNotificationScrimWaitDistance;
    private boolean mOnNotificationsOnDown;

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
        mHeader.getBackgroundView().setOnClickListener(this);
        mHeader.setOverlayParent(this);
        mKeyguardStatusView = findViewById(R.id.keyguard_status_view);
        mQsContainer = findViewById(R.id.quick_settings_container);
        mQsPanel = (QSPanel) findViewById(R.id.quick_settings_panel);
        mClockView = (TextView) findViewById(R.id.clock_view);
        mScrollView = (ObservableScrollView) findViewById(R.id.scroll_view);
        mScrollView.setListener(this);
        mNotificationStackScroller = (NotificationStackScrollLayout)
                findViewById(R.id.notification_stack_scroller);
        mNotificationStackScroller.setOnHeightChangedListener(this);
        mNotificationStackScroller.setOverscrollTopChangedListener(this);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.linear_out_slow_in);
        mFastOutLinearInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_linear_in);
        mKeyguardBottomArea = (KeyguardBottomAreaView) findViewById(R.id.keyguard_bottom_area);
        mSwipeTranslationViews.add(mNotificationStackScroller);
        mSwipeTranslationViews.add(mKeyguardStatusView);
        mPageSwiper = new KeyguardPageSwipeHelper(this, getContext());
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Update Clock Pivot
        mKeyguardStatusView.setPivotX(getWidth() / 2);
        mKeyguardStatusView.setPivotY(
                (FONT_HEIGHT - CAP_HEIGHT) / 2048f * mClockView.getTextSize());

        // Calculate quick setting heights.
        mQsMinExpansionHeight = mHeader.getCollapsedHeight() + mQsPeekHeight;
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
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    private void positionClockAndNotifications() {
        boolean animate = mNotificationStackScroller.isAddOrRemoveAnimationPending();
        int stackScrollerPadding;
        if (mStatusBar.getBarState() != StatusBarState.KEYGUARD) {
            int bottom = mHeader.getCollapsedHeight();
            stackScrollerPadding = bottom + mQsPeekHeight
                    + mNotificationTopPadding;
            mTopPaddingAdjustment = 0;
        } else {
            mClockPositionAlgorithm.setup(
                    mStatusBar.getMaxKeyguardNotifications(),
                    getMaxPanelHeight(),
                    getExpandedHeight(),
                    mNotificationStackScroller.getNotGoneChildCount(),
                    getHeight(),
                    mKeyguardStatusView.getHeight());
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
        mKeyguardStatusView.setAlpha(alpha);
        mKeyguardStatusView.setScaleX(scale);
        mKeyguardStatusView.setScaleY(scale);
    }

    public void animateToFullShade() {
        mAnimateNextTopPaddingChange = true;
        mNotificationStackScroller.goToFullShade();
        requestLayout();
    }

    /**
     * @return Whether Quick Settings are currently expanded.
     */
    public boolean isQsExpanded() {
        return mQsExpanded;
    }

    public void setQsExpansionEnabled(boolean qsExpansionEnabled) {
        mQsExpansionEnabled = qsExpansionEnabled;
    }

    @Override
    public void resetViews() {
        mBlockTouches = false;
        mUnlockIconActive = false;
        mPageSwiper.reset();
        closeQs();
        mNotificationStackScroller.setOverScrollAmount(0f, true /* onTop */, false /* animate */,
                true /* cancelAnimators */);
    }

    public void closeQs() {
        cancelAnimation();
        setQsExpansion(mQsMinExpansionHeight);
    }

    public void openQs() {
        cancelAnimation();
        if (mQsExpansionEnabled) {
            setQsExpansion(mQsMaxExpansionHeight);
        }
    }

    @Override
    public void fling(float vel, boolean always) {
        GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
        if (gr != null) {
            gr.tag("fling " + ((vel > 0) ? "open" : "closed"), "notifications,v=" + vel);
        }
        super.fling(vel, always);
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
                mOnNotificationsOnDown = isOnNotifications(x, y);
                if (shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, 0)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
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
                } else if (mQsFullyExpanded && mOnNotificationsOnDown) {
                    flingSettings(0 /* vel */, false /* expand */);
                }
                mIntercepting = false;
                break;
        }
        return !mQsExpanded && super.onInterceptTouchEvent(event);
    }

    private boolean isOnNotifications(float x, float y) {
        return mNotificationStackScroller.getChildAtPosition(x, y) != null;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {

        // Block request when interacting with the scroll view so we can still intercept the
        // scrolling when QS is expanded.
        if (mScrollView.isDispatchingTouchEvent()) {
            return;
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    private void flingQsWithCurrentVelocity() {
        float vel = getCurrentVelocity();

        // TODO: Better logic whether we should expand or not.
        flingSettings(vel, vel > 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mBlockTouches) {
            return false;
        }
        // TODO: Handle doublefinger swipe to notifications again. Look at history for a reference
        // implementation.
        if ((!mIsExpanding || mHintAnimationRunning)
                && !mQsExpanded
                && mStatusBar.getBarState() != StatusBarState.SHADE) {
            mPageSwiper.onTouchEvent(event);
            if (mPageSwiper.isSwipingInProgress()) {
                return true;
            }
        }
        if (mQsTracking || mQsExpanded) {
            return onQsTouch(event);
        }

        super.onTouchEvent(event);
        return true;
    }

    @Override
    protected boolean hasConflictingGestures() {
        return mStatusBar.getBarState() != StatusBarState.SHADE;
    }

    private boolean onQsTouch(MotionEvent event) {
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
                flingQsWithCurrentVelocity();
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
    }

    @Override
    public void onOverscrolled(int amount) {
        if (mIntercepting) {
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
        setQsExpansion(mQsMinExpansionHeight + rounded);
        updateQsState();
    }

    @Override
    public void flingTopOverscroll(float velocity, boolean open) {
        setQsExpansion(mQsExpansionHeight);
        flingSettings(velocity, open, new Runnable() {
            @Override
            public void run() {
                mStackScrollerOverscrolling = false;
                mQsExpansionFromOverscroll = false;
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
        mScrollView.scrollTo(0, 0);
        setQsExpansion(height);
    }

    private void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
        }
    }

    public void setKeyguardShowing(boolean keyguardShowing) {
        if (!mKeyguardShowing && keyguardShowing) {
            setQsTranslation(mQsExpansionHeight);
            mHeader.setTranslationY(0f);
        }
        mKeyguardShowing = keyguardShowing;
        updateQsState();
    }

    private void updateQsState() {
        boolean expandVisually = mQsExpanded || mStackScrollerOverscrolling;
        mHeader.setExpanded(expandVisually, mStackScrollerOverscrolling);
        mNotificationStackScroller.setEnabled(!mQsExpanded || mQsExpansionFromOverscroll);
        mQsPanel.setVisibility(expandVisually ? View.VISIBLE : View.INVISIBLE);
        mQsContainer.setVisibility(
                mKeyguardShowing && !expandVisually ? View.INVISIBLE : View.VISIBLE);
        mScrollView.setTouchEnabled(mQsExpanded);
        mNotificationStackScroller.setTouchEnabled(!mQsExpanded || mQsExpansionFromOverscroll);
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
        mHeader.setExpansion(height - mQsPeekHeight);
        setQsTranslation(height);
        requestScrollerTopPaddingUpdate(false /* animate */);
        updateNotificationScrim(height);
        mStatusBar.userActivity();
    }

    private void updateNotificationScrim(float height) {
        int startDistance = mQsMinExpansionHeight + mNotificationScrimWaitDistance;
        float progress = (height - startDistance) / (mQsMaxExpansionHeight - startDistance);
        progress = Math.max(0.0f, Math.min(progress, 1.0f));
        mNotificationStackScroller.setScrimAlpha(progress);
    }

    private void setQsTranslation(float height) {
        mQsContainer.setY(height - mQsContainer.getHeight() + getHeaderTranslation());
    }

    private void requestScrollerTopPaddingUpdate(boolean animate) {
        mNotificationStackScroller.updateTopPadding(mQsExpansionHeight,
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
                mQsExpansionAnimator = null;
                if (onFinishRunnable != null) {
                    onFinishRunnable.run();
                }
            }
        });
        animator.start();
        mQsExpansionAnimator = animator;
    }

    /**
     * @return Whether we should intercept a gesture to open Quick Settings.
     */
    private boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        if (!mQsExpansionEnabled) {
            return false;
        }
        boolean onHeader = x >= mHeader.getLeft() && x <= mHeader.getRight()
                && y >= mHeader.getTop() && y <= mHeader.getBottom();
        if (mQsExpanded) {
            return onHeader || (mScrollView.isScrolledToBottom() && yDiff < 0);
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
        }
        return super.isScrolledToBottom();
    }

    @Override
    protected int getMaxPanelHeight() {
        if (mStatusBar.getBarState() != StatusBarState.KEYGUARD
                && mNotificationStackScroller.getNotGoneChildCount() == 0) {
            return (int) ((mQsMinExpansionHeight + getOverExpansionAmount())
                    * HEADER_RUBBERBAND_FACTOR);
        }
        // TODO: Figure out transition for collapsing when QS is open, adjust height here.
        int emptyBottomMargin = mNotificationStackScroller.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScroller.getHeight() - emptyBottomMargin
                - mTopPaddingAdjustment;
        maxHeight = Math.max(maxHeight, mStatusBarMinHeight);
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
        mNotificationStackScroller.setStackHeight(expandedHeight);
        updateHeader();
        updateUnlockIcon();
        updateNotificationTranslucency();
    }

    private void updateNotificationTranslucency() {
        float alpha = (mNotificationStackScroller.getNotificationsTopY()
                + mNotificationStackScroller.getItemHeight())
                / (mQsMinExpansionHeight
                        + mNotificationStackScroller.getItemHeight() / 2);
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);

        // TODO: Draw a rect with DST_OUT over the notifications to achieve the same effect -
        // this would be much more efficient.
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
            if (active && !mUnlockIconActive && mTracking) {
                mKeyguardBottomArea.getLockIcon().animate()
                        .alpha(1f)
                        .scaleY(LOCK_ICON_ACTIVE_SCALE)
                        .scaleX(LOCK_ICON_ACTIVE_SCALE)
                        .setInterpolator(mFastOutLinearInterpolator)
                        .setDuration(150);
            } else if (!active && mUnlockIconActive && mTracking) {
                mKeyguardBottomArea.getLockIcon().animate()
                        .alpha(KeyguardPageSwipeHelper.SWIPE_RESTING_ALPHA_AMOUNT)
                        .scaleY(1f)
                        .scaleX(1f)
                        .setInterpolator(mFastOutLinearInterpolator)
                        .setDuration(150);
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
        mHeader.setAlpha(1f);
        mHeader.setTranslationY(getHeaderTranslation());
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
        mHeader.setTranslationY(0f);
        float alpha;
        if (mStatusBar.getBarState() == StatusBarState.KEYGUARD) {

            // When on Keyguard, we hide the header as soon as the top card of the notification
            // stack scroller is close enough (collision distance) to the bottom of the header.
            alpha = mNotificationStackScroller.getNotificationsTopY()
                    /
                    (mQsMinExpansionHeight + mNotificationsHeaderCollideDistance);

        } else {

            // In SHADE_LOCKED, the top card is already really close to the header. Hide it as
            // soon as we start translating the stack.
            alpha = mNotificationStackScroller.getNotificationsTopY() / mQsMinExpansionHeight;
        }
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        mHeader.setAlpha(alpha);
        mKeyguardBottomArea.setAlpha(alpha);
        setQsTranslation(mQsExpansionHeight);
    }

    @Override
    protected void onExpandingStarted() {
        super.onExpandingStarted();
        mNotificationStackScroller.onExpansionStarted();
        mIsExpanding = true;
    }

    @Override
    protected void onExpandingFinished() {
        super.onExpandingFinished();
        mNotificationStackScroller.onExpansionStopped();
        mIsExpanding = false;
        if (mExpandedHeight == 0f) {
            mHeader.setListening(false);
            mQsPanel.setListening(false);
        } else {
            mHeader.setListening(true);
            mQsPanel.setListening(true);
        }
    }

    @Override
    public void instantExpand() {
        super.instantExpand();
        mHeader.setListening(true);
        mQsPanel.setListening(true);
    }

    @Override
    protected void setOverExpansion(float overExpansion, boolean isPixels) {
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
            mPageSwiper.animateHideLeftRightIcon();
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
            mPageSwiper.showAllIcons(true);
        }
        if (!expand && (mStatusBar.getBarState() == StatusBarState.KEYGUARD
                || mStatusBar.getBarState() == StatusBarState.SHADE_LOCKED)) {
            mKeyguardBottomArea.getLockIcon().animate()
                    .alpha(0f)
                    .scaleX(2f)
                    .scaleY(2f)
                    .setInterpolator(mFastOutLinearInterpolator)
                    .setDuration(100);
        }
    }

    @Override
    public void onHeightChanged(ExpandableView view) {
        requestPanelHeightUpdate();
    }

    @Override
    public void onScrollChanged() {
        if (mQsExpanded) {
            requestScrollerTopPaddingUpdate(false /* animate */);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPageSwiper.onConfigurationChanged();
    }

    @Override
    public void onClick(View v) {
        if (v == mHeader.getBackgroundView()) {
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
        if (start) {
            mKeyguardBottomArea.launchPhone();
        } else {
            mKeyguardBottomArea.launchCamera();
        }
        mBlockTouches = true;
    }

    @Override
    protected void onEdgeClicked(boolean right) {
        if ((right && getRightIcon().getVisibility() != View.VISIBLE)
                || (!right && getLeftIcon().getVisibility() != View.VISIBLE)) {
            return;
        }
        mHintAnimationRunning = true;
        mPageSwiper.startHintAnimation(right, new Runnable() {
            @Override
            public void run() {
                mHintAnimationRunning = false;
                mStatusBar.onHintFinished();
            }
        });
        startHighlightIconAnimation(right ? getRightIcon() : getLeftIcon());
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
    private void startHighlightIconAnimation(final View icon) {
        icon.animate()
                .alpha(1.0f)
                .setDuration(KeyguardPageSwipeHelper.HINT_PHASE1_DURATION)
                .setInterpolator(mFastOutSlowInInterpolator)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        icon.animate().alpha(KeyguardPageSwipeHelper.SWIPE_RESTING_ALPHA_AMOUNT)
                                .setDuration(KeyguardPageSwipeHelper.HINT_PHASE1_DURATION)
                                .setInterpolator(mFastOutSlowInInterpolator);
                    }
                });
    }

    @Override
    public float getPageWidth() {
        return getWidth();
    }

    @Override
    public ArrayList<View> getTranslationViews() {
        return mSwipeTranslationViews;
    }

    @Override
    public View getLeftIcon() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getCameraImageView()
                : mKeyguardBottomArea.getPhoneImageView();
    }

    @Override
    public View getCenterIcon() {
        return mKeyguardBottomArea.getLockIcon();
    }

    @Override
    public View getRightIcon() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? mKeyguardBottomArea.getPhoneImageView()
                : mKeyguardBottomArea.getCameraImageView();
    }

    @Override
    protected float getPeekHeight() {
        if (mNotificationStackScroller.getNotGoneChildCount() > 0) {
            return mNotificationStackScroller.getPeekHeight();
        } else {
            return mQsMinExpansionHeight * HEADER_RUBBERBAND_FACTOR;
        }
    }
}
