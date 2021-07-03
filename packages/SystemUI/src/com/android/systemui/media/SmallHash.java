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

package com.android.systemui.media;

import java.util.Objects;

/**
 * A simple hash function for use in privacy-sensitive logging.
 */
public final class SmallHash {
    // Hashes will be in the range [0, MAX_HASH).
    public static final int MAX_HASH = (1 << 13);

    /** Return Small hash of the string, if non-null, or 0 otherwise. */
    public static int hash(String in) {
        return hash(Objects.hashCode(in));
    }

    /**
     * Maps in to the range [0, MAX_HASH), keeping similar values distinct.
     *
     * @param in An arbitrary integer.
     * @return in mod MAX_HASH, signs chosen to stay in the range [0, MAX_HASH).
     */
    public static int hash(int in) {
        return Math.abs(Math.floorMod(in, MAX_HASH));
    }

    private SmallHash() {}
}
