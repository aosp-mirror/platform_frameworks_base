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
import android.content.IIntentSender;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.voice.IVoiceInteractionSession;
import android.util.SparseIntArray;
import android.view.RemoteAnimationAdapter;

import com.android.internal.app.IVoiceInteractor;

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
     * Grant Uri permissions from one app to another. This method only extends
     * permission grants if {@code callingUid} has permission to them.
     */
    public abstract void grantUriPermissionFromIntent(int callingUid, String targetPkg,
            Intent intent, int targetUserId);

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
     * Saves the current activity manager state and includes the saved state in the next dump of
     * activity manager.
     */
    public abstract void saveANRState(String reason);

    /**
     * Clears the previously saved activity manager ANR state.
     */
    public abstract void clearSavedANRState();

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
     */
    public abstract List<ProcessMemoryState> getMemoryStateForProcesses();

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

    /** Returns the screen compatibility mode for the given application. */
    public abstract int getPackageScreenCompatMode(ApplicationInfo ai);

    /** Sets the screen compatibility mode for the given application. */
    public abstract void setPackageScreenCompatMode(ApplicationInfo ai, int mode);

    /** Closes all system dialogs. */
    public abstract void closeSystemDialogs(String reason);

    /** Kill the processes in the list due to their tasks been removed. */
    public abstract void killProcessesForRemovedTask(ArrayList<Object> procsToKill);

    /**
     * Returns {@code true} if {@code uid} is running an activity from {@code packageName}.
     */
    public abstract boolean hasRunningActivity(int uid, @Nullable String packageName);
}
