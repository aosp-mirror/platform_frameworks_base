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
import android.app.ActivityTaskManager;
import android.app.ApplicationStartInfo;
import android.app.ApplicationErrorReport;
import android.app.ApplicationExitInfo;
import android.app.ContentProviderHolder;
import android.app.GrantedUriPermission;
import android.app.IApplicationStartInfoCompleteListener;
import android.app.IApplicationThread;
import android.app.IActivityController;
import android.app.IAppTask;
import android.app.IForegroundServiceObserver;
import android.app.IInstrumentationWatcher;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidFrozenStateChangedCallback;
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
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.LocusId;
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
    // the ones in frameworks/native/libs/binder/include_activitymanager/binder/ActivityManager.h
    // =============== Beginning of transactions used on native side as well ======================
    ParcelFileDescriptor openContentUri(in String uriString);
    void registerUidObserver(in IUidObserver observer, int which, int cutpoint,
            String callingPackage);
    void unregisterUidObserver(in IUidObserver observer);

    /**
     * Registers a UidObserver with a uid filter.
     *
     * @param observer The UidObserver implementation to register.
     * @param which    A bitmask of events to observe. See ActivityManager.UID_OBSERVER_*.
     * @param cutpoint The cutpoint for onUidStateChanged events. When the state crosses this
     *                 threshold in either direction, onUidStateChanged will be called.
     * @param callingPackage The name of the calling package.
     * @param uids     A list of uids to watch. If all uids are to be watched, use
     *                 registerUidObserver instead.
     * @throws RemoteException
     * @return Returns A binder token identifying the UidObserver registration.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)")
    IBinder registerUidObserverForUids(in IUidObserver observer, int which, int cutpoint,
            String callingPackage, in int[] uids);

    /**
     * Adds a uid to the list of uids that a UidObserver will receive updates about.
     *
     * @param observerToken  The binder token identifying the UidObserver registration.
     * @param callingPackage The name of the calling package.
     * @param uid            The uid to watch.
     * @throws RemoteException
     */
    void addUidToObserver(in IBinder observerToken, String callingPackage, int uid);

    /**
     * Removes a uid from the list of uids that a UidObserver will receive updates about.
     *
     * @param observerToken  The binder token identifying the UidObserver registration.
     * @param callingPackage The name of the calling package.
     * @param uid            The uid to stop watching.
     * @throws RemoteException
     */
    void removeUidFromObserver(in IBinder observerToken, String callingPackage, int uid);

    boolean isUidActive(int uid, String callingPackage);
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(allOf = {android.Manifest.permission.PACKAGE_USAGE_STATS, android.Manifest.permission.INTERACT_ACROSS_USERS_FULL}, conditional = true)")
    int getUidProcessState(int uid, in String callingPackage);
    @UnsupportedAppUsage
    int checkPermission(in String permission, int pid, int uid);

    /** Logs start of an API call to associate with an FGS, used for FGS Type Metrics */
    oneway void logFgsApiBegin(int apiType, int appUid, int appPid);

    /** Logs stop of an API call to associate with an FGS, used for FGS Type Metrics */
    oneway void logFgsApiEnd(int apiType, int appUid, int appPid);

    /** Logs API state change to associate with an FGS, used for FGS Type Metrics */
    oneway void logFgsApiStateChanged(int apiType, int state, int appUid, int appPid);
    // =============== End of transactions used on native side as well ============================

    // Special low-level communication with activity manager.
    void handleApplicationCrash(in IBinder app,
            in ApplicationErrorReport.ParcelableCrashInfo crashInfo);
    /** @deprecated Use {@link #startActivityWithFeature} instead */
    @UnsupportedAppUsage(maxTargetSdk=29, publicAlternatives="Use {@link android.content.Context#startActivity(android.content.Intent)} instead")
    int startActivity(in IApplicationThread caller, in String callingPackage, in Intent intent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flags, in ProfilerInfo profilerInfo, in Bundle options);
    int startActivityWithFeature(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int flags,
            in ProfilerInfo profilerInfo, in Bundle options);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void unhandledBack();
    @UnsupportedAppUsage
    boolean finishActivity(in IBinder token, int code, in Intent data, int finishTask);
    @UnsupportedAppUsage(maxTargetSdk=29, publicAlternatives="Use {@link android.content.Context#registerReceiver(android.content.BroadcastReceiver, android.content.IntentFilter)} instead")
    Intent registerReceiver(in IApplicationThread caller, in String callerPackage,
            in IIntentReceiver receiver, in IntentFilter filter,
            in String requiredPermission, int userId, int flags);
    Intent registerReceiverWithFeature(in IApplicationThread caller, in String callerPackage,
            in String callingFeatureId, in String receiverId, in IIntentReceiver receiver,
            in IntentFilter filter, in String requiredPermission, int userId, int flags);
    @UnsupportedAppUsage
    void unregisterReceiver(in IIntentReceiver receiver);
    /** @deprecated Use {@link #broadcastIntentWithFeature} instead */
    @UnsupportedAppUsage(maxTargetSdk=29, publicAlternatives="Use {@link android.content.Context#sendBroadcast(android.content.Intent)} instead")
    int broadcastIntent(in IApplicationThread caller, in Intent intent,
            in String resolvedType, in IIntentReceiver resultTo, int resultCode,
            in String resultData, in Bundle map, in String[] requiredPermissions,
            int appOp, in Bundle options, boolean serialized, boolean sticky, int userId);
    int broadcastIntentWithFeature(in IApplicationThread caller, in String callingFeatureId,
            in Intent intent, in String resolvedType, in IIntentReceiver resultTo, int resultCode,
            in String resultData, in Bundle map, in String[] requiredPermissions, in String[] excludePermissions,
            in String[] excludePackages, int appOp, in Bundle options, boolean serialized, boolean sticky, int userId);
    void unbroadcastIntent(in IApplicationThread caller, in Intent intent, int userId);
    @UnsupportedAppUsage
    oneway void finishReceiver(in IBinder who, int resultCode, in String resultData, in Bundle map,
            boolean abortBroadcast, int flags);
    void attachApplication(in IApplicationThread app, long startSeq);
    void finishAttachApplication(long startSeq, long timestampApplicationOnCreateNs);
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);
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
            in String resolvedType, boolean requireForeground, in String callingPackage,
            in String callingFeatureId, int userId);
    @UnsupportedAppUsage
    int stopService(in IApplicationThread caller, in Intent service,
            in String resolvedType, int userId);
    // Currently keeping old bindService because it is on the greylist
    @UnsupportedAppUsage
    int bindService(in IApplicationThread caller, in IBinder token, in Intent service,
            in String resolvedType, in IServiceConnection connection, long flags,
            in String callingPackage, int userId);
    int bindServiceInstance(in IApplicationThread caller, in IBinder token, in Intent service,
            in String resolvedType, in IServiceConnection connection, long flags,
            in String instanceName, in String callingPackage, int userId);
    void updateServiceGroup(in IServiceConnection connection, int group, int importance);
    @UnsupportedAppUsage
    boolean unbindService(in IServiceConnection connection);
    void publishService(in IBinder token, in Intent intent, in IBinder service);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setDebugApp(in String packageName, boolean waitForDebugger, boolean persistent);
    void setAgentApp(in String packageName, @nullable String agent);
    @UnsupportedAppUsage
    void setAlwaysFinish(boolean enabled);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
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
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean updateConfiguration(in Configuration values);
    /**
     * Updates mcc mnc configuration and applies changes to the entire system.
     *
     * @param mcc mcc configuration to update.
     * @param mnc mnc configuration to update.
     * @throws RemoteException; IllegalArgumentException if mcc or mnc is null.
     * @return Returns {@code true} if the configuration was updated;
     *         {@code false} otherwise.
     */
    boolean updateMccMncConfiguration(in String mcc, in String mnc);
    boolean stopServiceToken(in ComponentName className, in IBinder token, int startId);
    @UnsupportedAppUsage
    void setProcessLimit(int max);
    @UnsupportedAppUsage
    int getProcessLimit();
    int checkUriPermission(in Uri uri, int pid, int uid, int mode, int userId,
            in IBinder callerToken);
    int checkContentUriPermissionFull(in Uri uri, int pid, int uid, int mode, int userId);
    int[] checkUriPermissions(in List<Uri> uris, int pid, int uid, int mode, int userId,
                in IBinder callerToken);
    void grantUriPermission(in IApplicationThread caller, in String targetPkg, in Uri uri,
            int mode, int userId);
    void revokeUriPermission(in IApplicationThread caller, in String targetPkg, in Uri uri,
            int mode, int userId);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setActivityController(in IActivityController watcher, boolean imAMonkey);
    void showWaitingForDebugger(in IApplicationThread who, boolean waiting);
    /*
     * This will deliver the specified signal to all the persistent processes. Currently only
     * SIGUSR1 is delivered. All others are ignored.
     */
    void signalPersistentProcesses(int signal);

    @UnsupportedAppUsage
    ParceledListSlice getRecentTasks(int maxNum, int flags, int userId);
    @UnsupportedAppUsage
    oneway void serviceDoneExecuting(in IBinder token, int type, int startId, int res,
            in Intent intent);
    /** @deprecated  Use {@link #getIntentSenderWithFeature} instead */
    @UnsupportedAppUsage(maxTargetSdk=29, publicAlternatives="Use {@link PendingIntent#getIntentSender()} instead")
    IIntentSender getIntentSender(int type, in String packageName, in IBinder token,
            in String resultWho, int requestCode, in Intent[] intents, in String[] resolvedTypes,
            int flags, in Bundle options, int userId);
    IIntentSender getIntentSenderWithFeature(int type, in String packageName, in String featureId,
            in IBinder token, in String resultWho, int requestCode, in Intent[] intents,
            in String[] resolvedTypes, int flags, in Bundle options, int userId);
    void cancelIntentSender(in IIntentSender sender);
    ActivityManager.PendingIntentInfo getInfoForIntentSender(in IIntentSender sender);
    /**
      This method used to be called registerIntentSenderCancelListener(), was void, and
      would call `receiver` if the PI has already been canceled.
      Now it returns false if the PI is cancelled, without calling `receiver`.
      The method was renamed to catch calls to the original method.
     */
    boolean registerIntentSenderCancelListenerEx(in IIntentSender sender,
        in IResultReceiver receiver);
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
    void stopAppForUser(in String packageName, int userId);
    /** Returns {@code false} if the callback could not be registered, {@true} otherwise. */
    boolean registerForegroundServiceObserver(in IForegroundServiceObserver callback);
    @UnsupportedAppUsage
    void forceStopPackage(in String packageName, int userId);
    void forceStopPackageEvenWhenStopping(in String packageName, int userId);
    boolean killPids(in int[] pids, in String reason, boolean secure);
    @UnsupportedAppUsage
    List<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags);
    // Retrieve running application processes in the system
    @UnsupportedAppUsage
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses();
    IBinder peekService(in Intent service, in String resolvedType, in String callingPackage);
    // Turn on/off profiling in a particular process.
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean profileControl(in String process, int userId, boolean start,
            in ProfilerInfo profilerInfo, int profileType);
    @UnsupportedAppUsage
    boolean shutdown(int timeout);
    @UnsupportedAppUsage
    void stopAppSwitches();
    @UnsupportedAppUsage
    void resumeAppSwitches();
    boolean bindBackupAgent(in String packageName, int backupRestoreMode, int targetUserId,
            int backupDestination);
    void backupAgentCreated(in String packageName, in IBinder agent, int userId);
    void unbindBackupAgent(in ApplicationInfo appInfo);
    int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            boolean requireFull, in String name, in String callerPackage);
    void addPackageDependency(in String packageName);
    void killApplication(in String pkg, int appId, int userId, in String reason,
            int exitInfoReason);
    @UnsupportedAppUsage
    void closeSystemDialogs(in String reason);
    @UnsupportedAppUsage
    Debug.MemoryInfo[] getProcessMemoryInfo(in int[] pids);
    void killApplicationProcess(in String processName, int uid);
    // Special low-level communication with activity manager.
    boolean handleApplicationWtf(in IBinder app, in String tag, boolean system,
            in ApplicationErrorReport.ParcelableCrashInfo crashInfo, int immediateCallerPid);
    @UnsupportedAppUsage
    void killBackgroundProcesses(in String packageName, int userId);
    boolean isUserAMonkey();
    // Retrieve info of applications installed on external media that are currently
    // running.
    List<ApplicationInfo> getRunningExternalApplications();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void finishHeavyWeightApp();
    // A StrictMode violation to be handled.
    @UnsupportedAppUsage
    void handleApplicationStrictModeViolation(in IBinder app, int penaltyMask,
            in StrictMode.ViolationInfo crashInfo);
    void registerStrictModeCallback(in IBinder binder);
    boolean isTopActivityImmersive();
    void crashApplicationWithType(int uid, int initialPid, in String packageName, int userId,
            in String message, boolean force, int exceptionTypeId);
    void crashApplicationWithTypeWithExtras(int uid, int initialPid, in String packageName,
            int userId, in String message, boolean force, int exceptionTypeId, in Bundle extras);
    oneway void getMimeTypeFilterAsync(in Uri uri, int userId, in RemoteCallback resultCallback);
    // Cause the specified process to dump the specified heap.
    boolean dumpHeap(in String process, int userId, boolean managed, boolean mallocInfo,
            boolean runGc, in String dumpBitmaps, in String path, in ParcelFileDescriptor fd,
            in RemoteCallback finishCallback);
    @UnsupportedAppUsage
    boolean isUserRunning(int userid, int flags);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setPackageScreenCompatMode(in String packageName, int mode);
    @UnsupportedAppUsage
    boolean switchUser(int userid);
    String getSwitchingFromUserMessage();
    String getSwitchingToUserMessage();
    @UnsupportedAppUsage
    void setStopUserOnSwitch(int value);
    boolean removeTask(int taskId);
    @UnsupportedAppUsage
    void registerProcessObserver(in IProcessObserver observer);
    @UnsupportedAppUsage
    void unregisterProcessObserver(in IProcessObserver observer);
    boolean isIntentSenderTargetedToPackage(in IIntentSender sender);
    @UnsupportedAppUsage
    void updatePersistentConfiguration(in Configuration values);
    void updatePersistentConfigurationWithAttribution(in Configuration values,
            String callingPackageName, String callingAttributionTag);
    @UnsupportedAppUsage
    long[] getProcessPss(in int[] pids);
    void showBootMessage(in CharSequence msg, boolean always);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void killAllBackgroundProcesses();
    ContentProviderHolder getContentProviderExternal(in String name, int userId,
            in IBinder token, String tag);
    /** @deprecated - Use {@link #removeContentProviderExternalAsUser} which takes a user ID. */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void removeContentProviderExternal(in String name, in IBinder token);
    void removeContentProviderExternalAsUser(in String name, in IBinder token, int userId);
    // Get memory information about the calling process.
    void getMyMemoryState(out ActivityManager.RunningAppProcessInfo outInfo);
    boolean killProcessesBelowForeground(in String reason);
    @UnsupportedAppUsage
    UserInfo getCurrentUser();
    int getCurrentUserId();
    // This is not public because you need to be very careful in how you
    // manage your activity to make sure it is always the uid you expect.
    @UnsupportedAppUsage
    int getLaunchedFromUid(in IBinder activityToken);
    @UnsupportedAppUsage
    void unstableProviderDied(in IBinder connection);
    @UnsupportedAppUsage
    boolean isIntentSenderAnActivity(in IIntentSender sender);
    /** @deprecated Use {@link startActivityAsUserWithFeature} instead */
    @UnsupportedAppUsage(maxTargetSdk=29, publicAlternatives="Use {@code android.content.Context#createContextAsUser(android.os.UserHandle, int)} and {@link android.content.Context#startActivity(android.content.Intent)} instead")
    int startActivityAsUser(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo,
            in Bundle options, int userId);
    int startActivityAsUserWithFeature(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    int stopUser(int userid, boolean stopProfileRegardlessOfParent, in IStopUserCallback callback);
    int stopUserWithCallback(int userid, in IStopUserCallback callback);
    int stopUserExceptCertainProfiles(int userid, boolean stopProfileRegardlessOfParent, in IStopUserCallback callback);
    /**
     * Check {@link com.android.server.am.ActivityManagerService#stopUserWithDelayedLocking(int, IStopUserCallback)}
     * for details.
     */
    int stopUserWithDelayedLocking(int userid, in IStopUserCallback callback);

    @UnsupportedAppUsage
    void registerUserSwitchObserver(in IUserSwitchObserver observer, in String name);
    void unregisterUserSwitchObserver(in IUserSwitchObserver observer);
    int[] getRunningUserIds();

    // Request a heap dump for the system server.
    void requestSystemServerHeapDump();

    void requestBugReport(int bugreportType);
    void requestBugReportWithDescription(in @nullable String shareTitle,
                in @nullable String shareDescription, int bugreportType);

    /**
     *  Takes a telephony bug report and notifies the user with the title and description
     *  that are passed to this API as parameters
     *
     *  @param shareTitle should be a valid legible string less than 50 chars long
     *  @param shareDescription should be less than 150 chars long
     *
     *  @throws IllegalArgumentException if shareTitle or shareDescription is too big or if the
     *          paremeters cannot be encoding to an UTF-8 charset.
     */
    void requestTelephonyBugReport(in String shareTitle, in String shareDescription);

    /**
     *  This method is only used by Wifi.
     *
     *  Takes a minimal bugreport of Wifi-related state.
     *
     *  @param shareTitle should be a valid legible string less than 50 chars long
     *  @param shareDescription should be less than 150 chars long
     *
     *  @throws IllegalArgumentException if shareTitle or shareDescription is too big or if the
     *          parameters cannot be encoding to an UTF-8 charset.
     */
    void requestWifiBugReport(in String shareTitle, in String shareDescription);
    void requestInteractiveBugReportWithDescription(in String shareTitle,
            in String shareDescription);

    void requestInteractiveBugReport();
    void requestBugReportWithExtraAttachments(in List<Uri> extraAttachment);
    void requestFullBugReport();
    void requestRemoteBugReport(long nonce);
    boolean launchBugReportHandlerApp();
    List<String> getBugreportWhitelistedPackages();

    @UnsupportedAppUsage
    Intent getIntentForIntentSender(in IIntentSender sender);
    // This is not public because you need to be very careful in how you
    // manage your activity to make sure it is always the uid you expect.
    @UnsupportedAppUsage
    String getLaunchedFromPackage(in IBinder activityToken);
    void killUid(int appId, int userId, in String reason);
    void setUserIsMonkey(boolean monkey);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void hang(in IBinder who, boolean allowRestart);

    List<ActivityTaskManager.RootTaskInfo> getAllRootTaskInfos();
    void moveTaskToRootTask(int taskId, int rootTaskId, boolean toTop);
    void setFocusedRootTask(int taskId);
    ActivityTaskManager.RootTaskInfo getFocusedRootTaskInfo();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void restart();
    void performIdleMaintenance();
    void appNotRespondingViaProvider(in IBinder connection);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    Rect getTaskBounds(int taskId);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean setProcessMemoryTrimLevel(in String process, int userId, int level);


    // Start of L transactions
    String getTagForIntentSender(in IIntentSender sender, in String prefix);

    /**
      * Starts a user in the background (i.e., while another user is running in the foreground).
      *
      * Notice that a background user is "invisible" and cannot launch activities. Starting on
      * Android U, all users started with this method are invisible, even profiles (prior to Android
      * U, profiles started with this method would be visible if its parent was the current user) -
      * if you want to start a profile visible, you should call {@code startProfile()} instead.
      */
    @UnsupportedAppUsage
    boolean startUserInBackground(int userid);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isInLockTaskMode();
    @UnsupportedAppUsage
    int startActivityFromRecents(int taskId, in Bundle options);
    @UnsupportedAppUsage
    void startSystemLockTaskMode(int taskId);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isTopOfTask(in IBinder token);
    void bootAnimationComplete();

    /**
     * Used by {@link com.android.systemui.theme.ThemeOverlayController} to notify when color
     * palette is ready.
     *
     * @param userId The ID of the user where ThemeOverlayController is ready.
     *
     * @throws RemoteException
     */
    void setThemeOverlayReady(int userId);

    @UnsupportedAppUsage
    void registerTaskStackListener(in ITaskStackListener listener);
    void unregisterTaskStackListener(in ITaskStackListener listener);
    void notifyCleartextNetwork(int uid, in byte[] firstPacket);
    @UnsupportedAppUsage
    void setTaskResizeable(int taskId, int resizeableMode);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void resizeTask(int taskId, in Rect bounds, int resizeMode);
    @UnsupportedAppUsage
    int getLockTaskModeState();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setDumpHeapDebugLimit(in String processName, int uid, long maxMemSize,
            in String reportPackage);
    void dumpHeapFinished(in String path);
    void updateLockTaskPackages(int userId, in String[] packages);
    void noteAlarmStart(in IIntentSender sender, in WorkSource workSource, int sourceUid, in String tag);
    void noteAlarmFinish(in IIntentSender sender, in WorkSource workSource, int sourceUid, in String tag);
    @UnsupportedAppUsage
    int getPackageProcessState(in String packageName, in String callingPackage);

    // Start of N transactions
    // Start Binder transaction tracking for all applications.
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean startBinderTracking();
    // Stop Binder transaction tracking for all applications and dump trace data to the given file
    // descriptor.
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean stopBinderTrackingAndDump(in ParcelFileDescriptor fd);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void suppressResizeConfigChanges(boolean suppress);

    /**
     * @deprecated Use {@link #unlockUser2(int, IProgressListener)} instead, since the token and
     * secret arguments no longer do anything.  This method still exists only because it is marked
     * with {@code @UnsupportedAppUsage}, so it might not be safe to remove it or change its
     * signature.
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean unlockUser(int userid, in byte[] token, in byte[] secret,
            in IProgressListener listener);

    /**
     * Tries to unlock the given user.
     * <p>
     * This will succeed only if the user's CE storage key is already unlocked or if the user
     * doesn't have a lockscreen credential set.
     *
     * @param userId The ID of the user to unlock.
     * @param listener An optional progress listener.
     *
     * @return true if the user was successfully unlocked, otherwise false.
     */
    boolean unlockUser2(int userId, in IProgressListener listener);

    void killPackageDependents(in String packageName, int userId);
    void makePackageIdle(String packageName, int userId);
    void setDeterministicUidIdle(boolean deterministic);
    int getMemoryTrimLevel();
    boolean isVrModePackageEnabled(in ComponentName packageName);
    void notifyLockedProfile(int userId);
    void startConfirmDeviceCredentialIntent(in Intent intent, in Bundle options);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void sendIdleJobTrigger();
    int sendIntentSender(in IApplicationThread caller, in IIntentSender target,
            in IBinder whitelistToken, int code,
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
    /** Cancels the window transitions for the given task. */
    @UnsupportedAppUsage
    void cancelTaskWindowTransition(int taskId);
    void scheduleApplicationInfoChanged(in List<String> packageNames, int userId);
    void setPersistentVrThread(int tid);

    void waitForNetworkStateUpdate(long procStateSeq);
    /**
     * Add a bare uid to the background restrictions whitelist.  Only the system uid may call this.
     */
    void backgroundAllowlistUid(int uid);

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

    /**
     * Method for the shell UID to get currently adopted permissions for an active instrumentation.
     * An active instrumentation is one running and started from the shell.
     */
    List<String> getDelegatedShellPermissions();

    /** Returns a file descriptor that'll be closed when the system server process dies. */
    ParcelFileDescriptor getLifeMonitor();

    /**
     * Start user, if it us not already running, and bring it to foreground.
     * unlockProgressListener can be null if monitoring progress is not necessary.
     */
    boolean startUserInForegroundWithListener(int userid, IProgressListener unlockProgressListener);

    /**
     * Method for the app to tell system that it's wedged and would like to trigger an ANR.
     */
    void appNotResponding(String reason);

    /**
     * Return a list of {@link ApplicationStartInfo} records.
     *
     * <p class="note"> Note: System stores historical information in a ring buffer, older
     * records would be overwritten by newer records. </p>
     *
     * @param packageName Optional, an empty value means match all packages belonging to the
     *                    caller's UID. If this package belongs to another UID, you must hold
     *                    {@link android.Manifest.permission#DUMP} in order to retrieve it.
     * @param maxNum      Optional, the maximum number of results should be returned; A value of 0
     *                    means to ignore this parameter and return all matching records
     * @param userId      The userId in the multi-user environment.
     *
     * @return a list of {@link ApplicationStartInfo} records with the matching criteria, sorted in
     *         the order from most recent to least recent.
     */
    ParceledListSlice<ApplicationStartInfo> getHistoricalProcessStartReasons(String packageName,
            int maxNum, int userId);


    /**
     * Sets a callback for {@link ApplicationStartInfo} upon completion of collecting startup data.
     *
     * <p class="note"> Note: completion of startup is no guaranteed and as such this callback may not occur.</p>
     *
     * @param listener    A listener to for the callback upon completion of startup data collection.
     * @param userId      The userId in the multi-user environment.
     */
    void addApplicationStartInfoCompleteListener(IApplicationStartInfoCompleteListener listener,
            int userId);


    /**
     * Removes callback for {@link ApplicationStartInfo} upon completion of collecting startup data.
     *
     * @param userId      The userId in the multi-user environment.
     */
    void removeApplicationStartInfoCompleteListener(IApplicationStartInfoCompleteListener listener,
            int userId);


    /**
     * Adds a timestamp of the moment called to the calling apps most recent
     * {@link ApplicationStartInfo}.
     *
     *
     * @param key         Unique key for timestamp.
     * @param timestampNs Clock monotonic time in nanoseconds of event to be
     *                    recorded.
     * @param userId      The userId in the multi-user environment.
     */
    void addStartInfoTimestamp(int key, long timestampNs, int userId);

    /**
    * Reports view related timestamps to be added to the calling apps most
    * recent {@link ApplicationStartInfo}.
    *
    * @param renderThreadDrawStartTimeNs Clock monotonic time in nanoseconds of RenderThread draw start
    * @param framePresentedTimeNs        Clock monotonic time in nanoseconds of frame presented
    */
    oneway void reportStartInfoViewTimestamps(long renderThreadDrawStartTimeNs, long framePresentedTimeNs);

    /**
     * Return a list of {@link ApplicationExitInfo} records.
     *
     * <p class="note"> Note: System stores these historical information in a ring buffer, older
     * records would be overwritten by newer records. </p>
     *
     * <p class="note"> Note: In the case that this application bound to an external service with
     * flag {@link android.content.Context#BIND_EXTERNAL_SERVICE}, the process of that external
     * service will be included in this package's exit info. </p>
     *
     * @param packageName Optional, an empty value means match all packages belonging to the
     *                    caller's UID. If this package belongs to another UID, you must hold
     *                    {@link android.Manifest.permission#DUMP} in order to retrieve it.
     * @param pid         Optional, it could be a process ID that used to belong to this package but
     *                    died later; A value of 0 means to ignore this parameter and return all
     *                    matching records.
     * @param maxNum      Optional, the maximum number of results should be returned; A value of 0
     *                    means to ignore this parameter and return all matching records
     * @param userId      The userId in the multi-user environment.
     *
     * @return a list of {@link ApplicationExitInfo} records with the matching criteria, sorted in
     *         the order from most recent to least recent.
     */
    ParceledListSlice<ApplicationExitInfo> getHistoricalProcessExitReasons(String packageName,
            int pid, int maxNum, int userId);

    /*
     * Kill the given PIDs, but the killing will be delayed until the device is idle
     * and the given process is imperceptible.
     */
    void killProcessesWhenImperceptible(in int[] pids, String reason);

    /**
     * Set locus context for a given activity.
     * @param activity
     * @param locusId a unique, stable id that identifies this activity instance from others.
     * @param appToken ActivityRecord's appToken.
     */
    void setActivityLocusContext(in ComponentName activity, in LocusId locusId,
            in IBinder appToken);

    /**
     * Set custom state data for this process. It will be included in the record of
     * {@link ApplicationExitInfo} on the death of the current calling process; the new process
     * of the app can retrieve this state data by calling
     * {@link ApplicationExitInfo#getProcessStateSummary} on the record returned by
     * {@link #getHistoricalProcessExitReasons}.
     *
     * <p> This would be useful for the calling app to save its stateful data: if it's
     * killed later for any reason, the new process of the app can know what the
     * previous process of the app was doing. For instance, you could use this to encode
     * the current level in a game, or a set of features/experiments that were enabled. Later you
     * could analyze under what circumstances the app tends to crash or use too much memory.
     * However, it's not suggested to rely on this to restore the applications previous UI state
     * or so, it's only meant for analyzing application healthy status.</p>
     *
     * <p> System might decide to throttle the calls to this API; so call this API in a reasonable
     * manner, excessive calls to this API could result a {@link java.lang.RuntimeException}.
     * </p>
     *
     * @param state The customized state data
     */
    void setProcessStateSummary(in byte[] state);

    /**
     * Return whether the app freezer is supported (true) or not (false) by this system.
     */
    boolean isAppFreezerSupported();

    /**
     * Return whether the app freezer is enabled (true) or not (false) by this system.
     */
    boolean isAppFreezerEnabled();

    /**
     * Kills uid with the reason of permission change.
     */
    void killUidForPermissionChange(int appId, int userId, String reason);

    /**
     * Resets the state of the {@link com.android.server.am.AppErrors} instance.
     * This is intended for testing within the CTS only and is protected by
     * android.permission.RESET_APP_ERRORS.
     */
    void resetAppErrors();

    /**
     * Control the app freezer state. Returns true in case of success, false if the operation
     * didn't succeed (for example, when the app freezer isn't supported).
     * Handling the freezer state via this method is reentrant, that is it can be
     * disabled and re-enabled multiple times in parallel. As long as there's a 1:1 disable to
     * enable match, the freezer is re-enabled at last enable only.
     * @param enable set it to true to enable the app freezer, false to disable it.
     */
    boolean enableAppFreezer(in boolean enable);

    /**
     * Suppress or reenable the rate limit on foreground service notification deferral.
     * This is for use within CTS and is protected by android.permission.WRITE_DEVICE_CONFIG.
     *
     * @param enable false to suppress rate-limit policy; true to reenable it.
     */
    boolean enableFgsNotificationRateLimit(in boolean enable);

    /**
     * Holds the AM lock for the specified amount of milliseconds.
     * This is intended for use by the tests that need to imitate lock contention.
     * The token should be obtained by
     * {@link android.content.pm.PackageManager#getHoldLockToken()}.
     */
    void holdLock(in IBinder token, in int durationMs);

    /**
     * Starts a profile.
     * @param userId the user id of the profile.
     * @return true if the profile has been successfully started or if the profile is already
     * running, false if profile failed to start.
     * @throws IllegalArgumentException if the user is not a profile.
     */
    boolean startProfile(int userId);

    /**
     * Stops a profile.
     * @param userId the user id of the profile.
     * @return true if the profile has been successfully stopped or is already stopped. Otherwise
     * the exceptions listed below are thrown.
     * @throws IllegalArgumentException if the user is not a profile.
     */
    boolean stopProfile(int userId);

    /** Called by PendingIntent.queryIntentComponents() */
    ParceledListSlice queryIntentComponentsForIntentSender(in IIntentSender sender, int matchFlags);

    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(allOf = {android.Manifest.permission.PACKAGE_USAGE_STATS, android.Manifest.permission.INTERACT_ACROSS_USERS_FULL}, conditional = true)")
    int getUidProcessCapabilities(int uid, in String callingPackage);

    /** Blocks until all broadcast queues become idle. */
    void waitForBroadcastIdle();
    void waitForBroadcastBarrier();

    /** Delays delivering broadcasts to the specified package. */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.DUMP)")
    void forceDelayBroadcastDelivery(in String targetPackage, long delayedDurationMs);

    /** Checks if the process represented by the given pid is frozen. */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.DUMP)")
    boolean isProcessFrozen(int pid);

    /**
     * @return The reason code of whether or not the given UID should be exempted from background
     * restrictions here.
     *
     * <p>
     * Note: Call it with caution as it'll try to acquire locks in other services.
     * </p>
     */
    int getBackgroundRestrictionExemptionReason(int uid);

    // Start (?) of T transactions
    /**
     * Similar to {@link #startUserInBackgroundWithListener(int userId, IProgressListener unlockProgressListener)},
     * but setting the user as the visible user of that display (i.e., allowing the user and its
     * running profiles to launch activities on that display).
     *
     * <p>Typically used only by automotive builds when the vehicle has multiple displays.
     */
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS, android.Manifest.permission.CREATE_USERS}, conditional = true)")
    boolean startUserInBackgroundVisibleOnDisplay(int userid, int displayId, IProgressListener unlockProgressListener);

    /**
     * Similar to {@link #startProfile(int userId)}, but with a listener to report user unlock
     * progress.
     */
    boolean startProfileWithListener(int userid, IProgressListener unlockProgressListener);

    int restartUserInBackground(int userId, int userStartMode);

    /**
     * Gets the ids of displays that can be used on {@link #startUserInBackgroundVisibleOnDisplay(int userId, int displayId)}.
     *
     * <p>Typically used only by automotive builds when the vehicle has multiple displays.
     */
    @nullable int[] getDisplayIdsForStartingVisibleBackgroundUsers();

    /** Returns if the service is a short-service is still "alive" and past the timeout. */
    boolean shouldServiceTimeOut(in ComponentName className, in IBinder token);
    /** Returns if the service has a time-limit restricted type and is past the time limit. */
    boolean hasServiceTimeLimitExceeded(in ComponentName className, in IBinder token);

    void registerUidFrozenStateChangedCallback(in IUidFrozenStateChangedCallback callback);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)")
    void unregisterUidFrozenStateChangedCallback(in IUidFrozenStateChangedCallback callback);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)")
    int[] getUidFrozenState(in int[] uids);

    int checkPermissionForDevice(in String permission, int pid, int uid, int deviceId);

    /**
     * Notify AMS about binder transactions to frozen apps.
     *
     * @param debugPid The binder transaction sender
     * @param code The binder transaction code
     * @param flags The binder transaction flags
     * @param err The binder transaction error
     */
    oneway void frozenBinderTransactionDetected(int debugPid, int code, int flags, int err);
    int getBindingUidProcessState(int uid, in String callingPackage);

    /**
     * Return the timestampe (in the elapsed timebase) when the UID became idle from active
     * last time (regardless of if the UID is still idle, or became active again).
     * This is useful when trying to detect whether an UID has ever became idle since a certain
     * time in the past.
     */
    long getUidLastIdleElapsedTime(int uid, in String callingPackage);

    /**
     * Adds permission to be overridden to the given state. Must be called from root user.
     *
     * @param originatingUid The UID of the instrumented app that initialized the override
     * @param uid The UID of the app whose permission will be overridden
     * @param permission The permission whose state will be overridden
     * @param result The state to override the permission to
     *
     * @see PackageManager.PermissionResult
     */
    void addOverridePermissionState(int originatingUid, int uid, String permission, int result);

    /**
     * Removes overridden permission. Must be called from root user.
     *
     * @param originatingUid The UID of the instrumented app that initialized the override
     * @param uid The UID of the app whose permission is overridden
     * @param permission The permission whose state will no longer be overridden
     */
    void removeOverridePermissionState(int originatingUid, int uid, String permission);

    /**
     * Clears all overridden permissions for the given UID. Must be called from root user.
     *
     * @param originatingUid The UID of the instrumented app that initialized the override
     * @param uid The UID of the app whose permissions will no longer be overridden
     */
    void clearOverridePermissionStates(int originatingUid, int uid);

    /**
     * Clears all overridden permissions on the device. Must be called from root user.
     *
     * @param originatingUid The UID of the instrumented app that initialized the override
     */
    void clearAllOverridePermissionStates(int originatingUid);

    /**
     * Request the system to log the reason for restricting / unrestricting an app.
     * @see ActivityManager#noteAppRestrictionEnabled
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.DEVICE_POWER)")
    void noteAppRestrictionEnabled(in String packageName, int uid, int restrictionType,
            boolean enabled, int reason, in String subReason, int source, long threshold);
}
