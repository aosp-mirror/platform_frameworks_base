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

import org.junit.Test;

import android.support.test.filters.SmallTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@SmallTest
public class PackageVersionsTest {

    @Test
    public void equals() {
        PackageVersions baseline =
                new PackageVersions(1 /* updateAppVersion */, 1 /* dataAppVersion */);
        assertEquals(baseline, baseline);

        PackageVersions deepEqual =
                new PackageVersions(1 /* updateAppVersion */, 1 /* dataAppVersion */);
        assertEquals(baseline, deepEqual);

        PackageVersions differentUpdateAppVersion =
                new PackageVersions(2 /* updateAppVersion */, 1 /* dataAppVersion */);
        assertFalse(baseline.equals(differentUpdateAppVersion));

        PackageVersions differentDataAppVersion =
                new PackageVersions(1 /* updateAppVersion */, 2 /* dataAppVersion */);
        assertFalse(baseline.equals(differentDataAppVersion));
    }
}
