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

package com.android.server.am;

import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.app.ActivityManager.StackId.RECENTS_STACK_ID;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;

import static android.view.Display.INVALID_DISPLAY;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_APP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CONTAINERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SAVED_STATE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_APP;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CONTAINERS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SAVED_STATE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.ASSISTANT_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStack.ActivityState.STOPPED;
import static com.android.server.am.ActivityStack.ActivityState.STOPPING;
import static com.android.server.am.ActivityStackSupervisor.FindTaskResult;
import static com.android.server.am.ActivityStackSupervisor.ON_TOP;
import static com.android.server.am.ActivityStackSupervisor.PAUSE_IMMEDIATELY;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.am.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.AppTransition.TRANSIT_ACTIVITY_CLOSE;
import static com.android.server.wm.AppTransition.TRANSIT_ACTIVITY_OPEN;
import static com.android.server.wm.AppTransition.TRANSIT_NONE;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_CLOSE;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_OPEN;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_OPEN_BEHIND;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_TO_BACK;
import static com.android.server.wm.AppTransition.TRANSIT_TASK_TO_FRONT;
import static java.lang.Integer.MAX_VALUE;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackId;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.BatteryStatsImpl;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.wm.StackWindowController;
import com.android.server.wm.StackWindowListener;
import com.android.server.wm.WindowManagerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * State and management of a single stack of activities.
 */
