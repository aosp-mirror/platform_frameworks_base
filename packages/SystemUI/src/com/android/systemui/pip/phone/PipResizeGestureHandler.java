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
import android.os.Looper;
import android.provider.DeviceConfig;
import android.util.DisplayMetrics;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import com.android.internal.policy.TaskResizingAlgorithm;
import com.android.systemui.R;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.util.DeviceConfigProxy;

import java.util.concurrent.Executor;

/**
 * Helper on top of PipTouchHandler that handles inputs OUTSIDE of the PIP window, which is used to
 * trigger dynamic resize.
 */
public class PipResizeGestureHandler {

    private static final String TAG = "PipResizeGestureHandler";

    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipTouchHandler mPipTouchHandler;
    private final PipMotionHelper mMotionHelper;
    private final int mDisplayId;
    private final Executor mMainExecutor;
    private final Region mTmpRegion = new Region();

    private final PointF mDownPoint = new PointF();
    private final Point mMaxSize = new Point();
    private final Point mMinSize = new Point();
    private final Rect mLastResizeBounds = new Rect();
    private final Rect mLastDownBounds = new Rect();
    private final Rect mTmpBounds = new Rect();
    private final int mDelta;

    private boolean mAllowGesture = false;
    private boolean mIsAttached;
    private boolean mIsEnabled;
    private boolean mEnablePipResize;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;
    private PipTaskOrganizer mPipTaskOrganizer;

    private int mCtrlType;

    public PipResizeGestureHandler(Context context, PipBoundsHandler pipBoundsHandler,
            PipTouchHandler pipTouchHandler, PipMotionHelper motionHelper,
            DeviceConfigProxy deviceConfig, PipTaskOrganizer pipTaskOrganizer) {
        final Resources res = context.getResources();
        context.getDisplay().getMetrics(mDisplayMetrics);
        mDisplayId = context.getDisplayId();
        mMainExecutor = context.getMainExecutor();
        mPipBoundsHandler = pipBoundsHandler;
        mPipTouchHandler = pipTouchHandler;
        mMotionHelper = motionHelper;
        mPipTaskOrganizer = pipTaskOrganizer;

        context.getDisplay().getRealSize(mMaxSize);
        mDelta = res.getDimensionPixelSize(R.dimen.pip_resize_edge_size);

        mEnablePipResize = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_USER_RESIZE,
                /* defaultValue = */ true);
        deviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI, mMainExecutor,
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        if (properties.getKeyset().contains(PIP_USER_RESIZE)) {
                            mEnablePipResize = properties.getBoolean(
                                    PIP_USER_RESIZE, /* defaultValue = */ true);
                        }
                    }
                });
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
        boolean isEnabled = mIsAttached && mEnablePipResize;
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

    private boolean isWithinTouchRegion(int x, int y) {
        final Rect currentPipBounds = mMotionHelper.getBounds();
        if (currentPipBounds == null) {
            return false;
        }

        mTmpBounds.set(currentPipBounds);
        mTmpBounds.inset(-mDelta, -mDelta);

        mTmpRegion.set(mTmpBounds);
        mTmpRegion.op(currentPipBounds, Region.Op.DIFFERENCE);

        if (mTmpRegion.contains(x, y)) {
            if (x < currentPipBounds.left) {
                mCtrlType |= CTRL_LEFT;
            }
            if (x > currentPipBounds.right) {
                mCtrlType |= CTRL_RIGHT;
            }
            if (y < currentPipBounds.top) {
                mCtrlType |= CTRL_TOP;
            }
            if (y > currentPipBounds.bottom) {
                mCtrlType |= CTRL_BOTTOM;
            }
            return true;
        }
        return false;
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mLastResizeBounds.setEmpty();
            mAllowGesture = isWithinTouchRegion((int) ev.getX(), (int) ev.getY());
            if (mAllowGesture) {
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
                    mInputMonitor.pilferPointers();
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
                    mPipTaskOrganizer.scheduleFinishResizePip(mLastResizeBounds);
                    mMotionHelper.synchronizePinnedStackBounds();
                    mCtrlType = CTRL_NONE;
                    mAllowGesture = false;
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
