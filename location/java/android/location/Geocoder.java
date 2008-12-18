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
import android.location.Address;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/**
 * A class for handling geocoding and reverse geocoding.  Geocoding is
 * the process of transforming a street address or other description
 * of a location into a (latitude, longitude) coordinate.  Reverse
 * geocoding is the process of transforming a (latitude, longitude)
 * coordinate into a (partial) address.  The amount of detail in a
 * reverse geocoded location description may vary, for example one
 * might contain the full street address of the closest building, while
 * another might contain only a city name and postal code.
 */
public final class Geocoder {
    private static final String TAG = "Geocoder";

    private String mLanguage;
    private String mCountry;
    private String mVariant;
    private String mAppName;
    private ILocationManager mService;

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
        if (locale == null) {
            throw new NullPointerException("locale == null");
        }
        mLanguage = locale.getLanguage();
        mCountry = locale.getCountry();
        mVariant = locale.getVariant();
        mAppName = context.getPackageName();

        IBinder b = ServiceManager.getService(Context.LOCATION_SERVICE);
        mService = ILocationManager.Stub.asInterface(b);
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
     * Returns an array of Addresses that are known to describe the
     * area immediately surrounding the given latitude and longitude.
     * The returned addresses will be localized for the locale
     * provided to this class's constructor.
     *
     * <p> The returned values may be obtained by means of a network lookup.
     * The results are a best guess and are not guaranteed to be meaningful or
     * correct. It may be useful to call this method from a thread separate from your
     * primary UI thread.
     *
     * @param latitude the latitude a point for the search
     * @param longitude the longitude a point for the search
     * @param maxResults max number of addresses to return. Smaller numbers (1 to 5) are recommended
     *
     * @return a list of Address objects or null if no matches were
     * found.
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
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude == " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude == " + longitude);
        }
        try {
            List<Address> results = new ArrayList<Address>();
            String ex =  mService.getFromLocation(latitude, longitude, maxResults,
                mLanguage, mCountry, mVariant, mAppName, results);
            if (ex != null) {
                throw new IOException(ex);
            } else {
                return results;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getFromLocation: got RemoteException", e);
            return null;
        }
    }

    /**
     * Returns an array of Addresses that are known to describe the
     * named location, which may be a place name such as "Dalvik,
     * Iceland", an address such as "1600 Amphitheatre Parkway,
     * Mountain View, CA", an airport code such as "SFO", etc..  The
     * returned addresses will be localized for the locale provided to
     * this class's constructor.
     *
     * <p> The query will block and returned values will be obtained by means of a network lookup.
     * The results are a best guess and are not guaranteed to be meaningful or
     * correct. It may be useful to call this method from a thread separate from your
     * primary UI thread.
     *
     * @param locationName a user-supplied description of a location
     * @param maxResults max number of results to return. Smaller numbers (1 to 5) are recommended
     *
     * @return a list of Address objects or null if no matches were found.
     *
     * @throws IllegalArgumentException if locationName is null
     * @throws IOException if the network is unavailable or any other
     * I/O problem occurs
     */
    public List<Address> getFromLocationName(String locationName, int maxResults) throws IOException {
        if (locationName == null) {
            throw new IllegalArgumentException("locationName == null");
        }
        try {
            List<Address> results = new ArrayList<Address>();
            String ex = mService.getFromLocationName(locationName,
                0, 0, 0, 0, maxResults, mLanguage, mCountry, mVariant, mAppName, results);
            if (ex != null) {
                throw new IOException(ex);
            } else {
                return results;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getFromLocationName: got RemoteException", e);
            return null;
        }
    }

    /**
     * Returns an array of Addresses that are known to describe the
     * named location, which may be a place name such as "Dalvik,
     * Iceland", an address such as "1600 Amphitheatre Parkway,
     * Mountain View, CA", an airport code such as "SFO", etc..  The
     * returned addresses will be localized for the locale provided to
     * this class's constructor.
     *
     * <p> You may specify a bounding box for the search results by including
     * the Latitude and Longitude of the Lower Left point and Upper Right
     * point of the box.
     *
     * <p> The query will block and returned values will be obtained by means of a network lookup.
     * The results are a best guess and are not guaranteed to be meaningful or
     * correct. It may be useful to call this method from a thread separate from your
     * primary UI thread.
     *
     * @param locationName a user-supplied description of a location
     * @param maxResults max number of addresses to return. Smaller numbers (1 to 5) are recommended
     * @param lowerLeftLatitude the latitude of the lower left corner of the bounding box
     * @param lowerLeftLongitude the longitude of the lower left corner of the bounding box
     * @param upperRightLatitude the latitude of the upper right corner of the bounding box
     * @param upperRightLongitude the longitude of the upper right corner of the bounding box
     *
     * @return a list of Address objects or null if no matches were found.
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
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude) throws IOException {
        if (locationName == null) {
            throw new IllegalArgumentException("locationName == null");
        }
        if (lowerLeftLatitude < -90.0 || lowerLeftLatitude > 90.0) {
            throw new IllegalArgumentException("lowerLeftLatitude == "
                + lowerLeftLatitude);
        }
        if (lowerLeftLongitude < -180.0 || lowerLeftLongitude > 180.0) {
            throw new IllegalArgumentException("lowerLeftLongitude == "
                + lowerLeftLongitude);
        }
        if (upperRightLatitude < -90.0 || upperRightLatitude > 90.0) {
            throw new IllegalArgumentException("upperRightLatitude == "
                + upperRightLatitude);
        }
        if (upperRightLongitude < -180.0 || upperRightLongitude > 180.0) {
            throw new IllegalArgumentException("upperRightLongitude == "
                + upperRightLongitude);
        }
        try {
            ArrayList<Address> result = new ArrayList<Address>();
            String ex =  mService.getFromLocationName(locationName,
                lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude,
                maxResults, mLanguage, mCountry, mVariant, mAppName, result);
            if (ex != null) {
                throw new IOException(ex);
            } else {
                return result;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getFromLocationName: got RemoteException", e);
            return null;
        }
    }
}
