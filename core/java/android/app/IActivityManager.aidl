/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ApplicationErrorReport;
import android.app.ContentProviderHolder;
import android.app.GrantedUriPermission;
import android.app.IApplicationThread;
import android.app.IActivityController;
import android.app.IAppTask;
import android.app.IAssistDataReceiver;
import android.app.IInstrumentationWatcher;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidObserver;
import android.app.IUserSwitchObserver;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.StrictMode;
import android.os.WorkSource;
import android.service.voice.IVoiceInteractionSession;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationAdapter;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;

import java.util.List;

/**
 * System private API for talking with the activity manager service.  This
 * provides calls from the application back to the activity manager.
 *
 * {@hide}
 */
interface IActivityManager {
    // WARNING: when these transactions are updated, check if they are any callers on the native
    // side. If so, make sure they are using the correct transaction ids and arguments.
    // If a transaction which will also be used on the native side is being inserted, add it to
    // below block of transactions.

    // Since these transactions are also called from native code, these must be kept in sync with
    // the ones in frameworks/native/libs/binder/include/binder/IActivityManager.h
    // =============== Beginning of transactions used on native side as well ======================
    ParcelFileDescriptor openContentUri(in String uriString);
    void registerUidObserver(in IUidObserver observer, int which, int cutpoint,
            String callingPackage);
    void unregisterUidObserver(in IUidObserver observer);
    boolean isUidActive(int uid, String callingPackage);
    int getUidProcessState(int uid, in String callingPackage);
    // =============== End of transactions used on native side as well ============================

