/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony;


/**
 *  Implement the WSP data type decoder.
 *
 *  @hide
 */
public class WspTypeDecoder {

    private static final int WAP_PDU_SHORT_LENGTH_MAX = 30;
    private static final int WAP_PDU_LENGTH_QUOTE = 31;

    public static final int PDU_TYPE_PUSH = 0x06;
    public static final int PDU_TYPE_CONFIRMED_PUSH = 0x07;

    // TODO we should have mapping between those binary code and mime type string.
    //  see http://www.openmobilealliance.org/tech/omna/omna-wsp-content-type.aspx

    public static final int CONTENT_TYPE_B_DRM_RIGHTS_XML = 0x4a;
    public static final int CONTENT_TYPE_B_DRM_RIGHTS_WBXML = 0x4b;
    public static final int CONTENT_TYPE_B_PUSH_SI = 0x2e;
    public static final int CONTENT_TYPE_B_PUSH_SL = 0x30;
    public static final int CONTENT_TYPE_B_PUSH_CO = 0x32;
    public static final int CONTENT_TYPE_B_MMS = 0x3e;
    public static final int CONTENT_TYPE_B_VND_DOCOMO_PF = 0x0310;

    public static final String CONTENT_MIME_TYPE_B_DRM_RIGHTS_XML =
            "application/vnd.oma.drm.rights+xml";
    public static final String CONTENT_MIME_TYPE_B_DRM_RIGHTS_WBXML =
            "application/vnd.oma.drm.rights+wbxml";
    public static final String CONTENT_MIME_TYPE_B_PUSH_SI = "application/vnd.wap.sic";
    public static final String CONTENT_MIME_TYPE_B_PUSH_SL = "application/vnd.wap.slc";
    public static final String CONTENT_MIME_TYPE_B_PUSH_CO = "application/vnd.wap.coc";
    public static final String CONTENT_MIME_TYPE_B_MMS = "application/vnd.wap.mms-message";
    public static final String CONTENT_MIME_TYPE_B_VND_DOCOMO_PF = "application/vnd.docomo.pf";

    public static final int PARAMETER_ID_X_WAP_APPLICATION_ID = 0x2f;


    byte[] wspData;
    int    dataLength;
    long   unsigned32bit;
    String stringValue;

    public WspTypeDecoder(byte[] pdu) {
        wspData = pdu;
    }

    /**
     * Decode the "Text-string" type for WSP pdu
     *
     * @param startIndex The starting position of the "Text-string" in this pdu
     *
     * @return false when error(not a Text-string) occur
     *         return value can be retrieved by getValueString() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeTextString(int startIndex) {
        int index = startIndex;
        while (wspData[index] != 0) {
            index++;
        }
        dataLength  = index - startIndex + 1;
        if (wspData[startIndex] == 127) {
            stringValue = new String(wspData, startIndex+1, dataLength - 2);
        } else {
            stringValue = new String(wspData, startIndex, dataLength - 1);
        }
        return true;
    }

    /**
     * Decode the "Short-integer" type for WSP pdu
     *
     * @param startIndex The starting position of the "Short-integer" in this pdu
     *
     * @return false when error(not a Short-integer) occur
     *         return value can be retrieved by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeShortInteger(int startIndex) {
        if ((wspData[startIndex] & 0x80) == 0) {
            return false;
        }
        unsigned32bit = wspData[startIndex] & 0x7f;
        dataLength = 1;
        return true;
    }

    /**
     * Decode the "Long-integer" type for WSP pdu
     *
     * @param startIndex The starting position of the "Long-integer" in this pdu
     *
     * @return false when error(not a Long-integer) occur
     *         return value can be retrieved by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeLongInteger(int startIndex) {
        int lengthMultiOctet = wspData[startIndex] & 0xff;

        if (lengthMultiOctet > WAP_PDU_SHORT_LENGTH_MAX) {
            return false;
        }
        unsigned32bit = 0;
        for (int i=1; i<=lengthMultiOctet; i++) {
            unsigned32bit = (unsigned32bit << 8) | (wspData[startIndex+i] & 0xff);
        }
        dataLength = 1+lengthMultiOctet;
        return true;
    }

    /**
     * Decode the "Integer-Value" type for WSP pdu
     *
     * @param startIndex The starting position of the "Integer-Value" in this pdu
     *
     * @return false when error(not a Integer-Value) occur
     *         return value can be retrieved by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeIntegerValue(int startIndex) {
        if (decodeShortInteger(startIndex) == true) {
            return true;
        }
        return decodeLongInteger(startIndex);
    }

    /**
     * Decode the "Uintvar-integer" type for WSP pdu
     *
     * @param startIndex The starting position of the "Uintvar-integer" in this pdu
     *
     * @return false when error(not a Uintvar-integer) occur
     *         return value can be retrieved by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeUintvarInteger(int startIndex) {
        int  index = startIndex;

        unsigned32bit = 0;
        while ((wspData[index] & 0x80) != 0) {
            if ((index - startIndex) >= 4) {
                return false;
            }
            unsigned32bit = (unsigned32bit << 7) | (wspData[index] & 0x7f);
            index++;
        }
        unsigned32bit = (unsigned32bit << 7) | (wspData[index] & 0x7f);
        dataLength = index - startIndex + 1;
        return true;
    }

    /**
     * Decode the "Value-length" type for WSP pdu
     *
     * @param startIndex The starting position of the "Value-length" in this pdu
     *
     * @return false when error(not a Value-length) occur
     *         return value can be retrieved by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeValueLength(int startIndex) {
        if ((wspData[startIndex] & 0xff) > WAP_PDU_LENGTH_QUOTE) {
            return false;
        }
        if (wspData[startIndex] < WAP_PDU_LENGTH_QUOTE) {
            unsigned32bit = wspData[startIndex];
            dataLength = 1;
        } else {
            decodeUintvarInteger(startIndex+1);
            dataLength ++;
        }
        return true;
    }

    /**
    * Decode the "Extension-media" type for WSP PDU.
    *
    * @param startIndex The starting position of the "Extension-media" in this PDU.
    *
    * @return false on error, such as if there is no Extension-media at startIndex.
    * Side-effects: updates stringValue (available with getValueString()), which will be
    * null on error. The length of the data in the PDU is available with getValue32(), 0
    * on error.
    */
    public boolean decodeExtensionMedia(int startIndex) {
        int index = startIndex;
        dataLength = 0;
        stringValue = null;
        int length = wspData.length;
        boolean rtrn = index < length;

        while (index < length && wspData[index] != 0) {
            index++;
        }

        dataLength  = index - startIndex + 1;
        stringValue = new String(wspData, startIndex, dataLength - 1);

        return rtrn;
    }

