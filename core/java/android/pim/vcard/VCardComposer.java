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
import java.nio.charset.UnsupportedCharsetException;
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
 * <pre class="prettyprint">VCardComposer composer = null;
 * try {
 *     composer = new VCardComposer(context);
 *     composer.addHandler(
 *             composer.new HandlerForOutputStream(outputStream));
 *     if (!composer.init()) {
 *         // Do something handling the situation.
 *         return;
 *     }
 *     while (!composer.isAfterLast()) {
 *         if (mCanceled) {
 *             // Assume a user may cancel this operation during the export.
 *             return;
 *         }
 *         if (!composer.createOneEntry()) {
 *             // Do something handling the error situation.
 *             return;
 *         }
 *     }
 * } finally {
 *     if (composer != null) {
 *         composer.terminate();
 *     }
 * } </pre>
 */
public class VCardComposer {
    private static final String LOG_TAG = "vcard.VCardComposer";

    // TODO: Should be configurable?
    public static final int DEFAULT_PHONE_TYPE = Phone.TYPE_HOME;
    public static final int DEFAULT_POSTAL_TYPE = StructuredPostal.TYPE_HOME;
    public static final int DEFAULT_EMAIL_TYPE = Email.TYPE_OTHER;

    public static final String FAILURE_REASON_FAILED_TO_GET_DATABASE_INFO =
        "Failed to get database information";

    public static final String FAILURE_REASON_NO_ENTRY =
        "There's no exportable in the database";

    public static final String FAILURE_REASON_NOT_INITIALIZED =
        "The vCard composer object is not correctly initialized";

    /** Should be visible only from developers... (no need to translate, hopefully) */
    public static final String FAILURE_REASON_UNSUPPORTED_URI =
        "The Uri vCard composer received is not supported by the composer.";

    public static final String NO_ERROR = "No error";

    public static final String VCARD_TYPE_STRING_DOCOMO = "docomo";

    // Property for call log entry
    private static final String VCARD_PROPERTY_X_TIMESTAMP = "X-IRMC-CALL-DATETIME";
    private static final String VCARD_PROPERTY_CALLTYPE_INCOMING = "INCOMING";
    private static final String VCARD_PROPERTY_CALLTYPE_OUTGOING = "OUTGOING";
    private static final String VCARD_PROPERTY_CALLTYPE_MISSED = "MISSED";

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

    /**
     * Special URI for testing.
     */
    public static final String VCARD_TEST_AUTHORITY = "com.android.unit_tests.vcard";
    public static final Uri VCARD_TEST_AUTHORITY_URI =
        Uri.parse("content://" + VCARD_TEST_AUTHORITY);
    public static final Uri CONTACTS_TEST_CONTENT_URI =
        Uri.withAppendedPath(VCARD_TEST_AUTHORITY_URI, "contacts");

    private static final Uri sDataRequestUri;
    private static final Map<Integer, String> sImMap;

    /**
     * See the comment in {@link VCardConfig#FLAG_REFRAIN_QP_TO_PRIMARY_PROPERTIES}.
     */
    private static final Set<String> sPrimaryPropertyNameSet;

    static {
        Uri.Builder builder = RawContacts.CONTENT_URI.buildUpon();
        builder.appendQueryParameter(Data.FOR_EXPORT_ONLY, "1");
        sDataRequestUri = builder.build();
        sImMap = new HashMap<Integer, String>();
        sImMap.put(Im.PROTOCOL_AIM, Constants.PROPERTY_X_AIM);
        sImMap.put(Im.PROTOCOL_MSN, Constants.PROPERTY_X_MSN);
        sImMap.put(Im.PROTOCOL_YAHOO, Constants.PROPERTY_X_YAHOO);
        sImMap.put(Im.PROTOCOL_ICQ, Constants.PROPERTY_X_ICQ);
        sImMap.put(Im.PROTOCOL_JABBER, Constants.PROPERTY_X_JABBER);
        sImMap.put(Im.PROTOCOL_SKYPE, Constants.PROPERTY_X_SKYPE_USERNAME);
        // Google talk is a special case.

        // TODO: incomplete. Implement properly
        sPrimaryPropertyNameSet = new HashSet<String>();
        sPrimaryPropertyNameSet.add(Constants.PROPERTY_N);
        sPrimaryPropertyNameSet.add(Constants.PROPERTY_FN);
        sPrimaryPropertyNameSet.add(Constants.PROPERTY_SOUND);
    }

    public static interface OneEntryHandler {
        public boolean onInit(Context context);
        public boolean onEntryCreated(String vcard);
        public void onTerminate();
    }

    /**
     * <p>
     * An useful example handler, which emits VCard String to outputstream one by one.
     * </p>
     * <p>
     * The input OutputStream object is closed() on {@link #onTerminate()}.
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
    private final boolean mAppendTypeParamName;
    private final boolean mRefrainsQPToPrimaryProperties;
    private final boolean mNeedsToConvertPhoneticString;

    private Cursor mCursor;
    private int mIdColumn;

    private final String mCharsetString;
    private final String mVCardCharsetParameter;
    private boolean mTerminateIsCalled;
    final private List<OneEntryHandler> mHandlerList;

    private String mErrorReason = NO_ERROR;

    private boolean mIsCallLogComposer;

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
        this(context, VCardConfig.VCARD_TYPE_DEFAULT, true);
    }

    public VCardComposer(Context context, int vcardType) {
        this(context, vcardType, true);
    }

    public VCardComposer(Context context, String vcardTypeStr, boolean careHandlerErrors) {
        this(context, VCardConfig.getVCardTypeFromString(vcardTypeStr), careHandlerErrors);
    }

    /**
     * Construct for supporting call log entry vCard composing.
     */
    public VCardComposer(final Context context, final int vcardType,
            final boolean careHandlerErrors) {
        mContext = context;
        mVCardType = vcardType;
        mCareHandlerErrors = careHandlerErrors;
        mContentResolver = context.getContentResolver();

        mIsV30 = VCardConfig.isV30(vcardType);
        mUsesQuotedPrintable = VCardConfig.usesQuotedPrintable(vcardType);
        mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
        mIsJapaneseMobilePhone = VCardConfig.needsToConvertPhoneticString(vcardType);
        mOnlyOneNoteFieldIsAvailable = VCardConfig.onlyOneNoteFieldIsAvailable(vcardType);
        mUsesAndroidProperty = VCardConfig.usesAndroidSpecificProperty(vcardType);
        mUsesDefactProperty = VCardConfig.usesDefactProperty(vcardType);
        mUsesUtf8 = VCardConfig.usesUtf8(vcardType);
        mUsesShiftJis = VCardConfig.usesShiftJis(vcardType);
        mRefrainsQPToPrimaryProperties = VCardConfig.refrainsQPToPrimaryProperties(vcardType);
        mAppendTypeParamName = VCardConfig.appendTypeParamName(vcardType);
        mNeedsToConvertPhoneticString = VCardConfig.needsToConvertPhoneticString(vcardType);
        mHandlerList = new ArrayList<OneEntryHandler>();

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
    }

