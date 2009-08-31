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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.Entity.NamedContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.RemoteException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Miscellaneous;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.text.TextUtils;
import android.util.CharsetUtils;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * The class for composing VCard from Contacts information. Note that this is
 * completely differnt implementation from
 * android.syncml.pim.vcard.VCardComposer, which is not maintained anymore.
 * </p>
 * 
 * <p>
 * Usually, this class should be used like this.
 * </p>
 * 
 * <pre class="prettyprint"> VCardComposer composer = null; try { composer = new
 * VCardComposer(context); composer.addHandler(composer.new
 * HandlerForOutputStream(outputStream)); if (!composer.init()) { // Do
 * something handling the situation. return; } while (!composer.isAfterLast()) {
 * if (mCanceled) { // Assume a user may cancel this operation during the
 * export. return; } if (!composer.createOneEntry()) { // Do something handling
 * the error situation. return; } } } finally { if (composer != null) {
 * composer.terminate(); } } </pre>
 */
public class VCardComposer {
    private static final String LOG_TAG = "vcard.VCardComposer";

    public static interface OneEntryHandler {
        public boolean onInit(Context context);

        public boolean onEntryCreated(String vcard);

        public void onTerminate();
    }

    /**
     * <p>
     * An useful example handler, which emits VCard String to outputstream one
     * by one.
     * </p>
     * <p>
     * The input OutputStream object is closed() on {{@link #onTerminate()}.
     * Must not close the stream outside.
     * </p>
     */
    public class HandlerForOutputStream implements OneEntryHandler {
        @SuppressWarnings("hiding")
        private static final String LOG_TAG = "vcard.VCardComposer.HandlerForOutputStream";

        private OutputStream mOutputStream; // mWriter will close this.
        private Writer mWriter;

        private boolean mFinishIsCalled = false;

        /**
         * Input stream will be closed on the detruction of this object.
         */
        public HandlerForOutputStream(OutputStream outputStream) {
            mOutputStream = outputStream;
        }

        public boolean onInit(Context context) {
            try {
                mWriter = new BufferedWriter(new OutputStreamWriter(
                        mOutputStream, mCharsetString));
            } catch (UnsupportedEncodingException e1) {
                Log.e(LOG_TAG, "Unsupported charset: " + mCharsetString);
                mErrorReason = "Encoding is not supported (usually this does not happen!): "
                        + mCharsetString;
                return false;
            }

            if (mIsDoCoMo) {
                try {
                    // Create one empty entry.
                    mWriter.write(createOneEntryInternal("-1"));
                } catch (IOException e) {
                    Log.e(LOG_TAG,
                            "IOException occurred during exportOneContactData: "
                                    + e.getMessage());
                    mErrorReason = "IOException occurred: " + e.getMessage();
                    return false;
                }
            }
            return true;
        }

        public boolean onEntryCreated(String vcard) {
            try {
                mWriter.write(vcard);
            } catch (IOException e) {
                Log.e(LOG_TAG,
                        "IOException occurred during exportOneContactData: "
                                + e.getMessage());
                mErrorReason = "IOException occurred: " + e.getMessage();
                return false;
            }
            return true;
        }

        public void onTerminate() {
            if (mWriter != null) {
                try {
                    // Flush and sync the data so that a user is able to pull
                    // the SDCard just after
                    // the export.
                    mWriter.flush();
                    if (mOutputStream != null
                            && mOutputStream instanceof FileOutputStream) {
                            ((FileOutputStream) mOutputStream).getFD().sync();
                    }
                } catch (IOException e) {
                    Log.d(LOG_TAG,
                            "IOException during closing the output stream: "
                                    + e.getMessage());
                } finally {
                    try {
                        mWriter.close();
                    } catch (IOException e) {
                    }
                }
            }
        }

        @Override
        public void finalize() {
            if (!mFinishIsCalled) {
                onTerminate();
            }
        }
    }

    public static final String VCARD_TYPE_STRING_DOCOMO = "docomo";

    private static final String VCARD_PROPERTY_ADR = "ADR";
    private static final String VCARD_PROPERTY_BEGIN = "BEGIN";
    private static final String VCARD_PROPERTY_EMAIL = "EMAIL";
    private static final String VCARD_PROPERTY_END = "END";
    private static final String VCARD_PROPERTY_NAME = "N";
    private static final String VCARD_PROPERTY_FULL_NAME = "FN";
    private static final String VCARD_PROPERTY_NOTE = "NOTE";
    private static final String VCARD_PROPERTY_ORG = "ORG";
    private static final String VCARD_PROPERTY_SOUND = "SOUND";
    private static final String VCARD_PROPERTY_SORT_STRING = "SORT-STRING";
    private static final String VCARD_PROPERTY_NICKNAME = "NICKNAME";
    private static final String VCARD_PROPERTY_TEL = "TEL";
    private static final String VCARD_PROPERTY_TITLE = "TITLE";
    private static final String VCARD_PROPERTY_PHOTO = "PHOTO";
    private static final String VCARD_PROPERTY_VERSION = "VERSION";
    private static final String VCARD_PROPERTY_URL = "URL";
    private static final String VCARD_PROPERTY_BIRTHDAY = "BDAY";

    private static final String VCARD_PROPERTY_X_PHONETIC_FIRST_NAME = "X-PHONETIC-FIRST-NAME";
    private static final String VCARD_PROPERTY_X_PHONETIC_MIDDLE_NAME = "X-PHONETIC-MIDDLE-NAME";
    private static final String VCARD_PROPERTY_X_PHONETIC_LAST_NAME = "X-PHONETIC-LAST-NAME";

    // Android specific properties
    private static final String VCARD_PROPERTY_X_PHONETIC_NAME = "X-PHONETIC-NAME";
    private static final String VCARD_PROPERTY_X_NICKNAME = "X-NICKNAME";
    // TODO: add properties like X-LATITUDE

