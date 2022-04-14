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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.res.Configuration.EMPTY;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_APP_CRASHED;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.TRANSIT_TO_BACK;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_KEEP_SCREEN_ON;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TASKS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WALLPAPER;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_SURFACE_ALLOC;
import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.policy.PhoneWindowManager.SYSTEM_DIALOG_REASON_ASSIST;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityRecord.State.FINISHING;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerService.ANIMATE;
import static com.android.server.wm.ActivityTaskManagerService.TAG_SWITCH;
import static com.android.server.wm.ActivityTaskSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityTaskSupervisor.printThisActivity;
import static com.android.server.wm.KeyguardController.KEYGUARD_SLEEP_TOKEN_TAG;
import static com.android.server.wm.RootWindowContainerProto.IS_HOME_RECENTS_COMPONENT;
import static com.android.server.wm.RootWindowContainerProto.KEYGUARD_CONTROLLER;
import static com.android.server.wm.RootWindowContainerProto.WINDOW_CONTAINER;
import static com.android.server.wm.Task.REPARENT_LEAVE_ROOT_TASK_IN_PLACE;
import static com.android.server.wm.Task.REPARENT_MOVE_ROOT_TASK_TO_FRONT;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_INVISIBLE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WINDOW_FREEZE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_PLACING_SURFACES;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_NONE;
import static com.android.server.wm.WindowSurfacePlacer.SET_UPDATE_ROTATION;
import static com.android.server.wm.WindowSurfacePlacer.SET_WALLPAPER_ACTION_PENDING;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.AppGlobals;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.power.Mode;
import android.net.Uri;
import android.os.Binder;
import android.os.Debug;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.UserState;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.WindowManagerPolicy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Root {@link WindowContainer} for the device. */
class RootWindowContainer extends WindowContainer<DisplayContent>
        implements DisplayManager.DisplayListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RootWindowContainer" : TAG_WM;

    private static final int SET_SCREEN_BRIGHTNESS_OVERRIDE = 1;
    private static final int SET_USER_ACTIVITY_TIMEOUT = 2;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;

    private Object mLastWindowFreezeSource = null;
    private Session mHoldScreen = null;
    private float mScreenBrightnessOverride = PowerManager.BRIGHTNESS_INVALID_FLOAT;
    private long mUserActivityTimeout = -1;
    private boolean mUpdateRotation = false;
    // Following variables are for debugging screen wakelock only.
    // Last window that requires screen wakelock
    WindowState mHoldScreenWindow = null;
    // Last window that obscures all windows below
    WindowState mObscuringWindow = null;
    // Only set while traversing the default display based on its content.
    // Affects the behavior of mirroring on secondary displays.
    private boolean mObscureApplicationContentOnSecondaryDisplays = false;

    private boolean mSustainedPerformanceModeEnabled = false;
    private boolean mSustainedPerformanceModeCurrent = false;

    // During an orientation change, we track whether all windows have rendered
    // at the new orientation, and this will be false from changing orientation until that occurs.
    // For seamless rotation cases this always stays true, as the windows complete their orientation
    // changes 1 by 1 without disturbing global state.
    boolean mOrientationChangeComplete = true;
    boolean mWallpaperActionPending = false;

    private final Handler mHandler;

    private String mCloseSystemDialogsReason;

    // The ID of the display which is responsible for receiving display-unspecified key and pointer
    // events.
    private int mTopFocusedDisplayId = INVALID_DISPLAY;

    // Map from the PID to the top most app which has a focused window of the process.
    final ArrayMap<Integer, ActivityRecord> mTopFocusedAppByProcess = new ArrayMap<>();

    // Only a separate transaction until we separate the apply surface changes
    // transaction from the global transaction.
    private final SurfaceControl.Transaction mDisplayTransaction;

    // The tag for the token to put root tasks on the displays to sleep.
    private static final String DISPLAY_OFF_SLEEP_TOKEN_TAG = "Display-off";

    /** The token acquirer to put root tasks on the displays to sleep */
    final ActivityTaskManagerInternal.SleepTokenAcquirer mDisplayOffTokenAcquirer;

    /**
     * The modes which affect which tasks are returned when calling
     * {@link RootWindowContainer#anyTaskForId(int)}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MATCH_ATTACHED_TASK_ONLY,
            MATCH_ATTACHED_TASK_OR_RECENT_TASKS,
            MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE
    })
    public @interface AnyTaskForIdMatchTaskMode {
    }

    // Match only tasks that are attached to the hierarchy
    static final int MATCH_ATTACHED_TASK_ONLY = 0;
    // Match either attached tasks, or in the recent tasks if the tasks are detached
    static final int MATCH_ATTACHED_TASK_OR_RECENT_TASKS = 1;
    // Match either attached tasks, or in the recent tasks, restoring it to the provided task id
    static final int MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE = 2;

    ActivityTaskManagerService mService;
    ActivityTaskSupervisor mTaskSupervisor;
    WindowManagerService mWindowManager;
    DisplayManager mDisplayManager;
    private DisplayManagerInternal mDisplayManagerInternal;

    /** Reference to default display so we can quickly look it up. */
    private DisplayContent mDefaultDisplay;
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();

    /** The current user */
    int mCurrentUser;
    /** Root task id of the front root task when user switched, indexed by userId. */
    SparseIntArray mUserRootTaskInFront = new SparseIntArray(2);

    /**
     * A list of tokens that cause the top activity to be put to sleep.
     * They are used by components that may hide and block interaction with underlying
     * activities.
     */
    final SparseArray<SleepToken> mSleepTokens = new SparseArray<>();

    // The default minimal size that will be used if the activity doesn't specify its minimal size.
    // It will be calculated when the default display gets added.
    int mDefaultMinSizeOfResizeableTaskDp = -1;

    // Whether tasks have moved and we need to rank the tasks before next OOM scoring
    private boolean mTaskLayersChanged = true;
    private int mTmpTaskLayerRank;
    private final RankTaskLayersRunnable mRankTaskLayersRunnable = new RankTaskLayersRunnable();

    private final AttachApplicationHelper mAttachApplicationHelper = new AttachApplicationHelper();

    private String mDestroyAllActivitiesReason;
    private final Runnable mDestroyAllActivitiesRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mService.mGlobalLock) {
                try {
                    mTaskSupervisor.beginDeferResume();

                    final PooledConsumer c = PooledLambda.obtainConsumer(
                            RootWindowContainer::destroyActivity, RootWindowContainer.this,
                            PooledLambda.__(ActivityRecord.class));
                    forAllActivities(c);
                    c.recycle();
                } finally {
                    mTaskSupervisor.endDeferResume();
                    resumeFocusedTasksTopActivities();
                }
            }
        }

    };

    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();

    static class FindTaskResult implements Predicate<Task> {
        ActivityRecord mIdealRecord;
        ActivityRecord mCandidateRecord;

        private int mActivityType;
        private String mTaskAffinity;
        private Intent mIntent;
        private ActivityInfo mInfo;
        private ComponentName cls;
        private int userId;
        private boolean isDocument;
        private Uri documentData;

        void init(int activityType, String taskAffinity, Intent intent, ActivityInfo info) {
            mActivityType = activityType;
            mTaskAffinity = taskAffinity;
            mIntent = intent;
            mInfo = info;
            mIdealRecord = null;
            mCandidateRecord = null;
        }

        /**
         * Returns the top activity in any existing task matching the given Intent in the input
         * result. Returns null if no such task is found.
         */
        void process(WindowContainer parent) {
            cls = mIntent.getComponent();
            if (mInfo.targetActivity != null) {
                cls = new ComponentName(mInfo.packageName, mInfo.targetActivity);
            }
            userId = UserHandle.getUserId(mInfo.applicationInfo.uid);
            isDocument = mIntent != null & mIntent.isDocument();
            // If documentData is non-null then it must match the existing task data.
            documentData = isDocument ? mIntent.getData() : null;

            ProtoLog.d(WM_DEBUG_TASKS, "Looking for task of %s in %s", mInfo,
                    parent);
            parent.forAllLeafTasks(this);
        }

        @Override
        public boolean test(Task task) {
            if (!ConfigurationContainer.isCompatibleActivityType(mActivityType,
                    task.getActivityType())) {
                ProtoLog.d(WM_DEBUG_TASKS, "Skipping task: (mismatch activity/task) %s", task);
                return false;
            }

            if (task.voiceSession != null) {
                // We never match voice sessions; those always run independently.
                ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: voice session", task);
                return false;
            }
            if (task.mUserId != userId) {
                // Looking for a different task.
                ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: different user", task);
                return false;
            }

            if (matchingCandidate(task)) {
                return true;
            }

            // Looking for the embedded tasks (if any)
            return !task.isLeafTaskFragment() && task.forAllLeafTaskFragments(
                    this::matchingCandidate);
        }

        boolean matchingCandidate(TaskFragment taskFragment) {
            final Task task = taskFragment.asTask();
            if (task == null) {
                return false;
            }

            // Overlays should not be considered as the task's logical top activity.
            // Activities of the tasks that embedded from this one should not be used.
            final ActivityRecord r = task.getTopNonFinishingActivity(false /* includeOverlays */,
                    false /* includingEmbeddedTask */);

            if (r == null || r.finishing || r.mUserId != userId
                    || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: mismatch root %s", task, r);
                return false;
            }
            if (!ConfigurationContainer.isCompatibleActivityType(r.getActivityType(),
                    mActivityType)) {
                ProtoLog.d(WM_DEBUG_TASKS, "Skipping %s: mismatch activity type", task);
                return false;
            }

            final Intent taskIntent = task.intent;
            final Intent affinityIntent = task.affinityIntent;
            final boolean taskIsDocument;
            final Uri taskDocumentData;
            if (taskIntent != null && taskIntent.isDocument()) {
                taskIsDocument = true;
                taskDocumentData = taskIntent.getData();
            } else if (affinityIntent != null && affinityIntent.isDocument()) {
                taskIsDocument = true;
                taskDocumentData = affinityIntent.getData();
            } else {
                taskIsDocument = false;
                taskDocumentData = null;
            }

            ProtoLog.d(WM_DEBUG_TASKS, "Comparing existing cls=%s /aff=%s to new cls=%s /aff=%s",
                    r.getTask().rootAffinity, mIntent.getComponent().flattenToShortString(),
                    mInfo.taskAffinity, (task.realActivity != null
                            ? task.realActivity.flattenToShortString() : ""));
            // TODO Refactor to remove duplications. Check if logic can be simplified.
            if (task.realActivity != null && task.realActivity.compareTo(cls) == 0
                    && Objects.equals(documentData, taskDocumentData)) {
                ProtoLog.d(WM_DEBUG_TASKS, "Found matching class!");
                //dump();
                ProtoLog.d(WM_DEBUG_TASKS, "For Intent %s bringing to top: %s", mIntent, r.intent);
                mIdealRecord = r;
                return true;
            } else if (affinityIntent != null && affinityIntent.getComponent() != null
                    && affinityIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                ProtoLog.d(WM_DEBUG_TASKS, "Found matching class!");
                ProtoLog.d(WM_DEBUG_TASKS, "For Intent %s bringing to top: %s", mIntent, r.intent);
                mIdealRecord = r;
                return true;
            } else if (!isDocument && !taskIsDocument
                    && mIdealRecord == null && mCandidateRecord == null
                    && task.rootAffinity != null) {
                if (task.rootAffinity.equals(mTaskAffinity)) {
                    ProtoLog.d(WM_DEBUG_TASKS, "Found matching affinity candidate!");
                    // It is possible for multiple tasks to have the same root affinity especially
                    // if they are in separate root tasks. We save off this candidate, but keep
                    // looking to see if there is a better candidate.
                    mCandidateRecord = r;
                }
            } else {
                ProtoLog.d(WM_DEBUG_TASKS, "Not a match: %s", task);
            }

            return false;
        }
    }

    private final Consumer<WindowState> mCloseSystemDialogsConsumer = w -> {
        if (w.mHasSurface) {
            try {
                w.mClient.closeSystemDialogs(mCloseSystemDialogsReason);
            } catch (RemoteException e) {
            }
        }
    };

    private static final Consumer<WindowState> sRemoveReplacedWindowsConsumer = w -> {
        final ActivityRecord activity = w.mActivityRecord;
        if (activity != null) {
            activity.removeReplacedWindowIfNeeded(w);
        }
    };

    RootWindowContainer(WindowManagerService service) {
        super(service);
        mDisplayTransaction = service.mTransactionFactory.get();
        mHandler = new MyHandler(service.mH.getLooper());
        mService = service.mAtmService;
        mTaskSupervisor = mService.mTaskSupervisor;
        mTaskSupervisor.mRootWindowContainer = this;
        mDisplayOffTokenAcquirer = mService.new SleepTokenAcquirerImpl(DISPLAY_OFF_SLEEP_TOKEN_TAG);
    }

    boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        mTopFocusedAppByProcess.clear();
        boolean changed = false;
        int topFocusedDisplayId = INVALID_DISPLAY;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            changed |= dc.updateFocusedWindowLocked(mode, updateInputWindows, topFocusedDisplayId);
            final WindowState newFocus = dc.mCurrentFocus;
            if (newFocus != null) {
                final int pidOfNewFocus = newFocus.mSession.mPid;
                if (mTopFocusedAppByProcess.get(pidOfNewFocus) == null) {
                    mTopFocusedAppByProcess.put(pidOfNewFocus, newFocus.mActivityRecord);
                }
                if (topFocusedDisplayId == INVALID_DISPLAY) {
                    topFocusedDisplayId = dc.getDisplayId();
                }
            } else if (topFocusedDisplayId == INVALID_DISPLAY && dc.mFocusedApp != null) {
                // The top-most display that has a focused app should still be the top focused
                // display even when the app window is not ready yet (process not attached or
                // window not added yet).
                topFocusedDisplayId = dc.getDisplayId();
            }
        }
        if (topFocusedDisplayId == INVALID_DISPLAY) {
            topFocusedDisplayId = DEFAULT_DISPLAY;
        }
        if (mTopFocusedDisplayId != topFocusedDisplayId) {
            mTopFocusedDisplayId = topFocusedDisplayId;
            mWmService.mInputManager.setFocusedDisplay(topFocusedDisplayId);
            mWmService.mPolicy.setTopFocusedDisplay(topFocusedDisplayId);
            mWmService.mAccessibilityController.setFocusedDisplay(topFocusedDisplayId);
            ProtoLog.d(WM_DEBUG_FOCUS_LIGHT, "New topFocusedDisplayId=%d", topFocusedDisplayId);
        }
        return changed;
    }

    DisplayContent getTopFocusedDisplayContent() {
        final DisplayContent dc = getDisplayContent(mTopFocusedDisplayId);
        return dc != null ? dc : getDisplayContent(DEFAULT_DISPLAY);
    }

    @Override
    boolean isOnTop() {
        // Considered always on top
        return true;
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                !mWmService.mPerDisplayFocusEnabled /* updateInputWindows */);
        mTaskSupervisor.updateTopResumedActivityIfNeeded();
    }

    @Override
    boolean isAttached() {
        return true;
    }

    /**
     * Called when DisplayWindowSettings values may change.
     */
    void onSettingsRetrieved() {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            final boolean changed = mWmService.mDisplayWindowSettings.updateSettingsForDisplay(
                    displayContent);
            if (!changed) {
                continue;
            }

            displayContent.reconfigureDisplayLocked();

            // We need to update global configuration as well if config of default display has
            // changed. Do it inline because ATMS#retrieveSettings() will soon update the
            // configuration inline, which will overwrite the new windowing mode.
            if (displayContent.isDefaultDisplay) {
                final Configuration newConfig = mWmService.computeNewConfiguration(
                        displayContent.getDisplayId());
                mWmService.mAtmService.updateConfigurationLocked(newConfig, null /* starting */,
                        false /* initLocale */);
            }
        }
    }

    boolean isLayoutNeeded() {
        final int numDisplays = mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.isLayoutNeeded()) {
                return true;
            }
        }
        return false;
    }

    void getWindowsByName(ArrayList<WindowState> output, String name) {
        int objectId = 0;
        // See if this is an object ID.
        try {
            objectId = Integer.parseInt(name, 16);
            name = null;
        } catch (RuntimeException e) {
        }

        getWindowsByName(output, name, objectId);
    }

    private void getWindowsByName(ArrayList<WindowState> output, String name, int objectId) {
        forAllWindows((w) -> {
            if (name != null) {
                if (w.mAttrs.getTitle().toString().contains(name)) {
                    output.add(w);
                }
            } else if (System.identityHashCode(w) == objectId) {
                output.add(w);
            }
        }, true /* traverseTopToBottom */);
    }

    /**
     * Returns the app window token for the input binder if it exist in the system.
     * NOTE: Only one AppWindowToken is allowed to exist in the system for a binder token, since
     * AppWindowToken represents an activity which can only exist on one display.
     */
    ActivityRecord getActivityRecord(IBinder binder) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            final ActivityRecord activity = dc.getActivityRecord(binder);
            if (activity != null) {
                return activity;
            }
        }
        return null;
    }

    /** Returns the window token for the input binder if it exist in the system. */
    WindowToken getWindowToken(IBinder binder) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            final WindowToken wtoken = dc.getWindowToken(binder);
            if (wtoken != null) {
                return wtoken;
            }
        }
        return null;
    }

    /** Returns the display object the input window token is currently mapped on. */
    DisplayContent getWindowTokenDisplay(WindowToken token) {
        if (token == null) {
            return null;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mChildren.get(i);
            final WindowToken current = dc.getWindowToken(token.token);
            if (current == token) {
                return dc;
            }
        }

        return null;
    }

    @Override
    void dispatchConfigurationToChild(DisplayContent child, Configuration config) {
        if (child.isDefaultDisplay) {
            // The global configuration is also the override configuration of default display.
            child.performDisplayOverrideConfigUpdate(config);
        } else {
            child.onConfigurationChanged(config);
        }
    }

    void setSecureSurfaceState(int userId) {
        forAllWindows((w) -> {
            if (w.mHasSurface && userId == w.mShowUserId) {
                w.mWinAnimator.setSecureLocked(w.isSecureLocked());
            }
        }, true /* traverseTopToBottom */);
    }

    void updateHiddenWhileSuspendedState(final ArraySet<String> packages, final boolean suspended) {
        forAllWindows((w) -> {
            if (packages.contains(w.getOwningPackage())) {
                w.setHiddenWhileSuspended(suspended);
            }
        }, false);
    }

    void updateAppOpsState() {
        forAllWindows((w) -> {
            w.updateAppOpsState();
        }, false /* traverseTopToBottom */);
    }

    boolean canShowStrictModeViolation(int pid) {
        final WindowState win = getWindow((w) -> w.mSession.mPid == pid && w.isVisible());
        return win != null;
    }

    void closeSystemDialogs(String reason) {
        mCloseSystemDialogsReason = reason;
        forAllWindows(mCloseSystemDialogsConsumer, false /* traverseTopToBottom */);
    }

    void removeReplacedWindows() {
        ProtoLog.i(WM_SHOW_TRANSACTIONS, ">>> OPEN TRANSACTION removeReplacedWindows");
        mWmService.openSurfaceTransaction();
        try {
            forAllWindows(sRemoveReplacedWindowsConsumer, true /* traverseTopToBottom */);
        } finally {
            mWmService.closeSurfaceTransaction("removeReplacedWindows");
            ProtoLog.i(WM_SHOW_TRANSACTIONS, "<<< CLOSE TRANSACTION removeReplacedWindows");
        }
    }

    boolean hasPendingLayoutChanges(WindowAnimator animator) {
        boolean hasChanges = false;

        final int count = mChildren.size();
        for (int i = 0; i < count; ++i) {
            final int pendingChanges = mChildren.get(i).pendingLayoutChanges;
            if ((pendingChanges & FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                animator.mBulkUpdateParams |= SET_WALLPAPER_ACTION_PENDING;
            }
            if (pendingChanges != 0) {
                hasChanges = true;
            }
        }

        return hasChanges;
    }

    boolean reclaimSomeSurfaceMemory(WindowStateAnimator winAnimator, String operation,
            boolean secure) {
        final WindowSurfaceController surfaceController = winAnimator.mSurfaceController;
        boolean leakedSurface = false;
        boolean killedApps = false;
        EventLogTags.writeWmNoSurfaceMemory(winAnimator.mWin.toString(),
                winAnimator.mSession.mPid, operation);
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            // There was some problem...first, do a validity check of the window list to make sure
            // we haven't left any dangling surfaces around.

            Slog.i(TAG_WM, "Out of memory for surface!  Looking for leaks...");
            final int numDisplays = mChildren.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                leakedSurface |= mChildren.get(displayNdx).destroyLeakedSurfaces();
            }

            if (!leakedSurface) {
                Slog.w(TAG_WM, "No leaked surfaces; killing applications!");
                final SparseIntArray pidCandidates = new SparseIntArray();
                for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                    mChildren.get(displayNdx).forAllWindows((w) -> {
                        if (mWmService.mForceRemoves.contains(w)) {
                            return;
                        }
                        final WindowStateAnimator wsa = w.mWinAnimator;
                        if (wsa.mSurfaceController != null) {
                            pidCandidates.append(wsa.mSession.mPid, wsa.mSession.mPid);
                        }
                    }, false /* traverseTopToBottom */);

                    if (pidCandidates.size() > 0) {
                        int[] pids = new int[pidCandidates.size()];
                        for (int i = 0; i < pids.length; i++) {
                            pids[i] = pidCandidates.keyAt(i);
                        }
                        try {
                            if (mWmService.mActivityManager.killPids(pids, "Free memory", secure)) {
                                killedApps = true;
                            }
                        } catch (RemoteException e) {
                        }
                    }
                }
            }

            if (leakedSurface || killedApps) {
                // We managed to reclaim some memory, so get rid of the trouble surface and ask the
                // app to request another one.
                Slog.w(TAG_WM,
                        "Looks like we have reclaimed some memory, clearing surface for retry.");
                if (surfaceController != null) {
                    ProtoLog.i(WM_SHOW_SURFACE_ALLOC,
                            "SURFACE RECOVER DESTROY: %s", winAnimator.mWin);
                    SurfaceControl.Transaction t = mWmService.mTransactionFactory.get();
                    winAnimator.destroySurface(t);
                    t.apply();
                    if (winAnimator.mWin.mActivityRecord != null) {
                        winAnimator.mWin.mActivityRecord.removeStartingWindow();
                    }
                }

                try {
                    winAnimator.mWin.mClient.dispatchGetNewSurface();
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }

        return leakedSurface || killedApps;
    }

    void performSurfacePlacement() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "performSurfacePlacement");
        try {
            performSurfacePlacementNoTrace();
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    // "Something has changed!  Let's make it correct now."
    // TODO: Super long method that should be broken down...
    void performSurfacePlacementNoTrace() {
        if (DEBUG_WINDOW_TRACE) {
            Slog.v(TAG, "performSurfacePlacementInner: entry. Called by "
                    + Debug.getCallers(3));
        }

        int i;

        if (mWmService.mFocusMayChange) {
            mWmService.mFocusMayChange = false;
            mWmService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_WILL_PLACE_SURFACES, false /*updateInputWindows*/);
        }

        mHoldScreen = null;
        mScreenBrightnessOverride = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mUserActivityTimeout = -1;
        mObscureApplicationContentOnSecondaryDisplays = false;
        mSustainedPerformanceModeCurrent = false;
        mWmService.mTransactionSequence++;

        // TODO(multi-display): recents animation & wallpaper need support multi-display.
        final DisplayContent defaultDisplay = mWmService.getDefaultDisplayContentLocked();
        final WindowSurfacePlacer surfacePlacer = mWmService.mWindowPlacerLocked;

        if (SHOW_LIGHT_TRANSACTIONS) {
            Slog.i(TAG,
                    ">>> OPEN TRANSACTION performLayoutAndPlaceSurfaces");
        }
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "applySurfaceChanges");
        mWmService.openSurfaceTransaction();
        try {
            applySurfaceChangesTransaction();
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            mWmService.closeSurfaceTransaction("performLayoutAndPlaceSurfaces");
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            if (SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG,
                        "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
            }
        }

        // Send any pending task-info changes that were queued-up during a layout deferment
        mWmService.mAtmService.mTaskOrganizerController.dispatchPendingEvents();
        mWmService.mAtmService.mTaskFragmentOrganizerController.dispatchPendingEvents();
        mWmService.mSyncEngine.onSurfacePlacement();
        mWmService.mAnimator.executeAfterPrepareSurfacesRunnables();

        checkAppTransitionReady(surfacePlacer);

        // Defer starting the recents animation until the wallpaper has drawn
        final RecentsAnimationController recentsAnimationController =
                mWmService.getRecentsAnimationController();
        if (recentsAnimationController != null) {
            recentsAnimationController.checkAnimationReady(defaultDisplay.mWallpaperController);
        }

        for (int displayNdx = 0; displayNdx < mChildren.size(); ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.mWallpaperMayChange) {
                ProtoLog.v(WM_DEBUG_WALLPAPER, "Wallpaper may change!  Adjusting");
                displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                if (DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("WallpaperMayChange",
                            displayContent.pendingLayoutChanges);
                }
            }
        }

        if (mWmService.mFocusMayChange) {
            mWmService.mFocusMayChange = false;
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_PLACING_SURFACES,
                    false /*updateInputWindows*/);
        }

        if (isLayoutNeeded()) {
            defaultDisplay.pendingLayoutChanges |= FINISH_LAYOUT_REDO_LAYOUT;
            if (DEBUG_LAYOUT_REPEATS) {
                surfacePlacer.debugLayoutRepeats("mLayoutNeeded",
                        defaultDisplay.pendingLayoutChanges);
            }
        }

        handleResizingWindows();

        if (mWmService.mDisplayFrozen) {
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "With display frozen, orientationChangeComplete=%b",
                    mOrientationChangeComplete);
        }
        if (mOrientationChangeComplete) {
            if (mWmService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                mWmService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_NONE;
                mWmService.mLastFinishedFreezeSource = mLastWindowFreezeSource;
                mWmService.mH.removeMessages(WINDOW_FREEZE_TIMEOUT);
            }
            mWmService.stopFreezingDisplayLocked();
        }

        // Destroy the surface of any windows that are no longer visible.
        i = mWmService.mDestroySurface.size();
        if (i > 0) {
            do {
                i--;
                WindowState win = mWmService.mDestroySurface.get(i);
                win.mDestroying = false;
                final DisplayContent displayContent = win.getDisplayContent();
                if (displayContent.mInputMethodWindow == win) {
                    displayContent.setInputMethodWindowLocked(null);
                }
                if (displayContent.mWallpaperController.isWallpaperTarget(win)) {
                    displayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                }
                win.destroySurfaceUnchecked();
            } while (i > 0);
            mWmService.mDestroySurface.clear();
        }

        for (int displayNdx = 0; displayNdx < mChildren.size(); ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.pendingLayoutChanges != 0) {
                displayContent.setLayoutNeeded();
            }
        }

        mWmService.setHoldScreenLocked(mHoldScreen);
        if (!mWmService.mDisplayFrozen) {
            final float brightnessOverride = mScreenBrightnessOverride < PowerManager.BRIGHTNESS_MIN
                    || mScreenBrightnessOverride > PowerManager.BRIGHTNESS_MAX
                    ? PowerManager.BRIGHTNESS_INVALID_FLOAT : mScreenBrightnessOverride;
            int brightnessFloatAsIntBits = Float.floatToIntBits(brightnessOverride);
            // Post these on a handler such that we don't call into power manager service while
            // holding the window manager lock to avoid lock contention with power manager lock.
            mHandler.obtainMessage(SET_SCREEN_BRIGHTNESS_OVERRIDE, brightnessFloatAsIntBits,
                    0).sendToTarget();
            mHandler.obtainMessage(SET_USER_ACTIVITY_TIMEOUT, mUserActivityTimeout).sendToTarget();
        }

        if (mSustainedPerformanceModeCurrent != mSustainedPerformanceModeEnabled) {
            mSustainedPerformanceModeEnabled = mSustainedPerformanceModeCurrent;
            mWmService.mPowerManagerInternal.setPowerMode(
                    Mode.SUSTAINED_PERFORMANCE,
                    mSustainedPerformanceModeEnabled);
        }

        if (mUpdateRotation) {
            ProtoLog.d(WM_DEBUG_ORIENTATION, "Performing post-rotate rotation");
            mUpdateRotation = updateRotationUnchecked();
        }

        if (!mWmService.mWaitingForDrawnCallbacks.isEmpty()
                || (mOrientationChangeComplete && !isLayoutNeeded()
                && !mUpdateRotation)) {
            mWmService.checkDrawnWindowsLocked();
        }

        forAllDisplays(dc -> {
            dc.getInputMonitor().updateInputWindowsLw(true /*force*/);
            dc.updateSystemGestureExclusion();
            dc.updateKeepClearAreas();
            dc.updateTouchExcludeRegion();
        });

        // Check to see if we are now in a state where the screen should
        // be enabled, because the window obscured flags have changed.
        mWmService.enableScreenIfNeededLocked();

        mWmService.scheduleAnimationLocked();

        if (DEBUG_WINDOW_TRACE) Slog.e(TAG, "performSurfacePlacementInner exit");
    }

    private void checkAppTransitionReady(WindowSurfacePlacer surfacePlacer) {
        // Trace all displays app transition by Z-order for pending layout change.
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent curDisplay = mChildren.get(i);

            // If we are ready to perform an app transition, check through all of the app tokens
            // to be shown and see if they are ready to go.
            if (curDisplay.mAppTransition.isReady()) {
                // handleAppTransitionReady may modify curDisplay.pendingLayoutChanges.
                curDisplay.mAppTransitionController.handleAppTransitionReady();
                if (DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after handleAppTransitionReady",
                            curDisplay.pendingLayoutChanges);
                }
            }

            if (curDisplay.mAppTransition.isRunning() && !curDisplay.isAppTransitioning()) {
                // We have finished the animation of an app transition. To do this, we have
                // delayed a lot of operations like showing and hiding apps, moving apps in
                // Z-order, etc.
                // The app token list reflects the correct Z-order, but the window list may now
                // be out of sync with it. So here we will just rebuild the entire app window
                // list. Fun!
                curDisplay.handleAnimatingStoppedAndTransition();
                if (DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after handleAnimStopAndXitionLock",
                            curDisplay.pendingLayoutChanges);
                }
            }
        }
    }

    private void applySurfaceChangesTransaction() {
        mHoldScreenWindow = null;
        mObscuringWindow = null;

        // TODO(multi-display): Support these features on secondary screens.
        final DisplayContent defaultDc = mWmService.getDefaultDisplayContentLocked();
        final DisplayInfo defaultInfo = defaultDc.getDisplayInfo();
        final int defaultDw = defaultInfo.logicalWidth;
        final int defaultDh = defaultInfo.logicalHeight;
        if (mWmService.mWatermark != null) {
            mWmService.mWatermark.positionSurface(defaultDw, defaultDh, mDisplayTransaction);
        }
        if (mWmService.mStrictModeFlash != null) {
            mWmService.mStrictModeFlash.positionSurface(defaultDw, defaultDh, mDisplayTransaction);
        }
        if (mWmService.mEmulatorDisplayOverlay != null) {
            mWmService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh,
                    mWmService.getDefaultDisplayRotation(), mDisplayTransaction);
        }

        final int count = mChildren.size();
        for (int j = 0; j < count; ++j) {
            final DisplayContent dc = mChildren.get(j);
            dc.applySurfaceChangesTransaction();
        }

        // Give the display manager a chance to adjust properties like display rotation if it needs
        // to.
        mWmService.mDisplayManagerInternal.performTraversal(mDisplayTransaction);
        SurfaceControl.mergeToGlobalTransaction(mDisplayTransaction);
    }

    /**
     * Handles resizing windows during surface placement.
     */
    private void handleResizingWindows() {
        for (int i = mWmService.mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = mWmService.mResizingWindows.get(i);
            if (win.mAppFreezing || win.getDisplayContent().mWaitingForConfig) {
                // Don't remove this window until rotation has completed and is not waiting for the
                // complete configuration.
                continue;
            }
            win.reportResized();
            mWmService.mResizingWindows.remove(i);
        }
    }

    /**
     * @param w        WindowState this method is applied to.
     * @param obscured True if there is a window on top of this obscuring the display.
     * @param syswin   System window?
     * @return True when the display contains content to show the user. When false, the display
     * manager may choose to mirror or blank the display.
     */
    boolean handleNotObscuredLocked(WindowState w, boolean obscured, boolean syswin) {
        final WindowManager.LayoutParams attrs = w.mAttrs;
        final int attrFlags = attrs.flags;
        final boolean onScreen = w.isOnScreen();
        final boolean canBeSeen = w.isDisplayed();
        final int privateflags = attrs.privateFlags;
        boolean displayHasContent = false;

        ProtoLog.d(WM_DEBUG_KEEP_SCREEN_ON,
                "handleNotObscuredLocked w: %s, w.mHasSurface: %b, w.isOnScreen(): %b, w"
                        + ".isDisplayedLw(): %b, w.mAttrs.userActivityTimeout: %d",
                w, w.mHasSurface, onScreen, w.isDisplayed(), w.mAttrs.userActivityTimeout);
        if (w.mHasSurface && onScreen) {
            if (!syswin && w.mAttrs.userActivityTimeout >= 0 && mUserActivityTimeout < 0) {
                mUserActivityTimeout = w.mAttrs.userActivityTimeout;
                ProtoLog.d(WM_DEBUG_KEEP_SCREEN_ON, "mUserActivityTimeout set to %d",
                        mUserActivityTimeout);
            }
        }
        if (w.mHasSurface && canBeSeen) {
            if ((attrFlags & FLAG_KEEP_SCREEN_ON) != 0) {
                mHoldScreen = w.mSession;
                mHoldScreenWindow = w;
            } else if (w == mWmService.mLastWakeLockHoldingWindow) {
                ProtoLog.d(WM_DEBUG_KEEP_SCREEN_ON,
                        "handleNotObscuredLocked: %s was holding screen wakelock but no longer "
                                + "has FLAG_KEEP_SCREEN_ON!!! called by%s",
                        w, Debug.getCallers(10));
            }
            if (!syswin && w.mAttrs.screenBrightness >= 0
                    && Float.isNaN(mScreenBrightnessOverride)) {
                mScreenBrightnessOverride = w.mAttrs.screenBrightness;
            }

            final int type = attrs.type;
            // This function assumes that the contents of the default display are processed first
            // before secondary displays.
            final DisplayContent displayContent = w.getDisplayContent();
            if (displayContent != null && displayContent.isDefaultDisplay) {
                // While a dream or keyguard is showing, obscure ordinary application content on
                // secondary displays (by forcibly enabling mirroring unless there is other content
                // we want to show) but still allow opaque keyguard dialogs to be shown.
                if (w.isDreamWindow() || mWmService.mPolicy.isKeyguardShowing()) {
                    mObscureApplicationContentOnSecondaryDisplays = true;
                }
                displayHasContent = true;
            } else if (displayContent != null &&
                    (!mObscureApplicationContentOnSecondaryDisplays
                            || (obscured && type == TYPE_KEYGUARD_DIALOG))) {
                // Allow full screen keyguard presentation dialogs to be seen.
                displayHasContent = true;
            }
            if ((privateflags & PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE) != 0) {
                mSustainedPerformanceModeCurrent = true;
            }
        }

        return displayHasContent;
    }

    boolean updateRotationUnchecked() {
        boolean changed = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (mChildren.get(i).getDisplayRotation().updateRotationAndSendNewConfigIfChanged()) {
                changed = true;
            }
        }
        return changed;
    }

    boolean copyAnimToLayoutParams() {
        boolean doRequest = false;

        final int bulkUpdateParams = mWmService.mAnimator.mBulkUpdateParams;
        if ((bulkUpdateParams & SET_UPDATE_ROTATION) != 0) {
            mUpdateRotation = true;
            doRequest = true;
        }
        if (mOrientationChangeComplete) {
            mLastWindowFreezeSource = mWmService.mAnimator.mLastWindowFreezeSource;
            if (mWmService.mWindowsFreezingScreen != WINDOWS_FREEZING_SCREENS_NONE) {
                doRequest = true;
            }
        }

        if ((bulkUpdateParams & SET_WALLPAPER_ACTION_PENDING) != 0) {
            mWallpaperActionPending = true;
        }

        return doRequest;
    }

    private final class MyHandler extends Handler {

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_SCREEN_BRIGHTNESS_OVERRIDE:
                    mWmService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(
                            Float.intBitsToFloat(msg.arg1));
                    break;
                case SET_USER_ACTIVITY_TIMEOUT:
                    mWmService.mPowerManagerInternal.
                            setUserActivityTimeoutOverrideFromWindowManager((Long) msg.obj);
                    break;
                default:
                    break;
            }
        }
    }

    void dumpDisplayContents(PrintWriter pw) {
        pw.println("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)");
        if (mWmService.mDisplayReady) {
            final int count = mChildren.size();
            for (int i = 0; i < count; ++i) {
                final DisplayContent displayContent = mChildren.get(i);
                displayContent.dump(pw, "  ", true /* dumpAll */);
            }
        } else {
            pw.println("  NO DISPLAY");
        }
    }

    void dumpTopFocusedDisplayId(PrintWriter pw) {
        pw.print("  mTopFocusedDisplayId=");
        pw.println(mTopFocusedDisplayId);
    }

    void dumpLayoutNeededDisplayIds(PrintWriter pw) {
        if (!isLayoutNeeded()) {
            return;
        }
        pw.print("  mLayoutNeeded on displays=");
        final int count = mChildren.size();
        for (int displayNdx = 0; displayNdx < count; ++displayNdx) {
            final DisplayContent displayContent = mChildren.get(displayNdx);
            if (displayContent.isLayoutNeeded()) {
                pw.print(displayContent.getDisplayId());
            }
        }
        pw.println();
    }

    void dumpWindowsNoHeader(PrintWriter pw, boolean dumpAll, ArrayList<WindowState> windows) {
        final int[] index = new int[1];
        forAllWindows((w) -> {
            if (windows == null || windows.contains(w)) {
                pw.println("  Window #" + index[0] + " " + w + ":");
                w.dump(pw, "    ", dumpAll || windows != null);
                index[0] = index[0] + 1;
            }
        }, true /* traverseTopToBottom */);
    }

    void dumpTokens(PrintWriter pw, boolean dumpAll) {
        pw.println("  All tokens:");
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).dumpTokens(pw, dumpAll);
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);

        mTaskSupervisor.getKeyguardController().dumpDebug(proto, KEYGUARD_CONTROLLER);
        proto.write(IS_HOME_RECENTS_COMPONENT,
                mTaskSupervisor.mRecentTasks.isRecentsComponentHomeActivity(mCurrentUser));
        proto.end(token);
    }

    @Override
    String getName() {
        return "ROOT";
    }

    @Override
    void scheduleAnimation() {
        mWmService.scheduleAnimationLocked();
    }

    @Override
    protected void removeChild(DisplayContent dc) {
        super.removeChild(dc);
        if (mTopFocusedDisplayId == dc.getDisplayId()) {
            mWmService.updateFocusedWindowLocked(
                    UPDATE_FOCUS_NORMAL, true /* updateInputWindows */);
        }
    }

    /**
     * For all display at or below this call the callback.
     *
     * @param callback Callback to be called for every display.
     */
    void forAllDisplays(Consumer<DisplayContent> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            callback.accept(mChildren.get(i));
        }
    }

    void forAllDisplayPolicies(Consumer<DisplayPolicy> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            callback.accept(mChildren.get(i).getDisplayPolicy());
        }
    }

    /**
     * Get current topmost focused IME window in system.
     * Will look on all displays in current Z-order.
     */
    WindowState getCurrentInputMethodWindow() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final DisplayContent displayContent = mChildren.get(i);
            if (displayContent.mInputMethodWindow != null) {
                return displayContent.mInputMethodWindow;
            }
        }
        return null;
    }

    void getDisplayContextsWithNonToastVisibleWindows(int pid, List<Context> outContexts) {
        if (outContexts == null) {
            return;
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            DisplayContent dc = mChildren.get(i);
            if (dc.getWindow(w -> pid == w.mSession.mPid && w.isVisibleNow()
                    && w.mAttrs.type != WindowManager.LayoutParams.TYPE_TOAST) != null) {
                outContexts.add(dc.getDisplayUiContext());
            }
        }
    }

    @Nullable
    Context getDisplayUiContext(int displayId) {
        return getDisplayContent(displayId) != null
                ? getDisplayContent(displayId).getDisplayUiContext() : null;
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        mDisplayManager = mService.mContext.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(this, mService.mUiHandler);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);

        final Display[] displays = mDisplayManager.getDisplays();
        for (int displayNdx = 0; displayNdx < displays.length; ++displayNdx) {
            final Display display = displays[displayNdx];
            final DisplayContent displayContent = new DisplayContent(display, this);
            addChild(displayContent, POSITION_BOTTOM);
            if (displayContent.mDisplayId == DEFAULT_DISPLAY) {
                mDefaultDisplay = displayContent;
            }
        }

        final TaskDisplayArea defaultTaskDisplayArea = getDefaultTaskDisplayArea();
        defaultTaskDisplayArea.getOrCreateRootHomeTask(ON_TOP);
        positionChildAt(POSITION_TOP, defaultTaskDisplayArea.mDisplayContent,
                false /* includingParents */);
    }

    // TODO(multi-display): Look at all callpoints to make sure they make sense in multi-display.
    DisplayContent getDefaultDisplay() {
        return mDefaultDisplay;
    }

    /**
     * Get the default display area on the device dedicated to app windows. This one should be used
     * only as a fallback location for activity launches when no target display area is specified,
     * or for cases when multi-instance is not supported yet (like Split-screen, Freeform, PiP or
     * Recents).
     */
    TaskDisplayArea getDefaultTaskDisplayArea() {
        return mDefaultDisplay.getDefaultTaskDisplayArea();
    }

    /**
     * Get an existing instance of {@link DisplayContent} that has the given uniqueId. Unique ID is
     * defined in {@link DisplayInfo#uniqueId}.
     *
     * @param uniqueId the unique ID of the display
     * @return the {@link DisplayContent} or {@code null} if nothing is found.
     */
    DisplayContent getDisplayContent(String uniqueId) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            final boolean isValid = display.mDisplay.isValid();
            if (isValid && display.mDisplay.getUniqueId().equals(uniqueId)) {
                return display;
            }
        }

        return null;
    }

    // TODO: Look into consolidating with getDisplayContentOrCreate()
    DisplayContent getDisplayContent(int displayId) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent displayContent = getChildAt(i);
            if (displayContent.mDisplayId == displayId) {
                return displayContent;
            }
        }
        return null;
    }

    /**
     * Get an existing instance of {@link DisplayContent} or create new if there is a
     * corresponding record in display manager.
     */
    // TODO: Look into consolidating with getDisplayContent()
    @Nullable
    DisplayContent getDisplayContentOrCreate(int displayId) {
        DisplayContent displayContent = getDisplayContent(displayId);
        if (displayContent != null) {
            return displayContent;
        }
        if (mDisplayManager == null) {
            // The system isn't fully initialized yet.
            return null;
        }
        final Display display = mDisplayManager.getDisplay(displayId);
        if (display == null) {
            // The display is not registered in DisplayManager.
            return null;
        }
        // The display hasn't been added to ActivityManager yet, create a new record now.
        displayContent = new DisplayContent(display, this);
        addChild(displayContent, POSITION_BOTTOM);
        return displayContent;
    }

    ActivityRecord getDefaultDisplayHomeActivityForUser(int userId) {
        return getDefaultTaskDisplayArea().getHomeActivityForUser(userId);
    }

    boolean startHomeOnAllDisplays(int userId, String reason) {
        boolean homeStarted = false;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final int displayId = getChildAt(i).mDisplayId;
            homeStarted |= startHomeOnDisplay(userId, reason, displayId);
        }
        return homeStarted;
    }

    void startHomeOnEmptyDisplays(String reason) {
        forAllTaskDisplayAreas(taskDisplayArea -> {
            if (taskDisplayArea.topRunningActivity() == null) {
                startHomeOnTaskDisplayArea(mCurrentUser, reason, taskDisplayArea,
                        false /* allowInstrumenting */, false /* fromHomeKey */);
            }
        });
    }

    boolean startHomeOnDisplay(int userId, String reason, int displayId) {
        return startHomeOnDisplay(userId, reason, displayId, false /* allowInstrumenting */,
                false /* fromHomeKey */);
    }

    boolean startHomeOnDisplay(int userId, String reason, int displayId, boolean allowInstrumenting,
            boolean fromHomeKey) {
        // Fallback to top focused display or default display if the displayId is invalid.
        if (displayId == INVALID_DISPLAY) {
            final Task rootTask = getTopDisplayFocusedRootTask();
            displayId = rootTask != null ? rootTask.getDisplayId() : DEFAULT_DISPLAY;
        }

        final DisplayContent display = getDisplayContent(displayId);
        return display.reduceOnAllTaskDisplayAreas((taskDisplayArea, result) ->
                        result | startHomeOnTaskDisplayArea(userId, reason, taskDisplayArea,
                                allowInstrumenting, fromHomeKey),
                false /* initValue */);
    }

    /**
     * This starts home activity on display areas that can have system decorations based on
     * displayId - default display area always uses primary home component.
     * For secondary display areas, the home activity must have category SECONDARY_HOME and then
     * resolves according to the priorities listed below.
     * - If default home is not set, always use the secondary home defined in the config.
     * - Use currently selected primary home activity.
     * - Use the activity in the same package as currently selected primary home activity.
     * If there are multiple activities matched, use first one.
     * - Use the secondary home defined in the config.
     */
    boolean startHomeOnTaskDisplayArea(int userId, String reason, TaskDisplayArea taskDisplayArea,
            boolean allowInstrumenting, boolean fromHomeKey) {
        // Fallback to top focused display area if the provided one is invalid.
        if (taskDisplayArea == null) {
            final Task rootTask = getTopDisplayFocusedRootTask();
            taskDisplayArea = rootTask != null ? rootTask.getDisplayArea()
                    : getDefaultTaskDisplayArea();
        }

        Intent homeIntent = null;
        ActivityInfo aInfo = null;
        if (taskDisplayArea == getDefaultTaskDisplayArea()) {
            homeIntent = mService.getHomeIntent();
            aInfo = resolveHomeActivity(userId, homeIntent);
        } else if (shouldPlaceSecondaryHomeOnDisplayArea(taskDisplayArea)) {
            Pair<ActivityInfo, Intent> info = resolveSecondaryHomeActivity(userId, taskDisplayArea);
            aInfo = info.first;
            homeIntent = info.second;
        }
        if (aInfo == null || homeIntent == null) {
            return false;
        }

        if (!canStartHomeOnDisplayArea(aInfo, taskDisplayArea, allowInstrumenting)) {
            return false;
        }

        // Updates the home component of the intent.
        homeIntent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
        homeIntent.setFlags(homeIntent.getFlags() | FLAG_ACTIVITY_NEW_TASK);
        // Updates the extra information of the intent.
        if (fromHomeKey) {
            homeIntent.putExtra(WindowManagerPolicy.EXTRA_FROM_HOME_KEY, true);
            if (mWindowManager.getRecentsAnimationController() != null) {
                mWindowManager.getRecentsAnimationController().cancelAnimationForHomeStart();
            }
        }
        homeIntent.putExtra(WindowManagerPolicy.EXTRA_START_REASON, reason);

        // Update the reason for ANR debugging to verify if the user activity is the one that
        // actually launched.
        final String myReason = reason + ":" + userId + ":" + UserHandle.getUserId(
                aInfo.applicationInfo.uid) + ":" + taskDisplayArea.getDisplayId();
        mService.getActivityStartController().startHomeActivity(homeIntent, aInfo, myReason,
                taskDisplayArea);
        return true;
    }

    /**
     * This resolves the home activity info.
     *
     * @return the home activity info if any.
     */
    @VisibleForTesting
    ActivityInfo resolveHomeActivity(int userId, Intent homeIntent) {
        final int flags = ActivityManagerService.STOCK_PM_FLAGS;
        final ComponentName comp = homeIntent.getComponent();
        ActivityInfo aInfo = null;
        try {
            if (comp != null) {
                // Factory test.
                aInfo = AppGlobals.getPackageManager().getActivityInfo(comp, flags, userId);
            } else {
                final String resolvedType =
                        homeIntent.resolveTypeIfNeeded(mService.mContext.getContentResolver());
                final ResolveInfo info = AppGlobals.getPackageManager()
                        .resolveIntent(homeIntent, resolvedType, flags, userId);
                if (info != null) {
                    aInfo = info.activityInfo;
                }
            }
        } catch (RemoteException e) {
            // ignore
        }

        if (aInfo == null) {
            Slog.wtf(TAG, "No home screen found for " + homeIntent, new Throwable());
            return null;
        }

        aInfo = new ActivityInfo(aInfo);
        aInfo.applicationInfo = mService.getAppInfoForUser(aInfo.applicationInfo, userId);
        return aInfo;
    }

    @VisibleForTesting
    Pair<ActivityInfo, Intent> resolveSecondaryHomeActivity(int userId,
            @NonNull TaskDisplayArea taskDisplayArea) {
        if (taskDisplayArea == getDefaultTaskDisplayArea()) {
            throw new IllegalArgumentException(
                    "resolveSecondaryHomeActivity: Should not be default task container");
        }
        // Resolve activities in the same package as currently selected primary home activity.
        Intent homeIntent = mService.getHomeIntent();
        ActivityInfo aInfo = resolveHomeActivity(userId, homeIntent);
        if (aInfo != null) {
            if (ResolverActivity.class.getName().equals(aInfo.name)) {
                // Always fallback to secondary home component if default home is not set.
                aInfo = null;
            } else {
                // Look for secondary home activities in the currently selected default home
                // package.
                homeIntent = mService.getSecondaryHomeIntent(aInfo.applicationInfo.packageName);
                final List<ResolveInfo> resolutions = resolveActivities(userId, homeIntent);
                final int size = resolutions.size();
                final String targetName = aInfo.name;
                aInfo = null;
                for (int i = 0; i < size; i++) {
                    ResolveInfo resolveInfo = resolutions.get(i);
                    // We need to traverse all resolutions to check if the currently selected
                    // default home activity is present.
                    if (resolveInfo.activityInfo.name.equals(targetName)) {
                        aInfo = resolveInfo.activityInfo;
                        break;
                    }
                }
                if (aInfo == null && size > 0) {
                    // First one is the best.
                    aInfo = resolutions.get(0).activityInfo;
                }
            }
        }

        if (aInfo != null) {
            if (!canStartHomeOnDisplayArea(aInfo, taskDisplayArea,
                    false /* allowInstrumenting */)) {
                aInfo = null;
            }
        }

        // Fallback to secondary home component.
        if (aInfo == null) {
            homeIntent = mService.getSecondaryHomeIntent(null);
            aInfo = resolveHomeActivity(userId, homeIntent);
        }
        return Pair.create(aInfo, homeIntent);
    }

    /**
     * Retrieve all activities that match the given intent.
     * The list should already ordered from best to worst matched.
     * {@link android.content.pm.PackageManager#queryIntentActivities}
     */
    @VisibleForTesting
    List<ResolveInfo> resolveActivities(int userId, Intent homeIntent) {
        List<ResolveInfo> resolutions;
        try {
            final String resolvedType =
                    homeIntent.resolveTypeIfNeeded(mService.mContext.getContentResolver());
            resolutions = AppGlobals.getPackageManager().queryIntentActivities(homeIntent,
                    resolvedType, ActivityManagerService.STOCK_PM_FLAGS, userId).getList();

        } catch (RemoteException e) {
            resolutions = new ArrayList<>();
        }
        return resolutions;
    }

    boolean resumeHomeActivity(ActivityRecord prev, String reason,
            TaskDisplayArea taskDisplayArea) {
        if (!mService.isBooting() && !mService.isBooted()) {
            // Not ready yet!
            return false;
        }

        if (taskDisplayArea == null) {
            taskDisplayArea = getDefaultTaskDisplayArea();
        }

        final ActivityRecord r = taskDisplayArea.getHomeActivity();
        final String myReason = reason + " resumeHomeActivity";

        // Only resume home activity if isn't finishing.
        if (r != null && !r.finishing) {
            r.moveFocusableActivityToTop(myReason);
            return resumeFocusedTasksTopActivities(r.getRootTask(), prev, null);
        }
        return startHomeOnTaskDisplayArea(mCurrentUser, myReason, taskDisplayArea,
                false /* allowInstrumenting */, false /* fromHomeKey */);
    }

    /**
     * Check if the display area is valid for secondary home activity.
     *
     * @param taskDisplayArea The target display area.
     * @return {@code true} if allow to launch, {@code false} otherwise.
     */
    boolean shouldPlaceSecondaryHomeOnDisplayArea(TaskDisplayArea taskDisplayArea) {
        if (getDefaultTaskDisplayArea() == taskDisplayArea) {
            throw new IllegalArgumentException(
                    "shouldPlaceSecondaryHomeOnDisplay: Should not be on default task container");
        } else if (taskDisplayArea == null) {
            return false;
        }

        if (!taskDisplayArea.canHostHomeTask()) {
            // Can't launch home on a TaskDisplayArea that does not support root home task
            return false;
        }

        if (taskDisplayArea.getDisplayId() != DEFAULT_DISPLAY && !mService.mSupportsMultiDisplay) {
            // Can't launch home on secondary display if device does not support multi-display.
            return false;
        }

        final boolean deviceProvisioned = Settings.Global.getInt(
                mService.mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (!deviceProvisioned) {
            // Can't launch home on secondary display areas before device is provisioned.
            return false;
        }

        if (!StorageManager.isUserKeyUnlocked(mCurrentUser)) {
            // Can't launch home on secondary display areas if device is still locked.
            return false;
        }

        final DisplayContent display = taskDisplayArea.getDisplayContent();
        if (display == null || display.isRemoved() || !display.supportsSystemDecorations()) {
            // Can't launch home on display that doesn't support system decorations.
            return false;
        }

        return true;
    }

    /**
     * Check if home activity start should be allowed on a display.
     *
     * @param homeInfo           {@code ActivityInfo} of the home activity that is going to be
     *                           launched.
     * @param taskDisplayArea    The target display area.
     * @param allowInstrumenting Whether launching home should be allowed if being instrumented.
     * @return {@code true} if allow to launch, {@code false} otherwise.
     */
    boolean canStartHomeOnDisplayArea(ActivityInfo homeInfo, TaskDisplayArea taskDisplayArea,
            boolean allowInstrumenting) {
        if (mService.mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL
                && mService.mTopAction == null) {
            // We are running in factory test mode, but unable to find the factory test app, so
            // just sit around displaying the error message and don't try to start anything.
            return false;
        }

        final WindowProcessController app =
                mService.getProcessController(homeInfo.processName, homeInfo.applicationInfo.uid);
        if (!allowInstrumenting && app != null && app.isInstrumenting()) {
            // Don't do this if the home app is currently being instrumented.
            return false;
        }

        final int displayId = taskDisplayArea != null ? taskDisplayArea.getDisplayId()
                : INVALID_DISPLAY;
        if (displayId == DEFAULT_DISPLAY || (displayId != INVALID_DISPLAY
                && displayId == mService.mVr2dDisplayId)) {
            // No restrictions to default display or vr 2d display.
            return true;
        }

        if (!shouldPlaceSecondaryHomeOnDisplayArea(taskDisplayArea)) {
            return false;
        }

        final boolean supportMultipleInstance = homeInfo.launchMode != LAUNCH_SINGLE_TASK
                && homeInfo.launchMode != LAUNCH_SINGLE_INSTANCE;
        if (!supportMultipleInstance) {
            // Can't launch home on secondary displays if it requested to be single instance.
            return false;
        }

        return true;
    }

    /**
     * Ensure all activities visibility, update orientation and configuration.
     *
     * @param starting                  The currently starting activity or {@code null} if there is
     *                                  none.
     * @param displayId                 The id of the display where operation is executed.
     * @param markFrozenIfConfigChanged Whether to set {@link ActivityRecord#frozenBeforeDestroy} to
     *                                  {@code true} if config changed.
     * @param deferResume               Whether to defer resume while updating config.
     * @return 'true' if starting activity was kept or wasn't provided, 'false' if it was relaunched
     * because of configuration update.
     */
    boolean ensureVisibilityAndConfig(ActivityRecord starting, int displayId,
            boolean markFrozenIfConfigChanged, boolean deferResume) {
        // First ensure visibility without updating the config just yet. We need this to know what
        // activities are affecting configuration now.
        // Passing null here for 'starting' param value, so that visibility of actual starting
        // activity will be properly updated.
        ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */, false /* notifyClients */);

        if (displayId == INVALID_DISPLAY) {
            // The caller didn't provide a valid display id, skip updating config.
            return true;
        }

        // Force-update the orientation from the WindowManager, since we need the true configuration
        // to send to the client now.
        final DisplayContent displayContent = getDisplayContent(displayId);
        Configuration config = null;
        if (displayContent != null) {
            config = displayContent.updateOrientation(starting, true /* forceUpdate */);
        }
        // Visibilities may change so let the starting activity have a chance to report. Can't do it
        // when visibility is changed in each AppWindowToken because it may trigger wrong
        // configuration push because the visibility of some activities may not be updated yet.
        if (starting != null) {
            starting.reportDescendantOrientationChangeIfNeeded();
        }
        if (starting != null && markFrozenIfConfigChanged && config != null) {
            starting.frozenBeforeDestroy = true;
        }

        if (displayContent != null) {
            // Update the configuration of the activities on the display.
            return displayContent.updateDisplayOverrideConfigurationLocked(config, starting,
                    deferResume, null /* result */);
        } else {
            return true;
        }
    }

    /**
     * @return a list of pairs, containing activities and their task id which are the top ones in
     * each visible root task. The first entry will be the focused activity.
     */
    List<ActivityAssistInfo> getTopVisibleActivities() {
        final ArrayList<ActivityAssistInfo> topVisibleActivities = new ArrayList<>();
        final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
        // Traverse all displays.
        forAllRootTasks(rootTask -> {
            // Get top activity from a visible root task and add it to the list.
            if (rootTask.shouldBeVisible(null /* starting */)) {
                final ActivityRecord top = rootTask.getTopNonFinishingActivity();
                if (top != null) {
                    ActivityAssistInfo visibleActivity = new ActivityAssistInfo(top);
                    if (rootTask == topFocusedRootTask) {
                        topVisibleActivities.add(0, visibleActivity);
                    } else {
                        topVisibleActivities.add(visibleActivity);
                    }
                }
            }
        });
        return topVisibleActivities;
    }

    @Nullable
    Task getTopDisplayFocusedRootTask() {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final Task focusedRootTask = getChildAt(i).getFocusedRootTask();
            if (focusedRootTask != null) {
                return focusedRootTask;
            }
        }
        return null;
    }

    @Nullable
    ActivityRecord getTopResumedActivity() {
        final Task focusedRootTask = getTopDisplayFocusedRootTask();
        if (focusedRootTask == null) {
            return null;
        }
        final ActivityRecord resumedActivity = focusedRootTask.getTopResumedActivity();
        if (resumedActivity != null && resumedActivity.app != null) {
            return resumedActivity;
        }
        // The top focused root task might not have a resumed activity yet - look on all displays in
        // focus order.
        return getItemFromTaskDisplayAreas(TaskDisplayArea::getFocusedActivity);
    }

    boolean isTopDisplayFocusedRootTask(Task task) {
        return task != null && task == getTopDisplayFocusedRootTask();
    }

    boolean attachApplication(WindowProcessController app) throws RemoteException {
        try {
            return mAttachApplicationHelper.process(app);
        } finally {
            mAttachApplicationHelper.reset();
        }
    }

    /**
     * Make sure that all activities that need to be visible in the system actually are and update
     * their configuration.
     */
    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        ensureActivitiesVisible(starting, configChanges, preserveWindows, true /* notifyClients */);
    }

    /**
     * @see #ensureActivitiesVisible(ActivityRecord, int, boolean)
     */
    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        if (mTaskSupervisor.inActivityVisibilityUpdate()
                || mTaskSupervisor.isRootVisibilityUpdateDeferred()) {
            // Don't do recursive work.
            return;
        }

        try {
            mTaskSupervisor.beginActivityVisibilityUpdate();
            // First the front root tasks. In case any are not fullscreen and are in front of home.
            for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
                final DisplayContent display = getChildAt(displayNdx);
                display.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                        notifyClients);
            }
        } finally {
            mTaskSupervisor.endActivityVisibilityUpdate();
        }
    }

    boolean switchUser(int userId, UserState uss) {
        final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
        final int focusRootTaskId = topFocusedRootTask != null
                ? topFocusedRootTask.getRootTaskId() : INVALID_TASK_ID;
        // Also dismiss the pinned root task whenever we switch users. Removing the pinned root task
        // will also cause all tasks to be moved to the fullscreen root task at a position that is
        // appropriate.
        removeRootTasksInWindowingModes(WINDOWING_MODE_PINNED);

        mUserRootTaskInFront.put(mCurrentUser, focusRootTaskId);
        mCurrentUser = userId;

        mTaskSupervisor.mStartingUsers.add(uss);
        forAllRootTasks(rootTask -> {
            rootTask.switchUser(userId);
        });

        final int restoreRootTaskId = mUserRootTaskInFront.get(userId);
        Task rootTask = getRootTask(restoreRootTaskId);
        if (rootTask == null) {
            rootTask = getDefaultTaskDisplayArea().getOrCreateRootHomeTask();
        }
        final boolean homeInFront = rootTask.isActivityTypeHome();
        if (rootTask.isOnHomeDisplay()) {
            rootTask.moveToFront("switchUserOnHomeDisplay");
        } else {
            // Root task was moved to another display while user was swapped out.
            resumeHomeActivity(null, "switchUserOnOtherDisplay", getDefaultTaskDisplayArea());
        }
        return homeInFront;
    }

    void removeUser(int userId) {
        mUserRootTaskInFront.delete(userId);
    }

    /**
     * Update the last used root task id for non-current user (current user's last
     * used root task is the focused root task)
     */
    void updateUserRootTask(int userId, Task rootTask) {
        if (userId != mCurrentUser) {
            if (rootTask == null) {
                rootTask = getDefaultTaskDisplayArea().getOrCreateRootHomeTask();
            }

            mUserRootTaskInFront.put(userId, rootTask.getRootTaskId());
        }
    }

    /**
     * Move root task with all its existing content to specified task display area.
     *
     * @param rootTaskId      Id of root task to move.
     * @param taskDisplayArea The task display area to move root task to.
     * @param onTop           Indicates whether container should be place on top or on bottom.
     */
    void moveRootTaskToTaskDisplayArea(int rootTaskId, TaskDisplayArea taskDisplayArea,
            boolean onTop) {
        final Task rootTask = getRootTask(rootTaskId);
        if (rootTask == null) {
            throw new IllegalArgumentException("moveRootTaskToTaskDisplayArea: Unknown rootTaskId="
                    + rootTaskId);
        }

        final TaskDisplayArea currentTaskDisplayArea = rootTask.getDisplayArea();
        if (currentTaskDisplayArea == null) {
            throw new IllegalStateException("moveRootTaskToTaskDisplayArea: rootTask=" + rootTask
                    + " is not attached to any task display area.");
        }

        if (taskDisplayArea == null) {
            throw new IllegalArgumentException(
                    "moveRootTaskToTaskDisplayArea: Unknown taskDisplayArea=" + taskDisplayArea);
        }

        if (currentTaskDisplayArea == taskDisplayArea) {
            throw new IllegalArgumentException("Trying to move rootTask=" + rootTask
                    + " to its current taskDisplayArea=" + taskDisplayArea);
        }
        rootTask.reparent(taskDisplayArea, onTop);

        // Resume focusable root task after reparenting to another display area.
        rootTask.resumeNextFocusAfterReparent();

        // TODO(multi-display): resize rootTasks properly if moved from split-screen.
    }

    /**
     * Move root task with all its existing content to specified display.
     *
     * @param rootTaskId Id of root task to move.
     * @param displayId  Id of display to move root task to.
     * @param onTop      Indicates whether container should be place on top or on bottom.
     */
    void moveRootTaskToDisplay(int rootTaskId, int displayId, boolean onTop) {
        final DisplayContent displayContent = getDisplayContentOrCreate(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException("moveRootTaskToDisplay: Unknown displayId="
                    + displayId);
        }

        moveRootTaskToTaskDisplayArea(rootTaskId, displayContent.getDefaultTaskDisplayArea(),
                onTop);
    }

    void moveActivityToPinnedRootTask(@NonNull ActivityRecord r,
            @Nullable ActivityRecord launchIntoPipHostActivity, String reason) {
        mService.deferWindowLayout();

        final TaskDisplayArea taskDisplayArea = r.getDisplayArea();

        try {
            final Task task = r.getTask();

            // Create a transition now to collect the current pinned Task dismiss. Only do the
            // create here as the Task (trigger) to enter PIP is not ready yet.
            final TransitionController transitionController = task.mTransitionController;
            Transition newTransition = null;
            if (transitionController.isCollecting()) {
                transitionController.setReady(task, false /* ready */);
            } else if (transitionController.getTransitionPlayer() != null) {
                newTransition = transitionController.createTransition(TRANSIT_PIP);
            }

            // This will change the root pinned task's windowing mode to its original mode, ensuring
            // we only have one root task that is in pinned mode.
            final Task rootPinnedTask = taskDisplayArea.getRootPinnedTask();
            if (rootPinnedTask != null) {
                transitionController.collect(rootPinnedTask);
                rootPinnedTask.dismissPip();
            }

            // Set a transition to ensure that we don't immediately try and update the visibility
            // of the activity entering PIP
            r.getDisplayContent().prepareAppTransition(TRANSIT_NONE);

            final TaskFragment organizedTf = r.getOrganizedTaskFragment();
            // TODO: Does it make sense to only count non-finishing activities?
            final boolean singleActivity = task.getActivityCount() == 1;
            final Task rootTask;
            if (singleActivity) {
                rootTask = task;

                // Apply the last recents animation leash transform to the task entering PIP
                rootTask.maybeApplyLastRecentsAnimationTransaction();
            } else {
                // In the case of multiple activities, we will create a new task for it and then
                // move the PIP activity into the task. Note that we explicitly defer the task
                // appear being sent in this case and mark this newly created task to been visible.
                rootTask = new Task.Builder(mService)
                        .setActivityType(r.getActivityType())
                        .setOnTop(true)
                        .setActivityInfo(r.info)
                        .setParent(taskDisplayArea)
                        .setIntent(r.intent)
                        .setDeferTaskAppear(true)
                        .setHasBeenVisible(true)
                        .build();
                // Establish bi-directional link between the original and pinned task.
                r.setLastParentBeforePip(launchIntoPipHostActivity);
                // It's possible the task entering PIP is in freeform, so save the last
                // non-fullscreen bounds. Then when this new PIP task exits PIP, it can restore
                // to its previous freeform bounds.
                rootTask.setLastNonFullscreenBounds(task.mLastNonFullscreenBounds);
                rootTask.setBounds(task.getBounds());

                // Move the last recents animation transaction from original task to the new one.
                if (task.mLastRecentsAnimationTransaction != null) {
                    rootTask.setLastRecentsAnimationTransaction(
                            task.mLastRecentsAnimationTransaction,
                            task.mLastRecentsAnimationOverlay);
                    task.clearLastRecentsAnimationTransaction(false /* forceRemoveOverlay */);
                }

                // The organized TaskFragment is becoming empty because this activity is reparented
                // to a new PIP Task. In this case, we should notify the organizer about why the
                // TaskFragment becomes empty.
                if (organizedTf != null && organizedTf.getNonFinishingActivityCount() == 1
                        && organizedTf.getTopNonFinishingActivity() == r) {
                    organizedTf.mClearedTaskFragmentForPip = true;
                }

                // There are multiple activities in the task and moving the top activity should
                // reveal/leave the other activities in their original task.
                // On the other hand, ActivityRecord#onParentChanged takes care of setting the
                // up-to-dated root pinned task information on this newly created root task.
                r.reparent(rootTask, MAX_VALUE, reason);

                // Ensure the leash of new task is in sync with its current bounds after reparent.
                rootTask.maybeApplyLastRecentsAnimationTransaction();

                // In the case of this activity entering PIP due to it being moved to the back,
                // the old activity would have a TRANSIT_TASK_TO_BACK transition that needs to be
                // ran. But, since its visibility did not change (note how it was STOPPED/not
                // visible, and with it now at the back stack, it remains not visible), the logic to
                // add the transition is automatically skipped. We then add this activity manually
                // to the list of apps being closed, and request its transition to be ran.
                final ActivityRecord oldTopActivity = task.getTopMostActivity();
                if (oldTopActivity != null && oldTopActivity.isState(STOPPED)
                        && task.getDisplayContent().mAppTransition.containsTransitRequest(
                        TRANSIT_TO_BACK)) {
                    task.getDisplayContent().mClosingApps.add(oldTopActivity);
                    oldTopActivity.mRequestForceTransition = true;
                }
            }
            // The intermediate windowing mode to be set on the ActivityRecord later.
            // This needs to happen before the re-parenting, otherwise we will always set the
            // ActivityRecord to be fullscreen.
            final int intermediateWindowingMode = rootTask.getWindowingMode();
            if (rootTask.getParent() != taskDisplayArea) {
                // root task is nested, but pinned tasks need to be direct children of their
                // display area, so reparent.
                rootTask.reparent(taskDisplayArea, true /* onTop */);
            }

            // The new PIP Task is ready, start the transition before updating the windowing mode.
            if (newTransition != null) {
                transitionController.requestStartTransition(newTransition, rootTask,
                        null /* remoteTransition */, null /* displayChange */);
            }
            transitionController.collect(rootTask);

            // Defer the windowing mode change until after the transition to prevent the activity
            // from doing work and changing the activity visuals while animating
            // TODO(task-org): Figure-out more structured way to do this long term.
            r.setWindowingMode(intermediateWindowingMode);
            r.mWaitForEnteringPinnedMode = true;
            rootTask.forAllTaskFragments(tf -> {
                // When the Task is entering picture-in-picture, we should clear all override from
                // the client organizer, so the PIP activity can get the correct config from the
                // Task, and prevent conflict with the PipTaskOrganizer.
                if (tf.isOrganizedTaskFragment()) {
                    tf.resetAdjacentTaskFragment();
                    tf.updateRequestedOverrideConfiguration(EMPTY);
                }
            });
            rootTask.setWindowingMode(WINDOWING_MODE_PINNED);
            // Set the launch bounds for launch-into-pip Activity on the root task.
            if (r.getOptions() != null && r.getOptions().isLaunchIntoPip()) {
                rootTask.setBounds(r.getOptions().getLaunchBounds());
            }
            rootTask.setDeferTaskAppear(false);

            // Reset the state that indicates it can enter PiP while pausing after we've moved it
            // to the root pinned task
            r.supportsEnterPipOnTaskSwitch = false;

            if (organizedTf != null && organizedTf.mClearedTaskFragmentForPip) {
                // Dispatch the pending info to TaskFragmentOrganizer before PIP animation.
                // Otherwise, it will keep waiting for the empty TaskFragment to be non-empty.
                mService.mTaskFragmentOrganizerController.dispatchPendingInfoChangedEvent(
                        organizedTf);
            }
        } finally {
            mService.continueWindowLayout();
        }

        ensureActivitiesVisible(null, 0, false /* preserveWindows */);
        resumeFocusedTasksTopActivities();

        notifyActivityPipModeChanged(r.getTask(), r);
    }

    /**
     * Notifies when an activity enters or leaves PIP mode.
     *
     * @param task the task of {@param r}
     * @param r indicates the activity currently in PIP, can be null to indicate no activity is
     *          currently in PIP mode.
     */
    void notifyActivityPipModeChanged(@NonNull Task task, @Nullable ActivityRecord r) {
        final boolean inPip = r != null;
        if (inPip) {
            mService.getTaskChangeNotificationController().notifyActivityPinned(r);
        } else {
            mService.getTaskChangeNotificationController().notifyActivityUnpinned();
        }
        mWindowManager.mPolicy.setPipVisibilityLw(inPip);
        mWmService.mTransactionFactory.get()
                .setTrustedOverlay(task.getSurfaceControl(), inPip)
                .apply();
    }

    void executeAppTransitionForAllDisplay() {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            display.mDisplayContent.executeAppTransition();
        }
    }

    @Nullable
    ActivityRecord findTask(ActivityRecord r, TaskDisplayArea preferredTaskDisplayArea) {
        return findTask(r.getActivityType(), r.taskAffinity, r.intent, r.info,
                preferredTaskDisplayArea);
    }

    @Nullable
    ActivityRecord findTask(int activityType, String taskAffinity, Intent intent, ActivityInfo info,
            TaskDisplayArea preferredTaskDisplayArea) {
        ProtoLog.d(WM_DEBUG_TASKS, "Looking for task of type=%s, taskAffinity=%s, intent=%s"
                        + ", info=%s, preferredTDA=%s", activityType, taskAffinity, intent, info,
                preferredTaskDisplayArea);
        mTmpFindTaskResult.init(activityType, taskAffinity, intent, info);

        // Looking up task on preferred display area first
        ActivityRecord candidateActivity = null;
        if (preferredTaskDisplayArea != null) {
            mTmpFindTaskResult.process(preferredTaskDisplayArea);
            if (mTmpFindTaskResult.mIdealRecord != null) {
                return mTmpFindTaskResult.mIdealRecord;
            } else if (mTmpFindTaskResult.mCandidateRecord != null) {
                candidateActivity = mTmpFindTaskResult.mCandidateRecord;
            }
        }

        final ActivityRecord idealMatchActivity = getItemFromTaskDisplayAreas(taskDisplayArea -> {
            if (taskDisplayArea == preferredTaskDisplayArea) {
                return null;
            }

            mTmpFindTaskResult.process(taskDisplayArea);
            if (mTmpFindTaskResult.mIdealRecord != null) {
                return mTmpFindTaskResult.mIdealRecord;
            }
            return null;
        });
        if (idealMatchActivity != null) {
            return idealMatchActivity;
        }

        if (WM_DEBUG_TASKS.isEnabled() && candidateActivity == null) {
            ProtoLog.d(WM_DEBUG_TASKS, "No task found");
        }
        return candidateActivity;
    }

    /**
     * Finish the topmost activities in all root tasks that belong to the crashed app.
     *
     * @param app    The app that crashed.
     * @param reason Reason to perform this action.
     * @return The task id that was finished in this root task, or INVALID_TASK_ID if none was
     * finished.
     */
    int finishTopCrashedActivities(WindowProcessController app, String reason) {
        Task focusedRootTask = getTopDisplayFocusedRootTask();
        final Task[] finishedTask = new Task[1];
        forAllTasks(rootTask -> {
            final Task t = rootTask.finishTopCrashedActivityLocked(app, reason);
            if (rootTask == focusedRootTask || finishedTask[0] == null) {
                finishedTask[0] = t;
            }
        });
        return finishedTask[0] != null ? finishedTask[0].mTaskId : INVALID_TASK_ID;
    }

    boolean resumeFocusedTasksTopActivities() {
        return resumeFocusedTasksTopActivities(null, null, null);
    }

    boolean resumeFocusedTasksTopActivities(
            Task targetRootTask, ActivityRecord target, ActivityOptions targetOptions) {
        return resumeFocusedTasksTopActivities(targetRootTask, target, targetOptions,
                false /* deferPause */);
    }

    boolean resumeFocusedTasksTopActivities(
            Task targetRootTask, ActivityRecord target, ActivityOptions targetOptions,
            boolean deferPause) {
        if (!mTaskSupervisor.readyToResume()) {
            return false;
        }

        boolean result = false;
        if (targetRootTask != null && (targetRootTask.isTopRootTaskInDisplayArea()
                || getTopDisplayFocusedRootTask() == targetRootTask)) {
            result = targetRootTask.resumeTopActivityUncheckedLocked(target, targetOptions,
                    deferPause);
        }

        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            final boolean curResult = result;
            boolean[] resumedOnDisplay = new boolean[1];
            display.forAllRootTasks(rootTask -> {
                final ActivityRecord topRunningActivity = rootTask.topRunningActivity();
                if (!rootTask.isFocusableAndVisible() || topRunningActivity == null) {
                    return;
                }
                if (rootTask == targetRootTask) {
                    // Simply update the result for targetRootTask because the targetRootTask
                    // had already resumed in above. We don't want to resume it again,
                    // especially in some cases, it would cause a second launch failure
                    // if app process was dead.
                    resumedOnDisplay[0] |= curResult;
                    return;
                }
                if (rootTask.getDisplayArea().isTopRootTask(rootTask)
                        && topRunningActivity.isState(RESUMED)) {
                    // Kick off any lingering app transitions form the MoveTaskToFront
                    // operation, but only consider the top task and root-task on that
                    // display.
                    rootTask.executeAppTransition(targetOptions);
                } else {
                    resumedOnDisplay[0] |= topRunningActivity.makeActiveIfNeeded(target);
                }
            });
            result |= resumedOnDisplay[0];
            if (!resumedOnDisplay[0]) {
                // In cases when there are no valid activities (e.g. device just booted or launcher
                // crashed) it's possible that nothing was resumed on a display. Requesting resume
                // of top activity in focused root task explicitly will make sure that at least home
                // activity is started and resumed, and no recursion occurs.
                final Task focusedRoot = display.getFocusedRootTask();
                if (focusedRoot != null) {
                    result |= focusedRoot.resumeTopActivityUncheckedLocked(target, targetOptions);
                } else if (targetRootTask == null) {
                    result |= resumeHomeActivity(null /* prev */, "no-focusable-task",
                            display.getDefaultTaskDisplayArea());
                }
            }
        }

        return result;
    }

    void applySleepTokens(boolean applyToRootTasks) {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            // Set the sleeping state of the display.
            final DisplayContent display = getChildAt(displayNdx);
            final boolean displayShouldSleep = display.shouldSleep();
            if (displayShouldSleep == display.isSleeping()) {
                continue;
            }
            display.setIsSleeping(displayShouldSleep);

            if (!applyToRootTasks) {
                continue;
            }

            // Set the sleeping state of the root tasks on the display.
            display.forAllRootTasks(rootTask -> {
                if (displayShouldSleep) {
                    rootTask.goToSleepIfPossible(false /* shuttingDown */);
                } else {
                    rootTask.forAllLeafTasksAndLeafTaskFragments(
                            taskFragment -> taskFragment.awakeFromSleeping(),
                            true /* traverseTopToBottom */);
                    if (rootTask.isFocusedRootTaskOnDisplay()
                            && !mTaskSupervisor.getKeyguardController()
                            .isKeyguardOrAodShowing(display.mDisplayId)) {
                        // If the keyguard is unlocked - resume immediately.
                        // It is possible that the display will not be awake at the time we
                        // process the keyguard going away, which can happen before the sleep
                        // token is released. As a result, it is important we resume the
                        // activity here.
                        rootTask.resumeTopActivityUncheckedLocked(null, null);
                    }
                    // The visibility update must not be called before resuming the top, so the
                    // display orientation can be updated first if needed. Otherwise there may
                    // have redundant configuration changes due to apply outdated display
                    // orientation (from keyguard) to activity.
                    rootTask.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                            false /* preserveWindows */);
                }
            });
        }
    }

    protected Task getRootTask(int rooTaskId) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final Task rootTask = getChildAt(i).getRootTask(rooTaskId);
            if (rootTask != null) {
                return rootTask;
            }
        }
        return null;
    }

    /** @see DisplayContent#getRootTask(int, int) */
    Task getRootTask(int windowingMode, int activityType) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final Task rootTask = getChildAt(i).getRootTask(windowingMode, activityType);
            if (rootTask != null) {
                return rootTask;
            }
        }
        return null;
    }

    private Task getRootTask(int windowingMode, int activityType,
            int displayId) {
        DisplayContent display = getDisplayContent(displayId);
        if (display == null) {
            return null;
        }
        return display.getRootTask(windowingMode, activityType);
    }

    private RootTaskInfo getRootTaskInfo(Task task) {
        RootTaskInfo info = new RootTaskInfo();
        task.fillTaskInfo(info);

        final DisplayContent displayContent = task.getDisplayContent();
        if (displayContent == null) {
            // A task might be not attached to a display.
            info.position = -1;
        } else {
            // Find the task z-order among all root tasks on the display from bottom to top.
            final int[] taskIndex = new int[1];
            final boolean[] hasFound = new boolean[1];
            displayContent.forAllRootTasks(rootTask -> {
                if (task == rootTask) {
                    hasFound[0] = true;
                    return true;
                }
                taskIndex[0]++;
                return false;
            }, false /* traverseTopToBottom */);
            info.position = hasFound[0] ? taskIndex[0] : -1;
        }
        info.visible = task.shouldBeVisible(null);
        task.getBounds(info.bounds);

        final int numTasks = task.getDescendantTaskCount();
        info.childTaskIds = new int[numTasks];
        info.childTaskNames = new String[numTasks];
        info.childTaskBounds = new Rect[numTasks];
        info.childTaskUserIds = new int[numTasks];
        final int[] currentIndex = {0};

        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootWindowContainer::processTaskForTaskInfo, PooledLambda.__(Task.class), info,
                currentIndex);
        task.forAllLeafTasks(c, false /* traverseTopToBottom */);
        c.recycle();

        final ActivityRecord top = task.topRunningActivity();
        info.topActivity = top != null ? top.intent.getComponent() : null;
        return info;
    }

    private static void processTaskForTaskInfo(
            Task task, RootTaskInfo info, int[] currentIndex) {
        int i = currentIndex[0];
        info.childTaskIds[i] = task.mTaskId;
        info.childTaskNames[i] = task.origActivity != null ? task.origActivity.flattenToString()
                : task.realActivity != null ? task.realActivity.flattenToString()
                        : task.getTopNonFinishingActivity() != null
                                ? task.getTopNonFinishingActivity().packageName : "unknown";
        info.childTaskBounds[i] = task.mAtmService.getTaskBounds(task.mTaskId);
        info.childTaskUserIds[i] = task.mUserId;
        currentIndex[0] = ++i;
    }

    RootTaskInfo getRootTaskInfo(int taskId) {
        Task task = getRootTask(taskId);
        if (task != null) {
            return getRootTaskInfo(task);
        }
        return null;
    }

    RootTaskInfo getRootTaskInfo(int windowingMode, int activityType) {
        final Task rootTask = getRootTask(windowingMode, activityType);
        return (rootTask != null) ? getRootTaskInfo(rootTask) : null;
    }

    RootTaskInfo getRootTaskInfo(int windowingMode, int activityType, int displayId) {
        final Task rootTask = getRootTask(windowingMode, activityType, displayId);
        return (rootTask != null) ? getRootTaskInfo(rootTask) : null;
    }

    /** If displayId == INVALID_DISPLAY, this will get root task infos on all displays */
    ArrayList<RootTaskInfo> getAllRootTaskInfos(int displayId) {
        final ArrayList<RootTaskInfo> list = new ArrayList<>();
        if (displayId == INVALID_DISPLAY) {
            forAllRootTasks(rootTask -> {
                list.add(getRootTaskInfo(rootTask));
            });
            return list;
        }
        final DisplayContent display = getDisplayContent(displayId);
        if (display == null) {
            return list;
        }
        display.forAllRootTasks(rootTask -> {
            list.add(getRootTaskInfo(rootTask));
        });
        return list;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (DEBUG_ROOT_TASK) Slog.v(TAG, "Display added displayId=" + displayId);
        synchronized (mService.mGlobalLock) {
            final DisplayContent display = getDisplayContentOrCreate(displayId);
            if (display == null) {
                return;
            }
            // Do not start home before booting, or it may accidentally finish booting before it
            // starts. Instead, we expect home activities to be launched when the system is ready
            // (ActivityManagerService#systemReady).
            if (mService.isBooted() || mService.isBooting()) {
                startSystemDecorations(display);
            }
            // Drop any cached DisplayInfos associated with this display id - the values are now
            // out of date given this display added event.
            mWmService.mPossibleDisplayInfoMapper.removePossibleDisplayInfos(displayId);
        }
    }

    private void startSystemDecorations(final DisplayContent displayContent) {
        startHomeOnDisplay(mCurrentUser, "displayAdded", displayContent.getDisplayId());
        displayContent.getDisplayPolicy().notifyDisplayReady();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (DEBUG_ROOT_TASK) Slog.v(TAG, "Display removed displayId=" + displayId);
        if (displayId == DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can't remove the primary display.");
        }

        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = getDisplayContent(displayId);
            if (displayContent == null) {
                return;
            }
            displayContent.remove();
            mWmService.mPossibleDisplayInfoMapper.removePossibleDisplayInfos(displayId);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (DEBUG_ROOT_TASK) Slog.v(TAG, "Display changed displayId=" + displayId);
        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.onDisplayChanged();
            }
            // Drop any cached DisplayInfos associated with this display id - the values are now
            // out of date given this display changed event.
            mWmService.mPossibleDisplayInfoMapper.removePossibleDisplayInfos(displayId);
            updateDisplayImePolicyCache();
        }
    }

    void updateDisplayImePolicyCache() {
        ArrayMap<Integer, Integer> displayImePolicyMap = new ArrayMap<>();
        forAllDisplays(dc -> displayImePolicyMap.put(dc.getDisplayId(), dc.getImePolicy()));
        mWmService.mDisplayImePolicyCache = Collections.unmodifiableMap(displayImePolicyMap);
    }

    /** Update lists of UIDs that are present on displays and have access to them. */
    void updateUIDsPresentOnDisplay() {
        mDisplayAccessUIDs.clear();
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent displayContent = getChildAt(displayNdx);
            // Only bother calculating the allowlist for private displays
            if (displayContent.isPrivate()) {
                mDisplayAccessUIDs.append(
                        displayContent.mDisplayId, displayContent.getPresentUIDs());
            }
        }
        // Store updated lists in DisplayManager. Callers from outside of AM should get them there.
        mDisplayManagerInternal.setDisplayAccessUIDs(mDisplayAccessUIDs);
    }

    void prepareForShutdown() {
        for (int i = 0; i < getChildCount(); i++) {
            createSleepToken("shutdown", getChildAt(i).mDisplayId);
        }
    }

    SleepToken createSleepToken(String tag, int displayId) {
        final DisplayContent display = getDisplayContent(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Invalid display: " + displayId);
        }

        final int tokenKey = makeSleepTokenKey(tag, displayId);
        SleepToken token = mSleepTokens.get(tokenKey);
        if (token == null) {
            token = new SleepToken(tag, displayId);
            mSleepTokens.put(tokenKey, token);
            display.mAllSleepTokens.add(token);
            ProtoLog.d(WM_DEBUG_STATES, "Create sleep token: tag=%s, displayId=%d", tag, displayId);
        } else {
            throw new RuntimeException("Create the same sleep token twice: " + token);
        }
        return token;
    }

    void removeSleepToken(SleepToken token) {
        if (!mSleepTokens.contains(token.mHashKey)) {
            Slog.d(TAG, "Remove non-exist sleep token: " + token + " from " + Debug.getCallers(6));
        }
        mSleepTokens.remove(token.mHashKey);
        final DisplayContent display = getDisplayContent(token.mDisplayId);
        if (display == null) {
            Slog.d(TAG, "Remove sleep token for non-existing display: " + token + " from "
                    + Debug.getCallers(6));
            return;
        }

        ProtoLog.d(WM_DEBUG_STATES, "Remove sleep token: tag=%s, displayId=%d", token.mTag,
                token.mDisplayId);
        display.mAllSleepTokens.remove(token);
        if (display.mAllSleepTokens.isEmpty()) {
            mService.updateSleepIfNeededLocked();
            // Assuming no lock screen is set and a user launches an activity, turns off the screen
            // and turn on the screen again, then the launched activity should be displayed on the
            // screen without app transition animation. When the screen turns on, both keyguard
            // sleep token and display off sleep token are removed, but the order is
            // non-deterministic.
            // Note: Display#mSkipAppTransitionAnimation will be ignored when keyguard related
            // transition exists, so this affects only when no lock screen is set. Otherwise
            // keyguard going away animation will be played.
            // See also AppTransitionController#getTransitCompatType for more details.
            if ((!mTaskSupervisor.getKeyguardController().isDisplayOccluded(display.mDisplayId)
                    && token.mTag.equals(KEYGUARD_SLEEP_TOKEN_TAG))
                    || token.mTag.equals(DISPLAY_OFF_SLEEP_TOKEN_TAG)) {
                display.mSkipAppTransitionAnimation = true;
            }
        }
    }

    void addStartingWindowsForVisibleActivities() {
        final ArrayList<Task> addedTasks = new ArrayList<>();
        forAllActivities((r) -> {
            final Task task = r.getTask();
            if (r.mVisibleRequested && r.mStartingData == null && !addedTasks.contains(task)) {
                r.showStartingWindow(true /*taskSwitch*/);
                addedTasks.add(task);
            }
        });
    }

    void invalidateTaskLayers() {
        if (!mTaskLayersChanged) {
            mTaskLayersChanged = true;
            mService.mH.post(mRankTaskLayersRunnable);
        }
    }

    /** Generate oom-score-adjustment rank for all tasks in the system based on z-order. */
    void rankTaskLayers() {
        if (mTaskLayersChanged) {
            mTaskLayersChanged = false;
            mService.mH.removeCallbacks(mRankTaskLayersRunnable);
        }
        mTmpTaskLayerRank = 0;
        // Only rank for leaf tasks because the score of activity is based on immediate parent.
        forAllLeafTasks(task -> {
            final int oldRank = task.mLayerRank;
            final ActivityRecord r = task.topRunningActivityLocked();
            if (r != null && r.mVisibleRequested) {
                task.mLayerRank = ++mTmpTaskLayerRank;
            } else {
                task.mLayerRank = Task.LAYER_RANK_INVISIBLE;
            }
            if (task.mLayerRank != oldRank) {
                task.forAllActivities(activity -> {
                    if (activity.hasProcess()) {
                        mTaskSupervisor.onProcessActivityStateChanged(activity.app,
                                true /* forceBatch */);
                    }
                });
            }
        }, true /* traverseTopToBottom */);

        if (!mTaskSupervisor.inActivityVisibilityUpdate()) {
            mTaskSupervisor.computeProcessActivityStateBatch();
        }
    }

    void clearOtherAppTimeTrackers(AppTimeTracker except) {
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootWindowContainer::clearOtherAppTimeTrackers,
                PooledLambda.__(ActivityRecord.class), except);
        forAllActivities(c);
        c.recycle();
    }

    private static void clearOtherAppTimeTrackers(ActivityRecord r, AppTimeTracker except) {
        if (r.appTimeTracker != except) {
            r.appTimeTracker = null;
        }
    }

    void scheduleDestroyAllActivities(String reason) {
        mDestroyAllActivitiesReason = reason;
        mService.mH.post(mDestroyAllActivitiesRunnable);
    }

    private void destroyActivity(ActivityRecord r) {
        if (r.finishing || !r.isDestroyable()) return;

        if (DEBUG_SWITCH) {
            Slog.v(TAG_SWITCH, "Destroying " + r + " in state " + r.getState()
                    + " resumed=" + r.getTask().getTopResumedActivity() + " pausing="
                    + r.getTask().getTopPausingActivity() + " for reason "
                    + mDestroyAllActivitiesReason);
        }

        r.destroyImmediately(mDestroyAllActivitiesReason);
    }

    // Tries to put all activity tasks to sleep. Returns true if all tasks were
    // successfully put to sleep.
    boolean putTasksToSleep(boolean allowDelay, boolean shuttingDown) {
        final boolean[] result = {true};
        forAllRootTasks(task -> {
            if (allowDelay) {
                result[0] &= task.goToSleepIfPossible(shuttingDown);
            } else {
                task.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                        !PRESERVE_WINDOWS);
            }
        });
        return result[0];
    }

    void handleAppCrash(WindowProcessController app) {
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootWindowContainer::handleAppCrash, PooledLambda.__(ActivityRecord.class), app);
        forAllActivities(c);
        c.recycle();
    }

    private static void handleAppCrash(ActivityRecord r, WindowProcessController app) {
        if (r.app != app) return;
        Slog.w(TAG, "  Force finishing activity "
                + r.intent.getComponent().flattenToShortString());
        r.detachFromProcess();
        r.mDisplayContent.requestTransitionAndLegacyPrepare(TRANSIT_CLOSE,
                TRANSIT_FLAG_APP_CRASHED);
        r.destroyIfPossible("handleAppCrashed");
    }

    ActivityRecord findActivity(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        final int userId = UserHandle.getUserId(info.applicationInfo.uid);

        final PooledPredicate p = PooledLambda.obtainPredicate(
                RootWindowContainer::matchesActivity, PooledLambda.__(ActivityRecord.class),
                userId, compareIntentFilters, intent, cls);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean matchesActivity(ActivityRecord r, int userId,
            boolean compareIntentFilters, Intent intent, ComponentName cls) {
        if (!r.canBeTopRunning() || r.mUserId != userId) return false;

        if (compareIntentFilters) {
            if (r.intent.filterEquals(intent)) {
                return true;
            }
        } else {
            // Compare the target component instead of intent component so we don't miss if the
            // activity uses alias.
            if (r.mActivityComponent.equals(cls)) {
                return true;
            }
        }
        return false;
    }

    boolean hasAwakeDisplay() {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            if (!display.shouldSleep()) {
                return true;
            }
        }
        return false;
    }

    Task getOrCreateRootTask(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable Task candidateTask, boolean onTop) {
        return getOrCreateRootTask(r, options, candidateTask, null /* sourceTask */, onTop,
                null /* launchParams */, 0 /* launchFlags */);
    }

    /**
     * Returns the right root task to use for launching factoring in all the input parameters.
     *
     * @param r              The activity we are trying to launch. Can be null.
     * @param options        The activity options used to the launch. Can be null.
     * @param candidateTask  The possible task the activity might be launched in. Can be null.
     * @param sourceTask     The task requesting to start activity. Can be null.
     * @param launchParams   The resolved launch params to use.
     * @param launchFlags    The launch flags for this launch.
     * @param realCallingPid The pid from {@link ActivityStarter#setRealCallingPid}
     * @param realCallingUid The uid from {@link ActivityStarter#setRealCallingUid}
     * @return The root task to use for the launch.
     */
    Task getOrCreateRootTask(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable Task candidateTask,
            @Nullable Task sourceTask, boolean onTop,
            @Nullable LaunchParamsController.LaunchParams launchParams, int launchFlags) {
        // First preference goes to the launch root task set in the activity options.
        if (options != null) {
            final Task candidateRoot = Task.fromWindowContainerToken(options.getLaunchRootTask());
            if (candidateRoot != null && canLaunchOnDisplay(r, candidateRoot)) {
                return candidateRoot;
            }
        }

        // Next preference goes to the task id set in the activity options.
        if (options != null) {
            final int candidateTaskId = options.getLaunchTaskId();
            if (candidateTaskId != INVALID_TASK_ID) {
                // Temporarily set the task id to invalid in case in re-entry.
                options.setLaunchTaskId(INVALID_TASK_ID);
                final Task task = anyTaskForId(candidateTaskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE, options, onTop);
                options.setLaunchTaskId(candidateTaskId);
                if (canLaunchOnDisplay(r, task)) {
                    return task.getRootTask();
                }
            }
        }

        // Next preference goes to the TaskDisplayArea candidate from launchParams
        // or activity options.
        TaskDisplayArea taskDisplayArea = null;
        if (launchParams != null && launchParams.mPreferredTaskDisplayArea != null) {
            taskDisplayArea = launchParams.mPreferredTaskDisplayArea;
        } else if (options != null) {
            final WindowContainerToken daToken = options.getLaunchTaskDisplayArea();
            taskDisplayArea = daToken != null
                    ? (TaskDisplayArea) WindowContainer.fromBinder(daToken.asBinder()) : null;
            if (taskDisplayArea == null) {
                final int launchDisplayId = options.getLaunchDisplayId();
                if (launchDisplayId != INVALID_DISPLAY) {
                    final DisplayContent displayContent = getDisplayContent(launchDisplayId);
                    if (displayContent != null) {
                        taskDisplayArea = displayContent.getDefaultTaskDisplayArea();
                    }
                }
            }
        }

        final int activityType = resolveActivityType(r, options, candidateTask);
        if (taskDisplayArea != null) {
            if (canLaunchOnDisplay(r, taskDisplayArea.getDisplayId())) {
                return taskDisplayArea.getOrCreateRootTask(r, options, candidateTask,
                        sourceTask, launchParams, launchFlags, activityType, onTop);
            } else {
                taskDisplayArea = null;
            }
        }

        // Give preference to the root task and display of the input task and activity if they
        // match the mode we want to launch into.
        Task rootTask = null;
        if (candidateTask != null) {
            rootTask = candidateTask.getRootTask();
        }
        if (rootTask == null && r != null) {
            rootTask = r.getRootTask();
        }
        int windowingMode = launchParams != null ? launchParams.mWindowingMode
                : WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        if (rootTask != null) {
            taskDisplayArea = rootTask.getDisplayArea();
            if (taskDisplayArea != null
                    && canLaunchOnDisplay(r, taskDisplayArea.mDisplayContent.mDisplayId)) {
                if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
                    windowingMode = taskDisplayArea.resolveWindowingMode(r, options, candidateTask);
                }
                // Always allow organized tasks that created by organizer since the activity type
                // of an organized task is decided by the activity type of its top child, which
                // could be incompatible with the given windowing mode and activity type.
                if (rootTask.isCompatible(windowingMode, activityType)
                        || rootTask.mCreatedByOrganizer) {
                    return rootTask;
                }
            } else {
                taskDisplayArea = null;
            }

        }

        // Falling back to default task container
        if (taskDisplayArea == null) {
            taskDisplayArea = getDefaultTaskDisplayArea();
        }
        return taskDisplayArea.getOrCreateRootTask(r, options, candidateTask, sourceTask,
                launchParams, launchFlags, activityType, onTop);
    }

    private boolean canLaunchOnDisplay(ActivityRecord r, Task task) {
        if (task == null) {
            Slog.w(TAG, "canLaunchOnDisplay(), invalid task: " + task);
            return false;
        }

        if (!task.isAttached()) {
            Slog.w(TAG, "canLaunchOnDisplay(), Task is not attached: " + task);
            return false;
        }

        return canLaunchOnDisplay(r, task.getTaskDisplayArea().getDisplayId());
    }

    /** @return true if activity record is null or can be launched on provided display. */
    private boolean canLaunchOnDisplay(ActivityRecord r, int displayId) {
        if (r == null) {
            return true;
        }
        if (!r.canBeLaunchedOnDisplay(displayId)) {
            Slog.w(TAG, "Not allow to launch " + r + " on display " + displayId);
            return false;
        }
        return true;
    }

    int resolveActivityType(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable Task task) {
        // Preference is given to the activity type for the activity then the task since the type
        // once set shouldn't change.
        int activityType = r != null ? r.getActivityType() : ACTIVITY_TYPE_UNDEFINED;
        if (activityType == ACTIVITY_TYPE_UNDEFINED && task != null) {
            activityType = task.getActivityType();
        }
        if (activityType != ACTIVITY_TYPE_UNDEFINED) {
            return activityType;
        }
        if (options != null) {
            activityType = options.getLaunchActivityType();
        }
        return activityType != ACTIVITY_TYPE_UNDEFINED ? activityType : ACTIVITY_TYPE_STANDARD;
    }

    /**
     * Get next focusable root task in the system. This will search through the root task on the
     * same display as the current focused root task, looking for a focusable and visible root task,
     * different from the target root task. If no valid candidates will be found, it will then go
     * through all displays and root tasks in last-focused order.
     *
     * @param currentFocus  The root task that previously had focus.
     * @param ignoreCurrent If we should ignore {@param currentFocus} when searching for next
     *                      candidate.
     * @return Next focusable {@link Task}, {@code null} if not found.
     */
    Task getNextFocusableRootTask(@NonNull Task currentFocus, boolean ignoreCurrent) {
        // First look for next focusable root task on the same display
        TaskDisplayArea preferredDisplayArea = currentFocus.getDisplayArea();
        if (preferredDisplayArea == null) {
            // Root task is currently detached because it is being removed. Use the previous
            // display it was on.
            preferredDisplayArea = getDisplayContent(currentFocus.mPrevDisplayId)
                    .getDefaultTaskDisplayArea();
        }
        final Task preferredFocusableRootTask = preferredDisplayArea.getNextFocusableRootTask(
                currentFocus, ignoreCurrent);
        if (preferredFocusableRootTask != null) {
            return preferredFocusableRootTask;
        }
        if (preferredDisplayArea.mDisplayContent.supportsSystemDecorations()) {
            // Stop looking for focusable root task on other displays because the preferred display
            // supports system decorations. Home activity would be launched on the same display if
            // no focusable root task found.
            return null;
        }

        // Now look through all displays
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            if (display == preferredDisplayArea.mDisplayContent) {
                // We've already checked this one
                continue;
            }
            final Task nextFocusableRootTask = display.getDefaultTaskDisplayArea()
                    .getNextFocusableRootTask(currentFocus, ignoreCurrent);
            if (nextFocusableRootTask != null) {
                return nextFocusableRootTask;
            }
        }

        return null;
    }

    void closeSystemDialogActivities(String reason) {
        forAllActivities((r) -> {
            if ((r.info.flags & ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS) != 0
                    || shouldCloseAssistant(r, reason)) {
                r.finishIfPossible(reason, true /* oomAdj */);
            }
        });
    }

    /**
     * Returns {@code true} if {@code uid} has a visible window that's above the window of type
     * {@link WindowManager.LayoutParams#TYPE_NOTIFICATION_SHADE} and {@code uid} is not owner of
     * the window of type {@link WindowManager.LayoutParams#TYPE_NOTIFICATION_SHADE}.
     *
     * If there is no window with type {@link WindowManager.LayoutParams#TYPE_NOTIFICATION_SHADE},
     * it returns {@code false}.
     */
    boolean hasVisibleWindowAboveButDoesNotOwnNotificationShade(int uid) {
        boolean[] visibleWindowFound = {false};
        // We only return true if we found the notification shade (ie. window of type
        // TYPE_NOTIFICATION_SHADE). Usually, it should always be there, but if for some reason
        // it isn't, we should better be on the safe side and return false for this.
        return forAllWindows(w -> {
            if (w.mOwnerUid == uid && w.isVisible()) {
                visibleWindowFound[0] = true;
            }
            if (w.mAttrs.type == TYPE_NOTIFICATION_SHADE) {
                return visibleWindowFound[0] && w.mOwnerUid != uid;
            }
            return false;
        }, true /* traverseTopToBottom */);
    }

    private boolean shouldCloseAssistant(ActivityRecord r, String reason) {
        if (!r.isActivityTypeAssistant()) return false;
        if (reason == SYSTEM_DIALOG_REASON_ASSIST) return false;
        // When the assistant is configured to be on top of the dream, it will have higher z-order
        // than other activities. If it is also opaque, it will prevent other activities from
        // starting. We want to close the assistant on closeSystemDialogs to allow other activities
        // to start, e.g. on home button press.
        return mWmService.mAssistantOnTopOfDream;
    }

    FinishDisabledPackageActivitiesHelper mFinishDisabledPackageActivitiesHelper =
            new FinishDisabledPackageActivitiesHelper();

    class FinishDisabledPackageActivitiesHelper implements Predicate<ActivityRecord> {
        private String mPackageName;
        private Set<String> mFilterByClasses;
        private boolean mDoit;
        private boolean mEvenPersistent;
        private int mUserId;
        private boolean mOnlyRemoveNoProcess;
        private Task mLastTask;
        private final ArrayList<ActivityRecord> mCollectedActivities = new ArrayList<>();

        private void reset(String packageName, Set<String> filterByClasses,
                boolean doit, boolean evenPersistent, int userId, boolean onlyRemoveNoProcess) {
            mPackageName = packageName;
            mFilterByClasses = filterByClasses;
            mDoit = doit;
            mEvenPersistent = evenPersistent;
            mUserId = userId;
            mOnlyRemoveNoProcess = onlyRemoveNoProcess;
            mLastTask = null;
        }

        boolean process(String packageName, Set<String> filterByClasses,
                boolean doit, boolean evenPersistent, int userId, boolean onlyRemoveNoProcess) {
            reset(packageName, filterByClasses, doit, evenPersistent, userId, onlyRemoveNoProcess);
            forAllActivities(this);

            boolean didSomething = false;
            final int size = mCollectedActivities.size();
            // Keep the finishing order from top to bottom.
            for (int i = 0; i < size; i++) {
                final ActivityRecord r = mCollectedActivities.get(i);
                if (mOnlyRemoveNoProcess) {
                    if (!r.hasProcess()) {
                        didSomething = true;
                        Slog.i(TAG, "  Force removing " + r);
                        r.cleanUp(false /* cleanServices */, false /* setState */);
                        r.removeFromHistory("force-stop");
                    }
                } else {
                    didSomething = true;
                    Slog.i(TAG, "  Force finishing " + r);
                    r.finishIfPossible("force-stop", true /* oomAdj */);
                }
            }
            mCollectedActivities.clear();

            return didSomething;
        }

        @Override
        public boolean test(ActivityRecord r) {
            final boolean sameComponent =
                    (r.packageName.equals(mPackageName) && (mFilterByClasses == null
                            || mFilterByClasses.contains(r.mActivityComponent.getClassName())))
                            || (mPackageName == null && r.mUserId == mUserId);
            final boolean noProcess = !r.hasProcess();
            if ((mUserId == UserHandle.USER_ALL || r.mUserId == mUserId)
                    && (sameComponent || r.getTask() == mLastTask)
                    && (noProcess || mEvenPersistent || !r.app.isPersistent())) {
                if (!mDoit) {
                    if (r.finishing) {
                        // If this activity is just finishing, then it is not
                        // interesting as far as something to stop.
                        return false;
                    }
                    return true;
                }
                mCollectedActivities.add(r);
                mLastTask = r.getTask();
            }

            return false;
        }
    }

    /** @return true if some activity was finished (or would have finished if doit were true). */
    boolean finishDisabledPackageActivities(String packageName, Set<String> filterByClasses,
            boolean doit, boolean evenPersistent, int userId, boolean onlyRemoveNoProcess) {
        return mFinishDisabledPackageActivitiesHelper.process(packageName, filterByClasses, doit,
                evenPersistent, userId, onlyRemoveNoProcess);
    }

    void updateActivityApplicationInfo(ApplicationInfo aInfo) {
        final String packageName = aInfo.packageName;
        final int userId = UserHandle.getUserId(aInfo.uid);
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootWindowContainer::updateActivityApplicationInfo,
                PooledLambda.__(ActivityRecord.class), aInfo, userId, packageName);
        forAllActivities(c);
        c.recycle();
    }

    private static void updateActivityApplicationInfo(
            ActivityRecord r, ApplicationInfo aInfo, int userId, String packageName) {
        if (r.mUserId == userId && packageName.equals(r.packageName)) {
            r.updateApplicationInfo(aInfo);
        }
    }

    void finishVoiceTask(IVoiceInteractionSession session) {
        forAllRootTasks(rootTask -> {
            rootTask.finishVoiceTask(session);
        });
    }

    /**
     * Removes root tasks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeRootTasksInWindowingModes(int... windowingModes) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            getChildAt(i).removeRootTasksInWindowingModes(windowingModes);
        }
    }

    void removeRootTasksWithActivityTypes(int... activityTypes) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            getChildAt(i).removeRootTasksWithActivityTypes(activityTypes);
        }
    }

    ActivityRecord topRunningActivity() {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityRecord topActivity = getChildAt(i).topRunningActivity();
            if (topActivity != null) {
                return topActivity;
            }
        }
        return null;
    }

    boolean allResumedActivitiesIdle() {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            // TODO(b/117135575): Check resumed activities on all visible root tasks.
            final DisplayContent display = getChildAt(displayNdx);
            if (display.isSleeping()) {
                // No resumed activities while display is sleeping.
                continue;
            }

            // If the focused root task is not null or not empty, there should have some activities
            // resuming or resumed. Make sure these activities are idle.
            final Task rootTask = display.getFocusedRootTask();
            if (rootTask == null || !rootTask.hasActivity()) {
                continue;
            }
            final ActivityRecord resumedActivity = rootTask.getTopResumedActivity();
            if (resumedActivity == null || !resumedActivity.idle) {
                ProtoLog.d(WM_DEBUG_STATES, "allResumedActivitiesIdle: rootTask=%d %s "
                        + "not idle", rootTask.getRootTaskId(), resumedActivity);
                return false;
            }
        }
        // End power mode launch when idle.
        mService.endLaunchPowerMode(ActivityTaskManagerService.POWER_MODE_REASON_START_ACTIVITY);
        return true;
    }

    boolean allResumedActivitiesVisible() {
        boolean[] foundResumed = {false};
        final boolean foundInvisibleResumedActivity = forAllRootTasks(rootTask -> {
            final ActivityRecord r = rootTask.getTopResumedActivity();
            if (r != null) {
                if (!r.nowVisible) {
                    return true;
                }
                foundResumed[0] = true;
            }
            return false;
        });
        if (foundInvisibleResumedActivity) {
            return false;
        }
        return foundResumed[0];
    }

    boolean allPausedActivitiesComplete() {
        boolean[] pausing = {true};
        final boolean hasActivityNotCompleted = forAllLeafTasks(task -> {
            final ActivityRecord r = task.getTopPausingActivity();
            if (r != null && !r.isState(PAUSED, STOPPED, STOPPING, FINISHING)) {
                ProtoLog.d(WM_DEBUG_STATES, "allPausedActivitiesComplete: "
                        + "r=%s state=%s", r, r.getState());
                if (WM_DEBUG_STATES.isEnabled()) {
                    pausing[0] = false;
                } else {
                    return true;
                }
            }
            return false;
        });
        if (hasActivityNotCompleted) {
            return false;
        }
        return pausing[0];
    }

    /**
     * Find all tasks containing {@param userId} and intercept them with an activity
     * to block out the contents and possibly start a credential-confirming intent.
     *
     * @param userId user handle for the locked managed profile.
     */
    void lockAllProfileTasks(@UserIdInt int userId) {
        forAllLeafTasks(task -> {
            final ActivityRecord top = task.topRunningActivity();
            if (top != null && !top.finishing
                    && ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER.equals(top.intent.getAction())
                    && top.packageName.equals(
                            mService.getSysUiServiceComponentLocked().getPackageName())) {
                // Do nothing since the task is already secure by sysui.
                return;
            }

            if (task.getActivity(activity -> !activity.finishing && activity.mUserId == userId)
                    != null) {
                mService.getTaskChangeNotificationController().notifyTaskProfileLocked(
                        task.mTaskId, userId);
            }
        }, true /* traverseTopToBottom */);
    }

    Task anyTaskForId(int id) {
        return anyTaskForId(id, MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE);
    }

    Task anyTaskForId(int id, @RootWindowContainer.AnyTaskForIdMatchTaskMode int matchMode) {
        return anyTaskForId(id, matchMode, null, !ON_TOP);
    }

    /**
     * Returns a {@link Task} for the input id if available. {@code null} otherwise.
     *
     * @param id        Id of the task we would like returned.
     * @param matchMode The mode to match the given task id in.
     * @param aOptions  The activity options to use for restoration. Can be null.
     * @param onTop     If the root task for the task should be the topmost on the display.
     */
    Task anyTaskForId(int id, @RootWindowContainer.AnyTaskForIdMatchTaskMode int matchMode,
            @Nullable ActivityOptions aOptions, boolean onTop) {
        // If options are set, ensure that we are attempting to actually restore a task
        if (matchMode != MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE && aOptions != null) {
            throw new IllegalArgumentException("Should not specify activity options for non-restore"
                    + " lookup");
        }

        final PooledPredicate p = PooledLambda.obtainPredicate(
                Task::isTaskId, PooledLambda.__(Task.class), id);
        Task task = getTask(p);
        p.recycle();

        if (task != null) {
            if (aOptions != null) {
                // Resolve the root task the task should be placed in now based on options
                // and reparent if needed.
                final Task targetRootTask =
                        getOrCreateRootTask(null, aOptions, task, onTop);
                if (targetRootTask != null && task.getRootTask() != targetRootTask) {
                    final int reparentMode = onTop
                            ? REPARENT_MOVE_ROOT_TASK_TO_FRONT : REPARENT_LEAVE_ROOT_TASK_IN_PLACE;
                    task.reparent(targetRootTask, onTop, reparentMode, ANIMATE, DEFER_RESUME,
                            "anyTaskForId");
                }
            }
            return task;
        }

        // If we are matching root task tasks only, return now
        if (matchMode == MATCH_ATTACHED_TASK_ONLY) {
            return null;
        }

        // Otherwise, check the recent tasks and return if we find it there and we are not restoring
        // the task from recents
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS, "Looking for task id=" + id + " in recents");
        task = mTaskSupervisor.mRecentTasks.getTask(id);

        if (task == null) {
            if (DEBUG_RECENTS) {
                Slog.d(TAG_RECENTS, "\tDidn't find task id=" + id + " in recents");
            }

            return null;
        }

        if (matchMode == MATCH_ATTACHED_TASK_OR_RECENT_TASKS) {
            return task;
        }

        // Implicitly, this case is MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE
        if (!mTaskSupervisor.restoreRecentTaskLocked(task, aOptions, onTop)) {
            if (DEBUG_RECENTS) {
                Slog.w(TAG_RECENTS,
                        "Couldn't restore task id=" + id + " found in recents");
            }
            return null;
        }
        if (DEBUG_RECENTS) Slog.w(TAG_RECENTS, "Restored task id=" + id + " from in recents");
        return task;
    }

    @VisibleForTesting
    void getRunningTasks(int maxNum, List<ActivityManager.RunningTaskInfo> list,
            int flags, int callingUid, ArraySet<Integer> profileIds) {
        mTaskSupervisor.getRunningTasks().getTasks(maxNum, list, flags, this, callingUid,
                profileIds);
    }

    void startPowerModeLaunchIfNeeded(boolean forceSend, ActivityRecord targetActivity) {
        if (!forceSend && targetActivity != null && targetActivity.app != null) {
            // Set power mode when the activity's process is different than the current top resumed
            // activity on all display areas, or if there are no resumed activities in the system.
            boolean[] noResumedActivities = {true};
            boolean[] allFocusedProcessesDiffer = {true};
            forAllTaskDisplayAreas(taskDisplayArea -> {
                final ActivityRecord resumedActivity = taskDisplayArea.getFocusedActivity();
                final WindowProcessController resumedActivityProcess =
                        resumedActivity == null ? null : resumedActivity.app;

                noResumedActivities[0] &= resumedActivityProcess == null;
                if (resumedActivityProcess != null) {
                    allFocusedProcessesDiffer[0] &=
                            !resumedActivityProcess.equals(targetActivity.app);
                }
            });
            if (!noResumedActivities[0] && !allFocusedProcessesDiffer[0]) {
                // All focused activities are resumed and the process of the target activity is
                // the same as them, e.g. delivering new intent to the current top.
                return;
            }
        }

        int reason = ActivityTaskManagerService.POWER_MODE_REASON_START_ACTIVITY;
        // If the activity is launching while keyguard is locked (including occluded), the activity
        // may be visible until its first relayout is done (e.g. apply show-when-lock flag). To
        // avoid power mode from being cleared before that, add a special reason to consider whether
        // the unknown visibility is resolved. The case from SystemUI is excluded because it should
        // rely on keyguard-going-away.
        final boolean isKeyguardLocked = (targetActivity != null)
                ? targetActivity.isKeyguardLocked() : mDefaultDisplay.isKeyguardLocked();
        if (isKeyguardLocked && targetActivity != null
                && !targetActivity.isLaunchSourceType(ActivityRecord.LAUNCH_SOURCE_TYPE_SYSTEMUI)) {
            final ActivityOptions opts = targetActivity.getOptions();
            if (opts == null || opts.getSourceInfo() == null
                    || opts.getSourceInfo().type != ActivityOptions.SourceInfo.TYPE_LOCKSCREEN) {
                reason |= ActivityTaskManagerService.POWER_MODE_REASON_UNKNOWN_VISIBILITY;
            }
        }
        mService.startLaunchPowerMode(reason);
    }

    /**
     * Iterate over all task fragments, to see if there exists one that meets the
     * PermissionPolicyService's criteria to show a permission dialog.
     */
    public int getTaskToShowPermissionDialogOn(String pkgName, int uid) {
        PermissionPolicyInternal pPi = mService.getPermissionPolicyInternal();
        if (pPi == null) {
            return INVALID_TASK_ID;
        }

        final int[] validTaskId = {INVALID_TASK_ID};
        forAllLeafTaskFragments(fragment -> {
            ActivityRecord record = fragment.getActivity((r) -> {
                // skip hidden (or about to hide) apps, or the permission dialog
                return r.canBeTopRunning() && r.isVisibleRequested()
                        && !pPi.isIntentToPermissionDialog(r.intent);
            });
            if (record != null && record.isUid(uid)
                    && Objects.equals(pkgName, record.packageName)
                    && pPi.shouldShowNotificationDialogForTask(record.getTask().getTaskInfo(),
                    pkgName, record.intent)) {
                validTaskId[0] = record.getTask().mTaskId;
                return true;
            }
            return false;
        });

        return validTaskId[0];
    }

    /**
     * Dumps the activities matching the given {@param name} in the either the focused root task
     * or all visible root tasks if {@param dumpVisibleRootTasksOnly} is true.
     */
    ArrayList<ActivityRecord> getDumpActivities(String name, boolean dumpVisibleRootTasksOnly,
            boolean dumpFocusedRootTaskOnly, @UserIdInt int userId) {
        if (dumpFocusedRootTaskOnly) {
            final Task topFocusedRootTask = getTopDisplayFocusedRootTask();
            if (topFocusedRootTask != null) {
                return topFocusedRootTask.getDumpActivitiesLocked(name, userId);
            } else {
                return new ArrayList<>();
            }
        } else {
            final RecentTasks recentTasks = mWindowManager.mAtmService.getRecentTasks();
            final int recentsComponentUid = recentTasks != null
                    ? recentTasks.getRecentsComponentUid()
                    : -1;
            final ArrayList<ActivityRecord> activities = new ArrayList<>();
            forAllLeafTasks(task -> {
                final boolean isRecents = (task.effectiveUid == recentsComponentUid);
                if (!dumpVisibleRootTasksOnly || task.shouldBeVisible(null) || isRecents) {
                    activities.addAll(task.getDumpActivitiesLocked(name, userId));
                }
                return false;
            });
            return activities;
        }
    }

    @Override
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix);
        pw.println("topDisplayFocusedRootTask=" + getTopDisplayFocusedRootTask());
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            display.dump(pw, prefix, dumpAll);
        }
        pw.println();
    }

    /**
     * Dump all connected displays' configurations.
     *
     * @param prefix Prefix to apply to each line of the dump.
     */
    void dumpDisplayConfigs(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("Display override configurations:");
        final int displayCount = getChildCount();
        for (int i = 0; i < displayCount; i++) {
            final DisplayContent displayContent = getChildAt(i);
            pw.print(prefix);
            pw.print("  ");
            pw.print(displayContent.mDisplayId);
            pw.print(": ");
            pw.println(displayContent.getRequestedOverrideConfiguration());
        }
    }

    boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage) {
        boolean[] printed = {false};
        boolean[] needSep = {false};
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            DisplayContent displayContent = getChildAt(displayNdx);
            if (printed[0]) {
                pw.println();
            }
            pw.print("Display #");
            pw.print(displayContent.mDisplayId);
            pw.println(" (activities from top to bottom):");
            displayContent.forAllRootTasks(rootTask -> {
                if (needSep[0]) {
                    pw.println();
                }
                needSep[0] = rootTask.dump(fd, pw, dumpAll, dumpClient, dumpPackage, false);
                printed[0] |= needSep[0];
            });
            displayContent.forAllTaskDisplayAreas(taskDisplayArea -> {
                printed[0] |= printThisActivity(pw, taskDisplayArea.getFocusedActivity(),
                        dumpPackage, needSep[0], "    Resumed: ", () ->
                                pw.println("  Resumed activities in task display areas"
                                        + " (from top to bottom):"));
            });
        }

        printed[0] |= dumpHistoryList(fd, pw, mTaskSupervisor.mFinishingActivities, "  ",
                "Fin", false, !dumpAll,
                false, dumpPackage, true,
                () -> pw.println("  Activities waiting to finish:"), null);
        printed[0] |= dumpHistoryList(fd, pw, mTaskSupervisor.mStoppingActivities, "  ",
                "Stop", false, !dumpAll,
                false, dumpPackage, true,
                () -> pw.println("  Activities waiting to stop:"), null);

        return printed[0];
    }

    private static int makeSleepTokenKey(String tag, int displayId) {
        final String tokenKey = tag + displayId;
        return tokenKey.hashCode();
    }

    static final class SleepToken {
        private final String mTag;
        private final long mAcquireTime;
        private final int mDisplayId;
        final int mHashKey;

        SleepToken(String tag, int displayId) {
            mTag = tag;
            mDisplayId = displayId;
            mAcquireTime = SystemClock.uptimeMillis();
            mHashKey = makeSleepTokenKey(mTag, mDisplayId);
        }

        @Override
        public String toString() {
            return "{\"" + mTag + "\", display " + mDisplayId
                    + ", acquire at " + TimeUtils.formatUptime(mAcquireTime) + "}";
        }

        void writeTagToProto(ProtoOutputStream proto, long fieldId) {
            proto.write(fieldId, mTag);
        }
    }

    private class RankTaskLayersRunnable implements Runnable {
        @Override
        public void run() {
            synchronized (mService.mGlobalLock) {
                if (mTaskLayersChanged) {
                    mTaskLayersChanged = false;
                    rankTaskLayers();
                }
            }
        }
    }

    private class AttachApplicationHelper implements Consumer<Task>, Predicate<ActivityRecord> {
        private boolean mHasActivityStarted;
        private RemoteException mRemoteException;
        private WindowProcessController mApp;
        private ActivityRecord mTop;

        void reset() {
            mHasActivityStarted = false;
            mRemoteException = null;
            mApp = null;
            mTop = null;
        }

        boolean process(WindowProcessController app) throws RemoteException {
            mApp = app;
            for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
                getChildAt(displayNdx).forAllRootTasks(this);
                if (mRemoteException != null) {
                    throw mRemoteException;
                }
            }
            if (!mHasActivityStarted) {
                ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                        false /* preserveWindows */);
            }
            return mHasActivityStarted;
        }

        @Override
        public void accept(Task rootTask) {
            if (mRemoteException != null) {
                return;
            }
            if (rootTask.getVisibility(null /* starting */)
                    == TASK_FRAGMENT_VISIBILITY_INVISIBLE) {
                return;
            }
            mTop = rootTask.topRunningActivity();
            rootTask.forAllActivities(this);
        }

        @Override
        public boolean test(ActivityRecord r) {
            if (r.finishing || !r.showToCurrentUser() || !r.visibleIgnoringKeyguard
                    || r.app != null || mApp.mUid != r.info.applicationInfo.uid
                    || !mApp.mName.equals(r.processName)) {
                return false;
            }

            try {
                if (mTaskSupervisor.realStartActivityLocked(r, mApp,
                        mTop == r && r.getTask().canBeResumed(r) /* andResume */,
                        true /* checkConfig */)) {
                    mHasActivityStarted = true;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception in new application when starting activity " + mTop, e);
                mRemoteException = e;
                return true;
            }
            return false;
        }
    }
}
