/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.countrydetector;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.os.Handler;

/**
 * This class defines the methods need to be implemented by the country
 * detector.
 * <p>
 * Calling {@link #detectCountry} to start detecting the country. The country
 * could be returned immediately if it is available.
 *
 * @hide
 */
public abstract class CountryDetectorBase {
    private static final String ATTRIBUTION_TAG = "CountryDetector";

    protected final Handler mHandler;
    protected final Context mContext;
    protected CountryListener mListener;
    protected Country mDetectedCountry;

    public CountryDetectorBase(Context context) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mHandler = new Handler();
    }

    /**
     * Start detecting the country that the user is in.
     *
     * @return the country if it is available immediately, otherwise null should
     * be returned.
     */
    public abstract Country detectCountry();

    /**
     * Register a listener to receive the notification when the country is detected or changed.
     * <p>
     * The previous listener will be replaced if it exists.
     */
    public void setCountryListener(CountryListener listener) {
        mListener = listener;
    }

    /**
     * Stop detecting the country. The detector should release all system services and be ready to
     * be freed
     */
    public abstract void stop();

    protected void notifyListener(Country country) {
        if (mListener != null) {
            mListener.onCountryDetected(country);
        }
    }
}
