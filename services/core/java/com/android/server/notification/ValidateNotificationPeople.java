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

package com.android.server.notification;

import android.app.Notification;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Slog;

import com.android.server.notification.NotificationManagerService.NotificationRecord;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * This {@link NotificationSignalExtractor} attempts to validate
 * people references. Also elevates the priority of real people.
 */
public class ValidateNotificationPeople implements NotificationSignalExtractor {
    private static final String TAG = "ValidateNotificationPeople";
    private static final boolean INFO = true;
    private static final boolean DEBUG = false;

    private static final boolean ENABLE_PEOPLE_VALIDATOR = true;
    private static final String SETTING_ENABLE_PEOPLE_VALIDATOR =
            "validate_notification_people_enabled";
    private static final String[] LOOKUP_PROJECTION = { Contacts._ID };
    private static final int MAX_PEOPLE = 10;
    private static final int PEOPLE_CACHE_SIZE = 200;

    private static final float NONE = 0f;
    private static final float VALID_CONTACT = 0.5f;
    // TODO private static final float STARRED_CONTACT = 1f;

    protected boolean mEnabled;
    private Context mContext;

    // maps raw person handle to resolved person object
    private LruCache<String, LookupResult> mPeopleCache;

    private RankingFuture validatePeople(NotificationRecord record) {
        float affinity = NONE;
        Bundle extras = record.getNotification().extras;
        if (extras == null) {
            return null;
        }

        final String[] people = getExtraPeople(extras);
        if (people == null || people.length == 0) {
            return null;
        }

        if (INFO) Slog.i(TAG, "Validating: " + record.sbn.getKey());
        final LinkedList<String> pendingLookups = new LinkedList<String>();
        for (int personIdx = 0; personIdx < people.length && personIdx < MAX_PEOPLE; personIdx++) {
            final String handle = people[personIdx];
            if (TextUtils.isEmpty(handle)) continue;

            synchronized (mPeopleCache) {
                LookupResult lookupResult = mPeopleCache.get(handle);
                if (lookupResult == null || lookupResult.isExpired()) {
                    pendingLookups.add(handle);
                } else {
                    if (DEBUG) Slog.d(TAG, "using cached lookupResult: " + lookupResult.mId);
                }
                if (lookupResult != null) {
                    affinity = Math.max(affinity, lookupResult.getAffinity());
                }
            }
        }

        // record the best available data, so far:
        record.setContactAffinity(affinity);

        if (pendingLookups.isEmpty()) {
            if (INFO) Slog.i(TAG, "final affinity: " + affinity);
            return null;
        }

        if (DEBUG) Slog.d(TAG, "Pending: future work scheduled for: " + record.sbn.getKey());
        return new RankingFuture(record) {
            @Override
            public void work() {
                if (INFO) Slog.i(TAG, "Executing: validation for: " + mRecord.sbn.getKey());
                float affinity = NONE;
                LookupResult lookupResult = null;
                for (final String handle: pendingLookups) {
                    final Uri uri = Uri.parse(handle);
                    if ("tel".equals(uri.getScheme())) {
                        if (DEBUG) Slog.d(TAG, "checking telephone URI: " + handle);
                        lookupResult = resolvePhoneContact(handle, uri.getSchemeSpecificPart());
                    } else if (handle.startsWith(Contacts.CONTENT_LOOKUP_URI.toString())) {
                        if (DEBUG) Slog.d(TAG, "checking lookup URI: " + handle);
                        lookupResult = resolveContactsUri(handle, uri);
                    } else {
                        Slog.w(TAG, "unsupported URI " + handle);
                    }
                }
                if (lookupResult != null) {
                    affinity = Math.max(affinity, lookupResult.getAffinity());
                }

                float affinityBound = mRecord.getContactAffinity();
                affinity = Math.max(affinity, affinityBound);
                mRecord.setContactAffinity(affinity);
                if (INFO) Slog.i(TAG, "final affinity: " + affinity);
            }
        };
    }

