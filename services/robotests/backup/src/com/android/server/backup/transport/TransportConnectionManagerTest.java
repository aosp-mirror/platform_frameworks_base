/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.transport;

import static com.android.server.backup.TransportManager.SERVICE_ACTION_TRANSPORT_HOST;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TransportConnectionManagerTest {
    private static final String PACKAGE_NAME = "random.package.name";
    private static final String CLASS_NAME = "random.package.name.transport.Transport";

    @Mock private Context mContext;
    @Mock private TransportConnectionListener mTransportConnectionListener;
    private @UserIdInt int mUserId;
    private TransportConnectionManager mTransportConnectionManager;
    private ComponentName mTransportComponent;
    private Intent mBindIntent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mUserId = UserHandle.USER_SYSTEM;
        mTransportConnectionManager =
                new TransportConnectionManager(mUserId, mContext, new TransportStats());
        mTransportComponent = new ComponentName(PACKAGE_NAME, CLASS_NAME);
        mBindIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST).setComponent(mTransportComponent);

        when(mContext.bindServiceAsUser(
                        any(Intent.class),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class)))
                .thenReturn(true);
    }

    @Test
    public void testGetTransportClient() {
        TransportConnection transportConnection =
                mTransportConnectionManager.getTransportClient(mTransportComponent, "caller");

        // Connect to be able to extract the intent
        transportConnection.connectAsync(mTransportConnectionListener, "caller");
        verify(mContext)
                .bindServiceAsUser(
                        argThat(matchesIntentAndExtras(mBindIntent)),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class));
    }

    @Test
    public void testGetTransportClient_withExtras_createsTransportClientWithCorrectIntent() {
        Bundle extras = new Bundle();
        extras.putBoolean("random_extra", true);

        TransportConnection transportConnection =
                mTransportConnectionManager.getTransportClient(mTransportComponent, extras,
                        "caller");

        transportConnection.connectAsync(mTransportConnectionListener, "caller");
        mBindIntent.putExtras(extras);
        verify(mContext)
                .bindServiceAsUser(
                        argThat(matchesIntentAndExtras(mBindIntent)),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class));
    }

    @Test
    public void testDisposeOfTransportClient() {
        TransportConnection transportConnection =
                spy(mTransportConnectionManager.getTransportClient(mTransportComponent, "caller"));

        mTransportConnectionManager.disposeOfTransportClient(transportConnection, "caller");

        verify(transportConnection).unbind(any());
        verify(transportConnection).markAsDisposed();
    }

    private ArgumentMatcher<Intent> matchesIntentAndExtras(Intent expectedIntent) {
        return (Intent actualIntent) -> {
            if (!expectedIntent.filterEquals(actualIntent)) {
                return false;
            }

            Bundle expectedExtras = expectedIntent.getExtras();
            Bundle actualExtras = actualIntent.getExtras();

            if (expectedExtras == null && actualExtras == null) {
                return true;
            }

            if (expectedExtras == null || actualExtras == null) {
                return false;
            }

            if (expectedExtras.size() != actualExtras.size()) {
                return false;
            }

            for (String key : expectedExtras.keySet()) {
                if (!expectedExtras.get(key).equals(actualExtras.get(key))) {
                    return false;
                }
            }

            return true;
        };
    }
}
