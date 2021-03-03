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
package com.android.wm.shell.pip.phone;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.PIP_PINCH_RESIZE;
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
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.VisibleForTesting;

import com.android.internal.policy.TaskResizingAlgorithm;
import com.android.wm.shell.R;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;

import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Helper on top of PipTouchHandler that handles inputs OUTSIDE of the PIP window, which is used to
 * trigger dynamic resize.
 */
public class PipResizeGestureHandler {

    private static final String TAG = "PipResizeGestureHandler";
    private static final int PINCH_RESIZE_SNAP_DURATION = 250;
    private static final int PINCH_RESIZE_MAX_ANGLE_ROTATION = 45;
    private static final float PINCH_RESIZE_AUTO_MAX_RATIO = 0.9f;

    private final Context mContext;
    private final PipBoundsAlgorithm mPipBoundsAlgorithm;
    private final PipMotionHelper mMotionHelper;
    private final PipBoundsState mPipBoundsState;
    private final PipTaskOrganizer mPipTaskOrganizer;
    private final PhonePipMenuController mPhonePipMenuController;
    private final PipUiEventLogger mPipUiEventLogger;
    private final int mDisplayId;
    private final ShellExecutor mMainExecutor;
    private final Region mTmpRegion = new Region();

    private final PointF mDownPoint = new PointF();
    private final PointF mDownSecondaryPoint = new PointF();
    private final Point mMaxSize = new Point();
    private final Point mMinSize = new Point();
    private final Rect mLastResizeBounds = new Rect();
    private final Rect mUserResizeBounds = new Rect();
    private final Rect mLastDownBounds = new Rect();
    private final Rect mDragCornerSize = new Rect();
    private final Rect mTmpTopLeftCorner = new Rect();
    private final Rect mTmpTopRightCorner = new Rect();
    private final Rect mTmpBottomLeftCorner = new Rect();
    private final Rect mTmpBottomRightCorner = new Rect();
    private final Rect mDisplayBounds = new Rect();
    private final Function<Rect, Rect> mMovementBoundsSupplier;
    private final Runnable mUpdateMovementBoundsRunnable;

    private int mDelta;
    private float mTouchSlop;

    private boolean mAllowGesture;
    private boolean mIsAttached;
    private boolean mIsEnabled;
    private boolean mEnablePinchResize;
    private boolean mIsSysUiStateValid;
    // For drag-resize
    private boolean mThresholdCrossed;
    // For pinch-resize
    private boolean mThresholdCrossed0;
    private boolean mThresholdCrossed1;
    private boolean mUsingPinchToZoom = true;
    private float mAngle = 0;
    int mFirstIndex = -1;
    int mSecondIndex = -1;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private int mCtrlType;

    public PipResizeGestureHandler(Context context, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMotionHelper motionHelper,
            PipTaskOrganizer pipTaskOrganizer, Function<Rect, Rect> movementBoundsSupplier,
            Runnable updateMovementBoundsRunnable, PipUiEventLogger pipUiEventLogger,
            PhonePipMenuController menuActivityController, ShellExecutor mainExecutor) {
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = mainExecutor;
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipBoundsState = pipBoundsState;
        mMotionHelper = motionHelper;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMovementBoundsSupplier = movementBoundsSupplier;
        mUpdateMovementBoundsRunnable = updateMovementBoundsRunnable;
        mPhonePipMenuController = menuActivityController;
        mPipUiEventLogger = pipUiEventLogger;
    }

