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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.storage.StorageManager;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.io.File;


/**
 * Unit tests for {@link HashedStringCache}.
 */
public class HashedStringCacheTest {
    private static final String TAG = "HashedStringCacheTest";
    private Context mContext;
    private static final String TEST_STRING = "test_string";

    @Before
    public void setup() {
        mContext = null;
        mContext = InstrumentationRegistry.getContext();
        clearSharedPreferences();
    }

    @Test
    public void testInstanceNotNull() {
        HashedStringCache cache = HashedStringCache.getInstance();
        assertThat(cache, is(notNullValue()));
    }

    @Test
    public void testInstanceMatchesOnSecondCall() {
        HashedStringCache cache = HashedStringCache.getInstance();
        assertThat(HashedStringCache.getInstance(), is(cache));
    }

    @Test
    public void testHashedStringNotOriginalString() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        assertThat(cachedResult.hashedString, is(not(TEST_STRING)));
    }

    @Test
    public void testThatMultipleCallsResultInSameHash() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        HashedStringCache.HashResult cachedResult2 =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        assertThat(cachedResult2.hashedString, is(cachedResult.hashedString));
    }


    @Test
    public void testThatMultipleInputResultInDifferentHash() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        HashedStringCache.HashResult cachedResult2 =
                cache.hashString(mContext, TAG, "different_test", 7);
        assertThat(cachedResult2.hashedString, is(not(cachedResult.hashedString)));
    }

    @Test
    public void testThatZeroDaysResultsInNewHash() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        HashedStringCache.HashResult cachedResult2 =
                cache.hashString(mContext, TAG, TEST_STRING, 0);
        assertThat(cachedResult2.hashedString, is(not(cachedResult.hashedString)));
    }

    @Test
    public void testThatNegativeDaysResultsInNewHash() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        HashedStringCache.HashResult cachedResult2 =
                cache.hashString(mContext, TAG, TEST_STRING, -10);
        assertThat(cachedResult2.hashedString, is(not(cachedResult.hashedString)));
    }

    @Test
    public void testThatDaysGreater365ResultsInSameResult() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, 7);
        HashedStringCache.HashResult cachedResult2 =
                cache.hashString(mContext, TAG, TEST_STRING, 400);
        assertThat(cachedResult2.hashedString, is(cachedResult.hashedString));
    }

    /**
     * -1 is treated as a special input to short-circuit out of doing the hashing to give us
     * the option to turn this feature off if need be while incurring as little computational cost
     * as possible.
     */
    @Test
    public void testMinusOneResultsInNull() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, TEST_STRING, -1);
        assertThat(cachedResult, is(nullValue()));
    }

    @Test
    public void testEmptyStringInput() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, "", -1);
        assertThat(cachedResult, is(nullValue()));
    }

    @Test
    public void testNullInput() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, TAG, null, -1);
        assertThat(cachedResult, is(nullValue()));
    }

    @Test
    public void testEmptyStringTag() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, "", TEST_STRING, -1);
        assertThat(cachedResult, is(nullValue()));
    }

    @Test
    public void testNullTag() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(mContext, null, TEST_STRING, -1);
        assertThat(cachedResult, is(nullValue()));
    }

    @Test
    public void testNullContext() {
        HashedStringCache cache = HashedStringCache.getInstance();
        HashedStringCache.HashResult cachedResult =
                cache.hashString(null, TAG, TEST_STRING, -1);
        assertThat(cachedResult, is(nullValue()));
    }

    private void clearSharedPreferences() {
        SharedPreferences preferences = getTestSharedPreferences(mContext);
        preferences.edit()
                .remove(TAG + HashedStringCache.HASH_SALT)
                .remove(TAG + HashedStringCache.HASH_SALT_DATE)
                .remove(TAG + HashedStringCache.HASH_SALT_GEN).apply();
    }

    /**
     * Android:ui doesn't have persistent preferences, so need to fall back on this hack originally
     * from ChooserActivity.java
     * @param context
     * @return
     */
    private SharedPreferences getTestSharedPreferences(Context context) {
        final File prefsFile = new File(new File(
                Environment.getDataUserCePackageDirectory(
                        StorageManager.UUID_PRIVATE_INTERNAL,
                        context.getUserId(), context.getPackageName()),
                "shared_prefs"),
                "hashed_cache_test.xml");
        return context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
    }
}
