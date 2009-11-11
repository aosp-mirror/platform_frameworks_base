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
import android.net.Uri;
import android.os.RemoteException;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.telephony.PhoneNumberUtils;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.Time;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final String DEFAULT_EMAIL_TYPE = Constants.ATTR_TYPE_INTERNET;

    public static final String FAILURE_REASON_FAILED_TO_GET_DATABASE_INFO =
        "Failed to get database information";

    public static final String FAILURE_REASON_NO_ENTRY =
        "There's no exportable in the database";

    public static final String FAILURE_REASON_NOT_INITIALIZED =
        "The vCard composer object is not correctly initialized";

    public static final String NO_ERROR = "No error";

    private static final Uri sDataRequestUri;

    static {
        Uri.Builder builder = RawContacts.CONTENT_URI.buildUpon();
        builder.appendQueryParameter(Data.FOR_EXPORT_ONLY, "1");
        sDataRequestUri = builder.build();
    }

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

        final private OutputStream mOutputStream; // mWriter will close this.
        private Writer mWriter;

        private boolean mOnTerminateIsCalled = false;

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
            mOnTerminateIsCalled = true;
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
            if (!mOnTerminateIsCalled) {
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
    // TODO: ues extra MIME-TYPE instead of adding this kind of inflexible fields
    private static final String VCARD_PROPERTY_X_NICKNAME = "X-NICKNAME";

    // Property for call log entry
    private static final String VCARD_PROPERTY_X_TIMESTAMP = "X-IRMC-CALL-DATETIME";
    private static final String VCARD_PROPERTY_CALLTYPE_INCOMING = "INCOMING";
    private static final String VCARD_PROPERTY_CALLTYPE_OUTGOING = "OUTGOING";
    private static final String VCARD_PROPERTY_CALLTYPE_MISSED = "MISSED";

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
    private static final String VCARD_ATTR_EQUAL = "=";

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
    private final boolean mUsesUtf8;
    private final boolean mUsesShiftJis;
    private final boolean mUsesQPToPrimaryProperties;

    private Cursor mCursor;
    private int mIdColumn;

    private final String mCharsetString;
    private final String mVCardAttributeCharset;
    private boolean mTerminateIsCalled;
    final private List<OneEntryHandler> mHandlerList;

    private String mErrorReason = NO_ERROR;

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

    private boolean mIsCallLogComposer = false;

    private boolean mNeedPhotoForVCard = true;

    private static final String[] sContactsProjection = new String[] {
        Contacts._ID,
    };

    /** The projection to use when querying the call log table */
    private static final String[] sCallLogProjection = new String[] {
            Calls.NUMBER, Calls.DATE, Calls.TYPE, Calls.CACHED_NAME, Calls.CACHED_NUMBER_TYPE,
            Calls.CACHED_NUMBER_LABEL
    };
    private static final int NUMBER_COLUMN_INDEX = 0;
    private static final int DATE_COLUMN_INDEX = 1;
    private static final int CALL_TYPE_COLUMN_INDEX = 2;
    private static final int CALLER_NAME_COLUMN_INDEX = 3;
    private static final int CALLER_NUMBERTYPE_COLUMN_INDEX = 4;
    private static final int CALLER_NUMBERLABEL_COLUMN_INDEX = 5;

    private static final String FLAG_TIMEZONE_UTC = "Z";

    public VCardComposer(Context context) {
        this(context, VCardConfig.VCARD_TYPE_DEFAULT, true, false, true);
    }

    public VCardComposer(Context context, String vcardTypeStr,
            boolean careHandlerErrors) {
        this(context, VCardConfig.getVCardTypeFromString(vcardTypeStr),
                careHandlerErrors, false, true);
    }

    public VCardComposer(Context context, int vcardType, boolean careHandlerErrors) {
        this(context, vcardType, careHandlerErrors, false, true);
    }

    /**
     * Construct for supporting call log entry vCard composing.
     *
     * @param isCallLogComposer true if this composer is for creating Call Log vCard.
     */
    public VCardComposer(Context context, int vcardType, boolean careHandlerErrors,
            boolean isCallLogComposer, boolean needPhotoInVCard) {
        mContext = context;
        mVCardType = vcardType;
        mCareHandlerErrors = careHandlerErrors;
        mIsCallLogComposer = isCallLogComposer;
        mNeedPhotoForVCard = needPhotoInVCard;
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
        mUsesUtf8 = VCardConfig.usesUtf8(vcardType);
        mUsesShiftJis = VCardConfig.usesShiftJis(vcardType);
        mUsesQPToPrimaryProperties = VCardConfig.usesQPToPrimaryProperties(vcardType);
        mHandlerList = new ArrayList<OneEntryHandler>();

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
     * This static function is to compose vCard for phone own number
     */
    public String composeVCardForPhoneOwnNumber(int phonetype, String phoneName,
            String phoneNumber, boolean vcardVer21) {
        final StringBuilder builder = new StringBuilder();
        appendVCardLine(builder, VCARD_PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (!vcardVer21) {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V21);
        }

        boolean needCharset = false;
        if (!(VCardUtils.containsOnlyPrintableAscii(phoneName))) {
            needCharset = true;
        }
        // TODO: QP should be used? Using mUsesQPToPrimaryProperties should help.
        appendVCardLine(builder, VCARD_PROPERTY_FULL_NAME, phoneName, needCharset, false);
        appendVCardLine(builder, VCARD_PROPERTY_NAME, phoneName, needCharset, false);

        String label = Integer.toString(phonetype);
        appendVCardTelephoneLine(builder, phonetype, label, phoneNumber);

        appendVCardLine(builder, VCARD_PROPERTY_END, VCARD_DATA_VCARD);

        return builder.toString();
    }

    /**
     * Must call before {{@link #init()}.
     */
    public void addHandler(OneEntryHandler handler) {
        mHandlerList.add(handler);
    }

    public boolean init() {
        return init(null, null);
    }

    /**
     * @return Returns true when initialization is successful and all the other
     *          methods are available. Returns false otherwise.
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

        if (mIsCallLogComposer) {
            mCursor = mContentResolver.query(CallLog.Calls.CONTENT_URI, sCallLogProjection,
                    selection, selectionArgs, null);
        } else {
            mCursor = mContentResolver.query(Contacts.CONTENT_URI, sContactsProjection,
                    selection, selectionArgs, null);
        }

        if (mCursor == null) {
            mErrorReason = FAILURE_REASON_FAILED_TO_GET_DATABASE_INFO;
            return false;
        }

        if (getCount() == 0 || !mCursor.moveToFirst()) {
            try {
                mCursor.close();
            } catch (SQLiteException e) {
                Log.e(LOG_TAG, "SQLiteException on Cursor#close(): " + e.getMessage());
            } finally {
                mCursor = null;
                mErrorReason = FAILURE_REASON_NO_ENTRY;
            }
            return false;
        }

        if (mIsCallLogComposer) {
            mIdColumn = -1;
        } else {
            mIdColumn = mCursor.getColumnIndex(Contacts._ID);
        }

        return true;
    }

    public boolean createOneEntry() {
        if (mCursor == null || mCursor.isAfterLast()) {
            mErrorReason = FAILURE_REASON_NOT_INITIALIZED;
            return false;
        }
        String name = null;
        String vcard;
        try {
            if (mIsCallLogComposer) {
                vcard = createOneCallLogEntryInternal();
            } else {
                if (mIdColumn >= 0) {
                    vcard = createOneEntryInternal(mCursor.getString(mIdColumn));
                } else {
                    Log.e(LOG_TAG, "Incorrect mIdColumn: " + mIdColumn);
                    return true;
                }
            }
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

    /**
     * Format according to RFC 2445 DATETIME type.
     * The format is: ("%Y%m%dT%H%M%SZ").
     */
    private final String toRfc2455Format(final long millSecs) {
        Time startDate = new Time();
        startDate.set(millSecs);
        String date = startDate.format2445();
        return date + FLAG_TIMEZONE_UTC;
    }

    /**
     * Try to append the property line for a call history time stamp field if possible.
     * Do nothing if the call log type gotton from the database is invalid.
     */
    private void tryAppendCallHistoryTimeStampField(final StringBuilder builder) {
        // Extension for call history as defined in
        // in the Specification for Ic Mobile Communcation - ver 1.1,
        // Oct 2000. This is used to send the details of the call
        // history - missed, incoming, outgoing along with date and time
        // to the requesting device (For example, transferring phone book
        // when connected over bluetooth)
        //
        // e.g. "X-IRMC-CALL-DATETIME;MISSED:20050320T100000Z"
        final int callLogType = mCursor.getInt(CALL_TYPE_COLUMN_INDEX);
        final String callLogTypeStr;
        switch (callLogType) {
            case Calls.INCOMING_TYPE: {
                callLogTypeStr = VCARD_PROPERTY_CALLTYPE_INCOMING;
                break;
            }
            case Calls.OUTGOING_TYPE: {
                callLogTypeStr = VCARD_PROPERTY_CALLTYPE_OUTGOING;
                break;
            }
            case Calls.MISSED_TYPE: {
                callLogTypeStr = VCARD_PROPERTY_CALLTYPE_MISSED;
                break;
            }
            default: {
                Log.w(LOG_TAG, "Call log type not correct.");
                return;
            }
        }

        final long dateAsLong = mCursor.getLong(DATE_COLUMN_INDEX);
        builder.append(VCARD_PROPERTY_X_TIMESTAMP);
        builder.append(VCARD_ATTR_SEPARATOR);
        appendTypeAttribute(builder, callLogTypeStr);
        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(toRfc2455Format(dateAsLong));
        builder.append(VCARD_COL_SEPARATOR);
    }

    private String createOneCallLogEntryInternal() {
        final StringBuilder builder = new StringBuilder();
        appendVCardLine(builder, VCARD_PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (mIsV30) {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V21);
        }
        String name = mCursor.getString(CALLER_NAME_COLUMN_INDEX);
        if (TextUtils.isEmpty(name)) {
            name = mCursor.getString(NUMBER_COLUMN_INDEX);
        }
        final boolean needCharset = !(VCardUtils.containsOnlyPrintableAscii(name));
        // TODO: QP should be used? Using mUsesQPToPrimaryProperties should help.
        appendVCardLine(builder, VCARD_PROPERTY_FULL_NAME, name, needCharset, false);
        appendVCardLine(builder, VCARD_PROPERTY_NAME, name, needCharset, false);

        String number = mCursor.getString(NUMBER_COLUMN_INDEX);
        int type = mCursor.getInt(CALLER_NUMBERTYPE_COLUMN_INDEX);
        String label = mCursor.getString(CALLER_NUMBERLABEL_COLUMN_INDEX);
        if (TextUtils.isEmpty(label)) {
            label = Integer.toString(type);
        }
        appendVCardTelephoneLine(builder, type, label, number);
        tryAppendCallHistoryTimeStampField(builder);
        appendVCardLine(builder, VCARD_PROPERTY_END, VCARD_DATA_VCARD);
        return builder.toString();
    }

    private String createOneEntryInternal(final String contactId) {
        final Map<String, List<ContentValues>> contentValuesListMap =
                new HashMap<String, List<ContentValues>>();
        final String selection = Data.CONTACT_ID + "=?";
        final String[] selectionArgs = new String[] {contactId};
        // The resolver may return the entity iterator with no data. It is possiible.
        // e.g. If all the data in the contact of the given contact id are not exportable ones,
        //      they are hidden from the view of this method, though contact id itself exists.
        boolean dataExists = false;
        EntityIterator entityIterator = null;
        try {
            entityIterator = mContentResolver.queryEntities(
                    sDataRequestUri, selection, selectionArgs, null);
            dataExists = entityIterator.hasNext();
            while (entityIterator.hasNext()) {
                Entity entity = entityIterator.next();
                for (NamedContentValues namedContentValues : entity
                        .getSubValues()) {
                    ContentValues contentValues = namedContentValues.values;
                    String key = contentValues.getAsString(Data.MIMETYPE);
                    if (key != null) {
                        List<ContentValues> contentValuesList =
                                contentValuesListMap.get(key);
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

        if (!dataExists) {
            return "";
        }

        final StringBuilder builder = new StringBuilder();
        appendVCardLine(builder, VCARD_PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (mIsV30) {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, VCARD_PROPERTY_VERSION, Constants.VERSION_V21);
        }

        appendStructuredNames(builder, contentValuesListMap);
        appendNickNames(builder, contentValuesListMap);
        appendPhones(builder, contentValuesListMap);
        appendEmails(builder, contentValuesListMap);
        appendPostals(builder, contentValuesListMap);
        appendIms(builder, contentValuesListMap);
        appendWebsites(builder, contentValuesListMap);
        appendBirthday(builder, contentValuesListMap);
        appendOrganizations(builder, contentValuesListMap);
        if (mNeedPhotoForVCard) {
            appendPhotos(builder, contentValuesListMap);
        }
        appendNotes(builder, contentValuesListMap);
        // TODO: GroupMembership

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

    private void appendStructuredNames(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(StructuredName.CONTENT_ITEM_TYPE);
        if (contentValuesList != null && contentValuesList.size() > 0) {
            appendStructuredNamesInternal(builder, contentValuesList);
        } else if (mIsDoCoMo) {
            appendVCardLine(builder, VCARD_PROPERTY_NAME, "");
        } else if (mIsV30) {
            // vCard 3.0 requires "N" and "FN" properties.
            appendVCardLine(builder, VCARD_PROPERTY_NAME, "");
            appendVCardLine(builder, VCARD_PROPERTY_FULL_NAME, "");
        }
    }

    private boolean containsNonEmptyName(ContentValues contentValues) {
        final String familyName = contentValues.getAsString(StructuredName.FAMILY_NAME);
        final String middleName = contentValues.getAsString(StructuredName.MIDDLE_NAME);
        final String givenName = contentValues.getAsString(StructuredName.GIVEN_NAME);
        final String prefix = contentValues.getAsString(StructuredName.PREFIX);
        final String suffix = contentValues.getAsString(StructuredName.SUFFIX);
        final String displayName = contentValues.getAsString(StructuredName.DISPLAY_NAME);
        return !(TextUtils.isEmpty(familyName) && TextUtils.isEmpty(middleName) &&
                TextUtils.isEmpty(givenName) && TextUtils.isEmpty(prefix) &&
                TextUtils.isEmpty(suffix) && TextUtils.isEmpty(displayName));
    }

    private void appendStructuredNamesInternal(final StringBuilder builder,
            final List<ContentValues> contentValuesList) {
        // For safety, we'll emit just one value around StructuredName, as external importers
        // may get confused with multiple "N", "FN", etc. properties, though it is valid in
        // vCard spec.
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
                Integer isPrimary = contentValues.getAsInteger(StructuredName.IS_PRIMARY);
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

        final String familyName = primaryContentValues
                .getAsString(StructuredName.FAMILY_NAME);
        final String middleName = primaryContentValues
                .getAsString(StructuredName.MIDDLE_NAME);
        final String givenName = primaryContentValues
                .getAsString(StructuredName.GIVEN_NAME);
        final String prefix = primaryContentValues
                .getAsString(StructuredName.PREFIX);
        final String suffix = primaryContentValues
                .getAsString(StructuredName.SUFFIX);
        final String displayName = primaryContentValues
                .getAsString(StructuredName.DISPLAY_NAME);

        if (!TextUtils.isEmpty(familyName) || !TextUtils.isEmpty(givenName)) {
            final String encodedFamily;
            final String encodedGiven;
            final String encodedMiddle;
            final String encodedPrefix;
            final String encodedSuffix;

            final boolean reallyUseQuotedPrintableToName =
                (mUsesQPToPrimaryProperties &&
                    !(VCardUtils.containsOnlyNonCrLfPrintableAscii(familyName) &&
                            VCardUtils.containsOnlyNonCrLfPrintableAscii(givenName) &&
                            VCardUtils.containsOnlyNonCrLfPrintableAscii(middleName) &&
                            VCardUtils.containsOnlyNonCrLfPrintableAscii(prefix) &&
                            VCardUtils.containsOnlyNonCrLfPrintableAscii(suffix)));

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

            // N property. This order is specified by vCard spec and does not depend on countries.
            builder.append(VCARD_PROPERTY_NAME);
            if (shouldAppendCharsetAttribute(Arrays.asList(
                    familyName, givenName, middleName, prefix, suffix))) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(mVCardAttributeCharset);
            }
            if (reallyUseQuotedPrintableToName) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(VCARD_ATTR_ENCODING_QP);
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

            final String fullname = VCardUtils.constructNameFromElements(
                    VCardConfig.getNameOrderType(mVCardType),
                    encodedFamily, encodedMiddle, encodedGiven, encodedPrefix, encodedSuffix);
            final boolean reallyUseQuotedPrintableToFullname =
                mUsesQPToPrimaryProperties &&
                !VCardUtils.containsOnlyNonCrLfPrintableAscii(fullname);

            final String encodedFullname =
                reallyUseQuotedPrintableToFullname ?
                        encodeQuotedPrintable(fullname) :
                            escapeCharacters(fullname);

            // FN property
            builder.append(VCARD_PROPERTY_FULL_NAME);
            if (shouldAppendCharsetAttribute(encodedFullname)) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(mVCardAttributeCharset);
            }
            if (reallyUseQuotedPrintableToFullname) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(VCARD_ATTR_ENCODING_QP);
            }
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(encodedFullname);
            builder.append(VCARD_COL_SEPARATOR);
        } else if (!TextUtils.isEmpty(displayName)) {
            final boolean reallyUseQuotedPrintableToDisplayName =
                (mUsesQPToPrimaryProperties &&
                        !VCardUtils.containsOnlyNonCrLfPrintableAscii(displayName));
            final String encodedDisplayName =
                    reallyUseQuotedPrintableToDisplayName ?
                            encodeQuotedPrintable(displayName) :
                                escapeCharacters(displayName);

            builder.append(VCARD_PROPERTY_NAME);
            if (shouldAppendCharsetAttribute(encodedDisplayName)) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(mVCardAttributeCharset);
            }
            if (reallyUseQuotedPrintableToDisplayName) {
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(VCARD_ATTR_ENCODING_QP);
            }
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(encodedDisplayName);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_COL_SEPARATOR);
        } else if (mIsDoCoMo) {
            appendVCardLine(builder, VCARD_PROPERTY_NAME, "");
        } else if (mIsV30) {
            appendVCardLine(builder, VCARD_PROPERTY_NAME, "");
            appendVCardLine(builder, VCARD_PROPERTY_FULL_NAME, "");
        }

        String phoneticFamilyName = primaryContentValues
                .getAsString(StructuredName.PHONETIC_FAMILY_NAME);
        String phoneticMiddleName = primaryContentValues
                .getAsString(StructuredName.PHONETIC_MIDDLE_NAME);
        String phoneticGivenName = primaryContentValues
                .getAsString(StructuredName.PHONETIC_GIVEN_NAME);
        if (!(TextUtils.isEmpty(phoneticFamilyName)
                && TextUtils.isEmpty(phoneticMiddleName) &&
                TextUtils.isEmpty(phoneticGivenName))) { // if not empty
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
                                phoneticFamilyName,
                                phoneticMiddleName,
                                phoneticGivenName);
                builder.append(VCARD_PROPERTY_SORT_STRING);

                // Do not need to care about QP, since vCard 3.0 does not allow it.
                final String encodedSortString = escapeCharacters(sortString);
                if (shouldAppendCharsetAttribute(encodedSortString)) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedSortString);
                builder.append(VCARD_COL_SEPARATOR);
            } else {
                // Note: There is no appropriate property for expressing
                //       phonetic name in vCard 2.1, while there is in
                //       vCard 3.0 (SORT-STRING).
                //       We chose to use DoCoMo's way since it is supported by
                //       a lot of Japanese mobile phones. This is "X-" property, so
                //       any parser hopefully would not get confused with this.
                builder.append(VCARD_PROPERTY_SOUND);
                builder.append(VCARD_ATTR_SEPARATOR);
                builder.append(Constants.ATTR_TYPE_X_IRMC_N);

                boolean reallyUseQuotedPrintable =
                    (mUsesQPToPrimaryProperties &&
                            !(VCardUtils.containsOnlyNonCrLfPrintableAscii(
                                    phoneticFamilyName) &&
                              VCardUtils.containsOnlyNonCrLfPrintableAscii(
                                    phoneticMiddleName) &&
                              VCardUtils.containsOnlyNonCrLfPrintableAscii(
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

                if (shouldAppendCharsetAttribute(Arrays.asList(
                        encodedPhoneticFamilyName, encodedPhoneticMiddleName,
                        encodedPhoneticGivenName))) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticFamilyName);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(encodedPhoneticGivenName);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(encodedPhoneticMiddleName);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_COL_SEPARATOR);
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
                final boolean reallyUseQuotedPrintable =
                    (mUsesQPToPrimaryProperties &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticGivenName));
                final String encodedPhoneticGivenName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticGivenName = encodeQuotedPrintable(phoneticGivenName);
                } else {
                    encodedPhoneticGivenName = escapeCharacters(phoneticGivenName);
                }
                builder.append(VCARD_PROPERTY_X_PHONETIC_FIRST_NAME);
                if (shouldAppendCharsetAttribute(encodedPhoneticGivenName)) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(VCARD_ATTR_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticGivenName);
                builder.append(VCARD_COL_SEPARATOR);
            }
            if (!TextUtils.isEmpty(phoneticMiddleName)) {
                final boolean reallyUseQuotedPrintable =
                    (mUsesQPToPrimaryProperties &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticMiddleName));
                final String encodedPhoneticMiddleName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticMiddleName = encodeQuotedPrintable(phoneticMiddleName);
                } else {
                    encodedPhoneticMiddleName = escapeCharacters(phoneticMiddleName);
                }
                builder.append(VCARD_PROPERTY_X_PHONETIC_MIDDLE_NAME);
                if (shouldAppendCharsetAttribute(encodedPhoneticMiddleName)) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(VCARD_ATTR_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticMiddleName);
                builder.append(VCARD_COL_SEPARATOR);
            }
            if (!TextUtils.isEmpty(phoneticFamilyName)) {
                final boolean reallyUseQuotedPrintable =
                    (mUsesQPToPrimaryProperties &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticFamilyName));
                final String encodedPhoneticFamilyName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticFamilyName = encodeQuotedPrintable(phoneticFamilyName);
                } else {
                    encodedPhoneticFamilyName = escapeCharacters(phoneticFamilyName);
                }
                builder.append(VCARD_PROPERTY_X_PHONETIC_LAST_NAME);
                if (shouldAppendCharsetAttribute(encodedPhoneticFamilyName)) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(VCARD_ATTR_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticFamilyName);
                builder.append(VCARD_COL_SEPARATOR);
            }
        }
    }

    private void appendNickNames(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
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
                final String nickname = contentValues.getAsString(Nickname.NAME);
                if (TextUtils.isEmpty(nickname)) {
                    continue;
                }

                final String encodedNickname;
                final boolean reallyUseQuotedPrintable =
                    (mUsesQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(nickname));
                if (reallyUseQuotedPrintable) {
                    encodedNickname = encodeQuotedPrintable(nickname);
                } else {
                    encodedNickname = escapeCharacters(nickname);
                }

                builder.append(propertyNickname);
                if (shouldAppendCharsetAttribute(propertyNickname)) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(mVCardAttributeCharset);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_ATTR_SEPARATOR);
                    builder.append(VCARD_ATTR_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedNickname);
                builder.append(VCARD_COL_SEPARATOR);
            }
        }
    }

    private void appendPhones(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Phone.CONTENT_ITEM_TYPE);
        boolean phoneLineExists = false;
        if (contentValuesList != null) {
            Set<String> phoneSet = new HashSet<String>();
            for (ContentValues contentValues : contentValuesList) {
                final Integer typeAsObject = contentValues.getAsInteger(Phone.TYPE);
                final String label = contentValues.getAsString(Phone.LABEL);
                String phoneNumber = contentValues.getAsString(Phone.NUMBER);
                if (phoneNumber != null) {
                    phoneNumber = phoneNumber.trim();
                }
                if (TextUtils.isEmpty(phoneNumber)) {
                    continue;
                }
                int type = (typeAsObject != null ? typeAsObject : Phone.TYPE_HOME);

                phoneLineExists = true;
                if (type == Phone.TYPE_PAGER) {
                    phoneLineExists = true;
                    if (!phoneSet.contains(phoneNumber)) {
                        phoneSet.add(phoneNumber);
                        appendVCardTelephoneLine(builder, type, label, phoneNumber);
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
                            SpannableStringBuilder tmpBuilder =
                                new SpannableStringBuilder(actualPhoneNumber);
                            PhoneNumberUtils.formatNumber(tmpBuilder, format);
                            final String formattedPhoneNumber = tmpBuilder.toString();
                            phoneSet.add(actualPhoneNumber);
                            appendVCardTelephoneLine(builder, type, label, formattedPhoneNumber);
                        }
                    }
                }
            }
        }

        if (!phoneLineExists && mIsDoCoMo) {
            appendVCardTelephoneLine(builder, Phone.TYPE_HOME, "", "");
        }
    }

    private List<String> splitIfSeveralPhoneNumbersExist(final String phoneNumber) {
        List<String> phoneList = new ArrayList<String>();

        StringBuilder builder = new StringBuilder();
        final int length = phoneNumber.length();
        for (int i = 0; i < length; i++) {
            final char ch = phoneNumber.charAt(i);
            if (Character.isDigit(ch)) {
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

    private void appendEmails(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Email.CONTENT_ITEM_TYPE);
        boolean emailAddressExists = false;
        if (contentValuesList != null) {
            Set<String> addressSet = new HashSet<String>();
            for (ContentValues contentValues : contentValuesList) {
                Integer typeAsObject = contentValues.getAsInteger(Email.TYPE);
                final int type = (typeAsObject != null ?
                        typeAsObject : Email.TYPE_OTHER);
                final String label = contentValues.getAsString(Email.LABEL);
                String emailAddress = contentValues.getAsString(Email.DATA);
                if (emailAddress != null) {
                    emailAddress = emailAddress.trim();
                }
                if (TextUtils.isEmpty(emailAddress)) {
                    continue;
                }
                emailAddressExists = true;
                if (!addressSet.contains(emailAddress)) {
                    addressSet.add(emailAddress);
                    appendVCardEmailLine(builder, type, label, emailAddress);
                }
            }
        }

        if (!emailAddressExists && mIsDoCoMo) {
            appendVCardEmailLine(builder, Email.TYPE_HOME, "", "");
        }
    }

    private void appendPostals(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
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
     * Tries to append just one line. If there's no appropriate address
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
            final List<ContentValues> contentValuesList, Integer preferedType) {
        for (ContentValues contentValues : contentValuesList) {
            final Integer type = contentValues.getAsInteger(StructuredPostal.TYPE);
            final String label = contentValues.getAsString(StructuredPostal.LABEL);
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
            final Integer type = contentValues.getAsInteger(StructuredPostal.TYPE);
            final String label = contentValues.getAsString(StructuredPostal.LABEL);
            if (type != null) {
                appendVCardPostalLine(builder, type, label, contentValues);
            }
        }
    }

    private void appendIms(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Im.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                Integer protocol = contentValues.getAsInteger(Im.PROTOCOL);
                String data = contentValues.getAsString(Im.DATA);
                if (data != null) {
                    data = data.trim();
                }
                if (TextUtils.isEmpty(data)) {
                    continue;
                }

                if (protocol != null && protocol == Im.PROTOCOL_GOOGLE_TALK) {
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
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Website.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                String website = contentValues.getAsString(Website.URL);
                if (website != null) {
                    website = website.trim();
                }
                if (!TextUtils.isEmpty(website)) {
                    appendVCardLine(builder, VCARD_PROPERTY_URL, website);
                }
            }
        }
    }

    private void appendBirthday(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Event.CONTENT_ITEM_TYPE);
        if (contentValuesList != null && contentValuesList.size() > 0) {
            Integer eventType = contentValuesList.get(0).getAsInteger(Event.TYPE);
            if (eventType == null || !eventType.equals(Event.TYPE_BIRTHDAY)) {
                return;
            }
            // Theoretically, there must be only one birthday for each vCard data and
            // we are afraid of some parse error occuring in some devices, so
            // we emit only one birthday entry for now.
            String birthday = contentValuesList.get(0).getAsString(Event.START_DATE);
            if (birthday != null) {
                birthday = birthday.trim();
            }
            if (!TextUtils.isEmpty(birthday)) {
                appendVCardLine(builder, VCARD_PROPERTY_BIRTHDAY, birthday);
            }
        }
    }

    private void appendOrganizations(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Organization.CONTENT_ITEM_TYPE);
        if (contentValuesList != null) {
            for (ContentValues contentValues : contentValuesList) {
                String company = contentValues
                        .getAsString(Organization.COMPANY);
                if (company != null) {
                    company = company.trim();
                }
                String title = contentValues
                        .getAsString(Organization.TITLE);
                if (title != null) {
                    title = title.trim();
                }

                if (!TextUtils.isEmpty(company)) {
                    appendVCardLine(builder, VCARD_PROPERTY_ORG, company,
                            !VCardUtils.containsOnlyPrintableAscii(company),
                            (mUsesQuotedPrintable &&
                                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(company)));
                }
                if (!TextUtils.isEmpty(title)) {
                    appendVCardLine(builder, VCARD_PROPERTY_TITLE, title,
                            !VCardUtils.containsOnlyPrintableAscii(title),
                            (mUsesQuotedPrintable &&
                                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(title)));
                }
            }
        }
    }

    private void appendPhotos(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
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
                final String photoString = VCardUtils.encodeBase64(data);
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
                        (mUsesQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(noteStr));
                appendVCardLine(builder, VCARD_PROPERTY_NOTE, noteStr,
                        shouldAppendCharsetInfo, reallyUseQuotedPrintable);
            } else {
                for (ContentValues contentValues : contentValuesList) {
                    final String noteStr = contentValues.getAsString(Note.NOTE);
                    if (!TextUtils.isEmpty(noteStr)) {
                        final boolean shouldAppendCharsetInfo =
                                !VCardUtils.containsOnlyPrintableAscii(noteStr);
                        final boolean reallyUseQuotedPrintable =
                                (mUsesQuotedPrintable &&
                                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(noteStr));
                        appendVCardLine(builder, VCARD_PROPERTY_NOTE, noteStr,
                                shouldAppendCharsetInfo, reallyUseQuotedPrintable);
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
    private String escapeCharacters(final String unescaped) {
        if (TextUtils.isEmpty(unescaped)) {
            return "";
        }

        final StringBuilder tmpBuilder = new StringBuilder();
        final int length = unescaped.length();
        for (int i = 0; i < length; i++) {
            char ch = unescaped.charAt(i);
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
                            continue;
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

    private void appendVCardPhotoLine(final StringBuilder builder,
            final String encodedData, final String photoType) {
        StringBuilder tmpBuilder = new StringBuilder();
        tmpBuilder.append(VCARD_PROPERTY_PHOTO);
        tmpBuilder.append(VCARD_ATTR_SEPARATOR);
        if (mIsV30) {
            tmpBuilder.append(VCARD_ATTR_ENCODING_BASE64_V30);
        } else {
            tmpBuilder.append(VCARD_ATTR_ENCODING_BASE64_V21);
        }
        tmpBuilder.append(VCARD_ATTR_SEPARATOR);
        appendTypeAttribute(tmpBuilder, photoType);
        tmpBuilder.append(VCARD_DATA_SEPARATOR);
        tmpBuilder.append(encodedData);

        final String tmpStr = tmpBuilder.toString();
        tmpBuilder = new StringBuilder();
        int lineCount = 0;
        int length = tmpStr.length();
        for (int i = 0; i < length; i++) {
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

    private void appendVCardPostalLine(final StringBuilder builder,
            final Integer typeAsObject, final String label,
            final ContentValues contentValues) {
        builder.append(VCARD_PROPERTY_ADR);
        builder.append(VCARD_ATTR_SEPARATOR);

        // Note: Not sure why we need to emit "empty" line even when actual data does not exist.
        // There may be some reason or may not be any. We keep safer side.
        // TODO: investigate this.
        boolean dataExists = false;
        String[] dataArray = VCardUtils.getVCardPostalElements(contentValues);
        boolean actuallyUseQuotedPrintable = false;
        boolean shouldAppendCharset = false;
        for (String data : dataArray) {
            if (!TextUtils.isEmpty(data)) {
                dataExists = true;
                if (!shouldAppendCharset && !VCardUtils.containsOnlyPrintableAscii(data)) {
                    shouldAppendCharset = true;
                }
                if (mUsesQuotedPrintable && !VCardUtils.containsOnlyNonCrLfPrintableAscii(data)) {
                    actuallyUseQuotedPrintable = true;
                    break;
                }
            }
        }

        int length = dataArray.length;
        for (int i = 0; i < length; i++) {
            String data = dataArray[i];
            if (!TextUtils.isEmpty(data)) {
                if (actuallyUseQuotedPrintable) {
                    dataArray[i] = encodeQuotedPrintable(data);
                } else {
                    dataArray[i] = escapeCharacters(data);
                }
            }
        }

        final int typeAsPrimitive;
        if (typeAsObject == null) {
            typeAsPrimitive = StructuredPostal.TYPE_OTHER;
        } else {
            typeAsPrimitive = typeAsObject;
        }

        String typeAsString = null;
        switch (typeAsPrimitive) {
            case StructuredPostal.TYPE_HOME: {
                typeAsString = Constants.ATTR_TYPE_HOME;
                break;
            }
            case StructuredPostal.TYPE_WORK: {
                typeAsString = Constants.ATTR_TYPE_WORK;
                break;
            }
            case StructuredPostal.TYPE_CUSTOM: {
                if (mUsesAndroidProperty && !TextUtils.isEmpty(label)
                        && VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                    // We're not sure whether the label is valid in the spec
                    // ("IANA-token" in the vCard 3.0 is unclear...)
                    // Just  for safety, we add "X-" at the beggining of each label.
                    // Also checks the label obeys with vCard 3.0 spec.
                    builder.append("X-");
                    builder.append(label);
                    builder.append(VCARD_DATA_SEPARATOR);
                }
                break;
            }
            case StructuredPostal.TYPE_OTHER: {
                break;
            }
            default: {
                Log.e(LOG_TAG, "Unknown StructuredPostal type: " + typeAsPrimitive);
                break;
            }
        }

        // Attribute(s).

        {
            boolean shouldAppendAttrSeparator = false;
            if (typeAsString != null) {
                appendTypeAttribute(builder, typeAsString);
                shouldAppendAttrSeparator = true;
            }

            if (dataExists) {
                if (shouldAppendCharset) {
                    // Strictly, vCard 3.0 does not allow exporters to emit charset information,
                    // but we will add it since the information should be useful for importers,
                    //
                    // Assume no parser does not emit error with this attribute in vCard 3.0.
                    if (shouldAppendAttrSeparator) {
                        builder.append(VCARD_ATTR_SEPARATOR);
                    }
                    builder.append(mVCardAttributeCharset);
                    shouldAppendAttrSeparator = true;
                }

                if (actuallyUseQuotedPrintable) {
                    if (shouldAppendAttrSeparator) {
                        builder.append(VCARD_ATTR_SEPARATOR);
                    }
                    builder.append(VCARD_ATTR_ENCODING_QP);
                    shouldAppendAttrSeparator = true;
                }
            }
        }

        // Property values.

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

    private void appendVCardEmailLine(final StringBuilder builder,
            final Integer typeAsObject, final String label, final String data) {
        builder.append(VCARD_PROPERTY_EMAIL);

        final int typeAsPrimitive;
        if (typeAsObject == null) {
            typeAsPrimitive = Email.TYPE_OTHER;
        } else {
            typeAsPrimitive = typeAsObject;
        }

        final String typeAsString;
        switch (typeAsPrimitive) {
            case Email.TYPE_CUSTOM: {
                // For backward compatibility.
                // Detail: Until Donut, there isn't TYPE_MOBILE for email while there is now.
                //         To support mobile type at that time, this custom label had been used.
                if (android.provider.Contacts.ContactMethodsColumns.MOBILE_EMAIL_TYPE_NAME
                        .equals(label)) {
                    typeAsString = Constants.ATTR_TYPE_CELL;
                } else if (mUsesAndroidProperty && !TextUtils.isEmpty(label)
                        && VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                    typeAsString = "X-" + label;
                } else {
                    typeAsString = DEFAULT_EMAIL_TYPE;
                }
                break;
            }
            case Email.TYPE_HOME: {
                typeAsString = Constants.ATTR_TYPE_HOME;
                break;
            }
            case Email.TYPE_WORK: {
                typeAsString = Constants.ATTR_TYPE_WORK;
                break;
            }
            case Email.TYPE_OTHER: {
                typeAsString = DEFAULT_EMAIL_TYPE;
                break;
            }
            case Email.TYPE_MOBILE: {
                typeAsString = Constants.ATTR_TYPE_CELL;
                break;
            }
            default: {
                Log.e(LOG_TAG, "Unknown Email type: " + typeAsPrimitive);
                typeAsString = DEFAULT_EMAIL_TYPE;
                break;
            }
        }

        builder.append(VCARD_ATTR_SEPARATOR);
        appendTypeAttribute(builder, typeAsString);
        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(data);
        builder.append(VCARD_COL_SEPARATOR);
    }

    private void appendVCardTelephoneLine(final StringBuilder builder,
            final Integer typeAsObject, final String label,
            String encodedData) {
        builder.append(VCARD_PROPERTY_TEL);
        builder.append(VCARD_ATTR_SEPARATOR);

        final int typeAsPrimitive;
        if (typeAsObject == null) {
            typeAsPrimitive = Phone.TYPE_OTHER;
        } else {
            typeAsPrimitive = typeAsObject;
        }

        switch (typeAsPrimitive) {
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
                // Also, refrain from using appendType() so that "TYPE=" is never be appended.
                builder.append(Constants.ATTR_TYPE_VOICE);
            } else {
                appendTypeAttribute(builder, Constants.ATTR_TYPE_PAGER);
            }
            break;
        case Phone.TYPE_OTHER:
            appendTypeAttribute(builder, Constants.ATTR_TYPE_VOICE);
            break;
        case Phone.TYPE_CUSTOM:
            if (mUsesAndroidProperty && !TextUtils.isEmpty(label)
                        && VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                appendTypeAttribute(builder, "X-" + label);
            } else {
                // Just ignore the custom type.
                appendTypeAttribute(builder, Constants.ATTR_TYPE_VOICE);
            }
            break;
        default:
            appendUncommonPhoneType(builder, typeAsPrimitive);
            break;
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(encodedData);
        builder.append(VCARD_COL_SEPARATOR);
    }

    /**
     * Appends phone type string which may not be available in some devices.
     */
    private void appendUncommonPhoneType(final StringBuilder builder, final Integer type) {
        if (mIsDoCoMo) {
            // The previous implementation for DoCoMo had been conservative
            // about miscellaneous types.
            builder.append(Constants.ATTR_TYPE_VOICE);
        } else {
            String phoneAttribute = VCardUtils.getPhoneAttributeString(type);
            if (phoneAttribute != null) {
                appendTypeAttribute(builder, phoneAttribute);
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
            final String field, final String rawData, final boolean needCharset,
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
            appendTypeAttribute(builder, type);
        }
    }

    private void appendTypeAttribute(final StringBuilder builder, final String type) {
        // Note: In vCard 3.0, Type strings also can be like this: "TYPE=HOME,PREF"
        if (mIsV30) {
            builder.append(Constants.ATTR_TYPE).append(VCARD_ATTR_EQUAL);
        }
        builder.append(type);
    }

    /**
     * Returns true when the property line should contain charset attribute
     * information. This method may return true even when vCard version is 3.0.
     *
     * Strictly, adding charset information is invalid in VCard 3.0.
     * However we'll add the info only when used charset is not UTF-8
     * in vCard 3.0 format, since parser side may be able to use the charset
     * via this field, though we may encounter another problem by adding it...
     *
     * e.g. Japanese mobile phones use Shift_Jis while RFC 2426
     * recommends UTF-8. By adding this field, parsers may be able
     * to know this text is NOT UTF-8 but Shift_Jis.
     */
    private boolean shouldAppendCharsetAttribute(final String propertyValue) {
        return (!VCardUtils.containsOnlyPrintableAscii(propertyValue) &&
                        (!mIsV30 || !mUsesUtf8));
    }

    private boolean shouldAppendCharsetAttribute(final List<String> propertyValueList) {
        boolean shouldAppendBasically = false;
        for (String propertyValue : propertyValueList) {
            if (!VCardUtils.containsOnlyPrintableAscii(propertyValue)) {
                shouldAppendBasically = true;
                break;
            }
        }
        return shouldAppendBasically && (!mIsV30 || !mUsesUtf8);
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

        final StringBuilder tmpBuilder = new StringBuilder();
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
            tmpBuilder.append(String.format("=%02X", strArray[index]));
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
                tmpBuilder.append("=\r\n");
                lineCount = 0;
            }
        }

        return tmpBuilder.toString();
    }
}
