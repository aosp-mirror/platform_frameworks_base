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
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;

import static com.android.server.am.ActivityStackSupervisorProto.CONFIGURATION_CONTAINER;
import static com.android.server.am.ActivityStackSupervisorProto.DISPLAYS;
import static com.android.server.am.ActivityStackSupervisorProto.FOCUSED_STACK_ID;
import static com.android.server.am.ActivityStackSupervisorProto.IS_HOME_RECENTS_COMPONENT;
import static com.android.server.am.ActivityStackSupervisorProto.KEYGUARD_CONTROLLER;
import static com.android.server.am.ActivityStackSupervisorProto.PENDING_ACTIVITIES;
import static com.android.server.am.ActivityStackSupervisorProto.RESUMED_ACTIVITY;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityStackSupervisor.printThisActivity;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ANIMATE;
import static com.android.server.wm.TaskRecord.REPARENT_LEAVE_STACK_IN_PLACE;
import static com.android.server.wm.TaskRecord.REPARENT_MOVE_STACK_TO_FRONT;

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
import java.util.Set;

/**
 * Root node for activity containers.
 * TODO: This class is mostly temporary to separate things out of ActivityStackSupervisor.java. The
 * intention is to have this merged with RootWindowContainer.java as part of unifying the hierarchy.
 */
