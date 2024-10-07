/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.RESIZE_MODE_SYSTEM;
import static android.app.ActivityTaskManager.RESIZE_MODE_USER;
import static android.app.WaitResult.launchStateToString;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.Process.INVALID_UID;
import static android.view.Display.INVALID_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;
import static com.android.server.am.ActivityManagerDebugConfig.LOG_WRITER_INFO;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppBatteryTracker.BatteryUsage.BATTERY_USAGE_COUNT;
import static com.android.server.am.LowMemDetector.ADJ_MEM_FACTOR_NOTHING;

import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.AppGlobals;
import android.app.BroadcastOptions;
import android.app.ForegroundServiceDelegationOptions;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IProcessObserver;
import android.app.IStopUserCallback;
import android.app.IUserSwitchObserver;
import android.app.KeyguardManager;
import android.app.ProcessStateEnum;
import android.app.ProfilerInfo;
import android.app.RemoteServiceException.CrashedByAdbException;
import android.app.UidObserver;
import android.app.UserSwitchObserver;
import android.app.WaitResult;
import android.app.usage.AppStandbyInfo;
import android.app.usage.ConfigurationStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageStatsManager;
import android.compat.Compatibility;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.DeviceConfigurationProto;
import android.content.GlobalConfigurationProto;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.opengl.GLES10;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteCallback.OnResultListener;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.util.TeeWriter;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;
import android.view.Display;
import android.window.SplashScreen;

import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.util.MemInfoReader;
import com.android.server.LocalServices;
import com.android.server.am.LowMemDetector.MemFactor;
import com.android.server.am.nano.Capabilities;
import com.android.server.am.nano.Capability;
import com.android.server.am.nano.FrameworkCapability;
import com.android.server.am.nano.VMCapability;
import com.android.server.am.nano.VMInfo;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.AnrTimer;
import com.android.server.utils.Slogf;

import dalvik.annotation.optimization.NeverCompile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

final class ActivityManagerShellCommand extends ShellCommand {

    static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityManagerShellCommand" : TAG_AM;


    public static final String NO_CLASS_ERROR_CODE = "Error type 3";

    private static final String SHELL_PACKAGE_NAME = "com.android.shell";

    private static final int USER_OPERATION_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes

    private static final DateTimeFormatter LOG_NAME_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static final String PROFILER_OUTPUT_VERSION_FLAG = "--profiler-output-version";

    // IPC interface to activity manager -- don't need to do additional security checks.
    final IActivityManager mInterface;
    final IActivityTaskManager mTaskInterface;

    // Internal service impl -- must perform security checks before touching.
    final ActivityManagerService mInternal;

    // Convenience for interacting with package manager.
    final IPackageManager mPm;

    private int mStartFlags = 0;
    private boolean mWaitOption = false;
    private boolean mStopOption = false;

    private int mRepeat = 0;
    private int mUserId;
    private String mReceiverPermission;

    private String mProfileFile;
    private int mSamplingInterval;
    private boolean mAutoStop;
    private boolean mStreaming;   // Streaming the profiling output to a file.
    private String mAgent;  // Agent to attach on startup.
    private boolean mAttachAgentDuringBind;  // Whether agent should be attached late.
    private int mClockType; // Whether we need thread cpu / wall clock / both.
    private int mProfilerOutputVersion; // The version of the profiler output.
    private int mDisplayId;
    private int mTaskDisplayAreaFeatureId;
    private int mWindowingMode;
    private int mActivityType;
    private int mTaskId;
    private boolean mIsTaskOverlay;
    private boolean mIsLockTask;
    private boolean mAsync;
    private BroadcastOptions mBroadcastOptions;
    private boolean mShowSplashScreen;
    private boolean mDismissKeyguardIfInsecure;

    final boolean mDumping;

    private static final String[] CAPABILITIES = {"start.suspend"};


