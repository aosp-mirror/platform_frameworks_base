/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.pim.vcard;

import android.content.ContentValues;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.CharsetUtils;
import android.util.Log;

import org.apache.commons.codec.binary.Base64;

import java.io.UnsupportedEncodingException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The class which lets users create their own vCard String.
 */
public class VCardBuilder {
    private static final String LOG_TAG = "VCardBuilder";

    // If you add the other element, please check all the columns are able to be
    // converted to String.
    //
    // e.g. BLOB is not what we can handle here now.
    private static final Set<String> sAllowedAndroidPropertySet =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    Nickname.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE,
                    Relation.CONTENT_ITEM_TYPE)));

    public static final int DEFAULT_PHONE_TYPE = Phone.TYPE_HOME;
    public static final int DEFAULT_POSTAL_TYPE = StructuredPostal.TYPE_HOME;
    public static final int DEFAULT_EMAIL_TYPE = Email.TYPE_OTHER;

    private static final String VCARD_DATA_VCARD = "VCARD";
    private static final String VCARD_DATA_PUBLIC = "PUBLIC";

    private static final String VCARD_PARAM_SEPARATOR = ";";
    private static final String VCARD_END_OF_LINE = "\r\n";
    private static final String VCARD_DATA_SEPARATOR = ":";
    private static final String VCARD_ITEM_SEPARATOR = ";";
    private static final String VCARD_WS = " ";
    private static final String VCARD_PARAM_EQUAL = "=";

    private static final String VCARD_PARAM_ENCODING_QP = "ENCODING=QUOTED-PRINTABLE";

    private static final String VCARD_PARAM_ENCODING_BASE64_V21 = "ENCODING=BASE64";
    private static final String VCARD_PARAM_ENCODING_BASE64_V30 = "ENCODING=b";

    private static final String SHIFT_JIS = "SHIFT_JIS";
    private static final String UTF_8 = "UTF-8";

    private final int mVCardType;

    private final boolean mIsV30;
    private final boolean mIsJapaneseMobilePhone;
    private final boolean mOnlyOneNoteFieldIsAvailable;
    private final boolean mIsDoCoMo;
    private final boolean mShouldUseQuotedPrintable;
    private final boolean mUsesAndroidProperty;
    private final boolean mUsesDefactProperty;
    private final boolean mUsesUtf8;
    private final boolean mUsesShiftJis;
    private final boolean mAppendTypeParamName;
    private final boolean mRefrainsQPToNameProperties;
    private final boolean mNeedsToConvertPhoneticString;

    private final boolean mShouldAppendCharsetParam;

    private final String mCharsetString;
    private final String mVCardCharsetParameter;

    private StringBuilder mBuilder;
    private boolean mEndAppended;

    public VCardBuilder(final int vcardType) {
        mVCardType = vcardType;

        mIsV30 = VCardConfig.isV30(vcardType);
        mShouldUseQuotedPrintable = VCardConfig.shouldUseQuotedPrintable(vcardType);
        mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
        mIsJapaneseMobilePhone = VCardConfig.needsToConvertPhoneticString(vcardType);
        mOnlyOneNoteFieldIsAvailable = VCardConfig.onlyOneNoteFieldIsAvailable(vcardType);
        mUsesAndroidProperty = VCardConfig.usesAndroidSpecificProperty(vcardType);
        mUsesDefactProperty = VCardConfig.usesDefactProperty(vcardType);
        mUsesUtf8 = VCardConfig.usesUtf8(vcardType);
        mUsesShiftJis = VCardConfig.usesShiftJis(vcardType);
        mRefrainsQPToNameProperties = VCardConfig.shouldRefrainQPToNameProperties(vcardType);
        mAppendTypeParamName = VCardConfig.appendTypeParamName(vcardType);
        mNeedsToConvertPhoneticString = VCardConfig.needsToConvertPhoneticString(vcardType);

        mShouldAppendCharsetParam = !(mIsV30 && mUsesUtf8);

        if (mIsDoCoMo) {
            String charset;
            try {
                charset = CharsetUtils.charsetForVendor(SHIFT_JIS, "docomo").name();
            } catch (UnsupportedCharsetException e) {
                Log.e(LOG_TAG, "DoCoMo-specific SHIFT_JIS was not found. Use SHIFT_JIS as is.");
                charset = SHIFT_JIS;
            }
            mCharsetString = charset;
            // Do not use mCharsetString bellow since it is different from "SHIFT_JIS" but
            // may be "DOCOMO_SHIFT_JIS" or something like that (internal expression used in
            // Android, not shown to the public).
            mVCardCharsetParameter = "CHARSET=" + SHIFT_JIS;
        } else if (mUsesShiftJis) {
            String charset;
            try {
                charset = CharsetUtils.charsetForVendor(SHIFT_JIS).name();
            } catch (UnsupportedCharsetException e) {
                Log.e(LOG_TAG, "Vendor-specific SHIFT_JIS was not found. Use SHIFT_JIS as is.");
                charset = SHIFT_JIS;
            }
            mCharsetString = charset;
            mVCardCharsetParameter = "CHARSET=" + SHIFT_JIS;
        } else {
            mCharsetString = UTF_8;
            mVCardCharsetParameter = "CHARSET=" + UTF_8;
        }
        clear();
    }

    public void clear() {
        mBuilder = new StringBuilder();
        mEndAppended = false;
        appendLine(VCardConstants.PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (mIsV30) {
            appendLine(VCardConstants.PROPERTY_VERSION, VCardConstants.VERSION_V30);
        } else {
            appendLine(VCardConstants.PROPERTY_VERSION, VCardConstants.VERSION_V21);
        }
    }

    private boolean containsNonEmptyName(final ContentValues contentValues) {
        final String familyName = contentValues.getAsString(StructuredName.FAMILY_NAME);
        final String middleName = contentValues.getAsString(StructuredName.MIDDLE_NAME);
        final String givenName = contentValues.getAsString(StructuredName.GIVEN_NAME);
        final String prefix = contentValues.getAsString(StructuredName.PREFIX);
        final String suffix = contentValues.getAsString(StructuredName.SUFFIX);
        final String phoneticFamilyName =
                contentValues.getAsString(StructuredName.PHONETIC_FAMILY_NAME);
        final String phoneticMiddleName =
                contentValues.getAsString(StructuredName.PHONETIC_MIDDLE_NAME);
        final String phoneticGivenName =
                contentValues.getAsString(StructuredName.PHONETIC_GIVEN_NAME);
        final String displayName = contentValues.getAsString(StructuredName.DISPLAY_NAME);
        return !(TextUtils.isEmpty(familyName) && TextUtils.isEmpty(middleName) &&
                TextUtils.isEmpty(givenName) && TextUtils.isEmpty(prefix) &&
                TextUtils.isEmpty(suffix) && TextUtils.isEmpty(phoneticFamilyName) &&
                TextUtils.isEmpty(phoneticMiddleName) && TextUtils.isEmpty(phoneticGivenName) &&
                TextUtils.isEmpty(displayName));
    }

    private ContentValues getPrimaryContentValue(final List<ContentValues> contentValuesList) {
        ContentValues primaryContentValues = null;
        ContentValues subprimaryContentValues = null;
        for (ContentValues contentValues : contentValuesList) {
            if (contentValues == null){
                continue;
            }
            Integer isSuperPrimary = contentValues.getAsInteger(StructuredName.IS_SUPER_PRIMARY);
            if (isSuperPrimary != null && isSuperPrimary > 0) {
                // We choose "super primary" ContentValues.
                primaryContentValues = contentValues;
                break;
            } else if (primaryContentValues == null) {
                // We choose the first "primary" ContentValues
                // if "super primary" ContentValues does not exist.
                final Integer isPrimary = contentValues.getAsInteger(StructuredName.IS_PRIMARY);
                if (isPrimary != null && isPrimary > 0 &&
                        containsNonEmptyName(contentValues)) {
                    primaryContentValues = contentValues;
                    // Do not break, since there may be ContentValues with "super primary"
                    // afterword.
                } else if (subprimaryContentValues == null &&
                        containsNonEmptyName(contentValues)) {
                    subprimaryContentValues = contentValues;
                }
            }
        }

        if (primaryContentValues == null) {
            if (subprimaryContentValues != null) {
                // We choose the first ContentValues if any "primary" ContentValues does not exist.
                primaryContentValues = subprimaryContentValues;
            } else {
                Log.e(LOG_TAG, "All ContentValues given from database is empty.");
                primaryContentValues = new ContentValues();
            }
        }

        return primaryContentValues;
    }

    /**
     * For safety, we'll emit just one value around StructuredName, as external importers
     * may get confused with multiple "N", "FN", etc. properties, though it is valid in
     * vCard spec.
     */
    public VCardBuilder appendNameProperties(final List<ContentValues> contentValuesList) {
        if (contentValuesList == null || contentValuesList.isEmpty()) {
            if (mIsDoCoMo) {
                appendLine(VCardConstants.PROPERTY_N, "");
            } else if (mIsV30) {
                // vCard 3.0 requires "N" and "FN" properties.
                appendLine(VCardConstants.PROPERTY_N, "");
                appendLine(VCardConstants.PROPERTY_FN, "");
            }
            return this;
        }

        final ContentValues contentValues = getPrimaryContentValue(contentValuesList);
        final String familyName = contentValues.getAsString(StructuredName.FAMILY_NAME);
        final String middleName = contentValues.getAsString(StructuredName.MIDDLE_NAME);
        final String givenName = contentValues.getAsString(StructuredName.GIVEN_NAME);
        final String prefix = contentValues.getAsString(StructuredName.PREFIX);
        final String suffix = contentValues.getAsString(StructuredName.SUFFIX);
        final String displayName = contentValues.getAsString(StructuredName.DISPLAY_NAME);

        if (!TextUtils.isEmpty(familyName) || !TextUtils.isEmpty(givenName)) {
            final boolean reallyAppendCharsetParameterToName =
                    shouldAppendCharsetParam(familyName, givenName, middleName, prefix, suffix);
            final boolean reallyUseQuotedPrintableToName =
                    (!mRefrainsQPToNameProperties &&
                            !(VCardUtils.containsOnlyNonCrLfPrintableAscii(familyName) &&
                                    VCardUtils.containsOnlyNonCrLfPrintableAscii(givenName) &&
                                    VCardUtils.containsOnlyNonCrLfPrintableAscii(middleName) &&
                                    VCardUtils.containsOnlyNonCrLfPrintableAscii(prefix) &&
                                    VCardUtils.containsOnlyNonCrLfPrintableAscii(suffix)));

            final String formattedName;
            if (!TextUtils.isEmpty(displayName)) {
                formattedName = displayName;
            } else {
                formattedName = VCardUtils.constructNameFromElements(
                        VCardConfig.getNameOrderType(mVCardType),
                        familyName, middleName, givenName, prefix, suffix);
            }
            final boolean reallyAppendCharsetParameterToFN =
                    shouldAppendCharsetParam(formattedName);
            final boolean reallyUseQuotedPrintableToFN =
                    !mRefrainsQPToNameProperties &&
                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(formattedName);

            final String encodedFamily;
            final String encodedGiven;
            final String encodedMiddle;
            final String encodedPrefix;
            final String encodedSuffix;
            if (reallyUseQuotedPrintableToName) {
                encodedFamily = encodeQuotedPrintable(familyName);
                encodedGiven = encodeQuotedPrintable(givenName);
                encodedMiddle = encodeQuotedPrintable(middleName);
                encodedPrefix = encodeQuotedPrintable(prefix);
                encodedSuffix = encodeQuotedPrintable(suffix);
            } else {
                encodedFamily = escapeCharacters(familyName);
                encodedGiven = escapeCharacters(givenName);
                encodedMiddle = escapeCharacters(middleName);
                encodedPrefix = escapeCharacters(prefix);
                encodedSuffix = escapeCharacters(suffix);
            }

            final String encodedFormattedname =
                    (reallyUseQuotedPrintableToFN ?
                            encodeQuotedPrintable(formattedName) : escapeCharacters(formattedName));

            mBuilder.append(VCardConstants.PROPERTY_N);
            if (mIsDoCoMo) {
                if (reallyAppendCharsetParameterToName) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintableToName) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(VCARD_PARAM_ENCODING_QP);
                }
                mBuilder.append(VCARD_DATA_SEPARATOR);
                // DoCoMo phones require that all the elements in the "family name" field.
                mBuilder.append(formattedName);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
            } else {
                if (reallyAppendCharsetParameterToName) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintableToName) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(VCARD_PARAM_ENCODING_QP);
                }
                mBuilder.append(VCARD_DATA_SEPARATOR);
                mBuilder.append(encodedFamily);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(encodedGiven);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(encodedMiddle);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(encodedPrefix);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(encodedSuffix);
            }
            mBuilder.append(VCARD_END_OF_LINE);

            // FN property
            mBuilder.append(VCardConstants.PROPERTY_FN);
            if (reallyAppendCharsetParameterToFN) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(mVCardCharsetParameter);
            }
            if (reallyUseQuotedPrintableToFN) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(VCARD_PARAM_ENCODING_QP);
            }
            mBuilder.append(VCARD_DATA_SEPARATOR);
            mBuilder.append(encodedFormattedname);
            mBuilder.append(VCARD_END_OF_LINE);
        } else if (!TextUtils.isEmpty(displayName)) {
            final boolean reallyUseQuotedPrintableToDisplayName =
                (!mRefrainsQPToNameProperties &&
                        !VCardUtils.containsOnlyNonCrLfPrintableAscii(displayName));
            final String encodedDisplayName =
                    reallyUseQuotedPrintableToDisplayName ?
                            encodeQuotedPrintable(displayName) :
                                escapeCharacters(displayName);

            mBuilder.append(VCardConstants.PROPERTY_N);
            if (shouldAppendCharsetParam(displayName)) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(mVCardCharsetParameter);
            }
            if (reallyUseQuotedPrintableToDisplayName) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(VCARD_PARAM_ENCODING_QP);
            }
            mBuilder.append(VCARD_DATA_SEPARATOR);
            mBuilder.append(encodedDisplayName);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_END_OF_LINE);
            mBuilder.append(VCardConstants.PROPERTY_FN);

            // Note: "CHARSET" param is not allowed in vCard 3.0, but we may add it
            //       when it would be useful for external importers, assuming no external
            //       importer allows this vioration.
            if (shouldAppendCharsetParam(displayName)) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(mVCardCharsetParameter);
            }
            mBuilder.append(VCARD_DATA_SEPARATOR);
            mBuilder.append(encodedDisplayName);
            mBuilder.append(VCARD_END_OF_LINE);
        } else if (mIsV30) {
            // vCard 3.0 specification requires these fields.
            appendLine(VCardConstants.PROPERTY_N, "");
            appendLine(VCardConstants.PROPERTY_FN, "");
        } else if (mIsDoCoMo) {
            appendLine(VCardConstants.PROPERTY_N, "");
        }

        appendPhoneticNameFields(contentValues);
        return this;
    }

    private void appendPhoneticNameFields(final ContentValues contentValues) {
        final String phoneticFamilyName;
        final String phoneticMiddleName;
        final String phoneticGivenName;
        {
            final String tmpPhoneticFamilyName =
                contentValues.getAsString(StructuredName.PHONETIC_FAMILY_NAME);
            final String tmpPhoneticMiddleName =
                contentValues.getAsString(StructuredName.PHONETIC_MIDDLE_NAME);
            final String tmpPhoneticGivenName =
                contentValues.getAsString(StructuredName.PHONETIC_GIVEN_NAME);
            if (mNeedsToConvertPhoneticString) {
                phoneticFamilyName = VCardUtils.toHalfWidthString(tmpPhoneticFamilyName);
                phoneticMiddleName = VCardUtils.toHalfWidthString(tmpPhoneticMiddleName);
                phoneticGivenName = VCardUtils.toHalfWidthString(tmpPhoneticGivenName);
            } else {
                phoneticFamilyName = tmpPhoneticFamilyName;
                phoneticMiddleName = tmpPhoneticMiddleName;
                phoneticGivenName = tmpPhoneticGivenName;
            }
        }

        if (TextUtils.isEmpty(phoneticFamilyName)
                && TextUtils.isEmpty(phoneticMiddleName)
                && TextUtils.isEmpty(phoneticGivenName)) {
            if (mIsDoCoMo) {
                mBuilder.append(VCardConstants.PROPERTY_SOUND);
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(VCardConstants.PARAM_TYPE_X_IRMC_N);
                mBuilder.append(VCARD_DATA_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_ITEM_SEPARATOR);
                mBuilder.append(VCARD_END_OF_LINE);
            }
            return;
        }

        // Try to emit the field(s) related to phonetic name.
        if (mIsV30) {
            final String sortString = VCardUtils
                    .constructNameFromElements(mVCardType,
                            phoneticFamilyName, phoneticMiddleName, phoneticGivenName);
            mBuilder.append(VCardConstants.PROPERTY_SORT_STRING);
            if (shouldAppendCharsetParam(sortString)) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(mVCardCharsetParameter);
            }
            mBuilder.append(VCARD_DATA_SEPARATOR);
            mBuilder.append(escapeCharacters(sortString));
            mBuilder.append(VCARD_END_OF_LINE);
        } else if (mIsJapaneseMobilePhone) {
            // Note: There is no appropriate property for expressing
            //       phonetic name in vCard 2.1, while there is in
            //       vCard 3.0 (SORT-STRING).
            //       We chose to use DoCoMo's way when the device is Japanese one
            //       since it is supported by
            //       a lot of Japanese mobile phones. This is "X-" property, so
            //       any parser hopefully would not get confused with this.
            //
            //       Also, DoCoMo's specification requires vCard composer to use just the first
            //       column.
            //       i.e.
            //       o  SOUND;X-IRMC-N:Miyakawa Daisuke;;;;
            //       x  SOUND;X-IRMC-N:Miyakawa;Daisuke;;;
            mBuilder.append(VCardConstants.PROPERTY_SOUND);
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(VCardConstants.PARAM_TYPE_X_IRMC_N);

            boolean reallyUseQuotedPrintable =
                (!mRefrainsQPToNameProperties
                        && !(VCardUtils.containsOnlyNonCrLfPrintableAscii(
                                phoneticFamilyName)
                                && VCardUtils.containsOnlyNonCrLfPrintableAscii(
                                        phoneticMiddleName)
                                && VCardUtils.containsOnlyNonCrLfPrintableAscii(
                                        phoneticGivenName)));

            final String encodedPhoneticFamilyName;
            final String encodedPhoneticMiddleName;
            final String encodedPhoneticGivenName;
            if (reallyUseQuotedPrintable) {
                encodedPhoneticFamilyName = encodeQuotedPrintable(phoneticFamilyName);
                encodedPhoneticMiddleName = encodeQuotedPrintable(phoneticMiddleName);
                encodedPhoneticGivenName = encodeQuotedPrintable(phoneticGivenName);
            } else {
                encodedPhoneticFamilyName = escapeCharacters(phoneticFamilyName);
                encodedPhoneticMiddleName = escapeCharacters(phoneticMiddleName);
                encodedPhoneticGivenName = escapeCharacters(phoneticGivenName);
            }

            if (shouldAppendCharsetParam(encodedPhoneticFamilyName,
                    encodedPhoneticMiddleName, encodedPhoneticGivenName)) {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(mVCardCharsetParameter);
            }
            mBuilder.append(VCARD_DATA_SEPARATOR);
            {
                boolean first = true;
                if (!TextUtils.isEmpty(encodedPhoneticFamilyName)) {
                    mBuilder.append(encodedPhoneticFamilyName);
                    first = false;
                }
                if (!TextUtils.isEmpty(encodedPhoneticMiddleName)) {
                    if (first) {
                        first = false;
                    } else {
                        mBuilder.append(' ');
                    }
                    mBuilder.append(encodedPhoneticMiddleName);
                }
                if (!TextUtils.isEmpty(encodedPhoneticGivenName)) {
                    if (!first) {
                        mBuilder.append(' ');
                    }
                    mBuilder.append(encodedPhoneticGivenName);
                }
            }
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(VCARD_END_OF_LINE);
        }

        if (mUsesDefactProperty) {
            if (!TextUtils.isEmpty(phoneticGivenName)) {
                final boolean reallyUseQuotedPrintable =
                    (mShouldUseQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticGivenName));
                final String encodedPhoneticGivenName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticGivenName = encodeQuotedPrintable(phoneticGivenName);
                } else {
                    encodedPhoneticGivenName = escapeCharacters(phoneticGivenName);
                }
                mBuilder.append(VCardConstants.PROPERTY_X_PHONETIC_FIRST_NAME);
                if (shouldAppendCharsetParam(phoneticGivenName)) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintable) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(VCARD_PARAM_ENCODING_QP);
                }
                mBuilder.append(VCARD_DATA_SEPARATOR);
                mBuilder.append(encodedPhoneticGivenName);
                mBuilder.append(VCARD_END_OF_LINE);
            }
            if (!TextUtils.isEmpty(phoneticMiddleName)) {
                final boolean reallyUseQuotedPrintable =
                    (mShouldUseQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticMiddleName));
                final String encodedPhoneticMiddleName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticMiddleName = encodeQuotedPrintable(phoneticMiddleName);
                } else {
                    encodedPhoneticMiddleName = escapeCharacters(phoneticMiddleName);
                }
                mBuilder.append(VCardConstants.PROPERTY_X_PHONETIC_MIDDLE_NAME);
                if (shouldAppendCharsetParam(phoneticMiddleName)) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintable) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(VCARD_PARAM_ENCODING_QP);
                }
                mBuilder.append(VCARD_DATA_SEPARATOR);
                mBuilder.append(encodedPhoneticMiddleName);
                mBuilder.append(VCARD_END_OF_LINE);
            }
            if (!TextUtils.isEmpty(phoneticFamilyName)) {
                final boolean reallyUseQuotedPrintable =
                    (mShouldUseQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticFamilyName));
                final String encodedPhoneticFamilyName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticFamilyName = encodeQuotedPrintable(phoneticFamilyName);
                } else {
                    encodedPhoneticFamilyName = escapeCharacters(phoneticFamilyName);
                }
                mBuilder.append(VCardConstants.PROPERTY_X_PHONETIC_LAST_NAME);
                if (shouldAppendCharsetParam(phoneticFamilyName)) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintable) {
                    mBuilder.append(VCARD_PARAM_SEPARATOR);
                    mBuilder.append(VCARD_PARAM_ENCODING_QP);
                }
                mBuilder.append(VCARD_DATA_SEPARATOR);
                mBuilder.append(encodedPhoneticFamilyName);
                mBuilder.append(VCARD_END_OF_LINE);
            }
        }
    }

    public VCardBuilder appendNickNames(final List<ContentValues> contentValuesList) {
        final boolean useAndroidProperty;
        if (mIsV30) {
            useAndroidProperty = false;
        } else if (mUsesAndroidProperty) {
            useAndroidProperty = true;
        } else {
            // There's no way to add this field.
            return this;
        }
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                final String nickname = contentValues.getAsString(Nickname.NAME);
                if (TextUtils.isEmpty(nickname)) {
                    continue;
                }
                if (useAndroidProperty) {
                    appendAndroidSpecificProperty(Nickname.CONTENT_ITEM_TYPE, contentValues);
                } else {
                    appendLineWithCharsetAndQPDetection(VCardConstants.PROPERTY_NICKNAME, nickname);
                }
            }
        }
        return this;
    }

    public VCardBuilder appendPhones(final List<ContentValues> contentValuesList) {
        boolean phoneLineExists = false;
        if (contentValuesList != null) {
            Set<String> phoneSet = new HashSet<String>();
            for (ContentValues contentValues : contentValuesList) {
                final Integer typeAsObject = contentValues.getAsInteger(Phone.TYPE);
                final String label = contentValues.getAsString(Phone.LABEL);
                final Integer isPrimaryAsInteger = contentValues.getAsInteger(Phone.IS_PRIMARY);
                final boolean isPrimary = (isPrimaryAsInteger != null ?
                        (isPrimaryAsInteger > 0) : false);
                String phoneNumber = contentValues.getAsString(Phone.NUMBER);
                if (phoneNumber != null) {
                    phoneNumber = phoneNumber.trim();
                }
                if (TextUtils.isEmpty(phoneNumber)) {
                    continue;
                }
                int type = (typeAsObject != null ? typeAsObject : DEFAULT_PHONE_TYPE);
                if (type == Phone.TYPE_PAGER) {
                    phoneLineExists = true;
                    if (!phoneSet.contains(phoneNumber)) {
                        phoneSet.add(phoneNumber);
                        appendTelLine(type, label, phoneNumber, isPrimary);
                    }
                } else {
                    // The entry "may" have several phone numbers when the contact entry is
                    // corrupted because of its original source.
                    //
                    // e.g. I encountered the entry like the following.
                    // "111-222-3333 (Miami)\n444-555-6666 (Broward; 305-653-6796 (Miami); ..."
                    // This kind of entry is not able to be inserted via Android devices, but
                    // possible if the source of the data is already corrupted.
                    List<String> phoneNumberList = splitIfSeveralPhoneNumbersExist(phoneNumber);
                    if (phoneNumberList.isEmpty()) {
                        continue;
                    }
                    phoneLineExists = true;
                    for (String actualPhoneNumber : phoneNumberList) {
                        if (!phoneSet.contains(actualPhoneNumber)) {
                            final int format = VCardUtils.getPhoneNumberFormat(mVCardType);
                            final String formattedPhoneNumber =
                                    PhoneNumberUtils.formatNumber(actualPhoneNumber, format);
                            phoneSet.add(actualPhoneNumber);
                            appendTelLine(type, label, formattedPhoneNumber, isPrimary);
                        }
                    }
                }
            }
        }

        if (!phoneLineExists && mIsDoCoMo) {
            appendTelLine(Phone.TYPE_HOME, "", "", false);
        }

        return this;
    }

    private List<String> splitIfSeveralPhoneNumbersExist(final String phoneNumber) {
        List<String> phoneList = new ArrayList<String>();

        StringBuilder builder = new StringBuilder();
        final int length = phoneNumber.length();
        for (int i = 0; i < length; i++) {
            final char ch = phoneNumber.charAt(i);
            // TODO: add a test case for string with '+', and care the other possible issues
            // which may happen by ignoring non-digits other than '+'.
            if (Character.isDigit(ch) || ch == '+') {
                builder.append(ch);
            } else if ((ch == ';' || ch == '\n') && builder.length() > 0) {
                phoneList.add(builder.toString());
                builder = new StringBuilder();
            }
        }
        if (builder.length() > 0) {
            phoneList.add(builder.toString());
        }

        return phoneList;
    }

    public VCardBuilder appendEmails(final List<ContentValues> contentValuesList) {
        boolean emailAddressExists = false;
        if (contentValuesList != null) {
            final Set<String> addressSet = new HashSet<String>();
            for (ContentValues contentValues : contentValuesList) {
                String emailAddress = contentValues.getAsString(Email.DATA);
                if (emailAddress != null) {
                    emailAddress = emailAddress.trim();
                }
                if (TextUtils.isEmpty(emailAddress)) {
                    continue;
                }
                Integer typeAsObject = contentValues.getAsInteger(Email.TYPE);
                final int type = (typeAsObject != null ?
                        typeAsObject : DEFAULT_EMAIL_TYPE);
                final String label = contentValues.getAsString(Email.LABEL);
                Integer isPrimaryAsInteger = contentValues.getAsInteger(Email.IS_PRIMARY);
                final boolean isPrimary = (isPrimaryAsInteger != null ?
                        (isPrimaryAsInteger > 0) : false);
                emailAddressExists = true;
                if (!addressSet.contains(emailAddress)) {
                    addressSet.add(emailAddress);
                    appendEmailLine(type, label, emailAddress, isPrimary);
                }
            }
        }

        if (!emailAddressExists && mIsDoCoMo) {
            appendEmailLine(Email.TYPE_HOME, "", "", false);
        }

        return this;
    }

    public VCardBuilder appendPostals(final List<ContentValues> contentValuesList) {
        if (contentValuesList == null || contentValuesList.isEmpty()) {
            if (mIsDoCoMo) {
                mBuilder.append(VCardConstants.PROPERTY_ADR);
                mBuilder.append(VCARD_PARAM_SEPARATOR);
                mBuilder.append(VCardConstants.PARAM_TYPE_HOME);
                mBuilder.append(VCARD_DATA_SEPARATOR);
                mBuilder.append(VCARD_END_OF_LINE);
            }
        } else {
            if (mIsDoCoMo) {
                appendPostalsForDoCoMo(contentValuesList);
            } else {
                appendPostalsForGeneric(contentValuesList);
            }
        }

        return this;
    }

    private static final Map<Integer, Integer> sPostalTypePriorityMap;

    static {
        sPostalTypePriorityMap = new HashMap<Integer, Integer>();
        sPostalTypePriorityMap.put(StructuredPostal.TYPE_HOME, 0);
        sPostalTypePriorityMap.put(StructuredPostal.TYPE_WORK, 1);
        sPostalTypePriorityMap.put(StructuredPostal.TYPE_OTHER, 2);
        sPostalTypePriorityMap.put(StructuredPostal.TYPE_CUSTOM, 3);
    }

    /**
     * Tries to append just one line. If there's no appropriate address
     * information, append an empty line.
     */
    private void appendPostalsForDoCoMo(final List<ContentValues> contentValuesList) {
        int currentPriority = Integer.MAX_VALUE;
        int currentType = Integer.MAX_VALUE;
        ContentValues currentContentValues = null;
        for (final ContentValues contentValues : contentValuesList) {
            if (contentValues == null) {
                continue;
            }
            final Integer typeAsInteger = contentValues.getAsInteger(StructuredPostal.TYPE);
            final Integer priorityAsInteger = sPostalTypePriorityMap.get(typeAsInteger);
            final int priority =
                    (priorityAsInteger != null ? priorityAsInteger : Integer.MAX_VALUE);
            if (priority < currentPriority) {
                currentPriority = priority;
                currentType = typeAsInteger;
                currentContentValues = contentValues;
                if (priority == 0) {
                    break;
                }
            }
        }

        if (currentContentValues == null) {
            Log.w(LOG_TAG, "Should not come here. Must have at least one postal data.");
            return;
        }

        final String label = currentContentValues.getAsString(StructuredPostal.LABEL);
        appendPostalLine(currentType, label, currentContentValues, false, true);
    }

    private void appendPostalsForGeneric(final List<ContentValues> contentValuesList) {
        for (final ContentValues contentValues : contentValuesList) {
            if (contentValues == null) {
                continue;
            }
            final Integer typeAsInteger = contentValues.getAsInteger(StructuredPostal.TYPE);
            final int type = (typeAsInteger != null ?
                    typeAsInteger : DEFAULT_POSTAL_TYPE);
            final String label = contentValues.getAsString(StructuredPostal.LABEL);
            final Integer isPrimaryAsInteger =
                contentValues.getAsInteger(StructuredPostal.IS_PRIMARY);
            final boolean isPrimary = (isPrimaryAsInteger != null ?
                    (isPrimaryAsInteger > 0) : false);
            appendPostalLine(type, label, contentValues, isPrimary, false);
        }
    }

    private static class PostalStruct {
        final boolean reallyUseQuotedPrintable;
        final boolean appendCharset;
        final String addressData;
        public PostalStruct(final boolean reallyUseQuotedPrintable,
                final boolean appendCharset, final String addressData) {
            this.reallyUseQuotedPrintable = reallyUseQuotedPrintable;
            this.appendCharset = appendCharset;
            this.addressData = addressData;
        }
    }

    /**
     * @return null when there's no information available to construct the data.
     */
    private PostalStruct tryConstructPostalStruct(ContentValues contentValues) {
        // adr-value    = 0*6(text-value ";") text-value
        //              ; PO Box, Extended Address, Street, Locality, Region, Postal
        //              ; Code, Country Name
        final String rawPoBox = contentValues.getAsString(StructuredPostal.POBOX);
        final String rawNeighborhood = contentValues.getAsString(StructuredPostal.NEIGHBORHOOD);
        final String rawStreet = contentValues.getAsString(StructuredPostal.STREET);
        final String rawLocality = contentValues.getAsString(StructuredPostal.CITY);
        final String rawRegion = contentValues.getAsString(StructuredPostal.REGION);
        final String rawPostalCode = contentValues.getAsString(StructuredPostal.POSTCODE);
        final String rawCountry = contentValues.getAsString(StructuredPostal.COUNTRY);
        final String[] rawAddressArray = new String[]{
                rawPoBox, rawNeighborhood, rawStreet, rawLocality,
                rawRegion, rawPostalCode, rawCountry};
        if (!VCardUtils.areAllEmpty(rawAddressArray)) {
            final boolean reallyUseQuotedPrintable =
                (mShouldUseQuotedPrintable &&
                        !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawAddressArray));
            final boolean appendCharset =
                !VCardUtils.containsOnlyPrintableAscii(rawAddressArray);
            final String encodedPoBox;
            final String encodedStreet;
            final String encodedLocality;
            final String encodedRegion;
            final String encodedPostalCode;
            final String encodedCountry;
            final String encodedNeighborhood;

            final String rawLocality2;
            // This looks inefficient since we encode rawLocality and rawNeighborhood twice,
            // but this is intentional.
            //
            // QP encoding may add line feeds when needed and the result of
            // - encodeQuotedPrintable(rawLocality + " " + rawNeighborhood)
            // may be different from
            // - encodedLocality + " " + encodedNeighborhood.
            //
            // We use safer way.
            if (TextUtils.isEmpty(rawLocality)) {
                if (TextUtils.isEmpty(rawNeighborhood)) {
                    rawLocality2 = "";
                } else {
                    rawLocality2 = rawNeighborhood;
                }
            } else {
                if (TextUtils.isEmpty(rawNeighborhood)) {
                    rawLocality2 = rawLocality;
                } else {
                    rawLocality2 = rawLocality + " " + rawNeighborhood;
                }
            }
            if (reallyUseQuotedPrintable) {
                encodedPoBox = encodeQuotedPrintable(rawPoBox);
                encodedStreet = encodeQuotedPrintable(rawStreet);
                encodedLocality = encodeQuotedPrintable(rawLocality2);
                encodedRegion = encodeQuotedPrintable(rawRegion);
                encodedPostalCode = encodeQuotedPrintable(rawPostalCode);
                encodedCountry = encodeQuotedPrintable(rawCountry);
            } else {
                encodedPoBox = escapeCharacters(rawPoBox);
                encodedStreet = escapeCharacters(rawStreet);
                encodedLocality = escapeCharacters(rawLocality2);
                encodedRegion = escapeCharacters(rawRegion);
                encodedPostalCode = escapeCharacters(rawPostalCode);
                encodedCountry = escapeCharacters(rawCountry);
                encodedNeighborhood = escapeCharacters(rawNeighborhood);
            }
            final StringBuffer addressBuffer = new StringBuffer();
            addressBuffer.append(encodedPoBox);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(encodedStreet);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(encodedLocality);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(encodedRegion);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(encodedPostalCode);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(encodedCountry);
            return new PostalStruct(
                    reallyUseQuotedPrintable, appendCharset, addressBuffer.toString());
        } else {  // VCardUtils.areAllEmpty(rawAddressArray) == true
            // Try to use FORMATTED_ADDRESS instead.
            final String rawFormattedAddress =
                contentValues.getAsString(StructuredPostal.FORMATTED_ADDRESS);
            if (TextUtils.isEmpty(rawFormattedAddress)) {
                return null;
            }
            final boolean reallyUseQuotedPrintable =
                (mShouldUseQuotedPrintable &&
                        !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawFormattedAddress));
            final boolean appendCharset =
                !VCardUtils.containsOnlyPrintableAscii(rawFormattedAddress);
            final String encodedFormattedAddress;
            if (reallyUseQuotedPrintable) {
                encodedFormattedAddress = encodeQuotedPrintable(rawFormattedAddress);
            } else {
                encodedFormattedAddress = escapeCharacters(rawFormattedAddress);
            }

            // We use the second value ("Extended Address") just because Japanese mobile phones
            // do so. If the other importer expects the value be in the other field, some flag may
            // be needed.
            final StringBuffer addressBuffer = new StringBuffer();
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(encodedFormattedAddress);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            return new PostalStruct(
                    reallyUseQuotedPrintable, appendCharset, addressBuffer.toString());
        }
    }

    public VCardBuilder appendIms(final List<ContentValues> contentValuesList) {
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                final Integer protocolAsObject = contentValues.getAsInteger(Im.PROTOCOL);
                if (protocolAsObject == null) {
                    continue;
                }
                final String propertyName = VCardUtils.getPropertyNameForIm(protocolAsObject);
                if (propertyName == null) {
                    continue;
                }
                String data = contentValues.getAsString(Im.DATA);
                if (data != null) {
                    data = data.trim();
                }
                if (TextUtils.isEmpty(data)) {
                    continue;
                }
                final String typeAsString;
                {
                    final Integer typeAsInteger = contentValues.getAsInteger(Im.TYPE);
                    switch (typeAsInteger != null ? typeAsInteger : Im.TYPE_OTHER) {
                        case Im.TYPE_HOME: {
                            typeAsString = VCardConstants.PARAM_TYPE_HOME;
                            break;
                        }
                        case Im.TYPE_WORK: {
                            typeAsString = VCardConstants.PARAM_TYPE_WORK;
                            break;
                        }
                        case Im.TYPE_CUSTOM: {
                            final String label = contentValues.getAsString(Im.LABEL);
                            typeAsString = (label != null ? "X-" + label : null);
                            break;
                        }
                        case Im.TYPE_OTHER:  // Ignore
                        default: {
                            typeAsString = null;
                            break;
                        }
                    }
                }

                final List<String> parameterList = new ArrayList<String>();
                if (!TextUtils.isEmpty(typeAsString)) {
                    parameterList.add(typeAsString);
                }
                final Integer isPrimaryAsInteger = contentValues.getAsInteger(Im.IS_PRIMARY);
                final boolean isPrimary = (isPrimaryAsInteger != null ?
                        (isPrimaryAsInteger > 0) : false);
                if (isPrimary) {
                    parameterList.add(VCardConstants.PARAM_TYPE_PREF);
                }

                appendLineWithCharsetAndQPDetection(propertyName, parameterList, data);
            }
        }
        return this;
    }

    public VCardBuilder appendWebsites(final List<ContentValues> contentValuesList) {
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                String website = contentValues.getAsString(Website.URL);
                if (website != null) {
                    website = website.trim();
                }

                // Note: vCard 3.0 does not allow any parameter addition toward "URL"
                //       property, while there's no document in vCard 2.1.
                if (!TextUtils.isEmpty(website)) {
                    appendLineWithCharsetAndQPDetection(VCardConstants.PROPERTY_URL, website);
                }
            }
        }
        return this;
    }

    public VCardBuilder appendOrganizations(final List<ContentValues> contentValuesList) {
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                String company = contentValues.getAsString(Organization.COMPANY);
                if (company != null) {
                    company = company.trim();
                }
                String department = contentValues.getAsString(Organization.DEPARTMENT);
                if (department != null) {
                    department = department.trim();
                }
                String title = contentValues.getAsString(Organization.TITLE);
                if (title != null) {
                    title = title.trim();
                }

                StringBuilder orgBuilder = new StringBuilder();
                if (!TextUtils.isEmpty(company)) {
                    orgBuilder.append(company);
                }
                if (!TextUtils.isEmpty(department)) {
                    if (orgBuilder.length() > 0) {
                        orgBuilder.append(';');
                    }
                    orgBuilder.append(department);
                }
                final String orgline = orgBuilder.toString();
                appendLine(VCardConstants.PROPERTY_ORG, orgline,
                        !VCardUtils.containsOnlyPrintableAscii(orgline),
                        (mShouldUseQuotedPrintable &&
                                !VCardUtils.containsOnlyNonCrLfPrintableAscii(orgline)));

                if (!TextUtils.isEmpty(title)) {
                    appendLine(VCardConstants.PROPERTY_TITLE, title,
                            !VCardUtils.containsOnlyPrintableAscii(title),
                            (mShouldUseQuotedPrintable &&
                                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(title)));
                }
            }
        }
        return this;
    }

    public VCardBuilder appendPhotos(final List<ContentValues> contentValuesList) {
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                if (contentValues == null) {
                    continue;
                }
                byte[] data = contentValues.getAsByteArray(Photo.PHOTO);
                if (data == null) {
                    continue;
                }
                final String photoType = VCardUtils.guessImageType(data);
                if (photoType == null) {
                    Log.d(LOG_TAG, "Unknown photo type. Ignored.");
                    continue;
                }
                final String photoString = new String(Base64.encodeBase64(data));
                if (!TextUtils.isEmpty(photoString)) {
                    appendPhotoLine(photoString, photoType);
                }
            }
        }
        return this;
    }

    public VCardBuilder appendNotes(final List<ContentValues> contentValuesList) {
        if (contentValuesList != null) {
            if (mOnlyOneNoteFieldIsAvailable) {
                final StringBuilder noteBuilder = new StringBuilder();
                boolean first = true;
                for (final ContentValues contentValues : contentValuesList) {
                    String note = contentValues.getAsString(Note.NOTE);
                    if (note == null) {
                        note = "";
                    }
                    if (note.length() > 0) {
                        if (first) {
                            first = false;
                        } else {
                            noteBuilder.append('\n');
                        }
                        noteBuilder.append(note);
                    }
                }
                final String noteStr = noteBuilder.toString();
                // This means we scan noteStr completely twice, which is redundant.
                // But for now, we assume this is not so time-consuming..
                final boolean shouldAppendCharsetInfo =
                    !VCardUtils.containsOnlyPrintableAscii(noteStr);
                final boolean reallyUseQuotedPrintable =
                        (mShouldUseQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(noteStr));
                appendLine(VCardConstants.PROPERTY_NOTE, noteStr,
                        shouldAppendCharsetInfo, reallyUseQuotedPrintable);
            } else {
                for (ContentValues contentValues : contentValuesList) {
                    final String noteStr = contentValues.getAsString(Note.NOTE);
                    if (!TextUtils.isEmpty(noteStr)) {
                        final boolean shouldAppendCharsetInfo =
                                !VCardUtils.containsOnlyPrintableAscii(noteStr);
                        final boolean reallyUseQuotedPrintable =
                                (mShouldUseQuotedPrintable &&
                                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(noteStr));
                        appendLine(VCardConstants.PROPERTY_NOTE, noteStr,
                                shouldAppendCharsetInfo, reallyUseQuotedPrintable);
                    }
                }
            }
        }
        return this;
    }

    public VCardBuilder appendEvents(final List<ContentValues> contentValuesList) {
        if (contentValuesList != null) {
            String primaryBirthday = null;
            String secondaryBirthday = null;
            for (final ContentValues contentValues : contentValuesList) {
                if (contentValues == null) {
                    continue;
                }
                final Integer eventTypeAsInteger = contentValues.getAsInteger(Event.TYPE);
                final int eventType;
                if (eventTypeAsInteger != null) {
                    eventType = eventTypeAsInteger;
                } else {
                    eventType = Event.TYPE_OTHER;
                }
                if (eventType == Event.TYPE_BIRTHDAY) {
                    final String birthdayCandidate = contentValues.getAsString(Event.START_DATE);
                    if (birthdayCandidate == null) {
                        continue;
                    }
                    final Integer isSuperPrimaryAsInteger =
                        contentValues.getAsInteger(Event.IS_SUPER_PRIMARY);
                    final boolean isSuperPrimary = (isSuperPrimaryAsInteger != null ?
                            (isSuperPrimaryAsInteger > 0) : false);
                    if (isSuperPrimary) {
                        // "super primary" birthday should the prefered one.
                        primaryBirthday = birthdayCandidate;
                        break;
                    }
                    final Integer isPrimaryAsInteger =
                        contentValues.getAsInteger(Event.IS_PRIMARY);
                    final boolean isPrimary = (isPrimaryAsInteger != null ?
                            (isPrimaryAsInteger > 0) : false);
                    if (isPrimary) {
                        // We don't break here since "super primary" birthday may exist later.
                        primaryBirthday = birthdayCandidate;
                    } else if (secondaryBirthday == null) {
                        // First entry is set to the "secondary" candidate.
                        secondaryBirthday = birthdayCandidate;
                    }
                } else if (mUsesAndroidProperty) {
                    // Event types other than Birthday is not supported by vCard.
                    appendAndroidSpecificProperty(Event.CONTENT_ITEM_TYPE, contentValues);
                }
            }
            if (primaryBirthday != null) {
                appendLineWithCharsetAndQPDetection(VCardConstants.PROPERTY_BDAY,
                        primaryBirthday.trim());
            } else if (secondaryBirthday != null){
                appendLineWithCharsetAndQPDetection(VCardConstants.PROPERTY_BDAY,
                        secondaryBirthday.trim());
            }
        }
        return this;
    }

    public VCardBuilder appendRelation(final List<ContentValues> contentValuesList) {
        if (mUsesAndroidProperty && contentValuesList != null) {
            for (final ContentValues contentValues : contentValuesList) {
                if (contentValues == null) {
                    continue;
                }
                appendAndroidSpecificProperty(Relation.CONTENT_ITEM_TYPE, contentValues);
            }
        }
        return this;
    }

    public void appendPostalLine(final int type, final String label,
            final ContentValues contentValues,
            final boolean isPrimary, final boolean emitLineEveryTime) {
        final boolean reallyUseQuotedPrintable;
        final boolean appendCharset;
        final String addressValue;
        {
            PostalStruct postalStruct = tryConstructPostalStruct(contentValues);
            if (postalStruct == null) {
                if (emitLineEveryTime) {
                    reallyUseQuotedPrintable = false;
                    appendCharset = false;
                    addressValue = "";
                } else {
                    return;
                }
            } else {
                reallyUseQuotedPrintable = postalStruct.reallyUseQuotedPrintable;
                appendCharset = postalStruct.appendCharset;
                addressValue = postalStruct.addressData;
            }
        }

        List<String> parameterList = new ArrayList<String>();
        if (isPrimary) {
            parameterList.add(VCardConstants.PARAM_TYPE_PREF);
        }
        switch (type) {
            case StructuredPostal.TYPE_HOME: {
                parameterList.add(VCardConstants.PARAM_TYPE_HOME);
                break;
            }
            case StructuredPostal.TYPE_WORK: {
                parameterList.add(VCardConstants.PARAM_TYPE_WORK);
                break;
            }
            case StructuredPostal.TYPE_CUSTOM: {
                if (!TextUtils.isEmpty(label)
                        && VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                    // We're not sure whether the label is valid in the spec
                    // ("IANA-token" in the vCard 3.0 is unclear...)
                    // Just  for safety, we add "X-" at the beggining of each label.
                    // Also checks the label obeys with vCard 3.0 spec.
                    parameterList.add("X-" + label);
                }
                break;
            }
            case StructuredPostal.TYPE_OTHER: {
                break;
            }
            default: {
                Log.e(LOG_TAG, "Unknown StructuredPostal type: " + type);
                break;
            }
        }

        mBuilder.append(VCardConstants.PROPERTY_ADR);
        if (!parameterList.isEmpty()) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            appendTypeParameters(parameterList);
        }
        if (appendCharset) {
            // Strictly, vCard 3.0 does not allow exporters to emit charset information,
            // but we will add it since the information should be useful for importers,
            //
            // Assume no parser does not emit error with this parameter in vCard 3.0.
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(mVCardCharsetParameter);
        }
        if (reallyUseQuotedPrintable) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(VCARD_PARAM_ENCODING_QP);
        }
        mBuilder.append(VCARD_DATA_SEPARATOR);
        mBuilder.append(addressValue);
        mBuilder.append(VCARD_END_OF_LINE);
    }

    public void appendEmailLine(final int type, final String label,
            final String rawValue, final boolean isPrimary) {
        final String typeAsString;
        switch (type) {
            case Email.TYPE_CUSTOM: {
                if (VCardUtils.isMobilePhoneLabel(label)) {
                    typeAsString = VCardConstants.PARAM_TYPE_CELL;
                } else if (!TextUtils.isEmpty(label)
                        && VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                    typeAsString = "X-" + label;
                } else {
                    typeAsString = null;
                }
                break;
            }
            case Email.TYPE_HOME: {
                typeAsString = VCardConstants.PARAM_TYPE_HOME;
                break;
            }
            case Email.TYPE_WORK: {
                typeAsString = VCardConstants.PARAM_TYPE_WORK;
                break;
            }
            case Email.TYPE_OTHER: {
                typeAsString = null;
                break;
            }
            case Email.TYPE_MOBILE: {
                typeAsString = VCardConstants.PARAM_TYPE_CELL;
                break;
            }
            default: {
                Log.e(LOG_TAG, "Unknown Email type: " + type);
                typeAsString = null;
                break;
            }
        }

        final List<String> parameterList = new ArrayList<String>();
        if (isPrimary) {
            parameterList.add(VCardConstants.PARAM_TYPE_PREF);
        }
        if (!TextUtils.isEmpty(typeAsString)) {
            parameterList.add(typeAsString);
        }

        appendLineWithCharsetAndQPDetection(VCardConstants.PROPERTY_EMAIL, parameterList,
                rawValue);
    }

    public void appendTelLine(final Integer typeAsInteger, final String label,
            final String encodedValue, boolean isPrimary) {
        mBuilder.append(VCardConstants.PROPERTY_TEL);
        mBuilder.append(VCARD_PARAM_SEPARATOR);

        final int type;
        if (typeAsInteger == null) {
            type = Phone.TYPE_OTHER;
        } else {
            type = typeAsInteger;
        }

        ArrayList<String> parameterList = new ArrayList<String>();
        switch (type) {
            case Phone.TYPE_HOME: {
                parameterList.addAll(
                        Arrays.asList(VCardConstants.PARAM_TYPE_HOME));
                break;
            }
            case Phone.TYPE_WORK: {
                parameterList.addAll(
                        Arrays.asList(VCardConstants.PARAM_TYPE_WORK));
                break;
            }
            case Phone.TYPE_FAX_HOME: {
                parameterList.addAll(
                        Arrays.asList(VCardConstants.PARAM_TYPE_HOME, VCardConstants.PARAM_TYPE_FAX));
                break;
            }
            case Phone.TYPE_FAX_WORK: {
                parameterList.addAll(
                        Arrays.asList(VCardConstants.PARAM_TYPE_WORK, VCardConstants.PARAM_TYPE_FAX));
                break;
            }
            case Phone.TYPE_MOBILE: {
                parameterList.add(VCardConstants.PARAM_TYPE_CELL);
                break;
            }
            case Phone.TYPE_PAGER: {
                if (mIsDoCoMo) {
                    // Not sure about the reason, but previous implementation had
                    // used "VOICE" instead of "PAGER"
                    parameterList.add(VCardConstants.PARAM_TYPE_VOICE);
                } else {
                    parameterList.add(VCardConstants.PARAM_TYPE_PAGER);
                }
                break;
            }
            case Phone.TYPE_OTHER: {
                parameterList.add(VCardConstants.PARAM_TYPE_VOICE);
                break;
            }
            case Phone.TYPE_CAR: {
                parameterList.add(VCardConstants.PARAM_TYPE_CAR);
                break;
            }
            case Phone.TYPE_COMPANY_MAIN: {
                // There's no relevant field in vCard (at least 2.1).
                parameterList.add(VCardConstants.PARAM_TYPE_WORK);
                isPrimary = true;
                break;
            }
            case Phone.TYPE_ISDN: {
                parameterList.add(VCardConstants.PARAM_TYPE_ISDN);
                break;
            }
            case Phone.TYPE_MAIN: {
                isPrimary = true;
                break;
            }
            case Phone.TYPE_OTHER_FAX: {
                parameterList.add(VCardConstants.PARAM_TYPE_FAX);
                break;
            }
            case Phone.TYPE_TELEX: {
                parameterList.add(VCardConstants.PARAM_TYPE_TLX);
                break;
            }
            case Phone.TYPE_WORK_MOBILE: {
                parameterList.addAll(
                        Arrays.asList(VCardConstants.PARAM_TYPE_WORK, VCardConstants.PARAM_TYPE_CELL));
                break;
            }
            case Phone.TYPE_WORK_PAGER: {
                parameterList.add(VCardConstants.PARAM_TYPE_WORK);
                // See above.
                if (mIsDoCoMo) {
                    parameterList.add(VCardConstants.PARAM_TYPE_VOICE);
                } else {
                    parameterList.add(VCardConstants.PARAM_TYPE_PAGER);
                }
                break;
            }
            case Phone.TYPE_MMS: {
                parameterList.add(VCardConstants.PARAM_TYPE_MSG);
                break;
            }
            case Phone.TYPE_CUSTOM: {
                if (TextUtils.isEmpty(label)) {
                    // Just ignore the custom type.
                    parameterList.add(VCardConstants.PARAM_TYPE_VOICE);
                } else if (VCardUtils.isMobilePhoneLabel(label)) {
                    parameterList.add(VCardConstants.PARAM_TYPE_CELL);
                } else {
                    final String upperLabel = label.toUpperCase();
                    if (VCardUtils.isValidInV21ButUnknownToContactsPhoteType(upperLabel)) {
                        parameterList.add(upperLabel);
                    } else if (VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                        // Note: Strictly, vCard 2.1 does not allow "X-" parameter without
                        //       "TYPE=" string.
                        parameterList.add("X-" + label);
                    }
                }
                break;
            }
            case Phone.TYPE_RADIO:
            case Phone.TYPE_TTY_TDD:
            default: {
                break;
            }
        }

        if (isPrimary) {
            parameterList.add(VCardConstants.PARAM_TYPE_PREF);
        }

        if (parameterList.isEmpty()) {
            appendUncommonPhoneType(mBuilder, type);
        } else {
            appendTypeParameters(parameterList);
        }

        mBuilder.append(VCARD_DATA_SEPARATOR);
        mBuilder.append(encodedValue);
        mBuilder.append(VCARD_END_OF_LINE);
    }

    /**
     * Appends phone type string which may not be available in some devices.
     */
    private void appendUncommonPhoneType(final StringBuilder builder, final Integer type) {
        if (mIsDoCoMo) {
            // The previous implementation for DoCoMo had been conservative
            // about miscellaneous types.
            builder.append(VCardConstants.PARAM_TYPE_VOICE);
        } else {
            String phoneType = VCardUtils.getPhoneTypeString(type);
            if (phoneType != null) {
                appendTypeParameter(phoneType);
            } else {
                Log.e(LOG_TAG, "Unknown or unsupported (by vCard) Phone type: " + type);
            }
        }
    }

    /**
     * @param encodedValue Must be encoded by BASE64 
     * @param photoType
     */
    public void appendPhotoLine(final String encodedValue, final String photoType) {
        StringBuilder tmpBuilder = new StringBuilder();
        tmpBuilder.append(VCardConstants.PROPERTY_PHOTO);
        tmpBuilder.append(VCARD_PARAM_SEPARATOR);
        if (mIsV30) {
            tmpBuilder.append(VCARD_PARAM_ENCODING_BASE64_V30);
        } else {
            tmpBuilder.append(VCARD_PARAM_ENCODING_BASE64_V21);
        }
        tmpBuilder.append(VCARD_PARAM_SEPARATOR);
        appendTypeParameter(tmpBuilder, photoType);
        tmpBuilder.append(VCARD_DATA_SEPARATOR);
        tmpBuilder.append(encodedValue);

        final String tmpStr = tmpBuilder.toString();
        tmpBuilder = new StringBuilder();
        int lineCount = 0;
        final int length = tmpStr.length();
        final int maxNumForFirstLine = VCardConstants.MAX_CHARACTER_NUMS_BASE64_V30
                - VCARD_END_OF_LINE.length();
        final int maxNumInGeneral = maxNumForFirstLine - VCARD_WS.length();
        int maxNum = maxNumForFirstLine;
        for (int i = 0; i < length; i++) {
            tmpBuilder.append(tmpStr.charAt(i));
            lineCount++;
            if (lineCount > maxNum) {
                tmpBuilder.append(VCARD_END_OF_LINE);
                tmpBuilder.append(VCARD_WS);
                maxNum = maxNumInGeneral;
                lineCount = 0;
            }
        }
        mBuilder.append(tmpBuilder.toString());
        mBuilder.append(VCARD_END_OF_LINE);
        mBuilder.append(VCARD_END_OF_LINE);
    }

    public void appendAndroidSpecificProperty(final String mimeType, ContentValues contentValues) {
        if (!sAllowedAndroidPropertySet.contains(mimeType)) {
            return;
        }
        final List<String> rawValueList = new ArrayList<String>();
        for (int i = 1; i <= VCardConstants.MAX_DATA_COLUMN; i++) {
            String value = contentValues.getAsString("data" + i);
            if (value == null) {
                value = "";
            }
            rawValueList.add(value);
        }

        boolean needCharset =
            (mShouldAppendCharsetParam &&
                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawValueList));
        boolean reallyUseQuotedPrintable =
            (mShouldUseQuotedPrintable &&
                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawValueList));
        mBuilder.append(VCardConstants.PROPERTY_X_ANDROID_CUSTOM);
        if (needCharset) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(mVCardCharsetParameter);
        }
        if (reallyUseQuotedPrintable) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(VCARD_PARAM_ENCODING_QP);
        }
        mBuilder.append(VCARD_DATA_SEPARATOR);
        mBuilder.append(mimeType);  // Should not be encoded.
        for (String rawValue : rawValueList) {
            final String encodedValue;
            if (reallyUseQuotedPrintable) {
                encodedValue = encodeQuotedPrintable(rawValue);
            } else {
                // TODO: one line may be too huge, which may be invalid in vCard 3.0
                //        (which says "When generating a content line, lines longer than
                //        75 characters SHOULD be folded"), though several
                //        (even well-known) applications do not care this.
                encodedValue = escapeCharacters(rawValue);
            }
            mBuilder.append(VCARD_ITEM_SEPARATOR);
            mBuilder.append(encodedValue);
        }
        mBuilder.append(VCARD_END_OF_LINE);
    }

    public void appendLineWithCharsetAndQPDetection(final String propertyName,
            final String rawValue) {
        appendLineWithCharsetAndQPDetection(propertyName, null, rawValue);
    }

    public void appendLineWithCharsetAndQPDetection(
            final String propertyName, final List<String> rawValueList) {
        appendLineWithCharsetAndQPDetection(propertyName, null, rawValueList);
    }

    public void appendLineWithCharsetAndQPDetection(final String propertyName,
            final List<String> parameterList, final String rawValue) {
        final boolean needCharset =
                !VCardUtils.containsOnlyPrintableAscii(rawValue);
        final boolean reallyUseQuotedPrintable =
                (mShouldUseQuotedPrintable &&
                        !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawValue));
        appendLine(propertyName, parameterList,
                rawValue, needCharset, reallyUseQuotedPrintable);
    }

    public void appendLineWithCharsetAndQPDetection(final String propertyName,
            final List<String> parameterList, final List<String> rawValueList) {
        boolean needCharset =
            (mShouldAppendCharsetParam &&
                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawValueList));
        boolean reallyUseQuotedPrintable =
            (mShouldUseQuotedPrintable &&
                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawValueList));
        appendLine(propertyName, parameterList, rawValueList,
                needCharset, reallyUseQuotedPrintable);
    }

    /**
     * Appends one line with a given property name and value.  
     */
    public void appendLine(final String propertyName, final String rawValue) {
        appendLine(propertyName, rawValue, false, false);
    }

    public void appendLine(final String propertyName, final List<String> rawValueList) {
        appendLine(propertyName, rawValueList, false, false);
    }

    public void appendLine(final String propertyName,
            final String rawValue, final boolean needCharset,
            boolean reallyUseQuotedPrintable) {
        appendLine(propertyName, null, rawValue, needCharset, reallyUseQuotedPrintable);
    }

    public void appendLine(final String propertyName, final List<String> parameterList,
            final String rawValue) {
        appendLine(propertyName, parameterList, rawValue, false, false);
    }

    public void appendLine(final String propertyName, final List<String> parameterList,
            final String rawValue, final boolean needCharset,
            boolean reallyUseQuotedPrintable) {
        mBuilder.append(propertyName);
        if (parameterList != null && parameterList.size() > 0) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            appendTypeParameters(parameterList);
        }
        if (needCharset) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(mVCardCharsetParameter);
        }

        final String encodedValue;
        if (reallyUseQuotedPrintable) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(VCARD_PARAM_ENCODING_QP);
            encodedValue = encodeQuotedPrintable(rawValue);
        } else {
            // TODO: one line may be too huge, which may be invalid in vCard spec, though
            //       several (even well-known) applications do not care this.
            encodedValue = escapeCharacters(rawValue);
        }

        mBuilder.append(VCARD_DATA_SEPARATOR);
        mBuilder.append(encodedValue);
        mBuilder.append(VCARD_END_OF_LINE);
    }

    public void appendLine(final String propertyName, final List<String> rawValueList,
            final boolean needCharset, boolean needQuotedPrintable) {
        appendLine(propertyName, null, rawValueList, needCharset, needQuotedPrintable);
    }

    public void appendLine(final String propertyName, final List<String> parameterList,
            final List<String> rawValueList, final boolean needCharset,
            final boolean needQuotedPrintable) {
        mBuilder.append(propertyName);
        if (parameterList != null && parameterList.size() > 0) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            appendTypeParameters(parameterList);
        }
        if (needCharset) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(mVCardCharsetParameter);
        }
        if (needQuotedPrintable) {
            mBuilder.append(VCARD_PARAM_SEPARATOR);
            mBuilder.append(VCARD_PARAM_ENCODING_QP);
        }

        mBuilder.append(VCARD_DATA_SEPARATOR);
        boolean first = true;
        for (String rawValue : rawValueList) {
            final String encodedValue;
            if (needQuotedPrintable) {
                encodedValue = encodeQuotedPrintable(rawValue);
            } else {
                // TODO: one line may be too huge, which may be invalid in vCard 3.0
                //        (which says "When generating a content line, lines longer than
                //        75 characters SHOULD be folded"), though several
                //        (even well-known) applications do not care this.
                encodedValue = escapeCharacters(rawValue);
            }

            if (first) {
                first = false;
            } else {
                mBuilder.append(VCARD_ITEM_SEPARATOR);
            }
            mBuilder.append(encodedValue);
        }
        mBuilder.append(VCARD_END_OF_LINE);
    }

    /**
     * VCARD_PARAM_SEPARATOR must be appended before this method being called.
     */
    private void appendTypeParameters(final List<String> types) {
        // We may have to make this comma separated form like "TYPE=DOM,WORK" in the future,
        // which would be recommended way in vcard 3.0 though not valid in vCard 2.1.
        boolean first = true;
        for (final String typeValue : types) {
            // Note: vCard 3.0 specifies the different type of acceptable type Strings, but
            //       we don't emit that kind of vCard 3.0 specific type since there should be
            //       high probabilyty in which external importers cannot understand them.
            //
            // e.g. TYPE="\u578B\u306B\u3087" (vCard 3.0 allows non-Ascii characters if they
            //      are quoted.)
            if (!VCardUtils.isV21Word(typeValue)) {
                continue;
            }
            if (first) {
                first = false;
            } else {
                mBuilder.append(VCARD_PARAM_SEPARATOR);
            }
            appendTypeParameter(typeValue);
        }
    }

    /**
     * VCARD_PARAM_SEPARATOR must be appended before this method being called.
     */
    private void appendTypeParameter(final String type) {
        appendTypeParameter(mBuilder, type);
    }

    private void appendTypeParameter(final StringBuilder builder, final String type) {
        // Refrain from using appendType() so that "TYPE=" is not be appended when the
        // device is DoCoMo's (just for safety).
        //
        // Note: In vCard 3.0, Type strings also can be like this: "TYPE=HOME,PREF"
        if ((mIsV30 || mAppendTypeParamName) && !mIsDoCoMo) {
            builder.append(VCardConstants.PARAM_TYPE).append(VCARD_PARAM_EQUAL);
        }
        builder.append(type);
    }

    /**
     * Returns true when the property line should contain charset parameter
     * information. This method may return true even when vCard version is 3.0.
     *
     * Strictly, adding charset information is invalid in VCard 3.0.
     * However we'll add the info only when charset we use is not UTF-8
     * in vCard 3.0 format, since parser side may be able to use the charset
     * via this field, though we may encounter another problem by adding it.
     *
     * e.g. Japanese mobile phones use Shift_Jis while RFC 2426
     * recommends UTF-8. By adding this field, parsers may be able
     * to know this text is NOT UTF-8 but Shift_Jis.
     */
    private boolean shouldAppendCharsetParam(String...propertyValueList) {
        if (!mShouldAppendCharsetParam) {
            return false;
        }
        for (String propertyValue : propertyValueList) {
            if (!VCardUtils.containsOnlyPrintableAscii(propertyValue)) {
                return true;
            }
        }
        return false;
    }

    private String encodeQuotedPrintable(final String str) {
        if (TextUtils.isEmpty(str)) {
            return "";
        }

        final StringBuilder builder = new StringBuilder();
        int index = 0;
        int lineCount = 0;
        byte[] strArray = null;

        try {
            strArray = str.getBytes(mCharsetString);
        } catch (UnsupportedEncodingException e) {
            Log.e(LOG_TAG, "Charset " + mCharsetString + " cannot be used. "
                    + "Try default charset");
            strArray = str.getBytes();
        }
        while (index < strArray.length) {
            builder.append(String.format("=%02X", strArray[index]));
            index += 1;
            lineCount += 3;

            if (lineCount >= 67) {
                // Specification requires CRLF must be inserted before the
                // length of the line
                // becomes more than 76.
                // Assuming that the next character is a multi-byte character,
                // it will become
                // 6 bytes.
                // 76 - 6 - 3 = 67
                builder.append("=\r\n");
                lineCount = 0;
            }
        }

        return builder.toString();
    }

    /**
     * Append '\' to the characters which should be escaped. The character set is different
     * not only between vCard 2.1 and vCard 3.0 but also among each device.
     *
     * Note that Quoted-Printable string must not be input here.
     */
    @SuppressWarnings("fallthrough")
    private String escapeCharacters(final String unescaped) {
        if (TextUtils.isEmpty(unescaped)) {
            return "";
        }

        final StringBuilder tmpBuilder = new StringBuilder();
        final int length = unescaped.length();
        for (int i = 0; i < length; i++) {
            final char ch = unescaped.charAt(i);
            switch (ch) {
                case ';': {
                    tmpBuilder.append('\\');
                    tmpBuilder.append(';');
                    break;
                }
                case '\r': {
                    if (i + 1 < length) {
                        char nextChar = unescaped.charAt(i);
                        if (nextChar == '\n') {
                            break;
                        } else {
                            // fall through
                        }
                    } else {
                        // fall through
                    }
                }
                case '\n': {
                    // In vCard 2.1, there's no specification about this, while
                    // vCard 3.0 explicitly requires this should be encoded to "\n".
                    tmpBuilder.append("\\n");
                    break;
                }
                case '\\': {
                    if (mIsV30) {
                        tmpBuilder.append("\\\\");
                        break;
                    } else {
                        // fall through
                    }
                }
                case '<':
                case '>': {
                    if (mIsDoCoMo) {
                        tmpBuilder.append('\\');
                        tmpBuilder.append(ch);
                    } else {
                        tmpBuilder.append(ch);
                    }
                    break;
                }
                case ',': {
                    if (mIsV30) {
                        tmpBuilder.append("\\,");
                    } else {
                        tmpBuilder.append(ch);
                    }
                    break;
                }
                default: {
                    tmpBuilder.append(ch);
                    break;
                }
            }
        }
        return tmpBuilder.toString();
    }

    @Override
    public String toString() {
        if (!mEndAppended) {
            if (mIsDoCoMo) {
                appendLine(VCardConstants.PROPERTY_X_CLASS, VCARD_DATA_PUBLIC);
                appendLine(VCardConstants.PROPERTY_X_REDUCTION, "");
                appendLine(VCardConstants.PROPERTY_X_NO, "");
                appendLine(VCardConstants.PROPERTY_X_DCM_HMN_MODE, "");
            }
            appendLine(VCardConstants.PROPERTY_END, VCARD_DATA_VCARD);
            mEndAppended = true;
        }
        return mBuilder.toString();
    }
}
