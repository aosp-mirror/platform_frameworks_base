/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import static android.telephony.SmsCbEtwsInfo.ETWS_WARNING_TYPE_EARTHQUAKE;
import static android.telephony.SmsCbEtwsInfo.ETWS_WARNING_TYPE_EARTHQUAKE_AND_TSUNAMI;
import static android.telephony.SmsCbEtwsInfo.ETWS_WARNING_TYPE_OTHER_EMERGENCY;
import static android.telephony.SmsCbEtwsInfo.ETWS_WARNING_TYPE_TEST_MESSAGE;
import static android.telephony.SmsCbEtwsInfo.ETWS_WARNING_TYPE_TSUNAMI;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.telephony.SmsCbLocation;
import android.telephony.SmsCbMessage;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.telephony.CbGeoUtils;
import com.android.internal.telephony.CbGeoUtils.Circle;
import com.android.internal.telephony.CbGeoUtils.Geometry;
import com.android.internal.telephony.CbGeoUtils.LatLng;
import com.android.internal.telephony.CbGeoUtils.Polygon;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.gsm.GsmSmsCbMessage.GeoFencingTriggerMessage.CellBroadcastIdentity;
import com.android.internal.telephony.gsm.SmsCbHeader.DataCodingScheme;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parses a GSM or UMTS format SMS-CB message into an {@link SmsCbMessage} object. The class is
 * public because {@link #createSmsCbMessage(SmsCbLocation, byte[][])} is used by some test cases.
 */
public class GsmSmsCbMessage {
    private static final String TAG = GsmSmsCbMessage.class.getSimpleName();

    private static final char CARRIAGE_RETURN = 0x0d;

    private static final int PDU_BODY_PAGE_LENGTH = 82;

    /** Utility class with only static methods. */
    private GsmSmsCbMessage() { }

    /**
     * Get built-in ETWS primary messages by category. ETWS primary message does not contain text,
     * so we have to show the pre-built messages to the user.
     *
     * @param context Device context
     * @param category ETWS message category defined in SmsCbConstants
     * @return ETWS text message in string. Return an empty string if no match.
     */
    private static String getEtwsPrimaryMessage(Context context, int category) {
        final Resources r = context.getResources();
        switch (category) {
            case ETWS_WARNING_TYPE_EARTHQUAKE:
                return r.getString(R.string.etws_primary_default_message_earthquake);
            case ETWS_WARNING_TYPE_TSUNAMI:
                return r.getString(R.string.etws_primary_default_message_tsunami);
            case ETWS_WARNING_TYPE_EARTHQUAKE_AND_TSUNAMI:
                return r.getString(R.string.etws_primary_default_message_earthquake_and_tsunami);
            case ETWS_WARNING_TYPE_TEST_MESSAGE:
                return r.getString(R.string.etws_primary_default_message_test);
            case ETWS_WARNING_TYPE_OTHER_EMERGENCY:
                return r.getString(R.string.etws_primary_default_message_others);
            default:
                return "";
        }
    }

    /**
     * Create a new SmsCbMessage object from a header object plus one or more received PDUs.
     *
     * @param pdus PDU bytes
     */
    public static SmsCbMessage createSmsCbMessage(Context context, SmsCbHeader header,
            SmsCbLocation location, byte[][] pdus)
            throws IllegalArgumentException {
        long receivedTimeMillis = System.currentTimeMillis();
        if (header.isEtwsPrimaryNotification()) {
            // ETSI TS 23.041 ETWS Primary Notification message
            // ETWS primary message only contains 4 fields including serial number,
            // message identifier, warning type, and warning security information.
            // There is no field for the content/text so we get the text from the resources.
            return new SmsCbMessage(SmsCbMessage.MESSAGE_FORMAT_3GPP, header.getGeographicalScope(),
                    header.getSerialNumber(), location, header.getServiceCategory(), null,
                    getEtwsPrimaryMessage(context, header.getEtwsInfo().getWarningType()),
                    SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY, header.getEtwsInfo(),
                    header.getCmasInfo(), 0, null /* geometries */, receivedTimeMillis);
        } else if (header.isUmtsFormat()) {
            // UMTS format has only 1 PDU
            byte[] pdu = pdus[0];
            Pair<String, String> cbData = parseUmtsBody(header, pdu);
            String language = cbData.first;
            String body = cbData.second;
            int priority = header.isEmergencyMessage() ? SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY
                    : SmsCbMessage.MESSAGE_PRIORITY_NORMAL;
            int nrPages = pdu[SmsCbHeader.PDU_HEADER_LENGTH];
            int wacDataOffset = SmsCbHeader.PDU_HEADER_LENGTH
                    + 1 // number of pages
                    + (PDU_BODY_PAGE_LENGTH + 1) * nrPages; // cb data

            // Has Warning Area Coordinates information
            List<Geometry> geometries = null;
            int maximumWaitingTimeSec = 255;
            if (pdu.length > wacDataOffset) {
                try {
                    Pair<Integer, List<Geometry>> wac = parseWarningAreaCoordinates(pdu,
                            wacDataOffset);
                    maximumWaitingTimeSec = wac.first;
                    geometries = wac.second;
                } catch (Exception ex) {
                    // Catch the exception here, the message will be considered as having no WAC
                    // information which means the message will be broadcasted directly.
                    Slog.e(TAG, "Can't parse warning area coordinates, ex = " + ex.toString());
                }
            }

            return new SmsCbMessage(SmsCbMessage.MESSAGE_FORMAT_3GPP,
                    header.getGeographicalScope(), header.getSerialNumber(), location,
                    header.getServiceCategory(), language, body, priority,
                    header.getEtwsInfo(), header.getCmasInfo(), maximumWaitingTimeSec, geometries,
                    receivedTimeMillis);
        } else {
            String language = null;
            StringBuilder sb = new StringBuilder();
            for (byte[] pdu : pdus) {
                Pair<String, String> p = parseGsmBody(header, pdu);
                language = p.first;
                sb.append(p.second);
            }
            int priority = header.isEmergencyMessage() ? SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY
                    : SmsCbMessage.MESSAGE_PRIORITY_NORMAL;

            return new SmsCbMessage(SmsCbMessage.MESSAGE_FORMAT_3GPP,
                    header.getGeographicalScope(), header.getSerialNumber(), location,
                    header.getServiceCategory(), language, sb.toString(), priority,
                    header.getEtwsInfo(), header.getCmasInfo(), 0, null /* geometries */,
                    receivedTimeMillis);
        }
    }

    /**
     * Parse WEA Handset Action Message(WHAM) a.k.a geo-fencing trigger message.
     *
     * WEA Handset Action Message(WHAM) is a cell broadcast service message broadcast by the network
     * to direct devices to perform a geo-fencing check on selected alerts.
     *
     * WEA Handset Action Message(WHAM) requirements from ATIS-0700041 section 4
     * 1. The Warning Message contents of a WHAM shall be in Cell Broadcast(CB) data format as
     * defined in TS 23.041.
     * 2. The Warning Message Contents of WHAM shall be limited to one CB page(max 20 referenced
     * WEA messages).
     * 3. The broadcast area for a WHAM shall be the union of the broadcast areas of the referenced
     * WEA message.
     * @param pdu cell broadcast pdu, including the header
     * @return {@link GeoFencingTriggerMessage} instance
     */
    public static GeoFencingTriggerMessage createGeoFencingTriggerMessage(byte[] pdu) {
        try {
            // Header length + 1(number of page). ATIS-0700041 define the number of page of
            // geo-fencing trigger message is 1.
            int whamOffset = SmsCbHeader.PDU_HEADER_LENGTH + 1;

            BitStreamReader bitReader = new BitStreamReader(pdu, whamOffset);
            int type = bitReader.read(4);
            int length = bitReader.read(7);
            // Skip the remained 5 bits
            bitReader.skip();

            int messageIdentifierCount = (length - 2) * 8 / 32;
            List<CellBroadcastIdentity> cbIdentifiers = new ArrayList<>();
            for (int i = 0; i < messageIdentifierCount; i++) {
                // Both messageIdentifier and serialNumber are 16 bits integers.
                // ATIS-0700041 Section 5.1.6
                int messageIdentifier = bitReader.read(16);
                int serialNumber = bitReader.read(16);
                cbIdentifiers.add(new CellBroadcastIdentity(messageIdentifier, serialNumber));
            }
            return new GeoFencingTriggerMessage(type, cbIdentifiers);
        } catch (Exception ex) {
            Slog.e(TAG, "create geo-fencing trigger failed, ex = " + ex.toString());
            return null;
        }
    }

    /**
     * Parse the broadcast area and maximum wait time from the Warning Area Coordinates TLV.
     *
     * @param pdu Warning Area Coordinates TLV.
     * @param wacOffset the offset of Warning Area Coordinates TLV.
     * @return a pair with the first element is maximum wait time and the second is the broadcast
     * area. The default value of the maximum wait time is 255 which means use the device default
     * value.
     */
    private static Pair<Integer, List<Geometry>> parseWarningAreaCoordinates(
            byte[] pdu, int wacOffset) {
        // little-endian
        int wacDataLength = ((pdu[wacOffset + 1] & 0xff) << 8) | (pdu[wacOffset] & 0xff);
        int offset = wacOffset + 2;

        if (offset + wacDataLength > pdu.length) {
            throw new IllegalArgumentException("Invalid wac data, expected the length of pdu at"
                    + "least " + offset + wacDataLength + ", actual is " + pdu.length);
        }

        BitStreamReader bitReader = new BitStreamReader(pdu, offset);

        int maximumWaitTimeSec = SmsCbMessage.MAXIMUM_WAIT_TIME_NOT_SET;

        List<Geometry> geo = new ArrayList<>();
        int remainedBytes = wacDataLength;
        while (remainedBytes > 0) {
            int type = bitReader.read(4);
            int length = bitReader.read(10);
            remainedBytes -= length;
            // Skip the 2 remained bits
            bitReader.skip();

            switch (type) {
                case CbGeoUtils.GEO_FENCING_MAXIMUM_WAIT_TIME:
                    maximumWaitTimeSec = bitReader.read(8);
                    break;
                case CbGeoUtils.GEOMETRY_TYPE_POLYGON:
                    List<LatLng> latLngs = new ArrayList<>();
                    // Each coordinate is represented by 44 bits integer.
                    // ATIS-0700041 5.2.4 Coordinate coding
                    int n = (length - 2) * 8 / 44;
                    for (int i = 0; i < n; i++) {
                        latLngs.add(getLatLng(bitReader));
                    }
                    // Skip the padding bits
                    bitReader.skip();
                    geo.add(new Polygon(latLngs));
                    break;
                case CbGeoUtils.GEOMETRY_TYPE_CIRCLE:
                    LatLng center = getLatLng(bitReader);
                    // radius = (wacRadius / 2^6). The unit of wacRadius is km, we use meter as the
                    // distance unit during geo-fencing.
                    // ATIS-0700041 5.2.5 radius coding
                    double radius = (bitReader.read(20) * 1.0 / (1 << 6)) * 1000.0;
                    geo.add(new Circle(center, radius));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported geoType = " + type);
            }
        }
        return new Pair(maximumWaitTimeSec, geo);
    }

    /**
     * The coordinate is (latitude, longitude), represented by a 44 bits integer.
     * The coding is defined in ATIS-0700041 5.2.4
     * @param bitReader
     * @return coordinate (latitude, longitude)
     */
    private static LatLng getLatLng(BitStreamReader bitReader) {
        // wacLatitude = floor(((latitude + 90) / 180) * 2^22)
        // wacLongitude = floor(((longitude + 180) / 360) * 2^22)
        int wacLat = bitReader.read(22);
        int wacLng = bitReader.read(22);

        // latitude = wacLatitude * 180 / 2^22 - 90
        // longitude = wacLongitude * 360 / 2^22 -180
        return new LatLng((wacLat * 180.0 / (1 << 22)) - 90, (wacLng * 360.0 / (1 << 22) - 180));
    }

    /**
     * Parse and unpack the UMTS body text according to the encoding in the data coding scheme.
     *
     * @param header the message header to use
     * @param pdu the PDU to decode
     * @return a pair of string containing the language and body of the message in order
     */
    private static Pair<String, String> parseUmtsBody(SmsCbHeader header, byte[] pdu) {
        // Payload may contain multiple pages
        int nrPages = pdu[SmsCbHeader.PDU_HEADER_LENGTH];
        String language = header.getDataCodingSchemeStructedData().language;

        if (pdu.length < SmsCbHeader.PDU_HEADER_LENGTH + 1 + (PDU_BODY_PAGE_LENGTH + 1)
                * nrPages) {
            throw new IllegalArgumentException("Pdu length " + pdu.length + " does not match "
                    + nrPages + " pages");
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < nrPages; i++) {
            // Each page is 82 bytes followed by a length octet indicating
            // the number of useful octets within those 82
            int offset = SmsCbHeader.PDU_HEADER_LENGTH + 1 + (PDU_BODY_PAGE_LENGTH + 1) * i;
            int length = pdu[offset + PDU_BODY_PAGE_LENGTH];

            if (length > PDU_BODY_PAGE_LENGTH) {
                throw new IllegalArgumentException("Page length " + length
                        + " exceeds maximum value " + PDU_BODY_PAGE_LENGTH);
            }

            Pair<String, String> p = unpackBody(pdu, offset, length,
                    header.getDataCodingSchemeStructedData());
            language = p.first;
            sb.append(p.second);
        }
        return new Pair(language, sb.toString());

    }

    /**
     * Parse and unpack the GSM body text according to the encoding in the data coding scheme.
     * @param header the message header to use
     * @param pdu the PDU to decode
     * @return a pair of string containing the language and body of the message in order
     */
    private static Pair<String, String> parseGsmBody(SmsCbHeader header, byte[] pdu) {
        // Payload is one single page
        int offset = SmsCbHeader.PDU_HEADER_LENGTH;
        int length = pdu.length - offset;
        return unpackBody(pdu, offset, length, header.getDataCodingSchemeStructedData());
    }

    /**
     * Unpack body text from the pdu using the given encoding, position and length within the pdu.
     *
     * @param pdu The pdu
     * @param offset Position of the first byte to unpack
     * @param length Number of bytes to unpack
     * @param dcs data coding scheme
     * @return a Pair of Strings containing the language and body of the message
     */
    private static Pair<String, String> unpackBody(byte[] pdu, int offset, int length,
            DataCodingScheme dcs) {
        String body = null;

        String language = dcs.language;
        switch (dcs.encoding) {
            case SmsConstants.ENCODING_7BIT:
                body = GsmAlphabet.gsm7BitPackedToString(pdu, offset, length * 8 / 7);

                if (dcs.hasLanguageIndicator && body != null && body.length() > 2) {
                    // Language is two GSM characters followed by a CR.
                    // The actual body text is offset by 3 characters.
                    language = body.substring(0, 2);
                    body = body.substring(3);
                }
                break;

            case SmsConstants.ENCODING_16BIT:
                if (dcs.hasLanguageIndicator && pdu.length >= offset + 2) {
                    // Language is two GSM characters.
                    // The actual body text is offset by 2 bytes.
                    language = GsmAlphabet.gsm7BitPackedToString(pdu, offset, 2);
                    offset += 2;
                    length -= 2;
                }

                try {
                    body = new String(pdu, offset, (length & 0xfffe), "utf-16");
                } catch (UnsupportedEncodingException e) {
                    // Apparently it wasn't valid UTF-16.
                    throw new IllegalArgumentException("Error decoding UTF-16 message", e);
                }
                break;

            default:
                break;
        }

        if (body != null) {
            // Remove trailing carriage return
            for (int i = body.length() - 1; i >= 0; i--) {
                if (body.charAt(i) != CARRIAGE_RETURN) {
                    body = body.substring(0, i + 1);
                    break;
                }
            }
        } else {
            body = "";
        }

        return new Pair<String, String>(language, body);
    }

    /** A class use to facilitate the processing of bits stream data. */
    private static final class BitStreamReader {
        /** The bits stream represent by a bytes array. */
        private final byte[] mData;

        /** The offset of the current byte. */
        private int mCurrentOffset;

        /**
         * The remained bits of the current byte which have not been read. The most significant
         * will be read first, so the remained bits are always the least significant bits.
         */
        private int mRemainedBit;

        /**
         * Constructor
         * @param data bit stream data represent by byte array.
         * @param offset the offset of the first byte.
         */
        BitStreamReader(byte[] data, int offset) {
            mData = data;
            mCurrentOffset = offset;
            mRemainedBit = 8;
        }

        /**
         * Read the first {@code count} bits.
         * @param count the number of bits need to read
         * @return {@code bits} represent by an 32-bits integer, therefore {@code count} must be no
         * greater than 32.
         */
        public int read(int count) throws IndexOutOfBoundsException {
            int val = 0;
            while (count > 0) {
                if (count >= mRemainedBit) {
                    val <<= mRemainedBit;
                    val |= mData[mCurrentOffset] & ((1 << mRemainedBit) - 1);
                    count -= mRemainedBit;
                    mRemainedBit = 8;
                    ++mCurrentOffset;
                } else {
                    val <<= count;
                    val |= (mData[mCurrentOffset] & ((1 << mRemainedBit) - 1))
                            >> (mRemainedBit - count);
                    mRemainedBit -= count;
                    count = 0;
                }
            }
            return val;
        }

        /**
         * Skip the current bytes if the remained bits is less than 8. This is useful when
         * processing the padding or reserved bits.
         */
        public void skip() {
            if (mRemainedBit < 8) {
                mRemainedBit = 8;
                ++mCurrentOffset;
            }
        }
    }

    static final class GeoFencingTriggerMessage {
        /**
         * Indicate the list of active alerts share their warning area coordinates which means the
         * broadcast area is the union of the broadcast areas of the active alerts in this list.
         */
        public static final int TYPE_ACTIVE_ALERT_SHARE_WAC = 2;

        public final int type;
        public final List<CellBroadcastIdentity> cbIdentifiers;

        GeoFencingTriggerMessage(int type, @NonNull List<CellBroadcastIdentity> cbIdentifiers) {
            this.type = type;
            this.cbIdentifiers = cbIdentifiers;
        }

        boolean shouldShareBroadcastArea() {
            return type == TYPE_ACTIVE_ALERT_SHARE_WAC;
        }

        static final class CellBroadcastIdentity {
            public final int messageIdentifier;
            public final int serialNumber;
            CellBroadcastIdentity(int messageIdentifier, int serialNumber) {
                this.messageIdentifier = messageIdentifier;
                this.serialNumber = serialNumber;
            }
        }

        @Override
        public String toString() {
            String identifiers = cbIdentifiers.stream()
                    .map(cbIdentifier ->String.format("(msgId = %d, serial = %d)",
                            cbIdentifier.messageIdentifier, cbIdentifier.serialNumber))
                    .collect(Collectors.joining(","));
            return "triggerType=" + type + " identifiers=" + identifiers;
        }
    }
}