    ActivityManagerShellCommand(ActivityManagerService service, boolean dumping) {
        mInterface = service;
        mTaskInterface = service.mActivityTaskManager;
        mInternal = service;
        mPm = AppGlobals.getPackageManager();
        mDumping = dumping;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "help":
                    onHelp();
                    return 0;
                case "start":
                case "start-activity":
                    return runStartActivity(pw);
                case "start-in-vsync":
                    final ProgressWaiter waiter = new ProgressWaiter(0);
                    final int[] startResult = new int[1];
                    startResult[0] = -1;
                    mInternal.mUiHandler.runWithScissors(
                            () -> Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
                                try {
                                    startResult[0] = runStartActivity(pw);
                                    waiter.onFinished(0, null /* extras */);
                                } catch (Exception ex) {
                                    getErrPrintWriter().println(
                                            "Error: unable to start activity, " + ex);
                                }
                            }),
                            USER_OPERATION_TIMEOUT_MS / 2);
                    waiter.waitForFinish(USER_OPERATION_TIMEOUT_MS);
                    return startResult[0];
                case "startservice":
                case "start-service":
                    return runStartService(pw, false);
                case "startforegroundservice":
                case "startfgservice":
                case "start-foreground-service":
                case "start-fg-service":
                    return runStartService(pw, true);
                case "stopservice":
                case "stop-service":
                    return runStopService(pw);
                case "broadcast":
                    return runSendBroadcast(pw);
                case "compact":
                    return runCompact(pw);
                case "freeze":
                    return runFreeze(pw, true);
                case "unfreeze":
                    return runFreeze(pw, false);
                case "instrument":
                    getOutPrintWriter().println("Error: must be invoked through 'am instrument'.");
                    return -1;
                case "trace-ipc":
                    return runTraceIpc(pw);
                case "trace-timer":
                    return runTraceTimer(pw);
                case "profile":
                    return runProfile(pw);
                case "dumpheap":
                    return runDumpHeap(pw);
                case "set-debug-app":
                    return runSetDebugApp(pw);
                case "set-agent-app":
                    return runSetAgentApp(pw);
                case "clear-debug-app":
                    return runClearDebugApp(pw);
                case "set-watch-heap":
                    return runSetWatchHeap(pw);
                case "clear-watch-heap":
                    return runClearWatchHeap(pw);
                case "clear-start-info":
                    return runClearStartInfo(pw);
                case "start-info-detailed-monitoring":
                    return runStartInfoDetailedMonitoring(pw);
                case "clear-exit-info":
                    return runClearExitInfo(pw);
                case "bug-report":
                    return runBugReport(pw);
                case "force-stop":
                    return runForceStop(pw);
                case "stop-app":
                    return runStopApp(pw);
                case "clear-recent-apps":
                    return runClearRecentApps(pw);
                case "fgs-notification-rate-limit":
                    return runFgsNotificationRateLimit(pw);
                case "crash":
                    return runCrash(pw);
                case "kill":
                    return runKill(pw);
                case "kill-all":
                    return runKillAll(pw);
                case "make-uid-idle":
                    return runMakeIdle(pw);
                case "set-deterministic-uid-idle":
                    return runSetDeterministicUidIdle(pw);
                case "monitor":
                    return runMonitor(pw);
                case "watch-uids":
                    return runWatchUids(pw);
                case "hang":
                    return runHang(pw);
                case "restart":
                    return runRestart(pw);
                case "idle-maintenance":
                    return runIdleMaintenance(pw);
                case "screen-compat":
                    return runScreenCompat(pw);
                case "package-importance":
                    return runPackageImportance(pw);
                case "to-uri":
                    return runToUri(pw, 0);
                case "to-intent-uri":
                    return runToUri(pw, Intent.URI_INTENT_SCHEME);
                case "to-app-uri":
                    return runToUri(pw, Intent.URI_ANDROID_APP_SCHEME);
                case "switch-user":
                    return runSwitchUser(pw);
                case "get-current-user":
                    return runGetCurrentUser(pw);
                case "start-user":
                    return runStartUser(pw);
                case "unlock-user":
                    return runUnlockUser(pw);
                case "stop-user":
                    return runStopUser(pw);
                case "is-user-stopped":
                    return runIsUserStopped(pw);
                case "get-started-user-state":
                    return runGetStartedUserState(pw);
                case "track-associations":
                    return runTrackAssociations(pw);
                case "untrack-associations":
                    return runUntrackAssociations(pw);
                case "get-uid-state":
                    return getUidState(pw);
                case "get-config":
                    return runGetConfig(pw);
                case "suppress-resize-config-changes":
                    return runSuppressResizeConfigChanges(pw);
                case "set-inactive":
                    return runSetInactive(pw);
                case "get-inactive":
                    return runGetInactive(pw);
                case "set-standby-bucket":
                    return runSetStandbyBucket(pw);
                case "get-standby-bucket":
                    return runGetStandbyBucket(pw);
                case "send-trim-memory":
                    return runSendTrimMemory(pw);
                case "display":
                    return runDisplay(pw);
                case "stack":
                    return runStack(pw);
                case "task":
                    return runTask(pw);
                case "write":
                    return runWrite(pw);
                case "attach-agent":
                    return runAttachAgent(pw);
                case "supports-multiwindow":
                    return runSupportsMultiwindow(pw);
                case "supports-split-screen-multi-window":
                    return runSupportsSplitScreenMultiwindow(pw);
                case "update-appinfo":
                    return runUpdateApplicationInfo(pw);
                case "no-home-screen":
                    return runNoHomeScreen(pw);
                case "wait-for-broadcast-idle":
                    return runWaitForBroadcastIdle(pw);
                case "wait-for-broadcast-barrier":
                    return runWaitForBroadcastBarrier(pw);
                case "wait-for-application-barrier":
                    return runWaitForApplicationBarrier(pw);
                case "wait-for-broadcast-dispatch":
                    return runWaitForBroadcastDispatch(pw);
                case "set-ignore-delivery-group-policy":
                    return runSetIgnoreDeliveryGroupPolicy(pw);
                case "clear-ignore-delivery-group-policy":
                    return runClearIgnoreDeliveryGroupPolicy(pw);
                case "compat":
                    return runCompat(pw);
                case "refresh-settings-cache":
                    return runRefreshSettingsCache();
                case "memory-factor":
                    return runMemoryFactor(pw);
                case "service-restart-backoff":
                    return runServiceRestartBackoff(pw);
                case "get-isolated-pids":
                    return runGetIsolatedProcesses(pw);
                case "set-stop-user-on-switch":
                    return runSetStopUserOnSwitch(pw);
                case "set-bg-abusive-uids":
                    return runSetBgAbusiveUids(pw);
                case "list-bg-exemptions-config":
                    return runListBgExemptionsConfig(pw);
                case "set-bg-restriction-level":
                    return runSetBgRestrictionLevel(pw);
                case "get-bg-restriction-level":
                    return runGetBgRestrictionLevel(pw);
                case "observe-foreground-process":
                    return runGetCurrentForegroundProcess(pw, mInternal);
                case "reset-dropbox-rate-limiter":
                    return runResetDropboxRateLimiter();
                case "list-displays-for-starting-users":
                    return runListDisplaysForStartingUsers(pw);
                case "set-foreground-service-delegate":
                    return runSetForegroundServiceDelegate(pw);
                case "capabilities":
                    return runCapabilities(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    int runCapabilities(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        boolean outputAsProtobuf = false;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--protobuf")) {
                outputAsProtobuf = true;
            } else {
                err.println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String vmName = System.getProperty("java.vm.name", "?");
        String vmVersion = System.getProperty("java.vm.version", "?");

        if (outputAsProtobuf) {
            Capabilities capabilities = new Capabilities();
            capabilities.values =  new Capability[CAPABILITIES.length];
            for (int i = 0; i < CAPABILITIES.length; i++) {
                Capability cap = new Capability();
                cap.name = CAPABILITIES[i];
                capabilities.values[i] = cap;
            }

            String[] vmCapabilities = Debug.getVmFeatureList();
            capabilities.vmCapabilities = new VMCapability[vmCapabilities.length];
            for (int i = 0; i < vmCapabilities.length; i++) {
                VMCapability cap = new VMCapability();
                cap.name = vmCapabilities[i];
                capabilities.vmCapabilities[i] = cap;
            }

            String[] fmCapabilities = Debug.getFeatureList();
            capabilities.frameworkCapabilities = new FrameworkCapability[fmCapabilities.length];
            for (int i = 0; i < fmCapabilities.length; i++) {
                FrameworkCapability cap = new FrameworkCapability();
                cap.name = fmCapabilities[i];
                capabilities.frameworkCapabilities[i] = cap;
            }

            VMInfo vmInfo = new VMInfo();
            vmInfo.name = vmName;
            vmInfo.version = vmVersion;
            capabilities.vmInfo = vmInfo;

            try {
                getRawOutputStream().write(Capabilities.toByteArray(capabilities));
            } catch (IOException e) {
                pw.println("Error while serializing capabilities protobuffer");
            }

        } else {
            // Unfortunately we don't have protobuf text format capabilities here.
            // Fallback to line separated list instead for text parser.
            pw.println("Format: 2");
            for (String capability : CAPABILITIES) {
                pw.println(capability);
            }
            for (String capability : Debug.getVmFeatureList()) {
                pw.println("vm:" + capability);
            }
            for (String capability : Debug.getFeatureList()) {
                pw.println("framework:" + capability);
            }
            pw.println("vm_name:" + vmName);
            pw.println("vm_version:" + vmVersion);
        }
        return 0;
    }

    private Intent makeIntent(int defUser) throws URISyntaxException {
        mStartFlags = 0;
        mWaitOption = false;
        mStopOption = false;
        mRepeat = 0;
        mProfileFile = null;
        mSamplingInterval = 0;
        mAutoStop = false;
        mStreaming = false;
        mUserId = defUser;
        mDisplayId = INVALID_DISPLAY;
        mTaskDisplayAreaFeatureId = FEATURE_UNDEFINED;
        mWindowingMode = WINDOWING_MODE_UNDEFINED;
        mActivityType = ACTIVITY_TYPE_UNDEFINED;
        mTaskId = INVALID_TASK_ID;
        mIsTaskOverlay = false;
        mIsLockTask = false;
        mAsync = false;
        mBroadcastOptions = null;

        return Intent.parseCommandArgs(this, new Intent.CommandOptionHandler() {
            @Override
            public boolean handleOption(String opt, ShellCommand cmd) {
                if (opt.equals("-D")) {
                    mStartFlags |= ActivityManager.START_FLAG_DEBUG;
                } else if (opt.equals("--suspend")) {
                    mStartFlags |= ActivityManager.START_FLAG_DEBUG_SUSPEND;
                } else if (opt.equals("-N")) {
                    mStartFlags |= ActivityManager.START_FLAG_NATIVE_DEBUGGING;
                } else if (opt.equals("-W")) {
                    mWaitOption = true;
                } else if (opt.equals("-P")) {
                    mProfileFile = getNextArgRequired();
                    mAutoStop = true;
                } else if (opt.equals("--start-profiler")) {
                    mProfileFile = getNextArgRequired();
                    mAutoStop = false;
                } else if (opt.equals("--sampling")) {
                    mSamplingInterval = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--clock-type")) {
                    String clock_type = getNextArgRequired();
                    mClockType = ProfilerInfo.getClockTypeFromString(clock_type);
                } else if (opt.equals(PROFILER_OUTPUT_VERSION_FLAG)) {
                    mProfilerOutputVersion = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--streaming")) {
                    mStreaming = true;
                } else if (opt.equals("--attach-agent")) {
                    if (mAgent != null) {
                        cmd.getErrPrintWriter().println(
                                "Multiple --attach-agent(-bind) not supported");
                        return false;
                    }
                    mAgent = getNextArgRequired();
                    mAttachAgentDuringBind = false;
                } else if (opt.equals("--attach-agent-bind")) {
                    if (mAgent != null) {
                        cmd.getErrPrintWriter().println(
                                "Multiple --attach-agent(-bind) not supported");
                        return false;
                    }
                    mAgent = getNextArgRequired();
                    mAttachAgentDuringBind = true;
                } else if (opt.equals("-R")) {
                    mRepeat = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("-S")) {
                    mStopOption = true;
                } else if (opt.equals("--track-allocation")) {
                    mStartFlags |= ActivityManager.START_FLAG_TRACK_ALLOCATION;
                } else if (opt.equals("--user")) {
                    mUserId = UserHandle.parseUserArg(getNextArgRequired());
                } else if (opt.equals("--receiver-permission")) {
                    mReceiverPermission = getNextArgRequired();
                } else if (opt.equals("--display")) {
                    mDisplayId = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--task-display-area-feature-id")) {
                    mTaskDisplayAreaFeatureId = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--windowingMode")) {
                    mWindowingMode = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--activityType")) {
                    mActivityType = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--task")) {
                    mTaskId = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--task-overlay")) {
                    mIsTaskOverlay = true;
                } else if (opt.equals("--lock-task")) {
                    mIsLockTask = true;
                } else if (opt.equals("--allow-background-activity-starts")) {
                    if (mBroadcastOptions == null) {
                        mBroadcastOptions = BroadcastOptions.makeBasic();
                    }
                    mBroadcastOptions.setBackgroundActivityStartsAllowed(true);
                } else if (opt.equals("--async")) {
                    mAsync = true;
                } else if (opt.equals("--splashscreen-show-icon")) {
                    mShowSplashScreen = true;
                } else if (opt.equals("--dismiss-keyguard-if-insecure")
                        || opt.equals("--dismiss-keyguard")) {
                    mDismissKeyguardIfInsecure = true;
                } else if (opt.equals("--allow-fgs-start-reason")) {
                    final int reasonCode = Integer.parseInt(getNextArgRequired());
                    mBroadcastOptions = BroadcastOptions.makeBasic();
                    mBroadcastOptions.setTemporaryAppAllowlist(10_000,
                            TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                            reasonCode,
                            "");
                } else {
                    return false;
                }
                return true;
            }
        });
    }

    private class ProgressWaiter extends IProgressListener.Stub {
        private final CountDownLatch mFinishedLatch = new CountDownLatch(1);
        private final @UserIdInt int mUserId;

        private ProgressWaiter(@UserIdInt int userId) {
            mUserId = userId;
        }

        @Override
        public void onStarted(int id, Bundle extras) {}

        @Override
        public void onProgress(int id, int progress, Bundle extras) {
            Slogf.d(TAG, "ProgressWaiter[user=%d]: onProgress(%d, %d)", mUserId, id, progress);
        }

        @Override
        public void onFinished(int id, Bundle extras) {
            Slogf.d(TAG, "ProgressWaiter[user=%d]: onFinished(%d)", mUserId, id);
            mFinishedLatch.countDown();
        }

        @Override
        public String toString() {
            return "ProgressWaiter[userId=" + mUserId + ", finished="
                    + (mFinishedLatch.getCount() == 0) + "]";
        }

        public boolean waitForFinish(long timeoutMillis) {
            try {
                return mFinishedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                System.err.println("Thread interrupted unexpectedly.");
                return false;
            }
        }
    }

    int runStartActivity(PrintWriter pw) throws RemoteException {
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        if (mUserId == UserHandle.USER_ALL) {
            getErrPrintWriter().println("Error: Can't start service with user 'all'");
            return 1;
        }

        final String mimeType = intent.resolveType(mInternal.mContext);

        do {
            if (mStopOption) {
                String packageName;
                if (intent.getComponent() != null) {
                    packageName = intent.getComponent().getPackageName();
                } else {
                    // queryIntentActivities does not convert user id, so we convert it here first
                    int userIdForQuery = mInternal.mUserController.handleIncomingUser(
                            Binder.getCallingPid(), Binder.getCallingUid(), mUserId, false,
                            ALLOW_NON_FULL, "ActivityManagerShellCommand", null);
                    List<ResolveInfo> activities = mPm.queryIntentActivities(intent, mimeType,
                            0, userIdForQuery).getList();
                    if (activities == null || activities.size() <= 0) {
                        getErrPrintWriter().println("Error: Intent does not match any activities: "
                                + intent);
                        return 1;
                    } else if (activities.size() > 1) {
                        getErrPrintWriter().println(
                                "Error: Intent matches multiple activities; can't stop: "
                                + intent);
                        return 1;
                    }
                    packageName = activities.get(0).activityInfo.packageName;
                }
                pw.println("Stopping: " + packageName);
                pw.flush();
                mInterface.forceStopPackage(packageName, mUserId);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                }
            }

            ProfilerInfo profilerInfo = null;

            if (mProfileFile != null || mAgent != null) {
                ParcelFileDescriptor fd = null;
                if (mProfileFile != null) {
                    fd = openFileForSystem(mProfileFile, "w");
                    if (fd == null) {
                        return 1;
                    }
                }
                profilerInfo =
                        new ProfilerInfo(mProfileFile, fd, mSamplingInterval, mAutoStop, mStreaming,
                                mAgent, mAttachAgentDuringBind, mClockType, mProfilerOutputVersion);
            }

            pw.println("Starting: " + intent);
            pw.flush();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            WaitResult result = null;
            int res;
            final long startTime = SystemClock.uptimeMillis();
            ActivityOptions options = null;
            if (mDisplayId != INVALID_DISPLAY) {
                options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(mDisplayId);
            }
            if (mTaskDisplayAreaFeatureId != FEATURE_UNDEFINED) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setLaunchTaskDisplayAreaFeatureId(mTaskDisplayAreaFeatureId);
            }
            if (mWindowingMode != WINDOWING_MODE_UNDEFINED) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setLaunchWindowingMode(mWindowingMode);
            }
            if (mActivityType != ACTIVITY_TYPE_UNDEFINED) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setLaunchActivityType(mActivityType);
            }
            if (mTaskId != INVALID_TASK_ID) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setLaunchTaskId(mTaskId);

                if (mIsTaskOverlay) {
                    options.setTaskOverlay(true, true /* canResume */);
                }
            }
            if (mIsLockTask) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setLockTaskEnabled(true);
            }
            if (mShowSplashScreen) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON);
            }
            if (mDismissKeyguardIfInsecure) {
                if (options == null) {
                    options = ActivityOptions.makeBasic();
                }
                options.setDismissKeyguardIfInsecure();
            }
            if (mWaitOption) {
                result = mInternal.startActivityAndWait(null, SHELL_PACKAGE_NAME, null, intent,
                        mimeType, null, null, 0, mStartFlags, profilerInfo,
                        options != null ? options.toBundle() : null, mUserId);
                res = result.result;
            } else {
                res = mInternal.startActivityAsUserWithFeature(null, SHELL_PACKAGE_NAME, null,
                        intent, mimeType, null, null, 0, mStartFlags, profilerInfo,
                        options != null ? options.toBundle() : null, mUserId);
            }
            final long endTime = SystemClock.uptimeMillis();
            PrintWriter out = mWaitOption ? pw : getErrPrintWriter();
            boolean launched = false;
            switch (res) {
                case ActivityManager.START_SUCCESS:
                    launched = true;
                    break;
                case ActivityManager.START_SWITCHES_CANCELED:
                    launched = true;
                    out.println(
                            "Warning: Activity not started because the "
                                    + " current activity is being kept for the user.");
                    break;
                case ActivityManager.START_DELIVERED_TO_TOP:
                    launched = true;
                    out.println(
                            "Warning: Activity not started, intent has "
                                    + "been delivered to currently running "
                                    + "top-most instance.");
                    break;
                case ActivityManager.START_RETURN_INTENT_TO_CALLER:
                    launched = true;
                    out.println(
                            "Warning: Activity not started because intent "
                                    + "should be handled by the caller");
                    break;
                case ActivityManager.START_TASK_TO_FRONT:
                    launched = true;
                    out.println(
                            "Warning: Activity not started, its current "
                                    + "task has been brought to the front");
                    break;
                case ActivityManager.START_INTENT_NOT_RESOLVED:
                    out.println(
                            "Error: Activity not started, unable to "
                                    + "resolve " + intent.toString());
                    return 1;
                case ActivityManager.START_CLASS_NOT_FOUND:
                    out.println(NO_CLASS_ERROR_CODE);
                    out.println("Error: Activity class " +
                            intent.getComponent().toShortString()
                            + " does not exist.");
                    return 1;
                case ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT:
                    out.println(
                            "Error: Activity not started, you requested to "
                                    + "both forward and receive its result");
                    return 1;
                case ActivityManager.START_PERMISSION_DENIED:
                    out.println(
                            "Error: Activity not started, you do not "
                                    + "have permission to access it.");
                    return 1;
                case ActivityManager.START_NOT_VOICE_COMPATIBLE:
                    out.println(
                            "Error: Activity not started, voice control not allowed for: "
                                    + intent);
                    return 1;
                case ActivityManager.START_NOT_CURRENT_USER_ACTIVITY:
                    out.println(
                            "Error: Not allowed to start background user activity"
                                    + " that shouldn't be displayed for all users.");
                    return 1;
                default:
                    out.println(
                            "Error: Activity not started, unknown error code " + res);
                    return 1;
            }
            out.flush();
            if (mWaitOption && launched) {
                if (result == null) {
                    result = new WaitResult();
                    result.who = intent.getComponent();
                }
                pw.println("Status: " + (result.timeout ? "timeout" : "ok"));
                pw.println("LaunchState: " + launchStateToString(result.launchState));
                if (result.who != null) {
                    pw.println("Activity: " + result.who.flattenToShortString());
                }
                if (result.totalTime >= 0) {
                    pw.println("TotalTime: " + result.totalTime);
                }
                pw.println("WaitTime: " + (endTime-startTime));
                pw.println("Complete");
                pw.flush();
            }
            mRepeat--;
            if (mRepeat > 0) {
                mTaskInterface.unhandledBack();
            }
        } while (mRepeat > 0);
        return 0;
    }

    int runStartService(PrintWriter pw, boolean asForeground) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (mUserId == UserHandle.USER_ALL) {
            err.println("Error: Can't start activity with user 'all'");
            return -1;
        }
        pw.println("Starting service: " + intent);
        pw.flush();
        ComponentName cn = mInterface.startService(null, intent, intent.getType(),
                asForeground, SHELL_PACKAGE_NAME, null, mUserId);
        if (cn == null) {
            err.println("Error: Not found; no service started.");
            return -1;
        } else if (cn.getPackageName().equals("!")) {
            err.println("Error: Requires permission " + cn.getClassName());
            return -1;
        } else if (cn.getPackageName().equals("!!")) {
            err.println("Error: " + cn.getClassName());
            return -1;
        } else if (cn.getPackageName().equals("?")) {
            err.println("Error: " + cn.getClassName());
            return -1;
        }
        return 0;
    }

    int runStopService(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (mUserId == UserHandle.USER_ALL) {
            err.println("Error: Can't stop activity with user 'all'");
            return -1;
        }
        pw.println("Stopping service: " + intent);
        pw.flush();
        int result = mInterface.stopService(null, intent, intent.getType(), mUserId);
        if (result == 0) {
            err.println("Service not stopped: was not running.");
            return -1;
        } else if (result == 1) {
            err.println("Service stopped");
            return -1;
        } else if (result == -1) {
            err.println("Error stopping service");
            return -1;
        }
        return 0;
    }

    final static class IntentReceiver extends IIntentReceiver.Stub {
        private final PrintWriter mPw;
        private boolean mFinished = false;

        IntentReceiver(PrintWriter pw) {
            mPw = pw;
        }

        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            String line = "Broadcast completed: result=" + resultCode;
            if (data != null) line = line + ", data=\"" + data + "\"";
            if (extras != null) line = line + ", extras: " + extras;
            mPw.println(line);
            mPw.flush();
            synchronized (this) {
                mFinished = true;
                notifyAll();
            }
        }

        public synchronized void waitForFinish() {
            try {
                while (!mFinished) wait();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    int runSendBroadcast(PrintWriter pw) throws RemoteException {
        pw = new PrintWriter(new TeeWriter(LOG_WRITER_INFO, pw));
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        intent.addFlags(Intent.FLAG_RECEIVER_FROM_SHELL);
        IntentReceiver receiver = new IntentReceiver(pw);
        String[] requiredPermissions = mReceiverPermission == null ? null
                : new String[] {mReceiverPermission};
        pw.println("Broadcasting: " + intent);
        pw.flush();
        Bundle bundle = mBroadcastOptions == null ? null : mBroadcastOptions.toBundle();
        final int result = mInterface.broadcastIntentWithFeature(null, null, intent, null,
                receiver, 0, null, null, requiredPermissions, null, null,
                android.app.AppOpsManager.OP_NONE, bundle, true, false, mUserId);
        Slogf.i(TAG, "Enqueued broadcast %s: " + result, intent);
        if (result == ActivityManager.BROADCAST_SUCCESS && !mAsync) {
            receiver.waitForFinish();
        }
        return 0;
    }

    int runTraceIpc(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (op.equals("start")) {
            return runTraceIpcStart(pw);
        } else if (op.equals("stop")) {
            return runTraceIpcStop(pw);
        } else {
            getErrPrintWriter().println("Error: unknown trace ipc command '" + op + "'");
            return -1;
        }
    }

    int runTraceIpcStart(PrintWriter pw) throws RemoteException {
        pw.println("Starting IPC tracing.");
        pw.flush();
        mInterface.startBinderTracking();
        return 0;
    }

    int runTraceIpcStop(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        String opt;
        String filename = null;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--dump-file")) {
                filename = getNextArgRequired();
            } else {
                err.println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (filename == null) {
            err.println("Error: Specify filename to dump logs to.");
            return -1;
        }

        // Writes an error message to stderr on failure
        ParcelFileDescriptor fd = openFileForSystem(filename, "w");
        if (fd == null) {
            return -1;
        }

        if (!mInterface.stopBinderTrackingAndDump(fd)) {
            err.println("STOP TRACE FAILED.");
            return -1;
        }

        pw.println("Stopped IPC tracing. Dumping logs to: " + filename);
        return 0;
    }

    // Update AnrTimer tracing.
    private int runTraceTimer(PrintWriter pw) throws RemoteException {
        if (!AnrTimer.traceFeatureEnabled()) return -1;

        // Delegate all argument parsing to the AnrTimer method.
        try {
            final String result = AnrTimer.traceTimers(peekRemainingArgs());
            if (result != null) {
                pw.println(result);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println("Error: bad trace-timer command: " + e);
            return -1;
        }
    }

    // NOTE: current profiles can only be started on default display (even on automotive builds with
    // passenger displays), so there's no need to pass a display-id
    private int runProfile(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        String profileFile = null;
        boolean start = false;
        int userId = UserHandle.USER_CURRENT;
        int profileType = 0;
        mSamplingInterval = 0;
        mStreaming = false;
        mClockType = ProfilerInfo.CLOCK_TYPE_DEFAULT;
        mProfilerOutputVersion = ProfilerInfo.OUTPUT_VERSION_DEFAULT;

        String process = null;

        String cmd = getNextArgRequired();

        if ("start".equals(cmd)) {
            start = true;
            String opt;
            while ((opt=getNextOption()) != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else if (opt.equals("--clock-type")) {
                    String clock_type = getNextArgRequired();
                    mClockType = ProfilerInfo.getClockTypeFromString(clock_type);
                } else if (opt.equals(PROFILER_OUTPUT_VERSION_FLAG)) {
                    mProfilerOutputVersion = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--streaming")) {
                    mStreaming = true;
                } else if (opt.equals("--sampling")) {
                    mSamplingInterval = Integer.parseInt(getNextArgRequired());
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return -1;
                }
            }
            process = getNextArgRequired();
        } else if ("stop".equals(cmd)) {
            String opt;
            while ((opt=getNextOption()) != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return -1;
                }
            }
            process = getNextArgRequired();
        } else {
            // Compatibility with old syntax: process is specified first.
            process = cmd;
            cmd = getNextArgRequired();
            if ("start".equals(cmd)) {
                start = true;
            } else if (!"stop".equals(cmd)) {
                throw new IllegalArgumentException("Profile command " + process + " not valid");
            }
        }

        if (userId == UserHandle.USER_ALL) {
            err.println("Error: Can't profile with user 'all'");
            return -1;
        }

        ParcelFileDescriptor fd = null;
        ProfilerInfo profilerInfo = null;

        if (start) {
            profileFile = getNextArgRequired();
            fd = openFileForSystem(profileFile, "w");
            if (fd == null) {
                return -1;
            }
            profilerInfo = new ProfilerInfo(profileFile, fd, mSamplingInterval, false, mStreaming,
                    null, false, mClockType, mProfilerOutputVersion);
        }

        if (!mInterface.profileControl(process, userId, start, profilerInfo, profileType)) {
            err.println("PROFILE FAILED on process " + process);
            return -1;
        }
        return 0;
    }

    @NeverCompile
    int runCompact(PrintWriter pw) throws RemoteException {
        ProcessRecord app;
        String op = getNextArgRequired();
        boolean isFullCompact = op.equals("full");
        boolean isSomeCompact = op.equals("some");
        if (isFullCompact || isSomeCompact) {
            app = getProcessFromShell();
            if (app == null) {
                getErrPrintWriter().println("Error: could not find process");
                return -1;
            }
            pw.println("Process record found pid: " + app.mPid);
            if (isFullCompact) {
                pw.println("Executing full compaction for " + app.mPid);
                synchronized (mInternal.mProcLock) {
                    mInternal.mOomAdjuster.mCachedAppOptimizer.compactApp(app,
                            CachedAppOptimizer.CompactProfile.FULL,
                            CachedAppOptimizer.CompactSource.SHELL, true);
                }
                pw.println("Finished full compaction for " + app.mPid);
            } else if (isSomeCompact) {
                pw.println("Executing some compaction for " + app.mPid);
                synchronized (mInternal.mProcLock) {
                    mInternal.mOomAdjuster.mCachedAppOptimizer.compactApp(app,
                            CachedAppOptimizer.CompactProfile.SOME,
                            CachedAppOptimizer.CompactSource.SHELL, true);
                }
                pw.println("Finished some compaction for " + app.mPid);
            }
        } else if (op.equals("system")) {
            pw.println("Executing system compaction");
            synchronized (mInternal.mProcLock) {
                mInternal.mOomAdjuster.mCachedAppOptimizer.compactAllSystem();
            }
            pw.println("Finished system compaction");
        } else if (op.equals("native")) {
            op = getNextArgRequired();
            isFullCompact = op.equals("full");
            isSomeCompact = op.equals("some");
            int pid;
            String pidStr = getNextArgRequired();
            try {
                pid = Integer.parseInt(pidStr);
            } catch (Exception e) {
                getErrPrintWriter().println("Error: failed to parse '" + pidStr + "' as a PID");
                return -1;
            }
            if (isFullCompact) {
                mInternal.mOomAdjuster.mCachedAppOptimizer.compactNative(
                        CachedAppOptimizer.CompactProfile.FULL, pid);
            } else if (isSomeCompact) {
                mInternal.mOomAdjuster.mCachedAppOptimizer.compactNative(
                        CachedAppOptimizer.CompactProfile.SOME, pid);
            } else {
                getErrPrintWriter().println("Error: unknown compaction type '" + op + "'");
                return -1;
            }
        } else {
            getErrPrintWriter().println("Error: unknown compact command '" + op + "'");
            return -1;
        }

        return 0;
    }

    @NeverCompile
    int runFreeze(PrintWriter pw, boolean freeze) throws RemoteException {
        String freezerOpt = getNextOption();
        boolean isSticky = false;

        if (freezerOpt != null) {
            isSticky = freezerOpt.equals("--sticky");
        }
        ProcessRecord proc = getProcessFromShell();
        if (proc == null) {
            return -1;
        }
        pw.print(freeze ? "Freezing" : "Unfreezing");
        pw.print(" process " + proc.processName);
        pw.println(" (" + proc.mPid + ") sticky=" + isSticky);
        synchronized (mInternal) {
            synchronized (mInternal.mProcLock) {
                proc.mOptRecord.setFreezeSticky(isSticky);
                if (freeze) {
                    mInternal.mOomAdjuster.mCachedAppOptimizer.forceFreezeAppAsyncLSP(proc);
                } else {
                    mInternal.mOomAdjuster.mCachedAppOptimizer.unfreezeAppLSP(proc, 0, true);
                }
            }
        }
        return 0;
    }

    /**
     * Parses from the shell the pid or process name and provides the corresponding
     * {@link ProcessRecord}.
     * Example usage: {@code <processname>} or {@code <pid>}
     * @return process record of process, null if none or more than one found.
     * @throws RemoteException
     */
    @NeverCompile
    ProcessRecord getProcessFromShell() throws RemoteException {
        ProcessRecord proc = null;
        String process = getNextArgRequired();
        try {
            int pid = Integer.parseInt(process);
            synchronized (mInternal.mPidsSelfLocked) {
                proc = mInternal.mPidsSelfLocked.get(pid);
            }
        } catch (NumberFormatException e) {
            // Fallback to process name if it's not a valid pid
        }

        if (proc == null) {
            synchronized (mInternal.mProcLock) {
                ArrayMap<String, SparseArray<ProcessRecord>> all =
                        mInternal.mProcessList.getProcessNamesLOSP().getMap();
                SparseArray<ProcessRecord> procs = all.get(process);
                if (procs == null || procs.size() == 0) {
                    getErrPrintWriter().println("Error: could not find process");
                    return null;
                } else if (procs.size() > 1) {
                    getErrPrintWriter().println("Error: more than one processes found");
                    return null;
                }
                proc = procs.valueAt(0);
            }
        }

        return proc;
    }

    int runDumpHeap(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        boolean managed = true;
        boolean mallocInfo = false;
        String dumpBitmaps = null;
        int userId = UserHandle.USER_CURRENT;
        boolean runGc = false;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId == UserHandle.USER_ALL) {
                    err.println("Error: Can't dump heap with user 'all'");
                    return -1;
                }
            } else if (opt.equals("-n")) {
                managed = false;
            } else if (opt.equals("-g")) {
                runGc = true;
            } else if (opt.equals("-m")) {
                managed = false;
                mallocInfo = true;
            } else if (opt.equals("-b")) {
                dumpBitmaps = getNextArg();
                if (dumpBitmaps == null) {
                    dumpBitmaps = "png"; // default to PNG in dumping bitmaps
                }
            } else {
                err.println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String process = getNextArgRequired();
        String heapFile = getNextArg();
        if (heapFile == null) {
            LocalDateTime localDateTime = LocalDateTime.now(Clock.systemDefaultZone());
            String logNameTimeString = LOG_NAME_TIME_FORMATTER.format(localDateTime);
            heapFile = "/data/local/tmp/heapdump-" + logNameTimeString + ".prof";
        }

        // Writes an error message to stderr on failure
        ParcelFileDescriptor fd = openFileForSystem(heapFile, "w");
        if (fd == null) {
            return -1;
        }

        pw.println("File: " + heapFile);
        pw.flush();

        final CountDownLatch latch = new CountDownLatch(1);

        final RemoteCallback finishCallback = new RemoteCallback(new OnResultListener() {
            @Override
            public void onResult(Bundle result) {
                latch.countDown();
            }
        }, null);

        if (!mInterface.dumpHeap(process, userId, managed, mallocInfo, runGc, dumpBitmaps,
                heapFile, fd, finishCallback)) {
            err.println("HEAP DUMP FAILED on process " + process);
            return -1;
        }
        pw.println("Waiting for dump to finish...");
        pw.flush();
        try {
            latch.await();
        } catch (InterruptedException e) {
            err.println("Caught InterruptedException");
        }

        return 0;
    }

    int runSetDebugApp(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        boolean persistent = false;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("-w")) {
                wait = true;
            } else if (opt.equals("--persistent")) {
                persistent = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        String pkg = getNextArgRequired();
        mInterface.setDebugApp(pkg, wait, persistent);
        return 0;
    }

    int runSetAgentApp(PrintWriter pw) throws RemoteException {
        String pkg = getNextArgRequired();
        String agent = getNextArg();
        mInterface.setAgentApp(pkg, agent);
        return 0;
    }

    int runClearDebugApp(PrintWriter pw) throws RemoteException {
        mInterface.setDebugApp(null, false, true);
        return 0;
    }

    int runSetWatchHeap(PrintWriter pw) throws RemoteException {
        String proc = getNextArgRequired();
        String limit = getNextArgRequired();
        mInterface.setDumpHeapDebugLimit(proc, 0, Long.parseLong(limit), null);
        return 0;
    }

    int runClearWatchHeap(PrintWriter pw) throws RemoteException {
        String proc = getNextArgRequired();
        mInterface.setDumpHeapDebugLimit(proc, 0, -1, null);
        return 0;
    }

    int runClearStartInfo(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                "runClearStartInfo()");
        String opt;
        int userId = UserHandle.USER_CURRENT;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (userId == UserHandle.USER_CURRENT) {
            UserInfo user = mInterface.getCurrentUser();
            if (user == null) {
                return -1;
            }
            userId = user.id;
        }
        mInternal.mProcessList.getAppStartInfoTracker()
                .clearHistoryProcessStartInfo(getNextArg(), userId);
        return 0;
    }

    int runStartInfoDetailedMonitoring(PrintWriter pw) throws RemoteException {
        String opt;
        int userId = UserHandle.USER_CURRENT;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (userId == UserHandle.USER_CURRENT) {
            UserInfo user = mInterface.getCurrentUser();
            if (user == null) {
                return -1;
            }
            userId = user.id;
        }
        mInternal.mProcessList.getAppStartInfoTracker()
                .configureDetailedMonitoring(pw, getNextArg(), userId);
        return 0;
    }

    int runClearExitInfo(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                "runClearExitInfo()");
        String opt;
        int userId = UserHandle.USER_CURRENT;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (userId == UserHandle.USER_CURRENT) {
            UserInfo user = mInterface.getCurrentUser();
            if (user == null) {
                return -1;
            }
            userId = user.id;
        }
        mInternal.mProcessList.mAppExitInfoTracker.clearHistoryProcessExitInfo(getNextArg(),
                userId);
        return 0;
    }

    int runBugReport(PrintWriter pw) throws RemoteException {
        String opt;
        boolean fullBugreport = true;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--progress")) {
                fullBugreport = false;
                mInterface.requestInteractiveBugReport();
            } else if (opt.equals("--telephony")) {
                fullBugreport = false;
                // no title and description specified
                mInterface.requestTelephonyBugReport("" /* no title */, "" /* no descriptions */);
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (fullBugreport) {
            mInterface.requestFullBugReport();
        }
        pw.println("Your lovely bug report is being created; please be patient.");
        return 0;
    }

    int runForceStop(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.forceStopPackage(getNextArgRequired(), userId);
        return 0;
    }

    int runStopApp(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.stopAppForUser(getNextArgRequired(), userId);
        return 0;
    }

    int runClearRecentApps(PrintWriter pw) throws RemoteException {
        mTaskInterface.removeAllVisibleRecentTasks();
        return 0;
    }

    int runFgsNotificationRateLimit(PrintWriter pw) throws RemoteException {
        final String toggleValue = getNextArgRequired();
        final boolean enable;
        switch (toggleValue) {
            case "enable":
                enable = true;
                break;
            case "disable":
                enable = false;
                break;
            default:
                throw new IllegalArgumentException(
                        "Argument must be either 'enable' or 'disable'");
        }
        mInterface.enableFgsNotificationRateLimit(enable);
        return 0;
    }

    int runCrash(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        int pid = -1;
        String packageName = null;
        final String arg = getNextArgRequired();
        // The argument is either a pid or a package name
        try {
            pid = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            packageName = arg;
        }

        int[] userIds = (userId == UserHandle.USER_ALL) ? mInternal.mUserController.getUserIds()
                : new int[]{userId};
        for (int id : userIds) {
            if (mInternal.mUserController.hasUserRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES, id)) {
                getOutPrintWriter().println(
                        "Shell does not have permission to crash packages for user " + id);
                continue;
            }
            mInterface.crashApplicationWithType(-1, pid, packageName, id, "shell-induced crash",
                    false, CrashedByAdbException.TYPE_ID);
        }
        return 0;
    }

    int runKill(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.killBackgroundProcesses(getNextArgRequired(), userId);
        return 0;
    }

    int runKillAll(PrintWriter pw) throws RemoteException {
        mInterface.killAllBackgroundProcesses();
        return 0;
    }

    int runMakeIdle(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.makePackageIdle(getNextArgRequired(), userId);
        return 0;
    }

    int runSetDeterministicUidIdle(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        boolean deterministic = Boolean.parseBoolean(getNextArgRequired());
        mInterface.setDeterministicUidIdle(deterministic);
        return 0;
    }

    static final class MyActivityController extends IActivityController.Stub {
        final IActivityManager mInterface;
        final PrintWriter mPw;
        final InputStream mInput;
        final String mGdbPort;
        final boolean mMonkey;
        final boolean mSimpleMode;
        final String mTarget;
        final boolean mAlwaysContinue;
        final boolean mAlwaysKill;

        static final int STATE_NORMAL = 0;
        static final int STATE_CRASHED = 1;
        static final int STATE_EARLY_ANR = 2;
        static final int STATE_ANR = 3;

        int mState;

        static final int RESULT_DEFAULT = 0;

        static final int RESULT_CRASH_DIALOG = 0;
        static final int RESULT_CRASH_KILL = 1;

        static final int RESULT_EARLY_ANR_CONTINUE = 0;
        static final int RESULT_EARLY_ANR_KILL = 1;

        static final int RESULT_ANR_DIALOG = 0;
        static final int RESULT_ANR_KILL = 1;
        static final int RESULT_ANR_WAIT = 2;

        int mResult;

        Process mGdbProcess;
        Thread mGdbThread;
        boolean mGotGdbPrint;

        MyActivityController(IActivityManager iam, PrintWriter pw, InputStream input,
                String gdbPort, boolean monkey, boolean simpleMode, String target,
                boolean alwaysContinue, boolean alwaysKill) {
            mInterface = iam;
            mPw = pw;
            mInput = input;
            mGdbPort = gdbPort;
            mMonkey = monkey;
            mSimpleMode = simpleMode;
            mTarget = target;
            mAlwaysContinue = alwaysContinue;
            mAlwaysKill = alwaysKill;
        }

        private boolean shouldHandlePackageOrProcess(String packageOrProcess) {
            if (mTarget == null) {
                return true; // Always handle all packages / processes.
            }
            return mTarget.equals(packageOrProcess);
        }

        @Override
        public boolean activityResuming(String pkg) {
            if (!shouldHandlePackageOrProcess(pkg)) {
                return true;
            }
            synchronized (this) {
                mPw.println("** Activity resuming: " + pkg);
                mPw.flush();
            }
            return true;
        }

        @Override
        public boolean activityStarting(Intent intent, String pkg) {
            if (!shouldHandlePackageOrProcess(pkg)) {
                return true;
            }
            synchronized (this) {
                mPw.println("** Activity starting: " + pkg);
                mPw.flush();
            }
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) {
            if (!shouldHandlePackageOrProcess(processName)) {
                return true; // Don't kill
            }
            synchronized (this) {
                if (mSimpleMode) {
                    mPw.println("** PROCESS CRASHED: " + processName);
                } else {
                    mPw.println("** ERROR: PROCESS CRASHED");
                    mPw.println("processName: " + processName);
                    mPw.println("processPid: " + pid);
                    mPw.println("shortMsg: " + shortMsg);
                    mPw.println("longMsg: " + longMsg);
                    mPw.println("timeMillis: " + timeMillis);
                    mPw.println("uptime: " + SystemClock.uptimeMillis());
                    mPw.println("stack:");
                    mPw.print(stackTrace);
                    mPw.println("#");
                }
                mPw.flush();
                if (mAlwaysContinue) {
                    return true;
                }
                if (mAlwaysKill) {
                    return false;
                }
                int result = waitControllerLocked(pid, STATE_CRASHED);
                return result == RESULT_CRASH_KILL ? false : true;
            }
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            if (!shouldHandlePackageOrProcess(processName)) {
                return 0; // Continue
            }
            synchronized (this) {
                if (mSimpleMode) {
                    mPw.println("** EARLY PROCESS NOT RESPONDING: " + processName);
                } else {
                    mPw.println("** ERROR: EARLY PROCESS NOT RESPONDING");
                    mPw.println("processName: " + processName);
                    mPw.println("processPid: " + pid);
                    mPw.println("annotation: " + annotation);
                    mPw.println("uptime: " + SystemClock.uptimeMillis());
                }
                mPw.flush();
                if (mAlwaysContinue) {
                    return 0;
                }
                if (mAlwaysKill) {
                    return -1;
                }
                int result = waitControllerLocked(pid, STATE_EARLY_ANR);
                if (result == RESULT_EARLY_ANR_KILL) return -1;
                return 0;
            }
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats) {
            if (!shouldHandlePackageOrProcess(processName)) {
                return 0; // Default == show dialog
            }
            synchronized (this) {
                if (mSimpleMode) {
                    mPw.println("** PROCESS NOT RESPONDING: " + processName);
                } else {
                    mPw.println("** ERROR: PROCESS NOT RESPONDING");
                    mPw.println("processName: " + processName);
                    mPw.println("processPid: " + pid);
                    mPw.println("uptime: " + SystemClock.uptimeMillis());
                    mPw.println("processStats:");
                    mPw.print(processStats);
                    mPw.println("#");
                }
                mPw.flush();
                if (mAlwaysContinue) {
                    return 0;
                }
                if (mAlwaysKill) {
                    return -1;
                }
                int result = waitControllerLocked(pid, STATE_ANR);
                if (result == RESULT_ANR_KILL) return -1;
                if (result == RESULT_ANR_WAIT) return 1;
                return 0;
            }
        }

        @Override
        public int systemNotResponding(String message) {
            if (mTarget != null) {
                return -1; // If any target is set, just return.
            }
            synchronized (this) {
                mPw.println("** ERROR: PROCESS NOT RESPONDING");
                if (!mSimpleMode) {
                    mPw.println("message: " + message);
                    mPw.println("#");
                    mPw.println("Allowing system to die.");
                }
                mPw.flush();
                return -1;
            }
        }

        void killGdbLocked() {
            mGotGdbPrint = false;
            if (mGdbProcess != null) {
                mPw.println("Stopping gdbserver");
                mPw.flush();
                mGdbProcess.destroy();
                mGdbProcess = null;
            }
            if (mGdbThread != null) {
                mGdbThread.interrupt();
                mGdbThread = null;
            }
        }

        int waitControllerLocked(int pid, int state) {
            if (mGdbPort != null) {
                killGdbLocked();

                try {
                    mPw.println("Starting gdbserver on port " + mGdbPort);
                    mPw.println("Do the following:");
                    mPw.println("  adb forward tcp:" + mGdbPort + " tcp:" + mGdbPort);
                    mPw.println("  gdbclient app_process :" + mGdbPort);
                    mPw.flush();

                    mGdbProcess = Runtime.getRuntime().exec(new String[] {
                            "gdbserver", ":" + mGdbPort, "--attach", Integer.toString(pid)
                    });
                    final InputStreamReader converter = new InputStreamReader(
                            mGdbProcess.getInputStream());
                    mGdbThread = new Thread() {
                        @Override
                        public void run() {
                            BufferedReader in = new BufferedReader(converter);
                            String line;
                            int count = 0;
                            while (true) {
                                synchronized (MyActivityController.this) {
                                    if (mGdbThread == null) {
                                        return;
                                    }
                                    if (count == 2) {
                                        mGotGdbPrint = true;
                                        MyActivityController.this.notifyAll();
                                    }
                                }
                                try {
                                    line = in.readLine();
                                    if (line == null) {
                                        return;
                                    }
                                    mPw.println("GDB: " + line);
                                    mPw.flush();
                                    count++;
                                } catch (IOException e) {
                                    return;
                                }
                            }
                        }
                    };
                    mGdbThread.start();

                    // Stupid waiting for .5s.  Doesn't matter if we end early.
                    try {
                        this.wait(500);
                    } catch (InterruptedException e) {
                    }

                } catch (IOException e) {
                    mPw.println("Failure starting gdbserver: " + e);
                    mPw.flush();
                    killGdbLocked();
                }
            }
            mState = state;
            mPw.println("");
            printMessageForState();
            mPw.flush();

            while (mState != STATE_NORMAL) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            killGdbLocked();

            return mResult;
        }

        void resumeController(int result) {
            synchronized (this) {
                mState = STATE_NORMAL;
                mResult = result;
                notifyAll();
            }
        }

        void printMessageForState() {
            if ((mAlwaysContinue || mAlwaysKill) && mSimpleMode) {
                return; // In the simplest mode, we don't need to show anything.
            }
            switch (mState) {
                case STATE_NORMAL:
                    mPw.println("Monitoring activity manager...  available commands:");
                    break;
                case STATE_CRASHED:
                    mPw.println("Waiting after crash...  available commands:");
                    mPw.println("(c)ontinue: show crash dialog");
                    mPw.println("(k)ill: immediately kill app");
                    break;
                case STATE_EARLY_ANR:
                    mPw.println("Waiting after early ANR...  available commands:");
                    mPw.println("(c)ontinue: standard ANR processing");
                    mPw.println("(k)ill: immediately kill app");
                    break;
                case STATE_ANR:
                    mPw.println("Waiting after ANR...  available commands:");
                    mPw.println("(c)ontinue: show ANR dialog");
                    mPw.println("(k)ill: immediately kill app");
                    mPw.println("(w)ait: wait some more");
                    break;
            }
            mPw.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            try {
                printMessageForState();
                mPw.flush();

                mInterface.setActivityController(this, mMonkey);
                mState = STATE_NORMAL;

                InputStreamReader converter = new InputStreamReader(mInput);
                BufferedReader in = new BufferedReader(converter);
                String line;

                while ((line = in.readLine()) != null) {
                    boolean addNewline = true;
                    if (line.length() <= 0) {
                        addNewline = false;
                    } else if ("q".equals(line) || "quit".equals(line)) {
                        resumeController(RESULT_DEFAULT);
                        break;
                    } else if (mState == STATE_CRASHED) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_CRASH_DIALOG);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_CRASH_KILL);
                        } else {
                            mPw.println("Invalid command: " + line);
                        }
                    } else if (mState == STATE_ANR) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_ANR_DIALOG);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_ANR_KILL);
                        } else if ("w".equals(line) || "wait".equals(line)) {
                            resumeController(RESULT_ANR_WAIT);
                        } else {
                            mPw.println("Invalid command: " + line);
                        }
                    } else if (mState == STATE_EARLY_ANR) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_EARLY_ANR_CONTINUE);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_EARLY_ANR_KILL);
                        } else {
                            mPw.println("Invalid command: " + line);
                        }
                    } else {
                        mPw.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            mPw.println("");
                        }
                        printMessageForState();
                        mPw.flush();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace(mPw);
                mPw.flush();
            } finally {
                mInterface.setActivityController(null, mMonkey);
            }
        }
    }

    int runMonitor(PrintWriter pw) throws RemoteException {
        String opt;
        String gdbPort = null;
        boolean monkey = false;
        boolean simpleMode = false;
        boolean alwaysContinue = false;
        boolean alwaysKill = false;
        String target = null;

        while ((opt=getNextOption()) != null) {
            if (opt.equals("--gdb")) {
                gdbPort = getNextArgRequired();
            } else if (opt.equals("-p")) {
                target = getNextArgRequired();
            } else if (opt.equals("-m")) {
                monkey = true;
            } else if (opt.equals("-s")) {
                simpleMode = true;
            } else if (opt.equals("-c")) {
                alwaysContinue = true;
            } else if (opt.equals("-k")) {
                alwaysKill = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (alwaysContinue && alwaysKill) {
            getErrPrintWriter().println("Error: -k and -c options can't be used together.");
            return -1;
        }

        MyActivityController controller = new MyActivityController(mInterface, pw,
                getRawInputStream(), gdbPort, monkey, simpleMode, target, alwaysContinue,
                alwaysKill);
        controller.run();
        return 0;
    }

    static final class MyUidObserver extends UidObserver
            implements ActivityManagerService.OomAdjObserver {
        final IActivityManager mInterface;
        final ActivityManagerService mInternal;
        final PrintWriter mPw;
        final InputStream mInput;
        final int mUid;

        final int mMask;

        static final int STATE_NORMAL = 0;

        int mState;

        MyUidObserver(ActivityManagerService service, PrintWriter pw, InputStream input, int uid,
                int mask) {
            mInterface = service;
            mInternal = service;
            mPw = pw;
            mInput = input;
            mUid = uid;
            mMask = mask;
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            synchronized (this) {
                final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                try {
                    mPw.print(uid);
                    mPw.print(" procstate ");
                    mPw.print(ProcessList.makeProcStateString(procState));
                    mPw.print(" seq ");
                    mPw.print(procStateSeq);
                    mPw.print(" capability ");
                    mPw.println(capability & mMask);
                    mPw.flush();
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            synchronized (this) {
                final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                try {
                    mPw.print(uid);
                    mPw.print(" gone");
                    if (disabled) {
                        mPw.print(" disabled");
                    }
                    mPw.println();
                    mPw.flush();
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }
        }

        @Override
        public void onUidActive(int uid) {
            synchronized (this) {
                final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                try {
                    mPw.print(uid);
                    mPw.println(" active");
                    mPw.flush();
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
            synchronized (this) {
                final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                try {
                    mPw.print(uid);
                    mPw.print(" idle");
                    if (disabled) {
                        mPw.print(" disabled");
                    }
                    mPw.println();
                    mPw.flush();
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
            synchronized (this) {
                final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                try {
                    mPw.print(uid);
                    mPw.println(cached ? " cached" : " uncached");
                    mPw.flush();
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }
        }

        @Override
        public void onOomAdjMessage(String msg) {
            synchronized (this) {
                final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                try {
                    mPw.print("# ");
                    mPw.println(msg);
                    mPw.flush();
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }
        }

        void printMessageForState() {
            switch (mState) {
                case STATE_NORMAL:
                    mPw.println("Watching uid states...  available commands:");
                    break;
            }
            mPw.println("(q)uit: finish watching");
        }

        void run() throws RemoteException {
            try {
                printMessageForState();
                mPw.flush();

                mInterface.registerUidObserver(this, ActivityManager.UID_OBSERVER_ACTIVE
                        | ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_PROCSTATE
                        | ActivityManager.UID_OBSERVER_IDLE | ActivityManager.UID_OBSERVER_CACHED,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
                if (mUid >= 0) {
                    mInternal.setOomAdjObserver(mUid, this);
                }
                mState = STATE_NORMAL;

                InputStreamReader converter = new InputStreamReader(mInput);
                BufferedReader in = new BufferedReader(converter);
                String line;

                while ((line = in.readLine()) != null) {
                    boolean addNewline = true;
                    if (line.length() <= 0) {
                        addNewline = false;
                    } else if ("q".equals(line) || "quit".equals(line)) {
                        break;
                    } else {
                        mPw.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            mPw.println("");
                        }
                        printMessageForState();
                        mPw.flush();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace(mPw);
                mPw.flush();
            } finally {
                if (mUid >= 0) {
                    mInternal.clearOomAdjObserver();
                }
                mInterface.unregisterUidObserver(this);
            }
        }
    }

    int runWatchUids(PrintWriter pw) throws RemoteException {
        String opt;
        int uid = -1;

        // Because a lot of CTS won't ignore capabilities newly added, we report
        // only the following capabilities -- the ones we had on Android T -- by default.
        int mask = PROCESS_CAPABILITY_FOREGROUND_LOCATION
                | PROCESS_CAPABILITY_FOREGROUND_CAMERA
                | PROCESS_CAPABILITY_FOREGROUND_MICROPHONE
                | PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK;

        while ((opt=getNextOption()) != null) {
            if (opt.equals("--oom")) {
                uid = Integer.parseInt(getNextArgRequired());
            } else if (opt.equals("--mask")) {
                mask = Integer.parseInt(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;

            }
        }

        MyUidObserver controller = new MyUidObserver(mInternal, pw, getRawInputStream(), uid, mask);
        controller.run();
        return 0;
    }

    int runHang(PrintWriter pw) throws RemoteException {
        String opt;
        boolean allowRestart = false;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--allow-restart")) {
                allowRestart = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        pw.println("Hanging the system...");
        pw.flush();
        try {
            mInterface.hang(getShellCallback().getShellCallbackBinder(), allowRestart);
        } catch (NullPointerException e) {
            pw.println("Hanging failed, since caller " + Binder.getCallingPid() +
                    " did not provide a ShellCallback!");
            pw.flush();
            return 1;
        }
        return 0;
    }

    int runRestart(PrintWriter pw) throws RemoteException {
        String opt;
        while ((opt=getNextOption()) != null) {
            getErrPrintWriter().println("Error: Unknown option: " + opt);
            return -1;
        }

        pw.println("Restart the system...");
        pw.flush();
        mInterface.restart();
        return 0;
    }

    int runIdleMaintenance(PrintWriter pw) throws RemoteException {
        String opt;
        while ((opt=getNextOption()) != null) {
            getErrPrintWriter().println("Error: Unknown option: " + opt);
            return -1;
        }

        pw.println("Performing idle maintenance...");
        mInterface.sendIdleJobTrigger();
        mInternal.performIdleMaintenance();
        return 0;
    }

    int runScreenCompat(PrintWriter pw) throws RemoteException {
        String mode = getNextArgRequired();
        boolean enabled;
        if ("on".equals(mode)) {
            enabled = true;
        } else if ("off".equals(mode)) {
            enabled = false;
        } else {
            getErrPrintWriter().println("Error: enabled mode must be 'on' or 'off' at " + mode);
            return -1;
        }

        String packageName = getNextArgRequired();
        do {
            try {
                mInterface.setPackageScreenCompatMode(packageName, enabled
                        ? ActivityManager.COMPAT_MODE_ENABLED
                        : ActivityManager.COMPAT_MODE_DISABLED);
            } catch (RemoteException e) {
            }
            packageName = getNextArg();
        } while (packageName != null);
        return 0;
    }

    int runPackageImportance(PrintWriter pw) throws RemoteException {
        String packageName = getNextArgRequired();
        int procState = mInterface.getPackageProcessState(packageName, "com.android.shell");
        pw.println(ActivityManager.RunningAppProcessInfo.procStateToImportance(procState));
        return 0;
    }

    int runToUri(PrintWriter pw, int flags) throws RemoteException {
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        pw.println(intent.toUri(flags));
        return 0;
    }

    private boolean switchUserAndWaitForComplete(int userId) throws RemoteException {
        UserInfo currentUser = mInterface.getCurrentUser();
        if (currentUser != null && userId == currentUser.id) {
            // Already switched to the correct user, exit early.
            return true;
        }

        // Register switch observer.
        final CountDownLatch switchLatch = new CountDownLatch(1);
        final IUserSwitchObserver userSwitchObserver = new UserSwitchObserver() {
            @Override
            public void onUserSwitchComplete(int newUserId) {
                if (userId == newUserId) {
                    switchLatch.countDown();
                }
            }
        };
        try {
            mInterface.registerUserSwitchObserver(userSwitchObserver,
                    ActivityManagerShellCommand.class.getName());

            // Switch.
            boolean switched = mInterface.switchUser(userId);
            if (!switched) {
                // Switching failed, don't wait for the user switch observer.
                return false;
            }

            // Wait.
            try {
                switched = switchLatch.await(USER_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                getErrPrintWriter().println("Error: Thread interrupted unexpectedly.");
            }

            return switched;
        } finally {
            mInterface.unregisterUserSwitchObserver(userSwitchObserver);
        }
    }

    int runSwitchUser(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("-w".equals(opt)) {
                wait = true;
            } else {
                getErrPrintWriter().println("Error: unknown option: " + opt);
                return -1;
            }
        }

        int userId = Integer.parseInt(getNextArgRequired());

        UserManager userManager = mInternal.mContext.getSystemService(UserManager.class);
        final int userSwitchable = userManager.getUserSwitchability(UserHandle.of(userId));
        if (userSwitchable != UserManager.SWITCHABILITY_STATUS_OK) {
            getErrPrintWriter().println("Error: UserSwitchabilityResult=" + userSwitchable);
            return -1;
        }

        boolean switched;
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "shell_runSwitchUser");
        try {
            if (wait) {
                switched = switchUserAndWaitForComplete(userId);
            } else {
                switched = mInterface.switchUser(userId);
            }
            if (switched) {
                return 0;
            } else {
                pw.printf("Error: Failed to switch to user %d\n", userId);
                return 1;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    int runGetCurrentUser(PrintWriter pw) throws RemoteException {
        int userId = mInterface.getCurrentUserId();
        if (userId == UserHandle.USER_NULL) {
            throw new IllegalStateException("Current user not set");
        }
        pw.println(userId);
        return 0;
    }

    int runStartUser(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        String opt;
        int displayId = Display.INVALID_DISPLAY;
        while ((opt = getNextOption()) != null) {
            switch(opt) {
                case "-w":
                    wait = true;
                    break;
                case "--display":
                    displayId = getDisplayIdFromNextArg();
                    break;
                default:
                    getErrPrintWriter().println("Error: unknown option: " + opt);
                    return -1;
            }
        }
        final int userId = Integer.parseInt(getNextArgRequired());
        final ProgressWaiter waiter = wait ? new ProgressWaiter(userId) : null;

        // For backwards compatibility, if the user is a profile, we need to define whether it
        // should be started visible (when its parent is the current user) or not (when it isn't)
        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final ActivityManagerInternal ami = LocalServices.getService(ActivityManagerInternal.class);
        final int parentUserId = umi.getProfileParentId(userId);
        final int currentUserId = ami.getCurrentUserId();
        final boolean isProfile = parentUserId != userId;
        final boolean isVisibleProfile = isProfile && parentUserId == currentUserId;
        Slogf.d(TAG, "runStartUser(): userId=%d, parentUserId=%d, currentUserId=%d, isProfile=%b, "
                + "isVisibleProfile=%b, display=%d, waiter=%s", userId, parentUserId, currentUserId,
                isProfile, isVisibleProfile, displayId, waiter);

        boolean success;
        String displaySuffix = "";
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "shell_runStartUser" + userId);
        try {
            if (isVisibleProfile) {
                Slogf.d(TAG, "calling startProfileWithListener(%d, %s)", userId, waiter);
                // startProfileWithListener() will start the profile visible (as long its parent is
                // the current user), while startUserInBackgroundWithListener() will always start
                // the user (or profile) invisible
                success = mInterface.startProfileWithListener(userId, waiter);
            } else if (displayId == Display.INVALID_DISPLAY) {
                Slogf.d(TAG, "calling startUserInBackgroundWithListener(%d)", userId);
                success = mInterface.startUserInBackgroundWithListener(userId, waiter);
            } else {
                if (!UserManager.isVisibleBackgroundUsersEnabled()) {
                    pw.println("Not supported");
                    return -1;
                }
                Slogf.d(TAG, "calling startUserInBackgroundVisibleOnDisplay(%d, %d, %s)", userId,
                        displayId, waiter);
                success = mInterface.startUserInBackgroundVisibleOnDisplay(userId, displayId,
                        waiter);
                displaySuffix = " on display " + displayId;
            }
            if (wait && success) {
                Slogf.d(TAG, "waiting %d ms", USER_OPERATION_TIMEOUT_MS);
                success = waiter.waitForFinish(USER_OPERATION_TIMEOUT_MS);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }

        if (success) {
            pw.println("Success: user started" + displaySuffix);
        } else {
            getErrPrintWriter().println("Error: could not start user" + displaySuffix);
        }
        return 0;
    }

    int runUnlockUser(PrintWriter pw) throws RemoteException {
        int userId = Integer.parseInt(getNextArgRequired());

        /*
         * Originally this command required two more parameters: the hardware
         * authentication token and secret needed to unlock the user.  However,
         * unlockUser() no longer takes a token parameter at all, and there
         * isn't really any way for callers of this shell command to get the
         * secret if one is needed (i.e., when the user has an LSKF), given that
         * the secret must be a cryptographic key derived from the user's
         * synthetic password.  I.e. the secret is *not* the LSKF here, but
         * rather an intermediate value several steps down the chain.
         *
         * As such, the only supported use for this command is unlocking a user
         * who doesn't have an LSKF, with empty or unspecified token and secret.
         *
         * To preserve previous behavior, an exclamation mark ("!") is also
         * accepted for these values; it means the same as an empty string.
         */
        String token = getNextArg();
        if (!TextUtils.isEmpty(token) && !"!".equals(token)) {
            getErrPrintWriter().println("Error: token parameter not supported");
            return -1;
        }
        String secret = getNextArg();
        if (!TextUtils.isEmpty(secret) && !"!".equals(secret)) {
            getErrPrintWriter().println("Error: secret parameter not supported");
            return -1;
        }

        boolean success = mInterface.unlockUser2(userId, null);
        if (success) {
            pw.println("Success: user unlocked");
        } else {
            // TODO(b/218389026): we can reach here even if the user's storage
            // was successfully unlocked.
            getErrPrintWriter().println("Error: could not unlock user");
        }
        return 0;
    }

    static final class StopUserCallback extends IStopUserCallback.Stub {
        private final @UserIdInt int mUserId;
        private boolean mFinished = false;

        private StopUserCallback(@UserIdInt int userId) {
            mUserId = userId;
        }

        public synchronized void waitForFinish() {
            try {
                while (!mFinished) wait();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            Slogf.d(TAG, "user %d finished stopping", mUserId);
        }

        @Override
        public synchronized void userStopped(int userId) {
            Slogf.d(TAG, "StopUserCallback: userStopped(%d)", userId);
            mFinished = true;
            notifyAll();
        }

        @Override
        public synchronized void userStopAborted(int userId) {
            Slogf.d(TAG, "StopUserCallback: userStopAborted(%d)", userId);
            mFinished = true;
            notifyAll();
        }

        @Override
        public String toString() {
            return "ProgressWaiter[userId=" + mUserId + ", finished=" + mFinished + "]";
        }
    }

    int runStopUser(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        boolean force = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("-w".equals(opt)) {
                wait = true;
            } else if ("-f".equals(opt)) {
                force = true;
            } else {
                getErrPrintWriter().println("Error: unknown option: " + opt);
                return -1;
            }
        }
        int userId = Integer.parseInt(getNextArgRequired());
        StopUserCallback callback = wait ? new StopUserCallback(userId) : null;

        Slogf.d(TAG, "Calling stopUser(%d, %b, %s)", userId, force, callback);
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "shell_runStopUser-" + userId + "-[stopUser]");
        try {
            int res = mInterface.stopUserExceptCertainProfiles(
                    userId, /* stopProfileRegardlessOfParent= */ force, callback);
            if (res != ActivityManager.USER_OP_SUCCESS) {
                String txt = "";
                switch (res) {
                    case ActivityManager.USER_OP_IS_CURRENT:
                        txt = " (Can't stop current user)";
                        break;
                    case ActivityManager.USER_OP_UNKNOWN_USER:
                        txt = " (Unknown user " + userId + ")";
                        break;
                    case ActivityManager.USER_OP_ERROR_IS_SYSTEM:
                        txt = " (System user cannot be stopped)";
                        break;
                    case ActivityManager.USER_OP_ERROR_RELATED_USERS_CANNOT_STOP:
                        txt = " (Can't stop user " + userId
                                + " - one of its related users can't be stopped)";
                        break;
                }
                getErrPrintWriter().println("Switch failed: " + res + txt);
                return -1;
            } else if (callback != null) {
                callback.waitForFinish();
            }
            return 0;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    int runIsUserStopped(PrintWriter pw) {
        int userId = UserHandle.parseUserArg(getNextArgRequired());
        boolean stopped = mInternal.isUserStopped(userId);
        pw.println(stopped);
        return 0;
    }

    int runGetStartedUserState(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.DUMP,
                "runGetStartedUserState()");
        final int userId = Integer.parseInt(getNextArgRequired());
        try {
            pw.println(mInternal.getStartedUserState(userId));
        } catch (NullPointerException e) {
            pw.println("User is not started: " + userId);
        }
        return 0;
    }

    int runTrackAssociations(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "runTrackAssociations()");
        synchronized (mInternal) {
            if (!mInternal.mTrackingAssociations) {
                mInternal.mTrackingAssociations = true;
                pw.println("Association tracking started.");
            } else {
                pw.println("Association tracking already enabled.");
            }
        }
        return 0;
    }

    int runUntrackAssociations(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "runUntrackAssociations()");
        synchronized (mInternal) {
            if (mInternal.mTrackingAssociations) {
                mInternal.mTrackingAssociations = false;
                mInternal.mAssociations.clear();
                pw.println("Association tracking stopped.");
            } else {
                pw.println("Association tracking not running.");
            }
        }
        return 0;
    }

    int getUidState(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.DUMP,
                "getUidState()");
        int state = mInternal.getUidState(Integer.parseInt(getNextArgRequired()));
        pw.print(state);
        pw.print(" (");
        pw.printf(DebugUtils.valueToString(ActivityManager.class, "PROCESS_STATE_", state));
        pw.println(")");
        return 0;
    }

    private List<Configuration> getRecentConfigurations(int days) {
        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        final long now = System.currentTimeMillis();
        final long nDaysAgo = now - (days * 24 * 60 * 60 * 1000);
        try {
            @SuppressWarnings("unchecked")
            ParceledListSlice<ConfigurationStats> configStatsSlice = usm.queryConfigurationStats(
                    UsageStatsManager.INTERVAL_BEST, nDaysAgo, now, "com.android.shell");
            if (configStatsSlice == null) {
                return Collections.emptyList();
            }

            final ArrayMap<Configuration, Integer> recentConfigs = new ArrayMap<>();
            final List<ConfigurationStats> configStatsList = configStatsSlice.getList();
            final int configStatsListSize = configStatsList.size();
            for (int i = 0; i < configStatsListSize; i++) {
                final ConfigurationStats stats = configStatsList.get(i);
                final int indexOfKey = recentConfigs.indexOfKey(stats.getConfiguration());
                if (indexOfKey < 0) {
                    recentConfigs.put(stats.getConfiguration(), stats.getActivationCount());
                } else {
                    recentConfigs.setValueAt(indexOfKey,
                            recentConfigs.valueAt(indexOfKey) + stats.getActivationCount());
                }
            }

            final Comparator<Configuration> comparator = new Comparator<Configuration>() {
                @Override
                public int compare(Configuration a, Configuration b) {
                    return recentConfigs.get(b).compareTo(recentConfigs.get(a));
                }
            };

            ArrayList<Configuration> configs = new ArrayList<>(recentConfigs.size());
            configs.addAll(recentConfigs.keySet());
            Collections.sort(configs, comparator);
            return configs;

        } catch (RemoteException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Adds all supported GL extensions for a provided EGLConfig to a set by creating an EGLContext
     * and EGLSurface and querying extensions.
     *
     * @param egl An EGL API object
     * @param display An EGLDisplay to create a context and surface with
     * @param config The EGLConfig to get the extensions for
     * @param surfaceSize eglCreatePbufferSurface generic parameters
     * @param contextAttribs eglCreateContext generic parameters
     * @param glExtensions A Set<String> to add GL extensions to
     */
    private static void addExtensionsForConfig(
            EGL10 egl,
            EGLDisplay display,
            EGLConfig config,
            int[] surfaceSize,
            int[] contextAttribs,
            Set<String> glExtensions) {
        // Create a context.
        EGLContext context =
                egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs);
        // No-op if we can't create a context.
        if (context == EGL10.EGL_NO_CONTEXT) {
            return;
        }

        // Create a surface.
        EGLSurface surface = egl.eglCreatePbufferSurface(display, config, surfaceSize);
        if (surface == EGL10.EGL_NO_SURFACE) {
            egl.eglDestroyContext(display, context);
            return;
        }

        // Update the current surface and context.
        egl.eglMakeCurrent(display, surface, surface, context);

        // Get the list of extensions.
        String extensionList = GLES10.glGetString(GLES10.GL_EXTENSIONS);
        if (!TextUtils.isEmpty(extensionList)) {
            // The list of extensions comes from the driver separated by spaces.
            // Split them apart and add them into a Set for deduping purposes.
            for (String extension : extensionList.split(" ")) {
                glExtensions.add(extension);
            }
        }

        // Tear down the context and surface for this config.
        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        egl.eglDestroySurface(display, surface);
        egl.eglDestroyContext(display, context);
    }


    Set<String> getGlExtensionsFromDriver() {
        Set<String> glExtensions = new HashSet<>();

        // Get the EGL implementation.
        EGL10 egl = (EGL10) EGLContext.getEGL();
        if (egl == null) {
            getErrPrintWriter().println("Warning: couldn't get EGL");
            return glExtensions;
        }

        // Get the default display and initialize it.
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        egl.eglInitialize(display, version);

        // Call getConfigs() in order to find out how many there are.
        int[] numConfigs = new int[1];
        if (!egl.eglGetConfigs(display, null, 0, numConfigs)) {
            getErrPrintWriter().println("Warning: couldn't get EGL config count");
            return glExtensions;
        }

        // Allocate space for all configs and ask again.
        EGLConfig[] configs = new EGLConfig[numConfigs[0]];
        if (!egl.eglGetConfigs(display, configs, numConfigs[0], numConfigs)) {
            getErrPrintWriter().println("Warning: couldn't get EGL configs");
            return glExtensions;
        }

        // Allocate surface size parameters outside of the main loop to cut down
        // on GC thrashing.  1x1 is enough since we are only using it to get at
        // the list of extensions.
        int[] surfaceSize =
                new int[] {
                        EGL10.EGL_WIDTH, 1,
                        EGL10.EGL_HEIGHT, 1,
                        EGL10.EGL_NONE
                };

        // For when we need to create a GLES2.0 context.
        final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        int[] gles2 = new int[] {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};

        // For getting return values from eglGetConfigAttrib
        int[] attrib = new int[1];

        for (int i = 0; i < numConfigs[0]; i++) {
            // Get caveat for this config in order to skip slow (i.e. software) configs.
            egl.eglGetConfigAttrib(display, configs[i], EGL10.EGL_CONFIG_CAVEAT, attrib);
            if (attrib[0] == EGL10.EGL_SLOW_CONFIG) {
                continue;
            }

            // If the config does not support pbuffers we cannot do an eglMakeCurrent
            // on it in addExtensionsForConfig(), so skip it here. Attempting to make
            // it current with a pbuffer will result in an EGL_BAD_MATCH error
            egl.eglGetConfigAttrib(display, configs[i], EGL10.EGL_SURFACE_TYPE, attrib);
            if ((attrib[0] & EGL10.EGL_PBUFFER_BIT) == 0) {
                continue;
            }

            final int EGL_OPENGL_ES_BIT = 0x0001;
            final int EGL_OPENGL_ES2_BIT = 0x0004;
            egl.eglGetConfigAttrib(display, configs[i], EGL10.EGL_RENDERABLE_TYPE, attrib);
            if ((attrib[0] & EGL_OPENGL_ES_BIT) != 0) {
                addExtensionsForConfig(egl, display, configs[i], surfaceSize, null, glExtensions);
            }
            if ((attrib[0] & EGL_OPENGL_ES2_BIT) != 0) {
                addExtensionsForConfig(egl, display, configs[i], surfaceSize, gles2, glExtensions);
            }
        }

        // Release all EGL resources.
        egl.eglTerminate(display);

        return glExtensions;
    }

    private void writeDeviceConfig(ProtoOutputStream protoOutputStream, long fieldId,
            PrintWriter pw, Configuration config, DisplayMetrics displayMetrics) {
        long token = -1;
        if (protoOutputStream != null) {
            token = protoOutputStream.start(fieldId);
            protoOutputStream.write(DeviceConfigurationProto.STABLE_SCREEN_WIDTH_PX,
                    displayMetrics.widthPixels);
            protoOutputStream.write(DeviceConfigurationProto.STABLE_SCREEN_HEIGHT_PX,
                    displayMetrics.heightPixels);
            protoOutputStream.write(DeviceConfigurationProto.STABLE_DENSITY_DPI,
                    DisplayMetrics.DENSITY_DEVICE_STABLE);
        }
        if (pw != null) {
            pw.print("stable-width-px: "); pw.println(displayMetrics.widthPixels);
            pw.print("stable-height-px: "); pw.println(displayMetrics.heightPixels);
            pw.print("stable-density-dpi: "); pw.println(DisplayMetrics.DENSITY_DEVICE_STABLE);
        }

        MemInfoReader memreader = new MemInfoReader();
        memreader.readMemInfo();
        KeyguardManager kgm = mInternal.mContext.getSystemService(KeyguardManager.class);
        if (protoOutputStream != null) {
            protoOutputStream.write(DeviceConfigurationProto.TOTAL_RAM, memreader.getTotalSize());
            protoOutputStream.write(DeviceConfigurationProto.LOW_RAM,
                    ActivityManager.isLowRamDeviceStatic());
            protoOutputStream.write(DeviceConfigurationProto.MAX_CORES,
                    Runtime.getRuntime().availableProcessors());
            protoOutputStream.write(DeviceConfigurationProto.HAS_SECURE_SCREEN_LOCK,
                    kgm.isDeviceSecure());
        }
        if (pw != null) {
            pw.print("total-ram: "); pw.println(memreader.getTotalSize());
            pw.print("low-ram: "); pw.println(ActivityManager.isLowRamDeviceStatic());
            pw.print("max-cores: "); pw.println(Runtime.getRuntime().availableProcessors());
            pw.print("has-secure-screen-lock: "); pw.println(kgm.isDeviceSecure());
        }

        ConfigurationInfo configInfo = null;
        try {
            configInfo = mTaskInterface.getDeviceConfigurationInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        if (configInfo.reqGlEsVersion != ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {
            if (protoOutputStream != null) {
                protoOutputStream.write(DeviceConfigurationProto.OPENGL_VERSION,
                        configInfo.reqGlEsVersion);
            }
            if (pw != null) {
                pw.print("opengl-version: 0x");
                pw.println(Integer.toHexString(configInfo.reqGlEsVersion));
            }
        }

        Set<String> glExtensionsSet = getGlExtensionsFromDriver();
        String[] glExtensions = new String[glExtensionsSet.size()];
        glExtensions = glExtensionsSet.toArray(glExtensions);
        Arrays.sort(glExtensions);
        for (int i = 0; i < glExtensions.length; i++) {
            if (protoOutputStream != null) {
                protoOutputStream.write(DeviceConfigurationProto.OPENGL_EXTENSIONS,
                        glExtensions[i]);
            }
            if (pw != null) {
                pw.print("opengl-extensions: "); pw.println(glExtensions[i]);
            }

        }

        PackageManager pm = mInternal.mContext.getPackageManager();
        List<SharedLibraryInfo> slibs = pm.getSharedLibraries(0);
        Collections.sort(slibs, Comparator.comparing(SharedLibraryInfo::getName));
        for (int i = 0; i < slibs.size(); i++) {
            if (protoOutputStream != null) {
                protoOutputStream.write(DeviceConfigurationProto.SHARED_LIBRARIES,
                        slibs.get(i).getName());
            }
            if (pw != null) {
                pw.print("shared-libraries: "); pw.println(slibs.get(i).getName());
            }
        }

        FeatureInfo[] features = pm.getSystemAvailableFeatures();
        Arrays.sort(features, (o1, o2) -> {
            if (o1.name == o2.name) return 0;
            if (o1.name == null) return -1;
            if (o2.name == null) return 1;
            return o1.name.compareTo(o2.name);
        });

        for (int i = 0; i < features.length; i++) {
            if (features[i].name != null) {
                if (protoOutputStream != null) {
                    protoOutputStream.write(DeviceConfigurationProto.FEATURES, features[i].name);
                }
                if (pw != null) {
                    pw.print("features: "); pw.println(features[i].name);
                }
            }
        }

        if (protoOutputStream != null) {
            protoOutputStream.end(token);
        }
    }

    private int getDisplayIdFromNextArg() {
        int displayId = Integer.parseInt(getNextArgRequired());
        if (displayId < 0) {
            throw new IllegalArgumentException("--display must be a non-negative integer");
        }
        return displayId;
    }

    int runGetConfig(PrintWriter pw) throws RemoteException {
        int days = -1;
        int displayId = Display.DEFAULT_DISPLAY;
        boolean asProto = false;
        boolean inclDevice = false;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--days")) {
                days = Integer.parseInt(getNextArgRequired());
                if (days <= 0) {
                    throw new IllegalArgumentException("--days must be a positive integer");
                }
            } else if (opt.equals("--proto")) {
                asProto = true;
            } else if (opt.equals("--device")) {
                inclDevice = true;
            } else if (opt.equals("--display")) {
                displayId = getDisplayIdFromNextArg();
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        Configuration config = mInterface.getConfiguration();
        if (config == null) {
            getErrPrintWriter().println("Activity manager has no configuration");
            return -1;
        }

        DisplayManager dm = mInternal.mContext.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(displayId);

        if (display == null) {
            getErrPrintWriter().println("Error: Display does not exist: " + displayId);
            return -1;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);

        if (asProto) {
            final ProtoOutputStream proto = new ProtoOutputStream(getOutFileDescriptor());
            config.writeResConfigToProto(proto, GlobalConfigurationProto.RESOURCES, metrics);
            if (inclDevice) {
                writeDeviceConfig(proto, GlobalConfigurationProto.DEVICE, null, config, metrics);
            }
            proto.flush();
        } else {
            pw.println("config: " + Configuration.resourceQualifierString(config, metrics));
            pw.println("abi: " + TextUtils.join(",", Build.SUPPORTED_ABIS));
            if (inclDevice) {
                writeDeviceConfig(null, -1, pw, config, metrics);
            }

            if (days >= 0) {
                final List<Configuration> recentConfigs = getRecentConfigurations(days);
                final int recentConfigSize = recentConfigs.size();
                if (recentConfigSize > 0) {
                    pw.println("recentConfigs:");
                    for (int i = 0; i < recentConfigSize; i++) {
                        pw.println("  config: " + Configuration.resourceQualifierString(
                                recentConfigs.get(i)));
                    }
                }
            }

        }
        return 0;
    }

    int runSuppressResizeConfigChanges(PrintWriter pw) throws RemoteException {
        boolean suppress = Boolean.valueOf(getNextArgRequired());
        mTaskInterface.suppressResizeConfigChanges(suppress);
        return 0;
    }

    int runSetInactive(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArgRequired();
        String value = getNextArgRequired();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        usm.setAppInactive(packageName, Boolean.parseBoolean(value), userId);
        return 0;
    }

    private int bucketNameToBucketValue(String name) {
        String lower = name.toLowerCase();
        if (lower.startsWith("ac")) {
            return UsageStatsManager.STANDBY_BUCKET_ACTIVE;
        } else if (lower.startsWith("wo")) {
            return UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
        } else if (lower.startsWith("fr")) {
            return UsageStatsManager.STANDBY_BUCKET_FREQUENT;
        } else if (lower.startsWith("ra")) {
            return UsageStatsManager.STANDBY_BUCKET_RARE;
        } else if (lower.startsWith("re")) {
            return UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
        } else if (lower.startsWith("ne")) {
            return UsageStatsManager.STANDBY_BUCKET_NEVER;
        } else {
            try {
                int bucket = Integer.parseInt(lower);
                return bucket;
            } catch (NumberFormatException nfe) {
                getErrPrintWriter().println("Error: Unknown bucket: " + name);
            }
        }
        return -1;
    }

    int runSetStandbyBucket(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArgRequired();
        String value = getNextArgRequired();
        int bucket = bucketNameToBucketValue(value);
        if (bucket < 0) return -1;
        boolean multiple = peekNextArg() != null;


        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        if (!multiple) {
            usm.setAppStandbyBucket(packageName, bucketNameToBucketValue(value), userId);
        } else {
            ArrayList<AppStandbyInfo> bucketInfoList = new ArrayList<>();
            bucketInfoList.add(new AppStandbyInfo(packageName, bucket));
            while ((packageName = getNextArg()) != null) {
                value = getNextArgRequired();
                bucket = bucketNameToBucketValue(value);
                if (bucket < 0) continue;
                bucketInfoList.add(new AppStandbyInfo(packageName, bucket));
            }
            ParceledListSlice<AppStandbyInfo> slice = new ParceledListSlice<>(bucketInfoList);
            usm.setAppStandbyBuckets(slice, userId);
        }
        return 0;
    }

    int runGetStandbyBucket(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArg();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        if (packageName != null) {
            int bucket = usm.getAppStandbyBucket(packageName, null, userId);
            pw.println(bucket);
        } else {
            ParceledListSlice<AppStandbyInfo> buckets = usm.getAppStandbyBuckets(
                    SHELL_PACKAGE_NAME, userId);
            for (AppStandbyInfo bucketInfo : buckets.getList()) {
                pw.print(bucketInfo.mPackageName); pw.print(": ");
                pw.println(bucketInfo.mStandbyBucket);
            }
        }
        return 0;
    }

    int runGetInactive(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArgRequired();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        boolean isIdle = usm.isAppInactive(packageName, userId, SHELL_PACKAGE_NAME);
        pw.println("Idle=" + isIdle);
        return 0;
    }

    int runSendTrimMemory(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;
        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId == UserHandle.USER_ALL) {
                    getErrPrintWriter().println("Error: Can't use user 'all'");
                    return -1;
                }
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        String proc = getNextArgRequired();
        String levelArg = getNextArgRequired();
        int level;
        switch (levelArg) {
            case "HIDDEN":
                level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                break;
            case "RUNNING_MODERATE":
                level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
                break;
            case "BACKGROUND":
                level = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                break;
            case "RUNNING_LOW":
                level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
                break;
            case "MODERATE":
                level = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                break;
            case "RUNNING_CRITICAL":
                level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
                break;
            case "COMPLETE":
                level = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
                break;
            default:
                try {
                    level = Integer.parseInt(levelArg);
                } catch (NumberFormatException e) {
                    getErrPrintWriter().println("Error: Unknown level option: " + levelArg);
                    return -1;
                }
        }
        if (!mInterface.setProcessMemoryTrimLevel(proc, userId, level)) {
            getErrPrintWriter().println("Unknown error: failed to set trim level");
            return -1;
        }
        return 0;
    }

    int runDisplay(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        switch (op) {
            case "move-stack":
                return runDisplayMoveStack(pw);
            default:
                getErrPrintWriter().println("Error: unknown command '" + op + "'");
                return -1;
        }
    }

    int runStack(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        switch (op) {
            case "move-task":
                return runStackMoveTask(pw);
            case "list":
                return runStackList(pw);
            case "info":
                return runRootTaskInfo(pw);
            case "remove":
                return runRootTaskRemove(pw);
            default:
                getErrPrintWriter().println("Error: unknown command '" + op + "'");
                return -1;
        }
    }


    private Rect getBounds() {
        String leftStr = getNextArgRequired();
        int left = Integer.parseInt(leftStr);
        String topStr = getNextArgRequired();
        int top = Integer.parseInt(topStr);
        String rightStr = getNextArgRequired();
        int right = Integer.parseInt(rightStr);
        String bottomStr = getNextArgRequired();
        int bottom = Integer.parseInt(bottomStr);
        if (left < 0) {
            getErrPrintWriter().println("Error: bad left arg: " + leftStr);
            return null;
        }
        if (top < 0) {
            getErrPrintWriter().println("Error: bad top arg: " + topStr);
            return null;
        }
        if (right <= 0) {
            getErrPrintWriter().println("Error: bad right arg: " + rightStr);
            return null;
        }
        if (bottom <= 0) {
            getErrPrintWriter().println("Error: bad bottom arg: " + bottomStr);
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    int runDisplayMoveStack(PrintWriter pw) throws RemoteException {
        String rootTaskIdStr = getNextArgRequired();
        int rootTaskId = Integer.parseInt(rootTaskIdStr);
        String displayIdStr = getNextArgRequired();
        int displayId = Integer.parseInt(displayIdStr);
        mTaskInterface.moveRootTaskToDisplay(rootTaskId, displayId);
        return 0;
    }

    int runStackMoveTask(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String rootTaskIdStr = getNextArgRequired();
        int rootTaskId = Integer.parseInt(rootTaskIdStr);
        String toTopStr = getNextArgRequired();
        final boolean toTop;
        if ("true".equals(toTopStr)) {
            toTop = true;
        } else if ("false".equals(toTopStr)) {
            toTop = false;
        } else {
            getErrPrintWriter().println("Error: bad toTop arg: " + toTopStr);
            return -1;
        }

        mTaskInterface.moveTaskToRootTask(taskId, rootTaskId, toTop);
        return 0;
    }

    int runStackList(PrintWriter pw) throws RemoteException {
        List<RootTaskInfo> tasks = mTaskInterface.getAllRootTaskInfos();
        for (RootTaskInfo info : tasks) {
            pw.println(info);
        }
        return 0;
    }

    int runRootTaskInfo(PrintWriter pw) throws RemoteException {
        int windowingMode = Integer.parseInt(getNextArgRequired());
        int activityType = Integer.parseInt(getNextArgRequired());
        RootTaskInfo info = mTaskInterface.getRootTaskInfo(windowingMode, activityType);
        pw.println(info);
        return 0;
    }

    int runRootTaskRemove(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        mTaskInterface.removeTask(taskId);
        return 0;
    }

    int runTask(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (op.equals("lock")) {
            return runTaskLock(pw);
        } else if (op.equals("resizeable")) {
            return runTaskResizeable(pw);
        } else if (op.equals("resize")) {
            return runTaskResize(pw);
        } else if (op.equals("focus")) {
            return runTaskFocus(pw);
        } else {
            getErrPrintWriter().println("Error: unknown command '" + op + "'");
            return -1;
        }
    }

    int runTaskLock(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        if (taskIdStr.equals("stop")) {
            mTaskInterface.stopSystemLockTaskMode();
        } else {
            int taskId = Integer.parseInt(taskIdStr);
            mTaskInterface.startSystemLockTaskMode(taskId);
        }
        pw.println("Activity manager is " + (mTaskInterface.isInLockTaskMode() ? "" : "not ") +
                "in lockTaskMode");
        return 0;
    }

    int runTaskResizeable(PrintWriter pw) throws RemoteException {
        final String taskIdStr = getNextArgRequired();
        final int taskId = Integer.parseInt(taskIdStr);
        final String resizeableStr = getNextArgRequired();
        final int resizeableMode = Integer.parseInt(resizeableStr);
        mTaskInterface.setTaskResizeable(taskId, resizeableMode);
        return 0;
    }

    int runTaskResize(PrintWriter pw) throws RemoteException {
        final String taskIdStr = getNextArgRequired();
        final int taskId = Integer.parseInt(taskIdStr);
        final Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        taskResize(taskId, bounds, 0, false);
        return 0;
    }

    void taskResize(int taskId, Rect bounds, int delay_ms, boolean pretendUserResize)
            throws RemoteException {
        final int resizeMode = pretendUserResize ? RESIZE_MODE_USER : RESIZE_MODE_SYSTEM;
        mTaskInterface.resizeTask(taskId, bounds, resizeMode);
        try {
            Thread.sleep(delay_ms);
        } catch (InterruptedException e) {
        }
    }

    int moveTask(int taskId, Rect taskRect, Rect stackRect, int stepSize,
            int maxToTravel, boolean movingForward, boolean horizontal, int delay_ms)
            throws RemoteException {
        int maxMove;
        if (movingForward) {
            while (maxToTravel > 0
                    && ((horizontal && taskRect.right < stackRect.right)
                    ||(!horizontal && taskRect.bottom < stackRect.bottom))) {
                if (horizontal) {
                    maxMove = Math.min(stepSize, stackRect.right - taskRect.right);
                    maxToTravel -= maxMove;
                    taskRect.right += maxMove;
                    taskRect.left += maxMove;
                } else {
                    maxMove = Math.min(stepSize, stackRect.bottom - taskRect.bottom);
                    maxToTravel -= maxMove;
                    taskRect.top += maxMove;
                    taskRect.bottom += maxMove;
                }
                taskResize(taskId, taskRect, delay_ms, false);
            }
        } else {
            while (maxToTravel < 0
                    && ((horizontal && taskRect.left > stackRect.left)
                    ||(!horizontal && taskRect.top > stackRect.top))) {
                if (horizontal) {
                    maxMove = Math.min(stepSize, taskRect.left - stackRect.left);
                    maxToTravel -= maxMove;
                    taskRect.right -= maxMove;
                    taskRect.left -= maxMove;
                } else {
                    maxMove = Math.min(stepSize, taskRect.top - stackRect.top);
                    maxToTravel -= maxMove;
                    taskRect.top -= maxMove;
                    taskRect.bottom -= maxMove;
                }
                taskResize(taskId, taskRect, delay_ms, false);
            }
        }
        // Return the remaining distance we didn't travel because we reached the target location.
        return maxToTravel;
    }

    int getStepSize(int current, int target, int inStepSize, boolean greaterThanTarget) {
        int stepSize = 0;
        if (greaterThanTarget && target < current) {
            current -= inStepSize;
            stepSize = inStepSize;
            if (target > current) {
                stepSize -= (target - current);
            }
        }
        if (!greaterThanTarget && target > current) {
            current += inStepSize;
            stepSize = inStepSize;
            if (target < current) {
                stepSize += (current - target);
            }
        }
        return stepSize;
    }

    int runTaskFocus(PrintWriter pw) throws RemoteException {
        final int taskId = Integer.parseInt(getNextArgRequired());
        pw.println("Setting focus to task " + taskId);
        mTaskInterface.setFocusedTask(taskId);
        return 0;
    }

    int runWrite(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerUidObserver()");
        mInternal.mAtmInternal.flushRecentTasks();
        pw.println("All tasks persisted.");
        return 0;
    }

    int runAttachAgent(PrintWriter pw) {
        // TODO: revisit the permissions required for attaching agents
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "attach-agent");
        String process = getNextArgRequired();
        String agent = getNextArgRequired();
        String opt;
        if ((opt = getNextArg()) != null) {
            pw.println("Error: Unknown option: " + opt);
            return -1;
        }
        mInternal.attachAgent(process, agent);
        return 0;
    }

    int runSupportsMultiwindow(PrintWriter pw) throws RemoteException {
        final Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(ActivityTaskManager.supportsMultiWindow(mInternal.mContext));
        return 0;
    }

    int runSupportsSplitScreenMultiwindow(PrintWriter pw) throws RemoteException {
        final Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(ActivityTaskManager.supportsSplitScreenMultiWindow(mInternal.mContext));
        return 0;
    }

    int runUpdateApplicationInfo(PrintWriter pw) throws RemoteException {
        int userid = UserHandle.parseUserArg(getNextArgRequired());
        ArrayList<String> packages = new ArrayList<>();
        packages.add(getNextArgRequired());
        String packageName;
        while ((packageName = getNextArg()) != null) {
            packages.add(packageName);
        }
        mInternal.scheduleApplicationInfoChanged(packages, userid);
        pw.println("Packages updated with most recent ApplicationInfos.");
        return 0;
    }

    int runNoHomeScreen(PrintWriter pw) throws RemoteException {
        final Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(res.getBoolean(com.android.internal.R.bool.config_noHomeScreen));
        return 0;
    }

    int runWaitForBroadcastIdle(PrintWriter pw) throws RemoteException {
        pw = new PrintWriter(new TeeWriter(LOG_WRITER_INFO, pw));
        boolean flushBroadcastLoopers = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--flush-broadcast-loopers")) {
                flushBroadcastLoopers = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInternal.waitForBroadcastIdle(pw, flushBroadcastLoopers);
        return 0;
    }

    int runWaitForBroadcastBarrier(PrintWriter pw) throws RemoteException {
        pw = new PrintWriter(new TeeWriter(LOG_WRITER_INFO, pw));
        boolean flushBroadcastLoopers = false;
        boolean flushApplicationThreads = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--flush-broadcast-loopers")) {
                flushBroadcastLoopers = true;
            } else if (opt.equals("--flush-application-threads")) {
                flushApplicationThreads = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInternal.waitForBroadcastBarrier(pw, flushBroadcastLoopers, flushApplicationThreads);
        return 0;
    }

    int runWaitForApplicationBarrier(PrintWriter pw) throws RemoteException {
        pw = new PrintWriter(new TeeWriter(LOG_WRITER_INFO, pw));
        mInternal.waitForApplicationBarrier(pw);
        return 0;
    }

    int runWaitForBroadcastDispatch(PrintWriter pw) throws RemoteException {
        pw = new PrintWriter(new TeeWriter(LOG_WRITER_INFO, pw));
        final Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        mInternal.waitForBroadcastDispatch(pw, intent);
        return 0;
    }

    int runSetIgnoreDeliveryGroupPolicy(PrintWriter pw) throws RemoteException {
        final String broadcastAction = getNextArgRequired();
        mInternal.setIgnoreDeliveryGroupPolicy(broadcastAction);
        return 0;
    }

    int runClearIgnoreDeliveryGroupPolicy(PrintWriter pw) throws RemoteException {
        final String broadcastAction = getNextArgRequired();
        mInternal.clearIgnoreDeliveryGroupPolicy(broadcastAction);
        return 0;
    }

    int runRefreshSettingsCache() throws RemoteException {
        mInternal.refreshSettingsCache();
        return 0;
    }

    private int runCompat(PrintWriter pw) throws RemoteException {
        final PlatformCompat platformCompat = (PlatformCompat)
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE);
        String toggleValue = getNextArgRequired();
        boolean killPackage = !"--no-kill".equals(getNextOption());
        boolean toggleAll = false;
        int targetSdkVersion = -1;
        long changeId = -1;

        if (toggleValue.endsWith("-all")) {
            toggleValue = toggleValue.substring(0, toggleValue.lastIndexOf("-all"));
            toggleAll = true;
            if (!toggleValue.equals("reset")) {
                try {
                    targetSdkVersion = Integer.parseInt(getNextArgRequired());
                } catch (NumberFormatException e) {
                    pw.println("Invalid targetSdkVersion!");
                    return -1;
                }
            }
        } else {
            String changeIdString = getNextArgRequired();
            try {
                changeId = Long.parseLong(changeIdString);
            } catch (NumberFormatException e) {
                changeId = platformCompat.lookupChangeId(changeIdString);
            }
            if (changeId == -1) {
                pw.println("Unknown or invalid change: '" + changeIdString + "'.");
                return -1;
            }
        }
        String packageName = getNextArgRequired();
        if (!toggleAll && !platformCompat.isKnownChangeId(changeId)) {
            pw.println("Warning! Change " + changeId + " is not known yet. Enabling/disabling it"
                    + " could have no effect.");
        }
        ArraySet<Long> enabled = new ArraySet<>();
        ArraySet<Long> disabled = new ArraySet<>();
        try {
            switch (toggleValue) {
                case "enable":
                    if (toggleAll) {
                        int numChanges = platformCompat.enableTargetSdkChanges(packageName,
                                                                               targetSdkVersion);
                        if (numChanges == 0) {
                            pw.println("No changes were enabled.");
                            return -1;
                        }
                        pw.println("Enabled " + numChanges + " changes gated by targetSdkVersion "
                                + targetSdkVersion + " for " + packageName + ".");
                    } else {
                        enabled.add(changeId);
                        CompatibilityChangeConfig overrides =
                                new CompatibilityChangeConfig(
                                        new Compatibility.ChangeConfig(enabled, disabled));
                        if (killPackage) {
                            platformCompat.setOverrides(overrides, packageName);
                        } else {
                            platformCompat.setOverridesForTest(overrides, packageName);
                        }
                        pw.println("Enabled change " + changeId + " for " + packageName + ".");
                    }
                    return 0;
                case "disable":
                    if (toggleAll) {
                        int numChanges = platformCompat.disableTargetSdkChanges(packageName,
                                                                                targetSdkVersion);
                        if (numChanges == 0) {
                            pw.println("No changes were disabled.");
                            return -1;
                        }
                        pw.println("Disabled " + numChanges + " changes gated by targetSdkVersion "
                                + targetSdkVersion + " for " + packageName + ".");
                    } else {
                        disabled.add(changeId);
                        CompatibilityChangeConfig overrides =
                                new CompatibilityChangeConfig(
                                        new Compatibility.ChangeConfig(enabled, disabled));
                        if (killPackage) {
                            platformCompat.setOverrides(overrides, packageName);
                        } else {
                            platformCompat.setOverridesForTest(overrides, packageName);
                        }
                        pw.println("Disabled change " + changeId + " for " + packageName + ".");
                    }
                    return 0;
                case "reset":
                    if (toggleAll) {
                        if (killPackage) {
                            platformCompat.clearOverrides(packageName);
                        } else {
                            platformCompat.clearOverridesForTest(packageName);
                        }
                        pw.println("Reset all changes for " + packageName + " to default value.");
                        return 0;
                    }
                    boolean existed;
                    if (killPackage) {
                        existed = platformCompat.clearOverride(changeId, packageName);
                    } else {
                        existed = platformCompat.clearOverrideForTest(changeId, packageName);
                    }
                    if (existed) {
                        pw.println("Reset change " + changeId + " for " + packageName
                                + " to default value.");
                    } else {
                        pw.println("No override exists for changeId " + changeId + ".");
                    }
                    return 0;
                default:
                    pw.println("Invalid toggle value: '" + toggleValue + "'.");
            }
        } catch (SecurityException e) {
            pw.println(e.getMessage());
        }
        return -1;
    }

    private int runGetCurrentForegroundProcess(PrintWriter pw, IActivityManager iam)
            throws RemoteException {

        ProcessObserver observer = new ProcessObserver(pw, iam);
        iam.registerProcessObserver(observer);

        final InputStream mInput = getRawInputStream();
        InputStreamReader converter = new InputStreamReader(mInput);
        BufferedReader in = new BufferedReader(converter);
        String line;
        try {
            while ((line = in.readLine()) != null) {
                boolean addNewline = true;
                if (line.length() <= 0) {
                    addNewline = false;
                } else if ("q".equals(line) || "quit".equals(line)) {
                    break;
                } else {
                    pw.println("Invalid command: " + line);
                }
                if (addNewline) {
                    pw.println("");
                }
                pw.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
            pw.flush();
        } finally {
            iam.unregisterProcessObserver(observer);
        }
        return 0;
    }

    static final class ProcessObserver extends IProcessObserver.Stub {

        private PrintWriter mPw;
        private IActivityManager mIam;

        ProcessObserver(PrintWriter mPw, IActivityManager mIam) {
            this.mPw = mPw;
            this.mIam = mIam;
        }

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
            if (foregroundActivities) {
                try {
                    int prcState = mIam.getUidProcessState(uid, "android");

                    if (prcState == ProcessStateEnum.TOP) {
                        mPw.println("New foreground process: " + pid);
                    } else {
                        mPw.println("No top app found");
                    }
                    mPw.flush();
                } catch (RemoteException e) {
                    mPw.println("Error occurred in binder call");
                    mPw.flush();
                }
            }
        }

        @Override
        public void onProcessStarted(int pid, int processUid, int packageUid, String packageName,
                String processName) {
        }

        @Override
        public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
        }

        @Override
        public void onProcessDied(int pid, int uid) {
        }
    }

    private int runSetMemoryFactor(PrintWriter pw) throws RemoteException {
        final String levelArg = getNextArgRequired();
        @MemFactor int level = ADJ_MEM_FACTOR_NOTHING;
        switch (levelArg) {
            case "NORMAL":
                level = ADJ_MEM_FACTOR_NORMAL;
                break;
            case "MODERATE":
                level = ADJ_MEM_FACTOR_MODERATE;
                break;
            case "LOW":
                level = ADJ_MEM_FACTOR_LOW;
                break;
            case "CRITICAL":
                level = ADJ_MEM_FACTOR_CRITICAL;
                break;
            default:
                try {
                    level = Integer.parseInt(levelArg);
                } catch (NumberFormatException e) {
                }
                if (level < ADJ_MEM_FACTOR_NORMAL || level > ADJ_MEM_FACTOR_CRITICAL) {
                    getErrPrintWriter().println("Error: Unknown level option: " + levelArg);
                    return -1;
                }
        }
        mInternal.setMemFactorOverride(level);
        return 0;
    }

    private int runShowMemoryFactor(PrintWriter pw) throws RemoteException {
        final @MemFactor int level = mInternal.getMemoryTrimLevel();
        switch (level) {
            case ADJ_MEM_FACTOR_NOTHING:
                pw.println("<UNKNOWN>");
                break;
            case ADJ_MEM_FACTOR_NORMAL:
                pw.println("NORMAL");
                break;
            case ADJ_MEM_FACTOR_MODERATE:
                pw.println("MODERATE");
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.println("LOW");
                break;
            case ADJ_MEM_FACTOR_CRITICAL:
                pw.println("CRITICAL");
                break;
        }
        pw.flush();
        return 0;
    }

    private int runResetMemoryFactor(PrintWriter pw) throws RemoteException {
        mInternal.setMemFactorOverride(ADJ_MEM_FACTOR_NOTHING);
        return 0;
    }

    private int runMemoryFactor(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                "runMemoryFactor()");

        final String op = getNextArgRequired();
        switch (op) {
            case "set":
                return runSetMemoryFactor(pw);
            case "show":
                return runShowMemoryFactor(pw);
            case "reset":
                return runResetMemoryFactor(pw);
            default:
                getErrPrintWriter().println("Error: unknown command '" + op + "'");
                return -1;
        }
    }

    private int runServiceRestartBackoff(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "runServiceRestartBackoff()");

        final String opt = getNextArgRequired();
        switch (opt) {
            case "enable":
                mInternal.setServiceRestartBackoffEnabled(getNextArgRequired(), true, "shell");
                return 0;
            case "disable":
                mInternal.setServiceRestartBackoffEnabled(getNextArgRequired(), false, "shell");
                return 0;
            case "show":
                pw.println(mInternal.isServiceRestartBackoffEnabled(getNextArgRequired())
                        ? "enabled" : "disabled");
                return 0;
            default:
                getErrPrintWriter().println("Error: unknown command '" + opt + "'");
                return -1;
        }
    }

    private int runGetIsolatedProcesses(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.DUMP,
                "getIsolatedProcesses()");
        final List<Integer> result = mInternal.mInternal.getIsolatedProcesses(
                Integer.parseInt(getNextArgRequired()));
        pw.print("[");
        if (result != null) {
            for (int i = 0, size = result.size(); i < size; i++) {
                if (i > 0) {
                    pw.print(", ");
                }
                pw.print(result.get(i));
            }
        }
        pw.println("]");
        return 0;
    }

    private int runSetStopUserOnSwitch(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "setStopUserOnSwitch()");
        String arg = getNextArg();
        if (arg == null) {
            Slogf.i(TAG, "setStopUserOnSwitch(): resetting to default value");
            mInternal.setStopUserOnSwitch(ActivityManager.STOP_USER_ON_SWITCH_DEFAULT);
            pw.println("Reset to default value");
            return 0;
        }

        boolean stop = Boolean.parseBoolean(arg);
        int value = stop
                ? ActivityManager.STOP_USER_ON_SWITCH_TRUE
                : ActivityManager.STOP_USER_ON_SWITCH_FALSE;

        Slogf.i(TAG, "runSetStopUserOnSwitch(): setting to %d (%b)", value, stop);
        mInternal.setStopUserOnSwitch(value);
        pw.println("Set to " + stop);

        return 0;
    }

    // TODO(b/203105544) STOPSHIP - For debugging only, to be removed before shipping.
    private int runSetBgAbusiveUids(PrintWriter pw) throws RemoteException {
        final String arg = getNextArg();
        final AppBatteryTracker batteryTracker =
                mInternal.mAppRestrictionController.getAppStateTracker(AppBatteryTracker.class);
        if (batteryTracker == null) {
            getErrPrintWriter().println("Unable to get bg battery tracker");
            return -1;
        }
        if (arg == null) {
            batteryTracker.clearDebugUidPercentage();
            return 0;
        }
        String[] pairs = arg.split(",");
        int[] uids = new int[pairs.length];
        double[][] values = new double[pairs.length][];
        try {
            for (int i = 0; i < pairs.length; i++) {
                String[] pair = pairs[i].split("=");
                if (pair.length != 2) {
                    getErrPrintWriter().println("Malformed input");
                    return -1;
                }
                uids[i] = Integer.parseInt(pair[0]);
                final String[] vals = pair[1].split(":");
                if (vals.length != BATTERY_USAGE_COUNT) {
                    getErrPrintWriter().println("Malformed input");
                    return -1;
                }
                values[i] = new double[vals.length];
                for (int j = 0; j < vals.length; j++) {
                    values[i][j] = Double.parseDouble(vals[j]);
                }
            }
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Malformed input");
            return -1;
        }
        batteryTracker.setDebugUidPercentage(uids, values);
        return 0;
    }

    private int runListBgExemptionsConfig(PrintWriter pw) throws RemoteException {
        final ArraySet<String> sysConfigs = mInternal.mAppRestrictionController
                .mBgRestrictionExemptioFromSysConfig;
        if (sysConfigs != null) {
            for (int i = 0, size = sysConfigs.size(); i < size; i++) {
                pw.print(sysConfigs.valueAt(i));
                pw.print(' ');
            }
            pw.println();
        }
        return 0;
    }

    private @ActivityManager.RestrictionLevel int restrictionNameToLevel(String name) {
        String lower = name.toLowerCase();
        switch (lower) {
            case "unrestricted":
                return ActivityManager.RESTRICTION_LEVEL_UNRESTRICTED;
            case "exempted":
                return ActivityManager.RESTRICTION_LEVEL_EXEMPTED;
            case "adaptive_bucket":
                return ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
            case "restricted_bucket":
                return ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
            case "background_restricted":
                return ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
            case "force_stopped":
                return ActivityManager.RESTRICTION_LEVEL_FORCE_STOPPED;
            case "user_launch_only":
                return ActivityManager.RESTRICTION_LEVEL_USER_LAUNCH_ONLY;
            case "custom":
                return ActivityManager.RESTRICTION_LEVEL_CUSTOM;
            default:
                return ActivityManager.RESTRICTION_LEVEL_UNKNOWN;
        }
    }

    int runSetBgRestrictionLevel(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArgRequired();
        final String value = getNextArgRequired();
        final int level = restrictionNameToLevel(value);
        if (level == ActivityManager.RESTRICTION_LEVEL_UNKNOWN) {
            pw.println("Error: invalid restriction level");
            return -1;
        }

        int uid = INVALID_UID;
        try {
            final PackageManager pm = mInternal.mContext.getPackageManager();
            uid = pm.getPackageUidAsUser(packageName,
                    PackageManager.PackageInfoFlags.of(MATCH_ANY_USER), userId);
        } catch (PackageManager.NameNotFoundException e) {
            pw.println("Error: userId:" + userId + " package:" + packageName + " is not found");
            return -1;
        }
        mInternal.setBackgroundRestrictionLevel(packageName, uid, userId, level,
                REASON_MAIN_FORCED_BY_USER, REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED);
        return 0;
    }

    int runGetBgRestrictionLevel(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        final String packageName = getNextArgRequired();
        final @ActivityManager.RestrictionLevel int level =
                mInternal.getBackgroundRestrictionLevel(packageName, userId);
        pw.println(ActivityManager.restrictionLevelToName(level));
        return 0;
    }

    int runSetForegroundServiceDelegate(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        final String packageName = getNextArgRequired();
        final String action = getNextArgRequired();
        boolean isStart = true;
        if ("start".equals(action)) {
            isStart = true;
        } else if ("stop".equals(action)) {
            isStart = false;
        } else {
            pw.println("Error: action is either start or stop");
            return -1;
        }

        int uid = INVALID_UID;
        try {
            final PackageManager pm = mInternal.mContext.getPackageManager();
            uid = pm.getPackageUidAsUser(packageName,
                    PackageManager.PackageInfoFlags.of(MATCH_ANY_USER), userId);
        } catch (PackageManager.NameNotFoundException e) {
            pw.println("Error: userId:" + userId + " package:" + packageName + " is not found");
            return -1;
        }
        mInternal.setForegroundServiceDelegate(packageName, uid, isStart,
                ForegroundServiceDelegationOptions.DELEGATION_SERVICE_SPECIAL_USE,
                "FgsDelegate");
        return 0;
    }

    int runResetDropboxRateLimiter() throws RemoteException {
        mInternal.resetDropboxRateLimiter();
        return 0;
    }

    int runListDisplaysForStartingUsers(PrintWriter pw) throws RemoteException {
        int[] displayIds = mInterface.getDisplayIdsForStartingVisibleBackgroundUsers();
        // NOTE: format below cannot be changed as it's used by ITestDevice
        pw.println(displayIds == null || displayIds.length == 0
                ? "none"
                : Arrays.toString(displayIds));
        return 0;
    }

    private Resources getResources(PrintWriter pw) throws RemoteException {
        // system resources does not contain all the device configuration, construct it manually.
        Configuration config = mInterface.getConfiguration();
        if (config == null) {
            pw.println("Error: Activity manager has no configuration");
            return null;
        }

        final DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();

        return new Resources(AssetManager.getSystem(), metrics, config);
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        dumpHelp(pw, mDumping);
    }

    @NeverCompile // Avoid size overhead of debugging code.
    static void dumpHelp(PrintWriter pw, boolean dumping) {
        if (dumping) {
            pw.println("Activity manager dump options:");
            pw.println("  [-a] [-c] [-p PACKAGE] [-h] [WHAT] ...");
            pw.println("  WHAT may be one of:");
            pw.println("    a[ctivities]: activity stack state");
            pw.println("    r[recents]: recent activities state");
            pw.println("    b[roadcasts] [PACKAGE_NAME] [history [-s]]: broadcast state");
            pw.println("    broadcast-stats [PACKAGE_NAME]: aggregated broadcast statistics");
            pw.println("    i[ntents] [PACKAGE_NAME]: pending intent state");
            pw.println("    p[rocesses] [PACKAGE_NAME]: process state");
            pw.println("    o[om]: out of memory management");
            pw.println("    perm[issions]: URI permission grant state");
            pw.println("    prov[iders] [COMP_SPEC ...]: content provider state");
            pw.println("    provider [COMP_SPEC]: provider client-side state");
            pw.println("    s[ervices] [COMP_SPEC ...]: service state");
            pw.println("    allowed-associations: current package association restrictions");
            pw.println("    as[sociations]: tracked app associations");
            pw.println("    start-info [PACKAGE_NAME]: historical process start information");
            pw.println("    exit-info [PACKAGE_NAME]: historical process exit information");
            pw.println("    lmk: stats on low memory killer");
            pw.println("    lru: raw LRU process list");
            pw.println("    binder-proxies: stats on binder objects and IPCs");
            pw.println("    settings: currently applied config settings");
            pw.println("    timers: the current ANR timer state");
            pw.println("    service [COMP_SPEC]: service client-side state");
            pw.println("    package [PACKAGE_NAME]: all state related to given package");
            pw.println("    all: dump all activities");
            pw.println("    top: dump the top activity");
            pw.println("    users: user state");
            pw.println("  WHAT may also be a COMP_SPEC to dump activities.");
            pw.println("  COMP_SPEC may be a component name (com.foo/.myApp),");
            pw.println("    a partial substring in a component name, a");
            pw.println("    hex object identifier.");
            pw.println("  -a: include all available server state.");
            pw.println("  -c: include client state.");
            pw.println("  -p: limit output to given package.");
            pw.println("  -d: limit output to given display.");
            pw.println("  --checkin: output checkin format, resetting data.");
            pw.println("  --C: output checkin format, not resetting data.");
            pw.println("  --proto: output dump in protocol buffer format.");
            pw.printf("  %s: dump just the DUMPABLE-related state of an activity. Use the %s "
                    + "option to list the supported DUMPABLEs\n", Activity.DUMP_ARG_DUMP_DUMPABLE,
                    Activity.DUMP_ARG_LIST_DUMPABLES);
            pw.printf("  %s: show the available dumpables in an activity\n",
                    Activity.DUMP_ARG_LIST_DUMPABLES);
        } else {
            pw.println("Activity manager (activity) commands:");
            pw.println("  help");
            pw.println("      Print this help text.");
            pw.println("  start-activity [-D] [-N] [-W] [-P <FILE>] [--start-profiler <FILE>]");
            pw.println("          [--sampling INTERVAL] [--clock-type <TYPE>] [--streaming]");
            pw.println("          [" + PROFILER_OUTPUT_VERSION_FLAG + " NUMBER]");
            pw.println("          [-R COUNT] [-S] [--track-allocation]");
            pw.println("          [--user <USER_ID> | current] [--suspend] <INTENT>");
            pw.println("      Start an Activity.  Options are:");
            pw.println("      -D: enable debugging");
            pw.println("      --suspend: debugged app suspend threads at startup (only with -D)");
            pw.println("      -N: enable native debugging");
            pw.println("      -W: wait for launch to complete (initial display)");
            pw.println("      --start-profiler <FILE>: start profiler and send results to <FILE>");
            pw.println("      --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
            pw.println("          between samples (use with --start-profiler)");
            pw.println("      --clock-type <TYPE>: type can be wall / thread-cpu / dual. Specify");
            pw.println("          the clock that is used to report the timestamps when profiling");
            pw.println("          The default value is dual. (use with --start-profiler)");
            pw.println("      --streaming: stream the profiling output to the specified file");
            pw.println("          (use with --start-profiler)");
            pw.println("      " + PROFILER_OUTPUT_VERSION_FLAG + " Specify the version of the");
            pw.println("          profiling output (use with --start-profiler)");
            pw.println("      -P <FILE>: like above, but profiling stops when app goes idle");
            pw.println("      --attach-agent <agent>: attach the given agent before binding");
            pw.println("      --attach-agent-bind <agent>: attach the given agent during binding");
            pw.println("      -R: repeat the activity launch <COUNT> times.  Prior to each repeat,");
            pw.println("          the top activity will be finished.");
            pw.println("      -S: force stop the target app before starting the activity");
            pw.println("      --track-allocation: enable tracking of object allocations");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("      --windowingMode <WINDOWING_MODE>: The windowing mode to launch the activity into.");
            pw.println("      --activityType <ACTIVITY_TYPE>: The activity type to launch the activity as.");
            pw.println("      --display <DISPLAY_ID>: The display to launch the activity into.");
            pw.println("      --splashscreen-icon: Show the splash screen icon on launch.");
            pw.println("  start-in-vsync");
            pw.println("      Start an Activity with vsync aligned. See `start-activity` for the");
            pw.println("      possible options.");
            pw.println("  start-service [--user <USER_ID> | current] <INTENT>");
            pw.println("      Start a Service.  Options are:");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("  start-foreground-service [--user <USER_ID> | current] <INTENT>");
            pw.println("      Start a foreground Service.  Options are:");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("  stop-service [--user <USER_ID> | current] <INTENT>");
            pw.println("      Stop a Service.  Options are:");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("  broadcast [--user <USER_ID> | all | current]");
            pw.println("          [--receiver-permission <PERMISSION>]");
            pw.println("          [--allow-background-activity-starts]");
            pw.println("          [--async] <INTENT>");
            pw.println("      Send a broadcast Intent.  Options are:");
            pw.println("      --user <USER_ID> | all | current: Specify which user to send to; if not");
            pw.println("          specified then send to all users.");
            pw.println("      --receiver-permission <PERMISSION>: Require receiver to hold permission.");
            pw.println("      --allow-background-activity-starts: The receiver may start activities");
            pw.println("          even if in the background.");
            pw.println("      --async: Send without waiting for the completion of the receiver.");
            pw.println("  compact {some|full} <PROCESS>");
            pw.println("      Perform a single process compaction. The given <PROCESS> argument");
            pw.println("          may be either a process name or pid.");
            pw.println("      some: execute file compaction.");
            pw.println("      full: execute anon + file compaction.");
            pw.println("  compact system");
            pw.println("      Perform a full system compaction.");
            pw.println("  compact native {some|full} <pid>");
            pw.println("      Perform a native compaction for process with <pid>.");
            pw.println("      some: execute file compaction.");
            pw.println("      full: execute anon + file compaction.");
            pw.println("  freeze [--sticky] <PROCESS>");
            pw.println("      Freeze a process. The given <PROCESS> argument");
            pw.println("          may be either a process name or pid.  Options are:");
            pw.println("      --sticky: persists the frozen state for the process lifetime or");
            pw.println("                  until an unfreeze is triggered via shell");
            pw.println("  unfreeze [--sticky] <PROCESS>");
            pw.println("      Unfreeze a process. The given <PROCESS> argument");
            pw.println("          may be either a process name or pid.  Options are:");
            pw.println("      --sticky: persists the unfrozen state for the process lifetime or");
            pw.println("                  until a freeze is triggered via shell");
            pw.println("  instrument [-r] [-e <NAME> <VALUE>] [-p <FILE>] [-w]");
            pw.println("          [--user <USER_ID> | current]");
            pw.println("          [--no-hidden-api-checks [--no-test-api-access]]");
            pw.println("          [--no-isolated-storage]");
            pw.println("          [--no-window-animation] [--abi <ABI>] <COMPONENT>");
            pw.println("      Start an Instrumentation.  Typically this target <COMPONENT> is in the");
            pw.println("      form <TEST_PACKAGE>/<RUNNER_CLASS> or only <TEST_PACKAGE> if there");
            pw.println("      is only one instrumentation.  Options are:");
            pw.println("      -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT).  Use with");
            pw.println("          [-e perf true] to generate raw output for performance measurements.");
            pw.println("      -e <NAME> <VALUE>: set argument <NAME> to <VALUE>.  For test runners a");
            pw.println("          common form is [-e <testrunner_flag> <value>[,<value>...]].");
            pw.println("      -p <FILE>: write profiling data to <FILE>");
            pw.println("      -m: Write output as protobuf to stdout (machine readable)");
            pw.println("      -f <Optional PATH/TO/FILE>: Write output as protobuf to a file (machine");
            pw.println("          readable). If path is not specified, default directory and file name will");
            pw.println("          be used: /sdcard/instrument-logs/log-yyyyMMdd-hhmmss-SSS.instrumentation_data_proto");
            pw.println("      -w: wait for instrumentation to finish before returning.  Required for");
            pw.println("          test runners.");
            pw.println("      --user <USER_ID> | current: Specify user instrumentation runs in;");
            pw.println("          current user if not specified.");
            pw.println("      --no-hidden-api-checks: disable restrictions on use of hidden API.");
            pw.println("      --no-test-api-access: do not allow access to test APIs, if hidden");
            pw.println("          API checks are enabled.");
            pw.println("      --no-isolated-storage: don't use isolated storage sandbox and ");
            pw.println("          mount full external storage");
            pw.println("      --no-window-animation: turn off window animations while running.");
            pw.println("      --abi <ABI>: Launch the instrumented process with the selected ABI.");
            pw.println("          This assumes that the process supports the selected ABI.");
            pw.println("  trace-ipc [start|stop] [--dump-file <FILE>]");
            pw.println("      Trace IPC transactions.");
            pw.println("      start: start tracing IPC transactions.");
            pw.println("      stop: stop tracing IPC transactions and dump the results to file.");
            pw.println("      --dump-file <FILE>: Specify the file the trace should be dumped to.");
            anrTimerHelp(pw);
            pw.println("  profile start [--user <USER_ID> current]");
            pw.println("          [--clock-type <TYPE>]");
            pw.println("          [" + PROFILER_OUTPUT_VERSION_FLAG + " VERSION]");
            pw.println("          [--sampling INTERVAL | --streaming] <PROCESS> <FILE>");
            pw.println("      Start profiler on a process.  The given <PROCESS> argument");
            pw.println("        may be either a process name or pid.  Options are:");
            pw.println("      --user <USER_ID> | current: When supplying a process name,");
            pw.println("          specify user of process to profile; uses current user if not");
            pw.println("          specified.");
            pw.println("      --clock-type <TYPE>: use the specified clock to report timestamps.");
            pw.println("          The type can be one of wall | thread-cpu | dual. The default");
            pw.println("          value is dual.");
            pw.println("      " + PROFILER_OUTPUT_VERSION_FLAG + "VERSION: specifies the output");
            pw.println("          format version");
            pw.println("      --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
            pw.println("          between samples.");
            pw.println("      --streaming: stream the profiling output to the specified file.");
            pw.println("  profile stop [--user <USER_ID> current] <PROCESS>");
            pw.println("      Stop profiler on a process.  The given <PROCESS> argument");
            pw.println("        may be either a process name or pid.  Options are:");
            pw.println("      --user <USER_ID> | current: When supplying a process name,");
            pw.println("          specify user of process to profile; uses current user if not");
            pw.println("          specified.");
            pw.println("  dumpheap [--user <USER_ID> current] [-n] [-g] [-b <format>] ");
            pw.println("           <PROCESS> <FILE>");
            pw.println("      Dump the heap of a process.  The given <PROCESS> argument may");
            pw.println("        be either a process name or pid.  Options are:");
            pw.println("      -n: dump native heap instead of managed heap");
            pw.println("      -g: force GC before dumping the heap");
            pw.println("      -b <format>: dump contents of bitmaps in the format specified,");
            pw.println("         which can be \"png\", \"jpg\" or \"webp\".");
            pw.println("      --user <USER_ID> | current: When supplying a process name,");
            pw.println("          specify user of process to dump; uses current user if not specified.");
            pw.println("  set-debug-app [-w] [--persistent] <PACKAGE>");
            pw.println("      Set application <PACKAGE> to debug.  Options are:");
            pw.println("      -w: wait for debugger when application starts");
            pw.println("      --persistent: retain this value");
            pw.println("  clear-debug-app");
            pw.println("      Clear the previously set-debug-app.");
            pw.println("  set-watch-heap <PROCESS> <MEM-LIMIT>");
            pw.println("      Start monitoring pss size of <PROCESS>, if it is at or");
            pw.println("      above <HEAP-LIMIT> then a heap dump is collected for the user to report.");
            pw.println("  clear-watch-heap");
            pw.println("      Clear the previously set-watch-heap.");
            pw.println("  clear-start-info [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Clear process start-info for the given package.");
            pw.println("      Clear start-info for all packages if no package is provided.");
            pw.println("  start-info-detailed-monitoring [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Enable application start info detailed monitoring for the given package.");
            pw.println("      Disable if no package is supplied.");
            pw.println("  clear-exit-info [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Clear process exit-info for the given package.");
            pw.println("      Clear exit-info for all packages if no package is provided.");
            pw.println("  bug-report [--progress | --telephony]");
            pw.println("      Request bug report generation; will launch a notification");
            pw.println("        when done to select where it should be delivered. Options are:");
            pw.println("     --progress: will launch a notification right away to show its progress.");
            pw.println("     --telephony: will dump only telephony sections.");
            pw.println("  fgs-notification-rate-limit {enable | disable}");
            pw.println("     Enable/disable rate limit on FGS notification deferral policy.");
            pw.println("  force-stop [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Completely stop the given application package.");
            pw.println("  stop-app [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Stop an app and all of its services.  Unlike `force-stop` this does");
            pw.println("      not cancel the app's scheduled alarms and jobs.");
            pw.println("  crash [--user <USER_ID>] <PACKAGE|PID>");
            pw.println("      Induce a VM crash in the specified package or process");
            pw.println("  kill [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Kill all background processes associated with the given application.");
            pw.println("  kill-all");
            pw.println("      Kill all processes that are safe to kill (cached, etc).");
            pw.println("  make-uid-idle [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      If the given application's uid is in the background and waiting to");
            pw.println("      become idle (not allowing background services), do that now.");
            pw.println(
                    "  set-deterministic-uid-idle [--user <USER_ID> | all | current] <true|false>");
            pw.println("      If true, sets the timing of making UIDs idle consistent and");
            pw.println("      deterministic. If false, the timing will be variable depending on");
            pw.println("      other activity on the device. The default is false.");
            pw.println("  monitor [--gdb <port>] [-p <TARGET>] [-s] [-c] [-k]");
            pw.println("      Start monitoring for crashes or ANRs.");
            pw.println("      --gdb: start gdbserv on the given port at crash/ANR");
            pw.println("      -p: only show events related to a specific process / package");
            pw.println("      -s: simple mode, only show a summary line for each event");
            pw.println("      -c: assume the input is always [c]ontinue");
            pw.println("      -k: assume the input is always [k]ill");
            pw.println("         -c and -k are mutually exclusive.");
            pw.println("  watch-uids [--oom <uid>] [--mask <capabilities integer>]");
            pw.println("      Start watching for and reporting uid state changes.");
            pw.println("      --oom: specify a uid for which to report detailed change messages.");
            pw.println("      --mask: Specify PROCESS_CAPABILITY_XXX mask to report. ");
            pw.println("              By default, it only reports FOREGROUND_LOCATION (1)");
            pw.println("              FOREGROUND_CAMERA (2), FOREGROUND_MICROPHONE (4)");
            pw.println("              and NETWORK (8). New capabilities added on or after");
            pw.println("              Android UDC will not be reported by default.");
            pw.println("  hang [--allow-restart]");
            pw.println("      Hang the system.");
            pw.println("      --allow-restart: allow watchdog to perform normal system restart");
            pw.println("  restart");
            pw.println("      Restart the user-space system.");
            pw.println("  idle-maintenance");
            pw.println("      Perform idle maintenance now.");
            pw.println("  screen-compat [on|off] <PACKAGE>");
            pw.println("      Control screen compatibility mode of <PACKAGE>.");
            pw.println("  package-importance <PACKAGE>");
            pw.println("      Print current importance of <PACKAGE>.");
            pw.println("  to-uri [INTENT]");
            pw.println("      Print the given Intent specification as a URI.");
            pw.println("  to-intent-uri [INTENT]");
            pw.println("      Print the given Intent specification as an intent: URI.");
            pw.println("  to-app-uri [INTENT]");
            pw.println("      Print the given Intent specification as an android-app: URI.");
            pw.println("  switch-user <USER_ID>");
            pw.println("      Switch to put USER_ID in the foreground, starting");
            pw.println("      execution of that user if it is currently stopped.");
            pw.println("  get-current-user");
            pw.println("      Returns id of the current foreground user.");
            pw.println("  start-user [-w] [--display DISPLAY_ID] <USER_ID>");
            pw.println("      Start USER_ID in background if it is currently stopped;");
            pw.println("      use switch-user if you want to start the user in foreground.");
            pw.println("      -w: wait for start-user to complete and the user to be unlocked.");
            pw.println("      --display <DISPLAY_ID>: starts the user visible in that display, "
                    + "which allows the user to launch activities on it.");
            pw.println("        (not supported on all devices; typically only on automotive builds "
                    + "where the vehicle has passenger displays)");
            pw.println("  unlock-user <USER_ID>");
            pw.println("      Unlock the given user.  This will only work if the user doesn't");
            pw.println("      have an LSKF (PIN/pattern/password).");
            pw.println("  stop-user [-w] [-f] <USER_ID>");
            pw.println("      Stop execution of USER_ID, not allowing it to run any");
            pw.println("      code until a later explicit start or switch to it.");
            pw.println("      -w: wait for stop-user to complete.");
            pw.println("      -f: force stop, even if user has an unstoppable parent.");
            pw.println("  is-user-stopped <USER_ID>");
            pw.println("      Returns whether <USER_ID> has been stopped or not.");
            pw.println("  get-started-user-state <USER_ID>");
            pw.println("      Gets the current state of the given started user.");
            pw.println("  track-associations");
            pw.println("      Enable association tracking.");
            pw.println("  untrack-associations");
            pw.println("      Disable and clear association tracking.");
            pw.println("  get-uid-state <UID>");
            pw.println("      Gets the process state of an app given its <UID>.");
            pw.println("  attach-agent <PROCESS> <FILE>");
            pw.println("    Attach an agent to the specified <PROCESS>, which may be either a process name or a PID.");
            pw.println("  get-config [--days N] [--device] [--proto] [--display <DISPLAY_ID>]");
            pw.println("      Retrieve the configuration and any recent configurations of the device.");
            pw.println("      --days: also return last N days of configurations that have been seen.");
            pw.println("      --device: also output global device configuration info.");
            pw.println("      --proto: return result as a proto; does not include --days info.");
            pw.println("      --display: Specify for which display to run the command; if not ");
            pw.println("          specified then run for the default display.");
            pw.println("  supports-multiwindow");
            pw.println("      Returns true if the device supports multiwindow.");
            pw.println("  supports-split-screen-multi-window");
            pw.println("      Returns true if the device supports split screen multiwindow.");
            pw.println("  suppress-resize-config-changes <true|false>");
            pw.println("      Suppresses configuration changes due to user resizing an activity/task.");
            pw.println("  set-inactive [--user <USER_ID>] <PACKAGE> true|false");
            pw.println("      Sets the inactive state of an app.");
            pw.println("  get-inactive [--user <USER_ID>] <PACKAGE>");
            pw.println("      Returns the inactive state of an app.");
            pw.println("  set-standby-bucket [--user <USER_ID>] <PACKAGE> active|working_set|frequent|rare|restricted");
            pw.println("      Puts an app in the standby bucket.");
            pw.println("  get-standby-bucket [--user <USER_ID>] <PACKAGE>");
            pw.println("      Returns the standby bucket of an app.");
            pw.println("  send-trim-memory [--user <USER_ID>] <PROCESS>");
            pw.println("          [HIDDEN|RUNNING_MODERATE|BACKGROUND|RUNNING_LOW|MODERATE|RUNNING_CRITICAL|COMPLETE]");
            pw.println("      Send a memory trim event to a <PROCESS>.  May also supply a raw trim int level.");
            pw.println("  display [COMMAND] [...]: sub-commands for operating on displays.");
            pw.println("       move-stack <STACK_ID> <DISPLAY_ID>");
            pw.println("           Move <STACK_ID> from its current display to <DISPLAY_ID>.");
            pw.println("  stack [COMMAND] [...]: sub-commands for operating on activity stacks.");
            pw.println("       move-task <TASK_ID> <STACK_ID> [true|false]");
            pw.println("           Move <TASK_ID> from its current stack to the top (true) or");
            pw.println("           bottom (false) of <STACK_ID>.");
            pw.println("       list");
            pw.println("           List all of the activity stacks and their sizes.");
            pw.println("       info <WINDOWING_MODE> <ACTIVITY_TYPE>");
            pw.println("           Display the information about activity stack in <WINDOWING_MODE> and <ACTIVITY_TYPE>.");
            pw.println("       remove <STACK_ID>");
            pw.println("           Remove stack <STACK_ID>.");
            pw.println("  task [COMMAND] [...]: sub-commands for operating on activity tasks.");
            pw.println("       lock <TASK_ID>");
            pw.println("           Bring <TASK_ID> to the front and don't allow other tasks to run.");
            pw.println("       lock stop");
            pw.println("           End the current task lock.");
            pw.println("       resizeable <TASK_ID> [0|1|2|3]");
            pw.println("           Change resizeable mode of <TASK_ID> to one of the following:");
            pw.println("           0: unresizeable");
            pw.println("           1: crop_windows");
            pw.println("           2: resizeable");
            pw.println("           3: resizeable_and_pipable");
            pw.println("       resize <TASK_ID> <LEFT> <TOP> <RIGHT> <BOTTOM>");
            pw.println("           The task is resized only if it is in multi-window windowing");
            pw.println("           mode or freeform windowing mode.");
            pw.println("  update-appinfo <USER_ID> <PACKAGE_NAME> [<PACKAGE_NAME>...]");
            pw.println("      Update the ApplicationInfo objects of the listed packages for <USER_ID>");
            pw.println("      without restarting any processes.");
            pw.println("  write");
            pw.println("      Write all pending state to storage.");
            pw.println("  compat [COMMAND] [...]: sub-commands for toggling app-compat changes.");
            pw.println("         enable|disable [--no-kill] <CHANGE_ID|CHANGE_NAME> <PACKAGE_NAME>");
            pw.println("            Toggles a change either by id or by name for <PACKAGE_NAME>.");
            pw.println("            It kills <PACKAGE_NAME> (to allow the toggle to take effect) unless --no-kill is provided.");
            pw.println("         reset <CHANGE_ID|CHANGE_NAME> <PACKAGE_NAME>");
            pw.println("            Toggles a change either by id or by name for <PACKAGE_NAME>.");
            pw.println("            It kills <PACKAGE_NAME> (to allow the toggle to take effect).");
            pw.println("         enable-all|disable-all <targetSdkVersion> <PACKAGE_NAME>");
            pw.println("            Toggles all changes that are gated by <targetSdkVersion>.");
            pw.println("         reset-all [--no-kill] <PACKAGE_NAME>");
            pw.println("            Removes all existing overrides for all changes for ");
            pw.println("            <PACKAGE_NAME> (back to default behaviour).");
            pw.println("            It kills <PACKAGE_NAME> (to allow the toggle to take effect) unless --no-kill is provided.");
            pw.println("  memory-factor [command] [...]: sub-commands for overriding memory pressure factor");
            pw.println("         set <NORMAL|MODERATE|LOW|CRITICAL>");
            pw.println("            Overrides memory pressure factor. May also supply a raw int level");
            pw.println("         show");
            pw.println("            Shows the existing memory pressure factor");
            pw.println("         reset");
            pw.println("            Removes existing override for memory pressure factor");
            pw.println("  service-restart-backoff <COMMAND> [...]: sub-commands to toggle service restart backoff policy.");
            pw.println("         enable|disable <PACKAGE_NAME>");
            pw.println("            Toggles the restart backoff policy on/off for <PACKAGE_NAME>.");
            pw.println("         show <PACKAGE_NAME>");
            pw.println("            Shows the restart backoff policy state for <PACKAGE_NAME>.");
            pw.println("  get-isolated-pids <UID>");
            pw.println("         Get the PIDs of isolated processes with packages in this <UID>");
            pw.println("  set-stop-user-on-switch [true|false]");
            pw.println("         Sets whether the current user (and its profiles) should be stopped"
                    + " when switching to a different user.");
            pw.println("         Without arguments, it resets to the value defined by platform.");
            pw.println("  set-bg-abusive-uids [uid=percentage][,uid=percentage...]");
            pw.println("         Force setting the battery usage of the given UID.");
            pw.println("  set-bg-restriction-level [--user <USER_ID>] <PACKAGE> unrestricted|exempted|adaptive_bucket|restricted_bucket|background_restricted|hibernation");
            pw.println("         Set an app's background restriction level which in turn map to a app standby bucket.");
            pw.println("  get-bg-restriction-level [--user <USER_ID>] <PACKAGE>");
            pw.println("         Get an app's background restriction level.");
            pw.println("  list-displays-for-starting-users");
            pw.println("         Lists the id of displays that can be used to start users on "
                    + "background.");
            pw.println("  set-foreground-service-delegate [--user <USER_ID>] <PACKAGE> start|stop");
            pw.println("         Start/stop an app's foreground service delegate.");
            pw.println("  set-ignore-delivery-group-policy <ACTION>");
            pw.println("         Start ignoring delivery group policy set for a broadcast action");
            pw.println("  clear-ignore-delivery-group-policy <ACTION>");
            pw.println("         Stop ignoring delivery group policy set for a broadcast action");
            pw.println("  capabilities [--protobuf]");
            pw.println("         Output am supported features (text format). Options are:");
            pw.println("         --protobuf: format output using protobuffer");
            Intent.printIntentArgsHelp(pw, "");
        }
    }

    static void anrTimerHelp(PrintWriter pw) {
        // Return silently if tracing is not feature-enabled.
        if (!AnrTimer.traceFeatureEnabled()) return;

        String h = AnrTimer.traceTimers(new String[]{"help"});
        if (h == null) {
            return;
        }

        pw.println("  trace-timer <cmd>");
        for (String s : h.split("\n")) {
            pw.println("         " + s);
        }
    }
}