    public void init() {
        mContext.getDisplay().getRealSize(mMaxSize);
        reloadResources();

        mEnablePinchResize = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_PINCH_RESIZE,
                /* defaultValue = */ true);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mMainExecutor,
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        if (properties.getKeyset().contains(PIP_PINCH_RESIZE)) {
                            mEnablePinchResize = properties.getBoolean(
                                    PIP_PINCH_RESIZE, /* defaultValue = */ true);
                        }
                    }
                });
    }

    public void onConfigurationChanged() {
        reloadResources();
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI valid or not.
     */
    public void onSystemUiStateChanged(boolean isSysUiStateValid) {
        mIsSysUiStateValid = isSysUiStateValid;
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
        mUserResizeBounds.setEmpty();
        updateIsEnabled();
    }

    private void updateIsEnabled() {
        boolean isEnabled = mIsAttached;
        if (isEnabled == mIsEnabled) {
            return;
        }
        mIsEnabled = isEnabled;
        disposeInputChannel();

        if (mIsEnabled) {
            // Register input event receiver
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "pip-resize", mDisplayId);
            try {
                mMainExecutor.executeBlocking(() -> {
                    mInputEventReceiver = new PipResizeInputEventReceiver(
                            mInputMonitor.getInputChannel(), Looper.myLooper());
                });
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to create input event receiver", e);
            }
        }
    }

    private void onInputEvent(InputEvent ev) {
        // Don't allow resize when PiP is stashed.
        if (mPipBoundsState.isStashed()) {
            return;
        }

        if (ev instanceof MotionEvent) {
            if (mUsingPinchToZoom) {
                onPinchResize((MotionEvent) ev);
            } else {
                onDragCornerResize((MotionEvent) ev);
            }
        }
    }

    /**
     * Checks if there is currently an on-going gesture, either drag-resize or pinch-resize.
     */
    public boolean hasOngoingGesture() {
        return mCtrlType != CTRL_NONE || mUsingPinchToZoom;
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
    public boolean isWithinDragResizeRegion(int x, int y) {
        final Rect currentPipBounds = mPipBoundsState.getBounds();
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

    public boolean isUsingPinchToZoom() {
        return mEnablePinchResize;
    }

    public boolean willStartResizeGesture(MotionEvent ev) {
        if (isInValidSysUiState()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (isWithinDragResizeRegion((int) ev.getRawX(), (int) ev.getRawY())) {
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mEnablePinchResize && ev.getPointerCount() == 2) {
                        onPinchResize(ev);
                        mUsingPinchToZoom = true;
                        return true;
                    }
                    break;

                default:
                    break;
            }
        }
        return false;
    }

    private void setCtrlType(int x, int y) {
        final Rect currentPipBounds = mPipBoundsState.getBounds();

        Rect movementBounds = mMovementBoundsSupplier.apply(currentPipBounds);

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

    private boolean isInValidSysUiState() {
        return mIsSysUiStateValid;
    }

    private void onPinchResize(MotionEvent ev) {
        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mFirstIndex = -1;
            mSecondIndex = -1;
            finishResize();
        }

        if (ev.getPointerCount() != 2) {
            return;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (mFirstIndex == -1 && mSecondIndex == -1) {
                mFirstIndex = 0;
                mSecondIndex = 1;
                mDownPoint.set(ev.getRawX(mFirstIndex), ev.getRawY(mFirstIndex));
                mDownSecondaryPoint.set(ev.getRawX(mSecondIndex), ev.getRawY(mSecondIndex));


                mLastDownBounds.set(mPipBoundsState.getBounds());
                mLastResizeBounds.set(mLastDownBounds);
            }
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (mFirstIndex == -1 || mSecondIndex == -1) {
                return;
            }

            float x0 = ev.getRawX(mFirstIndex);
            float y0 = ev.getRawY(mFirstIndex);
            float x1 = ev.getRawX(mSecondIndex);
            float y1 = ev.getRawY(mSecondIndex);

            double hypot0 = Math.hypot(x0 - mDownPoint.x, y0 - mDownPoint.y);
            double hypot1 = Math.hypot(x1 - mDownSecondaryPoint.x, y1 - mDownSecondaryPoint.y);
            // Capture inputs
            if (hypot0 > mTouchSlop && !mThresholdCrossed0) {
                mInputMonitor.pilferPointers();
                mThresholdCrossed0 = true;
                // Reset the down to begin resizing from this point
                mDownPoint.set(x0, y0);
            }
            if (hypot1 > mTouchSlop && !mThresholdCrossed1) {
                mInputMonitor.pilferPointers();
                mThresholdCrossed1 = true;
                // Reset the down to begin resizing from this point
                mDownSecondaryPoint.set(x1, y1);
            }
            if (mThresholdCrossed0 || mThresholdCrossed1) {
                if (mPhonePipMenuController.isMenuVisible()) {
                    mPhonePipMenuController.hideMenu();
                }

                x0 = mThresholdCrossed0 ? x0 : mDownPoint.x;
                y0 = mThresholdCrossed0 ? y0 : mDownPoint.y;
                x1 = mThresholdCrossed1 ? x1 : mDownSecondaryPoint.x;
                y1 = mThresholdCrossed1 ? y1 : mDownSecondaryPoint.y;

                final Rect originalPipBounds = mPipBoundsState.getBounds();
                int focusX = (int) originalPipBounds.centerX();
                int focusY = (int) originalPipBounds.centerY();

                float down0X = mDownPoint.x;
                float down0Y = mDownPoint.y;
                float down1X = mDownSecondaryPoint.x;
                float down1Y = mDownSecondaryPoint.y;

                if (down0X > focusX && down0Y < focusY && down1X < focusX && down1Y > focusY) {
                    // Top right + Bottom left pinch to zoom.
                    mAngle = calculateRotationAngle(mLastResizeBounds.centerX(),
                            mLastResizeBounds.centerY(), x0, y0, x1, y1, true);
                } else if (down1X > focusX && down1Y < focusY
                        && down0X < focusX && down0Y > focusY) {
                    // Top right + Bottom left pinch to zoom.
                    mAngle = calculateRotationAngle(mLastResizeBounds.centerX(),
                            mLastResizeBounds.centerY(), x1, y1, x0, y0, true);
                } else if (down0X < focusX && down0Y < focusY
                        && down1X > focusX && down1Y > focusY) {
                    // Top left + bottom right pinch to zoom.
                    mAngle = calculateRotationAngle(mLastResizeBounds.centerX(),
                            mLastResizeBounds.centerY(), x0, y0, x1, y1, false);
                } else if (down1X < focusX && down1Y < focusY
                        && down0X > focusX && down0Y > focusY) {
                    // Top left + bottom right pinch to zoom.
                    mAngle = calculateRotationAngle(mLastResizeBounds.centerX(),
                            mLastResizeBounds.centerY(), x1, y1, x0, y0, false);
                }

                mLastResizeBounds.set(PipPinchResizingAlgorithm.pinchResize(x0, y0, x1, y1,
                        mDownPoint.x, mDownPoint.y, mDownSecondaryPoint.x, mDownSecondaryPoint.y,
                        originalPipBounds, mMinSize.x, mMinSize.y, mMaxSize));

                mPipTaskOrganizer.scheduleUserResizePip(mLastDownBounds, mLastResizeBounds,
                        (float) -mAngle, null);
                mPipBoundsState.setHasUserResizedPip(true);
            }
        }
    }

    private float calculateRotationAngle(int pivotX, int pivotY, float topX, float topY,
            float bottomX, float bottomY, boolean positive) {

        // The base angle is the angle formed by taking the angle between the center horizontal
        // and one of the corners.
        double baseAngle = Math.toDegrees(Math.atan2(Math.abs(mLastResizeBounds.top - pivotY),
                Math.abs(mLastResizeBounds.right - pivotX)));

        double angle0 = mThresholdCrossed0
                ? Math.toDegrees(Math.atan2(pivotY - topY, topX - pivotX)) : baseAngle;
        double angle1 = mThresholdCrossed0
                ? Math.toDegrees(Math.atan2(pivotY - bottomY, bottomX - pivotX)) : baseAngle;


        if (positive) {
            angle1 = angle1 < 0 ? 180 + angle1 : angle1 - 180;
        } else {
            angle0 = angle0 < 0 ? -angle0 - 180 : 180 - angle0;
            angle1 = -angle1;
        }

        // Calculate the percentage difference of [0, 90] compare to the base angle.
        double diff0 = (Math.max(0, Math.min(angle0, 90)) - baseAngle) / 90;
        double diff1 = (Math.max(0, Math.min(angle1, 90)) - baseAngle) / 90;

        return (float) (diff0 + diff1) / 2 * PINCH_RESIZE_MAX_ANGLE_ROTATION * (positive ? 1 : -1);
    }

    private void onDragCornerResize(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            final Rect currentPipBounds = mPipBoundsState.getBounds();
            mLastResizeBounds.setEmpty();
            mAllowGesture = isInValidSysUiState() && isWithinDragResizeRegion((int) x, (int) y);
            if (mAllowGesture) {
                setCtrlType((int) x, (int) y);
                mDownPoint.set(x, y);
                mLastDownBounds.set(mPipBoundsState.getBounds());
            }
            if (!currentPipBounds.contains((int) ev.getX(), (int) ev.getY())
                    && mPhonePipMenuController.isMenuVisible()) {
                mPhonePipMenuController.hideMenu();
            }

        } else if (mAllowGesture) {
            switch (action) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    // We do not support multi touch for resizing via drag
                    mAllowGesture = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Capture inputs
                    if (!mThresholdCrossed
                            && Math.hypot(x - mDownPoint.x, y - mDownPoint.y) > mTouchSlop) {
                        mThresholdCrossed = true;
                        // Reset the down to begin resizing from this point
                        mDownPoint.set(x, y);
                        mInputMonitor.pilferPointers();
                    }
                    if (mThresholdCrossed) {
                        if (mPhonePipMenuController.isMenuVisible()) {
                            mPhonePipMenuController.hideMenu(false /* animate */,
                                    false /* resize */);
                        }
                        final Rect currentPipBounds = mPipBoundsState.getBounds();
                        mLastResizeBounds.set(TaskResizingAlgorithm.resizeDrag(x, y,
                                mDownPoint.x, mDownPoint.y, currentPipBounds, mCtrlType, mMinSize.x,
                                mMinSize.y, mMaxSize, true,
                                mLastDownBounds.width() > mLastDownBounds.height()));
                        mPipBoundsAlgorithm.transformBoundsToAspectRatio(mLastResizeBounds,
                                mPipBoundsState.getAspectRatio(), false /* useCurrentMinEdgeSize */,
                                true /* useCurrentSize */);
                        mPipTaskOrganizer.scheduleUserResizePip(mLastDownBounds, mLastResizeBounds,
                                null);
                        mPipBoundsState.setHasUserResizedPip(true);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    finishResize();
                    break;
            }
        }
    }

    private void finishResize() {
        if (!mLastResizeBounds.isEmpty()) {
            final Consumer<Rect> callback = (rect) -> {
                mUserResizeBounds.set(mLastResizeBounds);
                mMotionHelper.synchronizePinnedStackBounds();
                mUpdateMovementBoundsRunnable.run();
                resetState();
            };

            // Pinch-to-resize needs to re-calculate snap fraction and animate to the snapped
            // position correctly. Drag-resize does not need to move, so just finalize resize.
            if (mUsingPinchToZoom) {
                final Rect startBounds = new Rect(mLastResizeBounds);
                // If user resize is pretty close to max size, just auto resize to max.
                if (mLastResizeBounds.width() >= PINCH_RESIZE_AUTO_MAX_RATIO * mMaxSize.x
                        || mLastResizeBounds.height() >= PINCH_RESIZE_AUTO_MAX_RATIO * mMaxSize.y) {
                    mLastResizeBounds.set(0, 0, mMaxSize.x, mMaxSize.y);
                }
                final float snapFraction = mPipBoundsAlgorithm.getSnapFraction(mLastResizeBounds);
                mPipBoundsAlgorithm.applySnapFraction(mLastResizeBounds, snapFraction);
                mPipTaskOrganizer.scheduleAnimateResizePip(startBounds, mLastResizeBounds,
                        PINCH_RESIZE_SNAP_DURATION, -mAngle, callback);
            } else {
                mPipTaskOrganizer.scheduleFinishResizePip(mLastResizeBounds,
                        PipAnimationController.TRANSITION_DIRECTION_USER_RESIZE, callback);
            }
            mPipUiEventLogger.log(
                    PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_RESIZE);
        } else {
            resetState();
        }
    }

    private void resetState() {
        mCtrlType = CTRL_NONE;
        mAngle = 0;
        mUsingPinchToZoom = false;
        mAllowGesture = false;
        mThresholdCrossed = false;
    }

    void setUserResizeBounds(Rect bounds) {
        mUserResizeBounds.set(bounds);
    }

    void invalidateUserResizeBounds() {
        mUserResizeBounds.setEmpty();
    }

    Rect getUserResizeBounds() {
        return mUserResizeBounds;
    }

    @VisibleForTesting public void updateMaxSize(int maxX, int maxY) {
        mMaxSize.set(maxX, maxY);
    }

    @VisibleForTesting public void updateMinSize(int minX, int minY) {
        mMinSize.set(minX, minY);
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mAllowGesture=" + mAllowGesture);
        pw.println(innerPrefix + "mIsAttached=" + mIsAttached);
        pw.println(innerPrefix + "mIsEnabled=" + mIsEnabled);
        pw.println(innerPrefix + "mEnablePinchResize=" + mEnablePinchResize);
        pw.println(innerPrefix + "mThresholdCrossed=" + mThresholdCrossed);
    }

    class PipResizeInputEventReceiver extends BatchedInputEventReceiver {
        PipResizeInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper, Choreographer.getSfInstance());
        }

        public void onInputEvent(InputEvent event) {
            PipResizeGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }
}
