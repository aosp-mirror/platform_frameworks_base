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

package com.android.server.net.ipmemorystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.IOnBlobRetrievedListener;
import android.net.ipmemorystore.IOnNetworkAttributesRetrieved;
import android.net.ipmemorystore.IOnStatusListener;
import android.net.ipmemorystore.NetworkAttributes;
import android.net.ipmemorystore.NetworkAttributesParcelable;
import android.net.ipmemorystore.Status;
import android.net.ipmemorystore.StatusParcelable;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Unit tests for {@link IpMemoryStoreService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class IpMemoryStoreServiceTest {
    private static final String TEST_CLIENT_ID = "testClientId";
    private static final String TEST_DATA_NAME = "testData";

    @Mock
    private Context mMockContext;
    private File mDbFile;

    private IpMemoryStoreService mService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        final Context context = InstrumentationRegistry.getContext();
        final File dir = context.getFilesDir();
        mDbFile = new File(dir, "test.db");
        doReturn(mDbFile).when(mMockContext).getDatabasePath(anyString());
        mService = new IpMemoryStoreService(mMockContext);
    }

    @After
    public void tearDown() {
        mService.shutdown();
        mDbFile.delete();
    }

    /** Helper method to make a vanilla IOnStatusListener */
    private IOnStatusListener onStatus(Consumer<Status> functor) {
        return new IOnStatusListener() {
            @Override
            public void onComplete(final StatusParcelable statusParcelable) throws RemoteException {
                functor.accept(new Status(statusParcelable));
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };
    }

    /** Helper method to make an IOnBlobRetrievedListener */
    private interface OnBlobRetrievedListener {
        void onBlobRetrieved(Status status, String l2Key, String name, byte[] data);
    }
    private IOnBlobRetrievedListener onBlobRetrieved(final OnBlobRetrievedListener functor) {
        return new IOnBlobRetrievedListener() {
            @Override
            public void onBlobRetrieved(final StatusParcelable statusParcelable,
                    final String l2Key, final String name, final Blob blob) throws RemoteException {
                functor.onBlobRetrieved(new Status(statusParcelable), l2Key, name,
                        null == blob ? null : blob.data);
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };
    }

    /** Helper method to make an IOnNetworkAttributesRetrievedListener */
    private interface OnNetworkAttributesRetrievedListener  {
        void onNetworkAttributesRetrieved(Status status, String l2Key, NetworkAttributes attr);
    }
    private IOnNetworkAttributesRetrieved onNetworkAttributesRetrieved(
            final OnNetworkAttributesRetrievedListener functor) {
        return new IOnNetworkAttributesRetrieved() {
            @Override
            public void onL2KeyResponse(final StatusParcelable status, final String l2Key,
                    final NetworkAttributesParcelable attributes)
                    throws RemoteException {
                functor.onNetworkAttributesRetrieved(new Status(status), l2Key,
                        null == attributes ? null : new NetworkAttributes(attributes));
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };
    }

    // Helper method to factorize some boilerplate
    private void doLatched(final String timeoutMessage, final Consumer<CountDownLatch> functor) {
        final CountDownLatch latch = new CountDownLatch(1);
        functor.accept(latch);
        try {
            latch.await(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail(timeoutMessage);
        }
    }

    @Test
    public void testNetworkAttributes() {
        final NetworkAttributes.Builder na = new NetworkAttributes.Builder();
        try {
            na.setAssignedV4Address(
                    (Inet4Address) Inet4Address.getByAddress(new byte[]{1, 2, 3, 4}));
        } catch (UnknownHostException e) { /* Can't happen */ }
        na.setGroupHint("hint1");
        na.setMtu(219);
        final String l2Key = UUID.randomUUID().toString();
        NetworkAttributes attributes = na.build();
        doLatched("Did not complete storing attributes", latch ->
                mService.storeNetworkAttributes(l2Key, attributes.toParcelable(),
                        onStatus(status -> {
                            assertTrue("Store status not successful : " + status.resultCode,
                                    status.isSuccess());
                            latch.countDown();
                        })));

        doLatched("Did not complete retrieving attributes", latch ->
                mService.retrieveNetworkAttributes(l2Key, onNetworkAttributesRetrieved(
                        (status, key, attr) -> {
                            assertTrue("Retrieve network attributes not successful : "
                                    + status.resultCode, status.isSuccess());
                            assertEquals(l2Key, key);
                            assertEquals(attributes, attr);
                            latch.countDown();
                        })));

        final NetworkAttributes.Builder na2 = new NetworkAttributes.Builder();
        try {
            na.setDnsAddresses(Arrays.asList(
                    new InetAddress[] {Inet6Address.getByName("0A1C:2E40:480A::1CA6")}));
        } catch (UnknownHostException e) { /* Still can't happen */ }
        final NetworkAttributes attributes2 = na2.build();
        doLatched("Did not complete storing attributes 2", latch ->
                mService.storeNetworkAttributes(l2Key, attributes2.toParcelable(),
                        onStatus(status -> latch.countDown())));

        doLatched("Did not complete retrieving attributes 2", latch ->
                mService.retrieveNetworkAttributes(l2Key, onNetworkAttributesRetrieved(
                        (status, key, attr) -> {
                            assertTrue("Retrieve network attributes not successful : "
                                    + status.resultCode, status.isSuccess());
                            assertEquals(l2Key, key);
                            assertEquals(attributes.assignedV4Address, attr.assignedV4Address);
                            assertEquals(attributes.groupHint, attr.groupHint);
                            assertEquals(attributes.mtu, attr.mtu);
                            assertEquals(attributes2.dnsAddresses, attr.dnsAddresses);
                            latch.countDown();
                        })));

        doLatched("Did not complete retrieving attributes 3", latch ->
                mService.retrieveNetworkAttributes(l2Key + "nonexistent",
                        onNetworkAttributesRetrieved(
                                (status, key, attr) -> {
                                    assertTrue("Retrieve network attributes not successful : "
                                            + status.resultCode, status.isSuccess());
                                    assertEquals(l2Key + "nonexistent", key);
                                    assertNull("Retrieved data not stored", attr);
                                    latch.countDown();
                                }
                        )));

        // Verify that this test does not miss any new field added later.
        // If any field is added to NetworkAttributes it must be tested here for storing
        // and retrieving.
        assertEquals(4, Arrays.stream(NetworkAttributes.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).count());
    }

    @Test
    public void testInvalidAttributes() {
        doLatched("Did not complete storing bad attributes", latch ->
                mService.storeNetworkAttributes("key", null, onStatus(status -> {
                    assertFalse("Success storing on a null key",
                            status.isSuccess());
                    assertEquals(Status.ERROR_ILLEGAL_ARGUMENT, status.resultCode);
                    latch.countDown();
                })));

        final NetworkAttributes na = new NetworkAttributes.Builder().setMtu(2).build();
        doLatched("Did not complete storing bad attributes", latch ->
                mService.storeNetworkAttributes(null, na.toParcelable(), onStatus(status -> {
                    assertFalse("Success storing null attributes on a null key",
                            status.isSuccess());
                    assertEquals(Status.ERROR_ILLEGAL_ARGUMENT, status.resultCode);
                    latch.countDown();
                })));

        doLatched("Did not complete storing bad attributes", latch ->
                mService.storeNetworkAttributes(null, null, onStatus(status -> {
                    assertFalse("Success storing null attributes on a null key",
                            status.isSuccess());
                    assertEquals(Status.ERROR_ILLEGAL_ARGUMENT, status.resultCode);
                    latch.countDown();
                })));

        doLatched("Did not complete retrieving bad attributes", latch ->
                mService.retrieveNetworkAttributes(null, onNetworkAttributesRetrieved(
                        (status, key, attr) -> {
                            assertFalse("Success retrieving attributes for a null key",
                                    status.isSuccess());
                            assertEquals(Status.ERROR_ILLEGAL_ARGUMENT, status.resultCode);
                            assertNull(key);
                            assertNull(attr);
                        })));
    }

    @Test
    public void testPrivateData() {
        final Blob b = new Blob();
        b.data = new byte[] { -3, 6, 8, -9, 12, -128, 0, 89, 112, 91, -34 };
        final String l2Key = UUID.randomUUID().toString();
        doLatched("Did not complete storing private data", latch ->
                mService.storeBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, b,
                        onStatus(status -> {
                            assertTrue("Store status not successful : " + status.resultCode,
                                    status.isSuccess());
                            latch.countDown();
                        })));

        doLatched("Did not complete retrieving private data", latch ->
                mService.retrieveBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, onBlobRetrieved(
                        (status, key, name, data) -> {
                            assertTrue("Retrieve blob status not successful : " + status.resultCode,
                                    status.isSuccess());
                            assertEquals(l2Key, key);
                            assertEquals(name, TEST_DATA_NAME);
                            Arrays.equals(b.data, data);
                            latch.countDown();
                        })));

        // Most puzzling error message ever
        doLatched("Did not complete retrieving nothing", latch ->
                mService.retrieveBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME + "2", onBlobRetrieved(
                        (status, key, name, data) -> {
                            assertTrue("Retrieve blob status not successful : " + status.resultCode,
                                    status.isSuccess());
                            assertEquals(l2Key, key);
                            assertEquals(name, TEST_DATA_NAME + "2");
                            assertNull(data);
                            latch.countDown();
                        })));
    }

    @Test
    public void testFindL2Key() {
        // TODO : implement this
    }

    @Test
    public void testIsSameNetwork() {
        // TODO : implement this
    }
}
