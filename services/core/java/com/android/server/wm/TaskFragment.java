/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.Task.ActivityState.PAUSED;
import static com.android.server.wm.Task.ActivityState.PAUSING;
import static com.android.server.wm.Task.ActivityState.RESUMED;
import static com.android.server.wm.Task.ActivityState.STOPPING;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ResultInfo;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A basic container that can be used to contain activities or other {@link TaskFragment}, which
 * also able to manage the activity lifecycle and updates the visibilities of the activities in it.
 */
class TaskFragment extends WindowContainer<WindowContainer> {
    @IntDef(prefix = {"TASK_FRAGMENT_VISIBILITY"}, value = {
            TASK_FRAGMENT_VISIBILITY_VISIBLE,
            TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
            TASK_FRAGMENT_VISIBILITY_INVISIBLE,
    })
    @interface TaskFragmentVisibility {}

    /**
     * TaskFragment is visible. No other TaskFragment(s) on top that fully or partially occlude it.
     */
    static final int TASK_FRAGMENT_VISIBILITY_VISIBLE = 0;

    /** TaskFragment is partially occluded by other translucent TaskFragment(s) on top of it. */
    static final int TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT = 1;

    /** TaskFragment is completely invisible. */
    static final int TASK_FRAGMENT_VISIBILITY_INVISIBLE = 2;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskFragment" : TAG_ATM;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;

    /** Set to false to disable the preview that is shown while a new activity is being started. */
    static final boolean SHOW_APP_STARTING_PREVIEW = true;

    final ActivityTaskManagerService mAtmService;
    final ActivityTaskSupervisor mTaskSupervisor;
    final RootWindowContainer mRootWindowContainer;

    // The TaskFragment that adjacent to this one.
    private TaskFragment mAdjacentTaskFragment;

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     *
     * Only set at leaf task fragments.
     */
    @Nullable
    private ActivityRecord mPausingActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    // TODO(b/189384393): check whether this should work on root task or leaf.
    ActivityRecord mLastPausedActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     * Only set at leaf task fragments.
     */
    @Nullable
    private ActivityRecord mResumedActivity = null;

    private final EnsureActivitiesVisibleHelper mEnsureActivitiesVisibleHelper =
            new EnsureActivitiesVisibleHelper(this);

    TaskFragment(ActivityTaskManagerService atmService) {
        super(atmService.mWindowManager);

        mAtmService = atmService;
        mTaskSupervisor = atmService.mTaskSupervisor;
        mRootWindowContainer = mAtmService.mRootWindowContainer;
    }

    void setAdjacentTaskFragment(TaskFragment taskFragment) {
        mAdjacentTaskFragment = taskFragment;
        taskFragment.mAdjacentTaskFragment = this;
    }

    TaskFragment getAdjacentTaskFragment() {
        return mAdjacentTaskFragment;
    }

    /** @return the currently resumed activity. */
    ActivityRecord getResumedActivity() {
        return mResumedActivity;
    }

