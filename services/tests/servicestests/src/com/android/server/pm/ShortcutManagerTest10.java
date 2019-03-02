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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils
        .assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps.PinItemRequest;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Process;
import android.test.suitebuilder.annotation.SmallTest;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link ShortcutManager#createShortcutResultIntent(ShortcutInfo)} and relevant APIs.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest10 \
 -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class ShortcutManagerTest10 extends BaseShortcutManagerTest {

    private PinItemRequest mRequest;

    private PinItemRequest verifyAndGetCreateShortcutResult(Intent resultIntent) {
        PinItemRequest request = mLauncherApps.getPinItemRequest(resultIntent);
        assertNotNull(request);
        assertEquals(PinItemRequest.REQUEST_TYPE_SHORTCUT, request.getRequestType());
        return request;
    }

    public void testCreateShortcutResult_noDefaultLauncher() {
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            assertNull(mManager.createShortcutResultIntent(s1));
        });
    }

    public void testCreateShortcutResult_validResult() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            Intent intent = mManager.createShortcutResultIntent(s1);
            mRequest = verifyAndGetCreateShortcutResult(intent);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertTrue(mRequest.isValid());
            assertTrue(mRequest.accept());
        });
    }

    public void testCreateShortcutResult_alreadyPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            Intent intent = mManager.createShortcutResultIntent(s1);
            mRequest = verifyAndGetCreateShortcutResult(intent);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertTrue(mRequest.isValid());
            assertTrue(mRequest.getShortcutInfo().isPinned());
            assertTrue(mRequest.accept());
        });
    }

    public void testCreateShortcutResult_alreadyPinnedByAnother() {
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });

        // Initially all launchers have the shortcut permission, until we call setDefaultLauncher().
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });

        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            Intent intent = mManager.createShortcutResultIntent(s1);
            mRequest = verifyAndGetCreateShortcutResult(intent);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertTrue(mRequest.isValid());
            assertFalse(mRequest.getShortcutInfo().isPinned());
            assertTrue(mRequest.accept());
        });
    }

    public void testCreateShortcutResult_defaultLauncherChanges() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            Intent intent = mManager.createShortcutResultIntent(s1);
            mRequest = verifyAndGetCreateShortcutResult(intent);
        });

        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_2, USER_0));
        // Verify that other launcher can't use this request
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertFalse(mRequest.isValid());
            assertExpectException(SecurityException.class, "Calling uid mismatch",
                    mRequest::accept);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Set some random caller UID.
            mInjectedCallingUid = 12345;

            assertFalse(mRequest.isValid());
            assertExpectException(SecurityException.class, "Calling uid mismatch",
                    mRequest::accept);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertTrue(mRequest.isValid());
            assertTrue(mRequest.accept());
        });
    }

    private LauncherActivityInfo setupMockActivityInfo() {
        doReturn(getTestContext().getPackageName()).when(mServiceContext).getPackageName();
        doReturn(getTestContext().getContentResolver()).when(mServiceContext).getContentResolver();

        LauncherActivityInfo info = mock(LauncherActivityInfo.class);
        when(info.getComponentName()).thenReturn(
                new ComponentName(getTestContext(), "a.ShortcutConfigActivity"));
        when(info.getUser()).thenReturn(Process.myUserHandle());
        return info;
    }

    public void testStartConfigActivity_defaultLauncher() {
        LauncherActivityInfo info = setupMockActivityInfo();
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        runWithCaller(LAUNCHER_1, USER_0, () ->
            assertNotNull(mLauncherApps.getShortcutConfigActivityIntent(info))
        );
    }

    public void testStartConfigActivity_nonDefaultLauncher() {
        LauncherActivityInfo info = setupMockActivityInfo();
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        runWithCaller(LAUNCHER_2, USER_0, () ->
            assertExpectException(SecurityException.class, null, () ->
                    mLauncherApps.getShortcutConfigActivityIntent(info))
        );
    }
}
