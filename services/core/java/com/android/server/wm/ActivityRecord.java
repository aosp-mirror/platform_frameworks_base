/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.TaskDescription.ATTR_TASKDESCRIPTION_PREFIX;
import static android.app.ActivityOptions.ANIM_CLIP_REVEAL;
import static android.app.ActivityOptions.ANIM_CUSTOM;
import static android.app.ActivityOptions.ANIM_NONE;
import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.ActivityOptions.ANIM_REMOTE_ANIMATION;
import static android.app.ActivityOptions.ANIM_SCALE_UP;
import static android.app.ActivityOptions.ANIM_SCENE_TRANSITION;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_ASPECT_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_ASPECT_SCALE_UP;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_UP;
import static android.app.ActivityOptions.ANIM_UNDEFINED;
import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_PICTURE_IN_PICTURE;
import static android.app.WaitResult.INVALID_DELAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.CATEGORY_HOME;
import static android.content.Intent.CATEGORY_LAUNCHER;
import static android.content.Intent.CATEGORY_SECONDARY_HOME;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NO_HISTORY;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
import static android.content.pm.ActivityInfo.FLAG_IMMERSIVE;
import static android.content.pm.ActivityInfo.FLAG_INHERIT_SHOW_WHEN_LOCKED;
import static android.content.pm.ActivityInfo.FLAG_MULTIPROCESS;
import static android.content.pm.ActivityInfo.FLAG_NO_HISTORY;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
import static android.content.pm.ActivityInfo.FLAG_STATE_NOT_NEEDED;
import static android.content.pm.ActivityInfo.FLAG_TURN_SCREEN_ON;
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.content.pm.ActivityInfo.PERSIST_ACROSS_REBOOTS;
import static android.content.pm.ActivityInfo.PERSIST_ROOT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.isFixedOrientationPortrait;
import static android.content.res.Configuration.EMPTY;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.content.res.Configuration.UI_MODE_TYPE_VR_HEADSET;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Process.SYSTEM_UID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;

import static com.android.server.am.ActivityRecordProto.CONFIGURATION_CONTAINER;
import static com.android.server.am.ActivityRecordProto.FRONT_OF_TASK;
import static com.android.server.am.ActivityRecordProto.IDENTIFIER;
import static com.android.server.am.ActivityRecordProto.PROC_ID;
import static com.android.server.am.ActivityRecordProto.STATE;
import static com.android.server.am.ActivityRecordProto.TRANSLUCENT;
import static com.android.server.am.ActivityRecordProto.VISIBLE;
import static com.android.server.am.EventLogTags.AM_RELAUNCH_ACTIVITY;
import static com.android.server.am.EventLogTags.AM_RELAUNCH_RESUME_ACTIVITY;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.wm.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.wm.ActivityStack.ActivityState.FINISHING;
import static com.android.server.wm.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESTARTING_PROCESS;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STARTED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONTAINERS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SAVED_STATE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONTAINERS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SAVED_STATE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.TaskPersister.DEBUG;
import static com.android.server.wm.TaskPersister.IMAGE_EXTENSION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ResultInfo;
import android.app.WaitResult.LaunchState;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.DestroyActivityItem;
import android.app.servertransaction.MoveToDisplayItem;
import android.app.servertransaction.MultiWindowModeChangeItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.PipModeChangeItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.app.servertransaction.TopResumedActivityChangeItem;
import android.app.servertransaction.WindowVisibilityItem;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.AppTransitionAnimationSpec;
import android.view.DisplayCutout;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IApplicationToken;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager.LayoutParams;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.util.XmlUtils;
import com.android.server.AttributeCache;
import com.android.server.AttributeCache.Entry;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentRecord;
import com.android.server.uri.UriPermissionOwner;
import com.android.server.wm.ActivityMetricsLogger.WindowingModeTransitionInfoSnapshot;
import com.android.server.wm.ActivityStack.ActivityState;

import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * An entry in the history stack, representing an activity.
 */
