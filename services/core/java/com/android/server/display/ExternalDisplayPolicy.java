/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display;

import static android.hardware.display.DisplayManagerGlobal.EVENT_DISPLAY_CONNECTED;
import static android.os.Temperature.THROTTLING_CRITICAL;
import static android.os.Temperature.THROTTLING_NONE;
import static android.view.Display.TYPE_EXTERNAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerGlobal.DisplayEvent;
import android.os.Build;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.Temperature.ThrottlingStatus;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayManagerService.SyncRoot;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.notifications.DisplayNotificationManager;
import com.android.server.display.utils.DebugUtils;

/**
 * Listens for Skin thermal sensor events, disables external displays if thermal status becomes
 * equal or above {@link android.os.Temperature#THROTTLING_CRITICAL}, enables external displays if
 * status goes below {@link android.os.Temperature#THROTTLING_CRITICAL}.
 */
class ExternalDisplayPolicy {
    private static final String TAG = "ExternalDisplayPolicy";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.ExternalDisplayPolicy DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    @VisibleForTesting
    static final String ENABLE_ON_CONNECT = "persist.sys.display.enable_on_connect.external";

    static boolean isExternalDisplayLocked(@NonNull final LogicalDisplay logicalDisplay) {
        return logicalDisplay.getDisplayInfoLocked().type == TYPE_EXTERNAL;
    }

    /**
     * Injector interface for {@link ExternalDisplayPolicy}
     */
    interface Injector {
        void sendExternalDisplayEventLocked(@NonNull LogicalDisplay display,
                @DisplayEvent int event);

        @NonNull
        LogicalDisplayMapper getLogicalDisplayMapper();

        @NonNull
        SyncRoot getSyncRoot();

        @Nullable
        IThermalService getThermalService();

        @NonNull
        DisplayManagerFlags getFlags();

        @NonNull
        DisplayNotificationManager getDisplayNotificationManager();

        @NonNull
        Handler getHandler();

        @NonNull
        ExternalDisplayStatsService getExternalDisplayStatsService();
    }

    @NonNull
    private final Injector mInjector;
    @NonNull
    private final LogicalDisplayMapper mLogicalDisplayMapper;
    @NonNull
    private final SyncRoot mSyncRoot;
    @NonNull
    private final DisplayManagerFlags mFlags;
    @NonNull
    private final DisplayNotificationManager mDisplayNotificationManager;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final ExternalDisplayStatsService mExternalDisplayStatsService;
    @ThrottlingStatus
    private volatile int mStatus = THROTTLING_NONE;

    ExternalDisplayPolicy(@NonNull final Injector injector) {
        mInjector = injector;
        mLogicalDisplayMapper = mInjector.getLogicalDisplayMapper();
        mSyncRoot = mInjector.getSyncRoot();
        mFlags = mInjector.getFlags();
        mDisplayNotificationManager = mInjector.getDisplayNotificationManager();
        mHandler = mInjector.getHandler();
        mExternalDisplayStatsService = mInjector.getExternalDisplayStatsService();
    }

    /**
     * Starts listening for temperature changes.
     */
    void onBootCompleted() {
        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            if (DEBUG) {
                Slog.d(TAG, "External display management is not enabled on your device:"
                                    + " cannot register thermal listener.");
            }
            return;
        }

        if (!mFlags.isConnectedDisplayErrorHandlingEnabled()) {
            if (DEBUG) {
                Slog.d(TAG, "ConnectedDisplayErrorHandlingEnabled is not enabled on your device:"
                                    + " cannot register thermal listener.");
            }
            return;
        }

