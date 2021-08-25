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
import android.os.Looper;
import android.os.RemoteException;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;

import com.android.internal.view.BaseIWindow;

/**
 * An input event listener registered to InputDispatcher to receive input events on task edges and
 * convert them to drag resize requests.
 */
class DragResizeInputListener implements AutoCloseable {
    private static final String TAG = "DragResizeInputListener";

    private final IWindowSession mWindowSession = WindowManagerGlobal.getWindowSession();
    private final Handler mHandler;
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

    private int mDragPointerId = -1;

    DragResizeInputListener(
            Context context,
            Handler handler,
            int displayId,
            SurfaceControl decorationSurface,
            DragResizeCallback callback) {
        mInputManager = context.getSystemService(InputManager.class);
        mHandler = handler;
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
                    new SurfaceControl(mDecorationSurface, TAG),
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

        mInputEventReceiver = new TaskResizeInputEventReceiver(mInputChannel, mHandler.getLooper());
        mCallback = callback;
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
     */
    void setGeometry(int width, int height, int resizeHandleThickness) {
        if (mWidth == width && mHeight == height
                && mResizeHandleThickness == resizeHandleThickness) {
            return;
        }

        mWidth = width;
        mHeight = height;
        mResizeHandleThickness = resizeHandleThickness;

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

        try {
            mWindowSession.updateInputChannel(
                    mInputChannel.getToken(),
                    mDisplayId,
                    new SurfaceControl(
                            mDecorationSurface, "DragResizeInputListener#setTouchRegion"),
                    FLAG_NOT_FOCUSABLE,
                    PRIVATE_FLAG_TRUSTED_OVERLAY,
                    touchRegion);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        // This marks all relevant components have handled the previous resize event and can take
        // the next one now.
        mInputEventReceiver.onHandledLastResizeEvent();
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
        private boolean mWaitingForLastResizeEventHandled;

        private TaskResizeInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        private void onHandledLastResizeEvent() {
            mWaitingForLastResizeEventHandled = false;
            consumeBatchedInputEvents(-1);
        }

        @Override
        public void onBatchedInputEventPending(int source) {
            // InputEventReceiver keeps continuous move events in a batched event until explicitly
            // consuming it or an incompatible event shows up (likely an up event in this case). We
            // continue to keep move events in the next batched event until we receive a geometry
            // update so that we don't put too much pressure on the framework with excessive number
            // of input events if it can't handle them fast enough. It's more responsive to always
            // resize the task to the latest received coordinates.
            if (!mWaitingForLastResizeEventHandled) {
                consumeBatchedInputEvents(-1);
            }
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
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mCallback.onDragResizeStart(
                            calculateCtrlType(e.getX(0), e.getY(0)), e.getRawX(0), e.getRawY(0));
                    mWaitingForLastResizeEventHandled = false;
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int dragPointerIndex = e.findPointerIndex(mDragPointerId);
                    mCallback.onDragResizeMove(
                            e.getRawX(dragPointerIndex), e.getRawY(dragPointerIndex));
                    mWaitingForLastResizeEventHandled = true;
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    int dragPointerIndex = e.findPointerIndex(mDragPointerId);
                    mCallback.onDragResizeEnd(
                            e.getRawX(dragPointerIndex), e.getRawY(dragPointerIndex));
                    mWaitingForLastResizeEventHandled = false;
                    mDragPointerId = -1;
                    break;
                }
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    updateCursorType(e.getXCursorPosition(), e.getYCursorPosition());
                    break;
                }
                case MotionEvent.ACTION_HOVER_EXIT:
                    mInputManager.setPointerIconType(PointerIcon.TYPE_DEFAULT);
                    break;
            }
            return true;
        }

        @TaskPositioner.CtrlType
        private int calculateCtrlType(float x, float y) {
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

        private void updateCursorType(float x, float y) {
            @TaskPositioner.CtrlType int ctrlType = calculateCtrlType(x, y);

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
