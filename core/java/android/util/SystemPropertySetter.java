/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemProperties;

/**
 * This class provides a single, static function to set a system property.  The function retries on
 * error.  System properties should be reliable but there have been reports of failures in the set()
 * command on lower-end devices.  Clients may want to use this method instead of calling
 * {@link SystemProperties.set} directly.
 * @hide
 */
public class SystemPropertySetter {

    /**
     * The default retryDelayMs for {@link #setWithRetry}.  This value has been found to give
     * reasonable behavior in the field.
     */
    public static final int PROPERTY_FAILURE_RETRY_DELAY_MILLIS = 200;

    /**
     * The default retryLimit for {@link #setWithRetry}.  This value has been found to give
     * reasonable behavior in the field.
     */
    public static final int PROPERTY_FAILURE_RETRY_LIMIT = 5;

    /**
     * Set the value for the given {@code key} to {@code val}.  This method retries using the
     * standard parameters, above, if the native method throws a RuntimeException.
     *
     * @param key The name of the property to be set.
     * @param val The new string value of the property.
     * @throws IllegalArgumentException for non read-only properties if the {@code val} exceeds
     * 91 characters.
     * @throws RuntimeException if the property cannot be set, for example, if it was blocked by
     * SELinux. libc will log the underlying reason.
     */
    public static void setWithRetry(@NonNull String key, @Nullable String val) {
        setWithRetry(key, val,PROPERTY_FAILURE_RETRY_DELAY_MILLIS, PROPERTY_FAILURE_RETRY_LIMIT);
    }

    /**
     * Set the value for the given {@code key} to {@code val}.  This method retries if the native
     * method throws a RuntimeException.  If the {@code maxRetry} count is exceeded, the method
     * throws the first RuntimeException that was seen.
     *
     * @param key The name of the property to be set.
     * @param val The new string value of the property.
     * @param maxRetry The maximum number of times; must be non-negative.
     * @param retryDelayMs The number of milliseconds to wait between retries; must be positive.
     * @throws IllegalArgumentException for non read-only properties if the {@code val} exceeds
     * 91 characters, or if the retry parameters are invalid.
     * @throws RuntimeException if the property cannot be set, for example, if it was blocked by
     * SELinux. libc will log the underlying reason.
     */
    public static void setWithRetry(@NonNull String key, @Nullable String val, int maxRetry,
            long retryDelayMs) {
        if (maxRetry < 0) {
            throw new IllegalArgumentException("invalid retry count: " + maxRetry);
        }
        if (retryDelayMs <= 0) {
            throw new IllegalArgumentException("invalid retry delay: " + retryDelayMs);
        }

        RuntimeException failure = null;
        for (int attempt = 0; attempt < maxRetry; attempt++) {
            try {
                SystemProperties.set(key, val);
                return;
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException x) {
                    // Ignore this exception.  The desired delay is only approximate and
                    // there is no issue if the sleep sometimes terminates early.
                }
            }
        }
        // This point is reached only if SystemProperties.set() fails at least once.
        // Rethrow the first exception that was received.
        throw failure;
    }
}
