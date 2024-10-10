/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.PackageManager.FEATURE_PC;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.view.WindowManager.TRANSIT_CHANGE;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.extension.TaskInfoKt;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link CaptionWindowDecoration}.
 */
public class CaptionWindowDecorViewModel implements WindowDecorViewModel {
    private static final String TAG = "CaptionWindowDecorViewModel";

    private final ShellTaskOrganizer mTaskOrganizer;
    private final IWindowManager mWindowManager;
    private final Context mContext;
    private final Handler mMainHandler;
    private final @ShellBackgroundThread ShellExecutor mBgExecutor;
    private final ShellExecutor mMainExecutor;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final Transitions mTransitions;
    private final Region mExclusionRegion = Region.obtain();
    private final InputManager mInputManager;
    private TaskOperations mTaskOperations;

    /**
     * Whether to pilfer the next motion event to send cancellations to the windows below.
     * Useful when the caption window is spy and the gesture should be handled by the system
     * instead of by the app for their custom header content.
     */
    private boolean mShouldPilferCaptionEvents;

    private final SparseArray<CaptionWindowDecoration> mWindowDecorByTaskId = new SparseArray<>();

    private final ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region systemGestureExclusionUnrestricted) {
                    if (mContext.getDisplayId() != displayId) {
                        return;
                    }
                    mMainExecutor.execute(() -> {
                        mExclusionRegion.set(systemGestureExclusion);
                    });
                }
            };

    public CaptionWindowDecorViewModel(
            Context context,
            Handler mainHandler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            ShellExecutor shellExecutor,
            Choreographer mainChoreographer,
            IWindowManager windowManager,
            ShellInit shellInit,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            SyncTransactionQueue syncQueue,
            Transitions transitions) {
        mContext = context;
        mMainExecutor = shellExecutor;
        mMainHandler = mainHandler;
        mBgExecutor = bgExecutor;
        mWindowManager = windowManager;
        mMainChoreographer = mainChoreographer;
        mTaskOrganizer = taskOrganizer;
        mDisplayController = displayController;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) {
            mTaskOperations = new TaskOperations(null, mContext, mSyncQueue);
        }
        mInputManager = mContext.getSystemService(InputManager.class);

        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        try {
            mWindowManager.registerSystemGestureExclusionListener(mGestureExclusionListener,
                    mContext.getDisplayId());
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register window manager callbacks", e);
        }
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
    }

    @Override
    public void setSplitScreenController(SplitScreenController splitScreenController) {}

    @Override
    public boolean onTaskOpening(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        if (!shouldShowWindowDecor(taskInfo)) return false;
        createWindowDecoration(taskInfo, taskSurface, startT, finishT);
        return true;
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

        if (decoration == null) return;

        if (!shouldShowWindowDecor(taskInfo)) {
            destroyWindowDecoration(taskInfo);
            return;
        }

        decoration.relayout(taskInfo);
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        // A task vanishing doesn't necessarily mean the task was closed, it could also mean its
        // windowing mode changed. We're only interested in closing tasks so checking whether
        // its info still exists in the task organizer is one way to disambiguate.
        final boolean closed = mTaskOrganizer.getRunningTaskInfo(taskInfo.taskId) == null;
        if (closed) {
            // Destroying the window decoration is usually handled when a TRANSIT_CLOSE transition
            // changes happen, but there are certain cases in which closing tasks aren't included
            // in transitions, such as when a non-visible task is closed. See b/296921167.
            // Destroy the decoration here in case the lack of transition missed it.
            destroyWindowDecoration(taskInfo);
        }
    }

    @Override
    public void onTaskChanging(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);

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
                    false /* setTaskCropAndPosition */);
        }
    }

    @Override
    public void onTaskClosing(
            RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.relayout(taskInfo, startT, finishT, false /* applyStartTransactionOnDraw */,
                false /* setTaskCropAndPosition */);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final CaptionWindowDecoration decoration =
                mWindowDecorByTaskId.removeReturnOld(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
    }

    private boolean shouldShowWindowDecor(RunningTaskInfo taskInfo) {
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            return true;
        }
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_PINNED) {
            return false;
        }
        if (taskInfo.getActivityType() != ACTIVITY_TYPE_STANDARD) {
            return false;
        }
        final DisplayAreaInfo rootDisplayAreaInfo =
                mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId);
        if (rootDisplayAreaInfo != null) {
            return rootDisplayAreaInfo.configuration.windowConfiguration.getWindowingMode()
                    == WINDOWING_MODE_FREEFORM;
        }

        // It is possible that the rootDisplayAreaInfo is null when a task appears soon enough after
        // a new display shows up, because TDA may appear after task appears in WM shell. Instead of
        // fixing the synchronization issues, let's use other signals to "guess" the answer. It is
        // OK in this context because no other captions other than the legacy developer option
        // freeform and Kingyo/CF PC may use this class. WM shell should have full control over the
        // condition where captions should show up in all new cases such as desktop mode, for which
        // we should use different window decor view models. Ultimately Kingyo/CF PC may need to
        // spin up their own window decor view model when they start to care about multiple
        // displays.
        if (isPc()) {
            return true;
        }
        return taskInfo.displayId != Display.DEFAULT_DISPLAY
                && forcesDesktopModeOnExternalDisplays();
    }

    private void createWindowDecoration(
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final CaptionWindowDecoration oldDecoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (oldDecoration != null) {
            // close the old decoration if it exists to avoid two window decorations being added
            oldDecoration.close();
        }
        final CaptionWindowDecoration windowDecoration =
                new CaptionWindowDecoration(
                        mContext,
                        mContext.createContextAsUser(UserHandle.of(taskInfo.userId), 0 /* flags */),
                        mDisplayController,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mBgExecutor,
                        mMainChoreographer,
                        mSyncQueue);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        final FluidResizeTaskPositioner taskPositioner =
                new FluidResizeTaskPositioner(mTaskOrganizer, mTransitions, windowDecoration,
                        mDisplayController);
        final CaptionTouchEventListener touchEventListener =
                new CaptionTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setCaptionListeners(touchEventListener, touchEventListener);
        windowDecoration.setDragPositioningCallback(taskPositioner);
        windowDecoration.setTaskDragResizer(taskPositioner);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */, false /* setTaskCropAndPosition */);
    }

    private class CaptionTouchEventListener implements
            View.OnClickListener, View.OnTouchListener, DragDetector.MotionEventHandler {

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mDragDetector;
        private final int mDisplayId;

        private int mDragPointerId = -1;
        private boolean mIsDragging;

        private CaptionTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            mDragDetector = new DragDetector(this, 0 /* holdToDragMinDurationMs */,
                    ViewConfiguration.get(mContext).getScaledTouchSlop());
            mDisplayId = taskInfo.displayId;
        }

        @Override
        public void onClick(View v) {
            final int id = v.getId();
            if (id == R.id.close_window) {
                mTaskOperations.closeTask(mTaskToken);
            } else if (id == R.id.back_button) {
                mTaskOperations.injectBackKey(mDisplayId);
            } else if (id == R.id.minimize_window) {
                mTaskOperations.minimizeTask(mTaskToken);
            } else if (id == R.id.maximize_window) {
                RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                final DisplayAreaInfo rootDisplayAreaInfo =
                        mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(taskInfo.displayId);
                mTaskOperations.maximizeTask(taskInfo,
                        rootDisplayAreaInfo.configuration.windowConfiguration.getWindowingMode());
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (v.getId() != R.id.caption) {
                return false;
            }
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
                if (!taskInfo.isFocused) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    wct.reorder(mTaskToken, true /* onTop */, true /* includingParents */);
                    mSyncQueue.queue(wct);
                }
            }
            final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);

            final int actionMasked = e.getActionMasked();
            final boolean isDown = actionMasked == MotionEvent.ACTION_DOWN;
            final boolean isUpOrCancel = actionMasked == MotionEvent.ACTION_CANCEL
                    || actionMasked == MotionEvent.ACTION_UP;
            if (isDown) {
                final boolean downInCustomizableCaptionRegion =
                        decoration.checkTouchEventInCustomizableRegion(e);
                final boolean downInExclusionRegion = mExclusionRegion.contains(
                        (int) e.getRawX(), (int) e.getRawY());
                final boolean isTransparentCaption =
                        TaskInfoKt.isTransparentCaptionBarAppearance(decoration.mTaskInfo);
                // MotionEvent's coordinates are relative to view, we want location in window
                // to offset position relative to caption as a whole.
                int[] viewLocation = new int[2];
                v.getLocationInWindow(viewLocation);
                final boolean isResizeEvent = decoration.shouldResizeListenerHandleEvent(e,
                        new Point(viewLocation[0], viewLocation[1]));
                // The caption window may be a spy window when the caption background is
                // transparent, which means events will fall through to the app window. Make
                // sure to cancel these events if they do not happen in the intersection of the
                // customizable region and what the app reported as exclusion areas, because
                // the drag-move or other caption gestures should take priority outside those
                // regions.
                mShouldPilferCaptionEvents = !(downInCustomizableCaptionRegion
                        && downInExclusionRegion && isTransparentCaption) && !isResizeEvent;
            }

            if (!mShouldPilferCaptionEvents) {
                // The event will be handled by a window below or pilfered by resize handler.
                return false;
            }
            // Otherwise pilfer so that windows below receive cancellations for this gesture, and
            // continue normal handling as a caption gesture.
            if (mInputManager != null) {
                // TODO(b/352127475): Only pilfer once per gesture
                mInputManager.pilferPointers(v.getViewRootImpl().getInputToken());
            }
            if (isUpOrCancel) {
                // Gesture is finished, reset state.
                mShouldPilferCaptionEvents = false;
            }
            return mDragDetector.onMotionEvent(e);
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if a drag is happening; or {@code false} if it is not
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final RunningTaskInfo taskInfo = mTaskOrganizer.getRunningTaskInfo(mTaskId);
            if (taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mDragPointerId = e.getPointerId(0);
                    mDragPositioningCallback.onDragPositioningStart(
                            0 /* ctrlType */, e.getRawX(0), e.getRawY(0));
                    mIsDragging = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final CaptionWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
                    // If a decor's resize drag zone is active, don't also try to reposition it.
                    if (decoration.isHandlingDragResize()) break;
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mIsDragging = true;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    final Rect newTaskBounds = mDragPositioningCallback.onDragPositioningEnd(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(newTaskBounds,
                            mWindowDecorByTaskId.get(mTaskId).calculateValidDragArea());
                    if (newTaskBounds != taskInfo.configuration.windowConfiguration.getBounds()) {
                        final WindowContainerTransaction wct = new WindowContainerTransaction();
                        wct.setBounds(taskInfo.token, newTaskBounds);
                        mTransitions.startTransition(TRANSIT_CHANGE, wct, null);
                    }
                    final boolean wasDragging = mIsDragging;
                    mIsDragging = false;
                    return wasDragging;
                }
            }
            return true;
        }
    }

    /**
     * Returns if this device is a PC.
     */
    private boolean isPc() {
        return mContext.getPackageManager().hasSystemFeature(FEATURE_PC);
    }

    private boolean forcesDesktopModeOnExternalDisplays() {
        final ContentResolver resolver = mContext.getContentResolver();
        return Settings.Global.getInt(resolver,
                DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0) != 0;
    }
}