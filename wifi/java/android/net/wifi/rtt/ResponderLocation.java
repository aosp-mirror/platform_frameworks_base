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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.location.Address;
import android.net.MacAddress;
import android.net.wifi.rtt.CivicLocationKeys.CivicLocationKeysType;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ResponderLocation is both a Location Configuration Information (LCI) decoder and a Location Civic
 * Report (LCR) decoder for information received from a Wi-Fi Access Point (AP) during Wi-Fi RTT
 * ranging process.
 *
 * <p>This is based on the IEEE P802.11-REVmc/D8.0 spec section 9.4.2.22, under Measurement Report
 * Element. Subelement location data-fields parsed from separate input LCI and LCR Information
 * Elements are unified in this class.</p>
 *
 * <p>Note: The information provided by this class is broadcast by a responder (usually an Access
 * Point), and passed on as-is. There is no guarantee this information is accurate or correct, and
 * as a result developers should carefully consider how this information should be used and provide
 * corresponding advice to users.</p>
 */
public final class ResponderLocation implements Parcelable {
    private static final int BYTE_MASK = 0xFF;
    private static final int LSB_IN_BYTE = 0x01;
    private static final int MSB_IN_BYTE = 0x80;
    private static final int MIN_BUFFER_SIZE = 3; // length of LEAD_LCI_ELEMENT_BYTES
    private static final int MAX_BUFFER_SIZE = 256;

    // Information Element (IE) fields
    private static final byte MEASUREMENT_TOKEN_AUTONOMOUS = 0x01;
    private static final byte MEASUREMENT_REPORT_MODE = 0x00;
    private static final byte MEASUREMENT_TYPE_LCI = 0x08;
    private static final byte MEASUREMENT_TYPE_LCR = 0x0b;

    // LCI Subelement IDs
    private static final byte SUBELEMENT_LCI = 0x00;
    private static final byte SUBELEMENT_Z = 0x04;
    private static final byte SUBELEMENT_USAGE = 0x06;
    private static final byte SUBELEMENT_BSSID_LIST = 0x07;

    // LCI Subelement Lengths
    private static final int SUBELEMENT_LCI_LENGTH = 16;
    private static final int SUBELEMENT_Z_LENGTH = 6;
    private static final int SUBELEMENT_USAGE_LENGTH1 = 1;
    private static final int SUBELEMENT_USAGE_LENGTH3 = 3;
    private static final int SUBELEMENT_BSSID_LIST_MIN_BUFFER_LENGTH = 1;

    private static final byte[] LEAD_LCI_ELEMENT_BYTES = {
            MEASUREMENT_TOKEN_AUTONOMOUS, MEASUREMENT_REPORT_MODE, MEASUREMENT_TYPE_LCI
    };

    // Subelement LCI constants

    /* The LCI subelement bit-field lengths are described in Figure 9-214 of the REVmc spec and
    represented here as a an array of integers */
    private static final int[] SUBELEMENT_LCI_BIT_FIELD_LENGTHS = {
            6, 34, 6, 34, 4, 6, 30, 3, 1, 1, 1, 2
    };
    private static final int LATLNG_FRACTION_BITS = 25;
    private static final int LATLNG_UNCERTAINTY_BASE = 8;
    private static final int ALTITUDE_FRACTION_BITS = 8;
    private static final int ALTITUDE_UNCERTAINTY_BASE = 21;
    private static final double LAT_ABS_LIMIT = 90.0;
    private static final double LNG_ABS_LIMIT = 180.0;
    private static final int UNCERTAINTY_UNDEFINED = 0;

    // Subelement LCI fields indices
    private static final int SUBELEMENT_LCI_LAT_UNCERTAINTY_INDEX = 0;
    private static final int SUBELEMENT_LCI_LAT_INDEX = 1;
    private static final int SUBELEMENT_LCI_LNG_UNCERTAINTY_INDEX = 2;
    private static final int SUBELEMENT_LCI_LNG_INDEX = 3;
    private static final int SUBELEMENT_LCI_ALT_TYPE_INDEX = 4;
    private static final int SUBELEMENT_LCI_ALT_UNCERTAINTY_INDEX = 5;
    private static final int SUBELEMENT_LCI_ALT_INDEX = 6;
    private static final int SUBELEMENT_LCI_DATUM_INDEX = 7;
    private static final int SUBELEMENT_LCI_REGLOC_AGREEMENT_INDEX = 8;
    private static final int SUBELEMENT_LCI_REGLOC_DSE_INDEX = 9;
    private static final int SUBELEMENT_LCI_DEPENDENT_STA_INDEX = 10;
    private static final int SUBELEMENT_LCI_VERSION_INDEX = 11;

    /**
     * The Altitude value is interpreted based on the Altitude Type, and the selected mDatum.
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef({ALTITUDE_UNDEFINED, ALTITUDE_METERS, ALTITUDE_FLOORS})
    public @interface AltitudeType {
    }

    /**
     * Altitude is not defined for the Responder.
     * The altitude and altitude uncertainty should not be used: see section 2.4 of IETF RFC 6225.
     */
    public static final int ALTITUDE_UNDEFINED = 0;
    /** Responder Altitude is measured in meters.  */
    public static final int ALTITUDE_METERS = 1;
    /** Responder Altitude is measured in floors. */
    public static final int ALTITUDE_FLOORS = 2;

    /**
     * The Datum value determines how coordinates are organized in relation to the real world.
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef({DATUM_UNDEFINED, DATUM_WGS84, DATUM_NAD83_NAV88, DATUM_NAD83_MLLW})
    public @interface DatumType {
    }

    /** Datum is not defined. */
    public static final int DATUM_UNDEFINED = 0;
    /** Datum used is WGS84. */
    public static final int DATUM_WGS84 = 1;
    /** Datum used is NAD83 NAV88. */
    public static final int DATUM_NAD83_NAV88 = 2;
    /** Datum used is NAD83 MLLW. */
    public static final int DATUM_NAD83_MLLW = 3;


    /** Version of the LCI protocol is 1.0, the only defined protocol at this time. */
    public static final int LCI_VERSION_1 = 1;

