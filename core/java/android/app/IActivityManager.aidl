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
import android.app.IApplicationThread;
import android.app.IActivityContainer;
import android.app.IActivityContainerCallback;
import android.app.IActivityController;
import android.app.IAppTask;
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
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.StrictMode;
import android.service.voice.IVoiceInteractionSession;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
 * System private API for talking with the activity manager service.  This
 * provides calls from the application back to the activity manager.
 *
 * {@hide}
 */
interface IActivityManager {
    // Please keep these transaction codes the same -- they are also
    // sent by C++ code. when a new method is added, use the next available transaction id.

    // Special low-level communication with activity manager.
    void handleApplicationCrash(in IBinder app,
            in ApplicationErrorReport.ParcelableCrashInfo crashInfo) = 1;
    int startActivity(in IApplicationThread caller, in String callingPackage, in Intent intent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flags, in ProfilerInfo profilerInfo, in Bundle options) = 2;
    void unhandledBack() = 3;
    ParcelFileDescriptor openContentUri(in String uriString) = 4;

    boolean finishActivity(in IBinder token, int code, in Intent data, int finishTask) = 10;
    Intent registerReceiver(in IApplicationThread caller, in String callerPackage,
            in IIntentReceiver receiver, in IntentFilter filter,
            in String requiredPermission, int userId) = 11;
    void unregisterReceiver(in IIntentReceiver receiver) = 12;
    int broadcastIntent(in IApplicationThread caller, in Intent intent,
            in String resolvedType, in IIntentReceiver resultTo, int resultCode,
            in String resultData, in Bundle map, in String[] requiredPermissions,
            int appOp, in Bundle options, boolean serialized, boolean sticky, int userId) = 13;
    void unbroadcastIntent(in IApplicationThread caller, in Intent intent, int userId) = 14;
    oneway void finishReceiver(in IBinder who, int resultCode, in String resultData, in Bundle map,
            boolean abortBroadcast, int flags) = 15;
    void attachApplication(in IApplicationThread app) = 16;
    oneway void activityIdle(in IBinder token, in Configuration config,
            in boolean stopProfiling) = 17;
    void activityPaused(in IBinder token) = 18;
    oneway void activityStopped(in IBinder token, in Bundle state,
            in PersistableBundle persistentState, in CharSequence description) = 19;
    String getCallingPackage(in IBinder token) = 20;
    ComponentName getCallingActivity(in IBinder token) = 21;
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, int flags) = 22;
    void moveTaskToFront(int task, int flags, in Bundle options) = 23;
    void moveTaskBackwards(int task) = 25;
    int getTaskForActivity(in IBinder token, in boolean onlyRoot) = 26;
    ContentProviderHolder getContentProvider(in IApplicationThread caller,
            in String name, int userId, boolean stable) = 28;
    void publishContentProviders(in IApplicationThread caller,
            in List<ContentProviderHolder> providers) = 29;
    boolean refContentProvider(in IBinder connection, int stableDelta, int unstableDelta) = 30;
    void finishSubActivity(in IBinder token, in String resultWho, int requestCode) = 31;
    PendingIntent getRunningServiceControlPanel(in ComponentName service) = 32;
    ComponentName startService(in IApplicationThread caller, in Intent service,
            in String resolvedType, in String callingPackage, int userId) = 33;
    int stopService(in IApplicationThread caller, in Intent service,
            in String resolvedType, int userId) = 34;
    int bindService(in IApplicationThread caller, in IBinder token, in Intent service,
            in String resolvedType, in IServiceConnection connection, int flags,
            in String callingPackage, int userId) = 35;
    boolean unbindService(in IServiceConnection connection) = 36;
    void publishService(in IBinder token, in Intent intent, in IBinder service) = 37;
    void activityResumed(in IBinder token) = 38;
    void setDebugApp(in String packageName, boolean waitForDebugger, boolean persistent) = 41;
    void setAlwaysFinish(boolean enabled) = 42;
    boolean startInstrumentation(in ComponentName className, in String profileFile,
            int flags, in Bundle arguments, in IInstrumentationWatcher watcher,
            in IUiAutomationConnection connection, int userId,
            in String abiOverride) = 43;
    void finishInstrumentation(in IApplicationThread target, int resultCode,
            in Bundle results) = 44;
    /**
     * @return A copy of global {@link Configuration}, contains general settings for the entire
     *         system. Corresponds to the configuration of the default display.
     * @throws RemoteException
     */
    Configuration getConfiguration() = 45;
    /**
     * Updates global configuration and applies changes to the entire system.
     * @param values Update values for global configuration. If null is passed it will request the
     *               Window Manager to compute new config for the default display.
     * @throws RemoteException
     * @return Returns true if the configuration was updated.
     */
    boolean updateConfiguration(in Configuration values) = 46;
    boolean stopServiceToken(in ComponentName className, in IBinder token, int startId) = 47;
    ComponentName getActivityClassForToken(in IBinder token) = 48;
    String getPackageForToken(in IBinder token) = 49;
    void setProcessLimit(int max) = 50;
    int getProcessLimit() = 51;
    int checkPermission(in String permission, int pid, int uid) = 52;
    int checkUriPermission(in Uri uri, int pid, int uid, int mode, int userId,
            in IBinder callerToken) = 53;
    void grantUriPermission(in IApplicationThread caller, in String targetPkg, in Uri uri,
            int mode, int userId) = 54;
    void revokeUriPermission(in IApplicationThread caller, in Uri uri, int mode, int userId) = 55;
    void setActivityController(in IActivityController watcher, boolean imAMonkey) = 56;
    void showWaitingForDebugger(in IApplicationThread who, boolean waiting) = 57;
    /*
     * This will deliver the specified signal to all the persistent processes. Currently only
     * SIGUSR1 is delivered. All others are ignored.
     */
    void signalPersistentProcesses(int signal) = 58;
    ParceledListSlice getRecentTasks(int maxNum,
            int flags, int userId) = 59;
    oneway void serviceDoneExecuting(in IBinder token, int type, int startId, int res) = 60;
    oneway void activityDestroyed(in IBinder token) = 61;
    IIntentSender getIntentSender(int type, in String packageName, in IBinder token,
            in String resultWho, int requestCode, in Intent[] intents, in String[] resolvedTypes,
            int flags, in Bundle options, int userId) = 62;
    void cancelIntentSender(in IIntentSender sender) = 63;
    String getPackageForIntentSender(in IIntentSender sender) = 64;
    void enterSafeMode() = 65;
    boolean startNextMatchingActivity(in IBinder callingActivity,
            in Intent intent, in Bundle options) = 66;
    void noteWakeupAlarm(in IIntentSender sender, int sourceUid,
            in String sourcePkg, in String tag) = 67;
    void removeContentProvider(in IBinder connection, boolean stable) = 68;
    void setRequestedOrientation(in IBinder token, int requestedOrientation) = 69;
    int getRequestedOrientation(in IBinder token) = 70;
    void unbindFinished(in IBinder token, in Intent service, boolean doRebind) = 71;
    void setProcessForeground(in IBinder token, int pid, boolean isForeground) = 72;
    void setServiceForeground(in ComponentName className, in IBinder token,
            int id, in Notification notification, int flags) = 73;
    boolean moveActivityTaskToBack(in IBinder token, boolean nonRoot) = 74;
    void getMemoryInfo(out ActivityManager.MemoryInfo outInfo) = 75;
    List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() = 76;
    boolean clearApplicationUserData(in String packageName,
            in IPackageDataObserver observer, int userId) = 77;
    void forceStopPackage(in String packageName, int userId) = 78;
    boolean killPids(in int[] pids, in String reason, boolean secure) = 79;
    List<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags) = 80;
    ActivityManager.TaskThumbnail getTaskThumbnail(int taskId) = 81;
    // Retrieve running application processes in the system
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() = 82;
    // Get device configuration
    ConfigurationInfo getDeviceConfigurationInfo() = 83;
    IBinder peekService(in Intent service, in String resolvedType, in String callingPackage) = 84;
    // Turn on/off profiling in a particular process.
    boolean profileControl(in String process, int userId, boolean start,
            in ProfilerInfo profilerInfo, int profileType) = 85;
    boolean shutdown(int timeout) = 86;
    void stopAppSwitches() = 87;
    void resumeAppSwitches() = 88;
    boolean bindBackupAgent(in String packageName, int backupRestoreMode, int userId) = 89;
    void backupAgentCreated(in String packageName, in IBinder agent) = 90;
    void unbindBackupAgent(in ApplicationInfo appInfo) = 91;
    int getUidForIntentSender(in IIntentSender sender) = 92;
    int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            boolean requireFull, in String name, in String callerPackage) = 93;
    void addPackageDependency(in String packageName) = 94;
    void killApplication(in String pkg, int appId, int userId, in String reason) = 95;
    void closeSystemDialogs(in String reason) = 96;
    Debug.MemoryInfo[] getProcessMemoryInfo(in int[] pids) = 97;
    void killApplicationProcess(in String processName, int uid) = 98;
    int startActivityIntentSender(in IApplicationThread caller,
            in IntentSender intent, in Intent fillInIntent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode,
            int flagsMask, int flagsValues, in Bundle options) = 99;
    void overridePendingTransition(in IBinder token, in String packageName,
            int enterAnim, int exitAnim) = 100;
    // Special low-level communication with activity manager.
    boolean handleApplicationWtf(in IBinder app, in String tag, boolean system,
            in ApplicationErrorReport.ParcelableCrashInfo crashInfo) = 101;
    void killBackgroundProcesses(in String packageName, int userId) = 102;
    boolean isUserAMonkey() = 103;
    WaitResult startActivityAndWait(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options,
            int userId) = 104;
    boolean willActivityBeVisible(in IBinder token) = 105;
    int startActivityWithConfig(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int startFlags, in Configuration newConfig,
            in Bundle options, int userId) = 106;
    // Retrieve info of applications installed on external media that are currently
    // running.
    List<ApplicationInfo> getRunningExternalApplications() = 107;
    void finishHeavyWeightApp() = 108;
    // A StrictMode violation to be handled.  The violationMask is a
    // subset of the original StrictMode policy bitmask, with only the
    // bit violated and penalty bits to be executed by the
    // ActivityManagerService remaining set.
    void handleApplicationStrictModeViolation(in IBinder app, int violationMask,
            in StrictMode.ViolationInfo crashInfo) = 109;
    boolean isImmersive(in IBinder token) = 110;
    void setImmersive(in IBinder token, boolean immersive) = 111;
    boolean isTopActivityImmersive() = 112;
    void crashApplication(int uid, int initialPid, in String packageName, in String message) = 113;
    String getProviderMimeType(in Uri uri, int userId) = 114;
    IBinder newUriPermissionOwner(in String name) = 115;
    void grantUriPermissionFromOwner(in IBinder owner, int fromUid, in String targetPkg,
            in Uri uri, int mode, int sourceUserId, int targetUserId) = 116;
    void revokeUriPermissionFromOwner(in IBinder owner, in Uri uri, int mode, int userId) = 117;
    int checkGrantUriPermission(int callingUid, in String targetPkg, in Uri uri,
            int modeFlags, int userId) = 118;
    // Cause the specified process to dump the specified heap.
    boolean dumpHeap(in String process, int userId, boolean managed, in String path,
            in ParcelFileDescriptor fd) = 119;
    int startActivities(in IApplicationThread caller, in String callingPackage,
            in Intent[] intents, in String[] resolvedTypes, in IBinder resultTo,
            in Bundle options, int userId) = 120;
    boolean isUserRunning(int userid, int flags) = 121;
    oneway void activitySlept(in IBinder token) = 122;
    int getFrontActivityScreenCompatMode() = 123;
    void setFrontActivityScreenCompatMode(int mode) = 124;
    int getPackageScreenCompatMode(in String packageName) = 125;
    void setPackageScreenCompatMode(in String packageName, int mode) = 126;
    boolean getPackageAskScreenCompat(in String packageName) = 127;
    void setPackageAskScreenCompat(in String packageName, boolean ask) = 128;
    boolean switchUser(int userid) = 129;
    void setFocusedTask(int taskId) = 130;
    boolean removeTask(int taskId) = 131;
    void registerProcessObserver(in IProcessObserver observer) = 132;
    void unregisterProcessObserver(in IProcessObserver observer) = 133;
    boolean isIntentSenderTargetedToPackage(in IIntentSender sender) = 134;
    void updatePersistentConfiguration(in Configuration values) = 135;
    long[] getProcessPss(in int[] pids) = 136;
    void showBootMessage(in CharSequence msg, boolean always) = 137;
    void killAllBackgroundProcesses() = 139;
    ContentProviderHolder getContentProviderExternal(in String name, int userId,
            in IBinder token) = 140;
    void removeContentProviderExternal(in String name, in IBinder token) = 141;
    // Get memory information about the calling process.
    void getMyMemoryState(out ActivityManager.RunningAppProcessInfo outInfo) = 142;
    boolean killProcessesBelowForeground(in String reason) = 143;
    UserInfo getCurrentUser() = 144;
    boolean shouldUpRecreateTask(in IBinder token, in String destAffinity) = 145;
    boolean navigateUpTo(in IBinder token, in Intent target, int resultCode,
            in Intent resultData) = 146;
    void setLockScreenShown(boolean showing) = 147;
    boolean finishActivityAffinity(in IBinder token) = 148;
    // This is not public because you need to be very careful in how you
    // manage your activity to make sure it is always the uid you expect.
    int getLaunchedFromUid(in IBinder activityToken) = 149;
    void unstableProviderDied(in IBinder connection) = 150;
    boolean isIntentSenderAnActivity(in IIntentSender sender) = 151;
    int startActivityAsUser(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo,
            in Bundle options, int userId) = 152;
    int stopUser(int userid, boolean force, in IStopUserCallback callback) = 153;
    void registerUserSwitchObserver(in IUserSwitchObserver observer, in String name) = 154;
    void unregisterUserSwitchObserver(in IUserSwitchObserver observer) = 155;
    int[] getRunningUserIds() = 156;
    void requestBugReport(int bugreportType) = 157;
    long inputDispatchingTimedOut(int pid, boolean aboveSystem, in String reason) = 158;
    void clearPendingBackup() = 159;
    Intent getIntentForIntentSender(in IIntentSender sender) = 160;
    Bundle getAssistContextExtras(int requestType) = 161;
    void reportAssistContextExtras(in IBinder token, in Bundle extras,
            in AssistStructure structure, in AssistContent content, in Uri referrer) = 162;
    // This is not public because you need to be very careful in how you
    // manage your activity to make sure it is always the uid you expect.
    String getLaunchedFromPackage(in IBinder activityToken) = 163;
    void killUid(int appId, int userId, in String reason) = 164;
    void setUserIsMonkey(boolean monkey) = 165;
    void hang(in IBinder who, boolean allowRestart) = 166;
    IActivityContainer createVirtualActivityContainer(in IBinder parentActivityToken,
            in IActivityContainerCallback callback) = 167;
    void moveTaskToStack(int taskId, int stackId, boolean toTop) = 168;
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
    void resizeStack(int stackId, in Rect bounds, boolean allowResizeInDockedMode,
            boolean preserveWindows, boolean animate, int animationDuration) = 169;
    List<ActivityManager.StackInfo> getAllStackInfos() = 170;
    void setFocusedStack(int stackId) = 171;
    ActivityManager.StackInfo getStackInfo(int stackId) = 172;
    boolean convertFromTranslucent(in IBinder token) = 173;
    boolean convertToTranslucent(in IBinder token, in Bundle options) = 174;
    void notifyActivityDrawn(in IBinder token) = 175;
    void reportActivityFullyDrawn(in IBinder token) = 176;
    void restart() = 177;
    void performIdleMaintenance() = 178;
    void takePersistableUriPermission(in Uri uri, int modeFlags, int userId) = 179;
    void releasePersistableUriPermission(in Uri uri, int modeFlags, int userId) = 180;
    ParceledListSlice getPersistedUriPermissions(in String packageName, boolean incoming) = 181;
    void appNotRespondingViaProvider(in IBinder connection) = 182;
    Rect getTaskBounds(int taskId) = 183;
    int getActivityDisplayId(in IBinder activityToken) = 184;
    boolean setProcessMemoryTrimLevel(in String process, int uid, int level) = 186;


    // Start of L transactions
    String getTagForIntentSender(in IIntentSender sender, in String prefix) = 210;
    boolean startUserInBackground(int userid) = 211;
    boolean isInHomeStack(int taskId) = 212;
    void startLockTaskModeById(int taskId) = 213;
    void startLockTaskModeByToken(in IBinder token) = 214;
    void stopLockTaskMode() = 215;
    boolean isInLockTaskMode() = 216;
    void setTaskDescription(in IBinder token, in ActivityManager.TaskDescription values) = 217;
    int startVoiceActivity(in String callingPackage, int callingPid, int callingUid,
            in Intent intent, in String resolvedType, in IVoiceInteractionSession session,
            in IVoiceInteractor interactor, int flags, in ProfilerInfo profilerInfo,
            in Bundle options, int userId) = 218;
    Bundle getActivityOptions(in IBinder token) = 219;
    List<IBinder> getAppTasks(in String callingPackage) = 220;
    void startSystemLockTaskMode(int taskId) = 221;
    void stopSystemLockTaskMode() = 222;
    void finishVoiceTask(in IVoiceInteractionSession session) = 223;
    boolean isTopOfTask(in IBinder token) = 224;
    boolean requestVisibleBehind(in IBinder token, boolean visible) = 225;
    boolean isBackgroundVisibleBehind(in IBinder token) = 226;
    void backgroundResourcesReleased(in IBinder token) = 227;
    void notifyLaunchTaskBehindComplete(in IBinder token) = 228;
    int startActivityFromRecents(int taskId, in Bundle options) = 229;
    void notifyEnterAnimationComplete(in IBinder token) = 230;
    int startActivityAsCaller(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options,
            boolean ignoreTargetSecurity, int userId) = 232;
    int addAppTask(in IBinder activityToken, in Intent intent,
            in ActivityManager.TaskDescription description, in Bitmap thumbnail) = 233;
    Point getAppTaskThumbnailSize() = 234;
    boolean releaseActivityInstance(in IBinder token) = 235;
    void releaseSomeActivities(in IApplicationThread app) = 236;
    void bootAnimationComplete() = 237;
    Bitmap getTaskDescriptionIcon(in String filename, int userId) = 238;
    boolean launchAssistIntent(in Intent intent, int requestType, in String hint, int userHandle,
            in Bundle args) = 239;
    void startInPlaceAnimationOnFrontMostApplication(in Bundle opts) = 240;
    int checkPermissionWithToken(in String permission, int pid, int uid,
            in IBinder callerToken) = 241;
    void registerTaskStackListener(in ITaskStackListener listener) = 242;


    // Start of M transactions
    void notifyCleartextNetwork(int uid, in byte[] firstPacket) = 280;
    IActivityContainer createStackOnDisplay(int displayId) = 281;
    int getFocusedStackId() = 282;
    void setTaskResizeable(int taskId, int resizeableMode) = 283;
    boolean requestAssistContextExtras(int requestType, in IResultReceiver receiver,
            in Bundle receiverExtras, in IBinder activityToken,
            boolean focused, boolean newSessionId) = 284;
    void resizeTask(int taskId, in Rect bounds, int resizeMode) = 285;
    int getLockTaskModeState() = 286;
    void setDumpHeapDebugLimit(in String processName, int uid, long maxMemSize,
            in String reportPackage) = 287;
    void dumpHeapFinished(in String path) = 288;
    void setVoiceKeepAwake(in IVoiceInteractionSession session, boolean keepAwake) = 289;
    void updateLockTaskPackages(int userId, in String[] packages) = 290;
    void noteAlarmStart(in IIntentSender sender, int sourceUid, in String tag) = 291;
    void noteAlarmFinish(in IIntentSender sender, int sourceUid, in String tag) = 292;
    int getPackageProcessState(in String packageName, in String callingPackage) = 293;
    oneway void showLockTaskEscapeMessage(in IBinder token) = 294;
    void updateDeviceOwner(in String packageName) = 295;
    /**
     * Notify the system that the keyguard is going away.
     *
     * @param flags See {@link android.view.WindowManagerPolicy#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
    void keyguardGoingAway(int flags) = 296;
    void registerUidObserver(in IUidObserver observer, int which, int cutpoint,
            String callingPackage) = 297;
    void unregisterUidObserver(in IUidObserver observer) = 298;
    boolean isAssistDataAllowedOnCurrentActivity() = 299;
    boolean showAssistFromActivity(in IBinder token, in Bundle args) = 300;
    boolean isRootVoiceInteraction(in IBinder token) = 301;


    // Start of N transactions
    // Start Binder transaction tracking for all applications.
    boolean startBinderTracking() = 340;
    // Stop Binder transaction tracking for all applications and dump trace data to the given file
    // descriptor.
    boolean stopBinderTrackingAndDump(in ParcelFileDescriptor fd) = 341;
    void positionTaskInStack(int taskId, int stackId, int position) = 342;
    int getActivityStackId(in IBinder token) = 343;
    void exitFreeformMode(in IBinder token) = 344;
    void reportSizeConfigurations(in IBinder token, in int[] horizontalSizeConfiguration,
            in int[] verticalSizeConfigurations, in int[] smallestWidthConfigurations) = 345;
    boolean moveTaskToDockedStack(int taskId, int createMode, boolean toTop, boolean animate,
            in Rect initialBounds, boolean moveHomeStackFront) = 346;
    void suppressResizeConfigChanges(boolean suppress) = 347;
    void moveTasksToFullscreenStack(int fromStackId, boolean onTop) = 348;
    boolean moveTopActivityToPinnedStack(int stackId, in Rect bounds) = 349;
    int getAppStartMode(int uid, in String packageName) = 350;
    boolean unlockUser(int userid, in byte[] token, in byte[] secret,
            in IProgressListener listener) = 351;
    boolean isInMultiWindowMode(in IBinder token) = 352;
    boolean isInPictureInPictureMode(in IBinder token) = 353;
    void killPackageDependents(in String packageName, int userId) = 354;
    void enterPictureInPictureMode(in IBinder token) = 355;
    void activityRelaunched(in IBinder token) = 356;
    IBinder getUriPermissionOwnerForActivity(in IBinder activityToken) = 357;
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
    void resizeDockedStack(in Rect dockedBounds, in Rect tempDockedTaskBounds,
            in Rect tempDockedTaskInsetBounds,
            in Rect tempOtherTaskBounds, in Rect tempOtherTaskInsetBounds) = 358;
    int setVrMode(in IBinder token, boolean enabled, in ComponentName packageName) = 359;
    // Gets the URI permissions granted to an arbitrary package.
    // NOTE: this is different from getPersistedUriPermissions(), which returns the URIs the package
    // granted to another packages (instead of those granted to it).
    ParceledListSlice getGrantedUriPermissions(in String packageName, int userId) = 360;
    // Clears the URI permissions granted to an arbitrary package.
    void clearGrantedUriPermissions(in String packageName, int userId) = 361;
    boolean isAppForeground(int uid) = 362;
    void startLocalVoiceInteraction(in IBinder token, in Bundle options) = 363;
    void stopLocalVoiceInteraction(in IBinder token) = 364;
    boolean supportsLocalVoiceInteraction() = 365;
    void notifyPinnedStackAnimationEnded() = 366;
    void removeStack(int stackId) = 367;
    void makePackageIdle(String packageName, int userId) = 368;
    int getMemoryTrimLevel() = 369;
    /**
     * Resizes the pinned stack.
     *
     * @param pinnedBounds The bounds for the pinned stack.
     * @param tempPinnedTaskBounds The temporary bounds for the tasks in the pinned stack, which
     *                             might be different from the stack bounds to allow more
     *                             flexibility while resizing, or {@code null} if they should be the
     *                             same as the stack bounds.
     */
    void resizePinnedStack(in Rect pinnedBounds, in Rect tempPinnedTaskBounds) = 370;
    boolean isVrModePackageEnabled(in ComponentName packageName) = 371;
    /**
     * Moves all tasks from the docked stack in the fullscreen stack and puts the top task of the
     * fullscreen stack into the docked stack.
     */
    void swapDockedAndFullscreenStack() = 372;
    void notifyLockedProfile(int userId) = 373;
    void startConfirmDeviceCredentialIntent(in Intent intent) = 374;
    void sendIdleJobTrigger() = 375;
    int sendIntentSender(in IIntentSender target, int code, in Intent intent,
            in String resolvedType, in IIntentReceiver finishedReceiver,
            in String requiredPermission, in Bundle options) = 376;


    // Start of N MR1 transactions
    void setVrThread(int tid) = 377;
    void setRenderThread(int tid) = 378;
    /**
     * Lets activity manager know whether the calling process is currently showing "top-level" UI
     * that is not an activity, i.e. windows on the screen the user is currently interacting with.
     *
     * <p>This flag can only be set for persistent processes.
     *
     * @param hasTopUi Whether the calling process has "top-level" UI.
     */
    void setHasTopUi(boolean hasTopUi) = 379;
    /**
     * Returns if the target of the PendingIntent can be fired directly, without triggering
     * a work profile challenge. This can happen if the PendingIntent is to start direct-boot
     * aware activities, and the target user is in RUNNING_LOCKED state, i.e. we should allow
     * direct-boot aware activity to bypass work challenge when the user hasn't unlocked yet.
     * @param intent the {@link  PendingIntent} to be tested.
     * @return {@code true} if the intent should not trigger a work challenge, {@code false}
     *     otherwise.
     * @throws RemoteException
     */
    boolean canBypassWorkChallenge(in PendingIntent intent) = 380;

    // Start of O transactions
    void requestActivityRelaunch(in IBinder token) = 400;
    /**
     * Updates override configuration applied to specific display.
     * @param values Update values for display configuration. If null is passed it will request the
     *               Window Manager to compute new config for the specified display.
     * @param displayId Id of the display to apply the config to.
     * @throws RemoteException
     * @return Returns true if the configuration was updated.
     */
    boolean updateDisplayOverrideConfiguration(in Configuration values, int displayId) = 401;
    void unregisterTaskStackListener(ITaskStackListener listener) = 402;
    void moveStackToDisplay(int stackId, int displayId) = 403;
    void enterPictureInPictureModeWithAspectRatio(in IBinder token, float aspectRatio) = 404;
    void setPictureInPictureAspectRatio(in IBinder token, float aspectRatio) = 405;
    boolean requestAutoFillData(in IResultReceiver receiver, in Bundle receiverExtras,
            in IBinder activityToken) = 406;

    // Please keep these transaction codes the same -- they are also
    // sent by C++ code. when a new method is added, use the next available transaction id.
}