    /**
     * Must be called before {@link #init()}.
     */
    public void addHandler(OneEntryHandler handler) {
        if (handler != null) {
            mHandlerList.add(handler);
        }
    }

    /**
     * @return Returns true when initialization is successful and all the other
     *          methods are available. Returns false otherwise.
     */
    public boolean init() {
        return init(null, null);
    }

    public boolean init(final String selection, final String[] selectionArgs) {
        return init(Contacts.CONTENT_URI, selection, selectionArgs, null);
    }

    /**
     * Note that this is unstable interface, may be deleted in the future.
     */
    public boolean init(final Uri contentUri, final String selection,
            final String[] selectionArgs, final String sortOrder) {
        if (contentUri == null) {
            return false;
        }
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

        final String[] projection;
        if (CallLog.Calls.CONTENT_URI.equals(contentUri)) {
            projection = sCallLogProjection;
            mIsCallLogComposer = true;
        } else if (Contacts.CONTENT_URI.equals(contentUri) ||
                CONTACTS_TEST_CONTENT_URI.equals(contentUri)) {
            projection = sContactsProjection;
        } else {
            mErrorReason = FAILURE_REASON_UNSUPPORTED_URI;
            return false;
        }
        mCursor = mContentResolver.query(
                contentUri, projection, selection, selectionArgs, sortOrder);

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
            Log.e(LOG_TAG, "OutOfMemoryError occured. Ignore the entry: " + name);
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
                for (NamedContentValues namedContentValues : entity.getSubValues()) {
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
        appendVCardLine(builder, Constants.PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (mIsV30) {
            appendVCardLine(builder, Constants.PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, Constants.PROPERTY_VERSION, Constants.VERSION_V21);
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
        appendPhotos(builder, contentValuesListMap);
        appendNotes(builder, contentValuesListMap);
        // TODO: GroupMembership, Relation, Event other than birthday.

        if (mIsDoCoMo) {
            appendVCardLine(builder, Constants.PROPERTY_X_CLASS, VCARD_DATA_PUBLIC);
            appendVCardLine(builder, Constants.PROPERTY_X_REDUCTION, "");
            appendVCardLine(builder, Constants.PROPERTY_X_NO, "");
            appendVCardLine(builder, Constants.PROPERTY_X_DCM_HMN_MODE, "");
        }

        appendVCardLine(builder, Constants.PROPERTY_END, VCARD_DATA_VCARD);

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
            appendVCardLine(builder, Constants.PROPERTY_N, "");
        } else if (mIsV30) {
            // vCard 3.0 requires "N" and "FN" properties.
            appendVCardLine(builder, Constants.PROPERTY_N, "");
            appendVCardLine(builder, Constants.PROPERTY_FN, "");
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

        final String familyName = primaryContentValues.getAsString(StructuredName.FAMILY_NAME);
        final String middleName = primaryContentValues.getAsString(StructuredName.MIDDLE_NAME);
        final String givenName = primaryContentValues.getAsString(StructuredName.GIVEN_NAME);
        final String prefix = primaryContentValues.getAsString(StructuredName.PREFIX);
        final String suffix = primaryContentValues.getAsString(StructuredName.SUFFIX);
        final String displayName = primaryContentValues.getAsString(StructuredName.DISPLAY_NAME);

        if (!TextUtils.isEmpty(familyName) || !TextUtils.isEmpty(givenName)) {
            final boolean shouldAppendCharsetParameterToName =
                !(mIsV30 && UTF_8.equalsIgnoreCase(mCharsetString)) &&
                shouldAppendCharsetParameters(Arrays.asList(
                        familyName, givenName, middleName, prefix, suffix));
            final boolean reallyUseQuotedPrintableToName =
                    (!mRefrainsQPToPrimaryProperties &&
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
            final boolean shouldAppendCharsetParameterToFN =
                    !(mIsV30 && UTF_8.equalsIgnoreCase(mCharsetString)) &&
                    shouldAppendCharsetParameter(formattedName);
            final boolean reallyUseQuotedPrintableToFN =
                    !mRefrainsQPToPrimaryProperties &&
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

            builder.append(Constants.PROPERTY_N);
            if (mIsDoCoMo) {
                if (shouldAppendCharsetParameterToName) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintableToName) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(VCARD_PARAM_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                // DoCoMo phones require that all the elements in the "family name" field.
                builder.append(formattedName);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
            } else {
                if (shouldAppendCharsetParameterToName) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintableToName) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(VCARD_PARAM_ENCODING_QP);
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
            }
            builder.append(VCARD_END_OF_LINE);

            // FN property
            builder.append(Constants.PROPERTY_FN);
            if (shouldAppendCharsetParameterToFN) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(mVCardCharsetParameter);
            }
            if (reallyUseQuotedPrintableToFN) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(VCARD_PARAM_ENCODING_QP);
            }
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(encodedFormattedname);
            builder.append(VCARD_END_OF_LINE);
        } else if (!TextUtils.isEmpty(displayName)) {
            final boolean reallyUseQuotedPrintableToDisplayName =
                (!mRefrainsQPToPrimaryProperties &&
                        !VCardUtils.containsOnlyNonCrLfPrintableAscii(displayName));
            final String encodedDisplayName =
                    reallyUseQuotedPrintableToDisplayName ?
                            encodeQuotedPrintable(displayName) :
                                escapeCharacters(displayName);

            builder.append(Constants.PROPERTY_N);
            if (shouldAppendCharsetParameter(displayName)) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(mVCardCharsetParameter);
            }
            if (reallyUseQuotedPrintableToDisplayName) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(VCARD_PARAM_ENCODING_QP);
            }
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(encodedDisplayName);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_ITEM_SEPARATOR);
            builder.append(VCARD_END_OF_LINE);
            builder.append(Constants.PROPERTY_FN);

