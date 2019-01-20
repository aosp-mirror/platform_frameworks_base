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

import android.annotation.Nullable;
import android.location.Address;
import android.net.wifi.rtt.CivicLocationKeys.CivicLocationKeysType;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Decodes the Type Length Value (TLV) elements found in a Location Civic Record as defined by IEEE
 * P802.11-REVmc/D8.0 section 9.4.2.22.13 using the format described in IETF RFC 4776.
 *
 * <p>The TLVs each define a key, value pair for a civic address type such as apt, street, city,
 * county, and country. The class provides a general getter method to extract a value for an element
 * key, returning null if not set.
 *
 * @hide
 */
public final class CivicLocation implements Parcelable {
    // Address (class) line indexes
    private static final int ADDRESS_LINE_0_ROOM_DESK_FLOOR = 0;
    private static final int ADDRESS_LINE_1_NUMBER_ROAD_SUFFIX_APT = 1;
    private static final int ADDRESS_LINE_2_CITY = 2;
    private static final int ADDRESS_LINE_3_STATE_POSTAL_CODE = 3;
    private static final int ADDRESS_LINE_4_COUNTRY = 4;

    // Buffer management
    private static final int MIN_CIVIC_BUFFER_SIZE = 3;
    private static final int MAX_CIVIC_BUFFER_SIZE = 256;
    private static final int COUNTRY_CODE_LENGTH = 2;
    private static final int BYTE_MASK = 0xFF;
    private static final int TLV_TYPE_INDEX = 0;
    private static final int TLV_LENGTH_INDEX = 1;
    private static final int TLV_VALUE_INDEX = 2;

    private final boolean mIsValid;
    private final String mCountryCode; // Two character country code (ISO 3166 standard).
    private SparseArray<String> mCivicAddressElements =
            new SparseArray<>(MIN_CIVIC_BUFFER_SIZE);


    /**
     * Constructor
     *
     * @param civicTLVs    a byte buffer containing parameters in the form type, length, value
     * @param countryCode the two letter code defined by the ISO 3166 standard
     *
     * @hide
     */
    public CivicLocation(@Nullable byte[] civicTLVs, @Nullable String countryCode) {
        this.mCountryCode = countryCode;
        if (countryCode == null || countryCode.length() != COUNTRY_CODE_LENGTH) {
            this.mIsValid = false;
            return;
        }
        boolean isValid = false;
        if (civicTLVs != null
                && civicTLVs.length >= MIN_CIVIC_BUFFER_SIZE
                && civicTLVs.length < MAX_CIVIC_BUFFER_SIZE) {
            isValid = parseCivicTLVs(civicTLVs);
        }

        mIsValid = isValid;
    }

    private CivicLocation(Parcel in) {
        mIsValid = in.readByte() != 0;
        mCountryCode = in.readString();
        mCivicAddressElements = in.readSparseArray(this.getClass().getClassLoader());
    }

