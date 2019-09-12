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
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;
import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.Manifest.permission.REMOVE_TASKS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.Manifest.permission.STOP_APP_SWITCHES;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.RESIZE_MODE_PRESERVE_WINDOW;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ApplicationInfo.FLAG_FACTORY_TEST;
import static android.content.pm.ConfigurationInfo.GL_ES_VERSION_UNDEFINED;
import static android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS;
import static android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT;
import static android.content.pm.PackageManager.FEATURE_PC;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
import static android.os.Build.VERSION_CODES.N;
import static android.os.FactoryTest.FACTORY_TEST_HIGH_LEVEL;
import static android.os.FactoryTest.FACTORY_TEST_LOW_LEVEL;
import static android.os.FactoryTest.FACTORY_TEST_OFF;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.provider.Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RTL;
import static android.provider.Settings.Global.HIDE_ERROR_DIALOGS;
import static android.provider.Settings.System.FONT_SCALE;
import static android.service.voice.VoiceInteractionSession.SHOW_SOURCE_APPLICATION;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_TASK_IN_PLACE;

import static com.android.server.am.ActivityManagerService.ANR_TRACE_DIR;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.ActivityManagerService.STOCK_PM_FLAGS;
import static com.android.server.am.ActivityManagerService.dumpStackTraces;
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
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_IMMERSIVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_IMMERSIVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_DATA;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;
import static com.android.server.wm.ActivityTaskManagerService.H.REPORT_TIME_TRACKER_MSG;
import static com.android.server.wm.ActivityTaskManagerService.UiHandler.DISMISS_DIALOG_UI_MSG;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_ONLY;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.wm.TaskRecord.REPARENT_KEEP_STACK_AT_FRONT;
import static com.android.server.wm.TaskRecord.REPARENT_LEAVE_STACK_IN_PLACE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IActivityTaskManager;
import android.app.IApplicationThread;
import android.app.IAssistDataReceiver;
import android.app.INotificationManager;
import android.app.IRequestFinishCallback;
import android.app.ITaskStackListener;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ProfilerInfo;
import android.app.RemoteAction;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.app.admin.DevicePolicyCache;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IIntentSender;
import android.content.Intent;
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
import android.metrics.LogMaker;
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
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
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
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.sysprop.DisplayProperties;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodSystemProperty;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.ProcessMap;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.KeyguardDismissCallback;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AttributeCache;
import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityManagerServiceDumpActivitiesProto;
import com.android.server.am.ActivityManagerServiceDumpProcessesProto;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.BaseErrorDialog;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentController;
import com.android.server.am.PendingIntentRecord;
import com.android.server.am.UserState;
import com.android.server.appop.AppOpsService;
import com.android.server.firewall.IntentFirewall;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.vr.VrManagerInternal;

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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * System service for managing activities and their containers (task, stacks, displays,... ).
 *
 * {@hide}
 */
public class ActivityTaskManagerService extends IActivityTaskManager.Stub {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityTaskManagerService" : TAG_ATM;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_IMMERSIVE = TAG + POSTFIX_IMMERSIVE;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;

    // How long we wait until we timeout on key dispatching.
    public static final int KEY_DISPATCHING_TIMEOUT_MS = 5 * 1000;
    // How long we wait until we timeout on key dispatching during instrumentation.
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MS = 60 * 1000;
    // How long we permit background activity starts after an activity in the process
    // started or finished.
    static final long ACTIVITY_BG_START_GRACE_PERIOD_MS = 10 * 1000;

    /** Used to indicate that an app transition should be animated. */
    static final boolean ANIMATE = true;

    /** Hardware-reported OpenGLES version. */
    final int GL_ES_VERSION;

    public static final String DUMP_ACTIVITIES_CMD = "activities" ;
    public static final String DUMP_ACTIVITIES_SHORT_CMD = "a" ;
    public static final String DUMP_LASTANR_CMD = "lastanr" ;
    public static final String DUMP_LASTANR_TRACES_CMD = "lastanr-traces" ;
    public static final String DUMP_STARTER_CMD = "starter" ;
    public static final String DUMP_CONTAINERS_CMD = "containers" ;
    public static final String DUMP_RECENTS_CMD = "recents" ;
    public static final String DUMP_RECENTS_SHORT_CMD = "r" ;

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
    final Context mUiContext;
    final ActivityThread mSystemThread;
    H mH;
    UiHandler mUiHandler;
    ActivityManagerInternal mAmInternal;
    UriGrantsManagerInternal mUgmInternal;
    private PackageManagerInternal mPmInternal;
    private PermissionPolicyInternal mPermissionPolicyInternal;
    @VisibleForTesting
    final ActivityTaskManagerInternal mInternal;
    PowerManagerInternal mPowerManagerInternal;
    private UsageStatsManagerInternal mUsageStatsInternal;

    PendingIntentController mPendingIntentController;
    IntentFirewall mIntentFirewall;

    /* Global service lock used by the package the owns this service. */
    final WindowManagerGlobalLock mGlobalLock = new WindowManagerGlobalLock();
    /**
     * It is the same instance as {@link mGlobalLock}, just declared as a type that the
     * locked-region-code-injection does't recognize it. It is used to skip wrapping priority
     * booster for places that are already in the scope of another booster (e.g. computing oom-adj).
     *
     * @see WindowManagerThreadPriorityBooster
     */
    final Object mGlobalLockWithoutBoost = mGlobalLock;
    ActivityStackSupervisor mStackSupervisor;
    RootActivityContainer mRootActivityContainer;
    WindowManagerService mWindowManager;
    private UserManagerService mUserManager;
    private AppOpsService mAppOpsService;
    /** All active uids in the system. */
    private final MirrorActiveUids mActiveUids = new MirrorActiveUids();
    private final SparseArray<String> mPendingTempWhitelist = new SparseArray<>();
    /** All processes currently running that might have a window organized by name. */
    final ProcessMap<WindowProcessController> mProcessNames = new ProcessMap<>();
    /** All processes we currently have running mapped by pid and uid */
    final WindowProcessControllerMap mProcessMap = new WindowProcessControllerMap();
    /** This is the process holding what we currently consider to be the "home" activity. */
    WindowProcessController mHomeProcess;
    /** The currently running heavy-weight process, if any. */
    WindowProcessController mHeavyWeightProcess = null;
    boolean mHasHeavyWeightFeature;
    /**
     * This is the process holding the activity the user last visited that is in a different process
     * from the one they are currently in.
     */
    WindowProcessController mPreviousProcess;
    /** The time at which the previous process was last visible. */
    long mPreviousProcessVisibleTime;

    /** List of intents that were used to start the most recent tasks. */
    private RecentTasks mRecentTasks;
    /** State of external calls telling us if the device is awake or asleep. */
    private boolean mKeyguardShown = false;

    // Wrapper around VoiceInteractionServiceManager
    private AssistUtils mAssistUtils;

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

    // Permission tokens are used to temporarily granted a trusted app the ability to call
    // #startActivityAsCaller.  A client is expected to dump its token after this time has elapsed,
    // showing any appropriate error messages to the user.
    private static final long START_AS_CALLER_TOKEN_TIMEOUT =
            10 * MINUTE_IN_MILLIS;

    // How long before the service actually expires a token.  This is slightly longer than
    // START_AS_CALLER_TOKEN_TIMEOUT, to provide a buffer so clients will rarely encounter the
    // expiration exception.
    private static final long START_AS_CALLER_TOKEN_TIMEOUT_IMPL =
            START_AS_CALLER_TOKEN_TIMEOUT + 2 * 1000;

    // How long the service will remember expired tokens, for the purpose of providing error
    // messaging when a client uses an expired token.
    private static final long START_AS_CALLER_TOKEN_EXPIRED_TIMEOUT =
            START_AS_CALLER_TOKEN_TIMEOUT_IMPL + 20 * MINUTE_IN_MILLIS;

    // How long to whitelist the Services for when requested.
    private static final int SERVICE_LAUNCH_IDLE_WHITELIST_DURATION_MS = 5 * 1000;

    // Activity tokens of system activities that are delegating their call to
    // #startActivityByCaller, keyed by the permissionToken granted to the delegate.
    final HashMap<IBinder, IBinder> mStartActivitySources = new HashMap<>();

    // Permission tokens that have expired, but we remember for error reporting.
    final ArrayList<IBinder> mExpiredStartAsCallerTokens = new ArrayList<>();

    private final ArrayList<PendingAssistExtras> mPendingAssistExtras = new ArrayList<>();

    // Keeps track of the active voice interaction service component, notified from
    // VoiceInteractionManagerService
    ComponentName mActiveVoiceInteractionServiceComponent;

    // A map userId and all its companion app uids
    private final Map<Integer, Set<Integer>> mCompanionAppUidsMap = new ArrayMap<>();

    VrController mVrController;
    KeyguardController mKeyguardController;
    private final ClientLifecycleManager mLifecycleManager;
    private TaskChangeNotificationController mTaskChangeNotificationController;
    /** The controller for all operations related to locktask. */
    private LockTaskController mLockTaskController;
    private ActivityStartController mActivityStartController;

    boolean mSuppressResizeConfigChanges;

    private final UpdateConfigurationResult mTmpUpdateConfigurationResult =
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
    // To cache the list of supported system locales
    private String[] mSupportedSystemLocales = null;

    /**
     * Temp object used when global and/or display override configuration is updated. It is also
     * sent to outer world instead of {@link #getGlobalConfiguration} because we don't trust
     * anyone...
     */
    private Configuration mTempConfig = new Configuration();

    /** Temporary to avoid allocations. */
    final StringBuilder mStringBuilder = new StringBuilder(256);

    // Amount of time after a call to stopAppSwitches() during which we will
    // prevent further untrusted switches from happening.
    private static final long APP_SWITCH_DELAY_TIME = 5 * 1000;

    /**
     * The time at which we will allow normal application switches again,
     * after a call to {@link #stopAppSwitches()}.
     */
    private long mAppSwitchesAllowedTime;
    /**
     * This is set to true after the first switch after mAppSwitchesAllowedTime
     * is set; any switches after that will clear the time.
     */
    private boolean mDidAppSwitch;

    /**
     * Last stop app switches time, apps finished before this time cannot start background activity
     * even if they are in grace period.
     */
    private long mLastStopAppSwitchesTime;

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
    private float mFullscreenThumbnailScale;

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
    boolean mSupportsMultiDisplay;
    boolean mForceResizableActivities;

    final List<ActivityTaskManagerInternal.ScreenObserver> mScreenObservers = new ArrayList<>();

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
    private boolean mSleeping = false;

    /**
     * The process state used for processes that are running the top activities.
     * This changes between TOP and TOP_SLEEPING to following mSleeping.
     */
    int mTopProcessState = ActivityManager.PROCESS_STATE_TOP;

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
    private @Nullable ActivityRecord mTracedResumedActivity;

    /** If non-null, we are tracking the time the user spends in the currently focused app. */
    AppTimeTracker mCurAppTimeTracker;

    private AppWarnings mAppWarnings;

    /**
     * Packages that the user has asked to have run in screen size
     * compatibility mode instead of filling the screen.
     */
    CompatModePackages mCompatModePackages;

    private FontScaleSettingObserver mFontScaleSettingObserver;

    private int mDeviceOwnerUid = Process.INVALID_UID;

    private final class FontScaleSettingObserver extends ContentObserver {
        private final Uri mFontScaleUri = Settings.System.getUriFor(FONT_SCALE);
        private final Uri mHideErrorDialogsUri = Settings.Global.getUriFor(HIDE_ERROR_DIALOGS);

        public FontScaleSettingObserver() {
            super(mH);
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mFontScaleUri, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mHideErrorDialogsUri, false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            if (mFontScaleUri.equals(uri)) {
                updateFontScaleIfNeeded(userId);
            } else if (mHideErrorDialogsUri.equals(uri)) {
                synchronized (mGlobalLock) {
                    updateShouldShowDialogsLocked(getGlobalConfiguration());
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
        int caller() default NONE;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public ActivityTaskManagerService(Context context) {
        mContext = context;
        mFactoryTest = FactoryTest.getMode();
        mSystemThread = ActivityThread.currentActivityThread();
        mUiContext = mSystemThread.getSystemUiContext();
        mLifecycleManager = new ClientLifecycleManager();
        mInternal = new LocalService();
        GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version", GL_ES_VERSION_UNDEFINED);
    }

    public void onSystemReady() {
        synchronized (mGlobalLock) {
            mHasHeavyWeightFeature = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_CANT_SAVE_STATE);
            mAssistUtils = new AssistUtils(mContext);
            mVrController.onSystemReady();
            mRecentTasks.onSystemReadyLocked();
            mStackSupervisor.onSystemReady();
        }
    }

    public void onInitPowerManagement() {
        synchronized (mGlobalLock) {
            mStackSupervisor.initPowerManagement();
            final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            mVoiceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*voice*");
            mVoiceWakeLock.setReferenceCounted(false);
        }
    }

    public void installSystemProviders() {
        mFontScaleSettingObserver = new FontScaleSettingObserver();
    }

    public void retrieveSettings(ContentResolver resolver) {
        final boolean freeformWindowManagement =
                mContext.getPackageManager().hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                        || Settings.Global.getInt(
                        resolver, DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;

        final boolean supportsMultiWindow = ActivityTaskManager.supportsMultiWindow(mContext);
        final boolean supportsPictureInPicture = supportsMultiWindow &&
                mContext.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE);
        final boolean supportsSplitScreenMultiWindow =
                ActivityTaskManager.supportsSplitScreenMultiWindow(mContext);
        final boolean supportsMultiDisplay = mContext.getPackageManager()
                .hasSystemFeature(FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS);
        final boolean forceRtl = Settings.Global.getInt(resolver, DEVELOPMENT_FORCE_RTL, 0) != 0;
        final boolean forceResizable = Settings.Global.getInt(
                resolver, DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0) != 0;
        final boolean isPc = mContext.getPackageManager().hasSystemFeature(FEATURE_PC);

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
            final boolean multiWindowFormEnabled = freeformWindowManagement
                    || supportsSplitScreenMultiWindow
                    || supportsPictureInPicture
                    || supportsMultiDisplay;
            if ((supportsMultiWindow || forceResizable) && multiWindowFormEnabled) {
                mSupportsMultiWindow = true;
                mSupportsFreeformWindowManagement = freeformWindowManagement;
                mSupportsSplitScreenMultiWindow = supportsSplitScreenMultiWindow;
                mSupportsPictureInPicture = supportsPictureInPicture;
                mSupportsMultiDisplay = supportsMultiDisplay;
            } else {
                mSupportsMultiWindow = false;
                mSupportsFreeformWindowManagement = false;
                mSupportsSplitScreenMultiWindow = false;
                mSupportsPictureInPicture = false;
                mSupportsMultiDisplay = false;
            }
            mWindowManager.setForceResizableTasks(mForceResizableActivities);
            mWindowManager.setSupportsPictureInPicture(mSupportsPictureInPicture);
            mWindowManager.setSupportsFreeformWindowManagement(mSupportsFreeformWindowManagement);
            mWindowManager.setIsPc(isPc);
            mWindowManager.mRoot.onSettingsRetrieved();
            // This happens before any activities are started, so we can change global configuration
            // in-place.
            updateConfigurationLocked(configuration, null, true);
            final Configuration globalConfig = getGlobalConfiguration();
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION, "Initial config: " + globalConfig);

            // Load resources only after the current configuration has been set.
            final Resources res = mContext.getResources();
            mThumbnailWidth = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.thumbnail_width);
            mThumbnailHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.thumbnail_height);

            if ((globalConfig.uiMode & UI_MODE_TYPE_TELEVISION) == UI_MODE_TYPE_TELEVISION) {
                mFullscreenThumbnailScale = (float) res
                        .getInteger(com.android.internal.R.integer.thumbnail_width_tv) /
                        (float) globalConfig.screenWidthDp;
            } else {
                mFullscreenThumbnailScale = res.getFraction(
                        com.android.internal.R.fraction.thumbnail_fullscreen_scale, 1, 1);
            }
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
        mAppWarnings = new AppWarnings(this, mUiContext, mH, mUiHandler, systemDir);
        mCompatModePackages = new CompatModePackages(this, systemDir, mH);
        mPendingIntentController = intentController;

        mTempConfig.setToDefaults();
        mTempConfig.setLocales(LocaleList.getDefault());
        mConfigurationSeq = mTempConfig.seq = 1;
        mStackSupervisor = createStackSupervisor();
        mRootActivityContainer = new RootActivityContainer(this);
        mRootActivityContainer.onConfigurationChanged(mTempConfig);

        mTaskChangeNotificationController =
                new TaskChangeNotificationController(mGlobalLock, mStackSupervisor, mH);
        mLockTaskController = new LockTaskController(mContext, mStackSupervisor, mH);
        mActivityStartController = new ActivityStartController(this);
        mRecentTasks = createRecentTasks();
        mStackSupervisor.setRecentTasks(mRecentTasks);
        mVrController = new VrController(mGlobalLock);
        mKeyguardController = mStackSupervisor.getKeyguardController();
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

    protected ActivityStackSupervisor createStackSupervisor() {
        final ActivityStackSupervisor supervisor = new ActivityStackSupervisor(this, mH.getLooper());
        supervisor.initialize();
        return supervisor;
    }

    public void setWindowManager(WindowManagerService wm) {
        synchronized (mGlobalLock) {
            mWindowManager = wm;
            mLockTaskController.setWindowManager(wm);
            mStackSupervisor.setWindowManager(wm);
            mRootActivityContainer.setWindowManager(wm);
        }
    }

    public void setUsageStatsManager(UsageStatsManagerInternal usageStatsManager) {
        synchronized (mGlobalLock) {
            mUsageStatsInternal = usageStatsManager;
        }
    }

    UserManagerService getUserManager() {
        if (mUserManager == null) {
            IBinder b = ServiceManager.getService(Context.USER_SERVICE);
            mUserManager = (UserManagerService) IUserManager.Stub.asInterface(b);
        }
        return mUserManager;
    }

    AppOpsService getAppOpsService() {
        if (mAppOpsService == null) {
            IBinder b = ServiceManager.getService(Context.APP_OPS_SERVICE);
            mAppOpsService = (AppOpsService) IAppOpsService.Stub.asInterface(b);
        }
        return mAppOpsService;
    }

    boolean hasUserRestriction(String restriction, int userId) {
        return getUserManager().hasUserRestriction(restriction, userId);
    }

    boolean hasSystemAlertWindowPermission(int callingUid, int callingPid, String callingPackage) {
        final int mode = getAppOpsService().noteOperation(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                callingUid, callingPackage);
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkPermission(Manifest.permission.SYSTEM_ALERT_WINDOW, callingPid, callingUid)
                    == PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    protected RecentTasks createRecentTasks() {
        return new RecentTasks(this, mStackSupervisor);
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
        public void onUnlockUser(int userId) {
            synchronized (mService.getGlobalLock()) {
                mService.mStackSupervisor.onUserUnlocked(userId);
            }
        }

        @Override
        public void onCleanupUser(int userId) {
            synchronized (mService.getGlobalLock()) {
                mService.mStackSupervisor.mLaunchParamsPersister.onCleanupUser(userId);
            }
        }

        public ActivityTaskManagerService getService() {
            return mService;
        }
    }

    @Override
    public final int startActivity(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions) {
        return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
                resultWho, requestCode, startFlags, profilerInfo, bOptions,
                UserHandle.getCallingUserId());
    }

    @Override
    public final int startActivities(IApplicationThread caller, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle bOptions,
            int userId) {
        final String reason = "startActivities";
        enforceNotIsolatedCaller(reason);
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, reason);
        // TODO: Switch to user app stacks here.
        return getActivityStartController().startActivities(caller, -1, 0, -1, callingPackage,
                intents, resolvedTypes, resultTo, SafeActivityOptions.fromBundle(bOptions), userId,
                reason, null /* originatingPendingIntent */,
                false /* allowBackgroundActivityStart */);
    }

