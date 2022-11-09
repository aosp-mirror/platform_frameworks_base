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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.transition.Transitions;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */

public class CaptionWindowDecorViewModel implements WindowDecorViewModel {
    private static final String TAG = "CaptionViewModel";
    private final ActivityTaskManager mActivityTaskManager;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private FreeformTaskTransitionStarter mTransitionStarter;
    private DesktopModeController mDesktopModeController;
    private EventReceiver mEventReceiver;
    private InputMonitor mInputMonitor;

    private final SparseArray<CaptionWindowDecoration> mWindowDecorByTaskId = new SparseArray<>();
    private final DragStartListenerImpl mDragStartListener = new DragStartListenerImpl();

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            DesktopModeController desktopModeController) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
        mDesktopModeController = desktopModeController;
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTransitionStarter = transitionStarter;
    }

    @Override
    public boolean createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return false;
        CaptionWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final CaptionWindowDecoration windowDecoration = new CaptionWindowDecoration(
                mContext,
                mDisplayController,
                mTaskOrganizer,
                taskInfo,
                taskSurface,
                mMainHandler,
                mMainChoreographer,
                mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        TaskPositioner taskPositioner = new TaskPositioner(mTaskOrganizer, windowDecoration,
                mDragStartListener);
        CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragResizeCallback(taskPositioner);
        setupWindowDecorationForTransition(taskInfo, startT, finishT);
        if (mInputMonitor == null) {
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "caption-touch", mContext.getDisplayId());
            mEventReceiver = new EventReceiver(
                    mInputMonitor.getInputChannel(), Looper.myLooper());
        }
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo);
    }

    @Override
    public boolean setupWindowDecorationForTransition(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return false;

        decoration.relayout(taskInfo, startT, finishT);
        return true;
    }

    @Override
    public boolean destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return false;

        decoration.close();
        return true;
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragResizeCallback mDragResizeCallback;

        private int mDragPointerId = -1;
        private boolean mDragActive = false;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragResizeCallback dragResizeCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragResizeCallback = dragResizeCallback;
        }

        @Override
        public void onClick(View v) {
            CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final int id = v.getId();
            if (id == R.id.close_window) {
                WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.removeTask(mTaskToken);
                if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                    mTransitionStarter.startRemoveTransition(wct);
                } else {
                    mSyncQueue.queue(wct);
                }
            } else if (id == R.id.back_button) {
                injectBackKey();
            } else if (id == R.id.caption_handle) {
                decoration.createHandleMenu();
            } else if (id == R.id.desktop_button) {
                mDesktopModeController.setDesktopModeActive(true);
                decoration.closeHandleMenu();
            } else if (id == R.id.fullscreen_button) {
                mDesktopModeController.setDesktopModeActive(false);
                decoration.closeHandleMenu();
                decoration.setButtonVisibility();
            }
        }
        private void injectBackKey() {
            sendBackEvent(KeyEvent.ACTION_DOWN);
            sendBackEvent(KeyEvent.ACTION_UP);
        }

        private void sendBackEvent(int action) {
            final long when = SystemClock.uptimeMillis();
            final KeyEvent ev = new KeyEvent(when, when, action, KeyEvent.KEYCODE_BACK,
                    0 /* repeat */, 0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0 /* scancode */, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);

            ev.setDisplayId(mContext.getDisplay().getDisplayId());
            if (!InputManager.getInstance()
                    .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
                Log.e(TAG, "Inject input event fail");
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int id = v.getId();
            if (id != R.id.caption_handle && id != R.id.caption) {
                return false;
            }
            if (id == R.id.caption_handle || mDragActive) {
                handleEventForMove(e);
            }
            if (e.getAction() != MotionEvent.ACTION_DOWN) {
                return false;
            }
            RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo.isFocused) {
                return false;
            }
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.reorder(mTaskToken, true /* onTop */);
            mSyncQueue.queue(wct);
            return true;
        }

        private void handleEventForMove(MotionEvent e) {
            RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            int windowingMode =  mDesktopModeController
                    .getDisplayAreaWindowingMode(taskInfo.displayId);
            if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                return;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDragActive = true;
                    mDragPointerId  = e.getPointerId(0);
                    mDragResizeCallback.onDragResizeStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    break;
                case MotionEvent.ACTION_MOVE: {
                    int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragResizeCallback.onDragResizeMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    mDragActive = false;
                    int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    int statusBarHeight = mDisplayController.getDisplayLayout(taskInfo.displayId)
                            .stableInsets().top;
                    mDragResizeCallback.onDragResizeEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    if (e.getRawY(dragPointerIdx) <= statusBarHeight
                            && windowingMode == WINDOWING_MODE_FREEFORM) {
                        mDesktopModeController.setDesktopModeActive(false);
                    }
                    break;
                }
            }
        }
    }

    // InputEventReceiver to listen for touch input outside of caption bounds
    private class EventReceiver extends InputEventReceiver {
        EventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            if (event instanceof MotionEvent
                    && ((MotionEvent) event).getActionMasked() == MotionEvent.ACTION_UP) {
                handled = true;
                CaptionWindowDecorViewModel.this.handleMotionEvent((MotionEvent) event);
            }
            finishInputEvent(event, handled);
        }
    }

    // If any input received is outside of caption bounds, turn off handle menu
    private void handleMotionEvent(MotionEvent ev) {
        int size = mWindowDecorByTaskId.size();
        for (int i = 0; i < size; i++) {
            CaptionWindowDecoration decoration = mWindowDecorByTaskId.valueAt(i);
            if (decoration != null) {
                decoration.closeHandleMenuIfNeeded(ev);
            }
        }
    }


    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) return true;
        return DesktopModeStatus.IS_SUPPORTED
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && mDisplayController.getDisplayContext(taskInfo.displayId)
                .getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private class DragStartListenerImpl implements TaskPositioner.DragStartListener{
        @Override
        public void onDragStart(int taskId) {
            mWindowDecorByTaskId.get(taskId).closeHandleMenu();
        }
    }
}
