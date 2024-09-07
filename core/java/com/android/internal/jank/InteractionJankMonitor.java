/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.jank;

import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.DeviceConfig.NAMESPACE_INTERACTION_JANK_MONITOR;

import static com.android.internal.jank.FrameTracker.REASON_CANCEL_NORMAL;
import static com.android.internal.jank.FrameTracker.REASON_CANCEL_TIMEOUT;
import static com.android.internal.jank.FrameTracker.REASON_END_NORMAL;

import android.Manifest;
import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.UiThread;
import android.annotation.WorkerThread;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.FrameTracker.ChoreographerWrapper;
import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.FrameTrackerListener;
import com.android.internal.jank.FrameTracker.Reasons;
import com.android.internal.jank.FrameTracker.SurfaceControlWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.FrameTracker.ViewRootWrapper;
import com.android.internal.util.PerfettoTrigger;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * This class lets users begin and end the always on tracing mechanism.
 *
 * Enabling for local development:
 *<pre>
 * adb shell device_config put interaction_jank_monitor enabled true
 * adb shell device_config put interaction_jank_monitor sampling_interval 1
 * </pre>
 * On debuggable builds, an overlay can be used to display the name of the
 * currently running cuj using:
 * <pre>
 * adb shell device_config put interaction_jank_monitor debug_overlay_enabled true
 * </pre>
 * <b>NOTE</b>: The overlay will interfere with metrics, so it should only be used
 * for understanding which UI events correspond to which CUJs.
 *
 * @hide
 */
public class InteractionJankMonitor {
    private static final String TAG = InteractionJankMonitor.class.getSimpleName();
    private static final String ACTION_PREFIX = InteractionJankMonitor.class.getCanonicalName();

    private static final String DEFAULT_WORKER_NAME = TAG + "-Worker";
    private static final long DEFAULT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(2L);
    static final long EXECUTOR_TASK_TIMEOUT = 500;
    private static final String SETTINGS_ENABLED_KEY = "enabled";
    private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
    private static final String SETTINGS_THRESHOLD_MISSED_FRAMES_KEY =
            "trace_threshold_missed_frames";
    private static final String SETTINGS_THRESHOLD_FRAME_TIME_MILLIS_KEY =
            "trace_threshold_frame_time_millis";
    private static final String SETTINGS_DEBUG_OVERLAY_ENABLED_KEY = "debug_overlay_enabled";
    /** Default to being enabled on debug builds. */
    private static final boolean DEFAULT_ENABLED = Build.IS_DEBUGGABLE;
    /** Default to collecting data for all CUJs. */
    private static final int DEFAULT_SAMPLING_INTERVAL = 1;
    /** Default to triggering trace if 3 frames are missed OR a frame takes at least 64ms */
    private static final int DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES = 3;
    private static final int DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS = 64;
    private static final boolean DEFAULT_DEBUG_OVERLAY_ENABLED = false;

    private static final int MAX_LENGTH_SESSION_NAME = 100;

    public static final String ACTION_SESSION_END = ACTION_PREFIX + ".ACTION_SESSION_END";
    public static final String ACTION_SESSION_CANCEL = ACTION_PREFIX + ".ACTION_SESSION_CANCEL";

