/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.shared.system;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_SLEEP;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IApplicationThread;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.IRecentsAnimationController;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.PictureInPictureSurfaceTransaction;
import android.window.RemoteTransition;
import android.window.TaskSnapshot;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.util.TransitionUtil;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Helper class to build {@link RemoteTransition} objects
 */
public class RemoteTransitionCompat {
    private static final String TAG = "RemoteTransitionCompat";

    /** Constructor specifically for recents animation */
    public static RemoteTransition newRemoteTransition(RecentsAnimationListener recents,
            IApplicationThread appThread) {
        IRemoteTransition remote = new IRemoteTransition.Stub() {
            final RecentsControllerWrap mRecentsSession = new RecentsControllerWrap();
            IBinder mToken = null;

            @Override
            public void startAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                // TODO(b/177438007): Move this set-up logic into launcher's animation impl.
                mToken = transition;
                mRecentsSession.start(recents, mToken, info, t, finishedCallback);
            }

            @Override
            public void mergeAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                if (mergeTarget.equals(mToken) && mRecentsSession.merge(info, t)) {
                    try {
                        finishedCallback.onTransitionFinished(null /* wct */, null /* sct */);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error merging transition.", e);
                    }
                    // commit taskAppeared after merge transition finished.
                    mRecentsSession.commitTasksAppearedIfNeeded();
                } else {
                    t.close();
                    info.releaseAllSurfaces();
                }
            }
        };
        return new RemoteTransition(remote, appThread, "Recents");
    }

    /**
     * Wrapper to hook up parts of recents animation to shell transition.
     * TODO(b/177438007): Remove this once Launcher handles shell transitions directly.
     */
    @VisibleForTesting
    static class RecentsControllerWrap extends IRecentsAnimationController.Default {
        private RecentsAnimationListener mListener = null;
        private IRemoteTransitionFinishedCallback mFinishCB = null;

        /**
         * List of tasks that we are switching away from via this transition. Upon finish, these
         * pausing tasks will become invisible.
         * These need to be ordered since the order must be restored if there is no task-switch.
         */
        private ArrayList<TaskState> mPausingTasks = null;

        /**
         * List of tasks that we are switching to. Upon finish, these will remain visible and
         * on top.
         */
        private ArrayList<TaskState> mOpeningTasks = null;

        private WindowContainerToken mPipTask = null;
        private WindowContainerToken mRecentsTask = null;
        private int mRecentsTaskId = 0;
        private TransitionInfo mInfo = null;
        private boolean mOpeningSeparateHome = false;
        private ArrayMap<SurfaceControl, SurfaceControl> mLeashMap = null;
        private PictureInPictureSurfaceTransaction mPipTransaction = null;
        private IBinder mTransition = null;
        private boolean mKeyguardLocked = false;
        private RemoteAnimationTarget[] mAppearedTargets;
        private boolean mWillFinishToHome = false;

        /** The animation is idle, waiting for the user to choose a task to switch to. */
        private static final int STATE_NORMAL = 0;

        /** The user chose a new task to switch to and the animation is animating to it. */
        private static final int STATE_NEW_TASK = 1;

        /** The latest state that the recents animation is operating in. */
        private int mState = STATE_NORMAL;

        void start(RecentsAnimationListener listener,
                IBinder transition, TransitionInfo info, SurfaceControl.Transaction t,
                IRemoteTransitionFinishedCallback finishedCallback) {
            if (mInfo != null) {
                throw new IllegalStateException("Trying to run a new recents animation while"
                        + " recents is already active.");
            }
            mListener = listener;
            mInfo = info;
            mFinishCB = finishedCallback;
            mPausingTasks = new ArrayList<>();
            mOpeningTasks = new ArrayList<>();
            mPipTask = null;
            mRecentsTask = null;
            mRecentsTaskId = -1;
            mLeashMap = new ArrayMap<>();
            mTransition = transition;
            mKeyguardLocked = (info.getFlags() & TRANSIT_FLAG_KEYGUARD_LOCKED) != 0;
            mState = STATE_NORMAL;

            final ArrayList<RemoteAnimationTarget> apps = new ArrayList<>();
            final ArrayList<RemoteAnimationTarget> wallpapers = new ArrayList<>();
            TransitionUtil.LeafTaskFilter leafTaskFilter = new TransitionUtil.LeafTaskFilter();
            // About layering: we divide up the "layer space" into 3 regions (each the size of
            // the change count). This lets us categorize things into above/below/between
            // while maintaining their relative ordering.
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                if (TransitionUtil.isWallpaper(change)) {
                    final RemoteAnimationTarget target = TransitionUtil.newTarget(change,
                            // wallpapers go into the "below" layer space
                            info.getChanges().size() - i, info, t, mLeashMap);
                    wallpapers.add(target);
                    // Make all the wallpapers opaque since we want them visible from the start
                    t.setAlpha(target.leash, 1);
                } else if (leafTaskFilter.test(change)) {
                    // start by putting everything into the "below" layer space.
                    final RemoteAnimationTarget target = TransitionUtil.newTarget(change,
                            info.getChanges().size() - i, info, t, mLeashMap);
                    apps.add(target);
                    if (TransitionUtil.isClosingType(change.getMode())) {
                        // raise closing (pausing) task to "above" layer so it isn't covered
                        t.setLayer(target.leash, info.getChanges().size() * 3 - i);
                        mPausingTasks.add(new TaskState(change, target.leash));
                        if (taskInfo.pictureInPictureParams != null
                                && taskInfo.pictureInPictureParams.isAutoEnterEnabled()) {
                            mPipTask = taskInfo.token;
                        }
                    } else if (taskInfo != null
                            && taskInfo.topActivityType == ACTIVITY_TYPE_RECENTS) {
                        // There's a 3p launcher, so make sure recents goes above that.
                        t.setLayer(target.leash, info.getChanges().size() * 3 - i);
                        mRecentsTask = taskInfo.token;
                        mRecentsTaskId = taskInfo.taskId;
                    } else if (taskInfo != null && taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                        mRecentsTask = taskInfo.token;
                        mRecentsTaskId = taskInfo.taskId;
                    } else if (TransitionUtil.isOpeningType(change.getMode())) {
                        mOpeningTasks.add(new TaskState(change, target.leash));
                    }
                }
            }
            t.apply();
            mListener.onAnimationStart(new RecentsAnimationControllerCompat(this),
                    apps.toArray(new RemoteAnimationTarget[apps.size()]),
                    wallpapers.toArray(new RemoteAnimationTarget[wallpapers.size()]),
                    new Rect(0, 0, 0, 0), new Rect());
        }

        @SuppressLint("NewApi")
        boolean merge(TransitionInfo info, SurfaceControl.Transaction t) {
            if (info.getType() == TRANSIT_SLEEP) {
                // A sleep event means we need to stop animations immediately, so cancel here.
                mListener.onAnimationCanceled(new HashMap<>());
                finish(mWillFinishToHome, false /* userLeaveHint */);
                return false;
            }
            ArrayList<TransitionInfo.Change> openingTasks = null;
            ArrayList<TransitionInfo.Change> closingTasks = null;
            mAppearedTargets = null;
            mOpeningSeparateHome = false;
            TransitionInfo.Change recentsOpening = null;
            boolean foundRecentsClosing = false;
            boolean hasChangingApp = false;
            final TransitionUtil.LeafTaskFilter leafTaskFilter =
                    new TransitionUtil.LeafTaskFilter();
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                final boolean isLeafTask = leafTaskFilter.test(change);
                if (TransitionUtil.isOpeningType(change.getMode())) {
                    if (mRecentsTask.equals(change.getContainer())) {
                        recentsOpening = change;
                    } else if (isLeafTask) {
                        if (taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                            // This is usually a 3p launcher
                            mOpeningSeparateHome = true;
                        }
                        if (openingTasks == null) {
                            openingTasks = new ArrayList<>();
                        }
                        openingTasks.add(change);
                    }
                } else if (TransitionUtil.isClosingType(change.getMode())) {
                    if (mRecentsTask.equals(change.getContainer())) {
                        foundRecentsClosing = true;
                    } else if (isLeafTask) {
                        if (closingTasks == null) {
                            closingTasks = new ArrayList<>();
                        }
                        closingTasks.add(change);
                    }
                } else if (change.getMode() == TRANSIT_CHANGE) {
                    // Finish recents animation if the display is changed, so the default
                    // transition handler can play the animation such as rotation effect.
                    if (change.hasFlags(TransitionInfo.FLAG_IS_DISPLAY)) {
                        mListener.onSwitchToScreenshot(() -> finish(false /* toHome */,
                                false /* userLeaveHint */));
                        return false;
                    }
                    hasChangingApp = true;
                }
            }
            if (hasChangingApp && foundRecentsClosing) {
                // This happens when a visible app is expanding (usually PiP). In this case,
                // that transition probably has a special-purpose animation, so finish recents
                // now and let it do its animation (since recents is going to be occluded).
                if (!mListener.onSwitchToScreenshot(
                        () -> finish(true /* toHome */, false /* userLeaveHint */))) {
                    Log.w(TAG, "Recents callback doesn't support support switching to screenshot"
                            + ", there might be a flicker.");
                    finish(true /* toHome */, false /* userLeaveHint */);
                }
                return false;
            }
            if (recentsOpening != null) {
                // the recents task re-appeared. This happens if the user gestures before the
                // task-switch (NEW_TASK) animation finishes.
                if (mState == STATE_NORMAL) {
                    Log.e(TAG, "Returning to recents while recents is already idle.");
                }
                if (closingTasks == null || closingTasks.size() == 0) {
                    Log.e(TAG, "Returning to recents without closing any opening tasks.");
                }
                // Setup may hide it initially since it doesn't know that overview was still active.
                t.show(recentsOpening.getLeash());
                t.setAlpha(recentsOpening.getLeash(), 1.f);
                mState = STATE_NORMAL;
            }
            boolean didMergeThings = false;
            if (closingTasks != null) {
                // Cancelling a task-switch. Move the tasks back to mPausing from mOpening
                for (int i = 0; i < closingTasks.size(); ++i) {
                    final TransitionInfo.Change change = closingTasks.get(i);
                    int openingIdx = TaskState.indexOf(mOpeningTasks, change);
                    if (openingIdx < 0) {
                        Log.e(TAG, "Back to existing recents animation from an unrecognized "
                                + "task: " + change.getTaskInfo().taskId);
                        continue;
                    }
                    mPausingTasks.add(mOpeningTasks.remove(openingIdx));
                    didMergeThings = true;
                }
            }
            if (openingTasks != null && openingTasks.size() > 0) {
                // Switching to some new tasks, add to mOpening and remove from mPausing. Also,
                // enter NEW_TASK state since this will start the switch-to animation.
                final int layer = mInfo.getChanges().size() * 3;
                final RemoteAnimationTarget[] targets =
                        new RemoteAnimationTarget[openingTasks.size()];
                for (int i = 0; i < openingTasks.size(); ++i) {
                    final TransitionInfo.Change change = openingTasks.get(i);
                    int pausingIdx = TaskState.indexOf(mPausingTasks, change);
                    if (pausingIdx >= 0) {
                        // Something is showing/opening a previously-pausing app.
                        targets[i] = TransitionUtil.newTarget(change, layer,
                                mPausingTasks.get(pausingIdx).mLeash);
                        mOpeningTasks.add(mPausingTasks.remove(pausingIdx));
                        // Setup hides opening tasks initially, so make it visible again (since we
                        // are already showing it).
                        t.show(change.getLeash());
                        t.setAlpha(change.getLeash(), 1.f);
                    } else {
                        // We are receiving new opening tasks, so convert to onTasksAppeared.
                        targets[i] = TransitionUtil.newTarget(change, layer, info, t, mLeashMap);
                        // reparent into the original `mInfo` since that's where we are animating.
                        final int rootIdx = TransitionUtil.rootIndexFor(change, mInfo);
                        t.reparent(targets[i].leash, mInfo.getRoot(rootIdx).getLeash());
                        t.setLayer(targets[i].leash, layer);
                        mOpeningTasks.add(new TaskState(change, targets[i].leash));
                    }
                }
                didMergeThings = true;
                mState = STATE_NEW_TASK;
                mAppearedTargets = targets;
            }
            if (!didMergeThings) {
                // Didn't recognize anything in incoming transition so don't merge it.
                Log.w(TAG, "Don't know how to merge this transition.");
                return false;
            }
            t.apply();
            // not using the incoming anim-only surfaces
            info.releaseAnimSurfaces();
            return true;
        }

        private void commitTasksAppearedIfNeeded() {
            if (mAppearedTargets != null) {
                mListener.onTasksAppeared(mAppearedTargets);
                mAppearedTargets = null;
            }
        }

        @Override public TaskSnapshot screenshotTask(int taskId) {
            try {
                return ActivityTaskManager.getService().takeTaskSnapshot(taskId,
                        true /* updateCache */);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to screenshot task", e);
            }
            return null;
        }

        @Override public void setInputConsumerEnabled(boolean enabled) {
            if (!enabled) return;
            // transient launches don't receive focus automatically. Since we are taking over
            // the gesture now, take focus explicitly.
            // This also moves recents back to top if the user gestured before a switch
            // animation finished.
            try {
                ActivityTaskManager.getService().setFocusedTask(mRecentsTaskId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set focused task", e);
            }
        }

        @Override public void setAnimationTargetsBehindSystemBars(boolean behindSystemBars) {
        }

        @Override public void setFinishTaskTransaction(int taskId,
                PictureInPictureSurfaceTransaction finishTransaction, SurfaceControl overlay) {
            mPipTransaction = finishTransaction;
        }

        @Override
        @SuppressLint("NewApi")
        public void finish(boolean toHome, boolean sendUserLeaveHint) {
            if (mFinishCB == null) {
                Log.e(TAG, "Duplicate call to finish", new RuntimeException());
                return;
            }
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            final WindowContainerTransaction wct = new WindowContainerTransaction();

            if (mKeyguardLocked && mRecentsTask != null) {
                if (toHome) wct.reorder(mRecentsTask, true /* toTop */);
                else wct.restoreTransientOrder(mRecentsTask);
            }
            if (!toHome && !mWillFinishToHome && mPausingTasks != null && mState == STATE_NORMAL) {
                // The gesture is returning to the pausing-task(s) rather than continuing with
                // recents, so end the transition by moving the app back to the top (and also
                // re-showing it's task).
                for (int i = mPausingTasks.size() - 1; i >= 0; --i) {
                    // reverse order so that index 0 ends up on top
                    wct.reorder(mPausingTasks.get(i).mToken, true /* onTop */);
                    t.show(mPausingTasks.get(i).mTaskSurface);
                }
                if (!mKeyguardLocked && mRecentsTask != null) {
                    wct.restoreTransientOrder(mRecentsTask);
                }
            } else if (toHome && mOpeningSeparateHome && mPausingTasks != null) {
                // Special situation where 3p launcher was changed during recents (this happens
                // during tapltests...). Here we get both "return to home" AND "home opening".
                // This is basically going home, but we have to restore the recents and home order.
                for (int i = 0; i < mOpeningTasks.size(); ++i) {
                    final TaskState state = mOpeningTasks.get(i);
                    if (state.mTaskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                        // Make sure it is on top.
                        wct.reorder(state.mToken, true /* onTop */);
                    }
                    t.show(state.mTaskSurface);
                }
                for (int i = mPausingTasks.size() - 1; i >= 0; --i) {
                    t.hide(mPausingTasks.get(i).mTaskSurface);
                }
                if (!mKeyguardLocked && mRecentsTask != null) {
                    wct.restoreTransientOrder(mRecentsTask);
                }
            } else {
                // The general case: committing to recents, going home, or switching tasks.
                for (int i = 0; i < mOpeningTasks.size(); ++i) {
                    t.show(mOpeningTasks.get(i).mTaskSurface);
                }
                for (int i = 0; i < mPausingTasks.size(); ++i) {
                    if (!sendUserLeaveHint) {
                        // This means recents is not *actually* finishing, so of course we gotta
                        // do special stuff in WMCore to accommodate.
                        wct.setDoNotPip(mPausingTasks.get(i).mToken);
                    }
                    // Since we will reparent out of the leashes, pre-emptively hide the child
                    // surface to match the leash. Otherwise, there will be a flicker before the
                    // visibility gets committed in Core when using split-screen (in splitscreen,
                    // the leaf-tasks are not "independent" so aren't hidden by normal setup).
                    t.hide(mPausingTasks.get(i).mTaskSurface);
                }
                if (mPipTask != null && mPipTransaction != null && sendUserLeaveHint) {
                    t.show(mInfo.getChange(mPipTask).getLeash());
                    PictureInPictureSurfaceTransaction.apply(mPipTransaction,
                            mInfo.getChange(mPipTask).getLeash(), t);
                    mPipTask = null;
                    mPipTransaction = null;
                }
            }
            try {
                mFinishCB.onTransitionFinished(wct.isEmpty() ? null : wct, t);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call animation finish callback", e);
                t.apply();
            }
            // Only release the non-local created surface references. The animator is responsible
            // for releasing the leashes created by local.
            mInfo.releaseAllSurfaces();
            // Reset all members.
            mListener = null;
            mFinishCB = null;
            mPausingTasks = null;
            mOpeningTasks = null;
            mAppearedTargets = null;
            mInfo = null;
            mOpeningSeparateHome = false;
            mLeashMap = null;
            mTransition = null;
            mState = STATE_NORMAL;
        }

        @Override public void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot) {
        }

        @Override public void cleanupScreenshot() {
        }

        @Override public void setWillFinishToHome(boolean willFinishToHome) {
            mWillFinishToHome = willFinishToHome;
        }

        /**
         * @see IRecentsAnimationController#removeTask
         */
        @Override public boolean removeTask(int taskId) {
            return false;
        }

        /**
         * @see IRecentsAnimationController#detachNavigationBarFromApp
         */
        @Override public void detachNavigationBarFromApp(boolean moveHomeToTop) {
            try {
                ActivityTaskManager.getService().detachNavigationBarFromApp(mTransition);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to detach the navigation bar from app", e);
            }
        }

        /**
         * @see IRecentsAnimationController#animateNavigationBarToApp(long)
         */
        @Override public void animateNavigationBarToApp(long duration) {
        }
    }

    /** Utility class to track the state of a task as-seen by recents. */
    private static class TaskState {
        WindowContainerToken mToken;
        ActivityManager.RunningTaskInfo mTaskInfo;

        /** The surface/leash of the task provided by Core. */
        SurfaceControl mTaskSurface;

        /** The (local) animation-leash created for this task. */
        SurfaceControl mLeash;

        TaskState(TransitionInfo.Change change, SurfaceControl leash) {
            mToken = change.getContainer();
            mTaskInfo = change.getTaskInfo();
            mTaskSurface = change.getLeash();
            mLeash = leash;
        }

        static int indexOf(ArrayList<TaskState> list, TransitionInfo.Change change) {
            for (int i = list.size() - 1; i >= 0; --i) {
                if (list.get(i).mToken.equals(change.getContainer())) {
                    return i;
                }
            }
            return -1;
        }

        public String toString() {
            return "" + mToken + " : " + mLeash;
        }
    }
}
