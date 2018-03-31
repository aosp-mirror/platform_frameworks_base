package com.android.server.am;

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityManagerInternal.APP_TRANSITION_TIMEOUT;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
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
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CLASS_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INSTANT_APP_LAUNCH_TOKEN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PACKAGE_OPTIMIZATION_COMPILATION_REASON;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PACKAGE_OPTIMIZATION_COMPILATION_FILTER;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_COLD_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_HOT_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_WITH_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_WARM_LAUNCH;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_METRICS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.MemoryStatUtil.MemoryStat;
import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.dex.ArtManagerInternal;
import android.content.pm.dex.PackageOptimizationInfo;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.server.LocalServices;

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

    private long mCurrentTransitionStartTime = INVALID_START_TIME;
    private long mLastTransitionStartTime = INVALID_START_TIME;

    private int mCurrentTransitionDeviceUptime;
    private int mCurrentTransitionDelayMs;
    private boolean mLoggedTransitionStarting;

    private final SparseArray<WindowingModeTransitionInfo> mWindowingModeTransitionInfo =
            new SparseArray<>();
    private final SparseArray<WindowingModeTransitionInfo> mLastWindowingModeTransitionInfo =
            new SparseArray<>();
    private final H mHandler;

    private ArtManagerInternal mArtManagerInternal;

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
        private ActivityRecord launchedActivity;
        private int startResult;
        private boolean currentTransitionProcessRunning;
        private int windowsDrawnDelayMs;
        private int startingWindowDelayMs = -1;
        private int bindApplicationDelayMs = -1;
        private int reason = APP_TRANSITION_TIMEOUT;
        private boolean loggedWindowsDrawn;
        private boolean loggedStartingWindowDrawn;
    }

    private final class WindowingModeTransitionInfoSnapshot {
        final private ApplicationInfo applicationInfo;
        final private ProcessRecord processRecord;
        final private String packageName;
        final private String launchedActivityName;
        final private String launchedActivityLaunchedFromPackage;
        final private String launchedActivityLaunchToken;
        final private String launchedActivityAppRecordRequiredAbi;
        final private String processName;
        final private int reason;
        final private int startingWindowDelayMs;
        final private int bindApplicationDelayMs;
        final private int windowsDrawnDelayMs;
        final private int type;

        private WindowingModeTransitionInfoSnapshot(WindowingModeTransitionInfo info) {
            applicationInfo = info.launchedActivity.appInfo;
            packageName = info.launchedActivity.packageName;
            launchedActivityName = info.launchedActivity.info.name;
            launchedActivityLaunchedFromPackage = info.launchedActivity.launchedFromPackage;
            launchedActivityLaunchToken = info.launchedActivity.info.launchToken;
            launchedActivityAppRecordRequiredAbi = info.launchedActivity.app == null
                    ? null
                    : info.launchedActivity.app.requiredAbi;
            reason = info.reason;
            startingWindowDelayMs = info.startingWindowDelayMs;
            bindApplicationDelayMs = info.bindApplicationDelayMs;
            windowsDrawnDelayMs = info.windowsDrawnDelayMs;
            type = getTransitionType(info);
            processRecord = findProcessForActivity(info.launchedActivity);
            processName = info.launchedActivity.processName;
        }
    }

    ActivityMetricsLogger(ActivityStackSupervisor supervisor, Context context, Looper looper) {
        mLastLogTimeSecs = SystemClock.elapsedRealtime() / 1000;
        mSupervisor = supervisor;
        mContext = context;
        mHandler = new H(looper);
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
        ActivityStack stack = mSupervisor.getFocusedStack();
        if (stack.isActivityTypeAssistant()) {
            mWindowState = WINDOW_STATE_ASSISTANT;
            return;
        }

        int windowingMode = stack.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_PINNED) {
            stack = mSupervisor.findStackBehind(stack);
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
    void notifyActivityLaunching() {
        if (!isAnyTransitionActive()) {
            if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunching");
            mCurrentTransitionStartTime = SystemClock.uptimeMillis();
            mLastTransitionStartTime = mCurrentTransitionStartTime;
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
        final ProcessRecord processRecord = findProcessForActivity(launchedActivity);
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

        if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunched"
                + " resultCode=" + resultCode
                + " launchedActivity=" + launchedActivity
                + " processRunning=" + processRunning
                + " processSwitch=" + processSwitch);

        // If we are already in an existing transition, only update the activity name, but not the
        // other attributes.
        final int windowingMode = launchedActivity != null
                ? launchedActivity.getWindowingMode()
                : WINDOWING_MODE_UNDEFINED;

        if (mCurrentTransitionStartTime == INVALID_START_TIME) {
            return;
        }

        final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(windowingMode);
        if (launchedActivity != null && info != null) {
            info.launchedActivity = launchedActivity;
            return;
        }

        final boolean otherWindowModesLaunching =
                mWindowingModeTransitionInfo.size() > 0 && info == null;
        if ((!isLoggableResultCode(resultCode) || launchedActivity == null || !processSwitch
                || windowingMode == WINDOWING_MODE_UNDEFINED) && !otherWindowModesLaunching) {

            // Failed to launch or it was not a process switch, so we don't care about the timing.
            reset(true /* abort */);
            return;
        } else if (otherWindowModesLaunching) {
            // Don't log this windowing mode but continue with the other windowing modes.
            return;
        }

        if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunched successful");

        final WindowingModeTransitionInfo newInfo = new WindowingModeTransitionInfo();
        newInfo.launchedActivity = launchedActivity;
        newInfo.currentTransitionProcessRunning = processRunning;
        newInfo.startResult = resultCode;
        mWindowingModeTransitionInfo.put(windowingMode, newInfo);
        mLastWindowingModeTransitionInfo.put(windowingMode, newInfo);
        mCurrentTransitionDeviceUptime = (int) (SystemClock.uptimeMillis() / 1000);
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
    void notifyWindowsDrawn(int windowingMode, long timestamp) {
        if (DEBUG_METRICS) Slog.i(TAG, "notifyWindowsDrawn windowingMode=" + windowingMode);

        final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(windowingMode);
        if (info == null || info.loggedWindowsDrawn) {
            return;
        }
        info.windowsDrawnDelayMs = calculateDelay(timestamp);
        info.loggedWindowsDrawn = true;
        if (allWindowsDrawn() && mLoggedTransitionStarting) {
            reset(false /* abort */);
        }
    }

    /**
     * Notifies the tracker that the starting window was drawn.
     */
    void notifyStartingWindowDrawn(int windowingMode, long timestamp) {
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
     *                              of ActivityManagerInternal.APP_TRANSITION_* reasons.
     */
    void notifyTransitionStarting(SparseIntArray windowingModeToReason, long timestamp) {
        if (!isAnyTransitionActive() || mLoggedTransitionStarting) {
            return;
        }
        if (DEBUG_METRICS) Slog.i(TAG, "notifyTransitionStarting");
        mCurrentTransitionDelayMs = calculateDelay(timestamp);
        mLoggedTransitionStarting = true;
        for (int index = windowingModeToReason.size() - 1; index >= 0; index--) {
            final int windowingMode = windowingModeToReason.keyAt(index);
            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(
                    windowingMode);
            if (info == null) {
                continue;
            }
            info.reason = windowingModeToReason.valueAt(index);
        }
        if (allWindowsDrawn()) {
            reset(false /* abort */);
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
        final TaskRecord t = activityRecord.getTask();
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = t;
        args.arg2 = activityRecord;
        mHandler.obtainMessage(MSG_CHECK_VISIBILITY, args).sendToTarget();
    }

    private void checkVisibility(TaskRecord t, ActivityRecord r) {
        synchronized (mSupervisor.mService) {

            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.get(
                    r.getWindowingMode());

            // If we have an active transition that's waiting on a certain activity that will be
            // invisible now, we'll never get onWindowsDrawn, so abort the transition if necessary.
            if (info != null && !t.isVisible()) {
                if (DEBUG_METRICS) Slog.i(TAG, "notifyVisibilityChanged to invisible"
                        + " activity=" + r);
                logAppTransitionCancel(info);
                mWindowingModeTransitionInfo.remove(r.getWindowingMode());
                if (mWindowingModeTransitionInfo.size() == 0) {
                    reset(true /* abort */);
                }
            }
        }
    }

    /**
     * Notifies the tracker that we called immediately before we call bindApplication on the client.
     *
     * @param app The client into which we'll call bindApplication.
     */
    void notifyBindApplication(ProcessRecord app) {
        for (int i = mWindowingModeTransitionInfo.size() - 1; i >= 0; i--) {
            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.valueAt(i);

            // App isn't attached to record yet, so match with info.
            if (info.launchedActivity.appInfo == app.info) {
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

    private void reset(boolean abort) {
        if (DEBUG_METRICS) Slog.i(TAG, "reset abort=" + abort);
        if (!abort && isAnyTransitionActive()) {
            logAppTransitionMultiEvents();
        }
        mCurrentTransitionStartTime = INVALID_START_TIME;
        mCurrentTransitionDelayMs = -1;
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
        if (type == -1) {
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
    }

    private void logAppTransitionMultiEvents() {
        if (DEBUG_METRICS) Slog.i(TAG, "logging transition events");
        for (int index = mWindowingModeTransitionInfo.size() - 1; index >= 0; index--) {
            final WindowingModeTransitionInfo info = mWindowingModeTransitionInfo.valueAt(index);
            final int type = getTransitionType(info);
            if (type == -1) {
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
        if (info.startingWindowDelayMs != -1) {
            builder.addTaggedData(APP_TRANSITION_STARTING_WINDOW_DELAY_MS,
                    info.startingWindowDelayMs);
        }
        if (info.bindApplicationDelayMs != -1) {
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
        logAppStartMemoryStateCapture(info);
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

    void logAppTransitionReportedDrawn(ActivityRecord r, boolean restoredFromBundle) {
        final WindowingModeTransitionInfo info = mLastWindowingModeTransitionInfo.get(
                r.getWindowingMode());
        if (info == null) {
            return;
        }
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
    }

    private int getTransitionType(WindowingModeTransitionInfo info) {
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

    private void logAppStartMemoryStateCapture(WindowingModeTransitionInfoSnapshot info) {
        if (info.processRecord == null) {
            if (DEBUG_METRICS) Slog.i(TAG, "logAppStartMemoryStateCapture processRecord null");
            return;
        }

        final int pid = info.processRecord.pid;
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

    private ProcessRecord findProcessForActivity(ActivityRecord launchedActivity) {
        return launchedActivity != null
                ? mSupervisor.mService.mProcessNames.get(launchedActivity.processName,
                        launchedActivity.appInfo.uid)
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
}
