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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.WindowInsets.Type.statusBars;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.FINAL_FREEFORM_SCALE;
import static com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.FREEFORM_ANIMATION_DURATION;
import static com.android.wm.shell.windowdecor.MoveToDesktopAnimator.DRAG_FREEFORM_SCALE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreen.StageType;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.KeyguardChangeListener;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration.ExclusionRegionListener;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link DesktopModeWindowDecoration}.
 */

public class DesktopModeWindowDecorViewModel implements WindowDecorViewModel {
    private static final String TAG = "DesktopModeWindowDecorViewModel";

    private final DesktopModeWindowDecoration.Factory mDesktopModeWindowDecorFactory;
    private final ActivityTaskManager mActivityTaskManager;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final ShellController mShellController;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private final Optional<DesktopTasksController> mDesktopTasksController;
    private boolean mTransitionDragActive;

    private SparseArray<EventReceiver> mEventReceiversByDisplay = new SparseArray<>();

    private final ExclusionRegionListener mExclusionRegionListener =
            new ExclusionRegionListenerImpl();

    private final SparseArray<DesktopModeWindowDecoration> mWindowDecorByTaskId =
            new SparseArray<>();
    private final DragStartListenerImpl mDragStartListener = new DragStartListenerImpl();
    private final InputMonitorFactory mInputMonitorFactory;
    private TaskOperations mTaskOperations;
    private final Supplier<SurfaceControl.Transaction> mTransactionFactory;
    private final Transitions mTransitions;

    private SplitScreenController mSplitScreenController;

    private MoveToDesktopAnimator mMoveToDesktopAnimator;
    private final Rect mDragToDesktopAnimationStartBounds = new Rect();
    private final DesktopModeKeyguardChangeListener mDesktopModeKeyguardChangeListener =
            new DesktopModeKeyguardChangeListener();
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final DisplayInsetsController mDisplayInsetsController;
    private boolean mInImmersiveMode;

