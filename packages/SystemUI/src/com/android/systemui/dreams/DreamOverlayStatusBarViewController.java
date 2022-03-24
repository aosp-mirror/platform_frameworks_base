/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.PluralsMessageFormatter;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.time.DateFormatUtil;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * View controller for {@link DreamOverlayStatusBarView}.
 */
@DreamOverlayComponent.DreamOverlayScope
public class DreamOverlayStatusBarViewController extends ViewController<DreamOverlayStatusBarView> {
    private final ConnectivityManager mConnectivityManager;
    private final TouchInsetManager.TouchInsetSession mTouchInsetSession;
    private final NextAlarmController mNextAlarmController;
    private final AlarmManager mAlarmManager;
    private final Resources mResources;
    private final DateFormatUtil mDateFormatUtil;
    private final IndividualSensorPrivacyController mSensorPrivacyController;
    private final DreamOverlayNotificationCountProvider mDreamOverlayNotificationCountProvider;
    private final ZenModeController mZenModeController;
    private final Executor mMainExecutor;

    private boolean mIsAttached;

    private final NetworkRequest mNetworkRequest = new NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(
                Network network, NetworkCapabilities networkCapabilities) {
            updateWifiUnavailableStatusIcon();
        }

        @Override
        public void onAvailable(Network network) {
            updateWifiUnavailableStatusIcon();
        }

        @Override
        public void onLost(Network network) {
            updateWifiUnavailableStatusIcon();
        }
    };

    private final IndividualSensorPrivacyController.Callback mSensorCallback =
            (sensor, blocked) -> updateMicCameraBlockedStatusIcon();

    private final NextAlarmController.NextAlarmChangeCallback mNextAlarmCallback =
            nextAlarm -> updateAlarmStatusIcon();

    private final ZenModeController.Callback mZenModeCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(int zen) {
            updatePriorityModeStatusIcon();
        }
    };

    private final DreamOverlayNotificationCountProvider.Callback mNotificationCountCallback =
            notificationCount -> showIcon(
                    DreamOverlayStatusBarView.STATUS_ICON_NOTIFICATIONS,
                    notificationCount > 0,
                    notificationCount > 0
                            ? buildNotificationsContentDescription(notificationCount)
                            : null);

    @Inject
    public DreamOverlayStatusBarViewController(
            DreamOverlayStatusBarView view,
            @Main Resources resources,
            @Main Executor mainExecutor,
            ConnectivityManager connectivityManager,
            TouchInsetManager.TouchInsetSession touchInsetSession,
            AlarmManager alarmManager,
            NextAlarmController nextAlarmController,
            DateFormatUtil dateFormatUtil,
            IndividualSensorPrivacyController sensorPrivacyController,
            DreamOverlayNotificationCountProvider dreamOverlayNotificationCountProvider,
            ZenModeController zenModeController) {
        super(view);
        mResources = resources;
        mMainExecutor = mainExecutor;
        mConnectivityManager = connectivityManager;
        mTouchInsetSession = touchInsetSession;
        mAlarmManager = alarmManager;
        mNextAlarmController = nextAlarmController;
        mDateFormatUtil = dateFormatUtil;
        mSensorPrivacyController = sensorPrivacyController;
        mDreamOverlayNotificationCountProvider = dreamOverlayNotificationCountProvider;
        mZenModeController = zenModeController;
    }

    @Override
    protected void onViewAttached() {
        mIsAttached = true;

        mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);
        updateWifiUnavailableStatusIcon();

        mNextAlarmController.addCallback(mNextAlarmCallback);
        updateAlarmStatusIcon();

        mSensorPrivacyController.addCallback(mSensorCallback);
        updateMicCameraBlockedStatusIcon();

        mZenModeController.addCallback(mZenModeCallback);
        updatePriorityModeStatusIcon();

        mDreamOverlayNotificationCountProvider.addCallback(mNotificationCountCallback);
        mTouchInsetSession.addViewToTracking(mView);
    }

    @Override
    protected void onViewDetached() {
        mZenModeController.removeCallback(mZenModeCallback);
        mSensorPrivacyController.removeCallback(mSensorCallback);
        mNextAlarmController.removeCallback(mNextAlarmCallback);
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mDreamOverlayNotificationCountProvider.removeCallback(mNotificationCountCallback);
        mTouchInsetSession.clear();

        mIsAttached = false;
    }

    private void updateWifiUnavailableStatusIcon() {
        final NetworkCapabilities capabilities =
                mConnectivityManager.getNetworkCapabilities(
                        mConnectivityManager.getActiveNetwork());
        final boolean available = capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, !available);
    }

    private void updateAlarmStatusIcon() {
        final AlarmManager.AlarmClockInfo alarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        final boolean hasAlarm = alarm != null && alarm.getTriggerTime() > 0;
        showIcon(
                DreamOverlayStatusBarView.STATUS_ICON_ALARM_SET,
                hasAlarm,
                hasAlarm ? buildAlarmContentDescription(alarm) : null);
    }

    private String buildAlarmContentDescription(AlarmManager.AlarmClockInfo alarm) {
        final String skeleton = mDateFormatUtil.is24HourFormat() ? "EHm" : "Ehma";
        final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        final String dateString = DateFormat.format(pattern, alarm.getTriggerTime()).toString();

        return mResources.getString(R.string.accessibility_quick_settings_alarm, dateString);
    }

    private void updateMicCameraBlockedStatusIcon() {
        final boolean micBlocked = mSensorPrivacyController
                .isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE);
        final boolean cameraBlocked = mSensorPrivacyController
                .isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA);
        showIcon(
                DreamOverlayStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED,
                micBlocked && cameraBlocked);
    }

    private String buildNotificationsContentDescription(int notificationCount) {
        return PluralsMessageFormatter.format(
                mResources,
                Map.of("count", notificationCount),
                R.string.dream_overlay_status_bar_notification_indicator);
    }

    private void updatePriorityModeStatusIcon() {
        showIcon(
                DreamOverlayStatusBarView.STATUS_ICON_PRIORITY_MODE_ON,
                mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF);
    }

    private void showIcon(@DreamOverlayStatusBarView.StatusIconType int iconType, boolean show) {
        showIcon(iconType, show, null);
    }

    private void showIcon(
            @DreamOverlayStatusBarView.StatusIconType int iconType,
            boolean show,
            @Nullable String contentDescription) {
        mMainExecutor.execute(() -> {
            if (mIsAttached) {
                mView.showIcon(iconType, show, contentDescription);
            }
        });
    }
}
