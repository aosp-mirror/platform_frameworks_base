/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.internal.util;

import static android.Manifest.permission.READ_DEVICE_CONFIG;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_APP;
import static android.provider.DeviceConfig.NAMESPACE_LATENCY_TRACKER;

import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_BACK_SYSTEM_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL_UNLOCKED;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_EXPAND_PANEL;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FACE_WAKE_AND_UNLOCK;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FINGERPRINT_WAKE_AND_UNLOCK;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FOLD_TO_AOD;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOAD_SHARE_SHEET;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOCKSCREEN_UNLOCK;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATION_BIG_PICTURE_LOADED;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_REQUEST_IME_HIDDEN;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_REQUEST_IME_SHOWN;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_CAMERA_CHECK;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_SENSOR;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_BACK_ARROW;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_SELECTION_TOOLBAR;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_VOICE_INTERACTION;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SMARTSPACE_DOORBELL;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_START_RECENTS_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SWITCH_DISPLAY_UNFOLD;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_TOGGLE_RECENTS;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_TURN_ON_SCREEN;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_UDFPS_ILLUMINATE;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_USER_SWITCH;
import static com.android.internal.util.FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__UNKNOWN_ACTION;
import static com.android.internal.util.LatencyTracker.ActionProperties.ENABLE_SUFFIX;
import static com.android.internal.util.LatencyTracker.ActionProperties.LEGACY_TRACE_THRESHOLD_SUFFIX;
import static com.android.internal.util.LatencyTracker.ActionProperties.SAMPLE_INTERVAL_SUFFIX;
import static com.android.internal.util.LatencyTracker.ActionProperties.TRACE_THRESHOLD_SUFFIX;

import android.Manifest;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.EventLogTags;
import com.android.internal.os.BackgroundThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Class to track various latencies in SystemUI. It then writes the latency to statsd and also
 * outputs it to logcat so these latencies can be captured by tests and then used for dashboards.
 * <p>
 * This is currently only in Keyguard. It can be shared between SystemUI and Keyguard, but
 * eventually we'd want to merge these two packages together so Keyguard can use common classes
 * that are shared with SystemUI.
 */
public class LatencyTracker {
    private static final String TAG = "LatencyTracker";
    public static final String SETTINGS_ENABLED_KEY = "enabled";
    private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
    private static final boolean DEBUG = false;
    /** Default to being enabled on debug builds. */
    private static final boolean DEFAULT_ENABLED = Build.IS_DEBUGGABLE;
    /** Default to collecting data for 1/5 of all actions (randomly sampled). */
    private static final int DEFAULT_SAMPLING_INTERVAL = 5;

    /**
     * Time it takes until the first frame of the notification panel to be displayed while expanding
     */
    public static final int ACTION_EXPAND_PANEL = 0;

    /**
     * Time it takes until the first frame of recents is drawn after invoking it with the button.
     */
    public static final int ACTION_TOGGLE_RECENTS = 1;

    /**
     * Time between we get a fingerprint acquired signal until we start with the unlock animation
     */
    public static final int ACTION_FINGERPRINT_WAKE_AND_UNLOCK = 2;

    /**
     * Time it takes to check PIN/Pattern/Password.
     */
    public static final int ACTION_CHECK_CREDENTIAL = 3;

    /**
     * Time it takes to check fully PIN/Pattern/Password, i.e. that's the time spent including the
     * actions to unlock a user.
     */
    public static final int ACTION_CHECK_CREDENTIAL_UNLOCKED = 4;

    /**
     * Time it takes to turn on the screen.
     */
    public static final int ACTION_TURN_ON_SCREEN = 5;

    /**
     * Time it takes to rotate the screen.
     */
    public static final int ACTION_ROTATE_SCREEN = 6;

    /*
     * Time between we get a face acquired signal until we start with the unlock animation
     */
    public static final int ACTION_FACE_WAKE_AND_UNLOCK = 7;