    // Properties for DoCoMo vCard.
    private static final String VCARD_PROPERTY_X_CLASS = "X-CLASS";
    private static final String VCARD_PROPERTY_X_REDUCTION = "X-REDUCTION";
    private static final String VCARD_PROPERTY_X_NO = "X-NO";
    private static final String VCARD_PROPERTY_X_DCM_HMN_MODE = "X-DCM-HMN-MODE";

    private static final String VCARD_DATA_VCARD = "VCARD";
    private static final String VCARD_DATA_PUBLIC = "PUBLIC";

    private static final String VCARD_ATTR_SEPARATOR = ";";
    private static final String VCARD_COL_SEPARATOR = "\r\n";
    private static final String VCARD_DATA_SEPARATOR = ":";
    private static final String VCARD_ITEM_SEPARATOR = ";";
    private static final String VCARD_WS = " ";

    // Type strings are now in VCardConstants.java.

    private static final String VCARD_ATTR_ENCODING_QP = "ENCODING=QUOTED-PRINTABLE";

    private static final String VCARD_ATTR_ENCODING_BASE64_V21 = "ENCODING=BASE64";
    private static final String VCARD_ATTR_ENCODING_BASE64_V30 = "ENCODING=b";

    private static final String SHIFT_JIS = "SHIFT_JIS";

    private final Context mContext;
    private final int mVCardType;
    private final boolean mCareHandlerErrors;
    private final ContentResolver mContentResolver;

    // Convenient member variables about the restriction of the vCard format.
    // Used for not calling the same methods returning same results.
    private final boolean mIsV30;
    private final boolean mIsJapaneseMobilePhone;
    private final boolean mOnlyOneNoteFieldIsAvailable;
    private final boolean mIsDoCoMo;
    private final boolean mUsesQuotedPrintable;
    private final boolean mUsesAndroidProperty;
    private final boolean mUsesDefactProperty;
    private final boolean mUsesShiftJis;

    private Cursor mCursor;
    private int mIdColumn;

    private String mCharsetString;
    private static String mVCardAttributeCharset;
    private boolean mTerminateIsCalled;
    private List<OneEntryHandler> mHandlerList;

    private String mErrorReason = "No error";

    private static final Map<Integer, String> sImMap;
    
    static {
        sImMap = new HashMap<Integer, String>();
        sImMap.put(Im.PROTOCOL_AIM, Constants.PROPERTY_X_AIM);
        sImMap.put(Im.PROTOCOL_MSN, Constants.PROPERTY_X_MSN);
        sImMap.put(Im.PROTOCOL_YAHOO, Constants.PROPERTY_X_YAHOO);
        sImMap.put(Im.PROTOCOL_ICQ, Constants.PROPERTY_X_ICQ);
        sImMap.put(Im.PROTOCOL_JABBER, Constants.PROPERTY_X_JABBER);
        sImMap.put(Im.PROTOCOL_SKYPE, Constants.PROPERTY_X_SKYPE_USERNAME);
        // Google talk is a special case.
    }
    
    
    public VCardComposer(Context context) {
        this(context, VCardConfig.VCARD_TYPE_DEFAULT, true);
    }

    public VCardComposer(Context context, String vcardTypeStr,
            boolean careHandlerErrors) {
        this(context, VCardConfig.getVCardTypeFromString(vcardTypeStr),
                careHandlerErrors);
    }

    public VCardComposer(Context context, int vcardType, boolean careHandlerErrors) {
        mContext = context;
        mVCardType = vcardType;
        mCareHandlerErrors = careHandlerErrors;
        mContentResolver = context.getContentResolver();

        mIsV30 = VCardConfig.isV30(vcardType);
        mUsesQuotedPrintable = VCardConfig.usesQuotedPrintable(vcardType);
        mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
        mIsJapaneseMobilePhone = VCardConfig
                .needsToConvertPhoneticString(vcardType);
        mOnlyOneNoteFieldIsAvailable = VCardConfig
                .onlyOneNoteFieldIsAvailable(vcardType);
        mUsesAndroidProperty = VCardConfig
                .usesAndroidSpecificProperty(vcardType);
        mUsesDefactProperty = VCardConfig.usesDefactProperty(vcardType);
        mUsesShiftJis = VCardConfig.usesShiftJis(vcardType);

        if (mIsDoCoMo) {
            mCharsetString = CharsetUtils.charsetForVendor(SHIFT_JIS, "docomo").name();
            // Do not use mCharsetString bellow since it is different from "SHIFT_JIS" but
            // may be "DOCOMO_SHIFT_JIS" or something like that (internal expression used in
            // Android, not shown to the public).
            mVCardAttributeCharset = "CHARSET=" + SHIFT_JIS;
        } else if (mUsesShiftJis) {
            mCharsetString = CharsetUtils.charsetForVendor(SHIFT_JIS).name();
            mVCardAttributeCharset = "CHARSET=" + SHIFT_JIS;
        } else {
            mCharsetString = "UTF-8";
            mVCardAttributeCharset = "CHARSET=UTF-8";
        }
    }

    /**
     * Must call before {{@link #init()}.
     */
    public void addHandler(OneEntryHandler handler) {
        if (mHandlerList == null) {
            mHandlerList = new ArrayList<OneEntryHandler>();
        }
        mHandlerList.add(handler);
    }

    public boolean init() {
        return init(null, null);
    }

