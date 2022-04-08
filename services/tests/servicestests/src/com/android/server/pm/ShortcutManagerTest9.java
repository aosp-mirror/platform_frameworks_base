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
 * limitations under the License
 */

package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.PinItemRequest;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link android.content.pm.ShortcutServiceInternal#requestPinAppWidget}
 * and relevant APIs.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest9 \
 -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class ShortcutManagerTest9 extends BaseShortcutManagerTest {

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

    private AppWidgetProviderInfo makeProviderInfo(String className) {
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(CALLING_PACKAGE_3, className);
        return info;
    }

    private void assertPinItemRequestIntent(Intent actualIntent, String expectedPackage) {
        assertEquals(LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET, actualIntent.getAction());
        assertEquals(expectedPackage, actualIntent.getComponent().getPackageName());
        assertEquals(PIN_CONFIRM_ACTIVITY_CLASS,
                actualIntent.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK,
                actualIntent.getFlags());
    }

    private void assertPinItemRequest(PinItemRequest actualRequest, String className) {
        assertNotNull(actualRequest);
        assertEquals(PinItemRequest.REQUEST_TYPE_APPWIDGET, actualRequest.getRequestType());
        assertEquals(className, actualRequest.getAppWidgetProviderInfo(getTestContext())
                .provider.getClassName());
    }

    public void testNotForeground() {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            makeCallerBackground();

            assertExpectException(IllegalStateException.class, "foreground activity", () -> {
                mInternal.requestPinAppWidget(CALLING_PACKAGE_1, makeProviderInfo("dummy"),
                        null /* extras */, null /* resultIntent= */, USER_P0);
            });

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
            verify(mServiceContext, times(0)).startActivityAsUser(
                    any(Intent.class), any(UserHandle.class));
        });
    }

    private void checkRequestPinAppWidget(@Nullable PendingIntent resultIntent) {
        setDefaultLauncher(USER_0, mMainActivityFetcher.apply(LAUNCHER_1, USER_0));
        setDefaultLauncher(USER_10, mMainActivityFetcher.apply(LAUNCHER_2, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            AppWidgetProviderInfo info = makeProviderInfo("c1");

            assertTrue(mInternal.requestPinAppWidget(CALLING_PACKAGE_1, info, null,
                    resultIntent == null ? null : resultIntent.getIntentSender(), USER_P0));

            verify(mServiceContext, times(0)).sendIntentSender(any(IntentSender.class));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Check the intent passed to startActivityAsUser().
            final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

            verify(mServiceContext).startActivityAsUser(intent.capture(), eq(HANDLE_USER_0));

            assertPinItemRequestIntent(intent.getValue(), mInjectedClientPackage);

            // Check the request object.
            final PinItemRequest request = mLauncherApps.getPinItemRequest(intent.getValue());
            assertPinItemRequest(request, "c1");

            // Accept the request.
            assertTrue(request.accept());
        });

        // This method is always called, even with PI == null.
        if (resultIntent == null) {
            verify(mServiceContext, times(1)).sendIntentSender(eq(null));
        } else {
            verify(mServiceContext, times(1)).sendIntentSender(any(IntentSender.class));
        }
    }

    public void testRequestPinAppWidget() {
        checkRequestPinAppWidget(/* resultIntent=*/ null);
    }

    public void testRequestPinAppWidget_withCallback() {
        final PendingIntent resultIntent =
                PendingIntent.getActivity(getTestContext(), 0, new Intent(), 0);

        checkRequestPinAppWidget(resultIntent);
    }
}
