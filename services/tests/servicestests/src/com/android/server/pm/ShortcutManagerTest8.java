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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.anyOrNull;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertForLauncherCallbackNoThrow;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.reset;
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
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.Pair;

import com.android.frameworks.servicestests.R;

import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link ShortcutManager#requestPinShortcut} and relevant APIs.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest8 \
 -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner

 * TODO for CTS
 * - Foreground check.
 * - Reading icons from requested shortcuts.
 * - Invalid pre-approved token.
 */
@SmallTest
public class ShortcutManagerTest8 extends BaseShortcutManagerTest {
    private ShortcutRequestPinProcessor mProcessor;

    @Override
    protected void initService() {
        super.initService();
        mProcessor = mService.getShortcutRequestPinProcessorForTest();
    }

    @Override
    protected void setCaller(String packageName, int userId) {
        super.setCaller(packageName, userId);

        // Note during this test, assume all callers are in the foreground by default.
        makeCallerForeground();
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
        actual = mProcessor.getRequestPinConfirmationActivity(USER_0,
                PinItemRequest.REQUEST_TYPE_SHORTCUT);

        assertEquals(LAUNCHER_1, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_0, (int) actual.second);

        // User 10
        actual = mProcessor.getRequestPinConfirmationActivity(USER_10,
                PinItemRequest.REQUEST_TYPE_SHORTCUT);

        assertEquals(LAUNCHER_2, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_10, (int) actual.second);

        // User P0 -> managed profile, return user-0's launcher.
        actual = mProcessor.getRequestPinConfirmationActivity(USER_P0,
                PinItemRequest.REQUEST_TYPE_SHORTCUT);

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
        actual = mProcessor.getRequestPinConfirmationActivity(USER_10,
                PinItemRequest.REQUEST_TYPE_SHORTCUT);

        assertEquals(LAUNCHER_2, actual.first.getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS, actual.first.getClassName());
        assertEquals(USER_10, (int) actual.second);

        // But user-0 and user p0 no longer has a confirmation activity.
        assertNull(mProcessor.getRequestPinConfirmationActivity(USER_0,
                PinItemRequest.REQUEST_TYPE_SHORTCUT));
        assertNull(mProcessor.getRequestPinConfirmationActivity(USER_P0,
                PinItemRequest.REQUEST_TYPE_SHORTCUT));

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
        assertEquals(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT, actualIntent.getAction());
        assertEquals(expectedPackage, actualIntent.getComponent().getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS,
                actualIntent.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK,
                actualIntent.getFlags());
    }

    public void testNotForeground() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            makeCallerBackground();