    /**
     * @return Returns true when initialization is successful and all the other
     *         methods are available. Returns false otherwise.
     */
    public boolean init(final String selection, final String[] selectionArgs) {
        if (mCareHandlerErrors) {
            List<OneEntryHandler> finishedList = new ArrayList<OneEntryHandler>(
                    mHandlerList.size());
            for (OneEntryHandler handler : mHandlerList) {
                if (!handler.onInit(mContext)) {
                    for (OneEntryHandler finished : finishedList) {
                        finished.onTerminate();
                    }
                    return false;
                }
            }
        } else {
            // Just ignore the false returned from onInit().
            for (OneEntryHandler handler : mHandlerList) {
                handler.onInit(mContext);
            }
        }

        final String[] projection = new String[] {Contacts._ID,};

        // TODO: thorow an appropriate exception!
        mCursor = mContentResolver.query(RawContacts.CONTENT_URI, projection,
                selection, selectionArgs, null);
        if (mCursor == null || !mCursor.moveToFirst()) {
            if (mCursor != null) {
                try {
                    mCursor.close();
                } catch (SQLiteException e) {
                    Log.e(LOG_TAG, "SQLiteException on Cursor#close(): "
                            + e.getMessage());
                }
                mCursor = null;
            }
            mErrorReason = "Getting database information failed.";
            return false;
        }

        mIdColumn = mCursor.getColumnIndex(Contacts._ID);

        return true;
    }

    public boolean createOneEntry() {
        if (mCursor == null || mCursor.isAfterLast()) {
            // TODO: ditto
            mErrorReason = "Not initialized or database has some problem.";
            return false;
        }
        String name = null;
        String vcard;
        try {
            vcard = createOneEntryInternal(mCursor.getString(mIdColumn));
        } catch (OutOfMemoryError error) {
            // Maybe some data (e.g. photo) is too big to have in memory. But it
            // should be rare.
            Log.e(LOG_TAG, "OutOfMemoryError occured. Ignore the entry: "
                    + name);
            System.gc();
            // TODO: should tell users what happened?
            return true;
        } finally {
            mCursor.moveToNext();
        }

        // This function does not care the OutOfMemoryError on the handler side
        // :-P
        if (mCareHandlerErrors) {
            List<OneEntryHandler> finishedList = new ArrayList<OneEntryHandler>(
                    mHandlerList.size());
            for (OneEntryHandler handler : mHandlerList) {
                if (!handler.onEntryCreated(vcard)) {
                    return false;
                }
            }
        } else {
            for (OneEntryHandler handler : mHandlerList) {
                handler.onEntryCreated(vcard);
            }
        }

        return true;
    }

    private String createOneEntryInternal(final String contactId) {
        final StringBuilder builder = new StringBuilder();
        appendVCardLine(builder, VCARD_PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (mIsV30) {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V21);
        }

        final Map<String, List<ContentValues>> contentValuesListMap =
            new HashMap<String, List<ContentValues>>();

        final String selection = Data.RAW_CONTACT_ID + "=?";
        final String[] selectionArgs = new String[] {contactId};
        EntityIterator entityIterator = null;
        try {
            entityIterator = mContentResolver.queryEntities(
                    RawContacts.CONTENT_URI, selection, selectionArgs, null);
            while (entityIterator.hasNext()) {
                Entity entity = entityIterator.next();
                for (NamedContentValues namedContentValues : entity
                        .getSubValues()) {
                    ContentValues contentValues = namedContentValues.values;
                    String key = contentValues.getAsString(Data.MIMETYPE);
                    if (key != null) {
                        List<ContentValues> contentValuesList = contentValuesListMap
                                .get(key);
                        if (contentValuesList == null) {
                            contentValuesList = new ArrayList<ContentValues>();
                            contentValuesListMap.put(key, contentValuesList);
                        }
                        contentValuesList.add(contentValues);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, String.format("RemoteException at id %s (%s)",
                    contactId, e.getMessage()));
            return "";
        } finally {
            if (entityIterator != null) {
                entityIterator.close();
            }
        }

        // TODO: consolidate order? (low priority)
        appendStructuredNames(builder, contentValuesListMap);
        appendNickNames(builder, contentValuesListMap);
        appendPhones(builder, contentValuesListMap);
        appendEmails(builder, contentValuesListMap);
        appendPostals(builder, contentValuesListMap);
        appendIms(builder, contentValuesListMap);
        appendWebsites(builder, contentValuesListMap);
        appendBirthday(builder, contentValuesListMap);
        appendOrganizations(builder, contentValuesListMap);
        appendPhotos(builder, contentValuesListMap);
        appendNotes(builder, contentValuesListMap);
        // TODO: GroupMembership... What?

        if (mIsDoCoMo) {
            appendVCardLine(builder, VCARD_PROPERTY_X_CLASS, VCARD_DATA_PUBLIC);
            appendVCardLine(builder, VCARD_PROPERTY_X_REDUCTION, "");
            appendVCardLine(builder, VCARD_PROPERTY_X_NO, "");
            appendVCardLine(builder, VCARD_PROPERTY_X_DCM_HMN_MODE, "");
        }

        appendVCardLine(builder, VCARD_PROPERTY_END, VCARD_DATA_VCARD);

        return builder.toString();
    }

    public void terminate() {
        for (OneEntryHandler handler : mHandlerList) {
            handler.onTerminate();
        }

        if (mCursor != null) {
            try {
                mCursor.close();
            } catch (SQLiteException e) {
                Log.e(LOG_TAG, "SQLiteException on Cursor#close(): "
                        + e.getMessage());
            }
            mCursor = null;
        }

        mTerminateIsCalled = true;
    }

    @Override
    public void finalize() {
        if (!mTerminateIsCalled) {
            terminate();
        }
    }

    public int getCount() {
        if (mCursor == null) {
            return 0;
        }
        return mCursor.getCount();
    }

    public boolean isAfterLast() {
        if (mCursor == null) {
            return false;
        }
        return mCursor.isAfterLast();
    }

    /**
     * @return Return the error reason if possible.
     */
    public String getErrorReason() {
        return mErrorReason;
    }

    private void appendStructuredNames(StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(StructuredName.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            appendStructuredNamesInternal(builder, contentValuesList);
        } else if (mIsDoCoMo) {
            appendVCardLine(builder, VCARD_PROPERTY_NAME, "");
        }
    }

    private void appendStructuredNamesInternal(final StringBuilder builder,
            final List<ContentValues> contentValuesList) {
        for (ContentValues contentValues : contentValuesList) {
            final String familyName = contentValues
                    .getAsString(StructuredName.FAMILY_NAME);
            final String middleName = contentValues
                    .getAsString(StructuredName.MIDDLE_NAME);
            final String givenName = contentValues
                    .getAsString(StructuredName.GIVEN_NAME);
            final String prefix = contentValues
                    .getAsString(StructuredName.PREFIX);
            final String suffix = contentValues
                    .getAsString(StructuredName.SUFFIX);
            final String displayName = contentValues
                    .getAsString(StructuredName.DISPLAY_NAME);

            // For now, some primary element is not encoded into Quoted-Printable, which is not
            // valid in vCard spec strictly. In the future, we may have to have some flag to
            // enable composer to encode these primary field into Quoted-Printable.
            if (!TextUtils.isEmpty(familyName) || !TextUtils.isEmpty(givenName)) {
                final String encodedFamily = escapeCharacters(familyName);
                final String encodedGiven = escapeCharacters(givenName);
                final String encodedMiddle = escapeCharacters(middleName);
                final String encodedPrefix = escapeCharacters(prefix);
                final String encodedSuffix = escapeCharacters(suffix);

                // N property. This order is specified by vCard spec and does not depend on countries. 
                builder.append(VCARD_PROPERTY_NAME);
                if (!(VCardUtils.containsOnlyAscii(familyName) &&
                        VCardUtils.containsOnlyAscii(givenName) &&
                        VCardUtils.containsOnlyAscii(middleName) &&
                        VCardUtils.containsOnlyAscii(prefix) &&
                        VCardUtils.containsOnlyAscii(suffix))) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);   
                }

                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedFamily);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(encodedGiven);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(encodedMiddle);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(encodedPrefix);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(encodedSuffix);
                builder.append(VCARD_COL_SEPARATOR);

                final String encodedFullname = VCardUtils.constructNameFromElements(
                        VCardConfig.getNameOrderType(mVCardType),
                        encodedFamily, encodedMiddle, encodedGiven, encodedPrefix, encodedSuffix);

                // FN property
                builder.append(VCARD_PROPERTY_FULL_NAME);
                builder.append(VCARD_ATTR_SEPARATOR);
                if (!VCardUtils.containsOnlyAscii(encodedFullname)) {
                    builder.append(mVCardAttributeCharset);
                    builder.append(VCARD_DATA_SEPARATOR);
                }
                builder.append(encodedFullname);
                builder.append(VCARD_COL_SEPARATOR);
            } else if (!TextUtils.isEmpty(displayName)) {
                builder.append(VCARD_PROPERTY_NAME);
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(mVCardAttributeCharset);
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(escapeCharacters(displayName));
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_COL_SEPARATOR);
            } else if (mIsDoCoMo) {
                appendVCardLine(builder, VCARD_PROPERTY_NAME, "");
            }

