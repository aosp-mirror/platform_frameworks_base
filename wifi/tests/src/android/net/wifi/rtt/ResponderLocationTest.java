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

import android.location.Address;
import android.location.Location;
import android.net.MacAddress;
import android.os.Parcel;
import android.webkit.MimeTypeMap;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

/**
 * Tests for {@link ResponderLocation}.
 */
@RunWith(JUnit4.class)
public class ResponderLocationTest {
    private static final double LATLNG_TOLERANCE_DEGREES = 0.00001;
    private static final double ALT_TOLERANCE_METERS = 0.01;
    private static final double HEIGHT_TOLERANCE_METERS = 0.01;
    private static final int INDEX_ELEMENT_TYPE = 2;
    private static final int INDEX_SUBELEMENT_TYPE = 0;
    private static final int INDEX_SUBELEMENT_LENGTH = 1;

    /* Test Buffers */

    private static final byte[] sTestLciIeHeader = {
            (byte) 0x01, (byte) 0x00, (byte) 0x08 // LCI Information Element (IE)
    };

    private static final byte[] sTestLciShortBuffer = {
        (byte) 0x00
    };

    private static final byte[] sTestLciSE = {
            (byte) 0x00, // Subelement LCI
            (byte) 16,   // Subelement LCI length always = 16
            (byte) 0x52,
            (byte) 0x83,
            (byte) 0x4d,
            (byte) 0x12,
            (byte) 0xef,
            (byte) 0xd2,
            (byte) 0xb0,
            (byte) 0x8b,
            (byte) 0x9b,
            (byte) 0x4b,
            (byte) 0xf1,
            (byte) 0xcc,
            (byte) 0x2c,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x41
    };

    private static final byte[] sTestZHeightSE = {
            (byte) 0x04, // Subelement Z
            (byte) 6, // Length always 6
            (byte) 0x00, // LSB STA Floor Info (2 bytes)
            (byte) 0x01, // MSB
            (byte) 0xcd, // LSB Height(m) (3 bytes)
            (byte) 0x2c,
            (byte) 0x00, // MSB Height(m)
            (byte) 0x0e, // STA Height Uncertainty
    };

    private static final byte[] sTestUsageSE1 = {
            (byte) 0x06, // Subelement Usage Rights
            (byte) 1, // Length 1 (with no retention limit)
            (byte) 0x01, // Retransmit ok, No expiration, no extra info available
    };

    private static final byte[] sTestUsageSE2 = {
            (byte) 0x06, // Subelement Usage Rights
            (byte) 3,    // Length 3 (including retention limit)
            (byte) 0x06, // Retransmit not ok, Expiration, extra info available
            (byte) 0x00, // LSB expiration time  (0x8000 = 32768 hrs)
            (byte) 0x80  // MSB expiration time
    };

    private static final byte[] sTestBssidListSE = {
            (byte) 0x07, // Subelement BSSID list
            (byte) 13, // length dependent on number of BSSIDs in list
            (byte) 0x02, // Number of BSSIDs in list
            (byte) 0x01, // BSSID #1 (MSB)
            (byte) 0x02,
            (byte) 0x03,
            (byte) 0x04,
            (byte) 0x05,
            (byte) 0x06, // (LSB)
            (byte) 0xf1, // BSSID #2 (MSB)
            (byte) 0xf2,
            (byte) 0xf3,
            (byte) 0xf4,
            (byte) 0xf5,
            (byte) 0xf6 // (LSB)
    };

    private static final byte[] sTestLcrBufferHeader = {
            (byte) 0x01, (byte) 0x00, (byte) 0x0b,
    };

    private static final byte[] sEmptyBuffer = {};

