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

package com.android.internal.logging.testing;

import android.annotation.SuppressLint;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;

/**
 * A fake implementation of InstanceIdSequence that returns 0, 1, 2, ...
 */
public class InstanceIdSequenceFake extends InstanceIdSequence {

    public InstanceIdSequenceFake(int instanceIdMax) {
        super(instanceIdMax);
    }

    /**
     * Extend InstanceId to add a constructor we can call, strictly for testing purposes.
     * Public so that tests can check whether the InstanceIds they see are fake.
     */
    public static class InstanceIdFake extends InstanceId {
        @SuppressLint("VisibleForTests")  // This is test infrastructure, which ought to count
        InstanceIdFake(int id) {
            super(id);
        }
    }

    private int mNextId = 0;

    @Override
    public InstanceId newInstanceId() {
        synchronized (this) {
            ++mNextId;
            if (mNextId >= mInstanceIdMax) {
                mNextId = 0;
            }
            return new InstanceIdFake(mNextId);
        }
    }
}
