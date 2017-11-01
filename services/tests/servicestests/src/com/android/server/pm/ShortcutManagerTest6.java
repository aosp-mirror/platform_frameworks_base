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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.List;

/**
 * Tests for {@link ShortcutService#hasShortcutHostPermissionInner}, which includes
 * {@link ShortcutService#getDefaultLauncher}.
 */
@SmallTest
public class ShortcutManagerTest6 extends BaseShortcutManagerTest {
    public void testHasShortcutHostPermissionInner_systemLauncherOnly() {
        // Preferred isn't set, use the system launcher.
        prepareGetHomeActivitiesAsUser(
                /* preferred */ null,
                list(getSystemLauncher(), getFallbackLauncher()),
                USER_0);
        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Should be cached.
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        // Also make sure the last known is saved, but the cached is not.

        initService();

        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(null,
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());
    }

    public void testHasShortcutHostPermissionInner_with3pLauncher() {
        // Preferred isn't set, still use the system launcher.
        prepareGetHomeActivitiesAsUser(
                /* preferred */ null,
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_0);
        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Should be cached.
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());
    }

    public void testHasShortcutHostPermissionInner_with3pLauncher_complicated() {
        // Preferred is set.  That's the default launcher.
        prepareGetHomeActivitiesAsUser(
                /* preferred */ cn(CALLING_PACKAGE_2, "name"),
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_0);
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Should be cached.
        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());


        // Once set, even after the preferred launcher is cleared, SM still allows it to access
        // shortcuts.
        prepareGetHomeActivitiesAsUser(
                /* preferred */ null,
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_0);

        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Should be cached.
        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        // However, if the component has been disabled, then we'll recalculate it.
        mEnabledActivityChecker = (comp, user) -> false;

        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        mEnabledActivityChecker = (comp, user) -> true;

        // Now the preferred changed.
        prepareGetHomeActivitiesAsUser(
                /* preferred */ cn(CALLING_PACKAGE_1, "xyz"),
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_0);

        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));

        // Should be cached.
        assertEquals(cn(CALLING_PACKAGE_1, "xyz"),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(CALLING_PACKAGE_1, "xyz"),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());


        // As long as there's the cached launcher set, even if getHomeActivitiesAsUser()
        // returns different values, the cached one is still the default.
        prepareGetHomeActivitiesAsUser(
                /* preferred */ getSystemLauncher().activityInfo.getComponentName(),
                list(getSystemLauncher(), getFallbackLauncher()),
                USER_0);

        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));

        // Cached ones haven't changed.
        assertEquals(cn(CALLING_PACKAGE_1, "xyz"),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(CALLING_PACKAGE_1, "xyz"),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        // However, now the "real" default launcher is the system one.  So if the system
        // launcher asks for shortcuts, we'll allow it.
        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));

        // Since the cache is updated, CALLING_PACKAGE_1 no longer has the permission.
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));

        // Cached ones haven't changed.
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());
    }

    public void testHasShortcutHostPermissionInner_multiUser() {
        mRunningUsers.put(USER_10, true);

        prepareGetHomeActivitiesAsUser(
                /* preferred */ null,
                list(getSystemLauncher(), getFallbackLauncher()),
                USER_0);

        prepareGetHomeActivitiesAsUser(
                /* preferred */ cn(CALLING_PACKAGE_2, "name"),
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_10);

        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_0));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_0));

        // Check the cache.
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getLastKnownLauncher());
        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_10));
        assertFalse(mService.hasShortcutHostPermissionInner(PACKAGE_FALLBACK_LAUNCHER, USER_10));
        assertFalse(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_1, USER_10));
        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_10));

        // Check the cache.
        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_10).getLastKnownLauncher());
        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_10).getCachedLauncher());
    }

    public void testHasShortcutHostPermissionInner_clearCache() {
        mRunningUsers.put(USER_10, true);

        prepareGetHomeActivitiesAsUser(
                /* preferred */ null,
                list(getSystemLauncher(), getFallbackLauncher()),
                USER_0);

        prepareGetHomeActivitiesAsUser(
                /* preferred */ cn(CALLING_PACKAGE_2, "name"),
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_10);

        assertTrue(mService.hasShortcutHostPermissionInner(PACKAGE_SYSTEM_LAUNCHER, USER_0));
        assertTrue(mService.hasShortcutHostPermissionInner(CALLING_PACKAGE_2, USER_10));

        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_10).getCachedLauncher());

        // Test it on a non-running user.
        // Send ACTION_PREFERRED_ACTIVITY_CHANGED on user 10.
        // But the user is not running, so will be ignored.
        mRunningUsers.put(USER_10, false);

        mService.mPackageMonitor.onReceive(mServiceContext,
                new Intent(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED).putExtra(
                        Intent.EXTRA_USER_HANDLE, USER_10));

        // Need to run the user again to access the internal status.
        mRunningUsers.put(USER_10, true);

        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        assertEquals(cn(CALLING_PACKAGE_2, "name"),
                mService.getUserShortcutsLocked(USER_10).getCachedLauncher());

         // Send it again after starting the user.
        mRunningUsers.put(USER_10, true);
        mService.mPackageMonitor.onReceive(mServiceContext,
                new Intent(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED).putExtra(
                        Intent.EXTRA_USER_HANDLE, USER_10));

        assertEquals(cn(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME),
                mService.getUserShortcutsLocked(USER_0).getCachedLauncher());

        // Only user-10's cache is cleared.
        assertEquals(null,
                mService.getUserShortcutsLocked(USER_10).getCachedLauncher());

    }
}