    private static final byte[] sTestCivicLocationSEWithAddress = {
            (byte) 0, // Civic Location Subelement
            (byte) 39, // Length of subelement value
            (byte) 'U', // CountryCodeChar1
            (byte) 'S', // CountryCodeChar2
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

    // Buffer representing: "https://map.com/mall.jpg"
    private static final byte[] sTestMapUrlSE = {
            (byte) 5, // Map URL Subelement
            (byte) 25,
            (byte) 0, // MAP_TYPE_URL_DEFINED
            (byte) 'h',
            (byte) 't',
            (byte) 't',
            (byte) 'p',
            (byte) 's',
            (byte) ':',
            (byte) '/',
            (byte) '/',
            (byte) 'm',
            (byte) 'a',
            (byte) 'p',
            (byte) '.',
            (byte) 'c',
            (byte) 'o',
            (byte) 'm',
            (byte) '/',
            (byte) 'm',
            (byte) 'a',
            (byte) 'l',
            (byte) 'l',
            (byte) '.',
            (byte) 'j',
            (byte) 'p',
            (byte) 'g'
    };

    /**
     * Test if the lci and lcr buffers are null.
     */
    @Test
    public void testIfLciOrLcrIsNull() {
        ResponderLocation responderLocation = new ResponderLocation(null, null);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test if the lci and lcr buffers are empty.
     */
    @Test
    public void testIfLciOrLcrIsEmpty() {
        ResponderLocation responderLocation = new ResponderLocation(sEmptyBuffer, sEmptyBuffer);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test if the lci subelement only has one byte
     */
    @Test
    public void testIfLciShortBuffer() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciShortBuffer);
        ResponderLocation responderLocation =
                new ResponderLocation(testLciBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test that the example buffer contains a valid LCI Subelement.
     */
    @Test
    public void testLciValidSubelement() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        ResponderLocation responderLocation =
                new ResponderLocation(testLciBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();
        Location location = responderLocation.toLocation();

        assertTrue(valid);
        assertTrue(lciValid);
        assertFalse(zValid);
        assertEquals(0.0009765625, responderLocation.getLatitudeUncertainty());
        assertEquals(-33.857009, responderLocation.getLatitude(),
                LATLNG_TOLERANCE_DEGREES);
        assertEquals(0.0009765625, responderLocation.getLongitudeUncertainty());
        assertEquals(151.215200, responderLocation.getLongitude(),
                LATLNG_TOLERANCE_DEGREES);
        assertEquals(1, responderLocation.getAltitudeType());
        assertEquals(64.0, responderLocation.getAltitudeUncertainty());
        assertEquals(11.2, responderLocation.getAltitude(), ALT_TOLERANCE_METERS);
        assertEquals(1, responderLocation.getDatum()); // WGS84
        assertEquals(false, responderLocation.getRegisteredLocationAgreementIndication());
        assertEquals(false, responderLocation.getRegisteredLocationDseIndication());
        assertEquals(false, responderLocation.getDependentStationIndication());
        assertEquals(1, responderLocation.getLciVersion());

        // Testing Location Object
        assertEquals(-33.857009, location.getLatitude(),
                LATLNG_TOLERANCE_DEGREES);
        assertEquals(151.215200, location.getLongitude(),
                LATLNG_TOLERANCE_DEGREES);
        assertEquals((0.0009765625 + 0.0009765625) / 2, location.getAccuracy(),
                LATLNG_TOLERANCE_DEGREES);
        assertEquals(11.2, location.getAltitude(), ALT_TOLERANCE_METERS);
        assertEquals(64.0, location.getVerticalAccuracyMeters(), ALT_TOLERANCE_METERS);
    }

    /**
     * Test for an invalid LCI element.
     */
    @Test
    public void testLciInvalidElement() {
        byte[] testBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        testBuffer[INDEX_ELEMENT_TYPE] = (byte) 0xFF;
        ResponderLocation responderLocation =
                new ResponderLocation(testBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test for an invalid subelement type.
     */
    @Test
    public void testSkipLciSubElementUnusedOrUnknown() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        // Corrupt the subelement type to an unknown type.
        testLciBuffer[sTestLciIeHeader.length + INDEX_SUBELEMENT_TYPE] = (byte) 0x77;
        ResponderLocation responderLocation =
                new ResponderLocation(testLciBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test for a subelement LCI length too small.
     */
    @Test
    public void testInvalidLciSubElementLengthTooSmall() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        // Corrupt the length making it too small.
        testLciBuffer[sTestLciIeHeader.length + INDEX_SUBELEMENT_LENGTH] = (byte) 0x01;
        ResponderLocation responderLocation =
                new ResponderLocation(testLciBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test for a subelement LCI length too big.
     */
    @Test
    public void testInvalidLciSubElementLengthTooBig() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        // Corrupt the length making it too big.
        testLciBuffer[sTestLciIeHeader.length + INDEX_SUBELEMENT_TYPE] = (byte) 0x11;
        ResponderLocation responderLocation =
                new ResponderLocation(testLciBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean lciValid = responderLocation.isLciSubelementValid();
        boolean zValid = responderLocation.isZaxisSubelementValid();

        assertFalse(valid);
        assertFalse(lciValid);
        assertFalse(zValid);
    }

    /**
     * Test for a valid Z (Height) subelement following an LCI subelement.
     */
    @Test
    public void testLciValidZBufferSEAfterLci() {
        byte[] testBufferTmp = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        byte[] testBuffer = concatenateArrays(testBufferTmp, sTestZHeightSE);
        ResponderLocation responderLocation =
                new ResponderLocation(testBuffer, sTestLcrBufferHeader);

        boolean isValid = responderLocation.isValid();
        boolean isZValid = responderLocation.isZaxisSubelementValid();
        boolean isLciValid = responderLocation.isLciSubelementValid();
        double staFloorNumber = responderLocation.getFloorNumber();
        double staHeightAboveFloorMeters = responderLocation.getHeightAboveFloorMeters();
        double staHeightAboveFloorUncertaintyMeters =
                responderLocation.getHeightAboveFloorUncertaintyMeters();

        assertTrue(isValid);
        assertTrue(isZValid);
        assertTrue(isLciValid);
        assertEquals(4.0, staFloorNumber);
        assertEquals(2.8, staHeightAboveFloorMeters, HEIGHT_TOLERANCE_METERS);
        assertEquals(0.125, staHeightAboveFloorUncertaintyMeters);
    }

    /**
     * Test for a valid Usage Policy that is unrestrictive
     */
    @Test
    public void testLciOpenUsagePolicy() {
        byte[] testBufferTmp = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        byte[] testBuffer = concatenateArrays(testBufferTmp, sTestUsageSE1);
        ResponderLocation responderLocation =
                new ResponderLocation(testBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean retransmit = responderLocation.getRetransmitPolicyIndication();
        boolean expiration = responderLocation.getRetentionExpiresIndication();
        boolean extraInfo = responderLocation.getExtraInfoOnAssociationIndication();

        assertTrue(valid);
        assertTrue(retransmit);
        assertFalse(expiration);
        assertFalse(extraInfo);
    }

    /**
     * Test for a valid Usage Policy that is restrictive
     */
    @Test
    public void testLciRestrictiveUsagePolicy() {
        byte[] testBufferTmp = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        byte[] testBuffer = concatenateArrays(testBufferTmp, sTestUsageSE2);
        ResponderLocation responderLocation =
                new ResponderLocation(testBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        boolean retransmit = responderLocation.getRetransmitPolicyIndication();
        boolean expiration = responderLocation.getRetentionExpiresIndication();
        boolean extraInfo = responderLocation.getExtraInfoOnAssociationIndication();

        assertFalse(valid);
        assertFalse(retransmit);
        assertTrue(expiration);
        assertTrue(extraInfo);
    }

    /**
     * Test for a valid BSSID element following an LCI subelement.
     */
    @Test
    public void testLciBssidListSEAfterLci() {
        byte[] testBufferTmp = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        byte[] testBuffer = concatenateArrays(testBufferTmp, sTestBssidListSE);
        ResponderLocation responderLocation =
                new ResponderLocation(testBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        List<MacAddress> bssidList = responderLocation.getColocatedBssids();

        assertTrue(valid);
        assertEquals(2, bssidList.size());
        MacAddress macAddress1 = bssidList.get(0);
        assertEquals("01:02:03:04:05:06", macAddress1.toString());
        MacAddress macAddress2 = bssidList.get(1);
        assertEquals("f1:f2:f3:f4:f5:f6", macAddress2.toString());
    }

    /**
     * Test for a valid BSSID element before and LCI element
     */
    @Test
    public void testLciBssidListSEBeforeLci() {
        byte[] testBufferTmp = concatenateArrays(sTestLciIeHeader, sTestBssidListSE);
        byte[] testBuffer = concatenateArrays(testBufferTmp, sTestLciSE);
        ResponderLocation responderLocation =
                new ResponderLocation(testBuffer, sTestLcrBufferHeader);

        boolean valid = responderLocation.isValid();
        List<MacAddress> bssidList = responderLocation.getColocatedBssids();

        assertTrue(valid);
        assertEquals(2, bssidList.size());
        MacAddress macAddress1 = bssidList.get(0);
        assertEquals("01:02:03:04:05:06", macAddress1.toString());
        MacAddress macAddress2 = bssidList.get(1);
        assertEquals("f1:f2:f3:f4:f5:f6", macAddress2.toString());
    }

    /**
     * Test that a valid address can be extracted from a valid lcr buffer with Civic Location.
     */
    @Test
    public void testLcrTestCivicLocationAddress() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        byte[] testLcrBuffer =
                concatenateArrays(sTestLcrBufferHeader, sTestCivicLocationSEWithAddress);
        ResponderLocation responderLocation = new ResponderLocation(testLciBuffer, testLcrBuffer);

        boolean valid = responderLocation.isValid();
        String countryCode = responderLocation.getCivicLocationCountryCode();
        Address address = responderLocation.toCivicLocationAddress();

        assertTrue(valid);
        assertEquals("US", countryCode);
        assertEquals("", address.getAddressLine(0));
        assertEquals("15 Alto", address.getAddressLine(1));
        assertEquals("Mtn View", address.getAddressLine(2));
        assertEquals("CA 94043", address.getAddressLine(3));
        assertEquals("US", address.getAddressLine(4));
    }

    /**
     * Test that a URL can be extracted from a valid lcr buffer with a map image subelement.
     */
    @Test
    public void testLcrCheckMapUriIsValid() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        byte[] testLcrBuffer = concatenateArrays(sTestLcrBufferHeader, sTestMapUrlSE);
        ResponderLocation responderLocation = new ResponderLocation(testLciBuffer, testLcrBuffer);

        boolean valid = responderLocation.isValid();
        String mapImageMimeType = responderLocation.getMapImageMimeType();
        String urlString = "";
        if (responderLocation.getMapImageUri() != null) {
            urlString = responderLocation.getMapImageUri().toString();
        }

        assertTrue(valid);
        MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        assertEquals(mimeTypeMap.getMimeTypeFromExtension("jpg"), mapImageMimeType);
        assertEquals("https://map.com/mall.jpg", urlString);
    }

    /**
     * Test the object is parcelable
     */
    @Test
    public void testResponderLocationParcelable() {
        byte[] testLciBuffer = concatenateArrays(sTestLciIeHeader, sTestLciSE);
        ResponderLocation responderLocation =
                new ResponderLocation(testLciBuffer, sTestLcrBufferHeader);

        Parcel parcel = Parcel.obtain();
        responderLocation.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ResponderLocation responderLocationFromParcel =
                ResponderLocation.CREATOR.createFromParcel(parcel);

        assertEquals(responderLocationFromParcel, responderLocation);
    }

    /* Helper Method */

    /**
     * Concatenate two arrays.
     *
     * @param a first array
     * @param b second array
     * @return a third array which is the concatenation of the two array params
     */
    private byte[] concatenateArrays(byte[] a, byte[] b) {
        int aLen = a.length;
        int bLen = b.length;
        byte[] c = new byte[aLen + bLen];
        System.arraycopy(a, 0, c, 0, aLen);
        System.arraycopy(b, 0, c, aLen, bLen);
        return c;
    }
}
