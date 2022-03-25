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

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A class for handling geocoding and reverse geocoding. Geocoding is the process of transforming a
 * street address or other description of a location into a (latitude, longitude) coordinate.
 * Reverse geocoding is the process of transforming a (latitude, longitude) coordinate into a
 * (partial) address. The amount of detail in a reverse geocoded location description may vary, for
 * example one might contain the full street address of the closest building, while another might
 * contain only a city name and postal code.
 *
 * The Geocoder class requires a backend service that is not included in the core android framework.
 * The Geocoder query methods will return an empty list if there no backend service in the platform.
 * Use the isPresent() method to determine whether a Geocoder implementation exists.
 *
 * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
 * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful or
 * correct. Do not use this API for any safety-critical or regulatory compliance purpose.
 */
public final class Geocoder {

    /** A listener for asynchronous geocoding results. */
    public interface GeocodeListener {
        /** Invoked when geocoding completes successfully. May return an empty list. */
        void onGeocode(@NonNull List<Address> addresses);
        /** Invoked when geocoding fails, with a brief error message. */
        default void onError(@Nullable String errorMessage) {}
    }

    private static final long TIMEOUT_MS = 60000;

    private final GeocoderParams mParams;
    private final ILocationManager mService;

    /**
     * Returns true if there is a geocoder implementation present that may return results. If true,
     * there is still no guarantee that any individual geocoding attempt will succeed.
     */
    public static boolean isPresent() {
        ILocationManager lm = Objects.requireNonNull(ILocationManager.Stub.asInterface(
                ServiceManager.getService(Context.LOCATION_SERVICE)));
        try {
            return lm.geocoderIsPresent();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Constructs a Geocoder localized for the default locale.
     */
    public Geocoder(@NonNull Context context) {
        this(context, Locale.getDefault());
    }

    /**
     * Constructs a Geocoder localized for the given locale.
     */
    public Geocoder(@NonNull Context context, @NonNull Locale locale) {
        mParams = new GeocoderParams(context, locale);
        mService = ILocationManager.Stub.asInterface(
                ServiceManager.getService(Context.LOCATION_SERVICE));
    }

    /**
     * Returns an array of Addresses that attempt to describe the area immediately surrounding the
     * given latitude and longitude. The returned addresses should be localized for the locale
     * provided to this class's constructor.
     *
     * <p class="warning"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.</p>
     *
     * <p class="warning"><strong>Warning:</strong> This API may hit the network, and may block for
     * excessive amounts of time, up to 60 seconds or more. It's strongly encouraged to use the
     * asynchronous version of this API. If that is not possible, this should be run on a background
     * thread to avoid blocking other operations.</p>
     *
     * @param latitude the latitude a point for the search
     * @param longitude the longitude a point for the search
     * @param maxResults max number of addresses to return. Smaller numbers (1 to 5) are recommended
     *
     * @return a list of Address objects. Returns null or empty list if no matches were
     * found or there is no backend service available.
     *
     * @throws IllegalArgumentException if latitude or longitude is invalid
     * @throws IOException if there is a failure
     *
     * @deprecated Use {@link #getFromLocation(double, double, int, GeocodeListener)} instead to
     * avoid blocking a thread waiting for results.
     */
    @Deprecated
    public @Nullable List<Address> getFromLocation(
            @FloatRange(from = -90D, to = 90D) double latitude,
            @FloatRange(from = -180D, to = 180D)double longitude,
            @IntRange int maxResults)
            throws IOException {
        SynchronousGeocoder listener = new SynchronousGeocoder();
        getFromLocation(latitude, longitude, maxResults, listener);
        return listener.getResults();
    }

    /**
     * Provides an array of Addresses that attempt to describe the area immediately surrounding the
     * given latitude and longitude. The returned addresses should be localized for the locale
     * provided to this class's constructor.
     *
     * <p class="warning"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.</p>
     *
     * @param latitude the latitude a point for the search
     * @param longitude the longitude a point for the search
     * @param maxResults max number of addresses to return. Smaller numbers (1 to 5) are recommended
     * @param listener a listener for receiving results
     *
     * @throws IllegalArgumentException if latitude or longitude is invalid
     */
    public void getFromLocation(
            @FloatRange(from = -90D, to = 90D) double latitude,
            @FloatRange(from = -180D, to = 180D) double longitude,
            @IntRange int maxResults,
            @NonNull GeocodeListener listener) {
        Preconditions.checkArgumentInRange(latitude, -90.0, 90.0, "latitude");
        Preconditions.checkArgumentInRange(longitude, -180.0, 180.0, "longitude");

        try {
            mService.getFromLocation(latitude, longitude, maxResults, mParams,
                    new GeocoderImpl(listener));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an array of Addresses that attempt to describe the named location, which may be a
     * place name such as "Dalvik, Iceland", an address such as "1600 Amphitheatre Parkway, Mountain
     * View, CA", an airport code such as "SFO", and so forth. The returned addresses should be
     * localized for the locale provided to this class's constructor.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.</p>
     *
     * <p class="warning"><strong>Warning:</strong> This API may hit the network, and may block for
     * excessive amounts of time, up to 60 seconds or more. It's strongly encouraged to use the
     * asynchronous version of this API. If that is not possible, this should be run on a background
     * thread to avoid blocking other operations.</p>
     *
     * @param locationName a user-supplied description of a location
     * @param maxResults max number of results to return. Smaller numbers (1 to 5) are recommended
     *
     * @return a list of Address objects. Returns null or empty list if no matches were
     * found or there is no backend service available.
     *
     * @throws IllegalArgumentException if locationName is null
     * @throws IOException if there is a failure
     *
     * @deprecated Use {@link #getFromLocationName(String, int, GeocodeListener)} instead to avoid
     * blocking a thread waiting for results.
     */
    @Deprecated
    public @Nullable List<Address> getFromLocationName(
            @NonNull String locationName,
            @IntRange int maxResults) throws IOException {
        return getFromLocationName(locationName, maxResults, 0, 0, 0, 0);
    }

    /**
     * Provides an array of Addresses that attempt to describe the named location, which may be a
     * place name such as "Dalvik, Iceland", an address such as "1600 Amphitheatre Parkway, Mountain
     * View, CA", an airport code such as "SFO", and so forth. The returned addresses should be
     * localized for the locale provided to this class's constructor.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.</p>
     *
     * @param locationName a user-supplied description of a location
     * @param maxResults max number of results to return. Smaller numbers (1 to 5) are recommended
     * @param listener a listener for receiving results
     *
     * @throws IllegalArgumentException if locationName is null
     */
    public void getFromLocationName(
            @NonNull String locationName,
            @IntRange int maxResults,
            @NonNull GeocodeListener listener) {
        getFromLocationName(locationName, maxResults, 0, 0, 0, 0, listener);
    }

    /**
     * Returns an array of Addresses that attempt to describe the named location, which may be a
     * place name such as "Dalvik, Iceland", an address such as "1600 Amphitheatre Parkway, Mountain
     * View, CA", an airport code such as "SFO", and so forth. The returned addresses should be
     * localized for the locale provided to this class's constructor.
     *
     * <p> You may specify a bounding box for the search results by including the latitude and
     * longitude of the lower left point and upper right point of the box.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.</p>
     *
     * <p class="warning"><strong>Warning:</strong> This API may hit the network, and may block for
     * excessive amounts of time, up to 60 seconds or more. It's strongly encouraged to use the
     * asynchronous version of this API. If that is not possible, this should be run on a background
     * thread to avoid blocking other operations.</p>
     *
     * @param locationName        a user-supplied description of a location
     * @param maxResults          max number of addresses to return. Smaller numbers (1 to 5) are
     *                            recommended
     * @param lowerLeftLatitude   the latitude of the lower left corner of the bounding box
     * @param lowerLeftLongitude  the longitude of the lower left corner of the bounding box
     * @param upperRightLatitude  the latitude of the upper right corner of the bounding box
     * @param upperRightLongitude the longitude of the upper right corner of the bounding box
     *
     * @return a list of Address objects. Returns null or empty list if no matches were
     * found or there is no backend service available.
     *
     * @throws IllegalArgumentException if locationName is null
     * @throws IllegalArgumentException if any latitude or longitude is invalid
     * @throws IOException              if there is a failure
     *
     * @deprecated Use {@link #getFromLocationName(String, int, double, double, double, double,
     * GeocodeListener)} instead to avoid blocking a thread waiting for results.
     */
    @Deprecated
    public @Nullable List<Address> getFromLocationName(
            @NonNull String locationName,
            @IntRange int maxResults,
            @FloatRange(from = -90D, to = 90D) double lowerLeftLatitude,
            @FloatRange(from = -180D, to = 180D) double lowerLeftLongitude,
            @FloatRange(from = -90D, to = 90D) double upperRightLatitude,
            @FloatRange(from = -180D, to = 180D) double upperRightLongitude) throws IOException {
        SynchronousGeocoder listener = new SynchronousGeocoder();
        getFromLocationName(locationName, maxResults, lowerLeftLatitude, lowerLeftLongitude,
                upperRightLatitude, upperRightLongitude, listener);
        return listener.getResults();
    }

    /**
     * Returns an array of Addresses that attempt to describe the named location, which may be a
     * place name such as "Dalvik, Iceland", an address such as "1600 Amphitheatre Parkway, Mountain
     * View, CA", an airport code such as "SFO", and so forth. The returned addresses should be
     * localized for the locale provided to this class's constructor.
     *
     * <p> You may specify a bounding box for the search results by including the latitude and
     * longitude of the lower left point and upper right point of the box.
     *
     * <p class="note"><strong>Warning:</strong> Geocoding services may provide no guarantees on
     * availability or accuracy. Results are a best guess, and are not guaranteed to be meaningful
     * or correct. Do <b>NOT</b> use this API for any safety-critical or regulatory compliance
     * purposes.</p>
     *
     * @param locationName        a user-supplied description of a location
     * @param maxResults          max number of addresses to return. Smaller numbers (1 to 5) are
     *                            recommended
     * @param lowerLeftLatitude   the latitude of the lower left corner of the bounding box
     * @param lowerLeftLongitude  the longitude of the lower left corner of the bounding box
     * @param upperRightLatitude  the latitude of the upper right corner of the bounding box
     * @param upperRightLongitude the longitude of the upper right corner of the bounding box
     * @param listener            a listener for receiving results
     *
     * @throws IllegalArgumentException if locationName is null
     * @throws IllegalArgumentException if any latitude or longitude is invalid
     */
    public void getFromLocationName(
            @NonNull String locationName,
            @IntRange int maxResults,
            @FloatRange(from = -90D, to = 90D) double lowerLeftLatitude,
            @FloatRange(from = -180D, to = 180D) double lowerLeftLongitude,
            @FloatRange(from = -90D, to = 90D) double upperRightLatitude,
            @FloatRange(from = -180D, to = 180D) double upperRightLongitude,
            @NonNull GeocodeListener listener) {
        Preconditions.checkArgument(locationName != null);
        Preconditions.checkArgumentInRange(lowerLeftLatitude, -90.0, 90.0, "lowerLeftLatitude");
        Preconditions.checkArgumentInRange(lowerLeftLongitude, -180.0, 180.0, "lowerLeftLongitude");
        Preconditions.checkArgumentInRange(upperRightLatitude, -90.0, 90.0, "upperRightLatitude");
        Preconditions.checkArgumentInRange(upperRightLongitude, -180.0, 180.0,
                "upperRightLongitude");

        try {
            mService.getFromLocationName(locationName, lowerLeftLatitude, lowerLeftLongitude,
                    upperRightLatitude, upperRightLongitude, maxResults, mParams,
                    new GeocoderImpl(listener));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class GeocoderImpl extends IGeocodeListener.Stub {

        private GeocodeListener mListener;

        GeocoderImpl(GeocodeListener listener) {
            mListener = Objects.requireNonNull(listener);
        }

        @Override
        public void onResults(String error, List<Address> addresses) throws RemoteException {
            if (mListener == null) {
                return;
            }

            GeocodeListener listener = mListener;
            mListener = null;

            if (error != null) {
                listener.onError(error);
            } else {
                if (addresses == null) {
                    addresses = Collections.emptyList();
                }
                listener.onGeocode(addresses);
            }
        }
    }

    private static class SynchronousGeocoder implements GeocodeListener {
        private final CountDownLatch mLatch = new CountDownLatch(1);

        private String mError = null;
        private List<Address> mResults = Collections.emptyList();

        SynchronousGeocoder() {}

        @Override
        public void onGeocode(List<Address> addresses) {
            mResults = addresses;
            mLatch.countDown();
        }

        @Override
        public void onError(String errorMessage) {
            mError = errorMessage;
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
