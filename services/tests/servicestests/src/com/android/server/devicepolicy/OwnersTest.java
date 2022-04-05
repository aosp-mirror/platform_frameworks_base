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

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_DEFAULT;
import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.os.IpcDataCache;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.devicepolicy.DevicePolicyManagerServiceTestable.OwnersTestable;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the DeviceOwner object that saves & loads device and policy owner information.
 *
 * <p>Run this test with:
 *
 * {@code atest FrameworksServicesTests:com.android.server.devicepolicy.OwnersTest}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class OwnersTest extends DpmTestBase {

    private static final List<String> DEVICE_OWNER_PROTECTED_PACKAGES =
            Lists.newArrayList("package_1", "package_2");

    @Before
    public void setUp() throws Exception {
        // Disable caches in this test process. This must happen early, since some of the
        // following initialization steps invalidate caches.
        IpcDataCache.disableForTestMode();
    }

    @Test
    public void testUpgrade01() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test01/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertThat(owners.getLegacyConfigFile().exists()).isFalse();

            // File was empty, so no new files should be created.
            assertThat(owners.getDeviceOwnerFile().exists()).isFalse();

            assertThat(owners.getProfileOwnerFile(10).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(11).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(20).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(21).exists()).isFalse();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();
            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();

            owners.setDeviceOwnerType(owners.getDeviceOwnerPackageName(),
                    DEVICE_OWNER_TYPE_FINANCED, /* isAdminTestOnly= */ false);
            // There is no device owner, so the default owner type should be returned.
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();
            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }
    }

    @Test
    public void testUpgrade02() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test02/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertThat(owners.getLegacyConfigFile().exists()).isFalse();

            assertThat(owners.getDeviceOwnerFile().exists()).isTrue(); // TODO Check content

            assertThat(owners.getProfileOwnerFile(10).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(11).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(20).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(21).exists()).isFalse();

            assertThat(owners.hasDeviceOwner()).isTrue();
            assertThat(owners.getDeviceOwnerName()).isEqualTo(null);
            assertThat(owners.getDeviceOwnerPackageName()).isEqualTo("com.google.android.testdpc");
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isTrue();
            assertThat(owners.getDeviceOwnerName()).isEqualTo(null);
            assertThat(owners.getDeviceOwnerPackageName()).isEqualTo("com.google.android.testdpc");
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }
    }

    @Test
    public void testUpgrade03() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test03/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertThat(owners.getLegacyConfigFile().exists()).isFalse();

            assertThat(owners.getDeviceOwnerFile().exists()).isFalse();

            assertThat(owners.getProfileOwnerFile(10).exists()).isTrue();
            assertThat(owners.getProfileOwnerFile(11).exists()).isTrue();
            assertThat(owners.getProfileOwnerFile(20).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(21).exists()).isFalse();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getProfileOwnerKeys()).hasSize(2);
            assertThat(owners.getProfileOwnerComponent(10))
                    .isEqualTo(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"));
            assertThat(owners.getProfileOwnerName(10)).isEqualTo("0");
            assertThat(owners.getProfileOwnerPackage(10)).isEqualTo("com.google.android.testdpc");

            assertThat(owners.getProfileOwnerComponent(11))
                    .isEqualTo(new ComponentName("com.google.android.testdpc1", ""));
            assertThat(owners.getProfileOwnerName(11)).isEqualTo("1");
            assertThat(owners.getProfileOwnerPackage(11)).isEqualTo("com.google.android.testdpc1");

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getProfileOwnerKeys()).hasSize(2);
            assertThat(owners.getProfileOwnerComponent(10))
                    .isEqualTo(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"));
            assertThat(owners.getProfileOwnerName(10)).isEqualTo("0");
            assertThat(owners.getProfileOwnerPackage(10)).isEqualTo("com.google.android.testdpc");

            assertThat(owners.getProfileOwnerComponent(11))
                    .isEqualTo(new ComponentName("com.google.android.testdpc1", ""));
            assertThat(owners.getProfileOwnerName(11)).isEqualTo("1");
            assertThat(owners.getProfileOwnerPackage(11)).isEqualTo("com.google.android.testdpc1");

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }
    }

    /**
     * Note this also tests {@link Owners#setDeviceOwnerUserRestrictionsMigrated()}
     * and {@link  Owners#setProfileOwnerUserRestrictionsMigrated(int)}.
     */
    @Test
    public void testUpgrade04() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test04/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertThat(owners.getLegacyConfigFile().exists()).isFalse();

            assertThat(owners.getDeviceOwnerFile().exists()).isTrue();

            assertThat(owners.getProfileOwnerFile(10).exists()).isTrue();
            assertThat(owners.getProfileOwnerFile(11).exists()).isTrue();
            assertThat(owners.getProfileOwnerFile(20).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(21).exists()).isFalse();

            assertThat(owners.hasDeviceOwner()).isTrue();
            assertThat(owners.getDeviceOwnerName()).isEqualTo(null);
            assertThat(owners.getDeviceOwnerPackageName()).isEqualTo("com.google.android.testdpc");
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getSystemUpdatePolicy()).isNotNull();
            assertThat(owners.getSystemUpdatePolicy().getPolicyType()).isEqualTo(5);

            assertThat(owners.getProfileOwnerKeys()).hasSize(2);
            assertThat(owners.getProfileOwnerComponent(10))
                    .isEqualTo(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"));
            assertThat(owners.getProfileOwnerName(10)).isEqualTo("0");
            assertThat(owners.getProfileOwnerPackage(10)).isEqualTo("com.google.android.testdpc");

            assertThat(owners.getProfileOwnerComponent(11))
                    .isEqualTo(new ComponentName("com.google.android.testdpc1", ""));
            assertThat(owners.getProfileOwnerName(11)).isEqualTo("1");
            assertThat(owners.getProfileOwnerPackage(11)).isEqualTo("com.google.android.testdpc1");

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isTrue();
            assertThat(owners.getDeviceOwnerName()).isEqualTo(null);
            assertThat(owners.getDeviceOwnerPackageName()).isEqualTo("com.google.android.testdpc");
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_SYSTEM);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getSystemUpdatePolicy()).isNotNull();
            assertThat(owners.getSystemUpdatePolicy().getPolicyType()).isEqualTo(5);

            assertThat(owners.getProfileOwnerKeys()).hasSize(2);
            assertThat(owners.getProfileOwnerComponent(10))
                    .isEqualTo(new ComponentName("com.google.android.testdpc",
                            "com.google.android.testdpc.DeviceAdminReceiver0"));
            assertThat(owners.getProfileOwnerName(10)).isEqualTo("0");
            assertThat(owners.getProfileOwnerPackage(10)).isEqualTo("com.google.android.testdpc");

            assertThat(owners.getProfileOwnerComponent(11))
                    .isEqualTo(new ComponentName("com.google.android.testdpc1", ""));
            assertThat(owners.getProfileOwnerName(11)).isEqualTo("1");
            assertThat(owners.getProfileOwnerPackage(11)).isEqualTo("com.google.android.testdpc1");

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();

            owners.setDeviceOwnerUserRestrictionsMigrated();

            owners.setDeviceOwnerType(owners.getDeviceOwnerPackageName(),
                    DEVICE_OWNER_TYPE_FINANCED, /* isAdminTestOnly= */ false);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_FINANCED);

            owners.setDeviceOwnerProtectedPackages(
                    owners.getDeviceOwnerPackageName(), DEVICE_OWNER_PROTECTED_PACKAGES);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEqualTo(DEVICE_OWNER_PROTECTED_PACKAGES);
            verify(getServices().packageManagerInternal)
                    .setDeviceOwnerProtectedPackages(
                            owners.getDeviceOwnerPackageName(), DEVICE_OWNER_PROTECTED_PACKAGES);
        }

        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isTrue();
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_FINANCED);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEqualTo(DEVICE_OWNER_PROTECTED_PACKAGES);

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();

            owners.setProfileOwnerUserRestrictionsMigrated(11);

            owners.setDeviceOwnerType(owners.getDeviceOwnerPackageName(),
                    DEVICE_OWNER_TYPE_DEFAULT, /* isAdminTestOnly= */ false);
            // The previous device owner type should persist.
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_FINANCED);

            owners.setDeviceOwnerProtectedPackages(
                    owners.getDeviceOwnerPackageName(), new ArrayList<>());
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();
            verify(getServices().packageManagerInternal)
                    .setDeviceOwnerProtectedPackages(
                            owners.getDeviceOwnerPackageName(), new ArrayList<>());
        }

        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isTrue();
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_FINANCED);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isTrue();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();

            owners.setProfileOwnerUserRestrictionsMigrated(11);
        }
    }

    @Test
    public void testUpgrade05() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test05/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertThat(owners.getLegacyConfigFile().exists()).isFalse();

            // Note device initializer is no longer supported.  No need to write the DO file.
            assertThat(owners.getDeviceOwnerFile().exists()).isFalse();

            assertThat(owners.getProfileOwnerFile(10).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(11).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(20).exists()).isFalse();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();


            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();


            assertThat(owners.getSystemUpdatePolicy()).isNull();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }
    }

    @Test
    public void testUpgrade06() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        // First, migrate.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());

            DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                    DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test06/input.xml"));

            owners.load();

            // The legacy file should be removed.
            assertThat(owners.getLegacyConfigFile().exists()).isFalse();

            assertThat(owners.getDeviceOwnerFile().exists()).isTrue();

            assertThat(owners.getProfileOwnerFile(10).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(11).exists()).isFalse();
            assertThat(owners.getProfileOwnerFile(20).exists()).isFalse();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getSystemUpdatePolicy()).isNotNull();
            assertThat(owners.getSystemUpdatePolicy().getPolicyType()).isEqualTo(5);

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }

        // Then re-read and check.
        {
            final OwnersTestable owners = new OwnersTestable(getServices());
            owners.load();

            assertThat(owners.hasDeviceOwner()).isFalse();
            assertThat(owners.getDeviceOwnerUserId()).isEqualTo(UserHandle.USER_NULL);
            assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                    DEVICE_OWNER_TYPE_DEFAULT);
            assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                    .isEmpty();
            assertThat(owners.getProfileOwnerKeys()).isEmpty();

            assertThat(owners.getSystemUpdatePolicy()).isNotNull();
            assertThat(owners.getSystemUpdatePolicy().getPolicyType()).isEqualTo(5);

            assertThat(owners.getDeviceOwnerUserRestrictionsNeedsMigration()).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(10)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(11)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(20)).isFalse();
            assertThat(owners.getProfileOwnerUserRestrictionsNeedsMigration(21)).isFalse();
        }
    }

    @Test
    public void testRemoveExistingFiles() throws Exception {
        getServices().addUsers(10, 11, 20, 21);

        final OwnersTestable owners = new OwnersTestable(getServices());

        // First, migrate to create new-style config files.
        DpmTestUtils.writeToFile(owners.getLegacyConfigFile(),
                DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/test04/input.xml"));

        owners.load();

        assertThat(owners.getLegacyConfigFile().exists()).isFalse();

        assertThat(owners.getDeviceOwnerFile().exists()).isTrue();
        assertThat(owners.getDeviceOwnerType(owners.getDeviceOwnerPackageName())).isEqualTo(
                DEVICE_OWNER_TYPE_DEFAULT);
        assertThat(owners.getDeviceOwnerProtectedPackages(owners.getDeviceOwnerPackageName()))
                .isEmpty();
        assertThat(owners.getProfileOwnerFile(10).exists()).isTrue();
        assertThat(owners.getProfileOwnerFile(11).exists()).isTrue();

        String previousDeviceOwnerPackageName = owners.getDeviceOwnerPackageName();
        owners.setDeviceOwnerType(previousDeviceOwnerPackageName, DEVICE_OWNER_TYPE_FINANCED,
                /* isAdminTestOnly= */ false);
        assertThat(owners.getDeviceOwnerType(previousDeviceOwnerPackageName)).isEqualTo(
                DEVICE_OWNER_TYPE_FINANCED);
        owners.setDeviceOwnerProtectedPackages(
                previousDeviceOwnerPackageName, DEVICE_OWNER_PROTECTED_PACKAGES);
        assertThat(owners.getDeviceOwnerProtectedPackages(previousDeviceOwnerPackageName))
                .isEqualTo(DEVICE_OWNER_PROTECTED_PACKAGES);
        verify(getServices().packageManagerInternal)
                .setDeviceOwnerProtectedPackages(
                        owners.getDeviceOwnerPackageName(), DEVICE_OWNER_PROTECTED_PACKAGES);

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
        assertThat(owners.getDeviceOwnerFile().exists()).isFalse();
        assertThat(owners.getProfileOwnerFile(10).exists()).isFalse();
        assertThat(owners.getProfileOwnerFile(11).exists()).isFalse();

        assertThat(owners.getDeviceOwnerType(previousDeviceOwnerPackageName)).isEqualTo(
                DEVICE_OWNER_TYPE_DEFAULT);
        assertThat(owners.getDeviceOwnerProtectedPackages(previousDeviceOwnerPackageName))
                .isEmpty();
        verify(getServices().packageManagerInternal)
                .setDeviceOwnerProtectedPackages(
                        previousDeviceOwnerPackageName, new ArrayList<>());
    }
}