            assertExpectException(IllegalStateException.class, "foreground activity", () -> {
                assertTrue(mManager.requestPinShortcut(makeShortcut("s1"),
                        /* resultIntent= */ null));
            });

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
            verify(mServiceContext, times(0)).startActivityAsUser(
                    any(Intent.class), any(UserHandle.class));
        });
    }

    private void assertPinItemRequest(PinItemRequest actualRequest) {
        assertNotNull(actualRequest);
        assertEquals(PinItemRequest.REQUEST_TYPE_SHORTCUT, actualRequest.getRequestType());

        Log.i(TAG, "Requested shortcut: " + actualRequest.getShortcutInfo().toInsecureString());
    }

    /**
     * Basic flow:
     * - Launcher supports the feature.
     * - Shortcut doesn't pre-exist.
     */
    private void checkRequestPinShortcut(@Nullable IntentSender resultIntent) {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        setDefaultLauncher(USER_10, mMainActivityFetcher.apply(LAUNCHER_2, USER_10));

        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            /// Create a shortcut with no target activity.
            final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, "s1")
                    .setShortLabel("Title-" + "s1")
                    .setIcon(res32x32)
                    .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class));
            final ShortcutInfo s = b.build();

            assertNull(s.getActivity());

            assertTrue(mManager.requestPinShortcut(s, resultIntent));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

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
                    .areAllOrphan()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, MAIN_ACTIVITY_CLASS))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            // Accept the request.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertTrue(request.accept()))
                    .assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_P0)
                    .haveIds("s1");
        });

        // This method is always called, even with PI == null.
        if (resultIntent == null) {
            verify(mServiceContext, times(1)).sendIntentSender(isNull(IntentSender.class));
        } else {
            verify(mServiceContext, times(1)).sendIntentSender(notNull(IntentSender.class));
        }

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllNotDynamic()
                    .areAllEnabled()
                    .areAllPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, MAIN_ACTIVITY_CLASS))
                    .areAllWithIntent();
        });
    }

    public void testRequestPinShortcut() {
        checkRequestPinShortcut(/* resultIntent=*/ null);
    }

    private IntentSender makeResultIntent() {
        return PendingIntent.getActivity(getTestContext(), 0, new Intent(), 0).getIntentSender();
    }

    public void testRequestPinShortcut_withCallback() {
        checkRequestPinShortcut(makeResultIntent());
    }

    public void testRequestPinShortcut_explicitTargetActivity() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        setDefaultLauncher(USER_10, mMainActivityFetcher.apply(LAUNCHER_2, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcutWithActivity("s1",
                    new ComponentName(CALLING_PACKAGE_1, "different_activity"));

            assertTrue(mManager.requestPinShortcut(s1, null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

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
                    .areAllOrphan()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "different_activity"))
                    .areAllWithNoIntent();

            // Accept the request.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertTrue(request.accept()))
                    .assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_P0)
                    .haveIds("s1");
        });

        verify(mServiceContext, times(1)).sendIntentSender(eq(null));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllNotDynamic()
                    .areAllEnabled()
                    .areAllPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "different_activity"))
                    .areAllWithIntent();
        });
    }

    public void testRequestPinShortcut_wrongTargetActivity() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            // Create dynamic shortcut
            ShortcutInfo s1 = makeShortcutWithActivity("s1",
                    new ComponentName("wrong_package", "different_activity"));

            assertExpectException(IllegalStateException.class, "not belong to package", () -> {
                assertTrue(mManager.requestPinShortcut(s1, /* resultIntent=*/ null));
            });

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
            verify(mServiceContext, times(0)).startActivityAsUser(
                    any(Intent.class), any(UserHandle.class));
        });
    }

    public void testRequestPinShortcut_noTargetActivity_noMainActivity() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        setDefaultLauncher(USER_10, mMainActivityFetcher.apply(LAUNCHER_2, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            /// Create a shortcut with no target activity.
            final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, "s1")
                    .setShortLabel("Title-" + "s1")
                    .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class));
            final ShortcutInfo s = b.build();

            assertNull(s.getActivity());

            // Caller has no main activity.
            mMainActivityFetcher = (packageName, userId) -> null;

            assertTrue(mManager.requestPinShortcut(s, null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

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
                    .areAllOrphan()
                    .areAllWithNoActivity() // Activity is not set; expected.
                    .areAllWithNoIntent();

            // Accept the request.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertTrue(request.accept()))
                    .assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_P0)
                    .haveIds("s1");
        });

        verify(mServiceContext, times(1)).sendIntentSender(eq(null));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllNotDynamic()
                    .areAllEnabled()
                    .areAllPinned()
                    .areAllWithNoActivity() // Activity is not set; expected.
                    .areAllWithIntent();
        });

    }

    public void testRequestPinShortcut_dynamicExists() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            // Create dynamic shortcut
            ShortcutInfo s1 = makeShortcutWithIcon("s1", res32x32);
            assertTrue(mManager.setDynamicShortcuts(list(s1)));

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllNotPinned();
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
                    .areAllDynamic()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            // Accept the request.
            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();
        });
    }

    public void testRequestPinShortcut_manifestExists() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("ms1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllNotPinned();
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
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            // Accept the request.
            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();
        });
    }

    public void testRequestPinShortcut_dynamicExists_alreadyPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, "s1")
                    .setShortLabel("Title-" + "s1")
                    .setIcon(res32x32)
                    .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class));
            final ShortcutInfo s = b.build();
            assertTrue(mManager.setDynamicShortcuts(list(s)));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "MainActivity"))
                    .areAllPinned();

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    makeResultIntent()));

            // The intent should be sent right away.
            verify(mServiceContext, times(1)).sendIntentSender(notNull(IntentSender.class));
        });

        // Already pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "MainActivity"))
                    .areAllPinned();
        });

        // ... But the launcher will still receive the request.
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
                    .areAllDynamic()
                    .areAllPinned() // Note it's pinned already.
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "MainActivity"))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            reset(mServiceContext);

            // Accept the request.
            assertTrue(request.accept());

            // The intent is only sent once, so times(1).
            verify(mServiceContext, times(1)).sendIntentSender(isNull(IntentSender.class));
        });

        // Still pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "MainActivity"))
                    .areAllPinned();
        });
    }

    public void testRequestPinShortcut_manifestExists_alreadyPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms1"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("ms1"),
                    makeResultIntent()));

            // The intent should be sent right away.
            verify(mServiceContext, times(1)).sendIntentSender(notNull(IntentSender.class));
        });

        // Already pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();
        });

        // ... But the launcher will still receive the request.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Check the intent passed to startActivityAsUser().
            final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

            verify(mServiceContext).startActivityAsUser(intent.capture(), eq(HANDLE_USER_0));

            assertPinItemRequestIntent(intent.getValue(), mInjectedClientPackage);

            // Check the request object.
            final PinItemRequest request = mLauncherApps.getPinItemRequest(intent.getValue());

            assertPinItemRequest(request);

            assertWith(request.getShortcutInfo())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllPinned() // Note it's pinned already.
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            reset(mServiceContext);

            // Accept the request.
            assertTrue(request.accept());

            // The intent is only sent once, so times(1).
            verify(mServiceContext, times(1)).sendIntentSender(isNull(IntentSender.class));
        });

        // Still pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();
        });
    }

    public void testRequestPinShortcut_wasDynamic_alreadyPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.removeAllDynamicShortcuts();
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllNotDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    /* resultIntent=*/ null));

            // The intent should be sent right away.
            verify(mServiceContext, times(1)).sendIntentSender(anyOrNull(IntentSender.class));
        });
    }

    public void testRequestPinShortcut_wasDynamic_disabled_alreadyPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.disableShortcuts(list("s1"));

            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllNotDynamic()
                    .areAllDisabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();

            assertExpectException(IllegalArgumentException.class, "exists but disabled", () -> {
                mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                        /* resultIntent=*/ null);
            });

            // Shouldn't be called.
            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
        });
    }

    public void testRequestPinShortcut_wasManifest_alreadyPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms1"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_0);

            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllNotManifest()
                    .areAllDisabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();

            assertExpectException(IllegalArgumentException.class, "exists but disabled", () -> {
                mManager.requestPinShortcut(makeShortcutIdOnly("ms1"),
                        /* resultIntent=*/ null);
            });

            // Shouldn't be called.
            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
        });
    }

    public void testRequestPinShortcut_dynamicExists_alreadyPinnedByAnother() {
        // Initially all launchers have the shortcut permission, until we call setDefaultLauncher().

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });

        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();

            // The shortcut is already pinned, but not by the current launcher, so it'll still
            // invoke the whole flow.
            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
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
                    .areAllDynamic()
                    .areAllNotPinned() // Note it's not pinned by this launcher.
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            // Accept the request.
            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();
        });
    }

    public void testRequestPinShortcut_manifestExists_alreadyPinnedByAnother() {
        // Initially all launchers have the shortcut permission, until we call setDefaultLauncher().

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);
        });

        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms1"), HANDLE_USER_P0);
        });

        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();

            // The shortcut is already pinned, but not by the current launcher, so it'll still
            // invoke the whole flow.
            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("ms1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
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
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllNotPinned() // Note it's not pinned by this launcher.
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();

            // Accept the request.
            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllPinned();
        });
    }

    /**
     * The launcher already has a pinned shortuct.  The new one should be added, not replace
     * the existing one.
     */
    public void testRequestPinShortcut_launcherAlreadyHasPinned() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"), makeShortcut("s2"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_P0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
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
                    .areAllDynamic()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            // Accept the request.
            assertTrue(request.accept());

            assertWith(getShortcutAsLauncher(USER_P0))
                    .haveIds("s1", "s2")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s2")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllPinned();
        });
    }

    /**
     * When trying to pin an existing shortcut, the new fields shouldn't override existing fields.
     */
    public void testRequestPinShortcut_dynamicExists_titleWontChange() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            // Create dynamic shortcut
            ShortcutInfo s1 = makeShortcutWithIcon("s1", res32x32);
            assertTrue(mManager.setDynamicShortcuts(list(s1)));

            assertTrue(mManager.requestPinShortcut(makeShortcutWithShortLabel("s1", "xxx"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllNotPinned();
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
                    .areAllDynamic()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            // Accept the request.
            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllDynamic()
                    .areAllEnabled()
                    .areAllPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .forShortcutWithId("s1", (si) -> {
                        // Still the original title.
                        assertEquals("Title-s1", si.getShortLabel());
                    });
        });
    }

    /**
     * When trying to pin an existing shortcut, the new fields shouldn't override existing fields.
     */
    public void testRequestPinShortcut_manifestExists_titleWontChange() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);

            assertTrue(mManager.requestPinShortcut(makeShortcutWithShortLabel("ms1", "xxx"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllNotPinned();
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
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();

            assertAllHaveIcon(list(request.getShortcutInfo()));

            // Accept the request.
            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()
                    .areAllPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .forShortcutWithId("ms1", (si) -> {
                        // Still the original title.
                        // Title should be something like:
                        // "string-com.android.test.1-user:20-res:2131034112/en"
                        MoreAsserts.assertContainsRegex("^string-", si.getShortLabel().toString());
                    });
        });
    }

    /**
     * The dynamic shortcut existed, but before accepting(), it's removed.  Because the request
     * has a partial shortcut, accept() should fail.
     */
    public void testRequestPinShortcut_dynamicExists_thenRemoved_error() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            // Create dynamic shortcut
            ShortcutInfo s1 = makeShortcut("s1");
            assertTrue(mManager.setDynamicShortcuts(list(s1)));

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            mManager.removeAllDynamicShortcuts();

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
                    .areAllDynamic()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            // Accept the request -> should fail.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertFalse(request.accept()))
                    .assertNoCallbackCalled();
        });

        // Intent shouldn't be sent.
        verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });
    }

    /**
     * The dynamic shortcut existed, but before accepting(), it's removed.  Because the request
     * has all the mandatory fields, we can go ahead and still publish it.
     */
    public void testRequestPinShortcut_dynamicExists_thenRemoved_okay() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            // Create dynamic shortcut
            ShortcutInfo s1 = makeShortcut("s1");
            assertTrue(mManager.setDynamicShortcuts(list(s1)));

            assertTrue(mManager.requestPinShortcut(makeShortcutWithShortLabel("s1", "new"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            mManager.removeAllDynamicShortcuts();

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
                    .areAllDynamic()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllFloating()
                    .forShortcutWithId("s1", si -> {
                        assertEquals("new", si.getShortLabel());
                    });
        });
    }

    /**
     * The manifest shortcut existed, but before accepting(), it's removed.  Because the request
     * has a partial shortcut, accept() should fail.
     */
    public void testRequestPinShortcut_manifestExists_thenRemoved_error() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("ms1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            publishManifestShortcutsAsCaller(R.xml.shortcut_0);

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
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();

            // Accept the request -> should fail.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertFalse(request.accept()))
                    .assertNoCallbackCalled();
        });

        // Intent shouldn't be sent.
        verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });
    }

    /**
     * The manifest shortcut existed, but before accepting(), it's removed.  Because the request
     * has all the mandatory fields, we can go ahead and still publish it.
     */
    public void testRequestPinShortcut_manifestExists_thenRemoved_okay() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);

            assertTrue(mManager.requestPinShortcut(makeShortcutWithShortLabel("ms1", "new"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

            publishManifestShortcutsAsCaller(R.xml.shortcut_0);

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
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();


            assertTrue(request.accept());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllMutable() // Note it's no longer immutable.
                    .areAllFloating()

                    // Note it's the activity from makeShortcutWithShortLabel().
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .forShortcutWithId("ms1", si -> {
                        assertEquals("new", si.getShortLabel());
                    });
        });
    }

    /**
     * The dynamic shortcut existed, but before accepting(), it's removed.  Because the request
     * has a partial shortcut, accept() should fail.
     */
    public void testRequestPinShortcut_dynamicExists_thenDisabled_error() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            assertTrue(mManager.setDynamicShortcuts(list(s1)));

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("s1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
        });

        // Then, pin by another launcher and disable it.
        // We have to pin it here so that disable() won't remove it.
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_2, USER_0));
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_P0);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.disableShortcuts(list("s1"));
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllDisabled();
        });

        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
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
                    .areAllDynamic()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllWithNoIntent();

            // Accept the request -> should fail.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertFalse(request.accept()))
                    .assertNoCallbackCalled();

            // Note s1 is floating and pinned by another launcher, so it shouldn't be
            // visible here.
            assertWith(getShortcutAsLauncher(USER_P0))
                    .isEmpty();
        });

        // Intent shouldn't be sent.
        verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1")
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1, "main"))
                    .areAllDisabled();
        });
    }

    /**
     * The manifest shortcut existed, but before accepting(), it's removed.  Because the request
     * has a partial shortcut, accept() should fail.
     */
    public void testRequestPinShortcut_manifestExists_thenDisabled_error() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_1);

            assertTrue(mManager.requestPinShortcut(makeShortcutIdOnly("ms1"),
                    /* resultIntent=*/ null));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
        });

        // Then, pin by another launcher and disable it.
        // We have to pin it here so that disable() won't remove it.
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_2, USER_0));
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms1"), HANDLE_USER_P0);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            publishManifestShortcutsAsCaller(R.xml.shortcut_0);
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllDisabled();
        });

        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Check the intent passed to startActivityAsUser().
            final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

            verify(mServiceContext).startActivityAsUser(intent.capture(), eq(HANDLE_USER_0));

            assertPinItemRequestIntent(intent.getValue(), mInjectedClientPackage);

            // Check the request object.
            final PinItemRequest request = mLauncherApps.getPinItemRequest(intent.getValue());

            assertPinItemRequest(request);

            assertWith(request.getShortcutInfo())
                    .haveIds("ms1")
                    .areAllManifest()
                    .areAllNotPinned()
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllWithNoIntent();

            // Accept the request -> should fail.
            assertForLauncherCallbackNoThrow(mLauncherApps,
                    () -> assertFalse(request.accept()))
                    .assertNoCallbackCalled();

            // Note ms1 is floating and pinned by another launcher, so it shouldn't be
            // visible here.
            assertWith(getShortcutAsLauncher(USER_P0))
                    .isEmpty();
        });

        // Intent shouldn't be sent.
        verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllWithActivity(new ComponentName(CALLING_PACKAGE_1,
                            ShortcutActivity.class.getName()))
                    .areAllDisabled();
        });
    }

    public void testRequestPinShortcut_wrongLauncherCannotAccept() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo s1 = makeShortcut("s1");
            assertTrue(mManager.requestPinShortcut(s1, null));
            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
        });

        final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(mServiceContext).startActivityAsUser(intent.capture(), eq(HANDLE_USER_0));
        final PinItemRequest request = mLauncherApps.getPinItemRequest(intent.getValue());

        // Verify that other launcher can't use this request
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Set some random caller UID.
            mInjectedCallingUid = 12345;

            assertFalse(request.isValid());
            assertExpectException(SecurityException.class, "Calling uid mismatch", request::accept);
        });

        // The default launcher can still use this request
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertTrue(request.isValid());
            assertTrue(request.accept());
        });
    }

    // TODO More tests:

    // Cancel previous pending request and release memory?

    // Check the launcher callback too.

    // Missing fields -- pre and post, both.
}
