/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.annotation.Nullable;

import java.util.Arrays;

/** @hide */
public final class Pin {
    public final String digestAlgorithm;
    public final byte[] digest;

    private final int mHashCode;

    public Pin(String digestAlgorithm, byte[] digest) {
        this.digestAlgorithm = digestAlgorithm;
        this.digest = digest;
        mHashCode = Arrays.hashCode(digest) ^ digestAlgorithm.hashCode();
    }

    /**
     * @hide
     */
    public static boolean isSupportedDigestAlgorithm(String algorithm) {
        // Currently only SHA-256 is supported. SHA-512 if/once Chromium networking stack
        // supports it.
        return "SHA-256".equalsIgnoreCase(algorithm);
    }

    /**
     * @hide
     */
    public static int getDigestLength(String algorithm) {
        if ("SHA-256".equalsIgnoreCase(algorithm)) {
            return 32;
        }
        throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm);
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Pin)) {
            return false;
        }
        Pin other = (Pin) obj;
        if (other.hashCode() != mHashCode) {
            return false;
        }
        if (!Arrays.equals(digest, other.digest)) {
            return false;
        }
        if (!digestAlgorithm.equals(other.digestAlgorithm)) {
            return false;
        }
        return true;
    }
}
