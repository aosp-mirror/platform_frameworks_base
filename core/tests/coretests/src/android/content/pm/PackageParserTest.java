/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.Build;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PackageParserTest {
    private static final String RELEASED = null;
    private static final String OLDER_PRE_RELEASE = "A";
    private static final String PRE_RELEASE = "B";
    private static final String NEWER_PRE_RELEASE = "C";

    private static final String[] CODENAMES_RELEASED = { /* empty */ };
    private static final String[] CODENAMES_PRE_RELEASE = { PRE_RELEASE };

    private static final int OLDER_VERSION = 10;
    private static final int PLATFORM_VERSION = 20;
    private static final int NEWER_VERSION = 30;

    private void verifyComputeMinSdkVersion(int minSdkVersion, String minSdkCodename,
            boolean isPlatformReleased, int expectedMinSdk) {
        final String[] outError = new String[1];
        final int result = PackageParser.computeMinSdkVersion(
                minSdkVersion,
                minSdkCodename,
                PLATFORM_VERSION,
                isPlatformReleased ? CODENAMES_RELEASED : CODENAMES_PRE_RELEASE,
                outError);

        assertEquals(result, expectedMinSdk);

        if (expectedMinSdk == -1) {
            assertNotNull(outError[0]);
        } else {
            assertNull(outError[0]);
        }
    }

    @Test
    public void testComputeMinSdkVersion_preReleasePlatform() {
        // Do allow older release minSdkVersion on pre-release platform.
        // APP: Released API 10
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, RELEASED, false, OLDER_VERSION);

        // Do allow same release minSdkVersion on pre-release platform.
        // APP: Released API 20
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, RELEASED, false, PLATFORM_VERSION);

        // Don't allow newer release minSdkVersion on pre-release platform.
        // APP: Released API 30
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, RELEASED, false, -1);

        // Don't allow older pre-release minSdkVersion on pre-release platform.
        // APP: Pre-release API 10
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, false, -1);

        // Do allow same pre-release minSdkVersion on pre-release platform,
        // but overwrite the specified version with CUR_DEVELOPMENT.
        // APP: Pre-release API 20
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT);

        // Don't allow newer pre-release minSdkVersion on pre-release platform.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false, -1);
    }

    @Test
    public void testComputeMinSdkVersion_releasedPlatform() {
        // Do allow older release minSdkVersion on released platform.
        // APP: Released API 10
        // DEV: Released API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, RELEASED, true, OLDER_VERSION);

        // Do allow same release minSdkVersion on released platform.
        // APP: Released API 20
        // DEV: Released API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, RELEASED, true, PLATFORM_VERSION);

        // Don't allow newer release minSdkVersion on released platform.
        // APP: Released API 30
        // DEV: Released API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, RELEASED, true, -1);

        // Don't allow older pre-release minSdkVersion on released platform.
        // APP: Pre-release API 10
        // DEV: Released API 20
        verifyComputeMinSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, true, -1);

        // Don't allow same pre-release minSdkVersion on released platform.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeMinSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true, -1);

        // Don't allow newer pre-release minSdkVersion on released platform.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeMinSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true, -1);
    }

    private void verifyComputeTargetSdkVersion(int targetSdkVersion, String targetSdkCodename,
            boolean isPlatformReleased, int expectedTargetSdk) {
        final String[] outError = new String[1];
        final int result = PackageParser.computeTargetSdkVersion(
                targetSdkVersion,
                targetSdkCodename,
                PLATFORM_VERSION,
                isPlatformReleased ? CODENAMES_RELEASED : CODENAMES_PRE_RELEASE,
                outError);

        assertEquals(result, expectedTargetSdk);

        if (expectedTargetSdk == -1) {
            assertNotNull(outError[0]);
        } else {
            assertNull(outError[0]);
        }
    }

    @Test
    public void testComputeTargetSdkVersion_preReleasePlatform() {
        // Do allow older release targetSdkVersion on pre-release platform.
        // APP: Released API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, RELEASED, false, OLDER_VERSION);

        // Do allow same release targetSdkVersion on pre-release platform.
        // APP: Released API 20
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, RELEASED, false, PLATFORM_VERSION);

        // Do allow newer release targetSdkVersion on pre-release platform.
        // APP: Released API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, RELEASED, false, NEWER_VERSION);

        // Don't allow older pre-release targetSdkVersion on pre-release platform.
        // APP: Pre-release API 10
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, false, -1);

        // Do allow same pre-release targetSdkVersion on pre-release platform,
        // but overwrite the specified version with CUR_DEVELOPMENT.
        // APP: Pre-release API 20
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, false,
                Build.VERSION_CODES.CUR_DEVELOPMENT);

        // Don't allow newer pre-release targetSdkVersion on pre-release platform.
        // APP: Pre-release API 30
        // DEV: Pre-release API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, false, -1);
    }

    @Test
    public void testComputeTargetSdkVersion_releasedPlatform() {
        // Do allow older release targetSdkVersion on released platform.
        // APP: Released API 10
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, RELEASED, true, OLDER_VERSION);

        // Do allow same release targetSdkVersion on released platform.
        // APP: Released API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, RELEASED, true, PLATFORM_VERSION);

        // Do allow newer release targetSdkVersion on released platform.
        // APP: Released API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, RELEASED, true, NEWER_VERSION);

        // Don't allow older pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 10
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(OLDER_VERSION, OLDER_PRE_RELEASE, true, -1);

        // Don't allow same pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 20
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(PLATFORM_VERSION, PRE_RELEASE, true, -1);

        // Don't allow newer pre-release targetSdkVersion on released platform.
        // APP: Pre-release API 30
        // DEV: Released API 20
        verifyComputeTargetSdkVersion(NEWER_VERSION, NEWER_PRE_RELEASE, true, -1);
    }

    /**
     * Unit test for PackageParser.getActivityConfigChanges().
     * If the bit is 1 in the original configChanges, it is still 1 in the final configChanges.
     * If the bit is 0 in the original configChanges and the bit is not set to 1 in
     * recreateOnConfigChanges, the bit is changed to 1 in the final configChanges by default.
     */
    @Test
    public void testGetActivityConfigChanges() {
        // Not set in either configChanges or recreateOnConfigChanges.
        int configChanges = 0x0000; // 00000000.
        int recreateOnConfigChanges = 0x0000; // 00000000.
        int finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should be 00000011.

        // Not set in configChanges, but set in recreateOnConfigChanges.
        configChanges = 0x0000; // 00000000.
        recreateOnConfigChanges = 0x0003; // 00000011.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0000, finalConfigChanges); // Should be 00000000.

        // Set in configChanges.
        configChanges = 0x0003; // 00000011.
        recreateOnConfigChanges = 0X0000; // 00000000.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should be 00000011.

        recreateOnConfigChanges = 0x0003; // 00000011.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0003, finalConfigChanges); // Should still be 00000011.

        // Other bit set in configChanges.
        configChanges = 0x0080; // 10000000, orientation.
        recreateOnConfigChanges = 0x0000; // 00000000.
        finalConfigChanges =
                PackageParser.getActivityConfigChanges(configChanges, recreateOnConfigChanges);
        assertEquals(0x0083, finalConfigChanges); // Should be 10000011.
    }
}
