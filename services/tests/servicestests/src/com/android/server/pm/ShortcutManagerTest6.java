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
package com.android.server.pm;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

/**
 * Tests for {@link ShortcutService#hasShortcutHostPermissionInner}, which includes
 * {@link ShortcutService#getDefaultLauncher}.
 */
@Presubmit
@SmallTest
public class ShortcutManagerTest6 extends BaseShortcutManagerTest {
    public void testHasShortcutHostPermissionInner_with3pLauncher_complicated() {
        // Set the default launcher.
        prepareGetRoleHoldersAsUser(CALLING_PACKAGE_2, USER_0);
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Last known launcher should be set.
        assertEquals(CALLING_PACKAGE_2,
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        // Now the default launcher has changed.
        prepareGetRoleHoldersAsUser(CALLING_PACKAGE_1, USER_0);

        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));

        // Last known launcher should be set.
        assertEquals(CALLING_PACKAGE_1,
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        // Change the default launcher again.
        prepareGetRoleHoldersAsUser(
                getSystemLauncher().activityInfo.getComponentName().getPackageName(), USER_0);

        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));

        // Last known launcher should be set to default.
        assertEquals(PACKAGE_SYSTEM_LAUNCHER,
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());
    }

    public void testHasShortcutHostPermissionInner_multiUser() {
        mRunningUsers.put(USER_10, true);

        prepareGetRoleHoldersAsUser(PACKAGE_FALLBACK_LAUNCHER, USER_0);
        prepareGetRoleHoldersAsUser(CALLING_PACKAGE_2, USER_10);

        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Last known launcher should be set.
        assertEquals(PACKAGE_FALLBACK_LAUNCHER,
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_10));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_10));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_10));
        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_10));

        // Last known launcher should be set.
        assertEquals(CALLING_PACKAGE_2,
                mService.getUserShortcutsLocked(USER_10).getCachedLauncher());
    }
}
