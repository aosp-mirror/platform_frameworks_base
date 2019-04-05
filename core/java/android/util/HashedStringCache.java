/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * HashedStringCache provides hashing functionality with an underlying LRUCache and expiring salt.
 * Salt and expiration time are being stored under the tag passed in by the calling package --
 * intended usage is the calling package name.
 * @hide
 */
public class HashedStringCache {
    private static HashedStringCache sHashedStringCache = null;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int HASH_CACHE_SIZE = 100;
    private static final int HASH_LENGTH = 8;
    @VisibleForTesting
    static final String HASH_SALT = "_hash_salt";
    @VisibleForTesting
    static final String HASH_SALT_DATE = "_hash_salt_date";
    @VisibleForTesting
    static final String HASH_SALT_GEN = "_hash_salt_gen";
    // For privacy we need to rotate the salt regularly
    private static final long DAYS_TO_MILLIS = 1000 * 60 * 60 * 24;
    private static final int MAX_SALT_DAYS = 100;
    private final LruCache<String, String> mHashes;
    private final SecureRandom mSecureRandom;
    private final Object mPreferenceLock = new Object();
    private final MessageDigest mDigester;
    private byte[] mSalt;
    private int mSaltGen;
    private SharedPreferences mSharedPreferences;

    private static final String TAG = "HashedStringCache";
    private static final boolean DEBUG = false;

    private HashedStringCache() {
        mHashes = new LruCache<>(HASH_CACHE_SIZE);
        mSecureRandom = new SecureRandom();
        try {
            mDigester = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            // this can't happen - MD5 is always present
            throw new RuntimeException(impossible);
        }
    }

    /**
     * @return - instance of the HashedStringCache
     * @hide
     */
    public static HashedStringCache getInstance() {
        if (sHashedStringCache == null) {
            sHashedStringCache = new HashedStringCache();
        }
        return sHashedStringCache;
    }

    /**
     * Take the string and context and create a hash of the string. Trigger refresh on salt if salt
     * is more than 7 days old
     * @param context - callers context to retrieve SharedPreferences
     * @param clearText - string that needs to be hashed
     * @param tag - class name to use for storing values in shared preferences
     * @param saltExpirationDays - number of days we may keep the same salt
     *                           special value -1 will short-circuit and always return null.
     * @return - HashResult containing the hashed string and the generation of the hash salt, null
     *      if clearText string is empty
     *
     * @hide
     */
    public HashResult hashString(Context context, String tag, String clearText,
            int saltExpirationDays) {
        if (saltExpirationDays == -1 || context == null
                || TextUtils.isEmpty(clearText) || TextUtils.isEmpty(tag)) {
            return null;
        }

        populateSaltValues(context, tag, saltExpirationDays);
        String hashText = mHashes.get(clearText);
        if (hashText != null) {
            return new HashResult(hashText, mSaltGen);
        }

        mDigester.reset();
        mDigester.update(mSalt);
        mDigester.update(clearText.getBytes(UTF_8));
        byte[] bytes = mDigester.digest();
        int len = Math.min(HASH_LENGTH, bytes.length);
        hashText = Base64.encodeToString(bytes, 0, len, Base64.NO_PADDING | Base64.NO_WRAP);
        mHashes.put(clearText, hashText);

        return new HashResult(hashText, mSaltGen);
    }

    /**
     * Populates the mSharedPreferences and checks if there is a salt present and if it's older than
     * 7 days
     * @param tag - class name to use for storing values in shared preferences
     * @param saltExpirationDays - number of days we may keep the same salt
     * @param saltDate - the date retrieved from configuration
     * @return - true if no salt or salt is older than 7 days
     */
    private boolean checkNeedsNewSalt(String tag, int saltExpirationDays, long saltDate) {
        if (saltDate == 0 || saltExpirationDays < -1) {
            return true;
        }
        if (saltExpirationDays > MAX_SALT_DAYS) {
            saltExpirationDays = MAX_SALT_DAYS;
        }
        long now = System.currentTimeMillis();
        long delta = now - saltDate;
        // Check for delta < 0 to make sure we catch if someone puts their phone far in the
        // future and then goes back to normal time.
        return delta >= saltExpirationDays * DAYS_TO_MILLIS || delta < 0;
    }

    /**
     * Populate the salt and saltGen member variables if they aren't already set / need refreshing.
     * @param context - to get sharedPreferences
     * @param tag - class name to use for storing values in shared preferences
     * @param saltExpirationDays - number of days we may keep the same salt
     */
    private void populateSaltValues(Context context, String tag, int saltExpirationDays) {
        synchronized (mPreferenceLock) {
            // check if we need to refresh the salt
            mSharedPreferences = getHashSharedPreferences(context);
            long saltDate = mSharedPreferences.getLong(tag + HASH_SALT_DATE, 0);
            boolean needsNewSalt = checkNeedsNewSalt(tag, saltExpirationDays, saltDate);
            if (needsNewSalt) {
                mHashes.evictAll();
            }
            if (mSalt == null || needsNewSalt) {
                String saltString = mSharedPreferences.getString(tag + HASH_SALT, null);
                mSaltGen = mSharedPreferences.getInt(tag + HASH_SALT_GEN, 0);
                if (saltString == null || needsNewSalt) {
                    mSaltGen++;
                    byte[] saltBytes = new byte[16];
                    mSecureRandom.nextBytes(saltBytes);
                    saltString = Base64.encodeToString(saltBytes,
                            Base64.NO_PADDING | Base64.NO_WRAP);
                    mSharedPreferences.edit()
                            .putString(tag + HASH_SALT, saltString)
                            .putInt(tag + HASH_SALT_GEN, mSaltGen)
                            .putLong(tag + HASH_SALT_DATE, System.currentTimeMillis()).apply();
                    if (DEBUG) {
                        Log.d(TAG, "created a new salt: " + saltString);
                    }
                }
                mSalt = saltString.getBytes(UTF_8);
            }
        }
    }

    /**
     * Android:ui doesn't have persistent preferences, so need to fall back on this hack originally
     * from ChooserActivity.java
     * @param context
     * @return
     */
    private SharedPreferences getHashSharedPreferences(Context context) {
        final File prefsFile = new File(new File(
                Environment.getDataUserCePackageDirectory(
                        StorageManager.UUID_PRIVATE_INTERNAL,
                        context.getUserId(), context.getPackageName()),
                "shared_prefs"),
                "hashed_cache.xml");
        return context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
    }

    /**
     * Helper class to hold hashed string and salt generation.
     */
    public class HashResult {
        public String hashedString;
        public int saltGeneration;

        public HashResult(String hString, int saltGen) {
            hashedString = hString;
            saltGeneration = saltGen;
        }
    }
}