    /**
     * Enumerates the flags contained in getLciFlags()
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef(flag = true, value = {LCI_FLAGS_MASK_REGLOC_AGREEMENT, LCI_FLAGS_MASK_REGLOC_DSE,
        LCI_FLAGS_MASK_DEPENDENT_STA, LCI_FLAGS_MASK_VERSION})
    public @interface LciFlagMasks {
    }
    /** Location agreement flag is obtained by ANDing this mask with the getLciFlags() value.*/
    public static final int LCI_FLAGS_MASK_REGLOC_AGREEMENT = 0x10;
    /** Location DSE flag is obtained by ANDing this mask with the getLciFlags() value.*/
    public static final int LCI_FLAGS_MASK_REGLOC_DSE = 0x08;
    /** Dependent station flag is obtained by ANDing this mask with the getLciFlags() value. */
    public static final int LCI_FLAGS_MASK_DEPENDENT_STA = 0x04;
    /** Version bits are obtained by ANDing this mask with the getLciFlags() value.*/
    public static final int LCI_FLAGS_MASK_VERSION = 0x03;

    // LCI Subelement Z constants
    private static final int[] SUBELEMENT_Z_BIT_FIELD_LENGTHS = {2, 14, 24, 8};
    private static final int Z_FLOOR_NUMBER_FRACTION_BITS = 4;
    private static final int Z_FLOOR_HEIGHT_FRACTION_BITS = 12;
    private static final int Z_MAX_HEIGHT_UNCERTAINTY_FACTOR = 25;

    // LCI Subelement Z fields indices
    private static final int SUBELEMENT_Z_LAT_EXPECTED_TO_MOVE_INDEX = 0;
    private static final int SUBELEMENT_Z_STA_FLOOR_NUMBER_INDEX = 1;
    private static final int SUBELEMENT_Z_STA_HEIGHT_ABOVE_FLOOR_INDEX = 2;
    private static final int SUBELEMENT_Z_STA_HEIGHT_ABOVE_FLOOR_UNCERTAINTY_INDEX = 3;

    // LCI Subelement Usage Rules constants
    private static final int SUBELEMENT_USAGE_MASK_RETRANSMIT = 0x01;
    private static final int SUBELEMENT_USAGE_MASK_RETENTION_EXPIRES = 0x02;
    private static final int SUBELEMENT_USAGE_MASK_STA_LOCATION_POLICY = 0x04;

    // LCI Subelement Usage Rules field indices
    private static final int SUBELEMENT_USAGE_PARAMS_INDEX = 0; // 8 bits

    // LCI Subelement BSSID List
    private static final int SUBELEMENT_BSSID_MAX_INDICATOR_INDEX = 0;
    private static final int SUBELEMENT_BSSID_LIST_INDEX = 1;
    private static final int BYTES_IN_A_BSSID = 6;

    /**
     * The Expected-To-Move value determines how mobile we expect the STA to be.
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef({LOCATION_FIXED, LOCATION_VARIABLE, LOCATION_MOVEMENT_UNKNOWN, LOCATION_RESERVED})
    public @interface ExpectedToMoveType {
    }

    /** Location of responder is fixed (does not move) */
    public static final int LOCATION_FIXED = 0;
    /** Location of the responder is variable, and may move */
    public static final int LOCATION_VARIABLE = 1;
    /** Location of the responder is not known to be either fixed or variable. */
    public static final int LOCATION_MOVEMENT_UNKNOWN = 2;
    /** Location of the responder status is a reserved value */
    public static final int LOCATION_RESERVED = 3;

    // LCR Subelement IDs
    private static final byte SUBELEMENT_LOCATION_CIVIC = 0x00;
    private static final byte SUBELEMENT_MAP_IMAGE = 0x05;

    // LCR Subelement Lengths
    private static final int SUBELEMENT_LOCATION_CIVIC_MIN_LENGTH = 2;
    private static final int SUBELEMENT_LOCATION_CIVIC_MAX_LENGTH = 256;
    private static final int SUBELEMENT_MAP_IMAGE_URL_MAX_LENGTH = 256;

    private static final byte[] LEAD_LCR_ELEMENT_BYTES = {
            MEASUREMENT_TOKEN_AUTONOMOUS, MEASUREMENT_REPORT_MODE, MEASUREMENT_TYPE_LCR
    };

    // LCR Location Civic Subelement
    private static final int CIVIC_COUNTRY_CODE_INDEX = 0;
    private static final int CIVIC_TLV_LIST_INDEX = 2;

    // LCR Map Image Subelement field indexes.
    private static final int SUBELEMENT_IMAGE_MAP_TYPE_INDEX = 0;

    /**
     * The Map Type value specifies the image format type.
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef({
            MAP_TYPE_URL_DEFINED,
            MAP_TYPE_PNG,
            MAP_TYPE_GIF,
            MAP_TYPE_JPG,
            MAP_TYPE_SVG,
            MAP_TYPE_DXF,
            MAP_TYPE_DWG,
            MAP_TYPE_DWF,
            MAP_TYPE_CAD,
            MAP_TYPE_TIFF,
            MAP_TYPE_GML,
            MAP_TYPE_KML,
            MAP_TYPE_BMP,
            MAP_TYPE_PGM,
            MAP_TYPE_PPM,
            MAP_TYPE_XBM,
            MAP_TYPE_XPM,
            MAP_TYPE_ICO
    })
    public @interface MapImageType {
    }

    /** File type defined by the file suffix itself. */
    public static final int MAP_TYPE_URL_DEFINED = 0;
    /** File type is in PNG format. */
    public static final int MAP_TYPE_PNG = 1;
    /** File type is in GIF format. */
    public static final int MAP_TYPE_GIF = 2;
    /** File type is in JPG format. */
    public static final int MAP_TYPE_JPG = 3;
    /** File type is in SVG format. */
    public static final int MAP_TYPE_SVG = 4;
    /** File type is in DXF format. */
    public static final int MAP_TYPE_DXF = 5;
    /** File type is in DWG format. */
    public static final int MAP_TYPE_DWG = 6;
    /** File type is in DWF format. */
    public static final int MAP_TYPE_DWF = 7;
    /** File type is in CAD format. */
    public static final int MAP_TYPE_CAD = 8;
    /** File type is in TIFF format. */
    public static final int MAP_TYPE_TIFF = 9;
    /** File type is in GML format. */
    public static final int MAP_TYPE_GML = 10;
    /** File type is in KML format. */
    public static final int MAP_TYPE_KML = 11;
    /** File type is in BMP format. */
    public static final int MAP_TYPE_BMP = 12;
    /** File type is in PGM format. */
    public static final int MAP_TYPE_PGM = 13;
    /** File type is in PPM format. */
    public static final int MAP_TYPE_PPM = 14;
    /** File type is in XBM format. */
    public static final int MAP_TYPE_XBM = 15;
    /** File type is in XPM format. */
    public static final int MAP_TYPE_XPM = 16;
    /** File type is in ICO format. */
    public static final int MAP_TYPE_ICO = 17;

