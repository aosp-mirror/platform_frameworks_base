/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;

import static com.android.server.am.ActivityStackProto.BOUNDS;
import static com.android.server.am.ActivityStackProto.CONFIGURATION_CONTAINER;
import static com.android.server.am.ActivityStackProto.DISPLAY_ID;
import static com.android.server.am.ActivityStackProto.FULLSCREEN;
import static com.android.server.am.ActivityStackProto.ID;
import static com.android.server.am.ActivityStackProto.RESUMED_ACTIVITY;
import static com.android.server.am.ActivityStackProto.TASKS;
import static com.android.server.wm.ActivityDisplay.POSITION_BOTTOM;
import static com.android.server.wm.ActivityDisplay.POSITION_TOP;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.wm.ActivityStack.ActivityState.FINISHING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStackSupervisor.PAUSE_IMMEDIATELY;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityStackSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityStackSupervisor.printThisActivity;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONTAINERS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SAVED_STATE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONTAINERS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SAVED_STATE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.H.FIRST_ACTIVITY_STACK_MSG;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.RootActivityContainer.FindTaskResult;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.RemoteAction;
import android.app.ResultInfo;
import android.app.WindowConfiguration.ActivityType;
import android.app.WindowConfiguration.WindowingMode;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.DestroyActivityItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.app.servertransaction.WindowVisibilityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentRecord;

import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * State and management of a single stack of activities.
 */
