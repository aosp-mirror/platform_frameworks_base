/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.cdma.sms;

import com.android.internal.telephony.SmsHeader;

/*
 * The SMSDataCoding class encodes and decodes CDMA SMS messages.
 */
public class SmsDataCoding {
    private final static String TAG = "CDMA_SMS_JNI";

    private final static int CDMA_SMS_WMS_MASK_BD_NULL             =   0x00000000;
    private final static int CDMA_SMS_WMS_MASK_BD_MSG_ID           =   0x00000001;
    private final static int CDMA_SMS_WMS_MASK_BD_USER_DATA        =   0x00000002;
//    private final static int CDMA_SMS_WMS_MASK_BD_USER_RESP        =   0x00000004;
    private final static int CDMA_SMS_WMS_MASK_BD_MC_TIME          =   0x00000008;
//    private final static int CDMA_SMS_WMS_MASK_BD_VALID_ABS        =   0x00000010;
//    private final static int CDMA_SMS_WMS_MASK_BD_VALID_REL        =   0x00000020;
//    private final static int CDMA_SMS_WMS_MASK_BD_DEFER_ABS        =   0x00000040;
//    private final static int CDMA_SMS_WMS_MASK_BD_DEFER_REL        =   0x00000080;
//    private final static int CDMA_SMS_WMS_MASK_BD_PRIORITY         =   0x00000100;
//    private final static int CDMA_SMS_WMS_MASK_BD_PRIVACY          =   0x00000200;
//    private final static int CDMA_SMS_WMS_MASK_BD_REPLY_OPTION     =   0x00000400;
    private final static int CDMA_SMS_WMS_MASK_BD_NUM_OF_MSGS      =   0x00000800;
//    private final static int CDMA_SMS_WMS_MASK_BD_ALERT            =   0x00001000;
//    private final static int CDMA_SMS_WMS_MASK_BD_LANGUAGE         =   0x00002000;
    private final static int CDMA_SMS_WMS_MASK_BD_CALLBACK         =   0x00004000;
    private final static int CDMA_SMS_WMS_MASK_BD_DISPLAY_MODE     =   0x00008000;
//    private final static int CDMA_SMS_WMS_MASK_BD_SCPT_DATA        =   0x00010000;
//    private final static int CDMA_SMS_WMS_MASK_BD_SCPT_RESULT      =   0x00020000;
//    private final static int CDMA_SMS_WMS_MASK_BD_DEPOSIT_INDEX    =   0x00040000;
//    private final static int CDMA_SMS_WMS_MASK_BD_DELIVERY_STATUS  =   0x00080000;
//    private final static int CDMA_SMS_WMS_MASK_BD_IP_ADDRESS       =   0x10000000;
//    private final static int CDMA_SMS_WMS_MASK_BD_RSN_NO_NOTIFY    =   0x20000000;
//    private final static int CDMA_SMS_WMS_MASK_BD_OTHER            =   0x40000000;

    /**
     * Successful operation.
     */
    private static final int JNI_CDMA_SMS_SUCCESS = 0;

    /**
     * General failure.
     */
    private static final int JNI_CDMA_SMS_FAILURE = 1;

    /**
     * Data length is out of length.
     */
    private static final int JNI_CDMA_SMS_DATA_LEN_OUT_OF_RANGE = 2;

    /**
     * Class name unknown.
     */
    private static final int JNI_CDMA_SMS_CLASS_UNKNOWN = 3;

    /**
     * Field ID unknown.
     */
    private static final int JNI_CDMA_SMS_FIELD_ID_UNKNOWN = 4;

    /**
     * Memory allocation failed.
     */
    private static final int JNI_CDMA_SMS_OUT_OF_MEMORY = 5;

