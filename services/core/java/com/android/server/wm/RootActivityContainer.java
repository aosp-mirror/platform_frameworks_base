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

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;

import static com.android.server.am.ActivityStackSupervisorProto.DISPLAYS;
import static com.android.server.am.ActivityStackSupervisorProto.FOCUSED_STACK_ID;
import static com.android.server.am.ActivityStackSupervisorProto.IS_HOME_RECENTS_COMPONENT;
import static com.android.server.am.ActivityStackSupervisorProto.KEYGUARD_CONTROLLER;
import static com.android.server.am.ActivityStackSupervisorProto.PENDING_ACTIVITIES;
import static com.android.server.am.ActivityStackSupervisorProto.RESUMED_ACTIVITY;
import static com.android.server.am.ActivityStackSupervisorProto.ROOT_WINDOW_CONTAINER;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.ActivityStackSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityStackSupervisor.printThisActivity;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ANIMATE;
import static com.android.server.wm.ActivityTaskManagerService.TAG_SWITCH;
import static com.android.server.wm.Task.REPARENT_LEAVE_STACK_IN_PLACE;
import static com.android.server.wm.Task.REPARENT_MOVE_STACK_TO_FRONT;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.power.V1_0.PowerHint;
import android.net.Uri;
import android.os.FactoryTest;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.UserState;
import com.android.server.policy.WindowManagerPolicy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Root node for activity containers.
 * TODO: This class is mostly temporary to separate things out of ActivityStackSupervisor.java. The
 * intention is to have this merged with RootWindowContainer.java as part of unifying the hierarchy.
 */
