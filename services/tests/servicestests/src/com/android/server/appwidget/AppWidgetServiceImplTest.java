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

package com.android.server.appwidget;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManagerInternal;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ShortcutServiceInternal;
import android.os.Handler;
import android.os.UserHandle;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.LocalServices;

import org.mockito.ArgumentCaptor;


/**
 * Tests for {@link AppWidgetManager} and {@link AppWidgetServiceImpl}.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.appwidget.AppWidgetServiceImplTest \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class AppWidgetServiceImplTest extends InstrumentationTestCase {

    private TestContext mTestContext;
    private AppWidgetServiceImpl mService;
    private AppWidgetManager mManager;

    private ShortcutServiceInternal mMockShortcutService;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        mTestContext = new TestContext();
        mService = new AppWidgetServiceImpl(mTestContext);
        mManager = new AppWidgetManager(mTestContext, mService);

        mMockShortcutService = mock(ShortcutServiceInternal.class);
        LocalServices.addService(ShortcutServiceInternal.class, mMockShortcutService);

        mService.onStart();
    }

    public void testRequestPinAppWidget_otherProvider() {
        ComponentName otherProvider = null;
        for (AppWidgetProviderInfo provider : mManager.getInstalledProviders()) {
            if (!provider.provider.getPackageName().equals(mTestContext.getPackageName())) {
                otherProvider = provider.provider;
                break;
            }
        }
        if (otherProvider == null) {
            // No other provider found. Ignore this test.
        }
        assertFalse(mManager.requestPinAppWidget(otherProvider, null));
    }

    public void testRequestPinAppWidget() {
        ComponentName provider = new ComponentName(mTestContext, DummyAppWidget.class);
        // Set up users.
        when(mMockShortcutService.requestPinAppWidget(anyString(),
                any(AppWidgetProviderInfo.class), any(IntentSender.class), anyInt()))
                .thenReturn(true);
        assertTrue(mManager.requestPinAppWidget(provider, null));

        final ArgumentCaptor<AppWidgetProviderInfo> providerCaptor =
                ArgumentCaptor.forClass(AppWidgetProviderInfo.class);
        verify(mMockShortcutService, times(1)).requestPinAppWidget(anyString(),
                providerCaptor.capture(), eq(null), anyInt());
        assertEquals(provider, providerCaptor.getValue().provider);
    }

    private class TestContext extends ContextWrapper {

        public TestContext() {
            super(getInstrumentation().getContext());
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            // ignore.
            return null;
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            // ignore.
        }
    }
}
