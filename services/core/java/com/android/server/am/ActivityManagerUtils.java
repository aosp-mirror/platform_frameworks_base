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
package com.android.server.am;

import android.app.ActivityThread;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * To store random utility methods...
 */
public class ActivityManagerUtils {
    private ActivityManagerUtils() {
    }

    private static Integer sAndroidIdHash;

    @GuardedBy("sHashCache")
    private static final ArrayMap<String, Integer> sHashCache = new ArrayMap<>();

    private static String sInjectedAndroidId;

    /** Used by the unit tests to inject an android ID. Do not set in the prod code. */
    @VisibleForTesting
    static void injectAndroidIdForTest(String androidId) {
        sInjectedAndroidId = androidId;
        sAndroidIdHash = null;
    }

    /**
     * Return a hash between [0, MAX_VALUE] generated from the android ID.
     */
    @VisibleForTesting
    static int getAndroidIdHash() {
        // No synchronization is required. Double-initialization is fine here.
        if (sAndroidIdHash == null) {
            final String androidId = Settings.Secure.getString(
                    ActivityThread.currentApplication().getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            sAndroidIdHash = getUnsignedHashUnCached(
                    sInjectedAndroidId != null ? sInjectedAndroidId : androidId);
        }
        return sAndroidIdHash;
    }

    /**
     * Return a hash between [0, MAX_VALUE] generated from a package name, using a cache.
     *
     * Because all the results are cached, do not use it for dynamically generated strings.
     */
    @VisibleForTesting
    static int getUnsignedHashCached(String s) {
        synchronized (sHashCache) {
            final Integer cached = sHashCache.get(s);
            if (cached != null) {
                return cached;
            }
            final int hash = getUnsignedHashUnCached(s);
            sHashCache.put(s.intern(), hash);
            return hash;
        }
    }

    /**
     * Return a hash between [0, MAX_VALUE] generated from a package name.
     */
    private static int getUnsignedHashUnCached(String s) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(s.getBytes());
            return unsignedIntFromBytes(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    static int unsignedIntFromBytes(byte[] longEnoughBytes) {
        return (extractByte(longEnoughBytes, 0)
                | extractByte(longEnoughBytes, 1)
                | extractByte(longEnoughBytes, 2)
                | extractByte(longEnoughBytes, 3))
                & 0x7FFF_FFFF;
    }

    private static int extractByte(byte[] bytes, int index) {
        return (((int) bytes[index]) & 0xFF) << (index * 8);
    }

    /**
     * @return whether a package should be logged, using a random value based on the ANDROID_ID,
     * with a given sampling rate.
     */
    public static boolean shouldSamplePackageForAtom(String packageName, float rate) {
        if (rate <= 0) {
            return false;
        }
        if (rate >= 1) {
            return true;
        }
        final int hash = getUnsignedHashCached(packageName) ^ getAndroidIdHash();

        return (((double) hash) / Integer.MAX_VALUE) <= rate;
    }

    /**
     * @param shortInstanceName {@link ServiceRecord#shortInstanceName}.
     * @return hash of the ServiceRecord's shortInstanceName, combined with ANDROID_ID.
     */
    public static int hashComponentNameForAtom(String shortInstanceName) {
        return getUnsignedHashUnCached(shortInstanceName) ^ getAndroidIdHash();
    }
}