    // General LCI and LCR state
    private final boolean mIsValid;

    /*
      These members are not final because we are not sure if the corresponding subelement will be
      present until after the parsing process. However, the default value should be set as listed.
    */
    private boolean mIsLciValid = false;
    private boolean mIsZValid = false;
    private boolean mIsUsageValid = true; // By default this is assumed true
    private boolean mIsBssidListValid = false;
    private boolean mIsLocationCivicValid = false;
    private boolean mIsMapImageValid = false;

    // LCI Subelement LCI state
    private double mLatitudeUncertainty;
    private double mLatitude;
    private double mLongitudeUncertainty;
    private double mLongitude;
    private int mAltitudeType;
    private double mAltitudeUncertainty;
    private double mAltitude;
    private int mDatum;
    private int mLciFlags;

    // LCI Subelement Z state
    private int mExpectedToMove;
    private double mStaFloorNumber;
    private double mStaHeightAboveFloorMeters;
    private double mStaHeightAboveFloorUncertaintyMeters;

    // LCI Subelement Usage Rights state
    private boolean mUsageRetransmit;
    private boolean mUsageRetentionExpires;
    private boolean mUsageExtraInfoOnAssociation;

    // LCI Subelement BSSID List state
    private ArrayList<MacAddress> mBssidList;

    // LCR Subelement Location Civic state
    private String mCivicLocationCountryCode;
    private String mCivicLocationString;
    private CivicLocation mCivicLocation;

    // LCR Subelement Map Image state
    private int mMapImageType;
    private URL mMapImageUrl;

    /**
     * Constructor
     *
     * @param lciBuffer the bytes received in the LCI Measurement Report Information Element
     * @param lcrBuffer the bytes received in the LCR Measurement Report Information Element
     *
     * @hide
     */
    public ResponderLocation(byte[] lciBuffer, byte[] lcrBuffer) {
        boolean isLciIeValid = false;
        boolean isLcrIeValid = false;
        setLciSubelementDefaults();
        setZSubelementDefaults();
        setUsageSubelementDefaults();
        setBssidListSubelementDefaults();
        setCivicLocationSubelementDefaults();
        setMapImageSubelementDefaults();
        if (lciBuffer != null && lciBuffer.length > LEAD_LCI_ELEMENT_BYTES.length) {
            isLciIeValid = parseInformationElementBuffer(
                MEASUREMENT_TYPE_LCI, lciBuffer, LEAD_LCI_ELEMENT_BYTES);
        }
        if (lcrBuffer != null && lcrBuffer.length > LEAD_LCR_ELEMENT_BYTES.length) {
            isLcrIeValid = parseInformationElementBuffer(
                MEASUREMENT_TYPE_LCR, lcrBuffer, LEAD_LCR_ELEMENT_BYTES);
        }

        boolean isLciValid = isLciIeValid && mIsUsageValid
                && (mIsLciValid || mIsZValid || mIsBssidListValid);

        boolean isLcrValid = isLcrIeValid && mIsUsageValid
                && (mIsLocationCivicValid || mIsMapImageValid);

        mIsValid = isLciValid || isLcrValid;

        if (!mIsValid) {
            setLciSubelementDefaults();
            setZSubelementDefaults();
            setCivicLocationSubelementDefaults();
            setMapImageSubelementDefaults();
        }
    }

    private ResponderLocation(Parcel in) {
        // Object Validation
        mIsValid = in.readByte() != 0;
        mIsLciValid = in.readByte() != 0;
        mIsZValid = in.readByte() != 0;
        mIsUsageValid = in.readByte() != 0;
        mIsBssidListValid = in.readByte() != 0;
        mIsLocationCivicValid = in.readByte() != 0;
        mIsMapImageValid = in.readByte() != 0;

        // LCI Subelement LCI state
        mLatitudeUncertainty = in.readDouble();
        mLatitude = in.readDouble();
        mLongitudeUncertainty = in.readDouble();
        mLongitude = in.readDouble();
        mAltitudeType = in.readInt();
        mAltitudeUncertainty = in.readDouble();
        mAltitude = in.readDouble();
        mDatum = in.readInt();
        mLciFlags = in.readInt();

        // LCI Subelement Z state
        mExpectedToMove = in.readInt();
        mStaFloorNumber = in.readDouble();
        mStaHeightAboveFloorMeters = in.readDouble();
        mStaHeightAboveFloorUncertaintyMeters = in.readDouble();

        // LCI Usage Rights
        mUsageRetransmit = in.readByte() != 0;
        mUsageRetentionExpires = in.readByte() != 0;
        mUsageExtraInfoOnAssociation = in.readByte() != 0;

        // LCI Subelement BSSID List
        mBssidList = in.readArrayList(MacAddress.class.getClassLoader());

        // LCR Subelement Location Civic
        mCivicLocationCountryCode = in.readString();
        mCivicLocationString = in.readString();
        mCivicLocation = in.readParcelable(this.getClass().getClassLoader());

        // LCR Subelement Map Image
        mMapImageType = in.readInt();
        try {
            mMapImageUrl = new URL(in.readString());
        } catch (MalformedURLException e) {
            mMapImageUrl = null;
        }
    }

