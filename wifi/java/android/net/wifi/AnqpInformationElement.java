/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi;

/**
 * This object contains the payload of an ANQP element.
 * Vendor id is the vendor ID for the element, or 0 if it is an 802.11(u) element.
 * Hotspot 2.0 uses the WFA Vendor ID which is 0x506f9a
 * The payload contains the bytes of the payload, starting after the length octet(s).
 * @hide
 */
public class AnqpInformationElement {
    public static final int HOTSPOT20_VENDOR_ID = 0x506f9a;

    public static final int ANQP_QUERY_LIST = 256;
    public static final int ANQP_CAPABILITY_LIST = 257;
    public static final int ANQP_VENUE_NAME = 258;
    public static final int ANQP_EMERGENCY_NUMBER = 259;
    public static final int ANQP_NWK_AUTH_TYPE = 260;
    public static final int ANQP_ROAMING_CONSORTIUM = 261;
    public static final int ANQP_IP_ADDR_AVAILABILITY = 262;
    public static final int ANQP_NAI_REALM = 263;
    public static final int ANQP_3GPP_NETWORK = 264;
    public static final int ANQP_GEO_LOC = 265;
    public static final int ANQP_CIVIC_LOC = 266;
    public static final int ANQP_LOC_URI = 267;
    public static final int ANQP_DOM_NAME = 268;
    public static final int ANQP_EMERGENCY_ALERT = 269;
    public static final int ANQP_TDLS_CAP = 270;
    public static final int ANQP_EMERGENCY_NAI = 271;
    public static final int ANQP_NEIGHBOR_REPORT = 272;
    public static final int ANQP_VENDOR_SPEC = 56797;

    public static final int HS_QUERY_LIST = 1;
    public static final int HS_CAPABILITY_LIST = 2;
    public static final int HS_FRIENDLY_NAME = 3;
    public static final int HS_WAN_METRICS = 4;
    public static final int HS_CONN_CAPABILITY = 5;
    public static final int HS_NAI_HOME_REALM_QUERY = 6;
    public static final int HS_OPERATING_CLASS = 7;
    public static final int HS_OSU_PROVIDERS = 8;
    public static final int HS_ICON_REQUEST = 10;
    public static final int HS_ICON_FILE = 11;

    private final int mVendorId;
    private final int mElementId;
    private final byte[] mPayload;

    public AnqpInformationElement(int vendorId, int elementId, byte[] payload) {
        mVendorId = vendorId;
        mElementId = elementId;
        mPayload = payload;
    }

    public int getVendorId() {
        return mVendorId;
    }

    public int getElementId() {
        return mElementId;
    }

    public byte[] getPayload() {
        return mPayload;
    }
}
