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

package com.android.server.am;

import static android.app.ActivityManager.ENABLE_TASK_SNAPSHOTS;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.StackId;
import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.app.ActivityManager.TaskDescription.ATTR_TASKDESCRIPTION_PREFIX;
import static android.app.ActivityOptions.ANIM_CLIP_REVEAL;
import static android.app.ActivityOptions.ANIM_CUSTOM;
import static android.app.ActivityOptions.ANIM_SCALE_UP;
import static android.app.ActivityOptions.ANIM_SCENE_TRANSITION;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_ASPECT_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_ASPECT_SCALE_UP;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_UP;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_PICTURE_IN_PICTURE;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.CATEGORY_HOME;
import static android.content.Intent.CATEGORY_LAUNCHER;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NO_HISTORY;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
import static android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
import static android.content.pm.ActivityInfo.FLAG_IMMERSIVE;
import static android.content.pm.ActivityInfo.FLAG_MULTIPROCESS;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.ActivityInfo.FLAG_STATE_NOT_NEEDED;
import static android.content.pm.ActivityInfo.FLAG_TURN_SCREEN_ON;
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static android.content.pm.ActivityInfo.PERSIST_ACROSS_REBOOTS;
import static android.content.pm.ActivityInfo.PERSIST_ROOT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.FLAG_NO_HISTORY;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.isFixedOrientationPortrait;
import static android.content.res.Configuration.EMPTY;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.content.res.Configuration.UI_MODE_TYPE_VR_HEADSET;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.WindowManagerPolicy.NAV_BAR_LEFT;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SAVED_STATE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SCREENSHOTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_THUMBNAILS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SAVED_STATE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SCREENSHOTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_THUMBNAILS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.TAKE_FULLSCREEN_SCREENSHOTS;
import static com.android.server.am.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.am.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.am.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.am.ActivityStack.ActivityState.PAUSED;
import static com.android.server.am.ActivityStack.ActivityState.PAUSING;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStack.ActivityState.STOPPED;
import static com.android.server.am.ActivityStack.ActivityState.STOPPING;
import static com.android.server.am.ActivityStack.LAUNCH_TICK;
import static com.android.server.am.ActivityStack.LAUNCH_TICK_MSG;
import static com.android.server.am.ActivityStack.PAUSE_TIMEOUT_MSG;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;
import static com.android.server.am.ActivityStack.STOP_TIMEOUT_MSG;
import static com.android.server.am.EventLogTags.AM_ACTIVITY_FULLY_DRAWN_TIME;
import static com.android.server.am.EventLogTags.AM_ACTIVITY_LAUNCH_TIME;
import static com.android.server.am.EventLogTags.AM_RELAUNCH_ACTIVITY;
import static com.android.server.am.EventLogTags.AM_RELAUNCH_RESUME_ACTIVITY;
import static com.android.server.am.TaskPersister.DEBUG;
import static com.android.server.am.TaskPersister.IMAGE_EXTENSION;
import static com.android.server.am.TaskRecord.INVALID_TASK_ID;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.NonNull;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.service.voice.IVoiceInteractionSession;
import android.util.EventLog;
import android.util.Log;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IApplicationToken;
import android.view.WindowManager.LayoutParams;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.util.XmlUtils;
import com.android.server.AttributeCache;
import com.android.server.AttributeCache.Entry;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.wm.AppWindowContainerController;
import com.android.server.wm.AppWindowContainerListener;
import com.android.server.wm.TaskWindowContainerController;

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
final class ActivityRecord extends ConfigurationContainer implements AppWindowContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityRecord" : TAG_AM;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_SAVED_STATE = TAG + POSTFIX_SAVED_STATE;
    private static final String TAG_SCREENSHOTS = TAG + POSTFIX_SCREENSHOTS;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_THUMBNAILS = TAG + POSTFIX_THUMBNAILS;
    private static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    private static final boolean SHOW_ACTIVITY_START_TIME = true;
    private static final String RECENTS_PACKAGE_NAME = "com.android.systemui.recents";

    private static final String ATTR_ID = "id";
    private static final String TAG_INTENT = "intent";
    private static final String ATTR_USERID = "user_id";
    private static final String TAG_PERSISTABLEBUNDLE = "persistable_bundle";
    private static final String ATTR_LAUNCHEDFROMUID = "launched_from_uid";
    private static final String ATTR_LAUNCHEDFROMPACKAGE = "launched_from_package";
    private static final String ATTR_RESOLVEDTYPE = "resolved_type";
    private static final String ATTR_COMPONENTSPECIFIED = "component_specified";
    static final String ACTIVITY_ICON_SUFFIX = "_activity_icon_";

    final ActivityManagerService service; // owner
    final IApplicationToken.Stub appToken; // window manager token
    AppWindowContainerController mWindowContainerController;
    final ActivityInfo info; // all about me
    final ApplicationInfo appInfo; // information about activity's app
    final int launchedFromPid; // always the pid who started the activity.
    final int launchedFromUid; // always the uid who started the activity.
    final String launchedFromPackage; // always the package who started the activity.
    final int userId;          // Which user is this running for?
    final Intent intent;    // the original intent that generated us
    final ComponentName realActivity;  // the intent component, or target of an alias.
    final String shortComponentName; // the short component name of the intent
    final String resolvedType; // as per original caller;
    final String packageName; // the package implementing intent's component
    final String processName; // process where this component wants to run
    final String taskAffinity; // as per ActivityInfo.taskAffinity
    final boolean stateNotNeeded; // As per ActivityInfo.flags
    boolean fullscreen; // covers the full screen?
    final boolean noDisplay;  // activity is not displayed?
    private final boolean componentSpecified;  // did caller specify an explicit component?
    final boolean rootVoiceInteraction;  // was this the root activity of a voice interaction?

    static final int APPLICATION_ACTIVITY_TYPE = 0;
    static final int HOME_ACTIVITY_TYPE = 1;
    static final int RECENTS_ACTIVITY_TYPE = 2;
    static final int ASSISTANT_ACTIVITY_TYPE = 3;
    int mActivityType;

    private CharSequence nonLocalizedLabel;  // the label information from the package mgr.
    private int labelRes;           // the label information from the package mgr.
    private int icon;               // resource identifier of activity's icon.
    private int logo;               // resource identifier of activity's logo.
    private int theme;              // resource identifier of activity's theme.
    private int realTheme;          // actual theme resource we will use, never 0.
    private int windowFlags;        // custom window flags for preview window.
    private TaskRecord task;        // the task this is in.
    private long createTime = System.currentTimeMillis();
    long displayStartTime;  // when we started launching this activity
    long fullyDrawnStartTime; // when we started launching this activity
    private long startTime;         // last time this activity was started
    long lastVisibleTime;   // last time this activity became visible
    long cpuTimeAtResume;   // the cpu time of host process at the time of resuming activity
    long pauseTime;         // last time we started pausing the activity
    long launchTickTime;    // base time for launch tick messages
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
    HashSet<ConnectionRecord> connections; // All ConnectionRecord we hold
    UriPermissionOwner uriPermissions; // current special URI access perms.
    ProcessRecord app;      // if non-null, hosting application
    ActivityState state;    // current state we are in
    Bundle  icicle;         // last saved activity state
    PersistableBundle persistentState; // last persistently saved activity state
    boolean frontOfTask;    // is this the root activity of its task?
    boolean launchFailed;   // set if a launched failed, to abort on 2nd try
    boolean haveState;      // have we gotten the last activity state?
    boolean stopped;        // is activity pause finished?
    boolean delayedResume;  // not yet resumed because of stopped app switches?
    boolean finishing;      // activity in pending finish list?
    boolean deferRelaunchUntilPaused;   // relaunch of activity is being deferred until pause is
                                        // completed
    boolean preserveWindowOnDeferredRelaunch; // activity windows are preserved on deferred relaunch
    int configChangeFlags;  // which config values have changed
    private boolean keysPaused;     // has key dispatching been paused for it?
    int launchMode;         // the launch mode activity attribute.
    boolean visible;        // does this activity's window need to be shown?
    boolean visibleIgnoringKeyguard; // is this activity visible, ignoring the fact that Keyguard
                                     // might hide this activity?
    private boolean mDeferHidingClient; // If true we told WM to defer reporting to the client
                                        // process that it is hidden.
    boolean sleeping;       // have we told the activity to sleep?
    boolean nowVisible;     // is this activity's window visible?
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

    static final int STARTING_WINDOW_NOT_SHOWN = 0;
    static final int STARTING_WINDOW_SHOWN = 1;
    static final int STARTING_WINDOW_REMOVED = 2;
    int mStartingWindowState = STARTING_WINDOW_NOT_SHOWN;
    boolean mTaskOverlay = false; // Task is always on-top of other activities in the task.

    boolean mUpdateTaskThumbnailWhenHidden;

    TaskDescription taskDescription; // the recents information for this activity
    boolean mLaunchTaskBehind; // this activity is actively being launched with
        // ActivityOptions.setLaunchTaskBehind, will be cleared once launch is completed.

    // These configurations are collected from application's resources based on size-sensitive
    // qualifiers. For example, layout-w800dp will be added to mHorizontalSizeConfigurations as 800
    // and drawable-sw400dp will be added to both as 400.
    private int[] mVerticalSizeConfigurations;
    private int[] mHorizontalSizeConfigurations;
    private int[] mSmallestSizeConfigurations;

    boolean pendingVoiceInteractionStart;   // Waiting for activity-invoked voice session
    IVoiceInteractionSession voiceSession;  // Voice interaction session for this activity

    // A hint to override the window specified rotation animation, or -1
    // to use the window specified value. We use this so that
    // we can select the right animation in the cases of starting
    // windows, where the app hasn't had time to set a value
    // on the window.
    int mRotationAnimationHint = -1;

    // The bounds of this activity. Mainly used for aspect-ratio compatibility.
    // TODO(b/36505427): Every level on ConfigurationContainer now has bounds information, which
    // directly affects the configuration. We should probably move this into that class and have it
    // handle calculating override configuration from the bounds.
    private final Rect mBounds = new Rect();

    private boolean mShowWhenLocked;
    private boolean mTurnScreenOn;

    /**
     * Temp configs used in {@link #ensureActivityConfigurationLocked(int, boolean)}
     */
    private final Configuration mTmpConfig = new Configuration();
    private final Rect mTmpBounds = new Rect();

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
                pw.print(" userId="); pw.println(userId);
        pw.print(prefix); pw.print("app="); pw.println(app);
        pw.print(prefix); pw.println(intent.toInsecureStringWithClip());
        pw.print(prefix); pw.print("frontOfTask="); pw.print(frontOfTask);
                pw.print(" task="); pw.println(task);
        pw.print(prefix); pw.print("taskAffinity="); pw.println(taskAffinity);
        pw.print(prefix); pw.print("realActivity=");
                pw.println(realActivity.flattenToShortString());
        if (appInfo != null) {
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
                pw.print(" mActivityType="); pw.println(mActivityType);
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
        if (!getOverrideConfiguration().equals(EMPTY)) {
            pw.println(prefix + "OverrideConfiguration=" + getOverrideConfiguration());
        }
        if (!mBounds.isEmpty()) {
            pw.println(prefix + "mBounds=" + mBounds);
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
                        pw.print(" iconFilename="); pw.print(taskDescription.getIconFilename());
                        pw.print(" label=\""); pw.print(taskDescription.getLabel());
                                pw.print("\"");
                        pw.print(" primaryColor=");
                        pw.println(Integer.toHexString(taskDescription.getPrimaryColor()));
                        pw.print(prefix); pw.print("  backgroundColor=");
                        pw.println(Integer.toHexString(taskDescription.getBackgroundColor()));
                        pw.print(prefix); pw.print("  statusBarColor=");
                        pw.println(Integer.toHexString(taskDescription.getStatusBarColor()));
                        pw.print(prefix); pw.print("  navigationBarColor=");
                        pw.println(Integer.toHexString(taskDescription.getNavigationBarColor()));
            }
            if (iconFilename == null && taskDescription.getIcon() != null) {
                pw.print(prefix); pw.println("taskDescription contains Bitmap");
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
        pw.print(prefix); pw.print("haveState="); pw.print(haveState);
                pw.print(" icicle="); pw.println(icicle);
        pw.print(prefix); pw.print("state="); pw.print(state);
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
                pw.println(activityTypeToString(mActivityType));
        if (requestedVrComponent != null) {
            pw.print(prefix);
            pw.print("requestedVrComponent=");
            pw.println(requestedVrComponent);
        }
        if (displayStartTime != 0 || startTime != 0) {
            pw.print(prefix); pw.print("displayStartTime=");
                    if (displayStartTime == 0) pw.print("0");
                    else TimeUtils.formatDuration(displayStartTime, now, pw);
                    pw.print(" startTime=");
                    if (startTime == 0) pw.print("0");
                    else TimeUtils.formatDuration(startTime, now, pw);
                    pw.println();
        }
        final boolean waitingVisible =
                mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(this);
        if (lastVisibleTime != 0 || waitingVisible || nowVisible) {
            pw.print(prefix); pw.print("waitingVisible="); pw.print(waitingVisible);
                    pw.print(" nowVisible="); pw.print(nowVisible);
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
        if (connections != null) {
            pw.print(prefix); pw.print("connections="); pw.println(connections);
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
        }
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
        if (app == null || app.thread == null) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.w(TAG,
                    "Can't report activity moved to display - client not running, activityRecord="
                            + this + ", displayId=" + displayId);
            return;
        }
        try {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Reporting activity moved to display" + ", activityRecord=" + this
                            + ", displayId=" + displayId + ", config=" + config);

            app.thread.scheduleActivityMovedToDisplay(appToken, displayId,
                    new Configuration(config));
        } catch (RemoteException e) {
            // If process died, whatever.
        }
    }

    private void scheduleConfigurationChanged(Configuration config) {
        if (app == null || app.thread == null) {
            if (DEBUG_CONFIGURATION) Slog.w(TAG,
                    "Can't report activity configuration update - client not running"
                            + ", activityRecord=" + this);
            return;
        }
        try {
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending new config to " + this + ", config: "
                    + config);

            app.thread.scheduleActivityConfigurationChanged(appToken, new Configuration(config));
        } catch (RemoteException e) {
            // If process died, whatever.
        }
    }

    void updateMultiWindowMode() {
        if (task == null || task.getStack() == null || app == null || app.thread == null) {
            return;
        }

        // An activity is considered to be in multi-window mode if its task isn't fullscreen.
        final boolean inMultiWindowMode = !task.mFullscreen;
        if (inMultiWindowMode != mLastReportedMultiWindowMode) {
            mLastReportedMultiWindowMode = inMultiWindowMode;
            scheduleMultiWindowModeChanged(getConfiguration());
        }
    }

    private void scheduleMultiWindowModeChanged(Configuration overrideConfig) {
        try {
            app.thread.scheduleMultiWindowModeChanged(appToken, mLastReportedMultiWindowMode,
                    overrideConfig);
        } catch (Exception e) {
            // If process died, I don't care.
        }
    }

    void updatePictureInPictureMode(Rect targetStackBounds, boolean forceUpdate) {
        if (task == null || task.getStack() == null || app == null || app.thread == null) {
            return;
        }

        final boolean inPictureInPictureMode = (task.getStackId() == PINNED_STACK_ID) &&
                (targetStackBounds != null);
        if (inPictureInPictureMode != mLastReportedPictureInPictureMode || forceUpdate) {
            // Picture-in-picture mode changes also trigger a multi-window mode change as well, so
            // update that here in order
            mLastReportedPictureInPictureMode = inPictureInPictureMode;
            mLastReportedMultiWindowMode = inPictureInPictureMode;
            final Configuration newConfig = task.computeNewOverrideConfigurationForBounds(
                    targetStackBounds, null);
            schedulePictureInPictureModeChanged(newConfig);
            scheduleMultiWindowModeChanged(newConfig);
        }
    }

    private void schedulePictureInPictureModeChanged(Configuration overrideConfig) {
        try {
            app.thread.schedulePictureInPictureModeChanged(appToken,
                    mLastReportedPictureInPictureMode, overrideConfig);
        } catch (Exception e) {
            // If process died, no one cares.
        }
    }

    boolean isFreeform() {
        return task != null && task.getStackId() == FREEFORM_WORKSPACE_STACK_ID;
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
        return getTask();
    }

    TaskRecord getTask() {
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
        setTask(task, false /*reparenting*/);
    }

    /**
     * This method should only be called by {@link TaskRecord#removeActivity(ActivityRecord)}.
     */
    void setTask(TaskRecord task, boolean reparenting) {
        // Do nothing if the {@link TaskRecord} is the same as the current {@link getTask}.
        if (task != null && task == getTask()) {
            return;
        }

        final ActivityStack stack = getStack();

        // If the new {@link TaskRecord} is from a different {@link ActivityStack}, remove this
        // {@link ActivityRecord} from its current {@link ActivityStack}.
        if (!reparenting && stack != null && (task == null || stack != task.getStack())) {
            stack.onActivityRemovedFromStack(this);
        }

        this.task = task;

        if (!reparenting) {
            onParentChanged();
        }
    }

    static class Token extends IApplicationToken.Stub {
        private final WeakReference<ActivityRecord> weakActivity;

        Token(ActivityRecord activity) {
            weakActivity = new WeakReference<>(activity);
        }

        private static ActivityRecord tokenToActivityRecordLocked(Token token) {
            if (token == null) {
                return null;
            }
            ActivityRecord r = token.weakActivity.get();
            if (r == null || r.getStack() == null) {
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
    }

    static ActivityRecord forTokenLocked(IBinder token) {
        try {
            return Token.tokenToActivityRecordLocked((Token)token);
        } catch (ClassCastException e) {
            Slog.w(TAG, "Bad activity token: " + token, e);
            return null;
        }
    }

    boolean isResolverActivity() {
        return ResolverActivity.class.getName().equals(realActivity.getClassName());
    }

    ActivityRecord(ActivityManagerService _service, ProcessRecord _caller, int _launchedFromPid,
            int _launchedFromUid, String _launchedFromPackage, Intent _intent, String _resolvedType,
            ActivityInfo aInfo, Configuration _configuration,
            ActivityRecord _resultTo, String _resultWho, int _reqCode,
            boolean _componentSpecified, boolean _rootVoiceInteraction,
            ActivityStackSupervisor supervisor, ActivityOptions options,
            ActivityRecord sourceRecord) {
        service = _service;
        appToken = new Token(this);
        info = aInfo;
        launchedFromPid = _launchedFromPid;
        launchedFromUid = _launchedFromUid;
        launchedFromPackage = _launchedFromPackage;
        userId = UserHandle.getUserId(aInfo.applicationInfo.uid);
        intent = _intent;
        shortComponentName = _intent.getComponent().flattenToShortString();
        resolvedType = _resolvedType;
        componentSpecified = _componentSpecified;
        rootVoiceInteraction = _rootVoiceInteraction;
        mLastReportedConfiguration = new MergedConfiguration(_configuration);
        resultTo = _resultTo;
        resultWho = _resultWho;
        requestCode = _reqCode;
        state = INITIALIZING;
        frontOfTask = false;
        launchFailed = false;
        stopped = false;
        delayedResume = false;
        finishing = false;
        deferRelaunchUntilPaused = false;
        keysPaused = false;
        inHistory = false;
        visible = false;
        nowVisible = false;
        idle = false;
        hasBeenLaunched = false;
        mStackSupervisor = supervisor;

        mRotationAnimationHint = aInfo.rotationAnimation;

        if (options != null) {
            pendingOptions = options;
            mLaunchTaskBehind = pendingOptions.getLaunchTaskBehind();

            final int rotationAnimation = pendingOptions.getRotationAnimationHint();
            // Only override manifest supplied option if set.
            if (rotationAnimation >= 0) {
                mRotationAnimationHint = rotationAnimation;
            }
            PendingIntent usageReport = pendingOptions.getUsageTimeReport();
            if (usageReport != null) {
                appTimeTracker = new AppTimeTracker(usageReport);
            }
        }

        // This starts out true, since the initial state of an activity is that we have everything,
        // and we shouldn't never consider it lacking in state to be removed if it dies.
        haveState = true;

        // If the class name in the intent doesn't match that of the target, this is
        // probably an alias. We have to create a new ComponentName object to keep track
        // of the real activity name, so that FLAG_ACTIVITY_CLEAR_TOP is handled properly.
        if (aInfo.targetActivity == null
                || (aInfo.targetActivity.equals(_intent.getComponent().getClassName())
                && (aInfo.launchMode == LAUNCH_MULTIPLE
                || aInfo.launchMode == LAUNCH_SINGLE_TOP))) {
            realActivity = _intent.getComponent();
        } else {
            realActivity = new ComponentName(aInfo.packageName, aInfo.targetActivity);
        }
        taskAffinity = aInfo.taskAffinity;
        stateNotNeeded = (aInfo.flags & FLAG_STATE_NOT_NEEDED) != 0;
        appInfo = aInfo.applicationInfo;
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
                    || aInfo.applicationInfo.uid == _caller.info.uid)) {
            processName = _caller.processName;
        } else {
            processName = aInfo.processName;
        }

        if ((aInfo.flags & FLAG_EXCLUDE_FROM_RECENTS) != 0) {
            intent.addFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }

        packageName = aInfo.applicationInfo.packageName;
        launchMode = aInfo.launchMode;

        Entry ent = AttributeCache.instance().get(packageName,
                realTheme, com.android.internal.R.styleable.Window, userId);
        fullscreen = ent != null && !ActivityInfo.isTranslucentOrFloating(ent.array);
        noDisplay = ent != null && ent.array.getBoolean(
                com.android.internal.R.styleable.Window_windowNoDisplay, false);

        setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);

        immersive = (aInfo.flags & FLAG_IMMERSIVE) != 0;

        requestedVrComponent = (aInfo.requestedVrComponent == null) ?
                null : ComponentName.unflattenFromString(aInfo.requestedVrComponent);

        mShowWhenLocked = (aInfo.flags & FLAG_SHOW_WHEN_LOCKED) != 0;
        mTurnScreenOn = (aInfo.flags & FLAG_TURN_SCREEN_ON) != 0;
    }

    AppWindowContainerController getWindowContainerController() {
        return mWindowContainerController;
    }

    void createWindowContainer() {
        if (mWindowContainerController != null) {
            throw new IllegalArgumentException("Window container=" + mWindowContainerController
                    + " already created for r=" + this);
        }

        inHistory = true;

        final TaskWindowContainerController taskController = task.getWindowContainerController();

        // TODO(b/36505427): Maybe this call should be moved inside updateOverrideConfiguration()
        task.updateOverrideConfigurationFromLaunchBounds();
        // Make sure override configuration is up-to-date before using to create window controller.
        updateOverrideConfiguration();

        mWindowContainerController = new AppWindowContainerController(taskController, appToken,
                this, Integer.MAX_VALUE /* add on top */, info.screenOrientation, fullscreen,
                (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0, info.configChanges,
                task.voiceSession != null, mLaunchTaskBehind, isAlwaysFocusable(),
                appInfo.targetSdkVersion, mRotationAnimationHint,
                ActivityManagerService.getInputDispatchingTimeoutLocked(this) * 1000000L,
                getOverrideConfiguration(), mBounds);

        task.addActivityToTop(this);

        // When an activity is started directly into a split-screen fullscreen stack, we need to
        // update the initial multi-window modes so that the callbacks are scheduled correctly when
        // the user leaves that mode.
        mLastReportedMultiWindowMode = !task.mFullscreen;
        mLastReportedPictureInPictureMode = (task.getStackId() == PINNED_STACK_ID);
    }

    void removeWindowContainer() {
        // Resume key dispatching if it is currently paused before we remove the container.
        resumeKeyDispatchingLocked();

        mWindowContainerController.removeContainer(getDisplayId());
        mWindowContainerController = null;
    }

    /**
     * Reparents this activity into {@param newTask} at the provided {@param position}.  The caller
     * should ensure that the {@param newTask} is not already the parent of this activity.
     */
    void reparent(TaskRecord newTask, int position, String reason) {
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

        // Must reparent first in window manager
        mWindowContainerController.reparent(newTask.getWindowContainerController(), position);

        // Remove the activity from the old task and add it to the new task.
        prevTask.removeActivity(this, true /*reparenting*/);

        newTask.addActivityAtIndex(position, this);
    }

    private boolean isHomeIntent(Intent intent) {
        return ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(CATEGORY_HOME)
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

    private boolean canLaunchHomeActivity(int uid, ActivityRecord sourceRecord) {
        if (uid == Process.myUid() || uid == 0) {
            // System process can launch home activity.
            return true;
        }
        // Resolver activity can launch home activity.
        return sourceRecord != null && sourceRecord.isResolverActivity();
    }

    /**
     * @return whether the given package name can launch an assist activity.
     */
    private boolean canLaunchAssistActivity(String packageName) {
        if (service.mAssistUtils == null) {
            return false;
        }

        final ComponentName assistComponent = service.mAssistUtils.getActiveServiceComponentName();
        if (assistComponent != null) {
            return assistComponent.getPackageName().equals(packageName);
        }
        return false;
    }

    private void setActivityType(boolean componentSpecified, int launchedFromUid, Intent intent,
            ActivityOptions options, ActivityRecord sourceRecord) {
        if ((!componentSpecified || canLaunchHomeActivity(launchedFromUid, sourceRecord))
                && isHomeIntent(intent) && !isResolverActivity()) {
            // This sure looks like a home activity!
            mActivityType = HOME_ACTIVITY_TYPE;

            if (info.resizeMode == RESIZE_MODE_FORCE_RESIZEABLE
                    || info.resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // We only allow home activities to be resizeable if they explicitly requested it.
                info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
            }
        } else if (realActivity.getClassName().contains(RECENTS_PACKAGE_NAME)) {
            mActivityType = RECENTS_ACTIVITY_TYPE;
        } else if (options != null && options.getLaunchStackId() == ASSISTANT_STACK_ID
                && canLaunchAssistActivity(launchedFromPackage)) {
            mActivityType = ASSISTANT_ACTIVITY_TYPE;
        } else {
            mActivityType = APPLICATION_ACTIVITY_TYPE;
        }
    }

    void setTaskToAffiliateWith(TaskRecord taskToAffiliateWith) {
        if (launchMode != LAUNCH_SINGLE_INSTANCE && launchMode != LAUNCH_SINGLE_TASK) {
            task.setTaskToAffiliateWith(taskToAffiliateWith);
        }
    }

    /**
     * @return Stack value from current task, null if there is no task.
     */
    <T extends ActivityStack> T getStack() {
        return task != null ? (T) task.getStack() : null;
    }

    int getStackId() {
        return getStack() != null ? getStack().mStackId : INVALID_STACK_ID;
    }

    boolean changeWindowTranslucency(boolean toOpaque) {
        if (fullscreen == toOpaque) {
            return false;
        }

        // Keep track of the number of fullscreen activities in this task.
        task.numFullscreen += toOpaque ? +1 : -1;

        fullscreen = toOpaque;
        return true;
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
        final ActivityStack stack = getStack();
        return stack != null && stack.isInStackLocked(this) != null;
    }

    boolean isHomeActivity() {
        return mActivityType == HOME_ACTIVITY_TYPE;
    }

    boolean isRecentsActivity() {
        return mActivityType == RECENTS_ACTIVITY_TYPE;
    }

    boolean isAssistantActivity() {
        return mActivityType == ASSISTANT_ACTIVITY_TYPE;
    }

    boolean isApplicationActivity() {
        return mActivityType == APPLICATION_ACTIVITY_TYPE;
    }

    boolean isPersistable() {
        return (info.persistableMode == PERSIST_ROOT_ONLY ||
                info.persistableMode == PERSIST_ACROSS_REBOOTS) &&
                (intent == null ||
                        (intent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0);
    }

    boolean isFocusable() {
        return StackId.canReceiveKeys(task.getStackId()) || isAlwaysFocusable();
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
        return service.mSupportsPictureInPicture && !isHomeActivity()
                && info.supportsPictureInPicture();
    }

    /**
     * @return whether this activity supports split-screen multi-window and can be put in the docked
     *         stack.
     */
    boolean supportsSplitScreen() {
        // An activity can not be docked even if it is considered resizeable because it only
        // supports picture-in-picture mode but has a non-resizeable resizeMode
        return service.mSupportsSplitScreenMultiWindow && supportsResizeableMultiWindow();
    }

    /**
     * @return whether this activity supports freeform multi-window and can be put in the freeform
     *         stack.
     */
    boolean supportsFreeform() {
        return service.mSupportsFreeformWindowManagement && supportsResizeableMultiWindow();
    }

    /**
     * @return whether this activity supports non-PiP multi-window.
     */
    private boolean supportsResizeableMultiWindow() {
        return service.mSupportsMultiWindow && !isHomeActivity()
                && (ActivityInfo.isResizeableMode(info.resizeMode)
                        || service.mForceResizableActivities);
    }

    /**
     * Check whether this activity can be launched on the specified display.
     * @param displayId Target display id.
     * @return {@code true} if either it is the default display or this activity is resizeable and
     *         can be put a secondary screen.
     */
    boolean canBeLaunchedOnDisplay(int displayId) {
        final TaskRecord task = getTask();

        // The resizeability of an Activity's parent task takes precendence over the ActivityInfo.
        // This allows for a non resizable activity to be launched into a resizeable task.
        final boolean resizeable =
                task != null ? task.isResizeable() : supportsResizeableMultiWindow();

        return service.mStackSupervisor.canPlaceEntityOnDisplay(displayId,
                resizeable, launchedFromPid, launchedFromUid, info);
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
        if (service.shouldDisableNonVrUiLocked()) {
            return false;
        }

        boolean isKeyguardLocked = service.isKeyguardLocked();
        boolean isCurrentAppLocked = mStackSupervisor.getLockTaskModeState() != LOCK_TASK_MODE_NONE;
        boolean hasPinnedStack = mStackSupervisor.getStack(PINNED_STACK_ID) != null;
        // Don't return early if !isNotLocked, since we want to throw an exception if the activity
        // is in an incorrect state
        boolean isNotLockedOrOnKeyguard = !isKeyguardLocked && !isCurrentAppLocked;

        // We don't allow auto-PiP when something else is already pipped.
        if (beforeStopping && hasPinnedStack) {
            return false;
        }

        switch (state) {
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
        try {
            return service.getAppOpsService().checkOperation(OP_PICTURE_IN_PICTURE,
                    appInfo.uid, packageName) == MODE_ALLOWED;
        } catch (RemoteException e) {
            // Local call
        }
        return false;
    }

    boolean isAlwaysFocusable() {
        return (info.flags & FLAG_ALWAYS_FOCUSABLE) != 0;
    }


    /**
     * @return true if the activity contains windows that have
     *         {@link LayoutParams#FLAG_DISMISS_KEYGUARD} set
     */
    boolean hasDismissKeyguardWindows() {
        return service.mWindowManager.containsDismissKeyguardWindow(appToken);
    }

    void makeFinishingLocked() {
        if (finishing) {
            return;
        }
        finishing = true;
        if (stopped) {
            clearOptionsLocked();
        }

        if (service != null) {
            service.mTaskChangeNotificationController.notifyTaskStackChanged();
        }
    }

    UriPermissionOwner getUriPermissionsLocked() {
        if (uriPermissions == null) {
            uriPermissions = new UriPermissionOwner(service, this);
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

    private void addNewIntentLocked(ReferrerIntent intent) {
        if (newIntents == null) {
            newIntents = new ArrayList<>();
        }
        newIntents.add(intent);
    }

    /**
     * Deliver a new Intent to an existing activity, so that its onNewIntent()
     * method will be called at the proper time.
     */
    final void deliverNewIntentLocked(int callingUid, Intent intent, String referrer) {
        // The activity now gets access to the data associated with this Intent.
        service.grantUriPermissionFromIntentLocked(callingUid, packageName,
                intent, getUriPermissionsLocked(), userId);
        final ReferrerIntent rintent = new ReferrerIntent(intent, referrer);
        boolean unsent = true;
        final ActivityStack stack = getStack();
        final boolean isTopActivityWhileSleeping = isTopRunningActivity()
                && (stack != null ? stack.shouldSleepActivities() : service.isSleepingLocked());

        // We want to immediately deliver the intent to the activity if:
        // - It is currently resumed or paused. i.e. it is currently visible to the user and we want
        //   the user to see the visual effects caused by the intent delivery now.
        // - The device is sleeping and it is the top activity behind the lock screen (b/6700897).
        if ((state == RESUMED || state == PAUSED
                || isTopActivityWhileSleeping) && app != null && app.thread != null) {
            try {
                ArrayList<ReferrerIntent> ar = new ArrayList<>(1);
                ar.add(rintent);
                app.thread.scheduleNewIntent(
                        ar, appToken, state == PAUSED /* andPause */);
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
            if (pendingOptions != null) {
                pendingOptions.abort();
            }
            pendingOptions = options;
        }
    }

    void applyOptionsLocked() {
        if (pendingOptions != null
                && pendingOptions.getAnimationType() != ANIM_SCENE_TRANSITION) {
            final int animationType = pendingOptions.getAnimationType();
            switch (animationType) {
                case ANIM_CUSTOM:
                    service.mWindowManager.overridePendingAppTransition(
                            pendingOptions.getPackageName(),
                            pendingOptions.getCustomEnterResId(),
                            pendingOptions.getCustomExitResId(),
                            pendingOptions.getOnAnimationStartListener());
                    break;
                case ANIM_CLIP_REVEAL:
                    service.mWindowManager.overridePendingAppTransitionClipReveal(
                            pendingOptions.getStartX(), pendingOptions.getStartY(),
                            pendingOptions.getWidth(), pendingOptions.getHeight());
                    if (intent.getSourceBounds() == null) {
                        intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                                pendingOptions.getStartY(),
                                pendingOptions.getStartX()+pendingOptions.getWidth(),
                                pendingOptions.getStartY()+pendingOptions.getHeight()));
                    }
                    break;
                case ANIM_SCALE_UP:
                    service.mWindowManager.overridePendingAppTransitionScaleUp(
                            pendingOptions.getStartX(), pendingOptions.getStartY(),
                            pendingOptions.getWidth(), pendingOptions.getHeight());
                    if (intent.getSourceBounds() == null) {
                        intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                                pendingOptions.getStartY(),
                                pendingOptions.getStartX()+pendingOptions.getWidth(),
                                pendingOptions.getStartY()+pendingOptions.getHeight()));
                    }
                    break;
                case ANIM_THUMBNAIL_SCALE_UP:
                case ANIM_THUMBNAIL_SCALE_DOWN:
                    final boolean scaleUp = (animationType == ANIM_THUMBNAIL_SCALE_UP);
                    final GraphicBuffer buffer = pendingOptions.getThumbnail();
                    service.mWindowManager.overridePendingAppTransitionThumb(buffer,
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
                        service.mWindowManager.overridePendingAppTransitionMultiThumbFuture(
                                specsFuture, pendingOptions.getOnAnimationStartListener(),
                                animationType == ANIM_THUMBNAIL_ASPECT_SCALE_UP);
                    } else if (animationType == ANIM_THUMBNAIL_ASPECT_SCALE_DOWN
                            && specs != null) {
                        service.mWindowManager.overridePendingAppTransitionMultiThumb(
                                specs, pendingOptions.getOnAnimationStartListener(),
                                pendingOptions.getAnimationFinishedListener(), false);
                    } else {
                        service.mWindowManager.overridePendingAppTransitionAspectScaledThumb(
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
                default:
                    Slog.e(TAG, "applyOptionsLocked: Unknown animationType=" + animationType);
                    break;
            }
            pendingOptions = null;
        }
    }

    ActivityOptions getOptionsForTargetActivityLocked() {
        return pendingOptions != null ? pendingOptions.forTargetActivity() : null;
    }

    void clearOptionsLocked() {
        if (pendingOptions != null) {
            pendingOptions.abort();
            pendingOptions = null;
        }
    }

    ActivityOptions takeOptionsLocked() {
        ActivityOptions opts = pendingOptions;
        pendingOptions = null;
        return opts;
    }

    void removeUriPermissionsLocked() {
        if (uriPermissions != null) {
            uriPermissions.removeUriPermissionsLocked();
            uriPermissions = null;
        }
    }

    void pauseKeyDispatchingLocked() {
        if (!keysPaused) {
            keysPaused = true;
            mWindowContainerController.pauseKeyDispatching();
        }
    }

    void resumeKeyDispatchingLocked() {
        if (keysPaused) {
            keysPaused = false;
            mWindowContainerController.resumeKeyDispatching();
        }
    }

    void updateThumbnailLocked(Bitmap newThumbnail, CharSequence description) {
        if (newThumbnail != null) {
            if (DEBUG_THUMBNAILS) Slog.i(TAG_THUMBNAILS,
                    "Setting thumbnail of " + this + " to " + newThumbnail);
            boolean thumbnailUpdated = task.setLastThumbnailLocked(newThumbnail);
            if (thumbnailUpdated && isPersistable()) {
                service.notifyTaskPersisterLocked(task, false);
            }
        }
        task.lastDescription = description;
    }

    final Bitmap screenshotActivityLocked() {
        if (DEBUG_SCREENSHOTS) Slog.d(TAG_SCREENSHOTS, "screenshotActivityLocked: " + this);

        if (ENABLE_TASK_SNAPSHOTS) {
            // No need to screenshot if snapshots are enabled.
            if (DEBUG_SCREENSHOTS) Slog.d(TAG_SCREENSHOTS,
                    "\tSnapshots are enabled, abort taking screenshot");
            return null;
        }

        if (noDisplay) {
            if (DEBUG_SCREENSHOTS) Slog.d(TAG_SCREENSHOTS, "\tNo display");
            return null;
        }

        final ActivityStack stack = getStack();
        if (stack.isHomeOrRecentsStack()) {
            // This is an optimization -- since we never show Home or Recents within Recents itself,
            // we can just go ahead and skip taking the screenshot if this is the home stack.
            if (DEBUG_SCREENSHOTS) Slog.d(TAG_SCREENSHOTS, stack.getStackId() == HOME_STACK_ID ?
                        "\tHome stack" : "\tRecents stack");
            return null;
        }

        int w = service.mThumbnailWidth;
        int h = service.mThumbnailHeight;

        if (w <= 0) {
            Slog.e(TAG, "\tInvalid thumbnail dimensions: " + w + "x" + h);
            return null;
        }

        if (stack.mStackId == DOCKED_STACK_ID && mStackSupervisor.mIsDockMinimized) {
            // When the docked stack is minimized its app windows are cropped significantly so any
            // screenshot taken will not display the apps contain. So, we avoid taking a screenshot
            // in that case.
            if (DEBUG_SCREENSHOTS) Slog.e(TAG, "\tIn minimized docked stack");
            return null;
        }

        float scale = 0;
        if (DEBUG_SCREENSHOTS) Slog.d(TAG_SCREENSHOTS, "\tTaking screenshot");

        // When this flag is set, we currently take the fullscreen screenshot of the activity but
        // scaled to half the size. This gives us a "good-enough" fullscreen thumbnail to use within
        // SystemUI while keeping memory usage low.
        if (TAKE_FULLSCREEN_SCREENSHOTS) {
            w = h = -1;
            scale = service.mFullscreenThumbnailScale;
        }

        return mWindowContainerController.screenshotApplications(getDisplayId(), w, h, scale);
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
        mWindowContainerController.setVisibility(visible, mDeferHidingClient);
        mStackSupervisor.mActivityMetricsLogger.notifyVisibilityChanged(this, visible);
    }

    // TODO: Look into merging with #setVisibility()
    void setVisible(boolean newVisible) {
        visible = newVisible;
        mDeferHidingClient = !visible && mDeferHidingClient;
        if (!visible && mUpdateTaskThumbnailWhenHidden) {
            updateThumbnailLocked(screenshotActivityLocked(), null /* description */);
            mUpdateTaskThumbnailWhenHidden = false;
        }
        setVisibility(visible);
        mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
    }

    void notifyAppResumed(boolean wasStopped) {
        mWindowContainerController.notifyAppResumed(wasStopped);
    }

    void notifyUnknownVisibilityLaunched() {

        // No display activities never add a window, so there is no point in waiting them for
        // relayout.
        if (!noDisplay) {
            mWindowContainerController.notifyUnknownVisibilityLaunched();
        }
    }

    /**
     * @return true if the input activity should be made visible, ignoring any effect Keyguard
     * might have on the visibility
     *
     * @see {@link ActivityStack#checkKeyguardVisibility}
     */
    boolean shouldBeVisibleIgnoringKeyguard(boolean behindFullscreenActivity) {
        if (!okToShowLocked()) {
            return false;
        }

        boolean isVisible = !behindFullscreenActivity || mLaunchTaskBehind;

        if (service.mSupportsLeanbackOnly && isVisible && isRecentsActivity()) {
            // On devices that support leanback only (Android TV), Recents activity can only be
            // visible if the home stack is the focused stack or we are in split-screen mode.
            isVisible = mStackSupervisor.getStack(DOCKED_STACK_ID) != null
                    || mStackSupervisor.isFocusedStack(getStack());
        }

        return isVisible;
    }

    void makeVisibleIfNeeded(ActivityRecord starting) {
        // This activity is not currently visible, but is running. Tell it to become visible.
        if (state == RESUMED || this == starting) {
            if (DEBUG_VISIBILITY) Slog.d(TAG_VISIBILITY,
                    "Not making visible, r=" + this + " state=" + state + " starting=" + starting);
            return;
        }

        // If this activity is paused, tell it to now show its window.
        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                "Making visible and scheduling visibility: " + this);
        final ActivityStack stack = getStack();
        try {
            if (stack.mTranslucentActivityWaiting != null) {
                updateOptionsLocked(returningOptions);
                stack.mUndrawnActivitiesBelowTopTranslucent.add(this);
            }
            setVisible(true);
            sleeping = false;
            app.pendingUiClean = true;
            app.thread.scheduleWindowVisibility(appToken, true /* showWindow */);
            // The activity may be waiting for stop, but that is no longer appropriate for it.
            mStackSupervisor.mStoppingActivities.remove(this);
            mStackSupervisor.mGoingToSleepActivities.remove(this);
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making visibile: " + intent.getComponent(), e);
        }
        handleAlreadyVisible();
    }

    boolean handleAlreadyVisible() {
        stopFreezingScreenLocked(false);
        try {
            if (returningOptions != null) {
                app.thread.scheduleOnNewActivityOptions(appToken, returningOptions.toBundle());
            }
        } catch(RemoteException e) {
        }
        return state == RESUMED;
    }

    static void activityResumedLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (DEBUG_SAVED_STATE) Slog.i(TAG_STATES, "Resumed activity; dropping state of: " + r);
        if (r != null) {
            r.icicle = null;
            r.haveState = false;
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

        if (isHomeActivity()) {
            ProcessRecord app = task.mActivities.get(0).app;
            if (app != null && app != service.mHomeProcess) {
                service.mHomeProcess = app;
            }
        }

        if (nowVisible) {
            // We won't get a call to reportActivityVisibleLocked() so dismiss lockscreen now.
            mStackSupervisor.reportActivityVisibleLocked(this);
        }

        // Schedule an idle timeout in case the app doesn't do it for us.
        mStackSupervisor.scheduleIdleTimeoutLocked(this);

        mStackSupervisor.reportResumedActivityLocked(this);

        resumeKeyDispatchingLocked();
        final ActivityStack stack = getStack();
        stack.mNoAnimActivities.clear();

        // Mark the point when the activity is resuming
        // TODO: To be more accurate, the mark should be before the onCreate,
        //       not after the onResume. But for subsequent starts, onResume is fine.
        if (app != null) {
            cpuTimeAtResume = service.mProcessCpuTracker.getCpuTimeForPid(app.pid);
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

    final void activityStoppedLocked(Bundle newIcicle, PersistableBundle newPersistentState,
            CharSequence description) {
        final ActivityStack stack = getStack();
        if (state != STOPPING) {
            Slog.i(TAG, "Activity reported stop, but no longer stopping: " + this);
            stack.mHandler.removeMessages(STOP_TIMEOUT_MSG, this);
            return;
        }
        if (newPersistentState != null) {
            persistentState = newPersistentState;
            service.notifyTaskPersisterLocked(task, false);
        }
        if (DEBUG_SAVED_STATE) Slog.i(TAG_SAVED_STATE, "Saving icicle of " + this + ": " + icicle);

        if (newIcicle != null) {
            // If icicle is null, this is happening due to a timeout, so we haven't really saved
            // the state.
            icicle = newIcicle;
            haveState = true;
            launchCount = 0;
            updateThumbnailLocked(null /* newThumbnail */, description);
        }
        if (!stopped) {
            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to STOPPED: " + this + " (stop complete)");
            stack.mHandler.removeMessages(STOP_TIMEOUT_MSG, this);
            stopped = true;
            state = STOPPED;

            mWindowContainerController.notifyAppStopped();

            if (finishing) {
                clearOptionsLocked();
            } else {
                if (deferRelaunchUntilPaused) {
                    stack.destroyActivityLocked(this, true /* removeFromApp */, "stop-config");
                    mStackSupervisor.resumeFocusedStackTopActivityLocked();
                } else {
                    mStackSupervisor.updatePreviousProcessLocked(this);
                }
            }
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

        final ActivityStack stack = getStack();
        if (stack == null) {
            return false;
        }

        Message msg = stack.mHandler.obtainMessage(LAUNCH_TICK_MSG, this);
        stack.mHandler.removeMessages(LAUNCH_TICK_MSG);
        stack.mHandler.sendMessageDelayed(msg, LAUNCH_TICK);
        return true;
    }

    void finishLaunchTickingLocked() {
        launchTickTime = 0;
        final ActivityStack stack = getStack();
        if (stack != null) {
            stack.mHandler.removeMessages(LAUNCH_TICK_MSG);
        }
    }

    // IApplicationToken

    public boolean mayFreezeScreenLocked(ProcessRecord app) {
        // Only freeze the screen if this activity is currently attached to
        // an application, and that application is not blocked or unresponding.
        // In any other case, we can't count on getting the screen unfrozen,
        // so it is best to leave as-is.
        return app != null && !app.crashing && !app.notResponding;
    }

    public void startFreezingScreenLocked(ProcessRecord app, int configChanges) {
        if (mayFreezeScreenLocked(app)) {
            mWindowContainerController.startFreezingScreen(configChanges);
        }
    }

    public void stopFreezingScreenLocked(boolean force) {
        if (force || frozenBeforeDestroy) {
            frozenBeforeDestroy = false;
            mWindowContainerController.stopFreezingScreen(force);
        }
    }

    public void reportFullyDrawnLocked(boolean restoredFromBundle) {
        final long curTime = SystemClock.uptimeMillis();
        if (displayStartTime != 0) {
            reportLaunchTimeLocked(curTime);
        }
        final ActivityStack stack = getStack();
        if (fullyDrawnStartTime != 0 && stack != null) {
            final long thisTime = curTime - fullyDrawnStartTime;
            final long totalTime = stack.mFullyDrawnStartTime != 0
                    ? (curTime - stack.mFullyDrawnStartTime) : thisTime;
            if (SHOW_ACTIVITY_START_TIME) {
                Trace.asyncTraceEnd(TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
                EventLog.writeEvent(AM_ACTIVITY_FULLY_DRAWN_TIME,
                        userId, System.identityHashCode(this), shortComponentName,
                        thisTime, totalTime);
                StringBuilder sb = service.mStringBuilder;
                sb.setLength(0);
                sb.append("Fully drawn ");
                sb.append(shortComponentName);
                sb.append(": ");
                TimeUtils.formatDuration(thisTime, sb);
                if (thisTime != totalTime) {
                    sb.append(" (total ");
                    TimeUtils.formatDuration(totalTime, sb);
                    sb.append(")");
                }
                Log.i(TAG, sb.toString());
            }
            if (totalTime > 0) {
                //service.mUsageStatsService.noteFullyDrawnTime(realActivity, (int) totalTime);
            }
            stack.mFullyDrawnStartTime = 0;
        }
        mStackSupervisor.mActivityMetricsLogger.logAppTransitionReportedDrawn(this,
                restoredFromBundle);
        fullyDrawnStartTime = 0;
    }

    private void reportLaunchTimeLocked(final long curTime) {
        final ActivityStack stack = getStack();
        if (stack == null) {
            return;
        }
        final long thisTime = curTime - displayStartTime;
        final long totalTime = stack.mLaunchStartTime != 0
                ? (curTime - stack.mLaunchStartTime) : thisTime;
        if (SHOW_ACTIVITY_START_TIME) {
            Trace.asyncTraceEnd(TRACE_TAG_ACTIVITY_MANAGER, "launching: " + packageName, 0);
            EventLog.writeEvent(AM_ACTIVITY_LAUNCH_TIME,
                    userId, System.identityHashCode(this), shortComponentName,
                    thisTime, totalTime);
            StringBuilder sb = service.mStringBuilder;
            sb.setLength(0);
            sb.append("Displayed ");
            sb.append(shortComponentName);
            sb.append(": ");
            TimeUtils.formatDuration(thisTime, sb);
            if (thisTime != totalTime) {
                sb.append(" (total ");
                TimeUtils.formatDuration(totalTime, sb);
                sb.append(")");
            }
            Log.i(TAG, sb.toString());
        }
        mStackSupervisor.reportActivityLaunchedLocked(false, this, thisTime, totalTime);
        if (totalTime > 0) {
            //service.mUsageStatsService.noteLaunchTime(realActivity, (int)totalTime);
        }
        displayStartTime = 0;
        stack.mLaunchStartTime = 0;
    }

    @Override
    public void onStartingWindowDrawn(long timestamp) {
        synchronized (service) {
            mStackSupervisor.mActivityMetricsLogger.notifyStartingWindowDrawn(
                    getStackId(), timestamp);
        }
    }

    @Override
    public void onWindowsDrawn(long timestamp) {
        synchronized (service) {
            mStackSupervisor.mActivityMetricsLogger.notifyWindowsDrawn(getStackId(), timestamp);
            if (displayStartTime != 0) {
                reportLaunchTimeLocked(timestamp);
            }
            mStackSupervisor.sendWaitingVisibleReportLocked(this);
            startTime = 0;
            finishLaunchTickingLocked();
            if (task != null) {
                task.hasBeenVisible = true;
            }
        }
    }

    @Override
    public void onWindowsVisible() {
        synchronized (service) {
            mStackSupervisor.reportActivityVisibleLocked(this);
            if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsVisibleLocked(): " + this);
            if (!nowVisible) {
                nowVisible = true;
                lastVisibleTime = SystemClock.uptimeMillis();
                if (idle || mStackSupervisor.isStoppingNoHistoryActivity()) {
                    // If this activity was already idle or there is an activity that must be
                    // stopped immediately after visible, then we now need to make sure we perform
                    // the full stop of any activities that are waiting to do so. This is because
                    // we won't do that while they are still waiting for this one to become visible.
                    final int size = mStackSupervisor.mActivitiesWaitingForVisibleActivity.size();
                    if (size > 0) {
                        for (int i = 0; i < size; i++) {
                            final ActivityRecord r =
                                    mStackSupervisor.mActivitiesWaitingForVisibleActivity.get(i);
                            if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "Was waiting for visible: " + r);
                        }
                        mStackSupervisor.mActivitiesWaitingForVisibleActivity.clear();
                        mStackSupervisor.scheduleIdleLocked();
                    }
                } else {
                    // Instead of doing the full stop routine here, let's just hide any activities
                    // we now can, and let them stop when the normal idle happens.
                    mStackSupervisor.processStoppingActivitiesLocked(null /* idleActivity */,
                            false /* remove */, true /* processPausingActivities */);
                }
                service.scheduleAppGcsLocked();
            }
        }
    }

    @Override
    public void onWindowsGone() {
        synchronized (service) {
            if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsGone(): " + this);
            nowVisible = false;
        }
    }

    @Override
    public boolean keyDispatchingTimedOut(String reason, int windowPid) {
        ActivityRecord anrActivity;
        ProcessRecord anrApp;
        boolean windowFromSameProcessAsActivity;
        synchronized (service) {
            anrActivity = getWaitingHistoryRecordLocked();
            anrApp = app;
            windowFromSameProcessAsActivity =
                    app == null || app.pid == windowPid || windowPid == -1;
        }
        if (windowFromSameProcessAsActivity) {
            return service.inputDispatchingTimedOut(anrApp, anrActivity, this, false, reason);
        } else {
            // In this case another process added windows using this activity token. So, we call the
            // generic service input dispatch timed out method so that the right process is blamed.
            return service.inputDispatchingTimedOut(windowPid, false /* aboveSystem */, reason) < 0;
        }
    }

    private ActivityRecord getWaitingHistoryRecordLocked() {
        // First find the real culprit...  if this activity is waiting for
        // another activity to start or has stopped, then the key dispatching
        // timeout should not be caused by this.
        if (mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(this) || stopped) {
            final ActivityStack stack = mStackSupervisor.getFocusedStack();
            // Try to use the one which is closest to top.
            ActivityRecord r = stack.mResumedActivity;
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
        if (!StorageManager.isUserKeyUnlocked(userId)
                && !info.applicationInfo.isEncryptionAware()) {
            return false;
        }

        return (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0
                || (mStackSupervisor.isCurrentProfileLocked(userId)
                && service.mUserController.isUserRunningLocked(userId, 0 /* flags */));
    }

    /**
     * This method will return true if the activity is either visible, is becoming visible, is
     * currently pausing, or is resumed.
     */
    public boolean isInterestingToUserLocked() {
        return visible || nowVisible || state == PAUSING ||
                state == RESUMED;
    }

    void setSleeping(boolean _sleeping) {
        setSleeping(_sleeping, false);
    }

    void setSleeping(boolean _sleeping, boolean force) {
        if (!force && sleeping == _sleeping) {
            return;
        }
        if (app != null && app.thread != null) {
            try {
                app.thread.scheduleSleeping(appToken, _sleeping);
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
        if (activityNdx < 0 || (onlyRoot && activityNdx > task.findEffectiveRootIndex())) {
            return INVALID_TASK_ID;
        }
        return task.taskId;
    }

    static ActivityRecord isInStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return (r != null) ? r.getStack().isInStackLocked(r) : null;
    }

    static ActivityStack getStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null) {
            return r.getStack();
        }
        return null;
    }

    /**
     * @return display id to which this record is attached, -1 if not attached.
     */
    int getDisplayId() {
        final ActivityStack stack = getStack();
        if (stack == null) {
            return -1;
        }
        return stack.mDisplayId;
    }

    final boolean isDestroyable() {
        if (finishing || app == null || state == DESTROYING
                || state == DESTROYED) {
            // This would be redundant.
            return false;
        }
        final ActivityStack stack = getStack();
        if (stack == null || this == stack.mResumedActivity || this == stack.mPausingActivity
                || !haveState || !stopped) {
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
            service.mRecentTasks.saveImage(icon, iconFilePath);
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
        if (mWindowContainerController == null) {
            return;
        }
        if (mTaskOverlay) {
            // We don't show starting window for overlay activities.
            return;
        }

        final CompatibilityInfo compatInfo =
                service.compatibilityInfoForPackageLocked(info.applicationInfo);
        final boolean shown = mWindowContainerController.addStartingWindow(packageName, theme,
                compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                prev != null ? prev.appToken : null, newTask, taskSwitch, isProcessRunning(),
                allowTaskSnapshot(),
                state.ordinal() >= RESUMED.ordinal() && state.ordinal() <= STOPPED.ordinal(),
                fromRecents);
        if (shown) {
            mStartingWindowState = STARTING_WINDOW_SHOWN;
        }
    }

    void removeOrphanedStartingWindow(boolean behindFullscreenActivity) {
        if (mStartingWindowState == STARTING_WINDOW_SHOWN && behindFullscreenActivity) {
            if (DEBUG_VISIBILITY) Slog.w(TAG_VISIBILITY, "Found orphaned starting window " + this);
            mStartingWindowState = STARTING_WINDOW_REMOVED;
            mWindowContainerController.removeStartingWindow();
        }
    }

    int getRequestedOrientation() {
        return mWindowContainerController.getOrientation();
    }

    void setRequestedOrientation(int requestedOrientation) {
        if (ActivityInfo.isFixedOrientation(requestedOrientation) && !fullscreen
                && appInfo.targetSdkVersion >= O_MR1) {
            throw new IllegalStateException("Only fullscreen activities can request orientation");
        }

        final int displayId = getDisplayId();
        final Configuration displayConfig =
                mStackSupervisor.getDisplayOverrideConfiguration(displayId);

        final Configuration config = mWindowContainerController.setOrientation(requestedOrientation,
                displayId, displayConfig, mayFreezeScreenLocked(app));
        if (config != null) {
            frozenBeforeDestroy = true;
            if (!service.updateDisplayOverrideConfigurationLocked(config, this,
                    false /* deferResume */, displayId)) {
                mStackSupervisor.resumeFocusedStackTopActivityLocked();
            }
        }
        service.mTaskChangeNotificationController.notifyActivityRequestedOrientationChanged(
                task.taskId, requestedOrientation);
    }

    void setDisablePreviewScreenshots(boolean disable) {
        mWindowContainerController.setDisablePreviewScreenshots(disable);
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

    void setLastReportedConfiguration(Configuration global, Configuration override) {
        mLastReportedConfiguration.setConfiguration(global, override);
    }

    @Override
    void onOverrideConfigurationChanged(Configuration newConfig) {
        final Configuration currentConfig = getOverrideConfiguration();
        if (currentConfig.equals(newConfig)) {
            return;
        }
        super.onOverrideConfigurationChanged(newConfig);
        if (mWindowContainerController == null) {
            return;
        }
        mWindowContainerController.onOverrideConfigurationChanged(newConfig, mBounds);
    }

    // TODO(b/36505427): Consider moving this method and similar ones to ConfigurationContainer.
    private void updateOverrideConfiguration() {
        mTmpConfig.unset();
        computeBounds(mTmpBounds);
        if (mTmpBounds.equals(mBounds)) {
            return;
        }

        mBounds.set(mTmpBounds);
        // Bounds changed...update configuration to match.
        if (!mBounds.isEmpty()) {
            task.computeOverrideConfiguration(mTmpConfig, mBounds, null /* insetBounds */,
                    false /* overrideWidth */, false /* overrideHeight */);
        }
        onOverrideConfigurationChanged(mTmpConfig);
    }

    /** Returns true if the configuration is compatible with this activity. */
    boolean isConfigurationCompatible(Configuration config) {
        final int orientation = mWindowContainerController != null
                ? mWindowContainerController.getOrientation() : info.screenOrientation;
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
    private void computeBounds(Rect outBounds) {
        outBounds.setEmpty();
        final float maxAspectRatio = info.maxAspectRatio;
        final ActivityStack stack = getStack();
        if (task == null || stack == null || !task.mFullscreen || maxAspectRatio == 0
                || isInVrUiMode(getConfiguration())) {
            // We don't set override configuration if that activity task isn't fullscreen. I.e. the
            // activity is in multi-window mode. Or, there isn't a max aspect ratio specified for
            // the activity. This is indicated by an empty {@link outBounds}. We also don't set it
            // if we are in VR mode.
            return;
        }

        // We must base this on the parent configuration, because we set our override
        // configuration's appBounds based on the result of this method. If we used our own
        // configuration, it would be influenced by past invocations.
        final Configuration configuration = getParent().getConfiguration();
        final int containingAppWidth = configuration.appBounds.width();
        final int containingAppHeight = configuration.appBounds.height();
        int maxActivityWidth = containingAppWidth;
        int maxActivityHeight = containingAppHeight;

        if (containingAppWidth < containingAppHeight) {
            // Width is the shorter side, so we use that to figure-out what the max. height
            // should be given the aspect ratio.
            maxActivityHeight = (int) ((maxActivityWidth * maxAspectRatio) + 0.5f);
        } else {
            // Height is the shorter side, so we use that to figure-out what the max. width
            // should be given the aspect ratio.
            maxActivityWidth = (int) ((maxActivityHeight * maxAspectRatio) + 0.5f);
        }

        if (containingAppWidth <= maxActivityWidth && containingAppHeight <= maxActivityHeight) {
            // The display matches or is less than the activity aspect ratio, so nothing else to do.
            // Return the existing bounds. If this method is running for the first time,
            // {@link mBounds} will be empty (representing no override). If the method has run
            // before, then effect of {@link mBounds} will already have been applied to the
            // value returned from {@link getConfiguration}. Refer to
            // {@link TaskRecord#computeOverrideConfiguration}.
            outBounds.set(mBounds);
            return;
        }

        // Compute configuration based on max supported width and height.
        outBounds.set(0, 0, maxActivityWidth, maxActivityHeight);
        // Position the activity frame on the opposite side of the nav bar.
        final int navBarPosition = service.mWindowManager.getNavBarPosition();
        final int left = navBarPosition == NAV_BAR_LEFT
                ? configuration.appBounds.right - outBounds.width() : 0;
        outBounds.offsetTo(left, 0 /* top */);
    }

    /** Get bounds of the activity. */
    @VisibleForTesting
    Rect getBounds() {
        return new Rect(mBounds);
    }

    /**
     * Make sure the given activity matches the current configuration. Returns false if the activity
     * had to be destroyed.  Returns true if the configuration is the same, or the activity will
     * remain running as-is for whatever reason. Ensures the HistoryRecord is updated with the
     * correct configuration and all other bookkeeping is handled.
     */
    boolean ensureActivityConfigurationLocked(int globalChanges, boolean preserveWindow) {
        final ActivityStack stack = getStack();
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

        // Skip updating configuration for activity that are stopping or stopped.
        if (state == STOPPING || state == STOPPED) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Skipping config check stopped or stopping: " + this);
            return true;
        }

        // TODO: We should add ActivityRecord.shouldBeVisible() that checks if the activity should
        // be visible based on the stack, task, and lockscreen state and use that here instead. The
        // method should be based on the logic in ActivityStack.ensureActivitiesVisibleLocked().
        // Skip updating configuration for activity is a stack that shouldn't be visible.
        if (stack.shouldBeVisible(null /* starting */) == STACK_INVISIBLE) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Skipping config check invisible stack: " + this);
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

        setLastReportedConfiguration(service.getGlobalConfiguration(), newMergedOverrideConfig);

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
        if (app == null || app.thread == null) {
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
            if (app == null || app.thread == null) {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is destroying non-running " + this);
                stack.destroyActivityLocked(this, true, "config");
            } else if (state == PAUSING) {
                // A little annoying: we are waiting for this activity to finish pausing. Let's not
                // do anything now, but just flag that it needs to be restarted when done pausing.
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Config is skipping already pausing " + this);
                deferRelaunchUntilPaused = true;
                preserveWindowOnDeferredRelaunch = preserveWindow;
                return true;
            } else if (state == RESUMED) {
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
        if (appInfo.targetSdkVersion < O
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

        return changes;
    }

    private static boolean isResizeOnlyChange(int change) {
        return (change & ~(CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE | CONFIG_ORIENTATION
                | CONFIG_SCREEN_LAYOUT)) == 0;
    }

    void relaunchActivityLocked(boolean andResume, boolean preserveWindow) {
        if (service.mSuppressResizeConfigChanges && preserveWindow) {
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
                        : AM_RELAUNCH_ACTIVITY, userId, System.identityHashCode(this),
                task.taskId, shortComponentName);

        startFreezingScreenLocked(app, 0);

        try {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_SWITCH,
                    "Moving to " + (andResume ? "RESUMED" : "PAUSED") + " Relaunching " + this
                            + " callers=" + Debug.getCallers(6));
            forceNewConfig = false;
            mStackSupervisor.activityRelaunchingLocked(this);
            app.thread.scheduleRelaunchActivity(appToken, pendingResults, pendingNewIntents,
                    configChangeFlags, !andResume,
                    new Configuration(service.getGlobalConfiguration()),
                    new Configuration(getMergedOverrideConfiguration()), preserveWindow);
            // Note: don't need to call pauseIfSleepingLocked() here, because the caller will only
            // pass in 'andResume' if this activity is currently resumed, which implies we aren't
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
            service.showUnsupportedZoomDialogIfNeededLocked(this);
            service.showAskCompatModeDialogLocked(this);
        } else {
            service.mHandler.removeMessages(PAUSE_TIMEOUT_MSG, this);
            state = PAUSED;
            // if the app is relaunched when it's stopped, and we're not resuming,
            // put it back into stopped state.
            if (stopped) {
                getStack().addToStopping(this, true /* scheduleIdle */, false /* idleDelayed */);
            }
        }

        configChangeFlags = 0;
        deferRelaunchUntilPaused = false;
        preserveWindowOnDeferredRelaunch = false;
    }

    private boolean isProcessRunning() {
        ProcessRecord proc = app;
        if (proc == null) {
            proc = service.mProcessNames.get(processName, info.applicationInfo.uid);
        }
        return proc != null && proc.thread != null;
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
        out.attribute(null, ATTR_USERID, String.valueOf(userId));

        if (taskDescription != null) {
            taskDescription.saveToXml(out);
        }

        out.startTag(null, TAG_INTENT);
        intent.saveToXml(out);
        out.endTag(null, TAG_INTENT);

        if (isPersistable() && persistentState != null) {
            out.startTag(null, TAG_PERSISTABLEBUNDLE);
            persistentState.saveToXml(out);
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

        final ActivityManagerService service = stackSupervisor.mService;
        final ActivityInfo aInfo = stackSupervisor.resolveActivity(intent, resolvedType, 0, null,
                userId);
        if (aInfo == null) {
            throw new XmlPullParserException("restoreActivity resolver error. Intent=" + intent +
                    " resolvedType=" + resolvedType);
        }
        final ActivityRecord r = new ActivityRecord(service, null /* caller */,
                0 /* launchedFromPid */, launchedFromUid, launchedFromPackage, intent, resolvedType,
                aInfo, service.getConfiguration(), null /* resultTo */, null /* resultWho */,
                0 /* reqCode */, componentSpecified, false /* rootVoiceInteraction */,
                stackSupervisor, null /* options */, null /* sourceRecord */);

        r.persistentState = persistentState;
        r.taskDescription = taskDescription;
        r.createTime = createTime;

        return r;
    }

    private static String activityTypeToString(int type) {
        switch (type) {
            case APPLICATION_ACTIVITY_TYPE: return "APPLICATION_ACTIVITY_TYPE";
            case HOME_ACTIVITY_TYPE: return "HOME_ACTIVITY_TYPE";
            case RECENTS_ACTIVITY_TYPE: return "RECENTS_ACTIVITY_TYPE";
            case ASSISTANT_ACTIVITY_TYPE: return "ASSISTANT_ACTIVITY_TYPE";
            default: return Integer.toString(type);
        }
    }

    private static boolean isInVrUiMode(Configuration config) {
        return (config.uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_VR_HEADSET;
    }

    int getUid() {
        return info.applicationInfo.uid;
    }

    void setShowWhenLocked(boolean showWhenLocked) {
        mShowWhenLocked = showWhenLocked;
    }

    /**
     * @return true if the activity contains windows that have
     *         {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED} set or if the activity has set
     *         {@link #mShowWhenLocked}.
     */
    boolean canShowWhenLocked() {
        return mShowWhenLocked || service.mWindowManager.containsShowWhenLockedWindow(appToken);
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
        final ActivityStack stack = getStack();
        return mTurnScreenOn && stack != null &&
                stack.checkKeyguardVisibility(this, true /* shouldBeVisible */, true /* isTop */);
    }

    boolean getTurnScreenOnFlag() {
        return mTurnScreenOn;
    }

    boolean isTopRunningActivity() {
        return mStackSupervisor.topRunningActivityLocked() == this;
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
        sb.append(userId);
        sb.append(' ');
        sb.append(intent.getComponent().flattenToShortString());
        stringName = sb.toString();
        return toString();
    }
}
