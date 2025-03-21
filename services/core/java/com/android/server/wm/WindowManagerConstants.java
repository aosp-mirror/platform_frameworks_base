/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.provider.AndroidDeviceConfig.KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE;
import static android.provider.AndroidDeviceConfig.KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP;

import android.provider.AndroidDeviceConfig;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Settings constants that can modify the window manager's behavior.
 */
final class WindowManagerConstants {

    /**
     * The orientation of activity will be always "unspecified" except for game apps.
     * <p>Possible values:
     * <ul>
     * <li>false: applies to no apps (default)</li>
     * <li>true: applies to all apps</li>
     * </ul>
     */
    private static final String KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST =
            "ignore_activity_orientation_request";

    /**
     * The orientation of activity will be always "unspecified" except for game apps.
     * <p>Possible values:
     * <ul>
     * <li>none: applies to no apps (default)</li>
     * <li>all: applies to all apps ({@see #KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST})</li>
     * <li>large: applies to all apps but only on large screens</li>
     * </ul>
     */
    private static final String KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST_SCREENS =
            "ignore_activity_orientation_request_screens";

    /** The packages that ignore {@link #KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST}. */
    private static final String KEY_OPT_OUT_IGNORE_ACTIVITY_ORIENTATION_REQUEST_LIST =
            "opt_out_ignore_activity_orientation_request_list";

    /**
     * The minimum duration between gesture exclusion logging for a given window in
     * milliseconds.
     *
     * Events that happen in-between will be silently dropped.
     *
     * A non-positive value disables logging.
     *
     * <p>Note: On Devices running Q, this key is in the "android:window_manager" namespace.
     *
     * @see android.provider.DeviceConfig#NAMESPACE_WINDOW_MANAGER
     */
    static final String KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS =
            "system_gesture_exclusion_log_debounce_millis";

    private static final int MIN_GESTURE_EXCLUSION_LIMIT_DP = 200;

    /** @see #KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS */
    long mSystemGestureExclusionLogDebounceTimeoutMillis;
    /** @see AndroidDeviceConfig#KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP */
    int mSystemGestureExclusionLimitDp;
    /** @see AndroidDeviceConfig#KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE */
    boolean mSystemGestureExcludedByPreQStickyImmersive;

    /** @see #KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST */
    boolean mIgnoreActivityOrientationRequestLargeScreen;
    boolean mIgnoreActivityOrientationRequestSmallScreen;

    /** @see #KEY_OPT_OUT_IGNORE_ACTIVITY_ORIENTATION_REQUEST_LIST */
    private ArraySet<String> mOptOutIgnoreActivityOrientationRequestPackages;

    private final WindowManagerGlobalLock mGlobalLock;
    private final Runnable mUpdateSystemGestureExclusionCallback;
    private final DeviceConfigInterface mDeviceConfig;
    private final DeviceConfig.OnPropertiesChangedListener mListenerAndroid;
    private final DeviceConfig.OnPropertiesChangedListener mListenerWindowManager;

    WindowManagerConstants(WindowManagerService service, DeviceConfigInterface deviceConfig) {
        this(service.mGlobalLock, () -> service.mRoot.forAllDisplays(
                DisplayContent::updateSystemGestureExclusionLimit), deviceConfig);
    }

    @VisibleForTesting
    WindowManagerConstants(WindowManagerGlobalLock globalLock,
            Runnable updateSystemGestureExclusionCallback,
            DeviceConfigInterface deviceConfig) {
        mGlobalLock = Objects.requireNonNull(globalLock);
        mUpdateSystemGestureExclusionCallback = Objects.requireNonNull(updateSystemGestureExclusionCallback);
        mDeviceConfig = deviceConfig;
        mListenerAndroid = this::onAndroidPropertiesChanged;
        mListenerWindowManager = this::onWindowPropertiesChanged;
    }

