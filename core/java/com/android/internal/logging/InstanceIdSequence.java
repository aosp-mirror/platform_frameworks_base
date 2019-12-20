/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.logging;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Generates random InstanceIds in range [0, instanceIdMax) for passing to
 * UiEventLogger.logWithInstanceId(). Holds a SecureRandom, which self-seeds on
 * first use; try to give it a long lifetime. Safe for concurrent use.
 */
public class InstanceIdSequence {
    // At most 20 bits: ~1m possibilities, ~0.5% probability of collision in 100 values
    private static final int INSTANCE_ID_MAX = 1 << 20;
    protected final int mInstanceIdMax;
    private final Random mRandom = new SecureRandom();

    /**
     * Constructs a sequence with identifiers [0, instanceIdMax).  Capped at INSTANCE_ID_MAX.
     * @param instanceIdMax Limiting value of identifiers. Normally positive: otherwise you get
     *                      an all-zero sequence.
     */
    public InstanceIdSequence(int instanceIdMax) {
        mInstanceIdMax = min(max(0, instanceIdMax), INSTANCE_ID_MAX);
    }

    /**
     * Gets the next instance from the sequence.  Safe for concurrent use.
     * @return new InstanceId
     */
    public InstanceId newInstanceId() {
        return new InstanceId(mRandom.nextInt(mInstanceIdMax));
    }
}
