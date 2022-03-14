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
package com.android.server.devicepolicy;

import static android.os.UserHandle.USER_NULL;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.testng.Assert.expectThrows;

import android.content.ComponentName;

import org.junit.Test;

/**
 * Run it as {@code atest FrameworksMockingServicesTests:OwnerShellDataTest}
 */
public final class OwnerShellDataTest {

    private static final int USER_ID = 007;
    private static final int PARENT_USER_ID = 'M' + 'I' + 6;
    private static final ComponentName ADMIN = new ComponentName("Bond", "James");

    @Test
    public void testForDeviceOwner_noAdmin() {
        expectThrows(NullPointerException.class,
                () -> OwnerShellData.forDeviceOwner(USER_ID, /* admin= */ null));
    }

    @Test
    public void testForDeviceOwner_invalidUser() {
        expectThrows(IllegalArgumentException.class,
                () -> OwnerShellData.forDeviceOwner(USER_NULL, ADMIN));
    }

    @Test
    public void testForDeviceOwner() {
        OwnerShellData dto = OwnerShellData.forDeviceOwner(USER_ID, ADMIN);

        assertWithMessage("dto(%s).userId", dto).that(dto.userId).isEqualTo(USER_ID);
        assertWithMessage("dto(%s).parentUserId", dto).that(dto.parentUserId)
                .isEqualTo(USER_NULL);
        assertWithMessage("dto(%s).admin", dto).that(dto.admin).isSameInstanceAs(ADMIN);
        assertWithMessage("dto(%s).isDeviceOwner", dto).that(dto.isDeviceOwner).isTrue();
        assertWithMessage("dto(%s).isProfileOwner", dto).that(dto.isProfileOwner).isFalse();
        assertWithMessage("dto(%s).isManagedProfileOwner", dto).that(dto.isManagedProfileOwner)
                .isFalse();
        assertWithMessage("dto(%s).isAffiliated", dto).that(dto.isAffiliated).isFalse();
    }

    @Test
    public void testForUserProfileOwner_noAdmin() {
        expectThrows(NullPointerException.class,
                () -> OwnerShellData.forUserProfileOwner(USER_ID, /* admin= */ null));
    }

    @Test
    public void testForUserProfileOwner_invalidUser() {
        expectThrows(IllegalArgumentException.class,
                () -> OwnerShellData.forUserProfileOwner(USER_NULL, ADMIN));
    }

    @Test
    public void testForUserProfileOwner() {
        OwnerShellData dto = OwnerShellData.forUserProfileOwner(USER_ID, ADMIN);

        assertWithMessage("dto(%s).userId", dto).that(dto.userId).isEqualTo(USER_ID);
        assertWithMessage("dto(%s).parentUserId", dto).that(dto.parentUserId)
                .isEqualTo(USER_NULL);
        assertWithMessage("dto(%s).admin", dto).that(dto.admin).isSameInstanceAs(ADMIN);
        assertWithMessage("dto(%s).isDeviceOwner", dto).that(dto.isDeviceOwner).isFalse();
        assertWithMessage("dto(%s).isProfileOwner", dto).that(dto.isProfileOwner).isTrue();
        assertWithMessage("dto(%s).isManagedProfileOwner", dto).that(dto.isManagedProfileOwner)
                .isFalse();
        assertWithMessage("dto(%s).isAffiliated", dto).that(dto.isAffiliated).isFalse();
    }

    @Test
    public void testForManagedProfileOwner_noAdmin() {
        expectThrows(NullPointerException.class,
                () -> OwnerShellData.forManagedProfileOwner(USER_ID, PARENT_USER_ID, null));
    }

    @Test
    public void testForManagedProfileOwner_invalidUser() {
        expectThrows(IllegalArgumentException.class,
                () -> OwnerShellData.forManagedProfileOwner(USER_NULL, PARENT_USER_ID, ADMIN));
    }

    @Test
    public void testForManagedProfileOwner_invalidParent() {
        expectThrows(IllegalArgumentException.class,
                () -> OwnerShellData.forManagedProfileOwner(USER_ID, USER_NULL, ADMIN));
    }

    @Test
    public void testForManagedProfileOwner_parentOfItself() {
        expectThrows(IllegalArgumentException.class,
                () -> OwnerShellData.forManagedProfileOwner(USER_ID, USER_ID, ADMIN));
    }

    @Test
    public void testForManagedProfileOwner() {
        OwnerShellData dto = OwnerShellData.forManagedProfileOwner(USER_ID, PARENT_USER_ID, ADMIN);

        assertWithMessage("dto(%s).userId", dto).that(dto.userId).isEqualTo(USER_ID);
        assertWithMessage("dto(%s).parentUserId", dto).that(dto.parentUserId)
                .isEqualTo(PARENT_USER_ID);
        assertWithMessage("dto(%s).admin", dto).that(dto.admin).isSameInstanceAs(ADMIN);
        assertWithMessage("dto(%s).isDeviceOwner", dto).that(dto.isDeviceOwner).isFalse();
        assertWithMessage("dto(%s).isProfileOwner", dto).that(dto.isProfileOwner).isFalse();
        assertWithMessage("dto(%s).isManagedProfileOwner", dto).that(dto.isManagedProfileOwner)
                .isTrue();
        assertWithMessage("dto(%s).isAffiliated", dto).that(dto.isAffiliated).isFalse();
    }
}
