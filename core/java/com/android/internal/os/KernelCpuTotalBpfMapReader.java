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

package com.android.internal.os;

import android.annotation.Nullable;

/** Reads total CPU time bpf map. */
public final class KernelCpuTotalBpfMapReader {
    private KernelCpuTotalBpfMapReader() {
    }

    /** Reads total CPU times (excluding sleep) per frequency in milliseconds from bpf map. */
    @Nullable
    public static long[] read() {
        if (!KernelCpuBpfTracking.startTracking()) {
            return null;
        }
        return readInternal();
    }

    @Nullable
    private static native long[] readInternal();
}