class RootActivityContainer extends RootWindowContainer
        implements DisplayManager.DisplayListener {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "RootActivityContainer" : TAG_ATM;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;

    /**
     * The modes which affect which tasks are returned when calling
     * {@link RootActivityContainer#anyTaskForId(int)}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MATCH_TASK_IN_STACKS_ONLY,
            MATCH_TASK_IN_STACKS_OR_RECENT_TASKS,
            MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE
    })
    public @interface AnyTaskForIdMatchTaskMode {}
    // Match only tasks in the current stacks
    static final int MATCH_TASK_IN_STACKS_ONLY = 0;
    // Match either tasks in the current stacks, or in the recent tasks if not found in the stacks
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS = 1;
    // Match either tasks in the current stacks, or in the recent tasks, restoring it to the
    // provided stack id
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE = 2;

    ActivityTaskManagerService mService;
    ActivityStackSupervisor mStackSupervisor;
    WindowManagerService mWindowManager;
    DisplayManager mDisplayManager;
    private DisplayManagerInternal mDisplayManagerInternal;

    /** Reference to default display so we can quickly look it up. */
    private DisplayContent mDefaultDisplay;
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();

    /** The current user */
    int mCurrentUser;
    /** Stack id of the front stack when user switched, indexed by userId. */
    SparseIntArray mUserStackInFront = new SparseIntArray(2);

    /**
     * A list of tokens that cause the top activity to be put to sleep.
     * They are used by components that may hide and block interaction with underlying
     * activities.
     */
    final ArrayList<ActivityTaskManagerInternal.SleepToken> mSleepTokens = new ArrayList<>();

    /** Is dock currently minimized. */
    boolean mIsDockMinimized;

    /** Set when a power hint has started, but not ended. */
    private boolean mPowerHintSent;

    /** Used to keep ensureActivitiesVisible() from being entered recursively. */
    private boolean mInEnsureActivitiesVisible = false;

    // The default minimal size that will be used if the activity doesn't specify its minimal size.
    // It will be calculated when the default display gets added.
    int mDefaultMinSizeOfResizeableTaskDp = -1;

    // Whether tasks have moved and we need to rank the tasks before next OOM scoring
    private boolean mTaskLayersChanged = true;
    private int mTmpTaskLayerRank;

    private boolean mTmpBoolean;
    private RemoteException mTmpRemoteException;

    private String mDestroyAllActivitiesReason;
    private final Runnable mDestroyAllActivitiesRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mService.mGlobalLock) {
                try {
                    mStackSupervisor.beginDeferResume();

                    final PooledConsumer c = PooledLambda.obtainConsumer(
                            RootActivityContainer::destroyActivity, RootActivityContainer.this,
                            PooledLambda.__(ActivityRecord.class));
                    forAllActivities(c);
                    c.recycle();
                } finally {
                    mStackSupervisor.endDeferResume();
                    resumeFocusedStacksTopActivities();
                }
            }
        }

    };

    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();
    static class FindTaskResult implements Function<Task, Boolean> {
        ActivityRecord mRecord;
        boolean mIdealMatch;

        private ActivityRecord mTarget;
        private Intent intent;
        private ActivityInfo info;
        private ComponentName cls;
        private int userId;
        private boolean isDocument;
        private Uri documentData;

        /**
         * Returns the top activity in any existing task matching the given Intent in the input
         * result. Returns null if no such task is found.
         */
        void process(ActivityRecord target, ActivityStack parent) {
            mTarget = target;

            intent = target.intent;
            info = target.info;
            cls = intent.getComponent();
            if (info.targetActivity != null) {
                cls = new ComponentName(info.packageName, info.targetActivity);
            }
            userId = UserHandle.getUserId(info.applicationInfo.uid);
            isDocument = intent != null & intent.isDocument();
            // If documentData is non-null then it must match the existing task data.
            documentData = isDocument ? intent.getData() : null;

            if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Looking for task of " + target + " in " + parent);
            parent.forAllTasks(this);
        }

        void clear() {
            mRecord = null;
            mIdealMatch = false;
        }

        void setTo(FindTaskResult result) {
            mRecord = result.mRecord;
            mIdealMatch = result.mIdealMatch;
        }

        @Override
        public Boolean apply(Task task) {
            if (task.voiceSession != null) {
                // We never match voice sessions; those always run independently.
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": voice session");
                return false;
            }
            if (task.mUserId != userId) {
                // Looking for a different task.
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": different user");
                return false;
            }

            // Overlays should not be considered as the task's logical top activity.
            final ActivityRecord r = task.getTopNonFinishingActivity(false /* includeOverlays */);
            if (r == null || r.finishing || r.mUserId != userId ||
                    r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": mismatch root " + r);
                return false;
            }
            if (!r.hasCompatibleActivityType(mTarget)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping " + task + ": mismatch activity type");
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

            if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Comparing existing cls="
                    + (task.realActivity != null ? task.realActivity.flattenToShortString() : "")
                    + "/aff=" + r.getTask().rootAffinity + " to new cls="
                    + intent.getComponent().flattenToShortString() + "/aff=" + info.taskAffinity);
            // TODO Refactor to remove duplications. Check if logic can be simplified.
            if (task.realActivity != null && task.realActivity.compareTo(cls) == 0
                    && Objects.equals(documentData, taskDocumentData)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching class!");
                //dump();
                if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                        "For Intent " + intent + " bringing to top: " + r.intent);
                mRecord = r;
                mIdealMatch = true;
                return true;
            } else if (affinityIntent != null && affinityIntent.getComponent() != null &&
                    affinityIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching class!");
                if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                        "For Intent " + intent + " bringing to top: " + r.intent);
                mRecord = r;
                mIdealMatch = true;
                return true;
            } else if (!isDocument && !taskIsDocument
                    && mRecord == null && task.rootAffinity != null) {
                if (task.rootAffinity.equals(mTarget.taskAffinity)) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Found matching affinity candidate!");
                    // It is possible for multiple tasks to have the same root affinity especially
                    // if they are in separate stacks. We save off this candidate, but keep looking
                    // to see if there is a better candidate.
                    mRecord = r;
                    mIdealMatch = false;
                }
            } else if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Not a match: " + task);

            return false;
        }
    }

    RootActivityContainer(ActivityTaskManagerService service, WindowManagerService wmService) {
        super(wmService);
        mService = service;
        mStackSupervisor = service.mStackSupervisor;
        mStackSupervisor.mRootActivityContainer = this;
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
            if (displayContent.mDisplayId == DEFAULT_DISPLAY) {
                mDefaultDisplay = displayContent;
            }
        }
        calculateDefaultMinimalSizeOfResizeableTasks();

        final DisplayContent defaultDisplay = getDefaultDisplay();

        defaultDisplay.getOrCreateStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        positionChildAt(POSITION_TOP, defaultDisplay, false /* includingParents */);
    }

    // TODO(multi-display): Look at all callpoints to make sure they make sense in multi-display.
    DisplayContent getDefaultDisplay() {
        return mDefaultDisplay;
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
    @Nullable DisplayContent getDisplayContentOrCreate(int displayId) {
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
        return displayContent;
    }

    ActivityRecord getDefaultDisplayHomeActivity() {
        return getDefaultDisplayHomeActivityForUser(mCurrentUser);
    }

    ActivityRecord getDefaultDisplayHomeActivityForUser(int userId) {
        return getDisplayContent(DEFAULT_DISPLAY).getHomeActivityForUser(userId);
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
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final DisplayContent display = getChildAt(i);
            if (display.topRunningActivity() == null) {
                startHomeOnDisplay(mCurrentUser, reason, display.mDisplayId);
            }
        }
    }

    boolean startHomeOnDisplay(int userId, String reason, int displayId) {
        return startHomeOnDisplay(userId, reason, displayId, false /* allowInstrumenting */,
                false /* fromHomeKey */);
    }

    /**
     * This starts home activity on displays that can have system decorations based on displayId -
     * Default display always use primary home component.
     * For Secondary displays, the home activity must have category SECONDARY_HOME and then resolves
     * according to the priorities listed below.
     *  - If default home is not set, always use the secondary home defined in the config.
     *  - Use currently selected primary home activity.
     *  - Use the activity in the same package as currently selected primary home activity.
     *    If there are multiple activities matched, use first one.
     *  - Use the secondary home defined in the config.
     */
    boolean startHomeOnDisplay(int userId, String reason, int displayId, boolean allowInstrumenting,
            boolean fromHomeKey) {
        // Fallback to top focused display if the displayId is invalid.
        if (displayId == INVALID_DISPLAY) {
            final ActivityStack stack = getTopDisplayFocusedStack();
            displayId = stack != null ? stack.mDisplayId : DEFAULT_DISPLAY;
        }

        Intent homeIntent = null;
        ActivityInfo aInfo = null;
        if (displayId == DEFAULT_DISPLAY) {
            homeIntent = mService.getHomeIntent();
            aInfo = resolveHomeActivity(userId, homeIntent);
        } else if (shouldPlaceSecondaryHomeOnDisplay(displayId)) {
            Pair<ActivityInfo, Intent> info = resolveSecondaryHomeActivity(userId, displayId);
            aInfo = info.first;
            homeIntent = info.second;
        }
        if (aInfo == null || homeIntent == null) {
            return false;
        }

        if (!canStartHomeOnDisplay(aInfo, displayId, allowInstrumenting)) {
            return false;
        }

        // Updates the home component of the intent.
        homeIntent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
        homeIntent.setFlags(homeIntent.getFlags() | FLAG_ACTIVITY_NEW_TASK);
        // Updates the extra information of the intent.
        if (fromHomeKey) {
            homeIntent.putExtra(WindowManagerPolicy.EXTRA_FROM_HOME_KEY, true);
        }
        // Update the reason for ANR debugging to verify if the user activity is the one that
        // actually launched.
        final String myReason = reason + ":" + userId + ":" + UserHandle.getUserId(
                aInfo.applicationInfo.uid) + ":" + displayId;
        mService.getActivityStartController().startHomeActivity(homeIntent, aInfo, myReason,
                displayId);
        return true;
    }

    /**
     * This resolves the home activity info.
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
    Pair<ActivityInfo, Intent> resolveSecondaryHomeActivity(int userId, int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "resolveSecondaryHomeActivity: Should not be DEFAULT_DISPLAY");
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
            if (!canStartHomeOnDisplay(aInfo, displayId, false /* allowInstrumenting */)) {
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

    boolean resumeHomeActivity(ActivityRecord prev, String reason, int displayId) {
        if (!mService.isBooting() && !mService.isBooted()) {
            // Not ready yet!
            return false;
        }

        if (displayId == INVALID_DISPLAY) {
            displayId = DEFAULT_DISPLAY;
        }

        final ActivityRecord r = getDisplayContent(displayId).getHomeActivity();
        final String myReason = reason + " resumeHomeActivity";

        // Only resume home activity if isn't finishing.
        if (r != null && !r.finishing) {
            r.moveFocusableActivityToTop(myReason);
            return resumeFocusedStacksTopActivities(r.getActivityStack(), prev, null);
        }
        return startHomeOnDisplay(mCurrentUser, myReason, displayId);
    }

    /**
     * Check if the display is valid for secondary home activity.
     * @param displayId The id of the target display.
     * @return {@code true} if allow to launch, {@code false} otherwise.
     */
    boolean shouldPlaceSecondaryHomeOnDisplay(int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "shouldPlaceSecondaryHomeOnDisplay: Should not be DEFAULT_DISPLAY");
        } else if (displayId == INVALID_DISPLAY) {
            return false;
        }

        if (!mService.mSupportsMultiDisplay) {
            // Can't launch home on secondary display if device does not support multi-display.
            return false;
        }

        final boolean deviceProvisioned = Settings.Global.getInt(
                mService.mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (!deviceProvisioned) {
            // Can't launch home on secondary display before device is provisioned.
            return false;
        }

        if (!StorageManager.isUserKeyUnlocked(mCurrentUser)) {
            // Can't launch home on secondary displays if device is still locked.
            return false;
        }

        final DisplayContent display = getDisplayContent(displayId);
        if (display == null || display.isRemoved() || !display.supportsSystemDecorations()) {
            // Can't launch home on display that doesn't support system decorations.
            return false;
        }

        return true;
    }

    /**
     * Check if home activity start should be allowed on a display.
     * @param homeInfo {@code ActivityInfo} of the home activity that is going to be launched.
     * @param displayId The id of the target display.
     * @param allowInstrumenting Whether launching home should be allowed if being instrumented.
     * @return {@code true} if allow to launch, {@code false} otherwise.
     */
    boolean canStartHomeOnDisplay(ActivityInfo homeInfo, int displayId,
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

        if (displayId == DEFAULT_DISPLAY || (displayId != INVALID_DISPLAY
                && displayId == mService.mVr2dDisplayId)) {
            // No restrictions to default display or vr 2d display.
            return true;
        }

        if (!shouldPlaceSecondaryHomeOnDisplay(displayId)) {
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
     * @param starting The currently starting activity or {@code null} if there is none.
     * @param displayId The id of the display where operation is executed.
     * @param markFrozenIfConfigChanged Whether to set {@link ActivityRecord#frozenBeforeDestroy} to
     *                                  {@code true} if config changed.
     * @param deferResume Whether to defer resume while updating config.
     * @return 'true' if starting activity was kept or wasn't provided, 'false' if it was relaunched
     *         because of configuration update.
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
            config = displayContent.updateOrientation(
                    getDisplayOverrideConfiguration(displayId),
                    starting != null && starting.mayFreezeScreenLocked()
                            ? starting.appToken : null,
                    true /* forceUpdate */);
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
     * @return a list of activities which are the top ones in each visible stack. The first
     * entry will be the focused activity.
     */
    List<IBinder> getTopVisibleActivities() {
        final ArrayList<IBinder> topActivityTokens = new ArrayList<>();
        final ActivityStack topFocusedStack = getTopDisplayFocusedStack();
        // Traverse all displays.
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final DisplayContent display = getChildAt(i);
            // Traverse all stacks on a display.
            for (int j = display.getStackCount() - 1; j >= 0; --j) {
                final ActivityStack stack = display.getStackAt(j);
                // Get top activity from a visible stack and add it to the list.
                if (stack.shouldBeVisible(null /* starting */)) {
                    final ActivityRecord top = stack.getTopNonFinishingActivity();
                    if (top != null) {
                        if (stack == topFocusedStack) {
                            topActivityTokens.add(0, top.appToken);
                        } else {
                            topActivityTokens.add(top.appToken);
                        }
                    }
                }
            }
        }
        return topActivityTokens;
    }

    ActivityStack getTopDisplayFocusedStack() {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityStack focusedStack = getChildAt(i).getFocusedStack();
            if (focusedStack != null) {
                return focusedStack;
            }
        }
        return null;
    }

    ActivityRecord getTopResumedActivity() {
        final ActivityStack focusedStack = getTopDisplayFocusedStack();
        if (focusedStack == null) {
            return null;
        }
        final ActivityRecord resumedActivity = focusedStack.getResumedActivity();
        if (resumedActivity != null && resumedActivity.app != null) {
            return resumedActivity;
        }
        // The top focused stack might not have a resumed activity yet - look on all displays in
        // focus order.
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            final ActivityRecord resumedActivityOnDisplay = display.getResumedActivity();
            if (resumedActivityOnDisplay != null) {
                return resumedActivityOnDisplay;
            }
        }
        return null;
    }

    boolean isFocusable(ConfigurationContainer container, boolean alwaysFocusable) {
        if (container.inSplitScreenPrimaryWindowingMode() && mIsDockMinimized) {
            return false;
        }

        return container.getWindowConfiguration().canReceiveKeys() || alwaysFocusable;
    }

    boolean isTopDisplayFocusedStack(ActivityStack stack) {
        return stack != null && stack == getTopDisplayFocusedStack();
    }

    void updatePreviousProcess(ActivityRecord r) {
        // Now that this process has stopped, we may want to consider it to be the previous app to
        // try to keep around in case the user wants to return to it.

        // First, found out what is currently the foreground app, so that we don't blow away the
        // previous app if this activity is being hosted by the process that is actually still the
        // foreground.
        WindowProcessController fgApp = null;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                if (isTopDisplayFocusedStack(stack)) {
                    final ActivityRecord resumedActivity = stack.getResumedActivity();
                    if (resumedActivity != null) {
                        fgApp = resumedActivity.app;
                    } else if (stack.mPausingActivity != null) {
                        fgApp = stack.mPausingActivity.app;
                    }
                    break;
                }
            }
        }

        // Now set this one as the previous process, only if that really makes sense to.
        if (r.hasProcess() && fgApp != null && r.app != fgApp
                && r.lastVisibleTime > mService.mPreviousProcessVisibleTime
                && r.app != mService.mHomeProcess) {
            mService.mPreviousProcess = r.app;
            mService.mPreviousProcessVisibleTime = r.lastVisibleTime;
        }
    }

    boolean attachApplication(WindowProcessController app) throws RemoteException {
        final String processName = app.mName;
        boolean didSomething = false;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            final ActivityStack stack = display.getFocusedStack();
            if (stack == null) {
                continue;
            }

            mTmpRemoteException = null;
            mTmpBoolean = false; // Set to true if an activity was started.
            final PooledFunction c = PooledLambda.obtainFunction(
                    RootActivityContainer::startActivityForAttachedApplicationIfNeeded, this,
                    PooledLambda.__(ActivityRecord.class), app, stack.topRunningActivity());
            stack.forAllActivities(c);
            c.recycle();
            if (mTmpRemoteException != null) {
                throw mTmpRemoteException;
            }
            didSomething |= mTmpBoolean;
        }
        if (!didSomething) {
            ensureActivitiesVisible(null, 0, false /* preserve_windows */);
        }
        return didSomething;
    }

    private boolean startActivityForAttachedApplicationIfNeeded(ActivityRecord r,
            WindowProcessController app, ActivityRecord top) {
        if (r.finishing || !r.okToShowLocked() || !r.visibleIgnoringKeyguard || r.app != null
                || app.mUid != r.info.applicationInfo.uid || !app.mName.equals(r.processName)) {
            return false;
        }

        try {
            if (mStackSupervisor.realStartActivityLocked(r, app, top == r /*andResume*/,
                    true /*checkConfig*/)) {
                mTmpBoolean = true;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception in new application when starting activity "
                    + top.intent.getComponent().flattenToShortString(), e);
            mTmpRemoteException = e;
            return true;
        }
        return false;
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
        if (mInEnsureActivitiesVisible) {
            // Don't do recursive work.
            return;
        }
        mInEnsureActivitiesVisible = true;

        try {
            mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
            // First the front stacks. In case any are not fullscreen and are in front of home.
            for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
                final DisplayContent display = getChildAt(displayNdx);
                display.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                        notifyClients);
            }
        } finally {
            mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
            mInEnsureActivitiesVisible = false;
        }
    }

    boolean switchUser(int userId, UserState uss) {
        final int focusStackId = getTopDisplayFocusedStack().getStackId();
        // We dismiss the docked stack whenever we switch users.
        final ActivityStack dockedStack = getDefaultDisplay().getSplitScreenPrimaryStack();
        if (dockedStack != null) {
            mStackSupervisor.moveTasksToFullscreenStackLocked(
                    dockedStack, dockedStack.isFocusedStackOnDisplay());
        }
        // Also dismiss the pinned stack whenever we switch users. Removing the pinned stack will
        // also cause all tasks to be moved to the fullscreen stack at a position that is
        // appropriate.
        removeStacksInWindowingModes(WINDOWING_MODE_PINNED);

        mUserStackInFront.put(mCurrentUser, focusStackId);
        final int restoreStackId =
                mUserStackInFront.get(userId, getDefaultDisplay().getHomeStack().mStackId);
        mCurrentUser = userId;

        mStackSupervisor.mStartingUsers.add(uss);
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                stack.switchUser(userId);
                Task task = stack.getTopMostTask();
                if (task != null) {
                    stack.positionChildAtTop(task);
                }
            }
        }

        ActivityStack stack = getStack(restoreStackId);
        if (stack == null) {
            stack = getDefaultDisplay().getHomeStack();
        }
        final boolean homeInFront = stack.isActivityTypeHome();
        if (stack.isOnHomeDisplay()) {
            stack.moveToFront("switchUserOnHomeDisplay");
        } else {
            // Stack was moved to another display while user was swapped out.
            resumeHomeActivity(null, "switchUserOnOtherDisplay", DEFAULT_DISPLAY);
        }
        return homeInFront;
    }

    void removeUser(int userId) {
        mUserStackInFront.delete(userId);
    }

    /**
     * Update the last used stack id for non-current user (current user's last
     * used stack is the focused stack)
     */
    void updateUserStack(int userId, ActivityStack stack) {
        if (userId != mCurrentUser) {
            mUserStackInFront.put(userId, stack != null ? stack.getStackId()
                    : getDefaultDisplay().getHomeStack().mStackId);
        }
    }

    /**
     * Move stack with all its existing content to specified display.
     * @param stackId Id of stack to move.
     * @param displayId Id of display to move stack to.
     * @param onTop Indicates whether container should be place on top or on bottom.
     */
    void moveStackToDisplay(int stackId, int displayId, boolean onTop) {
        final DisplayContent displayContent = getDisplayContentOrCreate(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException("moveStackToDisplay: Unknown displayId="
                    + displayId);
        }
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveStackToDisplay: Unknown stackId="
                    + stackId);
        }

        final DisplayContent currentDisplay = stack.getDisplay();
        if (currentDisplay == null) {
            throw new IllegalStateException("moveStackToDisplay: Stack with stack=" + stack
                    + " is not attached to any display.");
        }

        if (currentDisplay.mDisplayId == displayId) {
            throw new IllegalArgumentException("Trying to move stack=" + stack
                    + " to its current displayId=" + displayId);
        }

        if (displayContent.isSingleTaskInstance() && displayContent.getStackCount() > 0) {
            // We don't allow moving stacks to single instance display that already has a child.
            Slog.e(TAG, "Can not move stack=" + stack
                    + " to single task instance display=" + displayContent);
            return;
        }

        stack.reparent(displayContent.mDisplayContent, onTop);
        // TODO(multi-display): resize stacks properly if moved from split-screen.
    }

    boolean moveTopStackActivityToPinnedStack(int stackId) {
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException(
                    "moveTopStackActivityToPinnedStack: Unknown stackId=" + stackId);
        }

        final ActivityRecord r = stack.topRunningActivity();
        if (r == null) {
            Slog.w(TAG, "moveTopStackActivityToPinnedStack: No top running activity"
                    + " in stack=" + stack);
            return false;
        }

        if (!mService.mForceResizableActivities && !r.supportsPictureInPicture()) {
            Slog.w(TAG, "moveTopStackActivityToPinnedStack: Picture-In-Picture not supported for "
                    + " r=" + r);
            return false;
        }

        moveActivityToPinnedStack(r, null /* sourceBounds */, 0f /* aspectRatio */,
                "moveTopActivityToPinnedStack");
        return true;
    }

    void moveActivityToPinnedStack(ActivityRecord r, Rect sourceHintBounds, float aspectRatio,
            String reason) {
        mService.deferWindowLayout();

        final DisplayContent display = r.getActivityStack().getDisplay();

        try {
            final Task task = r.getTask();

            final ActivityStack pinnedStack = display.getPinnedStack();
            // This will change the pinned stack's windowing mode to its original mode, ensuring
            // we only have one stack that is in pinned mode.
            if (pinnedStack != null) {
                pinnedStack.dismissPip();
            }

            final boolean singleActivity = task.getChildCount() == 1;

            final ActivityStack stack;
            if (singleActivity) {
                stack = r.getActivityStack();
            } else {
                // In the case of multiple activities, we will create a new stack for it and then
                // move the PIP activity into the stack.
                // We will then perform a windowing mode change for both scenarios.
                stack = display.createStack(
                        r.getActivityStack().getRequestedOverrideWindowingMode(),
                        r.getActivityType(), ON_TOP);
                // There are multiple activities in the task and moving the top activity should
                // reveal/leave the other activities in their original task.

                // Currently, we don't support reparenting activities across tasks in two different
                // stacks, so instead, just create a new task in the same stack, reparent the
                // activity into that task, and then reparent the whole task to the new stack. This
                // ensures that all the necessary work to migrate states in the old and new stacks
                // is also done.
                final Task newTask = task.getStack().createTask(
                        mStackSupervisor.getNextTaskIdForUserLocked(r.mUserId), r.info,
                        r.intent, null, null, true);
                r.reparent(newTask, MAX_VALUE, "moveActivityToStack");

                // Defer resume until below, and do not schedule PiP changes until we animate below
                newTask.reparent(stack, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, !ANIMATE,
                        DEFER_RESUME, false /* schedulePictureInPictureModeChange */, reason);
            }

            stack.setWindowingMode(WINDOWING_MODE_PINNED);

            // Reset the state that indicates it can enter PiP while pausing after we've moved it
            // to the pinned stack
            r.supportsEnterPipOnTaskSwitch = false;
        } finally {
            mService.continueWindowLayout();
        }

        // Notify the pinned stack controller to prepare the PiP animation, expect callback
        // delivered from SystemUI to WM to start the animation.
        final PinnedStackController pinnedStackController =
                display.mDisplayContent.getPinnedStackController();
        pinnedStackController.prepareAnimation(sourceHintBounds, aspectRatio,
                null /* stackBounds */);

        // TODO: revisit the following statement after the animation is moved from WM to SysUI.
        // Update the visibility of all activities after the they have been reparented to the new
        // stack.  This MUST run after the animation above is scheduled to ensure that the windows
        // drawn signal is scheduled after the bounds animation start call on the bounds animator
        // thread.
        ensureActivitiesVisible(null, 0, false /* preserveWindows */);
        resumeFocusedStacksTopActivities();

        mService.getTaskChangeNotificationController().notifyActivityPinned(r);
    }

    void executeAppTransitionForAllDisplay() {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            display.mDisplayContent.executeAppTransition();
        }
    }

    void setDockedStackMinimized(boolean minimized) {
        // Get currently focused stack before setting mIsDockMinimized. We do this because if
        // split-screen is active, primary stack will not be focusable (see #isFocusable) while
        // still occluding other stacks. This will cause getTopDisplayFocusedStack() to return null.
        final ActivityStack current = getTopDisplayFocusedStack();
        mIsDockMinimized = minimized;
        if (mIsDockMinimized) {
            if (current.inSplitScreenPrimaryWindowingMode()) {
                // The primary split-screen stack can't be focused while it is minimize, so move
                // focus to something else.
                current.adjustFocusToNextFocusableStack("setDockedStackMinimized");
            }
        }
    }

    ActivityRecord findTask(ActivityRecord r, int preferredDisplayId) {
        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Looking for task of " + r);
        mTmpFindTaskResult.clear();

        // Looking up task on preferred display first
        final DisplayContent preferredDisplay = getDisplayContent(preferredDisplayId);
        if (preferredDisplay != null) {
            preferredDisplay.findTaskLocked(r, true /* isPreferredDisplay */, mTmpFindTaskResult);
            if (mTmpFindTaskResult.mIdealMatch) {
                return mTmpFindTaskResult.mRecord;
            }
        }

        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            if (display.mDisplayId == preferredDisplayId) {
                continue;
            }

            display.findTaskLocked(r, false /* isPreferredDisplay */, mTmpFindTaskResult);
            if (mTmpFindTaskResult.mIdealMatch) {
                return mTmpFindTaskResult.mRecord;
            }
        }

        if (DEBUG_TASKS && mTmpFindTaskResult.mRecord == null) Slog.d(TAG_TASKS, "No task found");
        return mTmpFindTaskResult.mRecord;
    }

    /**
     * Finish the topmost activities in all stacks that belong to the crashed app.
     * @param app The app that crashed.
     * @param reason Reason to perform this action.
     * @return The task id that was finished in this stack, or INVALID_TASK_ID if none was finished.
     */
    int finishTopCrashedActivities(WindowProcessController app, String reason) {
        Task finishedTask = null;
        ActivityStack focusedStack = getTopDisplayFocusedStack();
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            // It is possible that request to finish activity might also remove its task and stack,
            // so we need to be careful with indexes in the loop and check child count every time.
            for (int stackNdx = 0; stackNdx < display.getStackCount(); ++stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                final Task t = stack.finishTopCrashedActivityLocked(app, reason);
                if (stack == focusedStack || finishedTask == null) {
                    finishedTask = t;
                }
            }
        }
        return finishedTask != null ? finishedTask.mTaskId : INVALID_TASK_ID;
    }

    boolean resumeFocusedStacksTopActivities() {
        return resumeFocusedStacksTopActivities(null, null, null);
    }

    boolean resumeFocusedStacksTopActivities(
            ActivityStack targetStack, ActivityRecord target, ActivityOptions targetOptions) {

        if (!mStackSupervisor.readyToResume()) {
            return false;
        }

        boolean result = false;
        if (targetStack != null && (targetStack.isTopStackOnDisplay()
                || getTopDisplayFocusedStack() == targetStack)) {
            result = targetStack.resumeTopActivityUncheckedLocked(target, targetOptions);
        }

        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            boolean resumedOnDisplay = false;
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                final ActivityRecord topRunningActivity = stack.topRunningActivity();
                if (!stack.isFocusableAndVisible() || topRunningActivity == null) {
                    continue;
                }
                if (stack == targetStack) {
                    // Simply update the result for targetStack because the targetStack had
                    // already resumed in above. We don't want to resume it again, especially in
                    // some cases, it would cause a second launch failure if app process was dead.
                    resumedOnDisplay |= result;
                    continue;
                }
                if (display.isTopStack(stack) && topRunningActivity.isState(RESUMED)) {
                    // Kick off any lingering app transitions form the MoveTaskToFront operation,
                    // but only consider the top task and stack on that display.
                    stack.executeAppTransition(targetOptions);
                } else {
                    resumedOnDisplay |= topRunningActivity.makeActiveIfNeeded(target);
                }
            }
            if (!resumedOnDisplay) {
                // In cases when there are no valid activities (e.g. device just booted or launcher
                // crashed) it's possible that nothing was resumed on a display. Requesting resume
                // of top activity in focused stack explicitly will make sure that at least home
                // activity is started and resumed, and no recursion occurs.
                final ActivityStack focusedStack = display.getFocusedStack();
                if (focusedStack != null) {
                    result |= focusedStack.resumeTopActivityUncheckedLocked(target, targetOptions);
                } else if (targetStack == null && display.getStackCount() == 0) {
                    result |= resumeHomeActivity(null /* prev */, "empty-display",
                            display.mDisplayId);
                }
            }
        }

        return result;
    }

    void applySleepTokens(boolean applyToStacks) {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            // Set the sleeping state of the display.
            final DisplayContent display = getChildAt(displayNdx);
            final boolean displayShouldSleep = display.shouldSleep();
            if (displayShouldSleep == display.isSleeping()) {
                continue;
            }
            display.setIsSleeping(displayShouldSleep);

            if (!applyToStacks) {
                continue;
            }

            // Set the sleeping state of the stacks on the display.
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                if (displayShouldSleep) {
                    stack.goToSleepIfPossible(false /* shuttingDown */);
                } else {
                    // When the display which can only contain one task turns on, start a special
                    // transition. {@link AppTransitionController#handleAppTransitionReady} later
                    // picks up the transition, and schedules
                    // {@link ITaskStackListener#onSingleTaskDisplayDrawn} callback which is
                    // triggered after contents are drawn on the display.
                    if (display.isSingleTaskInstance()) {
                        display.mDisplayContent.prepareAppTransition(
                                TRANSIT_SHOW_SINGLE_TASK_DISPLAY, false);
                    }
                    stack.awakeFromSleepingLocked();
                    if (stack.isFocusedStackOnDisplay()
                            && !mStackSupervisor.getKeyguardController()
                            .isKeyguardOrAodShowing(display.mDisplayId)) {
                        // If the keyguard is unlocked - resume immediately.
                        // It is possible that the display will not be awake at the time we
                        // process the keyguard going away, which can happen before the sleep token
                        // is released. As a result, it is important we resume the activity here.
                        resumeFocusedStacksTopActivities();
                    }
                }
            }

            if (displayShouldSleep || mStackSupervisor.mGoingToSleepActivities.isEmpty()) {
                continue;
            }
            // The display is awake now, so clean up the going to sleep list.
            for (Iterator<ActivityRecord> it =
                 mStackSupervisor.mGoingToSleepActivities.iterator(); it.hasNext(); ) {
                final ActivityRecord r = it.next();
                if (r.getDisplayId() == display.mDisplayId) {
                    it.remove();
                }
            }
        }
    }

    protected ActivityStack getStack(int stackId) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getChildAt(i).getStack(stackId);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    /** @see DisplayContent#getStack(int, int) */
    ActivityStack getStack(int windowingMode, int activityType) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getChildAt(i).getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private ActivityStack getStack(int windowingMode, int activityType,
            int displayId) {
        DisplayContent display = getDisplayContent(displayId);
        if (display == null) {
            return null;
        }
        return display.getStack(windowingMode, activityType);
    }

    private ActivityManager.StackInfo getStackInfo(ActivityStack stack) {
        final int displayId = stack.mDisplayId;
        final DisplayContent display = getDisplayContent(displayId);
        ActivityManager.StackInfo info = new ActivityManager.StackInfo();
        stack.getBounds(info.bounds);
        info.displayId = displayId;
        info.stackId = stack.mStackId;
        info.stackToken = stack.mRemoteToken;
        info.userId = stack.mCurrentUser;
        info.visible = stack.shouldBeVisible(null);
        // A stack might be not attached to a display.
        info.position = display != null ? display.getIndexOf(stack) : 0;
        info.configuration.setTo(stack.getConfiguration());

        final int numTasks = stack.getChildCount();
        info.taskIds = new int[numTasks];
        info.taskNames = new String[numTasks];
        info.taskBounds = new Rect[numTasks];
        info.taskUserIds = new int[numTasks];
        final int[] currenIndex = {0};

        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootActivityContainer::processTaskForStackInfo, PooledLambda.__(Task.class), info,
                currenIndex);
        stack.forAllTasks(c, false);
        c.recycle();

        final ActivityRecord top = stack.topRunningActivity();
        info.topActivity = top != null ? top.intent.getComponent() : null;
        return info;
    }

    private static void processTaskForStackInfo(
            Task task, ActivityManager.StackInfo info, int[] currentIndex) {
        int i = currentIndex[0];
        info.taskIds[i] = task.mTaskId;
        info.taskNames[i] = task.origActivity != null ? task.origActivity.flattenToString()
                : task.realActivity != null ? task.realActivity.flattenToString()
                        : task.getTopNonFinishingActivity() != null
                                ? task.getTopNonFinishingActivity().packageName : "unknown";
        info.taskBounds[i] = task.mAtmService.getTaskBounds(task.mTaskId);
        info.taskUserIds[i] = task.mUserId;
        currentIndex[0] = ++i;
    }

    ActivityManager.StackInfo getStackInfo(int stackId) {
        ActivityStack stack = getStack(stackId);
        if (stack != null) {
            return getStackInfo(stack);
        }
        return null;
    }

    ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType) {
        final ActivityStack stack = getStack(windowingMode, activityType);
        return (stack != null) ? getStackInfo(stack) : null;
    }

    ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType, int displayId) {
        final ActivityStack stack = getStack(windowingMode, activityType, displayId);
        return (stack != null) ? getStackInfo(stack) : null;
    }

    /** If displayId == INVALID_DISPLAY, this will get stack infos on all displays */
    ArrayList<ActivityManager.StackInfo> getAllStackInfos(int displayId) {
        ArrayList<ActivityManager.StackInfo> list = new ArrayList<>();
        if (displayId == INVALID_DISPLAY) {
            for (int displayNdx = 0; displayNdx < getChildCount(); ++displayNdx) {
                final DisplayContent display = getChildAt(displayNdx);
                for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                    final ActivityStack stack = display.getStackAt(stackNdx);
                    list.add(getStackInfo(stack));
                }
            }
            return list;
        }
        final DisplayContent display = getDisplayContent(displayId);
        if (display == null) {
            return list;
        }
        for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = display.getStackAt(stackNdx);
            list.add(getStackInfo(stack));
        }
        return list;
    }

    void deferUpdateBounds(int activityType) {
        final ActivityStack stack = getStack(WINDOWING_MODE_UNDEFINED, activityType);
        if (stack != null) {
            stack.deferUpdateBounds();
        }
    }

    void continueUpdateBounds(int activityType) {
        final ActivityStack stack = getStack(WINDOWING_MODE_UNDEFINED, activityType);
        if (stack != null) {
            stack.continueUpdateBounds();
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display added displayId=" + displayId);
        synchronized (mService.mGlobalLock) {
            final DisplayContent display = getDisplayContentOrCreate(displayId);
            if (display == null) {
                return;
            }
            // Do not start home before booting, or it may accidentally finish booting before it
            // starts. Instead, we expect home activities to be launched when the system is ready
            // (ActivityManagerService#systemReady).
            if (mService.isBooted() || mService.isBooting()) {
                startSystemDecorations(display.mDisplayContent);
            }
        }
    }

    private void startSystemDecorations(final DisplayContent displayContent) {
        startHomeOnDisplay(mCurrentUser, "displayAdded", displayContent.getDisplayId());
        displayContent.getDisplayPolicy().notifyDisplayReady();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display removed displayId=" + displayId);
        if (displayId == DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can't remove the primary display.");
        }

        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = getDisplayContent(displayId);
            if (displayContent == null) {
                return;
            }

            displayContent.remove();
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display changed displayId=" + displayId);
        synchronized (mService.mGlobalLock) {
            final DisplayContent displayContent = getDisplayContent(displayId);
            if (displayContent != null) {
                displayContent.onDisplayChanged();
            }
        }
    }

    /** Update lists of UIDs that are present on displays and have access to them. */
    void updateUIDsPresentOnDisplay() {
        mDisplayAccessUIDs.clear();
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent displayContent = getChildAt(displayNdx);
            // Only bother calculating the whitelist for private displays
            if (displayContent.isPrivate()) {
                mDisplayAccessUIDs.append(
                        displayContent.mDisplayId, displayContent.getPresentUIDs());
            }
        }
        // Store updated lists in DisplayManager. Callers from outside of AM should get them there.
        mDisplayManagerInternal.setDisplayAccessUIDs(mDisplayAccessUIDs);
    }

    ActivityStack findStackBehind(ActivityStack stack) {
        final DisplayContent display = getDisplayContent(stack.mDisplayId);
        if (display != null) {
            for (int i = display.getStackCount() - 1; i >= 0; i--) {
                if (display.getStackAt(i) == stack && i > 0) {
                    return display.getStackAt(i - 1);
                }
            }
        }
        throw new IllegalStateException("Failed to find a stack behind stack=" + stack
                + " in=" + display);
    }

    @Override
    void positionChildAt(int position, DisplayContent child, boolean includingParents) {
        super.positionChildAt(position, child, includingParents);
        mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    Configuration getDisplayOverrideConfiguration(int displayId) {
        final DisplayContent displayContent = getDisplayContentOrCreate(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }

        return displayContent.getRequestedOverrideConfiguration();
    }

    void setDisplayOverrideConfiguration(Configuration overrideConfiguration, int displayId) {
        final DisplayContent displayContent = getDisplayContentOrCreate(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }

        displayContent.onRequestedOverrideConfigurationChanged(overrideConfiguration);
    }

    void prepareForShutdown() {
        for (int i = 0; i < getChildCount(); i++) {
            createSleepToken("shutdown", getChildAt(i).mDisplayId);
        }
    }

    ActivityTaskManagerInternal.SleepToken createSleepToken(String tag, int displayId) {
        final DisplayContent display = getDisplayContent(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Invalid display: " + displayId);
        }

        final SleepTokenImpl token = new SleepTokenImpl(tag, displayId);
        mSleepTokens.add(token);
        display.mAllSleepTokens.add(token);
        return token;
    }

    private void removeSleepToken(SleepTokenImpl token) {
        mSleepTokens.remove(token);

        final DisplayContent display = getDisplayContent(token.mDisplayId);
        if (display != null) {
            display.mAllSleepTokens.remove(token);
            if (display.mAllSleepTokens.isEmpty()) {
                mService.updateSleepIfNeededLocked();
            }
        }
    }

    void addStartingWindowsForVisibleActivities() {
        forAllActivities((r) -> {
            if (r.mVisibleRequested) {
                r.showStartingWindow(null /* prev */, false /* newTask */, true /*taskSwitch*/);
            }
        });
    }

    void invalidateTaskLayers() {
        mTaskLayersChanged = true;
    }

    void rankTaskLayersIfNeeded() {
        if (!mTaskLayersChanged) {
            return;
        }
        mTaskLayersChanged = false;
        mTmpTaskLayerRank = 0;
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootActivityContainer::rankTaskLayerForActivity, this,
                PooledLambda.__(ActivityRecord.class));
        forAllActivities(c);
        c.recycle();
    }

    private void rankTaskLayerForActivity(ActivityRecord r) {
        if (r.canBeTopRunning() && r.mVisibleRequested) {
            r.getTask().mLayerRank = ++mTmpTaskLayerRank;
        } else {
            r.getTask().mLayerRank = -1;
        }
    }

    void clearOtherAppTimeTrackers(AppTimeTracker except) {
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootActivityContainer::clearOtherAppTimeTrackers,
                PooledLambda.__(ActivityRecord.class), except);
        forAllActivities(c);
        c.recycle();
    }

    private static void clearOtherAppTimeTrackers(ActivityRecord r, AppTimeTracker except) {
        if ( r.appTimeTracker != except) {
            r.appTimeTracker = null;
        }
    }

    void scheduleDestroyAllActivities(String reason) {
        mDestroyAllActivitiesReason = reason;
        mService.mH.post(mDestroyAllActivitiesRunnable);
    }

    private void destroyActivity(ActivityRecord r) {
        if (r.finishing || !r.isDestroyable()) return;

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Destroying " + r + " in state " + r.getState()
                + " resumed=" + r.getStack().mResumedActivity + " pausing="
                + r.getStack().mPausingActivity + " for reason " + mDestroyAllActivitiesReason);

        r.destroyImmediately(true /* removeFromTask */, mDestroyAllActivitiesReason);
    }

    // Tries to put all activity stacks to sleep. Returns true if all stacks were
    // successfully put to sleep.
    boolean putStacksToSleep(boolean allowDelay, boolean shuttingDown) {
        boolean allSleep = true;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                // Stacks and activities could be removed while putting activities to sleep if
                // the app process was gone. This prevents us getting exception by accessing an
                // invalid stack index.
                if (stackNdx >= display.getStackCount()) {
                    continue;
                }

                final ActivityStack stack = display.getStackAt(stackNdx);
                if (allowDelay) {
                    allSleep &= stack.goToSleepIfPossible(shuttingDown);
                } else {
                    stack.goToSleep();
                }
            }
        }
        return allSleep;
    }

    void handleAppCrash(WindowProcessController app) {
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootActivityContainer::handleAppCrash, PooledLambda.__(ActivityRecord.class), app);
        forAllActivities(c);
        c.recycle();
    }

    private static void handleAppCrash(ActivityRecord r, WindowProcessController app) {
        if (r.app != app) return;
        Slog.w(TAG, "  Force finishing activity "
                + r.intent.getComponent().flattenToShortString());
        // Force the destroy to skip right to removal.
        r.app = null;
        r.getDisplay().mDisplayContent.prepareAppTransition(
                TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        r.destroyIfPossible("handleAppCrashed");
    }

    ActivityRecord findActivity(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        final int userId = UserHandle.getUserId(info.applicationInfo.uid);

        final PooledPredicate p = PooledLambda.obtainPredicate(
                RootActivityContainer::matchesActivity, PooledLambda.__(ActivityRecord.class),
                userId, compareIntentFilters, intent, cls);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean matchesActivity(ActivityRecord r, int userId,
            boolean compareIntentFilters, Intent intent, ComponentName cls) {
        if (!r.canBeTopRunning() || r.mUserId != userId)  return false;

        if (compareIntentFilters) {
            if (r.intent.filterEquals(intent)) {
                return true;
            }
        } else {
            if (r.intent.getComponent().equals(cls)) {
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

    ActivityStack getLaunchStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable Task candidateTask, boolean onTop) {
        return getLaunchStack(r, options, candidateTask, onTop, null /* launchParams */,
                -1 /* no realCallingPid */, -1 /* no realCallingUid */);
    }

    /**
     * Returns the right stack to use for launching factoring in all the input parameters.
     *
     * @param r The activity we are trying to launch. Can be null.
     * @param options The activity options used to the launch. Can be null.
     * @param candidateTask The possible task the activity might be launched in. Can be null.
     * @param launchParams The resolved launch params to use.
     * @param realCallingPid The pid from {@link ActivityStarter#setRealCallingPid}
     * @param realCallingUid The uid from {@link ActivityStarter#setRealCallingUid}
     *
     * @return The stack to use for the launch or INVALID_STACK_ID.
     */
    ActivityStack getLaunchStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable Task candidateTask, boolean onTop,
            @Nullable LaunchParamsController.LaunchParams launchParams, int realCallingPid,
            int realCallingUid) {
        int taskId = INVALID_TASK_ID;
        int displayId = INVALID_DISPLAY;
        //Rect bounds = null;

        // We give preference to the launch preference in activity options.
        if (options != null) {
            taskId = options.getLaunchTaskId();
            displayId = options.getLaunchDisplayId();
        }

        // First preference for stack goes to the task Id set in the activity options. Use the stack
        // associated with that if possible.
        if (taskId != INVALID_TASK_ID) {
            // Temporarily set the task id to invalid in case in re-entry.
            options.setLaunchTaskId(INVALID_TASK_ID);
            final Task task = anyTaskForId(taskId,
                    MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, options, onTop);
            options.setLaunchTaskId(taskId);
            if (task != null) {
                return task.getStack();
            }
        }

        final int activityType = resolveActivityType(r, options, candidateTask);
        ActivityStack stack;

        // Next preference for stack goes to the display Id set the candidate display.
        if (launchParams != null && launchParams.mPreferredDisplayId != INVALID_DISPLAY) {
            displayId = launchParams.mPreferredDisplayId;
        }
        final boolean canLaunchOnDisplayFromStartRequest =
                realCallingPid != 0 && realCallingUid > 0 && r != null
                        && mStackSupervisor.canPlaceEntityOnDisplay(displayId, realCallingPid,
                        realCallingUid, r.info);
        // Checking if the activity's launch caller, or the realCallerId of the activity from
        // start request (i.e. entity that invokes PendingIntent) is allowed to launch on the
        // display.
        if (displayId != INVALID_DISPLAY && (canLaunchOnDisplay(r, displayId)
                || canLaunchOnDisplayFromStartRequest)) {
            if (r != null) {
                stack = getValidLaunchStackOnDisplay(displayId, r, candidateTask, options,
                        launchParams);
                if (stack != null) {
                    return stack;
                }
            }
            final DisplayContent display = getDisplayContentOrCreate(displayId);
            if (display != null) {
                stack = display.getOrCreateStack(r, options, candidateTask, activityType, onTop);
                if (stack != null) {
                    return stack;
                }
            }
        }

        // Give preference to the stack and display of the input task and activity if they match the
        // mode we want to launch into.
        stack = null;
        DisplayContent display = null;
        if (candidateTask != null) {
            stack = candidateTask.getStack();
        }
        if (stack == null && r != null) {
            stack = r.getActivityStack();
        }
        if (stack != null) {
            display = stack.getDisplay();
            if (display != null && canLaunchOnDisplay(r, display.mDisplayId)) {
                int windowingMode = launchParams != null ? launchParams.mWindowingMode
                        : WindowConfiguration.WINDOWING_MODE_UNDEFINED;
                if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
                    windowingMode = display.resolveWindowingMode(r, options, candidateTask,
                            activityType);
                }
                if (stack.isCompatible(windowingMode, activityType)) {
                    return stack;
                }
                if (windowingMode == WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY
                        && display.getSplitScreenPrimaryStack() == stack
                        && candidateTask == stack.getTopMostTask()) {
                    // This is a special case when we try to launch an activity that is currently on
                    // top of split-screen primary stack, but is targeting split-screen secondary.
                    // In this case we don't want to move it to another stack.
                    // TODO(b/78788972): Remove after differentiating between preferred and required
                    // launch options.
                    return stack;
                }
            }
        }

        if (display == null || !canLaunchOnDisplay(r, display.mDisplayId)) {
            display = getDefaultDisplay();
        }

        return display.getOrCreateStack(r, options, candidateTask, activityType, onTop);
    }

    /** @return true if activity record is null or can be launched on provided display. */
    private boolean canLaunchOnDisplay(ActivityRecord r, int displayId) {
        if (r == null) {
            return true;
        }
        return r.canBeLaunchedOnDisplay(displayId);
    }

    /**
     * Get a topmost stack on the display, that is a valid launch stack for specified activity.
     * If there is no such stack, new dynamic stack can be created.
     * @param displayId Target display.
     * @param r Activity that should be launched there.
     * @param candidateTask The possible task the activity might be put in.
     * @return Existing stack if there is a valid one, new dynamic stack if it is valid or null.
     */
    private ActivityStack getValidLaunchStackOnDisplay(int displayId, @NonNull ActivityRecord r,
            @Nullable Task candidateTask, @Nullable ActivityOptions options,
            @Nullable LaunchParamsController.LaunchParams launchParams) {
        final DisplayContent displayContent = getDisplayContentOrCreate(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException(
                    "Display with displayId=" + displayId + " not found.");
        }

        if (!r.canBeLaunchedOnDisplay(displayId)) {
            return null;
        }

        // If {@code r} is already in target display and its task is the same as the candidate task,
        // the intention should be getting a launch stack for the reusable activity, so we can use
        // the existing stack.
        if (candidateTask != null && (r.getTask() == null || r.getTask() == candidateTask)) {
            final int attachedDisplayId = r.getDisplayId();
            if (attachedDisplayId == INVALID_DISPLAY || attachedDisplayId == displayId) {
                return candidateTask.getStack();
            }
        }

        int windowingMode;
        if (launchParams != null) {
            // When launch params is not null, we always defer to its windowing mode. Sometimes
            // it could be unspecified, which indicates it should inherit windowing mode from
            // display.
            windowingMode = launchParams.mWindowingMode;
        } else {
            windowingMode = options != null ? options.getLaunchWindowingMode()
                    : r.getWindowingMode();
        }
        windowingMode = displayContent.validateWindowingMode(windowingMode, r, candidateTask,
                r.getActivityType());

        // Return the topmost valid stack on the display.
        for (int i = displayContent.getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = displayContent.getStackAt(i);
            if (isValidLaunchStack(stack, r, windowingMode)) {
                return stack;
            }
        }

        // If there is no valid stack on the external display - check if new dynamic stack will do.
        if (displayId != DEFAULT_DISPLAY) {
            final int activityType =
                    options != null && options.getLaunchActivityType() != ACTIVITY_TYPE_UNDEFINED
                            ? options.getLaunchActivityType() : r.getActivityType();
            return displayContent.createStack(windowingMode, activityType, true /*onTop*/);
        }

        return null;
    }

    ActivityStack getValidLaunchStackOnDisplay(int displayId, @NonNull ActivityRecord r,
            @Nullable ActivityOptions options,
            @Nullable LaunchParamsController.LaunchParams launchParams) {
        return getValidLaunchStackOnDisplay(displayId, r, null /* candidateTask */, options,
                launchParams);
    }

    // TODO: Can probably be consolidated into getLaunchStack()...
    private boolean isValidLaunchStack(ActivityStack stack, ActivityRecord r, int windowingMode) {
        switch (stack.getActivityType()) {
            case ACTIVITY_TYPE_HOME: return r.isActivityTypeHome();
            case ACTIVITY_TYPE_RECENTS: return r.isActivityTypeRecents();
            case ACTIVITY_TYPE_ASSISTANT: return r.isActivityTypeAssistant();
        }
        // There is a 1-to-1 relationship between stack and task when not in
        // primary split-windowing mode.
        if (stack.getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                && r.supportsSplitScreenWindowingMode()
                && (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_UNDEFINED)) {
            return true;
        }
        return false;
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
     * Get next focusable stack in the system. This will search through the stack on the same
     * display as the current focused stack, looking for a focusable and visible stack, different
     * from the target stack. If no valid candidates will be found, it will then go through all
     * displays and stacks in last-focused order.
     *
     * @param currentFocus The stack that previously had focus.
     * @param ignoreCurrent If we should ignore {@param currentFocus} when searching for next
     *                     candidate.
     * @return Next focusable {@link ActivityStack}, {@code null} if not found.
     */
    ActivityStack getNextFocusableStack(@NonNull ActivityStack currentFocus,
            boolean ignoreCurrent) {
        // First look for next focusable stack on the same display
        DisplayContent preferredDisplay = currentFocus.getDisplay();
        if (preferredDisplay == null) {
            // Stack is currently detached because it is being removed. Use the previous display it
            // was on.
            preferredDisplay = getDisplayContent(currentFocus.mPrevDisplayId);
        }
        final ActivityStack preferredFocusableStack = preferredDisplay.getNextFocusableStack(
                currentFocus, ignoreCurrent);
        if (preferredFocusableStack != null) {
            return preferredFocusableStack;
        }
        if (preferredDisplay.supportsSystemDecorations()) {
            // Stop looking for focusable stack on other displays because the preferred display
            // supports system decorations. Home activity would be launched on the same display if
            // no focusable stack found.
            return null;
        }

        // Now look through all displays
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            if (display == preferredDisplay) {
                // We've already checked this one
                continue;
            }
            final ActivityStack nextFocusableStack = display.getNextFocusableStack(currentFocus,
                    ignoreCurrent);
            if (nextFocusableStack != null) {
                return nextFocusableStack;
            }
        }

        return null;
    }

    /**
     * Get next valid stack for launching provided activity in the system. This will search across
     * displays and stacks in last-focused order for a focusable and visible stack, except those
     * that are on a currently focused display.
     *
     * @param r The activity that is being launched.
     * @param currentFocus The display that previously had focus and thus needs to be ignored when
     *                     searching for the next candidate.
     * @return Next valid {@link ActivityStack}, null if not found.
     */
    ActivityStack getNextValidLaunchStack(@NonNull ActivityRecord r, int currentFocus) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            if (display.mDisplayId == currentFocus) {
                continue;
            }
            final ActivityStack stack = getValidLaunchStackOnDisplay(display.mDisplayId, r,
                    null /* options */, null /* launchParams */);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    boolean handleAppDied(WindowProcessController app) {
        boolean hasVisibleActivities = false;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                hasVisibleActivities |= stack.handleAppDied(app);
            }
        }
        return hasVisibleActivities;
    }

    void closeSystemDialogs() {
        forAllActivities((r) -> {
            if ((r.info.flags & ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS) != 0) {
                r.finishIfPossible("close-sys", true /* oomAdj */);
            }
        });
    }

    FinishDisabledPackageActivitiesHelper mFinishDisabledPackageActivitiesHelper =
            new FinishDisabledPackageActivitiesHelper();
    class FinishDisabledPackageActivitiesHelper {
        private boolean mDidSomething;
        private String mPackageName;
        private Set<String> mFilterByClasses;
        private boolean mDoit;
        private boolean mEvenPersistent;
        private int mUserId;
        private Task mLastTask;
        private ComponentName mHomeActivity;

        private void reset(String packageName, Set<String> filterByClasses,
                boolean doit, boolean evenPersistent, int userId) {
            mDidSomething = false;
            mPackageName = packageName;
            mFilterByClasses = filterByClasses;
            mDoit = doit;
            mEvenPersistent = evenPersistent;
            mUserId = userId;
            mLastTask = null;
            mHomeActivity = null;
        }

        boolean process(String packageName, Set<String> filterByClasses,
                boolean doit, boolean evenPersistent, int userId) {
            reset(packageName, filterByClasses, doit, evenPersistent, userId);

            final PooledFunction f = PooledLambda.obtainFunction(
                    FinishDisabledPackageActivitiesHelper::processActivity, this,
                    PooledLambda.__(ActivityRecord.class));
            forAllActivities(f);
            f.recycle();
            return mDidSomething;
        }

        private boolean processActivity(ActivityRecord r) {
            final boolean sameComponent =
                    (r.packageName.equals(mPackageName) && (mFilterByClasses == null
                            || mFilterByClasses.contains(r.mActivityComponent.getClassName())))
                            || (mPackageName == null && r.mUserId == mUserId);
            if ((mUserId == UserHandle.USER_ALL || r.mUserId == mUserId)
                    && (sameComponent || r.getTask() == mLastTask)
                    && (r.app == null || mEvenPersistent || !r.app.isPersistent())) {
                if (!mDoit) {
                    if (r.finishing) {
                        // If this activity is just finishing, then it is not
                        // interesting as far as something to stop.
                        return false;
                    }
                    return true;
                }
                if (r.isActivityTypeHome()) {
                    if (mHomeActivity != null && mHomeActivity.equals(r.mActivityComponent)) {
                        Slog.i(TAG, "Skip force-stop again " + r);
                        return false;
                    } else {
                        mHomeActivity = r.mActivityComponent;
                    }
                }
                mDidSomething = true;
                Slog.i(TAG, "  Force finishing activity " + r);
                mLastTask = r.getTask();
                r.finishIfPossible("force-stop", true);
            }

            return false;
        }
    }

    /** @return true if some activity was finished (or would have finished if doit were true). */
    boolean finishDisabledPackageActivities(String packageName, Set<String> filterByClasses,
            boolean doit, boolean evenPersistent, int userId) {
        return mFinishDisabledPackageActivitiesHelper.process(packageName, filterByClasses, doit,
                evenPersistent, userId);
    }

    void updateActivityApplicationInfo(ApplicationInfo aInfo) {
        final String packageName = aInfo.packageName;
        final int userId = UserHandle.getUserId(aInfo.uid);
        final PooledConsumer c = PooledLambda.obtainConsumer(
                RootActivityContainer::updateActivityApplicationInfo,
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
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            final int numStacks = display.getStackCount();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                stack.finishVoiceTask(session);
            }
        }
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(int... windowingModes) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            getChildAt(i).removeStacksInWindowingModes(windowingModes);
        }
    }

    void removeStacksWithActivityTypes(int... activityTypes) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            getChildAt(i).removeStacksWithActivityTypes(activityTypes);
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
            // TODO(b/117135575): Check resumed activities on all visible stacks.
            final DisplayContent display = getChildAt(displayNdx);
            if (display.isSleeping()) {
                // No resumed activities while display is sleeping.
                continue;
            }

            // If the focused stack is not null or not empty, there should have some activities
            // resuming or resumed. Make sure these activities are idle.
            final ActivityStack stack = display.getFocusedStack();
            if (stack == null || !stack.hasActivity()) {
                continue;
            }
            final ActivityRecord resumedActivity = stack.getResumedActivity();
            if (resumedActivity == null || !resumedActivity.idle) {
                if (DEBUG_STATES) {
                    Slog.d(TAG_STATES, "allResumedActivitiesIdle: stack="
                            + stack.mStackId + " " + resumedActivity + " not idle");
                }
                return false;
            }
        }
        // Send launch end powerhint when idle
        sendPowerHintForLaunchEndIfNeeded();
        return true;
    }

    boolean allResumedActivitiesVisible() {
        boolean foundResumed = false;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                final ActivityRecord r = stack.getResumedActivity();
                if (r != null) {
                    if (!r.nowVisible) {
                        return false;
                    }
                    foundResumed = true;
                }
            }
        }
        return foundResumed;
    }

    boolean allPausedActivitiesComplete() {
        boolean pausing = true;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                final ActivityRecord r = stack.mPausingActivity;
                if (r != null && !r.isState(PAUSED, STOPPED, STOPPING)) {
                    if (DEBUG_STATES) {
                        Slog.d(TAG_STATES,
                                "allPausedActivitiesComplete: r=" + r + " state=" + r.getState());
                        pausing = false;
                    } else {
                        return false;
                    }
                }
            }
        }
        return pausing;
    }

    /**
     * Find all visible task stacks containing {@param userId} and intercept them with an activity
     * to block out the contents and possibly start a credential-confirming intent.
     *
     * @param userId user handle for the locked managed profile.
     */
    void lockAllProfileTasks(@UserIdInt int userId) {
        mService.deferWindowLayout();
        try {
            final PooledConsumer c = PooledLambda.obtainConsumer(
                    RootActivityContainer::taskTopActivityIsUser, this, PooledLambda.__(Task.class),
                    userId);
            forAllTasks(c);
            c.recycle();
        } finally {
            mService.continueWindowLayout();
        }
    }

    /**
     * Detects whether we should show a lock screen in front of this task for a locked user.
     * <p>
     * We'll do this if either of the following holds:
     * <ul>
     *   <li>The top activity explicitly belongs to {@param userId}.</li>
     *   <li>The top activity returns a result to an activity belonging to {@param userId}.</li>
     * </ul>
     *
     * @return {@code true} if the top activity looks like it belongs to {@param userId}.
     */
    private void taskTopActivityIsUser(Task task, @UserIdInt int userId) {
        // To handle the case that work app is in the task but just is not the top one.
        final ActivityRecord activityRecord = task.getTopNonFinishingActivity();
        final ActivityRecord resultTo = (activityRecord != null ? activityRecord.resultTo : null);

        // Check the task for a top activity belonging to userId, or returning a
        // result to an activity belonging to userId. Example case: a document
        // picker for personal files, opened by a work app, should still get locked.
        if ((activityRecord != null && activityRecord.mUserId == userId)
                || (resultTo != null && resultTo.mUserId == userId)) {
            mService.getTaskChangeNotificationController().notifyTaskProfileLocked(
                    task.mTaskId, userId);
        }
    }

    void cancelInitializingActivities() {
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                stack.cancelInitializingActivities();
            }
        }
    }

    Task anyTaskForId(int id) {
        return anyTaskForId(id, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE);
    }

    Task anyTaskForId(int id, @AnyTaskForIdMatchTaskMode int matchMode) {
        return anyTaskForId(id, matchMode, null, !ON_TOP);
    }

    /**
     * Returns a {@link Task} for the input id if available. {@code null} otherwise.
     * @param id Id of the task we would like returned.
     * @param matchMode The mode to match the given task id in.
     * @param aOptions The activity options to use for restoration. Can be null.
     * @param onTop If the stack for the task should be the topmost on the display.
     */
    Task anyTaskForId(int id, @AnyTaskForIdMatchTaskMode int matchMode,
            @Nullable ActivityOptions aOptions, boolean onTop) {
        // If options are set, ensure that we are attempting to actually restore a task
        if (matchMode != MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE && aOptions != null) {
            throw new IllegalArgumentException("Should not specify activity options for non-restore"
                    + " lookup");
        }

        final PooledPredicate p = PooledLambda.obtainPredicate(
                Task::isTaskId, PooledLambda.__(Task.class), id);
        Task task = getTask(p);
        p.recycle();

        if (task != null) {
            if (aOptions != null) {
                // Resolve the stack the task should be placed in now based on options
                // and reparent if needed.
                final ActivityStack launchStack =
                        getLaunchStack(null, aOptions, task, onTop);
                if (launchStack != null && task.getStack() != launchStack) {
                    final int reparentMode = onTop
                            ? REPARENT_MOVE_STACK_TO_FRONT : REPARENT_LEAVE_STACK_IN_PLACE;
                    task.reparent(launchStack, onTop, reparentMode, ANIMATE, DEFER_RESUME,
                            "anyTaskForId");
                }
            }
            return task;
        }

        // If we are matching stack tasks only, return now
        if (matchMode == MATCH_TASK_IN_STACKS_ONLY) {
            return null;
        }

        // Otherwise, check the recent tasks and return if we find it there and we are not restoring
        // the task from recents
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS, "Looking for task id=" + id + " in recents");
        task = mStackSupervisor.mRecentTasks.getTask(id);

        if (task == null) {
            if (DEBUG_RECENTS) {
                Slog.d(TAG_RECENTS, "\tDidn't find task id=" + id + " in recents");
            }

            return null;
        }

        if (matchMode == MATCH_TASK_IN_STACKS_OR_RECENT_TASKS) {
            return task;
        }

        // Implicitly, this case is MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE
        if (!mStackSupervisor.restoreRecentTaskLocked(task, aOptions, onTop)) {
            if (DEBUG_RECENTS) Slog.w(TAG_RECENTS,
                    "Couldn't restore task id=" + id + " found in recents");
            return null;
        }
        if (DEBUG_RECENTS) Slog.w(TAG_RECENTS, "Restored task id=" + id + " from in recents");
        return task;
    }

    ActivityRecord isInAnyStack(IBinder token) {
        int numDisplays = getChildCount();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final DisplayContent display = getChildAt(displayNdx);
            for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getStackAt(stackNdx);
                final ActivityRecord r = stack.isInStackLocked(token);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    @VisibleForTesting
    void getRunningTasks(int maxNum, List<ActivityManager.RunningTaskInfo> list,
            @WindowConfiguration.ActivityType int ignoreActivityType,
            @WindowConfiguration.WindowingMode int ignoreWindowingMode, int callingUid,
            boolean allowed, boolean crossUser, ArraySet<Integer> profileIds) {
        mStackSupervisor.getRunningTasks().getTasks(maxNum, list, ignoreActivityType,
                ignoreWindowingMode, this, callingUid, allowed, crossUser, profileIds);
    }

    void sendPowerHintForLaunchStartIfNeeded(boolean forceSend, ActivityRecord targetActivity) {
        boolean sendHint = forceSend;

        if (!sendHint) {
            // Send power hint if we don't know what we're launching yet
            sendHint = targetActivity == null || targetActivity.app == null;
        }

        if (!sendHint) { // targetActivity != null
            // Send power hint when the activity's process is different than the current resumed
            // activity on all displays, or if there are no resumed activities in the system.
            boolean noResumedActivities = true;
            boolean allFocusedProcessesDiffer = true;
            for (int displayNdx = 0; displayNdx < getChildCount(); ++displayNdx) {
                final DisplayContent displayContent = getChildAt(displayNdx);
                final ActivityRecord resumedActivity = displayContent.getResumedActivity();
                final WindowProcessController resumedActivityProcess =
                        resumedActivity == null ? null : resumedActivity.app;

                noResumedActivities &= resumedActivityProcess == null;
                if (resumedActivityProcess != null) {
                    allFocusedProcessesDiffer &= !resumedActivityProcess.equals(targetActivity.app);
                }
            }
            sendHint = noResumedActivities || allFocusedProcessesDiffer;
        }

        if (sendHint && mService.mPowerManagerInternal != null) {
            mService.mPowerManagerInternal.powerHint(PowerHint.LAUNCH, 1);
            mPowerHintSent = true;
        }
    }

    void sendPowerHintForLaunchEndIfNeeded() {
        // Trigger launch power hint if activity is launched
        if (mPowerHintSent && mService.mPowerManagerInternal != null) {
            mService.mPowerManagerInternal.powerHint(PowerHint.LAUNCH, 0);
            mPowerHintSent = false;
        }
    }

    private void calculateDefaultMinimalSizeOfResizeableTasks() {
        final Resources res = mService.mContext.getResources();
        final float minimalSize = res.getDimension(
                com.android.internal.R.dimen.default_minimal_size_resizable_task);
        final DisplayMetrics dm = res.getDisplayMetrics();

        mDefaultMinSizeOfResizeableTaskDp = (int) (minimalSize / dm.density);
    }

    /**
     * Dumps the activities matching the given {@param name} in the either the focused stack
     * or all visible stacks if {@param dumpVisibleStacks} is true.
     */
    ArrayList<ActivityRecord> getDumpActivities(String name, boolean dumpVisibleStacksOnly,
            boolean dumpFocusedStackOnly) {
        if (dumpFocusedStackOnly) {
            return getTopDisplayFocusedStack().getDumpActivitiesLocked(name);
        } else {
            ArrayList<ActivityRecord> activities = new ArrayList<>();
            int numDisplays = getChildCount();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final DisplayContent display = getChildAt(displayNdx);
                for (int stackNdx = display.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                    final ActivityStack stack = display.getStackAt(stackNdx);
                    if (!dumpVisibleStacksOnly || stack.shouldBeVisible(null)) {
                        activities.addAll(stack.getDumpActivitiesLocked(name));
                    }
                }
            }
            return activities;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("topDisplayFocusedStack=" + getTopDisplayFocusedStack());
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            display.dump(pw, prefix, true /* dumpAll */);
        }
    }

    /**
     * Dump all connected displays' configurations.
     * @param prefix Prefix to apply to each line of the dump.
     */
    void dumpDisplayConfigs(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.println("Display override configurations:");
        final int displayCount = getChildCount();
        for (int i = 0; i < displayCount; i++) {
            final DisplayContent displayContent = getChildAt(i);
            pw.print(prefix); pw.print("  "); pw.print(displayContent.mDisplayId); pw.print(": ");
            pw.println(displayContent.getRequestedOverrideConfiguration());
        }
    }

    public void dumpDisplays(PrintWriter pw) {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final DisplayContent display = getChildAt(i);
            pw.print("[id:" + display.mDisplayId + " stacks:");
            display.dumpStacks(pw);
            pw.print("]");
        }
    }

    boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage) {
        boolean printed = false;
        boolean needSep = false;
        for (int displayNdx = getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            DisplayContent displayContent = getChildAt(displayNdx);
            pw.print("Display #"); pw.print(displayContent.mDisplayId);
            pw.println(" (activities from top to bottom):");
            for (int stackNdx = displayContent.getStackCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = displayContent.getStackAt(stackNdx);
                pw.println();
                printed = stack.dump(fd, pw, dumpAll, dumpClient, dumpPackage, needSep);
                needSep = printed;
            }
            printThisActivity(pw, displayContent.getResumedActivity(), dumpPackage, needSep,
                    " ResumedActivity:");
        }

        printed |= dumpHistoryList(fd, pw, mStackSupervisor.mFinishingActivities, "  ",
                "Fin", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to finish:", null);
        printed |= dumpHistoryList(fd, pw, mStackSupervisor.mStoppingActivities, "  ",
                "Stop", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to stop:", null);
        printed |= dumpHistoryList(fd, pw, mStackSupervisor.mGoingToSleepActivities,
                "  ", "Sleep", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to sleep:", null);

        return printed;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        dumpDebugInner(proto, ROOT_WINDOW_CONTAINER, logLevel);
        for (int displayNdx = 0; displayNdx < getChildCount(); ++displayNdx) {
            final DisplayContent displayContent = getChildAt(displayNdx);
            displayContent.dumpDebug(proto, DISPLAYS, logLevel);
        }
        mStackSupervisor.getKeyguardController().dumpDebug(proto, KEYGUARD_CONTROLLER);
        // TODO(b/111541062): Update tests to look for resumed activities on all displays
        final ActivityStack focusedStack = getTopDisplayFocusedStack();
        if (focusedStack != null) {
            proto.write(FOCUSED_STACK_ID, focusedStack.mStackId);
            final ActivityRecord focusedActivity = focusedStack.getDisplay().getResumedActivity();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
            }
        } else {
            proto.write(FOCUSED_STACK_ID, INVALID_STACK_ID);
        }
        proto.write(IS_HOME_RECENTS_COMPONENT,
                mStackSupervisor.mRecentTasks.isRecentsComponentHomeActivity(mCurrentUser));
        mService.getActivityStartController().dumpDebug(proto, PENDING_ACTIVITIES);
        proto.end(token);
    }

    private final class SleepTokenImpl extends ActivityTaskManagerInternal.SleepToken {
        private final String mTag;
        private final long mAcquireTime;
        private final int mDisplayId;

        public SleepTokenImpl(String tag, int displayId) {
            mTag = tag;
            mDisplayId = displayId;
            mAcquireTime = SystemClock.uptimeMillis();
        }

        @Override
        public void release() {
            synchronized (mService.mGlobalLock) {
                removeSleepToken(this);
            }
        }

        @Override
        public String toString() {
            return "{\"" + mTag + "\", display " + mDisplayId
                    + ", acquire at " + TimeUtils.formatUptime(mAcquireTime) + "}";
        }
    }
}