    // Special low-level communication with activity manager.
    void handleApplicationCrash(in IBinder app,
            in ApplicationErrorReport.ParcelableCrashInfo crashInfo);
    @UnsupportedAppUsage
    int startActivity(in IApplicationThread caller, in String callingPackage, in Intent intent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flags, in ProfilerInfo profilerInfo, in Bundle options);
    @UnsupportedAppUsage
    void unhandledBack();
    @UnsupportedAppUsage
    boolean finishActivity(in IBinder token, int code, in Intent data, int finishTask);
    @UnsupportedAppUsage
    Intent registerReceiver(in IApplicationThread caller, in String callerPackage,
            in IIntentReceiver receiver, in IntentFilter filter,
            in String requiredPermission, int userId, int flags);
    @UnsupportedAppUsage
    void unregisterReceiver(in IIntentReceiver receiver);
    @UnsupportedAppUsage
    int broadcastIntent(in IApplicationThread caller, in Intent intent,
            in String resolvedType, in IIntentReceiver resultTo, int resultCode,
            in String resultData, in Bundle map, in String[] requiredPermissions,
            int appOp, in Bundle options, boolean serialized, boolean sticky, int userId);
    void unbroadcastIntent(in IApplicationThread caller, in Intent intent, int userId);
    oneway void finishReceiver(in IBinder who, int resultCode, in String resultData, in Bundle map,
            boolean abortBroadcast, int flags);
    void attachApplication(in IApplicationThread app, long startSeq);
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);
    @UnsupportedAppUsage
    List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum, int ignoreActivityType,
            int ignoreWindowingMode);
    @UnsupportedAppUsage
    void moveTaskToFront(in IApplicationThread caller, in String callingPackage, int task,
            int flags, in Bundle options);
    @UnsupportedAppUsage
    int getTaskForActivity(in IBinder token, in boolean onlyRoot);
    ContentProviderHolder getContentProvider(in IApplicationThread caller, in String callingPackage,
            in String name, int userId, boolean stable);
    @UnsupportedAppUsage
    void publishContentProviders(in IApplicationThread caller,
            in List<ContentProviderHolder> providers);
    boolean refContentProvider(in IBinder connection, int stableDelta, int unstableDelta);
    PendingIntent getRunningServiceControlPanel(in ComponentName service);
    ComponentName startService(in IApplicationThread caller, in Intent service,
            in String resolvedType, boolean requireForeground, in String callingPackage, int userId);
    @UnsupportedAppUsage
    int stopService(in IApplicationThread caller, in Intent service,
            in String resolvedType, int userId);
    // Currently keeping old bindService because it is on the greylist
    @UnsupportedAppUsage
    int bindService(in IApplicationThread caller, in IBinder token, in Intent service,
            in String resolvedType, in IServiceConnection connection, int flags,
            in String callingPackage, int userId);
    int bindIsolatedService(in IApplicationThread caller, in IBinder token, in Intent service,
            in String resolvedType, in IServiceConnection connection, int flags,
            in String instanceName, in String callingPackage, int userId);
    void updateServiceGroup(in IServiceConnection connection, int group, int importance);
    @UnsupportedAppUsage
    boolean unbindService(in IServiceConnection connection);
    void publishService(in IBinder token, in Intent intent, in IBinder service);
    @UnsupportedAppUsage
    void setDebugApp(in String packageName, boolean waitForDebugger, boolean persistent);
    void setAgentApp(in String packageName, @nullable String agent);
    @UnsupportedAppUsage
    void setAlwaysFinish(boolean enabled);
    @UnsupportedAppUsage
    boolean startInstrumentation(in ComponentName className, in String profileFile,
            int flags, in Bundle arguments, in IInstrumentationWatcher watcher,
            in IUiAutomationConnection connection, int userId,
            in String abiOverride);
    void addInstrumentationResults(in IApplicationThread target, in Bundle results);
    void finishInstrumentation(in IApplicationThread target, int resultCode,
            in Bundle results);
    /**
     * @return A copy of global {@link Configuration}, contains general settings for the entire
     *         system. Corresponds to the configuration of the default display.
     * @throws RemoteException
     */
    @UnsupportedAppUsage
    Configuration getConfiguration();
    /**
     * Updates global configuration and applies changes to the entire system.
     * @param values Update values for global configuration. If null is passed it will request the
     *               Window Manager to compute new config for the default display.
     * @throws RemoteException
     * @return Returns true if the configuration was updated.
     */
    @UnsupportedAppUsage
    boolean updateConfiguration(in Configuration values);
    boolean stopServiceToken(in ComponentName className, in IBinder token, int startId);
    @UnsupportedAppUsage
    void setProcessLimit(int max);
    @UnsupportedAppUsage
    int getProcessLimit();
    @UnsupportedAppUsage
    int checkPermission(in String permission, int pid, int uid);
    int checkUriPermission(in Uri uri, int pid, int uid, int mode, int userId,
            in IBinder callerToken);
    void grantUriPermission(in IApplicationThread caller, in String targetPkg, in Uri uri,
            int mode, int userId);
    void revokeUriPermission(in IApplicationThread caller, in String targetPkg, in Uri uri,
            int mode, int userId);
    @UnsupportedAppUsage
    void setActivityController(in IActivityController watcher, boolean imAMonkey);
    void showWaitingForDebugger(in IApplicationThread who, boolean waiting);
    /*
     * This will deliver the specified signal to all the persistent processes. Currently only
     * SIGUSR1 is delivered. All others are ignored.
     */
    void signalPersistentProcesses(int signal);

    @UnsupportedAppUsage
    ParceledListSlice getRecentTasks(int maxNum, int flags, int userId);
    oneway void serviceDoneExecuting(in IBinder token, int type, int startId, int res);
    @UnsupportedAppUsage
    IIntentSender getIntentSender(int type, in String packageName, in IBinder token,
            in String resultWho, int requestCode, in Intent[] intents, in String[] resolvedTypes,
            int flags, in Bundle options, int userId);
    void cancelIntentSender(in IIntentSender sender);
    String getPackageForIntentSender(in IIntentSender sender);
    void registerIntentSenderCancelListener(in IIntentSender sender, in IResultReceiver receiver);
    void unregisterIntentSenderCancelListener(in IIntentSender sender, in IResultReceiver receiver);
    void enterSafeMode();
    void noteWakeupAlarm(in IIntentSender sender, in WorkSource workSource, int sourceUid,
            in String sourcePkg, in String tag);
    oneway void removeContentProvider(in IBinder connection, boolean stable);
    @UnsupportedAppUsage
    void setRequestedOrientation(in IBinder token, int requestedOrientation);
    void unbindFinished(in IBinder token, in Intent service, boolean doRebind);
    @UnsupportedAppUsage
    void setProcessImportant(in IBinder token, int pid, boolean isForeground, String reason);
    void setServiceForeground(in ComponentName className, in IBinder token,
            int id, in Notification notification, int flags, int foregroundServiceType);
    int getForegroundServiceType(in ComponentName className, in IBinder token);
    @UnsupportedAppUsage
    boolean moveActivityTaskToBack(in IBinder token, boolean nonRoot);
    @UnsupportedAppUsage
    void getMemoryInfo(out ActivityManager.MemoryInfo outInfo);
    List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState();
    boolean clearApplicationUserData(in String packageName, boolean keepState,
            in IPackageDataObserver observer, int userId);
    @UnsupportedAppUsage
    void forceStopPackage(in String packageName, int userId);
    boolean killPids(in int[] pids, in String reason, boolean secure);
    @UnsupportedAppUsage
    List<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags);
    // Retrieve running application processes in the system
    @UnsupportedAppUsage
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses();
    IBinder peekService(in Intent service, in String resolvedType, in String callingPackage);
    // Turn on/off profiling in a particular process.
    @UnsupportedAppUsage
    boolean profileControl(in String process, int userId, boolean start,
            in ProfilerInfo profilerInfo, int profileType);
    @UnsupportedAppUsage
    boolean shutdown(int timeout);
    @UnsupportedAppUsage
    void stopAppSwitches();
    @UnsupportedAppUsage
    void resumeAppSwitches();
    boolean bindBackupAgent(in String packageName, int backupRestoreMode, int targetUserId);
    void backupAgentCreated(in String packageName, in IBinder agent, int userId);
    void unbindBackupAgent(in ApplicationInfo appInfo);
    int getUidForIntentSender(in IIntentSender sender);
    int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            boolean requireFull, in String name, in String callerPackage);
    void addPackageDependency(in String packageName);
    void killApplication(in String pkg, int appId, int userId, in String reason);
    @UnsupportedAppUsage
    void closeSystemDialogs(in String reason);
    @UnsupportedAppUsage
    Debug.MemoryInfo[] getProcessMemoryInfo(in int[] pids);
    void killApplicationProcess(in String processName, int uid);
    // Special low-level communication with activity manager.
    boolean handleApplicationWtf(in IBinder app, in String tag, boolean system,
            in ApplicationErrorReport.ParcelableCrashInfo crashInfo);
    @UnsupportedAppUsage
    void killBackgroundProcesses(in String packageName, int userId);
    boolean isUserAMonkey();
    // Retrieve info of applications installed on external media that are currently
    // running.
    List<ApplicationInfo> getRunningExternalApplications();
    @UnsupportedAppUsage
    void finishHeavyWeightApp();
    // A StrictMode violation to be handled.
    @UnsupportedAppUsage
    void handleApplicationStrictModeViolation(in IBinder app, int penaltyMask,
            in StrictMode.ViolationInfo crashInfo);
    boolean isTopActivityImmersive();
    void crashApplication(int uid, int initialPid, in String packageName, int userId, in String message);
    @UnsupportedAppUsage
    String getProviderMimeType(in Uri uri, int userId);
    // Cause the specified process to dump the specified heap.
    boolean dumpHeap(in String process, int userId, boolean managed, boolean mallocInfo,
            boolean runGc, in String path, in ParcelFileDescriptor fd,
            in RemoteCallback finishCallback);
    @UnsupportedAppUsage
    boolean isUserRunning(int userid, int flags);
    @UnsupportedAppUsage
    void setPackageScreenCompatMode(in String packageName, int mode);
    @UnsupportedAppUsage
    boolean switchUser(int userid);
    @UnsupportedAppUsage
    boolean removeTask(int taskId);
    @UnsupportedAppUsage
    void registerProcessObserver(in IProcessObserver observer);
    @UnsupportedAppUsage
    void unregisterProcessObserver(in IProcessObserver observer);
    boolean isIntentSenderTargetedToPackage(in IIntentSender sender);
    @UnsupportedAppUsage
    void updatePersistentConfiguration(in Configuration values);
    @UnsupportedAppUsage
    long[] getProcessPss(in int[] pids);
    void showBootMessage(in CharSequence msg, boolean always);
    @UnsupportedAppUsage
    void killAllBackgroundProcesses();
    ContentProviderHolder getContentProviderExternal(in String name, int userId,
            in IBinder token, String tag);
    /** @deprecated - Use {@link #removeContentProviderExternalAsUser} which takes a user ID. */
    @UnsupportedAppUsage
    void removeContentProviderExternal(in String name, in IBinder token);
    void removeContentProviderExternalAsUser(in String name, in IBinder token, int userId);
    // Get memory information about the calling process.
    void getMyMemoryState(out ActivityManager.RunningAppProcessInfo outInfo);
    boolean killProcessesBelowForeground(in String reason);
    @UnsupportedAppUsage
    UserInfo getCurrentUser();
    // This is not public because you need to be very careful in how you
    // manage your activity to make sure it is always the uid you expect.
    @UnsupportedAppUsage
    int getLaunchedFromUid(in IBinder activityToken);
    @UnsupportedAppUsage
    void unstableProviderDied(in IBinder connection);
    @UnsupportedAppUsage
    boolean isIntentSenderAnActivity(in IIntentSender sender);
    boolean isIntentSenderAForegroundService(in IIntentSender sender);
    boolean isIntentSenderABroadcast(in IIntentSender sender);
    @UnsupportedAppUsage
    int startActivityAsUser(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo,
            in Bundle options, int userId);
    @UnsupportedAppUsage
    int stopUser(int userid, boolean force, in IStopUserCallback callback);
    @UnsupportedAppUsage
    void registerUserSwitchObserver(in IUserSwitchObserver observer, in String name);
    void unregisterUserSwitchObserver(in IUserSwitchObserver observer);
    int[] getRunningUserIds();

    // Request a heap dump for the system server.
    void requestSystemServerHeapDump();

    // Deprecated - This method is only used by a few internal components and it will soon be
    // replaced by a proper bug report API (which will be restricted to a few, pre-defined apps).
    // No new code should be calling it.
    @UnsupportedAppUsage
    void requestBugReport(int bugreportType);

    /**
     *  Takes a telephony bug report and notifies the user with the title and description
     *  that are passed to this API as parameters
     *
     *  @param shareTitle should be a valid legible string less than 50 chars long
     *  @param shareDescription should be less than 91 bytes when encoded into UTF-8 format
     *
     *  @throws IllegalArgumentException if shareTitle or shareDescription is too big or if the
     *          paremeters cannot be encoding to an UTF-8 charset.
     */
    void requestTelephonyBugReport(in String shareTitle, in String shareDescription);

    /**
     *  Deprecated - This method is only used by Wifi, and it will soon be replaced by a proper
     *  bug report API.
     *
     *  Takes a minimal bugreport of Wifi-related state.
     *
     *  @param shareTitle should be a valid legible string less than 50 chars long
     *  @param shareDescription should be less than 91 bytes when encoded into UTF-8 format
     *
     *  @throws IllegalArgumentException if shareTitle or shareDescription is too big or if the
     *          parameters cannot be encoding to an UTF-8 charset.
     */
    void requestWifiBugReport(in String shareTitle, in String shareDescription);

    @UnsupportedAppUsage
    Intent getIntentForIntentSender(in IIntentSender sender);
    // This is not public because you need to be very careful in how you
    // manage your activity to make sure it is always the uid you expect.
    @UnsupportedAppUsage
    String getLaunchedFromPackage(in IBinder activityToken);
    void killUid(int appId, int userId, in String reason);
    void setUserIsMonkey(boolean monkey);
    @UnsupportedAppUsage
    void hang(in IBinder who, boolean allowRestart);

    @UnsupportedAppUsage
    List<ActivityManager.StackInfo> getAllStackInfos();
    @UnsupportedAppUsage
    void moveTaskToStack(int taskId, int stackId, boolean toTop);
    /**
     * Resizes the input stack id to the given bounds.
     *
     * @param stackId Id of the stack to resize.
     * @param bounds Bounds to resize the stack to or {@code null} for fullscreen.
     * @param allowResizeInDockedMode True if the resize should be allowed when the docked stack is
     *                                active.
     * @param preserveWindows True if the windows of activities contained in the stack should be
     *                        preserved.
     * @param animate True if the stack resize should be animated.
     * @param animationDuration The duration of the resize animation in milliseconds or -1 if the
     *                          default animation duration should be used.
     * @throws RemoteException
     */
    @UnsupportedAppUsage
    void resizeStack(int stackId, in Rect bounds, boolean allowResizeInDockedMode,
            boolean preserveWindows, boolean animate, int animationDuration);
    void setFocusedStack(int stackId);
    ActivityManager.StackInfo getFocusedStackInfo();
    @UnsupportedAppUsage
    void restart();
    void performIdleMaintenance();
    void appNotRespondingViaProvider(in IBinder connection);
    @UnsupportedAppUsage
    Rect getTaskBounds(int taskId);
    @UnsupportedAppUsage
    boolean setProcessMemoryTrimLevel(in String process, int uid, int level);


    // Start of L transactions
    String getTagForIntentSender(in IIntentSender sender, in String prefix);
    @UnsupportedAppUsage
    boolean startUserInBackground(int userid);
    @UnsupportedAppUsage
    boolean isInLockTaskMode();
    @UnsupportedAppUsage
    void startRecentsActivity(in Intent intent, in IAssistDataReceiver assistDataReceiver,
            in IRecentsAnimationRunner recentsAnimationRunner);
    @UnsupportedAppUsage
    void cancelRecentsAnimation(boolean restoreHomeStackPosition);
    @UnsupportedAppUsage
    int startActivityFromRecents(int taskId, in Bundle options);
    @UnsupportedAppUsage
    void startSystemLockTaskMode(int taskId);
    @UnsupportedAppUsage
    boolean isTopOfTask(in IBinder token);
    void bootAnimationComplete();
    int checkPermissionWithToken(in String permission, int pid, int uid,
            in IBinder callerToken);
    @UnsupportedAppUsage
    void registerTaskStackListener(in ITaskStackListener listener);
    void unregisterTaskStackListener(in ITaskStackListener listener);
    void notifyCleartextNetwork(int uid, in byte[] firstPacket);
    @UnsupportedAppUsage
    void setTaskResizeable(int taskId, int resizeableMode);
    @UnsupportedAppUsage
    void resizeTask(int taskId, in Rect bounds, int resizeMode);
    @UnsupportedAppUsage
    int getLockTaskModeState();
    @UnsupportedAppUsage
    void setDumpHeapDebugLimit(in String processName, int uid, long maxMemSize,
            in String reportPackage);
    void dumpHeapFinished(in String path);
    void updateLockTaskPackages(int userId, in String[] packages);
    void noteAlarmStart(in IIntentSender sender, in WorkSource workSource, int sourceUid, in String tag);
    void noteAlarmFinish(in IIntentSender sender, in WorkSource workSource, int sourceUid, in String tag);
    @UnsupportedAppUsage
    int getPackageProcessState(in String packageName, in String callingPackage);
    void updateDeviceOwner(in String packageName);

    // Start of N transactions
    // Start Binder transaction tracking for all applications.
    @UnsupportedAppUsage
    boolean startBinderTracking();
    // Stop Binder transaction tracking for all applications and dump trace data to the given file
    // descriptor.
    @UnsupportedAppUsage
    boolean stopBinderTrackingAndDump(in ParcelFileDescriptor fd);
    /**
     * Try to place task to provided position. The final position might be different depending on
     * current user and stacks state. The task will be moved to target stack if it's currently in
     * different stack.
     */
    @UnsupportedAppUsage
    void positionTaskInStack(int taskId, int stackId, int position);
    @UnsupportedAppUsage
    void suppressResizeConfigChanges(boolean suppress);
    @UnsupportedAppUsage
    boolean moveTopActivityToPinnedStack(int stackId, in Rect bounds);
    boolean isAppStartModeDisabled(int uid, in String packageName);
    @UnsupportedAppUsage
    boolean unlockUser(int userid, in byte[] token, in byte[] secret,
            in IProgressListener listener);
    void killPackageDependents(in String packageName, int userId);
    /**
     * Resizes the docked stack, and all other stacks as the result of the dock stack bounds change.
     *
     * @param dockedBounds The bounds for the docked stack.
     * @param tempDockedTaskBounds The temporary bounds for the tasks in the docked stack, which
     *                             might be different from the stack bounds to allow more
     *                             flexibility while resizing, or {@code null} if they should be the
     *                             same as the stack bounds.
     * @param tempDockedTaskInsetBounds The temporary bounds for the tasks to calculate the insets.
     *                                  When resizing, we usually "freeze" the layout of a task. To
     *                                  achieve that, we also need to "freeze" the insets, which
     *                                  gets achieved by changing task bounds but not bounds used
     *                                  to calculate the insets in this transient state
     * @param tempOtherTaskBounds The temporary bounds for the tasks in all other stacks, or
     *                            {@code null} if they should be the same as the stack bounds.
     * @param tempOtherTaskInsetBounds Like {@code tempDockedTaskInsetBounds}, but for the other
     *                                 stacks.
     * @throws RemoteException
     */
    @UnsupportedAppUsage
    void resizeDockedStack(in Rect dockedBounds, in Rect tempDockedTaskBounds,
            in Rect tempDockedTaskInsetBounds,
            in Rect tempOtherTaskBounds, in Rect tempOtherTaskInsetBounds);
    @UnsupportedAppUsage
    void removeStack(int stackId);
    void makePackageIdle(String packageName, int userId);
    int getMemoryTrimLevel();
    boolean isVrModePackageEnabled(in ComponentName packageName);
    void notifyLockedProfile(int userId);
    void startConfirmDeviceCredentialIntent(in Intent intent, in Bundle options);
    @UnsupportedAppUsage
    void sendIdleJobTrigger();
    int sendIntentSender(in IIntentSender target, in IBinder whitelistToken, int code,
            in Intent intent, in String resolvedType, in IIntentReceiver finishedReceiver,
            in String requiredPermission, in Bundle options);
    boolean isBackgroundRestricted(in String packageName);

    // Start of N MR1 transactions
    void setRenderThread(int tid);
    /**
     * Lets activity manager know whether the calling process is currently showing "top-level" UI
     * that is not an activity, i.e. windows on the screen the user is currently interacting with.
     *
     * <p>This flag can only be set for persistent processes.
     *
     * @param hasTopUi Whether the calling process has "top-level" UI.
     */
    void setHasTopUi(boolean hasTopUi);

    // Start of O transactions
    int restartUserInBackground(int userId);
    /** Cancels the window transitions for the given task. */
    @UnsupportedAppUsage
    void cancelTaskWindowTransition(int taskId);
    /**
     * @param taskId the id of the task to retrieve the sAutoapshots for
     * @param reducedResolution if set, if the snapshot needs to be loaded from disk, this will load
     *                          a reduced resolution of it, which is much faster
     * @return a graphic buffer representing a screenshot of a task
     */
    @UnsupportedAppUsage
    ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution);
    void scheduleApplicationInfoChanged(in List<String> packageNames, int userId);
    void setPersistentVrThread(int tid);

    void waitForNetworkStateUpdate(long procStateSeq);
    /**
     * Add a bare uid to the background restrictions whitelist.  Only the system uid may call this.
     */
    void backgroundWhitelistUid(int uid);

    // Start of P transactions
    /**
     *  Similar to {@link #startUserInBackground(int userId), but with a listener to report
     *  user unlock progress.
     */
    boolean startUserInBackgroundWithListener(int userid, IProgressListener unlockProgressListener);

    /**
     * Method for the shell UID to start deletating its permission identity to an
     * active instrumenation. The shell can delegate permissions only to one active
     * instrumentation at a time. An active instrumentation is one running and
     * started from the shell.
     */
    void startDelegateShellPermissionIdentity(int uid, in String[] permissions);

    /**
     * Method for the shell UID to stop deletating its permission identity to an
     * active instrumenation. An active instrumentation is one running and
     * started from the shell.
     */
    void stopDelegateShellPermissionIdentity();

    /** Returns a file descriptor that'll be closed when the system server process dies. */
    ParcelFileDescriptor getLifeMonitor();

    /**
     * Start user, if it us not already running, and bring it to foreground.
     * unlockProgressListener can be null if monitoring progress is not necessary.
     */
    boolean startUserInForegroundWithListener(int userid, IProgressListener unlockProgressListener);

    /**
     *  Should disable touch if three fingers to screen shot is active?
     */
    boolean isSwipeToScreenshotGestureActive();
}