    /**
     * Time between the swipe-up gesture and window drawn of recents activity.
     */
    public static final int ACTION_START_RECENTS_ANIMATION = 8;

    /**
     * Time it takes to for the camera based algorithm to rotate the screen.
     */
    public static final int ACTION_ROTATE_SCREEN_CAMERA_CHECK = 9;

    /**
     * Time it takes the sensor to detect rotation.
     */
    public static final int ACTION_ROTATE_SCREEN_SENSOR = 10;

    /**
     * Time it takes to start unlock animation .
     */
    public static final int ACTION_LOCKSCREEN_UNLOCK = 11;

    /**
     * Time it takes to switch users.
     */
    public static final int ACTION_USER_SWITCH = 12;

    /**
     * Time it takes to turn on the inner screen for a foldable device.
     */
    public static final int ACTION_SWITCH_DISPLAY_UNFOLD = 13;

    /**
     * Time it takes for a UDFPS sensor to appear ready after it is touched.
     */
    public static final int ACTION_UDFPS_ILLUMINATE = 14;

    /**
     * Time it takes for the gesture back affordance arrow to show up.
     */
    public static final int ACTION_SHOW_BACK_ARROW = 15;

    /**
     * Time it takes for loading share sheet.
     */
    public static final int ACTION_LOAD_SHARE_SHEET = 16;

    /**
     * Time it takes for showing the selection toolbar.
     */
    public static final int ACTION_SHOW_SELECTION_TOOLBAR = 17;

    /**
     * Time it takes to show AOD display after folding the device.
     */
    public static final int ACTION_FOLD_TO_AOD = 18;

    /**
     * Time it takes to show the {@link android.service.voice.VoiceInteractionSession} system UI
     * after a {@link android.hardware.soundtrigger3.ISoundTriggerHw} voice trigger.
     */
    public static final int ACTION_SHOW_VOICE_INTERACTION = 19;

    /**
     * Time it takes to request IME shown animation.
     */
    public static final int ACTION_REQUEST_IME_SHOWN = 20;

    /**
     * Time it takes to request IME hidden animation.
     */
    public static final int ACTION_REQUEST_IME_HIDDEN = 21;

    /**
     * Time it takes to load the animation frames in smart space doorbell card.
     * It measures the duration from the images uris are passed into the view
     * to all the frames are loaded.
     * <p/>
     * A long latency makes the doorbell animation looks janky until all the frames are loaded.
     */
    public static final int ACTION_SMARTSPACE_DOORBELL = 22;

    /**
     * Time it takes to lazy-load the image of a {@link android.app.Notification.BigPictureStyle}
     * notification.
     */
    public static final int ACTION_NOTIFICATION_BIG_PICTURE_LOADED = 23;

    /**
     * Time it takes to unlock the device via fps,
     * until either the launcher or the foreground app appears.
     */
    public static final int ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME = 24;

    /**
     * Time it takes to start back preview surface animation after a back gesture starts.
     */
    public static final int ACTION_BACK_SYSTEM_ANIMATION = 25;

    /**
     * Time notifications spent in hidden state for performance reasons. We might temporary
     * hide notifications after display size changes (e.g. fold/unfold of a foldable device)
     * and measure them while they are hidden to unblock rendering of the rest of the UI.
     */
    public static final int ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE = 26;

    /**
     * The same as {@link ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE} but tracks time only
     * when the notifications are hidden and when the shade is open or keyguard is visible.
     */
    public static final int ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN = 27;

    /**
     * Time it takes to unlock the device via face,
     * until either the launcher or the foreground app appears.
     */
    public static final int ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME = 28;

