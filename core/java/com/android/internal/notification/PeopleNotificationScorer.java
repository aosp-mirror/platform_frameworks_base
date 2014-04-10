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

package com.android.internal.notification;

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

/**
 * This {@link NotificationScorer} attempts to validate people references.
 * Also elevates the priority of real people.
 */
public class PeopleNotificationScorer implements NotificationScorer {
    private static final String TAG = "PeopleNotificationScorer";
    private static final boolean DBG = false;

    private static final boolean ENABLE_PEOPLE_SCORER = true;
    private static final String SETTING_ENABLE_PEOPLE_SCORER = "people_scorer_enabled";
    private static final String[] LOOKUP_PROJECTION = { Contacts._ID };
    private static final int MAX_PEOPLE = 10;
    private static final int PEOPLE_CACHE_SIZE = 200;
    // see NotificationManagerService
    private static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10;

    protected boolean mEnabled;
    private Context mContext;

    // maps raw person handle to resolved person object
    private LruCache<String, LookupResult> mPeopleCache;

    private float findMaxContactScore(Bundle extras) {
        if (extras == null) {
            return 0f;
        }

        final String[] people = extras.getStringArray(Notification.EXTRA_PEOPLE);
        if (people == null || people.length == 0) {
            return 0f;
        }

        float rank = 0f;
        for (int personIdx = 0; personIdx < people.length && personIdx < MAX_PEOPLE; personIdx++) {
            final String handle = people[personIdx];
            if (TextUtils.isEmpty(handle)) continue;

            LookupResult lookupResult = mPeopleCache.get(handle);
            if (lookupResult == null || lookupResult.isExpired()) {
                final Uri uri = Uri.parse(handle);
                if ("tel".equals(uri.getScheme())) {
                    if (DBG) Slog.w(TAG, "checking telephone URI: " + handle);
                    lookupResult = lookupPhoneContact(handle, uri.getSchemeSpecificPart());
                } else if (handle.startsWith(Contacts.CONTENT_LOOKUP_URI.toString())) {
                    if (DBG) Slog.w(TAG, "checking lookup URI: " + handle);
                    lookupResult = resolveContactsUri(handle, uri);
                } else {
                    if (DBG) Slog.w(TAG, "unsupported URI " + handle);
                }
            } else {
                if (DBG) Slog.w(TAG, "using cached lookupResult: " + lookupResult.mId);
            }
            if (lookupResult != null) {
                rank = Math.max(rank, lookupResult.getRank());
            }
        }
        return rank;
    }

    private LookupResult lookupPhoneContact(final String handle, final String number) {
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
                if (DBG) Slog.w(TAG, "is valid: " + id);
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
        mPeopleCache.put(handle, lookupResult);
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
                if (DBG) Slog.w(TAG, "is valid: " + id);
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
        mPeopleCache.put(handle, lookupResult);
        return lookupResult;
    }

    private final static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    // TODO: rework this function before shipping
    private static int priorityBumpMap(int incomingScore) {
        //assumption is that scale runs from [-2*pm, 2*pm]
        int pm = NOTIFICATION_PRIORITY_MULTIPLIER;
        int theScore = incomingScore;
        // enforce input in range
        theScore = clamp(theScore, -2 * pm, 2 * pm);
        if (theScore != incomingScore) return incomingScore;
        // map -20 -> -20 and -10 -> 5 (when pm = 10)
        if (theScore <= -pm) {
            theScore += 1.5 * (theScore + 2 * pm);
        } else {
            // map 0 -> 10, 10 -> 15, 20 -> 20;
            theScore += 0.5 * (2 * pm - theScore);
        }
        if (DBG) Slog.v(TAG, "priorityBumpMap: score before: " + incomingScore
                + ", score after " + theScore + ".");
        return theScore;
    }

    @Override
    public void initialize(Context context) {
        if (DBG) Slog.v(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mContext = context;
        mPeopleCache = new LruCache<String, LookupResult>(PEOPLE_CACHE_SIZE);
        mEnabled = ENABLE_PEOPLE_SCORER && 1 == Settings.Global.getInt(
                mContext.getContentResolver(), SETTING_ENABLE_PEOPLE_SCORER, 0);
    }

    @Override
    public int getScore(Notification notification, int score) {
        if (notification == null || !mEnabled) {
            if (DBG) Slog.w(TAG, "empty notification? scorer disabled?");
            return score;
        }
        float contactScore = findMaxContactScore(notification.extras);
        if (contactScore > 0f) {
            if (DBG) Slog.v(TAG, "Notification references a real contact. Promoted!");
            score = priorityBumpMap(score);
        } else {
            if (DBG) Slog.v(TAG, "Notification lacks any valid contact reference. Not promoted!");
        }
        return score;
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

        public float getRank() {
            if (isInvalid()) {
                return 0f;
            } else {
                return 1f;  // TODO: finer grained score
            }
        }

        public LookupResult setId(int id) {
            mId = id;
            return this;
        }
    }
}

