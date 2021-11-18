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

package com.android.wm.shell.legacysplitscreen;

import static android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;
import static android.view.WindowManager.DOCKED_RIGHT;

import static com.android.wm.shell.animation.Interpolators.DIM_INTERPOLATOR;
import static com.android.wm.shell.animation.Interpolators.SLOWDOWN_INTERPOLATOR;
import static com.android.wm.shell.common.split.DividerView.TOUCH_ANIMATION_DURATION;
import static com.android.wm.shell.common.split.DividerView.TOUCH_RELEASE_ANIMATION_DURATION;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.internal.policy.DockedDividerUtils;
import com.android.wm.shell.R;
import com.android.wm.shell.animation.FlingAnimationUtils;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.split.DividerHandleView;

import java.util.function.Consumer;

/**
 * Docked stack divider.
 */
public class DividerView extends FrameLayout implements OnTouchListener,
        OnComputeInternalInsetsListener {
    private static final String TAG = "DividerView";
    private static final boolean DEBUG = LegacySplitScreenController.DEBUG;

    interface DividerCallbacks {
        void onDraggingStart();
        void onDraggingEnd();
    }

    public static final int INVALID_RECENTS_GROW_TARGET = -1;

    private static final int LOG_VALUE_RESIZE_50_50 = 0;
    private static final int LOG_VALUE_RESIZE_DOCKED_SMALLER = 1;
    private static final int LOG_VALUE_RESIZE_DOCKED_LARGER = 2;

    private static final int LOG_VALUE_UNDOCK_MAX_DOCKED = 0;
    private static final int LOG_VALUE_UNDOCK_MAX_OTHER = 1;

    private static final int TASK_POSITION_SAME = Integer.MAX_VALUE;

    /**
     * How much the background gets scaled when we are in the minimized dock state.
     */
    private static final float MINIMIZE_DOCK_SCALE = 0f;
    private static final float ADJUSTED_FOR_IME_SCALE = 0.5f;

    private static final Interpolator IME_ADJUST_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0.1f, 1f);

    private DividerHandleView mHandle;
    private View mBackground;
    private MinimizedDockShadow mMinimizedShadow;
    private int mStartX;
    private int mStartY;
    private int mStartPosition;
    private int mDockSide;
    private boolean mMoving;
    private int mTouchSlop;
    private boolean mBackgroundLifted;
    private boolean mIsInMinimizeInteraction;
    SnapTarget mSnapTargetBeforeMinimized;

    private int mDividerInsets;
    private final Display mDefaultDisplay;

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
    private final Rect mTmpRect = new Rect();
    private LegacySplitScreenController mSplitScreenController;
    private WindowManagerProxy mWindowManagerProxy;
    private DividerWindowManager mWindowManager;
    private VelocityTracker mVelocityTracker;
    private FlingAnimationUtils mFlingAnimationUtils;
    private LegacySplitDisplayLayout mSplitLayout;
    private DividerImeController mImeController;
    private DividerCallbacks mCallback;

    private AnimationHandler mSfVsyncAnimationHandler;
    private ValueAnimator mCurrentAnimator;
    private boolean mEntranceAnimationRunning;
    private boolean mExitAnimationRunning;
    private int mExitStartPosition;
    private boolean mDockedStackMinimized;
    private boolean mHomeStackResizable;
    private boolean mAdjustedForIme;
    private DividerState mState;

    private LegacySplitScreenTaskListener mTiles;
    boolean mFirstLayout = true;
    int mDividerPositionX;
    int mDividerPositionY;

    private final Matrix mTmpMatrix = new Matrix();
    private final float[] mTmpValues = new float[9];

    // The view is removed or in the process of been removed from the system.
    private boolean mRemoved;

    // Whether the surface for this view has been hidden regardless of actual visibility. This is
    // used interact with keyguard.
    private boolean mSurfaceHidden = false;

    private final AccessibilityDelegate mHandleDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            final DividerSnapAlgorithm snapAlgorithm = getSnapAlgorithm();
            if (isHorizontalDivision()) {
                info.addAction(new AccessibilityAction(R.id.action_move_tl_full,
                        mContext.getString(R.string.accessibility_action_divider_top_full)));
                if (snapAlgorithm.isFirstSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_70,
                            mContext.getString(R.string.accessibility_action_divider_top_70)));
                }
                if (snapAlgorithm.showMiddleSplitTargetForAccessibility()) {
                    // Only show the middle target if there are more than 1 split target
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_50,
                            mContext.getString(R.string.accessibility_action_divider_top_50)));
                }
                if (snapAlgorithm.isLastSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_30,
                            mContext.getString(R.string.accessibility_action_divider_top_30)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_rb_full,
                        mContext.getString(R.string.accessibility_action_divider_bottom_full)));
            } else {
                info.addAction(new AccessibilityAction(R.id.action_move_tl_full,
                        mContext.getString(R.string.accessibility_action_divider_left_full)));
                if (snapAlgorithm.isFirstSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_70,
                            mContext.getString(R.string.accessibility_action_divider_left_70)));
                }
                if (snapAlgorithm.showMiddleSplitTargetForAccessibility()) {
                    // Only show the middle target if there are more than 1 split target
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_50,
                            mContext.getString(R.string.accessibility_action_divider_left_50)));
                }
                if (snapAlgorithm.isLastSplitTargetAvailable()) {
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
            DividerSnapAlgorithm snapAlgorithm = mSplitLayout.getSnapAlgorithm();
            if (action == R.id.action_move_tl_full) {
                nextTarget = snapAlgorithm.getDismissEndTarget();
            } else if (action == R.id.action_move_tl_70) {
                nextTarget = snapAlgorithm.getLastSplitTarget();
            } else if (action == R.id.action_move_tl_50) {
                nextTarget = snapAlgorithm.getMiddleTarget();
            } else if (action == R.id.action_move_tl_30) {
                nextTarget = snapAlgorithm.getFirstSplitTarget();
            } else if (action == R.id.action_move_rb_full) {
                nextTarget = snapAlgorithm.getDismissStartTarget();
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

    private Runnable mUpdateEmbeddedMatrix = () -> {
        if (getViewRootImpl() == null) {
            return;
        }
        if (isHorizontalDivision()) {
            mTmpMatrix.setTranslate(0, mDividerPositionY - mDividerInsets);
        } else {
            mTmpMatrix.setTranslate(mDividerPositionX - mDividerInsets, 0);
        }
        mTmpMatrix.getValues(mTmpValues);
        try {
            getViewRootImpl().getAccessibilityEmbeddedConnection().setWindowMatrix(mTmpValues);
        } catch (RemoteException e) {
        }
    };

    public DividerView(Context context) {
        this(context, null);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DividerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        final DisplayManager displayManager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        mDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
    }

    public void setAnimationHandler(AnimationHandler sfVsyncAnimationHandler) {
        mSfVsyncAnimationHandler = sfVsyncAnimationHandler;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandle = findViewById(R.id.docked_divider_handle);
        mBackground = findViewById(R.id.docked_divider_background);
        mMinimizedShadow = findViewById(R.id.minimized_dock_shadow);
        mHandle.setOnTouchListener(this);
        final int dividerWindowWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        mDividerSize = dividerWindowWidth - 2 * mDividerInsets;
        mTouchElevation = getResources().getDimensionPixelSize(
                R.dimen.docked_stack_divider_lift_elevation);
        mLongPressEntraceAnimDuration = getResources().getInteger(
                R.integer.long_press_dock_anim_duration);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        mFlingAnimationUtils = new FlingAnimationUtils(getResources().getDisplayMetrics(), 0.3f);
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mHandle.setPointerIcon(PointerIcon.getSystemIcon(getContext(),
                landscape ? TYPE_HORIZONTAL_DOUBLE_ARROW : TYPE_VERTICAL_DOUBLE_ARROW));
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        mHandle.setAccessibilityDelegate(mHandleDelegate);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Save the current target if not minimized once attached to window
        if (mDockSide != WindowManager.DOCKED_INVALID && !mIsInMinimizeInteraction) {
            saveSnapTargetBeforeMinimized(mSnapTargetBeforeMinimized);
        }
        mFirstLayout = true;
    }

    void onDividerRemoved() {
        mRemoved = true;
        mCallback = null;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mFirstLayout) {
            // Wait for first layout so that the ViewRootImpl surface has been created.
            initializeSurfaceState();
            mFirstLayout = false;
        }
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
            notifySplitScreenBoundsChanged();
        }
    }

    void injectDependencies(LegacySplitScreenController splitScreenController,
            DividerWindowManager windowManager, DividerState dividerState,
            DividerCallbacks callback, LegacySplitScreenTaskListener tiles,
            LegacySplitDisplayLayout sdl, DividerImeController imeController,
            WindowManagerProxy wmProxy) {
        mSplitScreenController = splitScreenController;
        mWindowManager = windowManager;
        mState = dividerState;
        mCallback = callback;
        mTiles = tiles;
        mSplitLayout = sdl;
        mImeController = imeController;
        mWindowManagerProxy = wmProxy;

        if (mState.mRatioPositionBeforeMinimized == 0) {
            // Set the middle target as the initial state
            mSnapTargetBeforeMinimized = mSplitLayout.getSnapAlgorithm().getMiddleTarget();
        } else {
            repositionSnapTargetBeforeMinimized();
        }
    }

    /** Gets non-minimized secondary bounds of split screen. */
    public Rect getNonMinimizedSplitScreenSecondaryBounds() {
        mOtherTaskRect.set(mSplitLayout.mSecondary);
        return mOtherTaskRect;
    }

    private boolean inSplitMode() {
        return getVisibility() == VISIBLE;
    }

    /** Unlike setVisible, this directly hides the surface without changing view visibility. */
    void setHidden(boolean hidden) {
        if (mSurfaceHidden == hidden) {
            return;
        }
        mSurfaceHidden = hidden;
        post(() -> {
            final SurfaceControl sc = getWindowSurfaceControl();
            if (sc == null) {
                return;
            }
            Transaction t = mTiles.getTransaction();
            if (hidden) {
                t.hide(sc);
            } else {
                t.show(sc);
            }
            mImeController.setDimsHidden(t, hidden);
            t.apply();
            mTiles.releaseTransaction(t);
        });
    }

    boolean isHidden() {
        return getVisibility() != View.VISIBLE || mSurfaceHidden;
    }

    /** Starts dragging the divider bar. */
    public boolean startDragging(boolean animate, boolean touching) {
        cancelFlingAnimation();
        if (touching) {
            mHandle.setTouching(true, animate);
        }
        mDockSide = mSplitLayout.getPrimarySplitSide();

        mWindowManagerProxy.setResizing(true);
        if (touching) {
            mWindowManager.setSlippery(false);
            liftBackground();
        }
        if (mCallback != null) {
            mCallback.onDraggingStart();
        }
        return inSplitMode();
    }

    /** Stops dragging the divider bar. */
    public void stopDragging(int position, float velocity, boolean avoidDismissStart,
            boolean logMetrics) {
        mHandle.setTouching(false, true /* animate */);
        fling(position, velocity, avoidDismissStart, logMetrics);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    private void stopDragging(int position, SnapTarget target, long duration,
            Interpolator interpolator) {
        stopDragging(position, target, duration, 0 /* startDelay*/, 0 /* endDelay */, interpolator);
    }

    private void stopDragging(int position, SnapTarget target, long duration,
            Interpolator interpolator, long endDelay) {
        stopDragging(position, target, duration, 0 /* startDelay*/, endDelay, interpolator);
    }

    private void stopDragging(int position, SnapTarget target, long duration, long startDelay,
            long endDelay, Interpolator interpolator) {
        mHandle.setTouching(false, true /* animate */);
        flingTo(position, target, duration, startDelay, endDelay, interpolator);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    private void stopDragging() {
        mHandle.setTouching(false, true /* animate */);
        mWindowManager.setSlippery(true);
        mWindowManagerProxy.setResizing(false);
        releaseBackground();
    }

    private void updateDockSide() {
        mDockSide = mSplitLayout.getPrimarySplitSide();
        mMinimizedShadow.setDockSide(mDockSide);
    }

    public DividerSnapAlgorithm getSnapAlgorithm() {
        return mDockedStackMinimized ? mSplitLayout.getMinimizedSnapAlgorithm(mHomeStackResizable)
                        : mSplitLayout.getSnapAlgorithm();
    }

    public int getCurrentPosition() {
        return isHorizontalDivision() ? mDividerPositionY : mDividerPositionX;
    }

    public boolean isMinimized() {
        return mDockedStackMinimized;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        convertToScreenCoordinates(event);
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
                    SnapTarget snapTarget = getSnapAlgorithm().calculateSnapTarget(
                            mStartPosition, 0 /* velocity */, false /* hardDismiss */);
                    resizeStackSurfaces(calculatePosition(x, y), mStartPosition, snapTarget,
                            null /* transaction */);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!mMoving) {
                    stopDragging();
                    break;
                }

                x = (int) event.getRawX();
                y = (int) event.getRawY();
                mVelocityTracker.addMovement(event);
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
        if (snapTarget == mSplitLayout.getSnapAlgorithm().getDismissStartTarget()) {
            MetricsLogger.action(
                    mContext, MetricsEvent.ACTION_WINDOW_UNDOCK_MAX, dockSideTopLeft(mDockSide)
                            ? LOG_VALUE_UNDOCK_MAX_OTHER
                            : LOG_VALUE_UNDOCK_MAX_DOCKED);
        } else if (snapTarget == mSplitLayout.getSnapAlgorithm().getDismissEndTarget()) {
            MetricsLogger.action(
                    mContext, MetricsEvent.ACTION_WINDOW_UNDOCK_MAX, dockSideBottomRight(mDockSide)
                            ? LOG_VALUE_UNDOCK_MAX_OTHER
                            : LOG_VALUE_UNDOCK_MAX_DOCKED);
        } else if (snapTarget == mSplitLayout.getSnapAlgorithm().getMiddleTarget()) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_RESIZE,
                    LOG_VALUE_RESIZE_50_50);
        } else if (snapTarget == mSplitLayout.getSnapAlgorithm().getFirstSplitTarget()) {
            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_RESIZE,
                    dockSideTopLeft(mDockSide)
                            ? LOG_VALUE_RESIZE_DOCKED_SMALLER
                            : LOG_VALUE_RESIZE_DOCKED_LARGER);
        } else if (snapTarget == mSplitLayout.getSnapAlgorithm().getLastSplitTarget()) {
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
        DividerSnapAlgorithm currentSnapAlgorithm = getSnapAlgorithm();
        SnapTarget snapTarget = currentSnapAlgorithm.calculateSnapTarget(position, velocity);
        if (avoidDismissStart && snapTarget == currentSnapAlgorithm.getDismissStartTarget()) {
            snapTarget = currentSnapAlgorithm.getFirstSplitTarget();
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
        if (mCurrentAnimator != null) {
            cancelFlingAnimation();
            updateDockSide();
        }
        if (DEBUG) Slog.d(TAG, "Getting fling " + position + "->" + snapTarget.position);
        final boolean taskPositionSameAtEnd = snapTarget.flag == SnapTarget.FLAG_NONE;
        ValueAnimator anim = ValueAnimator.ofInt(position, snapTarget.position);
        anim.addUpdateListener(animation -> resizeStackSurfaces((int) animation.getAnimatedValue(),
                taskPositionSameAtEnd && animation.getAnimatedFraction() == 1f
                        ? TASK_POSITION_SAME
                        : snapTarget.taskPosition,
                snapTarget, null /* transaction */));
        Consumer<Boolean> endAction = cancelled -> {
            if (DEBUG) Slog.d(TAG, "End Fling " + cancelled + " min:" + mIsInMinimizeInteraction);
            final boolean wasMinimizeInteraction = mIsInMinimizeInteraction;
            // Reset minimized divider position after unminimized state animation finishes.
            if (!cancelled && !mDockedStackMinimized && mIsInMinimizeInteraction) {
                mIsInMinimizeInteraction = false;
            }
            boolean dismissed = commitSnapFlags(snapTarget);
            mWindowManagerProxy.setResizing(false);
            updateDockSide();
            mCurrentAnimator = null;
            mEntranceAnimationRunning = false;
            mExitAnimationRunning = false;
            if (!dismissed && !wasMinimizeInteraction) {
                mWindowManagerProxy.applyResizeSplits(snapTarget.position, mSplitLayout);
            }
            if (mCallback != null) {
                mCallback.onDraggingEnd();
            }

            // Record last snap target the divider moved to
            if (!mIsInMinimizeInteraction) {
                // The last snapTarget position can be negative when the last divider position was
                // offscreen. In that case, save the middle (default) SnapTarget so calculating next
                // position isn't negative.
                final SnapTarget saveTarget;
                if (snapTarget.position < 0) {
                    saveTarget = mSplitLayout.getSnapAlgorithm().getMiddleTarget();
                } else {
                    saveTarget = snapTarget;
                }
                final DividerSnapAlgorithm snapAlgo = mSplitLayout.getSnapAlgorithm();
                if (saveTarget.position != snapAlgo.getDismissEndTarget().position
                        && saveTarget.position != snapAlgo.getDismissStartTarget().position) {
                    saveSnapTargetBeforeMinimized(saveTarget);
                }
            }
            notifySplitScreenBoundsChanged();
        };
        anim.addListener(new AnimatorListenerAdapter() {

            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                long delay = 0;
                if (endDelay != 0) {
                    delay = endDelay;
                } else if (mCancelled) {
                    delay = 0;
                }
                if (delay == 0) {
                    endAction.accept(mCancelled);
                } else {
                    final Boolean cancelled = mCancelled;
                    if (DEBUG) Slog.d(TAG, "Posting endFling " + cancelled + " d:" + delay + "ms");
                    getHandler().postDelayed(() -> endAction.accept(cancelled), delay);
                }
            }
        });
        mCurrentAnimator = anim;
        mCurrentAnimator.setAnimationHandler(mSfVsyncAnimationHandler);
        return anim;
    }

    private void notifySplitScreenBoundsChanged() {
        if (mSplitLayout.mPrimary == null || mSplitLayout.mSecondary == null) {
            return;
        }
        mOtherTaskRect.set(mSplitLayout.mSecondary);

        mTmpRect.set(mHandle.getLeft(), mHandle.getTop(), mHandle.getRight(), mHandle.getBottom());
        if (isHorizontalDivision()) {
            mTmpRect.offsetTo(mHandle.getLeft(), mDividerPositionY);
        } else {
            mTmpRect.offsetTo(mDividerPositionX, mHandle.getTop());
        }
        mWindowManagerProxy.setTouchRegion(mTmpRect);

        mTmpRect.set(mSplitLayout.mDisplayLayout.stableInsets());
        switch (mSplitLayout.getPrimarySplitSide()) {
            case WindowManager.DOCKED_LEFT:
                mTmpRect.left = 0;
                break;
            case WindowManager.DOCKED_RIGHT:
                mTmpRect.right = 0;
                break;
            case WindowManager.DOCKED_TOP:
                mTmpRect.top = 0;
                break;
        }
        mSplitScreenController.notifyBoundsChanged(mOtherTaskRect, mTmpRect);
    }

    private void cancelFlingAnimation() {
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
        }
    }

    private boolean commitSnapFlags(SnapTarget target) {
        if (target.flag == SnapTarget.FLAG_NONE) {
            return false;
        }
        final boolean dismissOrMaximize;
        if (target.flag == SnapTarget.FLAG_DISMISS_START) {
            dismissOrMaximize = mDockSide == WindowManager.DOCKED_LEFT
                    || mDockSide == WindowManager.DOCKED_TOP;
        } else {
            dismissOrMaximize = mDockSide == WindowManager.DOCKED_RIGHT
                    || mDockSide == WindowManager.DOCKED_BOTTOM;
        }
        mWindowManagerProxy.dismissOrMaximizeDocked(mTiles, mSplitLayout, dismissOrMaximize);
        Transaction t = mTiles.getTransaction();
        setResizeDimLayer(t, true /* primary */, 0f);
        setResizeDimLayer(t, false /* primary */, 0f);
        t.apply();
        mTiles.releaseTransaction(t);
        return true;
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

    private void initializeSurfaceState() {
        int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
        // Recalculate the split-layout's internal tile bounds
        mSplitLayout.resizeSplits(midPos);
        Transaction t = mTiles.getTransaction();
        if (mDockedStackMinimized) {
            int position = mSplitLayout.getMinimizedSnapAlgorithm(mHomeStackResizable)
                    .getMiddleTarget().position;
            calculateBoundsForPosition(position, mDockSide, mDockedRect);
            calculateBoundsForPosition(position, DockedDividerUtils.invertDockSide(mDockSide),
                    mOtherRect);
            mDividerPositionX = mDividerPositionY = position;
            resizeSplitSurfaces(t, mDockedRect, mSplitLayout.mPrimary,
                    mOtherRect, mSplitLayout.mSecondary);
        } else {
            resizeSplitSurfaces(t, mSplitLayout.mPrimary, null,
                    mSplitLayout.mSecondary, null);
        }
        setResizeDimLayer(t, true /* primary */, 0.f /* alpha */);
        setResizeDimLayer(t, false /* secondary */, 0.f /* alpha */);
        t.apply();
        mTiles.releaseTransaction(t);

        // Get the actually-visible bar dimensions (relative to full window). This is a thin
        // bar going through the center.
        final Rect dividerBar = isHorizontalDivision()
                ? new Rect(0, mDividerInsets, mSplitLayout.mDisplayLayout.width(),
                mDividerInsets + mDividerSize)
                : new Rect(mDividerInsets, 0, mDividerInsets + mDividerSize,
                mSplitLayout.mDisplayLayout.height());
        final Region touchRegion = new Region(dividerBar);
        // Add in the "draggable" portion. While not visible, this is an expanded area that the
        // user can interact with.
        touchRegion.union(new Rect(mHandle.getLeft(), mHandle.getTop(),
                mHandle.getRight(), mHandle.getBottom()));
        mWindowManager.setTouchRegion(touchRegion);
    }

    void setMinimizedDockStack(boolean minimized, boolean isHomeStackResizable,
            Transaction t) {
        mHomeStackResizable = isHomeStackResizable;
        updateDockSide();
        if (!minimized) {
            resetBackground();
        }
        mMinimizedShadow.setAlpha(minimized ? 1f : 0f);
        if (mDockedStackMinimized != minimized) {
            mDockedStackMinimized = minimized;
            if (mSplitLayout.mDisplayLayout.rotation() != mDefaultDisplay.getRotation()) {
                // Splitscreen to minimize is about to starts after rotating landscape to seascape,
                // update display info and snap algorithm targets
                repositionSnapTargetBeforeMinimized();
            }
            if (mIsInMinimizeInteraction != minimized || mCurrentAnimator != null) {
                cancelFlingAnimation();
                if (minimized) {
                    // Relayout to recalculate the divider shadow when minimizing
                    requestLayout();
                    mIsInMinimizeInteraction = true;
                    resizeStackSurfaces(mSplitLayout.getMinimizedSnapAlgorithm(mHomeStackResizable)
                            .getMiddleTarget(), t);
                } else {
                    resizeStackSurfaces(mSnapTargetBeforeMinimized, t);
                    mIsInMinimizeInteraction = false;
                }
            }
        }
    }

    void enterSplitMode(boolean isHomeStackResizable) {
        setHidden(false);

        SnapTarget miniMid =
                mSplitLayout.getMinimizedSnapAlgorithm(isHomeStackResizable).getMiddleTarget();
        if (mDockedStackMinimized) {
            mDividerPositionY = mDividerPositionX = miniMid.position;
        }
    }

    /**
     * Tries to grab a surface control from ViewRootImpl. If this isn't available for some reason
     * (ie. the window isn't ready yet), it will get the surfacecontrol that the WindowlessWM has
     * assigned to it.
     */
    private SurfaceControl getWindowSurfaceControl() {
        return mWindowManager.mSystemWindows.getViewSurface(this);
    }

    void exitSplitMode() {
        // The view is going to be removed right after this function involved, updates the surface
        // in the current thread instead of posting it to the view's UI thread.
        final SurfaceControl sc = getWindowSurfaceControl();
        if (sc == null) {
            return;
        }
        Transaction t = mTiles.getTransaction();
        t.hide(sc);
        mImeController.setDimsHidden(t, true);
        t.apply();
        mTiles.releaseTransaction(t);

        // Reset tile bounds
        int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
        mWindowManagerProxy.applyResizeSplits(midPos, mSplitLayout);
    }

    void setMinimizedDockStack(boolean minimized, long animDuration,
            boolean isHomeStackResizable) {
        if (DEBUG) Slog.d(TAG, "setMinDock: " + mDockedStackMinimized + "->" + minimized);
        mHomeStackResizable = isHomeStackResizable;
        updateDockSide();
        if (mDockedStackMinimized != minimized) {
            mIsInMinimizeInteraction = true;
            mDockedStackMinimized = minimized;
            stopDragging(minimized
                            ? mSnapTargetBeforeMinimized.position
                            : getCurrentPosition(),
                    minimized
                            ? mSplitLayout.getMinimizedSnapAlgorithm(mHomeStackResizable)
                                    .getMiddleTarget()
                            : mSnapTargetBeforeMinimized,
                    animDuration, Interpolators.FAST_OUT_SLOW_IN, 0);
            setAdjustedForIme(false, animDuration);
        }
        if (!minimized) {
            mBackground.animate().withEndAction(mResetBackgroundRunnable);
        }
        mBackground.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(animDuration)
                .start();
    }

    // Needed to end any currently playing animations when they might compete with other anims
    // (specifically, IME adjust animation immediately after leaving minimized). Someday maybe
    // these can be unified, but not today.
    void finishAnimations() {
        if (mCurrentAnimator != null) {
            mCurrentAnimator.end();
        }
    }

    void setAdjustedForIme(boolean adjustedForIme, long animDuration) {
        if (mAdjustedForIme == adjustedForIme) {
            return;
        }
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

    private void saveSnapTargetBeforeMinimized(SnapTarget target) {
        mSnapTargetBeforeMinimized = target;
        mState.mRatioPositionBeforeMinimized = (float) target.position
                / (isHorizontalDivision() ? mSplitLayout.mDisplayLayout.height()
                        : mSplitLayout.mDisplayLayout.width());
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
    }

    private void repositionSnapTargetBeforeMinimized() {
        int position = (int) (mState.mRatioPositionBeforeMinimized
                * (isHorizontalDivision() ? mSplitLayout.mDisplayLayout.height()
                        : mSplitLayout.mDisplayLayout.width()));

        // Set the snap target before minimized but do not save until divider is attached and not
        // minimized because it does not know its minimized state yet.
        mSnapTargetBeforeMinimized =
                mSplitLayout.getSnapAlgorithm().calculateNonDismissingSnapTarget(position);
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

    private void calculateBoundsForPosition(int position, int dockSide, Rect outRect) {
        DockedDividerUtils.calculateBoundsForPosition(position, dockSide, outRect,
                mSplitLayout.mDisplayLayout.width(), mSplitLayout.mDisplayLayout.height(),
                mDividerSize);
    }

    private void resizeStackSurfaces(SnapTarget taskSnapTarget, Transaction t) {
        resizeStackSurfaces(taskSnapTarget.position, taskSnapTarget.position, taskSnapTarget, t);
    }

    void resizeSplitSurfaces(Transaction t, Rect dockedRect, Rect otherRect) {
        resizeSplitSurfaces(t, dockedRect, null, otherRect, null);
    }

    private void resizeSplitSurfaces(Transaction t, Rect dockedRect, Rect dockedTaskRect,
            Rect otherRect, Rect otherTaskRect) {
        dockedTaskRect = dockedTaskRect == null ? dockedRect : dockedTaskRect;
        otherTaskRect = otherTaskRect == null ? otherRect : otherTaskRect;

        mDividerPositionX = mSplitLayout.getPrimarySplitSide() == DOCKED_RIGHT
                ? otherRect.right : dockedRect.right;
        mDividerPositionY = dockedRect.bottom;

        if (DEBUG) {
            Slog.d(TAG, "Resizing split surfaces: " + dockedRect + " " + dockedTaskRect
                    + " " + otherRect + " " + otherTaskRect);
        }

        t.setPosition(mTiles.mPrimarySurface, dockedTaskRect.left, dockedTaskRect.top);
        Rect crop = new Rect(dockedRect);
        crop.offsetTo(-Math.min(dockedTaskRect.left - dockedRect.left, 0),
                -Math.min(dockedTaskRect.top - dockedRect.top, 0));
        t.setWindowCrop(mTiles.mPrimarySurface, crop);
        t.setPosition(mTiles.mSecondarySurface, otherTaskRect.left, otherTaskRect.top);
        crop.set(otherRect);
        crop.offsetTo(-(otherTaskRect.left - otherRect.left),
                -(otherTaskRect.top - otherRect.top));
        t.setWindowCrop(mTiles.mSecondarySurface, crop);
        final SurfaceControl dividerCtrl = getWindowSurfaceControl();
        if (dividerCtrl != null) {
            if (isHorizontalDivision()) {
                t.setPosition(dividerCtrl, 0, mDividerPositionY - mDividerInsets);
            } else {
                t.setPosition(dividerCtrl, mDividerPositionX - mDividerInsets, 0);
            }
        }
        if (getViewRootImpl() != null) {
            getHandler().removeCallbacks(mUpdateEmbeddedMatrix);
            getHandler().post(mUpdateEmbeddedMatrix);
        }
    }

    void setResizeDimLayer(Transaction t, boolean primary, float alpha) {
        SurfaceControl dim = primary ? mTiles.mPrimaryDim : mTiles.mSecondaryDim;
        if (alpha <= 0.001f) {
            t.hide(dim);
        } else {
            t.setAlpha(dim, alpha);
            t.show(dim);
        }
    }

    void resizeStackSurfaces(int position, int taskPosition, SnapTarget taskSnapTarget,
            Transaction transaction) {
        if (mRemoved) {
            // This divider view has been removed so shouldn't have any additional influence.
            return;
        }
        calculateBoundsForPosition(position, mDockSide, mDockedRect);
        calculateBoundsForPosition(position, DockedDividerUtils.invertDockSide(mDockSide),
                mOtherRect);

        if (mDockedRect.equals(mLastResizeRect) && !mEntranceAnimationRunning) {
            return;
        }

        // Make sure shadows are updated
        if (mBackground.getZ() > 0f) {
            mBackground.invalidate();
        }

        final boolean ownTransaction = transaction == null;
        final Transaction t = ownTransaction ? mTiles.getTransaction() : transaction;
        mLastResizeRect.set(mDockedRect);
        if (mIsInMinimizeInteraction) {
            calculateBoundsForPosition(mSnapTargetBeforeMinimized.position, mDockSide,
                    mDockedTaskRect);
            calculateBoundsForPosition(mSnapTargetBeforeMinimized.position,
                    DockedDividerUtils.invertDockSide(mDockSide), mOtherTaskRect);

            // Move a right-docked-app to line up with the divider while dragging it
            if (mDockSide == DOCKED_RIGHT) {
                mDockedTaskRect.offset(Math.max(position, -mDividerSize)
                        - mDockedTaskRect.left + mDividerSize, 0);
            }
            resizeSplitSurfaces(t, mDockedRect, mDockedTaskRect, mOtherRect, mOtherTaskRect);
            if (ownTransaction) {
                t.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());
                t.apply();
                mTiles.releaseTransaction(t);
            }
            return;
        }

        if (mEntranceAnimationRunning && taskPosition != TASK_POSITION_SAME) {
            calculateBoundsForPosition(taskPosition, mDockSide, mDockedTaskRect);

            // Move a docked app if from the right in position with the divider up to insets
            if (mDockSide == DOCKED_RIGHT) {
                mDockedTaskRect.offset(Math.max(position, -mDividerSize)
                        - mDockedTaskRect.left + mDividerSize, 0);
            }
            calculateBoundsForPosition(taskPosition, DockedDividerUtils.invertDockSide(mDockSide),
                    mOtherTaskRect);
            resizeSplitSurfaces(t, mDockedRect, mDockedTaskRect, mOtherRect, mOtherTaskRect);
        } else if (mExitAnimationRunning && taskPosition != TASK_POSITION_SAME) {
            calculateBoundsForPosition(taskPosition, mDockSide, mDockedTaskRect);
            mDockedInsetRect.set(mDockedTaskRect);
            calculateBoundsForPosition(mExitStartPosition,
                    DockedDividerUtils.invertDockSide(mDockSide), mOtherTaskRect);
            mOtherInsetRect.set(mOtherTaskRect);
            applyExitAnimationParallax(mOtherTaskRect, position);

            // Move a right-docked-app to line up with the divider while dragging it
            if (mDockSide == DOCKED_RIGHT) {
                mDockedTaskRect.offset(position + mDividerSize, 0);
            }
            resizeSplitSurfaces(t, mDockedRect, mDockedTaskRect, mOtherRect, mOtherTaskRect);
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
            mTmpRect.set(0, 0, mSplitLayout.mDisplayLayout.width(),
                    mSplitLayout.mDisplayLayout.height());
            alignTopLeft(mDockedRect, mDockedTaskRect);
            alignTopLeft(mOtherRect, mOtherTaskRect);
            mDockedInsetRect.set(mDockedTaskRect);
            mOtherInsetRect.set(mOtherTaskRect);
            if (dockSideTopLeft(mDockSide)) {
                alignTopLeft(mTmpRect, mDockedInsetRect);
                alignBottomRight(mTmpRect, mOtherInsetRect);
            } else {
                alignBottomRight(mTmpRect, mDockedInsetRect);
                alignTopLeft(mTmpRect, mOtherInsetRect);
            }
            applyDismissingParallax(mDockedTaskRect, mDockSide, taskSnapTarget, position,
                    taskPositionDocked);
            applyDismissingParallax(mOtherTaskRect, dockSideInverted, taskSnapTarget, position,
                    taskPositionOther);
            resizeSplitSurfaces(t, mDockedRect, mDockedTaskRect, mOtherRect, mOtherTaskRect);
        } else {
            resizeSplitSurfaces(t, mDockedRect, null, mOtherRect, null);
        }
        SnapTarget closestDismissTarget = getSnapAlgorithm().getClosestDismissTarget(position);
        float dimFraction = getDimFraction(position, closestDismissTarget);
        setResizeDimLayer(t, isDismissTargetPrimary(closestDismissTarget), dimFraction);
        if (ownTransaction) {
            t.apply();
            mTiles.releaseTransaction(t);
        }
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
        float fraction = getSnapAlgorithm().calculateDismissingFraction(position);
        fraction = Math.max(0, Math.min(fraction, 1f));
        fraction = DIM_INTERPOLATOR.getInterpolation(fraction);
        return fraction;
    }

    /**
     * When the snap target is dismissing one side, make sure that the dismissing side doesn't get
     * 0 size.
     */
    private int restrictDismissingTaskPosition(int taskPosition, int dockSide,
            SnapTarget snapTarget) {
        if (snapTarget.flag == SnapTarget.FLAG_DISMISS_START && dockSideTopLeft(dockSide)) {
            return Math.max(mSplitLayout.getSnapAlgorithm().getFirstSplitTarget().position,
                    mStartPosition);
        } else if (snapTarget.flag == SnapTarget.FLAG_DISMISS_END
                && dockSideBottomRight(dockSide)) {
            return Math.min(mSplitLayout.getSnapAlgorithm().getLastSplitTarget().position,
                    mStartPosition);
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
                mSplitLayout.getSnapAlgorithm().calculateDismissingFraction(position)));
        SnapTarget dismissTarget = null;
        SnapTarget splitTarget = null;
        int start = 0;
        if (position <= mSplitLayout.getSnapAlgorithm().getLastSplitTarget().position
                && dockSideTopLeft(dockSide)) {
            dismissTarget = mSplitLayout.getSnapAlgorithm().getDismissStartTarget();
            splitTarget = mSplitLayout.getSnapAlgorithm().getFirstSplitTarget();
            start = taskPosition;
        } else if (position >= mSplitLayout.getSnapAlgorithm().getLastSplitTarget().position
                && dockSideBottomRight(dockSide)) {
            dismissTarget = mSplitLayout.getSnapAlgorithm().getDismissEndTarget();
            splitTarget = mSplitLayout.getSnapAlgorithm().getLastSplitTarget();
            start = splitTarget.position;
        }
        if (dismissTarget != null && fraction > 0f
                && isDismissing(splitTarget, position, dockSide)) {
            fraction = calculateParallaxDismissingFraction(fraction, dockSide);
            int offsetPosition = (int) (start + fraction
                    * (dismissTarget.position - splitTarget.position));
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

    private boolean isDismissTargetPrimary(SnapTarget dismissTarget) {
        return (dismissTarget.flag == SnapTarget.FLAG_DISMISS_START && dockSideTopLeft(mDockSide))
                || (dismissTarget.flag == SnapTarget.FLAG_DISMISS_END
                        && dockSideBottomRight(mDockSide));
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

    void onUndockingTask() {
        int dockSide = mSplitLayout.getPrimarySplitSide();
        if (inSplitMode()) {
            startDragging(false /* animate */, false /* touching */);
            SnapTarget target = dockSideTopLeft(dockSide)
                    ? mSplitLayout.getSnapAlgorithm().getDismissEndTarget()
                    : mSplitLayout.getSnapAlgorithm().getDismissStartTarget();

            // Don't start immediately - give a little bit time to settle the drag resize change.
            mExitAnimationRunning = true;
            mExitStartPosition = getCurrentPosition();
            stopDragging(mExitStartPosition, target, 336 /* duration */, 100 /* startDelay */,
                    0 /* endDelay */, Interpolators.FAST_OUT_SLOW_IN);
        }
    }

    private int calculatePositionForInsetBounds() {
        mSplitLayout.mDisplayLayout.getStableBounds(mTmpRect);
        return DockedDividerUtils.calculatePositionForBounds(mTmpRect, mDockSide, mDividerSize);
    }
}
