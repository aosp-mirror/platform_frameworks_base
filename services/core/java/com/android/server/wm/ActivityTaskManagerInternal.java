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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppProtoEnums;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.voice.IVoiceInteractionSession;
import android.util.IntArray;
import android.util.proto.ProtoOutputStream;
import android.window.TaskSnapshot;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.PendingIntentRecord;
import com.android.server.am.UserState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;

/**
 * Activity Task manager local system service interface.
 * @hide Only for use within system server
 */
public abstract class ActivityTaskManagerInternal {

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we drew
     * the splash screen.
     */
    public static final int APP_TRANSITION_SPLASH_SCREEN =
              AppProtoEnums.APP_TRANSITION_SPLASH_SCREEN; // 1

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we all
     * app windows were drawn
     */
    public static final int APP_TRANSITION_WINDOWS_DRAWN =
              AppProtoEnums.APP_TRANSITION_WINDOWS_DRAWN; // 2

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because of a
     * timeout.
     */
    public static final int APP_TRANSITION_TIMEOUT =
              AppProtoEnums.APP_TRANSITION_TIMEOUT; // 3

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because of a
     * we drew a task snapshot.
     */
    public static final int APP_TRANSITION_SNAPSHOT =
              AppProtoEnums.APP_TRANSITION_SNAPSHOT; // 4

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because it was a
     * recents animation and we only needed to wait on the wallpaper.
     */
    public static final int APP_TRANSITION_RECENTS_ANIM =
            AppProtoEnums.APP_TRANSITION_RECENTS_ANIM; // 5

    /**
     * The id of the task source of assist state.
     */
    public static final String ASSIST_TASK_ID = "taskId";

    /**
     * The id of the activity source of assist state.
     */
    public static final String ASSIST_ACTIVITY_ID = "activityId";

    /**
     * The bundle key to extract the assist data.
     */
    public static final String ASSIST_KEY_DATA = "data";

    /**
     * The bundle key to extract the assist structure.
     */
    public static final String ASSIST_KEY_STRUCTURE = "structure";

    /**
     * The bundle key to extract the assist content.
     */
    public static final String ASSIST_KEY_CONTENT = "content";

    /**
     * The bundle key to extract the assist receiver extras.
     */
    public static final String ASSIST_KEY_RECEIVER_EXTRAS = "receiverExtras";

    public interface ScreenObserver {
        void onAwakeStateChanged(boolean isAwake);
        void onKeyguardStateChanged(boolean isShowing);
    }

    /**
     * Sleep tokens cause the activity manager to put the top activity to sleep.
     * They are used by components such as dreams that may hide and block interaction
     * with underlying activities.
     * The Acquirer provides an interface that encapsulates the underlying work, so the user does
     * not need to handle the token by him/herself.
     */
    public interface SleepTokenAcquirer {

        /**
         * Acquires a sleep token.
         * @param displayId The display to apply to.
         */
        void acquire(int displayId);

        /**
         * Releases the sleep token.
         * @param displayId The display to apply to.
         */
        void release(int displayId);
    }

    /**
     * Creates a sleep token acquirer for the specified display with the specified tag.
     *
     * @param tag A string identifying the purpose (eg. "Dream").
     */
    public abstract SleepTokenAcquirer createSleepTokenAcquirer(@NonNull String tag);

    /**
     * Returns home activity for the specified user.
     *
     * @param userId ID of the user or {@link android.os.UserHandle#USER_ALL}
     */
    public abstract ComponentName getHomeActivityForUser(int userId);

    public abstract void onLocalVoiceInteractionStarted(IBinder callingActivity,
            IVoiceInteractionSession mSession,
            IVoiceInteractor mInteractor);

    /**
     * Returns the top activity from each of the currently visible root tasks, and the related task
     * id. The first entry will be the focused activity.
     */
    public abstract List<ActivityAssistInfo> getTopVisibleActivities();

    /**
     * Returns whether {@code uid} has any resumed activity.
     */
    public abstract boolean hasResumedActivity(int uid);

