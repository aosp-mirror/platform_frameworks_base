/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi.rtt;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;

import java.lang.annotation.Retention;

/**
 * Civic Address key types used to define address elements.
 *
 * <p>These keys can be used with {@code ResponderLocation.toCivicLocationSparseArray()}
 * to look-up the corresponding string values.</p>
 */
public class CivicLocationKeys {

    /**
     * An enumeration of all civic location keys.
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef({LANGUAGE, STATE, COUNTY, CITY, BOROUGH, NEIGHBORHOOD, GROUP_OF_STREETS, PRD, POD, STS,
            HNO, HNS, LMK, LOC, NAM, POSTAL_CODE, BUILDING, APT, FLOOR, ROOM, TYPE_OF_PLACE, PCN,
            PO_BOX, ADDITIONAL_CODE, DESK, PRIMARY_ROAD_NAME, ROAD_SECTION, BRANCH_ROAD_NAME,
            SUBBRANCH_ROAD_NAME, STREET_NAME_PRE_MODIFIER, STREET_NAME_POST_MODIFIER, SCRIPT})
    public @interface CivicLocationKeysType {
    }

    /** Language key e.g. i-default. */
    public static final int LANGUAGE = 0;
    /** Category label A1 key e.g. California. */
    public static final int STATE = 1;
    /** Category label A2 key e.g. Marin. */
    public static final int COUNTY = 2;
    /** Category label A3 key e.g. San Francisco. */
    public static final int CITY = 3;
    /** Category label A4 key e.g. Westminster. */
    public static final int BOROUGH = 4;
    /** Category label A5 key e.g. Pacific Heights. */
    public static final int NEIGHBORHOOD = 5;
    /** Category label A6 key e.g. University District. */
    public static final int GROUP_OF_STREETS = 6;
    // 7 - 15 not defined
    /** Leading Street direction key e.g. N. */
    public static final int PRD = 16;
    /** Trailing street suffix key e.g. SW. */
    public static final int POD = 17;
    /** Street suffix or Type key e.g Ave, Platz. */
    public static final int STS = 18;
    /** House Number key e.g. 123. */
    public static final int HNO = 19;
    /** House number suffix key e.g. A, 1/2. */
    public static final int HNS = 20;
    /** Landmark or vanity address key e.g. Golden Gate Bridge. */
    public static final int LMK = 21;
    /** Additional Location info key e.g. South Wing. */
    public static final int LOC = 22;
    /** Name of residence key e.g. Joe's Barbershop. */
    public static final int NAM = 23;
    /** Postal or ZIP code key e.g. 10027-1234. */
    public static final int POSTAL_CODE = 24;
    /** Building key e.g. Lincoln Library. */
    public static final int BUILDING = 25;
    /** Apartment or suite key e.g. Apt 42. */
    public static final int APT = 26;
    /** Floor key e.g. 4. */
    public static final int FLOOR = 27;
    /** Room key e.g. 450F. */
    public static final int ROOM = 28;
    /** Type of place key e.g. office. */
    public static final int TYPE_OF_PLACE = 29;
    /** Postal community name key e.g. Leonia. */
    public static final int PCN = 30;
    /** Post Office Box key e.g. 12345. */
    public static final int PO_BOX = 31;
    /** Additional Code key e.g. 13203000003. */
    public static final int ADDITIONAL_CODE = 32;
    /** Seat, desk, pole, or cubical key e.g. WS 181. */
    public static final int DESK = 33;
    /** Primary road name key e.g. Shoreline. */
    public static final int PRIMARY_ROAD_NAME = 34;
    /** Road Section key e.g. 14. */
    public static final int ROAD_SECTION = 35;
    /** Branch Rd Name key e.g. Lane 7. */
    public static final int BRANCH_ROAD_NAME = 36;
    /** Subbranch Rd Name key e.g. Alley 8. */
    public static final int SUBBRANCH_ROAD_NAME = 37;
    /** Premodifier key e.g. Old. */
    public static final int STREET_NAME_PRE_MODIFIER = 38;
    /** Postmodifier key e.g. Service. */
    public static final int STREET_NAME_POST_MODIFIER = 39;
    /** Script key e.g. Latn. */
    public static final int SCRIPT = 128;

    /** private constructor */
    private CivicLocationKeys() {}
}

