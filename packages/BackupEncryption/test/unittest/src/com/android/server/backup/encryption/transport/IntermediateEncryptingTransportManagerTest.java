/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.transport;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotSame;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportClientManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class IntermediateEncryptingTransportManagerTest {
    @Mock private TransportClient mTransportClient;
    @Mock private TransportClientManager mTransportClientManager;

    private final ComponentName mTransportComponent = new ComponentName("pkg", "class");
    private final Bundle mExtras = new Bundle();
    private Intent mEncryptingTransportIntent;
    private IntermediateEncryptingTransportManager mIntermediateEncryptingTransportManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mExtras.putInt("test", 1);
        mEncryptingTransportIntent =
                TransportClientManager.getEncryptingTransportIntent(mTransportComponent)
                        .putExtras(mExtras);
        mIntermediateEncryptingTransportManager =
                new IntermediateEncryptingTransportManager(mTransportClientManager);
    }

    @Test
    public void testGet_createsClientWithRealTransportComponentAndExtras() {
        when(mTransportClientManager.getTransportClient(any(), any(), any()))
                .thenReturn(mTransportClient);

        IntermediateEncryptingTransport intermediateEncryptingTransport =
                mIntermediateEncryptingTransportManager.get(mEncryptingTransportIntent);

        assertEquals(mTransportClient, intermediateEncryptingTransport.getClient());
        verify(mTransportClientManager, times(1))
                .getTransportClient(eq(mTransportComponent), argThat(mExtras::kindofEquals), any());
        verifyNoMoreInteractions(mTransportClientManager);
    }

    @Test
    public void testGet_callTwice_returnsSameTransport() {
        IntermediateEncryptingTransport transport1 =
                mIntermediateEncryptingTransportManager.get(mEncryptingTransportIntent);
        IntermediateEncryptingTransport transport2 =
                mIntermediateEncryptingTransportManager.get(mEncryptingTransportIntent);

        assertEquals(transport1, transport2);
    }

    @Test
    public void testCleanup_disposesTransportClient() {
        when(mTransportClientManager.getTransportClient(any(), any(), any()))
                .thenReturn(mTransportClient);

        IntermediateEncryptingTransport transport =
                mIntermediateEncryptingTransportManager.get(mEncryptingTransportIntent);
        mIntermediateEncryptingTransportManager.cleanup(mEncryptingTransportIntent);

        verify(mTransportClientManager, times(1)).getTransportClient(any(), any(), any());
        verify(mTransportClientManager, times(1))
                .disposeOfTransportClient(eq(mTransportClient), any());
        verifyNoMoreInteractions(mTransportClientManager);
    }

    @Test
    public void testCleanup_removesCachedTransport() {
        when(mTransportClientManager.getTransportClient(any(), any(), any()))
                .thenReturn(mTransportClient);

        IntermediateEncryptingTransport transport1 =
                mIntermediateEncryptingTransportManager.get(mEncryptingTransportIntent);
        mIntermediateEncryptingTransportManager.cleanup(mEncryptingTransportIntent);
        IntermediateEncryptingTransport transport2 =
                mIntermediateEncryptingTransportManager.get(mEncryptingTransportIntent);

        assertNotSame(transport1, transport2);
    }
}
