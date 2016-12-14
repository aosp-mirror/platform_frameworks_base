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

import android.app.admin.ConnectEvent;
import android.app.admin.DnsEvent;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import static junit.framework.Assert.assertEquals;

@SmallTest
public class NetworkEventTest extends DpmTestBase {

    /**
     * Test parceling and unparceling of a ConnectEvent.
     */
    public void testConnectEventParceling() {
        ConnectEvent event = new ConnectEvent("127.0.0.1", 80, "com.android.whateverdude", 100000);
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        ConnectEvent unparceledEvent = p.readParcelable(NetworkEventTest.class.getClassLoader());
        p.recycle();
        assertEquals(event.getIpAddress(), unparceledEvent.getIpAddress());
        assertEquals(event.getPort(), unparceledEvent.getPort());
        assertEquals(event.getPackageName(), unparceledEvent.getPackageName());
        assertEquals(event.getTimestamp(), unparceledEvent.getTimestamp());
    }

    /**
     * Test parceling and unparceling of a DnsEvent.
     */
    public void testDnsEventParceling() {
        DnsEvent event = new DnsEvent("d.android.com", new String[]{"192.168.0.1", "127.0.0.1"}, 2,
                "com.android.whateverdude", 100000);
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        DnsEvent unparceledEvent = p.readParcelable(NetworkEventTest.class.getClassLoader());
        p.recycle();
        assertEquals(event.getHostname(), unparceledEvent.getHostname());
        assertEquals(event.getIpAddresses()[0], unparceledEvent.getIpAddresses()[0]);
        assertEquals(event.getIpAddresses()[1], unparceledEvent.getIpAddresses()[1]);
        assertEquals(event.getIpAddressesCount(), unparceledEvent.getIpAddressesCount());
        assertEquals(event.getPackageName(), unparceledEvent.getPackageName());
        assertEquals(event.getTimestamp(), unparceledEvent.getTimestamp());
    }
}