    private static final int[] ACTIONS_ALL = {
        ACTION_EXPAND_PANEL,
        ACTION_TOGGLE_RECENTS,
        ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
        ACTION_CHECK_CREDENTIAL,
        ACTION_CHECK_CREDENTIAL_UNLOCKED,
        ACTION_TURN_ON_SCREEN,
        ACTION_ROTATE_SCREEN,
        ACTION_FACE_WAKE_AND_UNLOCK,
        ACTION_START_RECENTS_ANIMATION,
        ACTION_ROTATE_SCREEN_CAMERA_CHECK,
        ACTION_ROTATE_SCREEN_SENSOR,
        ACTION_LOCKSCREEN_UNLOCK,
        ACTION_USER_SWITCH,
        ACTION_SWITCH_DISPLAY_UNFOLD,
        ACTION_UDFPS_ILLUMINATE,
        ACTION_SHOW_BACK_ARROW,
        ACTION_LOAD_SHARE_SHEET,
        ACTION_SHOW_SELECTION_TOOLBAR,
        ACTION_FOLD_TO_AOD,
        ACTION_SHOW_VOICE_INTERACTION,
        ACTION_REQUEST_IME_SHOWN,
        ACTION_REQUEST_IME_HIDDEN,
        ACTION_SMARTSPACE_DOORBELL,
        ACTION_NOTIFICATION_BIG_PICTURE_LOADED,
        ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME,
        ACTION_BACK_SYSTEM_ANIMATION,
        ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE,
        ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN,
        ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME,
    };

