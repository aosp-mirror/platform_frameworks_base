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
package com.android.systemui.pip.phone;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.PIP_USER_RESIZE;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_BOTTOM;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_LEFT;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_NONE;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_RIGHT;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_TOP;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.policy.TaskResizingAlgorithm;
import com.android.systemui.R;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.util.DeviceConfigProxy;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Helper on top of PipTouchHandler that handles inputs OUTSIDE of the PIP window, which is used to
 * trigger dynamic resize.
 */
public class PipResizeGestureHandler {

    private static final String TAG = "PipResizeGestureHandler";

    private final Context mContext;
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipMotionHelper mMotionHelper;
    private final int mDisplayId;
    private final Executor mMainExecutor;
    private final Region mTmpRegion = new Region();

    private final PointF mDownPoint = new PointF();
    private final Point mMaxSize = new Point();
    private final Point mMinSize = new Point();
    private final Rect mLastResizeBounds = new Rect();
    private final Rect mLastDownBounds = new Rect();
    private final Rect mDragCornerSize = new Rect();
    private final Rect mTmpTopLeftCorner = new Rect();
    private final Rect mTmpTopRightCorner = new Rect();
    private final Rect mTmpBottomLeftCorner = new Rect();
    private final Rect mTmpBottomRightCorner = new Rect();
    private final Rect mDisplayBounds = new Rect();
    private final Supplier<Rect> mMovementBoundsSupplier;
    private final Runnable mUpdateMovementBoundsRunnable;

    private int mDelta;
    private float mTouchSlop;
    private boolean mAllowGesture;
    private boolean mIsAttached;
    private boolean mIsEnabled;
    private boolean mEnableUserResize;
    private boolean mThresholdCrossed;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;
    private PipTaskOrganizer mPipTaskOrganizer;

    private int mCtrlType;

