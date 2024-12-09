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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_HOVER_ENTER;
import static android.view.MotionEvent.ACTION_HOVER_EXIT;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowInsets.Type.statusBars;

import static com.android.internal.jank.Cuj.CUJ_DESKTOP_MODE_ENTER_MODE_APP_HANDLE_MENU;
import static com.android.window.flags.Flags.enableDisplayFocusInShellTransitions;
import static com.android.wm.shell.compatui.AppCompatUtils.isTopActivityExemptFromDesktopWindowing;
import static com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod;
import static com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger;
import static com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.IndicatorType.TO_FULLSCREEN_INDICATOR;
import static com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_LEFT_INDICATOR;
import static com.android.wm.shell.desktopmode.DesktopModeVisualIndicator.IndicatorType.TO_SPLIT_RIGHT_INDICATOR;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE;
import static com.android.wm.shell.shared.multiinstance.ManageWindowsViewContainer.MANAGE_WINDOWS_MINIMUM_INSTANCES;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.window.DesktopModeFlags;
import android.window.TaskSnapshot;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.Cuj;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.protolog.ProtoLog;
import com.android.window.flags.Flags;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.apptoweb.AppToWebGenericLinksParser;
import com.android.wm.shell.apptoweb.AssistContentRequester;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.MultiInstanceHelper;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.CompatUIController;
import com.android.wm.shell.desktopmode.DesktopActivityOrientationChangeHandler;
import com.android.wm.shell.desktopmode.DesktopImmersiveController;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger;
import com.android.wm.shell.desktopmode.DesktopModeUiEventLogger;
import com.android.wm.shell.desktopmode.DesktopModeUiEventLogger.DesktopUiEventEnum;
import com.android.wm.shell.desktopmode.DesktopModeUtils;
import com.android.wm.shell.desktopmode.DesktopModeVisualIndicator;
import com.android.wm.shell.desktopmode.DesktopRepository;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition;
import com.android.wm.shell.desktopmode.DesktopTasksLimiter;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.desktopmode.DesktopWallpaperActivity;
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository;
import com.android.wm.shell.desktopmode.common.ToggleTaskSizeInteraction;
import com.android.wm.shell.desktopmode.common.ToggleTaskSizeUtilsKt;
import com.android.wm.shell.desktopmode.education.AppHandleEducationController;
import com.android.wm.shell.desktopmode.education.AppToWebEducationController;
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter;
import com.android.wm.shell.shared.FocusTransitionListener;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource;
import com.android.wm.shell.shared.split.SplitScreenConstants.SplitPosition;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.KeyguardChangeListener;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.FocusTransitionObserver;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration.ExclusionRegionListener;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.extension.InsetsStateKt;
import com.android.wm.shell.windowdecor.extension.TaskInfoKt;
import com.android.wm.shell.windowdecor.viewholder.AppHeaderViewHolder;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import kotlinx.coroutines.ExperimentalCoroutinesApi;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * View model for the window decoration with a caption and shadows. Works with
 * {@link DesktopModeWindowDecoration}.
 */