    /** @hide */
    @IntDef({
        ACTION_EXPAND_PANEL,
        ACTION_TOGGLE_RECENTS,
        ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
        ACTION_CHECK_CREDENTIAL,
        ACTION_CHECK_CREDENTIAL_UNLOCKED,
        ACTION_TURN_ON_SCREEN,
        ACTION_ROTATE_SCREEN,
        ACTION_FACE_WAKE_AND_UNLOCK,
        ACTION_START_RECENTS_ANIMATION,
        ACTION_ROTATE_SCREEN_CAMERA_CHECK,
        ACTION_ROTATE_SCREEN_SENSOR,
        ACTION_LOCKSCREEN_UNLOCK,
        ACTION_USER_SWITCH,
        ACTION_SWITCH_DISPLAY_UNFOLD,
        ACTION_UDFPS_ILLUMINATE,
        ACTION_SHOW_BACK_ARROW,
        ACTION_LOAD_SHARE_SHEET,
        ACTION_SHOW_SELECTION_TOOLBAR,
        ACTION_FOLD_TO_AOD,
        ACTION_SHOW_VOICE_INTERACTION,
        ACTION_REQUEST_IME_SHOWN,
        ACTION_REQUEST_IME_HIDDEN,
        ACTION_SMARTSPACE_DOORBELL,
        ACTION_NOTIFICATION_BIG_PICTURE_LOADED,
        ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME,
        ACTION_BACK_SYSTEM_ANIMATION,
        ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE,
        ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN,
        ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    @VisibleForTesting
    public static final int[] STATSD_ACTION = new int[] {
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_EXPAND_PANEL,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_TOGGLE_RECENTS,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL_UNLOCKED,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_TURN_ON_SCREEN,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_FACE_WAKE_AND_UNLOCK,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_START_RECENTS_ANIMATION,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_CAMERA_CHECK,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_SENSOR,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOCKSCREEN_UNLOCK,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_USER_SWITCH,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_SWITCH_DISPLAY_UNFOLD,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_UDFPS_ILLUMINATE,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_BACK_ARROW,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOAD_SHARE_SHEET,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_SELECTION_TOOLBAR,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_FOLD_TO_AOD,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_VOICE_INTERACTION,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_REQUEST_IME_SHOWN,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_REQUEST_IME_HIDDEN,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_SMARTSPACE_DOORBELL,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATION_BIG_PICTURE_LOADED,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_BACK_SYSTEM_ANIMATION,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN,
            UIACTION_LATENCY_REPORTED__ACTION__ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME,
    };

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<Session> mSessions = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<ActionProperties> mActionPropertiesMap = new SparseArray<>();
    @GuardedBy("mLock")
    private boolean mEnabled;
    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            this::updateProperties;

    // Wrapping this in a holder class achieves lazy loading behavior
    private static final class SLatencyTrackerHolder {
        private static final LatencyTracker sLatencyTracker;

        static {
            sLatencyTracker = new LatencyTracker();
            sLatencyTracker.startListeningForLatencyTrackerConfigChanges();
        }
    }

    public static LatencyTracker getInstance(Context context) {
        return SLatencyTrackerHolder.sLatencyTracker;
    }

    /**
     * Constructor for LatencyTracker
     *
     * <p>This constructor is only visible for test classes to inject their own consumer callbacks
     *
     * @param startListeningForPropertyChanges If set, constructor will register for device config
     *                      property updates prior to returning. If not set,
     *                      {@link #startListeningForLatencyTrackerConfigChanges} must be called
     *                      to start listening.
     */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    @VisibleForTesting
    public LatencyTracker() {
        mEnabled = DEFAULT_ENABLED;
    }

    private void updateProperties(DeviceConfig.Properties properties) {
        synchronized (mLock) {
            int samplingInterval = properties.getInt(SETTINGS_SAMPLING_INTERVAL_KEY,
                    DEFAULT_SAMPLING_INTERVAL);
            boolean wasEnabled = mEnabled;
            mEnabled = properties.getBoolean(SETTINGS_ENABLED_KEY, DEFAULT_ENABLED);
            if (wasEnabled != mEnabled) {
                Log.d(TAG, "Latency tracker " + (mEnabled ? "enabled" : "disabled") + ".");
            }
            for (int action : ACTIONS_ALL) {
                String actionName = getNameOfAction(STATSD_ACTION[action]).toLowerCase(Locale.ROOT);
                int legacyActionTraceThreshold = properties.getInt(
                        actionName + LEGACY_TRACE_THRESHOLD_SUFFIX, -1);
                mActionPropertiesMap.put(action, new ActionProperties(action,
                        properties.getBoolean(actionName + ENABLE_SUFFIX, mEnabled),
                        properties.getInt(actionName + SAMPLE_INTERVAL_SUFFIX, samplingInterval),
                        properties.getInt(actionName + TRACE_THRESHOLD_SUFFIX,
                                legacyActionTraceThreshold)));
            }
            onDeviceConfigPropertiesUpdated(mActionPropertiesMap);
        }
    }

    /**
     * Test method to start listening to {@link DeviceConfig} properties changes.
     *
     * <p>During testing, a {@link LatencyTracker} it is desired to stop and start listening for
     * config updates.
     *
     * <p>This is not used for production usages of this class outside of testing as we are
     * using a single static object.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public void startListeningForLatencyTrackerConfigChanges() {
        final Context context = ActivityThread.currentApplication();
        if (context == null) {
            Log.e(
                    TAG,
                    String.format(
                            "No application for package: %s. Latency Tracker Disabled",
                            ActivityThread.currentPackageName()));
            return;
        }
        if (context.checkCallingOrSelfPermission(READ_DEVICE_CONFIG) != PERMISSION_GRANTED) {
            if (DEBUG) {
                synchronized (mLock) {
                    Log.d(TAG, "Initialized the LatencyTracker."
                            + " (No READ_DEVICE_CONFIG permission to change configs)"
                            + " enabled=" + mEnabled + ", package=" + context.getPackageName());
                }
            }
            return;
        }

        // Post initialization to the background in case we're running on the main thread.
        BackgroundThread.getHandler().post(() -> {
            try {
                this.updateProperties(
                        DeviceConfig.getProperties(NAMESPACE_LATENCY_TRACKER));
                DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_LATENCY_TRACKER,
                        BackgroundThread.getExecutor(), mOnPropertiesChangedListener);
            } catch (SecurityException ex) {
                // In case of running tests that the main thread passes the check,
                // but the background thread doesn't have necessary permissions.
                // Swallow it since it's ok to ignore device config changes in the tests.
                Log.d(TAG, "Can't get properties: READ_DEVICE_CONFIG granted="
                        + context.checkCallingOrSelfPermission(READ_DEVICE_CONFIG)
                        + ", package=" + context.getPackageName());
            }
        });
    }

    /**
     * Test method to stop listening to {@link DeviceConfig} properties changes.
     *
     * <p>During testing, a {@link LatencyTracker} it is desired to stop and start listening for
     * config updates.
     *
     * <p>This is not used for production usages of this class outside of testing as we are
     * using a single static object.
     */
    @VisibleForTesting
    public void stopListeningForLatencyTrackerConfigChanges() {
        DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
    }

    /**
     * A helper method to translate action type to name.
     *
     * @param atomsProtoAction the action type defined in AtomsProto.java
     * @return the name of the action
     */
    public static String getNameOfAction(int atomsProtoAction) {
        // Defined in AtomsProto.java
        switch (atomsProtoAction) {
            case UIACTION_LATENCY_REPORTED__ACTION__UNKNOWN_ACTION:
                return "UNKNOWN";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_EXPAND_PANEL:
                return "ACTION_EXPAND_PANEL";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_TOGGLE_RECENTS:
                return "ACTION_TOGGLE_RECENTS";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_FINGERPRINT_WAKE_AND_UNLOCK:
                return "ACTION_FINGERPRINT_WAKE_AND_UNLOCK";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL:
                return "ACTION_CHECK_CREDENTIAL";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL_UNLOCKED:
                return "ACTION_CHECK_CREDENTIAL_UNLOCKED";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_TURN_ON_SCREEN:
                return "ACTION_TURN_ON_SCREEN";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN:
                return "ACTION_ROTATE_SCREEN";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_FACE_WAKE_AND_UNLOCK:
                return "ACTION_FACE_WAKE_AND_UNLOCK";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_START_RECENTS_ANIMATION:
                return "ACTION_START_RECENTS_ANIMATION";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_CAMERA_CHECK:
                return "ACTION_ROTATE_SCREEN_CAMERA_CHECK";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_SENSOR:
                return "ACTION_ROTATE_SCREEN_SENSOR";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOCKSCREEN_UNLOCK:
                return "ACTION_LOCKSCREEN_UNLOCK";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_USER_SWITCH:
                return "ACTION_USER_SWITCH";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_SWITCH_DISPLAY_UNFOLD:
                return "ACTION_SWITCH_DISPLAY_UNFOLD";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_UDFPS_ILLUMINATE:
                return "ACTION_UDFPS_ILLUMINATE";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_BACK_ARROW:
                return "ACTION_SHOW_BACK_ARROW";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOAD_SHARE_SHEET:
                return "ACTION_LOAD_SHARE_SHEET";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_SELECTION_TOOLBAR:
                return "ACTION_SHOW_SELECTION_TOOLBAR";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_FOLD_TO_AOD:
                return "ACTION_FOLD_TO_AOD";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_VOICE_INTERACTION:
                return "ACTION_SHOW_VOICE_INTERACTION";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_REQUEST_IME_SHOWN:
                return "ACTION_REQUEST_IME_SHOWN";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_REQUEST_IME_HIDDEN:
                return "ACTION_REQUEST_IME_HIDDEN";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_SMARTSPACE_DOORBELL:
                return "ACTION_SMARTSPACE_DOORBELL";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATION_BIG_PICTURE_LOADED:
                return "ACTION_NOTIFICATION_BIG_PICTURE_LOADED";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME:
                return "ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_BACK_SYSTEM_ANIMATION:
                return "ACTION_BACK_SYSTEM_ANIMATION";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE:
                return "ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN:
                return "ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN";
            case UIACTION_LATENCY_REPORTED__ACTION__ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME:
                return "ACTION_KEYGUARD_FACE_UNLOCK_TO_HOME";
            default:
                throw new IllegalArgumentException("Invalid action");
        }
    }

    private static String getTraceNameOfAction(@Action int action, String tag) {
        if (TextUtils.isEmpty(tag)) {
            return "L<" + getNameOfAction(STATSD_ACTION[action]) + ">";
        } else {
            return "L<" + getNameOfAction(STATSD_ACTION[action]) + "::" + tag + ">";
        }
    }

    private static String getTraceTriggerNameForAction(@Action int action) {
        return "com.android.telemetry.latency-tracker-" + getNameOfAction(STATSD_ACTION[action]);
    }

    /**
     * @deprecated Use {@link #isEnabled(Context, int)}
     */
    @Deprecated
    public static boolean isEnabled(Context ctx) {
        return getInstance(ctx).isEnabled();
    }

    /**
     * @deprecated Used {@link #isEnabled(int)}
     */
    @Deprecated
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    public static boolean isEnabled(Context ctx, int action) {
        return getInstance(ctx).isEnabled(action);
    }

    public boolean isEnabled(int action) {
        synchronized (mLock) {
            ActionProperties actionProperties = mActionPropertiesMap.get(action);
            if (actionProperties != null) {
                return actionProperties.isEnabled();
            }
            return false;
        }
    }

    /**
     * Notifies that an action is starting. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to start. One of the ACTION_* values.
     */
    public void onActionStart(@Action int action) {
        onActionStart(action, null);
    }

    /**
     * Notifies that an action is starting. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to start. One of the ACTION_* values.
     * @param tag The brief description of the action.
     */
    public void onActionStart(@Action int action, String tag) {
        synchronized (mLock) {
            if (!isEnabled(action)) {
                return;
            }
            // skip if the action is already instrumenting.
            if (mSessions.get(action) != null) {
                return;
            }
            Session session = new Session(action, tag);
            session.begin(() -> onActionCancel(action));
            mSessions.put(action, session);

            if (DEBUG) {
                Log.d(TAG, "onActionStart: " + session.name() + ", start=" + session.mStartRtc);
            }
        }
    }

    /**
     * Notifies that an action has ended. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to end. One of the ACTION_* values.
     */
    public void onActionEnd(@Action int action) {
        synchronized (mLock) {
            if (!isEnabled(action)) {
                return;
            }
            Session session = mSessions.get(action);
            if (session == null) {
                return;
            }
            session.end();
            mSessions.delete(action);
            logAction(action, session.duration());

            if (DEBUG) {
                Log.d(TAG, "onActionEnd:" + session.name() + ", duration=" + session.duration());
            }
        }
    }

    /**
     * Notifies that an action has canceled. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to cancel. One of the ACTION_* values.
     * @hide
     */
    public void onActionCancel(@Action int action) {
        synchronized (mLock) {
            Session session = mSessions.get(action);
            if (session == null) {
                return;
            }
            session.cancel();
            mSessions.delete(action);

            if (DEBUG) {
                Log.d(TAG, "onActionCancel: " + session.name());
            }
        }
    }

    /**
     * Testing API to get the time when a given action was started.
     *
     * @param action Action which to retrieve start time from
     * @return Elapsed realtime timestamp when the action started. -1 if the action is not active.
     * @hide
     */
    @VisibleForTesting
    @ElapsedRealtimeLong
    public long getActiveActionStartTime(@Action int action) {
        synchronized (mLock) {
            if (mSessions.contains(action)) {
                return mSessions.get(action).mStartRtc;
            }
            return -1;
        }
    }

    /**
     * Logs an action that has started and ended. This needs to be called from the main thread.
     *
     * @param action   The action to end. One of the ACTION_* values.
     * @param duration The duration of the action in ms.
     */
    public void logAction(@Action int action, int duration) {
        boolean shouldSample;
        int traceThreshold;
        synchronized (mLock) {
            if (!isEnabled(action)) {
                return;
            }
            ActionProperties actionProperties = mActionPropertiesMap.get(action);
            if (actionProperties == null) {
                return;
            }
            int nextRandNum = ThreadLocalRandom.current().nextInt(
                    actionProperties.getSamplingInterval());
            shouldSample = nextRandNum == 0;
            traceThreshold = actionProperties.getTraceThreshold();
        }

        boolean shouldTriggerPerfettoTrace = traceThreshold > 0 && duration >= traceThreshold;

        if (DEBUG) {
            Log.i(TAG, "logAction: " + getNameOfAction(STATSD_ACTION[action])
                    + " duration=" + duration
                    + " shouldSample=" + shouldSample
                    + " shouldTriggerPerfettoTrace=" + shouldTriggerPerfettoTrace);
        }

        EventLog.writeEvent(EventLogTags.SYSUI_LATENCY, action, duration);
        if (shouldTriggerPerfettoTrace) {
            onTriggerPerfetto(getTraceTriggerNameForAction(action));
        }
        if (shouldSample) {
            onLogToFrameworkStats(
                    new FrameworkStatsLogEvent(action, FrameworkStatsLog.UI_ACTION_LATENCY_REPORTED,
                            STATSD_ACTION[action], duration)
            );
        }
    }

    static class Session {
        @Action
        private final int mAction;
        private final String mTag;
        private final String mName;
        private Runnable mTimeoutRunnable;
        private long mStartRtc = -1;
        private long mEndRtc = -1;

        Session(@Action int action, @Nullable String tag) {
            mAction = action;
            mTag = tag;
            mName = TextUtils.isEmpty(mTag)
                    ? getNameOfAction(STATSD_ACTION[mAction])
                    : getNameOfAction(STATSD_ACTION[mAction]) + "::" + mTag;
        }

        String name() {
            return mName;
        }

        String traceName() {
            return getTraceNameOfAction(mAction, mTag);
        }

        void begin(@NonNull Runnable timeoutAction) {
            mStartRtc = SystemClock.elapsedRealtime();
            Trace.asyncTraceForTrackBegin(TRACE_TAG_APP, traceName(), traceName(), 0);

            // start counting timeout.
            mTimeoutRunnable = () -> {
                Trace.instantForTrack(TRACE_TAG_APP, traceName(), "timeout");
                timeoutAction.run();
            };
            BackgroundThread.getHandler()
                    .postDelayed(mTimeoutRunnable, TimeUnit.SECONDS.toMillis(15));
        }

        void end() {
            mEndRtc = SystemClock.elapsedRealtime();
            Trace.asyncTraceForTrackEnd(TRACE_TAG_APP, traceName(), 0);
            BackgroundThread.getHandler().removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }

        void cancel() {
            Trace.instantForTrack(TRACE_TAG_APP, traceName(), "cancel");
            Trace.asyncTraceForTrackEnd(TRACE_TAG_APP, traceName(), 0);
            BackgroundThread.getHandler().removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }

        int duration() {
            return (int) (mEndRtc - mStartRtc);
        }
    }

    @VisibleForTesting
    public static class ActionProperties {
        static final String ENABLE_SUFFIX = "_enable";
        static final String SAMPLE_INTERVAL_SUFFIX = "_sample_interval";
        // TODO: migrate all usages of the legacy trace threshold property
        static final String LEGACY_TRACE_THRESHOLD_SUFFIX = "";
        static final String TRACE_THRESHOLD_SUFFIX = "_trace_threshold";

        @Action
        private final int mAction;
        private final boolean mEnabled;
        private final int mSamplingInterval;
        private final int mTraceThreshold;

        @VisibleForTesting
        public ActionProperties(
                @Action int action,
                boolean enabled,
                int samplingInterval,
                int traceThreshold) {
            this.mAction = action;
            com.android.internal.util.AnnotationValidations.validate(
                    Action.class, null, mAction);
            this.mEnabled = enabled;
            this.mSamplingInterval = samplingInterval;
            this.mTraceThreshold = traceThreshold;
        }

        @VisibleForTesting
        @Action
        public int getAction() {
            return mAction;
        }

        @VisibleForTesting
        public boolean isEnabled() {
            return mEnabled;
        }

        @VisibleForTesting
        public int getSamplingInterval() {
            return mSamplingInterval;
        }

        @VisibleForTesting
        public int getTraceThreshold() {
            return mTraceThreshold;
        }

        @Override
        public String toString() {
            return "ActionProperties{"
                    + " mAction=" + mAction
                    + ", mEnabled=" + mEnabled
                    + ", mSamplingInterval=" + mSamplingInterval
                    + ", mTraceThreshold=" + mTraceThreshold
                    + "}";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o == null) {
                return false;
            }
            if (!(o instanceof ActionProperties)) {
                return false;
            }
            ActionProperties that = (ActionProperties) o;
            return mAction == that.mAction
                    && mEnabled == that.mEnabled
                    && mSamplingInterval == that.mSamplingInterval
                    && mTraceThreshold == that.mTraceThreshold;
        }

        @Override
        public int hashCode() {
            int _hash = 1;
            _hash = 31 * _hash + mAction;
            _hash = 31 * _hash + Boolean.hashCode(mEnabled);
            _hash = 31 * _hash + mSamplingInterval;
            _hash = 31 * _hash + mTraceThreshold;
            return _hash;
        }
    }

    /**
     * Testing method intended to be overridden to determine when the LatencyTracker's device
     * properties are updated.
     */
    @VisibleForTesting
    public void onDeviceConfigPropertiesUpdated(SparseArray<ActionProperties> actionProperties) {
        if (DEBUG) {
            Log.d(TAG, "onDeviceConfigPropertiesUpdated: " + actionProperties);
        }
    }

    /**
     * Testing class intended to be overridden to determine when LatencyTracker triggers perfetto.
     */
    @VisibleForTesting
    public void onTriggerPerfetto(String triggerName) {
        if (DEBUG) {
            Log.i(TAG, "onTriggerPerfetto: triggerName=" + triggerName);
        }
        PerfettoTrigger.trigger(triggerName);
    }

    /**
     * Testing method intended to be overridden to determine when LatencyTracker writes to
     * FrameworkStatsLog.
     */
    @VisibleForTesting
    public void onLogToFrameworkStats(FrameworkStatsLogEvent event) {
        if (DEBUG) {
            Log.i(TAG, "onLogToFrameworkStats: event=" + event);
        }
        FrameworkStatsLog.write(event.logCode, event.statsdAction, event.durationMillis);
    }

    /**
     * Testing class intended to reject what should be written to the {@link FrameworkStatsLog}
     *
     * <p>This class is used in {@link #onLogToFrameworkStats(FrameworkStatsLogEvent)} for test code
     * to observer when and what information is being logged by {@link LatencyTracker}
     */
    @VisibleForTesting
    public static class FrameworkStatsLogEvent {

        @VisibleForTesting
        public final int action;
        @VisibleForTesting
        public final int logCode;
        @VisibleForTesting
        public final int statsdAction;
        @VisibleForTesting
        public final int durationMillis;

        private FrameworkStatsLogEvent(int action, int logCode, int statsdAction,
                int durationMillis) {
            this.action = action;
            this.logCode = logCode;
            this.statsdAction = statsdAction;
            this.durationMillis = durationMillis;
        }

        @Override
        public String toString() {
            return "FrameworkStatsLogEvent{"
                    + " logCode=" + logCode
                    + ", statsdAction=" + statsdAction
                    + ", durationMillis=" + durationMillis
                    + "}";
        }
    }
}
