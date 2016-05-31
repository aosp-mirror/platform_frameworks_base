/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import static android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager.StackId;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.internal.policy.DockedDividerUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DockedTopTaskEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.activity.UndockingTaskEvent;
import com.android.systemui.recents.events.ui.RecentsDrawnEvent;
import com.android.systemui.recents.events.ui.RecentsGrowingEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.stackdivider.events.StartedDragingEvent;
import com.android.systemui.stackdivider.events.StoppedDragingEvent;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;

/**
 * Docked stack divider.
 */
public class DividerView extends FrameLayout implements OnTouchListener,
        OnComputeInternalInsetsListener {

    static final long TOUCH_ANIMATION_DURATION = 150;
    static final long TOUCH_RELEASE_ANIMATION_DURATION = 200;

    public static final int INVALID_RECENTS_GROW_TARGET = -1;

    private static final int LOG_VALUE_RESIZE_50_50 = 0;
    private static final int LOG_VALUE_RESIZE_DOCKED_SMALLER = 1;
    private static final int LOG_VALUE_RESIZE_DOCKED_LARGER = 2;

    private static final int LOG_VALUE_UNDOCK_MAX_DOCKED = 0;
    private static final int LOG_VALUE_UNDOCK_MAX_OTHER = 1;

    private static final int TASK_POSITION_SAME = Integer.MAX_VALUE;
    private static final boolean SWAPPING_ENABLED = false;

    /**
     * How much the background gets scaled when we are in the minimized dock state.
     */
    private static final float MINIMIZE_DOCK_SCALE = 0f;
    private static final float ADJUSTED_FOR_IME_SCALE = 0.5f;

    private static final PathInterpolator SLOWDOWN_INTERPOLATOR =
            new PathInterpolator(0.5f, 1f, 0.5f, 1f);
    private static final PathInterpolator DIM_INTERPOLATOR =
            new PathInterpolator(.23f, .87f, .52f, -0.11f);
    private static final Interpolator IME_ADJUST_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0.1f, 1f);

    private DividerHandleView mHandle;
    private View mBackground;
    private MinimizedDockShadow mMinimizedShadow;
    private int mStartX;
    private int mStartY;
    private int mStartPosition;
    private int mDockSide;
    private final int[] mTempInt2 = new int[2];
    private boolean mMoving;
    private int mTouchSlop;
    private boolean mBackgroundLifted;

    private int mDividerInsets;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mDividerWindowWidth;
    private int mDividerSize;
    private int mTouchElevation;
    private int mLongPressEntraceAnimDuration;

    private final Rect mDockedRect = new Rect();
    private final Rect mDockedTaskRect = new Rect();
    private final Rect mOtherTaskRect = new Rect();
    private final Rect mOtherRect = new Rect();
    private final Rect mDockedInsetRect = new Rect();
    private final Rect mOtherInsetRect = new Rect();
    private final Rect mLastResizeRect = new Rect();
    private final Rect mDisplayRect = new Rect();
    private final WindowManagerProxy mWindowManagerProxy = WindowManagerProxy.getInstance();
    private DividerWindowManager mWindowManager;
    private VelocityTracker mVelocityTracker;
    private FlingAnimationUtils mFlingAnimationUtils;
    private DividerSnapAlgorithm mSnapAlgorithm;
    private final Rect mStableInsets = new Rect();

    private boolean mGrowRecents;
    private ValueAnimator mCurrentAnimator;
    private boolean mEntranceAnimationRunning;
    private boolean mExitAnimationRunning;
    private int mExitStartPosition;
    private GestureDetector mGestureDetector;
    private boolean mDockedStackMinimized;
    private boolean mAdjustedForIme;
    private DividerState mState;
    private final Handler mHandler = new Handler();

    private final AccessibilityDelegate mHandleDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            if (isHorizontalDivision()) {
                info.addAction(new AccessibilityAction(R.id.action_move_tl_full,
                        mContext.getString(R.string.accessibility_action_divider_top_full)));
                if (mSnapAlgorithm.isFirstSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_70,
                            mContext.getString(R.string.accessibility_action_divider_top_70)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_tl_50,
                        mContext.getString(R.string.accessibility_action_divider_top_50)));
                if (mSnapAlgorithm.isLastSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_30,
                            mContext.getString(R.string.accessibility_action_divider_top_30)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_rb_full,
                        mContext.getString(R.string.accessibility_action_divider_bottom_full)));
            } else {
                info.addAction(new AccessibilityAction(R.id.action_move_tl_full,
                        mContext.getString(R.string.accessibility_action_divider_left_full)));
                if (mSnapAlgorithm.isFirstSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_70,
                            mContext.getString(R.string.accessibility_action_divider_left_70)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_tl_50,
                        mContext.getString(R.string.accessibility_action_divider_left_50)));
                if (mSnapAlgorithm.isLastSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_30,
                            mContext.getString(R.string.accessibility_action_divider_left_30)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_rb_full,
                        mContext.getString(R.string.accessibility_action_divider_right_full)));
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            int currentPosition = getCurrentPosition();
            SnapTarget nextTarget = null;
            switch (action) {
                case R.id.action_move_tl_full:
                    nextTarget = mSnapAlgorithm.getDismissEndTarget();
                    break;
                case R.id.action_move_tl_70:
                    nextTarget = mSnapAlgorithm.getLastSplitTarget();
                    break;
                case R.id.action_move_tl_50:
                    nextTarget = mSnapAlgorithm.getMiddleTarget();
                    break;
                case R.id.action_move_tl_30:
                    nextTarget = mSnapAlgorithm.getFirstSplitTarget();
                    break;
                case R.id.action_move_rb_full:
                    nextTarget = mSnapAlgorithm.getDismissStartTarget();
                    break;
            }
            if (nextTarget != null) {
                startDragging(true /* animate */, false /* touching */);
                stopDragging(currentPosition, nextTarget, 250, Interpolators.FAST_OUT_SLOW_IN);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    private final Runnable mResetBackgroundRunnable = new Runnable() {
        @Override
        public void run() {
            resetBackground();
        }
    };

    public DividerView(Context context) {
        super(context);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandle = (DividerHandleView) findViewById(R.id.docked_divider_handle);
        mBackground = findViewById(R.id.docked_divider_background);
        mMinimizedShadow = (MinimizedDockShadow) findViewById(R.id.minimized_dock_shadow);
        mHandle.setOnTouchListener(this);
        mDividerWindowWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        mDividerSize = mDividerWindowWidth - 2 * mDividerInsets;
        mTouchElevation = getResources().getDimensionPixelSize(
                R.dimen.docked_stack_divider_lift_elevation);
        mLongPressEntraceAnimDuration = getResources().getInteger(
                R.integer.long_press_dock_anim_duration);
        mGrowRecents = getResources().getBoolean(R.bool.recents_grow_in_multiwindow);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        mFlingAnimationUtils = new FlingAnimationUtils(getContext(), 0.3f);
        updateDisplayInfo();
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mHandle.setPointerIcon(PointerIcon.getSystemIcon(getContext(),
                landscape ? TYPE_HORIZONTAL_DOUBLE_ARROW : TYPE_VERTICAL_DOUBLE_ARROW));
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        mHandle.setAccessibilityDelegate(mHandleDelegate);
        mGestureDetector = new GestureDetector(mContext, new SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (SWAPPING_ENABLED) {
                    updateDockSide();
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    if (mDockSide != WindowManager.DOCKED_INVALID
                            && !ssp.isRecentsActivityVisible()) {
                        mWindowManagerProxy.swapTasks();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (mStableInsets.left != insets.getStableInsetLeft()
                || mStableInsets.top != insets.getStableInsetTop()
                || mStableInsets.right != insets.getStableInsetRight()
                || mStableInsets.bottom != insets.getStableInsetBottom()) {
            mStableInsets.set(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                    insets.getStableInsetRight(), insets.getStableInsetBottom());
            if (mSnapAlgorithm != null) {
                mSnapAlgorithm = null;
                initializeSnapAlgorithm();
            }
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int minimizeLeft = 0;
        int minimizeTop = 0;
        if (mDockSide == WindowManager.DOCKED_TOP) {
            minimizeTop = mBackground.getTop();
        } else if (mDockSide == WindowManager.DOCKED_LEFT) {
            minimizeLeft = mBackground.getLeft();
        } else if (mDockSide == WindowManager.DOCKED_RIGHT) {
            minimizeLeft = mBackground.getRight() - mMinimizedShadow.getWidth();
        }
        mMinimizedShadow.layout(minimizeLeft, minimizeTop,
                minimizeLeft + mMinimizedShadow.getMeasuredWidth(),
                minimizeTop + mMinimizedShadow.getMeasuredHeight());
        if (changed) {
            mWindowManagerProxy.setTouchRegion(new Rect(mHandle.getLeft(), mHandle.getTop(),
                    mHandle.getRight(), mHandle.getBottom()));
        }
    }

    public void injectDependencies(DividerWindowManager windowManager, DividerState dividerState) {
        mWindowManager = windowManager;
        mState = dividerState;
    }

    public WindowManagerProxy getWindowManagerProxy() {
        return mWindowManagerProxy;
    }

    public boolean startDragging(boolean animate, boolean touching) {
        cancelFlingAnimation();
        if (touching) {
            mHandle.setTouching(true, animate);
        }
        mDockSide = mWindowManagerProxy.getDockSide();
        initializeSnapAlgorithm();
        mWindowManagerProxy.setResizing(true);
        if (touching) {
            mWindowManager.setSlippery(false);
            liftBackground();
        }
        EventBus.getDefault().send(new StartedDragingEvent());
        return mDockSide != WindowManager.DOCKED_INVALID;
    }

    public void stopDragging(int position, float velocity, boolean avoidDismissStart,
            boolean logMetrics) {
        mHandle.setTouching(false, true /* animate */);
        fling(position, velocity, avoidDismissStart, logMetrics);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    public void stopDragging(int position, SnapTarget target, long duration,
            Interpolator interpolator) {
        stopDragging(position, target, duration, 0 /* startDelay*/, 0 /* endDelay */, interpolator);
    }

    public void stopDragging(int position, SnapTarget target, long duration,
            Interpolator interpolator, long endDelay) {
        stopDragging(position, target, duration, 0 /* startDelay*/, endDelay, interpolator);
    }

    public void stopDragging(int position, SnapTarget target, long duration, long startDelay,
            long endDelay, Interpolator interpolator) {
        mHandle.setTouching(false, true /* animate */);
        flingTo(position, target, duration, startDelay, endDelay, interpolator);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    private void stopDragging() {
        mHandle.setTouching(false, true /* animate */);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    private void updateDockSide() {
        mDockSide = mWindowManagerProxy.getDockSide();
        mMinimizedShadow.setDockSide(mDockSide);
    }

    private void initializeSnapAlgorithm() {
        if (mSnapAlgorithm == null) {
            mSnapAlgorithm = new DividerSnapAlgorithm(getContext().getResources(), mDisplayWidth,
                    mDisplayHeight, mDividerSize, isHorizontalDivision(), mStableInsets);
        }
    }

    public DividerSnapAlgorithm getSnapAlgorithm() {
        initializeSnapAlgorithm();
        return mSnapAlgorithm;
    }

    public int getCurrentPosition() {
        getLocationOnScreen(mTempInt2);
        if (isHorizontalDivision()) {
            return mTempInt2[1] + mDividerInsets;
        } else {
            return mTempInt2[0] + mDividerInsets;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        convertToScreenCoordinates(event);
        mGestureDetector.onTouchEvent(event);
        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(event);
                mStartX = (int) event.getX();
                mStartY = (int) event.getY();
                boolean result = startDragging(true /* animate */, true /* touching */);
                if (!result) {

                    // Weren't able to start dragging successfully, so cancel it again.
                    stopDragging();
                }
                mStartPosition = getCurrentPosition();
                mMoving = false;
                return result;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                int x = (int) event.getX();
                int y = (int) event.getY();
                boolean exceededTouchSlop =
                        isHorizontalDivision() && Math.abs(y - mStartY) > mTouchSlop
                                || (!isHorizontalDivision() && Math.abs(x - mStartX) > mTouchSlop);
                if (!mMoving && exceededTouchSlop) {
                    mStartX = x;
                    mStartY = y;
                    mMoving = true;
                }
                if (mMoving && mDockSide != WindowManager.DOCKED_INVALID) {
                    SnapTarget snapTarget = mSnapAlgorithm.calculateSnapTarget(
                            mStartPosition, 0 /* velocity */, false /* hardDismiss */);
                    resizeStack(calculatePosition(x, y), mStartPosition, snapTarget);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mVelocityTracker.addMovement(event);

                x = (int) event.getRawX();
                y = (int) event.getRawY();

                mVelocityTracker.computeCurrentVelocity(1000);
                int position = calculatePosition(x, y);
                stopDragging(position, isHorizontalDivision() ? mVelocityTracker.getYVelocity()
                        : mVelocityTracker.getXVelocity(), false /* avoidDismissStart */,
                        true /* log */);
                mMoving = false;
                break;
        }
        return true;
    }

    private void logResizeEvent(SnapTarget snapTarget) {
        if (snapTarget == mSnapAlgorithm.getDismissStartTarget()) {
            MetricsLogger.action(
                    mContext, MetricsEvent.ACTION_WINDOW_UNDOCK_MAX, dockSideTopLeft(mDockSide)
                            ? LOG_VALUE_UNDOCK_MAX_OTHER
                            : LOG_VALUE_UNDOCK_MAX_DOCKED);
        } else if (snapTarget == mSnapAlgorithm.getDismissEndTarget()) {
            MetricsLogger.action(
                    mContext, MetricsEvent.ACTION_WINDOW_UNDOCK_MAX, dockSideBottomRight(mDockSide)
                            ? LOG_VALUE_UNDOCK_MAX_OTHER
                            : LOG_VALUE_UNDOCK_MAX_DOCKED);
        } else if (snapTarget == mSnapAlgorithm.getMiddleTarget()) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_RESIZE,
                    LOG_VALUE_RESIZE_50_50);
        } else if (snapTarget == mSnapAlgorithm.getFirstSplitTarget()) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_RESIZE,
                    dockSideTopLeft(mDockSide)
                            ? LOG_VALUE_RESIZE_DOCKED_SMALLER
                            : LOG_VALUE_RESIZE_DOCKED_LARGER);
        } else if (snapTarget == mSnapAlgorithm.getLastSplitTarget()) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_RESIZE,
                    dockSideTopLeft(mDockSide)
                            ? LOG_VALUE_RESIZE_DOCKED_LARGER
                            : LOG_VALUE_RESIZE_DOCKED_SMALLER);
        }
    }

    private void convertToScreenCoordinates(MotionEvent event) {
        event.setLocation(event.getRawX(), event.getRawY());
    }

    private void fling(int position, float velocity, boolean avoidDismissStart,
            boolean logMetrics) {
        SnapTarget snapTarget = mSnapAlgorithm.calculateSnapTarget(position, velocity);
        if (avoidDismissStart && snapTarget == mSnapAlgorithm.getDismissStartTarget()) {
            snapTarget = mSnapAlgorithm.getFirstSplitTarget();
        }
        if (logMetrics) {
            logResizeEvent(snapTarget);
        }
        ValueAnimator anim = getFlingAnimator(position, snapTarget, 0 /* endDelay */);
        mFlingAnimationUtils.apply(anim, position, snapTarget.position, velocity);
        anim.start();
    }

    private void flingTo(int position, SnapTarget target, long duration, long startDelay,
            long endDelay, Interpolator interpolator) {
        ValueAnimator anim = getFlingAnimator(position, target, endDelay);
        anim.setDuration(duration);
        anim.setStartDelay(startDelay);
        anim.setInterpolator(interpolator);
        anim.start();
    }

    private ValueAnimator getFlingAnimator(int position, final SnapTarget snapTarget,
            final long endDelay) {
        final boolean taskPositionSameAtEnd = snapTarget.flag == SnapTarget.FLAG_NONE;
        ValueAnimator anim = ValueAnimator.ofInt(position, snapTarget.position);
        anim.addUpdateListener(animation -> resizeStack((Integer) animation.getAnimatedValue(),
                taskPositionSameAtEnd && animation.getAnimatedFraction() == 1f
                        ? TASK_POSITION_SAME
                        : snapTarget.taskPosition, snapTarget));
        Runnable endAction = () -> {
            commitSnapFlags(snapTarget);
            mWindowManagerProxy.setResizing(false);
            mDockSide = WindowManager.DOCKED_INVALID;
            mCurrentAnimator = null;
            mEntranceAnimationRunning = false;
            mExitAnimationRunning = false;
            EventBus.getDefault().send(new StoppedDragingEvent());
        };
        anim.addListener(new AnimatorListenerAdapter() {

            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (endDelay == 0 || mCancelled) {
                    endAction.run();
                } else {
                    mHandler.postDelayed(endAction, endDelay);
                }
            }
        });
        mCurrentAnimator = anim;
        return anim;
    }

    private void cancelFlingAnimation() {
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
        }
    }

    private void commitSnapFlags(SnapTarget target) {
        if (target.flag == SnapTarget.FLAG_NONE) {
            return;
        }
        boolean dismissOrMaximize;
        if (target.flag == SnapTarget.FLAG_DISMISS_START) {
            dismissOrMaximize = mDockSide == WindowManager.DOCKED_LEFT
                    || mDockSide == WindowManager.DOCKED_TOP;
        } else {
            dismissOrMaximize = mDockSide == WindowManager.DOCKED_RIGHT
                    || mDockSide == WindowManager.DOCKED_BOTTOM;
        }
        if (dismissOrMaximize) {
            mWindowManagerProxy.dismissDockedStack();
        } else {
            mWindowManagerProxy.maximizeDockedStack();
        }
        mWindowManagerProxy.setResizeDimLayer(false, -1, 0f);
    }

    private void liftBackground() {
        if (mBackgroundLifted) {
            return;
        }
        if (isHorizontalDivision()) {
            mBackground.animate().scaleY(1.4f);
        } else {
            mBackground.animate().scaleX(1.4f);
        }
        mBackground.animate()
                .setInterpolator(Interpolators.TOUCH_RESPONSE)
                .setDuration(TOUCH_ANIMATION_DURATION)
                .translationZ(mTouchElevation)
                .start();

        // Lift handle as well so it doesn't get behind the background, even though it doesn't
        // cast shadow.
        mHandle.animate()
                .setInterpolator(Interpolators.TOUCH_RESPONSE)
                .setDuration(TOUCH_ANIMATION_DURATION)
                .translationZ(mTouchElevation)
                .start();
        mBackgroundLifted = true;
    }

    private void releaseBackground() {
        if (!mBackgroundLifted) {
            return;
        }
        mBackground.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(TOUCH_RELEASE_ANIMATION_DURATION)
                .translationZ(0)
                .scaleX(1f)
                .scaleY(1f)
                .start();
        mHandle.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(TOUCH_RELEASE_ANIMATION_DURATION)
                .translationZ(0)
                .start();
        mBackgroundLifted = false;
    }


    public void setMinimizedDockStack(boolean minimized) {
        updateDockSide();
        mHandle.setAlpha(minimized ? 0f : 1f);
        if (!minimized) {
            resetBackground();
        } else if (mDockSide == WindowManager.DOCKED_TOP) {
            mBackground.setPivotY(0);
            mBackground.setScaleY(MINIMIZE_DOCK_SCALE);
        } else if (mDockSide == WindowManager.DOCKED_LEFT
                || mDockSide == WindowManager.DOCKED_RIGHT) {
            mBackground.setPivotX(mDockSide == WindowManager.DOCKED_LEFT
                    ? 0
                    : mBackground.getWidth());
            mBackground.setScaleX(MINIMIZE_DOCK_SCALE);
        }
        mMinimizedShadow.setAlpha(minimized ? 1f : 0f);
        mDockedStackMinimized = minimized;
    }

    public void setMinimizedDockStack(boolean minimized, long animDuration) {
        updateDockSide();
        mHandle.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(animDuration)
                .alpha(minimized ? 0f : 1f)
                .start();
        if (mDockSide == WindowManager.DOCKED_TOP) {
            mBackground.setPivotY(0);
            mBackground.animate()
                    .scaleY(minimized ? MINIMIZE_DOCK_SCALE : 1f);
        } else if (mDockSide == WindowManager.DOCKED_LEFT
                || mDockSide == WindowManager.DOCKED_RIGHT) {
            mBackground.setPivotX(mDockSide == WindowManager.DOCKED_LEFT
                    ? 0
                    : mBackground.getWidth());
            mBackground.animate()
                    .scaleX(minimized ? MINIMIZE_DOCK_SCALE : 1f);
        }
        if (!minimized) {
            mBackground.animate().withEndAction(mResetBackgroundRunnable);
        }
        mMinimizedShadow.animate()
                .alpha(minimized ? 1f : 0f)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setDuration(animDuration)
                .start();
        mBackground.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(animDuration)
                .start();
        mDockedStackMinimized = minimized;
    }

    public void setAdjustedForIme(boolean adjustedForIme) {
        updateDockSide();
        mHandle.setAlpha(adjustedForIme ? 0f : 1f);
        if (!adjustedForIme) {
            resetBackground();
        } else if (mDockSide == WindowManager.DOCKED_TOP) {
            mBackground.setPivotY(0);
            mBackground.setScaleY(ADJUSTED_FOR_IME_SCALE);
        }
        mAdjustedForIme = adjustedForIme;
    }

    public void setAdjustedForIme(boolean adjustedForIme, long animDuration) {
        updateDockSide();
        mHandle.animate()
                .setInterpolator(IME_ADJUST_INTERPOLATOR)
                .setDuration(animDuration)
                .alpha(adjustedForIme ? 0f : 1f)
                .start();
        if (mDockSide == WindowManager.DOCKED_TOP) {
            mBackground.setPivotY(0);
            mBackground.animate()
                    .scaleY(adjustedForIme ? ADJUSTED_FOR_IME_SCALE : 1f);
        }
        if (!adjustedForIme) {
            mBackground.animate().withEndAction(mResetBackgroundRunnable);
        }
        mBackground.animate()
                .setInterpolator(IME_ADJUST_INTERPOLATOR)
                .setDuration(animDuration)
                .start();
        mAdjustedForIme = adjustedForIme;
    }

    private void resetBackground() {
        mBackground.setPivotX(mBackground.getWidth() / 2);
        mBackground.setPivotY(mBackground.getHeight() / 2);
        mBackground.setScaleX(1f);
        mBackground.setScaleY(1f);
        mMinimizedShadow.setAlpha(0f);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDisplayInfo();
    }


    public void notifyDockSideChanged(int newDockSide) {
        mDockSide = newDockSide;
        mMinimizedShadow.setDockSide(mDockSide);
        requestLayout();
    }

    private void updateDisplayInfo() {
        final DisplayManager displayManager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final DisplayInfo info = new DisplayInfo();
        display.getDisplayInfo(info);
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
        mSnapAlgorithm = null;
        initializeSnapAlgorithm();
    }

    private int calculatePosition(int touchX, int touchY) {
        return isHorizontalDivision() ? calculateYPosition(touchY) : calculateXPosition(touchX);
    }

    public boolean isHorizontalDivision() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private int calculateXPosition(int touchX) {
        return mStartPosition + touchX - mStartX;
    }

    private int calculateYPosition(int touchY) {
        return mStartPosition + touchY - mStartY;
    }

    private void alignTopLeft(Rect containingRect, Rect rect) {
        int width = rect.width();
        int height = rect.height();
        rect.set(containingRect.left, containingRect.top,
                containingRect.left + width, containingRect.top + height);
    }

    private void alignBottomRight(Rect containingRect, Rect rect) {
        int width = rect.width();
        int height = rect.height();
        rect.set(containingRect.right - width, containingRect.bottom - height,
                containingRect.right, containingRect.bottom);
    }

    public void calculateBoundsForPosition(int position, int dockSide, Rect outRect) {
        DockedDividerUtils.calculateBoundsForPosition(position, dockSide, outRect, mDisplayWidth,
                mDisplayHeight, mDividerSize);
    }

    public void resizeStack(int position, int taskPosition, SnapTarget taskSnapTarget) {
        calculateBoundsForPosition(position, mDockSide, mDockedRect);

        if (mDockedRect.equals(mLastResizeRect) && !mEntranceAnimationRunning) {
            return;
        }

        // Make sure shadows are updated
        if (mBackground.getZ() > 0f) {
            mBackground.invalidate();
        }

        mLastResizeRect.set(mDockedRect);
        if (mEntranceAnimationRunning && taskPosition != TASK_POSITION_SAME) {
            if (mCurrentAnimator != null) {
                calculateBoundsForPosition(taskPosition, mDockSide, mDockedTaskRect);
            } else {
                calculateBoundsForPosition(isHorizontalDivision() ? mDisplayHeight : mDisplayWidth,
                        mDockSide, mDockedTaskRect);
            }
            calculateBoundsForPosition(taskPosition, DockedDividerUtils.invertDockSide(mDockSide),
                    mOtherTaskRect);
            mWindowManagerProxy.resizeDockedStack(mDockedRect, mDockedTaskRect, null,
                    mOtherTaskRect, null);
        } else if (mExitAnimationRunning && taskPosition != TASK_POSITION_SAME) {
            calculateBoundsForPosition(taskPosition,
                    mDockSide, mDockedTaskRect);
            calculateBoundsForPosition(mExitStartPosition,
                    DockedDividerUtils.invertDockSide(mDockSide), mOtherTaskRect);
            mOtherInsetRect.set(mOtherTaskRect);
            applyExitAnimationParallax(mOtherTaskRect, position);
            mWindowManagerProxy.resizeDockedStack(mDockedRect, mDockedTaskRect, null,
                    mOtherTaskRect, mOtherInsetRect);
        } else if (taskPosition != TASK_POSITION_SAME) {
            calculateBoundsForPosition(position, DockedDividerUtils.invertDockSide(mDockSide),
                    mOtherRect);
            int dockSideInverted = DockedDividerUtils.invertDockSide(mDockSide);
            int taskPositionDocked =
                    restrictDismissingTaskPosition(taskPosition, mDockSide, taskSnapTarget);
            int taskPositionOther =
                    restrictDismissingTaskPosition(taskPosition, dockSideInverted, taskSnapTarget);
            calculateBoundsForPosition(taskPositionDocked, mDockSide, mDockedTaskRect);
            calculateBoundsForPosition(taskPositionOther, dockSideInverted, mOtherTaskRect);
            mDisplayRect.set(0, 0, mDisplayWidth, mDisplayHeight);
            alignTopLeft(mDockedRect, mDockedTaskRect);
            alignTopLeft(mOtherRect, mOtherTaskRect);
            mDockedInsetRect.set(mDockedTaskRect);
            mOtherInsetRect.set(mOtherTaskRect);
            if (dockSideTopLeft(mDockSide)) {
                alignTopLeft(mDisplayRect, mDockedInsetRect);
                alignBottomRight(mDisplayRect, mOtherInsetRect);
            } else {
                alignBottomRight(mDisplayRect, mDockedInsetRect);
                alignTopLeft(mDisplayRect, mOtherInsetRect);
            }
            applyDismissingParallax(mDockedTaskRect, mDockSide, taskSnapTarget, position,
                    taskPositionDocked);
            applyDismissingParallax(mOtherTaskRect, dockSideInverted, taskSnapTarget, position,
                    taskPositionOther);
            mWindowManagerProxy.resizeDockedStack(mDockedRect, mDockedTaskRect, mDockedInsetRect,
                    mOtherTaskRect, mOtherInsetRect);
        } else {
            mWindowManagerProxy.resizeDockedStack(mDockedRect, null, null, null, null);
        }
        SnapTarget closestDismissTarget = mSnapAlgorithm.getClosestDismissTarget(position);
        float dimFraction = getDimFraction(position, closestDismissTarget);
        mWindowManagerProxy.setResizeDimLayer(dimFraction != 0f,
                getStackIdForDismissTarget(closestDismissTarget),
                dimFraction);
    }

    private void applyExitAnimationParallax(Rect taskRect, int position) {
        if (mDockSide == WindowManager.DOCKED_TOP) {
            taskRect.offset(0, (int) ((position - mExitStartPosition) * 0.25f));
        } else if (mDockSide == WindowManager.DOCKED_LEFT) {
            taskRect.offset((int) ((position - mExitStartPosition) * 0.25f), 0);
        } else if (mDockSide == WindowManager.DOCKED_RIGHT) {
            taskRect.offset((int) ((mExitStartPosition - position) * 0.25f), 0);
        }
    }

    private float getDimFraction(int position, SnapTarget dismissTarget) {
        if (mEntranceAnimationRunning) {
            return 0f;
        }
        float fraction = mSnapAlgorithm.calculateDismissingFraction(position);
        fraction = Math.max(0, Math.min(fraction, 1f));
        fraction = DIM_INTERPOLATOR.getInterpolation(fraction);
        if (hasInsetsAtDismissTarget(dismissTarget)) {

            // Less darkening with system insets.
            fraction *= 0.8f;
        }
        return fraction;
    }

    /**
     * @return true if and only if there are system insets at the location of the dismiss target
     */
    private boolean hasInsetsAtDismissTarget(SnapTarget dismissTarget) {
        if (isHorizontalDivision()) {
            if (dismissTarget == mSnapAlgorithm.getDismissStartTarget()) {
                return mStableInsets.top != 0;
            } else {
                return mStableInsets.bottom != 0;
            }
        } else {
            if (dismissTarget == mSnapAlgorithm.getDismissStartTarget()) {
                return mStableInsets.left != 0;
            } else {
                return mStableInsets.right != 0;
            }
        }
    }

    /**
     * When the snap target is dismissing one side, make sure that the dismissing side doesn't get
     * 0 size.
     */
    private int restrictDismissingTaskPosition(int taskPosition, int dockSide,
            SnapTarget snapTarget) {
        if (snapTarget.flag == SnapTarget.FLAG_DISMISS_START && dockSideTopLeft(dockSide)) {
            return Math.max(mSnapAlgorithm.getFirstSplitTarget().position, mStartPosition);
        } else if (snapTarget.flag == SnapTarget.FLAG_DISMISS_END
                && dockSideBottomRight(dockSide)) {
            return Math.min(mSnapAlgorithm.getLastSplitTarget().position, mStartPosition);
        } else {
            return taskPosition;
        }
    }

    /**
     * Applies a parallax to the task when dismissing.
     */
    private void applyDismissingParallax(Rect taskRect, int dockSide, SnapTarget snapTarget,
            int position, int taskPosition) {
        float fraction = Math.min(1, Math.max(0,
                mSnapAlgorithm.calculateDismissingFraction(position)));
        SnapTarget dismissTarget = null;
        SnapTarget splitTarget = null;
        int start = 0;
        if (position <= mSnapAlgorithm.getLastSplitTarget().position
                && dockSideTopLeft(dockSide)) {
            dismissTarget = mSnapAlgorithm.getDismissStartTarget();
            splitTarget = mSnapAlgorithm.getFirstSplitTarget();
            start = taskPosition;
        } else if (position >= mSnapAlgorithm.getLastSplitTarget().position
                && dockSideBottomRight(dockSide)) {
            dismissTarget = mSnapAlgorithm.getDismissEndTarget();
            splitTarget = mSnapAlgorithm.getLastSplitTarget();
            start = splitTarget.position;
        }
        if (dismissTarget != null && fraction > 0f
                && isDismissing(splitTarget, position, dockSide)) {
            fraction = calculateParallaxDismissingFraction(fraction, dockSide);
            int offsetPosition = (int) (start +
                    fraction * (dismissTarget.position - splitTarget.position));
            int width = taskRect.width();
            int height = taskRect.height();
            switch (dockSide) {
                case WindowManager.DOCKED_LEFT:
                    taskRect.left = offsetPosition - width;
                    taskRect.right = offsetPosition;
                    break;
                case WindowManager.DOCKED_RIGHT:
                    taskRect.left = offsetPosition + mDividerSize;
                    taskRect.right = offsetPosition + width + mDividerSize;
                    break;
                case WindowManager.DOCKED_TOP:
                    taskRect.top = offsetPosition - height;
                    taskRect.bottom = offsetPosition;
                    break;
                case WindowManager.DOCKED_BOTTOM:
                    taskRect.top = offsetPosition + mDividerSize;
                    taskRect.bottom = offsetPosition + height + mDividerSize;
                    break;
            }
        }
    }

    /**
     * @return for a specified {@code fraction}, this returns an adjusted value that simulates a
     *         slowing down parallax effect
     */
    private static float calculateParallaxDismissingFraction(float fraction, int dockSide) {
        float result = SLOWDOWN_INTERPOLATOR.getInterpolation(fraction) / 3.5f;

        // Less parallax at the top, just because.
        if (dockSide == WindowManager.DOCKED_TOP) {
            result /= 2f;
        }
        return result;
    }

    private static boolean isDismissing(SnapTarget snapTarget, int position, int dockSide) {
        if (dockSide == WindowManager.DOCKED_TOP || dockSide == WindowManager.DOCKED_LEFT) {
            return position < snapTarget.position;
        } else {
            return position > snapTarget.position;
        }
    }

    private int getStackIdForDismissTarget(SnapTarget dismissTarget) {
        if ((dismissTarget.flag == SnapTarget.FLAG_DISMISS_START && dockSideTopLeft(mDockSide))
                || (dismissTarget.flag == SnapTarget.FLAG_DISMISS_END
                        && dockSideBottomRight(mDockSide))) {
            return StackId.DOCKED_STACK_ID;
        } else {
            return StackId.HOME_STACK_ID;
        }
    }

    /**
     * @return true if and only if {@code dockSide} is top or left
     */
    private static boolean dockSideTopLeft(int dockSide) {
        return dockSide == WindowManager.DOCKED_TOP || dockSide == WindowManager.DOCKED_LEFT;
    }

    /**
     * @return true if and only if {@code dockSide} is bottom or right
     */
    private static boolean dockSideBottomRight(int dockSide) {
        return dockSide == WindowManager.DOCKED_BOTTOM || dockSide == WindowManager.DOCKED_RIGHT;
    }

    @Override
    public void onComputeInternalInsets(InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(mHandle.getLeft(), mHandle.getTop(), mHandle.getRight(),
                mHandle.getBottom());
        inoutInfo.touchableRegion.op(mBackground.getLeft(), mBackground.getTop(),
                mBackground.getRight(), mBackground.getBottom(), Op.UNION);
    }

    /**
     * Checks whether recents will grow when invoked. This happens in multi-window when recents is
     * very small. When invoking recents, we shrink the docked stack so recents has more space.
     *
     * @return the position of the divider when recents grows, or
     *         {@link #INVALID_RECENTS_GROW_TARGET} if recents won't grow
     */
    public int growsRecents() {
        boolean result = mGrowRecents
                && mWindowManagerProxy.getDockSide() == WindowManager.DOCKED_TOP
                && getCurrentPosition() == getSnapAlgorithm().getLastSplitTarget().position;
        if (result) {
            return getSnapAlgorithm().getMiddleTarget().position;
        } else {
            return INVALID_RECENTS_GROW_TARGET;
        }
    }

    public final void onBusEvent(RecentsActivityStartingEvent recentsActivityStartingEvent) {
        if (mGrowRecents && getWindowManagerProxy().getDockSide() == WindowManager.DOCKED_TOP
                && getCurrentPosition() == getSnapAlgorithm().getLastSplitTarget().position) {
            mState.growAfterRecentsDrawn = true;
            startDragging(false /* animate */, false /* touching */);
        }
    }

    public final void onBusEvent(DockedTopTaskEvent event) {
        if (event.dragMode == NavigationBarGestureHelper.DRAG_MODE_NONE) {
            mState.growAfterRecentsDrawn = false;
            mState.animateAfterRecentsDrawn = true;
            startDragging(false /* animate */, false /* touching */);
        }
        updateDockSide();
        int position = DockedDividerUtils.calculatePositionForBounds(event.initialRect,
                mDockSide, mDividerSize);
        mEntranceAnimationRunning = true;

        // Insets might not have been fetched yet, so fetch manually if needed.
        if (mStableInsets.isEmpty()) {
            SystemServicesProxy.getInstance(mContext).getStableInsets(mStableInsets);
            mSnapAlgorithm = null;
            initializeSnapAlgorithm();
        }

        resizeStack(position, mSnapAlgorithm.getMiddleTarget().position,
                mSnapAlgorithm.getMiddleTarget());
    }

    public final void onBusEvent(RecentsDrawnEvent drawnEvent) {
        if (mState.animateAfterRecentsDrawn) {
            mState.animateAfterRecentsDrawn = false;
            updateDockSide();

            mHandler.post(() -> {
                // Delay switching resizing mode because this might cause jank in recents animation
                // that's longer than this animation.
                stopDragging(getCurrentPosition(), mSnapAlgorithm.getMiddleTarget(),
                        mLongPressEntraceAnimDuration, Interpolators.FAST_OUT_SLOW_IN,
                        200 /* endDelay */);
            });
        }
        if (mState.growAfterRecentsDrawn) {
            mState.growAfterRecentsDrawn = false;
            updateDockSide();
            EventBus.getDefault().send(new RecentsGrowingEvent());
            stopDragging(getCurrentPosition(), mSnapAlgorithm.getMiddleTarget(), 336,
                    Interpolators.FAST_OUT_SLOW_IN);
        }
    }

    public final void onBusEvent(UndockingTaskEvent undockingTaskEvent) {
        int dockSide = mWindowManagerProxy.getDockSide();
        if (dockSide != WindowManager.DOCKED_INVALID && !mDockedStackMinimized) {
            startDragging(false /* animate */, false /* touching */);
            SnapTarget target = dockSideTopLeft(dockSide)
                    ? mSnapAlgorithm.getDismissEndTarget()
                    : mSnapAlgorithm.getDismissStartTarget();

            // Don't start immediately - give a little bit time to settle the drag resize change.
            mExitAnimationRunning = true;
            mExitStartPosition = getCurrentPosition();
            stopDragging(mExitStartPosition, target, 336 /* duration */, 100 /* startDelay */,
                    0 /* endDelay */, Interpolators.FAST_OUT_SLOW_IN);
        }
    }
}
