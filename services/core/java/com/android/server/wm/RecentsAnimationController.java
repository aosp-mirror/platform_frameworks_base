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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_RECENTS_ANIMATIONS;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_RECENTS_ANIM;
import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.WindowConfiguration;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.InputWindowHandle;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;
import android.view.WindowInsets.Type;
import android.window.PictureInPictureSurfaceTransaction;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.utils.InsetUtils;

import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Controls a single instance of the remote driven recents animation. In particular, this allows
 * the calling SystemUI to animate the visible task windows as a part of the transition. The remote
 * runner is provided an animation controller which allows it to take screenshots and to notify
 * window manager when the animation is completed. In addition, window manager may also notify the
 * app if it requires the animation to be canceled at any time (ie. due to timeout, etc.)
 */
public class RecentsAnimationController implements DeathRecipient {
    private static final String TAG = RecentsAnimationController.class.getSimpleName();
    private static final long FAILSAFE_DELAY = 1000;

    // Constant for a yet-to-be-calculated {@link RemoteAnimationTarget#Mode} state
    private static final int MODE_UNKNOWN = -1;

    public static final int REORDER_KEEP_IN_PLACE = 0;
    public static final int REORDER_MOVE_TO_TOP = 1;
    public static final int REORDER_MOVE_TO_ORIGINAL_POSITION = 2;

    @IntDef(prefix = { "REORDER_MODE_" }, value = {
            REORDER_KEEP_IN_PLACE,
            REORDER_MOVE_TO_TOP,
            REORDER_MOVE_TO_ORIGINAL_POSITION
    })
    public @interface ReorderMode {}

    private final WindowManagerService mService;
    @VisibleForTesting
    final StatusBarManagerInternal mStatusBar;
    private IRecentsAnimationRunner mRunner;
    private final RecentsAnimationCallbacks mCallbacks;
    private final ArrayList<TaskAnimationAdapter> mPendingAnimations = new ArrayList<>();
    private final IntArray mPendingNewTaskTargets = new IntArray(0);

    private final ArrayList<WallpaperAnimationAdapter> mPendingWallpaperAnimations =
            new ArrayList<>();
    private final int mDisplayId;
    private boolean mWillFinishToHome = false;
    private final Runnable mFailsafeRunnable = this::onFailsafe;

    // The recents component app token that is shown behind the visible tasks
    private ActivityRecord mTargetActivityRecord;
    private DisplayContent mDisplayContent;
    private int mTargetActivityType;

    // We start the RecentsAnimationController in a pending-start state since we need to wait for
    // the wallpaper/activity to draw before we can give control to the handler to start animating
    // the visible task surfaces
    private boolean mPendingStart = true;

    // Set when the animation has been canceled
    private boolean mCanceled;

    // Whether or not the input consumer is enabled. The input consumer must be both registered and
    // enabled for it to start intercepting touch events.
    private boolean mInputConsumerEnabled;

    private final Rect mTmpRect = new Rect();

    private boolean mLinkedToDeathOfRunner;

    // Whether to try to defer canceling from a root task order change until the next transition
    private boolean mRequestDeferCancelUntilNextTransition;
    // Whether to actually defer canceling until the next transition
    private boolean mCancelOnNextTransitionStart;
    // Whether to take a screenshot when handling a deferred cancel
    private boolean mCancelDeferredWithScreenshot;
    // The reorder mode to apply after the cleanupScreenshot() callback
    private int mPendingCancelWithScreenshotReorderMode = REORDER_MOVE_TO_ORIGINAL_POSITION;

    @VisibleForTesting
    boolean mIsAddingTaskToTargets;
    @VisibleForTesting
    boolean mShouldAttachNavBarToAppDuringTransition;
    private boolean mNavigationBarAttachedToApp;
    private ActivityRecord mNavBarAttachedApp;

    private final ArrayList<RemoteAnimationTarget> mPendingTaskAppears = new ArrayList<>();

    private boolean mIsActivityStarting = false;

    /**
     * An app transition listener to cancel the recents animation only after the app transition
     * starts or is canceled.
     */
    final AppTransitionListener mAppTransitionListener = new AppTransitionListener() {
        @Override
        public int onAppTransitionStartingLocked(boolean keyguardGoingAway,
                boolean keyguardOccluding, long duration, long statusBarAnimationStartTime,
                long statusBarAnimationDuration) {
            continueDeferredCancel();
            return 0;
        }

        @Override
        public void onAppTransitionCancelledLocked(boolean keyguardGoingAway) {
            continueDeferredCancel();
        }

        private void continueDeferredCancel() {
            mDisplayContent.mAppTransition.unregisterListener(this);
            if (mCanceled) {
                return;
            }

            if (mCancelOnNextTransitionStart) {
                mCancelOnNextTransitionStart = false;
                cancelAnimationWithScreenshot(mCancelDeferredWithScreenshot);
            }
        }
    };

    public interface RecentsAnimationCallbacks {
        /** Callback when recents animation is finished. */
        void onAnimationFinished(@ReorderMode int reorderMode, boolean sendUserLeaveHint);
    }

