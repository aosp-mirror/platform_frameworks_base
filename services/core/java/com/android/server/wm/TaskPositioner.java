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

import static android.app.ActivityTaskManager.RESIZE_MODE_USER;
import static android.app.ActivityTaskManager.RESIZE_MODE_USER_FORCED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP;

import android.annotation.IntDef;
import android.app.IActivityTaskManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputWindowHandle;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class TaskPositioner implements IBinder.DeathRecipient {
    private static final boolean DEBUG_ORIENTATION_VIOLATIONS = false;
    private static final String TAG_LOCAL = "TaskPositioner";
    private static final String TAG = TAG_WITH_CLASS_NAME ? TAG_LOCAL : TAG_WM;

    private static Factory sFactory;

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

    // The minimal aspect ratio which needs to be met to count as landscape (or 1/.. for portrait).
    // Note: We do not use the 1.33 from the CDD here since the user is allowed to use what ever
    // aspect he desires.
    @VisibleForTesting
    static final float MIN_ASPECT = 1.2f;

    private final WindowManagerService mService;
    private final IActivityTaskManager mActivityManager;
    private WindowPositionerEventReceiver mInputEventReceiver;
    private DisplayContent mDisplayContent;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private Rect mTmpRect = new Rect();
    private int mSideMargin;
    private int mMinVisibleWidth;
    private int mMinVisibleHeight;

    @VisibleForTesting
    Task mTask;
    private boolean mResizing;
    private boolean mPreserveOrientation;
    private boolean mStartOrientationWasLandscape;
    private final Rect mWindowOriginalBounds = new Rect();
    private final Rect mWindowDragBounds = new Rect();
    private final Point mMaxVisibleSize = new Point();
    private float mStartDragX;
    private float mStartDragY;
    @CtrlType
    private int mCtrlType = CTRL_NONE;
    @VisibleForTesting
    boolean mDragEnded;
    IBinder mClientCallback;

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
                        synchronized (mService.mGlobalLock) {
                            mDragEnded = notifyMoveLocked(newX, newY);
                            mTask.getDimBounds(mTmpRect);
                        }
                        if (!mTmpRect.equals(mWindowDragBounds)) {
                            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                                    "wm.TaskPositioner.resizeTask");
                            try {
                                mActivityManager.resizeTask(
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
                    synchronized (mService.mGlobalLock) {
                        endDragLocked();
                        mTask.getDimBounds(mTmpRect);
                    }
                    try {
                        if (wasResizing && !mTmpRect.equals(mWindowDragBounds)) {
                            // We were using fullscreen surface during resizing. Request
                            // resizeTask() one last time to restore surface to window size.
                            mActivityManager.resizeTask(
                                    mTask.mTaskId, mWindowDragBounds, RESIZE_MODE_USER_FORCED);
                        }
                    } catch(RemoteException e) {}

                    // Post back to WM to handle clean-ups. We still need the input
                    // event handler for the last finishInputEvent()!
                    mService.mTaskPositioningController.finishTaskPositioning();
                }
                handled = true;
            } catch (Exception e) {
                Slog.e(TAG, "Exception caught by drag handleMotion", e);
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    @VisibleForTesting
    TaskPositioner(WindowManagerService service, IActivityTaskManager activityManager) {
        mService = service;
        mActivityManager = activityManager;
    }

    /** Use {@link #create(WindowManagerService)} instead **/
    TaskPositioner(WindowManagerService service) {
        this(service, service.mActivityTaskManager);
    }

    @VisibleForTesting
    Rect getWindowDragBounds() {
        return mWindowDragBounds;
    }

    /**
     * @param displayContent The Display that the window being dragged is on.
     */
    void register(DisplayContent displayContent) {
        final Display display = displayContent.getDisplay();

        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Registering task positioner");
        }

        if (mClientChannel != null) {
            Slog.e(TAG, "Task positioner already registered");
            return;
        }

        mDisplayContent = displayContent;
        display.getMetrics(mDisplayMetrics);
        final InputChannel[] channels = InputChannel.openInputChannelPair(TAG);
        mServerChannel = channels[0];
        mClientChannel = channels[1];
        mService.mInputManager.registerInputChannel(mServerChannel, null);

        mInputEventReceiver = new WindowPositionerEventReceiver(
                mClientChannel, mService.mAnimationHandler.getLooper(),
                mService.mAnimator.getChoreographer());

        mDragApplicationHandle = new InputApplicationHandle(new Binder());
        mDragApplicationHandle.name = TAG;
        mDragApplicationHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

        mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null,
                display.getDisplayId());
        mDragWindowHandle.name = TAG;
        mDragWindowHandle.token = mServerChannel.getToken();
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
        display.getRealSize(p);
        mDragWindowHandle.frameRight = p.x;
        mDragWindowHandle.frameBottom = p.y;

        // Pause rotations before a drag.
        if (DEBUG_ORIENTATION) {
            Slog.d(TAG, "Pausing rotation during re-position");
        }
        mDisplayContent.pauseRotationLocked();

        // Notify InputMonitor to take mDragWindowHandle.
        mDisplayContent.getInputMonitor().updateInputWindowsImmediately();
        new SurfaceControl.Transaction().syncInputWindows().apply();

        mSideMargin = dipToPixel(SIDE_MARGIN_DIP, mDisplayMetrics);
        mMinVisibleWidth = dipToPixel(MINIMUM_VISIBLE_WIDTH_IN_DP, mDisplayMetrics);
        mMinVisibleHeight = dipToPixel(MINIMUM_VISIBLE_HEIGHT_IN_DP, mDisplayMetrics);
        display.getRealSize(mMaxVisibleSize);

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
        mDragEnded = true;

        // Notify InputMonitor to remove mDragWindowHandle.
        mDisplayContent.getInputMonitor().updateInputWindowsLw(true /*force*/);

        // Resume rotations after a drag.
        if (DEBUG_ORIENTATION) {
            Slog.d(TAG, "Resuming rotation after re-position");
        }
        mDisplayContent.resumeRotationLocked();
        mDisplayContent = null;
        mClientCallback.unlinkToDeath(this, 0 /* flags */);
    }

    void startDrag(WindowState win, boolean resize, boolean preserveOrientation, float startX,
                   float startY) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "startDrag: win=" + win + ", resize=" + resize
                    + ", preserveOrientation=" + preserveOrientation + ", {" + startX + ", "
                    + startY + "}");
        }
        try {
            mClientCallback = win.mClient.asBinder();
            mClientCallback.linkToDeath(this, 0 /* flags */);
        } catch (RemoteException e) {
            // The caller has died, so clean up TaskPositioningController.
            mService.mTaskPositioningController.finishTaskPositioning();
            return;
        }
        mTask = win.getTask();
        // Use the bounds of the task which accounts for
        // multiple app windows. Don't use any bounds from win itself as it
        // may not be the same size as the task.
        mTask.getBounds(mTmpRect);
        startDrag(resize, preserveOrientation, startX, startY, mTmpRect);
    }

    protected void startDrag(boolean resize, boolean preserveOrientation,
                   float startX, float startY, Rect startBounds) {
        mCtrlType = CTRL_NONE;
        mStartDragX = startX;
        mStartDragY = startY;
        mPreserveOrientation = preserveOrientation;

        if (resize) {
            if (startX < startBounds.left) {
                mCtrlType |= CTRL_LEFT;
            }
            if (startX > startBounds.right) {
                mCtrlType |= CTRL_RIGHT;
            }
            if (startY < startBounds.top) {
                mCtrlType |= CTRL_TOP;
            }
            if (startY > startBounds.bottom) {
                mCtrlType |= CTRL_BOTTOM;
            }
            mResizing = mCtrlType != CTRL_NONE;
        }

        // In case of !isDockedInEffect we are using the union of all task bounds. These might be
        // made up out of multiple windows which are only partially overlapping. When that happens,
        // the orientation from the window of interest to the entire stack might diverge. However
        // for now we treat them as the same.
        mStartOrientationWasLandscape = startBounds.width() >= startBounds.height();
        mWindowOriginalBounds.set(startBounds);

        // Notify the app that resizing has started, even though we haven't received any new
        // bounds yet. This will guarantee that the app starts the backdrop renderer before
        // configuration changes which could cause an activity restart.
        if (mResizing) {
            synchronized (mService.mGlobalLock) {
                notifyMoveLocked(startX, startY);
            }

            // Perform the resize on the WMS handler thread when we don't have the WMS lock held
            // to ensure that we don't deadlock WMS and AMS. Note that WindowPositionerEventReceiver
            // callbacks are delivered on the same handler so this initial resize is always
            // guaranteed to happen before subsequent drag resizes.
            mService.mH.post(() -> {
                try {
                    mActivityManager.resizeTask(
                            mTask.mTaskId, startBounds, RESIZE_MODE_USER_FORCED);
                } catch (RemoteException e) {
                }
            });
        }

        // Make sure we always have valid drag bounds even if the drag ends before any move events
        // have been handled.
        mWindowDragBounds.set(startBounds);
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
            resizeDrag(x, y);
            mTask.setDragResizing(true, DRAG_RESIZE_MODE_FREEFORM);
            return false;
        }

        // This is a moving or scrolling operation.
        mTask.mStack.getDimBounds(mTmpRect);
        // If a target window is covered by system bar, there is no way to move it again by touch.
        // So we exclude them from stack bounds. and then it will be shown inside stable area.
        Rect stableBounds = new Rect();
        mDisplayContent.getStableRect(stableBounds);
        mTmpRect.intersect(stableBounds);

        int nX = (int) x;
        int nY = (int) y;
        if (!mTmpRect.contains(nX, nY)) {
            // For a moving operation we allow the pointer to go out of the stack bounds, but
            // use the clamped pointer position for the drag bounds computation.
            nX = Math.min(Math.max(nX, mTmpRect.left), mTmpRect.right);
            nY = Math.min(Math.max(nY, mTmpRect.top), mTmpRect.bottom);
        }

        updateWindowDragBounds(nX, nY, mTmpRect);
        return false;
    }

    /**
     * The user is drag - resizing the window.
     *
     * @param x The x coordinate of the current drag coordinate.
     * @param y the y coordinate of the current drag coordinate.
     */
    @VisibleForTesting
    void resizeDrag(float x, float y) {
        // This is a resizing operation.
        // We need to keep various constraints:
        // 1. mMinVisible[Width/Height] <= [width/height] <= mMaxVisibleSize.[x/y]
        // 2. The orientation is kept - if required.
        final int deltaX = Math.round(x - mStartDragX);
        final int deltaY = Math.round(y - mStartDragY);
        int left = mWindowOriginalBounds.left;
        int top = mWindowOriginalBounds.top;
        int right = mWindowOriginalBounds.right;
        int bottom = mWindowOriginalBounds.bottom;

        // The aspect which we have to respect. Note that if the orientation does not need to be
        // preserved the aspect will be calculated as 1.0 which neutralizes the following
        // computations.
        final float minAspect = !mPreserveOrientation
                ? 1.0f
                : (mStartOrientationWasLandscape ? MIN_ASPECT : (1.0f / MIN_ASPECT));
        // Calculate the resulting width and height of the drag operation.
        int width = right - left;
        int height = bottom - top;
        if ((mCtrlType & CTRL_LEFT) != 0) {
            width = Math.max(mMinVisibleWidth, width - deltaX);
        } else if ((mCtrlType & CTRL_RIGHT) != 0) {
            width = Math.max(mMinVisibleWidth, width + deltaX);
        }
        if ((mCtrlType & CTRL_TOP) != 0) {
            height = Math.max(mMinVisibleHeight, height - deltaY);
        } else if ((mCtrlType & CTRL_BOTTOM) != 0) {
            height = Math.max(mMinVisibleHeight, height + deltaY);
        }

        // If we have to preserve the orientation - check that we are doing so.
        final float aspect = (float) width / (float) height;
        if (mPreserveOrientation && ((mStartOrientationWasLandscape && aspect < MIN_ASPECT)
                || (!mStartOrientationWasLandscape && aspect > (1.0 / MIN_ASPECT)))) {
            // Calculate 2 rectangles fulfilling all requirements for either X or Y being the major
            // drag axis. What ever is producing the bigger rectangle will be chosen.
            int width1;
            int width2;
            int height1;
            int height2;
            if (mStartOrientationWasLandscape) {
                // Assuming that the width is our target we calculate the height.
                width1 = Math.max(mMinVisibleWidth, Math.min(mMaxVisibleSize.x, width));
                height1 = Math.min(height, Math.round((float)width1 / MIN_ASPECT));
                if (height1 < mMinVisibleHeight) {
                    // If the resulting height is too small we adjust to the minimal size.
                    height1 = mMinVisibleHeight;
                    width1 = Math.max(mMinVisibleWidth,
                            Math.min(mMaxVisibleSize.x, Math.round((float)height1 * MIN_ASPECT)));
                }
                // Assuming that the height is our target we calculate the width.
                height2 = Math.max(mMinVisibleHeight, Math.min(mMaxVisibleSize.y, height));
                width2 = Math.max(width, Math.round((float)height2 * MIN_ASPECT));
                if (width2 < mMinVisibleWidth) {
                    // If the resulting width is too small we adjust to the minimal size.
                    width2 = mMinVisibleWidth;
                    height2 = Math.max(mMinVisibleHeight,
                            Math.min(mMaxVisibleSize.y, Math.round((float)width2 / MIN_ASPECT)));
                }
            } else {
                // Assuming that the width is our target we calculate the height.
                width1 = Math.max(mMinVisibleWidth, Math.min(mMaxVisibleSize.x, width));
                height1 = Math.max(height, Math.round((float)width1 * MIN_ASPECT));
                if (height1 < mMinVisibleHeight) {
                    // If the resulting height is too small we adjust to the minimal size.
                    height1 = mMinVisibleHeight;
                    width1 = Math.max(mMinVisibleWidth,
                            Math.min(mMaxVisibleSize.x, Math.round((float)height1 / MIN_ASPECT)));
                }
                // Assuming that the height is our target we calculate the width.
                height2 = Math.max(mMinVisibleHeight, Math.min(mMaxVisibleSize.y, height));
                width2 = Math.min(width, Math.round((float)height2 / MIN_ASPECT));
                if (width2 < mMinVisibleWidth) {
                    // If the resulting width is too small we adjust to the minimal size.
                    width2 = mMinVisibleWidth;
                    height2 = Math.max(mMinVisibleHeight,
                            Math.min(mMaxVisibleSize.y, Math.round((float)width2 * MIN_ASPECT)));
                }
            }

            // Use the bigger of the two rectangles if the major change was positive, otherwise
            // do the opposite.
            final boolean grows = width > (right - left) || height > (bottom - top);
            if (grows == (width1 * height1 > width2 * height2)) {
                width = width1;
                height = height1;
            } else {
                width = width2;
                height = height2;
            }
        }

        // Update mWindowDragBounds to the new drag size.
        updateDraggedBounds(left, top, right, bottom, width, height);
    }

    /**
     * Given the old coordinates and the new width and height, update the mWindowDragBounds.
     *
     * @param left      The original left bound before the user started dragging.
     * @param top       The original top bound before the user started dragging.
     * @param right     The original right bound before the user started dragging.
     * @param bottom    The original bottom bound before the user started dragging.
     * @param newWidth  The new dragged width.
     * @param newHeight The new dragged height.
     */
    void updateDraggedBounds(int left, int top, int right, int bottom, int newWidth,
                             int newHeight) {
        // Generate the final bounds by keeping the opposite drag edge constant.
        if ((mCtrlType & CTRL_LEFT) != 0) {
            left = right - newWidth;
        } else { // Note: The right might have changed - if we pulled at the right or not.
            right = left + newWidth;
        }
        if ((mCtrlType & CTRL_TOP) != 0) {
            top = bottom - newHeight;
        } else { // Note: The height might have changed - if we pulled at the bottom or not.
            bottom = top + newHeight;
        }

        mWindowDragBounds.set(left, top, right, bottom);

        checkBoundsForOrientationViolations(mWindowDragBounds);
    }

    /**
     * Validate bounds against orientation violations (if DEBUG_ORIENTATION_VIOLATIONS is set).
     *
     * @param bounds The bounds to be checked.
     */
    private void checkBoundsForOrientationViolations(Rect bounds) {
        // When using debug check that we are not violating the given constraints.
        if (DEBUG_ORIENTATION_VIOLATIONS) {
            if (mStartOrientationWasLandscape != (bounds.width() >= bounds.height())) {
                Slog.e(TAG, "Orientation violation detected! should be "
                        + (mStartOrientationWasLandscape ? "landscape" : "portrait")
                        + " but is the other");
            } else {
                Slog.v(TAG, "new bounds size: " + bounds.width() + " x " + bounds.height());
            }
            if (mMinVisibleWidth > bounds.width() || mMinVisibleHeight > bounds.height()) {
                Slog.v(TAG, "Minimum requirement violated: Width(min, is)=(" + mMinVisibleWidth
                        + ", " + bounds.width() + ") Height(min,is)=("
                        + mMinVisibleHeight + ", " + bounds.height() + ")");
            }
            if (mMaxVisibleSize.x < bounds.width() || mMaxVisibleSize.y < bounds.height()) {
                Slog.v(TAG, "Maximum requirement violated: Width(min, is)=(" + mMaxVisibleSize.x
                        + ", " + bounds.width() + ") Height(min,is)=("
                        + mMaxVisibleSize.y + ", " + bounds.height() + ")");
            }
        }
    }

    private void updateWindowDragBounds(int x, int y, Rect stackBounds) {
        final int offsetX = Math.round(x - mStartDragX);
        final int offsetY = Math.round(y - mStartDragY);
        mWindowDragBounds.set(mWindowOriginalBounds);
        // Horizontally, at least mMinVisibleWidth pixels of the window should remain visible.
        final int maxLeft = stackBounds.right - mMinVisibleWidth;
        final int minLeft = stackBounds.left + mMinVisibleWidth - mWindowOriginalBounds.width();

        // Vertically, the top mMinVisibleHeight of the window should remain visible.
        // (This assumes that the window caption bar is at the top of the window).
        final int minTop = stackBounds.top;
        final int maxTop = stackBounds.bottom - mMinVisibleHeight;

        mWindowDragBounds.offsetTo(
                Math.min(Math.max(mWindowOriginalBounds.left + offsetX, minLeft), maxLeft),
                Math.min(Math.max(mWindowOriginalBounds.top + offsetY, minTop), maxTop));

        if (DEBUG_TASK_POSITIONING) Slog.d(TAG,
                "updateWindowDragBounds: " + mWindowDragBounds);
    }

    public String toShortString() {
        return TAG;
    }

    static void setFactory(Factory factory) {
        sFactory = factory;
    }

    static TaskPositioner create(WindowManagerService service) {
        if (sFactory == null) {
            sFactory = new Factory() {};
        }

        return sFactory.create(service);
    }

    @Override
    public void binderDied() {
        mService.mTaskPositioningController.finishTaskPositioning();
    }

    interface Factory {
        default TaskPositioner create(WindowManagerService service) {
            return new TaskPositioner(service);
        }
    }
}
