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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.PinItemRequest;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link ShortcutManager#requestPinShortcut} and relevant APIs.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest8 \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class ShortcutManagerTest8 extends BaseShortcutManagerTest {
    private ShortcutRequestPinProcessor mProcessor;

    @Override
    protected void initService() {
        super.initService();
        mProcessor = mService.getShortcutRequestPinProcessorForTest();
    }

    public void testGetParentOrSelfUserId() {
        assertEquals(USER_0, mService.getParentOrSelfUserId(USER_0));
        assertEquals(USER_10, mService.getParentOrSelfUserId(USER_10));
        assertEquals(USER_11, mService.getParentOrSelfUserId(USER_11));
        assertEquals(USER_0, mService.getParentOrSelfUserId(USER_P0));
    }

    public void testIsRequestPinShortcutSupported() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        setDefaultLauncher(USER_10, mMainActivityFetcher.apply(LAUNCHER_2, USER_10));

        Pair<ComponentName, Integer> actual;
        // User 0
        actual = mProcessor.getRequestPinShortcutConfirmationActivity(USER_0);

        assertEquals(LAUNCHER_1, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_0, (int) actual.second);

        // User 10
        actual = mProcessor.getRequestPinShortcutConfirmationActivity(USER_10);

        assertEquals(LAUNCHER_2, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_10, (int) actual.second);

        // User P0 -> managed profile, return user-0's launcher.
        actual = mProcessor.getRequestPinShortcutConfirmationActivity(USER_P0);

        assertEquals(LAUNCHER_1, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_0, (int) actual.second);

        // Check from the public API.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.isRequestPinShortcutSupported());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.isRequestPinShortcutSupported());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.isRequestPinShortcutSupported());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.isRequestPinShortcutSupported());
        });

        // Now, USER_0's launcher no longer has a confirm activity.
        mPinConfirmActivityFetcher = (packageName, userId) ->
                !LAUNCHER_2.equals(packageName)
                        ? null : new ComponentName(packageName, PIN_CONFIRM_ACTIVITY_CLASS);

        // User 10 -- still has confirm activity.
        actual = mProcessor.getRequestPinShortcutConfirmationActivity(USER_10);

        assertEquals(LAUNCHER_2, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_10, (int) actual.second);

        // But user-0 and user p0 no longer has a confirmation activity.
        assertNull(mProcessor.getRequestPinShortcutConfirmationActivity(USER_0));
        assertNull(mProcessor.getRequestPinShortcutConfirmationActivity(USER_P0));

        // Check from the public API.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertFalse(mManager.isRequestPinShortcutSupported());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertFalse(mManager.isRequestPinShortcutSupported());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.isRequestPinShortcutSupported());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertFalse(mManager.isRequestPinShortcutSupported());
        });
    }

    public void testRequestPinShortcut_notSupported() {
        // User-0's launcher has no confirmation activity.
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        mPinConfirmActivityFetcher = (packageName, userId) ->
                !LAUNCHER_2.equals(packageName)
                        ? null : new ComponentName(packageName, PIN_CONFIRM_ACTIVITY_CLASS);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");

            assertFalse(mManager.requestPinShortcut(s1,
                    /*PendingIntent=*/ null));

            verify(mServiceContext, times(0))
                    .startActivityAsUser(any(Intent.class), any(UserHandle.class));
            verify(mServiceContext, times(0))
                    .sendIntentSender(any(IntentSender.class));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");

            assertFalse(mManager.requestPinShortcut(s1,
                    /*PendingIntent=*/ null));

            verify(mServiceContext, times(0))
                    .startActivityAsUser(any(Intent.class), any(UserHandle.class));
            verify(mServiceContext, times(0))
                    .sendIntentSender(any(IntentSender.class));
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");

            assertFalse(mManager.requestPinShortcut(s1,
                    /*PendingIntent=*/ null));

            verify(mServiceContext, times(0))
                    .startActivityAsUser(any(Intent.class), any(UserHandle.class));
            verify(mServiceContext, times(0))
                    .sendIntentSender(any(IntentSender.class));
        });
    }

    private void assertPinItemRequestIntent(Intent actualIntent, String expectedPackage) {
        assertEquals(LauncherApps.ACTION_CONFIRM_PIN_ITEM, actualIntent.getAction());
        assertEquals(expectedPackage, actualIntent.getComponent().getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS,
                actualIntent.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK,
                actualIntent.getFlags());
    }

    private void assertPinItemRequest(PinItemRequest actualRequest) {
        assertNotNull(actualRequest);

        assertEquals(PinItemRequest.REQUEST_TYPE_SHORTCUT, actualRequest.getRequestType());
    }

    /**
     * Basic flow:
     * - Launcher supports the feature.
     * - Shortcut doesn't pre-exist.
     */
    private void checkRequestPinShortcut(@Nullable PendingIntent resultIntent) {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        setDefaultLauncher(USER_10, mMainActivityFetcher.apply(LAUNCHER_2, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");

            assertTrue(mManager.requestPinShortcut(s1,
                    resultIntent == null ? null : resultIntent.getIntentSender()));

            verify(mServiceContext, times(0))
                    .sendIntentSender(any(IntentSender.class));

            // Shortcut shouldn't be registered yet.
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Check the intent passed to startActivityAsUser().
            final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

            verify(mServiceContext).startActivityAsUser(intent.capture(), eq(HANDLE_USER_0));

            assertPinItemRequestIntent(intent.getValue(), mInjectedClientPackage);

            // Check the request object.
            final PinItemRequest request = mLauncherApps.getPinItemRequest(intent.getValue());

            assertPinItemRequest(request);

            assertWith(request.getShortcutInfo())
                    .haveIds("s1")
                    .areAllOrphan();

            // Can't test icons; need to test on CTS.

            // Accept the request.
            request.accept();
        });

        // Check from the launcher side, including callback

        // This method is always called, even with PI == null.
        if (resultIntent == null) {
            verify(mServiceContext, times(1)).sendIntentSender(eq(null));
        } else {
            verify(mServiceContext, times(1)).sendIntentSender(any(IntentSender.class));
        }

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllNotDynamic()
                    .areAllEnabled()
                    .areAllPinned();
        });
    }

    public void testRequestPinShortcut() {
        checkRequestPinShortcut(/* resultIntent=*/ null);
    }

    public void testRequestPinShortcut_withCallback() {
        final PendingIntent resultIntent =
                PendingIntent.getActivity(getTestContext(), 0, new Intent(), 0);

        checkRequestPinShortcut(resultIntent);
    }

    // TODO More tests:
    // Shortcut exists as a dynamic shortcut.
    // Shortcut exists as a manifest shortcut.
    // Shortcut exists as a dynamic, already pinned by this launcher
    // Shortcut exists as a manifest, already pinned by this launcher
    // Shortcut exists as floating, already pinned by this launcher

    // Shortcut exists as a dynamic, already pinned by another launcher
    // Shortcut exists as a manifest, already pinned by another launcher
    // Shortcut exists as floating, already pinned by another launcher

    // Shortcut exists but disabled (both mutable and immutable)

    // Shortcut exists but removed before accept().
    // Shortcut exists but disabled before accept().
    // Shortcut exists but pinned before accept().
    // Shortcut exists but unpinned before accept().

    // Cancel previous pending request and release memory?
}
