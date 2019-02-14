/*
 * Copyright (C) 2016, The Android Open Source Project
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

package com.android.server.connectivity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.os.INetworkManagementService;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.format.DateUtils;

import com.android.internal.R;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LingerMonitorTest {
    static final String CELLULAR = "CELLULAR";
    static final String WIFI     = "WIFI";

    static final long LOW_RATE_LIMIT = DateUtils.MINUTE_IN_MILLIS;
    static final long HIGH_RATE_LIMIT = 0;

    static final int LOW_DAILY_LIMIT = 2;
    static final int HIGH_DAILY_LIMIT = 1000;

    LingerMonitor mMonitor;

    @Mock ConnectivityService mConnService;
    @Mock INetd mNetd;
    @Mock INetworkManagementService mNMS;
    @Mock Context mCtx;
    @Mock NetworkMisc mMisc;
    @Mock NetworkNotificationManager mNotifier;
    @Mock Resources mResources;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mCtx.getResources()).thenReturn(mResources);
        when(mCtx.getPackageName()).thenReturn("com.android.server.connectivity");

        mMonitor = new TestableLingerMonitor(mCtx, mNotifier, HIGH_DAILY_LIMIT, HIGH_RATE_LIMIT);
    }

    @Test
    public void testTransitions() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        NetworkAgentInfo nai1 = wifiNai(100);
        NetworkAgentInfo nai2 = cellNai(101);

        assertTrue(mMonitor.isNotificationEnabled(nai1, nai2));
        assertFalse(mMonitor.isNotificationEnabled(nai2, nai1));
    }

    @Test
    public void testNotificationOnLinger() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNotification(from, to);
    }

    @Test
    public void testToastOnLinger() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_TOAST);
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyToast(from, to);
    }

    @Test
    public void testNotificationClearedAfterDisconnect() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNotification(from, to);

        mMonitor.noteDisconnect(to);
        verify(mNotifier, times(1)).clearNotification(100);
    }

    @Test
    public void testNotificationClearedAfterSwitchingBack() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNotification(from, to);

        mMonitor.noteLingerDefaultNetwork(to, from);
        verify(mNotifier, times(1)).clearNotification(100);
    }

    @Test
    public void testUniqueToast() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_TOAST);
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyToast(from, to);

        mMonitor.noteLingerDefaultNetwork(to, from);
        verify(mNotifier, times(1)).clearNotification(100);

        reset(mNotifier);
        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNoNotifications();
    }

    @Test
    public void testMultipleNotifications() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo wifi1 = wifiNai(100);
        NetworkAgentInfo wifi2 = wifiNai(101);
        NetworkAgentInfo cell = cellNai(102);

        mMonitor.noteLingerDefaultNetwork(wifi1, cell);
        verifyNotification(wifi1, cell);

        mMonitor.noteLingerDefaultNetwork(cell, wifi2);
        verify(mNotifier, times(1)).clearNotification(100);

        reset(mNotifier);
        mMonitor.noteLingerDefaultNetwork(wifi2, cell);
        verifyNotification(wifi2, cell);
    }

    @Test
    public void testRateLimiting() throws InterruptedException {
        mMonitor = new TestableLingerMonitor(mCtx, mNotifier, HIGH_DAILY_LIMIT, LOW_RATE_LIMIT);

        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo wifi1 = wifiNai(100);
        NetworkAgentInfo wifi2 = wifiNai(101);
        NetworkAgentInfo wifi3 = wifiNai(102);
        NetworkAgentInfo cell = cellNai(103);

        mMonitor.noteLingerDefaultNetwork(wifi1, cell);
        verifyNotification(wifi1, cell);
        reset(mNotifier);

        Thread.sleep(50);
        mMonitor.noteLingerDefaultNetwork(cell, wifi2);
        mMonitor.noteLingerDefaultNetwork(wifi2, cell);
        verifyNoNotifications();

        Thread.sleep(50);
        mMonitor.noteLingerDefaultNetwork(cell, wifi3);
        mMonitor.noteLingerDefaultNetwork(wifi3, cell);
        verifyNoNotifications();
    }

    @Test
    public void testDailyLimiting() throws InterruptedException {
        mMonitor = new TestableLingerMonitor(mCtx, mNotifier, LOW_DAILY_LIMIT, HIGH_RATE_LIMIT);

        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo wifi1 = wifiNai(100);
        NetworkAgentInfo wifi2 = wifiNai(101);
        NetworkAgentInfo wifi3 = wifiNai(102);
        NetworkAgentInfo cell = cellNai(103);

        mMonitor.noteLingerDefaultNetwork(wifi1, cell);
        verifyNotification(wifi1, cell);
        reset(mNotifier);

        Thread.sleep(50);
        mMonitor.noteLingerDefaultNetwork(cell, wifi2);
        mMonitor.noteLingerDefaultNetwork(wifi2, cell);
        verifyNotification(wifi2, cell);
        reset(mNotifier);

        Thread.sleep(50);
        mMonitor.noteLingerDefaultNetwork(cell, wifi3);
        mMonitor.noteLingerDefaultNetwork(wifi3, cell);
        verifyNoNotifications();
    }

    @Test
    public void testUniqueNotification() {
        setNotificationSwitch(transition(WIFI, CELLULAR));
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NOTIFICATION);
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNotification(from, to);

        mMonitor.noteLingerDefaultNetwork(to, from);
        verify(mNotifier, times(1)).clearNotification(100);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNotification(from, to);
    }

    @Test
    public void testIgnoreNeverValidatedNetworks() {
        setNotificationType(LingerMonitor.NOTIFY_TYPE_TOAST);
        setNotificationSwitch(transition(WIFI, CELLULAR));
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);
        from.everValidated = false;

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNoNotifications();
    }

    @Test
    public void testIgnoreCurrentlyValidatedNetworks() {
        setNotificationType(LingerMonitor.NOTIFY_TYPE_TOAST);
        setNotificationSwitch(transition(WIFI, CELLULAR));
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);
        from.lastValidated = true;

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNoNotifications();
    }

    @Test
    public void testNoNotificationType() {
        setNotificationType(LingerMonitor.NOTIFY_TYPE_TOAST);
        setNotificationSwitch();
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNoNotifications();
    }

    @Test
    public void testNoTransitionToNotify() {
        setNotificationType(LingerMonitor.NOTIFY_TYPE_NONE);
        setNotificationSwitch(transition(WIFI, CELLULAR));
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNoNotifications();
    }

    @Test
    public void testDifferentTransitionToNotify() {
        setNotificationType(LingerMonitor.NOTIFY_TYPE_TOAST);
        setNotificationSwitch(transition(CELLULAR, WIFI));
        NetworkAgentInfo from = wifiNai(100);
        NetworkAgentInfo to = cellNai(101);

        mMonitor.noteLingerDefaultNetwork(from, to);
        verifyNoNotifications();
    }

    void setNotificationSwitch(String... transitions) {
        when(mResources.getStringArray(R.array.config_networkNotifySwitches))
                .thenReturn(transitions);
    }

    String transition(String from, String to) {
        return from + "-" + to;
    }

    void setNotificationType(int type) {
        when(mResources.getInteger(R.integer.config_networkNotifySwitchType)).thenReturn(type);
    }

    void verifyNoToast() {
        verify(mNotifier, never()).showToast(any(), any());
    }

    void verifyNoNotification() {
        verify(mNotifier, never())
                .showNotification(anyInt(), any(), any(), any(), any(), anyBoolean());
    }

    void verifyNoNotifications() {
        verifyNoToast();
        verifyNoNotification();
    }

    void verifyToast(NetworkAgentInfo from, NetworkAgentInfo to) {
        verifyNoNotification();
        verify(mNotifier, times(1)).showToast(from, to);
    }

    void verifyNotification(NetworkAgentInfo from, NetworkAgentInfo to) {
        verifyNoToast();
        verify(mNotifier, times(1)).showNotification(eq(from.network.netId),
                eq(NotificationType.NETWORK_SWITCH), eq(from), eq(to), any(), eq(true));
    }

    NetworkAgentInfo nai(int netId, int transport, int networkType, String networkTypeName) {
        NetworkInfo info = new NetworkInfo(networkType, 0, networkTypeName, "");
        NetworkCapabilities caps = new NetworkCapabilities();
        caps.addCapability(0);
        caps.addTransportType(transport);
        NetworkAgentInfo nai = new NetworkAgentInfo(null, null, new Network(netId), info, null,
                caps, 50, mCtx, null, mMisc, mConnService, mNetd, mNMS);
        nai.everValidated = true;
        return nai;
    }

    NetworkAgentInfo wifiNai(int netId) {
        return nai(netId, NetworkCapabilities.TRANSPORT_WIFI,
                ConnectivityManager.TYPE_WIFI, WIFI);
    }

    NetworkAgentInfo cellNai(int netId) {
        return nai(netId, NetworkCapabilities.TRANSPORT_CELLULAR,
                ConnectivityManager.TYPE_MOBILE, CELLULAR);
    }

    public static class TestableLingerMonitor extends LingerMonitor {
        public TestableLingerMonitor(Context c, NetworkNotificationManager n, int l, long r) {
            super(c, n, l, r);
        }
        @Override protected PendingIntent createNotificationIntent() {
            return null;
        }
    }
}
