/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;

import android.os.Parcel;

import junit.framework.TestCase;

import java.util.Arrays;

public class ScoredNetworkTest extends TestCase {
    private static final RssiCurve CURVE =
            new RssiCurve(-110, 10, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});

    public void testInvalidCurve_nullBuckets() {
        try {
            new RssiCurve(-110, 10, null);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testInvalidCurve_emptyBuckets() {
        try {
            new RssiCurve(-110, 10, new byte[] {});
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testParceling() {
        NetworkKey key = new NetworkKey(new WifiKey("\"ssid\"", "00:00:00:00:00:00"));
        ScoredNetwork network = new ScoredNetwork(key, CURVE);
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeParcelable(network, 0);
            parcel.setDataPosition(0);
            network = parcel.readParcelable(getClass().getClassLoader());
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
        assertEquals(CURVE.start, network.rssiCurve.start);
        assertEquals(CURVE.bucketWidth, network.rssiCurve.bucketWidth);
        assertTrue(Arrays.equals(CURVE.rssiBuckets, network.rssiCurve.rssiBuckets));
    }
}
