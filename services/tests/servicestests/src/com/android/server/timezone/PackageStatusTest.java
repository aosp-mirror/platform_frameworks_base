/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class PackageStatusTest {

    @Test
    public void equals() {
        PackageVersions packageVersions1 =
                new PackageVersions(1 /* updateAppVersion */, 1 /* dataAppVersion */);
        PackageVersions packageVersions2 =
                new PackageVersions(2 /* updateAppVersion */, 1 /* dataAppVersion */);
        assertFalse(packageVersions1.equals(packageVersions2));

        PackageStatus baseline =
                new PackageStatus(PackageStatus.CHECK_STARTED, packageVersions1);
        assertEquals(baseline, baseline);

        PackageStatus deepEqual =
                new PackageStatus(PackageStatus.CHECK_STARTED, packageVersions1);
        assertEquals(baseline, deepEqual);

        PackageStatus differentStatus =
                new PackageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions1);
        assertFalse(differentStatus.equals(baseline));

        PackageStatus differentPackageVersions =
                new PackageStatus(PackageStatus.CHECK_STARTED, packageVersions2);
        assertFalse(differentPackageVersions.equals(baseline));
    }
}
