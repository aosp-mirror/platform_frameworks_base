/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;

import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STARTED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityStackSupervisor.printThisActivity;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.H.FIRST_ACTIVITY_STACK_MSG;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.TaskProto.ACTIVITY_TYPE;
import static com.android.server.wm.TaskProto.ANIMATING_BOUNDS;
import static com.android.server.wm.TaskProto.BOUNDS;
import static com.android.server.wm.TaskProto.CREATED_BY_ORGANIZER;
import static com.android.server.wm.TaskProto.DEFER_REMOVAL;
import static com.android.server.wm.TaskProto.DISPLAY_ID;
import static com.android.server.wm.TaskProto.FILLS_PARENT;
import static com.android.server.wm.TaskProto.LAST_NON_FULLSCREEN_BOUNDS;
import static com.android.server.wm.TaskProto.MIN_HEIGHT;
import static com.android.server.wm.TaskProto.MIN_WIDTH;
import static com.android.server.wm.TaskProto.ORIG_ACTIVITY;
import static com.android.server.wm.TaskProto.REAL_ACTIVITY;
import static com.android.server.wm.TaskProto.RESIZE_MODE;
import static com.android.server.wm.TaskProto.RESUMED_ACTIVITY;
import static com.android.server.wm.TaskProto.ROOT_TASK_ID;
import static com.android.server.wm.TaskProto.SURFACE_HEIGHT;
import static com.android.server.wm.TaskProto.SURFACE_WIDTH;
import static com.android.server.wm.TaskProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.RemoteAction;
import android.app.ResultInfo;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.am.AppTimeTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * State and management of a single stack of activities.
 */
class ActivityStack extends Task {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStack" : TAG_ATM;
    static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_APP = TAG + POSTFIX_APP;
    static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    // Set to false to disable the preview that is shown while a new activity
    // is being started.
    private static final boolean SHOW_APP_STARTING_PREVIEW = true;

    // How long to wait for all background Activities to redraw following a call to
    // convertToTranslucent().
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;

    @IntDef(prefix = {"STACK_VISIBILITY"}, value = {
            STACK_VISIBILITY_VISIBLE,
            STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
            STACK_VISIBILITY_INVISIBLE,
    })
    @interface StackVisibility {}

    /** Stack is visible. No other stacks on top that fully or partially occlude it. */
    static final int STACK_VISIBILITY_VISIBLE = 0;

    /** Stack is partially occluded by other translucent stack(s) on top of it. */
    static final int STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT = 1;

    /** Stack is completely invisible. */
    static final int STACK_VISIBILITY_INVISIBLE = 2;

    enum ActivityState {
        INITIALIZING,
        STARTED,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED,
        RESTARTING_PROCESS
    }

    // The topmost Activity passed to convertToTranslucent(). When non-null it means we are
    // waiting for all Activities in mUndrawnActivitiesBelowTopTranslucent to be removed as they
    // are drawn. When the last member of mUndrawnActivitiesBelowTopTranslucent is removed the
    // Activity in mTranslucentActivityWaiting is notified via
    // Activity.onTranslucentConversionComplete(false). If a timeout occurs prior to the last
    // background activity being drawn then the same call will be made with a true value.
    ActivityRecord mTranslucentActivityWaiting = null;
    ArrayList<ActivityRecord> mUndrawnActivitiesBelowTopTranslucent = new ArrayList<>();

    /**
     * Set when we know we are going to be calling updateConfiguration()
     * soon, so want to skip intermediate config checks.
     */
    boolean mConfigWillChange;

    /**
     * Used to keep resumeTopActivityUncheckedLocked() from being entered recursively
     */
    boolean mInResumeTopActivity = false;

    private boolean mUpdateBoundsDeferred;
    private boolean mUpdateBoundsDeferredCalled;
    private boolean mUpdateDisplayedBoundsDeferredCalled;
    private final Rect mDeferredBounds = new Rect();
    private final Rect mDeferredDisplayedBounds = new Rect();

    int mCurrentUser;

    /** For comparison with DisplayContent bounds. */
    private Rect mTmpRect = new Rect();
    private Rect mTmpRect2 = new Rect();

    /** Detach this stack from its display when animation completes. */
    // TODO: maybe tie this to WindowContainer#removeChild some how...
    // TODO: This is no longer set. Okay to remove or was the set removed by accident?
    private boolean mDeferRemoval;

    // If this is true, we are in the bounds animating mode. The task will be down or upscaled to
    // perfectly fit the region it would have been cropped to. We may also avoid certain logic we
    // would otherwise apply while resizing, while resizing in the bounds animating mode.
    private boolean mBoundsAnimating = false;
    // Set when an animation has been requested but has not yet started from the UI thread. This is
    // cleared when the animation actually starts.
    private boolean mBoundsAnimatingRequested = false;
    private Rect mBoundsAnimationTarget = new Rect();
    private Rect mBoundsAnimationSourceHintBounds = new Rect();

    Rect mPreAnimationBounds = new Rect();

    private final AnimatingActivityRegistry mAnimatingActivityRegistry =
            new AnimatingActivityRegistry();

    /** Stores the override windowing-mode from before a transient mode change (eg. split) */
    private int mRestoreOverrideWindowingMode = WINDOWING_MODE_UNDEFINED;

    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;

    private static final int TRANSLUCENT_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 1;

    private final Handler mHandler;

    private class ActivityStackHandler extends Handler {

        ActivityStackHandler(Looper looper) {
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
    private final EnsureActivitiesVisibleHelper mEnsureActivitiesVisibleHelper =
            new EnsureActivitiesVisibleHelper(this);
    private final EnsureVisibleActivitiesConfigHelper mEnsureVisibleActivitiesConfigHelper =
            new EnsureVisibleActivitiesConfigHelper();
    private class EnsureVisibleActivitiesConfigHelper {
        private boolean mUpdateConfig;
        private boolean mPreserveWindow;
        private boolean mBehindFullscreen;

        void reset(boolean preserveWindow) {
            mPreserveWindow = preserveWindow;
            mUpdateConfig = false;
            mBehindFullscreen = false;
        }

        void process(ActivityRecord start, boolean preserveWindow) {
            if (start == null || !start.mVisibleRequested) {
                return;
            }
            reset(preserveWindow);

            final PooledFunction f = PooledLambda.obtainFunction(
                    EnsureVisibleActivitiesConfigHelper::processActivity, this,
                    PooledLambda.__(ActivityRecord.class));
            forAllActivities(f, start, true /*includeBoundary*/, true /*traverseTopToBottom*/);
            f.recycle();

            if (mUpdateConfig) {
                // Ensure the resumed state of the focus activity if we updated the configuration of
                // any activity.
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
        }

        boolean processActivity(ActivityRecord r) {
            mUpdateConfig |= r.ensureActivityConfiguration(0 /*globalChanges*/, mPreserveWindow);
            mBehindFullscreen |= r.occludesParent();
            return mBehindFullscreen;
        }
    }

    private final CheckBehindFullscreenActivityHelper mCheckBehindFullscreenActivityHelper =
            new CheckBehindFullscreenActivityHelper();
    private class CheckBehindFullscreenActivityHelper {
        private boolean mAboveTop;
        private boolean mBehindFullscreenActivity;
        private ActivityRecord mToCheck;
        private Consumer<ActivityRecord> mHandleBehindFullscreenActivity;
        private boolean mHandlingOccluded;

        private void reset(ActivityRecord toCheck,
                Consumer<ActivityRecord> handleBehindFullscreenActivity) {
            mToCheck = toCheck;
            mHandleBehindFullscreenActivity = handleBehindFullscreenActivity;
            mAboveTop = true;
            mBehindFullscreenActivity = false;

            if (!shouldBeVisible(null)) {
                // The stack is not visible, so no activity in it should be displaying a starting
                // window. Mark all activities below top and behind fullscreen.
                mAboveTop = false;
                mBehindFullscreenActivity = true;
            }

            mHandlingOccluded = mToCheck == null && mHandleBehindFullscreenActivity != null;
        }

        boolean process(ActivityRecord toCheck,
                Consumer<ActivityRecord> handleBehindFullscreenActivity) {
            reset(toCheck, handleBehindFullscreenActivity);

            if (!mHandlingOccluded && mBehindFullscreenActivity) {
                return true;
            }

            final ActivityRecord topActivity = topRunningActivity();
            final PooledFunction f = PooledLambda.obtainFunction(
                    CheckBehindFullscreenActivityHelper::processActivity, this,
                    PooledLambda.__(ActivityRecord.class), topActivity);
            forAllActivities(f);
            f.recycle();

            return mBehindFullscreenActivity;
        }

        private boolean processActivity(ActivityRecord r, ActivityRecord topActivity) {
            if (mAboveTop) {
                if (r == topActivity) {
                    if (r == mToCheck) {
                        // It is the top activity in a visible stack.
                        mBehindFullscreenActivity = false;
                        return true;
                    }
                    mAboveTop = false;
                }
                mBehindFullscreenActivity |= r.occludesParent();
                return false;
            }

            if (mHandlingOccluded) {
                mHandleBehindFullscreenActivity.accept(r);
            } else if (r == mToCheck) {
                return true;
            } else if (mBehindFullscreenActivity) {
                // It is occluded before {@param toCheck} is found.
                return true;
            }
            mBehindFullscreenActivity |= r.occludesParent();
            return false;
        }
    }

    // TODO: Can we just loop through WindowProcessController#mActivities instead of doing this?
    private final RemoveHistoryRecordsForApp mRemoveHistoryRecordsForApp =
            new RemoveHistoryRecordsForApp();
    private class RemoveHistoryRecordsForApp {
        private boolean mHasVisibleActivities;
        private boolean mIsProcessRemoved;
        private WindowProcessController mApp;
        private ArrayList<ActivityRecord> mToRemove = new ArrayList<>();

        boolean process(WindowProcessController app) {
            mToRemove.clear();
            mHasVisibleActivities = false;
            mApp = app;
            mIsProcessRemoved = app.isRemoved();
            if (mIsProcessRemoved) {
                // The package of the died process should be force-stopped, so make its activities
                // as finishing to prevent the process from being started again if the next top
                // (or being visible) activity also resides in the same process.
                app.makeFinishingForProcessRemoved();
            }

            final PooledConsumer c = PooledLambda.obtainConsumer(
                    RemoveHistoryRecordsForApp::addActivityToRemove, this,
                    PooledLambda.__(ActivityRecord.class));
            forAllActivities(c);
            c.recycle();

            while (!mToRemove.isEmpty()) {
                processActivity(mToRemove.remove(0));
            }

            mApp = null;
            return mHasVisibleActivities;
        }

        private void addActivityToRemove(ActivityRecord r) {
            if (r.app == mApp) {
                mToRemove.add(r);
            }
        }

        private void processActivity(ActivityRecord r) {
            if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "Record " + r + ": app=" + r.app);

            if (r.app != mApp) {
                return;
            }
            if (r.isVisible() || r.mVisibleRequested) {
                // While an activity launches a new activity, it's possible that the old
                // activity is already requested to be hidden (mVisibleRequested=false), but
                // this visibility is not yet committed, so isVisible()=true.
                mHasVisibleActivities = true;
            }
            final boolean remove;
            if ((r.mRelaunchReason == RELAUNCH_REASON_WINDOWING_MODE_RESIZE
                    || r.mRelaunchReason == RELAUNCH_REASON_FREE_RESIZE)
                    && r.launchCount < 3 && !r.finishing) {
                // If the process crashed during a resize, always try to relaunch it, unless
                // it has failed more than twice. Skip activities that's already finishing
                // cleanly by itself.
                remove = false;
            } else if ((!r.hasSavedState() && !r.stateNotNeeded
                    && !r.isState(ActivityState.RESTARTING_PROCESS)) || r.finishing) {
                // Don't currently have state for the activity, or
                // it is finishing -- always remove it.
                remove = true;
            } else if (!r.mVisibleRequested && r.launchCount > 2
                    && r.lastLaunchTime > (SystemClock.uptimeMillis() - 60000)) {
                // We have launched this activity too many times since it was
                // able to run, so give up and remove it.
                // (Note if the activity is visible, we don't remove the record.
                // We leave the dead window on the screen but the process will
                // not be restarted unless user explicitly tap on it.)
                remove = true;
            } else {
                // The process may be gone, but the activity lives on!
                remove = false;
            }
            if (remove) {
                if (DEBUG_ADD_REMOVE || DEBUG_CLEANUP) Slog.i(TAG_ADD_REMOVE,
                        "Removing activity " + r + " from stack "
                                + ": hasSavedState=" + r.hasSavedState()
                                + " stateNotNeeded=" + r.stateNotNeeded
                                + " finishing=" + r.finishing
                                + " state=" + r.getState() + " callers=" + Debug.getCallers(5));
                if (!r.finishing || mIsProcessRemoved) {
                    Slog.w(TAG, "Force removing " + r + ": app died, no saved state");
                    EventLogTags.writeWmFinishActivity(r.mUserId,
                        System.identityHashCode(r), r.getTask().mTaskId,
                            r.shortComponentName, "proc died without state saved");
                }
            } else {
                // We have the current state for this activity, so
                // it can be restarted later when needed.
                if (DEBUG_ALL) Slog.v(TAG, "Keeping entry, setting app to null");
                if (DEBUG_APP) Slog.v(TAG_APP,
                        "Clearing app during removeHistory for activity " + r);
                r.app = null;
                // Set nowVisible to previous visible state. If the app was visible while
                // it died, we leave the dead window on screen so it's basically visible.
                // This is needed when user later tap on the dead window, we need to stop
                // other apps when user transfers focus to the restarted activity.
                r.nowVisible = r.mVisibleRequested;
            }
            r.cleanUp(true /* cleanServices */, true /* setState */);
            if (remove) {
                r.removeFromHistory("appDied");
            }
        }
    }

    ActivityStack(ActivityTaskManagerService atmService, int id, int activityType,
            ActivityInfo info, Intent intent, boolean createdByOrganizer) {
        this(atmService, id, info, intent, null /*voiceSession*/, null /*voiceInteractor*/,
                null /*taskDescription*/, null /*stack*/);
        mCreatedByOrganizer = createdByOrganizer;
        setActivityType(activityType);
    }

    ActivityStack(ActivityTaskManagerService atmService, int id, ActivityInfo info, Intent _intent,
            IVoiceInteractionSession _voiceSession, IVoiceInteractor _voiceInteractor,
            ActivityManager.TaskDescription _taskDescription, ActivityStack stack) {
        this(atmService, id, _intent,  null /*_affinityIntent*/, null /*_affinity*/,
                null /*_rootAffinity*/, null /*_realActivity*/, null /*_origActivity*/,
                false /*_rootWasReset*/, false /*_autoRemoveRecents*/, false /*_askedCompatMode*/,
                UserHandle.getUserId(info.applicationInfo.uid), 0 /*_effectiveUid*/,
                null /*_lastDescription*/, System.currentTimeMillis(),
                true /*neverRelinquishIdentity*/,
                _taskDescription != null ? _taskDescription : new ActivityManager.TaskDescription(),
                id, INVALID_TASK_ID, INVALID_TASK_ID, 0 /*taskAffiliationColor*/,
                info.applicationInfo.uid, info.packageName, null, info.resizeMode,
                info.supportsPictureInPicture(), false /*_realActivitySuspended*/,
                false /*userSetupComplete*/, INVALID_MIN_SIZE, INVALID_MIN_SIZE, info,
                _voiceSession, _voiceInteractor, stack);
    }

    ActivityStack(ActivityTaskManagerService atmService, int id, Intent _intent,
            Intent _affinityIntent, String _affinity, String _rootAffinity,
            ComponentName _realActivity, ComponentName _origActivity, boolean _rootWasReset,
            boolean _autoRemoveRecents, boolean _askedCompatMode, int _userId, int _effectiveUid,
            String _lastDescription, long lastTimeMoved, boolean neverRelinquishIdentity,
            ActivityManager.TaskDescription _lastTaskDescription, int taskAffiliation,
            int prevTaskId, int nextTaskId, int taskAffiliationColor, int callingUid,
            String callingPackage, @Nullable String callingFeatureId, int resizeMode,
            boolean supportsPictureInPicture, boolean _realActivitySuspended,
            boolean userSetupComplete, int minWidth, int minHeight,
            ActivityInfo info, IVoiceInteractionSession _voiceSession,
            IVoiceInteractor _voiceInteractor, ActivityStack stack) {
        super(atmService, id, _intent, _affinityIntent, _affinity, _rootAffinity,
                _realActivity, _origActivity, _rootWasReset, _autoRemoveRecents, _askedCompatMode,
                _userId, _effectiveUid, _lastDescription, lastTimeMoved, neverRelinquishIdentity,
                _lastTaskDescription, taskAffiliation, prevTaskId, nextTaskId, taskAffiliationColor,
                callingUid, callingPackage, callingFeatureId, resizeMode, supportsPictureInPicture,
                _realActivitySuspended, userSetupComplete, minWidth, minHeight, info, _voiceSession,
                _voiceInteractor, stack);

        EventLogTags.writeWmStackCreated(id);
        mHandler = new ActivityStackHandler(mStackSupervisor.mLooper);
        mCurrentUser = mAtmService.mAmInternal.getCurrentUserId();
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        // Calling Task#onConfigurationChanged() for leaf task since the ops in this method are
        // particularly for ActivityStack, like preventing bounds changes when inheriting certain
        // windowing mode.
        if (!isRootTask()) {
            super.onConfigurationChanged(newParentConfig);
            return;
        }

        final int prevWindowingMode = getWindowingMode();
        final boolean prevIsAlwaysOnTop = isAlwaysOnTop();
        final int prevRotation = getWindowConfiguration().getRotation();
        final Rect newBounds = mTmpRect;
        // Initialize the new bounds by previous bounds as the input and output for calculating
        // override bounds in pinned (pip) or split-screen mode.
        getBounds(newBounds);

        super.onConfigurationChanged(newParentConfig);

        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            return;
        }

        if (prevWindowingMode != getWindowingMode()) {
            taskDisplayArea.onStackWindowingModeChanged(this);
        }

        final DisplayContent display = getDisplay();
        if (display == null ) {
            return;
        }

        final boolean windowingModeChanged = prevWindowingMode != getWindowingMode();
        final int overrideWindowingMode = getRequestedOverrideWindowingMode();
        // Update bounds if applicable
        boolean hasNewOverrideBounds = false;
        // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
        if ((overrideWindowingMode != WINDOWING_MODE_PINNED) && !matchParentBounds()) {
            // If the parent (display) has rotated, rotate our bounds to best-fit where their
            // bounds were on the pre-rotated display.
            final int newRotation = getWindowConfiguration().getRotation();
            final boolean rotationChanged = prevRotation != newRotation;
            if (rotationChanged) {
                display.mDisplayContent.rotateBounds(
                        newParentConfig.windowConfiguration.getBounds(), prevRotation, newRotation,
                        newBounds);
                hasNewOverrideBounds = true;
            }
        }

        if (windowingModeChanged) {
            taskDisplayArea.onStackWindowingModeChanged(this);
        }
        if (hasNewOverrideBounds) {
            if (inSplitScreenWindowingMode()) {
                setBounds(newBounds);
            } else if (overrideWindowingMode != WINDOWING_MODE_PINNED) {
                // For pinned stack, resize is now part of the {@link WindowContainerTransaction}
                resize(new Rect(newBounds), PRESERVE_WINDOWS, true /* deferResume */);
            }
        }
        if (prevIsAlwaysOnTop != isAlwaysOnTop()) {
            // Since always on top is only on when the stack is freeform or pinned, the state
            // can be toggled when the windowing mode changes. We must make sure the stack is
            // placed properly when always on top state changes.
            taskDisplayArea.positionStackAtTop(this, false /* includingParents */);
        }
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        // Reset the cached result of toString()
        stringName = null;

        // Calling Task#setWindowingMode() for leaf task since this is the a specialization of
        // {@link #setWindowingMode(int)} for ActivityStack.
        if (!isRootTask()) {
            super.setWindowingMode(windowingMode);
            return;
        }

        setWindowingMode(windowingMode, false /* creating */);
    }