            // Note: "CHARSET" param is not allowed in vCard 3.0, but we may add it
            //       when it would be useful for external importers, assuming no external
            //       importer allows this vioration.
            if (shouldAppendCharsetParameter(displayName)) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(mVCardCharsetParameter);
            }
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(encodedDisplayName);
            builder.append(VCARD_END_OF_LINE);
        } else if (mIsV30) {
            // vCard 3.0 specification requires these fields.
            appendVCardLine(builder, Constants.PROPERTY_N, "");
            appendVCardLine(builder, Constants.PROPERTY_FN, "");
        } else if (mIsDoCoMo) {
            appendVCardLine(builder, Constants.PROPERTY_N, "");
        }

        final String phoneticFamilyName;
        final String phoneticMiddleName;
        final String phoneticGivenName;
        {
            final String tmpPhoneticFamilyName =
                primaryContentValues.getAsString(StructuredName.PHONETIC_FAMILY_NAME);
            final String tmpPhoneticMiddleName =
                primaryContentValues.getAsString(StructuredName.PHONETIC_MIDDLE_NAME);
            final String tmpPhoneticGivenName =
                primaryContentValues.getAsString(StructuredName.PHONETIC_GIVEN_NAME);
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

        if (!(TextUtils.isEmpty(phoneticFamilyName)
                && TextUtils.isEmpty(phoneticMiddleName)
                && TextUtils.isEmpty(phoneticGivenName))) {
            // Try to emit the field(s) related to phonetic name.
            if (mIsV30) {
                final String sortString = VCardUtils
                        .constructNameFromElements(mVCardType,
                                phoneticFamilyName, phoneticMiddleName, phoneticGivenName);
                builder.append(Constants.PROPERTY_SORT_STRING);
                if (shouldAppendCharsetParameter(sortString)) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(escapeCharacters(sortString));
                builder.append(VCARD_END_OF_LINE);
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
                builder.append(Constants.PROPERTY_SOUND);
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(Constants.PARAM_TYPE_X_IRMC_N);

                boolean reallyUseQuotedPrintable =
                    (!mRefrainsQPToPrimaryProperties
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

                if (shouldAppendCharsetParameters(Arrays.asList(
                        encodedPhoneticFamilyName, encodedPhoneticMiddleName,
                        encodedPhoneticGivenName))) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                {
                    boolean first = true;
                    if (!TextUtils.isEmpty(encodedPhoneticFamilyName)) {
                        builder.append(encodedPhoneticFamilyName);
                        first = false;
                    }
                    if (!TextUtils.isEmpty(encodedPhoneticMiddleName)) {
                        if (first) {
                            first = false;
                        } else {
                            builder.append(' ');
                        }
                        builder.append(encodedPhoneticMiddleName);
                    }
                    if (!TextUtils.isEmpty(encodedPhoneticGivenName)) {
                        if (!first) {
                            builder.append(' ');
                        }
                        builder.append(encodedPhoneticGivenName);
                    }
                }
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_END_OF_LINE);
            }
        } else {  // If phonetic name fields are all empty
            if (mIsDoCoMo) {
                builder.append(Constants.PROPERTY_SOUND);
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(Constants.PARAM_TYPE_X_IRMC_N);
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_ITEM_SEPARATOR);
                builder.append(VCARD_END_OF_LINE);
            }
        }

        if (mUsesDefactProperty) {
            if (!TextUtils.isEmpty(phoneticGivenName)) {
                final boolean reallyUseQuotedPrintable =
                    (mUsesQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticGivenName));
                final String encodedPhoneticGivenName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticGivenName = encodeQuotedPrintable(phoneticGivenName);
                } else {
                    encodedPhoneticGivenName = escapeCharacters(phoneticGivenName);
                }
                builder.append(Constants.PROPERTY_X_PHONETIC_FIRST_NAME);
                if (shouldAppendCharsetParameter(phoneticGivenName)) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(VCARD_PARAM_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticGivenName);
                builder.append(VCARD_END_OF_LINE);
            }
            if (!TextUtils.isEmpty(phoneticMiddleName)) {
                final boolean reallyUseQuotedPrintable =
                    (mUsesQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticMiddleName));
                final String encodedPhoneticMiddleName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticMiddleName = encodeQuotedPrintable(phoneticMiddleName);
                } else {
                    encodedPhoneticMiddleName = escapeCharacters(phoneticMiddleName);
                }
                builder.append(Constants.PROPERTY_X_PHONETIC_MIDDLE_NAME);
                if (shouldAppendCharsetParameter(phoneticMiddleName)) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(VCARD_PARAM_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticMiddleName);
                builder.append(VCARD_END_OF_LINE);
            }
            if (!TextUtils.isEmpty(phoneticFamilyName)) {
                final boolean reallyUseQuotedPrintable =
                    (mUsesQuotedPrintable &&
                            !VCardUtils.containsOnlyNonCrLfPrintableAscii(phoneticFamilyName));
                final String encodedPhoneticFamilyName;
                if (reallyUseQuotedPrintable) {
                    encodedPhoneticFamilyName = encodeQuotedPrintable(phoneticFamilyName);
                } else {
                    encodedPhoneticFamilyName = escapeCharacters(phoneticFamilyName);
                }
                builder.append(Constants.PROPERTY_X_PHONETIC_LAST_NAME);
                if (shouldAppendCharsetParameter(phoneticFamilyName)) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(mVCardCharsetParameter);
                }
                if (reallyUseQuotedPrintable) {
                    builder.append(VCARD_PARAM_SEPARATOR);
                    builder.append(VCARD_PARAM_ENCODING_QP);
                }
                builder.append(VCARD_DATA_SEPARATOR);
                builder.append(encodedPhoneticFamilyName);
                builder.append(VCARD_END_OF_LINE);
            }
        }
    }

    private void appendNickNames(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Nickname.CONTENT_ITEM_TYPE);
        if (contentValuesList == null) {
            return;
        }

        final boolean useAndroidProperty;
        if (mIsV30) {
            useAndroidProperty = false;
        } else if (mUsesAndroidProperty) {
            useAndroidProperty = true;
        } else {
            // There's no way to add this field.
            return;
        }

        for (ContentValues contentValues : contentValuesList) {
            final String nickname = contentValues.getAsString(Nickname.NAME);
            if (TextUtils.isEmpty(nickname)) {
                continue;
            }
            if (useAndroidProperty) {
                appendAndroidSpecificProperty(builder, Nickname.CONTENT_ITEM_TYPE,
                        contentValues);
            } else {
                appendVCardLineWithCharsetAndQPDetection(builder,
                        Constants.PROPERTY_NICKNAME, nickname);
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
                        appendVCardTelephoneLine(builder, type, label, phoneNumber, isPrimary);
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
                            appendVCardTelephoneLine(builder, type, label,
                                    formattedPhoneNumber, isPrimary);
                        }
                    }
                }
            }
        }

        if (!phoneLineExists && mIsDoCoMo) {
            appendVCardTelephoneLine(builder, Phone.TYPE_HOME, "", "", false);
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
                    appendVCardEmailLine(builder, type, label, emailAddress, isPrimary);
                }
            }
        }

        if (!emailAddressExists && mIsDoCoMo) {
            appendVCardEmailLine(builder, Email.TYPE_HOME, "", "", false);
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
            builder.append(Constants.PROPERTY_ADR);
            builder.append(VCARD_PARAM_SEPARATOR);
            builder.append(Constants.PARAM_TYPE_HOME);
            builder.append(VCARD_DATA_SEPARATOR);
            builder.append(VCARD_END_OF_LINE);
        }
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
    private void appendPostalsForDoCoMo(final StringBuilder builder,
            final List<ContentValues> contentValuesList) {
        int currentPriority = Integer.MAX_VALUE;
        int currentType = Integer.MAX_VALUE;
        ContentValues currentContentValues = null;
        for (ContentValues contentValues : contentValuesList) {
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
        appendVCardPostalLine(builder, currentType, label, currentContentValues, false, true);
    }

    private void appendPostalsForGeneric(final StringBuilder builder,
            final List<ContentValues> contentValuesList) {
        for (ContentValues contentValues : contentValuesList) {
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
            appendVCardPostalLine(builder, type, label, contentValues, isPrimary, false);
        }
    }

    private void appendIms(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Im.CONTENT_ITEM_TYPE);
        if (contentValuesList == null) {
            return;
        }
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
                        typeAsString = Constants.PARAM_TYPE_HOME;
                        break;
                    }
                    case Im.TYPE_WORK: {
                        typeAsString = Constants.PARAM_TYPE_WORK;
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

            List<String> parameterList = new ArrayList<String>();
            if (!TextUtils.isEmpty(typeAsString)) {
                parameterList.add(typeAsString);
            }
            final Integer isPrimaryAsInteger = contentValues.getAsInteger(Im.IS_PRIMARY);
            final boolean isPrimary = (isPrimaryAsInteger != null ?
                    (isPrimaryAsInteger > 0) : false);
            if (isPrimary) {
                parameterList.add(Constants.PARAM_TYPE_PREF);
            }

            appendVCardLineWithCharsetAndQPDetection(
                    builder, propertyName, parameterList, data);
        }
    }

    private void appendWebsites(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Website.CONTENT_ITEM_TYPE);
        if (contentValuesList == null) {
            return;
        }
        for (ContentValues contentValues : contentValuesList) {
            String website = contentValues.getAsString(Website.URL);
            if (website != null) {
                website = website.trim();
            }

            // Note: vCard 3.0 does not allow any parameter addition toward "URL"
            //       property, while there's no document in vCard 2.1.
            //
            // TODO: Should we allow adding it when appropriate?
            //       (Actually, we drop some data. Using "group.X-URL-TYPE" or something
            //        may help)
            if (!TextUtils.isEmpty(website)) {
                appendVCardLine(builder, Constants.PROPERTY_URL, website);
            }
        }
    }

    /**
     * Theoretically, there must be only one birthday for each vCard entry.
     * Also, we are afraid of some importer's parse error during its import.
     * We emit only one birthday entry even when there are more than one.
     */
    private void appendBirthday(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList =
                contentValuesListMap.get(Event.CONTENT_ITEM_TYPE);
        if (contentValuesList == null) {
            return;
        }
        String primaryBirthday = null;
        String secondaryBirthday = null;
        for (ContentValues contentValues : contentValuesList) {
            if (contentValues == null) {
                continue;
            }
            final Integer eventType = contentValues.getAsInteger(Event.TYPE);
            if (eventType == null || !eventType.equals(Event.TYPE_BIRTHDAY)) {
                continue;
            }
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
        }

        final String birthday;
        if (primaryBirthday != null) {
            birthday = primaryBirthday.trim();
        } else if (secondaryBirthday != null){
            birthday = secondaryBirthday.trim();
        } else {
            return;
        }
        appendVCardLineWithCharsetAndQPDetection(builder, Constants.PROPERTY_BDAY, birthday);
    }

    private void appendOrganizations(final StringBuilder builder,
            final Map<String, List<ContentValues>> contentValuesListMap) {
        final List<ContentValues> contentValuesList = contentValuesListMap
                .get(Organization.CONTENT_ITEM_TYPE);
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
                appendVCardLine(builder, Constants.PROPERTY_ORG, orgline,
                        !VCardUtils.containsOnlyPrintableAscii(orgline),
                        (mUsesQuotedPrintable &&
                                !VCardUtils.containsOnlyNonCrLfPrintableAscii(orgline)));

                if (!TextUtils.isEmpty(title)) {
                    appendVCardLine(builder, Constants.PROPERTY_TITLE, title,
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
                    // Note: vCard 2.1 officially does not support PNG, but we may have it and
                    //       using X- word like "X-PNG" may not let importers know it is PNG.
                    //       So we use the String "PNG" as is...
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
                appendVCardLine(builder, Constants.PROPERTY_NOTE, noteStr,
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
                        appendVCardLine(builder, Constants.PROPERTY_NOTE, noteStr,
                                shouldAppendCharsetInfo, reallyUseQuotedPrintable);
                    }
                }
            }
        }
    }

    private void appendAndroidSpecificProperty(final StringBuilder builder,
            final String mimeType, ContentValues contentValues) {
        List<String> rawDataList = new ArrayList<String>();
        rawDataList.add(mimeType);
        final List<String> columnNameList;
        if (Nickname.CONTENT_ITEM_TYPE.equals(mimeType)) {

        } else {
            // If you add the other field, please check all the columns are able to be
            // converted to String.
            //
            // e.g. BLOB is not what we can handle here now.
            return;
        }

        for (int i = 1; i <= Constants.MAX_DATA_COLUMN; i++) {
            String value = contentValues.getAsString("data" + i);
            if (value == null) {
                value = "";
            }
            rawDataList.add(value);
        }

        appendVCardLineWithCharsetAndQPDetection(builder,
                Constants.PROPERTY_X_ANDROID_CUSTOM, rawDataList);
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

    private void appendVCardPhotoLine(final StringBuilder builder,
            final String encodedData, final String photoType) {
        StringBuilder tmpBuilder = new StringBuilder();
        tmpBuilder.append(Constants.PROPERTY_PHOTO);
        tmpBuilder.append(VCARD_PARAM_SEPARATOR);
        if (mIsV30) {
            tmpBuilder.append(VCARD_PARAM_ENCODING_BASE64_V30);
        } else {
            tmpBuilder.append(VCARD_PARAM_ENCODING_BASE64_V21);
        }
        tmpBuilder.append(VCARD_PARAM_SEPARATOR);
        appendTypeParameter(tmpBuilder, photoType);
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
                tmpBuilder.append(VCARD_END_OF_LINE);
                tmpBuilder.append(VCARD_WS);
                lineCount = 0;
            }
        }
        builder.append(tmpBuilder.toString());
        builder.append(VCARD_END_OF_LINE);
        builder.append(VCARD_END_OF_LINE);
    }

    private class PostalStruct {
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
        boolean reallyUseQuotedPrintable = false;
        boolean appendCharset = false;

        boolean dataArrayExists = false;
        String[] dataArray = VCardUtils.getVCardPostalElements(contentValues);
        for (String data : dataArray) {
            if (!TextUtils.isEmpty(data)) {
                dataArrayExists = true;
                if (!appendCharset && !VCardUtils.containsOnlyPrintableAscii(data)) {
                    appendCharset = true;
                }
                if (mUsesQuotedPrintable && !VCardUtils.containsOnlyNonCrLfPrintableAscii(data)) {
                    reallyUseQuotedPrintable = true;
                    break;
                }
            }
        }

        if (dataArrayExists) {
            StringBuffer addressBuffer = new StringBuffer();
            boolean first = true;
            for (String data : dataArray) {
                if (first) {
                    first = false;
                } else {
                    addressBuffer.append(VCARD_ITEM_SEPARATOR);
                }
                if (!TextUtils.isEmpty(data)) {
                    if (reallyUseQuotedPrintable) {
                        addressBuffer.append(encodeQuotedPrintable(data));
                    } else {
                        addressBuffer.append(escapeCharacters(data));
                    }
                }
            }
            return new PostalStruct(reallyUseQuotedPrintable, appendCharset,
                    addressBuffer.toString());
        }

        String formattedAddress =
            contentValues.getAsString(StructuredPostal.FORMATTED_ADDRESS);
        if (!TextUtils.isEmpty(formattedAddress)) {
            reallyUseQuotedPrintable =
                !VCardUtils.containsOnlyPrintableAscii(formattedAddress);
            appendCharset =
                !VCardUtils.containsOnlyNonCrLfPrintableAscii(formattedAddress);
            if (reallyUseQuotedPrintable) {
                formattedAddress = encodeQuotedPrintable(formattedAddress);
            } else {
                formattedAddress = escapeCharacters(formattedAddress);
            }
            // We use the second value ("Extended Address").
            //
            // adr-value    = 0*6(text-value ";") text-value
            //              ; PO Box, Extended Address, Street, Locality, Region, Postal
            //              ; Code, Country Name
            StringBuffer addressBuffer = new StringBuffer();
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(formattedAddress);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            addressBuffer.append(VCARD_ITEM_SEPARATOR);
            return new PostalStruct(
                    reallyUseQuotedPrintable, appendCharset, addressBuffer.toString());
        }
        return null;  // There's no data available.
    }

    private void appendVCardPostalLine(final StringBuilder builder,
            final int type, final String label, final ContentValues contentValues,
            final boolean isPrimary, final boolean emitLineEveryTime) {
        final boolean reallyUseQuotedPrintable;
        final boolean appendCharset;
        final String addressData;
        {
            PostalStruct postalStruct = tryConstructPostalStruct(contentValues);
            if (postalStruct == null) {
                if (emitLineEveryTime) {
                    reallyUseQuotedPrintable = false;
                    appendCharset = false;
                    addressData = "";
                } else {
                    return;
                }
            } else {
                reallyUseQuotedPrintable = postalStruct.reallyUseQuotedPrintable;
                appendCharset = postalStruct.appendCharset;
                addressData = postalStruct.addressData;
            }
        }

        List<String> parameterList = new ArrayList<String>();
        if (isPrimary) {
            parameterList.add(Constants.PARAM_TYPE_PREF);
        }
        switch (type) {
            case StructuredPostal.TYPE_HOME: {
                parameterList.add(Constants.PARAM_TYPE_HOME);
                break;
            }
            case StructuredPostal.TYPE_WORK: {
                parameterList.add(Constants.PARAM_TYPE_WORK);
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

        // Actual data construction starts from here.
        
        builder.append(Constants.PROPERTY_ADR);

        // Parameters
        {
            if (!parameterList.isEmpty()) {
                builder.append(VCARD_PARAM_SEPARATOR);
                appendTypeParameters(builder, parameterList);
            }

            if (appendCharset) {
                // Strictly, vCard 3.0 does not allow exporters to emit charset information,
                // but we will add it since the information should be useful for importers,
                //
                // Assume no parser does not emit error with this parameter in vCard 3.0.
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(mVCardCharsetParameter);
            }

            if (reallyUseQuotedPrintable) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(VCARD_PARAM_ENCODING_QP);
            }
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(addressData);
        builder.append(VCARD_END_OF_LINE);
    }

    private void appendVCardEmailLine(final StringBuilder builder,
            final int type, final String label,
            final String rawData, final boolean isPrimary) {
        final String typeAsString;
        switch (type) {
            case Email.TYPE_CUSTOM: {
                if (VCardUtils.isMobilePhoneLabel(label)) {
                    typeAsString = Constants.PARAM_TYPE_CELL;
                } else if (!TextUtils.isEmpty(label)
                        && VCardUtils.containsOnlyAlphaDigitHyphen(label)) {
                    typeAsString = "X-" + label;
                } else {
                    typeAsString = null;
                }
                break;
            }
            case Email.TYPE_HOME: {
                typeAsString = Constants.PARAM_TYPE_HOME;
                break;
            }
            case Email.TYPE_WORK: {
                typeAsString = Constants.PARAM_TYPE_WORK;
                break;
            }
            case Email.TYPE_OTHER: {
                typeAsString = null;
                break;
            }
            case Email.TYPE_MOBILE: {
                typeAsString = Constants.PARAM_TYPE_CELL;
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
            parameterList.add(Constants.PARAM_TYPE_PREF);
        }
        if (!TextUtils.isEmpty(typeAsString)) {
            parameterList.add(typeAsString);
        }

        appendVCardLineWithCharsetAndQPDetection(builder, Constants.PROPERTY_EMAIL,
                parameterList, rawData);
    }

    private void appendVCardTelephoneLine(final StringBuilder builder,
            final Integer typeAsInteger, final String label,
            final String encodedData, boolean isPrimary) {
        builder.append(Constants.PROPERTY_TEL);
        builder.append(VCARD_PARAM_SEPARATOR);

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
                        Arrays.asList(Constants.PARAM_TYPE_HOME));
                break;
            }
            case Phone.TYPE_WORK: {
                parameterList.addAll(
                        Arrays.asList(Constants.PARAM_TYPE_WORK));
                break;
            }
            case Phone.TYPE_FAX_HOME: {
                parameterList.addAll(
                        Arrays.asList(Constants.PARAM_TYPE_HOME, Constants.PARAM_TYPE_FAX));
                break;
            }
            case Phone.TYPE_FAX_WORK: {
                parameterList.addAll(
                        Arrays.asList(Constants.PARAM_TYPE_WORK, Constants.PARAM_TYPE_FAX));
                break;
            }
            case Phone.TYPE_MOBILE: {
                parameterList.add(Constants.PARAM_TYPE_CELL);
                break;
            }
            case Phone.TYPE_PAGER: {
                if (mIsDoCoMo) {
                    // Not sure about the reason, but previous implementation had
                    // used "VOICE" instead of "PAGER"
                    parameterList.add(Constants.PARAM_TYPE_VOICE);
                } else {
                    parameterList.add(Constants.PARAM_TYPE_PAGER);
                }
                break;
            }
            case Phone.TYPE_OTHER: {
                parameterList.add(Constants.PARAM_TYPE_VOICE);
                break;
            }
            case Phone.TYPE_CAR: {
                parameterList.add(Constants.PARAM_TYPE_CAR);
                break;
            }
            case Phone.TYPE_COMPANY_MAIN: {
                // There's no relevant field in vCard (at least 2.1).
                parameterList.add(Constants.PARAM_TYPE_WORK);
                isPrimary = true;
                break;
            }
            case Phone.TYPE_ISDN: {
                parameterList.add(Constants.PARAM_TYPE_ISDN);
                break;
            }
            case Phone.TYPE_MAIN: {
                isPrimary = true;
                break;
            }
            case Phone.TYPE_OTHER_FAX: {
                parameterList.add(Constants.PARAM_TYPE_FAX);
                break;
            }
            case Phone.TYPE_TELEX: {
                parameterList.add(Constants.PARAM_TYPE_TLX);
                break;
            }
            case Phone.TYPE_WORK_MOBILE: {
                parameterList.addAll(
                        Arrays.asList(Constants.PARAM_TYPE_WORK, Constants.PARAM_TYPE_CELL));
                break;
            }
            case Phone.TYPE_WORK_PAGER: {
                parameterList.add(Constants.PARAM_TYPE_WORK);
                // See above.
                if (mIsDoCoMo) {
                    parameterList.add(Constants.PARAM_TYPE_VOICE);
                } else {
                    parameterList.add(Constants.PARAM_TYPE_PAGER);
                }
                break;
            }
            case Phone.TYPE_MMS: {
                parameterList.add(Constants.PARAM_TYPE_MSG);
                break;
            }
            case Phone.TYPE_CUSTOM: {
                if (TextUtils.isEmpty(label)) {
                    // Just ignore the custom type.
                    parameterList.add(Constants.PARAM_TYPE_VOICE);
                } else if (VCardUtils.isMobilePhoneLabel(label)) {
                    parameterList.add(Constants.PARAM_TYPE_CELL);
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
            parameterList.add(Constants.PARAM_TYPE_PREF);
        }

        if (parameterList.isEmpty()) {
            appendUncommonPhoneType(builder, type);
        } else {
            appendTypeParameters(builder, parameterList);
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(encodedData);
        builder.append(VCARD_END_OF_LINE);
    }

    /**
     * Appends phone type string which may not be available in some devices.
     */
    private void appendUncommonPhoneType(final StringBuilder builder, final Integer type) {
        if (mIsDoCoMo) {
            // The previous implementation for DoCoMo had been conservative
            // about miscellaneous types.
            builder.append(Constants.PARAM_TYPE_VOICE);
        } else {
            String phoneType = VCardUtils.getPhoneTypeString(type);
            if (phoneType != null) {
                appendTypeParameter(builder, phoneType);
            } else {
                Log.e(LOG_TAG, "Unknown or unsupported (by vCard) Phone type: " + type);
            }
        }
    }

    // appendVCardLine() variants accepting one String.

    private void appendVCardLineWithCharsetAndQPDetection(final StringBuilder builder,
            final String propertyName, final String rawData) {
        appendVCardLineWithCharsetAndQPDetection(builder, propertyName, null, rawData);
    }

    private void appendVCardLineWithCharsetAndQPDetection(final StringBuilder builder,
            final String propertyName,
            final List<String> parameterList, final String rawData) {
        final boolean needCharset =
            (mUsesQuotedPrintable && !VCardUtils.containsOnlyPrintableAscii(rawData));
        final boolean reallyUseQuotedPrintable =
            !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawData);
        appendVCardLine(builder, propertyName, parameterList,
                rawData, needCharset, reallyUseQuotedPrintable);
    }

    private void appendVCardLine(final StringBuilder builder,
            final String propertyName, final String rawData) {
        appendVCardLine(builder, propertyName, rawData, false, false);
    }

    private void appendVCardLine(final StringBuilder builder,
            final String propertyName, final String rawData, final boolean needCharset,
            boolean needQuotedPrintable) {
        appendVCardLine(builder, propertyName, null, rawData, needCharset, needQuotedPrintable);
    }

    private void appendVCardLine(final StringBuilder builder,
            final String propertyName,
            final List<String> parameterList,
            final String rawData, final boolean needCharset,
            boolean needQuotedPrintable) {
        builder.append(propertyName);
        if (parameterList != null && parameterList.size() > 0) {
            builder.append(VCARD_PARAM_SEPARATOR);
            appendTypeParameters(builder, parameterList);
        }
        if (needCharset) {
            builder.append(VCARD_PARAM_SEPARATOR);
            builder.append(mVCardCharsetParameter);
        }

        final String encodedData;
        if (needQuotedPrintable) {
            builder.append(VCARD_PARAM_SEPARATOR);
            builder.append(VCARD_PARAM_ENCODING_QP);
            encodedData = encodeQuotedPrintable(rawData);
        } else {
            // TODO: one line may be too huge, which may be invalid in vCard spec, though
            //       several (even well-known) applications do not care this.
            encodedData = escapeCharacters(rawData);
        }

        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(encodedData);
        builder.append(VCARD_END_OF_LINE);
    }

    // appendVCardLine() variants accepting List<String>.

    private void appendVCardLineWithCharsetAndQPDetection(final StringBuilder builder,
            final String propertyName, final List<String> rawDataList) {
        appendVCardLineWithCharsetAndQPDetection(builder, propertyName, null, rawDataList);
    }

    private void appendVCardLineWithCharsetAndQPDetection(final StringBuilder builder,
            final String propertyName,
            final List<String> parameterList, final List<String> rawDataList) {
        boolean needCharset = false;
        boolean reallyUseQuotedPrintable = false;
        for (String rawData : rawDataList) {
            if (!needCharset && mUsesQuotedPrintable &&
                    !VCardUtils.containsOnlyPrintableAscii(rawData)) {
                needCharset = true;
            }
            if (!reallyUseQuotedPrintable &&
                    !VCardUtils.containsOnlyNonCrLfPrintableAscii(rawData)) {
                reallyUseQuotedPrintable = true;
            }
            if (needCharset && reallyUseQuotedPrintable) {
                break;
            }
        }

        appendVCardLine(builder, propertyName, parameterList,
                rawDataList, needCharset, reallyUseQuotedPrintable);
    }

    /*
    private void appendVCardLine(final StringBuilder builder,
            final String propertyName, final List<String> rawDataList) {
        appendVCardLine(builder, propertyName, rawDataList, false, false);
    }

    private void appendVCardLine(final StringBuilder builder,
            final String propertyName, final List<String> rawDataList,
            final boolean needCharset, boolean needQuotedPrintable) {
        appendVCardLine(builder, propertyName, null, rawDataList, needCharset, needQuotedPrintable);
    }*/

    private void appendVCardLine(final StringBuilder builder,
            final String propertyName,
            final List<String> parameterList,
            final List<String> rawDataList, final boolean needCharset,
            final boolean needQuotedPrintable) {
        builder.append(propertyName);
        if (parameterList != null && parameterList.size() > 0) {
            builder.append(VCARD_PARAM_SEPARATOR);
            appendTypeParameters(builder, parameterList);
        }
        if (needCharset) {
            builder.append(VCARD_PARAM_SEPARATOR);
            builder.append(mVCardCharsetParameter);
        }

        builder.append(VCARD_DATA_SEPARATOR);
        boolean first = true;
        for (String rawData : rawDataList) {
            final String encodedData;
            if (needQuotedPrintable) {
                builder.append(VCARD_PARAM_SEPARATOR);
                builder.append(VCARD_PARAM_ENCODING_QP);
                encodedData = encodeQuotedPrintable(rawData);
            } else {
                // TODO: one line may be too huge, which may be invalid in vCard 3.0
                //        (which says "When generating a content line, lines longer than
                //        75 characters SHOULD be folded"), though several
                //        (even well-known) applications do not care this.
                encodedData = escapeCharacters(rawData);
            }

            if (first) {
                first = false;
            } else {
                builder.append(VCARD_ITEM_SEPARATOR);
            }
            builder.append(encodedData);
        }
        builder.append(VCARD_END_OF_LINE);
    }

    /**
     * VCARD_PARAM_SEPARATOR must be appended before this method being called.
     */
    private void appendTypeParameters(final StringBuilder builder,
            final List<String> types) {
        // We may have to make this comma separated form like "TYPE=DOM,WORK" in the future,
        // which would be recommended way in vcard 3.0 though not valid in vCard 2.1.
        boolean first = true;
        for (String type : types) {
            if (first) {
                first = false;
            } else {
                builder.append(VCARD_PARAM_SEPARATOR);
            }
            appendTypeParameter(builder, type);
        }
    }

    /**
     * VCARD_PARAM_SEPARATOR must be appended before this method being called.
     */
    private void appendTypeParameter(final StringBuilder builder, final String type) {
        // Refrain from using appendType() so that "TYPE=" is not be appended when the
        // device is DoCoMo's (just for safety).
        //
        // Note: In vCard 3.0, Type strings also can be like this: "TYPE=HOME,PREF"
        if ((mIsV30 || mAppendTypeParamName) && !mIsDoCoMo) {
            builder.append(Constants.PARAM_TYPE).append(VCARD_PARAM_EQUAL);
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
    private boolean shouldAppendCharsetParameter(final String propertyValue) {
        return (!(mIsV30 && mUsesUtf8) && !VCardUtils.containsOnlyPrintableAscii(propertyValue));
    }

    private boolean shouldAppendCharsetParameters(final List<String> propertyValueList) {
        if (mIsV30 && mUsesUtf8) {
            return false;
        }
        for (String propertyValue : propertyValueList) {
            if (!VCardUtils.containsOnlyPrintableAscii(propertyValue)) {
                return true;
            }
        }
        return false;
    }

    private String encodeQuotedPrintable(String str) {
        if (TextUtils.isEmpty(str)) {
            return "";
        }
        {
            // Replace "\n" and "\r" with "\r\n".
            final StringBuilder tmpBuilder = new StringBuilder();
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

    //// The methods bellow are for call log history ////

    /**
     * This static function is to compose vCard for phone own number
     */
    public String composeVCardForPhoneOwnNumber(int phonetype, String phoneName,
            String phoneNumber, boolean vcardVer21) {
        final StringBuilder builder = new StringBuilder();
        appendVCardLine(builder, Constants.PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (!vcardVer21) {
            appendVCardLine(builder, Constants.PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, Constants.PROPERTY_VERSION, Constants.VERSION_V21);
        }

        boolean needCharset = false;
        if (!(VCardUtils.containsOnlyPrintableAscii(phoneName))) {
            needCharset = true;
        }
        appendVCardLine(builder, Constants.PROPERTY_FN, phoneName, needCharset, false);
        appendVCardLine(builder, Constants.PROPERTY_N, phoneName, needCharset, false);

        if (!TextUtils.isEmpty(phoneNumber)) {
            String label = Integer.toString(phonetype);
            appendVCardTelephoneLine(builder, phonetype, label, phoneNumber, false);
        }

        appendVCardLine(builder, Constants.PROPERTY_END, VCARD_DATA_VCARD);

        return builder.toString();
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
        builder.append(VCARD_PARAM_SEPARATOR);
        appendTypeParameter(builder, callLogTypeStr);
        builder.append(VCARD_DATA_SEPARATOR);
        builder.append(toRfc2455Format(dateAsLong));
        builder.append(VCARD_END_OF_LINE);
    }

    private String createOneCallLogEntryInternal() {
        final StringBuilder builder = new StringBuilder();
        appendVCardLine(builder, Constants.PROPERTY_BEGIN, VCARD_DATA_VCARD);
        if (mIsV30) {
            appendVCardLine(builder, Constants.PROPERTY_VERSION, Constants.VERSION_V30);
        } else {
            appendVCardLine(builder, Constants.PROPERTY_VERSION, Constants.VERSION_V21);
        }
        String name = mCursor.getString(CALLER_NAME_COLUMN_INDEX);
        if (TextUtils.isEmpty(name)) {
            name = mCursor.getString(NUMBER_COLUMN_INDEX);
        }
        final boolean needCharset = !(VCardUtils.containsOnlyPrintableAscii(name));
        appendVCardLine(builder, Constants.PROPERTY_FN, name, needCharset, false);
        appendVCardLine(builder, Constants.PROPERTY_N, name, needCharset, false);

        String number = mCursor.getString(NUMBER_COLUMN_INDEX);
        int type = mCursor.getInt(CALLER_NUMBERTYPE_COLUMN_INDEX);
        String label = mCursor.getString(CALLER_NUMBERLABEL_COLUMN_INDEX);
        if (TextUtils.isEmpty(label)) {
            label = Integer.toString(type);
        }
        appendVCardTelephoneLine(builder, type, label, number, false);
        tryAppendCallHistoryTimeStampField(builder);
        appendVCardLine(builder, Constants.PROPERTY_END, VCARD_DATA_VCARD);
        return builder.toString();
    }
}
