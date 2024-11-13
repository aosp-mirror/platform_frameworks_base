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

import static com.google.common.truth.Truth.assertThat;

import android.os.FileUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class CpuScalingPolicyReaderTest {
    private CpuScalingPolicyReader mCpuScalingPolicyReader;

    @Before
    public void setup() throws IOException {
        File testDir = Files.createTempDirectory("CpuScalingPolicyReaderTest").toFile();
        FileUtils.deleteContents(testDir);

        File policy0 = new File(testDir, "policy0");
        FileUtils.createDir(policy0);
        FileUtils.stringToFile(new File(policy0, "related_cpus"), "0 2 7");
        FileUtils.stringToFile(new File(policy0, "scaling_available_frequencies"), "1234 9876");

        File policy5 = new File(testDir, "policy5");
        FileUtils.createDir(policy5);
        FileUtils.stringToFile(new File(policy5, "related_cpus"), "3 6\n");
        FileUtils.stringToFile(new File(policy5, "scaling_available_frequencies"), "1234 5678\n");
        FileUtils.stringToFile(new File(policy5, "scaling_boost_frequencies"), "9998 9999\n");

        File policy7 = new File(testDir, "policy7");
        FileUtils.createDir(policy7);
        FileUtils.stringToFile(new File(policy7, "related_cpus"), "8\n");
        FileUtils.stringToFile(new File(policy7, "cpuinfo_cur_freq"), "1000000");

        File policy9 = new File(testDir, "policy9");
        FileUtils.createDir(policy9);
        FileUtils.stringToFile(new File(policy9, "related_cpus"), "42");

        File policy999 = new File(testDir, "policy999");
        FileUtils.createDir(policy999);

        mCpuScalingPolicyReader = new CpuScalingPolicyReader(testDir.getPath());
    }

    @Test
    public void readFromSysFs() {
        CpuScalingPolicies info = mCpuScalingPolicyReader.read();
        assertThat(info.getPolicies()).isEqualTo(new int[]{0, 5, 7, 9});
        assertThat(info.getRelatedCpus(0)).isEqualTo(new int[]{0, 2, 7});
        assertThat(info.getFrequencies(0)).isEqualTo(new int[]{1234, 9876});
        assertThat(info.getRelatedCpus(5)).isEqualTo(new int[]{3, 6});
        assertThat(info.getFrequencies(5)).isEqualTo(new int[]{1234, 5678, 9998, 9999});
        assertThat(info.getRelatedCpus(7)).isEqualTo(new int[]{8});
        assertThat(info.getFrequencies(7)).isEqualTo(new int[]{1000000});
        assertThat(info.getRelatedCpus(9)).isEqualTo(new int[]{42});
        assertThat(info.getFrequencies(9)).isEqualTo(new int[]{0});     // Unknown
        assertThat(info.getScalingStepCount()).isEqualTo(8);
    }
}
