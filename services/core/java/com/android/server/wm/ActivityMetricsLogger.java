package com.android.server.wm;

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityManager.processStateAmToProto;
import static android.app.WaitResult.LAUNCH_STATE_COLD;
import static android.app.WaitResult.LAUNCH_STATE_HOT;
import static android.app.WaitResult.LAUNCH_STATE_WARM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_ACTIVITY_START;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_BIND_APPLICATION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_CALLING_PACKAGE_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_CANCELLED;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DEVICE_UPTIME_SECONDS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_IS_EPHEMERAL;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_PROCESS_RUNNING;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_REPORTED_DRAWN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_REPORTED_DRAWN_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_STARTING_WINDOW_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_PACKAGE_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_UID;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_UID_HAS_ANY_VISIBLE_WINDOW;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_UID_PROC_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CLASS_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_COMING_FROM_PENDING_INTENT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INSTANT_APP_LAUNCH_TOKEN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INTENT_ACTION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_CUR_PROC_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_CLIENT_ACTIVITIES;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_FOREGROUND_ACTIVITIES;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_FOREGROUND_SERVICES;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_OVERLAY_UI;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_TOP_UI;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_MILLIS_SINCE_FG_INTERACTION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_MILLIS_SINCE_LAST_INTERACTION_EVENT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_MILLIS_SINCE_UNIMPORTANT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_PENDING_UI_CLEAN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_PROCESS_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_REAL_CALLING_UID;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_REAL_CALLING_UID_HAS_ANY_VISIBLE_WINDOW;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_REAL_CALLING_UID_PROC_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TARGET_SHORT_COMPONENT_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PACKAGE_OPTIMIZATION_COMPILATION_FILTER;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PACKAGE_OPTIMIZATION_COMPILATION_REASON;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_COLD_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_HOT_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_WITH_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_WARM_LAUNCH;
import static com.android.server.am.EventLogTags.AM_ACTIVITY_LAUNCH_TIME;
import static com.android.server.am.MemoryStatUtil.MemoryStat;
import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_METRICS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_TIMEOUT;

import android.app.WaitResult;
import android.app.WindowConfiguration.WindowingMode;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.dex.ArtManagerInternal;
import android.content.pm.dex.PackageOptimizationInfo;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.server.LocalServices;

/**
 * Listens to activity launches, transitions, visibility changes and window drawn callbacks to
 * determine app launch times and draw delays. Source of truth for activity metrics and provides
 * data for Tron, logcat, event logs and {@link android.app.WaitResult}.
 *
 * Tests:
 * atest CtsWindowManagerDeviceTestCases:ActivityMetricsLoggerTests
 */
class ActivityMetricsLogger {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityMetricsLogger" : TAG_ATM;

    // Window modes we are interested in logging. If we ever introduce a new type, we need to add
    // a value here and increase the {@link #TRON_WINDOW_STATE_VARZ_STRINGS} array.
    private static final int WINDOW_STATE_STANDARD = 0;
    private static final int WINDOW_STATE_SIDE_BY_SIDE = 1;
    private static final int WINDOW_STATE_FREEFORM = 2;
    private static final int WINDOW_STATE_ASSISTANT = 3;
    private static final int WINDOW_STATE_INVALID = -1;

    private static final long INVALID_START_TIME = -1;
    private static final int INVALID_DELAY = -1;
    private static final int INVALID_TRANSITION_TYPE = -1;

    private static final int MSG_CHECK_VISIBILITY = 0;

    // Preallocated strings we are sending to tron, so we don't have to allocate a new one every
    // time we log.
    private static final String[] TRON_WINDOW_STATE_VARZ_STRINGS = {
            "window_time_0", "window_time_1", "window_time_2", "window_time_3"};

    private int mWindowState = WINDOW_STATE_STANDARD;
    private long mLastLogTimeSecs;
    private final ActivityStackSupervisor mSupervisor;
    private final Context mContext;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    // set to INVALID_START_TIME in reset.
    // set to valid value in notifyActivityLaunching
    private long mCurrentTransitionStartTime = INVALID_START_TIME;
    private long mLastTransitionStartTime = INVALID_START_TIME;

    private int mCurrentTransitionDeviceUptime;
    private int mCurrentTransitionDelayMs;

    /** If the any app transitions have been logged as starting, after the latest reset. */
    private boolean mLoggedTransitionStarting;

    /** Map : @WindowingMode int => WindowingModeTransitionInfo */
    private final SparseArray<WindowingModeTransitionInfo> mWindowingModeTransitionInfo =
            new SparseArray<>();
    /** Map : @WindowingMode int => WindowingModeTransitionInfo */
    private final SparseArray<WindowingModeTransitionInfo> mLastWindowingModeTransitionInfo =
            new SparseArray<>();
    private final H mHandler;

    private ArtManagerInternal mArtManagerInternal;
    private final StringBuilder mStringBuilder = new StringBuilder();

    /**
     * Due to the global single concurrent launch sequence, all calls to this observer must be made
     * in-order on the same thread to fulfill the "happens-before" guarantee in LaunchObserver.
     */
    private final LaunchObserverRegistryImpl mLaunchObserver;
    @VisibleForTesting static final int LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE = 512;

