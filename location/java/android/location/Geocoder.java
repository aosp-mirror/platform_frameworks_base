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

package android.location;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A class for handling geocoding and reverse geocoding.  Geocoding is
 * the process of transforming a street address or other description
 * of a location into a (latitude, longitude) coordinate.  Reverse
 * geocoding is the process of transforming a (latitude, longitude)
 * coordinate into a (partial) address.  The amount of detail in a
 * reverse geocoded location description may vary, for example one
 * might contain the full street address of the closest building, while
 * another might contain only a city name and postal code.
 *
 * The Geocoder class requires a backend service that is not included in
 * the core android framework.  The Geocoder query methods will return an
 * empty list if there no backend service in the platform.  Use the
 * isPresent() method to determine whether a Geocoder implementation
 * exists.
 *
 * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
 * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful or
 * correct. Do not use this API for any safety-critical or regulatory compliance purpose.
 */
public final class Geocoder {

    private static final long TIMEOUT_MS = 60000;

    private final GeocoderParams mParams;
    private final ILocationManager mService;

    /**
     * Returns true if the Geocoder methods getFromLocation and
     * getFromLocationName are implemented.  Lack of network
     * connectivity may still cause these methods to return null or
     * empty lists.
     */
    public static boolean isPresent() {
        IBinder b = ServiceManager.getService(Context.LOCATION_SERVICE);
        ILocationManager lm = ILocationManager.Stub.asInterface(b);
        try {
            return lm.geocoderIsPresent();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Constructs a Geocoder whose responses will be localized for the
     * given Locale.
     *
     * @param context the Context of the calling Activity
     * @param locale the desired Locale for the query results
     *
     * @throws NullPointerException if Locale is null
     */
    public Geocoder(Context context, Locale locale) {
        mParams = new GeocoderParams(context, locale);
        mService = ILocationManager.Stub.asInterface(
                ServiceManager.getService(Context.LOCATION_SERVICE));
    }

    /**
     * Constructs a Geocoder whose responses will be localized for the
     * default system Locale.
     *
     * @param context the Context of the calling Activity
     */
    public Geocoder(Context context) {
        this(context, Locale.getDefault());
    }

    /**
     * Returns an array of Addresses that attempt to describe the area immediately surrounding the
     * given latitude and longitude. The returned addresses should be localized for the locale
     * provided to this class's constructor. Results may be obtained by means of a network lookup
     * and this method may take some time to return, and so should not be called on the main thread.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.
     *
     * @param latitude the latitude a point for the search
     * @param longitude the longitude a point for the search
     * @param maxResults max number of addresses to return. Smaller numbers (1 to 5) are recommended
     *
     * @return a list of Address objects. Returns null or empty list if no matches were
     * found or there is no backend service available.
     *
     * @throws IllegalArgumentException if latitude is
     * less than -90 or greater than 90
     * @throws IllegalArgumentException if longitude is
     * less than -180 or greater than 180
     * @throws IOException if the network is unavailable or any other
     * I/O problem occurs
     */
    public List<Address> getFromLocation(double latitude, double longitude, int maxResults)
            throws IOException {
        Preconditions.checkArgumentInRange(latitude, -90.0, 90.0, "latitude");
        Preconditions.checkArgumentInRange(longitude, -180.0, 180.0, "longitude");

        try {
            GeocodeListener listener = new GeocodeListener();
            mService.getFromLocation(latitude, longitude, maxResults, mParams, listener);
            return listener.getResults();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an array of Addresses that attempt to describe the named location, which may be a
     * place name such as "Dalvik, Iceland", an address such as "1600 Amphitheatre Parkway, Mountain
     * View, CA", an airport code such as "SFO", and so forth. The returned addresses should be
     * localized for the locale provided to this class's constructor. Results may be obtained by
     * means of a network lookup and this method may take some time to return, and so should not be
     * called on the main thread.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.
     *
     * @param locationName a user-supplied description of a location
     * @param maxResults max number of results to return. Smaller numbers (1 to 5) are recommended
     *
     * @return a list of Address objects. Returns null or empty list if no matches were
     * found or there is no backend service available.
     *
     * @throws IllegalArgumentException if locationName is null
     * @throws IOException if the network is unavailable or any other
     * I/O problem occurs
     */
    public List<Address> getFromLocationName(String locationName, int maxResults) throws IOException {
        return getFromLocationName(locationName, maxResults, 0, 0, 0, 0);
    }

    /**
     * Returns an array of Addresses that attempt to describe the named location, which may be a
     * place name such as "Dalvik, Iceland", an address such as "1600 Amphitheatre Parkway, Mountain
     * View, CA", an airport code such as "SFO", and so forth. The returned addresses should be
     * localized for the locale provided to this class's constructor. Results may be obtained by
     * means of a network lookup and this method may take some time to return, and so should not be
     * called on the main thread.
     *
     * <p> You may specify a bounding box for the search results by including the latitude and
     * longitude of the lower left point and upper right point of the box.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.
     *
     * @param locationName a user-supplied description of a location
     * @param maxResults max number of addresses to return. Smaller numbers (1 to 5) are recommended
     * @param lowerLeftLatitude the latitude of the lower left corner of the bounding box
     * @param lowerLeftLongitude the longitude of the lower left corner of the bounding box
     * @param upperRightLatitude the latitude of the upper right corner of the bounding box
     * @param upperRightLongitude the longitude of the upper right corner of the bounding box
     *
     * @return a list of Address objects. Returns null or empty list if no matches were
     * found or there is no backend service available.
     *
     * @throws IllegalArgumentException if locationName is null
     * @throws IllegalArgumentException if any latitude is
     * less than -90 or greater than 90
     * @throws IllegalArgumentException if any longitude is
     * less than -180 or greater than 180
     * @throws IOException if the network is unavailable or any other
     * I/O problem occurs
     */
    public List<Address> getFromLocationName(String locationName, int maxResults,
            double lowerLeftLatitude, double lowerLeftLongitude, double upperRightLatitude,
            double upperRightLongitude) throws IOException {
        Preconditions.checkArgument(locationName != null);
        Preconditions.checkArgumentInRange(lowerLeftLatitude, -90.0, 90.0, "lowerLeftLatitude");
        Preconditions.checkArgumentInRange(lowerLeftLongitude, -180.0, 180.0, "lowerLeftLongitude");
        Preconditions.checkArgumentInRange(upperRightLatitude, -90.0, 90.0, "upperRightLatitude");
        Preconditions.checkArgumentInRange(upperRightLongitude, -180.0, 180.0,
                "upperRightLongitude");

        try {
            GeocodeListener listener = new GeocodeListener();
            mService.getFromLocationName(locationName, lowerLeftLatitude, lowerLeftLongitude,
                    upperRightLatitude, upperRightLongitude, maxResults, mParams, listener);
            return listener.getResults();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class GeocodeListener extends IGeocodeListener.Stub {
        private final CountDownLatch mLatch = new CountDownLatch(1);

        private String mError = null;
        private List<Address> mResults = Collections.emptyList();

        GeocodeListener() {}

        @Override
        public void onResults(String error, List<Address> results) {
            mError = error;
            mResults = results;
            mLatch.countDown();
        }

        public List<Address> getResults() throws IOException {
            try {
                if (!mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    mError = "Service not Available";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (mError != null) {
                throw new IOException(mError);
            } else {
                return mResults;
            }
        }
    }
}
