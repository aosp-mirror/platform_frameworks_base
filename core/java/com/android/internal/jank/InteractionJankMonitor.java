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
import static com.android.internal.jank.FrameTracker.REASON_END_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__BIOMETRIC_PROMPT_TRANSITION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__IME_INSETS_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_ALL_APPS_SCROLL;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_WIDGET;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_SWIPE_TO_RECENTS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_TO_HOME;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_ALL_APPS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_SEARCH_RESULT;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_UNLOCK_ENTRANCE_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_CLOCK_MOVE_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_LAUNCH_CAMERA;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_OCCLUSION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_FROM_AOD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_TO_AOD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_UNLOCK_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__ONE_HANDED_ENTER_TRANSITION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__ONE_HANDED_EXIT_TRANSITION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__PIP_TRANSITION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__RECENTS_SCROLLING;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SCREEN_OFF;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SCREEN_OFF_SHOW_AOD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_PAGE_SCROLL;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_SLIDER;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_TOGGLE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_QS_TILE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_CLEAR_ALL;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_DIALOG_OPEN;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLASHSCREEN_AVD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLASHSCREEN_EXIT_ANIM;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_ENTER;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_EXIT;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_RESIZE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_SCREEN_FOR_STATUS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_TO_NEXT_FLOW;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TAKE_SCREENSHOT;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TASKBAR_COLLAPSE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TASKBAR_EXPAND;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__UNFOLD_ANIM;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__USER_DIALOG_OPEN;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__USER_SWITCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__VOLUME_CONTROL;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__WALLPAPER_TRANSITION;

import android.Manifest;
import android.annotation.ColorInt;
import android.annotation.IntDef;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * This class let users to begin and end the always on tracing mechanism.
 *
 * Enabling for local development:
 *
 * adb shell device_config put interaction_jank_monitor enabled true
 * adb shell device_config put interaction_jank_monitor sampling_interval 1
 *
 * On debuggable builds, an overlay can be used to display the name of the
 * currently running cuj using:
 *
 * adb shell device_config put interaction_jank_monitor debug_overlay_enabled true
 *
 * NOTE: The overlay will interfere with metrics, so it should only be used
 * for understanding which UI events correspeond to which CUJs.
 *
 * @hide
 */
public class InteractionJankMonitor {
    private static final String TAG = InteractionJankMonitor.class.getSimpleName();
    private static final boolean DEBUG = false;
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

    @VisibleForTesting
    public static final int MAX_LENGTH_OF_CUJ_NAME = 80;
    private static final int MAX_LENGTH_SESSION_NAME = 100;

    public static final String ACTION_SESSION_END = ACTION_PREFIX + ".ACTION_SESSION_END";
    public static final String ACTION_SESSION_CANCEL = ACTION_PREFIX + ".ACTION_SESSION_CANCEL";

    // Every value must have a corresponding entry in CUJ_STATSD_INTERACTION_TYPE.
    public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE = 0;
    public static final int CUJ_NOTIFICATION_SHADE_SCROLL_FLING = 2;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_EXPAND = 3;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_SWIPE = 4;
    public static final int CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE = 5;
    public static final int CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE = 6;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS = 7;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON = 8;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_HOME = 9;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_PIP = 10;
    public static final int CUJ_LAUNCHER_QUICK_SWITCH = 11;
    public static final int CUJ_NOTIFICATION_HEADS_UP_APPEAR = 12;
    public static final int CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR = 13;
    public static final int CUJ_NOTIFICATION_ADD = 14;
    public static final int CUJ_NOTIFICATION_REMOVE = 15;
    public static final int CUJ_NOTIFICATION_APP_START = 16;
    public static final int CUJ_LOCKSCREEN_PASSWORD_APPEAR = 17;
    public static final int CUJ_LOCKSCREEN_PATTERN_APPEAR = 18;
    public static final int CUJ_LOCKSCREEN_PIN_APPEAR = 19;
    public static final int CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR = 20;
    public static final int CUJ_LOCKSCREEN_PATTERN_DISAPPEAR = 21;
    public static final int CUJ_LOCKSCREEN_PIN_DISAPPEAR = 22;
    public static final int CUJ_LOCKSCREEN_TRANSITION_FROM_AOD = 23;
    public static final int CUJ_LOCKSCREEN_TRANSITION_TO_AOD = 24;
    public static final int CUJ_LAUNCHER_OPEN_ALL_APPS = 25;
    public static final int CUJ_LAUNCHER_ALL_APPS_SCROLL = 26;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET = 27;
    public static final int CUJ_SETTINGS_PAGE_SCROLL = 28;
    public static final int CUJ_LOCKSCREEN_UNLOCK_ANIMATION = 29;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON = 30;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER = 31;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE = 32;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON = 33;
    public static final int CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP = 34;
    public static final int CUJ_PIP_TRANSITION = 35;
    public static final int CUJ_WALLPAPER_TRANSITION = 36;
    public static final int CUJ_USER_SWITCH = 37;
    public static final int CUJ_SPLASHSCREEN_AVD = 38;
    public static final int CUJ_SPLASHSCREEN_EXIT_ANIM = 39;
    public static final int CUJ_SCREEN_OFF = 40;
    public static final int CUJ_SCREEN_OFF_SHOW_AOD = 41;
    public static final int CUJ_ONE_HANDED_ENTER_TRANSITION = 42;
    public static final int CUJ_ONE_HANDED_EXIT_TRANSITION = 43;
    public static final int CUJ_UNFOLD_ANIM = 44;
    public static final int CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS = 45;
    public static final int CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS = 46;
    public static final int CUJ_SUW_LOADING_TO_NEXT_FLOW = 47;
    public static final int CUJ_SUW_LOADING_SCREEN_FOR_STATUS = 48;
    public static final int CUJ_SPLIT_SCREEN_ENTER = 49;
    public static final int CUJ_SPLIT_SCREEN_EXIT = 50;
    public static final int CUJ_LOCKSCREEN_LAUNCH_CAMERA = 51; // reserved.
    public static final int CUJ_SPLIT_SCREEN_RESIZE = 52;
    public static final int CUJ_SETTINGS_SLIDER = 53;
    public static final int CUJ_TAKE_SCREENSHOT = 54;
    public static final int CUJ_VOLUME_CONTROL = 55;
    public static final int CUJ_BIOMETRIC_PROMPT_TRANSITION = 56;
    public static final int CUJ_SETTINGS_TOGGLE = 57;
    public static final int CUJ_SHADE_DIALOG_OPEN = 58;
    public static final int CUJ_USER_DIALOG_OPEN = 59;
    public static final int CUJ_TASKBAR_EXPAND = 60;
    public static final int CUJ_TASKBAR_COLLAPSE = 61;
    public static final int CUJ_SHADE_CLEAR_ALL = 62;
    public static final int CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION = 63;
    public static final int CUJ_LOCKSCREEN_OCCLUSION = 64;
    public static final int CUJ_RECENTS_SCROLLING = 65;
    public static final int CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS = 66;
    public static final int CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE = 67;
    public static final int CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME = 68;
    public static final int CUJ_IME_INSETS_ANIMATION = 69;
    public static final int CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION = 70;
    public static final int CUJ_LAUNCHER_OPEN_SEARCH_RESULT = 71;

