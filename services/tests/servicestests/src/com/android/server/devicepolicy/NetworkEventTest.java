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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.admin.ConnectEvent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DnsEvent;
import android.app.admin.NetworkEvent;
import android.content.Intent;
import android.os.Bundle;
import android.os.IpcDataCache;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

@SmallTest
public class NetworkEventTest extends DpmTestBase {
    private static final int MAX_EVENTS_PER_BATCH = 1200;

    private DpmMockContext mSpiedDpmMockContext;
    private DevicePolicyManagerServiceTestable mDpmTestable;

    @Before
    public void setUp() throws Exception {
        // Disable caches in this test process. This must happen early, since some of the
        // following initialization steps invalidate caches.
        IpcDataCache.disableForTestMode();

        mSpiedDpmMockContext = spy(mMockContext);
        mSpiedDpmMockContext.callerPermissions.add(
                android.Manifest.permission.MANAGE_DEVICE_ADMINS);
        doNothing().when(mSpiedDpmMockContext).sendBroadcastAsUser(any(Intent.class),
                any(UserHandle.class));
        mDpmTestable = new DevicePolicyManagerServiceTestable(getServices(), mSpiedDpmMockContext);
        setUpPackageManagerForAdmin(admin1, DpmMockContext.CALLER_UID);
        mDpmTestable.setActiveAdmin(admin1, true, DpmMockContext.CALLER_USER_HANDLE, null);
    }

    @Test
    public void testNetworkEventId_monotonicallyIncreasing() throws Exception {
        // GIVEN the handler has not processed any events.
        long startingId = 0;

        // WHEN the handler has processed the events.
        List<NetworkEvent> events = fillHandlerWithFullBatchOfEvents(startingId);

        // THEN the events are in a batch.
        assertWithMessage("Batch not at the returned token.").that(events).isNotNull();
        assertWithMessage("Batch not at the returned token.").that(events)
                .hasSize(MAX_EVENTS_PER_BATCH);

        // THEN event ids are monotonically increasing.
        long expectedId = startingId;
        for (int i = 0; i < MAX_EVENTS_PER_BATCH; i++) {
            assertWithMessage("At index %s, the event has the wrong id.", i)
                    .that(events.get(i).getId()).isEqualTo(expectedId);
            expectedId++;
        }
    }

    @Test
    public void testNetworkEventId_wrapsAround() throws Exception {
        // GIVEN the handler has almost processed Long.MAX_VALUE events.
        int gap = 5;
        long startingId = Long.MAX_VALUE - gap;

        // WHEN the handler has processed the events.
        List<NetworkEvent> events = fillHandlerWithFullBatchOfEvents(startingId);

        // THEN the events are in a batch.
        assertWithMessage("Batch not at the returned token.").that(events).isNotNull();
        assertWithMessage("Batch not at the returned token.").that(events)
                .hasSize(MAX_EVENTS_PER_BATCH);
        // THEN event ids are monotonically increasing.
        long expectedId = startingId;
        for (int i = 0; i < gap; i++) {
            assertWithMessage("At index %s, the event has the wrong id.", i)
                    .that(events.get(i).getId()).isEqualTo(expectedId);
            expectedId++;
        }
        // THEN event ids are reset when the id reaches the maximum possible value.
        assertWithMessage("Event was not assigned the maximum id value.")
                .that(events.get(gap).getId()).isEqualTo(Long.MAX_VALUE);
        assertWithMessage("Event id was not reset.").that(events.get(gap + 1).getId()).isEqualTo(0);
        // THEN event ids are monotonically increasing.
        expectedId = 0;
        for (int i = gap + 1; i < MAX_EVENTS_PER_BATCH; i++) {
            assertWithMessage("At index %s, the event has the wrong id.", i)
                    .that(events.get(i).getId()).isEqualTo(expectedId);
            expectedId++;
        }
    }

    private List<NetworkEvent> fillHandlerWithFullBatchOfEvents(long startingId) throws Exception {
        // GIVEN a handler with events
        NetworkLoggingHandler handler = new NetworkLoggingHandler(new TestLooper().getLooper(),
                mDpmTestable, startingId, DpmMockContext.CALLER_USER_HANDLE);
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
        assertThat(DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE)
                .isEqualTo(intentCaptor.getValue().getAction());
        long token = intentCaptor.getValue().getExtras().getLong(
                DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, 0);
        return handler.retrieveFullLogBatch(token);
    }

    /**
     * Test parceling and unparceling of a ConnectEvent.
     */
    @Test
    public void testConnectEventParceling() {
        ConnectEvent event = new ConnectEvent("127.0.0.1", 80, "com.android.whateverdude", 100000);
        event.setId(5L);
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        ConnectEvent unparceledEvent = p.readParcelable(NetworkEventTest.class.getClassLoader());
        p.recycle();
        assertThat(unparceledEvent.getInetAddress()).isEqualTo(event.getInetAddress());
        assertThat(unparceledEvent.getPort()).isEqualTo(event.getPort());
        assertThat(unparceledEvent.getPackageName()).isEqualTo(event.getPackageName());
        assertThat(unparceledEvent.getTimestamp()).isEqualTo(event.getTimestamp());
        assertThat(unparceledEvent.getId()).isEqualTo(event.getId());
    }

    /**
     * Test parceling and unparceling of a DnsEvent.
     */
    @Test
    public void testDnsEventParceling() {
        DnsEvent event = new DnsEvent("d.android.com", new String[]{"192.168.0.1", "127.0.0.1"}, 2,
                "com.android.whateverdude", 100000);
        event.setId(5L);
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        DnsEvent unparceledEvent = p.readParcelable(NetworkEventTest.class.getClassLoader());
        p.recycle();
        assertThat(unparceledEvent.getHostname()).isEqualTo(event.getHostname());
        assertThat(unparceledEvent.getInetAddresses().get(0))
                .isEqualTo(event.getInetAddresses().get(0));
        assertThat(unparceledEvent.getInetAddresses().get(1))
                .isEqualTo(event.getInetAddresses().get(1));
        assertThat(unparceledEvent.getTotalResolvedAddressCount())
                .isEqualTo(event.getTotalResolvedAddressCount());
        assertThat(unparceledEvent.getPackageName()).isEqualTo(event.getPackageName());
        assertThat(unparceledEvent.getTimestamp()).isEqualTo(event.getTimestamp());
        assertThat(unparceledEvent.getId()).isEqualTo(event.getId());
    }
}
