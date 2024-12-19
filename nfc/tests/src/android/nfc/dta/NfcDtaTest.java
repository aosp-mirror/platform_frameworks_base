/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.nfc.dta;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.nfc.INfcDta;
import android.nfc.NfcAdapter;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class NfcDtaTest {
    private final String mServiceName = "serviceName";
    private final int mServiceSap = 1;
    private final int mMiu = 1;
    private final int mRwSize = 1;
    private final int mTestCaseId = 1;
    @Mock
    private NfcAdapter mMockNfcAdapter;
    @Mock
    private INfcDta mMockService;
    @Mock
    private Context mMockContext;

    private NfcDta mNfcDta;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mMockNfcAdapter.getContext()).thenReturn(mMockContext);
        when(mMockNfcAdapter.getNfcDtaInterface()).thenReturn(mMockService);

        mNfcDta = NfcDta.getInstance(mMockNfcAdapter);
    }

    @Test
    public void testEnableData() throws RemoteException {
        assertTrue(mNfcDta.enableDta());
        verify(mMockService).enableDta();
    }

    @Test
    public void testEnableDataWithRemoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mMockService).enableDta();

        assertFalse(mNfcDta.enableDta());
        verify(mMockService).enableDta();
    }

    @Test
    public void testDisableData() throws RemoteException {
        assertTrue(mNfcDta.disableDta());
        verify(mMockService).disableDta();
    }

    @Test
    public void testDisableDataWithRemoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mMockService).disableDta();

        assertFalse(mNfcDta.disableDta());
        verify(mMockService).disableDta();
    }

    @Test
    public void testEnableServer() throws RemoteException {
        when(mMockService.enableServer(mServiceName, mServiceSap, mMiu, mRwSize,
                mTestCaseId)).thenReturn(true);

        mNfcDta.enableServer(mServiceName, mServiceSap, mMiu, mRwSize, mTestCaseId);
        verify(mMockService).enableServer(mServiceName, mServiceSap, mMiu, mRwSize, mTestCaseId);
    }

    @Test
    public void testEnableServerWithRemoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mMockService).enableServer(mServiceName, mServiceSap,
                mMiu,
                mRwSize, mTestCaseId);

        mNfcDta.enableServer(mServiceName, mServiceSap, mMiu, mRwSize, mTestCaseId);
        verify(mMockService).enableServer(mServiceName, mServiceSap, mMiu, mRwSize, mTestCaseId);
    }

    @Test
    public void testDisableServer() throws RemoteException {
        assertTrue(mNfcDta.disableServer());
        verify(mMockService).disableServer();
    }

    @Test
    public void testDisableServerWithRemoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mMockService).disableServer();

        assertFalse(mNfcDta.disableServer());
        verify(mMockService).disableServer();
    }

    @Test
    public void testEnableClient() throws RemoteException {
        when(mMockService.enableClient(mServiceName, mMiu, mRwSize, mTestCaseId)).thenReturn(true);

        mNfcDta.enableClient(mServiceName, mMiu, mRwSize, mTestCaseId);
        verify(mMockService).enableClient(mServiceName, mMiu, mRwSize, mTestCaseId);
    }

    @Test
    public void testEnableClientWithRemoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mMockService).enableClient(mServiceName, mMiu, mRwSize,
                mTestCaseId);

        mNfcDta.enableClient(mServiceName, mMiu, mRwSize, mTestCaseId);
        verify(mMockService).enableClient(mServiceName, mMiu, mRwSize, mTestCaseId);
    }

    @Test
    public void testDisableClient() throws RemoteException {
        assertTrue(mNfcDta.disableClient());
        verify(mMockService).disableClient();
    }

    @Test
    public void testDisableClientWithRemoteException() throws RemoteException {
        doThrow(new RemoteException()).when(mMockService).disableClient();

        assertFalse(mNfcDta.disableClient());
        verify(mMockService).disableClient();
    }

    @Test
    public void testRegisterMessageService() throws RemoteException {
        String msgServiceName = "sampleServiceName";
        when(mMockService.registerMessageService(msgServiceName)).thenReturn(true);

        mNfcDta.registerMessageService(msgServiceName);
        verify(mMockService).registerMessageService(msgServiceName);
    }

    @Test
    public void testRegisterMessageServiceWithRemoteException() throws RemoteException {
        String msgServiceName = "sampleServiceName";
        doThrow(new RemoteException()).when(mMockService).registerMessageService(msgServiceName);

        assertFalse(mNfcDta.registerMessageService(msgServiceName));
    }

    @Test(expected = NullPointerException.class)
    public void testGetInstanceWithNullPointerException() {
        NfcDta.getInstance(null);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetInstanceWithUnsupportedOperationExceptionForNfcAdapterContext() {
        when(mMockNfcAdapter.getContext()).thenReturn(null);

        NfcDta.getInstance(mMockNfcAdapter);
    }
}