    @Override
    public int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
                resultWho, requestCode, startFlags, profilerInfo, bOptions, userId,
                true /*validateIncomingUser*/);
    }

    int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId,
            boolean validateIncomingUser) {
        enforceNotIsolatedCaller("startActivityAsUser");

        userId = getActivityStartController().checkTargetUser(userId, validateIncomingUser,
                Binder.getCallingPid(), Binder.getCallingUid(), "startActivityAsUser");

        // TODO: Switch to user app stacks here.
        return getActivityStartController().obtainStarter(intent, "startActivityAsUser")
                .setCaller(caller)
                .setCallingPackage(callingPackage)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .setStartFlags(startFlags)
                .setProfilerInfo(profilerInfo)
                .setActivityOptions(bOptions)
                .setMayWait(userId)
                .execute();

    }

    @Override
    public int startActivityIntentSender(IApplicationThread caller, IIntentSender target,
            IBinder whitelistToken, Intent fillInIntent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int flagsMask, int flagsValues, Bundle bOptions) {
        enforceNotIsolatedCaller("startActivityIntentSender");
        // Refuse possible leaked file descriptors
        if (fillInIntent != null && fillInIntent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (!(target instanceof PendingIntentRecord)) {
            throw new IllegalArgumentException("Bad PendingIntent object");
        }

        PendingIntentRecord pir = (PendingIntentRecord)target;

        synchronized (mGlobalLock) {
            // If this is coming from the currently resumed activity, it is
            // effectively saying that app switches are allowed at this point.
            final ActivityStack stack = getTopDisplayFocusedStack();
            if (stack.mResumedActivity != null &&
                    stack.mResumedActivity.info.applicationInfo.uid == Binder.getCallingUid()) {
                mAppSwitchesAllowedTime = 0;
            }
        }
        return pir.sendInner(0, fillInIntent, resolvedType, whitelistToken, null, null,
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
            final ActivityRecord r = ActivityRecord.isInStackLocked(callingActivity);
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
                for (int i=0; i<N; i++) {
                    ResolveInfo rInfo = resolves.get(i);
                    if (rInfo.activityInfo.packageName.equals(r.packageName)
                            && rInfo.activityInfo.name.equals(r.info.name)) {
                        // We found the current one...  the next matching is
                        // after it.
                        i++;
                        if (i<N) {
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
            intent.setFlags(intent.getFlags()&~(
                    Intent.FLAG_ACTIVITY_FORWARD_RESULT|
                            Intent.FLAG_ACTIVITY_CLEAR_TOP|
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK|
                            FLAG_ACTIVITY_NEW_TASK));

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
            final int res = getActivityStartController()
                    .obtainStarter(intent, "startNextMatchingActivity")
                    .setCaller(r.app.getThread())
                    .setResolvedType(r.resolvedType)
                    .setActivityInfo(aInfo)
                    .setResultTo(resultTo != null ? resultTo.appToken : null)
                    .setResultWho(resultWho)
                    .setRequestCode(requestCode)
                    .setCallingPid(-1)
                    .setCallingUid(r.launchedFromUid)
                    .setCallingPackage(r.launchedFromPackage)
                    .setRealCallingPid(-1)
                    .setRealCallingUid(r.launchedFromUid)
                    .setActivityOptions(options)
                    .execute();
            Binder.restoreCallingIdentity(origId);

            r.finishing = wasFinishing;
            if (res != ActivityManager.START_SUCCESS) {
                return false;
            }
            return true;
        }
    }

    @Override
    public final WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        final WaitResult res = new WaitResult();
        synchronized (mGlobalLock) {
            enforceNotIsolatedCaller("startActivityAndWait");
            userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                    userId, "startActivityAndWait");
            // TODO: Switch to user app stacks here.
            getActivityStartController().obtainStarter(intent, "startActivityAndWait")
                    .setCaller(caller)
                    .setCallingPackage(callingPackage)
                    .setResolvedType(resolvedType)
                    .setResultTo(resultTo)
                    .setResultWho(resultWho)
                    .setRequestCode(requestCode)
                    .setStartFlags(startFlags)
                    .setActivityOptions(bOptions)
                    .setMayWait(userId)
                    .setProfilerInfo(profilerInfo)
                    .setWaitResult(res)
                    .execute();
        }
        return res;
    }

    @Override
    public final int startActivityWithConfig(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, Configuration config, Bundle bOptions, int userId) {
        synchronized (mGlobalLock) {
            enforceNotIsolatedCaller("startActivityWithConfig");
            userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                    "startActivityWithConfig");
            // TODO: Switch to user app stacks here.
            return getActivityStartController().obtainStarter(intent, "startActivityWithConfig")
                    .setCaller(caller)
                    .setCallingPackage(callingPackage)
                    .setResolvedType(resolvedType)
                    .setResultTo(resultTo)
                    .setResultWho(resultWho)
                    .setRequestCode(requestCode)
                    .setStartFlags(startFlags)
                    .setGlobalConfiguration(config)
                    .setActivityOptions(bOptions)
                    .setMayWait(userId)
                    .execute();
        }
    }


    @Override
    public IBinder requestStartActivityPermissionToken(IBinder delegatorToken) {
        int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) != SYSTEM_UID) {
            throw new SecurityException("Only the system process can request a permission token, "
                    + "received request from uid: " + callingUid);
        }
        IBinder permissionToken = new Binder();
        synchronized (mGlobalLock) {
            mStartActivitySources.put(permissionToken, delegatorToken);
        }

        Message expireMsg = PooledLambda.obtainMessage(
                ActivityTaskManagerService::expireStartAsCallerTokenMsg, this, permissionToken);
        mUiHandler.sendMessageDelayed(expireMsg, START_AS_CALLER_TOKEN_TIMEOUT_IMPL);

        Message forgetMsg = PooledLambda.obtainMessage(
                ActivityTaskManagerService::forgetStartAsCallerTokenMsg, this, permissionToken);
        mUiHandler.sendMessageDelayed(forgetMsg, START_AS_CALLER_TOKEN_EXPIRED_TIMEOUT);

        return permissionToken;
    }

    @Override
    public final int startActivityAsCaller(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, IBinder permissionToken,
            boolean ignoreTargetSecurity, int userId) {
        // This is very dangerous -- it allows you to perform a start activity (including
        // permission grants) as any app that may launch one of your own activities.  So we only
        // allow this in two cases:
        // 1)  The caller is an activity that is part of the core framework, and then only when it
        //     is running as the system.
        // 2)  The caller provides a valid permissionToken.  Permission tokens are one-time use and
        //     can only be requested by a system activity, which may then delegate this call to
        //     another app.
        final ActivityRecord sourceRecord;
        final int targetUid;
        final String targetPackage;
        final boolean isResolver;
        synchronized (mGlobalLock) {
            if (resultTo == null) {
                throw new SecurityException("Must be called from an activity");
            }
            final IBinder sourceToken;
            if (permissionToken != null) {
                // To even attempt to use a permissionToken, an app must also have this signature
                // permission.
                mAmInternal.enforceCallingPermission(
                        android.Manifest.permission.START_ACTIVITY_AS_CALLER,
                        "startActivityAsCaller");
                // If called with a permissionToken, we want the sourceRecord from the delegator
                // activity that requested this token.
                sourceToken = mStartActivitySources.remove(permissionToken);
                if (sourceToken == null) {
                    // Invalid permissionToken, check if it recently expired.
                    if (mExpiredStartAsCallerTokens.contains(permissionToken)) {
                        throw new SecurityException("Called with expired permission token: "
                                + permissionToken);
                    } else {
                        throw new SecurityException("Called with invalid permission token: "
                                + permissionToken);
                    }
                }
            } else {
                // This method was called directly by the source.
                sourceToken = resultTo;
            }

            sourceRecord = mRootActivityContainer.isInAnyStack(sourceToken);
            if (sourceRecord == null) {
                throw new SecurityException("Called with bad activity token: " + sourceToken);
            }
            if (sourceRecord.app == null) {
                throw new SecurityException("Called without a process attached to activity");
            }

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
                    .setResolvedType(resolvedType)
                    .setResultTo(resultTo)
                    .setResultWho(resultWho)
                    .setRequestCode(requestCode)
                    .setStartFlags(startFlags)
                    .setActivityOptions(bOptions)
                    .setMayWait(userId)
                    .setIgnoreTargetSecurity(ignoreTargetSecurity)
                    .setFilterCallingUid(isResolver ? 0 /* system */ : targetUid)
                    // The target may well be in the background, which would normally prevent it
                    // from starting an activity. Here we definitely want the start to succeed.
                    .setAllowBackgroundActivityStart(true)
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
                ALLOW_FULL_ONLY, name, null /* callerPackage */);
    }

    @Override
    public int startVoiceActivity(String callingPackage, int callingPid, int callingUid,
            Intent intent, String resolvedType, IVoiceInteractionSession session,
            IVoiceInteractor interactor, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions, int userId) {
        mAmInternal.enforceCallingPermission(BIND_VOICE_INTERACTION, "startVoiceActivity()");
        if (session == null || interactor == null) {
            throw new NullPointerException("null session or interactor");
        }
        userId = handleIncomingUser(callingPid, callingUid, userId, "startVoiceActivity");
        // TODO: Switch to user app stacks here.
        return getActivityStartController().obtainStarter(intent, "startVoiceActivity")
                .setCallingUid(callingUid)
                .setCallingPackage(callingPackage)
                .setResolvedType(resolvedType)
                .setVoiceSession(session)
                .setVoiceInteractor(interactor)
                .setStartFlags(startFlags)
                .setProfilerInfo(profilerInfo)
                .setActivityOptions(bOptions)
                .setMayWait(userId)
                .setAllowBackgroundActivityStart(true)
                .execute();
    }

    @Override
    public int startAssistantActivity(String callingPackage, int callingPid, int callingUid,
            Intent intent, String resolvedType, Bundle bOptions, int userId) {
        mAmInternal.enforceCallingPermission(BIND_VOICE_INTERACTION, "startAssistantActivity()");
        userId = handleIncomingUser(callingPid, callingUid, userId, "startAssistantActivity");

        return getActivityStartController().obtainStarter(intent, "startAssistantActivity")
                .setCallingUid(callingUid)
                .setCallingPackage(callingPackage)
                .setResolvedType(resolvedType)
                .setActivityOptions(bOptions)
                .setMayWait(userId)
                .setAllowBackgroundActivityStart(true)
                .execute();
    }

    /**
     * Start the recents activity to perform the recents animation.
     *
     * @param intent The intent to start the recents activity.
     * @param recentsAnimationRunner Pass {@code null} to only preload the activity.
     */
    @Override
    public void startRecentsActivity(Intent intent, @Deprecated IAssistDataReceiver unused,
            @Nullable IRecentsAnimationRunner recentsAnimationRunner) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "startRecentsActivity()");
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ComponentName recentsComponent = mRecentTasks.getRecentsComponent();
                final int recentsUid = mRecentTasks.getRecentsComponentUid();
                final WindowProcessController caller = getProcessController(callingPid, callingUid);

                // Start a new recents animation
                final RecentsAnimation anim = new RecentsAnimation(this, mStackSupervisor,
                        getActivityStartController(), mWindowManager, intent, recentsComponent,
                        recentsUid, caller);
                if (recentsAnimationRunner == null) {
                    anim.preloadRecentsActivity();
                } else {
                    anim.startRecentsActivity(recentsAnimationRunner);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public final int startActivityFromRecents(int taskId, Bundle bOptions) {
        enforceCallerIsRecentsOrHasPermission(START_TASKS_FROM_RECENTS,
                "startActivityFromRecents()");

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(bOptions);
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mStackSupervisor.startActivityFromRecents(callingPid, callingUid, taskId,
                        safeOptions);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Public API to check if the client is allowed to start an activity on specified display.
     *
     * If the target display is private or virtual, some restrictions will apply.
     *
     * @param displayId Target display id.
     * @param intent Intent used to launch the activity.
     * @param resolvedType The MIME type of the intent.
     * @param userId The id of the user for whom the call is made.
     * @return {@code true} if a call to start an activity on the target display should succeed and
     *         no {@link SecurityException} will be thrown, {@code false} otherwise.
     */
    @Override
    public final boolean isActivityStartAllowedOnDisplay(int displayId, Intent intent,
            String resolvedType, int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long origId = Binder.clearCallingIdentity();

        try {
            // Collect information about the target of the Intent.
            ActivityInfo aInfo = mStackSupervisor.resolveActivity(intent, resolvedType,
                    0 /* startFlags */, null /* profilerInfo */, userId,
                    ActivityStarter.computeResolveFilterUid(callingUid, callingUid,
                            UserHandle.USER_NULL));
            aInfo = mAmInternal.getActivityInfoForUser(aInfo, userId);

            synchronized (mGlobalLock) {
                return mStackSupervisor.canPlaceEntityOnDisplay(displayId, callingPid, callingUid,
                        aInfo);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     *
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * @param finishTask Whether to finish the task associated with this Activity.
     *
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    @Override
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData,
            int finishTask) {
        // Refuse possible leaked file descriptors
        if (resultData != null && resultData.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return true;
            }
            // Keep track of the root activity of the task before we finish it
            final TaskRecord tr = r.getTaskRecord();
            ActivityRecord rootR = tr.getRootActivity();
            if (rootR == null) {
                Slog.w(TAG, "Finishing task with all activities already finished");
            }
            // Do not allow task to finish if last task in lockTask mode. Launchable priv-apps can
            // finish.
            if (getLockTaskController().activityBlockedFromFinish(r)) {
                return false;
            }

            // TODO: There is a dup. of this block of code in ActivityStack.navigateUpToLocked
            // We should consolidate.
            if (mController != null) {
                // Find the first activity that is not finishing.
                final ActivityRecord next = r.getActivityStack().topRunningActivityLocked(token, 0);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mController.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mController = null;
                        Watchdog.getInstance().setActivityController(null);
                    }

                    if (!resumeOK) {
                        Slog.i(TAG, "Not finishing activity because controller resumed");
                        return false;
                    }
                }
            }

            // note down that the process has finished an activity and is in background activity
            // starts grace period
            if (r.app != null) {
                r.app.setLastActivityFinishTimeIfNeeded(SystemClock.uptimeMillis());
            }

            final long origId = Binder.clearCallingIdentity();
            try {
                boolean res;
                final boolean finishWithRootActivity =
                        finishTask == Activity.FINISH_TASK_WITH_ROOT_ACTIVITY;
                if (finishTask == Activity.FINISH_TASK_WITH_ACTIVITY
                        || (finishWithRootActivity && r == rootR)) {
                    // If requested, remove the task that is associated to this activity only if it
                    // was the root activity in the task. The result code and data is ignored
                    // because we don't support returning them across task boundaries. Also, to
                    // keep backwards compatibility we remove the task from recents when finishing
                    // task with root activity.
                    res = mStackSupervisor.removeTaskByIdLocked(tr.taskId, false,
                            finishWithRootActivity, "finish-activity");
                    if (!res) {
                        Slog.i(TAG, "Removing task failed to finish activity");
                    }
                    // Explicitly dismissing the activity so reset its relaunch flag.
                    r.mRelaunchReason = RELAUNCH_REASON_NONE;
                } else {
                    res = tr.getStack().requestFinishActivityLocked(token, resultCode,
                            resultData, "app-request", true);
                    if (!res) {
                        Slog.i(TAG, "Failed to finish by app-request");
                    }
                }
                return res;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public boolean finishActivityAffinity(IBinder token) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }

                // Do not allow task to finish if last task in lockTask mode. Launchable priv-apps
                // can finish.
                final TaskRecord task = r.getTaskRecord();
                if (getLockTaskController().activityBlockedFromFinish(r)) {
                    return false;
                }
                return task.getStack().finishActivityAffinityLocked(r);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public final void activityIdle(IBinder token, Configuration config, boolean stopProfiling) {
        final long origId = Binder.clearCallingIdentity();
        try {
            WindowProcessController proc = null;
            synchronized (mGlobalLock) {
                ActivityStack stack = ActivityRecord.getStackLocked(token);
                if (stack == null) {
                    return;
                }
                final ActivityRecord r = mStackSupervisor.activityIdleInternalLocked(token,
                        false /* fromTimeout */, false /* processPausingActivities */, config);
                if (r != null) {
                    proc = r.app;
                }
                if (stopProfiling && proc != null) {
                    proc.clearProfilerIfNeeded();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public final void activityResumed(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            ActivityRecord.activityResumedLocked(token);
            mWindowManager.notifyAppResumedFinished(token);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityTopResumedStateLost() {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            mStackSupervisor.handleTopResumedStateReleased(false /* timeout */);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityPaused(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                stack.activityPausedLocked(token, false);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityStopped(IBinder token, Bundle icicle,
            PersistableBundle persistentState, CharSequence description) {
        if (DEBUG_ALL) Slog.v(TAG, "Activity stopped: token=" + token);

        // Refuse possible leaked file descriptors
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();

        String restartingName = null;
        int restartingUid = 0;
        final ActivityRecord r;
        synchronized (mGlobalLock) {
            r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                if (r.attachedToProcess()
                        && r.isState(ActivityStack.ActivityState.RESTARTING_PROCESS)) {
                    // The activity was requested to restart from
                    // {@link #restartActivityProcessIfVisible}.
                    restartingName = r.app.mName;
                    restartingUid = r.app.mUid;
                }
                r.activityStoppedLocked(icicle, persistentState, description);
            }
        }

        if (restartingName != null) {
            // In order to let the foreground activity can be restarted with its saved state from
            // {@link android.app.Activity#onSaveInstanceState}, the kill operation is postponed
            // until the activity reports stopped with the state. And the activity record will be
            // kept because the record state is restarting, then the activity will be restarted
            // immediately if it is still the top one.
            mStackSupervisor.removeRestartTimeouts(r);
            mAmInternal.killProcess(restartingName, restartingUid, "restartActivityProcess");
        }
        mAmInternal.trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityDestroyed(IBinder token) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "ACTIVITY DESTROYED: " + token);
        synchronized (mGlobalLock) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                stack.activityDestroyedLocked(token, "activityDestroyed");
            }
        }
    }

    @Override
    public final void activityRelaunched(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            mStackSupervisor.activityRelaunchedLocked(token);
        }
        Binder.restoreCallingIdentity(origId);
    }

    public final void activitySlept(IBinder token) {
        if (DEBUG_ALL) Slog.v(TAG, "Activity slept: token=" + token);

        final long origId = Binder.clearCallingIdentity();

        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                mStackSupervisor.activitySleptLocked(r);
            }
        }

        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                r.setRequestedOrientation(requestedOrientation);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public int getRequestedOrientation(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            return r.getOrientation();
        }
    }

    @Override
    public void setImmersive(IBinder token, boolean immersive) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            r.immersive = immersive;

            // update associated state if we're frontmost
            if (r.isResumedActivityOnDisplay()) {
                if (DEBUG_IMMERSIVE) Slog.d(TAG_IMMERSIVE, "Frontmost changed immersion: "+ r);
                applyUpdateLockStateLocked(r);
            }
        }
    }

    void applyUpdateLockStateLocked(ActivityRecord r) {
        // Modifications to the UpdateLock state are done on our handler, outside
        // the activity manager's locks.  The new state is determined based on the
        // state *now* of the relevant activity record.  The object is passed to
        // the handler solely for logging detail, not to be consulted/modified.
        final boolean nextState = r != null && r.immersive;
        mH.post(() -> {
            if (mUpdateLock.isHeld() != nextState) {
                if (DEBUG_IMMERSIVE) Slog.d(TAG_IMMERSIVE,
                        "Applying new update lock state '" + nextState + "' for " + r);
                if (nextState) {
                    mUpdateLock.acquire();
                } else {
                    mUpdateLock.release();
                }
            }
        });
    }

    @Override
    public boolean isImmersive(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            return r.immersive;
        }
    }

    @Override
    public boolean isTopActivityImmersive() {
        enforceNotIsolatedCaller("isTopActivityImmersive");
        synchronized (mGlobalLock) {
            final ActivityRecord r = getTopDisplayFocusedStack().topRunningActivityLocked();
            return (r != null) ? r.immersive : false;
        }
    }

    @Override
    public void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim) {
        synchronized (mGlobalLock) {
            ActivityRecord self = ActivityRecord.isInStackLocked(token);
            if (self == null) {
                return;
            }

            final long origId = Binder.clearCallingIdentity();

            if (self.isState(
                    ActivityStack.ActivityState.RESUMED, ActivityStack.ActivityState.PAUSING)) {
                self.getDisplay().mDisplayContent.mAppTransition.overridePendingAppTransition(
                        packageName, enterAnim, exitAnim, null);
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int getFrontActivityScreenCompatMode() {
        enforceNotIsolatedCaller("getFrontActivityScreenCompatMode");
        synchronized (mGlobalLock) {
            final ActivityRecord r = getTopDisplayFocusedStack().topRunningActivityLocked();
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
            final ActivityRecord r = getTopDisplayFocusedStack().topRunningActivityLocked();
            if (r == null) {
                Slog.w(TAG, "setFrontActivityScreenCompatMode failed: no top activity");
                return;
            }
            ai = r.info.applicationInfo;
            mCompatModePackages.setPackageScreenCompatModeLocked(ai, mode);
        }
    }

    @Override
    public int getLaunchedFromUid(IBinder activityToken) {
        ActivityRecord srec;
        synchronized (mGlobalLock) {
            srec = ActivityRecord.forTokenLocked(activityToken);
        }
        if (srec == null) {
            return -1;
        }
        return srec.launchedFromUid;
    }

    @Override
    public String getLaunchedFromPackage(IBinder activityToken) {
        ActivityRecord srec;
        synchronized (mGlobalLock) {
            srec = ActivityRecord.forTokenLocked(activityToken);
        }
        if (srec == null) {
            return null;
        }
        return srec.launchedFromPackage;
    }

    @Override
    public boolean convertFromTranslucent(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }
                final boolean translucentChanged = r.changeWindowTranslucency(true);
                if (translucentChanged) {
                    mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                }
                mWindowManager.setAppFullscreen(token, true);
                return translucentChanged;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean convertToTranslucent(IBinder token, Bundle options) {
        SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(options);
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }
                final TaskRecord task = r.getTaskRecord();
                int index = task.mActivities.lastIndexOf(r);
                if (index > 0) {
                    ActivityRecord under = task.mActivities.get(index - 1);
                    under.returningOptions = safeOptions != null ? safeOptions.getOptions(r) : null;
                }
                final boolean translucentChanged = r.changeWindowTranslucency(false);
                if (translucentChanged) {
                    r.getActivityStack().convertActivityToTranslucent(r);
                }
                mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                mWindowManager.setAppFullscreen(token, false);
                return translucentChanged;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void notifyActivityDrawn(IBinder token) {
        if (DEBUG_VISIBILITY) Slog.d(TAG_VISIBILITY, "notifyActivityDrawn: token=" + token);
        synchronized (mGlobalLock) {
            ActivityRecord r = mRootActivityContainer.isInAnyStack(token);
            if (r != null) {
                r.getActivityStack().notifyActivityDrawnLocked(r);
            }
        }
    }

    @Override
    public void reportActivityFullyDrawn(IBinder token, boolean restoredFromBundle) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            r.reportFullyDrawnLocked(restoredFromBundle);
        }
    }

    @Override
    public int getActivityDisplayId(IBinder activityToken) throws RemoteException {
        synchronized (mGlobalLock) {
            final ActivityStack stack = ActivityRecord.getStackLocked(activityToken);
            if (stack != null && stack.mDisplayId != INVALID_DISPLAY) {
                return stack.mDisplayId;
            }
            return DEFAULT_DISPLAY;
        }
    }

    @Override
    public ActivityManager.StackInfo getFocusedStackInfo() throws RemoteException {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "getStackInfo()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ActivityStack focusedStack = getTopDisplayFocusedStack();
                if (focusedStack != null) {
                    return mRootActivityContainer.getStackInfo(focusedStack.mStackId);
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setFocusedStack(int stackId) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "setFocusedStack()");
        if (DEBUG_FOCUS) Slog.d(TAG_FOCUS, "setFocusedStack: stackId=" + stackId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityStack stack = mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    Slog.w(TAG, "setFocusedStack: No stack with id=" + stackId);
                    return;
                }
                final ActivityRecord r = stack.topRunningActivityLocked();
                if (r != null && r.moveFocusableActivityToTop("setFocusedStack")) {
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void setFocusedTask(int taskId) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "setFocusedTask()");
        if (DEBUG_FOCUS) Slog.d(TAG_FOCUS, "setFocusedTask: taskId=" + taskId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_ONLY);
                if (task == null) {
                    return;
                }
                final ActivityRecord r = task.topRunningActivityLocked();
                if (r != null && r.moveFocusableActivityToTop("setFocusedTask")) {
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void restartActivityProcessIfVisible(IBinder activityToken) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "restartActivityProcess()");
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                if (r == null) {
                    return;
                }
                r.restartProcessIfVisible();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public boolean removeTask(int taskId) {
        enforceCallerIsRecentsOrHasPermission(REMOVE_TASKS, "removeTask()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return mStackSupervisor.removeTaskByIdLocked(taskId, true, REMOVE_FROM_RECENTS,
                        "remove-task");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void removeAllVisibleRecentTasks() {
        enforceCallerIsRecentsOrHasPermission(REMOVE_TASKS, "removeAllVisibleRecentTasks()");
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
    public boolean shouldUpRecreateTask(IBinder token, String destAffinity) {
        synchronized (mGlobalLock) {
            final ActivityRecord srec = ActivityRecord.forTokenLocked(token);
            if (srec != null) {
                return srec.getActivityStack().shouldUpRecreateTaskLocked(srec, destAffinity);
            }
        }
        return false;
    }

    @Override
    public boolean navigateUpTo(IBinder token, Intent destIntent, int resultCode,
            Intent resultData) {

        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                return r.getActivityStack().navigateUpToLocked(
                        r, destIntent, resultCode, resultData);
            }
            return false;
        }
    }

    /**
     * Attempts to move a task backwards in z-order (the order of activities within the task is
     * unchanged).
     *
     * There are several possible results of this call:
     * - if the task is locked, then we will show the lock toast
     * - if there is a task behind the provided task, then that task is made visible and resumed as
     *   this task is moved to the back
     * - otherwise, if there are no other tasks in the stack:
     *     - if this task is in the pinned stack, then we remove the stack completely, which will
     *       have the effect of moving the task to the top or bottom of the fullscreen stack
     *       (depending on whether it is visible)
     *     - otherwise, we simply return home and hide this task
     *
     * @param token A reference to the activity we wish to move
     * @param nonRoot If false then this only works if the activity is the root
     *                of a task; if true it will work for any activity in a task.
     * @return Returns true if the move completed, false if not.
     */
    @Override
    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        enforceNotIsolatedCaller("moveActivityTaskToBack");
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                int taskId = ActivityRecord.getTaskForActivityLocked(token, !nonRoot);
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId);
                if (task != null) {
                    return ActivityRecord.getStackLocked(token).moveTaskToBackLocked(taskId);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
        return false;
    }

    @Override
    public Rect getTaskBounds(int taskId) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "getTaskBounds()");
        long ident = Binder.clearCallingIdentity();
        Rect rect = new Rect();
        try {
            synchronized (mGlobalLock) {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
                if (task == null) {
                    Slog.w(TAG, "getTaskBounds: taskId=" + taskId + " not found");
                    return rect;
                }
                if (task.getStack() != null) {
                    // Return the bounds from window manager since it will be adjusted for various
                    // things like the presense of a docked stack for tasks that aren't resizeable.
                    task.getWindowContainerBounds(rect);
                } else {
                    // Task isn't in window manager yet since it isn't associated with a stack.
                    // Return the persist value from activity manager
                    if (!task.matchParentBounds()) {
                        rect.set(task.getBounds());
                    } else if (task.mLastNonFullscreenBounds != null) {
                        rect.set(task.mLastNonFullscreenBounds);
                    }
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
            enforceCallerIsRecentsOrHasPermission(
                    MANAGE_ACTIVITY_STACKS, "getTaskDescription()");
            final TaskRecord tr = mRootActivityContainer.anyTaskForId(id,
                    MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
            if (tr != null) {
                return tr.lastTaskDescription;
            }
        }
        return null;
    }

    @Override
    public void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) {
        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            setTaskWindowingModeSplitScreenPrimary(taskId, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT,
                    toTop, ANIMATE, null /* initialBounds */, true /* showRecents */);
            return;
        }
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "setTaskWindowingMode()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_ONLY);
                if (task == null) {
                    Slog.w(TAG, "setTaskWindowingMode: No task for id=" + taskId);
                    return;
                }

                if (DEBUG_STACK) Slog.d(TAG_STACK, "setTaskWindowingMode: moving task=" + taskId
                        + " to windowingMode=" + windowingMode + " toTop=" + toTop);

                if (!task.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("setTaskWindowingMode: Attempt to move"
                            + " non-standard task " + taskId + " to windowing mode="
                            + windowingMode);
                }

                final ActivityStack stack = task.getStack();
                if (toTop) {
                    stack.moveToFront("setTaskWindowingMode", task);
                }
                stack.setWindowingMode(windowingMode);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public String getCallingPackage(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = getCallingRecordLocked(token);
            return r != null ? r.info.packageName : null;
        }
    }

    @Override
    public ComponentName getCallingActivity(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = getCallingRecordLocked(token);
            return r != null ? r.intent.getComponent() : null;
        }
    }

    private ActivityRecord getCallingRecordLocked(IBinder token) {
        ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r == null) {
            return null;
        }
        return r.resultTo;
    }

    @Override
    public void unhandledBack() {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.FORCE_BACK, "unhandledBack()");

        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                getTopDisplayFocusedStack().unhandledBackLocked();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void onBackPressedOnTaskRoot(IBinder token, IRequestFinishCallback callback) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            ActivityStack stack = r.getActivityStack();
            if (stack != null && stack.isSingleTaskInstance()) {
                // Single-task stacks are used for activities which are presented in floating
                // windows above full screen activities. Instead of directly finishing the
                // task, a task change listener is used to notify SystemUI so the action can be
                // handled specially.
                final TaskRecord task = r.getTaskRecord();
                mTaskChangeNotificationController
                        .notifyBackPressedOnTaskRoot(task.getTaskInfo());
            } else {
                try {
                    callback.requestFinish();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to invoke request finish callback", e);
                }
            }
        }
    }

    /**
     * TODO: Add mController hook
     */
    @Override
    public void moveTaskToFront(IApplicationThread appThread, String callingPackage, int taskId,
            int flags, Bundle bOptions) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.REORDER_TASKS, "moveTaskToFront()");

        if (DEBUG_STACK) Slog.d(TAG_STACK, "moveTaskToFront: moving taskId=" + taskId);
        synchronized (mGlobalLock) {
            moveTaskToFrontLocked(appThread, callingPackage, taskId, flags,
                    SafeActivityOptions.fromBundle(bOptions), false /* fromRecents */);
        }
    }

    void moveTaskToFrontLocked(@Nullable IApplicationThread appThread,
            @Nullable String callingPackage, int taskId, int flags, SafeActivityOptions options,
            boolean fromRecents) {

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        if (!isSameApp(callingUid, callingPackage)) {
            String msg = "Permission Denial: moveTaskToFrontLocked() from pid="
                    + Binder.getCallingPid() + " as package " + callingPackage;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (!checkAppSwitchAllowedLocked(callingPid, callingUid, -1, -1, "Task to front")) {
            SafeActivityOptions.abort(options);
            return;
        }
        final long origId = Binder.clearCallingIdentity();
        WindowProcessController callerApp = null;
        if (appThread != null) {
            callerApp = getProcessController(appThread);
        }
        final ActivityStarter starter = getActivityStartController().obtainStarter(
                null /* intent */, "moveTaskToFront");
        if (starter.shouldAbortBackgroundActivityStart(callingUid, callingPid, callingPackage, -1,
                -1, callerApp, null, false, null)) {
            if (!isBackgroundActivityStartsEnabled()) {
                return;
            }
        }
        try {
            final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId);
            if (task == null) {
                Slog.d(TAG, "Could not find task for id: "+ taskId);
                SafeActivityOptions.abort(options);
                return;
            }
            if (getLockTaskController().isLockTaskModeViolation(task)) {
                Slog.e(TAG, "moveTaskToFront: Attempt to violate Lock Task Mode");
                SafeActivityOptions.abort(options);
                return;
            }
            ActivityOptions realOptions = options != null
                    ? options.getOptions(mStackSupervisor)
                    : null;
            mStackSupervisor.findTaskToMoveToFront(task, flags, realOptions, "moveTaskToFront",
                    false /* forceNonResizable */);

            final ActivityRecord topActivity = task.getTopActivity();
            if (topActivity != null) {

                // We are reshowing a task, use a starting window to hide the initial draw delay
                // so the transition can start earlier.
                topActivity.showStartingWindow(null /* prev */, false /* newTask */,
                        true /* taskSwitch */, fromRecents);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Return true if callingUid is system, or packageName belongs to that callingUid.
     */
    boolean isSameApp(int callingUid, @Nullable String packageName) {
        try {
            if (callingUid != 0 && callingUid != SYSTEM_UID) {
                if (packageName == null) {
                    return false;
                }
                final int uid = AppGlobals.getPackageManager().getPackageUid(packageName,
                        PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.getUserId(callingUid));
                return UserHandle.isSameApp(callingUid, uid);
            }
        } catch (RemoteException e) {
            // Should not happen
        }
        return true;
    }

    boolean checkAppSwitchAllowedLocked(int sourcePid, int sourceUid,
            int callingPid, int callingUid, String name) {
        if (mAppSwitchesAllowedTime < SystemClock.uptimeMillis()) {
            return true;
        }

        if (getRecentTasks().isCallerRecents(sourceUid)) {
            return true;
        }

        int perm = checkComponentPermission(STOP_APP_SWITCHES, sourcePid, sourceUid, -1, true);
        if (perm == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (checkAllowAppSwitchUid(sourceUid)) {
            return true;
        }

        // If the actual IPC caller is different from the logical source, then
        // also see if they are allowed to control app switches.
        if (callingUid != -1 && callingUid != sourceUid) {
            perm = checkComponentPermission(STOP_APP_SWITCHES, callingPid, callingUid, -1, true);
            if (perm == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            if (checkAllowAppSwitchUid(callingUid)) {
                return true;
            }
        }

        Slog.w(TAG, name + " request from " + sourceUid + " stopped");
        return false;
    }

    private boolean checkAllowAppSwitchUid(int uid) {
        ArrayMap<String, Integer> types = mAllowAppSwitchUids.get(UserHandle.getUserId(uid));
        if (types != null) {
            for (int i = types.size() - 1; i >= 0; i--) {
                if (types.valueAt(i).intValue() == uid) {
                    return true;
                }
            }
        }
        return false;
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

    @Override
    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        synchronized (mGlobalLock) {
            return ActivityRecord.getTaskForActivityLocked(token, onlyRoot);
        }
    }

    @Override
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return getFilteredTasks(maxNum, ACTIVITY_TYPE_UNDEFINED, WINDOWING_MODE_UNDEFINED);
    }

    @Override
    public List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum,
            @WindowConfiguration.ActivityType int ignoreActivityType,
            @WindowConfiguration.WindowingMode int ignoreWindowingMode) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final boolean crossUser = isCrossUserAllowed(callingPid, callingUid);
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
            mRootActivityContainer.getRunningTasks(maxNum, list, ignoreActivityType,
                    ignoreWindowingMode, callingUid, allowed, crossUser, callingProfileIds);
        }

        return list;
    }

    @Override
    public final void finishSubActivity(IBinder token, String resultWho, int requestCode) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.getActivityStack().finishSubActivityLocked(r, resultWho, requestCode);
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean willActivityBeVisible(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                return stack.willActivityBeVisibleLocked(token);
            }
            return false;
        }
    }

    @Override
    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "moveTaskToStack()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId);
                if (task == null) {
                    Slog.w(TAG, "moveTaskToStack: No task for id=" + taskId);
                    return;
                }

                if (DEBUG_STACK) Slog.d(TAG_STACK, "moveTaskToStack: moving task=" + taskId
                        + " to stackId=" + stackId + " toTop=" + toTop);

                final ActivityStack stack = mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    throw new IllegalStateException(
                            "moveTaskToStack: No stack for stackId=" + stackId);
                }
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("moveTaskToStack: Attempt to move task "
                            + taskId + " to stack " + stackId);
                }
                if (stack.inSplitScreenPrimaryWindowingMode()) {
                    mWindowManager.setDockedStackCreateState(
                            SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, null /* initialBounds */);
                }
                task.reparent(stack, toTop, REPARENT_KEEP_STACK_AT_FRONT, ANIMATE, !DEFER_RESUME,
                        "moveTaskToStack");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void resizeStack(int stackId, Rect destBounds, boolean allowResizeInDockedMode,
            boolean preserveWindows, boolean animate, int animationDuration) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "resizeStack()");

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (animate) {
                    final ActivityStack stack = mRootActivityContainer.getStack(stackId);
                    if (stack == null) {
                        Slog.w(TAG, "resizeStack: stackId " + stackId + " not found.");
                        return;
                    }
                    if (stack.getWindowingMode() != WINDOWING_MODE_PINNED) {
                        throw new IllegalArgumentException("Stack: " + stackId
                                + " doesn't support animated resize.");
                    }
                    stack.animateResizePinnedStack(null /* sourceHintBounds */, destBounds,
                            animationDuration, false /* fromFullscreen */);
                } else {
                    final ActivityStack stack = mRootActivityContainer.getStack(stackId);
                    if (stack == null) {
                        Slog.w(TAG, "resizeStack: stackId " + stackId + " not found.");
                        return;
                    }
                    mRootActivityContainer.resizeStack(stack, destBounds,
                            null /* tempTaskBounds */, null /* tempTaskInsetBounds */,
                            preserveWindows, allowResizeInDockedMode, !DEFER_RESUME);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void offsetPinnedStackBounds(int stackId, Rect compareBounds, int xOffset, int yOffset,
            int animationDuration) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "offsetPinnedStackBounds()");

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (xOffset == 0 && yOffset == 0) {
                    return;
                }
                final ActivityStack stack = mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    Slog.w(TAG, "offsetPinnedStackBounds: stackId " + stackId + " not found.");
                    return;
                }
                if (stack.getWindowingMode() != WINDOWING_MODE_PINNED) {
                    throw new IllegalArgumentException("Stack: " + stackId
                            + " doesn't support animated resize.");
                }
                final Rect destBounds = new Rect();
                stack.getAnimationOrCurrentBounds(destBounds);
                if (destBounds.isEmpty() || !destBounds.equals(compareBounds)) {
                    Slog.w(TAG, "The current stack bounds does not matched! It may be obsolete.");
                    return;
                }
                destBounds.offset(xOffset, yOffset);
                stack.animateResizePinnedStack(null /* sourceHintBounds */, destBounds,
                        animationDuration, false /* fromFullscreen */);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
    /**
     * Moves the specified task to the primary-split-screen stack.
     *
     * @param taskId Id of task to move.
     * @param createMode The mode the primary split screen stack should be created in if it doesn't
     *                   exist already. See
     *                   {@link android.app.ActivityTaskManager#SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT}
     *                   and
     *                   {@link android.app.ActivityTaskManager#SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT}
     * @param toTop If the task and stack should be moved to the top.
     * @param animate Whether we should play an animation for the moving the task.
     * @param initialBounds If the primary stack gets created, it will use these bounds for the
     *                      stack. Pass {@code null} to use default bounds.
     * @param showRecents If the recents activity should be shown on the other side of the task
     *                    going into split-screen mode.
     */
    @Override
    public boolean setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode,
            boolean toTop, boolean animate, Rect initialBounds, boolean showRecents) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "setTaskWindowingModeSplitScreenPrimary()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_ONLY);
                if (task == null) {
                    Slog.w(TAG, "setTaskWindowingModeSplitScreenPrimary: No task for id=" + taskId);
                    return false;
                }
                if (DEBUG_STACK) Slog.d(TAG_STACK,
                        "setTaskWindowingModeSplitScreenPrimary: moving task=" + taskId
                                + " to createMode=" + createMode + " toTop=" + toTop);
                if (!task.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("setTaskWindowingMode: Attempt to move"
                            + " non-standard task " + taskId + " to split-screen windowing mode");
                }

                mWindowManager.setDockedStackCreateState(createMode, initialBounds);
                final int windowingMode = task.getWindowingMode();
                final ActivityStack stack = task.getStack();
                if (toTop) {
                    stack.moveToFront("setTaskWindowingModeSplitScreenPrimary", task);
                }
                stack.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, animate, showRecents,
                        false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */,
                        false /* creating */);
                return windowingMode != task.getWindowingMode();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    @Override
    public void removeStacksInWindowingModes(int[] windowingModes) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "removeStacksInWindowingModes()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mRootActivityContainer.removeStacksInWindowingModes(windowingModes);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void removeStacksWithActivityTypes(int[] activityTypes) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "removeStacksWithActivityTypes()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mRootActivityContainer.removeStacksWithActivityTypes(activityTypes);
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
        final boolean detailed = checkGetTasksPermission(
                android.Manifest.permission.GET_DETAILED_TASKS, Binder.getCallingPid(),
                UserHandle.getAppId(callingUid))
                == PackageManager.PERMISSION_GRANTED;

        synchronized (mGlobalLock) {
            return mRecentTasks.getRecentTasks(maxNum, flags, allowed, detailed, userId,
                    callingUid);
        }
    }

    @Override
    public List<ActivityManager.StackInfo> getAllStackInfos() {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "getAllStackInfos()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.getAllStackInfos();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "getStackInfo()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.getStackInfo(windowingMode, activityType);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void cancelRecentsAnimation(boolean restoreHomeStackPosition) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "cancelRecentsAnimation()");
        final long callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                // Cancel the recents animation synchronously (do not hold the WM lock)
                mWindowManager.cancelRecentsAnimationSynchronously(restoreHomeStackPosition
                        ? REORDER_MOVE_TO_ORIGINAL_POSITION
                        : REORDER_KEEP_IN_PLACE, "cancelRecentsAnimation/uid=" + callingUid);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void startLockTaskModeByToken(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r == null) {
                return;
            }
            startLockTaskModeLocked(r.getTaskRecord(), false /* isSystemCaller */);
        }
    }

    @Override
    public void startSystemLockTaskMode(int taskId) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "startSystemLockTaskMode");
        // This makes inner call to look as if it was initiated by system.
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_ONLY);
                if (task == null) {
                    return;
                }

                // When starting lock task mode the stack must be in front and focused
                task.getStack().moveToFront("startSystemLockTaskMode");
                startLockTaskModeLocked(task, true /* isSystemCaller */);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void stopLockTaskModeByToken(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r == null) {
                return;
            }
            stopLockTaskModeInternal(r.getTaskRecord(), false /* isSystemCaller */);
        }
    }

    /**
     * This API should be called by SystemUI only when user perform certain action to dismiss
     * lock task mode. We should only dismiss pinned lock task mode in this case.
     */
    @Override
    public void stopSystemLockTaskMode() throws RemoteException {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "stopSystemLockTaskMode");
        stopLockTaskModeInternal(null, true /* isSystemCaller */);
    }

    private void startLockTaskModeLocked(@Nullable TaskRecord task, boolean isSystemCaller) {
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "startLockTaskModeLocked: " + task);
        if (task == null || task.mLockTaskAuth == LOCK_TASK_AUTH_DONT_LOCK) {
            return;
        }

        final ActivityStack stack = mRootActivityContainer.getTopDisplayFocusedStack();
        if (stack == null || task != stack.topTask()) {
            throw new IllegalArgumentException("Invalid task, not in foreground");
        }

        // {@code isSystemCaller} is used to distinguish whether this request is initiated by the
        // system or a specific app.
        // * System-initiated requests will only start the pinned mode (screen pinning)
        // * App-initiated requests
        //   - will put the device in fully locked mode (LockTask), if the app is whitelisted
        //   - will start the pinned mode, otherwise
        final int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            // When a task is locked, dismiss the pinned stack if it exists
            mRootActivityContainer.removeStacksInWindowingModes(WINDOWING_MODE_PINNED);

            getLockTaskController().startLockTaskMode(task, isSystemCaller, callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void stopLockTaskModeInternal(@Nullable TaskRecord task, boolean isSystemCaller) {
        final int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
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
        synchronized (this) {
            if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "Whitelisting " + userId + ":"
                    + Arrays.toString(packages));
            getLockTaskController().updateLockTaskPackages(userId, packages);
        }
    }

    @Override
    public boolean isInLockTaskMode() {
        return getLockTaskModeState() != LOCK_TASK_MODE_NONE;
    }

    @Override
    public int getLockTaskModeState() {
        synchronized (mGlobalLock) {
            return getLockTaskController().getLockTaskModeState();
        }
    }

    @Override
    public void setTaskDescription(IBinder token, ActivityManager.TaskDescription td) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.setTaskDescription(td);
                final TaskRecord task = r.getTaskRecord();
                task.updateTaskDescription();
                mTaskChangeNotificationController.notifyTaskDescriptionChanged(task.getTaskInfo());
            }
        }
    }

    @Override
    public Bundle getActivityOptions(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    final ActivityOptions activityOptions = r.takeOptionsLocked(
                            true /* fromClient */);
                    return activityOptions == null ? null : activityOptions.toBundle();
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<IBinder> getAppTasks(String callingPackage) {
        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return mRecentTasks.getAppTasksList(callingUid, callingPackage);
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
                mRootActivityContainer.finishVoiceTask(session);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

    }

    @Override
    public boolean isTopOfTask(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            return r != null && r.getTaskRecord().getTopActivity() == r;
        }
    }

    @Override
    public void notifyLaunchTaskBehindComplete(IBinder token) {
        mStackSupervisor.scheduleLaunchTaskBehindComplete(token);
    }

    @Override
    public void notifyEnterAnimationComplete(IBinder token) {
        mH.post(() -> {
            synchronized (mGlobalLock) {
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r != null && r.attachedToProcess()) {
                    try {
                        r.app.getThread().scheduleEnterAnimationComplete(r.appToken);
                    } catch (RemoteException e) {
                    }
                }
            }

        });
    }

    /** Called from an app when assist data is ready. */
    @Override
    public void reportAssistContextExtras(IBinder token, Bundle extras, AssistStructure structure,
            AssistContent content, Uri referrer) {
        PendingAssistExtras pae = (PendingAssistExtras) token;
        synchronized (pae) {
            pae.result = extras;
            pae.structure = structure;
            pae.content = content;
            if (referrer != null) {
                pae.extras.putParcelable(Intent.EXTRA_REFERRER, referrer);
            }
            if (structure != null) {
                // Pre-fill the task/activity component for all assist data receivers
                structure.setTaskId(pae.activity.getTaskRecord().taskId);
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
                        pae.activity.getTaskRecord().taskId);
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
            if (TextUtils.equals(pae.intent.getAction(),
                    android.service.voice.VoiceInteractionService.SERVICE_INTERFACE)) {
                pae.intent.putExtras(pae.extras);

                startVoiceInteractionServiceAsUser(pae.intent, pae.userHandle, "AssistContext");
            } else {
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
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Workaround for historical API which starts the Assist service with a non-foreground
     * {@code startService()} call.
     */
    private void startVoiceInteractionServiceAsUser(
            Intent intent, int userHandle, String reason) {
        // Resolve the intent to find out which package we need to whitelist.
        ResolveInfo resolveInfo =
                mContext.getPackageManager().resolveServiceAsUser(intent, 0, userHandle);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.e(TAG, "VoiceInteractionService intent does not resolve. Not starting.");
            return;
        }
        intent.setPackage(resolveInfo.serviceInfo.packageName);

        // Whitelist background services temporarily.
        LocalServices.getService(DeviceIdleController.LocalService.class)
                .addPowerSaveTempWhitelistApp(Process.myUid(), intent.getPackage(),
                        SERVICE_LAUNCH_IDLE_WHITELIST_DURATION_MS, userHandle, false, reason);

        // Finally, try to start the service.
        try {
            mContext.startServiceAsUser(intent, UserHandle.of(userHandle));
        } catch (RuntimeException e) {
            Slog.e(TAG, "VoiceInteractionService failed to start.", e);
        }
    }

    @Override
    public int addAppTask(IBinder activityToken, Intent intent,
            ActivityManager.TaskDescription description, Bitmap thumbnail) throws RemoteException {
        final int callingUid = Binder.getCallingUid();
        final long callingIdent = Binder.clearCallingIdentity();

        try {
            synchronized (mGlobalLock) {
                ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
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
                if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0) {
                    if ((intent.getFlags()&Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS) == 0) {
                        // The caller has added this as an auto-remove task...  that makes no
                        // sense, so turn off auto-remove.
                        intent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
                    }
                }
                final ActivityInfo ainfo = AppGlobals.getPackageManager().getActivityInfo(comp,
                        STOCK_PM_FLAGS, UserHandle.getUserId(callingUid));
                if (ainfo.applicationInfo.uid != callingUid) {
                    throw new SecurityException(
                            "Can't add task for another application: target uid="
                                    + ainfo.applicationInfo.uid + ", calling uid=" + callingUid);
                }

                final ActivityStack stack = r.getActivityStack();
                final TaskRecord task = stack.createTaskRecord(
                        mStackSupervisor.getNextTaskIdForUserLocked(r.mUserId), ainfo, intent,
                        null /* voiceSession */, null /* voiceInteractor */, !ON_TOP);
                if (!mRecentTasks.addToBottom(task)) {
                    // The app has too many tasks already and we can't add any more
                    stack.removeTask(task, "addAppTask", REMOVE_TASK_MODE_DESTROYING);
                    return INVALID_TASK_ID;
                }
                task.lastTaskDescription.copyFrom(description);

                // TODO: Send the thumbnail to WM to store it.

                return task.taskId;
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
            final TaskRecord task = mRootActivityContainer.anyTaskForId(
                    taskId, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
            if (task == null) {
                Slog.w(TAG, "setTaskResizeable: taskId=" + taskId + " not found");
                return;
            }
            task.setResizeMode(resizeableMode);
        }
    }

    @Override
    public void resizeTask(int taskId, Rect bounds, int resizeMode) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "resizeTask()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_ONLY);
                if (task == null) {
                    Slog.w(TAG, "resizeTask: taskId=" + taskId + " not found");
                    return;
                }
                // Place the task in the right stack if it isn't there already based on
                // the requested bounds.
                // The stack transition logic is:
                // - a null bounds on a freeform task moves that task to fullscreen
                // - a non-null bounds on a non-freeform (fullscreen OR docked) task moves
                //   that task to freeform
                // - otherwise the task is not moved
                ActivityStack stack = task.getStack();
                if (!task.getWindowConfiguration().canResizeTask()) {
                    throw new IllegalArgumentException("resizeTask not allowed on task=" + task);
                }
                if (bounds == null && stack.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                    stack = stack.getDisplay().getOrCreateStack(
                            WINDOWING_MODE_FULLSCREEN, stack.getActivityType(), ON_TOP);
                } else if (bounds != null && stack.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
                    stack = stack.getDisplay().getOrCreateStack(
                            WINDOWING_MODE_FREEFORM, stack.getActivityType(), ON_TOP);
                }

                // Reparent the task to the right stack if necessary
                boolean preserveWindow = (resizeMode & RESIZE_MODE_PRESERVE_WINDOW) != 0;
                if (stack != task.getStack()) {
                    // Defer resume until the task is resized below
                    task.reparent(stack, ON_TOP, REPARENT_KEEP_STACK_AT_FRONT, ANIMATE,
                            DEFER_RESUME, "resizeTask");
                    preserveWindow = false;
                }

                // After reparenting (which only resizes the task to the stack bounds), resize the
                // task to the actual bounds provided
                task.resize(bounds, resizeMode, preserveWindow, !DEFER_RESUME);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean releaseActivityInstance(IBinder token) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }
                return r.getActivityStack().safelyDestroyActivityLocked(r, "app-req");
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void releaseSomeActivities(IApplicationThread appInt) {
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                final WindowProcessController app = getProcessController(appInt);
                mRootActivityContainer.releaseSomeActivitiesLocked(app, "low-mem");
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
            long ident = Binder.clearCallingIdentity();
            if (mKeyguardShown != keyguardShowing) {
                mKeyguardShown = keyguardShowing;
                final Message msg = PooledLambda.obtainMessage(
                        ActivityManagerInternal::reportCurKeyguardUsageEvent, mAmInternal,
                        keyguardShowing);
                mH.sendMessage(msg);
            }
            try {
                mKeyguardController.setKeyguardShown(keyguardShowing, aodShowing);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        mH.post(() -> {
            for (int i = mScreenObservers.size() - 1; i >= 0; i--) {
                mScreenObservers.get(i).onKeyguardStateChanged(keyguardShowing);
            }
        });
    }

    public void onScreenAwakeChanged(boolean isAwake) {
        mH.post(() -> {
            for (int i = mScreenObservers.size() - 1; i >= 0; i--) {
                mScreenObservers.get(i).onAwakeStateChanged(isAwake);
            }
        });
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
    public void startInPlaceAnimationOnFrontMostApplication(Bundle opts) {
        final SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(opts);
        final ActivityOptions activityOptions = safeOptions != null
                ? safeOptions.getOptions(mStackSupervisor)
                : null;
        if (activityOptions == null
                || activityOptions.getAnimationType() != ActivityOptions.ANIM_CUSTOM_IN_PLACE
                || activityOptions.getCustomInPlaceResId() == 0) {
            throw new IllegalArgumentException("Expected in-place ActivityOption " +
                    "with valid animation");
        }
        // Get top display of front most application.
        final ActivityStack focusedStack = getTopDisplayFocusedStack();
        if (focusedStack != null) {
            final DisplayContent dc = focusedStack.getDisplay().mDisplayContent;
            dc.prepareAppTransition(TRANSIT_TASK_IN_PLACE, false);
            dc.mAppTransition.overrideInPlaceAppTransition(activityOptions.getPackageName(),
                    activityOptions.getCustomInPlaceResId());
            dc.executeAppTransition();
        }
    }

    @Override
    public void removeStack(int stackId) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "removeStack()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final ActivityStack stack = mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    Slog.w(TAG, "removeStack: No stack with id=" + stackId);
                    return;
                }
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException(
                            "Removing non-standard stack is not allowed.");
                }
                mStackSupervisor.removeStack(stack);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void moveStackToDisplay(int stackId, int displayId) {
        mAmInternal.enforceCallingPermission(INTERNAL_SYSTEM_WINDOW, "moveStackToDisplay()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG_STACK) Slog.d(TAG_STACK, "moveStackToDisplay: moving stackId=" + stackId
                        + " to displayId=" + displayId);
                mRootActivityContainer.moveStackToDisplay(stackId, displayId, ON_TOP);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void toggleFreeformWindowingMode(IBinder token) {
        synchronized (mGlobalLock) {
            long ident = Binder.clearCallingIdentity();
            try {
                final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException(
                            "toggleFreeformWindowingMode: No activity record matching token="
                                    + token);
                }

                final ActivityStack stack = r.getActivityStack();
                if (stack == null) {
                    throw new IllegalStateException("toggleFreeformWindowingMode: the activity "
                            + "doesn't have a stack");
                }

                if (!stack.inFreeformWindowingMode()
                        && stack.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
                    throw new IllegalStateException("toggleFreeformWindowingMode: You can only "
                            + "toggle between fullscreen and freeform.");
                }

                if (stack.inFreeformWindowingMode()) {
                    stack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
                } else if (stack.getParent().inFreeformWindowingMode()) {
                    // If the window is on a freeform display, set it to undefined. It will be
                    // resolved to freeform and it can adjust windowing mode when the display mode
                    // changes in runtime.
                    stack.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                } else {
                    stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /** Sets the task stack listener that gets callbacks when a task stack changes. */
    @Override
    public void registerTaskStackListener(ITaskStackListener listener) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "registerTaskStackListener()");
        mTaskChangeNotificationController.registerTaskStackListener(listener);
    }

    /** Unregister a task stack listener so that it stops receiving callbacks. */
    @Override
    public void unregisterTaskStackListener(ITaskStackListener listener) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "unregisterTaskStackListener()");
        mTaskChangeNotificationController.unregisterTaskStackListener(listener);
    }

    @Override
    public boolean requestAssistContextExtras(int requestType, IAssistDataReceiver receiver,
            Bundle receiverExtras, IBinder activityToken, boolean focused, boolean newSessionId) {
        return enqueueAssistContext(requestType, null, null, receiver, receiverExtras,
                activityToken, focused, newSessionId, UserHandle.getCallingUserId(), null,
                PENDING_ASSIST_EXTRAS_LONG_TIMEOUT, 0) != null;
    }

    @Override
    public boolean requestAutofillData(IAssistDataReceiver receiver, Bundle receiverExtras,
            IBinder activityToken, int flags) {
        return enqueueAssistContext(ActivityManager.ASSIST_CONTEXT_AUTOFILL, null, null,
                receiver, receiverExtras, activityToken, true, true, UserHandle.getCallingUserId(),
                null, PENDING_AUTOFILL_ASSIST_STRUCTURE_TIMEOUT, flags) != null;
    }

    @Override
    public boolean launchAssistIntent(Intent intent, int requestType, String hint, int userHandle,
            Bundle args) {
        return enqueueAssistContext(requestType, intent, hint, null, null, null,
                true /* focused */, true /* newSessionId */, userHandle, args,
                PENDING_ASSIST_EXTRAS_TIMEOUT, 0) != null;
    }

    @Override
    public Bundle getAssistContextExtras(int requestType) {
        PendingAssistExtras pae = enqueueAssistContext(requestType, null, null, null,
                null, null, true /* focused */, true /* newSessionId */,
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
                permission, Binder.getCallingPid(), UserHandle.getAppId(Binder.getCallingUid()));
    }

    /** This can be called with or without the global lock held. */
    private void enforceCallerIsRecentsOrHasPermission(String permission, String func) {
        if (!getRecentTasks().isCallerRecents(Binder.getCallingUid())) {
            mAmInternal.enforceCallingPermission(permission, func);
        }
    }

    @VisibleForTesting
    int checkGetTasksPermission(String permission, int pid, int uid) {
        return checkPermission(permission, pid, uid);
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

    boolean isGetTasksAllowed(String caller, int callingPid, int callingUid) {
        if (getRecentTasks().isCallerRecents(callingUid)) {
            // Always allow the recents component to get tasks
            return true;
        }

        boolean allowed = checkGetTasksPermission(android.Manifest.permission.REAL_GET_TASKS,
                callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
        if (!allowed) {
            if (checkGetTasksPermission(android.Manifest.permission.GET_TASKS,
                    callingPid, callingUid) == PackageManager.PERMISSION_GRANTED) {
                // Temporary compatibility: some existing apps on the system image may
                // still be requesting the old permission and not switched to the new
                // one; if so, we'll still allow them full access.  This means we need
                // to see if they are holding the old permission and are a system app.
                try {
                    if (AppGlobals.getPackageManager().isUidPrivileged(callingUid)) {
                        allowed = true;
                        if (DEBUG_TASKS) Slog.w(TAG, caller + ": caller " + callingUid
                                + " is using old GET_TASKS but privileged; allowing");
                    }
                } catch (RemoteException e) {
                }
            }
            if (DEBUG_TASKS) Slog.w(TAG, caller + ": caller " + callingUid
                    + " does not hold REAL_GET_TASKS; limiting output");
        }
        return allowed;
    }

    boolean isCrossUserAllowed(int pid, int uid) {
        return checkPermission(INTERACT_ACROSS_USERS, pid, uid) == PERMISSION_GRANTED
                || checkPermission(INTERACT_ACROSS_USERS_FULL, pid, uid) == PERMISSION_GRANTED;
    }

    private PendingAssistExtras enqueueAssistContext(int requestType, Intent intent, String hint,
            IAssistDataReceiver receiver, Bundle receiverExtras, IBinder activityToken,
            boolean focused, boolean newSessionId, int userHandle, Bundle args, long timeout,
            int flags) {
        mAmInternal.enforceCallingPermission(android.Manifest.permission.GET_TOP_ACTIVITY_INFO,
                "enqueueAssistContext()");

        synchronized (mGlobalLock) {
            ActivityRecord activity = getTopDisplayFocusedStack().getTopActivity();
            if (activity == null) {
                Slog.w(TAG, "getAssistContextExtras failed: no top activity");
                return null;
            }
            if (!activity.attachedToProcess()) {
                Slog.w(TAG, "getAssistContextExtras failed: no process for " + activity);
                return null;
            }
            if (focused) {
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
                activity.app.getThread().requestAssistContextExtras(activity.appToken, pae,
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
            final ActivityStack focusedStack = getTopDisplayFocusedStack();
            if (focusedStack == null || focusedStack.isActivityTypeAssistant()) {
                return false;
            }

            final ActivityRecord activity = focusedStack.getTopActivity();
            if (activity == null) {
                return false;
            }
            userId = activity.mUserId;
        }
        return !DevicePolicyCache.getInstance().getScreenCaptureDisabled(userId);
    }

    @Override
    public boolean showAssistFromActivity(IBinder token, Bundle args) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ActivityRecord caller = ActivityRecord.forTokenLocked(token);
                ActivityRecord top = getTopDisplayFocusedStack().getTopActivity();
                if (top != caller) {
                    Slog.w(TAG, "showAssistFromActivity failed: caller " + caller
                            + " is not current top " + top);
                    return false;
                }
                if (!top.nowVisible) {
                    Slog.w(TAG, "showAssistFromActivity failed: caller " + caller
                            + " is not visible");
                    return false;
                }
            }
            return mAssistUtils.showSessionForActiveService(args, SHOW_SOURCE_APPLICATION, null,
                    token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isRootVoiceInteraction(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return false;
            }
            return r.rootVoiceInteraction;
        }
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
            long token = Binder.clearCallingIdentity();
            try {
                startRunningVoiceLocked(voiceSession, activityToCallback.appInfo.uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            // TODO: VI Should we cache the activity so that it's easier to find later
            // rather than scan through all the stacks and activities?
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
    public ComponentName getActivityClassForToken(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return null;
            }
            return r.intent.getComponent();
        }
    }

    @Override
    public String getPackageForToken(IBinder token) {
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return null;
            }
            return r.packageName;
        }
    }

    @Override
    public void showLockTaskEscapeMessage(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r == null) {
                return;
            }
            getLockTaskController().showLockTaskToast();
        }
    }

    @Override
    public void keyguardGoingAway(int flags) {
        enforceNotIsolatedCaller("keyguardGoingAway");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mKeyguardController.keyguardGoingAway(flags);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Try to place task to provided position. The final position might be different depending on
     * current user and stacks state. The task will be moved to target stack if it's currently in
     * different stack.
     */
    @Override
    public void positionTaskInStack(int taskId, int stackId, int position) {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "positionTaskInStack()");
        synchronized (mGlobalLock) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG_STACK) Slog.d(TAG_STACK, "positionTaskInStack: positioning task="
                        + taskId + " in stackId=" + stackId + " at position=" + position);
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId);
                if (task == null) {
                    throw new IllegalArgumentException("positionTaskInStack: no task for id="
                            + taskId);
                }

                final ActivityStack stack = mRootActivityContainer.getStack(stackId);

                if (stack == null) {
                    throw new IllegalArgumentException("positionTaskInStack: no stack for id="
                            + stackId);
                }
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("positionTaskInStack: Attempt to change"
                            + " the position of task " + taskId + " in/to non-standard stack");
                }

                // TODO: Have the callers of this API call a separate reparent method if that is
                // what they intended to do vs. having this method also do reparenting.
                if (task.getStack() == stack) {
                    // Change position in current stack.
                    stack.positionChildAt(task, position);
                } else {
                    // Reparent to new stack.
                    task.reparent(stack, position, REPARENT_LEAVE_STACK_IN_PLACE, !ANIMATE,
                            !DEFER_RESUME, "positionTaskInStack");
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void reportSizeConfigurations(IBinder token, int[] horizontalSizeConfiguration,
            int[] verticalSizeConfigurations, int[] smallestSizeConfigurations) {
        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Report configuration: " + token + " "
                + horizontalSizeConfiguration + " " + verticalSizeConfigurations);
        synchronized (mGlobalLock) {
            ActivityRecord record = ActivityRecord.isInStackLocked(token);
            if (record == null) {
                throw new IllegalArgumentException("reportSizeConfigurations: ActivityRecord not "
                        + "found for: " + token);
            }
            record.setSizeConfigurations(horizontalSizeConfiguration,
                    verticalSizeConfigurations, smallestSizeConfigurations);
        }
    }

    /**
     * Dismisses split-screen multi-window mode.
     * @param toTop If true the current primary split-screen stack will be placed or left on top.
     */
    @Override
    public void dismissSplitScreenMode(boolean toTop) {
        enforceCallerIsRecentsOrHasPermission(
                MANAGE_ACTIVITY_STACKS, "dismissSplitScreenMode()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityStack stack =
                        mRootActivityContainer.getDefaultDisplay().getSplitScreenPrimaryStack();
                if (stack == null) {
                    Slog.w(TAG, "dismissSplitScreenMode: primary split-screen stack not found.");
                    return;
                }

                if (toTop) {
                    // Caller wants the current split-screen primary stack to be the top stack after
                    // it goes fullscreen, so move it to the front.
                    stack.moveToFront("dismissSplitScreenMode");
                } else if (mRootActivityContainer.isTopDisplayFocusedStack(stack)) {
                    // In this case the current split-screen primary stack shouldn't be the top
                    // stack after it goes fullscreen, but it current has focus, so we move the
                    // focus to the top-most split-screen secondary stack next to it.
                    final ActivityStack otherStack = stack.getDisplay().getTopStackInWindowingMode(
                            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
                    if (otherStack != null) {
                        otherStack.moveToFront("dismissSplitScreenMode_other");
                    }
                }

                stack.setWindowingMode(WINDOWING_MODE_UNDEFINED);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Dismisses Pip
     * @param animate True if the dismissal should be animated.
     * @param animationDuration The duration of the resize animation in milliseconds or -1 if the
     *                          default animation duration should be used.
     */
    @Override
    public void dismissPip(boolean animate, int animationDuration) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "dismissPip()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityStack stack =
                        mRootActivityContainer.getDefaultDisplay().getPinnedStack();
                if (stack == null) {
                    Slog.w(TAG, "dismissPip: pinned stack not found.");
                    return;
                }
                if (stack.getWindowingMode() != WINDOWING_MODE_PINNED) {
                    throw new IllegalArgumentException("Stack: " + stack
                            + " doesn't support animated resize.");
                }
                if (animate) {
                    stack.animateResizePinnedStack(null /* sourceHintBounds */,
                            null /* destBounds */, animationDuration, false /* fromFullscreen */);
                } else {
                    mStackSupervisor.moveTasksToFullscreenStackLocked(stack, true /* onTop */);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void suppressResizeConfigChanges(boolean suppress) throws RemoteException {
        mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, "suppressResizeConfigChanges()");
        synchronized (mGlobalLock) {
            mSuppressResizeConfigChanges = suppress;
        }
    }

    /**
     * NOTE: For the pinned stack, this method is usually called after the bounds animation has
     *       animated the stack to the fullscreen, but can also be called if we are relaunching an
     *       activity and clearing the task at the same time.
     */
    @Override
    // TODO: API should just be about changing windowing modes...
    public void moveTasksToFullscreenStack(int fromStackId, boolean onTop) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "moveTasksToFullscreenStack()");
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                final ActivityStack stack = mRootActivityContainer.getStack(fromStackId);
                if (stack != null){
                    if (!stack.isActivityTypeStandardOrUndefined()) {
                        throw new IllegalArgumentException(
                                "You can't move tasks from non-standard stacks.");
                    }
                    mStackSupervisor.moveTasksToFullscreenStackLocked(stack, onTop);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    /**
     * Moves the top activity in the input stackId to the pinned stack.
     *
     * @param stackId Id of stack to move the top activity to pinned stack.
     * @param bounds Bounds to use for pinned stack.
     *
     * @return True if the top activity of the input stack was successfully moved to the pinned
     *          stack.
     */
    @Override
    public boolean moveTopActivityToPinnedStack(int stackId, Rect bounds) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "moveTopActivityToPinnedStack()");
        synchronized (mGlobalLock) {
            if (!mSupportsPictureInPicture) {
                throw new IllegalStateException("moveTopActivityToPinnedStack:"
                        + "Device doesn't support picture-in-picture mode");
            }

            long ident = Binder.clearCallingIdentity();
            try {
                return mRootActivityContainer.moveTopStackActivityToPinnedStack(stackId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public boolean isInMultiWindowMode(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }
                // An activity is consider to be in multi-window mode if its task isn't fullscreen.
                return r.inMultiWindowMode();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean isInPictureInPictureMode(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                return isInPictureInPictureMode(ActivityRecord.forTokenLocked(token));
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private boolean isInPictureInPictureMode(ActivityRecord r) {
        if (r == null || r.getActivityStack() == null || !r.inPinnedWindowingMode()
                || r.getActivityStack().isInStackLocked(r) == null) {
            return false;
        }

        // If we are animating to fullscreen then we have already dispatched the PIP mode
        // changed, so we should reflect that check here as well.
        final TaskStack taskStack = r.getActivityStack().getTaskStack();
        return !taskStack.isAnimatingBoundsToFullscreen();
    }

    @Override
    public boolean enterPictureInPictureMode(IBinder token, final PictureInPictureParams params) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ensureValidPictureInPictureActivityParamsLocked(
                        "enterPictureInPictureMode", token, params);

                // If the activity is already in picture in picture mode, then just return early
                if (isInPictureInPictureMode(r)) {
                    return true;
                }

                // Activity supports picture-in-picture, now check that we can enter PiP at this
                // point, if it is
                if (!r.checkEnterPictureInPictureState("enterPictureInPictureMode",
                        false /* beforeStopping */)) {
                    return false;
                }

                final Runnable enterPipRunnable = () -> {
                    synchronized (mGlobalLock) {
                        // Only update the saved args from the args that are set
                        r.pictureInPictureArgs.copyOnlySet(params);
                        final float aspectRatio = r.pictureInPictureArgs.getAspectRatio();
                        final List<RemoteAction> actions = r.pictureInPictureArgs.getActions();
                        // Adjust the source bounds by the insets for the transition down
                        final Rect sourceBounds = new Rect(
                                r.pictureInPictureArgs.getSourceRectHint());
                        mRootActivityContainer.moveActivityToPinnedStack(
                                r, sourceBounds, aspectRatio, "enterPictureInPictureMode");
                        final ActivityStack stack = r.getActivityStack();
                        stack.setPictureInPictureAspectRatio(aspectRatio);
                        stack.setPictureInPictureActions(actions);
                        MetricsLoggerWrapper.logPictureInPictureEnter(mContext, r.appInfo.uid,
                                r.shortComponentName, r.supportsEnterPipOnTaskSwitch);
                        logPictureInPictureArgs(params);
                    }
                };

                if (isKeyguardLocked()) {
                    // If the keyguard is showing or occluded, then try and dismiss it before
                    // entering picture-in-picture (this will prompt the user to authenticate if the
                    // device is currently locked).
                    dismissKeyguard(token, new KeyguardDismissCallback() {
                        @Override
                        public void onDismissSucceeded() {
                            mH.post(enterPipRunnable);
                        }
                    }, null /* message */);
                } else {
                    // Enter picture in picture immediately otherwise
                    enterPipRunnable.run();
                }
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setPictureInPictureParams(IBinder token, final PictureInPictureParams params) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ensureValidPictureInPictureActivityParamsLocked(
                        "setPictureInPictureParams", token, params);

                // Only update the saved args from the args that are set
                r.pictureInPictureArgs.copyOnlySet(params);
                if (r.inPinnedWindowingMode()) {
                    // If the activity is already in picture-in-picture, update the pinned stack now
                    // if it is not already expanding to fullscreen. Otherwise, the arguments will
                    // be used the next time the activity enters PiP
                    final ActivityStack stack = r.getActivityStack();
                    if (!stack.isAnimatingBoundsToFullscreen()) {
                        stack.setPictureInPictureAspectRatio(
                                r.pictureInPictureArgs.getAspectRatio());
                        stack.setPictureInPictureActions(r.pictureInPictureArgs.getActions());
                    }
                }
                logPictureInPictureArgs(params);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int getMaxNumPictureInPictureActions(IBinder token) {
        // Currently, this is a static constant, but later, we may change this to be dependent on
        // the context of the activity
        return 3;
    }

    private void logPictureInPictureArgs(PictureInPictureParams params) {
        if (params.hasSetActions()) {
            MetricsLogger.histogram(mContext, "tron_varz_picture_in_picture_actions_count",
                    params.getActions().size());
        }
        if (params.hasSetAspectRatio()) {
            LogMaker lm = new LogMaker(MetricsEvent.ACTION_PICTURE_IN_PICTURE_ASPECT_RATIO_CHANGED);
            lm.addTaggedData(MetricsEvent.PICTURE_IN_PICTURE_ASPECT_RATIO, params.getAspectRatio());
            MetricsLogger.action(lm);
        }
    }

    /**
     * Checks the state of the system and the activity associated with the given {@param token} to
     * verify that picture-in-picture is supported for that activity.
     *
     * @return the activity record for the given {@param token} if all the checks pass.
     */
    private ActivityRecord ensureValidPictureInPictureActivityParamsLocked(String caller,
            IBinder token, PictureInPictureParams params) {
        if (!mSupportsPictureInPicture) {
            throw new IllegalStateException(caller
                    + ": Device doesn't support picture-in-picture mode.");
        }

        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            throw new IllegalStateException(caller
                    + ": Can't find activity for token=" + token);
        }

        if (!r.supportsPictureInPicture()) {
            throw new IllegalStateException(caller
                    + ": Current activity does not support picture-in-picture.");
        }

        if (params.hasSetAspectRatio()
                && !mWindowManager.isValidPictureInPictureAspectRatio(
                        r.getActivityStack().mDisplayId, params.getAspectRatio())) {
            final float minAspectRatio = mContext.getResources().getFloat(
                    com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
            final float maxAspectRatio = mContext.getResources().getFloat(
                    com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
            throw new IllegalArgumentException(String.format(caller
                            + ": Aspect ratio is too extreme (must be between %f and %f).",
                    minAspectRatio, maxAspectRatio));
        }

        // Truncate the number of actions if necessary
        params.truncateActions(getMaxNumPictureInPictureActions(token));

        return r;
    }

    @Override
    public IBinder getUriPermissionOwnerForActivity(IBinder activityToken) {
        enforceNotIsolatedCaller("getUriPermissionOwnerForActivity");
        synchronized (mGlobalLock) {
            ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
            if (r == null) {
                throw new IllegalArgumentException("Activity does not exist; token="
                        + activityToken);
            }
            return r.getUriPermissionsLocked().getExternalToken();
        }
    }

    @Override
    public void resizeDockedStack(Rect dockedBounds, Rect tempDockedTaskBounds,
            Rect tempDockedTaskInsetBounds,
            Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "resizeDockedStack()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mStackSupervisor.resizeDockedStackLocked(dockedBounds, tempDockedTaskBounds,
                        tempDockedTaskInsetBounds, tempOtherTaskBounds, tempOtherTaskInsetBounds,
                        PRESERVE_WINDOWS);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setSplitScreenResizing(boolean resizing) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "setSplitScreenResizing()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mStackSupervisor.setSplitScreenResizing(resizing);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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
    public int setVrMode(IBinder token, boolean enabled, ComponentName packageName) {
        enforceSystemHasVrFeature();

        final VrManagerInternal vrService = LocalServices.getService(VrManagerInternal.class);

        ActivityRecord r;
        synchronized (mGlobalLock) {
            r = ActivityRecord.isInStackLocked(token);
        }

        if (r == null) {
            throw new IllegalArgumentException();
        }

        int err;
        if ((err = vrService.hasVrPackage(packageName, r.mUserId)) !=
                VrManagerInternal.NO_ERROR) {
            return err;
        }

        // Clear the binder calling uid since this path may call moveToTask().
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                r.requestedVrComponent = (enabled) ? packageName : null;

                // Update associated state if this activity is currently focused
                if (r.isResumedActivityOnDisplay()) {
                    applyUpdateVrModeLocked(r);
                }
                return 0;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
        Slog.i(TAG, "Activity tried to startLocalVoiceInteraction");
        synchronized (mGlobalLock) {
            ActivityRecord activity = getTopDisplayFocusedStack().getTopActivity();
            if (ActivityRecord.forTokenLocked(callingActivity) != activity) {
                throw new SecurityException("Only focused activity can call startVoiceInteraction");
            }
            if (mRunningVoice != null || activity.getTaskRecord().voiceSession != null
                    || activity.voiceSession != null) {
                Slog.w(TAG, "Already in a voice interaction, cannot start new voice interaction");
                return;
            }
            if (activity.pendingVoiceInteractionStart) {
                Slog.w(TAG, "Pending start of voice interaction already.");
                return;
            }
            activity.pendingVoiceInteractionStart = true;
        }
        LocalServices.getService(VoiceInteractionManagerInternal.class)
                .startLocalVoiceInteraction(callingActivity, options);
    }

    @Override
    public void stopLocalVoiceInteraction(IBinder callingActivity) {
        LocalServices.getService(VoiceInteractionManagerInternal.class)
                .stopLocalVoiceInteraction(callingActivity);
    }

    @Override
    public boolean supportsLocalVoiceInteraction() {
        return LocalServices.getService(VoiceInteractionManagerInternal.class)
                .supportsLocalVoiceInteraction();
    }

    /** Notifies all listeners when the pinned stack animation starts. */
    @Override
    public void notifyPinnedStackAnimationStarted() {
        mTaskChangeNotificationController.notifyPinnedStackAnimationStarted();
    }

    /** Notifies all listeners when the pinned stack animation ends. */
    @Override
    public void notifyPinnedStackAnimationEnded() {
        mTaskChangeNotificationController.notifyPinnedStackAnimationEnded();
    }

    @Override
    public void resizePinnedStack(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "resizePinnedStack()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mStackSupervisor.resizePinnedStackLocked(pinnedBounds, tempPinnedTaskBounds);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean updateDisplayOverrideConfiguration(Configuration values, int displayId) {
        mAmInternal.enforceCallingPermission(CHANGE_CONFIGURATION, "updateDisplayOverrideConfiguration()");

        synchronized (mGlobalLock) {
            // Check if display is initialized in AM.
            if (!mRootActivityContainer.isDisplayAdded(displayId)) {
                // Call might come when display is not yet added or has already been removed.
                if (DEBUG_CONFIGURATION) {
                    Slog.w(TAG, "Trying to update display configuration for non-existing displayId="
                            + displayId);
                }
                return false;
            }

            if (values == null && mWindowManager != null) {
                // sentinel: fetch the current configuration from the window manager
                values = mWindowManager.computeNewConfiguration(displayId);
            }

            if (mWindowManager != null) {
                final Message msg = PooledLambda.obtainMessage(
                        ActivityManagerInternal::updateOomLevelsForDisplay, mAmInternal, displayId);
                mH.sendMessage(msg);
            }

            final long origId = Binder.clearCallingIdentity();
            try {
                if (values != null) {
                    Settings.System.clearConfiguration(values);
                }
                updateDisplayOverrideConfigurationLocked(values, null /* starting */,
                        false /* deferResume */, displayId, mTmpUpdateConfigurationResult);
                return mTmpUpdateConfigurationResult.changes != 0;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public boolean updateConfiguration(Configuration values) {
        mAmInternal.enforceCallingPermission(CHANGE_CONFIGURATION, "updateConfiguration()");

        synchronized (mGlobalLock) {
            if (values == null && mWindowManager != null) {
                // sentinel: fetch the current configuration from the window manager
                values = mWindowManager.computeNewConfiguration(DEFAULT_DISPLAY);
            }

            if (mWindowManager != null) {
                final Message msg = PooledLambda.obtainMessage(
                        ActivityManagerInternal::updateOomLevelsForDisplay, mAmInternal,
                        DEFAULT_DISPLAY);
                mH.sendMessage(msg);
            }

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
    public void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback,
            CharSequence message) {
        if (message != null) {
            mAmInternal.enforceCallingPermission(
                    Manifest.permission.SHOW_KEYGUARD_MESSAGE, "dismissKeyguard()");
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mKeyguardController.dismissKeyguard(token, callback, message);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void cancelTaskWindowTransition(int taskId) {
        enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "cancelTaskWindowTransition()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskRecord task = mRootActivityContainer.anyTaskForId(taskId,
                        MATCH_TASK_IN_STACKS_ONLY);
                if (task == null) {
                    Slog.w(TAG, "cancelTaskWindowTransition: taskId=" + taskId + " not found");
                    return;
                }
                task.cancelWindowTransition();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution) {
        enforceCallerIsRecentsOrHasPermission(READ_FRAME_BUFFER, "getTaskSnapshot()");
        final long ident = Binder.clearCallingIdentity();
        try {
            return getTaskSnapshot(taskId, reducedResolution, true /* restoreFromDisk */);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution,
            boolean restoreFromDisk) {
        final TaskRecord task;
        synchronized (mGlobalLock) {
            task = mRootActivityContainer.anyTaskForId(taskId,
                    MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
            if (task == null) {
                Slog.w(TAG, "getTaskSnapshot: taskId=" + taskId + " not found");
                return null;
            }
        }
        // Don't call this while holding the lock as this operation might hit the disk.
        return task.getSnapshot(reducedResolution, restoreFromDisk);
    }

    @Override
    public void setDisablePreviewScreenshots(IBinder token, boolean disable) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                Slog.w(TAG, "setDisablePreviewScreenshots: Unable to find activity for token="
                        + token);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                r.setDisablePreviewScreenshots(disable);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
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
            mAmInternal.enforceCallingPermission(android.Manifest.permission.UPDATE_LOCK_TASK_PACKAGES,
                    "updateLockTaskFeatures()");
        }
        synchronized (mGlobalLock) {
            if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "Allowing features " + userId + ":0x" +
                    Integer.toHexString(flags));
            getLockTaskController().updateLockTaskFeatures(userId, flags);
        }
    }

    @Override
    public void setShowWhenLocked(IBinder token, boolean showWhenLocked) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                r.setShowWhenLocked(showWhenLocked);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void setInheritShowWhenLocked(IBinder token, boolean inheritShowWhenLocked) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                r.setInheritShowWhenLocked(inheritShowWhenLocked);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void setTurnScreenOn(IBinder token, boolean turnScreenOn) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                r.setTurnScreenOn(turnScreenOn);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void registerRemoteAnimations(IBinder token, RemoteAnimationDefinition definition) {
        mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "registerRemoteAnimations");
        definition.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                r.registerRemoteAnimations(definition);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void registerRemoteAnimationForNextActivityStart(String packageName,
            RemoteAnimationAdapter adapter) {
        mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "registerRemoteAnimationForNextActivityStart");
        adapter.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                getActivityStartController().registerRemoteAnimationForNextActivityStart(
                        packageName, adapter);
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
            final ActivityDisplay display = mRootActivityContainer.getActivityDisplay(displayId);
            if (display == null) {
                Slog.e(TAG, "Couldn't find display with id: " + displayId);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                display.mDisplayContent.registerRemoteAnimations(definition);
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
        enforceCallerIsRecentsOrHasPermission(STOP_APP_SWITCHES, "stopAppSwitches");
        synchronized (mGlobalLock) {
            mAppSwitchesAllowedTime = SystemClock.uptimeMillis() + APP_SWITCH_DELAY_TIME;
            mLastStopAppSwitchesTime = SystemClock.uptimeMillis();
            mDidAppSwitch = false;
            getActivityStartController().schedulePendingActivityLaunches(APP_SWITCH_DELAY_TIME);
        }
    }

    @Override
    public void resumeAppSwitches() {
        enforceCallerIsRecentsOrHasPermission(STOP_APP_SWITCHES, "resumeAppSwitches");
        synchronized (mGlobalLock) {
            // Note that we don't execute any pending app switches... we will
            // let those wait until either the timeout, or the next start
            // activity request.
            mAppSwitchesAllowedTime = 0;
        }
    }

    long getLastStopAppSwitchesTime() {
        return mLastStopAppSwitchesTime;
    }

    void onStartActivitySetDidAppSwitch() {
        if (mDidAppSwitch) {
            // This is the second allowed switch since we stopped switches, so now just generally
            // allow switches. Use case:
            // - user presses home (switches disabled, switch to home, mDidAppSwitch now true);
            // - user taps a home icon (coming from home so allowed, we hit here and now allow
            // anyone to switch again).
            mAppSwitchesAllowedTime = 0;
        } else {
            mDidAppSwitch = true;
        }
    }

    /** @return whether the system should disable UI modes incompatible with VR mode. */
    boolean shouldDisableNonVrUiLocked() {
        return mVrController.shouldDisableNonVrUiLocked();
    }

    private void applyUpdateVrModeLocked(ActivityRecord r) {
        // VR apps are expected to run in a main display. If an app is turning on VR for
        // itself, but isn't on the main display, then move it there before enabling VR Mode.
        if (r.requestedVrComponent != null && r.getDisplayId() != DEFAULT_DISPLAY) {
            Slog.i(TAG, "Moving " + r.shortComponentName + " from display " + r.getDisplayId()
                    + " to main display for VR");
            mRootActivityContainer.moveStackToDisplay(
                    r.getStackId(), DEFAULT_DISPLAY, true /* toTop */);
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
                    // then remove the pinned stack.
                    mRootActivityContainer.removeStacksInWindowingModes(WINDOWING_MODE_PINNED);
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

    ActivityStack getTopDisplayFocusedStack() {
        return mRootActivityContainer.getTopDisplayFocusedStack();
    }

    /** Pokes the task persister. */
    void notifyTaskPersisterLocked(TaskRecord task, boolean flush) {
        mRecentTasks.notifyTaskPersisterLocked(task, flush);
    }

    boolean isKeyguardLocked() {
        return mKeyguardController.isKeyguardLocked();
    }

    /**
     * Clears launch params for the given package.
     * @param packageNames the names of the packages of which the launch params are to be cleared
     */
    @Override
    public void clearLaunchParamsForPackages(List<String> packageNames) {
        mAmInternal.enforceCallingPermission(Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "clearLaunchParamsForPackages");
        synchronized (mGlobalLock) {
            for (int i = 0; i < packageNames.size(); ++i) {
                mStackSupervisor.mLaunchParamsPersister.removeRecordForPackage(packageNames.get(i));
            }
        }
    }

    /**
     * Makes the display with the given id a single task instance display. I.e the display can only
     * contain one task.
     */
    @Override
    public void setDisplayToSingleTaskInstance(int displayId) {
        mAmInternal.enforceCallingPermission(Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "setDisplayToSingleTaskInstance");
        final long origId = Binder.clearCallingIdentity();
        try {
            final ActivityDisplay display =
                    mRootActivityContainer.getActivityDisplayOrCreate(displayId);
            if (display != null) {
                display.setDisplayToSingleTaskInstance();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage,
                "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)");
    }

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage, String header) {
        pw.println(header);

        boolean printedAnything = mRootActivityContainer.dumpActivities(fd, pw, dumpAll, dumpClient,
                dumpPackage);
        boolean needSep = printedAnything;

        boolean printed = ActivityStackSupervisor.printThisActivity(pw,
                mRootActivityContainer.getTopResumedActivity(),  dumpPackage, needSep,
                "  ResumedActivity: ");
        if (printed) {
            printedAnything = true;
            needSep = false;
        }

        if (dumpPackage == null) {
            if (needSep) {
                pw.println();
            }
            printedAnything = true;
            mStackSupervisor.dump(pw, "  ");
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpActivityContainersLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER STARTER (dumpsys activity containers)");
        mRootActivityContainer.dumpChildrenNames(pw, " ");
        pw.println(" ");
    }

    void dumpActivityStarterLocked(PrintWriter pw, String dumpPackage) {
        pw.println("ACTIVITY MANAGER STARTER (dumpsys activity starter)");
        getActivityStartController().dump(pw, "", dumpPackage);
    }

    /**
     * There are three things that cmd can be:
     *  - a flattened component name that matches an existing activity
     *  - the cmd arg isn't the flattened component name of an existing activity:
     *    dump all activity whose component contains the cmd as a substring
     *  - A hex number of the ActivityRecord object instance.
     * <p>
     * The caller should not hold lock when calling this method because it will wait for the
     * activities to complete the dump.
     *
     *  @param dumpVisibleStacksOnly dump activity with {@param name} only if in a visible stack
     *  @param dumpFocusedStackOnly dump activity with {@param name} only if in the focused stack
     */
    protected boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll, boolean dumpVisibleStacksOnly, boolean dumpFocusedStackOnly) {
        ArrayList<ActivityRecord> activities;

        synchronized (mGlobalLock) {
            activities = mRootActivityContainer.getDumpActivities(name, dumpVisibleStacksOnly,
                    dumpFocusedStackOnly);
        }

        if (activities.size() <= 0) {
            return false;
        }

        String[] newArgs = new String[args.length - opti];
        System.arraycopy(args, opti, newArgs, 0, args.length - opti);

        TaskRecord lastTask = null;
        boolean needSep = false;
        for (int i = activities.size() - 1; i >= 0; i--) {
            ActivityRecord r = activities.get(i);
            if (needSep) {
                pw.println();
            }
            needSep = true;
            synchronized (mGlobalLock) {
                final TaskRecord task = r.getTaskRecord();
                if (lastTask != task) {
                    lastTask = task;
                    pw.print("TASK "); pw.print(lastTask.affinity);
                    pw.print(" id="); pw.print(lastTask.taskId);
                    pw.print(" userId="); pw.println(lastTask.userId);
                    if (dumpAll) {
                        lastTask.dump(pw, "  ");
                    }
                }
            }
            dumpActivity("  ", fd, pw, activities.get(i), newArgs, dumpAll);
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpActivity() on the thread of the specified activity if
     * there is a thread associated with the activity.
     */
    private void dumpActivity(String prefix, FileDescriptor fd, PrintWriter pw,
            final ActivityRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        IApplicationThread appThread = null;
        synchronized (mGlobalLock) {
            pw.print(prefix); pw.print("ACTIVITY "); pw.print(r.shortComponentName);
            pw.print(" "); pw.print(Integer.toHexString(System.identityHashCode(r)));
            pw.print(" pid=");
            if (r.hasProcess()) {
                pw.println(r.app.getPid());
                appThread = r.app.getThread();
            } else {
                pw.println("(not running)");
            }
            if (dumpAll) {
                r.dump(pw, innerPrefix);
            }
        }
        if (appThread != null) {
            // flush anything that is already in the PrintWriter since the thread is going
            // to write to the file descriptor directly
            pw.flush();
            try (TransferPipe tp = new TransferPipe()) {
                appThread.dumpActivity(tp.getWriteFd(), r.appToken, innerPrefix, args);
                tp.go(fd);
            } catch (IOException e) {
                pw.println(innerPrefix + "Failure while dumping the activity: " + e);
            } catch (RemoteException e) {
                pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
            }
        }
    }

    private void writeSleepStateToProto(ProtoOutputStream proto, int wakeFullness,
            boolean testPssMode) {
        final long sleepToken = proto.start(ActivityManagerServiceDumpProcessesProto.SLEEP_STATUS);
        proto.write(ActivityManagerServiceDumpProcessesProto.SleepStatus.WAKEFULNESS,
                PowerManagerInternal.wakefulnessToProtoEnum(wakeFullness));
        for (ActivityTaskManagerInternal.SleepToken st : mRootActivityContainer.mSleepTokens) {
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

    private void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    public Configuration getConfiguration() {
        Configuration ci;
        synchronized(mGlobalLock) {
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
        return mRootActivityContainer.getConfiguration();
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

        if (mWindowManager != null) {
            mWindowManager.deferSurfaceLayout();
        }
        try {
            if (values != null) {
                changes = updateGlobalConfigurationLocked(values, initLocale, persistent, userId,
                        deferResume);
            }

            kept = ensureConfigAndVisibilityAfterUpdate(starting, changes);
        } finally {
            if (mWindowManager != null) {
                mWindowManager.continueSurfaceLayout();
            }
        }

        if (result != null) {
            result.changes = changes;
            result.activityRelaunched = !kept;
        }
        return kept;
    }

    /** Update default (global) configuration and notify listeners about changes. */
    private int updateGlobalConfigurationLocked(@NonNull Configuration values, boolean initLocale,
            boolean persistent, int userId, boolean deferResume) {
        mTempConfig.setTo(getGlobalConfiguration());
        final int changes = mTempConfig.updateFrom(values);
        if (changes == 0) {
            // Since calling to Activity.setRequestedOrientation leads to freezing the window with
            // setting WindowManagerService.mWaitingForConfig to true, it is important that we call
            // performDisplayOverrideConfigUpdate in order to send the new display configuration
            // (even if there are no actual changes) to unfreeze the window.
            performDisplayOverrideConfigUpdate(values, deferResume, DEFAULT_DISPLAY);
            return 0;
        }

        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.i(TAG_CONFIGURATION,
                "Updating global configuration to: " + values);

        EventLog.writeEvent(EventLogTags.CONFIGURATION_CHANGED, changes);
        StatsLog.write(StatsLog.RESOURCE_CONFIGURATION_CHANGED,
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

            final Message m = PooledLambda.obtainMessage(
                    ActivityTaskManagerService::sendLocaleToMountDaemonMsg, this,
                    locales.get(bestLocaleIndex));
            mH.sendMessage(m);
        }

        mTempConfig.seq = increaseConfigurationSeqLocked();

        // Update stored global config and notify everyone about the change.
        mRootActivityContainer.onConfigurationChanged(mTempConfig);

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

        // We need another copy of global config because we're scheduling some calls instead of
        // running them in place. We need to be sure that object we send will be handled unchanged.
        final Configuration configCopy = new Configuration(mTempConfig);
        if (persistent && Settings.System.hasInterestingConfigurationChanges(changes)) {
            final Message msg = PooledLambda.obtainMessage(
                    ActivityTaskManagerService::sendPutConfigurationForUserMsg,
                    this, userId, configCopy);
            mH.sendMessage(msg);
        }

        SparseArray<WindowProcessController> pidMap = mProcessMap.getPidMap();
        for (int i = pidMap.size() - 1; i >= 0; i--) {
            final int pid = pidMap.keyAt(i);
            final WindowProcessController app = pidMap.get(pid);
            if (DEBUG_CONFIGURATION) {
                Slog.v(TAG_CONFIGURATION, "Update process config of "
                        + app.mName + " to new config " + configCopy);
            }
            app.onConfigurationChanged(configCopy);
        }

        final Message msg = PooledLambda.obtainMessage(
                ActivityManagerInternal::broadcastGlobalConfigurationChanged,
                mAmInternal, changes, initLocale);
        mH.sendMessage(msg);

        // Override configuration of the default display duplicates global config, so we need to
        // update it also. This will also notify WindowManager about changes.
        performDisplayOverrideConfigUpdate(mRootActivityContainer.getConfiguration(), deferResume,
                DEFAULT_DISPLAY);

        return changes;
    }

    boolean updateDisplayOverrideConfigurationLocked(Configuration values, ActivityRecord starting,
            boolean deferResume, int displayId) {
        return updateDisplayOverrideConfigurationLocked(values, starting, deferResume /* deferResume */,
                displayId, null /* result */);
    }

    /**
     * Updates override configuration specific for the selected display. If no config is provided,
     * new one will be computed in WM based on current display info.
     */
    boolean updateDisplayOverrideConfigurationLocked(Configuration values,
            ActivityRecord starting, boolean deferResume, int displayId,
            ActivityTaskManagerService.UpdateConfigurationResult result) {
        int changes = 0;
        boolean kept = true;

        if (mWindowManager != null) {
            mWindowManager.deferSurfaceLayout();
        }
        try {
            if (values != null) {
                if (displayId == DEFAULT_DISPLAY) {
                    // Override configuration of the default display duplicates global config, so
                    // we're calling global config update instead for default display. It will also
                    // apply the correct override config.
                    changes = updateGlobalConfigurationLocked(values, false /* initLocale */,
                            false /* persistent */, UserHandle.USER_NULL /* userId */, deferResume);
                } else {
                    changes = performDisplayOverrideConfigUpdate(values, deferResume, displayId);
                }
            }

            kept = ensureConfigAndVisibilityAfterUpdate(starting, changes);
        } finally {
            if (mWindowManager != null) {
                mWindowManager.continueSurfaceLayout();
            }
        }

        if (result != null) {
            result.changes = changes;
            result.activityRelaunched = !kept;
        }
        return kept;
    }

    private int performDisplayOverrideConfigUpdate(Configuration values, boolean deferResume,
            int displayId) {
        mTempConfig.setTo(mRootActivityContainer.getDisplayOverrideConfiguration(displayId));
        final int changes = mTempConfig.updateFrom(values);
        if (changes != 0) {
            Slog.i(TAG, "Override config changes=" + Integer.toHexString(changes) + " "
                    + mTempConfig + " for displayId=" + displayId);
            mRootActivityContainer.setDisplayOverrideConfiguration(mTempConfig, displayId);

            final boolean isDensityChange = (changes & ActivityInfo.CONFIG_DENSITY) != 0;
            if (isDensityChange && displayId == DEFAULT_DISPLAY) {
                mAppWarnings.onDensityChanged();

                // Post message to start process to avoid possible deadlock of calling into AMS with
                // the ATMS lock held.
                final Message msg = PooledLambda.obtainMessage(
                        ActivityManagerInternal::killAllBackgroundProcessesExcept, mAmInternal,
                        N, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
                mH.sendMessage(msg);
            }
        }
        return changes;
    }

    private void updateEventDispatchingLocked(boolean booted) {
        mWindowManager.setEventDispatching(booted && !mShuttingDown);
    }

    private void sendPutConfigurationForUserMsg(int userId, Configuration config) {
        final ContentResolver resolver = mContext.getContentResolver();
        Settings.System.putConfigurationForUser(resolver, config, userId);
    }

    private void sendLocaleToMountDaemonMsg(Locale l) {
        try {
            IBinder service = ServiceManager.getService("mount");
            IStorageManager storageManager = IStorageManager.Stub.asInterface(service);
            Log.d(TAG, "Storing locale " + l.toLanguageTag() + " for decryption UI");
            storageManager.setField(StorageManager.SYSTEM_LOCALE_KEY, l.toLanguageTag());
        } catch (RemoteException e) {
            Log.e(TAG, "Error storing locale for decryption UI", e);
        }
    }

    private void expireStartAsCallerTokenMsg(IBinder permissionToken) {
        mStartActivitySources.remove(permissionToken);
        mExpiredStartAsCallerTokens.add(permissionToken);
    }

    private void forgetStartAsCallerTokenMsg(IBinder permissionToken) {
        mExpiredStartAsCallerTokens.remove(permissionToken);
    }

    boolean isActivityStartsLoggingEnabled() {
        return mAmInternal.isActivityStartsLoggingEnabled();
    }

    boolean isBackgroundActivityStartsEnabled() {
        return mAmInternal.isBackgroundActivityStartsEnabled();
    }

    void enableScreenAfterBoot(boolean booted) {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN,
                SystemClock.uptimeMillis());
        mWindowManager.enableScreenAfterBoot();

        synchronized (mGlobalLock) {
            updateEventDispatchingLocked(booted);
        }
    }

    static long getInputDispatchingTimeoutLocked(ActivityRecord r) {
        if (r == null || !r.hasProcess()) {
            return KEY_DISPATCHING_TIMEOUT_MS;
        }
        return getInputDispatchingTimeoutLocked(r.app);
    }

    private static long getInputDispatchingTimeoutLocked(WindowProcessController r) {
        return r != null ? r.getInputDispatchingTimeout() : KEY_DISPATCHING_TIMEOUT_MS;
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
        int modeType = config.uiMode & Configuration.UI_MODE_TYPE_MASK;
        final boolean uiModeSupportsDialogs = (modeType != Configuration.UI_MODE_TYPE_CAR
                && !(modeType == Configuration.UI_MODE_TYPE_WATCH && Build.IS_USER)
                && modeType != Configuration.UI_MODE_TYPE_TELEVISION
                && modeType != Configuration.UI_MODE_TYPE_VR_HEADSET);
        final boolean hideDialogsSet = Settings.Global.getInt(mContext.getContentResolver(),
                HIDE_ERROR_DIALOGS, 0) != 0;
        mShowDialogs = inputMethodExists && uiModeSupportsDialogs && !hideDialogsSet;
    }

    private void updateFontScaleIfNeeded(@UserIdInt int userId) {
        final float scaleFactor = Settings.System.getFloatForUser(mContext.getContentResolver(),
                FONT_SCALE, 1.0f, userId);

        synchronized (this) {
            if (getGlobalConfiguration().fontScale == scaleFactor) {
                return;
            }

            final Configuration configuration
                    = mWindowManager.computeNewConfiguration(DEFAULT_DISPLAY);
            configuration.fontScale = scaleFactor;
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
    void setResumedActivityUncheckLocked(ActivityRecord r, String reason) {
        final TaskRecord task = r.getTaskRecord();
        if (task.isActivityTypeStandard()) {
            if (mCurAppTimeTracker != r.appTimeTracker) {
                // We are switching app tracking.  Complete the current one.
                if (mCurAppTimeTracker != null) {
                    mCurAppTimeTracker.stop();
                    mH.obtainMessage(
                            REPORT_TIME_TRACKER_MSG, mCurAppTimeTracker).sendToTarget();
                    mRootActivityContainer.clearOtherAppTimeTrackers(r.appTimeTracker);
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

                final TaskRecord lastResumedActivityTask = mLastResumedActivity.getTaskRecord();
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
        updateResumedAppTrace(r);
        mLastResumedActivity = r;

        r.getDisplay().setFocusedApp(r, true);

        applyUpdateLockStateLocked(r);
        applyUpdateVrModeLocked(r);

        EventLogTags.writeAmSetResumedActivity(
                r == null ? -1 : r.mUserId,
                r == null ? "NULL" : r.shortComponentName,
                reason);
    }

    ActivityTaskManagerInternal.SleepToken acquireSleepToken(String tag, int displayId) {
        synchronized (mGlobalLock) {
            final ActivityTaskManagerInternal.SleepToken token =
                    mRootActivityContainer.createSleepToken(tag, displayId);
            updateSleepIfNeededLocked();
            return token;
        }
    }

    void updateSleepIfNeededLocked() {
        final boolean shouldSleep = !mRootActivityContainer.hasAwakeDisplay();
        final boolean wasSleeping = mSleeping;
        boolean updateOomAdj = false;

        if (!shouldSleep) {
            // If wasSleeping is true, we need to wake up activity manager state from when
            // we started sleeping. In either case, we need to apply the sleep tokens, which
            // will wake up stacks or put them to sleep as appropriate.
            if (wasSleeping) {
                mSleeping = false;
                StatsLog.write(StatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED,
                        StatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED__STATE__AWAKE);
                startTimeTrackingFocusedActivityLocked();
                mTopProcessState = ActivityManager.PROCESS_STATE_TOP;
                Slog.d(TAG, "Top Process State changed to PROCESS_STATE_TOP");
                mStackSupervisor.comeOutOfSleepIfNeededLocked();
            }
            mRootActivityContainer.applySleepTokens(true /* applyToStacks */);
            if (wasSleeping) {
                updateOomAdj = true;
            }
        } else if (!mSleeping && shouldSleep) {
            mSleeping = true;
            StatsLog.write(StatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED,
                    StatsLog.ACTIVITY_MANAGER_SLEEP_STATE_CHANGED__STATE__ASLEEP);
            if (mCurAppTimeTracker != null) {
                mCurAppTimeTracker.stop();
            }
            mTopProcessState = ActivityManager.PROCESS_STATE_TOP_SLEEPING;
            Slog.d(TAG, "Top Process State changed to PROCESS_STATE_TOP_SLEEPING");
            mStackSupervisor.goingToSleepLocked();
            updateResumedAppTrace(null /* resumed */);
            updateOomAdj = true;
        }
        if (updateOomAdj) {
            mH.post(mAmInternal::updateOomAdj);
        }
    }

    void updateOomAdj() {
        mH.post(mAmInternal::updateOomAdj);
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

    void updateActivityUsageStats(ActivityRecord activity, int event) {
        ComponentName taskRoot = null;
        final TaskRecord task = activity.getTaskRecord();
        if (task != null) {
            final ActivityRecord rootActivity = task.getRootActivity();
            if (rootActivity != null) {
                taskRoot = rootActivity.mActivityComponent;
            }
        }

        final Message m = PooledLambda.obtainMessage(
                ActivityManagerInternal::updateActivityUsageStats, mAmInternal,
                activity.mActivityComponent, activity.mUserId, event, activity.appToken, taskRoot);
        mH.sendMessage(m);
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
            inm.cancelNotificationWithTag("android", null,
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
                                    intent, PendingIntent.FLAG_CANCEL_CURRENT, null,
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

    IIntentSender getIntentSenderLocked(int type, String packageName, int callingUid, int userId,
            IBinder token, String resultWho, int requestCode, Intent[] intents,
            String[] resolvedTypes, int flags, Bundle bOptions) {

        ActivityRecord activity = null;
        if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
            activity = ActivityRecord.isInStackLocked(token);
            if (activity == null) {
                Slog.w(TAG, "Failed createPendingResult: activity " + token + " not in any stack");
                return null;
            }
            if (activity.finishing) {
                Slog.w(TAG, "Failed createPendingResult: activity " + activity + " is finishing");
                return null;
            }
        }

        final PendingIntentRecord rec = mPendingIntentController.getIntentSender(type, packageName,
                callingUid, userId, token, resultWho, requestCode, intents, resolvedTypes, flags,
                bOptions);
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
        final ActivityRecord resumedActivity = mRootActivityContainer.getTopResumedActivity();
        if (!mSleeping && mCurAppTimeTracker != null && resumedActivity != null) {
            mCurAppTimeTracker.start(resumedActivity.packageName);
        }
    }

    private void updateResumedAppTrace(@Nullable ActivityRecord resumed) {
        if (mTracedResumedActivity != null) {
            Trace.asyncTraceEnd(TRACE_TAG_ACTIVITY_MANAGER,
                    constructResumedTraceName(mTracedResumedActivity.packageName), 0);
        }
        if (resumed != null) {
            Trace.asyncTraceBegin(TRACE_TAG_ACTIVITY_MANAGER,
                    constructResumedTraceName(resumed.packageName), 0);
        }
        mTracedResumedActivity = resumed;
    }

    private String constructResumedTraceName(String packageName) {
        return "focused app: " + packageName;
    }

    /** Applies latest configuration and/or visibility updates if needed. */
    private boolean ensureConfigAndVisibilityAfterUpdate(ActivityRecord starting, int changes) {
        boolean kept = true;
        final ActivityStack mainStack = mRootActivityContainer.getTopDisplayFocusedStack();
        // mainStack is null during startup.
        if (mainStack != null) {
            if (changes != 0 && starting == null) {
                // If the configuration changed, and the caller is not already
                // in the process of starting an activity, then find the top
                // activity to check if its configuration needs to change.
                starting = mainStack.topRunningActivityLocked();
            }

            if (starting != null) {
                kept = starting.ensureActivityConfiguration(changes,
                        false /* preserveWindow */);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                mRootActivityContainer.ensureActivitiesVisible(starting, changes,
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

    PermissionPolicyInternal getPermissionPolicyInternal() {
        if (mPermissionPolicyInternal == null) {
            mPermissionPolicyInternal = LocalServices.getService(PermissionPolicyInternal.class);
        }
        return mPermissionPolicyInternal;
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
     * @param preferredPackage Specify a preferred package name, otherwise use secondary home
     *                        component defined in config_secondaryHomeComponent.
     * @return the intent set with {@link Intent#CATEGORY_SECONDARY_HOME}
     */
    Intent getSecondaryHomeIntent(String preferredPackage) {
        final Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
        final boolean useSystemProvidedLauncher = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useSystemProvidedLauncherForSecondary);
        if (preferredPackage == null || useSystemProvidedLauncher) {
            // Using the component stored in config if no package name or forced.
            final String secondaryHomeComponent = mContext.getResources().getString(
                    com.android.internal.R.string.config_secondaryHomeComponent);
            intent.setComponent(ComponentName.unflattenFromString(secondaryHomeComponent));
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
        for (int i = pmap.size()-1; i >= 0; i--) {
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

    WindowProcessController getProcessController(int pid, int uid) {
        final WindowProcessController proc = mProcessMap.getProcess(pid);
        if (proc == null) return null;
        if (UserHandle.isApp(uid) && proc.mUid == uid) {
            return proc;
        }
        return null;
    }

    int getUidState(int uid) {
        return mActiveUids.getUidState(uid);
    }

    boolean isUidForeground(int uid) {
        // A uid is considered to be foreground if it has a visible non-toast window.
        return mWindowManager.mRoot.isAnyNonToastWindowVisibleForUid(uid);
    }

    boolean isDeviceOwner(int uid) {
        return uid >= 0 && mDeviceOwnerUid == uid;
    }

    void setDeviceOwnerUid(int uid) {
        mDeviceOwnerUid = uid;
    }

    /**
     * @return whitelist tag for a uid from mPendingTempWhitelist, null if not currently on
     * the whitelist
     */
    String getPendingTempWhitelistTagForUidLocked(int uid) {
        return mPendingTempWhitelist.get(uid);
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
                Time tobj = new Time();
                tobj.set(System.currentTimeMillis());
                sb.append(tobj.format("%Y-%m-%d %H:%M:%S"));
                sb.append(": ");
                TimeUtils.formatDuration(SystemClock.uptimeMillis()-startTime, sb);
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
                dumpStackTraces(tracesFile.getAbsolutePath(), firstPids, null, null);
            }

            File lastTracesFile = null;
            File curTracesFile = null;
            for (int i=9; i>=0; i--) {
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

    final class H extends Handler {
        static final int REPORT_TIME_TRACKER_MSG = 1;


        static final int FIRST_ACTIVITY_STACK_MSG = 100;
        static final int FIRST_SUPERVISOR_STACK_MSG = 200;

        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REPORT_TIME_TRACKER_MSG: {
                    AppTimeTracker tracker = (AppTimeTracker) msg.obj;
                    tracker.deliverResult(mContext);
                } break;
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
        public SleepToken acquireSleepToken(String tag, int displayId) {
            Preconditions.checkNotNull(tag);
            return ActivityTaskManagerService.this.acquireSleepToken(tag, displayId);
        }

        @Override
        public ComponentName getHomeActivityForUser(int userId) {
            synchronized (mGlobalLock) {
                final ActivityRecord homeActivity =
                        mRootActivityContainer.getDefaultDisplayHomeActivityForUser(userId);
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
        public void notifyAppTransitionStarting(SparseIntArray reasons,
                long timestamp) {
            synchronized (mGlobalLock) {
                mStackSupervisor.getActivityMetricsLogger().notifyTransitionStarting(
                        reasons, timestamp);
            }
        }

        @Override
        public void notifySingleTaskDisplayDrawn(int displayId) {
            mTaskChangeNotificationController.notifySingleTaskDisplayDrawn(displayId);
        }

        @Override
        public void notifyAppTransitionFinished() {
            synchronized (mGlobalLock) {
                mStackSupervisor.notifyAppTransitionDone();
            }
        }

        @Override
        public void notifyAppTransitionCancelled() {
            synchronized (mGlobalLock) {
                mStackSupervisor.notifyAppTransitionDone();
            }
        }

        @Override
        public List<IBinder> getTopVisibleActivities() {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.getTopVisibleActivities();
            }
        }

        @Override
        public void notifyDockedStackMinimizedChanged(boolean minimized) {
            synchronized (mGlobalLock) {
                mRootActivityContainer.setDockedStackMinimized(minimized);
            }
        }

        @Override
        public int startActivitiesAsPackage(String packageName, int userId, Intent[] intents,
                Bundle bOptions) {
            Preconditions.checkNotNull(intents, "intents");
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
                    packageUid, packageName,
                    intents, resolvedTypes, null /* resultTo */,
                    SafeActivityOptions.fromBundle(bOptions), userId,
                    false /* validateIncomingUser */, null /* originatingPendingIntent */,
                    false /* allowBackgroundActivityStart */);
        }

        @Override
        public int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid,
                String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo,
                SafeActivityOptions options, int userId, boolean validateIncomingUser,
                PendingIntentRecord originatingPendingIntent,
                boolean allowBackgroundActivityStart) {
            synchronized (mGlobalLock) {
                return getActivityStartController().startActivitiesInPackage(uid, realCallingPid,
                        realCallingUid, callingPackage, intents, resolvedTypes, resultTo, options,
                        userId, validateIncomingUser, originatingPendingIntent,
                        allowBackgroundActivityStart);
            }
        }

        @Override
        public int startActivityInPackage(int uid, int realCallingPid, int realCallingUid,
                String callingPackage, Intent intent, String resolvedType, IBinder resultTo,
                String resultWho, int requestCode, int startFlags, SafeActivityOptions options,
                int userId, TaskRecord inTask, String reason, boolean validateIncomingUser,
                PendingIntentRecord originatingPendingIntent,
                boolean allowBackgroundActivityStart) {
            synchronized (mGlobalLock) {
                return getActivityStartController().startActivityInPackage(uid, realCallingPid,
                        realCallingUid, callingPackage, intent, resolvedType, resultTo, resultWho,
                        requestCode, startFlags, options, userId, inTask, reason,
                        validateIncomingUser, originatingPendingIntent,
                        allowBackgroundActivityStart);
            }
        }

        @Override
        public int startActivityAsUser(IApplicationThread caller, String callerPacakge,
                Intent intent, Bundle options, int userId) {
            return ActivityTaskManagerService.this.startActivityAsUser(
                    caller, callerPacakge, intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, options, userId,
                    false /*validateIncomingUser*/);
        }

        @Override
        public void notifyKeyguardFlagsChanged(@Nullable Runnable callback, int displayId) {
            synchronized (mGlobalLock) {

                // We might change the visibilities here, so prepare an empty app transition which
                // might be overridden later if we actually change visibilities.
                final ActivityDisplay activityDisplay =
                        mRootActivityContainer.getActivityDisplay(displayId);
                if (activityDisplay == null) {
                    return;
                }
                final DisplayContent dc = activityDisplay.mDisplayContent;
                final boolean wasTransitionSet =
                        dc.mAppTransition.getAppTransition() != TRANSIT_NONE;
                if (!wasTransitionSet) {
                    dc.prepareAppTransition(TRANSIT_NONE, false /* alwaysKeepCurrent */);
                }
                mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);

                // If there was a transition set already we don't want to interfere with it as we
                // might be starting it too early.
                if (!wasTransitionSet) {
                    dc.executeAppTransition();
                }
            }
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void notifyKeyguardTrustedChanged() {
            synchronized (mGlobalLock) {
                if (mKeyguardController.isKeyguardShowing(DEFAULT_DISPLAY)) {
                    mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                }
            }
        }

        /**
         * Called after virtual display Id is updated by
         * {@link com.android.server.vr.Vr2dDisplay} with a specific
         * {@param vrVr2dDisplayId}.
         */
        @Override
        public void setVr2dDisplayId(int vr2dDisplayId) {
            if (DEBUG_STACK) Slog.d(TAG, "setVr2dDisplayId called for: " + vr2dDisplayId);
            synchronized (mGlobalLock) {
                mVr2dDisplayId = vr2dDisplayId;
            }
        }

        @Override
        public void setFocusedActivity(IBinder token) {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException(
                            "setFocusedActivity: No activity record matching token=" + token);
                }
                if (r.moveFocusableActivityToTop("setFocusedActivity")) {
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
            }
        }

        @Override
        public void registerScreenObserver(ScreenObserver observer) {
            mScreenObservers.add(observer);
        }

        @Override
        public boolean isCallerRecents(int callingUid) {
            return getRecentTasks().isCallerRecents(callingUid);
        }

        @Override
        public boolean isRecentsComponentHomeActivity(int userId) {
            return getRecentTasks().isRecentsComponentHomeActivity(userId);
        }

        @Override
        public void cancelRecentsAnimation(boolean restoreHomeStackPosition) {
            ActivityTaskManagerService.this.cancelRecentsAnimation(restoreHomeStackPosition);
        }

        @Override
        public void enforceCallerIsRecentsOrHasPermission(String permission, String func) {
            ActivityTaskManagerService.this.enforceCallerIsRecentsOrHasPermission(permission, func);
        }

        @Override
        public void notifyActiveVoiceInteractionServiceChanged(ComponentName component) {
            synchronized (mGlobalLock) {
                mActiveVoiceInteractionServiceComponent = component;
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
            synchronized (mGlobalLock) {
                return ActivityTaskManagerService.this.isGetTasksAllowed(
                        caller, callingPid, callingUid);
            }
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
            synchronized (mGlobalLockWithoutBoost) {
                return mTopProcessState;
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public boolean isHeavyWeightProcess(WindowProcessController proc) {
            synchronized (mGlobalLockWithoutBoost) {
                return proc == mHeavyWeightProcess;
            }
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
            synchronized (mGlobalLockWithoutBoost) {
                return isSleepingLocked();
            }
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
                mRootActivityContainer.prepareForShutdown();
                updateEventDispatchingLocked(booted);
                notifyTaskPersisterLocked(null, true);
                return mStackSupervisor.shutdownLocked(timeout);
            }
        }

        @Override
        public void enableScreenAfterBoot(boolean booted) {
            synchronized (mGlobalLock) {
                EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN,
                        SystemClock.uptimeMillis());
                mWindowManager.enableScreenAfterBoot();
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
        public void onPackageDataCleared(String name) {
            synchronized (mGlobalLock) {
                mCompatModePackages.handlePackageDataClearedLocked(name);
                mAppWarnings.onPackageDataCleared(name);
            }
        }

        @Override
        public void onPackageUninstalled(String name) {
            synchronized (mGlobalLock) {
                mAppWarnings.onPackageUninstalled(name);
                mCompatModePackages.handlePackageUninstalledLocked(name);
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
                mRootActivityContainer.updateActivityApplicationInfo(aInfo);
            }
        }

        @Override
        public CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai) {
            synchronized (mGlobalLock) {
                return compatibilityInfoForPackageLocked(ai);
            }
        }

        /**
         * Set the corresponding display information for the process global configuration. To be
         * called when we need to show IME on a different display.
         *
         * @param pid The process id associated with the IME window.
         * @param displayId The ID of the display showing the IME.
         */
        @Override
        public void onImeWindowSetOnDisplay(final int pid, final int displayId) {
            // Don't update process-level configuration for Multi-Client IME process since other
            // IMEs on other displays will also receive this configuration change due to IME
            // services use the same application config/context.
            if (InputMethodSystemProperty.MULTI_CLIENT_IME_ENABLED) return;

            if (pid == MY_PID || pid < 0) {
                if (DEBUG_CONFIGURATION) {
                    Slog.w(TAG,
                            "Trying to update display configuration for system/invalid process.");
                }
                return;
            }
            synchronized (mGlobalLock) {
                final ActivityDisplay activityDisplay =
                        mRootActivityContainer.getActivityDisplay(displayId);
                if (activityDisplay == null) {
                    // Call might come when display is not yet added or has been removed.
                    if (DEBUG_CONFIGURATION) {
                        Slog.w(TAG, "Trying to update display configuration for non-existing "
                                + "displayId=" + displayId);
                    }
                    return;
                }
                final WindowProcessController process = mProcessMap.getProcess(pid);
                if (process == null) {
                    if (DEBUG_CONFIGURATION) {
                        Slog.w(TAG, "Trying to update display configuration for invalid "
                                + "process, pid=" + pid);
                    }
                    return;
                }
                process.registerDisplayConfigurationListenerLocked(activityDisplay);
            }
        }

        @Override
        public void sendActivityResult(int callingUid, IBinder activityToken, String resultWho,
                int requestCode, int resultCode, Intent data) {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                if (r != null && r.getActivityStack() != null) {
                    r.getActivityStack().sendActivityResultLocked(callingUid, r, resultWho,
                            requestCode, resultCode, data);
                }
            }
        }

        @Override
        public void clearPendingResultForActivity(IBinder activityToken,
                WeakReference<PendingIntentRecord> pir) {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                if (r != null && r.pendingResults != null) {
                    r.pendingResults.remove(pir);
                }
            }
        }

        @Override
        public ActivityTokens getTopActivityForTask(int taskId) {
            synchronized (mGlobalLock) {
                final TaskRecord taskRecord = mRootActivityContainer.anyTaskForId(taskId);
                if (taskRecord == null) {
                    Slog.w(TAG, "getApplicationThreadForTopActivity failed:"
                            + " Requested task not found");
                    return null;
                }
                final ActivityRecord activity = taskRecord.getTopActivity();
                if (activity == null) {
                    Slog.w(TAG, "getApplicationThreadForTopActivity failed:"
                            + " Requested activity not found");
                    return null;
                }
                if (!activity.attachedToProcess()) {
                    Slog.w(TAG, "getApplicationThreadForTopActivity failed: No process for "
                            + activity);
                    return null;
                }
                return new ActivityTokens(activity.appToken, activity.assistToken,
                        activity.app.getThread());
            }
        }

        @Override
        public IIntentSender getIntentSender(int type, String packageName,
                int callingUid, int userId, IBinder token, String resultWho,
                int requestCode, Intent[] intents, String[] resolvedTypes, int flags,
                Bundle bOptions) {
            synchronized (mGlobalLock) {
                return getIntentSenderLocked(type, packageName, callingUid, userId, token,
                        resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
            }
        }

        @Override
        public ActivityServiceConnectionsHolder getServiceConnectionsHolder(IBinder token) {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return null;
                }
                if (r.mServiceConnectionsHolder == null) {
                    r.mServiceConnectionsHolder = new ActivityServiceConnectionsHolder(
                            ActivityTaskManagerService.this, r);
                }

                return r.mServiceConnectionsHolder;
            }
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
                return mRootActivityContainer.startHomeOnDisplay(userId, reason, DEFAULT_DISPLAY);
            }
        }

        @Override
        public boolean startHomeOnDisplay(int userId, String reason, int displayId,
                boolean allowInstrumenting, boolean fromHomeKey) {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.startHomeOnDisplay(userId, reason, displayId,
                        allowInstrumenting, fromHomeKey);
            }
        }

        @Override
        public boolean startHomeOnAllDisplays(int userId, String reason) {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.startHomeOnAllDisplays(userId, reason);
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public boolean isFactoryTestProcess(WindowProcessController wpc) {
            synchronized (mGlobalLockWithoutBoost) {
                if (mFactoryTest == FACTORY_TEST_OFF) {
                    return false;
                }
                if (mFactoryTest == FACTORY_TEST_LOW_LEVEL && mTopComponent != null
                        && wpc.mName.equals(mTopComponent.getPackageName())) {
                    return true;
                }
                return mFactoryTest == FACTORY_TEST_HIGH_LEVEL
                        && (wpc.mInfo.flags & FLAG_FACTORY_TEST) != 0;
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
                // Remove this application's activities from active lists.
                boolean hasVisibleActivities = mRootActivityContainer.handleAppDied(wpc);

                wpc.clearRecentTasks();
                wpc.clearActivities();

                if (wpc.isInstrumenting()) {
                    finishInstrumentationCallback.run();
                }

                if (!restarting && hasVisibleActivities) {
                    mWindowManager.deferSurfaceLayout();
                    try {
                        if (!mRootActivityContainer.resumeFocusedStacksTopActivities()) {
                            // If there was nothing to resume, and we are not already restarting
                            // this process, but there is a visible activity that is hosted by the
                            // process...then make sure all visible activities are running, taking
                            // care of restarting this process.
                            mRootActivityContainer.ensureActivitiesVisible(null, 0,
                                    !PRESERVE_WINDOWS);
                        }
                    } finally {
                        mWindowManager.continueSurfaceLayout();
                    }
                }
            }
        }

        @Override
        public void closeSystemDialogs(String reason) {
            enforceNotIsolatedCaller("closeSystemDialogs");

            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
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

                    mRootActivityContainer.closeSystemDialogs();
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
                // Clean-up disabled activities.
                if (mRootActivityContainer.finishDisabledPackageActivities(
                        packageName, disabledClasses, true, false, userId) && booted) {
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
                    mStackSupervisor.scheduleIdleLocked();
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

                boolean didSomething =
                        getActivityStartController().clearPendingActivityLaunches(packageName);
                didSomething |= mRootActivityContainer.finishDisabledPackageActivities(packageName,
                        null, doit, evenPersistent, userId);
                return didSomething;
            }
        }

        @Override
        public void resumeTopActivities(boolean scheduleIdle) {
            synchronized (mGlobalLock) {
                mRootActivityContainer.resumeFocusedStacksTopActivities();
                if (scheduleIdle) {
                    mStackSupervisor.scheduleIdleLocked();
                }
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public void preBindApplication(WindowProcessController wpc) {
            synchronized (mGlobalLockWithoutBoost) {
                mStackSupervisor.getActivityMetricsLogger().notifyBindApplication(wpc.mInfo);
            }
        }

        @HotPath(caller = HotPath.PROCESS_CHANGE)
        @Override
        public boolean attachApplication(WindowProcessController wpc) throws RemoteException {
            synchronized (mGlobalLockWithoutBoost) {
                return mRootActivityContainer.attachApplication(wpc);
            }
        }

        @Override
        public void notifyLockedProfile(@UserIdInt int userId, int currentUserId) {
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
                        if (mKeyguardController.isKeyguardLocked()) {
                            // Showing launcher to avoid user entering credential twice.
                            startHomeActivity(currentUserId, "notifyLockedProfile");
                        }
                        mRootActivityContainer.lockAllProfileTasks(userId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void startConfirmDeviceCredentialIntent(Intent intent, Bundle options) {
            mAmInternal.enforceCallingPermission(
                    MANAGE_ACTIVITY_STACKS, "startConfirmDeviceCredentialIntent");

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
                // is ActivityManagerServiceDumpActivitiesProto
                mRootActivityContainer.writeToProto(proto,
                        ActivityManagerServiceDumpActivitiesProto.ACTIVITY_STACK_SUPERVISOR,
                        WindowTraceLogLevel.ALL);
            }
        }

        @Override
        public void saveANRState(String reason) {
            synchronized (mGlobalLock) {
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                pw.println("  ANR time: " + DateFormat.getDateTimeInstance().format(new Date()));
                if (reason != null) {
                    pw.println("  Reason: " + reason);
                }
                pw.println();
                getActivityStartController().dump(pw, "  ", null);
                pw.println();
                pw.println("-------------------------------------------------------------------------------");
                dumpActivitiesLocked(null /* fd */, pw, null /* args */, 0 /* opti */,
                        true /* dumpAll */, false /* dumpClient */, null /* dumpPackage */,
                        "" /* header */);
                pw.println();
                pw.close();

                mLastANRState = sw.toString();
            }
        }

        @Override
        public void clearSavedANRState() {
            synchronized (mGlobalLock) {
                mLastANRState = null;
            }
        }

        @Override
        public void dump(String cmd, FileDescriptor fd, PrintWriter pw, String[] args, int opti,
                boolean dumpAll, boolean dumpClient, String dumpPackage) {
            synchronized (mGlobalLock) {
                if (DUMP_ACTIVITIES_CMD.equals(cmd) || DUMP_ACTIVITIES_SHORT_CMD.equals(cmd)) {
                    dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
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
                }
            }
        }

        @Override
        public boolean dumpForProcesses(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
                String dumpPackage, int dumpAppId, boolean needSep, boolean testPssMode,
                int wakefulness) {
            synchronized (mGlobalLock) {
                if (mHomeProcess != null && (dumpPackage == null
                        || mHomeProcess.mPkgList.contains(dumpPackage))) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    pw.println("  mHomeProcess: " + mHomeProcess);
                }
                if (mPreviousProcess != null && (dumpPackage == null
                        || mPreviousProcess.mPkgList.contains(dumpPackage))) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    pw.println("  mPreviousProcess: " + mPreviousProcess);
                }
                if (dumpAll && (mPreviousProcess == null || dumpPackage == null
                        || mPreviousProcess.mPkgList.contains(dumpPackage))) {
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("  mPreviousProcessVisibleTime: ");
                    TimeUtils.formatDuration(mPreviousProcessVisibleTime, sb);
                    pw.println(sb);
                }
                if (mHeavyWeightProcess != null && (dumpPackage == null
                        || mHeavyWeightProcess.mPkgList.contains(dumpPackage))) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
                }
                if (dumpPackage == null) {
                    pw.println("  mGlobalConfiguration: " + getGlobalConfiguration());
                    mRootActivityContainer.dumpDisplayConfigs(pw, "  ");
                }
                if (dumpAll) {
                    if (dumpPackage == null) {
                        pw.println("  mConfigWillChange: "
                                + getTopDisplayFocusedStack().mConfigWillChange);
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
                    pw.println("  mSleepTokens=" + mRootActivityContainer.mSleepTokens);
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
                    pw.println("  mGoingToSleepWakeLock=" + mStackSupervisor.mGoingToSleepWakeLock);
                    pw.println("  mLaunchingActivityWakeLock="
                            + mStackSupervisor.mLaunchingActivityWakeLock);
                }

                return needSep;
            }
        }

        @Override
        public void writeProcessesToProto(ProtoOutputStream proto, String dumpPackage,
                int wakeFullness, boolean testPssMode) {
            synchronized (mGlobalLock) {
                if (dumpPackage == null) {
                    getGlobalConfiguration().writeToProto(proto, GLOBAL_CONFIGURATION);
                    proto.write(CONFIG_WILL_CHANGE, getTopDisplayFocusedStack().mConfigWillChange);
                    writeSleepStateToProto(proto, wakeFullness, testPssMode);
                    if (mRunningVoice != null) {
                        final long vrToken = proto.start(
                                ActivityManagerServiceDumpProcessesProto.RUNNING_VOICE);
                        proto.write(ActivityManagerServiceDumpProcessesProto.Voice.SESSION,
                                mRunningVoice.toString());
                        mVoiceWakeLock.writeToProto(
                                proto, ActivityManagerServiceDumpProcessesProto.Voice.WAKELOCK);
                        proto.end(vrToken);
                    }
                    mVrController.writeToProto(proto,
                            ActivityManagerServiceDumpProcessesProto.VR_CONTROLLER);
                    if (mController != null) {
                        final long token = proto.start(CONTROLLER);
                        proto.write(CONTROLLER, mController.toString());
                        proto.write(IS_A_MONKEY, mControllerIsAMonkey);
                        proto.end(token);
                    }
                    mStackSupervisor.mGoingToSleepWakeLock.writeToProto(proto, GOING_TO_SLEEP);
                    mStackSupervisor.mLaunchingActivityWakeLock.writeToProto(proto,
                            LAUNCHING_ACTIVITY);
                }

                if (mHomeProcess != null && (dumpPackage == null
                        || mHomeProcess.mPkgList.contains(dumpPackage))) {
                    mHomeProcess.writeToProto(proto, HOME_PROC);
                }

                if (mPreviousProcess != null && (dumpPackage == null
                        || mPreviousProcess.mPkgList.contains(dumpPackage))) {
                    mPreviousProcess.writeToProto(proto, PREVIOUS_PROC);
                    proto.write(PREVIOUS_PROC_VISIBLE_TIME_MS, mPreviousProcessVisibleTime);
                }

                if (mHeavyWeightProcess != null && (dumpPackage == null
                        || mHeavyWeightProcess.mPkgList.contains(dumpPackage))) {
                    mHeavyWeightProcess.writeToProto(proto, HEAVY_WEIGHT_PROC);
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
                    mCurAppTimeTracker.writeToProto(proto, CURRENT_TRACKER, true);
                }

            }
        }

        @Override
        public boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name,
                String[] args, int opti, boolean dumpAll, boolean dumpVisibleStacksOnly,
                boolean dumpFocusedStackOnly) {
            return ActivityTaskManagerService.this.dumpActivity(fd, pw, name, args, opti, dumpAll,
                    dumpVisibleStacksOnly, dumpFocusedStackOnly);
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
                return isSleeping() || mRootActivityContainer.allResumedActivitiesIdle();
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public WindowProcessController getTopApp() {
            synchronized (mGlobalLockWithoutBoost) {
                final ActivityRecord top = mRootActivityContainer.getTopResumedActivity();
                return top != null ? top.app : null;
            }
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public void rankTaskLayersIfNeeded() {
            synchronized (mGlobalLockWithoutBoost) {
                if (mRootActivityContainer != null) {
                    mRootActivityContainer.rankTaskLayersIfNeeded();
                }
            }
        }

        @Override
        public void scheduleDestroyAllActivities(String reason) {
            synchronized (mGlobalLock) {
                mRootActivityContainer.scheduleDestroyAllActivities(null, reason);
            }
        }

        @Override
        public void removeUser(int userId) {
            synchronized (mGlobalLock) {
                mRootActivityContainer.removeUser(userId);
            }
        }

        @Override
        public boolean switchUser(int userId, UserState userState) {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.switchUser(userId, userState);
            }
        }

        @Override
        public void onHandleAppCrash(WindowProcessController wpc) {
            synchronized (mGlobalLock) {
                mRootActivityContainer.handleAppCrash(wpc);
            }
        }

        @Override
        public int finishTopCrashedActivities(WindowProcessController crashedApp, String reason) {
            synchronized (mGlobalLock) {
                return mRootActivityContainer.finishTopCrashedActivities(crashedApp, reason);
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
        public void onActiveUidsCleared() {
            mActiveUids.onActiveUidsCleared();
        }

        @HotPath(caller = HotPath.OOM_ADJUSTMENT)
        @Override
        public void onUidProcStateChanged(int uid, int procState) {
            mActiveUids.onUidProcStateChanged(uid, procState);
        }

        @Override
        public void onUidAddedToPendingTempWhitelist(int uid, String tag) {
            synchronized (mGlobalLockWithoutBoost) {
                mPendingTempWhitelist.put(uid, tag);
            }
        }

        @Override
        public void onUidRemovedFromPendingTempWhitelist(int uid) {
            synchronized (mGlobalLockWithoutBoost) {
                mPendingTempWhitelist.remove(uid);
            }
        }

        @Override
        public boolean handleAppCrashInActivityController(String processName, int pid,
                String shortMsg, String longMsg, long timeMillis, String stackTrace,
                Runnable killCrashingAppCallback) {
            synchronized (mGlobalLock) {
                if (mController == null) {
                    return false;
                }

                try {
                    if (!mController.appCrashed(processName, pid, shortMsg, longMsg, timeMillis,
                            stackTrace)) {
                        killCrashingAppCallback.run();
                        return true;
                    }
                } catch (RemoteException e) {
                    mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
                return false;
            }
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
        public WindowProcessController getHomeProcess() {
            synchronized (mGlobalLock) {
                return mHomeProcess;
            }
        }

        @Override
        public WindowProcessController getPreviousProcess() {
            synchronized (mGlobalLock) {
                return mPreviousProcess;
            }
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
                return mStackSupervisor.getActivityMetricsLogger().getLaunchObserverRegistry();
            }
        }

        @Override
        public ActivityManager.TaskSnapshot getTaskSnapshotNoRestore(int taskId,
                boolean reducedResolution) {
            return ActivityTaskManagerService.this.getTaskSnapshot(taskId, reducedResolution,
                    false /* restoreFromDisk */);
        }

        @Override
        public boolean isUidForeground(int uid) {
            synchronized (mGlobalLock) {
                return ActivityTaskManagerService.this.isUidForeground(uid);
            }
        }

        @Override
        public void setDeviceOwnerUid(int uid) {
            synchronized (mGlobalLock) {
                ActivityTaskManagerService.this.setDeviceOwnerUid(uid);
            }
        }

        @Override
        public void setCompanionAppPackages(int userId, Set<String> companionAppPackages) {
            // Translate package names into UIDs
            final Set<Integer> result = new HashSet<>();
            for (String pkg : companionAppPackages) {
                final int uid = getPackageManagerInternalLocked().getPackageUid(pkg, 0, userId);
                if (uid >= 0) {
                    result.add(uid);
                }
            }
            synchronized (mGlobalLock) {
                mCompanionAppUidsMap.put(userId, result);
            }
        }
    }
}
