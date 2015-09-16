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
import static android.app.ActivityManager.FREEFORM_WORKSPACE_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static com.android.server.wm.WindowManagerService.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerService.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP;

import android.annotation.IntDef;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputWindowHandle;
import com.android.server.wm.WindowManagerService.H;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class TaskPositioner implements DimLayer.DimLayerUser {
    private static final String TAG = "TaskPositioner";

    // The margin the pointer position has to be within the side of the screen to be
    // considered at the side of the screen.
    private static final int SIDE_MARGIN_DIP = 100;

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

    private final WindowManagerService mService;
    private WindowPositionerEventReceiver mInputEventReceiver;
    private Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private DimLayer mDimLayer;
    @CtrlType
    private int mCurrentDimSide;
    private Rect mTmpRect = new Rect();
    private int mSideMargin;

    private int mTaskId;
    private TaskStack mStack;
    private boolean mResizing;
    private final Rect mWindowOriginalBounds = new Rect();
    private final Rect mWindowDragBounds = new Rect();
    private float mStartDragX;
    private float mStartDragY;
    @CtrlType
    private int mCtrlType = CTRL_NONE;

    InputChannel mServerChannel;
    InputChannel mClientChannel;
    InputApplicationHandle mDragApplicationHandle;
    InputWindowHandle mDragWindowHandle;

    private final class WindowPositionerEventReceiver extends InputEventReceiver {
        public WindowPositionerEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
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
                boolean endDrag = false;
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
                            notifyMoveLocked(newX, newY);
                        }
                        try {
                            mService.mActivityManager.resizeTask(
                                    mTaskId, mWindowDragBounds, true /* resizedByUser */);
                        } catch(RemoteException e) {}
                    } break;

                    case MotionEvent.ACTION_UP: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_UP @ {" + newX + ", " + newY + "}");
                        }
                        endDrag = true;
                    } break;

                    case MotionEvent.ACTION_CANCEL: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_CANCEL @ {" + newX + ", " + newY + "}");
                        }
                        endDrag = true;
                    } break;
                }

                if (endDrag) {
                    mResizing = false;
                    try {
                        mService.mActivityManager.resizeTask(
                                mTaskId, mWindowDragBounds, true /* resizedByUser */);
                    } catch(RemoteException e) {}
                    // Post back to WM to handle clean-ups. We still need the input
                    // event handler for the last finishInputEvent()!
                    mService.mH.sendEmptyMessage(H.FINISH_TASK_POSITIONING);
                    if (mCurrentDimSide != CTRL_NONE) {
                        final int createMode = mCurrentDimSide == CTRL_LEFT
                                ? DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT
                                : DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
                        mService.mActivityManager.moveTaskToDockedStack(
                                mTaskId, createMode, true /*toTop*/);
                    }
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

        mInputEventReceiver = new WindowPositionerEventReceiver(mClientChannel,
                mService.mH.getLooper());

        mDragApplicationHandle = new InputApplicationHandle(null);
        mDragApplicationHandle.name = TAG;
        mDragApplicationHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

        mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null,
                mDisplay.getDisplayId());
        mDragWindowHandle.name = TAG;
        mDragWindowHandle.inputChannel = mServerChannel;
        mDragWindowHandle.layer = getDragLayerLocked();
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
        if (WindowManagerService.DEBUG_ORIENTATION) {
            Slog.d(TAG, "Pausing rotation during re-position");
        }
        mService.pauseRotationLocked();

        mDimLayer = new DimLayer(mService, this, mDisplay.getDisplayId());
        mSideMargin = mService.dipToPixel(SIDE_MARGIN_DIP, mDisplayMetrics);
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

        // Resume rotations after a drag.
        if (WindowManagerService.DEBUG_ORIENTATION) {
            Slog.d(TAG, "Resuming rotation after re-position");
        }
        mService.resumeRotationLocked();
    }

    boolean isTaskResizing(final Task task) {
        return mResizing && task != null && mTaskId == task.mTaskId;
    }

    void startDragLocked(WindowState win, boolean resize, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "startDragLocked: win=" + win + ", resize=" + resize
                + ", {" + startX + ", " + startY + "}");
        }
        mCtrlType = CTRL_NONE;
        if (resize) {
            final Rect visibleFrame = win.mVisibleFrame;
            if (startX < visibleFrame.left) {
                mCtrlType |= CTRL_LEFT;
            }
            if (startX > visibleFrame.right) {
                mCtrlType |= CTRL_RIGHT;
            }
            if (startY < visibleFrame.top) {
                mCtrlType |= CTRL_TOP;
            }
            if (startY > visibleFrame.bottom) {
                mCtrlType |= CTRL_BOTTOM;
            }
            mResizing = true;
        }

        final Task task = win.getTask();
        mTaskId = task.mTaskId;
        mStack = task.mStack;
        mStartDragX = startX;
        mStartDragY = startY;

        mService.getTaskBounds(mTaskId, mWindowOriginalBounds);
    }

    private void notifyMoveLocked(float x, float y) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "notifyMoveLw: {" + x + "," + y + "}");
        }

        if (mCtrlType != CTRL_NONE) {
            // This is a resizing operation.
            final int deltaX = Math.round(x - mStartDragX);
            final int deltaY = Math.round(y - mStartDragY);
            final int minSizeX = mService.dipToPixel(MINIMUM_VISIBLE_WIDTH_IN_DP, mDisplayMetrics);
            final int minSizeY = mService.dipToPixel(MINIMUM_VISIBLE_HEIGHT_IN_DP, mDisplayMetrics);
            int left = mWindowOriginalBounds.left;
            int top = mWindowOriginalBounds.top;
            int right = mWindowOriginalBounds.right;
            int bottom = mWindowOriginalBounds.bottom;
            if ((mCtrlType & CTRL_LEFT) != 0) {
                left = Math.min(left + deltaX, right - minSizeX);
            }
            if ((mCtrlType & CTRL_TOP) != 0) {
                top = Math.min(top + deltaY, bottom - minSizeY);
            }
            if ((mCtrlType & CTRL_RIGHT) != 0) {
                right = Math.max(left + minSizeX, right + deltaX);
            }
            if ((mCtrlType & CTRL_BOTTOM) != 0) {
                bottom = Math.max(top + minSizeY, bottom + deltaY);
            }
            mWindowDragBounds.set(left, top, right, bottom);
        } else {
            // This is a moving operation.
            mWindowDragBounds.set(mWindowOriginalBounds);
            mWindowDragBounds.offset(Math.round(x - mStartDragX), Math.round(y - mStartDragY));
            updateDimLayerVisibility((int) x);
        }
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
        if (mStack.mStackId != FREEFORM_WORKSPACE_STACK_ID
                || !mStack.isFullscreen()
                || mService.mCurConfiguration.orientation != ORIENTATION_LANDSCAPE) {
            return CTRL_NONE;
        }

        mStack.getBounds(mTmpRect);
        if (x - mSideMargin <= mTmpRect.left) {
            return CTRL_LEFT;
        }
        if (x + mSideMargin >= mTmpRect.right) {
            return CTRL_RIGHT;
        }

        return CTRL_NONE;
    }

    private void showDimLayer() {
        mStack.getBounds(mTmpRect);
        if (mCurrentDimSide == CTRL_LEFT) {
            mTmpRect.right = mTmpRect.centerX();
        } else if (mCurrentDimSide == CTRL_RIGHT) {
            mTmpRect.left = mTmpRect.centerX();
        }

        mDimLayer.setBounds(mTmpRect);
        mDimLayer.show(getDragLayerLocked(), 0.5f, 0);
    }

    @Override /** {@link DimLayer.DimLayerUser} */
    public boolean isFullscreen() {
        return false;
    }

    @Override /** {@link DimLayer.DimLayerUser} */
    public DisplayInfo getDisplayInfo() {
        return mStack.getDisplayInfo();
    }

    private int getDragLayerLocked() {
        return mService.mPolicy.windowTypeToLayerLw(WindowManager.LayoutParams.TYPE_DRAG)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }
}
