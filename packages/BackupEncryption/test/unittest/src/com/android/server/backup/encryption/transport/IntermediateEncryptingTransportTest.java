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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.transport.TransportClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class IntermediateEncryptingTransportTest {
    @Mock private IBackupTransport mRealTransport;
    @Mock private TransportClient mTransportClient;

    private IntermediateEncryptingTransport mIntermediateEncryptingTransport;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mIntermediateEncryptingTransport = new IntermediateEncryptingTransport(mTransportClient);
    }

    @Test
    public void testGetDelegate_callsConnect() throws Exception {
        when(mTransportClient.connect(anyString())).thenReturn(mRealTransport);

        IBackupTransport ret = mIntermediateEncryptingTransport.getDelegate();

        assertEquals(mRealTransport, ret);
        verify(mTransportClient, times(1)).connect(anyString());
        verifyNoMoreInteractions(mTransportClient);
    }

    @Test
    public void testGetDelegate_callTwice_callsConnectOnce() throws Exception {
        when(mTransportClient.connect(anyString())).thenReturn(mRealTransport);

        IBackupTransport ret1 = mIntermediateEncryptingTransport.getDelegate();
        IBackupTransport ret2 = mIntermediateEncryptingTransport.getDelegate();

        assertEquals(mRealTransport, ret1);
        assertEquals(mRealTransport, ret2);
        verify(mTransportClient, times(1)).connect(anyString());
        verifyNoMoreInteractions(mTransportClient);
    }
}
