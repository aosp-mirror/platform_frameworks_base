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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.conditions.CommunalTrustedNetworkCondition;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.utils.os.FakeHandler;

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

    private final Handler mHandler = new FakeHandler(Looper.getMainLooper());
    private CommunalTrustedNetworkCondition mCondition;

    private final String mTrustedWifi1 = "wifi-1";
    private final String mTrustedWifi2 = "wifi-2";

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        final FakeSettings secureSettings = new FakeSettings();
        secureSettings.putStringForUser(Settings.Secure.COMMUNAL_MODE_TRUSTED_NETWORKS,
                mTrustedWifi1 + CommunalTrustedNetworkCondition.SETTINGS_STRING_DELIMINATOR
                        + mTrustedWifi2, UserHandle.USER_SYSTEM);
        mCondition = new CommunalTrustedNetworkCondition(mHandler, mConnectivityManager,
                secureSettings);
    }

    @Test
    public void updateCallback_connectedToTrustedNetwork_reportsTrue() {
        final CommunalTrustedNetworkCondition.Callback callback =
                mock(CommunalTrustedNetworkCondition.Callback.class);
        mCondition.addCallback(callback);

        final ConnectivityManager.NetworkCallback networkCallback = captureNetworkCallback();

        // Connected to trusted Wi-Fi network.
        final Network network = mock(Network.class);
        networkCallback.onAvailable(network);
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities(mTrustedWifi1));

        // Verifies that the callback is triggered.
        verify(callback).onConditionChanged(mCondition, true);
    }

    @Test
    public void updateCallback_switchedToAnotherTrustedNetwork_reportsNothing() {
        final CommunalTrustedNetworkCondition.Callback callback =
                mock(CommunalTrustedNetworkCondition.Callback.class);
        mCondition.addCallback(callback);

        final ConnectivityManager.NetworkCallback networkCallback = captureNetworkCallback();

        // Connected to a trusted Wi-Fi network.
        final Network network = mock(Network.class);
        networkCallback.onAvailable(network);
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities(mTrustedWifi1));
        clearInvocations(callback);

        // Connected to another trusted Wi-Fi network.
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities(mTrustedWifi2));

        // Verifies that the callback is not triggered.
        verify(callback, never()).onConditionChanged(eq(mCondition), anyBoolean());
    }

    @Test
    public void updateCallback_connectedToNonTrustedNetwork_reportsFalse() {
        final CommunalTrustedNetworkCondition.Callback callback =
                mock(CommunalTrustedNetworkCondition.Callback.class);
        mCondition.addCallback(callback);

        final ConnectivityManager.NetworkCallback networkCallback = captureNetworkCallback();

        // Connected to trusted Wi-Fi network.
        final Network network = mock(Network.class);
        networkCallback.onAvailable(network);
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities(mTrustedWifi1));

        // Connected to non-trusted Wi-Fi network.
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities("random-wifi"));

        // Verifies that the callback is triggered.
        verify(callback).onConditionChanged(mCondition, false);
    }

    @Test
    public void updateCallback_disconnectedFromNetwork_reportsFalse() {
        final CommunalTrustedNetworkCondition.Callback callback =
                mock(CommunalTrustedNetworkCondition.Callback.class);
        mCondition.addCallback(callback);

        final ConnectivityManager.NetworkCallback networkCallback = captureNetworkCallback();

        // Connected to Wi-Fi.
        final Network network = mock(Network.class);
        networkCallback.onAvailable(network);
        networkCallback.onCapabilitiesChanged(network, fakeNetworkCapabilities(mTrustedWifi1));
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

    private NetworkCapabilities fakeNetworkCapabilities(String ssid) {
        final NetworkCapabilities networkCapabilities = mock(NetworkCapabilities.class);
        final WifiInfo wifiInfo = mock(WifiInfo.class);
        when(wifiInfo.getSSID()).thenReturn(ssid);
        when(networkCapabilities.getTransportInfo()).thenReturn(wifiInfo);
        return networkCapabilities;
    }
}