    private static final int LAST_CUJ = CUJ_LAUNCHER_OPEN_SEARCH_RESULT;
    private static final int NO_STATSD_LOGGING = -1;

    // Used to convert CujType to InteractionType enum value for statsd logging.
    // Use NO_STATSD_LOGGING in case the measurement for a given CUJ should not be logged to statsd.
    @VisibleForTesting
    public static final int[] CUJ_TO_STATSD_INTERACTION_TYPE = new int[LAST_CUJ + 1];

    static {
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[1] = NO_STATSD_LOGGING; // This is deprecated.
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_SCROLL_FLING] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_ROW_EXPAND] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_ROW_SWIPE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_CLOSE_TO_HOME] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_CLOSE_TO_PIP] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_QUICK_SWITCH] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_HEADS_UP_APPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_ADD] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_REMOVE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_APP_START] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PASSWORD_APPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PATTERN_APPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PIN_APPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PATTERN_DISAPPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PIN_DISAPPEAR] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_TRANSITION_FROM_AOD] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_FROM_AOD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_TRANSITION_TO_AOD] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_TO_AOD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_OPEN_ALL_APPS] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_ALL_APPS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_ALL_APPS_SCROLL] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_ALL_APPS_SCROLL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_WIDGET;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SETTINGS_PAGE_SCROLL] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_PAGE_SCROLL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_UNLOCK_ANIMATION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_UNLOCK_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_QS_TILE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_PIP_TRANSITION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__PIP_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_WALLPAPER_TRANSITION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__WALLPAPER_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_USER_SWITCH] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__USER_SWITCH;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLASHSCREEN_AVD] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLASHSCREEN_AVD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLASHSCREEN_EXIT_ANIM] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLASHSCREEN_EXIT_ANIM;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SCREEN_OFF] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SCREEN_OFF;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SCREEN_OFF_SHOW_AOD] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SCREEN_OFF_SHOW_AOD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_ONE_HANDED_ENTER_TRANSITION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__ONE_HANDED_ENTER_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_ONE_HANDED_EXIT_TRANSITION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__ONE_HANDED_EXIT_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_UNFOLD_ANIM] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__UNFOLD_ANIM;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_LOADING_TO_NEXT_FLOW] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_TO_NEXT_FLOW;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_LOADING_SCREEN_FOR_STATUS] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_SCREEN_FOR_STATUS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_ENTER] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_ENTER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_EXIT] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_EXIT;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_LAUNCH_CAMERA] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_LAUNCH_CAMERA;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_RESIZE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_RESIZE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SETTINGS_SLIDER] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_SLIDER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_TAKE_SCREENSHOT] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TAKE_SCREENSHOT;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_VOLUME_CONTROL] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__VOLUME_CONTROL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_BIOMETRIC_PROMPT_TRANSITION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__BIOMETRIC_PROMPT_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SETTINGS_TOGGLE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_TOGGLE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_DIALOG_OPEN] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_DIALOG_OPEN;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_USER_DIALOG_OPEN] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__USER_DIALOG_OPEN;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_TASKBAR_EXPAND] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TASKBAR_EXPAND;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_TASKBAR_COLLAPSE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TASKBAR_COLLAPSE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_CLEAR_ALL] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_CLEAR_ALL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_UNLOCK_ENTRANCE_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_OCCLUSION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_OCCLUSION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_RECENTS_SCROLLING] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__RECENTS_SCROLLING;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_SWIPE_TO_RECENTS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_TO_HOME;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_IME_INSETS_ANIMATION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__IME_INSETS_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_CLOCK_MOVE_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_OPEN_SEARCH_RESULT] = UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_SEARCH_RESULT;
    }

    private static class InstanceHolder {
        public static final InteractionJankMonitor INSTANCE =
            new InteractionJankMonitor(new HandlerThread(DEFAULT_WORKER_NAME));
    }

    private final DeviceConfig.OnPropertiesChangedListener mPropertiesChangedListener =
            this::updateProperties;

    @GuardedBy("mLock")
    private final SparseArray<FrameTracker> mRunningTrackers;
    @GuardedBy("mLock")
    private final SparseArray<Runnable> mTimeoutActions;
    private final HandlerThread mWorker;
    private final DisplayResolutionTracker mDisplayResolutionTracker;
    private final Object mLock = new Object();
    private @ColorInt int mDebugBgColor = Color.CYAN;
    private double mDebugYOffset = 0.1;
    private InteractionMonitorDebugOverlay mDebugOverlay;

    private volatile boolean mEnabled = DEFAULT_ENABLED;
    private int mSamplingInterval = DEFAULT_SAMPLING_INTERVAL;
    private int mTraceThresholdMissedFrames = DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES;
    private int mTraceThresholdFrameTimeMillis = DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS;

    /** @hide */
    @IntDef({
            CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_SCROLL_FLING,
            CUJ_NOTIFICATION_SHADE_ROW_EXPAND,
            CUJ_NOTIFICATION_SHADE_ROW_SWIPE,
            CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON,
            CUJ_LAUNCHER_APP_CLOSE_TO_HOME,
            CUJ_LAUNCHER_APP_CLOSE_TO_PIP,
            CUJ_LAUNCHER_QUICK_SWITCH,
            CUJ_NOTIFICATION_HEADS_UP_APPEAR,
            CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR,
            CUJ_NOTIFICATION_ADD,
            CUJ_NOTIFICATION_REMOVE,
            CUJ_NOTIFICATION_APP_START,
            CUJ_LOCKSCREEN_PASSWORD_APPEAR,
            CUJ_LOCKSCREEN_PATTERN_APPEAR,
            CUJ_LOCKSCREEN_PIN_APPEAR,
            CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR,
            CUJ_LOCKSCREEN_PATTERN_DISAPPEAR,
            CUJ_LOCKSCREEN_PIN_DISAPPEAR,
            CUJ_LOCKSCREEN_TRANSITION_FROM_AOD,
            CUJ_LOCKSCREEN_TRANSITION_TO_AOD,
            CUJ_LAUNCHER_OPEN_ALL_APPS,
            CUJ_LAUNCHER_ALL_APPS_SCROLL,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET,
            CUJ_SETTINGS_PAGE_SCROLL,
            CUJ_LOCKSCREEN_UNLOCK_ANIMATION,
            CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON,
            CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER,
            CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE,
            CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON,
            CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
            CUJ_PIP_TRANSITION,
            CUJ_WALLPAPER_TRANSITION,
            CUJ_USER_SWITCH,
            CUJ_SPLASHSCREEN_AVD,
            CUJ_SPLASHSCREEN_EXIT_ANIM,
            CUJ_SCREEN_OFF,
            CUJ_SCREEN_OFF_SHOW_AOD,
            CUJ_ONE_HANDED_ENTER_TRANSITION,
            CUJ_ONE_HANDED_EXIT_TRANSITION,
            CUJ_UNFOLD_ANIM,
            CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS,
            CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS,
            CUJ_SUW_LOADING_TO_NEXT_FLOW,
            CUJ_SUW_LOADING_SCREEN_FOR_STATUS,
            CUJ_SPLIT_SCREEN_ENTER,
            CUJ_SPLIT_SCREEN_EXIT,
            CUJ_LOCKSCREEN_LAUNCH_CAMERA,
            CUJ_SPLIT_SCREEN_RESIZE,
            CUJ_SETTINGS_SLIDER,
            CUJ_TAKE_SCREENSHOT,
            CUJ_VOLUME_CONTROL,
            CUJ_BIOMETRIC_PROMPT_TRANSITION,
            CUJ_SETTINGS_TOGGLE,
            CUJ_SHADE_DIALOG_OPEN,
            CUJ_USER_DIALOG_OPEN,
            CUJ_TASKBAR_EXPAND,
            CUJ_TASKBAR_COLLAPSE,
            CUJ_SHADE_CLEAR_ALL,
            CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION,
            CUJ_LOCKSCREEN_OCCLUSION,
            CUJ_RECENTS_SCROLLING,
            CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS,
            CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE,
            CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME,
            CUJ_IME_INSETS_ANIMATION,
            CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION,
            CUJ_LAUNCHER_OPEN_SEARCH_RESULT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CujType {
    }

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
        mRunningTrackers = new SparseArray<>();
        mTimeoutActions = new SparseArray<>();
        mWorker = worker;
        mWorker.start();
        mDisplayResolutionTracker = new DisplayResolutionTracker(worker.getThreadHandler());
        mSamplingInterval = DEFAULT_SAMPLING_INTERVAL;
        mEnabled = DEFAULT_ENABLED;

        final Context context = ActivityThread.currentApplication();
        if (context.checkCallingOrSelfPermission(READ_DEVICE_CONFIG) != PERMISSION_GRANTED) {
            if (DEBUG) {
                Log.d(TAG, "Initialized the InteractionJankMonitor."
                        + " (No READ_DEVICE_CONFIG permission to change configs)"
                        + " enabled=" + mEnabled + ", interval=" + mSamplingInterval
                        + ", missedFrameThreshold=" + mTraceThresholdMissedFrames
                        + ", frameTimeThreshold=" + mTraceThresholdFrameTimeMillis
                        + ", package=" + context.getPackageName());
            }
            return;
        }

        // Post initialization to the background in case we're running on the main thread.
        mWorker.getThreadHandler().post(
                () -> {
                    try {
                        mPropertiesChangedListener.onPropertiesChanged(
                                DeviceConfig.getProperties(NAMESPACE_INTERACTION_JANK_MONITOR));
                        DeviceConfig.addOnPropertiesChangedListener(
                                NAMESPACE_INTERACTION_JANK_MONITOR,
                                new HandlerExecutor(mWorker.getThreadHandler()),
                                mPropertiesChangedListener);
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
     * @param config the config used in instrumenting
     * @param session the session associates with this tracker
     * @return instance of the FrameTracker
     */
    @VisibleForTesting
    public FrameTracker createFrameTracker(Configuration config, Session session) {
        final View view = config.mView;

        if (!config.hasValidView()) {
            boolean attached = false;
            boolean hasViewRoot = false;
            boolean hasRenderer = false;
            if (view != null) {
                attached = view.isAttachedToWindow();
                hasViewRoot = view.getViewRootImpl() != null;
                hasRenderer = view.getThreadedRenderer() != null;
            }
            Log.d(TAG, "create FrameTracker fails: view=" + view
                    + ", attached=" + attached + ", hasViewRoot=" + hasViewRoot
                    + ", hasRenderer=" + hasRenderer, new Throwable());
            return null;
        }

        final ThreadedRendererWrapper threadedRenderer =
                view == null ? null : new ThreadedRendererWrapper(view.getThreadedRenderer());
        final ViewRootWrapper viewRoot =
                view == null ? null : new ViewRootWrapper(view.getViewRootImpl());
        final SurfaceControlWrapper surfaceControl = new SurfaceControlWrapper();
        final ChoreographerWrapper choreographer =
                new ChoreographerWrapper(Choreographer.getInstance());
        final FrameTrackerListener eventsListener = (s, act) -> handleCujEvents(act, s);
        final FrameMetricsWrapper frameMetrics = new FrameMetricsWrapper();

        return new FrameTracker(this, session, config.getHandler(), threadedRenderer, viewRoot,
                surfaceControl, choreographer, frameMetrics,
                new FrameTracker.StatsLogWrapper(mDisplayResolutionTracker),
                mTraceThresholdMissedFrames, mTraceThresholdFrameTimeMillis,
                eventsListener, config);
    }

    @UiThread
    private void handleCujEvents(String action, Session session) {
        // Clear the running and timeout tasks if the end / cancel was fired within the tracker.
        // Or we might have memory leaks.
        if (needRemoveTasks(action, session)) {
            getTracker(session.getCuj()).getHandler().runWithScissors(() -> {
                removeTimeout(session.getCuj());
                removeTracker(session.getCuj(), session.getReason());
            }, EXECUTOR_TASK_TIMEOUT);
        }
    }

    private boolean needRemoveTasks(String action, Session session) {
        final boolean badEnd = action.equals(ACTION_SESSION_END)
                && session.getReason() != REASON_END_NORMAL;
        final boolean badCancel = action.equals(ACTION_SESSION_CANCEL)
                && !(session.getReason() == REASON_CANCEL_NORMAL
                || session.getReason() == REASON_CANCEL_TIMEOUT);
        return badEnd || badCancel;
    }

    private void removeTimeout(@CujType int cujType) {
        synchronized (mLock) {
            Runnable timeout = mTimeoutActions.get(cujType);
            if (timeout != null) {
                getTracker(cujType).getHandler().removeCallbacks(timeout);
                mTimeoutActions.remove(cujType);
            }
        }
    }

    /**
     * @param cujType cuj type
     * @return true if the cuj is under instrumenting, false otherwise.
     */
    public boolean isInstrumenting(@CujType int cujType) {
        synchronized (mLock) {
            return mRunningTrackers.contains(cujType);
        }
    }

    /**
     * Begins a trace session.
     *
     * @param v an attached view.
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @return boolean true if the tracker is started successfully, false otherwise.
     */
    public boolean begin(View v, @CujType int cujType) {
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
                Log.d(TAG, "begin failed due to timeout, CUJ=" + getNameOfCuj(config.mCujType));
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
        if (!shouldMonitor(cujType)) return false;
        FrameTracker tracker = getTracker(cujType);
        // Skip subsequent calls if we already have an ongoing tracing.
        if (tracker != null) return false;

        // begin a new trace session.
        tracker = createFrameTracker(conf, new Session(cujType, conf.mTag));
        if (tracker == null) return false;
        putTracker(cujType, tracker);
        tracker.begin();

        // Cancel the trace if we don't get an end() call in specified duration.
        scheduleTimeoutAction(
                cujType, conf.mTimeout, () -> cancel(cujType, REASON_CANCEL_TIMEOUT));
        return true;
    }

    /**
     * Check if the monitoring is enabled and if it should be sampled.
     */
    @SuppressWarnings("RandomModInteger")
    @VisibleForTesting
    public boolean shouldMonitor(@CujType int cujType) {
        boolean shouldSample = ThreadLocalRandom.current().nextInt() % mSamplingInterval == 0;
        if (!mEnabled || !shouldSample) {
            if (DEBUG) {
                Log.d(TAG, "Skip monitoring cuj: " + getNameOfCuj(cujType)
                        + ", enable=" + mEnabled + ", debuggable=" + DEFAULT_ENABLED
                        + ", sample=" + shouldSample + ", interval=" + mSamplingInterval);
            }
            return false;
        }
        return true;
    }

    /**
     * Schedules a timeout action.
     * @param cuj cuj type
     * @param timeout duration to timeout
     * @param action action once timeout
     */
    @VisibleForTesting
    public void scheduleTimeoutAction(@CujType int cuj, long timeout, Runnable action) {
        synchronized (mLock) {
            mTimeoutActions.put(cuj, action);
            getTracker(cuj).getHandler().postDelayed(action, timeout);
        }
    }

    /**
     * Ends a trace session.
     *
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @return boolean true if the tracker is ended successfully, false otherwise.
     */
    public boolean end(@CujType int cujType) {
        postEventLogToWorkerThread((unixNanos, elapsedNanos, realtimeNanos) -> {
            EventLogTags.writeJankCujEventsEndRequest(
                    cujType, unixNanos, elapsedNanos, realtimeNanos);
        });
        FrameTracker tracker = getTracker(cujType);
        // Skip this call since we haven't started a trace yet.
        if (tracker == null) return false;
        try {
            final TrackerResult result = new TrackerResult();
            final boolean success = tracker.getHandler().runWithScissors(
                    () -> result.mResult = endInternal(cujType), EXECUTOR_TASK_TIMEOUT);
            if (!success) {
                Log.d(TAG, "end failed due to timeout, CUJ=" + getNameOfCuj(cujType));
                return false;
            }
            return result.mResult;
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Execute end task failed!", ex);
            return false;
        }
    }

    @UiThread
    private boolean endInternal(@CujType int cujType) {
        // remove the timeout action first.
        removeTimeout(cujType);
        FrameTracker tracker = getTracker(cujType);
        if (tracker == null) return false;
        // if the end call doesn't return true, another thread is handling end of the cuj.
        if (tracker.end(REASON_END_NORMAL)) {
            removeTracker(cujType, REASON_END_NORMAL);
        }
        return true;
    }

    /**
     * Cancels the trace session.
     *
     * @return boolean true if the tracker is cancelled successfully, false otherwise.
     */
    public boolean cancel(@CujType int cujType) {
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
    public boolean cancel(@CujType int cujType, @Reasons int reason) {
        FrameTracker tracker = getTracker(cujType);
        // Skip this call since we haven't started a trace yet.
        if (tracker == null) return false;
        try {
            final TrackerResult result = new TrackerResult();
            final boolean success = tracker.getHandler().runWithScissors(
                    () -> result.mResult = cancelInternal(cujType, reason), EXECUTOR_TASK_TIMEOUT);
            if (!success) {
                Log.d(TAG, "cancel failed due to timeout, CUJ=" + getNameOfCuj(cujType));
                return false;
            }
            return result.mResult;
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Execute cancel task failed!", ex);
            return false;
        }
    }

    @UiThread
    private boolean cancelInternal(@CujType int cujType, @Reasons int reason) {
        // remove the timeout action first.
        removeTimeout(cujType);
        FrameTracker tracker = getTracker(cujType);
        if (tracker == null) return false;
        // if the cancel call doesn't return true, another thread is handling cancel of the cuj.
        if (tracker.cancel(reason)) {
            removeTracker(cujType, reason);
        }
        return true;
    }

    private void putTracker(@CujType int cuj, @NonNull FrameTracker tracker) {
        synchronized (mLock) {
            mRunningTrackers.put(cuj, tracker);
            if (mDebugOverlay != null) {
                mDebugOverlay.onTrackerAdded(cuj, tracker.getViewRoot());
            }
            if (DEBUG) {
                Log.d(TAG, "Added tracker for " + getNameOfCuj(cuj)
                        + ". mRunningTrackers=" + listNamesOfCujs(mRunningTrackers));
            }
        }
    }

    private FrameTracker getTracker(@CujType int cuj) {
        synchronized (mLock) {
            return mRunningTrackers.get(cuj);
        }
    }

    private void removeTracker(@CujType int cuj, int reason) {
        synchronized (mLock) {
            mRunningTrackers.remove(cuj);
            if (mDebugOverlay != null) {
                mDebugOverlay.onTrackerRemoved(cuj, reason, mRunningTrackers);
            }
            if (DEBUG) {
                Log.d(TAG, "Removed tracker for " + getNameOfCuj(cuj)
                        + ". mRunningTrackers=" + listNamesOfCujs(mRunningTrackers));
            }
        }
    }

    @WorkerThread
    private void updateProperties(DeviceConfig.Properties properties) {
        mSamplingInterval = properties.getInt(SETTINGS_SAMPLING_INTERVAL_KEY,
                DEFAULT_SAMPLING_INTERVAL);
        mTraceThresholdMissedFrames = properties.getInt(SETTINGS_THRESHOLD_MISSED_FRAMES_KEY,
                DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES);
        mTraceThresholdFrameTimeMillis = properties.getInt(
                SETTINGS_THRESHOLD_FRAME_TIME_MILLIS_KEY,
                DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS);
        // Never allow the debug overlay to be used on user builds
        boolean debugOverlayEnabled = Build.IS_DEBUGGABLE && properties.getBoolean(
                SETTINGS_DEBUG_OVERLAY_ENABLED_KEY,
                DEFAULT_DEBUG_OVERLAY_ENABLED);
        if (debugOverlayEnabled && mDebugOverlay == null) {
            mDebugOverlay = new InteractionMonitorDebugOverlay(mDebugBgColor, mDebugYOffset);
        } else if (!debugOverlayEnabled && mDebugOverlay != null) {
            mDebugOverlay.dispose();
            mDebugOverlay = null;
        }
        // The memory visibility is powered by the volatile field, mEnabled.
        mEnabled = properties.getBoolean(SETTINGS_ENABLED_KEY, DEFAULT_ENABLED);
    }

    @VisibleForTesting
    public DeviceConfig.OnPropertiesChangedListener getPropertiesChangedListener() {
        return mPropertiesChangedListener;
    }

    /**
     * Triggers the perfetto daemon to collect and upload data.
     */
    @VisibleForTesting
    public void trigger(Session session) {
        mWorker.getThreadHandler().post(
                () -> PerfettoTrigger.trigger(session.getPerfettoTrigger()));
    }

    /**
     * A helper method to translate interaction type to CUJ name.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the name of the interaction type
     */
    public static String getNameOfInteraction(int interactionType) {
        // There is an offset amount of 1 between cujType and interactionType.
        return getNameOfCuj(getCujTypeFromInteraction(interactionType));
    }

    /**
     * A helper method to translate interaction type to CUJ type.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the integer in {@link CujType}
     */
    private static int getCujTypeFromInteraction(int interactionType) {
        return interactionType - 1;
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

    /**
     * A helper method for getting a string representation of all running CUJs. For example,
     * "(LOCKSCREEN_TRANSITION_FROM_AOD, IME_INSETS_ANIMATION)"
     */
    private static String listNamesOfCujs(SparseArray<FrameTracker> trackers) {
        if (!DEBUG) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < trackers.size(); i++) {
            sb.append(getNameOfCuj(trackers.keyAt(i)));
            if (i < trackers.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * A helper method to translate CUJ type to CUJ name.
     *
     * @param cujType the cuj type defined in this file
     * @return the name of the cuj type
     */
    public static String getNameOfCuj(int cujType) {
        // Please note:
        // 1. The length of the returned string shouldn't exceed MAX_LENGTH_OF_CUJ_NAME.
        // 2. The returned string should be the same with the name defined in atoms.proto.
        switch (cujType) {
            case CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE:
                return "NOTIFICATION_SHADE_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_SCROLL_FLING:
                return "NOTIFICATION_SHADE_SCROLL_FLING";
            case CUJ_NOTIFICATION_SHADE_ROW_EXPAND:
                return "NOTIFICATION_SHADE_ROW_EXPAND";
            case CUJ_NOTIFICATION_SHADE_ROW_SWIPE:
                return "NOTIFICATION_SHADE_ROW_SWIPE";
            case CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE:
                return "NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE:
                return "NOTIFICATION_SHADE_QS_SCROLL_SWIPE";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS:
                return "LAUNCHER_APP_LAUNCH_FROM_RECENTS";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON:
                return "LAUNCHER_APP_LAUNCH_FROM_ICON";
            case CUJ_LAUNCHER_APP_CLOSE_TO_HOME:
                return "LAUNCHER_APP_CLOSE_TO_HOME";
            case CUJ_LAUNCHER_APP_CLOSE_TO_PIP:
                return "LAUNCHER_APP_CLOSE_TO_PIP";
            case CUJ_LAUNCHER_QUICK_SWITCH:
                return "LAUNCHER_QUICK_SWITCH";
            case CUJ_NOTIFICATION_HEADS_UP_APPEAR:
                return "NOTIFICATION_HEADS_UP_APPEAR";
            case CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR:
                return "NOTIFICATION_HEADS_UP_DISAPPEAR";
            case CUJ_NOTIFICATION_ADD:
                return "NOTIFICATION_ADD";
            case CUJ_NOTIFICATION_REMOVE:
                return "NOTIFICATION_REMOVE";
            case CUJ_NOTIFICATION_APP_START:
                return "NOTIFICATION_APP_START";
            case CUJ_LOCKSCREEN_PASSWORD_APPEAR:
                return "LOCKSCREEN_PASSWORD_APPEAR";
            case CUJ_LOCKSCREEN_PATTERN_APPEAR:
                return "LOCKSCREEN_PATTERN_APPEAR";
            case CUJ_LOCKSCREEN_PIN_APPEAR:
                return "LOCKSCREEN_PIN_APPEAR";
            case CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR:
                return "LOCKSCREEN_PASSWORD_DISAPPEAR";
            case CUJ_LOCKSCREEN_PATTERN_DISAPPEAR:
                return "LOCKSCREEN_PATTERN_DISAPPEAR";
            case CUJ_LOCKSCREEN_PIN_DISAPPEAR:
                return "LOCKSCREEN_PIN_DISAPPEAR";
            case CUJ_LOCKSCREEN_TRANSITION_FROM_AOD:
                return "LOCKSCREEN_TRANSITION_FROM_AOD";
            case CUJ_LOCKSCREEN_TRANSITION_TO_AOD:
                return "LOCKSCREEN_TRANSITION_TO_AOD";
            case CUJ_LAUNCHER_OPEN_ALL_APPS :
                return "LAUNCHER_OPEN_ALL_APPS";
            case CUJ_LAUNCHER_ALL_APPS_SCROLL:
                return "LAUNCHER_ALL_APPS_SCROLL";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET:
                return "LAUNCHER_APP_LAUNCH_FROM_WIDGET";
            case CUJ_SETTINGS_PAGE_SCROLL:
                return "SETTINGS_PAGE_SCROLL";
            case CUJ_LOCKSCREEN_UNLOCK_ANIMATION:
                return "LOCKSCREEN_UNLOCK_ANIMATION";
            case CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON:
                return "SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON";
            case CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER:
                return "SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER";
            case CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE:
                return "SHADE_APP_LAUNCH_FROM_QS_TILE";
            case CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON:
                return "SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON";
            case CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP:
                return "STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP";
            case CUJ_PIP_TRANSITION:
                return "PIP_TRANSITION";
            case CUJ_WALLPAPER_TRANSITION:
                return "WALLPAPER_TRANSITION";
            case CUJ_USER_SWITCH:
                return "USER_SWITCH";
            case CUJ_SPLASHSCREEN_AVD:
                return "SPLASHSCREEN_AVD";
            case CUJ_SPLASHSCREEN_EXIT_ANIM:
                return "SPLASHSCREEN_EXIT_ANIM";
            case CUJ_SCREEN_OFF:
                return "SCREEN_OFF";
            case CUJ_SCREEN_OFF_SHOW_AOD:
                return "SCREEN_OFF_SHOW_AOD";
            case CUJ_ONE_HANDED_ENTER_TRANSITION:
                return "ONE_HANDED_ENTER_TRANSITION";
            case CUJ_ONE_HANDED_EXIT_TRANSITION:
                return "ONE_HANDED_EXIT_TRANSITION";
            case CUJ_UNFOLD_ANIM:
                return "UNFOLD_ANIM";
            case CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS:
                return "SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS";
            case CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS:
                return "SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS";
            case CUJ_SUW_LOADING_TO_NEXT_FLOW:
                return "SUW_LOADING_TO_NEXT_FLOW";
            case CUJ_SUW_LOADING_SCREEN_FOR_STATUS:
                return "SUW_LOADING_SCREEN_FOR_STATUS";
            case CUJ_SPLIT_SCREEN_ENTER:
                return "SPLIT_SCREEN_ENTER";
            case CUJ_SPLIT_SCREEN_EXIT:
                return "SPLIT_SCREEN_EXIT";
            case CUJ_LOCKSCREEN_LAUNCH_CAMERA:
                return "LOCKSCREEN_LAUNCH_CAMERA";
            case CUJ_SPLIT_SCREEN_RESIZE:
                return "SPLIT_SCREEN_RESIZE";
            case CUJ_SETTINGS_SLIDER:
                return "SETTINGS_SLIDER";
            case CUJ_TAKE_SCREENSHOT:
                return "TAKE_SCREENSHOT";
            case CUJ_VOLUME_CONTROL:
                return "VOLUME_CONTROL";
            case CUJ_BIOMETRIC_PROMPT_TRANSITION:
                return "BIOMETRIC_PROMPT_TRANSITION";
            case CUJ_SETTINGS_TOGGLE:
                return "SETTINGS_TOGGLE";
            case CUJ_SHADE_DIALOG_OPEN:
                return "SHADE_DIALOG_OPEN";
            case CUJ_USER_DIALOG_OPEN:
                return "USER_DIALOG_OPEN";
            case CUJ_TASKBAR_EXPAND:
                return "TASKBAR_EXPAND";
            case CUJ_TASKBAR_COLLAPSE:
                return "TASKBAR_COLLAPSE";
            case CUJ_SHADE_CLEAR_ALL:
                return "SHADE_CLEAR_ALL";
            case CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION:
                return "LAUNCHER_UNLOCK_ENTRANCE_ANIMATION";
            case CUJ_LOCKSCREEN_OCCLUSION:
                return "LOCKSCREEN_OCCLUSION";
            case CUJ_RECENTS_SCROLLING:
                return "RECENTS_SCROLLING";
            case CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS:
                return "LAUNCHER_APP_SWIPE_TO_RECENTS";
            case CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE:
                return "LAUNCHER_CLOSE_ALL_APPS_SWIPE";
            case CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME:
                return "LAUNCHER_CLOSE_ALL_APPS_TO_HOME";
            case CUJ_IME_INSETS_ANIMATION:
                return "IME_INSETS_ANIMATION";
            case CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION:
                return "LOCKSCREEN_CLOCK_MOVE_ANIMATION";
            case CUJ_LAUNCHER_OPEN_SEARCH_RESULT:
                return "LAUNCHER_OPEN_SEARCH_RESULT";
        }
        return "UNKNOWN";
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
        private final boolean mSurfaceOnly;
        private final SurfaceControl mSurfaceControl;
        private final @CujType int mCujType;
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
            private @CujType int mAttrCujType;
            private boolean mAttrDeferMonitor = true;

            /**
             * Creates a builder which instruments only surface.
             * @param cuj The enum defined in {@link InteractionJankMonitor.CujType}.
             * @param context context
             * @param surfaceControl surface control
             * @return builder
             */
            public static Builder withSurface(@CujType int cuj, @NonNull Context context,
                    @NonNull SurfaceControl surfaceControl) {
                return new Builder(cuj)
                        .setContext(context)
                        .setSurfaceControl(surfaceControl)
                        .setSurfaceOnly(true);
            }

            /**
             * Creates a builder which instruments both surface and view.
             * @param cuj The enum defined in {@link InteractionJankMonitor.CujType}.
             * @param view view
             * @return builder
             */
            public static Builder withView(@CujType int cuj, @NonNull View view) {
                return new Builder(cuj).setView(view)
                        .setContext(view.getContext());
            }

            private Builder(@CujType int cuj) {
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

        private Configuration(@CujType int cuj, View view, String tag, long timeout,
                boolean surfaceOnly, Context context, SurfaceControl surfaceControl,
                boolean deferMonitor) {
            mCujType = cuj;
            mTag = tag;
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

        @VisibleForTesting
        /**
         * @return a view which is attached to the view tree.
         */
        public View getView() {
            return mView;
        }

        /**
         * @return true if the monitoring should be deferred to the next frame, false otherwise.
         */
        public boolean shouldDeferMonitor() {
            return mDeferMonitor;
        }

        @VisibleForTesting
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
    }

    /**
     * A class to represent a session.
     */
    public static class Session {
        @CujType
        private final int mCujType;
        private final long mTimeStamp;
        @Reasons
        private int mReason = REASON_END_UNKNOWN;
        private final String mName;

        public Session(@CujType int cujType, @NonNull String postfix) {
            mCujType = cujType;
            mTimeStamp = System.nanoTime();
            mName = generateSessionName(getNameOfCuj(cujType), postfix);
        }

        private String generateSessionName(@NonNull String cujName, @NonNull String cujPostfix) {
            final boolean hasPostfix = !TextUtils.isEmpty(cujPostfix);
            // We assert that the cujName shouldn't exceed MAX_LENGTH_OF_CUJ_NAME.
            if (cujName.length() > MAX_LENGTH_OF_CUJ_NAME) {
                throw new IllegalArgumentException(TextUtils.formatSimple(
                        "The length of cuj name <%s> exceeds %d", cujName, MAX_LENGTH_OF_CUJ_NAME));
            }
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

        @CujType
        public int getCuj() {
            return mCujType;
        }

        public int getStatsdInteractionType() {
            return CUJ_TO_STATSD_INTERACTION_TYPE[mCujType];
        }

        /** Describes whether the measurement from this session should be written to statsd. */
        public boolean logToStatsd() {
            return getStatsdInteractionType() != NO_STATSD_LOGGING;
        }

        public String getPerfettoTrigger() {
            return String.format(Locale.US, "com.android.telemetry.interaction-jank-monitor-%d",
                    mCujType);
        }

        public String getName() {
            return mName;
        }

        public long getTimeStamp() {
            return mTimeStamp;
        }

        public void setReason(@Reasons int reason) {
            mReason = reason;
        }

        @Reasons
        public int getReason() {
            return mReason;
        }
    }

    @FunctionalInterface
    private interface TimeFunction {
        void invoke(long unixNanos, long elapsedNanos, long realtimeNanos);
    }

    private void postEventLogToWorkerThread(TimeFunction logFunction) {
        final Instant now = Instant.now();
        final long unixNanos = TimeUnit.NANOSECONDS.convert(now.getEpochSecond(), TimeUnit.SECONDS)
                + now.getNano();
        final long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        final long realtimeNanos = SystemClock.uptimeNanos();

        mWorker.getThreadHandler().post(() -> {
            logFunction.invoke(unixNanos, elapsedNanos, realtimeNanos);
        });
    }
}