    public static final Creator<CivicLocation> CREATOR = new Creator<CivicLocation>() {
        @Override
        public CivicLocation createFromParcel(Parcel in) {
            return new CivicLocation(in);
        }

        @Override
        public CivicLocation[] newArray(int size) {
            return new CivicLocation[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeByte((byte) (mIsValid ? 1 : 0));
        parcel.writeString(mCountryCode);
        parcel.writeSparseArray((android.util.SparseArray) mCivicAddressElements);
    }

    /**
     * Check TLV format and store TLV key/value pairs in this object so they can be queried by key.
     *
     * @param civicTLVs the buffer of TLV elements
     * @return a boolean indicating success of the parsing process
     */
    private boolean parseCivicTLVs(byte[] civicTLVs) {
        int bufferPtr = 0;
        int bufferLength = civicTLVs.length;

        // Iterate through the sub-elements contained in the LCI IE checking the accumulated
        // element lengths do not overflow the total buffer length
        while (bufferPtr < bufferLength) {
            int civicAddressType = civicTLVs[bufferPtr + TLV_TYPE_INDEX] & BYTE_MASK;
            int civicAddressTypeLength = civicTLVs[bufferPtr + TLV_LENGTH_INDEX];
            if (civicAddressTypeLength != 0) {
                if (bufferPtr + TLV_VALUE_INDEX + civicAddressTypeLength > bufferLength) {
                    return false;
                }
                mCivicAddressElements.put(civicAddressType,
                        new String(civicTLVs, bufferPtr + TLV_VALUE_INDEX,
                                civicAddressTypeLength, StandardCharsets.UTF_8));
            }
            bufferPtr += civicAddressTypeLength + TLV_VALUE_INDEX;
        }
        return true;
    }

    /**
     * Getter for the value of a civic Address element type.
     *
     * @param key an integer code for the element type key
     * @return the string value associated with that element type
     */
    @Nullable
    public String getCivicElementValue(@CivicLocationKeysType int key) {
        return mCivicAddressElements.get(key);
    }

    /**
     * Generates a comma separated string of all the defined elements.
     *
     * @return a compiled string representing all elements
     */
    @Override
    public String toString() {
        return mCivicAddressElements.toString();
    }

    /**
     * Converts Civic Location to the best effort Address Object.
     *
     * @return the {@link Address} object based on the Civic Location data
     */
    @Nullable
    public Address toAddress() {
        if (!mIsValid) {
            return null;
        }
        Address address = new Address(Locale.US);
        String room = formatAddressElement("Room: ", getCivicElementValue(CivicLocationKeys.ROOM));
        String desk =
                formatAddressElement(" Desk: ", getCivicElementValue(CivicLocationKeys.DESK));
        String floor =
                formatAddressElement(", Flr: ", getCivicElementValue(CivicLocationKeys.FLOOR));
        String houseNumber = formatAddressElement("", getCivicElementValue(CivicLocationKeys.HNO));
        String houseNumberSuffix =
                formatAddressElement("", getCivicElementValue(CivicLocationKeys.HNS));
        String road =
                formatAddressElement(" ", getCivicElementValue(
                        CivicLocationKeys.PRIMARY_ROAD_NAME));
        String roadSuffix = formatAddressElement(" ", getCivicElementValue(CivicLocationKeys.STS));
        String apt = formatAddressElement(", Apt: ", getCivicElementValue(CivicLocationKeys.APT));
        String city = formatAddressElement("", getCivicElementValue(CivicLocationKeys.CITY));
        String state = formatAddressElement("", getCivicElementValue(CivicLocationKeys.STATE));
        String postalCode =
                formatAddressElement(" ", getCivicElementValue(CivicLocationKeys.POSTAL_CODE));

        // Aggregation into common address format
        String addressLine0 =
                new StringBuilder().append(room).append(desk).append(floor).toString();
        String addressLine1 =
                new StringBuilder().append(houseNumber).append(houseNumberSuffix).append(road)
                        .append(roadSuffix).append(apt).toString();
        String addressLine2 = city;
        String addressLine3 = new StringBuilder().append(state).append(postalCode).toString();
        String addressLine4 = mCountryCode;

        // Setting Address object line fields by common convention.
        address.setAddressLine(ADDRESS_LINE_0_ROOM_DESK_FLOOR, addressLine0);
        address.setAddressLine(ADDRESS_LINE_1_NUMBER_ROAD_SUFFIX_APT, addressLine1);
        address.setAddressLine(ADDRESS_LINE_2_CITY, addressLine2);
        address.setAddressLine(ADDRESS_LINE_3_STATE_POSTAL_CODE, addressLine3);
        address.setAddressLine(ADDRESS_LINE_4_COUNTRY, addressLine4);

        // Other compatible fields between the CIVIC_ADDRESS and the Address Class.
        address.setFeatureName(getCivicElementValue(CivicLocationKeys.NAM)); // Structure name
        address.setSubThoroughfare(getCivicElementValue(CivicLocationKeys.HNO));
        address.setThoroughfare(getCivicElementValue(CivicLocationKeys.PRIMARY_ROAD_NAME));
        address.setSubLocality(getCivicElementValue(CivicLocationKeys.NEIGHBORHOOD));
        address.setSubAdminArea(getCivicElementValue(CivicLocationKeys.COUNTY));
        address.setAdminArea(getCivicElementValue(CivicLocationKeys.STATE));
        address.setPostalCode(getCivicElementValue(CivicLocationKeys.POSTAL_CODE));
        address.setCountryCode(mCountryCode); // Country
        return address;
    }

    /**
     * Prepares an address element so that it can be integrated into an address line convention.
     *
     * <p>If an address element is null, the return string will be empty e.g. "".
     *
     * @param label a string defining the type of address element
     * @param value a string defining the elements value
     * @return the formatted version of the value, with null values converted to empty strings
     */
    private String formatAddressElement(String label, String value) {
        if (value != null) {
            return label + value;
        } else {
            return "";
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CivicLocation)) {
            return false;
        }
        CivicLocation other = (CivicLocation) obj;
        return mIsValid == other.mIsValid
                && Objects.equals(mCountryCode, other.mCountryCode)
                && isSparseArrayStringEqual(mCivicAddressElements, other.mCivicAddressElements);
    }

    @Override
    public int hashCode() {
        int[] civicAddressKeys = getSparseArrayKeys(mCivicAddressElements);
        String[] civicAddressValues = getSparseArrayValues(mCivicAddressElements);
        return Objects.hash(mIsValid, mCountryCode, civicAddressKeys, civicAddressValues);
    }

    /**
     * Tests if the Civic Location object is valid
     *
     * @return a boolean defining mIsValid
     */
    public boolean isValid() {
        return mIsValid;
    }

    /**
     * Tests if two sparse arrays are equal on a key for key basis
     *
     * @param sa1 the first sparse array
     * @param sa2 the second sparse array
     * @return the boolean result after comparing values key by key
     */
    private boolean isSparseArrayStringEqual(SparseArray<String> sa1, SparseArray<String> sa2) {
        int size = sa1.size();
        if (size != sa2.size()) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            String sa1Value = sa1.valueAt(i);
            String sa2Value = sa2.valueAt(i);
            if (!sa1Value.equals(sa2Value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extract an array of all the keys in a SparseArray<String>
     *
     * @param sa the sparse array of Strings
     * @return an integer array of all keys in the SparseArray<String>
     */
    private int[] getSparseArrayKeys(SparseArray<String> sa) {
        int size = sa.size();
        int[] keys = new int[size];
        for (int i = 0; i < size; i++) {
            keys[i] = sa.keyAt(i);
        }
        return keys;
    }

    /**
     * Extract an array of all the String values in a SparseArray<String>
     *
     * @param sa the sparse array of Strings
     * @return a String array of all values in the SparseArray<String>
     */
    private String[] getSparseArrayValues(SparseArray<String> sa) {
        int size = sa.size();
        String[] values = new String[size];
        for (int i = 0; i < size; i++) {
            values[i] = sa.valueAt(i);
        }
        return values;
    }
}
