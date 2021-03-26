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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import android.app.PendingIntent;
import android.app.appsearch.PackageIdentifier;
import android.content.pm.AppSearchShortcutInfo;
import android.os.RemoteException;
import android.os.UserHandle;

import java.util.Random;

/**
 * Tests for {@link android.app.appsearch.AppSearchManager} and relevant APIs in ShortcutManager.
 *
 atest -c com.android.server.pm.ShortcutManagerTest12
 */
public class ShortcutManagerTest12 extends BaseShortcutManagerTest {

    public void testUpdateShortcutVisibility_updatesShortcutSchema() {

        final byte[] cert = new byte[20];
        new Random().nextBytes(cert);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.updateShortcutVisibility(CALLING_PACKAGE_2, cert, true);
            assertTrue(mMockAppSearchManager.mSchemasPackageAccessible.containsKey(
                    AppSearchShortcutInfo.SCHEMA_TYPE));
            assertTrue(mMockAppSearchManager.mSchemasPackageAccessible.get(
                    AppSearchShortcutInfo.SCHEMA_TYPE).get(0).equals(
                            new PackageIdentifier(CALLING_PACKAGE_2, cert)));
        });
    }

    public void testGetShortcutIntents_ReturnsMutablePendingIntents() throws RemoteException {
        setDefaultLauncher(USER_0, LAUNCHER_1);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () ->
                assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))))
        );

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            final PendingIntent intent = mLauncherApps.getShortcutIntent(
                    CALLING_PACKAGE_1, "s1", null, UserHandle.SYSTEM);
            assertNotNull(intent);
        });
    }
}
