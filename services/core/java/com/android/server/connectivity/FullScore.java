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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.net.NetworkScore;

/**
 * This class represents how desirable a network is.
 *
 * FullScore is very similar to NetworkScore, but it contains the bits that are managed
 * by ConnectivityService. This provides static guarantee that all users must know whether
 * they are handling a score that had the CS-managed bits set.
 */
public class FullScore {
    // This will be removed soon. Do *NOT* depend on it for any new code that is not part of
    // a migration.
    private final int mLegacyInt;

    // Agent-managed policies are in NetworkScore. They start from 1.
    // CS-managed policies
    // This network is validated. CS-managed because the source of truth is in NetworkCapabilities.
    public static final int POLICY_IS_VALIDATED = 63;

    // Bitmask of all the policies applied to this score.
    private final long mPolicies;

    FullScore(final int legacyInt, final long policies) {
        mLegacyInt = legacyInt;
        mPolicies = policies;
    }

    /**
     * Make a FullScore from a NetworkScore
     */
    public static FullScore withPolicy(@NonNull final NetworkScore originalScore,
            final boolean isValidated) {
        return new FullScore(originalScore.getLegacyInt(),
                isValidated ? 1L << POLICY_IS_VALIDATED : 0L);
    }

    /**
     * For backward compatibility, get the legacy int.
     * This will be removed before S is published.
     */
    public int getLegacyInt() {
        return mLegacyInt;
    }

    @Override
    public String toString() {
        return "Score(" + mLegacyInt + ")";
    }
}
