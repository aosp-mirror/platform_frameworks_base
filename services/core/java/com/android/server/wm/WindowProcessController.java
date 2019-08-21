/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.os.Build.VERSION_CODES.Q;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.wm.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STARTED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ACTIVITY_BG_START_GRACE_PERIOD_MS;
import static com.android.server.wm.ActivityTaskManagerService.INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MS;
import static com.android.server.wm.ActivityTaskManagerService.KEY_DISPATCHING_TIMEOUT_MS;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.app.servertransaction.ConfigurationChangeItem;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IRemoteAnimationRunner;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.wm.ActivityTaskManagerService.HotPath;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * The Activity Manager (AM) package manages the lifecycle of processes in the system through
 * ProcessRecord. However, it is important for the Window Manager (WM) package to be aware
 * of the processes and their state since it affects how WM manages windows and activities. This
 * class that allows the ProcessRecord object in the AM package to communicate important
 * changes to its state to the WM package in a structured way. WM package also uses
 * {@link WindowProcessListener} to request changes to the process state on the AM side.
 * Note that public calls into this class are assumed to be originating from outside the
 * window manager so the window manager lock is held and appropriate permissions are checked before
 * calls are allowed to proceed.
 */
public class WindowProcessController extends ConfigurationContainer<ConfigurationContainer>
        implements ConfigurationContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowProcessController" : TAG_ATM;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;

    // all about the first app in the process
    final ApplicationInfo mInfo;
    final String mName;
    final int mUid;
    // The process of this application; 0 if none
    private volatile int mPid;
    // user of process.
    final int mUserId;
    // The owner of this window process controller object. Mainly for identification when we
    // communicate back to the activity manager side.
    public final Object mOwner;
    // List of packages running in the process
    final ArraySet<String> mPkgList = new ArraySet<>();
    private final WindowProcessListener mListener;
    private final ActivityTaskManagerService mAtm;
    // The actual proc...  may be null only if 'persistent' is true (in which case we are in the
    // process of launching the app)
    private IApplicationThread mThread;
    // Currently desired scheduling class
    private volatile int mCurSchedGroup;
    // Currently computed process state
    private volatile int mCurProcState = PROCESS_STATE_NONEXISTENT;
    // Last reported process state;
    private volatile int mRepProcState = PROCESS_STATE_NONEXISTENT;
    // are we in the process of crashing?
    private volatile boolean mCrashing;
    // does the app have a not responding dialog?
    private volatile boolean mNotResponding;
    // always keep this application running?
    private volatile boolean mPersistent;
    // The ABI this process was launched with
    private volatile String mRequiredAbi;
    // Running any services that are foreground?
    private volatile boolean mHasForegroundServices;
    // Running any activities that are foreground?
    private volatile boolean mHasForegroundActivities;
    // Are there any client services with activities?
    private volatile boolean mHasClientActivities;
    // Is this process currently showing a non-activity UI that the user is interacting with?
    // E.g. The status bar when it is expanded, but not when it is minimized. When true the process
    // will be set to use the ProcessList#SCHED_GROUP_TOP_APP scheduling group to boost performance.
    private volatile boolean mHasTopUi;
    // Is the process currently showing a non-activity UI that overlays on-top of activity UIs on
    // screen. E.g. display a window of type
    // android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY When true the process will
    // oom adj score will be set to ProcessList#PERCEPTIBLE_APP_ADJ at minimum to reduce the chance
    // of the process getting killed.
    private volatile boolean mHasOverlayUi;
    // Want to clean up resources from showing UI?
    private volatile boolean mPendingUiClean;
    // The time we sent the last interaction event
    private volatile long mInteractionEventTime;
    // When we became foreground for interaction purposes
    private volatile long mFgInteractionTime;
    // When (uptime) the process last became unimportant
    private volatile long mWhenUnimportant;
    // was app launched for debugging?
    private volatile boolean mDebugging;
    // Active instrumentation running in process?
    private volatile boolean mInstrumenting;
    // Active instrumentation with background activity starts privilege running in process?
    private volatile boolean mInstrumentingWithBackgroundActivityStartPrivileges;
    // This process it perceptible by the user.
    private volatile boolean mPerceptible;
    // Set to true when process was launched with a wrapper attached
    private volatile boolean mUsingWrapper;
    // Set to true if this process is currently temporarily whitelisted to start activities even if
    // it's not in the foreground
    private volatile boolean mAllowBackgroundActivityStarts;
    // Set of UIDs of clients currently bound to this process
    private volatile ArraySet<Integer> mBoundClientUids = new ArraySet<Integer>();

    // Thread currently set for VR scheduling
    int mVrThreadTid;

    boolean mIsImeProcess;

    // all activities running in the process
    private final ArrayList<ActivityRecord> mActivities = new ArrayList<>();
    // any tasks this process had run root activities in
    private final ArrayList<TaskRecord> mRecentTasks = new ArrayList<>();
    // The most recent top-most activity that was resumed in the process for pre-Q app.
    private ActivityRecord mPreQTopResumedActivity = null;
    // The last time an activity was launched in the process
    private long mLastActivityLaunchTime;
    // The last time an activity was finished in the process while the process participated
    // in a visible task
    private long mLastActivityFinishTime;

    // Last configuration that was reported to the process.
    private final Configuration mLastReportedConfiguration;
    // Registered display id as a listener to override config change
    private int mDisplayId;

    /** Whether our process is currently running a {@link RecentsAnimation} */
    private boolean mRunningRecentsAnimation;

    /** Whether our process is currently running a {@link IRemoteAnimationRunner} */
    private boolean mRunningRemoteAnimation;

    public WindowProcessController(ActivityTaskManagerService atm, ApplicationInfo info,
            String name, int uid, int userId, Object owner, WindowProcessListener listener) {
        mInfo = info;
        mName = name;
        mUid = uid;
        mUserId = userId;
        mOwner = owner;
        mListener = listener;
        mAtm = atm;
        mLastReportedConfiguration = new Configuration();
        mDisplayId = INVALID_DISPLAY;
        if (atm != null) {
            onConfigurationChanged(atm.getGlobalConfiguration());
        }
    }

    public void setPid(int pid) {
        mPid = pid;
    }

    public int getPid() {
        return mPid;
    }

    @HotPath(caller = HotPath.PROCESS_CHANGE)
    public void setThread(IApplicationThread thread) {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mThread = thread;
        }
    }

    IApplicationThread getThread() {
        return mThread;
    }

    boolean hasThread() {
        return mThread != null;
    }

    public void setCurrentSchedulingGroup(int curSchedGroup) {
        mCurSchedGroup = curSchedGroup;
    }

    int getCurrentSchedulingGroup() {
        return mCurSchedGroup;
    }

    public void setCurrentProcState(int curProcState) {
        mCurProcState = curProcState;
    }

    int getCurrentProcState() {
        return mCurProcState;
    }

    public void setReportedProcState(int repProcState) {
        mRepProcState = repProcState;
    }

    int getReportedProcState() {
        return mRepProcState;
    }

    public void setCrashing(boolean crashing) {
        mCrashing = crashing;
    }

    boolean isCrashing() {
        return mCrashing;
    }

    public void setNotResponding(boolean notResponding) {
        mNotResponding = notResponding;
    }

    boolean isNotResponding() {
        return mNotResponding;
    }

    public void setPersistent(boolean persistent) {
        mPersistent = persistent;
    }

    boolean isPersistent() {
        return mPersistent;
    }

    public void setHasForegroundServices(boolean hasForegroundServices) {
        mHasForegroundServices = hasForegroundServices;
    }

    boolean hasForegroundServices() {
        return mHasForegroundServices;
    }

    public void setHasForegroundActivities(boolean hasForegroundActivities) {
        mHasForegroundActivities = hasForegroundActivities;
    }

    boolean hasForegroundActivities() {
        return mHasForegroundActivities;
    }

    public void setHasClientActivities(boolean hasClientActivities) {
        mHasClientActivities = hasClientActivities;
    }

    boolean hasClientActivities() {
        return mHasClientActivities;
    }

    public void setHasTopUi(boolean hasTopUi) {
        mHasTopUi = hasTopUi;
    }

    boolean hasTopUi() {
        return mHasTopUi;
    }

    public void setHasOverlayUi(boolean hasOverlayUi) {
        mHasOverlayUi = hasOverlayUi;
    }

    boolean hasOverlayUi() {
        return mHasOverlayUi;
    }

    public void setPendingUiClean(boolean hasPendingUiClean) {
        mPendingUiClean = hasPendingUiClean;
    }

    boolean hasPendingUiClean() {
        return mPendingUiClean;
    }

    /** @return {@code true} if the process registered to a display as a config listener. */
    boolean registeredForDisplayConfigChanges() {
        return mDisplayId != INVALID_DISPLAY;
    }

    void postPendingUiCleanMsg(boolean pendingUiClean) {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::setPendingUiClean, mListener, pendingUiClean);
        mAtm.mH.sendMessage(m);
    }

    public void setInteractionEventTime(long interactionEventTime) {
        mInteractionEventTime = interactionEventTime;
    }

    long getInteractionEventTime() {
        return mInteractionEventTime;
    }

    public void setFgInteractionTime(long fgInteractionTime) {
        mFgInteractionTime = fgInteractionTime;
    }

    long getFgInteractionTime() {
        return mFgInteractionTime;
    }

    public void setWhenUnimportant(long whenUnimportant) {
        mWhenUnimportant = whenUnimportant;
    }

    long getWhenUnimportant() {
        return mWhenUnimportant;
    }

    public void setRequiredAbi(String requiredAbi) {
        mRequiredAbi = requiredAbi;
    }

    String getRequiredAbi() {
        return mRequiredAbi;
    }

    /** Returns ID of display overriding the configuration for this process, or
     *  INVALID_DISPLAY if no display is overriding. */
    @VisibleForTesting
    int getDisplayId() {
        return mDisplayId;
    }

    public void setDebugging(boolean debugging) {
        mDebugging = debugging;
    }

    boolean isDebugging() {
        return mDebugging;
    }

    public void setUsingWrapper(boolean usingWrapper) {
        mUsingWrapper = usingWrapper;
    }

    boolean isUsingWrapper() {
        return mUsingWrapper;
    }

    void setLastActivityLaunchTime(long launchTime) {
        if (launchTime <= mLastActivityLaunchTime) {
            return;
        }
        mLastActivityLaunchTime = launchTime;
    }

    void setLastActivityFinishTimeIfNeeded(long finishTime) {
        if (finishTime <= mLastActivityFinishTime || !hasActivityInVisibleTask()) {
            return;
        }
        mLastActivityFinishTime = finishTime;
    }

    public void setAllowBackgroundActivityStarts(boolean allowBackgroundActivityStarts) {
        mAllowBackgroundActivityStarts = allowBackgroundActivityStarts;
    }

    boolean areBackgroundActivityStartsAllowed() {
        // allow if the whitelisting flag was explicitly set
        if (mAllowBackgroundActivityStarts) {
            return true;
        }
        // allow if any activity in the caller has either started or finished very recently, and
        // it must be started or finished after last stop app switches time.
        final long now = SystemClock.uptimeMillis();
        if (now - mLastActivityLaunchTime < ACTIVITY_BG_START_GRACE_PERIOD_MS
                || now - mLastActivityFinishTime < ACTIVITY_BG_START_GRACE_PERIOD_MS) {
            // if activity is started and finished before stop app switch time, we should not
            // let app to be able to start background activity even it's in grace period.
            if (mLastActivityLaunchTime > mAtm.getLastStopAppSwitchesTime()
                    || mLastActivityFinishTime > mAtm.getLastStopAppSwitchesTime()) {
                return true;
            }
        }
        // allow if the proc is instrumenting with background activity starts privs
        if (mInstrumentingWithBackgroundActivityStartPrivileges) {
            return true;
        }
        // allow if the caller has an activity in any foreground task
        if (hasActivityInVisibleTask()) {
            return true;
        }
        // allow if the caller is bound by a UID that's currently foreground
        if (isBoundByForegroundUid()) {
            return true;
        }
        return false;
    }

    private boolean isBoundByForegroundUid() {
        for (int i = mBoundClientUids.size() - 1; i >= 0; --i) {
            if (mAtm.isUidForeground(mBoundClientUids.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    public void setBoundClientUids(ArraySet<Integer> boundClientUids) {
        mBoundClientUids = boundClientUids;
    }

    public void setInstrumenting(boolean instrumenting,
            boolean hasBackgroundActivityStartPrivileges) {
        mInstrumenting = instrumenting;
        mInstrumentingWithBackgroundActivityStartPrivileges = hasBackgroundActivityStartPrivileges;
    }

    boolean isInstrumenting() {
        return mInstrumenting;
    }

    public void setPerceptible(boolean perceptible) {
        mPerceptible = perceptible;
    }

    boolean isPerceptible() {
        return mPerceptible;
    }

    @Override
    protected int getChildCount() {
        return 0;
    }

    @Override
    protected ConfigurationContainer getChildAt(int index) {
        return null;
    }

    @Override
    protected ConfigurationContainer getParent() {
        return null;
    }

    @HotPath(caller = HotPath.PROCESS_CHANGE)
    public void addPackage(String packageName) {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mPkgList.add(packageName);
        }
    }

    @HotPath(caller = HotPath.PROCESS_CHANGE)
    public void clearPackageList() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mPkgList.clear();
        }
    }

    void addActivityIfNeeded(ActivityRecord r) {
        // even if we already track this activity, note down that it has been launched
        setLastActivityLaunchTime(r.lastLaunchTime);
        if (mActivities.contains(r)) {
            return;
        }
        mActivities.add(r);
    }

    void removeActivity(ActivityRecord r) {
        mActivities.remove(r);
    }

    void makeFinishingForProcessRemoved() {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            mActivities.get(i).makeFinishingLocked();
        }
    }

    void clearActivities() {
        mActivities.clear();
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasActivities() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return !mActivities.isEmpty();
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasVisibleActivities() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                if (r.visible) {
                    return true;
                }
            }
        }
        return false;
    }

    @HotPath(caller = HotPath.LRU_UPDATE)
    public boolean hasActivitiesOrRecentTasks() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return !mActivities.isEmpty() || !mRecentTasks.isEmpty();
        }
    }

    private boolean hasActivityInVisibleTask() {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            TaskRecord task = mActivities.get(i).getTaskRecord();
            if (task == null) {
                continue;
            }
            ActivityRecord topActivity = task.getTopActivity();
            if (topActivity == null) {
                continue;
            }
            // If an activity has just been started it will not yet be visible, but
            // is expected to be soon. We treat this as if it were already visible.
            // This ensures a subsequent activity can be started even before this one
            // becomes visible.
            if (topActivity.visible || topActivity.isState(INITIALIZING)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Update the top resuming activity in process for pre-Q apps, only the top-most visible
     * activities are allowed to be resumed per process.
     * @return {@code true} if the activity is allowed to be resumed by compatibility
     * restrictions, which the activity was the topmost visible activity in process or the app is
     * targeting after Q. Note that non-focusable activity, in picture-in-picture mode for instance,
     * does not count as a topmost activity.
     */
    boolean updateTopResumingActivityInProcessIfNeeded(@NonNull ActivityRecord activity) {
        if (mInfo.targetSdkVersion >= Q || mPreQTopResumedActivity == activity) {
            return true;
        }

        final ActivityDisplay display = activity.getDisplay();
        if (display == null) {
            // No need to update if the activity hasn't attach to any display.
            return false;
        }

        boolean canUpdate = false;
        final ActivityDisplay topDisplay =
                mPreQTopResumedActivity != null ? mPreQTopResumedActivity.getDisplay() : null;
        // Update the topmost activity if current top activity is
        // - not on any display OR
        // - no longer visible OR
        // - not focusable (in PiP mode for instance)
        if (topDisplay == null
                || !mPreQTopResumedActivity.visible
                || !mPreQTopResumedActivity.isFocusable()) {
            canUpdate = true;
        }

        // Update the topmost activity if the current top activity wasn't on top of the other one.
        if (!canUpdate && topDisplay.mDisplayContent.compareTo(display.mDisplayContent) < 0) {
            canUpdate = true;
        }

        // Compare the z-order of ActivityStacks if both activities landed on same display.
        if (display == topDisplay
                && mPreQTopResumedActivity.getActivityStack().mTaskStack.compareTo(
                activity.getActivityStack().mTaskStack) <= 0) {
            canUpdate = true;
        }

        if (canUpdate) {
            // Make sure the previous top activity in the process no longer be resumed.
            if (mPreQTopResumedActivity != null && mPreQTopResumedActivity.isState(RESUMED)) {
                final ActivityStack stack = mPreQTopResumedActivity.getActivityStack();
                if (stack != null) {
                    stack.startPausingLocked(false /* userLeaving */, false /* uiSleeping */,
                            activity);
                }
            }
            mPreQTopResumedActivity = activity;
        }
        return canUpdate;
    }

    public void stopFreezingActivities() {
        synchronized (mAtm.mGlobalLock) {
            int i = mActivities.size();
            while (i > 0) {
                i--;
                mActivities.get(i).stopFreezingScreenLocked(true);
            }
        }
    }

    void finishActivities() {
        ArrayList<ActivityRecord> activities = new ArrayList<>(mActivities);
        for (int i = 0; i < activities.size(); i++) {
            final ActivityRecord r = activities.get(i);
            if (!r.finishing && r.isInStackLocked()) {
                r.finishIfPossible("finish-heavy", true /* oomAdj */);
            }
        }
    }

    public boolean isInterestingToUser() {
        synchronized (mAtm.mGlobalLock) {
            final int size = mActivities.size();
            for (int i = 0; i < size; i++) {
                ActivityRecord r = mActivities.get(i);
                if (r.isInterestingToUserLocked()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasRunningActivity(String packageName) {
        synchronized (mAtm.mGlobalLock) {
            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                if (packageName.equals(r.packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void clearPackagePreferredForHomeActivities() {
        synchronized (mAtm.mGlobalLock) {
            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                if (r.isActivityTypeHome()) {
                    Log.i(TAG, "Clearing package preferred activities from " + r.packageName);
                    try {
                        ActivityThread.getPackageManager()
                                .clearPackagePreferredActivities(r.packageName);
                    } catch (RemoteException c) {
                        // pm is in same process, this will never happen.
                    }
                }
            }
        }
    }

    boolean hasStartedActivity(ActivityRecord launchedActivity) {
        for (int i = mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mActivities.get(i);
            if (launchedActivity == activity) {
                continue;
            }
            if (!activity.stopped) {
                return true;
            }
        }
        return false;
    }


    void updateIntentForHeavyWeightActivity(Intent intent) {
        if (mActivities.isEmpty()) {
            return;
        }
        ActivityRecord hist = mActivities.get(0);
        intent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP, hist.packageName);
        intent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK, hist.getTaskRecord().taskId);
    }

    boolean shouldKillProcessForRemovedTask(TaskRecord tr) {
        for (int k = 0; k < mActivities.size(); k++) {
            final ActivityRecord activity = mActivities.get(k);
            if (!activity.stopped) {
                // Don't kill process(es) that has an activity not stopped.
                return false;
            }
            final TaskRecord otherTask = activity.getTaskRecord();
            if (tr.taskId != otherTask.taskId && otherTask.inRecents) {
                // Don't kill process(es) that has an activity in a different task that is
                // also in recents.
                return false;
            }
        }
        return true;
    }

    ArraySet<TaskRecord> getReleaseSomeActivitiesTasks() {
        // Examine all activities currently running in the process.
        TaskRecord firstTask = null;
        // Tasks is non-null only if two or more tasks are found.
        ArraySet<TaskRecord> tasks = null;
        if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Trying to release some activities in " + this);
        for (int i = 0; i < mActivities.size(); i++) {
            final ActivityRecord r = mActivities.get(i);
            // First, if we find an activity that is in the process of being destroyed,
            // then we just aren't going to do anything for now; we want things to settle
            // down before we try to prune more activities.
            if (r.finishing || r.isState(DESTROYING, DESTROYED)) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Abort release; already destroying: " + r);
                return null;
            }
            // Don't consider any activities that are currently not in a state where they
            // can be destroyed.
            if (r.visible || !r.stopped || !r.hasSavedState()
                    || r.isState(STARTED, RESUMED, PAUSING, PAUSED, STOPPING)) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Not releasing in-use activity: " + r);
                continue;
            }

            final TaskRecord task = r.getTaskRecord();
            if (task != null) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Collecting release task " + task
                        + " from " + r);
                if (firstTask == null) {
                    firstTask = task;
                } else if (firstTask != task) {
                    if (tasks == null) {
                        tasks = new ArraySet<>();
                        tasks.add(firstTask);
                    }
                    tasks.add(task);
                }
            }
        }

        return tasks;
    }

    public interface ComputeOomAdjCallback {
        void onVisibleActivity();
        void onPausedActivity();
        void onStoppingActivity(boolean finishing);
        void onOtherActivity();
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public int computeOomAdjFromActivities(int minTaskLayer, ComputeOomAdjCallback callback) {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            final int activitiesSize = mActivities.size();
            for (int j = 0; j < activitiesSize; j++) {
                final ActivityRecord r = mActivities.get(j);
                if (r.app != this) {
                    Log.e(TAG, "Found activity " + r + " in proc activity list using " + r.app
                            + " instead of expected " + this);
                    if (r.app == null || (r.app.mUid == mUid)) {
                        // Only fix things up when they look sane
                        r.setProcess(this);
                    } else {
                        continue;
                    }
                }
                if (r.visible) {
                    callback.onVisibleActivity();
                    final TaskRecord task = r.getTaskRecord();
                    if (task != null && minTaskLayer > 0) {
                        final int layer = task.mLayerRank;
                        if (layer >= 0 && minTaskLayer > layer) {
                            minTaskLayer = layer;
                        }
                    }
                    break;
                } else if (r.isState(PAUSING, PAUSED)) {
                    callback.onPausedActivity();
                } else if (r.isState(STOPPING)) {
                    callback.onStoppingActivity(r.finishing);
                } else {
                    callback.onOtherActivity();
                }
            }
        }

        return minTaskLayer;
    }

    public int computeRelaunchReason() {
        synchronized (mAtm.mGlobalLock) {
            final int activitiesSize = mActivities.size();
            for (int i = activitiesSize - 1; i >= 0; i--) {
                final ActivityRecord r = mActivities.get(i);
                if (r.mRelaunchReason != RELAUNCH_REASON_NONE) {
                    return r.mRelaunchReason;
                }
            }
        }
        return RELAUNCH_REASON_NONE;
    }

    public long getInputDispatchingTimeout() {
        synchronized (mAtm.mGlobalLock) {
            return isInstrumenting() || isUsingWrapper()
                    ? INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MS : KEY_DISPATCHING_TIMEOUT_MS;
        }
    }

    void clearProfilerIfNeeded() {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::clearProfilerIfNeeded, mListener));
    }

    void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange,
            boolean updateOomAdj) {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(WindowProcessListener::updateProcessInfo,
                mListener, updateServiceConnectionActivities, activityChange, updateOomAdj);
        mAtm.mH.sendMessage(m);
    }

    void updateServiceConnectionActivities() {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::updateServiceConnectionActivities, mListener));
    }

    void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::setPendingUiCleanAndForceProcessStateUpTo,
                mListener, newState);
        mAtm.mH.sendMessage(m);
    }

    boolean isRemoved() {
        return mListener == null ? false : mListener.isRemoved();
    }

    private boolean shouldSetProfileProc() {
        return mAtm.mProfileApp != null && mAtm.mProfileApp.equals(mName)
                && (mAtm.mProfileProc == null || mAtm.mProfileProc == this);
    }

    ProfilerInfo createProfilerInfoIfNeeded() {
        final ProfilerInfo currentProfilerInfo = mAtm.mProfilerInfo;
        if (currentProfilerInfo == null || currentProfilerInfo.profileFile == null
                || !shouldSetProfileProc()) {
            return null;
        }
        if (currentProfilerInfo.profileFd != null) {
            try {
                currentProfilerInfo.profileFd = currentProfilerInfo.profileFd.dup();
            } catch (IOException e) {
                currentProfilerInfo.closeFd();
            }
        }
        return new ProfilerInfo(currentProfilerInfo);
    }

    void onStartActivity(int topProcessState, ActivityInfo info) {
        if (mListener == null) return;
        String packageName = null;
        if ((info.flags & ActivityInfo.FLAG_MULTIPROCESS) == 0
                || !"android".equals(info.packageName)) {
            // Don't add this if it is a platform component that is marked to run in multiple
            // processes, because this is actually part of the framework so doesn't make sense
            // to track as a separate apk in the process.
            packageName = info.packageName;
        }
        // Posting the message at the front of queue so WM lock isn't held when we call into AM,
        // and the process state of starting activity can be updated quicker which will give it a
        // higher scheduling group.
        final Message m = PooledLambda.obtainMessage(WindowProcessListener::onStartActivity,
                mListener, topProcessState, shouldSetProfileProc(), packageName,
                info.applicationInfo.longVersionCode);
        mAtm.mH.sendMessageAtFrontOfQueue(m);
    }

    public void appDied() {
        if (mListener == null) return;
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::appDied, mListener);
        mAtm.mH.sendMessage(m);
    }

    void registerDisplayConfigurationListenerLocked(ActivityDisplay activityDisplay) {
        if (activityDisplay == null) {
            return;
        }
        // A process can only register to one display to listener to the override configuration
        // change. Unregister existing listener if it has one before register the new one.
        unregisterDisplayConfigurationListenerLocked();
        mDisplayId = activityDisplay.mDisplayId;
        activityDisplay.registerConfigurationChangeListener(this);
    }

    @VisibleForTesting
    void unregisterDisplayConfigurationListenerLocked() {
        if (mDisplayId == INVALID_DISPLAY) {
            return;
        }
        final ActivityDisplay activityDisplay =
                mAtm.mRootActivityContainer.getActivityDisplay(mDisplayId);
        if (activityDisplay != null) {
            activityDisplay.unregisterConfigurationChangeListener(this);
        }
        mDisplayId = INVALID_DISPLAY;
    }

    @Override
    public void onConfigurationChanged(Configuration newGlobalConfig) {
        super.onConfigurationChanged(newGlobalConfig);
        updateConfiguration();
    }

    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration newOverrideConfig) {
        super.onRequestedOverrideConfigurationChanged(newOverrideConfig);
        updateConfiguration();
    }

    private void updateConfiguration() {
        final Configuration config = getConfiguration();
        if (mLastReportedConfiguration.diff(config) == 0) {
            // Nothing changed.
            if (Build.IS_DEBUGGABLE && mIsImeProcess) {
                // TODO (b/135719017): Temporary log for debugging IME service.
                Slog.w(TAG_CONFIGURATION, "Current config: " + config
                        + " unchanged for IME proc " + mName);
            }
            return;
        }

        try {
            if (mThread == null) {
                if (Build.IS_DEBUGGABLE && mIsImeProcess) {
                    // TODO (b/135719017): Temporary log for debugging IME service.
                    Slog.w(TAG_CONFIGURATION, "Unable to send config for IME proc " + mName
                            + ": no app thread");
                }
                return;
            }
            if (DEBUG_CONFIGURATION) {
                Slog.v(TAG_CONFIGURATION, "Sending to proc " + mName
                        + " new config " + config);
            }
            if (Build.IS_DEBUGGABLE && mIsImeProcess) {
                // TODO (b/135719017): Temporary log for debugging IME service.
                Slog.v(TAG_CONFIGURATION, "Sending to IME proc " + mName
                        + " new config " + config);
            }
            config.seq = mAtm.increaseConfigurationSeqLocked();
            mAtm.getLifecycleManager().scheduleTransaction(mThread,
                    ConfigurationChangeItem.obtain(config));
            setLastReportedConfiguration(config);
        } catch (Exception e) {
            Slog.e(TAG_CONFIGURATION, "Failed to schedule configuration change", e);
        }
    }

    private void setLastReportedConfiguration(Configuration config) {
        mLastReportedConfiguration.setTo(config);
    }

    Configuration getLastReportedConfiguration() {
        return mLastReportedConfiguration;
    }

    /** Returns the total time (in milliseconds) spent executing in both user and system code. */
    public long getCpuTime() {
        return (mListener != null) ? mListener.getCpuTime() : 0;
    }

    void addRecentTask(TaskRecord task) {
        mRecentTasks.add(task);
    }

    void removeRecentTask(TaskRecord task) {
        mRecentTasks.remove(task);
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasRecentTasks() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return !mRecentTasks.isEmpty();
        }
    }

    void clearRecentTasks() {
        for (int i = mRecentTasks.size() - 1; i >= 0; i--) {
            mRecentTasks.get(i).clearRootProcess();
        }
        mRecentTasks.clear();
    }

    public void appEarlyNotResponding(String annotation, Runnable killAppCallback) {
        synchronized (mAtm.mGlobalLock) {
            if (mAtm.mController == null) {
                return;
            }

            try {
                // 0 == continue, -1 = kill process immediately
                int res = mAtm.mController.appEarlyNotResponding(mName, mPid, annotation);
                if (res < 0 && mPid != MY_PID) {
                    killAppCallback.run();
                }
            } catch (RemoteException e) {
                mAtm.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }
    }

    public boolean appNotResponding(String info, Runnable killAppCallback,
            Runnable serviceTimeoutCallback) {
        Runnable targetRunnable = null;
        synchronized (mAtm.mGlobalLock) {
            if (mAtm.mController == null) {
                return false;
            }

            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mAtm.mController.appNotResponding(mName, mPid, info);
                if (res != 0) {
                    if (res < 0 && mPid != MY_PID) {
                        targetRunnable = killAppCallback;
                    } else {
                        targetRunnable = serviceTimeoutCallback;
                    }
                }
            } catch (RemoteException e) {
                mAtm.mController = null;
                Watchdog.getInstance().setActivityController(null);
                return false;
            }
        }
        if (targetRunnable != null) {
            targetRunnable.run();
            return true;
        }
        return false;
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public void onTopProcChanged() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            mAtm.mVrController.onTopProcChangedLocked(this);
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isHomeProcess() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return this == mAtm.mHomeProcess;
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isPreviousProcess() {
        synchronized (mAtm.mGlobalLockWithoutBoost) {
            return this == mAtm.mPreviousProcess;
        }
    }

    void setRunningRecentsAnimation(boolean running) {
        if (mRunningRecentsAnimation == running) {
            return;
        }
        mRunningRecentsAnimation = running;
        updateRunningRemoteOrRecentsAnimation();
    }

    void setRunningRemoteAnimation(boolean running) {
        if (mRunningRemoteAnimation == running) {
            return;
        }
        mRunningRemoteAnimation = running;
        updateRunningRemoteOrRecentsAnimation();
    }

    private void updateRunningRemoteOrRecentsAnimation() {

        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::setRunningRemoteAnimation, mListener,
                mRunningRecentsAnimation || mRunningRemoteAnimation));
    }

    @Override
    public String toString() {
        return mOwner != null ? mOwner.toString() : null;
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mAtm.mGlobalLock) {
            if (mActivities.size() > 0) {
                pw.print(prefix); pw.println("Activities:");
                for (int i = 0; i < mActivities.size(); i++) {
                    pw.print(prefix); pw.print("  - "); pw.println(mActivities.get(i));
                }
            }

            if (mRecentTasks.size() > 0) {
                pw.println(prefix + "Recent Tasks:");
                for (int i = 0; i < mRecentTasks.size(); i++) {
                    pw.println(prefix + "  - " + mRecentTasks.get(i));
                }
            }

            if (mVrThreadTid != 0) {
                pw.print(prefix); pw.print("mVrThreadTid="); pw.println(mVrThreadTid);
            }
        }
        pw.println(prefix + " Configuration=" + getConfiguration());
        pw.println(prefix + " OverrideConfiguration=" + getRequestedOverrideConfiguration());
        pw.println(prefix + " mLastReportedConfiguration=" + mLastReportedConfiguration);
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        if (mListener != null) {
            mListener.writeToProto(proto, fieldId);
        }
    }
}
