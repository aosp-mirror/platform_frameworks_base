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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.statusbar.policy.BatteryController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayStatusBarViewControllerTest extends SysuiTestCase {

    @Mock
    DreamOverlayStatusBarView mView;
    @Mock
    BatteryController mBatteryController;
    @Mock
    BatteryMeterViewController mBatteryMeterViewController;
    @Mock
    ConnectivityManager mConnectivityManager;
    @Mock
    NetworkCapabilities mNetworkCapabilities;
    @Mock
    Network mNetwork;

    DreamOverlayStatusBarViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mController = new DreamOverlayStatusBarViewController(
                mContext, mView, mBatteryController, mBatteryMeterViewController,
                mConnectivityManager);
    }

    @Test
    public void testOnInitInitializesControllers() {
        mController.onInit();
        verify(mBatteryMeterViewController).init();
    }

    @Test
    public void testOnViewAttachedAddsBatteryControllerCallback() {
        mController.onViewAttached();
        verify(mBatteryController)
                .addCallback(any(BatteryController.BatteryStateChangeCallback.class));
    }

    @Test
    public void testOnViewAttachedRegistersNetworkCallback() {
        mController.onViewAttached();
        verify(mConnectivityManager)
                .registerNetworkCallback(any(NetworkRequest.class), any(
                        ConnectivityManager.NetworkCallback.class));
    }

    @Test
    public void testOnViewAttachedShowsWifiStatusWhenWifiUnavailable() {
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(false);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();
        verify(mView).showWifiStatus(true);
    }

    @Test
    public void testOnViewAttachedHidesWifiStatusWhenWifiAvailable() {
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();
        verify(mView).showWifiStatus(false);
    }

    @Test
    public void testOnViewAttachedShowsWifiStatusWhenNetworkCapabilitiesUnavailable() {
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(null);
        mController.onViewAttached();
        verify(mView).showWifiStatus(true);
    }

    @Test
    public void testOnViewDetachedRemovesBatteryControllerCallback() {
        mController.onViewDetached();
        verify(mBatteryController)
                .removeCallback(any(BatteryController.BatteryStateChangeCallback.class));
    }

    @Test
    public void testOnViewDetachedUnregistersNetworkCallback() {
        mController.onViewDetached();
        verify(mConnectivityManager)
                .unregisterNetworkCallback(any(ConnectivityManager.NetworkCallback.class));
    }

    @Test
    public void testBatteryPercentTextShownWhenBatteryLevelChangesWhileCharging() {
        final ArgumentCaptor<BatteryController.BatteryStateChangeCallback> callbackCapture =
                ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback.class);
        mController.onViewAttached();
        verify(mBatteryController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onBatteryLevelChanged(1, true, true);
        verify(mView).showBatteryPercentText(true);
    }

    @Test
    public void testBatteryPercentTextHiddenWhenBatteryLevelChangesWhileNotCharging() {
        final ArgumentCaptor<BatteryController.BatteryStateChangeCallback> callbackCapture =
                ArgumentCaptor.forClass(BatteryController.BatteryStateChangeCallback.class);
        mController.onViewAttached();
        verify(mBatteryController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onBatteryLevelChanged(1, true, false);
        verify(mView).showBatteryPercentText(false);
    }

    @Test
    public void testWifiStatusHiddenWhenWifiBecomesAvailable() {
        // Make sure wifi starts out unavailable when onViewAttached is called.
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(false);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();

        final ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackCapture =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mConnectivityManager).registerNetworkCallback(any(), callbackCapture.capture());
        callbackCapture.getValue().onAvailable(mNetwork);
        verify(mView).showWifiStatus(false);
    }

    @Test
    public void testWifiStatusShownWhenWifiBecomesUnavailable() {
        // Make sure wifi starts out available when onViewAttached is called.
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .thenReturn(true);
        when(mConnectivityManager.getNetworkCapabilities(any())).thenReturn(mNetworkCapabilities);
        mController.onViewAttached();

        final ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackCapture =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mConnectivityManager).registerNetworkCallback(any(), callbackCapture.capture());
        callbackCapture.getValue().onLost(mNetwork);
        verify(mView).showWifiStatus(true);
    }

    @Test
    public void testWifiStatusHiddenWhenCapabilitiesChange() {
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
        verify(mView).showWifiStatus(false);
    }
}