class ActivityStack extends ConfigurationContainer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStack" : TAG_ATM;
    private static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_APP = TAG + POSTFIX_APP;
    private static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
    private static final String TAG_CONTAINERS = TAG + POSTFIX_CONTAINERS;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_SAVED_STATE = TAG + POSTFIX_SAVED_STATE;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    private static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    // Ticks during which we check progress while waiting for an app to launch.
    static final int LAUNCH_TICK = 500;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    private static final int PAUSE_TIMEOUT = 500;

    // How long we wait for the activity to tell us it has stopped before
    // giving up.  This is a good amount of time because we really need this
    // from the application in order to get its saved state. Once the stop
    // is complete we may start destroying client resources triggering
    // crashes if the UI thread was hung. We put this timeout one second behind
    // the ANR timeout so these situations will generate ANR instead of
    // Surface lost or other errors.
    private static final int STOP_TIMEOUT = 11 * 1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    private static final int DESTROY_TIMEOUT = 10 * 1000;

    // Set to false to disable the preview that is shown while a new activity
    // is being started.
    private static final boolean SHOW_APP_STARTING_PREVIEW = true;

    // How long to wait for all background Activities to redraw following a call to
    // convertToTranslucent().
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;

    // How many activities have to be scheduled to stop to force a stop pass.
    private static final int MAX_STOPPING_TO_FORCE = 3;

    @IntDef(prefix = {"STACK_VISIBILITY"}, value = {
            STACK_VISIBILITY_VISIBLE,
            STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
            STACK_VISIBILITY_INVISIBLE,
    })
    @interface StackVisibility {}

    /** Stack is visible. No other stacks on top that fully or partially occlude it. */
    static final int STACK_VISIBILITY_VISIBLE = 0;

    /** Stack is partially occluded by other translucent stack(s) on top of it. */
    static final int STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT = 1;

    /** Stack is completely invisible. */
    static final int STACK_VISIBILITY_INVISIBLE = 2;

    @Override
    protected int getChildCount() {
        return mTaskHistory.size();
    }

    @Override
    protected TaskRecord getChildAt(int index) {
        return mTaskHistory.get(index);
    }

    @Override
    protected ActivityDisplay getParent() {
        return getDisplay();
    }

    void setParent(ActivityDisplay parent) {
        ActivityDisplay current = getParent();
        if (current != parent) {
            mDisplayId = parent.mDisplayId;
            onParentChanged();
        }
    }

    @Override
    protected void onParentChanged() {
        ActivityDisplay display = getParent();
        if (display != null) {
            // Rotations are relative to the display. This means if there are 2 displays rotated
            // differently (eg. 2 monitors with one landscape and one portrait), moving a stack
            // from one to the other could look like a rotation change. To prevent this
            // apparent rotation change (and corresponding bounds rotation), pretend like our
            // current rotation is already the same as the new display.
            // Note, if ActivityStack or related logic ever gets nested, this logic will need
            // to move to onConfigurationChanged.
            getConfiguration().windowConfiguration.setRotation(
                    display.getWindowConfiguration().getRotation());
        }
        super.onParentChanged();
        if (display != null && inSplitScreenPrimaryWindowingMode()) {
            // If we created a docked stack we want to resize it so it resizes all other stacks
            // in the system.
            getStackDockedModeBounds(null /* dockedBounds */, null /* currentTempTaskBounds */,
                    mTmpRect /* outStackBounds */, mTmpRect2 /* outTempTaskBounds */);
            mStackSupervisor.resizeDockedStackLocked(getRequestedOverrideBounds(), mTmpRect,
                    mTmpRect2, null, null, PRESERVE_WINDOWS);
        }
        mRootActivityContainer.updateUIDsPresentOnDisplay();
    }

    enum ActivityState {
        INITIALIZING,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED,
        RESTARTING_PROCESS
    }

    @VisibleForTesting
    /* The various modes for the method {@link #removeTask}. */
    // Task is being completely removed from all stacks in the system.
    protected static final int REMOVE_TASK_MODE_DESTROYING = 0;
    // Task is being removed from this stack so we can add it to another stack. In the case we are
    // moving we don't want to perform some operations on the task like removing it from window
    // manager or recents.
    static final int REMOVE_TASK_MODE_MOVING = 1;
    // Similar to {@link #REMOVE_TASK_MODE_MOVING} and the task will be added to the top of its new
    // stack and the new stack will be on top of all stacks.
    static final int REMOVE_TASK_MODE_MOVING_TO_TOP = 2;

    final ActivityTaskManagerService mService;
    final WindowManagerService mWindowManager;

    /**
     * The back history of all previous (and possibly still
     * running) activities.  It contains #TaskRecord objects.
     */
    private final ArrayList<TaskRecord> mTaskHistory = new ArrayList<>();

    /**
     * List of running activities, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains HistoryRecord objects.
     */
    private final ArrayList<ActivityRecord> mLRUActivities = new ArrayList<>();

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     */
    ActivityRecord mPausingActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    ActivityRecord mLastPausedActivity = null;

    /**
     * Activities that specify No History must be removed once the user navigates away from them.
     * If the device goes to sleep with such an activity in the paused state then we save it here
     * and finish it later if another activity replaces it on wakeup.
     */
    ActivityRecord mLastNoHistoryActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     */
    ActivityRecord mResumedActivity = null;

    // The topmost Activity passed to convertToTranslucent(). When non-null it means we are
    // waiting for all Activities in mUndrawnActivitiesBelowTopTranslucent to be removed as they
    // are drawn. When the last member of mUndrawnActivitiesBelowTopTranslucent is removed the
    // Activity in mTranslucentActivityWaiting is notified via
    // Activity.onTranslucentConversionComplete(false). If a timeout occurs prior to the last
    // background activity being drawn then the same call will be made with a true value.
    ActivityRecord mTranslucentActivityWaiting = null;
    ArrayList<ActivityRecord> mUndrawnActivitiesBelowTopTranslucent = new ArrayList<>();

    /**
     * Set when we know we are going to be calling updateConfiguration()
     * soon, so want to skip intermediate config checks.
     */
    boolean mConfigWillChange;

    /**
     * When set, will force the stack to report as invisible.
     */
    boolean mForceHidden = false;

    /**
     * Used to keep resumeTopActivityUncheckedLocked() from being entered recursively
     */
    boolean mInResumeTopActivity = false;

    private boolean mUpdateBoundsDeferred;
    private boolean mUpdateBoundsDeferredCalled;
    private boolean mUpdateDisplayedBoundsDeferredCalled;
    private final Rect mDeferredBounds = new Rect();
    private final Rect mDeferredDisplayedBounds = new Rect();

    int mCurrentUser;

    final int mStackId;
    /** The attached Display's unique identifier, or -1 if detached */
    int mDisplayId;

    /** Stores the override windowing-mode from before a transient mode change (eg. split) */
    private int mRestoreOverrideWindowingMode = WINDOWING_MODE_UNDEFINED;

    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final ActivityOptions mTmpOptions = ActivityOptions.makeBasic();

    /** List for processing through a set of activities */
    private final ArrayList<ActivityRecord> mTmpActivities = new ArrayList<>();

    /** Run all ActivityStacks through this */
    protected final ActivityStackSupervisor mStackSupervisor;
    protected final RootActivityContainer mRootActivityContainer;

    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;

    static final int PAUSE_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 1;
    static final int DESTROY_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 2;
    static final int LAUNCH_TICK_MSG = FIRST_ACTIVITY_STACK_MSG + 3;
    static final int STOP_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 4;
    static final int DESTROY_ACTIVITIES_MSG = FIRST_ACTIVITY_STACK_MSG + 5;
    static final int TRANSLUCENT_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 6;

    // TODO: remove after unification.
    TaskStack mTaskStack;

    private static class ScheduleDestroyArgs {
        final WindowProcessController mOwner;
        final String mReason;
        ScheduleDestroyArgs(WindowProcessController owner, String reason) {
            mOwner = owner;
            mReason = reason;
        }
    }

    final Handler mHandler;

    private class ActivityStackHandler extends Handler {

        ActivityStackHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PAUSE_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity pause timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        if (r.hasProcess()) {
                            mService.logAppTooSlow(r.app, r.pauseTime, "pausing " + r);
                        }
                        activityPausedLocked(r.appToken, true);
                    }
                } break;
                case LAUNCH_TICK_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    synchronized (mService.mGlobalLock) {
                        if (r.continueLaunchTickingLocked()) {
                            mService.logAppTooSlow(r.app, r.launchTickTime, "launching " + r);
                        }
                    }
                } break;
                case DESTROY_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity destroy timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        activityDestroyedLocked(r != null ? r.appToken : null, "destroyTimeout");
                    }
                } break;
                case STOP_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity stop timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        if (r.isInHistory()) {
                            r.activityStoppedLocked(null /* icicle */,
                                    null /* persistentState */, null /* description */);
                        }
                    }
                } break;
                case DESTROY_ACTIVITIES_MSG: {
                    ScheduleDestroyArgs args = (ScheduleDestroyArgs)msg.obj;
                    synchronized (mService.mGlobalLock) {
                        destroyActivitiesLocked(args.mOwner, args.mReason);
                    }
                } break;
                case TRANSLUCENT_TIMEOUT_MSG: {
                    synchronized (mService.mGlobalLock) {
                        notifyActivityDrawnLocked(null);
                    }
                } break;
            }
        }
    }

    int numActivities() {
        int count = 0;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            count += mTaskHistory.get(taskNdx).mActivities.size();
        }
        return count;
    }

    ActivityStack(ActivityDisplay display, int stackId, ActivityStackSupervisor supervisor,
            int windowingMode, int activityType, boolean onTop) {
        mStackSupervisor = supervisor;
        mService = supervisor.mService;
        mRootActivityContainer = mService.mRootActivityContainer;
        mHandler = new ActivityStackHandler(supervisor.mLooper);
        mWindowManager = mService.mWindowManager;
        mStackId = stackId;
        mCurrentUser = mService.mAmInternal.getCurrentUserId();
        // Set display id before setting activity and window type to make sure it won't affect
        // stacks on a wrong display.
        mDisplayId = display.mDisplayId;
        setActivityType(activityType);
        createTaskStack(display.mDisplayId, onTop, mTmpRect2);
        setWindowingMode(windowingMode, false /* animate */, false /* showRecents */,
                false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */,
                true /* creating */);
        display.addChild(this, onTop ? POSITION_TOP : POSITION_BOTTOM);
    }

    void createTaskStack(int displayId, boolean onTop, Rect outBounds) {
        final DisplayContent dc = mWindowManager.mRoot.getDisplayContent(displayId);
        if (dc == null) {
            throw new IllegalArgumentException("Trying to add stackId=" + mStackId
                    + " to unknown displayId=" + displayId);
        }
        mTaskStack = new TaskStack(mWindowManager, mStackId, this);
        dc.setStackOnDisplay(mStackId, onTop, mTaskStack);
        if (mTaskStack.matchParentBounds()) {
            outBounds.setEmpty();
        } else {
            mTaskStack.getRawBounds(outBounds);
        }
    }

    TaskStack getTaskStack() {
        return mTaskStack;
    }

    /**
     * This should be called when an activity in a child task changes state. This should only
     * be called from
     * {@link TaskRecord#onActivityStateChanged(ActivityRecord, ActivityState, String)}.
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, ActivityState state, String reason) {
        if (record == mResumedActivity && state != RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }

        if (state == RESUMED) {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "set resumed activity to:" + record + " reason:"
                    + reason);
            setResumedActivity(record, reason + " - onActivityStateChanged");
            if (record == mRootActivityContainer.getTopResumedActivity()) {
                // TODO(b/111361570): Support multiple focused apps in WM
                mService.setResumedActivityUncheckLocked(record, reason);
            }
            mStackSupervisor.mRecentTasks.add(record.getTaskRecord());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        final int prevWindowingMode = getWindowingMode();
        final boolean prevIsAlwaysOnTop = isAlwaysOnTop();
        final int prevRotation = getWindowConfiguration().getRotation();
        final int prevDensity = getConfiguration().densityDpi;
        final int prevScreenW = getConfiguration().screenWidthDp;
        final int prevScreenH = getConfiguration().screenHeightDp;
        final Rect newBounds = mTmpRect;
        // Initialize the new bounds by previous bounds as the input and output for calculating
        // override bounds in pinned (pip) or split-screen mode.
        getBounds(newBounds);

        super.onConfigurationChanged(newParentConfig);
        final ActivityDisplay display = getDisplay();
        if (display == null || getTaskStack() == null) {
            return;
        }

        final boolean windowingModeChanged = prevWindowingMode != getWindowingMode();
        final int overrideWindowingMode = getRequestedOverrideWindowingMode();
        // Update bounds if applicable
        boolean hasNewOverrideBounds = false;
        // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
        if (overrideWindowingMode == WINDOWING_MODE_PINNED) {
            // Pinned calculation already includes rotation
            hasNewOverrideBounds = getTaskStack().calculatePinnedBoundsForConfigChange(newBounds);
        } else if (!matchParentBounds()) {
            // If the parent (display) has rotated, rotate our bounds to best-fit where their
            // bounds were on the pre-rotated display.
            final int newRotation = getWindowConfiguration().getRotation();
            final boolean rotationChanged = prevRotation != newRotation;
            if (rotationChanged) {
                display.mDisplayContent.rotateBounds(
                        newParentConfig.windowConfiguration.getBounds(), prevRotation, newRotation,
                        newBounds);
                hasNewOverrideBounds = true;
            }

            // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
            if (overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    || overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                // If entering split screen or if something about the available split area changes,
                // recalculate the split windows to match the new configuration.
                if (rotationChanged || windowingModeChanged
                        || prevDensity != getConfiguration().densityDpi
                        || prevScreenW != getConfiguration().screenWidthDp
                        || prevScreenH != getConfiguration().screenHeightDp) {
                    getTaskStack().calculateDockedBoundsForConfigChange(newParentConfig, newBounds);
                    hasNewOverrideBounds = true;
                }
            }
        }

        if (windowingModeChanged) {
            // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
            if (overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                getStackDockedModeBounds(null /* dockedBounds */, null /* currentTempTaskBounds */,
                        newBounds /* outStackBounds */, mTmpRect2 /* outTempTaskBounds */);
                // immediately resize so docked bounds are available in onSplitScreenModeActivated
                setTaskDisplayedBounds(null);
                setTaskBounds(newBounds);
                setBounds(newBounds);
                newBounds.set(newBounds);
            } else if (overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                Rect dockedBounds = display.getSplitScreenPrimaryStack().getBounds();
                final boolean isMinimizedDock =
                        display.mDisplayContent.getDockedDividerController().isMinimizedDock();
                if (isMinimizedDock) {
                    TaskRecord topTask = display.getSplitScreenPrimaryStack().topTask();
                    if (topTask != null) {
                        dockedBounds = topTask.getBounds();
                    }
                }
                getStackDockedModeBounds(dockedBounds, null /* currentTempTaskBounds */,
                        newBounds /* outStackBounds */, mTmpRect2 /* outTempTaskBounds */);
                hasNewOverrideBounds = true;
            }
            display.onStackWindowingModeChanged(this);
        }
        if (hasNewOverrideBounds) {
            // Note the resizeStack may enter onConfigurationChanged recursively, so we make a copy
            // of the temporary bounds (newBounds is mTmpRect) to avoid it being modified.
            mRootActivityContainer.resizeStack(this, new Rect(newBounds), null /* tempTaskBounds */,
                    null /* tempTaskInsetBounds */, PRESERVE_WINDOWS,
                    true /* allowResizeInDockedMode */, true /* deferResume */);
        }
        if (prevIsAlwaysOnTop != isAlwaysOnTop()) {
            // Since always on top is only on when the stack is freeform or pinned, the state
            // can be toggled when the windowing mode changes. We must make sure the stack is
            // placed properly when always on top state changes.
            display.positionChildAtTop(this, false /* includingParents */);
        }
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode, false /* animate */, false /* showRecents */,
                false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */,
                false /* creating */);
    }

    /**
     * A transient windowing mode is one which activities enter into temporarily. Examples of this
     * are Split window modes and pip. Non-transient modes are modes that displays can adopt.
     *
     * @param windowingMode the windowingMode to test for transient-ness.
     * @return {@code true} if the windowing mode is transient, {@code false} otherwise.
     */
    private static boolean isTransientWindowingMode(int windowingMode) {
        // TODO(b/114842032): add PIP if/when it uses mode transitions instead of task reparenting
        return windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
    }

    /**
     * Specialization of {@link #setWindowingMode(int)} for this subclass.
     *
     * @param preferredWindowingMode the preferred windowing mode. This may not be honored depending
     *         on the state of things. For example, WINDOWING_MODE_UNDEFINED will resolve to the
     *         previous non-transient mode if this stack is currently in a transient mode.
     * @param animate Can be used to prevent animation.
     * @param showRecents Controls whether recents is shown on the other side of a split while
     *         entering split mode.
     * @param enteringSplitScreenMode {@code true} if entering split mode.
     * @param deferEnsuringVisibility Whether visibility updates are deferred. This is set when
     *         many operations (which can effect visibility) are being performed in bulk.
     * @param creating {@code true} if this is being run during ActivityStack construction.
     */
    void setWindowingMode(int preferredWindowingMode, boolean animate, boolean showRecents,
            boolean enteringSplitScreenMode, boolean deferEnsuringVisibility, boolean creating) {
        mWindowManager.inSurfaceTransaction(() -> setWindowingModeInSurfaceTransaction(
                preferredWindowingMode, animate, showRecents, enteringSplitScreenMode,
                deferEnsuringVisibility, creating));
    }

    private void setWindowingModeInSurfaceTransaction(int preferredWindowingMode, boolean animate,
            boolean showRecents, boolean enteringSplitScreenMode, boolean deferEnsuringVisibility,
            boolean creating) {
        final int currentMode = getWindowingMode();
        final int currentOverrideMode = getRequestedOverrideWindowingMode();
        final ActivityDisplay display = getDisplay();
        final TaskRecord topTask = topTask();
        final ActivityStack splitScreenStack = display.getSplitScreenPrimaryStack();
        int windowingMode = preferredWindowingMode;
        if (preferredWindowingMode == WINDOWING_MODE_UNDEFINED
                && isTransientWindowingMode(currentMode)) {
            // Leaving a transient mode. Interpret UNDEFINED as "restore"
            windowingMode = mRestoreOverrideWindowingMode;
        }
        mTmpOptions.setLaunchWindowingMode(windowingMode);

        // Need to make sure windowing mode is supported. If we in the process of creating the stack
        // no need to resolve the windowing mode again as it is already resolved to the right mode.
        if (!creating) {
            windowingMode = display.validateWindowingMode(windowingMode,
                    null /* ActivityRecord */, topTask, getActivityType());
        }
        if (splitScreenStack == this
                && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            // Resolution to split-screen secondary for the primary split-screen stack means
            // we want to leave split-screen mode.
            windowingMode = mRestoreOverrideWindowingMode;
        }

        final boolean alreadyInSplitScreenMode = display.hasSplitScreenPrimaryStack();

        // Don't send non-resizeable notifications if the windowing mode changed was a side effect
        // of us entering split-screen mode.
        final boolean sendNonResizeableNotification = !enteringSplitScreenMode;
        // Take any required action due to us not supporting the preferred windowing mode.
        if (alreadyInSplitScreenMode && windowingMode == WINDOWING_MODE_FULLSCREEN
                && sendNonResizeableNotification && isActivityTypeStandardOrUndefined()) {
            final boolean preferredSplitScreen =
                    preferredWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    || preferredWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (preferredSplitScreen || creating) {
                // Looks like we can't launch in split screen mode or the stack we are launching
                // doesn't support split-screen mode, go ahead an dismiss split-screen and display a
                // warning toast about it.
                mService.getTaskChangeNotificationController().notifyActivityDismissingDockedStack();
                final ActivityStack primarySplitStack = display.getSplitScreenPrimaryStack();
                primarySplitStack.setWindowingModeInSurfaceTransaction(WINDOWING_MODE_UNDEFINED,
                        false /* animate */, false /* showRecents */,
                        false /* enteringSplitScreenMode */, true /* deferEnsuringVisibility */,
                        primarySplitStack == this ? creating : false);
            }
        }

        if (currentMode == windowingMode) {
            // You are already in the window mode, so we can skip most of the work below. However,
            // it's possible that we have inherited the current windowing mode from a parent. So,
            // fulfill this method's contract by setting the override mode directly.
            getRequestedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
            return;
        }

        final WindowManagerService wm = mService.mWindowManager;
        final ActivityRecord topActivity = getTopActivity();

        // For now, assume that the Stack's windowing mode is what will actually be used
        // by it's activities. In the future, there may be situations where this doesn't
        // happen; so at that point, this message will need to handle that.
        int likelyResolvedMode = windowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            final ConfigurationContainer parent = getParent();
            likelyResolvedMode = parent != null ? parent.getWindowingMode()
                    : WINDOWING_MODE_FULLSCREEN;
        }
        if (sendNonResizeableNotification && likelyResolvedMode != WINDOWING_MODE_FULLSCREEN
                && topActivity != null && topActivity.isNonResizableOrForcedResizable()
                && !topActivity.noDisplay) {
            // Inform the user that they are starting an app that may not work correctly in
            // multi-window mode.
            final String packageName = topActivity.appInfo.packageName;
            mService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                    topTask.taskId, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN, packageName);
        }

        wm.deferSurfaceLayout();
        try {
            if (!animate && topActivity != null) {
                mStackSupervisor.mNoAnimActivities.add(topActivity);
            }
            super.setWindowingMode(windowingMode);
            // setWindowingMode triggers an onConfigurationChanged cascade which can result in a
            // different resolved windowing mode (usually when preferredWindowingMode is UNDEFINED).
            windowingMode = getWindowingMode();

            if (creating) {
                // Nothing else to do if we don't have a window container yet. E.g. call from ctor.
                return;
            }

            if (windowingMode == WINDOWING_MODE_PINNED || currentMode == WINDOWING_MODE_PINNED) {
                // TODO: Need to remove use of PinnedActivityStack for this to be supported.
                // NOTE: Need to ASS.scheduleUpdatePictureInPictureModeIfNeeded() in
                // setWindowModeUnchecked() when this support is added. See TaskRecord.reparent()
                throw new IllegalArgumentException(
                        "Changing pinned windowing mode not currently supported");
            }

            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && splitScreenStack != null) {
                // We already have a split-screen stack in this display, so just move the tasks over.
                // TODO: Figure-out how to do all the stuff in
                // AMS.setTaskWindowingModeSplitScreenPrimary
                throw new IllegalArgumentException("Setting primary split-screen windowing mode"
                        + " while there is already one isn't currently supported");
                //return;
            }
            if (isTransientWindowingMode(windowingMode) && !isTransientWindowingMode(currentMode)) {
                mRestoreOverrideWindowingMode = currentOverrideMode;
            }

            mTmpRect2.setEmpty();
            if (windowingMode != WINDOWING_MODE_FULLSCREEN) {
                if (mTaskStack.matchParentBounds()) {
                    mTmpRect2.setEmpty();
                } else {
                    mTaskStack.getRawBounds(mTmpRect2);
                }
            }

            if (!Objects.equals(getRequestedOverrideBounds(), mTmpRect2)) {
                resize(mTmpRect2, null /* tempTaskBounds */, null /* tempTaskInsetBounds */);
            }
        } finally {
            if (showRecents && !alreadyInSplitScreenMode && mDisplayId == DEFAULT_DISPLAY
                    && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                // Make sure recents stack exist when creating a dock stack as it normally needs to
                // be on the other side of the docked stack and we make visibility decisions based
                // on that.
                // TODO: This is only here to help out with the case where recents stack doesn't
                // exist yet. For that case the initial size of the split-screen stack will be the
                // the one where the home stack is visible since recents isn't visible yet, but the
                // divider will be off. I think we should just make the initial bounds that of home
                // so that the divider matches and remove this logic.
                // TODO: This is currently only called when entering split-screen while in another
                // task, and from the tests
                // TODO (b/78247419): Check if launcher and overview are same then move home stack
                // instead of recents stack. Then fix the rotation animation from fullscreen to
                // minimized mode
                final ActivityStack recentStack = display.getOrCreateStack(
                        WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_RECENTS,
                        true /* onTop */);
                recentStack.moveToFront("setWindowingMode");
                // If task moved to docked stack - show recents if needed.
                mService.mWindowManager.showRecentApps();
            }
            wm.continueSurfaceLayout();
        }

        if (!deferEnsuringVisibility) {
            mRootActivityContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    @Override
    public boolean isCompatible(int windowingMode, int activityType) {
        // TODO: Should we just move this to ConfigurationContainer?
        if (activityType == ACTIVITY_TYPE_UNDEFINED) {
            // Undefined activity types end up in a standard stack once the stack is created on a
            // display, so they should be considered compatible.
            activityType = ACTIVITY_TYPE_STANDARD;
        }
        return super.isCompatible(windowingMode, activityType);
    }

    /** Adds the stack to specified display and calls WindowManager to do the same. */
    void reparent(ActivityDisplay activityDisplay, boolean onTop, boolean displayRemoved) {
        // TODO: We should probably resolve the windowing mode for the stack on the new display here
        // so that it end up in a compatible mode in the new display. e.g. split-screen secondary.
        removeFromDisplay();
        // Reparent the window container before we try to update the position when adding it to
        // the new display below
        mTmpRect2.setEmpty();
        if (mTaskStack == null) {
            // TODO: Remove after unification.
            Log.w(TAG, "Task stack is not valid when reparenting.");
        } else {
            mTaskStack.reparent(activityDisplay.mDisplayId, mTmpRect2, onTop);
        }
        setBounds(mTmpRect2.isEmpty() ? null : mTmpRect2);
        activityDisplay.addChild(this, onTop ? POSITION_TOP : POSITION_BOTTOM);
        if (!displayRemoved) {
            postReparent();
        }
    }

    /** Resume next focusable stack after reparenting to another display. */
    void postReparent() {
        adjustFocusToNextFocusableStack("reparent", true /* allowFocusSelf */);
        mRootActivityContainer.resumeFocusedStacksTopActivities();
        // Update visibility of activities before notifying WM. This way it won't try to resize
        // windows that are no longer visible.
        mRootActivityContainer.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);
    }

    /**
     * Updates the inner state of the stack to remove it from its current parent, so it can be
     * either destroyed completely or re-parented.
     */
    private void removeFromDisplay() {
        final ActivityDisplay display = getDisplay();
        if (display != null) {
            display.removeChild(this);
        }
        mDisplayId = INVALID_DISPLAY;
    }

    /** Removes the stack completely. Also calls WindowManager to do the same on its side. */
    void remove() {
        removeFromDisplay();
        if (mTaskStack != null) {
            mTaskStack.removeIfPossible();
            mTaskStack = null;
        }
        onParentChanged();
    }

    ActivityDisplay getDisplay() {
        return mRootActivityContainer.getActivityDisplay(mDisplayId);
    }

    /**
     * @see #getStackDockedModeBounds(Rect, Rect, Rect, Rect)
     */
    void getStackDockedModeBounds(Rect dockedBounds, Rect currentTempTaskBounds,
            Rect outStackBounds, Rect outTempTaskBounds) {
        if (mTaskStack != null) {
            mTaskStack.getStackDockedModeBoundsLocked(getParent().getConfiguration(), dockedBounds,
                    currentTempTaskBounds, outStackBounds, outTempTaskBounds);
        } else {
            outStackBounds.setEmpty();
            outTempTaskBounds.setEmpty();
        }
    }

    void prepareFreezingTaskBounds() {
        if (mTaskStack != null) {
            // TODO: This cannot be false after unification.
            mTaskStack.prepareFreezingTaskBounds();
        }
    }

    void getWindowContainerBounds(Rect outBounds) {
        if (mTaskStack != null) {
            mTaskStack.getBounds(outBounds);
            return;
        }
        outBounds.setEmpty();
    }

    void positionChildWindowContainerAtTop(TaskRecord child) {
        if (mTaskStack != null) {
            // TODO: Remove after unification. This cannot be false after that.
            mTaskStack.positionChildAtTop(child.getTask(), true /* includingParents */);
        }
    }

    void positionChildWindowContainerAtBottom(TaskRecord child) {
        // If there are other focusable stacks on the display, the z-order of the display should not
        // be changed just because a task was placed at the bottom. E.g. if it is moving the topmost
        // task to bottom, the next focusable stack on the same display should be focused.
        final ActivityStack nextFocusableStack = getDisplay().getNextFocusableStack(
                child.getStack(), true /* ignoreCurrent */);
        if (mTaskStack != null) {
            // TODO: Remove after unification. This cannot be false after that.
            mTaskStack.positionChildAtBottom(child.getTask(),
                    nextFocusableStack == null /* includingParents */);
        }
    }

    /**
     * Returns whether to defer the scheduling of the multi-window mode.
     */
    boolean deferScheduleMultiWindowModeChanged() {
        if (inPinnedWindowingMode()) {
            // For the pinned stack, the deferring of the multi-window mode changed is tied to the
            // transition animation into picture-in-picture, and is called once the animation
            // completes, or is interrupted in a way that would leave the stack in a non-fullscreen
            // state.
            // @see BoundsAnimationController
            // @see BoundsAnimationControllerTests
            if (getTaskStack() == null) return false;
            return getTaskStack().deferScheduleMultiWindowModeChanged();
        }
        return false;
    }

    /**
     * Defers updating the bounds of the stack. If the stack was resized/repositioned while
     * deferring, the bounds will update in {@link #continueUpdateBounds()}.
     */
    void deferUpdateBounds() {
        if (!mUpdateBoundsDeferred) {
            mUpdateBoundsDeferred = true;
            mUpdateBoundsDeferredCalled = false;
        }
    }

    /**
     * Continues updating bounds after updates have been deferred. If there was a resize attempt
     * between {@link #deferUpdateBounds()} and {@link #continueUpdateBounds()}, the stack will
     * be resized to that bounds.
     */
    void continueUpdateBounds() {
        if (mUpdateBoundsDeferred) {
            mUpdateBoundsDeferred = false;
            if (mUpdateBoundsDeferredCalled) {
                setTaskBounds(mDeferredBounds);
                setBounds(mDeferredBounds);
            }
            if (mUpdateDisplayedBoundsDeferredCalled) {
                setTaskDisplayedBounds(mDeferredDisplayedBounds);
            }
        }
    }

    boolean updateBoundsAllowed(Rect bounds) {
        if (!mUpdateBoundsDeferred) {
            return true;
        }
        if (bounds != null) {
            mDeferredBounds.set(bounds);
        } else {
            mDeferredBounds.setEmpty();
        }
        mUpdateBoundsDeferredCalled = true;
        return false;
    }

    boolean updateDisplayedBoundsAllowed(Rect bounds) {
        if (!mUpdateBoundsDeferred) {
            return true;
        }
        if (bounds != null) {
            mDeferredDisplayedBounds.set(bounds);
        } else {
            mDeferredDisplayedBounds.setEmpty();
        }
        mUpdateDisplayedBoundsDeferredCalled = true;
        return false;
    }

    @Override
    public int setBounds(Rect bounds) {
        return super.setBounds(!inMultiWindowMode() ? null : bounds);
    }

    ActivityRecord topRunningActivityLocked() {
        return topRunningActivityLocked(false /* focusableOnly */);
    }

    void getAllRunningVisibleActivitiesLocked(ArrayList<ActivityRecord> outActivities) {
        outActivities.clear();
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            mTaskHistory.get(taskNdx).getAllRunningVisibleActivitiesLocked(outActivities);
        }
    }

    ActivityRecord topRunningActivityLocked(boolean focusableOnly) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            ActivityRecord r = mTaskHistory.get(taskNdx).topRunningActivityLocked();
            if (r != null && (!focusableOnly || r.isFocusable())) {
                return r;
            }
        }
        return null;
    }

    ActivityRecord topRunningNonOverlayTaskActivity() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && !r.mTaskOverlay) {
                    return r;
                }
            }
        }
        return null;
    }

    ActivityRecord topRunningNonDelayedActivityLocked(ActivityRecord notTop) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && !r.delayedResume && r != notTop && r.okToShowLocked()) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * This is a simplified version of topRunningActivity that provides a number of
     * optional skip-over modes.  It is intended for use with the ActivityController hook only.
     *
     * @param token If non-null, any history records matching this token will be skipped.
     * @param taskId If non-zero, we'll attempt to skip over records with the same task ID.
     *
     * @return Returns the HistoryRecord of the next activity on the stack.
     */
    final ActivityRecord topRunningActivityLocked(IBinder token, int taskId) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.taskId == taskId) {
                continue;
            }
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int i = activities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = activities.get(i);
                // Note: the taskId check depends on real taskId fields being non-zero
                if (!r.finishing && (token != r.appToken) && r.okToShowLocked()) {
                    return r;
                }
            }
        }
        return null;
    }

    ActivityRecord getTopActivity() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ActivityRecord r = mTaskHistory.get(taskNdx).getTopActivity();
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    final TaskRecord topTask() {
        final int size = mTaskHistory.size();
        if (size > 0) {
            return mTaskHistory.get(size - 1);
        }
        return null;
    }

    private TaskRecord bottomTask() {
        if (mTaskHistory.isEmpty()) {
            return null;
        }
        return mTaskHistory.get(0);
    }

    TaskRecord taskForIdLocked(int id) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.taskId == id) {
                return task;
            }
        }
        return null;
    }

    ActivityRecord isInStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return isInStackLocked(r);
    }

    ActivityRecord isInStackLocked(ActivityRecord r) {
        if (r == null) {
            return null;
        }
        final TaskRecord task = r.getTaskRecord();
        final ActivityStack stack = r.getActivityStack();
        if (stack != null && task.mActivities.contains(r) && mTaskHistory.contains(task)) {
            if (stack != this) Slog.w(TAG,
                    "Illegal state! task does not point to stack it is in.");
            return r;
        }
        return null;
    }

    boolean isInStackLocked(TaskRecord task) {
        return mTaskHistory.contains(task);
    }

    /** Checks if there are tasks with specific UID in the stack. */
    boolean isUidPresent(int uid) {
        for (TaskRecord task : mTaskHistory) {
            for (ActivityRecord r : task.mActivities) {
                if (r.getUid() == uid) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Get all UIDs that are present in the stack. */
    void getPresentUIDs(IntArray presentUIDs) {
        for (TaskRecord task : mTaskHistory) {
            for (ActivityRecord r : task.mActivities) {
                presentUIDs.add(r.getUid());
            }
        }
    }

    /** @return true if the stack can only contain one task */
    boolean isSingleTaskInstance() {
        final ActivityDisplay display = getDisplay();
        return display != null && display.isSingleTaskInstance();
    }

    final void removeActivitiesFromLRUListLocked(TaskRecord task) {
        for (ActivityRecord r : task.mActivities) {
            mLRUActivities.remove(r);
        }
    }

    final boolean updateLRUListLocked(ActivityRecord r) {
        final boolean hadit = mLRUActivities.remove(r);
        mLRUActivities.add(r);
        return hadit;
    }

    final boolean isHomeOrRecentsStack() {
        return isActivityTypeHome() || isActivityTypeRecents();
    }

    final boolean isOnHomeDisplay() {
        return mDisplayId == DEFAULT_DISPLAY;
    }

    private boolean returnsToHomeStack() {
        return !inMultiWindowMode()
                && !mTaskHistory.isEmpty()
                && mTaskHistory.get(0).returnsToHomeStack();
    }

    void moveToFront(String reason) {
        moveToFront(reason, null);
    }

    /**
     * @param reason The reason for moving the stack to the front.
     * @param task If non-null, the task will be moved to the top of the stack.
     * */
    void moveToFront(String reason, TaskRecord task) {
        if (!isAttached()) {
            return;
        }

        final ActivityDisplay display = getDisplay();

        if (inSplitScreenSecondaryWindowingMode()) {
            // If the stack is in split-screen seconardy mode, we need to make sure we move the
            // primary split-screen stack forward in the case it is currently behind a fullscreen
            // stack so both halves of the split-screen appear on-top and the fullscreen stack isn't
            // cutting between them.
            // TODO(b/70677280): This is a workaround until we can fix as part of b/70677280.
            final ActivityStack topFullScreenStack =
                    display.getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (topFullScreenStack != null) {
                final ActivityStack primarySplitScreenStack = display.getSplitScreenPrimaryStack();
                if (display.getIndexOf(topFullScreenStack)
                        > display.getIndexOf(primarySplitScreenStack)) {
                    primarySplitScreenStack.moveToFront(reason + " splitScreenToTop");
                }
            }
        }

        if (!isActivityTypeHome() && returnsToHomeStack()) {
            // Make sure the home stack is behind this stack since that is where we should return to
            // when this stack is no longer visible.
            display.moveHomeStackToFront(reason + " returnToHome");
        }

        final boolean movingTask = task != null;
        display.positionChildAtTop(this, !movingTask /* includingParents */, reason);
        if (movingTask) {
            // This also moves the entire hierarchy branch to top, including parents
            insertTaskAtTop(task, null /* starting */);
        }
    }

    /**
     * @param reason The reason for moving the stack to the back.
     * @param task If non-null, the task will be moved to the bottom of the stack.
     **/
    void moveToBack(String reason, TaskRecord task) {
        if (!isAttached()) {
            return;
        }

        /**
         * The intent behind moving a primary split screen stack to the back is usually to hide
         * behind the home stack. Exit split screen in this case.
         */
        if (getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            setWindowingMode(WINDOWING_MODE_UNDEFINED);
        }

        getDisplay().positionChildAtBottom(this, reason);
        if (task != null) {
            insertTaskAtBottom(task);
        }
    }

    boolean isFocusable() {
        final ActivityRecord r = topRunningActivityLocked();
        return mRootActivityContainer.isFocusable(this, r != null && r.isFocusable());
    }

    boolean isFocusableAndVisible() {
        return isFocusable() && shouldBeVisible(null /* starting */);
    }

    final boolean isAttached() {
        final ActivityDisplay display = getDisplay();
        return display != null && !display.isRemoved();
    }

    /**
     * Returns the top activity in any existing task matching the given Intent in the input result.
     * Returns null if no such task is found.
     */
    void findTaskLocked(ActivityRecord target, FindTaskResult result) {
        Intent intent = target.intent;
        ActivityInfo info = target.info;
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        final int userId = UserHandle.getUserId(info.applicationInfo.uid);
        boolean isDocument = intent != null & intent.isDocument();
        // If documentData is non-null then it must match the existing task data.
        Uri documentData = isDocument ? intent.getData() : null;

        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Looking for task of " + target + " in " + this);
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.voiceSession != null) {
                // We never match voice sessions; those always run independently.
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": voice session");
                continue;
            }
            if (task.userId != userId) {
                // Looking for a different task.
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": different user");
                continue;
            }

            // Overlays should not be considered as the task's logical top activity.
            final ActivityRecord r = task.getTopActivity(false /* includeOverlays */);
            if (r == null || r.finishing || r.mUserId != userId ||
                    r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": mismatch root " + r);
                continue;
            }
            if (!r.hasCompatibleActivityType(target)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": mismatch activity type");
                continue;
            }

            final Intent taskIntent = task.intent;
            final Intent affinityIntent = task.affinityIntent;
            final boolean taskIsDocument;
            final Uri taskDocumentData;
            if (taskIntent != null && taskIntent.isDocument()) {
                taskIsDocument = true;
                taskDocumentData = taskIntent.getData();
            } else if (affinityIntent != null && affinityIntent.isDocument()) {
                taskIsDocument = true;
                taskDocumentData = affinityIntent.getData();
            } else {
                taskIsDocument = false;
                taskDocumentData = null;
            }

            if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Comparing existing cls="
                    + (task.realActivity != null ? task.realActivity.flattenToShortString() : "")
                    + "/aff=" + r.getTaskRecord().rootAffinity + " to new cls="
                    + intent.getComponent().flattenToShortString() + "/aff=" + info.taskAffinity);
            // TODO Refactor to remove duplications. Check if logic can be simplified.
            if (task.realActivity != null && task.realActivity.compareTo(cls) == 0
                    && Objects.equals(documentData, taskDocumentData)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching class!");
                //dump();
                if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                        "For Intent " + intent + " bringing to top: " + r.intent);
                result.mRecord = r;
                result.mIdealMatch = true;
                break;
            } else if (affinityIntent != null && affinityIntent.getComponent() != null &&
                    affinityIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching class!");
                //dump();
                if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                        "For Intent " + intent + " bringing to top: " + r.intent);
                result.mRecord = r;
                result.mIdealMatch = true;
                break;
            } else if (!isDocument && !taskIsDocument
                    && result.mRecord == null && task.rootAffinity != null) {
                if (task.rootAffinity.equals(target.taskAffinity)) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching affinity candidate!");
                    // It is possible for multiple tasks to have the same root affinity especially
                    // if they are in separate stacks. We save off this candidate, but keep looking
                    // to see if there is a better candidate.
                    result.mRecord = r;
                    result.mIdealMatch = false;
                }
            } else if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Not a match: " + task);
        }
    }

    /**
     * Returns the first activity (starting from the top of the stack) that
     * is the same as the given activity.  Returns null if no such activity
     * is found.
     */
    ActivityRecord findActivityLocked(Intent intent, ActivityInfo info,
                                      boolean compareIntentFilters) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        final int userId = UserHandle.getUserId(info.applicationInfo.uid);

        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;

            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.okToShowLocked()) {
                    continue;
                }
                if (!r.finishing && r.mUserId == userId) {
                    if (compareIntentFilters) {
                        if (r.intent.filterEquals(intent)) {
                            return r;
                        }
                    } else {
                        if (r.intent.getComponent().equals(cls)) {
                            return r;
                        }
                    }
                }
            }
        }

        return null;
    }

    /*
     * Move the activities around in the stack to bring a user to the foreground.
     */
    final void switchUserLocked(int userId) {
        if (mCurrentUser == userId) {
            return;
        }
        mCurrentUser = userId;

        // Move userId's tasks to the top.
        int index = mTaskHistory.size();
        for (int i = 0; i < index; ) {
            final TaskRecord task = mTaskHistory.get(i);

            if (task.okToShowLocked()) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "switchUser: stack=" + getStackId() +
                        " moving " + task + " to top");
                mTaskHistory.remove(i);
                mTaskHistory.add(task);
                --index;
                // Use same value for i.
            } else {
                ++i;
            }
        }
    }

    void minimalResumeActivityLocked(ActivityRecord r) {
        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + r + " (starting new instance)"
                + " callers=" + Debug.getCallers(5));
        r.setState(RESUMED, "minimalResumeActivityLocked");
        r.completeResumeLocked();
        if (DEBUG_SAVED_STATE) Slog.i(TAG_SAVED_STATE,
                "Launch completed; removing icicle of " + r.icicle);
    }

    private void clearLaunchTime(ActivityRecord r) {
        // Make sure that there is no activity waiting for this to launch.
        if (!mStackSupervisor.mWaitingActivityLaunched.isEmpty()) {
            mStackSupervisor.removeTimeoutsForActivityLocked(r);
            mStackSupervisor.scheduleIdleTimeoutLocked(r);
        }
    }

    void awakeFromSleepingLocked() {
        // Ensure activities are no longer sleeping.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                activities.get(activityNdx).setSleeping(false);
            }
        }
        if (mPausingActivity != null) {
            Slog.d(TAG, "awakeFromSleepingLocked: previously pausing activity didn't pause");
            activityPausedLocked(mPausingActivity.appToken, true);
        }
    }

    void updateActivityApplicationInfoLocked(ApplicationInfo aInfo) {
        final String packageName = aInfo.packageName;
        final int userId = UserHandle.getUserId(aInfo.uid);

        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final List<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord ar = activities.get(activityNdx);

                if ((userId == ar.mUserId) && packageName.equals(ar.packageName)) {
                    ar.updateApplicationInfo(aInfo);
                }
            }
        }
    }

    void checkReadyForSleep() {
        if (shouldSleepActivities() && goToSleepIfPossible(false /* shuttingDown */)) {
            mStackSupervisor.checkReadyForSleepLocked(true /* allowDelay */);
        }
    }

    /**
     * Tries to put the activities in the stack to sleep.
     *
     * If the stack is not in a state where its activities can be put to sleep, this function will
     * start any necessary actions to move the stack into such a state. It is expected that this
     * function get called again when those actions complete.
     *
     * @param shuttingDown true when the called because the device is shutting down.
     * @return true if the stack finished going to sleep, false if the stack only started the
     * process of going to sleep (checkReadyForSleep will be called when that process finishes).
     */
    boolean goToSleepIfPossible(boolean shuttingDown) {
        boolean shouldSleep = true;

        if (mResumedActivity != null) {
            // Still have something resumed; can't sleep until it is paused.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep needs to pause " + mResumedActivity);
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "Sleep => pause with userLeaving=false");

            startPausingLocked(false, true, null, false);
            shouldSleep = false ;
        } else if (mPausingActivity != null) {
            // Still waiting for something to pause; can't sleep yet.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still waiting to pause " + mPausingActivity);
            shouldSleep = false;
        }

        if (!shuttingDown) {
            if (containsActivityFromStack(mStackSupervisor.mStoppingActivities)) {
                // Still need to tell some activities to stop; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to stop "
                        + mStackSupervisor.mStoppingActivities.size() + " activities");

                mStackSupervisor.scheduleIdleLocked();
                shouldSleep = false;
            }

            if (containsActivityFromStack(mStackSupervisor.mGoingToSleepActivities)) {
                // Still need to tell some activities to sleep; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to sleep "
                        + mStackSupervisor.mGoingToSleepActivities.size() + " activities");
                shouldSleep = false;
            }
        }

        if (shouldSleep) {
            goToSleep();
        }

        return shouldSleep;
    }

    void goToSleep() {
        // Ensure visibility without updating configuration, as activities are about to sleep.
        ensureActivitiesVisibleLocked(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);

        // Make sure any paused or stopped but visible activities are now sleeping.
        // This ensures that the activity's onStop() is called.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.isState(STOPPING, STOPPED, PAUSED, PAUSING)) {
                    r.setSleeping(true);
                }
            }
        }
    }

    private boolean containsActivityFromStack(List<ActivityRecord> rs) {
        for (ActivityRecord r : rs) {
            if (r.getActivityStack() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schedule a pause timeout in case the app doesn't respond. We don't give it much time because
     * this directly impacts the responsiveness seen by the user.
     */
    private void schedulePauseTimeout(ActivityRecord r) {
        final Message msg = mHandler.obtainMessage(PAUSE_TIMEOUT_MSG);
        msg.obj = r;
        r.pauseTime = SystemClock.uptimeMillis();
        mHandler.sendMessageDelayed(msg, PAUSE_TIMEOUT);
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Waiting for pause to complete...");
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
     * @param pauseImmediately True if the caller does not want to wait for the activity callback to
     *                         complete pausing.
     * @return Returns true if an activity now is in the PAUSING state, and we are waiting for
     * it to tell us when it is done.
     */
    final boolean startPausingLocked(boolean userLeaving, boolean uiSleeping,
            ActivityRecord resuming, boolean pauseImmediately) {
        if (mPausingActivity != null) {
            Slog.wtf(TAG, "Going to pause when pause is already pending for " + mPausingActivity
                    + " state=" + mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                // Avoid recursion among check for sleep and complete pause during sleeping.
                // Because activity will be paused immediately after resume, just let pause
                // be completed by the order of activity paused from clients.
                completePauseLocked(false, resuming);
            }
        }
        ActivityRecord prev = mResumedActivity;

        if (prev == null) {
            if (resuming == null) {
                Slog.wtf(TAG, "Trying to pause when nothing is resumed");
                mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }

        if (prev == resuming) {
            Slog.wtf(TAG, "Trying to pause activity that is in process of being resumed");
            return false;
        }

        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSING: " + prev);
        else if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Start pausing: " + prev);
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        mLastNoHistoryActivity = (prev.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (prev.info.flags & ActivityInfo.FLAG_NO_HISTORY) != 0 ? prev : null;
        prev.setState(PAUSING, "startPausingLocked");
        prev.getTaskRecord().touchActiveTime();
        clearLaunchTime(prev);

        mService.updateCpuStats();

        if (prev.attachedToProcess()) {
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueueing pending pause: " + prev);
            try {
                EventLogTags.writeAmPauseActivity(prev.mUserId, System.identityHashCode(prev),
                        prev.shortComponentName, "userLeaving=" + userLeaving);

                mService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
                        prev.appToken, PauseActivityItem.obtain(prev.finishing, userLeaving,
                                prev.configChangeFlags, pauseImmediately));
            } catch (Exception e) {
                // Ignore exception, if process died other code will cleanup.
                Slog.w(TAG, "Exception thrown during pause", e);
                mPausingActivity = null;
                mLastPausedActivity = null;
                mLastNoHistoryActivity = null;
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!uiSleeping && !mService.isSleepingOrShuttingDownLocked()) {
            mStackSupervisor.acquireLaunchWakelock();
        }

        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else if (DEBUG_PAUSE) {
                 Slog.v(TAG_PAUSE, "Key dispatch not paused for screen off");
            }

            if (pauseImmediately) {
                // If the caller said they don't want to wait for the pause, then complete
                // the pause now.
                completePauseLocked(false, resuming);
                return false;

            } else {
                schedulePauseTimeout(prev);
                return true;
            }

        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Activity not running, resuming next.");
            if (resuming == null) {
                mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }
    }

    final void activityPausedLocked(IBinder token, boolean timeout) {
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE,
            "Activity paused: token=" + token + ", timeout=" + timeout);

        final ActivityRecord r = isInStackLocked(token);

        if (r != null) {
            mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
            if (mPausingActivity == r) {
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSED: " + r
                        + (timeout ? " (due to timeout)" : " (pause complete)"));
                mService.mWindowManager.deferSurfaceLayout();
                try {
                    completePauseLocked(true /* resumeNext */, null /* resumingActivity */);
                } finally {
                    mService.mWindowManager.continueSurfaceLayout();
                }
                return;
            } else {
                EventLog.writeEvent(EventLogTags.AM_FAILED_TO_PAUSE,
                        r.mUserId, System.identityHashCode(r), r.shortComponentName,
                        mPausingActivity != null
                            ? mPausingActivity.shortComponentName : "(none)");
                if (r.isState(PAUSING)) {
                    r.setState(PAUSED, "activityPausedLocked");
                    if (r.finishing) {
                        if (DEBUG_PAUSE) Slog.v(TAG,
                                "Executing finish of failed to pause activity: " + r);
                        finishCurrentActivityLocked(r, FINISH_AFTER_VISIBLE, false,
                                "activityPausedLocked");
                    }
                }
            }
        }
        mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
    }

    private void completePauseLocked(boolean resumeNext, ActivityRecord resuming) {
        ActivityRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Complete pause: " + prev);

        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            final boolean wasStopping = prev.isState(STOPPING);
            prev.setState(PAUSED, "completePausedLocked");
            if (prev.finishing) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Executing finish of activity: " + prev);
                prev = finishCurrentActivityLocked(prev, FINISH_AFTER_VISIBLE, false,
                        "completePausedLocked");
            } else if (prev.hasProcess()) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueue pending stop if needed: " + prev
                        + " wasStopping=" + wasStopping + " visible=" + prev.visible);
                if (prev.deferRelaunchUntilPaused) {
                    // Complete the deferred relaunch that was waiting for pause to complete.
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Re-launching after pause: " + prev);
                    prev.relaunchActivityLocked(false /* andResume */,
                            prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    // We are also stopping, the stop request must have gone soon after the pause.
                    // We can't clobber it, because the stop confirmation will not be handled.
                    // We don't need to schedule another stop, we only need to let it happen.
                    prev.setState(STOPPING, "completePausedLocked");
                } else if (!prev.visible || shouldSleepOrShutDownActivities()) {
                    // Clear out any deferred client hide we might currently have.
                    prev.setDeferHidingClient(false);
                    // If we were visible then resumeTopActivities will release resources before
                    // stopping.
                    addToStopping(prev, true /* scheduleIdle */, false /* idleDelayed */,
                            "completePauseLocked");
                }
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "App died during pause, not stopping: " + prev);
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
            final ActivityStack topStack = mRootActivityContainer.getTopDisplayFocusedStack();
            if (!topStack.shouldSleepOrShutDownActivities()) {
                mRootActivityContainer.resumeFocusedStacksTopActivities(topStack, prev, null);
            } else {
                checkReadyForSleep();
                ActivityRecord top = topStack.topRunningActivityLocked();
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the stack is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();

            if (prev.hasProcess() && prev.cpuTimeAtResume > 0) {
                final long diff = prev.app.getCpuTime() - prev.cpuTimeAtResume;
                if (diff > 0) {
                    final Runnable r = PooledLambda.obtainRunnable(
                            ActivityManagerInternal::updateForegroundTimeIfOnBattery,
                            mService.mAmInternal, prev.info.packageName,
                            prev.info.applicationInfo.uid,
                            diff);
                    mService.mH.post(r);
                }
            }
            prev.cpuTimeAtResume = 0; // reset it
        }

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active pinned stack - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mStackSupervisor.mAppVisibilitiesChangedSinceLastPause
                || (getDisplay() != null && getDisplay().hasPinnedStack())) {
            mService.getTaskChangeNotificationController().notifyTaskStackChanged();
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }

        mRootActivityContainer.ensureActivitiesVisible(resuming, 0, !PRESERVE_WINDOWS);
    }

    void addToStopping(ActivityRecord r, boolean scheduleIdle, boolean idleDelayed, String reason) {
        if (!mStackSupervisor.mStoppingActivities.contains(r)) {
            EventLog.writeEvent(EventLogTags.AM_ADD_TO_STOPPING, r.mUserId,
                    System.identityHashCode(r), r.shortComponentName, reason);
            mStackSupervisor.mStoppingActivities.add(r);
        }

        // If we already have a few activities waiting to stop, then give up
        // on things going idle and start clearing them out. Or if r is the
        // last of activity of the last task the stack will be empty and must
        // be cleared immediately.
        boolean forceIdle = mStackSupervisor.mStoppingActivities.size() > MAX_STOPPING_TO_FORCE
                || (r.frontOfTask && mTaskHistory.size() <= 1);
        if (scheduleIdle || forceIdle) {
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Scheduling idle now: forceIdle="
                    + forceIdle + "immediate=" + !idleDelayed);
            if (!idleDelayed) {
                mStackSupervisor.scheduleIdleLocked();
            } else {
                mStackSupervisor.scheduleIdleTimeoutLocked(r);
            }
        } else {
            checkReadyForSleep();
        }
    }

    /**
     * Returns true if the stack is translucent and can have other contents visible behind it if
     * needed. A stack is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     */
    @VisibleForTesting
    boolean isStackTranslucent(ActivityRecord starting) {
        if (!isAttached() || mForceHidden) {
            return true;
        }
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);

                if (r.finishing) {
                    // We don't factor in finishing activities when determining translucency since
                    // they will be gone soon.
                    continue;
                }

                if (!r.visibleIgnoringKeyguard && r != starting) {
                    // Also ignore invisible activities that are not the currently starting
                    // activity (about to be visible).
                    continue;
                }

                if (r.fullscreen || r.hasWallpaper) {
                    // Stack isn't translucent if it has at least one fullscreen activity
                    // that is visible.
                    return false;
                }
            }
        }
        return true;
    }

    boolean isTopStackOnDisplay() {
        final ActivityDisplay display = getDisplay();
        return display != null && display.isTopStack(this);
    }

    /**
     * @return {@code true} if this is the focused stack on its current display, {@code false}
     * otherwise.
     */
    boolean isFocusedStackOnDisplay() {
        final ActivityDisplay display = getDisplay();
        return display != null && this == display.getFocusedStack();
    }

    boolean isTopActivityVisible() {
        final ActivityRecord topActivity = getTopActivity();
        return topActivity != null && topActivity.visible;
    }

    /**
     * Returns true if the stack should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != STACK_VISIBILITY_INVISIBLE;
    }

    /**
     * Returns true if the stack should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    @StackVisibility
    int getVisibility(ActivityRecord starting) {
        if (!isAttached() || mForceHidden) {
            return STACK_VISIBILITY_INVISIBLE;
        }

        final ActivityDisplay display = getDisplay();
        boolean gotSplitScreenStack = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentSplitScreenPrimary = false;
        boolean gotTranslucentSplitScreenSecondary = false;
        boolean shouldBeVisible = true;
        final int windowingMode = getWindowingMode();
        final boolean isAssistantType = isActivityTypeAssistant();
        for (int i = display.getChildCount() - 1; i >= 0; --i) {
            final ActivityStack other = display.getChildAt(i);
            final boolean hasRunningActivities = other.topRunningActivityLocked() != null;
            if (other == this) {
                // Should be visible if there is no other stack occluding it, unless it doesn't
                // have any running activities, not starting one and not home stack.
                shouldBeVisible = hasRunningActivities || isInStackLocked(starting) != null
                        || isActivityTypeHome();
                break;
            }

            if (!hasRunningActivities) {
                continue;
            }

            final int otherWindowingMode = other.getWindowingMode();

            if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                // In this case the home stack isn't resizeable even though we are in split-screen
                // mode. We still want the primary splitscreen stack to be visible as there will be
                // a slight hint of it in the status bar area above the non-resizeable home
                // activity. In addition, if the fullscreen assistant is over primary splitscreen
                // stack, the stack should still be visible in the background as long as the recents
                // animation is running.
                final int activityType = other.getActivityType();
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                    if (activityType == ACTIVITY_TYPE_HOME
                            || (activityType == ACTIVITY_TYPE_ASSISTANT
                                && mWindowManager.getRecentsAnimationController() != null)) {
                        break;
                    }
                }
                if (other.isStackTranslucent(starting)) {
                    // Can be visible behind a translucent fullscreen stack.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return STACK_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && !gotOpaqueSplitScreenPrimary) {
                gotSplitScreenStack = true;
                gotTranslucentSplitScreenPrimary = other.isStackTranslucent(starting);
                gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && gotOpaqueSplitScreenPrimary) {
                    // Can not be visible behind another opaque stack in split-screen-primary mode.
                    return STACK_VISIBILITY_INVISIBLE;
                }
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && !gotOpaqueSplitScreenSecondary) {
                gotSplitScreenStack = true;
                gotTranslucentSplitScreenSecondary = other.isStackTranslucent(starting);
                gotOpaqueSplitScreenSecondary = !gotTranslucentSplitScreenSecondary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        && gotOpaqueSplitScreenSecondary) {
                    // Can not be visible behind another opaque stack in split-screen-secondary mode.
                    return STACK_VISIBILITY_INVISIBLE;
                }
            }
            if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                // Can not be visible if we are in split-screen windowing mode and both halves of
                // the screen are opaque.
                return STACK_VISIBILITY_INVISIBLE;
            }
            if (isAssistantType && gotSplitScreenStack) {
                // Assistant stack can't be visible behind split-screen. In addition to this not
                // making sense, it also works around an issue here we boost the z-order of the
                // assistant window surfaces in window manager whenever it is visible.
                return STACK_VISIBILITY_INVISIBLE;
            }
        }

        if (!shouldBeVisible) {
            return STACK_VISIBILITY_INVISIBLE;
        }

        // Handle cases when there can be a translucent split-screen stack on top.
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                if (gotTranslucentSplitScreenPrimary || gotTranslucentSplitScreenSecondary) {
                    // At least one of the split-screen stacks that covers this one is translucent.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                if (gotTranslucentSplitScreenPrimary) {
                    // Covered by translucent primary split-screen on top.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                if (gotTranslucentSplitScreenSecondary) {
                    // Covered by translucent secondary split-screen on top.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
        }

        // Lastly - check if there is a translucent fullscreen stack on top.
        return gotTranslucentFullscreen ? STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT
                : STACK_VISIBILITY_VISIBLE;
    }

    final int rankTaskLayers(int baseLayer) {
        int layer = 0;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            ActivityRecord r = task.topRunningActivityLocked();
            if (r == null || r.finishing || !r.visible) {
                task.mLayerRank = -1;
            } else {
                task.mLayerRank = baseLayer + layer++;
            }
        }
        return layer;
    }

    /**
     * Make sure that all activities that need to be visible in the stack (that is, they
     * currently can be seen by the user) actually are and update their configuration.
     */
    final void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows,
                true /* notifyClients */);
    }

    /**
     * Ensure visibility with an option to also update the configuration of visible activities.
     * @see #ensureActivitiesVisibleLocked(ActivityRecord, int, boolean)
     * @see RootActivityContainer#ensureActivitiesVisible(ActivityRecord, int, boolean)
     */
    // TODO: Should be re-worked based on the fact that each task as a stack in most cases.
    final void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mTopActivityOccludesKeyguard = false;
        mTopDismissingKeyguardActivity = null;
        mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
        try {
            ActivityRecord top = topRunningActivityLocked();
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "ensureActivitiesVisible behind " + top
                    + " configChanges=0x" + Integer.toHexString(configChanges));
            if (top != null) {
                checkTranslucentActivityWaiting(top);
            }

            // If the top activity is not fullscreen, then we need to
            // make sure any activities under it are now visible.
            boolean aboveTop = top != null;
            final boolean stackShouldBeVisible = shouldBeVisible(starting);
            boolean behindFullscreenActivity = !stackShouldBeVisible;
            boolean resumeNextActivity = isFocusable() && isInStackLocked(starting) == null;
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                final TaskRecord task = mTaskHistory.get(taskNdx);
                final ArrayList<ActivityRecord> activities = task.mActivities;
                for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                    final ActivityRecord r = activities.get(activityNdx);
                    if (r.finishing) {
                        continue;
                    }
                    final boolean isTop = r == top;
                    if (aboveTop && !isTop) {
                        continue;
                    }
                    aboveTop = false;

                    // Check whether activity should be visible without Keyguard influence
                    final boolean visibleIgnoringKeyguard = r.shouldBeVisibleIgnoringKeyguard(
                            behindFullscreenActivity);
                    final boolean reallyVisible = r.shouldBeVisible(behindFullscreenActivity);
                    if (visibleIgnoringKeyguard) {
                        behindFullscreenActivity = updateBehindFullscreen(!stackShouldBeVisible,
                                behindFullscreenActivity, r);
                    }
                    if (reallyVisible) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Make visible? " + r
                                + " finishing=" + r.finishing + " state=" + r.getState());
                        // First: if this is not the current activity being started, make
                        // sure it matches the current configuration.
                        if (r != starting && notifyClients) {
                            r.ensureActivityConfiguration(0 /* globalChanges */, preserveWindows,
                                    true /* ignoreVisibility */);
                        }

                        if (!r.attachedToProcess()) {
                            if (makeVisibleAndRestartIfNeeded(starting, configChanges, isTop,
                                    resumeNextActivity, r)) {
                                if (activityNdx >= activities.size()) {
                                    // Record may be removed if its process needs to restart.
                                    activityNdx = activities.size() - 1;
                                } else {
                                    resumeNextActivity = false;
                                }
                            }
                        } else if (r.visible) {
                            // If this activity is already visible, then there is nothing to do here.
                            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                                    "Skipping: already visible at " + r);

                            if (r.mClientVisibilityDeferred && notifyClients) {
                                r.makeClientVisible();
                            }

                            if (r.handleAlreadyVisible()) {
                                resumeNextActivity = false;
                            }

                            if (notifyClients) {
                                r.makeActiveIfNeeded(starting);
                            }
                        } else {
                            r.makeVisibleIfNeeded(starting, notifyClients);
                        }
                        // Aggregate current change flags.
                        configChanges |= r.configChangeFlags;
                    } else {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Make invisible? " + r
                                + " finishing=" + r.finishing + " state=" + r.getState()
                                + " stackShouldBeVisible=" + stackShouldBeVisible
                                + " behindFullscreenActivity=" + behindFullscreenActivity
                                + " mLaunchTaskBehind=" + r.mLaunchTaskBehind);
                        makeInvisible(r);
                    }
                }
                final int windowingMode = getWindowingMode();
                if (windowingMode == WINDOWING_MODE_FREEFORM) {
                    // The visibility of tasks and the activities they contain in freeform stack are
                    // determined individually unlike other stacks where the visibility or fullscreen
                    // status of an activity in a previous task affects other.
                    behindFullscreenActivity = !stackShouldBeVisible;
                } else if (isActivityTypeHome()) {
                    if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Home task: at " + task
                            + " stackShouldBeVisible=" + stackShouldBeVisible
                            + " behindFullscreenActivity=" + behindFullscreenActivity);
                    // No other task in the home stack should be visible behind the home activity.
                    // Home activities is usually a translucent activity with the wallpaper behind
                    // them. However, when they don't have the wallpaper behind them, we want to
                    // show activities in the next application stack behind them vs. another
                    // task in the home stack like recents.
                    behindFullscreenActivity = true;
                }
            }

            if (mTranslucentActivityWaiting != null &&
                    mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
                // Nothing is getting drawn or everything was already visible, don't wait for timeout.
                notifyActivityDrawnLocked(null);
            }
        } finally {
            mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
        }
    }

    void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            mTaskHistory.get(taskNdx).addStartingWindowsForVisibleActivities(taskSwitch);
        }
    }

    /**
     * @return true if the top visible activity wants to occlude the Keyguard, false otherwise
     */
    boolean topActivityOccludesKeyguard() {
        return mTopActivityOccludesKeyguard;
    }

    /**
     * Returns true if this stack should be resized to match the bounds specified by
     * {@link ActivityOptions#setLaunchBounds} when launching an activity into the stack.
     */
    boolean resizeStackWithLaunchBounds() {
        return inPinnedWindowingMode();
    }

    @Override
    public boolean supportsSplitScreenWindowingMode() {
        final TaskRecord topTask = topTask();
        return super.supportsSplitScreenWindowingMode()
                && (topTask == null || topTask.supportsSplitScreenWindowingMode());
    }

    /** @return True if the resizing of the primary-split-screen stack affects this stack size. */
    boolean affectedBySplitScreenResize() {
        if (!supportsSplitScreenWindowingMode()) {
            return false;
        }
        final int windowingMode = getWindowingMode();
        return windowingMode != WINDOWING_MODE_FREEFORM && windowingMode != WINDOWING_MODE_PINNED;
    }

    /**
     * @return the top most visible activity that wants to dismiss Keyguard
     */
    ActivityRecord getTopDismissingKeyguardActivity() {
        return mTopDismissingKeyguardActivity;
    }

    /**
     * Checks whether {@param r} should be visible depending on Keyguard state and updates
     * {@link #mTopActivityOccludesKeyguard} and {@link #mTopDismissingKeyguardActivity} if
     * necessary.
     *
     * @return true if {@param r} is visible taken Keyguard state into account, false otherwise
     */
    boolean checkKeyguardVisibility(ActivityRecord r, boolean shouldBeVisible, boolean isTop) {
        final int displayId = mDisplayId != INVALID_DISPLAY ? mDisplayId : DEFAULT_DISPLAY;
        final boolean keyguardOrAodShowing = mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        final boolean keyguardLocked = mStackSupervisor.getKeyguardController().isKeyguardLocked();
        final boolean showWhenLocked = r.canShowWhenLocked();
        final boolean dismissKeyguard = r.mAppWindowToken != null
                && r.mAppWindowToken.containsDismissKeyguardWindow();
        if (shouldBeVisible) {
            if (dismissKeyguard && mTopDismissingKeyguardActivity == null) {
                mTopDismissingKeyguardActivity = r;
            }

            // Only the top activity may control occluded, as we can't occlude the Keyguard if the
            // top app doesn't want to occlude it.
            if (isTop) {
                mTopActivityOccludesKeyguard |= showWhenLocked;
            }

            final boolean canShowWithKeyguard = canShowWithInsecureKeyguard()
                    && mStackSupervisor.getKeyguardController().canDismissKeyguard();
            if (canShowWithKeyguard) {
                return true;
            }
        }
        if (keyguardOrAodShowing) {
            // If keyguard is showing, nothing is visible, except if we are able to dismiss Keyguard
            // right away and AOD isn't visible.
            return shouldBeVisible && mStackSupervisor.getKeyguardController()
                    .canShowActivityWhileKeyguardShowing(r, dismissKeyguard);
        } else if (keyguardLocked) {
            return shouldBeVisible && mStackSupervisor.getKeyguardController().canShowWhileOccluded(
                    dismissKeyguard, showWhenLocked);
        } else {
            return shouldBeVisible;
        }
    }

    /**
     * Check if the display to which this stack is attached has
     * {@link Display#FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD} applied.
     */
    boolean canShowWithInsecureKeyguard() {
        final ActivityDisplay activityDisplay = getDisplay();
        if (activityDisplay == null) {
            throw new IllegalStateException("Stack is not attached to any display, stackId="
                    + mStackId);
        }

        final int flags = activityDisplay.mDisplay.getFlags();
        return (flags & FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0;
    }

    private void checkTranslucentActivityWaiting(ActivityRecord top) {
        if (mTranslucentActivityWaiting != top) {
            mUndrawnActivitiesBelowTopTranslucent.clear();
            if (mTranslucentActivityWaiting != null) {
                // Call the callback with a timeout indication.
                notifyActivityDrawnLocked(null);
                mTranslucentActivityWaiting = null;
            }
            mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);
        }
    }

    private boolean makeVisibleAndRestartIfNeeded(ActivityRecord starting, int configChanges,
            boolean isTop, boolean andResume, ActivityRecord r) {
        // We need to make sure the app is running if it's the top, or it is just made visible from
        // invisible. If the app is already visible, it must have died while it was visible. In this
        // case, we'll show the dead window but will not restart the app. Otherwise we could end up
        // thrashing.
        if (isTop || !r.visible) {
            // This activity needs to be visible, but isn't even running...
            // get it started and resume if no other stack in this stack is resumed.
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Start and freeze screen for " + r);
            if (r != starting) {
                r.startFreezingScreenLocked(r.app, configChanges);
            }
            if (!r.visible || r.mLaunchTaskBehind) {
                if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Starting and making visible: " + r);
                r.setVisible(true);
            }
            if (r != starting) {
                // We should not resume activities that being launched behind because these
                // activities are actually behind other fullscreen activities, but still required
                // to be visible (such as performing Recents animation).
                mStackSupervisor.startSpecificActivityLocked(r, andResume && !r.mLaunchTaskBehind,
                        true /* checkConfig */);
                return true;
            }
        }
        return false;
    }

    // TODO: Should probably be moved into ActivityRecord.
    private void makeInvisible(ActivityRecord r) {
        if (!r.visible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Already invisible: " + r);
            return;
        }
        // Now for any activities that aren't visible to the user, make sure they no longer are
        // keeping the screen frozen.
        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Making invisible: " + r + " " + r.getState());
        try {
            final boolean canEnterPictureInPicture = r.checkEnterPictureInPictureState(
                    "makeInvisible", true /* beforeStopping */);
            // Defer telling the client it is hidden if it can enter Pip and isn't current paused,
            // stopped or stopping. This gives it a chance to enter Pip in onPause().
            // TODO: There is still a question surrounding activities in multi-window mode that want
            // to enter Pip after they are paused, but are still visible. I they should be okay to
            // enter Pip in those cases, but not "auto-Pip" which is what this condition covers and
            // the current contract for "auto-Pip" is that the app should enter it before onPause
            // returns. Just need to confirm this reasoning makes sense.
            final boolean deferHidingClient = canEnterPictureInPicture
                    && !r.isState(STOPPING, STOPPED, PAUSED);
            r.setDeferHidingClient(deferHidingClient);
            r.setVisible(false);

            switch (r.getState()) {
                case STOPPING:
                case STOPPED:
                    if (r.attachedToProcess()) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                                "Scheduling invisibility: " + r);
                        mService.getLifecycleManager().scheduleTransaction(r.app.getThread(),
                                r.appToken, WindowVisibilityItem.obtain(false /* showWindow */));
                    }

                    // Reset the flag indicating that an app can enter picture-in-picture once the
                    // activity is hidden
                    r.supportsEnterPipOnTaskSwitch = false;
                    break;

                case INITIALIZING:
                case RESUMED:
                case PAUSING:
                case PAUSED:
                    addToStopping(r, true /* scheduleIdle */,
                            canEnterPictureInPicture /* idleDelayed */, "makeInvisible");
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making hidden: " + r.intent.getComponent(), e);
        }
    }

    private boolean updateBehindFullscreen(boolean stackInvisible, boolean behindFullscreenActivity,
            ActivityRecord r) {
        if (r.fullscreen) {
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Fullscreen: at " + r
                        + " stackInvisible=" + stackInvisible
                        + " behindFullscreenActivity=" + behindFullscreenActivity);
            // At this point, nothing else needs to be shown in this task.
            behindFullscreenActivity = true;
        }
        return behindFullscreenActivity;
    }

    void convertActivityToTranslucent(ActivityRecord r) {
        mTranslucentActivityWaiting = r;
        mUndrawnActivitiesBelowTopTranslucent.clear();
        mHandler.sendEmptyMessageDelayed(TRANSLUCENT_TIMEOUT_MSG, TRANSLUCENT_CONVERSION_TIMEOUT);
    }

    void clearOtherAppTimeTrackers(AppTimeTracker except) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if ( r.appTimeTracker != except) {
                    r.appTimeTracker = null;
                }
            }
        }
    }

    /**
     * Called as activities below the top translucent activity are redrawn. When the last one is
     * redrawn notify the top activity by calling
     * {@link Activity#onTranslucentConversionComplete}.
     *
     * @param r The most recent background activity to be drawn. Or, if r is null then a timeout
     * occurred and the activity will be notified immediately.
     */
    void notifyActivityDrawnLocked(ActivityRecord r) {
        if ((r == null)
                || (mUndrawnActivitiesBelowTopTranslucent.remove(r) &&
                        mUndrawnActivitiesBelowTopTranslucent.isEmpty())) {
            // The last undrawn activity below the top has just been drawn. If there is an
            // opaque activity at the top, notify it that it can become translucent safely now.
            final ActivityRecord waitingActivity = mTranslucentActivityWaiting;
            mTranslucentActivityWaiting = null;
            mUndrawnActivitiesBelowTopTranslucent.clear();
            mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);

            if (waitingActivity != null) {
                mWindowManager.setWindowOpaque(waitingActivity.appToken, false);
                if (waitingActivity.attachedToProcess()) {
                    try {
                        waitingActivity.app.getThread().scheduleTranslucentConversionComplete(
                                waitingActivity.appToken, r != null);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /** If any activities below the top running one are in the INITIALIZING state and they have a
     * starting window displayed then remove that starting window. It is possible that the activity
     * in this state will never resumed in which case that starting window will be orphaned. */
    void cancelInitializingActivities() {
        final ActivityRecord topActivity = topRunningActivityLocked();
        boolean aboveTop = true;
        // We don't want to clear starting window for activities that aren't behind fullscreen
        // activities as we need to display their starting window until they are done initializing.
        boolean behindFullscreenActivity = false;

        if (!shouldBeVisible(null)) {
            // The stack is not visible, so no activity in it should be displaying a starting
            // window. Mark all activities below top and behind fullscreen.
            aboveTop = false;
            behindFullscreenActivity = true;
        }

        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (aboveTop) {
                    if (r == topActivity) {
                        aboveTop = false;
                    }
                    behindFullscreenActivity |= r.fullscreen;
                    continue;
                }

                r.removeOrphanedStartingWindow(behindFullscreenActivity);
                behindFullscreenActivity |= r.fullscreen;
            }
        }
    }

    /**
     * Ensure that the top activity in the stack is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     * @param options Activity options.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     *
     * NOTE: It is not safe to call this method directly as it can cause an activity in a
     *       non-focused stack to be resumed.
     *       Use {@link RootActivityContainer#resumeFocusedStacksTopActivities} to resume the
     *       right activity for the current system state.
     */
    @GuardedBy("mService")
    boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (mInResumeTopActivity) {
            // Don't even start recursing.
            return false;
        }

        boolean result = false;
        try {
            // Protect against recursion.
            mInResumeTopActivity = true;
            result = resumeTopActivityInnerLocked(prev, options);

            // When resuming the top activity, it may be necessary to pause the top activity (for
            // example, returning to the lock screen. We suppress the normal pause logic in
            // {@link #resumeTopActivityUncheckedLocked}, since the top activity is resumed at the
            // end. We call the {@link ActivityStackSupervisor#checkReadyForSleepLocked} again here
            // to ensure any necessary pause logic occurs. In the case where the Activity will be
            // shown regardless of the lock screen, the call to
            // {@link ActivityStackSupervisor#checkReadyForSleepLocked} is skipped.
            final ActivityRecord next = topRunningActivityLocked(true /* focusableOnly */);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
        } finally {
            mInResumeTopActivity = false;
        }

        return result;
    }

    /**
     * Returns the currently resumed activity.
     */
    protected ActivityRecord getResumedActivity() {
        return mResumedActivity;
    }

    private void setResumedActivity(ActivityRecord r, String reason) {
        if (mResumedActivity == r) {
            return;
        }

        if (DEBUG_STACK) Slog.d(TAG_STACK, "setResumedActivity stack:" + this + " + from: "
                + mResumedActivity + " to:" + r + " reason:" + reason);
        mResumedActivity = r;
        mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    @GuardedBy("mService")
    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        if (!mService.isBooting() && !mService.isBooted()) {
            // Not ready yet!
            return false;
        }

        // Find the next top-most activity to resume in this stack that is not finishing and is
        // focusable. If it is not focusable, we will fall into the case below to resume the
        // top activity in the next focusable task.
        ActivityRecord next = topRunningActivityLocked(true /* focusableOnly */);

        final boolean hasRunningActivity = next != null;

        // TODO: Maybe this entire condition can get removed?
        if (hasRunningActivity && !isAttached()) {
            return false;
        }

        mRootActivityContainer.cancelInitializingActivities();

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        boolean userLeaving = mStackSupervisor.mUserLeaving;
        mStackSupervisor.mUserLeaving = false;

        if (!hasRunningActivity) {
            // There are no activities left in the stack, let's look somewhere else.
            return resumeNextFocusableActivityWhenStackIsEmpty(prev, options);
        }

        next.delayedResume = false;
        final ActivityDisplay display = getDisplay();

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.isState(RESUMED)
                && display.allResumedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed " + next);
            return false;
        }

        if (!next.canResumeByCompat()) {
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top
        // activity is paused, well that is the state we want.
        if (shouldSleepOrShutDownActivities()
                && mLastPausedActivity == next
                && mRootActivityContainer.allPausedActivitiesComplete()) {
            // If the current top activity may be able to occlude keyguard but the occluded state
            // has not been set, update visibility and check again if we should continue to resume.
            boolean nothingToResume = true;
            if (!mService.mShuttingDown) {
                final boolean canShowWhenLocked = !mTopActivityOccludesKeyguard
                        && next.canShowWhenLocked();
                final boolean mayDismissKeyguard = mTopDismissingKeyguardActivity != next
                        && next.mAppWindowToken != null
                        && next.mAppWindowToken.containsDismissKeyguardWindow();

                if (canShowWhenLocked || mayDismissKeyguard) {
                    ensureActivitiesVisibleLocked(null /* starting */, 0 /* configChanges */,
                            !PRESERVE_WINDOWS);
                    nothingToResume = shouldSleepActivities();
                }
            }
            if (nothingToResume) {
                // Make sure we have executed any pending transitions, since there
                // should be nothing left to do at this point.
                executeAppTransition(options);
                if (DEBUG_STATES) Slog.d(TAG_STATES,
                        "resumeTopActivityLocked: Going to sleep and all paused");
                return false;
            }
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (!mService.mAmInternal.hasStartedUserState(next.mUserId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.mUserId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStackSupervisor.mStoppingActivities.remove(next);
        mStackSupervisor.mGoingToSleepActivities.remove(next);
        next.sleeping = false;

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything until that is done.
        if (!mRootActivityContainer.allPausedActivitiesComplete()) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "resumeTopActivityLocked: Skip resume: some activity pausing.");

            return false;
        }

        mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        boolean lastResumedCanPip = false;
        ActivityRecord lastResumed = null;
        final ActivityStack lastFocusedStack = display.getLastFocusedStack();
        if (lastFocusedStack != null && lastFocusedStack != this) {
            // So, why aren't we using prev here??? See the param comment on the method. prev doesn't
            // represent the last resumed activity. However, the last focus stack does if it isn't null.
            lastResumed = lastFocusedStack.mResumedActivity;
            if (userLeaving && inMultiWindowMode() && lastFocusedStack.shouldBeVisible(next)) {
                // The user isn't leaving if this stack is the multi-window mode and the last
                // focused stack should still be visible.
                if(DEBUG_USER_LEAVING) Slog.i(TAG_USER_LEAVING, "Overriding userLeaving to false"
                        + " next=" + next + " lastResumed=" + lastResumed);
                userLeaving = false;
            }
            lastResumedCanPip = lastResumed != null && lastResumed.checkEnterPictureInPictureState(
                    "resumeTopActivity", userLeaving /* beforeStopping */);
        }
        // If the flag RESUME_WHILE_PAUSING is set, then continue to schedule the previous activity
        // to be paused, while at the same time resuming the new resume activity only if the
        // previous activity can't go into Pip since we want to give Pip activities a chance to
        // enter Pip before resuming the next activity.
        final boolean resumeWhilePausing = (next.info.flags & FLAG_RESUME_WHILE_PAUSING) != 0
                && !lastResumedCanPip;

        boolean pausing = getDisplay().pauseBackStacks(userLeaving, next, false);
        if (mResumedActivity != null) {
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Pausing " + mResumedActivity);
            pausing |= startPausingLocked(userLeaving, false, next, false);
        }
        if (pausing && !resumeWhilePausing) {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.v(TAG_STATES,
                    "resumeTopActivityLocked: Skip resume: need to start pausing");
            // At this point we want to put the upcoming activity's process
            // at the top of the LRU list, since we know we will be needing it
            // very soon and it would be a waste to let it get killed if it
            // happens to be sitting towards the end.
            if (next.attachedToProcess()) {
                next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                        true /* activityChange */, false /* updateOomAdj */);
            }
            if (lastResumed != null) {
                lastResumed.setWillCloseOrEnterPip(true);
            }
            return true;
        } else if (mResumedActivity == next && next.isState(RESUMED)
                && display.allResumedActivitiesComplete()) {
            // It is possible for the activity to be resumed when we paused back stacks above if the
            // next activity doesn't have to wait for pause to complete.
            // So, nothing else to-do except:
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed (dontWaitForPause) " + next);
            return true;
        }

        // If the most recent activity was noHistory but was only stopped rather
        // than stopped+finished because the device went to sleep, we need to make
        // sure to finish it as we're making a new activity topmost.
        if (shouldSleepActivities() && mLastNoHistoryActivity != null &&
                !mLastNoHistoryActivity.finishing) {
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "no-history finish of " + mLastNoHistoryActivity + " on new resume");
            requestFinishActivityLocked(mLastNoHistoryActivity.appToken, Activity.RESULT_CANCELED,
                    null, "resume-no-history", false);
            mLastNoHistoryActivity = null;
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
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Not waiting for visible to hide: " + prev
                        + ", nowVisible=" + next.nowVisible);
            } else {
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Previous already visible but still waiting to hide: " + prev
                        + ", nowVisible=" + next.nowVisible);
            }

        }

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
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
        final DisplayContent dc = getDisplay().mDisplayContent;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare close transition: prev=" + prev);
                if (mStackSupervisor.mNoAnimActivities.contains(prev)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    dc.prepareAppTransition(
                            prev.getTaskRecord() == next.getTaskRecord() ? TRANSIT_ACTIVITY_CLOSE
                                    : TRANSIT_TASK_CLOSE, false);
                }
                prev.setVisibility(false);
            } else {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare open transition: prev=" + prev);
                if (mStackSupervisor.mNoAnimActivities.contains(next)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    dc.prepareAppTransition(
                            prev.getTaskRecord() == next.getTaskRecord() ? TRANSIT_ACTIVITY_OPEN
                                    : next.mLaunchTaskBehind ? TRANSIT_TASK_OPEN_BEHIND
                                            : TRANSIT_TASK_OPEN, false);
                }
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
            if (mStackSupervisor.mNoAnimActivities.contains(next)) {
                anim = false;
                dc.prepareAppTransition(TRANSIT_NONE, false);
            } else {
                dc.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false);
            }
        }

        if (anim) {
            next.applyOptionsLocked();
        } else {
            next.clearOptionsLocked();
        }

        mStackSupervisor.mNoAnimActivities.clear();

        if (next.attachedToProcess()) {
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resume running: " + next
                    + " stopped=" + next.stopped + " visible=" + next.visible);

            // If the previous activity is translucent, force a visibility update of
            // the next activity, so that it's added to WM's opening app list, and
            // transition animation can be set up properly.
            // For example, pressing Home button with a translucent activity in focus.
            // Launcher is already visible in this case. If we don't add it to opening
            // apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
            // TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
            final boolean lastActivityTranslucent = lastFocusedStack != null
                    && (lastFocusedStack.inMultiWindowMode()
                    || (lastFocusedStack.mLastPausedActivity != null
                    && !lastFocusedStack.mLastPausedActivity.fullscreen));

            // This activity is now becoming visible.
            if (!next.visible || next.stopped || lastActivityTranslucent) {
                next.setVisibility(true);
            }

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity =
                    lastFocusedStack == null ? null : lastFocusedStack.mResumedActivity;
            final ActivityState lastState = next.getState();

            mService.updateCpuStats();

            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + next
                    + " (in existing)");

            next.setState(RESUMED, "resumeTopActivityInnerLocked");

            next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */);
            updateLRUListLocked(next);

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
                notUpdated = !mRootActivityContainer.ensureVisibilityAndConfig(next, mDisplayId,
                        true /* markFrozenIfConfigChanged */, false /* deferResume */);
            }

            if (notUpdated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                ActivityRecord nextNext = topRunningActivityLocked();
                if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_STATES,
                        "Activity config changed during resume: " + next
                                + ", new next: " + nextNext);
                if (nextNext != next) {
                    // Do over!
                    mStackSupervisor.scheduleResumeTopActivities();
                }
                if (!next.visible || next.stopped) {
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
                    final int N = a.size();
                    if (!next.finishing && N > 0) {
                        if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                                "Delivering results to " + next + ": " + a);
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

                EventLog.writeEvent(EventLogTags.AM_RESUME_ACTIVITY, next.mUserId,
                        System.identityHashCode(next), next.getTaskRecord().taskId,
                        next.shortComponentName);

                next.sleeping = false;
                mService.getAppWarningsLocked().onResumeActivity(next);
                next.app.setPendingUiCleanAndForceProcessStateUpTo(mService.mTopProcessState);
                next.clearOptionsLocked();
                transaction.setLifecycleStateRequest(
                        ResumeActivityItem.obtain(next.app.getReportedProcState(),
                                getDisplay().mDisplayContent.isNextTransitionForward()));
                mService.getLifecycleManager().scheduleTransaction(transaction);

                if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Resumed "
                        + next);
            } catch (Exception e) {
                // Whoops, need to restart this activity!
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Resume failed; resetting state to "
                        + lastState + ": " + next);
                next.setState(lastState, "resumeTopActivityInnerLocked");

                // lastResumedActivity being non-null implies there is a lastStack present.
                if (lastResumedActivity != null) {
                    lastResumedActivity.setState(RESUMED, "resumeTopActivityInnerLocked");
                }

                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else  if (SHOW_APP_STARTING_PREVIEW && lastFocusedStack != null
                        && lastFocusedStack.isTopStackOnDisplay()) {
                    next.showStartingWindow(null /* prev */, false /* newTask */,
                            false /* taskSwitch */);
                }
                mStackSupervisor.startSpecificActivityLocked(next, true, false);
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
                requestFinishActivityLocked(next.appToken, Activity.RESULT_CANCELED, null,
                        "resume-exception", true);
                return true;
            }
        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_PREVIEW) {
                    next.showStartingWindow(null /* prev */, false /* newTask */,
                            false /* taskSwich */);
                }
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
            }
            if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Restarting " + next);
            mStackSupervisor.startSpecificActivityLocked(next, true, true);
        }

        return true;
    }

    /**
     * Resume the next eligible activity in a focusable stack when this one does not have any
     * running activities left. The focus will be adjusted to the next focusable stack and
     * top running activities will be resumed in all focusable stacks. However, if the current stack
     * is a home stack - we have to keep it focused, start and resume a home activity on the current
     * display instead to make sure that the display is not empty.
     */
    private boolean resumeNextFocusableActivityWhenStackIsEmpty(ActivityRecord prev,
            ActivityOptions options) {
        final String reason = "noMoreActivities";

        if (!isActivityTypeHome()) {
            final ActivityStack nextFocusedStack = adjustFocusToNextFocusableStack(reason);
            if (nextFocusedStack != null) {
                // Try to move focus to the next visible stack with a running activity if this
                // stack is not covering the entire screen or is on a secondary display with no home
                // stack.
                return mRootActivityContainer.resumeFocusedStacksTopActivities(nextFocusedStack,
                        prev, null /* targetOptions */);
            }
        }

        // If the current stack is a home stack, or if focus didn't switch to a different stack -
        // just start up the Launcher...
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeNextFocusableActivityWhenStackIsEmpty: " + reason + ", go home");
        return mRootActivityContainer.resumeHomeActivity(prev, reason, mDisplayId);
    }

    /** Returns the position the input task should be placed in this stack. */
    int getAdjustedPositionForTask(TaskRecord task, int suggestedPosition,
            ActivityRecord starting) {

        int maxPosition = mTaskHistory.size();
        if ((starting != null && starting.okToShowLocked())
                || (starting == null && task.okToShowLocked())) {
            // If the task or starting activity can be shown, then whatever position is okay.
            return Math.min(suggestedPosition, maxPosition);
        }

        // The task can't be shown, put non-current user tasks below current user tasks.
        while (maxPosition > 0) {
            final TaskRecord tmpTask = mTaskHistory.get(maxPosition - 1);
            if (!mStackSupervisor.isCurrentProfileLocked(tmpTask.userId)
                    || tmpTask.topRunningActivityLocked() == null) {
                break;
            }
            maxPosition--;
        }

        return  Math.min(suggestedPosition, maxPosition);
    }

    /**
     * Used from {@link ActivityStack#positionTask(TaskRecord, int)}.
     * @see ActivityTaskManagerService#positionTaskInStack(int, int, int).
     */
    private void insertTaskAtPosition(TaskRecord task, int position) {
        if (position >= mTaskHistory.size()) {
            insertTaskAtTop(task, null);
            return;
        } else if (position <= 0) {
            insertTaskAtBottom(task);
            return;
        }
        position = getAdjustedPositionForTask(task, position, null /* starting */);
        mTaskHistory.remove(task);
        mTaskHistory.add(position, task);
        if (mTaskStack != null) {
            // TODO: this could not be false after unification.
            mTaskStack.positionChildAt(task.getTask(), position);
        }
        updateTaskMovement(task, true);
    }

    private void insertTaskAtTop(TaskRecord task, ActivityRecord starting) {
        // TODO: Better place to put all the code below...may be addTask...
        mTaskHistory.remove(task);
        // Now put task at top.
        final int position = getAdjustedPositionForTask(task, mTaskHistory.size(), starting);
        mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        positionChildWindowContainerAtTop(task);
    }

    private void insertTaskAtBottom(TaskRecord task) {
        mTaskHistory.remove(task);
        final int position = getAdjustedPositionForTask(task, 0, null);
        mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        positionChildWindowContainerAtBottom(task);
    }

    void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity,
            boolean newTask, boolean keepCurTransition, ActivityOptions options) {
        TaskRecord rTask = r.getTaskRecord();
        final int taskId = rTask.taskId;
        final boolean allowMoveToFront = options == null || !options.getAvoidMoveToFront();
        // mLaunchTaskBehind tasks get placed at the back of the task stack.
        if (!r.mLaunchTaskBehind && allowMoveToFront
                && (taskForIdLocked(taskId) == null || newTask)) {
            // Last activity in task had been removed or ActivityManagerService is reusing task.
            // Insert or replace.
            // Might not even be in.
            insertTaskAtTop(rTask, r);
        }
        TaskRecord task = null;
        if (!newTask) {
            // If starting in an existing task, find where that is...
            boolean startIt = true;
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                task = mTaskHistory.get(taskNdx);
                if (task.getTopActivity() == null) {
                    // All activities in task are finishing.
                    continue;
                }
                if (task == rTask) {
                    // Here it is!  Now, if this is not yet visible to the
                    // user, then just add it without starting; it will
                    // get started when the user navigates back to it.
                    if (!startIt) {
                        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to task "
                                + task, new RuntimeException("here").fillInStackTrace());
                        r.createAppWindowToken();
                        ActivityOptions.abort(options);
                        return;
                    }
                    break;
                } else if (task.numFullscreen > 0) {
                    startIt = false;
                }
            }
        }

        // Place a new activity at top of stack, so it is next to interact with the user.

        // If we are not placing the new activity frontmost, we do not want to deliver the
        // onUserLeaving callback to the actual frontmost activity
        final TaskRecord activityTask = r.getTaskRecord();
        if (task == activityTask && mTaskHistory.indexOf(task) != (mTaskHistory.size() - 1)) {
            mStackSupervisor.mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "startActivity() behind front, mUserLeaving=false");
        }

        task = activityTask;

        // Slot the activity into the history stack and proceed
        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to stack to task " + task,
                new RuntimeException("here").fillInStackTrace());
        // TODO: Need to investigate if it is okay for the controller to already be created by the
        // time we get to this point. I think it is, but need to double check.
        // Use test in b/34179495 to trace the call path.
        if (r.mAppWindowToken == null) {
            r.createAppWindowToken();
        }

        task.setFrontOfTask();

        // The transition animation and starting window are not needed if {@code allowMoveToFront}
        // is false, because the activity won't be visible.
        if ((!isHomeOrRecentsStack() || numActivities() > 0) && allowMoveToFront) {
            final DisplayContent dc = getDisplay().mDisplayContent;
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                    "Prepare open transition: starting " + r);
            if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                dc.prepareAppTransition(TRANSIT_NONE, keepCurTransition);
                mStackSupervisor.mNoAnimActivities.add(r);
            } else {
                int transit = TRANSIT_ACTIVITY_OPEN;
                if (newTask) {
                    if (r.mLaunchTaskBehind) {
                        transit = TRANSIT_TASK_OPEN_BEHIND;
                    } else if (getDisplay().isSingleTaskInstance()) {
                        transit = TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
                    } else {
                        // If a new task is being launched, then mark the existing top activity as
                        // supporting picture-in-picture while pausing only if the starting activity
                        // would not be considered an overlay on top of the current activity
                        // (eg. not fullscreen, or the assistant)
                        if (canEnterPipOnTaskSwitch(focusedTopActivity,
                                null /* toFrontTask */, r, options)) {
                            focusedTopActivity.supportsEnterPipOnTaskSwitch = true;
                        }
                        transit = TRANSIT_TASK_OPEN;
                    }
                }
                dc.prepareAppTransition(transit, keepCurTransition);
                mStackSupervisor.mNoAnimActivities.remove(r);
            }
            boolean doShow = true;
            if (newTask) {
                // Even though this activity is starting fresh, we still need
                // to reset it to make sure we apply affinities to move any
                // existing activities from other tasks in to it.
                // If the caller has requested that the target task be
                // reset, then do so.
                if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    resetTaskIfNeededLocked(r, r);
                    doShow = topRunningNonDelayedActivityLocked(null) == r;
                }
            } else if (options != null && options.getAnimationType()
                    == ActivityOptions.ANIM_SCENE_TRANSITION) {
                doShow = false;
            }
            if (r.mLaunchTaskBehind) {
                // Don't do a starting window for mLaunchTaskBehind. More importantly make sure we
                // tell WindowManager that r is visible even though it is at the back of the stack.
                r.setVisibility(true);
                ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
            } else if (SHOW_APP_STARTING_PREVIEW && doShow) {
                // Figure out if we are transitioning from another activity that is
                // "has the same starting icon" as the next one.  This allows the
                // window manager to keep the previous window it had previously
                // created, if it still had one.
                TaskRecord prevTask = r.getTaskRecord();
                ActivityRecord prev = prevTask.topRunningActivityWithStartingWindowLocked();
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.getTaskRecord() != prevTask) {
                        prev = null;
                    }
                    // (2) The current activity is already displayed.
                    else if (prev.nowVisible) {
                        prev = null;
                    }
                }
                r.showStartingWindow(prev, newTask, isTaskSwitch(r, focusedTopActivity));
            }
        } else {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            ActivityOptions.abort(options);
        }
    }

    /**
     * @return Whether the switch to another task can trigger the currently running activity to
     * enter PiP while it is pausing (if supported). Only one of {@param toFrontTask} or
     * {@param toFrontActivity} should be set.
     */
    private boolean canEnterPipOnTaskSwitch(ActivityRecord pipCandidate,
            TaskRecord toFrontTask, ActivityRecord toFrontActivity, ActivityOptions opts) {
        if (opts != null && opts.disallowEnterPictureInPictureWhileLaunching()) {
            // Ensure the caller has requested not to trigger auto-enter PiP
            return false;
        }
        if (pipCandidate == null || pipCandidate.inPinnedWindowingMode()) {
            // Ensure that we do not trigger entering PiP an activity on the pinned stack
            return false;
        }
        final ActivityStack targetStack = toFrontTask != null
                ? toFrontTask.getStack() : toFrontActivity.getActivityStack();
        if (targetStack != null && targetStack.isActivityTypeAssistant()) {
            // Ensure the task/activity being brought forward is not the assistant
            return false;
        }
        return true;
    }

    private boolean isTaskSwitch(ActivityRecord r,
            ActivityRecord topFocusedActivity) {
        return topFocusedActivity != null && r.getTaskRecord() != topFocusedActivity.getTaskRecord();
    }

    /**
     * Perform a reset of the given task, if needed as part of launching it.
     * Returns the new HistoryRecord at the top of the task.
     */
    /**
     * Helper method for #resetTaskIfNeededLocked.
     * We are inside of the task being reset...  we'll either finish this activity, push it out
     * for another task, or leave it as-is.
     * @param task The task containing the Activity (taskTop) that might be reset.
     * @param forceReset
     * @return An ActivityOptions that needs to be processed.
     */
    private ActivityOptions resetTargetTaskIfNeededLocked(TaskRecord task, boolean forceReset) {
        ActivityOptions topOptions = null;

        int replyChainEnd = -1;
        boolean canMoveOptions = true;

        // We only do this for activities that are not the root of the task (since if we finish
        // the root, we may no longer have the task!).
        final ArrayList<ActivityRecord> activities = task.mActivities;
        final int numActivities = activities.size();
        final int rootActivityNdx = task.findEffectiveRootIndex();
        for (int i = numActivities - 1; i > rootActivityNdx; --i ) {
            ActivityRecord target = activities.get(i);
            if (target.frontOfTask)
                break;

            final int flags = target.info.flags;
            final boolean finishOnTaskLaunch =
                    (flags & ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
            final boolean allowTaskReparenting =
                    (flags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;
            final boolean clearWhenTaskReset =
                    (target.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0;

            if (!finishOnTaskLaunch
                    && !clearWhenTaskReset
                    && target.resultTo != null) {
                // If this activity is sending a reply to a previous
                // activity, we can't do anything with it now until
                // we reach the start of the reply chain.
                // XXX note that we are assuming the result is always
                // to the previous activity, which is almost always
                // the case but we really shouldn't count on.
                if (replyChainEnd < 0) {
                    replyChainEnd = i;
                }
            } else if (!finishOnTaskLaunch
                    && !clearWhenTaskReset
                    && allowTaskReparenting
                    && target.taskAffinity != null
                    && !target.taskAffinity.equals(task.affinity)) {
                // If this activity has an affinity for another
                // task, then we need to move it out of here.  We will
                // move it as far out of the way as possible, to the
                // bottom of the activity stack.  This also keeps it
                // correctly ordered with any activities we previously
                // moved.
                final TaskRecord targetTask;
                final ActivityRecord bottom =
                        !mTaskHistory.isEmpty() && !mTaskHistory.get(0).mActivities.isEmpty() ?
                                mTaskHistory.get(0).mActivities.get(0) : null;
                if (bottom != null && target.taskAffinity != null
                        && target.taskAffinity.equals(bottom.getTaskRecord().affinity)) {
                    // If the activity currently at the bottom has the
                    // same task affinity as the one we are moving,
                    // then merge it into the same task.
                    targetTask = bottom.getTaskRecord();
                    if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Start pushing activity " + target
                            + " out to bottom task " + targetTask);
                } else {
                    targetTask = createTaskRecord(
                            mStackSupervisor.getNextTaskIdForUserLocked(target.mUserId),
                            target.info, null, null, null, false);
                    targetTask.affinityIntent = target.intent;
                    if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Start pushing activity " + target
                            + " out to new task " + targetTask);
                }

                boolean noOptions = canMoveOptions;
                final int start = replyChainEnd < 0 ? i : replyChainEnd;
                for (int srcPos = start; srcPos >= i; --srcPos) {
                    final ActivityRecord p = activities.get(srcPos);
                    if (p.finishing) {
                        continue;
                    }

                    canMoveOptions = false;
                    if (noOptions && topOptions == null) {
                        topOptions = p.takeOptionsLocked(false /* fromClient */);
                        if (topOptions != null) {
                            noOptions = false;
                        }
                    }
                    if (DEBUG_ADD_REMOVE) Slog.i(TAG_ADD_REMOVE,
                            "Removing activity " + p + " from task=" + task + " adding to task="
                            + targetTask + " Callers=" + Debug.getCallers(4));
                    if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                            "Pushing next activity " + p + " out to target's task " + target);
                    p.reparent(targetTask, 0 /* position - bottom */, "resetTargetTaskIfNeeded");
                }

                positionChildWindowContainerAtBottom(targetTask);
                replyChainEnd = -1;
            } else if (forceReset || finishOnTaskLaunch || clearWhenTaskReset) {
                // If the activity should just be removed -- either
                // because it asks for it, or the task should be
                // cleared -- then finish it and anything that is
                // part of its reply chain.
                int end;
                if (clearWhenTaskReset) {
                    // In this case, we want to finish this activity
                    // and everything above it, so be sneaky and pretend
                    // like these are all in the reply chain.
                    end = activities.size() - 1;
                } else if (replyChainEnd < 0) {
                    end = i;
                } else {
                    end = replyChainEnd;
                }
                boolean noOptions = canMoveOptions;
                for (int srcPos = i; srcPos <= end; srcPos++) {
                    ActivityRecord p = activities.get(srcPos);
                    if (p.finishing) {
                        continue;
                    }
                    canMoveOptions = false;
                    if (noOptions && topOptions == null) {
                        topOptions = p.takeOptionsLocked(false /* fromClient */);
                        if (topOptions != null) {
                            noOptions = false;
                        }
                    }
                    if (DEBUG_TASKS) Slog.w(TAG_TASKS,
                            "resetTaskIntendedTask: calling finishActivity on " + p);
                    if (finishActivityLocked(
                            p, Activity.RESULT_CANCELED, null, "reset-task", false)) {
                        end--;
                        srcPos--;
                    }
                }
                replyChainEnd = -1;
            } else {
                // If we were in the middle of a chain, well the
                // activity that started it all doesn't want anything
                // special, so leave it all as-is.
                replyChainEnd = -1;
            }
        }

        return topOptions;
    }

    /**
     * Helper method for #resetTaskIfNeededLocked. Processes all of the activities in a given
     * TaskRecord looking for an affinity with the task of resetTaskIfNeededLocked.taskTop.
     * @param affinityTask The task we are looking for an affinity to.
     * @param task Task that resetTaskIfNeededLocked.taskTop belongs to.
     * @param topTaskIsHigher True if #task has already been processed by resetTaskIfNeededLocked.
     * @param forceReset Flag passed in to resetTaskIfNeededLocked.
     */
    private int resetAffinityTaskIfNeededLocked(TaskRecord affinityTask, TaskRecord task,
            boolean topTaskIsHigher, boolean forceReset, int taskInsertionPoint) {
        int replyChainEnd = -1;
        final int taskId = task.taskId;
        final String taskAffinity = task.affinity;

        final ArrayList<ActivityRecord> activities = affinityTask.mActivities;
        final int numActivities = activities.size();
        final int rootActivityNdx = affinityTask.findEffectiveRootIndex();

        // Do not operate on or below the effective root Activity.
        for (int i = numActivities - 1; i > rootActivityNdx; --i) {
            ActivityRecord target = activities.get(i);
            if (target.frontOfTask)
                break;

            final int flags = target.info.flags;
            boolean finishOnTaskLaunch = (flags & ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
            boolean allowTaskReparenting = (flags & ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;

            if (target.resultTo != null) {
                // If this activity is sending a reply to a previous
                // activity, we can't do anything with it now until
                // we reach the start of the reply chain.
                // XXX note that we are assuming the result is always
                // to the previous activity, which is almost always
                // the case but we really shouldn't count on.
                if (replyChainEnd < 0) {
                    replyChainEnd = i;
                }
            } else if (topTaskIsHigher
                    && allowTaskReparenting
                    && taskAffinity != null
                    && taskAffinity.equals(target.taskAffinity)) {
                // This activity has an affinity for our task. Either remove it if we are
                // clearing or move it over to our task.  Note that
                // we currently punt on the case where we are resetting a
                // task that is not at the top but who has activities above
                // with an affinity to it...  this is really not a normal
                // case, and we will need to later pull that task to the front
                // and usually at that point we will do the reset and pick
                // up those remaining activities.  (This only happens if
                // someone starts an activity in a new task from an activity
                // in a task that is not currently on top.)
                if (forceReset || finishOnTaskLaunch) {
                    final int start = replyChainEnd >= 0 ? replyChainEnd : i;
                    if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                            "Finishing task at index " + start + " to " + i);
                    for (int srcPos = start; srcPos >= i; --srcPos) {
                        final ActivityRecord p = activities.get(srcPos);
                        if (p.finishing) {
                            continue;
                        }
                        finishActivityLocked(
                                p, Activity.RESULT_CANCELED, null, "move-affinity", false);
                    }
                } else {
                    if (taskInsertionPoint < 0) {
                        taskInsertionPoint = task.mActivities.size();

                    }

                    final int start = replyChainEnd >= 0 ? replyChainEnd : i;
                    if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                            "Reparenting from task=" + affinityTask + ":" + start + "-" + i
                            + " to task=" + task + ":" + taskInsertionPoint);
                    for (int srcPos = start; srcPos >= i; --srcPos) {
                        final ActivityRecord p = activities.get(srcPos);
                        p.reparent(task, taskInsertionPoint, "resetAffinityTaskIfNeededLocked");

                        if (DEBUG_ADD_REMOVE) Slog.i(TAG_ADD_REMOVE,
                                "Removing and adding activity " + p + " to stack at " + task
                                + " callers=" + Debug.getCallers(3));
                        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Pulling activity " + p
                                + " from " + srcPos + " in to resetting task " + task);
                    }
                    positionChildWindowContainerAtTop(task);

                    // Now we've moved it in to place...  but what if this is
                    // a singleTop activity and we have put it on top of another
                    // instance of the same activity?  Then we drop the instance
                    // below so it remains singleTop.
                    if (target.info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
                        ArrayList<ActivityRecord> taskActivities = task.mActivities;
                        int targetNdx = taskActivities.indexOf(target);
                        if (targetNdx > 0) {
                            ActivityRecord p = taskActivities.get(targetNdx - 1);
                            if (p.intent.getComponent().equals(target.intent.getComponent())) {
                                finishActivityLocked(p, Activity.RESULT_CANCELED, null, "replace",
                                        false);
                            }
                        }
                    }
                }

                replyChainEnd = -1;
            }
        }
        return taskInsertionPoint;
    }

    final ActivityRecord resetTaskIfNeededLocked(ActivityRecord taskTop,
            ActivityRecord newActivity) {
        final boolean forceReset =
                (newActivity.info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        final TaskRecord task = taskTop.getTaskRecord();

        /** False until we evaluate the TaskRecord associated with taskTop. Switches to true
         * for remaining tasks. Used for later tasks to reparent to task. */
        boolean taskFound = false;

        /** If ActivityOptions are moved out and need to be aborted or moved to taskTop. */
        ActivityOptions topOptions = null;

        // Preserve the location for reparenting in the new task.
        int reparentInsertionPoint = -1;

        for (int i = mTaskHistory.size() - 1; i >= 0; --i) {
            final TaskRecord targetTask = mTaskHistory.get(i);

            if (targetTask == task) {
                topOptions = resetTargetTaskIfNeededLocked(task, forceReset);
                taskFound = true;
            } else {
                reparentInsertionPoint = resetAffinityTaskIfNeededLocked(targetTask, task,
                        taskFound, forceReset, reparentInsertionPoint);
            }
        }

        int taskNdx = mTaskHistory.indexOf(task);
        if (taskNdx >= 0) {
            do {
                taskTop = mTaskHistory.get(taskNdx--).getTopActivity();
            } while (taskTop == null && taskNdx >= 0);
        }

        if (topOptions != null) {
            // If we got some ActivityOptions from an activity on top that
            // was removed from the task, propagate them to the new real top.
            if (taskTop != null) {
                taskTop.updateOptionsLocked(topOptions);
            } else {
                topOptions.abort();
            }
        }

        return taskTop;
    }

    void sendActivityResultLocked(int callingUid, ActivityRecord r,
            String resultWho, int requestCode, int resultCode, Intent data) {

        if (callingUid > 0) {
            mService.mUgmInternal.grantUriPermissionFromIntent(callingUid, r.packageName,
                    data, r.getUriPermissionsLocked(), r.mUserId);
        }

        if (DEBUG_RESULTS) Slog.v(TAG, "Send activity result to " + r
                + " : who=" + resultWho + " req=" + requestCode
                + " res=" + resultCode + " data=" + data);
        if (mResumedActivity == r && r.attachedToProcess()) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
                list.add(new ResultInfo(resultWho, requestCode,
                        resultCode, data));
                mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), r.appToken,
                        ActivityResultItem.obtain(list));
                return;
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending result to " + r, e);
            }
        }

        r.addResultLocked(null, resultWho, requestCode, resultCode, data);
    }

    /** Returns true if the task is one of the task finishing on-top of the top running task. */
    private boolean isATopFinishingTask(TaskRecord task) {
        for (int i = mTaskHistory.size() - 1; i >= 0; --i) {
            final TaskRecord current = mTaskHistory.get(i);
            final ActivityRecord r = current.topRunningActivityLocked();
            if (r != null) {
                // We got a top running activity, so there isn't a top finishing task...
                return false;
            }
            if (current == task) {
                return true;
            }
        }
        return false;
    }

    private void adjustFocusedActivityStack(ActivityRecord r, String reason) {
        if (!mRootActivityContainer.isTopDisplayFocusedStack(this) ||
                ((mResumedActivity != r) && (mResumedActivity != null))) {
            return;
        }

        final ActivityRecord next = topRunningActivityLocked();
        final String myReason = reason + " adjustFocus";

        if (next == r) {
            final ActivityRecord top = mRootActivityContainer.topRunningActivity();
            if (top != null) {
                top.moveFocusableActivityToTop(myReason);
            }
            return;
        }

        if (next != null && isFocusable()) {
            // Keep focus in stack if we have a top running activity and are focusable.
            return;
        }

        // Task is not guaranteed to be non-null. For example, destroying the
        // {@link ActivityRecord} will disassociate the task from the activity.
        final TaskRecord task = r.getTaskRecord();

        if (task == null) {
            throw new IllegalStateException("activity no longer associated with task:" + r);
        }

        // Move focus to next focusable stack if possible.
        final ActivityStack nextFocusableStack = adjustFocusToNextFocusableStack(myReason);
        if (nextFocusableStack != null) {
            final ActivityRecord top = nextFocusableStack.topRunningActivityLocked();
            if (top != null && top == mRootActivityContainer.getTopResumedActivity()) {
                // TODO(b/111361570): Remove this and update focused app per-display in
                // WindowManager every time an activity becomes resumed in
                // ActivityTaskManagerService#setResumedActivityUncheckLocked().
                mService.setResumedActivityUncheckLocked(top, reason);
            }
            return;
        }

        // Whatever...go home.
        getDisplay().moveHomeActivityToTop(myReason);
    }

    /**
     * Find next proper focusable stack and make it focused.
     * @return The stack that now got the focus, {@code null} if none found.
     */
    ActivityStack adjustFocusToNextFocusableStack(String reason) {
        return adjustFocusToNextFocusableStack(reason, false /* allowFocusSelf */);
    }

    /**
     * Find next proper focusable stack and make it focused.
     * @param allowFocusSelf Is the focus allowed to remain on the same stack.
     * @return The stack that now got the focus, {@code null} if none found.
     */
    private ActivityStack adjustFocusToNextFocusableStack(String reason, boolean allowFocusSelf) {
        final ActivityStack stack =
                mRootActivityContainer.getNextFocusableStack(this, !allowFocusSelf);
        final String myReason = reason + " adjustFocusToNextFocusableStack";
        if (stack == null) {
            return null;
        }

        final ActivityRecord top = stack.topRunningActivityLocked();

        if (stack.isActivityTypeHome() && (top == null || !top.visible)) {
            // If we will be focusing on the home stack next and its current top activity isn't
            // visible, then use the move the home stack task to top to make the activity visible.
            stack.getDisplay().moveHomeActivityToTop(reason);
            return stack;
        }

        stack.moveToFront(myReason);
        return stack;
    }

    final void stopActivityLocked(ActivityRecord r) {
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH, "Stopping: " + r);
        if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (r.info.flags&ActivityInfo.FLAG_NO_HISTORY) != 0) {
            if (!r.finishing) {
                if (!shouldSleepActivities()) {
                    if (DEBUG_STATES) Slog.d(TAG_STATES, "no-history finish of " + r);
                    if (requestFinishActivityLocked(r.appToken, Activity.RESULT_CANCELED, null,
                            "stop-no-history", false)) {
                        // If {@link requestFinishActivityLocked} returns {@code true},
                        // {@link adjustFocusedActivityStack} would have been already called.
                        r.resumeKeyDispatchingLocked();
                        return;
                    }
                } else {
                    if (DEBUG_STATES) Slog.d(TAG_STATES, "Not finishing noHistory " + r
                            + " on stop because we're just sleeping");
                }
            }
        }

        if (r.attachedToProcess()) {
            adjustFocusedActivityStack(r, "stopActivity");
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                if (DEBUG_STATES) Slog.v(TAG_STATES,
                        "Moving to STOPPING: " + r + " (stop requested)");
                r.setState(STOPPING, "stopActivityLocked");
                if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                        "Stopping visible=" + r.visible + " for " + r);
                if (!r.visible) {
                    r.setVisible(false);
                }
                EventLogTags.writeAmStopActivity(
                        r.mUserId, System.identityHashCode(r), r.shortComponentName);
                mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), r.appToken,
                        StopActivityItem.obtain(r.visible, r.configChangeFlags));
                if (shouldSleepOrShutDownActivities()) {
                    r.setSleeping(true);
                }
                Message msg = mHandler.obtainMessage(STOP_TIMEOUT_MSG, r);
                mHandler.sendMessageDelayed(msg, STOP_TIMEOUT);
            } catch (Exception e) {
                // Maybe just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                Slog.w(TAG, "Exception thrown during pause", e);
                // Just in case, assume it to be stopped.
                r.stopped = true;
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Stop failed; moving to STOPPED: " + r);
                r.setState(STOPPED, "stopActivityLocked");
                if (r.deferRelaunchUntilPaused) {
                    destroyActivityLocked(r, true, "stop-except");
                }
            }
        }
    }

    /**
     * @return Returns true if the activity is being finished, false if for
     * some reason it is being left as-is.
     */
    final boolean requestFinishActivityLocked(IBinder token, int resultCode,
            Intent resultData, String reason, boolean oomAdj) {
        ActivityRecord r = isInStackLocked(token);
        if (DEBUG_RESULTS || DEBUG_STATES) Slog.v(TAG_STATES,
                "Finishing activity token=" + token + " r="
                + ", result=" + resultCode + ", data=" + resultData
                + ", reason=" + reason);
        if (r == null) {
            return false;
        }

        finishActivityLocked(r, resultCode, resultData, reason, oomAdj);
        return true;
    }

    final void finishSubActivityLocked(ActivityRecord self, String resultWho, int requestCode) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.resultTo == self && r.requestCode == requestCode) {
                    if ((r.resultWho == null && resultWho == null) ||
                        (r.resultWho != null && r.resultWho.equals(resultWho))) {
                        finishActivityLocked(r, Activity.RESULT_CANCELED, null, "request-sub",
                                false);
                    }
                }
            }
        }
        mService.updateOomAdj();
    }

    /**
     * Finish the topmost activity that belongs to the crashed app. We may also finish the activity
     * that requested launch of the crashed one to prevent launch-crash loop.
     * @param app The app that crashed.
     * @param reason Reason to perform this action.
     * @return The task that was finished in this stack, {@code null} if top running activity does
     *         not belong to the crashed app.
     */
    final TaskRecord finishTopCrashedActivityLocked(WindowProcessController app, String reason) {
        ActivityRecord r = topRunningActivityLocked();
        TaskRecord finishedTask = null;
        if (r == null || r.app != app) {
            return null;
        }
        Slog.w(TAG, "  Force finishing activity "
                + r.intent.getComponent().flattenToShortString());
        finishedTask = r.getTaskRecord();
        int taskNdx = mTaskHistory.indexOf(finishedTask);
        final TaskRecord task = finishedTask;
        int activityNdx = task.mActivities.indexOf(r);
        getDisplay().mDisplayContent.prepareAppTransition(
                TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        finishActivityLocked(r, Activity.RESULT_CANCELED, null, reason, false);
        finishedTask = task;
        // Also terminate any activities below it that aren't yet
        // stopped, to avoid a situation where one will get
        // re-start our crashing activity once it gets resumed again.
        --activityNdx;
        if (activityNdx < 0) {
            do {
                --taskNdx;
                if (taskNdx < 0) {
                    break;
                }
                activityNdx = mTaskHistory.get(taskNdx).mActivities.size() - 1;
            } while (activityNdx < 0);
        }
        if (activityNdx >= 0) {
            r = mTaskHistory.get(taskNdx).mActivities.get(activityNdx);
            if (r.isState(RESUMED, PAUSING, PAUSED)) {
                if (!r.isActivityTypeHome() || mService.mHomeProcess != r.app) {
                    Slog.w(TAG, "  Force finishing activity "
                            + r.intent.getComponent().flattenToShortString());
                    finishActivityLocked(r, Activity.RESULT_CANCELED, null, reason, false);
                }
            }
        }
        return finishedTask;
    }

    final void finishVoiceTask(IVoiceInteractionSession session) {
        IBinder sessionBinder = session.asBinder();
        boolean didOne = false;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            TaskRecord tr = mTaskHistory.get(taskNdx);
            if (tr.voiceSession != null && tr.voiceSession.asBinder() == sessionBinder) {
                for (int activityNdx = tr.mActivities.size() - 1; activityNdx >= 0; --activityNdx) {
                    ActivityRecord r = tr.mActivities.get(activityNdx);
                    if (!r.finishing) {
                        finishActivityLocked(r, Activity.RESULT_CANCELED, null, "finish-voice",
                                false);
                        didOne = true;
                    }
                }
            } else {
                // Check if any of the activities are using voice
                for (int activityNdx = tr.mActivities.size() - 1; activityNdx >= 0; --activityNdx) {
                    ActivityRecord r = tr.mActivities.get(activityNdx);
                    if (r.voiceSession != null && r.voiceSession.asBinder() == sessionBinder) {
                        // Inform of cancellation
                        r.clearVoiceSessionLocked();
                        try {
                            r.app.getThread().scheduleLocalVoiceInteractionStarted(
                                    r.appToken, null);
                        } catch (RemoteException re) {
                            // Ok
                        }
                        mService.finishRunningVoiceLocked();
                        break;
                    }
                }
            }
        }

        if (didOne) {
            mService.updateOomAdj();
        }
    }

    final boolean finishActivityAffinityLocked(ActivityRecord r) {
        ArrayList<ActivityRecord> activities = r.getTaskRecord().mActivities;
        for (int index = activities.indexOf(r); index >= 0; --index) {
            ActivityRecord cur = activities.get(index);
            if (!Objects.equals(cur.taskAffinity, r.taskAffinity)) {
                break;
            }
            finishActivityLocked(cur, Activity.RESULT_CANCELED, null, "request-affinity", true);
        }
        return true;
    }

    private void finishActivityResultsLocked(ActivityRecord r, int resultCode, Intent resultData) {
        // send the result
        ActivityRecord resultTo = r.resultTo;
        if (resultTo != null) {
            if (DEBUG_RESULTS) Slog.v(TAG_RESULTS, "Adding result to " + resultTo
                    + " who=" + r.resultWho + " req=" + r.requestCode
                    + " res=" + resultCode + " data=" + resultData);
            if (resultTo.mUserId != r.mUserId) {
                if (resultData != null) {
                    resultData.prepareToLeaveUser(r.mUserId);
                }
            }
            if (r.info.applicationInfo.uid > 0) {
                mService.mUgmInternal.grantUriPermissionFromIntent(r.info.applicationInfo.uid,
                        resultTo.packageName, resultData,
                        resultTo.getUriPermissionsLocked(), resultTo.mUserId);
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode, resultData);
            r.resultTo = null;
        }
        else if (DEBUG_RESULTS) Slog.v(TAG_RESULTS, "No result destination from " + r);

        // Make sure this HistoryRecord is not holding on to other resources,
        // because clients have remote IPC references to this object so we
        // can't assume that will go away and want to avoid circular IPC refs.
        r.results = null;
        r.pendingResults = null;
        r.newIntents = null;
        r.icicle = null;
    }

    /**
     * See {@link #finishActivityLocked(ActivityRecord, int, Intent, String, boolean, boolean)}
     */
    final boolean finishActivityLocked(ActivityRecord r, int resultCode, Intent resultData,
            String reason, boolean oomAdj) {
        return finishActivityLocked(r, resultCode, resultData, reason, oomAdj, !PAUSE_IMMEDIATELY);
    }

    /**
     * @return Returns true if this activity has been removed from the history
     * list, or false if it is still in the list and will be removed later.
     */
    final boolean finishActivityLocked(ActivityRecord r, int resultCode, Intent resultData,
            String reason, boolean oomAdj, boolean pauseImmediately) {
        if (r.finishing) {
            Slog.w(TAG, "Duplicate finish request for " + r);
            return false;
        }

        mWindowManager.deferSurfaceLayout();
        try {
            r.makeFinishingLocked();
            final TaskRecord task = r.getTaskRecord();
            EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                    r.mUserId, System.identityHashCode(r),
                    task.taskId, r.shortComponentName, reason);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            final int index = activities.indexOf(r);
            if (index < (activities.size() - 1)) {
                task.setFrontOfTask();
                if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                    // If the caller asked that this activity (and all above it)
                    // be cleared when the task is reset, don't lose that information,
                    // but propagate it up to the next activity.
                    ActivityRecord next = activities.get(index+1);
                    next.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                }
            }

            r.pauseKeyDispatchingLocked();

            adjustFocusedActivityStack(r, "finishActivity");

            finishActivityResultsLocked(r, resultCode, resultData);

            final boolean endTask = index <= 0 && !task.isClearingToReuseTask();
            final int transit = endTask ? TRANSIT_TASK_CLOSE : TRANSIT_ACTIVITY_CLOSE;
            if (mResumedActivity == r) {
                if (DEBUG_VISIBILITY || DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare close transition: finishing " + r);
                if (endTask) {
                    mService.getTaskChangeNotificationController().notifyTaskRemovalStarted(
                            task.getTaskInfo());
                }
                getDisplay().mDisplayContent.prepareAppTransition(transit, false);

                // When finishing the activity pre-emptively take the snapshot before the app window
                // is marked as hidden and any configuration changes take place
                if (mWindowManager.mTaskSnapshotController != null) {
                    final ArraySet<Task> tasks = Sets.newArraySet(task.mTask);
                    mWindowManager.mTaskSnapshotController.snapshotTasks(tasks);
                    mWindowManager.mTaskSnapshotController.addSkipClosingAppSnapshotTasks(tasks);
                }

                // Tell window manager to prepare for this one to be removed.
                r.setVisibility(false);

                if (mPausingActivity == null) {
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish needs to pause: " + r);
                    if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                            "finish() => pause with userLeaving=false");
                    startPausingLocked(false, false, null, pauseImmediately);
                }

                if (endTask) {
                    mService.getLockTaskController().clearLockedTask(task);
                }
            } else if (!r.isState(PAUSING)) {
                // If the activity is PAUSING, we will complete the finish once
                // it is done pausing; else we can just directly finish it here.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish not pausing: " + r);
                if (r.visible) {
                    prepareActivityHideTransitionAnimation(r, transit);
                }

                final int finishMode = (r.visible || r.nowVisible) ? FINISH_AFTER_VISIBLE
                        : FINISH_AFTER_PAUSE;
                final boolean removedActivity = finishCurrentActivityLocked(r, finishMode, oomAdj,
                        "finishActivityLocked") == null;

                // The following code is an optimization. When the last non-task overlay activity
                // is removed from the task, we remove the entire task from the stack. However,
                // since that is done after the scheduled destroy callback from the activity, that
                // call to change the visibility of the task overlay activities would be out of
                // sync with the activitiy visibility being set for this finishing activity above.
                // In this case, we can set the visibility of all the task overlay activities when
                // we detect the last one is finishing to keep them in sync.
                if (task.onlyHasTaskOverlayActivities(true /* excludeFinishing */)) {
                    for (ActivityRecord taskOverlay : task.mActivities) {
                        if (!taskOverlay.mTaskOverlay) {
                            continue;
                        }
                        prepareActivityHideTransitionAnimation(taskOverlay, transit);
                    }
                }
                return removedActivity;
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish waiting for pause of: " + r);
            }

            return false;
        } finally {
            mWindowManager.continueSurfaceLayout();
        }
    }

    private void prepareActivityHideTransitionAnimation(ActivityRecord r, int transit) {
        final DisplayContent dc = getDisplay().mDisplayContent;
        dc.prepareAppTransition(transit, false);
        r.setVisibility(false);
        dc.executeAppTransition();
    }

    static final int FINISH_IMMEDIATELY = 0;
    static final int FINISH_AFTER_PAUSE = 1;
    static final int FINISH_AFTER_VISIBLE = 2;

    final ActivityRecord finishCurrentActivityLocked(ActivityRecord r, int mode, boolean oomAdj,
            String reason) {
        // First things first: if this activity is currently visible,
        // and the resumed activity is not yet visible, then hold off on
        // finishing until the resumed one becomes visible.

        // The activity that we are finishing may be over the lock screen. In this case, we do not
        // want to consider activities that cannot be shown on the lock screen as running and should
        // proceed with finishing the activity if there is no valid next top running activity.
        // Note that if this finishing activity is floating task, we don't need to wait the
        // next activity resume and can destroy it directly.
        final ActivityDisplay display = getDisplay();
        final ActivityRecord next = display.topRunningActivity(true /* considerKeyguardState */);
        final boolean isFloating = r.getConfiguration().windowConfiguration.tasksAreFloating();

        if (mode == FINISH_AFTER_VISIBLE && (r.visible || r.nowVisible)
                && next != null && !next.nowVisible && !isFloating) {
            if (!mStackSupervisor.mStoppingActivities.contains(r)) {
                addToStopping(r, false /* scheduleIdle */, false /* idleDelayed */,
                        "finishCurrentActivityLocked");
            }
            if (DEBUG_STATES) Slog.v(TAG_STATES,
                    "Moving to STOPPING: "+ r + " (finish requested)");
            r.setState(STOPPING, "finishCurrentActivityLocked");
            if (oomAdj) {
                mService.updateOomAdj();
            }
            return r;
        }

        // make sure the record is cleaned out of other places.
        mStackSupervisor.mStoppingActivities.remove(r);
        mStackSupervisor.mGoingToSleepActivities.remove(r);
        final ActivityState prevState = r.getState();
        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to FINISHING: " + r);

        r.setState(FINISHING, "finishCurrentActivityLocked");

        // Don't destroy activity immediately if the display contains home stack, although there is
        // no next activity at the moment but another home activity should be started later. Keep
        // this activity alive until next home activity is resumed then user won't see a temporary
        // black screen.
        final boolean noRunningStack = next == null && display.topRunningActivity() == null
                && display.getHomeStack() == null;
        final boolean noFocusedStack = r.getActivityStack() != display.getFocusedStack();
        final boolean finishingInNonFocusedStackOrNoRunning = mode == FINISH_AFTER_VISIBLE
                && prevState == PAUSED && (noFocusedStack || noRunningStack);

        if (mode == FINISH_IMMEDIATELY
                || (prevState == PAUSED
                    && (mode == FINISH_AFTER_PAUSE || inPinnedWindowingMode()))
                || finishingInNonFocusedStackOrNoRunning
                || prevState == STOPPING
                || prevState == STOPPED
                || prevState == ActivityState.INITIALIZING) {
            r.makeFinishingLocked();
            boolean activityRemoved = destroyActivityLocked(r, true, "finish-imm:" + reason);

            if (finishingInNonFocusedStackOrNoRunning) {
                // Finishing activity that was in paused state and it was in not currently focused
                // stack, need to make something visible in its place. Also if the display does not
                // have running activity, the configuration may need to be updated for restoring
                // original orientation of the display.
                mRootActivityContainer.ensureVisibilityAndConfig(next, mDisplayId,
                        false /* markFrozenIfConfigChanged */, true /* deferResume */);
            }
            if (activityRemoved) {
                mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
            if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS,
                    "destroyActivityLocked: finishCurrentActivityLocked r=" + r +
                    " destroy returned removed=" + activityRemoved);
            return activityRemoved ? null : r;
        }

        // Need to go through the full pause cycle to get this
        // activity into the stopped state and then finish it.
        if (DEBUG_ALL) Slog.v(TAG, "Enqueueing pending finish: " + r);
        mStackSupervisor.mFinishingActivities.add(r);
        r.resumeKeyDispatchingLocked();
        mRootActivityContainer.resumeFocusedStacksTopActivities();
        // If activity was not paused at this point - explicitly pause it to start finishing
        // process. Finishing will be completed once it reports pause back.
        if (r.isState(RESUMED) && mPausingActivity != null) {
            startPausingLocked(false /* userLeaving */, false /* uiSleeping */, next /* resuming */,
                    false /* dontWait */);
        }
        return r;
    }

    void finishAllActivitiesLocked(boolean immediately) {
        boolean noActivitiesInStack = true;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                noActivitiesInStack = false;
                if (r.finishing && !immediately) {
                    continue;
                }
                Slog.d(TAG, "finishAllActivitiesLocked: finishing " + r + " immediately");
                finishCurrentActivityLocked(r, FINISH_IMMEDIATELY, false,
                        "finishAllActivitiesLocked");
            }
        }
        if (noActivitiesInStack) {
            remove();
        }
    }

    /** @return true if the stack behind this one is a standard activity type. */
    boolean inFrontOfStandardStack() {
        final ActivityDisplay display = getDisplay();
        if (display == null) {
            return false;
        }
        final int index = display.getIndexOf(this);
        if (index == 0) {
            return false;
        }
        final ActivityStack stackBehind = display.getChildAt(index - 1);
        return stackBehind.isActivityTypeStandard();
    }

    boolean shouldUpRecreateTaskLocked(ActivityRecord srec, String destAffinity) {
        // Basic case: for simple app-centric recents, we need to recreate
        // the task if the affinity has changed.
        if (srec == null || srec.getTaskRecord().affinity == null ||
                !srec.getTaskRecord().affinity.equals(destAffinity)) {
            return true;
        }
        // Document-centric case: an app may be split in to multiple documents;
        // they need to re-create their task if this current activity is the root
        // of a document, unless simply finishing it will return them to the the
        // correct app behind.
        final TaskRecord task = srec.getTaskRecord();
        if (srec.frontOfTask && task.getBaseIntent() != null && task.getBaseIntent().isDocument()) {
            // Okay, this activity is at the root of its task.  What to do, what to do...
            if (!inFrontOfStandardStack()) {
                // Finishing won't return to an application, so we need to recreate.
                return true;
            }
            // We now need to get the task below it to determine what to do.
            int taskIdx = mTaskHistory.indexOf(task);
            if (taskIdx <= 0) {
                Slog.w(TAG, "shouldUpRecreateTask: task not in history for " + srec);
                return false;
            }
            final TaskRecord prevTask = mTaskHistory.get(taskIdx);
            if (!task.affinity.equals(prevTask.affinity)) {
                // These are different apps, so need to recreate.
                return true;
            }
        }
        return false;
    }

    final boolean navigateUpToLocked(ActivityRecord srec, Intent destIntent, int resultCode,
            Intent resultData) {
        final TaskRecord task = srec.getTaskRecord();
        final ArrayList<ActivityRecord> activities = task.mActivities;
        final int start = activities.indexOf(srec);
        if (!mTaskHistory.contains(task) || (start < 0)) {
            return false;
        }
        int finishTo = start - 1;
        ActivityRecord parent = finishTo < 0 ? null : activities.get(finishTo);
        boolean foundParentInTask = false;
        final ComponentName dest = destIntent.getComponent();
        if (start > 0 && dest != null) {
            for (int i = finishTo; i >= 0; i--) {
                ActivityRecord r = activities.get(i);
                if (r.info.packageName.equals(dest.getPackageName()) &&
                        r.info.name.equals(dest.getClassName())) {
                    finishTo = i;
                    parent = r;
                    foundParentInTask = true;
                    break;
                }
            }
        }

        // TODO: There is a dup. of this block of code in ActivityTaskManagerService.finishActivity
        // We should consolidate.
        IActivityController controller = mService.mController;
        if (controller != null) {
            ActivityRecord next = topRunningActivityLocked(srec.appToken, 0);
            if (next != null) {
                // ask watcher if this is allowed
                boolean resumeOK = true;
                try {
                    resumeOK = controller.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }

                if (!resumeOK) {
                    return false;
                }
            }
        }
        final long origId = Binder.clearCallingIdentity();
        for (int i = start; i > finishTo; i--) {
            ActivityRecord r = activities.get(i);
            requestFinishActivityLocked(r.appToken, resultCode, resultData, "navigate-up", true);
            // Only return the supplied result for the first activity finished
            resultCode = Activity.RESULT_CANCELED;
            resultData = null;
        }

        if (parent != null && foundParentInTask) {
            final int parentLaunchMode = parent.info.launchMode;
            final int destIntentFlags = destIntent.getFlags();
            if (parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TOP ||
                    (destIntentFlags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                parent.deliverNewIntentLocked(srec.info.applicationInfo.uid, destIntent,
                        srec.packageName);
            } else {
                try {
                    ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(
                            destIntent.getComponent(), ActivityManagerService.STOCK_PM_FLAGS,
                            srec.mUserId);
                    // TODO(b/64750076): Check if calling pid should really be -1.
                    final int res = mService.getActivityStartController()
                            .obtainStarter(destIntent, "navigateUpTo")
                            .setCaller(srec.app.getThread())
                            .setActivityInfo(aInfo)
                            .setResultTo(parent.appToken)
                            .setCallingPid(-1)
                            .setCallingUid(parent.launchedFromUid)
                            .setCallingPackage(parent.launchedFromPackage)
                            .setRealCallingPid(-1)
                            .setRealCallingUid(parent.launchedFromUid)
                            .setComponentSpecified(true)
                            .execute();
                    foundParentInTask = res == ActivityManager.START_SUCCESS;
                } catch (RemoteException e) {
                    foundParentInTask = false;
                }
                requestFinishActivityLocked(parent.appToken, resultCode,
                        resultData, "navigate-top", true);
            }
        }
        Binder.restoreCallingIdentity(origId);
        return foundParentInTask;
    }

    /**
     * Remove any state associated with the {@link ActivityRecord}. This should be called whenever
     * an activity moves away from the stack.
     */
    void onActivityRemovedFromStack(ActivityRecord r) {
        removeTimeoutsForActivityLocked(r);

        if (mResumedActivity != null && mResumedActivity == r) {
            setResumedActivity(null, "onActivityRemovedFromStack");
        }
        if (mPausingActivity != null && mPausingActivity == r) {
            mPausingActivity = null;
        }
    }

    void onActivityAddedToStack(ActivityRecord r) {
        if(r.getState() == RESUMED) {
            setResumedActivity(r, "onActivityAddedToStack");
        }
    }

    /**
     * Perform the common clean-up of an activity record.  This is called both
     * as part of destroyActivityLocked() (when destroying the client-side
     * representation) and cleaning things up as a result of its hosting
     * processing going away, in which case there is no remaining client-side
     * state to destroy so only the cleanup here is needed.
     *
     * Note: Call before #removeActivityFromHistoryLocked.
     */
    private void cleanUpActivityLocked(ActivityRecord r, boolean cleanServices, boolean setState) {
        onActivityRemovedFromStack(r);

        r.deferRelaunchUntilPaused = false;
        r.frozenBeforeDestroy = false;

        if (setState) {
            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to DESTROYED: " + r + " (cleaning up)");
            r.setState(DESTROYED, "cleanupActivityLocked");
            if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during cleanUp for activity " + r);
            r.app = null;
        }

        // Inform supervisor the activity has been removed.
        mStackSupervisor.cleanupActivity(r);


        // Remove any pending results.
        if (r.finishing && r.pendingResults != null) {
            for (WeakReference<PendingIntentRecord> apr : r.pendingResults) {
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    mService.mPendingIntentController.cancelIntentSender(rec, false);
                }
            }
            r.pendingResults = null;
        }

        if (cleanServices) {
            cleanUpActivityServicesLocked(r);
        }

        // Get rid of any pending idle timeouts.
        removeTimeoutsForActivityLocked(r);
        // Clean-up activities are no longer relaunching (e.g. app process died). Notify window
        // manager so it can update its bookkeeping.
        mWindowManager.notifyAppRelaunchesCleared(r.appToken);
    }

    private void removeTimeoutsForActivityLocked(ActivityRecord r) {
        mStackSupervisor.removeTimeoutsForActivityLocked(r);
        mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
        mHandler.removeMessages(STOP_TIMEOUT_MSG, r);
        mHandler.removeMessages(DESTROY_TIMEOUT_MSG, r);
        r.finishLaunchTickingLocked();
    }

    private void removeActivityFromHistoryLocked(ActivityRecord r, String reason) {
        finishActivityResultsLocked(r, Activity.RESULT_CANCELED, null);
        r.makeFinishingLocked();
        if (DEBUG_ADD_REMOVE) Slog.i(TAG_ADD_REMOVE,
                "Removing activity " + r + " from stack callers=" + Debug.getCallers(5));

        r.takeFromHistory();
        removeTimeoutsForActivityLocked(r);
        if (DEBUG_STATES) Slog.v(TAG_STATES,
                "Moving to DESTROYED: " + r + " (removed from history)");
        r.setState(DESTROYED, "removeActivityFromHistoryLocked");
        if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during remove for activity " + r);
        r.app = null;
        r.removeWindowContainer();
        final TaskRecord task = r.getTaskRecord();
        final boolean lastActivity = task != null ? task.removeActivity(r) : false;
        // If we are removing the last activity in the task, not including task overlay activities,
        // then fall through into the block below to remove the entire task itself
        final boolean onlyHasTaskOverlays = task != null
                ? task.onlyHasTaskOverlayActivities(false /* excludingFinishing */) : false;

        if (lastActivity || onlyHasTaskOverlays) {
            if (DEBUG_STACK) {
                Slog.i(TAG_STACK,
                        "removeActivityFromHistoryLocked: last activity removed from " + this
                                + " onlyHasTaskOverlays=" + onlyHasTaskOverlays);
            }

            // The following block can be executed multiple times if there is more than one overlay.
            // {@link ActivityStackSupervisor#removeTaskByIdLocked} handles this by reverse lookup
            // of the task by id and exiting early if not found.
            if (onlyHasTaskOverlays) {
                // When destroying a task, tell the supervisor to remove it so that any activity it
                // has can be cleaned up correctly. This is currently the only place where we remove
                // a task with the DESTROYING mode, so instead of passing the onlyHasTaskOverlays
                // state into removeTask(), we just clear the task here before the other residual
                // work.
                // TODO: If the callers to removeTask() changes such that we have multiple places
                //       where we are destroying the task, move this back into removeTask()
                mStackSupervisor.removeTaskByIdLocked(task.taskId, false /* killProcess */,
                        !REMOVE_FROM_RECENTS, PAUSE_IMMEDIATELY, reason);
            }

            // We must keep the task around until all activities are destroyed. The following
            // statement will only execute once since overlays are also considered activities.
            if (lastActivity) {
                removeTask(task, reason, REMOVE_TASK_MODE_DESTROYING);
            }
        }
        cleanUpActivityServicesLocked(r);
        r.removeUriPermissionsLocked();
    }

    /**
     * Perform clean-up of service connections in an activity record.
     */
    private void cleanUpActivityServicesLocked(ActivityRecord r) {
        if (r.mServiceConnectionsHolder == null) {
            return;
        }
        // Throw away any services that have been bound by this activity.
        r.mServiceConnectionsHolder.disconnectActivityFromServices();
    }

    final void scheduleDestroyActivities(WindowProcessController owner, String reason) {
        Message msg = mHandler.obtainMessage(DESTROY_ACTIVITIES_MSG);
        msg.obj = new ScheduleDestroyArgs(owner, reason);
        mHandler.sendMessage(msg);
    }

    private void destroyActivitiesLocked(WindowProcessController owner, String reason) {
        boolean lastIsOpaque = false;
        boolean activityRemoved = false;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.finishing) {
                    continue;
                }
                if (r.fullscreen) {
                    lastIsOpaque = true;
                }
                if (owner != null && r.app != owner) {
                    continue;
                }
                if (!lastIsOpaque) {
                    continue;
                }
                if (r.isDestroyable()) {
                    if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Destroying " + r
                            + " in state " + r.getState()
                            + " resumed=" + mResumedActivity
                            + " pausing=" + mPausingActivity + " for reason " + reason);
                    if (destroyActivityLocked(r, true, reason)) {
                        activityRemoved = true;
                    }
                }
            }
        }
        if (activityRemoved) {
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    final boolean safelyDestroyActivityLocked(ActivityRecord r, String reason) {
        if (r.isDestroyable()) {
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                    "Destroying " + r + " in state " + r.getState() + " resumed=" + mResumedActivity
                    + " pausing=" + mPausingActivity + " for reason " + reason);
            return destroyActivityLocked(r, true, reason);
        }
        return false;
    }

    final int releaseSomeActivitiesLocked(WindowProcessController app, ArraySet<TaskRecord> tasks,
            String reason) {
        // Iterate over tasks starting at the back (oldest) first.
        if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Trying to release some activities in " + app);
        int maxTasks = tasks.size() / 4;
        if (maxTasks < 1) {
            maxTasks = 1;
        }
        int numReleased = 0;
        for (int taskNdx = 0; taskNdx < mTaskHistory.size() && maxTasks > 0; taskNdx++) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (!tasks.contains(task)) {
                continue;
            }
            if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Looking for activities to release in " + task);
            int curNum = 0;
            final ArrayList<ActivityRecord> activities = task.mActivities;
            for (int actNdx = 0; actNdx < activities.size(); actNdx++) {
                final ActivityRecord activity = activities.get(actNdx);
                if (activity.app == app && activity.isDestroyable()) {
                    if (DEBUG_RELEASE) Slog.v(TAG_RELEASE, "Destroying " + activity
                            + " in state " + activity.getState() + " resumed=" + mResumedActivity
                            + " pausing=" + mPausingActivity + " for reason " + reason);
                    destroyActivityLocked(activity, true, reason);
                    if (activities.get(actNdx) != activity) {
                        // Was removed from list, back up so we don't miss the next one.
                        actNdx--;
                    }
                    curNum++;
                }
            }
            if (curNum > 0) {
                numReleased += curNum;
                maxTasks--;
                if (mTaskHistory.get(taskNdx) != task) {
                    // The entire task got removed, back up so we don't miss the next one.
                    taskNdx--;
                }
            }
        }
        if (DEBUG_RELEASE) Slog.d(TAG_RELEASE,
                "Done releasing: did " + numReleased + " activities");
        return numReleased;
    }

    /**
     * Destroy the current CLIENT SIDE instance of an activity.  This may be
     * called both when actually finishing an activity, or when performing
     * a configuration switch where we destroy the current client-side object
     * but then create a new client-side object for this same HistoryRecord.
     */
    final boolean destroyActivityLocked(ActivityRecord r, boolean removeFromApp, String reason) {
        if (DEBUG_SWITCH || DEBUG_CLEANUP) Slog.v(TAG_SWITCH,
                "Removing activity from " + reason + ": token=" + r
                        + ", app=" + (r.hasProcess() ? r.app.mName : "(null)"));

        if (r.isState(DESTROYING, DESTROYED)) {
            if (DEBUG_STATES) Slog.v(TAG_STATES, "activity " + r + " already destroying."
                    + "skipping request with reason:" + reason);
            return false;
        }

        EventLog.writeEvent(EventLogTags.AM_DESTROY_ACTIVITY,
                r.mUserId, System.identityHashCode(r),
                r.getTaskRecord().taskId, r.shortComponentName, reason);

        boolean removedFromHistory = false;

        cleanUpActivityLocked(r, false, false);

        final boolean hadApp = r.hasProcess();

        if (hadApp) {
            if (removeFromApp) {
                r.app.removeActivity(r);
                if (!r.app.hasActivities()) {
                    mService.clearHeavyWeightProcessIfEquals(r.app);
                }
                if (!r.app.hasActivities()) {
                    // Update any services we are bound to that might care about whether
                    // their client may have activities.
                    // No longer have activities, so update LRU list and oom adj.
                    r.app.updateProcessInfo(true /* updateServiceConnectionActivities */,
                            false /* activityChange */, true /* updateOomAdj */);
                }
            }

            boolean skipDestroy = false;

            try {
                if (DEBUG_SWITCH) Slog.i(TAG_SWITCH, "Destroying: " + r);
                mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), r.appToken,
                        DestroyActivityItem.obtain(r.finishing, r.configChangeFlags));
            } catch (Exception e) {
                // We can just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                //Slog.w(TAG, "Exception thrown during finish", e);
                if (r.finishing) {
                    removeActivityFromHistoryLocked(r, reason + " exceptionInScheduleDestroy");
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }

            r.nowVisible = false;

            // If the activity is finishing, we need to wait on removing it
            // from the list to give it a chance to do its cleanup.  During
            // that time it may make calls back with its token so we need to
            // be able to find it on the list and so we don't want to remove
            // it from the list yet.  Otherwise, we can just immediately put
            // it in the destroyed state since we are not removing it from the
            // list.
            if (r.finishing && !skipDestroy) {
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to DESTROYING: " + r
                        + " (destroy requested)");
                r.setState(DESTROYING,
                        "destroyActivityLocked. finishing and not skipping destroy");
                Message msg = mHandler.obtainMessage(DESTROY_TIMEOUT_MSG, r);
                mHandler.sendMessageDelayed(msg, DESTROY_TIMEOUT);
            } else {
                if (DEBUG_STATES) Slog.v(TAG_STATES,
                        "Moving to DESTROYED: " + r + " (destroy skipped)");
                r.setState(DESTROYED,
                        "destroyActivityLocked. not finishing or skipping destroy");
                if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during destroy for activity " + r);
                r.app = null;
            }
        } else {
            // remove this record from the history.
            if (r.finishing) {
                removeActivityFromHistoryLocked(r, reason + " hadNoApp");
                removedFromHistory = true;
            } else {
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to DESTROYED: " + r + " (no app)");
                r.setState(DESTROYED, "destroyActivityLocked. not finishing and had no app");
                if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during destroy for activity " + r);
                r.app = null;
            }
        }

        r.configChangeFlags = 0;

        if (!mLRUActivities.remove(r) && hadApp) {
            Slog.w(TAG, "Activity " + r + " being finished, but not in LRU list");
        }

        return removedFromHistory;
    }

    final void activityDestroyedLocked(IBinder token, String reason) {
        final long origId = Binder.clearCallingIdentity();
        try {
            activityDestroyedLocked(ActivityRecord.forTokenLocked(token), reason);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * This method is to only be called from the client via binder when the activity is destroyed
     * AND finished.
     */
    final void activityDestroyedLocked(ActivityRecord record, String reason) {
        if (record != null) {
            mHandler.removeMessages(DESTROY_TIMEOUT_MSG, record);
        }

        if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS, "activityDestroyedLocked: r=" + record);

        if (isInStackLocked(record) != null) {
            if (record.isState(DESTROYING, DESTROYED)) {
                cleanUpActivityLocked(record, true, false);
                removeActivityFromHistoryLocked(record, reason);
            }
        }

        mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    private void removeHistoryRecordsForAppLocked(ArrayList<ActivityRecord> list,
            WindowProcessController app, String listName) {
        int i = list.size();
        if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
            "Removing app " + app + " from list " + listName + " with " + i + " entries");
        while (i > 0) {
            i--;
            ActivityRecord r = list.get(i);
            if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "Record #" + i + " " + r);
            if (r.app == app) {
                if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "---> REMOVING this entry!");
                list.remove(i);
                removeTimeoutsForActivityLocked(r);
            }
        }
    }

    private boolean removeHistoryRecordsForAppLocked(WindowProcessController app) {
        removeHistoryRecordsForAppLocked(mLRUActivities, app, "mLRUActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mStoppingActivities, app,
                "mStoppingActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mGoingToSleepActivities, app,
                "mGoingToSleepActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mFinishingActivities, app,
                "mFinishingActivities");

        final boolean isProcessRemoved = app.isRemoved();
        if (isProcessRemoved) {
            // The package of the died process should be force-stopped, so make its activities as
            // finishing to prevent the process from being started again if the next top (or being
            // visible) activity also resides in the same process.
            app.makeFinishingForProcessRemoved();
        }

        boolean hasVisibleActivities = false;

        // Clean out the history list.
        int i = numActivities();
        if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                "Removing app " + app + " from history with " + i + " entries");
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            mTmpActivities.clear();
            mTmpActivities.addAll(activities);

            while (!mTmpActivities.isEmpty()) {
                final int targetIndex = mTmpActivities.size() - 1;
                final ActivityRecord r = mTmpActivities.remove(targetIndex);
                if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                        "Record #" + targetIndex + " " + r + ": app=" + r.app);

                if (r.app == app) {
                    if (r.visible) {
                        hasVisibleActivities = true;
                    }
                    final boolean remove;
                    if ((r.mRelaunchReason == RELAUNCH_REASON_WINDOWING_MODE_RESIZE
                            || r.mRelaunchReason == RELAUNCH_REASON_FREE_RESIZE)
                            && r.launchCount < 3 && !r.finishing) {
                        // If the process crashed during a resize, always try to relaunch it, unless
                        // it has failed more than twice. Skip activities that's already finishing
                        // cleanly by itself.
                        remove = false;
                    } else if ((!r.haveState && !r.stateNotNeeded
                            && !r.isState(ActivityState.RESTARTING_PROCESS)) || r.finishing) {
                        // Don't currently have state for the activity, or
                        // it is finishing -- always remove it.
                        remove = true;
                    } else if (!r.visible && r.launchCount > 2 &&
                            r.lastLaunchTime > (SystemClock.uptimeMillis() - 60000)) {
                        // We have launched this activity too many times since it was
                        // able to run, so give up and remove it.
                        // (Note if the activity is visible, we don't remove the record.
                        // We leave the dead window on the screen but the process will
                        // not be restarted unless user explicitly tap on it.)
                        remove = true;
                    } else {
                        // The process may be gone, but the activity lives on!
                        remove = false;
                    }
                    if (remove) {
                        if (DEBUG_ADD_REMOVE || DEBUG_CLEANUP) Slog.i(TAG_ADD_REMOVE,
                                "Removing activity " + r + " from stack at " + i
                                + ": haveState=" + r.haveState
                                + " stateNotNeeded=" + r.stateNotNeeded
                                + " finishing=" + r.finishing
                                + " state=" + r.getState() + " callers=" + Debug.getCallers(5));
                        if (!r.finishing || isProcessRemoved) {
                            Slog.w(TAG, "Force removing " + r + ": app died, no saved state");
                            EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                                    r.mUserId, System.identityHashCode(r),
                                    r.getTaskRecord().taskId, r.shortComponentName,
                                    "proc died without state saved");
                        }
                    } else {
                        // We have the current state for this activity, so
                        // it can be restarted later when needed.
                        if (DEBUG_ALL) Slog.v(TAG, "Keeping entry, setting app to null");
                        if (DEBUG_APP) Slog.v(TAG_APP,
                                "Clearing app during removeHistory for activity " + r);
                        r.app = null;
                        // Set nowVisible to previous visible state. If the app was visible while
                        // it died, we leave the dead window on screen so it's basically visible.
                        // This is needed when user later tap on the dead window, we need to stop
                        // other apps when user transfers focus to the restarted activity.
                        r.nowVisible = r.visible;
                        if (!r.haveState) {
                            if (DEBUG_SAVED_STATE) Slog.i(TAG_SAVED_STATE,
                                    "App died, clearing saved state of " + r);
                            r.icicle = null;
                        }
                    }
                    cleanUpActivityLocked(r, true, true);
                    if (remove) {
                        removeActivityFromHistoryLocked(r, "appDied");
                    }
                }
            }
        }

        return hasVisibleActivities;
    }

    private void updateTransitLocked(int transit, ActivityOptions options) {
        if (options != null) {
            ActivityRecord r = topRunningActivityLocked();
            if (r != null && !r.isState(RESUMED)) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        getDisplay().mDisplayContent.prepareAppTransition(transit, false);
    }

    private void updateTaskMovement(TaskRecord task, boolean toFront) {
        if (task.isPersistable) {
            task.mLastTimeMoved = System.currentTimeMillis();
            // Sign is used to keep tasks sorted when persisted. Tasks sent to the bottom most
            // recently will be most negative, tasks sent to the bottom before that will be less
            // negative. Similarly for recent tasks moved to the top which will be most positive.
            if (!toFront) {
                task.mLastTimeMoved *= -1;
            }
        }
        mRootActivityContainer.invalidateTaskLayers();
    }

    final void moveTaskToFrontLocked(TaskRecord tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, String reason) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "moveTaskToFront: " + tr);

        final ActivityStack topStack = getDisplay().getTopStack();
        final ActivityRecord topActivity = topStack != null ? topStack.getTopActivity() : null;
        final int numTasks = mTaskHistory.size();
        final int index = mTaskHistory.indexOf(tr);
        if (numTasks == 0 || index < 0)  {
            // nothing to do!
            if (noAnimation) {
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(TRANSIT_TASK_TO_FRONT, options);
            }
            return;
        }

        if (timeTracker != null) {
            // The caller wants a time tracker associated with this task.
            for (int i = tr.mActivities.size() - 1; i >= 0; i--) {
                tr.mActivities.get(i).appTimeTracker = timeTracker;
            }
        }

        try {
            // Defer updating the IME target since the new IME target will try to get computed
            // before updating all closing and opening apps, which can cause the ime target to
            // get calculated incorrectly.
            getDisplay().deferUpdateImeTarget();

            // Shift all activities with this task up to the top
            // of the stack, keeping them in the same internal order.
            insertTaskAtTop(tr, null);

            // Don't refocus if invisible to current user
            final ActivityRecord top = tr.getTopActivity();
            if (top == null || !top.okToShowLocked()) {
                if (top != null) {
                    mStackSupervisor.mRecentTasks.add(top.getTaskRecord());
                }
                ActivityOptions.abort(options);
                return;
            }

            // Set focus to the top running activity of this stack.
            final ActivityRecord r = topRunningActivityLocked();
            if (r != null) {
                r.moveFocusableActivityToTop(reason);
            }

            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to front transition: task=" + tr);
            if (noAnimation) {
                getDisplay().mDisplayContent.prepareAppTransition(TRANSIT_NONE, false);
                if (r != null) {
                    mStackSupervisor.mNoAnimActivities.add(r);
                }
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(TRANSIT_TASK_TO_FRONT, options);
            }
            // If a new task is moved to the front, then mark the existing top activity as
            // supporting

            // picture-in-picture while paused only if the task would not be considered an oerlay
            // on top
            // of the current activity (eg. not fullscreen, or the assistant)
            if (canEnterPipOnTaskSwitch(topActivity, tr, null /* toFrontActivity */,
                    options)) {
                topActivity.supportsEnterPipOnTaskSwitch = true;
            }

            mRootActivityContainer.resumeFocusedStacksTopActivities();
            EventLog.writeEvent(EventLogTags.AM_TASK_TO_FRONT, tr.userId, tr.taskId);
            mService.getTaskChangeNotificationController().notifyTaskMovedToFront(tr.getTaskInfo());
        } finally {
            getDisplay().continueUpdateImeTarget();
        }
    }

    /**
     * Worker method for rearranging history stack. Implements the function of moving all
     * activities for a specific task (gathering them if disjoint) into a single group at the
     * bottom of the stack.
     *
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     *
     * @param taskId The taskId to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    final boolean moveTaskToBackLocked(int taskId) {
        final TaskRecord tr = taskForIdLocked(taskId);
        if (tr == null) {
            Slog.i(TAG, "moveTaskToBack: bad taskId=" + taskId);
            return false;
        }
        Slog.i(TAG, "moveTaskToBack: " + tr);

        // In LockTask mode, moving a locked task to the back of the stack may expose unlocked
        // ones. Therefore we need to check if this operation is allowed.
        if (!mService.getLockTaskController().canMoveTaskToBack(tr)) {
            return false;
        }

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (isTopStackOnDisplay() && mService.mController != null) {
            ActivityRecord next = topRunningActivityLocked(null, taskId);
            if (next == null) {
                next = topRunningActivityLocked(null, 0);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mService.mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
                if (!moveOK) {
                    return false;
                }
            }
        }

        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to back transition: task=" + taskId);

        mTaskHistory.remove(tr);
        mTaskHistory.add(0, tr);
        updateTaskMovement(tr, false);

        getDisplay().mDisplayContent.prepareAppTransition(TRANSIT_TASK_TO_BACK, false);
        moveToBack("moveTaskToBackLocked", tr);

        if (inPinnedWindowingMode()) {
            mStackSupervisor.removeStack(this);
            return true;
        }

        ActivityRecord topActivity = getDisplay().topRunningActivity();
        ActivityStack topStack = topActivity.getActivityStack();
        if (topStack != null && topStack != this && topActivity.isState(RESUMED)) {
            // The new top activity is already resumed, so there's a good chance that nothing will
            // get resumed below. So, update visibility now in case the transition is closed
            // prematurely.
            mRootActivityContainer.ensureVisibilityAndConfig(null /* starting */,
                    getDisplay().mDisplayId, false /* markFrozenIfConfigChanged */,
                    false /* deferResume */);
        }

        mRootActivityContainer.resumeFocusedStacksTopActivities();
        return true;
    }

    static void logStartActivity(int tag, ActivityRecord r, TaskRecord task) {
        final Uri data = r.intent.getData();
        final String strData = data != null ? data.toSafeString() : null;

        EventLog.writeEvent(tag,
                r.mUserId, System.identityHashCode(r), task.taskId,
                r.shortComponentName, r.intent.getAction(),
                r.intent.getType(), strData, r.intent.getFlags());
    }

    /**
     * Ensures all visible activities at or below the input activity have the right configuration.
     */
    void ensureVisibleActivitiesConfigurationLocked(ActivityRecord start, boolean preserveWindow) {
        if (start == null || !start.visible) {
            return;
        }

        final TaskRecord startTask = start.getTaskRecord();
        boolean behindFullscreen = false;
        boolean updatedConfig = false;

        for (int taskIndex = mTaskHistory.indexOf(startTask); taskIndex >= 0; --taskIndex) {
            final TaskRecord task = mTaskHistory.get(taskIndex);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            int activityIndex = (start.getTaskRecord() == task)
                    ? activities.indexOf(start) : activities.size() - 1;
            for (; activityIndex >= 0; --activityIndex) {
                final ActivityRecord r = activities.get(activityIndex);
                updatedConfig |= r.ensureActivityConfiguration(0 /* globalChanges */,
                        preserveWindow);
                if (r.fullscreen) {
                    behindFullscreen = true;
                    break;
                }
            }
            if (behindFullscreen) {
                break;
            }
        }
        if (updatedConfig) {
            // Ensure the resumed state of the focus activity if we updated the configuration of
            // any activity.
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    // TODO: Figure-out a way to consolidate with resize() method below.
    void requestResize(Rect bounds) {
        mService.resizeStack(mStackId, bounds,
                true /* allowResizeInDockedMode */, false /* preserveWindows */,
                false /* animate */, -1 /* animationDuration */);
    }

    // TODO: Can only be called from special methods in ActivityStackSupervisor.
    // Need to consolidate those calls points into this resize method so anyone can call directly.
    void resize(Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds) {
        if (!updateBoundsAllowed(bounds)) {
            return;
        }

        // Update override configurations of all tasks in the stack.
        final Rect taskBounds = tempTaskBounds != null ? tempTaskBounds : bounds;

        for (int i = mTaskHistory.size() - 1; i >= 0; i--) {
            final TaskRecord task = mTaskHistory.get(i);
            if (task.isResizeable()) {
                task.updateOverrideConfiguration(taskBounds, tempTaskInsetBounds);
            }
        }

        setBounds(bounds);
    }

    void onPipAnimationEndResize() {
        if (mTaskStack == null) return;
        mTaskStack.onPipAnimationEndResize();
    }


    /**
     * Until we can break this "set task bounds to same as stack bounds" behavior, this
     * basically resizes both stack and task bounds to the same bounds.
     */
    void setTaskBounds(Rect bounds) {
        if (!updateBoundsAllowed(bounds)) {
            return;
        }

        for (int i = mTaskHistory.size() - 1; i >= 0; i--) {
            final TaskRecord task = mTaskHistory.get(i);
            if (task.isResizeable()) {
                task.setBounds(bounds);
            } else {
                task.setBounds(null);
            }
        }
    }

    /** Helper to setDisplayedBounds on all child tasks */
    void setTaskDisplayedBounds(Rect bounds) {
        if (!updateDisplayedBoundsAllowed(bounds)) {
            return;
        }

        for (int i = mTaskHistory.size() - 1; i >= 0; i--) {
            final TaskRecord task = mTaskHistory.get(i);
            if (bounds == null || bounds.isEmpty()) {
                task.setDisplayedBounds(null);
            } else if (task.isResizeable()) {
                task.setDisplayedBounds(bounds);
            }
        }
    }

    boolean willActivityBeVisibleLocked(IBinder token) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.appToken == token) {
                    return true;
                }
                if (r.fullscreen && !r.finishing) {
                    return false;
                }
            }
        }
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            return false;
        }
        if (r.finishing) Slog.e(TAG, "willActivityBeVisibleLocked: Returning false,"
                + " would have returned true for r=" + r);
        return !r.finishing;
    }

    void closeSystemDialogsLocked() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if ((r.info.flags&ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS) != 0) {
                    finishActivityLocked(r, Activity.RESULT_CANCELED, null, "close-sys", true);
                }
            }
        }
    }

    boolean finishDisabledPackageActivitiesLocked(String packageName, Set<String> filterByClasses,
            boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        TaskRecord lastTask = null;
        ComponentName homeActivity = null;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            mTmpActivities.clear();
            mTmpActivities.addAll(activities);

            while (!mTmpActivities.isEmpty()) {
                ActivityRecord r = mTmpActivities.remove(0);
                final boolean sameComponent =
                        (r.packageName.equals(packageName) && (filterByClasses == null
                                || filterByClasses.contains(r.mActivityComponent.getClassName())))
                        || (packageName == null && r.mUserId == userId);
                if ((userId == UserHandle.USER_ALL || r.mUserId == userId)
                        && (sameComponent || r.getTaskRecord() == lastTask)
                        && (r.app == null || evenPersistent || !r.app.isPersistent())) {
                    if (!doit) {
                        if (r.finishing) {
                            // If this activity is just finishing, then it is not
                            // interesting as far as something to stop.
                            continue;
                        }
                        return true;
                    }
                    if (r.isActivityTypeHome()) {
                        if (homeActivity != null && homeActivity.equals(r.mActivityComponent)) {
                            Slog.i(TAG, "Skip force-stop again " + r);
                            continue;
                        } else {
                            homeActivity = r.mActivityComponent;
                        }
                    }
                    didSomething = true;
                    Slog.i(TAG, "  Force finishing activity " + r);
                    lastTask = r.getTaskRecord();
                    finishActivityLocked(r, Activity.RESULT_CANCELED, null, "force-stop",
                            true);
                }
            }
        }
        return didSomething;
    }

    /**
     * @return The set of running tasks through {@param tasksOut} that are available to the caller.
     *         If {@param ignoreActivityType} or {@param ignoreWindowingMode} are not undefined,
     *         then skip running tasks that match those types.
     */
    void getRunningTasks(List<TaskRecord> tasksOut, @ActivityType int ignoreActivityType,
            @WindowingMode int ignoreWindowingMode, int callingUid, boolean allowed,
            boolean crossUser, ArraySet<Integer> profileIds) {
        boolean focusedStack = mRootActivityContainer.getTopDisplayFocusedStack() == this;
        boolean topTask = true;
        int userId = UserHandle.getUserId(callingUid);
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.getTopActivity() == null) {
                // Skip if there are no activities in the task
                continue;
            }
            if (task.effectiveUid != callingUid) {
                if (task.userId != userId && !crossUser && !profileIds.contains(task.userId)) {
                    // Skip if the caller does not have cross user permission or cannot access
                    // the task's profile
                    continue;
                }
                if (!allowed && !task.isActivityTypeHome()) {
                    // Skip if the caller isn't allowed to fetch this task, except for the home
                    // task which we always return.
                    continue;
                }
            }
            if (ignoreActivityType != ACTIVITY_TYPE_UNDEFINED
                    && task.getActivityType() == ignoreActivityType) {
                // Skip ignored activity type
                continue;
            }
            if (ignoreWindowingMode != WINDOWING_MODE_UNDEFINED
                    && task.getWindowingMode() == ignoreWindowingMode) {
                // Skip ignored windowing mode
                continue;
            }
            if (focusedStack && topTask) {
                // For the focused stack top task, update the last stack active time so that it can
                // be used to determine the order of the tasks (it may not be set for newly created
                // tasks)
                task.lastActiveTime = SystemClock.elapsedRealtime();
                topTask = false;
            }
            tasksOut.add(task);
        }
    }

    void unhandledBackLocked() {
        final int top = mTaskHistory.size() - 1;
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH, "Performing unhandledBack(): top activity at " + top);
        if (top >= 0) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(top).mActivities;
            int activityTop = activities.size() - 1;
            if (activityTop >= 0) {
                finishActivityLocked(activities.get(activityTop), Activity.RESULT_CANCELED, null,
                        "unhandled-back", true);
            }
        }
    }

    /**
     * Reset local parameters because an app's activity died.
     * @param app The app of the activity that died.
     * @return result from removeHistoryRecordsForAppLocked.
     */
    boolean handleAppDiedLocked(WindowProcessController app) {
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE || DEBUG_CLEANUP) Slog.v(TAG_PAUSE,
                    "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }

        return removeHistoryRecordsForAppLocked(app);
    }

    void handleAppCrash(WindowProcessController app) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.app == app) {
                    Slog.w(TAG, "  Force finishing activity "
                            + r.intent.getComponent().flattenToShortString());
                    // Force the destroy to skip right to removal.
                    r.app = null;
                    getDisplay().mDisplayContent.prepareAppTransition(
                            TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
                    finishCurrentActivityLocked(r, FINISH_IMMEDIATELY, false,
                            "handleAppCrashedLocked");
                }
            }
        }
    }

    boolean dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage, boolean needSep) {
        pw.println("  Stack #" + mStackId
                + ": type=" + activityTypeToString(getActivityType())
                + " mode=" + windowingModeToString(getWindowingMode()));
        pw.println("  isSleeping=" + shouldSleepActivities());
        pw.println("  mBounds=" + getRequestedOverrideBounds());

        boolean printed = dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage,
                needSep);

        printed |= dumpHistoryList(fd, pw, mLRUActivities, "    ", "Run", false,
                !dumpAll, false, dumpPackage, true,
                "    Running activities (most recent first):", null);

        needSep = printed;
        boolean pr = printThisActivity(pw, mPausingActivity, dumpPackage, needSep,
                "    mPausingActivity: ");
        if (pr) {
            printed = true;
            needSep = false;
        }
        pr = printThisActivity(pw, getResumedActivity(), dumpPackage, needSep,
                "    mResumedActivity: ");
        if (pr) {
            printed = true;
            needSep = false;
        }
        if (dumpAll) {
            pr = printThisActivity(pw, mLastPausedActivity, dumpPackage, needSep,
                    "    mLastPausedActivity: ");
            if (pr) {
                printed = true;
                needSep = true;
            }
            printed |= printThisActivity(pw, mLastNoHistoryActivity, dumpPackage,
                    needSep, "    mLastNoHistoryActivity: ");
        }
        return printed;
    }

    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, boolean needSep) {

        if (mTaskHistory.isEmpty()) {
            return false;
        }
        final String prefix = "    ";
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (needSep) {
                pw.println("");
            }
            pw.println(prefix + "Task id #" + task.taskId);
            pw.println(prefix + "mBounds=" + task.getRequestedOverrideBounds());
            pw.println(prefix + "mMinWidth=" + task.mMinWidth);
            pw.println(prefix + "mMinHeight=" + task.mMinHeight);
            pw.println(prefix + "mLastNonFullscreenBounds=" + task.mLastNonFullscreenBounds);
            pw.println(prefix + "* " + task);
            task.dump(pw, prefix + "  ");
            dumpHistoryList(fd, pw, mTaskHistory.get(taskNdx).mActivities,
                    prefix, "Hist", true, !dumpAll, dumpClient, dumpPackage, false, null, task);
        }
        return true;
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        ArrayList<ActivityRecord> activities = new ArrayList<>();

        if ("all".equals(name)) {
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                activities.addAll(mTaskHistory.get(taskNdx).mActivities);
            }
        } else if ("top".equals(name)) {
            final int top = mTaskHistory.size() - 1;
            if (top >= 0) {
                final ArrayList<ActivityRecord> list = mTaskHistory.get(top).mActivities;
                int listTop = list.size() - 1;
                if (listTop >= 0) {
                    activities.add(list.get(listTop));
                }
            }
        } else {
            ItemMatcher matcher = new ItemMatcher();
            matcher.build(name);

            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                for (ActivityRecord r1 : mTaskHistory.get(taskNdx).mActivities) {
                    if (matcher.match(r1, r1.intent.getComponent())) {
                        activities.add(r1);
                    }
                }
            }
        }

        return activities;
    }

    ActivityRecord restartPackage(String packageName) {
        ActivityRecord starting = topRunningActivityLocked();

        // All activities that came from the package must be
        // restarted as if there was a config change.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord a = activities.get(activityNdx);
                if (a.info.packageName.equals(packageName)) {
                    a.forceNewConfig = true;
                    if (starting != null && a == starting && a.visible) {
                        a.startFreezingScreenLocked(starting.app,
                                CONFIG_SCREEN_LAYOUT);
                    }
                }
            }
        }

        return starting;
    }

    /**
     * Removes the input task from this stack.
     *
     * @param task to remove.
     * @param reason for removal.
     * @param mode task removal mode. Either {@link #REMOVE_TASK_MODE_DESTROYING},
     *             {@link #REMOVE_TASK_MODE_MOVING}, {@link #REMOVE_TASK_MODE_MOVING_TO_TOP}.
     */
    void removeTask(TaskRecord task, String reason, int mode) {
        final boolean removed = mTaskHistory.remove(task);

        if (removed) {
            EventLog.writeEvent(EventLogTags.AM_REMOVE_TASK, task.taskId, getStackId());
        }

        removeActivitiesFromLRUListLocked(task);
        updateTaskMovement(task, true);

        if (mode == REMOVE_TASK_MODE_DESTROYING) {
            task.cleanUpResourcesForDestroy();
        }

        final ActivityDisplay display = getDisplay();
        if (mTaskHistory.isEmpty()) {
            if (DEBUG_STACK) Slog.i(TAG_STACK, "removeTask: removing stack=" + this);
            // We only need to adjust focused stack if this stack is in focus and we are not in the
            // process of moving the task to the top of the stack that will be focused.
            if (mode != REMOVE_TASK_MODE_MOVING_TO_TOP
                    && mRootActivityContainer.isTopDisplayFocusedStack(this)) {
                String myReason = reason + " leftTaskHistoryEmpty";
                if (!inMultiWindowMode() || adjustFocusToNextFocusableStack(myReason) == null) {
                    display.moveHomeStackToFront(myReason);
                }
            }
            if (isAttached()) {
                display.positionChildAtBottom(this);
            }
            if (!isActivityTypeHome() || !isAttached()) {
                remove();
            }
        }

        task.setStack(null);

        // Notify if a task from the pinned stack is being removed (or moved depending on the mode)
        if (inPinnedWindowingMode()) {
            mService.getTaskChangeNotificationController().notifyActivityUnpinned();
        }
        if (display.isSingleTaskInstance()) {
            mService.notifySingleTaskDisplayEmpty(display.mDisplayId);
        }
    }

    TaskRecord createTaskRecord(int taskId, ActivityInfo info, Intent intent,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            boolean toTop) {
        return createTaskRecord(taskId, info, intent, voiceSession, voiceInteractor, toTop,
                null /*activity*/, null /*source*/, null /*options*/);
    }

    TaskRecord createTaskRecord(int taskId, ActivityInfo info, Intent intent,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            boolean toTop, ActivityRecord activity, ActivityRecord source,
            ActivityOptions options) {
        final TaskRecord task = TaskRecord.create(
                mService, taskId, info, intent, voiceSession, voiceInteractor);
        // add the task to stack first, mTaskPositioner might need the stack association
        addTask(task, toTop, "createTaskRecord");
        final int displayId = mDisplayId != INVALID_DISPLAY ? mDisplayId : DEFAULT_DISPLAY;
        final boolean isLockscreenShown = mService.mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        if (!mStackSupervisor.getLaunchParamsController()
                .layoutTask(task, info.windowLayout, activity, source, options)
                && !matchParentBounds() && task.isResizeable() && !isLockscreenShown) {
            task.updateOverrideConfiguration(getRequestedOverrideBounds());
        }
        task.createTask(toTop, (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
        return task;
    }

    ArrayList<TaskRecord> getAllTasks() {
        return new ArrayList<>(mTaskHistory);
    }

    void addTask(final TaskRecord task, final boolean toTop, String reason) {
        addTask(task, toTop ? MAX_VALUE : 0, true /* schedulePictureInPictureModeChange */, reason);
        if (toTop) {
            // TODO: figure-out a way to remove this call.
            positionChildWindowContainerAtTop(task);
        }
    }

    // TODO: This shouldn't allow automatic reparenting. Remove the call to preAddTask and deal
    // with the fall-out...
    void addTask(final TaskRecord task, int position, boolean schedulePictureInPictureModeChange,
            String reason) {
        // TODO: Is this remove really needed? Need to look into the call path for the other addTask
        mTaskHistory.remove(task);
        if (isSingleTaskInstance() && !mTaskHistory.isEmpty()) {
            throw new IllegalStateException("Can only have one child on stack=" + this);
        }

        position = getAdjustedPositionForTask(task, position, null /* starting */);
        final boolean toTop = position >= mTaskHistory.size();
        final ActivityStack prevStack = preAddTask(task, reason, toTop);

        mTaskHistory.add(position, task);
        task.setStack(this);

        updateTaskMovement(task, toTop);

        postAddTask(task, prevStack, schedulePictureInPictureModeChange);
    }

    void positionChildAt(TaskRecord task, int index) {

        if (task.getStack() != this) {
            throw new IllegalArgumentException("AS.positionChildAt: task=" + task
                    + " is not a child of stack=" + this + " current parent=" + task.getStack());
        }

        task.updateOverrideConfigurationForStack(this);

        final ActivityRecord topRunningActivity = task.topRunningActivityLocked();
        final boolean wasResumed = topRunningActivity == task.getStack().mResumedActivity;
        insertTaskAtPosition(task, index);
        task.setStack(this);
        postAddTask(task, null /* prevStack */, true /* schedulePictureInPictureModeChange */);

        if (wasResumed) {
            if (mResumedActivity != null) {
                Log.wtf(TAG, "mResumedActivity was already set when moving mResumedActivity from"
                        + " other stack to this stack mResumedActivity=" + mResumedActivity
                        + " other mResumedActivity=" + topRunningActivity);
            }
            topRunningActivity.setState(RESUMED, "positionChildAt");
        }

        // The task might have already been running and its visibility needs to be synchronized with
        // the visibility of the stack / windows.
        ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    private ActivityStack preAddTask(TaskRecord task, String reason, boolean toTop) {
        final ActivityStack prevStack = task.getStack();
        if (prevStack != null && prevStack != this) {
            prevStack.removeTask(task, reason,
                    toTop ? REMOVE_TASK_MODE_MOVING_TO_TOP : REMOVE_TASK_MODE_MOVING);
        }
        return prevStack;
    }

    /**
     * @param schedulePictureInPictureModeChange specifies whether or not to schedule the PiP mode
     *            change. Callers may set this to false if they are explicitly scheduling PiP mode
     *            changes themselves, like during the PiP animation
     */
    private void postAddTask(TaskRecord task, ActivityStack prevStack,
            boolean schedulePictureInPictureModeChange) {
        if (schedulePictureInPictureModeChange && prevStack != null) {
            mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, prevStack);
        } else if (task.voiceSession != null) {
            try {
                task.voiceSession.taskStarted(task.intent, task.taskId);
            } catch (RemoteException e) {
            }
        }
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        final ActivityDisplay display = getDisplay();
        // positionChildAtTop() must be called even when always on top gets turned off because we
        // need to make sure that the stack is moved from among always on top windows to below other
        // always on top windows. Since the position the stack should be inserted into is calculated
        // properly in {@link ActivityDisplay#getTopInsertPosition()} in both cases, we can just
        // request that the stack is put at top here.
        display.positionChildAtTop(this, false /* includingParents */);
    }

    /** NOTE: Should only be called from {@link TaskRecord#reparent}. */
    void moveToFrontAndResumeStateIfNeeded(ActivityRecord r, boolean moveToFront, boolean setResume,
            boolean setPause, String reason) {
        if (!moveToFront) {
            return;
        }

        final ActivityState origState = r.getState();
        // If the activity owns the last resumed activity, transfer that together,
        // so that we don't resume the same activity again in the new stack.
        // Apps may depend on onResume()/onPause() being called in pairs.
        if (setResume) {
            r.setState(RESUMED, "moveToFrontAndResumeStateIfNeeded");
            updateLRUListLocked(r);
        }
        // If the activity was previously pausing, then ensure we transfer that as well
        if (setPause) {
            mPausingActivity = r;
            schedulePauseTimeout(r);
        }
        // Move the stack in which we are placing the activity to the front.
        moveToFront(reason);
        // If the original state is resumed, there is no state change to update focused app.
        // So here makes sure the activity focus is set if it is the top.
        if (origState == RESUMED && r == mRootActivityContainer.getTopResumedActivity()) {
            // TODO(b/111361570): Support multiple focused apps in WM
            mService.setResumedActivityUncheckLocked(r, reason);
        }
    }


    Rect getDefaultPictureInPictureBounds(float aspectRatio) {
        if (getTaskStack() == null) return null;
        return getTaskStack().getPictureInPictureBounds(aspectRatio, null /* currentStackBounds */);
    }

    void animateResizePinnedStack(Rect sourceHintBounds, Rect toBounds, int animationDuration,
            boolean fromFullscreen) {
        if (!inPinnedWindowingMode()) return;
        if (toBounds == null /* toFullscreen */) {
            final Configuration parentConfig = getParent().getConfiguration();
            final ActivityRecord top = topRunningNonOverlayTaskActivity();
            if (top != null && !top.isConfigurationCompatible(parentConfig)) {
                // The final orientation of this activity will change after moving to full screen.
                // Start freezing screen here to prevent showing a temporary full screen window.
                top.startFreezingScreenLocked(top.app, CONFIG_SCREEN_LAYOUT);
                mService.moveTasksToFullscreenStack(mStackId, true /* onTop */);
                return;
            }
        }
        if (getTaskStack() == null) return;
        getTaskStack().animateResizePinnedStack(toBounds, sourceHintBounds,
                animationDuration, fromFullscreen);
    }

    /**
     * Get current bounds of this stack, return empty when it is unavailable.
     * @see TaskStack#getAnimationOrCurrentBounds(Rect)
     */
    void getAnimationOrCurrentBounds(Rect outBounds) {
        final TaskStack stack = getTaskStack();
        if (stack == null) {
            outBounds.setEmpty();
            return;
        }
        stack.getAnimationOrCurrentBounds(outBounds);
    }

    void setPictureInPictureAspectRatio(float aspectRatio) {
        if (getTaskStack() == null) return;
        getTaskStack().setPictureInPictureAspectRatio(aspectRatio);
    }

    void setPictureInPictureActions(List<RemoteAction> actions) {
        if (getTaskStack() == null) return;
        getTaskStack().setPictureInPictureActions(actions);
    }

    boolean isAnimatingBoundsToFullscreen() {
        if (getTaskStack() == null) return false;
        return getTaskStack().isAnimatingBoundsToFullscreen();
    }

    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds,
            boolean forceUpdate) {
        // It is guaranteed that the activities requiring the update will be in the pinned stack at
        // this point (either reparented before the animation into PiP, or before reparenting after
        // the animation out of PiP)
        synchronized (mService.mGlobalLock) {
            if (!isAttached()) {
                return;
            }
            ArrayList<TaskRecord> tasks = getAllTasks();
            for (int i = 0; i < tasks.size(); i++) {
                mStackSupervisor.updatePictureInPictureMode(tasks.get(i), targetStackBounds,
                        forceUpdate);
            }
        }
    }

    public int getStackId() {
        return mStackId;
    }

    @Override
    public String toString() {
        return "ActivityStack{" + Integer.toHexString(System.identityHashCode(this))
                + " stackId=" + mStackId + " type=" + activityTypeToString(getActivityType())
                + " mode=" + windowingModeToString(getWindowingMode())
                + " visible=" + shouldBeVisible(null /* starting */)
                + " translucent=" + isStackTranslucent(null /* starting */)
                + ", "
                + mTaskHistory.size() + " tasks}";
    }

    void onLockTaskPackagesUpdated() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            mTaskHistory.get(taskNdx).setLockTaskAuth();
        }
    }

    void executeAppTransition(ActivityOptions options) {
        getDisplay().mDisplayContent.executeAppTransition();
        ActivityOptions.abort(options);
    }

    boolean shouldSleepActivities() {
        final ActivityDisplay display = getDisplay();

        // Do not sleep activities in this stack if we're marked as focused and the keyguard
        // is in the process of going away.
        if (isFocusedStackOnDisplay()
                && mStackSupervisor.getKeyguardController().isKeyguardGoingAway()) {
            return false;
        }

        return display != null ? display.isSleeping() : mService.isSleepingLocked();
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mService.mShuttingDown;
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, CONFIGURATION_CONTAINER, logLevel);
        proto.write(ID, mStackId);
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            task.writeToProto(proto, TASKS, logLevel);
        }
        if (mResumedActivity != null) {
            mResumedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
        }
        proto.write(DISPLAY_ID, mDisplayId);
        if (!matchParentBounds()) {
            final Rect bounds = getRequestedOverrideBounds();
            bounds.writeToProto(proto, BOUNDS);
        }

        // TODO: Remove, no longer needed with windowingMode.
        proto.write(FULLSCREEN, matchParentBounds());
        proto.end(token);
    }
}
