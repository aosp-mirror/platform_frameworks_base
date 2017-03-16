package com.android.server.am;

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.app.ActivityManagerInternal.APP_TRANSITION_TIMEOUT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_ACTIVITY_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DEVICE_UPTIME_SECONDS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_STARTING_WINDOW_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_COLD_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_HOT_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_WARM_LAUNCH;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;

import android.app.ActivityManager.StackId;
import android.content.Context;
import android.metrics.LogMaker;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.logging.MetricsLogger;

import java.util.ArrayList;

/**
 * Handles logging into Tron.
 */
class ActivityMetricsLogger {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityMetricsLogger" : TAG_AM;

    // Window modes we are interested in logging. If we ever introduce a new type, we need to add
    // a value here and increase the {@link #TRON_WINDOW_STATE_VARZ_STRINGS} array.
    private static final int WINDOW_STATE_STANDARD = 0;
    private static final int WINDOW_STATE_SIDE_BY_SIDE = 1;
    private static final int WINDOW_STATE_FREEFORM = 2;
    private static final int WINDOW_STATE_ASSISTANT = 3;
    private static final int WINDOW_STATE_INVALID = -1;

    private static final long INVALID_START_TIME = -1;

    // Preallocated strings we are sending to tron, so we don't have to allocate a new one every
    // time we log.
    private static final String[] TRON_WINDOW_STATE_VARZ_STRINGS = {
            "window_time_0", "window_time_1", "window_time_2", "window_time_3"};

    private int mWindowState = WINDOW_STATE_STANDARD;
    private long mLastLogTimeSecs;
    private final ActivityStackSupervisor mSupervisor;
    private final Context mContext;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private long mCurrentTransitionStartTime = INVALID_START_TIME;

    private int mCurrentTransitionDeviceUptime;
    private int mCurrentTransitionDelayMs;
    private boolean mLoggedTransitionStarting;

    private final SparseArray<StackTransitionInfo> mStackTransitionInfo = new SparseArray<>();

    private final class StackTransitionInfo {
        private ActivityRecord launchedActivity;
        private int startResult;
        private boolean currentTransitionProcessRunning;
        private int windowsDrawnDelayMs;
        private int startingWindowDelayMs;
        private int reason = APP_TRANSITION_TIMEOUT;
        private boolean loggedWindowsDrawn;
        private boolean loggedStartingWindowDrawn;
    }

    ActivityMetricsLogger(ActivityStackSupervisor supervisor, Context context) {
        mLastLogTimeSecs = SystemClock.elapsedRealtime() / 1000;
        mSupervisor = supervisor;
        mContext = context;
    }

    void logWindowState() {
        final long now = SystemClock.elapsedRealtime() / 1000;
        if (mWindowState != WINDOW_STATE_INVALID) {
            // We log even if the window state hasn't changed, because the user might remain in
            // home/fullscreen move forever and we would like to track this kind of behavior
            // too.
            MetricsLogger.count(mContext, TRON_WINDOW_STATE_VARZ_STRINGS[mWindowState],
                    (int) (now - mLastLogTimeSecs));
        }
        mLastLogTimeSecs = now;

        ActivityStack stack = mSupervisor.getStack(DOCKED_STACK_ID);
        if (stack != null && stack.getStackVisibilityLocked(null) != STACK_INVISIBLE) {
            mWindowState = WINDOW_STATE_SIDE_BY_SIDE;
            return;
        }
        mWindowState = WINDOW_STATE_INVALID;
        stack = mSupervisor.getFocusedStack();
        if (stack.mStackId == PINNED_STACK_ID) {
            stack = mSupervisor.findStackBehind(stack);
        }
        if (StackId.isHomeOrRecentsStack(stack.mStackId)
                || stack.mStackId == FULLSCREEN_WORKSPACE_STACK_ID) {
            mWindowState = WINDOW_STATE_STANDARD;
        } else if (stack.mStackId == DOCKED_STACK_ID) {
            Slog.wtf(TAG, "Docked stack shouldn't be the focused stack, because it reported not"
                    + " being visible.");
            mWindowState = WINDOW_STATE_INVALID;
        } else if (stack.mStackId == FREEFORM_WORKSPACE_STACK_ID) {
            mWindowState = WINDOW_STATE_FREEFORM;
        } else if (stack.mStackId == ASSISTANT_STACK_ID) {
            mWindowState = WINDOW_STATE_ASSISTANT;
        } else if (StackId.isStaticStack(stack.mStackId)) {
            throw new IllegalStateException("Unknown stack=" + stack);
        }
    }

