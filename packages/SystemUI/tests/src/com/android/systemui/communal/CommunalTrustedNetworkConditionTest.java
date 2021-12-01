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

package com.android.systemui.communal;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.conditions.CommunalTrustedNetworkCondition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalTrustedNetworkConditionTest extends SysuiTestCase {
    @Mock private ConnectivityManager mConnectivityManager;

    @Captor private ArgumentCaptor<ConnectivityManager.NetworkCallback> mNetworkCallbackCaptor;

    private CommunalTrustedNetworkCondition mCondition;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mCondition = new CommunalTrustedNetworkCondition(mConnectivityManager);
    }

    @Test
    public void updateCallback_connectedToTrustedNetwork_reportsTrue() {
        final CommunalTrustedNetworkCondition.Callback callback =
                mock(CommunalTrustedNetworkCondition.Callback.class);
        mCondition.addCallback(callback);

        final ConnectivityManager.NetworkCallback networkCallback = captureNetworkCallback();

        // Connected to Wi-Fi.
        final Network network = mock(Network.class);
        networkCallback.onAvailable(network);
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities());

        // Verifies that the callback is triggered.
        verify(callback).onConditionChanged(mCondition, true);
    }

    @Test
    public void updateCallback_disconnectedFromTrustedNetwork_reportsFalse() {
        final CommunalTrustedNetworkCondition.Callback callback =
                mock(CommunalTrustedNetworkCondition.Callback.class);
        mCondition.addCallback(callback);

        final ConnectivityManager.NetworkCallback networkCallback = captureNetworkCallback();

        // Connected to Wi-Fi.
        final Network network = mock(Network.class);
        networkCallback.onAvailable(network);
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities());
        clearInvocations(callback);

        // Disconnected from Wi-Fi.
        networkCallback.onLost(network);

        // Verifies that the callback is triggered.
        verify(callback).onConditionChanged(mCondition, false);
    }

    // Captures and returns the network callback, assuming it is registered with the connectivity
    // manager.
    private ConnectivityManager.NetworkCallback captureNetworkCallback() {
        verify(mConnectivityManager).registerNetworkCallback(any(NetworkRequest.class),
                mNetworkCallbackCaptor.capture());
        return mNetworkCallbackCaptor.getValue();
    }

    private NetworkCapabilities fakeNetworkCapabilities() {
        final NetworkCapabilities networkCapabilities = mock(NetworkCapabilities.class);
        final WifiInfo wifiInfo = mock(WifiInfo.class);
        when(networkCapabilities.getTransportInfo()).thenReturn(wifiInfo);
        return networkCapabilities;
    }
}
