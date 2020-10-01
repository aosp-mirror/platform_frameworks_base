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

package com.android.server.people.data;

import android.content.Context;
import android.location.Country;
import android.location.CountryDetector;

import java.util.Locale;

/** The utilities static methods for people service data package. */
class Utils {

    /**
     * @return The ISO 3166-1 two letters country code of the country the user is in.
     */
    static String getCurrentCountryIso(Context context) {
        String countryIso = null;
        CountryDetector detector = (CountryDetector) context.getSystemService(
                Context.COUNTRY_DETECTOR);
        if (detector != null) {
            Country country = detector.detectCountry();
            if (country != null) {
                countryIso = country.getCountryIso();
            }
        }
        if (countryIso == null) {
            countryIso = Locale.getDefault().getCountry();
        }
        return countryIso;
    }

    private Utils() {
    }
}
