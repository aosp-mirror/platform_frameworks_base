/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SPY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;

import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_LEFT;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT;
import static com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_TOP;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Choreographer;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManagerGlobal;

import com.android.internal.view.BaseIWindow;

import java.util.function.Supplier;

/**
 * An input event listener registered to InputDispatcher to receive input events on task edges and
 * and corners. Converts them to drag resize requests.
 * Task edges are for resizing with a mouse.
 * Task corners are for resizing with touch input.
 */
class DragResizeInputListener implements AutoCloseable {
    private static final String TAG = "DragResizeInputListener";
    private final IWindowSession mWindowSession = WindowManagerGlobal.getWindowSession();
    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final InputManager mInputManager;
    private final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;

    private final int mDisplayId;
    private final BaseIWindow mFakeWindow;
    private final IBinder mFocusGrantToken;
    private final SurfaceControl mDecorationSurface;
    private final InputChannel mInputChannel;
    private final TaskResizeInputEventReceiver mInputEventReceiver;
    private final DragPositioningCallback mCallback;

    private final SurfaceControl mInputSinkSurface;
    private final BaseIWindow mFakeSinkWindow;
    private final InputChannel mSinkInputChannel;

    private int mTaskWidth;
    private int mTaskHeight;
    private int mResizeHandleThickness;
    private int mCornerSize;
    private int mTaskCornerRadius;

    private Rect mLeftTopCornerBounds;
    private Rect mRightTopCornerBounds;
    private Rect mLeftBottomCornerBounds;
    private Rect mRightBottomCornerBounds;

    private int mDragPointerId = -1;
    private DragDetector mDragDetector;