        if (!registerThermalServiceListener(new SkinThermalStatusObserver())) {
            Slog.e(TAG, "Failed to register thermal listener");
        }
    }

    /**
     * Checks the display type is external, and if it is external then enables/disables it.
     */
    void setExternalDisplayEnabledLocked(@NonNull final LogicalDisplay logicalDisplay,
            final boolean enabled) {
        if (!isExternalDisplayLocked(logicalDisplay)) {
            Slog.e(TAG, "setExternalDisplayEnabledLocked called for non external display");
            return;
        }

        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            if (DEBUG) {
                Slog.d(TAG, "setExternalDisplayEnabledLocked: External display management is not"
                                    + " enabled on your device, cannot enable/disable display.");
            }
            return;
        }

        if (enabled && !isExternalDisplayAllowed()) {
            Slog.w(TAG, "setExternalDisplayEnabledLocked: External display can not be enabled"
                                + " because it is currently not allowed.");
            mHandler.post(mDisplayNotificationManager::onHighTemperatureExternalDisplayNotAllowed);
            return;
        }

        mLogicalDisplayMapper.setDisplayEnabledLocked(logicalDisplay, enabled);
    }

    /**
     * Upon external display became available check if external displays allowed, this display
     * is disabled and then sends {@link DisplayManagerGlobal#EVENT_DISPLAY_CONNECTED} to allow
     * user to decide how to use this display.
     */
    void handleExternalDisplayConnectedLocked(@NonNull final LogicalDisplay logicalDisplay) {
        if (!isExternalDisplayLocked(logicalDisplay)) {
            Slog.e(TAG, "handleExternalDisplayConnectedLocked called for non-external display");
            return;
        }

        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            if (DEBUG) {
                Slog.d(TAG, "handleExternalDisplayConnectedLocked connected display management"
                                    + " flag is off");
            }
            return;
        }

        mExternalDisplayStatsService.onDisplayConnected(logicalDisplay);

        if ((Build.IS_ENG || Build.IS_USERDEBUG)
                && SystemProperties.getBoolean(ENABLE_ON_CONNECT, false)) {
            Slog.w(TAG, "External display is enabled by default, bypassing user consent.");
            mInjector.sendExternalDisplayEventLocked(logicalDisplay, EVENT_DISPLAY_CONNECTED);
            return;
        } else {
            // As external display is enabled by default, need to disable it now.
            // TODO(b/292196201) Remove when the display can be disabled before DPC is created.
            logicalDisplay.setEnabledLocked(false);
        }

        if (!isExternalDisplayAllowed()) {
            Slog.w(TAG, "handleExternalDisplayConnectedLocked: External display can not be used"
                                + " because it is currently not allowed.");
            mDisplayNotificationManager.onHighTemperatureExternalDisplayNotAllowed();
            return;
        }

        mInjector.sendExternalDisplayEventLocked(logicalDisplay, EVENT_DISPLAY_CONNECTED);

        if (DEBUG) {
            Slog.d(TAG, "handleExternalDisplayConnectedLocked complete"
                                + " displayId=" + logicalDisplay.getDisplayIdLocked());
        }
    }

    /**
     * Upon external display become unavailable.
     */
    void handleLogicalDisplayDisconnectedLocked(@NonNull final LogicalDisplay logicalDisplay) {
        // Type of the display here is always UNKNOWN, so we can't verify it is an external display

        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            return;
        }

        mExternalDisplayStatsService.onDisplayDisconnected(logicalDisplay.getDisplayIdLocked());
    }

    /**
     * Upon external display gets added.
     */
    void handleLogicalDisplayAddedLocked(@NonNull final LogicalDisplay logicalDisplay) {
        if (!isExternalDisplayLocked(logicalDisplay)) {
            return;
        }

        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            return;
        }

        mExternalDisplayStatsService.onDisplayAdded(logicalDisplay.getDisplayIdLocked());
    }

    /**
     * Upon presentation started.
     */
    void onPresentation(int displayId, boolean isShown) {
        synchronized (mSyncRoot) {
            var logicalDisplay = mLogicalDisplayMapper.getDisplayLocked(displayId);
            if (logicalDisplay == null || !isExternalDisplayLocked(logicalDisplay)) {
                return;
            }
        }

        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            return;
        }

        if (isShown) {
            mExternalDisplayStatsService.onPresentationWindowAdded(displayId);
        } else {
            mExternalDisplayStatsService.onPresentationWindowRemoved(displayId);
        }
    }

    @GuardedBy("mSyncRoot")
    private void disableExternalDisplayLocked(@NonNull final LogicalDisplay logicalDisplay) {
        if (!isExternalDisplayLocked(logicalDisplay)) {
            return;
        }

        if (!mFlags.isConnectedDisplayManagementEnabled()) {
            Slog.e(TAG, "disableExternalDisplayLocked shouldn't be called when the"
                                + " connected display management flag is off");
            return;
        }

        if (!mFlags.isConnectedDisplayErrorHandlingEnabled()) {
            if (DEBUG) {
                Slog.d(TAG, "disableExternalDisplayLocked shouldn't be called when the"
                                    + " error handling flag is off");
            }
            return;
        }

        if (!logicalDisplay.isEnabledLocked()) {
            if (DEBUG) {
                Slog.d(TAG, "disableExternalDisplayLocked is not allowed:"
                                    + " displayId=" + logicalDisplay.getDisplayIdLocked()
                                    + " isEnabledLocked=false");
            }
            return;
        }

        if (!isExternalDisplayAllowed()) {
            Slog.w(TAG, "External display is currently not allowed and is getting disabled.");
            mDisplayNotificationManager.onHighTemperatureExternalDisplayNotAllowed();
        }

        mLogicalDisplayMapper.setDisplayEnabledLocked(logicalDisplay, /*enabled=*/ false);

        mExternalDisplayStatsService.onDisplayDisabled(logicalDisplay.getDisplayIdLocked());

        if (DEBUG) {
            Slog.d(TAG, "disableExternalDisplayLocked complete"
                                + " displayId=" + logicalDisplay.getDisplayIdLocked());
        }
    }

    /**
     * @return whether external displays use is currently allowed.
     */
    @VisibleForTesting
    boolean isExternalDisplayAllowed() {
        return mStatus < THROTTLING_CRITICAL;
    }

    private boolean registerThermalServiceListener(
            @NonNull final IThermalEventListener.Stub listener) {
        final var thermalService = mInjector.getThermalService();
        if (thermalService == null) {
            Slog.w(TAG, "Could not observe thermal status. Service not available");
            return false;
        }
        try {
            thermalService.registerThermalEventListenerWithType(listener, Temperature.TYPE_SKIN);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register thermal status listener", e);
            return false;
        }
        if (DEBUG) {
            Slog.d(TAG, "registerThermalServiceListener complete.");
        }
        return true;
    }

    private void disableExternalDisplays() {
        synchronized (mSyncRoot) {
            mLogicalDisplayMapper.forEachLocked(this::disableExternalDisplayLocked);
        }
    }

    private final class SkinThermalStatusObserver extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(@NonNull final Temperature temp) {
            @ThrottlingStatus final int newStatus = temp.getStatus();
            final var previousStatus = mStatus;
            mStatus = newStatus;
            if (THROTTLING_CRITICAL > previousStatus && THROTTLING_CRITICAL <= newStatus) {
                disableExternalDisplays();
            }
        }
    }
}
