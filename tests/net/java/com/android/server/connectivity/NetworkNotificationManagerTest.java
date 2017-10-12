/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.server.connectivity.NetworkNotificationManager.NotificationType.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.filters.SmallTest;
import android.telephony.TelephonyManager;

import com.android.server.connectivity.NetworkNotificationManager.NotificationType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkNotificationManagerTest {

    static final NetworkCapabilities CELL_CAPABILITIES = new NetworkCapabilities();
    static final NetworkCapabilities WIFI_CAPABILITIES = new NetworkCapabilities();
    static {
        CELL_CAPABILITIES.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        CELL_CAPABILITIES.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        WIFI_CAPABILITIES.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        WIFI_CAPABILITIES.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Mock Context mCtx;
    @Mock Resources mResources;
    @Mock PackageManager mPm;
    @Mock TelephonyManager mTelephonyManager;
    @Mock NotificationManager mNotificationManager;
    @Mock NetworkAgentInfo mWifiNai;
    @Mock NetworkAgentInfo mCellNai;
    @Mock NetworkInfo mNetworkInfo;
    ArgumentCaptor<Notification> mCaptor;

    NetworkNotificationManager mManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mCaptor = ArgumentCaptor.forClass(Notification.class);
        mWifiNai.networkCapabilities = WIFI_CAPABILITIES;
        mWifiNai.networkInfo = mNetworkInfo;
        mCellNai.networkCapabilities = CELL_CAPABILITIES;
        mCellNai.networkInfo = mNetworkInfo;
        when(mCtx.getResources()).thenReturn(mResources);
        when(mCtx.getPackageManager()).thenReturn(mPm);
        when(mCtx.getApplicationInfo()).thenReturn(new ApplicationInfo());
        when(mNetworkInfo.getExtraInfo()).thenReturn("extra");
        when(mResources.getColor(anyInt(), any())).thenReturn(0xFF607D8B);

        mManager = new NetworkNotificationManager(mCtx, mTelephonyManager, mNotificationManager);
    }

    @Test
    public void testNotificationsShownAndCleared() {
        final int NETWORK_ID_BASE = 100;
        List<NotificationType> types = Arrays.asList(NotificationType.values());
        List<Integer> ids = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            ids.add(NETWORK_ID_BASE + i);
        }
        Collections.shuffle(ids);
        Collections.shuffle(types);

        for (int i = 0; i < ids.size(); i++) {
            mManager.showNotification(ids.get(i), types.get(i), mWifiNai, mCellNai, null, false);
        }

        List<Integer> idsToClear = new ArrayList<>(ids);
        Collections.shuffle(idsToClear);
        for (int i = 0; i < ids.size(); i++) {
            mManager.clearNotification(idsToClear.get(i));
        }

        for (int i = 0; i < ids.size(); i++) {
            final int id = ids.get(i);
            final int eventId = types.get(i).eventId;
            final String tag = NetworkNotificationManager.tagFor(id);
            verify(mNotificationManager, times(1)).notifyAsUser(eq(tag), eq(eventId), any(), any());
            verify(mNotificationManager, times(1)).cancelAsUser(eq(tag), eq(eventId), any());
        }
    }

    @Test
    public void testNoInternetNotificationsNotShownForCellular() {
        mManager.showNotification(100, NO_INTERNET, mCellNai, mWifiNai, null, false);
        mManager.showNotification(101, LOST_INTERNET, mCellNai, mWifiNai, null, false);

        verify(mNotificationManager, never()).notifyAsUser(any(), anyInt(), any(), any());

        mManager.showNotification(102, NO_INTERNET, mWifiNai, mCellNai, null, false);

        final int eventId = NO_INTERNET.eventId;
        final String tag = NetworkNotificationManager.tagFor(102);
        verify(mNotificationManager, times(1)).notifyAsUser(eq(tag), eq(eventId), any(), any());
    }

    @Test
    public void testNotificationsNotShownIfNoInternetCapability() {
        mWifiNai.networkCapabilities = new NetworkCapabilities();
        mWifiNai.networkCapabilities .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mManager.showNotification(102, NO_INTERNET, mWifiNai, mCellNai, null, false);
        mManager.showNotification(103, LOST_INTERNET, mWifiNai, mCellNai, null, false);
        mManager.showNotification(104, NETWORK_SWITCH, mWifiNai, mCellNai, null, false);

        verify(mNotificationManager, never()).notifyAsUser(any(), anyInt(), any(), any());
    }

    @Test
    public void testDuplicatedNotificationsNoInternetThenSignIn() {
        final int id = 101;
        final String tag = NetworkNotificationManager.tagFor(id);

        // Show first NO_INTERNET
        mManager.showNotification(id, NO_INTERNET, mWifiNai, mCellNai, null, false);
        verify(mNotificationManager, times(1))
                .notifyAsUser(eq(tag), eq(NO_INTERNET.eventId), any(), any());

        // Captive portal detection triggers SIGN_IN a bit later, clearing the previous NO_INTERNET
        mManager.showNotification(id, SIGN_IN, mWifiNai, mCellNai, null, false);
        verify(mNotificationManager, times(1))
                .cancelAsUser(eq(tag), eq(NO_INTERNET.eventId), any());
        verify(mNotificationManager, times(1))
                .notifyAsUser(eq(tag), eq(SIGN_IN.eventId), any(), any());

        // Network disconnects
        mManager.clearNotification(id);
        verify(mNotificationManager, times(1)).cancelAsUser(eq(tag), eq(SIGN_IN.eventId), any());
    }

    @Test
    public void testDuplicatedNotificationsSignInThenNoInternet() {
        final int id = 101;
        final String tag = NetworkNotificationManager.tagFor(id);

        // Show first SIGN_IN
        mManager.showNotification(id, SIGN_IN, mWifiNai, mCellNai, null, false);
        verify(mNotificationManager, times(1))
                .notifyAsUser(eq(tag), eq(SIGN_IN.eventId), any(), any());
        reset(mNotificationManager);

        // NO_INTERNET arrives after, but is ignored.
        mManager.showNotification(id, NO_INTERNET, mWifiNai, mCellNai, null, false);
        verify(mNotificationManager, never()).cancelAsUser(any(), anyInt(), any());
        verify(mNotificationManager, never()).notifyAsUser(any(), anyInt(), any(), any());

        // Network disconnects
        mManager.clearNotification(id);
        verify(mNotificationManager, times(1)).cancelAsUser(eq(tag), eq(SIGN_IN.eventId), any());
    }
}