class ActivityStack<T extends StackWindowController> extends ConfigurationContainer
        implements StackWindowListener {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStack" : TAG_AM;
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
    // from the application in order to get its saved state.
    private static final int STOP_TIMEOUT = 10 * 1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    private static final int DESTROY_TIMEOUT = 10 * 1000;

    // How long until we reset a task when the user returns to it.  Currently
    // disabled.
    private static final long ACTIVITY_INACTIVE_RESET_TIME = 0;

    // Set to false to disable the preview that is shown while a new activity
    // is being started.
    private static final boolean SHOW_APP_STARTING_PREVIEW = true;

    // How long to wait for all background Activities to redraw following a call to
    // convertToTranslucent().
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;

    // How many activities have to be scheduled to stop to force a stop pass.
    private static final int MAX_STOPPING_TO_FORCE = 3;

    @Override
    protected int getChildCount() {
        return mTaskHistory.size();
    }

    @Override
    protected ConfigurationContainer getChildAt(int index) {
        return mTaskHistory.get(index);
    }

    @Override
    protected ConfigurationContainer getParent() {
        return getDisplay();
    }

    @Override
    void onParentChanged() {
        super.onParentChanged();
        mStackSupervisor.updateUIDsPresentOnDisplay();
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
        DESTROYED
    }

    // Stack is not considered visible.
    static final int STACK_INVISIBLE = 0;
    // Stack is considered visible
    static final int STACK_VISIBLE = 1;

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

    // The height/width divide used when fitting a task within a bounds with method
    // {@link #fitWithinBounds}.
    // We always want the task to to be visible in the bounds without affecting its size when
    // fitting. To make sure this is the case, we don't adjust the task left or top side pass
    // the input bounds right or bottom side minus the width or height divided by this value.
    private static final int FIT_WITHIN_BOUNDS_DIVIDER = 3;

    final ActivityManagerService mService;
    private final WindowManagerService mWindowManager;
    T mWindowContainerController;
    private final RecentTasks mRecentTasks;

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
    final ArrayList<ActivityRecord> mLRUActivities = new ArrayList<>();

    /**
     * Animations that for the current transition have requested not to
     * be considered for the transition animation.
     */
    final ArrayList<ActivityRecord> mNoAnimActivities = new ArrayList<>();

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

    // Whether or not this stack covers the entire screen; by default stacks are fullscreen
    boolean mFullscreen = true;
    // Current bounds of the stack or null if fullscreen.
    Rect mBounds = null;

    private boolean mUpdateBoundsDeferred;
    private boolean mUpdateBoundsDeferredCalled;
    private final Rect mDeferredBounds = new Rect();
    private final Rect mDeferredTaskBounds = new Rect();
    private final Rect mDeferredTaskInsetBounds = new Rect();

    long mLaunchStartTime = 0;
    long mFullyDrawnStartTime = 0;

    int mCurrentUser;

    final int mStackId;
    /** The other stacks, in order, on the attached display. Updated at attach/detach time. */
    // TODO: This list doesn't belong here...
    ArrayList<ActivityStack> mStacks;
    /** The attached Display's unique identifier, or -1 if detached */
    int mDisplayId;

    /** Temp variables used during override configuration update. */
    private final SparseArray<Configuration> mTmpConfigs = new SparseArray<>();
    private final SparseArray<Rect> mTmpBounds = new SparseArray<>();
    private final SparseArray<Rect> mTmpInsetBounds = new SparseArray<>();
    private final Rect mTmpRect2 = new Rect();

    /** Run all ActivityStacks through this */
    protected final ActivityStackSupervisor mStackSupervisor;

    private final LaunchingTaskPositioner mTaskPositioner;

    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;

    static final int PAUSE_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 1;
    static final int DESTROY_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 2;
    static final int LAUNCH_TICK_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 3;
    static final int STOP_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 4;
    static final int DESTROY_ACTIVITIES_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 5;
    static final int TRANSLUCENT_TIMEOUT_MSG = ActivityManagerService.FIRST_ACTIVITY_STACK_MSG + 6;

    private static class ScheduleDestroyArgs {
        final ProcessRecord mOwner;
        final String mReason;
        ScheduleDestroyArgs(ProcessRecord owner, String reason) {
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
                    synchronized (mService) {
                        if (r.app != null) {
                            mService.logAppTooSlow(r.app, r.pauseTime, "pausing " + r);
                        }
                        activityPausedLocked(r.appToken, true);
                    }
                } break;
                case LAUNCH_TICK_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    synchronized (mService) {
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
                    synchronized (mService) {
                        activityDestroyedLocked(r != null ? r.appToken : null, "destroyTimeout");
                    }
                } break;
                case STOP_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity stop timeout for " + r);
                    synchronized (mService) {
                        if (r.isInHistory()) {
                            r.activityStoppedLocked(null /* icicle */,
                                    null /* persistentState */, null /* description */);
                        }
                    }
                } break;
                case DESTROY_ACTIVITIES_MSG: {
                    ScheduleDestroyArgs args = (ScheduleDestroyArgs)msg.obj;
                    synchronized (mService) {
                        destroyActivitiesLocked(args.mOwner, args.mReason);
                    }
                } break;
                case TRANSLUCENT_TIMEOUT_MSG: {
                    synchronized (mService) {
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

    ActivityStack(ActivityStackSupervisor.ActivityDisplay display, int stackId,
            ActivityStackSupervisor supervisor, RecentTasks recentTasks, boolean onTop) {
        mStackSupervisor = supervisor;
        mService = supervisor.mService;
        mHandler = new ActivityStackHandler(mService.mHandler.getLooper());
        mWindowManager = mService.mWindowManager;
        mStackId = stackId;
        mCurrentUser = mService.mUserController.getCurrentUserIdLocked();
        mRecentTasks = recentTasks;
        mTaskPositioner = mStackId == FREEFORM_WORKSPACE_STACK_ID
                ? new LaunchingTaskPositioner() : null;
        mTmpRect2.setEmpty();
        mWindowContainerController = createStackWindowController(display.mDisplayId, onTop,
                mTmpRect2);
        mStackSupervisor.mStacks.put(mStackId, this);
        postAddToDisplay(display, mTmpRect2.isEmpty() ? null : mTmpRect2, onTop);
    }

    T createStackWindowController(int displayId, boolean onTop, Rect outBounds) {
        return (T) new StackWindowController(mStackId, this, displayId, onTop, outBounds);
    }

    T getWindowContainerController() {
        return mWindowContainerController;
    }

    /** Adds the stack to specified display and calls WindowManager to do the same. */
    void reparent(ActivityStackSupervisor.ActivityDisplay activityDisplay, boolean onTop) {
        removeFromDisplay();
        mTmpRect2.setEmpty();
        postAddToDisplay(activityDisplay, mTmpRect2.isEmpty() ? null : mTmpRect2, onTop);
        adjustFocusToNextFocusableStackLocked("reparent", true /* allowFocusSelf */);
        mStackSupervisor.resumeFocusedStackTopActivityLocked();
        // Update visibility of activities before notifying WM. This way it won't try to resize
        // windows that are no longer visible.
        mStackSupervisor.ensureActivitiesVisibleLocked(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);
        mWindowContainerController.reparent(activityDisplay.mDisplayId, mTmpRect2, onTop);
    }

    /**
     * Updates internal state after adding to new display.
     * @param activityDisplay New display to which this stack was attached.
     * @param bounds Updated bounds.
     */
    private void postAddToDisplay(ActivityStackSupervisor.ActivityDisplay activityDisplay,
            Rect bounds, boolean onTop) {
        mDisplayId = activityDisplay.mDisplayId;
        mStacks = activityDisplay.mStacks;
        mBounds = bounds != null ? new Rect(bounds) : null;
        mFullscreen = mBounds == null;
        if (mTaskPositioner != null) {
            mTaskPositioner.setDisplay(activityDisplay.mDisplay);
            mTaskPositioner.configure(mBounds);
        }
        onParentChanged();

        activityDisplay.attachStack(this, findStackInsertIndex(onTop));
        if (mStackId == DOCKED_STACK_ID) {
            // If we created a docked stack we want to resize it so it resizes all other stacks
            // in the system.
            mStackSupervisor.resizeDockedStackLocked(
                    mBounds, null, null, null, null, PRESERVE_WINDOWS);
        }
    }

    /**
     * Updates the inner state of the stack to remove it from its current parent, so it can be
     * either destroyed completely or re-parented.
     */
    private void removeFromDisplay() {
        final ActivityStackSupervisor.ActivityDisplay display = getDisplay();
        if (display != null) {
            display.detachStack(this);
        }
        mDisplayId = INVALID_DISPLAY;
        mStacks = null;
        if (mTaskPositioner != null) {
            mTaskPositioner.reset();
        }
        if (mStackId == DOCKED_STACK_ID) {
            // If we removed a docked stack we want to resize it so it resizes all other stacks
            // in the system to fullscreen.
            mStackSupervisor.resizeDockedStackLocked(
                    null, null, null, null, null, PRESERVE_WINDOWS);
        }
    }

    /** Removes the stack completely. Also calls WindowManager to do the same on its side. */
    void remove() {
        removeFromDisplay();
        mStackSupervisor.mStacks.remove(mStackId);
        mWindowContainerController.removeContainer();
        mWindowContainerController = null;
        onParentChanged();
    }

    ActivityStackSupervisor.ActivityDisplay getDisplay() {
        return mStackSupervisor.getActivityDisplay(mDisplayId);
    }

    void getDisplaySize(Point out) {
        getDisplay().mDisplay.getSize(out);
    }

    /**
     * @see ActivityStack.getStackDockedModeBounds(Rect, Rect, Rect, boolean)
     */
    void getStackDockedModeBounds(Rect currentTempTaskBounds, Rect outStackBounds,
            Rect outTempTaskBounds, boolean ignoreVisibility) {
        mWindowContainerController.getStackDockedModeBounds(currentTempTaskBounds,
                outStackBounds, outTempTaskBounds, ignoreVisibility);
    }

    void prepareFreezingTaskBounds() {
        mWindowContainerController.prepareFreezingTaskBounds();
    }

    void getWindowContainerBounds(Rect outBounds) {
        if (mWindowContainerController != null) {
            mWindowContainerController.getBounds(outBounds);
            return;
        }
        outBounds.setEmpty();
    }

    void getBoundsForNewConfiguration(Rect outBounds) {
        mWindowContainerController.getBoundsForNewConfiguration(outBounds);
    }

    void positionChildWindowContainerAtTop(TaskRecord child) {
        mWindowContainerController.positionChildAtTop(child.getWindowContainerController(),
                true /* includingParents */);
    }

    /**
     * Returns whether to defer the scheduling of the multi-window mode.
     */
    boolean deferScheduleMultiWindowModeChanged() {
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
        final boolean wasDeferred = mUpdateBoundsDeferred;
        mUpdateBoundsDeferred = false;
        if (wasDeferred && mUpdateBoundsDeferredCalled) {
            resize(mDeferredBounds.isEmpty() ? null : mDeferredBounds,
                    mDeferredTaskBounds.isEmpty() ? null : mDeferredTaskBounds,
                    mDeferredTaskInsetBounds.isEmpty() ? null : mDeferredTaskInsetBounds);
        }
    }

    boolean updateBoundsAllowed(Rect bounds, Rect tempTaskBounds,
            Rect tempTaskInsetBounds) {
        if (!mUpdateBoundsDeferred) {
            return true;
        }
        if (bounds != null) {
            mDeferredBounds.set(bounds);
        } else {
            mDeferredBounds.setEmpty();
        }
        if (tempTaskBounds != null) {
            mDeferredTaskBounds.set(tempTaskBounds);
        } else {
            mDeferredTaskBounds.setEmpty();
        }
        if (tempTaskInsetBounds != null) {
            mDeferredTaskInsetBounds.set(tempTaskInsetBounds);
        } else {
            mDeferredTaskInsetBounds.setEmpty();
        }
        mUpdateBoundsDeferredCalled = true;
        return false;
    }

    void setBounds(Rect bounds) {
        mBounds = mFullscreen ? null : new Rect(bounds);
        if (mTaskPositioner != null) {
            mTaskPositioner.configure(bounds);
        }
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

    private ActivityRecord topRunningActivityLocked(boolean focusableOnly) {
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
     * This is a simplified version of topRunningActivityLocked that provides a number of
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

    final ActivityRecord topActivity() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing) {
                    return r;
                }
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

    final TaskRecord bottomTask() {
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
        final TaskRecord task = r.getTask();
        final ActivityStack stack = r.getStack();
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

    final boolean isHomeStack() {
        return mStackId == HOME_STACK_ID;
    }

    final boolean isRecentsStack() {
        return mStackId == RECENTS_STACK_ID;
    }

    final boolean isHomeOrRecentsStack() {
        return StackId.isHomeOrRecentsStack(mStackId);
    }

    final boolean isDockedStack() {
        return mStackId == DOCKED_STACK_ID;
    }

    final boolean isPinnedStack() {
        return mStackId == PINNED_STACK_ID;
    }

    final boolean isAssistantStack() {
        return mStackId == ASSISTANT_STACK_ID;
    }

    final boolean isOnHomeDisplay() {
        return isAttached() && mDisplayId == DEFAULT_DISPLAY;
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

        mStacks.remove(this);
        mStacks.add(findStackInsertIndex(ON_TOP), this);
        mStackSupervisor.setFocusStackUnchecked(reason, this);
        if (task != null) {
            insertTaskAtTop(task, null);
            return;
        }

        task = topTask();
        if (task != null) {
            mWindowContainerController.positionChildAtTop(task.getWindowContainerController(),
                    true /* includingParents */);
        }
    }

    /**
     * @param task If non-null, the task will be moved to the back of the stack.
     * */
    private void moveToBack(TaskRecord task) {
        if (!isAttached()) {
            return;
        }

        mStacks.remove(this);
        mStacks.add(0, this);

        if (task != null) {
            mTaskHistory.remove(task);
            mTaskHistory.add(0, task);
            updateTaskMovement(task, false);
            mWindowContainerController.positionChildAtBottom(task.getWindowContainerController());
        }
    }

    /**
     * @return the index to insert a new stack into, taking the always-on-top stacks into account.
     */
    private int findStackInsertIndex(boolean onTop) {
        if (onTop) {
            int addIndex = mStacks.size();
            if (addIndex > 0) {
                final ActivityStack topStack = mStacks.get(addIndex - 1);
                if (StackId.isAlwaysOnTop(topStack.mStackId) && topStack != this) {
                    // If the top stack is always on top, we move this stack just below it.
                    addIndex--;
                }
            }
            return addIndex;
        } else {
            return 0;
        }
    }

    boolean isFocusable() {
        if (StackId.canReceiveKeys(mStackId)) {
            return true;
        }
        // The stack isn't focusable. See if its top activity is focusable to force focus on the
        // stack.
        final ActivityRecord r = topRunningActivityLocked();
        return r != null && r.isFocusable();
    }

    final boolean isAttached() {
        return mStacks != null;
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
            if (r == null || r.finishing || r.userId != userId ||
                    r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": mismatch root " + r);
                continue;
            }
            if (r.mActivityType != target.mActivityType) {
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
                    + taskIntent.getComponent().flattenToShortString()
                    + "/aff=" + r.getTask().rootAffinity + " to new cls="
                    + intent.getComponent().flattenToShortString() + "/aff=" + info.taskAffinity);
            // TODO Refactor to remove duplications. Check if logic can be simplified.
            if (taskIntent != null && taskIntent.getComponent() != null &&
                    taskIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching class!");
                //dump();
                if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                        "For Intent " + intent + " bringing to top: " + r.intent);
                result.r = r;
                result.matchedByRootAffinity = false;
                break;
            } else if (affinityIntent != null && affinityIntent.getComponent() != null &&
                    affinityIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching class!");
                //dump();
                if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                        "For Intent " + intent + " bringing to top: " + r.intent);
                result.r = r;
                result.matchedByRootAffinity = false;
                break;
            } else if (!isDocument && !taskIsDocument
                    && result.r == null && task.rootAffinity != null) {
                if (task.rootAffinity.equals(target.taskAffinity)) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching affinity candidate!");
                    // It is possible for multiple tasks to have the same root affinity especially
                    // if they are in separate stacks. We save off this candidate, but keep looking
                    // to see if there is a better candidate.
                    result.r = r;
                    result.matchedByRootAffinity = true;
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
                if (!r.finishing && r.userId == userId) {
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
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "switchUserLocked: stack=" + getStackId() +
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
        setResumedActivityLocked(r, "minimalResumeActivityLocked");
        r.completeResumeLocked();
        setLaunchTime(r);
        if (DEBUG_SAVED_STATE) Slog.i(TAG_SAVED_STATE,
                "Launch completed; removing icicle of " + r.icicle);
    }

    void addRecentActivityLocked(ActivityRecord r) {
        if (r != null) {
            final TaskRecord task = r.getTask();
            mRecentTasks.addLocked(task);
            task.touchActiveTime();
        }
    }

    private void startLaunchTraces(String packageName) {
        if (mFullyDrawnStartTime != 0)  {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
        }
        Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "launching: " + packageName, 0);
        Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
    }

    private void stopFullyDrawnTraceIfNeeded() {
        if (mFullyDrawnStartTime != 0 && mLaunchStartTime == 0) {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
            mFullyDrawnStartTime = 0;
        }
    }

    void setLaunchTime(ActivityRecord r) {
        if (r.displayStartTime == 0) {
            r.fullyDrawnStartTime = r.displayStartTime = SystemClock.uptimeMillis();
            if (mLaunchStartTime == 0) {
                startLaunchTraces(r.packageName);
                mLaunchStartTime = mFullyDrawnStartTime = r.displayStartTime;
            }
        } else if (mLaunchStartTime == 0) {
            startLaunchTraces(r.packageName);
            mLaunchStartTime = mFullyDrawnStartTime = SystemClock.uptimeMillis();
        }
    }

    private void clearLaunchTime(ActivityRecord r) {
        // Make sure that there is no activity waiting for this to launch.
        if (mStackSupervisor.mWaitingActivityLaunched.isEmpty()) {
            r.displayStartTime = r.fullyDrawnStartTime = 0;
        } else {
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

                if ((userId == ar.userId) && packageName.equals(ar.packageName)) {
                    ar.info.applicationInfo = aInfo;
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

            // If we are in the middle of resuming the top activity in
            // {@link #resumeTopActivityUncheckedLocked}, mResumedActivity will be set but not
            // resumed yet. We must not proceed pausing the activity here. This method will be
            // called again if necessary as part of {@link #checkReadyForSleep} or
            // {@link ActivityStackSupervisor#checkReadyForSleepLocked}.
            if (mStackSupervisor.inResumeTopActivity) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "In the middle of resuming top activity "
                        + mResumedActivity);
            } else {
                startPausingLocked(false, true, null, false);
            }
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
        ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);

        // Make sure any paused or stopped but visible activities are now sleeping.
        // This ensures that the activity's onStop() is called.
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.state == STOPPING || r.state == STOPPED
                        || r.state == ActivityState.PAUSED || r.state == ActivityState.PAUSING) {
                    r.setSleeping(true);
                }
            }
        }
    }

    private boolean containsActivityFromStack(List<ActivityRecord> rs) {
        for (ActivityRecord r : rs) {
            if (r.getStack() == this) {
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
                    + " state=" + mPausingActivity.state);
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
                mStackSupervisor.resumeFocusedStackTopActivityLocked();
            }
            return false;
        }

        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSING: " + prev);
        else if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Start pausing: " + prev);
        mResumedActivity = null;
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        mLastNoHistoryActivity = (prev.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (prev.info.flags & ActivityInfo.FLAG_NO_HISTORY) != 0 ? prev : null;
        prev.state = ActivityState.PAUSING;
        prev.getTask().touchActiveTime();
        clearLaunchTime(prev);
        final ActivityRecord next = mStackSupervisor.topRunningActivityLocked();
        if (mService.mHasRecents
                && (next == null || next.noDisplay || next.getTask() != prev.getTask()
                || uiSleeping)) {
            prev.mUpdateTaskThumbnailWhenHidden = true;
        }
        stopFullyDrawnTraceIfNeeded();

        mService.updateCpuStats();

        if (prev.app != null && prev.app.thread != null) {
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueueing pending pause: " + prev);
            try {
                EventLog.writeEvent(EventLogTags.AM_PAUSE_ACTIVITY,
                        prev.userId, System.identityHashCode(prev),
                        prev.shortComponentName);
                mService.updateUsageStats(prev, false);
                prev.app.thread.schedulePauseActivity(prev.appToken, prev.finishing,
                        userLeaving, prev.configChangeFlags, pauseImmediately);
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
                mStackSupervisor.resumeFocusedStackTopActivityLocked();
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
                        r.userId, System.identityHashCode(r), r.shortComponentName,
                        mPausingActivity != null
                            ? mPausingActivity.shortComponentName : "(none)");
                if (r.state == ActivityState.PAUSING) {
                    r.state = ActivityState.PAUSED;
                    if (r.finishing) {
                        if (DEBUG_PAUSE) Slog.v(TAG,
                                "Executing finish of failed to pause activity: " + r);
                        finishCurrentActivityLocked(r, FINISH_AFTER_VISIBLE, false);
                    }
                }
            }
        }
        mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
    }

    private void completePauseLocked(boolean resumeNext, ActivityRecord resuming) {
        ActivityRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Complete pause: " + prev);

        if (prev != null) {
            final boolean wasStopping = prev.state == STOPPING;
            prev.state = ActivityState.PAUSED;
            if (prev.finishing) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Executing finish of activity: " + prev);
                prev = finishCurrentActivityLocked(prev, FINISH_AFTER_VISIBLE, false);
            } else if (prev.app != null) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueue pending stop if needed: " + prev
                        + " wasStopping=" + wasStopping + " visible=" + prev.visible);
                if (mStackSupervisor.mActivitiesWaitingForVisibleActivity.remove(prev)) {
                    if (DEBUG_SWITCH || DEBUG_PAUSE) Slog.v(TAG_PAUSE,
                            "Complete pause, no longer waiting: " + prev);
                }
                if (prev.deferRelaunchUntilPaused) {
                    // Complete the deferred relaunch that was waiting for pause to complete.
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Re-launching after pause: " + prev);
                    prev.relaunchActivityLocked(false /* andResume */,
                            prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    // We are also stopping, the stop request must have gone soon after the pause.
                    // We can't clobber it, because the stop confirmation will not be handled.
                    // We don't need to schedule another stop, we only need to let it happen.
                    prev.state = STOPPING;
                } else if (!prev.visible || shouldSleepOrShutDownActivities()) {
                    // Clear out any deferred client hide we might currently have.
                    prev.setDeferHidingClient(false);
                    // If we were visible then resumeTopActivities will release resources before
                    // stopping.
                    addToStopping(prev, true /* scheduleIdle */, false /* idleDelayed */);
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
            final ActivityStack topStack = mStackSupervisor.getFocusedStack();
            if (!topStack.shouldSleepOrShutDownActivities()) {
                mStackSupervisor.resumeFocusedStackTopActivityLocked(topStack, prev, null);
            } else {
                checkReadyForSleep();
                ActivityRecord top = topStack.topRunningActivityLocked();
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the stack is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mStackSupervisor.resumeFocusedStackTopActivityLocked();
                }
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();

            if (prev.app != null && prev.cpuTimeAtResume > 0
                    && mService.mBatteryStatsService.isOnBattery()) {
                long diff = mService.mProcessCpuTracker.getCpuTimeForPid(prev.app.pid)
                        - prev.cpuTimeAtResume;
                if (diff > 0) {
                    BatteryStatsImpl bsi = mService.mBatteryStatsService.getActiveStatistics();
                    synchronized (bsi) {
                        BatteryStatsImpl.Uid.Proc ps =
                                bsi.getProcessStatsLocked(prev.info.applicationInfo.uid,
                                        prev.info.packageName);
                        if (ps != null) {
                            ps.addForegroundTimeLocked(diff);
                        }
                    }
                }
            }
            prev.cpuTimeAtResume = 0; // reset it
        }

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active pinned stack - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mStackSupervisor.mAppVisibilitiesChangedSinceLastPause
                || mService.mStackSupervisor.getStack(PINNED_STACK_ID) != null) {
            mService.mTaskChangeNotificationController.notifyTaskStackChanged();
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }

        mStackSupervisor.ensureActivitiesVisibleLocked(resuming, 0, !PRESERVE_WINDOWS);
    }

    void addToStopping(ActivityRecord r, boolean scheduleIdle, boolean idleDelayed) {
        if (!mStackSupervisor.mStoppingActivities.contains(r)) {
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

    // Find the first visible activity above the passed activity and if it is translucent return it
    // otherwise return null;
    ActivityRecord findNextTranslucentActivity(ActivityRecord r) {
        TaskRecord task = r.getTask();
        if (task == null) {
            return null;
        }

        final ActivityStack stack = task.getStack();
        if (stack == null) {
            return null;
        }

        int stackNdx = mStacks.indexOf(stack);

        ArrayList<TaskRecord> tasks = stack.mTaskHistory;
        int taskNdx = tasks.indexOf(task);

        ArrayList<ActivityRecord> activities = task.mActivities;
        int activityNdx = activities.indexOf(r) + 1;

        final int numStacks = mStacks.size();
        while (stackNdx < numStacks) {
            final ActivityStack historyStack = mStacks.get(stackNdx);
            tasks = historyStack.mTaskHistory;
            final int numTasks = tasks.size();
            while (taskNdx < numTasks) {
                final TaskRecord currentTask = tasks.get(taskNdx);
                activities = currentTask.mActivities;
                final int numActivities = activities.size();
                while (activityNdx < numActivities) {
                    final ActivityRecord activity = activities.get(activityNdx);
                    if (!activity.finishing) {
                        return historyStack.mFullscreen
                                && currentTask.mFullscreen && activity.fullscreen ? null : activity;
                    }
                    ++activityNdx;
                }
                activityNdx = 0;
                ++taskNdx;
            }
            taskNdx = 0;
            ++stackNdx;
        }

        return null;
    }

    /** Returns true if the stack contains a fullscreen task. */
    private boolean hasFullscreenTask() {
        for (int i = mTaskHistory.size() - 1; i >= 0; --i) {
            final TaskRecord task = mTaskHistory.get(i);
            if (task.mFullscreen) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the stack is translucent and can have other contents visible behind it if
     * needed. A stack is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     * @param stackBehindId The id of the stack directly behind this one.
     */
    private boolean isStackTranslucent(ActivityRecord starting, int stackBehindId) {
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

                if (!r.visible && r != starting) {
                    // Also ignore invisible activities that are not the currently starting
                    // activity (about to be visible).
                    continue;
                }

                if (r.fullscreen) {
                    // Stack isn't translucent if it has at least one fullscreen activity
                    // that is visible.
                    return false;
                }

                if (!isHomeOrRecentsStack() && r.frontOfTask && task.isOverHomeStack()
                        && !StackId.isHomeOrRecentsStack(stackBehindId) && !isAssistantStack()) {
                    // Stack isn't translucent if it's top activity should have the home stack
                    // behind it and the stack currently behind it isn't the home or recents stack
                    // or the assistant stack.
                    return false;
                }
            }
        }
        return true;
    }

    /** Returns true if the stack is currently considered visible. */
    boolean isVisible() {
        return mWindowContainerController != null && mWindowContainerController.isVisible()
                && !mForceHidden;
    }

    /**
     * Returns what the stack visibility should be: {@link #STACK_INVISIBLE} or
     * {@link #STACK_VISIBLE}.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    int shouldBeVisible(ActivityRecord starting) {
        if (!isAttached() || mForceHidden) {
            return STACK_INVISIBLE;
        }

        if (mStackSupervisor.isFrontStackOnDisplay(this) || mStackSupervisor.isFocusedStack(this)) {
            return STACK_VISIBLE;
        }

        final int stackIndex = mStacks.indexOf(this);

        if (stackIndex == mStacks.size() - 1) {
            Slog.wtf(TAG,
                    "Stack=" + this + " isn't front stack but is at the top of the stack list");
            return STACK_INVISIBLE;
        }

        // Check position and visibility of this stack relative to the front stack on its display.
        final ActivityStack topStack = getTopStackOnDisplay();
        final int topStackId = topStack.mStackId;

        if (mStackId == DOCKED_STACK_ID) {
            // If the assistant stack is focused and translucent, then the docked stack is always
            // visible
            if (topStack.isAssistantStack()) {
                return (topStack.isStackTranslucent(starting, DOCKED_STACK_ID)) ? STACK_VISIBLE
                        : STACK_INVISIBLE;
            }
            return STACK_VISIBLE;
        }

        // Set home stack to invisible when it is below but not immediately below the docked stack
        // A case would be if recents stack exists but has no tasks and is below the docked stack
        // and home stack is below recents
        if (mStackId == HOME_STACK_ID) {
            int dockedStackIndex = mStacks.indexOf(mStackSupervisor.getStack(DOCKED_STACK_ID));
            if (dockedStackIndex > stackIndex && stackIndex != dockedStackIndex - 1) {
                return STACK_INVISIBLE;
            }
        }

        // Find the first stack behind front stack that actually got something visible.
        int stackBehindTopIndex = mStacks.indexOf(topStack) - 1;
        while (stackBehindTopIndex >= 0 &&
                mStacks.get(stackBehindTopIndex).topRunningActivityLocked() == null) {
            stackBehindTopIndex--;
        }
        final int stackBehindTopId = (stackBehindTopIndex >= 0)
                ? mStacks.get(stackBehindTopIndex).mStackId : INVALID_STACK_ID;
        if (topStackId == DOCKED_STACK_ID || StackId.isAlwaysOnTop(topStackId)) {
            if (stackIndex == stackBehindTopIndex) {
                // Stacks directly behind the docked or pinned stack are always visible.
                return STACK_VISIBLE;
            } else if (StackId.isAlwaysOnTop(topStackId) && stackIndex == stackBehindTopIndex - 1) {
                // Otherwise, this stack can also be visible if it is directly behind a docked stack
                // or translucent assistant stack behind an always-on-top top-most stack
                if (stackBehindTopId == DOCKED_STACK_ID) {
                    return STACK_VISIBLE;
                } else if (stackBehindTopId == ASSISTANT_STACK_ID) {
                    return mStacks.get(stackBehindTopIndex).isStackTranslucent(starting, mStackId)
                            ? STACK_VISIBLE : STACK_INVISIBLE;
                }
            }
        }

        if (StackId.isBackdropToTranslucentActivity(topStackId)
                && topStack.isStackTranslucent(starting, stackBehindTopId)) {
            // Stacks behind the fullscreen or assistant stack with a translucent activity are
            // always visible so they can act as a backdrop to the translucent activity.
            // For example, dialog activities
            if (stackIndex == stackBehindTopIndex) {
                return STACK_VISIBLE;
            }
            if (stackBehindTopIndex >= 0) {
                if ((stackBehindTopId == DOCKED_STACK_ID
                        || stackBehindTopId == PINNED_STACK_ID)
                        && stackIndex == (stackBehindTopIndex - 1)) {
                    // The stack behind the docked or pinned stack is also visible so we can have a
                    // complete backdrop to the translucent activity when the docked stack is up.
                    return STACK_VISIBLE;
                }
            }
        }

        if (StackId.isStaticStack(mStackId)) {
            // Visibility of any static stack should have been determined by the conditions above.
            return STACK_INVISIBLE;
        }

        for (int i = stackIndex + 1; i < mStacks.size(); i++) {
            final ActivityStack stack = mStacks.get(i);

            if (!stack.mFullscreen && !stack.hasFullscreenTask()) {
                continue;
            }

            if (!StackId.isDynamicStacksVisibleBehindAllowed(stack.mStackId)) {
                // These stacks can't have any dynamic stacks visible behind them.
                return STACK_INVISIBLE;
            }

            if (!stack.isStackTranslucent(starting, INVALID_STACK_ID)) {
                return STACK_INVISIBLE;
            }
        }

        return STACK_VISIBLE;
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
     * Make sure that all activities that need to be visible (that is, they
     * currently can be seen by the user) actually are.
     */
    final void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        mTopActivityOccludesKeyguard = false;
        mTopDismissingKeyguardActivity = null;
        mStackSupervisor.mKeyguardController.beginActivityVisibilityUpdate();
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
            final int stackVisibility = shouldBeVisible(starting);
            final boolean stackInvisible = stackVisibility != STACK_VISIBLE;
            boolean behindFullscreenActivity = stackInvisible;
            boolean resumeNextActivity = mStackSupervisor.isFocusedStack(this)
                    && (isInStackLocked(starting) == null);
            boolean behindTranslucentActivity = false;
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                final TaskRecord task = mTaskHistory.get(taskNdx);
                final ArrayList<ActivityRecord> activities = task.mActivities;
                for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                    final ActivityRecord r = activities.get(activityNdx);
                    if (r.finishing) {
                        // Normally the screenshot will be taken in makeInvisible(). When an activity
                        // is finishing, we no longer change its visibility, but we still need to take
                        // the screenshots if startPausingLocked decided it should be taken.
                        if (r.mUpdateTaskThumbnailWhenHidden) {
                            r.updateThumbnailLocked(r.screenshotActivityLocked(),
                                    null /* description */);
                            r.mUpdateTaskThumbnailWhenHidden = false;
                        }
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
                    r.visibleIgnoringKeyguard = visibleIgnoringKeyguard;

                    // Now check whether it's really visible depending on Keyguard state.
                    final boolean reallyVisible = checkKeyguardVisibility(r,
                            visibleIgnoringKeyguard, isTop);
                    if (visibleIgnoringKeyguard) {
                        behindFullscreenActivity = updateBehindFullscreen(stackInvisible,
                                behindFullscreenActivity, task, r);
                        if (behindFullscreenActivity && !r.fullscreen) {
                            behindTranslucentActivity = true;
                        }
                    }
                    if (reallyVisible) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Make visible? " + r
                                + " finishing=" + r.finishing + " state=" + r.state);
                        // First: if this is not the current activity being started, make
                        // sure it matches the current configuration.
                        if (r != starting) {
                            r.ensureActivityConfigurationLocked(0 /* globalChanges */, preserveWindows);
                        }

                        if (r.app == null || r.app.thread == null) {
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

                            if (r.handleAlreadyVisible()) {
                                resumeNextActivity = false;
                            }
                        } else {
                            r.makeVisibleIfNeeded(starting);
                        }
                        // Aggregate current change flags.
                        configChanges |= r.configChangeFlags;
                    } else {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Make invisible? " + r
                                + " finishing=" + r.finishing + " state=" + r.state + " stackInvisible="
                                + stackInvisible + " behindFullscreenActivity="
                                + behindFullscreenActivity + " mLaunchTaskBehind="
                                + r.mLaunchTaskBehind);
                        makeInvisible(r);
                    }
                }
                if (mStackId == FREEFORM_WORKSPACE_STACK_ID) {
                    // The visibility of tasks and the activities they contain in freeform stack are
                    // determined individually unlike other stacks where the visibility or fullscreen
                    // status of an activity in a previous task affects other.
                    behindFullscreenActivity = stackVisibility == STACK_INVISIBLE;
                } else if (mStackId == HOME_STACK_ID) {
                    if (task.isHomeTask()) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Home task: at " + task
                                + " stackInvisible=" + stackInvisible
                                + " behindFullscreenActivity=" + behindFullscreenActivity);
                        // No other task in the home stack should be visible behind the home activity.
                        // Home activities is usually a translucent activity with the wallpaper behind
                        // them. However, when they don't have the wallpaper behind them, we want to
                        // show activities in the next application stack behind them vs. another
                        // task in the home stack like recents.
                        behindFullscreenActivity = true;
                    } else if (task.isRecentsTask()
                            && task.getTaskToReturnTo() == APPLICATION_ACTIVITY_TYPE) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                                "Recents task returning to app: at " + task
                                        + " stackInvisible=" + stackInvisible
                                        + " behindFullscreenActivity=" + behindFullscreenActivity);
                        // We don't want any other tasks in the home stack visible if the recents
                        // activity is going to be returning to an application activity type.
                        // We do this to preserve the visible order the user used to get into the
                        // recents activity. The recents activity is normally translucent and if it
                        // doesn't have the wallpaper behind it the next activity in the home stack
                        // shouldn't be visible when the home stack is brought to the front to display
                        // the recents activity from an app.
                        behindFullscreenActivity = true;
                    }
                } else if (mStackId == FULLSCREEN_WORKSPACE_STACK_ID) {
                    if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Skipping after task=" + task
                            + " returning to non-application type=" + task.getTaskToReturnTo());
                    // Once we reach a fullscreen stack task that has a running activity and should
                    // return to another stack task, then no other activities behind that one should
                    // be visible.
                    if (task.topRunningActivityLocked() != null &&
                            task.getTaskToReturnTo() != APPLICATION_ACTIVITY_TYPE) {
                        behindFullscreenActivity = true;
                    }
                }
            }

            if (mTranslucentActivityWaiting != null &&
                    mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
                // Nothing is getting drawn or everything was already visible, don't wait for timeout.
                notifyActivityDrawnLocked(null);
            }
        } finally {
            mStackSupervisor.mKeyguardController.endActivityVisibilityUpdate();
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
    boolean checkKeyguardVisibility(ActivityRecord r, boolean shouldBeVisible,
            boolean isTop) {
        final boolean isInPinnedStack = r.getStack().getStackId() == PINNED_STACK_ID;
        final boolean keyguardShowing = mStackSupervisor.mKeyguardController.isKeyguardShowing(
                mDisplayId != INVALID_DISPLAY ? mDisplayId : DEFAULT_DISPLAY);
        final boolean keyguardLocked = mStackSupervisor.mKeyguardController.isKeyguardLocked();
        final boolean showWhenLocked = r.canShowWhenLocked() && !isInPinnedStack;
        final boolean dismissKeyguard = r.hasDismissKeyguardWindows();
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
                    && mStackSupervisor.mKeyguardController.canDismissKeyguard();
            if (canShowWithKeyguard) {
                return true;
            }
        }
        if (keyguardShowing) {

            // If keyguard is showing, nothing is visible, except if we are able to dismiss Keyguard
            // right away.
            return shouldBeVisible && mStackSupervisor.mKeyguardController
                    .canShowActivityWhileKeyguardShowing(r, dismissKeyguard);
        } else if (keyguardLocked) {
            return shouldBeVisible && mStackSupervisor.mKeyguardController.canShowWhileOccluded(
                    dismissKeyguard, showWhenLocked);
        } else {
            return shouldBeVisible;
        }
    }

    /**
     * Check if the display to which this stack is attached has
     * {@link Display#FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD} applied.
     */
    private boolean canShowWithInsecureKeyguard() {
        final ActivityStackSupervisor.ActivityDisplay activityDisplay = getDisplay();
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
                mStackSupervisor.startSpecificActivityLocked(r, andResume, false);
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
        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Making invisible: " + r + " " + r.state);
        try {
            final boolean canEnterPictureInPicture = r.checkEnterPictureInPictureState(
                    "makeInvisible", true /* beforeStopping */);
            // Defer telling the client it is hidden if it can enter Pip and isn't current stopped
            // or stopping. This gives it a chance to enter Pip in onPause().
            final boolean deferHidingClient = canEnterPictureInPicture
                    && r.state != STOPPING && r.state != STOPPED;
            r.setDeferHidingClient(deferHidingClient);
            r.setVisible(false);

            switch (r.state) {
                case STOPPING:
                case STOPPED:
                    if (r.app != null && r.app.thread != null) {
                        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                                "Scheduling invisibility: " + r);
                        r.app.thread.scheduleWindowVisibility(r.appToken, false);
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
                            canEnterPictureInPicture /* idleDelayed */);
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
            TaskRecord task, ActivityRecord r) {
        if (r.fullscreen) {
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Fullscreen: at " + r
                        + " stackInvisible=" + stackInvisible
                        + " behindFullscreenActivity=" + behindFullscreenActivity);
            // At this point, nothing else needs to be shown in this task.
            behindFullscreenActivity = true;
        } else if (!isHomeOrRecentsStack() && r.frontOfTask && task.isOverHomeStack()) {
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Showing home: at " + r
                    + " stackInvisible=" + stackInvisible
                    + " behindFullscreenActivity=" + behindFullscreenActivity);
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
                if (waitingActivity.app != null && waitingActivity.app.thread != null) {
                    try {
                        waitingActivity.app.thread.scheduleTranslucentConversionComplete(
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

        if (shouldBeVisible(null) == STACK_INVISIBLE) {
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
     *       Use {@link ActivityStackSupervisor#resumeFocusedStackTopActivityLocked} to resume the
     *       right activity for the current system state.
     */
    boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (mStackSupervisor.inResumeTopActivity) {
            // Don't even start recursing.
            return false;
        }

        boolean result = false;
        try {
            // Protect against recursion.
            mStackSupervisor.inResumeTopActivity = true;
            result = resumeTopActivityInnerLocked(prev, options);
        } finally {
            mStackSupervisor.inResumeTopActivity = false;
        }

        // When resuming the top activity, it may be necessary to pause the top activity (for
        // example, returning to the lock screen. We suppress the normal pause logic in
        // {@link #resumeTopActivityUncheckedLocked}, since the top activity is resumed at the end.
        // We call the {@link ActivityStackSupervisor#checkReadyForSleepLocked} again here to ensure
        // any necessary pause logic occurs. In the case where the Activity will be shown regardless
        // of the lock screen, the call to {@link ActivityStackSupervisor#checkReadyForSleepLocked}
        // is skipped.
        final ActivityRecord next = topRunningActivityLocked(true /* focusableOnly */);
        if (next == null || !next.canTurnScreenOn()) {
            checkReadyForSleep();
        }

        return result;
    }

    void setResumedActivityLocked(ActivityRecord r, String reason) {
        // TODO: move mResumedActivity to stack supervisor,
        // there should only be 1 global copy of resumed activity.
        mResumedActivity = r;
        r.state = ActivityState.RESUMED;
        mService.setResumedActivityUncheckLocked(r, reason);
        final TaskRecord task = r.getTask();
        task.touchActiveTime();
        mRecentTasks.addLocked(task);
    }

    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        if (!mService.mBooting && !mService.mBooted) {
            // Not ready yet!
            return false;
        }

        // Find the next top-most activity to resume in this stack that is not finishing and is
        // focusable. If it is not focusable, we will fall into the case below to resume the
        // top activity in the next focusable task.
        final ActivityRecord next = topRunningActivityLocked(true /* focusableOnly */);

        final boolean hasRunningActivity = next != null;

        // TODO: Maybe this entire condition can get removed?
        if (hasRunningActivity && getDisplay() == null) {
            return false;
        }

        mStackSupervisor.cancelInitializingActivities();

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        final boolean userLeaving = mStackSupervisor.mUserLeaving;
        mStackSupervisor.mUserLeaving = false;

        if (!hasRunningActivity) {
            // There are no activities left in the stack, let's look somewhere else.
            return resumeTopActivityInNextFocusableStack(prev, options, "noMoreActivities");
        }

        next.delayedResume = false;

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.state == ActivityState.RESUMED &&
                    mStackSupervisor.allResumedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed " + next);
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return false;
        }

        final TaskRecord nextTask = next.getTask();
        final TaskRecord prevTask = prev != null ? prev.getTask() : null;
        if (prevTask != null && prevTask.getStack() == this &&
                prevTask.isOverHomeStack() && prev.finishing && prev.frontOfTask) {
            if (DEBUG_STACK)  mStackSupervisor.validateTopActivitiesLocked();
            if (prevTask == nextTask) {
                prevTask.setFrontOfTask();
            } else if (prevTask != topTask()) {
                // This task is going away but it was supposed to return to the home stack.
                // Now the task above it has to return to the home task instead.
                final int taskNdx = mTaskHistory.indexOf(prevTask) + 1;
                mTaskHistory.get(taskNdx).setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            } else if (!isOnHomeDisplay()) {
                return false;
            } else if (!isHomeStack()){
                if (DEBUG_STATES) Slog.d(TAG_STATES,
                        "resumeTopActivityLocked: Launching home next");
                return isOnHomeDisplay() &&
                        mStackSupervisor.resumeHomeStackTask(prev, "prevFinished");
            }
        }

        // If we are sleeping, and there is no resumed activity, and the top
        // activity is paused, well that is the state we want.
        if (shouldSleepOrShutDownActivities()
                && mLastPausedActivity == next
                && mStackSupervisor.allPausedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Going to sleep and all paused");
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return false;
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (!mService.mUserController.hasStartedUserState(next.userId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.userId + " is stopped");
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStackSupervisor.mStoppingActivities.remove(next);
        mStackSupervisor.mGoingToSleepActivities.remove(next);
        next.sleeping = false;
        mStackSupervisor.mActivitiesWaitingForVisibleActivity.remove(next);

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything until that is done.
        if (!mStackSupervisor.allPausedActivitiesComplete()) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "resumeTopActivityLocked: Skip resume: some activity pausing.");
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return false;
        }

        mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        boolean lastResumedCanPip = false;
        final ActivityStack lastFocusedStack = mStackSupervisor.getLastStack();
        if (lastFocusedStack != null && lastFocusedStack != this) {
            // So, why aren't we using prev here??? See the param comment on the method. prev doesn't
            // represent the last resumed activity. However, the last focus stack does if it isn't null.
            final ActivityRecord lastResumed = lastFocusedStack.mResumedActivity;
            lastResumedCanPip = lastResumed != null && lastResumed.checkEnterPictureInPictureState(
                    "resumeTopActivity", userLeaving /* beforeStopping */);
        }
        // If the flag RESUME_WHILE_PAUSING is set, then continue to schedule the previous activity
        // to be paused, while at the same time resuming the new resume activity only if the
        // previous activity can't go into Pip since we want to give Pip activities a chance to
        // enter Pip before resuming the next activity.
        final boolean resumeWhilePausing = (next.info.flags & FLAG_RESUME_WHILE_PAUSING) != 0
                && !lastResumedCanPip;

        boolean pausing = mStackSupervisor.pauseBackStacks(userLeaving, next, false);
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
            if (next.app != null && next.app.thread != null) {
                mService.updateLruProcessLocked(next.app, true, null);
            }
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
            return true;
        } else if (mResumedActivity == next && next.state == ActivityState.RESUMED &&
                mStackSupervisor.allResumedActivitiesComplete()) {
            // It is possible for the activity to be resumed when we paused back stacks above if the
            // next activity doesn't have to wait for pause to complete.
            // So, nothing else to-do except:
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed (dontWaitForPause) " + next);
            if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
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

        if (prev != null && prev != next) {
            if (!mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(prev)
                    && next != null && !next.nowVisible) {
                mStackSupervisor.mActivitiesWaitingForVisibleActivity.add(prev);
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Resuming top, waiting visible to hide: " + prev);
            } else {
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
                            "Not waiting for visible to hide: " + prev + ", waitingVisible="
                            + mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(prev)
                            + ", nowVisible=" + next.nowVisible);
                } else {
                    if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                            "Previous already visible but still waiting to hide: " + prev
                            + ", waitingVisible="
                            + mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(prev)
                            + ", nowVisible=" + next.nowVisible);
                }
            }
        }

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    next.packageName, false, next.userId); /* TODO: Verify if correct userid */
        } catch (RemoteException e1) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        boolean anim = true;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare close transition: prev=" + prev);
                if (mNoAnimActivities.contains(prev)) {
                    anim = false;
                    mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    mWindowManager.prepareAppTransition(prev.getTask() == next.getTask()
                            ? TRANSIT_ACTIVITY_CLOSE
                            : TRANSIT_TASK_CLOSE, false);
                }
                prev.setVisibility(false);
            } else {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare open transition: prev=" + prev);
                if (mNoAnimActivities.contains(next)) {
                    anim = false;
                    mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    mWindowManager.prepareAppTransition(prev.getTask() == next.getTask()
                            ? TRANSIT_ACTIVITY_OPEN
                            : next.mLaunchTaskBehind
                                    ? TRANSIT_TASK_OPEN_BEHIND
                                    : TRANSIT_TASK_OPEN, false);
                }
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
            if (mNoAnimActivities.contains(next)) {
                anim = false;
                mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
            } else {
                mWindowManager.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false);
            }
        }

        Bundle resumeAnimOptions = null;
        if (anim) {
            ActivityOptions opts = next.getOptionsForTargetActivityLocked();
            if (opts != null) {
                resumeAnimOptions = opts.toBundle();
            }
            next.applyOptionsLocked();
        } else {
            next.clearOptionsLocked();
        }

        ActivityStack lastStack = mStackSupervisor.getLastStack();
        if (next.app != null && next.app.thread != null) {
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resume running: " + next
                    + " stopped=" + next.stopped + " visible=" + next.visible);

            // If the previous activity is translucent, force a visibility update of
            // the next activity, so that it's added to WM's opening app list, and
            // transition animation can be set up properly.
            // For example, pressing Home button with a translucent activity in focus.
            // Launcher is already visible in this case. If we don't add it to opening
            // apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
            // TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
            final boolean lastActivityTranslucent = lastStack != null
                    && (!lastStack.mFullscreen
                    || (lastStack.mLastPausedActivity != null
                    && !lastStack.mLastPausedActivity.fullscreen));

            // The contained logic must be synchronized, since we are both changing the visibility
            // and updating the {@link Configuration}. {@link ActivityRecord#setVisibility} will
            // ultimately cause the client code to schedule a layout. Since layouts retrieve the
            // current {@link Configuration}, we must ensure that the below code updates it before
            // the layout can occur.
            synchronized(mWindowManager.getWindowManagerLock()) {
                // This activity is now becoming visible.
                if (!next.visible || next.stopped || lastActivityTranslucent) {
                    next.setVisibility(true);
                }

                // schedule launch ticks to collect information about slow apps.
                next.startLaunchTickingLocked();

                ActivityRecord lastResumedActivity =
                        lastStack == null ? null :lastStack.mResumedActivity;
                ActivityState lastState = next.state;

                mService.updateCpuStats();

                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + next
                        + " (in existing)");

                setResumedActivityLocked(next, "resumeTopActivityInnerLocked");

                mService.updateLruProcessLocked(next.app, true, null);
                updateLRUListLocked(next);
                mService.updateOomAdjLocked();

                // Have the window manager re-evaluate the orientation of
                // the screen based on the new activity order.
                boolean notUpdated = true;

                if (mStackSupervisor.isFocusedStack(this)) {

                    // We have special rotation behavior when Keyguard is locked. Make sure all
                    // activity visibilities are set correctly as well as the transition is updated
                    // if needed to get the correct rotation behavior.
                    // TODO: Remove this once visibilities are set correctly immediately when
                    // starting an activity.
                    if (mStackSupervisor.mKeyguardController.isKeyguardLocked()) {
                        mStackSupervisor.ensureActivitiesVisibleLocked(null /* starting */,
                                0 /* configChanges */, false /* preserveWindows */);
                    }
                    final Configuration config = mWindowManager.updateOrientationFromAppTokens(
                            mStackSupervisor.getDisplayOverrideConfiguration(mDisplayId),
                            next.mayFreezeScreenLocked(next.app) ? next.appToken : null,
                                    mDisplayId);
                    if (config != null) {
                        next.frozenBeforeDestroy = true;
                    }
                    notUpdated = !mService.updateDisplayOverrideConfigurationLocked(config, next,
                            false /* deferResume */, mDisplayId);
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
                    if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
                    return true;
                }

                try {
                    // Deliver all pending results.
                    ArrayList<ResultInfo> a = next.results;
                    if (a != null) {
                        final int N = a.size();
                        if (!next.finishing && N > 0) {
                            if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                                    "Delivering results to " + next + ": " + a);
                            next.app.thread.scheduleSendResult(next.appToken, a);
                        }
                    }

                    if (next.newIntents != null) {
                        next.app.thread.scheduleNewIntent(
                                next.newIntents, next.appToken, false /* andPause */);
                    }

                    // Well the app will no longer be stopped.
                    // Clear app token stopped state in window manager if needed.
                    next.notifyAppResumed(next.stopped);

                    EventLog.writeEvent(EventLogTags.AM_RESUME_ACTIVITY, next.userId,
                            System.identityHashCode(next), next.getTask().taskId,
                            next.shortComponentName);

                    next.sleeping = false;
                    mService.showUnsupportedZoomDialogIfNeededLocked(next);
                    mService.showAskCompatModeDialogLocked(next);
                    next.app.pendingUiClean = true;
                    next.app.forceProcessStateUpTo(mService.mTopProcessState);
                    next.clearOptionsLocked();
                    next.app.thread.scheduleResumeActivity(next.appToken, next.app.repProcState,
                            mService.isNextTransitionForward(), resumeAnimOptions);

                    if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Resumed "
                            + next);
                } catch (Exception e) {
                    // Whoops, need to restart this activity!
                    if (DEBUG_STATES) Slog.v(TAG_STATES, "Resume failed; resetting state to "
                            + lastState + ": " + next);
                    next.state = lastState;
                    if (lastStack != null) {
                        lastStack.mResumedActivity = lastResumedActivity;
                    }
                    Slog.i(TAG, "Restarting because process died: " + next);
                    if (!next.hasBeenLaunched) {
                        next.hasBeenLaunched = true;
                    } else  if (SHOW_APP_STARTING_PREVIEW && lastStack != null &&
                            mStackSupervisor.isFrontStackOnDisplay(lastStack)) {
                        next.showStartingWindow(null /* prev */, false /* newTask */,
                                false /* taskSwitch */);
                    }
                    mStackSupervisor.startSpecificActivityLocked(next, true, false);
                    if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
                    return true;
                }
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
                if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
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

        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        return true;
    }

    private boolean resumeTopActivityInNextFocusableStack(ActivityRecord prev,
            ActivityOptions options, String reason) {
        if ((!mFullscreen || !isOnHomeDisplay()) && adjustFocusToNextFocusableStackLocked(reason)) {
            // Try to move focus to the next visible stack with a running activity if this
            // stack is not covering the entire screen or is on a secondary display (with no home
            // stack).
            return mStackSupervisor.resumeFocusedStackTopActivityLocked(
                    mStackSupervisor.getFocusedStack(), prev, null);
        }

        // Let's just start up the Launcher...
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeTopActivityInNextFocusableStack: " + reason + ", go home");
        if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
        // Only resume home if on home display
        return isOnHomeDisplay() &&
                mStackSupervisor.resumeHomeStackTask(prev, reason);
    }

    private TaskRecord getNextTask(TaskRecord targetTask) {
        final int index = mTaskHistory.indexOf(targetTask);
        if (index >= 0) {
            final int numTasks = mTaskHistory.size();
            for (int i = index + 1; i < numTasks; ++i) {
                TaskRecord task = mTaskHistory.get(i);
                if (task.userId == targetTask.userId) {
                    return task;
                }
            }
        }
        return null;
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
     * @see ActivityManagerService#positionTaskInStack(int, int, int).
     */
    private void insertTaskAtPosition(TaskRecord task, int position) {
        if (position >= mTaskHistory.size()) {
            insertTaskAtTop(task, null);
            return;
        }
        position = getAdjustedPositionForTask(task, position, null /* starting */);
        mTaskHistory.remove(task);
        mTaskHistory.add(position, task);
        mWindowContainerController.positionChildAt(task.getWindowContainerController(), position,
                task.mBounds, task.getOverrideConfiguration());
        updateTaskMovement(task, true);
    }

    private void insertTaskAtTop(TaskRecord task, ActivityRecord starting) {
        updateTaskReturnToForTopInsertion(task);
        // TODO: Better place to put all the code below...may be addTask...
        mTaskHistory.remove(task);
        // Now put task at top.
        final int position = getAdjustedPositionForTask(task, mTaskHistory.size(), starting);
        mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        mWindowContainerController.positionChildAtTop(task.getWindowContainerController(),
                true /* includingParents */);
    }

    /**
     * Updates the {@param task}'s return type before it is moved to the top.
     */
    private void updateTaskReturnToForTopInsertion(TaskRecord task) {
        boolean isLastTaskOverHome = false;
        // If the moving task is over the home or assistant stack, transfer its return type to next
        // task so that they return to the same stack
        if (task.isOverHomeStack() || task.isOverAssistantStack()) {
            final TaskRecord nextTask = getNextTask(task);
            if (nextTask != null) {
                nextTask.setTaskToReturnTo(task.getTaskToReturnTo());
            } else {
                isLastTaskOverHome = true;
            }
        }

        // If this is not on the default display, then just set the return type to application
        if (!isOnHomeDisplay()) {
            task.setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
            return;
        }

        final ActivityStack lastStack = mStackSupervisor.getLastStack();

        // If there is no last task, do not set task to return to
        if (lastStack == null) {
            return;
        }

        // If the task was launched from the assistant stack, set the return type to assistant
        if (lastStack.isAssistantStack()) {
            task.setTaskToReturnTo(ASSISTANT_ACTIVITY_TYPE);
            return;
        }

        // If this is being moved to the top by another activity or being launched from the home
        // activity, set mTaskToReturnTo accordingly.
        final boolean fromHomeOrRecents = lastStack.isHomeOrRecentsStack();
        final TaskRecord topTask = lastStack.topTask();
        if (!isHomeOrRecentsStack() && (fromHomeOrRecents || topTask() != task)) {
            // If it's a last task over home - we default to keep its return to type not to
            // make underlying task focused when this one will be finished.
            int returnToType = isLastTaskOverHome
                    ? task.getTaskToReturnTo() : APPLICATION_ACTIVITY_TYPE;
            if (fromHomeOrRecents && StackId.allowTopTaskToReturnHome(mStackId)) {
                returnToType = topTask == null ? HOME_ACTIVITY_TYPE : topTask.taskType;
            }
            task.setTaskToReturnTo(returnToType);
        }
    }

    final void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity,
            boolean newTask, boolean keepCurTransition, ActivityOptions options) {
        TaskRecord rTask = r.getTask();
        final int taskId = rTask.taskId;
        // mLaunchTaskBehind tasks get placed at the back of the task stack.
        if (!r.mLaunchTaskBehind && (taskForIdLocked(taskId) == null || newTask)) {
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
                        r.createWindowContainer();
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
        final TaskRecord activityTask = r.getTask();
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
        if (r.getWindowContainerController() == null) {
            r.createWindowContainer();
        }
        task.setFrontOfTask();

        if (!isHomeOrRecentsStack() || numActivities() > 0) {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                    "Prepare open transition: starting " + r);
            if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                mWindowManager.prepareAppTransition(TRANSIT_NONE, keepCurTransition);
                mNoAnimActivities.add(r);
            } else {
                int transit = TRANSIT_ACTIVITY_OPEN;
                if (newTask) {
                    if (r.mLaunchTaskBehind) {
                        transit = TRANSIT_TASK_OPEN_BEHIND;
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
                mWindowManager.prepareAppTransition(transit, keepCurTransition);
                mNoAnimActivities.remove(r);
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
                TaskRecord prevTask = r.getTask();
                ActivityRecord prev = prevTask.topRunningActivityWithStartingWindowLocked();
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.getTask() != prevTask) {
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
        if (pipCandidate == null || pipCandidate.getStackId() == PINNED_STACK_ID) {
            // Ensure that we do not trigger entering PiP an activity on the pinned stack
            return false;
        }
        final int targetStackId = toFrontTask != null ? toFrontTask.getStackId()
                : toFrontActivity.getStackId();
        if (targetStackId == ASSISTANT_STACK_ID) {
            // Ensure the task/activity being brought forward is not the assistant
            return false;
        }
        return true;
    }

    private boolean isTaskSwitch(ActivityRecord r,
            ActivityRecord topFocusedActivity) {
        return topFocusedActivity != null && r.getTask() != topFocusedActivity.getTask();
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
                        && target.taskAffinity.equals(bottom.getTask().affinity)) {
                    // If the activity currently at the bottom has the
                    // same task affinity as the one we are moving,
                    // then merge it into the same task.
                    targetTask = bottom.getTask();
                    if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Start pushing activity " + target
                            + " out to bottom task " + targetTask);
                } else {
                    targetTask = createTaskRecord(
                            mStackSupervisor.getNextTaskIdForUserLocked(target.userId),
                            target.info, null, null, null, false, target.mActivityType);
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
                        topOptions = p.takeOptionsLocked();
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

                mWindowContainerController.positionChildAtBottom(
                        targetTask.getWindowContainerController());
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
                        topOptions = p.takeOptionsLocked();
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
                    mWindowContainerController.positionChildAtTop(
                            task.getWindowContainerController(), true /* includingParents */);

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
        boolean forceReset =
                (newActivity.info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        if (ACTIVITY_INACTIVE_RESET_TIME > 0
                && taskTop.getTask().getInactiveDuration() > ACTIVITY_INACTIVE_RESET_TIME) {
            if ((newActivity.info.flags & ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE) == 0) {
                forceReset = true;
            }
        }

        final TaskRecord task = taskTop.getTask();

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
            mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                    data, r.getUriPermissionsLocked(), r.userId);
        }

        if (DEBUG_RESULTS) Slog.v(TAG, "Send activity result to " + r
                + " : who=" + resultWho + " req=" + requestCode
                + " res=" + resultCode + " data=" + data);
        if (mResumedActivity == r && r.app != null && r.app.thread != null) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
                list.add(new ResultInfo(resultWho, requestCode,
                        resultCode, data));
                r.app.thread.scheduleSendResult(r.appToken, list);
                return;
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending result to " + r, e);
            }
        }

        r.addResultLocked(null, resultWho, requestCode, resultCode, data);
    }

    /** Returns true if the task is one of the task finishing on-top of the top running task. */
    boolean isATopFinishingTask(TaskRecord task) {
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

    private void adjustFocusedActivityStackLocked(ActivityRecord r, String reason) {
        if (!mStackSupervisor.isFocusedStack(this) ||
                ((mResumedActivity != r) && (mResumedActivity != null))) {
            return;
        }

        final ActivityRecord next = topRunningActivityLocked();
        final String myReason = reason + " adjustFocus";

        if (next != r) {
            if (next != null && StackId.keepFocusInStackIfPossible(mStackId) && isFocusable()) {
                // For freeform, docked, and pinned stacks we always keep the focus within the
                // stack as long as there is a running activity.
                return;
            } else {
                // Task is not guaranteed to be non-null. For example, destroying the
                // {@link ActivityRecord} will disassociate the task from the activity.
                final TaskRecord task = r.getTask();

                if (task == null) {
                    throw new IllegalStateException("activity no longer associated with task:" + r);
                }

                final boolean isAssistantOrOverAssistant = task.getStack().isAssistantStack() ||
                        task.isOverAssistantStack();
                if (r.frontOfTask && isATopFinishingTask(task)
                        && (task.isOverHomeStack() || isAssistantOrOverAssistant)) {
                    // For non-fullscreen or assistant stack, we want to move the focus to the next
                    // visible stack to prevent the home screen from moving to the top and obscuring
                    // other visible stacks.
                    if ((!mFullscreen || isAssistantOrOverAssistant)
                            && adjustFocusToNextFocusableStackLocked(myReason)) {
                        return;
                    }
                    // Move the home stack to the top if this stack is fullscreen or there is no
                    // other visible stack.
                    if (task.isOverHomeStack() &&
                            mStackSupervisor.moveHomeStackTaskToTop(myReason)) {
                        // Activity focus was already adjusted. Nothing else to do...
                        return;
                    }
                }
            }
        }

        mStackSupervisor.moveFocusableActivityStackToFrontLocked(
                mStackSupervisor.topRunningActivityLocked(), myReason);
    }

    /** Find next proper focusable stack and make it focused. */
    private boolean adjustFocusToNextFocusableStackLocked(String reason) {
        return adjustFocusToNextFocusableStackLocked(reason, false /* allowFocusSelf */);
    }

    /**
     * Find next proper focusable stack and make it focused.
     * @param allowFocusSelf Is the focus allowed to remain on the same stack.
     */
    private boolean adjustFocusToNextFocusableStackLocked(String reason, boolean allowFocusSelf) {
        if (isAssistantStack() && bottomTask() != null &&
                bottomTask().getTaskToReturnTo() == HOME_ACTIVITY_TYPE) {
            // If the current stack is the assistant stack, then use the return-to type to determine
            // whether to return to the home screen. This is needed to workaround an issue where
            // launching a fullscreen task (and subequently returning from that task) will cause
            // the fullscreen stack to be found as the next focusable stack below, even if the
            // assistant was launched over home.
            return mStackSupervisor.moveHomeStackTaskToTop(reason);
        }

        final ActivityStack stack = mStackSupervisor.getNextFocusableStackLocked(
                allowFocusSelf ? null : this);
        final String myReason = reason + " adjustFocusToNextFocusableStack";
        if (stack == null) {
            return false;
        }

        final ActivityRecord top = stack.topRunningActivityLocked();

        if (stack.isHomeOrRecentsStack() && (top == null || !top.visible)) {
            // If we will be focusing on the home stack next and its current top activity isn't
            // visible, then use the task return to value to determine the home task to display
            // next.
            return mStackSupervisor.moveHomeStackTaskToTop(reason);
        }

        if (stack.isAssistantStack() && top != null
                && top.getTask().getTaskToReturnTo() == HOME_ACTIVITY_TYPE) {
            // It is possible for the home stack to not be directly underneath the assistant stack.
            // For example, the assistant may start an activity in the fullscreen stack. Upon
            // returning to the assistant stack, we must ensure that the home stack is underneath
            // when appropriate.
            mStackSupervisor.moveHomeStackTaskToTop("adjustAssistantReturnToHome");
        }

        stack.moveToFront(myReason);
        return true;
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
                        // {@link adjustFocusedActivityStackLocked} would have been already called.
                        r.resumeKeyDispatchingLocked();
                        return;
                    }
                } else {
                    if (DEBUG_STATES) Slog.d(TAG_STATES, "Not finishing noHistory " + r
                            + " on stop because we're just sleeping");
                }
            }
        }

        if (r.app != null && r.app.thread != null) {
            adjustFocusedActivityStackLocked(r, "stopActivity");
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                if (DEBUG_STATES) Slog.v(TAG_STATES,
                        "Moving to STOPPING: " + r + " (stop requested)");
                r.state = STOPPING;
                if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                        "Stopping visible=" + r.visible + " for " + r);
                if (!r.visible) {
                    r.setVisible(false);
                }
                EventLogTags.writeAmStopActivity(
                        r.userId, System.identityHashCode(r), r.shortComponentName);
                r.app.thread.scheduleStopActivity(r.appToken, r.visible, r.configChangeFlags);
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
                r.state = STOPPED;
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
        mService.updateOomAdjLocked();
    }

    final TaskRecord finishTopRunningActivityLocked(ProcessRecord app, String reason) {
        ActivityRecord r = topRunningActivityLocked();
        TaskRecord finishedTask = null;
        if (r == null || r.app != app) {
            return null;
        }
        Slog.w(TAG, "  Force finishing activity "
                + r.intent.getComponent().flattenToShortString());
        finishedTask = r.getTask();
        int taskNdx = mTaskHistory.indexOf(finishedTask);
        final TaskRecord task = finishedTask;
        int activityNdx = task.mActivities.indexOf(r);
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
            if (r.state == ActivityState.RESUMED
                    || r.state == ActivityState.PAUSING
                    || r.state == ActivityState.PAUSED) {
                if (!r.isHomeActivity() || mService.mHomeProcess != r.app) {
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
                    if (r.voiceSession != null
                            && r.voiceSession.asBinder() == sessionBinder) {
                        // Inform of cancellation
                        r.clearVoiceSessionLocked();
                        try {
                            r.app.thread.scheduleLocalVoiceInteractionStarted((IBinder) r.appToken,
                                    null);
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
            mService.updateOomAdjLocked();
        }
    }

    final boolean finishActivityAffinityLocked(ActivityRecord r) {
        ArrayList<ActivityRecord> activities = r.getTask().mActivities;
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
            if (resultTo.userId != r.userId) {
                if (resultData != null) {
                    resultData.prepareToLeaveUser(r.userId);
                }
            }
            if (r.info.applicationInfo.uid > 0) {
                mService.grantUriPermissionFromIntentLocked(r.info.applicationInfo.uid,
                        resultTo.packageName, resultData,
                        resultTo.getUriPermissionsLocked(), resultTo.userId);
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode,
                                     resultData);
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
            final TaskRecord task = r.getTask();
            EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                    r.userId, System.identityHashCode(r),
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

            adjustFocusedActivityStackLocked(r, "finishActivity");

            finishActivityResultsLocked(r, resultCode, resultData);

            final boolean endTask = index <= 0 && !task.isClearingToReuseTask();
            final int transit = endTask ? TRANSIT_TASK_CLOSE : TRANSIT_ACTIVITY_CLOSE;
            if (mResumedActivity == r) {
                if (DEBUG_VISIBILITY || DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare close transition: finishing " + r);
                if (endTask) {
                    mService.mTaskChangeNotificationController.notifyTaskRemovalStarted(
                            task.taskId);
                }
                mWindowManager.prepareAppTransition(transit, false);

                // Tell window manager to prepare for this one to be removed.
                r.setVisibility(false);

                if (mPausingActivity == null) {
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish needs to pause: " + r);
                    if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                            "finish() => pause with userLeaving=false");
                    startPausingLocked(false, false, null, pauseImmediately);
                }

                if (endTask) {
                    mStackSupervisor.removeLockedTaskLocked(task);
                }
            } else if (r.state != ActivityState.PAUSING) {
                // If the activity is PAUSING, we will complete the finish once
                // it is done pausing; else we can just directly finish it here.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish not pausing: " + r);
                if (r.visible) {
                    prepareActivityHideTransitionAnimation(r, transit);
                }

                final int finishMode = (r.visible || r.nowVisible) ? FINISH_AFTER_VISIBLE
                        : FINISH_AFTER_PAUSE;
                final boolean removedActivity = finishCurrentActivityLocked(r, finishMode, oomAdj)
                        == null;

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
        mWindowManager.prepareAppTransition(transit, false);
        r.setVisibility(false);
        mWindowManager.executeAppTransition();
        if (!mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(r)) {
            mStackSupervisor.mActivitiesWaitingForVisibleActivity.add(r);
        }
    }

    static final int FINISH_IMMEDIATELY = 0;
    static final int FINISH_AFTER_PAUSE = 1;
    static final int FINISH_AFTER_VISIBLE = 2;

    final ActivityRecord finishCurrentActivityLocked(ActivityRecord r, int mode, boolean oomAdj) {
        // First things first: if this activity is currently visible,
        // and the resumed activity is not yet visible, then hold off on
        // finishing until the resumed one becomes visible.

        final ActivityRecord next = mStackSupervisor.topRunningActivityLocked();

        if (mode == FINISH_AFTER_VISIBLE && (r.visible || r.nowVisible)
                && next != null && !next.nowVisible) {
            if (!mStackSupervisor.mStoppingActivities.contains(r)) {
                addToStopping(r, false /* scheduleIdle */, false /* idleDelayed */);
            }
            if (DEBUG_STATES) Slog.v(TAG_STATES,
                    "Moving to STOPPING: "+ r + " (finish requested)");
            r.state = STOPPING;
            if (oomAdj) {
                mService.updateOomAdjLocked();
            }
            return r;
        }

        // make sure the record is cleaned out of other places.
        mStackSupervisor.mStoppingActivities.remove(r);
        mStackSupervisor.mGoingToSleepActivities.remove(r);
        mStackSupervisor.mActivitiesWaitingForVisibleActivity.remove(r);
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        final ActivityState prevState = r.state;
        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to FINISHING: " + r);
        r.state = ActivityState.FINISHING;
        final boolean finishingActivityInNonFocusedStack
                = r.getStack() != mStackSupervisor.getFocusedStack()
                && prevState == ActivityState.PAUSED && mode == FINISH_AFTER_VISIBLE;

        if (mode == FINISH_IMMEDIATELY
                || (prevState == ActivityState.PAUSED
                    && (mode == FINISH_AFTER_PAUSE || mStackId == PINNED_STACK_ID))
                || finishingActivityInNonFocusedStack
                || prevState == STOPPING
                || prevState == STOPPED
                || prevState == ActivityState.INITIALIZING) {
            r.makeFinishingLocked();
            boolean activityRemoved = destroyActivityLocked(r, true, "finish-imm");

            if (finishingActivityInNonFocusedStack) {
                // Finishing activity that was in paused state and it was in not currently focused
                // stack, need to make something visible in its place.
                mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
            }
            if (activityRemoved) {
                mStackSupervisor.resumeFocusedStackTopActivityLocked();
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
        mStackSupervisor.resumeFocusedStackTopActivityLocked();
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
                finishCurrentActivityLocked(r, FINISH_IMMEDIATELY, false);
            }
        }
        if (noActivitiesInStack) {
            remove();
        }
    }

    final boolean shouldUpRecreateTaskLocked(ActivityRecord srec, String destAffinity) {
        // Basic case: for simple app-centric recents, we need to recreate
        // the task if the affinity has changed.
        if (srec == null || srec.getTask().affinity == null ||
                !srec.getTask().affinity.equals(destAffinity)) {
            return true;
        }
        // Document-centric case: an app may be split in to multiple documents;
        // they need to re-create their task if this current activity is the root
        // of a document, unless simply finishing it will return them to the the
        // correct app behind.
        final TaskRecord task = srec.getTask();
        if (srec.frontOfTask && task != null && task.getBaseIntent() != null
                && task.getBaseIntent().isDocument()) {
            // Okay, this activity is at the root of its task.  What to do, what to do...
            if (task.getTaskToReturnTo() != ActivityRecord.APPLICATION_ACTIVITY_TYPE) {
                // Finishing won't return to an application, so we need to recreate.
                return true;
            }
            // We now need to get the task below it to determine what to do.
            int taskIdx = mTaskHistory.indexOf(task);
            if (taskIdx <= 0) {
                Slog.w(TAG, "shouldUpRecreateTask: task not in history for " + srec);
                return false;
            }
            if (taskIdx == 0) {
                // At the bottom of the stack, nothing to go back to.
                return true;
            }
            TaskRecord prevTask = mTaskHistory.get(taskIdx);
            if (!task.affinity.equals(prevTask.affinity)) {
                // These are different apps, so need to recreate.
                return true;
            }
        }
        return false;
    }

    final boolean navigateUpToLocked(ActivityRecord srec, Intent destIntent, int resultCode,
            Intent resultData) {
        final TaskRecord task = srec.getTask();
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
                            destIntent.getComponent(), 0, srec.userId);
                    int res = mService.mActivityStarter.startActivityLocked(srec.app.thread,
                            destIntent, null /*ephemeralIntent*/, null, aInfo, null /*rInfo*/, null,
                            null, parent.appToken, null, 0, -1, parent.launchedFromUid,
                            parent.launchedFromPackage, -1, parent.launchedFromUid, 0, null,
                            false, true, null, null, "navigateUpTo");
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
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        if (mPausingActivity == r) {
            mPausingActivity = null;
        }

        removeTimeoutsForActivityLocked(r);
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
            r.state = ActivityState.DESTROYED;
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
                    mService.cancelIntentSenderLocked(rec, false);
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

    void removeTimeoutsForActivityLocked(ActivityRecord r) {
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
        r.state = ActivityState.DESTROYED;
        if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during remove for activity " + r);
        r.app = null;
        r.removeWindowContainer();
        final TaskRecord task = r.getTask();
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

            if (mStackSupervisor.isFocusedStack(this) && task == topTask() &&
                    task.isOverHomeStack()) {
                mStackSupervisor.moveHomeStackTaskToTop(reason);
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
                        !REMOVE_FROM_RECENTS, PAUSE_IMMEDIATELY);
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
        // Throw away any services that have been bound by this activity.
        if (r.connections != null) {
            Iterator<ConnectionRecord> it = r.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                mService.mServices.removeConnectionLocked(c, null, r);
            }
            r.connections = null;
        }
    }

    final void scheduleDestroyActivities(ProcessRecord owner, String reason) {
        Message msg = mHandler.obtainMessage(DESTROY_ACTIVITIES_MSG);
        msg.obj = new ScheduleDestroyArgs(owner, reason);
        mHandler.sendMessage(msg);
    }

    private void destroyActivitiesLocked(ProcessRecord owner, String reason) {
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
                    if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Destroying " + r + " in state " + r.state
                            + " resumed=" + mResumedActivity
                            + " pausing=" + mPausingActivity + " for reason " + reason);
                    if (destroyActivityLocked(r, true, reason)) {
                        activityRemoved = true;
                    }
                }
            }
        }
        if (activityRemoved) {
            mStackSupervisor.resumeFocusedStackTopActivityLocked();
        }
    }

    final boolean safelyDestroyActivityLocked(ActivityRecord r, String reason) {
        if (r.isDestroyable()) {
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                    "Destroying " + r + " in state " + r.state + " resumed=" + mResumedActivity
                    + " pausing=" + mPausingActivity + " for reason " + reason);
            return destroyActivityLocked(r, true, reason);
        }
        return false;
    }

    final int releaseSomeActivitiesLocked(ProcessRecord app, ArraySet<TaskRecord> tasks,
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
                            + " in state " + activity.state + " resumed=" + mResumedActivity
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
                        + ", app=" + (r.app != null ? r.app.processName : "(null)"));
        EventLog.writeEvent(EventLogTags.AM_DESTROY_ACTIVITY,
                r.userId, System.identityHashCode(r),
                r.getTask().taskId, r.shortComponentName, reason);

        boolean removedFromHistory = false;

        cleanUpActivityLocked(r, false, false);

        final boolean hadApp = r.app != null;

        if (hadApp) {
            if (removeFromApp) {
                r.app.activities.remove(r);
                if (mService.mHeavyWeightProcess == r.app && r.app.activities.size() <= 0) {
                    mService.mHeavyWeightProcess = null;
                    mService.mHandler.sendEmptyMessage(
                            ActivityManagerService.CANCEL_HEAVY_NOTIFICATION_MSG);
                }
                if (r.app.activities.isEmpty()) {
                    // Update any services we are bound to that might care about whether
                    // their client may have activities.
                    mService.mServices.updateServiceConnectionActivitiesLocked(r.app);
                    // No longer have activities, so update LRU list and oom adj.
                    mService.updateLruProcessLocked(r.app, false, null);
                    mService.updateOomAdjLocked();
                }
            }

            boolean skipDestroy = false;

            try {
                if (DEBUG_SWITCH) Slog.i(TAG_SWITCH, "Destroying: " + r);
                r.app.thread.scheduleDestroyActivity(r.appToken, r.finishing,
                        r.configChangeFlags);
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
                r.state = ActivityState.DESTROYING;
                Message msg = mHandler.obtainMessage(DESTROY_TIMEOUT_MSG, r);
                mHandler.sendMessageDelayed(msg, DESTROY_TIMEOUT);
            } else {
                if (DEBUG_STATES) Slog.v(TAG_STATES,
                        "Moving to DESTROYED: " + r + " (destroy skipped)");
                r.state = ActivityState.DESTROYED;
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
                r.state = ActivityState.DESTROYED;
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
            ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                mHandler.removeMessages(DESTROY_TIMEOUT_MSG, r);
            }
            if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS, "activityDestroyedLocked: r=" + r);

            if (isInStackLocked(r) != null) {
                if (r.state == ActivityState.DESTROYING) {
                    cleanUpActivityLocked(r, true, false);
                    removeActivityFromHistoryLocked(r, reason);
                }
            }
            mStackSupervisor.resumeFocusedStackTopActivityLocked();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void removeHistoryRecordsForAppLocked(ArrayList<ActivityRecord> list,
            ProcessRecord app, String listName) {
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

    private boolean removeHistoryRecordsForAppLocked(ProcessRecord app) {
        removeHistoryRecordsForAppLocked(mLRUActivities, app, "mLRUActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mStoppingActivities, app,
                "mStoppingActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mGoingToSleepActivities, app,
                "mGoingToSleepActivities");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mActivitiesWaitingForVisibleActivity, app,
                "mActivitiesWaitingForVisibleActivity");
        removeHistoryRecordsForAppLocked(mStackSupervisor.mFinishingActivities, app,
                "mFinishingActivities");

        boolean hasVisibleActivities = false;

        // Clean out the history list.
        int i = numActivities();
        if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                "Removing app " + app + " from history with " + i + " entries");
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                --i;
                if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                        "Record #" + i + " " + r + ": app=" + r.app);
                if (r.app == app) {
                    if (r.visible) {
                        hasVisibleActivities = true;
                    }
                    final boolean remove;
                    if ((!r.haveState && !r.stateNotNeeded) || r.finishing) {
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
                                + " state=" + r.state + " callers=" + Debug.getCallers(5));
                        if (!r.finishing) {
                            Slog.w(TAG, "Force removing " + r + ": app died, no saved state");
                            EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                                    r.userId, System.identityHashCode(r),
                                    r.getTask().taskId, r.shortComponentName,
                                    "proc died without state saved");
                            if (r.state == ActivityState.RESUMED) {
                                mService.updateUsageStats(r, false);
                            }
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
            if (r != null && r.state != ActivityState.RESUMED) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        mWindowManager.prepareAppTransition(transit, false);
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
        mStackSupervisor.invalidateTaskLayers();
    }

    void moveHomeStackTaskToTop() {
        final int top = mTaskHistory.size() - 1;
        for (int taskNdx = top; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.taskType == HOME_ACTIVITY_TYPE) {
                if (DEBUG_TASKS || DEBUG_STACK) Slog.d(TAG_STACK,
                        "moveHomeStackTaskToTop: moving " + task);
                mTaskHistory.remove(taskNdx);
                mTaskHistory.add(top, task);
                updateTaskMovement(task, true);
                return;
            }
        }
    }

    final void moveTaskToFrontLocked(TaskRecord tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, String reason) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "moveTaskToFront: " + tr);

        final ActivityStack topStack = getTopStackOnDisplay();
        final ActivityRecord topActivity = topStack != null ? topStack.topActivity() : null;
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

        // Shift all activities with this task up to the top
        // of the stack, keeping them in the same internal order.
        insertTaskAtTop(tr, null);

        // Don't refocus if invisible to current user
        final ActivityRecord top = tr.getTopActivity();
        if (top == null || !top.okToShowLocked()) {
            addRecentActivityLocked(top);
            ActivityOptions.abort(options);
            return;
        }

        // Set focus to the top running activity of this stack.
        final ActivityRecord r = topRunningActivityLocked();
        mStackSupervisor.moveFocusableActivityStackToFrontLocked(r, reason);

        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to front transition: task=" + tr);
        if (noAnimation) {
            mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
            if (r != null) {
                mNoAnimActivities.add(r);
            }
            ActivityOptions.abort(options);
        } else {
            updateTransitLocked(TRANSIT_TASK_TO_FRONT, options);
        }
        // If a new task is moved to the front, then mark the existing top activity as supporting
        // picture-in-picture while paused only if the task would not be considered an oerlay on top
        // of the current activity (eg. not fullscreen, or the assistant)
        if (canEnterPipOnTaskSwitch(topActivity, tr, null /* toFrontActivity */,
                options)) {
            topActivity.supportsEnterPipOnTaskSwitch = true;
        }

        mStackSupervisor.resumeFocusedStackTopActivityLocked();
        EventLog.writeEvent(EventLogTags.AM_TASK_TO_FRONT, tr.userId, tr.taskId);

        mService.mTaskChangeNotificationController.notifyTaskMovedToFront(tr.taskId);
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

        // If the task is locked, then show the lock task toast
        if (mStackSupervisor.isLockedTask(tr)) {
            mStackSupervisor.showLockTaskToast();
            return false;
        }

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (mStackSupervisor.isFrontStackOnDisplay(this) && mService.mController != null) {
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

        boolean prevIsHome = false;

        // If true, we should resume the home activity next if the task we are moving to the
        // back is over the home stack. We force to false if the task we are moving to back
        // is the home task and we don't want it resumed after moving to the back.
        final boolean canGoHome = !tr.isHomeTask() && tr.isOverHomeStack();
        if (canGoHome) {
            final TaskRecord nextTask = getNextTask(tr);
            if (nextTask != null) {
                nextTask.setTaskToReturnTo(tr.getTaskToReturnTo());
            } else {
                prevIsHome = true;
            }
        }

        boolean requiresMove = mTaskHistory.indexOf(tr) != 0;
        if (requiresMove) {
            mTaskHistory.remove(tr);
            mTaskHistory.add(0, tr);
            updateTaskMovement(tr, false);

            mWindowManager.prepareAppTransition(TRANSIT_TASK_TO_BACK, false);
            mWindowContainerController.positionChildAtBottom(tr.getWindowContainerController());
        }

        if (mStackId == PINNED_STACK_ID) {
            mStackSupervisor.removeStackLocked(PINNED_STACK_ID);
            return true;
        }

        // Otherwise, there is an assumption that moving a task to the back moves it behind the
        // home activity. We make sure here that some activity in the stack will launch home.
        int numTasks = mTaskHistory.size();
        for (int taskNdx = numTasks - 1; taskNdx >= 1; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.isOverHomeStack()) {
                break;
            }
            if (taskNdx == 1) {
                // Set the last task before tr to go to home.
                task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            }
        }

        final TaskRecord task = mResumedActivity != null ? mResumedActivity.getTask() : null;
        if (prevIsHome || (task == tr && canGoHome) || (numTasks <= 1 && isOnHomeDisplay())) {
            if (!mService.mBooting && !mService.mBooted) {
                // Not ready yet!
                return false;
            }
            tr.setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
            return mStackSupervisor.resumeHomeStackTask(null, "moveTaskToBack");
        }

        mStackSupervisor.resumeFocusedStackTopActivityLocked();
        return true;
    }

    /**
     * Get the topmost stack on the current display. It may be different from focused stack, because
     * focus may be on another display.
     */
    private ActivityStack getTopStackOnDisplay() {
        final ArrayList<ActivityStack> stacks = getDisplay().mStacks;
        return stacks.isEmpty() ? null : stacks.get(stacks.size() - 1);
    }

    static void logStartActivity(int tag, ActivityRecord r, TaskRecord task) {
        final Uri data = r.intent.getData();
        final String strData = data != null ? data.toSafeString() : null;

        EventLog.writeEvent(tag,
                r.userId, System.identityHashCode(r), task.taskId,
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

        final TaskRecord startTask = start.getTask();
        boolean behindFullscreen = false;
        boolean updatedConfig = false;

        for (int taskIndex = mTaskHistory.indexOf(startTask); taskIndex >= 0; --taskIndex) {
            final TaskRecord task = mTaskHistory.get(taskIndex);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            int activityIndex =
                    (start.getTask() == task) ? activities.indexOf(start) : activities.size() - 1;
            for (; activityIndex >= 0; --activityIndex) {
                final ActivityRecord r = activities.get(activityIndex);
                updatedConfig |= r.ensureActivityConfigurationLocked(0 /* globalChanges */,
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
            mStackSupervisor.resumeFocusedStackTopActivityLocked();
        }
    }

    // TODO: Figure-out a way to consolidate with resize() method below.
    @Override
    public void requestResize(Rect bounds) {
        mService.resizeStack(mStackId, bounds, true /* allowResizeInDockedMode */,
                false /* preserveWindows */, false /* animate */, -1 /* animationDuration */);
    }

    // TODO: Can only be called from special methods in ActivityStackSupervisor.
    // Need to consolidate those calls points into this resize method so anyone can call directly.
    void resize(Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds) {
        bounds = TaskRecord.validateBounds(bounds);

        if (!updateBoundsAllowed(bounds, tempTaskBounds, tempTaskInsetBounds)) {
            return;
        }

        // Update override configurations of all tasks in the stack.
        final Rect taskBounds = tempTaskBounds != null ? tempTaskBounds : bounds;
        final Rect insetBounds = tempTaskInsetBounds != null ? tempTaskInsetBounds : taskBounds;

        mTmpBounds.clear();
        mTmpConfigs.clear();
        mTmpInsetBounds.clear();

        for (int i = mTaskHistory.size() - 1; i >= 0; i--) {
            final TaskRecord task = mTaskHistory.get(i);
            if (task.isResizeable()) {
                if (mStackId == FREEFORM_WORKSPACE_STACK_ID) {
                    // For freeform stack we don't adjust the size of the tasks to match that
                    // of the stack, but we do try to make sure the tasks are still contained
                    // with the bounds of the stack.
                    mTmpRect2.set(task.mBounds);
                    fitWithinBounds(mTmpRect2, bounds);
                    task.updateOverrideConfiguration(mTmpRect2);
                } else {
                    task.updateOverrideConfiguration(taskBounds, insetBounds);
                }
            }

            mTmpConfigs.put(task.taskId, task.getOverrideConfiguration());
            mTmpBounds.put(task.taskId, task.mBounds);
            if (tempTaskInsetBounds != null) {
                mTmpInsetBounds.put(task.taskId, tempTaskInsetBounds);
            }
        }

        mFullscreen = mWindowContainerController.resize(bounds, mTmpConfigs, mTmpBounds,
                mTmpInsetBounds);
        setBounds(bounds);
    }


    /**
     * Adjust bounds to stay within stack bounds.
     *
     * Since bounds might be outside of stack bounds, this method tries to move the bounds in a way
     * that keep them unchanged, but be contained within the stack bounds.
     *
     * @param bounds Bounds to be adjusted.
     * @param stackBounds Bounds within which the other bounds should remain.
     */
    private static void fitWithinBounds(Rect bounds, Rect stackBounds) {
        if (stackBounds == null || stackBounds.contains(bounds)) {
            return;
        }

        if (bounds.left < stackBounds.left || bounds.right > stackBounds.right) {
            final int maxRight = stackBounds.right
                    - (stackBounds.width() / FIT_WITHIN_BOUNDS_DIVIDER);
            int horizontalDiff = stackBounds.left - bounds.left;
            if ((horizontalDiff < 0 && bounds.left >= maxRight)
                    || (bounds.left + horizontalDiff >= maxRight)) {
                horizontalDiff = maxRight - bounds.left;
            }
            bounds.left += horizontalDiff;
            bounds.right += horizontalDiff;
        }

        if (bounds.top < stackBounds.top || bounds.bottom > stackBounds.bottom) {
            final int maxBottom = stackBounds.bottom
                    - (stackBounds.height() / FIT_WITHIN_BOUNDS_DIVIDER);
            int verticalDiff = stackBounds.top - bounds.top;
            if ((verticalDiff < 0 && bounds.top >= maxBottom)
                    || (bounds.top + verticalDiff >= maxBottom)) {
                verticalDiff = maxBottom - bounds.top;
            }
            bounds.top += verticalDiff;
            bounds.bottom += verticalDiff;
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
            int numActivities = activities.size();
            for (int activityNdx = 0; activityNdx < numActivities; ++activityNdx) {
                ActivityRecord r = activities.get(activityNdx);
                final boolean sameComponent =
                        (r.packageName.equals(packageName) && (filterByClasses == null
                                || filterByClasses.contains(r.realActivity.getClassName())))
                        || (packageName == null && r.userId == userId);
                if ((userId == UserHandle.USER_ALL || r.userId == userId)
                        && (sameComponent || r.getTask() == lastTask)
                        && (r.app == null || evenPersistent || !r.app.persistent)) {
                    if (!doit) {
                        if (r.finishing) {
                            // If this activity is just finishing, then it is not
                            // interesting as far as something to stop.
                            continue;
                        }
                        return true;
                    }
                    if (r.isHomeActivity()) {
                        if (homeActivity != null && homeActivity.equals(r.realActivity)) {
                            Slog.i(TAG, "Skip force-stop again " + r);
                            continue;
                        } else {
                            homeActivity = r.realActivity;
                        }
                    }
                    didSomething = true;
                    Slog.i(TAG, "  Force finishing activity " + r);
                    if (sameComponent) {
                        if (r.app != null) {
                            r.app.removed = true;
                        }
                        r.app = null;
                    }
                    lastTask = r.getTask();
                    if (finishActivityLocked(r, Activity.RESULT_CANCELED, null, "force-stop",
                            true)) {
                        // r has been deleted from mActivities, accommodate.
                        --numActivities;
                        --activityNdx;
                    }
                }
            }
        }
        return didSomething;
    }

    void getTasksLocked(List<RunningTaskInfo> list, int callingUid, boolean allowed) {
        boolean focusedStack = mStackSupervisor.getFocusedStack() == this;
        boolean topTask = true;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            if (task.getTopActivity() == null) {
                continue;
            }
            ActivityRecord r = null;
            ActivityRecord top = null;
            ActivityRecord tmp;
            int numActivities = 0;
            int numRunning = 0;
            final ArrayList<ActivityRecord> activities = task.mActivities;
            if (!allowed && !task.isHomeTask() && task.effectiveUid != callingUid) {
                continue;
            }
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                tmp = activities.get(activityNdx);
                if (tmp.finishing) {
                    continue;
                }
                r = tmp;

                // Initialize state for next task if needed.
                if (top == null || (top.state == ActivityState.INITIALIZING)) {
                    top = r;
                    numActivities = numRunning = 0;
                }

                // Add 'r' into the current task.
                numActivities++;
                if (r.app != null && r.app.thread != null) {
                    numRunning++;
                }

                if (DEBUG_ALL) Slog.v(
                    TAG, r.intent.getComponent().flattenToShortString()
                    + ": task=" + r.getTask());
            }

            RunningTaskInfo ci = new RunningTaskInfo();
            ci.id = task.taskId;
            ci.stackId = mStackId;
            ci.baseActivity = r.intent.getComponent();
            ci.topActivity = top.intent.getComponent();
            ci.lastActiveTime = task.lastActiveTime;
            if (focusedStack && topTask) {
                // Give the latest time to ensure foreground task can be sorted
                // at the first, because lastActiveTime of creating task is 0.
                ci.lastActiveTime = System.currentTimeMillis();
                topTask = false;
            }

            if (top.getTask() != null) {
                ci.description = top.getTask().lastDescription;
            }
            ci.numActivities = numActivities;
            ci.numRunning = numRunning;
            ci.supportsSplitScreenMultiWindow = task.supportsSplitScreen();
            ci.resizeMode = task.mResizeMode;
            list.add(ci);
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
    boolean handleAppDiedLocked(ProcessRecord app) {
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

    void handleAppCrashLocked(ProcessRecord app) {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<ActivityRecord> activities = mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.app == app) {
                    Slog.w(TAG, "  Force finishing activity "
                            + r.intent.getComponent().flattenToShortString());
                    // Force the destroy to skip right to removal.
                    r.app = null;
                    finishCurrentActivityLocked(r, FINISH_IMMEDIATELY, false);
                }
            }
        }
    }

    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, boolean needSep, String header) {
        boolean printed = false;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);
            printed |= ActivityStackSupervisor.dumpHistoryList(fd, pw,
                    mTaskHistory.get(taskNdx).mActivities, "    ", "Hist", true, !dumpAll,
                    dumpClient, dumpPackage, needSep, header,
                    "    Task id #" + task.taskId + "\n" +
                    "    mFullscreen=" + task.mFullscreen + "\n" +
                    "    mBounds=" + task.mBounds + "\n" +
                    "    mMinWidth=" + task.mMinWidth + "\n" +
                    "    mMinHeight=" + task.mMinHeight + "\n" +
                    "    mLastNonFullscreenBounds=" + task.mLastNonFullscreenBounds);
            if (printed) {
                header = null;
            }
        }
        return printed;
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
     * @param task to remove.
     * @param reason for removal.
     * @param mode task removal mode. Either {@link #REMOVE_TASK_MODE_DESTROYING},
     *             {@link #REMOVE_TASK_MODE_MOVING}, {@link #REMOVE_TASK_MODE_MOVING_TO_TOP}.
     */
    void removeTask(TaskRecord task, String reason, int mode) {
        for (ActivityRecord record : task.mActivities) {
            onActivityRemovedFromStack(record);
        }

        final int taskNdx = mTaskHistory.indexOf(task);
        final int topTaskNdx = mTaskHistory.size() - 1;
        if (task.isOverHomeStack() && taskNdx < topTaskNdx) {
            final TaskRecord nextTask = mTaskHistory.get(taskNdx + 1);
            if (!nextTask.isOverHomeStack() && !nextTask.isOverAssistantStack()) {
                nextTask.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            }
        }
        mTaskHistory.remove(task);
        removeActivitiesFromLRUListLocked(task);
        updateTaskMovement(task, true);

        if (mode == REMOVE_TASK_MODE_DESTROYING && task.mActivities.isEmpty()) {
            // TODO: VI what about activity?
            final boolean isVoiceSession = task.voiceSession != null;
            if (isVoiceSession) {
                try {
                    task.voiceSession.taskFinished(task.intent, task.taskId);
                } catch (RemoteException e) {
                }
            }
            if (task.autoRemoveFromRecents() || isVoiceSession) {
                // Task creator asked to remove this when done, or this task was a voice
                // interaction, so it should not remain on the recent tasks list.
                mRecentTasks.remove(task);
                task.removedFromRecents();
            }

            task.removeWindowContainer();
        }

        if (mTaskHistory.isEmpty()) {
            if (DEBUG_STACK) Slog.i(TAG_STACK, "removeTask: removing stack=" + this);
            // We only need to adjust focused stack if this stack is in focus and we are not in the
            // process of moving the task to the top of the stack that will be focused.
            if (isOnHomeDisplay() && mode != REMOVE_TASK_MODE_MOVING_TO_TOP
                    && mStackSupervisor.isFocusedStack(this)) {
                String myReason = reason + " leftTaskHistoryEmpty";
                if (mFullscreen || !adjustFocusToNextFocusableStackLocked(myReason)) {
                    mStackSupervisor.moveHomeStackToFront(myReason);
                }
            }
            if (mStacks != null) {
                mStacks.remove(this);
                mStacks.add(0, this);
            }
            if (!isHomeOrRecentsStack()) {
                remove();
            }
        }

        task.setStack(null);

        // Notify if a task from the pinned stack is being removed (or moved depending on the mode)
        if (mStackId == PINNED_STACK_ID) {
            mService.mTaskChangeNotificationController.notifyActivityUnpinned();
        }
    }

    TaskRecord createTaskRecord(int taskId, ActivityInfo info, Intent intent,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            boolean toTop, int type) {
        TaskRecord task = new TaskRecord(mService, taskId, info, intent, voiceSession,
                voiceInteractor, type);
        // add the task to stack first, mTaskPositioner might need the stack association
        addTask(task, toTop, "createTaskRecord");
        final boolean isLockscreenShown = mService.mStackSupervisor.mKeyguardController
                .isKeyguardShowing(mDisplayId != INVALID_DISPLAY ? mDisplayId : DEFAULT_DISPLAY);
        if (!layoutTaskInStack(task, info.windowLayout) && mBounds != null && task.isResizeable()
                && !isLockscreenShown) {
            task.updateOverrideConfiguration(mBounds);
        }
        task.createWindowContainer(toTop, (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
        return task;
    }

    boolean layoutTaskInStack(TaskRecord task, ActivityInfo.WindowLayout windowLayout) {
        if (mTaskPositioner == null) {
            return false;
        }
        mTaskPositioner.updateDefaultBounds(task, mTaskHistory, windowLayout);
        return true;
    }

    ArrayList<TaskRecord> getAllTasks() {
        return new ArrayList<>(mTaskHistory);
    }

    void addTask(final TaskRecord task, final boolean toTop, String reason) {
        addTask(task, toTop ? MAX_VALUE : 0, true /* schedulePictureInPictureModeChange */, reason);
        if (toTop) {
            // TODO: figure-out a way to remove this call.
            mWindowContainerController.positionChildAtTop(task.getWindowContainerController(),
                    true /* includingParents */);
        }
    }

    // TODO: This shouldn't allow automatic reparenting. Remove the call to preAddTask and deal
    // with the fall-out...
    void addTask(final TaskRecord task, int position, boolean schedulePictureInPictureModeChange,
            String reason) {
        // TODO: Is this remove really needed? Need to look into the call path for the other addTask
        mTaskHistory.remove(task);
        position = getAdjustedPositionForTask(task, position, null /* starting */);
        final boolean toTop = position >= mTaskHistory.size();
        final ActivityStack prevStack = preAddTask(task, reason, toTop);

        mTaskHistory.add(position, task);
        task.setStack(this);

        if (toTop) {
            updateTaskReturnToForTopInsertion(task);
        }

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
            mResumedActivity = topRunningActivity;
        }

        // The task might have already been running and its visibility needs to be synchronized with
        // the visibility of the stack / windows.
        ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        mStackSupervisor.resumeFocusedStackTopActivityLocked();
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

    void moveToFrontAndResumeStateIfNeeded(ActivityRecord r, boolean moveToFront, boolean setResume,
            boolean setPause, String reason) {
        if (!moveToFront) {
            return;
        }

        // If the activity owns the last resumed activity, transfer that together,
        // so that we don't resume the same activity again in the new stack.
        // Apps may depend on onResume()/onPause() being called in pairs.
        if (setResume) {
            mResumedActivity = r;
            updateLRUListLocked(r);
        }
        // If the activity was previously pausing, then ensure we transfer that as well
        if (setPause) {
            mPausingActivity = r;
            schedulePauseTimeout(r);
        }
        // Move the stack in which we are placing the activity to the front. The call will also
        // make sure the activity focus is set.
        moveToFront(reason);
    }

    public int getStackId() {
        return mStackId;
    }

    @Override
    public String toString() {
        return "ActivityStack{" + Integer.toHexString(System.identityHashCode(this))
                + " stackId=" + mStackId + ", " + mTaskHistory.size() + " tasks}";
    }

    void onLockTaskPackagesUpdatedLocked() {
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            mTaskHistory.get(taskNdx).setLockTaskAuth();
        }
    }

    void executeAppTransition(ActivityOptions options) {
        mWindowManager.executeAppTransition();
        mNoAnimActivities.clear();
        ActivityOptions.abort(options);
    }

    boolean shouldSleepActivities() {
        final ActivityStackSupervisor.ActivityDisplay display = getDisplay();
        return display != null ? display.isSleeping() : mService.isSleepingLocked();
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mService.isShuttingDownLocked();
    }
}
