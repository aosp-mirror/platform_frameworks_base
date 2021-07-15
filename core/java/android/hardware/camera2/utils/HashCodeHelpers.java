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

package android.hardware.camera2.utils;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * Provide hashing functions using the Modified Bernstein hash
 */
public final class HashCodeHelpers {

    /**
     * Hash every element uniformly using the Modified Bernstein hash.
     *
     * <p>Useful to implement a {@link Object#hashCode} for uniformly distributed data.</p>
     *
     * @param array a non-{@code null} array of integers
     *
     * @return the numeric hash code
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int hashCode(int... array) {
        if (array == null) {
            return 0;
        }

        /*
         *  Note that we use 31 here instead of 33 since it's preferred in Effective Java
         *  and used elsewhere in the runtime (e.g. Arrays#hashCode)
         *
         *  That being said 33 and 31 are nearly identical in terms of their usefulness
         *  according to http://svn.apache.org/repos/asf/apr/apr/trunk/tables/apr_hash.c
         */
        int h = 1;
        for (int x : array) {
            // Strength reduction; in case the compiler has illusions about divisions being faster
            h = ((h << 5) - h) ^ x; // (h * 31) XOR x
        }

        return h;
    }

    /**
     * Hash every element uniformly using the Modified Bernstein hash.
     *
     * <p>Useful to implement a {@link Object#hashCode} for uniformly distributed data.</p>
     *
     * @param array a non-{@code null} array of floats
     *
     * @return the numeric hash code
     */
    public static int hashCode(float... array) {
        if (array == null) {
            return 0;
        }

        int h = 1;
        for (float f : array) {
            int x = Float.floatToIntBits(f);
            h = ((h << 5) - h) ^ x; // (h * 31) XOR x
        }

        return h;
    }

    /**
     * Hash every element uniformly using the Modified Bernstein hash.
     *
     * <p>Useful to implement a {@link Object#hashCode} for uniformly distributed data.</p>
     *
     * @param array a non-{@code null} array of objects
     *
     * @return the numeric hash code
     */
    public static <T> int hashCodeGeneric(T... array) {
        if (array == null) {
            return 0;
        }

        int h = 1;
        for (T o : array) {
            int x = (o == null) ? 0 : o.hashCode();
            h = ((h << 5) - h) ^ x; // (h * 31) XOR x
        }

        return h;
    }

}
