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

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

@SmallTest
public class CheckTokenTest {

    @Test
    public void toByteArray() throws Exception {
        PackageVersions packageVersions =
                new PackageVersions(1 /* updateAppVersion */, 1 /* dataAppVersion */);
        CheckToken originalToken = new CheckToken(1 /* optimisticLockId */, packageVersions);
        assertEquals(originalToken, CheckToken.fromByteArray(originalToken.toByteArray()));
    }

    @Test
    public void fromByteArray() {
        PackageVersions packageVersions =
                new PackageVersions(1 /* updateAppVersion */, 1 /* dataAppVersion */);
        CheckToken token = new CheckToken(1, packageVersions);
        byte[] validTokenBytes = token.toByteArray();
        byte[] shortTokenBytes = new byte[validTokenBytes.length - 1];
        System.arraycopy(validTokenBytes, 0, shortTokenBytes, 0, shortTokenBytes.length);

        try {
            CheckToken.fromByteArray(shortTokenBytes);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void equals() {
        PackageVersions packageVersions1 =
                new PackageVersions(1 /* updateAppVersion */, 1 /* dataAppVersion */);
        PackageVersions packageVersions2 =
                new PackageVersions(2 /* updateAppVersion */, 2 /* dataAppVersion */);
        assertFalse(packageVersions1.equals(packageVersions2));

        CheckToken baseline = new CheckToken(1, packageVersions1);
        assertEquals(baseline, baseline);

        CheckToken deepEqual = new CheckToken(1, packageVersions1);
        assertEquals(baseline, deepEqual);

        CheckToken differentOptimisticLockId = new CheckToken(2, packageVersions1);
        assertFalse(differentOptimisticLockId.equals(baseline));

        CheckToken differentPackageVersions = new CheckToken(1, packageVersions2);
        assertFalse(differentPackageVersions.equals(baseline));
    }
}
