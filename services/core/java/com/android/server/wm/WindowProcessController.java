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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.content.res.Configuration.ASSETS_SEQ_UNDEFINED;
import static android.os.Build.VERSION_CODES.Q;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MILLIS;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.Task.ActivityState.DESTROYED;
import static com.android.server.wm.Task.ActivityState.DESTROYING;
import static com.android.server.wm.Task.ActivityState.PAUSED;
import static com.android.server.wm.Task.ActivityState.PAUSING;
import static com.android.server.wm.Task.ActivityState.RESUMED;
import static com.android.server.wm.Task.ActivityState.STARTED;
import static com.android.server.wm.Task.ActivityState.STOPPING;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.app.servertransaction.ConfigurationChangeItem;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IRemoteAnimationRunner;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.wm.ActivityTaskManagerService.HotPath;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
    private final BackgroundLaunchProcessController mBgLaunchController;
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
    // If there is active instrumentation, this is the source
    private volatile int mInstrumentationSourceUid = -1;
    // Active instrumentation with background activity starts privilege running in process?
    private volatile boolean mInstrumentingWithBackgroundActivityStartPrivileges;
    // This process it perceptible by the user.
    private volatile boolean mPerceptible;
    // Set to true when process was launched with a wrapper attached
    private volatile boolean mUsingWrapper;

    // Thread currently set for VR scheduling
    int mVrThreadTid;

    // Whether this process has ever started a service with the BIND_INPUT_METHOD permission.
    private volatile boolean mHasImeService;

    /** Whether {@link #mActivities} is not empty. */
    private volatile boolean mHasActivities;
    /** All activities running in the process (exclude destroying). */
    private final ArrayList<ActivityRecord> mActivities = new ArrayList<>();
    /** The activities will be removed but still belong to this process. */
    private ArrayList<ActivityRecord> mInactiveActivities;
    /** Whether {@link #mRecentTasks} is not empty. */
    private volatile boolean mHasRecentTasks;
    // any tasks this process had run root activities in
    private final ArrayList<Task> mRecentTasks = new ArrayList<>();
    // The most recent top-most activity that was resumed in the process for pre-Q app.
    private ActivityRecord mPreQTopResumedActivity = null;
    // The last time an activity was launched in the process
    private volatile long mLastActivityLaunchTime;
    // The last time an activity was finished in the process while the process participated
    // in a visible task
    private volatile long mLastActivityFinishTime;

    // Last configuration that was reported to the process.
    private final Configuration mLastReportedConfiguration = new Configuration();
    /** Whether the process configuration is waiting to be dispatched to the process. */
    private boolean mHasPendingConfigurationChange;

    /**
     * Registered {@link DisplayArea} as a listener to override config changes. {@code null} if not
     * registered.
     */
    @Nullable
    private DisplayArea mDisplayArea;
    private ActivityRecord mConfigActivityRecord;
    // Whether the activity config override is allowed for this process.
    private volatile boolean mIsActivityConfigOverrideAllowed = true;
    /** Non-zero to pause dispatching process configuration change. */
    private int mPauseConfigurationDispatchCount;

    /**
     * Activities that hosts some UI drawn by the current process. The activities live
     * in another process. This is used to check if the process is currently showing anything
     * visible to the user.
     */
    @Nullable
    private final ArrayList<ActivityRecord> mHostActivities = new ArrayList<>();

    /** Whether our process is currently running a {@link RecentsAnimation} */
    private boolean mRunningRecentsAnimation;

    /** Whether our process is currently running a {@link IRemoteAnimationRunner} */
    private boolean mRunningRemoteAnimation;

    // The bits used for mActivityStateFlags.
    private static final int ACTIVITY_STATE_FLAG_IS_VISIBLE = 1 << 16;
    private static final int ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED = 1 << 17;
    private static final int ACTIVITY_STATE_FLAG_IS_STOPPING = 1 << 18;
    private static final int ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING = 1 << 19;
    private static final int ACTIVITY_STATE_FLAG_IS_WINDOW_VISIBLE = 1 << 20;
    private static final int ACTIVITY_STATE_FLAG_HAS_RESUMED = 1 << 21;
    private static final int ACTIVITY_STATE_FLAG_HAS_ACTIVITY_IN_VISIBLE_TASK = 1 << 22;
    private static final int ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER = 0x0000ffff;

    /**
     * The state for oom-adjustment calculation. The higher 16 bits are the activity states, and the
     * lower 16 bits are the task layer rank (see {@link Task#mLayerRank}). This field is written by
     * window manager and read by activity manager.
     */
    private volatile int mActivityStateFlags = ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;

    public WindowProcessController(@NonNull ActivityTaskManagerService atm,
            @NonNull ApplicationInfo info, String name, int uid, int userId, Object owner,
            @NonNull WindowProcessListener listener) {
        mInfo = info;
        mName = name;
        mUid = uid;
        mUserId = userId;
        mOwner = owner;
        mListener = listener;
        mAtm = atm;
        mBgLaunchController = new BackgroundLaunchProcessController(
                atm::hasActiveVisibleWindow, atm.getBackgroundActivityStartCallback());

        boolean isSysUiPackage = info.packageName.equals(
                mAtm.getSysUiServiceComponentLocked().getPackageName());
        if (isSysUiPackage || mUid == Process.SYSTEM_UID) {
            // This is a system owned process and should not use an activity config.
            // TODO(b/151161907): Remove after support for display-independent (raw) SysUi configs.
            mIsActivityConfigOverrideAllowed = false;
        }

        onConfigurationChanged(atm.getGlobalConfiguration());
        mAtm.mPackageConfigPersister.updateConfigIfNeeded(this, mUserId, mName);
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
            // In general this is called from attaching application, so the last configuration
            // has been sent to client by {@link android.app.IApplicationThread#bindApplication}.
            // If this process is system server, it is fine because system is booting and a new
            // configuration will update when display is ready.
            if (thread != null) {
                setLastReportedConfiguration(getConfiguration());
            } else {
                // The process is inactive.
                mAtm.mVisibleActivityProcessTracker.removeProcess(this);
            }
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

    boolean hasForegroundActivities() {
        return mAtm.mTopApp == this || (mActivityStateFlags
                & (ACTIVITY_STATE_FLAG_IS_VISIBLE | ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED
                        | ACTIVITY_STATE_FLAG_IS_STOPPING)) != 0;
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

    /** @return {@code true} if the process registered to a display area as a config listener. */
    boolean registeredForDisplayAreaConfigChanges() {
        return mDisplayArea != null;
    }

    /** @return {@code true} if the process registered to an activity as a config listener. */
    @VisibleForTesting
    boolean registeredForActivityConfigChanges() {
        return mConfigActivityRecord != null;
    }

    void postPendingUiCleanMsg(boolean pendingUiClean) {
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

    /**
     * Registered {@link DisplayArea} as a listener to override config changes. {@code null} if not
     * registered.
     */
    @VisibleForTesting
    @Nullable
    DisplayArea getDisplayArea() {
        return mDisplayArea;
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

    boolean hasEverLaunchedActivity() {
        return mLastActivityLaunchTime > 0;
    }

    void setLastActivityLaunchTime(long launchTime) {
        if (launchTime <= mLastActivityLaunchTime) {
            if (launchTime < mLastActivityLaunchTime) {
                Slog.w(TAG,
                        "Tried to set launchTime (" + launchTime + ") < mLastActivityLaunchTime ("
                                + mLastActivityLaunchTime + ")");
            }
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

    /**
     * @see BackgroundLaunchProcessController#addOrUpdateAllowBackgroundActivityStartsToken(Binder,
     * IBinder)
     */
    public void addOrUpdateAllowBackgroundActivityStartsToken(Binder entity,
            @Nullable IBinder originatingToken) {
        mBgLaunchController.addOrUpdateAllowBackgroundActivityStartsToken(entity, originatingToken);
    }

    /** @see BackgroundLaunchProcessController#removeAllowBackgroundActivityStartsToken(Binder) */
    public void removeAllowBackgroundActivityStartsToken(Binder entity) {
        mBgLaunchController.removeAllowBackgroundActivityStartsToken(entity);
    }

    /**
     * Is this WindowProcessController in the state of allowing background FGS start?
     */
    @HotPath(caller = HotPath.START_SERVICE)
    public boolean areBackgroundFgsStartsAllowed() {
        return areBackgroundActivityStartsAllowed(mAtm.getBalAppSwitchesAllowed(),
                true /* isCheckingForFgsStart */);
    }

    boolean areBackgroundActivityStartsAllowed(boolean appSwitchAllowed) {
        return areBackgroundActivityStartsAllowed(appSwitchAllowed,
                false /* isCheckingForFgsStart */);
    }

    private boolean areBackgroundActivityStartsAllowed(boolean appSwitchAllowed,
            boolean isCheckingForFgsStart) {
        return mBgLaunchController.areBackgroundActivityStartsAllowed(mPid, mUid, mInfo.packageName,
                appSwitchAllowed, isCheckingForFgsStart, hasActivityInVisibleTask(),
                mInstrumentingWithBackgroundActivityStartPrivileges,
                mAtm.getLastStopAppSwitchesTime(),
                mLastActivityLaunchTime, mLastActivityFinishTime);
    }

    /**
     * Returns whether this process is allowed to close system dialogs via a background activity
     * start token that allows the close system dialogs operation (eg. notification).
     */
    boolean canCloseSystemDialogsByToken() {
        return mBgLaunchController.canCloseSystemDialogsByToken(mUid);
    }

    /**
     * Clear all bound client Uids.
     */
    public void clearBoundClientUids() {
        mBgLaunchController.clearBalOptInBoundClientUids();
    }

    /**
     * Add bound client Uid.
     */
    public void addBoundClientUid(int clientUid, String clientPackageName, int bindFlags) {
        mBgLaunchController.addBoundClientUid(clientUid, clientPackageName, bindFlags);
    }

    /**
     * Set instrumentation-related info.
     *
     * If {@code instrumenting} is {@code false}, {@code sourceUid} has to be -1.
     */
    public void setInstrumenting(boolean instrumenting, int sourceUid,
            boolean hasBackgroundActivityStartPrivileges) {
        checkArgument(instrumenting || sourceUid == -1);
        mInstrumenting = instrumenting;
        mInstrumentationSourceUid = sourceUid;
        mInstrumentingWithBackgroundActivityStartPrivileges = hasBackgroundActivityStartPrivileges;
    }

    boolean isInstrumenting() {
        return mInstrumenting;
    }

    /** Returns the uid of the active instrumentation source if there is one, otherwise -1. */
    int getInstrumentationSourceUid() {
        return mInstrumentationSourceUid;
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
        // Returning RootWindowContainer as the parent, so that this process controller always
        // has full configuration and overrides (e.g. from display) are always added on top of
        // global config.
        return mAtm.mRootWindowContainer;
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
        mHasActivities = true;
        if (mInactiveActivities != null) {
            mInactiveActivities.remove(r);
        }
        updateActivityConfigurationListener();
    }

    /**
     * Indicates that the given activity is no longer active in this process.
     *
     * @param r The running activity to be removed.
     * @param keepAssociation {@code true} if the activity still belongs to this process but will
     *                        be removed soon, e.g. destroying. From the perspective of process
     *                        priority, the process is not important if it only contains activities
     *                        that are being destroyed. But the association is still needed to
     *                        ensure all activities are reachable from this process.
     */
    void removeActivity(ActivityRecord r, boolean keepAssociation) {
        if (keepAssociation) {
            if (mInactiveActivities == null) {
                mInactiveActivities = new ArrayList<>();
                mInactiveActivities.add(r);
            } else if (!mInactiveActivities.contains(r)) {
                mInactiveActivities.add(r);
            }
        } else if (mInactiveActivities != null) {
            mInactiveActivities.remove(r);
        }
        mActivities.remove(r);
        mHasActivities = !mActivities.isEmpty();
        updateActivityConfigurationListener();
    }

    void clearActivities() {
        mInactiveActivities = null;
        mActivities.clear();
        mHasActivities = false;
        updateActivityConfigurationListener();
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasActivities() {
        return mHasActivities;
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasVisibleActivities() {
        return (mActivityStateFlags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0;
    }

    boolean hasActivityInVisibleTask() {
        return (mActivityStateFlags & ACTIVITY_STATE_FLAG_HAS_ACTIVITY_IN_VISIBLE_TASK) != 0;
    }

    @HotPath(caller = HotPath.LRU_UPDATE)
    public boolean hasActivitiesOrRecentTasks() {
        return mHasActivities || mHasRecentTasks;
    }

    @Nullable
    TaskDisplayArea getTopActivityDisplayArea() {
        if (mActivities.isEmpty()) {
            return null;
        }

        final int lastIndex = mActivities.size() - 1;
        ActivityRecord topRecord = mActivities.get(lastIndex);
        TaskDisplayArea displayArea = topRecord.getDisplayArea();

        for (int index = lastIndex - 1; index >= 0; --index) {
            ActivityRecord nextRecord = mActivities.get(index);
            TaskDisplayArea nextDisplayArea = nextRecord.getDisplayArea();
            if (nextRecord.compareTo(topRecord) > 0 && nextDisplayArea != null) {
                topRecord = nextRecord;
                displayArea = nextDisplayArea;
            }
        }

        return displayArea;
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

        if (!activity.isAttached()) {
            // No need to update if the activity hasn't attach to any display.
            return false;
        }

        boolean canUpdate = false;
        final DisplayContent topDisplay =
                (mPreQTopResumedActivity != null && mPreQTopResumedActivity.isAttached())
                        ? mPreQTopResumedActivity.mDisplayContent
                        : null;
        // Update the topmost activity if current top activity is
        // - not on any display OR
        // - no longer visible OR
        // - not focusable (in PiP mode for instance)
        if (topDisplay == null
                || !mPreQTopResumedActivity.mVisibleRequested
                || !mPreQTopResumedActivity.isFocusable()) {
            canUpdate = true;
        }

        final DisplayContent display = activity.mDisplayContent;
        // Update the topmost activity if the current top activity wasn't on top of the other one.
        if (!canUpdate && topDisplay.compareTo(display) < 0) {
            canUpdate = true;
        }

        // Compare the z-order of ActivityStacks if both activities landed on same display.
        if (display == topDisplay
                && mPreQTopResumedActivity.getRootTask().compareTo(
                        activity.getRootTask()) <= 0) {
            canUpdate = true;
        }

        if (canUpdate) {
            // Make sure the previous top activity in the process no longer be resumed.
            if (mPreQTopResumedActivity != null && mPreQTopResumedActivity.isState(RESUMED)) {
                final Task task = mPreQTopResumedActivity.getTask();
                if (task != null) {
                    boolean userLeaving = task.shouldBeVisible(null);
                    task.startPausingLocked(userLeaving, false /* uiSleeping */,
                            activity, "top-resumed-changed");
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
            if (!r.finishing && r.isInRootTaskLocked()) {
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
            if (isEmbedded()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} if this process is rendering content on to a window shown by
     * another process.
     */
    private boolean isEmbedded() {
        for (int i = mHostActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord r = mHostActivities.get(i);
            if (r.isInterestingToUserLocked()) {
                return true;
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

    void updateNightModeForAllActivities(int nightMode) {
        for (int i = mActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord r = mActivities.get(i);
            if (r.setOverrideNightMode(nightMode) && r.mVisibleRequested) {
                r.ensureActivityConfiguration(0 /* globalChanges */, true /* preserveWindow */);
            }
        }
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

    boolean hasResumedActivity() {
        return (mActivityStateFlags & ACTIVITY_STATE_FLAG_HAS_RESUMED) != 0;
    }

    void updateIntentForHeavyWeightActivity(Intent intent) {
        if (mActivities.isEmpty()) {
            return;
        }
        ActivityRecord hist = mActivities.get(0);
        intent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP, hist.packageName);
        intent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK, hist.getTask().mTaskId);
    }

    boolean shouldKillProcessForRemovedTask(Task task) {
        for (int k = 0; k < mActivities.size(); k++) {
            final ActivityRecord activity = mActivities.get(k);
            if (!activity.stopped) {
                // Don't kill process(es) that has an activity not stopped.
                return false;
            }
            final Task otherTask = activity.getTask();
            if (task.mTaskId != otherTask.mTaskId && otherTask.inRecents) {
                // Don't kill process(es) that has an activity in a different task that is
                // also in recents.
                return false;
            }
        }
        return true;
    }

    void releaseSomeActivities(String reason) {
        // Examine all activities currently running in the process.
        // Candidate activities that can be destroyed.
        ArrayList<ActivityRecord> candidates = null;
        if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Trying to release some activities in " + this);
        for (int i = 0; i < mActivities.size(); i++) {
            final ActivityRecord r = mActivities.get(i);
            // First, if we find an activity that is in the process of being destroyed,
            // then we just aren't going to do anything for now; we want things to settle
            // down before we try to prune more activities.
            if (r.finishing || r.isState(DESTROYING, DESTROYED)) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Abort release; already destroying: " + r);
                return;
            }
            // Don't consider any activities that are currently not in a state where they
            // can be destroyed.
            if (r.mVisibleRequested || !r.stopped || !r.hasSavedState() || !r.isDestroyable()
                    || r.isState(STARTED, RESUMED, PAUSING, PAUSED, STOPPING)) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Not releasing in-use activity: " + r);
                continue;
            }

            if (r.getParent() != null) {
                if (candidates == null) {
                    candidates = new ArrayList<>();
                }
                candidates.add(r);
            }
        }

        if (candidates != null) {
            // Sort based on z-order in hierarchy.
            candidates.sort(WindowContainer::compareTo);
            // Release some older activities
            int maxRelease = Math.max(candidates.size(), 1);
            do {
                final ActivityRecord r = candidates.remove(0);
                if (DEBUG_RELEASE) Slog.v(TAG_RELEASE, "Destroying " + r
                        + " in state " + r.getState() + " for reason " + reason);
                r.destroyImmediately(reason);
                --maxRelease;
            } while (maxRelease > 0);
        }
    }

    /**
     * Returns display UI context list which there is any app window shows or starting activities
     * int this process.
     */
    public void getDisplayContextsWithErrorDialogs(List<Context> displayContexts) {
        if (displayContexts == null) {
            return;
        }
        synchronized (mAtm.mGlobalLock) {
            final RootWindowContainer root = mAtm.mWindowManager.mRoot;
            root.getDisplayContextsWithNonToastVisibleWindows(mPid, displayContexts);

            for (int i = mActivities.size() - 1; i >= 0; --i) {
                final ActivityRecord r = mActivities.get(i);
                final int displayId = r.getDisplayId();
                final Context c = root.getDisplayUiContext(displayId);

                if (r.mVisibleRequested && !displayContexts.contains(c)) {
                    displayContexts.add(c);
                }
            }
        }
    }

    /** Adds an activity that hosts UI drawn by the current process. */
    void addHostActivity(ActivityRecord r) {
        if (mHostActivities.contains(r)) {
            return;
        }
        mHostActivities.add(r);
    }

    /** Removes an activity that hosts UI drawn by the current process. */
    void removeHostActivity(ActivityRecord r) {
        mHostActivities.remove(r);
    }

    public interface ComputeOomAdjCallback {
        void onVisibleActivity();
        void onPausedActivity();
        void onStoppingActivity(boolean finishing);
        void onOtherActivity();
    }

    /**
     * Returns the minimum task layer rank. It should only be called if {@link #hasActivities}
     * returns {@code true}.
     */
    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public int computeOomAdjFromActivities(ComputeOomAdjCallback callback) {
        final int flags = mActivityStateFlags;
        if ((flags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0) {
            callback.onVisibleActivity();
        } else if ((flags & ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED) != 0) {
            callback.onPausedActivity();
        } else if ((flags & ACTIVITY_STATE_FLAG_IS_STOPPING) != 0) {
            callback.onStoppingActivity(
                    (flags & ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING) != 0);
        } else {
            callback.onOtherActivity();
        }
        return flags & ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;
    }

    void computeProcessActivityState() {
        // Since there could be more than one activities in a process record, we don't need to
        // compute the OomAdj with each of them, just need to find out the activity with the
        // "best" state, the order would be visible, pausing, stopping...
        Task.ActivityState bestInvisibleState = DESTROYED;
        boolean allStoppingFinishing = true;
        boolean visible = false;
        int minTaskLayer = Integer.MAX_VALUE;
        int stateFlags = 0;
        final boolean wasResumed = hasResumedActivity();
        final boolean wasAnyVisible = (mActivityStateFlags
                & (ACTIVITY_STATE_FLAG_IS_VISIBLE | ACTIVITY_STATE_FLAG_IS_WINDOW_VISIBLE)) != 0;
        for (int i = mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = mActivities.get(i);
            if (r.isVisible()) {
                stateFlags |= ACTIVITY_STATE_FLAG_IS_WINDOW_VISIBLE;
            }
            final Task task = r.getTask();
            if (task != null && task.mLayerRank != Task.LAYER_RANK_INVISIBLE) {
                stateFlags |= ACTIVITY_STATE_FLAG_HAS_ACTIVITY_IN_VISIBLE_TASK;
            }
            if (r.mVisibleRequested) {
                if (r.isState(RESUMED)) {
                    stateFlags |= ACTIVITY_STATE_FLAG_HAS_RESUMED;
                }
                if (task != null && minTaskLayer > 0) {
                    final int layer = task.mLayerRank;
                    if (layer >= 0 && minTaskLayer > layer) {
                        minTaskLayer = layer;
                    }
                }
                visible = true;
                // continue the loop, in case there are multiple visible activities in
                // this process, we'd find out the one with the minimal layer, thus it'll
                // get a higher adj score.
            } else if (!visible && bestInvisibleState != PAUSING) {
                if (r.isState(PAUSING, PAUSED)) {
                    bestInvisibleState = PAUSING;
                } else if (r.isState(STOPPING)) {
                    bestInvisibleState = STOPPING;
                    // Not "finishing" if any of activity isn't finishing.
                    allStoppingFinishing &= r.finishing;
                }
            }
        }

        stateFlags |= minTaskLayer & ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;
        if (visible) {
            stateFlags |= ACTIVITY_STATE_FLAG_IS_VISIBLE;
        } else if (bestInvisibleState == PAUSING) {
            stateFlags |= ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED;
        } else if (bestInvisibleState == STOPPING) {
            stateFlags |= ACTIVITY_STATE_FLAG_IS_STOPPING;
            if (allStoppingFinishing) {
                stateFlags |= ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING;
            }
        }
        mActivityStateFlags = stateFlags;

        final boolean anyVisible = (stateFlags
                & (ACTIVITY_STATE_FLAG_IS_VISIBLE | ACTIVITY_STATE_FLAG_IS_WINDOW_VISIBLE)) != 0;
        if (!wasAnyVisible && anyVisible) {
            mAtm.mVisibleActivityProcessTracker.onAnyActivityVisible(this);
        } else if (wasAnyVisible && !anyVisible) {
            mAtm.mVisibleActivityProcessTracker.onAllActivitiesInvisible(this);
        } else if (wasAnyVisible && !wasResumed && hasResumedActivity()) {
            mAtm.mVisibleActivityProcessTracker.onActivityResumedWhileVisible(this);
        }
    }

    /** Called when the process has some oom related changes and it is going to update oom-adj. */
    private void prepareOomAdjustment() {
        mAtm.mRootWindowContainer.rankTaskLayers();
        mAtm.mTaskSupervisor.computeProcessActivityStateBatch();
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

    /**
     * Get the current dispatching timeout. If instrumentation is currently taking place, return
     * a longer value. Shorter timeout is returned otherwise.
     * @return The timeout in milliseconds
     */
    public long getInputDispatchingTimeoutMillis() {
        synchronized (mAtm.mGlobalLock) {
            return isInstrumenting() || isUsingWrapper()
                    ? INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MILLIS :
                    DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        }
    }

    void clearProfilerIfNeeded() {
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::clearProfilerIfNeeded, mListener));
    }

    void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange,
            boolean updateOomAdj, boolean addPendingTopUid) {
        if (addPendingTopUid) {
            mAtm.mAmInternal.addPendingTopUid(mUid, mPid);
        }
        if (updateOomAdj) {
            prepareOomAdjustment();
        }
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(WindowProcessListener::updateProcessInfo,
                mListener, updateServiceConnectionActivities, activityChange, updateOomAdj);
        mAtm.mH.sendMessage(m);
    }

    void updateServiceConnectionActivities() {
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::updateServiceConnectionActivities, mListener));
    }

    void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::setPendingUiCleanAndForceProcessStateUpTo,
                mListener, newState);
        mAtm.mH.sendMessage(m);
    }

    boolean isRemoved() {
        return mListener.isRemoved();
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
        String packageName = null;
        if ((info.flags & ActivityInfo.FLAG_MULTIPROCESS) == 0
                || !"android".equals(info.packageName)) {
            // Don't add this if it is a platform component that is marked to run in multiple
            // processes, because this is actually part of the framework so doesn't make sense
            // to track as a separate apk in the process.
            packageName = info.packageName;
        }
        // update ActivityManagerService.PendingStartActivityUids list.
        if (topProcessState == ActivityManager.PROCESS_STATE_TOP) {
            mAtm.mAmInternal.addPendingTopUid(mUid, mPid);
        }
        prepareOomAdjustment();
        // Posting the message at the front of queue so WM lock isn't held when we call into AM,
        // and the process state of starting activity can be updated quicker which will give it a
        // higher scheduling group.
        final Message m = PooledLambda.obtainMessage(WindowProcessListener::onStartActivity,
                mListener, topProcessState, shouldSetProfileProc(), packageName,
                info.applicationInfo.longVersionCode);
        mAtm.mH.sendMessageAtFrontOfQueue(m);
    }

    void appDied(String reason) {
        // Posting on handler so WM lock isn't held when we call into AM.
        final Message m = PooledLambda.obtainMessage(
                WindowProcessListener::appDied, mListener, reason);
        mAtm.mH.sendMessage(m);
    }

    /**
     * Clean up the activities belonging to this process.
     *
     * @return {@code true} if the process has any visible activity.
     */
    boolean handleAppDied() {
        mAtm.mTaskSupervisor.removeHistoryRecords(this);

        boolean hasVisibleActivities = false;
        final boolean hasInactiveActivities =
                mInactiveActivities != null && !mInactiveActivities.isEmpty();
        final ArrayList<ActivityRecord> activities =
                (mHasActivities || hasInactiveActivities) ? new ArrayList<>() : mActivities;
        if (mHasActivities) {
            activities.addAll(mActivities);
        }
        if (hasInactiveActivities) {
            // Make sure that all activities in this process are handled.
            activities.addAll(mInactiveActivities);
        }
        if (isRemoved()) {
            // The package of the died process should be force-stopped, so make its activities as
            // finishing to prevent the process from being started again if the next top (or being
            // visible) activity also resides in the same process. This must be done before removal.
            for (int i = activities.size() - 1; i >= 0; i--) {
                activities.get(i).makeFinishingLocked();
            }
        }
        for (int i = activities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = activities.get(i);
            if (r.mVisibleRequested || r.isVisible()) {
                // While an activity launches a new activity, it's possible that the old activity
                // is already requested to be hidden (mVisibleRequested=false), but this visibility
                // is not yet committed, so isVisible()=true.
                hasVisibleActivities = true;
            }

            final Task task = r.getTask();
            if (task != null) {
                // There may be a pausing activity that hasn't shown any window and was requested
                // to be hidden. But pausing is also a visible state, it should be regarded as
                // visible, so the caller can know the next activity should be resumed.
                hasVisibleActivities |= task.handleAppDied(this);
            }
            r.handleAppDied();
        }
        clearRecentTasks();
        clearActivities();

        return hasVisibleActivities;
    }

    void registerDisplayAreaConfigurationListener(@Nullable DisplayArea displayArea) {
        if (displayArea == null || displayArea.containsListener(this)) {
            return;
        }
        unregisterConfigurationListeners();
        mDisplayArea = displayArea;
        displayArea.registerConfigurationChangeListener(this);
    }

    @VisibleForTesting
    void unregisterDisplayAreaConfigurationListener() {
        if (mDisplayArea == null) {
            return;
        }
        mDisplayArea.unregisterConfigurationChangeListener(this);
        mDisplayArea = null;
        onMergedOverrideConfigurationChanged(Configuration.EMPTY);
    }

    void registerActivityConfigurationListener(ActivityRecord activityRecord) {
        if (activityRecord == null || activityRecord.containsListener(this)
                // Check for the caller from outside of this class.
                || !mIsActivityConfigOverrideAllowed) {
            return;
        }
        unregisterConfigurationListeners();
        mConfigActivityRecord = activityRecord;
        activityRecord.registerConfigurationChangeListener(this);
    }

    private void unregisterActivityConfigurationListener() {
        if (mConfigActivityRecord == null) {
            return;
        }
        mConfigActivityRecord.unregisterConfigurationChangeListener(this);
        mConfigActivityRecord = null;
        onMergedOverrideConfigurationChanged(Configuration.EMPTY);
    }

    /**
     * A process can only register to one {@link WindowContainer} to listen to the override
     * configuration changes. Unregisters the existing listener if it has one before registers a
     * new one.
     */
    private void unregisterConfigurationListeners() {
        unregisterActivityConfigurationListener();
        unregisterDisplayAreaConfigurationListener();
    }

    /**
     * Check if activity configuration override for the activity process needs an update and perform
     * if needed. By default we try to override the process configuration to match the top activity
     * config to increase app compatibility with multi-window and multi-display. The process will
     * always track the configuration of the non-finishing activity last added to the process.
     */
    private void updateActivityConfigurationListener() {
        if (!mIsActivityConfigOverrideAllowed) {
            return;
        }

        for (int i = mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord activityRecord = mActivities.get(i);
            if (!activityRecord.finishing) {
                // Eligible activity is found, update listener.
                registerActivityConfigurationListener(activityRecord);
                return;
            }
        }

        // No eligible activities found, let's remove the configuration listener.
        unregisterActivityConfigurationListener();
    }

    @Override
    public void onConfigurationChanged(Configuration newGlobalConfig) {
        super.onConfigurationChanged(newGlobalConfig);
        updateConfiguration();
    }

    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
    }

    @Override
    public void onMergedOverrideConfigurationChanged(Configuration mergedOverrideConfig) {
        super.onRequestedOverrideConfigurationChanged(mergedOverrideConfig);
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        final Configuration requestedOverrideConfig = getRequestedOverrideConfiguration();
        if (requestedOverrideConfig.assetsSeq != ASSETS_SEQ_UNDEFINED
                && newParentConfig.assetsSeq > requestedOverrideConfig.assetsSeq) {
            requestedOverrideConfig.assetsSeq = ASSETS_SEQ_UNDEFINED;
        }
        super.resolveOverrideConfiguration(newParentConfig);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        // Make sure that we don't accidentally override the activity type.
        resolvedConfig.windowConfiguration.setActivityType(ACTIVITY_TYPE_UNDEFINED);
        // Activity has an independent ActivityRecord#mConfigurationSeq. If this process registers
        // activity configuration, its config seq shouldn't go backwards by activity configuration.
        // Otherwise if other places send wpc.getConfiguration() to client, the configuration may
        // be ignored due to the seq is older.
        resolvedConfig.seq = newParentConfig.seq;
    }

    private void updateConfiguration() {
        final Configuration config = getConfiguration();
        if (mLastReportedConfiguration.diff(config) == 0) {
            // Nothing changed.
            if (Build.IS_DEBUGGABLE && mHasImeService) {
                // TODO (b/135719017): Temporary log for debugging IME service.
                Slog.w(TAG_CONFIGURATION, "Current config: " + config
                        + " unchanged for IME proc " + mName);
            }
            return;
        }

        if (mPauseConfigurationDispatchCount > 0) {
            mHasPendingConfigurationChange = true;
            return;
        }
        dispatchConfiguration(config);
    }

    void dispatchConfiguration(Configuration config) {
        mHasPendingConfigurationChange = false;
        if (mThread == null) {
            if (Build.IS_DEBUGGABLE && mHasImeService) {
                // TODO (b/135719017): Temporary log for debugging IME service.
                Slog.w(TAG_CONFIGURATION, "Unable to send config for IME proc " + mName
                        + ": no app thread");
            }
            return;
        }
        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Sending to proc %s new config %s", mName,
                config);
        if (Build.IS_DEBUGGABLE && mHasImeService) {
            // TODO (b/135719017): Temporary log for debugging IME service.
            Slog.v(TAG_CONFIGURATION, "Sending to IME proc " + mName + " new config " + config);
        }

        try {
            config.seq = mAtm.increaseConfigurationSeqLocked();
            mAtm.getLifecycleManager().scheduleTransaction(mThread,
                    ConfigurationChangeItem.obtain(config));
            setLastReportedConfiguration(config);
        } catch (Exception e) {
            Slog.e(TAG_CONFIGURATION, "Failed to schedule configuration change", e);
        }
    }

    void setLastReportedConfiguration(Configuration config) {
        mLastReportedConfiguration.setTo(config);
    }

    Configuration getLastReportedConfiguration() {
        return mLastReportedConfiguration;
    }

    void pauseConfigurationDispatch() {
        mPauseConfigurationDispatchCount++;
    }

    /** Returns {@code true} if the configuration change is pending to dispatch. */
    boolean resumeConfigurationDispatch() {
        if (mPauseConfigurationDispatchCount == 0) {
            return false;
        }
        mPauseConfigurationDispatchCount--;
        return mHasPendingConfigurationChange;
    }

    void updateAssetConfiguration(int assetSeq) {
        // Update the process override configuration directly if the process configuration will
        // not be override from its activities.
        if (!mHasActivities || !mIsActivityConfigOverrideAllowed) {
            Configuration overrideConfig = new Configuration(getRequestedOverrideConfiguration());
            overrideConfig.assetsSeq = assetSeq;
            onRequestedOverrideConfigurationChanged(overrideConfig);
            return;
        }

        // Otherwise, we can just update the activity override configuration.
        for (int i = mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = mActivities.get(i);
            Configuration overrideConfig = new Configuration(r.getRequestedOverrideConfiguration());
            overrideConfig.assetsSeq = assetSeq;
            r.onRequestedOverrideConfigurationChanged(overrideConfig);
            if (r.mVisibleRequested) {
                r.ensureActivityConfiguration(0, true);
            }
        }
    }

    /**
     * This is called for sending {@link android.app.servertransaction.LaunchActivityItem}.
     * The caller must call {@link #setLastReportedConfiguration} if the delivered configuration
     * is newer.
     */
    Configuration prepareConfigurationForLaunchingActivity() {
        final Configuration config = getConfiguration();
        if (mHasPendingConfigurationChange) {
            mHasPendingConfigurationChange = false;
            // The global configuration may not change, so the client process may have the same
            // config seq. This increment ensures that the client won't ignore the configuration.
            config.seq = mAtm.increaseConfigurationSeqLocked();
        }
        return config;
    }

    /** Returns the total time (in milliseconds) spent executing in both user and system code. */
    public long getCpuTime() {
        return mListener.getCpuTime();
    }

    void addRecentTask(Task task) {
        mRecentTasks.add(task);
        mHasRecentTasks = true;
    }

    void removeRecentTask(Task task) {
        mRecentTasks.remove(task);
        mHasRecentTasks = !mRecentTasks.isEmpty();
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean hasRecentTasks() {
        return mHasRecentTasks;
    }

    void clearRecentTasks() {
        for (int i = mRecentTasks.size() - 1; i >= 0; i--) {
            mRecentTasks.get(i).clearRootProcess();
        }
        mRecentTasks.clear();
        mHasRecentTasks = false;
    }

    public void appEarlyNotResponding(String annotation, Runnable killAppCallback) {
        Runnable targetRunnable = null;
        synchronized (mAtm.mGlobalLock) {
            if (mAtm.mController == null) {
                return;
            }

            try {
                // 0 == continue, -1 = kill process immediately
                int res = mAtm.mController.appEarlyNotResponding(mName, mPid, annotation);
                if (res < 0 && mPid != MY_PID) {
                    targetRunnable = killAppCallback;
                }
            } catch (RemoteException e) {
                mAtm.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }
        if (targetRunnable != null) {
            targetRunnable.run();
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
            // Execute runnable outside WM lock since the runnable will hold AM lock
            targetRunnable.run();
            return true;
        }
        return false;
    }

    /**
     * Called to notify {@link WindowProcessController} of a started service.
     *
     * @param serviceInfo information describing the started service.
     */
    public void onServiceStarted(ServiceInfo serviceInfo) {
        String permission = serviceInfo.permission;
        if (permission == null) {
            return;
        }

        // TODO: Audit remaining services for disabling activity override (Wallpaper, Dream, etc).
        switch (permission) {
            case Manifest.permission.BIND_INPUT_METHOD:
                mHasImeService = true;
                // Fall-through
            case Manifest.permission.BIND_ACCESSIBILITY_SERVICE:
            case Manifest.permission.BIND_VOICE_INTERACTION:
                // We want to avoid overriding the config of these services with that of the
                // activity as it could lead to incorrect display metrics. For ex, IME services
                // expect their config to match the config of the display with the IME window
                // showing.
                mIsActivityConfigOverrideAllowed = false;
                break;
            default:
                break;
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public void onTopProcChanged() {
        if (mAtm.mVrController.isInterestingToSchedGroup()) {
            mAtm.mH.post(() -> {
                synchronized (mAtm.mGlobalLock) {
                    mAtm.mVrController.onTopProcChangedLocked(this);
                }
            });
        }
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isHomeProcess() {
        return this == mAtm.mHomeProcess;
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isPreviousProcess() {
        return this == mAtm.mPreviousProcess;
    }

    @HotPath(caller = HotPath.OOM_ADJUSTMENT)
    public boolean isHeavyWeightProcess() {
        return this == mAtm.mHeavyWeightProcess;
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

    void updateRunningRemoteOrRecentsAnimation() {
        // Posting on handler so WM lock isn't held when we call into AM.
        mAtm.mH.sendMessage(PooledLambda.obtainMessage(
                WindowProcessListener::setRunningRemoteAnimation, mListener,
                mRunningRecentsAnimation || mRunningRemoteAnimation));
    }

    /** Adjusts scheduling group for animation. This method MUST NOT be called inside WM lock. */
    void setRunningAnimationUnsafe() {
        mListener.setRunningRemoteAnimation(true);
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

            mBgLaunchController.dump(pw, prefix);
        }
        pw.println(prefix + " Configuration=" + getConfiguration());
        pw.println(prefix + " OverrideConfiguration=" + getRequestedOverrideConfiguration());
        pw.println(prefix + " mLastReportedConfiguration=" + mLastReportedConfiguration);

        final int stateFlags = mActivityStateFlags;
        if (stateFlags != ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER) {
            pw.print(prefix + " mActivityStateFlags=");
            if ((stateFlags & ACTIVITY_STATE_FLAG_IS_WINDOW_VISIBLE) != 0) {
                pw.print("W|");
            }
            if ((stateFlags & ACTIVITY_STATE_FLAG_IS_VISIBLE) != 0) {
                pw.print("V|");
                if ((stateFlags & ACTIVITY_STATE_FLAG_HAS_RESUMED) != 0) {
                    pw.print("R|");
                }
            } else if ((stateFlags & ACTIVITY_STATE_FLAG_IS_PAUSING_OR_PAUSED) != 0) {
                pw.print("P|");
            } else if ((stateFlags & ACTIVITY_STATE_FLAG_IS_STOPPING) != 0) {
                pw.print("S|");
                if ((stateFlags & ACTIVITY_STATE_FLAG_IS_STOPPING_FINISHING) != 0) {
                    pw.print("F|");
                }
            }
            final int taskLayer = stateFlags & ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER;
            if (taskLayer != ACTIVITY_STATE_FLAG_MASK_MIN_TASK_LAYER) {
                pw.print("taskLayer=" + taskLayer);
            }
            pw.println();
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        mListener.dumpDebug(proto, fieldId);
    }
}
