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

package android.net.ipmemorystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ParcelableTests {
    @Test
    public void testNetworkAttributesParceling() throws Exception {
        final NetworkAttributes.Builder builder = new NetworkAttributes.Builder();
        NetworkAttributes in = builder.build();
        assertEquals(in, new NetworkAttributes(parcelingRoundTrip(in.toParcelable())));

        builder.setAssignedV4Address((Inet4Address) Inet4Address.getByName("1.2.3.4"));
        // groupHint stays null this time around
        builder.setDnsAddresses(Collections.emptyList());
        builder.setMtu(18);
        in = builder.build();
        assertEquals(in, new NetworkAttributes(parcelingRoundTrip(in.toParcelable())));

        builder.setAssignedV4Address((Inet4Address) Inet4Address.getByName("6.7.8.9"));
        builder.setGroupHint("groupHint");
        builder.setDnsAddresses(Arrays.asList(
                InetAddress.getByName("ACA1:652B:0911:DE8F:1200:115E:913B:AA2A"),
                InetAddress.getByName("6.7.8.9")));
        builder.setMtu(1_000_000);
        in = builder.build();
        assertEquals(in, new NetworkAttributes(parcelingRoundTrip(in.toParcelable())));

        builder.setMtu(null);
        in = builder.build();
        assertEquals(in, new NetworkAttributes(parcelingRoundTrip(in.toParcelable())));

        // Verify that this test does not miss any new field added later.
        // If any field is added to NetworkAttributes it must be tested here for parceling
        // roundtrip.
        assertEquals(4, Arrays.stream(NetworkAttributes.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).count());
    }

    @Test
    public void testPrivateDataParceling() throws Exception {
        final Blob in = new Blob();
        in.data = new byte[] {89, 111, 108, 111};
        final Blob out = parcelingRoundTrip(in);
        // Object.equals on byte[] tests the references
        assertEquals(in.data.length, out.data.length);
        assertTrue(Arrays.equals(in.data, out.data));
    }

    @Test
    public void testSameL3NetworkResponseParceling() throws Exception {
        final SameL3NetworkResponseParcelable parcelable = new SameL3NetworkResponseParcelable();
        parcelable.l2Key1 = "key 1";
        parcelable.l2Key2 = "key 2";
        parcelable.confidence = 0.43f;

        final SameL3NetworkResponse in = new SameL3NetworkResponse(parcelable);
        assertEquals("key 1", in.l2Key1);
        assertEquals("key 2", in.l2Key2);
        assertEquals(0.43f, in.confidence, 0.01f /* delta */);

        final SameL3NetworkResponse out =
                new SameL3NetworkResponse(parcelingRoundTrip(in.toParcelable()));

        assertEquals(in, out);
        assertEquals(in.l2Key1, out.l2Key1);
        assertEquals(in.l2Key2, out.l2Key2);
        assertEquals(in.confidence, out.confidence, 0.01f /* delta */);
    }

    private <T extends Parcelable> T parcelingRoundTrip(final T in) throws Exception {
        final Parcel p = Parcel.obtain();
        in.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        final byte[] marshalledData = p.marshall();
        p.recycle();

        final Parcel q = Parcel.obtain();
        q.unmarshall(marshalledData, 0, marshalledData.length);
        q.setDataPosition(0);

        final Parcelable.Creator<T> creator = (Parcelable.Creator<T>)
                in.getClass().getField("CREATOR").get(null); // static object, so null receiver
        final T unmarshalled = (T) creator.createFromParcel(q);
        q.recycle();
        return unmarshalled;
    }
}
