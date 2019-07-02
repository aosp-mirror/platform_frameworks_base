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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.location.Address;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link CivicLocation}.
 */
@RunWith(JUnit4.class)
public class CivicLocationTest {
    private static final String sUsCountryCode = "US";

    private static final byte[] sEmptyBuffer = {};

    private static final byte[] sTestCivicLocationBuffer = {
            (byte) 17,
            (byte) 3,
            (byte) 'a',
            (byte) 'b',
            (byte) 'c',
            (byte) 4,
            (byte) 4,
            (byte) 'd',
            (byte) 'e',
            (byte) 'f',
            (byte) 'g',
            (byte) 12,
            (byte) 1,
            (byte) 'h'
    };

    private static final byte[] sTestCivicLocationBufferWithAddress = {
            (byte) CivicLocationKeys.HNO,
            (byte) 2,
            (byte) '1',
            (byte) '5',
            (byte) CivicLocationKeys.PRIMARY_ROAD_NAME,
            (byte) 4,
            (byte) 'A',
            (byte) 'l',
            (byte) 't',
            (byte) 'o',
            (byte) CivicLocationKeys.STREET_NAME_POST_MODIFIER,
            (byte) 4,
            (byte) 'R',
            (byte) 'o',
            (byte) 'a',
            (byte) 'd',
            (byte) CivicLocationKeys.CITY,
            (byte) 8,
            (byte) 'M',
            (byte) 't',
            (byte) 'n',
            (byte) ' ',
            (byte) 'V',
            (byte) 'i',
            (byte) 'e',
            (byte) 'w',
            (byte) CivicLocationKeys.STATE,
            (byte) 2,
            (byte) 'C',
            (byte) 'A',
            (byte) CivicLocationKeys.POSTAL_CODE,
            (byte) 5,
            (byte) '9',
            (byte) '4',
            (byte) '0',
            (byte) '4',
            (byte) '3'
    };

    /**
     * Test inValid for null CountryCode.
     */
    @Test
    public void testCivicLocationNullCountryCode() {
        CivicLocation civicLocation = new CivicLocation(sTestCivicLocationBuffer, null);

        boolean valid = civicLocation.isValid();

        assertFalse(valid);
    }

    /**
     * Test inValid for CountryCode too short.
     */
    @Test
    public void testCivicLocationCountryCodeTooShort() {
        CivicLocation civicLocation = new CivicLocation(sTestCivicLocationBuffer, "X");

        boolean valid = civicLocation.isValid();

        assertFalse(valid);
    }

    /**
     * Test inValid for CountryCode too long.
     */
    @Test
    public void testCivicLocationCountryCodeTooLong() {
        CivicLocation civicLocation = new CivicLocation(sTestCivicLocationBuffer, "XYZ");

        boolean valid = civicLocation.isValid();

        assertFalse(valid);
    }

    /**
     * Test inValid for null CivicLocation Buffer
     */
    @Test
    public void testCivicLocationNullBuffer() {
        CivicLocation civicLocation = new CivicLocation(null, sUsCountryCode);

        boolean valid = civicLocation.isValid();

        assertFalse(valid);
    }

    /**
     * Test inValid for Empty CivicLocation Buffer.
     */
    @Test
    public void testCivicLocationEmptyBuffer() {
        CivicLocation civicLocation = new CivicLocation(sEmptyBuffer, sUsCountryCode);

        boolean valid = civicLocation.isValid();

        assertFalse(valid);
    }

    /**
     * Test for valid CivicLocationBuffer and Country Code.
     */
    @Test
    public void testCivicLocationValid() {
        CivicLocation civicLocation = new CivicLocation(sTestCivicLocationBuffer, sUsCountryCode);

        boolean valid = civicLocation.isValid();

        assertTrue(valid);
    }

    /**
     * Test toString Representation
     */
    @Test
    public void testCivicLocationToString() {
        CivicLocation civicLocation = new CivicLocation(sTestCivicLocationBuffer, sUsCountryCode);

        String str = civicLocation.toString();

        assertEquals("{4=defg, 12=h, 17=abc}", str);
    }

    /**
     * Test the toString
     */
    @Test
    public void testCivicLocationgetElementValue() {
        CivicLocation civicLocation = new CivicLocation(sTestCivicLocationBuffer, sUsCountryCode);

        String value1 = civicLocation.getCivicElementValue(4);
        String value2 = civicLocation.getCivicElementValue(17);
        String value3 = civicLocation.getCivicElementValue(12);
        String value4 = civicLocation.getCivicElementValue(156); // not in test data
        String value5 = civicLocation.getCivicElementValue(276); // greater than key index

        assertEquals("defg", value1);
        assertEquals("abc", value2);
        assertEquals("h", value3);
        assertNull(value4);
        assertNull(value5);
    }

    /* Test toAddress representation */
    @Test
    public void testCivicLocationToAddress() {
        CivicLocation civicLocation =
                new CivicLocation(sTestCivicLocationBufferWithAddress, sUsCountryCode);

        Address address = civicLocation.toAddress();

        assertEquals("", address.getAddressLine(0));
        assertEquals("15 Alto", address.getAddressLine(1));
        assertEquals("Mtn View", address.getAddressLine(2));
        assertEquals("CA 94043", address.getAddressLine(3));
        assertEquals("US", address.getAddressLine(4));
    }

    /**
     * Test toString Representation
     */
    @Test
    public void testCivicLocationToString2() {
        CivicLocation civicLocation =
                new CivicLocation(sTestCivicLocationBufferWithAddress, sUsCountryCode);

        String str = civicLocation.toString();

        assertEquals("{1=CA, 3=Mtn View, 19=15, 24=94043, 34=Alto, 39=Road}", str);
    }

    /** Test object is Parcellable */
    @Test
    public void testCivicLocationParcelable() {
        CivicLocation civicLocation =
                new CivicLocation(sTestCivicLocationBufferWithAddress, sUsCountryCode);

        Parcel parcel = Parcel.obtain();
        civicLocation.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        CivicLocation civicLocationFromParcel =
                CivicLocation.CREATOR.createFromParcel(parcel);

        assertEquals(civicLocationFromParcel, civicLocation);
    }
}
