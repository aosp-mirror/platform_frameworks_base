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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_RECENTS_ANIM;
import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.BoundsAnimationController.FADE_IN;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS;
import static com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import android.annotation.IntDef;
import android.app.ActivityManager.TaskSnapshot;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.InputWindowHandle;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.utils.InsetUtils;

import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.ArrayList;

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
    private final StatusBarManagerInternal mStatusBar;
    private IRecentsAnimationRunner mRunner;
    private final RecentsAnimationCallbacks mCallbacks;
    private final ArrayList<TaskAnimationAdapter> mPendingAnimations = new ArrayList<>();
    private final int mDisplayId;
    private final Runnable mFailsafeRunnable = () ->
            cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "failSafeRunnable");

    // The recents component app token that is shown behind the visibile tasks
    private AppWindowToken mTargetAppToken;
    private DisplayContent mDisplayContent;
    private int mTargetActivityType;
    private Rect mMinimizedHomeBounds = new Rect();

    // We start the RecentsAnimationController in a pending-start state since we need to wait for
    // the wallpaper/activity to draw before we can give control to the handler to start animating
    // the visible task surfaces
    private boolean mPendingStart = true;

    // Set when the animation has been canceled
    private boolean mCanceled;

    // Whether or not the input consumer is enabled. The input consumer must be both registered and
    // enabled for it to start intercepting touch events.
    private boolean mInputConsumerEnabled;

    // Whether or not the recents animation should cause the primary split-screen stack to be
    // minimized
    private boolean mSplitScreenMinimized;

    private final Rect mTmpRect = new Rect();

    private boolean mLinkedToDeathOfRunner;

    // Whether to try to defer canceling from a stack order change until the next transition
    private boolean mRequestDeferCancelUntilNextTransition;
    // Whether to actually defer canceling until the next transition
    private boolean mCancelOnNextTransitionStart;
    // Whether to take a screenshot when handling a deferred cancel
    private boolean mCancelDeferredWithScreenshot;

    /**
     * Animates the screenshot of task that used to be controlled by RecentsAnimation.
     * @see {@link #setCancelOnNextTransitionStart}
     */
    SurfaceAnimator mRecentScreenshotAnimator;

    /**
     * An app transition listener to cancel the recents animation only after the app transition
     * starts or is canceled.
     */
    final AppTransitionListener mAppTransitionListener = new AppTransitionListener() {
        @Override
        public int onAppTransitionStartingLocked(int transit, long duration,
                long statusBarAnimationStartTime, long statusBarAnimationDuration) {
            continueDeferredCancel();
            return 0;
        }

        @Override
        public void onAppTransitionCancelledLocked(int transit) {
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
            if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "screenshotTask(" + taskId + "):"
                    + " mCanceled=" + mCanceled);
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
                            return snapshotController.getSnapshot(taskId, 0 /* userId */,
                                    false /* restoreFromDisk */, false /* reducedResolution */);
                        }
                    }
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void finish(boolean moveHomeToTop, boolean sendUserLeaveHint) {
            if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "finish(" + moveHomeToTop + "):"
                    + " mCanceled=" + mCanceled);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return;
                    }
                }

                // Note, the callback will handle its own synchronization, do not lock on WM lock
                // prior to calling the callback
                mCallbacks.onAnimationFinished(moveHomeToTop
                        ? REORDER_MOVE_TO_TOP
                        : REORDER_MOVE_TO_ORIGINAL_POSITION, sendUserLeaveHint);
                mDisplayContent.mBoundsAnimationController.setAnimationType(FADE_IN);
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
                    mService.mWindowPlacerLocked.requestTraversal();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setInputConsumerEnabled(boolean enabled) {
            if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "setInputConsumerEnabled(" + enabled + "):"
                    + " mCanceled=" + mCanceled);
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
        public void setSplitScreenMinimized(boolean minimized) {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return;
                    }

                    mSplitScreenMinimized = minimized;
                    mService.checkSplitScreenMinimizedChanged(true /* animate */);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void hideCurrentInputMethod() {
            final long token = Binder.clearCallingIdentity();
            try {
                final InputMethodManagerInternal inputMethodManagerInternal =
                        LocalServices.getService(InputMethodManagerInternal.class);
                if (inputMethodManagerInternal != null) {
                    inputMethodManagerInternal.hideCurrentInputMethod();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        @Deprecated
        public void setCancelWithDeferredScreenshot(boolean screenshot) {
            synchronized (mService.mGlobalLock) {
                setDeferredCancel(true /* deferred */, screenshot);
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
            synchronized (mService.mGlobalLock) {
                if (mRecentScreenshotAnimator != null) {
                    mRecentScreenshotAnimator.cancelAnimation();
                    mRecentScreenshotAnimator = null;
                }
            }
        }
    };

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
    }

    /**
     * Initializes the recents animation controller. This is a separate call from the constructor
     * because it may call cancelAnimation() which needs to properly clean up the controller
     * in the window manager.
     */
    public void initialize(int targetActivityType, SparseBooleanArray recentTaskIds) {
        mTargetActivityType = targetActivityType;
        mDisplayContent.mAppTransition.registerListenerLocked(mAppTransitionListener);

        // Make leashes for each of the visible/target tasks and add it to the recents animation to
        // be started
        final ArrayList<Task> visibleTasks = mDisplayContent.getVisibleTasks();
        final TaskStack targetStack = mDisplayContent.getStack(WINDOWING_MODE_UNDEFINED,
                targetActivityType);
        if (targetStack != null) {
            for (int i = targetStack.getChildCount() - 1; i >= 0; i--) {
                final Task t = targetStack.getChildAt(i);
                if (!visibleTasks.contains(t)) {
                    visibleTasks.add(t);
                }
            }
        }
        final int taskCount = visibleTasks.size();
        for (int i = 0; i < taskCount; i++) {
            final Task task = visibleTasks.get(i);
            final WindowConfiguration config = task.getWindowConfiguration();
            if (config.tasksAreFloating()
                    || config.getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                continue;
            }
            addAnimation(task, !recentTaskIds.get(task.mTaskId));
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

        // Adjust the wallpaper visibility for the showing target activity
        final AppWindowToken recentsComponentAppToken =
                targetStack.getTopChild().getTopFullscreenAppToken();
        if (recentsComponentAppToken != null) {
            if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "setHomeApp("
                    + recentsComponentAppToken.getName() + ")");
            mTargetAppToken = recentsComponentAppToken;
            if (recentsComponentAppToken.windowsCanBeWallpaperTarget()) {
                mDisplayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                mDisplayContent.setLayoutNeeded();
            }
        }

        // Save the minimized home height
        final TaskStack dockedStack = mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        mDisplayContent.getDockedDividerController().getHomeStackBoundsInDockedMode(
                mDisplayContent.getConfiguration(),
                dockedStack == null ? DOCKED_INVALID : dockedStack.getDockSide(),
                mMinimizedHomeBounds);

        mService.mWindowPlacerLocked.performSurfacePlacement();

        // Notify that the animation has started
        if (mStatusBar != null) {
            mStatusBar.onRecentsAnimationStateChanged(true /* running */);
        }
    }

    @VisibleForTesting
    AnimationAdapter addAnimation(Task task, boolean isRecentTaskInvisible) {
        if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "addAnimation(" + task.getName() + ")");
        final TaskAnimationAdapter taskAdapter = new TaskAnimationAdapter(task,
                isRecentTaskInvisible);
        task.startAnimation(task.getPendingTransaction(), taskAdapter, false /* hidden */);
        task.commitPendingTransaction();
        mPendingAnimations.add(taskAdapter);
        return taskAdapter;
    }

    @VisibleForTesting
    void removeAnimation(TaskAnimationAdapter taskAdapter) {
        if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "removeAnimation("
                + taskAdapter.mTask.mTaskId + ")");
        taskAdapter.mTask.setCanAffectSystemUiFlags(true);
        taskAdapter.mCapturedFinishCallback.onAnimationFinished(taskAdapter);
        mPendingAnimations.remove(taskAdapter);
    }

    void startAnimation() {
        if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "startAnimation(): mPendingStart=" + mPendingStart
                + " mCanceled=" + mCanceled);
        if (!mPendingStart || mCanceled) {
            // Skip starting if we've already started or canceled the animation
            return;
        }
        try {
            final ArrayList<RemoteAnimationTarget> appAnimations = new ArrayList<>();
            for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                final TaskAnimationAdapter taskAdapter = mPendingAnimations.get(i);
                final RemoteAnimationTarget target = taskAdapter.createRemoteAnimationApp();
                if (target != null) {
                    appAnimations.add(target);
                } else {
                    removeAnimation(taskAdapter);
                }
            }

            // Skip the animation if there is nothing to animate
            if (appAnimations.isEmpty()) {
                cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "startAnimation-noAppWindows");
                return;
            }

            final RemoteAnimationTarget[] appTargets = appAnimations.toArray(
                    new RemoteAnimationTarget[appAnimations.size()]);
            mPendingStart = false;

            // Perform layout if it was scheduled before to make sure that we get correct content
            // insets for the target app window after a rotation
            mDisplayContent.performLayout(false /* initial */, false /* updateInputWindows */);

            final Rect minimizedHomeBounds = mTargetAppToken != null
                    && mTargetAppToken.inSplitScreenSecondaryWindowingMode()
                            ? mMinimizedHomeBounds
                            : null;
            final Rect contentInsets;
            if (mTargetAppToken != null && mTargetAppToken.findMainWindow() != null) {
                contentInsets = mTargetAppToken.findMainWindow().getContentInsets();
            } else {
                // If the window for the activity had not yet been created, use the display insets.
                mService.getStableInsets(mDisplayId, mTmpRect);
                contentInsets = mTmpRect;
            }
            mRunner.onAnimationStart(mController, appTargets, contentInsets, minimizedHomeBounds);
            if (DEBUG_RECENTS_ANIMATIONS) {
                Slog.d(TAG, "startAnimation(): Notify animation start:");
                for (int i = 0; i < mPendingAnimations.size(); i++) {
                    final Task task = mPendingAnimations.get(i).mTask;
                    Slog.d(TAG, "\t" + task.mTaskId);
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to start recents animation", e);
        }
        final SparseIntArray reasons = new SparseIntArray();
        reasons.put(WINDOWING_MODE_FULLSCREEN, APP_TRANSITION_RECENTS_ANIM);
        mService.mAtmInternal.notifyAppTransitionStarting(reasons, SystemClock.uptimeMillis());
    }

    void cancelAnimation(@ReorderMode int reorderMode, String reason) {
        cancelAnimation(reorderMode, false /*screenshot */, reason);
    }

    void cancelAnimationWithScreenshot(boolean screenshot) {
        cancelAnimation(REORDER_KEEP_IN_PLACE, screenshot, "stackOrderChanged");
    }

    private void cancelAnimation(@ReorderMode int reorderMode, boolean screenshot, String reason) {
        if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG, "cancelAnimation(): reason=" + reason);
        synchronized (mService.getWindowManagerLock()) {
            if (mCanceled) {
                // We've already canceled the animation
                return;
            }
            mService.mH.removeCallbacks(mFailsafeRunnable);
            mCanceled = true;

            if (screenshot) {
                // Screen shot previous task when next task starts transition and notify the runner.
                // We will actually finish the animation once the runner calls cleanUpScreenshot().
                final Task task = mPendingAnimations.get(0).mTask;
                final TaskSnapshot taskSnapshot = screenshotRecentTask(task, reorderMode);
                try {
                    mRunner.onAnimationCanceled(taskSnapshot);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to cancel recents animation", e);
                }
                if (taskSnapshot == null) {
                    mCallbacks.onAnimationFinished(reorderMode, false /* sendUserLeaveHint */);
                }
            } else {
                // Otherwise, notify the runner and clean up the animation immediately
                // Note: In the fallback case, this can trigger multiple onAnimationCancel() calls
                // to the runner if we this actually triggers cancel twice on the caller
                try {
                    mRunner.onAnimationCanceled(null /* taskSnapshot */);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to cancel recents animation", e);
                }
                mCallbacks.onAnimationFinished(reorderMode, false /* sendUserLeaveHint */);
            }
        }
    }

    /**
     * Cancel recents animation when the next app transition starts.
     * <p>
     * When we cancel the recents animation due to a stack order change, we can't just cancel it
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
     * canceling from a stack order change.  If {@param screenshot} is specified, then the system
     * will replace the contents of the leash with a screenshot, which must be cleaned up when the
     * runner calls cleanUpScreenshot().
     */
    void setDeferredCancel(boolean defer, boolean screenshot) {
        mRequestDeferCancelUntilNextTransition = defer;
        mCancelDeferredWithScreenshot = screenshot;
    }

    /**
     * @return Whether we should defer the cancel from a stack order change until the next app
     * transition.
     */
    boolean shouldDeferCancelUntilNextTransition() {
        return mRequestDeferCancelUntilNextTransition;
    }

    /**
     * @return Whether we should both defer the cancel from a stack order change until the next
     * app transition, and also that the deferred cancel should replace the contents of the leash
     * with a screenshot.
     */
    boolean shouldDeferCancelWithScreenshot() {
        return mRequestDeferCancelUntilNextTransition && mCancelDeferredWithScreenshot;
    }

    TaskSnapshot screenshotRecentTask(Task task, @ReorderMode int reorderMode) {
        final TaskSnapshotController snapshotController = mService.mTaskSnapshotController;
        final ArraySet<Task> tasks = Sets.newArraySet(task);
        snapshotController.snapshotTasks(tasks);
        snapshotController.addSkipClosingAppSnapshotTasks(tasks);
        final TaskSnapshot taskSnapshot = snapshotController.getSnapshot(task.mTaskId,
                task.mUserId, false /* restoreFromDisk */, false /* reducedResolution */);
        if (taskSnapshot == null) {
            return null;
        }

        final TaskScreenshotAnimatable animatable = new TaskScreenshotAnimatable(mService.mSurfaceControlFactory, task,
                new SurfaceControl.ScreenshotGraphicBuffer(taskSnapshot.getSnapshot(),
                        taskSnapshot.getColorSpace(), false /* containsSecureLayers */));
        mRecentScreenshotAnimator = new SurfaceAnimator(
                animatable,
                () -> {
                    if (DEBUG_RECENTS_ANIMATIONS) {
                        Slog.d(TAG, "mRecentScreenshotAnimator finish");
                    }
                    mCallbacks.onAnimationFinished(reorderMode, false /* sendUserLeaveHint */);
                }, mService);
        mRecentScreenshotAnimator.transferAnimation(task.mSurfaceAnimator);
        return taskSnapshot;
    }

    void cleanupAnimation(@ReorderMode int reorderMode) {
        if (DEBUG_RECENTS_ANIMATIONS) Slog.d(TAG,
                "cleanupAnimation(): Notify animation finished mPendingAnimations="
                        + mPendingAnimations.size() + " reorderMode=" + reorderMode);
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter taskAdapter = mPendingAnimations.get(i);
            if (reorderMode == REORDER_MOVE_TO_TOP || reorderMode == REORDER_KEEP_IN_PLACE) {
                taskAdapter.mTask.dontAnimateDimExit();
            }
            removeAnimation(taskAdapter);
        }

        // Clear any pending failsafe runnables
        mService.mH.removeCallbacks(mFailsafeRunnable);
        mDisplayContent.mAppTransition.unregisterListener(mAppTransitionListener);

        // Clear references to the runner
        unlinkToDeathOfRunner();
        mRunner = null;
        mCanceled = true;

        // Make sure previous animator has cleaned-up.
        if (mRecentScreenshotAnimator != null) {
            mRecentScreenshotAnimator.cancelAnimation();
            mRecentScreenshotAnimator = null;
        }

        // Update the input windows after the animation is complete
        final InputMonitor inputMonitor = mDisplayContent.getInputMonitor();
        inputMonitor.updateInputWindowsLw(true /*force*/);

        // We have deferred all notifications to the target app as a part of the recents animation,
        // so if we are actually transitioning there, notify again here
        if (mTargetAppToken != null) {
            if (reorderMode == REORDER_MOVE_TO_TOP || reorderMode == REORDER_KEEP_IN_PLACE) {
                mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(
                        mTargetAppToken.token);
            }
        }

        // Notify that the animation has ended
        if (mStatusBar != null) {
            mStatusBar.onRecentsAnimationStateChanged(false /* running */);
        }
    }

    void scheduleFailsafe() {
        mService.mH.postDelayed(mFailsafeRunnable, FAILSAFE_DELAY);
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
        cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "binderDied");

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

    boolean isSplitScreenMinimized() {
        return mSplitScreenMinimized;
    }

    boolean isWallpaperVisible(WindowState w) {
        return w != null && w.mAttrs.type == TYPE_BASE_APPLICATION && w.mAppToken != null
                && mTargetAppToken == w.mAppToken && isTargetOverWallpaper();
    }

    /**
     * @return Whether to use the input consumer to override app input to route home/recents.
     */
    boolean shouldApplyInputConsumer(AppWindowToken appToken) {
        // Only apply the input consumer if it is enabled, it is not the target (home/recents)
        // being revealed with the transition, and we are actively animating the app as a part of
        // the animation
        return mInputConsumerEnabled && mTargetAppToken != appToken && isAnimatingApp(appToken);
    }

    boolean updateInputConsumerForApp(InputWindowHandle inputWindowHandle,
            boolean hasFocus) {
        // Update the input consumer touchable region to match the target app main window
        final WindowState targetAppMainWindow = mTargetAppToken != null
                ? mTargetAppToken.findMainWindow()
                : null;
        if (targetAppMainWindow != null) {
            targetAppMainWindow.getBounds(mTmpRect);
            inputWindowHandle.hasFocus = hasFocus;
            inputWindowHandle.touchableRegion.set(mTmpRect);
            return true;
        }
        return false;
    }

    boolean isTargetApp(AppWindowToken token) {
        return mTargetAppToken != null && token == mTargetAppToken;
    }

    private boolean isTargetOverWallpaper() {
        if (mTargetAppToken == null) {
            return false;
        }
        return mTargetAppToken.windowsCanBeWallpaperTarget();
    }

    boolean isAnimatingTask(Task task) {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            if (task == mPendingAnimations.get(i).mTask) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnimatingApp(AppWindowToken appToken) {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final Task task = mPendingAnimations.get(i).mTask;
            for (int j = task.getChildCount() - 1; j >= 0; j--) {
                final AppWindowToken app = task.getChildAt(j);
                if (app == appToken) {
                    return true;
                }
            }
        }
        return false;
    }

    @VisibleForTesting
    class TaskAnimationAdapter implements AnimationAdapter {

        private final Task mTask;
        private SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private final boolean mIsRecentTaskInvisible;
        private RemoteAnimationTarget mTarget;
        private final Point mPosition = new Point();
        private final Rect mBounds = new Rect();

        TaskAnimationAdapter(Task task, boolean isRecentTaskInvisible) {
            mTask = task;
            mIsRecentTaskInvisible = isRecentTaskInvisible;
            final WindowContainer container = mTask.getParent();
            container.getRelativeDisplayedPosition(mPosition);
            mBounds.set(container.getDisplayedBounds());
        }

        RemoteAnimationTarget createRemoteAnimationApp() {
            final AppWindowToken topApp = mTask.getTopVisibleAppToken();
            final WindowState mainWindow = topApp != null
                    ? topApp.findMainWindow()
                    : null;
            if (mainWindow == null) {
                return null;
            }
            final Rect insets = new Rect();
            mainWindow.getContentInsets(insets);
            InsetUtils.addInsets(insets, mainWindow.mAppToken.getLetterboxInsets());
            final int mode = topApp.getActivityType() == mTargetActivityType
                    ? MODE_OPENING
                    : MODE_CLOSING;
            mTarget = new RemoteAnimationTarget(mTask.mTaskId, mode, mCapturedLeash,
                    !topApp.fillsParent(), mainWindow.mWinAnimator.mLastClipRect,
                    insets, mTask.getPrefixOrderIndex(), mPosition, mBounds,
                    mTask.getWindowConfiguration(), mIsRecentTaskInvisible, null, null);
            return mTarget;
        }

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                OnAnimationFinishedCallback finishCallback) {
            // Restore z-layering, position and stack crop until client has a chance to modify it.
            t.setLayer(animationLeash, mTask.getPrefixOrderIndex());
            t.setPosition(animationLeash, mPosition.x, mPosition.y);
            mTmpRect.set(mBounds);
            mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, mTmpRect);
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
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
            pw.println("mPosition=" + mPosition);
            pw.println("mBounds=" + mBounds);
            pw.println("mIsRecentTaskInvisible=" + mIsRecentTaskInvisible);
        }

        @Override
        public void writeToProto(ProtoOutputStream proto) {
            final long token = proto.start(REMOTE);
            if (mTarget != null) {
                mTarget.writeToProto(proto, TARGET);
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
        pw.print(innerPrefix); pw.println("mSplitScreenMinimized=" + mSplitScreenMinimized);
        pw.print(innerPrefix); pw.println("mTargetAppToken=" + mTargetAppToken);
        pw.print(innerPrefix); pw.println("isTargetOverWallpaper=" + isTargetOverWallpaper());
        pw.print(innerPrefix); pw.println("mRequestDeferCancelUntilNextTransition="
                + mRequestDeferCancelUntilNextTransition);
        pw.print(innerPrefix); pw.println("mCancelOnNextTransitionStart="
                + mCancelOnNextTransitionStart);
        pw.print(innerPrefix); pw.println("mCancelDeferredWithScreenshot="
                + mCancelDeferredWithScreenshot);
    }
}