    /**
     * A transient windowing mode is one which activities enter into temporarily. Examples of this
     * are Split window modes and pip. Non-transient modes are modes that displays can adopt.
     *
     * @param windowingMode the windowingMode to test for transient-ness.
     * @return {@code true} if the windowing mode is transient, {@code false} otherwise.
     */
    private static boolean isTransientWindowingMode(int windowingMode) {
        return windowingMode == WINDOWING_MODE_PINNED
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
    }

    /**
     * Specialization of {@link #setWindowingMode(int)} for this subclass.
     *
     * @param preferredWindowingMode the preferred windowing mode. This may not be honored depending
     *         on the state of things. For example, WINDOWING_MODE_UNDEFINED will resolve to the
     *         previous non-transient mode if this stack is currently in a transient mode.
     * @param creating {@code true} if this is being run during ActivityStack construction.
     */
    void setWindowingMode(int preferredWindowingMode, boolean creating) {
        mWmService.inSurfaceTransaction(() -> setWindowingModeInSurfaceTransaction(
                preferredWindowingMode, creating));
    }

    private void setWindowingModeInSurfaceTransaction(int preferredWindowingMode,
            boolean creating) {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            Slog.d(TAG, "taskDisplayArea is null, bail early");
            return;
        }
        final int currentMode = getWindowingMode();
        final int currentOverrideMode = getRequestedOverrideWindowingMode();
        final Task topTask = getTopMostTask();
        int windowingMode = preferredWindowingMode;
        if (preferredWindowingMode == WINDOWING_MODE_UNDEFINED
                && isTransientWindowingMode(currentMode)) {
            // Leaving a transient mode. Interpret UNDEFINED as "restore"
            windowingMode = mRestoreOverrideWindowingMode;
        }

        // Need to make sure windowing mode is supported. If we in the process of creating the stack
        // no need to resolve the windowing mode again as it is already resolved to the right mode.
        if (!creating) {
            windowingMode = taskDisplayArea.validateWindowingMode(windowingMode,
                    null /* ActivityRecord */, topTask, getActivityType());
        }
        if (taskDisplayArea.getRootSplitScreenPrimaryTask() == this
                && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            // Resolution to split-screen secondary for the primary split-screen stack means
            // we want to leave split-screen mode.
            windowingMode = mRestoreOverrideWindowingMode;
        }

        final boolean alreadyInSplitScreenMode = taskDisplayArea.isSplitScreenModeActivated();

        // Take any required action due to us not supporting the preferred windowing mode.
        if (alreadyInSplitScreenMode && windowingMode == WINDOWING_MODE_FULLSCREEN
                && isActivityTypeStandardOrUndefined()) {
            final boolean preferredSplitScreen =
                    preferredWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    || preferredWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (preferredSplitScreen || creating) {
                // Looks like we can't launch in split screen mode or the stack we are launching
                // doesn't support split-screen mode, go ahead an dismiss split-screen and display a
                // warning toast about it.
                mAtmService.getTaskChangeNotificationController()
                        .notifyActivityDismissingDockedStack();
                taskDisplayArea.onSplitScreenModeDismissed(this);
            }
        }

        if (currentMode == windowingMode) {
            // You are already in the window mode, so we can skip most of the work below. However,
            // it's possible that we have inherited the current windowing mode from a parent. So,
            // fulfill this method's contract by setting the override mode directly.
            getRequestedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode);
            return;
        }

        final ActivityRecord topActivity = getTopNonFinishingActivity();

