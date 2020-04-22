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
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_PICTURE_IN_PICTURE;
import static android.app.WaitResult.INVALID_DELAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
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
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.COLOR_MODE_DEFAULT;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_UNSET;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityRecordProto.ALL_DRAWN;
import static com.android.server.wm.ActivityRecordProto.APP_STOPPED;
import static com.android.server.wm.ActivityRecordProto.CLIENT_VISIBLE;
import static com.android.server.wm.ActivityRecordProto.DEFER_HIDING_CLIENT;
import static com.android.server.wm.ActivityRecordProto.FILLS_PARENT;
import static com.android.server.wm.ActivityRecordProto.FRONT_OF_TASK;
import static com.android.server.wm.ActivityRecordProto.FROZEN_BOUNDS;
import static com.android.server.wm.ActivityRecordProto.IDENTIFIER;
import static com.android.server.wm.ActivityRecordProto.IS_ANIMATING;
import static com.android.server.wm.ActivityRecordProto.IS_WAITING_FOR_TRANSITION_START;
import static com.android.server.wm.ActivityRecordProto.LAST_ALL_DRAWN;
import static com.android.server.wm.ActivityRecordProto.LAST_SURFACE_SHOWING;
import static com.android.server.wm.ActivityRecordProto.NAME;
import static com.android.server.wm.ActivityRecordProto.NUM_DRAWN_WINDOWS;
import static com.android.server.wm.ActivityRecordProto.NUM_INTERESTING_WINDOWS;
import static com.android.server.wm.ActivityRecordProto.PROC_ID;
import static com.android.server.wm.ActivityRecordProto.REPORTED_DRAWN;
import static com.android.server.wm.ActivityRecordProto.REPORTED_VISIBLE;
import static com.android.server.wm.ActivityRecordProto.STARTING_DISPLAYED;
import static com.android.server.wm.ActivityRecordProto.STARTING_MOVED;
import static com.android.server.wm.ActivityRecordProto.STARTING_WINDOW;
import static com.android.server.wm.ActivityRecordProto.STATE;
import static com.android.server.wm.ActivityRecordProto.THUMBNAIL;
import static com.android.server.wm.ActivityRecordProto.TRANSLUCENT;
import static com.android.server.wm.ActivityRecordProto.VISIBLE;
import static com.android.server.wm.ActivityRecordProto.VISIBLE_REQUESTED;
import static com.android.server.wm.ActivityRecordProto.VISIBLE_SET_FROM_TRANSFERRED_STARTING_WINDOW;
import static com.android.server.wm.ActivityRecordProto.WINDOW_TOKEN;
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
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
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
import static com.android.server.wm.ActivityTaskManagerService.getInputDispatchingTimeoutLocked;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS_ANIM;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_STARTING_WINDOW;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.TaskPersister.DEBUG;
import static com.android.server.wm.TaskPersister.IMAGE_EXTENSION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.ACTIVITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowState.LEGACY_POLICY_VISIBILITY;
import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.app.Activity;
import android.app.ActivityManager;
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
import android.app.servertransaction.StartActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.app.servertransaction.TopResumedActivityChangeItem;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
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
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.service.dreams.DreamActivity;
import android.service.dreams.DreamManagerInternal;
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
import android.view.DisplayInfo;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IApplicationToken;
import android.view.InputApplicationHandle;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.window.WindowContainerToken;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AttributeCache;
import com.android.server.LocalServices;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.PendingIntentRecord;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.protolog.common.ProtoLog;
import com.android.server.uri.UriPermissionOwner;
import com.android.server.wm.ActivityMetricsLogger.TransitionInfoSnapshot;
import com.android.server.wm.ActivityStack.ActivityState;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.WindowManagerService.H;
import com.android.server.wm.utils.InsetUtils;

import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An entry in the history stack, representing an activity.
 */
final class ActivityRecord extends WindowToken implements WindowManagerService.AppFreezeListener {
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
    private static final String ATTR_LAUNCHEDFROMFEATURE = "launched_from_feature";
    private static final String ATTR_RESOLVEDTYPE = "resolved_type";
    private static final String ATTR_COMPONENTSPECIFIED = "component_specified";
    static final String ACTIVITY_ICON_SUFFIX = "_activity_icon_";

    // How many activities have to be scheduled to stop to force a stop pass.
    private static final int MAX_STOPPING_TO_FORCE = 3;

    private static final int STARTING_WINDOW_TYPE_NONE = 0;
    private static final int STARTING_WINDOW_TYPE_SNAPSHOT = 1;
    private static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 2;

    /**
     * Value to increment the z-layer when boosting a layer during animations. BOOST in l33tsp34k.
     */
    @VisibleForTesting static final int Z_BOOST_BASE = 800570000;
    static final int INVALID_PID = -1;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    private static final int PAUSE_TIMEOUT = 500;

    // Ticks during which we check progress while waiting for an app to launch.
    private static final int LAUNCH_TICK = 500;

    // How long we wait for the activity to tell us it has stopped before
    // giving up.  This is a good amount of time because we really need this
    // from the application in order to get its saved state. Once the stop
    // is complete we may start destroying client resources triggering
    // crashes if the UI thread was hung. We put this timeout one second behind
    // the ANR timeout so these situations will generate ANR instead of
    // Surface lost or other errors.
    private static final int STOP_TIMEOUT = 11 * 1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    private static final int DESTROY_TIMEOUT = 10 * 1000;

    final ActivityTaskManagerService mAtmService;
    final ActivityInfo info; // activity info provided by developer in AndroidManifest
    // Non-null only for application tokens.
    // TODO: rename to mActivityToken
    final ActivityRecord.Token appToken;
    // Which user is this running for?
    final int mUserId;
    // The package implementing intent's component
    // TODO: rename to mPackageName
    final String packageName;
    // the intent component, or target of an alias.
    final ComponentName mActivityComponent;
    // Has a wallpaper window as a background.
    // TODO: Rename to mHasWallpaper and also see if it possible to combine this with the
    // mOccludesParent field.
    final boolean hasWallpaper;
    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    final int launchedFromPid; // always the pid who started the activity.
    final int launchedFromUid; // always the uid who started the activity.
    final String launchedFromPackage; // always the package who started the activity.
    final @Nullable String launchedFromFeatureId; // always the feature in launchedFromPackage
    final Intent intent;    // the original intent that generated us
    final String shortComponentName; // the short component name of the intent
    final String resolvedType; // as per original caller;
    final String processName; // process where this component wants to run
    final String taskAffinity; // as per ActivityInfo.taskAffinity
    final boolean stateNotNeeded; // As per ActivityInfo.flags
    @VisibleForTesting
    int mHandoverLaunchDisplayId = INVALID_DISPLAY; // Handover launch display id to next activity.
    @VisibleForTesting
    TaskDisplayArea mHandoverTaskDisplayArea; // Handover launch task display area.
    private final boolean componentSpecified;  // did caller specify an explicit component?
    final boolean rootVoiceInteraction;  // was this the root activity of a voice interaction?

    private CharSequence nonLocalizedLabel;  // the label information from the package mgr.
    private int labelRes;           // the label information from the package mgr.
    private int icon;               // resource identifier of activity's icon.
    private int logo;               // resource identifier of activity's logo.
    private int theme;              // resource identifier of activity's theme.
    private int windowFlags;        // custom window flags for preview window.
    private Task task;              // the task this is in.
    private long createTime = System.currentTimeMillis();
    long lastVisibleTime;         // last time this activity became visible
    long cpuTimeAtResume;         // the cpu time of host process at the time of resuming activity
    long pauseTime;               // last time we started pausing the activity
    long launchTickTime;          // base time for launch tick messages
    long topResumedStateLossTime; // last time we reported top resumed state loss to an activity
    // Last configuration reported to the activity in the client process.
    private MergedConfiguration mLastReportedConfiguration;
    private int mLastReportedDisplayId;
    boolean mLastReportedMultiWindowMode;
    boolean mLastReportedPictureInPictureMode;
    CompatibilityInfo compat;// last used compatibility mode
    ActivityRecord resultTo; // who started this entry, so will get our reply
    final String resultWho; // additional identifier for use by resultTo.
    final int requestCode;  // code given by requester (resultTo)
    ArrayList<ResultInfo> results; // pending ActivityResult objs we have received
    HashSet<WeakReference<PendingIntentRecord>> pendingResults; // all pending intents for this act
    ArrayList<ReferrerIntent> newIntents; // any pending new intents for single-top mode
    Intent mLastNewIntent;  // the last new intent we delivered to client
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
    private boolean mVisible;        // Should this token's windows be visible?
    boolean visibleIgnoringKeyguard; // is this activity visible, ignoring the fact that Keyguard
                                     // might hide this activity?
    // True if the visible state of this token was forced to true due to a transferred starting
    // window.
    private boolean mVisibleSetFromTransferredStartingWindow;
    // TODO: figure out how to consolidate with the same variable in ActivityRecord.
    private boolean mDeferHidingClient; // If true we told WM to defer reporting to the client
                                        // process that it is hidden.
    private boolean mLastDeferHidingClient; // If true we will defer setting mClientVisible to false
                                           // and reporting to the client that it is hidden.
    private boolean mSetToSleep; // have we told the activity to sleep?
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

    boolean inHistory;  // are we in the history stack?
    final ActivityStackSupervisor mStackSupervisor;
    final RootWindowContainer mRootWindowContainer;

    static final int STARTING_WINDOW_NOT_SHOWN = 0;
    static final int STARTING_WINDOW_SHOWN = 1;
    static final int STARTING_WINDOW_REMOVED = 2;
    int mStartingWindowState = STARTING_WINDOW_NOT_SHOWN;
    private boolean mTaskOverlay = false; // Task is always on-top of other activities in the task.

    // Marking the reason why this activity is being relaunched. Mainly used to track that this
    // activity is being relaunched to fulfill a resize request due to compatibility issues, e.g. in
    // pre-NYC apps that don't have a sense of being resized.
    int mRelaunchReason = RELAUNCH_REASON_NONE;

    TaskDescription taskDescription; // the recents information for this activity

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

    boolean mVoiceInteraction;

    private int mPendingRelaunchCount;

    // True if we are current in the process of removing this app token from the display
    private boolean mRemovingFromDisplay = false;

    private RemoteAnimationDefinition mRemoteAnimationDefinition;

    AnimatingActivityRegistry mAnimatingActivityRegistry;

    private Task mLastParent;

    // Have we told the window clients to show themselves?
    private boolean mClientVisible;

    boolean firstWindowDrawn;
    // Last drawn state we reported to the app token.
    private boolean reportedDrawn;
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults =
            new WindowState.UpdateReportedVisibilityResults();

    boolean mUseTransferredAnimation;

    /**
     * @see #currentLaunchCanTurnScreenOn()
     */
    private boolean mCurrentLaunchCanTurnScreenOn = true;

    /** Whether our surface was set to be showing in the last call to {@link #prepareSurfaces} */
    private boolean mLastSurfaceShowing = true;

    private Letterbox mLetterbox;

    /**
     * The activity is opaque and fills the entire space of this task.
     * @see WindowContainer#fillsParent()
     */
    private boolean mOccludesParent;

    // The input dispatching timeout for this application token in nanoseconds.
    long mInputDispatchingTimeoutNanos;

    private boolean mShowWhenLocked;
    private boolean mInheritShownWhenLocked;
    private boolean mTurnScreenOn;

    /** Have we been asked to have this token keep the screen frozen? */
    private boolean mFreezingScreen;

    // These are used for determining when all windows associated with
    // an activity have been drawn, so they can be made visible together
    // at the same time.
    // initialize so that it doesn't match mTransactionSequence which is an int.
    private long mLastTransactionSequence = Long.MIN_VALUE;
    private int mNumInterestingWindows;
    private int mNumDrawnWindows;
    boolean allDrawn;
    private boolean mLastAllDrawn;

    private boolean mLastContainsShowWhenLockedWindow;
    private boolean mLastContainsDismissKeyguardWindow;
    private boolean mLastContainsTurnScreenOnWindow;

    /**
     * A flag to determine if this AR is in the process of closing or entering PIP. This is needed
     * to help AR know that the app is in the process of closing but hasn't yet started closing on
     * the WM side.
     */
    private boolean mWillCloseOrEnterPip;

    /**
     * The scale to fit at least one side of the activity to its parent. If the activity uses
     * 1920x1080, and the actually size on the screen is 960x540, then the scale is 0.5.
     */
    private float mSizeCompatScale = 1f;
    /**
     * The bounds in global coordinates for activity in size compatibility mode.
     * @see ActivityRecord#hasSizeCompatBounds()
     */
    private Rect mSizeCompatBounds;

    // activity is not displayed?
    // TODO: rename to mNoDisplay
    @VisibleForTesting
    boolean noDisplay;
    boolean mShowForAllUsers;
    // TODO: Make this final
    int mTargetSdk;

    // Is this window's surface needed?  This is almost like visible, except
    // it will sometimes be true a little earlier: when the activity record has
    // been shown, but is still waiting for its app transition to execute
    // before making its windows shown.
    boolean mVisibleRequested;

    // Last visibility state we reported to the app token.
    boolean reportedVisible;

    boolean mDisablePreviewScreenshots;

    // Information about an application starting window if displayed.
    // Note: these are de-referenced before the starting window animates away.
    StartingData mStartingData;
    WindowState startingWindow;
    WindowManagerPolicy.StartingSurface startingSurface;
    boolean startingDisplayed;
    boolean startingMoved;

    // TODO: Have a WindowContainer state for tracking exiting/deferred removal.
    boolean mIsExiting;

    boolean mEnteringAnimation;

    boolean mAppStopped;
    // A hint to override the window specified rotation animation, or -1 to use the window specified
    // value. We use this so that we can select the right animation in the cases of starting
    // windows, where the app hasn't had time to set a value on the window.
    int mRotationAnimationHint = -1;

    ArrayDeque<Rect> mFrozenBounds = new ArrayDeque<>();
    ArrayDeque<Configuration> mFrozenMergedConfig = new ArrayDeque<>();

    private AppSaturationInfo mLastAppSaturationInfo;

    private final ColorDisplayService.ColorTransformController mColorTransformController =
            (matrix, translation) -> mWmService.mH.post(() -> {
                synchronized (mWmService.mGlobalLock) {
                    if (mLastAppSaturationInfo == null) {
                        mLastAppSaturationInfo = new AppSaturationInfo();
                    }

                    mLastAppSaturationInfo.setSaturation(matrix, translation);
                    updateColorTransform();
                }
            });

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

