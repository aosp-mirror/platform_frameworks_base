/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.util.SparseArray;

import libcore.util.EmptyArray;

import java.util.Arrays;

/**
 * CPU scaling policies: the policy IDs and corresponding supported scaling for those
 * policies.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class CpuScalingPolicies {
    private final SparseArray<int[]> mCpusByPolicy;
    private final SparseArray<int[]> mFreqsByPolicy;
    private final int[] mPolicies;
    private final int mScalingStepCount;

    public CpuScalingPolicies(@NonNull SparseArray<int[]> cpusByPolicy,
            @NonNull SparseArray<int[]> freqsByPolicy) {
        mCpusByPolicy = cpusByPolicy;
        mFreqsByPolicy = freqsByPolicy;

        mPolicies = new int[cpusByPolicy.size()];
        for (int i = 0; i < mPolicies.length; i++) {
            mPolicies[i] = cpusByPolicy.keyAt(i);
        }

        Arrays.sort(mPolicies);

        int count = 0;
        for (int i = freqsByPolicy.size() - 1; i >= 0; i--) {
            count += freqsByPolicy.valueAt(i).length;
        }
        mScalingStepCount = count;
    }

    /**
     * Returns available policies (aka clusters).
     */
    @NonNull
    public int[] getPolicies() {
        return mPolicies;
    }

    /**
     * CPUs covered by the specified policy.
     */
    @NonNull
    public int[] getRelatedCpus(int policy) {
        return mCpusByPolicy.get(policy, EmptyArray.INT);
    }

    /**
     * Scaling frequencies supported for the specified policy.
     */
    @NonNull
    public int[] getFrequencies(int policy) {
        return mFreqsByPolicy.get(policy, EmptyArray.INT);
    }

    /**
     * Returns the overall number of supported scaling steps: grand total of available frequencies
     * across all scaling policies.
     */
    public int getScalingStepCount() {
        return mScalingStepCount;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int policy : mPolicies) {
            sb.append("policy").append(policy)
                    .append("\n CPUs: ").append(Arrays.toString(mCpusByPolicy.get(policy)))
                    .append("\n freqs: ").append(Arrays.toString(mFreqsByPolicy.get(policy)))
                    .append("\n");
        }
        return sb.toString();
    }
}