public class DesktopModeWindowDecorViewModel implements WindowDecorViewModel,
        FocusTransitionListener {
    private static final String TAG = "DesktopModeWindowDecorViewModel";

    private final DesktopModeWindowDecoration.Factory mDesktopModeWindowDecorFactory;
    private final IWindowManager mWindowManager;
    private final ShellExecutor mMainExecutor;
    private final ActivityTaskManager mActivityTaskManager;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final DesktopUserRepositories mDesktopUserRepositories;
    private final ShellController mShellController;
    private final Context mContext;
    private final @ShellMainThread Handler mMainHandler;
    private final @ShellBackgroundThread ShellExecutor mBgExecutor;
    private final Choreographer mMainChoreographer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;
    private final DesktopTasksController mDesktopTasksController;
    private final DesktopImmersiveController mDesktopImmersiveController;
    private final InputManager mInputManager;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final MultiInstanceHelper mMultiInstanceHelper;
    private final WindowDecorCaptionHandleRepository mWindowDecorCaptionHandleRepository;
    private final Optional<DesktopTasksLimiter> mDesktopTasksLimiter;
    private final AppHandleEducationController mAppHandleEducationController;
    private final AppToWebEducationController mAppToWebEducationController;
    private final AppHeaderViewHolder.Factory mAppHeaderViewHolderFactory;
    private boolean mTransitionDragActive;

    private SparseArray<EventReceiver> mEventReceiversByDisplay = new SparseArray<>();

    private final ExclusionRegionListener mExclusionRegionListener =
            new ExclusionRegionListenerImpl();

    private final SparseArray<DesktopModeWindowDecoration> mWindowDecorByTaskId;
    private final DragEventListenerImpl mDragEventListener = new DragEventListenerImpl();
    private final InputMonitorFactory mInputMonitorFactory;
    private TaskOperations mTaskOperations;
    private final Supplier<SurfaceControl.Transaction> mTransactionFactory;
    private final Transitions mTransitions;
    private final Optional<DesktopActivityOrientationChangeHandler>
            mActivityOrientationChangeHandler;

    private SplitScreenController mSplitScreenController;

    private MoveToDesktopAnimator mMoveToDesktopAnimator;
    private final Rect mDragToDesktopAnimationStartBounds = new Rect();
    private final DesktopModeKeyguardChangeListener mDesktopModeKeyguardChangeListener =
            new DesktopModeKeyguardChangeListener();
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final AppToWebGenericLinksParser mGenericLinksParser;
    private final DisplayInsetsController mDisplayInsetsController;
    private final Region mExclusionRegion = Region.obtain();
    private boolean mInImmersiveMode;
    private final String mSysUIPackageName;
    private final AssistContentRequester mAssistContentRequester;
    private final WindowDecorViewHostSupplier<WindowDecorViewHost> mWindowDecorViewHostSupplier;

    private final DisplayChangeController.OnDisplayChangingListener mOnDisplayChangingListener;
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
                        onExclusionRegionChanged(displayId, mExclusionRegion);
                    });
                }
            };
    private final TaskPositionerFactory mTaskPositionerFactory;
    private final FocusTransitionObserver mFocusTransitionObserver;
    private final DesktopModeEventLogger mDesktopModeEventLogger;
    private final DesktopModeUiEventLogger mDesktopModeUiEventLogger;

    public DesktopModeWindowDecorViewModel(
            Context context,
            ShellExecutor shellExecutor,
            @ShellMainThread Handler mainHandler,
            Choreographer mainChoreographer,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            IWindowManager windowManager,
            ShellTaskOrganizer taskOrganizer,
            DesktopUserRepositories desktopUserRepositories,
            DisplayController displayController,
            ShellController shellController,
            DisplayInsetsController displayInsetsController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopTasksController> desktopTasksController,
            DesktopImmersiveController desktopImmersiveController,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            InteractionJankMonitor interactionJankMonitor,
            AppToWebGenericLinksParser genericLinksParser,
            AssistContentRequester assistContentRequester,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            MultiInstanceHelper multiInstanceHelper,
            Optional<DesktopTasksLimiter> desktopTasksLimiter,
            AppHandleEducationController appHandleEducationController,
            AppToWebEducationController appToWebEducationController,
            WindowDecorCaptionHandleRepository windowDecorCaptionHandleRepository,
            Optional<DesktopActivityOrientationChangeHandler> activityOrientationChangeHandler,
            FocusTransitionObserver focusTransitionObserver,
            DesktopModeEventLogger desktopModeEventLogger,
            DesktopModeUiEventLogger desktopModeUiEventLogger) {
        this(
                context,
                shellExecutor,
                mainHandler,
                mainChoreographer,
                bgExecutor,
                shellInit,
                shellCommandHandler,
                windowManager,
                taskOrganizer,
                desktopUserRepositories,
                displayController,
                shellController,
                displayInsetsController,
                syncQueue,
                transitions,
                desktopTasksController,
                desktopImmersiveController,
                genericLinksParser,
                assistContentRequester,
                windowDecorViewHostSupplier,
                multiInstanceHelper,
                new DesktopModeWindowDecoration.Factory(),
                new InputMonitorFactory(),
                SurfaceControl.Transaction::new,
                new AppHeaderViewHolder.Factory(),
                rootTaskDisplayAreaOrganizer,
                new SparseArray<>(),
                interactionJankMonitor,
                desktopTasksLimiter,
                appHandleEducationController,
                appToWebEducationController,
                windowDecorCaptionHandleRepository,
                activityOrientationChangeHandler,
                new TaskPositionerFactory(),
                focusTransitionObserver,
                desktopModeEventLogger,
                desktopModeUiEventLogger);
    }

    @VisibleForTesting
    DesktopModeWindowDecorViewModel(
            Context context,
            ShellExecutor shellExecutor,
            @ShellMainThread Handler mainHandler,
            Choreographer mainChoreographer,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            IWindowManager windowManager,
            ShellTaskOrganizer taskOrganizer,
            DesktopUserRepositories desktopUserRepositories,
            DisplayController displayController,
            ShellController shellController,
            DisplayInsetsController displayInsetsController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopTasksController> desktopTasksController,
            DesktopImmersiveController desktopImmersiveController,
            AppToWebGenericLinksParser genericLinksParser,
            AssistContentRequester assistContentRequester,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            MultiInstanceHelper multiInstanceHelper,
            DesktopModeWindowDecoration.Factory desktopModeWindowDecorFactory,
            InputMonitorFactory inputMonitorFactory,
            Supplier<SurfaceControl.Transaction> transactionFactory,
            AppHeaderViewHolder.Factory appHeaderViewHolderFactory,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            SparseArray<DesktopModeWindowDecoration> windowDecorByTaskId,
            InteractionJankMonitor interactionJankMonitor,
            Optional<DesktopTasksLimiter> desktopTasksLimiter,
            AppHandleEducationController appHandleEducationController,
            AppToWebEducationController appToWebEducationController,
            WindowDecorCaptionHandleRepository windowDecorCaptionHandleRepository,
            Optional<DesktopActivityOrientationChangeHandler> activityOrientationChangeHandler,
            TaskPositionerFactory taskPositionerFactory,
            FocusTransitionObserver focusTransitionObserver,
            DesktopModeEventLogger desktopModeEventLogger,
            DesktopModeUiEventLogger desktopModeUiEventLogger) {
        mContext = context;
        mMainExecutor = shellExecutor;
        mMainHandler = mainHandler;
        mMainChoreographer = mainChoreographer;
        mBgExecutor = bgExecutor;
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mTaskOrganizer = taskOrganizer;
        mDesktopUserRepositories = desktopUserRepositories;
        mShellController = shellController;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mSyncQueue = syncQueue;
        mTransitions = transitions;
        mDesktopTasksController = desktopTasksController.get();
        mDesktopImmersiveController = desktopImmersiveController;
        mMultiInstanceHelper = multiInstanceHelper;
        mShellCommandHandler = shellCommandHandler;
        mWindowManager = windowManager;
        mDesktopModeWindowDecorFactory = desktopModeWindowDecorFactory;
        mInputMonitorFactory = inputMonitorFactory;
        mTransactionFactory = transactionFactory;
        mAppHeaderViewHolderFactory = appHeaderViewHolderFactory;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mGenericLinksParser = genericLinksParser;
        mInputManager = mContext.getSystemService(InputManager.class);
        mWindowDecorByTaskId = windowDecorByTaskId;
        mSysUIPackageName = mContext.getResources().getString(
                com.android.internal.R.string.config_systemUi);
        mInteractionJankMonitor = interactionJankMonitor;
        mDesktopTasksLimiter = desktopTasksLimiter;
        mAppHandleEducationController = appHandleEducationController;
        mAppToWebEducationController = appToWebEducationController;
        mWindowDecorCaptionHandleRepository = windowDecorCaptionHandleRepository;
        mActivityOrientationChangeHandler = activityOrientationChangeHandler;
        mAssistContentRequester = assistContentRequester;
        mWindowDecorViewHostSupplier = windowDecorViewHostSupplier;
        mOnDisplayChangingListener = (displayId, fromRotation, toRotation, displayAreaInfo, t) -> {
            DesktopModeWindowDecoration decoration;
            RunningTaskInfo taskInfo;
            for (int i = 0; i < mWindowDecorByTaskId.size(); i++) {
                decoration = mWindowDecorByTaskId.valueAt(i);
                if (decoration == null) {
                    continue;
                } else {
                    taskInfo = decoration.mTaskInfo;
                }

                // Check if display has been rotated between portrait & landscape
                if (displayId == taskInfo.displayId && taskInfo.isFreeform()
                        && (fromRotation % 2 != toRotation % 2)) {
                    // Check if the task bounds on the rotated display will be out of bounds.
                    // If so, then update task bounds to be within reachable area.
                    final Rect taskBounds = new Rect(
                            taskInfo.configuration.windowConfiguration.getBounds());
                    if (DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(
                            taskBounds, decoration.calculateValidDragArea())) {
                        t.setBounds(taskInfo.token, taskBounds);
                    }
                }
            }
        };
        mTaskPositionerFactory = taskPositionerFactory;
        mFocusTransitionObserver = focusTransitionObserver;
        mDesktopModeEventLogger = desktopModeEventLogger;
        mDesktopModeUiEventLogger = desktopModeUiEventLogger;

        shellInit.addInitCallback(this::onInit, this);
    }

    @OptIn(markerClass = ExperimentalCoroutinesApi.class)
    private void onInit() {
        mShellController.addKeyguardChangeListener(mDesktopModeKeyguardChangeListener);
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mDisplayInsetsController.addGlobalInsetsChangedListener(
                new DesktopModeOnInsetsChangedListener());
        mDesktopTasksController.setOnTaskResizeAnimationListener(
                new DesktopModeOnTaskResizeAnimationListener());
        mDesktopTasksController.setOnTaskRepositionAnimationListener(
                new DesktopModeOnTaskRepositionAnimationListener());
        mDisplayController.addDisplayChangingController(mOnDisplayChangingListener);
        try {
            mWindowManager.registerSystemGestureExclusionListener(mGestureExclusionListener,
                    mContext.getDisplayId());
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register window manager callbacks", e);
        }
        if (DesktopModeStatus.canEnterDesktopModeOrShowAppHandle(mContext)
                && Flags.enableDesktopWindowingAppHandleEducation()) {
            mAppHandleEducationController.setAppHandleEducationTooltipCallbacks(
                    /* appHandleTooltipClickCallback= */(taskId) -> {
                        openHandleMenu(taskId);
                        return Unit.INSTANCE;
                    },
                    /* onToDesktopClickCallback= */(taskId, desktopModeTransitionSource) -> {
                        onToDesktop(taskId, desktopModeTransitionSource);
                        return Unit.INSTANCE;
                    });
        }
        mFocusTransitionObserver.setLocalFocusTransitionListener(this, mMainExecutor);
    }

    @Override
    public void onFocusedTaskChanged(int taskId, boolean isFocusedOnDisplay,
            boolean isFocusedGlobally) {
        final WindowDecoration decor = mWindowDecorByTaskId.get(taskId);
        if (decor != null) {
            decor.relayout(decor.mTaskInfo, isFocusedGlobally, decor.mExclusionRegion);
        }
    }

    @Override
    public void setFreeformTaskTransitionStarter(FreeformTaskTransitionStarter transitionStarter) {
        mTaskOperations = new TaskOperations(transitionStarter, mContext, mSyncQueue);
        mDesktopTasksController.setFreeformTaskTransitionStarter(transitionStarter);
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
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;
        final RunningTaskInfo oldTaskInfo = decoration.mTaskInfo;

        if (taskInfo.displayId != oldTaskInfo.displayId
                && !DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
            removeTaskFromEventReceiver(oldTaskInfo.displayId);
            incrementEventReceiverTasks(taskInfo.displayId);
        }
        if (enableDisplayFocusInShellTransitions()) {
            // Pass the current global focus status to avoid updates outside of a ShellTransition.
            decoration.relayout(taskInfo, decoration.mHasGlobalFocus, decoration.mExclusionRegion);
        } else {
            decoration.relayout(taskInfo, taskInfo.isFocused, decoration.mExclusionRegion);
        }
        mActivityOrientationChangeHandler.ifPresent(handler ->
                handler.handleActivityOrientationChange(oldTaskInfo, taskInfo));
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        // A task vanishing doesn't necessarily mean the task was closed, it could also mean its
        // windowing mode changed. We're only interested in closing tasks so checking whether
        // its info still exists in the task organizer is one way to disambiguate.
        final boolean closed = mTaskOrganizer.getRunningTaskInfo(taskInfo.taskId) == null;
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "Task Vanished: #%d closed=%b", taskInfo.taskId, closed);
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
                    false /* shouldSetTaskPositionAndCrop */,
                    mFocusTransitionObserver.hasGlobalFocus(taskInfo),
                    mExclusionRegion);
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
                false /* shouldSetTaskPositionAndCrop */,
                mFocusTransitionObserver.hasGlobalFocus(taskInfo),
                mExclusionRegion);
    }

    @Override
    public void destroyWindowDecoration(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) return;

        decoration.close();
        final int displayId = taskInfo.displayId;
        if (mEventReceiversByDisplay.contains(displayId)
                && !DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
            removeTaskFromEventReceiver(displayId);
        }
        // Remove the decoration from the cache last because WindowDecoration#close could still
        // issue CANCEL MotionEvents to touch listeners before its view host is released.
        // See b/327664694.
        mWindowDecorByTaskId.remove(taskInfo.taskId);
    }

    private void onExclusionRegionChanged(int displayId, @NonNull Region exclusionRegion) {
        final int decorCount = mWindowDecorByTaskId.size();
        for (int i = 0; i < decorCount; i++) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.valueAt(i);
            if (decoration.mTaskInfo.displayId != displayId) continue;
            decoration.onExclusionRegionChanged(exclusionRegion);
        }
    }

    private void openHandleMenu(int taskId) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        // TODO(b/379873022): Run the instance check and the AssistContent request in
        //  createHandleMenu on the same bg thread dispatch.
        mBgExecutor.execute(() -> {
            final int numOfInstances = checkNumberOfOtherInstances(decoration.mTaskInfo);
            mMainExecutor.execute(() -> {
                decoration.createHandleMenu(
                        numOfInstances >= MANAGE_WINDOWS_MINIMUM_INSTANCES
                );
            });
        });
    }

    private void onToggleSizeInteraction(
            int taskId, @NonNull ToggleTaskSizeInteraction.AmbiguousSource source,
            @Nullable MotionEvent motionEvent) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }
        final ToggleTaskSizeInteraction interaction =
                createToggleSizeInteraction(decoration, source, motionEvent);
        if (interaction == null) {
            return;
        }
        if (interaction.getCujTracing() != null) {
            mInteractionJankMonitor.begin(
                    decoration.mTaskSurface, mContext, mMainHandler,
                    interaction.getCujTracing(), interaction.getJankTag());
        }
        mDesktopTasksController.toggleDesktopTaskSize(decoration.mTaskInfo, interaction);
        decoration.closeHandleMenu();
        decoration.closeMaximizeMenu();
    }

    private ToggleTaskSizeInteraction createToggleSizeInteraction(
            @NonNull DesktopModeWindowDecoration decoration,
            @NonNull ToggleTaskSizeInteraction.AmbiguousSource source,
            @Nullable MotionEvent motionEvent) {
        final RunningTaskInfo taskInfo = decoration.mTaskInfo;

        final DisplayLayout displayLayout = mDisplayController.getDisplayLayout(taskInfo.displayId);
        if (displayLayout == null) {
            return null;
        }
        final Rect stableBounds = new Rect();
        displayLayout.getStableBounds(stableBounds);
        boolean isMaximized = DesktopModeUtils.isTaskMaximized(taskInfo, stableBounds);

        return new ToggleTaskSizeInteraction(
                isMaximized
                        ? ToggleTaskSizeInteraction.Direction.RESTORE
                        : ToggleTaskSizeInteraction.Direction.MAXIMIZE,
                ToggleTaskSizeUtilsKt.toSource(source, isMaximized),
                DesktopModeEventLogger.getInputMethodFromMotionEvent(motionEvent)
        );
    }

    private void onEnterOrExitImmersive(RunningTaskInfo taskInfo) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskInfo.taskId);
        if (decoration == null) {
            return;
        }
        final DesktopRepository desktopRepository = mDesktopUserRepositories.getProfile(
                taskInfo.userId);
        if (desktopRepository.isTaskInFullImmersiveState(taskInfo.taskId)) {
            mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_MAXIMIZE_BUTTON_MENU_TAP_TO_RESTORE);
            mDesktopImmersiveController.moveTaskToNonImmersive(
                    decoration.mTaskInfo, DesktopImmersiveController.ExitReason.USER_INTERACTION);
        } else {
            mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_MAXIMIZE_BUTTON_MENU_TAP_TO_IMMERSIVE);
            mDesktopImmersiveController.moveTaskToImmersive(decoration.mTaskInfo);
        }
        decoration.closeMaximizeMenu();
    }

    /** Snap-resize a task to the left or right side of the desktop. */
    public void onSnapResize(int taskId, boolean left, InputMethod inputMethod, boolean fromMenu) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }

        if (fromMenu) {
            final DesktopModeUiEventLogger.DesktopUiEventEnum event = left
                    ? DesktopUiEventEnum.DESKTOP_WINDOW_MAXIMIZE_BUTTON_MENU_TAP_TO_TILE_TO_LEFT
                    : DesktopUiEventEnum.DESKTOP_WINDOW_MAXIMIZE_BUTTON_MENU_TAP_TO_TILE_TO_RIGHT;
            mDesktopModeUiEventLogger.log(decoration.mTaskInfo, event);
        }

        mInteractionJankMonitor.begin(decoration.mTaskSurface, mContext, mMainHandler,
                Cuj.CUJ_DESKTOP_MODE_SNAP_RESIZE, "maximize_menu_resizable");
        mDesktopTasksController.handleInstantSnapResizingTask(
                decoration.mTaskInfo,
                left ? SnapPosition.LEFT : SnapPosition.RIGHT,
                left ? ResizeTrigger.SNAP_LEFT_MENU : ResizeTrigger.SNAP_RIGHT_MENU,
                inputMethod,
                decoration);

        decoration.closeHandleMenu();
        decoration.closeMaximizeMenu();
    }

    private void onOpenInBrowser(int taskId, @NonNull Intent intent) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }
        openInBrowser(intent, decoration.getUser());
        decoration.closeHandleMenu();
        decoration.closeMaximizeMenu();
    }

    private void openInBrowser(@NonNull Intent intent, @NonNull UserHandle userHandle) {
        mContext.startActivityAsUser(intent, userHandle);
    }

    private void onToDesktop(int taskId, DesktopModeTransitionSource source) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mInteractionJankMonitor.begin(decoration.mTaskSurface, mContext, mMainHandler,
                CUJ_DESKTOP_MODE_ENTER_MODE_APP_HANDLE_MENU);
        // App sometimes draws before the insets from WindowDecoration#relayout have
        // been added, so they must be added here
        decoration.addCaptionInset(wct);
        mDesktopTasksController.moveTaskToDesktop(taskId, wct, source,
                /* remoteTransition= */ null);
        decoration.closeHandleMenu();

        if (source == DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON) {
            mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                    DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_MENU_TAP_TO_DESKTOP_MODE);
        }
    }

    private void onToFullscreen(int taskId) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }
        decoration.closeHandleMenu();
        if (isTaskInSplitScreen(taskId)) {
            mSplitScreenController.moveTaskToFullscreen(taskId,
                    SplitScreenController.EXIT_REASON_DESKTOP_MODE);
        } else {
            mDesktopTasksController.moveToFullscreen(taskId,
                    DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON);
        }
        mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_MENU_TAP_TO_FULL_SCREEN);
    }

    private void onToSplitScreen(int taskId) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }
        decoration.closeHandleMenu();
        // When the app enters split-select, the handle will no longer be visible, meaning
        // we shouldn't receive input for it any longer.
        decoration.disposeStatusBarInputLayer();
        mDesktopTasksController.requestSplit(decoration.mTaskInfo, false /* leftOrTop */);
        mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_MENU_TAP_TO_SPLIT_SCREEN);
    }

    private void onNewWindow(int taskId) {
        final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
        if (decoration == null) {
            return;
        }
        decoration.closeHandleMenu();
        mDesktopTasksController.openNewWindow(decoration.mTaskInfo);
    }

    private void onManageWindows(DesktopModeWindowDecoration decoration) {
        if (decoration == null) {
            return;
        }
        decoration.closeHandleMenu();
        mBgExecutor.execute(() -> {
            final ArrayList<Pair<Integer, TaskSnapshot>> snapshotList =
                    getTaskSnapshots(decoration.mTaskInfo);
            mMainExecutor.execute(() -> decoration.createManageWindowsMenu(
                    snapshotList,
                    /* onIconClickListener= */ (Integer requestedTaskId) -> {
                        decoration.closeManageWindowsMenu();
                        mDesktopTasksController.openInstance(decoration.mTaskInfo,
                                requestedTaskId);
                        return Unit.INSTANCE;
                    }
                )
            );
        });
    }

    private ArrayList<Pair<Integer, TaskSnapshot>> getTaskSnapshots(
            @NonNull RunningTaskInfo callerTaskInfo
    ) {
        final ArrayList<Pair<Integer, TaskSnapshot>> snapshotList = new ArrayList<>();
        final IActivityManager activityManager = ActivityManager.getService();
        final IActivityTaskManager activityTaskManagerService = ActivityTaskManager.getService();
        final List<ActivityManager.RecentTaskInfo> recentTasks;
        try {
            recentTasks = mActivityTaskManager.getRecentTasks(
                    Integer.MAX_VALUE,
                    ActivityManager.RECENT_WITH_EXCLUDED,
                    activityManager.getCurrentUser().id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        final String callerPackageName = callerTaskInfo.baseActivity.getPackageName();
        for (ActivityManager.RecentTaskInfo info : recentTasks) {
            if (info.taskId == callerTaskInfo.taskId || info.baseActivity == null) continue;
            final String infoPackageName = info.baseActivity.getPackageName();
            if (!infoPackageName.equals(callerPackageName)) {
                continue;
            }
            if (info.baseActivity != null) {
                if (callerPackageName.equals(infoPackageName)) {
                    // TODO(b/337903443): Fix this returning null for freeform tasks.
                    try {
                        TaskSnapshot screenshot = activityTaskManagerService
                                .getTaskSnapshot(info.taskId, false);
                        if (screenshot == null) {
                            screenshot = activityTaskManagerService
                                    .takeTaskSnapshot(info.taskId, false);
                        }
                        snapshotList.add(new Pair(info.taskId, screenshot));
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return snapshotList;
    }

    private class DesktopModeTouchEventListener extends GestureDetector.SimpleOnGestureListener
            implements View.OnClickListener, View.OnTouchListener, View.OnLongClickListener,
            View.OnGenericMotionListener, DragDetector.MotionEventHandler {
        private static final long APP_HANDLE_HOLD_TO_DRAG_DURATION_MS = 100;
        private static final long APP_HEADER_HOLD_TO_DRAG_DURATION_MS = 0;

        private final int mTaskId;
        private final WindowContainerToken mTaskToken;
        private final DragPositioningCallback mDragPositioningCallback;
        private final DragDetector mHandleDragDetector;
        private final DragDetector mHeaderDragDetector;
        private final GestureDetector mGestureDetector;
        private final int mDisplayId;
        private final Rect mOnDragStartInitialBounds = new Rect();

        /**
         * Whether to pilfer the next motion event to send cancellations to the windows below.
         * Useful when the caption window is spy and the gesture should be handled by the system
         * instead of by the app for their custom header content.
         * Should not have any effect when {@link Flags#enableAccessibleCustomHeaders()}, because
         * a spy window is not used then.
         */
        private boolean mIsCustomHeaderGesture;
        private boolean mIsResizeGesture;
        private boolean mIsDragging;
        private boolean mTouchscreenInUse;
        private int mDragPointerId = -1;
        private MotionEvent mMotionEvent;

        private DesktopModeTouchEventListener(
                RunningTaskInfo taskInfo,
                DragPositioningCallback dragPositioningCallback) {
            mTaskId = taskInfo.taskId;
            mTaskToken = taskInfo.token;
            mDragPositioningCallback = dragPositioningCallback;
            final int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
            final long appHandleHoldToDragDuration =
                    DesktopModeFlags.ENABLE_HOLD_TO_DRAG_APP_HANDLE.isTrue()
                    ? APP_HANDLE_HOLD_TO_DRAG_DURATION_MS : 0;
            mHandleDragDetector = new DragDetector(this, appHandleHoldToDragDuration,
                    touchSlop);
            mHeaderDragDetector = new DragDetector(this, APP_HEADER_HOLD_TO_DRAG_DURATION_MS,
                    touchSlop);
            mGestureDetector = new GestureDetector(mContext, this);
            mDisplayId = taskInfo.displayId;
        }

        @Override
        public void onClick(View v) {
            if (mIsDragging) {
                mIsDragging = false;
                return;
            }
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final int id = v.getId();
            if (id == R.id.close_window) {
                if (isTaskInSplitScreen(mTaskId)) {
                    mSplitScreenController.moveTaskToFullscreen(getOtherSplitTask(mTaskId).taskId,
                            SplitScreenController.EXIT_REASON_DESKTOP_MODE);
                } else {
                    WindowContainerTransaction wct = new WindowContainerTransaction();
                    final Function1<IBinder, Unit> runOnTransitionStart =
                            mDesktopTasksController.onDesktopWindowClose(
                                    wct, mDisplayId, decoration.mTaskInfo);
                    final IBinder transition = mTaskOperations.closeTask(mTaskToken, wct);
                    if (transition != null && runOnTransitionStart != null) {
                        runOnTransitionStart.invoke(transition);
                    }
                }
            } else if (id == R.id.back_button) {
                mTaskOperations.injectBackKey(mDisplayId);
            } else if (id == R.id.caption_handle || id == R.id.open_menu_button) {
                if (id == R.id.caption_handle && !decoration.mTaskInfo.isFreeform()) {
                    // Clicking the App Handle.
                    mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                            DesktopUiEventEnum.DESKTOP_WINDOW_APP_HANDLE_TAP);
                }
                if (!decoration.isHandleMenuActive()) {
                    moveTaskToFront(decoration.mTaskInfo);
                    openHandleMenu(mTaskId);
                }
            } else if (id == R.id.maximize_window) {
                // TODO(b/346441962): move click detection logic into the decor's
                //  {@link AppHeaderViewHolder}. Let it encapsulate the that and have it report
                //  back to the decoration using
                //  {@link DesktopModeWindowDecoration#setOnMaximizeOrRestoreClickListener}, which
                //  should shared with the maximize menu's maximize/restore actions.
                if (Flags.enableFullyImmersiveInDesktop()
                        && TaskInfoKt.getRequestingImmersive(decoration.mTaskInfo)) {
                    // Task is requesting immersive, so it should either enter or exit immersive,
                    // depending on immersive state.
                    onEnterOrExitImmersive(decoration.mTaskInfo);
                } else {
                    // Full immersive is disabled or task doesn't request/support it, so just
                    // toggle between maximize/restore states.
                    onToggleSizeInteraction(decoration.mTaskInfo.taskId,
                            ToggleTaskSizeInteraction.AmbiguousSource.HEADER_BUTTON, mMotionEvent);
                }
            } else if (id == R.id.minimize_window) {
                mDesktopTasksController.minimizeTask(decoration.mTaskInfo);
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            mMotionEvent = e;
            final int id = v.getId();
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            if ((e.getSource() & SOURCE_TOUCHSCREEN) == SOURCE_TOUCHSCREEN) {
                mTouchscreenInUse = e.getActionMasked() != ACTION_UP
                        && e.getActionMasked() != ACTION_CANCEL;
            }
            if (id != R.id.caption_handle && id != R.id.desktop_mode_caption
                    && id != R.id.open_menu_button && id != R.id.close_window
                    && id != R.id.maximize_window && id != R.id.minimize_window) {
                return false;
            }
            final boolean isAppHandle = !getTaskInfo().isFreeform();
            final int actionMasked = e.getActionMasked();
            final boolean isDown = actionMasked == MotionEvent.ACTION_DOWN;
            final boolean isUpOrCancel = actionMasked == MotionEvent.ACTION_CANCEL
                    || actionMasked == MotionEvent.ACTION_UP;
            if (isDown) {
                // Only move to front on down to prevent 2+ tasks from fighting
                // (and thus flickering) for front status when drag-moving them simultaneously with
                // two pointers.
                // TODO(b/356962065): during a drag-move, this shouldn't be a WCT - just move the
                //  task surface to the top of other tasks and reorder once the user releases the
                //  gesture together with the bounds' WCT. This is probably still valid for other
                //  gestures like simple clicks.
                moveTaskToFront(decoration.mTaskInfo);

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
                mIsResizeGesture = decoration.shouldResizeListenerHandleEvent(e,
                        new Point(viewLocation[0], viewLocation[1]));
                // The caption window may be a spy window when the caption background is
                // transparent, which means events will fall through to the app window. Make
                // sure to cancel these events if they do not happen in the intersection of the
                // customizable region and what the app reported as exclusion areas, because
                // the drag-move or other caption gestures should take priority outside those
                // regions.
                mIsCustomHeaderGesture = downInCustomizableCaptionRegion
                        && downInExclusionRegion && isTransparentCaption;
            }
            if (mIsCustomHeaderGesture || mIsResizeGesture) {
                // The event will be handled by the custom window below or pilfered by resize
                // handler.
                return false;
            }
            if (mInputManager != null
                    && !Flags.enableAccessibleCustomHeaders()) {
                ViewRootImpl viewRootImpl = v.getViewRootImpl();
                if (viewRootImpl != null) {
                    // Pilfer so that windows below receive cancellations for this gesture.
                    mInputManager.pilferPointers(viewRootImpl.getInputToken());
                }
            }
            if (isUpOrCancel) {
                // Gesture is finished, reset state.
                mIsCustomHeaderGesture = false;
                mIsResizeGesture = false;
            }
            if (isAppHandle) {
                return mHandleDragDetector.onMotionEvent(v, e);
            } else {
                return mHeaderDragDetector.onMotionEvent(v, e);
            }
        }

        @Override
        public boolean onLongClick(View v) {
            final int id = v.getId();
            if (id == R.id.maximize_window && mTouchscreenInUse) {
                final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
                moveTaskToFront(decoration.mTaskInfo);
                if (decoration.isMaximizeMenuActive()) {
                    decoration.closeMaximizeMenu();
                } else {
                    mDesktopModeUiEventLogger.log(decoration.mTaskInfo,
                            DesktopUiEventEnum.DESKTOP_WINDOW_MAXIMIZE_BUTTON_REVEAL_MENU);
                    decoration.createMaximizeMenu();
                }
                return true;
            }
            return false;
        }

        /**
         * TODO(b/346441962): move this hover detection logic into the decor's
         * {@link AppHeaderViewHolder}.
         */
        @Override
        public boolean onGenericMotion(View v, MotionEvent ev) {
            mMotionEvent = ev;
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final int id = v.getId();
            if (ev.getAction() == ACTION_HOVER_ENTER && id == R.id.maximize_window) {
                decoration.setAppHeaderMaximizeButtonHovered(true);
                if (!decoration.isMaximizeMenuActive()) {
                    decoration.onMaximizeButtonHoverEnter();
                }
                return true;
            }
            if (ev.getAction() == ACTION_HOVER_EXIT && id == R.id.maximize_window) {
                decoration.setAppHeaderMaximizeButtonHovered(false);
                decoration.onMaximizeHoverStateChanged();
                if (!decoration.isMaximizeMenuActive()) {
                    decoration.onMaximizeButtonHoverExit();
                }
                return true;
            }
            return false;
        }

        private void moveTaskToFront(RunningTaskInfo taskInfo) {
            if (!mFocusTransitionObserver.hasGlobalFocus(taskInfo)) {
                mDesktopModeUiEventLogger.log(taskInfo,
                        DesktopUiEventEnum.DESKTOP_WINDOW_HEADER_TAP_TO_REFOCUS);
                mDesktopTasksController.moveTaskToFront(taskInfo);
            }
        }

        /**
         * @param e {@link MotionEvent} to process
         * @return {@code true} if the motion event is handled.
         */
        @Override
        public boolean handleMotionEvent(@Nullable View v, MotionEvent e) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            final RunningTaskInfo taskInfo = decoration.mTaskInfo;
            if (DesktopModeStatus.canEnterDesktopModeOrShowAppHandle(mContext)
                    && !taskInfo.isFreeform()) {
                return handleNonFreeformMotionEvent(decoration, v, e);
            } else {
                return handleFreeformMotionEvent(decoration, taskInfo, v, e);
            }
        }

        @NonNull
        private RunningTaskInfo getTaskInfo() {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(mTaskId);
            return decoration.mTaskInfo;
        }

        private boolean handleNonFreeformMotionEvent(DesktopModeWindowDecoration decoration,
                View v, MotionEvent e) {
            final int id = v.getId();
            if (id == R.id.caption_handle) {
                handleCaptionThroughStatusBar(e, decoration);
                final boolean wasDragging = mIsDragging;
                updateDragStatus(e.getActionMasked());
                final boolean upOrCancel = e.getActionMasked() == ACTION_UP
                        || e.getActionMasked() == ACTION_CANCEL;
                if (wasDragging && upOrCancel) {
                    // When finishing a drag the event will be consumed, which means the pressed
                    // state of the App Handle must be manually reset to scale its drawable back to
                    // its original shape. This is necessary for drag gestures of the Handle that
                    // result in a cancellation (dragging back to the top).
                    v.setPressed(false);
                }
                // Only prevent onClick from receiving this event if it's a drag.
                return wasDragging;
            }
            return false;
        }

        private boolean handleFreeformMotionEvent(DesktopModeWindowDecoration decoration,
                RunningTaskInfo taskInfo, View v, MotionEvent e) {
            final int id = v.getId();
            if (mGestureDetector.onTouchEvent(e)) {
                return true;
            }
            final boolean touchingButton = (id == R.id.close_window || id == R.id.maximize_window
                    || id == R.id.open_menu_button || id == R.id.minimize_window);
            final DesktopRepository desktopRepository = mDesktopUserRepositories.getProfile(
                    taskInfo.userId);
            final boolean dragAllowed =
                    !desktopRepository.isTaskInFullImmersiveState(taskInfo.taskId);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (dragAllowed) {
                        mDragPointerId = e.getPointerId(0);
                        final Rect initialBounds = mDragPositioningCallback.onDragPositioningStart(
                                0 /* ctrlType */, e.getRawX(0),
                                e.getRawY(0));
                        updateDragStatus(e.getActionMasked());
                        mOnDragStartInitialBounds.set(initialBounds);
                    }
                    // Do not consume input event if a button is touched, otherwise it would
                    // prevent the button's ripple effect from showing.
                    return !touchingButton;
                }
                case ACTION_MOVE: {
                    // If a decor's resize drag zone is active, don't also try to reposition it.
                    if (decoration.isHandlingDragResize()) break;
                    // Dragging the header isn't allowed, so skip the positioning work.
                    if (!dragAllowed) break;

                    decoration.closeMaximizeMenu();
                    if (e.findPointerIndex(mDragPointerId) == -1) {
                        mDragPointerId = e.getPointerId(0);
                    }
                    final int dragPointerIdx = e.findPointerIndex(mDragPointerId);
                    final Rect newTaskBounds = mDragPositioningCallback.onDragPositioningMove(
                            e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx));
                    mDesktopTasksController.onDragPositioningMove(taskInfo,
                            decoration.mTaskSurface,
                            e.getRawX(dragPointerIdx),
                            newTaskBounds);
                    //  Flip mIsDragging only if the bounds actually changed.
                    if (mIsDragging || !newTaskBounds.equals(mOnDragStartInitialBounds)) {
                        updateDragStatus(e.getActionMasked());
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final boolean wasDragging = mIsDragging;
                    if (!wasDragging) {
                        return false;
                    }
                    mDesktopModeUiEventLogger.log(taskInfo,
                            DesktopUiEventEnum.DESKTOP_WINDOW_MOVE_BY_HEADER_DRAG);
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
                    // Tasks bounds haven't actually been updated (only its leash), so pass to
                    // DesktopTasksController to allow secondary transformations (i.e. snap resizing
                    // or transforming to fullscreen) before setting new task bounds.
                    mDesktopTasksController.onDragPositioningEnd(
                            taskInfo, decoration.mTaskSurface, position,
                            new PointF(e.getRawX(dragPointerIdx), e.getRawY(dragPointerIdx)),
                            newTaskBounds, decoration.calculateValidDragArea(),
                            new Rect(mOnDragStartInitialBounds), e,
                            mWindowDecorByTaskId.get(taskInfo.taskId));
                    if (touchingButton) {
                        // We need the input event to not be consumed here to end the ripple
                        // effect on the touched button. We will reset drag state in the ensuing
                        // onClick call that results.
                        return false;
                    } else {
                        updateDragStatus(e.getActionMasked());
                        return true;
                    }
                }
            }
            return true;
        }

        private void updateDragStatus(int eventAction) {
            switch (eventAction) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    mIsDragging = false;
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    mIsDragging = true;
                    break;
                }
            }
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
            final DesktopRepository desktopRepository = mDesktopUserRepositories.getCurrent();
            if (desktopRepository.isTaskInFullImmersiveState(mTaskId)) {
                // Disallow double-tap to resize when in full immersive.
                return false;
            }
            onToggleSizeInteraction(mTaskId,
                    ToggleTaskSizeInteraction.AmbiguousSource.DOUBLE_TAP, e);
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
        if (DesktopModeStatus.canEnterDesktopMode(mContext)) {
            if (!mInImmersiveMode && (relevantDecor == null
                    || relevantDecor.mTaskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM
                    || mTransitionDragActive)) {
                handleCaptionThroughStatusBar(ev, relevantDecor);
            }
        }
        handleEventOutsideCaption(ev, relevantDecor);
        // Prevent status bar from reacting to a caption drag.
        if (DesktopModeStatus.canEnterDesktopMode(mContext)) {
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
        relevantDecor.updateHoverAndPressStatus(ev);
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!mTransitionDragActive && !DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
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
        if (relevantDecor == null) {
            if (ev.getActionMasked() == ACTION_UP) {
                mMoveToDesktopAnimator = null;
                mTransitionDragActive = false;
            }
            return;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_EXIT:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_ENTER: {
                relevantDecor.updateHoverAndPressStatus(ev);
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                // Begin drag through status bar if applicable.
                relevantDecor.checkTouchEvent(ev);
                relevantDecor.updateHoverAndPressStatus(ev);
                mDragToDesktopAnimationStartBounds.set(
                        relevantDecor.mTaskInfo.configuration.windowConfiguration.getBounds());
                boolean dragFromStatusBarAllowed = false;
                if (DesktopModeStatus.canEnterDesktopMode(mContext)) {
                    // In proto2 any full screen or multi-window task can be dragged to
                    // freeform.
                    final int windowingMode = relevantDecor.mTaskInfo.getWindowingMode();
                    dragFromStatusBarAllowed = windowingMode == WINDOWING_MODE_FULLSCREEN
                            || windowingMode == WINDOWING_MODE_MULTI_WINDOW;
                }
                final boolean shouldStartTransitionDrag =
                        relevantDecor.checkTouchEventInFocusedCaptionHandle(ev)
                                || DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue();
                if (dragFromStatusBarAllowed && shouldStartTransitionDrag) {
                    mTransitionDragActive = true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                if (mTransitionDragActive) {
                    final DesktopModeVisualIndicator.DragStartState dragStartState =
                            DesktopModeVisualIndicator.DragStartState
                                    .getDragStartState(relevantDecor.mTaskInfo);
                    if (dragStartState == null) return;
                    mDesktopTasksController.updateVisualIndicator(relevantDecor.mTaskInfo,
                            relevantDecor.mTaskSurface, ev.getRawX(), ev.getRawY(),
                            dragStartState);
                    mTransitionDragActive = false;
                    if (mMoveToDesktopAnimator != null) {
                        // Though this isn't a hover event, we need to update handle's hover state
                        // as it likely will change.
                        relevantDecor.updateHoverAndPressStatus(ev);
                        if (ev.getActionMasked() == ACTION_CANCEL) {
                            mDesktopTasksController.onDragPositioningCancelThroughStatusBar(
                                    relevantDecor.mTaskInfo);
                        } else {
                            endDragToDesktop(ev, relevantDecor);
                        }
                        mMoveToDesktopAnimator = null;
                        return;
                    } else {
                        // In cases where we create an indicator but do not start the
                        // move-to-desktop animation, we need to dismiss it.
                        mDesktopTasksController.releaseVisualIndicator();
                    }
                }
                relevantDecor.checkTouchEvent(ev);
                break;
            }
            case ACTION_MOVE: {
                if (relevantDecor == null) {
                    return;
                }
                if (mTransitionDragActive) {
                    // Do not create an indicator at all if we're not past transition height.
                    DisplayLayout layout = mDisplayController
                            .getDisplayLayout(relevantDecor.mTaskInfo.displayId);
                    if (ev.getRawY() < 2 * layout.stableInsets().top
                            && mMoveToDesktopAnimator == null) {
                        return;
                    }
                    final DesktopModeVisualIndicator.DragStartState dragStartState =
                            DesktopModeVisualIndicator.DragStartState
                                    .getDragStartState(relevantDecor.mTaskInfo);
                    if (dragStartState == null) return;
                    final DesktopModeVisualIndicator.IndicatorType indicatorType =
                            mDesktopTasksController.updateVisualIndicator(
                                    relevantDecor.mTaskInfo,
                                    relevantDecor.mTaskSurface, ev.getRawX(), ev.getRawY(),
                                    dragStartState);
                    if (indicatorType != TO_FULLSCREEN_INDICATOR) {
                        if (mMoveToDesktopAnimator == null) {
                            mMoveToDesktopAnimator = new MoveToDesktopAnimator(
                                    mContext, mDragToDesktopAnimationStartBounds,
                                    relevantDecor.mTaskInfo, relevantDecor.mTaskSurface);
                            mDesktopTasksController.startDragToDesktop(relevantDecor.mTaskInfo,
                                    mMoveToDesktopAnimator, relevantDecor.mTaskSurface);
                        }
                    }
                    if (mMoveToDesktopAnimator != null) {
                        mMoveToDesktopAnimator.updatePosition(ev);
                    }
                }
                break;
            }
        }
    }

    private void endDragToDesktop(MotionEvent ev, DesktopModeWindowDecoration relevantDecor) {
        DesktopModeVisualIndicator.IndicatorType resultType =
                mDesktopTasksController.onDragPositioningEndThroughStatusBar(
                        new PointF(ev.getRawX(), ev.getRawY()),
                        relevantDecor.mTaskInfo,
                        relevantDecor.mTaskSurface);
        // If we are entering split select, handle will no longer be visible and
        // should not be receiving any input.
        if (resultType == TO_SPLIT_LEFT_INDICATOR
                || resultType == TO_SPLIT_RIGHT_INDICATOR) {
            relevantDecor.disposeStatusBarInputLayer();
            // We should also dispose the other split task's input layer if
            // applicable.
            final int splitPosition = mSplitScreenController
                    .getSplitPosition(relevantDecor.mTaskInfo.taskId);
            if (splitPosition != SPLIT_POSITION_UNDEFINED) {
                final int oppositePosition =
                        splitPosition == SPLIT_POSITION_TOP_OR_LEFT
                                ? SPLIT_POSITION_BOTTOM_OR_RIGHT
                                : SPLIT_POSITION_TOP_OR_LEFT;
                final RunningTaskInfo oppositeTaskInfo =
                        mSplitScreenController.getTaskInfo(oppositePosition);
                if (oppositeTaskInfo != null) {
                    mWindowDecorByTaskId.get(oppositeTaskInfo.taskId)
                            .disposeStatusBarInputLayer();
                }
            }
        }
    }

    @Nullable
    private DesktopModeWindowDecoration getRelevantWindowDecor(MotionEvent ev) {
        // If we are mid-transition, dragged task's decor is always relevant.
        final int draggedTaskId = mDesktopTasksController.getDraggingTaskId();
        if (draggedTaskId != INVALID_TASK_ID) {
            return mWindowDecorByTaskId.get(draggedTaskId);
        }
        final DesktopModeWindowDecoration focusedDecor = getFocusedDecor();
        if (focusedDecor == null) {
            return null;
        }
        final boolean splitScreenVisible = mSplitScreenController != null
                && mSplitScreenController.isSplitScreenVisible();
        // It's possible that split tasks are visible but neither is focused, such as when there's
        // a fullscreen translucent window on top of them. In that case, the relevant decor should
        // just be that translucent focused window.
        final boolean focusedTaskInSplit = mSplitScreenController != null
                && mSplitScreenController.isTaskInSplitScreen(focusedDecor.mTaskInfo.taskId);
        if (splitScreenVisible && focusedTaskInSplit) {
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
                mInputMonitorFactory.create(inputManager, displayId);
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
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODALS_POLICY.isTrue()
                && isTopActivityExemptFromDesktopWindowing(mContext, taskInfo)) {
            return false;
        }
        if (isPartOfDefaultHomePackage(taskInfo)) {
            return false;
        }
        final boolean isOnLargeScreen = taskInfo.getConfiguration().smallestScreenWidthDp
                >= WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP;
        if (!DesktopModeStatus.canEnterDesktopMode(mContext)
                && DesktopModeStatus.overridesShowAppHandle(mContext) && !isOnLargeScreen) {
            // Devices with multiple screens may enable the app handle but it should not show on
            // small screens
            return false;
        }
        return DesktopModeStatus.canEnterDesktopModeOrShowAppHandle(mContext)
                && !DesktopWallpaperActivity.isWallpaperTask(taskInfo)
                && taskInfo.getWindowingMode() != WINDOWING_MODE_PINNED
                && taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && !taskInfo.configuration.windowConfiguration.isAlwaysOnTop();
    }

    private boolean isPartOfDefaultHomePackage(RunningTaskInfo taskInfo) {
        final ComponentName currentDefaultHome =
                mContext.getPackageManager().getHomeActivities(new ArrayList<>());
        return currentDefaultHome != null && taskInfo.baseActivity != null
                && currentDefaultHome.getPackageName()
                .equals(taskInfo.baseActivity.getPackageName());
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
                        Flags.enableBugFixesForSecondaryDisplay()
                                ? mDisplayController.getDisplayContext(taskInfo.displayId)
                                : mContext,
                        mContext.createContextAsUser(UserHandle.of(taskInfo.userId), 0 /* flags */),
                        mDisplayController,
                        mSplitScreenController,
                        mDesktopUserRepositories,
                        mTaskOrganizer,
                        taskInfo,
                        taskSurface,
                        mMainHandler,
                        mBgExecutor,
                        mMainChoreographer,
                        mSyncQueue,
                        mAppHeaderViewHolderFactory,
                        mRootTaskDisplayAreaOrganizer,
                        mGenericLinksParser,
                        mAssistContentRequester,
                        mWindowDecorViewHostSupplier,
                        mMultiInstanceHelper,
                        mWindowDecorCaptionHandleRepository,
                        mDesktopModeEventLogger);
        mWindowDecorByTaskId.put(taskInfo.taskId, windowDecoration);

        final TaskPositioner taskPositioner = mTaskPositionerFactory.create(
                mTaskOrganizer,
                windowDecoration,
                mDisplayController,
                mDragEventListener,
                mTransitions,
                mInteractionJankMonitor,
                mTransactionFactory,
                mMainHandler);
        windowDecoration.setTaskDragResizer(taskPositioner);

        final DesktopModeTouchEventListener touchEventListener =
                new DesktopModeTouchEventListener(taskInfo, taskPositioner);
        windowDecoration.setOnMaximizeOrRestoreClickListener(() -> {
            onToggleSizeInteraction(taskInfo.taskId,
                    ToggleTaskSizeInteraction.AmbiguousSource.MAXIMIZE_MENU,
                    touchEventListener.mMotionEvent);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnImmersiveOrRestoreClickListener(() -> {
            onEnterOrExitImmersive(taskInfo);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnLeftSnapClickListener(() -> {
            onSnapResize(taskInfo.taskId, /* isLeft= */ true,
                    DesktopModeEventLogger.getInputMethodFromMotionEvent(
                            touchEventListener.mMotionEvent), /* fromMenu= */ true);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnRightSnapClickListener(() -> {
            onSnapResize(taskInfo.taskId, /* isLeft= */ false,
                    DesktopModeEventLogger.getInputMethodFromMotionEvent(
                            touchEventListener.mMotionEvent), /* fromMenu= */ true);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnToDesktopClickListener(desktopModeTransitionSource -> {
            onToDesktop(taskInfo.taskId, desktopModeTransitionSource);
        });
        windowDecoration.setOnToFullscreenClickListener(() -> {
            onToFullscreen(taskInfo.taskId);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnToSplitScreenClickListener(() -> {
            onToSplitScreen(taskInfo.taskId);
            return Unit.INSTANCE;
        });
        windowDecoration.setOpenInBrowserClickListener((intent) -> {
            onOpenInBrowser(taskInfo.taskId, intent);
        });
        windowDecoration.setOnNewWindowClickListener(() -> {
            onNewWindow(taskInfo.taskId);
            return Unit.INSTANCE;
        });
        windowDecoration.setManageWindowsClickListener(() -> {
            onManageWindows(windowDecoration);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnChangeAspectRatioClickListener(() -> {
            CompatUIController.launchUserAspectRatioSettings(mContext, taskInfo);
            return Unit.INSTANCE;
        });
        windowDecoration.setOnMaximizeHoverListener(() -> {
            if (!windowDecoration.isMaximizeMenuActive()) {
                mDesktopModeUiEventLogger.log(taskInfo,
                        DesktopUiEventEnum.DESKTOP_WINDOW_MAXIMIZE_BUTTON_REVEAL_MENU);
                windowDecoration.createMaximizeMenu();
            }
            return Unit.INSTANCE;
        });
        windowDecoration.setCaptionListeners(
                touchEventListener, touchEventListener, touchEventListener, touchEventListener);
        windowDecoration.setExclusionRegionListener(mExclusionRegionListener);
        windowDecoration.setDragPositioningCallback(taskPositioner);
        windowDecoration.relayout(taskInfo, startT, finishT,
                false /* applyStartTransactionOnDraw */, false /* shouldSetTaskPositionAndCrop */,
                mFocusTransitionObserver.hasGlobalFocus(taskInfo),
                mExclusionRegion);
        if (!DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
            incrementEventReceiverTasks(taskInfo.displayId);
        }
    }

    private RunningTaskInfo getOtherSplitTask(int taskId) {
        @SplitPosition int remainingTaskPosition = mSplitScreenController
                .getSplitPosition(taskId) == SPLIT_POSITION_BOTTOM_OR_RIGHT
                ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT;
        return mSplitScreenController.getTaskInfo(remainingTaskPosition);
    }

    private boolean isTaskInSplitScreen(int taskId) {
        return mSplitScreenController != null
                && mSplitScreenController.isTaskInSplitScreen(taskId);
    }

    private void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + "DesktopModeWindowDecorViewModel");
        pw.println(innerPrefix + "DesktopModeStatus="
                + DesktopModeStatus.canEnterDesktopMode(mContext));
        pw.println(innerPrefix + "mTransitionDragActive=" + mTransitionDragActive);
        pw.println(innerPrefix + "mEventReceiversByDisplay=" + mEventReceiversByDisplay);
        pw.println(innerPrefix + "mWindowDecorByTaskId=" + mWindowDecorByTaskId);
        pw.println(innerPrefix + "mExclusionRegion=" + mExclusionRegion);
    }

    private class DesktopModeOnTaskRepositionAnimationListener
            implements OnTaskRepositionAnimationListener {
        @Override
        public void onAnimationStart(int taskId) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            if (decoration != null) {
                decoration.setAnimatingTaskResizeOrReposition(true);
            }
        }

        @Override
        public void onAnimationEnd(int taskId) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            if (decoration != null) {
                decoration.setAnimatingTaskResizeOrReposition(false);
            }
        }
    }

    private class DesktopModeOnTaskResizeAnimationListener
            implements OnTaskResizeAnimationListener {
        @Override
        public void onAnimationStart(int taskId, Transaction t, Rect bounds) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            if (decoration == null)  {
                t.apply();
                return;
            }
            decoration.showResizeVeil(t, bounds);
            decoration.setAnimatingTaskResizeOrReposition(true);
        }

        @Override
        public void onBoundsChange(int taskId, Transaction t, Rect bounds) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            if (decoration == null) return;
            decoration.updateResizeVeil(t, bounds);
        }

        @Override
        public void onAnimationEnd(int taskId) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            if (decoration == null) return;
            decoration.hideResizeVeil();
            decoration.setAnimatingTaskResizeOrReposition(false);
        }
    }

    private class DragEventListenerImpl
            implements DragPositioningCallbackUtility.DragEventListener {
        @Override
        public void onDragStart(int taskId) {
            final DesktopModeWindowDecoration decoration = mWindowDecorByTaskId.get(taskId);
            decoration.closeHandleMenu();
        }

        @Override
        public void onDragMove(int taskId) {

        }
    }

    /**
     * Gets the number of instances of a task running, not including the specified task itself.
     */
    private int checkNumberOfOtherInstances(@NonNull RunningTaskInfo info) {
        // TODO(b/336289597): Rather than returning number of instances, return a list of valid
        //  instances, then refer to the list's size and reuse the list for Manage Windows menu.
        final IActivityTaskManager activityTaskManager = ActivityTaskManager.getService();
        try {
            return activityTaskManager.getRecentTasks(Integer.MAX_VALUE,
                    ActivityManager.RECENT_WITH_EXCLUDED,
                    info.userId).getList().stream().filter(
                            recentTaskInfo -> (recentTaskInfo.taskId != info.taskId
                                    && recentTaskInfo.baseActivity != null
                                    && recentTaskInfo.baseActivity.getPackageName()
                                    .equals(info.baseActivity.getPackageName())
                            )
            ).toList().size();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    static class InputMonitorFactory {
        InputMonitor create(InputManager inputManager, int displayId) {
            return inputManager.monitorGestureInput("caption-touch", displayId);
        }
    }

    private class ExclusionRegionListenerImpl
            implements ExclusionRegionListener {

        @Override
        public void onExclusionRegionChanged(int taskId, Region region) {
            mDesktopTasksController.onExclusionRegionChanged(taskId, region);
        }

        @Override
        public void onExclusionRegionDismissed(int taskId) {
            mDesktopTasksController.removeExclusionRegionForTask(taskId);
        }
    }

    class DesktopModeKeyguardChangeListener implements KeyguardChangeListener {
        @Override
        public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
                boolean animatingDismiss) {
            final int size = mWindowDecorByTaskId.size();
            for (int i = size - 1; i >= 0; i--) {
                final DesktopModeWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
                if (decor != null) {
                    decor.onKeyguardStateChanged(visible, occluded);
                }
            }
        }
    }

    @VisibleForTesting
    class DesktopModeOnInsetsChangedListener implements
            DisplayInsetsController.OnInsetsChangedListener {
        @Override
        public void insetsChanged(int displayId, @NonNull InsetsState insetsState) {
            final int size = mWindowDecorByTaskId.size();
            for (int i = size - 1; i >= 0; i--) {
                final DesktopModeWindowDecoration decor = mWindowDecorByTaskId.valueAt(i);
                if (decor == null) {
                    continue;
                }
                if (decor.mTaskInfo.displayId == displayId
                        && Flags.enableDesktopWindowingImmersiveHandleHiding()) {
                    decor.onInsetsStateChanged(insetsState);
                }
                if (!DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
                    // If status bar inset is visible, top task is not in immersive mode.
                    // This value is only needed when the App Handle input is being handled
                    // through the global input monitor (hence the flag check) to ignore gestures
                    // when the app is in immersive mode. When disabled, the view itself handles
                    // input, and since it's removed when in immersive there's no need to track
                    // this here.
                    mInImmersiveMode = !InsetsStateKt.isVisible(insetsState, statusBars());
                }
            }
        }
    }

    @VisibleForTesting
    static class TaskPositionerFactory {
        TaskPositioner create(
                ShellTaskOrganizer taskOrganizer,
                DesktopModeWindowDecoration windowDecoration,
                DisplayController displayController,
                DragPositioningCallbackUtility.DragEventListener dragEventListener,
                Transitions transitions,
                InteractionJankMonitor interactionJankMonitor,
                Supplier<SurfaceControl.Transaction> transactionFactory,
                Handler handler) {
            final TaskPositioner taskPositioner = DesktopModeStatus.isVeiledResizeEnabled()
                    ? new VeiledResizeTaskPositioner(
                            taskOrganizer,
                            windowDecoration,
                            displayController,
                            dragEventListener,
                            transitions,
                            interactionJankMonitor,
                            handler)
                    : new FluidResizeTaskPositioner(
                            taskOrganizer,
                            transitions,
                            windowDecoration,
                            displayController,
                            dragEventListener,
                            transactionFactory);

            if (DesktopModeFlags.ENABLE_WINDOWING_SCALED_RESIZING.isTrue()) {
                return new FixedAspectRatioTaskPositionerDecorator(windowDecoration,
                        taskPositioner);
            }
            return taskPositioner;
        }
    }
}