    /**
     * Encode SMS.
     *
     * @param bearerData    an instance of BearerData.
     *
     * @return the encoded SMS as byte[].
     */
    public static byte[] encodeCdmaSms(BearerData bearerData) {
        byte[] encodedSms;

        if( nativeCdmaSmsConstructClientBD() == JNI_CDMA_SMS_FAILURE){
            return null;
        }

        // check bearer data and generate bit mask
        generateBearerDataBitMask(bearerData);
        encodedSms = startEncoding(bearerData);

        if( nativeCdmaSmsDestructClientBD() == JNI_CDMA_SMS_FAILURE){
            return null;
        }
        return encodedSms;
    }

    /**
     * Decode SMS.
     *
     * @param SmsData    the encoded SMS.
     *
     * @return an instance of BearerData.
     */
    public static BearerData decodeCdmaSms(byte[] SmsData) {
        BearerData bearerData;

        if( nativeCdmaSmsConstructClientBD() == JNI_CDMA_SMS_FAILURE){
            return null;
        }

        bearerData = startDecoding(SmsData);

        if( nativeCdmaSmsDestructClientBD() == JNI_CDMA_SMS_FAILURE){
            return null;
        }
        return bearerData;
    }

    private static void generateBearerDataBitMask(BearerData bearerData) {
        // initial
        bearerData.mask = CDMA_SMS_WMS_MASK_BD_NULL;

        // check message type
        if (bearerData.messageType != 0){
            bearerData.mask |= CDMA_SMS_WMS_MASK_BD_MSG_ID;
        }

        // check mUserData
        if (bearerData.userData != null){
            bearerData.mask |= CDMA_SMS_WMS_MASK_BD_USER_DATA;
        }

        // check mTimeStamp
        if (bearerData.timeStamp != null){
            bearerData.mask |= CDMA_SMS_WMS_MASK_BD_MC_TIME;
        }

        // check mNumberOfMessages
        if (bearerData.numberOfMessages > 0){
            bearerData.mask |= CDMA_SMS_WMS_MASK_BD_NUM_OF_MSGS;
        }

        // check mCallbackNumber
        if(bearerData.callbackNumber != null){
            bearerData.mask |= CDMA_SMS_WMS_MASK_BD_CALLBACK;
        }

        // check DisplayMode
        if(bearerData.displayMode == BearerData.DISPLAY_DEFAULT   ||
           bearerData.displayMode == BearerData.DISPLAY_IMMEDIATE ||
           bearerData.displayMode == BearerData.DISPLAY_USER){
            bearerData.mask |= CDMA_SMS_WMS_MASK_BD_DISPLAY_MODE;
        }
    }

    private static byte[] startEncoding(BearerData bearerData) {
        int m_id;
        byte[] m_data;
        int dataLength;
        byte[] encodedSms;
        int nbrOfHeaders = 0;

        if( nativeCdmaSmsSetBearerDataPrimitives(bearerData) == JNI_CDMA_SMS_FAILURE){
            return null;
        }

        if ((bearerData.mask & CDMA_SMS_WMS_MASK_BD_USER_DATA) == CDMA_SMS_WMS_MASK_BD_USER_DATA){
            if( nativeCdmaSmsSetUserData(bearerData.userData) == JNI_CDMA_SMS_FAILURE){
                return null;
            }

            if (bearerData.userData.userDataHeader != null){
                nbrOfHeaders = bearerData.userData.userDataHeader.nbrOfHeaders;
            }

            for (int i = 0; i < nbrOfHeaders; i++) {
                m_id = bearerData.userData.userDataHeader.getElements().get(i).getID();
                m_data = bearerData.userData.userDataHeader.getElements().get(i).getData();
                dataLength = m_data.length;
                if( nativeCdmaSmsSetUserDataHeader(m_id, m_data, dataLength, i)
                        == JNI_CDMA_SMS_FAILURE){
                    return null;
                }
            }
        }

        if ((bearerData.mask & CDMA_SMS_WMS_MASK_BD_CALLBACK) == CDMA_SMS_WMS_MASK_BD_CALLBACK) {
            if( nativeCdmaSmsSetSmsAddress(bearerData.callbackNumber) == JNI_CDMA_SMS_FAILURE){
                return null;
            }
        }

        /* call native method to encode SMS */
        encodedSms = nativeCdmaSmsEncodeSms();

        return encodedSms;
    }

