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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManager;
import android.util.AttributeSet;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.android.systemui.R;
import com.android.systemui.stackdivider.DividerSnapAlgorithm.SnapTarget;
import com.android.systemui.statusbar.FlingAnimationUtils;

import static android.view.PointerIcon.STYLE_HORIZONTAL_DOUBLE_ARROW;
import static android.view.PointerIcon.STYLE_VERTICAL_DOUBLE_ARROW;

/**
 * Docked stack divider.
 */
public class DividerView extends FrameLayout implements OnTouchListener,
        OnComputeInternalInsetsListener {

    private static final String TAG = "DividerView";

    private static final int TASK_POSITION_SAME = Integer.MAX_VALUE;

    private ImageButton mHandle;
    private View mBackground;
    private int mStartX;
    private int mStartY;
    private int mStartPosition;
    private int mDockSide;
    private final int[] mTempInt2 = new int[2];

    private int mDividerInsets;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mDividerWindowWidth;
    private int mDividerSize;
    private int mTouchElevation;

    private final Rect mDockedRect = new Rect();
    private final Rect mDockedTaskRect = new Rect();
    private final Rect mOtherTaskRect = new Rect();
    private final Rect mOtherRect = new Rect();
    private final Rect mDockedInsetRect = new Rect();
    private final Rect mOtherInsetRect = new Rect();
    private final Rect mLastResizeRect = new Rect();
    private final WindowManagerProxy mWindowManagerProxy = WindowManagerProxy.getInstance();
    private Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mTouchResponseInterpolator =
            new PathInterpolator(0.3f, 0f, 0.1f, 1f);
    private DividerWindowManager mWindowManager;
    private VelocityTracker mVelocityTracker;
    private FlingAnimationUtils mFlingAnimationUtils;
    private DividerSnapAlgorithm mSnapAlgorithm;

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
        mHandle = (ImageButton) findViewById(R.id.docked_divider_handle);
        mBackground = findViewById(R.id.docked_divider_background);
        mHandle.setOnTouchListener(this);
        mDividerWindowWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        mDividerSize = mDividerWindowWidth - 2 * mDividerInsets;
        mTouchElevation = getResources().getDimensionPixelSize(
                R.dimen.docked_stack_divider_lift_elevation);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
        mFlingAnimationUtils = new FlingAnimationUtils(getContext(), 0.3f);
        updateDisplayInfo();
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mHandle.setPointerIcon(PointerIcon.getSystemIcon(getContext(),
                landscape ? STYLE_HORIZONTAL_DOUBLE_ARROW : STYLE_VERTICAL_DOUBLE_ARROW));
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    public void setWindowManager(DividerWindowManager windowManager) {
        mWindowManager = windowManager;
    }

    public WindowManagerProxy getWindowManagerProxy() {
        return mWindowManagerProxy;
    }

    public boolean startDragging() {
        mDockSide = mWindowManagerProxy.getDockSide();
        mSnapAlgorithm = new DividerSnapAlgorithm(getContext(), mFlingAnimationUtils,
                mDividerSize, isHorizontalDivision());
        if (mDockSide != WindowManager.DOCKED_INVALID) {
            mWindowManagerProxy.setResizing(true);
            mWindowManager.setSlippery(false);
            liftBackground();
            return true;
        } else {
            return false;
        }
    }

    public void stopDragging(int position, float velocity) {
        fling(position, velocity);
        mWindowManager.setSlippery(true);
        releaseBackground();
    }

    public DividerSnapAlgorithm getSnapAlgorithm() {
        return mSnapAlgorithm;
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
                getLocationOnScreen(mTempInt2);
                boolean result = startDragging();
                if (isHorizontalDivision()) {
                    mStartPosition = mTempInt2[1] + mDividerInsets;
                } else {
                    mStartPosition = mTempInt2[0] + mDividerInsets;
                }
                return result;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                int x = (int) event.getX();
                int y = (int) event.getY();
                if (mDockSide != WindowManager.DOCKED_INVALID) {
                    int position = calculatePosition(x, y);
                    SnapTarget snapTarget = mSnapAlgorithm.calculateSnapTarget(position,
                            0 /* velocity */);
                    resizeStack(calculatePosition(x, y), snapTarget.position);
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
                        : mVelocityTracker.getXVelocity());
                break;
        }
        return true;
    }

    private void convertToScreenCoordinates(MotionEvent event) {
        event.setLocation(event.getRawX(), event.getRawY());
    }

    private void fling(int position, float velocity) {
        final SnapTarget snapTarget = mSnapAlgorithm.calculateSnapTarget(position, velocity);

        ValueAnimator anim = ValueAnimator.ofInt(position, snapTarget.position);
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                resizeStack((Integer) animation.getAnimatedValue(),
                        animation.getAnimatedFraction() == 1f
                                ? TASK_POSITION_SAME
                                : snapTarget.position);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                commitSnapFlags(snapTarget);
                mWindowManagerProxy.setResizing(false);
                mDockSide = WindowManager.DOCKED_INVALID;
            }
        });
        mFlingAnimationUtils.apply(anim, position, snapTarget.position, velocity);
        anim.start();
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
    }

    private void liftBackground() {
        if (isHorizontalDivision()) {
            mBackground.animate().scaleY(1.4f);
        } else {
            mBackground.animate().scaleX(1.4f);
        }
        mBackground.animate()
                .setInterpolator(mTouchResponseInterpolator)
                .setDuration(150)
                .translationZ(mTouchElevation);

        // Lift handle as well so it doesn't get behind the background, even though it doesn't
        // cast shadow.
        mHandle.animate()
                .setInterpolator(mTouchResponseInterpolator)
                .setDuration(150)
                .translationZ(mTouchElevation);
    }

    private void releaseBackground() {
        mBackground.animate()
                .setInterpolator(mFastOutSlowInInterpolator)
                .setDuration(200)
                .translationZ(0)
                .scaleX(1f)
                .scaleY(1f);
        mHandle.animate()
                .setInterpolator(mFastOutSlowInInterpolator)
                .setDuration(200)
                .translationZ(0);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDisplayInfo();
    }

    private void updateDisplayInfo() {
        final DisplayManager displayManager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final DisplayInfo info = new DisplayInfo();
        display.getDisplayInfo(info);
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
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

    public void calculateBoundsForPosition(int position, int dockSide, Rect outRect) {
        outRect.set(0, 0, mDisplayWidth, mDisplayHeight);
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                outRect.right = position;
                break;
            case WindowManager.DOCKED_TOP:
                outRect.bottom = position;
                break;
            case WindowManager.DOCKED_RIGHT:
                outRect.left = position + mDividerWindowWidth - 2 * mDividerInsets;
                break;
            case WindowManager.DOCKED_BOTTOM:
                outRect.top = position + mDividerWindowWidth - 2 * mDividerInsets;
                break;
        }
        if (outRect.left > outRect.right) {
            outRect.left = outRect.right;
        }
        if (outRect.top > outRect.bottom) {
            outRect.top = outRect.bottom;
        }
        if (outRect.right < outRect.left) {
            outRect.right = outRect.left;
        }
        if (outRect.bottom < outRect.top) {
            outRect.bottom = outRect.top;
        }
    }

    private int invertDockSide(int dockSide) {
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                return WindowManager.DOCKED_RIGHT;
            case WindowManager.DOCKED_TOP:
                return WindowManager.DOCKED_BOTTOM;
            case WindowManager.DOCKED_RIGHT:
                return WindowManager.DOCKED_LEFT;
            case WindowManager.DOCKED_BOTTOM:
                return WindowManager.DOCKED_TOP;
            default:
                return WindowManager.DOCKED_INVALID;
        }
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

    public void resizeStack(int position, int taskPosition) {
        calculateBoundsForPosition(position, mDockSide, mDockedRect);

        if (mDockedRect.equals(mLastResizeRect)) {
            return;
        }

        // Make sure shadows are updated
        mBackground.invalidate();

        mLastResizeRect.set(mDockedRect);
        if (taskPosition != TASK_POSITION_SAME) {
            calculateBoundsForPosition(position, invertDockSide(mDockSide), mOtherRect);
            calculateBoundsForPosition(taskPosition, mDockSide, mDockedTaskRect);
            calculateBoundsForPosition(taskPosition, invertDockSide(mDockSide), mOtherTaskRect);
            alignTopLeft(mDockedRect, mDockedTaskRect);
            alignTopLeft(mOtherRect, mOtherTaskRect);
            mDockedInsetRect.set(mDockedTaskRect);
            mOtherInsetRect.set(mOtherTaskRect);
            if (mDockSide == WindowManager.DOCKED_LEFT || mDockSide == WindowManager.DOCKED_TOP) {
                alignTopLeft(mDockedRect, mDockedInsetRect);
                alignBottomRight(mOtherRect, mOtherInsetRect);
            } else {
                alignBottomRight(mDockedRect, mDockedInsetRect);
                alignTopLeft(mOtherRect, mOtherInsetRect);
            }
            mWindowManagerProxy.resizeDockedStack(mDockedRect, mDockedTaskRect, mDockedInsetRect,
                    mOtherTaskRect, mOtherInsetRect);
        } else {
            mWindowManagerProxy.resizeDockedStack(mDockedRect, null, null, null, null);
        }
    }

    @Override
    public void onComputeInternalInsets(InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(mHandle.getLeft(), mHandle.getTop(), mHandle.getRight(),
                mHandle.getBottom());
        inoutInfo.touchableRegion.op(mBackground.getLeft(), mBackground.getTop(),
                mBackground.getRight(), mBackground.getBottom(), Op.UNION);
    }
}
