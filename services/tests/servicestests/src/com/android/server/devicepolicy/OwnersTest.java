/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.devicepolicy;

import com.android.server.devicepolicy.DevicePolicyManagerServiceTestable.OwnersTestable;

import android.content.ComponentName;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for the DeviceOwner object that saves & loads device and policy owner information.
 * run this test with:
 m FrameworksServicesTests &&
 adb install \
   -r out/target/product/hammerhead/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.devicepolicy.OwnersTest \
   -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 (mmma frameworks/base/services/tests/servicestests/ for non-ninja build)
 */
@SmallTest
public class OwnersTest extends DpmTestBase {
    public void testUpgrade01() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test01/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFile().exists());

            // File was empty, so no new files should be created.
            assertFalse(owners.getDeviceOwnerFile().exists());

            assertFalse(owners.getProfileOwnerFile(10).exists());
            assertFalse(owners.getProfileOwnerFile(11).exists());
            assertFalse(owners.getProfileOwnerFile(20).exists());
            assertFalse(owners.getProfileOwnerFile(21).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }
    }

    public void testUpgrade02() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test02/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFile().exists());

            assertTrue(owners.getDeviceOwnerFile().exists()); // TODO Check content

            assertFalse(owners.getProfileOwnerFile(10).exists());
            assertFalse(owners.getProfileOwnerFile(11).exists());
            assertFalse(owners.getProfileOwnerFile(20).exists());
            assertFalse(owners.getProfileOwnerFile(21).exists());

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertTrue(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertTrue(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }
    }

    public void testUpgrade03() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test03/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFile().exists());

            assertFalse(owners.getDeviceOwnerFile().exists());

            assertTrue(owners.getProfileOwnerFile(10).exists());
            assertTrue(owners.getProfileOwnerFile(11).exists());
            assertFalse(owners.getProfileOwnerFile(20).exists());
            assertFalse(owners.getProfileOwnerFile(21).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertNull(owners.getSystemUpdatePolicy());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertNull(owners.getSystemUpdatePolicy());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }
    }

    /**
     * Note this also tests {@link Owners#setDeviceOwnerUserRestrictionsMigrated()}
     * and {@link  Owners#setProfileOwnerUserRestrictionsMigrated(int)}.
     */
    public void testUpgrade04() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test04/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFile().exists());

            assertTrue(owners.getDeviceOwnerFile().exists());

            assertTrue(owners.getProfileOwnerFile(10).exists());
            assertTrue(owners.getProfileOwnerFile(11).exists());
            assertFalse(owners.getProfileOwnerFile(20).exists());
            assertFalse(owners.getProfileOwnerFile(21).exists());

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));

            assertTrue(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertTrue(owners.hasDeviceOwner());
            assertEquals(null, owners.getDeviceOwnerName());
            assertEquals("com.google.android.testdpc", owners.getDeviceOwnerPackageName());
            assertEquals(UserHandle.USER_SYSTEM, owners.getDeviceOwnerUserId());

            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());

            assertEquals(2, owners.getProfileOwnerKeys().size());
            assertEquals(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"),
                    owners.getProfileOwnerComponent(10));
            assertEquals("0", owners.getProfileOwnerName(10));
            assertEquals("com.google.android.testdpc", owners.getProfileOwnerPackage(10));

            assertEquals(new ComponentName("com.google.android.testdpc1", ""),
                    owners.getProfileOwnerComponent(11));
            assertEquals("1", owners.getProfileOwnerName(11));
            assertEquals("com.google.android.testdpc1", owners.getProfileOwnerPackage(11));

            assertTrue(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));

            owners.setDeviceOwnerUserRestrictionsMigrated();
        }

        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));

            owners.setProfileOwnerUserRestrictionsMigrated(11);
        }

        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertTrue(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));

            owners.setProfileOwnerUserRestrictionsMigrated(11);
        }
    }

    public void testUpgrade05() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test05/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFile().exists());

            // Note device initializer is no longer supported.  No need to write the DO file.
            assertFalse(owners.getDeviceOwnerFile().exists());

            assertFalse(owners.getProfileOwnerFile(10).exists());
            assertFalse(owners.getProfileOwnerFile(11).exists());
            assertFalse(owners.getProfileOwnerFile(20).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());


            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());


            assertNull(owners.getSystemUpdatePolicy());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }
    }

    public void testUpgrade06() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test06/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertFalse(owners.getLegacyConfigFile().exists());

            assertTrue(owners.getDeviceOwnerFile().exists());

            assertFalse(owners.getProfileOwnerFile(10).exists());
            assertFalse(owners.getProfileOwnerFile(11).exists());
            assertFalse(owners.getProfileOwnerFile(20).exists());

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertFalse(owners.hasDeviceOwner());
            assertEquals(UserHandle.USER_NULL, owners.getDeviceOwnerUserId());
            assertEquals(0, owners.getProfileOwnerKeys().size());

            assertNotNull(owners.getSystemUpdatePolicy());
            assertEquals(5, owners.getSystemUpdatePolicy().getPolicyType());

            assertFalse(owners.getDeviceOwnerUserRestrictionsNeedsMigration());
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(10));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(11));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(20));
            assertFalse(owners.getProfileOwnerUserRestrictionsNeedsMigration(21));
        }
    }

    public void testRemoveExistingFiles() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        final OwnersTestable owners = new OwnersTestable(getServices());

        // First, migrate to create new-style config files.
        DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test04/input.xml"));

        owners.load();

        assertFalse(owners.getLegacyConfigFile().exists());

        assertTrue(owners.getDeviceOwnerFile().exists());
        assertTrue(owners.getProfileOwnerFile(10).exists());
        assertTrue(owners.getProfileOwnerFile(11).exists());

        // Then clear all information and save.
        owners.clearDeviceOwner();
        owners.clearSystemUpdatePolicy();
        owners.removeProfileOwner(10);
        owners.removeProfileOwner(11);

        owners.writeDeviceOwner();
        owners.writeProfileOwner(10);
        owners.writeProfileOwner(11);
        owners.writeProfileOwner(20);
        owners.writeProfileOwner(21);

        // Now all files should be removed.
        assertFalse(owners.getDeviceOwnerFile().exists());
        assertFalse(owners.getProfileOwnerFile(10).exists());
        assertFalse(owners.getProfileOwnerFile(11).exists());
    }
}