    public DesktopModeWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            ShellController shellController,
            DisplayInsetsController displayInsetsController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopTasksController> desktopTasksController,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer
    ) {
        this(
                context,
                mainHandler,
                mainChoreographer,
                shellInit,
                shellCommandHandler,
                taskOrganizer,
                displayController,
                shellController,
                displayInsetsController,
                syncQueue,
                transitions,
                desktopTasksController,
                new DesktopModeWindowDecoration.Factory(),
                new InputMonitorFactory(),
                SurfaceControl.Transaction::new,
                rootTaskDisplayAreaOrganizer);
    }

    @VisibleForTesting
    DesktopModeWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            ShellController shellController,
            DisplayInsetsController displayInsetsController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopTasksController> desktopTasksController,
            DesktopModeWindowDecoration.Factory desktopModeWindowDecorFactory,
            InputMonitorFactory inputMonitorFactory,
            Supplier<SurfaceControl.Transaction> transactionFactory,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mShellController = shellController;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        mDesktopTasksController = desktopTasksController;
        mShellCommandHandler = shellCommandHandler;
        mDesktopModeWindowDecorFactory = desktopModeWindowDecorFactory;
        mInputMonitorFactory = inputMonitorFactory;
        mTransactionFactory = transactionFactory;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;

        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        mShellController.addKeyguardChangeListener(mDesktopModeKeyguardChangeListener);
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mDisplayInsetsController.addInsetsChangedListener(mContext.getDisplayId(),
                new DesktopModeOnInsetsChangedListener());
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
    }

    @Override
    public void setSplitScreenController(SplitScreenController splitScreenController) {
        mSplitScreenController = splitScreenController;
        mSplitScreenController.registerSplitScreenListener(new SplitScreen.SplitScreenListener() {
            @Override
            public void onTaskStageChanged(int taskId, @StageType int stage, boolean visible) {
                if (visible) {
                    DesktopModeWindowDecoration decor = mWindowDecorByTaskId.get(taskId);
                    if (decor != null && DesktopModeStatus.isEnabled()
                            && decor.mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                        mDesktopTasksController.ifPresent(c -> c.moveToSplit(decor.mTaskInfo));
                    }
                }
            }
        });
    }

    @Override
    public boolean onTaskOpening(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;
        final RunningTaskInfo oldTaskInfo = decoration.mTaskInfo;

        if (taskInfo.displayId != oldTaskInfo.displayId) {
            removeTaskFromEventReceiver(oldTaskInfo.displayId);
            incrementEventReceiverTasks(taskInfo.displayId);
        }
        decoration.relayout(taskInfo);
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (!shouldShowWindowDecor(taskInfo)) {
            if (decoration != null) {
                destroyWindowDecoration(taskInfo);
            }
            return;
        }

        if (decoration == null) {
            createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        } else {
            decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                    false /* shouldSetTaskPositionAndCrop */);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                false /* shouldSetTaskPositionAndCrop */);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
        final int displayId = taskInfo.displayId;
        if (mEventReceiversByDisplay.contains(displayId)) {
            removeTaskFromEventReceiver(displayId);
        }
    }

    private class DesktopModeTouchEventListener extends GestureDetector.SimpleOnGestureListener
            implements View.OnClickListener, View.OnTouchListener, View.OnLongClickListener,
            DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;
        private final GestureDetector mGestureDetector;

        private boolean mIsDragging;
        private boolean mHasLongClicked;
        private boolean mShouldClick;
        private int mDragPointerId = -1;

        private DesktopModeTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            mDragDetector = new DragDetector(this);
            mGestureDetector = new GestureDetector(mContext, this);
        }

        @Override
        public void onClick(View v) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final int id = v.getId();
            if (id == R.id.close_window) {
                if (isTaskInSplitScreen(mTaskId)) {
                    mSplitScreenController.moveTaskToFullscreen(getOtherSplitTask(mTaskId).taskId);
                } else {
                    mTaskOperations.closeTask(mTaskToken);
                }
            } else if (id == R.id.back_button) {
                mTaskOperations.injectBackKey();
            } else if (id == R.id.caption_handle || id == R.id.open_menu_button) {
                if (!decoration.isHandleMenuActive()) {
                    moveTaskToFront(mTaskOrganizer.getRunningTaskInfo(mTaskId));
                    decoration.createHandleMenu();
                } else {
                    decoration.closeHandleMenu();
                }
            } else if (id == R.id.desktop_button) {
                if (mDesktopTasksController.isPresent()) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    // App sometimes draws before the insets from WindowDecoration#relayout have
                    // been added, so they must be added here
                    mWindowDecorByTaskId.get(mTaskId).addCaptionInset(wct);
                    mDesktopTasksController.get().moveToDesktop(decoration, mTaskId, wct);
                    closeOtherSplitTask(mTaskId);
                }
                decoration.closeHandleMenu();
            } else if (id == R.id.fullscreen_button) {
                decoration.closeHandleMenu();
                if (isTaskInSplitScreen(mTaskId)) {
                    mSplitScreenController.moveTaskToFullscreen(mTaskId);
                } else {
                    mDesktopTasksController.ifPresent(c ->
                            c.moveToFullscreen(mTaskId));
                }
            } else if (id == R.id.split_screen_button) {
                decoration.closeHandleMenu();
                mDesktopTasksController.ifPresent(c -> {
                    c.requestSplit(decoration.mTaskInfo);
                });
            } else if (id == R.id.collapse_menu_button) {
                decoration.closeHandleMenu();
            } else if (id == R.id.select_button) {
                if (DesktopModeStatus.IS_DISPLAY_CHANGE_ENABLED) {
                    // TODO(b/278084491): dev option to enable display switching
                    //  remove when select is implemented
                    mDesktopTasksController.ifPresent(c -> c.moveToNextDisplay(mTaskId));
                }
            } else if (id == R.id.maximize_window) {
                if (decoration.isMaximizeMenuActive()) {
                    decoration.closeMaximizeMenu();
                    return;
                }
                final RunningTaskInfo taskInfo = decoration.mTaskInfo;
                mDesktopTasksController.ifPresent(c -> c.toggleDesktopTaskSize(
                        taskInfo, decoration));
                decoration.closeHandleMenu();
            } else if (id == R.id.maximize_menu_maximize_button) {
                final RunningTaskInfo taskInfo = decoration.mTaskInfo;
                mDesktopTasksController.ifPresent(c -> c.toggleDesktopTaskSize(
                        taskInfo, mWindowDecorByTaskId.get(taskInfo.taskId)));
                decoration.closeHandleMenu();
                decoration.closeMaximizeMenu();
            } else if (id == R.id.maximize_menu_snap_left_button) {
                final RunningTaskInfo taskInfo = decoration.mTaskInfo;
                mDesktopTasksController.ifPresent(c -> c.snapToHalfScreen(
                        taskInfo, mWindowDecorByTaskId.get(taskInfo.taskId), SnapPosition.LEFT));
                decoration.closeHandleMenu();
                decoration.closeMaximizeMenu();
            } else if (id == R.id.maximize_menu_snap_right_button) {
                final RunningTaskInfo taskInfo = decoration.mTaskInfo;
                mDesktopTasksController.ifPresent(c -> c.snapToHalfScreen(
                        taskInfo, mWindowDecorByTaskId.get(taskInfo.taskId), SnapPosition.RIGHT));
                decoration.closeHandleMenu();
                decoration.closeMaximizeMenu();
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            final int id = v.getId();
            if (id != R.id.caption_handle && id != R.id.desktop_mode_caption
                    && id != R.id.open_menu_button && id != R.id.close_window
                    && id != R.id.maximize_window) {
                return false;
            }
            moveTaskToFront(mTaskOrganizer.getRunningTaskInfo(mTaskId));

            if (!mHasLongClicked && id != R.id.maximize_window) {
                final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
                decoration.closeMaximizeMenuIfNeeded(e);
            }

            final long eventDuration = e.getEventTime() - e.getDownTime();
            final boolean shouldLongClick = id == R.id.maximize_window && !mIsDragging
                    && !mHasLongClicked && eventDuration >= ViewConfiguration.getLongPressTimeout();
            if (shouldLongClick) {
                v.performLongClick();
                mHasLongClicked = true;
                return true;
            }

            return mDragDetector.onMotionEvent(v, e);
        }

        @Override
        public boolean onLongClick(View v) {
            final int id = v.getId();
            if (id == R.id.maximize_window) {
                final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
                moveTaskToFront(decoration.mTaskInfo);
                if (decoration.isMaximizeMenuActive()) {
                    decoration.closeMaximizeMenu();
                } else {
                    decoration.createMaximizeMenu();
                }
                return true;
            }
            return false;
        }

        private void moveTaskToFront(RunningTaskInfo taskInfo) {
            if (!taskInfo.isFocused) {
                mDesktopTasksController.ifPresent(c -> c.moveTaskToFront(taskInfo));
            }
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if the motion event is handled.
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (DesktopModeStatus.isEnabled()
                    && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            if (mGestureDetector.onTouchEvent(e)) {
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                // If a motion event is cancelled, reset mShouldClick so a click is not accidentally
                // performed.
                mShouldClick = false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getRawX(0),
                            e.getRawY(0));
                    mIsDragging = false;
                    mShouldClick = true;
                    mHasLongClicked = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    mShouldClick = false;
                    final DesktopModeWindowDecoration decoration =
                            mWindowDecorByTaskId.get(mTaskId);
                    // If a decor's resize drag zone is active, don't also try to reposition it.
                    if (decoration.isHandlingDragResize()) break;
                    decoration.closeMaximizeMenu();
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    final Rect newTaskBounds = mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mDesktopTasksController.ifPresent(c -> c.onDragPositioningMove(taskInfo,
                            decoration.mTaskSurface,
                            e.getRawX(dragPointerIdx),
                            newTaskBounds));
                    mIsDragging = true;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final boolean wasDragging = mIsDragging;
                    if (!wasDragging) {
                        if (mShouldClick && v != null && !mHasLongClicked) {
                            v.performClick();
                            mShouldClick = false;
                            return true;
                        }
                        return false;
                    }
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    // Position of the task is calculated by subtracting the raw location of the
                    // motion event (the location of the motion relative to the display) by the
                    // location of the motion event relative to the task's bounds
                    final Point position = new Point(
                            (int) (e.getRawX(dragPointerIdx) - e.getX(dragPointerIdx)),
                            (int) (e.getRawY(dragPointerIdx) - e.getY(dragPointerIdx)));
                    final Rect newTaskBounds = mDragPositioningCallback.onDragPositioningEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mDesktopTasksController.ifPresent(c -> c.onDragPositioningEnd(taskInfo,
                            position,
                            new PointF(e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx)),
                            newTaskBounds));
                    mIsDragging = false;
                    return true;
                }
            }
            return true;
        }

        /**
         * Perform a task size toggle on release of the double-tap, assuming no drag event
         * was handled during the double-tap.
         * @param e The motion event that occurred during the double-tap gesture.
         * @return true if the event should be consumed, false if not
         */
        @Override
        public boolean onDoubleTapEvent(@NonNull MotionEvent e) {
            final int action = e.getActionMasked();
            if (mIsDragging || (action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_CANCEL)) {
                return false;
            }
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            mDesktopTasksController.ifPresent(c -> c.toggleDesktopTaskSize(taskInfo,
                    mWindowDecorByTaskId.get(taskInfo.taskId)));
            return true;
        }
    }

    // InputEventReceiver to listen for touch input outside of caption bounds
    class EventReceiver extends InputEventReceiver {
        private InputMonitor mInputMonitor;
        private int mTasksOnDisplay;
        EventReceiver(InputMonitor inputMonitor, InputChannel channel, Looper looper) {
            super(channel, looper);
            mInputMonitor = inputMonitor;
            mTasksOnDisplay = 1;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            if (event instanceof MotionEvent) {
                handled = true;
                DesktopModeWindowDecorViewModel.this
                        .handleReceivedMotionEvent((MotionEvent) event, mInputMonitor);
            }
            finishInputEvent(event, handled);
        }

        @Override
        public void dispose() {
            if (mInputMonitor != null) {
                mInputMonitor.dispose();
                mInputMonitor = null;
            }
            super.dispose();
        }

        @Override
        public String toString() {
            return "EventReceiver"
                    + "{"
                    + "tasksOnDisplay="
                    + mTasksOnDisplay
                    + "}";
        }

        private void incrementTaskNumber() {
            mTasksOnDisplay++;
        }

        private void decrementTaskNumber() {
            mTasksOnDisplay--;
        }

        private int getTasksOnDisplay() {
            return mTasksOnDisplay;
        }
    }

    /**
     * Check if an EventReceiver exists on a particular display.
     * If it does, increment its task count. Otherwise, create one for that display.
     * @param displayId the display to check against
     */
    private void incrementEventReceiverTasks(int displayId) {
        if (mEventReceiversByDisplay.contains(displayId)) {
            final EventReceiver eventReceiver = mEventReceiversByDisplay.get(displayId);
            eventReceiver.incrementTaskNumber();
        } else {
            createInputChannel(displayId);
        }
    }

    // If all tasks on this display are gone, we don't need to monitor its input.
    private void removeTaskFromEventReceiver(int displayId) {
        if (!mEventReceiversByDisplay.contains(displayId)) return;
        final EventReceiver eventReceiver = mEventReceiversByDisplay.get(displayId);
        if (eventReceiver == null) return;
        eventReceiver.decrementTaskNumber();
        if (eventReceiver.getTasksOnDisplay() == 0) {
            disposeInputChannel(displayId);
        }
    }

    /**
     * Handle MotionEvents relevant to focused task's caption that don't directly touch it
     *
     * @param ev the {@link MotionEvent} received by {@link EventReceiver}
     */
    private void handleReceivedMotionEvent(MotionEvent ev, InputMonitor inputMonitor) {
        final DesktopModeWindowDecoration relevantDecor = getRelevantWindowDecor(ev);
        if (DesktopModeStatus.isEnabled()) {
            if (!mInImmersiveMode && (relevantDecor == null
                    || relevantDecor.mTaskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM
                    || mTransitionDragActive)) {
                handleCaptionThroughStatusBar(ev, relevantDecor);
            }
        }
        handleEventOutsideCaption(ev, relevantDecor);
        // Prevent status bar from reacting to a caption drag.
        if (DesktopModeStatus.isEnabled()) {
            if (mTransitionDragActive) {
                inputMonitor.pilferPointers();
            }
        }
    }

    /**
     * If an UP/CANCEL action is received outside of the caption bounds, close the handle and
     * maximize the menu.
     *
     * @param relevantDecor the window decoration of the focused task's caption. This method only
     *                      handles motion events outside this caption's bounds.
     */
    private void handleEventOutsideCaption(MotionEvent ev,
            DesktopModeWindowDecoration relevantDecor) {
        // Returns if event occurs within caption
        if (relevantDecor == null || relevantDecor.checkTouchEventInCaption(ev)) {
            return;
        }

        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!mTransitionDragActive) {
                relevantDecor.closeHandleMenuIfNeeded(ev);
                relevantDecor.closeMaximizeMenuIfNeeded(ev);
            }
        }
    }


    /**
     * Perform caption actions if not able to through normal means.
     * Turn on desktop mode if handle is dragged below status bar.
     */
    private void handleCaptionThroughStatusBar(MotionEvent ev,
            DesktopModeWindowDecoration relevantDecor) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Begin drag through status bar if applicable.
                if (relevantDecor != null) {
                    mDragToDesktopAnimationStartBounds.set(
                            relevantDecor.mTaskInfo.configuration.windowConfiguration.getBounds());
                    boolean dragFromStatusBarAllowed = false;
                    if (DesktopModeStatus.isEnabled()) {
                        // In proto2 any full screen or multi-window task can be dragged to
                        // freeform.
                        final int windowingMode = relevantDecor.mTaskInfo.getWindowingMode();
                        dragFromStatusBarAllowed = windowingMode == WINDOWING_MODE_FULLSCREEN
                                || windowingMode == WINDOWING_MODE_MULTI_WINDOW;
                    }

                    if (dragFromStatusBarAllowed
                            && relevantDecor.checkTouchEventInFocusedCaptionHandle(ev)) {
                        mTransitionDragActive = true;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (relevantDecor == null) {
                    mMoveToDesktopAnimator = null;
                    mTransitionDragActive = false;
                    return;
                }
                if (mTransitionDragActive) {
                    mTransitionDragActive = false;
                    final int statusBarHeight = getStatusBarHeight(
                            relevantDecor.mTaskInfo.displayId);
                    if (ev.getY() > 2 * statusBarHeight) {
                        if (DesktopModeStatus.isEnabled()) {
                            animateToDesktop(relevantDecor, ev);
                        }
                        mMoveToDesktopAnimator = null;
                        return;
                    } else if (mMoveToDesktopAnimator != null) {
                        mDesktopTasksController.ifPresent(
                                c -> c.cancelDragToDesktop(relevantDecor.mTaskInfo));
                        mMoveToDesktopAnimator = null;
                        return;
                    }
                }
                relevantDecor.checkClickEvent(ev);
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (relevantDecor == null) {
                    return;
                }
                if (mTransitionDragActive) {
                    mDesktopTasksController.ifPresent(
                            c -> c.updateVisualIndicator(
                                    relevantDecor.mTaskInfo,
                                    relevantDecor.mTaskSurface, ev.getX(), ev.getY()));
                    final int statusBarHeight = getStatusBarHeight(
                            relevantDecor.mTaskInfo.displayId);
                    if (ev.getY() > statusBarHeight) {
                        if (mMoveToDesktopAnimator == null) {
                            mMoveToDesktopAnimator = new MoveToDesktopAnimator(
                                    mContext, mDragToDesktopAnimationStartBounds,
                                    relevantDecor.mTaskInfo, relevantDecor.mTaskSurface);
                            mDesktopTasksController.ifPresent(
                                    c -> c.startDragToDesktop(relevantDecor.mTaskInfo,
                                            mMoveToDesktopAnimator, relevantDecor));
                        }
                    }
                    if (mMoveToDesktopAnimator != null) {
                        mMoveToDesktopAnimator.updatePosition(ev);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                mTransitionDragActive = false;
                mMoveToDesktopAnimator = null;
            }
        }
    }

    /**
     * Gets bounds of a scaled window centered relative to the screen bounds
     * @param scale the amount to scale to relative to the Screen Bounds
     */
    private Rect calculateFreeformBounds(int displayId, float scale) {
        final DisplayLayout displayLayout = mDisplayController.getDisplayLayout(displayId);
        final int screenWidth = displayLayout.width();
        final int screenHeight = displayLayout.height();

        final float adjustmentPercentage = (1f - scale) / 2;
        final Rect endBounds = new Rect((int) (screenWidth * adjustmentPercentage),
                (int) (screenHeight * adjustmentPercentage),
                (int) (screenWidth * (adjustmentPercentage + scale)),
                (int) (screenHeight * (adjustmentPercentage + scale)));
        return endBounds;
    }

    /**
     * Blocks relayout until transition is finished and transitions to Desktop
     */
    private void animateToDesktop(DesktopModeWindowDecoration relevantDecor,
            MotionEvent ev) {
        centerAndMoveToDesktopWithAnimation(relevantDecor, ev);
    }

    /**
     * Animates a window to the center, grows to freeform size, and transitions to Desktop Mode.
     * @param relevantDecor the window decor of the task to be animated
     * @param ev the motion event that triggers the animation
     */
    private void centerAndMoveToDesktopWithAnimation(DesktopModeWindowDecoration relevantDecor,
            MotionEvent ev) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(FREEFORM_ANIMATION_DURATION);
        final SurfaceControl sc = relevantDecor.mTaskSurface;
        final Rect endBounds = calculateFreeformBounds(ev.getDisplayId(), DRAG_FREEFORM_SCALE);
        final Transaction t = mTransactionFactory.get();
        final float diffX = endBounds.centerX() - ev.getRawX();
        final float diffY = endBounds.top - ev.getRawY();
        final float startingX = ev.getRawX() - DRAG_FREEFORM_SCALE
                * mDragToDesktopAnimationStartBounds.width() / 2;

        animator.addUpdateListener(animation -> {
            final float animatorValue = (float) animation.getAnimatedValue();
            final float x = startingX + diffX * animatorValue;
            final float y = ev.getRawY() + diffY * animatorValue;
            t.setPosition(sc, x, y);
            t.apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mDesktopTasksController.ifPresent(
                        c -> {
                            c.onDragPositioningEndThroughStatusBar(relevantDecor.mTaskInfo,
                                    calculateFreeformBounds(ev.getDisplayId(),
                                            FINAL_FREEFORM_SCALE));
                        });
            }
        });
        animator.start();
    }

    @Nullable
    private DesktopModeWindowDecoration getRelevantWindowDecor(MotionEvent ev) {
        if (mSplitScreenController != null && mSplitScreenController.isSplitScreenVisible()) {
            // We can't look at focused task here as only one task will have focus.
            DesktopModeWindowDecoration splitTaskDecor = getSplitScreenDecor(ev);
            return splitTaskDecor == null ? getFocusedDecor() : splitTaskDecor;
        } else {
            return getFocusedDecor();
        }
    }

    @Nullable
    private DesktopModeWindowDecoration getSplitScreenDecor(MotionEvent ev) {
        ActivityManager.RunningTaskInfo topOrLeftTask =
                mSplitScreenController.getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT);
        ActivityManager.RunningTaskInfo bottomOrRightTask =
                mSplitScreenController.getTaskInfo(SPLIT_POSITION_BOTTOM_OR_RIGHT);
        if (topOrLeftTask != null && topOrLeftTask.getConfiguration()
                .windowConfiguration.getBounds().contains((int) ev.getX(), (int) ev.getY())) {
            return mWindowDecorByTaskId.get(topOrLeftTask.taskId);
        } else if (bottomOrRightTask != null && bottomOrRightTask.getConfiguration()
                .windowConfiguration.getBounds().contains((int) ev.getX(), (int) ev.getY())) {
            Rect bottomOrRightBounds = bottomOrRightTask.getConfiguration().windowConfiguration
                    .getBounds();
            ev.offsetLocation(-bottomOrRightBounds.left, -bottomOrRightBounds.top);
            return mWindowDecorByTaskId.get(bottomOrRightTask.taskId);
        } else {
            return null;
        }

    }

    @Nullable
    private DesktopModeWindowDecoration getFocusedDecor() {
        final int size = mWindowDecorByTaskId.size();
        DesktopModeWindowDecoration focusedDecor = null;
        // TODO(b/323251951): We need to iterate this in reverse to avoid potentially getting
        //  a decor for a closed task. This is a short term fix while the core issue is addressed,
        //  which involves refactoring the window decor lifecycle to be visibility based.
        for (int i = size - 1; i >= 0; i--) {
            final DesktopModeWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
            if (decor != null && decor.isFocused()) {
                focusedDecor = decor;
                break;
            }
        }
        return focusedDecor;
    }

    private int getStatusBarHeight(int displayId) {
        return mDisplayController.getDisplayLayout(displayId).stableInsets().top;
    }

    private void createInputChannel(int displayId) {
        final InputManager inputManager = mContext.getSystemService(InputManager.class);
        final InputMonitor inputMonitor =
                mInputMonitorFactory.create(inputManager, mContext);
        final EventReceiver eventReceiver = new EventReceiver(inputMonitor,
                inputMonitor.getInputChannel(), Looper.myLooper());
        mEventReceiversByDisplay.put(displayId, eventReceiver);
    }

    private void disposeInputChannel(int displayId) {
        final EventReceiver eventReceiver = mEventReceiversByDisplay.removeReturnOld(displayId);
        if (eventReceiver != null) {
            eventReceiver.dispose();
        }
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) return true;
        if (mSplitScreenController != null
                && mSplitScreenController.isTaskRootOrStageRoot(taskInfo.taskId)) {
            return false;
        }
        if (mDesktopModeKeyguardChangeListener.isKeyguardVisibleAndOccluded()
                && taskInfo.isFocused) {
            return false;
        }
        return DesktopModeStatus.isEnabled()
                && taskInfo.getWindowingMode() != WINDOWING_MODE_PINNED
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && !taskInfo.configuration.windowConfiguration.isAlwaysOnTop()
                && mDisplayController.getDisplayContext(taskInfo.displayId)
                .getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private void createWindowDecoration(
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final DesktopModeWindowDecoration windowDecoration =
                mDesktopModeWindowDecorFactory.create(
                        mContext,
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mMainChoreographer,
                        mSyncQueue,
                        mRootTaskDisplayAreaOrganizer);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);
        windowDecoration.createResizeVeil();

        final DragPositioningCallback dragPositioningCallback;
        final int transitionAreaHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.desktop_mode_transition_area_height);
        if (!DesktopModeStatus.isVeiledResizeEnabled()) {
            dragPositioningCallback =  new FluidResizeTaskPositioner(
                    mTaskOrganizer, mTransitions, windowDecoration, mDisplayController,
                    mDragStartListener, mTransactionFactory, transitionAreaHeight);
            windowDecoration.setTaskDragResizer(
                    (FluidResizeTaskPositioner) dragPositioningCallback);
        } else {
            dragPositioningCallback =  new VeiledResizeTaskPositioner(
                    mTaskOrganizer, windowDecoration, mDisplayController,
                    mDragStartListener, mTransitions, transitionAreaHeight);
            windowDecoration.setTaskDragResizer(
                    (VeiledResizeTaskPositioner) dragPositioningCallback);
        }

        final DesktopModeTouchEventListener touchEventListener =
                new DesktopModeTouchEventListener(taskInfo, dragPositioningCallback);

        windowDecoration.setCaptionListeners(
                touchEventListener, touchEventListener, touchEventListener);
        windowDecoration.setExclusionRegionListener(mExclusionRegionListener);
        windowDecoration.setDragPositioningCallback(dragPositioningCallback);
        windowDecoration.setDragDetector(touchEventListener.mDragDetector);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */, false /* shouldSetTaskPositionAndCrop */);
        incrementEventReceiverTasks(taskInfo.displayId);
    }

    private RunningTaskInfo getOtherSplitTask(int taskId) {
        @SplitPosition int remainingTaskPosition = mSplitScreenController
                .getSplitPosition(taskId) == SPLIT_POSITION_BOTTOM_OR_RIGHT
                ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT;
        return mSplitScreenController.getTaskInfo(remainingTaskPosition);
    }

    private void closeOtherSplitTask(int taskId) {
        if (isTaskInSplitScreen(taskId)) {
            mTaskOperations.closeTask(getOtherSplitTask(taskId).token);
        }
    }

    private boolean isTaskInSplitScreen(int taskId) {
        return mSplitScreenController != null
                && mSplitScreenController.isTaskInSplitScreen(taskId);
    }

    private void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + "DesktopModeWindowDecorViewModel");
        pw.println(innerPrefix + "DesktopModeStatus=" + DesktopModeStatus.isEnabled());
        pw.println(innerPrefix + "mTransitionDragActive=" + mTransitionDragActive);
        pw.println(innerPrefix + "mEventReceiversByDisplay=" + mEventReceiversByDisplay);
        pw.println(innerPrefix + "mWindowDecorByTaskId=" + mWindowDecorByTaskId);
    }

    private class DragStartListenerImpl
            implements DragPositioningCallbackUtility.DragStartListener {
        @Override
        public void onDragStart(int taskId) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            decoration.closeHandleMenu();
        }
    }

    static class InputMonitorFactory {
        InputMonitor create(InputManager inputManager, Context context) {
            return inputManager.monitorGestureInput("caption-touch", context.getDisplayId());
        }
    }

    private class ExclusionRegionListenerImpl
            implements ExclusionRegionListener {

        @Override
        public void onExclusionRegionChanged(int taskId, Region region) {
            mDesktopTasksController.ifPresent(d -> d.onExclusionRegionChanged(taskId, region));
        }

        @Override
        public void onExclusionRegionDismissed(int taskId) {
            mDesktopTasksController.ifPresent(d -> d.removeExclusionRegionForTask(taskId));
        }
    }

    static class DesktopModeKeyguardChangeListener implements KeyguardChangeListener {
        private boolean mIsKeyguardVisible;
        private boolean mIsKeyguardOccluded;

        @Override
        public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
                boolean animatingDismiss) {
            mIsKeyguardVisible = visible;
            mIsKeyguardOccluded = occluded;
        }

        public boolean isKeyguardVisibleAndOccluded() {
            return mIsKeyguardVisible && mIsKeyguardOccluded;
        }
    }

    @VisibleForTesting
    class DesktopModeOnInsetsChangedListener implements
            DisplayInsetsController.OnInsetsChangedListener {
        @Override
        public void insetsChanged(InsetsState insetsState) {
            for (int i = 0; i < insetsState.sourceSize(); i++) {
                final InsetsSource source = insetsState.sourceAt(i);
                if (source.getType() != statusBars()) {
                    continue;
                }

                final DesktopModeWindowDecoration decor = getFocusedDecor();
                if (decor == null) {
                    return;
                }
                // If status bar inset is visible, top task is not in immersive mode
                final boolean inImmersiveMode = !source.isVisible();
                // Calls WindowDecoration#relayout if decoration visibility needs to be updated
                if (inImmersiveMode != mInImmersiveMode) {
                    decor.relayout(decor.mTaskInfo);
                    mInImmersiveMode = inImmersiveMode;
                }

                return;
            }
        }
    }
}