    private static BearerData startDecoding(byte[] SmsData) {
        BearerData bData = new BearerData();
        byte[] udhData;

        /* call native method to decode SMS */
        if( nativeCdmaSmsDecodeSms(SmsData) == JNI_CDMA_SMS_FAILURE){
            return null;
        }

        if( nativeCdmaSmsGetBearerDataPrimitives(bData) == JNI_CDMA_SMS_FAILURE){
            return null;
        }

        if ((bData.mask & CDMA_SMS_WMS_MASK_BD_USER_DATA) == CDMA_SMS_WMS_MASK_BD_USER_DATA) {
            bData.userData = new UserData();
            if( nativeCdmaSmsGetUserData(bData.userData) == JNI_CDMA_SMS_FAILURE){
                return null;
            }

            udhData = nativeCdmaSmsGetUserDataHeader();
            if (udhData != null) {
                bData.userData.userDataHeader = SmsHeader.parse(udhData);
            }
        }

        if ((bData.mask & CDMA_SMS_WMS_MASK_BD_CALLBACK) == CDMA_SMS_WMS_MASK_BD_CALLBACK) {
            bData.callbackNumber = new CdmaSmsAddress();
            if( nativeCdmaSmsGetSmsAddress(bData.callbackNumber) == JNI_CDMA_SMS_FAILURE){
                return null;
            }
        }

        return bData;
    }

    // native methods

    /**
     * native method: Allocate memory for clientBD structure
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsConstructClientBD();

    /**
     * native method: Free memory used for clientBD structure
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsDestructClientBD();

    /**
     * native method: fill clientBD structure with bearerData primitives
     *
     * @param bearerData    an instance of BearerData.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsSetBearerDataPrimitives(BearerData bearerData);

    /**
     * native method: fill bearerData primitives with clientBD variables
     *
     * @param bearerData    an instance of BearerData.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsGetBearerDataPrimitives(BearerData bearerData);

    /**
     * native method: fill clientBD.user_data with UserData primitives
     *
     * @param userData    an instance of UserData.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsSetUserData(UserData userData);

    /**
     * native method: fill UserData primitives with clientBD.user_data
     *
     * @param userData    an instance of UserData.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsGetUserData(UserData userData);

    /**
     * native method: fill clientBD.user_data.headers with UserDataHeader primitives
     *
     * @param ID         ID of element.
     * @param data       element data.
     * @param dataLength data length
     * @param index      index of element
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsSetUserDataHeader(
            int ID, byte[] data, int dataLength, int index);

    /**
     * native method: fill UserDataHeader primitives with clientBD.user_data.headers
     *
     * @return   user data headers
     */
    private static native byte[] nativeCdmaSmsGetUserDataHeader();

    /**
     * native method: fill clientBD.callback with SmsAddress primitives
     *
     * @param smsAddr    an instance of SmsAddress.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsSetSmsAddress(CdmaSmsAddress smsAddr);

    /**
     * native method: fill SmsAddress primitives with clientBD.callback
     *
     * @param smsAddr    an instance of SmsAddress.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsGetSmsAddress(CdmaSmsAddress smsAddr);

    /**
     * native method: call encoding functions and get encoded SMS
     *
     * @return   the encoded SMS
     */
    private static native byte[] nativeCdmaSmsEncodeSms();

    /**
     * native method: call decode functions
     *
     * @param encodedSMS    encoded SMS.
     *
     * @return #JNI_CDMA_SMS_SUCCESS if succeed.
     *         #JNI_CDMA_SMS_FAILURE if fail.
     */
    private static native int nativeCdmaSmsDecodeSms(byte[] encodedSMS);

    /**
     * Load the shared library to link the native methods.
     */
    static {
        try {
            System.loadLibrary("cdma_sms_jni");
        }
        catch (UnsatisfiedLinkError ule) {
            System.err.println("WARNING: Could not load cdma_sms_jni.so");
        }
    }
}