    void start(Executor executor) {
        mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ANDROID, executor,
                mListenerAndroid);
        mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                executor, mListenerWindowManager);

        updateSystemGestureExclusionLogDebounceMillis();
        updateSystemGestureExclusionLimitDp();
        updateSystemGestureExcludedByPreQStickyImmersive();
        updateIgnoreActivityOrientationRequest();
        updateOptOutIgnoreActivityOrientationRequestList();
    }

    private void onAndroidPropertiesChanged(DeviceConfig.Properties properties) {
        synchronized (mGlobalLock) {
            boolean updateSystemGestureExclusionLimit = false;
            for (String name : properties.getKeyset()) {
                if (name == null) {
                    return;
                }
                switch (name) {
                    case KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP:
                        updateSystemGestureExclusionLimitDp();
                        updateSystemGestureExclusionLimit = true;
                        break;
                    case KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE:
                        updateSystemGestureExcludedByPreQStickyImmersive();
                        updateSystemGestureExclusionLimit = true;
                        break;
                    default:
                        break;
                }
            }
            if (updateSystemGestureExclusionLimit) {
                mUpdateSystemGestureExclusionCallback.run();
            }
        }
    }

    private void onWindowPropertiesChanged(DeviceConfig.Properties properties) {
        synchronized (mGlobalLock) {
            for (String name : properties.getKeyset()) {
                if (name == null) {
                    return;
                }
                switch (name) {
                    case KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS:
                        updateSystemGestureExclusionLogDebounceMillis();
                        break;
                    case KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST:
                    case KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST_SCREENS:
                        updateIgnoreActivityOrientationRequest();
                        break;
                    case KEY_OPT_OUT_IGNORE_ACTIVITY_ORIENTATION_REQUEST_LIST:
                        updateOptOutIgnoreActivityOrientationRequestList();
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void updateSystemGestureExclusionLogDebounceMillis() {
        mSystemGestureExclusionLogDebounceTimeoutMillis =
                mDeviceConfig.getLong(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                        KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS, 0);
    }

    private void updateSystemGestureExclusionLimitDp() {
        mSystemGestureExclusionLimitDp = Math.max(MIN_GESTURE_EXCLUSION_LIMIT_DP,
                mDeviceConfig.getInt(DeviceConfig.NAMESPACE_ANDROID,
                        KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP, 0));
    }

    private void updateSystemGestureExcludedByPreQStickyImmersive() {
        mSystemGestureExcludedByPreQStickyImmersive = mDeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ANDROID,
                KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE, false);
    }

    private void updateIgnoreActivityOrientationRequest() {
        boolean allScreens = mDeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST, false);
        String whichScreens = mDeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST_SCREENS);
        allScreens |= ("all".equalsIgnoreCase(whichScreens));
        boolean largeScreens = allScreens || ("large".equalsIgnoreCase(whichScreens));
        mIgnoreActivityOrientationRequestSmallScreen = allScreens;
        mIgnoreActivityOrientationRequestLargeScreen = largeScreens;
    }

    private void updateOptOutIgnoreActivityOrientationRequestList() {
        final String packageList = mDeviceConfig.getString(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_OPT_OUT_IGNORE_ACTIVITY_ORIENTATION_REQUEST_LIST, "");
        if (packageList.isEmpty()) {
            mOptOutIgnoreActivityOrientationRequestPackages = null;
            return;
        }
        mOptOutIgnoreActivityOrientationRequestPackages = new ArraySet<>();
        mOptOutIgnoreActivityOrientationRequestPackages.addAll(
                Arrays.asList(packageList.split(",")));
    }

    boolean isPackageOptOutIgnoreActivityOrientationRequest(String packageName) {
        return mOptOutIgnoreActivityOrientationRequestPackages != null
                && mOptOutIgnoreActivityOrientationRequestPackages.contains(packageName);
    }

    void dump(PrintWriter pw) {
        pw.println("WINDOW MANAGER CONSTANTS (dumpsys window constants):");

        pw.print("  "); pw.print(KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS);
        pw.print("="); pw.println(mSystemGestureExclusionLogDebounceTimeoutMillis);
        pw.print("  "); pw.print(KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP);
        pw.print("="); pw.println(mSystemGestureExclusionLimitDp);
        pw.print("  "); pw.print(KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE);
        pw.print("="); pw.println(mSystemGestureExcludedByPreQStickyImmersive);
        pw.print("  "); pw.print(KEY_IGNORE_ACTIVITY_ORIENTATION_REQUEST_SCREENS);
        pw.print("="); pw.println(mIgnoreActivityOrientationRequestSmallScreen ? "all"
                : mIgnoreActivityOrientationRequestLargeScreen ? "large" : "none");
        if (mOptOutIgnoreActivityOrientationRequestPackages != null) {
            pw.print("  "); pw.print(KEY_OPT_OUT_IGNORE_ACTIVITY_ORIENTATION_REQUEST_LIST);
            pw.print("="); pw.println(mOptOutIgnoreActivityOrientationRequestPackages);
        }
        pw.println();
    }
}