    // These are not the CUJ constants you are looking for. These constants simply forward their
    // definition from {@link Cuj}. They are here only as a transition measure until all references
    // have been updated to the new location.
    @Deprecated public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE = Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE;
    @Deprecated public static final int CUJ_NOTIFICATION_SHADE_SCROLL_FLING = Cuj.CUJ_NOTIFICATION_SHADE_SCROLL_FLING;
    @Deprecated public static final int CUJ_NOTIFICATION_SHADE_ROW_EXPAND = Cuj.CUJ_NOTIFICATION_SHADE_ROW_EXPAND;
    @Deprecated public static final int CUJ_NOTIFICATION_SHADE_ROW_SWIPE = Cuj.CUJ_NOTIFICATION_SHADE_ROW_SWIPE;
    @Deprecated public static final int CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE = Cuj.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE;
    @Deprecated public static final int CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE = Cuj.CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE;
    @Deprecated public static final int CUJ_NOTIFICATION_HEADS_UP_APPEAR = Cuj.CUJ_NOTIFICATION_HEADS_UP_APPEAR;
    @Deprecated public static final int CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR = Cuj.CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR;
    @Deprecated public static final int CUJ_NOTIFICATION_ADD = Cuj.CUJ_NOTIFICATION_ADD;
    @Deprecated public static final int CUJ_NOTIFICATION_REMOVE = Cuj.CUJ_NOTIFICATION_REMOVE;
    @Deprecated public static final int CUJ_NOTIFICATION_APP_START = Cuj.CUJ_NOTIFICATION_APP_START;
    @Deprecated public static final int CUJ_LOCKSCREEN_PASSWORD_APPEAR = Cuj.CUJ_LOCKSCREEN_PASSWORD_APPEAR;
    @Deprecated public static final int CUJ_LOCKSCREEN_PATTERN_APPEAR = Cuj.CUJ_LOCKSCREEN_PATTERN_APPEAR;
    @Deprecated public static final int CUJ_LOCKSCREEN_PIN_APPEAR = Cuj.CUJ_LOCKSCREEN_PIN_APPEAR;
    @Deprecated public static final int CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR = Cuj.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR;
    @Deprecated public static final int CUJ_LOCKSCREEN_PATTERN_DISAPPEAR = Cuj.CUJ_LOCKSCREEN_PATTERN_DISAPPEAR;
    @Deprecated public static final int CUJ_LOCKSCREEN_PIN_DISAPPEAR = Cuj.CUJ_LOCKSCREEN_PIN_DISAPPEAR;
    @Deprecated public static final int CUJ_LOCKSCREEN_TRANSITION_FROM_AOD = Cuj.CUJ_LOCKSCREEN_TRANSITION_FROM_AOD;
    @Deprecated public static final int CUJ_LOCKSCREEN_TRANSITION_TO_AOD = Cuj.CUJ_LOCKSCREEN_TRANSITION_TO_AOD;
    @Deprecated public static final int CUJ_SETTINGS_PAGE_SCROLL = Cuj.CUJ_SETTINGS_PAGE_SCROLL;
    @Deprecated public static final int CUJ_LOCKSCREEN_UNLOCK_ANIMATION = Cuj.CUJ_LOCKSCREEN_UNLOCK_ANIMATION;
    @Deprecated public static final int CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON = Cuj.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON;
    @Deprecated public static final int CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER = Cuj.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER;
    @Deprecated public static final int CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE = Cuj.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE;
    @Deprecated public static final int CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON = Cuj.CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON;
    @Deprecated public static final int CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP = Cuj.CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP;
    @Deprecated public static final int CUJ_PIP_TRANSITION = Cuj.CUJ_PIP_TRANSITION;
    @Deprecated public static final int CUJ_USER_SWITCH = Cuj.CUJ_USER_SWITCH;
    @Deprecated public static final int CUJ_SPLASHSCREEN_AVD = Cuj.CUJ_SPLASHSCREEN_AVD;
    @Deprecated public static final int CUJ_SPLASHSCREEN_EXIT_ANIM = Cuj.CUJ_SPLASHSCREEN_EXIT_ANIM;
    @Deprecated public static final int CUJ_SCREEN_OFF = Cuj.CUJ_SCREEN_OFF;
    @Deprecated public static final int CUJ_SCREEN_OFF_SHOW_AOD = Cuj.CUJ_SCREEN_OFF_SHOW_AOD;
    @Deprecated public static final int CUJ_UNFOLD_ANIM = Cuj.CUJ_UNFOLD_ANIM;
    @Deprecated public static final int CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS = Cuj.CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS;
    @Deprecated public static final int CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS = Cuj.CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS;
    @Deprecated public static final int CUJ_SUW_LOADING_TO_NEXT_FLOW = Cuj.CUJ_SUW_LOADING_TO_NEXT_FLOW;
    @Deprecated public static final int CUJ_SUW_LOADING_SCREEN_FOR_STATUS = Cuj.CUJ_SUW_LOADING_SCREEN_FOR_STATUS;
    @Deprecated public static final int CUJ_SPLIT_SCREEN_RESIZE = Cuj.CUJ_SPLIT_SCREEN_RESIZE;
    @Deprecated public static final int CUJ_SETTINGS_SLIDER = Cuj.CUJ_SETTINGS_SLIDER;
    @Deprecated public static final int CUJ_TAKE_SCREENSHOT = Cuj.CUJ_TAKE_SCREENSHOT;
    @Deprecated public static final int CUJ_VOLUME_CONTROL = Cuj.CUJ_VOLUME_CONTROL;
    @Deprecated public static final int CUJ_BIOMETRIC_PROMPT_TRANSITION = Cuj.CUJ_BIOMETRIC_PROMPT_TRANSITION;
    @Deprecated public static final int CUJ_SETTINGS_TOGGLE = Cuj.CUJ_SETTINGS_TOGGLE;
    @Deprecated public static final int CUJ_SHADE_DIALOG_OPEN = Cuj.CUJ_SHADE_DIALOG_OPEN;
    @Deprecated public static final int CUJ_USER_DIALOG_OPEN = Cuj.CUJ_USER_DIALOG_OPEN;
    @Deprecated public static final int CUJ_TASKBAR_EXPAND = Cuj.CUJ_TASKBAR_EXPAND;
    @Deprecated public static final int CUJ_TASKBAR_COLLAPSE = Cuj.CUJ_TASKBAR_COLLAPSE;
    @Deprecated public static final int CUJ_SHADE_CLEAR_ALL = Cuj.CUJ_SHADE_CLEAR_ALL;
    @Deprecated public static final int CUJ_LOCKSCREEN_OCCLUSION = Cuj.CUJ_LOCKSCREEN_OCCLUSION;
    @Deprecated public static final int CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION = Cuj.CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION;
    @Deprecated public static final int CUJ_SPLIT_SCREEN_DOUBLE_TAP_DIVIDER = Cuj.CUJ_SPLIT_SCREEN_DOUBLE_TAP_DIVIDER;
    @Deprecated public static final int CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY = Cuj.CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY;
    @Deprecated public static final int CUJ_PREDICTIVE_BACK_CROSS_TASK = Cuj.CUJ_PREDICTIVE_BACK_CROSS_TASK;
    @Deprecated public static final int CUJ_PREDICTIVE_BACK_HOME = Cuj.CUJ_PREDICTIVE_BACK_HOME;
    @Deprecated public static final int CUJ_LAUNCHER_LAUNCH_APP_PAIR_FROM_WORKSPACE = Cuj.CUJ_LAUNCHER_LAUNCH_APP_PAIR_FROM_WORKSPACE;
    @Deprecated public static final int CUJ_LAUNCHER_LAUNCH_APP_PAIR_FROM_TASKBAR = Cuj.CUJ_LAUNCHER_LAUNCH_APP_PAIR_FROM_TASKBAR;
    @Deprecated public static final int CUJ_LAUNCHER_SAVE_APP_PAIR = Cuj.CUJ_LAUNCHER_SAVE_APP_PAIR;
    @Deprecated public static final int CUJ_LAUNCHER_ALL_APPS_SEARCH_BACK = Cuj.CUJ_LAUNCHER_ALL_APPS_SEARCH_BACK;
    @Deprecated public static final int CUJ_LAUNCHER_TASKBAR_ALL_APPS_CLOSE_BACK = Cuj.CUJ_LAUNCHER_TASKBAR_ALL_APPS_CLOSE_BACK;
    @Deprecated public static final int CUJ_LAUNCHER_TASKBAR_ALL_APPS_SEARCH_BACK = Cuj.CUJ_LAUNCHER_TASKBAR_ALL_APPS_SEARCH_BACK;
    @Deprecated public static final int CUJ_LAUNCHER_WIDGET_PICKER_CLOSE_BACK = Cuj.CUJ_LAUNCHER_WIDGET_PICKER_CLOSE_BACK;
    @Deprecated public static final int CUJ_LAUNCHER_WIDGET_PICKER_SEARCH_BACK = Cuj.CUJ_LAUNCHER_WIDGET_PICKER_SEARCH_BACK;
    @Deprecated public static final int CUJ_LAUNCHER_WIDGET_BOTTOM_SHEET_CLOSE_BACK = Cuj.CUJ_LAUNCHER_WIDGET_BOTTOM_SHEET_CLOSE_BACK;
    @Deprecated public static final int CUJ_LAUNCHER_WIDGET_EDU_SHEET_CLOSE_BACK = Cuj.CUJ_LAUNCHER_WIDGET_EDU_SHEET_CLOSE_BACK;

