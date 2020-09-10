/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.text.TextUtils;
import android.util.Slog;

/** A helper class that queries the Contacts database. */
class ContactsQueryHelper {

    private static final String TAG = "ContactsQueryHelper";

    private final Context mContext;
    private Uri mContactUri;
    private boolean mIsStarred;
    private String mPhoneNumber;
    private long mLastUpdatedTimestamp;

    ContactsQueryHelper(Context context) {
        mContext = context;
    }

    /**
     * Queries the Contacts database with the given contact URI and returns whether the query runs
     * successfully.
     */
    @WorkerThread
    boolean query(@NonNull String contactUri) {
        if (TextUtils.isEmpty(contactUri)) {
            return false;
        }
        Uri uri = Uri.parse(contactUri);
        if ("tel".equals(uri.getScheme())) {
            return queryWithPhoneNumber(uri.getSchemeSpecificPart());
        } else if ("mailto".equals(uri.getScheme())) {
            return queryWithEmail(uri.getSchemeSpecificPart());
        } else if (contactUri.startsWith(Contacts.CONTENT_LOOKUP_URI.toString())) {
            return queryWithUri(uri);
        }
        return false;
    }

    /** Queries the Contacts database and read the most recently updated contact. */
    @WorkerThread
    boolean querySince(long sinceTime) {
        final String[] projection = new String[] {
                Contacts._ID, Contacts.LOOKUP_KEY, Contacts.STARRED, Contacts.HAS_PHONE_NUMBER,
                Contacts.CONTACT_LAST_UPDATED_TIMESTAMP };
        String selection = Contacts.CONTACT_LAST_UPDATED_TIMESTAMP + " > ?";
        String[] selectionArgs = new String[] {Long.toString(sinceTime)};
        return queryContact(Contacts.CONTENT_URI, projection, selection, selectionArgs);
    }

    @Nullable
    Uri getContactUri() {
        return mContactUri;
    }

    boolean isStarred() {
        return mIsStarred;
    }

    @Nullable
    String getPhoneNumber() {
        return mPhoneNumber;
    }

    long getLastUpdatedTimestamp() {
        return mLastUpdatedTimestamp;
    }

    private boolean queryWithPhoneNumber(String phoneNumber) {
        Uri phoneUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        return queryWithUri(phoneUri);
    }

    private boolean queryWithEmail(String email) {
        Uri emailUri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(email));
        return queryWithUri(emailUri);
    }

    private boolean queryWithUri(@NonNull Uri uri) {
        final String[] projection = new String[] {
                Contacts._ID, Contacts.LOOKUP_KEY, Contacts.STARRED, Contacts.HAS_PHONE_NUMBER };
        return queryContact(uri, projection, /* selection= */ null, /* selectionArgs= */ null);
    }

    private boolean queryContact(@NonNull Uri uri, @NonNull String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs) {
        long contactId;
        String lookupKey = null;
        boolean hasPhoneNumber = false;
        boolean found = false;
        try (Cursor cursor = mContext.getContentResolver().query(
                uri, projection, selection, selectionArgs, /* sortOrder= */ null)) {
            if (cursor == null) {
                Slog.w(TAG, "Cursor is null when querying contact.");
                return false;
            }
            while (cursor.moveToNext()) {
                // Contact ID
                int idIndex = cursor.getColumnIndex(Contacts._ID);
                contactId = cursor.getLong(idIndex);

                // Lookup key
                int lookupKeyIndex = cursor.getColumnIndex(Contacts.LOOKUP_KEY);
                lookupKey = cursor.getString(lookupKeyIndex);

                mContactUri = Contacts.getLookupUri(contactId, lookupKey);

                // Starred
                int starredIndex = cursor.getColumnIndex(Contacts.STARRED);
                mIsStarred = cursor.getInt(starredIndex) != 0;

                // Has phone number
                int hasPhoneNumIndex = cursor.getColumnIndex(Contacts.HAS_PHONE_NUMBER);
                hasPhoneNumber = cursor.getInt(hasPhoneNumIndex) != 0;

                // Last updated timestamp
                int lastUpdatedTimestampIndex = cursor.getColumnIndex(
                        Contacts.CONTACT_LAST_UPDATED_TIMESTAMP);
                if (lastUpdatedTimestampIndex >= 0) {
                    mLastUpdatedTimestamp = cursor.getLong(lastUpdatedTimestampIndex);
                }

                found = true;
            }
        }
        if (found && lookupKey != null && hasPhoneNumber) {
            return queryPhoneNumber(lookupKey);
        }
        return found;
    }

    private boolean queryPhoneNumber(String lookupKey) {
        String[] projection = new String[] {
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER };
        String selection = Contacts.LOOKUP_KEY + " = ?";
        String[] selectionArgs = new String[] { lookupKey };
        try (Cursor cursor = mContext.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection,
                selectionArgs, /* sortOrder= */ null)) {
            if (cursor == null) {
                Slog.w(TAG, "Cursor is null when querying contact phone number.");
                return false;
            }
            while (cursor.moveToNext()) {
                // Phone number
                int phoneNumIdx = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER);
                if (phoneNumIdx >= 0) {
                    mPhoneNumber = cursor.getString(phoneNumIdx);
                }
            }
        }
        return true;
    }
}
