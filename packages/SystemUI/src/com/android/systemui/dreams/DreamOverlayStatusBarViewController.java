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
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import android.util.PluralsMessageFormatter;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.time.DateFormatUtil;

import java.util.Locale;
import java.util.Map;

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
    private final NotificationListener mNotificationListener;
    private final ZenModeController mZenModeController;

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

    private final NotificationHandler mNotificationHandler = new NotificationHandler() {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            updateNotificationsStatusIcon();
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
            updateNotificationsStatusIcon();
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                RankingMap rankingMap,
                int reason) {
            updateNotificationsStatusIcon();
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
        }

        @Override
        public void onNotificationsInitialized() {
            updateNotificationsStatusIcon();
        }
    };

    private final ZenModeController.Callback mZenModeCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(int zen) {
            updatePriorityModeStatusIcon();
        }
    };

    @Inject
    public DreamOverlayStatusBarViewController(
            DreamOverlayStatusBarView view,
            @Main Resources resources,
            ConnectivityManager connectivityManager,
            TouchInsetManager.TouchInsetSession touchInsetSession,
            AlarmManager alarmManager,
            NextAlarmController nextAlarmController,
            DateFormatUtil dateFormatUtil,
            IndividualSensorPrivacyController sensorPrivacyController,
            NotificationListener notificationListener,
            ZenModeController zenModeController) {
        super(view);
        mResources = resources;
        mConnectivityManager = connectivityManager;
        mTouchInsetSession = touchInsetSession;
        mAlarmManager = alarmManager;
        mNextAlarmController = nextAlarmController;
        mDateFormatUtil = dateFormatUtil;
        mSensorPrivacyController = sensorPrivacyController;
        mNotificationListener = notificationListener;
        mZenModeController = zenModeController;

        // Handlers can be added to NotificationListener, but apparently they can't be removed. So
        // add the handler here in the constructor rather than in onViewAttached to avoid confusion.
        mNotificationListener.addNotificationHandler(mNotificationHandler);
    }

    @Override
    protected void onViewAttached() {
        updateNotificationsStatusIcon();

        mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);
        updateWifiUnavailableStatusIcon();

        mNextAlarmController.addCallback(mNextAlarmCallback);
        updateAlarmStatusIcon();

        mSensorPrivacyController.addCallback(mSensorCallback);
        updateMicCameraBlockedStatusIcon();

        mZenModeController.addCallback(mZenModeCallback);
        updatePriorityModeStatusIcon();

        mTouchInsetSession.addViewToTracking(mView);
    }

    @Override
    protected void onViewDetached() {
        mZenModeController.removeCallback(mZenModeCallback);
        mSensorPrivacyController.removeCallback(mSensorCallback);
        mNextAlarmController.removeCallback(mNextAlarmCallback);
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mTouchInsetSession.clear();
    }

    private void updateWifiUnavailableStatusIcon() {
        final NetworkCapabilities capabilities =
                mConnectivityManager.getNetworkCapabilities(
                        mConnectivityManager.getActiveNetwork());
        final boolean available = capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        mView.showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, !available);
    }

    private void updateAlarmStatusIcon() {
        final AlarmManager.AlarmClockInfo alarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        final boolean hasAlarm = alarm != null && alarm.getTriggerTime() > 0;
        mView.showIcon(
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
        mView.showIcon(
                DreamOverlayStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED,
                micBlocked && cameraBlocked);
    }

    private void updateNotificationsStatusIcon() {
        if (mView == null) {
            // It is possible for this method to be called before the view is attached, which makes
            // null-checking necessary.
            return;
        }

        final StatusBarNotification[] notifications =
                mNotificationListener.getActiveNotifications();
        final int notificationCount = notifications != null ? notifications.length : 0;
        mView.showIcon(
                DreamOverlayStatusBarView.STATUS_ICON_NOTIFICATIONS,
                notificationCount > 0,
                notificationCount > 0
                        ? buildNotificationsContentDescription(notificationCount)
                        : null);
    }

    private String buildNotificationsContentDescription(int notificationCount) {
        return PluralsMessageFormatter.format(
                mResources,
                Map.of("count", notificationCount),
                R.string.dream_overlay_status_bar_notification_indicator);
    }

    private void updatePriorityModeStatusIcon() {
        mView.showIcon(
                DreamOverlayStatusBarView.STATUS_ICON_PRIORITY_MODE_ON,
                mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF);
    }
}
