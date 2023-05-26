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

import static android.Manifest.permission.BIND_VOICE_INTERACTION;
import static android.Manifest.permission.CHANGE_CONFIGURATION;
import static android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;
import static android.Manifest.permission.DETECT_SCREEN_CAPTURE;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;
import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.Manifest.permission.MANAGE_GAME_ACTIVITY;
import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.Manifest.permission.REMOVE_TASKS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.Manifest.permission.STOP_APP_SWITCHES;
import static android.app.ActivityManager.DROP_CLOSE_SYSTEM_DIALOGS;
import static android.app.ActivityManager.LOCK_DOWN_CLOSE_SYSTEM_DIALOGS;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.RESIZE_MODE_PRESERVE_WINDOW;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ConfigurationInfo.GL_ES_VERSION_UNDEFINED;
import static android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS;
import static android.content.pm.PackageManager.FEATURE_CANT_SAVE_STATE;
import static android.content.pm.PackageManager.FEATURE_COMPANION_DEVICE_SETUP;
import static android.content.pm.PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.FactoryTest.FACTORY_TEST_LOW_LEVEL;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RTL;
import static android.provider.Settings.Global.HIDE_ERROR_DIALOGS;
import static android.provider.Settings.System.FONT_SCALE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_DREAM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IMMERSIVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_LOCKTASK;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TASKS;
import static com.android.server.am.ActivityManagerService.STOCK_PM_FLAGS;
import static com.android.server.am.ActivityManagerServiceDumpActivitiesProto.ROOT_WINDOW_CONTAINER;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.CONFIG_WILL_CHANGE;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.CONTROLLER;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.CURRENT_TRACKER;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.Controller.IS_A_MONKEY;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.GLOBAL_CONFIGURATION;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.GOING_TO_SLEEP;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.HEAVY_WEIGHT_PROC;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.HOME_PROC;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.LAUNCHING_ACTIVITY;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.PREVIOUS_PROC;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.PREVIOUS_PROC_VISIBLE_TIME_MS;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.SCREEN_COMPAT_PACKAGES;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.ScreenCompatPackage.MODE;
import static com.android.server.am.ActivityManagerServiceDumpProcessesProto.ScreenCompatPackage.PACKAGE;
import static com.android.server.am.EventLogTags.writeBootProgressEnableScreen;
import static com.android.server.am.EventLogTags.writeConfigurationChanged;
import static com.android.server.am.StackTracesDumpHelper.ANR_TRACE_DIR;
import static com.android.server.am.StackTracesDumpHelper.dumpStackTraces;
import static com.android.server.wm.ActivityInterceptorCallback.MAINLINE_FIRST_ORDERED_ID;
import static com.android.server.wm.ActivityInterceptorCallback.MAINLINE_LAST_ORDERED_ID;
import static com.android.server.wm.ActivityInterceptorCallback.SYSTEM_FIRST_ORDERED_ID;
import static com.android.server.wm.ActivityInterceptorCallback.SYSTEM_LAST_ORDERED_ID;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ROOT_TASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_DATA;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;
import static com.android.server.wm.ActivityTaskManagerService.H.REPORT_TIME_TRACKER_MSG;
import static com.android.server.wm.ActivityTaskManagerService.UiHandler.DISMISS_DIALOG_UI_MSG;
import static com.android.server.wm.ActivityTaskSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_ONLY;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS;
import static com.android.server.wm.Task.REPARENT_KEEP_ROOT_TASK_AT_FRONT;
import static com.android.server.wm.WindowManagerService.MY_PID;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AnrController;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.Dialog;
import android.app.IActivityClientController;
import android.app.IActivityController;
import android.app.IActivityTaskManager;
import android.app.IAppTask;
import android.app.IApplicationThread;
import android.app.IAssistDataReceiver;
import android.app.INotificationManager;
import android.app.IScreenCaptureObserver;
import android.app.ITaskStackListener;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DeviceStateCache;
import android.app.assist.ActivityId;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.compat.CompatChanges;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.power.Mode;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.service.dreams.DreamActivity;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.sysprop.DisplayProperties;
import android.telecom.TelecomManager;
import android.text.format.TimeMigrationUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.IWindowOrganizerController;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.ProcessMap;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.TransferPipe;
import com.android.internal.policy.AttributeCache;
import com.android.internal.policy.KeyguardDismissCallback;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityManagerServiceDumpProcessesProto;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.AssistDataRequester;
import com.android.server.am.BaseErrorDialog;
import com.android.server.am.PendingIntentController;
import com.android.server.am.PendingIntentRecord;
import com.android.server.am.UserState;
import com.android.server.firewall.IntentFirewall;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.sdksandbox.SdkSandboxManagerLocal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.NeededUriGrants;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wallpaper.WallpaperManagerInternal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * System service for managing activities and their containers (task, displays,... ).
 *
 * {@hide}
 */
public class ActivityTaskManagerService extends IActivityTaskManager.Stub {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityTaskManagerService" : TAG_ATM;
    static final String TAG_ROOT_TASK = TAG + POSTFIX_ROOT_TASK;
    static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;

    // How long we wait until we timeout on key dispatching during instrumentation.
    static final long INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MILLIS = 60 * 1000;
    // How long we permit background activity starts after an activity in the process
    // started or finished.
    static final long ACTIVITY_BG_START_GRACE_PERIOD_MS = 10 * 1000;

    /**
     * The duration to keep a process in animating state (top scheduling group) when the
     * wakefulness is dozing (unlocking) or changing from awake to doze or sleep (locking).
     */
    private static final long DOZE_ANIMATING_STATE_RETAIN_TIME_MS = 2000;

    /** Used to indicate that an app transition should be animated. */
    static final boolean ANIMATE = true;

    /** Hardware-reported OpenGLES version. */
    final int GL_ES_VERSION;

    public static final String DUMP_ACTIVITIES_CMD = "activities";
    public static final String DUMP_ACTIVITIES_SHORT_CMD = "a";
    public static final String DUMP_LASTANR_CMD = "lastanr";
    public static final String DUMP_LASTANR_TRACES_CMD = "lastanr-traces";
    public static final String DUMP_STARTER_CMD = "starter";
    public static final String DUMP_CONTAINERS_CMD = "containers";
    public static final String DUMP_RECENTS_CMD = "recents";
    public static final String DUMP_RECENTS_SHORT_CMD = "r";
    public static final String DUMP_TOP_RESUMED_ACTIVITY = "top-resumed";
    public static final String DUMP_VISIBLE_ACTIVITIES = "visible";

    /** This activity is not being relaunched, or being relaunched for a non-resize reason. */
    public static final int RELAUNCH_REASON_NONE = 0;
    /** This activity is being relaunched due to windowing mode change. */
    public static final int RELAUNCH_REASON_WINDOWING_MODE_RESIZE = 1;
    /** This activity is being relaunched due to a free-resize operation. */
    public static final int RELAUNCH_REASON_FREE_RESIZE = 2;

    Context mContext;

    /**
     * This Context is themable and meant for UI display (AlertDialogs, etc.). The theme can
     * change at runtime. Use mContext for non-UI purposes.
     */
    private final Context mUiContext;
    final ActivityThread mSystemThread;
    H mH;
    UiHandler mUiHandler;
    ActivityManagerInternal mAmInternal;
    UriGrantsManagerInternal mUgmInternal;
    private PackageManagerInternal mPmInternal;
    /** The cached sys ui service component name from package manager. */
    private ComponentName mSysUiServiceComponent;
    private PermissionPolicyInternal mPermissionPolicyInternal;
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private WallpaperManagerInternal mWallpaperManagerInternal;
    @VisibleForTesting
    final ActivityTaskManagerInternal mInternal;
    private PowerManagerInternal mPowerManagerInternal;
    private UsageStatsManagerInternal mUsageStatsInternal;

    PendingIntentController mPendingIntentController;
    IntentFirewall mIntentFirewall;

    final VisibleActivityProcessTracker mVisibleActivityProcessTracker;

    /* Global service lock used by the package the owns this service. */
    final WindowManagerGlobalLock mGlobalLock = new WindowManagerGlobalLock();
    /**
     * It is the same instance as {@link #mGlobalLock}, just declared as a type that the
     * locked-region-code-injection does't recognize it. It is used to skip wrapping priority
     * booster for places that are already in the scope of another booster (e.g. computing oom-adj).
     *
     * @see WindowManagerThreadPriorityBooster
     */
    final Object mGlobalLockWithoutBoost = mGlobalLock;
    ActivityTaskSupervisor mTaskSupervisor;
    ActivityClientController mActivityClientController;
    RootWindowContainer mRootWindowContainer;
    WindowManagerService mWindowManager;
    private UserManagerService mUserManager;
    private AppOpsManager mAppOpsManager;
    /** All active uids in the system. */
    final MirrorActiveUids mActiveUids = new MirrorActiveUids();
    /** All processes currently running that might have a window organized by name. */
    final ProcessMap<WindowProcessController> mProcessNames = new ProcessMap<>();
    /** All processes we currently have running mapped by pid and uid */
    final WindowProcessControllerMap mProcessMap = new WindowProcessControllerMap();
    /** This is the process holding what we currently consider to be the "home" activity. */
    volatile WindowProcessController mHomeProcess;
    /** The currently running heavy-weight process, if any. */
    volatile WindowProcessController mHeavyWeightProcess;
    boolean mHasHeavyWeightFeature;
    boolean mHasLeanbackFeature;
    boolean mHasCompanionDeviceSetupFeature;
    /** The process of the top most activity. */
    volatile WindowProcessController mTopApp;
    /**
     * This is the process holding the activity the user last visited that is in a different process
     * from the one they are currently in.
     */
    volatile WindowProcessController mPreviousProcess;
    /** The time at which the previous process was last visible. */
    private long mPreviousProcessVisibleTime;

