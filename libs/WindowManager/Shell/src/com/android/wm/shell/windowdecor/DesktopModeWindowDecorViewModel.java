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

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.DRAG_FREEFORM_SCALE;
import static com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.FINAL_FREEFORM_SCALE;
import static com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler.FREEFORM_ANIMATION_DURATION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration.TaskCornersListener;

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
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Context mContext;
    private final Handler mMainHandler;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private final Optional<DesktopModeController> mDesktopModeController;
    private final Optional<DesktopTasksController> mDesktopTasksController;
    private boolean mTransitionDragActive;

    private SparseArray<EventReceiver> mEventReceiversByDisplay = new SparseArray<>();

    private final TaskCornersListener mCornersListener = new TaskCornersListenerImpl();

    private final SparseArray<DesktopModeWindowDecoration> mWindowDecorByTaskId =
            new SparseArray<>();
    private final DragStartListenerImpl mDragStartListener = new DragStartListenerImpl();
    private final InputMonitorFactory mInputMonitorFactory;
    private TaskOperations mTaskOperations;
    private final Supplier<SurfaceControl.Transaction> mTransactionFactory;
    private final Transitions mTransitions;

    private SplitScreenController mSplitScreenController;

    private ValueAnimator mDragToDesktopValueAnimator;
    private final Rect mDragToDesktopAnimationStartBounds = new Rect();
    private boolean mDragToDesktopAnimationStarted;

    public DesktopModeWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController) {
        this(
                context,
                mainHandler,
                mainChoreographer,
                taskOrganizer,
                displayController,
                syncQueue,
                transitions,
                desktopModeController,
                desktopTasksController,
                new DesktopModeWindowDecoration.Factory(),
                new InputMonitorFactory(),
                SurfaceControl.Transaction::new);
    }

    @VisibleForTesting
    DesktopModeWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController,
            DesktopModeWindowDecoration.Factory desktopModeWindowDecorFactory,
            InputMonitorFactory inputMonitorFactory,
            Supplier<SurfaceControl.Transaction> transactionFactory) {
        mContext = context;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        mDesktopModeController = desktopModeController;
        mDesktopTasksController = desktopTasksController;

        mDesktopModeWindowDecorFactory = desktopModeWindowDecorFactory;
        mInputMonitorFactory = inputMonitorFactory;
        mTransactionFactory = transactionFactory;
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
    }

    @Override
    public void setSplitScreenController(SplitScreenController splitScreenController) {
        mSplitScreenController = splitScreenController;
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
    public void onTransitionReady(
            @NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull TransitionInfo.Change change) {
        if (change.getMode() == WindowManager.TRANSIT_CHANGE
                && (info.getType() == Transitions.TRANSIT_ENTER_DESKTOP_MODE
                || info.getType() == Transitions.TRANSIT_CANCEL_ENTERING_DESKTOP_MODE
                || info.getType() == Transitions.TRANSIT_EXIT_DESKTOP_MODE
                || info.getType() == Transitions.TRANSIT_DESKTOP_MODE_TOGGLE_RESIZE)) {
            mWindowDecorByTaskId.get(change.getTaskInfo().taskId)
                    .addTransitionPausingRelayout(transition);
        }
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing) {
        for (int i = 0; i < mWindowDecorByTaskId.size(); i++) {
            final DesktopModeWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
            decor.mergeTransitionPausingRelayout(merged, playing);
        }
    }

    @Override
    public void onTransitionFinished(@NonNull IBinder transition) {
        for (int i = 0; i < mWindowDecorByTaskId.size(); i++) {
            final DesktopModeWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
            decor.removeTransitionPausingRelayout(transition);
        }
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
            decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */);
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
            implements View.OnClickListener, View.OnTouchListener, DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;
        private final GestureDetector mGestureDetector;

        private boolean mIsDragging;
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
            if (id == R.id.close_window || id == R.id.close_button) {
                mTaskOperations.closeTask(mTaskToken);
                if (mSplitScreenController != null
                        && mSplitScreenController.isSplitScreenVisible()) {
                    int remainingTaskPosition = mTaskId == mSplitScreenController
                            .getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT).taskId
                            ? SPLIT_POSITION_BOTTOM_OR_RIGHT : SPLIT_POSITION_TOP_OR_LEFT;
                    ActivityManager.RunningTaskInfo remainingTask = mSplitScreenController
                            .getTaskInfo(remainingTaskPosition);
                    mSplitScreenController.moveTaskToFullscreen(remainingTask.taskId);
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
                mDesktopModeController.ifPresent(c -> c.setDesktopModeActive(true));
                if (mDesktopTasksController.isPresent()) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    // App sometimes draws before the insets from WindowDecoration#relayout have
                    // been added, so they must be added here
                    mWindowDecorByTaskId.get(mTaskId).addCaptionInset(wct);
                    mDesktopTasksController.get().moveToDesktop(mTaskId, wct);
                }
                decoration.closeHandleMenu();
            } else if (id == R.id.fullscreen_button) {
                mDesktopModeController.ifPresent(c -> c.setDesktopModeActive(false));
                mDesktopTasksController.ifPresent(c -> c.moveToFullscreen(mTaskId));
                decoration.closeHandleMenu();
            } else if (id == R.id.collapse_menu_button) {
                decoration.closeHandleMenu();
            } else if (id == R.id.select_button) {
                if (DesktopModeStatus.IS_DISPLAY_CHANGE_ENABLED) {
                    // TODO(b/278084491): dev option to enable display switching
                    //  remove when select is implemented
                    mDesktopTasksController.ifPresent(c -> c.moveToNextDisplay(mTaskId));
                    decoration.closeHandleMenu();
                }
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            final int id = v.getId();
            if (id != R.id.caption_handle && id != R.id.desktop_mode_caption
                    && id != R.id.open_menu_button && id != R.id.close_window) {
                return false;
            }
            moveTaskToFront(mTaskOrganizer.getRunningTaskInfo(mTaskId));
            return mDragDetector.onMotionEvent(v, e);
        }

        private void moveTaskToFront(RunningTaskInfo taskInfo) {
            if (!taskInfo.isFocused) {
                mDesktopTasksController.ifPresent(c -> c.moveTaskToFront(taskInfo));
                mDesktopModeController.ifPresent(c -> c.moveTaskToFront(taskInfo));
            }
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if the motion event is handled.
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (DesktopModeStatus.isProto2Enabled()
                    && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            if (DesktopModeStatus.isProto1Enabled() && mDesktopModeController.isPresent()
                    && mDesktopModeController.get().getDisplayAreaWindowingMode(taskInfo.displayId)
                    == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            if (mGestureDetector.onTouchEvent(e)) {
                return true;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getRawX(0),
                            e.getRawY(0));
                    mIsDragging = false;
                    mShouldClick = true;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    final DesktopModeWindowDecoration decoration =
                            mWindowDecorByTaskId.get(mTaskId);
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDesktopTasksController.ifPresent(c -> c.onDragPositioningMove(taskInfo,
                            decoration.mTaskSurface, e.getRawY(dragPointerIdx)));
                    mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mIsDragging = true;
                    mShouldClick = false;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final boolean wasDragging = mIsDragging;
                    if (!wasDragging) {
                        if (mShouldClick && v != null) {
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
                    mDragPositioningCallback.onDragPositioningEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mDesktopTasksController.ifPresent(c -> c.onDragPositioningEnd(taskInfo,
                            position, e.getRawY(), mWindowDecorByTaskId.get(mTaskId)));
                    mIsDragging = false;
                    return true;
                }
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            mDesktopTasksController.ifPresent(c -> {
                c.toggleDesktopTaskSize(taskInfo, mWindowDecorByTaskId.get(taskInfo.taskId));
            });
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
        if (DesktopModeStatus.isProto2Enabled()) {
            if (relevantDecor == null
                    || relevantDecor.mTaskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM
                    || mTransitionDragActive) {
                handleCaptionThroughStatusBar(ev, relevantDecor);
            }
        }
        handleEventOutsideFocusedCaption(ev, relevantDecor);
        // Prevent status bar from reacting to a caption drag.
        if (DesktopModeStatus.isProto2Enabled()) {
            if (mTransitionDragActive) {
                inputMonitor.pilferPointers();
            }
        } else if (DesktopModeStatus.isProto1Enabled()) {
            if (mTransitionDragActive && !DesktopModeStatus.isActive(mContext)) {
                inputMonitor.pilferPointers();
            }
        }
    }

    // If an UP/CANCEL action is received outside of caption bounds, turn off handle menu
    private void handleEventOutsideFocusedCaption(MotionEvent ev,
            DesktopModeWindowDecoration relevantDecor) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (relevantDecor == null) {
                return;
            }

            if (!mTransitionDragActive) {
                relevantDecor.closeHandleMenuIfNeeded(ev);
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
                    if (DesktopModeStatus.isProto2Enabled()) {
                        // In proto2 any full screen or multi-window task can be dragged to
                        // freeform.
                        final int windowingMode = relevantDecor.mTaskInfo.getWindowingMode();
                        dragFromStatusBarAllowed = windowingMode == WINDOWING_MODE_FULLSCREEN
                                || windowingMode == WINDOWING_MODE_MULTI_WINDOW;
                    }

                    if (dragFromStatusBarAllowed && relevantDecor.checkTouchEventInHandle(ev)) {
                        mTransitionDragActive = true;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (relevantDecor == null) {
                    mDragToDesktopAnimationStarted = false;
                    mTransitionDragActive = false;
                    return;
                }
                if (mTransitionDragActive) {
                    mTransitionDragActive = false;
                    final int statusBarHeight = getStatusBarHeight(
                            relevantDecor.mTaskInfo.displayId);
                    if (ev.getY() > 2 * statusBarHeight) {
                        if (DesktopModeStatus.isProto2Enabled()) {
                            animateToDesktop(relevantDecor, ev);
                        } else if (DesktopModeStatus.isProto1Enabled()) {
                            mDesktopModeController.ifPresent(c -> c.setDesktopModeActive(true));
                        }
                        mDragToDesktopAnimationStarted = false;
                        return;
                    } else if (mDragToDesktopAnimationStarted) {
                        Point position = new Point((int) ev.getX(), (int) ev.getY());
                        relevantDecor.incrementRelayoutBlock();
                        mDesktopTasksController.ifPresent(
                                c -> c.cancelMoveToFreeform(relevantDecor.mTaskInfo, position));
                        mDragToDesktopAnimationStarted = false;
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
                            c -> c.onDragPositioningMoveThroughStatusBar(
                                    relevantDecor.mTaskInfo,
                                    relevantDecor.mTaskSurface, ev.getY()));
                    final int statusBarHeight = getStatusBarHeight(
                            relevantDecor.mTaskInfo.displayId);
                    if (ev.getY() > statusBarHeight) {
                        if (!mDragToDesktopAnimationStarted) {
                            mDragToDesktopAnimationStarted = true;
                            mDesktopTasksController.ifPresent(
                                    c -> c.moveToFreeform(relevantDecor.mTaskInfo,
                                            mDragToDesktopAnimationStartBounds));
                            startAnimation(relevantDecor);
                        }
                    }
                    if (mDragToDesktopAnimationStarted) {
                        Transaction t = mTransactionFactory.get();
                        float width = (float) mDragToDesktopValueAnimator.getAnimatedValue()
                                * mDragToDesktopAnimationStartBounds.width();
                        float x = ev.getX() - (width / 2);
                        t.setPosition(relevantDecor.mTaskSurface, x, ev.getY());
                        t.apply();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                mTransitionDragActive = false;
                mDragToDesktopAnimationStarted = false;
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
        relevantDecor.incrementRelayoutBlock();
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
        final float diffX = endBounds.centerX() - ev.getX();
        final float diffY = endBounds.top - ev.getY();
        final float startingX = ev.getX() - DRAG_FREEFORM_SCALE
                * mDragToDesktopAnimationStartBounds.width() / 2;

        animator.addUpdateListener(animation -> {
            final float animatorValue = (float) animation.getAnimatedValue();
            final float x = startingX + diffX * animatorValue;
            final float y = ev.getY() + diffY * animatorValue;
            t.setPosition(sc, x, y);
            t.apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mDesktopTasksController.ifPresent(
                        c -> c.onDragPositioningEndThroughStatusBar(
                                relevantDecor.mTaskInfo,
                                calculateFreeformBounds(ev.getDisplayId(), FINAL_FREEFORM_SCALE)));
            }
        });
        animator.start();
    }

    private void startAnimation(@NonNull DesktopModeWindowDecoration focusedDecor) {
        mDragToDesktopValueAnimator = ValueAnimator.ofFloat(1f, DRAG_FREEFORM_SCALE);
        mDragToDesktopValueAnimator.setDuration(FREEFORM_ANIMATION_DURATION);
        final Transaction t = mTransactionFactory.get();
        mDragToDesktopValueAnimator.addUpdateListener(animation -> {
            final float animatorValue = (float) animation.getAnimatedValue();
            SurfaceControl sc = focusedDecor.mTaskSurface;
            t.setScale(sc, animatorValue, animatorValue);
            t.apply();
        });

        mDragToDesktopValueAnimator.start();
    }

    @Nullable
    private DesktopModeWindowDecoration getRelevantWindowDecor(MotionEvent ev) {
        if (mSplitScreenController != null && mSplitScreenController.isSplitScreenVisible()) {
            // We can't look at focused task here as only one task will have focus.
            return getSplitScreenDecor(ev);
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
        for (int i = 0; i < size; i++) {
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
        return DesktopModeStatus.isProto2Enabled()
                && taskInfo.getWindowingMode() != WINDOWING_MODE_PINNED
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
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
                        mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);
        windowDecoration.createResizeVeil();

        final DragPositioningCallback dragPositioningCallback = createDragPositioningCallback(
                windowDecoration, taskInfo);
        final DesktopModeTouchEventListener touchEventListener =
                new DesktopModeTouchEventListener(taskInfo, dragPositioningCallback);

        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setCornersListener(mCornersListener);
        windowDecoration.setDragPositioningCallback(dragPositioningCallback);
        windowDecoration.setDragDetector(touchEventListener.mDragDetector);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */);
        incrementEventReceiverTasks(taskInfo.displayId);
    }
    private DragPositioningCallback createDragPositioningCallback(
            @NonNull DesktopModeWindowDecoration windowDecoration,
            @NonNull RunningTaskInfo taskInfo) {
        final int screenWidth = mDisplayController.getDisplayLayout(taskInfo.displayId).width();
        final Rect disallowedAreaForEndBounds;
        if (DesktopModeStatus.isProto2Enabled()) {
            disallowedAreaForEndBounds = new Rect(0, 0, screenWidth,
                    getStatusBarHeight(taskInfo.displayId));
        } else {
            disallowedAreaForEndBounds = null;
        }
        if (!DesktopModeStatus.isVeiledResizeEnabled()) {
            return new FluidResizeTaskPositioner(mTaskOrganizer, windowDecoration,
                    mDisplayController, disallowedAreaForEndBounds, mDragStartListener,
                    mTransactionFactory);
        } else {
            return new VeiledResizeTaskPositioner(mTaskOrganizer, windowDecoration,
                    mDisplayController, disallowedAreaForEndBounds, mDragStartListener,
                    mTransitions);
        }
    }

    private class DragStartListenerImpl
            implements DragPositioningCallbackUtility.DragStartListener {
        @Override
        public void onDragStart(int taskId) {
            mWindowDecorByTaskId.get(taskId).closeHandleMenu();
        }
    }

    static class InputMonitorFactory {
        InputMonitor create(InputManager inputManager, Context context) {
            return inputManager.monitorGestureInput("caption-touch", context.getDisplayId());
        }
    }

    private class TaskCornersListenerImpl
            implements DesktopModeWindowDecoration.TaskCornersListener {

        @Override
        public void onTaskCornersChanged(int taskId, Region corner) {
            mDesktopModeController.ifPresent(d -> d.onTaskCornersChanged(taskId, corner));
            mDesktopTasksController.ifPresent(d -> d.onTaskCornersChanged(taskId, corner));
        }

        @Override
        public void onTaskCornersRemoved(int taskId) {
            mDesktopModeController.ifPresent(d -> d.removeCornersForTask(taskId));
            mDesktopTasksController.ifPresent(d -> d.removeCornersForTask(taskId));
        }
    }
}