    public PipResizeGestureHandler(Context context, PipBoundsHandler pipBoundsHandler,
            PipMotionHelper motionHelper, DeviceConfigProxy deviceConfig,
            PipTaskOrganizer pipTaskOrganizer, Supplier<Rect> movementBoundsSupplier,
            Runnable updateMovementBoundsRunnable) {
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = context.getMainExecutor();
        mPipBoundsHandler = pipBoundsHandler;
        mMotionHelper = motionHelper;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMovementBoundsSupplier = movementBoundsSupplier;
        mUpdateMovementBoundsRunnable = updateMovementBoundsRunnable;

        context.getDisplay().getRealSize(mMaxSize);
        reloadResources();

        mEnableUserResize = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_USER_RESIZE,
                /* defaultValue = */ true);
        deviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI, mMainExecutor,
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        if (properties.getKeyset().contains(PIP_USER_RESIZE)) {
                            mEnableUserResize = properties.getBoolean(
                                    PIP_USER_RESIZE, /* defaultValue = */ true);
                        }
                    }
                });
    }

    public void onConfigurationChanged() {
        reloadResources();
    }

    private void reloadResources() {
        final Resources res = mContext.getResources();
        mDelta = res.getDimensionPixelSize(R.dimen.pip_resize_edge_size);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
    }

    private void resetDragCorners() {
        mDragCornerSize.set(0, 0, mDelta, mDelta);
        mTmpTopLeftCorner.set(mDragCornerSize);
        mTmpTopRightCorner.set(mDragCornerSize);
        mTmpBottomLeftCorner.set(mDragCornerSize);
        mTmpBottomRightCorner.set(mDragCornerSize);
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    void onActivityPinned() {
        mIsAttached = true;
        updateIsEnabled();
    }

    void onActivityUnpinned() {
        mIsAttached = false;
        updateIsEnabled();
    }

    private void updateIsEnabled() {
        boolean isEnabled = mIsAttached && mEnableUserResize;
        if (isEnabled == mIsEnabled) {
            return;
        }
        mIsEnabled = isEnabled;
        disposeInputChannel();

        if (mIsEnabled) {
            // Register input event receiver
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "pip-resize", mDisplayId);
            mInputEventReceiver = new SysUiInputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper());
        }
    }

    private void onInputEvent(InputEvent ev) {
        if (ev instanceof MotionEvent) {
            onMotionEvent((MotionEvent) ev);
        }
    }

    /**
     * Check whether the current x,y coordinate is within the region in which drag-resize should
     * start.
     * This consists of 4 small squares on the 4 corners of the PIP window, a quarter of which
     * overlaps with the PIP window while the rest goes outside of the PIP window.
     *  _ _           _ _
     * |_|_|_________|_|_|
     * |_|_|         |_|_|
     *   |     PIP     |
     *   |   WINDOW    |
     *  _|_           _|_
     * |_|_|_________|_|_|
     * |_|_|         |_|_|
     */
    public boolean isWithinTouchRegion(int x, int y) {
        final Rect currentPipBounds = mMotionHelper.getBounds();
        if (currentPipBounds == null) {
            return false;
        }
        resetDragCorners();
        mTmpTopLeftCorner.offset(currentPipBounds.left - mDelta / 2,
                currentPipBounds.top - mDelta /  2);
        mTmpTopRightCorner.offset(currentPipBounds.right - mDelta / 2,
                currentPipBounds.top - mDelta /  2);
        mTmpBottomLeftCorner.offset(currentPipBounds.left - mDelta / 2,
                currentPipBounds.bottom - mDelta /  2);
        mTmpBottomRightCorner.offset(currentPipBounds.right - mDelta / 2,
                currentPipBounds.bottom - mDelta /  2);

        mTmpRegion.setEmpty();
        mTmpRegion.op(mTmpTopLeftCorner, Region.Op.UNION);
        mTmpRegion.op(mTmpTopRightCorner, Region.Op.UNION);
        mTmpRegion.op(mTmpBottomLeftCorner, Region.Op.UNION);
        mTmpRegion.op(mTmpBottomRightCorner, Region.Op.UNION);

        return mTmpRegion.contains(x, y);
    }

    private void setCtrlType(int x, int y) {
        final Rect currentPipBounds = mMotionHelper.getBounds();

        Rect movementBounds = mMovementBoundsSupplier.get();
        mDisplayBounds.set(movementBounds.left,
                movementBounds.top,
                movementBounds.right + currentPipBounds.width(),
                movementBounds.bottom + currentPipBounds.height());

        if (mTmpTopLeftCorner.contains(x, y) && currentPipBounds.top != mDisplayBounds.top
                && currentPipBounds.left != mDisplayBounds.left) {
            mCtrlType |= CTRL_LEFT;
            mCtrlType |= CTRL_TOP;
        }
        if (mTmpTopRightCorner.contains(x, y) && currentPipBounds.top != mDisplayBounds.top
                && currentPipBounds.right != mDisplayBounds.right) {
            mCtrlType |= CTRL_RIGHT;
            mCtrlType |= CTRL_TOP;
        }
        if (mTmpBottomRightCorner.contains(x, y)
                && currentPipBounds.bottom != mDisplayBounds.bottom
                && currentPipBounds.right != mDisplayBounds.right) {
            mCtrlType |= CTRL_RIGHT;
            mCtrlType |= CTRL_BOTTOM;
        }
        if (mTmpBottomLeftCorner.contains(x, y)
                && currentPipBounds.bottom != mDisplayBounds.bottom
                && currentPipBounds.left != mDisplayBounds.left) {
            mCtrlType |= CTRL_LEFT;
            mCtrlType |= CTRL_BOTTOM;
        }
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mLastResizeBounds.setEmpty();
            mAllowGesture = isWithinTouchRegion((int) ev.getX(), (int) ev.getY());
            if (mAllowGesture) {
                setCtrlType((int) ev.getX(), (int) ev.getY());
                mDownPoint.set(ev.getX(), ev.getY());
                mLastDownBounds.set(mMotionHelper.getBounds());
            }

        } else if (mAllowGesture) {
            switch (action) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    // We do not support multi touch for resizing via drag
                    mAllowGesture = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Capture inputs
                    float dx = Math.abs(ev.getX() - mDownPoint.x);
                    float dy = Math.abs(ev.getY() - mDownPoint.y);
                    if (!mThresholdCrossed && dx > mTouchSlop && dy > mTouchSlop) {
                        mThresholdCrossed = true;
                        mInputMonitor.pilferPointers();
                    }
                    final Rect currentPipBounds = mMotionHelper.getBounds();
                    mLastResizeBounds.set(TaskResizingAlgorithm.resizeDrag(ev.getX(), ev.getY(),
                            mDownPoint.x, mDownPoint.y, currentPipBounds, mCtrlType, mMinSize.x,
                            mMinSize.y, mMaxSize, true,
                            mLastDownBounds.width() > mLastDownBounds.height()));
                    mPipBoundsHandler.transformBoundsToAspectRatio(mLastResizeBounds);
                    mPipTaskOrganizer.scheduleUserResizePip(mLastDownBounds, mLastResizeBounds,
                            null);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mPipTaskOrganizer.scheduleFinishResizePip(mLastResizeBounds, (Rect bounds) -> {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            mMotionHelper.synchronizePinnedStackBounds();
                            mUpdateMovementBoundsRunnable.run();
                            mCtrlType = CTRL_NONE;
                            mAllowGesture = false;
                            mThresholdCrossed = false;
                        });
                    });
                    break;
            }
        }
    }

    void updateMaxSize(int maxX, int maxY) {
        mMaxSize.set(maxX, maxY);
    }

    void updateMinSize(int minX, int minY) {
        mMinSize.set(minX, minY);
    }

    class SysUiInputEventReceiver extends InputEventReceiver {
        SysUiInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        public void onInputEvent(InputEvent event) {
            PipResizeGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }
}
