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

package com.android.systemui.accessibility;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.res.R;

/**
 * A basic control to move the mirror window.
 */
class SimpleMirrorWindowControl extends MirrorWindowControl implements View.OnClickListener,
        View.OnTouchListener, View.OnLongClickListener {

    private static final String TAG = "SimpleMirrorWindowControl";
    private static final int MOVE_FRAME_DURATION_MS = 100;
    private final int mMoveFrameAmountShort;
    private final int mMoveFrameAmountLong;

    private boolean mIsDragState;
    private boolean mShouldSetTouchStart;

    @Nullable private MoveWindowTask mMoveWindowTask;
    private final PointF mLastDrag = new PointF();
    private final Handler mHandler;

    SimpleMirrorWindowControl(Context context, Handler handler) {
        super(context);
        mHandler = handler;
        final Resources resource = context.getResources();
        mMoveFrameAmountShort = resource.getDimensionPixelSize(
                R.dimen.magnification_frame_move_short);
        mMoveFrameAmountLong = resource.getDimensionPixelSize(
                R.dimen.magnification_frame_move_long);
    }

    @Override
    String getWindowTitle() {
        return mContext.getString(R.string.magnification_controls_title);
    }

    @Override
    View onCreateView(LayoutInflater layoutInflater, Point viewSize) {
        final View view = layoutInflater.inflate(R.layout.magnifier_controllers, null);
        final View leftControl = view.findViewById(R.id.left_control);
        final View upControl = view.findViewById(R.id.up_control);
        final View rightControl = view.findViewById(R.id.right_control);
        final View bottomControl = view.findViewById(R.id.down_control);

        leftControl.setOnClickListener(this);
        upControl.setOnClickListener(this);
        rightControl.setOnClickListener(this);
        bottomControl.setOnClickListener(this);

        leftControl.setOnLongClickListener(this);
        upControl.setOnLongClickListener(this);
        rightControl.setOnLongClickListener(this);
        bottomControl.setOnLongClickListener(this);

        leftControl.setOnTouchListener(this);
        upControl.setOnTouchListener(this);
        rightControl.setOnTouchListener(this);
        bottomControl.setOnTouchListener(this);

        view.setOnTouchListener(this);
        view.setOnLongClickListener(this::onViewRootLongClick);
        return view;
    }

    private Point findOffset(View v, int moveFrameAmount) {
        mTmpPoint.set(0, 0);
        if (v.getId() == R.id.left_control) {
            mTmpPoint.x = -moveFrameAmount;
        } else if (v.getId() == R.id.up_control) {
            mTmpPoint.y = -moveFrameAmount;
        } else if (v.getId() == R.id.right_control) {
            mTmpPoint.x = moveFrameAmount;
        } else if (v.getId() == R.id.down_control) {
            mTmpPoint.y = moveFrameAmount;
        } else {
            Log.w(TAG, "findOffset move is zero ");
        }
        return mTmpPoint;
    }

    @Override
    public void onClick(View v) {
        if (mMirrorWindowDelegate != null) {
            Point offset = findOffset(v, mMoveFrameAmountShort);
            mMirrorWindowDelegate.move(offset.x, offset.y);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (handleDragState(event)) {
            return true;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mMoveWindowTask != null) {
                    mMoveWindowTask.cancel();
                    mMoveWindowTask = null;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onLongClick(View v) {
        Point offset = findOffset(v, mMoveFrameAmountLong);
        mMoveWindowTask = new MoveWindowTask(mMirrorWindowDelegate, mHandler, offset.x, offset.y,
                MOVE_FRAME_DURATION_MS);
        mMoveWindowTask.schedule();
        return true;
    }

    private boolean onViewRootLongClick(View view) {
        mIsDragState = true;
        mShouldSetTouchStart = true;
        return true;
    }

    private boolean handleDragState(final MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (mIsDragState) {
                    if (mShouldSetTouchStart) {
                        mLastDrag.set(event.getRawX(), event.getRawY());
                        mShouldSetTouchStart = false;
                    }
                    int xDiff = (int) (event.getRawX() - mLastDrag.x);
                    int yDiff = (int) (event.getRawY() - mLastDrag.y);
                    move(xDiff, yDiff);
                    mLastDrag.set(event.getRawX(), event.getRawY());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIsDragState) {
                    mIsDragState = false;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * A timer task to move the mirror window periodically.
     */
    static class MoveWindowTask implements Runnable {
        private final MirrorWindowDelegate mMirrorWindowDelegate;
        private final int mXOffset;
        private final int mYOffset;
        private final Handler mHandler;
        /** Time in milliseconds between successive task executions.*/
        private final long mPeriod;
        private boolean mCancel;

        MoveWindowTask(@NonNull MirrorWindowDelegate windowDelegate, Handler handler, int xOffset,
                int yOffset, long period) {
            mMirrorWindowDelegate = windowDelegate;
            mXOffset = xOffset;
            mYOffset = yOffset;
            mHandler = handler;
            mPeriod = period;
        }

        @Override
        public void run() {
            if (mCancel) {
                return;
            }
            mMirrorWindowDelegate.move(mXOffset, mYOffset);
            schedule();
        }

        /**
         * Schedules the specified task periodically and immediately.
         */
        void schedule() {
            mHandler.postDelayed(this, mPeriod);
            mCancel = false;
        }

        void cancel() {
            mHandler.removeCallbacks(this);
            mCancel = true;
        }
    }
}
