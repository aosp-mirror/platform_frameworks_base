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
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.PINNED_WINDOWING_MODE_ELEVATION_IN_DIP;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
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

import static com.android.server.am.ActivityStackProto.DISPLAY_ID;
import static com.android.server.am.ActivityStackProto.FULLSCREEN;
import static com.android.server.am.ActivityStackProto.RESUMED_ACTIVITY;
import static com.android.server.am.ActivityStackProto.STACK;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.ActivityState.STARTED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPED;
import static com.android.server.wm.ActivityStack.ActivityState.STOPPING;
import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityStackSupervisor.dumpHistoryList;
import static com.android.server.wm.ActivityStackSupervisor.printThisActivity;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STACK;
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
import static com.android.server.wm.BoundsAnimationController.FADE_IN;
import static com.android.server.wm.BoundsAnimationController.NO_PIP_MODE_CHANGED_CALLBACKS;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_END;
import static com.android.server.wm.BoundsAnimationController.SCHEDULE_PIP_MODE_CHANGED_ON_START;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.StackProto.ADJUSTED_BOUNDS;
import static com.android.server.wm.StackProto.ADJUSTED_FOR_IME;
import static com.android.server.wm.StackProto.ADJUST_DIVIDER_AMOUNT;
import static com.android.server.wm.StackProto.ADJUST_IME_AMOUNT;
import static com.android.server.wm.StackProto.ANIMATING_BOUNDS;
import static com.android.server.wm.StackProto.DEFER_REMOVAL;
import static com.android.server.wm.StackProto.FILLS_PARENT;
import static com.android.server.wm.StackProto.MINIMIZE_AMOUNT;
import static com.android.server.wm.StackProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.IntDef;
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
import android.service.voice.IVoiceInteractionSession;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.am.AppTimeTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * State and management of a single stack of activities.
 */
class ActivityStack extends WindowContainer<WindowContainer> implements BoundsAnimationTarget {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStack" : TAG_ATM;
    static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_APP = TAG + POSTFIX_APP;
    private static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
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

    // Ticks during which we check progress while waiting for an app to launch.
    private static final int LAUNCH_TICK = 500;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    private static final int PAUSE_TIMEOUT = 500;

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

    /** Minimum size of an adjusted stack bounds relative to original stack bounds. Used to
     * restrict IME adjustment so that a min portion of top stack remains visible.*/
    private static final float ADJUSTED_STACK_FRACTION_MIN = 0.3f;

    /** Dimming amount for non-focused stack when stacks are IME-adjusted. */
    private static final float IME_ADJUST_DIM_AMOUNT = 0.25f;

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

    final ActivityTaskManagerService mService;
    final WindowManagerService mWindowManager;

    /**
     * List of running activities, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains HistoryRecord objects.
     */
    private final ArrayList<ActivityRecord> mLruActivities = new ArrayList<>();

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     */
    ActivityRecord mPausingActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    ActivityRecord mLastPausedActivity = null;

    /**
     * Activities that specify No History must be removed once the user navigates away from them.
     * If the device goes to sleep with such an activity in the paused state then we save it here
     * and finish it later if another activity replaces it on wakeup.
     */
    ActivityRecord mLastNoHistoryActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     */
    ActivityRecord mResumedActivity = null;

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
     * When set, will force the stack to report as invisible.
     */
    boolean mForceHidden = false;

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

    /** The attached Display's unique identifier, or -1 if detached */
    int mDisplayId;
    // Id of the previous display the stack was on.
    int mPrevDisplayId = INVALID_DISPLAY;

    /** Unique identifier */
    final int mStackId;

    /** For comparison with DisplayContent bounds. */
    private Rect mTmpRect = new Rect();
    private Rect mTmpRect2 = new Rect();
    private Rect mTmpRect3 = new Rect();

    /** For Pinned stack controlling. */
    private Rect mTmpToBounds = new Rect();

    /** Stack bounds adjusted to screen content area (taking into account IM windows, etc.) */
    private final Rect mAdjustedBounds = new Rect();

    /**
     * Fully adjusted IME bounds. These are different from {@link #mAdjustedBounds} because they
     * represent the state when the animation has ended.
     */
    private final Rect mFullyAdjustedImeBounds = new Rect();

    /** ActivityRecords that are exiting, but still on screen for animations. */
    final ArrayList<ActivityRecord> mExitingActivities = new ArrayList<>();

    /** Detach this stack from its display when animation completes. */
    // TODO: maybe tie this to WindowContainer#removeChild some how...
    private boolean mDeferRemoval;

    private final Rect mTmpAdjustedBounds = new Rect();
    private boolean mAdjustedForIme;
    private boolean mImeGoingAway;
    private WindowState mImeWin;
    private float mMinimizeAmount;
    private float mAdjustImeAmount;
    private float mAdjustDividerAmount;
    private final int mDockedStackMinimizeThickness;

    // If this is true, we are in the bounds animating mode. The task will be down or upscaled to
    // perfectly fit the region it would have been cropped to. We may also avoid certain logic we
    // would otherwise apply while resizing, while resizing in the bounds animating mode.
    private boolean mBoundsAnimating = false;
    // Set when an animation has been requested but has not yet started from the UI thread. This is
    // cleared when the animation actually starts.
    private boolean mBoundsAnimatingRequested = false;
    private boolean mBoundsAnimatingToFullscreen = false;
    private boolean mCancelCurrentBoundsAnimation = false;
    private Rect mBoundsAnimationTarget = new Rect();
    private Rect mBoundsAnimationSourceHintBounds = new Rect();
    private @BoundsAnimationController.AnimationType int mAnimationType;

    Rect mPreAnimationBounds = new Rect();

    private Dimmer mDimmer = new Dimmer(this);

    /**
     * For {@link #prepareSurfaces}.
     */
    private final Rect mTmpDimBoundsRect = new Rect();
    private final Point mLastSurfaceSize = new Point();

    private final AnimatingActivityRegistry mAnimatingActivityRegistry =
            new AnimatingActivityRegistry();

    /** Stores the override windowing-mode from before a transient mode change (eg. split) */
    private int mRestoreOverrideWindowingMode = WINDOWING_MODE_UNDEFINED;

    /** List for processing through a set of activities */
    private final ArrayList<ActivityRecord> mTmpActivities = new ArrayList<>();

    /** Run all ActivityStacks through this */
    protected final ActivityStackSupervisor mStackSupervisor;
    protected final RootActivityContainer mRootActivityContainer;

    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;

    private static final int PAUSE_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 1;
    private static final int DESTROY_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 2;
    private static final int LAUNCH_TICK_MSG = FIRST_ACTIVITY_STACK_MSG + 3;
    private static final int STOP_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 4;
    private static final int DESTROY_ACTIVITIES_MSG = FIRST_ACTIVITY_STACK_MSG + 5;
    private static final int TRANSLUCENT_TIMEOUT_MSG = FIRST_ACTIVITY_STACK_MSG + 6;

    private static class ScheduleDestroyArgs {
        final WindowProcessController mOwner;
        final String mReason;
        ScheduleDestroyArgs(WindowProcessController owner, String reason) {
            mOwner = owner;
            mReason = reason;
        }
    }

    private final Handler mHandler;

    private class ActivityStackHandler extends Handler {

        ActivityStackHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PAUSE_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity pause timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        if (r.hasProcess()) {
                            mService.logAppTooSlow(r.app, r.pauseTime, "pausing " + r);
                        }
                        activityPausedLocked(r.appToken, true);
                    }
                } break;
                case LAUNCH_TICK_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    synchronized (mService.mGlobalLock) {
                        if (r.continueLaunchTickingLocked()) {
                            mService.logAppTooSlow(r.app, r.launchTickTime, "launching " + r);
                        }
                    }
                } break;
                case DESTROY_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity destroy timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        if (r != null) {
                            r.destroyed("destroyTimeout");
                        }
                    }
                } break;
                case STOP_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    Slog.w(TAG, "Activity stop timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        if (r.isInHistory()) {
                            r.activityStoppedLocked(null /* icicle */,
                                    null /* persistentState */, null /* description */);
                        }
                    }
                } break;
                case DESTROY_ACTIVITIES_MSG: {
                    ScheduleDestroyArgs args = (ScheduleDestroyArgs)msg.obj;
                    synchronized (mService.mGlobalLock) {
                        destroyActivities(args.mOwner, args.mReason);
                    }
                } break;
                case TRANSLUCENT_TIMEOUT_MSG: {
                    synchronized (mService.mGlobalLock) {
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
            forAllActivities(f, start.getTask(), true /*includeBoundary*/,
                    true /*traverseTopToBottom*/);
            f.recycle();

            if (mUpdateConfig) {
                // Ensure the resumed state of the focus activity if we updated the configuration of
                // any activity.
                mRootActivityContainer.resumeFocusedStacksTopActivities();
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

    ActivityStack(DisplayContent display, int stackId, ActivityStackSupervisor supervisor,
            int windowingMode, int activityType, boolean onTop) {
        super(supervisor.mService.mWindowManager);
        mStackId = stackId;
        mDockedStackMinimizeThickness =
                supervisor.mService.mWindowManager.mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.docked_stack_minimize_thickness);
        EventLogTags.writeWmStackCreated(stackId);
        mStackSupervisor = supervisor;
        mService = supervisor.mService;
        mRootActivityContainer = mService.mRootActivityContainer;
        mHandler = new ActivityStackHandler(supervisor.mLooper);
        mWindowManager = mService.mWindowManager;
        mRemoteToken = new RemoteToken(this);
        mCurrentUser = mService.mAmInternal.getCurrentUserId();
        // Set display id before setting activity and window type to make sure it won't affect
        // stacks on a wrong display.
        mDisplayId = display.mDisplayId;
        setActivityType(activityType);
        display.addStack(this, onTop ? POSITION_TOP : POSITION_BOTTOM);
        setWindowingMode(windowingMode, false /* animate */, false /* showRecents */,
                false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */,
                true /* creating */);
    }

    /**
     * This should be called when an activity in a child task changes state. This should only
     * be called from
     * {@link Task#onActivityStateChanged(ActivityRecord, ActivityState, String)}.
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, ActivityState state, String reason) {
        if (record == mResumedActivity && state != RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }

        if (state == RESUMED) {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "set resumed activity to:" + record + " reason:"
                    + reason);
            setResumedActivity(record, reason + " - onActivityStateChanged");
            if (record == mRootActivityContainer.getTopResumedActivity()) {
                mService.setResumedActivityUncheckLocked(record, reason);
            }
            mStackSupervisor.mRecentTasks.add(record.getTask());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        final int prevWindowingMode = getWindowingMode();
        final boolean prevIsAlwaysOnTop = isAlwaysOnTop();
        final int prevRotation = getWindowConfiguration().getRotation();
        final int prevDensity = getConfiguration().densityDpi;
        final int prevScreenW = getConfiguration().screenWidthDp;
        final int prevScreenH = getConfiguration().screenHeightDp;
        final Rect newBounds = mTmpRect;
        // Initialize the new bounds by previous bounds as the input and output for calculating
        // override bounds in pinned (pip) or split-screen mode.
        getBounds(newBounds);

        super.onConfigurationChanged(newParentConfig);

        // Only need to update surface size here since the super method will handle updating
        // surface position.
        updateSurfaceSize(getPendingTransaction());

        if (mDisplayContent == null) {
            return;
        }

        if (prevWindowingMode != getWindowingMode()) {
            mDisplayContent.onStackWindowingModeChanged(this);

            if (inSplitScreenSecondaryWindowingMode()) {
                // When the stack is resized due to entering split screen secondary, offset the
                // windows to compensate for the new stack position.
                forAllWindows(w -> {
                    w.mWinAnimator.setOffsetPositionForStackResize(true);
                }, true);
            }
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

            // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
            if (overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    || overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                // If entering split screen or if something about the available split area changes,
                // recalculate the split windows to match the new configuration.
                if (rotationChanged || windowingModeChanged
                        || prevDensity != getConfiguration().densityDpi
                        || prevScreenW != getConfiguration().screenWidthDp
                        || prevScreenH != getConfiguration().screenHeightDp) {
                    calculateDockedBoundsForConfigChange(newParentConfig, newBounds);
                    hasNewOverrideBounds = true;
                }
            }
        }

        if (windowingModeChanged) {
            // Use override windowing mode to prevent extra bounds changes if inheriting the mode.
            if (overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                getStackDockedModeBounds(null /* dockedBounds */, null /* currentTempTaskBounds */,
                        newBounds /* outStackBounds */, mTmpRect2 /* outTempTaskBounds */);
                // immediately resize so docked bounds are available in onSplitScreenModeActivated
                setTaskDisplayedBounds(null);
                setTaskBounds(newBounds);
                setBounds(newBounds);
                newBounds.set(newBounds);
            } else if (overrideWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                Rect dockedBounds = display.getSplitScreenPrimaryStack().getBounds();
                final boolean isMinimizedDock =
                        display.mDisplayContent.getDockedDividerController().isMinimizedDock();
                if (isMinimizedDock) {
                    Task topTask = display.getSplitScreenPrimaryStack().getTopMostTask();
                    if (topTask != null) {
                        dockedBounds = topTask.getBounds();
                    }
                }
                getStackDockedModeBounds(dockedBounds, null /* currentTempTaskBounds */,
                        newBounds /* outStackBounds */, mTmpRect2 /* outTempTaskBounds */);
                hasNewOverrideBounds = true;
            }
        }
        if (hasNewOverrideBounds) {
            if (inSplitScreenPrimaryWindowingMode()) {
                mStackSupervisor.resizeDockedStackLocked(new Rect(newBounds),
                        null /* tempTaskBounds */, null /* tempTaskInsetBounds */,
                        null /* tempOtherTaskBounds */, null /* tempOtherTaskInsetBounds */,
                        PRESERVE_WINDOWS, true /* deferResume */);
            } else if (overrideWindowingMode != WINDOWING_MODE_PINNED) {
                // For pinned stack, resize is now part of the {@link WindowContainerTransaction}
                resize(new Rect(newBounds), null /* tempTaskBounds */,
                        null /* tempTaskInsetBounds */, PRESERVE_WINDOWS, true /* deferResume */);
            }
        }
        if (prevIsAlwaysOnTop != isAlwaysOnTop()) {
            // Since always on top is only on when the stack is freeform or pinned, the state
            // can be toggled when the windowing mode changes. We must make sure the stack is
            // placed properly when always on top state changes.
            display.positionStackAtTop(this, false /* includingParents */);
        }
    }

    @Override
    public void setWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode, false /* animate */, false /* showRecents */,
                false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */,
                false /* creating */);
    }

    /**
     * A transient windowing mode is one which activities enter into temporarily. Examples of this
     * are Split window modes and pip. Non-transient modes are modes that displays can adopt.
     *
     * @param windowingMode the windowingMode to test for transient-ness.
     * @return {@code true} if the windowing mode is transient, {@code false} otherwise.
     */
    private static boolean isTransientWindowingMode(int windowingMode) {
        // TODO(b/114842032): add PIP if/when it uses mode transitions instead of task reparenting
        return windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
    }

    /**
     * Specialization of {@link #setWindowingMode(int)} for this subclass.
     *
     * @param preferredWindowingMode the preferred windowing mode. This may not be honored depending
     *         on the state of things. For example, WINDOWING_MODE_UNDEFINED will resolve to the
     *         previous non-transient mode if this stack is currently in a transient mode.
     * @param animate Can be used to prevent animation.
     * @param showRecents Controls whether recents is shown on the other side of a split while
     *         entering split mode.
     * @param enteringSplitScreenMode {@code true} if entering split mode.
     * @param deferEnsuringVisibility Whether visibility updates are deferred. This is set when
     *         many operations (which can effect visibility) are being performed in bulk.
     * @param creating {@code true} if this is being run during ActivityStack construction.
     */
    void setWindowingMode(int preferredWindowingMode, boolean animate, boolean showRecents,
            boolean enteringSplitScreenMode, boolean deferEnsuringVisibility, boolean creating) {
        mWindowManager.inSurfaceTransaction(() -> setWindowingModeInSurfaceTransaction(
                preferredWindowingMode, animate, showRecents, enteringSplitScreenMode,
                deferEnsuringVisibility, creating));
    }

    private void setWindowingModeInSurfaceTransaction(int preferredWindowingMode, boolean animate,
            boolean showRecents, boolean enteringSplitScreenMode, boolean deferEnsuringVisibility,
            boolean creating) {
        final int currentMode = getWindowingMode();
        final int currentOverrideMode = getRequestedOverrideWindowingMode();
        final DisplayContent display = getDisplay();
        final Task topTask = getTopMostTask();
        final ActivityStack splitScreenStack = display.getSplitScreenPrimaryStack();
        int windowingMode = preferredWindowingMode;
        if (preferredWindowingMode == WINDOWING_MODE_UNDEFINED
                && isTransientWindowingMode(currentMode)) {
            // Leaving a transient mode. Interpret UNDEFINED as "restore"
            windowingMode = mRestoreOverrideWindowingMode;
        }

        // Need to make sure windowing mode is supported. If we in the process of creating the stack
        // no need to resolve the windowing mode again as it is already resolved to the right mode.
        if (!creating) {
            windowingMode = display.validateWindowingMode(windowingMode,
                    null /* ActivityRecord */, topTask, getActivityType());
        }
        if (splitScreenStack == this
                && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            // Resolution to split-screen secondary for the primary split-screen stack means
            // we want to leave split-screen mode.
            windowingMode = mRestoreOverrideWindowingMode;
        }

        final boolean alreadyInSplitScreenMode = display.hasSplitScreenPrimaryStack();

        // Don't send non-resizeable notifications if the windowing mode changed was a side effect
        // of us entering split-screen mode.
        final boolean sendNonResizeableNotification = !enteringSplitScreenMode;
        // Take any required action due to us not supporting the preferred windowing mode.
        if (alreadyInSplitScreenMode && windowingMode == WINDOWING_MODE_FULLSCREEN
                && sendNonResizeableNotification && isActivityTypeStandardOrUndefined()) {
            final boolean preferredSplitScreen =
                    preferredWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    || preferredWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (preferredSplitScreen || creating) {
                // Looks like we can't launch in split screen mode or the stack we are launching
                // doesn't support split-screen mode, go ahead an dismiss split-screen and display a
                // warning toast about it.
                mService.getTaskChangeNotificationController().notifyActivityDismissingDockedStack();
                final ActivityStack primarySplitStack = display.getSplitScreenPrimaryStack();
                primarySplitStack.setWindowingModeInSurfaceTransaction(WINDOWING_MODE_UNDEFINED,
                        false /* animate */, false /* showRecents */,
                        false /* enteringSplitScreenMode */, true /* deferEnsuringVisibility */,
                        primarySplitStack == this ? creating : false);
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
        if (sendNonResizeableNotification && likelyResolvedMode != WINDOWING_MODE_FULLSCREEN
                && topActivity != null && !topActivity.noDisplay
                && topActivity.isNonResizableOrForcedResizable(likelyResolvedMode)) {
            // Inform the user that they are starting an app that may not work correctly in
            // multi-window mode.
            final String packageName = topActivity.info.applicationInfo.packageName;
            mService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                    topTask.mTaskId, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN, packageName);
        }

        mService.deferWindowLayout();
        try {
            if (!animate && topActivity != null) {
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

            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && splitScreenStack != null) {
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
                resize(mTmpRect2, null /* tempTaskBounds */, null /* tempTaskInsetBounds */,
                        false /* preserveWindows */, true /* deferResume */);
            }
        } finally {
            if (showRecents && !alreadyInSplitScreenMode && mDisplayId == DEFAULT_DISPLAY
                    && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                // Make sure recents stack exist when creating a dock stack as it normally needs to
                // be on the other side of the docked stack and we make visibility decisions based
                // on that.
                // TODO: This is only here to help out with the case where recents stack doesn't
                // exist yet. For that case the initial size of the split-screen stack will be the
                // the one where the home stack is visible since recents isn't visible yet, but the
                // divider will be off. I think we should just make the initial bounds that of home
                // so that the divider matches and remove this logic.
                // TODO: This is currently only called when entering split-screen while in another
                // task, and from the tests
                // TODO (b/78247419): Fix the rotation animation from fullscreen to minimized mode
                final boolean isRecentsComponentHome =
                        mService.getRecentTasks().isRecentsComponentHomeActivity(mCurrentUser);
                final ActivityStack recentStack = display.getOrCreateStack(
                        WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                        isRecentsComponentHome ? ACTIVITY_TYPE_HOME : ACTIVITY_TYPE_RECENTS,
                        true /* onTop */);
                recentStack.moveToFront("setWindowingMode");
                // If task moved to docked stack - show recents if needed.
                mService.mWindowManager.showRecentApps();
            }
            mService.continueWindowLayout();
        }

        if (!deferEnsuringVisibility) {
            mRootActivityContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
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
        adjustFocusToNextFocusableStack("reparent", true /* allowFocusSelf */);
        mRootActivityContainer.resumeFocusedStacksTopActivities();
        // Update visibility of activities before notifying WM. This way it won't try to resize
        // windows that are no longer visible.
        mRootActivityContainer.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
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
            if (mUpdateDisplayedBoundsDeferredCalled) {
                setTaskDisplayedBounds(mDeferredDisplayedBounds);
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

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* focusableOnly */);
    }

    ActivityRecord topRunningActivity(boolean focusableOnly) {
        // Split into 2 to avoid object creation due to variable capture.
        if (focusableOnly) {
            return getActivity((r) -> r.canBeTopRunning() && r.isFocusable());
        } else {
            return getActivity(ActivityRecord::canBeTopRunning);
        }
    }

    private ActivityRecord topRunningNonOverlayTaskActivity() {
        return getActivity((r) -> (r.canBeTopRunning() && !r.mTaskOverlay));
    }

    ActivityRecord topRunningNonDelayedActivityLocked(ActivityRecord notTop) {
        final PooledPredicate p = PooledLambda.obtainPredicate(ActivityStack::isTopRunningNonDelayed
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
     * @return Returns the HistoryRecord of the next activity on the stack.
     */
    ActivityRecord topRunningActivity(IBinder token, int taskId) {
        final PooledPredicate p = PooledLambda.obtainPredicate(ActivityStack::isTopRunning,
                PooledLambda.__(ActivityRecord.class), taskId, token);
        final ActivityRecord r = getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isTopRunning(ActivityRecord r, int taskId, IBinder notTop) {
        return r.getTask().mTaskId != taskId && r.appToken != notTop && r.canBeTopRunning();
    }

    ActivityRecord getTopNonFinishingActivity() {
        return getTopActivity(false /*includeFinishing*/, true /*includeOverlays*/);
    }

    ActivityRecord isInStackLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return isInStackLocked(r);
    }

    ActivityRecord isInStackLocked(ActivityRecord r) {
        if (r == null) {
            return null;
        }
        final Task task = r.getTask();
        final ActivityStack stack = r.getActivityStack();
        if (stack != null && task.mChildren.contains(r) && mChildren.contains(task)) {
            if (stack != this) Slog.w(TAG,
                    "Illegal state! task does not point to stack it is in.");
            return r;
        }
        return null;
    }

    /** @return true if the stack can only contain one task */
    boolean isSingleTaskInstance() {
        final DisplayContent display = getDisplay();
        return display != null && display.isSingleTaskInstance();
    }

    /** @return {@code true} if LRU list contained the specified activity. */
    final boolean inLruList(ActivityRecord activity) {
        return mLruActivities.contains(activity);
    }

    /** @return {@code true} if the given activity was contained in LRU list. */
    final boolean updateLruList(ActivityRecord r) {
        final boolean contained = mLruActivities.remove(r);
        mLruActivities.add(r);
        return contained;
    }

    final boolean isHomeOrRecentsStack() {
        return isActivityTypeHome() || isActivityTypeRecents();
    }

    final boolean isOnHomeDisplay() {
        return mDisplayId == DEFAULT_DISPLAY;
    }

    private boolean returnsToHomeStack() {
        return !inMultiWindowMode() && hasChild() && getBottomMostTask().returnsToHomeStack();
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

        final DisplayContent display = getDisplay();

        if (inSplitScreenSecondaryWindowingMode()) {
            // If the stack is in split-screen seconardy mode, we need to make sure we move the
            // primary split-screen stack forward in the case it is currently behind a fullscreen
            // stack so both halves of the split-screen appear on-top and the fullscreen stack isn't
            // cutting between them.
            // TODO(b/70677280): This is a workaround until we can fix as part of b/70677280.
            final ActivityStack topFullScreenStack =
                    display.getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (topFullScreenStack != null) {
                final ActivityStack primarySplitScreenStack = display.getSplitScreenPrimaryStack();
                if (display.getIndexOf(topFullScreenStack)
                        > display.getIndexOf(primarySplitScreenStack)) {
                    primarySplitScreenStack.moveToFront(reason + " splitScreenToTop");
                }
            }
        }

        if (!isActivityTypeHome() && returnsToHomeStack()) {
            // Make sure the home stack is behind this stack since that is where we should return to
            // when this stack is no longer visible.
            display.moveHomeStackToFront(reason + " returnToHome");
        }

        final boolean movingTask = task != null;
        display.positionStackAtTop(this, !movingTask /* includingParents */, reason);
        if (movingTask) {
            // This also moves the entire hierarchy branch to top, including parents
            positionChildAtTop(task);
        }
    }

    /**
     * @param reason The reason for moving the stack to the back.
     * @param task If non-null, the task will be moved to the bottom of the stack.
     **/
    void moveToBack(String reason, Task task) {
        if (!isAttached()) {
            return;
        }

        getDisplay().positionStackAtBottom(this, reason);
        if (task != null) {
            positionChildAtBottom(task);
        }

        /**
         * The intent behind moving a primary split screen stack to the back is usually to hide
         * behind the home stack. Exit split screen in this case.
         */
        if (getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            setWindowingMode(WINDOWING_MODE_UNDEFINED);
        }
    }

    boolean isFocusable() {
        final ActivityRecord r = topRunningActivity();
        return mRootActivityContainer.isFocusable(this, r != null && r.isFocusable());
    }

    boolean isFocusableAndVisible() {
        return isFocusable() && shouldBeVisible(null /* starting */);
    }

    @Override
    public boolean isAttached() {
        final DisplayContent display = getDisplay();
        return display != null && !display.isRemoved();
    }

    // TODO: Should each user have there own stacks?
    @Override
    void switchUser(int userId) {
        if (mCurrentUser == userId) {
            return;
        }
        mCurrentUser = userId;

        super.switchUser(userId);
        forAllTasks((t) -> {
            if (t.mWmService.isCurrentProfileLocked(t.mUserId) || t.showForAllUsers()) {
                mChildren.remove(t);
                mChildren.add(t);
            }
        });
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
            mStackSupervisor.removeTimeoutsForActivityLocked(r);
            mStackSupervisor.scheduleIdleTimeoutLocked(r);
        }
    }

    void awakeFromSleepingLocked() {
        // Ensure activities are no longer sleeping.
        forAllActivities((Consumer<ActivityRecord>) (r) -> r.setSleeping(false));
        if (mPausingActivity != null) {
            Slog.d(TAG, "awakeFromSleepingLocked: previously pausing activity didn't pause");
            activityPausedLocked(mPausingActivity.appToken, true);
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

                mStackSupervisor.scheduleIdleLocked();
                shouldSleep = false;
            }

            if (containsActivityFromStack(mStackSupervisor.mGoingToSleepActivities)) {
                // Still need to tell some activities to sleep; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to sleep "
                        + mStackSupervisor.mGoingToSleepActivities.size() + " activities");
                shouldSleep = false;
            }
        }

        if (shouldSleep) {
            goToSleep();
        }

        return shouldSleep;
    }

    void goToSleep() {
        // Ensure visibility without updating configuration, as activities are about to sleep.
        ensureActivitiesVisible(null /* starting */, 0 /* configChanges */, !PRESERVE_WINDOWS);

        // Make sure any paused or stopped but visible activities are now sleeping.
        // This ensures that the activity's onStop() is called.
        forAllActivities((r) -> {
            if (r.isState(STARTED, STOPPING, STOPPED, PAUSED, PAUSING)) {
                r.setSleeping(true);
            }
        });
    }

    private boolean containsActivityFromStack(List<ActivityRecord> rs) {
        for (ActivityRecord r : rs) {
            if (r.getActivityStack() == this) {
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
                mRootActivityContainer.resumeFocusedStacksTopActivities();
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
        mLastNoHistoryActivity = (prev.intent.getFlags() & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (prev.info.flags & ActivityInfo.FLAG_NO_HISTORY) != 0 ? prev : null;
        prev.setState(PAUSING, "startPausingLocked");
        prev.getTask().touchActiveTime();
        clearLaunchTime(prev);

        mService.updateCpuStats();

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

                mService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
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
        if (!uiSleeping && !mService.isSleepingOrShuttingDownLocked()) {
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
                schedulePauseTimeoutForActivity(prev);
                return true;
            }

        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Activity not running, resuming next.");
            if (resuming == null) {
                mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
            return false;
        }
    }

    final void activityPausedLocked(IBinder token, boolean timeout) {
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE,
            "Activity paused: token=" + token + ", timeout=" + timeout);

        final ActivityRecord r = isInStackLocked(token);

        if (r != null) {
            mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
            if (mPausingActivity == r) {
                if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to PAUSED: " + r
                        + (timeout ? " (due to timeout)" : " (pause complete)"));
                mService.deferWindowLayout();
                try {
                    completePauseLocked(true /* resumeNext */, null /* resumingActivity */);
                } finally {
                    mService.continueWindowLayout();
                }
                return;
            } else {
                EventLogTags.writeWmFailedToPause(r.mUserId, System.identityHashCode(r),
                        r.shortComponentName, mPausingActivity != null
                                ? mPausingActivity.shortComponentName : "(none)");
                if (r.isState(PAUSING)) {
                    r.setState(PAUSED, "activityPausedLocked");
                    if (r.finishing) {
                        if (DEBUG_PAUSE) Slog.v(TAG,
                                "Executing finish of failed to pause activity: " + r);
                        r.completeFinishing("activityPausedLocked");
                    }
                }
            }
        }
        mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
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
            final ActivityStack topStack = mRootActivityContainer.getTopDisplayFocusedStack();
            if (!topStack.shouldSleepOrShutDownActivities()) {
                mRootActivityContainer.resumeFocusedStacksTopActivities(topStack, prev, null);
            } else {
                checkReadyForSleep();
                ActivityRecord top = topStack.topRunningActivity();
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the stack is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mRootActivityContainer.resumeFocusedStacksTopActivities();
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
                            mService.mAmInternal, prev.info.packageName,
                            prev.info.applicationInfo.uid,
                            diff);
                    mService.mH.post(r);
                }
            }
            prev.cpuTimeAtResume = 0; // reset it
        }

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active pinned stack - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mStackSupervisor.mAppVisibilitiesChangedSinceLastPause
                || (getDisplay() != null && getDisplay().hasPinnedStack())) {
            mService.getTaskChangeNotificationController().notifyTaskStackChanged();
            mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }

        mRootActivityContainer.ensureActivitiesVisible(resuming, 0, !PRESERVE_WINDOWS);
    }

    /**
     * Returns true if the stack is translucent and can have other contents visible behind it if
     * needed. A stack is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     */
    @VisibleForTesting
    boolean isStackTranslucent(ActivityRecord starting) {
        if (!isAttached() || mForceHidden) {
            return true;
        }
        final PooledPredicate p = PooledLambda.obtainPredicate(ActivityStack::isOpaqueActivity,
                PooledLambda.__(ActivityRecord.class), starting);
        final ActivityRecord opaque = getActivity(p);
        p.recycle();
        return opaque == null;
    }

    private static boolean isOpaqueActivity(ActivityRecord r, ActivityRecord starting) {
        if (r.finishing) {
            // We don't factor in finishing activities when determining translucency since
            // they will be gone soon.
            return false;
        }

        if (!r.visibleIgnoringKeyguard && r != starting) {
            // Also ignore invisible activities that are not the currently starting
            // activity (about to be visible).
            return false;
        }

        if (r.occludesParent() || r.hasWallpaper) {
            // Stack isn't translucent if it has at least one fullscreen activity
            // that is visible.
            return true;
        }
        return false;
    }

    boolean isTopStackOnDisplay() {
        final DisplayContent display = getDisplay();
        return display != null && display.isTopStack(this);
    }

    /**
     * @return {@code true} if this is the focused stack on its current display, {@code false}
     * otherwise.
     */
    boolean isFocusedStackOnDisplay() {
        final DisplayContent display = getDisplay();
        return display != null && this == display.getFocusedStack();
    }

    boolean isTopActivityVisible() {
        final ActivityRecord topActivity = getTopNonFinishingActivity();
        return topActivity != null && topActivity.mVisibleRequested;
    }

    /**
     * Returns true if the stack should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != STACK_VISIBILITY_INVISIBLE;
    }

    /**
     * Returns true if the stack should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    @StackVisibility
    int getVisibility(ActivityRecord starting) {
        if (!isAttached() || mForceHidden) {
            return STACK_VISIBILITY_INVISIBLE;
        }

        final DisplayContent display = getDisplay();
        boolean gotSplitScreenStack = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentSplitScreenPrimary = false;
        boolean gotTranslucentSplitScreenSecondary = false;
        boolean shouldBeVisible = true;
        final int windowingMode = getWindowingMode();
        final boolean isAssistantType = isActivityTypeAssistant();
        for (int i = display.getStackCount() - 1; i >= 0; --i) {
            final ActivityStack other = display.getStackAt(i);
            final boolean hasRunningActivities = other.topRunningActivity() != null;
            if (other == this) {
                // Should be visible if there is no other stack occluding it, unless it doesn't
                // have any running activities, not starting one and not home stack.
                shouldBeVisible = hasRunningActivities || isInStackLocked(starting) != null
                        || isActivityTypeHome();
                break;
            }

            if (!hasRunningActivities) {
                continue;
            }

            final int otherWindowingMode = other.getWindowingMode();

            if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                // In this case the home stack isn't resizeable even though we are in split-screen
                // mode. We still want the primary splitscreen stack to be visible as there will be
                // a slight hint of it in the status bar area above the non-resizeable home
                // activity. In addition, if the fullscreen assistant is over primary splitscreen
                // stack, the stack should still be visible in the background as long as the recents
                // animation is running.
                final int activityType = other.getActivityType();
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                    if (activityType == ACTIVITY_TYPE_HOME
                            || (activityType == ACTIVITY_TYPE_ASSISTANT
                                && mWindowManager.getRecentsAnimationController() != null)) {
                        break;
                    }
                }
                if (other.isStackTranslucent(starting)) {
                    // Can be visible behind a translucent fullscreen stack.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return STACK_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && !gotOpaqueSplitScreenPrimary) {
                gotSplitScreenStack = true;
                gotTranslucentSplitScreenPrimary = other.isStackTranslucent(starting);
                gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && gotOpaqueSplitScreenPrimary) {
                    // Can not be visible behind another opaque stack in split-screen-primary mode.
                    return STACK_VISIBILITY_INVISIBLE;
                }
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && !gotOpaqueSplitScreenSecondary) {
                gotSplitScreenStack = true;
                gotTranslucentSplitScreenSecondary = other.isStackTranslucent(starting);
                gotOpaqueSplitScreenSecondary = !gotTranslucentSplitScreenSecondary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        && gotOpaqueSplitScreenSecondary) {
                    // Can not be visible behind another opaque stack in split-screen-secondary mode.
                    return STACK_VISIBILITY_INVISIBLE;
                }
            }
            if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                // Can not be visible if we are in split-screen windowing mode and both halves of
                // the screen are opaque.
                return STACK_VISIBILITY_INVISIBLE;
            }
            if (isAssistantType && gotSplitScreenStack) {
                // Assistant stack can't be visible behind split-screen. In addition to this not
                // making sense, it also works around an issue here we boost the z-order of the
                // assistant window surfaces in window manager whenever it is visible.
                return STACK_VISIBILITY_INVISIBLE;
            }
        }

        if (!shouldBeVisible) {
            return STACK_VISIBILITY_INVISIBLE;
        }

        // Handle cases when there can be a translucent split-screen stack on top.
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                if (gotTranslucentSplitScreenPrimary || gotTranslucentSplitScreenSecondary) {
                    // At least one of the split-screen stacks that covers this one is translucent.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                if (gotTranslucentSplitScreenPrimary) {
                    // Covered by translucent primary split-screen on top.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                if (gotTranslucentSplitScreenSecondary) {
                    // Covered by translucent secondary split-screen on top.
                    return STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
        }

        // Lastly - check if there is a translucent fullscreen stack on top.
        return gotTranslucentFullscreen ? STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT
                : STACK_VISIBILITY_VISIBLE;
    }

    /**
     * Make sure that all activities that need to be visible in the stack (that is, they
     * currently can be seen by the user) actually are and update their configuration.
     */
    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        ensureActivitiesVisible(starting, configChanges, preserveWindows, true /* notifyClients */);
    }

    /**
     * Ensure visibility with an option to also update the configuration of visible activities.
     * @see #ensureActivitiesVisible(ActivityRecord, int, boolean)
     * @see RootActivityContainer#ensureActivitiesVisible(ActivityRecord, int, boolean)
     */
    // TODO: Should be re-worked based on the fact that each task as a stack in most cases.
    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
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

    @Override
    public boolean supportsSplitScreenWindowingMode() {
        final Task topTask = getTopMostTask();
        return super.supportsSplitScreenWindowingMode()
                && (topTask == null || topTask.supportsSplitScreenWindowingMode());
    }

    /** @return True if the resizing of the primary-split-screen stack affects this stack size. */
    boolean affectedBySplitScreenResize() {
        if (!supportsSplitScreenWindowingMode()) {
            return false;
        }
        final int windowingMode = getWindowingMode();
        return windowingMode != WINDOWING_MODE_FREEFORM && windowingMode != WINDOWING_MODE_PINNED;
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
        final int displayId = mDisplayId != INVALID_DISPLAY ? mDisplayId : DEFAULT_DISPLAY;
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
                    + mStackId);
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
                mWindowManager.setWindowOpaqueLocked(waitingActivity.appToken, false);
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
     *       Use {@link RootActivityContainer#resumeFocusedStacksTopActivities} to resume the
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

    /**
     * Returns the currently resumed activity.
     */
    protected ActivityRecord getResumedActivity() {
        return mResumedActivity;
    }

    private void setResumedActivity(ActivityRecord r, String reason) {
        if (mResumedActivity == r) {
            return;
        }

        if (DEBUG_STACK) Slog.d(TAG_STACK, "setResumedActivity stack:" + this + " + from: "
                + mResumedActivity + " to:" + r + " reason:" + reason);
        mResumedActivity = r;
        mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    @GuardedBy("mService")
    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        if (!mService.isBooting() && !mService.isBooted()) {
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

        mRootActivityContainer.cancelInitializingActivities();

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        boolean userLeaving = mStackSupervisor.mUserLeaving;
        mStackSupervisor.mUserLeaving = false;

        if (!hasRunningActivity) {
            // There are no activities left in the stack, let's look somewhere else.
            return resumeNextFocusableActivityWhenStackIsEmpty(prev, options);
        }

        next.delayedResume = false;
        final DisplayContent display = getDisplay();

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.isState(RESUMED)
                && display.allResumedActivitiesComplete()) {
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

        // If we are sleeping, and there is no resumed activity, and the top
        // activity is paused, well that is the state we want.
        if (shouldSleepOrShutDownActivities()
                && mLastPausedActivity == next
                && mRootActivityContainer.allPausedActivitiesComplete()) {
            // If the current top activity may be able to occlude keyguard but the occluded state
            // has not been set, update visibility and check again if we should continue to resume.
            boolean nothingToResume = true;
            if (!mService.mShuttingDown) {
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
        if (!mService.mAmInternal.hasStartedUserState(next.mUserId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.mUserId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStackSupervisor.mStoppingActivities.remove(next);
        mStackSupervisor.mGoingToSleepActivities.remove(next);
        next.sleeping = false;

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything until that is done.
        if (!mRootActivityContainer.allPausedActivitiesComplete()) {
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "resumeTopActivityLocked: Skip resume: some activity pausing.");

            return false;
        }

        mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        ActivityRecord lastResumed = null;
        final ActivityStack lastFocusedStack = display.getLastFocusedStack();
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

        boolean pausing = display.pauseBackStacks(userLeaving, next);
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
                final boolean isTop = this == display.getFocusedStack();
                mService.startProcessAsync(next, false /* knownToBeDead */, isTop,
                        isTop ? "pre-top-activity" : "pre-activity");
            }
            if (lastResumed != null) {
                lastResumed.setWillCloseOrEnterPip(true);
            }
            return true;
        } else if (mResumedActivity == next && next.isState(RESUMED)
                && display.allResumedActivitiesComplete()) {
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
            mService.getPackageManager().setPackageStoppedState(
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
        final DisplayContent dc = display.mDisplayContent;
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

            mService.updateCpuStats();

            if (DEBUG_STATES) Slog.v(TAG_STATES, "Moving to RESUMED: " + next
                    + " (in existing)");

            next.setState(RESUMED, "resumeTopActivityInnerLocked");

            next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */);
            updateLruList(next);

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
                notUpdated = !mRootActivityContainer.ensureVisibilityAndConfig(next, mDisplayId,
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

                next.sleeping = false;
                mService.getAppWarningsLocked().onResumeActivity(next);
                next.app.setPendingUiCleanAndForceProcessStateUpTo(mService.mTopProcessState);
                next.clearOptionsLocked();
                transaction.setLifecycleStateRequest(
                        ResumeActivityItem.obtain(next.app.getReportedProcState(),
                                dc.isNextTransitionForward()));
                mService.getLifecycleManager().scheduleTransaction(transaction);

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
                        && lastFocusedStack.isTopStackOnDisplay()) {
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
            final ActivityStack nextFocusedStack = adjustFocusToNextFocusableStack(reason);
            if (nextFocusedStack != null) {
                // Try to move focus to the next visible stack with a running activity if this
                // stack is not covering the entire screen or is on a secondary display with no home
                // stack.
                return mRootActivityContainer.resumeFocusedStacksTopActivities(nextFocusedStack,
                        prev, null /* targetOptions */);
            }
        }

        // If the current stack is a home stack, or if focus didn't switch to a different stack -
        // just start up the Launcher...
        ActivityOptions.abort(options);
        if (DEBUG_STATES) Slog.d(TAG_STATES,
                "resumeNextFocusableActivityWhenStackIsEmpty: " + reason + ", go home");
        return mRootActivityContainer.resumeHomeActivity(prev, reason, mDisplayId);
    }

    void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity,
            boolean newTask, boolean keepCurTransition, ActivityOptions options) {
        Task rTask = r.getTask();
        final boolean allowMoveToFront = options == null || !options.getAvoidMoveToFront();
        final boolean hasTask = hasChild(rTask);
        // mLaunchTaskBehind tasks get placed at the back of the task stack.
        if (!r.mLaunchTaskBehind && allowMoveToFront && (!hasTask || newTask)) {
            // Last activity in task had been removed or ActivityManagerService is reusing task.
            // Insert or replace.
            // Might not even be in.
            positionChildAtTop(rTask);
        }
        Task task = null;
        if (!newTask && hasTask) {
            final ActivityRecord occludingActivity = getActivity(
                    (ar) -> !ar.finishing && ar.occludesParent(), true, rTask);
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
                ? toFrontTask.getStack() : toFrontActivity.getActivityStack();
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
        final ActivityOptions topOptions = sResetTargetTaskHelper.process(this, task, forceReset);

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
     * Find next proper focusable stack and make it focused.
     * @return The stack that now got the focus, {@code null} if none found.
     */
    ActivityStack adjustFocusToNextFocusableStack(String reason) {
        return adjustFocusToNextFocusableStack(reason, false /* allowFocusSelf */);
    }

    /**
     * Find next proper focusable stack and make it focused.
     * @param allowFocusSelf Is the focus allowed to remain on the same stack.
     * @return The stack that now got the focus, {@code null} if none found.
     */
    private ActivityStack adjustFocusToNextFocusableStack(String reason, boolean allowFocusSelf) {
        final ActivityStack stack =
                mRootActivityContainer.getNextFocusableStack(this, !allowFocusSelf);
        final String myReason = reason + " adjustFocusToNextFocusableStack";
        if (stack == null) {
            return null;
        }

        final ActivityRecord top = stack.topRunningActivity();

        if (stack.isActivityTypeHome() && (top == null || !top.mVisibleRequested)) {
            // If we will be focusing on the home stack next and its current top activity isn't
            // visible, then use the move the home stack task to top to make the activity visible.
            stack.getDisplay().moveHomeActivityToTop(reason);
            return stack;
        }

        stack.moveToFront(myReason);
        // Top display focused stack is changed, update top resumed activity if needed.
        if (stack.mResumedActivity != null) {
            mStackSupervisor.updateTopResumedActivityIfNeeded();
            // Set focused app directly because if the next focused activity is already resumed
            // (e.g. the next top activity is on a different display), there won't have activity
            // state change to update it.
            mService.setResumedActivityUncheckLocked(stack.mResumedActivity, reason);
        }
        return stack;
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
                        || mService.mHomeProcess != activityBelow.app) {
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
        forAllTasks(c);
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
        final DisplayContent display = getDisplay();
        if (display == null) {
            return false;
        }
        final int index = display.getIndexOf(this);
        if (index == 0) {
            return false;
        }
        final ActivityStack stackBehind = display.getStackAt(index - 1);
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
        final Task task = srec.getTask();

        if (!mChildren.contains(task) || !task.hasChild(srec)) {
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
        IActivityController controller = mService.mController;
        if (controller != null) {
            ActivityRecord next = topRunningActivity(srec.appToken, INVALID_TASK_ID);
            if (next != null) {
                // ask watcher if this is allowed
                boolean resumeOK = true;
                try {
                    resumeOK = controller.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mService.mController = null;
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
            final int parentLaunchMode = parent.info.launchMode;
            final int destIntentFlags = destIntent.getFlags();
            if (parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                    parentLaunchMode == ActivityInfo.LAUNCH_SINGLE_TOP ||
                    (destIntentFlags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                parent.deliverNewIntentLocked(srec.info.applicationInfo.uid, destIntent,
                        srec.packageName);
            } else {
                try {
                    ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(
                            destIntent.getComponent(), ActivityManagerService.STOCK_PM_FLAGS,
                            srec.mUserId);
                    // TODO(b/64750076): Check if calling pid should really be -1.
                    final int res = mService.getActivityStartController()
                            .obtainStarter(destIntent, "navigateUpTo")
                            .setCaller(srec.app.getThread())
                            .setActivityInfo(aInfo)
                            .setResultTo(parent.appToken)
                            .setCallingPid(-1)
                            .setCallingUid(parent.launchedFromUid)
                            .setCallingPackage(parent.launchedFromPackage)
                            .setRealCallingPid(-1)
                            .setRealCallingUid(parent.launchedFromUid)
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

    /**
     * Remove any state associated with the {@link ActivityRecord}. This should be called whenever
     * an activity moves away from the stack.
     */
    void onActivityRemovedFromStack(ActivityRecord r) {
        removeTimeoutsForActivity(r);

        mExitingActivities.remove(r);
        mLruActivities.remove(r);

        if (mResumedActivity != null && mResumedActivity == r) {
            setResumedActivity(null, "onActivityRemovedFromStack");
        }
        if (mPausingActivity != null && mPausingActivity == r) {
            mPausingActivity = null;
        }
    }

    void onActivityAddedToStack(ActivityRecord r) {
        if (r.isState(RESUMED)) {
            setResumedActivity(r, "onActivityAddedToStack");
        }
        if (r.hasProcess()) {
            updateLruList(r);
        }
    }

    void removeTimeoutsForActivity(ActivityRecord r) {
        mStackSupervisor.removeTimeoutsForActivityLocked(r);
        removePauseTimeoutForActivity(r);
        removeStopTimeoutForActivity(r);
        removeDestroyTimeoutForActivity(r);
        r.finishLaunchTickingLocked();
    }

    void scheduleDestroyActivities(WindowProcessController owner, String reason) {
        final Message msg = mHandler.obtainMessage(DESTROY_ACTIVITIES_MSG);
        msg.obj = new ScheduleDestroyArgs(owner, reason);
        mHandler.sendMessage(msg);
    }

    void scheduleDestroyTimeoutForActivity(ActivityRecord r) {
        final Message msg = mHandler.obtainMessage(DESTROY_TIMEOUT_MSG, r);
        mHandler.sendMessageDelayed(msg, DESTROY_TIMEOUT);
    }

    void removeDestroyTimeoutForActivity(ActivityRecord r) {
        mHandler.removeMessages(DESTROY_TIMEOUT_MSG, r);
    }

    void scheduleStopTimeoutForActivity(ActivityRecord r) {
        final Message msg = mHandler.obtainMessage(STOP_TIMEOUT_MSG, r);
        mHandler.sendMessageDelayed(msg, STOP_TIMEOUT);
    }

    void removeStopTimeoutForActivity(ActivityRecord r) {
        mHandler.removeMessages(STOP_TIMEOUT_MSG, r);
    }

    /**
     * Schedule a pause timeout in case the app doesn't respond. We don't give it much time because
     * this directly impacts the responsiveness seen by the user.
     */
    private void schedulePauseTimeoutForActivity(ActivityRecord r) {
        final Message msg = mHandler.obtainMessage(PAUSE_TIMEOUT_MSG, r);
        r.pauseTime = SystemClock.uptimeMillis();
        mHandler.sendMessageDelayed(msg, PAUSE_TIMEOUT);
        if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Waiting for pause to complete...");
    }

    void removePauseTimeoutForActivity(ActivityRecord r) {
        mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
    }

    void scheduleLaunchTickForActivity(ActivityRecord r) {
        final Message msg = mHandler.obtainMessage(LAUNCH_TICK_MSG, r);
        mHandler.sendMessageDelayed(msg, LAUNCH_TICK);
    }

    void removeLaunchTickMessages() {
        mHandler.removeMessages(LAUNCH_TICK_MSG);
    }
    /// HANDLER INTERFACE END

    private void destroyActivities(WindowProcessController owner, String reason) {
        try {
            mStackSupervisor.beginDeferResume();

            final PooledConsumer c = PooledLambda.obtainConsumer(ActivityStack::destroyActivity,
                    PooledLambda.__(ActivityRecord.class), owner, reason);
            forAllActivities(c);
            c.recycle();
        } finally {
            mStackSupervisor.endDeferResume();
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    private static void destroyActivity(
            ActivityRecord r, WindowProcessController owner, String reason) {
        if (r.finishing || (owner != null && r.app != owner) || !r.isDestroyable()) return;

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Destroying " + r
                + " in state " + r.getState()
                + " resumed=" + r.getStack().mResumedActivity
                + " pausing=" + r.getStack().mPausingActivity + " for reason " + reason);

        r.destroyImmediately(true /* removeFromTask */, reason);
    }

    private void removeHistoryRecordsForApp(ArrayList<ActivityRecord> list,
            WindowProcessController app, String listName) {
        int i = list.size();
        if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
            "Removing app " + app + " from list " + listName + " with " + i + " entries");
        while (i > 0) {
            i--;
            ActivityRecord r = list.get(i);
            if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "Record #" + i + " " + r);
            if (r.app == app) {
                if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "---> REMOVING this entry!");
                list.remove(i);
                removeTimeoutsForActivity(r);
            }
        }
    }

    private boolean removeHistoryRecordsForApp(WindowProcessController app) {
        removeHistoryRecordsForApp(mLruActivities, app, "mLruActivities");
        removeHistoryRecordsForApp(mStackSupervisor.mStoppingActivities, app,
                "mStoppingActivities");
        removeHistoryRecordsForApp(mStackSupervisor.mGoingToSleepActivities, app,
                "mGoingToSleepActivities");
        removeHistoryRecordsForApp(mStackSupervisor.mFinishingActivities, app,
                "mFinishingActivities");
        return mRemoveHistoryRecordsForApp.process(app);
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

    final void moveTaskToFrontLocked(Task tr, boolean noAnimation, ActivityOptions options,
            AppTimeTracker timeTracker, String reason) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "moveTaskToFront: " + tr);

        final ActivityStack topStack = getDisplay().getTopStack();
        final ActivityRecord topActivity = topStack != null ? topStack.getTopNonFinishingActivity() : null;
        final int numTasks = getChildCount();
        final int index = mChildren.indexOf(tr);
        if (numTasks == 0 || index < 0)  {
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

            mRootActivityContainer.resumeFocusedStacksTopActivities();
            EventLogTags.writeWmTaskToFront(tr.mUserId, tr.mTaskId);
            mService.getTaskChangeNotificationController().notifyTaskMovedToFront(tr.getTaskInfo());
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
        if (!mService.getLockTaskController().canMoveTaskToBack(tr)) {
            return false;
        }

        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (isTopStackOnDisplay() && mService.mController != null) {
            ActivityRecord next = topRunningActivity(null, tr.mTaskId);
            if (next == null) {
                next = topRunningActivity(null, INVALID_TASK_ID);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mService.mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mService.mController = null;
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

        ActivityRecord topActivity = getDisplay().topRunningActivity();
        ActivityStack topStack = topActivity.getActivityStack();
        if (topStack != null && topStack != this && topActivity.isState(RESUMED)) {
            // The new top activity is already resumed, so there's a good chance that nothing will
            // get resumed below. So, update visibility now in case the transition is closed
            // prematurely.
            mRootActivityContainer.ensureVisibilityAndConfig(null /* starting */,
                    getDisplay().mDisplayId, false /* markFrozenIfConfigChanged */,
                    false /* deferResume */);
        }

        mRootActivityContainer.resumeFocusedStacksTopActivities();
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
    void resize(Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds,
            boolean preserveWindows, boolean deferResume) {
        if (!updateBoundsAllowed(bounds)) {
            return;
        }

        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "stack.resize_" + mStackId);
        mService.deferWindowLayout();
        try {
            // Update override configurations of all tasks in the stack.
            final Rect taskBounds = tempTaskBounds != null ? tempTaskBounds : bounds;
            final PooledConsumer c = PooledLambda.obtainConsumer(
                    ActivityStack::processTaskResizeBounds, PooledLambda.__(Task.class),
                    taskBounds, tempTaskInsetBounds);
            forAllTasks(c);
            c.recycle();

            setBounds(bounds);

            if (!deferResume) {
                ensureVisibleActivitiesConfiguration(topRunningActivity(), preserveWindows);
            }
        } finally {
            mService.continueWindowLayout();
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private static void processTaskResizeBounds(Task task, Rect bounds, Rect insetBounds) {
        if (!task.isResizeable()) return;

        if (insetBounds != null && !insetBounds.isEmpty()) {
            task.setOverrideDisplayedBounds(bounds);
            task.setBounds(insetBounds);
        } else {
            task.setOverrideDisplayedBounds(null);
            task.setBounds(bounds);
        }
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
        forAllTasks(c);
        c.recycle();
    }

    private static void setTaskBounds(Task task, Rect bounds) {
        task.setBounds(task.isResizeable() ? bounds : null);
    }

    /** Helper to setDisplayedBounds on all child tasks */
    private void setTaskDisplayedBounds(Rect bounds) {
        if (!updateDisplayedBoundsAllowed(bounds)) {
            return;
        }

        final PooledConsumer c = PooledLambda.obtainConsumer(ActivityStack::setTaskDisplayedBounds,
                PooledLambda.__(Task.class), bounds);
        forAllTasks(c);
        c.recycle();
    }

    private static void setTaskDisplayedBounds(Task task, Rect bounds) {
        task.setOverrideDisplayedBounds(bounds == null || bounds.isEmpty() ? null : bounds);
    }

    boolean willActivityBeVisible(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            return false;
        }

        // See if there is an occluding activity on-top of this one.
        final ActivityRecord occludingActivity = getActivity((ar) ->
                ar.occludesParent() && !ar.finishing,
                r, false /*includeBoundary*/, true /*traverseTopToBottom*/);
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
    boolean handleAppDiedLocked(WindowProcessController app) {
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE || DEBUG_CLEANUP) Slog.v(TAG_PAUSE,
                    "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
            mLastNoHistoryActivity = null;
        }

        return removeHistoryRecordsForApp(app);
    }

    boolean dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient,
            String dumpPackage, boolean needSep) {
        pw.println("  Stack #" + mStackId
                + ": type=" + activityTypeToString(getActivityType())
                + " mode=" + windowingModeToString(getWindowingMode()));
        pw.println("  isSleeping=" + shouldSleepActivities());
        pw.println("  mBounds=" + getRequestedOverrideBounds());

        boolean printed = dumpActivities(fd, pw, dumpAll, dumpClient, dumpPackage, needSep);

        printed |= dumpHistoryList(fd, pw, mLruActivities, "    ", "Run", false,
                !dumpAll, false, dumpPackage, true,
                "    Running activities (most recent first):", null);

        needSep = printed;
        boolean pr = printThisActivity(pw, mPausingActivity, dumpPackage, needSep,
                "    mPausingActivity: ");
        if (pr) {
            printed = true;
            needSep = false;
        }
        pr = printThisActivity(pw, getResumedActivity(), dumpPackage, needSep,
                "    mResumedActivity: ");
        if (pr) {
            printed = true;
            needSep = false;
        }
        if (dumpAll) {
            pr = printThisActivity(pw, mLastPausedActivity, dumpPackage, needSep,
                    "    mLastPausedActivity: ");
            if (pr) {
                printed = true;
                needSep = true;
            }
            printed |= printThisActivity(pw, mLastNoHistoryActivity, dumpPackage,
                    needSep, "    mLastNoHistoryActivity: ");
        }
        return printed;
    }

    private boolean dumpActivities(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, boolean needSep) {

        if (!hasChild()) {
            return false;
        }
        final String prefix = "    ";
        forAllTasks((task) -> {
            if (needSep) {
                pw.println("");
            }
            pw.println(prefix + "Task id #" + task.mTaskId);
            pw.println(prefix + "mBounds=" + task.getRequestedOverrideBounds());
            pw.println(prefix + "mMinWidth=" + task.mMinWidth);
            pw.println(prefix + "mMinHeight=" + task.mMinHeight);
            pw.println(prefix + "mLastNonFullscreenBounds=" + task.mLastNonFullscreenBounds);
            pw.println(prefix + "* " + task);
            task.dump(pw, prefix + "  ");
            final ArrayList<ActivityRecord> activities = new ArrayList<>();
            // Add activities by traversing the hierarchy from bottom to top, since activities
            // are dumped in reverse order in {@link ActivityStackSupervisor#dumpHistoryList()}.
            forAllActivities((Consumer<ActivityRecord>) activities::add,
                    false /* traverseTopToBottom */);
            dumpHistoryList(fd, pw, activities, prefix, "Hist", true, !dumpAll, dumpClient,
                    dumpPackage, false, null, task);
        });
        return true;
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

    /**
     * Removes the input task from this stack.
     *
     * @param child to remove.
     * @param reason for removal.
     */
    void removeChild(WindowContainer child, String reason) {
        if (!mChildren.contains(child)) {
            // Not really in this stack anymore...
            return;
        }

        final DisplayContent display = getDisplay();
        if (DEBUG_TASK_MOVEMENT) {
            Slog.d(TAG_WM, "removeChild: task=" + child + " reason=" + reason);
        }

        super.removeChild(child);

        EventLogTags.writeWmRemoveTask(((Task) child).mTaskId, mStackId);

        if (display.isSingleTaskInstance()) {
            mService.notifySingleTaskDisplayEmpty(display.mDisplayId);
        }

        display.mDisplayContent.setLayoutNeeded();

        if (!hasChild()) {
            // Stack is now empty...
          removeIfPossible();
        }
    }

    @Override
    void removeChild(WindowContainer child) {
        removeChild(child, "removeChild");
    }

    Task createTask(int taskId, ActivityInfo info, Intent intent,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            boolean toTop) {
        return createTask(taskId, info, intent, voiceSession, voiceInteractor, toTop,
                null /*activity*/, null /*source*/, null /*options*/);
    }

    Task createTask(int taskId, ActivityInfo info, Intent intent,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            boolean toTop, ActivityRecord activity, ActivityRecord source,
            ActivityOptions options) {
        final Task task = Task.create(
                mService, taskId, info, intent, voiceSession, voiceInteractor, this);
        // add the task to stack first, mTaskPositioner might need the stack association
        addChild(task, toTop, (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
        final int displayId = mDisplayId != INVALID_DISPLAY ? mDisplayId : DEFAULT_DISPLAY;
        final boolean isLockscreenShown = mService.mStackSupervisor.getKeyguardController()
                .isKeyguardOrAodShowing(displayId);
        if (!mStackSupervisor.getLaunchParamsController()
                .layoutTask(task, info.windowLayout, activity, source, options)
                && !matchParentBounds() && task.isResizeable() && !isLockscreenShown) {
            task.setBounds(getRequestedOverrideBounds());
        }
        return task;
    }

    void addChild(final Task task, final boolean toTop, boolean showForAllUsers) {
        if (isSingleTaskInstance() && hasChild()) {
            throw new IllegalStateException("Can only have one child on stack=" + this);
        }

        // We only want to move the parents to the parents if we are creating this task at the
        // top of its stack.
        addChild(task, toTop ? MAX_VALUE : 0, showForAllUsers, toTop /*moveParents*/);
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
        boolean includingParents = toTop || getDisplay().getNextFocusableStack(this,
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
        mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        final DisplayContent display = getDisplay();
        // positionChildAtTop() must be called even when always on top gets turned off because we
        // need to make sure that the stack is moved from among always on top windows to below other
        // always on top windows. Since the position the stack should be inserted into is calculated
        // properly in {@link DisplayContent#getTopInsertPosition()} in both cases, we can just
        // request that the stack is put at top here.
        display.positionStackAtTop(this, false /* includingParents */);
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
            updateLruList(r);
        }
        // If the activity was previously pausing, then ensure we transfer that as well
        if (setPause) {
            mPausingActivity = r;
            schedulePauseTimeoutForActivity(r);
        }
        // Move the stack in which we are placing the activity to the front.
        moveToFront(reason);
        // If the original state is resumed, there is no state change to update focused app.
        // So here makes sure the activity focus is set if it is the top.
        if (origState == RESUMED && r == mRootActivityContainer.getTopResumedActivity()) {
            mService.setResumedActivityUncheckLocked(r, reason);
        }
    }

    void animateResizePinnedStack(Rect toBounds, Rect sourceHintBounds, int animationDuration,
            boolean fromFullscreen) {
        if (!inPinnedWindowingMode()) return;
        if (toBounds == null /* toFullscreen */) {
            final Configuration parentConfig = getParent().getConfiguration();
            final ActivityRecord top = topRunningNonOverlayTaskActivity();
            if (top != null && !top.isConfigurationCompatible(parentConfig)) {
                // The final orientation of this activity will change after moving to full screen.
                // Start freezing screen here to prevent showing a temporary full screen window.
                top.startFreezingScreenLocked(CONFIG_SCREEN_LAYOUT);
                dismissPip();
                return;
            }
        }

        // Get the from-bounds
        final Rect fromBounds = new Rect();
        getBounds(fromBounds);

        // Get non-null fullscreen to-bounds for animating if the bounds are null
        @BoundsAnimationController.SchedulePipModeChangedState int schedulePipModeChangedState =
                NO_PIP_MODE_CHANGED_CALLBACKS;
        final boolean toFullscreen = toBounds == null;
        if (toFullscreen) {
            if (fromFullscreen) {
                throw new IllegalArgumentException("Should not defer scheduling PiP mode"
                        + " change on animation to fullscreen.");
            }
            schedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_START;

            mWmService.getStackBounds(
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, mTmpToBounds);
            if (!mTmpToBounds.isEmpty()) {
                // If there is a fullscreen bounds, use that
                toBounds = new Rect(mTmpToBounds);
            } else {
                // Otherwise, use the display bounds
                toBounds = new Rect();
                getDisplayContent().getBounds(toBounds);
            }
        } else if (fromFullscreen) {
            schedulePipModeChangedState = SCHEDULE_PIP_MODE_CHANGED_ON_END;
        }

        setAnimationFinalBounds(sourceHintBounds, toBounds, toFullscreen);

        final Rect finalToBounds = toBounds;
        final @BoundsAnimationController.SchedulePipModeChangedState int
                finalSchedulePipModeChangedState = schedulePipModeChangedState;
        final DisplayContent displayContent = getDisplayContent();
        @BoundsAnimationController.AnimationType int intendedAnimationType =
                displayContent.mBoundsAnimationController.getAnimationType();
        if (intendedAnimationType == FADE_IN) {
            if (fromFullscreen) {
                setPinnedStackAlpha(0f);
            }
            if (toBounds.width() == fromBounds.width()
                    && toBounds.height() == fromBounds.height()) {
                intendedAnimationType = BoundsAnimationController.BOUNDS;
            } else if (!fromFullscreen && !toBounds.equals(fromBounds)) {
                // intendedAnimationType may have been reset at the end of RecentsAnimation,
                // force it to BOUNDS type if we know for certain we're animating to
                // a different bounds, especially for expand and collapse of PiP window.
                intendedAnimationType = BoundsAnimationController.BOUNDS;
            }
        }

        final @BoundsAnimationController.AnimationType int animationType = intendedAnimationType;
        mCancelCurrentBoundsAnimation = false;
        displayContent.mBoundsAnimationController.getHandler().post(() -> {
            displayContent.mBoundsAnimationController.animateBounds(this, fromBounds,
                    finalToBounds, animationDuration, finalSchedulePipModeChangedState,
                    fromFullscreen, toFullscreen, animationType);
        });
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

        if (mChildren.size() != 1) {
            throw new RuntimeException("There should be only one task in a pinned stack.");
        }

        // give pinned stack a chance to save current bounds, this should happen before reparent.
        final ActivityRecord top = topRunningNonOverlayTaskActivity();
        if (top != null && top.isVisible()) {
            top.savePinnedStackBounds();
        }

        mWindowManager.inSurfaceTransaction(() -> {
            final Task task = getBottomMostTask();
            setWindowingMode(WINDOWING_MODE_UNDEFINED);

            getDisplay().positionStackAtTop(this, false /* includingParents */);

            mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, this);
            MetricsLoggerWrapper.logPictureInPictureFullScreen(mService.mContext,
                    task.effectiveUid, task.realActivity.flattenToString());
        });
    }

    private void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds,
            boolean forceUpdate) {
        // It is guaranteed that the activities requiring the update will be in the pinned stack at
        // this point (either reparented before the animation into PiP, or before reparenting after
        // the animation out of PiP)
        if (!isAttached()) {
            return;
        }
        final PooledConsumer c = PooledLambda.obtainConsumer(
                ActivityStackSupervisor::updatePictureInPictureMode, mStackSupervisor,
                PooledLambda.__(Task.class), targetStackBounds, forceUpdate);
        forAllTasks(c);
        c.recycle();
    }

    public int getStackId() {
        return mStackId;
    }

    void prepareFreezingTaskBounds() {
        forAllTasks(Task::prepareFreezingBounds);
    }

    /**
     * Overrides the adjusted bounds, i.e. sets temporary layout bounds which are different from
     * the normal task bounds.
     *
     * @param bounds The adjusted bounds.
     */
    private void setAdjustedBounds(Rect bounds) {
        if (mAdjustedBounds.equals(bounds) && !isAnimatingForIme()) {
            return;
        }

        mAdjustedBounds.set(bounds);
        final boolean adjusted = !mAdjustedBounds.isEmpty();
        Rect insetBounds = null;
        if (adjusted && isAdjustedForMinimizedDockedStack()) {
            insetBounds = getRawBounds();
        } else if (adjusted && mAdjustedForIme) {
            if (mImeGoingAway) {
                insetBounds = getRawBounds();
            } else {
                insetBounds = mFullyAdjustedImeBounds;
            }
        }

        if (!matchParentBounds()) {
            final boolean alignBottom = mAdjustedForIme && getDockSide() == DOCKED_TOP;
            final PooledConsumer c = PooledLambda.obtainConsumer(Task::alignToAdjustedBounds,
                    PooledLambda.__(Task.class), adjusted ? mAdjustedBounds : getRawBounds(),
                    insetBounds, alignBottom);
            forAllTasks(c);
            c.recycle();
        }

        mDisplayContent.setLayoutNeeded();
        updateSurfaceBounds();
    }

    @Override
    public int setBounds(Rect bounds) {
        return setBounds(getRequestedOverrideBounds(), bounds);
    }

    private int setBounds(Rect existing, Rect bounds) {
        if (equivalentBounds(existing, bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        final int result = super.setBounds(!inMultiWindowMode() ? null : bounds);

        updateAdjustedBounds();

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

    @Override
    public Rect getBounds() {
        // If we're currently adjusting for IME or minimized docked stack, we use the adjusted
        // bounds; otherwise, no need to adjust the output bounds if fullscreen or the docked
        // stack is visible since it is already what we want to represent to the rest of the
        // system.
        if (!mAdjustedBounds.isEmpty()) {
            return mAdjustedBounds;
        } else {
            return super.getBounds();
        }
    }

    /**
     * Sets the bounds animation target bounds ahead of an animation.  This can't currently be done
     * in onAnimationStart() since that is started on the UiThread.
     */
    private void setAnimationFinalBounds(Rect sourceHintBounds, Rect destBounds,
            boolean toFullscreen) {
        if (mAnimationType == BoundsAnimationController.BOUNDS) {
            mBoundsAnimatingRequested = true;
        }
        mBoundsAnimatingToFullscreen = toFullscreen;
        if (destBounds != null) {
            mBoundsAnimationTarget.set(destBounds);
        } else {
            mBoundsAnimationTarget.setEmpty();
        }
        if (sourceHintBounds != null) {
            mBoundsAnimationSourceHintBounds.set(sourceHintBounds);
        } else if (!mBoundsAnimating) {
            // If the bounds are already animating, we don't want to reset the source hint. This is
            // because the source hint is sent when starting the animation from the client that
            // requested to enter pip. Other requests can adjust the pip bounds during an animation,
            // but could accidentally reset the source hint bounds.
            mBoundsAnimationSourceHintBounds.setEmpty();
        }

        mPreAnimationBounds.set(getRawBounds());
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

    /**
     * @return the final animation bounds if the task stack is currently being animated, or the
     *         current stack bounds otherwise.
     */
    void getAnimationOrCurrentBounds(Rect outBounds) {
        if ((mBoundsAnimatingRequested || mBoundsAnimating) && !mBoundsAnimationTarget.isEmpty()) {
            getFinalAnimationBounds(outBounds);
            return;
        }
        getBounds(outBounds);
    }

    /** Bounds of the stack with other system factors taken into consideration. */
    void getDimBounds(Rect out) {
        getBounds(out);
    }

    /**
     * Reset the current animation running on {@link #mBoundsAnimationTarget}.
     *
     * @param destinationBounds the final destination bounds
     */
    void resetCurrentBoundsAnimation(Rect destinationBounds) {
        boolean animating = (mBoundsAnimatingRequested || mBoundsAnimating)
                && !mBoundsAnimationTarget.isEmpty();

        // The final boundary is updated while there is an existing boundary animation. Let's
        // cancel this animation to prevent the obsolete animation overwritten updated bounds.
        if (animating && !destinationBounds.equals(mBoundsAnimationTarget)) {
            final BoundsAnimationController controller =
                    getDisplayContent().mBoundsAnimationController;
            controller.getHandler().post(() -> controller.cancel(this));
        }
        // Once we've set the bounds based on the rotation of the old bounds in the new
        // orientation, clear the animation target bounds since they are obsolete, and
        // cancel any currently running animations
        mBoundsAnimationTarget.setEmpty();
        mBoundsAnimationSourceHintBounds.setEmpty();
        mCancelCurrentBoundsAnimation = true;
    }

    /**
     * Updates the passed-in {@code inOutBounds} based on the current state of the
     * docked controller. This gets run *after* the override configuration is updated, so it's
     * safe to rely on the controller's state in here (though eventually this dependence should
     * be removed).
     *
     * This does NOT modify this TaskStack's configuration. However, it does, for the time-being,
     * update docked controller state.
     *
     * @param parentConfig the parent configuration for reference.
     * @param inOutBounds the bounds to update (both input and output).
     */
    void calculateDockedBoundsForConfigChange(Configuration parentConfig, Rect inOutBounds) {
        final boolean primary =
                getRequestedOverrideWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
        repositionSplitScreenStackAfterRotation(parentConfig, primary, inOutBounds);
        final DisplayCutout cutout = mDisplayContent.getDisplayInfo().displayCutout;
        snapDockedStackAfterRotation(parentConfig, cutout, inOutBounds);
        if (primary) {
            final int newDockSide = getDockSide(parentConfig, inOutBounds);
            // Update the dock create mode and clear the dock create bounds, these
            // might change after a rotation and the original values will be invalid.
            mWmService.setDockedStackCreateStateLocked(
                    (newDockSide == DOCKED_LEFT || newDockSide == DOCKED_TOP)
                            ? SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT
                            : SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT,
                    null);
            mDisplayContent.getDockedDividerController().notifyDockSideChanged(newDockSide);
        }
    }

    /**
     * Some primary split screen sides are not allowed by the policy. This method queries the policy
     * and moves the primary stack around if needed.
     *
     * @param parentConfig the configuration of the stack's parent.
     * @param primary true if adjusting the primary docked stack, false for secondary.
     * @param inOutBounds the bounds of the stack to adjust.
     */
    void repositionSplitScreenStackAfterRotation(Configuration parentConfig, boolean primary,
            Rect inOutBounds) {
        final int dockSide = getDockSide(mDisplayContent, parentConfig, inOutBounds);
        final int otherDockSide = DockedDividerUtils.invertDockSide(dockSide);
        final int primaryDockSide = primary ? dockSide : otherDockSide;
        if (mDisplayContent.getDockedDividerController()
                .canPrimaryStackDockTo(primaryDockSide,
                        parentConfig.windowConfiguration.getBounds(),
                        parentConfig.windowConfiguration.getRotation())) {
            return;
        }
        final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
        switch (otherDockSide) {
            case DOCKED_LEFT:
                int movement = inOutBounds.left;
                inOutBounds.left -= movement;
                inOutBounds.right -= movement;
                break;
            case DOCKED_RIGHT:
                movement = parentBounds.right - inOutBounds.right;
                inOutBounds.left += movement;
                inOutBounds.right += movement;
                break;
            case DOCKED_TOP:
                movement = inOutBounds.top;
                inOutBounds.top -= movement;
                inOutBounds.bottom -= movement;
                break;
            case DOCKED_BOTTOM:
                movement = parentBounds.bottom - inOutBounds.bottom;
                inOutBounds.top += movement;
                inOutBounds.bottom += movement;
                break;
        }
    }

    /**
     * Snaps the bounds after rotation to the closest snap target for the docked stack.
     */
    void snapDockedStackAfterRotation(Configuration parentConfig, DisplayCutout displayCutout,
            Rect outBounds) {

        // Calculate the current position.
        final int dividerSize = mDisplayContent.getDockedDividerController().getContentWidth();
        final int dockSide = getDockSide(parentConfig, outBounds);
        final int dividerPosition = DockedDividerUtils.calculatePositionForBounds(outBounds,
                dockSide, dividerSize);
        final int displayWidth = parentConfig.windowConfiguration.getBounds().width();
        final int displayHeight = parentConfig.windowConfiguration.getBounds().height();

        // Snap the position to a target.
        final int rotation = parentConfig.windowConfiguration.getRotation();
        final int orientation = parentConfig.orientation;
        mDisplayContent.getDisplayPolicy().getStableInsetsLw(rotation, displayWidth, displayHeight,
                displayCutout, outBounds);
        final DividerSnapAlgorithm algorithm = new DividerSnapAlgorithm(
                mWmService.mContext.getResources(), displayWidth, displayHeight,
                dividerSize, orientation == Configuration.ORIENTATION_PORTRAIT, outBounds,
                getDockSide(), isMinimizedDockAndHomeStackResizable());
        final DividerSnapAlgorithm.SnapTarget target =
                algorithm.calculateNonDismissingSnapTarget(dividerPosition);

        // Recalculate the bounds based on the position of the target.
        DockedDividerUtils.calculateBoundsForPosition(target.position, dockSide,
                outBounds, displayWidth, displayHeight,
                dividerSize);
    }

    /**
     * Put a Task in this stack. Used for adding only.
     * When task is added to top of the stack, the entire branch of the hierarchy (including stack
     * and display) will be brought to top.
     * @param task The task to add.
     * @param position Target position to add the task to.
     * @param showForAllUsers Whether to show the task regardless of the current user.
     */
    private void addChild(Task task, int position, boolean showForAllUsers, boolean moveParents) {
        // Add child task.
        addChild(task, null);

        // Move child to a proper position, as some restriction for position might apply.
        positionChildAt(position, task, moveParents /* includingParents */, showForAllUsers);
    }

    @Override
    void addChild(WindowContainer child, int position) {
        final Task task = (Task) child;
        addChild(task, position, task.showForAllUsers(), false /* includingParents */);
    }

    void positionChildAtTop(Task child) {
        if (child == null) {
            // TODO: Fix the call-points that cause this to happen.
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
        final ActivityStack nextFocusableStack = getDisplay().getNextFocusableStack(
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
    void positionChildAt(int position, WindowContainer child, boolean includingParents) {
        final Task task = (Task) child;
        positionChildAt(position, task, includingParents, task.showForAllUsers());
    }

    /**
     * Overridden version of {@link ActivityStack#positionChildAt(int, WindowContainer, boolean)}.
     * Used in {@link ActivityStack#addChild(Task, int, boolean showForAllUsers, boolean)}, as it
     * can receive showForAllUsers param from {@link ActivityRecord} instead of
     * {@link Task#showForAllUsers()}.
     */
    private int positionChildAt(int position, Task child, boolean includingParents,
            boolean showForAllUsers) {
        final int targetPosition = findPositionForTask(child, position, showForAllUsers);
        super.positionChildAt(targetPosition, child, includingParents);

        // Log positioning.
        if (DEBUG_TASK_MOVEMENT) {
            Slog.d(TAG_WM, "positionTask: task=" + this + " position=" + position);
        }

        final int toTop = targetPosition == mChildren.size() - 1 ? 1 : 0;
        EventLogTags.writeWmTaskMoved(child.mTaskId, toTop, targetPosition);
        return targetPosition;
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        if (!mChildren.contains(child)) {
            return;
        }

        final Task task = (Task) child;
        final boolean isTop = getTopChild() == task;
        task.updateTaskMovement(isTop);
        if (isTop) {
            final DisplayContent displayContent = getDisplayContent();
            displayContent.layoutAndAssignWindowLayersIfNeeded();
        }
    }

    @Override
    protected void onParentChanged(
            ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        final DisplayContent display = newParent != null
                ? ((WindowContainer) newParent).getDisplayContent() : null;
        final DisplayContent oldDisplay = oldParent != null
                ? ((WindowContainer) oldParent).getDisplayContent() : null;

        mDisplayId = (display != null) ? display.mDisplayId : INVALID_DISPLAY;
        mPrevDisplayId = (oldDisplay != null) ? oldDisplay.mDisplayId : INVALID_DISPLAY;

        if (display != null) {
            // Rotations are relative to the display. This means if there are 2 displays rotated
            // differently (eg. 2 monitors with one landscape and one portrait), moving a stack
            // from one to the other could look like a rotation change. To prevent this
            // apparent rotation change (and corresponding bounds rotation), pretend like our
            // current rotation is already the same as the new display.
            // Note, if ActivityStack or related logic ever gets nested, this logic will need
            // to move to onConfigurationChanged.
            getConfiguration().windowConfiguration.setRotation(
                    display.getWindowConfiguration().getRotation());
        }
        super.onParentChanged(newParent, oldParent);
        if (getParent() == null && mDisplayContent != null) {
            EventLogTags.writeWmStackRemoved(mStackId);
            mDisplayContent = null;
            mWmService.mWindowPlacerLocked.requestTraversal();
        }
        if (display != null && inSplitScreenPrimaryWindowingMode()) {
            // If we created a docked stack we want to resize it so it resizes all other stacks
            // in the system.
            getStackDockedModeBounds(null /* dockedBounds */, null /* currentTempTaskBounds */,
                    mTmpRect /* outStackBounds */, mTmpRect2 /* outTempTaskBounds */);
            mStackSupervisor.resizeDockedStackLocked(getRequestedOverrideBounds(), mTmpRect,
                    mTmpRect2, null, null, PRESERVE_WINDOWS);
        }
        mRootActivityContainer.updateUIDsPresentOnDisplay();

        // Resume next focusable stack after reparenting to another display if we aren't removing
        // the prevous display.
        if (oldDisplay != null && oldDisplay.isRemoving()) {
            postReparent();
        }
    }

    void reparent(DisplayContent newParent, boolean onTop) {
        // Real parent of stack is within display object, so we have to delegate re-parenting there.
        newParent.moveStackToDisplay(this, onTop);
    }

    // TODO: We should really have users as a window container in the hierarchy so that we don't
    // have to do complicated things like we are doing in this method.
    int findPositionForTask(Task task, int targetPosition, boolean showForAllUsers) {
        final boolean canShowTask =
                showForAllUsers || mWmService.isCurrentProfileLocked(task.mUserId);

        final int stackSize = mChildren.size();
        int minPosition = 0;
        int maxPosition = stackSize - 1;

        if (canShowTask) {
            minPosition = computeMinPosition(minPosition, stackSize);
        } else {
            maxPosition = computeMaxPosition(maxPosition);
        }

        // preserve POSITION_BOTTOM/POSITION_TOP positions if they are still valid.
        if (targetPosition == POSITION_BOTTOM && minPosition == 0) {
            return POSITION_BOTTOM;
        } else if (targetPosition == POSITION_TOP && maxPosition == (stackSize - 1)) {
            return POSITION_TOP;
        }
        // Reset position based on minimum/maximum possible positions.
        return Math.min(Math.max(targetPosition, minPosition), maxPosition);
    }

    /** Calculate the minimum possible position for a task that can be shown to the user.
     *  The minimum position will be above all other tasks that can't be shown.
     *  @param minPosition The minimum position the caller is suggesting.
     *                  We will start adjusting up from here.
     *  @param size The size of the current task list.
     */
    // TODO(task-hierarchy): Move user to their own window container.
    private int computeMinPosition(int minPosition, int size) {
        while (minPosition < size) {
            final Task tmpTask = (Task) mChildren.get(minPosition);
            final boolean canShowTmpTask =
                    tmpTask.showForAllUsers()
                            || mWmService.isCurrentProfileLocked(tmpTask.mUserId);
            if (canShowTmpTask) {
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
    // TODO(task-hierarchy): Move user to their own window container.
    private int computeMaxPosition(int maxPosition) {
        while (maxPosition > 0) {
            final Task tmpTask = (Task) mChildren.get(maxPosition);
            final boolean canShowTmpTask =
                    tmpTask.showForAllUsers()
                            || mWmService.isCurrentProfileLocked(tmpTask.mUserId);
            if (!canShowTmpTask) {
                break;
            }
            maxPosition--;
        }
        return maxPosition;
    }

    private void updateSurfaceBounds() {
        updateSurfaceSize(getPendingTransaction());
        updateSurfacePosition();
        scheduleAnimation();
    }

    /**
     * Calculate an amount by which to expand the stack bounds in each direction.
     * Used to make room for shadows in the pinned windowing mode.
     */
    int getStackOutset() {
        DisplayContent displayContent = getDisplayContent();
        if (inPinnedWindowingMode() && displayContent != null) {
            final DisplayMetrics displayMetrics = displayContent.getDisplayMetrics();

            // We multiply by two to match the client logic for converting view elevation
            // to insets, as in {@link WindowManager.LayoutParams#setSurfaceInsets}
            return (int) Math.ceil(
                    mWmService.dipToPixel(PINNED_WINDOWING_MODE_ELEVATION_IN_DIP, displayMetrics)
                            * 2);
        }
        return 0;
    }

    @Override
    void getRelativeDisplayedPosition(Point outPos) {
        super.getRelativeDisplayedPosition(outPos);
        final int outset = getStackOutset();
        outPos.x -= outset;
        outPos.y -= outset;
    }

    private void updateSurfaceSize(SurfaceControl.Transaction transaction) {
        if (mSurfaceControl == null) {
            return;
        }

        final Rect stackBounds = getDisplayedBounds();
        int width = stackBounds.width();
        int height = stackBounds.height();

        final int outset = getStackOutset();
        width += 2 * outset;
        height += 2 * outset;

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

    @Override
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        updateSurfaceBounds();
    }

    /**
     * Determines the stack and task bounds of the other stack when in docked mode. The current task
     * bounds is passed in but depending on the stack, the task and stack must match. Only in
     * minimized mode with resizable launcher, the other stack ignores calculating the stack bounds
     * and uses the task bounds passed in as the stack and task bounds, otherwise the stack bounds
     * is calculated and is also used for its task bounds.
     * If any of the out bounds are empty, it represents default bounds
     *
     * @param currentTempTaskBounds the current task bounds of the other stack
     * @param outStackBounds the calculated stack bounds of the other stack
     * @param outTempTaskBounds the calculated task bounds of the other stack
     */
    void getStackDockedModeBounds(Rect dockedBounds,
            Rect currentTempTaskBounds, Rect outStackBounds, Rect outTempTaskBounds) {
        final Configuration parentConfig = getParent().getConfiguration();
        outTempTaskBounds.setEmpty();

        if (dockedBounds == null || dockedBounds.isEmpty()) {
            // Calculate the primary docked bounds.
            final boolean dockedOnTopOrLeft = mWmService.mDockedStackCreateMode
                    == SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
            getStackDockedModeBounds(parentConfig,
                    true /* primary */, outStackBounds, dockedBounds,
                    mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
            return;
        }
        final int dockedSide = getDockSide(parentConfig, dockedBounds);

        // When the home stack is resizable, should always have the same stack and task bounds
        if (isActivityTypeHome()) {
            final Task homeTask = getTopMostTask();
            if (homeTask == null || homeTask.isResizeable()) {
                // Calculate the home stack bounds when in docked mode and the home stack is
                // resizeable.
                getDisplayContent().mDividerControllerLocked
                        .getHomeStackBoundsInDockedMode(parentConfig,
                                dockedSide, outStackBounds);
            } else {
                // Home stack isn't resizeable, so don't specify stack bounds.
                outStackBounds.setEmpty();
            }

            outTempTaskBounds.set(outStackBounds);
            return;
        }

        // When minimized state, the stack bounds for all non-home and docked stack bounds should
        // match the passed task bounds
        if (isMinimizedDockAndHomeStackResizable() && currentTempTaskBounds != null) {
            outStackBounds.set(currentTempTaskBounds);
            return;
        }

        if (dockedSide == DOCKED_INVALID) {
            // Not sure how you got here...Only thing we can do is return current bounds.
            Slog.e(TAG_WM, "Failed to get valid docked side for docked stack");
            outStackBounds.set(getRawBounds());
            return;
        }

        final boolean dockedOnTopOrLeft = dockedSide == DOCKED_TOP || dockedSide == DOCKED_LEFT;
        getStackDockedModeBounds(parentConfig,
                false /* primary */, outStackBounds, dockedBounds,
                mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
    }

    /**
     * Outputs the bounds a stack should be given the presence of a docked stack on the display.
     * @param parentConfig The parent configuration.
     * @param primary {@code true} if getting the primary stack bounds.
     * @param outBounds Output bounds that should be used for the stack.
     * @param dockedBounds Bounds of the docked stack.
     * @param dockDividerWidth We need to know the width of the divider make to the output bounds
     *                         close to the side of the dock.
     * @param dockOnTopOrLeft If the docked stack is on the top or left side of the screen.
     */
    private void getStackDockedModeBounds(Configuration parentConfig, boolean primary,
            Rect outBounds, Rect dockedBounds, int dockDividerWidth,
            boolean dockOnTopOrLeft) {
        final Rect displayRect = parentConfig.windowConfiguration.getBounds();
        final boolean splitHorizontally = displayRect.width() > displayRect.height();

        outBounds.set(displayRect);
        if (primary) {
            if (mWmService.mDockedStackCreateBounds != null) {
                outBounds.set(mWmService.mDockedStackCreateBounds);
                return;
            }

            // The initial bounds of the docked stack when it is created about half the screen space
            // and its bounds can be adjusted after that. The bounds of all other stacks are
            // adjusted to occupy whatever screen space the docked stack isn't occupying.
            final DisplayCutout displayCutout = mDisplayContent.getDisplayInfo().displayCutout;
            mDisplayContent.getDisplayPolicy().getStableInsetsLw(
                    parentConfig.windowConfiguration.getRotation(),
                    displayRect.width(), displayRect.height(), displayCutout, mTmpRect2);
            final int position = new DividerSnapAlgorithm(mWmService.mContext.getResources(),
                    displayRect.width(),
                    displayRect.height(),
                    dockDividerWidth,
                    parentConfig.orientation == ORIENTATION_PORTRAIT,
                    mTmpRect2).getMiddleTarget().position;

            if (dockOnTopOrLeft) {
                if (splitHorizontally) {
                    outBounds.right = position;
                } else {
                    outBounds.bottom = position;
                }
            } else {
                if (splitHorizontally) {
                    outBounds.left = position + dockDividerWidth;
                } else {
                    outBounds.top = position + dockDividerWidth;
                }
            }
            return;
        }

        // Other stacks occupy whatever space is left by the docked stack.
        if (!dockOnTopOrLeft) {
            if (splitHorizontally) {
                outBounds.right = dockedBounds.left - dockDividerWidth;
            } else {
                outBounds.bottom = dockedBounds.top - dockDividerWidth;
            }
        } else {
            if (splitHorizontally) {
                outBounds.left = dockedBounds.right + dockDividerWidth;
            } else {
                outBounds.top = dockedBounds.bottom + dockDividerWidth;
            }
        }
        DockedDividerUtils.sanitizeStackBounds(outBounds, !dockOnTopOrLeft);
    }

    void resetDockedStackToMiddle() {
        if (!inSplitScreenPrimaryWindowingMode()) {
            throw new IllegalStateException("Not a docked stack=" + this);
        }

        mWmService.mDockedStackCreateBounds = null;

        final Rect bounds = new Rect();
        final Rect tempBounds = new Rect();
        getStackDockedModeBounds(null /* dockedBounds */, null /* currentTempTaskBounds */,
                bounds, tempBounds);
        mStackSupervisor.resizeDockedStackLocked(bounds, null /* tempTaskBounds */,
                null /* tempTaskInsetBounds */, null /* tempOtherTaskBounds */,
                null /* tempOtherTaskInsetBounds */, false /* preserveWindows */,
                false /* deferResume */);
    }

    @Override
    void removeIfPossible() {
        if (isAnimating(TRANSITION | CHILDREN)) {
            mDeferRemoval = true;
            return;
        }
        removeImmediately();
    }

    /**
     * Adjusts the stack bounds if the IME is visible.
     *
     * @param imeWin The IME window.
     * @param keepLastAmount Use {@code true} to keep the last adjusted amount from
     *                       {@link DockedStackDividerController} for adjusting the stack bounds,
     *                       Use {@code false} to reset adjusted amount as 0.
     * @see #updateAdjustForIme(float, float, boolean)
     */
    void setAdjustedForIme(WindowState imeWin, boolean keepLastAmount) {
        mImeWin = imeWin;
        mImeGoingAway = false;
        if (!mAdjustedForIme || keepLastAmount) {
            mAdjustedForIme = true;
            DockedStackDividerController controller = getDisplayContent().mDividerControllerLocked;
            final float adjustImeAmount = keepLastAmount ? controller.mLastAnimationProgress : 0f;
            final float adjustDividerAmount = keepLastAmount ? controller.mLastDividerProgress : 0f;
            updateAdjustForIme(adjustImeAmount, adjustDividerAmount, true /* force */);
        }
    }

    boolean isAdjustedForIme() {
        return mAdjustedForIme;
    }

    boolean isAnimatingForIme() {
        return mImeWin != null && mImeWin.isAnimatingLw();
    }

    /**
     * Update the stack's bounds (crop or position) according to the IME window's
     * current position. When IME window is animated, the bottom stack is animated
     * together to track the IME window's current position, and the top stack is
     * cropped as necessary.
     *
     * @return true if a traversal should be performed after the adjustment.
     */
    boolean updateAdjustForIme(float adjustAmount, float adjustDividerAmount, boolean force) {
        if (adjustAmount != mAdjustImeAmount
                || adjustDividerAmount != mAdjustDividerAmount || force) {
            mAdjustImeAmount = adjustAmount;
            mAdjustDividerAmount = adjustDividerAmount;
            updateAdjustedBounds();
            return isVisible();
        } else {
            return false;
        }
    }

    /**
     * Resets the adjustment after it got adjusted for the IME.
     * @param adjustBoundsNow if true, reset and update the bounds immediately and forget about
     *                        animations; otherwise, set flag and animates the window away together
     *                        with IME window.
     */
    void resetAdjustedForIme(boolean adjustBoundsNow) {
        if (adjustBoundsNow) {
            mImeWin = null;
            mImeGoingAway = false;
            mAdjustImeAmount = 0f;
            mAdjustDividerAmount = 0f;
            if (!mAdjustedForIme) {
                return;
            }
            mAdjustedForIme = false;
            updateAdjustedBounds();
            mWmService.setResizeDimLayer(false, getWindowingMode(), 1.0f);
        } else {
            mImeGoingAway |= mAdjustedForIme;
        }
    }

    /**
     * Sets the amount how much we currently minimize our stack.
     *
     * @param minimizeAmount The amount, between 0 and 1.
     * @return Whether the amount has changed and a layout is needed.
     */
    boolean setAdjustedForMinimizedDock(float minimizeAmount) {
        if (minimizeAmount != mMinimizeAmount) {
            mMinimizeAmount = minimizeAmount;
            updateAdjustedBounds();
            return isVisible();
        } else {
            return false;
        }
    }

    boolean shouldIgnoreInput() {
        return isAdjustedForMinimizedDockedStack()
                || (inSplitScreenPrimaryWindowingMode() && isMinimizedDockAndHomeStackResizable());
    }

    /**
     * Puts all visible tasks that are adjusted for IME into resizing mode and adds the windows
     * to the list of to be drawn windows the service is waiting for.
     */
    void beginImeAdjustAnimation() {
        forAllTasks((t) -> {
            if (t.hasContentToDisplay()) {
                t.setDragResizing(true, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
                t.setWaitingForDrawnIfResizingChanged();
            }
        });
    }

    /** Resets the resizing state of all windows. */
    void endImeAdjustAnimation() {
        forAllTasks((t) -> { t.setDragResizing(false, DRAG_RESIZE_MODE_DOCKED_DIVIDER); });
    }

    private int getMinTopStackBottom(final Rect displayContentRect, int originalStackBottom) {
        return displayContentRect.top + (int)
                ((originalStackBottom - displayContentRect.top) * ADJUSTED_STACK_FRACTION_MIN);
    }

    private boolean adjustForIME(final WindowState imeWin) {
        // To prevent task stack resize animation may flicking when playing app transition
        // animation & IME window enter animation in parallel, we need to make sure app
        // transition is done and then adjust task size for IME, skip the new adjusted frame when
        // app transition is still running.
        if (getDisplayContent().mAppTransition.isRunning()) {
            return false;
        }

        final int dockedSide = getDockSide();
        final boolean dockedTopOrBottom = dockedSide == DOCKED_TOP || dockedSide == DOCKED_BOTTOM;
        if (imeWin == null || !dockedTopOrBottom) {
            return false;
        }

        final Rect displayStableRect = mTmpRect;
        final Rect contentBounds = mTmpRect2;

        // Calculate the content bounds excluding the area occupied by IME
        getDisplayContent().getStableRect(displayStableRect);
        contentBounds.set(displayStableRect);
        int imeTop = Math.max(imeWin.getFrameLw().top, contentBounds.top);

        imeTop += imeWin.getGivenContentInsetsLw().top;
        if (contentBounds.bottom > imeTop) {
            contentBounds.bottom = imeTop;
        }

        final int yOffset = displayStableRect.bottom - contentBounds.bottom;

        final int dividerWidth =
                getDisplayContent().mDividerControllerLocked.getContentWidth();
        final int dividerWidthInactive =
                getDisplayContent().mDividerControllerLocked.getContentWidthInactive();

        if (dockedSide == DOCKED_TOP) {
            // If this stack is docked on top, we make it smaller so the bottom stack is not
            // occluded by IME. We shift its bottom up by the height of the IME, but
            // leaves at least 30% of the top stack visible.
            final int minTopStackBottom =
                    getMinTopStackBottom(displayStableRect, getRawBounds().bottom);
            final int bottom = Math.max(
                    getRawBounds().bottom - yOffset + dividerWidth - dividerWidthInactive,
                    minTopStackBottom);
            mTmpAdjustedBounds.set(getRawBounds());
            mTmpAdjustedBounds.bottom = (int) (mAdjustImeAmount * bottom + (1 - mAdjustImeAmount)
                    * getRawBounds().bottom);
            mFullyAdjustedImeBounds.set(getRawBounds());
        } else {
            // When the stack is on bottom and has no focus, it's only adjusted for divider width.
            final int dividerWidthDelta = dividerWidthInactive - dividerWidth;

            // When the stack is on bottom and has focus, it needs to be moved up so as to
            // not occluded by IME, and at the same time adjusted for divider width.
            // We try to move it up by the height of the IME window, but only to the extent
            // that leaves at least 30% of the top stack visible.
            // 'top' is where the top of bottom stack will move to in this case.
            final int topBeforeImeAdjust =
                    getRawBounds().top - dividerWidth + dividerWidthInactive;
            final int minTopStackBottom =
                    getMinTopStackBottom(displayStableRect,
                            getRawBounds().top - dividerWidth);
            final int top = Math.max(
                    getRawBounds().top - yOffset, minTopStackBottom + dividerWidthInactive);

            mTmpAdjustedBounds.set(getRawBounds());
            // Account for the adjustment for IME and divider width separately.
            // (top - topBeforeImeAdjust) is the amount of movement due to IME only,
            // and dividerWidthDelta is due to divider width change only.
            mTmpAdjustedBounds.top =
                    getRawBounds().top + (int) (mAdjustImeAmount * (top - topBeforeImeAdjust)
                            + mAdjustDividerAmount * dividerWidthDelta);
            mFullyAdjustedImeBounds.set(getRawBounds());
            mFullyAdjustedImeBounds.top = top;
            mFullyAdjustedImeBounds.bottom = top + getRawBounds().height();
        }
        return true;
    }

    private boolean adjustForMinimizedDockedStack(float minimizeAmount) {
        final int dockSide = getDockSide();
        if (dockSide == DOCKED_INVALID && !mTmpAdjustedBounds.isEmpty()) {
            return false;
        }

        if (dockSide == DOCKED_TOP) {
            mWmService.getStableInsetsLocked(DEFAULT_DISPLAY, mTmpRect);
            int topInset = mTmpRect.top;
            mTmpAdjustedBounds.set(getRawBounds());
            mTmpAdjustedBounds.bottom = (int) (minimizeAmount * topInset + (1 - minimizeAmount)
                    * getRawBounds().bottom);
        } else if (dockSide == DOCKED_LEFT) {
            mTmpAdjustedBounds.set(getRawBounds());
            final int width = getRawBounds().width();
            mTmpAdjustedBounds.right =
                    (int) (minimizeAmount * mDockedStackMinimizeThickness
                            + (1 - minimizeAmount) * getRawBounds().right);
            mTmpAdjustedBounds.left = mTmpAdjustedBounds.right - width;
        } else if (dockSide == DOCKED_RIGHT) {
            mTmpAdjustedBounds.set(getRawBounds());
            mTmpAdjustedBounds.left =
                    (int) (minimizeAmount * (getRawBounds().right - mDockedStackMinimizeThickness)
                            + (1 - minimizeAmount) * getRawBounds().left);
        }
        return true;
    }

    boolean isMinimizedDockAndHomeStackResizable() {
        return mDisplayContent.mDividerControllerLocked.isMinimizedDock()
                && mDisplayContent.mDividerControllerLocked.isHomeStackResizable();
    }

    /**
     * @return the distance in pixels how much the stack gets minimized from it's original size
     */
    int getMinimizeDistance() {
        final int dockSide = getDockSide();
        if (dockSide == DOCKED_INVALID) {
            return 0;
        }

        if (dockSide == DOCKED_TOP) {
            mWmService.getStableInsetsLocked(DEFAULT_DISPLAY, mTmpRect);
            int topInset = mTmpRect.top;
            return getRawBounds().bottom - topInset;
        } else if (dockSide == DOCKED_LEFT || dockSide == DOCKED_RIGHT) {
            return getRawBounds().width() - mDockedStackMinimizeThickness;
        } else {
            return 0;
        }
    }

    /**
     * Updates the adjustment depending on it's current state.
     */
    private void updateAdjustedBounds() {
        boolean adjust = false;
        if (mMinimizeAmount != 0f) {
            adjust = adjustForMinimizedDockedStack(mMinimizeAmount);
        } else if (mAdjustedForIme) {
            adjust = adjustForIME(mImeWin);
        }
        if (!adjust) {
            mTmpAdjustedBounds.setEmpty();
        }
        setAdjustedBounds(mTmpAdjustedBounds);

        final boolean isImeTarget = (mWmService.getImeFocusStackLocked() == this);
        if (mAdjustedForIme && adjust && !isImeTarget) {
            final float alpha = Math.max(mAdjustImeAmount, mAdjustDividerAmount)
                    * IME_ADJUST_DIM_AMOUNT;
            mWmService.setResizeDimLayer(true, getWindowingMode(), alpha);
        }
    }

    void applyAdjustForImeIfNeeded(Task task) {
        if (mMinimizeAmount != 0f || !mAdjustedForIme || mAdjustedBounds.isEmpty()) {
            return;
        }

        final Rect insetBounds = mImeGoingAway ? getRawBounds() : mFullyAdjustedImeBounds;
        task.alignToAdjustedBounds(mAdjustedBounds, insetBounds, getDockSide() == DOCKED_TOP);
        mDisplayContent.setLayoutNeeded();
    }


    boolean isAdjustedForMinimizedDockedStack() {
        return mMinimizeAmount != 0f;
    }

    /**
     * @return {@code true} if we have a {@link Task} that is animating (currently only used for the
     *         recents animation); {@code false} otherwise.
     */
    boolean isTaskAnimating() {
        return getTask(Task::isTaskAnimating) != null;
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.println(prefix + "mStackId=" + mStackId);
        pw.println(prefix + "mDeferRemoval=" + mDeferRemoval);
        pw.println(prefix + "mBounds=" + getRawBounds().toShortString());
        if (mMinimizeAmount != 0f) {
            pw.println(prefix + "mMinimizeAmount=" + mMinimizeAmount);
        }
        if (mAdjustedForIme) {
            pw.println(prefix + "mAdjustedForIme=true");
            pw.println(prefix + "mAdjustImeAmount=" + mAdjustImeAmount);
            pw.println(prefix + "mAdjustDividerAmount=" + mAdjustDividerAmount);
        }
        if (!mAdjustedBounds.isEmpty()) {
            pw.println(prefix + "mAdjustedBounds=" + mAdjustedBounds.toShortString());
        }
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

    @Override
    boolean fillsParent() {
        return matchParentBounds();
    }

    String getName() {
        return toShortString();
    }

    public String toShortString() {
        return "Stack=" + mStackId;
    }

    /**
     * For docked workspace (or workspace that's side-by-side to the docked), provides
     * information which side of the screen was the dock anchored.
     */
    int getDockSide() {
        return getDockSide(mDisplayContent.getConfiguration(), getRawBounds());
    }

    int getDockSideForDisplay(DisplayContent dc) {
        return getDockSide(dc, dc.getConfiguration(), getRawBounds());
    }

    int getDockSide(Configuration parentConfig, Rect bounds) {
        if (mDisplayContent == null) {
            return DOCKED_INVALID;
        }
        return getDockSide(mDisplayContent, parentConfig, bounds);
    }

    private int getDockSide(DisplayContent dc, Configuration parentConfig, Rect bounds) {
        return dc.getDockedDividerController().getDockSide(bounds,
                parentConfig.windowConfiguration.getBounds(),
                parentConfig.orientation, parentConfig.windowConfiguration.getRotation());
    }

    boolean hasTaskForUser(int userId) {
        final PooledPredicate p = PooledLambda.obtainPredicate(
                Task::isTaskForUser, PooledLambda.__(Task.class), userId);
        final Task task = getTask(p);
        p.recycle();
        return task != null;
    }

    public boolean setPinnedStackSize(Rect stackBounds, Rect tempTaskBounds) {
        // Hold the lock since this is called from the BoundsAnimator running on the UiThread
        synchronized (mWmService.mGlobalLock) {
            if (mCancelCurrentBoundsAnimation) {
                return false;
            }
        }

        try {
            mWmService.mActivityTaskManager.resizePinnedStack(stackBounds, tempTaskBounds);
        } catch (RemoteException e) {
            // I don't believe you.
        }
        return true;
    }

    void onAllWindowsDrawn() {
        if (!mBoundsAnimating && !mBoundsAnimatingRequested) {
            return;
        }

        getDisplayContent().mBoundsAnimationController.onAllWindowsDrawn();
    }

    @Override  // AnimatesBounds
    public boolean onAnimationStart(boolean schedulePipModeChangedCallback, boolean forceUpdate,
            @BoundsAnimationController.AnimationType int animationType) {
        // Hold the lock since this is called from the BoundsAnimator running on the UiThread
        synchronized (mWmService.mGlobalLock) {
            if (!isAttached()) {
                // Don't run the animation if the stack is already detached
                return false;
            }

            if (animationType == BoundsAnimationController.BOUNDS) {
                mBoundsAnimatingRequested = false;
                mBoundsAnimating = true;
            }
            mAnimationType = animationType;

            // If we are changing UI mode, as in the PiP to fullscreen
            // transition, then we need to wait for the window to draw.
            if (schedulePipModeChangedCallback) {
                forAllWindows((w) -> {
                    w.mWinAnimator.resetDrawState();
                }, false /* traverseTopToBottom */);
            }
        }

        if (inPinnedWindowingMode()) {
            try {
                mWmService.mActivityTaskManager.notifyPinnedStackAnimationStarted();
            } catch (RemoteException e) {
                // I don't believe you...
            }

            if ((schedulePipModeChangedCallback || animationType == FADE_IN)) {
                // We need to schedule the PiP mode change before the animation up. It is possible
                // in this case for the animation down to not have been completed, so always
                // force-schedule and update to the client to ensure that it is notified that it
                // is no longer in picture-in-picture mode
                updatePictureInPictureModeForPinnedStackAnimation(null, forceUpdate);
            }
        }
        return true;
    }

    @Override  // AnimatesBounds
    public void onAnimationEnd(boolean schedulePipModeChangedCallback, Rect finalStackSize,
            boolean moveToFullscreen) {
        synchronized (mWmService.mGlobalLock) {
            if (inPinnedWindowingMode()) {
                // Update to the final bounds if requested. This is done here instead of in the
                // bounds animator to allow us to coordinate this after we notify the PiP mode
                // changed

                if (schedulePipModeChangedCallback) {
                    // We need to schedule the PiP mode change after the animation down, so use the
                    // final bounds
                    updatePictureInPictureModeForPinnedStackAnimation(mBoundsAnimationTarget,
                            false /* forceUpdate */);
                }

                if (mAnimationType == BoundsAnimationController.FADE_IN) {
                    setPinnedStackAlpha(1f);
                    mWmService.mAtmService.notifyPinnedStackAnimationEnded();
                    return;
                }

                if (finalStackSize != null && !mCancelCurrentBoundsAnimation) {
                    setPinnedStackSize(finalStackSize, null);
                } else {
                    // We have been canceled, so the final stack size is null, still run the
                    // animation-end logic
                    onPipAnimationEndResize();
                }

                mWmService.mAtmService.notifyPinnedStackAnimationEnded();
                if (moveToFullscreen) {
                    ((ActivityStack) this).dismissPip();
                }
            } else {
                // No PiP animation, just run the normal animation-end logic
                onPipAnimationEndResize();
            }
        }
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

    /** Called immediately prior to resizing the tasks at the end of the pinned stack animation. */
    void onPipAnimationEndResize() {
        mBoundsAnimating = false;
        forAllTasks(Task::clearPreserveNonFloatingState, false);
        mWmService.requestTraversal();
    }

    @Override
    public boolean shouldDeferStartOnMoveToFullscreen() {
        synchronized (mWmService.mGlobalLock) {
            if (!isAttached()) {
                // Unnecessary to pause the animation because the stack is detached.
                return false;
            }

            // Workaround for the recents animation -- normally we need to wait for the new activity
            // to show before starting the PiP animation, but because we start and show the home
            // activity early for the recents animation prior to the PiP animation starting, there
            // is no subsequent all-drawn signal. In this case, we can skip the pause when the home
            // stack is already visible and drawn.
            final ActivityStack homeStack = mDisplayContent.getHomeStack();
            if (homeStack == null) {
                return true;
            }
            final Task homeTask = homeStack.getTopMostTask();
            if (homeTask == null) {
                return true;
            }
            final ActivityRecord homeApp = homeTask.getTopVisibleActivity();
            if (!homeTask.isVisible() || homeApp == null) {
                return true;
            }
            return !homeApp.allDrawn;
        }
    }

    /**
     * @return True if we are currently animating the pinned stack from fullscreen to non-fullscreen
     *         bounds and we have a deferred PiP mode changed callback set with the animation.
     */
    public boolean deferScheduleMultiWindowModeChanged() {
        if (inPinnedWindowingMode()) {
            // For the pinned stack, the deferring of the multi-window mode changed is tied to the
            // transition animation into picture-in-picture, and is called once the animation
            // completes, or is interrupted in a way that would leave the stack in a non-fullscreen
            // state.
            // @see BoundsAnimationController
            // @see BoundsAnimationControllerTests
            return (mBoundsAnimatingRequested || mBoundsAnimating);
        }
        return false;
    }

    public boolean isForceScaled() {
        return mBoundsAnimating;
    }

    public boolean isAnimatingBounds() {
        return mBoundsAnimating;
    }

    public boolean lastAnimatingBoundsWasToFullscreen() {
        return mBoundsAnimatingToFullscreen;
    }

    public boolean isAnimatingBoundsToFullscreen() {
        return isAnimatingBounds() && lastAnimatingBoundsWasToFullscreen();
    }

    public boolean pinnedStackResizeDisallowed() {
        if (mBoundsAnimating && mCancelCurrentBoundsAnimation) {
            return true;
        }
        return false;
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

    @Override
    int getOrientation() {
        return (canSpecifyOrientation()) ? super.getOrientation() : SCREEN_ORIENTATION_UNSET;
    }

    private boolean canSpecifyOrientation() {
        final int windowingMode = getWindowingMode();
        final int activityType = getActivityType();
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                || activityType == ACTIVITY_TYPE_HOME
                || activityType == ACTIVITY_TYPE_RECENTS
                || activityType == ACTIVITY_TYPE_ASSISTANT;
    }

    @Override
    Dimmer getDimmer() {
        return mDimmer;
    }

    @Override
    void prepareSurfaces() {
        mDimmer.resetDimStates();
        super.prepareSurfaces();
        getDimBounds(mTmpDimBoundsRect);

        // Bounds need to be relative, as the dim layer is a child.
        mTmpDimBoundsRect.offsetTo(0, 0);
        if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
            scheduleAnimation();
        }
    }

    @Override
    public boolean setPinnedStackAlpha(float alpha) {
        // Hold the lock since this is called from the BoundsAnimator running on the UiThread
        synchronized (mWmService.mGlobalLock) {
            final SurfaceControl sc = getSurfaceControl();
            if (sc == null || !sc.isValid()) {
                // If the stack is already removed, don't bother updating any stack animation
                return false;
            }
            getPendingTransaction().setAlpha(sc, mCancelCurrentBoundsAnimation ? 1 : alpha);
            scheduleAnimation();
            return !mCancelCurrentBoundsAnimation;
        }
    }

    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    void dim(float alpha) {
        mDimmer.dimAbove(getPendingTransaction(), alpha);
        scheduleAnimation();
    }

    void stopDimming() {
        mDimmer.stopDim(getPendingTransaction());
        scheduleAnimation();
    }

    AnimatingActivityRegistry getAnimatingActivityRegistry() {
        return mAnimatingActivityRegistry;
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final Task task = getTopMostTask();
        if (task != null) {
            task.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        } else {
            super.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        }
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final Task task = getTopMostTask();
        return task != null ? task.createRemoteAnimationTarget(record) : null;
    }

    @Override
    public String toString() {
        return "ActivityStack{" + Integer.toHexString(System.identityHashCode(this))
                + " stackId=" + mStackId + " type=" + activityTypeToString(getActivityType())
                + " mode=" + windowingModeToString(getWindowingMode())
                + " visible=" + shouldBeVisible(null /* starting */)
                + " translucent=" + isStackTranslucent(null /* starting */)
                + ", "
                + getChildCount() + " tasks}";
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

        return display != null ? display.isSleeping() : mService.isSleepingLocked();
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mService.mShuttingDown;
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        dumpDebugInnerStackOnly(proto, STACK, logLevel);
        proto.write(com.android.server.am.ActivityStackProto.ID, mStackId);

        forAllTasks((t) -> {
            t.dumpDebug(proto, com.android.server.am.ActivityStackProto.TASKS, logLevel);
        });
        if (mResumedActivity != null) {
            mResumedActivity.writeIdentifierToProto(proto, RESUMED_ACTIVITY);
        }
        proto.write(DISPLAY_ID, mDisplayId);
        if (!matchParentBounds()) {
            final Rect bounds = getRequestedOverrideBounds();
            bounds.dumpDebug(proto, com.android.server.am.ActivityStackProto.BOUNDS);
        }

        // TODO: Remove, no longer needed with windowingMode.
        proto.write(FULLSCREEN, matchParentBounds());
        proto.end(token);
    }

    // TODO(proto-merge): Remove once protos for ActivityStack and TaskStack are merged.
    void dumpDebugInnerStackOnly(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);
        proto.write(StackProto.ID, mStackId);
        forAllTasks((t) -> { t.dumpDebugInnerTaskOnly(proto, StackProto.TASKS, logLevel); });
        proto.write(FILLS_PARENT, matchParentBounds());
        getRawBounds().dumpDebug(proto, StackProto.BOUNDS);
        proto.write(DEFER_REMOVAL, mDeferRemoval);
        proto.write(MINIMIZE_AMOUNT, mMinimizeAmount);
        proto.write(ADJUSTED_FOR_IME, mAdjustedForIme);
        proto.write(ADJUST_IME_AMOUNT, mAdjustImeAmount);
        proto.write(ADJUST_DIVIDER_AMOUNT, mAdjustDividerAmount);
        mAdjustedBounds.dumpDebug(proto, ADJUSTED_BOUNDS);
        proto.write(ANIMATING_BOUNDS, mBoundsAnimating);
        proto.end(token);
    }
}