class RootActivityContainer extends ConfigurationContainer
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
    // TODO: Remove after object merge with RootWindowContainer.
    private RootWindowContainer mRootWindowContainer;

    /**
     * List of displays which contain activities, sorted by z-order.
     * The last entry in the list is the topmost.
     */
    private final ArrayList<ActivityDisplay> mActivityDisplays = new ArrayList<>();

    /** Reference to default display so we can quickly look it up. */
    private ActivityDisplay mDefaultDisplay;
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

    // The default minimal size that will be used if the activity doesn't specify its minimal size.
    // It will be calculated when the default display gets added.
    int mDefaultMinSizeOfResizeableTaskDp = -1;

    // Whether tasks have moved and we need to rank the tasks before next OOM scoring
    private boolean mTaskLayersChanged = true;

    private final ArrayList<ActivityRecord> mTmpActivityList = new ArrayList<>();

    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();
    static class FindTaskResult {
        ActivityRecord mRecord;
        boolean mIdealMatch;

        void clear() {
            mRecord = null;
            mIdealMatch = false;
        }

        void setTo(FindTaskResult result) {
            mRecord = result.mRecord;
            mIdealMatch = result.mIdealMatch;
        }
    }

    RootActivityContainer(ActivityTaskManagerService service) {
        mService = service;
        mStackSupervisor = service.mStackSupervisor;
        mStackSupervisor.mRootActivityContainer = this;
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        mRootWindowContainer = mWindowManager.mRoot;
        mRootWindowContainer.setRootActivityContainer(this);
        mDisplayManager = mService.mContext.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(this, mService.mUiHandler);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);

        final Display[] displays = mDisplayManager.getDisplays();
        for (int displayNdx = 0; displayNdx < displays.length; ++displayNdx) {
            final Display display = displays[displayNdx];
            final ActivityDisplay activityDisplay = new ActivityDisplay(this, display);
            if (activityDisplay.mDisplayId == DEFAULT_DISPLAY) {
                mDefaultDisplay = activityDisplay;
            }
            addChild(activityDisplay, ActivityDisplay.POSITION_TOP);
        }
        calculateDefaultMinimalSizeOfResizeableTasks();

        final ActivityDisplay defaultDisplay = getDefaultDisplay();

        defaultDisplay.getOrCreateStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        positionChildAt(defaultDisplay, ActivityDisplay.POSITION_TOP);
    }

    // TODO(multi-display): Look at all callpoints to make sure they make sense in multi-display.
    ActivityDisplay getDefaultDisplay() {
        return mDefaultDisplay;
    }

    /**
     * Get an existing instance of {@link ActivityDisplay} that has the given uniqueId. Unique ID is
     * defined in {@link DisplayInfo#uniqueId}.
     *
     * @param uniqueId the unique ID of the display
     * @return the {@link ActivityDisplay} or {@code null} if nothing is found.
     */
    ActivityDisplay getActivityDisplay(String uniqueId) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay display = mActivityDisplays.get(i);
            final boolean isValid = display.mDisplay.isValid();
            if (isValid && display.mDisplay.getUniqueId().equals(uniqueId)) {
                return display;
            }
        }

        return null;
    }

    // TODO: Look into consolidating with getActivityDisplayOrCreate()
    ActivityDisplay getActivityDisplay(int displayId) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay activityDisplay = mActivityDisplays.get(i);
            if (activityDisplay.mDisplayId == displayId) {
                return activityDisplay;
            }
        }
        return null;
    }

    /**
     * Get an existing instance of {@link ActivityDisplay} or create new if there is a
     * corresponding record in display manager.
     */
    // TODO: Look into consolidating with getActivityDisplay()
    @Nullable ActivityDisplay getActivityDisplayOrCreate(int displayId) {
        ActivityDisplay activityDisplay = getActivityDisplay(displayId);
        if (activityDisplay != null) {
            return activityDisplay;
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
        activityDisplay = new ActivityDisplay(this, display);
        addChild(activityDisplay, ActivityDisplay.POSITION_BOTTOM);
        return activityDisplay;
    }

    ActivityRecord getDefaultDisplayHomeActivity() {
        return getDefaultDisplayHomeActivityForUser(mCurrentUser);
    }

    ActivityRecord getDefaultDisplayHomeActivityForUser(int userId) {
        return getActivityDisplay(DEFAULT_DISPLAY).getHomeActivityForUser(userId);
    }

    boolean startHomeOnAllDisplays(int userId, String reason) {
        boolean homeStarted = false;
        for (int i = mActivityDisplays.size() - 1; i >= 0; i--) {
            final int displayId = mActivityDisplays.get(i).mDisplayId;
            homeStarted |= startHomeOnDisplay(userId, reason, displayId);
        }
        return homeStarted;
    }

    void startHomeOnEmptyDisplays(String reason) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; i--) {
            final ActivityDisplay display = mActivityDisplays.get(i);
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
            displayId = getTopDisplayFocusedStack().mDisplayId;
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

        final ActivityRecord r = getActivityDisplay(displayId).getHomeActivity();
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

        final ActivityDisplay display = getActivityDisplay(displayId);
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
        final DisplayContent displayContent = mRootWindowContainer.getDisplayContent(displayId);
        Configuration config = null;
        if (displayContent != null) {
            config = displayContent.updateOrientation(
                    getDisplayOverrideConfiguration(displayId),
                    starting != null && starting.mayFreezeScreenLocked(starting.app)
                            ? starting.appToken : null,
                    true /* forceUpdate */);
        }
        if (starting != null && markFrozenIfConfigChanged && config != null) {
            starting.frozenBeforeDestroy = true;
        }

        if (displayContent != null && displayContent.mAcitvityDisplay != null) {
            // Update the configuration of the activities on the display.
            return displayContent.mAcitvityDisplay.updateDisplayOverrideConfigurationLocked(config,
                    starting, deferResume, null /* result */);
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
        for (int i = mActivityDisplays.size() - 1; i >= 0; i--) {
            final ActivityDisplay display = mActivityDisplays.get(i);
            // Traverse all stacks on a display.
            for (int j = display.getChildCount() - 1; j >= 0; --j) {
                final ActivityStack stack = display.getChildAt(j);
                // Get top activity from a visible stack and add it to the list.
                if (stack.shouldBeVisible(null /* starting */)) {
                    final ActivityRecord top = stack.getTopActivity();
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
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityStack focusedStack = mActivityDisplays.get(i).getFocusedStack();
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
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay display = mActivityDisplays.get(i);
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            final ActivityStack stack = display.getFocusedStack();
            if (stack != null) {
                stack.getAllRunningVisibleActivitiesLocked(mTmpActivityList);
                final ActivityRecord top = stack.topRunningActivityLocked();
                final int size = mTmpActivityList.size();
                for (int i = 0; i < size; i++) {
                    final ActivityRecord activity = mTmpActivityList.get(i);
                    if (activity.app == null && app.mUid == activity.info.applicationInfo.uid
                            && processName.equals(activity.processName)) {
                        try {
                            if (mStackSupervisor.realStartActivityLocked(activity, app,
                                    top == activity /* andResume */, true /* checkConfig */)) {
                                didSomething = true;
                            }
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Exception in new application when starting activity "
                                    + top.intent.getComponent().flattenToShortString(), e);
                            throw e;
                        }
                    }
                }
            }
        }
        if (!didSomething) {
            ensureActivitiesVisible(null, 0, false /* preserve_windows */);
        }
        return didSomething;
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
        mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
        try {
            // First the front stacks. In case any are not fullscreen and are in front of home.
            for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
                final ActivityDisplay display = mActivityDisplays.get(displayNdx);
                display.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                        notifyClients);
            }
        } finally {
            mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.switchUserLocked(userId);
                TaskRecord task = stack.topTask();
                if (task != null) {
                    stack.positionChildWindowContainerAtTop(task);
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
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("moveStackToDisplay: Unknown displayId="
                    + displayId);
        }
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveStackToDisplay: Unknown stackId="
                    + stackId);
        }

        final ActivityDisplay currentDisplay = stack.getDisplay();
        if (currentDisplay == null) {
            throw new IllegalStateException("moveStackToDisplay: Stack with stack=" + stack
                    + " is not attached to any display.");
        }

        if (currentDisplay.mDisplayId == displayId) {
            throw new IllegalArgumentException("Trying to move stack=" + stack
                    + " to its current displayId=" + displayId);
        }

        if (activityDisplay.isSingleTaskInstance() && activityDisplay.getChildCount() > 0) {
            // We don't allow moving stacks to single instance display that already has a child.
            Slog.e(TAG, "Can not move stack=" + stack
                    + " to single task instance display=" + activityDisplay);
            return;
        }

        stack.reparent(activityDisplay, onTop, false /* displayRemoved */);
        // TODO(multi-display): resize stacks properly if moved from split-screen.
    }

    boolean moveTopStackActivityToPinnedStack(int stackId) {
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException(
                    "moveTopStackActivityToPinnedStack: Unknown stackId=" + stackId);
        }

        final ActivityRecord r = stack.topRunningActivityLocked();
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
        mWindowManager.deferSurfaceLayout();

        final ActivityDisplay display = r.getActivityStack().getDisplay();
        ActivityStack stack = display.getPinnedStack();

        // This will clear the pinned stack by moving an existing task to the full screen stack,
        // ensuring only one task is present.
        if (stack != null) {
            mStackSupervisor.moveTasksToFullscreenStackLocked(stack, !ON_TOP);
        }

        // Need to make sure the pinned stack exist so we can resize it below...
        stack = display.getOrCreateStack(WINDOWING_MODE_PINNED, r.getActivityType(), ON_TOP);

        try {
            final TaskRecord task = r.getTaskRecord();
            // Resize the pinned stack to match the current size of the task the activity we are
            // going to be moving is currently contained in. We do this to have the right starting
            // animation bounds for the pinned stack to the desired bounds the caller wants.
            stack.resize(task.getRequestedOverrideBounds(), null /* tempTaskBounds */,
                    null /* tempTaskInsetBounds */, !PRESERVE_WINDOWS, !DEFER_RESUME);

            if (task.mActivities.size() == 1) {
                // Defer resume until below, and do not schedule PiP changes until we animate below
                task.reparent(stack, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, !ANIMATE, DEFER_RESUME,
                        false /* schedulePictureInPictureModeChange */, reason);
            } else {
                // There are multiple activities in the task and moving the top activity should
                // reveal/leave the other activities in their original task.

                // Currently, we don't support reparenting activities across tasks in two different
                // stacks, so instead, just create a new task in the same stack, reparent the
                // activity into that task, and then reparent the whole task to the new stack. This
                // ensures that all the necessary work to migrate states in the old and new stacks
                // is also done.
                final TaskRecord newTask = task.getStack().createTaskRecord(
                        mStackSupervisor.getNextTaskIdForUserLocked(r.mUserId), r.info,
                        r.intent, null, null, true);
                r.reparent(newTask, MAX_VALUE, "moveActivityToStack");

                // Defer resume until below, and do not schedule PiP changes until we animate below
                newTask.reparent(stack, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, !ANIMATE,
                        DEFER_RESUME, false /* schedulePictureInPictureModeChange */, reason);
            }

            // Reset the state that indicates it can enter PiP while pausing after we've moved it
            // to the pinned stack
            r.supportsEnterPipOnTaskSwitch = false;
        } finally {
            mWindowManager.continueSurfaceLayout();
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
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
        final ActivityDisplay preferredDisplay = getActivityDisplay(preferredDisplayId);
        if (preferredDisplay != null) {
            preferredDisplay.findTaskLocked(r, true /* isPreferredDisplay */, mTmpFindTaskResult);
            if (mTmpFindTaskResult.mIdealMatch) {
                return mTmpFindTaskResult.mRecord;
            }
        }

        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
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
        TaskRecord finishedTask = null;
        ActivityStack focusedStack = getTopDisplayFocusedStack();
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            // It is possible that request to finish activity might also remove its task and stack,
            // so we need to be careful with indexes in the loop and check child count every time.
            for (int stackNdx = 0; stackNdx < display.getChildCount(); ++stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                final TaskRecord t = stack.finishTopCrashedActivityLocked(app, reason);
                if (stack == focusedStack || finishedTask == null) {
                    finishedTask = t;
                }
            }
        }
        return finishedTask != null ? finishedTask.taskId : INVALID_TASK_ID;
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

        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            boolean resumedOnDisplay = false;
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                final ActivityRecord topRunningActivity = stack.topRunningActivityLocked();
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
                    focusedStack.resumeTopActivityUncheckedLocked(target, targetOptions);
                }
            }
        }

        return result;
    }

    void applySleepTokens(boolean applyToStacks) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            // Set the sleeping state of the display.
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            final boolean displayShouldSleep = display.shouldSleep();
            if (displayShouldSleep == display.isSleeping()) {
                continue;
            }
            display.setIsSleeping(displayShouldSleep);

            if (!applyToStacks) {
                continue;
            }

            // Set the sleeping state of the stacks on the display.
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
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

    protected <T extends ActivityStack> T getStack(int stackId) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final T stack = mActivityDisplays.get(i).getStack(stackId);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    /** @see ActivityDisplay#getStack(int, int) */
    private <T extends ActivityStack> T getStack(int windowingMode, int activityType) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final T stack = mActivityDisplays.get(i).getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private ActivityManager.StackInfo getStackInfo(ActivityStack stack) {
        final int displayId = stack.mDisplayId;
        final ActivityDisplay display = getActivityDisplay(displayId);
        ActivityManager.StackInfo info = new ActivityManager.StackInfo();
        stack.getWindowContainerBounds(info.bounds);
        info.displayId = displayId;
        info.stackId = stack.mStackId;
        info.userId = stack.mCurrentUser;
        info.visible = stack.shouldBeVisible(null);
        // A stack might be not attached to a display.
        info.position = display != null ? display.getIndexOf(stack) : 0;
        info.configuration.setTo(stack.getConfiguration());

        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        final int numTasks = tasks.size();
        int[] taskIds = new int[numTasks];
        String[] taskNames = new String[numTasks];
        Rect[] taskBounds = new Rect[numTasks];
        int[] taskUserIds = new int[numTasks];
        for (int i = 0; i < numTasks; ++i) {
            final TaskRecord task = tasks.get(i);
            taskIds[i] = task.taskId;
            taskNames[i] = task.origActivity != null ? task.origActivity.flattenToString()
                    : task.realActivity != null ? task.realActivity.flattenToString()
                    : task.getTopActivity() != null ? task.getTopActivity().packageName
                    : "unknown";
            taskBounds[i] = new Rect();
            task.getWindowContainerBounds(taskBounds[i]);
            taskUserIds[i] = task.userId;
        }
        info.taskIds = taskIds;
        info.taskNames = taskNames;
        info.taskBounds = taskBounds;
        info.taskUserIds = taskUserIds;

        final ActivityRecord top = stack.topRunningActivityLocked();
        info.topActivity = top != null ? top.intent.getComponent() : null;
        return info;
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

    ArrayList<ActivityManager.StackInfo> getAllStackInfos() {
        ArrayList<ActivityManager.StackInfo> list = new ArrayList<>();
        for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); ++displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                list.add(getStackInfo(stack));
            }
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
            final ActivityDisplay display = getActivityDisplayOrCreate(displayId);
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
            final ActivityDisplay activityDisplay = getActivityDisplay(displayId);
            if (activityDisplay == null) {
                return;
            }

            activityDisplay.remove();
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display changed displayId=" + displayId);
        synchronized (mService.mGlobalLock) {
            final ActivityDisplay activityDisplay = getActivityDisplay(displayId);
            if (activityDisplay != null) {
                activityDisplay.onDisplayChanged();
            }
        }
    }

    /** Update lists of UIDs that are present on displays and have access to them. */
    void updateUIDsPresentOnDisplay() {
        mDisplayAccessUIDs.clear();
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay activityDisplay = mActivityDisplays.get(displayNdx);
            // Only bother calculating the whitelist for private displays
            if (activityDisplay.isPrivate()) {
                mDisplayAccessUIDs.append(
                        activityDisplay.mDisplayId, activityDisplay.getPresentUIDs());
            }
        }
        // Store updated lists in DisplayManager. Callers from outside of AM should get them there.
        mDisplayManagerInternal.setDisplayAccessUIDs(mDisplayAccessUIDs);
    }

    ActivityStack findStackBehind(ActivityStack stack) {
        final ActivityDisplay display = getActivityDisplay(stack.mDisplayId);
        if (display != null) {
            for (int i = display.getChildCount() - 1; i >= 0; i--) {
                if (display.getChildAt(i) == stack && i > 0) {
                    return display.getChildAt(i - 1);
                }
            }
        }
        throw new IllegalStateException("Failed to find a stack behind stack=" + stack
                + " in=" + display);
    }

    @Override
    protected int getChildCount() {
        return mActivityDisplays.size();
    }

    @Override
    protected ActivityDisplay getChildAt(int index) {
        return mActivityDisplays.get(index);
    }

    @Override
    protected ConfigurationContainer getParent() {
        return null;
    }

    // TODO: remove after object merge with RootWindowContainer
    void onChildPositionChanged(ActivityDisplay display, int position) {
        // Assume AM lock is held from positionChildAt of controller in each hierarchy.
        if (display != null) {
            positionChildAt(display, position);
        }
    }

    /** Change the z-order of the given display. */
    private void positionChildAt(ActivityDisplay display, int position) {
        if (position >= mActivityDisplays.size()) {
            position = mActivityDisplays.size() - 1;
        } else if (position < 0) {
            position = 0;
        }

        if (mActivityDisplays.isEmpty()) {
            mActivityDisplays.add(display);
        } else if (mActivityDisplays.get(position) != display) {
            mActivityDisplays.remove(display);
            mActivityDisplays.add(position, display);
        }
        mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    @VisibleForTesting
    void addChild(ActivityDisplay activityDisplay, int position) {
        positionChildAt(activityDisplay, position);
        mRootWindowContainer.positionChildAt(position, activityDisplay.mDisplayContent);
    }

    void removeChild(ActivityDisplay activityDisplay) {
        // The caller must tell the controller of {@link ActivityDisplay} to release its container
        // {@link DisplayContent}. That is done in {@link ActivityDisplay#releaseSelfIfNeeded}).
        mActivityDisplays.remove(activityDisplay);
    }

    Configuration getDisplayOverrideConfiguration(int displayId) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }

        return activityDisplay.getRequestedOverrideConfiguration();
    }

    void setDisplayOverrideConfiguration(Configuration overrideConfiguration, int displayId) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }

        activityDisplay.onRequestedOverrideConfigurationChanged(overrideConfiguration);
    }

    void prepareForShutdown() {
        for (int i = 0; i < mActivityDisplays.size(); i++) {
            createSleepToken("shutdown", mActivityDisplays.get(i).mDisplayId);
        }
    }

    ActivityTaskManagerInternal.SleepToken createSleepToken(String tag, int displayId) {
        final ActivityDisplay display = getActivityDisplay(displayId);
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

        final ActivityDisplay display = getActivityDisplay(token.mDisplayId);
        if (display != null) {
            display.mAllSleepTokens.remove(token);
            if (display.mAllSleepTokens.isEmpty()) {
                mService.updateSleepIfNeededLocked();
            }
        }
    }

    void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.addStartingWindowsForVisibleActivities(taskSwitch);
            }
        }
    }

    void invalidateTaskLayers() {
        mTaskLayersChanged = true;
    }

    void rankTaskLayersIfNeeded() {
        if (!mTaskLayersChanged) {
            return;
        }
        mTaskLayersChanged = false;
        for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); displayNdx++) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            int baseLayer = 0;
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                baseLayer += stack.rankTaskLayers(baseLayer);
            }
        }
    }

    void clearOtherAppTimeTrackers(AppTimeTracker except) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.clearOtherAppTimeTrackers(except);
            }
        }
    }

    void scheduleDestroyAllActivities(WindowProcessController app, String reason) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.scheduleDestroyActivities(app, reason);
            }
        }
    }

    void releaseSomeActivitiesLocked(WindowProcessController app, String reason) {
        // Tasks is non-null only if two or more tasks are found.
        ArraySet<TaskRecord> tasks = app.getReleaseSomeActivitiesTasks();
        if (tasks == null) {
            if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Didn't find two or more tasks to release");
            return;
        }
        // If we have activities in multiple tasks that are in a position to be destroyed,
        // let's iterate through the tasks and release the oldest one.
        final int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            final int stackCount = display.getChildCount();
            // Step through all stacks starting from behind, to hit the oldest things first.
            for (int stackNdx = 0; stackNdx < stackCount; stackNdx++) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                // Try to release activities in this stack; if we manage to, we are done.
                if (stack.releaseSomeActivitiesLocked(app, tasks, reason) > 0) {
                    return;
                }
            }
        }
    }

    // Tries to put all activity stacks to sleep. Returns true if all stacks were
    // successfully put to sleep.
    boolean putStacksToSleep(boolean allowDelay, boolean shuttingDown) {
        boolean allSleep = true;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                // Stacks and activities could be removed while putting activities to sleep if
                // the app process was gone. This prevents us getting exception by accessing an
                // invalid stack index.
                if (stackNdx >= display.getChildCount()) {
                    continue;
                }

                final ActivityStack stack = display.getChildAt(stackNdx);
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.handleAppCrash(app);
            }
        }
    }

    ActivityRecord findActivity(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                final ActivityRecord ar = stack.findActivityLocked(
                        intent, info, compareIntentFilters);
                if (ar != null) {
                    return ar;
                }
            }
        }
        return null;
    }

    boolean hasAwakeDisplay() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            if (!display.shouldSleep()) {
                return true;
            }
        }
        return false;
    }

    <T extends ActivityStack> T getLaunchStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable TaskRecord candidateTask, boolean onTop) {
        return getLaunchStack(r, options, candidateTask, onTop, null /* launchParams */);
    }

    /**
     * Returns the right stack to use for launching factoring in all the input parameters.
     *
     * @param r The activity we are trying to launch. Can be null.
     * @param options The activity options used to the launch. Can be null.
     * @param candidateTask The possible task the activity might be launched in. Can be null.
     * @params launchParams The resolved launch params to use.
     *
     * @return The stack to use for the launch or INVALID_STACK_ID.
     */
    <T extends ActivityStack> T getLaunchStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable TaskRecord candidateTask, boolean onTop,
            @Nullable LaunchParamsController.LaunchParams launchParams) {
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
            final TaskRecord task = anyTaskForId(taskId,
                    MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, options, onTop);
            options.setLaunchTaskId(taskId);
            if (task != null) {
                return task.getStack();
            }
        }

        final int activityType = resolveActivityType(r, options, candidateTask);
        T stack;

        // Next preference for stack goes to the display Id set the candidate display.
        if (launchParams != null && launchParams.mPreferredDisplayId != INVALID_DISPLAY) {
            displayId = launchParams.mPreferredDisplayId;
        }
        if (displayId != INVALID_DISPLAY && canLaunchOnDisplay(r, displayId)) {
            if (r != null) {
                stack = (T) getValidLaunchStackOnDisplay(displayId, r, candidateTask, options,
                        launchParams);
                if (stack != null) {
                    return stack;
                }
            }
            final ActivityDisplay display = getActivityDisplayOrCreate(displayId);
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
        ActivityDisplay display = null;
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
                        && candidateTask == stack.topTask()) {
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
            @Nullable TaskRecord candidateTask, @Nullable ActivityOptions options,
            @Nullable LaunchParamsController.LaunchParams launchParams) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreate(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException(
                    "Display with displayId=" + displayId + " not found.");
        }

        if (!r.canBeLaunchedOnDisplay(displayId)) {
            return null;
        }

        // If {@code r} is already in target display and its task is the same as the candidate task,
        // the intention should be getting a launch stack for the reusable activity, so we can use
        // the existing stack.
        if (r.getDisplayId() == displayId && r.getTaskRecord() == candidateTask) {
            return candidateTask.getStack();
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
        windowingMode = activityDisplay.validateWindowingMode(windowingMode, r, candidateTask,
                r.getActivityType());

        // Return the topmost valid stack on the display.
        for (int i = activityDisplay.getChildCount() - 1; i >= 0; --i) {
            final ActivityStack stack = activityDisplay.getChildAt(i);
            if (isValidLaunchStack(stack, r, windowingMode)) {
                return stack;
            }
        }

        // If there is no valid stack on the external display - check if new dynamic stack will do.
        if (displayId != DEFAULT_DISPLAY) {
            final int activityType =
                    options != null && options.getLaunchActivityType() != ACTIVITY_TYPE_UNDEFINED
                            ? options.getLaunchActivityType() : r.getActivityType();
            return activityDisplay.createStack(windowingMode, activityType, true /*onTop*/);
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
            @Nullable TaskRecord task) {
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
        final ActivityDisplay preferredDisplay = currentFocus.getDisplay();
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
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay display = mActivityDisplays.get(i);
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
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay display = mActivityDisplays.get(i);
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                hasVisibleActivities |= stack.handleAppDiedLocked(app);
            }
        }
        return hasVisibleActivities;
    }

    void closeSystemDialogs() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.closeSystemDialogsLocked();
            }
        }
    }

    /** @return true if some activity was finished (or would have finished if doit were true). */
    boolean finishDisabledPackageActivities(String packageName, Set<String> filterByClasses,
            boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                if (stack.finishDisabledPackageActivitiesLocked(
                        packageName, filterByClasses, doit, evenPersistent, userId)) {
                    didSomething = true;
                }
            }
        }
        return didSomething;
    }

    void updateActivityApplicationInfo(ApplicationInfo aInfo) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.updateActivityApplicationInfoLocked(aInfo);
            }
        }
    }

    void finishVoiceTask(IVoiceInteractionSession session) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            final int numStacks = display.getChildCount();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.finishVoiceTask(session);
            }
        }
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(int... windowingModes) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            mActivityDisplays.get(i).removeStacksInWindowingModes(windowingModes);
        }
    }

    void removeStacksWithActivityTypes(int... activityTypes) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            mActivityDisplays.get(i).removeStacksWithActivityTypes(activityTypes);
        }
    }

    ActivityRecord topRunningActivity() {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityRecord topActivity = mActivityDisplays.get(i).topRunningActivity();
            if (topActivity != null) {
                return topActivity;
            }
        }
        return null;
    }

    boolean allResumedActivitiesIdle() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            // TODO(b/117135575): Check resumed activities on all visible stacks.
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            if (display.isSleeping()) {
                // No resumed activities while display is sleeping.
                continue;
            }

            // If the focused stack is not null or not empty, there should have some activities
            // resuming or resumed. Make sure these activities are idle.
            final ActivityStack stack = display.getFocusedStack();
            if (stack == null || stack.numActivities() == 0) {
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
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
        mWindowManager.deferSurfaceLayout();
        try {
            for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
                final ActivityDisplay display = mActivityDisplays.get(displayNdx);
                for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                    final ActivityStack stack = display.getChildAt(stackNdx);
                    final List<TaskRecord> tasks = stack.getAllTasks();
                    for (int taskNdx = tasks.size() - 1; taskNdx >= 0; taskNdx--) {
                        final TaskRecord task = tasks.get(taskNdx);

                        // Check the task for a top activity belonging to userId, or returning a
                        // result to an activity belonging to userId. Example case: a document
                        // picker for personal files, opened by a work app, should still get locked.
                        if (taskTopActivityIsUser(task, userId)) {
                            mService.getTaskChangeNotificationController().notifyTaskProfileLocked(
                                    task.taskId, userId);
                        }
                    }
                }
            }
        } finally {
            mWindowManager.continueSurfaceLayout();
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
    private boolean taskTopActivityIsUser(TaskRecord task, @UserIdInt int userId) {
        // To handle the case that work app is in the task but just is not the top one.
        final ActivityRecord activityRecord = task.getTopActivity();
        final ActivityRecord resultTo = (activityRecord != null ? activityRecord.resultTo : null);

        return (activityRecord != null && activityRecord.mUserId == userId)
                || (resultTo != null && resultTo.mUserId == userId);
    }

    void cancelInitializingActivities() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.cancelInitializingActivities();
            }
        }
    }

    TaskRecord anyTaskForId(int id) {
        return anyTaskForId(id, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE);
    }

    TaskRecord anyTaskForId(int id, @AnyTaskForIdMatchTaskMode int matchMode) {
        return anyTaskForId(id, matchMode, null, !ON_TOP);
    }

    /**
     * Returns a {@link TaskRecord} for the input id if available. {@code null} otherwise.
     * @param id Id of the task we would like returned.
     * @param matchMode The mode to match the given task id in.
     * @param aOptions The activity options to use for restoration. Can be null.
     * @param onTop If the stack for the task should be the topmost on the display.
     */
    TaskRecord anyTaskForId(int id, @AnyTaskForIdMatchTaskMode int matchMode,
            @Nullable ActivityOptions aOptions, boolean onTop) {
        // If options are set, ensure that we are attempting to actually restore a task
        if (matchMode != MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE && aOptions != null) {
            throw new IllegalArgumentException("Should not specify activity options for non-restore"
                    + " lookup");
        }

        int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                final TaskRecord task = stack.taskForIdLocked(id);
                if (task == null) {
                    continue;
                }
                if (aOptions != null) {
                    // Resolve the stack the task should be placed in now based on options
                    // and reparent if needed.
                    final ActivityStack launchStack =
                            getLaunchStack(null, aOptions, task, onTop);
                    if (launchStack != null && stack != launchStack) {
                        final int reparentMode = onTop
                                ? REPARENT_MOVE_STACK_TO_FRONT : REPARENT_LEAVE_STACK_IN_PLACE;
                        task.reparent(launchStack, onTop, reparentMode, ANIMATE, DEFER_RESUME,
                                "anyTaskForId");
                    }
                }
                return task;
            }
        }

        // If we are matching stack tasks only, return now
        if (matchMode == MATCH_TASK_IN_STACKS_ONLY) {
            return null;
        }

        // Otherwise, check the recent tasks and return if we find it there and we are not restoring
        // the task from recents
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS, "Looking for task id=" + id + " in recents");
        final TaskRecord task = mStackSupervisor.mRecentTasks.getTask(id);

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
        int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
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
                ignoreWindowingMode, mActivityDisplays, callingUid, allowed, crossUser, profileIds);
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
            for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); ++displayNdx) {
                final ActivityDisplay activityDisplay = mActivityDisplays.get(displayNdx);
                final ActivityRecord resumedActivity = activityDisplay.getResumedActivity();
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
            int numDisplays = mActivityDisplays.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                final ActivityDisplay display = mActivityDisplays.get(displayNdx);
                for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                    final ActivityStack stack = display.getChildAt(stackNdx);
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
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay display = mActivityDisplays.get(i);
            display.dump(pw, prefix);
        }
    }

    /**
     * Dump all connected displays' configurations.
     * @param prefix Prefix to apply to each line of the dump.
     */
    void dumpDisplayConfigs(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.println("Display override configurations:");
        final int displayCount = mActivityDisplays.size();
        for (int i = 0; i < displayCount; i++) {
            final ActivityDisplay activityDisplay = mActivityDisplays.get(i);
            pw.print(prefix); pw.print("  "); pw.print(activityDisplay.mDisplayId); pw.print(": ");
            pw.println(activityDisplay.getRequestedOverrideConfiguration());
        }
    }

    public void dumpDisplays(PrintWriter pw) {
        for (int i = mActivityDisplays.size() - 1; i >= 0; --i) {
            final ActivityDisplay display = mActivityDisplays.get(i);
            pw.print("[id:" + display.mDisplayId + " stacks:");
            display.dumpStacks(pw);
            pw.print("]");
        }
    }

    boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage) {
        boolean printed = false;
        boolean needSep = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ActivityDisplay activityDisplay = mActivityDisplays.get(displayNdx);
            pw.print("Display #"); pw.print(activityDisplay.mDisplayId);
            pw.println(" (activities from top to bottom):");
            final ActivityDisplay display = mActivityDisplays.get(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                pw.println();
                printed = stack.dump(fd, pw, dumpAll, dumpClient, dumpPackage, needSep);
                needSep = printed;
            }
            printThisActivity(pw, activityDisplay.getResumedActivity(), dumpPackage, needSep,
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

    protected void writeToProto(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, CONFIGURATION_CONTAINER, logLevel);
        for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); ++displayNdx) {
            final ActivityDisplay activityDisplay = mActivityDisplays.get(displayNdx);
            activityDisplay.writeToProto(proto, DISPLAYS, logLevel);
        }
        mStackSupervisor.getKeyguardController().writeToProto(proto, KEYGUARD_CONTROLLER);
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
        mService.getActivityStartController().writeToProto(proto, PENDING_ACTIVITIES);
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