final class ActivityRecord extends ConfigurationContainer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityRecord" : TAG_ATM;
    private static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_APP = TAG + POSTFIX_APP;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_CONTAINERS = TAG + POSTFIX_CONTAINERS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_SAVED_STATE = TAG + POSTFIX_SAVED_STATE;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    private static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    private static final String ATTR_ID = "id";
    private static final String TAG_INTENT = "intent";
    private static final String ATTR_USERID = "user_id";
    private static final String TAG_PERSISTABLEBUNDLE = "persistable_bundle";
    private static final String ATTR_LAUNCHEDFROMUID = "launched_from_uid";
    private static final String ATTR_LAUNCHEDFROMPACKAGE = "launched_from_package";
    private static final String ATTR_RESOLVEDTYPE = "resolved_type";
    private static final String ATTR_COMPONENTSPECIFIED = "component_specified";
    static final String ACTIVITY_ICON_SUFFIX = "_activity_icon_";

    // How many activities have to be scheduled to stop to force a stop pass.
    private static final int MAX_STOPPING_TO_FORCE = 3;

    final ActivityTaskManagerService mAtmService; // owner
    final IApplicationToken.Stub appToken; // window manager token
    // TODO: Remove after unification
    AppWindowToken mAppWindowToken;

    final ActivityInfo info; // activity info provided by developer in AndroidManifest
    final int launchedFromPid; // always the pid who started the activity.
    final int launchedFromUid; // always the uid who started the activity.
    final String launchedFromPackage; // always the package who started the activity.
    final int mUserId;          // Which user is this running for?
    final Intent intent;    // the original intent that generated us
    final ComponentName mActivityComponent;  // the intent component, or target of an alias.
    final String shortComponentName; // the short component name of the intent
    final String resolvedType; // as per original caller;
    final String packageName; // the package implementing intent's component
    final String processName; // process where this component wants to run
    final String taskAffinity; // as per ActivityInfo.taskAffinity
    final boolean stateNotNeeded; // As per ActivityInfo.flags
    boolean fullscreen; // The activity is opaque and fills the entire space of this task.
    // TODO: See if it possible to combine this with the fullscreen field.
    final boolean hasWallpaper; // Has a wallpaper window as a background.
    @VisibleForTesting
    boolean noDisplay;  // activity is not displayed?
    @VisibleForTesting
    int mHandoverLaunchDisplayId = INVALID_DISPLAY; // Handover launch display id to next activity.
    private final boolean componentSpecified;  // did caller specify an explicit component?
    final boolean rootVoiceInteraction;  // was this the root activity of a voice interaction?

    private CharSequence nonLocalizedLabel;  // the label information from the package mgr.
    private int labelRes;           // the label information from the package mgr.
    private int icon;               // resource identifier of activity's icon.
    private int logo;               // resource identifier of activity's logo.
    private int theme;              // resource identifier of activity's theme.
    private int realTheme;          // actual theme resource we will use, never 0.
    private int windowFlags;        // custom window flags for preview window.
    private TaskRecord task;        // the task this is in.
    private long createTime = System.currentTimeMillis();
    long lastVisibleTime;         // last time this activity became visible
    long cpuTimeAtResume;         // the cpu time of host process at the time of resuming activity
    long pauseTime;               // last time we started pausing the activity
    long launchTickTime;          // base time for launch tick messages
    long topResumedStateLossTime; // last time we reported top resumed state loss to an activity
    // Last configuration reported to the activity in the client process.
    private MergedConfiguration mLastReportedConfiguration;
    private int mLastReportedDisplayId;
    private boolean mLastReportedMultiWindowMode;
    private boolean mLastReportedPictureInPictureMode;
    CompatibilityInfo compat;// last used compatibility mode
    ActivityRecord resultTo; // who started this entry, so will get our reply
    final String resultWho; // additional identifier for use by resultTo.
    final int requestCode;  // code given by requester (resultTo)
    ArrayList<ResultInfo> results; // pending ActivityResult objs we have received
    HashSet<WeakReference<PendingIntentRecord>> pendingResults; // all pending intents for this act
    ArrayList<ReferrerIntent> newIntents; // any pending new intents for single-top mode
    ActivityOptions pendingOptions; // most recently given options
    ActivityOptions returningOptions; // options that are coming back via convertToTranslucent
    AppTimeTracker appTimeTracker; // set if we are tracking the time in this app/task/activity
    ActivityServiceConnectionsHolder mServiceConnectionsHolder; // Service connections.
    UriPermissionOwner uriPermissions; // current special URI access perms.
    WindowProcessController app;      // if non-null, hosting application
    private ActivityState mState;    // current state we are in
    private Bundle mIcicle;         // last saved activity state
    private PersistableBundle mPersistentState; // last persistently saved activity state
    private boolean mHaveState = true; // Indicates whether the last saved state of activity is
                                       // preserved. This starts out 'true', since the initial state
                                       // of an activity is that we have everything, and we should
                                       // never consider it lacking in state to be removed if it
                                       // dies. After an activity is launched it follows the value
                                       // of #mIcicle.
    boolean launchFailed;   // set if a launched failed, to abort on 2nd try
    boolean stopped;        // is activity pause finished?
    boolean delayedResume;  // not yet resumed because of stopped app switches?
    boolean finishing;      // activity in pending finish list?
    boolean deferRelaunchUntilPaused;   // relaunch of activity is being deferred until pause is
                                        // completed
    boolean preserveWindowOnDeferredRelaunch; // activity windows are preserved on deferred relaunch
    int configChangeFlags;  // which config values have changed
    private boolean keysPaused;     // has key dispatching been paused for it?
    int launchMode;         // the launch mode activity attribute.
    int lockTaskLaunchMode; // the lockTaskMode manifest attribute, subject to override
    boolean visible;        // does this activity's window need to be shown?
    boolean visibleIgnoringKeyguard; // is this activity visible, ignoring the fact that Keyguard
                                     // might hide this activity?
    private boolean mDeferHidingClient; // If true we told WM to defer reporting to the client
                                        // process that it is hidden.
    boolean sleeping;       // have we told the activity to sleep?
    boolean nowVisible;     // is this activity's window visible?
    boolean mDrawn;          // is this activity's window drawn?
    boolean mClientVisibilityDeferred;// was the visibility change message to client deferred?
    boolean idle;           // has the activity gone idle?
    boolean hasBeenLaunched;// has this activity ever been launched?
    boolean frozenBeforeDestroy;// has been frozen but not yet destroyed.
    boolean immersive;      // immersive mode (don't interrupt if possible)
    boolean forceNewConfig; // force re-create with new config next time
    boolean supportsEnterPipOnTaskSwitch;  // This flag is set by the system to indicate that the
        // activity can enter picture in picture while pausing (only when switching to another task)
    PictureInPictureParams pictureInPictureArgs = new PictureInPictureParams.Builder().build();
        // The PiP params used when deferring the entering of picture-in-picture.
    int launchCount;        // count of launches since last state
    long lastLaunchTime;    // time of last launch of this activity
    ComponentName requestedVrComponent; // the requested component for handling VR mode.

    String stringName;      // for caching of toString().

    private boolean inHistory;  // are we in the history stack?
    final ActivityStackSupervisor mStackSupervisor;
    final RootActivityContainer mRootActivityContainer;

    static final int STARTING_WINDOW_NOT_SHOWN = 0;
    static final int STARTING_WINDOW_SHOWN = 1;
    static final int STARTING_WINDOW_REMOVED = 2;
    int mStartingWindowState = STARTING_WINDOW_NOT_SHOWN;
    boolean mTaskOverlay = false; // Task is always on-top of other activities in the task.

    // Marking the reason why this activity is being relaunched. Mainly used to track that this
    // activity is being relaunched to fulfill a resize request due to compatibility issues, e.g. in
    // pre-NYC apps that don't have a sense of being resized.
    int mRelaunchReason = RELAUNCH_REASON_NONE;

    TaskDescription taskDescription; // the recents information for this activity
    boolean mLaunchTaskBehind; // this activity is actively being launched with
        // ActivityOptions.setLaunchTaskBehind, will be cleared once launch is completed.

    // These configurations are collected from application's resources based on size-sensitive
    // qualifiers. For example, layout-w800dp will be added to mHorizontalSizeConfigurations as 800
    // and drawable-sw400dp will be added to both as 400.
    private int[] mVerticalSizeConfigurations;
    private int[] mHorizontalSizeConfigurations;
    private int[] mSmallestSizeConfigurations;

    /**
     * The precomputed display insets for resolving configuration. It will be non-null if
     * {@link #shouldUseSizeCompatMode} returns {@code true}.
     */
    private CompatDisplayInsets mCompatDisplayInsets;

    boolean pendingVoiceInteractionStart;   // Waiting for activity-invoked voice session
    IVoiceInteractionSession voiceSession;  // Voice interaction session for this activity

    // A hint to override the window specified rotation animation, or -1
    // to use the window specified value. We use this so that
    // we can select the right animation in the cases of starting
    // windows, where the app hasn't had time to set a value
    // on the window.
    int mRotationAnimationHint = -1;

    private boolean mShowWhenLocked;
    private boolean mInheritShownWhenLocked;
    private boolean mTurnScreenOn;

    /**
     * Current sequencing integer of the configuration, for skipping old activity configurations.
     */
    private int mConfigurationSeq;

    /**
     * Temp configs used in {@link #ensureActivityConfiguration(int, boolean)}
     */
    private final Configuration mTmpConfig = new Configuration();
    private final Rect mTmpBounds = new Rect();

    // Token for targeting this activity for assist purposes.
    final Binder assistToken = new Binder();

    private static String startingWindowStateToString(int state) {
        switch (state) {
            case STARTING_WINDOW_NOT_SHOWN:
                return "STARTING_WINDOW_NOT_SHOWN";
            case STARTING_WINDOW_SHOWN:
                return "STARTING_WINDOW_SHOWN";
            case STARTING_WINDOW_REMOVED:
                return "STARTING_WINDOW_REMOVED";
            default:
                return "unknown state=" + state;
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final long now = SystemClock.uptimeMillis();
        pw.print(prefix); pw.print("packageName="); pw.print(packageName);
                pw.print(" processName="); pw.println(processName);
        pw.print(prefix); pw.print("launchedFromUid="); pw.print(launchedFromUid);
                pw.print(" launchedFromPackage="); pw.print(launchedFromPackage);
                pw.print(" userId="); pw.println(mUserId);
        pw.print(prefix); pw.print("app="); pw.println(app);
        pw.print(prefix); pw.println(intent.toInsecureStringWithClip());
        pw.print(prefix); pw.print("rootOfTask="); pw.print(isRootOfTask());
                pw.print(" task="); pw.println(task);
        pw.print(prefix); pw.print("taskAffinity="); pw.println(taskAffinity);
        pw.print(prefix); pw.print("mActivityComponent=");
                pw.println(mActivityComponent.flattenToShortString());
        if (info != null && info.applicationInfo != null) {
            final ApplicationInfo appInfo = info.applicationInfo;
            pw.print(prefix); pw.print("baseDir="); pw.println(appInfo.sourceDir);
            if (!Objects.equals(appInfo.sourceDir, appInfo.publicSourceDir)) {
                pw.print(prefix); pw.print("resDir="); pw.println(appInfo.publicSourceDir);
            }
            pw.print(prefix); pw.print("dataDir="); pw.println(appInfo.dataDir);
            if (appInfo.splitSourceDirs != null) {
                pw.print(prefix); pw.print("splitDir=");
                        pw.println(Arrays.toString(appInfo.splitSourceDirs));
            }
        }
        pw.print(prefix); pw.print("stateNotNeeded="); pw.print(stateNotNeeded);
                pw.print(" componentSpecified="); pw.print(componentSpecified);
                pw.print(" mActivityType="); pw.println(
                        activityTypeToString(getActivityType()));
        if (rootVoiceInteraction) {
            pw.print(prefix); pw.print("rootVoiceInteraction="); pw.println(rootVoiceInteraction);
        }
        pw.print(prefix); pw.print("compat="); pw.print(compat);
                pw.print(" labelRes=0x"); pw.print(Integer.toHexString(labelRes));
                pw.print(" icon=0x"); pw.print(Integer.toHexString(icon));
                pw.print(" theme=0x"); pw.println(Integer.toHexString(theme));
        pw.println(prefix + "mLastReportedConfigurations:");
        mLastReportedConfiguration.dump(pw, prefix + " ");

        pw.print(prefix); pw.print("CurrentConfiguration="); pw.println(getConfiguration());
        if (!getRequestedOverrideConfiguration().equals(EMPTY)) {
            pw.println(prefix + "RequestedOverrideConfiguration="
                    + getRequestedOverrideConfiguration());
        }
        if (!getResolvedOverrideConfiguration().equals(getRequestedOverrideConfiguration())) {
            pw.println(prefix + "ResolvedOverrideConfiguration="
                    + getResolvedOverrideConfiguration());
        }
        if (!matchParentBounds()) {
            pw.println(prefix + "bounds=" + getBounds());
        }
        if (resultTo != null || resultWho != null) {
            pw.print(prefix); pw.print("resultTo="); pw.print(resultTo);
                    pw.print(" resultWho="); pw.print(resultWho);
                    pw.print(" resultCode="); pw.println(requestCode);
        }
        if (taskDescription != null) {
            final String iconFilename = taskDescription.getIconFilename();
            if (iconFilename != null || taskDescription.getLabel() != null ||
                    taskDescription.getPrimaryColor() != 0) {
                pw.print(prefix); pw.print("taskDescription:");
                        pw.print(" label=\""); pw.print(taskDescription.getLabel());
                                pw.print("\"");
                        pw.print(" icon="); pw.print(taskDescription.getInMemoryIcon() != null
                                ? taskDescription.getInMemoryIcon().getByteCount() + " bytes"
                                : "null");
                        pw.print(" iconResource="); pw.print(taskDescription.getIconResource());
                        pw.print(" iconFilename="); pw.print(taskDescription.getIconFilename());
                        pw.print(" primaryColor=");
                        pw.println(Integer.toHexString(taskDescription.getPrimaryColor()));
                        pw.print(prefix + " backgroundColor=");
                        pw.println(Integer.toHexString(taskDescription.getBackgroundColor()));
                        pw.print(prefix + " statusBarColor=");
                        pw.println(Integer.toHexString(taskDescription.getStatusBarColor()));
                        pw.print(prefix + " navigationBarColor=");
                        pw.println(Integer.toHexString(taskDescription.getNavigationBarColor()));
            }
        }
        if (results != null) {
            pw.print(prefix); pw.print("results="); pw.println(results);
        }
        if (pendingResults != null && pendingResults.size() > 0) {
            pw.print(prefix); pw.println("Pending Results:");
            for (WeakReference<PendingIntentRecord> wpir : pendingResults) {
                PendingIntentRecord pir = wpir != null ? wpir.get() : null;
                pw.print(prefix); pw.print("  - ");
                if (pir == null) {
                    pw.println("null");
                } else {
                    pw.println(pir);
                    pir.dump(pw, prefix + "    ");
                }
            }
        }
        if (newIntents != null && newIntents.size() > 0) {
            pw.print(prefix); pw.println("Pending New Intents:");
            for (int i=0; i<newIntents.size(); i++) {
                Intent intent = newIntents.get(i);
                pw.print(prefix); pw.print("  - ");
                if (intent == null) {
                    pw.println("null");
                } else {
                    pw.println(intent.toShortString(false, true, false, true));
                }
            }
        }
        if (pendingOptions != null) {
            pw.print(prefix); pw.print("pendingOptions="); pw.println(pendingOptions);
        }
        if (appTimeTracker != null) {
            appTimeTracker.dumpWithHeader(pw, prefix, false);
        }
        if (uriPermissions != null) {
            uriPermissions.dump(pw, prefix);
        }
        pw.print(prefix); pw.print("launchFailed="); pw.print(launchFailed);
                pw.print(" launchCount="); pw.print(launchCount);
                pw.print(" lastLaunchTime=");
                if (lastLaunchTime == 0) pw.print("0");
                else TimeUtils.formatDuration(lastLaunchTime, now, pw);
                pw.println();
        pw.print(prefix); pw.print("mHaveState="); pw.print(mHaveState);
                pw.print(" mIcicle="); pw.println(mIcicle);
        pw.print(prefix); pw.print("state="); pw.print(mState);
                pw.print(" stopped="); pw.print(stopped);
                pw.print(" delayedResume="); pw.print(delayedResume);
                pw.print(" finishing="); pw.println(finishing);
        pw.print(prefix); pw.print("keysPaused="); pw.print(keysPaused);
                pw.print(" inHistory="); pw.print(inHistory);
                pw.print(" visible="); pw.print(visible);
                pw.print(" sleeping="); pw.print(sleeping);
                pw.print(" idle="); pw.print(idle);
                pw.print(" mStartingWindowState=");
                pw.println(startingWindowStateToString(mStartingWindowState));
        pw.print(prefix); pw.print("fullscreen="); pw.print(fullscreen);
                pw.print(" noDisplay="); pw.print(noDisplay);
                pw.print(" immersive="); pw.print(immersive);
                pw.print(" launchMode="); pw.println(launchMode);
        pw.print(prefix); pw.print("frozenBeforeDestroy="); pw.print(frozenBeforeDestroy);
                pw.print(" forceNewConfig="); pw.println(forceNewConfig);
        pw.print(prefix); pw.print("mActivityType=");
                pw.println(activityTypeToString(getActivityType()));
        if (requestedVrComponent != null) {
            pw.print(prefix);
            pw.print("requestedVrComponent=");
            pw.println(requestedVrComponent);
        }
        if (lastVisibleTime != 0 || nowVisible) {
            pw.print(prefix); pw.print(" nowVisible="); pw.print(nowVisible);
                    pw.print(" lastVisibleTime=");
                    if (lastVisibleTime == 0) pw.print("0");
                    else TimeUtils.formatDuration(lastVisibleTime, now, pw);
                    pw.println();
        }
        if (mDeferHidingClient) {
            pw.println(prefix + "mDeferHidingClient=" + mDeferHidingClient);
        }
        if (deferRelaunchUntilPaused || configChangeFlags != 0) {
            pw.print(prefix); pw.print("deferRelaunchUntilPaused="); pw.print(deferRelaunchUntilPaused);
                    pw.print(" configChangeFlags=");
                    pw.println(Integer.toHexString(configChangeFlags));
        }
        if (mServiceConnectionsHolder != null) {
            pw.print(prefix); pw.print("connections="); pw.println(mServiceConnectionsHolder);
        }
        if (info != null) {
            pw.println(prefix + "resizeMode=" + ActivityInfo.resizeModeToString(info.resizeMode));
            pw.println(prefix + "mLastReportedMultiWindowMode=" + mLastReportedMultiWindowMode
                    + " mLastReportedPictureInPictureMode=" + mLastReportedPictureInPictureMode);
            if (info.supportsPictureInPicture()) {
                pw.println(prefix + "supportsPictureInPicture=" + info.supportsPictureInPicture());
                pw.println(prefix + "supportsEnterPipOnTaskSwitch: "
                        + supportsEnterPipOnTaskSwitch);
            }
            if (info.maxAspectRatio != 0) {
                pw.println(prefix + "maxAspectRatio=" + info.maxAspectRatio);
            }
            if (info.minAspectRatio != 0) {
                pw.println(prefix + "minAspectRatio=" + info.minAspectRatio);
            }
        }
    }

    /** Update the saved state of an activity. */
    void setSavedState(@Nullable Bundle savedState) {
        mIcicle = savedState;
        mHaveState = mIcicle != null;
    }

    /**
     * Get the actual Bundle instance of the saved state.
     * @see #hasSavedState() for checking if the record has saved state.
     */
    @Nullable Bundle getSavedState() {
        return mIcicle;
    }

    /**
     * Check if the activity has saved state.
     * @return {@code true} if the client reported a non-empty saved state from last onStop(), or
     *         if this record was just created and the client is yet to be launched and resumed.
     */
    boolean hasSavedState() {
        return mHaveState;
    }

    /** @return The actual PersistableBundle instance of the saved persistent state. */
    @Nullable PersistableBundle getPersistentSavedState() {
        return mPersistentState;
    }

    void updateApplicationInfo(ApplicationInfo aInfo) {
        info.applicationInfo = aInfo;
    }

    private boolean crossesHorizontalSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(mHorizontalSizeConfigurations, firstDp, secondDp);
    }

    private boolean crossesVerticalSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(mVerticalSizeConfigurations, firstDp, secondDp);
    }

    private boolean crossesSmallestSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(mSmallestSizeConfigurations, firstDp, secondDp);
    }

    /**
     * The purpose of this method is to decide whether the activity needs to be relaunched upon
     * changing its size. In most cases the activities don't need to be relaunched, if the resize
     * is small, all the activity content has to do is relayout itself within new bounds. There are
     * cases however, where the activity's content would be completely changed in the new size and
     * the full relaunch is required.
     *
     * The activity will report to us vertical and horizontal thresholds after which a relaunch is
     * required. These thresholds are collected from the application resource qualifiers. For
     * example, if application has layout-w600dp resource directory, then it needs a relaunch when
     * we resize from width of 650dp to 550dp, as it crosses the 600dp threshold. However, if
     * it resizes width from 620dp to 700dp, it won't be relaunched as it stays on the same side
     * of the threshold.
     */
    private static boolean crossesSizeThreshold(int[] thresholds, int firstDp,
            int secondDp) {
        if (thresholds == null) {
            return false;
        }
        for (int i = thresholds.length - 1; i >= 0; i--) {
            final int threshold = thresholds[i];
            if ((firstDp < threshold && secondDp >= threshold)
                    || (firstDp >= threshold && secondDp < threshold)) {
                return true;
            }
        }
        return false;
    }

    void setSizeConfigurations(int[] horizontalSizeConfiguration,
            int[] verticalSizeConfigurations, int[] smallestSizeConfigurations) {
        mHorizontalSizeConfigurations = horizontalSizeConfiguration;
        mVerticalSizeConfigurations = verticalSizeConfigurations;
        mSmallestSizeConfigurations = smallestSizeConfigurations;
    }

    private void scheduleActivityMovedToDisplay(int displayId, Configuration config) {
        if (!attachedToProcess()) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.w(TAG,
                    "Can't report activity moved to display - client not running, activityRecord="
                            + this + ", displayId=" + displayId);
            return;
        }
        try {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Reporting activity moved to display" + ", activityRecord=" + this
                            + ", displayId=" + displayId + ", config=" + config);

            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    MoveToDisplayItem.obtain(displayId, config));
        } catch (RemoteException e) {
            // If process died, whatever.
        }
    }

    private void scheduleConfigurationChanged(Configuration config) {
        if (!attachedToProcess()) {
            if (DEBUG_CONFIGURATION) Slog.w(TAG,
                    "Can't report activity configuration update - client not running"
                            + ", activityRecord=" + this);
            return;
        }
        try {
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending new config to " + this + ", config: "
                    + config);

            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    ActivityConfigurationChangeItem.obtain(config));
        } catch (RemoteException e) {
            // If process died, whatever.
        }
    }

    boolean scheduleTopResumedActivityChanged(boolean onTop) {
        if (!attachedToProcess()) {
            if (DEBUG_STATES) {
                Slog.w(TAG, "Can't report activity position update - client not running"
                                + ", activityRecord=" + this);
            }
            return false;
        }
        try {
            if (DEBUG_STATES) {
                Slog.v(TAG, "Sending position change to " + this + ", onTop: " + onTop);
            }

            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    TopResumedActivityChangeItem.obtain(onTop));
        } catch (RemoteException e) {
            // If process died, whatever.
            return false;
        }
        return true;
    }

    void updateMultiWindowMode() {
        if (task == null || task.getStack() == null || !attachedToProcess()) {
            return;
        }

        if (task.getStack().deferScheduleMultiWindowModeChanged()) {
            // Don't do anything if we are currently deferring multi-window mode change.
            return;
        }

        // An activity is considered to be in multi-window mode if its task isn't fullscreen.
        final boolean inMultiWindowMode = inMultiWindowMode();
        if (inMultiWindowMode != mLastReportedMultiWindowMode) {
            mLastReportedMultiWindowMode = inMultiWindowMode;
            scheduleMultiWindowModeChanged(getConfiguration());
        }
    }

    private void scheduleMultiWindowModeChanged(Configuration overrideConfig) {
        try {
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    MultiWindowModeChangeItem.obtain(mLastReportedMultiWindowMode, overrideConfig));
        } catch (Exception e) {
            // If process died, I don't care.
        }
    }

    void updatePictureInPictureMode(Rect targetStackBounds, boolean forceUpdate) {
        if (task == null || task.getStack() == null || !attachedToProcess()) {
            return;
        }

        final boolean inPictureInPictureMode = inPinnedWindowingMode() && targetStackBounds != null;
        if (inPictureInPictureMode != mLastReportedPictureInPictureMode || forceUpdate) {
            // Picture-in-picture mode changes also trigger a multi-window mode change as well, so
            // update that here in order. Set the last reported MW state to the same as the PiP
            // state since we haven't yet actually resized the task (these callbacks need to
            // preceed the configuration change from the resiez.
            // TODO(110009072): Once we move these callbacks to the client, remove all logic related
            // to forcing the update of the picture-in-picture mode as a part of the PiP animation.
            mLastReportedPictureInPictureMode = inPictureInPictureMode;
            mLastReportedMultiWindowMode = inPictureInPictureMode;
            final Configuration newConfig = new Configuration();
            if (targetStackBounds != null && !targetStackBounds.isEmpty()) {
                newConfig.setTo(task.getRequestedOverrideConfiguration());
                Rect outBounds = newConfig.windowConfiguration.getBounds();
                task.adjustForMinimalTaskDimensions(outBounds, outBounds);
                task.computeConfigResourceOverrides(newConfig, task.getParent().getConfiguration());
            }
            schedulePictureInPictureModeChanged(newConfig);
            scheduleMultiWindowModeChanged(newConfig);
        }
    }

    private void schedulePictureInPictureModeChanged(Configuration overrideConfig) {
        try {
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    PipModeChangeItem.obtain(mLastReportedPictureInPictureMode,
                            overrideConfig));
        } catch (Exception e) {
            // If process died, no one cares.
        }
    }

    @Override
    protected int getChildCount() {
        // {@link ActivityRecord} is a leaf node and has no children.
        return 0;
    }

    @Override
    protected ConfigurationContainer getChildAt(int index) {
        return null;
    }

    @Override
    protected ConfigurationContainer getParent() {
        return getTaskRecord();
    }

    TaskRecord getTaskRecord() {
        return task;
    }

    /**
     * Sets reference to the {@link TaskRecord} the {@link ActivityRecord} will treat as its parent.
     * Note that this does not actually add the {@link ActivityRecord} as a {@link TaskRecord}
     * children. However, this method will clean up references to this {@link ActivityRecord} in
     * {@link ActivityStack}.
     * @param task The new parent {@link TaskRecord}.
     */
    void setTask(TaskRecord task) {
        setTask(task /* task */, false /* reparenting */);
    }

    /**
     * This method should only be called by {@link TaskRecord#removeActivity(ActivityRecord)}.
     * @param task          The new parent task.
     * @param reparenting   Whether we're in the middle of reparenting.
     */
    void setTask(TaskRecord task, boolean reparenting) {
        // Do nothing if the {@link TaskRecord} is the same as the current {@link getTaskRecord}.
        if (task != null && task == getTaskRecord()) {
            return;
        }

        final ActivityStack oldStack = getActivityStack();
        final ActivityStack newStack = task != null ? task.getStack() : null;

        // Inform old stack (if present) of activity removal and new stack (if set) of activity
        // addition.
        if (oldStack != newStack) {
            if (!reparenting && oldStack != null) {
                oldStack.onActivityRemovedFromStack(this);
            }

            if (newStack != null) {
                newStack.onActivityAddedToStack(this);
            }
        }

        this.task = task;

        if (!reparenting) {
            onParentChanged();
        }
    }

    /**
     * Notifies AWT that this app is waiting to pause in order to determine if it will enter PIP.
     * This information helps AWT know that the app is in the process of pausing before it gets the
     * signal on the WM side.
     */
    void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        if (mAppWindowToken == null) {
            return;
        }

        mAppWindowToken.setWillCloseOrEnterPip(willCloseOrEnterPip);
    }

    static class Token extends IApplicationToken.Stub {
        private final WeakReference<ActivityRecord> weakActivity;
        private final String name;

        Token(ActivityRecord activity, Intent intent) {
            weakActivity = new WeakReference<>(activity);
            name = intent.getComponent().flattenToShortString();
        }

        private static @Nullable ActivityRecord tokenToActivityRecordLocked(Token token) {
            if (token == null) {
                return null;
            }
            ActivityRecord r = token.weakActivity.get();
            if (r == null || r.getActivityStack() == null) {
                return null;
            }
            return r;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Token{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            sb.append(weakActivity.get());
            sb.append('}');
            return sb.toString();
        }

        @Override
        public String getName() {
            return name;
        }
    }

    static @Nullable ActivityRecord forTokenLocked(IBinder token) {
        try {
            return Token.tokenToActivityRecordLocked((Token)token);
        } catch (ClassCastException e) {
            Slog.w(TAG, "Bad activity token: " + token, e);
            return null;
        }
    }

    static boolean isResolverActivity(String className) {
        return ResolverActivity.class.getName().equals(className);
    }

    boolean isResolverOrDelegateActivity() {
        return isResolverActivity(mActivityComponent.getClassName()) || Objects.equals(
                mActivityComponent, mAtmService.mStackSupervisor.getSystemChooserActivity());
    }

    boolean isResolverOrChildActivity() {
        if (!"android".equals(packageName)) {
            return false;
        }
        try {
            return ResolverActivity.class.isAssignableFrom(
                    Object.class.getClassLoader().loadClass(mActivityComponent.getClassName()));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    ActivityRecord(ActivityTaskManagerService _service, WindowProcessController _caller,
            int _launchedFromPid, int _launchedFromUid, String _launchedFromPackage, Intent _intent,
            String _resolvedType, ActivityInfo aInfo, Configuration _configuration,
            ActivityRecord _resultTo, String _resultWho, int _reqCode, boolean _componentSpecified,
            boolean _rootVoiceInteraction, ActivityStackSupervisor supervisor,
            ActivityOptions options, ActivityRecord sourceRecord) {
        mAtmService = _service;
        mRootActivityContainer = _service.mRootActivityContainer;
        appToken = new Token(this, _intent);
        info = aInfo;
        launchedFromPid = _launchedFromPid;
        launchedFromUid = _launchedFromUid;
        launchedFromPackage = _launchedFromPackage;
        mUserId = UserHandle.getUserId(aInfo.applicationInfo.uid);
        intent = _intent;
        shortComponentName = _intent.getComponent().flattenToShortString();
        resolvedType = _resolvedType;
        componentSpecified = _componentSpecified;
        rootVoiceInteraction = _rootVoiceInteraction;
        mLastReportedConfiguration = new MergedConfiguration(_configuration);
        resultTo = _resultTo;
        resultWho = _resultWho;
        requestCode = _reqCode;
        setState(INITIALIZING, "ActivityRecord ctor");
        launchFailed = false;
        stopped = false;
        delayedResume = false;
        finishing = false;
        deferRelaunchUntilPaused = false;
        keysPaused = false;
        inHistory = false;
        visible = false;
        nowVisible = false;
        mDrawn = false;
        idle = false;
        hasBeenLaunched = false;
        mStackSupervisor = supervisor;

        // If the class name in the intent doesn't match that of the target, this is
        // probably an alias. We have to create a new ComponentName object to keep track
        // of the real activity name, so that FLAG_ACTIVITY_CLEAR_TOP is handled properly.
        if (aInfo.targetActivity == null
                || (aInfo.targetActivity.equals(_intent.getComponent().getClassName())
                && (aInfo.launchMode == LAUNCH_MULTIPLE
                || aInfo.launchMode == LAUNCH_SINGLE_TOP))) {
            mActivityComponent = _intent.getComponent();
        } else {
            mActivityComponent = new ComponentName(aInfo.packageName, aInfo.targetActivity);
        }
        taskAffinity = aInfo.taskAffinity;
        stateNotNeeded = (aInfo.flags & FLAG_STATE_NOT_NEEDED) != 0;
        nonLocalizedLabel = aInfo.nonLocalizedLabel;
        labelRes = aInfo.labelRes;
        if (nonLocalizedLabel == null && labelRes == 0) {
            ApplicationInfo app = aInfo.applicationInfo;
            nonLocalizedLabel = app.nonLocalizedLabel;
            labelRes = app.labelRes;
        }
        icon = aInfo.getIconResource();
        logo = aInfo.getLogoResource();
        theme = aInfo.getThemeResource();
        realTheme = theme;
        if (realTheme == 0) {
            realTheme = aInfo.applicationInfo.targetSdkVersion < HONEYCOMB
                    ? android.R.style.Theme : android.R.style.Theme_Holo;
        }
        if ((aInfo.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0) {
            windowFlags |= LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        if ((aInfo.flags & FLAG_MULTIPROCESS) != 0 && _caller != null
                && (aInfo.applicationInfo.uid == SYSTEM_UID
                    || aInfo.applicationInfo.uid == _caller.mInfo.uid)) {
            processName = _caller.mName;
        } else {
            processName = aInfo.processName;
        }

        if ((aInfo.flags & FLAG_EXCLUDE_FROM_RECENTS) != 0) {
            intent.addFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }

        packageName = aInfo.applicationInfo.packageName;
        launchMode = aInfo.launchMode;

        Entry ent = AttributeCache.instance().get(packageName,
                realTheme, com.android.internal.R.styleable.Window, mUserId);

        if (ent != null) {
            fullscreen = !ActivityInfo.isTranslucentOrFloating(ent.array);
            hasWallpaper = ent.array.getBoolean(R.styleable.Window_windowShowWallpaper, false);
            noDisplay = ent.array.getBoolean(R.styleable.Window_windowNoDisplay, false);
        } else {
            hasWallpaper = false;
            noDisplay = false;
        }

        setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);

        immersive = (aInfo.flags & FLAG_IMMERSIVE) != 0;

        requestedVrComponent = (aInfo.requestedVrComponent == null) ?
                null : ComponentName.unflattenFromString(aInfo.requestedVrComponent);

        mShowWhenLocked = (aInfo.flags & FLAG_SHOW_WHEN_LOCKED) != 0;
        mInheritShownWhenLocked = (aInfo.privateFlags & FLAG_INHERIT_SHOW_WHEN_LOCKED) != 0;
        mTurnScreenOn = (aInfo.flags & FLAG_TURN_SCREEN_ON) != 0;

        mRotationAnimationHint = aInfo.rotationAnimation;
        lockTaskLaunchMode = aInfo.lockTaskLaunchMode;
        if (info.applicationInfo.isPrivilegedApp()
                && (lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_ALWAYS
                || lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_NEVER)) {
            lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        }

        if (options != null) {
            pendingOptions = options;
            mLaunchTaskBehind = options.getLaunchTaskBehind();

            final int rotationAnimation = pendingOptions.getRotationAnimationHint();
            // Only override manifest supplied option if set.
            if (rotationAnimation >= 0) {
                mRotationAnimationHint = rotationAnimation;
            }
            final PendingIntent usageReport = pendingOptions.getUsageTimeReport();
            if (usageReport != null) {
                appTimeTracker = new AppTimeTracker(usageReport);
            }
            final boolean useLockTask = pendingOptions.getLockTaskMode();
            if (useLockTask && lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_DEFAULT) {
                lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED;
            }
            // Gets launch display id from options. It returns INVALID_DISPLAY if not set.
            mHandoverLaunchDisplayId = options.getLaunchDisplayId();
        }
    }

    void setProcess(WindowProcessController proc) {
        app = proc;
        final ActivityRecord root = task != null ? task.getRootActivity() : null;
        if (root == this) {
            task.setRootProcess(proc);
        }
    }

    boolean hasProcess() {
        return app != null;
    }

    boolean attachedToProcess() {
        return hasProcess() && app.hasThread();
    }

    void createAppWindowToken() {
        if (mAppWindowToken != null) {
            throw new IllegalArgumentException("App Window Token=" + mAppWindowToken
                    + " already created for r=" + this);
        }

        inHistory = true;

        // TODO(b/36505427): Maybe this call should be moved inside updateOverrideConfiguration()
        task.updateOverrideConfigurationFromLaunchBounds();
        // Make sure override configuration is up-to-date before using to create window controller.
        updateOverrideConfiguration();

        // TODO: remove after unification
        mAppWindowToken = mAtmService.mWindowManager.mRoot.getAppWindowToken(appToken.asBinder());
        if (mAppWindowToken != null) {
            // TODO: Should this throw an exception instead?
            Slog.w(TAG, "Attempted to add existing app token: " + appToken);
        } else {
            final Task container = task.getTask();
            if (container == null) {
                throw new IllegalArgumentException("createAppWindowToken: invalid task =" + task);
            }
            mAppWindowToken = createAppWindow(mAtmService.mWindowManager, appToken,
                    task.voiceSession != null, container.getDisplayContent(),
                    ActivityTaskManagerService.getInputDispatchingTimeoutLocked(this)
                            * 1000000L, fullscreen,
                    (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0,
                    info.applicationInfo.targetSdkVersion,
                    info.screenOrientation, mRotationAnimationHint,
                    mLaunchTaskBehind, isAlwaysFocusable());
            if (DEBUG_TOKEN_MOVEMENT || DEBUG_ADD_REMOVE) {
                Slog.v(TAG, "addAppToken: "
                        + mAppWindowToken + " task=" + container + " at "
                        + Integer.MAX_VALUE);
            }
            container.addChild(mAppWindowToken, Integer.MAX_VALUE /* add on top */);
        }

        task.addActivityToTop(this);

        // When an activity is started directly into a split-screen fullscreen stack, we need to
        // update the initial multi-window modes so that the callbacks are scheduled correctly when
        // the user leaves that mode.
        mLastReportedMultiWindowMode = inMultiWindowMode();
        mLastReportedPictureInPictureMode = inPinnedWindowingMode();
    }

    boolean addStartingWindow(String pkg, int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon, int logo, int windowFlags,
            IBinder transferFrom, boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents) {
        if (DEBUG_STARTING_WINDOW) {
            Slog.v(TAG, "setAppStartingWindow: token=" + appToken
                    + " pkg=" + pkg + " transferFrom=" + transferFrom + " newTask=" + newTask
                    + " taskSwitch=" + taskSwitch + " processRunning=" + processRunning
                    + " allowTaskSnapshot=" + allowTaskSnapshot);
        }
        if (mAppWindowToken == null) {
            Slog.w(TAG_WM, "Attempted to set icon of non-existing app token: " + appToken);
            return false;
        }
        if (mAppWindowToken.getTask() == null) {
            // Can be removed after unification of Task and TaskRecord.
            Slog.w(TAG_WM, "Attempted to start a window to an app token not having attached to any"
                    + " task: " + appToken);
            return false;
        }
        return mAppWindowToken.addStartingWindow(pkg, theme, compatInfo, nonLocalizedLabel,
                labelRes, icon, logo, windowFlags, transferFrom, newTask, taskSwitch,
                processRunning, allowTaskSnapshot, activityCreated, fromRecents);
    }

    // TODO: Remove after unification
    @VisibleForTesting
    AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token,
            boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
            boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
            int rotationAnimationHint, boolean launchTaskBehind,
            boolean alwaysFocusable) {
        return new AppWindowToken(service, token, mActivityComponent, voiceInteraction, dc,
                inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk, orientation,
                rotationAnimationHint, launchTaskBehind, alwaysFocusable,
                this);
    }

    void removeWindowContainer() {
        if (mAtmService.mWindowManager.mRoot == null) return;

        final DisplayContent dc = mAtmService.mWindowManager.mRoot.getDisplayContent(
                getDisplayId());
        if (dc == null) {
            Slog.w(TAG, "removeWindowContainer: Attempted to remove token: "
                    + appToken + " from non-existing displayId=" + getDisplayId());
            return;
        }
        // Resume key dispatching if it is currently paused before we remove the container.
        resumeKeyDispatchingLocked();
        dc.removeAppToken(appToken.asBinder());
    }

    /**
     * Reparents this activity into {@param newTask} at the provided {@param position}.  The caller
     * should ensure that the {@param newTask} is not already the parent of this activity.
     */
    void reparent(TaskRecord newTask, int position, String reason) {
        if (mAppWindowToken == null) {
            Slog.w(TAG, "reparent: Attempted to reparent non-existing app token: " + appToken);
            return;
        }
        final TaskRecord prevTask = task;
        if (prevTask == newTask) {
            throw new IllegalArgumentException(reason + ": task=" + newTask
                    + " is already the parent of r=" + this);
        }

        // TODO: Ensure that we do not directly reparent activities across stacks, as that may leave
        //       the stacks in strange states. For now, we should use Task.reparent() to ensure that
        //       the stack is left in an OK state.
        if (prevTask != null && newTask != null && prevTask.getStack() != newTask.getStack()) {
            throw new IllegalArgumentException(reason + ": task=" + newTask
                    + " is in a different stack (" + newTask.getStackId() + ") than the parent of"
                    + " r=" + this + " (" + prevTask.getStackId() + ")");
        }

        mAppWindowToken.reparent(newTask.getTask(), position);

        // Reparenting prevents informing the parent stack of activity removal in the case that
        // the new stack has the same parent. we must manually signal here if this is not the case.
        final ActivityStack prevStack = prevTask.getStack();

        if (prevStack != newTask.getStack()) {
            prevStack.onActivityRemovedFromStack(this);
        }
        // Remove the activity from the old task and add it to the new task.
        prevTask.removeActivity(this, true /* reparenting */);

        newTask.addActivityAtIndex(position, this);
    }

    private boolean isHomeIntent(Intent intent) {
        return ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(CATEGORY_HOME)
                || intent.hasCategory(CATEGORY_SECONDARY_HOME))
                && intent.getCategories().size() == 1
                && intent.getData() == null
                && intent.getType() == null;
    }

    static boolean isMainIntent(Intent intent) {
        return ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(CATEGORY_LAUNCHER)
                && intent.getCategories().size() == 1
                && intent.getData() == null
                && intent.getType() == null;
    }

    @VisibleForTesting
    boolean canLaunchHomeActivity(int uid, ActivityRecord sourceRecord) {
        if (uid == Process.myUid() || uid == 0) {
            // System process can launch home activity.
            return true;
        }
        // Allow the recents component to launch the home activity.
        final RecentTasks recentTasks = mStackSupervisor.mService.getRecentTasks();
        if (recentTasks != null && recentTasks.isCallerRecents(uid)) {
            return true;
        }
        // Resolver or system chooser activity can launch home activity.
        return sourceRecord != null && sourceRecord.isResolverOrDelegateActivity();
    }

    /**
     * @return whether the given package name can launch an assist activity.
     */
    private boolean canLaunchAssistActivity(String packageName) {
        final ComponentName assistComponent =
                mAtmService.mActiveVoiceInteractionServiceComponent;
        if (assistComponent != null) {
            return assistComponent.getPackageName().equals(packageName);
        }
        return false;
    }

    private void setActivityType(boolean componentSpecified, int launchedFromUid, Intent intent,
            ActivityOptions options, ActivityRecord sourceRecord) {
        int activityType = ACTIVITY_TYPE_UNDEFINED;
        if ((!componentSpecified || canLaunchHomeActivity(launchedFromUid, sourceRecord))
                && isHomeIntent(intent) && !isResolverOrDelegateActivity()) {
            // This sure looks like a home activity!
            activityType = ACTIVITY_TYPE_HOME;

            if (info.resizeMode == RESIZE_MODE_FORCE_RESIZEABLE
                    || info.resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // We only allow home activities to be resizeable if they explicitly requested it.
                info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
            }
        } else if (mAtmService.getRecentTasks().isRecentsComponent(mActivityComponent,
                info.applicationInfo.uid)) {
            activityType = ACTIVITY_TYPE_RECENTS;
        } else if (options != null && options.getLaunchActivityType() == ACTIVITY_TYPE_ASSISTANT
                && canLaunchAssistActivity(launchedFromPackage)) {
            activityType = ACTIVITY_TYPE_ASSISTANT;
        }
        setActivityType(activityType);
    }

    void setTaskToAffiliateWith(TaskRecord taskToAffiliateWith) {
        if (launchMode != LAUNCH_SINGLE_INSTANCE && launchMode != LAUNCH_SINGLE_TASK) {
            task.setTaskToAffiliateWith(taskToAffiliateWith);
        }
    }

    /**
     * @return Stack value from current task, null if there is no task.
     */
    <T extends ActivityStack> T getActivityStack() {
        return task != null ? (T) task.getStack() : null;
    }

    int getStackId() {
        return getActivityStack() != null ? getActivityStack().mStackId : INVALID_STACK_ID;
    }

    ActivityDisplay getDisplay() {
        final ActivityStack stack = getActivityStack();
        return stack != null ? stack.getDisplay() : null;
    }

    boolean setOccludesParent(boolean occludesParent) {
        final boolean changed = mAppWindowToken.setOccludesParent(occludesParent);
        if (changed) {
            if (!occludesParent) {
                getActivityStack().convertActivityToTranslucent(this);
            }
            // Keep track of the number of fullscreen activities in this task.
            task.numFullscreen += occludesParent ? +1 : -1;
            fullscreen = occludesParent;
        }
        // Always ensure visibility if this activity doesn't occlude parent, so the
        // {@link #returningOptions} of the activity under this one can be applied in
        // {@link #handleAlreadyVisible()}.
        if (changed || !occludesParent) {
            mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        }
        return changed;
    }

    void takeFromHistory() {
        if (inHistory) {
            inHistory = false;
            if (task != null && !finishing) {
                task = null;
            }
            clearOptionsLocked();
        }
    }

    boolean isInHistory() {
        return inHistory;
    }

    boolean isInStackLocked() {
        final ActivityStack stack = getActivityStack();
        return stack != null && stack.isInStackLocked(this) != null;
    }

    boolean isPersistable() {
        return (info.persistableMode == PERSIST_ROOT_ONLY ||
                info.persistableMode == PERSIST_ACROSS_REBOOTS) &&
                (intent == null || (intent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0);
    }

    boolean isFocusable() {
        return mRootActivityContainer.isFocusable(this, isAlwaysFocusable());
    }

    boolean isResizeable() {
        return ActivityInfo.isResizeableMode(info.resizeMode) || info.supportsPictureInPicture();
    }

    /**
     * @return whether this activity is non-resizeable or forced to be resizeable
     */
    boolean isNonResizableOrForcedResizable() {
        return info.resizeMode != RESIZE_MODE_RESIZEABLE
                && info.resizeMode != RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
    }

    /**
     * @return whether this activity supports PiP multi-window and can be put in the pinned stack.
     */
    boolean supportsPictureInPicture() {
        return mAtmService.mSupportsPictureInPicture && isActivityTypeStandardOrUndefined()
                && info.supportsPictureInPicture();
    }

    /**
     * @return whether this activity supports split-screen multi-window and can be put in the docked
     *         stack.
     */
    @Override
    public boolean supportsSplitScreenWindowingMode() {
        // An activity can not be docked even if it is considered resizeable because it only
        // supports picture-in-picture mode but has a non-resizeable resizeMode
        return super.supportsSplitScreenWindowingMode()
                && mAtmService.mSupportsSplitScreenMultiWindow && supportsResizeableMultiWindow();
    }

    /**
     * @return whether this activity supports freeform multi-window and can be put in the freeform
     *         stack.
     */
    boolean supportsFreeform() {
        return mAtmService.mSupportsFreeformWindowManagement && supportsResizeableMultiWindow();
    }

    /**
     * @return whether this activity supports non-PiP multi-window.
     */
    private boolean supportsResizeableMultiWindow() {
        return mAtmService.mSupportsMultiWindow && !isActivityTypeHome()
                && (ActivityInfo.isResizeableMode(info.resizeMode)
                        || mAtmService.mForceResizableActivities);
    }

    /**
     * Check whether this activity can be launched on the specified display.
     *
     * @param displayId Target display id.
     * @return {@code true} if either it is the default display or this activity can be put on a
     *         secondary screen.
     */
    boolean canBeLaunchedOnDisplay(int displayId) {
        return mAtmService.mStackSupervisor.canPlaceEntityOnDisplay(displayId, launchedFromPid,
                launchedFromUid, info);
    }

    /**
     * @param beforeStopping Whether this check is for an auto-enter-pip operation, that is to say
     *         the activity has requested to enter PiP when it would otherwise be stopped.
     *
     * @return whether this activity is currently allowed to enter PIP.
     */
    boolean checkEnterPictureInPictureState(String caller, boolean beforeStopping) {
        if (!supportsPictureInPicture()) {
            return false;
        }

        // Check app-ops and see if PiP is supported for this package
        if (!checkEnterPictureInPictureAppOpsState()) {
            return false;
        }

        // Check to see if we are in VR mode, and disallow PiP if so
        if (mAtmService.shouldDisableNonVrUiLocked()) {
            return false;
        }

        boolean isKeyguardLocked = mAtmService.isKeyguardLocked();
        boolean isCurrentAppLocked =
                mAtmService.getLockTaskModeState() != LOCK_TASK_MODE_NONE;
        final ActivityDisplay display = getDisplay();
        boolean hasPinnedStack = display != null && display.hasPinnedStack();
        // Don't return early if !isNotLocked, since we want to throw an exception if the activity
        // is in an incorrect state
        boolean isNotLockedOrOnKeyguard = !isKeyguardLocked && !isCurrentAppLocked;

        // We don't allow auto-PiP when something else is already pipped.
        if (beforeStopping && hasPinnedStack) {
            return false;
        }

        switch (mState) {
            case RESUMED:
                // When visible, allow entering PiP if the app is not locked.  If it is over the
                // keyguard, then we will prompt to unlock in the caller before entering PiP.
                return !isCurrentAppLocked &&
                        (supportsEnterPipOnTaskSwitch || !beforeStopping);
            case PAUSING:
            case PAUSED:
                // When pausing, then only allow enter PiP as in the resume state, and in addition,
                // require that there is not an existing PiP activity and that the current system
                // state supports entering PiP
                return isNotLockedOrOnKeyguard && !hasPinnedStack
                        && supportsEnterPipOnTaskSwitch;
            case STOPPING:
                // When stopping in a valid state, then only allow enter PiP as in the pause state.
                // Otherwise, fall through to throw an exception if the caller is trying to enter
                // PiP in an invalid stopping state.
                if (supportsEnterPipOnTaskSwitch) {
                    return isNotLockedOrOnKeyguard && !hasPinnedStack;
                }
            default:
                return false;
        }
    }

    /**
     * @return Whether AppOps allows this package to enter picture-in-picture.
     */
    private boolean checkEnterPictureInPictureAppOpsState() {
        return mAtmService.getAppOpsService().checkOperation(
                OP_PICTURE_IN_PICTURE, info.applicationInfo.uid, packageName) == MODE_ALLOWED;
    }

    boolean isAlwaysFocusable() {
        return (info.flags & FLAG_ALWAYS_FOCUSABLE) != 0;
    }

    /** Move activity with its stack to front and make the stack focused. */
    boolean moveFocusableActivityToTop(String reason) {
        if (!isFocusable()) {
            if (DEBUG_FOCUS) {
                Slog.d(TAG_FOCUS, "moveActivityStackToFront: unfocusable activity=" + this);
            }
            return false;
        }

        final TaskRecord task = getTaskRecord();
        final ActivityStack stack = getActivityStack();
        if (stack == null) {
            Slog.w(TAG, "moveActivityStackToFront: invalid task or stack: activity="
                    + this + " task=" + task);
            return false;
        }

        if (mRootActivityContainer.getTopResumedActivity() == this) {
            if (DEBUG_FOCUS) {
                Slog.d(TAG_FOCUS, "moveActivityStackToFront: already on top, activity=" + this);
            }
            return false;
        }

        if (DEBUG_FOCUS) {
            Slog.d(TAG_FOCUS, "moveActivityStackToFront: activity=" + this);
        }

        stack.moveToFront(reason, task);
        // Report top activity change to tracking services and WM
        if (mRootActivityContainer.getTopResumedActivity() == this) {
            mAtmService.setResumedActivityUncheckLocked(this, reason);
        }
        return true;
    }

    /** Finish all activities in the task with the same affinity as this one. */
    void finishActivityAffinity() {
        final ArrayList<ActivityRecord> activities = getTaskRecord().mActivities;
        for (int index = activities.indexOf(this); index >= 0; --index) {
            final ActivityRecord cur = activities.get(index);
            if (!Objects.equals(cur.taskAffinity, taskAffinity)) {
                break;
            }
            cur.finishIfPossible("request-affinity", true /* oomAdj */);
        }
    }

    /**
     * Sets the result for activity that started this one, clears the references to activities
     * started for result from this one, and clears new intents.
     */
    void finishActivityResults(int resultCode, Intent resultData) {
        // Send the result if needed
        if (resultTo != null) {
            if (DEBUG_RESULTS) {
                Slog.v(TAG_RESULTS, "Adding result to " + resultTo
                        + " who=" + resultWho + " req=" + requestCode
                        + " res=" + resultCode + " data=" + resultData);
            }
            if (resultTo.mUserId != mUserId) {
                if (resultData != null) {
                    resultData.prepareToLeaveUser(mUserId);
                }
            }
            if (info.applicationInfo.uid > 0) {
                mAtmService.mUgmInternal.grantUriPermissionFromIntent(info.applicationInfo.uid,
                        resultTo.packageName, resultData,
                        resultTo.getUriPermissionsLocked(), resultTo.mUserId);
            }
            resultTo.addResultLocked(this, resultWho, requestCode, resultCode, resultData);
            resultTo = null;
        } else if (DEBUG_RESULTS) {
            Slog.v(TAG_RESULTS, "No result destination from " + this);
        }

        // Make sure this HistoryRecord is not holding on to other resources,
        // because clients have remote IPC references to this object so we
        // can't assume that will go away and want to avoid circular IPC refs.
        results = null;
        pendingResults = null;
        newIntents = null;
        setSavedState(null /* savedState */);
    }

    /** Activity finish request was not executed. */
    static final int FINISH_RESULT_CANCELLED = 0;
    /** Activity finish was requested, activity will be fully removed later. */
    static final int FINISH_RESULT_REQUESTED = 1;
    /** Activity finish was requested, activity was removed from history. */
    static final int FINISH_RESULT_REMOVED = 2;

    /** Definition of possible results for activity finish request. */
    @IntDef(prefix = { "FINISH_RESULT_" }, value = {
            FINISH_RESULT_CANCELLED,
            FINISH_RESULT_REQUESTED,
            FINISH_RESULT_REMOVED,
    })
    @interface FinishRequest {}

    /**
     * See {@link #finishIfPossible(int, Intent, String, boolean)}
     */
    @FinishRequest int finishIfPossible(String reason, boolean oomAdj) {
        return finishIfPossible(Activity.RESULT_CANCELED, null /* resultData */, reason, oomAdj);
    }

    /**
     * Finish activity if possible. If activity was resumed - we must first pause it to make the
     * activity below resumed. Otherwise we will try to complete the request immediately by calling
     * {@link #completeFinishing(String)}.
     * @return One of {@link FinishRequest} values:
     * {@link #FINISH_RESULT_REMOVED} if this activity has been removed from the history list.
     * {@link #FINISH_RESULT_REQUESTED} if removal process was started, but it is still in the list
     * and will be removed from history later.
     * {@link #FINISH_RESULT_CANCELLED} if activity is already finishing or in invalid state and the
     * request to finish it was not ignored.
     */
    @FinishRequest int finishIfPossible(int resultCode, Intent resultData, String reason,
            boolean oomAdj) {
        if (DEBUG_RESULTS || DEBUG_STATES) {
            Slog.v(TAG_STATES, "Finishing activity r=" + this + ", result=" + resultCode
                    + ", data=" + resultData + ", reason=" + reason);
        }

        if (finishing) {
            Slog.w(TAG, "Duplicate finish request for r=" + this);
            return FINISH_RESULT_CANCELLED;
        }

        if (!isInStackLocked()) {
            Slog.w(TAG, "Finish request when not in stack for r=" + this);
            return FINISH_RESULT_CANCELLED;
        }

        final ActivityStack stack = getActivityStack();
        final boolean mayAdjustFocus = (isState(RESUMED) || stack.mResumedActivity == null)
                // It must be checked before {@link #makeFinishingLocked} is called, because a stack
                // is not visible if it only contains finishing activities.
                && mRootActivityContainer.isTopDisplayFocusedStack(stack);

        mAtmService.mWindowManager.deferSurfaceLayout();
        try {
            makeFinishingLocked();
            final TaskRecord task = getTaskRecord();
            EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                    mUserId, System.identityHashCode(this),
                    task.taskId, shortComponentName, reason);
            final ArrayList<ActivityRecord> activities = task.mActivities;
            final int index = activities.indexOf(this);
            if (index < (activities.size() - 1)) {
                if ((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                    // If the caller asked that this activity (and all above it)
                    // be cleared when the task is reset, don't lose that information,
                    // but propagate it up to the next activity.
                    final ActivityRecord next = activities.get(index + 1);
                    next.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                }
            }

            pauseKeyDispatchingLocked();

            // We are finishing the top focused activity and its stack has nothing to be focused so
            // the next focusable stack should be focused.
            if (mayAdjustFocus
                    && (stack.topRunningActivityLocked() == null || !stack.isFocusable())) {
                stack.adjustFocusToNextFocusableStack("finish-top");
            }

            finishActivityResults(resultCode, resultData);

            final boolean endTask = index <= 0 && !task.isClearingToReuseTask();
            final int transit = endTask ? TRANSIT_TASK_CLOSE : TRANSIT_ACTIVITY_CLOSE;
            if (isState(RESUMED)) {
                if (endTask) {
                    mAtmService.getTaskChangeNotificationController().notifyTaskRemovalStarted(
                            task.getTaskInfo());
                }
                // Prepare app close transition, but don't execute just yet. It is possible that
                // an activity that will be made resumed in place of this one will immediately
                // launch another new activity. In this case current closing transition will be
                // combined with open transition for the new activity.
                if (DEBUG_VISIBILITY || DEBUG_TRANSITION) {
                    Slog.v(TAG_TRANSITION, "Prepare close transition: finishing " + this);
                }
                getDisplay().mDisplayContent.prepareAppTransition(transit, false);

                // When finishing the activity preemptively take the snapshot before the app window
                // is marked as hidden and any configuration changes take place
                if (mAtmService.mWindowManager.mTaskSnapshotController != null) {
                    final ArraySet<Task> tasks = Sets.newArraySet(task.mTask);
                    mAtmService.mWindowManager.mTaskSnapshotController.snapshotTasks(tasks);
                    mAtmService.mWindowManager.mTaskSnapshotController
                            .addSkipClosingAppSnapshotTasks(tasks);
                }

                // Tell window manager to prepare for this one to be removed.
                setVisibility(false);

                if (stack.mPausingActivity == null) {
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish needs to pause: " + this);
                    if (DEBUG_USER_LEAVING) {
                        Slog.v(TAG_USER_LEAVING, "finish() => pause with userLeaving=false");
                    }
                    stack.startPausingLocked(false /* userLeaving */, false /* uiSleeping */,
                            null /* resuming */);
                }

                if (endTask) {
                    mAtmService.getLockTaskController().clearLockedTask(task);
                }
            } else if (!isState(PAUSING)) {
                if (visible) {
                    // Prepare and execute close transition.
                    prepareActivityHideTransitionAnimation(transit);
                }

                final boolean removedActivity = completeFinishing("finishIfPossible") == null;
                // Performance optimization - only invoke OOM adjustment if the state changed to
                // 'STOPPING'. Otherwise it will not change the OOM scores.
                if (oomAdj && isState(STOPPING)) {
                    mAtmService.updateOomAdj();
                }

                // The following code is an optimization. When the last non-task overlay activity
                // is removed from the task, we remove the entire task from the stack. However,
                // since that is done after the scheduled destroy callback from the activity, that
                // call to change the visibility of the task overlay activities would be out of
                // sync with the activity visibility being set for this finishing activity above.
                // In this case, we can set the visibility of all the task overlay activities when
                // we detect the last one is finishing to keep them in sync.
                if (task.onlyHasTaskOverlayActivities(true /* excludeFinishing */)) {
                    for (ActivityRecord taskOverlay : task.mActivities) {
                        if (!taskOverlay.mTaskOverlay) {
                            continue;
                        }
                        taskOverlay.prepareActivityHideTransitionAnimation(transit);
                    }
                }

                return removedActivity ? FINISH_RESULT_REMOVED : FINISH_RESULT_REQUESTED;
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish waiting for pause of: " + this);
            }

            return FINISH_RESULT_REQUESTED;
        } finally {
            mAtmService.mWindowManager.continueSurfaceLayout();
        }
    }

    private void prepareActivityHideTransitionAnimation(int transit) {
        final DisplayContent dc = getDisplay().mDisplayContent;
        dc.prepareAppTransition(transit, false);
        setVisibility(false);
        dc.executeAppTransition();
    }

    /**
     * Complete activity finish request that was initiated earlier. If the activity is still
     * pausing we will wait for it to complete its transition. If the activity that should appear in
     * place of this one is not visible yet - we'll wait for it first. Otherwise - activity can be
     * destroyed right away.
     * @param reason Reason for finishing the activity.
     * @return Flag indicating whether the activity was removed from history.
     */
    ActivityRecord completeFinishing(String reason) {
        if (!finishing || isState(RESUMED)) {
            throw new IllegalArgumentException(
                    "Activity must be finishing and not resumed to complete, r=" + this
                            + ", finishing=" + finishing + ", state=" + mState);
        }

        if (isState(PAUSING)) {
            // Activity is marked as finishing and will be processed once it completes.
            return this;
        }

        boolean activityRemoved = false;

        // If this activity is currently visible, and the resumed activity is not yet visible, then
        // hold off on finishing until the resumed one becomes visible.
        // The activity that we are finishing may be over the lock screen. In this case, we do not
        // want to consider activities that cannot be shown on the lock screen as running and should
        // proceed with finishing the activity if there is no valid next top running activity.
        // Note that if this finishing activity is floating task, we don't need to wait the
        // next activity resume and can destroy it directly.
        // TODO(b/137329632): find the next activity directly underneath this one, not just anywhere
        final ActivityRecord next = getDisplay().topRunningActivity(
                true /* considerKeyguardState */);
        final boolean isVisible = visible || nowVisible;
        final ActivityStack stack = getActivityStack();
        final boolean notFocusedStack = stack != mRootActivityContainer.getTopDisplayFocusedStack();
        if (isVisible && next != null && !next.nowVisible) {
            addToStopping(false /* scheduleIdle */, false /* idleDelayed */,
                    "completeFinishing");
            if (DEBUG_STATES) {
                Slog.v(TAG_STATES, "Moving to STOPPING: " + this + " (finish requested)");
            }
            setState(STOPPING, "completeFinishing");
            if (notFocusedStack) {
                mRootActivityContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                        false /* markFrozenIfConfigChanged */, true /* deferResume */);
            }
        } else if (isVisible && isState(PAUSED) && getActivityStack().isFocusedStackOnDisplay()
                && !inPinnedWindowingMode()) {
            // TODO(b/137329632): Currently non-focused stack is handled differently.
            addToFinishingAndWaitForIdle();
        } else {
            // Not waiting for the next one to become visible - finish right away.
            activityRemoved = destroyIfPossible(reason);
        }

        return activityRemoved ? null : this;
    }

    /**
     * Destroy and cleanup the activity both on client and server if possible. If activity is the
     * last one left on display with home stack and there is no other running activity - delay
     * destroying it until the next one starts.
     */
    boolean destroyIfPossible(String reason) {
        setState(FINISHING, "destroyIfPossible");

        // Make sure the record is cleaned out of other places.
        mStackSupervisor.mStoppingActivities.remove(this);
        mStackSupervisor.mGoingToSleepActivities.remove(this);

        final ActivityStack stack = getActivityStack();
        final ActivityDisplay display = getDisplay();
        // TODO(b/137329632): Exclude current activity when looking for the next one with
        //  ActivityDisplay#topRunningActivity().
        final ActivityRecord next = display.topRunningActivity();
        final boolean isLastStackOverEmptyHome =
                next == null && stack.isFocusedStackOnDisplay() && display.getHomeStack() != null;
        if (isLastStackOverEmptyHome) {
            // Don't destroy activity immediately if this is the last activity on the display and
            // the display contains home stack. Although there is no next activity at the moment,
            // another home activity should be started later. Keep this activity alive until next
            // home activity is resumed. This way the user won't see a temporary black screen.
            addToFinishingAndWaitForIdle();
            return false;
        }
        makeFinishingLocked();

        final boolean activityRemoved = destroyImmediately(true /* removeFromApp */,
                "finish-imm:" + reason);

        // If the display does not have running activity, the configuration may need to be
        // updated for restoring original orientation of the display.
        if (next == null) {
            mRootActivityContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                    false /* markFrozenIfConfigChanged */, true /* deferResume */);
        }
        if (activityRemoved) {
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }

        if (DEBUG_CONTAINERS) {
            Slog.d(TAG_CONTAINERS, "destroyIfPossible: r=" + this + " destroy returned removed="
                    + activityRemoved);
        }

        return activityRemoved;
    }

    @VisibleForTesting
    void addToFinishingAndWaitForIdle() {
        if (DEBUG_STATES) Slog.v(TAG, "Enqueueing pending finish: " + this);
        setState(FINISHING, "addToFinishingAndWaitForIdle");
        mStackSupervisor.mFinishingActivities.add(this);
        resumeKeyDispatchingLocked();
        mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    /**
     * Destroy the current CLIENT SIDE instance of an activity. This may be called both when
     * actually finishing an activity, or when performing a configuration switch where we destroy
     * the current client-side object but then create a new client-side object for this same
     * HistoryRecord.
     * Normally the server-side record will be removed when the client reports back after
     * destruction. If, however, at this point there is no client process attached, the record will
     * be removed immediately.
     *
     * @return {@code true} if activity was immediately removed from history, {@code false}
     * otherwise.
     */
    boolean destroyImmediately(boolean removeFromApp, String reason) {
        if (DEBUG_SWITCH || DEBUG_CLEANUP) {
            Slog.v(TAG_SWITCH, "Removing activity from " + reason + ": token=" + this
                    + ", app=" + (hasProcess() ? app.mName : "(null)"));
        }

        if (isState(DESTROYING, DESTROYED)) {
            if (DEBUG_STATES) {
                Slog.v(TAG_STATES, "activity " + this + " already destroying."
                        + "skipping request with reason:" + reason);
            }
            return false;
        }

        EventLog.writeEvent(EventLogTags.AM_DESTROY_ACTIVITY, mUserId,
                System.identityHashCode(this), getTaskRecord().taskId, shortComponentName, reason);

        boolean removedFromHistory = false;

        cleanUp(false /* cleanServices */, false /* setState */);

        final ActivityStack stack = getActivityStack();
        final boolean hadApp = hasProcess();

        if (hadApp) {
            if (removeFromApp) {
                app.removeActivity(this);
                if (!app.hasActivities()) {
                    mAtmService.clearHeavyWeightProcessIfEquals(app);
                    // Update any services we are bound to that might care about whether
                    // their client may have activities.
                    // No longer have activities, so update LRU list and oom adj.
                    app.updateProcessInfo(true /* updateServiceConnectionActivities */,
                            false /* activityChange */, true /* updateOomAdj */);
                }
            }

            boolean skipDestroy = false;

            try {
                if (DEBUG_SWITCH) Slog.i(TAG_SWITCH, "Destroying: " + this);
                mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                        DestroyActivityItem.obtain(finishing, configChangeFlags));
            } catch (Exception e) {
                // We can just ignore exceptions here...  if the process has crashed, our death
                // notification will clean things up.
                if (finishing) {
                    removeFromHistory(reason + " exceptionInScheduleDestroy");
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }

            nowVisible = false;

            // If the activity is finishing, we need to wait on removing it from the list to give it
            // a chance to do its cleanup.  During that time it may make calls back with its token
            // so we need to be able to find it on the list and so we don't want to remove it from
            // the list yet.  Otherwise, we can just immediately put it in the destroyed state since
            // we are not removing it from the list.
            if (finishing && !skipDestroy) {
                if (DEBUG_STATES) {
                    Slog.v(TAG_STATES, "Moving to DESTROYING: " + this + " (destroy requested)");
                }
                setState(DESTROYING,
                        "destroyActivityLocked. finishing and not skipping destroy");
                stack.scheduleDestroyTimeoutForActivity(this);
            } else {
                if (DEBUG_STATES) {
                    Slog.v(TAG_STATES, "Moving to DESTROYED: " + this + " (destroy skipped)");
                }
                setState(DESTROYED,
                        "destroyActivityLocked. not finishing or skipping destroy");
                if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during destroy for activity " + this);
                app = null;
            }
        } else {
            // Remove this record from the history.
            if (finishing) {
                removeFromHistory(reason + " hadNoApp");
                removedFromHistory = true;
            } else {
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to DESTROYED: " + this + " (no app)");
                setState(DESTROYED, "destroyActivityLocked. not finishing and had no app");
            }
        }

        configChangeFlags = 0;

        if (!stack.removeActivityFromLRUList(this) && hadApp) {
            Slog.w(TAG, "Activity " + this + " being finished, but not in LRU list");
        }

        return removedFromHistory;
    }

    boolean safelyDestroy(String reason) {
        if (isDestroyable()) {
            if (DEBUG_SWITCH) {
                final ActivityStack stack = getActivityStack();
                Slog.v(TAG_SWITCH, "Safely destroying " + this + " in state " + getState()
                        + " resumed=" + stack.mResumedActivity
                        + " pausing=" + stack.mPausingActivity
                        + " for reason " + reason);
            }
            return destroyImmediately(true /* removeFromApp */, reason);
        }
        return false;
    }

    /** Note: call {@link #cleanUp(boolean, boolean)} before this method. */
    void removeFromHistory(String reason) {
        finishActivityResults(Activity.RESULT_CANCELED, null /* resultData */);
        makeFinishingLocked();
        if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i(TAG_ADD_REMOVE, "Removing activity " + this + " from stack callers="
                    + Debug.getCallers(5));
        }

        takeFromHistory();
        final ActivityStack stack = getActivityStack();
        stack.removeTimeoutsForActivity(this);
        if (DEBUG_STATES) {
            Slog.v(TAG_STATES, "Moving to DESTROYED: " + this + " (removed from history)");
        }
        setState(DESTROYED, "removeFromHistory");
        if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during remove for activity " + this);
        app = null;
        removeWindowContainer();
        final TaskRecord task = getTaskRecord();
        final boolean lastActivity = task.removeActivity(this);
        // If we are removing the last activity in the task, not including task overlay activities,
        // then fall through into the block below to remove the entire task itself
        final boolean onlyHasTaskOverlays =
                task.onlyHasTaskOverlayActivities(false /* excludingFinishing */);

        if (lastActivity || onlyHasTaskOverlays) {
            if (DEBUG_STATES) {
                Slog.i(TAG, "removeFromHistory: last activity removed from " + this
                        + " onlyHasTaskOverlays=" + onlyHasTaskOverlays);
            }

            // The following block can be executed multiple times if there is more than one overlay.
            // {@link ActivityStackSupervisor#removeTaskByIdLocked} handles this by reverse lookup
            // of the task by id and exiting early if not found.
            if (onlyHasTaskOverlays) {
                // When destroying a task, tell the supervisor to remove it so that any activity it
                // has can be cleaned up correctly. This is currently the only place where we remove
                // a task with the DESTROYING mode, so instead of passing the onlyHasTaskOverlays
                // state into removeTask(), we just clear the task here before the other residual
                // work.
                // TODO: If the callers to removeTask() changes such that we have multiple places
                //       where we are destroying the task, move this back into removeTask()
                mStackSupervisor.removeTaskByIdLocked(task.taskId, false /* killProcess */,
                        !REMOVE_FROM_RECENTS, reason);
            }

            // We must keep the task around until all activities are destroyed. The following
            // statement will only execute once since overlays are also considered activities.
            if (lastActivity) {
                stack.removeTask(task, reason, REMOVE_TASK_MODE_DESTROYING);
            }
        }

        cleanUpActivityServices();
        removeUriPermissionsLocked();
    }

    void makeFinishingLocked() {
        if (finishing) {
            return;
        }
        finishing = true;
        if (stopped) {
            clearOptionsLocked();
        }

        if (mAtmService != null) {
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
        }
    }

    /**
     * This method is to only be called from the client via binder when the activity is destroyed
     * AND finished.
     */
    void destroyed(String reason) {
        getActivityStack().removeDestroyTimeoutForActivity(this);

        if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS, "activityDestroyedLocked: r=" + this);

        if (!isState(DESTROYING, DESTROYED)) {
            throw new IllegalStateException(
                    "Reported destroyed for activity that is not destroying: r=" + this);
        }

        if (isInStackLocked()) {
            cleanUp(true /* cleanServices */, false /* setState */);
            removeFromHistory(reason);
        }

        mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    /**
     * Perform the common clean-up of an activity record.  This is called both as part of
     * destroyActivityLocked() (when destroying the client-side representation) and cleaning things
     * up as a result of its hosting processing going away, in which case there is no remaining
     * client-side state to destroy so only the cleanup here is needed.
     *
     * Note: Call before {@link #removeFromHistory(String)}.
     */
    void cleanUp(boolean cleanServices, boolean setState) {
        final ActivityStack stack = getActivityStack();
        stack.onActivityRemovedFromStack(this);

        deferRelaunchUntilPaused = false;
        frozenBeforeDestroy = false;

        if (setState) {
            setState(DESTROYED, "cleanUp");
            if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during cleanUp for activity " + this);
            app = null;
        }

        // Inform supervisor the activity has been removed.
        mStackSupervisor.cleanupActivity(this);

        // Remove any pending results.
        if (finishing && pendingResults != null) {
            for (WeakReference<PendingIntentRecord> apr : pendingResults) {
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    mAtmService.mPendingIntentController.cancelIntentSender(rec,
                            false /* cleanActivity */);
                }
            }
            pendingResults = null;
        }

        if (cleanServices) {
            cleanUpActivityServices();
        }

        // Get rid of any pending idle timeouts.
        stack.removeTimeoutsForActivity(this);
        // Clean-up activities are no longer relaunching (e.g. app process died). Notify window
        // manager so it can update its bookkeeping.
        mAtmService.mWindowManager.notifyAppRelaunchesCleared(appToken);
    }

    /**
     * Perform clean-up of service connections in an activity record.
     */
    private void cleanUpActivityServices() {
        if (mServiceConnectionsHolder == null) {
            return;
        }
        // Throw away any services that have been bound by this activity.
        mServiceConnectionsHolder.disconnectActivityFromServices();
    }

    void logStartActivity(int tag, TaskRecord task) {
        final Uri data = intent.getData();
        final String strData = data != null ? data.toSafeString() : null;

        EventLog.writeEvent(tag,
                mUserId, System.identityHashCode(this), task.taskId,
                shortComponentName, intent.getAction(),
                intent.getType(), strData, intent.getFlags());
    }

    UriPermissionOwner getUriPermissionsLocked() {
        if (uriPermissions == null) {
            uriPermissions = new UriPermissionOwner(mAtmService.mUgmInternal, this);
        }
        return uriPermissions;
    }

    void addResultLocked(ActivityRecord from, String resultWho,
            int requestCode, int resultCode,
            Intent resultData) {
        ActivityResult r = new ActivityResult(from, resultWho,
                requestCode, resultCode, resultData);
        if (results == null) {
            results = new ArrayList<ResultInfo>();
        }
        results.add(r);
    }

    void removeResultsLocked(ActivityRecord from, String resultWho,
            int requestCode) {
        if (results != null) {
            for (int i=results.size()-1; i>=0; i--) {
                ActivityResult r = (ActivityResult)results.get(i);
                if (r.mFrom != from) continue;
                if (r.mResultWho == null) {
                    if (resultWho != null) continue;
                } else {
                    if (!r.mResultWho.equals(resultWho)) continue;
                }
                if (r.mRequestCode != requestCode) continue;

                results.remove(i);
            }
        }
    }

    void sendResult(int callingUid, String resultWho, int requestCode, int resultCode,
            Intent data) {
        if (callingUid > 0) {
            mAtmService.mUgmInternal.grantUriPermissionFromIntent(callingUid, packageName,
                    data, getUriPermissionsLocked(), mUserId);
        }

        if (DEBUG_RESULTS) {
            Slog.v(TAG, "Send activity result to " + this
                    + " : who=" + resultWho + " req=" + requestCode
                    + " res=" + resultCode + " data=" + data);
        }
        if (isState(RESUMED) && attachedToProcess()) {
            try {
                final ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
                list.add(new ResultInfo(resultWho, requestCode, resultCode, data));
                mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                        ActivityResultItem.obtain(list));
                return;
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending result to " + this, e);
            }
        }

        addResultLocked(null /* from */, resultWho, requestCode, resultCode, data);
    }

    private void addNewIntentLocked(ReferrerIntent intent) {
        if (newIntents == null) {
            newIntents = new ArrayList<>();
        }
        newIntents.add(intent);
    }

    final boolean isSleeping() {
        final ActivityStack stack = getActivityStack();
        return stack != null ? stack.shouldSleepActivities() : mAtmService.isSleepingLocked();
    }

    /**
     * Deliver a new Intent to an existing activity, so that its onNewIntent()
     * method will be called at the proper time.
     */
    final void deliverNewIntentLocked(int callingUid, Intent intent, String referrer) {
        // The activity now gets access to the data associated with this Intent.
        mAtmService.mUgmInternal.grantUriPermissionFromIntent(callingUid, packageName,
                intent, getUriPermissionsLocked(), mUserId);
        final ReferrerIntent rintent = new ReferrerIntent(intent, referrer);
        boolean unsent = true;
        final boolean isTopActivityWhileSleeping = isTopRunningActivity() && isSleeping();

        // We want to immediately deliver the intent to the activity if:
        // - It is currently resumed or paused. i.e. it is currently visible to the user and we want
        //   the user to see the visual effects caused by the intent delivery now.
        // - The device is sleeping and it is the top activity behind the lock screen (b/6700897).
        if ((mState == RESUMED || mState == PAUSED || isTopActivityWhileSleeping)
                && attachedToProcess()) {
            try {
                ArrayList<ReferrerIntent> ar = new ArrayList<>(1);
                ar.add(rintent);
                // Making sure the client state is RESUMED after transaction completed and doing
                // so only if activity is currently RESUMED. Otherwise, client may have extra
                // life-cycle calls to RESUMED (and PAUSED later).
                mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                        NewIntentItem.obtain(ar, mState == RESUMED));
                unsent = false;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception thrown sending new intent to " + this, e);
            } catch (NullPointerException e) {
                Slog.w(TAG, "Exception thrown sending new intent to " + this, e);
            }
        }
        if (unsent) {
            addNewIntentLocked(rintent);
        }
    }

    void updateOptionsLocked(ActivityOptions options) {
        if (options != null) {
            if (DEBUG_TRANSITION) Slog.i(TAG, "Update options for " + this);
            if (pendingOptions != null) {
                pendingOptions.abort();
            }
            pendingOptions = options;
        }
    }

    void applyOptionsLocked() {
        if (pendingOptions != null
                && pendingOptions.getAnimationType() != ANIM_SCENE_TRANSITION) {
            if (DEBUG_TRANSITION) Slog.i(TAG, "Applying options for " + this);
            applyOptionsLocked(pendingOptions, intent);
            if (task == null) {
                clearOptionsLocked(false /* withAbort */);
            } else {
                // This will clear the options for all the ActivityRecords for this Task.
                task.clearAllPendingOptions();
            }
        }
    }

    /**
     * Apply override app transition base on options & animation type.
     */
    void applyOptionsLocked(ActivityOptions pendingOptions, Intent intent) {
        final int animationType = pendingOptions.getAnimationType();
        final DisplayContent displayContent = mAppWindowToken.getDisplayContent();
        switch (animationType) {
            case ANIM_CUSTOM:
                displayContent.mAppTransition.overridePendingAppTransition(
                        pendingOptions.getPackageName(),
                        pendingOptions.getCustomEnterResId(),
                        pendingOptions.getCustomExitResId(),
                        pendingOptions.getOnAnimationStartListener());
                break;
            case ANIM_CLIP_REVEAL:
                displayContent.mAppTransition.overridePendingAppTransitionClipReveal(
                        pendingOptions.getStartX(), pendingOptions.getStartY(),
                        pendingOptions.getWidth(), pendingOptions.getHeight());
                if (intent.getSourceBounds() == null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                            pendingOptions.getStartY(),
                            pendingOptions.getStartX() + pendingOptions.getWidth(),
                            pendingOptions.getStartY() + pendingOptions.getHeight()));
                }
                break;
            case ANIM_SCALE_UP:
                displayContent.mAppTransition.overridePendingAppTransitionScaleUp(
                        pendingOptions.getStartX(), pendingOptions.getStartY(),
                        pendingOptions.getWidth(), pendingOptions.getHeight());
                if (intent.getSourceBounds() == null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                            pendingOptions.getStartY(),
                            pendingOptions.getStartX() + pendingOptions.getWidth(),
                            pendingOptions.getStartY() + pendingOptions.getHeight()));
                }
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                final boolean scaleUp = (animationType == ANIM_THUMBNAIL_SCALE_UP);
                final GraphicBuffer buffer = pendingOptions.getThumbnail();
                displayContent.mAppTransition.overridePendingAppTransitionThumb(buffer,
                        pendingOptions.getStartX(), pendingOptions.getStartY(),
                        pendingOptions.getOnAnimationStartListener(),
                        scaleUp);
                if (intent.getSourceBounds() == null && buffer != null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                            pendingOptions.getStartY(),
                            pendingOptions.getStartX() + buffer.getWidth(),
                            pendingOptions.getStartY() + buffer.getHeight()));
                }
                break;
            case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
            case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
                final AppTransitionAnimationSpec[] specs = pendingOptions.getAnimSpecs();
                final IAppTransitionAnimationSpecsFuture specsFuture =
                        pendingOptions.getSpecsFuture();
                if (specsFuture != null) {
                    displayContent.mAppTransition.overridePendingAppTransitionMultiThumbFuture(
                            specsFuture, pendingOptions.getOnAnimationStartListener(),
                            animationType == ANIM_THUMBNAIL_ASPECT_SCALE_UP);
                } else if (animationType == ANIM_THUMBNAIL_ASPECT_SCALE_DOWN
                        && specs != null) {
                    displayContent.mAppTransition.overridePendingAppTransitionMultiThumb(
                            specs, pendingOptions.getOnAnimationStartListener(),
                            pendingOptions.getAnimationFinishedListener(), false);
                } else {
                    displayContent.mAppTransition.overridePendingAppTransitionAspectScaledThumb(
                            pendingOptions.getThumbnail(),
                            pendingOptions.getStartX(), pendingOptions.getStartY(),
                            pendingOptions.getWidth(), pendingOptions.getHeight(),
                            pendingOptions.getOnAnimationStartListener(),
                            (animationType == ANIM_THUMBNAIL_ASPECT_SCALE_UP));
                    if (intent.getSourceBounds() == null) {
                        intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                                pendingOptions.getStartY(),
                                pendingOptions.getStartX() + pendingOptions.getWidth(),
                                pendingOptions.getStartY() + pendingOptions.getHeight()));
                    }
                }
                break;
            case ANIM_OPEN_CROSS_PROFILE_APPS:
                displayContent.mAppTransition
                        .overridePendingAppTransitionStartCrossProfileApps();
                break;
            case ANIM_REMOTE_ANIMATION:
                displayContent.mAppTransition.overridePendingAppTransitionRemote(
                        pendingOptions.getRemoteAnimationAdapter());
                break;
            case ANIM_NONE:
            case ANIM_UNDEFINED:
                break;
            default:
                Slog.e(TAG_WM, "applyOptionsLocked: Unknown animationType=" + animationType);
                break;
        }
    }

    ActivityOptions getOptionsForTargetActivityLocked() {
        return pendingOptions != null ? pendingOptions.forTargetActivity() : null;
    }

    void clearOptionsLocked() {
        clearOptionsLocked(true /* withAbort */);
    }

    void clearOptionsLocked(boolean withAbort) {
        if (withAbort && pendingOptions != null) {
            pendingOptions.abort();
        }
        pendingOptions = null;
    }

    ActivityOptions takeOptionsLocked(boolean fromClient) {
        if (DEBUG_TRANSITION) Slog.i(TAG, "Taking options for " + this + " callers="
                + Debug.getCallers(6));
        ActivityOptions opts = pendingOptions;

        // If we are trying to take activity options from the client, do not null it out if it's a
        // remote animation as the client doesn't need it ever. This is a workaround when client is
        // faster to take the options than we are to resume the next activity.
        // TODO (b/132432864): Fix the root cause of these transition preparing/applying options
        // timing somehow
        if (!fromClient || opts == null || opts.getRemoteAnimationAdapter() == null) {
            pendingOptions = null;
        }
        return opts;
    }

    void removeUriPermissionsLocked() {
        if (uriPermissions != null) {
            uriPermissions.removeUriPermissions();
            uriPermissions = null;
        }
    }

    void pauseKeyDispatchingLocked() {
        if (!keysPaused) {
            keysPaused = true;

            // TODO: remove the check after unification with AppWindowToken. The DC check is not
            // needed after no mock mAppWindowToken in tests.
            if (mAppWindowToken != null && mAppWindowToken.getDisplayContent() != null) {
                mAppWindowToken.getDisplayContent().getInputMonitor().pauseDispatchingLw(
                        mAppWindowToken);
            }
        }
    }

    void resumeKeyDispatchingLocked() {
        if (keysPaused) {
            keysPaused = false;

            // TODO: remove the check after unification with AppWindowToken. The DC check is not
            // needed after no mock mAppWindowToken in tests.
            if (mAppWindowToken != null && mAppWindowToken.getDisplayContent() != null) {
                mAppWindowToken.getDisplayContent().getInputMonitor().resumeDispatchingLw(
                        mAppWindowToken);
            }
        }
    }

    private void updateTaskDescription(CharSequence description) {
        task.lastDescription = description;
    }

    void setDeferHidingClient(boolean deferHidingClient) {
        if (mDeferHidingClient == deferHidingClient) {
            return;
        }
        mDeferHidingClient = deferHidingClient;
        if (!mDeferHidingClient && !visible) {
            // Hiding the client is no longer deferred and the app isn't visible still, go ahead and
            // update the visibility.
            setVisibility(false);
        }
    }

    void setVisibility(boolean visible) {
        if (mAppWindowToken == null) {
            Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: "
                    + appToken);
            return;
        }
        mAppWindowToken.setVisibility(visible, mDeferHidingClient);
        mStackSupervisor.getActivityMetricsLogger().notifyVisibilityChanged(this);
    }

    // TODO: Look into merging with #commitVisibility()
    void setVisible(boolean newVisible) {
        visible = newVisible;
        mDeferHidingClient = !visible && mDeferHidingClient;
        setVisibility(visible);
        mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
    }

    void setState(ActivityState state, String reason) {
        if (DEBUG_STATES) Slog.v(TAG_STATES, "State movement: " + this + " from:" + getState()
                        + " to:" + state + " reason:" + reason);

        if (state == mState) {
            // No need to do anything if state doesn't change.
            if (DEBUG_STATES) Slog.v(TAG_STATES, "State unchanged from:" + state);
            return;
        }

        mState = state;

        final TaskRecord parent = getTaskRecord();

        if (parent != null) {
            parent.onActivityStateChanged(this, state, reason);
        }

        // The WindowManager interprets the app stopping signal as
        // an indication that the Surface will eventually be destroyed.
        // This however isn't necessarily true if we are going to sleep.
        if (state == STOPPING && !isSleeping()) {
            if (mAppWindowToken == null) {
                Slog.w(TAG_WM, "Attempted to notify stopping on non-existing app token: "
                        + appToken);
                return;
            }
            mAppWindowToken.detachChildren();
        }

        if (state == RESUMED) {
            mAtmService.updateBatteryStats(this, true);
            mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_RESUMED);
        } else if (state == PAUSED) {
            mAtmService.updateBatteryStats(this, false);
            mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_PAUSED);
        } else if (state == STOPPED) {
            mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_STOPPED);
        } else if (state == DESTROYED) {
            mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_DESTROYED);
        }
    }

    ActivityState getState() {
        return mState;
    }

    /**
     * Returns {@code true} if the Activity is in the specified state.
     */
    boolean isState(ActivityState state) {
        return state == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(ActivityState state1, ActivityState state2) {
        return state1 == mState || state2 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(ActivityState state1, ActivityState state2, ActivityState state3) {
        return state1 == mState || state2 == mState || state3 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(ActivityState state1, ActivityState state2, ActivityState state3,
            ActivityState state4) {
        return state1 == mState || state2 == mState || state3 == mState || state4 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(ActivityState state1, ActivityState state2, ActivityState state3,
            ActivityState state4, ActivityState state5) {
        return state1 == mState || state2 == mState || state3 == mState || state4 == mState
                || state5 == mState;
    }

    void notifyAppResumed(boolean wasStopped) {
        if (mAppWindowToken == null) {
            Slog.w(TAG_WM, "Attempted to notify resumed of non-existing app token: "
                    + appToken);
            return;
        }
        mAppWindowToken.notifyAppResumed(wasStopped);
    }

    void notifyUnknownVisibilityLaunched() {

        // No display activities never add a window, so there is no point in waiting them for
        // relayout.
        if (!noDisplay) {
            if (mAppWindowToken != null) {
                mAppWindowToken.getDisplayContent().mUnknownAppVisibilityController
                        .notifyLaunched(mAppWindowToken);
            }
        }
    }

    /**
     * @return true if the input activity should be made visible, ignoring any effect Keyguard
     * might have on the visibility
     *
     * TODO(b/123540470): Combine this method and {@link #shouldBeVisible(boolean)}.
     *
     * @see {@link ActivityStack#checkKeyguardVisibility}
     */
    boolean shouldBeVisibleIgnoringKeyguard(boolean behindFullscreenActivity) {
        if (!okToShowLocked()) {
            return false;
        }

        return !behindFullscreenActivity || mLaunchTaskBehind;
    }

    boolean shouldBeVisible(boolean behindFullscreenActivity) {
        // Check whether activity should be visible without Keyguard influence
        visibleIgnoringKeyguard = shouldBeVisibleIgnoringKeyguard(behindFullscreenActivity);

        final ActivityStack stack = getActivityStack();
        if (stack == null) {
            return false;
        }

        // Whether the activity is on the sleeping display.
        // TODO(b/129750406): This should be applied for the default display, too.
        final boolean isDisplaySleeping = getDisplay().isSleeping()
                && getDisplayId() != DEFAULT_DISPLAY;
        // Whether this activity is the top activity of this stack.
        final boolean isTop = this == stack.getTopActivity();
        // Exclude the case where this is the top activity in a pinned stack.
        final boolean isTopNotPinnedStack = stack.isAttached()
                && stack.getDisplay().isTopNotPinnedStack(stack);
        // Now check whether it's really visible depending on Keyguard state, and update
        // {@link ActivityStack} internal states.
        final boolean visibleIgnoringDisplayStatus = stack.checkKeyguardVisibility(this,
                visibleIgnoringKeyguard, isTop && isTopNotPinnedStack);
        return visibleIgnoringDisplayStatus && !isDisplaySleeping;
    }

    boolean shouldBeVisible() {
        final ActivityStack stack = getActivityStack();
        if (stack == null) {
            return false;
        }

        // TODO: Use real value of behindFullscreenActivity calculated using the same logic in
        // ActivityStack#ensureActivitiesVisibleLocked().
        return shouldBeVisible(!stack.shouldBeVisible(null /* starting */));
    }

    void makeVisibleIfNeeded(ActivityRecord starting, boolean reportToClient) {
        // This activity is not currently visible, but is running. Tell it to become visible.
        if (mState == RESUMED || this == starting) {
            if (DEBUG_VISIBILITY) Slog.d(TAG_VISIBILITY,
                    "Not making visible, r=" + this + " state=" + mState + " starting=" + starting);
            return;
        }

        // If this activity is paused, tell it to now show its window.
        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                "Making visible and scheduling visibility: " + this);
        final ActivityStack stack = getActivityStack();
        try {
            if (stack.mTranslucentActivityWaiting != null) {
                updateOptionsLocked(returningOptions);
                stack.mUndrawnActivitiesBelowTopTranslucent.add(this);
            }
            setVisible(true);
            sleeping = false;
            app.postPendingUiCleanMsg(true);
            if (reportToClient) {
                makeClientVisible();
            } else {
                mClientVisibilityDeferred = true;
            }
            // The activity may be waiting for stop, but that is no longer appropriate for it.
            mStackSupervisor.mStoppingActivities.remove(this);
            mStackSupervisor.mGoingToSleepActivities.remove(this);
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making visible: " + intent.getComponent(), e);
        }
        handleAlreadyVisible();
    }

    /** Send visibility change message to the client and pause if needed. */
    void makeClientVisible() {
        mClientVisibilityDeferred = false;
        try {
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    WindowVisibilityItem.obtain(true /* showWindow */));
            makeActiveIfNeeded(null /* activeActivity*/);
            if (isState(STOPPING, STOPPED)) {
                // Set state to STARTED in order to have consistent state with client while
                // making an non-active activity visible from stopped.
                setState(STARTED, "makeClientVisible");
            }
        } catch (Exception e) {
            Slog.w(TAG, "Exception thrown sending visibility update: " + intent.getComponent(), e);
        }
    }

    void makeInvisible() {
        if (!visible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Already invisible: " + this);
            return;
        }
        // Now for any activities that aren't visible to the user, make sure they no longer are
        // keeping the screen frozen.
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "Making invisible: " + this + ", state=" + getState());
        }
        try {
            final boolean canEnterPictureInPicture = checkEnterPictureInPictureState(
                    "makeInvisible", true /* beforeStopping */);
            // Defer telling the client it is hidden if it can enter Pip and isn't current paused,
            // stopped or stopping. This gives it a chance to enter Pip in onPause().
            // TODO: There is still a question surrounding activities in multi-window mode that want
            // to enter Pip after they are paused, but are still visible. I they should be okay to
            // enter Pip in those cases, but not "auto-Pip" which is what this condition covers and
            // the current contract for "auto-Pip" is that the app should enter it before onPause
            // returns. Just need to confirm this reasoning makes sense.
            final boolean deferHidingClient = canEnterPictureInPicture
                    && !isState(STOPPING, STOPPED, PAUSED);
            setDeferHidingClient(deferHidingClient);
            setVisible(false);

            switch (getState()) {
                case STOPPING:
                case STOPPED:
                    if (attachedToProcess()) {
                        if (DEBUG_VISIBILITY) {
                            Slog.v(TAG_VISIBILITY, "Scheduling invisibility: " + this);
                        }
                        mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(),
                                appToken, WindowVisibilityItem.obtain(false /* showWindow */));
                    }

                    // Reset the flag indicating that an app can enter picture-in-picture once the
                    // activity is hidden
                    supportsEnterPipOnTaskSwitch = false;
                    break;

                case INITIALIZING:
                case RESUMED:
                case PAUSING:
                case PAUSED:
                case STARTED:
                    addToStopping(true /* scheduleIdle */,
                            canEnterPictureInPicture /* idleDelayed */, "makeInvisible");
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making hidden: " + intent.getComponent(), e);
        }
    }

    /**
     * Make activity resumed or paused if needed.
     * @param activeActivity an activity that is resumed or just completed pause action.
     *                       We won't change the state of this activity.
     */
    boolean makeActiveIfNeeded(ActivityRecord activeActivity) {
        if (shouldResumeActivity(activeActivity)) {
            if (DEBUG_VISIBILITY) {
                Slog.v("TAG_VISIBILITY", "Resume visible activity, " + this);
            }
            return getActivityStack().resumeTopActivityUncheckedLocked(activeActivity /* prev */,
                    null /* options */);
        } else if (shouldPauseActivity(activeActivity)) {
            if (DEBUG_VISIBILITY) {
                Slog.v("TAG_VISIBILITY", "Pause visible activity, " + this);
            }
            // An activity must be in the {@link PAUSING} state for the system to validate
            // the move to {@link PAUSED}.
            setState(PAUSING, "makeVisibleIfNeeded");
            try {
                mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                        PauseActivityItem.obtain(finishing, false /* userLeaving */,
                                configChangeFlags, false /* dontReport */));
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending pause: " + intent.getComponent(), e);
            }
        }
        return false;
    }

    /**
     * Check if activity should be moved to PAUSED state. The activity:
     * - should be eligible to be made active (see {@link #shouldMakeActive(ActivityRecord)})
     * - should be non-focusable
     * - should not be currently pausing or paused
     * @param activeActivity the activity that is active or just completed pause action. We won't
     *                       resume if this activity is active.
     */
    private boolean shouldPauseActivity(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && !isFocusable() && !isState(PAUSING, PAUSED);
    }

    /**
     * Check if activity should be moved to RESUMED state. The activity:
     * - should be eligible to be made active (see {@link #shouldMakeActive(ActivityRecord)})
     * - should be focusable
     * @param activeActivity the activity that is active or just completed pause action. We won't
     *                       resume if this activity is active.
     */
    @VisibleForTesting
    boolean shouldResumeActivity(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && isFocusable() && !isState(RESUMED)
                && getActivityStack().getVisibility(activeActivity) == STACK_VISIBILITY_VISIBLE;
    }

    /**
     * Check if activity is eligible to be made active (resumed of paused). The activity:
     * - should be paused, stopped or stopping
     * - should not be the currently active one or launching behind other tasks
     * - should be either the topmost in task, or right below the top activity that is finishing
     * If all of these conditions are not met at the same time, the activity cannot be made active.
     */
    @VisibleForTesting
    boolean shouldMakeActive(ActivityRecord activeActivity) {
        // If the activity is stopped, stopping, cycle to an active state. We avoid doing
        // this when there is an activity waiting to become translucent as the extra binder
        // calls will lead to noticeable jank. A later call to
        // ActivityStack#ensureActivitiesVisibleLocked will bring the activity to a proper
        // active state.
        if (!isState(STARTED, RESUMED, PAUSED, STOPPED, STOPPING)
                || getActivityStack().mTranslucentActivityWaiting != null) {
            return false;
        }

        if (this == activeActivity) {
            return false;
        }

        if (!mStackSupervisor.readyToResume()) {
            // Making active is currently deferred (e.g. because an activity launch is in progress).
            return false;
        }

        if (this.mLaunchTaskBehind) {
            // This activity is being launched from behind, which means that it's not intended to be
            // presented to user right now, even if it's set to be visible.
            return false;
        }

        // Check if position in task allows to become paused
        final int positionInTask = task.mActivities.indexOf(this);
        if (positionInTask == -1) {
            throw new IllegalStateException("Activity not found in its task");
        }
        if (positionInTask == task.mActivities.size() - 1) {
            // It's the topmost activity in the task - should become resumed now
            return true;
        }
        // Check if activity above is finishing now and this one becomes the topmost in task.
        final ActivityRecord activityAbove = task.mActivities.get(positionInTask + 1);
        if (activityAbove.finishing && results == null) {
            // We will only allow making active if activity above wasn't launched for result.
            // Otherwise it will cause this activity to resume before getting result.
            return true;
        }
        return false;
    }

    void handleAlreadyVisible() {
        stopFreezingScreenLocked(false);
        try {
            if (returningOptions != null) {
                app.getThread().scheduleOnNewActivityOptions(appToken, returningOptions.toBundle());
            }
        } catch(RemoteException e) {
        }
    }

    static void activityResumedLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (DEBUG_SAVED_STATE) Slog.i(TAG_STATES, "Resumed activity; dropping state of: " + r);
        if (r == null) {
            // If an app reports resumed after a long delay, the record on server side might have
            // been removed (e.g. destroy timeout), so the token could be null.
            return;
        }
        r.setSavedState(null /* savedState */);

        final ActivityDisplay display = r.getDisplay();
        if (display != null) {
            display.handleActivitySizeCompatModeIfNeeded(r);
        }
    }

    /**
     * Once we know that we have asked an application to put an activity in the resumed state
     * (either by launching it or explicitly telling it), this function updates the rest of our
     * state to match that fact.
     */
    void completeResumeLocked() {
        final boolean wasVisible = visible;
        setVisible(true);
        if (!wasVisible) {
            // Visibility has changed, so take a note of it so we call the TaskStackChangedListener
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
        }
        idle = false;
        results = null;
        newIntents = null;
        stopped = false;

        if (isActivityTypeHome()) {
            mStackSupervisor.updateHomeProcess(task.mActivities.get(0).app);
        }

        if (nowVisible) {
            mStackSupervisor.stopWaitingForActivityVisible(this);
        }

        // Schedule an idle timeout in case the app doesn't do it for us.
        mStackSupervisor.scheduleIdleTimeoutLocked(this);

        mStackSupervisor.reportResumedActivityLocked(this);

        resumeKeyDispatchingLocked();
        final ActivityStack stack = getActivityStack();
        mStackSupervisor.mNoAnimActivities.clear();

        // Mark the point when the activity is resuming
        // TODO: To be more accurate, the mark should be before the onCreate,
        //       not after the onResume. But for subsequent starts, onResume is fine.
        if (hasProcess()) {
            cpuTimeAtResume = app.getCpuTime();
        } else {
            cpuTimeAtResume = 0; // Couldn't get the cpu time of process
        }

        returningOptions = null;

        if (canTurnScreenOn()) {
            mStackSupervisor.wakeUp("turnScreenOnFlag");
        } else {
            // If the screen is going to turn on because the caller explicitly requested it and
            // the keyguard is not showing don't attempt to sleep. Otherwise the Activity will
            // pause and then resume again later, which will result in a double life-cycle event.
            stack.checkReadyForSleep();
        }
    }

    void stopIfPossible() {
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH, "Stopping: " + this);
        final ActivityStack stack = getActivityStack();
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (info.flags & ActivityInfo.FLAG_NO_HISTORY) != 0) {
            if (!finishing) {
                if (!stack.shouldSleepActivities()) {
                    if (DEBUG_STATES) Slog.d(TAG_STATES, "no-history finish of " + this);
                    if (finishIfPossible("stop-no-history", false /* oomAdj */)
                            != FINISH_RESULT_CANCELLED) {
                        resumeKeyDispatchingLocked();
                        return;
                    }
                } else {
                    if (DEBUG_STATES) {
                        Slog.d(TAG_STATES, "Not finishing noHistory " + this
                                + " on stop because we're just sleeping");
                    }
                }
            }
        }

        if (!attachedToProcess()) {
            return;
        }
        resumeKeyDispatchingLocked();
        try {
            stopped = false;
            if (DEBUG_STATES) {
                Slog.v(TAG_STATES, "Moving to STOPPING: " + this + " (stop requested)");
            }
            setState(STOPPING, "stopIfPossible");
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Stopping visible=" + visible + " for " + this);
            }
            if (!visible) {
                setVisible(false);
            }
            EventLogTags.writeAmStopActivity(
                    mUserId, System.identityHashCode(this), shortComponentName);
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    StopActivityItem.obtain(visible, configChangeFlags));
            if (stack.shouldSleepOrShutDownActivities()) {
                setSleeping(true);
            }
            stack.scheduleStopTimeoutForActivity(this);
        } catch (Exception e) {
            // Maybe just ignore exceptions here...  if the process has crashed, our death
            // notification will clean things up.
            Slog.w(TAG, "Exception thrown during pause", e);
            // Just in case, assume it to be stopped.
            stopped = true;
            if (DEBUG_STATES) Slog.v(TAG_STATES, "Stop failed; moving to STOPPED: " + this);
            setState(STOPPED, "stopIfPossible");
            if (deferRelaunchUntilPaused) {
                destroyImmediately(true /* removeFromApp */, "stop-except");
            }
        }
    }

    final void activityStoppedLocked(Bundle newIcicle, PersistableBundle newPersistentState,
            CharSequence description) {
        final ActivityStack stack = getActivityStack();
        final boolean isStopping = mState == STOPPING;
        if (!isStopping && mState != RESTARTING_PROCESS) {
            Slog.i(TAG, "Activity reported stop, but no longer stopping: " + this);
            stack.removeStopTimeoutForActivity(this);
            return;
        }
        if (newPersistentState != null) {
            mPersistentState = newPersistentState;
            mAtmService.notifyTaskPersisterLocked(task, false);
        }

        if (newIcicle != null) {
            // If icicle is null, this is happening due to a timeout, so we haven't really saved
            // the state.
            setSavedState(newIcicle);
            launchCount = 0;
            updateTaskDescription(description);
        }
        if (DEBUG_SAVED_STATE) Slog.i(TAG_SAVED_STATE, "Saving icicle of " + this + ": " + mIcicle);
        if (!stopped) {
            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to STOPPED: " + this + " (stop complete)");
            stack.removeStopTimeoutForActivity(this);
            stopped = true;
            if (isStopping) {
                setState(STOPPED, "activityStoppedLocked");
            }

            if (mAppWindowToken != null) {
                mAppWindowToken.notifyAppStopped();
            }

            if (finishing) {
                clearOptionsLocked();
            } else {
                if (deferRelaunchUntilPaused) {
                    destroyImmediately(true /* removeFromApp */, "stop-config");
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
                } else {
                    mRootActivityContainer.updatePreviousProcess(this);
                }
            }
        }
    }

    void addToStopping(boolean scheduleIdle, boolean idleDelayed, String reason) {
        if (!mStackSupervisor.mStoppingActivities.contains(this)) {
            EventLog.writeEvent(EventLogTags.AM_ADD_TO_STOPPING, mUserId,
                    System.identityHashCode(this), shortComponentName, reason);
            mStackSupervisor.mStoppingActivities.add(this);
        }

        final ActivityStack stack = getActivityStack();
        // If we already have a few activities waiting to stop, then give up on things going idle
        // and start clearing them out. Or if r is the last of activity of the last task the stack
        // will be empty and must be cleared immediately.
        boolean forceIdle = mStackSupervisor.mStoppingActivities.size() > MAX_STOPPING_TO_FORCE
                || (isRootOfTask() && stack.getChildCount() <= 1);
        if (scheduleIdle || forceIdle) {
            if (DEBUG_PAUSE) {
                Slog.v(TAG_PAUSE, "Scheduling idle now: forceIdle=" + forceIdle
                        + "immediate=" + !idleDelayed);
            }
            if (!idleDelayed) {
                mStackSupervisor.scheduleIdleLocked();
            } else {
                mStackSupervisor.scheduleIdleTimeoutLocked(this);
            }
        } else {
            stack.checkReadyForSleep();
        }
    }

    void startLaunchTickingLocked() {
        if (Build.IS_USER) {
            return;
        }
        if (launchTickTime == 0) {
            launchTickTime = SystemClock.uptimeMillis();
            continueLaunchTickingLocked();
        }
    }

    boolean continueLaunchTickingLocked() {
        if (launchTickTime == 0) {
            return false;
        }

        final ActivityStack stack = getActivityStack();
        if (stack == null) {
            return false;
        }

        stack.removeLaunchTickMessages();
        stack.scheduleLaunchTickForActivity(this);
        return true;
    }

    void finishLaunchTickingLocked() {
        launchTickTime = 0;
        final ActivityStack stack = getActivityStack();
        if (stack == null) {
            return;
        }
        stack.removeLaunchTickMessages();
    }

    // IApplicationToken

    public boolean mayFreezeScreenLocked(WindowProcessController app) {
        // Only freeze the screen if this activity is currently attached to
        // an application, and that application is not blocked or unresponding.
        // In any other case, we can't count on getting the screen unfrozen,
        // so it is best to leave as-is.
        return hasProcess() && !app.isCrashing() && !app.isNotResponding();
    }

    public void startFreezingScreenLocked(WindowProcessController app, int configChanges) {
        if (mayFreezeScreenLocked(app)) {
            if (mAppWindowToken == null) {
                Slog.w(TAG_WM,
                        "Attempted to freeze screen with non-existing app token: " + appToken);
                return;
            }

            // Window configuration changes only effect windows, so don't require a screen freeze.
            int freezableConfigChanges = configChanges & ~(CONFIG_WINDOW_CONFIGURATION);
            if (freezableConfigChanges == 0 && mAppWindowToken.okToDisplay()) {
                if (DEBUG_ORIENTATION) Slog.v(TAG_WM, "Skipping set freeze of " + appToken);
                return;
            }

            mAppWindowToken.startFreezingScreen();
        }
    }

    public void stopFreezingScreenLocked(boolean force) {
        if (force || frozenBeforeDestroy) {
            frozenBeforeDestroy = false;
            if (mAppWindowToken == null) {
                return;
            }
            if (DEBUG_ORIENTATION) {
                Slog.v(TAG_WM, "Clear freezing of " + appToken + ": hidden="
                        + mAppWindowToken.isHidden() + " freezing="
                        + mAppWindowToken.isFreezingScreen());
            }
            mAppWindowToken.stopFreezingScreen(true, force);
        }
    }

    public void reportFullyDrawnLocked(boolean restoredFromBundle) {
        final WindowingModeTransitionInfoSnapshot info = mStackSupervisor
                .getActivityMetricsLogger().logAppTransitionReportedDrawn(this, restoredFromBundle);
        if (info != null) {
            mStackSupervisor.reportActivityLaunchedLocked(false /* timeout */, this,
                    info.windowsFullyDrawnDelayMs, info.getLaunchState());
        }
    }

    /**
     * Called when the starting window for this container is drawn.
     */
    public void onStartingWindowDrawn(long timestamp) {
        synchronized (mAtmService.mGlobalLock) {
            mStackSupervisor.getActivityMetricsLogger().notifyStartingWindowDrawn(
                    getWindowingMode(), timestamp);
        }
    }

    /** Called when the windows associated app window container are drawn. */
    public void onWindowsDrawn(boolean drawn, long timestamp) {
        synchronized (mAtmService.mGlobalLock) {
            mDrawn = drawn;
            if (!drawn) {
                return;
            }
            final WindowingModeTransitionInfoSnapshot info = mStackSupervisor
                    .getActivityMetricsLogger().notifyWindowsDrawn(getWindowingMode(), timestamp);
            final int windowsDrawnDelayMs = info != null ? info.windowsDrawnDelayMs : INVALID_DELAY;
            final @LaunchState int launchState = info != null ? info.getLaunchState() : -1;
            mStackSupervisor.reportActivityLaunchedLocked(false /* timeout */, this,
                    windowsDrawnDelayMs, launchState);
            mStackSupervisor.stopWaitingForActivityVisible(this);
            finishLaunchTickingLocked();
            if (task != null) {
                task.hasBeenVisible = true;
            }
        }
    }

    /** Called when the windows associated app window container are visible. */
    public void onWindowsVisible() {
        synchronized (mAtmService.mGlobalLock) {
            mStackSupervisor.stopWaitingForActivityVisible(this);
            if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsVisibleLocked(): " + this);
            if (!nowVisible) {
                nowVisible = true;
                lastVisibleTime = SystemClock.uptimeMillis();
                mAtmService.scheduleAppGcsLocked();
            }
        }
    }

    /** Called when the windows associated app window container are no longer visible. */
    public void onWindowsGone() {
        synchronized (mAtmService.mGlobalLock) {
            if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsGone(): " + this);
            nowVisible = false;
        }
    }

    void onAnimationFinished() {
        if (mRootActivityContainer.allResumedActivitiesIdle()
                || mStackSupervisor.isStoppingNoHistoryActivity()) {
            // If all activities are already idle or there is an activity that must be
            // stopped immediately after visible, then we now need to make sure we perform
            // the full stop of this activity. This is because we won't do that while they are still
            // waiting for the animation to finish.
            if (mStackSupervisor.mStoppingActivities.contains(this)) {
                mStackSupervisor.scheduleIdleLocked();
            }
        } else {
            // Instead of doing the full stop routine here, let's just hide any activities
            // we now can, and let them stop when the normal idle happens.
            mStackSupervisor.processStoppingActivitiesLocked(null /* idleActivity */,
                    false /* remove */, true /* processPausingActivities */);
        }
    }

    /**
     * Called when the key dispatching to a window associated with the app window container
     * timed-out.
     *
     * @param reason The reason for the key dispatching time out.
     * @param windowPid The pid of the window key dispatching timed out on.
     * @return True if input dispatching should be aborted.
     */
    public boolean keyDispatchingTimedOut(String reason, int windowPid) {
        ActivityRecord anrActivity;
        WindowProcessController anrApp;
        boolean windowFromSameProcessAsActivity;
        synchronized (mAtmService.mGlobalLock) {
            anrActivity = getWaitingHistoryRecordLocked();
            anrApp = app;
            windowFromSameProcessAsActivity =
                    !hasProcess() || app.getPid() == windowPid || windowPid == -1;
        }

        if (windowFromSameProcessAsActivity) {
            return mAtmService.mAmInternal.inputDispatchingTimedOut(anrApp.mOwner,
                    anrActivity.shortComponentName, anrActivity.info.applicationInfo,
                    shortComponentName, app, false, reason);
        } else {
            // In this case another process added windows using this activity token. So, we call the
            // generic service input dispatch timed out method so that the right process is blamed.
            return mAtmService.mAmInternal.inputDispatchingTimedOut(
                    windowPid, false /* aboveSystem */, reason) < 0;
        }
    }

    private ActivityRecord getWaitingHistoryRecordLocked() {
        // First find the real culprit...  if this activity has stopped, then the key dispatching
        // timeout should not be caused by this.
        if (stopped) {
            final ActivityStack stack = mRootActivityContainer.getTopDisplayFocusedStack();
            // Try to use the one which is closest to top.
            ActivityRecord r = stack.getResumedActivity();
            if (r == null) {
                r = stack.mPausingActivity;
            }
            if (r != null) {
                return r;
            }
        }
        return this;
    }

    /** Checks whether the activity should be shown for current user. */
    public boolean okToShowLocked() {
        // We cannot show activities when the device is locked and the application is not
        // encryption aware.
        if (!StorageManager.isUserKeyUnlocked(mUserId)
                && !info.applicationInfo.isEncryptionAware()) {
            return false;
        }

        return (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0
                || (mStackSupervisor.isCurrentProfileLocked(mUserId)
                && mAtmService.mAmInternal.isUserRunning(mUserId, 0 /* flags */));
    }

    /**
     * This method will return true if the activity is either visible, is becoming visible, is
     * currently pausing, or is resumed.
     */
    public boolean isInterestingToUserLocked() {
        return visible || nowVisible || mState == PAUSING || mState == RESUMED;
    }

    void setSleeping(boolean _sleeping) {
        setSleeping(_sleeping, false);
    }

    void setSleeping(boolean _sleeping, boolean force) {
        if (!force && sleeping == _sleeping) {
            return;
        }
        if (attachedToProcess()) {
            try {
                app.getThread().scheduleSleeping(appToken, _sleeping);
                if (_sleeping && !mStackSupervisor.mGoingToSleepActivities.contains(this)) {
                    mStackSupervisor.mGoingToSleepActivities.add(this);
                }
                sleeping = _sleeping;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception thrown when sleeping: " + intent.getComponent(), e);
            }
        }
    }

    static int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            return INVALID_TASK_ID;
        }
        final TaskRecord task = r.task;
        final int activityNdx = task.mActivities.indexOf(r);
        if (activityNdx < 0
                || (onlyRoot && activityNdx > task.findRootIndex(true /* effectiveRoot */))) {
            return INVALID_TASK_ID;
        }
        return task.taskId;
    }

    static ActivityRecord isInStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return (r != null) ? r.getActivityStack().isInStackLocked(r) : null;
    }

    static ActivityStack getStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null) {
            return r.getActivityStack();
        }
        return null;
    }

    /**
     * @return display id to which this record is attached,
     *         {@link android.view.Display#INVALID_DISPLAY} if not attached.
     */
    int getDisplayId() {
        final ActivityStack stack = getActivityStack();
        if (stack == null) {
            return INVALID_DISPLAY;
        }
        return stack.mDisplayId;
    }

    final boolean isDestroyable() {
        if (finishing || !hasProcess()) {
            // This would be redundant.
            return false;
        }
        final ActivityStack stack = getActivityStack();
        if (isState(RESUMED) || stack == null || this == stack.mPausingActivity || !mHaveState
                || !stopped) {
            // We're not ready for this kind of thing.
            return false;
        }
        if (visible) {
            // The user would notice this!
            return false;
        }
        return true;
    }

    private static String createImageFilename(long createTime, int taskId) {
        return String.valueOf(taskId) + ACTIVITY_ICON_SUFFIX + createTime +
                IMAGE_EXTENSION;
    }

    void setTaskDescription(TaskDescription _taskDescription) {
        Bitmap icon;
        if (_taskDescription.getIconFilename() == null &&
                (icon = _taskDescription.getIcon()) != null) {
            final String iconFilename = createImageFilename(createTime, task.taskId);
            final File iconFile = new File(TaskPersister.getUserImagesDir(task.userId),
                    iconFilename);
            final String iconFilePath = iconFile.getAbsolutePath();
            mAtmService.getRecentTasks().saveImage(icon, iconFilePath);
            _taskDescription.setIconFilename(iconFilePath);
        }
        taskDescription = _taskDescription;
    }

    void setVoiceSessionLocked(IVoiceInteractionSession session) {
        voiceSession = session;
        pendingVoiceInteractionStart = false;
    }

    void clearVoiceSessionLocked() {
        voiceSession = null;
        pendingVoiceInteractionStart = false;
    }

    void showStartingWindow(ActivityRecord prev, boolean newTask, boolean taskSwitch) {
        showStartingWindow(prev, newTask, taskSwitch, false /* fromRecents */);
    }

    void showStartingWindow(ActivityRecord prev, boolean newTask, boolean taskSwitch,
            boolean fromRecents) {
        if (mAppWindowToken == null) {
            return;
        }
        if (mTaskOverlay) {
            // We don't show starting window for overlay activities.
            return;
        }
        if (pendingOptions != null
                && pendingOptions.getAnimationType() == ActivityOptions.ANIM_SCENE_TRANSITION) {
            // Don't show starting window when using shared element transition.
            return;
        }

        final CompatibilityInfo compatInfo =
                mAtmService.compatibilityInfoForPackageLocked(info.applicationInfo);
        final boolean shown = addStartingWindow(packageName, theme,
                compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                prev != null ? prev.appToken : null, newTask, taskSwitch, isProcessRunning(),
                allowTaskSnapshot(),
                mState.ordinal() >= STARTED.ordinal() && mState.ordinal() <= STOPPED.ordinal(),
                fromRecents);
        if (shown) {
            mStartingWindowState = STARTING_WINDOW_SHOWN;
        }
    }

    void removeOrphanedStartingWindow(boolean behindFullscreenActivity) {
        if (mStartingWindowState == STARTING_WINDOW_SHOWN && behindFullscreenActivity) {
            if (DEBUG_VISIBILITY) Slog.w(TAG_VISIBILITY, "Found orphaned starting window " + this);
            mStartingWindowState = STARTING_WINDOW_REMOVED;
            mAppWindowToken.removeStartingWindow();
        }
    }

    void setRequestedOrientation(int requestedOrientation) {
        setOrientation(requestedOrientation, mayFreezeScreenLocked(app));
        mAtmService.getTaskChangeNotificationController().notifyActivityRequestedOrientationChanged(
                task.taskId, requestedOrientation);
    }

    private void setOrientation(int requestedOrientation, boolean freezeScreenIfNeeded) {
        if (mAppWindowToken == null) {
            Slog.w(TAG_WM,
                    "Attempted to set orientation of non-existing app token: " + appToken);
            return;
        }

        final IBinder binder =
                (freezeScreenIfNeeded && appToken != null) ? appToken.asBinder() : null;
        mAppWindowToken.setOrientation(requestedOrientation, binder, this);

        // Push the new configuration to the requested app in case where it's not pushed, e.g. when
        // the request is handled at task level with letterbox.
        if (!getMergedOverrideConfiguration().equals(
                mLastReportedConfiguration.getMergedConfiguration())) {
            ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        }
    }

    int getOrientation() {
        if (mAppWindowToken == null) {
            return info.screenOrientation;
        }

        return mAppWindowToken.getOrientationIgnoreVisibility();
    }

    void setDisablePreviewScreenshots(boolean disable) {
        if (mAppWindowToken == null) {
            Slog.w(TAG_WM, "Attempted to set disable screenshots of non-existing app"
                    + " token: " + appToken);
            return;
        }
        mAppWindowToken.setDisablePreviewScreenshots(disable);
    }

    /**
     * Set the last reported global configuration to the client. Should be called whenever a new
     * global configuration is sent to the client for this activity.
     */
    void setLastReportedGlobalConfiguration(@NonNull Configuration config) {
        mLastReportedConfiguration.setGlobalConfiguration(config);
    }

    /**
     * Set the last reported configuration to the client. Should be called whenever
     * a new merged configuration is sent to the client for this activity.
     */
    void setLastReportedConfiguration(@NonNull MergedConfiguration config) {
        setLastReportedConfiguration(config.getGlobalConfiguration(),
            config.getOverrideConfiguration());
    }

    private void setLastReportedConfiguration(Configuration global, Configuration override) {
        mLastReportedConfiguration.setConfiguration(global, override);
    }

    /**
     * Get the configuration orientation by the requested screen orientation
     * ({@link ActivityInfo.ScreenOrientation}) of this activity.
     *
     * @return orientation in ({@link Configuration#ORIENTATION_LANDSCAPE},
     *         {@link Configuration#ORIENTATION_PORTRAIT},
     *         {@link Configuration#ORIENTATION_UNDEFINED}).
     */
    int getRequestedConfigurationOrientation() {
        final int screenOrientation = getOrientation();
        if (screenOrientation == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR) {
            // NOSENSOR means the display's "natural" orientation, so return that.
            final ActivityDisplay display = getDisplay();
            if (display != null && display.mDisplayContent != null) {
                return display.mDisplayContent.getNaturalOrientation();
            }
        } else if (screenOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            // LOCKED means the activity's orientation remains unchanged, so return existing value.
            return getConfiguration().orientation;
        } else if (isFixedOrientationLandscape(screenOrientation)) {
            return ORIENTATION_LANDSCAPE;
        } else if (isFixedOrientationPortrait(screenOrientation)) {
            return ORIENTATION_PORTRAIT;
        }
        return ORIENTATION_UNDEFINED;
    }

    /**
     * @return {@code true} if this activity is in size compatibility mode that uses the different
     *         density or bounds from its parent.
     */
    boolean inSizeCompatMode() {
        if (!shouldUseSizeCompatMode()) {
            return false;
        }
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect resolvedAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
        if (resolvedAppBounds == null) {
            // The override configuration has not been resolved yet.
            return false;
        }

        final Configuration parentConfig = getParent().getConfiguration();
        // Although colorMode, screenLayout, smallestScreenWidthDp are also fixed, generally these
        // fields should be changed with density and bounds, so here only compares the most
        // significant field.
        if (parentConfig.densityDpi != resolvedConfig.densityDpi) {
            return true;
        }

        final Rect parentAppBounds = parentConfig.windowConfiguration.getAppBounds();
        final int appWidth = resolvedAppBounds.width();
        final int appHeight = resolvedAppBounds.height();
        final int parentAppWidth = parentAppBounds.width();
        final int parentAppHeight = parentAppBounds.height();
        if (parentAppWidth == appWidth && parentAppHeight == appHeight) {
            // Matched the parent bounds.
            return false;
        }
        if (parentAppWidth > appWidth && parentAppHeight > appHeight) {
            // Both sides are smaller than the parent.
            return true;
        }
        if (parentAppWidth < appWidth || parentAppHeight < appHeight) {
            // One side is larger than the parent.
            return true;
        }

        // The rest of the condition is that only one side is smaller than the parent, but it still
        // needs to exclude the cases where the size is limited by the fixed aspect ratio.
        if (info.maxAspectRatio > 0) {
            final float aspectRatio = (0.5f + Math.max(appWidth, appHeight))
                    / Math.min(appWidth, appHeight);
            if (aspectRatio >= info.maxAspectRatio) {
                // The current size has reached the max aspect ratio.
                return false;
            }
        }
        if (info.minAspectRatio > 0) {
            // The activity should have at least the min aspect ratio, so this checks if the parent
            // still has available space to provide larger aspect ratio.
            final float parentAspectRatio = (0.5f + Math.max(parentAppWidth, parentAppHeight))
                    / Math.min(parentAppWidth, parentAppHeight);
            if (parentAspectRatio <= info.minAspectRatio) {
                // The long side has reached the parent.
                return false;
            }
        }
        return true;
    }

    /**
     * Indicates the activity will keep the bounds and screen configuration when it was first
     * launched, no matter how its parent changes.
     *
     * @return {@code true} if this activity is declared as non-resizable and fixed orientation or
     *         aspect ratio.
     */
    boolean shouldUseSizeCompatMode() {
        return !isResizeable() && (info.isFixedOrientation() || info.hasFixedAspectRatio())
                // The configuration of non-standard type should be enforced by system.
                && isActivityTypeStandard()
                && !mAtmService.mForceResizableActivities;
    }

    // TODO(b/36505427): Consider moving this method and similar ones to ConfigurationContainer.
    private void updateOverrideConfiguration() {
        final Configuration overrideConfig = mTmpConfig;
        if (shouldUseSizeCompatMode()) {
            if (mCompatDisplayInsets != null) {
                // The override configuration is set only once in size compatibility mode.
                return;
            }
            final Configuration parentConfig = getParent().getConfiguration();
            if (!hasProcess() && !isConfigurationCompatible(parentConfig)) {
                // Don't compute when launching in fullscreen and the fixed orientation is not the
                // current orientation. It is more accurately to compute the override bounds from
                // the updated configuration after the fixed orientation is applied.
                return;
            }

            // Ensure the screen related fields are set. It is used to prevent activity relaunch
            // when moving between displays. For screenWidthDp and screenWidthDp, because they
            // are relative to bounds and density, they will be calculated in
            // {@link TaskRecord#computeConfigResourceOverrides} and the result will also be
            // relatively fixed.
            overrideConfig.unset();
            overrideConfig.colorMode = parentConfig.colorMode;
            overrideConfig.densityDpi = parentConfig.densityDpi;
            overrideConfig.screenLayout = parentConfig.screenLayout
                    & (Configuration.SCREENLAYOUT_LONG_MASK
                            | Configuration.SCREENLAYOUT_SIZE_MASK);
            // The smallest screen width is the short side of screen bounds. Because the bounds
            // and density won't be changed, smallestScreenWidthDp is also fixed.
            overrideConfig.smallestScreenWidthDp = parentConfig.smallestScreenWidthDp;

            // The role of CompatDisplayInsets is like the override bounds.
            final ActivityDisplay display = getDisplay();
            if (display != null && display.mDisplayContent != null) {
                mCompatDisplayInsets = new CompatDisplayInsets(display.mDisplayContent);
            }
        } else {
            // We must base this on the parent configuration, because we set our override
            // configuration's appBounds based on the result of this method. If we used our own
            // configuration, it would be influenced by past invocations.
            computeBounds(mTmpBounds, getParent().getWindowConfiguration().getAppBounds());

            if (mTmpBounds.equals(getRequestedOverrideBounds())) {
                // The bounds is not changed or the activity is resizable (both the 2 bounds are
                // empty).
                return;
            }

            overrideConfig.unset();
            overrideConfig.windowConfiguration.setBounds(mTmpBounds);
        }

        onRequestedOverrideConfigurationChanged(overrideConfig);
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfiguration) {
        if (mCompatDisplayInsets != null) {
            resolveSizeCompatModeConfiguration(newParentConfiguration);
        } else {
            super.resolveOverrideConfiguration(newParentConfiguration);
            // If the activity has override bounds, the relative configuration (e.g. screen size,
            // layout) needs to be resolved according to the bounds.
            if (!matchParentBounds()) {
                task.computeConfigResourceOverrides(getResolvedOverrideConfiguration(),
                        newParentConfiguration);
            }
        }

        // Assign configuration sequence number into hierarchy because there is a different way than
        // ensureActivityConfiguration() in this class that uses configuration in WindowState during
        // layout traversals.
        mConfigurationSeq = Math.max(++mConfigurationSeq, 1);
        getResolvedOverrideConfiguration().seq = mConfigurationSeq;
    }

    /**
     * Resolves consistent screen configuration for orientation and rotation changes without
     * inheriting the parent bounds.
     */
    private void resolveSizeCompatModeConfiguration(Configuration newParentConfiguration) {
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();

        final int parentRotation = newParentConfiguration.windowConfiguration.getRotation();
        final int parentOrientation = newParentConfiguration.orientation;
        int orientation = getConfiguration().orientation;
        if (orientation != parentOrientation && isConfigurationCompatible(newParentConfiguration)) {
            // The activity is compatible to apply the orientation change or it requests different
            // fixed orientation.
            orientation = parentOrientation;
        } else {
            if (!resolvedBounds.isEmpty()
                    // The decor insets may be different according to the rotation.
                    && getWindowConfiguration().getRotation() == parentRotation) {
                // Keep the computed resolved override configuration.
                return;
            }
            final int requestedOrientation = getRequestedConfigurationOrientation();
            if (requestedOrientation != ORIENTATION_UNDEFINED) {
                orientation = requestedOrientation;
            }
        }

        super.resolveOverrideConfiguration(newParentConfiguration);

        boolean useParentOverrideBounds = false;
        final Rect displayBounds = mTmpBounds;
        final Rect containingAppBounds = new Rect();
        if (task.handlesOrientationChangeFromDescendant()) {
            // Prefer to use the orientation which is determined by this activity to calculate
            // bounds because the parent will follow the requested orientation.
            mCompatDisplayInsets.getDisplayBoundsByOrientation(displayBounds, orientation);
        } else {
            // The parent hierarchy doesn't handle the orientation changes. This is usually because
            // the aspect ratio of display is close to square or the display rotation is fixed.
            // In this case, task will compute override bounds to fit the app with respect to the
            // requested orientation. So here we perform similar calculation to have consistent
            // bounds even the original parent hierarchies were changed.
            final int baseOrientation = task.getParent().getConfiguration().orientation;
            mCompatDisplayInsets.getDisplayBoundsByOrientation(displayBounds, baseOrientation);
            task.computeFullscreenBounds(containingAppBounds, this, displayBounds, baseOrientation);
            useParentOverrideBounds = !containingAppBounds.isEmpty();
        }

        // The offsets will be non-zero if the parent has override bounds.
        final int containingOffsetX = containingAppBounds.left;
        final int containingOffsetY = containingAppBounds.top;
        if (!useParentOverrideBounds) {
            containingAppBounds.set(displayBounds);
        }
        if (parentRotation != ROTATION_UNDEFINED) {
            // Ensure the container bounds won't overlap with the decors.
            TaskRecord.intersectWithInsetsIfFits(containingAppBounds, displayBounds,
                    mCompatDisplayInsets.mNonDecorInsets[parentRotation]);
        }

        computeBounds(resolvedBounds, containingAppBounds);
        if (resolvedBounds.isEmpty()) {
            // Use the entire available bounds because there is no restriction.
            resolvedBounds.set(useParentOverrideBounds ? containingAppBounds : displayBounds);
        } else {
            // The offsets are included in width and height by {@link #computeBounds}, so we have to
            // restore it.
            resolvedBounds.left += containingOffsetX;
            resolvedBounds.top += containingOffsetY;
        }
        task.computeConfigResourceOverrides(resolvedConfig, newParentConfiguration,
                mCompatDisplayInsets);

        // The horizontal inset included in width is not needed if the activity cannot fill the
        // parent, because the offset will be applied by {@link AppWindowToken#mSizeCompatBounds}.
        final Rect resolvedAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
        final Rect parentAppBounds = newParentConfiguration.windowConfiguration.getAppBounds();
        if (resolvedBounds.width() < parentAppBounds.width()) {
            resolvedBounds.right -= resolvedAppBounds.left;
        }
        // Use parent orientation if it cannot be decided by bounds, so the activity can fit inside
        // the parent bounds appropriately.
        if (resolvedConfig.screenWidthDp == resolvedConfig.screenHeightDp) {
            resolvedConfig.orientation = newParentConfiguration.orientation;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);

        // Configuration's equality doesn't consider seq so if only seq number changes in resolved
        // override configuration. Therefore ConfigurationContainer doesn't change merged override
        // configuration, but it's used to push configuration changes so explicitly update that.
        if (getMergedOverrideConfiguration().seq != getResolvedOverrideConfiguration().seq) {
            onMergedOverrideConfigurationChanged();
        }

        // TODO(b/80414790): Remove code below after unification.
        // Same as above it doesn't notify configuration listeners, and consequently AppWindowToken
        // can't get updated seq number. However WindowState's merged override configuration needs
        // to have this seq number because that's also used for activity config pushes during layout
        // traversal. Therefore explicitly update them here.
        if (mAppWindowToken == null) {
            return;
        }
        final Configuration appWindowTokenRequestedOverrideConfig =
                mAppWindowToken.getRequestedOverrideConfiguration();
        if (appWindowTokenRequestedOverrideConfig.seq != getResolvedOverrideConfiguration().seq) {
            appWindowTokenRequestedOverrideConfig.seq =
                    getResolvedOverrideConfiguration().seq;
            mAppWindowToken.onMergedOverrideConfigurationChanged();
        }

        final ActivityDisplay display = getDisplay();
        if (display == null) {
            return;
        }
        if (visible) {
            // It may toggle the UI for user to restart the size compatibility mode activity.
            display.handleActivitySizeCompatModeIfNeeded(this);
        } else if (shouldUseSizeCompatMode()) {
            // The override changes can only be obtained from display, because we don't have the
            // difference of full configuration in each hierarchy.
            final int displayChanges = display.getLastOverrideConfigurationChanges();
            final int orientationChanges = CONFIG_WINDOW_CONFIGURATION
                    | CONFIG_SCREEN_SIZE | CONFIG_ORIENTATION;
            final boolean hasNonOrienSizeChanged = hasResizeChange(displayChanges)
                    // Filter out the case of simple orientation change.
                    && (displayChanges & orientationChanges) != orientationChanges;
            // For background activity that uses size compatibility mode, if the size or density of
            // the display is changed, then reset the override configuration and kill the activity's
            // process if its process state is not important to user.
            if (hasNonOrienSizeChanged || (displayChanges & ActivityInfo.CONFIG_DENSITY) != 0) {
                restartProcessIfVisible();
            }
        }
    }

    /** Returns true if the configuration is compatible with this activity. */
    boolean isConfigurationCompatible(Configuration config) {
        final int orientation = getOrientation();
        if (isFixedOrientationPortrait(orientation)
                && config.orientation != ORIENTATION_PORTRAIT) {
            return false;
        }
        if (isFixedOrientationLandscape(orientation)
                && config.orientation != ORIENTATION_LANDSCAPE) {
            return false;
        }
        return true;
    }

    /**
     * Computes the bounds to fit the Activity within the bounds of the {@link Configuration}.
     */
    // TODO(b/36505427): Consider moving this method and similar ones to ConfigurationContainer.
    private void computeBounds(Rect outBounds, Rect containingAppBounds) {
        outBounds.setEmpty();
        final float maxAspectRatio = info.maxAspectRatio;
        final ActivityStack stack = getActivityStack();
        final float minAspectRatio = info.minAspectRatio;

        if (task == null || stack == null || task.inMultiWindowMode()
                || (maxAspectRatio == 0 && minAspectRatio == 0)
                || isInVrUiMode(getConfiguration())) {
            // We don't set override configuration if that activity task isn't fullscreen. I.e. the
            // activity is in multi-window mode. Or, there isn't a max aspect ratio specified for
            // the activity. This is indicated by an empty {@link outBounds}. We also don't set it
            // if we are in VR mode.
            return;
        }

        final int containingAppWidth = containingAppBounds.width();
        final int containingAppHeight = containingAppBounds.height();
        final float containingRatio = Math.max(containingAppWidth, containingAppHeight)
                / (float) Math.min(containingAppWidth, containingAppHeight);

        int activityWidth = containingAppWidth;
        int activityHeight = containingAppHeight;

        if (containingRatio > maxAspectRatio && maxAspectRatio != 0) {
            if (containingAppWidth < containingAppHeight) {
                // Width is the shorter side, so we use that to figure-out what the max. height
                // should be given the aspect ratio.
                activityHeight = (int) ((activityWidth * maxAspectRatio) + 0.5f);
            } else {
                // Height is the shorter side, so we use that to figure-out what the max. width
                // should be given the aspect ratio.
                activityWidth = (int) ((activityHeight * maxAspectRatio) + 0.5f);
            }
        } else if (containingRatio < minAspectRatio) {
            boolean adjustWidth;
            switch (getRequestedConfigurationOrientation()) {
                case ORIENTATION_LANDSCAPE:
                    // Width should be the longer side for this landscape app, so we use the width
                    // to figure-out what the max. height should be given the aspect ratio.
                    adjustWidth = false;
                    break;
                case ORIENTATION_PORTRAIT:
                    // Height should be the longer side for this portrait app, so we use the height
                    // to figure-out what the max. width should be given the aspect ratio.
                    adjustWidth = true;
                    break;
                default:
                    // This app doesn't have a preferred orientation, so we keep the length of the
                    // longer side, and use it to figure-out the length of the shorter side.
                    if (containingAppWidth < containingAppHeight) {
                        // Width is the shorter side, so we use the height to figure-out what the
                        // max. width should be given the aspect ratio.
                        adjustWidth = true;
                    } else {
                        // Height is the shorter side, so we use the width to figure-out what the
                        // max. height should be given the aspect ratio.
                        adjustWidth = false;
                    }
                    break;
            }
            if (adjustWidth) {
                activityWidth = (int) ((activityHeight / minAspectRatio) + 0.5f);
            } else {
                activityHeight = (int) ((activityWidth / minAspectRatio) + 0.5f);
            }
        }

        if (containingAppWidth <= activityWidth && containingAppHeight <= activityHeight) {
            // The display matches or is less than the activity aspect ratio, so nothing else to do.
            // Return the existing bounds. If this method is running for the first time,
            // {@link #getRequestedOverrideBounds()} will be empty (representing no override). If
            // the method has run before, then effect of {@link #getRequestedOverrideBounds()} will
            // already have been applied to the value returned from {@link getConfiguration}. Refer
            // to {@link TaskRecord#computeConfigResourceOverrides()}.
            outBounds.set(getRequestedOverrideBounds());
            return;
        }

        // Compute configuration based on max supported width and height.
        // Also account for the left / top insets (e.g. from display cutouts), which will be clipped
        // away later in {@link TaskRecord#computeConfigResourceOverrides()}. Otherwise, the app
        // bounds would end up too small.
        outBounds.set(0, 0, activityWidth + containingAppBounds.left,
                activityHeight + containingAppBounds.top);
    }

    /**
     * @return {@code true} if this activity was reparented to another display but
     *         {@link #ensureActivityConfiguration} is not called.
     */
    boolean shouldUpdateConfigForDisplayChanged() {
        return mLastReportedDisplayId != getDisplayId();
    }

    boolean ensureActivityConfiguration(int globalChanges, boolean preserveWindow) {
        return ensureActivityConfiguration(globalChanges, preserveWindow,
                false /* ignoreVisibility */);
    }

    /**
     * Make sure the given activity matches the current configuration. Ensures the HistoryRecord
     * is updated with the correct configuration and all other bookkeeping is handled.
     *
     * @param globalChanges The changes to the global configuration.
     * @param preserveWindow If the activity window should be preserved on screen if the activity
     *                       is relaunched.
     * @param ignoreVisibility If we should try to relaunch the activity even if it is invisible
     *                         (stopped state). This is useful for the case where we know the
     *                         activity will be visible soon and we want to ensure its configuration
     *                         before we make it visible.
     * @return False if the activity was relaunched and true if it wasn't relaunched because we
     *         can't or the app handles the specific configuration that is changing.
     */
    boolean ensureActivityConfiguration(int globalChanges, boolean preserveWindow,
            boolean ignoreVisibility) {
        final ActivityStack stack = getActivityStack();
        if (stack.mConfigWillChange) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Skipping config check (will change): " + this);
            return true;
        }

        // We don't worry about activities that are finishing.
        if (finishing) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Configuration doesn't matter in finishing " + this);
            stopFreezingScreenLocked(false);
            return true;
        }

        if (!ignoreVisibility && (mState == STOPPING || mState == STOPPED || !shouldBeVisible())) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Skipping config check invisible: " + this);
            return true;
        }

        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                "Ensuring correct configuration: " + this);

        final int newDisplayId = getDisplayId();
        final boolean displayChanged = mLastReportedDisplayId != newDisplayId;
        if (displayChanged) {
            mLastReportedDisplayId = newDisplayId;
        }
        // TODO(b/36505427): Is there a better place to do this?
        updateOverrideConfiguration();

        // Short circuit: if the two full configurations are equal (the common case), then there is
        // nothing to do.  We test the full configuration instead of the global and merged override
        // configurations because there are cases (like moving a task to the pinned stack) where
        // the combine configurations are equal, but would otherwise differ in the override config
        mTmpConfig.setTo(mLastReportedConfiguration.getMergedConfiguration());
        if (getConfiguration().equals(mTmpConfig) && !forceNewConfig && !displayChanged) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Configuration & display unchanged in " + this);
            return true;
        }

        // Okay we now are going to make this activity have the new config.
        // But then we need to figure out how it needs to deal with that.

        // Find changes between last reported merged configuration and the current one. This is used
        // to decide whether to relaunch an activity or just report a configuration change.
        final int changes = getConfigurationChanges(mTmpConfig);

        // Update last reported values.
        final Configuration newMergedOverrideConfig = getMergedOverrideConfiguration();

        setLastReportedConfiguration(mAtmService.getGlobalConfiguration(), newMergedOverrideConfig);

        if (mState == INITIALIZING) {
            // No need to relaunch or schedule new config for activity that hasn't been launched
            // yet. We do, however, return after applying the config to activity record, so that
            // it will use it for launch transaction.
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Skipping config check for initializing activity: " + this);
            return true;
        }

        if (changes == 0 && !forceNewConfig) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Configuration no differences in " + this);
            // There are no significant differences, so we won't relaunch but should still deliver
            // the new configuration to the client process.
            if (displayChanged) {
                scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig);
            } else {
                scheduleConfigurationChanged(newMergedOverrideConfig);
            }
            return true;
        }

        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                "Configuration changes for " + this + ", allChanges="
                        + Configuration.configurationDiffToString(changes));

        // If the activity isn't currently running, just leave the new configuration and it will
        // pick that up next time it starts.
        if (!attachedToProcess()) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Configuration doesn't matter not running " + this);
            stopFreezingScreenLocked(false);
            forceNewConfig = false;
            return true;
        }

        // Figure out how to handle the changes between the configurations.
        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                "Checking to restart " + info.name + ": changed=0x"
                        + Integer.toHexString(changes) + ", handles=0x"
                        + Integer.toHexString(info.getRealConfigChanged())
                        + ", mLastReportedConfiguration=" + mLastReportedConfiguration);

        if (shouldRelaunchLocked(changes, mTmpConfig) || forceNewConfig) {
            // Aha, the activity isn't handling the change, so DIE DIE DIE.
            configChangeFlags |= changes;
            startFreezingScreenLocked(app, globalChanges);
            forceNewConfig = false;
            preserveWindow &= isResizeOnlyChange(changes);
            final boolean hasResizeChange = hasResizeChange(changes & ~info.getRealConfigChanged());
            if (hasResizeChange) {
                final boolean isDragResizing =
                        getTaskRecord().getTask().isDragResizing();
                mRelaunchReason = isDragResizing ? RELAUNCH_REASON_FREE_RESIZE
                        : RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
            } else {
                mRelaunchReason = RELAUNCH_REASON_NONE;
            }
            if (!attachedToProcess()) {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is destroying non-running " + this);
                destroyImmediately(true /* removeFromApp */, "config");
            } else if (mState == PAUSING) {
                // A little annoying: we are waiting for this activity to finish pausing. Let's not
                // do anything now, but just flag that it needs to be restarted when done pausing.
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is skipping already pausing " + this);
                deferRelaunchUntilPaused = true;
                preserveWindowOnDeferredRelaunch = preserveWindow;
                return true;
            } else if (mState == RESUMED) {
                // Try to optimize this case: the configuration is changing and we need to restart
                // the top, resumed activity. Instead of doing the normal handshaking, just say
                // "restart!".
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is relaunching resumed " + this);

                if (DEBUG_STATES && !visible) {
                    Slog.v(TAG_STATES, "Config is relaunching resumed invisible activity " + this
                            + " called by " + Debug.getCallers(4));
                }

                relaunchActivityLocked(true /* andResume */, preserveWindow);
            } else {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is relaunching non-resumed " + this);
                relaunchActivityLocked(false /* andResume */, preserveWindow);
            }

            // All done...  tell the caller we weren't able to keep this activity around.
            return false;
        }

        // Default case: the activity can handle this new configuration, so hand it over.
        // NOTE: We only forward the override configuration as the system level configuration
        // changes is always sent to all processes when they happen so it can just use whatever
        // system level configuration it last got.
        if (displayChanged) {
            scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig);
        } else {
            scheduleConfigurationChanged(newMergedOverrideConfig);
        }
        stopFreezingScreenLocked(false);

        return true;
    }

    /**
     * When assessing a configuration change, decide if the changes flags and the new configurations
     * should cause the Activity to relaunch.
     *
     * @param changes the changes due to the given configuration.
     * @param changesConfig the configuration that was used to calculate the given changes via a
     *        call to getConfigurationChanges.
     */
    private boolean shouldRelaunchLocked(int changes, Configuration changesConfig) {
        int configChanged = info.getRealConfigChanged();
        boolean onlyVrUiModeChanged = onlyVrUiModeChanged(changes, changesConfig);

        // Override for apps targeting pre-O sdks
        // If a device is in VR mode, and we're transitioning into VR ui mode, add ignore ui mode
        // to the config change.
        // For O and later, apps will be required to add configChanges="uimode" to their manifest.
        if (info.applicationInfo.targetSdkVersion < O
                && requestedVrComponent != null
                && onlyVrUiModeChanged) {
            configChanged |= CONFIG_UI_MODE;
        }

        return (changes&(~configChanged)) != 0;
    }

    /**
     * Returns true if the configuration change is solely due to the UI mode switching into or out
     * of UI_MODE_TYPE_VR_HEADSET.
     */
    private boolean onlyVrUiModeChanged(int changes, Configuration lastReportedConfig) {
        final Configuration currentConfig = getConfiguration();
        return changes == CONFIG_UI_MODE && (isInVrUiMode(currentConfig)
            != isInVrUiMode(lastReportedConfig));
    }

    private int getConfigurationChanges(Configuration lastReportedConfig) {
        // Determine what has changed.  May be nothing, if this is a config that has come back from
        // the app after going idle.  In that case we just want to leave the official config object
        // now in the activity and do nothing else.
        final Configuration currentConfig = getConfiguration();
        int changes = lastReportedConfig.diff(currentConfig);
        // We don't want to use size changes if they don't cross boundaries that are important to
        // the app.
        if ((changes & CONFIG_SCREEN_SIZE) != 0) {
            final boolean crosses = crossesHorizontalSizeThreshold(lastReportedConfig.screenWidthDp,
                    currentConfig.screenWidthDp)
                    || crossesVerticalSizeThreshold(lastReportedConfig.screenHeightDp,
                    currentConfig.screenHeightDp);
            if (!crosses) {
                changes &= ~CONFIG_SCREEN_SIZE;
            }
        }
        if ((changes & CONFIG_SMALLEST_SCREEN_SIZE) != 0) {
            final int oldSmallest = lastReportedConfig.smallestScreenWidthDp;
            final int newSmallest = currentConfig.smallestScreenWidthDp;
            if (!crossesSmallestSizeThreshold(oldSmallest, newSmallest)) {
                changes &= ~CONFIG_SMALLEST_SCREEN_SIZE;
            }
        }
        // We don't want window configuration to cause relaunches.
        if ((changes & CONFIG_WINDOW_CONFIGURATION) != 0) {
            changes &= ~CONFIG_WINDOW_CONFIGURATION;
        }

        return changes;
    }

    private static boolean isResizeOnlyChange(int change) {
        return (change & ~(CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE | CONFIG_ORIENTATION
                | CONFIG_SCREEN_LAYOUT)) == 0;
    }

    private static boolean hasResizeChange(int change) {
        return (change & (CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE | CONFIG_ORIENTATION
                | CONFIG_SCREEN_LAYOUT)) != 0;
    }

    void relaunchActivityLocked(boolean andResume, boolean preserveWindow) {
        if (mAtmService.mSuppressResizeConfigChanges && preserveWindow) {
            configChangeFlags = 0;
            return;
        }

        List<ResultInfo> pendingResults = null;
        List<ReferrerIntent> pendingNewIntents = null;
        if (andResume) {
            pendingResults = results;
            pendingNewIntents = newIntents;
        }
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                "Relaunching: " + this + " with results=" + pendingResults
                        + " newIntents=" + pendingNewIntents + " andResume=" + andResume
                        + " preserveWindow=" + preserveWindow);
        EventLog.writeEvent(andResume ? AM_RELAUNCH_RESUME_ACTIVITY
                        : AM_RELAUNCH_ACTIVITY, mUserId, System.identityHashCode(this),
                task.taskId, shortComponentName);

        startFreezingScreenLocked(app, 0);

        try {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_SWITCH,
                    "Moving to " + (andResume ? "RESUMED" : "PAUSED") + " Relaunching " + this
                            + " callers=" + Debug.getCallers(6));
            forceNewConfig = false;
            mStackSupervisor.activityRelaunchingLocked(this);
            final ClientTransactionItem callbackItem = ActivityRelaunchItem.obtain(pendingResults,
                    pendingNewIntents, configChangeFlags,
                    new MergedConfiguration(mAtmService.getGlobalConfiguration(),
                            getMergedOverrideConfiguration()),
                    preserveWindow);
            final ActivityLifecycleItem lifecycleItem;
            if (andResume) {
                lifecycleItem = ResumeActivityItem.obtain(
                        getDisplay().mDisplayContent.isNextTransitionForward());
            } else {
                lifecycleItem = PauseActivityItem.obtain();
            }
            final ClientTransaction transaction = ClientTransaction.obtain(app.getThread(), appToken);
            transaction.addCallback(callbackItem);
            transaction.setLifecycleStateRequest(lifecycleItem);
            mAtmService.getLifecycleManager().scheduleTransaction(transaction);
            // Note: don't need to call pauseIfSleepingLocked() here, because the caller will only
            // request resume if this activity is currently resumed, which implies we aren't
            // sleeping.
        } catch (RemoteException e) {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_SWITCH, "Relaunch failed", e);
        }

        if (andResume) {
            if (DEBUG_STATES) {
                Slog.d(TAG_STATES, "Resumed after relaunch " + this);
            }
            results = null;
            newIntents = null;
            mAtmService.getAppWarningsLocked().onResumeActivity(this);
        } else {
            final ActivityStack stack = getActivityStack();
            if (stack != null) {
                stack.removePauseTimeoutForActivity(this);
            }
            setState(PAUSED, "relaunchActivityLocked");
        }

        configChangeFlags = 0;
        deferRelaunchUntilPaused = false;
        preserveWindowOnDeferredRelaunch = false;
    }

    /**
     * Request the process of the activity to restart with its saved state (from
     * {@link android.app.Activity#onSaveInstanceState}) if possible. It also forces to recompute
     * the override configuration. Note if the activity is in background, the process will be killed
     * directly with keeping its record.
     */
    void restartProcessIfVisible() {
        Slog.i(TAG, "Request to restart process of " + this);

        // Reset the existing override configuration so it can be updated according to the latest
        // configuration.
        getRequestedOverrideConfiguration().unset();
        getResolvedOverrideConfiguration().unset();
        mCompatDisplayInsets = null;
        if (visible) {
            // Configuration will be ensured when becoming visible, so if it is already visible,
            // then the manual update is needed.
            updateOverrideConfiguration();
        }

        if (!attachedToProcess()) {
            return;
        }

        // The restarting state avoids removing this record when process is died.
        setState(RESTARTING_PROCESS, "restartActivityProcess");

        if (!visible || mHaveState) {
            // Kill its process immediately because the activity should be in background.
            // The activity state will be update to {@link #DESTROYED} in
            // {@link ActivityStack#cleanUp} when handling process died.
            mAtmService.mH.post(() -> {
                final WindowProcessController wpc;
                synchronized (mAtmService.mGlobalLock) {
                    if (!hasProcess()
                            || app.getReportedProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND) {
                        return;
                    }
                    wpc = app;
                }
                mAtmService.mAmInternal.killProcess(wpc.mName, wpc.mUid, "resetConfig");
            });
            return;
        }

        if (mAppWindowToken != null) {
            mAppWindowToken.startFreezingScreen();
        }
        // The process will be killed until the activity reports stopped with saved state (see
        // {@link ActivityTaskManagerService.activityStopped}).
        try {
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    StopActivityItem.obtain(false /* showWindow */, 0 /* configChanges */));
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception thrown during restart " + this, e);
        }
        mStackSupervisor.scheduleRestartTimeout(this);
    }

    boolean isProcessRunning() {
        WindowProcessController proc = app;
        if (proc == null) {
            proc = mAtmService.mProcessNames.get(processName, info.applicationInfo.uid);
        }
        return proc != null && proc.hasThread();
    }

    /**
     * @return Whether a task snapshot starting window may be shown.
     */
    private boolean allowTaskSnapshot() {
        if (newIntents == null) {
            return true;
        }

        // Restrict task snapshot starting window to launcher start, or there is no intent at all
        // (eg. task being brought to front). If the intent is something else, likely the app is
        // going to show some specific page or view, instead of what's left last time.
        for (int i = newIntents.size() - 1; i >= 0; i--) {
            final Intent intent = newIntents.get(i);
            if (intent != null && !ActivityRecord.isMainIntent(intent)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the associated activity has the no history flag set on it.
     * {@code false} otherwise.
     */
    boolean isNoHistory() {
        return (intent.getFlags() & FLAG_ACTIVITY_NO_HISTORY) != 0
                || (info.flags & FLAG_NO_HISTORY) != 0;
    }

    void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        out.attribute(null, ATTR_ID, String.valueOf(createTime));
        out.attribute(null, ATTR_LAUNCHEDFROMUID, String.valueOf(launchedFromUid));
        if (launchedFromPackage != null) {
            out.attribute(null, ATTR_LAUNCHEDFROMPACKAGE, launchedFromPackage);
        }
        if (resolvedType != null) {
            out.attribute(null, ATTR_RESOLVEDTYPE, resolvedType);
        }
        out.attribute(null, ATTR_COMPONENTSPECIFIED, String.valueOf(componentSpecified));
        out.attribute(null, ATTR_USERID, String.valueOf(mUserId));

        if (taskDescription != null) {
            taskDescription.saveToXml(out);
        }

        out.startTag(null, TAG_INTENT);
        intent.saveToXml(out);
        out.endTag(null, TAG_INTENT);

        if (isPersistable() && mPersistentState != null) {
            out.startTag(null, TAG_PERSISTABLEBUNDLE);
            mPersistentState.saveToXml(out);
            out.endTag(null, TAG_PERSISTABLEBUNDLE);
        }
    }

    static ActivityRecord restoreFromXml(XmlPullParser in,
            ActivityStackSupervisor stackSupervisor) throws IOException, XmlPullParserException {
        Intent intent = null;
        PersistableBundle persistentState = null;
        int launchedFromUid = 0;
        String launchedFromPackage = null;
        String resolvedType = null;
        boolean componentSpecified = false;
        int userId = 0;
        long createTime = -1;
        final int outerDepth = in.getDepth();
        TaskDescription taskDescription = new TaskDescription();

        for (int attrNdx = in.getAttributeCount() - 1; attrNdx >= 0; --attrNdx) {
            final String attrName = in.getAttributeName(attrNdx);
            final String attrValue = in.getAttributeValue(attrNdx);
            if (DEBUG) Slog.d(TaskPersister.TAG,
                        "ActivityRecord: attribute name=" + attrName + " value=" + attrValue);
            if (ATTR_ID.equals(attrName)) {
                createTime = Long.parseLong(attrValue);
            } else if (ATTR_LAUNCHEDFROMUID.equals(attrName)) {
                launchedFromUid = Integer.parseInt(attrValue);
            } else if (ATTR_LAUNCHEDFROMPACKAGE.equals(attrName)) {
                launchedFromPackage = attrValue;
            } else if (ATTR_RESOLVEDTYPE.equals(attrName)) {
                resolvedType = attrValue;
            } else if (ATTR_COMPONENTSPECIFIED.equals(attrName)) {
                componentSpecified = Boolean.parseBoolean(attrValue);
            } else if (ATTR_USERID.equals(attrName)) {
                userId = Integer.parseInt(attrValue);
            } else if (attrName.startsWith(ATTR_TASKDESCRIPTION_PREFIX)) {
                taskDescription.restoreFromXml(attrName, attrValue);
            } else {
                Log.d(TAG, "Unknown ActivityRecord attribute=" + attrName);
            }
        }

        int event;
        while (((event = in.next()) != END_DOCUMENT) &&
                (event != END_TAG || in.getDepth() >= outerDepth)) {
            if (event == START_TAG) {
                final String name = in.getName();
                if (DEBUG)
                        Slog.d(TaskPersister.TAG, "ActivityRecord: START_TAG name=" + name);
                if (TAG_INTENT.equals(name)) {
                    intent = Intent.restoreFromXml(in);
                    if (DEBUG)
                            Slog.d(TaskPersister.TAG, "ActivityRecord: intent=" + intent);
                } else if (TAG_PERSISTABLEBUNDLE.equals(name)) {
                    persistentState = PersistableBundle.restoreFromXml(in);
                    if (DEBUG) Slog.d(TaskPersister.TAG,
                            "ActivityRecord: persistentState=" + persistentState);
                } else {
                    Slog.w(TAG, "restoreActivity: unexpected name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }

        if (intent == null) {
            throw new XmlPullParserException("restoreActivity error intent=" + intent);
        }

        final ActivityTaskManagerService service = stackSupervisor.mService;
        final ActivityInfo aInfo = stackSupervisor.resolveActivity(intent, resolvedType, 0, null,
                userId, Binder.getCallingUid());
        if (aInfo == null) {
            throw new XmlPullParserException("restoreActivity resolver error. Intent=" + intent +
                    " resolvedType=" + resolvedType);
        }
        final ActivityRecord r = new ActivityRecord(service, null /* caller */,
                0 /* launchedFromPid */, launchedFromUid, launchedFromPackage, intent, resolvedType,
                aInfo, service.getConfiguration(), null /* resultTo */, null /* resultWho */,
                0 /* reqCode */, componentSpecified, false /* rootVoiceInteraction */,
                stackSupervisor, null /* options */, null /* sourceRecord */);

        r.mPersistentState = persistentState;
        r.taskDescription = taskDescription;
        r.createTime = createTime;

        return r;
    }

    private static boolean isInVrUiMode(Configuration config) {
        return (config.uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_VR_HEADSET;
    }

    int getUid() {
        return info.applicationInfo.uid;
    }

    void setShowWhenLocked(boolean showWhenLocked) {
        mShowWhenLocked = showWhenLocked;
        mRootActivityContainer.ensureActivitiesVisible(null, 0 /* configChanges */,
                false /* preserveWindows */);
    }

    void setInheritShowWhenLocked(boolean inheritShowWhenLocked) {
        mInheritShownWhenLocked = inheritShowWhenLocked;
        mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
    }

    /**
     * @return true if the activity windowing mode is not
     *         {@link android.app.WindowConfiguration#WINDOWING_MODE_PINNED} and a) activity
     *         contains windows that have {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED} set or if the
     *         activity has set {@link #mShowWhenLocked}, or b) if the activity has set
     *         {@link #mInheritShownWhenLocked} and the activity behind this satisfies the
     *         conditions a) above.
     *         Multi-windowing mode will be exited if true is returned.
     */
    boolean canShowWhenLocked() {
        if (!inPinnedWindowingMode() && (mShowWhenLocked
                || (mAppWindowToken != null && mAppWindowToken.containsShowWhenLockedWindow()))) {
            return true;
        } else if (mInheritShownWhenLocked) {
            ActivityRecord r = getActivityBelow();
            return r != null && !r.inPinnedWindowingMode() && (r.mShowWhenLocked
                    || (r.mAppWindowToken != null
                        && r.mAppWindowToken.containsShowWhenLockedWindow()));
        } else {
            return false;
        }
    }

    /**
     * @return an {@link ActivityRecord} of the activity below this activity, or {@code null} if no
     * such activity exists.
     */
    @Nullable
    private ActivityRecord getActivityBelow() {
        final int pos = task.mActivities.indexOf(this);
        if (pos == -1) {
            throw new IllegalStateException("Activity not found in its task");
        }
        return pos == 0 ? null : task.getChildAt(pos - 1);
    }

    void setTurnScreenOn(boolean turnScreenOn) {
        mTurnScreenOn = turnScreenOn;
    }

    /**
     * Determines whether this ActivityRecord can turn the screen on. It checks whether the flag
     * {@link #mTurnScreenOn} is set and checks whether the ActivityRecord should be visible
     * depending on Keyguard state
     *
     * @return true if the screen can be turned on, false otherwise.
     */
    boolean canTurnScreenOn() {
        final ActivityStack stack = getActivityStack();
        return mTurnScreenOn && stack != null &&
                stack.checkKeyguardVisibility(this, true /* shouldBeVisible */, true /* isTop */);
    }

    /**
     * Check if this activity is able to resume. For pre-Q apps, only the topmost activities of each
     * process are allowed to be resumed.
     *
     * @return true if this activity can be resumed.
     */
    boolean canResumeByCompat() {
        return app == null || app.updateTopResumingActivityInProcessIfNeeded(this);
    }

    boolean getTurnScreenOnFlag() {
        return mTurnScreenOn;
    }

    boolean isTopRunningActivity() {
        return mRootActivityContainer.topRunningActivity() == this;
    }

    /**
     * @return {@code true} if this is the resumed activity on its current display, {@code false}
     * otherwise.
     */
    boolean isResumedActivityOnDisplay() {
        final ActivityDisplay display = getDisplay();
        return display != null && this == display.getResumedActivity();
    }


    /**
     * Check if this is the root of the task - first activity that is not finishing, starting from
     * the bottom of the task. If all activities are finishing - then this method will return
     * {@code true} if the activity is at the bottom.
     *
     * NOTE: This is different from 'effective root' - an activity that defines the task identity.
     */
    boolean isRootOfTask() {
        if (task == null) {
            return false;
        }
        final ActivityRecord rootActivity = task.getRootActivity();
        if (rootActivity != null) {
            return this == rootActivity;
        }
        // No non-finishing activity found. In this case the bottom-most activity is considered to
        // be the root.
        return task.getChildAt(0) == this;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        if (mAppWindowToken == null) {
            Slog.w(TAG_WM, "Attempted to register remote animations with non-existing app"
                    + " token: " + appToken);
            return;
        }
        mAppWindowToken.registerRemoteAnimations(definition);
    }

    @Override
    public String toString() {
        if (stringName != null) {
            return stringName + " t" + (task == null ? INVALID_TASK_ID : task.taskId) +
                    (finishing ? " f}" : "}");
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ActivityRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" u");
        sb.append(mUserId);
        sb.append(' ');
        sb.append(intent.getComponent().flattenToShortString());
        stringName = sb.toString();
        return toString();
    }

    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, mUserId);
        proto.write(TITLE, intent.getComponent().flattenToShortString());
        proto.end(token);
    }

    /**
     * Write all fields to an {@code ActivityRecordProto}. This assumes the
     * {@code ActivityRecordProto} is the outer-most proto data.
     */
    void writeToProto(ProtoOutputStream proto) {
        super.writeToProto(proto, CONFIGURATION_CONTAINER, WindowTraceLogLevel.ALL);
        writeIdentifierToProto(proto, IDENTIFIER);
        proto.write(STATE, mState.toString());
        proto.write(VISIBLE, visible);
        proto.write(FRONT_OF_TASK, isRootOfTask());
        if (hasProcess()) {
            proto.write(PROC_ID, app.getPid());
        }
        proto.write(TRANSLUCENT, !fullscreen);
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        writeToProto(proto);
        proto.end(token);
    }

    /**
     * The precomputed insets of the display in each rotation. This is used to make the size
     * compatibility mode activity compute the configuration without relying on its current display.
     */
    static class CompatDisplayInsets {
        final int mDisplayWidth;
        final int mDisplayHeight;

        /**
         * The nonDecorInsets for each rotation. Includes the navigation bar and cutout insets. It
         * is used to compute the appBounds.
         */
        final Rect[] mNonDecorInsets = new Rect[4];
        /**
         * The stableInsets for each rotation. Includes the status bar inset and the
         * nonDecorInsets. It is used to compute {@link Configuration#screenWidthDp} and
         * {@link Configuration#screenHeightDp}.
         */
        final Rect[] mStableInsets = new Rect[4];

        CompatDisplayInsets(DisplayContent display) {
            mDisplayWidth = display.mBaseDisplayWidth;
            mDisplayHeight = display.mBaseDisplayHeight;
            final DisplayPolicy policy = display.getDisplayPolicy();
            for (int rotation = 0; rotation < 4; rotation++) {
                mNonDecorInsets[rotation] = new Rect();
                mStableInsets[rotation] = new Rect();
                final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
                final int dw = rotated ? mDisplayHeight : mDisplayWidth;
                final int dh = rotated ? mDisplayWidth : mDisplayHeight;
                final DisplayCutout cutout = display.calculateDisplayCutoutForRotation(rotation)
                        .getDisplayCutout();
                policy.getNonDecorInsetsLw(rotation, dw, dh, cutout, mNonDecorInsets[rotation]);
                mStableInsets[rotation].set(mNonDecorInsets[rotation]);
                policy.convertNonDecorInsetsToStableInsets(mStableInsets[rotation], rotation);
            }
        }

        void getDisplayBoundsByRotation(Rect outBounds, int rotation) {
            final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
            final int dw = rotated ? mDisplayHeight : mDisplayWidth;
            final int dh = rotated ? mDisplayWidth : mDisplayHeight;
            outBounds.set(0, 0, dw, dh);
        }

        void getDisplayBoundsByOrientation(Rect outBounds, int orientation) {
            final int longSide = Math.max(mDisplayWidth, mDisplayHeight);
            final int shortSide = Math.min(mDisplayWidth, mDisplayHeight);
            final boolean isLandscape = orientation == ORIENTATION_LANDSCAPE;
            outBounds.set(0, 0, isLandscape ? longSide : shortSide,
                    isLandscape ? shortSide : longSide);
        }
    }
}
