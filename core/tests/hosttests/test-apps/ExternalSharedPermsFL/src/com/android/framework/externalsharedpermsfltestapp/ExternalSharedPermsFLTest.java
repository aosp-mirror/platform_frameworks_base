/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.framework.externalsharedpermsfltestapp;

import android.content.Context;
import android.os.Bundle;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import android.test.InstrumentationTestCase;

public class ExternalSharedPermsFLTest extends InstrumentationTestCase
{
    /** The use of location manager below is simply to simulate an app that
     *  tries to use it, so we can verify whether permissions are granted and accessible.
     * */
    public void testRunFineLocation()
    {
        LocationManager locationManager = (LocationManager)getInstrumentation().getContext(
                ).getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0,
                new LocationListener() {
                        public void onLocationChanged(Location location) {}
                        public void onProviderDisabled(String provider) {}
                        public void onProviderEnabled(String provider) {}
                        public void onStatusChanged(String provider, int status, Bundle extras) {}
                }
        );
    }
}
