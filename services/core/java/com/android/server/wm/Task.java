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

import static android.app.ActivityManager.isStartResultSuccessful;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.INVALID_WINDOWING_MODE;
import static android.app.ActivityTaskManager.RESIZE_MODE_FORCED;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.SurfaceControl.METADATA_TASK_ID;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_APP_CRASHED;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_LOCKTASK;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_RECENTS_ANIMATIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TASKS;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STARTED;
import static com.android.server.wm.ActivityRecord.TRANSFER_SPLASH_SCREEN_COPYING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.H.FIRST_ACTIVITY_TASK_MSG;
import static com.android.server.wm.ActivityTaskSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.ActivityTaskSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityTaskSupervisor.printThisActivity;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_ALLOWLISTED;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_PINNABLE;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.TaskProto.AFFINITY;
import static com.android.server.wm.TaskProto.BOUNDS;
import static com.android.server.wm.TaskProto.CREATED_BY_ORGANIZER;
import static com.android.server.wm.TaskProto.FILLS_PARENT;
import static com.android.server.wm.TaskProto.HAS_CHILD_PIP_ACTIVITY;
import static com.android.server.wm.TaskProto.LAST_NON_FULLSCREEN_BOUNDS;
import static com.android.server.wm.TaskProto.ORIG_ACTIVITY;
import static com.android.server.wm.TaskProto.REAL_ACTIVITY;
import static com.android.server.wm.TaskProto.RESIZE_MODE;
import static com.android.server.wm.TaskProto.RESUMED_ACTIVITY;
import static com.android.server.wm.TaskProto.ROOT_TASK_ID;
import static com.android.server.wm.TaskProto.SURFACE_HEIGHT;
import static com.android.server.wm.TaskProto.SURFACE_WIDTH;
import static com.android.server.wm.TaskProto.TASK_FRAGMENT;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.TASK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ROOT_TASK;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo.PersistedTaskSnapshotData;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppCompatTaskInfo;
import android.app.AppGlobals;
import android.app.CameraCompatTaskInfo;
import android.app.IActivityController;
import android.app.PictureInPictureParams;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.RemoteAnimationAdapter;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManager.TransitionOldType;
import android.window.ITaskOrganizer;
import android.window.PictureInPictureSurfaceTransaction;
import android.window.StartingWindowInfo;
import android.window.TaskFragmentParentInfo;
import android.window.TaskSnapshot;
import android.window.WindowContainerToken;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.uri.NeededUriGrants;
import com.android.window.flags.Flags;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link Task} is a TaskFragment that can contain a group of activities to perform a certain job.
 * Activities of the same task affinities usually group in the same {@link Task}. A {@link Task}
 * can also be an entity that showing in the Recents Screen for a job that user interacted with.
 * A {@link Task} can also contain other {@link Task}s.
 */
class Task extends TaskFragment {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_ATM;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    private static final String ATTR_TASKID = "task_id";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_AFFINITYINTENT = "affinity_intent";
    private static final String ATTR_REALACTIVITY = "real_activity";
    private static final String ATTR_REALACTIVITY_SUSPENDED = "real_activity_suspended";
    private static final String ATTR_ORIGACTIVITY = "orig_activity";
    private static final String TAG_ACTIVITY = "activity";
    private static final String ATTR_AFFINITY = "affinity";
    private static final String ATTR_ROOT_AFFINITY = "root_affinity";
    private static final String ATTR_ROOTHASRESET = "root_has_reset";
    private static final String ATTR_AUTOREMOVERECENTS = "auto_remove_recents";
    private static final String ATTR_USERID = "user_id";
    private static final String ATTR_USER_SETUP_COMPLETE = "user_setup_complete";
    private static final String ATTR_EFFECTIVE_UID = "effective_uid";
    @Deprecated
    private static final String ATTR_TASKTYPE = "task_type";
    private static final String ATTR_LASTDESCRIPTION = "last_description";
    private static final String ATTR_LASTTIMEMOVED = "last_time_moved";
    private static final String ATTR_NEVERRELINQUISH = "never_relinquish_identity";
    private static final String ATTR_TASK_AFFILIATION = "task_affiliation";
    private static final String ATTR_PREV_AFFILIATION = "prev_affiliation";
    private static final String ATTR_NEXT_AFFILIATION = "next_affiliation";
    private static final String ATTR_CALLING_UID = "calling_uid";
    private static final String ATTR_CALLING_PACKAGE = "calling_package";
    private static final String ATTR_CALLING_FEATURE_ID = "calling_feature_id";
    private static final String ATTR_SUPPORTS_PICTURE_IN_PICTURE = "supports_picture_in_picture";
    private static final String ATTR_RESIZE_MODE = "resize_mode";
    private static final String ATTR_NON_FULLSCREEN_BOUNDS = "non_fullscreen_bounds";
    private static final String ATTR_MIN_WIDTH = "min_width";
    private static final String ATTR_MIN_HEIGHT = "min_height";
    private static final String ATTR_PERSIST_TASK_VERSION = "persist_task_version";
    private static final String ATTR_WINDOW_LAYOUT_AFFINITY = "window_layout_affinity";
    private static final String ATTR_LAST_SNAPSHOT_TASK_SIZE = "last_snapshot_task_size";
    private static final String ATTR_LAST_SNAPSHOT_CONTENT_INSETS = "last_snapshot_content_insets";
    private static final String ATTR_LAST_SNAPSHOT_BUFFER_SIZE = "last_snapshot_buffer_size";

    // How long to wait for all background Activities to redraw following a call to
    // convertToTranslucent().
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;

    // Current version of the task record we persist. Used to check if we need to run any upgrade
    // code.
    static final int PERSIST_TASK_VERSION = 1;

    private static final int DEFAULT_MIN_TASK_SIZE_DP = 220;

    /**
     * The modes to control how root task is moved to the front when calling {@link Task#reparent}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            REPARENT_MOVE_ROOT_TASK_TO_FRONT,
            REPARENT_KEEP_ROOT_TASK_AT_FRONT,
            REPARENT_LEAVE_ROOT_TASK_IN_PLACE
    })
    @interface ReparentMoveRootTaskMode {}
    // Moves the root task to the front if it was not at the front
    static final int REPARENT_MOVE_ROOT_TASK_TO_FRONT = 0;
    // Only moves the root task to the front if it was focused or front most already
    static final int REPARENT_KEEP_ROOT_TASK_AT_FRONT = 1;
    // Do not move the root task as a part of reparenting
    static final int REPARENT_LEAVE_ROOT_TASK_IN_PLACE = 2;

    // The topmost Activity passed to convertToTranslucent(). When non-null it means we are
    // waiting for all Activities in mUndrawnActivitiesBelowTopTranslucent to be removed as they
    // are drawn. When the last member of mUndrawnActivitiesBelowTopTranslucent is removed the
    // Activity in mTranslucentActivityWaiting is notified via
    // Activity.onTranslucentConversionComplete(false). If a timeout occurs prior to the last
    // background activity being drawn then the same call will be made with a true value.
    ActivityRecord mTranslucentActivityWaiting = null;
    ArrayList<ActivityRecord> mUndrawnActivitiesBelowTopTranslucent = new ArrayList<>();

    // The topmost Activity that was converted to translucent for scene transition, which should
    // be converted from translucent once the transition is completed, or the app died.
    private ActivityRecord mPendingConvertFromTranslucentActivity = null;

    /**
     * Set when we know we are going to be calling updateConfiguration()
     * soon, so want to skip intermediate config checks.
     */
    boolean mConfigWillChange;

    /**
     * Used to keep resumeTopActivityUncheckedLocked() from being entered recursively
     */
    boolean mInResumeTopActivity = false;

    /**
     * Used to identify if the activity that is installed from device's system image.
     */
    boolean mIsEffectivelySystemApp;

    int mCurrentUser;

    String affinity;        // The affinity name for this task, or null; may change identity.
    String rootAffinity;    // Initial base affinity, or null; does not change from initial root.
    String mWindowLayoutAffinity; // Launch param affinity of this task or null. Used when saving
                                // launch params of this task.
    IVoiceInteractionSession voiceSession;    // Voice interaction session driving task
    IVoiceInteractor voiceInteractor;         // Associated interactor to provide to app
    Intent intent;          // The original intent that started the task. Note that this value can
                            // be null.
    Intent affinityIntent;  // Intent of affinity-moved activity that started this task.
    int effectiveUid;       // The current effective uid of the identity of this task.
    ComponentName origActivity; // The non-alias activity component of the intent.
    ComponentName realActivity; // The actual activity component that started the task.
    boolean realActivitySuspended; // True if the actual activity component that started the
                                   // task is suspended.
    boolean inRecents;      // Actually in the recents list?
    long lastActiveTime;    // Last time this task was active in the current device session,
                            // including sleep. This time is initialized to the elapsed time when
                            // restored from disk.
    boolean isAvailable;    // Is the activity available to be launched?
    boolean rootWasReset;   // True if the intent at the root of the task had
                            // the FLAG_ACTIVITY_RESET_TASK_IF_NEEDED flag.
    boolean autoRemoveRecents;  // If true, we should automatically remove the task from
                                // recents when activity finishes
    private boolean mHasBeenVisible; // Set if any activities in the task have been visible

    String stringName;      // caching of toString() result.
    boolean mUserSetupComplete; // The user set-up is complete as of the last time the task activity
                                // was changed.

    int mLockTaskAuth = LOCK_TASK_AUTH_PINNABLE;

    int mLockTaskUid = -1;  // The uid of the application that called startLockTask().

    /**
     * If non-null, the starting window should cover the associated task. It is assigned when the
     * parent activity of starting window is put in a partial area of the task. This field will be
     * cleared when all visible activities in this task are drawn.
     */
    StartingData mSharedStartingData;

    /** The process that had previously hosted the root activity of this task.
     * Used to know that we should try harder to keep this process around, in case the
     * user wants to return to it. */
    private WindowProcessController mRootProcess;

    /** The TF host info are set once the task has ever added an organized task fragment. */
    int mTaskFragmentHostUid;
    String mTaskFragmentHostProcessName;

    /** Takes on same value as first root activity */
    boolean isPersistable = false;
    int maxRecents;

    /** Only used for persistable tasks, otherwise 0. The last time this task was moved. Used for
     *  determining the order when restoring. */
    long mLastTimeMoved;

    /**
     * If it is set, the processes belong to the task will be killed when one of its activity
     * reports that Activity#onDestroy is done and the task no longer contains perceptible
     * components. This should only be set on a leaf task.
     */
    boolean mKillProcessesOnDestroyed;

    /** If original intent did not allow relinquishing task identity, save that information */
    private boolean mNeverRelinquishIdentity = true;

    /** Avoid reentrant of {@link #removeImmediately(String)}. */
    private boolean mRemoving;

    // Used in the unique case where we are clearing the task in order to reuse it. In that case we
    // do not want to delete the root task when the task goes empty.
    private boolean mReuseTask = false;

    CharSequence lastDescription; // Last description captured for this item.

    int mAffiliatedTaskId; // taskId of parent affiliation or self if no parent.
    Task mPrevAffiliate; // previous task in affiliated chain.
    int mPrevAffiliateTaskId = INVALID_TASK_ID; // previous id for persistence.
    Task mNextAffiliate; // next task in affiliated chain.
    int mNextAffiliateTaskId = INVALID_TASK_ID; // next id for persistence.

    // For relaunching the task from recents as though it was launched by the original launcher.
    int mCallingUid;
    String mCallingPackage;
    String mCallingFeatureId;

    private static final Rect sTmpBounds = new Rect();

    // Last non-fullscreen bounds the task was launched in or resized to.
    // The information is persisted and used to determine the appropriate root task to launch the
    // task into on restore.
    Rect mLastNonFullscreenBounds = null;

    // The surface transition of the target when recents animation is finished.
    // This is originally introduced to carry out the current surface control position and window
    // crop when a multi-activity task enters pip with autoEnterPip enabled. In such case,
    // the surface control of the task will be animated in Launcher and then the top activity is
    // reparented to pinned root task.
    // Do not forget to reset this after reparenting.
    // TODO: remove this once the recents animation is moved to the Shell
    PictureInPictureSurfaceTransaction mLastRecentsAnimationTransaction;
    // The content overlay to be applied with mLastRecentsAnimationTransaction
    // TODO: remove this once the recents animation is moved to the Shell
    SurfaceControl mLastRecentsAnimationOverlay;

    // A surface that is used by TaskFragmentOrganizer to place content on top of own activities and
    // trusted TaskFragments.
    @Nullable
    DecorSurfaceContainer mDecorSurfaceContainer;

    static final int LAYER_RANK_INVISIBLE = -1;
    // Ranking (from top) of this task among all visible tasks. (-1 means it's not visible)
    // This number will be assigned when we evaluate OOM scores for all visible tasks.
    int mLayerRank = LAYER_RANK_INVISIBLE;

    /* Unique identifier for this task. */
    final int mTaskId;
    /* User for which this task was created. */
    // TODO: Make final
    int mUserId;

    // Id of the previous display the root task was on.
    int mPrevDisplayId = INVALID_DISPLAY;

    int mMultiWindowRestoreWindowingMode = INVALID_WINDOWING_MODE;

    /**
     * Last requested orientation reported to DisplayContent. This is different from {@link
     * #mOrientation} in the sense that this takes activities' requested orientation into
     * account. Start with {@link ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} so that we don't need
     * to notify for activities that don't specify any orientation.
     */
    int mLastReportedRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    private final Rect mTmpRect = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    // Based on the {@link ActivityInfo#resizeMode} of the root activity.
    int mResizeMode;

    // Whether or not this task and its activities support PiP. Based on the
    // {@link ActivityInfo#FLAG_SUPPORTS_PICTURE_IN_PICTURE} flag of the root activity.
    boolean mSupportsPictureInPicture;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;

    // This represents the last resolved activity values for this task
    // NOTE: This value needs to be persisted with each task
    private TaskDescription mTaskDescription;

    // Information about the last snapshot that should be persisted with the task to allow SystemUI
    // to layout without loading all the task snapshots
    final PersistedTaskSnapshotData mLastTaskSnapshotData;

    /** @see #setCanAffectSystemUiFlags */
    private boolean mCanAffectSystemUiFlags = true;

    private static Exception sTmpException;

    private boolean mForceShowForAllUsers;

    // The display category name for this task.
    String mRequiredDisplayCategory;

    // TODO(b/160201781): Revisit double invocation issue in Task#removeChild.
    /**
     * Skip {@link ActivityTaskSupervisor#removeTask(Task, boolean, boolean, String)} execution if
     * {@code true} to prevent double traversal of {@link #mChildren} in a loop.
     */
    boolean mInRemoveTask;

    /**
     * When set, disassociate the leaf task if relaunched and reparented it to TDA as root task if
     * possible.
     */
    boolean mReparentLeafTaskIfRelaunch;

    private final AnimatingActivityRegistry mAnimatingActivityRegistry =
            new AnimatingActivityRegistry();

    private static final int TRANSLUCENT_TIMEOUT_MSG = FIRST_ACTIVITY_TASK_MSG + 1;

    private final Handler mHandler;

    private class ActivityTaskHandler extends Handler {

        ActivityTaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case TRANSLUCENT_TIMEOUT_MSG: {
                    synchronized (mAtmService.mGlobalLock) {
                        notifyActivityDrawnLocked(null);
                    }
                } break;
            }
        }
    }

    private static final ResetTargetTaskHelper sResetTargetTaskHelper = new ResetTargetTaskHelper();

    private final FindRootHelper mFindRootHelper = new FindRootHelper();
    private class FindRootHelper implements Predicate<ActivityRecord> {
        private ActivityRecord mRoot;
        private boolean mIgnoreRelinquishIdentity;
        private boolean mSetToBottomIfNone;

        ActivityRecord findRoot(boolean ignoreRelinquishIdentity, boolean setToBottomIfNone) {
            mIgnoreRelinquishIdentity = ignoreRelinquishIdentity;
            mSetToBottomIfNone = setToBottomIfNone;
            forAllActivities(this, false /* traverseTopToBottom */);
            final ActivityRecord root = mRoot;
            mRoot = null;
            return root;
        }

        @Override
        public boolean test(ActivityRecord r) {
            if (mRoot == null && mSetToBottomIfNone) {
                // This is the first activity we are process. Set it as the candidate root in case
                // we don't find a better one.
                mRoot = r;
            }

            if (r.finishing) return false;

            if (mRoot == null || mRoot.finishing) {
                // Set this as the candidate root since it isn't finishing.
                mRoot = r;
            }

            final int uid = mRoot == r ? effectiveUid : r.info.applicationInfo.uid;
            if (mIgnoreRelinquishIdentity
                    || (mRoot.info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0
                    || (mRoot.info.applicationInfo.uid != Process.SYSTEM_UID
                    && !mRoot.info.applicationInfo.isSystemApp()
                    && mRoot.info.applicationInfo.uid != uid)) {
                // No need to relinquish identity, end search.
                return true;
            }

            // Relinquish to next activity
            mRoot = r;
            return false;
        }
    }

    /**
     * The TaskOrganizer which is delegated presentation of this task. If set the Task will
     * emit an WindowContainerToken (allowing access to it's SurfaceControl leash) to the organizers
     * taskAppeared callback, and emit a taskRemoved callback when the Task is vanished.
     */
    ITaskOrganizer mTaskOrganizer;

    /**
     * Prevent duplicate calls to onTaskAppeared.
     */
    boolean mTaskAppearedSent;

    // If the sending of the task appear signal should be deferred until this flag is set back to
    // false.
    private boolean mDeferTaskAppear;

    // Tracking cookie for the creation of this task.
    IBinder mLaunchCookie;

    // The task will be removed when TaskOrganizer, which is managing the task, is destroyed.
    boolean mRemoveWithTaskOrganizer;

    /**
     * Reference to the pinned activity that is logically parented to this task, ie.
     * the previous top activity within this task is put into pinned mode.
     * This always gets cleared in pair with the ActivityRecord-to-Task link as seen in
     * {@link ActivityRecord#clearLastParentBeforePip()}.
     */
    ActivityRecord mChildPipActivity;

    boolean mLastSurfaceShowing;

    boolean mAlignActivityLocaleWithTask = false;

    private Task(ActivityTaskManagerService atmService, int _taskId, Intent _intent,
            Intent _affinityIntent, String _affinity, String _rootAffinity,
            ComponentName _realActivity, ComponentName _origActivity, boolean _rootWasReset,
            boolean _autoRemoveRecents, int _userId, int _effectiveUid,
            String _lastDescription, long lastTimeMoved, boolean neverRelinquishIdentity,
            TaskDescription _lastTaskDescription, PersistedTaskSnapshotData _lastSnapshotData,
            int taskAffiliation, int prevTaskId, int nextTaskId, int callingUid,
            String callingPackage, @Nullable String callingFeatureId, int resizeMode,
            boolean supportsPictureInPicture, boolean _realActivitySuspended,
            boolean userSetupComplete, int minWidth, int minHeight, ActivityInfo info,
            IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            boolean _createdByOrganizer, IBinder _launchCookie, boolean _deferTaskAppear,
            boolean _removeWithTaskOrganizer) {
        super(atmService, null /* fragmentToken */, _createdByOrganizer, false /* isEmbedded */);

        mTaskId = _taskId;
        mUserId = _userId;
        mResizeMode = resizeMode;
        mSupportsPictureInPicture = supportsPictureInPicture;
        mTaskDescription = _lastTaskDescription != null
                ? _lastTaskDescription
                : new TaskDescription();
        mLastTaskSnapshotData = _lastSnapshotData != null
                ? _lastSnapshotData
                : new PersistedTaskSnapshotData();
        affinityIntent = _affinityIntent;
        affinity = _affinity;
        rootAffinity = _rootAffinity;
        voiceSession = _voiceSession;
        voiceInteractor = _voiceInteractor;
        realActivity = _realActivity;
        realActivitySuspended = _realActivitySuspended;
        origActivity = _origActivity;
        rootWasReset = _rootWasReset;
        isAvailable = true;
        autoRemoveRecents = _autoRemoveRecents;
        mUserSetupComplete = userSetupComplete;
        effectiveUid = _effectiveUid;
        touchActiveTime();
        lastDescription = _lastDescription;
        mLastTimeMoved = lastTimeMoved;
        mNeverRelinquishIdentity = neverRelinquishIdentity;
        mAffiliatedTaskId = taskAffiliation;
        mPrevAffiliateTaskId = prevTaskId;
        mNextAffiliateTaskId = nextTaskId;
        mCallingUid = callingUid;
        mCallingPackage = callingPackage;
        mCallingFeatureId = callingFeatureId;
        mResizeMode = resizeMode;
        if (info != null) {
            setIntent(_intent, info);
            setMinDimensions(info);
        } else {
            intent = _intent;
            mMinWidth = minWidth;
            mMinHeight = minHeight;
        }
        mAtmService.getTaskChangeNotificationController().notifyTaskCreated(_taskId, realActivity);
        mHandler = new ActivityTaskHandler(mTaskSupervisor.mLooper);
        mCurrentUser = mAtmService.mAmInternal.getCurrentUserId();

        mLaunchCookie = _launchCookie;
        mDeferTaskAppear = _deferTaskAppear;
        mRemoveWithTaskOrganizer = _removeWithTaskOrganizer;
        EventLogTags.writeWmTaskCreated(mTaskId);
    }

    static Task fromWindowContainerToken(WindowContainerToken token) {
        if (token == null) return null;
        return fromBinder(token.asBinder()).asTask();
    }

    Task reuseAsLeafTask(IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            Intent intent, ActivityInfo info, ActivityRecord activity) {
        voiceSession = _voiceSession;
        voiceInteractor = _voiceInteractor;
        setIntent(activity, intent, info);
        setMinDimensions(info);
        // Before we began to reuse a root task as the leaf task, we used to
        // create a leaf task in this case. Therefore now we won't send out the task created
        // notification when we decide to reuse it here, so we send out the notification below.
        // The reason why the created notification sent out when root task is created doesn't work
        // is that realActivity isn't set until setIntent() method above is called for the first
        // time. Eventually this notification will be removed when we can populate those information
        // when root task is created.
        mAtmService.getTaskChangeNotificationController().notifyTaskCreated(mTaskId, realActivity);
        return this;
    }

    private void cleanUpResourcesForDestroy(WindowContainer<?> oldParent) {
        if (hasChild()) {
            return;
        }

        // This task is going away, so save the last state if necessary.
        saveLaunchingStateIfNeeded(oldParent.getDisplayContent());

        // TODO: VI what about activity?
        final boolean isVoiceSession = voiceSession != null;
        if (isVoiceSession) {
            try {
                voiceSession.taskFinished(intent, mTaskId);
            } catch (RemoteException e) {
            }
        }
        if (shouldAutoRemoveFromRecents(oldParent.asTaskFragment()) || isVoiceSession) {
            // Task creator asked to remove this when done, or this task was a voice
            // interaction, so it should not remain on the recent tasks list.
            mTaskSupervisor.mRecentTasks.remove(this);
        }

        removeIfPossible("cleanUpResourcesForDestroy");
    }

    @VisibleForTesting
    @Override
    void removeIfPossible() {
        removeIfPossible("removeTaskIfPossible");
    }

    void removeIfPossible(String reason) {
        mAtmService.getLockTaskController().clearLockedTask(this);
        if (shouldDeferRemoval()) {
            if (DEBUG_ROOT_TASK) Slog.i(TAG,
                    "removeTask:" + reason + " deferring removing taskId=" + mTaskId);
            return;
        }
        final boolean isLeafTask = isLeafTask();
        removeImmediately(reason);
        if (isLeafTask) {
            mAtmService.getTaskChangeNotificationController().notifyTaskRemoved(mTaskId);

            final TaskDisplayArea taskDisplayArea = getDisplayArea();
            if (taskDisplayArea != null) {
                taskDisplayArea.onLeafTaskRemoved(mTaskId);
            }
        }
    }

    void setResizeMode(int resizeMode) {
        if (mResizeMode == resizeMode) {
            return;
        }
        mResizeMode = resizeMode;
        mRootWindowContainer.ensureActivitiesVisible();
        mRootWindowContainer.resumeFocusedTasksTopActivities();
        updateTaskDescription();
    }

    boolean resize(Rect bounds, int resizeMode, boolean preserveWindow) {
        mAtmService.deferWindowLayout();

        try {
            final boolean forced = (resizeMode & RESIZE_MODE_FORCED) != 0;

            if (getParent() == null) {
                // Task doesn't exist in window manager yet (e.g. was restored from recents).
                // All we can do for now is update the bounds so it can be used when the task is
                // added to window manager.
                setBounds(bounds);
                if (!inFreeformWindowingMode()) {
                    // re-restore the task so it can have the proper root task association.
                    mTaskSupervisor.restoreRecentTaskLocked(this, null, !ON_TOP);
                }
                return true;
            }

            if (!canResizeToBounds(bounds)) {
                throw new IllegalArgumentException("resizeTask: Can not resize task=" + this
                        + " to bounds=" + bounds + " resizeMode=" + mResizeMode);
            }

            // Do not move the task to another root task here.
            // This method assumes that the task is already placed in the right root task.
            // we do not mess with that decision and we only do the resize!

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "resizeTask_" + mTaskId);

            // This variable holds information whether the configuration didn't change in a
            // significant way and the activity was kept the way it was. If it's false, it means
            // the activity had to be relaunched due to configuration change.
            boolean kept = true;
            if (setBounds(bounds, forced) != BOUNDS_CHANGE_NONE) {
                final ActivityRecord r = topRunningActivityLocked();
                if (r != null) {
                    kept = r.ensureActivityConfiguration();
                    // Preserve other windows for resizing because if resizing happens when there
                    // is a dialog activity in the front, the activity that still shows some
                    // content to the user will become black and cause flickers. Note in most cases
                    // this won't cause tons of irrelevant windows being preserved because only
                    // activities in this task may experience a bounds change. Configs for other
                    // activities stay the same.
                    mRootWindowContainer.ensureActivitiesVisible(r);
                    if (!kept) {
                        mRootWindowContainer.resumeFocusedTasksTopActivities();
                    }
                }
            }
            saveLaunchingStateIfNeeded();

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return kept;
        } finally {
            mAtmService.continueWindowLayout();
        }
    }

    /** Convenience method to reparent a task to the top or bottom position of the root task. */
    boolean reparent(Task preferredRootTask, boolean toTop,
            @ReparentMoveRootTaskMode int moveRootTaskMode, boolean animate, boolean deferResume,
            String reason) {
        return reparent(preferredRootTask, toTop ? MAX_VALUE : 0, moveRootTaskMode, animate,
                deferResume, true /* schedulePictureInPictureModeChange */, reason);
    }

    /**
     * Reparents the task into a preferred root task, creating it if necessary.
     *
     * @param preferredRootTask the target root task to move this task
     * @param position the position to place this task in the new root task
     * @param animate whether or not we should wait for the new window created as a part of the
     *            reparenting to be drawn and animated in
     * @param moveRootTaskMode whether or not to move the root task to the front always, only if
     *            it was previously focused & in front, or never
     * @param deferResume whether or not to update the visibility of other tasks and root tasks
     *            that may have changed as a result of this reparenting
     * @param schedulePictureInPictureModeChange specifies whether or not to schedule the PiP mode
     *            change. Callers may set this to false if they are explicitly scheduling PiP mode
     *            changes themselves, like during the PiP animation
     * @param reason the caller of this reparenting
     * @return whether the task was reparented
     */
    // TODO: Inspect all call sites and change to just changing windowing mode of the root task vs.
    // re-parenting the task. Can only be done when we are no longer using static root task Ids.
    boolean reparent(Task preferredRootTask, int position,
            @ReparentMoveRootTaskMode int moveRootTaskMode, boolean animate, boolean deferResume,
            boolean schedulePictureInPictureModeChange, String reason) {
        final ActivityTaskSupervisor supervisor = mTaskSupervisor;
        final RootWindowContainer root = mRootWindowContainer;
        final WindowManagerService windowManager = mAtmService.mWindowManager;
        final Task sourceRootTask = getRootTask();
        final Task toRootTask = supervisor.getReparentTargetRootTask(this, preferredRootTask,
                position == MAX_VALUE);
        if (toRootTask == sourceRootTask) {
            return false;
        }
        if (!canBeLaunchedOnDisplay(toRootTask.getDisplayId())) {
            return false;
        }

        final ActivityRecord topActivity = getTopNonFinishingActivity();

        mAtmService.deferWindowLayout();
        boolean kept = true;
        try {
            final ActivityRecord r = topRunningActivityLocked();
            final boolean wasFocused = r != null && root.isTopDisplayFocusedRootTask(sourceRootTask)
                    && (topRunningActivityLocked() == r);

            // In some cases the focused root task isn't the front root task. E.g. root pinned task.
            // Whenever we are moving the top activity from the front root task we want to make
            // sure to move the root task to the front.
            final boolean wasFront = r != null && sourceRootTask.isTopRootTaskInDisplayArea()
                    && (sourceRootTask.topRunningActivity() == r);

            final boolean moveRootTaskToFront = moveRootTaskMode == REPARENT_MOVE_ROOT_TASK_TO_FRONT
                    || (moveRootTaskMode == REPARENT_KEEP_ROOT_TASK_AT_FRONT
                            && (wasFocused || wasFront));

            reparent(toRootTask, position, moveRootTaskToFront, reason);

            if (schedulePictureInPictureModeChange) {
                // Notify of picture-in-picture mode changes
                supervisor.scheduleUpdatePictureInPictureModeIfNeeded(this, sourceRootTask);
            }

            // If the task had focus before (or we're requested to move focus), move focus to the
            // new root task by moving the root task to the front.
            if (r != null && moveRootTaskToFront) {
                // Move the root task in which we are placing the activity to the front.
                toRootTask.moveToFront(reason);

                // If the original state is resumed, there is no state change to update focused app.
                // So here makes sure the activity focus is set if it is the top.
                if (r.isState(RESUMED) && r == mRootWindowContainer.getTopResumedActivity()) {
                    mAtmService.setLastResumedActivityUncheckLocked(r, reason);
                }
            }
            if (!animate) {
                mTaskSupervisor.mNoAnimActivities.add(topActivity);
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        if (!deferResume) {
            // The task might have already been running and its visibility needs to be synchronized
            // with the visibility of the root task / windows.
            root.ensureActivitiesVisible();
            root.resumeFocusedTasksTopActivities();
        }

        // TODO: Handle incorrect request to move before the actual move, not after.
        supervisor.handleNonResizableTaskIfNeeded(this, preferredRootTask.getWindowingMode(),
                mRootWindowContainer.getDefaultTaskDisplayArea(), toRootTask);

        return (preferredRootTask == toRootTask);
    }

    void touchActiveTime() {
        lastActiveTime = SystemClock.elapsedRealtime();
    }

    long getInactiveDuration() {
        return SystemClock.elapsedRealtime() - lastActiveTime;
    }

    /** @see #setIntent(ActivityRecord, Intent, ActivityInfo) */
    void setIntent(ActivityRecord r) {
        setIntent(r, null /* intent */, null /* info */);
    }

    /**
     * Sets the original intent, and the calling uid and package.
     *
     * @param r The activity that started the task
     * @param intent The task info which could be different from {@code r.intent} if set.
     * @param info The activity info which could be different from {@code r.info} if set.
     */
    void setIntent(ActivityRecord r, @Nullable Intent intent, @Nullable ActivityInfo info) {
        boolean updateIdentity = false;
        if (this.intent == null) {
            updateIdentity = true;
        } else if (!mNeverRelinquishIdentity) {
            final ActivityInfo activityInfo = info != null ? info : r.info;
            updateIdentity = (effectiveUid == Process.SYSTEM_UID || mIsEffectivelySystemApp
                    || effectiveUid == activityInfo.applicationInfo.uid);
        }
        if (updateIdentity) {
            mCallingUid = r.launchedFromUid;
            mCallingPackage = r.launchedFromPackage;
            mCallingFeatureId = r.launchedFromFeatureId;
            setIntent(intent != null ? intent : r.intent, info != null ? info : r.info);
        }
        setLockTaskAuth(r);
    }

    /** Sets the original intent, _without_ updating the calling uid or package. */
    private void setIntent(Intent _intent, ActivityInfo info) {
        if (!isLeafTask()) return;

        mNeverRelinquishIdentity = (info.flags & FLAG_RELINQUISH_TASK_IDENTITY) == 0;
        affinity = info.taskAffinity;
        if (intent == null) {
            // If this task already has an intent associated with it, don't set the root
            // affinity -- we don't want it changing after initially set, but the initially
            // set value may be null.
            rootAffinity = affinity;
            mRequiredDisplayCategory = info.requiredDisplayCategory;
        }
        effectiveUid = info.applicationInfo.uid;
        mIsEffectivelySystemApp = info.applicationInfo.isSystemApp();

        if (info.targetActivity == null) {
            if (_intent != null) {
                // If this Intent has a selector, we want to clear it for the
                // recent task since it is not relevant if the user later wants
                // to re-launch the app.
                if (_intent.getSelector() != null || _intent.getSourceBounds() != null) {
                    _intent = new Intent(_intent);
                    _intent.setSelector(null);
                    _intent.setSourceBounds(null);
                }
            }
            ProtoLog.v(WM_DEBUG_TASKS, "Setting Intent of %s to %s", this, _intent);
            intent = _intent;
            realActivity = _intent != null ? _intent.getComponent() : null;
            origActivity = null;
        } else {
            ComponentName targetComponent = new ComponentName(
                    info.packageName, info.targetActivity);
            if (_intent != null) {
                Intent targetIntent = new Intent(_intent);
                targetIntent.setSelector(null);
                targetIntent.setSourceBounds(null);
                ProtoLog.v(WM_DEBUG_TASKS, "Setting Intent of %s to target %s", this, targetIntent);
                intent = targetIntent;
                realActivity = targetComponent;
                origActivity = _intent.getComponent();
            } else {
                intent = null;
                realActivity = targetComponent;
                origActivity = new ComponentName(info.packageName, info.name);
            }
        }
        mWindowLayoutAffinity =
                info.windowLayout == null ? null : info.windowLayout.windowLayoutAffinity;

        final int intentFlags = intent == null ? 0 : intent.getFlags();
        if ((intentFlags & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            // Once we are set to an Intent with this flag, we count this
            // task as having a true root activity.
            rootWasReset = true;
        }
        mUserId = UserHandle.getUserId(info.applicationInfo.uid);
        mUserSetupComplete = Settings.Secure.getIntForUser(
                mAtmService.mContext.getContentResolver(), USER_SETUP_COMPLETE, 0, mUserId) != 0;
        if ((info.flags & ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS) != 0) {
            // If the activity itself has requested auto-remove, then just always do it.
            autoRemoveRecents = true;
        } else if ((intentFlags & (FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_RETAIN_IN_RECENTS))
                == FLAG_ACTIVITY_NEW_DOCUMENT) {
            // If the caller has not asked for the document to be retained, then we may
            // want to turn on auto-remove, depending on whether the target has set its
            // own document launch mode.
            if (info.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NONE) {
                autoRemoveRecents = false;
            } else {
                autoRemoveRecents = true;
            }
        } else {
            autoRemoveRecents = false;
        }
        if (mResizeMode != info.resizeMode) {
            mResizeMode = info.resizeMode;
            updateTaskDescription();
        }
        mSupportsPictureInPicture = info.supportsPictureInPicture();
        stringName = null;

        // Re-adding the task to Recents once updated
        if (inRecents) {
            mTaskSupervisor.mRecentTasks.remove(this);
            mTaskSupervisor.mRecentTasks.add(this);
        }
    }

    /** Sets the original minimal width and height. */
    void setMinDimensions(ActivityInfo info) {
        if (info != null && info.windowLayout != null) {
            mMinWidth = info.windowLayout.minWidth;
            mMinHeight = info.windowLayout.minHeight;
        } else {
            mMinWidth = INVALID_MIN_SIZE;
            mMinHeight = INVALID_MIN_SIZE;
        }
    }

    /**
     * Return true if the input activity has the same intent filter as the intent this task
     * record is based on (normally the root activity intent).
     */
    boolean isSameIntentFilter(ActivityRecord r) {
        final Intent intent = new Intent(r.intent);
        // Make sure the component are the same if the input activity has the same real activity
        // as the one in the task because either one of them could be the alias activity.
        if (Objects.equals(realActivity, r.mActivityComponent) && this.intent != null) {
            intent.setComponent(this.intent.getComponent());
            if (intent.getSelector() == null) {
                // Make sure the package name the same to prevent one of the intent is set while the
                // other one is not.
                intent.setPackage(this.intent.getPackage());
            }
        }
        return intent.filterEquals(this.intent);
    }

    boolean returnsToHomeRootTask() {
        if (inMultiWindowMode() || !hasChild()) return false;
        if (intent != null) {
            final int returnHomeFlags = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME;
            if ((intent.getFlags() & returnHomeFlags) != returnHomeFlags) {
                return false;
            }
            final Task task = getDisplayArea() != null ? getDisplayArea().getRootHomeTask() : null;
            return !(task != null
                    && mAtmService.getLockTaskController().isLockTaskModeViolation(task));
        }
        final Task bottomTask = getBottomMostTask();
        return bottomTask != this && bottomTask.returnsToHomeRootTask();
    }

    void setPrevAffiliate(Task prevAffiliate) {
        mPrevAffiliate = prevAffiliate;
        mPrevAffiliateTaskId = prevAffiliate == null ? INVALID_TASK_ID : prevAffiliate.mTaskId;
    }

    void setNextAffiliate(Task nextAffiliate) {
        mNextAffiliate = nextAffiliate;
        mNextAffiliateTaskId = nextAffiliate == null ? INVALID_TASK_ID : nextAffiliate.mTaskId;
    }

    @Override
    void onParentChanged(ConfigurationContainer rawNewParent, ConfigurationContainer rawOldParent) {
        final WindowContainer<?> newParent = (WindowContainer<?>) rawNewParent;
        final WindowContainer<?> oldParent = (WindowContainer<?>) rawOldParent;
        final DisplayContent display = newParent != null ? newParent.getDisplayContent() : null;
        final DisplayContent oldDisplay = oldParent != null ? oldParent.getDisplayContent() : null;

        mPrevDisplayId = (oldDisplay != null) ? oldDisplay.mDisplayId : INVALID_DISPLAY;

        if (oldParent != null && newParent == null) {
            cleanUpResourcesForDestroy(oldParent);
        }

        if (display != null) {
            // TODO(b/168037178): Chat with the erosky@ of this code to see if this really makes
            //                    sense here...
            // Rotations are relative to the display. This means if there are 2 displays rotated
            // differently (eg. 2 monitors with one landscape and one portrait), moving a root task
            // from one to the other could look like a rotation change. To prevent this
            // apparent rotation change (and corresponding bounds rotation), pretend like our
            // current rotation is already the same as the new display.
            // Note, if Task or related logic ever gets nested, this logic will need
            // to move to onConfigurationChanged.
            getConfiguration().windowConfiguration.setRotation(
                    display.getWindowConfiguration().getRotation());
        }

        super.onParentChanged(newParent, oldParent);

        // Call this again after super onParentChanged in-case the surface wasn't created yet
        // (happens when the task is first inserted into the hierarchy). It's a no-op if it
        // already ran fully within super.onParentChanged
        updateTaskOrganizerState();

        // TODO(b/168037178): The check for null display content and setting it to null doesn't
        //                    really make sense here...

        // TODO(b/168037178): This is mostly taking care of the case where the stask is removing
        //                    from the display, so we should probably consolidate it there instead.

        if (getParent() == null && mDisplayContent != null) {
            mDisplayContent = null;
            mWmService.mWindowPlacerLocked.requestTraversal();
        }

        if (oldParent != null) {
            final Task oldParentTask = oldParent.asTask();
            if (oldParentTask != null) {
                forAllActivities(oldParentTask::cleanUpActivityReferences);
            }

            if (newParent == null || !newParent.inPinnedWindowingMode()) {
                if (oldParent.inPinnedWindowingMode()) {
                    // Notify if a task from the root pinned task is being removed
                    // (or moved depending on the mode).
                    mRootWindowContainer.notifyActivityPipModeChanged(this, null);
                } else if (inPinnedWindowingMode()) {
                    // The task in pinned mode is removed, even though the old parent was not pinned
                    // The task was most likely force killed or crashed
                    Slog.e(TAG, "Pinned task is removed t=" + this);
                    mRootWindowContainer.notifyActivityPipModeChanged(this, null);
                }
            }
        }

        if (newParent != null) {
            // Surface of Task that will not be organized should be shown by default.
            // See Task#showSurfaceOnCreation
            if (!mCreatedByOrganizer && !canBeOrganized()) {
                getSyncTransaction().show(mSurfaceControl);
            }

            // TODO: Ensure that this is actually necessary here
            // Notify the voice session if required
            if (voiceSession != null) {
                try {
                    voiceSession.taskStarted(intent, mTaskId);
                } catch (RemoteException e) {
                }
            }
        }

        // First time we are adding the task to the system.
        if (oldParent == null && newParent != null) {

            // TODO: Super random place to be doing this, but aligns with what used to be done
            // before we unified Task level. Look into if this can be done in a better place.
            updateOverrideConfigurationFromLaunchBounds();
        }

        mRootWindowContainer.updateUIDsPresentOnDisplay();

        // Ensure all animations are finished at same time in split-screen mode.
        forAllActivities(ActivityRecord::updateAnimatingActivityRegistry);
    }

    @Override
    @Nullable
    ActivityRecord getTopResumedActivity() {
        if (!isLeafTask()) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                ActivityRecord resumedActivity = mChildren.get(i).asTask().getTopResumedActivity();
                if (resumedActivity != null) {
                    return resumedActivity;
                }
            }
        }

        final ActivityRecord taskResumedActivity = getResumedActivity();
        ActivityRecord topResumedActivity = null;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.asTaskFragment() != null) {
                topResumedActivity = child.asTaskFragment().getTopResumedActivity();
            } else if (taskResumedActivity != null
                    && child.asActivityRecord() == taskResumedActivity) {
                topResumedActivity = taskResumedActivity;
            }
            if (topResumedActivity != null) {
                return topResumedActivity;
            }
        }
        return null;
    }

    @Override
    @Nullable
    ActivityRecord getTopPausingActivity() {
        if (!isLeafTask()) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                ActivityRecord pausingActivity = mChildren.get(i).asTask().getTopPausingActivity();
                if (pausingActivity != null) {
                    return pausingActivity;
                }
            }
        }

        final ActivityRecord taskPausingActivity = getPausingActivity();
        ActivityRecord topPausingActivity = null;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.asTaskFragment() != null) {
                topPausingActivity = child.asTaskFragment().getTopPausingActivity();
            } else if (taskPausingActivity != null
                    && child.asActivityRecord() == taskPausingActivity) {
                topPausingActivity = taskPausingActivity;
            }
            if (topPausingActivity != null) {
                return topPausingActivity;
            }
        }
        return null;
    }

    boolean pauseActivityIfNeeded(@Nullable ActivityRecord resuming, @NonNull String reason) {
        if (!isLeafTask()) {
            return false;
        }

        final int[] someActivityPaused = {0};
        // Check if the direct child resumed activity in the leaf task needed to be paused if
        // the leaf task is not a leaf task fragment.
        if (!isLeafTaskFragment()) {
            final ActivityRecord top = topRunningActivity();
            final ActivityRecord resumedActivity = getResumedActivity();
            if (resumedActivity != null
                    && (top == null || top.getTaskFragment() != this || !canBeResumed(resuming))) {
                // Pausing the resumed activity because it is occluded by other task fragment, or
                // should not be remained in resumed state.
                if (startPausing(false /* uiSleeping*/, resuming, reason)) {
                    someActivityPaused[0]++;
                }
            }
        }

        forAllLeafTaskFragments((taskFrag) -> {
            final ActivityRecord resumedActivity = taskFrag.getResumedActivity();
            if (resumedActivity != null && !taskFrag.canBeResumed(resuming)) {
                if (taskFrag.startPausing(false /* uiSleeping*/, resuming, reason)) {
                    someActivityPaused[0]++;
                }
            }
        }, true /* traverseTopToBottom */);

        return someActivityPaused[0] > 0;
    }

    void updateTaskMovement(boolean toTop, boolean toBottom, int position) {
        EventLogTags.writeWmTaskMoved(mTaskId, getRootTaskId(), getDisplayId(), toTop ? 1 : 0,
                position);
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea != null && isLeafTask()) {
            taskDisplayArea.onLeafTaskMoved(this, toTop, toBottom);
        }
        if (isPersistable) {
            mLastTimeMoved = System.currentTimeMillis();
        }
        if (toTop && inRecents) {
            // If task is in recents, ensure it is at the top
            mTaskSupervisor.mRecentTasks.add(this);
        }
    }

    // Close up recents linked list.
    private void closeRecentsChain() {
        if (mPrevAffiliate != null) {
            mPrevAffiliate.setNextAffiliate(mNextAffiliate);
        }
        if (mNextAffiliate != null) {
            mNextAffiliate.setPrevAffiliate(mPrevAffiliate);
        }
        setPrevAffiliate(null);
        setNextAffiliate(null);
    }

    void removedFromRecents() {
        closeRecentsChain();
        if (inRecents) {
            inRecents = false;
            mAtmService.notifyTaskPersisterLocked(this, false);
        }

        clearRootProcess();

        mAtmService.mWindowManager.mTaskSnapshotController.removeAndDeleteSnapshot(
                mTaskId, mUserId);
    }

    void setTaskToAffiliateWith(Task taskToAffiliateWith) {
        closeRecentsChain();
        mAffiliatedTaskId = taskToAffiliateWith.mAffiliatedTaskId;
        // Find the end
        while (taskToAffiliateWith.mNextAffiliate != null) {
            final Task nextRecents = taskToAffiliateWith.mNextAffiliate;
            if (nextRecents.mAffiliatedTaskId != mAffiliatedTaskId) {
                Slog.e(TAG, "setTaskToAffiliateWith: nextRecents=" + nextRecents + " affilTaskId="
                        + nextRecents.mAffiliatedTaskId + " should be " + mAffiliatedTaskId);
                if (nextRecents.mPrevAffiliate == taskToAffiliateWith) {
                    nextRecents.setPrevAffiliate(null);
                }
                taskToAffiliateWith.setNextAffiliate(null);
                break;
            }
            taskToAffiliateWith = nextRecents;
        }
        taskToAffiliateWith.setNextAffiliate(this);
        setPrevAffiliate(taskToAffiliateWith);
        setNextAffiliate(null);
    }

    /** Returns the intent for the root activity for this task */
    Intent getBaseIntent() {
        if (intent != null) return intent;
        if (affinityIntent != null) return affinityIntent;
        // Probably a task that contains other tasks, so return the intent for the top task?
        final Task topTask = getTopMostTask();
        return (topTask != this && topTask != null) ? topTask.getBaseIntent() : null;
    }

    /**
     * Returns the package name which stands for this task. It is empty string if no activities
     * have been added to this task.
     */
    @NonNull
    String getBasePackageName() {
        final Intent intent = getBaseIntent();
        if (intent == null) {
            return "";
        }
        final ComponentName componentName = intent.getComponent();
        return componentName != null ? componentName.getPackageName() : "";
    }

    /** Returns the first non-finishing activity from the bottom. */
    ActivityRecord getRootActivity() {
        // TODO: Figure out why we historical ignore relinquish identity for this case...
        return getRootActivity(true /*ignoreRelinquishIdentity*/, false /*setToBottomIfNone*/);
    }

    ActivityRecord getRootActivity(boolean setToBottomIfNone) {
        return getRootActivity(false /*ignoreRelinquishIdentity*/, setToBottomIfNone);
    }

    ActivityRecord getRootActivity(boolean ignoreRelinquishIdentity, boolean setToBottomIfNone) {
        return mFindRootHelper.findRoot(ignoreRelinquishIdentity, setToBottomIfNone);
    }

    ActivityRecord topRunningActivityLocked() {
        if (getParent() == null) {
            return null;
        }
        return getActivity(ActivityRecord::canBeTopRunning);
    }

    /**
     * Return true if any activities in this task belongs to input uid.
     */
    boolean isUidPresent(int uid) {
        final PooledPredicate p = PooledLambda.obtainPredicate(
                ActivityRecord::isUid, PooledLambda.__(ActivityRecord.class), uid);
        final boolean isUidPresent = getActivity(p) != null;
        p.recycle();
        return isUidPresent;
    }

    WindowState topStartingWindow() {
        return getWindow(w -> w.mAttrs.type == TYPE_APPLICATION_STARTING);
    }

    ActivityRecord topActivityContainsStartingWindow() {
        final WindowState startingWindow = topStartingWindow();
        return startingWindow != null ? startingWindow.mActivityRecord : null;
    }

    /**
     * Reorder the history task so that the passed activity is brought to the front.
     * @return whether it was actually moved (vs already being top).
     */
    final boolean moveActivityToFront(ActivityRecord newTop) {
        ProtoLog.i(WM_DEBUG_ADD_REMOVE, "Removing and adding activity %s to root task at top "
                + "callers=%s", newTop, Debug.getCallers(4));
        final TaskFragment taskFragment = newTop.getTaskFragment();
        boolean moved;
        if (taskFragment != this) {
            if (taskFragment.isEmbedded() && taskFragment.getNonFinishingActivityCount() == 1) {
                taskFragment.mClearedForReorderActivityToFront = true;
            }
            newTop.reparent(this, POSITION_TOP);
            moved = true;
            if (taskFragment.isEmbedded()) {
                mAtmService.mWindowOrganizerController.mTaskFragmentOrganizerController
                        .onActivityReparentedToTask(newTop);
            }
        } else {
            moved = moveChildToFront(newTop);
        }
        updateEffectiveIntent();
        return moved;
    }

    @Override
    void addChild(WindowContainer child, int index) {
        index = getAdjustedChildPosition(child, index);
        super.addChild(child, index);

        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "addChild: %s at top.", this);

        // A rootable task that is now being added to be the child of an organized task. Making
        // sure the root task references is keep updated.
        if (mTaskOrganizer != null && mCreatedByOrganizer && child.asTask() != null) {
            getDisplayArea().addRootTaskReferenceIfNeeded((Task) child);
        }

        // Make sure the list of display UID allowlists is updated
        // now that this record is in a new task.
        mRootWindowContainer.updateUIDsPresentOnDisplay();

        // Only pass minimum dimensions for pure TaskFragment. Task's minimum dimensions must be
        // passed from Task constructor.
        final TaskFragment childTaskFrag = child.asTaskFragment();
        if (childTaskFrag != null && childTaskFrag.asTask() == null) {
            if (childTaskFrag.mTaskFragmentOrganizerProcessName != null
                    && mTaskFragmentHostProcessName == null) {
                mTaskFragmentHostUid = childTaskFrag.mTaskFragmentOrganizerUid;
                mTaskFragmentHostProcessName = childTaskFrag.mTaskFragmentOrganizerProcessName;
            }
            childTaskFrag.setMinDimensions(mMinWidth, mMinHeight);

            // The starting window should keep covering its task when a pure TaskFragment is added
            // because its bounds may not fill the task.
            final ActivityRecord top = getTopMostActivity();
            if (top != null) {
                top.associateStartingWindowWithTaskIfNeeded();
            }
        }
    }

    /** Called when an {@link ActivityRecord} is added as a descendant */
    void onDescendantActivityAdded(boolean hadActivity, int activityType, ActivityRecord r) {
        warnForNonLeafTask("onDescendantActivityAdded");

        // Only set this based on the first activity
        if (!hadActivity) {
            int activityOverrideType =
                    r.getRequestedOverrideConfiguration().windowConfiguration.getActivityType();
            if (activityOverrideType == ACTIVITY_TYPE_UNDEFINED) {
                // Normally non-standard activity type for the activity record will be set when the
                // object is created, however we delay setting the standard application type until
                // this point so that the task can set the type for additional activities added in
                // the else condition below.
                activityOverrideType = activityType != ACTIVITY_TYPE_UNDEFINED ? activityType
                        : ACTIVITY_TYPE_STANDARD;
                // Set the Activity's requestedOverrideConfiguration directly to reduce
                // WC#onConfigurationChanged calls since it will be called while setting the
                // Task's activity type below.
                r.getRequestedOverrideConfiguration().windowConfiguration.setActivityType(
                        activityOverrideType);
            }
            setActivityType(activityOverrideType);
            isPersistable = r.isPersistable();
            mCallingUid = r.launchedFromUid;
            mCallingPackage = r.launchedFromPackage;
            mCallingFeatureId = r.launchedFromFeatureId;
            // Clamp to [1, max].
            maxRecents = Math.min(Math.max(r.info.maxRecents, 1),
                    ActivityTaskManager.getMaxAppRecentsLimitStatic());
        } else {
            // Otherwise make all added activities match this one.
            r.setActivityType(activityType);
        }

        updateEffectiveIntent();
    }

    @Override
    void removeChild(WindowContainer child) {
        removeChild(child, "removeChild");
    }

    void removeChild(WindowContainer r, String reason) {
        // A rootable child task that is now being removed from an organized task. Making sure
        // the root task references is keep updated.
        if (mCreatedByOrganizer && r.asTask() != null) {
            getDisplayArea().removeRootTaskReferenceIfNeeded((Task) r);
        }
        if (!mChildren.contains(r)) {
            Slog.e(TAG, "removeChild: r=" + r + " not found in t=" + this);
            return;
        }

        if (DEBUG_TASK_MOVEMENT) {
            Slog.d(TAG_WM, "removeChild: child=" + r + " reason=" + reason);
        }
        super.removeChild(r, false /* removeSelfIfPossible */);

        if (inPinnedWindowingMode()) {
            // We normally notify listeners of task stack changes on pause, however root pinned task
            // activities are normally in the paused state so no notification will be sent there
            // before the activity is removed. We send it here so instead.
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
        }

        if (mDecorSurfaceContainer != null && r == mDecorSurfaceContainer.mOwnerTaskFragment) {
            // Remove the decor surface if the owner TaskFragment is removed;
            removeDecorSurface();
        }

        if (hasChild()) {
            updateEffectiveIntent();

            // The following block can be executed multiple times if there is more than one overlay.
            // {@link ActivityTaskSupervisor#removeTaskByIdLocked} handles this by reverse lookup
            // of the task by id and exiting early if not found.
            if (onlyHasTaskOverlayActivities(true /*includeFinishing*/)) {
                // When destroying a task, tell the supervisor to remove it so that any activity it
                // has can be cleaned up correctly. This is currently the only place where we remove
                // a task with the DESTROYING mode, so instead of passing the onlyHasTaskOverlays
                // state into removeChild(), we just clear the task here before the other residual
                // work.
                // TODO: If the callers to removeChild() changes such that we have multiple places
                //       where we are destroying the task, move this back into removeChild()
                mTaskSupervisor.removeTask(this, false /* killProcess */,
                        !REMOVE_FROM_RECENTS, reason);
            }
        } else if (!mReuseTask && shouldRemoveSelfOnLastChildRemoval()) {
            reason += ", last child = " + r + " in " + this;
            removeIfPossible(reason);
        }
    }

    /**
     * @return whether or not there are ONLY task overlay activities in the task.
     *         If {@param includeFinishing} is set, then don't ignore finishing activities in the
     *         check. If there are no task overlay activities, this call returns false.
     */
    boolean onlyHasTaskOverlayActivities(boolean includeFinishing) {
        int count = 0;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final ActivityRecord r = getChildAt(i).asActivityRecord();
            if (r == null) {
                // Has a child that is other than Activity.
                return false;
            }
            if (!includeFinishing && r.finishing) {
                continue;
            }
            if (!r.isTaskOverlay()) {
                return false;
            }
            count++;
        }
        return count > 0;
    }

    private boolean shouldAutoRemoveFromRecents(TaskFragment oldParentFragment) {
        // We will automatically remove the task either if it has explicitly asked for
        // this, or it is empty and has never contained an activity that got shown to
        // the user, or it was being embedded in another Task, or the display policy
        // doesn't allow recents,
        return autoRemoveRecents || (!hasChild() && !getHasBeenVisible())
                || (oldParentFragment != null && oldParentFragment.isEmbedded())
                || (mDisplayContent != null && !mDisplayContent.canShowTasksInHostDeviceRecents());
    }

    private void clearPinnedTaskIfNeed() {
        // The original task is to be removed, try remove also the pinned task.
        if (mChildPipActivity != null && mChildPipActivity.getTask() != null) {
            mTaskSupervisor.removeRootTask(mChildPipActivity.getTask());
        }
    }

    /** Completely remove all activities associated with an existing task. */
    void removeActivities(String reason, boolean excludingTaskOverlay) {
        clearPinnedTaskIfNeed();
        // Broken down into to cases to avoid object create due to capturing mStack.
        if (getRootTask() == null) {
            forAllActivities((r) -> {
                if (r.finishing || (excludingTaskOverlay && r.isTaskOverlay())) {
                    return;
                }
                // Task was restored from persistent storage.
                r.takeFromHistory();
                removeChild(r, reason);
            });
        } else {
            final ArrayList<ActivityRecord> finishingActivities = new ArrayList<>();
            forAllActivities(r -> {
                if (r.finishing || (excludingTaskOverlay && r.isTaskOverlay())) {
                    return;
                }
                finishingActivities.add(r);
            });

            // Finish or destroy apps from the bottom to ensure that all the other activity have
            // been finished and the top task in another task gets resumed when a top activity is
            // removed. Otherwise, the next top activity could be started while the top activity
            // is removed, which is not necessary since the next top activity is on the same Task
            // and should also be removed.
            for (int i = finishingActivities.size() - 1; i >= 0; i--) {
                final ActivityRecord r = finishingActivities.get(i);

                // Prevent the transition from being executed too early if the top activity is
                // resumed but the mVisibleRequested of any other activity is true, the transition
                // should wait until next activity resumed.
                if (r.isState(RESUMED) || (r.isVisible()
                        && !mDisplayContent.mAppTransition.containsTransitRequest(TRANSIT_CLOSE))) {
                    r.finishIfPossible(reason, false /* oomAdj */);
                } else {
                    r.destroyIfPossible(reason);
                }
            }
        }
    }

    /**
     * Completely remove all activities associated with an existing task.
     */
    void performClearTaskForReuse(boolean excludingTaskOverlay) {
        mReuseTask = true;
        mTaskSupervisor.beginDeferResume();
        try {
            removeActivities("clear-task-all", excludingTaskOverlay);
        } finally {
            mTaskSupervisor.endDeferResume();
            mReuseTask = false;
        }
    }

    ActivityRecord performClearTop(ActivityRecord newR, int launchFlags, int[] finishCount) {
        // The task should be preserved for putting new activity in case the last activity is
        // finished if it is normal launch mode and not single top ("clear-task-top").
        mReuseTask = true;
        mTaskSupervisor.beginDeferResume();
        final ActivityRecord result;
        try {
            result = clearTopActivities(newR, launchFlags, finishCount);
        } finally {
            mTaskSupervisor.endDeferResume();
            mReuseTask = false;
        }
        return result;
    }

    /**
     * Perform clear operation as requested by
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP}: search from the top of the
     * root task to the given task, then look for
     * an instance of that activity in the root task and, if found, finish all
     * activities on top of it and return the instance.
     *
     * @param newR Description of the new activity being started.
     * @param finishCount 1-element array that will be populated with the number of activities
     *                    that have been finished.
     * @return Returns the existing activity in the task that performs the clear-top operation,
     * or {@code null} if none was found.
     */
    private ActivityRecord clearTopActivities(ActivityRecord newR, int launchFlags,
            int[] finishCount) {
        final ActivityRecord r = findActivityInHistory(newR.mActivityComponent, newR.mUserId);
        if (r == null) return null;

        moveTaskFragmentsToBottomIfNeeded(r, finishCount);

        final PooledPredicate f = PooledLambda.obtainPredicate(
                (ActivityRecord ar, ActivityRecord boundaryActivity) ->
                        finishActivityAbove(ar, boundaryActivity, finishCount),
                PooledLambda.__(ActivityRecord.class), r);
        forAllActivities(f);
        f.recycle();

        // Finally, if this is a normal launch mode (that is, not expecting onNewIntent()), then we
        // will finish the current instance of the activity so a new fresh one can be started.
        if (r.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                && (launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) == 0
                && !ActivityStarter.isDocumentLaunchesIntoExisting(launchFlags)) {
            if (!r.finishing) {
                r.finishIfPossible("clear-task-top", false /* oomAdj */);
            }
        }

        return r;
    }

    /**
     * Moves {@link TaskFragment}s to the bottom if the flag
     * {@link TaskFragment#isMoveToBottomIfClearWhenLaunch} is {@code true}.
     */
    @VisibleForTesting
    void moveTaskFragmentsToBottomIfNeeded(@NonNull ActivityRecord r, @NonNull int[] finishCount) {
        final int activityIndex = mChildren.indexOf(r);
        if (activityIndex < 0) {
            return;
        }

        List<TaskFragment> taskFragmentsToMove = null;

        // Find the TaskFragments that need to be moved
        for (int i = mChildren.size() - 1; i > activityIndex; i--) {
            final TaskFragment taskFragment = mChildren.get(i).asTaskFragment();
            if (taskFragment != null && taskFragment.isMoveToBottomIfClearWhenLaunch()) {
                if (taskFragmentsToMove == null) {
                    taskFragmentsToMove = new ArrayList<>();
                }
                taskFragmentsToMove.add(taskFragment);
            }
        }
        if (taskFragmentsToMove == null) {
            return;
        }

        // Move the TaskFragments to the bottom of the Task. Their relative orders are preserved.
        final int size = taskFragmentsToMove.size();
        for (int i = 0; i < size; i++) {
            final TaskFragment taskFragment = taskFragmentsToMove.get(i);

            // The visibility of the TaskFragment may change. Collect it in the transition so that
            // transition animation can be properly played.
            mTransitionController.collect(taskFragment);

            positionChildAt(POSITION_BOTTOM, taskFragment, false /* includeParents */);
        }

        // Treat it as if the TaskFragments are finished so that a transition animation can be
        // played to send the TaskFragments back and bring the activity to front.
        finishCount[0] += size;
    }

    private static boolean finishActivityAbove(ActivityRecord r, ActivityRecord boundaryActivity,
            @NonNull int[] finishCount) {
        // Stop operation once we reach the boundary activity.
        if (r == boundaryActivity) return true;

        if (!r.finishing && !r.isTaskOverlay()) {
            final ActivityOptions opts = r.getOptions();
            if (opts != null) {
                r.clearOptionsAnimation();
                // TODO: Why is this updating the boundary activity vs. the current activity???
                boundaryActivity.updateOptionsLocked(opts);
            }
            finishCount[0] += 1;
            r.finishIfPossible("clear-task-stack", false /* oomAdj */);
        }

        return false;
    }

    String lockTaskAuthToString() {
        switch (mLockTaskAuth) {
            case LOCK_TASK_AUTH_DONT_LOCK: return "LOCK_TASK_AUTH_DONT_LOCK";
            case LOCK_TASK_AUTH_PINNABLE: return "LOCK_TASK_AUTH_PINNABLE";
            case LOCK_TASK_AUTH_LAUNCHABLE: return "LOCK_TASK_AUTH_LAUNCHABLE";
            case LOCK_TASK_AUTH_ALLOWLISTED: return "LOCK_TASK_AUTH_ALLOWLISTED";
            case LOCK_TASK_AUTH_LAUNCHABLE_PRIV: return "LOCK_TASK_AUTH_LAUNCHABLE_PRIV";
            default: return "unknown=" + mLockTaskAuth;
        }
    }

    void setLockTaskAuth() {
        setLockTaskAuth(getRootActivity());
    }

    private void setLockTaskAuth(@Nullable ActivityRecord r) {
        mLockTaskAuth = mAtmService.getLockTaskController().getLockTaskAuth(r, this);
        ProtoLog.d(WM_DEBUG_LOCKTASK, "setLockTaskAuth: task=%s mLockTaskAuth=%s", this,
                lockTaskAuthToString());
    }

    boolean supportsFreeform() {
        return supportsFreeformInDisplayArea(getDisplayArea());
    }

    /**
     * @return whether this task supports freeform multi-window if it is in the given
     *         {@link TaskDisplayArea}.
     */
    boolean supportsFreeformInDisplayArea(@Nullable TaskDisplayArea tda) {
        return mAtmService.mSupportsFreeformWindowManagement
                && supportsMultiWindowInDisplayArea(tda);
    }

    /**
     * Check whether this task can be launched on the specified display.
     *
     * @param displayId Target display id.
     * @return {@code true} if either it is the default display or this activity can be put on a
     *         secondary display.
     */
    boolean canBeLaunchedOnDisplay(int displayId) {
        return mTaskSupervisor.canPlaceEntityOnDisplay(displayId,
                -1 /* don't check PID */, -1 /* don't check UID */, this);
    }

    /**
     * Check that a given bounds matches the application requested orientation.
     *
     * @param bounds The bounds to be tested.
     * @return True if the requested bounds are okay for a resizing request.
     */
    private boolean canResizeToBounds(Rect bounds) {
        if (bounds == null || !inFreeformWindowingMode()) {
            // Note: If not on the freeform workspace, we ignore the bounds.
            return true;
        }
        final boolean landscape = bounds.width() > bounds.height();
        final Rect configBounds = getRequestedOverrideBounds();
        if (mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION) {
            return configBounds.isEmpty()
                    || landscape == (configBounds.width() > configBounds.height());
        }
        return (mResizeMode != RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY || !landscape)
                && (mResizeMode != RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY || landscape);
    }

    /**
     * @return {@code true} if the task is being cleared for the purposes of being reused.
     */
    boolean isClearingToReuseTask() {
        return mReuseTask;
    }

    /**
     * Find the activity in the history task within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    ActivityRecord findActivityInHistory(ComponentName component, int userId) {
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::matchesActivityInHistory,
                PooledLambda.__(ActivityRecord.class), component, userId);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean matchesActivityInHistory(
            ActivityRecord r, ComponentName activityComponent, int userId) {
        return !r.finishing && r.mActivityComponent.equals(activityComponent)
                && r.mUserId == userId;
    }

    /** Updates the last task description values. */
    void updateTaskDescription() {
        final ActivityRecord root = getRootActivity(true);
        if (root == null) return;

        final TaskDescription taskDescription = new TaskDescription();
        final PooledPredicate f = PooledLambda.obtainPredicate(
                Task::setTaskDescriptionFromActivityAboveRoot,
                PooledLambda.__(ActivityRecord.class), root, taskDescription);
        forAllActivities(f);
        f.recycle();
        taskDescription.setResizeMode(mResizeMode);
        taskDescription.setMinWidth(mMinWidth);
        taskDescription.setMinHeight(mMinHeight);
        setTaskDescription(taskDescription);
        mAtmService.getTaskChangeNotificationController().notifyTaskDescriptionChanged(
                getTaskInfo());

        final WindowContainer parent = getParent();
        if (parent != null) {
            final Task t = parent.asTask();
            if (t != null) {
                t.updateTaskDescription();
            }
        }

        dispatchTaskInfoChangedIfNeeded(false /* force */);
    }

    private static boolean setTaskDescriptionFromActivityAboveRoot(
            ActivityRecord r, ActivityRecord root, TaskDescription td) {
        if (!r.isTaskOverlay() && r.taskDescription != null) {
            final TaskDescription atd = r.taskDescription;
            if (td.getLabel() == null) {
                td.setLabel(atd.getLabel());
            }
            if (td.getRawIcon() == null) {
                td.setIcon(atd.getRawIcon());
            }
            if (td.getIconFilename() == null) {
                td.setIconFilename(atd.getIconFilename());
            }
            if (td.getPrimaryColor() == 0) {
                td.setPrimaryColor(atd.getPrimaryColor());
            }
            if (td.getBackgroundColor() == 0) {
                td.setBackgroundColor(atd.getBackgroundColor());
            }
            if (td.getStatusBarColor() == 0) {
                td.setStatusBarColor(atd.getStatusBarColor());
                td.setEnsureStatusBarContrastWhenTransparent(
                        atd.getEnsureStatusBarContrastWhenTransparent());
            }
            if (td.getSystemBarsAppearance() == 0) {
                td.setSystemBarsAppearance(atd.getSystemBarsAppearance());
            }
            if (td.getNavigationBarColor() == 0) {
                td.setNavigationBarColor(atd.getNavigationBarColor());
                td.setEnsureNavigationBarContrastWhenTransparent(
                        atd.getEnsureNavigationBarContrastWhenTransparent());
            }
            if (td.getBackgroundColorFloating() == 0) {
                td.setBackgroundColorFloating(atd.getBackgroundColorFloating());
            }
        }

        // End search once we get to root.
        return r == root;
    }

    // TODO (AM refactor): Invoke automatically when there is a change in children
    @VisibleForTesting
    void updateEffectiveIntent() {
        final ActivityRecord root = getRootActivity(true /*setToBottomIfNone*/);
        if (root != null) {
            setIntent(root);
            // Update the task description when the activities change
            updateTaskDescription();
        }
    }

    void setLastNonFullscreenBounds(Rect bounds) {
        if (mLastNonFullscreenBounds == null) {
            mLastNonFullscreenBounds = new Rect(bounds);
        } else {
            mLastNonFullscreenBounds.set(bounds);
        }
    }

    private void onConfigurationChangedInner(Configuration newParentConfig) {
        // Check if the new configuration supports persistent bounds (eg. is Freeform) and if so
        // restore the last recorded non-fullscreen bounds.
        final boolean prevPersistTaskBounds = getWindowConfiguration().persistTaskBounds();
        boolean nextPersistTaskBounds =
                getRequestedOverrideConfiguration().windowConfiguration.persistTaskBounds();
        if (getRequestedOverrideWindowingMode() == WINDOWING_MODE_UNDEFINED) {
            nextPersistTaskBounds = newParentConfig.windowConfiguration.persistTaskBounds();
        }
        // Only restore to the last non-fullscreen bounds when the requested override bounds
        // have not been explicitly set already.
        nextPersistTaskBounds &=
                (getRequestedOverrideConfiguration().windowConfiguration.getBounds() == null
                || getRequestedOverrideConfiguration().windowConfiguration.getBounds().isEmpty());
        if (!prevPersistTaskBounds && nextPersistTaskBounds
                && mLastNonFullscreenBounds != null && !mLastNonFullscreenBounds.isEmpty()) {
            // Bypass onRequestedOverrideConfigurationChanged here to avoid infinite loop.
            getRequestedOverrideConfiguration().windowConfiguration
                    .setBounds(mLastNonFullscreenBounds);
        }

        final int prevWinMode = getWindowingMode();
        mTmpPrevBounds.set(getBounds());
        final boolean wasInMultiWindowMode = inMultiWindowMode();
        final boolean wasInPictureInPicture = inPinnedWindowingMode();
        super.onConfigurationChanged(newParentConfig);
        // Only need to update surface size here since the super method will handle updating
        // surface position.
        updateSurfaceSize(getSyncTransaction());

        final boolean pipChanging = wasInPictureInPicture != inPinnedWindowingMode();
        if (pipChanging) {
            mTaskSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(this, getRootTask());
        } else if (wasInMultiWindowMode != inMultiWindowMode()) {
            mTaskSupervisor.scheduleUpdateMultiWindowMode(this);
        }

        if (shouldStartChangeTransition(prevWinMode, mTmpPrevBounds)) {
            initializeChangeTransition(mTmpPrevBounds);
        }

        // If the configuration supports persistent bounds (eg. Freeform), keep track of the
        // current (non-fullscreen) bounds for persistence.
        if (getWindowConfiguration().persistTaskBounds()) {
            final Rect currentBounds = getRequestedOverrideBounds();
            if (!currentBounds.isEmpty()) {
                setLastNonFullscreenBounds(currentBounds);
            }
        }

        if (pipChanging && wasInPictureInPicture
                && !mTransitionController.isShellTransitionsEnabled()) {
            // If the top activity is changing from PiP to fullscreen with fixed rotation,
            // clear the crop and rotation matrix of task because fixed rotation will handle
            // the transformation on activity level. This also avoids flickering caused by the
            // latency of fullscreen task organizer configuring the surface.
            final ActivityRecord r = topRunningActivity();
            if (r != null && mDisplayContent.isFixedRotationLaunchingApp(r)) {
                resetSurfaceControlTransforms();
            }
        }

        saveLaunchingStateIfNeeded();
        final boolean taskOrgChanged = updateTaskOrganizerState();
        if (taskOrgChanged) {
            updateSurfacePosition(getSyncTransaction());
            if (!isOrganized()) {
                // Surface-size update was skipped before (since internally it no-ops if
                // isOrganized() is true); however, now that this is not organized, the surface
                // size needs to be updated by WM.
                updateSurfaceSize(getSyncTransaction());
            }
        }
        // If the task organizer has changed, then it will already be receiving taskAppeared with
        // the latest task-info thus the task-info won't have changed.
        if (!taskOrgChanged) {
            dispatchTaskInfoChangedIfNeeded(false /* force */);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        if (mDisplayContent != null
                && mDisplayContent.mPinnedTaskController.isFreezingTaskConfig(this)) {
            // It happens when animating from fullscreen to PiP with orientation change. Because
            // the activity in this pinned task is in fullscreen windowing mode (see
            // RootWindowContainer#moveActivityToPinnedRootTask) and the activity will be set to
            // pinned mode after the animation is done, the configuration change by orientation
            // change is just an intermediate state that should be ignored to avoid flickering.
            return;
        }
        // Calling Task#onConfigurationChanged() for leaf task since the ops in this method are
        // particularly for root tasks, like preventing bounds changes when inheriting certain
        // windowing mode.
        if (!isRootTask()) {
            onConfigurationChangedInner(newParentConfig);
            return;
        }

        final int prevWindowingMode = getWindowingMode();
        final boolean prevIsAlwaysOnTop = isAlwaysOnTop();
        final int prevRotation = getWindowConfiguration().getRotation();
        final Rect newBounds = mTmpRect;
        // Initialize the new bounds by previous bounds as the input and output for calculating
        // override bounds in pinned (pip) or split-screen mode.
        getBounds(newBounds);

        onConfigurationChangedInner(newParentConfig);

        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            return;
        }

        if (prevWindowingMode != getWindowingMode()) {
            taskDisplayArea.onRootTaskWindowingModeChanged(this);
        }

        if (!isOrganized() && !getRequestedOverrideBounds().isEmpty() && mDisplayContent != null) {
            // If the parent (display) has rotated, rotate our bounds to best-fit where their
            // bounds were on the pre-rotated display.
            final int newRotation = getWindowConfiguration().getRotation();
            final boolean rotationChanged = prevRotation != newRotation;
            if (rotationChanged) {
                mDisplayContent.rotateBounds(prevRotation, newRotation, newBounds);
                setBounds(newBounds);
            }
        }

        if (prevIsAlwaysOnTop != isAlwaysOnTop()) {
            // Since always on top is only on when the root task is freeform or pinned, the state
            // can be toggled when the windowing mode changes. We must make sure the root task is
            // placed properly when always on top state changes.
            taskDisplayArea.positionChildAt(POSITION_TOP, this, false /* includingParents */);
        }
    }

    void resolveLeafTaskOnlyOverrideConfigs(Configuration newParentConfig, Rect previousBounds) {
        if (!isLeafTask()) {
            return;
        }

        int windowingMode =
                getResolvedOverrideConfiguration().windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = newParentConfig.windowConfiguration.getWindowingMode();
        }
        // Commit the resolved windowing mode so the canSpecifyOrientation won't get the old
        // mode that may cause the bounds to be miscalculated, e.g. letterboxed.
        getConfiguration().windowConfiguration.setWindowingMode(windowingMode);
        Rect outOverrideBounds = getResolvedOverrideConfiguration().windowConfiguration.getBounds();

        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            if (!mCreatedByOrganizer) {
                // Use empty bounds to indicate "fill parent".
                outOverrideBounds.setEmpty();
            }
            // The bounds for fullscreen mode shouldn't be adjusted by minimal size. Otherwise if
            // the parent or display is smaller than the size, the content may be cropped.
            return;
        }

        adjustForMinimalTaskDimensions(outOverrideBounds, previousBounds, newParentConfig);
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            computeFreeformBounds(outOverrideBounds, newParentConfig);
            return;
        }
    }

    void adjustForMinimalTaskDimensions(@NonNull Rect bounds, @NonNull Rect previousBounds,
            @NonNull Configuration parentConfig) {
        int minWidth = mMinWidth;
        int minHeight = mMinHeight;
        // If the task has no requested minimal size, we'd like to enforce a minimal size
        // so that the user can not render the task fragment too small to manipulate. We don't need
        // to do this for the root pinned task as the bounds are controlled by the system.
        if (!inPinnedWindowingMode()) {
            // Use Display specific min sizes when there is one associated with this Task.
            final int defaultMinSizeDp = mDisplayContent == null
                    ? DEFAULT_MIN_TASK_SIZE_DP : mDisplayContent.mMinSizeOfResizeableTaskDp;
            final float density = (float) parentConfig.densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            final int defaultMinSize = (int) (defaultMinSizeDp * density);

            if (minWidth == INVALID_MIN_SIZE) {
                minWidth = defaultMinSize;
            }
            if (minHeight == INVALID_MIN_SIZE) {
                minHeight = defaultMinSize;
            }
        }
        if (bounds.isEmpty()) {
            // If inheriting parent bounds, check if parent bounds adhere to minimum size. If they
            // do, we can just skip.
            final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
            if (parentBounds.width() >= minWidth && parentBounds.height() >= minHeight) {
                return;
            }
            bounds.set(parentBounds);
        }
        final boolean adjustWidth = minWidth > bounds.width();
        final boolean adjustHeight = minHeight > bounds.height();
        if (!(adjustWidth || adjustHeight)) {
            return;
        }

        if (adjustWidth) {
            if (!previousBounds.isEmpty() && bounds.right == previousBounds.right) {
                bounds.left = bounds.right - minWidth;
            } else {
                // Either left bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping left.
                bounds.right = bounds.left + minWidth;
            }
        }
        if (adjustHeight) {
            if (!previousBounds.isEmpty() && bounds.bottom == previousBounds.bottom) {
                bounds.top = bounds.bottom - minHeight;
            } else {
                // Either top bounds match, or neither match, or the previous bounds were
                // fullscreen and we default to keeping top.
                bounds.bottom = bounds.top + minHeight;
            }
        }
    }

    /** Computes bounds for {@link WindowConfiguration#WINDOWING_MODE_FREEFORM}. */
    private void computeFreeformBounds(@NonNull Rect outBounds,
            @NonNull Configuration newParentConfig) {
        // by policy, make sure the window remains within parent somewhere
        final float density =
                ((float) newParentConfig.densityDpi) / DisplayMetrics.DENSITY_DEFAULT;
        final Rect parentBounds =
                new Rect(newParentConfig.windowConfiguration.getBounds());
        final DisplayContent display = getDisplayContent();
        if (display != null) {
            // If a freeform window moves below system bar, there is no way to move it again
            // by touch. Because its caption is covered by system bar. So we exclude them
            // from root task bounds. and then caption will be shown inside stable area.
            final Rect stableBounds = new Rect();
            display.getStableRect(stableBounds);
            parentBounds.intersect(stableBounds);
        }

        fitWithinBounds(outBounds, parentBounds,
                (int) (density * WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP),
                (int) (density * WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP));

        // Prevent to overlap caption with stable insets.
        final int offsetTop = parentBounds.top - outBounds.top;
        if (offsetTop > 0) {
            outBounds.offset(0, offsetTop);
        }
    }

    /**
     * Adjusts bounds to stay within root task bounds.
     *
     * Since bounds might be outside of root task bounds, this method tries to move the bounds in
     * a way that keep them unchanged, but be contained within the root task bounds.
     *
     * @param bounds Bounds to be adjusted.
     * @param rootTaskBounds Bounds within which the other bounds should remain.
     * @param overlapPxX The amount of px required to be visible in the X dimension.
     * @param overlapPxY The amount of px required to be visible in the Y dimension.
     */
    private static void fitWithinBounds(Rect bounds, Rect rootTaskBounds, int overlapPxX,
            int overlapPxY) {
        if (rootTaskBounds == null || rootTaskBounds.isEmpty() || rootTaskBounds.contains(bounds)) {
            return;
        }

        // For each side of the parent (eg. left), check if the opposing side of the window (eg.
        // right) is at least overlap pixels away. If less, offset the window by that difference.
        int horizontalDiff = 0;
        // If window is smaller than overlap, use it's smallest dimension instead
        int overlapLR = Math.min(overlapPxX, bounds.width());
        if (bounds.right < (rootTaskBounds.left + overlapLR)) {
            horizontalDiff = overlapLR - (bounds.right - rootTaskBounds.left);
        } else if (bounds.left > (rootTaskBounds.right - overlapLR)) {
            horizontalDiff = -(overlapLR - (rootTaskBounds.right - bounds.left));
        }
        int verticalDiff = 0;
        int overlapTB = Math.min(overlapPxY, bounds.width());
        if (bounds.bottom < (rootTaskBounds.top + overlapTB)) {
            verticalDiff = overlapTB - (bounds.bottom - rootTaskBounds.top);
        } else if (bounds.top > (rootTaskBounds.bottom - overlapTB)) {
            verticalDiff = -(overlapTB - (rootTaskBounds.bottom - bounds.top));
        }
        bounds.offset(horizontalDiff, verticalDiff);
    }

    private boolean shouldStartChangeTransition(int prevWinMode, @NonNull Rect prevBounds) {
        if (!(isLeafTask() || mCreatedByOrganizer) || !canStartChangeTransition()) {
            return false;
        }
        final int newWinMode = getWindowingMode();
        if (mTransitionController.inTransition(this)) {
            final Rect newBounds = getConfiguration().windowConfiguration.getBounds();
            return prevWinMode != newWinMode || prevBounds.width() != newBounds.width()
                    || prevBounds.height() != newBounds.height();
        }
        // Only do an animation into and out-of freeform mode for now. Other mode
        // transition animations are currently handled by system-ui.
        return (prevWinMode == WINDOWING_MODE_FREEFORM) != (newWinMode == WINDOWING_MODE_FREEFORM);
    }

    @Override
    void migrateToNewSurfaceControl(SurfaceControl.Transaction t) {
        super.migrateToNewSurfaceControl(t);
        mLastSurfaceSize.x = 0;
        mLastSurfaceSize.y = 0;
        updateSurfaceSize(t);
    }

    void updateSurfaceSize(SurfaceControl.Transaction transaction) {
        if (mSurfaceControl == null || isOrganized()) {
            return;
        }

        // Apply crop to root tasks only and clear the crops of the descendant tasks.
        int width = 0;
        int height = 0;
        if (isRootTask()) {
            final Rect taskBounds = getBounds();
            width = taskBounds.width();
            height = taskBounds.height();
        }
        if (width == mLastSurfaceSize.x && height == mLastSurfaceSize.y) {
            return;
        }
        transaction.setWindowCrop(mSurfaceControl, width, height);
        mLastSurfaceSize.set(width, height);
    }

    @VisibleForTesting
    Point getLastSurfaceSize() {
        return mLastSurfaceSize;
    }

    @VisibleForTesting
    boolean isInChangeTransition() {
        return mSurfaceFreezer.hasLeash() || AppTransition.isChangeTransitOld(mTransit);
    }

    @Override
    public SurfaceControl getFreezeSnapshotTarget() {
        if (!mDisplayContent.mAppTransition.containsTransitRequest(TRANSIT_CHANGE)) {
            return null;
        }
        // Skip creating snapshot if this transition is controlled by a remote animator which
        // doesn't need it.
        final ArraySet<Integer> activityTypes = new ArraySet<>();
        activityTypes.add(getActivityType());
        final RemoteAnimationAdapter adapter =
                mDisplayContent.mAppTransitionController.getRemoteAnimationOverride(
                        this, TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE, activityTypes);
        if (adapter != null && !adapter.getChangeNeedsSnapshot()) {
            return null;
        }
        return getSurfaceControl();
    }

    @Override
    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, mUserId);
        proto.write(TITLE, intent != null && intent.getComponent() != null
                ? intent.getComponent().flattenToShortString() : "Task");
        proto.end(token);
    }

    /**
     * Saves launching state if necessary so that we can launch the activity to its latest state.
     */
    private void saveLaunchingStateIfNeeded() {
        saveLaunchingStateIfNeeded(getDisplayContent());
    }

    private void saveLaunchingStateIfNeeded(DisplayContent display) {
        if (!isLeafTask()) {
            return;
        }

        if (!getHasBeenVisible()) {
            // Not ever visible to user.
            return;
        }

        final int windowingMode = getWindowingMode();
        if (windowingMode != WINDOWING_MODE_FULLSCREEN
                && windowingMode != WINDOWING_MODE_FREEFORM) {
            return;
        }

        // Don't persist state if Task Display Area isn't in freeform mode. Then the task will be
        // launched back to its last state in a freeform Task Display Area when it's launched in a
        // freeform Task Display Area next time.
        if (getTaskDisplayArea() == null
                || getTaskDisplayArea().getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return;
        }

        // Saves the new state so that we can launch the activity at the same location.
        mTaskSupervisor.mLaunchParamsPersister.saveTask(this, display);
    }

    Rect updateOverrideConfigurationFromLaunchBounds() {
        // If the task is controlled by another organized task, do not set override
        // configurations and let its parent (organized task) to control it;
        final Task rootTask = getRootTask();
        final Rect bounds = rootTask != this && rootTask.isOrganized() ? null : getLaunchBounds();
        setBounds(bounds);
        if (bounds != null && !bounds.isEmpty()) {
            // TODO: Review if we actually want to do this - we are setting the launch bounds
            // directly here.
            bounds.set(getRequestedOverrideBounds());
        }
        return bounds;
    }

    /** Returns the bounds that should be used to launch this task. */
    Rect getLaunchBounds() {
        final Task rootTask = getRootTask();
        if (rootTask == null) {
            return null;
        }

        final int windowingMode = getWindowingMode();
        if (!isActivityTypeStandardOrUndefined()
                || windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return isResizeable() ? rootTask.getRequestedOverrideBounds() : null;
        } else if (!getWindowConfiguration().persistTaskBounds()) {
            return rootTask.getRequestedOverrideBounds();
        }
        return mLastNonFullscreenBounds;
    }

    void setRootProcess(WindowProcessController proc) {
        clearRootProcess();
        if (intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0) {
            mRootProcess = proc;
            mRootProcess.addRecentTask(this);
        }
    }

    void clearRootProcess() {
        if (mRootProcess != null) {
            mRootProcess.removeRecentTask(this);
            mRootProcess = null;
        }
    }

    /** @return Id of root task. */
    int getRootTaskId() {
        return getRootTask().mTaskId;
    }

    /** @return the first organized task. */
    @Nullable
    Task getOrganizedTask() {
        if (isOrganized()) {
            return this;
        }
        final WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }
        final Task parentTask = parent.asTask();
        return parentTask == null ? null : parentTask.getOrganizedTask();
    }

    /** @return the first create-by-organizer task. */
    @Nullable
    Task getCreatedByOrganizerTask() {
        if (mCreatedByOrganizer) {
            return this;
        }
        final WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }
        final Task parentTask = parent.asTask();
        return parentTask == null ? null : parentTask.getCreatedByOrganizerTask();
    }

    /** @return the first adjacent task of this task or its parent. */
    @Nullable
    Task getAdjacentTask() {
        final TaskFragment adjacentTaskFragment = getAdjacentTaskFragment();
        if (adjacentTaskFragment != null && adjacentTaskFragment.asTask() != null) {
            return adjacentTaskFragment.asTask();
        }

        final WindowContainer parent = getParent();
        if (parent == null || parent.asTask() == null) {
            return null;
        }

        return parent.asTask().getAdjacentTask();
    }

    // TODO(task-merge): Figure out what's the right thing to do for places that used it.
    boolean isRootTask() {
        return getRootTask() == this;
    }

    boolean isLeafTask() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asTask() != null) {
                return false;
            }
        }
        return true;
    }

    /** Return the top-most leaf-task under this one, or this task if it is a leaf. */
    public Task getTopLeafTask() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task child = mChildren.get(i).asTask();
            if (child == null) continue;
            return child.getTopLeafTask();
        }
        return this;
    }

    int getDescendantTaskCount() {
        final int[] currentCount = {0};
        forAllLeafTasks(t -> currentCount[0]++, false /* traverseTopToBottom */);
        return currentCount[0];
    }

    /**
     * Find next proper focusable root task and make it focused.
     * @return The root task that now got the focus, {@code null} if none found.
     */
    Task adjustFocusToNextFocusableTask(String reason) {
        return adjustFocusToNextFocusableTask(reason, false /* allowFocusSelf */,
                true /* moveDisplayToTop */);
    }

    /** Return the next focusable task by looking from the siblings and parent tasks */
    private Task getNextFocusableTask(boolean allowFocusSelf) {
        final WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }

        final Task focusableTask = parent.getTask((task) -> (allowFocusSelf || task != this)
                && ((Task) task).isFocusableAndVisible());
        if (focusableTask == null && parent.asTask() != null) {
            return parent.asTask().getNextFocusableTask(allowFocusSelf);
        } else {
            return focusableTask;
        }
    }

    /**
     * Find next proper focusable task and make it focused.
     * @param reason The reason of making the adjustment.
     * @param allowFocusSelf Is the focus allowed to remain on the same task.
     * @param moveDisplayToTop Whether to move display to top while making the task focused.
     * @return The root task that now got the focus, {@code null} if none found.
     */
    Task adjustFocusToNextFocusableTask(String reason, boolean allowFocusSelf,
            boolean moveDisplayToTop) {
        Task focusableTask = getNextFocusableTask(allowFocusSelf);
        if (focusableTask == null) {
            focusableTask = mRootWindowContainer.getNextFocusableRootTask(this, !allowFocusSelf);
        }
        if (focusableTask == null) {
            final TaskDisplayArea taskDisplayArea = getDisplayArea();
            if (taskDisplayArea != null) {
                // Clear the recorded task since there is no next focusable task.
                taskDisplayArea.clearPreferredTopFocusableRootTask();
            }
            return null;
        }

        final Task rootTask = focusableTask.getRootTask();
        if (!moveDisplayToTop) {
            // There may be multiple task layers above this task, so when relocating the task to the
            // top, we should move this task and each of its parent task that below display area to
            // the top of each layer.
            WindowContainer parent = focusableTask.getParent();
            WindowContainer next = focusableTask;
            do {
                parent.positionChildAt(POSITION_TOP, next, false /* includingParents */);
                next = parent;
                parent = next.getParent();
            } while (next.asTask() != null && parent != null);
            return rootTask;
        }

        final String myReason = reason + " adjustFocusToNextFocusableTask";
        final ActivityRecord top = focusableTask.topRunningActivity();
        if (focusableTask.isActivityTypeHome() && (top == null || !top.isVisibleRequested())) {
            // If we will be focusing on the root home task next and its current top activity isn't
            // visible, then use the move the root home task to top to make the activity visible.
            focusableTask.getDisplayArea().moveHomeActivityToTop(myReason);
            return rootTask;
        }

        // Move the entire hierarchy to top with updating global top resumed activity
        // and focused application if needed.
        focusableTask.moveToFront(myReason);
        // Top display focused root task is changed, update top resumed activity if needed.
        if (rootTask.getTopResumedActivity() != null) {
            mTaskSupervisor.updateTopResumedActivityIfNeeded(reason);
        }
        return rootTask;
    }

    /** Calculate the minimum possible position for a task that can be shown to the user.
     *  The minimum position will be above all other tasks that can't be shown.
     *  @param minPosition The minimum position the caller is suggesting.
     *                  We will start adjusting up from here.
     *  @param size The size of the current task list.
     */
    // TODO: Move user to their own window container.
    private int computeMinUserPosition(int minPosition, int size) {
        while (minPosition < size) {
            final WindowContainer child = mChildren.get(minPosition);
            final boolean canShow = child.showToCurrentUser();
            if (canShow) {
                break;
            }
            minPosition++;
        }
        return minPosition;
    }

    /** Calculate the maximum possible position for a task that can't be shown to the user.
     *  The maximum position will be below all other tasks that can be shown.
     *  @param maxPosition The maximum position the caller is suggesting.
     *                  We will start adjusting down from here.
     */
    // TODO: Move user to their own window container.
    private int computeMaxUserPosition(int maxPosition) {
        while (maxPosition > 0) {
            final WindowContainer child = mChildren.get(maxPosition);
            final boolean canShow = child.showToCurrentUser();
            if (!canShow) {
                break;
            }
            maxPosition--;
        }
        return maxPosition;
    }

    private int getAdjustedChildPosition(WindowContainer wc, int suggestedPosition) {
        final boolean canShowChild = wc.showToCurrentUser();

        final int size = mChildren.size();

        // Figure-out min/max possible position depending on if child can show for current user.
        int minPosition = (canShowChild) ? computeMinUserPosition(0, size) : 0;
        int maxPosition = minPosition;
        if (size > 0) {
            maxPosition = (canShowChild) ? size - 1 : computeMaxUserPosition(size - 1);
        }

        // Factor in always-on-top children in max possible position.
        if (!wc.isAlwaysOnTop()) {
            // We want to place all non-always-on-top containers below always-on-top ones.
            while (maxPosition > minPosition) {
                if (!mChildren.get(maxPosition).isAlwaysOnTop()) break;
                --maxPosition;
            }
        }

        // preserve POSITION_BOTTOM/POSITION_TOP positions if they are still valid.
        if (suggestedPosition == POSITION_BOTTOM && minPosition == 0) {
            return POSITION_BOTTOM;
        } else if (suggestedPosition == POSITION_TOP && maxPosition >= (size - 1)) {
            return POSITION_TOP;
        }

        // Increase the maxPosition because children size will grow once wc is added.
        if (!hasChild(wc)) {
            ++maxPosition;
        }

        // Reset position based on minimum/maximum possible positions.
        return Math.min(Math.max(suggestedPosition, minPosition), maxPosition);
    }

    @Override
    void positionChildAt(int position, WindowContainer child, boolean includingParents) {
        final boolean toTop = position >= (mChildren.size() - 1);
        position = getAdjustedChildPosition(child, position);
        super.positionChildAt(position, child, includingParents);

        // Log positioning.
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM, "positionChildAt: child=" + child
                + " position=" + position + " parent=" + this);

        final Task task = child.asTask();
        if (task != null) {
            task.updateTaskMovement(toTop, position == POSITION_BOTTOM, position);
        }
    }

    @Override
    void removeImmediately() {
        removeImmediately("removeTask");
    }

    @Override
    void removeImmediately(String reason) {
        if (DEBUG_ROOT_TASK) Slog.i(TAG, "removeTask:" + reason + " removing taskId=" + mTaskId);
        if (mRemoving) {
            return;
        }
        mRemoving = true;

        EventLogTags.writeWmTaskRemoved(mTaskId, getRootTaskId(), getDisplayId(), reason);
        clearPinnedTaskIfNeed();
        if (mChildPipActivity != null) {
            mChildPipActivity.clearLastParentBeforePip();
        }
        // If applicable let the TaskOrganizer know the Task is vanishing.
        setTaskOrganizer(null);
        if (mDecorSurfaceContainer != null) {
            mDecorSurfaceContainer.release();
        }

        super.removeImmediately();
        mRemoving = false;
    }

    // TODO: Consolidate this with Task.reparent()
    void reparent(Task rootTask, int position, boolean moveParents, String reason) {
        if (DEBUG_ROOT_TASK) Slog.i(TAG, "reParentTask: removing taskId=" + mTaskId
                + " from rootTask=" + getRootTask());
        EventLogTags.writeWmTaskRemoved(mTaskId, getRootTaskId(), getDisplayId(),
                "reParentTask:" + reason);

        reparent(rootTask, position);

        rootTask.positionChildAt(position, this, moveParents);
    }

    public int setBounds(Rect bounds, boolean forceResize) {
        final int boundsChanged = setBounds(bounds);

        if (forceResize && (boundsChanged & BOUNDS_CHANGE_SIZE) != BOUNDS_CHANGE_SIZE) {
            onResize();
            return BOUNDS_CHANGE_SIZE | boundsChanged;
        }

        return boundsChanged;
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    @Override
    public int setBounds(Rect bounds) {
        if (isRootTask()) {
            return setBounds(getRequestedOverrideBounds(), bounds);
        }

        final int boundsChange = super.setBounds(bounds);
        updateSurfacePositionNonOrganized();
        return boundsChange;
    }

    /** Sets the requested bounds regardless of the windowing mode. */
    int setBoundsUnchecked(@NonNull Rect bounds) {
        final int boundsChange = super.setBounds(bounds);
        updateSurfaceBounds();
        return boundsChange;
    }

    @Override
    public boolean isCompatible(int windowingMode, int activityType) {
        // TODO: Should we just move this to ConfigurationContainer?
        if (activityType == ACTIVITY_TYPE_UNDEFINED) {
            // Undefined activity types end up in a standard root task once the root task is
            // created on a display, so they should be considered compatible.
            activityType = ACTIVITY_TYPE_STANDARD;
        }
        return super.isCompatible(windowingMode, activityType);
    }

    @Override
    public boolean onDescendantOrientationChanged(WindowContainer requestingContainer) {
        if (super.onDescendantOrientationChanged(requestingContainer)) {
            return true;
        }

        // No one in higher hierarchy handles this request, let's adjust our bounds to fulfill
        // it if possible.
        if (getParent() != null) {
            onConfigurationChanged(getParent().getConfiguration());
            return true;
        }
        return false;
    }

    @Override
    boolean handlesOrientationChangeFromDescendant(@ScreenOrientation int orientation) {
        if (!super.handlesOrientationChangeFromDescendant(orientation)) {
            return false;
        }

        // At task level, we want to check canSpecifyOrientation() based on the top activity type.
        // Do this only on leaf Task, so that the result is not affecting by the sibling leaf Task.
        // Otherwise, root Task will use the result from the top leaf Task, and all its child
        // leaf Tasks will rely on that from super.handlesOrientationChangeFromDescendant().
        if (!isLeafTask()) {
            return true;
        }

        // Check for leaf Task.
        // Display won't rotate for the orientation request if the Task/TaskDisplayArea
        // can't specify orientation.
        return canSpecifyOrientation() && getDisplayArea().canSpecifyOrientation(orientation);
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        if (isLeafTask()) {
            final int displayId = (dc != null) ? dc.getDisplayId() : INVALID_DISPLAY;
            mWmService.mAtmService.getTaskChangeNotificationController().notifyTaskDisplayChanged(
                    mTaskId, displayId);
        }
        if (isRootTask()) {
            updateSurfaceBounds();
        }
        sendTaskFragmentParentInfoChangedIfNeeded();
    }

    boolean isResizeable() {
        return isResizeable(/* checkPictureInPictureSupport */ true);
    }

    boolean isResizeable(boolean checkPictureInPictureSupport) {
        final boolean forceResizable = mAtmService.mForceResizableActivities
                && getActivityType() == ACTIVITY_TYPE_STANDARD;
        return forceResizable || ActivityInfo.isResizeableMode(mResizeMode)
                || (mSupportsPictureInPicture && checkPictureInPictureSupport);
    }

    /**
     * Tests if the orientation should be preserved upon user interactive resizig operations.

     * @return true if orientation should not get changed upon resizing operation.
     */
    boolean preserveOrientationOnResize() {
        return mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    boolean cropWindowsToRootTaskBounds() {
        // Don't crop HOME/RECENTS windows to root task bounds. This is because in split-screen
        // they extend past their root task and sysui uses the root task surface to control
        // cropping.
        // TODO(b/158242495): get rid of this when drag/drop can use surface bounds.
        if (isActivityTypeHomeOrRecents()) {
            // Make sure this is the top-most non-organizer root task (if not top-most, it means
            // another translucent task could be above this, so this needs to stay cropped.
            final Task rootTask = getRootTask();
            final Task topNonOrgTask =
                    rootTask.mCreatedByOrganizer ? rootTask.getTopMostTask() : rootTask;
            if (this == topNonOrgTask || isDescendantOf(topNonOrgTask)) {
                return false;
            }
        }
        return isResizeable();
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        // If this task has its adjacent task, it means they should animate together. Use display
        // bounds for them could move same as full screen task.
        if (getAdjacentTask() != null) {
            super.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
            return;
        }

        final WindowState windowState = getTopVisibleAppMainWindow();
        if (windowState != null) {
            windowState.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        } else {
            super.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        }
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out the union of visible bounds.
     */
    private static void getMaxVisibleBounds(ActivityRecord token, Rect out, boolean[] foundTop) {
        // skip hidden (or about to hide) apps
        if (token.mIsExiting || !token.isClientVisible() || !token.isVisibleRequested()) {
            return;
        }
        final WindowState win = token.findMainWindow();
        if (win == null) {
            return;
        }
        if (!foundTop[0]) {
            foundTop[0] = true;
            out.setEmpty();
        }

        final Rect visibleFrame = sTmpBounds;
        final WindowManager.LayoutParams attrs = win.mAttrs;
        visibleFrame.set(win.getFrame());
        visibleFrame.inset(win.getInsetsStateWithVisibilityOverride().calculateVisibleInsets(
                visibleFrame, attrs.type, win.getActivityType(), attrs.softInputMode,
                attrs.flags));
        out.union(visibleFrame);
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    @Override
    void getDimBounds(@NonNull Rect out) {
        if (isRootTask()) {
            getBounds(out);
            return;
        }

        final Task rootTask = getRootTask();
        if (inFreeformWindowingMode()) {
            boolean[] foundTop = { false };
            forAllActivities(a -> { getMaxVisibleBounds(a, out, foundTop); });
            if (foundTop[0]) {
                return;
            }
        }

        if (!matchParentBounds()) {
            // When minimizing the root docked task when going home, we don't adjust the task bounds
            // so we need to intersect the task bounds with the root task bounds here..
            rootTask.getBounds(mTmpRect);
            mTmpRect.intersect(getBounds());
            out.set(mTmpRect);
        } else {
            out.set(getBounds());
        }
        return;
    }

    void setDragResizing(boolean dragResizing) {
        if (mDragResizing != dragResizing) {
            // No need to check if allowed if it's leaving dragResize
            if (dragResizing
                    && !(getRootTask().getWindowConfiguration().canResizeTask())) {
                Slog.e(TAG, "Drag resize isn't allowed for root task id=" + getRootTaskId());
                return;
            }
            mDragResizing = dragResizing;
            resetDragResizingChangeReported();
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    /** Cancels any running app transitions associated with the task. */
    void cancelTaskWindowTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).cancelAnimation();
        }
    }

    boolean showForAllUsers() {
        if (mChildren.isEmpty()) return false;
        final ActivityRecord r = getTopNonFinishingActivity();
        return r != null && r.mShowForAllUsers;
    }

    @Override
    boolean showToCurrentUser() {
        return mForceShowForAllUsers || showForAllUsers()
                || mWmService.isUserVisible(getTopMostTask().mUserId);
    }

    void setForceShowForAllUsers(boolean forceShowForAllUsers) {
        mForceShowForAllUsers = forceShowForAllUsers;
    }

    /** Returns the top-most activity that occludes the given one, or {@code null} if none. */
    @Nullable
    ActivityRecord getOccludingActivityAbove(ActivityRecord activity) {
        final ActivityRecord top = getActivity(r -> {
            if (r == activity) {
                // Reached the given activity, return the activity to stop searching.
                return true;
            }

            if (!r.occludesParent()) {
                return false;
            }

            TaskFragment parent = r.getTaskFragment();
            if (parent == activity.getTaskFragment()) {
                // Found it. This activity on top of the given activity on the same TaskFragment.
                return true;
            }
            if (parent != null && parent.asTask() != null) {
                // Found it. This activity is the direct child of a leaf Task.
                return true;
            }
            // The candidate activity is being embedded. Checking if the bounds of the containing
            // TaskFragment equals to the outer TaskFragment.
            TaskFragment grandParent = parent.getParent().asTaskFragment();
            while (grandParent != null) {
                if (!parent.getBounds().equals(grandParent.getBounds())) {
                    // Not occluding the grandparent.
                    break;
                }
                if (grandParent.asTask() != null) {
                    // Found it. The activity occludes its parent TaskFragment and the parent
                    // TaskFragment also occludes its parent all the way up.
                    return true;
                }
                parent = grandParent;
                grandParent = parent.getParent().asTaskFragment();
            }
            return false;
        });
        return top != activity ? top : null;
    }

    @Override
    public SurfaceControl.Builder makeAnimationLeash() {
        return super.makeAnimationLeash().setMetadata(METADATA_TASK_ID, mTaskId);
    }

    boolean shouldAnimate() {
        /**
         * Animations are handled by the TaskOrganizer implementation.
         */
        if (isOrganized()) {
            return false;
        }
        // Don't animate while the task runs recents animation but only if we are in the mode
        // where we cancel with deferred screenshot, which means that the controller has
        // transformed the task.
        final RecentsAnimationController controller = mWmService.getRecentsAnimationController();
        if (controller != null && controller.isAnimatingTask(this)
                && controller.shouldDeferCancelUntilNextTransition()) {
            return false;
        }
        return true;
    }

    @Override
    void setInitialSurfaceControlProperties(SurfaceControl.Builder b) {
        b.setEffectLayer().setMetadata(METADATA_TASK_ID, mTaskId);
        super.setInitialSurfaceControlProperties(b);
    }

    /** Checking if self or its child tasks are animated by recents animation. */
    boolean isAnimatingByRecents() {
        return isAnimating(CHILDREN, ANIMATION_TYPE_RECENTS)
                || mTransitionController.isTransientHide(this);
    }

    WindowState getTopVisibleAppMainWindow() {
        final ActivityRecord activity = getTopVisibleActivity();
        return activity != null ? activity.findMainWindow() : null;
    }

    ActivityRecord topRunningNonDelayedActivityLocked(ActivityRecord notTop) {
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::isTopRunningNonDelayed
                , PooledLambda.__(ActivityRecord.class), notTop);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isTopRunningNonDelayed(ActivityRecord r, ActivityRecord notTop) {
        return !r.delayedResume && r != notTop && r.canBeTopRunning();
    }

    /**
     * This is a simplified version of topRunningActivity that provides a number of
     * optional skip-over modes.  It is intended for use with the ActivityController hook only.
     *
     * @param token If non-null, any history records matching this token will be skipped.
     * @param taskId If non-zero, we'll attempt to skip over records with the same task ID.
     *
     * @return Returns the HistoryRecord of the next activity on the root task.
     */
    ActivityRecord topRunningActivity(IBinder token, int taskId) {
        final PooledPredicate p = PooledLambda.obtainPredicate(Task::isTopRunning,
                PooledLambda.__(ActivityRecord.class), taskId, token);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isTopRunning(ActivityRecord r, int taskId, IBinder notTop) {
        return r.getTask().mTaskId != taskId && r.token != notTop && r.canBeTopRunning();
    }

    ActivityRecord getTopFullscreenActivity() {
        return getActivity((r) -> {
            final WindowState win = r.findMainWindow();
            return (win != null && win.mAttrs.isFullscreen());
        });
    }

    /**
     * Return the top visible requested activity. The activity has been requested to be visible,
     * but it's possible that the activity has just been created, so no window is yet attached to
     * this activity.
     */
    ActivityRecord getTopVisibleActivity() {
        return getActivity((r) -> !r.mIsExiting && r.isClientVisible() && r.isVisibleRequested());
    }

    /**
     * Return the top visible activity. The activity has a window on which contents are drawn.
     * However it's possible that the activity has already been requested to be invisible, but the
     * visibility is not yet committed.
     */
    ActivityRecord getTopRealVisibleActivity() {
        return getActivity((r) -> !r.mIsExiting && r.isClientVisible() && r.isVisible());
    }

    ActivityRecord getTopWaitSplashScreenActivity() {
        return getActivity((r) -> {
            return r.mHandleExitSplashScreen
                    && r.mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_COPYING;
        });
    }

    void setTaskDescription(TaskDescription taskDescription) {
        mTaskDescription = taskDescription;
    }

    void onSnapshotChanged(TaskSnapshot snapshot) {
        mLastTaskSnapshotData.set(snapshot);
        mAtmService.getTaskChangeNotificationController().notifyTaskSnapshotChanged(
                mTaskId, snapshot);
    }

    void onSnapshotInvalidated() {
        mAtmService.getTaskChangeNotificationController().notifyTaskSnapshotInvalidated(mTaskId);
    }


    TaskDescription getTaskDescription() {
        return mTaskDescription;
    }

    @Override
    void forAllLeafTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        boolean isLeafTask = true;
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                final Task child = mChildren.get(i).asTask();
                if (child != null) {
                    isLeafTask = false;
                    child.forAllLeafTasks(callback, traverseTopToBottom);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final Task child = mChildren.get(i).asTask();
                if (child != null) {
                    isLeafTask = false;
                    child.forAllLeafTasks(callback, traverseTopToBottom);
                }
            }
        }
        if (isLeafTask) callback.accept(this);
    }

    @Override
    void forAllTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        super.forAllTasks(callback, traverseTopToBottom);
        callback.accept(this);
    }

    @Override
    void forAllRootTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        if (isRootTask()) {
            callback.accept(this);
        }
    }

    @Override
    boolean forAllTasks(Predicate<Task> callback) {
        if (super.forAllTasks(callback)) return true;
        return callback.test(this);
    }

    @Override
    boolean forAllLeafTasks(Predicate<Task> callback) {
        boolean isLeafTask = true;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task child = mChildren.get(i).asTask();
            if (child != null) {
                isLeafTask = false;
                if (child.forAllLeafTasks(callback)) {
                    return true;
                }
            }
        }
        if (isLeafTask) {
            return callback.test(this);
        }
        return false;
    }

    /** Iterates through all leaf task fragments and the leaf tasks. */
    void forAllLeafTasksAndLeafTaskFragments(final Consumer<TaskFragment> callback,
            boolean traverseTopToBottom) {
        forAllLeafTasks(task -> {
            if (task.isLeafTaskFragment()) {
                callback.accept(task);
                return;
            }

            // A leaf task that may contains both activities and task fragments.
            boolean consumed = false;
            if (traverseTopToBottom) {
                for (int i = task.mChildren.size() - 1; i >= 0; --i) {
                    final WindowContainer child = task.mChildren.get(i);
                    if (child.asTaskFragment() != null) {
                        child.forAllLeafTaskFragments(callback, traverseTopToBottom);
                    } else if (child.asActivityRecord() != null && !consumed) {
                        callback.accept(task);
                        consumed = true;
                    }
                }
            } else {
                for (int i = 0; i < task.mChildren.size(); i++) {
                    final WindowContainer child = task.mChildren.get(i);
                    if (child.asTaskFragment() != null) {
                        child.forAllLeafTaskFragments(callback, traverseTopToBottom);
                    } else if (child.asActivityRecord() != null && !consumed) {
                        callback.accept(task);
                        consumed = true;
                    }
                }
            }
        }, traverseTopToBottom);
    }

    @Override
    boolean forAllRootTasks(Predicate<Task> callback, boolean traverseTopToBottom) {
        return isRootTask() ? callback.test(this) : false;
    }

    @Override
    Task getTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        final Task t = super.getTask(callback, traverseTopToBottom);
        if (t != null) return t;
        return callback.test(this) ? this : null;
    }

    @Nullable
    @Override
    Task getRootTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        return isRootTask() && callback.test(this) ? this : null;
    }

    /**
     * @param canAffectSystemUiFlags If false, all windows in this task can not affect SystemUI
     *                               flags. See {@link WindowState#canAffectSystemUiFlags()}.
     */
    void setCanAffectSystemUiFlags(boolean canAffectSystemUiFlags) {
        mCanAffectSystemUiFlags = canAffectSystemUiFlags;
    }

    /**
     * @see #setCanAffectSystemUiFlags
     */
    boolean canAffectSystemUiFlags() {
        return mCanAffectSystemUiFlags;
    }

    void dontAnimateDimExit() {
        mDimmer.dontAnimateExit();
    }

    String getName() {
        return "Task=" + mTaskId;
    }

    @Override
    Dimmer getDimmer() {
        // If the window is in multi-window mode, we want to dim at the Task level to ensure the dim
        // bounds match the area the app lives in
        if (inMultiWindowMode()) {
            return mDimmer;
        }

        // If we're not at the root task level, we want to keep traversing through the parents to
        // find the root.
        // Once at the root task level, we want to check {@link #isTranslucent(ActivityRecord)}.
        // If true, we want to get the Dimmer from the level above since we don't want to animate
        // the dim with the Task.
        if (!isRootTask() || (Dimmer.DIMMER_REFACTOR && isTranslucentAndVisible())
                || (Flags.getDimmerOnClosing() ? isTranslucentForTransition()
                                                : isTranslucent(null))) {
            return super.getDimmer();
        }

        return mDimmer;
    }

    @Override
    void prepareSurfaces() {
        mDimmer.resetDimStates();
        super.prepareSurfaces();

        final Rect dimBounds = mDimmer.getDimBounds();
        if (dimBounds != null) {
            getDimBounds(dimBounds);

            // Bounds need to be relative, as the dim layer is a child.
            if (inFreeformWindowingMode()) {
                getBounds(mTmpRect);
                dimBounds.offsetTo(dimBounds.left - mTmpRect.left, dimBounds.top - mTmpRect.top);
            } else {
                dimBounds.offsetTo(0, 0);
            }
        }

        final SurfaceControl.Transaction t = getSyncTransaction();

        if (dimBounds != null && mDimmer.updateDims(t)) {
            scheduleAnimation();
        }

        // Let organizer manage task visibility for shell transition. So don't change it's
        // visibility during collecting.
        if (mTransitionController.isCollecting() && mCreatedByOrganizer) {
            return;
        }

        // We intend to let organizer manage task visibility but it doesn't
        // have enough information until we finish shell transitions.
        // In the mean time we do an easy fix here.
        final boolean visible = isVisible();
        final boolean show = visible || isAnimating(TRANSITION | PARENTS | CHILDREN);
        if (mSurfaceControl != null) {
            if (show != mLastSurfaceShowing) {
                t.setVisibility(mSurfaceControl, show);
            }
        }
        // Only show the overlay if the task has other visible children
        if (mOverlayHost != null) {
            mOverlayHost.setVisibility(t, visible);
        }
        mLastSurfaceShowing = show;
    }

    @Override
    protected void applyAnimationUnchecked(WindowManager.LayoutParams lp, boolean enter,
            @TransitionOldType int transit, boolean isVoiceInteraction,
            @Nullable ArrayList<WindowContainer> sources) {
        final RecentsAnimationController control = mWmService.getRecentsAnimationController();
        if (control != null) {
            // We let the transition to be controlled by RecentsAnimation, and callback task's
            // RemoteAnimationTarget for remote runner to animate.
            if (enter && !isActivityTypeHomeOrRecents()) {
                ProtoLog.d(WM_DEBUG_RECENTS_ANIMATIONS,
                        "applyAnimationUnchecked, control: %s, task: %s, transit: %s",
                        control, asTask(), AppTransition.appTransitionOldToString(transit));
                final int size = sources != null ? sources.size() : 0;
                control.addTaskToTargets(this, (type, anim) -> {
                    for (int i = 0; i < size; ++i) {
                        sources.get(i).onAnimationFinished(type, anim);
                    }
                });
            }
        } else {
            super.applyAnimationUnchecked(lp, enter, transit, isVoiceInteraction, sources);
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        mAnimatingActivityRegistry.dump(pw, "AnimatingApps:", prefix);
    }


    /**
     * Fills in a {@link TaskInfo} with information from this task. Note that the base intent in the
     * task info will not include any extras or clip data.
     */
    void fillTaskInfo(TaskInfo info) {
        fillTaskInfo(info, true /* stripExtras */);
    }

    void fillTaskInfo(TaskInfo info, boolean stripExtras) {
        fillTaskInfo(info, stripExtras, getDisplayArea());
    }

    /**
     * Fills in a {@link TaskInfo} with information from this task.
     *
     * @param tda consider whether this Task can be put in multi window as it will be attached to
     *            the give {@link TaskDisplayArea}.
     */
    void fillTaskInfo(TaskInfo info, boolean stripExtras, @Nullable TaskDisplayArea tda) {
        info.launchCookies.clear();
        info.addLaunchCookie(mLaunchCookie);
        final ActivityRecord top = mTaskSupervisor.mTaskInfoHelper.fillAndReturnTop(this, info);

        info.userId = isLeafTask() ? mUserId : mCurrentUser;
        info.taskId = mTaskId;
        info.displayId = getDisplayId();
        info.displayAreaFeatureId = tda != null ? tda.mFeatureId : FEATURE_UNDEFINED;
        final Intent baseIntent = getBaseIntent();
        // Make a copy of base intent because this is like a snapshot info.
        // Besides, {@link RecentTasks#getRecentTasksImpl} may modify it.
        final int baseIntentFlags = baseIntent == null ? 0 : baseIntent.getFlags();
        info.baseIntent = baseIntent == null
                ? new Intent()
                : stripExtras ? baseIntent.cloneFilter() : new Intent(baseIntent);
        info.baseIntent.setFlags(baseIntentFlags);

        info.isRunning = top != null;
        info.topActivity = top != null ? top.mActivityComponent : null;
        info.origActivity = origActivity;
        info.realActivity = realActivity;
        info.lastActiveTime = lastActiveTime;
        info.taskDescription = new ActivityManager.TaskDescription(getTaskDescription());
        info.supportsMultiWindow = supportsMultiWindowInDisplayArea(tda);
        info.configuration.setTo(getConfiguration());
        // Update to the task's current activity type and windowing mode which may differ from the
        // window configuration
        info.configuration.windowConfiguration.setActivityType(getActivityType());
        info.configuration.windowConfiguration.setWindowingMode(getWindowingMode());
        info.token = mRemoteToken.toWindowContainerToken();

        //TODO (AM refactor): Just use local once updateEffectiveIntent is run during all child
        //                    order changes.
        final Task topTask = top != null ? top.getTask() : this;
        info.resizeMode = topTask.mResizeMode;
        info.topActivityType = topTask.getActivityType();
        info.displayCutoutInsets = topTask.getDisplayCutoutInsets();
        info.isResizeable = isResizeable();
        info.minWidth = mMinWidth;
        info.minHeight = mMinHeight;
        info.defaultMinSize = mDisplayContent == null
                ? DEFAULT_MIN_TASK_SIZE_DP : mDisplayContent.mMinSizeOfResizeableTaskDp;
        info.positionInParent = getRelativePosition();

        info.topActivityInfo = top != null ? top.info : null;
        info.pictureInPictureParams = getPictureInPictureParams(top);
        info.launchIntoPipHostTaskId = (info.pictureInPictureParams != null
                && info.pictureInPictureParams.isLaunchIntoPip()
                && top.getLastParentBeforePip() != null)
                        ? top.getLastParentBeforePip().mTaskId : INVALID_TASK_ID;
        info.lastParentTaskIdBeforePip = top != null && top.getLastParentBeforePip() != null
                ? top.getLastParentBeforePip().mTaskId : INVALID_TASK_ID;
        info.shouldDockBigOverlays = top != null && top.shouldDockBigOverlays;
        info.mTopActivityLocusId = top != null ? top.getLocusId() : null;

        final boolean isTopActivityResumed = top != null
                && top.getOrganizedTask() == this && top.isState(RESUMED);
        final boolean isTopActivityVisible = top != null
                && top.getOrganizedTask() == this && top.isVisible();
        final AppCompatTaskInfo appCompatTaskInfo = info.appCompatTaskInfo;
        // Whether the direct top activity is in size compat mode
        appCompatTaskInfo.topActivityInSizeCompat = isTopActivityVisible && top.inSizeCompatMode();
        if (appCompatTaskInfo.topActivityInSizeCompat
                && mWmService.mLetterboxConfiguration.isTranslucentLetterboxingEnabled()) {
            // We hide the restart button in case of transparent activities.
            appCompatTaskInfo.topActivityInSizeCompat = top.fillsParent();
        }
        // Whether the direct top activity is eligible for letterbox education.
        appCompatTaskInfo.topActivityEligibleForLetterboxEducation = isTopActivityResumed
                && top.isEligibleForLetterboxEducation();
        appCompatTaskInfo.isLetterboxEducationEnabled = top != null
                && top.mLetterboxUiController.isLetterboxEducationEnabled();
        // Whether the direct top activity requested showing camera compat control.
        appCompatTaskInfo.cameraCompatTaskInfo.cameraCompatControlState = isTopActivityResumed
                ? top.getCameraCompatControlState()
                : CameraCompatTaskInfo.CAMERA_COMPAT_CONTROL_HIDDEN;

        final Task parentTask = getParent() != null ? getParent().asTask() : null;
        info.parentTaskId = parentTask != null && parentTask.mCreatedByOrganizer
                ? parentTask.mTaskId
                : INVALID_TASK_ID;
        info.isFocused = isFocused();
        info.isVisible = hasVisibleChildren();
        info.isVisibleRequested = isVisibleRequested();
        info.isSleeping = shouldSleepActivities();
        info.isTopActivityTransparent = top != null && !top.fillsParent();
        appCompatTaskInfo.topActivityLetterboxVerticalPosition = TaskInfo.PROPERTY_VALUE_UNSET;
        appCompatTaskInfo.topActivityLetterboxHorizontalPosition = TaskInfo.PROPERTY_VALUE_UNSET;
        appCompatTaskInfo.topActivityLetterboxWidth = TaskInfo.PROPERTY_VALUE_UNSET;
        appCompatTaskInfo.topActivityLetterboxHeight = TaskInfo.PROPERTY_VALUE_UNSET;
        appCompatTaskInfo.isUserFullscreenOverrideEnabled = top != null
                && top.mLetterboxUiController.shouldApplyUserFullscreenOverride();
        appCompatTaskInfo.isSystemFullscreenOverrideEnabled = top != null
                && top.mLetterboxUiController.isSystemOverrideToFullscreenEnabled();
        appCompatTaskInfo.isFromLetterboxDoubleTap = top != null
                && top.mLetterboxUiController.isFromDoubleTap();
        if (top != null) {
            appCompatTaskInfo.topActivityLetterboxWidth = top.getBounds().width();
            appCompatTaskInfo.topActivityLetterboxHeight = top.getBounds().height();
        }
        // We need to consider if letterboxed or pillarboxed
        // TODO(b/336807329) Encapsulate reachability logic
        appCompatTaskInfo.isLetterboxDoubleTapEnabled = top != null
                && top.mLetterboxUiController.isLetterboxDoubleTapEducationEnabled();
        if (appCompatTaskInfo.isLetterboxDoubleTapEnabled) {
            if (appCompatTaskInfo.isTopActivityPillarboxed()) {
                if (top.mLetterboxUiController.allowHorizontalReachabilityForThinLetterbox()) {
                    // Pillarboxed
                    appCompatTaskInfo.topActivityLetterboxHorizontalPosition =
                            top.mLetterboxUiController
                                    .getLetterboxPositionForHorizontalReachability();
                } else {
                    appCompatTaskInfo.isLetterboxDoubleTapEnabled = false;
                }
            } else {
                if (top.mLetterboxUiController.allowVerticalReachabilityForThinLetterbox()) {
                    // Letterboxed
                    appCompatTaskInfo.topActivityLetterboxVerticalPosition =
                            top.mLetterboxUiController
                                    .getLetterboxPositionForVerticalReachability();
                } else {
                    appCompatTaskInfo.isLetterboxDoubleTapEnabled = false;
                }
            }
        }
        appCompatTaskInfo.topActivityEligibleForUserAspectRatioButton = top != null
                && !appCompatTaskInfo.topActivityInSizeCompat
                && top.mLetterboxUiController.shouldEnableUserAspectRatioSettings()
                && !info.isTopActivityTransparent;
        appCompatTaskInfo.topActivityBoundsLetterboxed = top != null  && top.areBoundsLetterboxed();
    }

    /**
     * Removes the activity info if the activity belongs to a different uid, which is
     * different from the app that hosts the task.
     */
    static void trimIneffectiveInfo(Task task, TaskInfo info) {
        final ActivityRecord baseActivity = task.getActivity(r -> !r.finishing,
                false /* traverseTopToBottom */);
        final int baseActivityUid =
                baseActivity != null ? baseActivity.getUid() : task.effectiveUid;

        if (info.topActivityInfo != null
                && task.effectiveUid != info.topActivityInfo.applicationInfo.uid) {
            // Making a copy to prevent eliminating the info in the original ActivityRecord.
            info.topActivityInfo = new ActivityInfo(info.topActivityInfo);
            info.topActivityInfo.applicationInfo =
                    new ApplicationInfo(info.topActivityInfo.applicationInfo);

            // Strip the sensitive info.
            info.topActivity = new ComponentName("", "");
            info.topActivityInfo.packageName = "";
            info.topActivityInfo.taskAffinity = "";
            info.topActivityInfo.processName = "";
            info.topActivityInfo.name = "";
            info.topActivityInfo.parentActivityName = "";
            info.topActivityInfo.targetActivity = "";
            info.topActivityInfo.splitName = "";
            info.topActivityInfo.applicationInfo.className = "";
            info.topActivityInfo.applicationInfo.credentialProtectedDataDir = "";
            info.topActivityInfo.applicationInfo.dataDir = "";
            info.topActivityInfo.applicationInfo.deviceProtectedDataDir = "";
            info.topActivityInfo.applicationInfo.manageSpaceActivityName = "";
            info.topActivityInfo.applicationInfo.nativeLibraryDir = "";
            info.topActivityInfo.applicationInfo.nativeLibraryRootDir = "";
            info.topActivityInfo.applicationInfo.processName = "";
            info.topActivityInfo.applicationInfo.publicSourceDir = "";
            info.topActivityInfo.applicationInfo.scanPublicSourceDir = "";
            info.topActivityInfo.applicationInfo.scanSourceDir = "";
            info.topActivityInfo.applicationInfo.sourceDir = "";
            info.topActivityInfo.applicationInfo.taskAffinity = "";
            info.topActivityInfo.applicationInfo.name = "";
            info.topActivityInfo.applicationInfo.packageName = "";
        }

        if (task.effectiveUid != baseActivityUid) {
            info.baseActivity = new ComponentName("", "");
        }
    }

    @Nullable PictureInPictureParams getPictureInPictureParams() {
        final Task topTask = getTopMostTask();
        if (topTask == null) return null;
        return getPictureInPictureParams(topTask.getTopMostActivity());
    }

    private static @Nullable PictureInPictureParams getPictureInPictureParams(ActivityRecord top) {
        return (top == null || top.pictureInPictureArgs.empty())
                ? null : new PictureInPictureParams(top.pictureInPictureArgs);
    }

    Rect getDisplayCutoutInsets() {
        if (mDisplayContent == null || getDisplayInfo().displayCutout == null) return null;
        final WindowState w = getTopVisibleAppMainWindow();
        final int displayCutoutMode = w == null
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                : w.getAttrs().layoutInDisplayCutoutMode;
        return (displayCutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                || displayCutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
                ? null : getDisplayInfo().displayCutout.getSafeInsets();
    }

    /**
     * Returns a {@link TaskInfo} with information from this task.
     */
    ActivityManager.RunningTaskInfo getTaskInfo() {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        fillTaskInfo(info);
        return info;
    }

    /**
     * Returns a {@link StartingWindowInfo} with information from this task and the target activity.
     * @param activity Target activity which to show the starting window.
     */
    StartingWindowInfo getStartingWindowInfo(ActivityRecord activity) {
        final StartingWindowInfo info = new StartingWindowInfo();
        info.taskInfo = getTaskInfo();
        info.targetActivityInfo = info.taskInfo.topActivityInfo != null
                && activity.info != info.taskInfo.topActivityInfo
                ? activity.info : null;
        info.isKeyguardOccluded =
                mAtmService.mKeyguardController.isKeyguardOccluded(info.taskInfo.displayId);

        info.startingWindowTypeParameter = activity.mStartingData != null
                ? activity.mStartingData.mTypeParams
                : (StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED
                        | StartingWindowInfo.TYPE_PARAMETER_WINDOWLESS);
        if ((info.startingWindowTypeParameter
                & StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED) != 0) {
            final WindowState topMainWin = getWindow(w -> w.mAttrs.type == TYPE_BASE_APPLICATION);
            if (topMainWin != null) {
                info.mainWindowLayoutParams = topMainWin.getAttrs();
                info.requestedVisibleTypes = topMainWin.getRequestedVisibleTypes();
            }
        }
        final Rect rotatedBounds = activity.getFixedRotationTransformDisplayBounds();
        info.taskBounds.set(rotatedBounds != null ? rotatedBounds
                : info.taskInfo.configuration.windowConfiguration.getBounds());
        // If the developer has persist a different configuration, we need to override it to the
        // starting window because persisted configuration does not effect to Task.
        info.taskInfo.configuration.setTo(activity.getConfiguration());
        final ActivityRecord topFullscreenActivity = getTopFullscreenActivity();
        if (topFullscreenActivity != null) {
            final WindowState topFullscreenOpaqueWindow =
                    topFullscreenActivity.getTopFullscreenOpaqueWindow();
            if (topFullscreenOpaqueWindow != null) {
                info.topOpaqueWindowInsetsState =
                        topFullscreenOpaqueWindow.getInsetsStateWithVisibilityOverride();
                info.topOpaqueWindowLayoutParams = topFullscreenOpaqueWindow.getAttrs();
            }
        }
        return info;
    }

    /**
     * Returns the {@link TaskFragmentParentInfo} which will send to the client
     * {@link android.window.TaskFragmentOrganizer}
     */
    TaskFragmentParentInfo getTaskFragmentParentInfo() {
        return new TaskFragmentParentInfo(getConfiguration(), getDisplayId(),
                shouldBeVisible(null /* starting */), hasNonFinishingDirectActivity(),
                getDecorSurface());
    }

    @Override
    protected boolean onChildVisibleRequestedChanged(@Nullable WindowContainer child) {
        if (!super.onChildVisibleRequestedChanged(child)) return false;
        sendTaskFragmentParentInfoChangedIfNeeded();
        return true;
    }

    void sendTaskFragmentParentInfoChangedIfNeeded() {
        if (!isLeafTask()) {
            // Only send parent info changed event for leaf task.
            return;
        }
        final TaskFragment childOrganizedTf =
                getTaskFragment(TaskFragment::isOrganizedTaskFragment);
        if (childOrganizedTf != null) {
            childOrganizedTf.sendTaskFragmentParentInfoChanged();
        }
    }

    @Override
    void assignChildLayers(@NonNull SurfaceControl.Transaction t) {
        int layer = 0;
        boolean decorSurfacePlaced = false;

        // We use two passes as a way to promote children which
        // need Z-boosting to the end of the list.
        for (int j = 0; j < mChildren.size(); ++j) {
            final WindowContainer wc = mChildren.get(j);
            wc.assignChildLayers(t);
            if (!wc.needsZBoost()) {
                // Place the decor surface under any untrusted content.
                if (mDecorSurfaceContainer != null
                        && !mDecorSurfaceContainer.mIsBoosted
                        && !decorSurfacePlaced
                        && shouldPlaceDecorSurfaceBelowContainer(wc)) {
                    mDecorSurfaceContainer.assignLayer(t, layer++);
                    decorSurfacePlaced = true;
                }
                wc.assignLayer(t, layer++);

                // Boost the adjacent TaskFragment for dimmer if needed.
                final TaskFragment taskFragment = wc.asTaskFragment();
                if (taskFragment != null && taskFragment.isEmbedded()) {
                    final TaskFragment adjacentTf = taskFragment.getAdjacentTaskFragment();
                    if (adjacentTf != null && adjacentTf.shouldBoostDimmer()) {
                        adjacentTf.assignLayer(t, layer++);
                    }
                }

                // Place the decor surface just above the owner TaskFragment.
                if (mDecorSurfaceContainer != null
                        && !mDecorSurfaceContainer.mIsBoosted
                        && !decorSurfacePlaced
                        && wc == mDecorSurfaceContainer.mOwnerTaskFragment) {
                    mDecorSurfaceContainer.assignLayer(t, layer++);
                    decorSurfacePlaced = true;
                }
            }
        }

        // Boost the decor surface above other non-boosted windows if requested. The cover surface
        // will ensure that the content of the windows below are invisible.
        if (mDecorSurfaceContainer != null && mDecorSurfaceContainer.mIsBoosted) {
            mDecorSurfaceContainer.assignLayer(t, layer++);
        }

        for (int j = 0; j < mChildren.size(); ++j) {
            final WindowContainer wc = mChildren.get(j);
            if (wc.needsZBoost()) {
                wc.assignLayer(t, layer++);
            }
        }
        if (mOverlayHost != null) {
            mOverlayHost.setLayer(t, layer++);
        }
    }

    boolean shouldPlaceDecorSurfaceBelowContainer(@NonNull WindowContainer wc) {
        boolean isOwnActivity =
                wc.asActivityRecord() != null
                        && wc.asActivityRecord().isUid(effectiveUid);
        boolean isTrustedTaskFragment =
                wc.asTaskFragment() != null
                        && wc.asTaskFragment().isEmbedded()
                        && wc.asTaskFragment().isAllowedToBeEmbeddedInTrustedMode();
        return !isOwnActivity && !isTrustedTaskFragment;
    }

    void setDecorSurfaceBoosted(
            @NonNull TaskFragment ownerTaskFragment,
            boolean isBoosted,
            @Nullable SurfaceControl.Transaction clientTransaction) {
        if (mDecorSurfaceContainer == null
                || mDecorSurfaceContainer.mOwnerTaskFragment != ownerTaskFragment) {
            return;
        }
        mDecorSurfaceContainer.setBoosted(isBoosted, clientTransaction);
        // scheduleAnimation() is called inside assignChildLayers(), which ensures that child
        // surface visibility is updated with prepareSurfaces()
        assignChildLayers();
    }

    boolean isDecorSurfaceBoosted() {
        return mDecorSurfaceContainer != null && mDecorSurfaceContainer.mIsBoosted;
    }

    boolean isTaskId(int taskId) {
        return mTaskId == taskId;
    }

    @Override
    Task asTask() {
        // I'm a task!
        return this;
    }

    ActivityRecord isInTask(ActivityRecord r) {
        if (r == null) {
            return null;
        }
        if (r.isDescendantOf(this)) {
            return r;
        }
        return null;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("userId="); pw.print(mUserId);
        pw.print(" effectiveUid="); UserHandle.formatUid(pw, effectiveUid);
        pw.print(" mCallingUid="); UserHandle.formatUid(pw, mCallingUid);
        pw.print(" mUserSetupComplete="); pw.print(mUserSetupComplete);
        pw.print(" mCallingPackage="); pw.print(mCallingPackage);
        pw.print(" mCallingFeatureId="); pw.println(mCallingFeatureId);
        if (affinity != null || rootAffinity != null) {
            pw.print(prefix); pw.print("affinity="); pw.print(affinity);
            if (affinity == null || !affinity.equals(rootAffinity)) {
                pw.print(" root="); pw.println(rootAffinity);
            } else {
                pw.println();
            }
        }
        if (mWindowLayoutAffinity != null) {
            pw.print(prefix); pw.print("windowLayoutAffinity="); pw.println(mWindowLayoutAffinity);
        }
        if (voiceSession != null || voiceInteractor != null) {
            pw.print(prefix); pw.print("VOICE: session=0x");
            pw.print(Integer.toHexString(System.identityHashCode(voiceSession)));
            pw.print(" interactor=0x");
            pw.println(Integer.toHexString(System.identityHashCode(voiceInteractor)));
        }
        if (intent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("intent={");
            intent.toShortString(sb, false, true, false, false);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (affinityIntent != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(prefix); sb.append("affinityIntent={");
            affinityIntent.toShortString(sb, false, true, false, false);
            sb.append('}');
            pw.println(sb.toString());
        }
        if (origActivity != null) {
            pw.print(prefix); pw.print("origActivity=");
            pw.println(origActivity.flattenToShortString());
        }
        if (realActivity != null) {
            pw.print(prefix); pw.print("mActivityComponent=");
            pw.println(realActivity.flattenToShortString());
        }
        if (autoRemoveRecents || isPersistable || !isActivityTypeStandard()) {
            pw.print(prefix); pw.print("autoRemoveRecents="); pw.print(autoRemoveRecents);
            pw.print(" isPersistable="); pw.print(isPersistable);
            pw.print(" activityType="); pw.println(getActivityType());
        }
        if (rootWasReset || mNeverRelinquishIdentity || mReuseTask
                || mLockTaskAuth != LOCK_TASK_AUTH_PINNABLE) {
            pw.print(prefix); pw.print("rootWasReset="); pw.print(rootWasReset);
            pw.print(" mNeverRelinquishIdentity="); pw.print(mNeverRelinquishIdentity);
            pw.print(" mReuseTask="); pw.print(mReuseTask);
            pw.print(" mLockTaskAuth="); pw.println(lockTaskAuthToString());
        }
        if (mAffiliatedTaskId != mTaskId || mPrevAffiliateTaskId != INVALID_TASK_ID
                || mPrevAffiliate != null || mNextAffiliateTaskId != INVALID_TASK_ID
                || mNextAffiliate != null) {
            pw.print(prefix); pw.print("affiliation="); pw.print(mAffiliatedTaskId);
            pw.print(" prevAffiliation="); pw.print(mPrevAffiliateTaskId);
            pw.print(" (");
            if (mPrevAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(mPrevAffiliate)));
            }
            pw.print(") nextAffiliation="); pw.print(mNextAffiliateTaskId);
            pw.print(" (");
            if (mNextAffiliate == null) {
                pw.print("null");
            } else {
                pw.print(Integer.toHexString(System.identityHashCode(mNextAffiliate)));
            }
            pw.println(")");
        }
        pw.print(prefix); pw.print("Activities="); pw.println(mChildren);
        if (!inRecents || !isAvailable) {
            pw.print(prefix);
            pw.print(" inRecents="); pw.print(inRecents);
            pw.print(" isAvailable="); pw.println(isAvailable);
        }
        if (lastDescription != null) {
            pw.print(prefix); pw.print("lastDescription="); pw.println(lastDescription);
        }
        if (mRootProcess != null) {
            pw.print(prefix); pw.print("mRootProcess="); pw.println(mRootProcess);
        }
        if (mSharedStartingData != null) {
            pw.println(prefix + "mSharedStartingData=" + mSharedStartingData);
        }
        if (mKillProcessesOnDestroyed) {
            pw.println(prefix + "mKillProcessesOnDestroyed=true");
        }
        pw.print(prefix); pw.print("taskId=" + mTaskId);
        pw.println(" rootTaskId=" + getRootTaskId());
        pw.print(prefix); pw.println("hasChildPipActivity=" + (mChildPipActivity != null));
        pw.print(prefix); pw.print("mHasBeenVisible="); pw.println(getHasBeenVisible());
        pw.print(prefix); pw.print("mResizeMode=");
        pw.print(ActivityInfo.resizeModeToString(mResizeMode));
        pw.print(" mSupportsPictureInPicture="); pw.print(mSupportsPictureInPicture);
        pw.print(" isResizeable="); pw.println(isResizeable());
        pw.print(prefix); pw.print("lastActiveTime="); pw.print(lastActiveTime);
        pw.println(" (inactive for " + (getInactiveDuration() / 1000) + "s)");
    }

    @Override
    String toFullString() {
        final StringBuilder sb = new StringBuilder(192);
        sb.append(this);
        sb.setLength(sb.length() - 1); // Remove tail '}'.
        sb.append(" U=");
        sb.append(mUserId);
        final Task rootTask = getRootTask();
        if (rootTask != this) {
            sb.append(" rootTaskId=");
            sb.append(rootTask.mTaskId);
        }
        sb.append(" visible=");
        sb.append(shouldBeVisible(null /* starting */));
        sb.append(" visibleRequested=");
        sb.append(isVisibleRequested());
        sb.append(" mode=");
        sb.append(windowingModeToString(getWindowingMode()));
        sb.append(" translucent=");
        sb.append(isTranslucent(null /* starting */));
        sb.append(" sz=");
        sb.append(getChildCount());
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() {
        if (stringName != null) return stringName;
        StringBuilder sb = new StringBuilder(128);
        sb.append("Task{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        sb.append(mTaskId);
        sb.append(" type=" + activityTypeToString(getActivityType()));
        if (affinity != null) {
            sb.append(" A=");
            sb.append(affinity);
        } else if (intent != null && intent.getComponent() != null) {
            sb.append(" I=");
            sb.append(intent.getComponent().flattenToShortString());
        } else if (affinityIntent != null && affinityIntent.getComponent() != null) {
            sb.append(" aI=");
            sb.append(affinityIntent.getComponent().flattenToShortString());
        }
        sb.append('}');
        return stringName = sb.toString();
    }

    /**
     * Saves this {@link Task} to XML using given serializer.
     */
    void saveToXml(TypedXmlSerializer out) throws Exception {
        if (DEBUG_RECENTS) Slog.i(TAG_RECENTS, "Saving task=" + this);

        out.attributeInt(null, ATTR_TASKID, mTaskId);
        if (realActivity != null) {
            out.attribute(null, ATTR_REALACTIVITY, realActivity.flattenToShortString());
        }
        out.attributeBoolean(null, ATTR_REALACTIVITY_SUSPENDED, realActivitySuspended);
        if (origActivity != null) {
            out.attribute(null, ATTR_ORIGACTIVITY, origActivity.flattenToShortString());
        }
        // Write affinity, and root affinity if it is different from affinity.
        // We use the special string "@" for a null root affinity, so we can identify
        // later whether we were given a root affinity or should just make it the
        // same as the affinity.
        if (affinity != null) {
            out.attribute(null, ATTR_AFFINITY, affinity);
            if (!affinity.equals(rootAffinity)) {
                out.attribute(null, ATTR_ROOT_AFFINITY, rootAffinity != null ? rootAffinity : "@");
            }
        } else if (rootAffinity != null) {
            out.attribute(null, ATTR_ROOT_AFFINITY, rootAffinity != null ? rootAffinity : "@");
        }
        if (mWindowLayoutAffinity != null) {
            out.attribute(null, ATTR_WINDOW_LAYOUT_AFFINITY, mWindowLayoutAffinity);
        }
        out.attributeBoolean(null, ATTR_ROOTHASRESET, rootWasReset);
        out.attributeBoolean(null, ATTR_AUTOREMOVERECENTS, autoRemoveRecents);
        out.attributeInt(null, ATTR_USERID, mUserId);
        out.attributeBoolean(null, ATTR_USER_SETUP_COMPLETE, mUserSetupComplete);
        out.attributeInt(null, ATTR_EFFECTIVE_UID, effectiveUid);
        out.attributeLong(null, ATTR_LASTTIMEMOVED, mLastTimeMoved);
        out.attributeBoolean(null, ATTR_NEVERRELINQUISH, mNeverRelinquishIdentity);
        if (lastDescription != null) {
            out.attribute(null, ATTR_LASTDESCRIPTION, lastDescription.toString());
        }
        if (getTaskDescription() != null) {
            getTaskDescription().saveToXml(out);
        }
        out.attributeInt(null, ATTR_TASK_AFFILIATION, mAffiliatedTaskId);
        out.attributeInt(null, ATTR_PREV_AFFILIATION, mPrevAffiliateTaskId);
        out.attributeInt(null, ATTR_NEXT_AFFILIATION, mNextAffiliateTaskId);
        out.attributeInt(null, ATTR_CALLING_UID, mCallingUid);
        out.attribute(null, ATTR_CALLING_PACKAGE, mCallingPackage == null ? "" : mCallingPackage);
        out.attribute(null, ATTR_CALLING_FEATURE_ID,
                mCallingFeatureId == null ? "" : mCallingFeatureId);
        out.attributeInt(null, ATTR_RESIZE_MODE, mResizeMode);
        out.attributeBoolean(null, ATTR_SUPPORTS_PICTURE_IN_PICTURE, mSupportsPictureInPicture);
        if (mLastNonFullscreenBounds != null) {
            out.attribute(
                    null, ATTR_NON_FULLSCREEN_BOUNDS, mLastNonFullscreenBounds.flattenToString());
        }
        out.attributeInt(null, ATTR_MIN_WIDTH, mMinWidth);
        out.attributeInt(null, ATTR_MIN_HEIGHT, mMinHeight);
        out.attributeInt(null, ATTR_PERSIST_TASK_VERSION, PERSIST_TASK_VERSION);

        if (mLastTaskSnapshotData.taskSize != null) {
            out.attribute(null, ATTR_LAST_SNAPSHOT_TASK_SIZE,
                    mLastTaskSnapshotData.taskSize.flattenToString());
        }
        if (mLastTaskSnapshotData.contentInsets != null) {
            out.attribute(null, ATTR_LAST_SNAPSHOT_CONTENT_INSETS,
                    mLastTaskSnapshotData.contentInsets.flattenToString());
        }
        if (mLastTaskSnapshotData.bufferSize != null) {
            out.attribute(null, ATTR_LAST_SNAPSHOT_BUFFER_SIZE,
                    mLastTaskSnapshotData.bufferSize.flattenToString());
        }

        if (affinityIntent != null) {
            out.startTag(null, TAG_AFFINITYINTENT);
            affinityIntent.saveToXml(out);
            out.endTag(null, TAG_AFFINITYINTENT);
        }

        if (intent != null) {
            out.startTag(null, TAG_INTENT);
            intent.saveToXml(out);
            out.endTag(null, TAG_INTENT);
        }

        sTmpException = null;
        final PooledPredicate f = PooledLambda.obtainPredicate(Task::saveActivityToXml,
                PooledLambda.__(ActivityRecord.class), getBottomMostActivity(), out);
        forAllActivities(f);
        f.recycle();
        if (sTmpException != null) {
            throw sTmpException;
        }
    }

    private static boolean saveActivityToXml(
            ActivityRecord r, ActivityRecord first, TypedXmlSerializer out) {
        if (r.info.persistableMode == ActivityInfo.PERSIST_ROOT_ONLY || !r.isPersistable()
                || ((r.intent.getFlags() & FLAG_ACTIVITY_NEW_DOCUMENT
                | FLAG_ACTIVITY_RETAIN_IN_RECENTS) == FLAG_ACTIVITY_NEW_DOCUMENT)
                && r != first) {
            // Stop at first non-persistable or first break in task (CLEAR_WHEN_TASK_RESET).
            return true;
        }
        try {
            out.startTag(null, TAG_ACTIVITY);
            r.saveToXml(out);
            out.endTag(null, TAG_ACTIVITY);
            return false;
        } catch (Exception e) {
            sTmpException = e;
            return true;
        }
    }

    static Task restoreFromXml(TypedXmlPullParser in, ActivityTaskSupervisor taskSupervisor)
            throws IOException, XmlPullParserException {
        Intent intent = null;
        Intent affinityIntent = null;
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        ComponentName realActivity = null;
        boolean realActivitySuspended = false;
        ComponentName origActivity = null;
        String affinity = null;
        String rootAffinity = null;
        boolean hasRootAffinity = false;
        String windowLayoutAffinity = null;
        boolean rootHasReset = false;
        boolean autoRemoveRecents = false;
        int taskType = 0;
        int userId = 0;
        boolean userSetupComplete = true;
        int effectiveUid = -1;
        String lastDescription = null;
        long lastTimeOnTop = 0;
        boolean neverRelinquishIdentity = true;
        int taskId = INVALID_TASK_ID;
        final int outerDepth = in.getDepth();
        TaskDescription taskDescription = new TaskDescription();
        PersistedTaskSnapshotData lastSnapshotData = new PersistedTaskSnapshotData();
        int taskAffiliation = INVALID_TASK_ID;
        int prevTaskId = INVALID_TASK_ID;
        int nextTaskId = INVALID_TASK_ID;
        int callingUid = -1;
        String callingPackage = "";
        String callingFeatureId = null;
        int resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
        boolean supportsPictureInPicture = false;
        Rect lastNonFullscreenBounds = null;
        int minWidth = INVALID_MIN_SIZE;
        int minHeight = INVALID_MIN_SIZE;
        int persistTaskVersion = 0;

        for (int attrNdx = in.getAttributeCount() - 1; attrNdx >= 0; --attrNdx) {
            final String attrName = in.getAttributeName(attrNdx);
            final String attrValue = in.getAttributeValue(attrNdx);
            if (TaskPersister.DEBUG) {
                Slog.d(TaskPersister.TAG, "Task: attribute name=" + attrName + " value="
                        + attrValue);
            }
            switch (attrName) {
                case ATTR_TASKID:
                    if (taskId == INVALID_TASK_ID) taskId = Integer.parseInt(attrValue);
                    break;
                case ATTR_REALACTIVITY:
                    realActivity = ComponentName.unflattenFromString(attrValue);
                    break;
                case ATTR_REALACTIVITY_SUSPENDED:
                    realActivitySuspended = Boolean.valueOf(attrValue);
                    break;
                case ATTR_ORIGACTIVITY:
                    origActivity = ComponentName.unflattenFromString(attrValue);
                    break;
                case ATTR_AFFINITY:
                    affinity = attrValue;
                    break;
                case ATTR_ROOT_AFFINITY:
                    rootAffinity = attrValue;
                    hasRootAffinity = true;
                    break;
                case ATTR_WINDOW_LAYOUT_AFFINITY:
                    windowLayoutAffinity = attrValue;
                    break;
                case ATTR_ROOTHASRESET:
                    rootHasReset = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_AUTOREMOVERECENTS:
                    autoRemoveRecents = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_USERID:
                    userId = Integer.parseInt(attrValue);
                    break;
                case ATTR_USER_SETUP_COMPLETE:
                    userSetupComplete = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_EFFECTIVE_UID:
                    effectiveUid = Integer.parseInt(attrValue);
                    break;
                case ATTR_TASKTYPE:
                    taskType = Integer.parseInt(attrValue);
                    break;
                case ATTR_LASTDESCRIPTION:
                    lastDescription = attrValue;
                    break;
                case ATTR_LASTTIMEMOVED:
                    lastTimeOnTop = Long.parseLong(attrValue);
                    break;
                case ATTR_NEVERRELINQUISH:
                    neverRelinquishIdentity = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_TASK_AFFILIATION:
                    taskAffiliation = Integer.parseInt(attrValue);
                    break;
                case ATTR_PREV_AFFILIATION:
                    prevTaskId = Integer.parseInt(attrValue);
                    break;
                case ATTR_NEXT_AFFILIATION:
                    nextTaskId = Integer.parseInt(attrValue);
                    break;
                case ATTR_CALLING_UID:
                    callingUid = Integer.parseInt(attrValue);
                    break;
                case ATTR_CALLING_PACKAGE:
                    callingPackage = attrValue;
                    break;
                case ATTR_CALLING_FEATURE_ID:
                    callingFeatureId = attrValue;
                    break;
                case ATTR_RESIZE_MODE:
                    resizeMode = Integer.parseInt(attrValue);
                    break;
                case ATTR_SUPPORTS_PICTURE_IN_PICTURE:
                    supportsPictureInPicture = Boolean.parseBoolean(attrValue);
                    break;
                case ATTR_NON_FULLSCREEN_BOUNDS:
                    lastNonFullscreenBounds = Rect.unflattenFromString(attrValue);
                    break;
                case ATTR_MIN_WIDTH:
                    minWidth = Integer.parseInt(attrValue);
                    break;
                case ATTR_MIN_HEIGHT:
                    minHeight = Integer.parseInt(attrValue);
                    break;
                case ATTR_PERSIST_TASK_VERSION:
                    persistTaskVersion = Integer.parseInt(attrValue);
                    break;
                case ATTR_LAST_SNAPSHOT_TASK_SIZE:
                    lastSnapshotData.taskSize = Point.unflattenFromString(attrValue);
                    break;
                case ATTR_LAST_SNAPSHOT_CONTENT_INSETS:
                    lastSnapshotData.contentInsets = Rect.unflattenFromString(attrValue);
                    break;
                case ATTR_LAST_SNAPSHOT_BUFFER_SIZE:
                    lastSnapshotData.bufferSize = Point.unflattenFromString(attrValue);
                    break;
                default:
                    if (!attrName.startsWith(TaskDescription.ATTR_TASKDESCRIPTION_PREFIX)) {
                        Slog.w(TAG, "Task: Unknown attribute=" + attrName);
                    }
            }
        }
        taskDescription.restoreFromXml(in);

        int event;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT)
                && (event != XmlPullParser.END_TAG || in.getDepth() >= outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                final String name = in.getName();
                if (TaskPersister.DEBUG) Slog.d(TaskPersister.TAG, "Task: START_TAG name=" + name);
                if (TAG_AFFINITYINTENT.equals(name)) {
                    affinityIntent = Intent.restoreFromXml(in);
                } else if (TAG_INTENT.equals(name)) {
                    intent = Intent.restoreFromXml(in);
                } else if (TAG_ACTIVITY.equals(name)) {
                    ActivityRecord activity =
                            ActivityRecord.restoreFromXml(in, taskSupervisor);
                    if (TaskPersister.DEBUG) {
                        Slog.d(TaskPersister.TAG, "Task: activity=" + activity);
                    }
                    if (activity != null) {
                        activities.add(activity);
                    }
                } else {
                    Slog.e(TAG, "restoreTask: Unexpected name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }
        if (!hasRootAffinity) {
            rootAffinity = affinity;
        } else if ("@".equals(rootAffinity)) {
            rootAffinity = null;
        }
        if (effectiveUid <= 0) {
            Intent checkIntent = intent != null ? intent : affinityIntent;
            effectiveUid = 0;
            if (checkIntent != null) {
                IPackageManager pm = AppGlobals.getPackageManager();
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(
                            checkIntent.getComponent().getPackageName(),
                            PackageManager.MATCH_UNINSTALLED_PACKAGES
                                    | PackageManager.MATCH_DISABLED_COMPONENTS, userId);
                    if (ai != null) {
                        effectiveUid = ai.uid;
                    }
                } catch (RemoteException e) {
                }
            }
            Slog.w(TAG, "Updating task #" + taskId + " for " + checkIntent
                    + ": effectiveUid=" + effectiveUid);
        }

        if (persistTaskVersion < 1) {
            // We need to convert the resize mode of home activities saved before version one if
            // they are marked as RESIZE_MODE_RESIZEABLE to
            // RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION since we didn't have that differentiation
            // before version 1 and the system didn't resize home activities before then.
            if (taskType == 1 /* old home type */ && resizeMode == RESIZE_MODE_RESIZEABLE) {
                resizeMode = RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
            }
        } else {
            // This activity has previously marked itself explicitly as both resizeable and
            // supporting picture-in-picture.  Since there is no longer a requirement for
            // picture-in-picture activities to be resizeable, we can mark this simply as
            // resizeable and supporting picture-in-picture separately.
            if (resizeMode == RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED) {
                resizeMode = RESIZE_MODE_RESIZEABLE;
                supportsPictureInPicture = true;
            }
        }

        final Task task = new Task.Builder(taskSupervisor.mService)
                .setTaskId(taskId)
                .setIntent(intent)
                .setAffinityIntent(affinityIntent)
                .setAffinity(affinity)
                .setRootAffinity(rootAffinity)
                .setRealActivity(realActivity)
                .setOrigActivity(origActivity)
                .setRootWasReset(rootHasReset)
                .setAutoRemoveRecents(autoRemoveRecents)
                .setUserId(userId)
                .setEffectiveUid(effectiveUid)
                .setLastDescription(lastDescription)
                .setLastTimeMoved(lastTimeOnTop)
                .setNeverRelinquishIdentity(neverRelinquishIdentity)
                .setLastTaskDescription(taskDescription)
                .setLastSnapshotData(lastSnapshotData)
                .setTaskAffiliation(taskAffiliation)
                .setPrevAffiliateTaskId(prevTaskId)
                .setNextAffiliateTaskId(nextTaskId)
                .setCallingUid(callingUid)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResizeMode(resizeMode)
                .setSupportsPictureInPicture(supportsPictureInPicture)
                .setRealActivitySuspended(realActivitySuspended)
                .setUserSetupComplete(userSetupComplete)
                .setMinWidth(minWidth)
                .setMinHeight(minHeight)
                .buildInner();
        task.mLastNonFullscreenBounds = lastNonFullscreenBounds;
        task.setBounds(lastNonFullscreenBounds);
        task.mWindowLayoutAffinity = windowLayoutAffinity;
        if (activities.size() > 0) {
            // We need to add the task into hierarchy before adding child to it.
            final DisplayContent dc =
                    taskSupervisor.mRootWindowContainer.getDisplayContent(DEFAULT_DISPLAY);
            dc.getDefaultTaskDisplayArea().addChild(task, POSITION_BOTTOM);

            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                task.addChild(activities.get(activityNdx));
            }
        }

        if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "Restored task=" + task);
        return task;
    }

    @Override
    boolean isOrganized() {
        return mTaskOrganizer != null;
    }

    private boolean canBeOrganized() {
        // All root tasks can be organized
        if (isRootTask() || mCreatedByOrganizer) {
            return true;
        }

        // Task could be organized if it's the direct child of a task created by organizer.
        final Task parentTask = getParent().asTask();
        return parentTask != null && parentTask.mCreatedByOrganizer;
    }

    @Override
    boolean showSurfaceOnCreation() {
        return false;
    }

    @Override
    protected void reparentSurfaceControl(SurfaceControl.Transaction t, SurfaceControl newParent) {
        /**
         * Avoid reparenting SurfaceControl of the organized tasks that are always on top, since
         * the surfaces should be controlled by the organizer itself, like bubbles.
         */
        if (isOrganized() && isAlwaysOnTop()) {
            return;
        }
        super.reparentSurfaceControl(t, newParent);
    }

    void setHasBeenVisible(boolean hasBeenVisible) {
        mHasBeenVisible = hasBeenVisible;
        if (!hasBeenVisible) {
            return;
        }
        if (!mDeferTaskAppear) {
            sendTaskAppeared();
        }
        for (WindowContainer<?> parent = getParent(); parent != null; parent = parent.getParent()) {
            final Task parentTask = parent.asTask();
            if (parentTask == null) {
                break;
            }
            parentTask.setHasBeenVisible(true);
        }
    }


    boolean getHasBeenVisible() {
        return mHasBeenVisible;
    }

    void setDeferTaskAppear(boolean deferTaskAppear) {
        final boolean wasDeferred = mDeferTaskAppear;
        mDeferTaskAppear = deferTaskAppear;
        if (wasDeferred && !deferTaskAppear) {
            sendTaskAppeared();
        }
    }

    /** In the case that these conditions are true, we want to send the Task to the organizer:
     *     1. An organizer has been set
     *     2. The Task was created by the organizer
     *     or
     *     2a. We have a SurfaceControl
     *     2b. We have finished drawing
     * Any time any of these conditions are updated, the updating code should call
     * sendTaskAppeared.
     */
    boolean taskAppearedReady() {
        if (mTaskOrganizer == null) {
            return false;
        }

        if (mDeferTaskAppear) {
            return false;
        }

        if (mCreatedByOrganizer) {
            return true;
        }

        return mSurfaceControl != null && getHasBeenVisible();
    }

    private void sendTaskAppeared() {
        if (mTaskOrganizer != null) {
            mAtmService.mTaskOrganizerController.onTaskAppeared(mTaskOrganizer, this);
        }
    }

    private void sendTaskVanished(ITaskOrganizer organizer) {
        if (organizer != null) {
            mAtmService.mTaskOrganizerController.onTaskVanished(organizer, this);
        }
   }

    @VisibleForTesting
    boolean setTaskOrganizer(ITaskOrganizer organizer) {
        return setTaskOrganizer(organizer, false /* skipTaskAppeared */);
    }

    @VisibleForTesting
    boolean setTaskOrganizer(ITaskOrganizer organizer, boolean skipTaskAppeared) {
        if (mTaskOrganizer == organizer) {
            return false;
        }

        ITaskOrganizer prevOrganizer = mTaskOrganizer;
        // Update the new task organizer before calling sendTaskVanished since it could result in
        // a new SurfaceControl getting created that would notify the old organizer about it.
        mTaskOrganizer = organizer;
        // Let the old organizer know it has lost control.
        sendTaskVanished(prevOrganizer);

        if (mTaskOrganizer != null) {
            if (!skipTaskAppeared) {
                sendTaskAppeared();
            }
        } else {
            // No longer managed by any organizer.
            final TaskDisplayArea taskDisplayArea = getDisplayArea();
            if (taskDisplayArea != null) {
                taskDisplayArea.removeLaunchRootTask(this);
            }
            setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, false /* set */);
            if (mCreatedByOrganizer) {
                removeImmediately("setTaskOrganizer");
            }
        }

        return true;
    }

    boolean updateTaskOrganizerState() {
        return updateTaskOrganizerState(false /* skipTaskAppeared */);
    }

    /**
     * Called when the task state changes (ie. from windowing mode change) an the task organizer
     * state should also be updated.
     *
     * @param skipTaskAppeared Skips calling taskAppeared for the new organizer if it has changed
     * @return {@code true} if task organizer changed.
     */
    boolean updateTaskOrganizerState(boolean skipTaskAppeared) {
        if (getSurfaceControl() == null) {
            // Can't call onTaskAppeared without a surfacecontrol, so defer this until next one
            // is created.
            return false;
        }
        if (!canBeOrganized()) {
            return setTaskOrganizer(null);
        }

        final TaskOrganizerController controller = mWmService.mAtmService.mTaskOrganizerController;
        final ITaskOrganizer organizer = controller.getTaskOrganizer();
        // Do not change to different organizer if the task is created by organizer because only
        // the creator knows how to manage it.
        if (mCreatedByOrganizer && mTaskOrganizer != null && organizer != null
                && mTaskOrganizer != organizer) {
            return false;
        }
        return setTaskOrganizer(organizer, skipTaskAppeared);
    }

    @Override
    void setSurfaceControl(SurfaceControl sc) {
        super.setSurfaceControl(sc);
        // If the TaskOrganizer was set before we created the SurfaceControl, we need to
        // emit the callbacks now.
        sendTaskAppeared();
    }

    /**
     * @return {@code true} if the task is currently focused or one of its children is focused.
     */
    boolean isFocused() {
        if (mDisplayContent == null || mDisplayContent.mFocusedApp == null) {
            return false;
        }
        final Task focusedTask = mDisplayContent.mFocusedApp.getTask();
        return focusedTask == this || (focusedTask != null && focusedTask.getParent() == this);
    }

    /**
     * @return true if the task is visible and has at least one visible child.
     */
    private boolean hasVisibleChildren() {
        if (!isAttached() || isForceHidden()) {
            return false;
        }

        return getActivity(ActivityRecord::isVisible) != null;
    }

    /**
     * Called on the task when it gained or lost focus.
     * @param hasFocus
     */
    void onAppFocusChanged(boolean hasFocus) {
        dispatchTaskInfoChangedIfNeeded(false /* force */);
        final Task parentTask = getParent().asTask();
        if (parentTask != null) parentTask.dispatchTaskInfoChangedIfNeeded(false /* force */);

        mAtmService.getTaskChangeNotificationController().notifyTaskFocusChanged(mTaskId, hasFocus);
    }

    void onPictureInPictureParamsChanged() {
        if (inPinnedWindowingMode()) {
            dispatchTaskInfoChangedIfNeeded(true /* force */);
        }
    }

    void onShouldDockBigOverlaysChanged() {
        dispatchTaskInfoChangedIfNeeded(true /* force */);
    }

    /** Called when the top activity in the Root Task enters or exits size compat mode. */
    void onSizeCompatActivityChanged() {
        // Trigger TaskInfoChanged to update the size compat restart button.
        dispatchTaskInfoChangedIfNeeded(true /* force */);
    }

    /**
     * See {@link WindowContainerTransaction#setBoundsChangeTransaction}. In short this
     * transaction will be consumed by the next BASE_APPLICATION window within our hierarchy
     * to resize, and it will defer the transaction until that resize frame completes.
     */
    void setMainWindowSizeChangeTransaction(SurfaceControl.Transaction t) {
        setMainWindowSizeChangeTransaction(t, this);
        forAllWindows(WindowState::requestRedrawForSync, true);
    }

    private void setMainWindowSizeChangeTransaction(SurfaceControl.Transaction t, Task origin) {
        // This is only meaningful on an activity's task, so put it on the top one.
        ActivityRecord topActivity = getTopNonFinishingActivity();
        Task leaf = topActivity != null ? topActivity.getTask() : null;
        if (leaf == null) {
            return;
        }
        if (leaf != this) {
            leaf.setMainWindowSizeChangeTransaction(t, origin);
            return;
        }
        final WindowState w = getTopVisibleAppMainWindow();
        if (w != null) {
            w.applyWithNextDraw((d) -> {
                d.merge(t);
            });
        } else {
            t.apply();
        }
    }

    /**
     * Sets/unsets the forced-hidden state flag for this task depending on {@param set}.
     * @return Whether the force hidden state changed
     */
    @Override
    boolean setForceHidden(@FlagForceHidden int flags, boolean set) {
        final boolean wasHidden = isForceHidden();
        final boolean wasVisible = isVisible();
        if (!super.setForceHidden(flags, set)) {
            return false;
        }
        final boolean nowHidden = isForceHidden();
        if (wasHidden != nowHidden) {
            final String reason = "setForceHidden";
            if (wasVisible && nowHidden) {
                // Move this visible task to back when the task is forced hidden
                moveToBack(reason, null);
            } else if (isAlwaysOnTop()) {
                // Move this always-on-top task to front when no longer hidden
                moveToFront(reason);
            }
        }
        return true;
    }

    @Override
    public boolean isAlwaysOnTop() {
        return !isForceHidden() && super.isAlwaysOnTop();
    }

    /**
     * @return whether this task is always on top without taking visibility into account.
     */
    public boolean isAlwaysOnTopWhenVisible() {
        return super.isAlwaysOnTop();
    }

    boolean isForceHiddenForPinnedTask() {
        return (mForceHiddenFlags & FLAG_FORCE_HIDDEN_FOR_PINNED_TASK) != 0;
    }

    @Override
    long getProtoFieldId() {
        return TASK;
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        // Calling Task#setWindowingMode() for leaf task since this is a specialization of
        // {@link #setWindowingMode(int)} for root task.
        if (!isRootTask()) {
            mMultiWindowRestoreWindowingMode = INVALID_WINDOWING_MODE;
            super.setWindowingMode(windowingMode);
            return;
        }

        setWindowingMode(windowingMode, false /* creating */);
    }

    /**
     * Specialization of {@link #setWindowingMode(int)} for this subclass.
     *
     * @param preferredWindowingMode the preferred windowing mode. This may not be honored depending
     *         on the state of things. For example, WINDOWING_MODE_UNDEFINED will resolve to the
     *         previous non-transient mode if this root task is currently in a transient mode.
     * @param creating {@code true} if this is being run during task construction.
     */
    void setWindowingMode(int preferredWindowingMode, boolean creating) {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            Slog.d(TAG, "taskDisplayArea is null, bail early");
            return;
        }
        final int currentMode = getWindowingMode();
        final Task topTask = getTopMostTask();
        int windowingMode = preferredWindowingMode;

        // Need to make sure windowing mode is supported. If we in the process of creating the
        // root task no need to resolve the windowing mode again as it is already resolved to the
        // right mode.
        if (!creating) {
            if (!taskDisplayArea.isValidWindowingMode(windowingMode, null /* ActivityRecord */,
                    topTask)) {
                windowingMode = WINDOWING_MODE_UNDEFINED;
            }
        }

        if (currentMode == windowingMode) {
            // You are already in the window mode, so we can skip most of the work below. However,
            // it's possible that we have inherited the current windowing mode from a parent. So,
            // fulfill this method's contract by setting the override mode directly.
            getRequestedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
            return;
        }

        // Reset multi-window restore windowing mode.
        mMultiWindowRestoreWindowingMode = INVALID_WINDOWING_MODE;

        final ActivityRecord topActivity = getTopNonFinishingActivity();

        // For now, assume that the root task's windowing mode is what will actually be used
        // by it's activities. In the future, there may be situations where this doesn't
        // happen; so at that point, this message will need to handle that.
        int likelyResolvedMode = windowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            final ConfigurationContainer parent = getParent();
            likelyResolvedMode = parent != null ? parent.getWindowingMode()
                    : WINDOWING_MODE_FULLSCREEN;
        }
        if (currentMode == WINDOWING_MODE_PINNED) {
            // In the case that we've disabled affecting the SysUI flags as a part of seamlessly
            // transferring the transform on the leash to the task, reset this state once we're
            // moving out of pip
            setCanAffectSystemUiFlags(true);
            mRootWindowContainer.notifyActivityPipModeChanged(this, null);
        }
        if (likelyResolvedMode == WINDOWING_MODE_PINNED) {
            if (taskDisplayArea.getRootPinnedTask() != null) {
                // Can only have 1 pip at a time, so replace an existing pip
                taskDisplayArea.getRootPinnedTask().dismissPip();
            }
        }
        if (likelyResolvedMode != WINDOWING_MODE_FULLSCREEN
                && topActivity != null && !topActivity.noDisplay
                && topActivity.canForceResizeNonResizable(likelyResolvedMode)) {
            // Inform the user that they are starting an app that may not work correctly in
            // multi-window mode.
            final String packageName = topActivity.info.applicationInfo.packageName;
            mAtmService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                    topTask.mTaskId, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN, packageName);
        }

        mAtmService.deferWindowLayout();
        try {
            if (topActivity != null) {
                mTaskSupervisor.mNoAnimActivities.add(topActivity);
            }

            final boolean isPip2ExperimentEnabled =
                    ActivityTaskManagerService.isPip2ExperimentEnabled();
            if (!isPip2ExperimentEnabled) {
                super.setWindowingMode(windowingMode);
            }

            if (currentMode == WINDOWING_MODE_PINNED && topActivity != null) {
                // Try reparent pinned activity back to its original task after
                // onConfigurationChanged cascade finishes. This is done on Task level instead of
                // {@link ActivityRecord#onConfigurationChanged(Configuration)} since when we exit
                // PiP, we set final windowing mode on the ActivityRecord first and then on its
                // Task when the exit PiP transition finishes. Meanwhile, the exit transition is
                // always performed on its original task, reparent immediately in ActivityRecord
                // breaks it. Do not reparent if the pinned task is in removal, indicated by the
                // force hidden flag.
                if (topActivity.getLastParentBeforePip() != null && !isForceHidden()
                        && topActivity.getLastParentBeforePip().isAttached()) {
                    // We need to collect the pip activity to allow for screenshots
                    // to be taken as a part of reparenting.
                    mTransitionController.collect(topActivity);

                    final Task lastParentBeforePip = topActivity.getLastParentBeforePip();
                    topActivity.reparent(lastParentBeforePip,
                            lastParentBeforePip.getChildCount() /* top */,
                            "movePinnedActivityToOriginalTask");
                    final DisplayContent dc = topActivity.getDisplayContent();
                    if (dc != null && dc.isFixedRotationLaunchingApp(topActivity)) {
                        // Expanding pip into new rotation, so create a rotation leash
                        // until the display is rotated.
                        topActivity.getOrCreateFixedRotationLeash(
                                topActivity.getSyncTransaction());
                    }
                    lastParentBeforePip.moveToFront("movePinnedActivityToOriginalTask");
                    // If the reparent is not included in transition, make sure the visibility of
                    // task is still updated by core. Otherwise if the task is collected (e.g.
                    // rotation change) after leaving this scope, the visibility operation will be
                    // put in sync transaction, then it is not synced with reparent.
                    if (com.android.window.flags.Flags.removePrepareSurfaceInPlacement()
                            && lastParentBeforePip.mSyncState == SYNC_STATE_NONE) {
                        lastParentBeforePip.prepareSurfaces();
                    }
                }
                if (isPip2ExperimentEnabled) {
                    super.setWindowingMode(windowingMode);
                }
                // Resume app-switches-allowed flag when exiting from pinned mode since
                // it does not follow the ActivityStarter path.
                if (topActivity.shouldBeVisible()) {
                    mAtmService.resumeAppSwitches();
                }
            } else if (isPip2ExperimentEnabled) {
                super.setWindowingMode(windowingMode);
            }

            if (creating) {
                // Nothing else to do if we don't have a window container yet. E.g. call from ctor.
                return;
            }

            // From fullscreen to PiP.
            if (topActivity != null && currentMode == WINDOWING_MODE_FULLSCREEN
                    && windowingMode == WINDOWING_MODE_PINNED
                    && !mTransitionController.isShellTransitionsEnabled()) {
                mDisplayContent.mPinnedTaskController
                        .deferOrientationChangeForEnteringPipFromFullScreenIfNeeded();
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        if (!mTaskSupervisor.isRootVisibilityUpdateDeferred()) {
            mRootWindowContainer.ensureActivitiesVisible();
            mRootWindowContainer.resumeFocusedTasksTopActivities();
        }
    }

    /**
     * Abort an incomplete pip-entry. If left in this state, it will cover everything but remain
     * paused. If this is needed, there is a bug -- this should only be used for recovery.
     *
     * @return true if there is an inconsistency in the task and activity state.
     */
    boolean abortPipEnter(ActivityRecord top) {
        // an incomplete state has the task PINNED but the activity not.
        if (!inPinnedWindowingMode() || top.inPinnedWindowingMode() || !canMoveTaskToBack(this)) {
            return false;
        }
        final Transition transition = new Transition(TRANSIT_TO_BACK, 0 /* flags */,
                mTransitionController, mWmService.mSyncEngine);
        mTransitionController.moveToCollecting(transition);
        mTransitionController.requestStartTransition(transition, this, null /* remoteTransition */,
                null /* displayChange */);
        if (top.getLastParentBeforePip() != null) {
            final Task lastParentBeforePip = top.getLastParentBeforePip();
            if (lastParentBeforePip.isAttached()) {
                top.reparent(lastParentBeforePip, lastParentBeforePip.getChildCount() /* top */,
                        "movePinnedActivityToOriginalTask");
            }
        }
        if (isAttached()) {
            setWindowingMode(WINDOWING_MODE_UNDEFINED);
            moveTaskToBackInner(this, null /* transition */);
        }
        if (top.isAttached()) {
            top.setWindowingMode(WINDOWING_MODE_UNDEFINED);
            top.mWaitForEnteringPinnedMode = false;
        }
        return true;
    }

    void resumeNextFocusAfterReparent() {
        adjustFocusToNextFocusableTask("reparent", true /* allowFocusSelf */,
                true /* moveDisplayToTop */);
        mRootWindowContainer.resumeFocusedTasksTopActivities();
        // Update visibility of activities before notifying WM. This way it won't try to resize
        // windows that are no longer visible.
        mRootWindowContainer.ensureActivitiesVisible();
    }

    final boolean isOnHomeDisplay() {
        return getDisplayId() == DEFAULT_DISPLAY;
    }

    void moveToFront(String reason) {
        moveToFront(reason, null);
    }

    /**
     * @param reason The reason for moving the root task to the front.
     * @param task If non-null, the task will be moved to the top of the root task.
     */
    @VisibleForTesting
    void moveToFront(String reason, Task task) {
        if (!isAttached()) {
            return;
        }
        mTransitionController.recordTaskOrder(this);

        final TaskDisplayArea taskDisplayArea = getDisplayArea();

        if (!isActivityTypeHome() && returnsToHomeRootTask()) {
            // Make sure the root home task is behind this root task since that is where we
            // should return to when this root task is no longer visible.
            taskDisplayArea.moveHomeRootTaskToFront(reason + " returnToHome");
        }

        final Task lastFocusedTask = isRootTask() ? taskDisplayArea.getFocusedRootTask() : null;
        if (task == null) {
            task = this;
        }
        task.getParent().positionChildAt(POSITION_TOP, task, true /* includingParents */);
        taskDisplayArea.updateLastFocusedRootTask(lastFocusedTask, reason);
    }

    /**
     * This moves 'task' to the back of this task and also recursively moves this task to the back
     * of its parents (if applicable).
     *
     * @param reason The reason for moving the root task to the back.
     * @param task If non-null, the task will be moved to the bottom of the root task.
     **/
    void moveToBack(String reason, Task task) {
        if (!isAttached()) {
            return;
        }
        final TaskDisplayArea displayArea = getDisplayArea();
        if (!mCreatedByOrganizer) {
            // If this is just a normal task, so move to back of parent and then move 'task' to
            // back of this.
            final WindowContainer parent = getParent();
            final Task parentTask = parent != null ? parent.asTask() : null;
            if (parentTask != null) {
                parentTask.moveToBack(reason, this);
            } else {
                final Task lastFocusedTask = displayArea.getFocusedRootTask();
                displayArea.positionChildAt(POSITION_BOTTOM, this, false /*includingParents*/);
                displayArea.updateLastFocusedRootTask(lastFocusedTask, reason);
            }
            if (task != null && task != this) {
                positionChildAtBottom(task);
            }
            return;
        }
        if (task == null || task == this) {
            return;
        }
        // This is a created-by-organizer task. In this case, let the organizer deal with this
        // task's ordering. However, we still need to move 'task' to back. The intention is that
        // this ends up behind the home-task so that it is made invisible; so, if the home task
        // is not a child of this, reparent 'task' to the back of the home task's actual parent.
        displayArea.positionTaskBehindHome(task);
    }

    // TODO: Should each user have there own root tasks?
    @Override
    void switchUser(int userId) {
        if (mCurrentUser == userId) {
            return;
        }
        mCurrentUser = userId;

        super.switchUser(userId);
        if (!isRootTask() && showToCurrentUser()) {
            getParent().positionChildAt(POSITION_TOP, this, false /*includeParents*/);
        }
    }

    void checkReadyForSleep() {
        if (shouldSleepActivities() && goToSleepIfPossible(false /* shuttingDown */)) {
            mTaskSupervisor.checkReadyForSleepLocked(true /* allowDelay */);
        }
    }

    /**
     * Tries to put the activities in the root task to sleep.
     *
     * If the root task is not in a state where its activities can be put to sleep, this function
     * will start any necessary actions to move the root task into such a state. It is expected
     * that this function get called again when those actions complete.
     *
     * @param shuttingDown true when the called because the device is shutting down.
     * @return true if the root task finished going to sleep, false if the root task only started
     * the process of going to sleep (checkReadyForSleep will be called when that process finishes).
     */
    boolean goToSleepIfPossible(boolean shuttingDown) {
        final int[] sleepInProgress = {0};
        forAllLeafTasksAndLeafTaskFragments(taskFragment -> {
            if (!taskFragment.sleepIfPossible(shuttingDown)) {
                sleepInProgress[0]++;
            }
        }, true /* traverseTopToBottom */);
        return sleepInProgress[0] == 0;
    }

    boolean isTopRootTaskInDisplayArea() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        return taskDisplayArea != null && taskDisplayArea.isTopRootTask(this);
    }

    /**
     * @return {@code true} if this is the focused root task on its current display, {@code false}
     * otherwise.
     */
    boolean isFocusedRootTaskOnDisplay() {
        return mDisplayContent != null && this == mDisplayContent.getFocusedRootTask();
    }

    /**
     * Make sure that all activities that need to be visible in the root task (that is, they
     * currently can be seen by the user) actually are and update their configuration.
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     */
    void ensureActivitiesVisible(@Nullable ActivityRecord starting) {
        ensureActivitiesVisible(starting, true /* notifyClients */);
    }

    /**
     * Ensure visibility with an option to also update the configuration of visible activities.
     * @see #ensureActivitiesVisible(ActivityRecord)
     * @see RootWindowContainer#ensureActivitiesVisible()
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     * @param notifyClients Flag indicating whether the visibility updates should be sent to the
     *                      clients in {@link EnsureActivitiesVisibleHelper}.
     */
    // TODO: Should be re-worked based on the fact that each task as a root task in most cases.
    void ensureActivitiesVisible(@Nullable ActivityRecord starting, boolean notifyClients) {
        mTaskSupervisor.beginActivityVisibilityUpdate();
        try {
            forAllLeafTasks(task -> {
                task.updateActivityVisibilities(starting, notifyClients);
            }, true /* traverseTopToBottom */);

            if (mTranslucentActivityWaiting != null &&
                    mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
                // Nothing is getting drawn or everything was already visible, don't wait for
                // timeout.
                notifyActivityDrawnLocked(null);
            }
        } finally {
            mTaskSupervisor.endActivityVisibilityUpdate();
        }
    }

    void abortTranslucentActivityWaiting(@NonNull ActivityRecord r) {
        if (r != mTranslucentActivityWaiting && r != mPendingConvertFromTranslucentActivity) {
            return;
        }

        if (mTranslucentActivityWaiting != null) {
            if (!mTranslucentActivityWaiting.finishing) {
                mTranslucentActivityWaiting.setOccludesParent(true);
            }
            mTranslucentActivityWaiting = null;
        }
        if (mPendingConvertFromTranslucentActivity != null) {
            if (!mPendingConvertFromTranslucentActivity.finishing) {
                mPendingConvertFromTranslucentActivity.setOccludesParent(true);
            }
            mPendingConvertFromTranslucentActivity = null;
        }
        mUndrawnActivitiesBelowTopTranslucent.clear();
        mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);
    }

    void checkTranslucentActivityWaiting(ActivityRecord top) {
        if (mTranslucentActivityWaiting != top) {
            mUndrawnActivitiesBelowTopTranslucent.clear();
            if (mTranslucentActivityWaiting != null) {
                // Call the callback with a timeout indication.
                notifyActivityDrawnLocked(null);
                mTranslucentActivityWaiting = null;
            }
            mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);
        }
    }

    void convertActivityToTranslucent(ActivityRecord r) {
        mTranslucentActivityWaiting = r;
        mPendingConvertFromTranslucentActivity = r;
        mUndrawnActivitiesBelowTopTranslucent.clear();
        mHandler.sendEmptyMessageDelayed(TRANSLUCENT_TIMEOUT_MSG, TRANSLUCENT_CONVERSION_TIMEOUT);
    }

    void convertActivityFromTranslucent(ActivityRecord r) {
        if (r != mPendingConvertFromTranslucentActivity) {
            Slog.e(TAG, "convertFromTranslucent expects " + mPendingConvertFromTranslucentActivity
                    + " but is " + r);
        }
        mPendingConvertFromTranslucentActivity = null;
    }

    /**
     * Called as activities below the top translucent activity are redrawn. When the last one is
     * redrawn notify the top activity by calling
     * {@link Activity#onTranslucentConversionComplete}.
     *
     * @param r The most recent background activity to be drawn. Or, if r is null then a timeout
     * occurred and the activity will be notified immediately.
     */
    void notifyActivityDrawnLocked(ActivityRecord r) {
        if ((r == null)
                || (mUndrawnActivitiesBelowTopTranslucent.remove(r) &&
                mUndrawnActivitiesBelowTopTranslucent.isEmpty())) {
            // The last undrawn activity below the top has just been drawn. If there is an
            // opaque activity at the top, notify it that it can become translucent safely now.
            final ActivityRecord waitingActivity = mTranslucentActivityWaiting;
            mTranslucentActivityWaiting = null;
            mUndrawnActivitiesBelowTopTranslucent.clear();
            mHandler.removeMessages(TRANSLUCENT_TIMEOUT_MSG);

            if (waitingActivity != null) {
                waitingActivity.setMainWindowOpaque(false);
                if (waitingActivity.attachedToProcess()) {
                    try {
                        waitingActivity.app.getThread().scheduleTranslucentConversionComplete(
                                waitingActivity.token, r != null);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /**
     * Ensure that the top activity in the root task is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     * @param options Activity options.
     * @param deferPause When {@code true}, this will not pause back tasks.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     *
     * NOTE: It is not safe to call this method directly as it can cause an activity in a
     *       non-focused root task to be resumed.
     *       Use {@link RootWindowContainer#resumeFocusedTasksTopActivities} to resume the
     *       right activity for the current system state.
     */
    @GuardedBy("mService")
    boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options,
            boolean deferPause) {
        if (mInResumeTopActivity) {
            // Don't even start recursing.
            return false;
        }

        boolean someActivityResumed = false;
        try {
            // Protect against recursion.
            mInResumeTopActivity = true;

            if (isLeafTask()) {
                if (isFocusableAndVisible()) {
                    someActivityResumed = resumeTopActivityInnerLocked(prev, options, deferPause);
                }
            } else {
                int idx = mChildren.size() - 1;
                while (idx >= 0) {
                    final Task child = (Task) getChildAt(idx--);
                    if (!child.isTopActivityFocusable()) {
                        continue;
                    }
                    if (child.getVisibility(null /* starting */)
                            != TASK_FRAGMENT_VISIBILITY_VISIBLE) {
                        if (child.topRunningActivity() == null) {
                            // Skip the task if no running activity and continue resuming next task.
                            continue;
                        }
                        // Otherwise, assuming everything behind this task should also be invisible.
                        break;
                    }

                    someActivityResumed |= child.resumeTopActivityUncheckedLocked(prev, options,
                            deferPause);
                    // Doing so in order to prevent IndexOOB since hierarchy might changes while
                    // resuming activities, for example dismissing split-screen while starting
                    // non-resizeable activity.
                    if (idx >= mChildren.size()) {
                        idx = mChildren.size() - 1;
                    }
                }
            }

            // When resuming the top activity, it may be necessary to pause the top activity (for
            // example, returning to the lock screen. We suppress the normal pause logic in
            // {@link #resumeTopActivityUncheckedLocked}, since the top activity is resumed at the
            // end. We call the {@link ActivityTaskSupervisor#checkReadyForSleepLocked} again here
            // to ensure any necessary pause logic occurs. In the case where the Activity will be
            // shown regardless of the lock screen, the call to
            // {@link ActivityTaskSupervisor#checkReadyForSleepLocked} is skipped.
            final ActivityRecord next = topRunningActivity(true /* focusableOnly */);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
        } finally {
            mInResumeTopActivity = false;
        }

        return someActivityResumed;
    }

    /** @see #resumeTopActivityUncheckedLocked(ActivityRecord, ActivityOptions, boolean) */
    @GuardedBy("mService")
    boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        return resumeTopActivityUncheckedLocked(prev, options, false /* skipPause */);
    }

    @GuardedBy("mService")
    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options,
            boolean deferPause) {
        if (!mAtmService.isBooting() && !mAtmService.isBooted()) {
            // Not ready yet!
            return false;
        }

        final ActivityRecord topActivity = topRunningActivity(true /* focusableOnly */);
        if (topActivity == null) {
            // There are no activities left in this task, let's look somewhere else.
            return resumeNextFocusableActivityWhenRootTaskIsEmpty(prev, options);
        }

        final boolean[] resumed = new boolean[1];
        final TaskFragment topFragment = topActivity.getTaskFragment();
        resumed[0] = topFragment.resumeTopActivity(prev, options, deferPause);
        forAllLeafTaskFragments(f -> {
            if (topFragment == f) {
                return;
            }
            if (!f.canBeResumed(null /* starting */)) {
                return;
            }
            resumed[0] |= f.resumeTopActivity(prev, options, deferPause);
        }, true);
        return resumed[0];
    }

    /**
     * Resume the next eligible activity in a focusable root task when this one does not have any
     * running activities left. The focus will be adjusted to the next focusable root task and
     * top running activities will be resumed in all focusable root tasks. However, if the
     * current root task is a root home task - we have to keep it focused, start and resume a
     * home activity on the current display instead to make sure that the display is not empty.
     */
    private boolean resumeNextFocusableActivityWhenRootTaskIsEmpty(ActivityRecord prev,
            ActivityOptions options) {
        final String reason = "noMoreActivities";

        if (!isActivityTypeHome()) {
            final Task nextFocusedTask = adjustFocusToNextFocusableTask(reason);
            if (nextFocusedTask != null) {
                // Try to move focus to the next visible root task with a running activity if this
                // root task is not covering the entire screen or is on a secondary display with
                // no home root task.
                return mRootWindowContainer.resumeFocusedTasksTopActivities(nextFocusedTask,
                        prev, null /* targetOptions */);
            }
        }

        // If the current root task is a root home task, or if focus didn't switch to a different
        // root task - just start up the Launcher...
        ActivityOptions.abort(options);
        ProtoLog.d(WM_DEBUG_STATES, "resumeNextFocusableActivityWhenRootTaskIsEmpty: %s, "
                + "go home", reason);
        return mRootWindowContainer.resumeHomeActivity(prev, reason, getDisplayArea());
    }

    void startActivityLocked(ActivityRecord r, @Nullable Task topTask, boolean newTask,
            boolean isTaskSwitch, ActivityOptions options, @Nullable ActivityRecord sourceRecord) {
        Task rTask = r.getTask();
        final boolean allowMoveToFront = options == null || !options.getAvoidMoveToFront();
        final boolean isOrhasTask = rTask == this || hasChild(rTask);
        // mLaunchTaskBehind tasks get placed at the back of the task stack.
        if (!r.mLaunchTaskBehind && allowMoveToFront && (!isOrhasTask || newTask)) {
            // Last activity in task had been removed or ActivityManagerService is reusing task.
            // Insert or replace.
            // Might not even be in.
            positionChildAtTop(rTask);
        }
        Task task = null;
        if (!newTask && isOrhasTask && !r.shouldBeVisible()) {
            ActivityOptions.abort(options);
            return;
        }

        // Place a new activity at top of root task, so it is next to interact with the user.

        // If we are not placing the new activity frontmost, we do not want to deliver the
        // onUserLeaving callback to the actual frontmost activity
        final Task activityTask = r.getTask();
        if (task == activityTask && mChildren.indexOf(task) != (getChildCount() - 1)) {
            mTaskSupervisor.mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "startActivity() behind front, mUserLeaving=false");
        }

        task = activityTask;

        // Slot the activity into the history root task and proceed
        ProtoLog.i(WM_DEBUG_ADD_REMOVE, "Adding activity %s to task %s "
                        + "callers: %s", r, task, new RuntimeException("here").fillInStackTrace());

        if (isActivityTypeHomeOrRecents() && getActivityBelow(r) == null) {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            ActivityOptions.abort(options);
            return;
        }

        if (!allowMoveToFront) {
            // The transition animation and starting window are not needed if
            // {@code allowMoveToFront} is false, because the activity won't be visible.
            ActivityOptions.abort(options);
            return;
        }

        final DisplayContent dc = mDisplayContent;
        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                "Prepare open transition: starting " + r);
        if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            dc.prepareAppTransition(TRANSIT_NONE);
            mTaskSupervisor.mNoAnimActivities.add(r);
            mTransitionController.setNoAnimation(r);
        } else {
            dc.prepareAppTransition(TRANSIT_OPEN);
            mTaskSupervisor.mNoAnimActivities.remove(r);
        }
        if (newTask && !r.mLaunchTaskBehind) {
            // If a new task is being launched, then mark the existing top activity as
            // supporting picture-in-picture while pausing only if the starting activity
            // would not be considered an overlay on top of the current activity
            // (eg. not fullscreen, or the assistant)
            if (!ActivityTaskManagerService.isPip2ExperimentEnabled()) {
                final ActivityRecord pipCandidate = findEnterPipOnTaskSwitchCandidate(topTask);
                enableEnterPipOnTaskSwitch(pipCandidate, null /* toFrontTask */, r, options);
            }
        }
        boolean doShow = true;
        if (newTask) {
            // Even though this activity is starting fresh, we still need
            // to reset it to make sure we apply affinities to move any
            // existing activities from other tasks in to it.
            // If the caller has requested that the target task be
            // reset, then do so.
            if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                resetTaskIfNeeded(r, r);
                doShow = topRunningNonDelayedActivityLocked(null) == r;
            }
        } else if (options != null && options.getAnimationType()
                == ActivityOptions.ANIM_SCENE_TRANSITION) {
            doShow = false;
        }
        if (options != null && options.getDisableStartingWindow()) {
            doShow = false;
        }
        if (r.mLaunchTaskBehind) {
            // Don't do a starting window for mLaunchTaskBehind. More importantly make sure we
            // tell WindowManager that r is visible even though it is at the back of the root
            // task.
            r.setVisibility(true);
            ensureActivitiesVisible(null /* starting */);
            // If launching behind, the app will start regardless of what's above it, so mark it
            // as unknown even before prior `pause`. This also prevents a race between set-ready
            // and activityPause. Launch-behind is basically only used for dream now.
            if (!r.isVisibleRequested()) {
                r.notifyUnknownVisibilityLaunchedForKeyguardTransition();
            }
            // Go ahead to execute app transition for this activity since the app transition
            // will not be triggered through the resume channel.
            mDisplayContent.executeAppTransition();
        } else if (SHOW_APP_STARTING_PREVIEW && doShow) {
            // Figure out if we are transitioning from another activity that is
            // "has the same starting icon" as the next one.  This allows the
            // window manager to keep the previous window it had previously
            // created, if it still had one.
            Task baseTask = r.getTask();
            final ActivityRecord prev = baseTask.getActivity(
                    a -> a.mStartingData != null && a.showToCurrentUser());
            mWmService.mStartingSurfaceController.showStartingWindow(r, prev, newTask,
                    isTaskSwitch, sourceRecord);
        }
    }

    /** On Task switch, finds the top activity that supports PiP. */
    @Nullable
    static ActivityRecord findEnterPipOnTaskSwitchCandidate(@Nullable Task topTask) {
        if (topTask == null) {
            return null;
        }
        final ActivityRecord[] candidate = new ActivityRecord[1];
        topTask.forAllLeafTaskFragments(tf -> {
            // Find the top activity that may enter Pip while pausing.
            final ActivityRecord topActivity = tf.getTopNonFinishingActivity();
            if (topActivity != null && topActivity.isState(RESUMED, PAUSING)
                    && topActivity.supportsPictureInPicture()) {
                candidate[0] = topActivity;
                return true;
            }
            return false;
        });
        return candidate[0];
    }

    /**
     * When switching to another Task, marks the currently PiP candidate activity as supporting to
     * enter PiP while it is pausing (if supported). Only one of {@param toFrontTask} or
     * {@param toFrontActivity} should be set.
     */
    static void enableEnterPipOnTaskSwitch(@Nullable ActivityRecord pipCandidate,
            @Nullable Task toFrontTask, @Nullable ActivityRecord toFrontActivity,
            @Nullable ActivityOptions opts) {
        if (pipCandidate == null) {
            return;
        }
        if (opts != null && opts.disallowEnterPictureInPictureWhileLaunching()) {
            // Ensure the caller has requested not to trigger auto-enter PiP
            return;
        }
        if (pipCandidate.inPinnedWindowingMode()) {
            // Ensure that we do not trigger entering PiP an activity on the root pinned task.
            return;
        }
        final Task targetRootTask = toFrontTask != null ? toFrontTask.getRootTask()
                : toFrontActivity != null ? toFrontActivity.getRootTask() : null;
        if (targetRootTask == null) {
            Slog.e(TAG, "No root task for enter pip, both to front task and activity are null?");
            return;
        }
        final boolean isTransient = opts != null && opts.getTransientLaunch()
                || (targetRootTask.mTransitionController.isTransientHide(targetRootTask));

        // Ensure the task/activity being brought forward is not the assistant and is not transient
        // nor transient hide target. In the case of transient-launch, we want to wait until the end
        // of the transition and only allow to enter pip on task switch after the transient launch
        // was committed.
        pipCandidate.supportsEnterPipOnTaskSwitch = !targetRootTask.isActivityTypeAssistant()
                && !isTransient;
    }

    /**
     * Reset the task by reparenting the activities that have same affinity to the task or
     * reparenting the activities that have different affinityies out of the task, while these
     * activities allow task reparenting.
     *
     * @param taskTop     Top activity of the task might be reset.
     * @param newActivity The activity that going to be started.
     * @return The non-finishing top activity of the task after reset or the original task top
     *         activity if all activities within the task are finishing.
     */
    ActivityRecord resetTaskIfNeeded(ActivityRecord taskTop, ActivityRecord newActivity) {
        final boolean forceReset =
                (newActivity.info.flags & ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        final Task task = taskTop.getTask();

        // If ActivityOptions are moved out and need to be aborted or moved to taskTop.
        final ActivityOptions topOptions;

        // Set the task to be reused, so the TaskFragment#mClearedTaskForReuse can be set if the
        // embedded activities are finished while reset task.
        mReuseTask = true;
        try {
            topOptions = sResetTargetTaskHelper.process(task, forceReset);
        } finally {
            mReuseTask = false;
        }

        if (mChildren.contains(task)) {
            final ActivityRecord newTop = task.getTopNonFinishingActivity();
            if (newTop != null) {
                taskTop = newTop;
            }
        }

        if (topOptions != null) {
            // If we got some ActivityOptions from an activity on top that
            // was removed from the task, propagate them to the new real top.
            taskTop.updateOptionsLocked(topOptions);
        }

        return taskTop;
    }

    /**
     * Finish the topmost activity that belongs to the crashed app. We may also finish the activity
     * that requested launch of the crashed one to prevent launch-crash loop.
     * @param app The app that crashed.
     * @param reason Reason to perform this action.
     * @return The task that was finished in this root task, {@code null} if top running activity
     *         does not belong to the crashed app.
     */
    final Task finishTopCrashedActivityLocked(WindowProcessController app, String reason) {
        final ActivityRecord r = topRunningActivity();
        if (r == null || r.app != app) {
            return null;
        }
        if (r.isActivityTypeHome() && mAtmService.mHomeProcess == app) {
            // Home activities should not be force-finished as we have nothing else to go
            // back to. AppErrors will get to it after two crashes in MIN_CRASH_INTERVAL.
            Slog.w(TAG, "  Not force finishing home activity "
                    + r.intent.getComponent().flattenToShortString());
            return null;
        }
        Slog.w(TAG, "  Force finishing activity "
                + r.intent.getComponent().flattenToShortString());
        Task finishedTask = r.getTask();
        mDisplayContent.requestTransitionAndLegacyPrepare(TRANSIT_CLOSE, TRANSIT_FLAG_APP_CRASHED);
        r.finishIfPossible(reason, false /* oomAdj */);

        // Also terminate any activities below it that aren't yet stopped, to avoid a situation
        // where one will get re-start our crashing activity once it gets resumed again.
        final ActivityRecord activityBelow = getActivityBelow(r);
        if (activityBelow != null) {
            if (activityBelow.isState(STARTED, RESUMED, PAUSING, PAUSED)) {
                if (!activityBelow.isActivityTypeHome()
                        || mAtmService.mHomeProcess != activityBelow.app) {
                    Slog.w(TAG, "  Force finishing activity "
                            + activityBelow.intent.getComponent().flattenToShortString());
                    activityBelow.finishIfPossible(reason, false /* oomAdj */);
                }
            }
        }

        return finishedTask;
    }

    void finishIfVoiceTask(IBinder binder) {
        if (voiceSession != null && voiceSession.asBinder() == binder) {
            forAllActivities((r) -> {
                if (r.finishing) return;
                r.finishIfPossible("finish-voice", false /* oomAdj */);
                mAtmService.updateOomAdj();
            });
        } else {
            // Check if any of the activities are using voice
            final PooledPredicate f = PooledLambda.obtainPredicate(
                    Task::finishIfVoiceActivity, PooledLambda.__(ActivityRecord.class),
                    binder);
            forAllActivities(f);
            f.recycle();
        }
    }

    private static boolean finishIfVoiceActivity(ActivityRecord r, IBinder binder) {
        if (r.voiceSession == null || r.voiceSession.asBinder() != binder) return false;
        // Inform of cancellation
        r.clearVoiceSessionLocked();
        try {
            r.app.getThread().scheduleLocalVoiceInteractionStarted(r.token, null);
        } catch (RemoteException re) {
            // Ok Boomer...
        }
        r.mAtmService.finishRunningVoiceLocked();
        return true;
    }

    /** @return true if the root task behind this one is a standard activity type. */
    private boolean inFrontOfStandardRootTask() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            return false;
        }
        final boolean[] hasFound = new boolean[1];
        final Task rootTaskBehind = taskDisplayArea.getRootTask(
                // From top to bottom, find the one behind this Task.
                task -> {
                    if (hasFound[0]) {
                        return true;
                    }
                    if (task == this) {
                        // The next one is our target.
                        hasFound[0] = true;
                    }
                    return false;
                });
        return rootTaskBehind != null && rootTaskBehind.isActivityTypeStandard();
    }

    boolean shouldUpRecreateTaskLocked(ActivityRecord srec, String destAffinity) {
        // Basic case: for simple app-centric recents, we need to recreate
        // the task if the affinity has changed.

        final String affinity = ActivityRecord.computeTaskAffinity(destAffinity, srec.getUid());
        if (srec == null || srec.getTask().affinity == null
                || !srec.getTask().affinity.equals(affinity)) {
            return true;
        }
        // Document-centric case: an app may be split in to multiple documents;
        // they need to re-create their task if this current activity is the root
        // of a document, unless simply finishing it will return them to the
        // correct app behind.
        final Task task = srec.getTask();
        if (srec.isRootOfTask() && task.getBaseIntent() != null
                && task.getBaseIntent().isDocument()) {
            // Okay, this activity is at the root of its task.  What to do, what to do...
            if (!inFrontOfStandardRootTask()) {
                // Finishing won't return to an application, so we need to recreate.
                return true;
            }
            // We now need to get the task below it to determine what to do.
            final Task prevTask = getTaskBelow(task);
            if (prevTask == null) {
                Slog.w(TAG, "shouldUpRecreateTask: task not in history for " + srec);
                return false;
            }
            if (!task.affinity.equals(prevTask.affinity)) {
                // These are different apps, so need to recreate.
                return true;
            }
        }
        return false;
    }

    boolean navigateUpTo(ActivityRecord srec, Intent destIntent, String resolvedType,
            NeededUriGrants destGrants, int resultCode, Intent resultData,
            NeededUriGrants resultGrants) {
        if (!srec.attachedToProcess()) {
            // Nothing to do if the caller is not attached, because this method should be called
            // from an alive activity.
            return false;
        }
        final Task task = srec.getTask();
        if (!srec.isDescendantOf(this)) {
            return false;
        }

        ActivityRecord parent = task.getActivityBelow(srec);
        boolean foundParentInTask = false;
        final ComponentName dest = destIntent.getComponent();
        if (task.getBottomMostActivity() != srec && dest != null) {
            final ActivityRecord candidate = task.getActivity(
                    (ar) -> ar.info.packageName.equals(dest.getPackageName())
                            && ar.info.name.equals(dest.getClassName()), srec,
                    false /*includeBoundary*/, true /*traverseTopToBottom*/);
            if (candidate != null) {
                parent = candidate;
                foundParentInTask = true;
            }
        }

        // TODO: There is a dup. of this block of code in ActivityTaskManagerService.finishActivity
        // We should consolidate.
        IActivityController controller = mAtmService.mController;
        if (controller != null) {
            ActivityRecord next = topRunningActivity(srec.token, INVALID_TASK_ID);
            if (next != null) {
                // ask watcher if this is allowed
                boolean resumeOK = true;
                try {
                    resumeOK = controller.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mAtmService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }

                if (!resumeOK) {
                    return false;
                }
            }
        }
        final long origId = Binder.clearCallingIdentity();

        final int[] resultCodeHolder = new int[1];
        resultCodeHolder[0] = resultCode;
        final Intent[] resultDataHolder = new Intent[1];
        resultDataHolder[0] = resultData;
        final NeededUriGrants[] resultGrantsHolder = new NeededUriGrants[1];
        resultGrantsHolder[0] = resultGrants;
        final ActivityRecord finalParent = parent;
        task.forAllActivities((ar) -> {
            if (ar == finalParent) return true;

            ar.finishIfPossible(resultCodeHolder[0], resultDataHolder[0], resultGrantsHolder[0],
                    "navigate-up", true /* oomAdj */);
            // Only return the supplied result for the first activity finished
            resultCodeHolder[0] = Activity.RESULT_CANCELED;
            resultDataHolder[0] = null;
            return false;
        }, srec, true, true);
        resultCode = resultCodeHolder[0];
        resultData = resultDataHolder[0];

        if (parent != null && foundParentInTask) {
            final int callingUid = srec.info.applicationInfo.uid;
            // TODO(b/64750076): Check if calling pid should really be -1.
            final int res = mAtmService.getActivityStartController()
                    .obtainStarter(destIntent, "navigateUpTo")
                    .setResolvedType(resolvedType)
                    .setUserId(srec.mUserId)
                    .setCaller(srec.app.getThread())
                    .setResultTo(parent.token)
                    .setIntentGrants(destGrants)
                    .setCallingPid(-1)
                    .setCallingUid(callingUid)
                    .setCallingPackage(srec.packageName)
                    .setCallingFeatureId(parent.launchedFromFeatureId)
                    .setRealCallingPid(-1)
                    .setRealCallingUid(callingUid)
                    .setComponentSpecified(true)
                    .execute();
            foundParentInTask = isStartResultSuccessful(res);
            if (res == ActivityManager.START_SUCCESS) {
                parent.finishIfPossible(resultCode, resultData, resultGrants,
                        "navigate-top", true /* oomAdj */);
            }
        }
        Binder.restoreCallingIdentity(origId);
        return foundParentInTask;
    }

    void removeLaunchTickMessages() {
        forAllActivities(ActivityRecord::removeLaunchTickRunnable);
    }

    private void updateTransitLocked(@WindowManager.TransitionType int transit,
            ActivityOptions options) {
        if (options != null) {
            ActivityRecord r = topRunningActivity();
            if (r != null && !r.isState(RESUMED)) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        mDisplayContent.prepareAppTransition(transit);
    }

    final void moveTaskToFront(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, String reason) {
        moveTaskToFront(tr, noAnimation, options, timeTracker, !DEFER_RESUME, reason);
    }

    final void moveTaskToFront(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, boolean deferResume, String reason) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "moveTaskToFront: " + tr);

        final ActivityRecord pipCandidate = findEnterPipOnTaskSwitchCandidate(
                getDisplayArea().getTopRootTask());

        if (tr != this && !tr.isDescendantOf(this)) {
            // nothing to do!
            if (noAnimation) {
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(TRANSIT_TO_FRONT, options);
            }
            return;
        }

        if (timeTracker != null) {
            // The caller wants a time tracker associated with this task.
            tr.forAllActivities(a -> { a.appTimeTracker = timeTracker; });
        }

        try {
            // Defer updating the IME target since the new IME target will try to get computed
            // before updating all closing and opening apps, which can cause the ime target to
            // get calculated incorrectly.
            mDisplayContent.deferUpdateImeTarget();

            // Don't refocus if invisible to current user
            final ActivityRecord top = tr.getTopNonFinishingActivity();
            if (top == null || !top.showToCurrentUser()) {
                positionChildAtTop(tr);
                if (top != null) {
                    mTaskSupervisor.mRecentTasks.add(top.getTask());
                }
                ActivityOptions.abort(options);
                return;
            }

            // Set focus to the top running activity of this task and move all its parents to top.
            top.moveFocusableActivityToTop(reason);

            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to front transition: task=" + tr);
            if (noAnimation) {
                mDisplayContent.prepareAppTransition(TRANSIT_NONE);
                mTaskSupervisor.mNoAnimActivities.add(top);
                mTransitionController.collect(top);
                mTransitionController.setNoAnimation(top);
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(TRANSIT_TO_FRONT, options);
            }

            // If a new task is moved to the front, then mark the existing top activity as
            // supporting

            // picture-in-picture while paused only if the task would not be considered an oerlay
            // on top
            // of the current activity (eg. not fullscreen, or the assistant)
            enableEnterPipOnTaskSwitch(pipCandidate, tr, null /* toFrontActivity */, options);

            if (!deferResume) {
                mRootWindowContainer.resumeFocusedTasksTopActivities();
            }
        } finally {
            mDisplayContent.continueUpdateImeTarget();
        }
    }

    private boolean canMoveTaskToBack(Task task) {
        // In LockTask mode, moving a locked task to the back of the root task may expose unlocked
        // ones. Therefore we need to check if this operation is allowed.
        if (!mAtmService.getLockTaskController().canMoveTaskToBack(task)) {
            return false;
        }

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (mAtmService.mController != null && isTopRootTaskInDisplayArea()) {
            ActivityRecord next = topRunningActivity(null, task.mTaskId);
            if (next == null) {
                next = topRunningActivity(null, INVALID_TASK_ID);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mAtmService.mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mAtmService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
                if (!moveOK) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Worker method for rearranging history task. Implements the function of moving all
     * activities for a specific task (gathering them if disjoint) into a single group at the
     * bottom of the root task.
     *
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     *
     * If this is a pinned task, it will be removed instead of rearranged.
     *
     * @param tr The task to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    boolean moveTaskToBack(Task tr) {
        Slog.i(TAG, "moveTaskToBack: " + tr);

        if (!canMoveTaskToBack(tr)) return false;

        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to back transition: task="
                + tr.mTaskId);

        if (mTransitionController.isShellTransitionsEnabled()) {
            // TODO(b/277838915): Consider to make it concurrent to eliminate the special case.
            final Transition collecting = mTransitionController.getCollectingTransition();
            if (collecting != null && collecting.mType == TRANSIT_OPEN) {
                // It can be a CLOSING participate of an OPEN transition. This avoids the deferred
                // transition from moving task to back after the task was moved to front.
                collecting.collect(tr);
                moveTaskToBackInner(tr, collecting);
                return true;
            }
            final Transition transition = new Transition(TRANSIT_TO_BACK, 0 /* flags */,
                    mTransitionController, mWmService.mSyncEngine);
            // Guarantee that this gets its own transition by queueing on SyncEngine
            mTransitionController.startCollectOrQueue(transition,
                    (deferred) -> {
                        // Need to check again if deferred since the system might
                        // be in a different state.
                        if (!isAttached() || (deferred && !canMoveTaskToBack(tr))) {
                            Slog.e(TAG, "Failed to move task to back after saying we could: "
                                    + tr.mTaskId);
                            transition.abort();
                            return;
                        }
                        mTransitionController.requestStartTransition(transition, tr,
                                null /* remoteTransition */, null /* displayChange */);
                        mTransitionController.collect(tr);
                        moveTaskToBackInner(tr, transition);
                    });
        } else {
            // Skip the transition for pinned task.
            if (!inPinnedWindowingMode()) {
                mDisplayContent.prepareAppTransition(TRANSIT_TO_BACK);
            }
            moveTaskToBackInner(tr, null /* transition */);
        }
        return true;
    }

    private void moveTaskToBackInner(@NonNull Task task, @Nullable Transition transition) {
        final Transition.ReadyCondition movedToBack =
                new Transition.ReadyCondition("moved-to-back", task);
        if (transition != null) {
            // Preventing from update surface position for WindowState if configuration changed,
            // because the position is depends on WindowFrame, so update the position before
            // relayout will only update it to "old" position.
            mAtmService.deferWindowLayout();
            transition.mReadyTracker.add(movedToBack);
        }
        try {
            moveToBack("moveTaskToBackInner", task);

            if (inPinnedWindowingMode()) {
                mTaskSupervisor.removeRootTask(this);
                return;
            }

            mRootWindowContainer.ensureVisibilityAndConfig(null /* starting */,
                    mDisplayContent, false /* deferResume */);
        } finally {
            if (mTransitionController.isShellTransitionsEnabled()) {
                mAtmService.continueWindowLayout();
            }
            if (transition != null) {
                movedToBack.meet();
            }
        }
        ActivityRecord topActivity = getDisplayArea().topRunningActivity();
        Task topRootTask = topActivity == null ? null : topActivity.getRootTask();
        if (topRootTask != null && topRootTask != this && topActivity.isState(RESUMED)) {
            // Usually resuming a top activity triggers the next app transition, but nothing's got
            // resumed in this case, so we need to execute it explicitly.
            mDisplayContent.executeAppTransition();
            mDisplayContent.setFocusedApp(topActivity);
        } else {
            mRootWindowContainer.resumeFocusedTasksTopActivities();
        }
    }

    boolean willActivityBeVisible(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            return false;
        }

        if (!r.shouldBeVisible()) return false;

        if (r.finishing) Slog.e(TAG, "willActivityBeVisible: Returning false,"
                + " would have returned true for r=" + r);
        return !r.finishing;
    }

    void unhandledBackLocked() {
        final ActivityRecord topActivity = getTopMostActivity();
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH,
                "Performing unhandledBack(): top activity: " + topActivity);
        if (topActivity != null) {
            topActivity.finishIfPossible("unhandled-back", true /* oomAdj */);
        }
    }

    boolean dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage, final boolean needSep) {
        return dump("  ", fd, pw, dumpAll, dumpClient, dumpPackage, needSep, null /* header */);
    }

    @Override
    void dumpInner(String prefix, PrintWriter pw, boolean dumpAll, String dumpPackage) {
        super.dumpInner(prefix, pw, dumpAll, dumpPackage);
        if (mCreatedByOrganizer) {
            pw.println(prefix + "  mCreatedByOrganizer=true");
        }
        if (mLastNonFullscreenBounds != null) {
            pw.print(prefix); pw.print("  mLastNonFullscreenBounds=");
            pw.println(mLastNonFullscreenBounds);
        }
        if (isLeafTask()) {
            pw.println(prefix + "  isSleeping=" + shouldSleepActivities());
            printThisActivity(pw, getTopPausingActivity(), dumpPackage, false,
                    prefix + "  topPausingActivity=", null);
            printThisActivity(pw, getTopResumedActivity(), dumpPackage, false,
                    prefix + "  topResumedActivity=", null);
            if (mMinWidth != INVALID_MIN_SIZE || mMinHeight != INVALID_MIN_SIZE) {
                pw.print(prefix); pw.print("  mMinWidth="); pw.print(mMinWidth);
                pw.print(" mMinHeight="); pw.println(mMinHeight);
            }
        }
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name, @UserIdInt int userId) {
        ArrayList<ActivityRecord> activities = new ArrayList<>();

        if ("all".equals(name)) {
            forAllActivities((Consumer<ActivityRecord>) activities::add);
        } else if ("top".equals(name)) {
            final ActivityRecord topActivity = getTopMostActivity();
            if (topActivity != null) {
                activities.add(topActivity);
            }
        } else {
            ActivityManagerService.ItemMatcher matcher = new ActivityManagerService.ItemMatcher();
            matcher.build(name);

            forAllActivities((r) -> {
                if (matcher.match(r, r.intent.getComponent())) {
                    activities.add(r);
                }
            });
        }
        if (userId != UserHandle.USER_ALL) {
            for (int i = activities.size() - 1; i >= 0; --i) {
                if (activities.get(i).mUserId != userId) {
                    activities.remove(i);
                }
            }
        }
        return activities;
    }

    Task reuseOrCreateTask(ActivityInfo info, Intent intent, boolean toTop) {
        return reuseOrCreateTask(info, intent, null /*voiceSession*/, null /*voiceInteractor*/,
                toTop, null /*activity*/, null /*source*/, null /*options*/);
    }

    // TODO: Can be removed once we change callpoints creating root tasks to be creating tasks.
    /** Either returns this current task to be re-used or creates a new child task. */
    Task reuseOrCreateTask(ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession,
            IVoiceInteractor voiceInteractor, boolean toTop, ActivityRecord activity,
            ActivityRecord source, ActivityOptions options) {

        Task task;
        if (canReuseAsLeafTask()) {
            // This root task will only contain one task, so just return itself since all root
            // tasks ara now tasks and all tasks are now root tasks.
            task = reuseAsLeafTask(voiceSession, voiceInteractor, intent, info, activity);
        } else {
            // Create child task since this root task can contain multiple tasks.
            final int taskId = activity != null
                    ? mTaskSupervisor.getNextTaskIdForUser(activity.mUserId)
                    : mTaskSupervisor.getNextTaskIdForUser();
            final int activityType = getActivityType();
            task = new Task.Builder(mAtmService)
                    .setTaskId(taskId)
                    .setActivityInfo(info)
                    .setActivityOptions(options)
                    .setIntent(intent)
                    .setVoiceSession(voiceSession)
                    .setVoiceInteractor(voiceInteractor)
                    .setOnTop(toTop)
                    .setParent(this)
                    .build();
        }

        int displayId = getDisplayId();
        if (displayId == INVALID_DISPLAY) displayId = DEFAULT_DISPLAY;
        final boolean isLockscreenShown = mAtmService.mTaskSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        if (!mTaskSupervisor.getLaunchParamsController()
                .layoutTask(task, info.windowLayout, activity, source, options)
                && !getRequestedOverrideBounds().isEmpty()
                && task.isResizeable() && !isLockscreenShown) {
            task.setBounds(getRequestedOverrideBounds());
        }

        return task;
    }

    /** Return {@code true} if this task can be reused as leaf task. */
    private boolean canReuseAsLeafTask() {
        // Cannot be reused as leaf task if this task is created by organizer or having child tasks.
        if (mCreatedByOrganizer || !isLeafTask()) {
            return false;
        }

        // Existing Tasks can be reused if a new root task will be created anyway.
        final int windowingMode = getWindowingMode();
        final int activityType = getActivityType();
        return DisplayContent.alwaysCreateRootTask(windowingMode, activityType);
    }

    void addChild(WindowContainer child, final boolean toTop, boolean showForAllUsers) {
        Task task = child.asTask();
        try {
            if (task != null) {
                task.setForceShowForAllUsers(showForAllUsers);
            }
            // We only want to move the parents to the parents if we are creating this task at the
            // top of its root task.
            addChild(child, toTop ? MAX_VALUE : 0, toTop /*moveParents*/);
        } finally {
            if (task != null) {
                task.setForceShowForAllUsers(false);
            }
        }
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        // {@link #isAwaysonTop} overrides the original behavior which also evaluates if this
        // task is force hidden, so super.isAlwaysOnTop() is used here to see whether the
        // alwaysOnTop attributes should be updated.
        if (super.isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        // positionChildAtTop() must be called even when always on top gets turned off because we
        // need to make sure that the root task is moved from among always on top windows to
        // below other always on top windows. Since the position the root task should be inserted
        // into is calculated properly in {@link DisplayContent#getTopInsertPosition()} in both
        // cases, we can just request that the root task is put at top here.
        // Don't bother moving task to top if this task is force hidden and invisible to user.
        if (!isForceHidden()) {
            getDisplayArea().positionChildAt(POSITION_TOP, this, false /* includingParents */);
        }
    }

    void dismissPip() {
        if (!isActivityTypeStandardOrUndefined()) {
            throw new IllegalArgumentException(
                    "You can't move tasks from non-standard root tasks.");
        }
        if (getWindowingMode() != WINDOWING_MODE_PINNED) {
            throw new IllegalArgumentException(
                    "Can't exit pinned mode if it's not pinned already.");
        }

        final Task task = getBottomMostTask();
        setWindowingMode(WINDOWING_MODE_UNDEFINED);

        // Task could have been removed from the hierarchy due to windowing mode change
        // where its only child is reparented back to their original parent task.
        if (isAttached()) {
            getDisplayArea().positionChildAt(POSITION_TOP, this, false /* includingParents */);
        }

        mTaskSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, this);
    }

    private int setBounds(Rect existing, Rect bounds) {
        if (equivalentBounds(existing, bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        return setBoundsUnchecked(!inMultiWindowMode() ? null : bounds);
    }

    @Override
    public void getBounds(Rect bounds) {
        bounds.set(getBounds());
    }

    /**
     * Put a Task in this root task. Used for adding only.
     * When task is added to top of the root task, the entire branch of the hierarchy (including
     * root task and display) will be brought to top.
     * @param child The child to add.
     * @param position Target position to add the task to.
     */
    private void addChild(WindowContainer child, int position, boolean moveParents) {
        // Add child task.
        addChild(child, null);

        // Move child to a proper position, as some restriction for position might apply.
        positionChildAt(position, child, moveParents /* includingParents */);
    }

    void positionChildAtTop(Task child) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        if (child == this) {
            // TODO: Fix call-points
            moveToFront("positionChildAtTop");
            return;
        }

        positionChildAt(POSITION_TOP, child, true /* includingParents */);
    }

    void positionChildAtBottom(Task child) {
        // If there are other focusable root tasks on the display, the z-order of the display
        // should not be changed just because a task was placed at the bottom. E.g. if it is
        // moving the topmost task to bottom, the next focusable root task on the same display
        // should be focused.
        final Task nextFocusableRootTask = getDisplayArea().getNextFocusableRootTask(
                child.getRootTask(), true /* ignoreCurrent */);
        positionChildAtBottom(child, nextFocusableRootTask == null /* includingParents */);
    }

    @VisibleForTesting
    void positionChildAtBottom(Task child, boolean includingParents) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        positionChildAt(POSITION_BOTTOM, child, includingParents);
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        dispatchTaskInfoChangedIfNeeded(false /* force */);

        if (!mChildren.contains(child)) {
            return;
        }
        if (child.asTask() != null) {
            // Non-root task position changed.
            mRootWindowContainer.invalidateTaskLayers();
        }

        if (child.asActivityRecord() != null) {
            // Send for TaskFragmentParentInfo#hasDirectActivity change.
            sendTaskFragmentParentInfoChangedIfNeeded();
        }
    }

    void reparent(TaskDisplayArea newParent, boolean onTop) {
        if (newParent == null) {
            throw new IllegalArgumentException("Task can't reparent to null " + this);
        }

        if (getParent() == newParent) {
            throw new IllegalArgumentException("Task=" + this + " already child of " + newParent);
        }

        if (canBeLaunchedOnDisplay(newParent.getDisplayId())) {
            reparent(newParent, onTop ? POSITION_TOP : POSITION_BOTTOM);
            if (isLeafTask()) {
                newParent.onLeafTaskMoved(this, onTop, !onTop);
            }
        } else {
            Slog.w(TAG, "Task=" + this + " can't reparent to " + newParent);
        }
    }

    void setLastRecentsAnimationTransaction(@NonNull PictureInPictureSurfaceTransaction transaction,
            @Nullable SurfaceControl overlay) {
        mLastRecentsAnimationTransaction = new PictureInPictureSurfaceTransaction(transaction);
        mLastRecentsAnimationOverlay = overlay;
    }

    void clearLastRecentsAnimationTransaction(boolean forceRemoveOverlay) {
        if (forceRemoveOverlay && mLastRecentsAnimationOverlay != null) {
            getPendingTransaction().remove(mLastRecentsAnimationOverlay);
        }
        mLastRecentsAnimationTransaction = null;
        mLastRecentsAnimationOverlay = null;
        // reset also the crop and transform introduced by mLastRecentsAnimationTransaction
        resetSurfaceControlTransforms();
    }

    void resetSurfaceControlTransforms() {
        getSyncTransaction().setMatrix(mSurfaceControl, Matrix.IDENTITY_MATRIX, new float[9])
                .setWindowCrop(mSurfaceControl, null)
                .setShadowRadius(mSurfaceControl, 0)
                .setCornerRadius(mSurfaceControl, 0);
    }

    void maybeApplyLastRecentsAnimationTransaction() {
        if (mLastRecentsAnimationTransaction != null) {
            final SurfaceControl.Transaction tx = getPendingTransaction();
            if (mLastRecentsAnimationOverlay != null) {
                tx.reparent(mLastRecentsAnimationOverlay, mSurfaceControl);
            }
            PictureInPictureSurfaceTransaction.apply(mLastRecentsAnimationTransaction,
                    mSurfaceControl, tx);
            // If we are transferring the transform from the root task entering PIP, then also show
            // the new task immediately
            tx.show(mSurfaceControl);
            mLastRecentsAnimationTransaction = null;
            mLastRecentsAnimationOverlay = null;
        }
    }

    private void updateSurfaceBounds() {
        updateSurfaceSize(getSyncTransaction());
        updateSurfacePositionNonOrganized();
        scheduleAnimation();
    }

    private Point getRelativePosition() {
        Point position = new Point();
        getRelativePosition(position);
        return position;
    }

    boolean shouldIgnoreInput() {
        if (mAtmService.mHasLeanbackFeature && inPinnedWindowingMode()
                && !isFocusedRootTaskOnDisplay()) {
            // Preventing Picture-in-Picture root task from receiving input on TVs.
            return true;
        }
        return false;
    }

    /**
     * Simply check and give warning logs if this is not operated on leaf task.
     */
    private void warnForNonLeafTask(String func) {
        if (!isLeafTask()) {
            Slog.w(TAG, func + " on non-leaf task " + this);
        }
    }

    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    AnimatingActivityRegistry getAnimatingActivityRegistry() {
        return mAnimatingActivityRegistry;
    }

    @Override
    void executeAppTransition(ActivityOptions options) {
        mDisplayContent.executeAppTransition();
        ActivityOptions.abort(options);
    }

    boolean shouldSleepActivities() {
        final DisplayContent display = mDisplayContent;
        final boolean isKeyguardGoingAway = (mDisplayContent != null)
                ? mDisplayContent.isKeyguardGoingAway()
                : mRootWindowContainer.getDefaultDisplay().isKeyguardGoingAway();

        // Do not sleep activities in this root task if we're marked as focused and the keyguard
        // is in the process of going away.
        if (isKeyguardGoingAway && isFocusedRootTaskOnDisplay()
                // Avoid resuming activities on secondary displays since we don't want bubble
                // activities to be resumed while bubble is still collapsed.
                // TODO(b/113840485): Having keyguard going away state for secondary displays.
                && display != null
                && display.isDefaultDisplay) {
            return false;
        }

        return display != null ? display.isSleeping() : mAtmService.isSleepingLocked();
    }

    private Rect getRawBounds() {
        return super.getBounds();
    }

    void dispatchTaskInfoChangedIfNeeded(boolean force) {
        if (isOrganized()) {
            mAtmService.mTaskOrganizerController.onTaskInfoChanged(this, force);
        }
    }

    void setReparentLeafTaskIfRelaunch(boolean reparentLeafTaskIfRelaunch) {
        if (isOrganized()) {
            mReparentLeafTaskIfRelaunch = reparentLeafTaskIfRelaunch;
        }
    }

    /**
     * Return true if the activityInfo has the same requiredDisplayCategory as this task.
     */
    boolean isSameRequiredDisplayCategory(@NonNull ActivityInfo info) {
        return mRequiredDisplayCategory != null && mRequiredDisplayCategory.equals(
                info.requiredDisplayCategory)
                || (mRequiredDisplayCategory == null && info.requiredDisplayCategory == null);
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);

        proto.write(TaskProto.ID, mTaskId);
        proto.write(ROOT_TASK_ID, getRootTaskId());

        if (getTopResumedActivity() != null) {
            getTopResumedActivity().writeIdentifierToProto(proto, RESUMED_ACTIVITY);
        }
        if (realActivity != null) {
            proto.write(REAL_ACTIVITY, realActivity.flattenToShortString());
        }
        if (origActivity != null) {
            proto.write(ORIG_ACTIVITY, origActivity.flattenToShortString());
        }
        proto.write(RESIZE_MODE, mResizeMode);
        proto.write(FILLS_PARENT, matchParentBounds());
        getRawBounds().dumpDebug(proto, BOUNDS);

        if (mLastNonFullscreenBounds != null) {
            mLastNonFullscreenBounds.dumpDebug(proto, LAST_NON_FULLSCREEN_BOUNDS);
        }

        if (mSurfaceControl != null) {
            proto.write(SURFACE_WIDTH, mSurfaceControl.getWidth());
            proto.write(SURFACE_HEIGHT, mSurfaceControl.getHeight());
        }

        proto.write(CREATED_BY_ORGANIZER, mCreatedByOrganizer);
        proto.write(AFFINITY, affinity);
        proto.write(HAS_CHILD_PIP_ACTIVITY, mChildPipActivity != null);

        super.dumpDebug(proto, TASK_FRAGMENT, logLevel);

        proto.end(token);
    }

    static class Builder {
        private final ActivityTaskManagerService mAtmService;
        private WindowContainer mParent;
        private int mTaskId;
        private Intent mIntent;
        private Intent mAffinityIntent;
        private String mAffinity;
        private String mRootAffinity;
        private ComponentName mRealActivity;
        private ComponentName mOrigActivity;
        private boolean mRootWasReset;
        private boolean mAutoRemoveRecents;
        private int mUserId;
        private int mEffectiveUid;
        private String mLastDescription;
        private long mLastTimeMoved;
        private boolean mNeverRelinquishIdentity;
        private TaskDescription mLastTaskDescription;
        private PersistedTaskSnapshotData mLastSnapshotData;
        private int mTaskAffiliation;
        private int mPrevAffiliateTaskId = INVALID_TASK_ID;
        private int mNextAffiliateTaskId = INVALID_TASK_ID;
        private int mCallingUid;
        private String mCallingPackage;
        private String mCallingFeatureId;
        private int mResizeMode;
        private boolean mSupportsPictureInPicture;
        private boolean mRealActivitySuspended;
        private boolean mUserSetupComplete;
        private int mMinWidth = INVALID_MIN_SIZE;
        private int mMinHeight = INVALID_MIN_SIZE;
        private ActivityInfo mActivityInfo;
        private ActivityOptions mActivityOptions;
        private IVoiceInteractionSession mVoiceSession;
        private IVoiceInteractor mVoiceInteractor;
        private int mActivityType;
        private int mWindowingMode = WINDOWING_MODE_UNDEFINED;
        private boolean mCreatedByOrganizer;
        private boolean mDeferTaskAppear;
        private IBinder mLaunchCookie;
        private boolean mOnTop;
        private boolean mHasBeenVisible;
        private boolean mRemoveWithTaskOrganizer;

        /**
         * Records the source task that requesting to build a new task, used to determine which of
         * the adjacent roots should be launch root of the new task.
         */
        private Task mSourceTask;

        /**
         * Records launch flags to apply when launching new task.
         */
        private int mLaunchFlags;

        Builder(ActivityTaskManagerService atm) {
            mAtmService = atm;
        }

        Builder setParent(WindowContainer parent) {
            mParent = parent;
            return this;
        }

        Builder setSourceTask(Task sourceTask) {
            mSourceTask = sourceTask;
            return this;
        }

        Builder setLaunchFlags(int launchFlags) {
            mLaunchFlags = launchFlags;
            return this;
        }

        Builder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        Builder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        Builder setRealActivity(ComponentName realActivity) {
            mRealActivity = realActivity;
            return this;
        }

        Builder setEffectiveUid(int effectiveUid) {
            mEffectiveUid = effectiveUid;
            return this;
        }

        Builder setMinWidth(int minWidth) {
            mMinWidth = minWidth;
            return this;
        }

        Builder setMinHeight(int minHeight) {
            mMinHeight = minHeight;
            return this;
        }

        Builder setActivityInfo(ActivityInfo info) {
            mActivityInfo = info;
            return this;
        }

        Builder setActivityOptions(ActivityOptions opts) {
            mActivityOptions = opts;
            return this;
        }

        Builder setVoiceSession(IVoiceInteractionSession voiceSession) {
            mVoiceSession = voiceSession;
            return this;
        }

        Builder setActivityType(int activityType) {
            mActivityType = activityType;
            return this;
        }

        int getActivityType() {
            return mActivityType;
        }

        Builder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        int getWindowingMode() {
            return mWindowingMode;
        }

        Builder setCreatedByOrganizer(boolean createdByOrganizer) {
            mCreatedByOrganizer = createdByOrganizer;
            return this;
        }

        boolean getCreatedByOrganizer() {
            return mCreatedByOrganizer;
        }

        Builder setDeferTaskAppear(boolean defer) {
            mDeferTaskAppear = defer;
            return this;
        }

        Builder setLaunchCookie(IBinder launchCookie) {
            mLaunchCookie = launchCookie;
            return this;
        }

        Builder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        Builder setHasBeenVisible(boolean hasBeenVisible) {
            mHasBeenVisible = hasBeenVisible;
            return this;
        }

        Builder setRemoveWithTaskOrganizer(boolean removeWithTaskOrganizer) {
            mRemoveWithTaskOrganizer = removeWithTaskOrganizer;
            return this;
        }

        private Builder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        private Builder setLastTimeMoved(long lastTimeMoved) {
            mLastTimeMoved = lastTimeMoved;
            return this;
        }

        private Builder setNeverRelinquishIdentity(boolean neverRelinquishIdentity) {
            mNeverRelinquishIdentity = neverRelinquishIdentity;
            return this;
        }

        private Builder setCallingUid(int callingUid) {
            mCallingUid = callingUid;
            return this;
        }

        private Builder setCallingPackage(String callingPackage) {
            mCallingPackage = callingPackage;
            return this;
        }

        private Builder setResizeMode(int resizeMode) {
            mResizeMode = resizeMode;
            return this;
        }

        private Builder setSupportsPictureInPicture(boolean supportsPictureInPicture) {
            mSupportsPictureInPicture = supportsPictureInPicture;
            return this;
        }

        private Builder setUserSetupComplete(boolean userSetupComplete) {
            mUserSetupComplete = userSetupComplete;
            return this;
        }

        private Builder setTaskAffiliation(int taskAffiliation) {
            mTaskAffiliation = taskAffiliation;
            return this;
        }

        private Builder setPrevAffiliateTaskId(int prevAffiliateTaskId) {
            mPrevAffiliateTaskId = prevAffiliateTaskId;
            return this;
        }

        private Builder setNextAffiliateTaskId(int nextAffiliateTaskId) {
            mNextAffiliateTaskId = nextAffiliateTaskId;
            return this;
        }

        private Builder setCallingFeatureId(String callingFeatureId) {
            mCallingFeatureId = callingFeatureId;
            return this;
        }

        private Builder setRealActivitySuspended(boolean realActivitySuspended) {
            mRealActivitySuspended = realActivitySuspended;
            return this;
        }

        private Builder setLastDescription(String lastDescription) {
            mLastDescription = lastDescription;
            return this;
        }

        private Builder setLastTaskDescription(TaskDescription lastTaskDescription) {
            mLastTaskDescription = lastTaskDescription;
            return this;
        }

        private Builder setLastSnapshotData(PersistedTaskSnapshotData lastSnapshotData) {
            mLastSnapshotData = lastSnapshotData;
            return this;
        }

        private Builder setOrigActivity(ComponentName origActivity) {
            mOrigActivity = origActivity;
            return this;
        }

        private Builder setRootWasReset(boolean rootWasReset) {
            mRootWasReset = rootWasReset;
            return this;
        }

        private Builder setAutoRemoveRecents(boolean autoRemoveRecents) {
            mAutoRemoveRecents = autoRemoveRecents;
            return this;
        }

        private Builder setAffinityIntent(Intent affinityIntent) {
            mAffinityIntent = affinityIntent;
            return this;
        }

        private Builder setAffinity(String affinity) {
            mAffinity = affinity;
            return this;
        }

        private Builder setRootAffinity(String rootAffinity) {
            mRootAffinity = rootAffinity;
            return this;
        }

        private Builder setVoiceInteractor(IVoiceInteractor voiceInteractor) {
            mVoiceInteractor = voiceInteractor;
            return this;
        }

        private void validateRootTask(TaskDisplayArea tda) {
            if (mActivityType == ACTIVITY_TYPE_UNDEFINED && !mCreatedByOrganizer) {
                // Can't have an undefined root task type yet...so re-map to standard. Anyone
                // that wants anything else should be passing it in anyways...except for the task
                // organizer.
                mActivityType = ACTIVITY_TYPE_STANDARD;
            }

            if (!DisplayContent.alwaysCreateRootTask(tda.getWindowingMode(), mActivityType)
                    && mActivityType != ACTIVITY_TYPE_UNDEFINED) {
                // Only Recents or Standard activity types are allowed to have more than one
                // root task on a display, this is independent of whatever windowing mode it
                // is currently in.
                Task rootTask = tda.getRootTask(WINDOWING_MODE_UNDEFINED, mActivityType);
                if (rootTask != null) {
                    throw new IllegalArgumentException("Root task=" + rootTask + " of activityType="
                            + mActivityType + " already on display=" + tda
                            + ". Can't have multiple.");
                }
            }

            if (!TaskDisplayArea.isWindowingModeSupported(mWindowingMode,
                    mAtmService.mSupportsMultiWindow,
                    mAtmService.mSupportsFreeformWindowManagement,
                    mAtmService.mSupportsPictureInPicture)) {
                throw new IllegalArgumentException("Can't create root task for unsupported "
                        + "windowingMode=" + mWindowingMode);
            }

            if (mWindowingMode == WINDOWING_MODE_PINNED
                    && mActivityType != ACTIVITY_TYPE_STANDARD) {
                throw new IllegalArgumentException(
                        "Root task with pinned windowing mode cannot with "
                                + "non-standard activity type.");
            }

            if (mWindowingMode == WINDOWING_MODE_PINNED && tda.getRootPinnedTask() != null) {
                // Only 1 root task can be PINNED at a time, so dismiss the existing one
                tda.getRootPinnedTask().dismissPip();
            }

            if (mIntent != null) {
                mLaunchFlags |= mIntent.getFlags();
            }

            // Task created by organizer are added as root.
            final Task launchRootTask = mCreatedByOrganizer
                    ? null : tda.getLaunchRootTask(mWindowingMode, mActivityType, mActivityOptions,
                    mSourceTask, mLaunchFlags);
            if (launchRootTask != null) {
                // Since this task will be put into a root task, its windowingMode will be
                // inherited.
                mWindowingMode = WINDOWING_MODE_UNDEFINED;
                mParent = launchRootTask;
            }

            mTaskId = tda.getNextRootTaskId();
        }

        Task build() {
            if (mParent != null && mParent instanceof TaskDisplayArea) {
                validateRootTask((TaskDisplayArea) mParent);
            }

            if (mActivityInfo == null) {
                mActivityInfo = new ActivityInfo();
                mActivityInfo.applicationInfo = new ApplicationInfo();
            }

            mUserId = UserHandle.getUserId(mActivityInfo.applicationInfo.uid);
            mTaskAffiliation = mTaskId;
            mLastTimeMoved = System.currentTimeMillis();
            mNeverRelinquishIdentity = true;
            mCallingUid = mActivityInfo.applicationInfo.uid;
            mCallingPackage = mActivityInfo.packageName;
            mResizeMode = mActivityInfo.resizeMode;
            mSupportsPictureInPicture = mActivityInfo.supportsPictureInPicture();
            if (!mRemoveWithTaskOrganizer && mActivityOptions != null) {
                mRemoveWithTaskOrganizer = mActivityOptions.getRemoveWithTaskOranizer();
            }

            final Task task = buildInner();
            task.mHasBeenVisible = mHasBeenVisible;

            // Set activity type before adding the root task to TaskDisplayArea, so home task can
            // be cached, see TaskDisplayArea#addRootTaskReferenceIfNeeded().
            if (mActivityType != ACTIVITY_TYPE_UNDEFINED) {
                task.setActivityType(mActivityType);
            }

            if (mParent != null) {
                if (mParent instanceof Task) {
                    final Task parentTask = (Task) mParent;
                    parentTask.addChild(task, mOnTop ? POSITION_TOP : POSITION_BOTTOM,
                            (mActivityInfo.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
                } else {
                    mParent.addChild(task, mOnTop ? POSITION_TOP : POSITION_BOTTOM);
                }
            }

            // Set windowing mode after attached to display area or it abort silently.
            if (mWindowingMode != WINDOWING_MODE_UNDEFINED) {
                task.setWindowingMode(mWindowingMode, true /* creating */);
            }
            return task;
        }

        /** Don't use {@link Builder#buildInner()} directly. This is only used by XML parser. */
        @VisibleForTesting
        Task buildInner() {
            return new Task(mAtmService, mTaskId, mIntent, mAffinityIntent, mAffinity,
                    mRootAffinity, mRealActivity, mOrigActivity, mRootWasReset, mAutoRemoveRecents,
                    mUserId, mEffectiveUid, mLastDescription, mLastTimeMoved,
                    mNeverRelinquishIdentity, mLastTaskDescription, mLastSnapshotData,
                    mTaskAffiliation, mPrevAffiliateTaskId, mNextAffiliateTaskId, mCallingUid,
                    mCallingPackage, mCallingFeatureId, mResizeMode, mSupportsPictureInPicture,
                    mRealActivitySuspended, mUserSetupComplete, mMinWidth, mMinHeight,
                    mActivityInfo, mVoiceSession, mVoiceInteractor, mCreatedByOrganizer,
                    mLaunchCookie, mDeferTaskAppear, mRemoveWithTaskOrganizer);
        }
    }

    @Override
    void updateOverlayInsetsState(WindowState originalChange) {
        super.updateOverlayInsetsState(originalChange);
        if (originalChange != getTopVisibleAppMainWindow()) {
            return;
        }
        if (mOverlayHost != null) {
            final InsetsState s = originalChange.getInsetsState(true);
            getBounds(mTmpRect);
            mOverlayHost.dispatchInsetsChanged(s, mTmpRect);
        }
    }

    @Nullable
    ActivityRecord getBottomMostActivityInSamePackage() {
        if (realActivity == null) {
            return null;
        }
        return getActivity(ar -> ar.packageName.equals(
                realActivity.getPackageName()), false /* traverseTopToBottom */);
    }

    /**
     * Associates the decor surface with the given TF, or create one if there
     * isn't one in the Task yet. The surface will be removed with the TF,
     * and become invisible if the TF is invisible. */
    void moveOrCreateDecorSurfaceFor(TaskFragment taskFragment) {
        if (mDecorSurfaceContainer != null) {
            mDecorSurfaceContainer.mOwnerTaskFragment = taskFragment;
        } else {
            mDecorSurfaceContainer = new DecorSurfaceContainer(taskFragment);
            assignChildLayers();
            sendTaskFragmentParentInfoChangedIfNeeded();
        }
    }

    void removeDecorSurface() {
        if (mDecorSurfaceContainer == null) {
            return;
        }
        mDecorSurfaceContainer.release();
        mDecorSurfaceContainer = null;
        sendTaskFragmentParentInfoChangedIfNeeded();
    }

    @Nullable SurfaceControl getDecorSurface() {
        return mDecorSurfaceContainer != null ? mDecorSurfaceContainer.mDecorSurface : null;
    }

    /**
     * A class managing the decor surface.
     *
     * A decor surface is requested by a {@link TaskFragmentOrganizer} and is placed below children
     * windows in the Task except for own Activities and TaskFragments in fully trusted mode. The
     * decor surface is created and shared with the client app with
     * {@link android.window.TaskFragmentOperation#OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE}
     * and be removed with
     * {@link android.window.TaskFragmentOperation#OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE}.
     *
     * When boosted with
     * {@link android.window.TaskFragmentOperation#OP_TYPE_SET_DECOR_SURFACE_BOOSTED}, the decor
     * surface is placed above all non-boosted windows in the Task, but all the content below it
     * will be hidden to prevent UI redressing attacks. This can be used by the draggable
     * divider between {@link TaskFragment}s where veils are drawn on the decor surface while
     * dragging to indicate new bounds.
     */
    @VisibleForTesting
    class DecorSurfaceContainer {

        // The container surface is the parent of the decor surface. The container surface
        // should NEVER be shared with the client. It is used to ensure that the decor surface has
        // a z-order in the Task that is managed by WM core and cannot be updated by the client
        // process.
        @VisibleForTesting
        @NonNull final SurfaceControl mContainerSurface;

        // The decor surface is shared with the client process owning the
        // {@link TaskFragmentOrganizer}. It can be used to draw the divider between TaskFragments
        // or other decorations.
        @VisibleForTesting
        @NonNull final SurfaceControl mDecorSurface;

        // The TaskFragment that requested the decor surface. If it is destroyed, the decor surface
        // is also released.
        @VisibleForTesting
        @NonNull TaskFragment mOwnerTaskFragment;

        private boolean mIsBoosted;

        // The surface transactions that will be applied when the layer is reassigned.
        @NonNull private final List<SurfaceControl.Transaction> mPendingClientTransactions =
                new ArrayList<>();

        private DecorSurfaceContainer(@NonNull TaskFragment initialOwner) {
            mOwnerTaskFragment = initialOwner;
            mContainerSurface = makeSurface().setContainerLayer()
                    .setParent(mSurfaceControl)
                    .setName(mSurfaceControl + " - decor surface container")
                    .setContainerLayer()
                    .setHidden(false)
                    .setCallsite("Task.DecorSurfaceContainer")
                    .build();

            mDecorSurface = makeSurface()
                    .setParent(mContainerSurface)
                    .setName(mSurfaceControl + " - decor surface")
                    .setHidden(false)
                    .setCallsite("Task.DecorSurfaceContainer")
                    .build();
        }

        private void setBoosted(
                boolean isBoosted, @Nullable SurfaceControl.Transaction clientTransaction) {
            mIsBoosted = isBoosted;
            // The client transaction will be applied together with the next assignLayer.
            if (clientTransaction != null) {
                mPendingClientTransactions.add(clientTransaction);
            }
        }

        private void assignLayer(@NonNull SurfaceControl.Transaction t, int layer) {
            t.setLayer(mContainerSurface, layer);
            t.setVisibility(mContainerSurface, mOwnerTaskFragment.isVisible() || mIsBoosted);
            for (int i = 0; i < mPendingClientTransactions.size(); i++) {
                t.merge(mPendingClientTransactions.get(i));
            }
            mPendingClientTransactions.clear();
        }

        private void release() {
            getSyncTransaction()
                    .remove(mDecorSurface)
                    .remove(mContainerSurface);
        }
    }
}
