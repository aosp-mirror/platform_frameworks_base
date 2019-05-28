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
 * limitations under the License.
 */

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.IOnStatusListener;
import android.net.ipmemorystore.NetworkAttributes;
import android.net.ipmemorystore.NetworkAttributesParcelable;
import android.net.ipmemorystore.Status;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.UnknownHostException;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpMemoryStoreTest {
    private static final String TAG = IpMemoryStoreTest.class.getSimpleName();
    private static final String TEST_CLIENT_ID = "testClientId";
    private static final String TEST_DATA_NAME = "testData";
    private static final String TEST_OTHER_DATA_NAME = TEST_DATA_NAME + "Other";
    private static final byte[] TEST_BLOB_DATA = new byte[] { -3, 6, 8, -9, 12,
            -128, 0, 89, 112, 91, -34 };
    private static final NetworkAttributes TEST_NETWORK_ATTRIBUTES = buildTestNetworkAttributes(
            "hint", 219);

    @Mock
    Context mMockContext;
    @Mock
    NetworkStackClient mNetworkStackClient;
    @Mock
    IIpMemoryStore mMockService;
    @Mock
    IOnStatusListener mIOnStatusListener;
    IpMemoryStore mStore;

    @Captor
    ArgumentCaptor<IIpMemoryStoreCallbacks> mCbCaptor;
    @Captor
    ArgumentCaptor<NetworkAttributesParcelable> mNapCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private void startIpMemoryStore(boolean supplyService) {
        if (supplyService) {
            doAnswer(invocation -> {
                ((IIpMemoryStoreCallbacks) invocation.getArgument(0))
                        .onIpMemoryStoreFetched(mMockService);
                return null;
            }).when(mNetworkStackClient).fetchIpMemoryStore(any());
        } else {
            doNothing().when(mNetworkStackClient).fetchIpMemoryStore(mCbCaptor.capture());
        }
        mStore = new IpMemoryStore(mMockContext) {
            @Override
            protected NetworkStackClient getNetworkStackClient() {
                return mNetworkStackClient;
            }
        };
    }

    private static NetworkAttributes buildTestNetworkAttributes(String hint, int mtu) {
        return new NetworkAttributes.Builder()
                .setGroupHint(hint)
                .setMtu(mtu)
                .build();
    }

    @Test
    public void testNetworkAttributes() throws Exception {
        startIpMemoryStore(true /* supplyService */);
        final String l2Key = "fakeKey";

        mStore.storeNetworkAttributes(l2Key, TEST_NETWORK_ATTRIBUTES,
                status -> assertTrue("Store not successful : " + status.resultCode,
                        status.isSuccess()));
        verify(mMockService, times(1)).storeNetworkAttributes(eq(l2Key),
                mNapCaptor.capture(), any());
        assertEquals(TEST_NETWORK_ATTRIBUTES, new NetworkAttributes(mNapCaptor.getValue()));

        mStore.retrieveNetworkAttributes(l2Key,
                (status, key, attr) -> {
                    assertTrue("Retrieve network attributes not successful : "
                            + status.resultCode, status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(TEST_NETWORK_ATTRIBUTES, attr);
                });

        verify(mMockService, times(1)).retrieveNetworkAttributes(eq(l2Key), any());
    }

    @Test
    public void testPrivateData() throws RemoteException {
        startIpMemoryStore(true /* supplyService */);
        final Blob b = new Blob();
        b.data = TEST_BLOB_DATA;
        final String l2Key = "fakeKey";

        mStore.storeBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, b,
                status -> {
                    assertTrue("Store not successful : " + status.resultCode, status.isSuccess());
                });
        verify(mMockService, times(1)).storeBlob(eq(l2Key), eq(TEST_CLIENT_ID), eq(TEST_DATA_NAME),
                eq(b), any());

        mStore.retrieveBlob(l2Key, TEST_CLIENT_ID, TEST_OTHER_DATA_NAME,
                (status, key, name, data) -> {
                    assertTrue("Retrieve blob status not successful : " + status.resultCode,
                            status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(name, TEST_DATA_NAME);
                    assertTrue(Arrays.equals(b.data, data.data));
                });
        verify(mMockService, times(1)).retrieveBlob(eq(l2Key), eq(TEST_CLIENT_ID),
                eq(TEST_OTHER_DATA_NAME), any());
    }

    @Test
    public void testFindL2Key()
            throws UnknownHostException, RemoteException, Exception {
        startIpMemoryStore(true /* supplyService */);
        final String l2Key = "fakeKey";

        mStore.findL2Key(TEST_NETWORK_ATTRIBUTES,
                (status, key) -> {
                    assertTrue("Retrieve network sameness not successful : " + status.resultCode,
                            status.isSuccess());
                    assertEquals(l2Key, key);
                });
        verify(mMockService, times(1)).findL2Key(mNapCaptor.capture(), any());
        assertEquals(TEST_NETWORK_ATTRIBUTES, new NetworkAttributes(mNapCaptor.getValue()));
    }

    @Test
    public void testIsSameNetwork() throws UnknownHostException, RemoteException {
        startIpMemoryStore(true /* supplyService */);
        final String l2Key1 = "fakeKey1";
        final String l2Key2 = "fakeKey2";

        mStore.isSameNetwork(l2Key1, l2Key2,
                (status, answer) -> {
                    assertFalse("Retrieve network sameness suspiciously successful : "
                            + status.resultCode, status.isSuccess());
                    assertEquals(Status.ERROR_ILLEGAL_ARGUMENT, status.resultCode);
                    assertNull(answer);
                });
        verify(mMockService, times(1)).isSameNetwork(eq(l2Key1), eq(l2Key2), any());
    }

    @Test
    public void testEnqueuedIpMsRequests() throws Exception {
        startIpMemoryStore(false /* supplyService */);

        final Blob b = new Blob();
        b.data = TEST_BLOB_DATA;
        final String l2Key = "fakeKey";

        // enqueue multiple ipms requests
        mStore.storeNetworkAttributes(l2Key, TEST_NETWORK_ATTRIBUTES,
                status -> assertTrue("Store not successful : " + status.resultCode,
                        status.isSuccess()));
        mStore.retrieveNetworkAttributes(l2Key,
                (status, key, attr) -> {
                    assertTrue("Retrieve network attributes not successful : "
                            + status.resultCode, status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(TEST_NETWORK_ATTRIBUTES, attr);
                });
        mStore.storeBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, b,
                status -> assertTrue("Store not successful : " + status.resultCode,
                        status.isSuccess()));
        mStore.retrieveBlob(l2Key, TEST_CLIENT_ID, TEST_OTHER_DATA_NAME,
                (status, key, name, data) -> {
                    assertTrue("Retrieve blob status not successful : " + status.resultCode,
                            status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(name, TEST_DATA_NAME);
                    assertTrue(Arrays.equals(b.data, data.data));
                });

        // get ipms service ready
        mCbCaptor.getValue().onIpMemoryStoreFetched(mMockService);

        InOrder inOrder = inOrder(mMockService);

        inOrder.verify(mMockService).storeNetworkAttributes(eq(l2Key), mNapCaptor.capture(), any());
        inOrder.verify(mMockService).retrieveNetworkAttributes(eq(l2Key), any());
        inOrder.verify(mMockService).storeBlob(eq(l2Key), eq(TEST_CLIENT_ID), eq(TEST_DATA_NAME),
                eq(b), any());
        inOrder.verify(mMockService).retrieveBlob(eq(l2Key), eq(TEST_CLIENT_ID),
                eq(TEST_OTHER_DATA_NAME), any());
        assertEquals(TEST_NETWORK_ATTRIBUTES, new NetworkAttributes(mNapCaptor.getValue()));
    }

    @Test
    public void testEnqueuedIpMsRequestsWithException() throws Exception {
        startIpMemoryStore(true /* supplyService */);
        doThrow(RemoteException.class).when(mMockService).retrieveNetworkAttributes(any(), any());

        final Blob b = new Blob();
        b.data = TEST_BLOB_DATA;
        final String l2Key = "fakeKey";

        // enqueue multiple ipms requests
        mStore.storeNetworkAttributes(l2Key, TEST_NETWORK_ATTRIBUTES,
                status -> assertTrue("Store not successful : " + status.resultCode,
                        status.isSuccess()));
        mStore.retrieveNetworkAttributes(l2Key,
                (status, key, attr) -> {
                    assertTrue("Retrieve network attributes not successful : "
                            + status.resultCode, status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(TEST_NETWORK_ATTRIBUTES, attr);
                });
        mStore.storeBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, b,
                status -> assertTrue("Store not successful : " + status.resultCode,
                        status.isSuccess()));
        mStore.retrieveBlob(l2Key, TEST_CLIENT_ID, TEST_OTHER_DATA_NAME,
                (status, key, name, data) -> {
                    assertTrue("Retrieve blob status not successful : " + status.resultCode,
                            status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(name, TEST_DATA_NAME);
                    assertTrue(Arrays.equals(b.data, data.data));
                });

        // verify the rest of the queue is still processed in order even if the remote exception
        // occurs when calling one or more requests
        InOrder inOrder = inOrder(mMockService);

        inOrder.verify(mMockService).storeNetworkAttributes(eq(l2Key), mNapCaptor.capture(), any());
        inOrder.verify(mMockService).storeBlob(eq(l2Key), eq(TEST_CLIENT_ID), eq(TEST_DATA_NAME),
                eq(b), any());
        inOrder.verify(mMockService).retrieveBlob(eq(l2Key), eq(TEST_CLIENT_ID),
                eq(TEST_OTHER_DATA_NAME), any());
        assertEquals(TEST_NETWORK_ATTRIBUTES, new NetworkAttributes(mNapCaptor.getValue()));
    }

    @Test
    public void testEnqueuedIpMsRequestsCallbackFunctionWithException() throws Exception {
        startIpMemoryStore(true /* supplyService */);

        final Blob b = new Blob();
        b.data = TEST_BLOB_DATA;
        final String l2Key = "fakeKey";

        // enqueue multiple ipms requests
        mStore.storeNetworkAttributes(l2Key, TEST_NETWORK_ATTRIBUTES,
                status -> assertTrue("Store not successful : " + status.resultCode,
                        status.isSuccess()));
        mStore.retrieveNetworkAttributes(l2Key,
                (status, key, attr) -> {
                    throw new RuntimeException("retrieveNetworkAttributes test");
                });
        mStore.storeBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, b,
                status -> {
                    throw new RuntimeException("storeBlob test");
                });
        mStore.retrieveBlob(l2Key, TEST_CLIENT_ID, TEST_OTHER_DATA_NAME,
                (status, key, name, data) -> {
                    assertTrue("Retrieve blob status not successful : " + status.resultCode,
                            status.isSuccess());
                    assertEquals(l2Key, key);
                    assertEquals(name, TEST_DATA_NAME);
                    assertTrue(Arrays.equals(b.data, data.data));
                });

        // verify the rest of the queue is still processed in order even if when one or more
        // callback throw the remote exception
        InOrder inOrder = inOrder(mMockService);

        inOrder.verify(mMockService).storeNetworkAttributes(eq(l2Key), mNapCaptor.capture(),
                any());
        inOrder.verify(mMockService).retrieveNetworkAttributes(eq(l2Key), any());
        inOrder.verify(mMockService).storeBlob(eq(l2Key), eq(TEST_CLIENT_ID), eq(TEST_DATA_NAME),
                eq(b), any());
        inOrder.verify(mMockService).retrieveBlob(eq(l2Key), eq(TEST_CLIENT_ID),
                eq(TEST_OTHER_DATA_NAME), any());
        assertEquals(TEST_NETWORK_ATTRIBUTES, new NetworkAttributes(mNapCaptor.getValue()));
    }

    @Test
    public void testFactoryReset() throws RemoteException {
        startIpMemoryStore(true /* supplyService */);
        mStore.factoryReset();
        verify(mMockService, times(1)).factoryReset();
    }
}
