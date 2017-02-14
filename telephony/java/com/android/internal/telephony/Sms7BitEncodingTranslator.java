/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.telephony.Rlog;
import android.os.Build;
import android.util.SparseIntArray;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import com.android.internal.util.XmlUtils;
import com.android.internal.telephony.cdma.sms.UserData;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class Sms7BitEncodingTranslator {
    private static final String TAG = "Sms7BitEncodingTranslator";
    private static final boolean DBG = Build.IS_DEBUGGABLE ;
    private static boolean mIs7BitTranslationTableLoaded = false;
    private static SparseIntArray mTranslationTable = null;
    private static SparseIntArray mTranslationTableCommon = null;
    private static SparseIntArray mTranslationTableGSM = null;
    private static SparseIntArray mTranslationTableCDMA = null;

    // Parser variables
    private static final String XML_START_TAG = "SmsEnforce7BitTranslationTable";
    private static final String XML_TRANSLATION_TYPE_TAG = "TranslationType";
    private static final String XML_CHARACTOR_TAG = "Character";
    private static final String XML_FROM_TAG = "from";
    private static final String XML_TO_TAG = "to";

    /**
     * Translates each message character that is not supported by GSM 7bit
     * alphabet into a supported one
     *
     * @param message
     *            message to be translated
     * @param throwsException
     *            if true and some error occurs during translation, an exception
     *            is thrown; otherwise a null String is returned
     * @return translated message or null if some error occur
     */
    public static String translate(CharSequence message) {
        if (message == null) {
            Rlog.w(TAG, "Null message can not be translated");
            return null;
        }

        int size = message.length();
        if (size <= 0) {
            return "";
        }

        if (!mIs7BitTranslationTableLoaded) {
            mTranslationTableCommon = new SparseIntArray();
            mTranslationTableGSM = new SparseIntArray();
            mTranslationTableCDMA = new SparseIntArray();
            load7BitTranslationTableFromXml();
            mIs7BitTranslationTableLoaded = true;
        }

        if ((mTranslationTableCommon != null && mTranslationTableCommon.size() > 0) ||
                (mTranslationTableGSM != null && mTranslationTableGSM.size() > 0) ||
                (mTranslationTableCDMA != null && mTranslationTableCDMA.size() > 0)) {
            char[] output = new char[size];
            boolean isCdmaFormat = useCdmaFormatForMoSms();
            for (int i = 0; i < size; i++) {
                output[i] = translateIfNeeded(message.charAt(i), isCdmaFormat);
            }

            return String.valueOf(output);
        }

        return null;
    }

    /**
     * Translates a single character into its corresponding acceptable one, if
     * needed, based on GSM 7-bit alphabet
     *
     * @param c
     *            character to be translated
     * @return original character, if it's present on GSM 7-bit alphabet; a
     *         corresponding character, based on the translation table or white
     *         space, if no mapping is found in the translation table for such
     *         character
     */
    private static char translateIfNeeded(char c, boolean isCdmaFormat) {
        if (noTranslationNeeded(c, isCdmaFormat)) {
            if (DBG) {
                Rlog.v(TAG, "No translation needed for " + Integer.toHexString(c));
            }
            return c;
        }

        /*
         * Trying to translate unicode to Gsm 7-bit alphabet; If c is not
         * present on translation table, c does not belong to Unicode Latin-1
         * (Basic + Supplement), so we don't know how to translate it to a Gsm
         * 7-bit character! We replace c for an empty space and advises the user
         * about it.
         */
        int translation = -1;

        if (mTranslationTableCommon != null) {
            translation = mTranslationTableCommon.get(c, -1);
        }

        if (translation == -1) {
            if (isCdmaFormat) {
                if (mTranslationTableCDMA != null) {
                    translation = mTranslationTableCDMA.get(c, -1);
                }
            } else {
                if (mTranslationTableGSM != null) {
                    translation = mTranslationTableGSM.get(c, -1);
                }
            }
        }

        if (translation != -1) {
            if (DBG) {
                Rlog.v(TAG, Integer.toHexString(c) + " (" + c + ")" + " translated to "
                        + Integer.toHexString(translation) + " (" + (char) translation + ")");
            }
            return (char) translation;
        } else {
            if (DBG) {
                Rlog.w(TAG, "No translation found for " + Integer.toHexString(c)
                        + "! Replacing for empty space");
            }
            return ' ';
        }
    }

    private static boolean noTranslationNeeded(char c, boolean isCdmaFormat) {
        if (isCdmaFormat) {
            return GsmAlphabet.isGsmSeptets(c) && UserData.charToAscii.get(c, -1) != -1;
        }
        else {
            return GsmAlphabet.isGsmSeptets(c);
        }
    }

    private static boolean useCdmaFormatForMoSms() {
        if (!SmsManager.getDefault().isImsSmsSupported()) {
            // use Voice technology to determine SMS format.
            return TelephonyManager.getDefault().getCurrentPhoneType()
                    == PhoneConstants.PHONE_TYPE_CDMA;
        }
        // IMS is registered with SMS support, check the SMS format supported
        return (SmsConstants.FORMAT_3GPP2.equals(SmsManager.getDefault().getImsSmsFormat()));
    }

    /**
     * Load the whole translation table file from the framework resource
     * encoded in XML.
     */
    private static void load7BitTranslationTableFromXml() {
        XmlResourceParser parser = null;
        Resources r = Resources.getSystem();

        if (parser == null) {
            if (DBG) Rlog.d(TAG, "load7BitTranslationTableFromXml: open normal file");
            parser = r.getXml(com.android.internal.R.xml.sms_7bit_translation_table);
        }

        try {
            XmlUtils.beginDocument(parser, XML_START_TAG);
            while (true)  {
                XmlUtils.nextElement(parser);
                String tag = parser.getName();
                if (DBG) {
                    Rlog.d(TAG, "tag: " + tag);
                }
                if (XML_TRANSLATION_TYPE_TAG.equals(tag)) {
                    String type = parser.getAttributeValue(null, "Type");
                    if (DBG) {
                        Rlog.d(TAG, "type: " + type);
                    }
                    if (type.equals("common")) {
                        mTranslationTable = mTranslationTableCommon;
                    } else if (type.equals("gsm")) {
                        mTranslationTable = mTranslationTableGSM;
                    } else if (type.equals("cdma")) {
                        mTranslationTable = mTranslationTableCDMA;
                    } else {
                        Rlog.e(TAG, "Error Parsing 7BitTranslationTable: found incorrect type" + type);
                    }
                } else if (XML_CHARACTOR_TAG.equals(tag) && mTranslationTable != null) {
                    int from = parser.getAttributeUnsignedIntValue(null,
                            XML_FROM_TAG, -1);
                    int to = parser.getAttributeUnsignedIntValue(null,
                            XML_TO_TAG, -1);
                    if ((from != -1) && (to != -1)) {
                        if (DBG) {
                            Rlog.d(TAG, "Loading mapping " + Integer.toHexString(from)
                                    .toUpperCase() + " -> " + Integer.toHexString(to)
                                    .toUpperCase());
                        }
                        mTranslationTable.put (from, to);
                    } else {
                        Rlog.d(TAG, "Invalid translation table file format");
                    }
                } else {
                    break;
                }
            }
            if (DBG) Rlog.d(TAG, "load7BitTranslationTableFromXml: parsing successful, file loaded");
        } catch (Exception e) {
            Rlog.e(TAG, "Got exception while loading 7BitTranslationTable file.", e);
        } finally {
            if (parser instanceof XmlResourceParser) {
                ((XmlResourceParser)parser).close();
            }
        }
    }
}
