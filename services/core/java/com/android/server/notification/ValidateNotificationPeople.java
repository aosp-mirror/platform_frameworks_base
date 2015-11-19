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
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LruCache;
import android.util.Slog;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import android.os.SystemClock;
import com.android.internal.logging.MetricsLogger;

/**
 * This {@link NotificationSignalExtractor} attempts to validate
 * people references. Also elevates the priority of real people.
 *
 * {@hide}
 */
public class ValidateNotificationPeople implements NotificationSignalExtractor {
    // Using a shorter log tag since setprop has a limit of 32chars on variable name.
    private static final String TAG = "ValidateNoPeople";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);;
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final boolean ENABLE_PEOPLE_VALIDATOR = true;
    private static final String SETTING_ENABLE_PEOPLE_VALIDATOR =
            "validate_notification_people_enabled";
    private static final String[] LOOKUP_PROJECTION = { Contacts._ID, Contacts.STARRED };
    private static final int MAX_PEOPLE = 10;
    private static final int PEOPLE_CACHE_SIZE = 200;

    /** Indicates that the notification does not reference any valid contacts. */
    static final float NONE = 0f;

    /**
     * Affinity will be equal to or greater than this value on notifications
     * that reference a valid contact.
     */
    static final float VALID_CONTACT = 0.5f;

    /**
     * Affinity will be equal to or greater than this value on notifications
     * that reference a starred contact.
     */
    static final float STARRED_CONTACT = 1f;

    protected boolean mEnabled;
    private Context mBaseContext;

    // maps raw person handle to resolved person object
    private LruCache<String, LookupResult> mPeopleCache;
    private Map<Integer, Context> mUserToContextMap;
    private Handler mHandler;
    private ContentObserver mObserver;
    private int mEvictionCount;
    private NotificationUsageStats mUsageStats;

    public void initialize(Context context, NotificationUsageStats usageStats) {
        if (DEBUG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mUserToContextMap = new ArrayMap<>();
        mBaseContext = context;
        mUsageStats = usageStats;
        mPeopleCache = new LruCache<String, LookupResult>(PEOPLE_CACHE_SIZE);
        mEnabled = ENABLE_PEOPLE_VALIDATOR && 1 == Settings.Global.getInt(
                mBaseContext.getContentResolver(), SETTING_ENABLE_PEOPLE_VALIDATOR, 1);
        if (mEnabled) {
            mHandler = new Handler();
            mObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri, int userId) {
                    super.onChange(selfChange, uri, userId);
                    if (DEBUG || mEvictionCount % 100 == 0) {
                        if (VERBOSE) Slog.i(TAG, "mEvictionCount: " + mEvictionCount);
                    }
                    mPeopleCache.evictAll();
                    mEvictionCount++;
                }
            };
            mBaseContext.getContentResolver().registerContentObserver(Contacts.CONTENT_URI, true,
                    mObserver, UserHandle.USER_ALL);
        }
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (!mEnabled) {
            if (VERBOSE) Slog.i(TAG, "disabled");
            return null;
        }
        if (record == null || record.getNotification() == null) {
            if (VERBOSE) Slog.i(TAG, "skipping empty notification");
            return null;
        }
        if (record.getUserId() == UserHandle.USER_ALL) {
            if (VERBOSE) Slog.i(TAG, "skipping global notification");
            return null;
        }
        Context context = getContextAsUser(record.getUser());
        if (context == null) {
            if (VERBOSE) Slog.i(TAG, "skipping notification that lacks a context");
            return null;
        }
        return validatePeople(context, record);
    }

    @Override
    public void setConfig(RankingConfig config) {
        // ignore: config has no relevant information yet.
    }

    /**
     * @param extras extras of the notification with EXTRA_PEOPLE populated
     * @param timeoutMs timeout in milliseconds to wait for contacts response
     * @param timeoutAffinity affinity to return when the timeout specified via
     *                        <code>timeoutMs</code> is hit
     */
    public float getContactAffinity(UserHandle userHandle, Bundle extras, int timeoutMs,
            float timeoutAffinity) {
        if (DEBUG) Slog.d(TAG, "checking affinity for " + userHandle);
        if (extras == null) return NONE;
        final String key = Long.toString(System.nanoTime());
        final float[] affinityOut = new float[1];
        Context context = getContextAsUser(userHandle);
        if (context == null) {
            return NONE;
        }
        final PeopleRankingReconsideration prr = validatePeople(context, key, extras, affinityOut);
        float affinity = affinityOut[0];

        if (prr != null) {
            // Perform the heavy work on a background thread so we can abort when we hit the
            // timeout.
            final Semaphore s = new Semaphore(0);
            AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    prr.work();
                    s.release();
                }
            });

            try {
                if (!s.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Slog.w(TAG, "Timeout while waiting for affinity: " + key + ". "
                            + "Returning timeoutAffinity=" + timeoutAffinity);
                    return timeoutAffinity;
                }
            } catch (InterruptedException e) {
                Slog.w(TAG, "InterruptedException while waiting for affinity: " + key + ". "
                        + "Returning affinity=" + affinity, e);
                return affinity;
            }

            affinity = Math.max(prr.getContactAffinity(), affinity);
        }
        return affinity;
    }

    private Context getContextAsUser(UserHandle userHandle) {
        Context context = mUserToContextMap.get(userHandle.getIdentifier());
        if (context == null) {
            try {
                context = mBaseContext.createPackageContextAsUser("android", 0, userHandle);
                mUserToContextMap.put(userHandle.getIdentifier(), context);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "failed to create package context for lookups", e);
            }
        }
        return context;
    }

    private RankingReconsideration validatePeople(Context context,
            final NotificationRecord record) {
        final String key = record.getKey();
        final Bundle extras = record.getNotification().extras;
        final float[] affinityOut = new float[1];
        final PeopleRankingReconsideration rr = validatePeople(context, key, extras, affinityOut);
        final float affinity = affinityOut[0];
        record.setContactAffinity(affinity);
        if (rr == null) {
            mUsageStats.registerPeopleAffinity(record, affinity > NONE, affinity == STARRED_CONTACT,
                    true /* cached */);
        } else {
            rr.setRecord(record);
        }
        return rr;
    }

    private PeopleRankingReconsideration validatePeople(Context context, String key, Bundle extras,
            float[] affinityOut) {
        long start = SystemClock.elapsedRealtime();
        float affinity = NONE;
        if (extras == null) {
            return null;
        }

        final String[] people = getExtraPeople(extras);
        if (people == null || people.length == 0) {
            return null;
        }

        if (VERBOSE) Slog.i(TAG, "Validating: " + key + " for " + context.getUserId());
        final LinkedList<String> pendingLookups = new LinkedList<String>();
        for (int personIdx = 0; personIdx < people.length && personIdx < MAX_PEOPLE; personIdx++) {
            final String handle = people[personIdx];
            if (TextUtils.isEmpty(handle)) continue;

            synchronized (mPeopleCache) {
                final String cacheKey = getCacheKey(context.getUserId(), handle);
                LookupResult lookupResult = mPeopleCache.get(cacheKey);
                if (lookupResult == null || lookupResult.isExpired()) {
                    pendingLookups.add(handle);
                } else {
                    if (DEBUG) Slog.d(TAG, "using cached lookupResult");
                }
                if (lookupResult != null) {
                    affinity = Math.max(affinity, lookupResult.getAffinity());
                }
            }
        }

        // record the best available data, so far:
        affinityOut[0] = affinity;

        MetricsLogger.histogram(mBaseContext, "validate_people_cache_latency",
                (int) (SystemClock.elapsedRealtime() - start));

        if (pendingLookups.isEmpty()) {
            if (VERBOSE) Slog.i(TAG, "final affinity: " + affinity);
            return null;
        }

        if (DEBUG) Slog.d(TAG, "Pending: future work scheduled for: " + key);
        return new PeopleRankingReconsideration(context, key, pendingLookups);
    }

    private String getCacheKey(int userId, String handle) {
        return Integer.toString(userId) + ":" + handle;
    }

    // VisibleForTesting
    public static String[] getExtraPeople(Bundle extras) {
        Object people = extras.get(Notification.EXTRA_PEOPLE);
        if (people instanceof String[]) {
            return (String[]) people;
        }

        if (people instanceof ArrayList) {
            ArrayList arrayList = (ArrayList) people;

            if (arrayList.isEmpty()) {
                return null;
            }

            if (arrayList.get(0) instanceof String) {
                ArrayList<String> stringArray = (ArrayList<String>) arrayList;
                return stringArray.toArray(new String[stringArray.size()]);
            }

            if (arrayList.get(0) instanceof CharSequence) {
                ArrayList<CharSequence> charSeqList = (ArrayList<CharSequence>) arrayList;
                final int N = charSeqList.size();
                String[] array = new String[N];
                for (int i = 0; i < N; i++) {
                    array[i] = charSeqList.get(i).toString();
                }
                return array;
            }

            return null;
        }

        if (people instanceof String) {
            String[] array = new String[1];
            array[0] = (String) people;
            return array;
        }

        if (people instanceof char[]) {
            String[] array = new String[1];
            array[0] = new String((char[]) people);
            return array;
        }

        if (people instanceof CharSequence) {
            String[] array = new String[1];
            array[0] = ((CharSequence) people).toString();
            return array;
        }

        if (people instanceof CharSequence[]) {
            CharSequence[] charSeqArray = (CharSequence[]) people;
            final int N = charSeqArray.length;
            String[] array = new String[N];
            for (int i = 0; i < N; i++) {
                array[i] = charSeqArray[i].toString();
            }
            return array;
        }

        return null;
    }

    private LookupResult resolvePhoneContact(Context context, final String number) {
        Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        return searchContacts(context, phoneUri);
    }

    private LookupResult resolveEmailContact(Context context, final String email) {
        Uri numberUri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI,
                Uri.encode(email));
        return searchContacts(context, numberUri);
    }

    private LookupResult searchContacts(Context context, Uri lookupUri) {
        LookupResult lookupResult = new LookupResult();
        Cursor c = null;
        try {
            c = context.getContentResolver().query(lookupUri, LOOKUP_PROJECTION, null, null, null);
            if (c == null) {
                Slog.w(TAG, "Null cursor from contacts query.");
                return lookupResult;
            }
            while (c.moveToNext()) {
                lookupResult.mergeContact(c);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return lookupResult;
    }

    private static class LookupResult {
        private static final long CONTACT_REFRESH_MILLIS = 60 * 60 * 1000;  // 1hr

        private final long mExpireMillis;
        private float mAffinity = NONE;

        public LookupResult() {
            mExpireMillis = System.currentTimeMillis() + CONTACT_REFRESH_MILLIS;
        }

        public void mergeContact(Cursor cursor) {
            mAffinity = Math.max(mAffinity, VALID_CONTACT);

            // Contact ID
            int id;
            final int idIdx = cursor.getColumnIndex(Contacts._ID);
            if (idIdx >= 0) {
                id = cursor.getInt(idIdx);
                if (DEBUG) Slog.d(TAG, "contact _ID is: " + id);
            } else {
                id = -1;
                Slog.i(TAG, "invalid cursor: no _ID");
            }

            // Starred
            final int starIdx = cursor.getColumnIndex(Contacts.STARRED);
            if (starIdx >= 0) {
                boolean isStarred = cursor.getInt(starIdx) != 0;
                if (isStarred) {
                    mAffinity = Math.max(mAffinity, STARRED_CONTACT);
                }
                if (DEBUG) Slog.d(TAG, "contact STARRED is: " + isStarred);
            } else {
                if (DEBUG) Slog.d(TAG, "invalid cursor: no STARRED");
            }
        }

        private boolean isExpired() {
            return mExpireMillis < System.currentTimeMillis();
        }

        private boolean isInvalid() {
            return mAffinity == NONE || isExpired();
        }

        public float getAffinity() {
            if (isInvalid()) {
                return NONE;
            }
            return mAffinity;
        }
    }

    private class PeopleRankingReconsideration extends RankingReconsideration {
        private final LinkedList<String> mPendingLookups;
        private final Context mContext;

        private float mContactAffinity = NONE;
        private NotificationRecord mRecord;

        private PeopleRankingReconsideration(Context context, String key, LinkedList<String> pendingLookups) {
            super(key);
            mContext = context;
            mPendingLookups = pendingLookups;
        }

        @Override
        public void work() {
            long start = SystemClock.elapsedRealtime();
            if (VERBOSE) Slog.i(TAG, "Executing: validation for: " + mKey);
            long timeStartMs = System.currentTimeMillis();
            for (final String handle: mPendingLookups) {
                LookupResult lookupResult = null;
                final Uri uri = Uri.parse(handle);
                if ("tel".equals(uri.getScheme())) {
                    if (DEBUG) Slog.d(TAG, "checking telephone URI: " + handle);
                    lookupResult = resolvePhoneContact(mContext, uri.getSchemeSpecificPart());
                } else if ("mailto".equals(uri.getScheme())) {
                    if (DEBUG) Slog.d(TAG, "checking mailto URI: " + handle);
                    lookupResult = resolveEmailContact(mContext, uri.getSchemeSpecificPart());
                } else if (handle.startsWith(Contacts.CONTENT_LOOKUP_URI.toString())) {
                    if (DEBUG) Slog.d(TAG, "checking lookup URI: " + handle);
                    lookupResult = searchContacts(mContext, uri);
                } else {
                    lookupResult = new LookupResult();  // invalid person for the cache
                    Slog.w(TAG, "unsupported URI " + handle);
                }
                if (lookupResult != null) {
                    synchronized (mPeopleCache) {
                        final String cacheKey = getCacheKey(mContext.getUserId(), handle);
                        mPeopleCache.put(cacheKey, lookupResult);
                    }
                    if (DEBUG) Slog.d(TAG, "lookup contactAffinity is " + lookupResult.getAffinity());
                    mContactAffinity = Math.max(mContactAffinity, lookupResult.getAffinity());
                } else {
                    if (DEBUG) Slog.d(TAG, "lookupResult is null");
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Validation finished in " + (System.currentTimeMillis() - timeStartMs) +
                        "ms");
            }

            if (mRecord != null) {
                mUsageStats.registerPeopleAffinity(mRecord, mContactAffinity > NONE,
                        mContactAffinity == STARRED_CONTACT, false /* cached */);
            }

            MetricsLogger.histogram(mBaseContext, "validate_people_lookup_latency",
                    (int) (SystemClock.elapsedRealtime() - start));
        }

        @Override
        public void applyChangesLocked(NotificationRecord operand) {
            float affinityBound = operand.getContactAffinity();
            operand.setContactAffinity(Math.max(mContactAffinity, affinityBound));
            if (VERBOSE) Slog.i(TAG, "final affinity: " + operand.getContactAffinity());
        }

        public float getContactAffinity() {
            return mContactAffinity;
        }

        public void setRecord(NotificationRecord record) {
            mRecord = record;
        }
    }
}

