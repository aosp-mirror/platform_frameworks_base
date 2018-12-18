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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.IOnStatusListener;
import android.net.ipmemorystore.NetworkAttributes;
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
import java.net.Inet4Address;
import java.net.UnknownHostException;
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
        final CountDownLatch latch = new CountDownLatch(1);
        mService.storeNetworkAttributes(l2Key, na.build().toParcelable(),
                onStatus(status -> {
                    assertTrue("Store status not successful : " + status.resultCode,
                            status.isSuccess());
                    latch.countDown();
                }));
        try {
            latch.await(5000, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Did not complete storing attributes");
        }
    }

    @Test
    public void testPrivateData() {
        final Blob b = new Blob();
        b.data = new byte[] { -3, 6, 8, -9, 12, -128, 0, 89, 112, 91, -34 };
        final String l2Key = UUID.randomUUID().toString();
        final CountDownLatch latch = new CountDownLatch(1);
        mService.storeBlob(l2Key, TEST_CLIENT_ID, TEST_DATA_NAME, b,
                onStatus(status -> {
                    assertTrue("Store status not successful : " + status.resultCode,
                            status.isSuccess());
                    latch.countDown();
                }));
        try {
            latch.await(5000, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Did not complete storing private data");
        }
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
