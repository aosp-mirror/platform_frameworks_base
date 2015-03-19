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
package com.android.internal.os;

import java.util.HashMap;

/**
 * Kernel wakelock stats object.
 */
public class KernelWakelockStats extends HashMap<String, KernelWakelockStats.Entry> {
    public static class Entry {
        public int mCount;
        public long mTotalTime;
        public int mVersion;

        Entry(int count, long totalTime, int version) {
            mCount = count;
            mTotalTime = totalTime;
            mVersion = version;
        }
    }

    int kernelWakelockVersion;
}