    /**
     * Decode the "Constrained-encoding" type for WSP pdu
     *
     * @param startIndex The starting position of the "Constrained-encoding" in this pdu
     *
     * @return false when error(not a Constrained-encoding) occur
     *         return value can be retrieved first by getValueString() and second by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeConstrainedEncoding(int startIndex) {
        if (decodeShortInteger(startIndex) == true) {
            stringValue = null;
            return true;
        }
        return decodeExtensionMedia(startIndex);
    }

    /**
     * Decode the "Content-type" type for WSP pdu
     *
     * @param startIndex The starting position of the "Content-type" in this pdu
     *
     * @return false when error(not a Content-type) occur
     *         return value can be retrieved first by getValueString() and second by getValue32()
     *         method length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeContentType(int startIndex) {
        int mediaPrefixLength;
        long mediaFieldLength;

        if (decodeValueLength(startIndex) == false) {
            return decodeConstrainedEncoding(startIndex);
        }
        mediaPrefixLength = getDecodedDataLength();
        mediaFieldLength = getValue32();
        if (decodeIntegerValue(startIndex + mediaPrefixLength) == true) {
            dataLength += mediaPrefixLength;
            stringValue = null;
            return true;
        }
        if (decodeExtensionMedia(startIndex + mediaPrefixLength) == true) {
            dataLength += mediaPrefixLength;
            return true;
        }
        return false;
    }

    /**
     * Decode the "Content length" type for WSP pdu
     *
     * @param startIndex The starting position of the "Content length" in this pdu
     *
     * @return false when error(not a Content length) occur
     *         return value can be retrieved by getValue32() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeContentLength(int startIndex) {
        return decodeIntegerValue(startIndex);
    }

    /**
     * Decode the "Content location" type for WSP pdu
     *
     * @param startIndex The starting position of the "Content location" in this pdu
     *
     * @return false when error(not a Content location) occur
     *         return value can be retrieved by getValueString() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeContentLocation(int startIndex) {
        return decodeTextString(startIndex);
    }

    /**
     * Decode the "X-Wap-Application-Id" type for WSP pdu
     *
     * @param startIndex The starting position of the "X-Wap-Application-Id" in this pdu
     *
     * @return false when error(not a X-Wap-Application-Id) occur
     *         return value can be retrieved first by getValueString() and second by getValue32()
     *         method length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeXWapApplicationId(int startIndex) {
        if (decodeIntegerValue(startIndex) == true) {
            stringValue = null;
            return true;
        }
        return decodeTextString(startIndex);
    }

    /**
     * Decode the "X-Wap-Content-URI" type for WSP pdu
     *
     * @param startIndex The starting position of the "X-Wap-Content-URI" in this pdu
     *
     * @return false when error(not a X-Wap-Content-URI) occur
     *         return value can be retrieved by getValueString() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeXWapContentURI(int startIndex) {
        return decodeTextString(startIndex);
    }

    /**
     * Decode the "X-Wap-Initiator-URI" type for WSP pdu
     *
     * @param startIndex The starting position of the "X-Wap-Initiator-URI" in this pdu
     *
     * @return false when error(not a X-Wap-Initiator-URI) occur
     *         return value can be retrieved by getValueString() method
     *         length of data in pdu can be retrieved by getValue32() method
     */
    public boolean decodeXWapInitiatorURI(int startIndex) {
        return decodeTextString(startIndex);
    }

    /**
     * The data length of latest operation.
     */
    public int getDecodedDataLength() {
        return dataLength;
    }

    /**
     * The 32-bits result of latest operation.
     */
    public long getValue32() {
        return unsigned32bit;
    }

    /**
     * The String result of latest operation.
     */
    public String getValueString() {
        return stringValue;
    }
}