    private String[] getExtraPeople(Bundle extras) {
        String[] people = extras.getStringArray(Notification.EXTRA_PEOPLE);
        if (people != null) {
            return people;
        }

        ArrayList<String> stringArray = extras.getStringArrayList(Notification.EXTRA_PEOPLE);
        if (stringArray != null) {
            return (String[]) stringArray.toArray();
        }

        String string = extras.getString(Notification.EXTRA_PEOPLE);
        if (string != null) {
            people = new String[1];
            people[0] = string;
            return people;
        }
        char[] charArray = extras.getCharArray(Notification.EXTRA_PEOPLE);
        if (charArray != null) {
            people = new String[1];
            people[0] = new String(charArray);
            return people;
        }

        CharSequence charSeq = extras.getCharSequence(Notification.EXTRA_PEOPLE);
        if (charSeq != null) {
            people = new String[1];
            people[0] = charSeq.toString();
            return people;
        }

        CharSequence[] charSeqArray = extras.getCharSequenceArray(Notification.EXTRA_PEOPLE);
        if (charSeqArray != null) {
            final int N = charSeqArray.length;
            people = new String[N];
            for (int i = 0; i < N; i++) {
                people[i] = charSeqArray[i].toString();
            }
            return people;
        }

        ArrayList<CharSequence> charSeqList =
                extras.getCharSequenceArrayList(Notification.EXTRA_PEOPLE);
        if (charSeqList != null) {
            final int N = charSeqList.size();
            people = new String[N];
            for (int i = 0; i < N; i++) {
                people[i] = charSeqList.get(i).toString();
            }
            return people;
        }
        return null;
    }

    private LookupResult resolvePhoneContact(final String handle, final String number) {
        LookupResult lookupResult = null;
        Cursor c = null;
        try {
            Uri numberUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number));
            c = mContext.getContentResolver().query(numberUri, LOOKUP_PROJECTION, null, null, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                final int idIdx = c.getColumnIndex(Contacts._ID);
                final int id = c.getInt(idIdx);
                if (DEBUG) Slog.d(TAG, "is valid: " + id);
                lookupResult = new LookupResult(id);
            }
        } catch(Throwable t) {
            Slog.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (lookupResult == null) {
            lookupResult = new LookupResult(LookupResult.INVALID_ID);
        }
        synchronized (mPeopleCache) {
            mPeopleCache.put(handle, lookupResult);
        }
        return lookupResult;
    }

    private LookupResult resolveContactsUri(String handle, final Uri personUri) {
        LookupResult lookupResult = null;
        Cursor c = null;
        try {
            c = mContext.getContentResolver().query(personUri, LOOKUP_PROJECTION, null, null, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                final int idIdx = c.getColumnIndex(Contacts._ID);
                final int id = c.getInt(idIdx);
                if (DEBUG) Slog.d(TAG, "is valid: " + id);
                lookupResult = new LookupResult(id);
            }
        } catch(Throwable t) {
            Slog.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (lookupResult == null) {
            lookupResult = new LookupResult(LookupResult.INVALID_ID);
        }
        synchronized (mPeopleCache) {
            mPeopleCache.put(handle, lookupResult);
        }
        return lookupResult;
    }

    public void initialize(Context context) {
        if (DEBUG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mContext = context;
        mPeopleCache = new LruCache<String, LookupResult>(PEOPLE_CACHE_SIZE);
        mEnabled = ENABLE_PEOPLE_VALIDATOR && 1 == Settings.Global.getInt(
                mContext.getContentResolver(), SETTING_ENABLE_PEOPLE_VALIDATOR, 1);
    }

    public RankingFuture process(NotificationManagerService.NotificationRecord record) {
        if (!mEnabled) {
            if (INFO) Slog.i(TAG, "disabled");
            return null;
        }
        if (record == null || record.getNotification() == null) {
            if (INFO) Slog.i(TAG, "skipping empty notification");
            return null;
        }
        return validatePeople(record);
    }

    private static class LookupResult {
        private static final long CONTACT_REFRESH_MILLIS = 60 * 60 * 1000;  // 1hr
        public static final int INVALID_ID = -1;

        private final long mExpireMillis;
        private int mId;

        public LookupResult(int id) {
            mId = id;
            mExpireMillis = System.currentTimeMillis() + CONTACT_REFRESH_MILLIS;
        }

        public boolean isExpired() {
            return mExpireMillis < System.currentTimeMillis();
        }

        public boolean isInvalid() {
            return mId == INVALID_ID || isExpired();
        }

        public float getAffinity() {
            if (isInvalid()) {
                return NONE;
            } else {
                return VALID_CONTACT;  // TODO: finer grained result: stars
            }
        }

        public LookupResult setId(int id) {
            mId = id;
            return this;
        }
    }
}