    /**
     * Start activity {@code intents} as if {@code packageName/featureId} on user {@code userId} did
     * it.
     *
     * - DO NOT call it with the calling UID cleared.
     * - All the necessary caller permission checks must be done at callsites.
     *
     * @return error codes used by {@link IActivityManager#startActivity} and its siblings.
     */
    public abstract int startActivitiesAsPackage(String packageName, String featureId,
            int userId, Intent[] intents, Bundle bOptions);

    /**
     * Start intents as a package.
     *
     * @param uid Make a call as if this UID did.
     * @param realCallingPid PID of the real caller.
     * @param realCallingUid UID of the real caller.
     * @param callingPackage Make a call as if this package did.
     * @param callingFeatureId Make a call as if this feature in the package did.
     * @param intents Intents to start.
     * @param userId Start the intents on this user.
     * @param validateIncomingUser Set true to skip checking {@code userId} with the calling UID.
     * @param originatingPendingIntent PendingIntentRecord that originated this activity start or
     *        null if not originated by PendingIntent
     * @param allowBackgroundActivityStart Whether the background activity start should be allowed
     *        from originatingPendingIntent
     */
    public abstract int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid,
            String callingPackage, @Nullable String callingFeatureId, Intent[] intents,
            String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId,
            boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent,
            boolean allowBackgroundActivityStart);

    public abstract int startActivityInPackage(int uid, int realCallingPid, int realCallingUid,
            String callingPackage, @Nullable String callingFeaturId, Intent intent,
            String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, SafeActivityOptions options, int userId, Task inTask, String reason,
            boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent,
            boolean allowBackgroundActivityStart);

    /**
     * Callback to be called on certain activity start scenarios.
     *
     * @see BackgroundActivityStartCallback
     */
    public abstract void setBackgroundActivityStartCallback(
            @Nullable BackgroundActivityStartCallback callback);

    /**
     * Sets the list of UIDs that contain an active accessibility service.
     */
    public abstract void setAccessibilityServiceUids(IntArray uids);

    /**
     * Start activity {@code intent} without calling user-id check.
     *
     * - DO NOT call it with the calling UID cleared.
     * - The caller must do the calling user ID check.
     *
     * @return error codes used by {@link IActivityManager#startActivity} and its siblings.
     */
    public abstract int startActivityAsUser(IApplicationThread caller, String callingPackage,
            @Nullable String callingFeatureId, Intent intent, @Nullable IBinder resultTo,
            int startFlags, @Nullable Bundle options, int userId);

    /**
     * Called when Keyguard flags might have changed.
     *
     * @param callback Callback to run after activity visibilities have been reevaluated. This can
     *                 be used from window manager so that when the callback is called, it's
     *                 guaranteed that all apps have their visibility updated accordingly.
     * @param displayId The id of the display where the keyguard flags changed.
     */
    public abstract void notifyKeyguardFlagsChanged(@Nullable Runnable callback, int displayId);

    /**
     * Called when the trusted state of Keyguard has changed.
     */
    public abstract void notifyKeyguardTrustedChanged();

    /**
     * Called after virtual display Id is updated by
     * {@link com.android.server.vr.Vr2dDisplay} with a specific
     * {@param vr2dDisplayId}.
     */
    public abstract void setVr2dDisplayId(int vr2dDisplayId);

    /**
     * Set focus on an activity.
     * @param token The IApplicationToken for the activity
     */
    public abstract void setFocusedActivity(IBinder token);

    public abstract void registerScreenObserver(ScreenObserver observer);

    /**
     * Returns is the caller has the same uid as the Recents component
     */
    public abstract boolean isCallerRecents(int callingUid);

    /**
     * Returns whether the recents component is the home activity for the given user.
     */
    public abstract boolean isRecentsComponentHomeActivity(int userId);

    /**
     * Returns true if the app can close system dialogs. Otherwise it either throws a {@link
     * SecurityException} or returns false with a logcat message depending on whether the app
     * targets SDK level {@link android.os.Build.VERSION_CODES#S} or not.
     */
    public abstract boolean checkCanCloseSystemDialogs(int pid, int uid,
            @Nullable String packageName);

    /**
     * Returns whether the app can close system dialogs or not.
     */
    public abstract boolean canCloseSystemDialogs(int pid, int uid);

    /**
     * Called after the voice interaction service has changed.
     */
    public abstract void notifyActiveVoiceInteractionServiceChanged(ComponentName component);

    /**
     * Called when the device changes its dreaming state.
     */
    public abstract void notifyDreamStateChanged(boolean dreaming);

    /**
     * Set a uid that is allowed to bypass stopped app switches, launching an app
     * whenever it wants.
     *
     * @param type Type of the caller -- unique string the caller supplies to identify itself
     * and disambiguate with other calles.
     * @param uid The uid of the app to be allowed, or -1 to clear the uid for this type.
     * @param userId The user it is allowed for.
     */
    public abstract void setAllowAppSwitches(@NonNull String type, int uid, int userId);

    /**
     * Called when a user has been stopped.
     *
     * @param userId The user being stopped.
     */
    public abstract void onUserStopped(int userId);
    public abstract boolean isGetTasksAllowed(String caller, int callingPid, int callingUid);

    public abstract void onProcessAdded(WindowProcessController proc);
    public abstract void onProcessRemoved(String name, int uid);
    public abstract void onCleanUpApplicationRecord(WindowProcessController proc);
    public abstract int getTopProcessState();
    public abstract void clearHeavyWeightProcessIfEquals(WindowProcessController proc);
    public abstract void finishHeavyWeightApp();

    public abstract boolean isDreaming();
    public abstract boolean isSleeping();
    public abstract boolean isShuttingDown();
    public abstract boolean shuttingDown(boolean booted, int timeout);
    public abstract void enableScreenAfterBoot(boolean booted);
    public abstract boolean showStrictModeViolationDialog();
    public abstract void showSystemReadyErrorDialogsIfNeeded();

    public abstract void onProcessMapped(int pid, WindowProcessController proc);
    public abstract void onProcessUnMapped(int pid);

    public abstract void onPackageDataCleared(String name);
    public abstract void onPackageUninstalled(String name);
    public abstract void onPackageAdded(String name, boolean replacing);
    public abstract void onPackageReplaced(ApplicationInfo aInfo);

    public abstract CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai);

    public final class ActivityTokens {
        private final @NonNull IBinder mActivityToken;
        private final @NonNull IBinder mAssistToken;
        private final @NonNull IBinder mShareableActivityToken;
        private final @NonNull IApplicationThread mAppThread;

        public ActivityTokens(@NonNull IBinder activityToken,
                @NonNull IBinder assistToken, @NonNull IApplicationThread appThread,
                @NonNull IBinder shareableActivityToken) {
            mActivityToken = activityToken;
            mAssistToken = assistToken;
            mAppThread = appThread;
            mShareableActivityToken = shareableActivityToken;
        }

        /**
         * @return The activity token.
         */
        public @NonNull IBinder getActivityToken() {
            return mActivityToken;
        }

        /**
         * @return The assist token.
         */
        public @NonNull IBinder getAssistToken() {
            return mAssistToken;
        }

        /**
         * @return The sharable activity token..
         */
        public @NonNull IBinder getShareableActivityToken() {
            return mShareableActivityToken;
        }

        /**
         * @return The assist token.
         */
        public @NonNull IApplicationThread getApplicationThread() {
            return mAppThread;
        }
    }

    public abstract void sendActivityResult(int callingUid, IBinder activityToken,
            String resultWho, int requestCode, int resultCode, Intent data);
    public abstract void clearPendingResultForActivity(
            IBinder activityToken, WeakReference<PendingIntentRecord> pir);

    /** Returns the component name of the activity token. */
    @Nullable
    public abstract ComponentName getActivityName(IBinder activityToken);

    /**
     * @return the activity token and IApplicationThread for the top activity in the task or null
     * if there isn't a top activity with a valid process.
     */
    @Nullable
    public abstract ActivityTokens getTopActivityForTask(int taskId);

    public abstract IIntentSender getIntentSender(int type, String packageName,
            @Nullable String featureId, int callingUid, int userId, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes, int flags,
            Bundle bOptions);

    /** @return the service connection holder for a given activity token. */
    public abstract ActivityServiceConnectionsHolder getServiceConnectionsHolder(IBinder token);

    /** @return The intent used to launch the home activity. */
    public abstract Intent getHomeIntent();
    public abstract boolean startHomeActivity(int userId, String reason);
    /**
     * This starts home activity on displays that can have system decorations based on displayId -
     * Default display always use primary home component.
     * For Secondary displays, the home activity must have category SECONDARY_HOME and then resolves
     * according to the priorities listed below.
     *  - If default home is not set, always use the secondary home defined in the config.
     *  - Use currently selected primary home activity.
     *  - Use the activity in the same package as currently selected primary home activity.
     *    If there are multiple activities matched, use first one.
     *  - Use the secondary home defined in the config.
     */
    public abstract boolean startHomeOnDisplay(int userId, String reason, int displayId,
            boolean allowInstrumenting, boolean fromHomeKey);
    /** Start home activities on all displays that support system decorations. */
    public abstract boolean startHomeOnAllDisplays(int userId, String reason);
    /** @return true if the given process is the factory test process. */
    public abstract boolean isFactoryTestProcess(WindowProcessController wpc);
    public abstract void updateTopComponentForFactoryTest();
    public abstract void handleAppDied(WindowProcessController wpc, boolean restarting,
            Runnable finishInstrumentationCallback);
    public abstract void closeSystemDialogs(String reason);

    /** Removes all components (e.g. activities, recents, ...) belonging to a disabled package. */
    public abstract void cleanupDisabledPackageComponents(
            String packageName, Set<String> disabledClasses, int userId, boolean booted);

    /** Called whenever AM force stops a package. */
    public abstract boolean onForceStopPackage(String packageName, boolean doit,
            boolean evenPersistent, int userId);
    /**
     * Resumes all top activities in the system if they aren't resumed already.
     * @param scheduleIdle If the idle message should be schedule after the top activities are
     *                     resumed.
     */
    public abstract void resumeTopActivities(boolean scheduleIdle);

    /** Called by AM just before it binds to an application process. */
    public abstract void preBindApplication(WindowProcessController wpc);

    /** Called by AM when an application process attaches. */
    public abstract boolean attachApplication(WindowProcessController wpc) throws RemoteException;

    /** @see IActivityManager#notifyLockedProfile(int) */
    public abstract void notifyLockedProfile(@UserIdInt int userId, int currentUserId);

    /** @see IActivityManager#startConfirmDeviceCredentialIntent(Intent, Bundle) */
    public abstract void startConfirmDeviceCredentialIntent(Intent intent, Bundle options);

    /** Writes current activity states to the proto stream. */
    public abstract void writeActivitiesToProto(ProtoOutputStream proto);

    /**
     * Saves the current activity manager state and includes the saved state in the next dump of
     * activity manager.
     */
    public abstract void saveANRState(String reason);

    /** Clears the previously saved activity manager ANR state. */
    public abstract void clearSavedANRState();

    /** Dump the current state based on the command. */
    public abstract void dump(String cmd, FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage);

    /** Dump the current state for inclusion in process dump. */
    public abstract boolean dumpForProcesses(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            String dumpPackage, int dumpAppId, boolean needSep, boolean testPssMode,
            int wakefulness);

    /** Writes the current window process states to the proto stream. */
    public abstract void writeProcessesToProto(ProtoOutputStream proto, String dumpPackage,
            int wakeFullness, boolean testPssMode);

    /** Dump the current activities state. */
    public abstract boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name,
            String[] args, int opti, boolean dumpAll, boolean dumpVisibleRootTasksOnly,
            boolean dumpFocusedRootTaskOnly);

    /** Dump the current state for inclusion in oom dump. */
    public abstract void dumpForOom(PrintWriter pw);

    /** @return true if it the activity management system is okay with GC running now. */
    public abstract boolean canGcNow();

    /** @return the process for the top-most resumed activity in the system. */
    public abstract WindowProcessController getTopApp();

    /** Destroy all activities. */
    public abstract void scheduleDestroyAllActivities(String reason);

    /** Remove user association with activities. */
    public abstract void removeUser(int userId);

    /** Switch current focused user for activities. */
    public abstract boolean switchUser(int userId, UserState userState);

    /** Called whenever an app crashes. */
    public abstract void onHandleAppCrash(WindowProcessController wpc);

    /**
     * Finish the topmost activities in all stacks that belong to the crashed app.
     * @param crashedApp The app that crashed.
     * @param reason Reason to perform this action.
     * @return The task id that was finished in this stack, or INVALID_TASK_ID if none was finished.
     */
    public abstract int finishTopCrashedActivities(
            WindowProcessController crashedApp, String reason);

    public abstract void onUidActive(int uid, int procState);
    public abstract void onUidInactive(int uid);
    public abstract void onUidProcStateChanged(int uid, int procState);

    public abstract void onUidAddedToPendingTempAllowlist(int uid, String tag);
    public abstract void onUidRemovedFromPendingTempAllowlist(int uid);

    /** Handle app crash event in {@link android.app.IActivityController} if there is one. */
    public abstract boolean handleAppCrashInActivityController(String processName, int pid,
            String shortMsg, String longMsg, long timeMillis, String stackTrace,
            Runnable killCrashingAppCallback);

    public abstract void removeRecentTasksByPackageName(String packageName, int userId);
    public abstract void cleanupRecentTasksForUser(int userId);
    public abstract void loadRecentTasksForUser(int userId);
    public abstract void onPackagesSuspendedChanged(String[] packages, boolean suspended,
            int userId);
    /** Flush recent tasks to disk. */
    public abstract void flushRecentTasks();

    public abstract void clearLockedTasks(String reason);
    public abstract void updateUserConfiguration();
    public abstract boolean canShowErrorDialogs();

    public abstract void setProfileApp(String profileApp);
    public abstract void setProfileProc(WindowProcessController wpc);
    public abstract void setProfilerInfo(ProfilerInfo profilerInfo);

    public abstract ActivityMetricsLaunchObserverRegistry getLaunchObserverRegistry();

    /**
     * Returns the URI permission owner associated with the given activity (see
     * {@link ActivityRecord#getUriPermissionsLocked()}). If the passed-in activity token is
     * invalid, returns null.
     */
    @Nullable
    public abstract IBinder getUriPermissionOwnerForActivity(@NonNull IBinder activityToken);

    /**
     * Gets bitmap snapshot of the provided task id.
     *
     * <p>Warning! this may restore the snapshot from disk so can block, don't call in a latency
     * sensitive environment.
     */
    public abstract TaskSnapshot getTaskSnapshotBlocking(int taskId,
            boolean isLowResolution);

    /** Returns true if uid is considered foreground for activity start purposes. */
    public abstract boolean isUidForeground(int uid);

    /**
     * Called by DevicePolicyManagerService to set the uid of the device owner.
     */
    public abstract void setDeviceOwnerUid(int uid);

    /**
     * Set all associated companion app that belongs to a userId.
     * @param userId
     * @param companionAppUids ActivityTaskManager will take ownership of this Set, the caller
     *                         shouldn't touch the Set after calling this interface.
     */
    public abstract void setCompanionAppUids(int userId, Set<Integer> companionAppUids);

    /**
     * @param packageName The package to check
     * @return Whether the package is the base of any locked task
     */
    public abstract boolean isBaseOfLockedTask(String packageName);

    /**
     * Create an interface to update configuration for an application.
     */
    public abstract PackageConfigurationUpdater createPackageConfigurationUpdater();

    /**
     * An interface to update configuration for an application, and will persist override
     * configuration for this package.
     */
    public interface PackageConfigurationUpdater {
        /**
         * Sets the dark mode for the current application. This setting is persisted and will
         * override the system configuration for this application.
         */
        PackageConfigurationUpdater setNightMode(int nightMode);

        /**
         * Commit changes.
         */
        void commit();
    }

    /**
     * A utility method to check AppOps and PackageManager for SYSTEM_ALERT_WINDOW permission.
     */
    public abstract boolean hasSystemAlertWindowPermission(int callingUid, int callingPid,
            String callingPackage);
}
