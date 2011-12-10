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
 * limitations under the License
 */

package com.android.server.location;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.Geocoder;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This class is used to detect the country where the user is. The sources of
 * country are queried in order of reliability, like
 * <ul>
 * <li>Mobile network</li>
 * <li>Location</li>
 * <li>SIM's country</li>
 * <li>Phone's locale</li>
 * </ul>
 * <p>
 * Call the {@link #detectCountry()} to get the available country immediately.
 * <p>
 * To be notified of the future country change, using the
 * {@link #setCountryListener(CountryListener)}
 * <p>
 * Using the {@link #stop()} to stop listening to the country change.
 * <p>
 * The country information will be refreshed every
 * {@link #LOCATION_REFRESH_INTERVAL} once the location based country is used.
 *
 * @hide
 */
public class ComprehensiveCountryDetector extends CountryDetectorBase {

    private final static String TAG = "ComprehensiveCountryDetector";
    /* package */ static final boolean DEBUG = false;

    /**
     * The refresh interval when the location based country was used
     */
    private final static long LOCATION_REFRESH_INTERVAL = 1000 * 60 * 60 * 24; // 1 day

    protected CountryDetectorBase mLocationBasedCountryDetector;
    protected Timer mLocationRefreshTimer;

    private Country mCountry;
    private final TelephonyManager mTelephonyManager;
    private Country mCountryFromLocation;
    private boolean mStopped = false;

    private PhoneStateListener mPhoneStateListener;

    /**
     * The listener for receiving the notification from LocationBasedCountryDetector.
     */
    private CountryListener mLocationBasedCountryDetectionListener = new CountryListener() {
        @Override
        public void onCountryDetected(Country country) {
            if (DEBUG) Slog.d(TAG, "Country detected via LocationBasedCountryDetector");
            mCountryFromLocation = country;
            // Don't start the LocationBasedCountryDetector.
            detectCountry(true, false);
            stopLocationBasedDetector();
        }
    };

    public ComprehensiveCountryDetector(Context context) {
        super(context);
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public Country detectCountry() {
        // Don't start the LocationBasedCountryDetector if we have been stopped.
        return detectCountry(false, !mStopped);
    }

    @Override
    public void stop() {
        // Note: this method in this subclass called only by tests.
        Slog.i(TAG, "Stop the detector.");
        cancelLocationRefresh();
        removePhoneStateListener();
        stopLocationBasedDetector();
        mListener = null;
        mStopped = true;
    }

    /**
     * Get the country from different sources in order of the reliability.
     */
    private Country getCountry() {
        Country result = null;
        result = getNetworkBasedCountry();
        if (result == null) {
            result = getLastKnownLocationBasedCountry();
        }
        if (result == null) {
            result = getSimBasedCountry();
        }
        if (result == null) {
            result = getLocaleCountry();
        }
        return result;
    }

    private boolean isNetworkCountryCodeAvailable() {
        // On CDMA TelephonyManager.getNetworkCountryIso() just returns SIM country.  We don't want
        // to prioritize it over location based country, so ignore it.
        final int phoneType = mTelephonyManager.getPhoneType();
        if (DEBUG) Slog.v(TAG, "    phonetype=" + phoneType);
        return phoneType == TelephonyManager.PHONE_TYPE_GSM;
    }

    /**
     * @return the country from the mobile network.
     */
    protected Country getNetworkBasedCountry() {
        String countryIso = null;
        if (isNetworkCountryCodeAvailable()) {
            countryIso = mTelephonyManager.getNetworkCountryIso();
            if (!TextUtils.isEmpty(countryIso)) {
                return new Country(countryIso, Country.COUNTRY_SOURCE_NETWORK);
            }
        }
        return null;
    }

    /**
     * @return the cached location based country.
     */
    protected Country getLastKnownLocationBasedCountry() {
        return mCountryFromLocation;
    }

    /**
     * @return the country from SIM card
     */
    protected Country getSimBasedCountry() {
        String countryIso = null;
        countryIso = mTelephonyManager.getSimCountryIso();
        if (!TextUtils.isEmpty(countryIso)) {
            return new Country(countryIso, Country.COUNTRY_SOURCE_SIM);
        }
        return null;
    }

    /**
     * @return the country from the system's locale.
     */
    protected Country getLocaleCountry() {
        Locale defaultLocale = Locale.getDefault();
        if (defaultLocale != null) {
            return new Country(defaultLocale.getCountry(), Country.COUNTRY_SOURCE_LOCALE);
        } else {
            return null;
        }
    }

    /**
     * @param notifyChange indicates whether the listener should be notified the change of the
     * country
     * @param startLocationBasedDetection indicates whether the LocationBasedCountryDetector could
     * be started if the current country source is less reliable than the location.
     * @return the current available UserCountry
     */
    private Country detectCountry(boolean notifyChange, boolean startLocationBasedDetection) {
        Country country = getCountry();
        runAfterDetectionAsync(mCountry != null ? new Country(mCountry) : mCountry, country,
                notifyChange, startLocationBasedDetection);
        mCountry = country;
        return mCountry;
    }

    /**
     * Run the tasks in the service's thread.
     */
    protected void runAfterDetectionAsync(final Country country, final Country detectedCountry,
            final boolean notifyChange, final boolean startLocationBasedDetection) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                runAfterDetection(
                        country, detectedCountry, notifyChange, startLocationBasedDetection);
            }
        });
    }

    @Override
    public void setCountryListener(CountryListener listener) {
        CountryListener prevListener = mListener;
        mListener = listener;
        if (mListener == null) {
            // Stop listening all services
            removePhoneStateListener();
            stopLocationBasedDetector();
            cancelLocationRefresh();
        } else if (prevListener == null) {
            addPhoneStateListener();
            detectCountry(false, true);
        }
    }

    void runAfterDetection(final Country country, final Country detectedCountry,
            final boolean notifyChange, final boolean startLocationBasedDetection) {
        if (notifyChange) {
            notifyIfCountryChanged(country, detectedCountry);
        }
        if (DEBUG) {
            Slog.d(TAG, "startLocationBasedDetection=" + startLocationBasedDetection
                    + " detectCountry=" + (detectedCountry == null ? null :
                        "(source: " + detectedCountry.getSource()
                        + ", countryISO: " + detectedCountry.getCountryIso() + ")")
                    + " isAirplaneModeOff()=" + isAirplaneModeOff()
                    + " mListener=" + mListener
                    + " isGeoCoderImplemnted()=" + isGeoCoderImplemented());
        }

        if (startLocationBasedDetection && (detectedCountry == null
                || detectedCountry.getSource() > Country.COUNTRY_SOURCE_LOCATION)
                && isAirplaneModeOff() && mListener != null && isGeoCoderImplemented()) {
            if (DEBUG) Slog.d(TAG, "run startLocationBasedDetector()");
            // Start finding location when the source is less reliable than the
            // location and the airplane mode is off (as geocoder will not
            // work).
            // TODO : Shall we give up starting the detector within a
            // period of time?
            startLocationBasedDetector(mLocationBasedCountryDetectionListener);
        }
        if (detectedCountry == null
                || detectedCountry.getSource() >= Country.COUNTRY_SOURCE_LOCATION) {
            // Schedule the location refresh if the country source is
            // not more reliable than the location or no country is
            // found.
            // TODO: Listen to the preference change of GPS, Wifi etc,
            // and start detecting the country.
            scheduleLocationRefresh();
        } else {
            // Cancel the location refresh once the current source is
            // more reliable than the location.
            cancelLocationRefresh();
            stopLocationBasedDetector();
        }
    }

    /**
     * Find the country from LocationProvider.
     */
    private synchronized void startLocationBasedDetector(CountryListener listener) {
        if (mLocationBasedCountryDetector != null) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "starts LocationBasedDetector to detect Country code via Location info "
                    + "(e.g. GPS)");
        }
        mLocationBasedCountryDetector = createLocationBasedCountryDetector();
        mLocationBasedCountryDetector.setCountryListener(listener);
        mLocationBasedCountryDetector.detectCountry();
    }

    private synchronized void stopLocationBasedDetector() {
        if (DEBUG) {
            Slog.d(TAG, "tries to stop LocationBasedDetector "
                    + "(current detector: " + mLocationBasedCountryDetector + ")");
        }
        if (mLocationBasedCountryDetector != null) {
            mLocationBasedCountryDetector.stop();
            mLocationBasedCountryDetector = null;
        }
    }

    protected CountryDetectorBase createLocationBasedCountryDetector() {
        return new LocationBasedCountryDetector(mContext);
    }

    protected boolean isAirplaneModeOff() {
        return Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) == 0;
    }

    /**
     * Notify the country change.
     */
    private void notifyIfCountryChanged(final Country country, final Country detectedCountry) {
        if (detectedCountry != null && mListener != null
                && (country == null || !country.equals(detectedCountry))) {
            Slog.d(TAG,
                    "The country was changed from " + country != null ? country.getCountryIso() :
                        country + " to " + detectedCountry.getCountryIso());
            notifyListener(detectedCountry);
        }
    }

    /**
     * Schedule the next location refresh. We will do nothing if the scheduled task exists.
     */
    private synchronized void scheduleLocationRefresh() {
        if (mLocationRefreshTimer != null) return;
        if (DEBUG) {
            Slog.d(TAG, "start periodic location refresh timer. Interval: "
                    + LOCATION_REFRESH_INTERVAL);
        }
        mLocationRefreshTimer = new Timer();
        mLocationRefreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (DEBUG) {
                    Slog.d(TAG, "periodic location refresh event. Starts detecting Country code");
                }
                mLocationRefreshTimer = null;
                detectCountry(false, true);
            }
        }, LOCATION_REFRESH_INTERVAL);
    }

    /**
     * Cancel the scheduled refresh task if it exists
     */
    private synchronized void cancelLocationRefresh() {
        if (mLocationRefreshTimer != null) {
            mLocationRefreshTimer.cancel();
            mLocationRefreshTimer = null;
        }
    }

    protected synchronized void addPhoneStateListener() {
        if (mPhoneStateListener == null) {
            mPhoneStateListener = new PhoneStateListener() {
                @Override
                public void onServiceStateChanged(ServiceState serviceState) {
                    if (!isNetworkCountryCodeAvailable()) {
                        return;
                    }
                    if (DEBUG) Slog.d(TAG, "onServiceStateChanged: " + serviceState.getState());

                    detectCountry(true, true);
                }
            };
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        }
    }

    protected synchronized void removePhoneStateListener() {
        if (mPhoneStateListener != null) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mPhoneStateListener = null;
        }
    }

    protected boolean isGeoCoderImplemented() {
        return Geocoder.isPresent();
    }
}
