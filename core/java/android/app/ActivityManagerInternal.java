/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityPresentationInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.TransactionTooLargeException;
import android.view.RemoteAnimationAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class ActivityManagerInternal {


    // Access modes for handleIncomingUser.
    public static final int ALLOW_NON_FULL = 0;
    public static final int ALLOW_NON_FULL_IN_PROFILE = 1;
    public static final int ALLOW_FULL_ONLY = 2;

    /**
     * Verify that calling app has access to the given provider.
     */
    public abstract String checkContentProviderAccess(String authority, int userId);

    // Called by the power manager.
    public abstract void onWakefulnessChanged(int wakefulness);

    /**
     * @return {@code true} if process start is successful, {@code false} otherwise.
     */
    public abstract boolean startIsolatedProcess(String entryPoint, String[] mainArgs,
            String processName, String abiOverride, int uid, Runnable crashHandler);

    /**
     * Kill foreground apps from the specified user.
     */
    public abstract void killForegroundAppsForUser(int userHandle);

    /**
     *  Sets how long a {@link PendingIntent} can be temporarily whitelist to by bypass restrictions
     *  such as Power Save mode.
     */
    public abstract void setPendingIntentWhitelistDuration(IIntentSender target,
            IBinder whitelistToken, long duration);

    /**
     * Allows for a {@link PendingIntent} to be whitelisted to start activities from background.
     */
    public abstract void setPendingIntentAllowBgActivityStarts(
            IIntentSender target, IBinder whitelistToken, int flags);

    /**
     * Allow DeviceIdleController to tell us about what apps are whitelisted.
     */
    public abstract void setDeviceIdleWhitelist(int[] allAppids, int[] exceptIdleAppids);

    /**
     * Update information about which app IDs are on the temp whitelist.
     */
    public abstract void updateDeviceIdleTempWhitelist(int[] appids, int changingAppId,
            boolean adding);

    /**
     * Get the procstate for the UID.  The return value will be between
     * {@link ActivityManager#MIN_PROCESS_STATE} and {@link ActivityManager#MAX_PROCESS_STATE}.
     * Note if the UID doesn't exist, it'll return {@link ActivityManager#PROCESS_STATE_NONEXISTENT}
     * (-1).
     */
    public abstract int getUidProcessState(int uid);

    /**
     * @return {@code true} if system is ready, {@code false} otherwise.
     */
    public abstract boolean isSystemReady();

    /**
     * Sets if the given pid has an overlay UI or not.
     *
     * @param pid The pid we are setting overlay UI for.
     * @param hasOverlayUi True if the process has overlay UI.
     * @see android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
     */
    public abstract void setHasOverlayUi(int pid, boolean hasOverlayUi);

    /**
     * Sets if the given pid is currently running a remote animation, which is taken a signal for
     * determining oom adjustment and scheduling behavior.
     *
     * @param pid The pid we are setting overlay UI for.
     * @param runningRemoteAnimation True if the process is running a remote animation, false
     *                               otherwise.
     * @see RemoteAnimationAdapter
     */
    public abstract void setRunningRemoteAnimation(int pid, boolean runningRemoteAnimation);

    /**
     * Called after the network policy rules are updated by
     * {@link com.android.server.net.NetworkPolicyManagerService} for a specific {@param uid} and
     * {@param procStateSeq}.
     */
    public abstract void notifyNetworkPolicyRulesUpdated(int uid, long procStateSeq);

    /**
     * @return true if runtime was restarted, false if it's normal boot
     */
    public abstract boolean isRuntimeRestarted();

    /**
     * Returns if more users can be started without stopping currently running users.
     */
    public abstract boolean canStartMoreUsers();

    /**
     * Sets the user switcher message for switching from {@link android.os.UserHandle#SYSTEM}.
     */
    public abstract void setSwitchingFromSystemUserMessage(String switchingFromSystemUserMessage);

    /**
     * Sets the user switcher message for switching to {@link android.os.UserHandle#SYSTEM}.
     */
    public abstract void setSwitchingToSystemUserMessage(String switchingToSystemUserMessage);

    /**
     * Returns maximum number of users that can run simultaneously.
     */
    public abstract int getMaxRunningUsers();

    /**
     * Whether an UID is active or idle.
     */
    public abstract boolean isUidActive(int uid);

    /**
     * Returns a list that contains the memory stats for currently running processes.
     *
     * Only processes managed by ActivityManagerService are included.
     */
    public abstract List<ProcessMemoryState> getMemoryStateForProcesses();

    /**
     * Returns a list that contains the memory high-water mark for currently running processes.
     *
     * Only processes managed by ActivityManagerService are included.
     */
    public abstract List<ProcessMemoryHighWaterMark> getMemoryHighWaterMarkForProcesses();

    /**
     * Checks to see if the calling pid is allowed to handle the user. Returns adjusted user id as
     * needed.
     */
    public abstract int handleIncomingUser(int callingPid, int callingUid, int userId,
            boolean allowAll, int allowMode, String name, String callerPackage);

    /** Checks if the calling binder pid as the permission. */
    public abstract void enforceCallingPermission(String permission, String func);

    /** Returns the current user id. */
    public abstract int getCurrentUserId();

    /** Returns true if the user is running. */
    public abstract boolean isUserRunning(int userId, int flags);

    /** Trims memory usage in the system by removing/stopping unused application processes. */
    public abstract void trimApplications();

    /** Kill the processes in the list due to their tasks been removed. */
    public abstract void killProcessesForRemovedTask(ArrayList<Object> procsToKill);

    /**
     * Returns {@code true} if {@code uid} is running an activity from {@code packageName}.
     */
    public abstract boolean hasRunningActivity(int uid, @Nullable String packageName);

    public abstract void updateOomAdj();
    public abstract void updateCpuStats();

    /**
     * Update battery stats on activity usage.
     * @param activity
     * @param uid
     * @param userId
     * @param started
     */
    public abstract void updateBatteryStats(
            ComponentName activity, int uid, int userId, boolean resumed);

    /**
     * Update UsageStats of the activity.
     * @param activity
     * @param userId
     * @param event
     * @param appToken ActivityRecord's appToken.
     * @param taskRoot TaskRecord's root
     */
    public abstract void updateActivityUsageStats(
            ComponentName activity, int userId, int event, IBinder appToken,
            ComponentName taskRoot);
    public abstract void updateForegroundTimeIfOnBattery(
            String packageName, int uid, long cpuTimeDiff);
    public abstract void sendForegroundProfileChanged(int userId);

    /**
     * Returns whether the given user requires credential entry at this time. This is used to
     * intercept activity launches for work apps when the Work Challenge is present.
     */
    public abstract boolean shouldConfirmCredentials(int userId);

    public abstract int[] getCurrentProfileIds();
    public abstract UserInfo getCurrentUser();
    public abstract void ensureNotSpecialUser(int userId);
    public abstract boolean isCurrentProfile(int userId);
    public abstract boolean hasStartedUserState(int userId);
    public abstract void finishUserSwitch(Object uss);

    /** Schedule the execution of all pending app GCs. */
    public abstract void scheduleAppGcs();

    /** Gets the task id for a given activity. */
    public abstract int getTaskIdForActivity(@NonNull IBinder token, boolean onlyRoot);

    /** Gets the basic info for a given activity. */
    public abstract ActivityPresentationInfo getActivityPresentationInfo(@NonNull IBinder token);

    public abstract void setBooting(boolean booting);
    public abstract boolean isBooting();
    public abstract void setBooted(boolean booted);
    public abstract boolean isBooted();
    public abstract void finishBooting();

    public abstract void tempWhitelistForPendingIntent(int callerPid, int callerUid, int targetUid,
            long duration, String tag);
    public abstract int broadcastIntentInPackage(String packageName, int uid, int realCallingUid,
            int realCallingPid, Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle resultExtras, String requiredPermission,
            Bundle bOptions, boolean serialized, boolean sticky, int userId,
            boolean allowBackgroundActivityStarts);
    public abstract ComponentName startServiceInPackage(int uid, Intent service,
            String resolvedType, boolean fgRequired, String callingPackage, int userId,
            boolean allowBackgroundActivityStarts) throws TransactionTooLargeException;

    public abstract void disconnectActivityFromServices(Object connectionHolder);
    public abstract void cleanUpServices(int userId, ComponentName component, Intent baseIntent);
    public abstract ActivityInfo getActivityInfoForUser(ActivityInfo aInfo, int userId);
    public abstract void ensureBootCompleted();
    public abstract void updateOomLevelsForDisplay(int displayId);
    public abstract boolean isActivityStartsLoggingEnabled();
    /** Returns true if the background activity starts is enabled. */
    public abstract boolean isBackgroundActivityStartsEnabled();
    public abstract void reportCurKeyguardUsageEvent(boolean keyguardShowing);

    /** Input dispatch timeout to a window, start the ANR process. */
    public abstract long inputDispatchingTimedOut(int pid, boolean aboveSystem, String reason);
    public abstract boolean inputDispatchingTimedOut(Object proc, String activityShortComponentName,
            ApplicationInfo aInfo, String parentShortComponentName, Object parentProc,
            boolean aboveSystem, String reason);

    /**
     * Sends {@link android.content.Intent#ACTION_CONFIGURATION_CHANGED} with all the appropriate
     * flags.
     */
    public abstract void broadcastGlobalConfigurationChanged(int changes, boolean initLocale);

    /**
     * Sends {@link android.content.Intent#ACTION_CLOSE_SYSTEM_DIALOGS} with all the appropriate
     * flags.
     */
    public abstract void broadcastCloseSystemDialogs(String reason);

    /**
     * Kills all background processes, except those matching any of the specified properties.
     *
     * @param minTargetSdk the target SDK version at or above which to preserve processes,
     *                     or {@code -1} to ignore the target SDK
     * @param maxProcState the process state at or below which to preserve processes,
     *                     or {@code -1} to ignore the process state
     */
    public abstract void killAllBackgroundProcessesExcept(int minTargetSdk, int maxProcState);

    /** Starts a given process. */
    public abstract void startProcess(String processName, ApplicationInfo info,
            boolean knownToBeDead, String hostingType, ComponentName hostingName);

    /** Starts up the starting activity process for debugging if needed.
     * This function needs to be called synchronously from WindowManager context so the caller
     * passes a lock {@code wmLock} and waits to be notified.
     *
     * @param wmLock calls {@code notify} on the object to wake up the caller.
    */
    public abstract void setDebugFlagsForStartingActivity(ActivityInfo aInfo, int startFlags,
            ProfilerInfo profilerInfo, Object wmLock);

    /** Returns mount mode for process running with given pid */
    public abstract int getStorageMountMode(int pid, int uid);

    /** Returns true if the given uid is the app in the foreground. */
    public abstract boolean isAppForeground(int uid);

    /** Remove pending backup for the given userId. */
    public abstract void clearPendingBackup(int userId);

    /**
     * When power button is very long pressed, call this interface to do some pre-shutdown work
     * like persisting database etc.
     */
    public abstract void prepareForPossibleShutdown();
}