    private final IRecentsAnimationController mController =
            new IRecentsAnimationController.Stub() {

        @Override
        public TaskSnapshot screenshotTask(int taskId) {
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                    "screenshotTask(%d): mCanceled=%b", taskId, mCanceled);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return null;
                    }
                    for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                        final TaskAnimationAdapter adapter = mPendingAnimations.get(i);
                        final Task task = adapter.mTask;
                        if (task.mTaskId == taskId) {
                            final TaskSnapshotController snapshotController =
                                    mService.mTaskSnapshotController;
                            final ArraySet<Task> tasks = Sets.newArraySet(task);
                            snapshotController.snapshotTasks(tasks);
                            snapshotController.addSkipClosingAppSnapshotTasks(tasks);
                            return snapshotController.getSnapshot(taskId, task.mUserId,
                                    false /* restoreFromDisk */, false /* isLowResolution */);
                        }
                    }
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setFinishTaskTransaction(int taskId,
                PictureInPictureSurfaceTransaction finishTransaction,
                SurfaceControl overlay) {
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                    "setFinishTaskTransaction(%d): transaction=%s", taskId, finishTransaction);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                        final TaskAnimationAdapter taskAdapter = mPendingAnimations.get(i);
                        if (taskAdapter.mTask.mTaskId == taskId) {
                            taskAdapter.mFinishTransaction = finishTransaction;
                            taskAdapter.mFinishOverlay = overlay;
                            break;
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void finish(boolean moveHomeToTop, boolean sendUserLeaveHint) {
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                    "finish(%b): mCanceled=%b", moveHomeToTop, mCanceled);
            final long token = Binder.clearCallingIdentity();
            try {
                // Note, the callback will handle its own synchronization, do not lock on WM lock
                // prior to calling the callback
                mCallbacks.onAnimationFinished(moveHomeToTop
                        ? REORDER_MOVE_TO_TOP
                        : REORDER_MOVE_TO_ORIGINAL_POSITION, sendUserLeaveHint);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAnimationTargetsBehindSystemBars(boolean behindSystemBars)
                throws RemoteException {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                        final Task task = mPendingAnimations.get(i).mTask;
                        if (task.getActivityType() != mTargetActivityType) {
                            task.setCanAffectSystemUiFlags(behindSystemBars);
                        }
                    }
                    InputMethodManagerInternal.get().maybeFinishStylusHandwriting();
                    mService.mWindowPlacerLocked.requestTraversal();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setInputConsumerEnabled(boolean enabled) {
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                    "setInputConsumerEnabled(%s): mCanceled=%b", enabled, mCanceled);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return;
                    }
                    mInputConsumerEnabled = enabled;
                    final InputMonitor inputMonitor = mDisplayContent.getInputMonitor();
                    inputMonitor.updateInputWindowsLw(true /*force*/);
                    mService.scheduleAnimationLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot) {
            synchronized (mService.mGlobalLock) {
                setDeferredCancel(defer, screenshot);
            }
        }

        @Override
        public void cleanupScreenshot() {
            final long token = Binder.clearCallingIdentity();
            try {
                // Note, the callback will handle its own synchronization, do not lock on WM lock
                // prior to calling the callback
                continueDeferredCancelAnimation();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setWillFinishToHome(boolean willFinishToHome) {
            synchronized (mService.getWindowManagerLock()) {
                RecentsAnimationController.this.setWillFinishToHome(willFinishToHome);
            }
        }

        @Override
        public boolean removeTask(int taskId) {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    return removeTaskInternal(taskId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void detachNavigationBarFromApp(boolean moveHomeToTop) {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    restoreNavigationBarFromApp(
                            moveHomeToTop || mIsAddingTaskToTargets /* animate */);
                    mService.mWindowPlacerLocked.requestTraversal();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void animateNavigationBarToApp(long duration) {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    animateNavigationBarForAppLaunch(duration);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    void notifyActivityStarting() {
        mIsActivityStarting = true;
    }

    boolean isActivityStarting() {
        return mIsActivityStarting;
    }

    /**
     * @param remoteAnimationRunner The remote runner which should be notified when the animation is
     *                              ready to start or has been canceled
     * @param callbacks Callbacks to be made when the animation finishes
     */
    RecentsAnimationController(WindowManagerService service,
            IRecentsAnimationRunner remoteAnimationRunner, RecentsAnimationCallbacks callbacks,
            int displayId) {
        mService = service;
        mRunner = remoteAnimationRunner;
        mCallbacks = callbacks;
        mDisplayId = displayId;
        mStatusBar = LocalServices.getService(StatusBarManagerInternal.class);
        mDisplayContent = service.mRoot.getDisplayContent(displayId);
        mShouldAttachNavBarToAppDuringTransition =
                mDisplayContent.getDisplayPolicy().shouldAttachNavBarToAppDuringTransition();
    }

    /**
     * Initializes the recents animation controller. This is a separate call from the constructor
     * because it may call cancelAnimation() which needs to properly clean up the controller
     * in the window manager.
     */
    public void initialize(int targetActivityType, SparseBooleanArray recentTaskIds,
            ActivityRecord targetActivity) {
        mTargetActivityType = targetActivityType;
        mDisplayContent.mAppTransition.registerListenerLocked(mAppTransitionListener);

        // Make leashes for each of the visible/target tasks and add it to the recents animation to
        // be started
        // TODO(b/153090560): Support Recents on multiple task display areas
        final ArrayList<Task> visibleTasks = mDisplayContent.getDefaultTaskDisplayArea()
                .getVisibleTasks();
        final Task targetRootTask = mDisplayContent.getDefaultTaskDisplayArea()
                .getRootTask(WINDOWING_MODE_UNDEFINED, targetActivityType);
        if (targetRootTask != null) {
            final PooledConsumer c = PooledLambda.obtainConsumer((t, outList) ->
	            { if (!outList.contains(t)) outList.add(t); }, PooledLambda.__(Task.class),
                    visibleTasks);
            targetRootTask.forAllLeafTasks(c, true /* traverseTopToBottom */);
            c.recycle();
        }

        final int taskCount = visibleTasks.size();
        for (int i = taskCount - 1; i >= 0; i--) {
            final Task task = visibleTasks.get(i);
            if (skipAnimation(task)) {
                continue;
            }
            addAnimation(task, !recentTaskIds.get(task.mTaskId), false /* hidden */,
                    (type, anim) -> task.forAllWindows(win -> {
                        win.onAnimationFinished(type, anim);
                    }, true /* traverseTopToBottom */));
        }

        // Skip the animation if there is nothing to animate
        if (mPendingAnimations.isEmpty()) {
            cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "initialize-noVisibleTasks");
            return;
        }

        try {
            linkToDeathOfRunner();
        } catch (RemoteException e) {
            cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "initialize-failedToLinkToDeath");
            return;
        }

        attachNavigationBarToApp();

        // Adjust the wallpaper visibility for the showing target activity
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                "setHomeApp(%s)", targetActivity.getName());
        mTargetActivityRecord = targetActivity;
        if (targetActivity.windowsCanBeWallpaperTarget()) {
            mDisplayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            mDisplayContent.setLayoutNeeded();
        }

        mService.mWindowPlacerLocked.performSurfacePlacement();

        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(targetActivity);

        // Notify that the animation has started
        if (mStatusBar != null) {
            mStatusBar.onRecentsAnimationStateChanged(true /* running */);
        }
    }

    /**
     * Return whether the given window should still be considered interesting for the all-drawn
     * state.  This is only interesting for the target app, which may have child windows that are
     * not actually visible and should not be considered interesting and waited upon.
     */
    protected boolean isInterestingForAllDrawn(WindowState window) {
        if (isTargetApp(window.getActivityRecord())) {
            if (window.getWindowType() != TYPE_BASE_APPLICATION
                    && window.getAttrs().alpha == 0f) {
                // If there is a cihld window that is alpha 0, then ignore that window
                return false;
            }
        }
        // By default all windows are still interesting for all drawn purposes
        return true;
    }

    /**
     * Whether a task should be filtered from the recents animation. This can be true for tasks
     * being displayed outside of recents.
     */
    private boolean skipAnimation(Task task) {
        final WindowConfiguration config = task.getWindowConfiguration();
        return task.isAlwaysOnTop() || config.tasksAreFloating();
    }

    @VisibleForTesting
    TaskAnimationAdapter addAnimation(Task task, boolean isRecentTaskInvisible) {
        return addAnimation(task, isRecentTaskInvisible, false /* hidden */,
                null /* finishedCallback */);
    }

    @VisibleForTesting
    TaskAnimationAdapter addAnimation(Task task, boolean isRecentTaskInvisible, boolean hidden,
            OnAnimationFinishedCallback finishedCallback) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "addAnimation(%s)", task.getName());
        final TaskAnimationAdapter taskAdapter = new TaskAnimationAdapter(task,
                isRecentTaskInvisible);
        task.startAnimation(task.getPendingTransaction(), taskAdapter, hidden,
                ANIMATION_TYPE_RECENTS, finishedCallback);
        task.commitPendingTransaction();
        mPendingAnimations.add(taskAdapter);
        return taskAdapter;
    }

    @VisibleForTesting
    void removeAnimation(TaskAnimationAdapter taskAdapter) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                "removeAnimation(%d)", taskAdapter.mTask.mTaskId);
        taskAdapter.onRemove();
        mPendingAnimations.remove(taskAdapter);
    }

    @VisibleForTesting
    void removeWallpaperAnimation(WallpaperAnimationAdapter wallpaperAdapter) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "removeWallpaperAnimation()");
        wallpaperAdapter.getLeashFinishedCallback().onAnimationFinished(
                wallpaperAdapter.getLastAnimationType(), wallpaperAdapter);
        mPendingWallpaperAnimations.remove(wallpaperAdapter);
    }

    void startAnimation() {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                "startAnimation(): mPendingStart=%b mCanceled=%b", mPendingStart, mCanceled);
        if (!mPendingStart || mCanceled) {
            // Skip starting if we've already started or canceled the animation
            return;
        }
        try {
            // Create the app targets
            final RemoteAnimationTarget[] appTargets = createAppAnimations();

            // Skip the animation if there is nothing to animate
            if (appTargets.length == 0) {
                cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "startAnimation-noAppWindows");
                return;
            }

            // Create the wallpaper targets
            final RemoteAnimationTarget[] wallpaperTargets = createWallpaperAnimations();

            mPendingStart = false;

            final Rect contentInsets;
            final WindowState targetAppMainWindow = getTargetAppMainWindow();
            if (targetAppMainWindow != null) {
                contentInsets = targetAppMainWindow
                        .getInsetsStateWithVisibilityOverride()
                        .calculateInsets(mTargetActivityRecord.getBounds(), Type.systemBars(),
                                false /* ignoreVisibility */).toRect();
            } else {
                // If the window for the activity had not yet been created, use the display insets.
                mService.getStableInsets(mDisplayId, mTmpRect);
                contentInsets = mTmpRect;
            }
            mRunner.onAnimationStart(mController, appTargets, wallpaperTargets, contentInsets,
                    null);
            ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                    "startAnimation(): Notify animation start: %s",
                    mPendingAnimations.stream()
                            .map(anim->anim.mTask.mTaskId).collect(Collectors.toList()));
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to start recents animation", e);
        }

        if (mTargetActivityRecord != null) {
            final ArrayMap<WindowContainer, Integer> reasons = new ArrayMap<>(1);
            reasons.put(mTargetActivityRecord, APP_TRANSITION_RECENTS_ANIM);
            mService.mAtmService.mTaskSupervisor.getActivityMetricsLogger()
                    .notifyTransitionStarting(reasons);
        }
    }

    boolean isNavigationBarAttachedToApp() {
        return mNavigationBarAttachedToApp;
    }

    @VisibleForTesting
    WindowState getNavigationBarWindow() {
        return mDisplayContent.getDisplayPolicy().getNavigationBar();
    }

    private void attachNavigationBarToApp() {
        if (!mShouldAttachNavBarToAppDuringTransition
                // Skip the case where the nav bar is controlled by fade rotation.
                || mDisplayContent.getAsyncRotationController() != null) {
            return;
        }
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter adapter = mPendingAnimations.get(i);
            final Task task = adapter.mTask;
            if (task.isActivityTypeHomeOrRecents()) {
                continue;
            }
            mNavBarAttachedApp = task.getTopVisibleActivity();
            break;
        }

        final WindowState navWindow = getNavigationBarWindow();
        if (mNavBarAttachedApp == null || navWindow == null || navWindow.mToken == null) {
            return;
        }
        mNavigationBarAttachedToApp = true;
        navWindow.mToken.cancelAnimation();
        final SurfaceControl.Transaction t = navWindow.mToken.getPendingTransaction();
        final SurfaceControl navSurfaceControl = navWindow.mToken.getSurfaceControl();
        navWindow.setSurfaceTranslationY(-mNavBarAttachedApp.getBounds().top);
        t.reparent(navSurfaceControl, mNavBarAttachedApp.getSurfaceControl());
        t.show(navSurfaceControl);

        final WindowContainer imeContainer = mDisplayContent.getImeContainer();
        if (imeContainer.isVisible()) {
            t.setRelativeLayer(navSurfaceControl, imeContainer.getSurfaceControl(), 1);
        } else {
            // Place the nav bar on top of anything else in the top activity.
            t.setLayer(navSurfaceControl, Integer.MAX_VALUE);
        }
        if (mStatusBar != null) {
            mStatusBar.setNavigationBarLumaSamplingEnabled(mDisplayId, false);
        }
    }

    @VisibleForTesting
    void restoreNavigationBarFromApp(boolean animate) {
        if (!mNavigationBarAttachedToApp) {
            return;
        }
        mNavigationBarAttachedToApp = false;

        if (mStatusBar != null) {
            mStatusBar.setNavigationBarLumaSamplingEnabled(mDisplayId, true);
        }

        final WindowState navWindow = getNavigationBarWindow();
        if (navWindow == null) {
            return;
        }
        navWindow.setSurfaceTranslationY(0);

        final WindowToken navToken = navWindow.mToken;
        if (navToken == null) {
            return;
        }
        final SurfaceControl.Transaction t = mDisplayContent.getPendingTransaction();
        final WindowContainer parent = navToken.getParent();
        t.setLayer(navToken.getSurfaceControl(), navToken.getLastLayer());

        if (animate) {
            final NavBarFadeAnimationController controller =
                        new NavBarFadeAnimationController(mDisplayContent);
            controller.fadeWindowToken(true);
        } else {
            // Reparent the SurfaceControl of nav bar token back.
            t.reparent(navToken.getSurfaceControl(), parent.getSurfaceControl());
        }
    }

    void animateNavigationBarForAppLaunch(long duration) {
        if (!mShouldAttachNavBarToAppDuringTransition
                // Skip the case where the nav bar is controlled by fade rotation.
                || mDisplayContent.getAsyncRotationController() != null
                || mNavigationBarAttachedToApp
                || mNavBarAttachedApp == null) {
            return;
        }

        final NavBarFadeAnimationController controller =
                new NavBarFadeAnimationController(mDisplayContent);
        controller.fadeOutAndInSequentially(duration, null /* fadeOutParent */,
                mNavBarAttachedApp.getSurfaceControl());
    }

    void addTaskToTargets(Task task, OnAnimationFinishedCallback finishedCallback) {
        if (mRunner != null) {
            mIsAddingTaskToTargets = task != null;
            mNavBarAttachedApp = task == null ? null : task.getTopVisibleActivity();
            // No need to send task appeared when the task target already exists, or when the
            // task is being managed as a multi-window mode outside of recents (e.g. bubbles).
            if (isAnimatingTask(task) || skipAnimation(task)) {
                return;
            }
            collectTaskRemoteAnimations(task, MODE_OPENING, finishedCallback);
        }
    }

    void sendTasksAppeared() {
        if (mPendingTaskAppears.isEmpty() || mRunner == null) return;
        try {
            final RemoteAnimationTarget[] targets = mPendingTaskAppears.toArray(
                    new RemoteAnimationTarget[0]);
            mRunner.onTasksAppeared(targets);
            mPendingTaskAppears.clear();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report task appeared", e);
        }
    }

    private void collectTaskRemoteAnimations(Task task, int mode,
            OnAnimationFinishedCallback finishedCallback) {
        final SparseBooleanArray recentTaskIds =
                mService.mAtmService.getRecentTasks().getRecentTaskIds();

        // The target must be built off the root task (the leaf task surface would be cropped
        // within the root surface). However, recents only tracks leaf task ids, so we'll traverse
        // and create animation target for all visible leaf tasks.
        task.forAllLeafTasks(leafTask -> {
            if (!leafTask.shouldBeVisible(null /* starting */)) {
                return;
            }
            final int taskId = leafTask.mTaskId;
            TaskAnimationAdapter adapter = addAnimation(leafTask,
                    !recentTaskIds.get(taskId), true /* hidden */, finishedCallback);
            mPendingNewTaskTargets.add(taskId);
            final RemoteAnimationTarget target =
                    adapter.createRemoteAnimationTarget(taskId, mode);
            if (target != null) {
                mPendingTaskAppears.add(target);
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                        "collectTaskRemoteAnimations, target: %s", target);
            }
        }, false /* traverseTopToBottom */);
    }

    private boolean removeTaskInternal(int taskId) {
        boolean result = false;
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            // Only allows when task target has became visible to user, to prevent
            // the flickering during remove animation and task visible.
            final TaskAnimationAdapter target = mPendingAnimations.get(i);
            if (target.mTask.mTaskId == taskId && target.mTask.isOnTop()) {
                removeAnimation(target);
                final int taskIndex = mPendingNewTaskTargets.indexOf(taskId);
                if (taskIndex != -1) {
                    mPendingNewTaskTargets.remove(taskIndex);
                }
                result = true;
                break;
            }
        }
        return result;
    }

    private RemoteAnimationTarget[] createAppAnimations() {
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter taskAdapter = mPendingAnimations.get(i);
            final RemoteAnimationTarget target =
                    taskAdapter.createRemoteAnimationTarget(INVALID_TASK_ID, MODE_UNKNOWN);
            if (target != null) {
                targets.add(target);
            } else {
                removeAnimation(taskAdapter);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    private RemoteAnimationTarget[] createWallpaperAnimations() {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "createWallpaperAnimations()");
        return WallpaperAnimationAdapter.startWallpaperAnimations(mDisplayContent, 0L, 0L,
                adapter -> {
                    synchronized (mService.mGlobalLock) {
                        // If the wallpaper animation is canceled, continue with the recents
                        // animation
                        mPendingWallpaperAnimations.remove(adapter);
                    }
                }, mPendingWallpaperAnimations);
    }

    void forceCancelAnimation(@ReorderMode int reorderMode, String reason) {
        if (!mCanceled) {
            cancelAnimation(reorderMode, reason);
        } else {
            continueDeferredCancelAnimation();
        }
    }

    void cancelAnimation(@ReorderMode int reorderMode, String reason) {
        cancelAnimation(reorderMode, false /*screenshot */, reason);
    }

    void cancelAnimationWithScreenshot(boolean screenshot) {
        cancelAnimation(REORDER_KEEP_IN_PLACE, screenshot, "rootTaskOrderChanged");
    }

    /**
     * Cancels the running animation when starting home, providing a snapshot for the runner to
     * properly handle the cancellation. This call uses the provided hint to determine how to
     * finish the animation.
     */
    public void cancelAnimationForHomeStart() {
        final int reorderMode = mTargetActivityType == ACTIVITY_TYPE_HOME && mWillFinishToHome
                ? REORDER_MOVE_TO_TOP
                : REORDER_KEEP_IN_PLACE;
        cancelAnimation(reorderMode, true /* screenshot */, "cancelAnimationForHomeStart");
    }

    /**
     * Cancels the running animation when there is a display change, providing a snapshot for the
     * runner to properly handle the cancellation. This call uses the provided hint to determine
     * how to finish the animation.
     */
    public void cancelAnimationForDisplayChange() {
        cancelAnimation(mWillFinishToHome ? REORDER_MOVE_TO_TOP : REORDER_MOVE_TO_ORIGINAL_POSITION,
                true /* screenshot */, "cancelAnimationForDisplayChange");
    }

    private void cancelAnimation(@ReorderMode int reorderMode, boolean screenshot, String reason) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS, "cancelAnimation(): reason=%s", reason);
        synchronized (mService.getWindowManagerLock()) {
            if (mCanceled) {
                // We've already canceled the animation
                return;
            }
            mService.mH.removeCallbacks(mFailsafeRunnable);
            mCanceled = true;

            if (screenshot && !mPendingAnimations.isEmpty()) {
                final ArrayMap<Task, TaskSnapshot> snapshotMap = screenshotRecentTasks();
                mPendingCancelWithScreenshotReorderMode = reorderMode;

                if (!snapshotMap.isEmpty()) {
                    try {
                        int[] taskIds = new int[snapshotMap.size()];
                        TaskSnapshot[] snapshots = new TaskSnapshot[snapshotMap.size()];
                        for (int i = snapshotMap.size() - 1; i >= 0; i--) {
                            taskIds[i] = snapshotMap.keyAt(i).mTaskId;
                            snapshots[i] = snapshotMap.valueAt(i);
                        }
                        mRunner.onAnimationCanceled(taskIds, snapshots);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to cancel recents animation", e);
                    }
                    // Schedule a new failsafe for if the runner doesn't clean up the screenshot
                    scheduleFailsafe();
                    return;
                }
                // Fallback to a normal cancel since we couldn't screenshot
            }

            // Notify the runner and clean up the animation immediately
            // Note: In the fallback case, this can trigger multiple onAnimationCancel() calls
            // to the runner if we this actually triggers cancel twice on the caller
            try {
                mRunner.onAnimationCanceled(null /* taskIds */, null /* taskSnapshots */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to cancel recents animation", e);
            }
            mCallbacks.onAnimationFinished(reorderMode, false /* sendUserLeaveHint */);
        }
    }

    @VisibleForTesting
    void continueDeferredCancelAnimation() {
        mCallbacks.onAnimationFinished(mPendingCancelWithScreenshotReorderMode,
                false /* sendUserLeaveHint */);
    }

    @VisibleForTesting
    void setWillFinishToHome(boolean willFinishToHome) {
        mWillFinishToHome = willFinishToHome;
    }

    /**
     * Cancel recents animation when the next app transition starts.
     * <p>
     * When we cancel the recents animation due to a root task order change, we can't just cancel it
     * immediately as it would lead to a flicker in Launcher if we just remove the task from the
     * leash. Instead we screenshot the previous task and replace the child of the leash with the
     * screenshot, so that Launcher can still control the leash lifecycle & make the next app
     * transition animate smoothly without flickering.
     */
    void setCancelOnNextTransitionStart() {
        mCancelOnNextTransitionStart = true;
    }

    /**
     * Requests that we attempt to defer the cancel until the next app transition if we are
     * canceling from a root task order change.  If {@param screenshot} is specified, then the
     * system will replace the contents of the leash with a screenshot, which must be cleaned up
     * when the runner calls cleanUpScreenshot().
     */
    void setDeferredCancel(boolean defer, boolean screenshot) {
        mRequestDeferCancelUntilNextTransition = defer;
        mCancelDeferredWithScreenshot = screenshot;
    }

    /**
     * @return Whether we should defer the cancel from a root task order change until the next app
     * transition.
     */
    boolean shouldDeferCancelUntilNextTransition() {
        return mRequestDeferCancelUntilNextTransition;
    }

    /**
     * @return Whether we should both defer the cancel from a root task order change until the next
     * app transition, and also that the deferred cancel should replace the contents of the leash
     * with a screenshot.
     */
    boolean shouldDeferCancelWithScreenshot() {
        return mRequestDeferCancelUntilNextTransition && mCancelDeferredWithScreenshot;
    }

    private ArrayMap<Task, TaskSnapshot> screenshotRecentTasks() {
        final TaskSnapshotController snapshotController = mService.mTaskSnapshotController;
        final ArrayMap<Task, TaskSnapshot> snapshotMap = new ArrayMap<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter adapter = mPendingAnimations.get(i);
            final Task task = adapter.mTask;
            snapshotController.recordTaskSnapshot(task, false /* allowSnapshotHome */);
            final TaskSnapshot snapshot = snapshotController.getSnapshot(task.mTaskId, task.mUserId,
                    false /* restoreFromDisk */, false /* isLowResolution */);
            if (snapshot != null) {
                snapshotMap.put(task, snapshot);
                // Defer until the runner calls back to cleanupScreenshot()
                adapter.setSnapshotOverlay(snapshot);
            }
        }
        snapshotController.addSkipClosingAppSnapshotTasks(snapshotMap.keySet());
        return snapshotMap;
    }

    void cleanupAnimation(@ReorderMode int reorderMode) {
        ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                        "cleanupAnimation(): Notify animation finished mPendingAnimations=%d "
                                + "reorderMode=%d",
                        mPendingAnimations.size(), reorderMode);
        if (reorderMode != REORDER_MOVE_TO_ORIGINAL_POSITION
                && mTargetActivityRecord != mDisplayContent.topRunningActivity()) {
            // Notify the state at the beginning because the removeAnimation may notify the
            // transition is finished. This is a signal that there will be a next transition.
            mDisplayContent.mFixedRotationTransitionListener.notifyRecentsWillBeTop();
        }
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter taskAdapter = mPendingAnimations.get(i);
            if (reorderMode == REORDER_MOVE_TO_TOP || reorderMode == REORDER_KEEP_IN_PLACE) {
                taskAdapter.mTask.dontAnimateDimExit();
            }
            removeAnimation(taskAdapter);
            taskAdapter.onCleanup();
        }
        // Should already be empty, but clean-up pending task-appears in-case they weren't sent.
        mPendingNewTaskTargets.clear();
        mPendingTaskAppears.clear();

        for (int i = mPendingWallpaperAnimations.size() - 1; i >= 0; i--) {
            final WallpaperAnimationAdapter wallpaperAdapter = mPendingWallpaperAnimations.get(i);
            removeWallpaperAnimation(wallpaperAdapter);
        }

        restoreNavigationBarFromApp(
                reorderMode == REORDER_MOVE_TO_TOP || mIsAddingTaskToTargets /* animate */);

        // Clear any pending failsafe runnables
        mService.mH.removeCallbacks(mFailsafeRunnable);
        mDisplayContent.mAppTransition.unregisterListener(mAppTransitionListener);

        // Clear references to the runner
        unlinkToDeathOfRunner();
        mRunner = null;
        mCanceled = true;

        // Restore IME icon only when moving the original app task to front from recents, in case
        // IME icon may missing if the moving task has already been the current focused task.
        if (reorderMode == REORDER_MOVE_TO_ORIGINAL_POSITION && !mIsAddingTaskToTargets) {
            InputMethodManagerInternal.get().updateImeWindowStatus(false /* disableImeIcon */);
        }

        // Update the input windows after the animation is complete
        final InputMonitor inputMonitor = mDisplayContent.getInputMonitor();
        inputMonitor.updateInputWindowsLw(true /*force*/);

        // We have deferred all notifications to the target app as a part of the recents animation,
        // so if we are actually transitioning there, notify again here
        if (mTargetActivityRecord != null) {
            if (reorderMode == REORDER_MOVE_TO_TOP || reorderMode == REORDER_KEEP_IN_PLACE) {
                mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(
                        mTargetActivityRecord.token);
            }
        }
        mDisplayContent.mFixedRotationTransitionListener.onFinishRecentsAnimation();

        // Notify that the animation has ended
        if (mStatusBar != null) {
            mStatusBar.onRecentsAnimationStateChanged(false /* running */);
        }
    }

    void scheduleFailsafe() {
        mService.mH.postDelayed(mFailsafeRunnable, FAILSAFE_DELAY);
    }

    void onFailsafe() {
        forceCancelAnimation(
                mWillFinishToHome ? REORDER_MOVE_TO_TOP : REORDER_MOVE_TO_ORIGINAL_POSITION,
                "onFailsafe");
    }

    private void linkToDeathOfRunner() throws RemoteException {
        if (!mLinkedToDeathOfRunner) {
            mRunner.asBinder().linkToDeath(this, 0);
            mLinkedToDeathOfRunner = true;
        }
    }

    private void unlinkToDeathOfRunner() {
        if (mLinkedToDeathOfRunner) {
            mRunner.asBinder().unlinkToDeath(this, 0);
            mLinkedToDeathOfRunner = false;
        }
    }

    @Override
    public void binderDied() {
        forceCancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "binderDied");

        synchronized (mService.getWindowManagerLock()) {
            // Clear associated input consumers on runner death
            final InputMonitor inputMonitor = mDisplayContent.getInputMonitor();
            inputMonitor.destroyInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);
        }
    }

    void checkAnimationReady(WallpaperController wallpaperController) {
        if (mPendingStart) {
            final boolean wallpaperReady = !isTargetOverWallpaper()
                    || (wallpaperController.getWallpaperTarget() != null
                            && wallpaperController.wallpaperTransitionReady());
            if (wallpaperReady) {
                mService.getRecentsAnimationController().startAnimation();
            }
        }
    }

    boolean isWallpaperVisible(WindowState w) {
        return w != null && w.mAttrs.type == TYPE_BASE_APPLICATION &&
                ((w.mActivityRecord != null && mTargetActivityRecord == w.mActivityRecord)
                        || isAnimatingTask(w.getTask()))
                && isTargetOverWallpaper() && w.isOnScreen();
    }

    /**
     * @return Whether to use the input consumer to override app input to route home/recents.
     */
    boolean shouldApplyInputConsumer(ActivityRecord activity) {
        // Only apply the input consumer if it is enabled, it is not the target (home/recents)
        // being revealed with the transition, and we are actively animating the app as a part of
        // the animation
        return mInputConsumerEnabled && activity != null
                && !isTargetApp(activity) && isAnimatingApp(activity);
    }

    boolean updateInputConsumerForApp(InputWindowHandle inputWindowHandle) {
        // Update the input consumer touchable region to match the target app main window
        final WindowState targetAppMainWindow = getTargetAppMainWindow();
        if (targetAppMainWindow != null) {
            targetAppMainWindow.getBounds(mTmpRect);
            inputWindowHandle.touchableRegion.set(mTmpRect);
            return true;
        }
        return false;
    }

    boolean isTargetApp(ActivityRecord activity) {
        return mTargetActivityRecord != null && activity == mTargetActivityRecord;
    }

    private boolean isTargetOverWallpaper() {
        if (mTargetActivityRecord == null) {
            return false;
        }
        return mTargetActivityRecord.windowsCanBeWallpaperTarget();
    }

    WindowState getTargetAppMainWindow() {
        if (mTargetActivityRecord == null) {
            return null;
        }
        return mTargetActivityRecord.findMainWindow();
    }

    DisplayArea getTargetAppDisplayArea() {
        if (mTargetActivityRecord == null) {
            return null;
        }
        return mTargetActivityRecord.getDisplayArea();
    }

    boolean isAnimatingTask(Task task) {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            if (task == mPendingAnimations.get(i).mTask) {
                return true;
            }
        }
        return false;
    }

    boolean isAnimatingWallpaper(WallpaperWindowToken token) {
        for (int i = mPendingWallpaperAnimations.size() - 1; i >= 0; i--) {
            if (token == mPendingWallpaperAnimations.get(i).getToken()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnimatingApp(ActivityRecord activity) {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            if (activity.isDescendantOf(mPendingAnimations.get(i).mTask)) {
                return true;
            }
        }
        return false;
    }

    boolean shouldIgnoreForAccessibility(WindowState windowState) {
        final Task task = windowState.getTask();
        return task != null && isAnimatingTask(task) && !isTargetApp(windowState.mActivityRecord);
    }

    /**
     * If the animation target ActivityRecord has a fixed rotation ({@link
     * WindowToken#hasFixedRotationTransform()}, the provided wallpaper will be rotated accordingly.
     *
     * This avoids any screen rotation animation when animating to the Recents view.
     */
    void linkFixedRotationTransformIfNeeded(@NonNull WindowToken wallpaper) {
        if (mTargetActivityRecord == null) {
            return;
        }
        wallpaper.linkFixedRotationTransform(mTargetActivityRecord);
    }

    @VisibleForTesting
    class TaskAnimationAdapter implements AnimationAdapter {

        private final Task mTask;
        private SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private @AnimationType int mLastAnimationType;
        private final boolean mIsRecentTaskInvisible;
        private RemoteAnimationTarget mTarget;
        private final Rect mBounds = new Rect();
        // The bounds of the target relative to its parent.
        private final Rect mLocalBounds = new Rect();
        // The final surface transaction when animation is finished.
        private PictureInPictureSurfaceTransaction mFinishTransaction;
        // An overlay used to mask the content as an app goes into PIP
        private SurfaceControl mFinishOverlay;
        // An overlay used for canceling the animation with a screenshot
        private SurfaceControl mSnapshotOverlay;

        TaskAnimationAdapter(Task task, boolean isRecentTaskInvisible) {
            mTask = task;
            mIsRecentTaskInvisible = isRecentTaskInvisible;
            mBounds.set(mTask.getBounds());

            mLocalBounds.set(mBounds);
            Point tmpPos = new Point();
            mTask.getRelativePosition(tmpPos);
            mLocalBounds.offsetTo(tmpPos.x, tmpPos.y);
        }

        /**
         * @param overrideTaskId overrides the target's taskId. It may differ from mTaskId and thus
         *                       can differ from taskInfo. This mismatch is needed, however, in
         *                       some cases where we are animating root tasks but need need leaf
         *                       ids for identification. If this is INVALID (-1), then mTaskId
         *                       will be used.
         * @param overrideMode overrides the target's mode. If this is -1, the mode will be
         *                     calculated relative to going to the target activity (ie. OPENING if
         *                     this is the target task, CLOSING otherwise).
         */
        RemoteAnimationTarget createRemoteAnimationTarget(int overrideTaskId, int overrideMode) {
            ActivityRecord topApp = mTask.getTopRealVisibleActivity();
            if (topApp == null) {
                topApp = mTask.getTopVisibleActivity();
            }
            final WindowState mainWindow = topApp != null
                    ? topApp.findMainWindow()
                    : null;
            if (mainWindow == null) {
                return null;
            }
            final Rect insets = mainWindow.getInsetsStateWithVisibilityOverride().calculateInsets(
                    mBounds, Type.systemBars(), false /* ignoreVisibility */).toRect();
            InsetUtils.addInsets(insets, mainWindow.mActivityRecord.getLetterboxInsets());
            final int mode = overrideMode != MODE_UNKNOWN
                    ? overrideMode
                    : topApp.getActivityType() == mTargetActivityType
                            ? MODE_OPENING
                            : MODE_CLOSING;
            if (overrideTaskId < 0) {
                overrideTaskId = mTask.mTaskId;
            }
            mTarget = new RemoteAnimationTarget(overrideTaskId, mode, mCapturedLeash,
                    !topApp.fillsParent(), new Rect(),
                    insets, mTask.getPrefixOrderIndex(), new Point(mBounds.left, mBounds.top),
                    mLocalBounds, mBounds, mTask.getWindowConfiguration(),
                    mIsRecentTaskInvisible, null, null, mTask.getTaskInfo(),
                    topApp.checkEnterPictureInPictureAppOpsState());

            final ActivityRecord topActivity = mTask.getTopNonFinishingActivity();
            if (topActivity != null && topActivity.mStartingData != null
                    && topActivity.mStartingData.hasImeSurface()) {
                mTarget.setWillShowImeOnTarget(true);
            }
            return mTarget;
        }

        void setSnapshotOverlay(TaskSnapshot snapshot) {
            // Create a surface control for the snapshot and reparent it to the leash
            final HardwareBuffer buffer = snapshot.getHardwareBuffer();
            if (buffer == null) {
                return;
            }

            final SurfaceSession session = new SurfaceSession();
            mSnapshotOverlay = mService.mSurfaceControlFactory.apply(session)
                    .setName("RecentTaskScreenshotSurface")
                    .setCallsite("TaskAnimationAdapter.setSnapshotOverlay")
                    .setFormat(buffer.getFormat())
                    .setParent(mCapturedLeash)
                    .setBLASTLayer()
                    .build();

            final float scale = 1.0f * mTask.getBounds().width() / buffer.getWidth();
            mTask.getPendingTransaction()
                    .setBuffer(mSnapshotOverlay, GraphicBuffer.createFromHardwareBuffer(buffer))
                    .setColorSpace(mSnapshotOverlay, snapshot.getColorSpace())
                    .setLayer(mSnapshotOverlay, Integer.MAX_VALUE)
                    .setMatrix(mSnapshotOverlay, scale, 0, 0, scale)
                    .show(mSnapshotOverlay)
                    .apply();
        }

        void onRemove() {
            if (mSnapshotOverlay != null) {
                // Clean up the snapshot overlay if necessary
                mTask.getPendingTransaction()
                        .remove(mSnapshotOverlay)
                        .apply();
                mSnapshotOverlay = null;
            }
            mTask.setCanAffectSystemUiFlags(true);
            mCapturedFinishCallback.onAnimationFinished(mLastAnimationType, this);
        }

        void onCleanup() {
            final Transaction pendingTransaction = mTask.getPendingTransaction();
            if (mFinishTransaction != null) {
                // Reparent the overlay
                if (mFinishOverlay != null) {
                    pendingTransaction.reparent(mFinishOverlay, mTask.mSurfaceControl);
                }

                // Transfer the transform from the leash to the task
                PictureInPictureSurfaceTransaction.apply(mFinishTransaction,
                        mTask.mSurfaceControl, pendingTransaction);
                mTask.setLastRecentsAnimationTransaction(mFinishTransaction, mFinishOverlay);
                if (mDisplayContent.isFixedRotationLaunchingApp(mTargetActivityRecord)) {
                    // The transaction is needed for position when rotating the display.
                    mDisplayContent.mPinnedTaskController.setEnterPipTransaction(
                            mFinishTransaction);
                }
                // In the case where we are transferring the transform to the task in preparation
                // for entering PIP, we disable the task being able to affect sysui flags otherwise
                // it may cause a flash
                if (mTask.getActivityType() != mTargetActivityType
                        && mFinishTransaction.getShouldDisableCanAffectSystemUiFlags()) {
                    mTask.setCanAffectSystemUiFlags(false);
                }
                mFinishTransaction = null;
                mFinishOverlay = null;
                pendingTransaction.apply();
            } else if (!mTask.isAttached()) {
                // Apply the task's pending transaction in case it is detached and its transaction
                // is not reachable.
                pendingTransaction.apply();
            }
        }

        @VisibleForTesting
        public SurfaceControl getSnapshotOverlay() {
            return mSnapshotOverlay;
        }

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                @AnimationType int type, @NonNull OnAnimationFinishedCallback finishCallback) {
            // Restore position and root task crop until client has a chance to modify it.
            t.setPosition(animationLeash, mLocalBounds.left, mLocalBounds.top);
            mTmpRect.set(mLocalBounds);
            mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, mTmpRect);
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
            mLastAnimationType = type;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            // Cancel the animation immediately if any single task animator is canceled
            cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "taskAnimationAdapterCanceled");
        }

        @Override
        public long getDurationHint() {
            return 0;
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.println("task=" + mTask);
            if (mTarget != null) {
                pw.print(prefix); pw.println("Target:");
                mTarget.dump(pw, prefix + "  ");
            } else {
                pw.print(prefix); pw.println("Target: null");
            }
            pw.println("mIsRecentTaskInvisible=" + mIsRecentTaskInvisible);
            pw.println("mLocalBounds=" + mLocalBounds);
            pw.println("mFinishTransaction=" + mFinishTransaction);
            pw.println("mBounds=" + mBounds);
            pw.println("mIsRecentTaskInvisible=" + mIsRecentTaskInvisible);
        }

        @Override
        public void dumpDebug(ProtoOutputStream proto) {
            final long token = proto.start(REMOTE);
            if (mTarget != null) {
                mTarget.dumpDebug(proto, TARGET);
            }
            proto.end(token);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println(RecentsAnimationController.class.getSimpleName() + ":");
        pw.print(innerPrefix); pw.println("mPendingStart=" + mPendingStart);
        pw.print(innerPrefix); pw.println("mPendingAnimations=" + mPendingAnimations.size());
        pw.print(innerPrefix); pw.println("mCanceled=" + mCanceled);
        pw.print(innerPrefix); pw.println("mInputConsumerEnabled=" + mInputConsumerEnabled);
        pw.print(innerPrefix); pw.println("mTargetActivityRecord=" + mTargetActivityRecord);
        pw.print(innerPrefix); pw.println("isTargetOverWallpaper=" + isTargetOverWallpaper());
        pw.print(innerPrefix); pw.println("mRequestDeferCancelUntilNextTransition="
                + mRequestDeferCancelUntilNextTransition);
        pw.print(innerPrefix); pw.println("mCancelOnNextTransitionStart="
                + mCancelOnNextTransitionStart);
        pw.print(innerPrefix); pw.println("mCancelDeferredWithScreenshot="
                + mCancelDeferredWithScreenshot);
        pw.print(innerPrefix); pw.println("mPendingCancelWithScreenshotReorderMode="
                + mPendingCancelWithScreenshotReorderMode);
    }
}
