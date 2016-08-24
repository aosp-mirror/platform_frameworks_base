package com.android.server.am;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;

import android.annotation.Nullable;
import android.app.ActivityManager.StackId;
import android.content.Context;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;

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
    private static final int WINDOW_STATE_INVALID = -1;

    private static final long INVALID_START_TIME = -1;

    // Preallocated strings we are sending to tron, so we don't have to allocate a new one every
    // time we log.
    private static final String[] TRON_WINDOW_STATE_VARZ_STRINGS = {
            "window_time_0", "window_time_1", "window_time_2"};

    private int mWindowState = WINDOW_STATE_STANDARD;
    private long mLastLogTimeSecs;
    private final ActivityStackSupervisor mSupervisor;
    private final Context mContext;

    private long mCurrentTransitionStartTime = INVALID_START_TIME;
    private boolean mLoggedWindowsDrawn;
    private boolean mLoggedStartingWindowDrawn;
    private boolean mLoggedTransitionStarting;

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
        if (stack.mStackId == HOME_STACK_ID
                || stack.mStackId == FULLSCREEN_WORKSPACE_STACK_ID) {
            mWindowState = WINDOW_STATE_STANDARD;
        } else if (stack.mStackId == DOCKED_STACK_ID) {
            Slog.wtf(TAG, "Docked stack shouldn't be the focused stack, because it reported not"
                    + " being visible.");
            mWindowState = WINDOW_STATE_INVALID;
        } else if (stack.mStackId == FREEFORM_WORKSPACE_STACK_ID) {
            mWindowState = WINDOW_STATE_FREEFORM;
        } else if (StackId.isStaticStack(stack.mStackId)) {
            throw new IllegalStateException("Unknown stack=" + stack);
        }
    }

    /**
     * Notifies the tracker at the earliest possible point when we are starting to launch an
     * activity.
     */
    void notifyActivityLaunching() {
        mCurrentTransitionStartTime = System.currentTimeMillis();
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
        final String componentName = launchedActivity != null
                ? launchedActivity.shortComponentName
                : null;

        // We consider this a "process switch" if the process of the activity that gets launched
        // didn't have an activity that was in started state. In this case, we assume that lot
        // of caches might be purged so the time until it produces the first frame is very
        // interesting.
        final boolean processSwitch = processRecord == null
                || !hasStartedActivity(processRecord, launchedActivity);

        notifyActivityLaunched(resultCode, componentName, processRunning, processSwitch);
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
     * @param componentName the component name of the activity being launched
     * @param processRunning whether the process that will contains the activity is already running
     * @param processSwitch whether the process that will contain the activity didn't have any
     *                      activity that was stopped, i.e. the started activity is "switching"
     *                      processes
     */
    private void notifyActivityLaunched(int resultCode, @Nullable String componentName,
            boolean processRunning, boolean processSwitch) {

        if (resultCode < 0 || componentName == null || !processSwitch) {

            // Failed to launch or it was not a process switch, so we don't care about the timing.
            reset();
            return;
        }

        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_COMPONENT_NAME,
                componentName);
        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_PROCESS_RUNNING,
                processRunning);
        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_DEVICE_UPTIME_SECONDS,
                (int) (SystemClock.uptimeMillis() / 1000));
    }

    /**
     * Notifies the tracker that all windows of the app have been drawn.
     */
    void notifyWindowsDrawn() {
        if (!isTransitionActive() || mLoggedWindowsDrawn) {
            return;
        }
        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS,
                calculateCurrentDelay());
        mLoggedWindowsDrawn = true;
        if (mLoggedTransitionStarting) {
            reset();
        }
    }

    /**
     * Notifies the tracker that the starting window was drawn.
     */
    void notifyStartingWindowDrawn() {
        if (!isTransitionActive() || mLoggedStartingWindowDrawn) {
            return;
        }
        mLoggedStartingWindowDrawn = true;
        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_STARTING_WINDOW_DELAY_MS,
                calculateCurrentDelay());
    }

    /**
     * Notifies the tracker that the app transition is starting.
     *
     * @param reason The reason why we started it. Must be on of
     *               ActivityManagerInternal.APP_TRANSITION_* reasons.
     */
    void notifyTransitionStarting(int reason) {
        if (!isTransitionActive() || mLoggedTransitionStarting) {
            return;
        }
        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_REASON, reason);
        MetricsLogger.action(mContext, MetricsEvent.APP_TRANSITION_DELAY_MS,
                calculateCurrentDelay());
        mLoggedTransitionStarting = true;
        if (mLoggedWindowsDrawn) {
            reset();
        }
    }

    private boolean isTransitionActive() {
        return mCurrentTransitionStartTime != INVALID_START_TIME;
    }

    private void reset() {
        mCurrentTransitionStartTime = INVALID_START_TIME;
        mLoggedWindowsDrawn = false;
        mLoggedTransitionStarting = false;
        mLoggedStartingWindowDrawn = false;
    }

    private int calculateCurrentDelay() {

        // Shouldn't take more than 25 days to launch an app, so int is fine here.
        return (int) (System.currentTimeMillis() - mCurrentTransitionStartTime);
    }
}