    DragResizeInputListener(
            Context context,
            Handler handler,
            Choreographer choreographer,
            int displayId,
            int taskCornerRadius,
            SurfaceControl decorationSurface,
            DragPositioningCallback callback,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier) {
        mInputManager = context.getSystemService(InputManager.class);
        mHandler = handler;
        mChoreographer = choreographer;
        mSurfaceControlTransactionSupplier = surfaceControlTransactionSupplier;
        mDisplayId = displayId;
        mTaskCornerRadius = taskCornerRadius;
        mDecorationSurface = decorationSurface;
        // Use a fake window as the backing surface is a container layer, and we don't want to
        // create a buffer layer for it, so we can't use ViewRootImpl.
        mFakeWindow = new BaseIWindow();
        mFakeWindow.setSession(mWindowSession);
        mFocusGrantToken = new Binder();
        mInputChannel = new InputChannel();
        try {
            mWindowSession.grantInputChannel(
                    mDisplayId,
                    mDecorationSurface,
                    mFakeWindow.asBinder(),
                    null /* hostInputToken */,
                    FLAG_NOT_FOCUSABLE,
                    PRIVATE_FLAG_TRUSTED_OVERLAY,
                    INPUT_FEATURE_SPY,
                    TYPE_APPLICATION,
                    null /* windowToken */,
                    mFocusGrantToken,
                    TAG + " of " + decorationSurface.toString(),
                    mInputChannel);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        mInputEventReceiver = new TaskResizeInputEventReceiver(
                mInputChannel, mHandler, mChoreographer);
        mCallback = callback;
        mDragDetector = new DragDetector(mInputEventReceiver);
        mDragDetector.setTouchSlop(ViewConfiguration.get(context).getScaledTouchSlop());

        mInputSinkSurface = surfaceControlBuilderSupplier.get()
                .setName("TaskInputSink of " + decorationSurface)
                .setContainerLayer()
                .setParent(mDecorationSurface)
                .build();
        mSurfaceControlTransactionSupplier.get()
                .setLayer(mInputSinkSurface, WindowDecoration.INPUT_SINK_Z_ORDER)
                .show(mInputSinkSurface)
                .apply();
        mFakeSinkWindow = new BaseIWindow();
        mSinkInputChannel = new InputChannel();
        try {
            mWindowSession.grantInputChannel(
                    mDisplayId,
                    mInputSinkSurface,
                    mFakeSinkWindow.asBinder(),
                    null /* hostInputToken */,
                    FLAG_NOT_FOCUSABLE,
                    0 /* privateFlags */,
                    INPUT_FEATURE_NO_INPUT_CHANNEL,
                    TYPE_INPUT_CONSUMER,
                    null /* windowToken */,
                    mFocusGrantToken,
                    "TaskInputSink of " + decorationSurface,
                    mSinkInputChannel);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the geometry (the touch region) of this drag resize handler.
     *
     * @param taskWidth The width of the task.
     * @param taskHeight The height of the task.
     * @param resizeHandleThickness The thickness of the resize handle in pixels.
     * @param cornerSize The size of the resize handle centered in each corner.
     * @param touchSlop The distance in pixels user has to drag with touch for it to register as
     *                  a resize action.
     * @return whether the geometry has changed or not
     */
    boolean setGeometry(int taskWidth, int taskHeight, int resizeHandleThickness, int cornerSize,
            int touchSlop) {
        if (mTaskWidth == taskWidth && mTaskHeight == taskHeight
                && mResizeHandleThickness == resizeHandleThickness
                && mCornerSize == cornerSize) {
            return false;
        }

        mTaskWidth = taskWidth;
        mTaskHeight = taskHeight;
        mResizeHandleThickness = resizeHandleThickness;
        mCornerSize = cornerSize;
        mDragDetector.setTouchSlop(touchSlop);

        Region touchRegion = new Region();
        final Rect topInputBounds = new Rect(
                -mResizeHandleThickness,
                -mResizeHandleThickness,
                mTaskWidth + mResizeHandleThickness,
                0);
        touchRegion.union(topInputBounds);

        final Rect leftInputBounds = new Rect(
                -mResizeHandleThickness,
                0,
                0,
                mTaskHeight);
        touchRegion.union(leftInputBounds);

        final Rect rightInputBounds = new Rect(
                mTaskWidth,
                0,
                mTaskWidth + mResizeHandleThickness,
                mTaskHeight);
        touchRegion.union(rightInputBounds);

        final Rect bottomInputBounds = new Rect(
                -mResizeHandleThickness,
                mTaskHeight,
                mTaskWidth + mResizeHandleThickness,
                mTaskHeight + mResizeHandleThickness);
        touchRegion.union(bottomInputBounds);

        // Set up touch areas in each corner.
        int cornerRadius = mCornerSize / 2;
        mLeftTopCornerBounds = new Rect(
                -cornerRadius,
                -cornerRadius,
                cornerRadius,
                cornerRadius);
        touchRegion.union(mLeftTopCornerBounds);

        mRightTopCornerBounds = new Rect(
                mTaskWidth - cornerRadius,
                -cornerRadius,
                mTaskWidth + cornerRadius,
                cornerRadius);
        touchRegion.union(mRightTopCornerBounds);

        mLeftBottomCornerBounds = new Rect(
                -cornerRadius,
                mTaskHeight - cornerRadius,
                cornerRadius,
                mTaskHeight + cornerRadius);
        touchRegion.union(mLeftBottomCornerBounds);

        mRightBottomCornerBounds = new Rect(
                mTaskWidth - cornerRadius,
                mTaskHeight - cornerRadius,
                mTaskWidth + cornerRadius,
                mTaskHeight + cornerRadius);
        touchRegion.union(mRightBottomCornerBounds);

        try {
            mWindowSession.updateInputChannel(
                    mInputChannel.getToken(),
                    mDisplayId,
                    mDecorationSurface,
                    FLAG_NOT_FOCUSABLE,
                    PRIVATE_FLAG_TRUSTED_OVERLAY,
                    INPUT_FEATURE_SPY,
                    touchRegion);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        mSurfaceControlTransactionSupplier.get()
                .setWindowCrop(mInputSinkSurface, mTaskWidth, mTaskHeight)
                .apply();
        // The touch region of the TaskInputSink should be the touch region of this
        // DragResizeInputHandler minus the task bounds. Pilfering events isn't enough to prevent
        // input windows from handling down events, which will bring tasks in the back to front.
        //
        // Note not the entire touch region responds to both mouse and touchscreen events.
        // Therefore, in the region that only responds to one of them, it would be a no-op to
        // perform a gesture in the other type of events. We currently only have a mouse-only region
        // out of the task bounds, and due to the roughness of touchscreen events, it's not a severe
        // issue. However, were there touchscreen-only a region out of the task bounds, mouse
        // gestures will become no-op in that region, even though the mouse gestures may appear to
        // be performed on the input window behind the resize handle.
        touchRegion.op(0, 0, mTaskWidth, mTaskHeight, Region.Op.DIFFERENCE);
        try {
            mWindowSession.updateInputChannel(
                    mSinkInputChannel.getToken(),
                    mDisplayId,
                    mInputSinkSurface,
                    FLAG_NOT_FOCUSABLE,
                    0 /* privateFlags */,
                    INPUT_FEATURE_NO_INPUT_CHANNEL,
                    touchRegion);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return true;
    }

    /**
     * Generate a Region that encapsulates all 4 corner handles
     */
    Region getCornersRegion() {
        Region region = new Region();
        region.union(mLeftTopCornerBounds);
        region.union(mLeftBottomCornerBounds);
        region.union(mRightTopCornerBounds);
        region.union(mRightBottomCornerBounds);
        return region;
    }

    @Override
    public void close() {
        mInputEventReceiver.dispose();
        mInputChannel.dispose();
        try {
            mWindowSession.remove(mFakeWindow.asBinder());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        mSinkInputChannel.dispose();
        try {
            mWindowSession.remove(mFakeSinkWindow.asBinder());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        mSurfaceControlTransactionSupplier.get()
                .remove(mInputSinkSurface)
                .apply();
    }

    private class TaskResizeInputEventReceiver extends InputEventReceiver
            implements DragDetector.MotionEventHandler {
        private final Choreographer mChoreographer;
        private final Runnable mConsumeBatchEventRunnable;
        private boolean mConsumeBatchEventScheduled;
        private boolean mShouldHandleEvents;
        private int mLastCursorType = PointerIcon.TYPE_DEFAULT;

        private TaskResizeInputEventReceiver(
                InputChannel inputChannel, Handler handler, Choreographer choreographer) {
            super(inputChannel, handler.getLooper());
            mChoreographer = choreographer;

            mConsumeBatchEventRunnable = () -> {
                mConsumeBatchEventScheduled = false;
                if (consumeBatchedInputEvents(mChoreographer.getFrameTimeNanos())) {
                    // If we consumed a batch here, we want to go ahead and schedule the
                    // consumption of batched input events on the next frame. Otherwise, we would
                    // wait until we have more input events pending and might get starved by other
                    // things occurring in the process.
                    scheduleConsumeBatchEvent();
                }
            };
        }

        @Override
        public void onBatchedInputEventPending(int source) {
            scheduleConsumeBatchEvent();
        }

        private void scheduleConsumeBatchEvent() {
            if (mConsumeBatchEventScheduled) {
                return;
            }
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_INPUT, mConsumeBatchEventRunnable, null);
            mConsumeBatchEventScheduled = true;
        }

        @Override
        public void onInputEvent(InputEvent inputEvent) {
            finishInputEvent(inputEvent, handleInputEvent(inputEvent));
        }

        private boolean handleInputEvent(InputEvent inputEvent) {
            if (!(inputEvent instanceof MotionEvent)) {
                return false;
            }
            return mDragDetector.onMotionEvent((MotionEvent) inputEvent);
        }

        @Override
        public boolean handleMotionEvent(View v, MotionEvent e) {
            boolean result = false;
            // Check if this is a touch event vs mouse event.
            // Touch events are tracked in four corners. Other events are tracked in resize edges.
            boolean isTouch = (e.getSource() & SOURCE_TOUCHSCREEN) == SOURCE_TOUCHSCREEN;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = e.getX(0);
                    float y = e.getY(0);
                    if (isTouch) {
                        mShouldHandleEvents = isInCornerBounds(x, y);
                    } else {
                        mShouldHandleEvents = isInResizeHandleBounds(x, y);
                    }
                    if (mShouldHandleEvents) {
                        mInputManager.pilferPointers(mInputChannel.getToken());

                        mDragPointerId = e.getPointerId(0);
                        float rawX = e.getRawX(0);
                        float rawY = e.getRawY(0);
                        int ctrlType = calculateCtrlType(isTouch, x, y);
                        mCallback.onDragPositioningStart(ctrlType, rawX, rawY);
                        result = true;
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!mShouldHandleEvents) {
                        break;
                    }
                    int dragPointerIndex = e.findPointerIndex(mDragPointerId);
                    float rawX = e.getRawX(dragPointerIndex);
                    float rawY = e.getRawY(dragPointerIndex);
                    mCallback.onDragPositioningMove(rawX, rawY);
                    result = true;
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (mShouldHandleEvents) {
                        int dragPointerIndex = e.findPointerIndex(mDragPointerId);
                        mCallback.onDragPositioningEnd(
                                e.getRawX(dragPointerIndex), e.getRawY(dragPointerIndex));
                    }
                    mShouldHandleEvents = false;
                    mDragPointerId = -1;
                    result = true;
                    break;
                }
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    updateCursorType(e.getXCursorPosition(), e.getYCursorPosition());
                    result = true;
                    break;
                }
                case MotionEvent.ACTION_HOVER_EXIT:
                    result = true;
                    break;
            }
            return result;
        }

        private boolean isInCornerBounds(float xf, float yf) {
            return calculateCornersCtrlType(xf, yf) != 0;
        }

        private boolean isInResizeHandleBounds(float x, float y) {
            return calculateResizeHandlesCtrlType(x, y) != 0;
        }

        @DragPositioningCallback.CtrlType
        private int calculateCtrlType(boolean isTouch, float x, float y) {
            if (isTouch) {
                return calculateCornersCtrlType(x, y);
            }
            return calculateResizeHandlesCtrlType(x, y);
        }

        @DragPositioningCallback.CtrlType
        private int calculateResizeHandlesCtrlType(float x, float y) {
            int ctrlType = 0;
            // mTaskCornerRadius is only used in comparing with corner regions. Comparisons with
            // sides will use the bounds specified in setGeometry and not go into task bounds.
            if (x < mTaskCornerRadius) {
                ctrlType |= CTRL_TYPE_LEFT;
            }
            if (x > mTaskWidth - mTaskCornerRadius) {
                ctrlType |= CTRL_TYPE_RIGHT;
            }
            if (y < mTaskCornerRadius) {
                ctrlType |= CTRL_TYPE_TOP;
            }
            if (y > mTaskHeight - mTaskCornerRadius) {
                ctrlType |= CTRL_TYPE_BOTTOM;
            }
            // Check distances from the center if it's in one of four corners.
            if ((ctrlType & (CTRL_TYPE_LEFT | CTRL_TYPE_RIGHT)) != 0
                    && (ctrlType & (CTRL_TYPE_TOP | CTRL_TYPE_BOTTOM)) != 0) {
                return checkDistanceFromCenter(ctrlType, x, y);
            }
            // Otherwise, we should make sure we don't resize tasks inside task bounds.
            return (x < 0 || y < 0 || x >= mTaskWidth || y >= mTaskHeight) ? ctrlType : 0;
        }

        // If corner input is not within appropriate distance of corner radius, do not use it.
        // If input is not on a corner or is within valid distance, return ctrlType.
        @DragPositioningCallback.CtrlType
        private int checkDistanceFromCenter(@DragPositioningCallback.CtrlType int ctrlType,
                float x, float y) {
            int centerX;
            int centerY;

            // Determine center of rounded corner circle; this is simply the corner if radius is 0.
            switch (ctrlType) {
                case CTRL_TYPE_LEFT | CTRL_TYPE_TOP: {
                    centerX = mTaskCornerRadius;
                    centerY = mTaskCornerRadius;
                    break;
                }
                case CTRL_TYPE_LEFT | CTRL_TYPE_BOTTOM: {
                    centerX = mTaskCornerRadius;
                    centerY = mTaskHeight - mTaskCornerRadius;
                    break;
                }
                case CTRL_TYPE_RIGHT | CTRL_TYPE_TOP: {
                    centerX = mTaskWidth - mTaskCornerRadius;
                    centerY = mTaskCornerRadius;
                    break;
                }
                case CTRL_TYPE_RIGHT | CTRL_TYPE_BOTTOM: {
                    centerX = mTaskWidth - mTaskCornerRadius;
                    centerY = mTaskHeight - mTaskCornerRadius;
                    break;
                }
                default: {
                    throw new IllegalArgumentException("ctrlType should be complex, but it's 0x"
                            + Integer.toHexString(ctrlType));
                }
            }
            double distanceFromCenter = Math.hypot(x - centerX, y - centerY);

            if (distanceFromCenter < mTaskCornerRadius + mResizeHandleThickness
                    && distanceFromCenter >= mTaskCornerRadius) {
                return ctrlType;
            }
            return 0;
        }

        @DragPositioningCallback.CtrlType
        private int calculateCornersCtrlType(float x, float y) {
            int xi = (int) x;
            int yi = (int) y;
            if (mLeftTopCornerBounds.contains(xi, yi)) {
                return CTRL_TYPE_LEFT | CTRL_TYPE_TOP;
            }
            if (mLeftBottomCornerBounds.contains(xi, yi)) {
                return CTRL_TYPE_LEFT | CTRL_TYPE_BOTTOM;
            }
            if (mRightTopCornerBounds.contains(xi, yi)) {
                return CTRL_TYPE_RIGHT | CTRL_TYPE_TOP;
            }
            if (mRightBottomCornerBounds.contains(xi, yi)) {
                return CTRL_TYPE_RIGHT | CTRL_TYPE_BOTTOM;
            }
            return 0;
        }

        private void updateCursorType(float x, float y) {
            @DragPositioningCallback.CtrlType int ctrlType = calculateResizeHandlesCtrlType(x, y);

            int cursorType = PointerIcon.TYPE_DEFAULT;
            switch (ctrlType) {
                case CTRL_TYPE_LEFT:
                case CTRL_TYPE_RIGHT:
                    cursorType = PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
                    break;
                case CTRL_TYPE_TOP:
                case CTRL_TYPE_BOTTOM:
                    cursorType = PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;
                    break;
                case CTRL_TYPE_LEFT | CTRL_TYPE_TOP:
                case CTRL_TYPE_RIGHT | CTRL_TYPE_BOTTOM:
                    cursorType = PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW;
                    break;
                case CTRL_TYPE_LEFT | CTRL_TYPE_BOTTOM:
                case CTRL_TYPE_RIGHT | CTRL_TYPE_TOP:
                    cursorType = PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;
                    break;
            }
            // Only update the cursor type to default once so that views behind the decor container
            // layer that aren't in the active resizing regions have chances to update the cursor
            // type. We would like to enforce the cursor type by setting the cursor type multilple
            // times in active regions because we shouldn't allow the views behind to change it, as
            // we'll pilfer the gesture initiated in this area. This is necessary because 1) we
            // should allow the views behind regions only for touches to set the cursor type; and 2)
            // there is a small region out of each rounded corner that's inside the task bounds,
            // where views in the task can receive input events because we can't set touch regions
            // of input sinks to have rounded corners.
            if (mLastCursorType != cursorType || cursorType != PointerIcon.TYPE_DEFAULT) {
                mInputManager.setPointerIconType(cursorType);
                mLastCursorType = cursorType;
            }
        }
    }
}