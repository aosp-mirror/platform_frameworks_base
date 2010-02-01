package android.location;

/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.location.Address;
import android.location.Geocoder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;

@Suppress
public class GeocoderTest extends AndroidTestCase {

    public void testGeocoder() throws Exception {
        Locale locale = new Locale("en", "us");
        Geocoder g = new Geocoder(mContext, locale);

        List<Address> addresses1 = g.getFromLocation(37.435067, -122.166767, 2);
        assertNotNull(addresses1);
        assertEquals(1, addresses1.size());

        Address addr = addresses1.get(0);
        assertEquals("94305", addr.getFeatureName());
        assertEquals("Palo Alto, CA 94305", addr.getAddressLine(0));
        assertEquals("USA", addr.getAddressLine(1));
        assertEquals("94305", addr.getPostalCode());
        assertFalse(Math.abs(addr.getLatitude() - 37.4240385) > 0.1);

        List<Address> addresses2 = g.getFromLocationName("San Francisco, CA", 1);
        assertNotNull(addresses2);
        assertEquals(1, addresses2.size());

        addr = addresses2.get(0);
        assertEquals("San Francisco", addr.getFeatureName());
        assertEquals("San Francisco, CA", addr.getAddressLine(0));
        assertEquals("United States", addr.getAddressLine(1));
        assertEquals("San Francisco", addr.getLocality());
        assertEquals("CA", addr.getAdminArea());
        assertEquals(null, addr.getPostalCode());

        assertFalse(Math.abs(addr.getLatitude() - 37.77916) > 0.1);

    }
}
