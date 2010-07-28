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

package android.location;

import android.test.AndroidTestCase;

public class CountryTester extends AndroidTestCase {
    public void testCountryEquals() {
        Country countryA = new Country("US", Country.COUNTRY_SOURCE_NETWORK);
        Country countryB = new Country("US", Country.COUNTRY_SOURCE_LOCALE);
        Country countryC = new Country("CN", Country.COUNTRY_SOURCE_LOCALE);
        Country countryD = new Country("us", Country.COUNTRY_SOURCE_NETWORK);
        assertTrue(countryA.equalsIgnoreSource(countryB));
        assertFalse(countryA.equalsIgnoreSource(countryC));
        assertFalse(countryA.equals(countryC));
        assertTrue(countryA.equals(countryD));
        assertTrue(countryA.hashCode() == countryD.hashCode());
    }
}
