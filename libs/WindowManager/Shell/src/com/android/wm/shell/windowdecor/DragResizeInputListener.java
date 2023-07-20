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
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

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
import android.view.ViewConfiguration;
import android.view.WindowManagerGlobal;

import com.android.internal.view.BaseIWindow;

/**
 * An input event listener registered to InputDispatcher to receive input events on task edges and
 * and corners. Converts them to drag resize requests.
 * Task edges are for resizing with a mouse.
 * Task corners are for resizing with touch input.
 */
// TODO(b/251270585): investigate how to pass taps in corners to the tasks
class DragResizeInputListener implements AutoCloseable {
    private static final String TAG = "DragResizeInputListener";

    private final IWindowSession mWindowSession = WindowManagerGlobal.getWindowSession();
    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final InputManager mInputManager;

    private final int mDisplayId;
    private final BaseIWindow mFakeWindow;
    private final IBinder mFocusGrantToken;
    private final SurfaceControl mDecorationSurface;
    private final InputChannel mInputChannel;
    private final TaskResizeInputEventReceiver mInputEventReceiver;
    private final com.android.wm.shell.windowdecor.DragResizeCallback mCallback;

    private int mWidth;
    private int mHeight;
    private int mResizeHandleThickness;
    private int mCornerSize;

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
            SurfaceControl decorationSurface,
            DragResizeCallback callback) {
        mInputManager = context.getSystemService(InputManager.class);
        mHandler = handler;
        mChoreographer = choreographer;
        mDisplayId = displayId;
        mDecorationSurface = decorationSurface;
        // Use a fake window as the backing surface is a container layer and we don't want to create
        // a buffer layer for it so we can't use ViewRootImpl.
        mFakeWindow = new BaseIWindow();
        mFakeWindow.setSession(mWindowSession);
        mFocusGrantToken = new Binder();
        mInputChannel = new InputChannel();
        try {
            mWindowSession.grantInputChannel(
                    mDisplayId,
                    mDecorationSurface,
                    mFakeWindow,
                    null /* hostInputToken */,
                    FLAG_NOT_FOCUSABLE,
                    PRIVATE_FLAG_TRUSTED_OVERLAY,
                    TYPE_APPLICATION,
                    mFocusGrantToken,
                    TAG + " of " + decorationSurface.toString(),
                    mInputChannel);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        mInputEventReceiver = new TaskResizeInputEventReceiver(
                mInputChannel, mHandler, mChoreographer);
        mCallback = callback;
        mDragDetector = new DragDetector(ViewConfiguration.get(context).getScaledTouchSlop());
    }

    /**
     * Updates geometry of this drag resize handler. Needs to be called every time there is a size
     * change to notify the input event receiver it's ready to take the next input event. Otherwise
     * it'll keep batching move events and the drag resize process is stalled.
     *
     * This is also used to update the touch regions of this handler every event dispatched here is
     * a potential resize request.
     *
     * @param width The width of the drag resize handler in pixels, including resize handle
     *              thickness. That is task width + 2 * resize handle thickness.
     * @param height The height of the drag resize handler in pixels, including resize handle
     *               thickness. That is task height + 2 * resize handle thickness.
     * @param resizeHandleThickness The thickness of the resize handle in pixels.
     * @param cornerSize The size of the resize handle centered in each corner.
     * @param touchSlop The distance in pixels user has to drag with touch for it to register as
     *                  a resize action.
     */
    void setGeometry(int width, int height, int resizeHandleThickness, int cornerSize,
            int touchSlop) {
        if (mWidth == width && mHeight == height
                && mResizeHandleThickness == resizeHandleThickness
                && mCornerSize == cornerSize) {
            return;
        }

        mWidth = width;
        mHeight = height;
        mResizeHandleThickness = resizeHandleThickness;
        mCornerSize = cornerSize;
        mDragDetector.setTouchSlop(touchSlop);

        Region touchRegion = new Region();
        final Rect topInputBounds = new Rect(0, 0, mWidth, mResizeHandleThickness);
        touchRegion.union(topInputBounds);

        final Rect leftInputBounds = new Rect(0, mResizeHandleThickness,
                mResizeHandleThickness, mHeight - mResizeHandleThickness);
        touchRegion.union(leftInputBounds);

        final Rect rightInputBounds = new Rect(
                mWidth - mResizeHandleThickness, mResizeHandleThickness,
                mWidth, mHeight - mResizeHandleThickness);
        touchRegion.union(rightInputBounds);

        final Rect bottomInputBounds = new Rect(0, mHeight - mResizeHandleThickness,
                mWidth, mHeight);
        touchRegion.union(bottomInputBounds);

        // Set up touch areas in each corner.
        int cornerRadius = mCornerSize / 2;
        mLeftTopCornerBounds = new Rect(
                mResizeHandleThickness - cornerRadius,
                mResizeHandleThickness - cornerRadius,
                mResizeHandleThickness + cornerRadius,
                mResizeHandleThickness + cornerRadius
        );
        touchRegion.union(mLeftTopCornerBounds);

        mRightTopCornerBounds = new Rect(
                mWidth - mResizeHandleThickness - cornerRadius,
                mResizeHandleThickness - cornerRadius,
                mWidth - mResizeHandleThickness + cornerRadius,
                mResizeHandleThickness + cornerRadius
        );
        touchRegion.union(mRightTopCornerBounds);

        mLeftBottomCornerBounds = new Rect(
                mResizeHandleThickness - cornerRadius,
                mHeight - mResizeHandleThickness - cornerRadius,
                mResizeHandleThickness + cornerRadius,
                mHeight - mResizeHandleThickness + cornerRadius
        );
        touchRegion.union(mLeftBottomCornerBounds);

        mRightBottomCornerBounds = new Rect(
                mWidth - mResizeHandleThickness - cornerRadius,
                mHeight - mResizeHandleThickness - cornerRadius,
                mWidth - mResizeHandleThickness + cornerRadius,
                mHeight - mResizeHandleThickness + cornerRadius
        );
        touchRegion.union(mRightBottomCornerBounds);

        try {
            mWindowSession.updateInputChannel(
                    mInputChannel.getToken(),
                    mDisplayId,
                    mDecorationSurface,
                    FLAG_NOT_FOCUSABLE,
                    PRIVATE_FLAG_TRUSTED_OVERLAY,
                    touchRegion);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    @Override
    public void close() {
        mInputChannel.dispose();
        try {
            mWindowSession.remove(mFakeWindow);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private class TaskResizeInputEventReceiver extends InputEventReceiver {
        private final Choreographer mChoreographer;
        private final Runnable mConsumeBatchEventRunnable;
        private boolean mConsumeBatchEventScheduled;
        private boolean mShouldHandleEvents;
        private boolean mDragging;

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

            MotionEvent e = (MotionEvent) inputEvent;
            boolean result = false;
            // Check if this is a touch event vs mouse event.
            // Touch events are tracked in four corners. Other events are tracked in resize edges.
            boolean isTouch = (e.getSource() & SOURCE_TOUCHSCREEN) == SOURCE_TOUCHSCREEN;
            if (isTouch) {
                mDragging = mDragDetector.detectDragEvent(e);
            }
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
                        mDragPointerId = e.getPointerId(0);
                        float rawX = e.getRawX(0);
                        float rawY = e.getRawY(0);
                        int ctrlType = calculateCtrlType(isTouch, x, y);
                        mCallback.onDragResizeStart(ctrlType, rawX, rawY);
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
                    if (!isTouch) {
                        // For all other types allow immediate dragging.
                        mDragging = true;
                    }
                    if (mDragging) {
                        mCallback.onDragResizeMove(rawX, rawY);
                        result = true;
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (mShouldHandleEvents && mDragging) {
                        int dragPointerIndex = e.findPointerIndex(mDragPointerId);
                        mCallback.onDragResizeEnd(
                                e.getRawX(dragPointerIndex), e.getRawY(dragPointerIndex));
                    }
                    mDragging = false;
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
                    mInputManager.setPointerIconType(PointerIcon.TYPE_DEFAULT);
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

        @TaskPositioner.CtrlType
        private int calculateCtrlType(boolean isTouch, float x, float y) {
            if (isTouch) {
                return calculateCornersCtrlType(x, y);
            }
            return calculateResizeHandlesCtrlType(x, y);
        }

        @TaskPositioner.CtrlType
        private int calculateResizeHandlesCtrlType(float x, float y) {
            int ctrlType = 0;
            if (x < mResizeHandleThickness) {
                ctrlType |= TaskPositioner.CTRL_TYPE_LEFT;
            }
            if (x > mWidth - mResizeHandleThickness) {
                ctrlType |= TaskPositioner.CTRL_TYPE_RIGHT;
            }
            if (y < mResizeHandleThickness) {
                ctrlType |= TaskPositioner.CTRL_TYPE_TOP;
            }
            if (y > mHeight - mResizeHandleThickness) {
                ctrlType |= TaskPositioner.CTRL_TYPE_BOTTOM;
            }
            return ctrlType;
        }

        @TaskPositioner.CtrlType
        private int calculateCornersCtrlType(float x, float y) {
            int xi = (int) x;
            int yi = (int) y;
            if (mLeftTopCornerBounds.contains(xi, yi)) {
                return TaskPositioner.CTRL_TYPE_LEFT | TaskPositioner.CTRL_TYPE_TOP;
            }
            if (mLeftBottomCornerBounds.contains(xi, yi)) {
                return TaskPositioner.CTRL_TYPE_LEFT | TaskPositioner.CTRL_TYPE_BOTTOM;
            }
            if (mRightTopCornerBounds.contains(xi, yi)) {
                return TaskPositioner.CTRL_TYPE_RIGHT | TaskPositioner.CTRL_TYPE_TOP;
            }
            if (mRightBottomCornerBounds.contains(xi, yi)) {
                return TaskPositioner.CTRL_TYPE_RIGHT | TaskPositioner.CTRL_TYPE_BOTTOM;
            }
            return 0;
        }

        private void updateCursorType(float x, float y) {
            @TaskPositioner.CtrlType int ctrlType = calculateResizeHandlesCtrlType(x, y);

            int cursorType = PointerIcon.TYPE_DEFAULT;
            switch (ctrlType) {
                case TaskPositioner.CTRL_TYPE_LEFT:
                case TaskPositioner.CTRL_TYPE_RIGHT:
                    cursorType = PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
                    break;
                case TaskPositioner.CTRL_TYPE_TOP:
                case TaskPositioner.CTRL_TYPE_BOTTOM:
                    cursorType = PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;
                    break;
                case TaskPositioner.CTRL_TYPE_LEFT | TaskPositioner.CTRL_TYPE_TOP:
                case TaskPositioner.CTRL_TYPE_RIGHT | TaskPositioner.CTRL_TYPE_BOTTOM:
                    cursorType = PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW;
                    break;
                case TaskPositioner.CTRL_TYPE_LEFT | TaskPositioner.CTRL_TYPE_BOTTOM:
                case TaskPositioner.CTRL_TYPE_RIGHT | TaskPositioner.CTRL_TYPE_TOP:
                    cursorType = PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;
                    break;
            }
            mInputManager.setPointerIconType(cursorType);
        }
    }
}