    /** It is set from keyguard-going-away to set-keyguard-shown. */
    static final int DEMOTE_TOP_REASON_DURING_UNLOCKING = 1;
    /** It is set if legacy recents animation is running. */
    static final int DEMOTE_TOP_REASON_ANIMATING_RECENTS = 1 << 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DEMOTE_TOP_REASON_DURING_UNLOCKING,
            DEMOTE_TOP_REASON_ANIMATING_RECENTS,
    })
    @interface DemoteTopReason {}

    /**
     * If non-zero, getTopProcessState() will
     * return {@link ActivityManager#PROCESS_STATE_IMPORTANT_FOREGROUND} to avoid top app from
     * preempting CPU while another process is running an important animation.
     */
    @DemoteTopReason
    volatile int mDemoteTopAppReasons;

    /** List of intents that were used to start the most recent tasks. */
    private RecentTasks mRecentTasks;
    /** State of external calls telling us if the device is awake or asleep. */
    private boolean mKeyguardShown = false;

    // VoiceInteraction session ID that changes for each new request except when
    // being called for multi-window assist in a single session.
    private int mViSessionId = 1000;

    // How long to wait in getAssistContextExtras for the activity and foreground services
    // to respond with the result.
    private static final int PENDING_ASSIST_EXTRAS_TIMEOUT = 500;

    // How long top wait when going through the modern assist (which doesn't need to block
    // on getting this result before starting to launch its UI).
    private static final int PENDING_ASSIST_EXTRAS_LONG_TIMEOUT = 2000;

    // How long to wait in getAutofillAssistStructure() for the activity to respond with the result.
    private static final int PENDING_AUTOFILL_ASSIST_STRUCTURE_TIMEOUT = 2000;

    private final ArrayList<PendingAssistExtras> mPendingAssistExtras = new ArrayList<>();

    // Keeps track of the active voice interaction service component, notified from
    // VoiceInteractionManagerService
    ComponentName mActiveVoiceInteractionServiceComponent;

    // A map userId and all its companion app uids
    private final Map<Integer, Set<Integer>> mCompanionAppUidsMap = new ArrayMap<>();

    VrController mVrController;
    KeyguardController mKeyguardController;
    private final ClientLifecycleManager mLifecycleManager;

    final BackNavigationController mBackNavigationController;

    private TaskChangeNotificationController mTaskChangeNotificationController;
    /** The controller for all operations related to locktask. */
    private LockTaskController mLockTaskController;
    private ActivityStartController mActivityStartController;
    private SparseArray<ActivityInterceptorCallback> mActivityInterceptorCallbacks =
            new SparseArray<>();
    PackageConfigPersister mPackageConfigPersister;

    boolean mSuppressResizeConfigChanges;

    final UpdateConfigurationResult mTmpUpdateConfigurationResult =
            new UpdateConfigurationResult();

    static final class UpdateConfigurationResult {
        // Configuration changes that were updated.
        int changes;
        // If the activity was relaunched to match the new configuration.
        boolean activityRelaunched;

        void reset() {
            changes = 0;
            activityRelaunched = false;
        }
    }

    /** Current sequencing integer of the configuration, for skipping old configurations. */
    private int mConfigurationSeq;

    /** Current sequencing integer of the asset changes, for skipping old resources overlays. */
    private int mGlobalAssetsSeq;

    // To cache the list of supported system locales
    private String[] mSupportedSystemLocales = null;

    /**
     * Temp object used when global and/or display override configuration is updated. It is also
     * sent to outer world instead of {@link #getGlobalConfiguration} because we don't trust
     * anyone...
     */
    private Configuration mTempConfig = new Configuration();

    /**
     * Whether normal application switches are allowed; a call to {@link #stopAppSwitches()
     * disables this.
     */
    private volatile int mAppSwitchesState = APP_SWITCH_ALLOW;

    // The duration of resuming foreground app switch from disallow.
    private static final long RESUME_FG_APP_SWITCH_MS = 500;

    /** App switch is not allowed. */
    static final int APP_SWITCH_DISALLOW = 0;

    /** App switch is allowed only if the activity launch was requested by a foreground app. */
    static final int APP_SWITCH_FG_ONLY = 1;

    /** App switch is allowed. */
    static final int APP_SWITCH_ALLOW = 2;

    @IntDef({
            APP_SWITCH_DISALLOW,
            APP_SWITCH_FG_ONLY,
            APP_SWITCH_ALLOW,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AppSwitchState {}

    /**
     * Last stop app switches time, apps finished before this time cannot start background activity
     * even if they are in grace period.
     */
    private volatile long mLastStopAppSwitchesTime;

    private final List<AnrController> mAnrController = new ArrayList<>();
    IActivityController mController = null;
    boolean mControllerIsAMonkey = false;

    final int mFactoryTest;

    /** Used to control how we initialize the service. */
    ComponentName mTopComponent;
    String mTopAction = Intent.ACTION_MAIN;
    String mTopData;

    /** Profiling app information. */
    String mProfileApp = null;
    WindowProcessController mProfileProc = null;
    ProfilerInfo mProfilerInfo = null;

    /**
     * Dump of the activity state at the time of the last ANR. Cleared after
     * {@link WindowManagerService#LAST_ANR_LIFETIME_DURATION_MSECS}
     */
    String mLastANRState;

    /**
     * Used to retain an update lock when the foreground activity is in
     * immersive mode.
     */
    private final UpdateLock mUpdateLock = new UpdateLock("immersive");

    /**
     * Packages that are being allowed to perform unrestricted app switches.  Mapping is
     * User -> Type -> uid.
     */
    final SparseArray<ArrayMap<String, Integer>> mAllowAppSwitchUids = new SparseArray<>();

    /** The dimensions of the thumbnails in the Recents UI. */
    private int mThumbnailWidth;
    private int mThumbnailHeight;

    /**
     * Flag that indicates if multi-window is enabled.
     *
     * For any particular form of multi-window to be enabled, generic multi-window must be enabled
     * in {@link com.android.internal.R.bool#config_supportsMultiWindow} config or
     * {@link Settings.Global#DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES} development option set.
     * At least one of the forms of multi-window must be enabled in order for this flag to be
     * initialized to 'true'.
     *
     * @see #mSupportsSplitScreenMultiWindow
     * @see #mSupportsFreeformWindowManagement
     * @see #mSupportsPictureInPicture
     * @see #mSupportsMultiDisplay
     */
    boolean mSupportsMultiWindow;
    boolean mSupportsSplitScreenMultiWindow;
    boolean mSupportsFreeformWindowManagement;
    boolean mSupportsPictureInPicture;
    boolean mSupportsExpandedPictureInPicture;
    boolean mSupportsMultiDisplay;
    boolean mForceResizableActivities;

    /** Development option to enable non resizable in multi window. */
    // TODO(b/176061101) change the default value to false.
    boolean mDevEnableNonResizableMultiWindow;

    /**
     * Whether the device supports non-resizable in multi windowing modes.
     * -1: The device doesn't support non-resizable in multi windowing modes.
     *  0: The device supports non-resizable in multi windowing modes only if this is a large
     *     screen (smallest width >= {@link WindowManager#LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP}).
     *  1: The device always supports non-resizable in multi windowing modes.
     */
    int mSupportsNonResizableMultiWindow;

    /**
     * Whether the device checks activity min width/height to determine if it can be shown in multi
     * windowing modes.
     * -1: The device ignores activity min width/height when determining if it can be shown in multi
     *     windowing modes.
     *  0: If it is a small screen (smallest width <
     *     {@link WindowManager#LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP}),
     *     the device compares the activity min width/height with the min multi windowing modes
     *     dimensions {@link #mMinPercentageMultiWindowSupportHeight} the device supports to
     *     determine whether the activity can be shown in multi windowing modes
     *  1: The device always compare the activity min width/height with the min multi windowing
     *     modes dimensions {@link #mMinPercentageMultiWindowSupportHeight} the device supports to
     *     determine whether it can be shown in multi windowing modes.
     */
    int mRespectsActivityMinWidthHeightMultiWindow;

    /**
     * This value is only used when the device checks activity min height to determine if it
     * can be shown in multi windowing modes.
     * If the activity min height is greater than this percentage of the display height in portrait,
     * it will not be allowed to be shown in multi windowing modes.
     * The value should be between [0 - 1].
     */
    float mMinPercentageMultiWindowSupportHeight;

    /**
     * This value is only used when the device checks activity min width to determine if it
     * can be shown in multi windowing modes.
     * If the activity min width is greater than this percentage of the display width in landscape,
     * it will not be allowed to be shown in multi windowing modes.
     * The value should be between [0 - 1].
     */
    float mMinPercentageMultiWindowSupportWidth;

    final List<ActivityTaskManagerInternal.ScreenObserver> mScreenObservers =
            Collections.synchronizedList(new ArrayList<>());

    // VR Vr2d Display Id.
    int mVr2dDisplayId = INVALID_DISPLAY;

    /**
     * Set while we are wanting to sleep, to prevent any
     * activities from being started/resumed.
     *
     * TODO(b/33594039): Clarify the actual state transitions represented by mSleeping.
     *
     * Currently mSleeping is set to true when transitioning into the sleep state, and remains true
     * while in the sleep state until there is a pending transition out of sleep, in which case
     * mSleeping is set to false, and remains false while awake.
     *
     * Whether mSleeping can quickly toggled between true/false without the device actually
     * display changing states is undefined.
     */
    private volatile boolean mSleeping;

    /**
     * The mActiveDreamComponent state is set by the {@link DreamManagerService} when it receives a
     * request to start/stop the dream. It is set to the active dream shortly before the
     * {@link DreamService} is started. It is set to null after the {@link DreamService} is stopped.
     */
    @Nullable
    private volatile ComponentName mActiveDreamComponent;

    /**
     * The process state used for processes that are running the top activities.
     * This changes between TOP and TOP_SLEEPING to following mSleeping.
     */
    volatile int mTopProcessState = ActivityManager.PROCESS_STATE_TOP;

    /** Whether to keep higher priority to launch app while device is sleeping. */
    private volatile boolean mRetainPowerModeAndTopProcessState;

    /** The timeout to restore power mode if {@link #mRetainPowerModeAndTopProcessState} is set. */
    private static final long POWER_MODE_UNKNOWN_VISIBILITY_TIMEOUT_MS = 1000;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            POWER_MODE_REASON_START_ACTIVITY,
            POWER_MODE_REASON_CHANGE_DISPLAY,
            POWER_MODE_REASON_UNKNOWN_VISIBILITY,
            POWER_MODE_REASON_ALL,
    })
    @interface PowerModeReason {}

    static final int POWER_MODE_REASON_START_ACTIVITY = 1 << 0;
    static final int POWER_MODE_REASON_CHANGE_DISPLAY = 1 << 1;
    /** @see UnknownAppVisibilityController */
    static final int POWER_MODE_REASON_UNKNOWN_VISIBILITY = 1 << 2;
    /** This can only be used by {@link #endLaunchPowerMode(int)}.*/
    static final int POWER_MODE_REASON_ALL = (1 << 2) - 1;

    /** The reasons to use {@link Mode#LAUNCH} power mode. */
    private @PowerModeReason int mLaunchPowerModeReasons;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            LAYOUT_REASON_CONFIG_CHANGED,
            LAYOUT_REASON_VISIBILITY_CHANGED,
    })
    @interface LayoutReason {
    }

    static final int LAYOUT_REASON_CONFIG_CHANGED = 0x1;
    static final int LAYOUT_REASON_VISIBILITY_CHANGED = 0x2;

    /** The reasons to perform surface placement. */
    @LayoutReason
    private int mLayoutReasons;

    // Whether we should show our dialogs (ANR, crash, etc) or just perform their default action
    // automatically. Important for devices without direct input devices.
    private boolean mShowDialogs = true;

    /** Set if we are shutting down the system, similar to sleeping. */
    boolean mShuttingDown = false;

    /**
     * We want to hold a wake lock while running a voice interaction session, since
     * this may happen with the screen off and we need to keep the CPU running to
     * be able to continue to interact with the user.
     */
    PowerManager.WakeLock mVoiceWakeLock;

    /**
     * Set while we are running a voice interaction. This overrides sleeping while it is active.
     */
    IVoiceInteractionSession mRunningVoice;

    /**
     * The last resumed activity. This is identical to the current resumed activity most
     * of the time but could be different when we're pausing one activity before we resume
     * another activity.
     */
    ActivityRecord mLastResumedActivity;

    /**
     * The activity that is currently being traced as the active resumed activity.
     *
     * @see #updateResumedAppTrace
     */
    @Nullable
    private ActivityRecord mTracedResumedActivity;

    /** If non-null, we are tracking the time the user spends in the currently focused app. */
    AppTimeTracker mCurAppTimeTracker;

    AppWarnings mAppWarnings;

    /**
     * Packages that the user has asked to have run in screen size
     * compatibility mode instead of filling the screen.
     */
    CompatModePackages mCompatModePackages;

    private SettingObserver mSettingsObserver;

    WindowOrganizerController mWindowOrganizerController;
    TaskOrganizerController mTaskOrganizerController;
    TaskFragmentOrganizerController mTaskFragmentOrganizerController;

    @Nullable
    private BackgroundActivityStartCallback mBackgroundActivityStartCallback;

    private int[] mAccessibilityServiceUids = new int[0];

    private int mDeviceOwnerUid = Process.INVALID_UID;

    private Set<Integer> mProfileOwnerUids = new ArraySet<Integer>();

    private final class SettingObserver extends ContentObserver {
        private final Uri mFontScaleUri = Settings.System.getUriFor(FONT_SCALE);
        private final Uri mHideErrorDialogsUri = Settings.Global.getUriFor(HIDE_ERROR_DIALOGS);
        private final Uri mFontWeightAdjustmentUri = Settings.Secure.getUriFor(
                Settings.Secure.FONT_WEIGHT_ADJUSTMENT);

        SettingObserver() {
            super(mH);
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mFontScaleUri, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mHideErrorDialogsUri, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    mFontWeightAdjustmentUri, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                @UserIdInt int userId) {
            for (Uri uri : uris) {
                if (mFontScaleUri.equals(uri)) {
                    updateFontScaleIfNeeded(userId);
                } else if (mHideErrorDialogsUri.equals(uri)) {
                    synchronized (mGlobalLock) {
                        updateShouldShowDialogsLocked(getGlobalConfiguration());
                    }
                } else if (mFontWeightAdjustmentUri.equals(uri)) {
                    updateFontWeightAdjustmentIfNeeded(userId);
                }
            }
        }
    }

    /** Indicates that the method may be invoked frequently or is sensitive to performance. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    @interface HotPath {
        int NONE = 0;
        int OOM_ADJUSTMENT = 1;
        int LRU_UPDATE = 2;
        int PROCESS_CHANGE = 3;
        int START_SERVICE = 4;

        int caller() default NONE;
    }

    private final Runnable mUpdateOomAdjRunnable = new Runnable() {
        @Override
        public void run() {
            mAmInternal.updateOomAdj(ActivityManagerInternal.OOM_ADJ_REASON_ACTIVITY);
        }
    };

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public ActivityTaskManagerService(Context context) {
        mContext = context;
        mFactoryTest = FactoryTest.getMode();
        mSystemThread = ActivityThread.currentActivityThread();
        mUiContext = mSystemThread.getSystemUiContext();
        mLifecycleManager = new ClientLifecycleManager();
        mVisibleActivityProcessTracker = new VisibleActivityProcessTracker(this);
        mInternal = new LocalService();
        GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version", GL_ES_VERSION_UNDEFINED);
        mWindowOrganizerController = new WindowOrganizerController(this);
        mTaskOrganizerController = mWindowOrganizerController.mTaskOrganizerController;
        mTaskFragmentOrganizerController =
                mWindowOrganizerController.mTaskFragmentOrganizerController;
        mBackNavigationController = new BackNavigationController();
    }

    public void onSystemReady() {
        synchronized (mGlobalLock) {
            final PackageManager pm = mContext.getPackageManager();
            mHasHeavyWeightFeature = pm.hasSystemFeature(FEATURE_CANT_SAVE_STATE);
            mHasLeanbackFeature = pm.hasSystemFeature(FEATURE_LEANBACK);
            mHasCompanionDeviceSetupFeature = pm.hasSystemFeature(FEATURE_COMPANION_DEVICE_SETUP);
            mVrController.onSystemReady();
            mRecentTasks.onSystemReadyLocked();
            mTaskSupervisor.onSystemReady();
            mActivityClientController.onSystemReady();
            // TODO(b/258792202) Cleanup once ASM is ready to launch
            ActivitySecurityModelFeatureFlags.initialize(mContext.getMainExecutor(), pm);
        }
    }

    public void onInitPowerManagement() {
        synchronized (mGlobalLock) {
            mTaskSupervisor.initPowerManagement();
            final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            mVoiceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*voice*");
            mVoiceWakeLock.setReferenceCounted(false);
        }
    }

    public void installSystemProviders() {
        mSettingsObserver = new SettingObserver();
    }

    public void retrieveSettings(ContentResolver resolver) {
        final boolean freeformWindowManagement =
                mContext.getPackageManager().hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                        || Settings.Global.getInt(
                        resolver, DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;

        final boolean supportsMultiWindow = ActivityTaskManager.supportsMultiWindow(mContext);
        final boolean supportsPictureInPicture = supportsMultiWindow &&
                mContext.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE);
        final boolean supportsExpandedPictureInPicture =
                supportsPictureInPicture && mContext.getPackageManager().hasSystemFeature(
                        FEATURE_EXPANDED_PICTURE_IN_PICTURE);
        final boolean supportsSplitScreenMultiWindow =
                ActivityTaskManager.supportsSplitScreenMultiWindow(mContext);
        final boolean supportsMultiDisplay = mContext.getPackageManager()
                .hasSystemFeature(FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS);
        final boolean forceRtl = Settings.Global.getInt(resolver, DEVELOPMENT_FORCE_RTL, 0) != 0;
        final boolean forceResizable = Settings.Global.getInt(
                resolver, DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0) != 0;
        final boolean devEnableNonResizableMultiWindow = Settings.Global.getInt(
                resolver, DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW, 0) != 0;
        final int supportsNonResizableMultiWindow = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_supportsNonResizableMultiWindow);
        final int respectsActivityMinWidthHeightMultiWindow = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_respectsActivityMinWidthHeightMultiWindow);
        final float minPercentageMultiWindowSupportHeight = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_minPercentageMultiWindowSupportHeight);
        final float minPercentageMultiWindowSupportWidth = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_minPercentageMultiWindowSupportWidth);

        // Transfer any global setting for forcing RTL layout, into a System Property
        DisplayProperties.debug_force_rtl(forceRtl);

        final Configuration configuration = new Configuration();
        Settings.System.getConfiguration(resolver, configuration);
        if (forceRtl) {
            // This will take care of setting the correct layout direction flags
            configuration.setLayoutDirection(configuration.locale);
        }

        synchronized (mGlobalLock) {
            mForceResizableActivities = forceResizable;
            mDevEnableNonResizableMultiWindow = devEnableNonResizableMultiWindow;
            mSupportsNonResizableMultiWindow = supportsNonResizableMultiWindow;
            mRespectsActivityMinWidthHeightMultiWindow = respectsActivityMinWidthHeightMultiWindow;
            mMinPercentageMultiWindowSupportHeight = minPercentageMultiWindowSupportHeight;
            mMinPercentageMultiWindowSupportWidth = minPercentageMultiWindowSupportWidth;
            final boolean multiWindowFormEnabled = freeformWindowManagement
                    || supportsSplitScreenMultiWindow
                    || supportsPictureInPicture
                    || supportsMultiDisplay;
            if ((supportsMultiWindow || forceResizable) && multiWindowFormEnabled) {
                mSupportsMultiWindow = true;
                mSupportsFreeformWindowManagement = freeformWindowManagement;
                mSupportsSplitScreenMultiWindow = supportsSplitScreenMultiWindow;
                mSupportsPictureInPicture = supportsPictureInPicture;
                mSupportsExpandedPictureInPicture = supportsExpandedPictureInPicture;
                mSupportsMultiDisplay = supportsMultiDisplay;
            } else {
                mSupportsMultiWindow = false;
                mSupportsFreeformWindowManagement = false;
                mSupportsSplitScreenMultiWindow = false;
                mSupportsPictureInPicture = false;
                mSupportsExpandedPictureInPicture = false;
                mSupportsMultiDisplay = false;
            }
            mWindowManager.mRoot.onSettingsRetrieved();
            // This happens before any activities are started, so we can change global configuration
            // in-place.
            updateConfigurationLocked(configuration, null, true);
            final Configuration globalConfig = getGlobalConfiguration();
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Initial config: %s", globalConfig);

            // Load resources only after the current configuration has been set.
            final Resources res = mContext.getResources();
            mThumbnailWidth = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.thumbnail_width);
            mThumbnailHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.thumbnail_height);
        }
    }

    public WindowManagerGlobalLock getGlobalLock() {
        return mGlobalLock;
    }

    /** For test purpose only. */
    @VisibleForTesting
    public ActivityTaskManagerInternal getAtmInternal() {
        return mInternal;
    }

    public void initialize(IntentFirewall intentFirewall, PendingIntentController intentController,
            Looper looper) {
        mH = new H(looper);
        mUiHandler = new UiHandler();
        mIntentFirewall = intentFirewall;
        final File systemDir = SystemServiceManager.ensureSystemDir();
        mAppWarnings = createAppWarnings(mUiContext, mH, mUiHandler, systemDir);
        mCompatModePackages = new CompatModePackages(this, systemDir, mH);
        mPendingIntentController = intentController;
        mTaskSupervisor = createTaskSupervisor();
        mActivityClientController = new ActivityClientController(this);

        mTaskChangeNotificationController =
                new TaskChangeNotificationController(mTaskSupervisor, mH);
        mLockTaskController = new LockTaskController(mContext, mTaskSupervisor, mH,
                mTaskChangeNotificationController);
        mActivityStartController = new ActivityStartController(this);
        setRecentTasks(new RecentTasks(this, mTaskSupervisor));
        mVrController = new VrController(mGlobalLock);
        mKeyguardController = mTaskSupervisor.getKeyguardController();
        mPackageConfigPersister = new PackageConfigPersister(mTaskSupervisor.mPersisterQueue, this);
    }

    public void onActivityManagerInternalAdded() {
        synchronized (mGlobalLock) {
            mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
            mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        }
    }

    int increaseConfigurationSeqLocked() {
        mConfigurationSeq = Math.max(++mConfigurationSeq, 1);
        return mConfigurationSeq;
    }

    protected ActivityTaskSupervisor createTaskSupervisor() {
        final ActivityTaskSupervisor supervisor = new ActivityTaskSupervisor(this,
                mH.getLooper());
        supervisor.initialize();
        return supervisor;
    }

    protected AppWarnings createAppWarnings(
            Context uiContext, Handler handler, Handler uiHandler, File systemDir) {
        return new AppWarnings(this, uiContext, handler, uiHandler, systemDir);
    }

    public void setWindowManager(WindowManagerService wm) {
        synchronized (mGlobalLock) {
            mWindowManager = wm;
            mRootWindowContainer = wm.mRoot;
            mWindowOrganizerController.mTransitionController.setWindowManager(wm);
            mTempConfig.setToDefaults();
            mTempConfig.setLocales(LocaleList.getDefault());
            mConfigurationSeq = mTempConfig.seq = 1;
            mRootWindowContainer.onConfigurationChanged(mTempConfig);
            mLockTaskController.setWindowManager(wm);
            mTaskSupervisor.setWindowManager(wm);
            mRootWindowContainer.setWindowManager(wm);
            mBackNavigationController.setWindowManager(wm);
        }
    }

    public void setUsageStatsManager(UsageStatsManagerInternal usageStatsManager) {
        synchronized (mGlobalLock) {
            mUsageStatsInternal = usageStatsManager;
        }
    }

    Context getUiContext() {
        return mUiContext;
    }

    UserManagerService getUserManager() {
        if (mUserManager == null) {
            IBinder b = ServiceManager.getService(Context.USER_SERVICE);
            mUserManager = (UserManagerService) IUserManager.Stub.asInterface(b);
        }
        return mUserManager;
    }

    AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        }
        return mAppOpsManager;
    }

    boolean hasUserRestriction(String restriction, int userId) {
        return getUserManager().hasUserRestriction(restriction, userId);
    }

    boolean hasSystemAlertWindowPermission(int callingUid, int callingPid,
            String callingPackage) {
        final int mode = getAppOpsManager().noteOpNoThrow(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                callingUid, callingPackage, /* featureId */ null, "");
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkPermission(Manifest.permission.SYSTEM_ALERT_WINDOW, callingPid, callingUid)
                    == PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @VisibleForTesting
    protected void setRecentTasks(RecentTasks recentTasks) {
        mRecentTasks = recentTasks;
        mTaskSupervisor.setRecentTasks(recentTasks);
    }

    RecentTasks getRecentTasks() {
        return mRecentTasks;
    }

    ClientLifecycleManager getLifecycleManager() {
        return mLifecycleManager;
    }

    ActivityStartController getActivityStartController() {
        return mActivityStartController;
    }

    TaskChangeNotificationController getTaskChangeNotificationController() {
        return mTaskChangeNotificationController;
    }

    LockTaskController getLockTaskController() {
        return mLockTaskController;
    }

    TransitionController getTransitionController() {
        return mWindowOrganizerController.getTransitionController();
    }

    /**
     * Return the global configuration used by the process corresponding to the input pid. This is
     * usually the global configuration with some overrides specific to that process.
     */
    Configuration getGlobalConfigurationForCallingPid() {
        final int pid = Binder.getCallingPid();
        return getGlobalConfigurationForPid(pid);
    }

    /**
     * Return the global configuration used by the process corresponding to the given pid.
     */
    Configuration getGlobalConfigurationForPid(int pid) {
        if (pid == MY_PID || pid < 0) {
            return getGlobalConfiguration();
        }
        synchronized (mGlobalLock) {
            final WindowProcessController app = mProcessMap.getProcess(pid);
            return app != null ? app.getConfiguration() : getGlobalConfiguration();
        }
    }

    /**
     * Return the device configuration info used by the process corresponding to the input pid.
     * The value is consistent with the global configuration for the process.
     */
    @Override
    public ConfigurationInfo getDeviceConfigurationInfo() {
        ConfigurationInfo config = new ConfigurationInfo();
        synchronized (mGlobalLock) {
            final Configuration globalConfig = getGlobalConfigurationForCallingPid();
            config.reqTouchScreen = globalConfig.touchscreen;
            config.reqKeyboardType = globalConfig.keyboard;
            config.reqNavigation = globalConfig.navigation;
            if (globalConfig.navigation == Configuration.NAVIGATION_DPAD
                    || globalConfig.navigation == Configuration.NAVIGATION_TRACKBALL) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
            }
            if (globalConfig.keyboard != Configuration.KEYBOARD_UNDEFINED
                    && globalConfig.keyboard != Configuration.KEYBOARD_NOKEYS) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
            }
            config.reqGlEsVersion = GL_ES_VERSION;
        }
        return config;
    }

    @Nullable
    public BackgroundActivityStartCallback getBackgroundActivityStartCallback() {
        return mBackgroundActivityStartCallback;
    }

    SparseArray<ActivityInterceptorCallback> getActivityInterceptorCallbacks() {
        return mActivityInterceptorCallbacks;
    }

    private void start() {
        LocalServices.addService(ActivityTaskManagerInternal.class, mInternal);
    }

    public static final class Lifecycle extends SystemService {
        private final ActivityTaskManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new ActivityTaskManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.ACTIVITY_TASK_SERVICE, mService);
            mService.start();
        }

        @Override
        public void onUserUnlocked(@NonNull TargetUser user) {
            synchronized (mService.getGlobalLock()) {
                mService.mTaskSupervisor.onUserUnlocked(user.getUserIdentifier());
            }
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            synchronized (mService.getGlobalLock()) {
                mService.mTaskSupervisor.mLaunchParamsPersister
                        .onCleanupUser(user.getUserIdentifier());
            }
        }

        public ActivityTaskManagerService getService() {
            return mService;
        }
    }

    @Override
    public final int startActivity(IApplicationThread caller, String callingPackage,
            String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions) {
        return startActivityAsUser(caller, callingPackage, callingFeatureId, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions,
                UserHandle.getCallingUserId());
    }

    @Override
    public final int startActivities(IApplicationThread caller, String callingPackage,
            String callingFeatureId, Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle bOptions, int userId) {
        assertPackageMatchesCallingUid(callingPackage);
        final String reason = "startActivities";
        enforceNotIsolatedCaller(reason);
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, reason);
        // TODO: Switch to user app stacks here.
        return getActivityStartController().startActivities(caller, -1, 0, -1, callingPackage,
                callingFeatureId, intents, resolvedTypes, resultTo,
                SafeActivityOptions.fromBundle(bOptions), userId, reason,
                null /* originatingPendingIntent */, BackgroundStartPrivileges.NONE);
    }

    @Override
    public int startActivityAsUser(IApplicationThread caller, String callingPackage,
            String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions, int userId) {
        return startActivityAsUser(caller, callingPackage, callingFeatureId, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, userId,
                true /*validateIncomingUser*/);
    }

    private int startActivityAsUser(IApplicationThread caller, String callingPackage,
            @Nullable String callingFeatureId, Intent intent, String resolvedType,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, Bundle bOptions, int userId, boolean validateIncomingUser) {

        final SafeActivityOptions opts = SafeActivityOptions.fromBundle(bOptions);

        assertPackageMatchesCallingUid(callingPackage);
        enforceNotIsolatedCaller("startActivityAsUser");

        if (intent != null && intent.isSandboxActivity(mContext)) {
            SdkSandboxManagerLocal sdkSandboxManagerLocal = LocalManagerRegistry.getManager(
                    SdkSandboxManagerLocal.class);
            sdkSandboxManagerLocal.enforceAllowedToHostSandboxedActivity(
                    intent, Binder.getCallingUid(), callingPackage
            );
        }

        if (Process.isSdkSandboxUid(Binder.getCallingUid())) {
            SdkSandboxManagerLocal sdkSandboxManagerLocal = LocalManagerRegistry.getManager(
                    SdkSandboxManagerLocal.class);
            if (sdkSandboxManagerLocal == null) {
                throw new IllegalStateException("SdkSandboxManagerLocal not found when starting"
                        + " an activity from an SDK sandbox uid.");
            }
            sdkSandboxManagerLocal.enforceAllowedToStartActivity(intent);
        }

        userId = getActivityStartController().checkTargetUser(userId, validateIncomingUser,
                Binder.getCallingPid(), Binder.getCallingUid(), "startActivityAsUser");

        // TODO: Switch to user app stacks here.
        return getActivityStartController().obtainStarter(intent, "startActivityAsUser")
                .setCaller(caller)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .setStartFlags(startFlags)
                .setProfilerInfo(profilerInfo)
                .setActivityOptions(opts)
                .setUserId(userId)
                .execute();

    }

    @Override
    public int startActivityIntentSender(IApplicationThread caller, IIntentSender target,
            IBinder allowlistToken, Intent fillInIntent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int flagsMask, int flagsValues, Bundle bOptions) {
        enforceNotIsolatedCaller("startActivityIntentSender");
        // Refuse possible leaked file descriptors
        if (fillInIntent != null && fillInIntent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (!(target instanceof PendingIntentRecord)) {
            throw new IllegalArgumentException("Bad PendingIntent object");
        }

        PendingIntentRecord pir = (PendingIntentRecord) target;

        synchronized (mGlobalLock) {
            // If this is coming from the currently resumed activity, it is
            // effectively saying that app switches are allowed at this point.
            final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
            if (topFocusedRootTask != null && topFocusedRootTask.getTopResumedActivity() != null
                    && topFocusedRootTask.getTopResumedActivity().info.applicationInfo.uid
                    == Binder.getCallingUid()) {
                mAppSwitchesState = APP_SWITCH_ALLOW;
            }
        }
        return pir.sendInner(caller, 0, fillInIntent, resolvedType, allowlistToken, null, null,
                resultTo, resultWho, requestCode, flagsMask, flagsValues, bOptions);
    }

    @Override
    public boolean startNextMatchingActivity(IBinder callingActivity, Intent intent,
            Bundle bOptions) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        SafeActivityOptions options = SafeActivityOptions.fromBundle(bOptions);

        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(callingActivity);
            if (r == null) {
                SafeActivityOptions.abort(options);
                return false;
            }
            if (!r.attachedToProcess()) {
                // The caller is not running...  d'oh!
                SafeActivityOptions.abort(options);
                return false;
            }
            intent = new Intent(intent);
            // The caller is not allowed to change the data.
            intent.setDataAndType(r.intent.getData(), r.intent.getType());
            // And we are resetting to find the next component...
            intent.setComponent(null);

            final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

            ActivityInfo aInfo = null;
            try {
                List<ResolveInfo> resolves =
                        AppGlobals.getPackageManager().queryIntentActivities(
                                intent, r.resolvedType,
                                PackageManager.MATCH_DEFAULT_ONLY | STOCK_PM_FLAGS,
                                UserHandle.getCallingUserId()).getList();

                // Look for the original activity in the list...
                final int N = resolves != null ? resolves.size() : 0;
                for (int i = 0; i < N; i++) {
                    ResolveInfo rInfo = resolves.get(i);
                    if (rInfo.activityInfo.packageName.equals(r.packageName)
                            && rInfo.activityInfo.name.equals(r.info.name)) {
                        // We found the current one...  the next matching is
                        // after it.
                        i++;
                        if (i < N) {
                            aInfo = resolves.get(i).activityInfo;
                        }
                        if (debug) {
                            Slog.v(TAG, "Next matching activity: found current " + r.packageName
                                    + "/" + r.info.name);
                            Slog.v(TAG, "Next matching activity: next is " + ((aInfo == null)
                                    ? "null" : aInfo.packageName + "/" + aInfo.name));
                        }
                        break;
                    }
                }
            } catch (RemoteException e) {
            }

            if (aInfo == null) {
                // Nobody who is next!
                SafeActivityOptions.abort(options);
                if (debug) Slog.d(TAG, "Next matching activity: nothing found");
                return false;
            }

            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            intent.setFlags(intent.getFlags() & ~(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | FLAG_ACTIVITY_NEW_TASK));

            // Okay now we need to start the new activity, replacing the currently running activity.
            // This is a little tricky because we want to start the new one as if the current one is
            // finished, but not finish the current one first so that there is no flicker.
            // And thus...
            final boolean wasFinishing = r.finishing;
            r.finishing = true;

            // Propagate reply information over to the new activity.
            final ActivityRecord resultTo = r.resultTo;
            final String resultWho = r.resultWho;
            final int requestCode = r.requestCode;
            r.resultTo = null;
            if (resultTo != null) {
                resultTo.removeResultsLocked(r, resultWho, requestCode);
            }

            final long origId = Binder.clearCallingIdentity();
            // TODO(b/64750076): Check if calling pid should really be -1.
            try {
                if (options == null) {
                    options = new SafeActivityOptions(ActivityOptions.makeBasic());
                }

                // Fixes b/230492947
                // Prevents background activity launch through #startNextMatchingActivity
                // An activity going into the background could still go back to the foreground
                // if the intent used matches both:
                // - the activity in the background
                // - a second activity.
                options.getOptions(r).setAvoidMoveToFront();
                final int res = getActivityStartController()
                        .obtainStarter(intent, "startNextMatchingActivity")
                        .setCaller(r.app.getThread())
                        .setResolvedType(r.resolvedType)
                        .setActivityInfo(aInfo)
                        .setResultTo(resultTo != null ? resultTo.token : null)
                        .setResultWho(resultWho)
                        .setRequestCode(requestCode)
                        .setCallingPid(-1)
                        .setCallingUid(r.launchedFromUid)
                        .setCallingPackage(r.launchedFromPackage)
                        .setCallingFeatureId(r.launchedFromFeatureId)
                        .setRealCallingPid(-1)
                        .setRealCallingUid(r.launchedFromUid)
                        .setActivityOptions(options)
                        .execute();
                r.finishing = wasFinishing;
                return res == ActivityManager.START_SUCCESS;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    boolean isDreaming() {
        return mActiveDreamComponent != null;
    }

    boolean canLaunchDreamActivity(String packageName) {
        if (mActiveDreamComponent == null || packageName == null) {
            ProtoLog.e(WM_DEBUG_DREAM, "Cannot launch dream activity due to invalid state. "
                    + "dream component: %s packageName: %s", mActiveDreamComponent, packageName);
            return false;
        }
        if (packageName.equals(mActiveDreamComponent.getPackageName())) {
            return true;
        }
        ProtoLog.e(WM_DEBUG_DREAM,
                "Dream packageName does not match active dream. Package %s does not match %s",
                packageName, String.valueOf(mActiveDreamComponent));
        return false;
    }

    private void enforceCallerIsDream(String callerPackageName) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (!canLaunchDreamActivity(callerPackageName)) {
                throw new SecurityException("The dream activity can be started only when the device"
                        + " is dreaming and only by the active dream package.");
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean startDreamActivity(@NonNull Intent intent) {
        assertPackageMatchesCallingUid(intent.getPackage());
        enforceCallerIsDream(intent.getPackage());

        final ActivityInfo a = new ActivityInfo();
        a.theme = com.android.internal.R.style.Theme_Dream;
        a.exported = true;
        a.name = DreamActivity.class.getName();
        a.enabled = true;
        a.launchMode = ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        a.persistableMode = ActivityInfo.PERSIST_NEVER;
        a.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        a.colorMode = ActivityInfo.COLOR_MODE_DEFAULT;
        a.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
        a.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        a.configChanges = 0xffffffff;

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchActivityType(ACTIVITY_TYPE_DREAM);

        synchronized (mGlobalLock) {
            final WindowProcessController process = mProcessMap.getProcess(Binder.getCallingPid());

            a.packageName = process.mInfo.packageName;
            a.applicationInfo = process.mInfo;
            a.processName = process.mName;
            a.uiOptions = process.mInfo.uiOptions;
            a.taskAffinity = "android:" + a.packageName + "/dream";

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();

            final long origId = Binder.clearCallingIdentity();
            try {
                getActivityStartController().obtainStarter(intent, "dream")
                        .setCallingUid(callingUid)
                        .setCallingPid(callingPid)
                        .setCallingPackage(intent.getPackage())
                        .setActivityInfo(a)
                        .setActivityOptions(createSafeActivityOptionsWithBalAllowed(options))
                        // To start the dream from background, we need to start it from a persistent
                        // system process. Here we set the real calling uid to the system server uid
                        .setRealCallingUid(Binder.getCallingUid())
                        .setBackgroundStartPrivileges(BackgroundStartPrivileges.ALLOW_BAL)
                        .execute();
                return true;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public final WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage,
            String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions, int userId) {
        assertPackageMatchesCallingUid(callingPackage);
        final WaitResult res = new WaitResult();
        enforceNotIsolatedCaller("startActivityAndWait");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, "startActivityAndWait");
        // TODO: Switch to user app stacks here.
        getActivityStartController().obtainStarter(intent, "startActivityAndWait")
                .setCaller(caller)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .setStartFlags(startFlags)
                .setActivityOptions(bOptions)
                .setUserId(userId)
                .setProfilerInfo(profilerInfo)
                .setWaitResult(res)
                .execute();
        return res;
    }

    @Override
    public final int startActivityWithConfig(IApplicationThread caller, String callingPackage,
            String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, Configuration config,
            Bundle bOptions, int userId) {
        assertPackageMatchesCallingUid(callingPackage);
        enforceNotIsolatedCaller("startActivityWithConfig");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                "startActivityWithConfig");
        // TODO: Switch to user app stacks here.
        return getActivityStartController().obtainStarter(intent, "startActivityWithConfig")
                .setCaller(caller)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .setStartFlags(startFlags)
                .setGlobalConfiguration(config)
                .setActivityOptions(bOptions)
                .setUserId(userId)
                .execute();
    }

    @Override
    public final int startActivityAsCaller(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions,
            boolean ignoreTargetSecurity, int userId) {
        // This is very dangerous -- it allows you to perform a start activity (including
        // permission grants) as any app that may launch one of your own activities.  So we only
        // allow this in two cases:
        // 1)  The calling process holds the signature permission START_ACTIVITY_AS_CALLER
        //
        // 2) The calling process is an activity belonging to the package "android" which is
        //    running as UID_SYSTEM or as the target UID (the activity which started the activity
        //    calling this method).

        final ActivityRecord sourceRecord;
        final int targetUid;
        final String targetPackage;
        final String targetFeatureId;
        final boolean isResolver;
        synchronized (mGlobalLock) {
            if (resultTo == null) {
                throw new SecurityException("Must be called from an activity");
            }

            sourceRecord = ActivityRecord.isInAnyTask(resultTo);
            if (sourceRecord == null) {
                throw new SecurityException("Called with bad activity token: " + resultTo);
            }
            if (sourceRecord.app == null) {
                throw new SecurityException("Called without a process attached to activity");
            }

            if (checkCallingPermission(Manifest.permission.START_ACTIVITY_AS_CALLER)
                    != PERMISSION_GRANTED) {
                // Whether called directly or from a delegate, the source activity must be from the
                // android package.
                if (!sourceRecord.info.packageName.equals("android")) {
                    throw new SecurityException("Must be called from an activity that is "
                            + "declared in the android package");
                }
                if (UserHandle.getAppId(sourceRecord.app.mUid) != SYSTEM_UID) {
                    // This is still okay, as long as this activity is running under the
                    // uid of the original calling activity.
                    if (sourceRecord.app.mUid != sourceRecord.launchedFromUid) {
                        throw new SecurityException(
                                "Calling activity in uid " + sourceRecord.app.mUid
                                        + " must be system uid or original calling uid "
                                        + sourceRecord.launchedFromUid);
                    }
                }
            }
            if (ignoreTargetSecurity) {
                if (intent.getComponent() == null) {
                    throw new SecurityException(
                            "Component must be specified with ignoreTargetSecurity");
                }
                if (intent.getSelector() != null) {
                    throw new SecurityException(
                            "Selector not allowed with ignoreTargetSecurity");
                }
            }
            targetUid = sourceRecord.launchedFromUid;
            targetPackage = sourceRecord.launchedFromPackage;
            targetFeatureId = sourceRecord.launchedFromFeatureId;
            isResolver = sourceRecord.isResolverOrChildActivity();
        }

        if (userId == UserHandle.USER_NULL) {
            userId = UserHandle.getUserId(sourceRecord.app.mUid);
        }

        // TODO: Switch to user app stacks here.
        try {
            return getActivityStartController().obtainStarter(intent, "startActivityAsCaller")
                    .setCallingUid(targetUid)
                    .setCallingPackage(targetPackage)
                    .setCallingFeatureId(targetFeatureId)
                    .setResolvedType(resolvedType)
                    .setResultTo(resultTo)
                    .setResultWho(resultWho)
                    .setRequestCode(requestCode)
                    .setStartFlags(startFlags)
                    .setActivityOptions(createSafeActivityOptionsWithBalAllowed(bOptions))
                    .setUserId(userId)
                    .setIgnoreTargetSecurity(ignoreTargetSecurity)
                    .setFilterCallingUid(isResolver ? 0 /* system */ : targetUid)
                    // The target may well be in the background, which would normally prevent it
                    // from starting an activity. Here we definitely want the start to succeed.
                    .setBackgroundStartPrivileges(BackgroundStartPrivileges.ALLOW_BAL)
                    .execute();
        } catch (SecurityException e) {
            // XXX need to figure out how to propagate to original app.
            // A SecurityException here is generally actually a fault of the original
            // calling activity (such as a fairly granting permissions), so propagate it
            // back to them.
            /*
            StringBuilder msg = new StringBuilder();
            msg.append("While launching");
            msg.append(intent.toString());
            msg.append(": ");
            msg.append(e.getMessage());
            */
            throw e;
        }
    }

    int handleIncomingUser(int callingPid, int callingUid, int userId, String name) {
        return mAmInternal.handleIncomingUser(callingPid, callingUid, userId, false /* allowAll */,
                ALLOW_NON_FULL, name, null /* callerPackage */);
    }

    @Override
    public int startVoiceActivity(String callingPackage, String callingFeatureId, int callingPid,
            int callingUid, Intent intent, String resolvedType, IVoiceInteractionSession session,
            IVoiceInteractor interactor, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions, int userId) {
        assertPackageMatchesCallingUid(callingPackage);
        mAmInternal.enforceCallingPermission(BIND_VOICE_INTERACTION, "startVoiceActivity()");
        if (session == null || interactor == null) {
            throw new NullPointerException("null session or interactor");
        }
        userId = handleIncomingUser(callingPid, callingUid, userId, "startVoiceActivity");
        // TODO: Switch to user app stacks here.
        return getActivityStartController().obtainStarter(intent, "startVoiceActivity")
                .setCallingUid(callingUid)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setVoiceSession(session)
                .setVoiceInteractor(interactor)
                .setStartFlags(startFlags)
                .setProfilerInfo(profilerInfo)
                .setActivityOptions(createSafeActivityOptionsWithBalAllowed(bOptions))
                .setUserId(userId)
                .setBackgroundStartPrivileges(BackgroundStartPrivileges.ALLOW_BAL)
                .execute();
    }

    @Override
    public String getVoiceInteractorPackageName(IBinder callingVoiceInteractor) {
        return LocalServices.getService(VoiceInteractionManagerInternal.class)
                .getVoiceInteractorPackageName(callingVoiceInteractor);
    }

    @Override
    public int startAssistantActivity(String callingPackage, @NonNull String callingFeatureId,
            int callingPid, int callingUid, Intent intent, String resolvedType, Bundle bOptions,
            int userId) {
        assertPackageMatchesCallingUid(callingPackage);
        mAmInternal.enforceCallingPermission(BIND_VOICE_INTERACTION, "startAssistantActivity()");
        userId = handleIncomingUser(callingPid, callingUid, userId, "startAssistantActivity");

        final long origId = Binder.clearCallingIdentity();
        try {
            return getActivityStartController().obtainStarter(intent, "startAssistantActivity")
                    .setCallingUid(callingUid)
                    .setCallingPackage(callingPackage)
                    .setCallingFeatureId(callingFeatureId)
                    .setResolvedType(resolvedType)
                    .setActivityOptions(createSafeActivityOptionsWithBalAllowed(bOptions))
                    .setUserId(userId)
                    .setBackgroundStartPrivileges(BackgroundStartPrivileges.ALLOW_BAL)
                    .execute();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Start the recents activity to perform the recents animation.
     *
     * @param intent                 The intent to start the recents activity.
     * @param eventTime              When the (touch) event is triggered to start recents activity.
     * @param recentsAnimationRunner Pass {@code null} to only preload the activity.
     */
    @Override
    public void startRecentsActivity(Intent intent, long eventTime,
            @Nullable IRecentsAnimationRunner recentsAnimationRunner) {
        enforceTaskPermission("startRecentsActivity()");
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ComponentName recentsComponent = mRecentTasks.getRecentsComponent();
                final String recentsFeatureId = mRecentTasks.getRecentsComponentFeatureId();
                final int recentsUid = mRecentTasks.getRecentsComponentUid();
                final WindowProcessController caller = getProcessController(callingPid, callingUid);

                // Start a new recents animation
                final RecentsAnimation anim = new RecentsAnimation(this, mTaskSupervisor,
                        getActivityStartController(), mWindowManager, intent, recentsComponent,
                        recentsFeatureId, recentsUid, caller);
                if (recentsAnimationRunner == null) {
                    anim.preloadRecentsActivity();
                } else {
                    anim.startRecentsActivity(recentsAnimationRunner, eventTime);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public final int startActivityFromRecents(int taskId, Bundle bOptions) {
        mAmInternal.enforceCallingPermission(START_TASKS_FROM_RECENTS,
                "startActivityFromRecents()");

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(bOptions);
        final long origId = Binder.clearCallingIdentity();
        try {
            return mTaskSupervisor.startActivityFromRecents(callingPid, callingUid, taskId,
                    safeOptions);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int startActivityFromGameSession(IApplicationThread caller, String callingPackage,
            String callingFeatureId, int callingPid, int callingUid, Intent intent, int taskId,
            int userId) {
        if (checkCallingPermission(MANAGE_GAME_ACTIVITY) != PERMISSION_GRANTED) {
            final String msg = "Permission Denial: startActivityFromGameSession() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + MANAGE_GAME_ACTIVITY;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        assertPackageMatchesCallingUid(callingPackage);

        final ActivityOptions activityOptions = ActivityOptions.makeBasic();
        activityOptions.setLaunchTaskId(taskId);

        userId = handleIncomingUser(callingPid, callingUid, userId, "startActivityFromGameSession");

        final long origId = Binder.clearCallingIdentity();
        try {
            return getActivityStartController()
                    .obtainStarter(intent, "startActivityFromGameSession")
                    .setCaller(caller)
                    .setCallingUid(callingUid)
                    .setCallingPid(callingPid)
                    .setCallingPackage(intent.getPackage())
                    .setCallingFeatureId(callingFeatureId)
                    .setUserId(userId)
                    .setActivityOptions(activityOptions.toBundle())
                    .setRealCallingUid(Binder.getCallingUid())
                    .execute();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public BackNavigationInfo startBackNavigation(
            RemoteCallback navigationObserver, BackAnimationAdapter adapter) {
        mAmInternal.enforceCallingPermission(START_TASKS_FROM_RECENTS,
                "startBackNavigation()");

        return mBackNavigationController.startBackNavigation(navigationObserver, adapter);
    }

    /**
     * Public API to check if the client is allowed to start an activity on specified display.
     *
     * If the target display is private or virtual, some restrictions will apply.
     *
     * @param displayId    Target display id.
     * @param intent       Intent used to launch the activity.
     * @param resolvedType The MIME type of the intent.
     * @param userId       The id of the user for whom the call is made.
     * @return {@code true} if a call to start an activity on the target display should succeed and
     * no {@link SecurityException} will be thrown, {@code false} otherwise.
     */
    @Override
    public final boolean isActivityStartAllowedOnDisplay(int displayId, Intent intent,
            String resolvedType, int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long origId = Binder.clearCallingIdentity();

        try {
            // Collect information about the target of the Intent.
            final ActivityInfo aInfo = resolveActivityInfoForIntent(intent, resolvedType, userId,
                    callingUid, callingPid);

            synchronized (mGlobalLock) {
                return mTaskSupervisor.canPlaceEntityOnDisplay(displayId, callingPid, callingUid,
                        aInfo);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    ActivityInfo resolveActivityInfoForIntent(Intent intent, String resolvedType,
            int userId, int callingUid, int callingPid) {
        ActivityInfo aInfo = mTaskSupervisor.resolveActivity(intent, resolvedType,
                0 /* startFlags */, null /* profilerInfo */, userId,
                ActivityStarter.computeResolveFilterUid(callingUid, callingUid,
                        UserHandle.USER_NULL), callingPid);
        return mAmInternal.getActivityInfoForUser(aInfo, userId);
    }

    @Override
    public IActivityClientController getActivityClientController() {
        return mActivityClientController;
    }

    void applyUpdateLockStateLocked(ActivityRecord r) {
        // Modifications to the UpdateLock state are done on our handler, outside
        // the activity manager's locks.  The new state is determined based on the
        // state *now* of the relevant activity record.  The object is passed to
        // the handler solely for logging detail, not to be consulted/modified.
        final boolean nextState = r != null && r.immersive;
        mH.post(() -> {
            if (mUpdateLock.isHeld() != nextState) {
                ProtoLog.d(WM_DEBUG_IMMERSIVE, "Applying new update lock state '%s' for %s",
                        nextState, r);
                if (nextState) {
                    mUpdateLock.acquire();
                } else {
                    mUpdateLock.release();
                }
            }
        });
    }

    @Override
    public boolean isTopActivityImmersive() {
        enforceNotIsolatedCaller("isTopActivityImmersive");
        synchronized (mGlobalLock) {
            final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
            if (topFocusedRootTask == null) {
                return false;
            }

            final ActivityRecord r = topFocusedRootTask.topRunningActivity();
            return r != null && r.immersive;
        }
    }

    @Override
    public int getFrontActivityScreenCompatMode() {
        enforceNotIsolatedCaller("getFrontActivityScreenCompatMode");
        synchronized (mGlobalLock) {
            final Task rootTask = getTopDisplayFocusedRootTask();
            final ActivityRecord r = rootTask != null ? rootTask.topRunningActivity() : null;
            if (r == null) {
                return ActivityManager.COMPAT_MODE_UNKNOWN;
            }
            return mCompatModePackages.computeCompatModeLocked(r.info.applicationInfo);
        }
    }

    @Override
    public void setFrontActivityScreenCompatMode(int mode) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setFrontActivityScreenCompatMode");
        ApplicationInfo ai;
        synchronized (mGlobalLock) {
            final Task rootTask = getTopDisplayFocusedRootTask();
            final ActivityRecord r = rootTask != null ? rootTask.topRunningActivity() : null;
            if (r == null) {
                Slog.w(TAG, "setFrontActivityScreenCompatMode failed: no top activity");
                return;
            }
            ai = r.info.applicationInfo;
            mCompatModePackages.setPackageScreenCompatModeLocked(ai, mode);
        }
    }


    @Override
    public RootTaskInfo getFocusedRootTaskInfo() throws RemoteException {
        enforceTaskPermission("getFocusedRootTaskInfo()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                Task focusedRootTask = getTopDisplayFocusedRootTask();
                if (focusedRootTask != null) {
                    return mRootWindowContainer.getRootTaskInfo(focusedRootTask.mTaskId);
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setFocusedRootTask(int taskId) {
        enforceTaskPermission("setFocusedRootTask()");
        ProtoLog.d(WM_DEBUG_FOCUS, "setFocusedRootTask: taskId=%d", taskId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.getRootTask(taskId);
                if (task == null) {
                    Slog.w(TAG, "setFocusedRootTask: No task with id=" + taskId);
                    return;
                }
                final ActivityRecord r = task.topRunningActivity();
                if (r != null && r.moveFocusableActivityToTop("setFocusedRootTask")) {
                    mRootWindowContainer.resumeFocusedTasksTopActivities();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void setFocusedTask(int taskId) {
        enforceTaskPermission("setFocusedTask()");
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                setFocusedTask(taskId, null /* touchedActivity */);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void focusTopTask(int displayId) {
        enforceTaskPermission("focusTopTask()");
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mRootWindowContainer.getDisplayContent(displayId);
                if (dc == null) return;
                final Task task = dc.getTask((t) -> t.isLeafTask() && t.isTopActivityFocusable(),
                        true /*  traverseTopToBottom */);
                if (task == null) return;
                setFocusedTask(task.mTaskId, null /* touchedActivity */);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    void setFocusedTask(int taskId, ActivityRecord touchedActivity) {
        ProtoLog.d(WM_DEBUG_FOCUS, "setFocusedTask: taskId=%d touchedActivity=%s", taskId,
                touchedActivity);
        final Task task = mRootWindowContainer.anyTaskForId(taskId, MATCH_ATTACHED_TASK_ONLY);
        if (task == null) {
            return;
        }
        final ActivityRecord r = task.topRunningActivityLocked();
        if (r == null) {
            return;
        }

        if ((touchedActivity == null || r == touchedActivity) && r.isState(RESUMED)
                && r == mRootWindowContainer.getTopResumedActivity()) {
            setLastResumedActivityUncheckLocked(r, "setFocusedTask-alreadyTop");
            return;
        }
        final Transition transition = (getTransitionController().isCollecting()
                || !getTransitionController().isShellTransitionsEnabled()) ? null
                : getTransitionController().createTransition(TRANSIT_TO_FRONT);
        if (transition != null) {
            // Set ready before doing anything. If order does change, then that will set it unready
            // so that we wait for the new lifecycles to complete.
            transition.setReady(task, true /* ready */);
        }
        final boolean movedToTop = r.moveFocusableActivityToTop("setFocusedTask");
        if (movedToTop) {
            if (transition != null) {
                getTransitionController().requestStartTransition(
                        transition, null /* startTask */, null /* remote */, null /* display */);
            }
            mRootWindowContainer.resumeFocusedTasksTopActivities();
        } else if (touchedActivity != null && touchedActivity.isFocusable()) {
            final TaskFragment parent = touchedActivity.getTaskFragment();
            if (parent != null && parent.isEmbedded()) {
                // Set the focused app directly if the focused window is currently embedded
                final DisplayContent displayContent = touchedActivity.getDisplayContent();
                displayContent.setFocusedApp(touchedActivity);
                mWindowManager.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                        true /* updateInputWindows */);
            }
        }
        if (transition != null && !movedToTop) {
            // No order changes and focus-changes, alone, aren't captured in transitions.
            transition.abort();
        }
    }

    @Override
    public boolean removeTask(int taskId) {
        mAmInternal.enforceCallingPermission(REMOVE_TASKS, "removeTask()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    Slog.w(TAG, "removeTask: No task remove with id=" + taskId);
                    return false;
                }

                if (task.isLeafTask()) {
                    mTaskSupervisor.removeTask(task, true, REMOVE_FROM_RECENTS, "remove-task");
                } else {
                    mTaskSupervisor.removeRootTask(task);
                }
                return true;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void removeAllVisibleRecentTasks() {
        mAmInternal.enforceCallingPermission(REMOVE_TASKS, "removeAllVisibleRecentTasks()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                getRecentTasks().removeAllVisibleTasks(mAmInternal.getCurrentUserId());
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public Rect getTaskBounds(int taskId) {
        enforceTaskPermission("getTaskBounds()");
        final long ident = Binder.clearCallingIdentity();
        Rect rect = new Rect();
        try {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    Slog.w(TAG, "getTaskBounds: taskId=" + taskId + " not found");
                    return rect;
                }
                if (task.getParent() != null) {
                    rect.set(task.getBounds());
                } else if (task.mLastNonFullscreenBounds != null) {
                    rect.set(task.mLastNonFullscreenBounds);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return rect;
    }

    @Override
    public ActivityManager.TaskDescription getTaskDescription(int id) {
        synchronized (mGlobalLock) {
            enforceTaskPermission("getTaskDescription()");
            final Task tr = mRootWindowContainer.anyTaskForId(id,
                    MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
            if (tr != null) {
                return tr.getTaskDescription();
            }
        }
        return null;
    }

    /**
     * Sets the locusId for a particular activity.
     *
     * @param locusId the locusId to set.
     * @param appToken the ActivityRecord's appToken.
     */
    public void setLocusId(LocusId locusId, IBinder appToken) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(appToken);
            if (r != null) {
                r.setLocusId(locusId);
            }
        }
    }

    NeededUriGrants collectGrants(Intent intent, ActivityRecord target) {
        if (target != null) {
            return mUgmInternal.checkGrantUriPermissionFromIntent(intent,
                    Binder.getCallingUid(), target.packageName, target.mUserId);
        } else {
            return null;
        }
    }

    @Override
    public void unhandledBack() {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.FORCE_BACK,
                "unhandledBack()");

        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
                if (topFocusedRootTask != null) {
                    topFocusedRootTask.unhandledBackLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    /**
     * TODO: Add mController hook
     */
    @Override
    public void moveTaskToFront(IApplicationThread appThread, String callingPackage, int taskId,
            int flags, Bundle bOptions) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToFront()");

        ProtoLog.d(WM_DEBUG_TASKS, "moveTaskToFront: moving taskId=%d", taskId);
        synchronized (mGlobalLock) {
            moveTaskToFrontLocked(appThread, callingPackage, taskId, flags,
                    SafeActivityOptions.fromBundle(bOptions));
        }
    }

    void moveTaskToFrontLocked(@Nullable IApplicationThread appThread,
            @Nullable String callingPackage, int taskId, int flags, SafeActivityOptions options) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        assertPackageMatchesCallingUid(callingPackage);

        final long origId = Binder.clearCallingIdentity();
        WindowProcessController callerApp = null;
        if (appThread != null) {
            callerApp = getProcessController(appThread);
        }
        final BackgroundActivityStartController balController =
                getActivityStartController().getBackgroundActivityLaunchController();
        if (balController.shouldAbortBackgroundActivityStart(
                callingUid,
                callingPid,
                callingPackage,
                -1,
                -1,
                callerApp,
                null,
                BackgroundStartPrivileges.NONE,
                null,
                null)) {
            if (!isBackgroundActivityStartsEnabled()) {
                return;
            }
        }
        try {
            final Task task = mRootWindowContainer.anyTaskForId(taskId);
            if (task == null) {
                ProtoLog.d(WM_DEBUG_TASKS, "Could not find task for id: %d", taskId);
                SafeActivityOptions.abort(options);
                return;
            }
            if (getLockTaskController().isLockTaskModeViolation(task)) {
                Slog.e(TAG, "moveTaskToFront: Attempt to violate Lock Task Mode");
                SafeActivityOptions.abort(options);
                return;
            }
            ActivityOptions realOptions = options != null
                    ? options.getOptions(mTaskSupervisor)
                    : null;
            mTaskSupervisor.findTaskToMoveToFront(task, flags, realOptions, "moveTaskToFront",
                    false /* forceNonResizable */);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Return true if callingUid is system, or packageName belongs to that callingUid.
     */
    private boolean isSameApp(int callingUid, @Nullable String packageName) {
        if (callingUid != 0 && callingUid != SYSTEM_UID) {
            return mPmInternal.isSameApp(packageName, callingUid, UserHandle.getUserId(callingUid));
        }
        return true;
    }

    /**
     * Checks that the provided package name matches the current calling UID, throws a security
     * exception if it doesn't.
     */
    void assertPackageMatchesCallingUid(@Nullable String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (isSameApp(callingUid, packageName)) {
            return;
        }
        final String msg = "Permission Denial: package=" + packageName
                + " does not belong to uid=" + callingUid;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    /**
     * Return true if app switching is allowed.
     */
    @AppSwitchState int getBalAppSwitchesState() {
        return mAppSwitchesState;
    }

    /** Register an {@link AnrController} to control the ANR dialog behavior */
    public void registerAnrController(AnrController controller) {
        synchronized (mGlobalLock) {
            mAnrController.add(controller);
        }
    }

    /** Unregister an {@link AnrController} */
    public void unregisterAnrController(AnrController controller) {
        synchronized (mGlobalLock) {
            mAnrController.remove(controller);
        }
    }

    /**
     * @return the controller with the max ANR delay from all registered
     * {@link AnrController} instances
     */
    @Nullable
    public AnrController getAnrController(ApplicationInfo info) {
        if (info == null || info.packageName == null) {
            return null;
        }

        final ArrayList<AnrController> controllers;
        synchronized (mGlobalLock) {
            controllers = new ArrayList<>(mAnrController);
        }

        final String packageName = info.packageName;
        final int uid = info.uid;
        long maxDelayMs = 0;
        AnrController controllerWithMaxDelay = null;

        for (AnrController controller : controllers) {
            long delayMs = controller.getAnrDelayMillis(packageName, uid);
            if (delayMs > 0 && delayMs > maxDelayMs) {
                controllerWithMaxDelay = controller;
                maxDelayMs = delayMs;
            }
        }

        return controllerWithMaxDelay;
    }

    @Override
    public void setActivityController(IActivityController controller, boolean imAMonkey) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "setActivityController()");
        synchronized (mGlobalLock) {
            mController = controller;
            mControllerIsAMonkey = imAMonkey;
            Watchdog.getInstance().setActivityController(controller);
        }
    }

    public boolean isControllerAMonkey() {
        synchronized (mGlobalLock) {
            return mController != null && mControllerIsAMonkey;
        }
    }

    /**
     * Gets info of running tasks up to the given number.
     *
     * @param maxNum the maximum number of task info returned by this method. If the total number of
     *               running tasks is larger than it then there is no guarantee which task will be
     *               left out.
     * @return a list of {@link ActivityManager.RunningTaskInfo} with up to {@code maxNum} items
     */
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return getTasks(maxNum, false /* filterForVisibleRecents */, false /* keepIntentExtra */,
                INVALID_DISPLAY);
    }

    /**
     * @param filterOnlyVisibleRecents whether to filter the tasks based on whether they would ever
     *                                 be visible in the recent task list in systemui
     */
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum,
            boolean filterOnlyVisibleRecents, boolean keepIntentExtra) {
        return getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra, INVALID_DISPLAY);
    }

    /**
     * @param displayId the target display id, or {@link INVALID_DISPLAY} not to filter by displayId
     */
    @Override
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum,
            boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        int flags = filterOnlyVisibleRecents ? RunningTasks.FLAG_FILTER_ONLY_VISIBLE_RECENTS : 0;
        flags |= (keepIntentExtra ? RunningTasks.FLAG_KEEP_INTENT_EXTRA : 0);
        final boolean crossUser = isCrossUserAllowed(callingPid, callingUid);
        flags |= (crossUser ? RunningTasks.FLAG_CROSS_USERS : 0);
        final int[] profileIds = getUserManager().getProfileIds(
                UserHandle.getUserId(callingUid), true);
        ArraySet<Integer> callingProfileIds = new ArraySet<>();
        for (int i = 0; i < profileIds.length; i++) {
            callingProfileIds.add(profileIds[i]);
        }
        ArrayList<ActivityManager.RunningTaskInfo> list = new ArrayList<>();

        synchronized (mGlobalLock) {
            if (DEBUG_ALL) Slog.v(TAG, "getTasks: max=" + maxNum);

            final boolean allowed = isGetTasksAllowed("getTasks", callingPid, callingUid);
            flags |= (allowed ? RunningTasks.FLAG_ALLOWED : 0);
            mRootWindowContainer.getRunningTasks(
                    maxNum, list, flags, callingUid, callingProfileIds, displayId);
        }

        return list;
    }

    @Override
    public void moveTaskToRootTask(int taskId, int rootTaskId, boolean toTop) {
        enforceTaskPermission("moveTaskToRootTask()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final Task task = mRootWindowContainer.anyTaskForId(taskId);
                if (task == null) {
                    Slog.w(TAG, "moveTaskToRootTask: No task for id=" + taskId);
                    return;
                }

                ProtoLog.d(WM_DEBUG_TASKS, "moveTaskToRootTask: moving task=%d to "
                        + "rootTaskId=%d toTop=%b", taskId, rootTaskId, toTop);

                final Task rootTask = mRootWindowContainer.getRootTask(rootTaskId);
                if (rootTask == null) {
                    throw new IllegalStateException(
                            "moveTaskToRootTask: No rootTask for rootTaskId=" + rootTaskId);
                }
                if (!rootTask.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("moveTaskToRootTask: Attempt to move task "
                            + taskId + " to rootTask " + rootTaskId);
                }
                task.reparent(rootTask, toTop, REPARENT_KEEP_ROOT_TASK_AT_FRONT, ANIMATE,
                        !DEFER_RESUME, "moveTaskToRootTask");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Removes root tasks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    @Override
    public void removeRootTasksInWindowingModes(int[] windowingModes) {
        enforceTaskPermission("removeRootTasksInWindowingModes()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mRootWindowContainer.removeRootTasksInWindowingModes(windowingModes);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void removeRootTasksWithActivityTypes(int[] activityTypes) {
        enforceTaskPermission("removeRootTasksWithActivityTypes()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mRootWindowContainer.removeRootTasksWithActivityTypes(activityTypes);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags,
            int userId) {
        final int callingUid = Binder.getCallingUid();
        userId = handleIncomingUser(Binder.getCallingPid(), callingUid, userId, "getRecentTasks");
        final boolean allowed = isGetTasksAllowed("getRecentTasks", Binder.getCallingPid(),
                callingUid);
        synchronized (mGlobalLock) {
            return mRecentTasks.getRecentTasks(maxNum, flags, allowed, userId, callingUid);
        }
    }

    @Override
    public List<RootTaskInfo> getAllRootTaskInfos() {
        enforceTaskPermission("getAllRootTaskInfos()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.getAllRootTaskInfos(INVALID_DISPLAY);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public RootTaskInfo getRootTaskInfo(int windowingMode, int activityType) {
        enforceTaskPermission("getRootTaskInfo()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.getRootTaskInfo(windowingMode, activityType);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<RootTaskInfo> getAllRootTaskInfosOnDisplay(int displayId) {
        enforceTaskPermission("getAllRootTaskInfosOnDisplay()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.getAllRootTaskInfos(displayId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public RootTaskInfo getRootTaskInfoOnDisplay(int windowingMode, int activityType,
            int displayId) {
        enforceTaskPermission("getRootTaskInfoOnDisplay()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.getRootTaskInfo(windowingMode, activityType, displayId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void cancelRecentsAnimation(boolean restoreHomeRootTaskPosition) {
        enforceTaskPermission("cancelRecentsAnimation()");
        final long callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                // Cancel the recents animation synchronously (do not hold the WM lock)
                mWindowManager.cancelRecentsAnimation(restoreHomeRootTaskPosition
                        ? REORDER_MOVE_TO_ORIGINAL_POSITION
                        : REORDER_KEEP_IN_PLACE, "cancelRecentsAnimation/uid=" + callingUid);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void startSystemLockTaskMode(int taskId) {
        enforceTaskPermission("startSystemLockTaskMode");
        // This makes inner call to look as if it was initiated by system.
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_ONLY);
                if (task == null) {
                    return;
                }

                // When starting lock task mode the root task must be in front and focused
                task.getRootTask().moveToFront("startSystemLockTaskMode");
                startLockTaskMode(task, true /* isSystemCaller */);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * This API should be called by SystemUI only when user perform certain action to dismiss
     * lock task mode. We should only dismiss pinned lock task mode in this case.
     */
    @Override
    public void stopSystemLockTaskMode() throws RemoteException {
        enforceTaskPermission("stopSystemLockTaskMode");
        stopLockTaskModeInternal(null, true /* isSystemCaller */);
    }

    void startLockTaskMode(@Nullable Task task, boolean isSystemCaller) {
        ProtoLog.w(WM_DEBUG_LOCKTASK, "startLockTaskMode: %s", task);
        if (task == null || task.mLockTaskAuth == LOCK_TASK_AUTH_DONT_LOCK) {
            return;
        }

        final Task rootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
        if (rootTask == null || task != rootTask.getTopMostTask()) {
            throw new IllegalArgumentException("Invalid task, not in foreground");
        }

        // {@code isSystemCaller} is used to distinguish whether this request is initiated by the
        // system or a specific app.
        // * System-initiated requests will only start the pinned mode (screen pinning)
        // * App-initiated requests
        //   - will put the device in fully locked mode (LockTask), if the app is allowlisted
        //   - will start the pinned mode, otherwise
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            // When a task is locked, dismiss the root pinned task if it exists
            mRootWindowContainer.removeRootTasksInWindowingModes(WINDOWING_MODE_PINNED);

            getLockTaskController().startLockTaskMode(task, isSystemCaller, callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void stopLockTaskModeInternal(@Nullable IBinder token, boolean isSystemCaller) {
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                Task task = null;
                if (token != null) {
                    final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                    if (r == null) {
                        return;
                    }
                    task = r.getTask();
                }
                // If {@code isSystemCaller} is {@code true}, it means the user intends to stop
                // pinned mode through UI; otherwise, it's called by an app and we need to stop
                // locked or pinned mode, subject to checks.
                getLockTaskController().stopLockTaskMode(task, isSystemCaller, callingUid);
            }
            // Launch in-call UI if a call is ongoing. This is necessary to allow stopping the lock
            // task and jumping straight into a call in the case of emergency call back.
            TelecomManager tm = (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                tm.showInCallScreen(false);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void updateLockTaskPackages(int userId, String[] packages) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != SYSTEM_UID) {
            mAmInternal.enforceCallingPermission(Manifest.permission.UPDATE_LOCK_TASK_PACKAGES,
                    "updateLockTaskPackages()");
        }
        synchronized (mGlobalLock) {
            ProtoLog.w(WM_DEBUG_LOCKTASK, "Allowlisting %d:%s", userId, Arrays.toString(packages));
            getLockTaskController().updateLockTaskPackages(userId, packages);
        }
    }

    @Override
    public boolean isInLockTaskMode() {
        return getLockTaskModeState() != LOCK_TASK_MODE_NONE;
    }

    @Override
    public int getLockTaskModeState() {
        return getLockTaskController().getLockTaskModeState();
    }

    @Override
    public List<IBinder> getAppTasks(String callingPackage) {
        assertPackageMatchesCallingUid(callingPackage);
        return getAppTasks(callingPackage, Binder.getCallingUid());
    }

    private List<IBinder> getAppTasks(String pkgName, int uid) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRecentTasks.getAppTasksList(uid, pkgName);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void finishVoiceTask(IVoiceInteractionSession session) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                // TODO: VI Consider treating local voice interactions and voice tasks
                // differently here
                mRootWindowContainer.finishVoiceTask(session);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

    }

    @Override
    public void reportAssistContextExtras(IBinder assistToken, Bundle extras,
            AssistStructure structure, AssistContent content, Uri referrer) {
        final PendingAssistExtras pae = (PendingAssistExtras) assistToken;
        synchronized (pae) {
            pae.result = extras;
            pae.structure = structure;
            pae.content = content;
            if (referrer != null) {
                pae.extras.putParcelable(Intent.EXTRA_REFERRER, referrer);
            }
            if (!pae.activity.isAttached()) {
                // Skip directly because the caller activity may have been destroyed. If a caller
                // is waiting for the assist data, it will be notified by timeout
                // (see PendingAssistExtras#run()) and then pendingAssistExtrasTimedOut will clean
                // up the request.
                return;
            }
            if (structure != null) {
                // Pre-fill the task/activity component for all assist data receivers
                structure.setTaskId(pae.activity.getTask().mTaskId);
                structure.setActivityComponent(pae.activity.mActivityComponent);
                structure.setHomeActivity(pae.isHome);
            }
            pae.haveResult = true;
            pae.notifyAll();
            if (pae.intent == null && pae.receiver == null) {
                // Caller is just waiting for the result.
                return;
            }
        }
        // We are now ready to launch the assist activity.
        IAssistDataReceiver sendReceiver = null;
        Bundle sendBundle = null;
        synchronized (mGlobalLock) {
            buildAssistBundleLocked(pae, extras);
            boolean exists = mPendingAssistExtras.remove(pae);
            mUiHandler.removeCallbacks(pae);
            if (!exists) {
                // Timed out.
                return;
            }

            if ((sendReceiver = pae.receiver) != null) {
                // Caller wants result sent back to them.
                sendBundle = new Bundle();
                sendBundle.putInt(ActivityTaskManagerInternal.ASSIST_TASK_ID,
                        pae.activity.getTask().mTaskId);
                sendBundle.putBinder(ActivityTaskManagerInternal.ASSIST_ACTIVITY_ID,
                        pae.activity.assistToken);
                sendBundle.putBundle(ASSIST_KEY_DATA, pae.extras);
                sendBundle.putParcelable(ASSIST_KEY_STRUCTURE, pae.structure);
                sendBundle.putParcelable(ASSIST_KEY_CONTENT, pae.content);
                sendBundle.putBundle(ASSIST_KEY_RECEIVER_EXTRAS, pae.receiverExtras);
            }
        }
        if (sendReceiver != null) {
            try {
                sendReceiver.onHandleAssistData(sendBundle);
            } catch (RemoteException e) {
            }
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            pae.intent.replaceExtras(pae.extras);
            pae.intent.setFlags(FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mInternal.closeSystemDialogs("assist");

            try {
                mContext.startActivityAsUser(pae.intent, new UserHandle(pae.userHandle));
            } catch (ActivityNotFoundException e) {
                Slog.w(TAG, "No activity to handle assist action.", e);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int addAppTask(IBinder activityToken, Intent intent,
            ActivityManager.TaskDescription description, Bitmap thumbnail) throws RemoteException {
        final int callingUid = Binder.getCallingUid();
        final long callingIdent = Binder.clearCallingIdentity();

        try {
            synchronized (mGlobalLock) {
                ActivityRecord r = ActivityRecord.isInRootTaskLocked(activityToken);
                if (r == null) {
                    throw new IllegalArgumentException("Activity does not exist; token="
                            + activityToken);
                }
                ComponentName comp = intent.getComponent();
                if (comp == null) {
                    throw new IllegalArgumentException("Intent " + intent
                            + " must specify explicit component");
                }
                if (thumbnail.getWidth() != mThumbnailWidth
                        || thumbnail.getHeight() != mThumbnailHeight) {
                    throw new IllegalArgumentException("Bad thumbnail size: got "
                            + thumbnail.getWidth() + "x" + thumbnail.getHeight() + ", require "
                            + mThumbnailWidth + "x" + mThumbnailHeight);
                }
                if (intent.getSelector() != null) {
                    intent.setSelector(null);
                }
                if (intent.getSourceBounds() != null) {
                    intent.setSourceBounds(null);
                }
                if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0) {
                    if ((intent.getFlags() & Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS) == 0) {
                        // The caller has added this as an auto-remove task...  that makes no
                        // sense, so turn off auto-remove.
                        intent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
                    }
                }
                final ActivityInfo ainfo = AppGlobals.getPackageManager().getActivityInfo(comp,
                        STOCK_PM_FLAGS, UserHandle.getUserId(callingUid));
                if (ainfo == null || ainfo.applicationInfo.uid != callingUid) {
                    Slog.e(TAG, "Can't add task for another application: target uid="
                            + (ainfo == null ? Process.INVALID_UID : ainfo.applicationInfo.uid)
                            + ", calling uid=" + callingUid);
                    return INVALID_TASK_ID;
                }

                final Task rootTask = r.getRootTask();
                final Task task = new Task.Builder(this)
                        .setWindowingMode(rootTask.getWindowingMode())
                        .setActivityType(rootTask.getActivityType())
                        .setActivityInfo(ainfo)
                        .setIntent(intent)
                        .setTaskId(rootTask.getDisplayArea().getNextRootTaskId())
                        .build();

                if (!mRecentTasks.addToBottom(task)) {
                    // The app has too many tasks already and we can't add any more
                    rootTask.removeChild(task, "addAppTask");
                    return INVALID_TASK_ID;
                }
                task.getTaskDescription().copyFrom(description);

                // TODO: Send the thumbnail to WM to store it.

                return task.mTaskId;
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdent);
        }
    }

    @Override
    public Point getAppTaskThumbnailSize() {
        synchronized (mGlobalLock) {
            return new Point(mThumbnailWidth, mThumbnailHeight);
        }
    }

    @Override
    public void setTaskResizeable(int taskId, int resizeableMode) {
        synchronized (mGlobalLock) {
            final Task task = mRootWindowContainer.anyTaskForId(
                    taskId, MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
            if (task == null) {
                Slog.w(TAG, "setTaskResizeable: taskId=" + taskId + " not found");
                return;
            }
            task.setResizeMode(resizeableMode);
        }
    }

    @Override
    public void resizeTask(int taskId, Rect bounds, int resizeMode) {
        enforceTaskPermission("resizeTask()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_ONLY);
                if (task == null) {
                    Slog.w(TAG, "resizeTask: taskId=" + taskId + " not found");
                    return;
                }
                if (!task.getWindowConfiguration().canResizeTask()) {
                    Slog.w(TAG, "resizeTask not allowed on task=" + task);
                    return;
                }

                // Reparent the task to the right root task if necessary
                boolean preserveWindow = (resizeMode & RESIZE_MODE_PRESERVE_WINDOW) != 0;

                if (!getTransitionController().isShellTransitionsEnabled()) {
                    // After reparenting (which only resizes the task to the root task bounds),
                    // resize the task to the actual bounds provided
                    task.resize(bounds, resizeMode, preserveWindow);
                    return;
                }

                final Transition transition = new Transition(TRANSIT_CHANGE, 0 /* flags */,
                        getTransitionController(), mWindowManager.mSyncEngine);
                getTransitionController().startCollectOrQueue(transition,
                        (deferred) -> {
                            if (deferred && !task.getWindowConfiguration().canResizeTask()) {
                                Slog.w(TAG, "resizeTask not allowed on task=" + task);
                                transition.abort();
                                return;
                            }
                            getTransitionController().requestStartTransition(transition, task,
                                    null /* remoteTransition */, null /* displayChange */);
                            getTransitionController().collect(task);
                            task.resize(bounds, resizeMode, preserveWindow);
                            transition.setReady(task, true);
                        });
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void releaseSomeActivities(IApplicationThread appInt) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                final WindowProcessController app = getProcessController(appInt);
                app.releaseSomeActivities("low-mem");
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void setLockScreenShown(boolean keyguardShowing, boolean aodShowing) {
        if (checkCallingPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.DEVICE_POWER);
        }

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            if (mKeyguardShown != keyguardShowing) {
                mKeyguardShown = keyguardShowing;
                final Message msg = PooledLambda.obtainMessage(
                        ActivityManagerInternal::reportCurKeyguardUsageEvent, mAmInternal,
                        keyguardShowing);
                mH.sendMessage(msg);
            }
            // Always reset the state regardless of keyguard-showing change, because that means the
            // unlock is either completed or canceled.
            if ((mDemoteTopAppReasons & DEMOTE_TOP_REASON_DURING_UNLOCKING) != 0) {
                mDemoteTopAppReasons &= ~DEMOTE_TOP_REASON_DURING_UNLOCKING;
                // The scheduling group of top process was demoted by unlocking, so recompute
                // to restore its real top priority if possible.
                if (mTopApp != null) {
                    mTopApp.scheduleUpdateOomAdj();
                }
            }
            try {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "setLockScreenShown");
                mRootWindowContainer.forAllDisplays(displayContent -> {
                    mKeyguardController.setKeyguardShown(displayContent.getDisplayId(),
                            keyguardShowing, aodShowing);
                });
                maybeHideLockedProfileActivityLocked();
            } finally {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                Binder.restoreCallingIdentity(ident);
            }
        }

        mH.post(() -> {
            for (int i = mScreenObservers.size() - 1; i >= 0; i--) {
                mScreenObservers.get(i).onKeyguardStateChanged(keyguardShowing);
            }
        });
    }

    /**
     * Hides locked profile activity by going to home screen to avoid showing the user two lock
     * screens in a row.
     */
    @GuardedBy("mGlobalLock")
    private void maybeHideLockedProfileActivityLocked() {
        if (!mKeyguardController.isKeyguardLocked(DEFAULT_DISPLAY)
                || mLastResumedActivity == null) {
            return;
        }
        var userInfo = mUserManager.getUserInfo(mLastResumedActivity.mUserId);
        if (userInfo == null || !userInfo.isManagedProfile()) {
            return;
        }
        if (mAmInternal.shouldConfirmCredentials(mLastResumedActivity.mUserId)) {
            mInternal.startHomeActivity(
                    mAmInternal.getCurrentUserId(), "maybeHideLockedProfileActivityLocked");
        }
    }

    // The caller MUST NOT hold the global lock.
    public void onScreenAwakeChanged(boolean isAwake) {
        mH.post(() -> {
            for (int i = mScreenObservers.size() - 1; i >= 0; i--) {
                mScreenObservers.get(i).onAwakeStateChanged(isAwake);
            }
        });

        if (isAwake) {
            return;
        }
        // If the device is going to sleep, keep a higher priority temporarily for potential
        // animation of system UI. Even if AOD is not enabled, it should be no harm.
        final WindowProcessController proc;
        synchronized (mGlobalLockWithoutBoost) {
            mDemoteTopAppReasons &= ~DEMOTE_TOP_REASON_DURING_UNLOCKING;
            final WindowState notificationShade = mRootWindowContainer.getDefaultDisplay()
                    .getDisplayPolicy().getNotificationShade();
            proc = notificationShade != null ? notificationShade.getProcess() : null;
        }
        setProcessAnimatingWhileDozing(proc);
    }

    // The caller MUST NOT hold the global lock because it calls AM method directly.
    void setProcessAnimatingWhileDozing(WindowProcessController proc) {
        if (proc == null) return;
        // Set to activity manager directly to make sure the state can be seen by the subsequent
        // update of scheduling group.
        proc.setRunningAnimationUnsafe();
        mH.removeMessages(H.UPDATE_PROCESS_ANIMATING_STATE, proc);
        mH.sendMessageDelayed(mH.obtainMessage(H.UPDATE_PROCESS_ANIMATING_STATE, proc),
                DOZE_ANIMATING_STATE_RETAIN_TIME_MS);
    }

    @Override
    public Bitmap getTaskDescriptionIcon(String filePath, int userId) {
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, "getTaskDescriptionIcon");

        final File passedIconFile = new File(filePath);
        final File legitIconFile = new File(TaskPersister.getUserImagesDir(userId),
                passedIconFile.getName());
        if (!legitIconFile.getPath().equals(filePath)
                || !filePath.contains(ActivityRecord.ACTIVITY_ICON_SUFFIX)) {
            throw new IllegalArgumentException("Bad file path: " + filePath
                    + " passed for userId " + userId);
        }
        return mRecentTasks.getTaskDescriptionIcon(filePath);
    }

    @Override
    public void moveRootTaskToDisplay(int taskId, int displayId) {
        mAmInternal.enforceCallingPermission(INTERNAL_SYSTEM_WINDOW, "moveRootTaskToDisplay()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                ProtoLog.d(WM_DEBUG_TASKS, "moveRootTaskToDisplay: moving taskId=%d to "
                        + "displayId=%d", taskId, displayId);
                mRootWindowContainer.moveRootTaskToDisplay(taskId, displayId, ON_TOP);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /** Sets the task stack listener that gets callbacks when a task stack changes. */
    @Override
    public void registerTaskStackListener(ITaskStackListener listener) {
        enforceTaskPermission("registerTaskStackListener()");
        mTaskChangeNotificationController.registerTaskStackListener(listener);
    }

    /** Unregister a task stack listener so that it stops receiving callbacks. */
    @Override
    public void unregisterTaskStackListener(ITaskStackListener listener) {
        enforceTaskPermission("unregisterTaskStackListener()");
        mTaskChangeNotificationController.unregisterTaskStackListener(listener);
    }

    @Override
    public boolean requestAssistContextExtras(int requestType, IAssistDataReceiver receiver,
            Bundle receiverExtras, IBinder activityToken, boolean checkActivityIsTop,
            boolean newSessionId) {
        return enqueueAssistContext(requestType, null, null, receiver, receiverExtras,
                activityToken, checkActivityIsTop, newSessionId, UserHandle.getCallingUserId(),
                null, PENDING_ASSIST_EXTRAS_LONG_TIMEOUT, 0) != null;
    }

    @Override
    public boolean requestAssistDataForTask(IAssistDataReceiver receiver, int taskId,
            String callingPackageName, @Nullable String callingAttributionTag) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.GET_TOP_ACTIVITY_INFO,
                "requestAssistDataForTask()");
        final long callingId = Binder.clearCallingIdentity();
        LocalService.ActivityTokens tokens = null;
        try {
            tokens = mInternal.getAttachedNonFinishingActivityForTask(taskId, null);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        if (tokens == null) {
            Log.e(TAG, "Could not find activity for task " + taskId);
            return false;
        }

        final AssistDataReceiverProxy proxy =
                new AssistDataReceiverProxy(receiver, callingPackageName);
        Object lock = new Object();
        AssistDataRequester requester = new AssistDataRequester(mContext, mWindowManager,
                getAppOpsManager(), proxy, lock, AppOpsManager.OP_ASSIST_STRUCTURE,
                AppOpsManager.OP_NONE);

        List<IBinder> topActivityToken = new ArrayList<>();
        topActivityToken.add(tokens.getActivityToken());
        requester.requestAssistData(topActivityToken, true /* fetchData */,
                false /* fetchScreenshot */, false /* fetchStructure */, true /* allowFetchData */,
                false /* allowFetchScreenshot*/, true /* ignoreFocusCheck */,
                Binder.getCallingUid(), callingPackageName, callingAttributionTag);

        return true;
    }

    @Override
    public boolean requestAutofillData(IAssistDataReceiver receiver, Bundle receiverExtras,
            IBinder activityToken, int flags) {
        return enqueueAssistContext(ActivityManager.ASSIST_CONTEXT_AUTOFILL, null, null,
                receiver, receiverExtras, activityToken, true, true, UserHandle.getCallingUserId(),
                null, PENDING_AUTOFILL_ASSIST_STRUCTURE_TIMEOUT, flags) != null;
    }

    @Override
    public Bundle getAssistContextExtras(int requestType) {
        PendingAssistExtras pae = enqueueAssistContext(requestType, null, null, null,
                null, null, true /* checkActivityIsTop */, true /* newSessionId */,
                UserHandle.getCallingUserId(), null, PENDING_ASSIST_EXTRAS_TIMEOUT, 0);
        if (pae == null) {
            return null;
        }
        synchronized (pae) {
            while (!pae.haveResult) {
                try {
                    pae.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        synchronized (mGlobalLock) {
            buildAssistBundleLocked(pae, pae.result);
            mPendingAssistExtras.remove(pae);
            mUiHandler.removeCallbacks(pae);
        }
        return pae.extras;
    }

    /**
     * Binder IPC calls go through the public entry point.
     * This can be called with or without the global lock held.
     */
    private static int checkCallingPermission(String permission) {
        return checkPermission(
                permission, Binder.getCallingPid(), Binder.getCallingUid());
    }

    /**
     * Returns true if the app can close system dialogs. Otherwise it either throws a {@link
     * SecurityException} or returns false with a logcat message depending on whether the app
     * targets SDK level {@link android.os.Build.VERSION_CODES#S} or not.
     */
    boolean checkCanCloseSystemDialogs(int pid, int uid, @Nullable String packageName) {
        final WindowProcessController process;
        synchronized (mGlobalLock) {
            process = mProcessMap.getProcess(pid);
        }
        if (packageName == null && process != null) {
            // WindowProcessController.mInfo is final, so after the synchronized memory barrier
            // above, process.mInfo can't change. As for reading mInfo.packageName,
            // WindowProcessController doesn't own the ApplicationInfo object referenced by mInfo.
            // ProcessRecord for example also holds a reference to that object, so protecting access
            // to packageName with the WM lock would not be enough as we'd also need to synchronize
            // on the AM lock if we are worried about races, but we can't synchronize on AM lock
            // here. Hence, since this is only used for logging, we don't synchronize here.
            packageName = process.mInfo.packageName;
        }
        String caller = "(pid=" + pid + ", uid=" + uid + ")";
        if (packageName != null) {
            caller = packageName + " " + caller;
        }
        if (!canCloseSystemDialogs(pid, uid)) {
            // The app can't close system dialogs, throw only if it targets S+
            if (CompatChanges.isChangeEnabled(LOCK_DOWN_CLOSE_SYSTEM_DIALOGS, uid)) {
                throw new SecurityException(
                        "Permission Denial: " + Intent.ACTION_CLOSE_SYSTEM_DIALOGS
                                + " broadcast from " + caller + " requires "
                                + Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS + ".");
            } else if (CompatChanges.isChangeEnabled(DROP_CLOSE_SYSTEM_DIALOGS, uid)) {
                Slog.e(TAG,
                        "Permission Denial: " + Intent.ACTION_CLOSE_SYSTEM_DIALOGS
                                + " broadcast from " + caller + " requires "
                                + Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS
                                + ", dropping broadcast.");
                return false;
            } else {
                Slog.w(TAG, Intent.ACTION_CLOSE_SYSTEM_DIALOGS
                        + " broadcast from " + caller + " will require "
                        + Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS
                        + " in future builds.");
                return true;
            }
        }
        return true;
    }

    private boolean canCloseSystemDialogs(int pid, int uid) {
        if (checkPermission(Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS, pid, uid)
                == PERMISSION_GRANTED) {
            return true;
        }
        synchronized (mGlobalLock) {
            // Check all the processes from the given uid, especially since for PendingIntents sent
            // the pid equals -1
            ArraySet<WindowProcessController> processes = mProcessMap.getProcesses(uid);
            if (processes != null) {
                for (int i = 0, n = processes.size(); i < n; i++) {
                    WindowProcessController process = processes.valueAt(i);
                    // Check if the instrumentation of the process has the permission. This covers
                    // the usual test started from the shell (which has the permission) case. This
                    // is needed for apps targeting SDK level < S but we are also allowing for
                    // targetSdk S+ as a convenience to avoid breaking a bunch of existing tests and
                    // asking them to adopt shell permissions to do this.
                    int sourceUid = process.getInstrumentationSourceUid();
                    if (process.isInstrumenting() && sourceUid != -1 && checkPermission(
                            Manifest.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS, -1, sourceUid)
                            == PERMISSION_GRANTED) {
                        return true;
                    }
                    // This is the notification trampoline use-case for example, where apps use
                    // Intent.ACSD to close the shade prior to starting an activity.
                    if (process.canCloseSystemDialogsByToken()) {
                        return true;
                    }
                }
            }
            if (!CompatChanges.isChangeEnabled(LOCK_DOWN_CLOSE_SYSTEM_DIALOGS, uid)) {
                // This covers the case where the app is displaying some UI on top of the
                // notification shade and wants to start an activity. The app then sends the intent
                // in order to move the notification shade out of the way and show the activity to
                // the user. This is fine since the caller already has privilege to show a visible
                // window on top of the notification shade, so it can already prevent the user from
                // accessing the shade if it wants to. We only allow for targetSdk < S, for S+ we
                // automatically collapse the shade on startActivity() for these apps.
                // It's ok that the owner of the shade is not allowed *per this rule* because it has
                // BROADCAST_CLOSE_SYSTEM_DIALOGS (SystemUI), so it would fall into that rule.
                if (mRootWindowContainer.hasVisibleWindowAboveButDoesNotOwnNotificationShade(uid)) {
                    return true;
                }
                // Accessibility services are allowed to send the intent unless they are targeting
                // S+, in which case they should use {@link AccessibilityService
                // #GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE} to dismiss the notification shade.
                if (ArrayUtils.contains(mAccessibilityServiceUids, uid)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void enforceTaskPermission(String func) {
        if (checkCallingPermission(MANAGE_ACTIVITY_TASKS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (checkCallingPermission(MANAGE_ACTIVITY_STACKS) == PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "MANAGE_ACTIVITY_STACKS is deprecated, "
                    + "please use alternative permission: MANAGE_ACTIVITY_TASKS");
            return;
        }

        String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid="
                + Binder.getCallingUid() + " requires android.permission.MANAGE_ACTIVITY_TASKS";
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    static int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        return checkComponentPermission(permission, pid, uid, -1, true);
    }

    public static int checkComponentPermission(String permission, int pid, int uid,
            int owningUid, boolean exported) {
        return ActivityManagerService.checkComponentPermission(
                permission, pid, uid, owningUid, exported);
    }

    boolean isCallerRecents(int callingUid) {
        return mRecentTasks.isCallerRecents(callingUid);
    }

    boolean isGetTasksAllowed(String caller, int callingPid, int callingUid) {
        if (isCallerRecents(callingUid)) {
            // Always allow the recents component to get tasks
            return true;
        }

        boolean allowed = checkPermission(android.Manifest.permission.REAL_GET_TASKS,
                callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
        if (!allowed) {
            if (checkPermission(android.Manifest.permission.GET_TASKS,
                    callingPid, callingUid) == PackageManager.PERMISSION_GRANTED) {
                // Temporary compatibility: some existing apps on the system image may
                // still be requesting the old permission and not switched to the new
                // one; if so, we'll still allow them full access.  This means we need
                // to see if they are holding the old permission and are a system app.
                try {
                    if (AppGlobals.getPackageManager().isUidPrivileged(callingUid)) {
                        allowed = true;
                        ProtoLog.w(WM_DEBUG_TASKS,
                                "%s: caller %d is using old GET_TASKS but privileged; allowing",
                                caller, callingUid);
                    }
                } catch (RemoteException e) {
                }
            }
            ProtoLog.w(WM_DEBUG_TASKS,
                    "%s: caller %d does not hold REAL_GET_TASKS; limiting output", caller,
                    callingUid);
        }
        return allowed;
    }

    boolean isCrossUserAllowed(int pid, int uid) {
        return checkPermission(INTERACT_ACROSS_USERS, pid, uid) == PERMISSION_GRANTED
                || checkPermission(INTERACT_ACROSS_USERS_FULL, pid, uid) == PERMISSION_GRANTED;
    }

    private PendingAssistExtras enqueueAssistContext(int requestType, Intent intent, String hint,
            IAssistDataReceiver receiver, Bundle receiverExtras, IBinder activityToken,
            boolean checkActivityIsTop, boolean newSessionId, int userHandle, Bundle args,
            long timeout, int flags) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.GET_TOP_ACTIVITY_INFO,
                "enqueueAssistContext()");

        synchronized (mGlobalLock) {
            final Task rootTask = getTopDisplayFocusedRootTask();
            ActivityRecord activity =
                    rootTask != null ? rootTask.getTopNonFinishingActivity() : null;
            if (activity == null) {
                Slog.w(TAG, "getAssistContextExtras failed: no top activity");
                return null;
            }
            if (!activity.attachedToProcess()) {
                Slog.w(TAG, "getAssistContextExtras failed: no process for " + activity);
                return null;
            }
            if (checkActivityIsTop) {
                if (activityToken != null) {
                    ActivityRecord caller = ActivityRecord.forTokenLocked(activityToken);
                    if (activity != caller) {
                        Slog.w(TAG, "enqueueAssistContext failed: caller " + caller
                                + " is not current top " + activity);
                        return null;
                    }
                }
            } else {
                activity = ActivityRecord.forTokenLocked(activityToken);
                if (activity == null) {
                    Slog.w(TAG, "enqueueAssistContext failed: activity for token=" + activityToken
                            + " couldn't be found");
                    return null;
                }
                if (!activity.attachedToProcess()) {
                    Slog.w(TAG, "enqueueAssistContext failed: no process for " + activity);
                    return null;
                }
            }

            PendingAssistExtras pae;
            Bundle extras = new Bundle();
            if (args != null) {
                extras.putAll(args);
            }
            extras.putString(Intent.EXTRA_ASSIST_PACKAGE, activity.packageName);
            extras.putInt(Intent.EXTRA_ASSIST_UID, activity.app.mUid);

            pae = new PendingAssistExtras(activity, extras, intent, hint, receiver, receiverExtras,
                    userHandle);
            pae.isHome = activity.isActivityTypeHome();

            // Increment the sessionId if necessary
            if (newSessionId) {
                mViSessionId++;
            }
            try {
                activity.app.getThread().requestAssistContextExtras(activity.token, pae,
                        requestType, mViSessionId, flags);
                mPendingAssistExtras.add(pae);
                mUiHandler.postDelayed(pae, timeout);
            } catch (RemoteException e) {
                Slog.w(TAG, "getAssistContextExtras failed: crash calling " + activity);
                return null;
            }
            return pae;
        }
    }

    private void buildAssistBundleLocked(PendingAssistExtras pae, Bundle result) {
        if (result != null) {
            pae.extras.putBundle(Intent.EXTRA_ASSIST_CONTEXT, result);
        }
        if (pae.hint != null) {
            pae.extras.putBoolean(pae.hint, true);
        }
    }

    private void pendingAssistExtrasTimedOut(PendingAssistExtras pae) {
        IAssistDataReceiver receiver;
        synchronized (mGlobalLock) {
            mPendingAssistExtras.remove(pae);
            receiver = pae.receiver;
        }
        if (receiver != null) {
            // Caller wants result sent back to them.
            Bundle sendBundle = new Bundle();
            // At least return the receiver extras
            sendBundle.putBundle(ASSIST_KEY_RECEIVER_EXTRAS, pae.receiverExtras);
            try {
                pae.receiver.onHandleAssistData(sendBundle);
            } catch (RemoteException e) {
            }
        }
    }

    public class PendingAssistExtras extends Binder implements Runnable {
        public final ActivityRecord activity;
        public boolean isHome;
        public final Bundle extras;
        public final Intent intent;
        public final String hint;
        public final IAssistDataReceiver receiver;
        public final int userHandle;
        public boolean haveResult = false;
        public Bundle result = null;
        public AssistStructure structure = null;
        public AssistContent content = null;
        public Bundle receiverExtras;

        public PendingAssistExtras(ActivityRecord _activity, Bundle _extras, Intent _intent,
                String _hint, IAssistDataReceiver _receiver, Bundle _receiverExtras,
                int _userHandle) {
            activity = _activity;
            extras = _extras;
            intent = _intent;
            hint = _hint;
            receiver = _receiver;
            receiverExtras = _receiverExtras;
            userHandle = _userHandle;
        }

        @Override
        public void run() {
            Slog.w(TAG, "getAssistContextExtras failed: timeout retrieving from " + activity);
            synchronized (this) {
                haveResult = true;
                notifyAll();
            }
            pendingAssistExtrasTimedOut(this);
        }
    }

    @Override
    public boolean isAssistDataAllowedOnCurrentActivity() {
        int userId;
        synchronized (mGlobalLock) {
            final Task focusedRootTask = getTopDisplayFocusedRootTask();
            if (focusedRootTask == null || focusedRootTask.isActivityTypeAssistant()) {
                return false;
            }

            final ActivityRecord activity = focusedRootTask.getTopNonFinishingActivity();
            if (activity == null) {
                return false;
            }
            userId = activity.mUserId;
        }
        return DevicePolicyCache.getInstance().isScreenCaptureAllowed(userId);
    }

    private void onLocalVoiceInteractionStartedLocked(IBinder activity,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        ActivityRecord activityToCallback = ActivityRecord.forTokenLocked(activity);
        if (activityToCallback == null) return;
        activityToCallback.setVoiceSessionLocked(voiceSession);

        // Inform the activity
        try {
            activityToCallback.app.getThread().scheduleLocalVoiceInteractionStarted(activity,
                    voiceInteractor);
            final long token = Binder.clearCallingIdentity();
            try {
                startRunningVoiceLocked(voiceSession, activityToCallback.info.applicationInfo.uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            // TODO: VI Should we cache the activity so that it's easier to find later
            // rather than scan through all the root tasks and activities?
        } catch (RemoteException re) {
            activityToCallback.clearVoiceSessionLocked();
            // TODO: VI Should this terminate the voice session?
        }
    }

    private void startRunningVoiceLocked(IVoiceInteractionSession session, int targetUid) {
        Slog.d(TAG, "<<<  startRunningVoiceLocked()");
        mVoiceWakeLock.setWorkSource(new WorkSource(targetUid));
        if (mRunningVoice == null || mRunningVoice.asBinder() != session.asBinder()) {
            boolean wasRunningVoice = mRunningVoice != null;
            mRunningVoice = session;
            if (!wasRunningVoice) {
                mVoiceWakeLock.acquire();
                updateSleepIfNeededLocked();
            }
        }
    }

    void finishRunningVoiceLocked() {
        if (mRunningVoice != null) {
            mRunningVoice = null;
            mVoiceWakeLock.release();
            updateSleepIfNeededLocked();
        }
    }

    @Override
    public void setVoiceKeepAwake(IVoiceInteractionSession session, boolean keepAwake) {
        synchronized (mGlobalLock) {
            if (mRunningVoice != null && mRunningVoice.asBinder() == session.asBinder()) {
                if (keepAwake) {
                    mVoiceWakeLock.acquire();
                } else {
                    mVoiceWakeLock.release();
                }
            }
        }
    }

    @Override
    public void keyguardGoingAway(int flags) {
        enforceNotIsolatedCaller("keyguardGoingAway");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                // Keyguard asked us to clear the home task snapshot before going away, so do that.
                if ((flags & KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT) != 0) {
                    mActivityClientController.invalidateHomeTaskSnapshot(null /* token */);
                } else if (mKeyguardShown) {
                    // Only set if it is not unlocking to launcher which may also animate.
                    mDemoteTopAppReasons |= DEMOTE_TOP_REASON_DURING_UNLOCKING;
                }

                mRootWindowContainer.forAllDisplays(displayContent -> {
                    mKeyguardController.keyguardGoingAway(displayContent.getDisplayId(), flags);
                });
            }
            WallpaperManagerInternal wallpaperManagerInternal = getWallpaperManagerInternal();
            if (wallpaperManagerInternal != null) {
                wallpaperManagerInternal.onKeyguardGoingAway();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void suppressResizeConfigChanges(boolean suppress) throws RemoteException {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_TASKS,
                "suppressResizeConfigChanges()");
        synchronized (mGlobalLock) {
            mSuppressResizeConfigChanges = suppress;
        }
    }

    /**
     * A splash screen view has copied, pass it to an activity.
     *
     * @param taskId Id of task to handle the material to reconstruct the view.
     * @param parcelable Used to reconstruct the view, null means the surface is un-copyable.
     * @hide
     */
    @Override
    public void onSplashScreenViewCopyFinished(int taskId, SplashScreenViewParcelable parcelable)
            throws RemoteException {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_TASKS,
                "copySplashScreenViewFinish()");
        synchronized (mGlobalLock) {
            final Task task = mRootWindowContainer.anyTaskForId(taskId,
                    MATCH_ATTACHED_TASK_ONLY);
            if (task != null) {
                final ActivityRecord r = task.getTopWaitSplashScreenActivity();
                if (r != null) {
                    r.onCopySplashScreenFinish(parcelable);
                }
            }
        }
    }

    boolean enterPictureInPictureMode(@NonNull ActivityRecord r,
            @NonNull PictureInPictureParams params, boolean fromClient) {
        return enterPictureInPictureMode(r, params, fromClient, false /* isAutoEnter */);
    }

    /**
     * Puts the given activity in picture in picture mode if possible.
     *
     * @param fromClient true if this comes from a client call (eg. Activity.enterPip).
     * @param isAutoEnter true if this comes from an automatic pip-enter.
     * @return true if the activity is now in picture-in-picture mode, or false if it could not
     * enter picture-in-picture mode.
     */
    boolean enterPictureInPictureMode(@NonNull ActivityRecord r,
            @NonNull PictureInPictureParams params, boolean fromClient, boolean isAutoEnter) {
        // If the activity is already in picture in picture mode, then just return early
        if (r.inPinnedWindowingMode()) {
            return true;
        }

        // Activity supports picture-in-picture, now check that we can enter PiP at this
        // point, if it is
        if (!r.checkEnterPictureInPictureState("enterPictureInPictureMode",
                false /* beforeStopping */)) {
            return false;
        }

        // If the app is using legacy-entry (not auto-enter), then we will get a client-request
        // that was actually a server-request (via pause(userLeaving=true)). This happens when
        // the app is PAUSING, so detect that case here.
        boolean originallyFromClient = fromClient
                && (!r.isState(PAUSING) || params.isAutoEnterEnabled());

        // Create a transition only for this pip entry if it is coming from the app without the
        // system requesting that the app enter-pip. If the system requested it, that means it
        // should be part of that transition if possible.
        final Transition transition =
                (getTransitionController().isShellTransitionsEnabled() && originallyFromClient)
                ? new Transition(TRANSIT_PIP, 0 /* flags */,
                        getTransitionController(), mWindowManager.mSyncEngine)
                : null;

        final Runnable enterPipRunnable = () -> {
            synchronized (mGlobalLock) {
                if (r.getParent() == null) {
                    Slog.e(TAG, "Skip enterPictureInPictureMode, destroyed " + r);
                    return;
                }
                EventLogTags.writeWmEnterPip(r.mUserId, System.identityHashCode(r),
                        r.shortComponentName, Boolean.toString(isAutoEnter));
                r.setPictureInPictureParams(params);
                r.mAutoEnteringPip = isAutoEnter;
                mRootWindowContainer.moveActivityToPinnedRootTask(r,
                        null /* launchIntoPipHostActivity */, "enterPictureInPictureMode",
                        transition);
                // Continue the pausing process after entering pip.
                if (r.isState(PAUSING) && r.mPauseSchedulePendingForPip) {
                    r.getTask().schedulePauseActivity(r, false /* userLeaving */,
                            false /* pauseImmediately */, true /* autoEnteringPip */, "auto-pip");
                }
                r.mAutoEnteringPip = false;
            }
        };

        if (r.isKeyguardLocked()) {
            // If the keyguard is showing or occluded, then try and dismiss it before
            // entering picture-in-picture (this will prompt the user to authenticate if the
            // device is currently locked).
            mActivityClientController.dismissKeyguard(r.token, new KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    if (transition == null) {
                        mH.post(enterPipRunnable);
                        return;
                    }
                    getTransitionController().startCollectOrQueue(transition, (deferred) -> {
                        if (deferred) {
                            enterPipRunnable.run();
                        } else {
                            mH.post(enterPipRunnable);
                        }
                    });
                }
            }, null /* message */);
        } else {
            // Enter picture in picture immediately otherwise
            if (transition != null) {
                getTransitionController().startCollectOrQueue(transition,
                        (deferred) -> enterPipRunnable.run());
            } else {
                enterPipRunnable.run();
            }
        }
        return true;
    }

    @Override
    public IWindowOrganizerController getWindowOrganizerController() {
        return mWindowOrganizerController;
    }

    /**
     * Check that we have the features required for VR-related API calls, and throw an exception if
     * not.
     */
    public void enforceSystemHasVrFeature() {
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)) {
            throw new UnsupportedOperationException("VR mode not supported on this device!");
        }
    }

    @Override
    public boolean supportsLocalVoiceInteraction() {
        return LocalServices.getService(VoiceInteractionManagerInternal.class)
                .supportsLocalVoiceInteraction();
    }

    @Override
    public boolean updateConfiguration(Configuration values) {
        mAmInternal.enforceCallingPermission(CHANGE_CONFIGURATION, "updateConfiguration()");

        synchronized (mGlobalLock) {
            if (mWindowManager == null) {
                Slog.w(TAG, "Skip updateConfiguration because mWindowManager isn't set");
                return false;
            }

            if (values == null) {
                // sentinel: fetch the current configuration from the window manager
                values = mWindowManager.computeNewConfiguration(DEFAULT_DISPLAY);
            }

            mH.sendMessage(PooledLambda.obtainMessage(
                    ActivityManagerInternal::updateOomLevelsForDisplay, mAmInternal,
                    DEFAULT_DISPLAY));

            final long origId = Binder.clearCallingIdentity();
            try {
                if (values != null) {
                    Settings.System.clearConfiguration(values);
                }
                updateConfigurationLocked(values, null, false, false /* persistent */,
                        UserHandle.USER_NULL, false /* deferResume */,
                        mTmpUpdateConfigurationResult);
                return mTmpUpdateConfigurationResult.changes != 0;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void cancelTaskWindowTransition(int taskId) {
        enforceTaskPermission("cancelTaskWindowTransition()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_ONLY);
                if (task == null) {
                    Slog.w(TAG, "cancelTaskWindowTransition: taskId=" + taskId + " not found");
                    return;
                }
                task.cancelTaskWindowTransition();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public TaskSnapshot getTaskSnapshot(int taskId, boolean isLowResolution,
            boolean takeSnapshotIfNeeded) {
        mAmInternal.enforceCallingPermission(READ_FRAME_BUFFER, "getTaskSnapshot()");
        final long ident = Binder.clearCallingIdentity();
        try {
            final Task task;
            synchronized (mGlobalLock) {
                task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    Slog.w(TAG, "getTaskSnapshot: taskId=" + taskId + " not found");
                    return null;
                }
            }
            // Don't call this while holding the lock as this operation might hit the disk.
            TaskSnapshot taskSnapshot = mWindowManager.mTaskSnapshotController.getSnapshot(taskId,
                    task.mUserId, true /* restoreFromDisk */, isLowResolution);
            if (taskSnapshot == null && takeSnapshotIfNeeded) {
                taskSnapshot = takeTaskSnapshot(taskId, false /* updateCache */);
            }
            return taskSnapshot;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public TaskSnapshot takeTaskSnapshot(int taskId, boolean updateCache) {
        mAmInternal.enforceCallingPermission(READ_FRAME_BUFFER, "takeTaskSnapshot()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null || !task.isVisible()) {
                    Slog.w(TAG, "takeTaskSnapshot: taskId=" + taskId + " not found or not visible");
                    return null;
                }
                if (updateCache) {
                    return mWindowManager.mTaskSnapshotController.recordSnapshot(task,
                            true /* snapshotHome */);
                } else {
                    return mWindowManager.mTaskSnapshotController.captureSnapshot(task,
                            true /* snapshotHome */);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Return the user id of the last resumed activity. */
    @Override
    public @UserIdInt
    int getLastResumedActivityUserId() {
        mAmInternal.enforceCallingPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL, "getLastResumedActivityUserId()");
        synchronized (mGlobalLock) {
            if (mLastResumedActivity == null) {
                return getCurrentUserId();
            }
            return mLastResumedActivity.mUserId;
        }
    }

    @Override
    public void updateLockTaskFeatures(int userId, int flags) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != SYSTEM_UID) {
            mAmInternal.enforceCallingPermission(
                    android.Manifest.permission.UPDATE_LOCK_TASK_PACKAGES,
                    "updateLockTaskFeatures()");
        }
        synchronized (mGlobalLock) {
            ProtoLog.w(WM_DEBUG_LOCKTASK, "Allowing features %d:0x%s",
                    userId, Integer.toHexString(flags));
            getLockTaskController().updateLockTaskFeatures(userId, flags);
        }
    }

    @Override
    public void registerRemoteAnimationForNextActivityStart(String packageName,
            RemoteAnimationAdapter adapter, IBinder launchCookie) {
        mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "registerRemoteAnimationForNextActivityStart");
        adapter.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                getActivityStartController().registerRemoteAnimationForNextActivityStart(
                        packageName, adapter, launchCookie);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void registerRemoteAnimationsForDisplay(int displayId,
            RemoteAnimationDefinition definition) {
        mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "registerRemoteAnimations");
        definition.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (mGlobalLock) {
            final DisplayContent display = mRootWindowContainer.getDisplayContent(displayId);
            if (display == null) {
                Slog.e(TAG, "Couldn't find display with id: " + displayId);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                display.registerRemoteAnimations(definition);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    @Override
    public void alwaysShowUnsupportedCompileSdkWarning(ComponentName activity) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                mAppWarnings.alwaysShowUnsupportedCompileSdkWarning(activity);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void setVrThread(int tid) {
        enforceSystemHasVrFeature();
        synchronized (mGlobalLock) {
            final int pid = Binder.getCallingPid();
            final WindowProcessController wpc = mProcessMap.getProcess(pid);
            mVrController.setVrThreadLocked(tid, pid, wpc);
        }
    }

    @Override
    public void setPersistentVrThread(int tid) {
        if (checkCallingPermission(Manifest.permission.RESTRICTED_VR_ACCESS)
                != PERMISSION_GRANTED) {
            final String msg = "Permission Denial: setPersistentVrThread() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + Manifest.permission.RESTRICTED_VR_ACCESS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        enforceSystemHasVrFeature();
        synchronized (mGlobalLock) {
            final int pid = Binder.getCallingPid();
            final WindowProcessController proc = mProcessMap.getProcess(pid);
            mVrController.setPersistentVrThreadLocked(tid, pid, proc);
        }
    }

    @Override
    public void stopAppSwitches() {
        mAmInternal.enforceCallingPermission(STOP_APP_SWITCHES, "stopAppSwitches");
        synchronized (mGlobalLock) {
            mAppSwitchesState = APP_SWITCH_DISALLOW;
            mLastStopAppSwitchesTime = SystemClock.uptimeMillis();
            mH.removeMessages(H.RESUME_FG_APP_SWITCH_MSG);
            mH.sendEmptyMessageDelayed(H.RESUME_FG_APP_SWITCH_MSG, RESUME_FG_APP_SWITCH_MS);
        }
    }

    @Override
    public void resumeAppSwitches() {
        mAmInternal.enforceCallingPermission(STOP_APP_SWITCHES, "resumeAppSwitches");
        synchronized (mGlobalLock) {
            mAppSwitchesState = APP_SWITCH_ALLOW;
            mH.removeMessages(H.RESUME_FG_APP_SWITCH_MSG);
        }
    }

    long getLastStopAppSwitchesTime() {
        return mLastStopAppSwitchesTime;
    }

    /** @return whether the system should disable UI modes incompatible with VR mode. */
    boolean shouldDisableNonVrUiLocked() {
        return mVrController.shouldDisableNonVrUiLocked();
    }

    void applyUpdateVrModeLocked(ActivityRecord r) {
        // VR apps are expected to run in a main display. If an app is turning on VR for
        // itself, but isn't on the main display, then move it there before enabling VR Mode.
        if (r.requestedVrComponent != null && r.getDisplayId() != DEFAULT_DISPLAY) {
            Slog.i(TAG, "Moving " + r.shortComponentName + " from display " + r.getDisplayId()
                    + " to main display for VR");
            mRootWindowContainer.moveRootTaskToDisplay(
                    r.getRootTaskId(), DEFAULT_DISPLAY, true /* toTop */);
        }
        mH.post(() -> {
            if (!mVrController.onVrModeChanged(r)) {
                return;
            }
            synchronized (mGlobalLock) {
                final boolean disableNonVrUi = mVrController.shouldDisableNonVrUiLocked();
                mWindowManager.disableNonVrUi(disableNonVrUi);
                if (disableNonVrUi) {
                    // If we are in a VR mode where Picture-in-Picture mode is unsupported,
                    // then remove the root pinned task.
                    mRootWindowContainer.removeRootTasksInWindowingModes(WINDOWING_MODE_PINNED);
                }
            }
        });
    }

    @Override
    public int getPackageScreenCompatMode(String packageName) {
        enforceNotIsolatedCaller("getPackageScreenCompatMode");
        synchronized (mGlobalLock) {
            return mCompatModePackages.getPackageScreenCompatModeLocked(packageName);
        }
    }

    @Override
    public void setPackageScreenCompatMode(String packageName, int mode) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setPackageScreenCompatMode");
        synchronized (mGlobalLock) {
            mCompatModePackages.setPackageScreenCompatModeLocked(packageName, mode);
        }
    }

    @Override
    public boolean getPackageAskScreenCompat(String packageName) {
        enforceNotIsolatedCaller("getPackageAskScreenCompat");
        synchronized (mGlobalLock) {
            return mCompatModePackages.getPackageAskCompatModeLocked(packageName);
        }
    }

    @Override
    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setPackageAskScreenCompat");
        synchronized (mGlobalLock) {
            mCompatModePackages.setPackageAskCompatModeLocked(packageName, ask);
        }
    }

    public static String relaunchReasonToString(int relaunchReason) {
        switch (relaunchReason) {
            case RELAUNCH_REASON_WINDOWING_MODE_RESIZE:
                return "window_resize";
            case RELAUNCH_REASON_FREE_RESIZE:
                return "free_resize";
            default:
                return null;
        }
    }

    Task getTopDisplayFocusedRootTask() {
        return mRootWindowContainer.getTopDisplayFocusedRootTask();
    }

    /** Pokes the task persister. */
    void notifyTaskPersisterLocked(Task task, boolean flush) {
        mRecentTasks.notifyTaskPersisterLocked(task, flush);
    }

    boolean isKeyguardLocked(int displayId) {
        return mKeyguardController.isKeyguardLocked(displayId);
    }

    /**
     * Clears launch params for the given package.
     *
     * @param packageNames the names of the packages of which the launch params are to be cleared
     */
    @Override
    public void clearLaunchParamsForPackages(List<String> packageNames) {
        enforceTaskPermission("clearLaunchParamsForPackages");
        synchronized (mGlobalLock) {
            for (int i = 0; i < packageNames.size(); ++i) {
                mTaskSupervisor.mLaunchParamsPersister.removeRecordForPackage(packageNames.get(i));
            }
        }
    }

    @Override
    public void onPictureInPictureStateChanged(PictureInPictureUiState pipState) {
        enforceTaskPermission("onPictureInPictureStateChanged");
        final Task rootPinnedTask = mRootWindowContainer.getDefaultTaskDisplayArea()
                .getRootPinnedTask();
        if (rootPinnedTask != null && rootPinnedTask.getTopMostActivity() != null) {
            mWindowManager.mAtmService.mActivityClientController.onPictureInPictureStateChanged(
                    rootPinnedTask.getTopMostActivity(), pipState);
        }
    }

    @Override
    public void detachNavigationBarFromApp(@NonNull IBinder transition) {
        mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "detachNavigationBarFromApp");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                getTransitionController().legacyDetachNavigationBarFromApp(transition);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void dumpLastANRLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)");
        if (mLastANRState == null) {
            pw.println("  <no ANR has occurred since boot>");
        } else {
            pw.println(mLastANRState);
        }
    }

    void dumpLastANRTracesLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER LAST ANR TRACES (dumpsys activity lastanr-traces)");

        final File[] files = new File(ANR_TRACE_DIR).listFiles();
        if (ArrayUtils.isEmpty(files)) {
            pw.println("  <no ANR has occurred since boot>");
            return;
        }
        // Find the latest file.
        File latest = null;
        for (File f : files) {
            if ((latest == null) || (latest.lastModified() < f.lastModified())) {
                latest = f;
            }
        }
        pw.print("File: ");
        pw.print(latest.getName());
        pw.println();
        try (BufferedReader in = new BufferedReader(new FileReader(latest))) {
            String line;
            while ((line = in.readLine()) != null) {
                pw.println(line);
            }
        } catch (IOException e) {
            pw.print("Unable to read: ");
            pw.print(e);
            pw.println();
        }
    }

    void dumpTopResumedActivityLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER TOP-RESUMED (dumpsys activity top-resumed)");
        ActivityRecord topRecord = mRootWindowContainer.getTopResumedActivity();
        if (topRecord != null) {
            topRecord.dump(pw, "", true);
        }
    }

    void dumpVisibleActivitiesLocked(PrintWriter pw, int displayIdFilter) {
        pw.println("ACTIVITY MANAGER VISIBLE ACTIVITIES (dumpsys activity visible)");
        ArrayList<ActivityRecord> activities =
                mRootWindowContainer.getDumpActivities("all", /* dumpVisibleRootTasksOnly */ true,
                        /* dumpFocusedRootTaskOnly */ false, UserHandle.USER_ALL);
        boolean needSeparator = false;
        boolean printedAnything = false;
        for (int i = activities.size() - 1; i >= 0; i--) {
            ActivityRecord activity = activities.get(i);
            if (!activity.isVisible() || (displayIdFilter != INVALID_DISPLAY
                    && activity.getDisplayId() != displayIdFilter)) {
                continue;
            }
            if (needSeparator) {
                pw.println();
            }
            printedAnything = true;
            activity.dump(pw, "", true);
            needSeparator = true;
        }
        if (!printedAnything) {
            pw.println("(nothing)");
        }
    }

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage,
            int displayIdFilter) {
        dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage, displayIdFilter,
                "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)");
    }

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage, int displayIdFilter,
            String header) {
        pw.println(header);

        boolean printedAnything = mRootWindowContainer.dumpActivities(fd, pw, dumpAll, dumpClient,
                dumpPackage, displayIdFilter);
        boolean needSep = printedAnything;

        boolean printed = ActivityTaskSupervisor.printThisActivity(pw,
                mRootWindowContainer.getTopResumedActivity(), dumpPackage, displayIdFilter, needSep,
                "  ResumedActivity: ", /* header= */ null);
        if (printed) {
            printedAnything = true;
            needSep = false;
        }

        if (dumpPackage == null) {
            if (needSep) {
                pw.println();
            }
            printedAnything = true;
            mTaskSupervisor.dump(pw, "  ");
            mTaskOrganizerController.dump(pw, "  ");
            mVisibleActivityProcessTracker.dump(pw, "  ");
            mActiveUids.dump(pw, "  ");
            if (mDemoteTopAppReasons != 0) {
                pw.println("  mDemoteTopAppReasons=" + mDemoteTopAppReasons);
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpActivityContainersLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER CONTAINERS (dumpsys activity containers)");
        mRootWindowContainer.dumpChildrenNames(pw, " ");
        pw.println(" ");
    }

    void dumpActivityStarterLocked(PrintWriter pw, String dumpPackage) {
        pw.println("ACTIVITY MANAGER STARTER (dumpsys activity starter)");
        getActivityStartController().dump(pw, "", dumpPackage);
    }

    /** Dumps installed packages having app-specific config. */
    void dumpInstalledPackagesConfig(PrintWriter pw) {
        mPackageConfigPersister.dump(pw, getCurrentUserId());
    }

    /**
     * There are three things that cmd can be:
     * - a flattened component name that matches an existing activity
     * - the cmd arg isn't the flattened component name of an existing activity:
     * dump all activity whose component contains the cmd as a substring
     * - A hex number of the ActivityRecord object instance.
     * <p>
     * The caller should not hold lock when calling this method because it will wait for the
     * activities to complete the dump.
     *
     * @param dumpVisibleRootTasksOnly dump activity with {@param name} only if in a visible root
     *                                 task
     * @param dumpFocusedRootTaskOnly  dump activity with {@param name} only if in the focused
     *                                 root task
     */
    protected boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll, boolean dumpVisibleRootTasksOnly,
            boolean dumpFocusedRootTaskOnly, int displayIdFilter, @UserIdInt int userId) {
        ArrayList<ActivityRecord> activities;

        synchronized (mGlobalLock) {
            activities = mRootWindowContainer.getDumpActivities(name, dumpVisibleRootTasksOnly,
                    dumpFocusedRootTaskOnly, userId);
        }

        if (activities.size() <= 0) {
            return false;
        }

        String[] newArgs = new String[args.length - opti];
        System.arraycopy(args, opti, newArgs, 0, args.length - opti);

        Task lastTask = null;
        boolean needSep = false;
        boolean printedAnything = false;
        for (int i = activities.size() - 1; i >= 0; i--) {
            ActivityRecord r = activities.get(i);
            if (needSep) {
                pw.println();
            }
            needSep = true;
            synchronized (mGlobalLock) {
                Task task = r.getTask();
                int displayId = task.getDisplayId();
                if (displayIdFilter != INVALID_DISPLAY && displayId != displayIdFilter) {
                    continue;
                }
                if (lastTask != task) {
                    printedAnything = true;
                    lastTask = task;
                    pw.print("TASK ");
                    pw.print(lastTask.affinity);
                    pw.print(" id=");
                    pw.print(lastTask.mTaskId);
                    pw.print(" userId=");
                    pw.print(lastTask.mUserId);
                    printDisplayInfoAndNewLine(pw, r);
                    if (dumpAll) {
                        lastTask.dump(pw, "  ");
                    }
                }
            }
            dumpActivity("  ", fd, pw, activities.get(i), newArgs, dumpAll);
        }
        if (!printedAnything) {
            // Typically happpens when no task matches displayIdFilter
            pw.println("(nothing)");
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpActivity() on the thread of the specified activity if
     * there is a thread associated with the activity.
     */
    private void dumpActivity(String prefix, FileDescriptor fd, PrintWriter pw,
            ActivityRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        IApplicationThread appThread = null;
        synchronized (mGlobalLock) {
            pw.print(prefix);
            pw.print("ACTIVITY ");
            pw.print(r.shortComponentName);
            pw.print(" ");
            pw.print(Integer.toHexString(System.identityHashCode(r)));
            pw.print(" pid=");
            if (r.hasProcess()) {
                pw.print(r.app.getPid());
                appThread = r.app.getThread();
            } else {
                pw.print("(not running)");
            }
            pw.print(" userId=");
            pw.print(r.mUserId);
            pw.print(" uid=");
            pw.print(r.getUid());
            printDisplayInfoAndNewLine(pw, r);
            if (dumpAll) {
                r.dump(pw, innerPrefix, /* dumpAll= */ true);
            }
        }
        if (appThread != null) {
            // flush anything that is already in the PrintWriter since the thread is going
            // to write to the file descriptor directly
            pw.flush();
            try (TransferPipe tp = new TransferPipe()) {
                appThread.dumpActivity(tp.getWriteFd(), r.token, innerPrefix, args);
                tp.go(fd);
            } catch (IOException e) {
                pw.println(innerPrefix + "Failure while dumping the activity: " + e);
            } catch (RemoteException e) {
                pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
            }
        }
    }

    private void printDisplayInfoAndNewLine(PrintWriter pw, ActivityRecord r) {
        pw.print(" displayId=");
        DisplayContent displayContent = r.getDisplayContent();
        if (displayContent == null) {
            pw.println("N/A");
            return;
        }
        Display display = displayContent.getDisplay();
        pw.print(display.getDisplayId());
        pw.print("(type=");
        pw.print(Display.typeToString(display.getType()));
        pw.println(")");
    }

    private void writeSleepStateToProto(ProtoOutputStream proto, int wakeFullness,
            boolean testPssMode) {
        final long sleepToken = proto.start(ActivityManagerServiceDumpProcessesProto.SLEEP_STATUS);
        proto.write(ActivityManagerServiceDumpProcessesProto.SleepStatus.WAKEFULNESS,
                PowerManagerInternal.wakefulnessToProtoEnum(wakeFullness));
        final int tokenSize = mRootWindowContainer.mSleepTokens.size();
        for (int i = 0; i < tokenSize; i++) {
            final RootWindowContainer.SleepToken st =
                    mRootWindowContainer.mSleepTokens.valueAt(i);
            proto.write(ActivityManagerServiceDumpProcessesProto.SleepStatus.SLEEP_TOKENS,
                    st.toString());
        }
        proto.write(ActivityManagerServiceDumpProcessesProto.SleepStatus.SLEEPING, mSleeping);
        proto.write(ActivityManagerServiceDumpProcessesProto.SleepStatus.SHUTTING_DOWN,
                mShuttingDown);
        proto.write(ActivityManagerServiceDumpProcessesProto.SleepStatus.TEST_PSS_MODE,
                testPssMode);
        proto.end(sleepToken);
    }

    int getCurrentUserId() {
        return mAmInternal.getCurrentUserId();
    }

    static void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    public Configuration getConfiguration() {
        Configuration ci;
        synchronized (mGlobalLock) {
            ci = new Configuration(getGlobalConfigurationForCallingPid());
            ci.userSetLocale = false;
        }
        return ci;
    }

    /**
     * Current global configuration information. Contains general settings for the entire system,
     * also corresponds to the merged configuration of the default display.
     */
    Configuration getGlobalConfiguration() {
        // Return default configuration before mRootWindowContainer initialized, which happens
        // while initializing process record for system, see {@link
        // ActivityManagerService#setSystemProcess}.
        return mRootWindowContainer != null ? mRootWindowContainer.getConfiguration()
                : new Configuration();
    }

    boolean updateConfigurationLocked(Configuration values, ActivityRecord starting,
            boolean initLocale) {
        return updateConfigurationLocked(values, starting, initLocale, false /* deferResume */);
    }

    boolean updateConfigurationLocked(Configuration values, ActivityRecord starting,
            boolean initLocale, boolean deferResume) {
        // pass UserHandle.USER_NULL as userId because we don't persist configuration for any user
        return updateConfigurationLocked(values, starting, initLocale, false /* persistent */,
                UserHandle.USER_NULL, deferResume);
    }

    public void updatePersistentConfiguration(Configuration values, @UserIdInt int userId) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                // Window configuration is unrelated to persistent configuration (e.g. font scale,
                // locale). Unset it to avoid affecting the current display configuration.
                values.windowConfiguration.setToDefaults();
                updateConfigurationLocked(values, null, false, true, userId,
                        false /* deferResume */);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private boolean updateConfigurationLocked(Configuration values, ActivityRecord starting,
            boolean initLocale, boolean persistent, int userId, boolean deferResume) {
        return updateConfigurationLocked(values, starting, initLocale, persistent, userId,
                deferResume, null /* result */);
    }

    /**
     * Do either or both things: (1) change the current configuration, and (2)
     * make sure the given activity is running with the (now) current
     * configuration.  Returns true if the activity has been left running, or
     * false if <var>starting</var> is being destroyed to match the new
     * configuration.
     *
     * @param userId is only used when persistent parameter is set to true to persist configuration
     *               for that particular user
     */
    boolean updateConfigurationLocked(Configuration values, ActivityRecord starting,
            boolean initLocale, boolean persistent, int userId, boolean deferResume,
            ActivityTaskManagerService.UpdateConfigurationResult result) {
        int changes = 0;
        boolean kept = true;

        deferWindowLayout();
        try {
            if (values != null) {
                changes = updateGlobalConfigurationLocked(values, initLocale, persistent, userId);
            }

            if (!deferResume) {
                kept = ensureConfigAndVisibilityAfterUpdate(starting, changes);
            }
        } finally {
            continueWindowLayout();
        }

        if (result != null) {
            result.changes = changes;
            result.activityRelaunched = !kept;
        }
        return kept;
    }

    /** Update default (global) configuration and notify listeners about changes. */
    int updateGlobalConfigurationLocked(@NonNull Configuration values, boolean initLocale,
            boolean persistent, int userId) {

        mTempConfig.setTo(getGlobalConfiguration());
        final int changes = mTempConfig.updateFrom(values);
        if (changes == 0) {
            return 0;
        }

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateGlobalConfiguration");
        ProtoLog.i(WM_DEBUG_CONFIGURATION, "Updating global configuration "
                + "to: %s", values);
        writeConfigurationChanged(changes);
        FrameworkStatsLog.write(FrameworkStatsLog.RESOURCE_CONFIGURATION_CHANGED,
                values.colorMode,
                values.densityDpi,
                values.fontScale,
                values.hardKeyboardHidden,
                values.keyboard,
                values.keyboardHidden,
                values.mcc,
                values.mnc,
                values.navigation,
                values.navigationHidden,
                values.orientation,
                values.screenHeightDp,
                values.screenLayout,
                values.screenWidthDp,
                values.smallestScreenWidthDp,
                values.touchscreen,
                values.uiMode);

        // Note: certain tests currently run as platform_app which is not allowed
        // to set debug system properties. To ensure that system properties are set
        // only when allowed, we check the current UID.
        if (Process.myUid() == Process.SYSTEM_UID) {
            if (values.mcc != 0) {
                SystemProperties.set("debug.tracing.mcc", Integer.toString(values.mcc));
            }
            if (values.mnc != 0) {
                SystemProperties.set("debug.tracing.mnc", Integer.toString(values.mnc));
            }
        }

        if (!initLocale && !values.getLocales().isEmpty() && values.userSetLocale) {
            final LocaleList locales = values.getLocales();
            int bestLocaleIndex = 0;
            if (locales.size() > 1) {
                if (mSupportedSystemLocales == null) {
                    mSupportedSystemLocales = Resources.getSystem().getAssets().getLocales();
                }
                bestLocaleIndex = Math.max(0, locales.getFirstMatchIndex(mSupportedSystemLocales));
            }
            SystemProperties.set("persist.sys.locale",
                    locales.get(bestLocaleIndex).toLanguageTag());
            LocaleList.setDefault(locales, bestLocaleIndex);
        }

        mTempConfig.seq = increaseConfigurationSeqLocked();

        Slog.i(TAG, "Config changes=" + Integer.toHexString(changes) + " " + mTempConfig);
        // TODO(multi-display): Update UsageEvents#Event to include displayId.
        mUsageStatsInternal.reportConfigurationChange(mTempConfig, mAmInternal.getCurrentUserId());

        // TODO: If our config changes, should we auto dismiss any currently showing dialogs?
        updateShouldShowDialogsLocked(mTempConfig);

        AttributeCache ac = AttributeCache.instance();
        if (ac != null) {
            ac.updateConfiguration(mTempConfig);
        }

        // Make sure all resources in our process are updated right now, so that anyone who is going
        // to retrieve resource values after we return will be sure to get the new ones. This is
        // especially important during boot, where the first config change needs to guarantee all
        // resources have that config before following boot code is executed.
        mSystemThread.applyConfigurationToResources(mTempConfig);

        if (persistent && Settings.System.hasInterestingConfigurationChanges(changes)) {
            final Message msg = PooledLambda.obtainMessage(
                    ActivityTaskManagerService::sendPutConfigurationForUserMsg,
                    this, userId, new Configuration(mTempConfig));
            mH.sendMessage(msg);
        }

        SparseArray<WindowProcessController> pidMap = mProcessMap.getPidMap();
        for (int i = pidMap.size() - 1; i >= 0; i--) {
            final int pid = pidMap.keyAt(i);
            final WindowProcessController app = pidMap.get(pid);
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Update process config of %s to new "
                    + "config %s", app.mName, mTempConfig);
            app.onConfigurationChanged(mTempConfig);
        }

        final Message msg = PooledLambda.obtainMessage(
                ActivityManagerInternal::broadcastGlobalConfigurationChanged,
                mAmInternal, changes, initLocale);
        mH.sendMessage(msg);

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "RootConfigChange");
        // Update stored global config and notify everyone about the change.
        mRootWindowContainer.onConfigurationChanged(mTempConfig);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        return changes;
    }

    private int increaseAssetConfigurationSeq() {
        mGlobalAssetsSeq = Math.max(++mGlobalAssetsSeq, 1);
        return mGlobalAssetsSeq;
    }

    /**
     * Update the asset configuration and increase the assets sequence number.
     * @param processes the processes that needs to update the asset configuration
     */
    public void updateAssetConfiguration(List<WindowProcessController> processes,
            boolean updateFrameworkRes) {
        synchronized (mGlobalLock) {
            final int assetSeq = increaseAssetConfigurationSeq();

            if (updateFrameworkRes) {
                Configuration newConfig = new Configuration();
                newConfig.assetsSeq = assetSeq;
                updateConfiguration(newConfig);
            }

            // Always update the override of every process so the asset sequence of the process is
            // always greater than or equal to the global configuration.
            for (int i = processes.size() - 1; i >= 0; i--) {
                final WindowProcessController wpc = processes.get(i);
                wpc.updateAssetConfiguration(assetSeq);
            }
        }
    }

    void startLaunchPowerMode(@PowerModeReason int reason) {
        if (mPowerManagerInternal != null) {
            mPowerManagerInternal.setPowerMode(Mode.LAUNCH, true);
        }
        mLaunchPowerModeReasons |= reason;
        if ((reason & POWER_MODE_REASON_UNKNOWN_VISIBILITY) != 0) {
            if (mRetainPowerModeAndTopProcessState) {
                mH.removeMessages(H.END_POWER_MODE_UNKNOWN_VISIBILITY_MSG);
            }
            mRetainPowerModeAndTopProcessState = true;
            mH.sendEmptyMessageDelayed(H.END_POWER_MODE_UNKNOWN_VISIBILITY_MSG,
                    POWER_MODE_UNKNOWN_VISIBILITY_TIMEOUT_MS);
            Slog.d(TAG, "Temporarily retain top process state for launching app");
        }
    }

    void endLaunchPowerMode(@PowerModeReason int reason) {
        if (mLaunchPowerModeReasons == 0) return;
        mLaunchPowerModeReasons &= ~reason;

        if ((mLaunchPowerModeReasons & POWER_MODE_REASON_UNKNOWN_VISIBILITY) != 0) {
            boolean allResolved = true;
            for (int i = mRootWindowContainer.getChildCount() - 1; i >= 0; i--) {
                allResolved &= mRootWindowContainer.getChildAt(i).mUnknownAppVisibilityController
                        .allResolved();
            }
            if (allResolved) {
                mLaunchPowerModeReasons &= ~POWER_MODE_REASON_UNKNOWN_VISIBILITY;
                mRetainPowerModeAndTopProcessState = false;
                mH.removeMessages(H.END_POWER_MODE_UNKNOWN_VISIBILITY_MSG);
            }
        }

        if (mLaunchPowerModeReasons == 0 && mPowerManagerInternal != null) {
            mPowerManagerInternal.setPowerMode(Mode.LAUNCH, false);
        }
    }

    /** @see WindowSurfacePlacer#deferLayout */
    void deferWindowLayout() {
        if (!mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            // Reset the reasons at the first entrance because we only care about the changes in the
            // deferred scope.
            mLayoutReasons = 0;
        }

        mWindowManager.mWindowPlacerLocked.deferLayout();
    }

    /** @see WindowSurfacePlacer#continueLayout */
    void continueWindowLayout() {
        mWindowManager.mWindowPlacerLocked.continueLayout(mLayoutReasons != 0);
        if (DEBUG_ALL && !mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            Slog.i(TAG, "continueWindowLayout reason=" + mLayoutReasons);
        }
    }

    /**
     * If a reason is added between {@link #deferWindowLayout} and {@link #continueWindowLayout},
     * it will make sure {@link WindowSurfacePlacer#performSurfacePlacement} is called when the last
     * defer count is gone.
     */
    void addWindowLayoutReasons(@LayoutReason int reasons) {
        mLayoutReasons |= reasons;
    }

    private void updateEventDispatchingLocked(boolean booted) {
        mWindowManager.setEventDispatching(booted && !mShuttingDown);
    }

    private void sendPutConfigurationForUserMsg(int userId, Configuration config) {
        final ContentResolver resolver = mContext.getContentResolver();
        Settings.System.putConfigurationForUser(resolver, config, userId);
    }

    boolean isActivityStartsLoggingEnabled() {
        return mAmInternal.isActivityStartsLoggingEnabled();
    }

    boolean isBackgroundActivityStartsEnabled() {
        return mAmInternal.isBackgroundActivityStartsEnabled();
    }

    static long getInputDispatchingTimeoutMillisLocked(ActivityRecord r) {
        if (r == null || !r.hasProcess()) {
            return DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        }
        return getInputDispatchingTimeoutMillisLocked(r.app);
    }

    private static long getInputDispatchingTimeoutMillisLocked(WindowProcessController r) {
        if (r == null) {
            return DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        }
        return r.getInputDispatchingTimeoutMillis();
    }

    /**
     * Decide based on the configuration whether we should show the ANR,
     * crash, etc dialogs.  The idea is that if there is no affordance to
     * press the on-screen buttons, or the user experience would be more
     * greatly impacted than the crash itself, we shouldn't show the dialog.
     *
     * A thought: SystemUI might also want to get told about this, the Power
     * dialog / global actions also might want different behaviors.
     */
    private void updateShouldShowDialogsLocked(Configuration config) {
        final boolean inputMethodExists = !(config.keyboard == Configuration.KEYBOARD_NOKEYS
                && config.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
                && config.navigation == Configuration.NAVIGATION_NONAV);
        final boolean hideDialogsSet = Settings.Global.getInt(mContext.getContentResolver(),
                HIDE_ERROR_DIALOGS, 0) != 0;
        mShowDialogs = inputMethodExists
                && ActivityTaskManager.currentUiModeSupportsErrorDialogs(config)
                && !hideDialogsSet;
    }

    private void updateFontScaleIfNeeded(@UserIdInt int userId) {
        if (userId != getCurrentUserId()) {
            return;
        }

        final float scaleFactor = Settings.System.getFloatForUser(mContext.getContentResolver(),
                FONT_SCALE, 1.0f, userId);

        synchronized (mGlobalLock) {
            if (getGlobalConfiguration().fontScale == scaleFactor) {
                return;
            }

            final Configuration configuration
                    = mWindowManager.computeNewConfiguration(DEFAULT_DISPLAY);
            configuration.fontScale = scaleFactor;
            updatePersistentConfiguration(configuration, userId);
        }
    }

    private void updateFontWeightAdjustmentIfNeeded(@UserIdInt int userId) {
        if (userId != getCurrentUserId()) {
            return;
        }

        final int fontWeightAdjustment =
                Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FONT_WEIGHT_ADJUSTMENT,
                        Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED,
                        userId);

        synchronized (mGlobalLock) {
            if (getGlobalConfiguration().fontWeightAdjustment == fontWeightAdjustment) {
                return;
            }

            final Configuration configuration =
                    mWindowManager.computeNewConfiguration(DEFAULT_DISPLAY);
            configuration.fontWeightAdjustment = fontWeightAdjustment;
            updatePersistentConfiguration(configuration, userId);
        }
    }

    // Actually is sleeping or shutting down or whatever else in the future
    // is an inactive state.
    boolean isSleepingOrShuttingDownLocked() {
        return isSleepingLocked() || mShuttingDown;
    }

    boolean isSleepingLocked() {
        return mSleeping;
    }

    /** Update AMS states when an activity is resumed. */
    void setLastResumedActivityUncheckLocked(ActivityRecord r, String reason) {
        final Task task = r.getTask();
        if (task.isActivityTypeStandard()) {
            if (mCurAppTimeTracker != r.appTimeTracker) {
                // We are switching app tracking.  Complete the current one.
                if (mCurAppTimeTracker != null) {
                    mCurAppTimeTracker.stop();
                    mH.obtainMessage(
                            REPORT_TIME_TRACKER_MSG, mCurAppTimeTracker).sendToTarget();
                    mRootWindowContainer.clearOtherAppTimeTrackers(r.appTimeTracker);
                    mCurAppTimeTracker = null;
                }
                if (r.appTimeTracker != null) {
                    mCurAppTimeTracker = r.appTimeTracker;
                    startTimeTrackingFocusedActivityLocked();
                }
            } else {
                startTimeTrackingFocusedActivityLocked();
            }
        } else {
            r.appTimeTracker = null;
        }
        // TODO: VI Maybe r.task.voiceInteractor || r.voiceInteractor != null
        // TODO: Probably not, because we don't want to resume voice on switching
        // back to this activity
        if (task.voiceInteractor != null) {
            startRunningVoiceLocked(task.voiceSession, r.info.applicationInfo.uid);
        } else {
            finishRunningVoiceLocked();

            if (mLastResumedActivity != null) {
                final IVoiceInteractionSession session;

                final Task lastResumedActivityTask = mLastResumedActivity.getTask();
                if (lastResumedActivityTask != null
                        && lastResumedActivityTask.voiceSession != null) {
                    session = lastResumedActivityTask.voiceSession;
                } else {
                    session = mLastResumedActivity.voiceSession;
                }

                if (session != null) {
                    // We had been in a voice interaction session, but now focused has
                    // move to something different.  Just finish the session, we can't
                    // return to it and retain the proper state and synchronization with
                    // the voice interaction service.
                    finishVoiceTask(session);
                }
            }
        }

        if (mLastResumedActivity != null && r.mUserId != mLastResumedActivity.mUserId) {
            mAmInternal.sendForegroundProfileChanged(r.mUserId);
        }
        final Task prevTask = mLastResumedActivity != null ? mLastResumedActivity.getTask() : null;

        updateResumedAppTrace(r);
        mLastResumedActivity = r;

        // Don't take focus when transient launching. We don't want the app to know anything
        // until we've committed to the gesture. The focus will be transferred at the end of
        // the transition (if the transient launch is committed) or early if explicitly requested
        // via `setFocused*`.
        boolean focusedAppChanged = false;
        if (!getTransitionController().isTransientCollect(r)) {
            focusedAppChanged = r.mDisplayContent.setFocusedApp(r);
            if (focusedAppChanged) {
                mWindowManager.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                        true /*updateInputWindows*/);
            }
        }
        if (task != prevTask) {
            mTaskSupervisor.mRecentTasks.add(task);
        }

        if (focusedAppChanged) {
            applyUpdateLockStateLocked(r);
        }
        if (mVrController.mVrService != null) {
            applyUpdateVrModeLocked(r);
        }

        EventLogTags.writeWmSetResumedActivity(r.mUserId, r.shortComponentName, reason);
    }

    final class SleepTokenAcquirerImpl implements ActivityTaskManagerInternal.SleepTokenAcquirer {
        private final String mTag;
        private final SparseArray<RootWindowContainer.SleepToken> mSleepTokens =
                new SparseArray<>();

        SleepTokenAcquirerImpl(@NonNull String tag) {
            mTag = tag;
        }

        @Override
        public void acquire(int displayId) {
            acquire(displayId, false /* isSwappingDisplay */);
        }

        @Override
        public void acquire(int displayId, boolean isSwappingDisplay) {
            synchronized (mGlobalLock) {
                if (!mSleepTokens.contains(displayId)) {
                    mSleepTokens.append(displayId,
                            mRootWindowContainer.createSleepToken(mTag, displayId,
                                    isSwappingDisplay));
                    updateSleepIfNeededLocked();
                }
            }
        }

        @Override
        public void release(int displayId) {
            synchronized (mGlobalLock) {
                final RootWindowContainer.SleepToken token = mSleepTokens.get(displayId);
                if (token != null) {
                    mRootWindowContainer.removeSleepToken(token);
                    mSleepTokens.remove(displayId);
                }
            }
        }
    }

    void updateSleepIfNeededLocked() {
        final boolean shouldSleep = !mRootWindowContainer.hasAwakeDisplay();
        final boolean wasSleeping = mSleeping;
        boolean updateOomAdj = false;

        if (!shouldSleep) {
            // If wasSleeping is true, we need to wake up activity manager state from when
            // we started sleeping. In either case, we need to apply the sleep tokens, which
            // will wake up root tasks or put them to sleep as appropriate.
            if (wasSleeping) {
                mSleeping = false;
                FrameworkStatsLog.write(FrameworkStatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED,
                        FrameworkStatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED__STATE__AWAKE);
                startTimeTrackingFocusedActivityLocked();
                mTopProcessState = ActivityManager.PROCESS_STATE_TOP;
                Slog.d(TAG, "Top Process State changed to PROCESS_STATE_TOP");
                mTaskSupervisor.comeOutOfSleepIfNeededLocked();
            }
            mRootWindowContainer.applySleepTokens(true /* applyToRootTasks */);
            if (wasSleeping) {
                updateOomAdj = true;
            }
        } else if (!mSleeping && shouldSleep) {
            mSleeping = true;
            FrameworkStatsLog.write(FrameworkStatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED,
                    FrameworkStatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED__STATE__ASLEEP);
            if (mCurAppTimeTracker != null) {
                mCurAppTimeTracker.stop();
            }
            mTopProcessState = ActivityManager.PROCESS_STATE_TOP_SLEEPING;
            Slog.d(TAG, "Top Process State changed to PROCESS_STATE_TOP_SLEEPING");
            mTaskSupervisor.goingToSleepLocked();
            updateResumedAppTrace(null /* resumed */);
            updateOomAdj = true;
        }
        if (updateOomAdj) {
            updateOomAdj();
        }
    }

    void updateOomAdj() {
        mH.removeCallbacks(mUpdateOomAdjRunnable);
        mH.post(mUpdateOomAdjRunnable);
    }

    void updateCpuStats() {
        mH.post(mAmInternal::updateCpuStats);
    }

    void updateBatteryStats(ActivityRecord component, boolean resumed) {
        final Message m = PooledLambda.obtainMessage(ActivityManagerInternal::updateBatteryStats,
                mAmInternal, component.mActivityComponent, component.app.mUid, component.mUserId,
                resumed);
        mH.sendMessage(m);
    }

    void updateTopApp(ActivityRecord topResumedActivity) {
        final ActivityRecord top = topResumedActivity != null ? topResumedActivity
                // If there is no resumed activity, it will choose the pausing or focused activity.
                : mRootWindowContainer.getTopResumedActivity();
        mTopApp = top != null ? top.app : null;
        if (mTopApp == mPreviousProcess) mPreviousProcess = null;
    }

    /**
     * The process state of previous activity is more important than the regular background to keep
     * around in case the user wants to return to it.
     */
    void updatePreviousProcess(ActivityRecord stoppedActivity) {
        if (stoppedActivity.app != null && mTopApp != null
                // Don't replace the previous process if the stopped one is the top, e.g. sleeping.
                && stoppedActivity.app != mTopApp
                // The stopped activity must have been visible later than the previous.
                && stoppedActivity.lastVisibleTime > mPreviousProcessVisibleTime
                // Home has its own retained state, so don't let it occupy the previous.
                && stoppedActivity.app != mHomeProcess) {
            mPreviousProcess = stoppedActivity.app;
            mPreviousProcessVisibleTime = stoppedActivity.lastVisibleTime;
        }
    }

    void updateActivityUsageStats(ActivityRecord activity, int event) {
        ComponentName taskRoot = null;
        int taskId = INVALID_TASK_ID;
        final Task task = activity.getTask();
        if (task != null) {
            final ActivityRecord rootActivity = task.getRootActivity();
            if (rootActivity != null) {
                taskRoot = rootActivity.mActivityComponent;
            }
            taskId = task.mTaskId;
        }
        final Message m = PooledLambda.obtainMessage(
                ActivityManagerInternal::updateActivityUsageStats, mAmInternal,
                activity.mActivityComponent, activity.mUserId, event, activity.token, taskRoot,
                new ActivityId(taskId, activity.shareableActivityToken));
        mH.sendMessage(m);
    }

    void startProcessAsync(ActivityRecord activity, boolean knownToBeDead, boolean isTop,
            String hostingType) {
        try {
            if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "dispatchingStartProcess:"
                        + activity.processName);
            }
            // Post message to start process to avoid possible deadlock of calling into AMS with the
            // ATMS lock held.
            final Message m = PooledLambda.obtainMessage(ActivityManagerInternal::startProcess,
                    mAmInternal, activity.processName, activity.info.applicationInfo, knownToBeDead,
                    isTop, hostingType, activity.intent.getComponent());
            mH.sendMessage(m);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    void setBooting(boolean booting) {
        mAmInternal.setBooting(booting);
    }

    boolean isBooting() {
        return mAmInternal.isBooting();
    }

    void setBooted(boolean booted) {
        mAmInternal.setBooted(booted);
    }

    boolean isBooted() {
        return mAmInternal.isBooted();
    }

    void postFinishBooting(boolean finishBooting, boolean enableScreen) {
        mH.post(() -> {
            if (finishBooting) {
                mAmInternal.finishBooting();
            }
            if (enableScreen) {
                mInternal.enableScreenAfterBoot(isBooted());
            }
        });
    }

    void setHeavyWeightProcess(ActivityRecord root) {
        mHeavyWeightProcess = root.app;
        final Message m = PooledLambda.obtainMessage(
                ActivityTaskManagerService::postHeavyWeightProcessNotification, this,
                root.app, root.intent, root.mUserId);
        mH.sendMessage(m);
    }

    void clearHeavyWeightProcessIfEquals(WindowProcessController proc) {
        if (mHeavyWeightProcess == null || mHeavyWeightProcess != proc) {
            return;
        }

        mHeavyWeightProcess = null;
        final Message m = PooledLambda.obtainMessage(
                ActivityTaskManagerService::cancelHeavyWeightProcessNotification, this,
                proc.mUserId);
        mH.sendMessage(m);
    }

    private void cancelHeavyWeightProcessNotification(int userId) {
        final INotificationManager inm = NotificationManager.getService();
        if (inm == null) {
            return;
        }
        try {
            inm.cancelNotificationWithTag("android", "android", null,
                    SystemMessage.NOTE_HEAVY_WEIGHT_NOTIFICATION, userId);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error canceling notification for service", e);
        } catch (RemoteException e) {
        }

    }

    private void postHeavyWeightProcessNotification(
            WindowProcessController proc, Intent intent, int userId) {
        if (proc == null) {
            return;
        }

        final INotificationManager inm = NotificationManager.getService();
        if (inm == null) {
            return;
        }

        try {
            Context context = mContext.createPackageContext(proc.mInfo.packageName, 0);
            String text = mContext.getString(R.string.heavy_weight_notification,
                    context.getApplicationInfo().loadLabel(context.getPackageManager()));
            Notification notification =
                    new Notification.Builder(context,
                            SystemNotificationChannels.HEAVY_WEIGHT_APP)
                            .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                            .setWhen(0)
                            .setOngoing(true)
                            .setTicker(text)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(text)
                            .setContentText(
                                    mContext.getText(R.string.heavy_weight_notification_detail))
                            .setContentIntent(PendingIntent.getActivityAsUser(mContext, 0,
                                    intent, PendingIntent.FLAG_CANCEL_CURRENT
                                            | PendingIntent.FLAG_IMMUTABLE, null,
                                    new UserHandle(userId)))
                            .build();
            try {
                inm.enqueueNotificationWithTag("android", "android", null,
                        SystemMessage.NOTE_HEAVY_WEIGHT_NOTIFICATION, notification, userId);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error showing notification for heavy-weight app", e);
            } catch (RemoteException e) {
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Unable to create context for heavy notification", e);
        }

    }

    IIntentSender getIntentSenderLocked(int type, String packageName, String featureId,
            int callingUid, int userId, IBinder token, String resultWho, int requestCode,
            Intent[] intents, String[] resolvedTypes, int flags, Bundle bOptions) {

        ActivityRecord activity = null;
        if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
            activity = ActivityRecord.isInRootTaskLocked(token);
            if (activity == null) {
                Slog.w(TAG, "Failed createPendingResult: activity " + token
                        + " not in any root task");
                return null;
            }
            if (activity.finishing) {
                Slog.w(TAG, "Failed createPendingResult: activity " + activity + " is finishing");
                return null;
            }
        }

        final PendingIntentRecord rec = mPendingIntentController.getIntentSender(type, packageName,
                featureId, callingUid, userId, token, resultWho, requestCode, intents,
                resolvedTypes, flags, bOptions);
        final boolean noCreate = (flags & PendingIntent.FLAG_NO_CREATE) != 0;
        if (noCreate) {
            return rec;
        }
        if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
            if (activity.pendingResults == null) {
                activity.pendingResults = new HashSet<>();
            }
            activity.pendingResults.add(rec.ref);
        }
        return rec;
    }

    // TODO(b/111541062): Update app time tracking to make it aware of multiple resumed activities
    private void startTimeTrackingFocusedActivityLocked() {
        final ActivityRecord resumedActivity = mRootWindowContainer.getTopResumedActivity();
        if (!mSleeping && mCurAppTimeTracker != null && resumedActivity != null) {
            mCurAppTimeTracker.start(resumedActivity.packageName);
        }
    }

    private void updateResumedAppTrace(@Nullable ActivityRecord resumed) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_WINDOW_MANAGER)) {
            if (mTracedResumedActivity != null) {
                Trace.asyncTraceForTrackEnd(TRACE_TAG_WINDOW_MANAGER,
                        "Focused app", System.identityHashCode(mTracedResumedActivity));
            }
            if (resumed != null) {
                Trace.asyncTraceForTrackBegin(TRACE_TAG_WINDOW_MANAGER,
                        "Focused app", resumed.mActivityComponent.flattenToShortString(),
                        System.identityHashCode(resumed));
            }
        }
        mTracedResumedActivity = resumed;
    }

    /** Applies latest configuration and/or visibility updates if needed. */
    boolean ensureConfigAndVisibilityAfterUpdate(ActivityRecord starting, int changes) {
        boolean kept = true;
        final Task mainRootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
        // mainRootTask is null during startup.
        if (mainRootTask != null) {
            if (changes != 0 && starting == null) {
                // If the configuration changed, and the caller is not already
                // in the process of starting an activity, then find the top
                // activity to check if its configuration needs to change.
                starting = mainRootTask.topRunningActivity();
            }

            if (starting != null) {
                kept = starting.ensureActivityConfiguration(changes,
                        false /* preserveWindow */);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                mRootWindowContainer.ensureActivitiesVisible(starting, changes,
                        !PRESERVE_WINDOWS);
            }
        }

        return kept;
    }

    void scheduleAppGcsLocked() {
        mH.post(() -> mAmInternal.scheduleAppGcs());
    }

    CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        return mCompatModePackages.compatibilityInfoForPackageLocked(ai);
    }

    /**
     * Returns the PackageManager. Used by classes hosted by {@link ActivityTaskManagerService}. The
     * PackageManager could be unavailable at construction time and therefore needs to be accessed
     * on demand.
     */
    IPackageManager getPackageManager() {
        return AppGlobals.getPackageManager();
    }

    PackageManagerInternal getPackageManagerInternalLocked() {
        if (mPmInternal == null) {
            mPmInternal = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPmInternal;
    }

    ComponentName getSysUiServiceComponentLocked() {
        if (mSysUiServiceComponent == null) {
            final PackageManagerInternal pm = getPackageManagerInternalLocked();
            mSysUiServiceComponent = pm.getSystemUiServiceComponent();
        }
        return mSysUiServiceComponent;
    }

    PermissionPolicyInternal getPermissionPolicyInternal() {
        if (mPermissionPolicyInternal == null) {
            mPermissionPolicyInternal = LocalServices.getService(PermissionPolicyInternal.class);
        }
        return mPermissionPolicyInternal;
    }

    StatusBarManagerInternal getStatusBarManagerInternal() {
        if (mStatusBarManagerInternal == null) {
            mStatusBarManagerInternal = LocalServices.getService(StatusBarManagerInternal.class);
        }
        return mStatusBarManagerInternal;
    }

    WallpaperManagerInternal getWallpaperManagerInternal() {
        if (mWallpaperManagerInternal == null) {
            mWallpaperManagerInternal = LocalServices.getService(WallpaperManagerInternal.class);
        }
        return mWallpaperManagerInternal;
    }

    AppWarnings getAppWarningsLocked() {
        return mAppWarnings;
    }

    Intent getHomeIntent() {
        Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
        intent.setComponent(mTopComponent);
        intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
        if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        return intent;
    }

    /**
     * Return the intent set with {@link Intent#CATEGORY_SECONDARY_HOME} to resolve secondary home
     * activities.
     *
     * @param preferredPackage Specify a preferred package name, otherwise use the package name
     *                         defined in config_secondaryHomePackage.
     * @return the intent set with {@link Intent#CATEGORY_SECONDARY_HOME}
     */
    Intent getSecondaryHomeIntent(String preferredPackage) {
        final Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
        final boolean useSystemProvidedLauncher = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useSystemProvidedLauncherForSecondary);
        if (preferredPackage == null || useSystemProvidedLauncher) {
            // Using the package name stored in config if no preferred package name or forced.
            final String secondaryHomePackage = mContext.getResources().getString(
                    com.android.internal.R.string.config_secondaryHomePackage);
            intent.setPackage(secondaryHomePackage);
        } else {
            intent.setPackage(preferredPackage);
        }
        intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
        if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            intent.addCategory(Intent.CATEGORY_SECONDARY_HOME);
        }
        return intent;
    }

    ApplicationInfo getAppInfoForUser(ApplicationInfo info, int userId) {
        if (info == null) return null;
        ApplicationInfo newInfo = new ApplicationInfo(info);
        newInfo.initForUser(userId);
        return newInfo;
    }

    WindowProcessController getProcessController(String processName, int uid) {
        if (uid == SYSTEM_UID) {
            // The system gets to run in any process. If there are multiple processes with the same
            // uid, just pick the first (this should never happen).
            final SparseArray<WindowProcessController> procs =
                    mProcessNames.getMap().get(processName);
            if (procs == null) return null;
            final int procCount = procs.size();
            for (int i = 0; i < procCount; i++) {
                final int procUid = procs.keyAt(i);
                if (UserHandle.isApp(procUid) || !UserHandle.isSameUser(procUid, uid)) {
                    // Don't use an app process or different user process for system component.
                    continue;
                }
                return procs.valueAt(i);
            }
        }

        return mProcessNames.get(processName, uid);
    }

    WindowProcessController getProcessController(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }

        final IBinder threadBinder = thread.asBinder();
        final ArrayMap<String, SparseArray<WindowProcessController>> pmap = mProcessNames.getMap();
        for (int i = pmap.size() - 1; i >= 0; i--) {
            final SparseArray<WindowProcessController> procs = pmap.valueAt(i);
            for (int j = procs.size() - 1; j >= 0; j--) {
                final WindowProcessController proc = procs.valueAt(j);
                if (proc.hasThread() && proc.getThread().asBinder() == threadBinder) {
                    return proc;
                }
            }
        }

        return null;
    }

    /**
     * Returns the {@link WindowProcessController} for the app process for the given uid and pid.
     *
     * If no such {@link WindowProcessController} is found, it does not belong to an app, or the
     * pid does not match the uid {@code null} is returned.
     */
    WindowProcessController getProcessController(int pid, int uid) {
        final WindowProcessController proc = mProcessMap.getProcess(pid);
        if (proc == null) return null;
        if (UserHandle.isApp(uid) && proc.mUid == uid) {
            return proc;
        }
        return null;
    }

    /**
     * Returns the package name if (and only if) the package name can be uniquely determined.
     * Otherwise returns {@code null}.
     *
     * The provided pid must match the provided uid, otherwise this also returns null.
     */
    @Nullable String getPackageNameIfUnique(int uid, int pid) {
        final WindowProcessController proc = mProcessMap.getProcess(pid);
        if (proc == null || proc.mUid != uid) {
            Slog.w(TAG, "callingPackage for (uid=" + uid + ", pid=" + pid + ") has no WPC");
            return null;
        }
        List<String> realCallingPackages = proc.getPackageList();
        if (realCallingPackages.size() != 1) {
            Slog.w(TAG, "callingPackage for (uid=" + uid + ", pid=" + pid + ") is ambiguous: "
                    + realCallingPackages);
            return null;
        }
        return realCallingPackages.get(0);
    }

    /** A uid is considered to be foreground if it has a visible non-toast window. */
    @HotPath(caller = HotPath.START_SERVICE)
    boolean hasActiveVisibleWindow(int uid) {
        if (mVisibleActivityProcessTracker.hasVisibleActivity(uid)) {
            return true;
        }
        return mActiveUids.hasNonAppVisibleWindow(uid);
    }

    boolean isDeviceOwner(int uid) {
        return uid >= 0 && mDeviceOwnerUid == uid;
    }

    void setDeviceOwnerUid(int uid) {
        mDeviceOwnerUid = uid;
    }

    boolean isAffiliatedProfileOwner(int uid) {
        return uid >= 0 && mProfileOwnerUids.contains(uid)
            && DeviceStateCache.getInstance().hasAffiliationWithDevice(UserHandle.getUserId(uid));
    }

    void setProfileOwnerUids(Set<Integer> uids) {
        mProfileOwnerUids = uids;
    }

    /**
     * Saves the current activity manager state and includes the saved state in the next dump of
     * activity manager.
     */
    void saveANRState(String reason) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new FastPrintWriter(sw, false, 1024);
        pw.println("  ANR time: " + DateFormat.getDateTimeInstance().format(new Date()));
        if (reason != null) {
            pw.println("  Reason: " + reason);
        }
        pw.println();
        getActivityStartController().dump(pw, "  ", null);
        pw.println();
        pw.println("-------------------------------------------------------------------"
                + "------------");
        dumpActivitiesLocked(null /* fd */, pw, null /* args */, 0 /* opti */,
                true /* dumpAll */, false /* dumpClient */, null /* dumpPackage */,
                INVALID_DISPLAY, "" /* header */);
        pw.println();
        pw.close();

        mLastANRState = sw.toString();
    }

    void logAppTooSlow(WindowProcessController app, long startTime, String msg) {
        if (true || Build.IS_USER) {
            return;
        }

        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        StrictMode.allowThreadDiskWrites();
        try {
            File tracesDir = new File("/data/anr");
            File tracesFile = null;
            try {
                tracesFile = File.createTempFile("app_slow", null, tracesDir);

                StringBuilder sb = new StringBuilder();
                String timeString =
                        TimeMigrationUtils.formatMillisWithFixedFormat(System.currentTimeMillis());
                sb.append(timeString);
                sb.append(": ");
                TimeUtils.formatDuration(SystemClock.uptimeMillis() - startTime, sb);
                sb.append(" since ");
                sb.append(msg);
                FileOutputStream fos = new FileOutputStream(tracesFile);
                fos.write(sb.toString().getBytes());
                if (app == null) {
                    fos.write("\n*** No application process!".getBytes());
                }
                fos.close();
                FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
            } catch (IOException e) {
                Slog.w(TAG, "Unable to prepare slow app traces file: " + tracesFile, e);
                return;
            }

            if (app != null && app.getPid() > 0) {
                ArrayList<Integer> firstPids = new ArrayList<Integer>();
                firstPids.add(app.getPid());
                dumpStackTraces(tracesFile.getAbsolutePath(), firstPids, null, null, null, null);
            }

            File lastTracesFile = null;
            File curTracesFile = null;
            for (int i = 9; i >= 0; i--) {
                String name = String.format(Locale.US, "slow%02d.txt", i);
                curTracesFile = new File(tracesDir, name);
                if (curTracesFile.exists()) {
                    if (lastTracesFile != null) {
                        curTracesFile.renameTo(lastTracesFile);
                    } else {
                        curTracesFile.delete();
                    }
                }
                lastTracesFile = curTracesFile;
            }
            tracesFile.renameTo(curTracesFile);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    boolean isAssociatedCompanionApp(int userId, int uid) {
        final Set<Integer> allUids = mCompanionAppUidsMap.get(userId);
        if (allUids == null) {
            return false;
        }
        return allUids.contains(uid);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw logAndRethrowRuntimeExceptionOnTransact(TAG, e);
        }
    }

    /** Provides the full stack traces of non-security exception that occurs in onTransact. */
    static RuntimeException logAndRethrowRuntimeExceptionOnTransact(String name,
            RuntimeException e) {
        if (!(e instanceof SecurityException)) {
            Slog.w(TAG, name + " onTransact aborts"
                    + " UID:" + Binder.getCallingUid()
                    + " PID:" + Binder.getCallingPid(), e);
        }
        throw e;
    }

    /**
     * Sets the corresponding {@link DisplayArea} information for the process global
     * configuration. To be called when we need to show IME on a different {@link DisplayArea}
     * or display.
     *
     * @param pid The process id associated with the IME window.
     * @param imeContainer The DisplayArea that contains the IME window.
     */
    void onImeWindowSetOnDisplayArea(final int pid, @NonNull final DisplayArea imeContainer) {
        if (pid == MY_PID || pid < 0) {
            ProtoLog.w(WM_DEBUG_CONFIGURATION,
                    "Trying to update display configuration for system/invalid process.");
            return;
        }
        final WindowProcessController process = mProcessMap.getProcess(pid);
        if (process == null) {
            ProtoLog.w(WM_DEBUG_CONFIGURATION, "Trying to update display "
                    + "configuration for invalid process, pid=%d", pid);
            return;
        }
        process.registerDisplayAreaConfigurationListener(imeContainer);
    }

    @Override
    public void setRunningRemoteTransitionDelegate(IApplicationThread delegate) {
        final TransitionController controller = getTransitionController();
        // A quick path without entering WM lock.
        if (delegate != null && controller.mRemotePlayer.reportRunning(delegate)) {
            // The delegate was known as running remote transition.
            return;
        }
        mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "setRunningRemoteTransition");
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            // Also only allow a process which is already runningRemoteAnimation to mark another
            // process.
            final WindowProcessController callingProc = getProcessController(callingPid,
                    callingUid);
            if (callingProc == null || !callingProc.isRunningRemoteTransition()) {
                final String msg = "Can't call setRunningRemoteTransition from a process (pid="
                        + callingPid + " uid=" + callingUid + ") which isn't itself running a "
                        + "remote transition.";
                Slog.e(TAG, msg);
                throw new SecurityException(msg);
            }
            final WindowProcessController wpc = getProcessController(delegate);
            if (wpc == null) {
                Slog.w(TAG, "setRunningRemoteTransition: no process for " + delegate);
                return;
            }
            controller.mRemotePlayer.update(wpc, true /* running */, false /* predict */);
        }
    }

    @Override
    public void registerScreenCaptureObserver(IBinder activityToken,
            IScreenCaptureObserver observer) {
        mAmInternal.enforceCallingPermission(DETECT_SCREEN_CAPTURE,
                "registerScreenCaptureObserver");
        synchronized (mGlobalLock) {
            ActivityRecord activityRecord = ActivityRecord.forTokenLocked(activityToken);
            if (activityRecord != null) {
                activityRecord.registerCaptureObserver(observer);
            }
        }
    }

    @Override
    public void unregisterScreenCaptureObserver(IBinder activityToken,
            IScreenCaptureObserver observer) {
        mAmInternal.enforceCallingPermission(DETECT_SCREEN_CAPTURE,
                "unregisterScreenCaptureObserver");
        synchronized (mGlobalLock) {
            ActivityRecord activityRecord = ActivityRecord.forTokenLocked(activityToken);
            if (activityRecord != null) {
                activityRecord.unregisterCaptureObserver(observer);
            }
        }
    }

    /**
     * Returns {@code true} if the process represented by the pid passed as argument is
     * instrumented and the instrumentation source was granted with the permission also
     * passed as argument.
     */
    boolean instrumentationSourceHasPermission(int pid, String permission) {
        final WindowProcessController process;
        synchronized (mGlobalLock) {
            process = mProcessMap.getProcess(pid);
        }
        if (process == null || !process.isInstrumenting()) {
            return false;
        }
        final int sourceUid = process.getInstrumentationSourceUid();
        return checkPermission(permission, -1, sourceUid) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Wrap the {@link ActivityOptions} in {@link SafeActivityOptions} and attach caller options
     * that allow using the callers permissions to start background activities.
     */
    private SafeActivityOptions createSafeActivityOptionsWithBalAllowed(
            @Nullable ActivityOptions options) {
        if (options == null) {
            options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        } else if (options.getPendingIntentBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return new SafeActivityOptions(options);
    }

    /**
     * Wrap the options {@link Bundle} in {@link SafeActivityOptions} and attach caller options
     * that allow using the callers permissions to start background activities.
     */
    private SafeActivityOptions createSafeActivityOptionsWithBalAllowed(@Nullable Bundle bOptions) {
        return createSafeActivityOptionsWithBalAllowed(ActivityOptions.fromBundle(bOptions));
    }

    final class H extends Handler {
        static final int REPORT_TIME_TRACKER_MSG = 1;
        static final int UPDATE_PROCESS_ANIMATING_STATE = 2;
        static final int END_POWER_MODE_UNKNOWN_VISIBILITY_MSG = 3;
        static final int RESUME_FG_APP_SWITCH_MSG = 4;

        static final int FIRST_ACTIVITY_TASK_MSG = 100;
        static final int FIRST_SUPERVISOR_TASK_MSG = 200;

        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REPORT_TIME_TRACKER_MSG: {
                    AppTimeTracker tracker = (AppTimeTracker) msg.obj;
                    tracker.deliverResult(mContext);
                }
                break;
                case UPDATE_PROCESS_ANIMATING_STATE: {
                    final WindowProcessController proc = (WindowProcessController) msg.obj;
                    synchronized (mGlobalLock) {
                        proc.updateRunningRemoteOrRecentsAnimation();
                    }
                }
                break;
                case END_POWER_MODE_UNKNOWN_VISIBILITY_MSG: {
                    synchronized (mGlobalLock) {
                        mRetainPowerModeAndTopProcessState = false;
                        endLaunchPowerMode(POWER_MODE_REASON_UNKNOWN_VISIBILITY);
                        if (mTopApp != null
                                && mTopProcessState == ActivityManager.PROCESS_STATE_TOP_SLEEPING) {
                            // Restore the scheduling group for sleeping.
                            mTopApp.updateProcessInfo(false /* updateServiceConnection */,
                                    false /* activityChange */, true /* updateOomAdj */,
                                    false /* addPendingTopUid */);
                        }
                    }
                }
                break;
                case RESUME_FG_APP_SWITCH_MSG: {
                    synchronized (mGlobalLock) {
                        if (mAppSwitchesState == APP_SWITCH_DISALLOW) {
                            mAppSwitchesState = APP_SWITCH_FG_ONLY;
                        }
                    }
                }
                break;
            }
        }
    }

    final class UiHandler extends Handler {
        static final int DISMISS_DIALOG_UI_MSG = 1;

        public UiHandler() {
            super(UiThread.get().getLooper(), null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DISMISS_DIALOG_UI_MSG: {
                    final Dialog d = (Dialog) msg.obj;
                    d.dismiss();
                    break;
                }
            }
        }
    }

    final class LocalService extends ActivityTaskManagerInternal {
        @Override
        public SleepTokenAcquirer createSleepTokenAcquirer(@NonNull String tag) {
            Objects.requireNonNull(tag);
            return new SleepTokenAcquirerImpl(tag);
        }

        @Override
        public ComponentName getHomeActivityForUser(int userId) {
            synchronized (mGlobalLock) {
                final ActivityRecord homeActivity =
                        mRootWindowContainer.getDefaultDisplayHomeActivityForUser(userId);
                return homeActivity == null ? null : homeActivity.mActivityComponent;
            }
        }

        @Override
        public void onLocalVoiceInteractionStarted(IBinder activity,
                IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
            synchronized (mGlobalLock) {
                onLocalVoiceInteractionStartedLocked(activity, voiceSession, voiceInteractor);
            }
        }

        @Override
        public List<ActivityAssistInfo> getTopVisibleActivities() {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.getTopVisibleActivities();
            }
        }

        @Override
        public boolean hasResumedActivity(int uid) {
            return mVisibleActivityProcessTracker.hasResumedActivity(uid);
        }

        @Override
        public void setBackgroundActivityStartCallback(
                @Nullable BackgroundActivityStartCallback backgroundActivityStartCallback) {
            mBackgroundActivityStartCallback = backgroundActivityStartCallback;
        }

        @Override
        public void setAccessibilityServiceUids(IntArray uids) {
            synchronized (mGlobalLock) {
                mAccessibilityServiceUids = uids.toArray();
            }
        }

        @Override
        public int startActivitiesAsPackage(String packageName, @Nullable String featureId,
                int userId, Intent[] intents, Bundle bOptions) {
            Objects.requireNonNull(intents, "intents");
            final String[] resolvedTypes = new String[intents.length];

            // UID of the package on user userId.
            // "= 0" is needed because otherwise catch(RemoteException) would make it look like
            // packageUid may not be initialized.
            int packageUid = 0;
            final long ident = Binder.clearCallingIdentity();

            try {
                for (int i = 0; i < intents.length; i++) {
                    resolvedTypes[i] =
                            intents[i].resolveTypeIfNeeded(mContext.getContentResolver());
                }

                packageUid = AppGlobals.getPackageManager().getPackageUid(
                        packageName, PackageManager.MATCH_DEBUG_TRIAGED_MISSING, userId);
            } catch (RemoteException e) {
                // Shouldn't happen.
            } finally {
                Binder.restoreCallingIdentity(ident);
            }

            return getActivityStartController().startActivitiesInPackage(
                    packageUid, packageName, featureId,
                    intents, resolvedTypes, null /* resultTo */,
                    SafeActivityOptions.fromBundle(bOptions), userId,
                    false /* validateIncomingUser */, null /* originatingPendingIntent */,
                    BackgroundStartPrivileges.NONE);
        }

        @Override
        public int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid,
                String callingPackage, @Nullable String callingFeatureId, Intent[] intents,
                String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId,
                boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent,
                BackgroundStartPrivileges backgroundStartPrivileges) {
            assertPackageMatchesCallingUid(callingPackage);
            return getActivityStartController().startActivitiesInPackage(uid, realCallingPid,
                    realCallingUid, callingPackage, callingFeatureId, intents, resolvedTypes,
                    resultTo, options, userId, validateIncomingUser, originatingPendingIntent,
                    backgroundStartPrivileges);
        }

        @Override
        public int startActivityInPackage(int uid, int realCallingPid, int realCallingUid,
                String callingPackage, @Nullable String callingFeatureId, Intent intent,
                String resolvedType, IBinder resultTo, String resultWho, int requestCode,
                int startFlags, SafeActivityOptions options, int userId, Task inTask, String reason,
                boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent,
                BackgroundStartPrivileges backgroundStartPrivileges) {
            assertPackageMatchesCallingUid(callingPackage);
            return getActivityStartController().startActivityInPackage(uid, realCallingPid,
                    realCallingUid, callingPackage, callingFeatureId, intent, resolvedType,
                    resultTo, resultWho, requestCode, startFlags, options, userId, inTask,
                    reason, validateIncomingUser, originatingPendingIntent,
                    backgroundStartPrivileges);
        }

        @Override
        public int startActivityAsUser(IApplicationThread caller, String callerPackage,
                @Nullable String callerFeatureId, Intent intent, @Nullable IBinder resultTo,
                int startFlags, Bundle options, int userId) {
            return ActivityTaskManagerService.this.startActivityAsUser(
                    caller, callerPackage, callerFeatureId, intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    resultTo, null, 0, startFlags, null, options, userId,
                    false /*validateIncomingUser*/);
        }

        /**
         * Called after virtual display Id is updated by
         * {@link com.android.server.vr.Vr2dDisplay} with a specific
         * {@param vrVr2dDisplayId}.
         */
        @Override
        public void setVr2dDisplayId(int vr2dDisplayId) {
            ProtoLog.d(WM_DEBUG_TASKS, "setVr2dDisplayId called for: %d", vr2dDisplayId);
            synchronized (mGlobalLock) {
                mVr2dDisplayId = vr2dDisplayId;
            }
        }

        @Override
        public int getDisplayId(IBinder token) {
            synchronized (mGlobalLock) {
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException(
                            "getDisplayId: No activity record matching token=" + token);
                }
                return r.getDisplayId();
            }
        }

        @Override
        public void registerScreenObserver(ScreenObserver observer) {
            mScreenObservers.add(observer);
        }

        @Override
        public void unregisterScreenObserver(ScreenObserver observer) {
            mScreenObservers.remove(observer);
        }

        @Override
        public boolean isCallerRecents(int callingUid) {
            return ActivityTaskManagerService.this.isCallerRecents(callingUid);
        }

        @Override
        public boolean isRecentsComponentHomeActivity(int userId) {
            return getRecentTasks().isRecentsComponentHomeActivity(userId);
        }

        @Override
        public boolean checkCanCloseSystemDialogs(int pid, int uid, @Nullable String packageName) {
            return ActivityTaskManagerService.this.checkCanCloseSystemDialogs(pid, uid,
                    packageName);
        }

        @Override
        public boolean canCloseSystemDialogs(int pid, int uid) {
            return ActivityTaskManagerService.this.canCloseSystemDialogs(pid, uid);
        }

        @Override
        public void notifyActiveVoiceInteractionServiceChanged(ComponentName component) {
            synchronized (mGlobalLock) {
                mActiveVoiceInteractionServiceComponent = component;
            }
        }

        @Override
        public void notifyActiveDreamChanged(@Nullable ComponentName dreamComponent) {
            synchronized (mGlobalLock) {
                mActiveDreamComponent = dreamComponent;
            }
        }

        @Override
        public void setAllowAppSwitches(@NonNull String type, int uid, int userId) {
            if (!mAmInternal.isUserRunning(userId, ActivityManager.FLAG_OR_STOPPED)) {
                return;
            }
            synchronized (mGlobalLock) {
                ArrayMap<String, Integer> types = mAllowAppSwitchUids.get(userId);
                if (types == null) {
                    if (uid < 0) {
                        return;
                    }
                    types = new ArrayMap<>();
                    mAllowAppSwitchUids.put(userId, types);
                }
                if (uid < 0) {
                    types.remove(type);
                } else {
                    types.put(type, uid);
                }
            }
        }

        @Override
        public void onUserStopped(int userId) {
            synchronized (mGlobalLock) {
                getRecentTasks().unloadUserDataFromMemoryLocked(userId);
                mAllowAppSwitchUids.remove(userId);
            }
        }

        @Override
        public boolean isGetTasksAllowed(String caller, int callingPid, int callingUid) {
            return ActivityTaskManagerService.this.isGetTasksAllowed(
                    caller, callingPid, callingUid);
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void onProcessAdded(WindowProcessController proc) {
            synchronized (mGlobalLockWithoutBoost) {
                mProcessNames.put(proc.mName, proc.mUid, proc);
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void onProcessRemoved(String name, int uid) {
            synchronized (mGlobalLockWithoutBoost) {
                mProcessNames.remove(name, uid);
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void onCleanUpApplicationRecord(WindowProcessController proc) {
            synchronized (mGlobalLockWithoutBoost) {
                if (proc == mHomeProcess) {
                    mHomeProcess = null;
                }
                if (proc == mPreviousProcess) {
                    mPreviousProcess = null;
                }
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public int getTopProcessState() {
            if (mRetainPowerModeAndTopProcessState) {
                // There is a launching app while device may be sleeping, force the top state so
                // the launching process can have top-app scheduling group.
                return ActivityManager.PROCESS_STATE_TOP;
            }
            return mTopProcessState;
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public boolean useTopSchedGroupForTopProcess() {
            // If it is non-zero, there may be a more important UI/animation than the top app.
            return mDemoteTopAppReasons == 0;
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void clearHeavyWeightProcessIfEquals(WindowProcessController proc) {
            synchronized (mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.clearHeavyWeightProcessIfEquals(proc);
            }
        }

        @Override
        public void finishHeavyWeightApp() {
            synchronized (mGlobalLock) {
                if (mHeavyWeightProcess != null) {
                    mHeavyWeightProcess.finishActivities();
                }
                ActivityTaskManagerService.this.clearHeavyWeightProcessIfEquals(
                        mHeavyWeightProcess);
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public boolean isSleeping() {
            return mSleeping;
        }

        @Override
        public boolean isShuttingDown() {
            synchronized (mGlobalLock) {
                return mShuttingDown;
            }
        }

        @Override
        public boolean shuttingDown(boolean booted, int timeout) {
            synchronized (mGlobalLock) {
                mShuttingDown = true;
                mRootWindowContainer.prepareForShutdown();
                updateEventDispatchingLocked(booted);
                notifyTaskPersisterLocked(null, true);
                return mTaskSupervisor.shutdownLocked(timeout);
            }
        }

        @Override
        public void enableScreenAfterBoot(boolean booted) {
            writeBootProgressEnableScreen(SystemClock.uptimeMillis());
            mWindowManager.enableScreenAfterBoot();
            synchronized (mGlobalLock) {
                updateEventDispatchingLocked(booted);
            }
        }

        @Override
        public boolean showStrictModeViolationDialog() {
            synchronized (mGlobalLock) {
                return mShowDialogs && !mSleeping && !mShuttingDown;
            }
        }

        @Override
        public void showSystemReadyErrorDialogsIfNeeded() {
            synchronized (mGlobalLock) {
                try {
                    if (AppGlobals.getPackageManager().hasSystemUidErrors()) {
                        Slog.e(TAG, "UIDs on the system are inconsistent, you need to wipe your"
                                + " data partition or your device will be unstable.");
                        mUiHandler.post(() -> {
                            if (mShowDialogs) {
                                AlertDialog d = new BaseErrorDialog(mUiContext);
                                d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                                d.setCancelable(false);
                                d.setTitle(mUiContext.getText(R.string.android_system_label));
                                d.setMessage(mUiContext.getText(R.string.system_error_wipe_data));
                                d.setButton(DialogInterface.BUTTON_POSITIVE,
                                        mUiContext.getText(R.string.ok),
                                        mUiHandler.obtainMessage(DISMISS_DIALOG_UI_MSG, d));
                                d.show();
                            }
                        });
                    }
                } catch (RemoteException e) {
                }

                if (!Build.isBuildConsistent()) {
                    Slog.e(TAG, "Build fingerprint is not consistent, warning user");
                    mUiHandler.post(() -> {
                        if (mShowDialogs) {
                            AlertDialog d = new BaseErrorDialog(mUiContext);
                            d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                            d.setCancelable(false);
                            d.setTitle(mUiContext.getText(R.string.android_system_label));
                            d.setMessage(mUiContext.getText(R.string.system_error_manufacturer));
                            d.setButton(DialogInterface.BUTTON_POSITIVE,
                                    mUiContext.getText(R.string.ok),
                                    mUiHandler.obtainMessage(DISMISS_DIALOG_UI_MSG, d));
                            d.show();
                        }
                    });
                }
            }
        }

        @Override
        public void onProcessMapped(int pid, WindowProcessController proc) {
            synchronized (mGlobalLock) {
                mProcessMap.put(pid, proc);
            }
        }

        @Override
        public void onProcessUnMapped(int pid) {
            synchronized (mGlobalLock) {
                mProcessMap.remove(pid);
            }
        }

        @Override
        public void onPackageDataCleared(String name, int userId) {
            synchronized (mGlobalLock) {
                mCompatModePackages.handlePackageDataClearedLocked(name);
                mAppWarnings.onPackageDataCleared(name);
                mPackageConfigPersister.onPackageDataCleared(name, userId);
            }
        }

        @Override
        public void onPackageUninstalled(String name, int userId) {
            synchronized (mGlobalLock) {
                mAppWarnings.onPackageUninstalled(name);
                mCompatModePackages.handlePackageUninstalledLocked(name);
                mPackageConfigPersister.onPackageUninstall(name, userId);
            }
        }

        @Override
        public void onPackageAdded(String name, boolean replacing) {
            synchronized (mGlobalLock) {
                mCompatModePackages.handlePackageAddedLocked(name, replacing);
            }
        }

        @Override
        public void onPackageReplaced(ApplicationInfo aInfo) {
            synchronized (mGlobalLock) {
                mRootWindowContainer.updateActivityApplicationInfo(aInfo);
            }
        }

        @Override
        public CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai) {
            synchronized (mGlobalLock) {
                return compatibilityInfoForPackageLocked(ai);
            }
        }

        @Override
        public void sendActivityResult(int callingUid, IBinder activityToken, String resultWho,
                int requestCode, int resultCode, Intent data) {
            final ActivityRecord r;
            synchronized (mGlobalLock) {
                r = ActivityRecord.isInRootTaskLocked(activityToken);
                if (r == null || r.getRootTask() == null) {
                    return;
                }
            }

            // Carefully collect grants without holding lock
            final NeededUriGrants dataGrants = collectGrants(data, r);

            synchronized (mGlobalLock) {
                r.sendResult(callingUid, resultWho, requestCode, resultCode, data, dataGrants);
            }
        }

        @Override
        public void clearPendingResultForActivity(IBinder activityToken,
                WeakReference<PendingIntentRecord> pir) {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(activityToken);
                if (r != null && r.pendingResults != null) {
                    r.pendingResults.remove(pir);
                }
            }
        }

        @Override
        public ComponentName getActivityName(IBinder activityToken) {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(activityToken);
                return r != null ? r.intent.getComponent() : null;
            }
        }

        @Override
        public ActivityTokens getAttachedNonFinishingActivityForTask(int taskId,
                IBinder token) {
            synchronized (mGlobalLock) {
                final Task task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_ONLY);
                if (task == null) {
                    Slog.w(TAG, "getApplicationThreadForTopActivity failed:"
                            + " Requested task not found");
                    return null;
                }
                final List<ActivityRecord> list = new ArrayList<>();
                task.forAllActivities(r -> {
                    if (!r.finishing) {
                        list.add(r);
                    }
                });
                if (list.size() <= 0) {
                    return null;
                }
                // pass null, get top Activity
                if (token == null && list.get(0).attachedToProcess()) {
                    ActivityRecord topRecord = list.get(0);
                    return new ActivityTokens(topRecord.token, topRecord.assistToken,
                            topRecord.app.getThread(), topRecord.shareableActivityToken,
                            topRecord.getUid());
                }
                // find the expected Activity
                for (int i = 0; i < list.size(); i++) {
                    ActivityRecord record = list.get(i);
                    if (record.shareableActivityToken == token && record.attachedToProcess()) {
                        return new ActivityTokens(record.token, record.assistToken,
                                record.app.getThread(), record.shareableActivityToken,
                                record.getUid());
                    }
                }
                return null;
            }
        }

        @Override
        public IIntentSender getIntentSender(int type, String packageName,
                @Nullable String featureId, int callingUid, int userId, IBinder token,
                String resultWho, int requestCode, Intent[] intents, String[] resolvedTypes,
                int flags, Bundle bOptions) {
            synchronized (mGlobalLock) {
                return getIntentSenderLocked(type, packageName, featureId, callingUid, userId,
                        token, resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
            }
        }

        @Override
        public ActivityServiceConnectionsHolder getServiceConnectionsHolder(IBinder token) {
            final ActivityRecord r = ActivityRecord.forToken(token);
            if (r == null || !r.inHistory) {
                return null;
            }
            return r.getOrCreateServiceConnectionsHolder();
        }

        @Override
        public Intent getHomeIntent() {
            synchronized (mGlobalLock) {
                return ActivityTaskManagerService.this.getHomeIntent();
            }
        }

        @Override
        public boolean startHomeActivity(int userId, String reason) {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.startHomeOnDisplay(userId, reason, DEFAULT_DISPLAY);
            }
        }

        @Override
        public boolean startHomeOnDisplay(int userId, String reason, int displayId,
                boolean allowInstrumenting, boolean fromHomeKey) {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.startHomeOnDisplay(userId, reason, displayId,
                        allowInstrumenting, fromHomeKey);
            }
        }

        @Override
        public boolean startHomeOnAllDisplays(int userId, String reason) {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.startHomeOnAllDisplays(userId, reason);
            }
        }

        @Override
        public void updateTopComponentForFactoryTest() {
            synchronized (mGlobalLock) {
                if (mFactoryTest != FACTORY_TEST_LOW_LEVEL) {
                    return;
                }
                final ResolveInfo ri = mContext.getPackageManager()
                        .resolveActivity(new Intent(Intent.ACTION_FACTORY_TEST), STOCK_PM_FLAGS);
                final CharSequence errorMsg;
                if (ri != null) {
                    final ActivityInfo ai = ri.activityInfo;
                    final ApplicationInfo app = ai.applicationInfo;
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        mTopAction = Intent.ACTION_FACTORY_TEST;
                        mTopData = null;
                        mTopComponent = new ComponentName(app.packageName, ai.name);
                        errorMsg = null;
                    } else {
                        errorMsg = mContext.getResources().getText(
                                com.android.internal.R.string.factorytest_not_system);
                    }
                } else {
                    errorMsg = mContext.getResources().getText(
                            com.android.internal.R.string.factorytest_no_action);
                }
                if (errorMsg == null) {
                    return;
                }

                mTopAction = null;
                mTopData = null;
                mTopComponent = null;
                mUiHandler.post(() -> {
                    Dialog d = new FactoryErrorDialog(mUiContext, errorMsg);
                    d.show();
                    mAmInternal.ensureBootCompleted();
                });
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void handleAppDied(WindowProcessController wpc, boolean restarting,
                Runnable finishInstrumentationCallback) {
            synchronized (mGlobalLockWithoutBoost) {
                mTaskSupervisor.beginDeferResume();
                final boolean hasVisibleActivities;
                try {
                    // Remove this application's activities from active lists.
                    hasVisibleActivities = wpc.handleAppDied();
                } finally {
                    mTaskSupervisor.endDeferResume();
                }

                if (!restarting && hasVisibleActivities) {
                    deferWindowLayout();
                    try {
                        if (!mRootWindowContainer.resumeFocusedTasksTopActivities()) {
                            // If there was nothing to resume, and we are not already restarting
                            // this process, but there is a visible activity that is hosted by the
                            // process...then make sure all visible activities are running, taking
                            // care of restarting this process.
                            mRootWindowContainer.ensureActivitiesVisible(null, 0,
                                    !PRESERVE_WINDOWS);
                        }
                    } finally {
                        continueWindowLayout();
                    }
                }
            }
            if (wpc.isInstrumenting()) {
                finishInstrumentationCallback.run();
            }
        }

        @Override
        public void closeSystemDialogs(String reason) {
            enforceNotIsolatedCaller("closeSystemDialogs");
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            if (!checkCanCloseSystemDialogs(pid, uid, null)) {
                return;
            }

            final long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mGlobalLock) {
                    // Only allow this from foreground processes, so that background
                    // applications can't abuse it to prevent system UI from being shown.
                    if (uid >= FIRST_APPLICATION_UID) {
                        final WindowProcessController proc = mProcessMap.getProcess(pid);
                        if (!proc.isPerceptible()) {
                            Slog.w(TAG, "Ignoring closeSystemDialogs " + reason
                                    + " from background process " + proc);
                            return;
                        }
                    }
                    mWindowManager.closeSystemDialogs(reason);

                    mRootWindowContainer.closeSystemDialogActivities(reason);
                }
                // Call into AM outside the synchronized block.
                mAmInternal.broadcastCloseSystemDialogs(reason);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        @Override
        public void cleanupDisabledPackageComponents(
                String packageName, Set<String> disabledClasses, int userId, boolean booted) {
            synchronized (mGlobalLock) {
                // In case if setWindowManager hasn't been called yet when booting.
                if (mRootWindowContainer == null) return;
                // Clean-up disabled activities.
                if (mRootWindowContainer.finishDisabledPackageActivities(
                        packageName, disabledClasses, true /* doit */, false /* evenPersistent */,
                        userId, false /* onlyRemoveNoProcess */) && booted) {
                    mRootWindowContainer.resumeFocusedTasksTopActivities();
                    mTaskSupervisor.scheduleIdle();
                }

                // Clean-up disabled tasks
                getRecentTasks().cleanupDisabledPackageTasksLocked(
                        packageName, disabledClasses, userId);
            }
        }

        @Override
        public boolean onForceStopPackage(String packageName, boolean doit, boolean evenPersistent,
                int userId) {
            synchronized (mGlobalLock) {
                // In case if setWindowManager hasn't been called yet when booting.
                if (mRootWindowContainer == null) return false;
                return mRootWindowContainer.finishDisabledPackageActivities(packageName,
                        null /* filterByClasses */, doit, evenPersistent, userId,
                        // Only remove the activities without process because the activities with
                        // attached process will be removed when handling process died with
                        // WindowProcessController#isRemoved == true.
                        true /* onlyRemoveNoProcess */);
            }
        }

        @Override
        public void resumeTopActivities(boolean scheduleIdle) {
            synchronized (mGlobalLock) {
                mRootWindowContainer.resumeFocusedTasksTopActivities();
                if (scheduleIdle) {
                    mTaskSupervisor.scheduleIdle();
                }
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void preBindApplication(WindowProcessController wpc) {
            synchronized (mGlobalLockWithoutBoost) {
                mTaskSupervisor.getActivityMetricsLogger().notifyBindApplication(wpc.mInfo);
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public boolean attachApplication(WindowProcessController wpc) throws RemoteException {
            synchronized (mGlobalLockWithoutBoost) {
                if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "attachApplication:" + wpc.mName);
                }
                try {
                    return mRootWindowContainer.attachApplication(wpc);
                } finally {
                    Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                }
            }
        }

        @Override
        public void notifyLockedProfile(@UserIdInt int userId) {
            try {
                if (!AppGlobals.getPackageManager().isUidPrivileged(Binder.getCallingUid())) {
                    throw new SecurityException("Only privileged app can call notifyLockedProfile");
                }
            } catch (RemoteException ex) {
                throw new SecurityException("Fail to check is caller a privileged app", ex);
            }

            synchronized (mGlobalLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mAmInternal.shouldConfirmCredentials(userId)) {
                        maybeHideLockedProfileActivityLocked();
                        mRootWindowContainer.lockAllProfileTasks(userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void startConfirmDeviceCredentialIntent(Intent intent, Bundle options) {
            enforceTaskPermission("startConfirmDeviceCredentialIntent");

            synchronized (mGlobalLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    final ActivityOptions activityOptions = options != null
                            ? new ActivityOptions(options) : ActivityOptions.makeBasic();
                    mContext.startActivityAsUser(intent, activityOptions.toBundle(),
                            UserHandle.CURRENT);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void writeActivitiesToProto(ProtoOutputStream proto) {
            synchronized (mGlobalLock) {
                // The output proto of "activity --proto activities"
                mRootWindowContainer.dumpDebug(
                        proto, ROOT_WINDOW_CONTAINER, WindowTraceLogLevel.ALL);
            }
        }

        @Override
        public void dump(String cmd, FileDescriptor fd, PrintWriter pw, String[] args, int opti,
                boolean dumpAll, boolean dumpClient, String dumpPackage, int displayIdFilter) {
            synchronized (mGlobalLock) {
                if (DUMP_ACTIVITIES_CMD.equals(cmd) || DUMP_ACTIVITIES_SHORT_CMD.equals(cmd)) {
                    dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage,
                            displayIdFilter);
                } else if (DUMP_LASTANR_CMD.equals(cmd)) {
                    dumpLastANRLocked(pw);
                } else if (DUMP_LASTANR_TRACES_CMD.equals(cmd)) {
                    dumpLastANRTracesLocked(pw);
                } else if (DUMP_STARTER_CMD.equals(cmd)) {
                    dumpActivityStarterLocked(pw, dumpPackage);
                } else if (DUMP_CONTAINERS_CMD.equals(cmd)) {
                    dumpActivityContainersLocked(pw);
                } else if (DUMP_RECENTS_CMD.equals(cmd) || DUMP_RECENTS_SHORT_CMD.equals(cmd)) {
                    if (getRecentTasks() != null) {
                        getRecentTasks().dump(pw, dumpAll, dumpPackage);
                    }
                } else if (DUMP_TOP_RESUMED_ACTIVITY.equals(cmd)) {
                    dumpTopResumedActivityLocked(pw);
                } else if (DUMP_VISIBLE_ACTIVITIES.equals(cmd)) {
                    dumpVisibleActivitiesLocked(pw, displayIdFilter);
                }
            }
        }

        @Override
        public boolean dumpForProcesses(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
                String dumpPackage, int dumpAppId, boolean needSep, boolean testPssMode,
                int wakefulness) {
            synchronized (mGlobalLock) {
                if (mHomeProcess != null && (dumpPackage == null
                        || mHomeProcess.containsPackage(dumpPackage))) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    pw.println("  mHomeProcess: " + mHomeProcess);
                }
                if (mPreviousProcess != null && (dumpPackage == null
                        || mPreviousProcess.containsPackage(dumpPackage))) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    pw.println("  mPreviousProcess: " + mPreviousProcess);
                }
                if (dumpAll && (mPreviousProcess == null || dumpPackage == null
                        || mPreviousProcess.containsPackage(dumpPackage))) {
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("  mPreviousProcessVisibleTime: ");
                    TimeUtils.formatDuration(mPreviousProcessVisibleTime, sb);
                    pw.println(sb);
                }
                if (mHeavyWeightProcess != null && (dumpPackage == null
                        || mHeavyWeightProcess.containsPackage(dumpPackage))) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
                }
                if (dumpPackage == null) {
                    pw.println("  mGlobalConfiguration: " + getGlobalConfiguration());
                    mRootWindowContainer.dumpDisplayConfigs(pw, "  ");
                }
                if (dumpAll) {
                    final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
                    if (dumpPackage == null && topFocusedRootTask != null) {
                        pw.println("  mConfigWillChange: " + topFocusedRootTask.mConfigWillChange);
                    }
                    if (mCompatModePackages.getPackages().size() > 0) {
                        boolean printed = false;
                        for (Map.Entry<String, Integer> entry
                                : mCompatModePackages.getPackages().entrySet()) {
                            String pkg = entry.getKey();
                            int mode = entry.getValue();
                            if (dumpPackage != null && !dumpPackage.equals(pkg)) {
                                continue;
                            }
                            if (!printed) {
                                pw.println("  mScreenCompatPackages:");
                                printed = true;
                            }
                            pw.println("    " + pkg + ": " + mode);
                        }
                    }
                }

                if (dumpPackage == null) {
                    pw.println("  mWakefulness="
                            + PowerManagerInternal.wakefulnessToString(wakefulness));
                    pw.println("  mSleepTokens=" + mRootWindowContainer.mSleepTokens);
                    if (mRunningVoice != null) {
                        pw.println("  mRunningVoice=" + mRunningVoice);
                        pw.println("  mVoiceWakeLock" + mVoiceWakeLock);
                    }
                    pw.println("  mSleeping=" + mSleeping);
                    pw.println("  mShuttingDown=" + mShuttingDown + " mTestPssMode=" + testPssMode);
                    pw.println("  mVrController=" + mVrController);
                }
                if (mCurAppTimeTracker != null) {
                    mCurAppTimeTracker.dumpWithHeader(pw, "  ", true);
                }
                if (mAllowAppSwitchUids.size() > 0) {
                    boolean printed = false;
                    for (int i = 0; i < mAllowAppSwitchUids.size(); i++) {
                        ArrayMap<String, Integer> types = mAllowAppSwitchUids.valueAt(i);
                        for (int j = 0; j < types.size(); j++) {
                            if (dumpPackage == null ||
                                    UserHandle.getAppId(types.valueAt(j).intValue()) == dumpAppId) {
                                if (needSep) {
                                    pw.println();
                                    needSep = false;
                                }
                                if (!printed) {
                                    pw.println("  mAllowAppSwitchUids:");
                                    printed = true;
                                }
                                pw.print("    User ");
                                pw.print(mAllowAppSwitchUids.keyAt(i));
                                pw.print(": Type ");
                                pw.print(types.keyAt(j));
                                pw.print(" = ");
                                UserHandle.formatUid(pw, types.valueAt(j).intValue());
                                pw.println();
                            }
                        }
                    }
                }
                if (dumpPackage == null) {
                    if (mController != null) {
                        pw.println("  mController=" + mController
                                + " mControllerIsAMonkey=" + mControllerIsAMonkey);
                    }
                    pw.println("  mGoingToSleepWakeLock=" + mTaskSupervisor.mGoingToSleepWakeLock);
                    pw.println("  mLaunchingActivityWakeLock="
                            + mTaskSupervisor.mLaunchingActivityWakeLock);
                }

                return needSep;
            }
        }

        @Override
        public void writeProcessesToProto(ProtoOutputStream proto, String dumpPackage,
                int wakeFullness, boolean testPssMode) {
            synchronized (mGlobalLock) {
                if (dumpPackage == null) {
                    getGlobalConfiguration().dumpDebug(proto, GLOBAL_CONFIGURATION);
                    final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
                    if (topFocusedRootTask != null) {
                        proto.write(CONFIG_WILL_CHANGE, topFocusedRootTask.mConfigWillChange);
                    }
                    writeSleepStateToProto(proto, wakeFullness, testPssMode);
                    if (mRunningVoice != null) {
                        final long vrToken = proto.start(
                                ActivityManagerServiceDumpProcessesProto.RUNNING_VOICE);
                        proto.write(ActivityManagerServiceDumpProcessesProto.Voice.SESSION,
                                mRunningVoice.toString());
                        mVoiceWakeLock.dumpDebug(
                                proto, ActivityManagerServiceDumpProcessesProto.Voice.WAKELOCK);
                        proto.end(vrToken);
                    }
                    mVrController.dumpDebug(proto,
                            ActivityManagerServiceDumpProcessesProto.VR_CONTROLLER);
                    if (mController != null) {
                        final long token = proto.start(CONTROLLER);
                        proto.write(ActivityManagerServiceDumpProcessesProto.Controller.CONTROLLER,
                                mController.toString());
                        proto.write(IS_A_MONKEY, mControllerIsAMonkey);
                        proto.end(token);
                    }
                    mTaskSupervisor.mGoingToSleepWakeLock.dumpDebug(proto, GOING_TO_SLEEP);
                    mTaskSupervisor.mLaunchingActivityWakeLock.dumpDebug(proto,
                            LAUNCHING_ACTIVITY);
                }

                if (mHomeProcess != null && (dumpPackage == null
                        || mHomeProcess.containsPackage(dumpPackage))) {
                    mHomeProcess.dumpDebug(proto, HOME_PROC);
                }

                if (mPreviousProcess != null && (dumpPackage == null
                        || mPreviousProcess.containsPackage(dumpPackage))) {
                    mPreviousProcess.dumpDebug(proto, PREVIOUS_PROC);
                    proto.write(PREVIOUS_PROC_VISIBLE_TIME_MS, mPreviousProcessVisibleTime);
                }

                if (mHeavyWeightProcess != null && (dumpPackage == null
                        || mHeavyWeightProcess.containsPackage(dumpPackage))) {
                    mHeavyWeightProcess.dumpDebug(proto, HEAVY_WEIGHT_PROC);
                }

                for (Map.Entry<String, Integer> entry
                        : mCompatModePackages.getPackages().entrySet()) {
                    String pkg = entry.getKey();
                    int mode = entry.getValue();
                    if (dumpPackage == null || dumpPackage.equals(pkg)) {
                        long compatToken = proto.start(SCREEN_COMPAT_PACKAGES);
                        proto.write(PACKAGE, pkg);
                        proto.write(MODE, mode);
                        proto.end(compatToken);
                    }
                }

                if (mCurAppTimeTracker != null) {
                    mCurAppTimeTracker.dumpDebug(proto, CURRENT_TRACKER, true);
                }

            }
        }

        @Override
        public boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name,
                String[] args, int opti, boolean dumpAll, boolean dumpVisibleRootTasksOnly,
                boolean dumpFocusedRootTaskOnly, int displayIdFilter,
                @UserIdInt int userId) {
            return ActivityTaskManagerService.this.dumpActivity(fd, pw, name, args, opti, dumpAll,
                    dumpVisibleRootTasksOnly, dumpFocusedRootTaskOnly, displayIdFilter, userId);
        }

        @Override
        public void dumpForOom(PrintWriter pw) {
            synchronized (mGlobalLock) {
                pw.println("  mHomeProcess: " + mHomeProcess);
                pw.println("  mPreviousProcess: " + mPreviousProcess);
                if (mHeavyWeightProcess != null) {
                    pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
                }
            }
        }

        @Override
        public boolean canGcNow() {
            synchronized (mGlobalLock) {
                return isSleeping() || mRootWindowContainer.allResumedActivitiesIdle();
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public WindowProcessController getTopApp() {
            return mTopApp;
        }

        @Override
        public void scheduleDestroyAllActivities(String reason) {
            synchronized (mGlobalLock) {
                mRootWindowContainer.scheduleDestroyAllActivities(reason);
            }
        }

        @Override
        public void removeUser(int userId) {
            synchronized (mGlobalLock) {
                mRootWindowContainer.removeUser(userId);
                mPackageConfigPersister.removeUser(userId);
            }
        }

        @Override
        public boolean switchUser(int userId, UserState userState) {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.switchUser(userId, userState);
            }
        }

        @Override
        public void onHandleAppCrash(@NonNull WindowProcessController wpc) {
            synchronized (mGlobalLock) {
                wpc.handleAppCrash();
            }
        }

        @Override
        public int finishTopCrashedActivities(WindowProcessController crashedApp, String reason) {
            synchronized (mGlobalLock) {
                return mRootWindowContainer.finishTopCrashedActivities(crashedApp, reason);
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public void onUidActive(int uid, int procState) {
            mActiveUids.onUidActive(uid, procState);
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public void onUidInactive(int uid) {
            mActiveUids.onUidInactive(uid);
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public void onUidProcStateChanged(int uid, int procState) {
            mActiveUids.onUidProcStateChanged(uid, procState);
        }

        @Override
        public boolean handleAppCrashInActivityController(String processName, int pid,
                String shortMsg, String longMsg, long timeMillis, String stackTrace,
                Runnable killCrashingAppCallback) {
            Runnable targetRunnable = null;
            synchronized (mGlobalLock) {
                if (mController == null) {
                    return false;
                }

                try {
                    if (!mController.appCrashed(processName, pid, shortMsg, longMsg, timeMillis,
                            stackTrace)) {
                        targetRunnable = killCrashingAppCallback;
                    }
                } catch (RemoteException e) {
                    mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
            }
            if (targetRunnable != null) {
                targetRunnable.run();
                return true;
            }
            return false;
        }

        @Override
        public void removeRecentTasksByPackageName(String packageName, int userId) {
            synchronized (mGlobalLock) {
                mRecentTasks.removeTasksByPackageName(packageName, userId);
            }
        }

        @Override
        public void cleanupRecentTasksForUser(int userId) {
            synchronized (mGlobalLock) {
                mRecentTasks.cleanupLocked(userId);
            }
        }

        @Override
        public void loadRecentTasksForUser(int userId) {
            synchronized (mGlobalLock) {
                mRecentTasks.loadUserRecentsLocked(userId);
                // TODO renaming the methods(?)
                mPackageConfigPersister.loadUserPackages(userId);
            }
        }

        @Override
        public void onPackagesSuspendedChanged(String[] packages, boolean suspended, int userId) {
            synchronized (mGlobalLock) {
                mRecentTasks.onPackagesSuspendedChanged(packages, suspended, userId);
            }
        }

        @Override
        public void flushRecentTasks() {
            mRecentTasks.flush();
        }

        @Override
        public void clearLockedTasks(String reason) {
            synchronized (mGlobalLock) {
                getLockTaskController().clearLockedTasks(reason);
            }
        }

        @Override
        public void updateUserConfiguration() {
            synchronized (mGlobalLock) {
                final Configuration configuration = new Configuration(getGlobalConfiguration());
                final int currentUserId = mAmInternal.getCurrentUserId();
                Settings.System.adjustConfigurationForUser(mContext.getContentResolver(),
                        configuration, currentUserId, Settings.System.canWrite(mContext));
                updateConfigurationLocked(configuration, null /* starting */,
                        false /* initLocale */, false /* persistent */, currentUserId,
                        false /* deferResume */);
            }
        }

        @Override
        public boolean canShowErrorDialogs() {
            synchronized (mGlobalLock) {
                return mShowDialogs && !mSleeping && !mShuttingDown
                        && !mKeyguardController.isKeyguardOrAodShowing(DEFAULT_DISPLAY)
                        && !hasUserRestriction(UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
                        mAmInternal.getCurrentUserId())
                        && !(UserManager.isDeviceInDemoMode(mContext)
                        && mAmInternal.getCurrentUser().isDemo());
            }
        }

        @Override
        public void setProfileApp(String profileApp) {
            synchronized (mGlobalLock) {
                mProfileApp = profileApp;
            }
        }

        @Override
        public void setProfileProc(WindowProcessController wpc) {
            synchronized (mGlobalLock) {
                mProfileProc = wpc;
            }
        }

        @Override
        public void setProfilerInfo(ProfilerInfo profilerInfo) {
            synchronized (mGlobalLock) {
                mProfilerInfo = profilerInfo;
            }
        }

        @Override
        public ActivityMetricsLaunchObserverRegistry getLaunchObserverRegistry() {
            synchronized (mGlobalLock) {
                return mTaskSupervisor.getActivityMetricsLogger().getLaunchObserverRegistry();
            }
        }

        @Nullable
        @Override
        public IBinder getUriPermissionOwnerForActivity(@NonNull IBinder activityToken) {
            ActivityTaskManagerService.enforceNotIsolatedCaller("getUriPermissionOwnerForActivity");
            synchronized (mGlobalLock) {
                ActivityRecord r = ActivityRecord.isInRootTaskLocked(activityToken);
                return (r == null) ? null : r.getUriPermissionsLocked().getExternalToken();
            }
        }

        @Override
        public TaskSnapshot getTaskSnapshotBlocking(
                int taskId, boolean isLowResolution) {
            return ActivityTaskManagerService.this.getTaskSnapshot(taskId, isLowResolution,
                    false /* takeSnapshotIfNeeded */);
        }

        @Override
        public boolean isUidForeground(int uid) {
            return ActivityTaskManagerService.this.hasActiveVisibleWindow(uid);
        }

        @Override
        public void setDeviceOwnerUid(int uid) {
            synchronized (mGlobalLock) {
                ActivityTaskManagerService.this.setDeviceOwnerUid(uid);
            }
        }

        @Override
        public void setProfileOwnerUids(Set<Integer> uids) {
            synchronized (mGlobalLock) {
                ActivityTaskManagerService.this.setProfileOwnerUids(uids);
            }
        }

        @Override
        public void setCompanionAppUids(int userId, Set<Integer> companionAppUids) {
            synchronized (mGlobalLock) {
                mCompanionAppUidsMap.put(userId, companionAppUids);
            }
        }


        @Override
        public boolean isBaseOfLockedTask(String packageName) {
            synchronized (mGlobalLock) {
                return getLockTaskController().isBaseOfLockedTask(packageName);
            }
        }

        @Override
        public PackageConfigurationUpdater createPackageConfigurationUpdater() {
            return new PackageConfigurationUpdaterImpl(Binder.getCallingPid(),
                    ActivityTaskManagerService.this);
        }

        @Override
        public PackageConfigurationUpdater createPackageConfigurationUpdater(
                String packageName , int userId) {
            return new PackageConfigurationUpdaterImpl(packageName, userId,
                    ActivityTaskManagerService.this);
        }

        @Override
        @Nullable
        public ActivityTaskManagerInternal.PackageConfig getApplicationConfig(String packageName,
                int userId) {
            return mPackageConfigPersister.findPackageConfiguration(packageName, userId);
        }

        @Override
        public boolean hasSystemAlertWindowPermission(int callingUid, int callingPid,
                String callingPackage) {
            return ActivityTaskManagerService.this.hasSystemAlertWindowPermission(callingUid,
                    callingPid, callingPackage);
        }

        @Override
        public void registerActivityStartInterceptor(
                @ActivityInterceptorCallback.OrderedId int id,
                ActivityInterceptorCallback callback) {
            synchronized (mGlobalLock) {
                if (mActivityInterceptorCallbacks.contains(id)) {
                    throw new IllegalArgumentException("Duplicate id provided: " + id);
                }
                if (callback == null) {
                    throw new IllegalArgumentException("The passed ActivityInterceptorCallback "
                            + "can not be null");
                }
                if (!ActivityInterceptorCallback.isValidOrderId(id)) {
                    throw new IllegalArgumentException(
                            "Provided id " + id + " is not in range of valid ids for system "
                                    + "services [" + SYSTEM_FIRST_ORDERED_ID + ","
                                    + SYSTEM_LAST_ORDERED_ID + "] nor in range of valid ids for "
                                    + "mainline module services [" + MAINLINE_FIRST_ORDERED_ID + ","
                                    + MAINLINE_LAST_ORDERED_ID + "]");
                }
                mActivityInterceptorCallbacks.put(id, callback);
            }
        }

        @Override
        public void unregisterActivityStartInterceptor(
                @ActivityInterceptorCallback.OrderedId int id) {
            synchronized (mGlobalLock) {
                if (!mActivityInterceptorCallbacks.contains(id)) {
                    throw new IllegalArgumentException(
                            "ActivityInterceptorCallback with id (" + id + ") is not registered");
                }
                mActivityInterceptorCallbacks.remove(id);
            }
        }

        @Override
        public ActivityManager.RecentTaskInfo getMostRecentTaskFromBackground() {
            List<ActivityManager.RunningTaskInfo> runningTaskInfoList = getTasks(1);
            ActivityManager.RunningTaskInfo runningTaskInfo;
            if (runningTaskInfoList.size() > 0) {
                runningTaskInfo = runningTaskInfoList.get(0);
            } else {
                Slog.i(TAG, "No running task found!");
                return null;
            }
            // Get 2 most recent tasks.
            List<ActivityManager.RecentTaskInfo> recentTaskInfoList =
                    getRecentTasks(
                                    2,
                                    ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                                    mContext.getUserId())
                            .getList();
            ActivityManager.RecentTaskInfo targetTask = null;
            for (ActivityManager.RecentTaskInfo info : recentTaskInfoList) {
                // Find a recent task that is not the current running task on screen.
                if (info.id != runningTaskInfo.id) {
                    targetTask = info;
                    break;
                }
            }
            return targetTask;
        }

        @Override
        public List<ActivityManager.AppTask> getAppTasks(String pkgName, int uid) {
            ArrayList<ActivityManager.AppTask> tasks = new ArrayList<>();
            List<IBinder> appTasks = ActivityTaskManagerService.this.getAppTasks(pkgName, uid);
            int numAppTasks = appTasks.size();
            for (int i = 0; i < numAppTasks; i++) {
                tasks.add(new ActivityManager.AppTask(IAppTask.Stub.asInterface(appTasks.get(i))));
            }
            return tasks;
        }

        @Override
        public int getTaskToShowPermissionDialogOn(String pkgName, int uid) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                return ActivityTaskManagerService.this.mRootWindowContainer
                        .getTaskToShowPermissionDialogOn(pkgName, uid);
            }
        }

        @Override
        public void restartTaskActivityProcessIfVisible(int taskId, String packageName) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                final Task task =
                        ActivityTaskManagerService.this.mRootWindowContainer
                                .anyTaskForId(taskId, MATCH_ATTACHED_TASK_ONLY);
                if (task == null) {
                    Slog.w(TAG, "Failed to restart Activity. No task found for id: " + taskId);
                    return;
                }

                final ActivityRecord activity = task.getActivity(activityRecord -> {
                    return packageName.equals(activityRecord.packageName)
                            && !activityRecord.finishing;
                });

                if (activity == null) {
                    Slog.w(TAG, "Failed to restart Activity. No Activity found for package name: "
                            + packageName + " in task: " + taskId);
                    return;
                }

                activity.restartProcessIfVisible();
            }
        }

        /** Sets the task stack listener that gets callbacks when a task stack changes. */
        @Override
        public void registerTaskStackListener(ITaskStackListener listener) {
            ActivityTaskManagerService.this.registerTaskStackListener(listener);
        }

        /** Unregister a task stack listener so that it stops receiving callbacks. */
        @Override
        public void unregisterTaskStackListener(ITaskStackListener listener) {
            ActivityTaskManagerService.this.unregisterTaskStackListener(listener);
        }
    }
}
