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

package com.android.internal.os;

import android.annotation.Nullable;

/**
 * CPU tracking using eBPF.
 *
 * The tracking state and data about available frequencies are cached to avoid JNI calls and
 * creating temporary arrays. The data is stored in a format that is convenient for metrics
 * computation.
 *
 * Synchronization is not needed because the underlying native library can be invoked concurrently
 * and getters are idempotent.
 */
public final class KernelCpuBpfTracking {
    private static boolean sTracking = false;

    /** Cached mapping from frequency index to frequency in kHz. */
    private static long[] sFreqs = null;

    /** Cached mapping from frequency index to CPU cluster / policy. */
    private static int[] sFreqsClusters = null;

    private KernelCpuBpfTracking() {
    }

    /** Returns whether CPU tracking using eBPF is supported. */
    public static native boolean isSupported();

    /** Starts CPU tracking using eBPF. */
    public static boolean startTracking() {
        if (!sTracking) {
            sTracking = startTrackingInternal();
        }
        return sTracking;
    }

    private static native boolean startTrackingInternal();

    /** Returns frequencies in kHz on which CPU is tracked. Empty if not supported. */
    public static long[] getFreqs() {
        if (sFreqs == null) {
            long[] freqs = getFreqsInternal();
            if (freqs == null) {
                return new long[0];
            }
            sFreqs = freqs;
        }
        return sFreqs;
    }

    @Nullable
    static native long[] getFreqsInternal();

    /**
     * Returns the cluster (policy) number  for each frequency on which CPU is tracked. Empty if
     * not supported.
     */
    public static int[] getFreqsClusters() {
        if (sFreqsClusters == null) {
            int[] freqsClusters = getFreqsClustersInternal();
            if (freqsClusters == null) {
                return new int[0];
            }
            sFreqsClusters = freqsClusters;
        }
        return sFreqsClusters;
    }

    @Nullable
    private static native int[] getFreqsClustersInternal();

    /** Returns the number of clusters (policies). */
    public static int getClusters() {
        int[] freqClusters = getFreqsClusters();
        return freqClusters.length > 0 ? freqClusters[freqClusters.length - 1] + 1 : 0;
    }
}