    private final class H extends Handler {

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CHECK_VISIBILITY:
                    final SomeArgs args = (SomeArgs) msg.obj;
                    checkVisibility((TaskRecord) args.arg1, (ActivityRecord) args.arg2);
                    break;
            }
        }
    }

    private final class WindowingModeTransitionInfo {
        /** The latest activity to have been launched. */
        private ActivityRecord launchedActivity;
        private int startResult;
        private boolean currentTransitionProcessRunning;
        /** Elapsed time from when we launch an activity to when its windows are drawn. */
        private int windowsDrawnDelayMs;
        private int startingWindowDelayMs = INVALID_DELAY;
        private int bindApplicationDelayMs = INVALID_DELAY;
        private int reason = APP_TRANSITION_TIMEOUT;
        private boolean loggedWindowsDrawn;
        private boolean loggedStartingWindowDrawn;
        private boolean launchTraceActive;
    }

    final class WindowingModeTransitionInfoSnapshot {
        final private ApplicationInfo applicationInfo;
        final private WindowProcessController processRecord;
        final String packageName;
        final String launchedActivityName;
        final private String launchedActivityLaunchedFromPackage;
        final private String launchedActivityLaunchToken;
        final private String launchedActivityAppRecordRequiredAbi;
        final String launchedActivityShortComponentName;
        final private String processName;
        final private int reason;
        final private int startingWindowDelayMs;
        final private int bindApplicationDelayMs;
        final int windowsDrawnDelayMs;
        final int type;
        final int userId;
        /**
         * Elapsed time from when we launch an activity to when the app reported it was
         * fully drawn. If this is not reported then the value is set to INVALID_DELAY.
         */
        final int windowsFullyDrawnDelayMs;
        final int activityRecordIdHashCode;

        private WindowingModeTransitionInfoSnapshot(WindowingModeTransitionInfo info) {
            this(info, info.launchedActivity);
        }

        private WindowingModeTransitionInfoSnapshot(WindowingModeTransitionInfo info,
                ActivityRecord launchedActivity) {
            this(info, launchedActivity, INVALID_DELAY);
        }

        private WindowingModeTransitionInfoSnapshot(WindowingModeTransitionInfo info,
                ActivityRecord launchedActivity, int windowsFullyDrawnDelayMs) {
            applicationInfo = launchedActivity.appInfo;
            packageName = launchedActivity.packageName;
            launchedActivityName = launchedActivity.info.name;
            launchedActivityLaunchedFromPackage = launchedActivity.launchedFromPackage;
            launchedActivityLaunchToken = launchedActivity.info.launchToken;
            launchedActivityAppRecordRequiredAbi = launchedActivity.app == null
                    ? null
                    : launchedActivity.app.getRequiredAbi();
            reason = info.reason;
            startingWindowDelayMs = info.startingWindowDelayMs;
            bindApplicationDelayMs = info.bindApplicationDelayMs;
            windowsDrawnDelayMs = info.windowsDrawnDelayMs;
            type = getTransitionType(info);
            processRecord = findProcessForActivity(launchedActivity);
            processName = launchedActivity.processName;
            userId = launchedActivity.mUserId;
            launchedActivityShortComponentName = launchedActivity.shortComponentName;
            activityRecordIdHashCode = System.identityHashCode(launchedActivity);
            this.windowsFullyDrawnDelayMs = windowsFullyDrawnDelayMs;
        }

        @WaitResult.LaunchState int getLaunchState() {
            switch (type) {
                case TYPE_TRANSITION_WARM_LAUNCH:
                    return LAUNCH_STATE_WARM;
                case TYPE_TRANSITION_HOT_LAUNCH:
                    return LAUNCH_STATE_HOT;
                case TYPE_TRANSITION_COLD_LAUNCH:
                    return LAUNCH_STATE_COLD;
                default:
                    return -1;
            }
        }
    }

    ActivityMetricsLogger(ActivityStackSupervisor supervisor, Context context, Looper looper) {
        mLastLogTimeSecs = SystemClock.elapsedRealtime() / 1000;
        mSupervisor = supervisor;
        mContext = context;
        mHandler = new H(looper);
        mLaunchObserver = new LaunchObserverRegistryImpl(looper);
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

        mWindowState = WINDOW_STATE_INVALID;
        ActivityStack stack =
                mSupervisor.mRootActivityContainer.getTopDisplayFocusedStack();
        if (stack == null) {
            return;
        }

        if (stack.isActivityTypeAssistant()) {
            mWindowState = WINDOW_STATE_ASSISTANT;
            return;
        }

        @WindowingMode int windowingMode = stack.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_PINNED) {
            stack = mSupervisor.mRootActivityContainer.findStackBehind(stack);
            windowingMode = stack.getWindowingMode();
        }
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                mWindowState = WINDOW_STATE_STANDARD;
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                mWindowState = WINDOW_STATE_SIDE_BY_SIDE;
                break;
            case WINDOWING_MODE_FREEFORM:
                mWindowState = WINDOW_STATE_FREEFORM;
                break;
            default:
                if (windowingMode != WINDOWING_MODE_UNDEFINED) {
                    throw new IllegalStateException("Unknown windowing mode for stack=" + stack
                            + " windowingMode=" + windowingMode);
                }
        }
    }

    /**
     * Notifies the tracker at the earliest possible point when we are starting to launch an
     * activity.
     */
    void notifyActivityLaunching(Intent intent) {
        if (DEBUG_METRICS) {
            Slog.i(TAG, String.format("notifyActivityLaunching: active:%b, intent:%s",
                                      isAnyTransitionActive(),
                                      intent));
        }

        if (mCurrentTransitionStartTime == INVALID_START_TIME) {

            mCurrentTransitionStartTime = SystemClock.uptimeMillis();
            mLastTransitionStartTime = mCurrentTransitionStartTime;

            launchObserverNotifyIntentStarted(intent);
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
        final WindowProcessController processRecord = findProcessForActivity(launchedActivity);
        final boolean processRunning = processRecord != null;

        // We consider this a "process switch" if the process of the activity that gets launched
        // didn't have an activity that was in started state. In this case, we assume that lot
        // of caches might be purged so the time until it produces the first frame is very
        // interesting.
        final boolean processSwitch = processRecord == null
                || !processRecord.hasStartedActivity(launchedActivity);

        notifyActivityLaunched(resultCode, launchedActivity, processRunning, processSwitch);
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

        if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunched"
                + " resultCode=" + resultCode
                + " launchedActivity=" + launchedActivity
                + " processRunning=" + processRunning
                + " processSwitch=" + processSwitch);

        // If we are already in an existing transition, only update the activity name, but not the
        // other attributes.
        final @WindowingMode int windowingMode = launchedActivity != null
                ? launchedActivity.getWindowingMode()
                : WINDOWING_MODE_UNDEFINED;
        final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(windowingMode);
        if (mCurrentTransitionStartTime == INVALID_START_TIME) {
            // No transition is active ignore this launch.
            return;
        }

        if (launchedActivity != null && launchedActivity.mDrawn) {
            // Launched activity is already visible. We cannot measure windows drawn delay.
            reset(true /* abort */, info, "launched activity already visible");
            return;
        }

        if (launchedActivity != null && info != null) {
            // If we are already in an existing transition, only update the activity name, but not
            // the other attributes.

            // Coalesce multiple (trampoline) activities from a single sequence together.
            info.launchedActivity = launchedActivity;
            return;
        }

        final boolean otherWindowModesLaunching =
                mWindowingModeTransitionInfo.size() > 0 && info == null;
        if ((!isLoggableResultCode(resultCode) || launchedActivity == null || !processSwitch
                || windowingMode == WINDOWING_MODE_UNDEFINED) && !otherWindowModesLaunching) {
            // Failed to launch or it was not a process switch, so we don't care about the timing.
            reset(true /* abort */, info, "failed to launch or not a process switch");
            return;
        } else if (otherWindowModesLaunching) {
            // Don't log this windowing mode but continue with the other windowing modes.
            return;
        }

        if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunched successful");

        // A new launch sequence [with the windowingMode] has begun.
        // Start tracking it.
        final WindowingModeTransitionInfo newInfo = new WindowingModeTransitionInfo();
        newInfo.launchedActivity = launchedActivity;
        newInfo.currentTransitionProcessRunning = processRunning;
        newInfo.startResult = resultCode;
        mWindowingModeTransitionInfo.put(windowingMode, newInfo);
        mLastWindowingModeTransitionInfo.put(windowingMode, newInfo);
        mCurrentTransitionDeviceUptime = (int) (SystemClock.uptimeMillis() / 1000);
        startTraces(newInfo);
        launchObserverNotifyActivityLaunched(newInfo);
    }

    /**
     * @return True if we should start logging an event for an activity start that returned
     *         {@code resultCode} and that we'll indeed get a windows drawn event.
     */
    private boolean isLoggableResultCode(int resultCode) {
        return resultCode == START_SUCCESS || resultCode == START_TASK_TO_FRONT;
    }

    /**
     * Notifies the tracker that all windows of the app have been drawn.
     */
    WindowingModeTransitionInfoSnapshot notifyWindowsDrawn(@WindowingMode int windowingMode,
                                                           long timestamp) {
        if (DEBUG_METRICS) Slog.i(TAG, "notifyWindowsDrawn windowingMode=" + windowingMode);

        final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(windowingMode);
        if (info == null || info.loggedWindowsDrawn) {
            return null;
        }
        info.windowsDrawnDelayMs = calculateDelay(timestamp);
        info.loggedWindowsDrawn = true;
        final WindowingModeTransitionInfoSnapshot infoSnapshot =
                new WindowingModeTransitionInfoSnapshot(info);
        if (allWindowsDrawn() && mLoggedTransitionStarting) {
            reset(false /* abort */, info, "notifyWindowsDrawn - all windows drawn");
        }
        return infoSnapshot;
    }

    /**
     * Notifies the tracker that the starting window was drawn.
     */
    void notifyStartingWindowDrawn(@WindowingMode int windowingMode, long timestamp) {
        final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(windowingMode);
        if (info == null || info.loggedStartingWindowDrawn) {
            return;
        }
        info.loggedStartingWindowDrawn = true;
        info.startingWindowDelayMs = calculateDelay(timestamp);
    }

    /**
     * Notifies the tracker that the app transition is starting.
     *
     * @param windowingModeToReason A map from windowing mode to a reason integer, which must be on
     *                              of ActivityTaskManagerInternal.APP_TRANSITION_* reasons.
     */
    void notifyTransitionStarting(SparseIntArray windowingModeToReason, long timestamp) {
        if (!isAnyTransitionActive() || mLoggedTransitionStarting) {
            // Ignore calls to this made after a reset and prior to notifyActivityLaunching.

            // Ignore any subsequent notifyTransitionStarting until the next reset.
            return;
        }
        if (DEBUG_METRICS) Slog.i(TAG, "notifyTransitionStarting");
        mCurrentTransitionDelayMs = calculateDelay(timestamp);
        mLoggedTransitionStarting = true;

        WindowingModeTransitionInfo foundInfo = null;
        for (int index = windowingModeToReason.size() - 1; index >= 0; index--) {
            final @WindowingMode int windowingMode = windowingModeToReason.keyAt(index);
            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(
                    windowingMode);
            if (info == null) {
                continue;
            }
            info.reason = windowingModeToReason.valueAt(index);
            foundInfo = info;
        }
        if (allWindowsDrawn()) {
            // abort metrics collection if we cannot find a matching transition.
            final boolean abortMetrics = foundInfo == null;
            reset(abortMetrics, foundInfo, "notifyTransitionStarting - all windows drawn");
        }
    }

    /**
     * Notifies the tracker that the visibility of an app is changing.
     *
     * @param activityRecord the app that is changing its visibility
     */
    void notifyVisibilityChanged(ActivityRecord activityRecord) {
        final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(
                activityRecord.getWindowingMode());
        if (info == null) {
            return;
        }
        if (info.launchedActivity != activityRecord) {
            return;
        }
        final TaskRecord t = activityRecord.getTaskRecord();
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = t;
        args.arg2 = activityRecord;
        mHandler.obtainMessage(MSG_CHECK_VISIBILITY, args).sendToTarget();
    }

    private boolean hasVisibleNonFinishingActivity(TaskRecord t) {
        for (int i = t.mActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord r = t.mActivities.get(i);
            if (r.visible && !r.finishing) {
                return true;
            }
        }
        return false;
    }

    private void checkVisibility(TaskRecord t, ActivityRecord r) {
        synchronized (mSupervisor.mService.mGlobalLock) {

            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(
                    r.getWindowingMode());

            // If we have an active transition that's waiting on a certain activity that will be
            // invisible now, we'll never get onWindowsDrawn, so abort the transition if necessary.

            // We have no active transitions.
            if (info == null) {
                return;
            }

            // The notified activity whose visibility changed is no longer the launched activity.
            // We can still wait to get onWindowsDrawn.
            if (info.launchedActivity != r) {
                return;
            }

            // Check if there is any activity in the task that is visible and not finishing. If the
            // launched activity finished before it is drawn and if there is another activity in
            // the task then that activity will be draw on screen.
            if (hasVisibleNonFinishingActivity(t)) {
                return;
            }

            if (DEBUG_METRICS) Slog.i(TAG, "notifyVisibilityChanged to invisible activity=" + r);
            logAppTransitionCancel(info);
            mWindowingModeTransitionInfo.remove(r.getWindowingMode());
            if (mWindowingModeTransitionInfo.size() == 0) {
                reset(true /* abort */, info, "notifyVisibilityChanged to invisible");
            }
        }
    }

    /**
     * Notifies the tracker that we called immediately before we call bindApplication on the client.
     *
     * @param appInfo The client into which we'll call bindApplication.
     */
    void notifyBindApplication(ApplicationInfo appInfo) {
        for (int i = mWindowingModeTransitionInfo.size() - 1; i >= 0; i--) {
            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.valueAt(i);

            // App isn't attached to record yet, so match with info.
            if (info.launchedActivity.appInfo == appInfo) {
                info.bindApplicationDelayMs = calculateCurrentDelay();
            }
        }
    }

    private boolean allWindowsDrawn() {
        for (int index = mWindowingModeTransitionInfo.size() - 1; index >= 0; index--) {
            if (!mWindowingModeTransitionInfo.valueAt(index).loggedWindowsDrawn) {
                return false;
            }
        }
        return true;
    }

    private boolean isAnyTransitionActive() {
        return mCurrentTransitionStartTime != INVALID_START_TIME
                && mWindowingModeTransitionInfo.size() > 0;
    }

    private void reset(boolean abort, WindowingModeTransitionInfo info, String cause) {
        if (DEBUG_METRICS) Slog.i(TAG, "reset abort=" + abort + ",cause=" + cause);
        if (!abort && isAnyTransitionActive()) {
            logAppTransitionMultiEvents();
        }
        stopLaunchTrace(info);

        // Ignore reset-after reset.
        if (isAnyTransitionActive()) {
            // LaunchObserver callbacks.
            if (abort) {
                launchObserverNotifyActivityLaunchCancelled(info);
            } else {
                launchObserverNotifyActivityLaunchFinished(info);
            }
        } else {
            launchObserverNotifyIntentFailed();
        }

        mCurrentTransitionStartTime = INVALID_START_TIME;
        mCurrentTransitionDelayMs = INVALID_DELAY;
        mLoggedTransitionStarting = false;
        mWindowingModeTransitionInfo.clear();
    }

    private int calculateCurrentDelay() {
        // Shouldn't take more than 25 days to launch an app, so int is fine here.
        return (int) (SystemClock.uptimeMillis() - mCurrentTransitionStartTime);
    }

    private int calculateDelay(long timestamp) {
        // Shouldn't take more than 25 days to launch an app, so int is fine here.
        return (int) (timestamp - mCurrentTransitionStartTime);
    }

    private void logAppTransitionCancel(WindowingModeTransitionInfo info) {
        final int type = getTransitionType(info);
        if (type == INVALID_TRANSITION_TYPE) {
            return;
        }
        final LogMaker builder = new LogMaker(APP_TRANSITION_CANCELLED);
        builder.setPackageName(info.launchedActivity.packageName);
        builder.setType(type);
        builder.addTaggedData(FIELD_CLASS_NAME, info.launchedActivity.info.name);
        mMetricsLogger.write(builder);
        StatsLog.write(
                StatsLog.APP_START_CANCELED,
                info.launchedActivity.appInfo.uid,
                info.launchedActivity.packageName,
                convertAppStartTransitionType(type),
                info.launchedActivity.info.name);
        if (DEBUG_METRICS) {
            Slog.i(TAG, String.format("APP_START_CANCELED(%s, %s, %s, %s)",
                    info.launchedActivity.appInfo.uid,
                    info.launchedActivity.packageName,
                    convertAppStartTransitionType(type),
                    info.launchedActivity.info.name));
        }
    }

    private void logAppTransitionMultiEvents() {
        if (DEBUG_METRICS) Slog.i(TAG, "logging transition events");
        for (int index = mWindowingModeTransitionInfo.size() - 1; index >= 0; index--) {
            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.valueAt(index);
            final int type = getTransitionType(info);
            if (type == INVALID_TRANSITION_TYPE) {
                if (DEBUG_METRICS) {
                    Slog.i(TAG, "invalid transition type"
                            + " processRunning=" + info.currentTransitionProcessRunning
                            + " startResult=" + info.startResult);
                }
                return;
            }

            // Take a snapshot of the transition info before sending it to the handler for logging.
            // This will avoid any races with other operations that modify the ActivityRecord.
            final WindowingModeTransitionInfoSnapshot infoSnapshot =
                    new WindowingModeTransitionInfoSnapshot(info);
            final int currentTransitionDeviceUptime = mCurrentTransitionDeviceUptime;
            final int currentTransitionDelayMs = mCurrentTransitionDelayMs;
            BackgroundThread.getHandler().post(() -> logAppTransition(
                    currentTransitionDeviceUptime, currentTransitionDelayMs, infoSnapshot));
            BackgroundThread.getHandler().post(() -> logAppDisplayed(infoSnapshot));

            info.launchedActivity.info.launchToken = null;
        }
    }

    // This gets called on a background thread without holding the activity manager lock.
    private void logAppTransition(int currentTransitionDeviceUptime, int currentTransitionDelayMs,
            WindowingModeTransitionInfoSnapshot info) {
        final LogMaker builder = new LogMaker(APP_TRANSITION);
        builder.setPackageName(info.packageName);
        builder.setType(info.type);
        builder.addTaggedData(FIELD_CLASS_NAME, info.launchedActivityName);
        final boolean isInstantApp = info.applicationInfo.isInstantApp();
        if (info.launchedActivityLaunchedFromPackage != null) {
            builder.addTaggedData(APP_TRANSITION_CALLING_PACKAGE_NAME,
                    info.launchedActivityLaunchedFromPackage);
        }
        String launchToken = info.launchedActivityLaunchToken;
        if (launchToken != null) {
            builder.addTaggedData(FIELD_INSTANT_APP_LAUNCH_TOKEN, launchToken);
        }
        builder.addTaggedData(APP_TRANSITION_IS_EPHEMERAL, isInstantApp ? 1 : 0);
        builder.addTaggedData(APP_TRANSITION_DEVICE_UPTIME_SECONDS,
                currentTransitionDeviceUptime);
        builder.addTaggedData(APP_TRANSITION_DELAY_MS, currentTransitionDelayMs);
        builder.setSubtype(info.reason);
        if (info.startingWindowDelayMs != INVALID_DELAY) {
            builder.addTaggedData(APP_TRANSITION_STARTING_WINDOW_DELAY_MS,
                    info.startingWindowDelayMs);
        }
        if (info.bindApplicationDelayMs != INVALID_DELAY) {
            builder.addTaggedData(APP_TRANSITION_BIND_APPLICATION_DELAY_MS,
                    info.bindApplicationDelayMs);
        }
        builder.addTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS, info.windowsDrawnDelayMs);
        final ArtManagerInternal artManagerInternal = getArtManagerInternal();
        final PackageOptimizationInfo packageOptimizationInfo =
                (artManagerInternal == null) || (info.launchedActivityAppRecordRequiredAbi == null)
                ? PackageOptimizationInfo.createWithNoInfo()
                : artManagerInternal.getPackageOptimizationInfo(
                        info.applicationInfo,
                        info.launchedActivityAppRecordRequiredAbi);
        builder.addTaggedData(PACKAGE_OPTIMIZATION_COMPILATION_REASON,
                packageOptimizationInfo.getCompilationReason());
        builder.addTaggedData(PACKAGE_OPTIMIZATION_COMPILATION_FILTER,
                packageOptimizationInfo.getCompilationFilter());
        mMetricsLogger.write(builder);
        StatsLog.write(
                StatsLog.APP_START_OCCURRED,
                info.applicationInfo.uid,
                info.packageName,
                convertAppStartTransitionType(info.type),
                info.launchedActivityName,
                info.launchedActivityLaunchedFromPackage,
                isInstantApp,
                currentTransitionDeviceUptime * 1000,
                info.reason,
                currentTransitionDelayMs,
                info.startingWindowDelayMs,
                info.bindApplicationDelayMs,
                info.windowsDrawnDelayMs,
                launchToken,
                packageOptimizationInfo.getCompilationReason(),
                packageOptimizationInfo.getCompilationFilter());

        if (DEBUG_METRICS) {
            Slog.i(TAG, String.format("APP_START_OCCURRED(%s, %s, %s, %s, %s)",
                    info.applicationInfo.uid,
                    info.packageName,
                    convertAppStartTransitionType(info.type),
                    info.launchedActivityName,
                    info.launchedActivityLaunchedFromPackage));
        }


        logAppStartMemoryStateCapture(info);
    }

    private void logAppDisplayed(WindowingModeTransitionInfoSnapshot info) {
        if (info.type != TYPE_TRANSITION_WARM_LAUNCH && info.type != TYPE_TRANSITION_COLD_LAUNCH) {
            return;
        }

        EventLog.writeEvent(AM_ACTIVITY_LAUNCH_TIME,
                info.userId, info.activityRecordIdHashCode, info.launchedActivityShortComponentName,
                info.windowsDrawnDelayMs);

        StringBuilder sb = mStringBuilder;
        sb.setLength(0);
        sb.append("Displayed ");
        sb.append(info.launchedActivityShortComponentName);
        sb.append(": ");
        TimeUtils.formatDuration(info.windowsDrawnDelayMs, sb);
        Log.i(TAG, sb.toString());
    }

    private int convertAppStartTransitionType(int tronType) {
        if (tronType == TYPE_TRANSITION_COLD_LAUNCH) {
            return StatsLog.APP_START_OCCURRED__TYPE__COLD;
        }
        if (tronType == TYPE_TRANSITION_WARM_LAUNCH) {
            return StatsLog.APP_START_OCCURRED__TYPE__WARM;
        }
        if (tronType == TYPE_TRANSITION_HOT_LAUNCH) {
            return StatsLog.APP_START_OCCURRED__TYPE__HOT;
        }
        return StatsLog.APP_START_OCCURRED__TYPE__UNKNOWN;
     }

    WindowingModeTransitionInfoSnapshot logAppTransitionReportedDrawn(ActivityRecord r,
            boolean restoredFromBundle) {
        final WindowingModeTransitionInfo info = mLastWindowingModeTransitionInfo.get(
                r.getWindowingMode());
        if (info == null) {
            return null;
        }

        // Record the handling of the reportFullyDrawn callback in the trace system. This is not
        // actually used to trace this function, but instead the logical task that this function
        // fullfils (handling reportFullyDrawn() callbacks).
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "ActivityManager:ReportingFullyDrawn " + info.launchedActivity.packageName);

        final LogMaker builder = new LogMaker(APP_TRANSITION_REPORTED_DRAWN);
        builder.setPackageName(r.packageName);
        builder.addTaggedData(FIELD_CLASS_NAME, r.info.name);
        long startupTimeMs = SystemClock.uptimeMillis() - mLastTransitionStartTime;
        builder.addTaggedData(APP_TRANSITION_REPORTED_DRAWN_MS, startupTimeMs);
        builder.setType(restoredFromBundle
                ? TYPE_TRANSITION_REPORTED_DRAWN_WITH_BUNDLE
                : TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE);
        builder.addTaggedData(APP_TRANSITION_PROCESS_RUNNING,
                info.currentTransitionProcessRunning ? 1 : 0);
        mMetricsLogger.write(builder);
        StatsLog.write(
                StatsLog.APP_START_FULLY_DRAWN,
                info.launchedActivity.appInfo.uid,
                info.launchedActivity.packageName,
                restoredFromBundle
                        ? StatsLog.APP_START_FULLY_DRAWN__TYPE__WITH_BUNDLE
                        : StatsLog.APP_START_FULLY_DRAWN__TYPE__WITHOUT_BUNDLE,
                info.launchedActivity.info.name,
                info.currentTransitionProcessRunning,
                startupTimeMs);

        // Ends the trace started at the beginning of this function. This is located here to allow
        // the trace slice to have a noticable duration.
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        final WindowingModeTransitionInfoSnapshot infoSnapshot =
                new WindowingModeTransitionInfoSnapshot(info, r, (int) startupTimeMs);
        BackgroundThread.getHandler().post(() -> logAppFullyDrawn(infoSnapshot));
        return infoSnapshot;
    }

    private void logAppFullyDrawn(WindowingModeTransitionInfoSnapshot info) {
        if (info.type != TYPE_TRANSITION_WARM_LAUNCH && info.type != TYPE_TRANSITION_COLD_LAUNCH) {
            return;
        }

        StringBuilder sb = mStringBuilder;
        sb.setLength(0);
        sb.append("Fully drawn ");
        sb.append(info.launchedActivityShortComponentName);
        sb.append(": ");
        TimeUtils.formatDuration(info.windowsFullyDrawnDelayMs, sb);
        Log.i(TAG, sb.toString());
    }

    void logAbortedBgActivityStart(Intent intent, WindowProcessController callerApp,
            int callingUid, String callingPackage, int callingUidProcState,
            boolean callingUidHasAnyVisibleWindow,
            int realCallingUid, int realCallingUidProcState,
            boolean realCallingUidHasAnyVisibleWindow,
            boolean comingFromPendingIntent) {

        final long nowElapsed = SystemClock.elapsedRealtime();
        final long nowUptime = SystemClock.uptimeMillis();
        final LogMaker builder = new LogMaker(ACTION_ACTIVITY_START);
        builder.setTimestamp(System.currentTimeMillis());
        builder.addTaggedData(FIELD_CALLING_UID, callingUid);
        builder.addTaggedData(FIELD_CALLING_PACKAGE_NAME, callingPackage);
        builder.addTaggedData(FIELD_CALLING_UID_PROC_STATE,
                processStateAmToProto(callingUidProcState));
        builder.addTaggedData(FIELD_CALLING_UID_HAS_ANY_VISIBLE_WINDOW,
                callingUidHasAnyVisibleWindow ? 1 : 0);
        builder.addTaggedData(FIELD_REAL_CALLING_UID, realCallingUid);
        builder.addTaggedData(FIELD_REAL_CALLING_UID_PROC_STATE,
                processStateAmToProto(realCallingUidProcState));
        builder.addTaggedData(FIELD_REAL_CALLING_UID_HAS_ANY_VISIBLE_WINDOW,
                realCallingUidHasAnyVisibleWindow ? 1 : 0);
        builder.addTaggedData(FIELD_COMING_FROM_PENDING_INTENT, comingFromPendingIntent ? 1 : 0);
        if (intent != null) {
            builder.addTaggedData(FIELD_INTENT_ACTION, intent.getAction());
            ComponentName component = intent.getComponent();
            if (component != null) {
                builder.addTaggedData(FIELD_TARGET_SHORT_COMPONENT_NAME,
                        component.flattenToShortString());
            }
        }
        if (callerApp != null) {
            builder.addTaggedData(FIELD_PROCESS_RECORD_PROCESS_NAME, callerApp.mName);
            builder.addTaggedData(FIELD_PROCESS_RECORD_CUR_PROC_STATE,
                    processStateAmToProto(callerApp.getCurrentProcState()));
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_CLIENT_ACTIVITIES,
                    callerApp.hasClientActivities() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_FOREGROUND_SERVICES,
                    callerApp.hasForegroundServices() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_FOREGROUND_ACTIVITIES,
                    callerApp.hasForegroundActivities() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_TOP_UI, callerApp.hasTopUi() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_OVERLAY_UI,
                    callerApp.hasOverlayUi() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_PENDING_UI_CLEAN,
                    callerApp.hasPendingUiClean() ? 1 : 0);
            if (callerApp.getInteractionEventTime() != 0) {
                builder.addTaggedData(FIELD_PROCESS_RECORD_MILLIS_SINCE_LAST_INTERACTION_EVENT,
                        (nowElapsed - callerApp.getInteractionEventTime()));
            }
            if (callerApp.getFgInteractionTime() != 0) {
                builder.addTaggedData(FIELD_PROCESS_RECORD_MILLIS_SINCE_FG_INTERACTION,
                        (nowElapsed - callerApp.getFgInteractionTime()));
            }
            if (callerApp.getWhenUnimportant() != 0) {
                builder.addTaggedData(FIELD_PROCESS_RECORD_MILLIS_SINCE_UNIMPORTANT,
                        (nowUptime - callerApp.getWhenUnimportant()));
            }
        }
        mMetricsLogger.write(builder);
    }

    private int getTransitionType(WindowingModeTransitionInfo info) {
        if (info.currentTransitionProcessRunning) {
            if (info.startResult == START_SUCCESS) {
                return TYPE_TRANSITION_WARM_LAUNCH;
            } else if (info.startResult == START_TASK_TO_FRONT) {
                return TYPE_TRANSITION_HOT_LAUNCH;
            }
        } else if (info.startResult == START_SUCCESS
                || (info.startResult == START_TASK_TO_FRONT)) {
            // TaskRecord may still exist when cold launching an activity and the start
            // result will be set to START_TASK_TO_FRONT. Treat this as a COLD launch.
            return TYPE_TRANSITION_COLD_LAUNCH;
        }
        return INVALID_TRANSITION_TYPE;
    }

    private void logAppStartMemoryStateCapture(WindowingModeTransitionInfoSnapshot info) {
        if (info.processRecord == null) {
            if (DEBUG_METRICS) Slog.i(TAG, "logAppStartMemoryStateCapture processRecord null");
            return;
        }

        final int pid = info.processRecord.getPid();
        final int uid = info.applicationInfo.uid;
        final MemoryStat memoryStat = readMemoryStatFromFilesystem(uid, pid);
        if (memoryStat == null) {
            if (DEBUG_METRICS) Slog.i(TAG, "logAppStartMemoryStateCapture memoryStat null");
            return;
        }

        StatsLog.write(
                StatsLog.APP_START_MEMORY_STATE_CAPTURED,
                uid,
                info.processName,
                info.launchedActivityName,
                memoryStat.pgfault,
                memoryStat.pgmajfault,
                memoryStat.rssInBytes,
                memoryStat.cacheInBytes,
                memoryStat.swapInBytes);
    }

    private WindowProcessController findProcessForActivity(ActivityRecord launchedActivity) {
        return launchedActivity != null
                ? mSupervisor.mService.mProcessNames.get(
                        launchedActivity.processName, launchedActivity.appInfo.uid)
                : null;
    }

    private ArtManagerInternal getArtManagerInternal() {
        if (mArtManagerInternal == null) {
            // Note that this may be null.
            // ArtManagerInternal is registered during PackageManagerService
            // initialization which happens after ActivityManagerService.
            mArtManagerInternal = LocalServices.getService(ArtManagerInternal.class);
        }
        return mArtManagerInternal;
    }

    /**
     * Starts traces for app launch.
     *
     * @param info
     * */
    private void startTraces(WindowingModeTransitionInfo info) {
        if (!Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER) || info == null
                || info.launchTraceActive) {
            return;
        }
        Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "launching: "
                + info.launchedActivity.packageName, 0);
        info.launchTraceActive = true;
    }

    private void stopLaunchTrace(WindowingModeTransitionInfo info) {
        if (info == null) {
            return;
        }
        if (info.launchTraceActive) {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, "launching: "
                    + info.launchedActivity.packageName, 0);
            info.launchTraceActive = false;
        }
    }

    public ActivityMetricsLaunchObserverRegistry getLaunchObserverRegistry() {
        return mLaunchObserver;
    }

    /** Notify the {@link ActivityMetricsLaunchObserver} that a new launch sequence has begun. */
    private void launchObserverNotifyIntentStarted(Intent intent) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyIntentStarted");

        // Beginning a launch is timing sensitive and so should be observed as soon as possible.
        mLaunchObserver.onIntentStarted(intent);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the previous launch sequence has
     * aborted due to intent failure (e.g. intent resolve failed or security error, etc) or
     * intent being delivered to the top running activity.
     */
    private void launchObserverNotifyIntentFailed() {
       Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyIntentFailed");

        mLaunchObserver.onIntentFailed();

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the current launch sequence's activity
     * has started.
     */
    private void launchObserverNotifyActivityLaunched(WindowingModeTransitionInfo info) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyActivityLaunched");

        @ActivityMetricsLaunchObserver.Temperature int temperature =
                convertTransitionTypeToLaunchObserverTemperature(getTransitionType(info));

        // Beginning a launch is timing sensitive and so should be observed as soon as possible.
        mLaunchObserver.onActivityLaunched(convertActivityRecordToProto(info.launchedActivity),
                                           temperature);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the current launch sequence is
     * cancelled.
     */
    private void launchObserverNotifyActivityLaunchCancelled(WindowingModeTransitionInfo info) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyActivityLaunchCancelled");

        final @ActivityMetricsLaunchObserver.ActivityRecordProto byte[] activityRecordProto =
                info != null ? convertActivityRecordToProto(info.launchedActivity) : null;

        mLaunchObserver.onActivityLaunchCancelled(activityRecordProto);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the current launch sequence's activity
     * has fully finished (successfully).
     */
    private void launchObserverNotifyActivityLaunchFinished(WindowingModeTransitionInfo info) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyActivityLaunchFinished");

        mLaunchObserver.onActivityLaunchFinished(
                convertActivityRecordToProto(info.launchedActivity));

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    @VisibleForTesting
    static @ActivityMetricsLaunchObserver.ActivityRecordProto byte[]
            convertActivityRecordToProto(ActivityRecord record) {
        // May take non-negligible amount of time to convert ActivityRecord into a proto,
        // so track the time.
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:convertActivityRecordToProto");

        // There does not appear to be a way to 'reset' a ProtoOutputBuffer stream,
        // so create a new one every time.
        final ProtoOutputStream protoOutputStream =
                new ProtoOutputStream(LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE);
        // Write this data out as the top-most ActivityRecordProto (i.e. it is not a sub-object).
        record.writeToProto(protoOutputStream);
        final byte[] bytes = protoOutputStream.getBytes();

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        return bytes;
    }

    private static @ActivityMetricsLaunchObserver.Temperature int
            convertTransitionTypeToLaunchObserverTemperature(int transitionType) {
        switch (transitionType) {
            case TYPE_TRANSITION_WARM_LAUNCH:
                return ActivityMetricsLaunchObserver.TEMPERATURE_WARM;
            case TYPE_TRANSITION_HOT_LAUNCH:
                return ActivityMetricsLaunchObserver.TEMPERATURE_HOT;
            case TYPE_TRANSITION_COLD_LAUNCH:
                return ActivityMetricsLaunchObserver.TEMPERATURE_COLD;
            default:
                return -1;
        }
    }
}