            String phoneticFamilyName = contentValues
                    .getAsString(StructuredName.PHONETIC_FAMILY_NAME);
            String phoneticMiddleName = contentValues
                    .getAsString(StructuredName.PHONETIC_MIDDLE_NAME);
            String phoneticGivenName = contentValues
                    .getAsString(StructuredName.PHONETIC_GIVEN_NAME);
            if (!(TextUtils.isEmpty(phoneticFamilyName)
                    && TextUtils.isEmpty(phoneticMiddleName) && TextUtils
                    .isEmpty(phoneticGivenName))) { // if not empty
                if (mIsJapaneseMobilePhone) {
                    phoneticFamilyName = VCardUtils
                            .toHalfWidthString(phoneticFamilyName);
                    phoneticMiddleName = VCardUtils
                            .toHalfWidthString(phoneticMiddleName);
                    phoneticGivenName = VCardUtils
                            .toHalfWidthString(phoneticGivenName);
                }

                if (mIsV30) {
                    final String sortString = VCardUtils
                            .constructNameFromElements(mVCardType,
                                    phoneticFamilyName, phoneticMiddleName,
                                    phoneticGivenName);
                    builder.append(VCARD_PROPERTY_SORT_STRING);

                    if (!VCardUtils.containsOnlyAscii(sortString)) {
                        // Strictly, adding charset information is NOT valid in
                        // VCard 3.0,
                        // but we'll add this info since parser side may be able to
                        // use the charset via
                        // this attribute field.
                        // 
                        // e.g. Japanese mobile phones use Shift_Jis while RFC 2426
                        // recommends
                        // UTF-8. By adding this field, parsers may be able to know
                        // this text
                        // is NOT UTF-8 but Shift_Jis.
                        builder.append(VCARD_ATTR_SEPARATOR);
                        builder.append(mVCardAttributeCharset);
                    }

                    builder.append(VCARD_DATA_SEPARATOR);
                    builder.append(sortString);
                    builder.append(VCARD_COL_SEPARATOR);
                } else {
                    // Note: There is no appropriate property for expressing
                    // phonetic name in
                    // VCard 2.1, while there is in VCard 3.0 (SORT-STRING).
                    // We chose to use DoCoMo's way since it is supported by a
                    // lot of
                    // Japanese mobile phones.
                    //
                    // TODO: should use Quoted-Pritable?
                    builder.append(VCARD_PROPERTY_SOUND);
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(Constants.ATTR_TYPE_X_IRMC_N);
                    builder.append(VCARD_ATTR_SEPARATOR);
                    
                    if (!(VCardUtils.containsOnlyAscii(phoneticFamilyName) &&
                            VCardUtils.containsOnlyAscii(phoneticMiddleName) &&
                            VCardUtils.containsOnlyAscii(phoneticGivenName))) {
                        builder.append(mVCardAttributeCharset);
                        builder.append(VCARD_DATA_SEPARATOR);                        
                    }

                    builder.append(escapeCharacters(phoneticFamilyName));
                    builder.append(VCARD_ITEM_SEPARATOR);
                    builder.append(escapeCharacters(phoneticMiddleName));
                    builder.append(VCARD_ITEM_SEPARATOR);
                    builder.append(escapeCharacters(phoneticGivenName));
                    builder.append(VCARD_ITEM_SEPARATOR);
                    builder.append(VCARD_ITEM_SEPARATOR);
                    builder.append(VCARD_COL_SEPARATOR);

                    if (mUsesAndroidProperty) {
                        final String phoneticName = VCardUtils
                                .constructNameFromElements(mVCardType,
                                        phoneticFamilyName, phoneticMiddleName,
                                        phoneticGivenName);
                        builder.append(VCARD_PROPERTY_X_PHONETIC_NAME);

                        if (!VCardUtils.containsOnlyAscii(phoneticName)) {
                            builder.append(VCARD_ATTR_SEPARATOR);
                            builder.append(mVCardAttributeCharset);
                        }

                        builder.append(VCARD_DATA_SEPARATOR);
                        // TODO: may need to make the text quoted-printable.
                        builder.append(phoneticName);
                        builder.append(VCARD_COL_SEPARATOR);
                    }
                }
            } else if (mIsDoCoMo) {
                builder.append(VCARD_PROPERTY_SOUND);
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(Constants.ATTR_TYPE_X_IRMC_N);
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_COL_SEPARATOR);
            }

            if (mUsesDefactProperty) {
                if (!TextUtils.isEmpty(phoneticGivenName)) {
                    builder.append(VCARD_PROPERTY_X_PHONETIC_FIRST_NAME);
                    builder.append(VCARD_DATA_SEPARATOR);
                    builder.append(phoneticGivenName);
                    builder.append(VCARD_COL_SEPARATOR);
                }
                if (!TextUtils.isEmpty(phoneticMiddleName)) {
                    builder.append(VCARD_PROPERTY_X_PHONETIC_MIDDLE_NAME);
                    builder.append(VCARD_DATA_SEPARATOR);
                    builder.append(phoneticMiddleName);
                    builder.append(VCARD_COL_SEPARATOR);
                }
                if (!TextUtils.isEmpty(phoneticFamilyName)) {
                    builder.append(VCARD_PROPERTY_X_PHONETIC_LAST_NAME);
                    builder.append(VCARD_DATA_SEPARATOR);
                    builder.append(phoneticFamilyName);
                    builder.append(VCARD_COL_SEPARATOR);
                }
            }
        }
    }

    private void appendNickNames(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Nickname.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            final String propertyNickname;
            if (mIsV30) {
                propertyNickname = VCARD_PROPERTY_NICKNAME;
            } else if (mUsesAndroidProperty) {
                propertyNickname = VCARD_PROPERTY_X_NICKNAME;
            } else {
                // There's no way to add this field.
                return;
            }

            for (ContentValues contentValues : contentValuesList) {
                final String nickname = contentValues
                        .getAsString(Nickname.NAME);
                if (TextUtils.isEmpty(nickname)) {
                    continue;
                }
                builder.append(propertyNickname);

                if (!VCardUtils.containsOnlyAscii(propertyNickname)) {
                    //  Strictly, this is not valid in vCard 3.0. See above.
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }

                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(escapeCharacters(nickname));
                builder.append(VCARD_COL_SEPARATOR);
            }
        }
    }

    private void appendPhones(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Phone.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                appendVCardTelephoneLine(builder, contentValues
                        .getAsInteger(Phone.TYPE), contentValues
                        .getAsString(Phone.LABEL), contentValues
                        .getAsString(Phone.NUMBER));
            }
        } else if (mIsDoCoMo) {
            appendVCardTelephoneLine(builder, Phone.TYPE_HOME, "", "");
        }
    }

    private void appendEmails(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Email.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                appendVCardEmailLine(builder, contentValues
                        .getAsInteger(Email.TYPE), contentValues
                        .getAsString(Email.LABEL), contentValues
                        .getAsString(Email.DATA));
            }
        } else if (mIsDoCoMo) {
            appendVCardEmailLine(builder, Email.TYPE_HOME, "", "");
        }
    }

    private void appendPostals(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(StructuredPostal.CONTENT_ITEM_TYPE);

        if (contentValuesList != null) {
            if (mIsDoCoMo) {
                appendPostalsForDoCoMo(builder, contentValuesList);
            } else {
                appendPostalsForGeneric(builder, contentValuesList);
            }
        } else if (mIsDoCoMo) {
            builder.append(VCARD_PROPERTY_ADR);
            builder.append(VCARD_ATTR_SEPARATOR);
            builder.append(Constants.ATTR_TYPE_HOME);
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(VCARD_COL_SEPARATOR);
        }
    }
    
    /**
     * Try to append just one line. If there's no appropriate address
     * information, append an empty line.
     */
    private void appendPostalsForDoCoMo(final StringBuilder builder,
            final List<ContentValues> contentValuesList) {
        // TODO: from old, inefficient code. fix this.
        if (appendPostalsForDoCoMoInternal(builder, contentValuesList,
                StructuredPostal.TYPE_HOME)) {
            return;
        }
        if (appendPostalsForDoCoMoInternal(builder, contentValuesList,
                StructuredPostal.TYPE_WORK)) {
            return;
        }
        if (appendPostalsForDoCoMoInternal(builder, contentValuesList,
                StructuredPostal.TYPE_OTHER)) {
            return;
        }
        if (appendPostalsForDoCoMoInternal(builder, contentValuesList,
                StructuredPostal.TYPE_CUSTOM)) {
            return;
        }

        Log.w(LOG_TAG,
                "Should not come here. Must have at least one postal data.");
    }

    private boolean appendPostalsForDoCoMoInternal(final StringBuilder builder,
            final List<ContentValues> contentValuesList, int preferedType) {
        for (ContentValues contentValues : contentValuesList) {
            final int type = contentValues.getAsInteger(StructuredPostal.TYPE);
            final String label = contentValues
                    .getAsString(StructuredPostal.LABEL);
            if (type == preferedType) {
                appendVCardPostalLine(builder, type, label, contentValues);
                return true;
            }
        }
        return false;
    }

    private void appendPostalsForGeneric(final StringBuilder builder,
            final List<ContentValues> contentValuesList) {
        for (ContentValues contentValues : contentValuesList) {
            final int type = contentValues.getAsInteger(StructuredPostal.TYPE);
            final String label = contentValues
                    .getAsString(StructuredPostal.LABEL);
            appendVCardPostalLine(builder, type, label, contentValues);
        }
    }

    private void appendIms(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Im.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                int type = contentValues.getAsInteger(Im.PROTOCOL);
                String data = contentValues.getAsString(Im.DATA);
                
                Log.d("@@@", "Im information. protocol=\"" + type +
                        "\", data=\"" + data + "\", protocol=\"" +
                        contentValues.getAsString(Im.PROTOCOL) + "\", custom_protocol=\"" +
                        contentValues.getAsString(Im.CUSTOM_PROTOCOL) + "\"");

                if (type == Im.PROTOCOL_GOOGLE_TALK) {
                    if (VCardConfig.usesAndroidSpecificProperty(mVCardType)) {
                        appendVCardLine(builder, Constants.PROPERTY_X_GOOGLE_TALK, data);
                    }
                    // TODO: add "X-GOOGLE TALK" case...
                }
            }            
        }
    }
    
    private void appendWebsites(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Website.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                final String website = contentValues.getAsString(Website.URL);
                appendVCardLine(builder, VCARD_PROPERTY_URL, website);
            }
        }
    }
    
    private void appendBirthday(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Website.CONTENT_ITEM_TYPE);
        if (contentValuesList != null && contentValuesList.size() > 0) {
            // Theoretically, there must be only one birthday for each vCard data and
            // we are afraid of some parse error occuring in some devices, so
            // we emit only one birthday entry for now.
            final String birthday = contentValuesList.get(0).getAsString(Miscellaneous.BIRTHDAY);
            appendVCardLine(builder, VCARD_PROPERTY_BIRTHDAY, birthday);
        }
    }

    private void appendOrganizations(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Organization.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                final String company = contentValues
                        .getAsString(Organization.COMPANY);
                final String title = contentValues
                        .getAsString(Organization.TITLE);
                appendVCardLine(builder, VCARD_PROPERTY_ORG, company, true,
                        mUsesQuotedPrintable);
                appendVCardLine(builder, VCARD_PROPERTY_TITLE, title, true,
                        mUsesQuotedPrintable);
            }
        }
    }

    private void appendPhotos(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        List<ContentValues> contentValuesList = contentValuesListMap
                .get(Photo.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                byte[] data = contentValues.getAsByteArray(Photo.PHOTO);
                if (data == null) {
                    continue;
                }
                final String photoType;
                // Use some heuristics for guessing the format of the image.
                // TODO: there should be some general API for detecting the file format.
                if (data.length >= 3 && data[0] == 'G' && data[1] == 'I'
                        && data[2] == 'F') {
                    photoType = "GIF";
                } else if (data.length >= 4 && data[0] == (byte) 0x89
                        && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
                    // Note: vCard 2.1 officially does not support PNG, but we
                    // may have it
                    // and using X- word like "X-PNG" may not let importers know
                    // it is
                    // PNG. So we use the String "PNG" as is...
                    photoType = "PNG";
                } else if (data.length >= 2 && data[0] == (byte) 0xff
                        && data[1] == (byte) 0xd8) {
                    photoType = "JPEG";
                } else {
                    Log.d(LOG_TAG, "Unknown photo type. Ignore.");
                    continue;
                }
                String photoString = VCardUtils.encodeBase64(data);
                if (photoString.length() > 0) {
                    appendVCardPhotoLine(builder, photoString, photoType);
                }
            }
        }
    }

    private void appendNotes(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList =
            contentValuesListMap.get(Note.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            if (mOnlyOneNoteFieldIsAvailable) {
                StringBuilder noteBuilder = new StringBuilder();
                boolean first = true;
                for (ContentValues contentValues : contentValuesList) {
                    final String note = contentValues.getAsString(Note.NOTE);
                    if (note.length() > 0) {
                        if (first) {
                            first = false;
                        } else {
                            noteBuilder.append('\n');
                        }
                        noteBuilder.append(note);
                    }
                }
                appendVCardLine(builder, VCARD_PROPERTY_NOTE, noteBuilder.toString(),
                        true, mUsesQuotedPrintable);
            } else {
                for (ContentValues contentValues : contentValuesList) {
                    final String note = contentValues.getAsString(Note.NOTE);
                    if (!TextUtils.isEmpty(note)) {
                        appendVCardLine(builder, VCARD_PROPERTY_NOTE, note, true,
                                mUsesQuotedPrintable);
                    }
                }
            }
        }
    }

    /**
     * Append '\' to the characters which should be escaped. The character set is different
     * not only between vCard 2.1 and vCard 3.0 but also among each device.
     * 
     * Note that Quoted-Printable string must not be input here.
     */
    @SuppressWarnings("fallthrough")
    private String escapeCharacters(String unescaped) {
        if (TextUtils.isEmpty(unescaped)) {
            return "";
        }
        
        StringBuilder builder = new StringBuilder();
        final int length = unescaped.length();
        for (int i = 0; i < length; i++) {
            char ch = unescaped.charAt(i);
            switch (ch) {
            case ';':
                builder.append('\\');
                builder.append(';');
                break;
            case '\r':
                if (i + 1 < length) {
                    char nextChar = unescaped.charAt(i);
                    if (nextChar == '\n') {
                        continue;
                    } else {
                        // fall through
                    }
                } else {
                    // fall through
                }
            case '\n':
                // In vCard 2.1, there's no specification about this, while
                // vCard 3.0 explicitly
                // requires this should be encoded to "\n".
                builder.append("\\n");
                break;
            case '\\':
                if (mIsV30) {
                    builder.append("\\\\");
                    break;
                }
            case '<':
            case '>':
                if (mIsDoCoMo) {
                    builder.append('\\');
                    builder.append(ch);
                }
                break;
            case ',':
                if (mIsV30) {
                    builder.append("\\,");
                    break;
                }
            default:
                builder.append(ch);
                break;
            }
        }
        return builder.toString();
    }

    private void appendVCardPhotoLine(StringBuilder builder,
            String encodedData, String type) {
        StringBuilder tmpBuilder = new StringBuilder();
        tmpBuilder.append(VCARD_PROPERTY_PHOTO);
        tmpBuilder.append(VCARD_ATTR_SEPARATOR);
        if (mIsV30) {
            tmpBuilder.append(VCARD_ATTR_ENCODING_BASE64_V30);
        } else {
            tmpBuilder.append(VCARD_ATTR_ENCODING_BASE64_V21);
        }
        tmpBuilder.append(VCARD_ATTR_SEPARATOR);
        tmpBuilder.append("TYPE=");
        tmpBuilder.append(type);
        tmpBuilder.append(VCARD_DATA_SEPARATOR);
        tmpBuilder.append(encodedData);

        String tmpStr = tmpBuilder.toString();
        tmpBuilder = new StringBuilder();
        int lineCount = 0;
        for (int i = 0; i < tmpStr.length(); i++) {
            tmpBuilder.append(tmpStr.charAt(i));
            lineCount++;
            if (lineCount > 72) {
                tmpBuilder.append(VCARD_COL_SEPARATOR);
                tmpBuilder.append(VCARD_WS);
                lineCount = 0;
            }
        }
        builder.append(tmpBuilder.toString());
        builder.append(VCARD_COL_SEPARATOR);
        builder.append(VCARD_COL_SEPARATOR);
    }

    private void appendVCardPostalLine(StringBuilder builder, int type,
            String label, final ContentValues contentValues) {
        builder.append(VCARD_PROPERTY_ADR);
        builder.append(VCARD_ATTR_SEPARATOR);

        boolean dataExists = false;
        String[] dataArray = VCardUtils.getVCardPostalElements(contentValues);
        int length = dataArray.length;
        final boolean useQuotedPrintable = mUsesQuotedPrintable;
        for (int i = 0; i < length; i++) {
            String data = dataArray[i];
            if (!TextUtils.isEmpty(data)) {
                dataExists = true;
                if (useQuotedPrintable) {
                    dataArray[i] = encodeQuotedPrintable(data);
                } else {
                    dataArray[i] = escapeCharacters(data);
                }
            }
        }

        boolean typeIsAppended = false;
        switch (type) {
        case StructuredPostal.TYPE_HOME:
            builder.append(Constants.ATTR_TYPE_HOME);
            typeIsAppended = true;
            break;
        case StructuredPostal.TYPE_WORK:
            builder.append(Constants.ATTR_TYPE_WORK);
            typeIsAppended = true;
            break;
        case StructuredPostal.TYPE_CUSTOM:
            if (mUsesAndroidProperty && VCardUtils.containsOnlyAlphaDigitHyphen(label)){
                // We're not sure whether the label is valid in the spec ("IANA-token" in the vCard 3.1
                // is unclear...)
                // Just for safety, we add "X-" at the beggining of each label.
                // Also checks the label obeys with vCard 3.0 spec.
                builder.append("X-");
                builder.append(label);
                builder.append(VCARD_DATA_SEPARATOR);
            }
            break;
        case StructuredPostal.TYPE_OTHER:
            break;
        default:
            Log.e(LOG_TAG, "Unknown StructuredPostal type: " + type);
            break;
        }

        if (dataExists) {
            if (typeIsAppended) {
                builder.append(VCARD_ATTR_SEPARATOR);
            }
            // Strictly, vCard 3.0 does not allow this, but we add this since
            // this information
            // should be useful, Assume no parser does not emit error with this
            // attribute.
            builder.append(mVCardAttributeCharset);
            if (useQuotedPrintable) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(VCARD_ATTR_ENCODING_QP);
            }
        }
        builder.append(VCARD_DATA_SEPARATOR);
        if (dataExists) {
            // The elements in dataArray are already encoded to quoted printable
            // if needed.
            // See above.
            //
            // TODO: in vCard 3.0, one line may become too huge. Fix this.
            builder.append(dataArray[0]);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(dataArray[1]);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(dataArray[2]);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(dataArray[3]);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(dataArray[4]);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(dataArray[5]);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(dataArray[6]);
        }
        builder.append(VCARD_COL_SEPARATOR);
    }

    private void appendVCardEmailLine(StringBuilder builder, int type,
            String label, String data) {
        builder.append(VCARD_PROPERTY_EMAIL);
        builder.append(VCARD_ATTR_SEPARATOR);

        switch (type) {
        case Email.TYPE_CUSTOM:
            if (label.equals(
                    android.provider.Contacts.ContactMethodsColumns.MOBILE_EMAIL_TYPE_NAME)) {
                builder.append(Constants.ATTR_TYPE_CELL);
            } else if (mUsesAndroidProperty && VCardUtils.containsOnlyAlphaDigitHyphen(label)){
                builder.append("X-");
                builder.append(label);
            } else {
                // Default to INTERNET.
                builder.append(Constants.ATTR_TYPE_INTERNET);
            }
            break;
        case Email.TYPE_HOME:
            builder.append(Constants.ATTR_TYPE_HOME);
            break;
        case Email.TYPE_WORK:
            builder.append(Constants.ATTR_TYPE_WORK);
            break;
        case Email.TYPE_OTHER:
            builder.append(Constants.ATTR_TYPE_INTERNET);
            break;
        default:
            Log.e(LOG_TAG, "Unknown Email type: " + type);
            builder.append(Constants.ATTR_TYPE_INTERNET);
            break;
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(data);
        builder.append(VCARD_COL_SEPARATOR);
    }

    private void appendVCardTelephoneLine(StringBuilder builder, int type,
            String label, String encodedData) {
        builder.append(VCARD_PROPERTY_TEL);
        builder.append(VCARD_ATTR_SEPARATOR);

        switch (type) {
        case Phone.TYPE_HOME:
            appendTypeAttributes(builder, Arrays.asList(
                    Constants.ATTR_TYPE_HOME, Constants.ATTR_TYPE_VOICE));
            break;
        case Phone.TYPE_WORK:
            appendTypeAttributes(builder, Arrays.asList(
                    Constants.ATTR_TYPE_WORK, Constants.ATTR_TYPE_VOICE));
            break;
        case Phone.TYPE_FAX_HOME:
            appendTypeAttributes(builder, Arrays.asList(
                    Constants.ATTR_TYPE_HOME, Constants.ATTR_TYPE_FAX));
            break;
        case Phone.TYPE_FAX_WORK:
            appendTypeAttributes(builder, Arrays.asList(
                    Constants.ATTR_TYPE_WORK, Constants.ATTR_TYPE_FAX));
            break;
        case Phone.TYPE_MOBILE:
            builder.append(Constants.ATTR_TYPE_CELL);
            break;
        case Phone.TYPE_PAGER:
            if (mIsDoCoMo) {
                // Not sure about the reason, but previous implementation had
                // used "VOICE" instead of "PAGER"
                builder.append(Constants.ATTR_TYPE_VOICE);
            } else {
                builder.append(Constants.ATTR_TYPE_PAGER);
            }
            break;
        case Phone.TYPE_OTHER:
            builder.append(Constants.ATTR_TYPE_VOICE);
            break;
        case Phone.TYPE_CUSTOM:
            if (mUsesAndroidProperty) {
                VCardUtils.containsOnlyAlphaDigitHyphen(label);
                builder.append("X-" + label);
            } else {
                // Just ignore the custom type.
                builder.append(Constants.ATTR_TYPE_VOICE);
            }
            break;
        default:
            appendUncommonPhoneType(builder, type);
            break;
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(encodedData);
        builder.append(VCARD_COL_SEPARATOR);
    }

    /**
     * Appends phone type string which may not be available in some devices.
     */
    private void appendUncommonPhoneType(StringBuilder builder, int type) {
        if (mIsDoCoMo) {
            // The previous implementation for DoCoMo had been conservative
            // about
            // miscellaneous types.
            builder.append(Constants.ATTR_TYPE_VOICE);
        } else {
            String phoneAttribute = VCardUtils.getPhoneAttributeString(type);
            if (phoneAttribute != null) {
                builder.append(phoneAttribute);
            } else {
                Log.e(LOG_TAG, "Unknown or unsupported (by vCard) Phone type: " + type);
            }
        }
    }

    private void appendVCardLine(final StringBuilder builder,
            final String propertyName, final String rawData) {
        appendVCardLine(builder, propertyName, rawData, false, false);
    }

    private void appendVCardLine(final StringBuilder builder,
            final String field, final String rawData, boolean needCharset,
            boolean needQuotedPrintable) {
        builder.append(field);
        if (needCharset) {
            builder.append(VCARD_ATTR_SEPARATOR);
            builder.append(mVCardAttributeCharset);
        }

        final String encodedData;
        if (needQuotedPrintable) {
            builder.append(VCARD_ATTR_SEPARATOR);
            builder.append(VCARD_ATTR_ENCODING_QP);
            encodedData = encodeQuotedPrintable(rawData);
        } else {
            // TODO: one line may be too huge, which may be invalid in vCard spec, though
            //       several (even well-known) applications do not care this. 
            encodedData = escapeCharacters(rawData);
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(encodedData);
        builder.append(VCARD_COL_SEPARATOR);
    }

    private void appendTypeAttributes(final StringBuilder builder,
            final List<String> types) {
        // We may have to make this comma separated form like "TYPE=DOM,WORK" in the future,
        // which would be recommended way in vcard 3.0 though not valid in vCard 2.1.
        boolean first = true;
        for (String type : types) {
            if (first) {
                first = false;
            } else {
                builder.append(VCARD_ATTR_SEPARATOR);
            }
            if (mIsV30) {
                builder.append(Constants.ATTR_TYPE);
                builder.append('=');
            }
            builder.append(type);
        }
    }

    private String encodeQuotedPrintable(String str) {
        if (TextUtils.isEmpty(str)) {
            return "";
        }
        {
            // Replace "\n" and "\r" with "\r\n".
            StringBuilder tmpBuilder = new StringBuilder();
            int length = str.length();
            for (int i = 0; i < length; i++) {
                char ch = str.charAt(i);
                if (ch == '\r') {
                    if (i + 1 < length && str.charAt(i + 1) == '\n') {
                        i++;
                    }
                    tmpBuilder.append("\r\n");
                } else if (ch == '\n') {
                    tmpBuilder.append("\r\n");
                } else {
                    tmpBuilder.append(ch);
                }
            }
            str = tmpBuilder.toString();
        }

        StringBuilder builder = new StringBuilder();
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
}