        // For now, assume that the Stack's windowing mode is what will actually be used
        // by it's activities. In the future, there may be situations where this doesn't
        // happen; so at that point, this message will need to handle that.
        int likelyResolvedMode = windowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            final ConfigurationContainer parent = getParent();
            likelyResolvedMode = parent != null ? parent.getWindowingMode()
                    : WINDOWING_MODE_FULLSCREEN;
        }
        if (currentMode == WINDOWING_MODE_PINNED) {
            mAtmService.getTaskChangeNotificationController().notifyActivityUnpinned();
        }
        if (likelyResolvedMode == WINDOWING_MODE_PINNED
                && taskDisplayArea.getRootPinnedTask() != null) {
            // Can only have 1 pip at a time, so replace an existing pip
            taskDisplayArea.getRootPinnedTask().dismissPip();
        }
        if (likelyResolvedMode != WINDOWING_MODE_FULLSCREEN
                && topActivity != null && !topActivity.noDisplay
                && topActivity.isNonResizableOrForcedResizable(likelyResolvedMode)) {
            // Inform the user that they are starting an app that may not work correctly in
            // multi-window mode.
            final String packageName = topActivity.info.applicationInfo.packageName;
            mAtmService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                    topTask.mTaskId, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN, packageName);
        }

        mAtmService.deferWindowLayout();
        try {
            if (topActivity != null) {
                mStackSupervisor.mNoAnimActivities.add(topActivity);
            }
            super.setWindowingMode(windowingMode);
            // setWindowingMode triggers an onConfigurationChanged cascade which can result in a
            // different resolved windowing mode (usually when preferredWindowingMode is UNDEFINED).
            windowingMode = getWindowingMode();

            if (creating) {
                // Nothing else to do if we don't have a window container yet. E.g. call from ctor.
                return;
            }

            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && alreadyInSplitScreenMode) {
                // We already have a split-screen stack in this display, so just move the tasks over.
                // TODO: Figure-out how to do all the stuff in
                // AMS.setTaskWindowingModeSplitScreenPrimary
                throw new IllegalArgumentException("Setting primary split-screen windowing mode"
                        + " while there is already one isn't currently supported");
                //return;
            }
            if (isTransientWindowingMode(windowingMode) && !isTransientWindowingMode(currentMode)) {
                mRestoreOverrideWindowingMode = currentOverrideMode;
            }

            mTmpRect2.setEmpty();
            if (windowingMode != WINDOWING_MODE_FULLSCREEN) {
                if (matchParentBounds()) {
                    mTmpRect2.setEmpty();
                } else {
                    getRawBounds(mTmpRect2);
                }
            }

            if (!Objects.equals(getRequestedOverrideBounds(), mTmpRect2)) {
                resize(mTmpRect2, false /*preserveWindows*/, true /*deferResume*/);
            }
        } finally {
            mAtmService.continueWindowLayout();
        }

        mRootWindowContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
    }

    @Override
    public boolean isCompatible(int windowingMode, int activityType) {
        // TODO: Should we just move this to ConfigurationContainer?
        if (activityType == ACTIVITY_TYPE_UNDEFINED) {
            // Undefined activity types end up in a standard stack once the stack is created on a
            // display, so they should be considered compatible.
            activityType = ACTIVITY_TYPE_STANDARD;
        }
        return super.isCompatible(windowingMode, activityType);
    }

    /** Resume next focusable stack after reparenting to another display. */
    void postReparent() {
        adjustFocusToNextFocusableTask("reparent", true /* allowFocusSelf */,
                true /* moveParentsToTop */);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
        // Update visibility of activities before notifying WM. This way it won't try to resize
        // windows that are no longer visible.
        mRootWindowContainer.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);
    }

    DisplayContent getDisplay() {
        return getDisplayContent();
    }

    /**
     * Defers updating the bounds of the stack. If the stack was resized/repositioned while
     * deferring, the bounds will update in {@link #continueUpdateBounds()}.
     */
    void deferUpdateBounds() {
        if (!mUpdateBoundsDeferred) {
            mUpdateBoundsDeferred = true;
            mUpdateBoundsDeferredCalled = false;
        }
    }

    /**
     * Continues updating bounds after updates have been deferred. If there was a resize attempt
     * between {@link #deferUpdateBounds()} and {@link #continueUpdateBounds()}, the stack will
     * be resized to that bounds.
     */
    void continueUpdateBounds() {
        if (mUpdateBoundsDeferred) {
            mUpdateBoundsDeferred = false;
            if (mUpdateBoundsDeferredCalled) {
                setTaskBounds(mDeferredBounds);
                setBounds(mDeferredBounds);
            }
        }
    }

    private boolean updateBoundsAllowed(Rect bounds) {
        if (!mUpdateBoundsDeferred) {
            return true;
        }
        if (bounds != null) {
            mDeferredBounds.set(bounds);
        } else {
            mDeferredBounds.setEmpty();
        }
        mUpdateBoundsDeferredCalled = true;
        return false;
    }

    private boolean updateDisplayedBoundsAllowed(Rect bounds) {
        if (!mUpdateBoundsDeferred) {
            return true;
        }
        if (bounds != null) {
            mDeferredDisplayedBounds.set(bounds);
        } else {
            mDeferredDisplayedBounds.setEmpty();
        }
        mUpdateDisplayedBoundsDeferredCalled = true;
        return false;
    }

    /** @return true if the stack can only contain one task */
    boolean isSingleTaskInstance() {
        final DisplayContent display = getDisplay();
        return display != null && display.isSingleTaskInstance();
    }

    final boolean isHomeOrRecentsStack() {
        return isActivityTypeHome() || isActivityTypeRecents();
    }

    final boolean isOnHomeDisplay() {
        return getDisplayId() == DEFAULT_DISPLAY;
    }

    void moveToFront(String reason) {
        moveToFront(reason, null);
    }

    /**
     * @param reason The reason for moving the stack to the front.
     * @param task If non-null, the task will be moved to the top of the stack.
     * */
    void moveToFront(String reason, Task task) {
        if (!isAttached()) {
            return;
        }

        final TaskDisplayArea taskDisplayArea = getDisplayArea();

        if (inSplitScreenSecondaryWindowingMode()) {
            // If the stack is in split-screen secondary mode, we need to make sure we move the
            // primary split-screen stack forward in the case it is currently behind a fullscreen
            // stack so both halves of the split-screen appear on-top and the fullscreen stack isn't
            // cutting between them.
            // TODO(b/70677280): This is a workaround until we can fix as part of b/70677280.
            final ActivityStack topFullScreenStack =
                    taskDisplayArea.getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (topFullScreenStack != null) {
                final ActivityStack primarySplitScreenStack =
                        taskDisplayArea.getRootSplitScreenPrimaryTask();
                if (primarySplitScreenStack != null
                        && taskDisplayArea.getIndexOf(topFullScreenStack)
                            > taskDisplayArea.getIndexOf(primarySplitScreenStack)) {
                    primarySplitScreenStack.moveToFront(reason + " splitScreenToTop");
                }
            }
        }

        if (!isActivityTypeHome() && returnsToHomeStack()) {
            // Make sure the home stack is behind this stack since that is where we should return to
            // when this stack is no longer visible.
            taskDisplayArea.moveHomeStackToFront(reason + " returnToHome");
        }

        if (isRootTask()) {
            taskDisplayArea.positionStackAtTop(this, false /* includingParents */, reason);
        }
        if (task == null) {
            task = this;
        }
        task.getParent().positionChildAt(POSITION_TOP, task, true /* includingParents */);
    }

    /**
     * This moves 'task' to the back of this task and also recursively moves this task to the back
     * of its parents (if applicable).
     *
     * @param reason The reason for moving the stack to the back.
     * @param task If non-null, the task will be moved to the bottom of the stack.
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
                ((ActivityStack) parentTask).moveToBack(reason, this);
            } else {
                displayArea.positionStackAtBottom(this, reason);
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
        final ActivityStack home = displayArea.getOrCreateRootHomeTask();
        final WindowContainer homeParent = home.getParent();
        final Task homeParentTask = homeParent != null ? homeParent.asTask() : null;
        if (homeParentTask == null) {
            ((ActivityStack) task).reparent(displayArea, false /* onTop */);
        } else if (homeParentTask == this) {
            // Apparently reparent early-outs if same stack, so we have to explicitly reorder.
            positionChildAtBottom(task);
        } else {
            task.reparent((ActivityStack) homeParentTask, false /* toTop */,
                    REPARENT_LEAVE_STACK_IN_PLACE, false /* animate */, false /* deferResume */,
                    "moveToBack");
        }
    }

    boolean isTopActivityFocusable() {
        final ActivityRecord r = topRunningActivity();
        return r != null ? r.isFocusable()
                : (isFocusable() && getWindowConfiguration().canReceiveKeys());
    }

    boolean isFocusableAndVisible() {
        return isTopActivityFocusable() && shouldBeVisible(null /* starting */);
    }

    // TODO: Should each user have there own stacks?
    @Override
    void switchUser(int userId) {
        if (mCurrentUser == userId) {
            return;
        }
        mCurrentUser = userId;

        super.switchUser(userId);
        forAllLeafTasks((t) -> {
            if (t.showToCurrentUser() && t != this) {
                mChildren.remove(t);
                mChildren.add(t);
            }
        }, true /* traverseTopToBottom */);
    }

    void minimalResumeActivityLocked(ActivityRecord r) {
        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + r + " (starting new instance)"
                + " callers=" + Debug.getCallers(5));
        r.setState(RESUMED, "minimalResumeActivityLocked");
        r.completeResumeLocked();
    }

    private void clearLaunchTime(ActivityRecord r) {
        // Make sure that there is no activity waiting for this to launch.
        if (!mStackSupervisor.mWaitingActivityLaunched.isEmpty()) {
            mStackSupervisor.removeIdleTimeoutForActivity(r);
            mStackSupervisor.scheduleIdleTimeout(r);
        }
    }

    void awakeFromSleepingLocked() {
        // Ensure activities are no longer sleeping.
        forAllActivities((Consumer<ActivityRecord>) (r) -> r.setSleeping(false));
        ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                false /* preserveWindows */);
        if (mPausingActivity != null) {
            Slog.d(TAG, "awakeFromSleepingLocked: previously pausing activity didn't pause");
            mPausingActivity.activityPaused(true);
        }
    }

    void checkReadyForSleep() {
        if (shouldSleepActivities() && goToSleepIfPossible(false /* shuttingDown */)) {
            mStackSupervisor.checkReadyForSleepLocked(true /* allowDelay */);
        }
    }

    /**
     * Tries to put the activities in the stack to sleep.
     *
     * If the stack is not in a state where its activities can be put to sleep, this function will
     * start any necessary actions to move the stack into such a state. It is expected that this
     * function get called again when those actions complete.
     *
     * @param shuttingDown true when the called because the device is shutting down.
     * @return true if the stack finished going to sleep, false if the stack only started the
     * process of going to sleep (checkReadyForSleep will be called when that process finishes).
     */
    boolean goToSleepIfPossible(boolean shuttingDown) {
        boolean shouldSleep = true;

        if (mResumedActivity != null) {
            // Still have something resumed; can't sleep until it is paused.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep needs to pause " + mResumedActivity);
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "Sleep => pause with userLeaving=false");

            startPausingLocked(false /* userLeaving */, true /* uiSleeping */, null /* resuming */);
            shouldSleep = false ;
        } else if (mPausingActivity != null) {
            // Still waiting for something to pause; can't sleep yet.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still waiting to pause " + mPausingActivity);
            shouldSleep = false;
        }

        if (!shuttingDown) {
            if (containsActivityFromStack(mStackSupervisor.mStoppingActivities)) {
                // Still need to tell some activities to stop; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to stop "
                        + mStackSupervisor.mStoppingActivities.size() + " activities");

                mStackSupervisor.scheduleIdle();
                shouldSleep = false;
            }
        }

        if (shouldSleep) {
            goToSleep();
        }

        return shouldSleep;
    }

    void goToSleep() {
        // Make sure all visible activities are now sleeping. This will update the activity's
        // visibility and onStop() will be called.
        forAllActivities((r) -> {
            if (r.isState(STARTED, RESUMED, PAUSING, PAUSED, STOPPING, STOPPED)) {
                r.setSleeping(true);
            }
        });

        // Ensure visibility after updating sleep states without updating configuration,
        // as activities are about to be sent to sleep.
        ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                !PRESERVE_WINDOWS);
    }

    private boolean containsActivityFromStack(List<ActivityRecord> rs) {
        for (ActivityRecord r : rs) {
            if (r.getRootTask() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start pausing the currently resumed activity.  It is an error to call this if there
     * is already an activity being paused or there is no resumed activity.
     *
     * @param userLeaving True if this should result in an onUserLeaving to the current activity.
     * @param uiSleeping True if this is happening with the user interface going to sleep (the
     * screen turning off).
     * @param resuming The activity we are currently trying to resume or null if this is not being
     *                 called as part of resuming the top activity, so we shouldn't try to instigate
     *                 a resume here if not null.
     * @return Returns true if an activity now is in the PAUSING state, and we are waiting for
     * it to tell us when it is done.
     */
    final boolean startPausingLocked(boolean userLeaving, boolean uiSleeping,
            ActivityRecord resuming) {
        if (mPausingActivity != null) {
            Slog.wtf(TAG, "Going to pause when pause is already pending for " + mPausingActivity
                    + " state=" + mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                // Avoid recursion among check for sleep and complete pause during sleeping.
                // Because activity will be paused immediately after resume, just let pause
                // be completed by the order of activity paused from clients.
                completePauseLocked(false, resuming);
            }
        }
        ActivityRecord prev = mResumedActivity;

        if (prev == null) {
            if (resuming == null) {
                Slog.wtf(TAG, "Trying to pause when nothing is resumed");
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }

        if (prev == resuming) {
            Slog.wtf(TAG, "Trying to pause activity that is in process of being resumed");
            return false;
        }

        if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSING: " + prev);
        else if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Start pausing: " + prev);
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        mLastNoHistoryActivity = prev.isNoHistory() ? prev : null;
        prev.setState(PAUSING, "startPausingLocked");
        prev.getTask().touchActiveTime();
        clearLaunchTime(prev);

        mAtmService.updateCpuStats();

        boolean pauseImmediately = false;
        if (resuming != null && (resuming.info.flags & FLAG_RESUME_WHILE_PAUSING) != 0) {
            // If the flag RESUME_WHILE_PAUSING is set, then continue to schedule the previous
            // activity to be paused, while at the same time resuming the new resume activity
            // only if the previous activity can't go into Pip since we want to give Pip
            // activities a chance to enter Pip before resuming the next activity.
            final boolean lastResumedCanPip = prev != null && prev.checkEnterPictureInPictureState(
                    "shouldResumeWhilePausing", userLeaving);
            if (!lastResumedCanPip) {
                pauseImmediately = true;
            }
        }

        if (prev.attachedToProcess()) {
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueueing pending pause: " + prev);
            try {
                EventLogTags.writeWmPauseActivity(prev.mUserId, System.identityHashCode(prev),
                        prev.shortComponentName, "userLeaving=" + userLeaving);

                mAtmService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
                        prev.appToken, PauseActivityItem.obtain(prev.finishing, userLeaving,
                                prev.configChangeFlags, pauseImmediately));
            } catch (Exception e) {
                // Ignore exception, if process died other code will cleanup.
                Slog.w(TAG, "Exception thrown during pause", e);
                mPausingActivity = null;
                mLastPausedActivity = null;
                mLastNoHistoryActivity = null;
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!uiSleeping && !mAtmService.isSleepingOrShuttingDownLocked()) {
            mStackSupervisor.acquireLaunchWakelock();
        }

        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else if (DEBUG_PAUSE) {
                 Slog.v(TAG_PAUSE, "Key dispatch not paused for screen off");
            }

            if (pauseImmediately) {
                // If the caller said they don't want to wait for the pause, then complete
                // the pause now.
                completePauseLocked(false, resuming);
                return false;

            } else {
                prev.schedulePauseTimeout();
                return true;
            }

        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Activity not running, resuming next.");
            if (resuming == null) {
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }
    }

    @VisibleForTesting
    void completePauseLocked(boolean resumeNext, ActivityRecord resuming) {
        ActivityRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Complete pause: " + prev);

        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            final boolean wasStopping = prev.isState(STOPPING);
            prev.setState(PAUSED, "completePausedLocked");
            if (prev.finishing) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Executing finish of activity: " + prev);
                prev = prev.completeFinishing("completePausedLocked");
            } else if (prev.hasProcess()) {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Enqueue pending stop if needed: " + prev
                        + " wasStopping=" + wasStopping
                        + " visibleRequested=" + prev.mVisibleRequested);
                if (prev.deferRelaunchUntilPaused) {
                    // Complete the deferred relaunch that was waiting for pause to complete.
                    if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Re-launching after pause: " + prev);
                    prev.relaunchActivityLocked(prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    // We are also stopping, the stop request must have gone soon after the pause.
                    // We can't clobber it, because the stop confirmation will not be handled.
                    // We don't need to schedule another stop, we only need to let it happen.
                    prev.setState(STOPPING, "completePausedLocked");
                } else if (!prev.mVisibleRequested || shouldSleepOrShutDownActivities()) {
                    // Clear out any deferred client hide we might currently have.
                    prev.setDeferHidingClient(false);
                    // If we were visible then resumeTopActivities will release resources before
                    // stopping.
                    prev.addToStopping(true /* scheduleIdle */, false /* idleDelayed */,
                            "completePauseLocked");
                }
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "App died during pause, not stopping: " + prev);
                prev = null;
            }
            // It is possible the activity was freezing the screen before it was paused.
            // In that case go ahead and remove the freeze this activity has on the screen
            // since it is no longer visible.
            if (prev != null) {
                prev.stopFreezingScreenLocked(true /*force*/);
            }
            mPausingActivity = null;
        }

        if (resumeNext) {
            final ActivityStack topStack = mRootWindowContainer.getTopDisplayFocusedStack();
            if (topStack != null && !topStack.shouldSleepOrShutDownActivities()) {
                mRootWindowContainer.resumeFocusedStacksTopActivities(topStack, prev, null);
            } else {
                checkReadyForSleep();
                final ActivityRecord top = topStack != null ? topStack.topRunningActivity() : null;
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the stack is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mRootWindowContainer.resumeFocusedStacksTopActivities();
                }
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();

            if (prev.hasProcess() && prev.cpuTimeAtResume > 0) {
                final long diff = prev.app.getCpuTime() - prev.cpuTimeAtResume;
                if (diff > 0) {
                    final Runnable r = PooledLambda.obtainRunnable(
                            ActivityManagerInternal::updateForegroundTimeIfOnBattery,
                            mAtmService.mAmInternal, prev.info.packageName,
                            prev.info.applicationInfo.uid,
                            diff);
                    mAtmService.mH.post(r);
                }
            }
            prev.cpuTimeAtResume = 0; // reset it
        }

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active pinned stack - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mStackSupervisor.mAppVisibilitiesChangedSinceLastPause
                || (getDisplayArea() != null && getDisplayArea().hasPinnedTask())) {
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }

        mRootWindowContainer.ensureActivitiesVisible(resuming, 0, !PRESERVE_WINDOWS);
    }

    boolean isTopStackInDisplayArea() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        return taskDisplayArea != null && taskDisplayArea.isTopStack(this);
    }

    /**
     * @return {@code true} if this is the focused stack on its current display, {@code false}
     * otherwise.
     */
    boolean isFocusedStackOnDisplay() {
        final DisplayContent display = getDisplay();
        return display != null && this == display.getFocusedStack();
    }

    /**
     * Make sure that all activities that need to be visible in the stack (that is, they
     * currently can be seen by the user) actually are and update their configuration.
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     * @param preserveWindows Flag indicating whether windows should be preserved when updating
     *                        configuration in {@link mEnsureActivitiesVisibleHelper}.
     * @param configChanges Parts of the configuration that changed for this activity for evaluating
     *                      if the screen should be frozen as part of
     *                      {@link mEnsureActivitiesVisibleHelper}.
     *
     */
    void ensureActivitiesVisible(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        ensureActivitiesVisible(starting, configChanges, preserveWindows, true /* notifyClients */);
    }

    /**
     * Ensure visibility with an option to also update the configuration of visible activities.
     * @see #ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @see RootWindowContainer#ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     * @param notifyClients Flag indicating whether the visibility updates should be sent to the
     *                      clients in {@link mEnsureActivitiesVisibleHelper}.
     * @param preserveWindows Flag indicating whether windows should be preserved when updating
     *                        configuration in {@link mEnsureActivitiesVisibleHelper}.
     * @param configChanges Parts of the configuration that changed for this activity for evaluating
     *                      if the screen should be frozen as part of
     *                      {@link mEnsureActivitiesVisibleHelper}.
     */
    // TODO: Should be re-worked based on the fact that each task as a stack in most cases.
    void ensureActivitiesVisible(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mTopActivityOccludesKeyguard = false;
        mTopDismissingKeyguardActivity = null;
        mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
        try {
            mEnsureActivitiesVisibleHelper.process(
                    starting, configChanges, preserveWindows, notifyClients);

            if (mTranslucentActivityWaiting != null &&
                    mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
                // Nothing is getting drawn or everything was already visible, don't wait for timeout.
                notifyActivityDrawnLocked(null);
            }
        } finally {
            mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
        }
    }

    /**
     * @return true if the top visible activity wants to occlude the Keyguard, false otherwise
     */
    boolean topActivityOccludesKeyguard() {
        return mTopActivityOccludesKeyguard;
    }

    /**
     * Returns true if this stack should be resized to match the bounds specified by
     * {@link ActivityOptions#setLaunchBounds} when launching an activity into the stack.
     */
    boolean shouldResizeStackWithLaunchBounds() {
        return inPinnedWindowingMode();
    }

    // TODO(NOW!)
    /**
     * Returns {@code true} if this is the top-most split-screen-primary or
     * split-screen-secondary stack, {@code false} otherwise.
     */
    boolean isTopSplitScreenStack() {
        return inSplitScreenWindowingMode()
                && this == getDisplayArea().getTopStackInWindowingMode(getWindowingMode());
    }

    /**
     * @return the top most visible activity that wants to dismiss Keyguard
     */
    ActivityRecord getTopDismissingKeyguardActivity() {
        return mTopDismissingKeyguardActivity;
    }

    /**
     * Checks whether {@param r} should be visible depending on Keyguard state and updates
     * {@link #mTopActivityOccludesKeyguard} and {@link #mTopDismissingKeyguardActivity} if
     * necessary.
     *
     * @return true if {@param r} is visible taken Keyguard state into account, false otherwise
     */
    boolean checkKeyguardVisibility(ActivityRecord r, boolean shouldBeVisible, boolean isTop) {
        int displayId = getDisplayId();
        if (displayId == INVALID_DISPLAY) displayId = DEFAULT_DISPLAY;

        final boolean keyguardOrAodShowing = mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        final boolean keyguardLocked = mStackSupervisor.getKeyguardController().isKeyguardLocked();
        final boolean showWhenLocked = r.canShowWhenLocked();
        final boolean dismissKeyguard = r.containsDismissKeyguardWindow();
        if (shouldBeVisible) {
            if (dismissKeyguard && mTopDismissingKeyguardActivity == null) {
                mTopDismissingKeyguardActivity = r;
            }

            // Only the top activity may control occluded, as we can't occlude the Keyguard if the
            // top app doesn't want to occlude it.
            if (isTop) {
                mTopActivityOccludesKeyguard |= showWhenLocked;
            }

            final boolean canShowWithKeyguard = canShowWithInsecureKeyguard()
                    && mStackSupervisor.getKeyguardController().canDismissKeyguard();
            if (canShowWithKeyguard) {
                return true;
            }
        }
        if (keyguardOrAodShowing) {
            // If keyguard is showing, nothing is visible, except if we are able to dismiss Keyguard
            // right away and AOD isn't visible.
            return shouldBeVisible && mStackSupervisor.getKeyguardController()
                    .canShowActivityWhileKeyguardShowing(r, dismissKeyguard);
        } else if (keyguardLocked) {
            return shouldBeVisible && mStackSupervisor.getKeyguardController().canShowWhileOccluded(
                    dismissKeyguard, showWhenLocked);
        } else {
            return shouldBeVisible;
        }
    }

    /**
     * Check if the display to which this stack is attached has
     * {@link Display#FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD} applied.
     */
    boolean canShowWithInsecureKeyguard() {
        final DisplayContent displayContent = getDisplay();
        if (displayContent == null) {
            throw new IllegalStateException("Stack is not attached to any display, stackId="
                    + getRootTaskId());
        }

        final int flags = displayContent.mDisplay.getFlags();
        return (flags & FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0;
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
        mUndrawnActivitiesBelowTopTranslucent.clear();
        mHandler.sendEmptyMessageDelayed(TRANSLUCENT_TIMEOUT_MSG, TRANSLUCENT_CONVERSION_TIMEOUT);
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
                mWmService.setWindowOpaqueLocked(waitingActivity.appToken, false);
                if (waitingActivity.attachedToProcess()) {
                    try {
                        waitingActivity.app.getThread().scheduleTranslucentConversionComplete(
                                waitingActivity.appToken, r != null);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /** @see ActivityRecord#cancelInitializing() */
    void cancelInitializingActivities() {
        // We don't want to clear starting window for activities that aren't behind fullscreen
        // activities as we need to display their starting window until they are done initializing.
        checkBehindFullscreenActivity(null /* toCheck */, ActivityRecord::cancelInitializing);
    }

    /**
     * If an activity {@param toCheck} is given, this method returns {@code true} if the activity
     * is occluded by any fullscreen activity. If there is no {@param toCheck} and the handling
     * function {@param handleBehindFullscreenActivity} is given, this method will pass all occluded
     * activities to the function.
     */
    boolean checkBehindFullscreenActivity(ActivityRecord toCheck,
            Consumer<ActivityRecord> handleBehindFullscreenActivity) {
        return mCheckBehindFullscreenActivityHelper.process(
                toCheck, handleBehindFullscreenActivity);
    }

    /**
     * Ensure that the top activity in the stack is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     * @param options Activity options.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     *
     * NOTE: It is not safe to call this method directly as it can cause an activity in a
     *       non-focused stack to be resumed.
     *       Use {@link RootWindowContainer#resumeFocusedStacksTopActivities} to resume the
     *       right activity for the current system state.
     */
    @GuardedBy("mService")
    boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (mInResumeTopActivity) {
            // Don't even start recursing.
            return false;
        }

        boolean result = false;
        try {
            // Protect against recursion.
            mInResumeTopActivity = true;
            result = resumeTopActivityInnerLocked(prev, options);

            // When resuming the top activity, it may be necessary to pause the top activity (for
            // example, returning to the lock screen. We suppress the normal pause logic in
            // {@link #resumeTopActivityUncheckedLocked}, since the top activity is resumed at the
            // end. We call the {@link ActivityStackSupervisor#checkReadyForSleepLocked} again here
            // to ensure any necessary pause logic occurs. In the case where the Activity will be
            // shown regardless of the lock screen, the call to
            // {@link ActivityStackSupervisor#checkReadyForSleepLocked} is skipped.
            final ActivityRecord next = topRunningActivity(true /* focusableOnly */);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
        } finally {
            mInResumeTopActivity = false;
        }

        return result;
    }

    @GuardedBy("mService")
    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        if (!mAtmService.isBooting() && !mAtmService.isBooted()) {
            // Not ready yet!
            return false;
        }

        // Find the next top-most activity to resume in this stack that is not finishing and is
        // focusable. If it is not focusable, we will fall into the case below to resume the
        // top activity in the next focusable task.
        ActivityRecord next = topRunningActivity(true /* focusableOnly */);

        final boolean hasRunningActivity = next != null;

        // TODO: Maybe this entire condition can get removed?
        if (hasRunningActivity && !isAttached()) {
            return false;
        }

        mRootWindowContainer.cancelInitializingActivities();

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        boolean userLeaving = mStackSupervisor.mUserLeaving;
        mStackSupervisor.mUserLeaving = false;

        if (!hasRunningActivity) {
            // There are no activities left in the stack, let's look somewhere else.
            return resumeNextFocusableActivityWhenStackIsEmpty(prev, options);
        }

        next.delayedResume = false;
        final TaskDisplayArea taskDisplayArea = getDisplayArea();

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed " + next);
            return false;
        }

        if (!next.canResumeByCompat()) {
            return false;
        }

        // If we are currently pausing an activity, then don't do anything until that is done.
        final boolean allPausedComplete = mRootWindowContainer.allPausedActivitiesComplete();
        if (!allPausedComplete) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) {
                Slog.v(TAG_PAUSE, "resumeTopActivityLocked: Skip resume: some activity pausing.");
            }
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top activity is paused,
        // well that is the state we want.
        if (shouldSleepOrShutDownActivities()
                && mLastPausedActivity == next
                && mRootWindowContainer.allPausedActivitiesComplete()) {
            // If the current top activity may be able to occlude keyguard but the occluded state
            // has not been set, update visibility and check again if we should continue to resume.
            boolean nothingToResume = true;
            if (!mAtmService.mShuttingDown) {
                final boolean canShowWhenLocked = !mTopActivityOccludesKeyguard
                        && next.canShowWhenLocked();
                final boolean mayDismissKeyguard = mTopDismissingKeyguardActivity != next
                        && next.containsDismissKeyguardWindow();

                if (canShowWhenLocked || mayDismissKeyguard) {
                    ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                            !PRESERVE_WINDOWS);
                    nothingToResume = shouldSleepActivities();
                }
            }
            if (nothingToResume) {
                // Make sure we have executed any pending transitions, since there
                // should be nothing left to do at this point.
                executeAppTransition(options);
                if (DEBUG_STATES) Slog.d(TAG_STATES,
                        "resumeTopActivityLocked: Going to sleep and all paused");
                return false;
            }
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (!mAtmService.mAmInternal.hasStartedUserState(next.mUserId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.mUserId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStackSupervisor.mStoppingActivities.remove(next);
        next.setSleeping(false);

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything until that is done.
        if (!mRootWindowContainer.allPausedActivitiesComplete()) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "resumeTopActivityLocked: Skip resume: some activity pausing.");

            return false;
        }

        mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        ActivityRecord lastResumed = null;
        final ActivityStack lastFocusedStack = taskDisplayArea.getLastFocusedStack();
        if (lastFocusedStack != null && lastFocusedStack != this) {
            // So, why aren't we using prev here??? See the param comment on the method. prev doesn't
            // represent the last resumed activity. However, the last focus stack does if it isn't null.
            lastResumed = lastFocusedStack.mResumedActivity;
            if (userLeaving && inMultiWindowMode() && lastFocusedStack.shouldBeVisible(next)) {
                // The user isn't leaving if this stack is the multi-window mode and the last
                // focused stack should still be visible.
                if(DEBUG_USER_LEAVING) Slog.i(TAG_USER_LEAVING, "Overriding userLeaving to false"
                        + " next=" + next + " lastResumed=" + lastResumed);
                userLeaving = false;
            }
        }

        boolean pausing = taskDisplayArea.pauseBackStacks(userLeaving, next);
        if (mResumedActivity != null) {
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Pausing " + mResumedActivity);
            pausing |= startPausingLocked(userLeaving, false /* uiSleeping */, next);
        }
        if (pausing) {
            if (DEBUG_SWITCH || DEBUG_STATES) Slog.v(TAG_STATES,
                    "resumeTopActivityLocked: Skip resume: need to start pausing");
            // At this point we want to put the upcoming activity's process
            // at the top of the LRU list, since we know we will be needing it
            // very soon and it would be a waste to let it get killed if it
            // happens to be sitting towards the end.
            if (next.attachedToProcess()) {
                next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                        true /* activityChange */, false /* updateOomAdj */);
            } else if (!next.isProcessRunning()) {
                // Since the start-process is asynchronous, if we already know the process of next
                // activity isn't running, we can start the process earlier to save the time to wait
                // for the current activity to be paused.
                final boolean isTop = this == taskDisplayArea.getFocusedStack();
                mAtmService.startProcessAsync(next, false /* knownToBeDead */, isTop,
                        isTop ? "pre-top-activity" : "pre-activity");
            }
            if (lastResumed != null) {
                lastResumed.setWillCloseOrEnterPip(true);
            }
            return true;
        } else if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // It is possible for the activity to be resumed when we paused back stacks above if the
            // next activity doesn't have to wait for pause to complete.
            // So, nothing else to-do except:
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "resumeTopActivityLocked: Top activity resumed (dontWaitForPause) " + next);
            return true;
        }

        // If the most recent activity was noHistory but was only stopped rather
        // than stopped+finished because the device went to sleep, we need to make
        // sure to finish it as we're making a new activity topmost.
        if (shouldSleepActivities() && mLastNoHistoryActivity != null &&
                !mLastNoHistoryActivity.finishing) {
            if (DEBUG_STATES) Slog.d(TAG_STATES,
                    "no-history finish of " + mLastNoHistoryActivity + " on new resume");
            mLastNoHistoryActivity.finishIfPossible("resume-no-history", false /* oomAdj */);
            mLastNoHistoryActivity = null;
        }

        if (prev != null && prev != next && next.nowVisible) {

            // The next activity is already visible, so hide the previous
            // activity's windows right now so we can show the new one ASAP.
            // We only do this if the previous is finishing, which should mean
            // it is on top of the one being resumed so hiding it quickly
            // is good.  Otherwise, we want to do the normal route of allowing
            // the resumed activity to be shown so we can decide if the
            // previous should actually be hidden depending on whether the
            // new one is found to be full-screen or not.
            if (prev.finishing) {
                prev.setVisibility(false);
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Not waiting for visible to hide: " + prev
                        + ", nowVisible=" + next.nowVisible);
            } else {
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Previous already visible but still waiting to hide: " + prev
                        + ", nowVisible=" + next.nowVisible);
            }

        }

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        try {
            mAtmService.getPackageManager().setPackageStoppedState(
                    next.packageName, false, next.mUserId); /* TODO: Verify if correct userid */
        } catch (RemoteException e1) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        boolean anim = true;
        final DisplayContent dc = taskDisplayArea.mDisplayContent;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare close transition: prev=" + prev);
                if (mStackSupervisor.mNoAnimActivities.contains(prev)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    dc.prepareAppTransition(
                            prev.getTask() == next.getTask() ? TRANSIT_ACTIVITY_CLOSE
                                    : TRANSIT_TASK_CLOSE, false);
                }
                prev.setVisibility(false);
            } else {
                if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                        "Prepare open transition: prev=" + prev);
                if (mStackSupervisor.mNoAnimActivities.contains(next)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE, false);
                } else {
                    dc.prepareAppTransition(
                            prev.getTask() == next.getTask() ? TRANSIT_ACTIVITY_OPEN
                                    : next.mLaunchTaskBehind ? TRANSIT_TASK_OPEN_BEHIND
                                            : TRANSIT_TASK_OPEN, false);
                }
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
            if (mStackSupervisor.mNoAnimActivities.contains(next)) {
                anim = false;
                dc.prepareAppTransition(TRANSIT_NONE, false);
            } else {
                dc.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false);
            }
        }

        if (anim) {
            next.applyOptionsLocked();
        } else {
            next.clearOptionsLocked();
        }

        mStackSupervisor.mNoAnimActivities.clear();

        if (next.attachedToProcess()) {
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resume running: " + next
                    + " stopped=" + next.stopped
                    + " visibleRequested=" + next.mVisibleRequested);

            // If the previous activity is translucent, force a visibility update of
            // the next activity, so that it's added to WM's opening app list, and
            // transition animation can be set up properly.
            // For example, pressing Home button with a translucent activity in focus.
            // Launcher is already visible in this case. If we don't add it to opening
            // apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
            // TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
            final boolean lastActivityTranslucent = lastFocusedStack != null
                    && (lastFocusedStack.inMultiWindowMode()
                    || (lastFocusedStack.mLastPausedActivity != null
                    && !lastFocusedStack.mLastPausedActivity.occludesParent()));

            // This activity is now becoming visible.
            if (!next.mVisibleRequested || next.stopped || lastActivityTranslucent) {
                next.setVisibility(true);
            }

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity =
                    lastFocusedStack == null ? null : lastFocusedStack.mResumedActivity;
            final ActivityState lastState = next.getState();

            mAtmService.updateCpuStats();

            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + next
                    + " (in existing)");

            next.setState(RESUMED, "resumeTopActivityInnerLocked");

            next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */);

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            boolean notUpdated = true;

            // Activity should also be visible if set mLaunchTaskBehind to true (see
            // ActivityRecord#shouldBeVisibleIgnoringKeyguard()).
            if (shouldBeVisible(next)) {
                // We have special rotation behavior when here is some active activity that
                // requests specific orientation or Keyguard is locked. Make sure all activity
                // visibilities are set correctly as well as the transition is updated if needed
                // to get the correct rotation behavior. Otherwise the following call to update
                // the orientation may cause incorrect configurations delivered to client as a
                // result of invisible window resize.
                // TODO: Remove this once visibilities are set correctly immediately when
                // starting an activity.
                notUpdated = !mRootWindowContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                        true /* markFrozenIfConfigChanged */, false /* deferResume */);
            }

            if (notUpdated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                ActivityRecord nextNext = topRunningActivity();
                if (DEBUG_SWITCH || DEBUG_STATES) Slog.i(TAG_STATES,
                        "Activity config changed during resume: " + next
                                + ", new next: " + nextNext);
                if (nextNext != next) {
                    // Do over!
                    mStackSupervisor.scheduleResumeTopActivities();
                }
                if (!next.mVisibleRequested || next.stopped) {
                    next.setVisibility(true);
                }
                next.completeResumeLocked();
                return true;
            }

            try {
                final ClientTransaction transaction =
                        ClientTransaction.obtain(next.app.getThread(), next.appToken);
                // Deliver all pending results.
                ArrayList<ResultInfo> a = next.results;
                if (a != null) {
                    final int N = a.size();
                    if (!next.finishing && N > 0) {
                        if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                                "Delivering results to " + next + ": " + a);
                        transaction.addCallback(ActivityResultItem.obtain(a));
                    }
                }

                if (next.newIntents != null) {
                    transaction.addCallback(
                            NewIntentItem.obtain(next.newIntents, true /* resume */));
                }

                // Well the app will no longer be stopped.
                // Clear app token stopped state in window manager if needed.
                next.notifyAppResumed(next.stopped);

                EventLogTags.writeWmResumeActivity(next.mUserId, System.identityHashCode(next),
                        next.getTask().mTaskId, next.shortComponentName);

                next.setSleeping(false);
                mAtmService.getAppWarningsLocked().onResumeActivity(next);
                next.app.setPendingUiCleanAndForceProcessStateUpTo(mAtmService.mTopProcessState);
                next.clearOptionsLocked();
                transaction.setLifecycleStateRequest(
                        ResumeActivityItem.obtain(next.app.getReportedProcState(),
                                dc.isNextTransitionForward()));
                mAtmService.getLifecycleManager().scheduleTransaction(transaction);

                if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Resumed "
                        + next);
            } catch (Exception e) {
                // Whoops, need to restart this activity!
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Resume failed; resetting state to "
                        + lastState + ": " + next);
                next.setState(lastState, "resumeTopActivityInnerLocked");

                // lastResumedActivity being non-null implies there is a lastStack present.
                if (lastResumedActivity != null) {
                    lastResumedActivity.setState(RESUMED, "resumeTopActivityInnerLocked");
                }

                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else  if (SHOW_APP_STARTING_PREVIEW && lastFocusedStack != null
                        && lastFocusedStack.isTopStackInDisplayArea()) {
                    next.showStartingWindow(null /* prev */, false /* newTask */,
                            false /* taskSwitch */);
                }
                mStackSupervisor.startSpecificActivity(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.completeResumeLocked();
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Slog.w(TAG, "Exception thrown during resume of " + next, e);
                next.finishIfPossible("resume-exception", true /* oomAdj */);
                return true;
            }
        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_PREVIEW) {
                    next.showStartingWindow(null /* prev */, false /* newTask */,
                            false /* taskSwich */);
                }
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
            }
            if (DEBUG_STATES) Slog.d(TAG_STATES, "resumeTopActivityLocked: Restarting " + next);
            mStackSupervisor.startSpecificActivity(next, true, true);
        }

        return true;
    }

    /**
     * Resume the next eligible activity in a focusable stack when this one does not have any
     * running activities left. The focus will be adjusted to the next focusable stack and
     * top running activities will be resumed in all focusable stacks. However, if the current stack
     * is a home stack - we have to keep it focused, start and resume a home activity on the current
     * display instead to make sure that the display is not empty.
     */
    private boolean resumeNextFocusableActivityWhenStackIsEmpty(ActivityRecord prev,
            ActivityOptions options) {
        final String reason = "noMoreActivities";

        if (!isActivityTypeHome()) {
            final ActivityStack nextFocusedStack = adjustFocusToNextFocusableTask(reason);
            if (nextFocusedStack != null) {
                // Try to move focus to the next visible stack with a running activity if this
                // stack is not covering the entire screen or is on a secondary display with no home
                // stack.
                return mRootWindowContainer.resumeFocusedStacksTopActivities(nextFocusedStack,
                        prev, null /* targetOptions */);
            }
        }

        // If the current stack is a home stack, or if focus didn't switch to a different stack -
        // just start up the Launcher...
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeNextFocusableActivityWhenStackIsEmpty: " + reason + ", go home");
        return mRootWindowContainer.resumeHomeActivity(prev, reason, getDisplayArea());
    }

    void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity,
            boolean newTask, boolean keepCurTransition, ActivityOptions options) {
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
        if (!newTask && isOrhasTask) {
            // Starting activity cannot be occluding activity, otherwise starting window could be
            // remove immediately without transferring to starting activity.
            final ActivityRecord occludingActivity = getOccludingActivityAbove(r);
            if (occludingActivity != null) {
                // Here it is!  Now, if this is not yet visible (occluded by another task) to the
                // user, then just add it without starting; it will get started when the user
                // navigates back to it.
                if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to task " + task,
                        new RuntimeException("here").fillInStackTrace());
                rTask.positionChildAtTop(r);
                ActivityOptions.abort(options);
                return;
            }
        }

        // Place a new activity at top of stack, so it is next to interact with the user.

        // If we are not placing the new activity frontmost, we do not want to deliver the
        // onUserLeaving callback to the actual frontmost activity
        final Task activityTask = r.getTask();
        if (task == activityTask && mChildren.indexOf(task) != (getChildCount() - 1)) {
            mStackSupervisor.mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                    "startActivity() behind front, mUserLeaving=false");
        }

        task = activityTask;

        // Slot the activity into the history stack and proceed
        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to stack to task " + task,
                new RuntimeException("here").fillInStackTrace());
        task.positionChildAtTop(r);

        // The transition animation and starting window are not needed if {@code allowMoveToFront}
        // is false, because the activity won't be visible.
        if ((!isHomeOrRecentsStack() || hasActivity()) && allowMoveToFront) {
            final DisplayContent dc = getDisplay().mDisplayContent;
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION,
                    "Prepare open transition: starting " + r);
            if ((r.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                dc.prepareAppTransition(TRANSIT_NONE, keepCurTransition);
                mStackSupervisor.mNoAnimActivities.add(r);
            } else {
                int transit = TRANSIT_ACTIVITY_OPEN;
                if (newTask) {
                    if (r.mLaunchTaskBehind) {
                        transit = TRANSIT_TASK_OPEN_BEHIND;
                    } else if (getDisplay().isSingleTaskInstance()) {
                        transit = TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
                    } else {
                        // If a new task is being launched, then mark the existing top activity as
                        // supporting picture-in-picture while pausing only if the starting activity
                        // would not be considered an overlay on top of the current activity
                        // (eg. not fullscreen, or the assistant)
                        if (canEnterPipOnTaskSwitch(focusedTopActivity,
                                null /* toFrontTask */, r, options)) {
                            focusedTopActivity.supportsEnterPipOnTaskSwitch = true;
                        }
                        transit = TRANSIT_TASK_OPEN;
                    }
                }
                dc.prepareAppTransition(transit, keepCurTransition);
                mStackSupervisor.mNoAnimActivities.remove(r);
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
            if (r.mLaunchTaskBehind) {
                // Don't do a starting window for mLaunchTaskBehind. More importantly make sure we
                // tell WindowManager that r is visible even though it is at the back of the stack.
                r.setVisibility(true);
                ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                // Go ahead to execute app transition for this activity since the app transition
                // will not be triggered through the resume channel.
                getDisplay().mDisplayContent.executeAppTransition();
            } else if (SHOW_APP_STARTING_PREVIEW && doShow) {
                // Figure out if we are transitioning from another activity that is
                // "has the same starting icon" as the next one.  This allows the
                // window manager to keep the previous window it had previously
                // created, if it still had one.
                Task prevTask = r.getTask();
                ActivityRecord prev = prevTask.topRunningActivityWithStartingWindowLocked();
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.getTask() != prevTask) {
                        prev = null;
                    }
                    // (2) The current activity is already displayed.
                    else if (prev.nowVisible) {
                        prev = null;
                    }
                }
                r.showStartingWindow(prev, newTask, isTaskSwitch(r, focusedTopActivity));
            }
        } else {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            ActivityOptions.abort(options);
        }
    }

    /**
     * @return Whether the switch to another task can trigger the currently running activity to
     * enter PiP while it is pausing (if supported). Only one of {@param toFrontTask} or
     * {@param toFrontActivity} should be set.
     */
    private boolean canEnterPipOnTaskSwitch(ActivityRecord pipCandidate,
            Task toFrontTask, ActivityRecord toFrontActivity, ActivityOptions opts) {
        if (opts != null && opts.disallowEnterPictureInPictureWhileLaunching()) {
            // Ensure the caller has requested not to trigger auto-enter PiP
            return false;
        }
        if (pipCandidate == null || pipCandidate.inPinnedWindowingMode()) {
            // Ensure that we do not trigger entering PiP an activity on the pinned stack
            return false;
        }
        final ActivityStack targetStack = toFrontTask != null
                ? toFrontTask.getStack() : toFrontActivity.getRootTask();
        if (targetStack != null && targetStack.isActivityTypeAssistant()) {
            // Ensure the task/activity being brought forward is not the assistant
            return false;
        }
        return true;
    }

    private boolean isTaskSwitch(ActivityRecord r, ActivityRecord topFocusedActivity) {
        return topFocusedActivity != null && r.getTask() != topFocusedActivity.getTask();
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
        final ActivityOptions topOptions = sResetTargetTaskHelper.process(task, forceReset);

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
     * @return The task that was finished in this stack, {@code null} if top running activity does
     *         not belong to the crashed app.
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
        getDisplay().mDisplayContent.prepareAppTransition(
                TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
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

    void finishVoiceTask(IVoiceInteractionSession session) {
        final PooledConsumer c = PooledLambda.obtainConsumer(ActivityStack::finishIfVoiceTask,
                PooledLambda.__(Task.class), session.asBinder());
        forAllLeafTasks(c, true /* traverseTopToBottom */);
        c.recycle();
    }

    private static void finishIfVoiceTask(Task tr, IBinder binder) {
        if (tr.voiceSession != null && tr.voiceSession.asBinder() == binder) {
            tr.forAllActivities((r) -> {
                if (r.finishing) return;
                r.finishIfPossible("finish-voice", false /* oomAdj */);
                tr.mAtmService.updateOomAdj();
            });
        } else {
            // Check if any of the activities are using voice
            final PooledFunction f = PooledLambda.obtainFunction(
                    ActivityStack::finishIfVoiceActivity, PooledLambda.__(ActivityRecord.class),
                    binder);
            tr.forAllActivities(f);
            f.recycle();
        }
    }

    private static boolean finishIfVoiceActivity(ActivityRecord r, IBinder binder) {
        if (r.voiceSession == null || r.voiceSession.asBinder() != binder) return false;
        // Inform of cancellation
        r.clearVoiceSessionLocked();
        try {
            r.app.getThread().scheduleLocalVoiceInteractionStarted(r.appToken, null);
        } catch (RemoteException re) {
            // Ok Boomer...
        }
        r.mAtmService.finishRunningVoiceLocked();
        return true;
    }

    /** Finish all activities in the stack without waiting. */
    void finishAllActivitiesImmediately() {
        if (!hasChild()) {
            removeIfPossible();
            return;
        }
        forAllActivities((r) -> {
            Slog.d(TAG, "finishAllActivitiesImmediatelyLocked: finishing " + r);
            r.destroyIfPossible("finishAllActivitiesImmediately");
        });
    }

    /** @return true if the stack behind this one is a standard activity type. */
    private boolean inFrontOfStandardStack() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        if (taskDisplayArea == null) {
            return false;
        }
        final int index = taskDisplayArea.getIndexOf(this);
        if (index == 0) {
            return false;
        }
        final ActivityStack stackBehind = taskDisplayArea.getChildAt(index - 1);
        return stackBehind.isActivityTypeStandard();
    }

    boolean shouldUpRecreateTaskLocked(ActivityRecord srec, String destAffinity) {
        // Basic case: for simple app-centric recents, we need to recreate
        // the task if the affinity has changed.
        if (srec == null || srec.getTask().affinity == null
                || !srec.getTask().affinity.equals(destAffinity)) {
            return true;
        }
        // Document-centric case: an app may be split in to multiple documents;
        // they need to re-create their task if this current activity is the root
        // of a document, unless simply finishing it will return them to the the
        // correct app behind.
        final Task task = srec.getTask();
        if (srec.isRootOfTask() && task.getBaseIntent() != null
                && task.getBaseIntent().isDocument()) {
            // Okay, this activity is at the root of its task.  What to do, what to do...
            if (!inFrontOfStandardStack()) {
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

    boolean navigateUpTo(ActivityRecord srec, Intent destIntent, int resultCode,
            Intent resultData) {
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
            final ActivityRecord candidate = task.getActivity((ar) ->
                    ar.info.packageName.equals(dest.getPackageName()) &&
                    ar.info.name.equals(dest.getClassName()), srec, false /*includeBoundary*/,
                    true /*traverseTopToBottom*/);
            if (candidate != null) {
                parent = candidate;
                foundParentInTask = true;
            }
        }

        // TODO: There is a dup. of this block of code in ActivityTaskManagerService.finishActivity
        // We should consolidate.
        IActivityController controller = mAtmService.mController;
        if (controller != null) {
            ActivityRecord next = topRunningActivity(srec.appToken, INVALID_TASK_ID);
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
        final ActivityRecord finalParent = parent;
        task.forAllActivities((ar) -> {
            if (ar == finalParent) return true;

            ar.finishIfPossible(
                    resultCodeHolder[0], resultDataHolder[0], "navigate-up", true /* oomAdj */);
            // Only return the supplied result for the first activity finished
            resultCodeHolder[0] = Activity.RESULT_CANCELED;
            resultDataHolder[0] = null;
            return false;
        }, srec, true, true);
        resultCode = resultCodeHolder[0];
        resultData = resultDataHolder[0];

        if (parent != null && foundParentInTask) {
            final int callingUid = srec.info.applicationInfo.uid;
            final int parentLaunchMode = parent.info.launchMode;
            final int destIntentFlags = destIntent.getFlags();
            if (parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TOP ||
                    (destIntentFlags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                parent.deliverNewIntentLocked(callingUid, destIntent, srec.packageName);
            } else {
                try {
                    ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(
                            destIntent.getComponent(), ActivityManagerService.STOCK_PM_FLAGS,
                            srec.mUserId);
                    // TODO(b/64750076): Check if calling pid should really be -1.
                    final int res = mAtmService.getActivityStartController()
                            .obtainStarter(destIntent, "navigateUpTo")
                            .setCaller(srec.app.getThread())
                            .setActivityInfo(aInfo)
                            .setResultTo(parent.appToken)
                            .setCallingPid(-1)
                            .setCallingUid(callingUid)
                            .setCallingPackage(srec.packageName)
                            .setCallingFeatureId(parent.launchedFromFeatureId)
                            .setRealCallingPid(-1)
                            .setRealCallingUid(callingUid)
                            .setComponentSpecified(true)
                            .execute();
                    foundParentInTask = res == ActivityManager.START_SUCCESS;
                } catch (RemoteException e) {
                    foundParentInTask = false;
                }
                parent.finishIfPossible(resultCode, resultData, "navigate-top", true /* oomAdj */);
            }
        }
        Binder.restoreCallingIdentity(origId);
        return foundParentInTask;
    }

    void removeLaunchTickMessages() {
        forAllActivities(ActivityRecord::removeLaunchTickRunnable);
    }

    private void updateTransitLocked(int transit, ActivityOptions options) {
        if (options != null) {
            ActivityRecord r = topRunningActivity();
            if (r != null && !r.isState(RESUMED)) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        getDisplay().mDisplayContent.prepareAppTransition(transit, false);
    }

    final void moveTaskToFront(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, String reason) {
        moveTaskToFront(tr, noAnimation, options, timeTracker, !DEFER_RESUME, reason);
    }

    final void moveTaskToFront(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, boolean deferResume, String reason) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "moveTaskToFront: " + tr);

        final ActivityStack topStack = getDisplayArea().getTopStack();
        final ActivityRecord topActivity = topStack != null
                ? topStack.getTopNonFinishingActivity() : null;

        if (tr != this && !tr.isDescendantOf(this)) {
            // nothing to do!
            if (noAnimation) {
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(TRANSIT_TASK_TO_FRONT, options);
            }
            return;
        }

        if (timeTracker != null) {
            // The caller wants a time tracker associated with this task.
            final PooledConsumer c = PooledLambda.obtainConsumer(ActivityRecord::setAppTimeTracker,
                    PooledLambda.__(ActivityRecord.class), timeTracker);
            tr.forAllActivities(c);
            c.recycle();
        }

        try {
            // Defer updating the IME target since the new IME target will try to get computed
            // before updating all closing and opening apps, which can cause the ime target to
            // get calculated incorrectly.
            getDisplay().deferUpdateImeTarget();

            // Shift all activities with this task up to the top
            // of the stack, keeping them in the same internal order.
            positionChildAtTop(tr);

            // Don't refocus if invisible to current user
            final ActivityRecord top = tr.getTopNonFinishingActivity();
            if (top == null || !top.okToShowLocked()) {
                if (top != null) {
                    mStackSupervisor.mRecentTasks.add(top.getTask());
                }
                ActivityOptions.abort(options);
                return;
            }

            // Set focus to the top running activity of this stack.
            final ActivityRecord r = topRunningActivity();
            if (r != null) {
                r.moveFocusableActivityToTop(reason);
            }

            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to front transition: task=" + tr);
            if (noAnimation) {
                getDisplay().mDisplayContent.prepareAppTransition(TRANSIT_NONE, false);
                if (r != null) {
                    mStackSupervisor.mNoAnimActivities.add(r);
                }
                ActivityOptions.abort(options);
            } else {
                updateTransitLocked(TRANSIT_TASK_TO_FRONT, options);
            }
            // If a new task is moved to the front, then mark the existing top activity as
            // supporting

            // picture-in-picture while paused only if the task would not be considered an oerlay
            // on top
            // of the current activity (eg. not fullscreen, or the assistant)
            if (canEnterPipOnTaskSwitch(topActivity, tr, null /* toFrontActivity */,
                    options)) {
                topActivity.supportsEnterPipOnTaskSwitch = true;
            }

            if (!deferResume) {
                mRootWindowContainer.resumeFocusedStacksTopActivities();
            }
            EventLogTags.writeWmTaskToFront(tr.mUserId, tr.mTaskId);
            mAtmService.getTaskChangeNotificationController()
                    .notifyTaskMovedToFront(tr.getTaskInfo());
        } finally {
            getDisplay().continueUpdateImeTarget();
        }
    }

    /**
     * Worker method for rearranging history stack. Implements the function of moving all
     * activities for a specific task (gathering them if disjoint) into a single group at the
     * bottom of the stack.
     *
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     *
     * @param tr The task to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    boolean moveTaskToBack(Task tr) {
        Slog.i(TAG, "moveTaskToBack: " + tr);

        // In LockTask mode, moving a locked task to the back of the stack may expose unlocked
        // ones. Therefore we need to check if this operation is allowed.
        if (!mAtmService.getLockTaskController().canMoveTaskToBack(tr)) {
            return false;
        }

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (isTopStackInDisplayArea() && mAtmService.mController != null) {
            ActivityRecord next = topRunningActivity(null, tr.mTaskId);
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

        if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare to back transition: task="
                + tr.mTaskId);

        getDisplay().mDisplayContent.prepareAppTransition(TRANSIT_TASK_TO_BACK, false);
        moveToBack("moveTaskToBackLocked", tr);

        if (inPinnedWindowingMode()) {
            mStackSupervisor.removeStack(this);
            return true;
        }

        ActivityRecord topActivity = getDisplayArea().topRunningActivity();
        ActivityStack topStack = topActivity.getRootTask();
        if (topStack != null && topStack != this && topActivity.isState(RESUMED)) {
            // The new top activity is already resumed, so there's a good chance that nothing will
            // get resumed below. So, update visibility now in case the transition is closed
            // prematurely.
            mRootWindowContainer.ensureVisibilityAndConfig(null /* starting */,
                    getDisplay().mDisplayId, false /* markFrozenIfConfigChanged */,
                    false /* deferResume */);
        } else {
            mRootWindowContainer.resumeFocusedStacksTopActivities();
        }
        return true;
    }

    /**
     * Ensures all visible activities at or below the input activity have the right configuration.
     */
    void ensureVisibleActivitiesConfiguration(ActivityRecord start, boolean preserveWindow) {
        mEnsureVisibleActivitiesConfigHelper.process(start, preserveWindow);
    }

    // TODO: Can only be called from special methods in ActivityStackSupervisor.
    // Need to consolidate those calls points into this resize method so anyone can call directly.
    void resize(Rect displayedBounds, boolean preserveWindows, boolean deferResume) {
        if (!updateBoundsAllowed(displayedBounds)) {
            return;
        }

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "stack.resize_" + getRootTaskId());
        mAtmService.deferWindowLayout();
        try {
            // TODO: Why not just set this on the stack directly vs. on each tasks?
            // Update override configurations of all tasks in the stack.
            final PooledConsumer c = PooledLambda.obtainConsumer(
                    ActivityStack::processTaskResizeBounds, PooledLambda.__(Task.class),
                    displayedBounds);
            forAllTasks(c, true /* traverseTopToBottom */);
            c.recycle();

            if (mBoundsAnimating) {
                // Force to update task surface bounds and relayout windows, since configBounds
                // remains unchanged during bounds animation.
                updateSurfaceBounds();
                getDisplay().setLayoutNeeded();
                mWmService.requestTraversal();
            }

            if (!deferResume) {
                ensureVisibleActivitiesConfiguration(topRunningActivity(), preserveWindows);
            }
        } finally {
            mAtmService.continueWindowLayout();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private static void processTaskResizeBounds(Task task, Rect displayedBounds) {
        if (!task.isResizeable()) return;

        task.setBounds(displayedBounds);
    }

    /**
     * Until we can break this "set task bounds to same as stack bounds" behavior, this
     * basically resizes both stack and task bounds to the same bounds.
     */
   private void setTaskBounds(Rect bounds) {
        if (!updateBoundsAllowed(bounds)) {
            return;
        }

        final PooledConsumer c = PooledLambda.obtainConsumer(ActivityStack::setTaskBounds,
                PooledLambda.__(Task.class), bounds);
        forAllLeafTasks(c, true /* traverseTopToBottom */);
        c.recycle();
    }

    private static void setTaskBounds(Task task, Rect bounds) {
        task.setBounds(task.isResizeable() ? bounds : null);
    }

    /**
     * Returns the top-most activity that occludes the given one, or @{code null} if none.
     */
    @Nullable
    private ActivityRecord getOccludingActivityAbove(ActivityRecord activity) {
        return getActivity((ar) -> ar.occludesParent(), true /* traverseTopToBottom */, activity);
    }

    boolean willActivityBeVisible(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            return false;
        }

        // See if there is an occluding activity on-top of this one.
        final ActivityRecord occludingActivity = getOccludingActivityAbove(r);
        if (occludingActivity != null) return false;

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

    /**
     * Reset local parameters because an app's activity died.
     * @param app The app of the activity that died.
     * @return result from removeHistoryRecordsForAppLocked.
     */
    boolean handleAppDied(WindowProcessController app) {
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE || DEBUG_CLEANUP) Slog.v(TAG_PAUSE,
                    "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }

        mStackSupervisor.removeHistoryRecords(app);
        return mRemoveHistoryRecordsForApp.process(app);
    }

    boolean dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage, final boolean needSep) {
        Runnable headerPrinter = () -> {
            if (needSep) {
                pw.println();
            }
            pw.println("  Stack #" + getRootTaskId()
                    + ": type=" + activityTypeToString(getActivityType())
                    + " mode=" + windowingModeToString(getWindowingMode()));
            pw.println("  isSleeping=" + shouldSleepActivities());
            pw.println("  mBounds=" + getRequestedOverrideBounds());
        };

        boolean printed = false;

        if (dumpPackage == null) {
            // If we are not filtering by package, we want to print absolutely everything,
            // so always print the header even if there are no tasks/activities inside.
            headerPrinter.run();
            headerPrinter = null;
            printed = true;
        }

        printed |= printThisActivity(pw, mPausingActivity, dumpPackage, false,
                "    mPausingActivity: ", null);
        printed |= printThisActivity(pw, getResumedActivity(), dumpPackage, false,
                "    mResumedActivity: ", null);
        if (dumpAll) {
            printed |= printThisActivity(pw, mLastPausedActivity, dumpPackage, false,
                    "    mLastPausedActivity: ", null);
            printed |= printThisActivity(pw, mLastNoHistoryActivity, dumpPackage,
                    false, "    mLastNoHistoryActivity: ", null);
        }

        printed |= dumpActivities(fd, pw, dumpAll, dumpClient, dumpPackage, false, headerPrinter);

        return printed;
    }

    private boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, boolean needSep, Runnable header) {
        if (!hasChild()) {
            return false;
        }
        final AtomicBoolean printedHeader = new AtomicBoolean(false);
        final AtomicBoolean printed = new AtomicBoolean(false);
        forAllLeafTasks((task) -> {
            final String prefix = "    ";
            Runnable headerPrinter = () -> {
                printed.set(true);
                if (!printedHeader.get()) {
                    if (needSep) {
                        pw.println("");
                    }
                    if (header != null) {
                        header.run();
                    }
                    printedHeader.set(true);
                }
                pw.print(prefix); pw.print("* "); pw.println(task);
                pw.print(prefix); pw.print("  mBounds=");
                pw.println(task.getRequestedOverrideBounds());
                pw.print(prefix); pw.print("  mMinWidth="); pw.print(task.mMinWidth);
                pw.print(" mMinHeight="); pw.println(task.mMinHeight);
                if (mLastNonFullscreenBounds != null) {
                    pw.print(prefix);
                    pw.print("  mLastNonFullscreenBounds=");
                    pw.println(task.mLastNonFullscreenBounds);
                }
                task.dump(pw, prefix + "  ");
            };
            if (dumpPackage == null) {
                // If we are not filtering by package, we want to print absolutely everything,
                // so always print the header even if there are no activities inside.
                headerPrinter.run();
                headerPrinter = null;
            }
            final ArrayList<ActivityRecord> activities = new ArrayList<>();
            // Add activities by traversing the hierarchy from bottom to top, since activities
            // are dumped in reverse order in {@link ActivityStackSupervisor#dumpHistoryList()}.
            task.forAllActivities((Consumer<ActivityRecord>) activities::add,
                    false /* traverseTopToBottom */);
            dumpHistoryList(fd, pw, activities, prefix, "Hist", true, !dumpAll, dumpClient,
                    dumpPackage, false, headerPrinter, task);
        }, true /* traverseTopToBottom */);
        return printed.get();
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        ArrayList<ActivityRecord> activities = new ArrayList<>();

        if ("all".equals(name)) {
            forAllActivities((Consumer<ActivityRecord>) activities::add);
        } else if ("top".equals(name)) {
            final ActivityRecord topActivity = getTopMostActivity();
            if (topActivity != null) {
                activities.add(topActivity);
            }
        } else {
            ItemMatcher matcher = new ItemMatcher();
            matcher.build(name);

            forAllActivities((r) -> {
                if (matcher.match(r, r.intent.getComponent())) {
                    activities.add(r);
                }
            });
        }

        return activities;
    }

    ActivityRecord restartPackage(String packageName) {
        ActivityRecord starting = topRunningActivity();

        // All activities that came from the package must be
        // restarted as if there was a config change.
        PooledConsumer c = PooledLambda.obtainConsumer(ActivityStack::restartPackage,
                PooledLambda.__(ActivityRecord.class), starting, packageName);
        forAllActivities(c);
        c.recycle();

        return starting;
    }

    private static void restartPackage(
            ActivityRecord r, ActivityRecord starting, String packageName) {
        if (r.info.packageName.equals(packageName)) {
            r.forceNewConfig = true;
            if (starting != null && r == starting && r.mVisibleRequested) {
                r.startFreezingScreenLocked(CONFIG_SCREEN_LAYOUT);
            }
        }
    }

    Task reuseOrCreateTask(ActivityInfo info, Intent intent, boolean toTop) {
        return reuseOrCreateTask(info, intent, null /*voiceSession*/, null /*voiceInteractor*/,
                toTop, null /*activity*/, null /*source*/, null /*options*/);
    }
    // TODO: Can be removed once we change callpoints creating stacks to be creating tasks.
    /** Either returns this current task to be re-used or creates a new child task. */
    Task reuseOrCreateTask(ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession,
            IVoiceInteractor voiceInteractor, boolean toTop, ActivityRecord activity,
            ActivityRecord source, ActivityOptions options) {

        Task task;
        if (DisplayContent.alwaysCreateStack(getWindowingMode(), getActivityType())) {
            // This stack will only contain one task, so just return itself since all stacks ara now
            // tasks and all tasks are now stacks.
            task = reuseAsLeafTask(voiceSession, voiceInteractor, intent, info, activity);
        } else {
            // Create child task since this stack can contain multiple tasks.
            final int taskId = activity != null
                    ? mStackSupervisor.getNextTaskIdForUser(activity.mUserId)
                    : mStackSupervisor.getNextTaskIdForUser();
            task = Task.create(
                    mAtmService, taskId, info, intent, voiceSession, voiceInteractor, this);

            // add the task to stack first, mTaskPositioner might need the stack association
            addChild(task, toTop, (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
        }

        int displayId = getDisplayId();
        if (displayId == INVALID_DISPLAY) displayId = DEFAULT_DISPLAY;
        final boolean isLockscreenShown = mAtmService.mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        if (!mStackSupervisor.getLaunchParamsController()
                .layoutTask(task, info.windowLayout, activity, source, options)
                && !matchParentBounds() && task.isResizeable() && !isLockscreenShown) {
            task.setBounds(getRequestedOverrideBounds());
        }

        return task;
    }

    void addChild(WindowContainer child, final boolean toTop, boolean showForAllUsers) {
        if (isSingleTaskInstance() && hasChild()) {
            throw new IllegalStateException("Can only have one child on stack=" + this);
        }

        Task task = child.asTask();
        try {

            if (task != null) {
                task.setForceShowForAllUsers(showForAllUsers);
            }
            // We only want to move the parents to the parents if we are creating this task at the
            // top of its stack.
            addChild(child, toTop ? MAX_VALUE : 0, toTop /*moveParents*/);
        } finally {
            if (task != null) {
                task.setForceShowForAllUsers(false);
            }
        }
    }

    void positionChildAt(Task task, int position) {
        if (task.getStack() != this) {
            throw new IllegalArgumentException("AS.positionChildAt: task=" + task
                    + " is not a child of stack=" + this + " current parent=" + task.getStack());
        }

        task.updateOverrideConfigurationForStack(this);

        final ActivityRecord topRunningActivity = task.topRunningActivityLocked();
        final boolean wasResumed = topRunningActivity == task.getStack().mResumedActivity;

        boolean toTop = position >= getChildCount();
        boolean includingParents = toTop || getDisplayArea().getNextFocusableStack(this,
                true /* ignoreCurrent */) == null;
        if (WindowManagerDebugConfig.DEBUG_STACK) {
            Slog.i(TAG_WM, "positionChildAt: positioning task=" + task + " at " + position);
        }
        positionChildAt(position, task, includingParents);
        task.updateTaskMovement(toTop);
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();


        // TODO: Investigate if this random code is really needed.
        if (task.voiceSession != null) {
            try {
                task.voiceSession.taskStarted(task.intent, task.mTaskId);
            } catch (RemoteException e) {
            }
        }

        if (wasResumed) {
            if (mResumedActivity != null) {
                Log.wtf(TAG, "mResumedActivity was already set when moving mResumedActivity from"
                        + " other stack to this stack mResumedActivity=" + mResumedActivity
                        + " other mResumedActivity=" + topRunningActivity);
            }
            topRunningActivity.setState(RESUMED, "positionChildAt");
        }

        // The task might have already been running and its visibility needs to be synchronized with
        // the visibility of the stack / windows.
        ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        mRootWindowContainer.resumeFocusedStacksTopActivities();
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        // positionChildAtTop() must be called even when always on top gets turned off because we
        // need to make sure that the stack is moved from among always on top windows to below other
        // always on top windows. Since the position the stack should be inserted into is calculated
        // properly in {@link DisplayContent#getTopInsertPosition()} in both cases, we can just
        // request that the stack is put at top here.
        taskDisplayArea.positionStackAtTop(this, false /* includingParents */);
    }

    /** NOTE: Should only be called from {@link Task#reparent}. */
    void moveToFrontAndResumeStateIfNeeded(ActivityRecord r, boolean moveToFront, boolean setResume,
            boolean setPause, String reason) {
        if (!moveToFront) {
            return;
        }

        final ActivityState origState = r.getState();
        // If the activity owns the last resumed activity, transfer that together,
        // so that we don't resume the same activity again in the new stack.
        // Apps may depend on onResume()/onPause() being called in pairs.
        if (setResume) {
            r.setState(RESUMED, "moveToFrontAndResumeStateIfNeeded");
        }
        // If the activity was previously pausing, then ensure we transfer that as well
        if (setPause) {
            mPausingActivity = r;
            r.schedulePauseTimeout();
        }
        // Move the stack in which we are placing the activity to the front.
        moveToFront(reason);
        // If the original state is resumed, there is no state change to update focused app.
        // So here makes sure the activity focus is set if it is the top.
        if (origState == RESUMED && r == mRootWindowContainer.getTopResumedActivity()) {
            mAtmService.setResumedActivityUncheckLocked(r, reason);
        }
    }

    void dismissPip() {
        if (!isActivityTypeStandardOrUndefined()) {
            throw new IllegalArgumentException(
                    "You can't move tasks from non-standard stacks.");
        }
        if (getWindowingMode() != WINDOWING_MODE_PINNED) {
            throw new IllegalArgumentException(
                    "Can't exit pinned mode if it's not pinned already.");
        }

        mWmService.inSurfaceTransaction(() -> {
            final Task task = getBottomMostTask();
            setWindowingMode(WINDOWING_MODE_UNDEFINED);

            getDisplayArea().positionStackAtTop(this, false /* includingParents */);

            mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, this);
            MetricsLoggerWrapper.logPictureInPictureFullScreen(mAtmService.mContext,
                    task.effectiveUid, task.realActivity.flattenToString());
        });
    }

    void prepareFreezingTaskBounds() {
        forAllLeafTasks(Task::prepareFreezingBounds, true /* traverseTopToBottom */);
    }

    @Override
    public int setBounds(Rect bounds) {
        // Calling Task#setBounds() for leaf task since this is the a specialization of
        // {@link #setBounds(int)} for ActivityStack.
        if (!isRootTask()) {
            return super.setBounds(bounds);
        } else {
            return setBounds(getRequestedOverrideBounds(), bounds);
        }
    }

    private int setBounds(Rect existing, Rect bounds) {
        if (equivalentBounds(existing, bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        final int result = super.setBounds(!inMultiWindowMode() ? null : bounds);

        updateSurfaceBounds();
        return result;
    }

    /** Bounds of the stack without adjusting for other factors in the system like visibility
     * of docked stack.
     * Most callers should be using {@link ConfigurationContainer#getRequestedOverrideBounds} a
     * it takes into consideration other system factors. */
    void getRawBounds(Rect out) {
        out.set(getRawBounds());
    }

    private Rect getRawBounds() {
        return super.getBounds();
    }

    @Override
    public void getBounds(Rect bounds) {
        bounds.set(getBounds());
    }

    /**
     * @return the final bounds for the bounds animation.
     */
    void getFinalAnimationBounds(Rect outBounds) {
        outBounds.set(mBoundsAnimationTarget);
    }

    /**
     * @return the final source bounds for the bounds animation.
     */
    void getFinalAnimationSourceHintBounds(Rect outBounds) {
        outBounds.set(mBoundsAnimationSourceHintBounds);
    }

    /** Bounds of the stack with other system factors taken into consideration. */
    void getDimBounds(Rect out) {
        getBounds(out);
    }

    /**
     * Put a Task in this stack. Used for adding only.
     * When task is added to top of the stack, the entire branch of the hierarchy (including stack
     * and display) will be brought to top.
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
        child.updateTaskMovement(true);

        final DisplayContent displayContent = getDisplayContent();
        displayContent.layoutAndAssignWindowLayersIfNeeded();
    }

    void positionChildAtBottom(Task child) {
        // If there are other focusable stacks on the display, the z-order of the display should not
        // be changed just because a task was placed at the bottom. E.g. if it is moving the topmost
        // task to bottom, the next focusable stack on the same display should be focused.
        final ActivityStack nextFocusableStack = getDisplayArea().getNextFocusableStack(
                child.getStack(), true /* ignoreCurrent */);
        positionChildAtBottom(child, nextFocusableStack == null /* includingParents */);
        child.updateTaskMovement(true);
    }

    @VisibleForTesting
    void positionChildAtBottom(Task child, boolean includingParents) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
            return;
        }

        positionChildAt(POSITION_BOTTOM, child, includingParents);
        getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        if (isOrganized()) {
            mAtmService.mTaskOrganizerController.dispatchTaskInfoChanged(this, false /* force */);
        }

        if (!mChildren.contains(child)) {
            return;
        }

        final boolean isTop = getTopChild() == child;

        final Task task = child.asTask();
        if (task != null) {
            task.updateTaskMovement(isTop);
        }

        if (isTop) {
            final DisplayContent displayContent = getDisplayContent();
            displayContent.layoutAndAssignWindowLayersIfNeeded();
        }
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        final DisplayContent display = newParent != null
                ? ((WindowContainer) newParent).getDisplayContent() : null;
        final DisplayContent oldDisplay = oldParent != null
                ? ((WindowContainer) oldParent).getDisplayContent() : null;
        super.onParentChanged(newParent, oldParent);

        // Resume next focusable stack after reparenting to another display if we aren't removing
        // the prevous display.
        if (oldDisplay != null && oldDisplay.isRemoving()) {
            postReparent();
        }
    }

    void reparent(TaskDisplayArea newParent, boolean onTop) {
        reparent(newParent, onTop ? POSITION_TOP : POSITION_BOTTOM);
    }

    private void updateSurfaceBounds() {
        updateSurfaceSize(getPendingTransaction());
        updateSurfacePosition();
        scheduleAnimation();
    }

    @Override
    void getRelativePosition(Point outPos) {
        super.getRelativePosition(outPos);
        final int outset = getTaskOutset();
        outPos.x -= outset;
        outPos.y -= outset;
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        if (isRootTask()) {
            updateSurfaceBounds();
        }
    }

    boolean shouldIgnoreInput() {
        if (inSplitScreenPrimaryWindowingMode() && !isFocusable()) {
            return true;
        }
        if (mAtmService.mHasLeanbackFeature && inPinnedWindowingMode()
                && !isFocusedStackOnDisplay()) {
            // Preventing Picture-in-Picture stack from receiving input on TVs.
            return true;
        }
        return false;
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.println(prefix + "mStackId=" + getRootTaskId());
        pw.println(prefix + "mDeferRemoval=" + mDeferRemoval);
        pw.println(prefix + "mBounds=" + getRawBounds().toShortString());
        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            mChildren.get(taskNdx).dump(pw, prefix + "  ", dumpAll);
        }
        if (!mExitingActivities.isEmpty()) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i = mExitingActivities.size() - 1; i >= 0; i--) {
                WindowToken token = mExitingActivities.get(i);
                pw.print("  Exiting App #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            }
        }
        mAnimatingActivityRegistry.dump(pw, "AnimatingApps:", prefix);
    }

    /**
     * Sets the current picture-in-picture aspect ratio.
     */
    void setPictureInPictureAspectRatio(float aspectRatio) {
        if (!mWmService.mAtmService.mSupportsPictureInPicture) {
            return;
        }

        final DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return;
        }

        if (!inPinnedWindowingMode()) {
            return;
        }

        final PinnedStackController pinnedStackController =
                getDisplayContent().getPinnedStackController();

        if (Float.compare(aspectRatio, pinnedStackController.getAspectRatio()) == 0) {
            return;
        }

        // Notify the pinned stack controller about aspect ratio change.
        // This would result a callback delivered from SystemUI to WM to start animation,
        // if the bounds are ought to be altered due to aspect ratio change.
        pinnedStackController.setAspectRatio(
                pinnedStackController.isValidPictureInPictureAspectRatio(aspectRatio)
                        ? aspectRatio : -1f);
    }

    /**
     * Sets the current picture-in-picture actions.
     */
    void setPictureInPictureActions(List<RemoteAction> actions) {
        if (!mWmService.mAtmService.mSupportsPictureInPicture) {
            return;
        }

        if (!inPinnedWindowingMode()) {
            return;
        }

        getDisplayContent().getPinnedStackController().setActions(actions);
    }

    public boolean isForceScaled() {
        return mBoundsAnimating;
    }

    /** Returns true if a removal action is still being deferred. */
    boolean checkCompleteDeferredRemoval() {
        if (isAnimating(TRANSITION | CHILDREN)) {
            return true;
        }
        if (mDeferRemoval) {
            removeImmediately();
        }

        return super.checkCompleteDeferredRemoval();
    }

    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    AnimatingActivityRegistry getAnimatingActivityRegistry() {
        return mAnimatingActivityRegistry;
    }

    void executeAppTransition(ActivityOptions options) {
        getDisplay().mDisplayContent.executeAppTransition();
        ActivityOptions.abort(options);
    }

    boolean shouldSleepActivities() {
        final DisplayContent display = getDisplay();

        // Do not sleep activities in this stack if we're marked as focused and the keyguard
        // is in the process of going away.
        if (isFocusedStackOnDisplay()
                && mStackSupervisor.getKeyguardController().isKeyguardGoingAway()) {
            return false;
        }

        return display != null ? display.isSleeping() : mAtmService.isSleepingLocked();
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mAtmService.mShuttingDown;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);

        proto.write(TaskProto.ID, mTaskId);
        proto.write(DISPLAY_ID, getDisplayId());
        proto.write(ROOT_TASK_ID, getRootTaskId());

        if (mResumedActivity != null) {
            mResumedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
        }
        if (realActivity != null) {
            proto.write(REAL_ACTIVITY, realActivity.flattenToShortString());
        }
        if (origActivity != null) {
            proto.write(ORIG_ACTIVITY, origActivity.flattenToShortString());
        }
        proto.write(ACTIVITY_TYPE, getActivityType());
        proto.write(RESIZE_MODE, mResizeMode);
        proto.write(MIN_WIDTH, mMinWidth);
        proto.write(MIN_HEIGHT, mMinHeight);

        proto.write(FILLS_PARENT, matchParentBounds());
        getRawBounds().dumpDebug(proto, BOUNDS);

        if (mLastNonFullscreenBounds != null) {
            mLastNonFullscreenBounds.dumpDebug(proto, LAST_NON_FULLSCREEN_BOUNDS);
        }

        proto.write(DEFER_REMOVAL, mDeferRemoval);
        proto.write(ANIMATING_BOUNDS, mBoundsAnimating);

        if (mSurfaceControl != null) {
            proto.write(SURFACE_WIDTH, mSurfaceControl.getWidth());
            proto.write(SURFACE_HEIGHT, mSurfaceControl.getHeight());
        }

        proto.write(CREATED_BY_ORGANIZER, mCreatedByOrganizer);

        proto.end(token);
    }
}