    void setResumedActivity(ActivityRecord r, String reason) {
        warnForNonLeafTaskFragment("setResumedActivity");
        if (mResumedActivity == r) {
            return;
        }

        if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
            Slog.d(TAG, "setResumedActivity taskFrag:" + this + " + from: "
                    + mResumedActivity + " to:" + r + " reason:" + reason);
        }
        mResumedActivity = r;
        mTaskSupervisor.updateTopResumedActivityIfNeeded();
    }

    @VisibleForTesting
    void setPausingActivity(ActivityRecord pausing) {
        mPausingActivity = pausing;
    }

    ActivityRecord getPausingActivity() {
        return mPausingActivity;
    }

    int getDisplayId() {
        final DisplayContent dc = getDisplayContent();
        return dc != null ? dc.mDisplayId : INVALID_DISPLAY;
    }

    @Nullable
    Task getTask() {
        if (asTask() != null) {
            return asTask();
        }

        TaskFragment parent = getParent() != null ? getParent().asTaskFragment() : null;
        return parent != null ? parent.getTask() : null;
    }

    @Override
    @Nullable
    TaskDisplayArea getDisplayArea() {
        return (TaskDisplayArea) super.getDisplayArea();
    }

    /**
     * Returns the root {@link TaskFragment}, which is usually also a {@link Task}.
     */
    TaskFragment getRootTaskFragment() {
        final WindowContainer parent = getParent();
        if (parent == null) return this;

        final TaskFragment parentTaskFragment = parent.asTaskFragment();
        return parentTaskFragment == null ? this : parentTaskFragment.getRootTaskFragment();
    }

    @Override
    TaskFragment asTaskFragment() {
        return this;
    }


    /**
     * Simply check and give warning logs if this is not operated on leaf {@link TaskFragment}.
     */
    private void warnForNonLeafTaskFragment(String func) {
        if (!isLeafTaskFragment()) {
            Slog.w(TAG, func + " on non-leaf task fragment " + this);
        }
    }

    boolean hasDirectChildActivities() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asActivityRecord() != null) {
                return true;
            }
        }
        return false;
    }

    void cleanUpActivityReferences(ActivityRecord r) {
        if (mPausingActivity != null && mPausingActivity == r) {
            mPausingActivity = null;
        }

        if (mResumedActivity != null && mResumedActivity == r) {
            setResumedActivity(null, "cleanUpActivityReferences");
        }

        // TODO(b/189384393): why clean up the references in parents while the references are
        //  kept in the leaf?
        final WindowContainer parent = getParent();
        if (parent != null && parent.asTaskFragment() != null) {
            parent.asTaskFragment().cleanUpActivityReferences(r);
            return;
        }
        r.removeTimeouts();
    }

    /**
     * Returns whether this TaskFragment is currently forced to be hidden for any reason.
     */
    protected boolean isForceHidden() {
        return false;
    }

    boolean isLeafTaskFragment() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asTaskFragment() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * This should be called when an child activity changes state. This should only
     * be called from
     * {@link ActivityRecord#setState(Task.ActivityState, String)} .
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, Task.ActivityState state, String reason) {
        warnForNonLeafTaskFragment("onActivityStateChanged");
        if (record == mResumedActivity && state != RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }

        if (state == RESUMED) {
            if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
                Slog.v(TAG, "set resumed activity to:" + record + " reason:" + reason);
            }
            setResumedActivity(record, reason + " - onActivityStateChanged");
            if (record == mRootWindowContainer.getTopResumedActivity()) {
                mAtmService.setResumedActivityUncheckLocked(record, reason);
            }
            mTaskSupervisor.mRecentTasks.add(record.getTask());
        }
    }

    /**
     * Resets local parameters because an app's activity died.
     * @param app The app of the activity that died.
     * @return {@code true} if the process of the pausing activity is died.
     */
    boolean handleAppDied(WindowProcessController app) {
        warnForNonLeafTaskFragment("handleAppDied");
        boolean isPausingDied = false;
        if (mPausingActivity != null && mPausingActivity.app == app) {
            ProtoLog.v(WM_DEBUG_STATES, "App died while pausing: %s",
                    mPausingActivity);
            mPausingActivity = null;
            isPausingDied = true;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            if (mLastPausedActivity.isNoHistory()) {
                mTaskSupervisor.mNoHistoryActivities.remove(mLastPausedActivity);
            }
            mLastPausedActivity = null;
        }
        return isPausingDied;
    }

    void awakeFromSleeping() {
        if (mPausingActivity != null) {
            Slog.d(TAG, "awakeFromSleeping: previously pausing activity didn't pause");
            mPausingActivity.activityPaused(true);
        }
    }

    /**
     * Tries to put the activities in the task fragment to sleep.
     *
     * If the task fragment is not in a state where its activities can be put to sleep, this
     * function will start any necessary actions to move the task fragment into such a state.
     * It is expected that this function get called again when those actions complete.
     *
     * @param shuttingDown {@code true} when the called because the device is shutting down.
     * @return true if the root task finished going to sleep, false if the root task only started
     * the process of going to sleep (checkReadyForSleep will be called when that process finishes).
     */
    boolean sleepIfPossible(boolean shuttingDown) {
        boolean shouldSleep = true;
        if (mResumedActivity != null) {
            // Still have something resumed; can't sleep until it is paused.
            ProtoLog.v(WM_DEBUG_STATES, "Sleep needs to pause %s", mResumedActivity);
            startPausing(false /* userLeaving */, true /* uiSleeping */, null /* resuming */,
                    "sleep");
            shouldSleep = false;
        } else if (mPausingActivity != null) {
            // Still waiting for something to pause; can't sleep yet.
            ProtoLog.v(WM_DEBUG_STATES, "Sleep still waiting to pause %s", mPausingActivity);
            shouldSleep = false;
        }

        if (!shuttingDown) {
            if (containsStoppingActivity()) {
                // Still need to tell some activities to stop; can't sleep yet.
                ProtoLog.v(WM_DEBUG_STATES, "Sleep still need to stop %d activities",
                        mTaskSupervisor.mStoppingActivities.size());

                mTaskSupervisor.scheduleIdle();
                shouldSleep = false;
            }
        }

        if (shouldSleep) {
            updateActivityVisibilities(null /* starting */, 0 /* configChanges */,
                    !PRESERVE_WINDOWS, true /* notifyClients */);
        }

        return shouldSleep;
    }

    private boolean containsStoppingActivity() {
        for (int i = mTaskSupervisor.mStoppingActivities.size() - 1; i >= 0; --i) {
            ActivityRecord r = mTaskSupervisor.mStoppingActivities.get(i);
            if (r.getTaskFragment() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the TaskFragment is translucent and can have other contents visible behind
     * it if needed. A TaskFragment is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     */
    @VisibleForTesting
    boolean isTranslucent(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return true;
        }
        final PooledPredicate p = PooledLambda.obtainPredicate(TaskFragment::isOpaqueActivity,
                PooledLambda.__(ActivityRecord.class), starting);
        final ActivityRecord opaque = getActivity(p);
        p.recycle();
        return opaque == null;
    }

    private static boolean isOpaqueActivity(ActivityRecord r, ActivityRecord starting) {
        if (r.finishing) {
            // We don't factor in finishing activities when determining translucency since
            // they will be gone soon.
            return false;
        }

        if (!r.visibleIgnoringKeyguard && r != starting) {
            // Also ignore invisible activities that are not the currently starting
            // activity (about to be visible).
            return false;
        }

        if (r.occludesParent()) {
            // Root task isn't translucent if it has at least one fullscreen activity
            // that is visible.
            return true;
        }
        return false;
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* focusableOnly */);
    }

    ActivityRecord topRunningActivity(boolean focusableOnly) {
        // Split into 2 to avoid object creation due to variable capture.
        if (focusableOnly) {
            return getActivity((r) -> r.canBeTopRunning() && r.isFocusable());
        } else {
            return getActivity(ActivityRecord::canBeTopRunning);
        }
    }

    boolean isTopActivityFocusable() {
        final ActivityRecord r = topRunningActivity();
        return r != null ? r.isFocusable()
                : (isFocusable() && getWindowConfiguration().canReceiveKeys());
    }

    /**
     * Returns the visibility state of this TaskFragment.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    @TaskFragmentVisibility
    int getVisibility(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
        }

        if (isTopActivityLaunchedBehind()) {
            return TASK_FRAGMENT_VISIBILITY_VISIBLE;
        }

        boolean gotRootSplitScreenFragment = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentSplitScreenPrimary = false;
        boolean gotTranslucentSplitScreenSecondary = false;
        boolean shouldBeVisible = true;

        // This TaskFragment is only considered visible if all its parent TaskFragments are
        // considered visible, so check the visibility of all ancestor TaskFragment first.
        final WindowContainer parent = getParent();
        if (parent.asTaskFragment() != null) {
            final int parentVisibility = parent.asTaskFragment().getVisibility(starting);
            if (parentVisibility == TASK_FRAGMENT_VISIBILITY_INVISIBLE) {
                // Can't be visible if parent isn't visible
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (parentVisibility == TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT) {
                // Parent is behind a translucent container so the highest visibility this container
                // can get is that.
                gotTranslucentFullscreen = true;
            }
        }

        final List<TaskFragment> adjacentTaskFragments = new ArrayList<>();
        final int windowingMode = getWindowingMode();
        final boolean isAssistantType = isActivityTypeAssistant();
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer wc = parent.getChildAt(i);
            final TaskFragment other = wc.asTaskFragment();
            if (other == null) continue;

            final boolean hasRunningActivities = other.topRunningActivity() != null;
            if (other == this) {
                // Should be visible if there is no other fragment occluding it, unless it doesn't
                // have any running activities, not starting one and not home stack.
                shouldBeVisible = hasRunningActivities
                        || (starting != null && starting.isDescendantOf(this))
                        || isActivityTypeHome();
                break;
            }

            if (!hasRunningActivities) {
                continue;
            }

            final int otherWindowingMode = other.getWindowingMode();
            if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                if (other.isTranslucent(starting)) {
                    // Can be visible behind a translucent fullscreen TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_MULTI_WINDOW
                    && other.matchParentBounds()) {
                if (other.isTranslucent(starting)) {
                    // Can be visible behind a translucent TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                // Multi-window TaskFragment that matches parent bounds would occlude other children
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && !gotOpaqueSplitScreenPrimary) {
                gotRootSplitScreenFragment = true;
                gotTranslucentSplitScreenPrimary = other.isTranslucent(starting);
                gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && gotOpaqueSplitScreenPrimary) {
                    // Can't be visible behind another opaque TaskFragment in split-screen-primary.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && !gotOpaqueSplitScreenSecondary) {
                gotRootSplitScreenFragment = true;
                gotTranslucentSplitScreenSecondary = other.isTranslucent(starting);
                gotOpaqueSplitScreenSecondary = !gotTranslucentSplitScreenSecondary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        && gotOpaqueSplitScreenSecondary) {
                    // Can't be visible behind another opaque TaskFragment in split-screen-secondary
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
            }
            if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                // Can not be visible if we are in split-screen windowing mode and both halves of
                // the screen are opaque.
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            }
            if (isAssistantType && gotRootSplitScreenFragment) {
                // Assistant TaskFragment can't be visible behind split-screen. In addition to
                // this not making sense, it also works around an issue here we boost the z-order
                // of the assistant window surfaces in window manager whenever it is visible.
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            }

            if (other.mAdjacentTaskFragment != null) {
                if (adjacentTaskFragments.contains(other.mAdjacentTaskFragment)) {
                    if (other.isTranslucent(starting)
                            || other.mAdjacentTaskFragment.isTranslucent(starting)) {
                        // Can be visible behind a translucent adjacent TaskFragments.
                        gotTranslucentFullscreen = true;
                        continue;
                    }
                    // Can not be visible behind adjacent TaskFragments.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                } else {
                    adjacentTaskFragments.add(other);
                }
            }

        }

        if (!shouldBeVisible) {
            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
        }

        // Handle cases when there can be a translucent split-screen TaskFragment on top.
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                if (gotTranslucentSplitScreenPrimary || gotTranslucentSplitScreenSecondary) {
                    // At least one of the split-screen TaskFragment that covers this one is
                    // translucent.
                    // When in split mode, home will be reparented to the secondary split while
                    // leaving TaskFragments not supporting split below. Due to
                    // TaskDisplayArea#assignRootTaskOrdering always adjusts home surface layer to
                    // the bottom, this makes sure TaskFragments not in split roots won't occlude
                    // home task unexpectedly.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                if (gotTranslucentSplitScreenPrimary) {
                    // Covered by translucent primary split-screen on top.
                    return TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                if (gotTranslucentSplitScreenSecondary) {
                    // Covered by translucent secondary split-screen on top.
                    return TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
        }

        // Lastly - check if there is a translucent fullscreen TaskFragment on top.
        return gotTranslucentFullscreen
                ? TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT
                : TASK_FRAGMENT_VISIBILITY_VISIBLE;
    }

    private boolean isTopActivityLaunchedBehind() {
        final ActivityRecord top = topRunningActivity();
        if (top != null && top.mLaunchTaskBehind) {
            return true;
        }
        return false;
    }

    final void updateActivityVisibilities(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mTaskSupervisor.beginActivityVisibilityUpdate();
        try {
            mEnsureActivitiesVisibleHelper.process(
                    starting, configChanges, preserveWindows, notifyClients);
        } finally {
            mTaskSupervisor.endActivityVisibilityUpdate();
        }
    }

    final boolean resumeTopActivity(ActivityRecord prev, ActivityOptions options,
            boolean deferPause) {
        ActivityRecord next = topRunningActivity(true /* focusableOnly */);
        if (next == null || !next.canResumeByCompat()) {
            return false;
        }

        next.delayedResume = false;
        final TaskDisplayArea taskDisplayArea = getDisplayArea();

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            // For devices that are not in fullscreen mode (e.g. freeform windows), it's possible
            // we still want to check if the visibility of other windows have changed (e.g. bringing
            // a fullscreen window forward to cover another freeform activity.)
            if (taskDisplayArea.inMultiWindowMode()) {
                taskDisplayArea.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                        false /* preserveWindows */, true /* notifyClients */);
            }
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Top activity "
                    + "resumed %s", next);
            return false;
        }

        // If we are currently pausing an activity, then don't do anything until that is done.
        final boolean allPausedComplete = mRootWindowContainer.allPausedActivitiesComplete();
        if (!allPausedComplete) {
            ProtoLog.v(WM_DEBUG_STATES,
                    "resumeTopActivity: Skip resume: some activity pausing.");
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top activity is paused,
        // well that is the state we want.
        if (mLastPausedActivity == next && shouldSleepOrShutDownActivities()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Going to sleep and"
                    + " all paused");
            return false;
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (!mAtmService.mAmInternal.hasStartedUserState(next.mUserId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.mUserId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mTaskSupervisor.mStoppingActivities.remove(next);

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        mTaskSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        ActivityRecord lastResumed = null;
        final Task lastFocusedRootTask = taskDisplayArea.getLastFocusedRootTask();
        if (lastFocusedRootTask != null && lastFocusedRootTask != getRootTaskFragment().asTask()) {
            // So, why aren't we using prev here??? See the param comment on the method. prev
            // doesn't represent the last resumed activity. However, the last focus stack does if
            // it isn't null.
            lastResumed = lastFocusedRootTask.getTopResumedActivity();
        }

        boolean pausing = !deferPause && taskDisplayArea.pauseBackTasks(next);
        if (mResumedActivity != null) {
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Pausing %s", mResumedActivity);
            pausing |= startPausingInner(mTaskSupervisor.mUserLeaving, false /* uiSleeping */,
                    next, "resumeTopActivity");
        }
        if (pausing) {
            ProtoLog.v(WM_DEBUG_STATES, "resumeTopActivity: Skip resume: need to"
                    + " start pausing");
            // At this point we want to put the upcoming activity's process
            // at the top of the LRU list, since we know we will be needing it
            // very soon and it would be a waste to let it get killed if it
            // happens to be sitting towards the end.
            if (next.attachedToProcess()) {
                next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                        true /* activityChange */, false /* updateOomAdj */,
                        false /* addPendingTopUid */);
            } else if (!next.isProcessRunning()) {
                // Since the start-process is asynchronous, if we already know the process of next
                // activity isn't running, we can start the process earlier to save the time to wait
                // for the current activity to be paused.
                final boolean isTop = this == taskDisplayArea.getFocusedRootTask();
                mAtmService.startProcessAsync(next, false /* knownToBeDead */, isTop,
                        isTop ? "pre-top-activity" : "pre-activity");
            }
            if (lastResumed != null) {
                lastResumed.setWillCloseOrEnterPip(true);
            }
            return true;
        } else if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // It is possible for the activity to be resumed when we paused back stacks above if the
            // next activity doesn't have to wait for pause to complete.
            // So, nothing else to-do except:
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Top activity resumed "
                    + "(dontWaitForPause) %s", next);
            return true;
        }

        // If the most recent activity was noHistory but was only stopped rather
        // than stopped+finished because the device went to sleep, we need to make
        // sure to finish it as we're making a new activity topmost.
        if (shouldSleepActivities()) {
            mTaskSupervisor.finishNoHistoryActivitiesIfNeeded(next);
        }

        if (prev != null && prev != next && next.nowVisible) {
            // The next activity is already visible, so hide the previous
            // activity's windows right now so we can show the new one ASAP.
            // We only do this if the previous is finishing, which should mean
            // it is on top of the one being resumed so hiding it quickly
            // is good.  Otherwise, we want to do the normal route of allowing
            // the resumed activity to be shown so we can decide if the
            // previous should actually be hidden depending on whether the
            // new one is found to be full-screen or not.
            if (prev.finishing) {
                prev.setVisibility(false);
                if (DEBUG_SWITCH) {
                    Slog.v(TAG_SWITCH, "Not waiting for visible to hide: " + prev
                            + ", nowVisible=" + next.nowVisible);
                }
            } else {
                if (DEBUG_SWITCH) {
                    Slog.v(TAG_SWITCH, "Previous already visible but still waiting to hide: " + prev
                            + ", nowVisible=" + next.nowVisible);
                }
            }
        }

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        try {
            mTaskSupervisor.getActivityMetricsLogger()
                    .notifyBeforePackageUnstopped(next.packageName);
            mAtmService.getPackageManager().setPackageStoppedState(
                    next.packageName, false, next.mUserId); /* TODO: Verify if correct userid */
        } catch (RemoteException e1) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        boolean anim = true;
        final DisplayContent dc = taskDisplayArea.mDisplayContent;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) {
                    Slog.v(TAG_TRANSITION, "Prepare close transition: prev=" + prev);
                }
                if (mTaskSupervisor.mNoAnimActivities.contains(prev)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE);
                } else {
                    dc.prepareAppTransition(TRANSIT_CLOSE);
                }
                prev.setVisibility(false);
            } else {
                if (DEBUG_TRANSITION) {
                    Slog.v(TAG_TRANSITION, "Prepare open transition: prev=" + prev);
                }
                if (mTaskSupervisor.mNoAnimActivities.contains(next)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE);
                } else {
                    dc.prepareAppTransition(TRANSIT_OPEN,
                            next.mLaunchTaskBehind ? TRANSIT_FLAG_OPEN_BEHIND : 0);
                }
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
            if (mTaskSupervisor.mNoAnimActivities.contains(next)) {
                anim = false;
                dc.prepareAppTransition(TRANSIT_NONE);
            } else {
                dc.prepareAppTransition(TRANSIT_OPEN);
            }
        }

        if (anim) {
            next.applyOptionsAnimation();
        } else {
            next.abortAndClearOptionsAnimation();
        }

        mTaskSupervisor.mNoAnimActivities.clear();

        if (next.attachedToProcess()) {
            if (DEBUG_SWITCH) {
                Slog.v(TAG_SWITCH, "Resume running: " + next + " stopped=" + next.stopped
                        + " visibleRequested=" + next.mVisibleRequested);
            }

            // If the previous activity is translucent, force a visibility update of
            // the next activity, so that it's added to WM's opening app list, and
            // transition animation can be set up properly.
            // For example, pressing Home button with a translucent activity in focus.
            // Launcher is already visible in this case. If we don't add it to opening
            // apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
            // TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
            final boolean lastActivityTranslucent = lastFocusedRootTask != null
                    && (lastFocusedRootTask.inMultiWindowMode()
                    || (lastFocusedRootTask.mLastPausedActivity != null
                    && !lastFocusedRootTask.mLastPausedActivity.occludesParent()));

            // This activity is now becoming visible.
            if (!next.mVisibleRequested || next.stopped || lastActivityTranslucent) {
                next.setVisibility(true);
            }

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity =
                    lastFocusedRootTask == null ? null
                            : lastFocusedRootTask.getTopResumedActivity();
            final Task.ActivityState lastState = next.getState();

            mAtmService.updateCpuStats();

            ProtoLog.v(WM_DEBUG_STATES, "Moving to RESUMED: %s (in existing)", next);

            next.setState(RESUMED, "resumeTopActivity");

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            boolean notUpdated = true;

            // Activity should also be visible if set mLaunchTaskBehind to true (see
            // ActivityRecord#shouldBeVisibleIgnoringKeyguard()).
            if (shouldBeVisible(next)) {
                // We have special rotation behavior when here is some active activity that
                // requests specific orientation or Keyguard is locked. Make sure all activity
                // visibilities are set correctly as well as the transition is updated if needed
                // to get the correct rotation behavior. Otherwise the following call to update
                // the orientation may cause incorrect configurations delivered to client as a
                // result of invisible window resize.
                // TODO: Remove this once visibilities are set correctly immediately when
                // starting an activity.
                notUpdated = !mRootWindowContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                        true /* markFrozenIfConfigChanged */, false /* deferResume */);
            }

            if (notUpdated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                ActivityRecord nextNext = topRunningActivity();
                ProtoLog.i(WM_DEBUG_STATES, "Activity config changed during resume: "
                        + "%s, new next: %s", next, nextNext);
                if (nextNext != next) {
                    // Do over!
                    mTaskSupervisor.scheduleResumeTopActivities();
                }
                if (!next.mVisibleRequested || next.stopped) {
                    next.setVisibility(true);
                }
                next.completeResumeLocked();
                return true;
            }

            try {
                final ClientTransaction transaction =
                        ClientTransaction.obtain(next.app.getThread(), next.appToken);
                // Deliver all pending results.
                ArrayList<ResultInfo> a = next.results;
                if (a != null) {
                    final int size = a.size();
                    if (!next.finishing && size > 0) {
                        if (DEBUG_RESULTS) {
                            Slog.v(TAG_RESULTS, "Delivering results to " + next + ": " + a);
                        }
                        transaction.addCallback(ActivityResultItem.obtain(a));
                    }
                }

                if (next.newIntents != null) {
                    transaction.addCallback(
                            NewIntentItem.obtain(next.newIntents, true /* resume */));
                }

                // Well the app will no longer be stopped.
                // Clear app token stopped state in window manager if needed.
                next.notifyAppResumed(next.stopped);

                EventLogTags.writeWmResumeActivity(next.mUserId, System.identityHashCode(next),
                        next.getTask().mTaskId, next.shortComponentName);

                mAtmService.getAppWarningsLocked().onResumeActivity(next);
                next.app.setPendingUiCleanAndForceProcessStateUpTo(mAtmService.mTopProcessState);
                next.abortAndClearOptionsAnimation();
                transaction.setLifecycleStateRequest(
                        ResumeActivityItem.obtain(next.app.getReportedProcState(),
                                dc.isNextTransitionForward()));
                mAtmService.getLifecycleManager().scheduleTransaction(transaction);

                ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Resumed %s", next);
            } catch (Exception e) {
                // Whoops, need to restart this activity!
                ProtoLog.v(WM_DEBUG_STATES, "Resume failed; resetting state to %s: "
                        + "%s", lastState, next);
                next.setState(lastState, "resumeTopActivityInnerLocked");

                // lastResumedActivity being non-null implies there is a lastStack present.
                if (lastResumedActivity != null) {
                    lastResumedActivity.setState(RESUMED, "resumeTopActivityInnerLocked");
                }

                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else if (SHOW_APP_STARTING_PREVIEW && lastFocusedRootTask != null
                        && lastFocusedRootTask.isTopRootTaskInDisplayArea()) {
                    next.showStartingWindow(false /* taskSwitch */);
                }
                mTaskSupervisor.startSpecificActivity(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.completeResumeLocked();
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Slog.w(TAG, "Exception thrown during resume of " + next, e);
                next.finishIfPossible("resume-exception", true /* oomAdj */);
                return true;
            }
        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_PREVIEW) {
                    next.showStartingWindow(false /* taskSwich */);
                }
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
            }
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Restarting %s", next);
            mTaskSupervisor.startSpecificActivity(next, true, true);
        }

        return true;
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mAtmService.mShuttingDown;
    }

    /**
     * Returns true if the TaskFragment should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != TASK_FRAGMENT_VISIBILITY_INVISIBLE;
    }

    final boolean startPausing(boolean uiSleeping, ActivityRecord resuming, String reason) {
        return startPausing(mTaskSupervisor.mUserLeaving, uiSleeping, resuming, reason);
    }

    final boolean startPausing(boolean userLeaving, boolean uiSleeping,
            ActivityRecord resuming, String reason) {
        final int[] pausing = {0};
        forAllLeafTaskFragments((f) -> {
            if (f.startPausingInner(userLeaving, uiSleeping, resuming, reason)) {
                pausing[0]++;
            }
        }, true /* traverseTopToBottom */);
        return pausing[0] > 0;
    }

    /**
     * Start pausing the currently resumed activity.  It is an error to call this if there
     * is already an activity being paused or there is no resumed activity.
     *
     * @param userLeaving True if this should result in an onUserLeaving to the current activity.
     * @param uiSleeping True if this is happening with the user interface going to sleep (the
     * screen turning off).
     * @param resuming The activity we are currently trying to resume or null if this is not being
     *                 called as part of resuming the top activity, so we shouldn't try to instigate
     *                 a resume here if not null.
     * @param reason The reason of pausing the activity.
     * @return Returns true if an activity now is in the PAUSING state, and we are waiting for
     * it to tell us when it is done.
     */
    private boolean startPausingInner(boolean userLeaving, boolean uiSleeping,
            ActivityRecord resuming, String reason) {
        if (!hasDirectChildActivities()) {
            return false;
        }

        if (mPausingActivity != null) {
            Slog.wtf(TAG, "Going to pause when pause is already pending for " + mPausingActivity
                    + " state=" + mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                // Avoid recursion among check for sleep and complete pause during sleeping.
                // Because activity will be paused immediately after resume, just let pause
                // be completed by the order of activity paused from clients.
                completePause(false, resuming);
            }
        }
        ActivityRecord prev = mResumedActivity;

        if (prev == null) {
            if (resuming == null) {
                Slog.wtf(TAG, "Trying to pause when nothing is resumed");
                mRootWindowContainer.resumeFocusedTasksTopActivities();
            }
            return false;
        }

        if (prev == resuming) {
            Slog.wtf(TAG, "Trying to pause activity that is in process of being resumed");
            return false;
        }

        ProtoLog.v(WM_DEBUG_STATES, "Moving to PAUSING: %s", prev);
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        if (prev.isNoHistory() && !mTaskSupervisor.mNoHistoryActivities.contains(prev)) {
            mTaskSupervisor.mNoHistoryActivities.add(prev);
        }
        prev.setState(PAUSING, "startPausingLocked");
        prev.getTask().touchActiveTime();

        mAtmService.updateCpuStats();

        boolean pauseImmediately = false;
        boolean shouldAutoPip = false;
        if (resuming != null && (resuming.info.flags & FLAG_RESUME_WHILE_PAUSING) != 0) {
            // If the flag RESUME_WHILE_PAUSING is set, then continue to schedule the previous
            // activity to be paused, while at the same time resuming the new resume activity
            // only if the previous activity can't go into Pip since we want to give Pip
            // activities a chance to enter Pip before resuming the next activity.
            final boolean lastResumedCanPip = prev != null && prev.checkEnterPictureInPictureState(
                    "shouldResumeWhilePausing", userLeaving);
            if (lastResumedCanPip && prev.pictureInPictureArgs.isAutoEnterEnabled()) {
                shouldAutoPip = true;
            } else if (!lastResumedCanPip) {
                pauseImmediately = true;
            } else {
                // The previous activity may still enter PIP even though it did not allow auto-PIP.
            }
        }

        boolean didAutoPip = false;
        if (prev.attachedToProcess()) {
            if (shouldAutoPip) {
                ProtoLog.d(WM_DEBUG_STATES, "Auto-PIP allowed, entering PIP mode "
                        + "directly: %s", prev);

                didAutoPip = mAtmService.enterPictureInPictureMode(prev, prev.pictureInPictureArgs);
                mPausingActivity = null;
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "Enqueueing pending pause: %s", prev);
                try {
                    EventLogTags.writeWmPauseActivity(prev.mUserId, System.identityHashCode(prev),
                            prev.shortComponentName, "userLeaving=" + userLeaving, reason);

                    mAtmService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
                            prev.appToken, PauseActivityItem.obtain(prev.finishing, userLeaving,
                                    prev.configChangeFlags, pauseImmediately));
                } catch (Exception e) {
                    // Ignore exception, if process died other code will cleanup.
                    Slog.w(TAG, "Exception thrown during pause", e);
                    mPausingActivity = null;
                    mLastPausedActivity = null;
                    mTaskSupervisor.mNoHistoryActivities.remove(prev);
                }
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
            mTaskSupervisor.mNoHistoryActivities.remove(prev);
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!uiSleeping && !mAtmService.isSleepingOrShuttingDownLocked()) {
            mTaskSupervisor.acquireLaunchWakelock();
        }

        // If already entered PIP mode, no need to keep pausing.
        if (mPausingActivity != null && !didAutoPip) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "Key dispatch not paused for screen off");
            }

            if (pauseImmediately) {
                // If the caller said they don't want to wait for the pause, then complete
                // the pause now.
                completePause(false, resuming);
                return false;

            } else {
                prev.schedulePauseTimeout();
                return true;
            }

        } else {
            // This activity either failed to schedule the pause or it entered PIP mode,
            // so just treat it as being paused now.
            ProtoLog.v(WM_DEBUG_STATES, "Activity not running or entered PiP, resuming next.");
            if (resuming == null) {
                mRootWindowContainer.resumeFocusedTasksTopActivities();
            }
            return false;
        }
    }

    @VisibleForTesting
    void completePause(boolean resumeNext, ActivityRecord resuming) {
        // Complete the pausing process of a pausing activity, so it doesn't make sense to
        // operate on non-leaf tasks.
        // warnForNonLeafTask("completePauseLocked");

        ActivityRecord prev = mPausingActivity;
        ProtoLog.v(WM_DEBUG_STATES, "Complete pause: %s", prev);

        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            final boolean wasStopping = prev.isState(STOPPING);
            prev.setState(PAUSED, "completePausedLocked");
            if (prev.finishing) {
                // We will update the activity visibility later, no need to do in
                // completeFinishing(). Updating visibility here might also making the next
                // activities to be resumed, and could result in wrong app transition due to
                // lack of previous activity information.
                ProtoLog.v(WM_DEBUG_STATES, "Executing finish of activity: %s", prev);
                prev = prev.completeFinishing(false /* updateVisibility */,
                        "completePausedLocked");
            } else if (prev.hasProcess()) {
                ProtoLog.v(WM_DEBUG_STATES, "Enqueue pending stop if needed: %s "
                                + "wasStopping=%b visibleRequested=%b",  prev,  wasStopping,
                        prev.mVisibleRequested);
                if (prev.deferRelaunchUntilPaused) {
                    // Complete the deferred relaunch that was waiting for pause to complete.
                    ProtoLog.v(WM_DEBUG_STATES, "Re-launching after pause: %s", prev);
                    prev.relaunchActivityLocked(prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    // We are also stopping, the stop request must have gone soon after the pause.
                    // We can't clobber it, because the stop confirmation will not be handled.
                    // We don't need to schedule another stop, we only need to let it happen.
                    prev.setState(STOPPING, "completePausedLocked");
                } else if (!prev.mVisibleRequested || shouldSleepOrShutDownActivities()) {
                    // Clear out any deferred client hide we might currently have.
                    prev.setDeferHidingClient(false);
                    // If we were visible then resumeTopActivities will release resources before
                    // stopping.
                    prev.addToStopping(true /* scheduleIdle */, false /* idleDelayed */,
                            "completePauseLocked");
                }
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "App died during pause, not stopping: %s", prev);
                prev = null;
            }
            // It is possible the activity was freezing the screen before it was paused.
            // In that case go ahead and remove the freeze this activity has on the screen
            // since it is no longer visible.
            if (prev != null) {
                prev.stopFreezingScreenLocked(true /*force*/);
            }
            mPausingActivity = null;
        }

        if (resumeNext) {
            final Task topRootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
            if (topRootTask != null && !topRootTask.shouldSleepOrShutDownActivities()) {
                mRootWindowContainer.resumeFocusedTasksTopActivities(topRootTask, prev, null);
            } else {
                // checkReadyForSleep();
                final ActivityRecord top =
                        topRootTask != null ? topRootTask.topRunningActivity() : null;
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the root task is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mRootWindowContainer.resumeFocusedTasksTopActivities();
                }
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
        }

        mRootWindowContainer.ensureActivitiesVisible(resuming, 0, !PRESERVE_WINDOWS);

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active root pinned task - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mTaskSupervisor.mAppVisibilitiesChangedSinceLastPause
                || (getDisplayArea() != null && getDisplayArea().hasPinnedTask())) {
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
            mTaskSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }
    }

    @Override
    void forAllLeafTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        boolean isLeafTaskFrag = true;
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                final TaskFragment child = mChildren.get(i).asTaskFragment();
                if (child != null) {
                    isLeafTaskFrag = false;
                    child.forAllLeafTaskFragments(callback, traverseTopToBottom);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final TaskFragment child = mChildren.get(i).asTaskFragment();
                if (child != null) {
                    isLeafTaskFrag = false;
                    child.forAllLeafTaskFragments(callback, traverseTopToBottom);
                }
            }
        }
        if (isLeafTaskFrag) callback.accept(this);
    }

    @Override
    boolean forAllLeafTaskFragments(Function<TaskFragment, Boolean> callback) {
        boolean isLeafTaskFrag = true;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final TaskFragment child = mChildren.get(i).asTaskFragment();
            if (child != null) {
                isLeafTaskFrag = false;
                if (child.forAllLeafTaskFragments(callback)) {
                    return true;
                }
            }
        }
        if (isLeafTaskFrag) {
            return callback.apply(this);
        }
        return false;
    }

    void executeAppTransition(ActivityOptions options) {
        // No app transition applied to the task fragment.
    }

    boolean shouldSleepActivities() {
        return false;
    }
}
