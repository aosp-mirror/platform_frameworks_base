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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.os.UserHandle.USER_NULL;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskSupervisor.printThisActivity;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.TaskFragmentProto.ACTIVITY_TYPE;
import static com.android.server.wm.TaskFragmentProto.DISPLAY_ID;
import static com.android.server.wm.TaskFragmentProto.MIN_HEIGHT;
import static com.android.server.wm.TaskFragmentProto.MIN_WIDTH;
import static com.android.server.wm.TaskFragmentProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainerChildProto.TASK_FRAGMENT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ResultInfo;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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

    /** Set to false to disable the preview that is shown while a new activity is being started. */
    static final boolean SHOW_APP_STARTING_PREVIEW = true;

    /**
     * Indicate that the minimal width/height should use the default value.
     *
     * @see #mMinWidth
     * @see #mMinHeight
     */
    static final int INVALID_MIN_SIZE = -1;

    final ActivityTaskManagerService mAtmService;
    final ActivityTaskSupervisor mTaskSupervisor;
    final RootWindowContainer mRootWindowContainer;

    /**
     * Minimal width of this task fragment when it's resizeable. {@link #INVALID_MIN_SIZE} means it
     * should use the default minimal width.
     */
    int mMinWidth;

    /**
     * Minimal height of this task fragment when it's resizeable. {@link #INVALID_MIN_SIZE} means it
     * should use the default minimal height.
     */
    int mMinHeight;

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
    ActivityRecord mLastPausedActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     * Only set at leaf task fragments.
     */
    @Nullable
    private ActivityRecord mResumedActivity = null;

    /**
     * This TaskFragment was created by an organizer which has the following implementations.
     * <ul>
     *     <li>The TaskFragment won't be removed when it is empty. Removal has to be an explicit
     *     request from the organizer.</li>
     *     <li>If this fragment is a Task object then unlike other non-root tasks, it's direct
     *     children are visible to the organizer for ordering purposes.</li>
     *     <li>A TaskFragment can be created by {@link android.window.TaskFragmentOrganizer}, and
     *     a Task can be created by {@link android.window.TaskOrganizer}.</li>
     * </ul>
     */
    @VisibleForTesting
    boolean mCreatedByOrganizer;

    /** Organizer that organizing this TaskFragment. */
    // TODO(b/190433129) set the value when creating TaskFragment from WCT.
    @Nullable
    private ITaskFragmentOrganizer mTaskFragmentOrganizer;

    /** Client assigned unique token for this TaskFragment if this is created by an organizer. */
    // TODO(b/190433129) set the value when creating TaskFragment from WCT.
    @Nullable
    private IBinder mFragmentToken;

    /**
     * The component name of the root activity that initiated this TaskFragment, which will be used
     * to configure the relationships for TaskFragments.
     */
    // TODO(b/190433129) set the value when creating TaskFragment from WCT.
    @Nullable
    private ComponentName mInitialComponentName;

    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpBounds = new Rect();
    private final Rect mTmpFullBounds = new Rect();
    private final Rect mTmpStableBounds = new Rect();
    private final Rect mTmpNonDecorBounds = new Rect();

    private final EnsureActivitiesVisibleHelper mEnsureActivitiesVisibleHelper =
            new EnsureActivitiesVisibleHelper(this);
    private final EnsureVisibleActivitiesConfigHelper mEnsureVisibleActivitiesConfigHelper =
            new EnsureVisibleActivitiesConfigHelper();
    private class EnsureVisibleActivitiesConfigHelper {
        private boolean mUpdateConfig;
        private boolean mPreserveWindow;
        private boolean mBehindFullscreen;

        void reset(boolean preserveWindow) {
            mPreserveWindow = preserveWindow;
            mUpdateConfig = false;
            mBehindFullscreen = false;
        }

        void process(ActivityRecord start, boolean preserveWindow) {
            if (start == null || !start.mVisibleRequested) {
                return;
            }
            reset(preserveWindow);

            final PooledFunction f = PooledLambda.obtainFunction(
                    EnsureVisibleActivitiesConfigHelper::processActivity, this,
                    PooledLambda.__(ActivityRecord.class));
            forAllActivities(f, start, true /*includeBoundary*/, true /*traverseTopToBottom*/);
            f.recycle();

            if (mUpdateConfig) {
                // Ensure the resumed state of the focus activity if we updated the configuration of
                // any activity.
                mRootWindowContainer.resumeFocusedTasksTopActivities();
            }
        }

        boolean processActivity(ActivityRecord r) {
            mUpdateConfig |= r.ensureActivityConfiguration(0 /*globalChanges*/, mPreserveWindow);
            mBehindFullscreen |= r.occludesParent();
            return mBehindFullscreen;
        }
    }

    TaskFragment(ActivityTaskManagerService atmService, boolean createdByOrganizer) {
        super(atmService.mWindowManager);

        mAtmService = atmService;
        mTaskSupervisor = atmService.mTaskSupervisor;
        mRootWindowContainer = mAtmService.mRootWindowContainer;
        mCreatedByOrganizer = createdByOrganizer;
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

    @Override
    public boolean isAttached() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        return taskDisplayArea != null && !taskDisplayArea.isRemoved();
    }

    /**
     * Returns the root {@link TaskFragment}, which is usually also a {@link Task}.
     */
    @NonNull
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

    void cleanUpActivityReferences(@NonNull ActivityRecord r) {
        if (mPausingActivity != null && mPausingActivity == r) {
            mPausingActivity = null;
        }

        if (mResumedActivity != null && mResumedActivity == r) {
            setResumedActivity(null, "cleanUpActivityReferences");
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
     * {@link ActivityRecord#setState(ActivityRecord.State, String)} .
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, ActivityRecord.State state,
            String reason) {
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
            final WindowContainer other = parent.getChildAt(i);
            if (other == null) continue;

            final boolean hasRunningActivities = hasRunningActivity(other);
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
                if (isTranslucent(other, starting)) {
                    // Can be visible behind a translucent fullscreen TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_MULTI_WINDOW
                    && other.matchParentBounds()) {
                if (isTranslucent(other, starting)) {
                    // Can be visible behind a translucent TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                // Multi-window TaskFragment that matches parent bounds would occlude other children
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && !gotOpaqueSplitScreenPrimary) {
                gotRootSplitScreenFragment = true;
                gotTranslucentSplitScreenPrimary = isTranslucent(other, starting);
                gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && gotOpaqueSplitScreenPrimary) {
                    // Can't be visible behind another opaque TaskFragment in split-screen-primary.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && !gotOpaqueSplitScreenSecondary) {
                gotRootSplitScreenFragment = true;
                gotTranslucentSplitScreenSecondary = isTranslucent(other, starting);
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

            final TaskFragment otherTaskFrag = other.asTaskFragment();
            if (otherTaskFrag != null && otherTaskFrag.mAdjacentTaskFragment != null) {
                if (adjacentTaskFragments.contains(otherTaskFrag.mAdjacentTaskFragment)) {
                    if (otherTaskFrag.isTranslucent(starting)
                            || otherTaskFrag.mAdjacentTaskFragment.isTranslucent(starting)) {
                        // Can be visible behind a translucent adjacent TaskFragments.
                        gotTranslucentFullscreen = true;
                        continue;
                    }
                    // Can not be visible behind adjacent TaskFragments.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                } else {
                    adjacentTaskFragments.add(otherTaskFrag);
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

    private static boolean hasRunningActivity(WindowContainer wc) {
        if (wc.asTaskFragment() != null) {
            return wc.asTaskFragment().topRunningActivity() != null;
        }
        return wc.asActivityRecord() != null && !wc.asActivityRecord().finishing;
    }

    private static boolean isTranslucent(WindowContainer wc, ActivityRecord starting) {
        if (wc.asTaskFragment() != null) {
            return wc.asTaskFragment().isTranslucent(starting);
        } else if (wc.asActivityRecord() != null) {
            return !wc.asActivityRecord().occludesParent();
        }
        return false;
    }


    private boolean isTopActivityLaunchedBehind() {
        final ActivityRecord top = topRunningActivity();
        return top != null && top.mLaunchTaskBehind;
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
            pausing |= startPausing(mTaskSupervisor.mUserLeaving, false /* uiSleeping */,
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
            final boolean lastActivityTranslucent = inMultiWindowMode()
                    || mLastPausedActivity != null && !mLastPausedActivity.occludesParent();

            // This activity is now becoming visible.
            if (!next.mVisibleRequested || next.stopped || lastActivityTranslucent) {
                next.setVisibility(true);
            }

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity =
                    lastFocusedRootTask == null ? null
                            : lastFocusedRootTask.getTopResumedActivity();
            final ActivityRecord.State lastState = next.getState();

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
    boolean startPausing(boolean userLeaving, boolean uiSleeping, ActivityRecord resuming,
            String reason) {
        if (!hasDirectChildActivities()) {
            return false;
        }

        ProtoLog.d(WM_DEBUG_STATES, "startPausing: taskFrag =%s " + "mResumedActivity=%s", this,
                mResumedActivity);

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
                mRootWindowContainer.resumeFocusedTasksTopActivities(topRootTask, prev,
                        null /* targetOptions */);
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
    void forAllTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        super.forAllTaskFragments(callback, traverseTopToBottom);
        callback.accept(this);
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

    void addChild(ActivityRecord r) {
        addChild(r, POSITION_TOP);
    }

    @Override
    void addChild(WindowContainer child, int index) {
        boolean isAddingActivity = child.asActivityRecord() != null;
        final Task task = isAddingActivity ? getTask() : null;

        // If this task had any child before we added this one.
        boolean taskHadChild = task != null && task.hasChild();
        // getActivityType() looks at the top child, so we need to read the type before adding
        // a new child in case the new child is on top and UNDEFINED.
        final int activityType = task != null ? task.getActivityType() : ACTIVITY_TYPE_UNDEFINED;

        super.addChild(child, index);

        if (isAddingActivity && task != null) {
            child.asActivityRecord().inHistory = true;
            task.onDescendantActivityAdded(taskHadChild, activityType, child.asActivityRecord());
        }
    }

    void executeAppTransition(ActivityOptions options) {
        // No app transition applied to the task fragment.
    }

    boolean shouldSleepActivities() {
        return false;
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        mTmpBounds.set(getResolvedOverrideConfiguration().windowConfiguration.getBounds());
        super.resolveOverrideConfiguration(newParentConfig);

        int windowingMode =
                getResolvedOverrideConfiguration().windowConfiguration.getWindowingMode();
        final int parentWindowingMode = newParentConfig.windowConfiguration.getWindowingMode();

        // Resolve override windowing mode to fullscreen for home task (even on freeform
        // display), or split-screen if in split-screen mode.
        if (getActivityType() == ACTIVITY_TYPE_HOME && windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = WindowConfiguration.isSplitScreenWindowingMode(parentWindowingMode)
                    ? parentWindowingMode : WINDOWING_MODE_FULLSCREEN;
            getResolvedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
        }

        // Do not allow tasks not support multi window to be in a multi-window mode, unless it is in
        // pinned windowing mode.
        if (!supportsMultiWindow()) {
            final int candidateWindowingMode =
                    windowingMode != WINDOWING_MODE_UNDEFINED ? windowingMode : parentWindowingMode;
            if (WindowConfiguration.inMultiWindowMode(candidateWindowingMode)
                    && candidateWindowingMode != WINDOWING_MODE_PINNED) {
                getResolvedOverrideConfiguration().windowConfiguration.setWindowingMode(
                        WINDOWING_MODE_FULLSCREEN);
            }
        }

        if (isLeafTaskFragment()) {
            resolveLeafOnlyOverrideConfigs(newParentConfig, mTmpBounds /* previousBounds */);
        }
        computeConfigResourceOverrides(getResolvedOverrideConfiguration(), newParentConfig);
    }

    boolean supportsMultiWindow() {
        return supportsMultiWindowInDisplayArea(getDisplayArea());
    }

    /**
     * @return whether this task supports multi-window if it is in the given
     *         {@link TaskDisplayArea}.
     */
    boolean supportsMultiWindowInDisplayArea(@Nullable TaskDisplayArea tda) {
        if (!mAtmService.mSupportsMultiWindow) {
            return false;
        }
        final Task task = getTask();
        if (task == null) {
            return false;
        }
        if (tda == null) {
            Slog.w(TAG, "Can't find TaskDisplayArea to determine support for multi"
                    + " window. Task id=" + getTaskId() + " attached=" + isAttached());
            return false;
        }
        if (!getTask().isResizeable() && !tda.supportsNonResizableMultiWindow()) {
            // Not support non-resizable in multi window.
            return false;
        }

        return tda.supportsActivityMinWidthHeightMultiWindow(mMinWidth, mMinHeight);
    }

    private int getTaskId() {
        return getTask() != null ? getTask().mTaskId : INVALID_TASK_ID;
    }

    private void resolveLeafOnlyOverrideConfigs(Configuration newParentConfig,
            Rect previousBounds) {

        int windowingMode =
                getResolvedOverrideConfiguration().windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = newParentConfig.windowConfiguration.getWindowingMode();
        }
        // Commit the resolved windowing mode so the canSpecifyOrientation won't get the old
        // mode that may cause the bounds to be miscalculated, e.g. letterboxed.
        getConfiguration().windowConfiguration.setWindowingMode(windowingMode);
        Rect outOverrideBounds = getResolvedOverrideConfiguration().windowConfiguration.getBounds();

        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            // Use empty bounds to indicate "fill parent".
            outOverrideBounds.setEmpty();
            // The bounds for fullscreen mode shouldn't be adjusted by minimal size. Otherwise if
            // the parent or display is smaller than the size, the content may be cropped.
            return;
        }

        adjustForMinimalTaskDimensions(outOverrideBounds, previousBounds, newParentConfig);
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            computeFreeformBounds(outOverrideBounds, newParentConfig);
            return;
        }
    }

    /** Computes bounds for {@link WindowConfiguration#WINDOWING_MODE_FREEFORM}. */
    private void computeFreeformBounds(@NonNull Rect outBounds,
            @NonNull Configuration newParentConfig) {
        // by policy, make sure the window remains within parent somewhere
        final float density =
                ((float) newParentConfig.densityDpi) / DisplayMetrics.DENSITY_DEFAULT;
        final Rect parentBounds =
                new Rect(newParentConfig.windowConfiguration.getBounds());
        final DisplayContent display = getDisplayContent();
        if (display != null) {
            // If a freeform window moves below system bar, there is no way to move it again
            // by touch. Because its caption is covered by system bar. So we exclude them
            // from root task bounds. and then caption will be shown inside stable area.
            final Rect stableBounds = new Rect();
            display.getStableRect(stableBounds);
            parentBounds.intersect(stableBounds);
        }

        fitWithinBounds(outBounds, parentBounds,
                (int) (density * WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP),
                (int) (density * WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP));

        // Prevent to overlap caption with stable insets.
        final int offsetTop = parentBounds.top - outBounds.top;
        if (offsetTop > 0) {
            outBounds.offset(0, offsetTop);
        }
    }

    /**
     * Adjusts bounds to stay within root task bounds.
     *
     * Since bounds might be outside of root task bounds, this method tries to move the bounds in
     * a way that keep them unchanged, but be contained within the root task bounds.
     *
     * @param bounds Bounds to be adjusted.
     * @param rootTaskBounds Bounds within which the other bounds should remain.
     * @param overlapPxX The amount of px required to be visible in the X dimension.
     * @param overlapPxY The amount of px required to be visible in the Y dimension.
     */
    private static void fitWithinBounds(Rect bounds, Rect rootTaskBounds, int overlapPxX,
            int overlapPxY) {
        if (rootTaskBounds == null || rootTaskBounds.isEmpty() || rootTaskBounds.contains(bounds)) {
            return;
        }

        // For each side of the parent (eg. left), check if the opposing side of the window (eg.
        // right) is at least overlap pixels away. If less, offset the window by that difference.
        int horizontalDiff = 0;
        // If window is smaller than overlap, use it's smallest dimension instead
        int overlapLR = Math.min(overlapPxX, bounds.width());
        if (bounds.right < (rootTaskBounds.left + overlapLR)) {
            horizontalDiff = overlapLR - (bounds.right - rootTaskBounds.left);
        } else if (bounds.left > (rootTaskBounds.right - overlapLR)) {
            horizontalDiff = -(overlapLR - (rootTaskBounds.right - bounds.left));
        }
        int verticalDiff = 0;
        int overlapTB = Math.min(overlapPxY, bounds.width());
        if (bounds.bottom < (rootTaskBounds.top + overlapTB)) {
            verticalDiff = overlapTB - (bounds.bottom - rootTaskBounds.top);
        } else if (bounds.top > (rootTaskBounds.bottom - overlapTB)) {
            verticalDiff = -(overlapTB - (rootTaskBounds.bottom - bounds.top));
        }
        bounds.offset(horizontalDiff, verticalDiff);
    }

    /**
     * Ensures all visible activities at or below the input activity have the right configuration.
     */
    void ensureVisibleActivitiesConfiguration(ActivityRecord start, boolean preserveWindow) {
        mEnsureVisibleActivitiesConfigHelper.process(start, preserveWindow);
    }

    void adjustForMinimalTaskDimensions(@NonNull Rect bounds, @NonNull Rect previousBounds,
            @NonNull Configuration parentConfig) {
        int minWidth = mMinWidth;
        int minHeight = mMinHeight;
        // If the task has no requested minimal size, we'd like to enforce a minimal size
        // so that the user can not render the task fragment too small to manipulate. We don't need
        // to do this for the root pinned task as the bounds are controlled by the system.
        if (!inPinnedWindowingMode()) {
            final int defaultMinSizeDp = mRootWindowContainer.mDefaultMinSizeOfResizeableTaskDp;
            final float density = (float) parentConfig.densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            final int defaultMinSize = (int) (defaultMinSizeDp * density);

            if (minWidth == INVALID_MIN_SIZE) {
                minWidth = defaultMinSize;
            }
            if (minHeight == INVALID_MIN_SIZE) {
                minHeight = defaultMinSize;
            }
        }
        if (bounds.isEmpty()) {
            // If inheriting parent bounds, check if parent bounds adhere to minimum size. If they
            // do, we can just skip.
            final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
            if (parentBounds.width() >= minWidth && parentBounds.height() >= minHeight) {
                return;
            }
            bounds.set(parentBounds);
        }
        final boolean adjustWidth = minWidth > bounds.width();
        final boolean adjustHeight = minHeight > bounds.height();
        if (!(adjustWidth || adjustHeight)) {
            return;
        }

        if (adjustWidth) {
            if (!previousBounds.isEmpty() && bounds.right == previousBounds.right) {
                bounds.left = bounds.right - minWidth;
            } else {
                // Either left bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping left.
                bounds.right = bounds.left + minWidth;
            }
        }
        if (adjustHeight) {
            if (!previousBounds.isEmpty() && bounds.bottom == previousBounds.bottom) {
                bounds.top = bounds.bottom - minHeight;
            } else {
                // Either top bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping top.
                bounds.bottom = bounds.top + minHeight;
            }
        }
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig) {
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* overrideDisplayInfo */,
                null /* compatInsets */);
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig, @Nullable DisplayInfo overrideDisplayInfo) {
        if (overrideDisplayInfo != null) {
            // Make sure the screen related configs can be computed by the provided display info.
            inOutConfig.screenLayout = Configuration.SCREENLAYOUT_UNDEFINED;
            invalidateAppBoundsConfig(inOutConfig);
        }
        computeConfigResourceOverrides(inOutConfig, parentConfig, overrideDisplayInfo,
                null /* compatInsets */);
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        if (compatInsets != null) {
            // Make sure the app bounds can be computed by the compat insets.
            invalidateAppBoundsConfig(inOutConfig);
        }
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* overrideDisplayInfo */,
                compatInsets);
    }

    /**
     * Forces the app bounds related configuration can be computed by
     * {@link #computeConfigResourceOverrides(Configuration, Configuration, DisplayInfo,
     * ActivityRecord.CompatDisplayInsets)}.
     */
    private static void invalidateAppBoundsConfig(@NonNull Configuration inOutConfig) {
        final Rect appBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (appBounds != null) {
            appBounds.setEmpty();
        }
        inOutConfig.screenWidthDp = Configuration.SCREEN_WIDTH_DP_UNDEFINED;
        inOutConfig.screenHeightDp = Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
    }

    /**
     * Calculates configuration values used by the client to get resources. This should be run
     * using app-facing bounds (bounds unmodified by animations or transient interactions).
     *
     * This assumes bounds are non-empty/null. For the null-bounds case, the caller is likely
     * configuring an "inherit-bounds" window which means that all configuration settings would
     * just be inherited from the parent configuration.
     **/
    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig, @Nullable DisplayInfo overrideDisplayInfo,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        int windowingMode = inOutConfig.windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = parentConfig.windowConfiguration.getWindowingMode();
        }

        float density = inOutConfig.densityDpi;
        if (density == Configuration.DENSITY_DPI_UNDEFINED) {
            density = parentConfig.densityDpi;
        }
        density *= DisplayMetrics.DENSITY_DEFAULT_SCALE;

        // The bounds may have been overridden at this level. If the parent cannot cover these
        // bounds, the configuration is still computed according to the override bounds.
        final boolean insideParentBounds;

        final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
        final Rect resolvedBounds = inOutConfig.windowConfiguration.getBounds();
        if (resolvedBounds == null || resolvedBounds.isEmpty()) {
            mTmpFullBounds.set(parentBounds);
            insideParentBounds = true;
        } else {
            mTmpFullBounds.set(resolvedBounds);
            insideParentBounds = parentBounds.contains(resolvedBounds);
        }

        // Non-null compatibility insets means the activity prefers to keep its original size, so
        // out bounds doesn't need to be restricted by the parent or current display
        final boolean customContainerPolicy = compatInsets != null;

        Rect outAppBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (outAppBounds == null || outAppBounds.isEmpty()) {
            // App-bounds hasn't been overridden, so calculate a value for it.
            inOutConfig.windowConfiguration.setAppBounds(mTmpFullBounds);
            outAppBounds = inOutConfig.windowConfiguration.getAppBounds();

            if (!customContainerPolicy && windowingMode != WINDOWING_MODE_FREEFORM) {
                final Rect containingAppBounds;
                if (insideParentBounds) {
                    containingAppBounds = parentConfig.windowConfiguration.getAppBounds();
                } else {
                    // Restrict appBounds to display non-decor rather than parent because the
                    // override bounds are beyond the parent. Otherwise, it won't match the
                    // overridden bounds.
                    final TaskDisplayArea displayArea = getDisplayArea();
                    containingAppBounds = displayArea != null
                            ? displayArea.getWindowConfiguration().getAppBounds() : null;
                }
                if (containingAppBounds != null && !containingAppBounds.isEmpty()) {
                    outAppBounds.intersect(containingAppBounds);
                }
            }
        }

        if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED
                || inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            if (!customContainerPolicy && WindowConfiguration.isFloating(windowingMode)) {
                mTmpNonDecorBounds.set(mTmpFullBounds);
                mTmpStableBounds.set(mTmpFullBounds);
            } else if (!customContainerPolicy
                    && (overrideDisplayInfo != null || getDisplayContent() != null)) {
                final DisplayInfo di = overrideDisplayInfo != null
                        ? overrideDisplayInfo
                        : getDisplayContent().getDisplayInfo();

                // For calculating screenWidthDp, screenWidthDp, we use the stable inset screen
                // area, i.e. the screen area without the system bars.
                // The non decor inset are areas that could never be removed in Honeycomb. See
                // {@link WindowManagerPolicy#getNonDecorInsetsLw}.
                calculateInsetFrames(mTmpNonDecorBounds, mTmpStableBounds, mTmpFullBounds, di);
            } else {
                // Apply the given non-decor and stable insets to calculate the corresponding bounds
                // for screen size of configuration.
                int rotation = inOutConfig.windowConfiguration.getRotation();
                if (rotation == ROTATION_UNDEFINED) {
                    rotation = parentConfig.windowConfiguration.getRotation();
                }
                if (rotation != ROTATION_UNDEFINED && customContainerPolicy) {
                    mTmpNonDecorBounds.set(mTmpFullBounds);
                    mTmpStableBounds.set(mTmpFullBounds);
                    compatInsets.getBoundsByRotation(mTmpBounds, rotation);
                    intersectWithInsetsIfFits(mTmpNonDecorBounds, mTmpBounds,
                            compatInsets.mNonDecorInsets[rotation]);
                    intersectWithInsetsIfFits(mTmpStableBounds, mTmpBounds,
                            compatInsets.mStableInsets[rotation]);
                    outAppBounds.set(mTmpNonDecorBounds);
                } else {
                    // Set to app bounds because it excludes decor insets.
                    mTmpNonDecorBounds.set(outAppBounds);
                    mTmpStableBounds.set(outAppBounds);
                }
            }

            if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                final int overrideScreenWidthDp = (int) (mTmpStableBounds.width() / density);
                inOutConfig.screenWidthDp = (insideParentBounds && !customContainerPolicy)
                        ? Math.min(overrideScreenWidthDp, parentConfig.screenWidthDp)
                        : overrideScreenWidthDp;
            }
            if (inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                final int overrideScreenHeightDp = (int) (mTmpStableBounds.height() / density);
                inOutConfig.screenHeightDp = (insideParentBounds && !customContainerPolicy)
                        ? Math.min(overrideScreenHeightDp, parentConfig.screenHeightDp)
                        : overrideScreenHeightDp;
            }

            if (inOutConfig.smallestScreenWidthDp
                    == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                if (WindowConfiguration.isFloating(windowingMode)) {
                    // For floating tasks, calculate the smallest width from the bounds of the task
                    inOutConfig.smallestScreenWidthDp = (int) (
                            Math.min(mTmpFullBounds.width(), mTmpFullBounds.height()) / density);
                }
                // otherwise, it will just inherit
            }
        }

        if (inOutConfig.orientation == ORIENTATION_UNDEFINED) {
            inOutConfig.orientation = (inOutConfig.screenWidthDp <= inOutConfig.screenHeightDp)
                    ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        }
        if (inOutConfig.screenLayout == Configuration.SCREENLAYOUT_UNDEFINED) {
            // For calculating screen layout, we need to use the non-decor inset screen area for the
            // calculation for compatibility reasons, i.e. screen area without system bars that
            // could never go away in Honeycomb.
            int compatScreenWidthDp = (int) (mTmpNonDecorBounds.width() / density);
            int compatScreenHeightDp = (int) (mTmpNonDecorBounds.height() / density);
            // Use overrides if provided. If both overrides are provided, mTmpNonDecorBounds is
            // undefined so it can't be used.
            if (inOutConfig.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                compatScreenWidthDp = inOutConfig.screenWidthDp;
            }
            if (inOutConfig.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                compatScreenHeightDp = inOutConfig.screenHeightDp;
            }
            // Reducing the screen layout starting from its parent config.
            inOutConfig.screenLayout = computeScreenLayoutOverride(parentConfig.screenLayout,
                    compatScreenWidthDp, compatScreenHeightDp);
        }
    }

    /**
     * Gets bounds with non-decor and stable insets applied respectively.
     *
     * If bounds overhangs the display, those edges will not get insets. See
     * {@link #intersectWithInsetsIfFits}
     *
     * @param outNonDecorBounds where to place bounds with non-decor insets applied.
     * @param outStableBounds where to place bounds with stable insets applied.
     * @param bounds the bounds to inset.
     */
    void calculateInsetFrames(Rect outNonDecorBounds, Rect outStableBounds, Rect bounds,
            DisplayInfo displayInfo) {
        outNonDecorBounds.set(bounds);
        outStableBounds.set(bounds);
        final Task rootTask = getRootTaskFragment().asTask();
        if (rootTask == null || rootTask.mDisplayContent == null) {
            return;
        }
        mTmpBounds.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);

        final DisplayPolicy policy = rootTask.mDisplayContent.getDisplayPolicy();
        policy.getNonDecorInsetsLw(displayInfo.rotation, displayInfo.logicalWidth,
                displayInfo.logicalHeight, displayInfo.displayCutout, mTmpInsets);
        intersectWithInsetsIfFits(outNonDecorBounds, mTmpBounds, mTmpInsets);

        policy.convertNonDecorInsetsToStableInsets(mTmpInsets, displayInfo.rotation);
        intersectWithInsetsIfFits(outStableBounds, mTmpBounds, mTmpInsets);
    }

    /**
     * Intersects inOutBounds with intersectBounds-intersectInsets. If inOutBounds is larger than
     * intersectBounds on a side, then the respective side will not be intersected.
     *
     * The assumption is that if inOutBounds is initially larger than intersectBounds, then the
     * inset on that side is no-longer applicable. This scenario happens when a task's minimal
     * bounds are larger than the provided parent/display bounds.
     *
     * @param inOutBounds the bounds to intersect.
     * @param intersectBounds the bounds to intersect with.
     * @param intersectInsets insets to apply to intersectBounds before intersecting.
     */
    static void intersectWithInsetsIfFits(
            Rect inOutBounds, Rect intersectBounds, Rect intersectInsets) {
        if (inOutBounds.right <= intersectBounds.right) {
            inOutBounds.right =
                    Math.min(intersectBounds.right - intersectInsets.right, inOutBounds.right);
        }
        if (inOutBounds.bottom <= intersectBounds.bottom) {
            inOutBounds.bottom =
                    Math.min(intersectBounds.bottom - intersectInsets.bottom, inOutBounds.bottom);
        }
        if (inOutBounds.left >= intersectBounds.left) {
            inOutBounds.left =
                    Math.max(intersectBounds.left + intersectInsets.left, inOutBounds.left);
        }
        if (inOutBounds.top >= intersectBounds.top) {
            inOutBounds.top =
                    Math.max(intersectBounds.top + intersectInsets.top, inOutBounds.top);
        }
    }

    /** Computes LONG, SIZE and COMPAT parts of {@link Configuration#screenLayout}. */
    static int computeScreenLayoutOverride(int sourceScreenLayout, int screenWidthDp,
            int screenHeightDp) {
        sourceScreenLayout = sourceScreenLayout
                & (Configuration.SCREENLAYOUT_LONG_MASK | Configuration.SCREENLAYOUT_SIZE_MASK);
        final int longSize = Math.max(screenWidthDp, screenHeightDp);
        final int shortSize = Math.min(screenWidthDp, screenHeightDp);
        return Configuration.reduceScreenLayout(sourceScreenLayout, longSize, shortSize);
    }

    @Override
    public int getActivityType() {
        final int applicationType = super.getActivityType();
        if (applicationType != ACTIVITY_TYPE_UNDEFINED || !hasChild()) {
            return applicationType;
        }
        return getTopChild().getActivityType();
    }

    /**
     * Returns a {@link TaskFragmentInfo} with information from this TaskFragment. Should not be
     * called from {@link Task}.
     */
    TaskFragmentInfo getTaskFragmentInfo() {
        return new TaskFragmentInfo(
                mFragmentToken,
                mInitialComponentName,
                mRemoteToken.toWindowContainerToken(),
                getConfiguration(),
                getChildCount() == 0,
                isVisible());
    }

    @Nullable
    IBinder getFragmentToken() {
        return mFragmentToken;
    }

    /** Clear {@link #mLastPausedActivity} for all {@link TaskFragment} children */
    void clearLastPausedActivity() {
        forAllTaskFragments(taskFragment -> taskFragment.mLastPausedActivity = null);
    }

    /**
     * Sets {@link #mMinWidth} and {@link #mMinWidth} to this TaskFragment.
     * It is usually set from the parent {@link Task} when adding the TaskFragment to the window
     * hierarchy.
     */
    void setMinDimensions(int minWidth, int minHeight) {
        if (asTask() != null) {
            throw new UnsupportedOperationException("This method must not be used to Task. The "
                    + " minimum dimension of Task should be passed from Task constructor.");
        }
        mMinWidth = minWidth;
        mMinHeight = minHeight;
    }

    boolean dump(String prefix, FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, final boolean needSep, Runnable header) {
        boolean printed = false;
        Runnable headerPrinter = () -> {
            if (needSep) {
                pw.println();
            }
            if (header != null) {
                header.run();
            }

            dumpInner(prefix, pw, dumpAll, dumpPackage);
        };

        if (dumpPackage == null) {
            // If we are not filtering by package, we want to print absolutely everything,
            // so always print the header even if there are no tasks/activities inside.
            headerPrinter.run();
            headerPrinter = null;
            printed = true;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            WindowContainer child = mChildren.get(i);
            if (child.asTaskFragment() != null) {
                printed |= child.asTaskFragment().dump(prefix + "      ", fd, pw, dumpAll,
                        dumpClient, dumpPackage, needSep, headerPrinter);
            } else if (child.asActivityRecord() != null) {
                ActivityRecord.dumpActivity(fd, pw, i, child.asActivityRecord(), prefix + "      ",
                        "Hist ", true, !dumpAll, dumpClient, dumpPackage, false, headerPrinter,
                        getTask());
            }
        }

        return printed;
    }

    void dumpInner(String prefix, PrintWriter pw, boolean dumpAll, String dumpPackage) {
        pw.print(prefix); pw.print("* "); pw.println(this);
        pw.println(prefix + "  mBounds=" + getRequestedOverrideBounds());
        if (dumpAll) {
            printThisActivity(pw, mLastPausedActivity, dumpPackage, false,
                    prefix + "  mLastPausedActivity: ", null);
        }
    }

    @Override
    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        final ActivityRecord topActivity = topRunningActivity();
        proto.write(USER_ID, topActivity != null ? topActivity.mUserId : USER_NULL);
        proto.write(TITLE, topActivity != null ? topActivity.intent.getComponent()
                .flattenToShortString() : "TaskFragment");
        proto.end(token);
    }

    @Override
    long getProtoFieldId() {
        return TASK_FRAGMENT;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);

        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);

        proto.write(DISPLAY_ID, getDisplayId());
        proto.write(ACTIVITY_TYPE, getActivityType());
        proto.write(MIN_WIDTH, mMinWidth);
        proto.write(MIN_HEIGHT, mMinHeight);

        proto.end(token);
    }
}
