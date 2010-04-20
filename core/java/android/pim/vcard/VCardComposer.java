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
import android.pim.vcard.exception.VCardException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.RawContactsEntity;
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
import android.util.CharsetUtils;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
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
    private static final String LOG_TAG = "VCardComposer";

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

    private static final Map<Integer, String> sImMap;

    static {
        sImMap = new HashMap<Integer, String>();
        sImMap.put(Im.PROTOCOL_AIM, VCardConstants.PROPERTY_X_AIM);
        sImMap.put(Im.PROTOCOL_MSN, VCardConstants.PROPERTY_X_MSN);
        sImMap.put(Im.PROTOCOL_YAHOO, VCardConstants.PROPERTY_X_YAHOO);
        sImMap.put(Im.PROTOCOL_ICQ, VCardConstants.PROPERTY_X_ICQ);
        sImMap.put(Im.PROTOCOL_JABBER, VCardConstants.PROPERTY_X_JABBER);
        sImMap.put(Im.PROTOCOL_SKYPE, VCardConstants.PROPERTY_X_SKYPE_USERNAME);
        // Google talk is a special case.
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
                    mWriter.write(createOneEntryInternal("-1", null));
                } catch (VCardException e) {
                    Log.e(LOG_TAG, "VCardException has been thrown during on Init(): " +
                            e.getMessage());
                    return false;
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

    private final boolean mIsDoCoMo;
    private final boolean mUsesShiftJis;
    private Cursor mCursor;
    private int mIdColumn;

    private final String mCharsetString;
    private boolean mTerminateIsCalled;
    private final List<OneEntryHandler> mHandlerList;

    private String mErrorReason = NO_ERROR;

    private static final String[] sContactsProjection = new String[] {
        Contacts._ID,
    };

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

        mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
        mUsesShiftJis = VCardConfig.usesShiftJis(vcardType);
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
        } else if (mUsesShiftJis) {
            String charset;
            try {
                charset = CharsetUtils.charsetForVendor(SHIFT_JIS).name();
            } catch (UnsupportedCharsetException e) {
                Log.e(LOG_TAG, "Vendor-specific SHIFT_JIS was not found. Use SHIFT_JIS as is.");
                charset = SHIFT_JIS;
            }
            mCharsetString = charset;
        } else {
            mCharsetString = UTF_8;
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
        if (Contacts.CONTENT_URI.equals(contentUri) ||
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

        mIdColumn = mCursor.getColumnIndex(Contacts._ID);

        return true;
    }

    public boolean createOneEntry() {
        return createOneEntry(null);
    }

    /**
     * @param getEntityIteratorMethod For Dependency Injection.
     * @hide just for testing.
     */
    public boolean createOneEntry(Method getEntityIteratorMethod) {
        if (mCursor == null || mCursor.isAfterLast()) {
            mErrorReason = FAILURE_REASON_NOT_INITIALIZED;
            return false;
        }
        String vcard;
        try {
            if (mIdColumn >= 0) {
                vcard = createOneEntryInternal(mCursor.getString(mIdColumn),
                        getEntityIteratorMethod);
            } else {
                Log.e(LOG_TAG, "Incorrect mIdColumn: " + mIdColumn);
                return true;
            }
        } catch (VCardException e) {
            Log.e(LOG_TAG, "VCardException has been thrown: " + e.getMessage());
            return false;
        } catch (OutOfMemoryError error) {
            // Maybe some data (e.g. photo) is too big to have in memory. But it
            // should be rare.
            Log.e(LOG_TAG, "OutOfMemoryError occured. Ignore the entry.");
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

    private String createOneEntryInternal(final String contactId,
            Method getEntityIteratorMethod) throws VCardException {
        final Map<String, List<ContentValues>> contentValuesListMap =
                new HashMap<String, List<ContentValues>>();
        // The resolver may return the entity iterator with no data. It is possible.
        // e.g. If all the data in the contact of the given contact id are not exportable ones,
        //      they are hidden from the view of this method, though contact id itself exists.
        EntityIterator entityIterator = null;
        try {
            final Uri uri = RawContactsEntity.CONTENT_URI.buildUpon()
                    .appendQueryParameter(Data.FOR_EXPORT_ONLY, "1")
                    .build();
            final String selection = Data.CONTACT_ID + "=?";
            final String[] selectionArgs = new String[] {contactId};
            if (getEntityIteratorMethod != null) {
                // Please note that this branch is executed by some tests only
                try {
                    entityIterator = (EntityIterator)getEntityIteratorMethod.invoke(null,
                            mContentResolver, uri, selection, selectionArgs, null);
                } catch (IllegalArgumentException e) {
                    Log.e(LOG_TAG, "IllegalArgumentException has been thrown: " +
                            e.getMessage());
                } catch (IllegalAccessException e) {
                    Log.e(LOG_TAG, "IllegalAccessException has been thrown: " +
                            e.getMessage());
                } catch (InvocationTargetException e) {
                    Log.e(LOG_TAG, "InvocationTargetException has been thrown: ");
                    StackTraceElement[] stackTraceElements = e.getCause().getStackTrace();
                    for (StackTraceElement element : stackTraceElements) {
                        Log.e(LOG_TAG, "    at " + element.toString());
                    }
                    throw new VCardException("InvocationTargetException has been thrown: " +
                            e.getCause().getMessage());
                }
            } else {
                entityIterator = RawContacts.newEntityIterator(mContentResolver.query(
                        uri, null, selection, selectionArgs, null));
            }

            if (entityIterator == null) {
                Log.e(LOG_TAG, "EntityIterator is null");
                return "";
            }

            if (!entityIterator.hasNext()) {
                Log.w(LOG_TAG, "Data does not exist. contactId: " + contactId);
                return "";
            }

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
        } finally {
            if (entityIterator != null) {
                entityIterator.close();
            }
        }

        final VCardBuilder builder = new VCardBuilder(mVCardType);
        builder.appendNameProperties(contentValuesListMap.get(StructuredName.CONTENT_ITEM_TYPE))
                .appendNickNames(contentValuesListMap.get(Nickname.CONTENT_ITEM_TYPE))
                .appendPhones(contentValuesListMap.get(Phone.CONTENT_ITEM_TYPE))
                .appendEmails(contentValuesListMap.get(Email.CONTENT_ITEM_TYPE))
                .appendPostals(contentValuesListMap.get(StructuredPostal.CONTENT_ITEM_TYPE))
                .appendOrganizations(contentValuesListMap.get(Organization.CONTENT_ITEM_TYPE))
                .appendWebsites(contentValuesListMap.get(Website.CONTENT_ITEM_TYPE));
        if ((mVCardType & VCardConfig.FLAG_REFRAIN_IMAGE_EXPORT) == 0) {
            builder.appendPhotos(contentValuesListMap.get(Photo.CONTENT_ITEM_TYPE));
        }
        builder.appendNotes(contentValuesListMap.get(Note.CONTENT_ITEM_TYPE))
                .appendEvents(contentValuesListMap.get(Event.CONTENT_ITEM_TYPE))
                .appendIms(contentValuesListMap.get(Im.CONTENT_ITEM_TYPE))
                .appendRelation(contentValuesListMap.get(Relation.CONTENT_ITEM_TYPE));
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
                Log.e(LOG_TAG, "SQLiteException on Cursor#close(): " + e.getMessage());
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
}
