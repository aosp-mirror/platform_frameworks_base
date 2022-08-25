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

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationRunner;
import android.window.IBackNaviAnimationController;

import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controls the back navigation animation.
 * This is throw-away code and should only be used for Android T, most code is duplicated from
 * RecentsAnimationController which should be stable to handle animation leash resources/flicker/
 * fixed rotation, etc. Remove this class at U and migrate to shell transition.
 */
public class BackNaviAnimationController implements IBinder.DeathRecipient {
    private static final String TAG = BackNavigationController.TAG;
    // Constant for a yet-to-be-calculated {@link RemoteAnimationTarget#Mode} state
    private static final int MODE_UNKNOWN = -1;

    // The activity which host this animation
    private ActivityRecord mTargetActivityRecord;
    // The original top activity
    private ActivityRecord mTopActivity;

    private final DisplayContent mDisplayContent;
    private final WindowManagerService mWindowManagerService;
    private final BackNavigationController mBackNavigationController;

    // We start the BackAnimationController in a pending-start state since we need to wait for
    // the wallpaper/activity to draw before we can give control to the handler to start animating
    // the visible task surfaces
    private boolean mPendingStart;
    private IBackAnimationRunner mRunner;
    final IBackNaviAnimationController mRemoteController;
    private boolean mLinkedToDeathOfRunner;

    private final ArrayList<TaskAnimationAdapter> mPendingAnimations = new ArrayList<>();

    BackNaviAnimationController(IBackAnimationRunner runner,
            BackNavigationController backNavigationController, int displayId) {
        mRunner = runner;
        mBackNavigationController = backNavigationController;
        mWindowManagerService = mBackNavigationController.mWindowManagerService;
        mDisplayContent = mWindowManagerService.mRoot.getDisplayContent(displayId);

        mRemoteController = new IBackNaviAnimationController.Stub() {
            @Override
            public void finish(boolean triggerBack) {
                synchronized (mWindowManagerService.getWindowManagerLock()) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        mWindowManagerService.inSurfaceTransaction(() -> {
                            mWindowManagerService.mAtmService.deferWindowLayout();
                            try {
                                if (triggerBack) {
                                    mDisplayContent.mFixedRotationTransitionListener
                                            .notifyRecentsWillBeTop();
                                    if (mTopActivity != null) {
                                        mWindowManagerService.mTaskSnapshotController
                                                .recordTaskSnapshot(mTopActivity.getTask(), false);
                                        // TODO consume moveTaskToBack?
                                        mTopActivity.commitVisibility(false, false, true);
                                    }
                                } else {
                                    mTargetActivityRecord.mTaskSupervisor
                                            .scheduleLaunchTaskBehindComplete(
                                                    mTargetActivityRecord.token);
                                }
                                cleanupAnimation();
                            } finally {
                                mWindowManagerService.mAtmService.continueWindowLayout();
                            }
                        });
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            }
        };
    }

    /**
     * @param targetActivity The home or opening activity which should host the wallpaper
     * @param topActivity The current top activity before animation start.
     */
    void initialize(ActivityRecord targetActivity, ActivityRecord topActivity) {
        mTargetActivityRecord = targetActivity;
        mTopActivity = topActivity;
        final Task topTask = mTopActivity.getTask();

        createAnimationAdapter(topTask, (type, anim) -> topTask.forAllWindows(
                win -> {
                    win.onAnimationFinished(type, anim);
                }, true));
        final Task homeTask = mTargetActivityRecord.getRootTask();
        createAnimationAdapter(homeTask, (type, anim) -> homeTask.forAllWindows(
                win -> {
                    win.onAnimationFinished(type, anim);
                }, true));
        try {
            linkToDeathOfRunner();
        } catch (RemoteException e) {
            cancelAnimation();
            return;
        }

        if (targetActivity.windowsCanBeWallpaperTarget()) {
            mDisplayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            mDisplayContent.setLayoutNeeded();
        }

        mWindowManagerService.mWindowPlacerLocked.performSurfacePlacement();

        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(targetActivity);
        mPendingStart = true;
    }

    void cleanupAnimation() {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter taskAdapter = mPendingAnimations.get(i);

            removeAnimationAdapter(taskAdapter);
            taskAdapter.onCleanup();
        }
        mTargetActivityRecord.mLaunchTaskBehind = false;
        // Clear references to the runner
        unlinkToDeathOfRunner();
        mRunner = null;

        // Update the input windows after the animation is complete
        final InputMonitor inputMonitor = mDisplayContent.getInputMonitor();
        inputMonitor.updateInputWindowsLw(true /*force*/);