    private final Runnable mPauseTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // We don't at this point know if the activity is fullscreen,
            // so we need to be conservative and assume it isn't.
            Slog.w(TAG, "Activity pause timeout for " + ActivityRecord.this);
            synchronized (mAtmService.mGlobalLock) {
                if (hasProcess()) {
                    mAtmService.logAppTooSlow(app, pauseTime, "pausing " + ActivityRecord.this);
                }
                activityPaused(true);
            }
        }
    };

    private final Runnable mLaunchTickRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAtmService.mGlobalLock) {
                if (continueLaunchTicking()) {
                    mAtmService.logAppTooSlow(
                            app, launchTickTime, "launching " + ActivityRecord.this);
                }
            }
        }
    };

    private final Runnable mDestroyTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAtmService.mGlobalLock) {
                Slog.w(TAG, "Activity destroy timeout for " + ActivityRecord.this);
                destroyed("destroyTimeout");
            }
        }
    };

    private final Runnable mStopTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAtmService.mGlobalLock) {
                Slog.w(TAG, "Activity stop timeout for " + ActivityRecord.this);
                if (isInHistory()) {
                    activityStopped(
                            null /*icicle*/, null /*persistentState*/, null /*description*/);
                }
            }
        }
    };

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

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        final long now = SystemClock.uptimeMillis();
        pw.print(prefix); pw.print("packageName="); pw.print(packageName);
                pw.print(" processName="); pw.println(processName);
        pw.print(prefix); pw.print("launchedFromUid="); pw.print(launchedFromUid);
                pw.print(" launchedFromPackage="); pw.print(launchedFromPackage);
                pw.print(" launchedFromFeature="); pw.print(launchedFromFeatureId);
                pw.print(" userId="); pw.println(mUserId);
        pw.print(prefix); pw.print("app="); pw.println(app);
        pw.print(prefix); pw.println(intent.toInsecureString());
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
                        pw.print(" iconResource=");
                                pw.print(taskDescription.getIconResourcePackage());
                                pw.print("/");
                                pw.print(taskDescription.getIconResource());
                        pw.print(" iconFilename="); pw.print(taskDescription.getIconFilename());
                        pw.print(" primaryColor=");
                        pw.println(Integer.toHexString(taskDescription.getPrimaryColor()));
                        pw.print(prefix + " backgroundColor=");
                        pw.print(Integer.toHexString(taskDescription.getBackgroundColor()));
                        pw.print(" statusBarColor=");
                        pw.print(Integer.toHexString(taskDescription.getStatusBarColor()));
                        pw.print(" navigationBarColor=");
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
                    pw.println(intent.toShortString(false, true, false, false));
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
        pw.print(" setToSleep="); pw.print(mSetToSleep);
                pw.print(" idle="); pw.print(idle);
                pw.print(" mStartingWindowState=");
                pw.println(startingWindowStateToString(mStartingWindowState));
        pw.print(prefix); pw.print("occludesParent="); pw.print(occludesParent());
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
        super.dump(pw, prefix, dumpAll);
        if (mVoiceInteraction) {
            pw.println(prefix + "mVoiceInteraction=true");
        }
        pw.print(prefix); pw.print("mOccludesParent="); pw.print(mOccludesParent);
        pw.print(" mOrientation="); pw.println(mOrientation);
        pw.println(prefix + "mVisibleRequested=" + mVisibleRequested
                + " mVisible=" + mVisible + " mClientVisible=" + mClientVisible
                + ((mDeferHidingClient) ? " mDeferHidingClient=" + mDeferHidingClient : "")
                + " reportedDrawn=" + reportedDrawn + " reportedVisible=" + reportedVisible);
        if (paused) {
            pw.print(prefix); pw.print("paused="); pw.println(paused);
        }
        if (mAppStopped) {
            pw.print(prefix); pw.print("mAppStopped="); pw.println(mAppStopped);
        }
        if (mNumInterestingWindows != 0 || mNumDrawnWindows != 0
                || allDrawn || mLastAllDrawn) {
            pw.print(prefix); pw.print("mNumInterestingWindows=");
            pw.print(mNumInterestingWindows);
            pw.print(" mNumDrawnWindows="); pw.print(mNumDrawnWindows);
            pw.print(" allDrawn="); pw.print(allDrawn);
            pw.print(" lastAllDrawn="); pw.print(mLastAllDrawn);
            pw.println(")");
        }
        if (mStartingData != null || firstWindowDrawn || mIsExiting) {
            pw.print(prefix); pw.print("startingData="); pw.print(mStartingData);
            pw.print(" firstWindowDrawn="); pw.print(firstWindowDrawn);
            pw.print(" mIsExiting="); pw.println(mIsExiting);
        }
        if (startingWindow != null || startingSurface != null
                || startingDisplayed || startingMoved || mVisibleSetFromTransferredStartingWindow) {
            pw.print(prefix); pw.print("startingWindow="); pw.print(startingWindow);
            pw.print(" startingSurface="); pw.print(startingSurface);
            pw.print(" startingDisplayed="); pw.print(startingDisplayed);
            pw.print(" startingMoved="); pw.print(startingMoved);
            pw.println(" mVisibleSetFromTransferredStartingWindow="
                    + mVisibleSetFromTransferredStartingWindow);
        }
        if (!mFrozenBounds.isEmpty()) {
            pw.print(prefix); pw.print("mFrozenBounds="); pw.println(mFrozenBounds);
            pw.print(prefix); pw.print("mFrozenMergedConfig="); pw.println(mFrozenMergedConfig);
        }
        if (mPendingRelaunchCount != 0) {
            pw.print(prefix); pw.print("mPendingRelaunchCount="); pw.println(mPendingRelaunchCount);
        }
        if (mSizeCompatScale != 1f || mSizeCompatBounds != null) {
            pw.println(prefix + "mSizeCompatScale=" + mSizeCompatScale + " mSizeCompatBounds="
                    + mSizeCompatBounds);
        }
        if (mRemovingFromDisplay) {
            pw.println(prefix + "mRemovingFromDisplay=" + mRemovingFromDisplay);
        }
        if (lastVisibleTime != 0 || nowVisible) {
            pw.print(prefix); pw.print("nowVisible="); pw.print(nowVisible);
                    pw.print(" lastVisibleTime=");
                    if (lastVisibleTime == 0) pw.print("0");
                    else TimeUtils.formatDuration(lastVisibleTime, now, pw);
                    pw.println();
        }
        if (mDeferHidingClient) {
            pw.println(prefix + "mDeferHidingClient=" + mDeferHidingClient);
        }
        if (deferRelaunchUntilPaused || configChangeFlags != 0) {
            pw.print(prefix); pw.print("deferRelaunchUntilPaused=");
                    pw.print(deferRelaunchUntilPaused);
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

    void setAppTimeTracker(AppTimeTracker att) {
        appTimeTracker = att;
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
            if (!inMultiWindowMode && mLastReportedPictureInPictureMode) {
                updatePictureInPictureMode(null, false);
            } else {
                mLastReportedMultiWindowMode = inMultiWindowMode;
                scheduleMultiWindowModeChanged(getConfiguration());
            }
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

    Task getTask() {
        return task;
    }

    /**
     * Sets the Task on this activity for the purposes of re-use during launch where we will
     * re-use another activity instead of this one for the launch.
     */
    void setTaskForReuse(Task task) {
        this.task = task;
    }

    ActivityStack getStack() {
        return task != null ? task.getStack() : null;
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        final Task oldTask = oldParent != null ? (Task) oldParent : null;
        final Task newTask = newParent != null ? (Task) newParent : null;
        this.task = newTask;

        super.onParentChanged(newParent, oldParent);

        if (isPersistable()) {
            if (oldTask != null) {
                mAtmService.notifyTaskPersisterLocked(oldTask, false);
            }
            if (newTask != null) {
                mAtmService.notifyTaskPersisterLocked(newTask, false);
            }
        }

        if (oldParent == null && newParent != null) {
            // First time we are adding the activity to the system.
            mVoiceInteraction = newTask.voiceSession != null;
            mInputDispatchingTimeoutNanos = getInputDispatchingTimeoutLocked(this) * 1000000L;

            // TODO(b/36505427): Maybe this call should be moved inside
            // updateOverrideConfiguration()
            newTask.updateOverrideConfigurationFromLaunchBounds();
            // Make sure override configuration is up-to-date before using to create window
            // controller.
            updateSizeCompatMode();
            // When an activity is started directly into a split-screen fullscreen stack, we need to
            // update the initial multi-window modes so that the callbacks are scheduled correctly
            // when the user leaves that mode.
            mLastReportedMultiWindowMode = inMultiWindowMode();
            mLastReportedPictureInPictureMode = inPinnedWindowingMode();
        }

        // When the associated task is {@code null}, the {@link ActivityRecord} can no longer
        // access visual elements like the {@link DisplayContent}. We must remove any associations
        // such as animations.
        if (task == null) {
            // It is possible we have been marked as a closing app earlier. We must remove ourselves
            // from this list so we do not participate in any future animations.
            if (getDisplayContent() != null) {
                getDisplayContent().mClosingApps.remove(this);
            }
        } else if (mLastParent != null && mLastParent.getStack() != null) {
            task.getStack().mExitingActivities.remove(this);
        }
        final ActivityStack stack = getStack();

        // If we reparent, make sure to remove ourselves from the old animation registry.
        if (mAnimatingActivityRegistry != null) {
            mAnimatingActivityRegistry.notifyFinished(this);
        }
        mAnimatingActivityRegistry = stack != null
                ? stack.getAnimatingActivityRegistry()
                : null;

        mLastParent = task;

        updateColorTransform();

        if (oldTask != null) {
            oldTask.cleanUpActivityReferences(this);
        }
        if (newTask != null && isState(RESUMED)) {
            newTask.setResumedActivity(this, "onParentChanged");
        }

        if (stack != null && stack.topRunningActivity() == this) {
            // carry over the PictureInPictureParams to the parent stack without calling
            // TaskOrganizerController#dispatchTaskInfoChanged.
            // this is to ensure the stack holding up-to-dated pinned stack information
            // when activity is re-parented to enter pip mode, see also
            // RootWindowContainer#moveActivityToPinnedStack
            stack.mPictureInPictureParams.copyOnlySet(pictureInPictureArgs);
            // make ensure the TaskOrganizer still works after re-parenting
            if (firstWindowDrawn) {
                stack.setHasBeenVisible(true);
            }
        }
    }

    private void updateColorTransform() {
        if (mSurfaceControl != null && mLastAppSaturationInfo != null) {
            getPendingTransaction().setColorTransform(mSurfaceControl,
                    mLastAppSaturationInfo.mMatrix, mLastAppSaturationInfo.mTranslation);
            mWmService.scheduleAnimationLocked();
        }
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        DisplayContent prevDc = mDisplayContent;
        super.onDisplayChanged(dc);
        if (prevDc == null || prevDc == mDisplayContent) {
            return;
        }

        if (prevDc.mOpeningApps.remove(this)) {
            // Transfer opening transition to new display.
            mDisplayContent.mOpeningApps.add(this);
            mDisplayContent.prepareAppTransition(prevDc.mAppTransition.getAppTransition(), true);
            mDisplayContent.executeAppTransition();
        }

        prevDc.mClosingApps.remove(this);

        if (prevDc.mFocusedApp == this) {
            prevDc.setFocusedApp(null);
            if (dc.getTopMostActivity() == this) {
                dc.setFocusedApp(this);
            }
        }

        if (mLetterbox != null) {
            mLetterbox.onMovedToDisplay(mDisplayContent.getDisplayId());
        }
    }

    void layoutLetterbox(WindowState winHint) {
        final WindowState w = findMainWindow();
        if (w == null || winHint != null && w != winHint) {
            return;
        }
        final boolean surfaceReady = w.isDrawnLw()  // Regular case
                || w.mWinAnimator.mSurfaceDestroyDeferred  // The preserved surface is still ready.
                || w.isDragResizeChanged();  // Waiting for relayoutWindow to call preserveSurface.
        final boolean needsLetterbox = surfaceReady && w.isLetterboxedAppWindow() && fillsParent();
        if (needsLetterbox) {
            if (mLetterbox == null) {
                mLetterbox = new Letterbox(() -> makeChildSurface(null),
                        mWmService.mTransactionFactory);
                mLetterbox.attachInput(w);
            }
            getPosition(mTmpPoint);
            // Get the bounds of the "space-to-fill". The transformed bounds have the highest
            // priority because the activity is launched in a rotated environment. In multi-window
            // mode, the task-level represents this. In fullscreen-mode, the task container does
            // (since the orientation letterbox is also applied to the task).
            final Rect transformedBounds = getFixedRotationTransformDisplayBounds();
            final Rect spaceToFill = transformedBounds != null
                    ? transformedBounds
                    : inMultiWindowMode()
                            ? task.getBounds()
                            : getRootTask().getParent().getBounds();
            mLetterbox.layout(spaceToFill, w.getFrameLw(), mTmpPoint);
        } else if (mLetterbox != null) {
            mLetterbox.hide();
        }
    }

    void updateLetterboxSurface(WindowState winHint) {
        final WindowState w = findMainWindow();
        if (w != winHint && winHint != null && w != null) {
            return;
        }
        layoutLetterbox(winHint);
        if (mLetterbox != null && mLetterbox.needsApplySurfaceChanges()) {
            mLetterbox.applySurfaceChanges(getPendingTransaction());
        }
    }

    Rect getLetterboxInsets() {
        if (mLetterbox != null) {
            return mLetterbox.getInsets();
        } else {
            return new Rect();
        }
    }

    /** Gets the inner bounds of letterbox. The bounds will be empty if there is no letterbox. */
    void getLetterboxInnerBounds(Rect outBounds) {
        if (mLetterbox != null) {
            outBounds.set(mLetterbox.getInnerFrame());
        } else {
            outBounds.setEmpty();
        }
    }

    /**
     * @return {@code true} if there is a letterbox and any part of that letterbox overlaps with
     * the given {@code rect}.
     */
    boolean isLetterboxOverlappingWith(Rect rect) {
        return mLetterbox != null && mLetterbox.isOverlappingWith(rect);
    }

    static class Token extends IApplicationToken.Stub {
        private WeakReference<ActivityRecord> weakActivity;
        private final String name;
        private final String tokenString;

        Token(Intent intent) {
            name = intent.getComponent().flattenToShortString();
            tokenString = "Token{" + Integer.toHexString(System.identityHashCode(this)) + "}";
        }

        private void attach(ActivityRecord activity) {
            if (weakActivity != null) {
                throw new IllegalStateException("Already attached..." + this);
            }
            weakActivity = new WeakReference<>(activity);
        }

        private static @Nullable ActivityRecord tokenToActivityRecordLocked(Token token) {
            if (token == null) {
                return null;
            }
            ActivityRecord r = token.weakActivity.get();
            if (r == null || r.getRootTask() == null) {
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
            if (weakActivity != null) {
                sb.append(weakActivity.get());
            }
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
            int _launchedFromPid, int _launchedFromUid, String _launchedFromPackage,
            @Nullable String _launchedFromFeature, Intent _intent, String _resolvedType,
            ActivityInfo aInfo, Configuration _configuration, ActivityRecord _resultTo,
            String _resultWho, int _reqCode, boolean _componentSpecified,
            boolean _rootVoiceInteraction, ActivityStackSupervisor supervisor,
            ActivityOptions options, ActivityRecord sourceRecord) {
        super(_service.mWindowManager, new Token(_intent).asBinder(), TYPE_APPLICATION, true,
                null /* displayContent */, false /* ownerCanManageAppTokens */);

        mAtmService = _service;
        appToken = (Token) token;
        info = aInfo;
        mUserId = UserHandle.getUserId(info.applicationInfo.uid);
        packageName = info.applicationInfo.packageName;
        mInputApplicationHandle = new InputApplicationHandle(appToken);
        intent = _intent;

        // If the class name in the intent doesn't match that of the target, this is probably an
        // alias. We have to create a new ComponentName object to keep track of the real activity
        // name, so that FLAG_ACTIVITY_CLEAR_TOP is handled properly.
        if (info.targetActivity == null
                || (info.targetActivity.equals(intent.getComponent().getClassName())
                && (info.launchMode == LAUNCH_MULTIPLE
                || info.launchMode == LAUNCH_SINGLE_TOP))) {
            mActivityComponent = intent.getComponent();
        } else {
            mActivityComponent =
                    new ComponentName(info.packageName, info.targetActivity);
        }

        mTargetSdk = info.applicationInfo.targetSdkVersion;
        mShowForAllUsers = (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0;
        setOrientation(info.screenOrientation);
        mRotationAnimationHint = info.rotationAnimation;

        mShowWhenLocked = (aInfo.flags & ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0;
        mInheritShownWhenLocked = (aInfo.privateFlags & FLAG_INHERIT_SHOW_WHEN_LOCKED) != 0;
        mTurnScreenOn = (aInfo.flags & FLAG_TURN_SCREEN_ON) != 0;

        int realTheme = info.getThemeResource();
        if (realTheme == Resources.ID_NULL) {
            realTheme = aInfo.applicationInfo.targetSdkVersion < HONEYCOMB
                    ? android.R.style.Theme : android.R.style.Theme_Holo;
        }

        final AttributeCache.Entry ent = AttributeCache.instance().get(packageName,
                realTheme, com.android.internal.R.styleable.Window, mUserId);

        if (ent != null) {
            mOccludesParent = !ActivityInfo.isTranslucentOrFloating(ent.array);
            hasWallpaper = ent.array.getBoolean(R.styleable.Window_windowShowWallpaper, false);
            noDisplay = ent.array.getBoolean(R.styleable.Window_windowNoDisplay, false);
        } else {
            hasWallpaper = false;
            noDisplay = false;
        }

        if (options != null) {
            mLaunchTaskBehind = options.getLaunchTaskBehind();

            final int rotationAnimation = options.getRotationAnimationHint();
            // Only override manifest supplied option if set.
            if (rotationAnimation >= 0) {
                mRotationAnimationHint = rotationAnimation;
            }
        }

        // Application tokens start out hidden.
        setVisible(false);
        mVisibleRequested = false;

        ColorDisplayService.ColorDisplayServiceInternal cds = LocalServices.getService(
                ColorDisplayService.ColorDisplayServiceInternal.class);
        cds.attachColorTransformController(packageName, mUserId,
                new WeakReference<>(mColorTransformController));

        appToken.attach(this);

        mRootWindowContainer = _service.mRootWindowContainer;
        launchedFromPid = _launchedFromPid;
        launchedFromUid = _launchedFromUid;
        launchedFromPackage = _launchedFromPackage;
        launchedFromFeatureId = _launchedFromFeature;
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
        nowVisible = false;
        mDrawn = false;
        mClientVisible = true;
        idle = false;
        hasBeenLaunched = false;
        mStackSupervisor = supervisor;

        // b/35954083: Limit task affinity to uid to avoid various issues associated with sharing
        // affinity across uids.
        final String uid = Integer.toString(info.applicationInfo.uid);
        if (info.taskAffinity != null && !info.taskAffinity.startsWith(uid)) {
            info.taskAffinity = uid + ":" + info.taskAffinity;
        }
        taskAffinity = info.taskAffinity;
        if (info.windowLayout != null && info.windowLayout.windowLayoutAffinity != null
                && !info.windowLayout.windowLayoutAffinity.startsWith(uid)) {
            info.windowLayout.windowLayoutAffinity =
                    uid + ":" + info.windowLayout.windowLayoutAffinity;
        }
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

        launchMode = aInfo.launchMode;

        setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);

        immersive = (aInfo.flags & FLAG_IMMERSIVE) != 0;

        requestedVrComponent = (aInfo.requestedVrComponent == null) ?
                null : ComponentName.unflattenFromString(aInfo.requestedVrComponent);

        lockTaskLaunchMode = getLockTaskLaunchMode(aInfo, options);

        if (options != null) {
            pendingOptions = options;
            final PendingIntent usageReport = pendingOptions.getUsageTimeReport();
            if (usageReport != null) {
                appTimeTracker = new AppTimeTracker(usageReport);
            }
            // Gets launch task display area and display id from options. Returns
            // null/INVALID_DISPLAY if not set.
            final WindowContainerToken daToken = options.getLaunchTaskDisplayArea();
            mHandoverTaskDisplayArea = daToken != null
                    ? (TaskDisplayArea) WindowContainer.fromBinder(daToken.asBinder()) : null;
            mHandoverLaunchDisplayId = options.getLaunchDisplayId();
        }
    }

    static int getLockTaskLaunchMode(ActivityInfo aInfo, @Nullable ActivityOptions options) {
        int lockTaskLaunchMode = aInfo.lockTaskLaunchMode;
        if (aInfo.applicationInfo.isPrivilegedApp()
                && (lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_ALWAYS
                || lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_NEVER)) {
            lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        }
        if (options != null) {
            final boolean useLockTask = options.getLockTaskMode();
            if (useLockTask && lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_DEFAULT) {
                lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED;
            }
        }
        return lockTaskLaunchMode;
    }

    @Override
    ActivityRecord asActivityRecord() {
        // I am an activity record!
        return this;
    }

    @Override
    boolean hasActivity() {
        // I am an activity!
        return true;
    }

    void setProcess(WindowProcessController proc) {
        app = proc;
        final ActivityRecord root = task != null ? task.getRootActivity() : null;
        if (root == this) {
            task.setRootProcess(proc);
        }
        proc.addActivityIfNeeded(this);
    }

    boolean hasProcess() {
        return app != null;
    }

    boolean attachedToProcess() {
        return hasProcess() && app.hasThread();
    }

    boolean addStartingWindow(String pkg, int theme, CompatibilityInfo compatInfo,
            CharSequence nonLocalizedLabel, int labelRes, int icon, int logo, int windowFlags,
            IBinder transferFrom, boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents) {
        // If the display is frozen, we won't do anything until the actual window is
        // displayed so there is no reason to put in the starting window.
        if (!okToDisplay()) {
            return false;
        }

        if (mStartingData != null) {
            return false;
        }

        final WindowState mainWin = findMainWindow();
        if (mainWin != null && mainWin.mWinAnimator.getShown()) {
            // App already has a visible window...why would you want a starting window?
            return false;
        }

        final ActivityManager.TaskSnapshot snapshot =
                mWmService.mTaskSnapshotController.getSnapshot(task.mTaskId, task.mUserId,
                        false /* restoreFromDisk */, false /* isLowResolution */);
        final int type = getStartingWindowType(newTask, taskSwitch, processRunning,
                allowTaskSnapshot, activityCreated, fromRecents, snapshot);

        if (type == STARTING_WINDOW_TYPE_SNAPSHOT) {
            if (isActivityTypeHome()) {
                // The snapshot of home is only used once because it won't be updated while screen
                // is on (see {@link TaskSnapshotController#screenTurningOff}).
                mWmService.mTaskSnapshotController.removeSnapshotCache(task.mTaskId);
                if ((mDisplayContent.mAppTransition.getTransitFlags()
                        & WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) == 0) {
                    // Only use snapshot of home as starting window when unlocking directly.
                    return false;
                }
            }
            return createSnapshot(snapshot);
        }

        // If this is a translucent window, then don't show a starting window -- the current
        // effect (a full-screen opaque starting window that fades away to the real contents
        // when it is ready) does not work for this.
        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Checking theme of starting window: 0x%x", theme);
        if (theme != 0) {
            AttributeCache.Entry ent = AttributeCache.instance().get(pkg, theme,
                    com.android.internal.R.styleable.Window,
                    mWmService.mCurrentUserId);
            if (ent == null) {
                // Whoops!  App doesn't exist. Um. Okay. We'll just pretend like we didn't
                // see that.
                return false;
            }
            final boolean windowIsTranslucent = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowIsTranslucent, false);
            final boolean windowIsFloating = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowIsFloating, false);
            final boolean windowShowWallpaper = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowShowWallpaper, false);
            final boolean windowDisableStarting = ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowDisablePreview, false);
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Translucent=%s Floating=%s ShowWallpaper=%s",
                    windowIsTranslucent, windowIsFloating, windowShowWallpaper);
            if (windowIsTranslucent) {
                return false;
            }
            if (windowIsFloating || windowDisableStarting) {
                return false;
            }
            if (windowShowWallpaper) {
                if (getDisplayContent().mWallpaperController
                        .getWallpaperTarget() == null) {
                    // If this theme is requesting a wallpaper, and the wallpaper
                    // is not currently visible, then this effectively serves as
                    // an opaque window and our starting window transition animation
                    // can still work.  We just need to make sure the starting window
                    // is also showing the wallpaper.
                    windowFlags |= FLAG_SHOW_WALLPAPER;
                } else {
                    return false;
                }
            }
        }

        if (transferStartingWindow(transferFrom)) {
            return true;
        }

        // There is no existing starting window, and we don't want to create a splash screen, so
        // that's it!
        if (type != STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
            return false;
        }

        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Creating SplashScreenStartingData");
        mStartingData = new SplashScreenStartingData(mWmService, pkg,
                theme, compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                getMergedOverrideConfiguration());
        scheduleAddStartingWindow();
        return true;
    }

    private boolean createSnapshot(ActivityManager.TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Creating SnapshotStartingData");
        mStartingData = new SnapshotStartingData(mWmService, snapshot);
        scheduleAddStartingWindow();
        return true;
    }

    void scheduleAddStartingWindow() {
        // Note: we really want to do sendMessageAtFrontOfQueue() because we
        // want to process the message ASAP, before any other queued
        // messages.
        if (!mWmService.mAnimationHandler.hasCallbacks(mAddStartingWindow)) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Enqueueing ADD_STARTING");
            mWmService.mAnimationHandler.postAtFrontOfQueue(mAddStartingWindow);
        }
    }

    private class AddStartingWindow implements Runnable {

        @Override
        public void run() {
            // Can be accessed without holding the global lock
            final StartingData startingData;
            synchronized (mWmService.mGlobalLock) {
                // There can only be one adding request, silly caller!
                mWmService.mAnimationHandler.removeCallbacks(this);

                if (mStartingData == null) {
                    // Animation has been canceled... do nothing.
                    ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                            "startingData was nulled out before handling"
                                    + " mAddStartingWindow: %s", ActivityRecord.this);
                    return;
                }
                startingData = mStartingData;
            }

            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Add starting %s: startingData=%s",
                    this, startingData);


            WindowManagerPolicy.StartingSurface surface = null;
            try {
                surface = startingData.createStartingSurface(ActivityRecord.this);
            } catch (Exception e) {
                Slog.w(TAG, "Exception when adding starting window", e);
            }
            if (surface != null) {
                boolean abort = false;
                synchronized (mWmService.mGlobalLock) {
                    // If the window was successfully added, then we need to remove it.
                    if (mStartingData == null) {
                        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Aborted starting %s: startingData=%s",
                                ActivityRecord.this, mStartingData);

                        startingWindow = null;
                        mStartingData = null;
                        abort = true;
                    } else {
                        startingSurface = surface;
                    }
                    if (!abort) {
                        ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                                "Added starting %s: startingWindow=%s startingView=%s",
                                ActivityRecord.this, startingWindow, startingSurface);
                    }
                }
                if (abort) {
                    surface.remove();
                }
            } else {
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Surface returned was null: %s",
                        ActivityRecord.this);
            }
        }
    }

    private final AddStartingWindow mAddStartingWindow = new AddStartingWindow();

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents,
            ActivityManager.TaskSnapshot snapshot) {
        if (getDisplayContent().mAppTransition.getAppTransition()
                == TRANSIT_DOCK_TASK_FROM_RECENTS) {
            // TODO(b/34099271): Remove this statement to add back the starting window and figure
            // out why it causes flickering, the starting window appears over the thumbnail while
            // the docked from recents transition occurs
            return STARTING_WINDOW_TYPE_NONE;
        } else if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
            return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else if (taskSwitch && allowTaskSnapshot) {
            if (snapshotOrientationSameAsTask(snapshot) || (snapshot != null && fromRecents)) {
                return STARTING_WINDOW_TYPE_SNAPSHOT;
            }
            if (!isActivityTypeHome()) {
                return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
            return STARTING_WINDOW_TYPE_NONE;
        } else {
            return STARTING_WINDOW_TYPE_NONE;
        }
    }

    private boolean snapshotOrientationSameAsTask(ActivityManager.TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return task.getConfiguration().orientation == snapshot.getOrientation();
    }

    void removeStartingWindow() {
        if (startingWindow == null) {
            if (mStartingData != null) {
                // Starting window has not been added yet, but it is scheduled to be added.
                // Go ahead and cancel the request.
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Clearing startingData for token=%s", this);
                mStartingData = null;
            }
            return;
        }

        final WindowManagerPolicy.StartingSurface surface;
        if (mStartingData != null) {
            surface = startingSurface;
            mStartingData = null;
            startingSurface = null;
            startingWindow = null;
            startingDisplayed = false;
            if (surface == null) {
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                        "startingWindow was set but startingSurface==null, couldn't "
                                + "remove");

                return;
            }
        } else {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                    "Tried to remove starting window but startingWindow was null: %s",
                    this);
            return;
        }

        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Schedule remove starting %s startingWindow=%s"
                        + " startingView=%s Callers=%s",
                this, startingWindow, startingSurface, Debug.getCallers(5));


        // Use the same thread to remove the window as we used to add it, as otherwise we end up
        // with things in the view hierarchy being called from different threads.
        mWmService.mAnimationHandler.post(() -> {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Removing startingView=%s", surface);
            try {
                surface.remove();
            } catch (Exception e) {
                Slog.w(TAG_WM, "Exception when removing starting window", e);
            }
        });
    }

    private void removeAppTokenFromDisplay() {
        if (mWmService.mRoot == null) return;

        final DisplayContent dc = mWmService.mRoot.getDisplayContent(getDisplayId());
        if (dc == null) {
            Slog.w(TAG, "removeAppTokenFromDisplay: Attempted to remove token: "
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
    void reparent(Task newTask, int position, String reason) {
        if (getParent() == null) {
            Slog.w(TAG, "reparent: Attempted to reparent non-existing app token: " + appToken);
            return;
        }
        final Task prevTask = task;
        if (prevTask == newTask) {
            throw new IllegalArgumentException(reason + ": task=" + newTask
                    + " is already the parent of r=" + this);
        }

        ProtoLog.i(WM_DEBUG_ADD_REMOVE, "reparent: moving activity=%s"
                + " to task=%d at %d", this, task.mTaskId, position);
        reparent(newTask, position);
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

    static boolean canLaunchDreamActivity(String packageName) {
        final DreamManagerInternal dreamManager =
                LocalServices.getService(DreamManagerInternal.class);

        // Verify that the package is the current active dream. The getActiveDreamComponent()
        // call path does not acquire the DreamManager lock and thus is safe to use.
        final ComponentName activeDream = dreamManager.getActiveDreamComponent(false /* doze */);
        if (activeDream == null || activeDream.getPackageName() == null
                || !activeDream.getPackageName().equals(packageName)) {
            return false;
        }

        // Verify that the device is dreaming.
        if (!LocalServices.getService(ActivityTaskManagerInternal.class).isDreaming()) {
            return false;
        }

        return true;
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
        } else if (options != null && options.getLaunchActivityType() == ACTIVITY_TYPE_DREAM
                && canLaunchDreamActivity(launchedFromPackage)
                && DreamActivity.class.getName() == info.name) {
            activityType = ACTIVITY_TYPE_DREAM;
        }
        setActivityType(activityType);
    }

    void setTaskToAffiliateWith(Task taskToAffiliateWith) {
        if (launchMode != LAUNCH_SINGLE_INSTANCE && launchMode != LAUNCH_SINGLE_TASK) {
            task.setTaskToAffiliateWith(taskToAffiliateWith);
        }
    }

    /** @return Root task of this activity, null if there is no task. */
    ActivityStack getRootTask() {
        return task != null ? (ActivityStack) task.getRootTask() : null;
    }

    int getRootTaskId() {
        return task != null ? task.getRootTaskId() : INVALID_TASK_ID;
    }

    DisplayContent getDisplay() {
        final ActivityStack stack = getRootTask();
        return stack != null ? stack.getDisplay() : null;
    }

    @Override
    @Nullable
    TaskDisplayArea getDisplayArea() {
        return (TaskDisplayArea) super.getDisplayArea();
    }

    @Override
    boolean fillsParent() {
        return occludesParent();
    }

    /** Returns true if this activity is opaque and fills the entire space of this task. */
    boolean occludesParent() {
        return !finishing && mOccludesParent;
    }

    boolean setOccludesParent(boolean occludesParent) {
        final boolean changed = occludesParent != mOccludesParent;
        mOccludesParent = occludesParent;
        setMainWindowOpaque(occludesParent);
        mWmService.mWindowPlacerLocked.requestTraversal();

        if (changed && task != null && !occludesParent) {
            getRootTask().convertActivityToTranslucent(this);
        }
        // Always ensure visibility if this activity doesn't occlude parent, so the
        // {@link #returningOptions} of the activity under this one can be applied in
        // {@link #handleAlreadyVisible()}.
        if (changed || !occludesParent) {
            mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        }
        return changed;
    }

    void setMainWindowOpaque(boolean isOpaque) {
        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        isOpaque = isOpaque & !PixelFormat.formatHasAlpha(win.getAttrs().format);
        win.mWinAnimator.setOpaqueLocked(isOpaque);
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
        final ActivityStack stack = getRootTask();
        return stack != null && stack.isInTask(this) != null;
    }

    boolean isPersistable() {
        return (info.persistableMode == PERSIST_ROOT_ONLY ||
                info.persistableMode == PERSIST_ACROSS_REBOOTS) &&
                (intent == null || (intent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0);
    }

    @Override
    boolean isFocusable() {
        return super.isFocusable()
                && (getWindowConfiguration().canReceiveKeys() || isAlwaysFocusable());
    }

    boolean isResizeable() {
        return ActivityInfo.isResizeableMode(info.resizeMode) || info.supportsPictureInPicture();
    }

    /** @return whether this activity is non-resizeable or forced to be resizeable */
    boolean isNonResizableOrForcedResizable(int windowingMode) {
        if (windowingMode == WINDOWING_MODE_PINNED && info.supportsPictureInPicture()) {
            return false;
        }
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
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        boolean hasPinnedStack = taskDisplayArea != null && taskDisplayArea.hasPinnedTask();
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
     * Sets if this {@link ActivityRecord} is in the process of closing or entering PIP.
     * {@link #mWillCloseOrEnterPip}}
     */
    void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        mWillCloseOrEnterPip = willCloseOrEnterPip;
    }

    /**
     * Returns whether this {@link ActivityRecord} is considered closing. Conditions are either
     * 1. Is this app animating and was requested to be hidden
     * 2. App is delayed closing since it might enter PIP.
     */
    boolean isClosingOrEnteringPip() {
        return (isAnimating(TRANSITION | PARENTS, ANIMATION_TYPE_APP_TRANSITION)
                && !mVisibleRequested) || mWillCloseOrEnterPip;
    }
    /**
     * @return Whether AppOps allows this package to enter picture-in-picture.
     */
    private boolean checkEnterPictureInPictureAppOpsState() {
        return mAtmService.getAppOpsManager().checkOpNoThrow(
                OP_PICTURE_IN_PICTURE, info.applicationInfo.uid, packageName) == MODE_ALLOWED;
    }

    private boolean isAlwaysFocusable() {
        return (info.flags & FLAG_ALWAYS_FOCUSABLE) != 0;
    }

    boolean windowsAreFocusable() {
        return windowsAreFocusable(false /* fromUserTouch */);
    }

    // TODO: Does this really need to be different from isAlwaysFocusable()? For the activity side
    // focusable means resumeable. I guess with that in mind maybe we should rename the other
    // method to isResumeable() or something like that.
    boolean windowsAreFocusable(boolean fromUserTouch) {
        if (!fromUserTouch && mTargetSdk < Build.VERSION_CODES.Q) {
            final int pid = getPid();
            final ActivityRecord topFocusedAppOfMyProcess =
                    mWmService.mRoot.mTopFocusedAppByProcess.get(pid);
            if (topFocusedAppOfMyProcess != null && topFocusedAppOfMyProcess != this) {
                // For the apps below Q, there can be only one app which has the focused window per
                // process, because legacy apps may not be ready for a multi-focus system.
                return false;
            }
        }
        return (getWindowConfiguration().canReceiveKeys() || isAlwaysFocusable())
                && getDisplay() != null;
    }

    /**
     * Move activity with its stack to front and make the stack focused.
     * @param reason the reason to move to top
     * @return {@code true} if the stack is focusable and has been moved to top or the activity
     *         is not yet resumed while the stack is already on top, {@code false} otherwise.
     */
    boolean moveFocusableActivityToTop(String reason) {
        if (!isFocusable()) {
            if (DEBUG_FOCUS) {
                Slog.d(TAG_FOCUS, "moveActivityStackToFront: unfocusable activity=" + this);
            }
            return false;
        }

        final ActivityStack stack = getRootTask();
        if (stack == null) {
            Slog.w(TAG, "moveActivityStackToFront: invalid task or stack: activity="
                    + this + " task=" + task);
            return false;
        }

        if (mRootWindowContainer.getTopResumedActivity() == this
                && getDisplayContent().mFocusedApp == this) {
            if (DEBUG_FOCUS) {
                Slog.d(TAG_FOCUS, "moveActivityStackToFront: already on top, activity=" + this);
            }
            return !isState(RESUMED);
        }

        if (DEBUG_FOCUS) {
            Slog.d(TAG_FOCUS, "moveActivityStackToFront: activity=" + this);
        }

        stack.moveToFront(reason, task);
        // Report top activity change to tracking services and WM
        if (mRootWindowContainer.getTopResumedActivity() == this) {
            mAtmService.setResumedActivityUncheckLocked(this, reason);
        }
        return true;
    }

    void finishIfSubActivity(ActivityRecord parent, String otherResultWho, int otherRequestCode) {
        if (resultTo != parent
                || requestCode != otherRequestCode
                || !Objects.equals(resultWho, otherResultWho)) return;

        finishIfPossible("request-sub", false /* oomAdj */);
    }

    /** Finish all activities in the task with the same affinity as this one. */
    boolean finishIfSameAffinity(ActivityRecord r) {
        // End search once we get to the activity that doesn't have the same affinity.
        if (!Objects.equals(r.taskAffinity, taskAffinity)) return true;

        r.finishIfPossible("request-affinity", true /* oomAdj */);
        return false;
    }

    /**
     * Sets the result for activity that started this one, clears the references to activities
     * started for result from this one, and clears new intents.
     */
    private void finishActivityResults(int resultCode, Intent resultData) {
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

        final ActivityStack stack = getRootTask();
        final boolean mayAdjustTop = (isState(RESUMED) || stack.mResumedActivity == null)
                && stack.isFocusedStackOnDisplay();
        final boolean shouldAdjustGlobalFocus = mayAdjustTop
                // It must be checked before {@link #makeFinishingLocked} is called, because a stack
                // is not visible if it only contains finishing activities.
                && mRootWindowContainer.isTopDisplayFocusedStack(stack);

        mAtmService.deferWindowLayout();
        try {
            makeFinishingLocked();
            // Make a local reference to its task since this.task could be set to null once this
            // activity is destroyed and detached from task.
            final Task task = getTask();
            EventLogTags.writeWmFinishActivity(mUserId, System.identityHashCode(this),
                    task.mTaskId, shortComponentName, reason);
            ActivityRecord next = task.getActivityAbove(this);
            if (next != null) {
                if ((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                    // If the caller asked that this activity (and all above it)
                    // be cleared when the task is reset, don't lose that information,
                    // but propagate it up to the next activity.
                    next.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                }
            }

            pauseKeyDispatchingLocked();

            // We are finishing the top focused activity and its task has nothing to be focused so
            // the next focusable task should be focused.
            if (mayAdjustTop && ((ActivityStack) task).topRunningActivity(true /* focusableOnly */)
                    == null) {
                task.adjustFocusToNextFocusableTask("finish-top", false /* allowFocusSelf */,
                        shouldAdjustGlobalFocus);
            }

            finishActivityResults(resultCode, resultData);

            final boolean endTask = task.getActivityBelow(this) == null
                    && !task.isClearingToReuseTask();
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
                    final ArraySet<Task> tasks = Sets.newArraySet(task);
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
                if (mVisibleRequested) {
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
                if (task.onlyHasTaskOverlayActivities(false /* includeFinishing */)) {
                    final PooledConsumer c = PooledLambda.obtainConsumer(
                            ActivityRecord::prepareActivityHideTransitionAnimationIfOvarlay,
                            PooledLambda.__(ActivityRecord.class), transit);
                    task.forAllActivities(c);
                    c.recycle();
                }
                return removedActivity ? FINISH_RESULT_REMOVED : FINISH_RESULT_REQUESTED;
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Finish waiting for pause of: " + this);
            }

            return FINISH_RESULT_REQUESTED;
        } finally {
            mAtmService.continueWindowLayout();
        }
    }

    private void prepareActivityHideTransitionAnimationIfOvarlay(int transit) {
        if (mTaskOverlay) {
            prepareActivityHideTransitionAnimation(transit);
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

        // Ensure activity visibilities and update lockscreen occluded/dismiss state when
        // finishing the top activity that occluded keyguard. So that, the
        // ActivityStack#mTopActivityOccludesKeyguard can be updated and the activity below won't
        // be resumed.
        if (isState(PAUSED)
                && mStackSupervisor.getKeyguardController().isKeyguardLocked()
                && getStack().topActivityOccludesKeyguard()) {
            getStack().ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                    false /* preserveWindows */, false /* notifyClients */);
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
        final ActivityRecord next = getDisplayArea().topRunningActivity(
                true /* considerKeyguardState */);
        // isNextNotYetVisible is to check if the next activity is invisible, or it has been
        // requested to be invisible but its windows haven't reported as invisible.  If so, it
        // implied that the current finishing activity should be added into stopping list rather
        // than destroy immediately.
        final boolean isNextNotYetVisible = next != null
                && (!next.nowVisible || !next.mVisibleRequested);
        if ((mVisibleRequested || isState(PAUSED)) && isNextNotYetVisible) {
            // Add this activity to the list of stopping activities. It will be processed and
            // destroyed when the next activity reports idle.
            addToStopping(false /* scheduleIdle */, false /* idleDelayed */,
                    "completeFinishing");
            setState(STOPPING, "completeFinishing");
        } else if (addToFinishingAndWaitForIdle()) {
            // We added this activity to the finishing list and something else is becoming resumed.
            // The activity will complete finishing when the next activity reports idle. No need to
            // do anything else here.
        } else {
            // Not waiting for the next one to become visible, and nothing else will be resumed in
            // place of this activity - requesting destruction right away.
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

        final ActivityStack stack = getRootTask();
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        // TODO(b/137329632): Exclude current activity when looking for the next one with
        // DisplayContent#topRunningActivity().
        final ActivityRecord next = taskDisplayArea.topRunningActivity();
        final boolean isLastStackOverEmptyHome =
                next == null && stack.isFocusedStackOnDisplay()
                        && taskDisplayArea.getOrCreateRootHomeTask() != null;
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
            mRootWindowContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                    false /* markFrozenIfConfigChanged */, true /* deferResume */);
        }
        if (activityRemoved) {
            mRootWindowContainer.resumeFocusedStacksTopActivities();
        }

        if (DEBUG_CONTAINERS) {
            Slog.d(TAG_CONTAINERS, "destroyIfPossible: r=" + this + " destroy returned removed="
                    + activityRemoved);
        }

        return activityRemoved;
    }

    /**
     * Add this activity to the list of finishing and trigger resuming of activities in focused
     * stacks.
     * @return {@code true} if some other activity is being resumed as a result of this call.
     */
    @VisibleForTesting
    boolean addToFinishingAndWaitForIdle() {
        if (DEBUG_STATES) Slog.v(TAG, "Enqueueing pending finish: " + this);
        setState(FINISHING, "addToFinishingAndWaitForIdle");
        if (!mStackSupervisor.mFinishingActivities.contains(this)) {
            mStackSupervisor.mFinishingActivities.add(this);
        }
        resumeKeyDispatchingLocked();
        return mRootWindowContainer.resumeFocusedStacksTopActivities();
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

        EventLogTags.writeWmDestroyActivity(mUserId, System.identityHashCode(this),
                task.mTaskId, shortComponentName, reason);

        boolean removedFromHistory = false;

        cleanUp(false /* cleanServices */, false /* setState */);

        if (hasProcess()) {
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
                mAtmService.mH.postDelayed(mDestroyTimeoutRunnable, DESTROY_TIMEOUT);
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

        return removedFromHistory;
    }

    boolean safelyDestroy(String reason) {
        if (isDestroyable()) {
            if (DEBUG_SWITCH) {
                final ActivityStack stack = getRootTask();
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
        removeTimeouts();
        if (DEBUG_STATES) {
            Slog.v(TAG_STATES, "Moving to DESTROYED: " + this + " (removed from history)");
        }
        setState(DESTROYED, "removeFromHistory");
        if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during remove for activity " + this);
        app = null;
        removeAppTokenFromDisplay();

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
    }

    /**
     * This method is to only be called from the client via binder when the activity is destroyed
     * AND finished.
     */
    void destroyed(String reason) {
        removeDestroyTimeout();

        if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS, "activityDestroyedLocked: r=" + this);

        if (!isState(DESTROYING, DESTROYED)) {
            throw new IllegalStateException(
                    "Reported destroyed for activity that is not destroying: r=" + this);
        }

        if (isInStackLocked()) {
            cleanUp(true /* cleanServices */, false /* setState */);
            removeFromHistory(reason);
        }

        mRootWindowContainer.resumeFocusedStacksTopActivities();
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
        task.cleanUpActivityReferences(this);

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
        removeTimeouts();
        // Clean-up activities are no longer relaunching (e.g. app process died). Notify window
        // manager so it can update its bookkeeping.
        clearRelaunching();
    }

    boolean isRelaunching() {
        return mPendingRelaunchCount > 0;
    }

    boolean shouldFreezeBounds() {
        // For freeform windows, we can't freeze the bounds at the moment because this would make
        // the resizing unresponsive.
        if (task == null || task.inFreeformWindowingMode()) {
            return false;
        }

        // We freeze the bounds while drag resizing to deal with the time between
        // the divider/drag handle being released, and the handling it's new
        // configuration. If we are relaunched outside of the drag resizing state,
        // we need to be careful not to do this.
        return task.isDragResizing();
    }

    void startRelaunching() {
        if (shouldFreezeBounds()) {
            freezeBounds();
        }

        // In the process of tearing down before relaunching, the app will
        // try and clean up it's child surfaces. We need to prevent this from
        // happening, so we sever the children, transfering their ownership
        // from the client it-self to the parent surface (owned by us).
        detachChildren();

        mPendingRelaunchCount++;
    }

    /**
     * Freezes the task bounds. The size of this task reported the app will be fixed to the bounds
     * freezed by {@link Task#prepareFreezingBounds} until {@link #unfreezeBounds} gets called, even
     * if they change in the meantime. If the bounds are already frozen, the bounds will be frozen
     * with a queue.
     */
    private void freezeBounds() {
        mFrozenBounds.offer(new Rect(task.mPreparedFrozenBounds));

        if (task.mPreparedFrozenMergedConfig.equals(Configuration.EMPTY)) {
            // We didn't call prepareFreezingBounds on the task, so use the current value.
            mFrozenMergedConfig.offer(new Configuration(task.getConfiguration()));
        } else {
            mFrozenMergedConfig.offer(new Configuration(task.mPreparedFrozenMergedConfig));
        }
        // Calling unset() to make it equal to Configuration.EMPTY.
        task.mPreparedFrozenMergedConfig.unset();
    }

    void detachChildren() {
        SurfaceControl.openTransaction();
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.mWinAnimator.detachChildren();
        }
        SurfaceControl.closeTransaction();
    }

    void finishRelaunching() {
        unfreezeBounds();

        if (mPendingRelaunchCount > 0) {
            mPendingRelaunchCount--;
        } else {
            // Update keyguard flags upon finishing relaunch.
            checkKeyguardFlagsChanged();
        }
    }

    void clearRelaunching() {
        if (mPendingRelaunchCount == 0) {
            return;
        }
        unfreezeBounds();
        mPendingRelaunchCount = 0;
    }

    /**
     * Unfreezes the previously frozen bounds. See {@link #freezeBounds}.
     */
    private void unfreezeBounds() {
        if (mFrozenBounds.isEmpty()) {
            return;
        }
        mFrozenBounds.remove();
        if (!mFrozenMergedConfig.isEmpty()) {
            mFrozenMergedConfig.remove();
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            win.onUnfreezeBounds();
        }
        mWmService.mWindowPlacerLocked.performSurfacePlacement();
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
        // This activity record is removing, make sure not to disconnect twice.
        mServiceConnectionsHolder = null;
    }

    @Override
    void removeImmediately() {
        onRemovedFromDisplay();
        super.removeImmediately();
    }

    @Override
    void removeIfPossible() {
        mIsExiting = false;
        removeAllWindowsIfPossible();
        removeImmediately();
    }

    @Override
    boolean checkCompleteDeferredRemoval() {
        if (mIsExiting) {
            removeIfPossible();
        }
        return super.checkCompleteDeferredRemoval();
    }

    void onRemovedFromDisplay() {
        if (mRemovingFromDisplay) {
            return;
        }
        mRemovingFromDisplay = true;

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Removing app token: %s", this);

        commitVisibility(false /* visible */, true /* performLayout */);

        getDisplayContent().mOpeningApps.remove(this);
        getDisplayContent().mChangingContainers.remove(this);
        getDisplayContent().mUnknownAppVisibilityController.appRemovedOrHidden(this);
        mWmService.mTaskSnapshotController.onAppRemoved(this);
        mStackSupervisor.getActivityMetricsLogger().notifyActivityRemoved(this);
        waitingToShow = false;

        // Defer removal of this activity when either a child is animating, or app transition is on
        // going. App transition animation might be applied on the parent stack not on the activity,
        // but the actual frame buffer is associated with the activity, so we have to keep the
        // activity while a parent is animating.
        boolean delayed = isAnimating(TRANSITION | PARENTS | CHILDREN,
                ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_WINDOW_ANIMATION);
        if (getDisplayContent().mClosingApps.contains(this)) {
            delayed = true;
        } else if (getDisplayContent().mAppTransition.isTransitionSet()) {
            getDisplayContent().mClosingApps.add(this);
            delayed = true;
        }

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "Removing app %s delayed=%b animation=%s animating=%b", this, delayed,
                getAnimation(),
                isAnimating(TRANSITION | PARENTS, ANIMATION_TYPE_APP_TRANSITION));

        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "removeAppToken: %s"
                + " delayed=%b Callers=%s", this, delayed, Debug.getCallers(4));

        if (mStartingData != null) {
            removeStartingWindow();
        }

        // If app transition animation was running for this activity, then we need to ensure that
        // the app transition notifies that animations have completed in
        // DisplayContent.handleAnimatingStoppedAndTransition(), so add to that list now
        if (isAnimating(TRANSITION | PARENTS, ANIMATION_TYPE_APP_TRANSITION)) {
            getDisplayContent().mNoAnimationNotifyOnTransitionFinished.add(token);
        }

        final ActivityStack stack = getStack();
        if (delayed && !isEmpty()) {
            // set the token aside because it has an active animation to be finished
            ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                    "removeAppToken make exiting: %s", this);
            if (stack != null) {
                stack.mExitingActivities.add(this);
            }
            mIsExiting = true;
        } else {
            // Make sure there is no animation running on this token, so any windows associated
            // with it will be removed as soon as their animations are complete
            cancelAnimation();
            if (stack != null) {
                stack.mExitingActivities.remove(this);
            }
            removeIfPossible();
        }

        stopFreezingScreen(true, true);

        final DisplayContent dc = getDisplayContent();
        if (dc.mFocusedApp == this) {
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT,
                    "Removing focused app token:%s displayId=%d", this,
                    dc.getDisplayId());
            dc.setFocusedApp(null);
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
        }
        if (mLetterbox != null) {
            mLetterbox.destroy();
            mLetterbox = null;
        }

        if (!delayed) {
            updateReportedVisibilityLocked();
        }

        // Reset the last saved PiP snap fraction on removal.
        mDisplayContent.mPinnedStackControllerLocked.onActivityHidden(mActivityComponent);
        mWmService.mEmbeddedWindowController.onActivityRemoved(this);
        mRemovingFromDisplay = false;
    }

    /**
     * Returns true if the new child window we are adding to this token is considered greater than
     * the existing child window in this token in terms of z-order.
     */
    @Override
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow,
            WindowState existingWindow) {
        final int type1 = newWindow.mAttrs.type;
        final int type2 = existingWindow.mAttrs.type;

        // Base application windows should be z-ordered BELOW all other windows in the app token.
        if (type1 == TYPE_BASE_APPLICATION && type2 != TYPE_BASE_APPLICATION) {
            return false;
        } else if (type1 != TYPE_BASE_APPLICATION && type2 == TYPE_BASE_APPLICATION) {
            return true;
        }

        // Starting windows should be z-ordered ABOVE all other windows in the app token.
        if (type1 == TYPE_APPLICATION_STARTING && type2 != TYPE_APPLICATION_STARTING) {
            return true;
        } else if (type1 != TYPE_APPLICATION_STARTING && type2 == TYPE_APPLICATION_STARTING) {
            return false;
        }

        // Otherwise the new window is greater than the existing window.
        return true;
    }

    /**
     * @return {@code true} if starting window is in app's hierarchy.
     */
    boolean hasStartingWindow() {
        if (startingDisplayed || mStartingData != null) {
            return true;
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (getChildAt(i).mAttrs.type == TYPE_APPLICATION_STARTING) {
                return true;
            }
        }
        return false;
    }

    boolean isLastWindow(WindowState win) {
        return mChildren.size() == 1 && mChildren.get(0) == win;
    }

    @Override
    void addWindow(WindowState w) {
        super.addWindow(w);

        boolean gotReplacementWindow = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState candidate = mChildren.get(i);
            gotReplacementWindow |= candidate.setReplacementWindowIfNeeded(w);
        }

        // if we got a replacement window, reset the timeout to give drawing more time
        if (gotReplacementWindow) {
            mWmService.scheduleWindowReplacementTimeouts(this);
        }
        checkKeyguardFlagsChanged();
    }

    @Override
    void removeChild(WindowState child) {
        if (!mChildren.contains(child)) {
            // This can be true when testing.
            return;
        }
        super.removeChild(child);
        checkKeyguardFlagsChanged();
        updateLetterboxSurface(child);
    }

    void onWindowReplacementTimeout() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            (mChildren.get(i)).onWindowReplacementTimeout();
        }
    }

    void setAppLayoutChanges(int changes, String reason) {
        if (!mChildren.isEmpty()) {
            final DisplayContent dc = getDisplayContent();
            dc.pendingLayoutChanges |= changes;
            if (DEBUG_LAYOUT_REPEATS) {
                mWmService.mWindowPlacerLocked.debugLayoutRepeats(reason, dc.pendingLayoutChanges);
            }
        }
    }

    void removeReplacedWindowIfNeeded(WindowState replacement) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            if (win.removeReplacedWindowIfNeeded(replacement)) {
                return;
            }
        }
    }

    boolean transferStartingWindow(IBinder transferFrom) {
        final ActivityRecord fromActivity = getDisplayContent().getActivityRecord(transferFrom);
        if (fromActivity == null) {
            return false;
        }

        final WindowState tStartingWindow = fromActivity.startingWindow;
        if (tStartingWindow != null && fromActivity.startingSurface != null) {
            // In this case, the starting icon has already been displayed, so start
            // letting windows get shown immediately without any more transitions.
            getDisplayContent().mSkipAppTransitionAnimation = true;

            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Moving existing starting %s"
                    + " from %s to %s", tStartingWindow, fromActivity, this);

            final long origId = Binder.clearCallingIdentity();
            try {
                // Transfer the starting window over to the new token.
                mStartingData = fromActivity.mStartingData;
                startingSurface = fromActivity.startingSurface;
                startingDisplayed = fromActivity.startingDisplayed;
                fromActivity.startingDisplayed = false;
                startingWindow = tStartingWindow;
                reportedVisible = fromActivity.reportedVisible;
                fromActivity.mStartingData = null;
                fromActivity.startingSurface = null;
                fromActivity.startingWindow = null;
                fromActivity.startingMoved = true;
                tStartingWindow.mToken = this;
                tStartingWindow.mActivityRecord = this;

                ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                        "Removing starting %s from %s", tStartingWindow, fromActivity);
                fromActivity.removeChild(tStartingWindow);
                fromActivity.postWindowRemoveStartingWindowCleanup(tStartingWindow);
                fromActivity.mVisibleSetFromTransferredStartingWindow = false;
                addWindow(tStartingWindow);

                // Propagate other interesting state between the tokens. If the old token is displayed,
                // we should immediately force the new one to be displayed. If it is animating, we need
                // to move that animation to the new one.
                if (fromActivity.allDrawn) {
                    allDrawn = true;
                }
                if (fromActivity.firstWindowDrawn) {
                    firstWindowDrawn = true;
                }
                if (fromActivity.isVisible()) {
                    setVisible(true);
                    mVisibleRequested = true;
                    mVisibleSetFromTransferredStartingWindow = true;
                }
                setClientVisible(fromActivity.mClientVisible);

                transferAnimation(fromActivity);

                // When transferring an animation, we no longer need to apply an animation to the
                // the token we transfer the animation over. Thus, set this flag to indicate we've
                // transferred the animation.
                mUseTransferredAnimation = true;

                mWmService.updateFocusedWindowLocked(
                        UPDATE_FOCUS_WILL_PLACE_SURFACES, true /*updateInputWindows*/);
                getDisplayContent().setLayoutNeeded();
                mWmService.mWindowPlacerLocked.performSurfacePlacement();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            return true;
        } else if (fromActivity.mStartingData != null) {
            // The previous app was getting ready to show a
            // starting window, but hasn't yet done so.  Steal it!
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                    "Moving pending starting from %s to %s", fromActivity, this);
            mStartingData = fromActivity.mStartingData;
            fromActivity.mStartingData = null;
            fromActivity.startingMoved = true;
            scheduleAddStartingWindow();
            return true;
        }

        // TODO: Transfer thumbnail

        return false;
    }

    /**
     * Tries to transfer the starting window from a token that's above ourselves in the task but
     * not visible anymore. This is a common scenario apps use: Trampoline activity T start main
     * activity M in the same task. Now, when reopening the task, T starts on top of M but then
     * immediately finishes after, so we have to transfer T to M.
     */
    void transferStartingWindowFromHiddenAboveTokenIfNeeded() {
        final PooledFunction p = PooledLambda.obtainFunction(ActivityRecord::transferStartingWindow,
                this, PooledLambda.__(ActivityRecord.class));
        task.forAllActivities(p);
        p.recycle();
    }

    private boolean transferStartingWindow(ActivityRecord fromActivity) {
        if (fromActivity == this) return true;

        return !fromActivity.mVisibleRequested && transferStartingWindow(fromActivity.token);
    }

    void checkKeyguardFlagsChanged() {
        final boolean containsDismissKeyguard = containsDismissKeyguardWindow();
        final boolean containsShowWhenLocked = containsShowWhenLockedWindow();
        if (containsDismissKeyguard != mLastContainsDismissKeyguardWindow
                || containsShowWhenLocked != mLastContainsShowWhenLockedWindow) {
            mWmService.notifyKeyguardFlagsChanged(null /* callback */,
                    getDisplayContent().getDisplayId());
        }
        mLastContainsDismissKeyguardWindow = containsDismissKeyguard;
        mLastContainsShowWhenLockedWindow = containsShowWhenLocked;
        mLastContainsTurnScreenOnWindow = containsTurnScreenOnWindow();
    }

    boolean containsDismissKeyguardWindow() {
        // Window state is transient during relaunch. We are not guaranteed to be frozen during the
        // entirety of the relaunch.
        if (isRelaunching()) {
            return mLastContainsDismissKeyguardWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_DISMISS_KEYGUARD) != 0) {
                return true;
            }
        }
        return false;
    }

    boolean containsShowWhenLockedWindow() {
        // When we are relaunching, it is possible for us to be unfrozen before our previous
        // windows have been added back. Using the cached value ensures that our previous
        // showWhenLocked preference is honored until relaunching is complete.
        if (isRelaunching()) {
            return mLastContainsShowWhenLockedWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_SHOW_WHEN_LOCKED) != 0) {
                return true;
            }
        }

        return false;
    }

    void setShowWhenLocked(boolean showWhenLocked) {
        mShowWhenLocked = showWhenLocked;
        mAtmService.mRootWindowContainer.ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */, false /* preserveWindows */);
    }

    void setInheritShowWhenLocked(boolean inheritShowWhenLocked) {
        mInheritShownWhenLocked = inheritShowWhenLocked;
        mAtmService.mRootWindowContainer.ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */, false /* preserveWindows */);
    }

    /**
     * @return {@code true} if the activity windowing mode is not
     *         {@link android.app.WindowConfiguration#WINDOWING_MODE_PINNED} and a) activity
     *         contains windows that have {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED} set or if the
     *         activity has set {@link #mShowWhenLocked}, or b) if the activity has set
     *         {@link #mInheritShownWhenLocked} and the activity behind this satisfies the
     *         conditions a) above.
     *         Multi-windowing mode will be exited if {@code true} is returned.
     */
    boolean canShowWhenLocked() {
        if (!inPinnedWindowingMode() && (mShowWhenLocked || containsShowWhenLockedWindow())) {
            return true;
        } else if (mInheritShownWhenLocked) {
            final ActivityRecord r = task.getActivityBelow(this);
            return r != null && !r.inPinnedWindowingMode() && (r.mShowWhenLocked
                    || r.containsShowWhenLockedWindow());
        } else {
            return false;
        }
    }

    /**
     * @return Whether we are allowed to show non-starting windows at the moment. We disallow
     *         showing windows during transitions in case we have windows that have wide-color-gamut
     *         color mode set to avoid jank in the middle of the transition.
     */
    boolean canShowWindows() {
        return allDrawn && !(isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION)
                && hasNonDefaultColorWindow());
    }

    /**
     * @return true if we have a window that has a non-default color mode set; false otherwise.
     */
    private boolean hasNonDefaultColorWindow() {
        return forAllWindows(ws -> ws.mAttrs.getColorMode() != COLOR_MODE_DEFAULT,
                true /* topToBottom */);
    }

    WindowState getImeTargetBelowWindow(WindowState w) {
        final int index = mChildren.indexOf(w);
        if (index > 0) {
            final WindowState target = mChildren.get(index - 1);
            if (target.canBeImeTarget()) {
                return target;
            }
        }
        return null;
    }

    WindowState getHighestAnimLayerWindow(WindowState currentTarget) {
        WindowState candidate = null;
        for (int i = mChildren.indexOf(currentTarget); i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            if (w.mRemoved) {
                continue;
            }
            if (candidate == null) {
                candidate = w;
            }
        }
        return candidate;
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        // For legacy reasons we process the TaskStack.mExitingActivities first in DisplayContent
        // before the non-exiting app tokens. So, we skip the exiting app tokens here.
        // TODO: Investigate if we need to continue to do this or if we can just process them
        // in-order.
        if (mIsExiting && !forAllWindowsUnchecked(WindowState::waitingForReplacement, true)) {
            return false;
        }
        return forAllWindowsUnchecked(callback, traverseTopToBottom);
    }

    boolean forAllWindowsUnchecked(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        return super.forAllWindows(callback, traverseTopToBottom);
    }

    @Override
    boolean forAllActivities(
            Function<ActivityRecord, Boolean> callback, boolean traverseTopToBottom) {
        return callback.apply(this);
    }

    @Override
    void forAllActivities(Consumer<ActivityRecord> callback, boolean traverseTopToBottom) {
        callback.accept(this);
    }

    @Override
    ActivityRecord getActivity(Predicate<ActivityRecord> callback, boolean traverseTopToBottom,
            WindowContainer boundary) {
        return callback.test(this) ? this : null;
    }

    @Override
    protected void setLayer(Transaction t, int layer) {
        if (!mSurfaceAnimator.hasLeash()) {
            t.setLayer(mSurfaceControl, layer);
        }
    }

    @Override
    protected void setRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
        if (!mSurfaceAnimator.hasLeash()) {
            t.setRelativeLayer(mSurfaceControl, relativeTo, layer);
        }
    }

    @Override
    protected void reparentSurfaceControl(Transaction t, SurfaceControl newParent) {
        if (!mSurfaceAnimator.hasLeash()) {
            t.reparent(mSurfaceControl, newParent);
        }
    }

    void logStartActivity(int tag, Task task) {
        final Uri data = intent.getData();
        final String strData = data != null ? data.toSafeString() : null;

        EventLog.writeEvent(tag,
                mUserId, System.identityHashCode(this), task.mTaskId,
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
        final ActivityStack stack = getRootTask();
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
                task.forAllActivities((r) -> {
                    r.clearOptionsLocked(false /* withAbort */);
                });
            }
        }
    }

    /**
     * Apply override app transition base on options & animation type.
     */
    void applyOptionsLocked(ActivityOptions pendingOptions, Intent intent) {
        final int animationType = pendingOptions.getAnimationType();
        final DisplayContent displayContent = getDisplayContent();
        switch (animationType) {
            case ANIM_CUSTOM:
                displayContent.mAppTransition.overridePendingAppTransition(
                        pendingOptions.getPackageName(),
                        pendingOptions.getCustomEnterResId(),
                        pendingOptions.getCustomExitResId(),
                        pendingOptions.getAnimationStartedListener(),
                        pendingOptions.getAnimationFinishedListener());
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
                final HardwareBuffer buffer = pendingOptions.getThumbnail();
                displayContent.mAppTransition.overridePendingAppTransitionThumb(buffer,
                        pendingOptions.getStartX(), pendingOptions.getStartY(),
                        pendingOptions.getAnimationStartedListener(),
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
                            specsFuture, pendingOptions.getAnimationStartedListener(),
                            animationType == ANIM_THUMBNAIL_ASPECT_SCALE_UP);
                } else if (animationType == ANIM_THUMBNAIL_ASPECT_SCALE_DOWN
                        && specs != null) {
                    displayContent.mAppTransition.overridePendingAppTransitionMultiThumb(
                            specs, pendingOptions.getAnimationStartedListener(),
                            pendingOptions.getAnimationFinishedListener(), false);
                } else {
                    displayContent.mAppTransition.overridePendingAppTransitionAspectScaledThumb(
                            pendingOptions.getThumbnail(),
                            pendingOptions.getStartX(), pendingOptions.getStartY(),
                            pendingOptions.getWidth(), pendingOptions.getHeight(),
                            pendingOptions.getAnimationStartedListener(),
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

    void clearAllDrawn() {
        allDrawn = false;
    }

    /**
     * Returns whether the drawn window states of this {@link ActivityRecord} has considered every
     * child {@link WindowState}. A child is considered if it has been passed into
     * {@link #updateDrawnWindowStates(WindowState)} after being added. This is used to determine
     * whether states, such as {@code allDrawn}, can be set, which relies on state variables such as
     * {@code mNumInterestingWindows}, which depend on all {@link WindowState}s being considered.
     *
     * @return {@code true} If all children have been considered, {@code false}.
     */
    private boolean allDrawnStatesConsidered() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState child = mChildren.get(i);
            if (child.mightAffectAllDrawn() && !child.getDrawnStateEvaluated()) {
                return false;
            }
        }
        return true;
    }

    /**
     *  Determines if the token has finished drawing. This should only be called from
     *  {@link DisplayContent#applySurfaceChangesTransaction}
     */
    void updateAllDrawn() {
        if (!allDrawn) {
            // Number of drawn windows can be less when a window is being relaunched, wait for
            // all windows to be launched and drawn for this token be considered all drawn.
            final int numInteresting = mNumInterestingWindows;

            // We must make sure that all present children have been considered (determined by
            // {@link #allDrawnStatesConsidered}) before evaluating whether everything has been
            // drawn.
            if (numInteresting > 0 && allDrawnStatesConsidered()
                    && mNumDrawnWindows >= numInteresting && !isRelaunching()) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawn: " + this
                        + " interesting=" + numInteresting + " drawn=" + mNumDrawnWindows);
                allDrawn = true;
                // Force an additional layout pass where
                // WindowStateAnimator#commitFinishDrawingLocked() will call performShowLocked().
                if (mDisplayContent != null) {
                    mDisplayContent.setLayoutNeeded();
                }
                mWmService.mH.obtainMessage(H.NOTIFY_ACTIVITY_DRAWN, token).sendToTarget();
            }
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

    boolean allowMoveToFront() {
        return pendingOptions == null || !pendingOptions.getAvoidMoveToFront();
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

            if (getDisplayContent() != null) {
                getDisplayContent().getInputMonitor().pauseDispatchingLw(this);
            }
        }
    }

    void resumeKeyDispatchingLocked() {
        if (keysPaused) {
            keysPaused = false;

            if (getDisplayContent() != null) {
                getDisplayContent().getInputMonitor().resumeDispatchingLw(this);
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
        if (!mDeferHidingClient && !mVisibleRequested) {
            // Hiding the client is no longer deferred and the app isn't visible still, go ahead and
            // update the visibility.
            setVisibility(false);
        }
    }

    @Override
    boolean isVisible() {
        // If the activity isn't hidden then it is considered visible and there is no need to check
        // its children windows to see if they are visible.
        return mVisible;
    }

    void setVisible(boolean visible) {
        if (visible != mVisible) {
            mVisible = visible;
            scheduleAnimation();
        }
    }

    /**
     * Set visibility on this {@link ActivityRecord}
     *
     * <p class="note"><strong>Note: </strong>This function might not update the visibility of
     * this {@link ActivityRecord} immediately. In case we are preparing an app transition, we
     * delay changing the visibility of this {@link ActivityRecord} until we execute that
     * transition.</p>
     *
     * @param visible {@code true} if the {@link ActivityRecord} should become visible, otherwise
     *                this should become invisible.
     */
    void setVisibility(boolean visible) {
        if (getParent() == null) {
            Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: "
                    + appToken);
            return;
        }
        if (visible) {
            mDeferHidingClient = false;
        }
        setVisibility(visible, mDeferHidingClient);
        mAtmService.addWindowLayoutReasons(
                ActivityTaskManagerService.LAYOUT_REASON_VISIBILITY_CHANGED);
        mStackSupervisor.getActivityMetricsLogger().notifyVisibilityChanged(this);
        mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
    }

    @VisibleForTesting
    void setVisibility(boolean visible, boolean deferHidingClient) {
        final AppTransition appTransition = getDisplayContent().mAppTransition;

        // Don't set visibility to false if we were already not visible. This prevents WM from
        // adding the app to the closing app list which doesn't make sense for something that is
        // already not visible. However, set visibility to true even if we are already visible.
        // This makes sure the app is added to the opening apps list so that the right
        // transition can be selected.
        // TODO: Probably a good idea to separate the concept of opening/closing apps from the
        // concept of setting visibility...
        if (!visible && !mVisibleRequested) {

            if (!deferHidingClient && mLastDeferHidingClient) {
                // We previously deferred telling the client to hide itself when visibility was
                // initially set to false. Now we would like it to hide, so go ahead and set it.
                mLastDeferHidingClient = deferHidingClient;
                setClientVisible(false);
            }
            return;
        }

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "setAppVisibility(%s, visible=%b): %s visible=%b mVisibleRequested=%b Callers=%s",
                appToken, visible, appTransition, isVisible(), mVisibleRequested,
                Debug.getCallers(6));

        onChildVisibilityRequested(visible);

        final DisplayContent displayContent = getDisplayContent();
        displayContent.mOpeningApps.remove(this);
        displayContent.mClosingApps.remove(this);
        waitingToShow = false;
        mVisibleRequested = visible;
        mLastDeferHidingClient = deferHidingClient;

        if (!visible) {
            // If the app is dead while it was visible, we kept its dead window on screen.
            // Now that the app is going invisible, we can remove it. It will be restarted
            // if made visible again.
            removeDeadWindows();
        } else {
            if (!appTransition.isTransitionSet()
                    && appTransition.isReady()) {
                // Add the app mOpeningApps if transition is unset but ready. This means
                // we're doing a screen freeze, and the unfreeze will wait for all opening
                // apps to be ready.
                displayContent.mOpeningApps.add(this);
            }
            startingMoved = false;
            // If the token is currently hidden (should be the common case), or has been
            // stopped, then we need to set up to wait for its windows to be ready.
            if (!isVisible() || mAppStopped) {
                clearAllDrawn();

                // If the app was already visible, don't reset the waitingToShow state.
                if (!isVisible()) {
                    waitingToShow = true;

                    // If the client isn't hidden, we don't need to reset the drawing state.
                    if (!isClientVisible()) {
                        // Let's reset the draw state in order to prevent the starting window to be
                        // immediately dismissed when the app still has the surface.
                        forAllWindows(w -> {
                            if (w.mWinAnimator.mDrawState == HAS_DRAWN) {
                                w.mWinAnimator.resetDrawState();

                                // Force add to mResizingWindows, so that we are guaranteed to get
                                // another reportDrawn callback.
                                w.resetLastContentInsets();
                            }
                        }, true /* traverseTopToBottom */);
                    }
                }
            }

            // In the case where we are making an app visible but holding off for a transition,
            // we still need to tell the client to make its windows visible so they get drawn.
            // Otherwise, we will wait on performing the transition until all windows have been
            // drawn, they never will be, and we are sad.
            setClientVisible(true);

            requestUpdateWallpaperIfNeeded();

            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "No longer Stopped: %s", this);
            mAppStopped = false;

            transferStartingWindowFromHiddenAboveTokenIfNeeded();
        }

        // If we are preparing an app transition, then delay changing
        // the visibility of this token until we execute that transition.
        // Note that we ignore display frozen since we want the opening / closing transition type
        // can be updated correctly even display frozen, and it's safe since in applyAnimation will
        // still check DC#okToAnimate again if the transition animation is fine to apply.
        if (okToAnimate(true /* ignoreFrozen */) && appTransition.isTransitionSet()) {
            if (visible) {
                displayContent.mOpeningApps.add(this);
                mEnteringAnimation = true;
            } else {
                displayContent.mClosingApps.add(this);
                mEnteringAnimation = false;
            }
            if (appTransition.getAppTransition() == TRANSIT_TASK_OPEN_BEHIND) {
                // We're launchingBehind, add the launching activity to mOpeningApps.
                final WindowState win = getDisplayContent().findFocusedWindow();
                if (win != null) {
                    final ActivityRecord focusedActivity = win.mActivityRecord;
                    if (focusedActivity != null) {
                        ProtoLog.d(WM_DEBUG_APP_TRANSITIONS,
                                "TRANSIT_TASK_OPEN_BEHIND,  adding %s to mOpeningApps",
                                focusedActivity);

                        // Force animation to be loaded.
                        displayContent.mOpeningApps.add(focusedActivity);
                    }
                }
            }
            return;
        }

        commitVisibility(visible, true /* performLayout */);
        updateReportedVisibilityLocked();
    }

    @Override
    boolean applyAnimation(WindowManager.LayoutParams lp, int transit, boolean enter,
            boolean isVoiceInteraction,
            @Nullable OnAnimationFinishedCallback animationFinishedCallback) {
        if (mUseTransferredAnimation) {
            return false;
        }
        return super.applyAnimation(lp, transit, enter, isVoiceInteraction,
                animationFinishedCallback);
    }

    /**
     * Update visibility to this {@link ActivityRecord}.
     *
     * <p class="note"><strong>Note: </strong> Unlike {@link #setVisibility}, this immediately
     * updates the visibility without starting an app transition. Since this function may start
     * animation on {@link WindowState} depending on app transition animation status, an app
     * transition animation must be started before calling this function if necessary.</p>
     *
     * @param visible {@code true} if this {@link ActivityRecord} should become visible, otherwise
     *                this should become invisible.
     * @param performLayout if {@code true}, perform surface placement after committing visibility.
     */
    void commitVisibility(boolean visible, boolean performLayout) {
        // Reset the state of mVisibleSetFromTransferredStartingWindow since visibility is actually
        // been set by the app now.
        mVisibleSetFromTransferredStartingWindow = false;
        if (visible == isVisible()) {
            return;
        }

        final int windowsCount = mChildren.size();
        for (int i = 0; i < windowsCount; i++) {
            mChildren.get(i).onAppVisibilityChanged(visible, isAnimating(PARENTS,
                    ANIMATION_TYPE_APP_TRANSITION));
        }
        setVisible(visible);
        mVisibleRequested = visible;
        if (!visible) {
            stopFreezingScreen(true, true);
        } else {
            // If we are being set visible, and the starting window is not yet displayed,
            // then make sure it doesn't get displayed.
            if (startingWindow != null && !startingWindow.isDrawnLw()) {
                startingWindow.clearPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
                startingWindow.mLegacyPolicyVisibilityAfterAnim = false;
            }
            // We are becoming visible, so better freeze the screen with the windows that are
            // getting visible so we also wait for them.
            forAllWindows(mWmService::makeWindowFreezingScreenIfNeededLocked, true);
        }
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "commitVisibility: %s: visible=%b mVisibleRequested=%b", this,
                isVisible(), mVisibleRequested);
        final DisplayContent displayContent = getDisplayContent();
        displayContent.getInputMonitor().setUpdateInputWindowsNeededLw();
        if (performLayout) {
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    false /*updateInputWindows*/);
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
        }
        displayContent.getInputMonitor().updateInputWindowsLw(false /*force*/);
        mUseTransferredAnimation = false;

        postApplyAnimation(visible);
    }

    /**
     * Post process after applying an app transition animation.
     *
     * <p class="note"><strong>Note: </strong> This function must be called after the animations
     * have been applied and {@link #commitVisibility}.</p>
     *
     * @param visible {@code true} if this {@link ActivityRecord} has become visible, otherwise
     *                this has become invisible.
     */
    private void postApplyAnimation(boolean visible) {
        final boolean delayed = isAnimating(PARENTS | CHILDREN,
                ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_WINDOW_ANIMATION);
        if (!delayed) {
            // We aren't delayed anything, but exiting windows rely on the animation finished
            // callback being called in case the ActivityRecord was pretending to be delayed,
            // which we might have done because we were in closing/opening apps list.
            onAnimationFinished(ANIMATION_TYPE_APP_TRANSITION, null /* AnimationAdapter */);
            if (visible) {
                // The token was made immediately visible, there will be no entrance animation.
                // We need to inform the client the enter animation was finished.
                mEnteringAnimation = true;
                mWmService.mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(
                        token);
            }
        }

        // If we're becoming visible, immediately change client visibility as well. there seem
        // to be some edge cases where we change our visibility but client visibility never gets
        // updated.
        // If we're becoming invisible, update the client visibility if we are not running an
        // animation. Otherwise, we'll update client visibility in onAnimationFinished.
        if (visible || !isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION)) {
            setClientVisible(visible);
        }

        final DisplayContent displayContent = getDisplayContent();
        if (!displayContent.mClosingApps.contains(this)
                && !displayContent.mOpeningApps.contains(this)) {
            // Take the screenshot before possibly hiding the WSA, otherwise the screenshot
            // will not be taken.
            mWmService.mTaskSnapshotController.notifyAppVisibilityChanged(this, visible);
        }

        // If we are hidden but there is no delay needed we immediately
        // apply the Surface transaction so that the ActivityManager
        // can have some guarantee on the Surface state following
        // setting the visibility. This captures cases like dismissing
        // the docked or pinned stack where there is no app transition.
        //
        // In the case of a "Null" animation, there will be
        // no animation but there will still be a transition set.
        // We still need to delay hiding the surface such that it
        // can be synchronized with showing the next surface in the transition.
        if (!isVisible() && !delayed && !displayContent.mAppTransition.isTransitionSet()) {
            SurfaceControl.openTransaction();
            try {
                forAllWindows(win -> {
                    win.mWinAnimator.hide("immediately hidden"); }, true);
            } finally {
                SurfaceControl.closeTransaction();
            }
        }
    }

    /**
     * Check if visibility of this {@link ActivityRecord} should be updated as part of an app
     * transition.
     *
     * <p class="note><strong>Note:</strong> If the visibility of this {@link ActivityRecord} is
     * already set to {@link #mVisible}, we don't need to update the visibility. So {@code false} is
     * returned.</p>
     *
     * @param visible {@code true} if this {@link ActivityRecord} should become visible,
     *                {@code false} if this should become invisible.
     * @return {@code true} if visibility of this {@link ActivityRecord} should be updated, and
     *         an app transition animation should be run.
     */
    boolean shouldApplyAnimation(boolean visible) {
        // Allow for state update and animation to be applied if:
        // * activity is transitioning visibility state
        // * or the activity was marked as hidden and is exiting before we had a chance to play the
        // transition animation
        // * or this is an opening app and windows are being replaced (e.g. freeform window to
        //   normal window).
        return isVisible() != visible || (!isVisible() && mIsExiting)
                || (visible && forAllWindows(WindowState::waitingForReplacement, true));
    }

    /**
     * See {@link Activity#setDisablePreviewScreenshots}.
     */
    void setDisablePreviewScreenshots(boolean disable) {
        mDisablePreviewScreenshots = disable;
    }

    /**
     * Retrieves whether we'd like to generate a snapshot that's based solely on the theme. This is
     * the case when preview screenshots are disabled {@link #setDisablePreviewScreenshots} or when
     * we can't take a snapshot for other reasons, for example, if we have a secure window.
     *
     * @return True if we need to generate an app theme snapshot, false if we'd like to take a real
     *         screenshot.
     */
    boolean shouldUseAppThemeSnapshot() {
        return mDisablePreviewScreenshots || forAllWindows(w -> (w.mAttrs.flags & FLAG_SECURE) != 0,
                true /* topToBottom */);
    }

    /**
     * Sets whether the current launch can turn the screen on.
     * @see #currentLaunchCanTurnScreenOn()
     */
    void setCurrentLaunchCanTurnScreenOn(boolean currentLaunchCanTurnScreenOn) {
        mCurrentLaunchCanTurnScreenOn = currentLaunchCanTurnScreenOn;
    }

    /**
     * Indicates whether the current launch can turn the screen on. This is to prevent multiple
     * relayouts from turning the screen back on. The screen should only turn on at most
     * once per activity resume.
     * <p>
     * Note this flag is only meaningful when {@link WindowManager.LayoutParams#FLAG_TURN_SCREEN_ON}
     * or {@link ActivityRecord#canTurnScreenOn} is set.
     *
     * @return {@code true} if the activity is ready to turn on the screen.
     */
    boolean currentLaunchCanTurnScreenOn() {
        return mCurrentLaunchCanTurnScreenOn;
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

        if (task != null) {
            task.onActivityStateChanged(this, state, reason);
        }

        // The WindowManager interprets the app stopping signal as
        // an indication that the Surface will eventually be destroyed.
        // This however isn't necessarily true if we are going to sleep.
        if (state == STOPPING && !isSleeping()) {
            if (getParent() == null) {
                Slog.w(TAG_WM, "Attempted to notify stopping on non-existing app token: "
                        + appToken);
                return;
            }
            detachChildren();
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

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(ActivityState state1, ActivityState state2, ActivityState state3,
            ActivityState state4, ActivityState state5, ActivityState state6) {
        return state1 == mState || state2 == mState || state3 == mState || state4 == mState
                || state5 == mState || state6 == mState;
    }

    void destroySurfaces() {
        destroySurfaces(false /*cleanupOnResume*/);
    }

    /**
     * Destroy surfaces which have been marked as eligible by the animator, taking care to ensure
     * the client has finished with them.
     *
     * @param cleanupOnResume whether this is done when app is resumed without fully stopped. If
     * set to true, destroy only surfaces of removed windows, and clear relevant flags of the
     * others so that they are ready to be reused. If set to false (common case), destroy all
     * surfaces that's eligible, if the app is already stopped.
     */
    private void destroySurfaces(boolean cleanupOnResume) {
        boolean destroyedSomething = false;

        // Copying to a different list as multiple children can be removed.
        final ArrayList<WindowState> children = new ArrayList<>(mChildren);
        for (int i = children.size() - 1; i >= 0; i--) {
            final WindowState win = children.get(i);
            destroyedSomething |= win.destroySurface(cleanupOnResume, mAppStopped);
        }
        if (destroyedSomething) {
            final DisplayContent dc = getDisplayContent();
            dc.assignWindowLayers(true /*setLayoutNeeded*/);
            updateLetterboxSurface(null);
        }
    }

    void notifyAppResumed(boolean wasStopped) {
        if (getParent() == null) {
            Slog.w(TAG_WM, "Attempted to notify resumed of non-existing app token: "
                    + appToken);
            return;
        }
        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "notifyAppResumed: wasStopped=%b %s",
                wasStopped, this);
        mAppStopped = false;
        // Allow the window to turn the screen on once the app is resumed again.
        setCurrentLaunchCanTurnScreenOn(true);
        if (!wasStopped) {
            destroySurfaces(true /*cleanupOnResume*/);
        }
    }

    /**
     * Notify that the app has stopped, and it is okay to destroy any surfaces which were
     * keeping alive in case they were still being used.
     */
    void notifyAppStopped() {
        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "notifyAppStopped: %s", this);
        mAppStopped = true;
        // Reset the last saved PiP snap fraction on app stop.
        mDisplayContent.mPinnedStackControllerLocked.onActivityHidden(mActivityComponent);
        destroySurfaces();
        // Remove any starting window that was added for this app if they are still around.
        removeStartingWindow();
    }

    /**
     * Suppress transition until the new activity becomes ready, otherwise the keyguard can appear
     * for a short amount of time before the new process with the new activity had the ability to
     * set its showWhenLocked flags.
     */
    void notifyUnknownVisibilityLaunchedForKeyguardTransition() {
        // No display activities never add a window, so there is no point in waiting them for
        // relayout.
        if (noDisplay || !mStackSupervisor.getKeyguardController().isKeyguardLocked()) {
            return;
        }

        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(this);
    }

    /** @return {@code true} if this activity should be made visible. */
    boolean shouldBeVisible(boolean behindFullscreenActivity, boolean ignoringKeyguard) {
        // Check whether activity should be visible without Keyguard influence
        visibleIgnoringKeyguard = (!behindFullscreenActivity || mLaunchTaskBehind)
                && okToShowLocked();

        if (ignoringKeyguard) {
            return visibleIgnoringKeyguard;
        }

        final ActivityStack stack = getRootTask();
        if (stack == null) {
            return false;
        }

        // Check if the activity is on a sleeping display, and if it can turn it ON.
        if (getDisplay().isSleeping()) {
            final boolean canTurnScreenOn = !mSetToSleep || canTurnScreenOn()
                    || canShowWhenLocked() || containsDismissKeyguardWindow();
            if (!canTurnScreenOn) {
                return false;
            }
        }

        // Now check whether it's really visible depending on Keyguard state, and update
        // {@link ActivityStack} internal states.
        // Inform the method if this activity is the top activity of this stack, but exclude the
        // case where this is the top activity in a pinned stack.
        final boolean isTop = this == stack.getTopNonFinishingActivity();
        final boolean isTopNotPinnedStack = stack.isAttached()
                && stack.getDisplayArea().isTopNotPinnedStack(stack);
        final boolean visibleIgnoringDisplayStatus = stack.checkKeyguardVisibility(this,
                visibleIgnoringKeyguard, isTop && isTopNotPinnedStack);

        return visibleIgnoringDisplayStatus;
    }

    boolean shouldBeVisible() {
        final ActivityStack stack = getRootTask();
        if (stack == null) {
            return false;
        }

        final boolean behindFullscreenActivity = stack.checkBehindFullscreenActivity(
                this, null /* handleBehindFullscreenActivity */);
        return shouldBeVisible(behindFullscreenActivity, false /* ignoringKeyguard */);
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
        final ActivityStack stack = getRootTask();
        try {
            if (stack.mTranslucentActivityWaiting != null) {
                updateOptionsLocked(returningOptions);
                stack.mUndrawnActivitiesBelowTopTranslucent.add(this);
            }
            setVisibility(true);
            mSetToSleep = false;
            app.postPendingUiCleanMsg(true);
            if (reportToClient) {
                mClientVisibilityDeferred = false;
                makeActiveIfNeeded(starting);
            } else {
                mClientVisibilityDeferred = true;
            }
            // The activity may be waiting for stop, but that is no longer appropriate for it.
            mStackSupervisor.mStoppingActivities.remove(this);
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making visible: " + intent.getComponent(), e);
        }
        handleAlreadyVisible();
    }

    void makeInvisible() {
        if (!mVisibleRequested) {
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
            setVisibility(false);

            switch (getState()) {
                case STOPPING:
                case STOPPED:
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
                Slog.v(TAG_VISIBILITY, "Resume visible activity, " + this);
            }
            return getRootTask().resumeTopActivityUncheckedLocked(activeActivity /* prev */,
                    null /* options */);
        } else if (shouldPauseActivity(activeActivity)) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Pause visible activity, " + this);
            }
            // An activity must be in the {@link PAUSING} state for the system to validate
            // the move to {@link PAUSED}.
            setState(PAUSING, "makeActiveIfNeeded");
            try {
                mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                        PauseActivityItem.obtain(finishing, false /* userLeaving */,
                                configChangeFlags, false /* dontReport */));
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending pause: " + intent.getComponent(), e);
            }
        } else if (shouldStartActivity()) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Start visible activity, " + this);
            }
            setState(STARTED, "makeActiveIfNeeded");
            try {
                mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                        StartActivityItem.obtain());
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending start: " + intent.getComponent(), e);
            }
            // The activity may be waiting for stop, but that is no longer appropriate if we are
            // starting the activity again
            mStackSupervisor.mStoppingActivities.remove(this);
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
    @VisibleForTesting
    boolean shouldPauseActivity(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && !isFocusable() && !isState(PAUSING, PAUSED)
                // We will only allow pausing if results is null, otherwise it will cause this
                // activity to resume before getting result
                && (results == null);
    }

    /**
     * Check if activity should be moved to RESUMED state.
     * See {@link #shouldBeResumed(ActivityRecord)}
     * @param activeActivity the activity that is active or just completed pause action. We won't
     *                       resume if this activity is active.
     */
    @VisibleForTesting
    boolean shouldResumeActivity(ActivityRecord activeActivity) {
        return shouldBeResumed(activeActivity) && !isState(RESUMED);
    }

    /**
     * Check if activity should be RESUMED now. The activity:
     * - should be eligible to be made active (see {@link #shouldMakeActive(ActivityRecord)})
     * - should be focusable
     */
    private boolean shouldBeResumed(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && isFocusable()
                && getTask().getVisibility(activeActivity) == STACK_VISIBILITY_VISIBLE
                && canResumeByCompat();
    }

    /**
     * Check if activity should be moved to STARTED state.
     * NOTE: This will not check if activity should be made paused or resumed first, so it must only
     * be called after checking with {@link #shouldResumeActivity(ActivityRecord)}
     * and {@link #shouldPauseActivity(ActivityRecord)}.
     */
    private boolean shouldStartActivity() {
        return mVisibleRequested && (isState(STOPPED) || isState(STOPPING));
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
        // ActivityStack#ensureActivitiesVisible will bring the activity to a proper
        // active state.
        if (!isState(STARTED, RESUMED, PAUSED, STOPPED, STOPPING)
                || getRootTask().mTranslucentActivityWaiting != null) {
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
        if (!task.hasChild(this)) {
            throw new IllegalStateException("Activity not found in its task");
        }
        final ActivityRecord activityAbove = task.getActivityAbove(this);
        if (activityAbove == null) {
            // It's the topmost activity in the task - should become resumed now
            return true;
        }
        // Check if activity above is finishing now and this one becomes the topmost in task.
        if (activityAbove.finishing) {
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

        final DisplayContent display = r.getDisplay();
        if (display != null) {
            display.handleActivitySizeCompatModeIfNeeded(r);
        }

        r.getDisplayContent().mUnknownAppVisibilityController.notifyAppResumedFinished(r);
    }

    /**
     * Once we know that we have asked an application to put an activity in the resumed state
     * (either by launching it or explicitly telling it), this function updates the rest of our
     * state to match that fact.
     */
    void completeResumeLocked() {
        final boolean wasVisible = mVisibleRequested;
        setVisibility(true);
        if (!wasVisible) {
            // Visibility has changed, so take a note of it so we call the TaskStackChangedListener
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
        }
        idle = false;
        results = null;
        if (newIntents != null && newIntents.size() > 0) {
            mLastNewIntent = newIntents.get(newIntents.size() - 1);
        }
        newIntents = null;
        stopped = false;

        if (isActivityTypeHome()) {
            mStackSupervisor.updateHomeProcess(task.getBottomMostActivity().app);
        }

        if (nowVisible) {
            mStackSupervisor.stopWaitingForActivityVisible(this);
        }

        // Schedule an idle timeout in case the app doesn't do it for us.
        mStackSupervisor.scheduleIdleTimeout(this);

        mStackSupervisor.reportResumedActivityLocked(this);

        resumeKeyDispatchingLocked();
        final ActivityStack stack = getRootTask();
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

    void activityPaused(boolean timeout) {
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE,
                "Activity paused: token=" + appToken + ", timeout=" + timeout);

        final ActivityStack stack = getStack();

        if (stack != null) {
            removePauseTimeout();

            if (stack.mPausingActivity == this) {
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSED: " + this
                        + (timeout ? " (due to timeout)" : " (pause complete)"));
                mAtmService.deferWindowLayout();
                try {
                    stack.completePauseLocked(true /* resumeNext */, null /* resumingActivity */);
                } finally {
                    mAtmService.continueWindowLayout();
                }
                return;
            } else {
                EventLogTags.writeWmFailedToPause(mUserId, System.identityHashCode(this),
                        shortComponentName, stack.mPausingActivity != null
                                ? stack.mPausingActivity.shortComponentName : "(none)");
                if (isState(PAUSING)) {
                    setState(PAUSED, "activityPausedLocked");
                    if (finishing) {
                        if (DEBUG_PAUSE) Slog.v(TAG,
                                "Executing finish of failed to pause activity: " + this);
                        completeFinishing("activityPausedLocked");
                    }
                }
            }
        }

        mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
    }

    /**
     * Schedule a pause timeout in case the app doesn't respond. We don't give it much time because
     * this directly impacts the responsiveness seen by the user.
     */
    void schedulePauseTimeout() {
        pauseTime = SystemClock.uptimeMillis();
        mAtmService.mH.postDelayed(mPauseTimeoutRunnable, PAUSE_TIMEOUT);
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Waiting for pause to complete...");
    }

    private void removePauseTimeout() {
        mAtmService.mH.removeCallbacks(mPauseTimeoutRunnable);
    }

    private void removeDestroyTimeout() {
        mAtmService.mH.removeCallbacks(mDestroyTimeoutRunnable);
    }

    private void removeStopTimeout() {
        mAtmService.mH.removeCallbacks(mStopTimeoutRunnable);
    }

    void removeTimeouts() {
        mStackSupervisor.removeIdleTimeoutForActivity(this);
        removePauseTimeout();
        removeStopTimeout();
        removeDestroyTimeout();
        finishLaunchTickingLocked();
    }

    void stopIfPossible() {
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH, "Stopping: " + this);
        final ActivityStack stack = getRootTask();
        if (isNoHistory()) {
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
                Slog.v(TAG_VISIBILITY, "Stopping:" + this);
            }
            EventLogTags.writeWmStopActivity(
                    mUserId, System.identityHashCode(this), shortComponentName);
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    StopActivityItem.obtain(configChangeFlags));

            if (stack.shouldSleepOrShutDownActivities()) {
                setSleeping(true);
            }
            mAtmService.mH.postDelayed(mStopTimeoutRunnable, STOP_TIMEOUT);
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

    void activityStopped(Bundle newIcicle, PersistableBundle newPersistentState,
            CharSequence description) {
        final ActivityStack stack = getRootTask();
        final boolean isStopping = mState == STOPPING;
        if (!isStopping && mState != RESTARTING_PROCESS) {
            Slog.i(TAG, "Activity reported stop, but no longer stopping: " + this);
            removeStopTimeout();
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
            removeStopTimeout();
            stopped = true;
            if (isStopping) {
                setState(STOPPED, "activityStoppedLocked");
            }

            notifyAppStopped();

            if (finishing) {
                clearOptionsLocked();
            } else {
                if (deferRelaunchUntilPaused) {
                    destroyImmediately(true /* removeFromApp */, "stop-config");
                    mRootWindowContainer.resumeFocusedStacksTopActivities();
                } else {
                    mRootWindowContainer.updatePreviousProcess(this);
                }
            }
        }
    }

    void addToStopping(boolean scheduleIdle, boolean idleDelayed, String reason) {
        if (!mStackSupervisor.mStoppingActivities.contains(this)) {
            EventLogTags.writeWmAddToStopping(mUserId, System.identityHashCode(this),
                    shortComponentName, reason);
            mStackSupervisor.mStoppingActivities.add(this);
        }

        final ActivityStack stack = getRootTask();
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
                mStackSupervisor.scheduleIdle();
            } else {
                mStackSupervisor.scheduleIdleTimeout(this);
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
            continueLaunchTicking();
        }
    }

    private boolean continueLaunchTicking() {
        if (launchTickTime == 0) {
            return false;
        }

        final ActivityStack stack = getRootTask();
        if (stack == null) {
            return false;
        }

        stack.removeLaunchTickMessages();
        mAtmService.mH.postDelayed(mLaunchTickRunnable, LAUNCH_TICK);
        return true;
    }

    void removeLaunchTickRunnable() {
        mAtmService.mH.removeCallbacks(mLaunchTickRunnable);
    }

    void finishLaunchTickingLocked() {
        launchTickTime = 0;
        final ActivityStack stack = getRootTask();
        if (stack == null) {
            return;
        }
        stack.removeLaunchTickMessages();
    }

    boolean mayFreezeScreenLocked() {
        return mayFreezeScreenLocked(app);
    }

    private boolean mayFreezeScreenLocked(WindowProcessController app) {
        // Only freeze the screen if this activity is currently attached to
        // an application, and that application is not blocked or unresponding.
        // In any other case, we can't count on getting the screen unfrozen,
        // so it is best to leave as-is.
        return hasProcess() && !app.isCrashing() && !app.isNotResponding();
    }

    void startFreezingScreenLocked(int configChanges) {
        startFreezingScreenLocked(app, configChanges);
    }

    void startFreezingScreenLocked(WindowProcessController app, int configChanges) {
        if (mayFreezeScreenLocked(app)) {
            if (getParent() == null) {
                Slog.w(TAG_WM,
                        "Attempted to freeze screen with non-existing app token: " + appToken);
                return;
            }

            // Window configuration changes only effect windows, so don't require a screen freeze.
            int freezableConfigChanges = configChanges & ~(CONFIG_WINDOW_CONFIGURATION);
            if (freezableConfigChanges == 0 && okToDisplay()) {
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Skipping set freeze of %s", appToken);
                return;
            }

            startFreezingScreen();
        }
    }

    void startFreezingScreen() {
        startFreezingScreen(ROTATION_UNDEFINED /* overrideOriginalDisplayRotation */);
    }

    void startFreezingScreen(int overrideOriginalDisplayRotation) {
        ProtoLog.i(WM_DEBUG_ORIENTATION,
                "Set freezing of %s: visible=%b freezing=%b visibleRequested=%b. %s",
                appToken, isVisible(), mFreezingScreen, mVisibleRequested,
                new RuntimeException().fillInStackTrace());
        if (!mVisibleRequested) {
            return;
        }

        // If the override is given, the rotation of display doesn't change but we still want to
        // cover the activity whose configuration is changing by freezing the display and running
        // the rotation animation.
        final boolean forceRotation = overrideOriginalDisplayRotation != ROTATION_UNDEFINED;
        if (!mFreezingScreen) {
            mFreezingScreen = true;
            mWmService.registerAppFreezeListener(this);
            mWmService.mAppsFreezingScreen++;
            if (mWmService.mAppsFreezingScreen == 1) {
                if (forceRotation) {
                    // Make sure normal rotation animation will be applied.
                    mDisplayContent.getDisplayRotation().cancelSeamlessRotation();
                }
                mWmService.startFreezingDisplay(0 /* exitAnim */, 0 /* enterAnim */,
                        mDisplayContent, overrideOriginalDisplayRotation);
                mWmService.mH.removeMessages(H.APP_FREEZE_TIMEOUT);
                mWmService.mH.sendEmptyMessageDelayed(H.APP_FREEZE_TIMEOUT, 2000);
            }
        }
        if (forceRotation) {
            // The rotation of the real display won't change, so in order to unfreeze the screen
            // via {@link #checkAppWindowsReadyToShow}, the windows have to be able to call
            // {@link WindowState#reportResized} (it is skipped if the window is freezing) to update
            // the drawn state.
            return;
        }
        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            final WindowState w = mChildren.get(i);
            w.onStartFreezingScreen();
        }
    }

    boolean isFreezingScreen() {
        return mFreezingScreen;
    }

    @Override
    public void onAppFreezeTimeout() {
        Slog.w(TAG_WM, "Force clearing freeze: " + this);
        stopFreezingScreen(true, true);
    }

    void stopFreezingScreenLocked(boolean force) {
        if (force || frozenBeforeDestroy) {
            frozenBeforeDestroy = false;
            if (getParent() == null) {
                return;
            }
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Clear freezing of %s: visible=%b freezing=%b", appToken,
                                isVisible(), isFreezingScreen());
            stopFreezingScreen(true, force);
        }
    }

    void stopFreezingScreen(boolean unfreezeSurfaceNow, boolean force) {
        if (!mFreezingScreen) {
            return;
        }
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Clear freezing of %s force=%b", this, force);
        final int count = mChildren.size();
        boolean unfrozeWindows = false;
        for (int i = 0; i < count; i++) {
            final WindowState w = mChildren.get(i);
            unfrozeWindows |= w.onStopFreezingScreen();
        }
        if (force || unfrozeWindows) {
            ProtoLog.v(WM_DEBUG_ORIENTATION, "No longer freezing: %s", this);
            mFreezingScreen = false;
            mWmService.unregisterAppFreezeListener(this);
            mWmService.mAppsFreezingScreen--;
            mWmService.mLastFinishedFreezeSource = this;
        }
        if (unfreezeSurfaceNow) {
            if (unfrozeWindows) {
                mWmService.mWindowPlacerLocked.performSurfacePlacement();
            }
            mWmService.stopFreezingDisplayLocked();
        }
    }

    void reportFullyDrawnLocked(boolean restoredFromBundle) {
        final TransitionInfoSnapshot info = mStackSupervisor
            .getActivityMetricsLogger().logAppTransitionReportedDrawn(this, restoredFromBundle);
        if (info != null) {
            mStackSupervisor.reportActivityLaunchedLocked(false /* timeout */, this,
                    info.windowsFullyDrawnDelayMs, info.getLaunchState());
        }
    }

    void onFirstWindowDrawn(WindowState win, WindowStateAnimator winAnimator) {
        firstWindowDrawn = true;

        // We now have a good window to show, remove dead placeholders
        removeDeadWindows();

        if (startingWindow != null) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Finish starting %s"
                    + ": first real window is shown, no animation", win.mToken);
            // If this initial window is animating, stop it -- we will do an animation to reveal
            // it from behind the starting window, so there is no need for it to also be doing its
            // own stuff.
            win.cancelAnimation();
        }
        removeStartingWindow();
        updateReportedVisibilityLocked();
    }

    void onStartingWindowDrawn() {
        if (task != null) {
            task.setHasBeenVisible(true);
        }
    }

    /** Called when the windows associated app window container are drawn. */
    void onWindowsDrawn(boolean drawn, long timestampNs) {
        mDrawn = drawn;
        if (!drawn) {
            return;
        }
        final TransitionInfoSnapshot info = mStackSupervisor
                .getActivityMetricsLogger().notifyWindowsDrawn(this, timestampNs);
        final boolean validInfo = info != null;
        final int windowsDrawnDelayMs = validInfo ? info.windowsDrawnDelayMs : INVALID_DELAY;
        final @LaunchState int launchState = validInfo ? info.getLaunchState() : -1;
        // The activity may have been requested to be invisible (another activity has been launched)
        // so there is no valid info. But if it is the current top activity (e.g. sleeping), the
        // invalid state is still reported to make sure the waiting result is notified.
        if (validInfo || this == getDisplayArea().topRunningActivity()) {
            mStackSupervisor.reportActivityLaunchedLocked(false /* timeout */, this,
                    windowsDrawnDelayMs, launchState);
            mStackSupervisor.stopWaitingForActivityVisible(this, windowsDrawnDelayMs);
        }
        finishLaunchTickingLocked();
        if (task != null) {
            task.setHasBeenVisible(true);
        }
    }

    /** Called when the windows associated app window container are visible. */
    void onWindowsVisible() {
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting visible in " + appToken);
        mStackSupervisor.stopWaitingForActivityVisible(this);
        if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsVisibleLocked(): " + this);
        if (!nowVisible) {
            nowVisible = true;
            lastVisibleTime = SystemClock.uptimeMillis();
            mAtmService.scheduleAppGcsLocked();
        }
    }

    /** Called when the windows associated app window container are no longer visible. */
    void onWindowsGone() {
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting gone in " + appToken);
        if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsGone(): " + this);
        nowVisible = false;
    }

    @Override
    void checkAppWindowsReadyToShow() {
        if (allDrawn == mLastAllDrawn) {
            return;
        }

        mLastAllDrawn = allDrawn;
        if (!allDrawn) {
            return;
        }

        // The token has now changed state to having all windows shown...  what to do, what to do?
        if (mFreezingScreen) {
            showAllWindowsLocked();
            stopFreezingScreen(false, true);
            ProtoLog.i(WM_DEBUG_ORIENTATION,
                    "Setting mOrientationChangeComplete=true because wtoken %s "
                            + "numInteresting=%d numDrawn=%d",
                    this, mNumInterestingWindows, mNumDrawnWindows);
            // This will set mOrientationChangeComplete and cause a pass through layout.
            setAppLayoutChanges(FINISH_LAYOUT_REDO_WALLPAPER,
                    "checkAppWindowsReadyToShow: freezingScreen");
        } else {
            setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM, "checkAppWindowsReadyToShow");

            // We can now show all of the drawn windows!
            if (!getDisplayContent().mOpeningApps.contains(this) && canShowWindows()) {
                showAllWindowsLocked();
            }
        }
    }

    /**
     * This must be called while inside a transaction.
     */
    void showAllWindowsLocked() {
        forAllWindows(windowState -> {
            if (DEBUG_VISIBILITY) Slog.v(TAG, "performing show on: " + windowState);
            windowState.performShowLocked();
        }, false /* traverseTopToBottom */);
    }

    void updateReportedVisibilityLocked() {
        if (appToken == null) {
            return;
        }

        if (DEBUG_VISIBILITY) Slog.v(TAG, "Update reported visibility: " + this);
        final int count = mChildren.size();

        mReportedVisibilityResults.reset();

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            win.updateReportedVisibility(mReportedVisibilityResults);
        }

        int numInteresting = mReportedVisibilityResults.numInteresting;
        int numVisible = mReportedVisibilityResults.numVisible;
        int numDrawn = mReportedVisibilityResults.numDrawn;
        boolean nowGone = mReportedVisibilityResults.nowGone;

        boolean nowDrawn = numInteresting > 0 && numDrawn >= numInteresting;
        boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting && isVisible();
        if (!nowGone) {
            // If the app is not yet gone, then it can only become visible/drawn.
            if (!nowDrawn) {
                nowDrawn = reportedDrawn;
            }
            if (!nowVisible) {
                nowVisible = reportedVisible;
            }
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "VIS " + this + ": interesting="
                + numInteresting + " visible=" + numVisible);
        if (nowDrawn != reportedDrawn) {
            onWindowsDrawn(nowDrawn, SystemClock.elapsedRealtimeNanos());
            reportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Visibility changed in " + this + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            if (nowVisible) {
                onWindowsVisible();
            } else {
                onWindowsGone();
            }
        }
    }

    boolean isClientVisible() {
        return mClientVisible;
    }

    void setClientVisible(boolean clientVisible) {
        if (mClientVisible == clientVisible || (!clientVisible && mDeferHidingClient)) {
            return;
        }
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "setClientVisible: %s clientVisible=%b Callers=%s", this, clientVisible,
                Debug.getCallers(5));
        mClientVisible = clientVisible;
        sendAppVisibilityToClients();
    }

    /**
     * Updated this app token tracking states for interesting and drawn windows based on the window.
     *
     * @return Returns true if the input window is considered interesting and drawn while all the
     *         windows in this app token where not considered drawn as of the last pass.
     */
    boolean updateDrawnWindowStates(WindowState w) {
        w.setDrawnStateEvaluated(true /*evaluated*/);

        if (DEBUG_STARTING_WINDOW_VERBOSE && w == startingWindow) {
            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen=" + w.isOnScreen()
                    + " allDrawn=" + allDrawn + " freezingScreen=" + mFreezingScreen);
        }

        if (allDrawn && !mFreezingScreen) {
            return false;
        }

        if (mLastTransactionSequence != mWmService.mTransactionSequence) {
            mLastTransactionSequence = mWmService.mTransactionSequence;
            mNumDrawnWindows = 0;
            startingDisplayed = false;

            // There is the main base application window, even if it is exiting, wait for it
            mNumInterestingWindows = findMainWindow(false /* includeStartingApp */) != null ? 1 : 0;
        }

        final WindowStateAnimator winAnimator = w.mWinAnimator;

        boolean isInterestingAndDrawn = false;

        if (!allDrawn && w.mightAffectAllDrawn()) {
            if (DEBUG_VISIBILITY || WM_DEBUG_ORIENTATION.isLogToLogcat()) {
                final boolean isAnimationSet = isAnimating(TRANSITION | PARENTS,
                        ANIMATION_TYPE_APP_TRANSITION);
                Slog.v(TAG, "Eval win " + w + ": isDrawn=" + w.isDrawnLw()
                        + ", isAnimationSet=" + isAnimationSet);
                if (!w.isDrawnLw()) {
                    Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurfaceController
                            + " pv=" + w.isVisibleByPolicy()
                            + " mDrawState=" + winAnimator.drawStateToString()
                            + " ph=" + w.isParentWindowHidden() + " th=" + mVisibleRequested
                            + " a=" + isAnimationSet);
                }
            }

            if (w != startingWindow) {
                if (w.isInteresting()) {
                    // Add non-main window as interesting since the main app has already been added
                    if (findMainWindow(false /* includeStartingApp */) != w) {
                        mNumInterestingWindows++;
                    }
                    if (w.isDrawnLw()) {
                        mNumDrawnWindows++;

                        if (DEBUG_VISIBILITY || WM_DEBUG_ORIENTATION.isLogToLogcat()) {
                            Slog.v(TAG, "tokenMayBeDrawn: "
                                    + this + " w=" + w + " numInteresting=" + mNumInterestingWindows
                                    + " freezingScreen=" + mFreezingScreen
                                    + " mAppFreezing=" + w.mAppFreezing);
                        }

                        isInterestingAndDrawn = true;
                    }
                }
            } else if (w.isDrawnLw()) {
                // The starting window for this container is drawn.
                mStackSupervisor.getActivityMetricsLogger().notifyStartingWindowDrawn(this);
                startingDisplayed = true;
            }
        }

        return isInterestingAndDrawn;
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
                    !hasProcess() || app.getPid() == windowPid || windowPid == INVALID_PID;
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
            final ActivityStack stack = mRootWindowContainer.getTopDisplayFocusedStack();
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

    boolean canBeTopRunning() {
        return !finishing && okToShowLocked();
    }

    /**
     * This method will return true if the activity is either visible, is becoming visible, is
     * currently pausing, or is resumed.
     */
    public boolean isInterestingToUserLocked() {
        return mVisibleRequested || nowVisible || mState == PAUSING || mState == RESUMED;
    }

    void setSleeping(boolean sleeping) {
        mSetToSleep = sleeping;
    }

    static int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null || r.getParent() == null) {
            return INVALID_TASK_ID;
        }
        final Task task = r.task;
        if (onlyRoot && r.compareTo(task.getRootActivity(
                false /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/)) > 0) {
            return INVALID_TASK_ID;
        }
        return task.mTaskId;
    }

    static ActivityRecord isInStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return (r != null) ? r.getRootTask().isInTask(r) : null;
    }

    static ActivityStack getStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null) {
            return r.getRootTask();
        }
        return null;
    }

    /**
     * @return display id to which this record is attached,
     *         {@link android.view.Display#INVALID_DISPLAY} if not attached.
     */
    int getDisplayId() {
        final ActivityStack stack = getRootTask();
        if (stack == null) {
            return INVALID_DISPLAY;
        }
        return stack.getDisplayId();
    }

    final boolean isDestroyable() {
        if (finishing || !hasProcess()) {
            // This would be redundant.
            return false;
        }
        final ActivityStack stack = getRootTask();
        if (isState(RESUMED) || stack == null || this == stack.mPausingActivity || !mHaveState
                || !stopped) {
            // We're not ready for this kind of thing.
            return false;
        }
        if (mVisibleRequested) {
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
            final String iconFilename = createImageFilename(createTime, task.mTaskId);
            final File iconFile = new File(TaskPersister.getUserImagesDir(task.mUserId),
                    iconFilename);
            final String iconFilePath = iconFile.getAbsolutePath();
            mAtmService.getRecentTasks().saveImage(icon, iconFilePath);
            _taskDescription.setIconFilename(iconFilePath);
        }
        taskDescription = _taskDescription;
        getTask().updateTaskDescription();
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

    /**
     * If any activities below the top running one are in the INITIALIZING state and they have a
     * starting window displayed then remove that starting window. It is possible that the activity
     * in this state will never resumed in which case that starting window will be orphaned.
     * <p>
     * It should only be called if this activity is behind other fullscreen activity.
     */
    void cancelInitializing() {
        if (mStartingWindowState == STARTING_WINDOW_SHOWN) {
            // Remove orphaned starting window.
            if (DEBUG_VISIBILITY) Slog.w(TAG_VISIBILITY, "Found orphaned starting window " + this);
            mStartingWindowState = STARTING_WINDOW_REMOVED;
            removeStartingWindow();
        }
        if (isState(INITIALIZING) && !shouldBeVisible(
                true /* behindFullscreenActivity */, true /* ignoringKeyguard */)) {
            // Remove the unknown visibility record because an invisible activity shouldn't block
            // the keyguard transition.
            mDisplayContent.mUnknownAppVisibilityController.appRemovedOrHidden(this);
        }
    }

    void postWindowRemoveStartingWindowCleanup(WindowState win) {
        // TODO: Something smells about the code below...Is there a better way?
        if (startingWindow == win) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Notify removed startingWindow %s", win);
            removeStartingWindow();
        } else if (mChildren.size() == 0) {
            // If this is the last window and we had requested a starting transition window,
            // well there is no point now.
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Nulling last startingData");
            mStartingData = null;
            if (mVisibleSetFromTransferredStartingWindow) {
                // We set the visible state to true for the token from a transferred starting
                // window. We now reset it back to false since the starting window was the last
                // window in the token.
                setVisible(false);
            }
        } else if (mChildren.size() == 1 && startingSurface != null && !isRelaunching()) {
            // If this is the last window except for a starting transition window,
            // we need to get rid of the starting transition.
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Last window, removing starting window %s", win);
            removeStartingWindow();
        }
    }

    void removeDeadWindows() {
        for (int winNdx = mChildren.size() - 1; winNdx >= 0; --winNdx) {
            WindowState win = mChildren.get(winNdx);
            if (win.mAppDied) {
                ProtoLog.w(WM_DEBUG_ADD_REMOVE,
                        "removeDeadWindows: %s", win);
                // Set mDestroying, we don't want any animation or delayed removal here.
                win.mDestroying = true;
                // Also removes child windows.
                win.removeIfPossible();
            }
        }
    }

    boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            // No need to loop through child windows as the answer should be the same as that of the
            // parent window.
            if (!(mChildren.get(i)).mAppDied) {
                return true;
            }
        }
        return false;
    }

    void setWillReplaceWindows(boolean animate) {
        ProtoLog.d(WM_DEBUG_ADD_REMOVE,
                "Marking app token %s with replacing windows.", this);

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.setWillReplaceWindow(animate);
        }
    }

    void setWillReplaceChildWindows() {
        ProtoLog.d(WM_DEBUG_ADD_REMOVE, "Marking app token %s"
                + " with replacing child windows.", this);
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.setWillReplaceChildWindows();
        }
    }

    void clearWillReplaceWindows() {
        ProtoLog.d(WM_DEBUG_ADD_REMOVE,
                "Resetting app token %s of replacing window marks.", this);

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.clearWillReplaceWindow();
        }
    }

    void requestUpdateWallpaperIfNeeded() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.requestUpdateWallpaperIfNeeded();
        }
    }

    /**
     * @return The to top most child window for which {@link LayoutParams#isFullscreen()} returns
     *         true and isn't fully transparent.
     */
    WindowState getTopFullscreenOpaqueWindow() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            if (win != null && win.mAttrs.isFullscreen() && !win.isFullyTransparent()) {
                return win;
            }
        }
        return null;
    }

    WindowState findMainWindow() {
        return findMainWindow(true);
    }

    /**
     * Finds the main window that either has type base application or application starting if
     * requested.
     *
     * @param includeStartingApp Allow to search application-starting windows to also be returned.
     * @return The main window of type base application or application starting if requested.
     */
    WindowState findMainWindow(boolean includeStartingApp) {
        WindowState candidate = null;
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState win = mChildren.get(j);
            final int type = win.mAttrs.type;
            // No need to loop through child window as base application and starting types can't be
            // child windows.
            if (type == TYPE_BASE_APPLICATION
                    || (includeStartingApp && type == TYPE_APPLICATION_STARTING)) {
                // In cases where there are multiple windows, we prefer the non-exiting window. This
                // happens for example when replacing windows during an activity relaunch. When
                // constructing the animation, we want the new window, not the exiting one.
                if (win.mAnimatingExit) {
                    candidate = win;
                } else {
                    return win;
                }
            }
        }
        return candidate;
    }

    SurfaceControl getAppAnimationLayer() {
        return getAppAnimationLayer(isActivityTypeHome() ? ANIMATION_LAYER_HOME
                : needsZBoost() ? ANIMATION_LAYER_BOOSTED
                        : ANIMATION_LAYER_STANDARD);
    }

    @Override
    boolean needsZBoost() {
        return mNeedsZBoost || super.needsZBoost();
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        // For transitions in the pinned stack (menu activity) we just let them occur as a child
        // of the pinned stack.
        // All normal app transitions take place in an animation layer which is below the pinned
        // stack but may be above the parent stacks of the given animating apps by default. When
        // a new hierarchical animation is enabled, we just let them occur as a child of the parent
        // stack, i.e. the hierarchy of the surfaces is unchanged.
        if (inPinnedWindowingMode()) {
            return getStack().getSurfaceControl();
        } else if (WindowManagerService.sHierarchicalAnimations) {
            return super.getAnimationLeashParent();
        } else {
            return getAppAnimationLayer();
        }
    }

    @VisibleForTesting
    boolean shouldAnimate() {
        return task == null || task.shouldAnimate();
    }

    /**
     * Creates a layer to apply crop to an animation.
     */
    private SurfaceControl createAnimationBoundsLayer(Transaction t) {
        ProtoLog.i(WM_DEBUG_APP_TRANSITIONS_ANIM, "Creating animation bounds layer");
        final SurfaceControl.Builder builder = makeAnimationLeash()
                .setParent(getAnimationLeashParent())
                .setName(getSurfaceControl() + " - animation-bounds");
        final SurfaceControl boundsLayer = builder.build();
        t.show(boundsLayer);
        return boundsLayer;
    }

    @Override
    public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
        return mAnimatingActivityRegistry != null
                && mAnimatingActivityRegistry.notifyAboutToFinish(
                this, endDeferFinishCallback);
    }

    @Override
    boolean isWaitingForTransitionStart() {
        final DisplayContent dc = getDisplayContent();
        return dc != null && dc.mAppTransition.isTransitionSet()
                && (dc.mOpeningApps.contains(this)
                || dc.mClosingApps.contains(this)
                || dc.mChangingContainers.contains(this));
    }

    private int getAnimationLayer() {
        // The leash is parented to the animation layer. We need to preserve the z-order by using
        // the prefix order index, but we boost if necessary.
        int layer;
        if (!inPinnedWindowingMode()) {
            layer = getPrefixOrderIndex();
        } else {
            // Pinned stacks have animations take place within themselves rather than an animation
            // layer so we need to preserve the order relative to the stack (e.g. the order of our
            // task/parent).
            layer = getParent().getPrefixOrderIndex();
        }

        if (mNeedsZBoost) {
            layer += Z_BOOST_BASE;
        }
        return layer;
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        t.setLayer(leash, getAnimationLayer());
        getDisplayContent().assignStackOrdering();
    }

    @Override
    public void onLeashAnimationStarting(Transaction t, SurfaceControl leash) {
        if (mAnimatingActivityRegistry != null) {
            mAnimatingActivityRegistry.notifyStarting(this);
        }

        // If the animation needs to be cropped then an animation bounds layer is created as a child
        // of the pinned stack or animation layer. The leash is then reparented to this new layer.
        if (mNeedsAnimationBoundsLayer) {
            mTmpRect.setEmpty();
            if (getDisplayContent().mAppTransitionController.isTransitWithinTask(
                    getTransit(), task)) {
                task.getBounds(mTmpRect);
            } else {
                final ActivityStack stack = getStack();
                if (stack == null) {
                    return;
                }
                // Set clip rect to stack bounds.
                stack.getBounds(mTmpRect);
            }
            mAnimationBoundsLayer = createAnimationBoundsLayer(t);

            // Crop to stack bounds.
            if (!WindowManagerService.sHierarchicalAnimations) {
                // For Hierarchical animation, we don't need to set window crop since the leash
                // surface size has already same as the animating container.
                t.setWindowCrop(mAnimationBoundsLayer, mTmpRect);
            }
            t.setLayer(leash, 0);
            t.setLayer(mAnimationBoundsLayer, getAnimationLayer());

            // Reparent leash to animation bounds layer.
            t.reparent(leash, mAnimationBoundsLayer);
        }
    }

    @Override
    void prepareSurfaces() {
        final boolean show = isVisible() || isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION);

        if (mSurfaceControl != null) {
            if (show && !mLastSurfaceShowing) {
                getPendingTransaction().show(mSurfaceControl);
            } else if (!show && mLastSurfaceShowing) {
                getPendingTransaction().hide(mSurfaceControl);
            }
        }
        if (mThumbnail != null) {
            mThumbnail.setShowing(getPendingTransaction(), show);
        }
        mLastSurfaceShowing = show;
        super.prepareSurfaces();
    }

    /**
     * @return Whether our {@link #getSurfaceControl} is currently showing.
     */
    boolean isSurfaceShowing() {
        return mLastSurfaceShowing;
    }

    void attachThumbnailAnimation() {
        if (!isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION)) {
            return;
        }
        final HardwareBuffer thumbnailHeader =
                getDisplayContent().mAppTransition.getAppTransitionThumbnailHeader(task);
        if (thumbnailHeader == null) {
            ProtoLog.d(WM_DEBUG_APP_TRANSITIONS, "No thumbnail header bitmap for: %s", task);
            return;
        }
        clearThumbnail();
        final Transaction transaction = getAnimatingContainer().getPendingTransaction();
        mThumbnail = new WindowContainerThumbnail(mWmService.mSurfaceFactory,
                transaction, getAnimatingContainer(), thumbnailHeader);
        mThumbnail.startAnimation(transaction, loadThumbnailAnimation(thumbnailHeader));
    }

    /**
     * Attaches a surface with a thumbnail for the
     * {@link android.app.ActivityOptions#ANIM_OPEN_CROSS_PROFILE_APPS} animation.
     */
    void attachCrossProfileAppsThumbnailAnimation() {
        if (!isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION)) {
            return;
        }
        clearThumbnail();

        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        final Rect frame = win.getRelativeFrameLw();
        final int thumbnailDrawableRes = task.mUserId == mWmService.mCurrentUserId
                ? R.drawable.ic_account_circle
                : R.drawable.ic_corp_badge;
        final HardwareBuffer thumbnail =
                getDisplayContent().mAppTransition
                        .createCrossProfileAppsThumbnail(thumbnailDrawableRes, frame);
        if (thumbnail == null) {
            return;
        }
        final Transaction transaction = getPendingTransaction();
        mThumbnail = new WindowContainerThumbnail(mWmService.mSurfaceFactory,
                transaction, getTask(), thumbnail);
        final Animation animation =
                getDisplayContent().mAppTransition.createCrossProfileAppsThumbnailAnimationLocked(
                        frame);
        mThumbnail.startAnimation(transaction, animation, new Point(frame.left, frame.top));
    }

    private Animation loadThumbnailAnimation(HardwareBuffer thumbnailHeader) {
        final DisplayInfo displayInfo = mDisplayContent.getDisplayInfo();

        // If this is a multi-window scenario, we use the windows frame as
        // destination of the thumbnail header animation. If this is a full screen
        // window scenario, we use the whole display as the target.
        WindowState win = findMainWindow();
        Rect appRect = win != null ? win.getContentFrameLw() :
                new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        final Rect insets = win != null ? win.getContentInsets() : null;
        final Configuration displayConfig = mDisplayContent.getConfiguration();
        return getDisplayContent().mAppTransition.createThumbnailAspectScaleAnimationLocked(
                appRect, insets, thumbnailHeader, task, displayConfig.uiMode,
                displayConfig.orientation);
    }

    @Override
    public void onAnimationLeashLost(Transaction t) {
        super.onAnimationLeashLost(t);
        if (mAnimationBoundsLayer != null) {
            t.remove(mAnimationBoundsLayer);
            mAnimationBoundsLayer = null;
        }

        if (mAnimatingActivityRegistry != null) {
            mAnimatingActivityRegistry.notifyFinished(this);
        }
    }

    @Override
    protected void onAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
        super.onAnimationFinished(type, anim);

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "AR#onAnimationFinished");
        mTransit = TRANSIT_UNSET;
        mTransitFlags = 0;
        mNeedsZBoost = false;
        mNeedsAnimationBoundsLayer = false;

        setAppLayoutChanges(FINISH_LAYOUT_REDO_ANIM | FINISH_LAYOUT_REDO_WALLPAPER,
                "ActivityRecord");

        clearThumbnail();
        setClientVisible(isVisible() || mVisibleRequested);

        getDisplayContent().computeImeTargetIfNeeded(this);

        if (DEBUG_ANIM) Slog.v(TAG, "Animation done in " + this
                + ": reportedVisible=" + reportedVisible
                + " okToDisplay=" + okToDisplay()
                + " okToAnimate=" + okToAnimate()
                + " startingDisplayed=" + startingDisplayed);

        // clean up thumbnail window
        if (mThumbnail != null) {
            mThumbnail.destroy();
            mThumbnail = null;
        }

        // WindowState.onExitAnimationDone might modify the children list, so make a copy and then
        // traverse the copy.
        final ArrayList<WindowState> children = new ArrayList<>(mChildren);
        children.forEach(WindowState::onExitAnimationDone);

        getDisplayContent().mAppTransition.notifyAppTransitionFinishedLocked(token);
        scheduleAnimation();

        if (!mStackSupervisor.mStoppingActivities.isEmpty()
                || !mStackSupervisor.mFinishingActivities.isEmpty()) {
            if (mRootWindowContainer.allResumedActivitiesIdle()) {
                // If all activities are already idle then we now need to make sure we perform
                // the full stop of this activity. This is because we won't do that while they
                // are still waiting for the animation to finish.
                mStackSupervisor.scheduleIdle();
            } else if (mRootWindowContainer.allResumedActivitiesVisible()) {
                // If all resumed activities are already visible (and should be drawn, see
                // updateReportedVisibility ~ nowVisible) but not idle, we still schedule to
                // process the stopping and finishing activities because the transition is done.
                // This also avoids if the next activity never reports idle (e.g. animating view),
                // the previous will need to wait until idle timeout to be stopped or destroyed.
                mStackSupervisor.scheduleProcessStoppingAndFinishingActivities();
            }
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    void clearAnimatingFlags() {
        boolean wallpaperMightChange = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState win = mChildren.get(i);
            wallpaperMightChange |= win.clearAnimatingFlags();
        }
        if (wallpaperMightChange) {
            requestUpdateWallpaperIfNeeded();
        }
    }

    @Override
    void cancelAnimation() {
        cancelAnimationOnly();
        clearThumbnail();
        mSurfaceFreezer.unfreeze(getPendingTransaction());
    }

    /**
     * This only cancels the animation. It doesn't do other teardown like cleaning-up thumbnail
     * or interim leashes.
     * <p>
     * Used when canceling in preparation for starting a new animation.
     */
    void cancelAnimationOnly() {
        super.cancelAnimation();
    }

    @VisibleForTesting
    WindowContainerThumbnail getThumbnail() {
        return mThumbnail;
    }

    private void clearThumbnail() {
        if (mThumbnail == null) {
            return;
        }
        mThumbnail.destroy();
        mThumbnail = null;
    }

    public int getTransit() {
        return mTransit;
    }

    int getTransitFlags() {
        return mTransitFlags;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mRemoteAnimationDefinition = definition;
        if (definition != null) {
            definition.linkToDeath(this::unregisterRemoteAnimations);
        }
    }

    void unregisterRemoteAnimations() {
        mRemoteAnimationDefinition = null;
    }

    @Override
    RemoteAnimationDefinition getRemoteAnimationDefinition() {
        return mRemoteAnimationDefinition;
    }

    @Override
    void applyFixedRotationTransform(DisplayInfo info, DisplayFrames displayFrames,
            Configuration config) {
        super.applyFixedRotationTransform(info, displayFrames, config);
        ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
    }

    @Override
    void onCancelFixedRotationTransform(int originalDisplayRotation) {
        if (this != mDisplayContent.getLastOrientationSource()
                || getRequestedConfigurationOrientation() != ORIENTATION_UNDEFINED) {
            // Only need to handle the activity that should be rotated with display.
            return;
        }

        // Perform rotation animation according to the rotation of this activity.
        startFreezingScreen(originalDisplayRotation);
        // This activity may relaunch or perform configuration change so once it has reported drawn,
        // the screen can be unfrozen.
        ensureActivityConfiguration(0 /* globalChanges */, !PRESERVE_WINDOWS);
    }

    void setRequestedOrientation(int requestedOrientation) {
        setOrientation(requestedOrientation, mayFreezeScreenLocked());
        mAtmService.getTaskChangeNotificationController().notifyActivityRequestedOrientationChanged(
                task.mTaskId, requestedOrientation);
    }

    private void setOrientation(int requestedOrientation, boolean freezeScreenIfNeeded) {
        final IBinder binder =
                (freezeScreenIfNeeded && appToken != null) ? appToken.asBinder() : null;
        setOrientation(requestedOrientation, binder, this);

        // Push the new configuration to the requested app in case where it's not pushed, e.g. when
        // the request is handled at task level with letterbox.
        if (!getMergedOverrideConfiguration().equals(
                mLastReportedConfiguration.getMergedConfiguration())) {
            ensureActivityConfiguration(0 /* globalChanges */, false /* preserveWindow */);
        }
    }

    void reportDescendantOrientationChangeIfNeeded() {
        // Orientation request is exposed only when we're visible. Therefore visibility change
        // will change requested orientation. Notify upward the hierarchy ladder to adjust
        // configuration. This is important to cases where activities with incompatible
        // orientations launch, or user goes back from an activity of bi-orientation to an
        // activity with specified orientation.
        if (getRequestedOrientation() == SCREEN_ORIENTATION_UNSET) {
            return;
        }

        final IBinder freezeToken = mayFreezeScreenLocked() ? appToken : null;
        onDescendantOrientationChanged(freezeToken, this);
    }

    /**
     * We override because this class doesn't want its children affecting its reported orientation
     * in anyway.
     */
    @Override
    int getOrientation(int candidate) {
        if (candidate == SCREEN_ORIENTATION_BEHIND) {
            // Allow app to specify orientation regardless of its visibility state if the current
            // candidate want us to use orientation behind. I.e. the visible app on-top of this one
            // wants us to use the orientation of the app behind it.
            return mOrientation;
        }

        // The {@link ActivityRecord} should only specify an orientation when it is not closing.
        // Allowing closing {@link ActivityRecord} to participate can lead to an Activity in another
        // task being started in the wrong orientation during the transition.
        if (!getDisplayContent().mClosingApps.contains(this)
                && (isVisible() || getDisplayContent().mOpeningApps.contains(this))) {
            return mOrientation;
        }

        return SCREEN_ORIENTATION_UNSET;
    }

    /** Returns the app's preferred orientation regardless of its currently visibility state. */
    int getRequestedOrientation() {
        return mOrientation;
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
     * @return {@code true} if this activity is in size compatibility mode that uses the different
     *         density than its parent or its bounds don't fit in parent naturally.
     */
    boolean inSizeCompatMode() {
        if (mCompatDisplayInsets == null || !shouldUseSizeCompatMode()
                // The orientation is different from parent when transforming.
                || isFixedRotationTransforming()) {
            return false;
        }
        final Rect appBounds = getConfiguration().windowConfiguration.getAppBounds();
        if (appBounds == null) {
            // The app bounds hasn't been computed yet.
            return false;
        }

        final Configuration parentConfig = getParent().getConfiguration();
        // Although colorMode, screenLayout, smallestScreenWidthDp are also fixed, generally these
        // fields should be changed with density and bounds, so here only compares the most
        // significant field.
        if (parentConfig.densityDpi != getConfiguration().densityDpi) {
            return true;
        }

        final Rect parentAppBounds = parentConfig.windowConfiguration.getAppBounds();
        final int appWidth = appBounds.width();
        final int appHeight = appBounds.height();
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
        if (inMultiWindowMode() || getWindowConfiguration().hasWindowDecorCaption()) {
            final ActivityRecord root = task != null ? task.getRootActivity() : null;
            if (root != null && root != this && !root.shouldUseSizeCompatMode()) {
                // If the root activity doesn't use size compatibility mode, the activities above
                // are forced to be the same for consistent visual appearance.
                return false;
            }
        }
        return !isResizeable() && (info.isFixedOrientation() || info.hasFixedAspectRatio())
                // The configuration of non-standard type should be enforced by system.
                && isActivityTypeStandard()
                && !mAtmService.mForceResizableActivities;
    }

    boolean hasSizeCompatBounds() {
        return mSizeCompatBounds != null;
    }

    // TODO(b/36505427): Consider moving this method and similar ones to ConfigurationContainer.
    private void updateSizeCompatMode() {
        if (mCompatDisplayInsets != null || !shouldUseSizeCompatMode()) {
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

        Configuration overrideConfig = getRequestedOverrideConfiguration();
        final Configuration fullConfig = getConfiguration();

        // Ensure the screen related fields are set. It is used to prevent activity relaunch
        // when moving between displays. For screenWidthDp and screenWidthDp, because they
        // are relative to bounds and density, they will be calculated in
        // {@link Task#computeConfigResourceOverrides} and the result will also be
        // relatively fixed.
        overrideConfig.colorMode = fullConfig.colorMode;
        overrideConfig.densityDpi = fullConfig.densityDpi;
        // The smallest screen width is the short side of screen bounds. Because the bounds
        // and density won't be changed, smallestScreenWidthDp is also fixed.
        overrideConfig.smallestScreenWidthDp = fullConfig.smallestScreenWidthDp;
        if (info.isFixedOrientation()) {
            // lock rotation too. When in size-compat, onConfigurationChanged will watch for and
            // apply runtime rotation changes.
            overrideConfig.windowConfiguration.setRotation(
                    fullConfig.windowConfiguration.getRotation());
        }

        // The role of CompatDisplayInsets is like the override bounds.
        mCompatDisplayInsets = new CompatDisplayInsets(mDisplayContent, this);
    }

    @VisibleForTesting
    void clearSizeCompatMode() {
        mSizeCompatScale = 1f;
        mSizeCompatBounds = null;
        mCompatDisplayInsets = null;
        onRequestedOverrideConfigurationChanged(EMPTY);
    }

    @Override
    public boolean matchParentBounds() {
        if (super.matchParentBounds() && mCompatDisplayInsets == null) {
            return true;
        }
        // An activity in size compatibility mode may have resolved override bounds, so the exact
        // bounds should also be checked. Otherwise IME window will show with offset. See
        // {@link DisplayContent#isImeAttachedToApp}.
        final WindowContainer parent = getParent();
        return parent == null || parent.getBounds().equals(getResolvedOverrideBounds());
    }

    @Override
    float getSizeCompatScale() {
        return hasSizeCompatBounds() ? mSizeCompatScale : super.getSizeCompatScale();
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfiguration) {
        super.resolveOverrideConfiguration(newParentConfiguration);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        if (isFixedRotationTransforming()) {
            // The resolved configuration is applied with rotated display configuration. If this
            // activity matches its parent (the following resolving procedures are no-op), then it
            // can use the resolved configuration directly. Otherwise (e.g. fixed aspect ratio),
            // the rotated configuration is used as parent configuration to compute the actual
            // resolved configuration. It is like putting the activity in a rotated container.
            mTmpConfig.setTo(newParentConfiguration);
            mTmpConfig.updateFrom(resolvedConfig);
            newParentConfiguration = mTmpConfig;
        }
        if (mCompatDisplayInsets != null) {
            resolveSizeCompatModeConfiguration(newParentConfiguration);
        } else {
            if (inMultiWindowMode()) {
                // We ignore activities' requested orientation in multi-window modes. Task level may
                // take them into consideration when calculating bounds.
                resolvedConfig.orientation = Configuration.ORIENTATION_UNDEFINED;
                // If the activity has requested override bounds, the configuration needs to be
                // computed accordingly.
                if (!matchParentBounds()) {
                    task.computeConfigResourceOverrides(resolvedConfig, newParentConfiguration);
                }
            } else {
                resolveFullscreenConfiguration(newParentConfiguration);
            }
        }

        // Assign configuration sequence number into hierarchy because there is a different way than
        // ensureActivityConfiguration() in this class that uses configuration in WindowState during
        // layout traversals.
        mConfigurationSeq = Math.max(++mConfigurationSeq, 1);
        getResolvedOverrideConfiguration().seq = mConfigurationSeq;
    }

    /**
     * Resolves the configuration of activity in fullscreen mode. If the bounds are restricted by
     * aspect ratio, the position will be centered horizontally in parent's app bounds to balance
     * the visual appearance. The policy of aspect ratio has higher priority than the requested
     * override bounds.
     */
    private void resolveFullscreenConfiguration(Configuration newParentConfiguration) {
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect parentAppBounds = newParentConfiguration.windowConfiguration.getAppBounds();
        final Rect parentBounds = newParentConfiguration.windowConfiguration.getBounds();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();
        // Use tmp bounds to calculate aspect ratio so we can know whether the activity should use
        // restricted size (resolved bounds may be the requested override bounds).
        mTmpBounds.setEmpty();
        applyAspectRatio(mTmpBounds, parentAppBounds, parentBounds);
        // If the out bounds is not empty, it means the activity cannot fill parent's app bounds,
        // then there is space to be centered.
        final boolean needToBeCentered = !mTmpBounds.isEmpty();
        if (needToBeCentered) {
            resolvedBounds.set(mTmpBounds);
            // Exclude the horizontal decor area.
            resolvedBounds.left = parentAppBounds.left;
        }
        if (!resolvedBounds.isEmpty() && !resolvedBounds.equals(parentBounds)) {
            // Compute the configuration based on the resolved bounds. If aspect ratio doesn't
            // restrict, the bounds should be the requested override bounds.
            task.computeConfigResourceOverrides(resolvedConfig, newParentConfiguration,
                    getFixedRotationTransformDisplayInfo());
        }
        if (needToBeCentered) {
            // Offset to center relative to parent's app bounds.
            final int offsetX = getHorizontalCenterOffset(
                    parentAppBounds.width(), resolvedBounds.width());
            offsetBounds(resolvedConfig, offsetX, 0 /* offsetY */);
        }
    }

    /**
     * Resolves consistent screen configuration for orientation and rotation changes without
     * inheriting the parent bounds.
     */
    private void resolveSizeCompatModeConfiguration(Configuration newParentConfiguration) {
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();
        final int requestedOrientation = getRequestedConfigurationOrientation();
        final boolean orientationRequested = requestedOrientation != ORIENTATION_UNDEFINED;
        final int orientation = orientationRequested
                ? requestedOrientation
                : newParentConfiguration.orientation;
        int rotation = newParentConfiguration.windowConfiguration.getRotation();
        final boolean canChangeOrientation = handlesOrientationChangeFromDescendant();
        if (canChangeOrientation && mCompatDisplayInsets.mIsRotatable
                && !mCompatDisplayInsets.mIsFloating) {
            // Use parent rotation because the original display can rotate by requested orientation.
            resolvedConfig.windowConfiguration.setRotation(rotation);
        } else {
            final int overrideRotation = resolvedConfig.windowConfiguration.getRotation();
            if (overrideRotation != ROTATION_UNDEFINED) {
                rotation = overrideRotation;
            }
        }

        // Use compat insets to lock width and height. We should not use the parent width and height
        // because apps in compat mode should have a constant width and height. The compat insets
        // are locked when the app is first launched and are never changed after that, so we can
        // rely on them to contain the original and unchanging width and height of the app.
        final Rect containingAppBounds = new Rect();
        final Rect containingBounds = mTmpBounds;
        mCompatDisplayInsets.getContainerBounds(containingAppBounds, containingBounds, rotation,
                orientation, orientationRequested, canChangeOrientation);
        resolvedBounds.set(containingBounds);
        // The size of floating task is fixed (only swap), so the aspect ratio is already correct.
        if (!mCompatDisplayInsets.mIsFloating) {
            applyAspectRatio(resolvedBounds, containingAppBounds, containingBounds);
        }
        // If the bounds are restricted by fixed aspect ratio, the resolved bounds should be put in
        // the container app bounds. Otherwise the entire container bounds are available.
        final boolean fillContainer = resolvedBounds.equals(containingBounds);
        if (!fillContainer) {
            // The horizontal position should not cover insets.
            resolvedBounds.left = containingAppBounds.left;
        }

        // Use resolvedBounds to compute other override configurations such as appBounds. The bounds
        // are calculated in compat container space. The actual position on screen will be applied
        // later, so the calculation is simpler that doesn't need to involve offset from parent.
        task.computeConfigResourceOverrides(resolvedConfig, newParentConfiguration,
                mCompatDisplayInsets);
        // Use current screen layout as source because the size of app is independent to parent.
        resolvedConfig.screenLayout = Task.computeScreenLayoutOverride(
                getConfiguration().screenLayout, resolvedConfig.screenWidthDp,
                resolvedConfig.screenHeightDp);

        // Use parent orientation if it cannot be decided by bounds, so the activity can fit inside
        // the parent bounds appropriately.
        if (resolvedConfig.screenWidthDp == resolvedConfig.screenHeightDp) {
            resolvedConfig.orientation = newParentConfiguration.orientation;
        }

        // Below figure is an example that puts an activity which was launched in a larger container
        // into a smaller container.
        //   The outermost rectangle is the real display bounds.
        //   "@" is the parent app bounds.
        //   "#" is the {@code resolvedBounds} that applies to application.
        //   "*" is the {@code mSizeCompatBounds} that used to show on screen if scaled.
        // ------------------------------
        // |                            |
        // |    @@@@*********@@@@###    |
        // |    @   *       *   @  #    |
        // |    @   *       *   @  #    |
        // |    @   *       *   @  #    |
        // |    @@@@*********@@@@  #    |
        // ---------#--------------#-----
        //          #              #
        //          ################
        // The application is still layouted in "#" since it was launched, and it will be visually
        // scaled and positioned to "*".

        // Calculates the scale and offset to horizontal center the size compatibility bounds into
        // the region which is available to application.
        final Rect parentBounds = newParentConfiguration.windowConfiguration.getBounds();
        final Rect parentAppBounds = newParentConfiguration.windowConfiguration.getAppBounds();
        final Rect resolvedAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
        final int contentW = resolvedAppBounds.width();
        final int contentH = resolvedAppBounds.height();
        final int viewportW = parentAppBounds.width();
        final int viewportH = parentAppBounds.height();
        // Only allow to scale down.
        mSizeCompatScale = (contentW <= viewportW && contentH <= viewportH)
                ? 1f : Math.min((float) viewportW / contentW, (float) viewportH / contentH);
        final int screenTopInset = parentAppBounds.top - parentBounds.top;
        final boolean topNotAligned = screenTopInset != resolvedAppBounds.top - resolvedBounds.top;
        if (mSizeCompatScale != 1f || topNotAligned) {
            if (mSizeCompatBounds == null) {
                mSizeCompatBounds = new Rect();
            }
            mSizeCompatBounds.set(resolvedAppBounds);
            mSizeCompatBounds.offsetTo(0, 0);
            mSizeCompatBounds.scale(mSizeCompatScale);
            // The insets are included in height, e.g. the area of real cutout shouldn't be scaled.
            mSizeCompatBounds.bottom += screenTopInset;
        } else {
            mSizeCompatBounds = null;
        }

        // Center horizontally in parent (app bounds) and align to top of parent (bounds)
        // - this is a UX choice.
        final int offsetX = getHorizontalCenterOffset(
                (int) viewportW, (int) (contentW * mSizeCompatScale));
        // Above coordinates are in "@" space, now place "*" and "#" to screen space.
        final int screenPosX = (fillContainer ? parentBounds.left : parentAppBounds.left) + offsetX;
        final int screenPosY = parentBounds.top;
        if (screenPosX != 0 || screenPosY != 0) {
            if (mSizeCompatBounds != null) {
                mSizeCompatBounds.offset(screenPosX, screenPosY);
            }
            // Add the global coordinates and remove the local coordinates.
            final int dx = screenPosX - resolvedBounds.left;
            final int dy = screenPosY - resolvedBounds.top;
            offsetBounds(resolvedConfig, dx, dy);
        }
    }

    /** @return The horizontal offset of putting the content in the center of viewport. */
    private static int getHorizontalCenterOffset(int viewportW, int contentW) {
        return (int) ((viewportW - contentW + 1) * 0.5f);
    }

    private static void offsetBounds(Configuration inOutConfig, int offsetX, int offsetY) {
        inOutConfig.windowConfiguration.getBounds().offset(offsetX, offsetY);
        inOutConfig.windowConfiguration.getAppBounds().offset(offsetX, offsetY);
    }

    @Override
    public Rect getBounds() {
        if (mSizeCompatBounds != null) {
            return mSizeCompatBounds;
        }
        return super.getBounds();
    }

    @VisibleForTesting
    @Override
    Rect getAnimationBounds(int appStackClipMode) {
        if (appStackClipMode == STACK_CLIP_BEFORE_ANIM && getStack() != null) {
            // Using the stack bounds here effectively applies the clipping before animation.
            return getStack().getBounds();
        }
        // Use task-bounds if available so that activity-level letterbox (maxAspectRatio) is
        // included in the animation.
        return task != null ? task.getBounds() : getBounds();
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        if (mCompatDisplayInsets != null) {
            Configuration overrideConfig = getRequestedOverrideConfiguration();
            // Adapt to changes in orientation locking. The app is still non-resizable, but
            // it can change which orientation is fixed. If the fixed orientation changes,
            // update the rotation used on the "compat" display
            boolean wasFixedOrient =
                    overrideConfig.windowConfiguration.getRotation() != ROTATION_UNDEFINED;
            int requestedOrient = getRequestedConfigurationOrientation();
            if (requestedOrient != ORIENTATION_UNDEFINED
                    && requestedOrient != getConfiguration().orientation
                    // The task orientation depends on the top activity orientation, so it
                    // should match. If it doesn't, just wait until it does.
                    && requestedOrient == getParent().getConfiguration().orientation
                    && (overrideConfig.windowConfiguration.getRotation()
                            != getParent().getWindowConfiguration().getRotation())) {
                overrideConfig.windowConfiguration.setRotation(
                        getParent().getWindowConfiguration().getRotation());
                onRequestedOverrideConfigurationChanged(overrideConfig);
                return;
            } else if (wasFixedOrient && requestedOrient == ORIENTATION_UNDEFINED
                    && (overrideConfig.windowConfiguration.getRotation()
                            != ROTATION_UNDEFINED)) {
                overrideConfig.windowConfiguration.setRotation(ROTATION_UNDEFINED);
                onRequestedOverrideConfigurationChanged(overrideConfig);
                return;
            }
        }
        super.onConfigurationChanged(newParentConfig);

        // Configuration's equality doesn't consider seq so if only seq number changes in resolved
        // override configuration. Therefore ConfigurationContainer doesn't change merged override
        // configuration, but it's used to push configuration changes so explicitly update that.
        if (getMergedOverrideConfiguration().seq != getResolvedOverrideConfiguration().seq) {
            onMergedOverrideConfigurationChanged();
        }

        final DisplayContent display = getDisplay();
        if (display == null) {
            return;
        }
        if (mVisibleRequested) {
            // It may toggle the UI for user to restart the size compatibility mode activity.
            display.handleActivitySizeCompatModeIfNeeded(this);
        } else if (mCompatDisplayInsets != null) {
            // The override changes can only be obtained from display, because we don't have the
            // difference of full configuration in each hierarchy.
            final int displayChanges = display.getCurrentOverrideConfigurationChanges();
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
        final int orientation = getRequestedOrientation();
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
     * Applies aspect ratio restrictions to outBounds. If no restrictions, then no change is
     * made to outBounds.
     */
    // TODO(b/36505427): Consider moving this method and similar ones to ConfigurationContainer.
    private void applyAspectRatio(Rect outBounds, Rect containingAppBounds,
            Rect containingBounds) {
        final float maxAspectRatio = info.maxAspectRatio;
        final ActivityStack stack = getRootTask();
        final float minAspectRatio = info.minAspectRatio;

        if (task == null || stack == null || (inMultiWindowMode() && !shouldUseSizeCompatMode())
                || (maxAspectRatio == 0 && minAspectRatio == 0)
                || isInVrUiMode(getConfiguration())) {
            // We don't enforce aspect ratio if the activity task is in multiwindow unless it
            // is in size-compat mode. We also don't set it if we are in VR mode.
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
            return;
        }

        // Compute configuration based on max supported width and height.
        // Also account for the left / top insets (e.g. from display cutouts), which will be clipped
        // away later in {@link Task#computeConfigResourceOverrides()}. Otherwise, the app
        // bounds would end up too small.
        outBounds.set(containingBounds.left, containingBounds.top,
                activityWidth + containingAppBounds.left,
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
        final ActivityStack stack = getRootTask();
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
        updateSizeCompatMode();

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

        setLastReportedConfiguration(getProcessGlobalConfiguration(), newMergedOverrideConfig);

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
            startFreezingScreenLocked(globalChanges);
            forceNewConfig = false;
            preserveWindow &= isResizeOnlyChange(changes);
            final boolean hasResizeChange = hasResizeChange(changes & ~info.getRealConfigChanged());
            if (hasResizeChange) {
                final boolean isDragResizing = task.isDragResizing();
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
            } else {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is relaunching " + this);
                if (DEBUG_STATES && !mVisibleRequested) {
                    Slog.v(TAG_STATES, "Config is relaunching invisible activity " + this
                            + " called by " + Debug.getCallers(4));
                }
                relaunchActivityLocked(preserveWindow);
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

    /** Get process configuration, or global config if the process is not set. */
    private Configuration getProcessGlobalConfiguration() {
        return app != null ? app.getConfiguration() : mAtmService.getGlobalConfiguration();
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

    void relaunchActivityLocked(boolean preserveWindow) {
        if (mAtmService.mSuppressResizeConfigChanges && preserveWindow) {
            configChangeFlags = 0;
            return;
        }

        final boolean andResume = shouldBeResumed(null /*activeActivity*/);
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
        if (andResume) {
            EventLogTags.writeWmRelaunchResumeActivity(mUserId, System.identityHashCode(this),
                    task.mTaskId, shortComponentName);
        } else {
            EventLogTags.writeWmRelaunchActivity(mUserId, System.identityHashCode(this),
                    task.mTaskId, shortComponentName);
        }

        startFreezingScreenLocked(0);

        try {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_SWITCH,
                    "Moving to " + (andResume ? "RESUMED" : "PAUSED") + " Relaunching " + this
                            + " callers=" + Debug.getCallers(6));
            forceNewConfig = false;
            startRelaunching();
            final ClientTransactionItem callbackItem = ActivityRelaunchItem.obtain(pendingResults,
                    pendingNewIntents, configChangeFlags,
                    new MergedConfiguration(getProcessGlobalConfiguration(),
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
            removePauseTimeout();
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
        clearSizeCompatMode();
        if (mVisibleRequested) {
            // Configuration will be ensured when becoming visible, so if it is already visible,
            // then the manual update is needed.
            updateSizeCompatMode();
        }

        if (!attachedToProcess()) {
            return;
        }

        // The restarting state avoids removing this record when process is died.
        setState(RESTARTING_PROCESS, "restartActivityProcess");

        if (!mVisibleRequested || mHaveState) {
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

        if (getParent() != null) {
            startFreezingScreen();
        }
        // The process will be killed until the activity reports stopped with saved state (see
        // {@link ActivityTaskManagerService.activityStopped}).
        try {
            mAtmService.getLifecycleManager().scheduleTransaction(app.getThread(), appToken,
                    StopActivityItem.obtain(0 /* configChanges */));
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

        // Restrict task snapshot starting window to launcher start, or is same as the last
        // delivered intent, or there is no intent at all (eg. task being brought to front). If
        // the intent is something else, likely the app is going to show some specific page or
        // view, instead of what's left last time.
        for (int i = newIntents.size() - 1; i >= 0; i--) {
            final Intent intent = newIntents.get(i);
            if (intent == null || ActivityRecord.isMainIntent(intent)) {
                continue;
            }

            final boolean sameIntent = mLastNewIntent != null ? mLastNewIntent.filterEquals(intent)
                    : this.intent.filterEquals(intent);
            if (!sameIntent || intent.getExtras() != null) {
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
        if (launchedFromFeatureId != null) {
            out.attribute(null, ATTR_LAUNCHEDFROMFEATURE, launchedFromFeatureId);
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
        String launchedFromFeature = null;
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
            } else if (ATTR_LAUNCHEDFROMFEATURE.equals(attrName)) {
                launchedFromFeature = attrValue;
            } else if (ATTR_RESOLVEDTYPE.equals(attrName)) {
                resolvedType = attrValue;
            } else if (ATTR_COMPONENTSPECIFIED.equals(attrName)) {
                componentSpecified = Boolean.parseBoolean(attrValue);
            } else if (ATTR_USERID.equals(attrName)) {
                userId = Integer.parseInt(attrValue);
            } else if (!attrName.startsWith(ATTR_TASKDESCRIPTION_PREFIX)) {
                Log.d(TAG, "Unknown ActivityRecord attribute=" + attrName);
            }
        }
        taskDescription.restoreFromXml(in);

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
                0 /* launchedFromPid */, launchedFromUid, launchedFromPackage, launchedFromFeature,
                intent, resolvedType, aInfo, service.getConfiguration(), null /* resultTo */,
                null /* resultWho */, 0 /* reqCode */, componentSpecified,
                false /* rootVoiceInteraction */, stackSupervisor, null /* options */,
                null /* sourceRecord */);

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

    boolean isUid(int uid) {
        return info.applicationInfo.uid == uid;
    }

    int getPid() {
        return app != null ? app.getPid() : 0;
    }

    /**
     * Determines whether this ActivityRecord can turn the screen on. It checks whether the flag
     * {@link ActivityRecord#getTurnScreenOnFlag} is set and checks whether the ActivityRecord
     * should be visible depending on Keyguard state
     *
     * @return true if the screen can be turned on, false otherwise.
     */
    boolean canTurnScreenOn() {
        if (!getTurnScreenOnFlag()) {
            return false;
        }
        final ActivityStack stack = getRootTask();
        return stack != null &&
                stack.checkKeyguardVisibility(this, true /* shouldBeVisible */,
                        stack.topRunningActivity() == this /* isTop */);
    }

    void setTurnScreenOn(boolean turnScreenOn) {
        mTurnScreenOn = turnScreenOn;
    }

    boolean getTurnScreenOnFlag() {
        return mTurnScreenOn || containsTurnScreenOnWindow();
    }

    private boolean containsTurnScreenOnWindow() {
        // When we are relaunching, it is possible for us to be unfrozen before our previous
        // windows have been added back. Using the cached value ensures that our previous
        // showWhenLocked preference is honored until relaunching is complete.
        if (isRelaunching()) {
            return mLastContainsTurnScreenOnWindow;
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & LayoutParams.FLAG_TURN_SCREEN_ON) != 0) {
                return true;
            }
        }
        return false;
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

    boolean isTopRunningActivity() {
        return mRootWindowContainer.topRunningActivity() == this;
    }

    /**
     * @return {@code true} if this is the resumed activity on its current display, {@code false}
     * otherwise.
     */
    boolean isResumedActivityOnDisplay() {
        final DisplayContent display = getDisplay();
        if (display == null) {
            return false;
        }
        for (int tdaNdx = display.getTaskDisplayAreaCount() - 1; tdaNdx >= 0; --tdaNdx) {
            final TaskDisplayArea taskDisplayArea = display.getTaskDisplayAreaAt(tdaNdx);
            final ActivityRecord resumedActivity = taskDisplayArea.getFocusedActivity();
            if (resumedActivity != null) {
                return resumedActivity == this;
            }
        }
        return false;
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
        final ActivityRecord rootActivity = task.getRootActivity(true);
        return this == rootActivity;
    }

    void setTaskOverlay(boolean taskOverlay) {
        mTaskOverlay = taskOverlay;
        setAlwaysOnTop(mTaskOverlay);
    }

    boolean isTaskOverlay() {
        return mTaskOverlay;
    }

    @Override
    boolean showToCurrentUser() {
        return mShowForAllUsers || mWmService.isCurrentProfile(mUserId);
    }

    @Override
    public String toString() {
        if (stringName != null) {
            return stringName + " t" + (task == null ? INVALID_TASK_ID : task.mTaskId) +
                    (finishing ? " f}" : "") + (mIsExiting ? " mIsExiting=" : "") + "}";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ActivityRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" u");
        sb.append(mUserId);
        sb.append(' ');
        sb.append(intent.getComponent().flattenToShortString());
        stringName = sb.toString();
        return stringName;
    }

    /**
     * Write all fields to an {@code ActivityRecordProto}. This assumes the
     * {@code ActivityRecordProto} is the outer-most proto data.
     */
    void dumpDebug(ProtoOutputStream proto, @WindowTraceLogLevel int logLevel) {
        writeNameToProto(proto, NAME);
        super.dumpDebug(proto, WINDOW_TOKEN, logLevel);
        proto.write(LAST_SURFACE_SHOWING, mLastSurfaceShowing);
        proto.write(IS_WAITING_FOR_TRANSITION_START, isWaitingForTransitionStart());
        proto.write(IS_ANIMATING, isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION));
        if (mThumbnail != null){
            mThumbnail.dumpDebug(proto, THUMBNAIL);
        }
        proto.write(FILLS_PARENT, mOccludesParent);
        proto.write(APP_STOPPED, mAppStopped);
        proto.write(TRANSLUCENT, !occludesParent());
        proto.write(VISIBLE, mVisible);
        proto.write(VISIBLE_REQUESTED, mVisibleRequested);
        proto.write(CLIENT_VISIBLE, mClientVisible);
        proto.write(DEFER_HIDING_CLIENT, mDeferHidingClient);
        proto.write(REPORTED_DRAWN, reportedDrawn);
        proto.write(REPORTED_VISIBLE, reportedVisible);
        proto.write(NUM_INTERESTING_WINDOWS, mNumInterestingWindows);
        proto.write(NUM_DRAWN_WINDOWS, mNumDrawnWindows);
        proto.write(ALL_DRAWN, allDrawn);
        proto.write(LAST_ALL_DRAWN, mLastAllDrawn);
        if (startingWindow != null) {
            startingWindow.writeIdentifierToProto(proto, STARTING_WINDOW);
        }
        proto.write(STARTING_DISPLAYED, startingDisplayed);
        proto.write(STARTING_MOVED, startingMoved);
        proto.write(VISIBLE_SET_FROM_TRANSFERRED_STARTING_WINDOW,
                mVisibleSetFromTransferredStartingWindow);
        for (Rect bounds : mFrozenBounds) {
            bounds.dumpDebug(proto, FROZEN_BOUNDS);
        }

        writeIdentifierToProto(proto, IDENTIFIER);
        proto.write(STATE, mState.toString());
        proto.write(FRONT_OF_TASK, isRootOfTask());
        if (hasProcess()) {
            proto.write(PROC_ID, app.getPid());
        }
    }

    @Override
    long getProtoFieldId() {
        return ACTIVITY;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        // Critical log level logs only visible elements to mitigate performance overheard
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        dumpDebug(proto, logLevel);
        proto.end(token);
    }

    void writeNameToProto(ProtoOutputStream proto, long fieldId) {
        if (appToken != null) {
            proto.write(fieldId, appToken.getName());
        }
    }

    @Override
    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, mUserId);
        proto.write(TITLE, intent.getComponent().flattenToShortString());
        proto.end(token);
    }

    /**
     * The precomputed insets of the display in each rotation. This is used to make the size
     * compatibility mode activity compute the configuration without relying on its current display.
     * This currently only supports fullscreen and freeform windowing mode.
     */
    static class CompatDisplayInsets {
        private final int mWidth;
        private final int mHeight;
        final boolean mIsFloating;
        final boolean mIsRotatable;

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

        /** Constructs the environment to simulate the bounds behavior of the given container. */
        CompatDisplayInsets(DisplayContent display, WindowContainer container) {
            mIsFloating = container.getWindowConfiguration().tasksAreFloating();
            mIsRotatable = !mIsFloating && !display.ignoreRotationForApps();
            if (mIsFloating) {
                final Rect containerBounds = container.getWindowConfiguration().getBounds();
                mWidth = containerBounds.width();
                mHeight = containerBounds.height();
                // For apps in freeform, the task bounds are the parent bounds from the app's
                // perspective. No insets because within a window.
                final Rect emptyRect = new Rect();
                for (int rotation = 0; rotation < 4; rotation++) {
                    mNonDecorInsets[rotation] = emptyRect;
                    mStableInsets[rotation] = emptyRect;
                }
                return;
            }

            // If the activity is not floating, assume it fills the display.
            mWidth = display.mBaseDisplayWidth;
            mHeight = display.mBaseDisplayHeight;
            final DisplayPolicy policy = display.getDisplayPolicy();
            for (int rotation = 0; rotation < 4; rotation++) {
                mNonDecorInsets[rotation] = new Rect();
                mStableInsets[rotation] = new Rect();
                final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
                final int dw = rotated ? mHeight : mWidth;
                final int dh = rotated ? mWidth : mHeight;
                final DisplayCutout cutout = display.calculateDisplayCutoutForRotation(rotation)
                        .getDisplayCutout();
                policy.getNonDecorInsetsLw(rotation, dw, dh, cutout, mNonDecorInsets[rotation]);
                mStableInsets[rotation].set(mNonDecorInsets[rotation]);
                policy.convertNonDecorInsetsToStableInsets(mStableInsets[rotation], rotation);
            }
        }

        void getBoundsByRotation(Rect outBounds, int rotation) {
            final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
            final int dw = rotated ? mHeight : mWidth;
            final int dh = rotated ? mWidth : mHeight;
            outBounds.set(0, 0, dw, dh);
        }

        void getFrameByOrientation(Rect outBounds, int orientation) {
            final int longSide = Math.max(mWidth, mHeight);
            final int shortSide = Math.min(mWidth, mHeight);
            final boolean isLandscape = orientation == ORIENTATION_LANDSCAPE;
            outBounds.set(0, 0, isLandscape ? longSide : shortSide,
                    isLandscape ? shortSide : longSide);
        }

        /** Gets the horizontal centered container bounds for size compatibility mode. */
        void getContainerBounds(Rect outAppBounds, Rect outBounds, int rotation, int orientation,
                boolean orientationRequested, boolean canChangeOrientation) {
            if (mIsFloating) {
                getFrameByOrientation(outBounds, orientation);
                outAppBounds.set(outBounds);
                return;
            }

            if (mIsRotatable && canChangeOrientation) {
                getBoundsByRotation(outBounds, rotation);
                if (orientationRequested) {
                    getFrameByOrientation(outAppBounds, orientation);
                } else {
                    outAppBounds.set(outBounds);
                }
            } else {
                outBounds.set(0, 0, mWidth, mHeight);
                getFrameByOrientation(outAppBounds, orientation);
                if (orientationRequested && !canChangeOrientation
                        && (outAppBounds.width() > outAppBounds.height()) != (mWidth > mHeight)) {
                    // The orientation is mismatched but the display cannot rotate. The bounds will
                    // fit to the short side of display.
                    if (orientation == ORIENTATION_LANDSCAPE) {
                        outAppBounds.bottom = (int) ((float) mWidth * mWidth / mHeight);
                        outAppBounds.right = mWidth;
                    } else {
                        outAppBounds.bottom = mHeight;
                        outAppBounds.right = (int) ((float) mHeight * mHeight / mWidth);
                    }
                    outAppBounds.offset(getHorizontalCenterOffset(outBounds.width(),
                            outAppBounds.width()), 0 /* dy */);
                } else {
                    outAppBounds.set(outBounds);
                }
            }

            if (rotation != ROTATION_UNDEFINED) {
                // Ensure the app bounds won't overlap with insets.
                Task.intersectWithInsetsIfFits(outAppBounds, outBounds, mNonDecorInsets[rotation]);
            }
        }
    }

    private static class AppSaturationInfo {
        float[] mMatrix = new float[9];
        float[] mTranslation = new float[3];

        void setSaturation(@Size(9) float[] matrix, @Size(3) float[] translation) {
            System.arraycopy(matrix, 0, mMatrix, 0, mMatrix.length);
            System.arraycopy(translation, 0, mTranslation, 0, mTranslation.length);
        }
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final WindowState mainWindow = findMainWindow();
        if (task == null || mainWindow == null) {
            return null;
        }
        final Rect insets = new Rect();
        mainWindow.getContentInsets(insets);
        InsetUtils.addInsets(insets, getLetterboxInsets());
        return new RemoteAnimationTarget(task.mTaskId, record.getMode(),
                record.mAdapter.mCapturedLeash, !fillsParent(),
                mainWindow.mWinAnimator.mLastClipRect, insets,
                getPrefixOrderIndex(), record.mAdapter.mPosition, record.mAdapter.mLocalBounds,
                record.mAdapter.mStackBounds, task.getWindowConfiguration(),
                false /*isNotInRecents*/,
                record.mThumbnailAdapter != null ? record.mThumbnailAdapter.mCapturedLeash : null,
                record.mStartBounds);
    }

    @Override
    boolean canCreateRemoteAnimationTarget() {
        return true;
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        win.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
    }

    void setPictureInPictureParams(PictureInPictureParams p) {
        pictureInPictureArgs.copyOnlySet(p);
        getTask().getRootTask().setPictureInPictureParams(p);
    }
}
