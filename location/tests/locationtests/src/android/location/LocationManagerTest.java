/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.location;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

@Suppress
public class LocationManagerTest extends AndroidTestCase {
    private static final String LOG_TAG = "LocationManagerTest";

    private LocationManager manager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        manager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        assertNotNull(manager);
    }

    public void testGetBogusProvider() {
        LocationProvider p = manager.getProvider("bogus");
        assertNull(p);
    }

    public void testGetNetworkProvider() {
        LocationProvider p = manager.getProvider("network");
        assertNotNull(p);
    }

    public void testGetGpsProvider() {
        LocationProvider p = manager.getProvider("gps");
        assertNotNull(p);
    }

    public void testGetBestProviderEmptyCriteria() {
        String p = manager.getBestProvider(new Criteria(), true);
        assertNotNull(p);
    }

    public void testGetBestProviderPowerCriteria() {
        Criteria c = new Criteria();
        c.setPowerRequirement(Criteria.POWER_HIGH);
        String p = manager.getBestProvider(c, true);
        assertNotNull(p);

        c.setPowerRequirement(Criteria.POWER_MEDIUM);
        p = manager.getBestProvider(c, true);
        assertNotNull(p);

        c.setPowerRequirement(Criteria.POWER_LOW);
        p = manager.getBestProvider(c, true);
        assertNotNull(p);

        c.setPowerRequirement(Criteria.NO_REQUIREMENT);
        p = manager.getBestProvider(c, true);
        assertNotNull(p);
    }

    public void testGpsTracklog() {
        LocationProvider p = manager.getProvider("gps");
        assertNotNull(p);

        // TODO: test requestUpdates method
    }

    public void testLocationConversions() {
        String loc1 = Location.convert(-80.075, Location.FORMAT_DEGREES);
        Log.i(LOG_TAG, "Input = " + (-80.075) + ", output = " + loc1);
        assertEquals("-80.075", loc1);

        String loc1b = Location.convert(-80.0, Location.FORMAT_DEGREES);
        Log.i(LOG_TAG, "Input = " + (-80.0) + ", output = " + loc1b);
        assertEquals("-80", loc1b);

        String loc2 = Location.convert(-80.085, Location.FORMAT_DEGREES);
        Log.i(LOG_TAG, "Input = " + (-80.085) + ", output = " + loc2);
        assertEquals("-80.085", loc2);

        String loc3 = Location.convert(-80.085, Location.FORMAT_MINUTES);
        Log.i(LOG_TAG, "Input = " + (-80.085) + ", output = " + loc3);
        assertEquals("-80:5.1", loc3);

        String loc4 = Location.convert(-80.085, Location.FORMAT_SECONDS);
        Log.i(LOG_TAG, "Input = " + (-80.085) + ", output = " + loc4);
        assertEquals("-80:5:6", loc4);

        String loc5 = Location.convert(5 + 0.5f / 60.0f, Location.FORMAT_MINUTES);
        Log.i(LOG_TAG, "Input = 5:0.5, output = " + loc5);
        int index = loc5.indexOf(':');
        String loc5a = loc5.substring(0, index);
        Log.i(LOG_TAG, "loc5a = " + loc5a);
        assertTrue(loc5a.equals("5"));
        String loc5b = loc5.substring(index + 1);
        Log.i(LOG_TAG, "loc5b = " + loc5b);
        double minutes = Double.parseDouble(loc5b);
        Log.i(LOG_TAG, "minutes = " + minutes);
        assertTrue(Math.abs(minutes - 0.5) < 0.0001);

        String loc6 = Location.convert(0.1, Location.FORMAT_DEGREES);
        Log.i(LOG_TAG, "loc6 = " + loc6);
        assertEquals(loc6, "0.1");

        String loc7 = Location.convert(0.1, Location.FORMAT_MINUTES);
        Log.i(LOG_TAG, "loc7 = " + loc7);
        assertEquals(loc7, "0:6");

        String loc8 = Location.convert(0.1, Location.FORMAT_SECONDS);
        Log.i(LOG_TAG, "loc8 = " + loc8);
        assertEquals(loc8, "0:6:0");
    }
}
