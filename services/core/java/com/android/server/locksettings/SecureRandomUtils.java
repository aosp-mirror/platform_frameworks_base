/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.locksettings;

import java.security.SecureRandom;

/** Utilities using a static SecureRandom */
public class SecureRandomUtils {
    private static final SecureRandom RNG = new SecureRandom();

    /** Use SecureRandom to generate `length` random bytes */
    public static byte[] randomBytes(int length) {
        byte[] res = new byte[length];
        RNG.nextBytes(res);
        return res;
    }

    /** Use SecureRandom to generate a random long */
    public static long randomLong() {
        return RNG.nextLong();
    }
}
