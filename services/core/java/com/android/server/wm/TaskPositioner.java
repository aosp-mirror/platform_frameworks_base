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

package com.android.server.wm;

import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
import static android.app.ActivityManager.RESIZE_MODE_USER;
import static android.app.ActivityManager.RESIZE_MODE_USER_FORCED;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP;

import android.annotation.IntDef;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputWindowHandle;
import com.android.server.wm.WindowManagerService.H;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class TaskPositioner implements DimLayer.DimLayerUser {
    private static final String TAG_LOCAL = "TaskPositioner";
    private static final String TAG = TAG_WITH_CLASS_NAME ? TAG_LOCAL : TAG_WM;

    // The margin the pointer position has to be within the side of the screen to be
    // considered at the side of the screen.
    static final int SIDE_MARGIN_DIP = 100;

    @IntDef(flag = true,
            value = {
                    CTRL_NONE,
                    CTRL_LEFT,
                    CTRL_RIGHT,
                    CTRL_TOP,
                    CTRL_BOTTOM
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface CtrlType {}

    private static final int CTRL_NONE   = 0x0;
    private static final int CTRL_LEFT   = 0x1;
    private static final int CTRL_RIGHT  = 0x2;
    private static final int CTRL_TOP    = 0x4;
    private static final int CTRL_BOTTOM = 0x8;

    public static final float RESIZING_HINT_ALPHA = 0.5f;

    public static final int RESIZING_HINT_DURATION_MS = 0;

    private final WindowManagerService mService;
    private WindowPositionerEventReceiver mInputEventReceiver;
    private Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private DimLayer mDimLayer;
    @CtrlType
    private int mCurrentDimSide;
    private Rect mTmpRect = new Rect();
    private int mSideMargin;
    private int mMinVisibleWidth;
    private int mMinVisibleHeight;

    private Task mTask;
    private boolean mResizing;
    private final Rect mWindowOriginalBounds = new Rect();
    private final Rect mWindowDragBounds = new Rect();
    private float mStartDragX;
    private float mStartDragY;
    @CtrlType
    private int mCtrlType = CTRL_NONE;
    private boolean mDragEnded = false;

    InputChannel mServerChannel;
    InputChannel mClientChannel;
    InputApplicationHandle mDragApplicationHandle;
    InputWindowHandle mDragWindowHandle;

    private final class WindowPositionerEventReceiver extends BatchedInputEventReceiver {
        public WindowPositionerEventReceiver(
                InputChannel inputChannel, Looper looper, Choreographer choreographer) {
            super(inputChannel, looper, choreographer);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            if (!(event instanceof MotionEvent)
                    || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) {
                return;
            }
            final MotionEvent motionEvent = (MotionEvent) event;
            boolean handled = false;

            try {
                if (mDragEnded) {
                    // The drag has ended but the clean-up message has not been processed by
                    // window manager. Drop events that occur after this until window manager
                    // has a chance to clean-up the input handle.
                    handled = true;
                    return;
                }

                final float newX = motionEvent.getRawX();
                final float newY = motionEvent.getRawY();

                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_DOWN @ {" + newX + ", " + newY + "}");
                        }
                    } break;

                    case MotionEvent.ACTION_MOVE: {
                        if (DEBUG_TASK_POSITIONING){
                            Slog.w(TAG, "ACTION_MOVE @ {" + newX + ", " + newY + "}");
                        }
                        synchronized (mService.mWindowMap) {
                            mDragEnded = notifyMoveLocked(newX, newY);
                            mTask.getDimBounds(mTmpRect);
                        }
                        if (!mTmpRect.equals(mWindowDragBounds)) {
                            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                                    "wm.TaskPositioner.resizeTask");
                            try {
                                mService.mActivityManager.resizeTask(
                                        mTask.mTaskId, mWindowDragBounds, RESIZE_MODE_USER);
                            } catch (RemoteException e) {
                            }
                            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                        }
                    } break;

                    case MotionEvent.ACTION_UP: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_UP @ {" + newX + ", " + newY + "}");
                        }
                        mDragEnded = true;
                    } break;

                    case MotionEvent.ACTION_CANCEL: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_CANCEL @ {" + newX + ", " + newY + "}");
                        }
                        mDragEnded = true;
                    } break;
                }

                if (mDragEnded) {
                    final boolean wasResizing = mResizing;
                    synchronized (mService.mWindowMap) {
                        endDragLocked();
                    }
                    try {
                        if (wasResizing) {
                            // We were using fullscreen surface during resizing. Request
                            // resizeTask() one last time to restore surface to window size.
                            mService.mActivityManager.resizeTask(
                                    mTask.mTaskId, mWindowDragBounds, RESIZE_MODE_USER_FORCED);
                        }

                        if (mCurrentDimSide != CTRL_NONE) {
                            final int createMode = mCurrentDimSide == CTRL_LEFT
                                    ? DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT
                                    : DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
                            mService.mActivityManager.moveTaskToDockedStack(
                                    mTask.mTaskId, createMode, true /*toTop*/, true /* animate */,
                                    null /* initialBounds */, false /* moveHomeStackFront */);
                        }
                    } catch(RemoteException e) {}

                    // Post back to WM to handle clean-ups. We still need the input
                    // event handler for the last finishInputEvent()!
                    mService.mH.sendEmptyMessage(H.FINISH_TASK_POSITIONING);
                }
                handled = true;
            } catch (Exception e) {
                Slog.e(TAG, "Exception caught by drag handleMotion", e);
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    TaskPositioner(WindowManagerService service) {
        mService = service;
    }

    /**
     * @param display The Display that the window being dragged is on.
     */
    void register(Display display) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Registering task positioner");
        }

        if (mClientChannel != null) {
            Slog.e(TAG, "Task positioner already registered");
            return;
        }

        mDisplay = display;
        mDisplay.getMetrics(mDisplayMetrics);
        final InputChannel[] channels = InputChannel.openInputChannelPair(TAG);
        mServerChannel = channels[0];
        mClientChannel = channels[1];
        mService.mInputManager.registerInputChannel(mServerChannel, null);

        mInputEventReceiver = new WindowPositionerEventReceiver(
                mClientChannel, mService.mH.getLooper(), mService.mChoreographer);

        mDragApplicationHandle = new InputApplicationHandle(null);
        mDragApplicationHandle.name = TAG;
        mDragApplicationHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

        mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null,
                mDisplay.getDisplayId());
        mDragWindowHandle.name = TAG;
        mDragWindowHandle.inputChannel = mServerChannel;
        mDragWindowHandle.layer = mService.getDragLayerLocked();
        mDragWindowHandle.layoutParamsFlags = 0;
        mDragWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_DRAG;
        mDragWindowHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        mDragWindowHandle.visible = true;
        mDragWindowHandle.canReceiveKeys = false;
        mDragWindowHandle.hasFocus = true;
        mDragWindowHandle.hasWallpaper = false;
        mDragWindowHandle.paused = false;
        mDragWindowHandle.ownerPid = Process.myPid();
        mDragWindowHandle.ownerUid = Process.myUid();
        mDragWindowHandle.inputFeatures = 0;
        mDragWindowHandle.scaleFactor = 1.0f;

        // The drag window cannot receive new touches.
        mDragWindowHandle.touchableRegion.setEmpty();

        // The drag window covers the entire display
        mDragWindowHandle.frameLeft = 0;
        mDragWindowHandle.frameTop = 0;
        final Point p = new Point();
        mDisplay.getRealSize(p);
        mDragWindowHandle.frameRight = p.x;
        mDragWindowHandle.frameBottom = p.y;

        // Pause rotations before a drag.
        if (DEBUG_ORIENTATION) {
            Slog.d(TAG, "Pausing rotation during re-position");
        }
        mService.pauseRotationLocked();

        mDimLayer = new DimLayer(mService, this, mDisplay.getDisplayId(), TAG_LOCAL);
        mSideMargin = dipToPixel(SIDE_MARGIN_DIP, mDisplayMetrics);
        mMinVisibleWidth = dipToPixel(MINIMUM_VISIBLE_WIDTH_IN_DP, mDisplayMetrics);
        mMinVisibleHeight = dipToPixel(MINIMUM_VISIBLE_HEIGHT_IN_DP, mDisplayMetrics);

        mDragEnded = false;
    }

    void unregister() {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Unregistering task positioner");
        }

        if (mClientChannel == null) {
            Slog.e(TAG, "Task positioner not registered");
            return;
        }

        mService.mInputManager.unregisterInputChannel(mServerChannel);

        mInputEventReceiver.dispose();
        mInputEventReceiver = null;
        mClientChannel.dispose();
        mServerChannel.dispose();
        mClientChannel = null;
        mServerChannel = null;

        mDragWindowHandle = null;
        mDragApplicationHandle = null;
        mDisplay = null;

        if (mDimLayer != null) {
            mDimLayer.destroySurface();
            mDimLayer = null;
        }
        mCurrentDimSide = CTRL_NONE;
        mDragEnded = true;

        // Resume rotations after a drag.
        if (DEBUG_ORIENTATION) {
            Slog.d(TAG, "Resuming rotation after re-position");
        }
        mService.resumeRotationLocked();
    }

    void startDragLocked(WindowState win, boolean resize, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "startDragLocked: win=" + win + ", resize=" + resize
                + ", {" + startX + ", " + startY + "}");
        }
        mCtrlType = CTRL_NONE;
        mTask = win.getTask();
        mStartDragX = startX;
        mStartDragY = startY;

        if (mTask.isDockedInEffect()) {
            // If this is a docked task or if task size is affected by docked stack changing size,
            // we can only be here if the task is not resizeable and we're handling a two-finger
            // scrolling. Use the original task bounds to position the task, the dim bounds
            // is cropped and doesn't move.
            mTask.getBounds(mTmpRect);
        } else {
            // Use the dim bounds, not the original task bounds. The cursor
            // movement should be calculated relative to the visible bounds.
            // Also, use the dim bounds of the task which accounts for
            // multiple app windows. Don't use any bounds from win itself as it
            // may not be the same size as the task.
            mTask.getDimBounds(mTmpRect);
        }

        if (resize) {
            if (startX < mTmpRect.left) {
                mCtrlType |= CTRL_LEFT;
            }
            if (startX > mTmpRect.right) {
                mCtrlType |= CTRL_RIGHT;
            }
            if (startY < mTmpRect.top) {
                mCtrlType |= CTRL_TOP;
            }
            if (startY > mTmpRect.bottom) {
                mCtrlType |= CTRL_BOTTOM;
            }
            mResizing = true;
        }

        mWindowOriginalBounds.set(mTmpRect);
    }

    private void endDragLocked() {
        mResizing = false;
        mTask.setDragResizing(false, DRAG_RESIZE_MODE_FREEFORM);
    }

    /** Returns true if the move operation should be ended. */
    private boolean notifyMoveLocked(float x, float y) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "notifyMoveLocked: {" + x + "," + y + "}");
        }

        if (mCtrlType != CTRL_NONE) {
            // This is a resizing operation.
            final int deltaX = Math.round(x - mStartDragX);
            final int deltaY = Math.round(y - mStartDragY);
            int left = mWindowOriginalBounds.left;
            int top = mWindowOriginalBounds.top;
            int right = mWindowOriginalBounds.right;
            int bottom = mWindowOriginalBounds.bottom;
            if ((mCtrlType & CTRL_LEFT) != 0) {
                left = Math.min(left + deltaX, right - mMinVisibleWidth);
            }
            if ((mCtrlType & CTRL_TOP) != 0) {
                top = Math.min(top + deltaY, bottom - mMinVisibleHeight);
            }
            if ((mCtrlType & CTRL_RIGHT) != 0) {
                right = Math.max(left + mMinVisibleWidth, right + deltaX);
            }
            if ((mCtrlType & CTRL_BOTTOM) != 0) {
                bottom = Math.max(top + mMinVisibleHeight, bottom + deltaY);
            }
            mWindowDragBounds.set(left, top, right, bottom);
            mTask.setDragResizing(true, DRAG_RESIZE_MODE_FREEFORM);
            return false;
        }

        // This is a moving operation.
        mTask.mStack.getDimBounds(mTmpRect);

        // If this is a non-resizeable task put into side-by-side mode, we are
        // handling a two-finger scrolling action. No need to shrink the bounds.
        if (!mTask.isDockedInEffect()) {
            mTmpRect.inset(mMinVisibleWidth, mMinVisibleHeight);
        }

        boolean dragEnded = false;
        final int nX = (int) x;
        final int nY = (int) y;
        if (!mTmpRect.contains(nX, nY)) {
            // We end the moving operation if position is outside the stack bounds.
            // In this case we need to clamp the position to stack bounds and calculate
            // the final window drag bounds.
            x = Math.min(Math.max(x, mTmpRect.left), mTmpRect.right);
            y = Math.min(Math.max(y, mTmpRect.top), mTmpRect.bottom);
            dragEnded = true;
        }

        updateWindowDragBounds(nX, nY);
        updateDimLayerVisibility(nX);
        return dragEnded;
    }

    private void updateWindowDragBounds(int x, int y) {
        mWindowDragBounds.set(mWindowOriginalBounds);
        if (mTask.isDockedInEffect()) {
            // Offset the bounds without clamp, the bounds will be shifted later
            // by window manager before applying the scrolling.
            if (mService.mCurConfiguration.orientation == ORIENTATION_LANDSCAPE) {
                mWindowDragBounds.offset(Math.round(x - mStartDragX), 0);
            } else {
                mWindowDragBounds.offset(0, Math.round(y - mStartDragY));
            }
        } else {
            mWindowDragBounds.offset(Math.round(x - mStartDragX), Math.round(y - mStartDragY));
        }
        if (DEBUG_TASK_POSITIONING) Slog.d(TAG,
                "updateWindowDragBounds: " + mWindowDragBounds);
    }

    private void updateDimLayerVisibility(int x) {
        @CtrlType
        int dimSide = getDimSide(x);
        if (dimSide == mCurrentDimSide) {
            return;
        }

        mCurrentDimSide = dimSide;

        if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION updateDimLayerVisibility");
        SurfaceControl.openTransaction();
        if (mCurrentDimSide == CTRL_NONE) {
            mDimLayer.hide();
        } else {
            showDimLayer();
        }
        SurfaceControl.closeTransaction();
    }

    /**
     * Returns the side of the screen the dim layer should be shown.
     * @param x horizontal coordinate used to determine if the dim layer should be shown
     * @return Returns {@link #CTRL_LEFT} if the dim layer should be shown on the left half of the
     * screen, {@link #CTRL_RIGHT} if on the right side, or {@link #CTRL_NONE} if the dim layer
     * shouldn't be shown.
     */
    private int getDimSide(int x) {
        if (mTask.mStack.mStackId != FREEFORM_WORKSPACE_STACK_ID
                || !mTask.mStack.isFullscreen()
                || mService.mCurConfiguration.orientation != ORIENTATION_LANDSCAPE) {
            return CTRL_NONE;
        }

        mTask.mStack.getDimBounds(mTmpRect);
        if (x - mSideMargin <= mTmpRect.left) {
            return CTRL_LEFT;
        }
        if (x + mSideMargin >= mTmpRect.right) {
            return CTRL_RIGHT;
        }

        return CTRL_NONE;
    }

    private void showDimLayer() {
        mTask.mStack.getDimBounds(mTmpRect);
        if (mCurrentDimSide == CTRL_LEFT) {
            mTmpRect.right = mTmpRect.centerX();
        } else if (mCurrentDimSide == CTRL_RIGHT) {
            mTmpRect.left = mTmpRect.centerX();
        }

        mDimLayer.setBounds(mTmpRect);
        mDimLayer.show(mService.getDragLayerLocked(), RESIZING_HINT_ALPHA,
                RESIZING_HINT_DURATION_MS);
    }

    @Override /** {@link DimLayer.DimLayerUser} */
    public boolean dimFullscreen() {
        return isFullscreen();
    }

    boolean isFullscreen() {
        return false;
    }

    @Override /** {@link DimLayer.DimLayerUser} */
    public DisplayInfo getDisplayInfo() {
        return mTask.mStack.getDisplayInfo();
    }

    @Override
    public void getDimBounds(Rect out) {
        // This dim layer user doesn't need this.
    }

    @Override
    public String toShortString() {
        return TAG;
    }
}
