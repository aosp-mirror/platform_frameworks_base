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

package android.content.pm;

import static com.google.common.truth.Truth.assertThat;

import static java.util.function.Function.identity;

import android.content.pm.PackagePartitions.SystemPartition;
import android.os.SystemProperties;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackagePartitionsTest {

    @Test
    public void testPackagePartitionsFingerprint() {
        final ArrayList<SystemPartition> partitions = PackagePartitions.getOrderedPartitions(
                identity());
        final String[] properties = new String[partitions.size() + 1];
        for (int i = 0; i < partitions.size(); i++) {
            final String name = partitions.get(i).getName();
            properties[i] = "ro." + name + ".build.fingerprint";
        }
        properties[partitions.size()] = "ro.build.fingerprint";

        assertThat(SystemProperties.digestOf(properties)).isEqualTo(PackagePartitions.FINGERPRINT);
    }
}