    public static final Creator<ResponderLocation> CREATOR = new Creator<ResponderLocation>() {
        @Override
        public ResponderLocation createFromParcel(Parcel in) {
            return new ResponderLocation(in);
        }

        @Override
        public ResponderLocation[] newArray(int size) {
            return new ResponderLocation[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        // Object
        parcel.writeByte((byte) (mIsValid ? 1 : 0));
        parcel.writeByte((byte) (mIsLciValid ? 1 : 0));
        parcel.writeByte((byte) (mIsZValid ? 1 : 0));
        parcel.writeByte((byte) (mIsUsageValid ? 1 : 0));
        parcel.writeByte((byte) (mIsBssidListValid ? 1 : 0));
        parcel.writeByte((byte) (mIsLocationCivicValid ? 1 : 0));
        parcel.writeByte((byte) (mIsMapImageValid ? 1 : 0));

        // LCI Subelement LCI state
        parcel.writeDouble(mLatitudeUncertainty);
        parcel.writeDouble(mLatitude);
        parcel.writeDouble(mLongitudeUncertainty);
        parcel.writeDouble(mLongitude);
        parcel.writeInt(mAltitudeType);
        parcel.writeDouble(mAltitudeUncertainty);
        parcel.writeDouble(mAltitude);
        parcel.writeInt(mDatum);
        parcel.writeInt(mLciFlags);

        // LCI Subelement Z state
        parcel.writeInt(mExpectedToMove);
        parcel.writeDouble(mStaFloorNumber);
        parcel.writeDouble(mStaHeightAboveFloorMeters);
        parcel.writeDouble(mStaHeightAboveFloorUncertaintyMeters);

        // LCI Usage Rights
        parcel.writeByte((byte) (mUsageRetransmit ? 1 : 0));
        parcel.writeByte((byte) (mUsageRetentionExpires ? 1 : 0));
        parcel.writeByte((byte) (mUsageExtraInfoOnAssociation ? 1 : 0));

        // LCI Subelement BSSID List
        parcel.writeList(mBssidList);

        // LCR Subelement Location Civic
        parcel.writeString(mCivicLocationCountryCode);
        parcel.writeString(mCivicLocationString);
        parcel.writeParcelable(mCivicLocation, flags);

        // LCR Subelement Map Image
        parcel.writeInt(mMapImageType);
        if (mMapImageUrl != null) {
            parcel.writeString(mMapImageUrl.toString());
        } else {
            parcel.writeString("");
        }
    }

    /**
     * Test if the Information Element (IE) is in the correct format, and then parse its Subelements
     * based on their type, and setting state in this object when present.
     *
     * @return a boolean indicating the success of the parsing function
     */
    private boolean parseInformationElementBuffer(
            int ieType, byte[] buffer, byte[] expectedLeadBytes) {
        int bufferPtr = 0;
        int bufferLength = buffer.length;

        // Ensure the buffer size is within expected limits
        if (bufferLength < MIN_BUFFER_SIZE || bufferLength > MAX_BUFFER_SIZE) {
            return false;
        }

        // Ensure the IE contains the correct leading bytes
        byte[] leadBufferBytes = Arrays.copyOfRange(buffer, bufferPtr, expectedLeadBytes.length);
        if (!Arrays.equals(leadBufferBytes, expectedLeadBytes)) {
            return false;
        }

        // Iterate through the sub-elements contained in the Information Element (IE)
        bufferPtr += expectedLeadBytes.length;
        // Loop over the buffer ensuring there are 2-bytes available for each new subelement tested.
        while (bufferPtr + 1 < bufferLength) {
            byte subelement = buffer[bufferPtr++];
            int subelementLength = buffer[bufferPtr++];
            // Check there is enough data for the next subelement
            if ((bufferPtr + subelementLength) > bufferLength || subelementLength <= 0) {
                return false;
            }

            byte[] subelementData =
                    Arrays.copyOfRange(buffer, bufferPtr, bufferPtr + subelementLength);

            if (ieType == MEASUREMENT_TYPE_LCI) {
                switch (subelement) {
                    case SUBELEMENT_LCI:
                        mIsLciValid = parseSubelementLci(subelementData);
                        if (!mIsLciValid || (mLciFlags & LCI_FLAGS_MASK_VERSION) != LCI_VERSION_1) {
                            setLciSubelementDefaults();
                        }
                        break;
                    case SUBELEMENT_Z:
                        mIsZValid = parseSubelementZ(subelementData);
                        if (!mIsZValid) {
                            setZSubelementDefaults();
                        }
                        break;
                    case SUBELEMENT_USAGE:
                        mIsUsageValid = parseSubelementUsage(subelementData);
                        // Note: if the Usage Subelement is not valid, don't reset the state, as
                        // it is now indicating the whole ResponderLocation is invalid.
                        break;
                    case SUBELEMENT_BSSID_LIST:
                        mIsBssidListValid = parseSubelementBssidList(subelementData);
                        if (!mIsBssidListValid) {
                            setBssidListSubelementDefaults();
                        }
                        break;
                    default:
                        break; // skip over unused or vendor specific subelements
                }
            } else if (ieType == MEASUREMENT_TYPE_LCR) {
                switch (subelement) {
                    case SUBELEMENT_LOCATION_CIVIC:
                        mIsLocationCivicValid = parseSubelementLocationCivic(subelementData);
                        if (!mIsLocationCivicValid) {
                            setCivicLocationSubelementDefaults();
                        }
                        break;
                    case SUBELEMENT_MAP_IMAGE:
                        mIsMapImageValid = parseSubelementMapImage(subelementData);
                        if (!mIsMapImageValid) {
                            setMapImageSubelementDefaults();
                        }
                        break;
                    default:
                        break; // skip over unused or other vendor specific subelements
                }
            }

            bufferPtr += subelementLength;
        }
        return true;
    }

    /**
     * Parse the LCI Sub-Element in the LCI Information Element (IE).
     *
     * @param buffer a buffer containing the subelement
     * @return boolean true indicates success
     */
    private boolean parseSubelementLci(byte[] buffer) {
        if (buffer.length > SUBELEMENT_LCI_LENGTH) {
            return false;
        }
        swapEndianByteByByte(buffer);
        long[] subelementLciFields = getFieldData(buffer, SUBELEMENT_LCI_BIT_FIELD_LENGTHS);
        if (subelementLciFields == null) {
            return false;
        }
        // Set member state based on parsed buffer data
        mLatitudeUncertainty = decodeLciLatLngUncertainty(
                subelementLciFields[SUBELEMENT_LCI_LAT_UNCERTAINTY_INDEX]);
        mLatitude = decodeLciLatLng(subelementLciFields, SUBELEMENT_LCI_BIT_FIELD_LENGTHS,
                SUBELEMENT_LCI_LAT_INDEX, LAT_ABS_LIMIT);
        mLongitudeUncertainty = decodeLciLatLngUncertainty(
                subelementLciFields[SUBELEMENT_LCI_LNG_UNCERTAINTY_INDEX]);
        mLongitude =
                decodeLciLatLng(subelementLciFields, SUBELEMENT_LCI_BIT_FIELD_LENGTHS,
                        SUBELEMENT_LCI_LNG_INDEX, LNG_ABS_LIMIT);
        mAltitudeType = (int) subelementLciFields[SUBELEMENT_LCI_ALT_TYPE_INDEX] & BYTE_MASK;
        mAltitudeUncertainty =
                decodeLciAltUncertainty(subelementLciFields[SUBELEMENT_LCI_ALT_UNCERTAINTY_INDEX]);
        mAltitude =
                Math.scalb(subelementLciFields[SUBELEMENT_LCI_ALT_INDEX], -ALTITUDE_FRACTION_BITS);
        mDatum = (int) subelementLciFields[SUBELEMENT_LCI_DATUM_INDEX] & BYTE_MASK;
        mLciFlags =
                (int) subelementLciFields[SUBELEMENT_LCI_REGLOC_AGREEMENT_INDEX]
                        * LCI_FLAGS_MASK_REGLOC_AGREEMENT
                        | (int) subelementLciFields[SUBELEMENT_LCI_REGLOC_DSE_INDEX]
                        * LCI_FLAGS_MASK_REGLOC_DSE
                        | (int) subelementLciFields[SUBELEMENT_LCI_DEPENDENT_STA_INDEX]
                        * LCI_FLAGS_MASK_DEPENDENT_STA
                        | (int) subelementLciFields[SUBELEMENT_LCI_VERSION_INDEX];
        return true;
    }

    /**
     * Decode the floating point value of an encoded lat or lng in the LCI subelement field.
     *
     * @param fields        the array of field data represented as longs
     * @param bitFieldSizes the lengths of each field
     * @param offset        the offset of the field being decoded
     * @param limit the maximum absolute value (note: different for lat vs lng)
     * @return the floating point value of the lat or lng
     */
    private double decodeLciLatLng(long[] fields, int[] bitFieldSizes, int offset, double limit) {
        double angle;
        if ((fields[offset] & (long) Math.pow(2, bitFieldSizes[offset] - 1)) != 0) {
            // Negative 2's complement value
            // Note: The Math.pow(...) method cannot return a NaN value because the bitFieldSize
            // for Lat or Lng is limited to exactly 34 bits.
            angle = Math.scalb(fields[offset] - Math.pow(2, bitFieldSizes[offset]),
                    -LATLNG_FRACTION_BITS);
        } else {
            // Positive 2's complement value
            angle = Math.scalb(fields[offset], -LATLNG_FRACTION_BITS);
        }
        if (angle > limit) {
            angle = limit;
        } else if (angle < -limit) {
            angle = -limit;
        }
        return angle;
    }

    /**
     * Coverts an encoded Lat/Lng uncertainty into a number of degrees.
     *
     * @param encodedValue the encoded uncertainty
     * @return the value in degrees
     */
    private double decodeLciLatLngUncertainty(long encodedValue) {
        return Math.pow(2, LATLNG_UNCERTAINTY_BASE - encodedValue);
    }

    /**
     * Converts an encoded Alt uncertainty into a number of degrees.
     *
     * @param encodedValue the encoded uncertainty
     * @return the value in degrees
     */
    private double decodeLciAltUncertainty(long encodedValue) {
        return Math.pow(2, ALTITUDE_UNCERTAINTY_BASE - encodedValue);
    }

    /**
     * Parse the Z subelement of the LCI IE.
     *
     * @param buffer a buffer containing the subelement
     * @return boolean true indicates success
     */
    private boolean parseSubelementZ(byte[] buffer) {
        if (buffer.length != SUBELEMENT_Z_LENGTH) {
            return false;
        }
        swapEndianByteByByte(buffer);
        long[] subelementZFields = getFieldData(buffer, SUBELEMENT_Z_BIT_FIELD_LENGTHS);
        if (subelementZFields == null) {
            return false;
        }

        mExpectedToMove =
                (int) subelementZFields[SUBELEMENT_Z_LAT_EXPECTED_TO_MOVE_INDEX] & BYTE_MASK;

        mStaFloorNumber = decodeZUnsignedToSignedValue(subelementZFields,
                SUBELEMENT_Z_BIT_FIELD_LENGTHS, SUBELEMENT_Z_STA_FLOOR_NUMBER_INDEX,
                Z_FLOOR_NUMBER_FRACTION_BITS);

        mStaHeightAboveFloorMeters = decodeZUnsignedToSignedValue(subelementZFields,
                SUBELEMENT_Z_BIT_FIELD_LENGTHS, SUBELEMENT_Z_STA_HEIGHT_ABOVE_FLOOR_INDEX,
                Z_FLOOR_HEIGHT_FRACTION_BITS);

        long zHeightUncertainty =
                subelementZFields[SUBELEMENT_Z_STA_HEIGHT_ABOVE_FLOOR_UNCERTAINTY_INDEX];
        if (zHeightUncertainty > 0 && zHeightUncertainty < Z_MAX_HEIGHT_UNCERTAINTY_FACTOR) {
            mStaHeightAboveFloorUncertaintyMeters =
                    Math.pow(2, Z_FLOOR_HEIGHT_FRACTION_BITS - zHeightUncertainty - 1);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Decode a two's complement encoded value, to a signed double based on the field length.
     *
     * @param fieldValues the array of field values reprented as longs
     * @param fieldLengths the array of field lengths
     * @param index the index of the field being decoded
     * @param fraction the number of fractional bits in the value
     * @return the signed value represented as a double
     */
    private double decodeZUnsignedToSignedValue(long[] fieldValues, int[] fieldLengths, int index,
            int fraction) {
        int value = (int) fieldValues[index];
        int maxPositiveValue = (int) Math.pow(2, fieldLengths[index] - 1) - 1;
        if (value > maxPositiveValue) {
            value -= Math.pow(2, fieldLengths[index]);
        }
        return Math.scalb(value, -fraction);
    }

    /**
     * Parse Subelement Usage Rights
     */
    private boolean parseSubelementUsage(byte[] buffer) {
        if (buffer.length != SUBELEMENT_USAGE_LENGTH1
                && buffer.length != SUBELEMENT_USAGE_LENGTH3) {
            return false;
        }
        mUsageRetransmit =
                (buffer[SUBELEMENT_USAGE_PARAMS_INDEX] & SUBELEMENT_USAGE_MASK_RETRANSMIT) != 0;
        mUsageRetentionExpires =
                (buffer[SUBELEMENT_USAGE_PARAMS_INDEX] & SUBELEMENT_USAGE_MASK_RETENTION_EXPIRES)
                        != 0;
        mUsageExtraInfoOnAssociation =
                (buffer[SUBELEMENT_USAGE_PARAMS_INDEX] & SUBELEMENT_USAGE_MASK_STA_LOCATION_POLICY)
                        != 0;
        // Note: the Retransmit flag must be true, and RetentionExpires, false for the
        // ResponderLocation object to be usable by public applications.
        return (mUsageRetransmit && !mUsageRetentionExpires);
    }

    /**
     * Parse the BSSID List Subelement of the LCI IE.
     *
     * @param buffer a buffer containing the subelement
     * @return boolean true indicates success
     */
    private boolean parseSubelementBssidList(byte[] buffer) {
        if (buffer.length < SUBELEMENT_BSSID_LIST_MIN_BUFFER_LENGTH) {
            return false;
        }
        if ((buffer.length - 1) % BYTES_IN_A_BSSID != 0) {
            return false;
        }

        int maxBssidIndicator = (int) buffer[SUBELEMENT_BSSID_MAX_INDICATOR_INDEX] & BYTE_MASK;
        int bssidListLength = (buffer.length - 1) / BYTES_IN_A_BSSID;
        // Check the max number of BSSIDs agrees with the list length.
        if (maxBssidIndicator != bssidListLength) {
            return false;
        }

        int bssidOffset = SUBELEMENT_BSSID_LIST_INDEX;
        for (int i = 0; i < bssidListLength; i++) {
            byte[] bssid = Arrays.copyOfRange(buffer, bssidOffset, bssidOffset + BYTES_IN_A_BSSID);
            MacAddress macAddress = MacAddress.fromBytes(bssid);
            mBssidList.add(macAddress);
            bssidOffset += BYTES_IN_A_BSSID;
        }
        return true;
    }

    /**
     * Parse the Location Civic subelement in the LCR IE.
     *
     * @param buffer a buffer containing the subelement
     * @return boolean true indicates success
     */
    private boolean parseSubelementLocationCivic(byte[] buffer) {
        if (buffer.length <  SUBELEMENT_LOCATION_CIVIC_MIN_LENGTH
                || buffer.length > SUBELEMENT_LOCATION_CIVIC_MAX_LENGTH) {
            return false;
        }
        mCivicLocationCountryCode =
                new String(Arrays.copyOfRange(buffer, CIVIC_COUNTRY_CODE_INDEX,
                        CIVIC_TLV_LIST_INDEX)).toUpperCase();
        CivicLocation civicLocation =
                new CivicLocation(
                        Arrays.copyOfRange(buffer, CIVIC_TLV_LIST_INDEX, buffer.length),
                        mCivicLocationCountryCode);
        if (!civicLocation.isValid()) {
            return false;
        }
        this.mCivicLocation = civicLocation;
        mCivicLocationString = civicLocation.toString();
        return true;
    }

    /**
     * Parse the Map Image subelement in the LCR IE.
     *
     * @param buffer a buffer containing the subelement
     * @return boolean true indicates success
     */
    private boolean parseSubelementMapImage(byte[] buffer) {
        if (buffer.length > SUBELEMENT_MAP_IMAGE_URL_MAX_LENGTH) {
            return false;
        }
        int mapImageType = buffer[SUBELEMENT_IMAGE_MAP_TYPE_INDEX];
        if (mapImageType < MAP_TYPE_URL_DEFINED || mapImageType > MAP_TYPE_DWG) {
            return false;
        }
        this.mMapImageType = mapImageType;
        byte[] urlBytes = Arrays.copyOfRange(buffer, 1, buffer.length);
        try {
            mMapImageUrl = new URL(new String(urlBytes));
        } catch (MalformedURLException e) {
            mMapImageUrl = null;
            return false;
        }
        return true;
    }

    /**
     * Converts a byte array containing fields of variable size, into an array of longs where the
     * field boundaries are defined in a constant int array passed as an argument.
     *
     * @param buffer        the byte array containing all the fields
     * @param bitFieldSizes the int array defining the size of each field
     */
    private long[] getFieldData(byte[] buffer, int[] bitFieldSizes) {
        int bufferLengthBits = buffer.length * Byte.SIZE;
        int sumBitFieldSizes = 0;
        for (int i : bitFieldSizes) {
            if (i > Long.SIZE) {
                return null;
            }
            sumBitFieldSizes += i;
        }
        if (bufferLengthBits != sumBitFieldSizes) {
            return null;
        }
        long[] fieldData = new long[bitFieldSizes.length];
        int bufferBitPos = 0;
        for (int fieldIndex = 0; fieldIndex < bitFieldSizes.length; fieldIndex++) {
            int bitFieldSize = bitFieldSizes[fieldIndex];
            long field = 0;
            for (int n = 0; n < bitFieldSize; n++) {
                field |= ((long) getBitAtBitOffsetInByteArray(buffer, bufferBitPos + n) << n);
            }
            fieldData[fieldIndex] = field;
            bufferBitPos += bitFieldSize;
        }
        return fieldData;
    }

    /**
     * Retrieves state of a bit at the bit-offset in a byte array, where the offset represents the
     * bytes in contiguous data with each value in big endian order.
     *
     * @param buffer          the data buffer of bytes containing all the fields
     * @param bufferBitOffset the bit offset into the entire buffer
     * @return a zero or one value, representing the state of that bit.
     */
    private int getBitAtBitOffsetInByteArray(byte[] buffer, int bufferBitOffset) {
        int bufferIndex = bufferBitOffset / Byte.SIZE; // The byte index that contains the bit
        int bitOffsetInByte = bufferBitOffset % Byte.SIZE; // The bit offset within that byte
        int result = (buffer[bufferIndex] & (MSB_IN_BYTE >> bitOffsetInByte)) == 0 ? 0 : 1;
        return result;
    }

    /**
     * Reverses the order of the bits in each byte of a byte array.
     *
     * @param buffer the array containing each byte that will be reversed
     */
    private void swapEndianByteByByte(byte[] buffer) {
        for (int n = 0; n < buffer.length; n++) {
            byte currentByte = buffer[n];
            byte reversedByte = 0; // Cleared value
            byte bitSelectorMask = LSB_IN_BYTE;
            for (int i = 0; i < Byte.SIZE; i++) {
                reversedByte = (byte) (reversedByte << 1);
                if ((currentByte & bitSelectorMask) != 0) {
                    reversedByte = (byte) (reversedByte | LSB_IN_BYTE);
                }
                bitSelectorMask = (byte) (bitSelectorMask << 1);
            }
            buffer[n] = reversedByte;
        }
    }

    /**
     * Sets the LCI subelement fields to the default undefined values.
     */
    private void setLciSubelementDefaults() {
        mIsLciValid = false;
        mLatitudeUncertainty = UNCERTAINTY_UNDEFINED;
        mLatitude = 0;
        mLongitudeUncertainty = UNCERTAINTY_UNDEFINED;
        mLongitude = 0;
        mAltitudeType = ALTITUDE_UNDEFINED;
        mAltitudeUncertainty = UNCERTAINTY_UNDEFINED;
        mAltitude = 0;
        mDatum = DATUM_UNDEFINED;
        mLciFlags = 0;
    }

    /**
     * Sets the Z subelement fields to the default values when undefined.
     */
    private void setZSubelementDefaults() {
        mIsZValid = false;
        mExpectedToMove = 0;
        mStaFloorNumber = 0;
        mStaHeightAboveFloorMeters = 0;
        mStaHeightAboveFloorUncertaintyMeters = 0;
    }

    /**
     * Sets the Usage Policy subelement fields to the default undefined values.
     */
    private void setUsageSubelementDefaults() {
        mUsageRetransmit = true;
        mUsageRetentionExpires = false;
        mUsageExtraInfoOnAssociation = false;
    }

    /**
     * Sets the BSSID List subelement fields to the default values when undefined.
     */
    private void setBssidListSubelementDefaults() {
        mIsBssidListValid = false;
        mBssidList = new ArrayList<>();
    }

    /**
     * Sets the LCR Civic Location subelement field to the default undefined value.
     *
     * @hide
     */
    public void setCivicLocationSubelementDefaults() {
        mIsLocationCivicValid = false;
        mCivicLocationCountryCode = "";
        mCivicLocationString = "";
        mCivicLocation = null;
    }

    /**
     * Sets the LCR Map Image subelement field to the default values when undefined.
     */
    private void setMapImageSubelementDefaults() {
        mIsMapImageValid = false;
        mMapImageType = MAP_TYPE_URL_DEFINED;
        mMapImageUrl = null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ResponderLocation other = (ResponderLocation) obj;
        return mIsValid == other.mIsValid
                && mIsLciValid == other.mIsLciValid
                && mIsZValid == other.mIsZValid
                && mIsUsageValid == other.mIsUsageValid
                && mIsBssidListValid == other.mIsBssidListValid
                && mIsLocationCivicValid == other.mIsLocationCivicValid
                && mIsMapImageValid == other.mIsMapImageValid
                && mLatitudeUncertainty == other.mLatitudeUncertainty
                && mLatitude == other.mLatitude
                && mLongitudeUncertainty == other.mLongitudeUncertainty
                && mLongitude == other.mLongitude
                && mAltitudeType == other.mAltitudeType
                && mAltitudeUncertainty == other.mAltitudeUncertainty
                && mAltitude == other.mAltitude
                && mDatum == other.mDatum
                && mLciFlags == other.mLciFlags
                && mExpectedToMove == other.mExpectedToMove
                && mStaFloorNumber == other.mStaFloorNumber
                && mStaHeightAboveFloorMeters == other.mStaHeightAboveFloorMeters
                && mStaHeightAboveFloorUncertaintyMeters
                        == other.mStaHeightAboveFloorUncertaintyMeters
                && mUsageRetransmit == other.mUsageRetransmit
                && mUsageRetentionExpires == other.mUsageRetentionExpires
                && mUsageExtraInfoOnAssociation == other.mUsageExtraInfoOnAssociation
                && mBssidList.equals(other.mBssidList)
                && mCivicLocationCountryCode.equals(other.mCivicLocationCountryCode)
                && mCivicLocationString.equals(other.mCivicLocationString)
                && Objects.equals(mCivicLocation, other.mCivicLocation)
                && mMapImageType == other.mMapImageType
                && Objects.equals(mMapImageUrl, other.mMapImageUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsValid, mIsLciValid, mIsZValid, mIsUsageValid, mIsBssidListValid,
                mIsLocationCivicValid, mIsMapImageValid, mLatitudeUncertainty, mLatitude,
                mLongitudeUncertainty, mLongitude, mAltitudeType, mAltitudeUncertainty, mAltitude,
                mDatum, mLciFlags, mExpectedToMove, mStaFloorNumber, mStaHeightAboveFloorMeters,
                mStaHeightAboveFloorUncertaintyMeters, mUsageRetransmit, mUsageRetentionExpires,
                mUsageExtraInfoOnAssociation, mBssidList, mCivicLocationCountryCode,
                mCivicLocationString, mCivicLocation, mMapImageType, mMapImageUrl);
    }

    /**
     * @return true if the ResponderLocation object is valid and contains useful information
     * relevant to the location of the Responder. If this is ever false, this object will not be
     * available to developers, and have a null value.
     *
     * @hide
     */
    public boolean isValid() {
        return mIsValid;
    }

    /**
     * @return true if the LCI subelement (containing Latitude, Longitude and Altitude) is valid.
     *
     * <p> This method tells us if the responder has provided its Location Configuration
     * Information (LCI) directly, and is useful when an external database of responder locations
     * is not available</p>
     *
     * <p>If isLciSubelementValid() returns true, all the LCI values provided by the corresponding
     * getter methods will have been set as described by the responder, or else if false, they
     * should not be used and will throw an IllegalStateException.</p>
     */
    public boolean isLciSubelementValid() {
        return mIsLciValid;
    }

    /**
     * @return the latitude uncertainty in degrees.
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     *
     * <p> An unknown uncertainty is indicated by 0.</p>
     */
    public double getLatitudeUncertainty() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLatitudeUncertainty(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mLatitudeUncertainty;
    }

    /**
     * @return the latitude in degrees
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     */
    public double getLatitude() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLatitude(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mLatitude;
    }

    /**
     * @return the Longitude uncertainty in degrees.
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     *
     * <p> An unknown uncertainty is indicated by 0.</p>
     */
    public double getLongitudeUncertainty() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLongitudeUncertainty(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mLongitudeUncertainty;
    }

    /**
     * @return the Longitude in degrees..
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     */
    public double getLongitude() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLatitudeUncertainty(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mLongitude;
    }

    /**
     * @return the Altitude type.
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     */
    @AltitudeType
    public int getAltitudeType() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLatitudeUncertainty(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mAltitudeType;
    }

    /**
     * @return the Altitude uncertainty in meters.
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     *
     * <p>An unknown uncertainty is indicated by 0.</p>
     */
    public double getAltitudeUncertainty() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLatitudeUncertainty(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mAltitudeUncertainty;
    }

    /**
     * @return the Altitude in units defined by the altitude type.
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     */
    public double getAltitude() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getAltitude(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mAltitude;
    }

    /**
     * @return the Datum used for the LCI positioning information.
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     */
    @DatumType
    public int getDatum() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getDatum(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mDatum;
    }

    /**
     * @return the LCI sub-element flags (5-bits).
     *
     * Only valid if {@link #isLciSubelementValid()} returns true, or will throw an exception.
     *
     * <p>Note: The flags/version can be extracted by bitwise ANDing this value with the
     * corresponding LCI_FLAGS_MASK_* .</p>
     */
    public int getLciFlags() {
        if (!mIsLciValid) {
            throw new IllegalStateException(
                "getLciFlags(): invoked on an invalid result: mIsLciValid = false)");
        }
        return mLciFlags;
    }

    /**
     * @return if the Z subelement (containing mobility, Floor, Height above floor) is valid.
     */
    public boolean isZsubelementValid() {
        return mIsZValid;
    }

    /**
     * @return an integer representing the mobility of the responder.
     *
     * Only valid if {@link #isZsubelementValid()} returns true, or will throw an exception.
     */
    @ExpectedToMoveType
    public int getExpectedToMove() {
        if (!mIsZValid) {
            throw new IllegalStateException(
                "getExpectedToMove(): invoked on an invalid result: mIsZValid = false)");
        }
        return mExpectedToMove;
    }

    /**
     * @return the Z sub element STA Floor Number.
     *
     * Only valid if {@link #isZsubelementValid()} returns true, or will throw an exception.
     *
     * <p>Note: this number can be positive or negative, with value increments of +/- 1/16 of a
     * floor.</p>.
     */
    public double getStaFloorNumber() {
        if (!mIsZValid) {
            throw new IllegalStateException(
                "getStaFloorNumber(): invoked on an invalid result: mIsZValid = false)");
        }
        return mStaFloorNumber;
    }

    /**
     * @return the Z subelement STA Height above the floor in meters.
     *
     * Only valid if {@link #isZsubelementValid()} returns true, or will throw an exception.
     *
     * <p>This value can be positive or negative. </p>
     */
    public double getStaHeightAboveFloorMeters() {
        if (!mIsZValid) {
            throw new IllegalStateException(
                "getStaHeightAboveFloorMeters(): invoked on an invalid result: mIsZValid = false)");
        }
        return mStaHeightAboveFloorMeters;
    }

    /**
     * @return the Z subelement STA Height above the floor uncertainty in meters.
     *
     * Only valid if {@link #isZsubelementValid()} returns true, or will throw an exception.
     *
     * <p>An unknown uncertainty is indicated by 0.</p>
     */
    public double getStaHeightAboveFloorUncertaintyMeters() {
        if (!mIsZValid) {
            throw new IllegalStateException(
                "getStaHeightAboveFloorUncertaintyMeters():"
                    + "invoked on an invalid result: mIsZValid = false)");
        }
        return mStaHeightAboveFloorUncertaintyMeters;
    }

    /**
     * @return true if the location information received from the responder can be
     * retransmitted to another device, physically separate from the one that received it.
     *
     * @hide
     */
    public boolean getRetransmitPolicyIndication() {
        return mUsageRetransmit;
    }

    /**
     * @return true if location-data received should expire (and be deleted)
     * by the time provided in the getRelativeExpirationTimeHours() method.
     *
     *
     * @hide
     */
    public boolean getRetentionExpiresIndication() {
        return mUsageRetentionExpires;
    }

    /**
     * @return true if there is extra location info available on association.
     *
     * @hide
     */
    @SystemApi
    public boolean getExtraInfoOnAssociationIndication() {
        return mUsageExtraInfoOnAssociation;
    }

    /**
     * @return the list of colocated BSSIDs at the responder.
     *
     * <p> Will return an empty list when there are no bssids listed.
     */
    public List<MacAddress> getColocatedBssids() {
        return mBssidList;
    }

    /**
     * @return the civic location represented as an {@link Address} object (best effort).
     *
     * <p> Will return a {@code null} when there is no Civic Location define defined.
     *
     * @hide
     */
    @Nullable
    public Address toCivicLocationAddress() {
        if (mCivicLocation != null && mCivicLocation.isValid()) {
            return mCivicLocation.toAddress();
        } else {
            return null;
        }
    }


    /**
     * @return the civic location two upper-case ASCII character country code defined in ISO 3166.
     *
     * <p> Will return a {@code null} when there is no country code defined.
     *
     * @hide
     */
    @Nullable
    public String getCivicLocationCountryCode() {
        return mCivicLocationCountryCode;
    }

    /**
     * @return the value of the Civic Location String associated with a key.
     *
     * <p> Will return a {@code null} when there is no value associated with the key provided.
     *
     * @param key used to find a corresponding value in the Civic Location Tuple list
     *
     * @hide
     */
    @Nullable
    public String getCivicLocationElementValue(@CivicLocationKeysType int key) {
        return mCivicLocation.getCivicElementValue(key);
    }

    /**
     * @return the Map Image file type, referred to by getMapImageUrl(), encoded as an integer.
     */
    @MapImageType
    public int getMapImageType() {
        return mMapImageType;
    }

    /**
     * @return a Url referencing a map-file showing the local floor plan.
     *
     * <p> Will return a {@code null} when there is no URL defined.
     */
    @Nullable
    public URL getMapImageUrl() {
        return mMapImageUrl;
    }
}
