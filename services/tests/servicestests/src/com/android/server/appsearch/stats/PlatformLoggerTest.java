/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch.stats;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.AppSearchConfig;
import com.android.server.appsearch.external.localstorage.stats.CallStats;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Tests covering the functionalities in {@link PlatformLogger} NOT requiring overriding any flags
 * in {@link android.provider.DeviceConfig}.
 *
 * <p>To add tests rely on overriding the flags, please add them in the
 * tests for {@link PlatformLogger} in mockingservicestests.
 */
public class PlatformLoggerTest {
    private final Map<UserHandle, PackageManager> mMockPackageManagers = new ArrayMap<>();
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        mContext = new ContextWrapper(context) {
            @Override
            public PackageManager getPackageManager() {
                return getMockPackageManager(mContext.getUser());
            }
        };
    }

    @Test
    public void testCalculateHashCode_MD5_int32_shortString()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final String str1 = "d1";
        final String str2 = "d2";

        int hashCodeForStr1 = PlatformLogger.calculateHashCodeMd5(str1);

        // hashing should be stable
        assertThat(hashCodeForStr1).isEqualTo(
                PlatformLogger.calculateHashCodeMd5(str1));
        assertThat(hashCodeForStr1).isNotEqualTo(
                PlatformLogger.calculateHashCodeMd5(str2));
    }

    @Test
    public void testGetCalculateCode_MD5_int32_mediumString()
            throws NoSuchAlgorithmException, UnsupportedEncodingException {
        final String str1 = "Siblings";
        final String str2 = "Teheran";

        int hashCodeForStr1 = PlatformLogger.calculateHashCodeMd5(str1);

        // hashing should be stable
        assertThat(hashCodeForStr1).isEqualTo(
                PlatformLogger.calculateHashCodeMd5(str1));
        assertThat(hashCodeForStr1).isNotEqualTo(
                PlatformLogger.calculateHashCodeMd5(str2));
    }

    @Test
    public void testCalculateHashCode_MD5_int32_longString() throws NoSuchAlgorithmException,
            UnsupportedEncodingException {
        final String str1 = "abcdefghijkl-mnopqrstuvwxyz";
        final String str2 = "abcdefghijkl-mnopqrstuvwxy123";

        int hashCodeForStr1 = PlatformLogger.calculateHashCodeMd5(str1);

        // hashing should be stable
        assertThat(hashCodeForStr1).isEqualTo(
                PlatformLogger.calculateHashCodeMd5(str1));
        assertThat(hashCodeForStr1).isNotEqualTo(
                PlatformLogger.calculateHashCodeMd5(str2));
    }

    @Test
    public void testCalculateHashCode_MD5_int32_sameAsBigInteger_intValue() throws
            NoSuchAlgorithmException, UnsupportedEncodingException {
        final String emptyStr = "";
        final String shortStr = "a";
        final String mediumStr = "Teheran";
        final String longStr = "abcd-efgh-ijkl-mnop-qrst-uvwx-yz";

        int emptyHashCode = PlatformLogger.calculateHashCodeMd5(emptyStr);
        int shortHashCode = PlatformLogger.calculateHashCodeMd5(shortStr);
        int mediumHashCode = PlatformLogger.calculateHashCodeMd5(mediumStr);
        int longHashCode = PlatformLogger.calculateHashCodeMd5(longStr);

        assertThat(emptyHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(emptyStr));
        assertThat(shortHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(shortStr));
        assertThat(mediumHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(mediumStr));
        assertThat(longHashCode).isEqualTo(calculateHashCodeMd5withBigInteger(longStr));
    }

    @Test
    public void testCalculateHashCode_MD5_strIsNull() throws
            NoSuchAlgorithmException, UnsupportedEncodingException {
        assertThat(PlatformLogger.calculateHashCodeMd5(/*str=*/ null)).isEqualTo(-1);
    }

    /** Makes sure the caching works while getting the UID for calling package. */
    @Test
    public void testGetPackageUidAsUser() throws Exception {
        final String testPackageName = "packageName";
        final int testUid = 1234;
        PlatformLogger logger = new PlatformLogger(
                mContext,
                AppSearchConfig.create(DIRECT_EXECUTOR));
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(testPackageName, /*flags=*/0)).thenReturn(testUid);

        // First time, no cache
        PlatformLogger.ExtraStats extraStats = logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT);
        verify(mockPackageManager, times(1))
                .getPackageUid(eq(testPackageName), /*flags=*/ anyInt());
        assertThat(extraStats.mPackageUid).isEqualTo(testUid);

        // Second time, we have cache
        extraStats = logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT);

        // Count is still one since we will use the cache
        verify(mockPackageManager, times(1))
                .getPackageUid(eq(testPackageName), /*flags=*/ anyInt());
        assertThat(extraStats.mPackageUid).isEqualTo(testUid);

        // Remove the cache and try again
        assertThat(logger.removeCachedUidForPackage(testPackageName)).isEqualTo(testUid);
        extraStats = logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT);

        // count increased by 1 since cache is cleared
        verify(mockPackageManager, times(2))
                .getPackageUid(eq(testPackageName), /*flags=*/ anyInt());
        assertThat(extraStats.mPackageUid).isEqualTo(testUid);
    }

    private static int calculateHashCodeMd5withBigInteger(@NonNull String str)
            throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(str.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md.digest();
        return new BigInteger(digest).intValue();
    }

    @NonNull
    private PackageManager getMockPackageManager(@NonNull UserHandle user) {
        PackageManager pm = mMockPackageManagers.get(user);
        if (pm == null) {
            pm = Mockito.mock(PackageManager.class);
            mMockPackageManagers.put(user, pm);
        }
        return pm;
    }
}