        mDisplayContent.mFixedRotationTransitionListener.onFinishRecentsAnimation();
        mBackNavigationController.finishAnimation();
    }

    void removeAnimationAdapter(TaskAnimationAdapter taskAdapter) {
        taskAdapter.onRemove();
        mPendingAnimations.remove(taskAdapter);
    }

    void checkAnimationReady(WallpaperController wallpaperController) {
        if (mPendingStart) {
            final boolean wallpaperReady = !isTargetOverWallpaper()
                    || (wallpaperController.getWallpaperTarget() != null
                    && wallpaperController.wallpaperTransitionReady());
            if (wallpaperReady) {
                startAnimation();
            }
        }
    }

    boolean isWallpaperVisible(WindowState w) {
        return w != null && w.mAttrs.type == TYPE_BASE_APPLICATION
                && ((w.mActivityRecord != null && mTargetActivityRecord == w.mActivityRecord)
                        || isAnimatingTask(w.getTask()))
                && isTargetOverWallpaper() && w.isOnScreen();
    }

    boolean isAnimatingTask(Task task) {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            if (task == mPendingAnimations.get(i).mTask) {
                return true;
            }
        }
        return false;
    }

    void linkFixedRotationTransformIfNeeded(@NonNull WindowToken wallpaper) {
        if (mTargetActivityRecord == null) {
            return;
        }
        wallpaper.linkFixedRotationTransform(mTargetActivityRecord);
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

    void startAnimation() {
        if (!mPendingStart) {
            // Skip starting if we've already started or canceled the animation
            return;
        }
        // Create the app targets
        final RemoteAnimationTarget[] appTargets = createAppAnimations();

        // Skip the animation if there is nothing to animate
        if (appTargets.length == 0) {
            cancelAnimation();
            return;
        }

        mPendingStart = false;

        try {
            mRunner.onAnimationStart(mRemoteController, BackNavigationInfo.TYPE_RETURN_TO_HOME,
                    appTargets, null /* wallpapers */, null /*nonApps*/);
        } catch (RemoteException e) {
            cancelAnimation();
        }
    }

    @Override
    public void binderDied() {
        cancelAnimation();
    }

    TaskAnimationAdapter createAnimationAdapter(Task task,
            SurfaceAnimator.OnAnimationFinishedCallback finishedCallback) {
        final TaskAnimationAdapter taskAdapter = new TaskAnimationAdapter(task,
                mTargetActivityRecord, this::cancelAnimation);
        // borrow from recents since we cannot start back animation if recents is playing
        task.startAnimation(task.getPendingTransaction(), taskAdapter, false /* hidden */,
                ANIMATION_TYPE_RECENTS, finishedCallback);
        task.commitPendingTransaction();
        mPendingAnimations.add(taskAdapter);
        return taskAdapter;
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
                removeAnimationAdapter(taskAdapter);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    private void cancelAnimation() {
        synchronized (mWindowManagerService.getWindowManagerLock()) {
            // Notify the runner and clean up the animation immediately
            // Note: In the fallback case, this can trigger multiple onAnimationCancel() calls
            // to the runner if we this actually triggers cancel twice on the caller
            try {
                mRunner.onAnimationCancelled();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to cancel recents animation", e);
            }
            cleanupAnimation();
        }
    }

    private boolean isTargetOverWallpaper() {
        if (mTargetActivityRecord == null) {
            return false;
        }
        return mTargetActivityRecord.windowsCanBeWallpaperTarget();
    }

    private static class TaskAnimationAdapter implements AnimationAdapter {
        private final Task mTask;
        private SurfaceControl mCapturedLeash;
        private SurfaceAnimator.OnAnimationFinishedCallback mCapturedFinishCallback;
        @SurfaceAnimator.AnimationType private int mLastAnimationType;
        private RemoteAnimationTarget mTarget;
        private final ActivityRecord mTargetActivityRecord;
        private final Runnable mCancelCallback;

        private final Rect mBounds = new Rect();
        // The bounds of the target relative to its parent.
        private final Rect mLocalBounds = new Rect();

        TaskAnimationAdapter(Task task, ActivityRecord target, Runnable cancelCallback) {
            mTask = task;
            mBounds.set(mTask.getBounds());

            mLocalBounds.set(mBounds);
            Point tmpPos = new Point();
            mTask.getRelativePosition(tmpPos);
            mLocalBounds.offsetTo(tmpPos.x, tmpPos.y);
            mTargetActivityRecord = target;
            mCancelCallback = cancelCallback;
        }

        // Keep overrideTaskId and overrideMode now, if we need to add other type of back animation
        // on legacy transition system then they can be useful.
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
            final Rect insets =
                    mainWindow.getInsetsStateWithVisibilityOverride().calculateInsets(
                            mBounds, WindowInsets.Type.systemBars(),
                            false /* ignoreVisibility */).toRect();
            InsetUtils.addInsets(insets, mainWindow.mActivityRecord.getLetterboxInsets());
            final int mode = overrideMode != MODE_UNKNOWN
                    ? overrideMode
                    : topApp.getActivityType() == mTargetActivityRecord.getActivityType()
                            ? MODE_OPENING
                            : MODE_CLOSING;
            if (overrideTaskId < 0) {
                overrideTaskId = mTask.mTaskId;
            }
            mTarget = new RemoteAnimationTarget(overrideTaskId, mode, mCapturedLeash,
                    !topApp.fillsParent(), new Rect(),
                    insets, mTask.getPrefixOrderIndex(), new Point(mBounds.left, mBounds.top),
                    mLocalBounds, mBounds, mTask.getWindowConfiguration(),
                    true /* isNotInRecents */, null, null, mTask.getTaskInfo(),
                    topApp.checkEnterPictureInPictureAppOpsState());
            return mTarget;
        }
        @Override
        public boolean getShowWallpaper() {
            return false;
        }
        @Override
        public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                @SurfaceAnimator.AnimationType int type,
                @NonNull SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
            t.setPosition(animationLeash, mLocalBounds.left, mLocalBounds.top);
            final Rect tmpRect = new Rect();
            tmpRect.set(mLocalBounds);
            tmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, tmpRect);
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
            mLastAnimationType = type;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            mCancelCallback.run();
        }

        void onRemove() {
            mCapturedFinishCallback.onAnimationFinished(mLastAnimationType, this);
        }

        void onCleanup() {
            final SurfaceControl.Transaction pendingTransaction = mTask.getPendingTransaction();
            if (!mTask.isAttached()) {
                // Apply the task's pending transaction in case it is detached and its transaction
                // is not reachable.
                pendingTransaction.apply();
            }
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
        public void dump(PrintWriter pw, String prefix) { }

        @Override
        public void dumpDebug(ProtoOutputStream proto) { }
    }
}