    private static class InstanceHolder {
        public static final InteractionJankMonitor INSTANCE =
            new InteractionJankMonitor(new HandlerThread(DEFAULT_WORKER_NAME));
    }

    @GuardedBy("mLock")
    private final SparseArray<RunningTracker> mRunningTrackers = new SparseArray<>();
    private final Handler mWorker;
    private final DisplayResolutionTracker mDisplayResolutionTracker;
    private final Object mLock = new Object();
    private @ColorInt int mDebugBgColor = Color.CYAN;
    private double mDebugYOffset = 0.1;
    private InteractionMonitorDebugOverlay mDebugOverlay;

    private volatile boolean mEnabled = DEFAULT_ENABLED;
    private int mSamplingInterval = DEFAULT_SAMPLING_INTERVAL;
    private int mTraceThresholdMissedFrames = DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES;
    private int mTraceThresholdFrameTimeMillis = DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS;

    /**
     * Get the singleton of InteractionJankMonitor.
     *
     * @return instance of InteractionJankMonitor
     */
    public static InteractionJankMonitor getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * This constructor should be only public to tests.
     *
     * @param worker the worker thread for the callbacks
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public InteractionJankMonitor(@NonNull HandlerThread worker) {
        worker.start();
        mWorker = worker.getThreadHandler();
        mDisplayResolutionTracker = new DisplayResolutionTracker(mWorker);

        final Context context = ActivityThread.currentApplication();
        if (context == null || context.checkCallingOrSelfPermission(READ_DEVICE_CONFIG) != PERMISSION_GRANTED) {
            Log.w(TAG, "Initializing without READ_DEVICE_CONFIG permission."
                    + " enabled=" + mEnabled + ", interval=" + mSamplingInterval
                    + ", missedFrameThreshold=" + mTraceThresholdMissedFrames
                    + ", frameTimeThreshold=" + mTraceThresholdFrameTimeMillis
                    + ", package=" + (context == null ? "null" : context.getPackageName()));
            return;
        }

        // Post initialization to the background in case we're running on the main thread.
        mWorker.post(() -> {
            try {
                updateProperties(DeviceConfig.getProperties(NAMESPACE_INTERACTION_JANK_MONITOR));
                DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_INTERACTION_JANK_MONITOR,
                        new HandlerExecutor(mWorker), this::updateProperties);
            } catch (SecurityException ex) {
                Log.d(TAG, "Can't get properties: READ_DEVICE_CONFIG granted="
                        + context.checkCallingOrSelfPermission(READ_DEVICE_CONFIG)
                        + ", package=" + context.getPackageName());
            }
        });
    }

    /**
     * Creates a {@link FrameTracker} instance.
     *
     * @param config the conifg associates with this tracker
     * @return instance of the FrameTracker
     */
    @VisibleForTesting
    public FrameTracker createFrameTracker(Configuration config) {
        final View view = config.mView;

        final ThreadedRendererWrapper threadedRenderer =
                view == null ? null : new ThreadedRendererWrapper(view.getThreadedRenderer());
        final ViewRootWrapper viewRoot =
                view == null ? null : new ViewRootWrapper(view.getViewRootImpl());
        final SurfaceControlWrapper surfaceControl = new SurfaceControlWrapper();
        final ChoreographerWrapper choreographer =
                new ChoreographerWrapper(Choreographer.getInstance());
        final FrameTrackerListener eventsListener = new FrameTrackerListener() {
            @Override
            public void onCujEvents(FrameTracker tracker, String action, int reason) {
                config.getHandler().runWithScissors(() ->
                        handleCujEvents(config.mCujType, tracker, action, reason),
                        EXECUTOR_TASK_TIMEOUT);
            }

            @Override
            public void triggerPerfetto(Configuration config) {
                mWorker.post(() -> PerfettoTrigger.trigger(config.getPerfettoTrigger()));
            }
        };
        final FrameMetricsWrapper frameMetrics = new FrameMetricsWrapper();

        return new FrameTracker(config, threadedRenderer, viewRoot,
                surfaceControl, choreographer, frameMetrics,
                new FrameTracker.StatsLogWrapper(mDisplayResolutionTracker),
                mTraceThresholdMissedFrames, mTraceThresholdFrameTimeMillis,
                eventsListener);
    }

    @UiThread
    private void handleCujEvents(
            @Cuj.CujType int cuj, FrameTracker tracker, String action, @Reasons int reason) {
        // Clear the running and timeout tasks if the end / cancel was fired within the tracker.
        // Or we might have memory leaks.
        if (needRemoveTasks(action, reason)) {
            removeTrackerIfCurrent(cuj, tracker, reason);
        }
    }

    private static boolean needRemoveTasks(String action, @Reasons int reason) {
        final boolean badEnd = action.equals(ACTION_SESSION_END) && reason != REASON_END_NORMAL;
        final boolean badCancel = action.equals(ACTION_SESSION_CANCEL)
                && !(reason == REASON_CANCEL_NORMAL || reason == REASON_CANCEL_TIMEOUT);
        return badEnd || badCancel;
    }

    /**
     * @param cujType cuj type
     * @return true if the cuj is under instrumenting, false otherwise.
     */
    public boolean isInstrumenting(@Cuj.CujType int cujType) {
        synchronized (mLock) {
            return mRunningTrackers.contains(cujType);
        }
    }

    /**
     * Begins a trace session.
     *
     * @param v an attached view.
     * @param cujType the specific {@link Cuj.CujType}.
     * @return boolean true if the tracker is started successfully, false otherwise.
     */
    public boolean begin(View v, @Cuj.CujType int cujType) {
        try {
            return begin(Configuration.Builder.withView(cujType, v));
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Build configuration failed!", ex);
            return false;
        }
    }

    /**
     * Begins a trace session.
     *
     * @param builder the builder of the configurations for instrumenting the CUJ.
     * @return boolean true if the tracker is begun successfully, false otherwise.
     */
    public boolean begin(@NonNull Configuration.Builder builder) {
        try {
            final Configuration config = builder.build();
            postEventLogToWorkerThread((unixNanos, elapsedNanos, realtimeNanos) -> {
                EventLogTags.writeJankCujEventsBeginRequest(
                        config.mCujType, unixNanos, elapsedNanos, realtimeNanos, config.mTag);
            });
            final TrackerResult result = new TrackerResult();
            final boolean success = config.getHandler().runWithScissors(
                    () -> result.mResult = beginInternal(config), EXECUTOR_TASK_TIMEOUT);
            if (!success) {
                Log.d(TAG, "begin failed due to timeout, CUJ=" + Cuj.getNameOfCuj(config.mCujType));
                return false;
            }
            return result.mResult;
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Build configuration failed!", ex);
            return false;
        }
    }

    @UiThread
    private boolean beginInternal(@NonNull Configuration conf) {
        int cujType = conf.mCujType;
        if (!shouldMonitor()) {
            return false;
        }

        RunningTracker tracker = putTrackerIfNoCurrent(cujType, () ->
                new RunningTracker(
                    conf, createFrameTracker(conf), () -> cancel(cujType, REASON_CANCEL_TIMEOUT)));
        if (tracker == null) {
            return false;
        }

        tracker.mTracker.begin();
        // Cancel the trace if we don't get an end() call in specified duration.
        scheduleTimeoutAction(tracker.mConfig, tracker.mTimeoutAction);

        return true;
    }

    /**
     * Check if the monitoring is enabled and if it should be sampled.
     */
    @VisibleForTesting
    public boolean shouldMonitor() {
        return mEnabled && (ThreadLocalRandom.current().nextInt(mSamplingInterval) == 0);
    }

    @VisibleForTesting
    public void scheduleTimeoutAction(Configuration config, Runnable action) {
        config.getHandler().postDelayed(action, config.mTimeout);
    }

    /**
     * Ends a trace session.
     *
     * @param cujType the specific {@link Cuj.CujType}.
     * @return boolean true if the tracker is ended successfully, false otherwise.
     */
    public boolean end(@Cuj.CujType int cujType) {
        postEventLogToWorkerThread((unixNanos, elapsedNanos, realtimeNanos) -> {
            EventLogTags.writeJankCujEventsEndRequest(
                    cujType, unixNanos, elapsedNanos, realtimeNanos);
        });
        RunningTracker tracker = getTracker(cujType);
        // Skip this call since we haven't started a trace yet.
        if (tracker == null) {
            return false;
        }
        try {
            final TrackerResult result = new TrackerResult();
            final boolean success = tracker.mConfig.getHandler().runWithScissors(
                    () -> result.mResult = endInternal(tracker), EXECUTOR_TASK_TIMEOUT);
            if (!success) {
                Log.d(TAG, "end failed due to timeout, CUJ=" + Cuj.getNameOfCuj(cujType));
                return false;
            }
            return result.mResult;
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Execute end task failed!", ex);
            return false;
        }
    }

    @UiThread
    private boolean endInternal(RunningTracker tracker) {
        if (removeTrackerIfCurrent(tracker, REASON_END_NORMAL)) {
            return false;
        }
        tracker.mTracker.end(REASON_END_NORMAL);
        return true;
    }

    /**
     * Cancels the trace session.
     *
     * @return boolean true if the tracker is cancelled successfully, false otherwise.
     */
    public boolean cancel(@Cuj.CujType int cujType) {
        postEventLogToWorkerThread((unixNanos, elapsedNanos, realtimeNanos) -> {
            EventLogTags.writeJankCujEventsCancelRequest(
                    cujType, unixNanos, elapsedNanos, realtimeNanos);
        });
        return cancel(cujType, REASON_CANCEL_NORMAL);
    }

    /**
     * Cancels the trace session.
     *
     * @return boolean true if the tracker is cancelled successfully, false otherwise.
     */
    @VisibleForTesting
    public boolean cancel(@Cuj.CujType int cujType, @Reasons int reason) {
        RunningTracker tracker = getTracker(cujType);
        // Skip this call since we haven't started a trace yet.
        if (tracker == null) {
            return false;
        }
        try {
            final TrackerResult result = new TrackerResult();
            final boolean success = tracker.mConfig.getHandler().runWithScissors(
                    () -> result.mResult = cancelInternal(tracker, reason), EXECUTOR_TASK_TIMEOUT);
            if (!success) {
                Log.d(TAG, "cancel failed due to timeout, CUJ=" + Cuj.getNameOfCuj(cujType));
                return false;
            }
            return result.mResult;
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Execute cancel task failed!", ex);
            return false;
        }
    }

    @UiThread
    private boolean cancelInternal(RunningTracker tracker, @Reasons int reason) {
        if (removeTrackerIfCurrent(tracker, reason)) {
            return false;
        }
        tracker.mTracker.cancel(reason);
        return true;
    }

    @UiThread
    private RunningTracker putTrackerIfNoCurrent(
            @Cuj.CujType int cuj, Supplier<RunningTracker> supplier) {
        synchronized (mLock) {
            if (mRunningTrackers.contains(cuj)) {
                return null;
            }

            RunningTracker tracker = supplier.get();
            if (tracker == null) {
                return null;
            }

            mRunningTrackers.put(cuj, tracker);
            if (mDebugOverlay != null) {
                mDebugOverlay.onTrackerAdded(cuj, tracker);
            }

            return tracker;
        }
    }

    private RunningTracker getTracker(@Cuj.CujType int cuj) {
        synchronized (mLock) {
            return mRunningTrackers.get(cuj);
        }
    }

    /**
     * @return {@code true} if another tracker is current
     */
    @UiThread
    private boolean removeTrackerIfCurrent(RunningTracker tracker, int reason) {
        return removeTrackerIfCurrent(tracker.mConfig.mCujType, tracker.mTracker, reason);
    }

    /**
     * @return {@code true} if another tracker is current
     */
    @UiThread
    private boolean removeTrackerIfCurrent(@Cuj.CujType int cuj, FrameTracker tracker, int reason) {
        synchronized (mLock) {
            RunningTracker running = mRunningTrackers.get(cuj);
            if (running == null || running.mTracker != tracker) {
                return true;
            }

            running.mConfig.getHandler().removeCallbacks(running.mTimeoutAction);
            mRunningTrackers.remove(cuj);
            if (mDebugOverlay != null) {
                mDebugOverlay.onTrackerRemoved(cuj, reason, mRunningTrackers);
            }
            return false;
        }
    }

    @WorkerThread
    @VisibleForTesting
    public void updateProperties(DeviceConfig.Properties properties) {
        for (String property : properties.getKeyset()) {
            switch (property) {
                case SETTINGS_SAMPLING_INTERVAL_KEY ->
                        mSamplingInterval = properties.getInt(property, DEFAULT_SAMPLING_INTERVAL);
                case SETTINGS_THRESHOLD_MISSED_FRAMES_KEY ->
                        mTraceThresholdMissedFrames =
                                properties.getInt(property, DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES);
                case SETTINGS_THRESHOLD_FRAME_TIME_MILLIS_KEY ->
                        mTraceThresholdFrameTimeMillis =
                                properties.getInt(property, DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS);
                case SETTINGS_ENABLED_KEY ->
                        mEnabled = properties.getBoolean(property, DEFAULT_ENABLED);
                case SETTINGS_DEBUG_OVERLAY_ENABLED_KEY -> {
                    // Never allow the debug overlay to be used on user builds
                    boolean debugOverlayEnabled = Build.IS_DEBUGGABLE
                            && properties.getBoolean(property, DEFAULT_DEBUG_OVERLAY_ENABLED);
                    if (debugOverlayEnabled && mDebugOverlay == null) {
                        mDebugOverlay = new InteractionMonitorDebugOverlay(
                                mLock, mDebugBgColor, mDebugYOffset);
                    } else if (!debugOverlayEnabled && mDebugOverlay != null) {
                        mDebugOverlay.dispose();
                        mDebugOverlay = null;
                    }
                }
                default -> Log.w(TAG, "Got a change event for an unknown property: "
                        + property + " => " + properties.getString(property, ""));
            }
        }
    }

    /**
     * A helper method to translate interaction type to CUJ name.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the name of the interaction type
     * @deprecated use {@link Cuj#getNameOfInteraction(int)}
     */
    @Deprecated
    public static String getNameOfInteraction(int interactionType) {
        return Cuj.getNameOfInteraction(interactionType);
    }

    /**
     * A helper method to translate CUJ type to CUJ name.
     *
     * @param cujType the cuj type defined in this file
     * @return the name of the cuj type
     * @deprecated use {@link Cuj#getNameOfCuj(int)}
     */
    @Deprecated
    public static String getNameOfCuj(int cujType) {
        return Cuj.getNameOfCuj(cujType);
    }

    /**
     * Configures the debug overlay used for displaying interaction names on the screen while they
     * occur.
     *
     * @param bgColor the background color of the box used to display the CUJ names
     * @param yOffset number between 0 and 1 to indicate where the top of the box should be relative
     *                to the height of the screen
     */
    public void configDebugOverlay(@ColorInt int bgColor, double yOffset) {
        mDebugBgColor = bgColor;
        mDebugYOffset = yOffset;
    }

    private void postEventLogToWorkerThread(TimeFunction logFunction) {
        final Instant now = Instant.now();
        final long unixNanos = TimeUnit.NANOSECONDS.convert(now.getEpochSecond(), TimeUnit.SECONDS)
                + now.getNano();
        final long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        final long realtimeNanos = SystemClock.uptimeNanos();

        mWorker.post(() -> logFunction.invoke(unixNanos, elapsedNanos, realtimeNanos));
    }

    private static class TrackerResult {
        private boolean mResult;
    }

    /**
     * Configurations used while instrumenting the CUJ. <br/>
     * <b>It may refer to an attached view, don't use static reference for any purpose.</b>
     */
    public static class Configuration {
        private final View mView;
        private final Context mContext;
        private final long mTimeout;
        private final String mTag;
        private final String mSessionName;
        private final boolean mSurfaceOnly;
        private final SurfaceControl mSurfaceControl;
        private final @Cuj.CujType int mCujType;
        private final boolean mDeferMonitor;
        private final Handler mHandler;

        /**
         * A builder for building Configuration. {@link #setView(View)} is essential
         * if {@link #setSurfaceOnly(boolean)} is not set, otherwise both
         * {@link #setSurfaceControl(SurfaceControl)} and {@link #setContext(Context)}
         * are necessary<br/>
         * <b>It may refer to an attached view, don't use static reference for any purpose.</b>
         */
        public static class Builder {
            private View mAttrView = null;
            private Context mAttrContext = null;
            private long mAttrTimeout = DEFAULT_TIMEOUT_MS;
            private String mAttrTag = "";
            private boolean mAttrSurfaceOnly;
            private SurfaceControl mAttrSurfaceControl;
            private final @Cuj.CujType int mAttrCujType;
            private boolean mAttrDeferMonitor = true;

            /**
             * Creates a builder which instruments only surface.
             * @param cuj The enum defined in {@link Cuj.CujType}.
             * @param context context
             * @param surfaceControl surface control
             * @return builder
             */
            public static Builder withSurface(@Cuj.CujType int cuj, @NonNull Context context,
                    @NonNull SurfaceControl surfaceControl) {
                return new Builder(cuj)
                        .setContext(context)
                        .setSurfaceControl(surfaceControl)
                        .setSurfaceOnly(true);
            }

            /**
             * Creates a builder which instruments both surface and view.
             * @param cuj The enum defined in {@link Cuj.CujType}.
             * @param view view
             * @return builder
             */
            public static Builder withView(@Cuj.CujType int cuj, @NonNull View view) {
                return new Builder(cuj)
                        .setView(view)
                        .setContext(view.getContext());
            }

            private Builder(@Cuj.CujType int cuj) {
                mAttrCujType = cuj;
            }

            /**
             * Specifies a view, must be set if {@link #setSurfaceOnly(boolean)} is set to false.
             * @param view an attached view
             * @return builder
             */
            private Builder setView(@NonNull View view) {
                mAttrView = view;
                return this;
            }

            /**
             * @param timeout duration to cancel the instrumentation in ms
             * @return builder
             */
            public Builder setTimeout(long timeout) {
                mAttrTimeout = timeout;
                return this;
            }

            /**
             * @param tag The postfix of the CUJ in the output trace.
             *           It provides a brief description for the CUJ like the concrete class
             *           who is dealing with the CUJ or the important state with the CUJ, etc.
             * @return builder
             */
            public Builder setTag(@NonNull String tag) {
                mAttrTag = tag;
                return this;
            }

            /**
             * Indicates if only instrument with surface,
             * if true, must also setup with {@link #setContext(Context)}
             * and {@link #setSurfaceControl(SurfaceControl)}.
             * @param surfaceOnly true if only instrument with surface, false otherwise
             * @return builder Surface only builder.
             */
            private Builder setSurfaceOnly(boolean surfaceOnly) {
                mAttrSurfaceOnly = surfaceOnly;
                return this;
            }

            /**
             * Specifies a context, must set if {@link #setSurfaceOnly(boolean)} is set.
             */
            private Builder setContext(Context context) {
                mAttrContext = context;
                return this;
            }

            /**
             * Specifies a surface control, must be set if {@link #setSurfaceOnly(boolean)} is set.
             */
            private Builder setSurfaceControl(SurfaceControl surfaceControl) {
                mAttrSurfaceControl = surfaceControl;
                return this;
            }

            /**
             * Indicates if the instrument should be deferred to the next frame.
             * @param defer true if the instrument should be deferred to the next frame.
             * @return builder
             */
            public Builder setDeferMonitorForAnimationStart(boolean defer) {
                mAttrDeferMonitor = defer;
                return this;
            }

            /**
             * Builds the {@link Configuration} instance
             * @return the instance of {@link Configuration}
             * @throws IllegalArgumentException if any invalid attribute is set
             */
            public Configuration build() throws IllegalArgumentException {
                return new Configuration(
                        mAttrCujType, mAttrView, mAttrTag, mAttrTimeout,
                        mAttrSurfaceOnly, mAttrContext, mAttrSurfaceControl,
                        mAttrDeferMonitor);
            }
        }

        private Configuration(@Cuj.CujType int cuj, View view, @NonNull String tag, long timeout,
                boolean surfaceOnly, Context context, SurfaceControl surfaceControl,
                boolean deferMonitor) {
            mCujType = cuj;
            mTag = tag;
            mSessionName = generateSessionName(Cuj.getNameOfCuj(cuj), tag);
            mTimeout = timeout;
            mView = view;
            mSurfaceOnly = surfaceOnly;
            mContext = context != null
                    ? context
                    : (view != null ? view.getContext().getApplicationContext() : null);
            mSurfaceControl = surfaceControl;
            mDeferMonitor = deferMonitor;
            validate();
            mHandler = mSurfaceOnly ? mContext.getMainThreadHandler() : mView.getHandler();
        }

        @VisibleForTesting
        public static String generateSessionName(
                @NonNull String cujName, @NonNull String cujPostfix) {
            final boolean hasPostfix = !TextUtils.isEmpty(cujPostfix);
            if (hasPostfix) {
                final int remaining = MAX_LENGTH_SESSION_NAME - cujName.length();
                if (cujPostfix.length() > remaining) {
                    cujPostfix = cujPostfix.substring(0, remaining - 3).concat("...");
                }
            }
            // The max length of the whole string should be:
            // 105 with postfix, 83 without postfix
            return hasPostfix
                    ? TextUtils.formatSimple("J<%s::%s>", cujName, cujPostfix)
                    : TextUtils.formatSimple("J<%s>", cujName);
        }

        private void validate() {
            boolean shouldThrow = false;
            final StringBuilder msg = new StringBuilder();

            if (mTag == null) {
                shouldThrow = true;
                msg.append("Invalid tag; ");
            }
            if (mTimeout < 0) {
                shouldThrow = true;
                msg.append("Invalid timeout value; ");
            }
            if (mSurfaceOnly) {
                if (mContext == null) {
                    shouldThrow = true;
                    msg.append("Must pass in a context if only instrument surface; ");
                }
                if (mSurfaceControl == null || !mSurfaceControl.isValid()) {
                    shouldThrow = true;
                    msg.append("Must pass in a valid surface control if only instrument surface; ");
                }
            } else {
                if (!hasValidView()) {
                    shouldThrow = true;
                    boolean attached = false;
                    boolean hasViewRoot = false;
                    boolean hasRenderer = false;
                    if (mView != null) {
                        attached = mView.isAttachedToWindow();
                        hasViewRoot = mView.getViewRootImpl() != null;
                        hasRenderer = mView.getThreadedRenderer() != null;
                    }
                    String err = "invalid view: view=" + mView + ", attached=" + attached
                            + ", hasViewRoot=" + hasViewRoot + ", hasRenderer=" + hasRenderer;
                    msg.append(err);
                }
            }
            if (shouldThrow) {
                throw new IllegalArgumentException(msg.toString());
            }
        }

        boolean hasValidView() {
            return mSurfaceOnly
                    || (mView != null && mView.isAttachedToWindow()
                    && mView.getViewRootImpl() != null && mView.getThreadedRenderer() != null);
        }

        /**
         * @return true if only instrumenting surface, false otherwise
         */
        public boolean isSurfaceOnly() {
            return mSurfaceOnly;
        }

        /**
         * @return the surafce control which is instrumenting
         */
        public SurfaceControl getSurfaceControl() {
            return mSurfaceControl;
        }

        /**
         * @return a view which is attached to the view tree.
         */
        @VisibleForTesting
        public View getView() {
            return mView;
        }

        /**
         * @return true if the monitoring should be deferred to the next frame, false otherwise.
         */
        public boolean shouldDeferMonitor() {
            return mDeferMonitor;
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * @return the ID of the display this interaction in on.
         */
        @VisibleForTesting
        public int getDisplayId() {
            return (mSurfaceOnly ? mContext : mView.getContext()).getDisplayId();
        }

        public String getSessionName() {
            return mSessionName;
        }

        public int getStatsdInteractionType() {
            return Cuj.getStatsdInteractionType(mCujType);
        }

        /** Describes whether the measurement from this session should be written to statsd. */
        public boolean logToStatsd() {
            return Cuj.logToStatsd(mCujType);
        }

        public String getPerfettoTrigger() {
            return TextUtils.formatSimple(
                    "com.android.telemetry.interaction-jank-monitor-%d", mCujType);
        }

        public @Cuj.CujType int getCujType() {
            return mCujType;
        }
    }

    @FunctionalInterface
    private interface TimeFunction {
        void invoke(long unixNanos, long elapsedNanos, long realtimeNanos);
    }

    static class RunningTracker {
        public final Configuration mConfig;
        public final FrameTracker mTracker;
        public final Runnable mTimeoutAction;

        RunningTracker(Configuration config, FrameTracker tracker, Runnable timeoutAction) {
            this.mConfig = config;
            this.mTracker = tracker;
            this.mTimeoutAction = timeoutAction;
        }
    }
}