    /**
     * Notifies the tracker at the earliest possible point when we are starting to launch an
     * activity.
     */
    void notifyActivityLaunching() {
        if (!isAnyTransitionActive()) {
            mCurrentTransitionStartTime = System.currentTimeMillis();
        }
    }

    /**
     * Notifies the tracker that the activity is actually launching.
     *
     * @param resultCode one of the ActivityManager.START_* flags, indicating the result of the
     *                   launch
     * @param launchedActivity the activity that is being launched
     */
    void notifyActivityLaunched(int resultCode, ActivityRecord launchedActivity) {
        final ProcessRecord processRecord = launchedActivity != null
                ? mSupervisor.mService.mProcessNames.get(launchedActivity.processName,
                        launchedActivity.appInfo.uid)
                : null;
        final boolean processRunning = processRecord != null;

        // We consider this a "process switch" if the process of the activity that gets launched
        // didn't have an activity that was in started state. In this case, we assume that lot
        // of caches might be purged so the time until it produces the first frame is very
        // interesting.
        final boolean processSwitch = processRecord == null
                || !hasStartedActivity(processRecord, launchedActivity);

        notifyActivityLaunched(resultCode, launchedActivity, processRunning, processSwitch);
    }

    private boolean hasStartedActivity(ProcessRecord record, ActivityRecord launchedActivity) {
        final ArrayList<ActivityRecord> activities = record.activities;
        for (int i = activities.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = activities.get(i);
            if (launchedActivity == activity) {
                continue;
            }
            if (!activity.stopped) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notifies the tracker the the activity is actually launching.
     *
     * @param resultCode one of the ActivityManager.START_* flags, indicating the result of the
     *                   launch
     * @param launchedActivity the activity being launched
     * @param processRunning whether the process that will contains the activity is already running
     * @param processSwitch whether the process that will contain the activity didn't have any
     *                      activity that was stopped, i.e. the started activity is "switching"
     *                      processes
     */
    private void notifyActivityLaunched(int resultCode, ActivityRecord launchedActivity,
            boolean processRunning, boolean processSwitch) {

        // If we are already in an existing transition, only update the activity name, but not the
        // other attributes.
        final int stackId = launchedActivity != null && launchedActivity.getStack() != null
                ? launchedActivity.getStack().mStackId
                : INVALID_STACK_ID;
        final StackTransitionInfo info = mStackTransitionInfo.get(stackId);
        if (launchedActivity != null && info != null) {
            info.launchedActivity = launchedActivity;
            return;
        }

        final boolean otherStacksLaunching = mStackTransitionInfo.size() > 0 && info == null;
        if ((resultCode < 0 || launchedActivity == null || !processSwitch
                || stackId == INVALID_STACK_ID) && !otherStacksLaunching) {

            // Failed to launch or it was not a process switch, so we don't care about the timing.
            reset(true /* abort */);
            return;
        } else if (otherStacksLaunching) {
            // Don't log this stack but continue with the other stacks.
            return;
        }

        final StackTransitionInfo newInfo = new StackTransitionInfo();
        newInfo.launchedActivity = launchedActivity;
        newInfo.currentTransitionProcessRunning = processRunning;
        newInfo.startResult = resultCode;
        mStackTransitionInfo.append(stackId, newInfo);
        mCurrentTransitionDeviceUptime = (int) (SystemClock.uptimeMillis() / 1000);
    }

    /**
     * Notifies the tracker that all windows of the app have been drawn.
     */
    void notifyWindowsDrawn(int stackId) {
        final StackTransitionInfo info = mStackTransitionInfo.get(stackId);
        if (info == null || info.loggedWindowsDrawn) {
            return;
        }
        info.windowsDrawnDelayMs = calculateCurrentDelay();
        info.loggedWindowsDrawn = true;
        if (allStacksWindowsDrawn() && mLoggedTransitionStarting) {
            reset(false /* abort */);
        }
    }

    /**
     * Notifies the tracker that the starting window was drawn.
     */
    void notifyStartingWindowDrawn(int stackId) {
        final StackTransitionInfo info = mStackTransitionInfo.get(stackId);
        if (info == null || info.loggedStartingWindowDrawn) {
            return;
        }
        info.loggedStartingWindowDrawn = true;
        info.startingWindowDelayMs = calculateCurrentDelay();
    }

    /**
     * Notifies the tracker that the app transition is starting.
     *
     * @param stackIdReasons A map from stack id to a reason integer, which must be on of
     *                       ActivityManagerInternal.APP_TRANSITION_* reasons.
     */
    void notifyTransitionStarting(SparseIntArray stackIdReasons) {
        // TODO (b/36339388): Figure out why stackIdReasons can be null
        if (stackIdReasons == null || !isAnyTransitionActive() || mLoggedTransitionStarting) {
            return;
        }
        mCurrentTransitionDelayMs = calculateCurrentDelay();
        mLoggedTransitionStarting = true;
        for (int index = stackIdReasons.size() - 1; index >= 0; index--) {
            final int stackId = stackIdReasons.keyAt(index);
            final StackTransitionInfo info = mStackTransitionInfo.get(stackId);
            if (info == null) {
                continue;
            }
            info.reason = stackIdReasons.valueAt(index);
        }
        if (allStacksWindowsDrawn()) {
            reset(false /* abort */);
        }
    }

    private boolean allStacksWindowsDrawn() {
        for (int index = mStackTransitionInfo.size() - 1; index >= 0; index--) {
            if (!mStackTransitionInfo.valueAt(index).loggedWindowsDrawn) {
                return false;
            }
        }
        return true;
    }

    private boolean isAnyTransitionActive() {
        return mCurrentTransitionStartTime != INVALID_START_TIME
                && mStackTransitionInfo.size() > 0;
    }

    private void reset(boolean abort) {
        if (!abort && isAnyTransitionActive()) {
            logAppTransitionMultiEvents();
        }
        mCurrentTransitionStartTime = INVALID_START_TIME;
        mCurrentTransitionDelayMs = -1;
        mLoggedTransitionStarting = false;
        mStackTransitionInfo.clear();
    }

    private int calculateCurrentDelay() {

        // Shouldn't take more than 25 days to launch an app, so int is fine here.
        return (int) (System.currentTimeMillis() - mCurrentTransitionStartTime);
    }

    private void logAppTransitionMultiEvents() {
        for (int index = mStackTransitionInfo.size() - 1; index >= 0; index--) {
            final StackTransitionInfo info = mStackTransitionInfo.valueAt(index);
            final int type = getTransitionType(info);
            if (type == -1) {
                return;
            }
            final LogMaker builder = new LogMaker(APP_TRANSITION);
            builder.setPackageName(info.launchedActivity.packageName);
            builder.addTaggedData(APP_TRANSITION_ACTIVITY_NAME, info.launchedActivity.info.name);
            builder.setType(type);
            builder.addTaggedData(APP_TRANSITION_DEVICE_UPTIME_SECONDS,
                    mCurrentTransitionDeviceUptime);
            builder.addTaggedData(APP_TRANSITION_DELAY_MS, mCurrentTransitionDelayMs);
            builder.setSubtype(info.reason);
            if (info.startingWindowDelayMs != -1) {
                builder.addTaggedData(APP_TRANSITION_STARTING_WINDOW_DELAY_MS,
                        info.startingWindowDelayMs);
            }
            builder.addTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS, info.windowsDrawnDelayMs);
            mMetricsLogger.write(builder);
        }
    }

    private int getTransitionType(StackTransitionInfo info) {
        if (info.currentTransitionProcessRunning) {
            if (info.startResult == START_SUCCESS) {
                return TYPE_TRANSITION_WARM_LAUNCH;
            } else if (info.startResult == START_TASK_TO_FRONT) {
                return TYPE_TRANSITION_HOT_LAUNCH;
            }
        } else if (info.startResult == START_SUCCESS) {
            return TYPE_TRANSITION_COLD_LAUNCH;
        }
        return -1;
    }
}
