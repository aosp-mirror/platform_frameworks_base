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
import android.os.FileUtils;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import libcore.util.EmptyArray;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures a CPU scaling policies such as available scaling frequencies as well as
 * CPUs (cores) for each policy.
 *
 * See <a
 * href="https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html
 * #policy-interface-in-sysfs">Policy Interface in sysfs</a>
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class CpuScalingPolicyReader {
    private static final String TAG = "CpuScalingPolicyReader";
    private static final String CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq";
    private static final Pattern POLICY_PATTERN = Pattern.compile("policy(\\d+)");
    private static final String FILE_NAME_RELATED_CPUS = "related_cpus";
    private static final String FILE_NAME_SCALING_AVAILABLE_FREQUENCIES =
            "scaling_available_frequencies";
    private static final String FILE_NAME_SCALING_BOOST_FREQUENCIES = "scaling_boost_frequencies";
    private static final String FILE_NAME_CPUINFO_CUR_FREQ = "cpuinfo_cur_freq";

    private final String mCpuFreqDir;

    public CpuScalingPolicyReader() {
        this(CPUFREQ_DIR);
    }

    @VisibleForTesting
    public CpuScalingPolicyReader(String cpuFreqDir) {
        mCpuFreqDir = cpuFreqDir;
    }

    /**
     * Reads scaling policy info from sysfs files in /sys/devices/system/cpu/cpufreq
     */
    @NonNull
    public CpuScalingPolicies read() {
        SparseArray<int[]> cpusByPolicy = new SparseArray<>();
        SparseArray<int[]> freqsByPolicy = new SparseArray<>();

        File cpuFreqDir = new File(mCpuFreqDir);
        File[] policyDirs = cpuFreqDir.listFiles();
        if (policyDirs != null) {
            for (File policyDir : policyDirs) {
                Matcher matcher = POLICY_PATTERN.matcher(policyDir.getName());
                if (matcher.matches()) {
                    int[] relatedCpus = readIntsFromFile(
                            new File(policyDir, FILE_NAME_RELATED_CPUS));
                    if (relatedCpus.length == 0) {
                        continue;
                    }

                    int[] availableFreqs = readIntsFromFile(
                            new File(policyDir, FILE_NAME_SCALING_AVAILABLE_FREQUENCIES));
                    int[] boostFreqs = readIntsFromFile(
                            new File(policyDir, FILE_NAME_SCALING_BOOST_FREQUENCIES));
                    int[] freqs;
                    if (boostFreqs.length == 0) {
                        freqs = availableFreqs;
                    } else {
                        freqs = Arrays.copyOf(availableFreqs,
                                availableFreqs.length + boostFreqs.length);
                        System.arraycopy(boostFreqs, 0, freqs, availableFreqs.length,
                                boostFreqs.length);
                    }
                    if (freqs.length == 0) {
                        freqs = readIntsFromFile(new File(policyDir, FILE_NAME_CPUINFO_CUR_FREQ));
                        if (freqs.length == 0) {
                            freqs = new int[]{0};  // Unknown frequency
                        }
                    }
                    int policy = Integer.parseInt(matcher.group(1));
                    cpusByPolicy.put(policy, relatedCpus);
                    freqsByPolicy.put(policy, freqs);
                }
            }
        }

        if (cpusByPolicy.size() == 0) {
            // There just has to be at least one CPU - otherwise, what's executing this code?
            cpusByPolicy.put(0, new int[]{0});
            freqsByPolicy.put(0, new int[]{0});
        }

        return new CpuScalingPolicies(cpusByPolicy, freqsByPolicy);
    }

    @NonNull
    private static int[] readIntsFromFile(File file) {
        if (!file.exists()) {
            return EmptyArray.INT;
        }

        IntArray intArray = new IntArray(16);
        try {
            String contents = FileUtils.readTextFile(file, 0, null).trim();
            String[] strings = contents.split(" ");
            intArray.clear();
            for (String s : strings) {
                if (s.isBlank()) {
                    continue;
                }
                try {
                    intArray.add(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Unexpected file format " + file
                            + ": " + contents, e);
                }
            }
            return intArray.toArray();
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read " + file, e);
            return EmptyArray.INT;
        }
    }
}
