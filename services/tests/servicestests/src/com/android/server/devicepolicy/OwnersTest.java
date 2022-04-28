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
import static android.app.admin.SystemUpdatePolicy.TYPE_INSTALL_WINDOWED;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.os.IpcDataCache;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.devicepolicy.DevicePolicyManagerServiceTestable.OwnersTestable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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

    private static final String TESTDPC_PACKAGE = "com.afwsamples.testdpc";

    @Before
    public void setUp() throws Exception {
        // Disable caches in this test process. This must happen early, since some of the
        // following initialization steps invalidate caches.
        IpcDataCache.disableForTestMode();
    }

    @Test
    public void loadProfileOwner() throws Exception {
        getServices().addUsers(10);

        final OwnersTestable owners = new OwnersTestable(getServices());

        DpmTestUtils.writeToFile(owners.getProfileOwnerFile(10),
                DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/profile_owner_1.xml"));

        owners.load();

        assertThat(owners.hasDeviceOwner()).isFalse();
        assertThat(owners.getSystemUpdatePolicy()).isNull();

        assertThat(owners.getProfileOwnerKeys()).hasSize(1);
        assertThat(owners.getProfileOwnerComponent(10))
                .isEqualTo(new ComponentName(TESTDPC_PACKAGE,
                        "com.afwsamples.testdpc.DeviceAdminReceiver"));
    }

    @Test
    public void loadDeviceOwner() throws Exception {
        final OwnersTestable owners = new OwnersTestable(getServices());

        DpmTestUtils.writeToFile(owners.getDeviceOwnerFile(),
                DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/device_owner_1.xml"));

        owners.load();

        assertThat(owners.hasDeviceOwner()).isTrue();

        assertThat(owners.getProfileOwnerKeys()).hasSize(0);
        assertThat(owners.getDeviceOwnerComponent())
                .isEqualTo(new ComponentName(TESTDPC_PACKAGE,
                        "com.afwsamples.testdpc.DeviceAdminReceiver"));

        assertThat(owners.getSystemUpdatePolicy().getPolicyType()).isEqualTo(TYPE_INSTALL_WINDOWED);
    }

    @Test
    public void testDeviceOwnerType() throws Exception {
        final OwnersTestable owners = new OwnersTestable(getServices());

        DpmTestUtils.writeToFile(owners.getDeviceOwnerFile(),
                DpmTestUtils.readAsset(mRealTestContext, "OwnersTest/device_owner_1.xml"));

        owners.load();

        assertThat(owners.getDeviceOwnerType(TESTDPC_PACKAGE))
                .isEqualTo(DEVICE_OWNER_TYPE_DEFAULT);

        // Should be able to set DO type to "financed".
        owners.setDeviceOwnerType(
                TESTDPC_PACKAGE, DEVICE_OWNER_TYPE_FINANCED, /* isAdminTestOnly= */ false);
        assertThat(owners.getDeviceOwnerType(TESTDPC_PACKAGE))
                .isEqualTo(DEVICE_OWNER_TYPE_FINANCED);

        // Once set, DO type cannot be changed.
        owners.setDeviceOwnerType(
                TESTDPC_PACKAGE, DEVICE_OWNER_TYPE_DEFAULT, /* isAdminTestOnly= */ false);
        assertThat(owners.getDeviceOwnerType(TESTDPC_PACKAGE))
                .isEqualTo(DEVICE_OWNER_TYPE_FINANCED);
    }
}
