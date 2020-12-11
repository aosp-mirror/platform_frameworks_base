/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.common.split;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.policy.DividerSnapAlgorithm;

/**
 * Stack divider for app pair.
 */
// TODO(b/172704238): add handle view to indicate touching status.
public class DividerView extends FrameLayout implements View.OnTouchListener {
    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    private SplitLayout mSplitLayout;
    private SurfaceControlViewHost mViewHost;
    private DragListener mDragListener;

    private VelocityTracker mVelocityTracker;
    private boolean mMoving;
    private int mStartPos;

    public DividerView(@NonNull Context context) {
        super(context);
    }

    public DividerView(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DividerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DividerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Sets up essential dependencies of the divider bar. */
    public void setup(
            SplitLayout layout,
            SurfaceControlViewHost viewHost,
            @Nullable DragListener dragListener) {
        mSplitLayout = layout;
        mViewHost = viewHost;
        mDragListener = dragListener;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setOnTouchListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mSplitLayout == null) {
            return false;
        }

        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        final boolean isLandscape = isLandscape();
        // Using raw xy to prevent lost track of motion events while moving divider bar.
        final int touchPos = isLandscape ? (int) event.getRawX() : (int) event.getRawY();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(event);
                setSlippery(false);
                mStartPos = touchPos;
                mMoving = false;
                break;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                if (!mMoving && Math.abs(touchPos - mStartPos) > mTouchSlop) {
                    mStartPos = touchPos;
                    mMoving = true;
                    if (mDragListener != null) {
                        mDragListener.onDragStart();
                    }
                }
                if (mMoving) {
                    final int position = mSplitLayout.getDividePosition() + touchPos - mStartPos;
                    mSplitLayout.updateDivideBounds(position);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mVelocityTracker.addMovement(event);
                mVelocityTracker.computeCurrentVelocity(1000 /* units */);
                final float velocity = isLandscape
                        ? mVelocityTracker.getXVelocity()
                        : mVelocityTracker.getYVelocity();
                setSlippery(true);
                mMoving = false;
                if (mDragListener != null) {
                    mDragListener.onDragEnd();
                }

                final int position = mSplitLayout.getDividePosition() + touchPos - mStartPos;
                final DividerSnapAlgorithm.SnapTarget snapTarget =
                        mSplitLayout.findSnapTarget(position, velocity);
                mSplitLayout.snapToTarget(position, snapTarget);
                break;
        }
        return true;
    }

    private void setSlippery(boolean slippery) {
        if (mViewHost == null) {
            return;
        }

        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        final boolean isSlippery = (lp.flags & FLAG_SLIPPERY) != 0;
        if (isSlippery == slippery) {
            return;
        }

        if (slippery) {
            lp.flags |= FLAG_SLIPPERY;
        } else {
            lp.flags &= ~FLAG_SLIPPERY;
        }
        mViewHost.relayout(lp);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE;
    }

    /** Monitors dragging action of the divider bar. */
    // TODO(b/172704238): add listeners to deal with resizing state of the app windows.
    public interface DragListener {
        /** Called when start dragging. */
        void onDragStart();
        /** Called when stop dragging. */
        void onDragEnd();
    }
}
