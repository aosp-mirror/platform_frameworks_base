/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.IActivityManager;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import com.android.internal.annotations.GuardedBy;
import com.android.server.input.InputManagerService;
import com.android.server.input.InputWindowHandle;

/**
 * Controller for task positioning by drag.
 */
class TaskPositioningController {
    private final WindowManagerService mService;
    private final InputManagerService mInputManager;
    private final InputMonitor mInputMonitor;
    private final IActivityManager mActivityManager;
    private final Handler mHandler;

    @GuardedBy("WindowManagerSerivce.mWindowMap")
    private @Nullable TaskPositioner mTaskPositioner;

    boolean isPositioningLocked() {
        return mTaskPositioner != null;
    }

    InputWindowHandle getDragWindowHandleLocked() {
        return mTaskPositioner != null ? mTaskPositioner.mDragWindowHandle : null;
    }

    TaskPositioningController(WindowManagerService service, InputManagerService inputManager,
            InputMonitor inputMonitor, IActivityManager activityManager, Looper looper) {
        mService = service;
        mInputMonitor = inputMonitor;
        mInputManager = inputManager;
        mActivityManager = activityManager;
        mHandler = new Handler(looper);
    }

    boolean startMovingTask(IWindow window, float startX, float startY) {
        WindowState win = null;
        synchronized (mService.mWindowMap) {
            win = mService.windowForClientLocked(null, window, false);
            // win shouldn't be null here, pass it down to startPositioningLocked
            // to get warning if it's null.
            if (!startPositioningLocked(
                    win, false /*resize*/, false /*preserveOrientation*/, startX, startY)) {
                return false;
            }
        }
        try {
            mActivityManager.setFocusedTask(win.getTask().mTaskId);
        } catch(RemoteException e) {}
        return true;
    }

    void handleTapOutsideTask(DisplayContent displayContent, int x, int y) {
        mHandler.post(() -> {
            int taskId = -1;
            synchronized (mService.mWindowMap) {
                final Task task = displayContent.findTaskForResizePoint(x, y);
                if (task != null) {
                    if (!startPositioningLocked(task.getTopVisibleAppMainWindow(), true /*resize*/,
                            task.preserveOrientationOnResize(), x, y)) {
                        return;
                    }
                    taskId = task.mTaskId;
                } else {
                    taskId = displayContent.taskIdFromPoint(x, y);
                }
            }
            if (taskId >= 0) {
                try {
                    mActivityManager.setFocusedTask(taskId);
                } catch (RemoteException e) {
                }
            }
        });
    }

    private boolean startPositioningLocked(WindowState win, boolean resize,
            boolean preserveOrientation, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING)
            Slog.d(TAG_WM, "startPositioningLocked: "
                    + "win=" + win + ", resize=" + resize + ", preserveOrientation="
                    + preserveOrientation + ", {" + startX + ", " + startY + "}");

        if (win == null || win.getAppToken() == null) {
            Slog.w(TAG_WM, "startPositioningLocked: Bad window " + win);
            return false;
        }
        if (win.mInputChannel == null) {
            Slog.wtf(TAG_WM, "startPositioningLocked: " + win + " has no input channel, "
                    + " probably being removed");
            return false;
        }

        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            Slog.w(TAG_WM, "startPositioningLocked: Invalid display content " + win);
            return false;
        }

        Display display = displayContent.getDisplay();
        mTaskPositioner = TaskPositioner.create(mService);
        mTaskPositioner.register(displayContent);
        mInputMonitor.updateInputWindowsLw(true /*force*/);

        // We need to grab the touch focus so that the touch events during the
        // resizing/scrolling are not sent to the app. 'win' is the main window
        // of the app, it may not have focus since there might be other windows
        // on top (eg. a dialog window).
        WindowState transferFocusFromWin = win;
        if (mService.mCurrentFocus != null && mService.mCurrentFocus != win
                && mService.mCurrentFocus.mAppToken == win.mAppToken) {
            transferFocusFromWin = mService.mCurrentFocus;
        }
        if (!mInputManager.transferTouchFocus(
                transferFocusFromWin.mInputChannel, mTaskPositioner.mServerChannel)) {
            Slog.e(TAG_WM, "startPositioningLocked: Unable to transfer touch focus");
            mTaskPositioner.unregister();
            mTaskPositioner = null;
            mInputMonitor.updateInputWindowsLw(true /*force*/);
            return false;
        }

        mTaskPositioner.startDrag(win, resize, preserveOrientation, startX, startY);
        return true;
    }

    void finishTaskPositioning() {
        mHandler.post(() -> {
            if (DEBUG_TASK_POSITIONING) Slog.d(TAG_WM, "finishPositioning");

            synchronized (mService.mWindowMap) {
                if (mTaskPositioner != null) {
                    mTaskPositioner.unregister();
                    mTaskPositioner = null;
                    mInputMonitor.updateInputWindowsLw(true /*force*/);
                }
            }
        });
    }
}
