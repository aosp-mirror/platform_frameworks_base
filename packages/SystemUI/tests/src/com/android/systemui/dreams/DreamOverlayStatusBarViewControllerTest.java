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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.time.DateFormatUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayStatusBarViewControllerTest extends SysuiTestCase {
    private static final String NOTIFICATION_INDICATOR_FORMATTER_STRING =
            "{count, plural, =1 {# notification} other {# notifications}}";

    @Mock
    DreamOverlayStatusBarView mView;
    @Mock
    ConnectivityManager mConnectivityManager;
    @Mock
    NetworkCapabilities mNetworkCapabilities;
    @Mock
    Network mNetwork;
    @Mock
    TouchInsetManager.TouchInsetSession mTouchSession;
    @Mock
    Resources mResources;
    @Mock
    AlarmManager mAlarmManager;
    @Mock
    AlarmManager.AlarmClockInfo mAlarmClockInfo;
    @Mock
    NextAlarmController mNextAlarmController;
    @Mock
    DateFormatUtil mDateFormatUtil;
    @Mock
    IndividualSensorPrivacyController mSensorPrivacyController;
    @Mock
    StatusBarNotification mStatusBarNotification;
    @Mock
    NotificationListenerService.RankingMap mRankingMap;
    @Mock
    NotificationListener mNotificationListener;
    @Mock
    ZenModeController mZenModeController;

    DreamOverlayStatusBarViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mResources.getString(R.string.dream_overlay_status_bar_notification_indicator))
                .thenReturn(NOTIFICATION_INDICATOR_FORMATTER_STRING);

        mController = new DreamOverlayStatusBarViewController(
                mView,
                mResources,
                mConnectivityManager,
                mTouchSession,
                mAlarmManager,
                mNextAlarmController,
                mDateFormatUtil,
                mSensorPrivacyController,
                mNotificationListener,
                mZenModeController);
    }

    @Test
    public void testOnViewAttachedAddsCallbacks() {
        mController.onViewAttached();
        verify(mNextAlarmController).addCallback(any());
        verify(mSensorPrivacyController).addCallback(any());
        verify(mZenModeController).addCallback(any());
    }

    @Test
    public void testOnViewAttachedRegistersNetworkCallback() {
        mController.onViewAttached();
        verify(mConnectivityManager)
                .registerNetworkCallback(any(NetworkRequest.class), any(
                        ConnectivityManager.NetworkCallback.class));
    }

    @Test
    public void testOnViewAttachedShowsWifiIconWhenWifiUnavailable() {
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(false);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, true);
    }

    @Test
    public void testOnViewAttachedHidesWifiIconWhenWifiAvailable() {
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, false);
    }

    @Test
    public void testOnViewAttachedShowsWifiIconWhenNetworkCapabilitiesUnavailable() {
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(null);
        mController.onViewAttached();
    }

    @Test
    public void testOnViewAttachedShowsAlarmIconWhenAlarmExists() {
        when(mAlarmClockInfo.getTriggerTime()).thenReturn(1L);
        when(mAlarmManager.getNextAlarmClock(anyInt())).thenReturn(mAlarmClockInfo);
        mController.onViewAttached();
        verify(mView).showIcon(
                eq(DreamOverlayStatusBarView.STATUS_ICON_ALARM_SET), eq(true), any());
    }

    @Test
    public void testOnViewAttachedHidesAlarmIconWhenNoAlarmExists() {
        when(mAlarmManager.getNextAlarmClock(anyInt())).thenReturn(null);
        mController.onViewAttached();
        verify(mView).showIcon(
                eq(DreamOverlayStatusBarView.STATUS_ICON_ALARM_SET), eq(false), isNull());
    }

    @Test
    public void testOnViewAttachedShowsMicCameraIconWhenDisabled() {
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(true);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(true);
        mController.onViewAttached();
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED, true);
    }

    @Test
    public void testOnViewAttachedHidesMicCameraIconWhenEnabled() {
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(false);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(false);
        mController.onViewAttached();
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED, false);
    }

    @Test
    public void testOnViewAttachedShowsNotificationsIconWhenNotificationsExist() {
        StatusBarNotification[] notifications = { mStatusBarNotification };
        when(mNotificationListener.getActiveNotifications()).thenReturn(notifications);
        mController.onViewAttached();
        verify(mView).showIcon(
                eq(DreamOverlayStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(true), any());
    }

    @Test
    public void testOnViewAttachedHidesNotificationsIconWhenNoNotificationsExist() {
        when(mNotificationListener.getActiveNotifications()).thenReturn(null);
        mController.onViewAttached();
        verify(mView).showIcon(
                eq(DreamOverlayStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(false), isNull());
    }

    @Test
    public void testOnViewAttachedShowsPriorityModeIconWhenEnabled() {
        when(mZenModeController.getZen()).thenReturn(
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        mController.onViewAttached();
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, true);
    }

    @Test
    public void testOnViewAttachedHidesPriorityModeIconWhenDisabled() {
        when(mZenModeController.getZen()).thenReturn(
                Settings.Global.ZEN_MODE_OFF);
        mController.onViewAttached();
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, false);
    }

    @Test
    public void testOnViewDetachedUnregistersNetworkCallback() {
        mController.onViewDetached();
        verify(mConnectivityManager)
                .unregisterNetworkCallback(any(ConnectivityManager.NetworkCallback.class));
    }

    @Test
    public void testOnViewDetachedRemovesCallbacks() {
        mController.onViewDetached();
        verify(mNextAlarmController).removeCallback(any());
        verify(mSensorPrivacyController).removeCallback(any());
        verify(mZenModeController).removeCallback(any());
    }

    @Test
    public void testWifiIconHiddenWhenWifiBecomesAvailable() {
        // Make sure wifi starts out unavailable when onViewAttached is called, and then returns
        // true on the second query.
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(false).thenReturn(true);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();

        final ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackCapture =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mConnectivityManager).registerNetworkCallback(any(), callbackCapture.capture());
        callbackCapture.getValue().onAvailable(mNetwork);
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, false);
    }

    @Test
    public void testWifiIconShownWhenWifiBecomesUnavailable() {
        // Make sure wifi starts out available when onViewAttached is called, then returns false
        // on the second query.
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true).thenReturn(false);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();

        final ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackCapture =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mConnectivityManager).registerNetworkCallback(any(), callbackCapture.capture());
        callbackCapture.getValue().onLost(mNetwork);
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, true);
    }

    @Test
    public void testWifiIconHiddenWhenCapabilitiesChange() {
        // Make sure wifi starts out unavailable when onViewAttached is called.
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(false);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();

        final ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackCapture =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mConnectivityManager).registerNetworkCallback(any(), callbackCapture.capture());
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true);
        callbackCapture.getValue().onCapabilitiesChanged(mNetwork, mNetworkCapabilities);
        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, false);
    }

    @Test
    public void testNotificationsIconShownWhenNotificationAdded() {
        mController.onViewAttached();

        StatusBarNotification[] notifications = { mStatusBarNotification };
        when(mNotificationListener.getActiveNotifications()).thenReturn(notifications);

        final ArgumentCaptor<NotificationListener.NotificationHandler> callbackCapture =
                ArgumentCaptor.forClass(NotificationListener.NotificationHandler.class);
        verify(mNotificationListener).addNotificationHandler(callbackCapture.capture());
        callbackCapture.getValue().onNotificationPosted(mStatusBarNotification, mRankingMap);

        verify(mView).showIcon(
                eq(DreamOverlayStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(true), any());
    }

    @Test
    public void testNotificationsIconHiddenWhenLastNotificationRemoved() {
        StatusBarNotification[] notifications = { mStatusBarNotification };
        when(mNotificationListener.getActiveNotifications()).thenReturn(notifications)
                .thenReturn(null);
        mController.onViewAttached();

        final ArgumentCaptor<NotificationListener.NotificationHandler> callbackCapture =
                ArgumentCaptor.forClass(NotificationListener.NotificationHandler.class);
        verify(mNotificationListener).addNotificationHandler(callbackCapture.capture());
        callbackCapture.getValue().onNotificationPosted(mStatusBarNotification, mRankingMap);

        verify(mView).showIcon(
                eq(DreamOverlayStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(false), any());
    }

    @Test
    public void testMicCameraIconShownWhenSensorsBlocked() {
        mController.onViewAttached();

        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(true);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(true);

        final ArgumentCaptor<IndividualSensorPrivacyController.Callback> callbackCapture =
                ArgumentCaptor.forClass(IndividualSensorPrivacyController.Callback.class);
        verify(mSensorPrivacyController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onSensorBlockedChanged(
                SensorPrivacyManager.Sensors.MICROPHONE, true);

        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED, true);
    }

    @Test
    public void testMicCameraIconHiddenWhenSensorsNotBlocked() {
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(true).thenReturn(false);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(true).thenReturn(false);
        mController.onViewAttached();

        final ArgumentCaptor<IndividualSensorPrivacyController.Callback> callbackCapture =
                ArgumentCaptor.forClass(IndividualSensorPrivacyController.Callback.class);
        verify(mSensorPrivacyController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onSensorBlockedChanged(
                SensorPrivacyManager.Sensors.MICROPHONE, false);

        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED, false);
    }

    @Test
    public void testPriorityModeIconShownWhenZenModeEnabled() {
        mController.onViewAttached();

        when(mZenModeController.getZen()).thenReturn(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);

        final ArgumentCaptor<ZenModeController.Callback> callbackCapture =
                ArgumentCaptor.forClass(ZenModeController.Callback.class);
        verify(mZenModeController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onZenChanged(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);

        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, true);
    }

    @Test
    public void testPriorityModeIconHiddenWhenZenModeDisabled() {
        when(mZenModeController.getZen()).thenReturn(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
                .thenReturn(Settings.Global.ZEN_MODE_OFF);
        mController.onViewAttached();


        final ArgumentCaptor<ZenModeController.Callback> callbackCapture =
                ArgumentCaptor.forClass(ZenModeController.Callback.class);
        verify(mZenModeController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onZenChanged(Settings.Global.ZEN_MODE_OFF);

        verify(mView).showIcon(DreamOverlayStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, false);
    }
}
