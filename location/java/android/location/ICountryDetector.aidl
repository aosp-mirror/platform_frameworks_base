/*
 * Copyright (C) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

import android.location.Country;
import android.location.ICountryListener;

/**
 * The API for detecting the country where the user is.
 *
 * {@hide}
 */
interface ICountryDetector
{
    /**
     * Start detecting the country that the user is in.
     * @return the country if it is available immediately, otherwise null will be returned.
     */
    Country detectCountry();

    /**
     * Add a listener to receive the notification when the country is detected or changed.
     */
    void addCountryListener(in ICountryListener listener);

    /**
     * Remove the listener
     */
    void removeCountryListener(in ICountryListener listener);
}