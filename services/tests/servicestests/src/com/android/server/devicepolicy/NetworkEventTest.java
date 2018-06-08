/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.devicepolicy;

import static com.android.server.devicepolicy.NetworkLoggingHandler.LOG_NETWORK_EVENT_MSG;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.admin.ConnectEvent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.DnsEvent;
import android.app.admin.NetworkEvent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.mockito.ArgumentCaptor;

import java.util.List;

@SmallTest
public class NetworkEventTest extends DpmTestBase {
    private static final int MAX_EVENTS_PER_BATCH = 1200;

    private DpmMockContext mSpiedDpmMockContext;
    private DevicePolicyManagerServiceTestable mDpmTestable;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSpiedDpmMockContext = spy(mMockContext);
        mSpiedDpmMockContext.callerPermissions.add(
                android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        doNothing().when(mSpiedDpmMockContext).sendBroadcastAsUser(any(Intent.class),
                any(UserHandle.class));
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        mDpmTestable = new DevicePolicyManagerServiceTestable(getServices(), mSpiedDpmMockContext);
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        mDpmTestable.setActiveAdmin(admin1, true, DpmMockContext.CALLER_USER_HANDLE);
    }

    public void testNetworkEventId_monotonicallyIncreasing() throws Exception {
        // GIVEN the handler has not processed any events.
        long startingId = 0;

        // WHEN the handler has processed the events.
        List<NetworkEvent> events = fillHandlerWithFullBatchOfEvents(startingId);

        // THEN the events are in a batch.
        assertTrue("Batch not at the returned token.",
                events != null && events.size() == MAX_EVENTS_PER_BATCH);
        // THEN event ids are monotonically increasing.
        long expectedId = startingId;
        for (int i = 0; i < MAX_EVENTS_PER_BATCH; i++) {
            assertEquals("At index " + i + ", the event has the wrong id.", expectedId,
                    events.get(i).getId());
            expectedId++;
        }
    }

    public void testNetworkEventId_wrapsAround() throws Exception {
        // GIVEN the handler has almost processed Long.MAX_VALUE events.
        int gap = 5;
        long startingId = Long.MAX_VALUE - gap;

        // WHEN the handler has processed the events.
        List<NetworkEvent> events = fillHandlerWithFullBatchOfEvents(startingId);

        // THEN the events are in a batch.
        assertTrue("Batch not at the returned token.",
                events != null && events.size() == MAX_EVENTS_PER_BATCH);
        // THEN event ids are monotonically increasing.
        long expectedId = startingId;
        for (int i = 0; i < gap; i++) {
            assertEquals("At index " + i + ", the event has the wrong id.", expectedId,
                    events.get(i).getId());
            expectedId++;
        }
        // THEN event ids are reset when the id reaches the maximum possible value.
        assertEquals("Event was not assigned the maximum id value.", Long.MAX_VALUE,
                events.get(gap).getId());
        assertEquals("Event id was not reset.", 0, events.get(gap + 1).getId());
        // THEN event ids are monotonically increasing.
        expectedId = 0;
        for (int i = gap + 1; i < MAX_EVENTS_PER_BATCH; i++) {
            assertEquals("At index " + i + ", the event has the wrong id.", expectedId,
                    events.get(i).getId());
            expectedId++;
        }
    }

    private List<NetworkEvent> fillHandlerWithFullBatchOfEvents(long startingId) throws Exception {
        // GIVEN a handler with events
        NetworkLoggingHandler handler = new NetworkLoggingHandler(new TestLooper().getLooper(),
                mDpmTestable, startingId);
        // GIVEN network events are sent to the handler.
        for (int i = 0; i < MAX_EVENTS_PER_BATCH; i++) {
            ConnectEvent event = new ConnectEvent("some_ip_address", 800, "com.google.foo",
                    SystemClock.currentThreadTimeMillis());
            Message msg = new Message();
            msg.what = LOG_NETWORK_EVENT_MSG;
            Bundle bundle = new Bundle();
            bundle.putParcelable(NetworkLoggingHandler.NETWORK_EVENT_KEY, event);
            msg.setData(bundle);
            handler.handleMessage(msg);
        }

        // WHEN the handler processes the events.
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mSpiedDpmMockContext).sendBroadcastAsUser(intentCaptor.capture(),
                any(UserHandle.class));
        assertEquals(intentCaptor.getValue().getAction(),
                DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE);
        long token = intentCaptor.getValue().getExtras().getLong(
                DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, 0);
        return handler.retrieveFullLogBatch(token);
    }

    /**
     * Test parceling and unparceling of a ConnectEvent.
     */
    public void testConnectEventParceling() {
        ConnectEvent event = new ConnectEvent("127.0.0.1", 80, "com.android.whateverdude", 100000);
        event.setId(5L);
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        ConnectEvent unparceledEvent = p.readParcelable(NetworkEventTest.class.getClassLoader());
        p.recycle();
        assertEquals(event.getInetAddress(), unparceledEvent.getInetAddress());
        assertEquals(event.getPort(), unparceledEvent.getPort());
        assertEquals(event.getPackageName(), unparceledEvent.getPackageName());
        assertEquals(event.getTimestamp(), unparceledEvent.getTimestamp());
        assertEquals(event.getId(), unparceledEvent.getId());
    }

    /**
     * Test parceling and unparceling of a DnsEvent.
     */
    public void testDnsEventParceling() {
        DnsEvent event = new DnsEvent("d.android.com", new String[]{"192.168.0.1", "127.0.0.1"}, 2,
                "com.android.whateverdude", 100000);
        event.setId(5L);
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        DnsEvent unparceledEvent = p.readParcelable(NetworkEventTest.class.getClassLoader());
        p.recycle();
        assertEquals(event.getHostname(), unparceledEvent.getHostname());
        assertEquals(event.getInetAddresses().get(0), unparceledEvent.getInetAddresses().get(0));
        assertEquals(event.getInetAddresses().get(1), unparceledEvent.getInetAddresses().get(1));
        assertEquals(event.getTotalResolvedAddressCount(),
                unparceledEvent.getTotalResolvedAddressCount());
        assertEquals(event.getPackageName(), unparceledEvent.getPackageName());
        assertEquals(event.getTimestamp(), unparceledEvent.getTimestamp());
        assertEquals(event.getId(), unparceledEvent.getId());
    }
}
