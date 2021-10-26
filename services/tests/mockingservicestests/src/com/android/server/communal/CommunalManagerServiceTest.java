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

package com.android.server.communal;

import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.communal.CommunalManagerService.ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT;
import static com.android.server.wm.ActivityInterceptorCallback.COMMUNAL_MODE_ORDERED_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.communal.ICommunalManager;
import android.app.compat.CompatChanges;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

/**
 * Test class for {@link CommunalManagerService}.
 *
 * Build/Install/Run:
 *   atest FrameworksMockingServicesTests:CommunalManagerServiceTest
 */
@RunWith(MockitoJUnitRunner.class)
@SmallTest
@Presubmit
public class CommunalManagerServiceTest {
    private static final int TEST_USER_ID = 1;
    private static final int TEST_REAL_CALLING_UID = 2;
    private static final int TEST_REAL_CALLING_PID = 3;
    private static final String TEST_CALLING_PACKAGE = "com.test.caller";
    private static final String TEST_PACKAGE_NAME = "com.test.package";

    private MockitoSession mMockingSession;
    private CommunalManagerService mService;

    @Mock
    private ActivityTaskManagerInternal mAtmInternal;
    @Mock
    private KeyguardManager mKeyguardManager;

    private ActivityInterceptorCallback mActivityInterceptorCallback;
    private ActivityInfo mAInfo;
    private ICommunalManager mBinder;
    private ContextWrapper mContextSpy;

    @Before
    public final void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(CompatChanges.class)
                .mockStatic(KeyguardManager.class)
                .strictness(Strictness.WARN)
                .startMocking();

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        MockContentResolver cr = new MockContentResolver(mContextSpy);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContextSpy.getContentResolver()).thenReturn(cr);

        when(mContextSpy.getSystemService(KeyguardManager.class)).thenReturn(mKeyguardManager);
        addLocalServiceMock(ActivityTaskManagerInternal.class, mAtmInternal);

        doNothing().when(mContextSpy).enforceCallingPermission(
                eq(Manifest.permission.WRITE_COMMUNAL_STATE), anyString());

        mService = new CommunalManagerService(mContextSpy);
        spyOn(mService);
        doNothing().when(mService).publishBinderServices();
        mService.onStart();

        ArgumentCaptor<ActivityInterceptorCallback> activityInterceptorCaptor =
                ArgumentCaptor.forClass(ActivityInterceptorCallback.class);
        verify(mAtmInternal).registerActivityStartInterceptor(eq(COMMUNAL_MODE_ORDERED_ID),
                activityInterceptorCaptor.capture());
        mActivityInterceptorCallback = activityInterceptorCaptor.getValue();

        mBinder = mService.getBinderServiceInstance();

        mAInfo = new ActivityInfo();
        mAInfo.applicationInfo = new ApplicationInfo();
        mAInfo.packageName = mAInfo.applicationInfo.packageName = TEST_PACKAGE_NAME;
    }

    @After
    public void tearDown() {
        FakeSettingsProvider.clearSettingsProvider();
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private ActivityInterceptorCallback.ActivityInterceptorInfo buildActivityInfo(Intent intent) {
        return new ActivityInterceptorCallback.ActivityInterceptorInfo(
                TEST_REAL_CALLING_UID,
                TEST_REAL_CALLING_PID,
                TEST_USER_ID,
                TEST_CALLING_PACKAGE,
                "featureId",
                intent,
                null,
                mAInfo,
                "resolvedType",
                TEST_REAL_CALLING_PID,
                TEST_REAL_CALLING_UID,
                null);
    }

    private void allowPackages(String packages) {
        Settings.Secure.putStringForUser(mContextSpy.getContentResolver(),
                Settings.Secure.COMMUNAL_MODE_PACKAGES, packages, UserHandle.USER_SYSTEM);
        mService.updateSelectedApps();
    }

    @Test
    public void testIntercept_unlocked_communalOff_appNotEnabled_showWhenLockedOff() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        mAInfo.flags = 0;
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNull();
    }

    @Test
    public void testIntercept_unlocked_communalOn_appNotEnabled_showWhenLockedOff()
            throws RemoteException {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        mBinder.setCommunalViewShowing(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        mAInfo.flags = 0;
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNull();
    }

    @Test
    public void testIntercept_locked_communalOff_appNotEnabled_showWhenLockedOff() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        mAInfo.flags = 0;
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNull();
    }

    @Test
    public void testIntercept_locked_communalOn_appNotEnabled_showWhenLockedOff_allowlistEnabled()
            throws RemoteException {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        mBinder.setCommunalViewShowing(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(CompatChanges.isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, TEST_PACKAGE_NAME,
                UserHandle.SYSTEM)).thenReturn(true);
        mAInfo.flags = 0;
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNotNull();
    }

    @Test
    public void testIntercept_locked_communalOn_appNotEnabled_showWhenLockedOn_allowlistEnabled()
            throws RemoteException {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        mBinder.setCommunalViewShowing(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(CompatChanges.isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, TEST_PACKAGE_NAME,
                UserHandle.SYSTEM)).thenReturn(true);
        mAInfo.flags = FLAG_SHOW_WHEN_LOCKED;

        allowPackages("package1,package2");
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNotNull();
    }

    @Test
    public void testIntercept_locked_communalOn_appEnabled_showWhenLockedOff_allowlistEnabled()
            throws RemoteException {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        mBinder.setCommunalViewShowing(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(CompatChanges.isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, TEST_PACKAGE_NAME,
                UserHandle.SYSTEM)).thenReturn(true);
        mAInfo.flags = 0;

        allowPackages(TEST_PACKAGE_NAME);
        // TODO(b/191994709): Fix this assertion once we start checking showWhenLocked
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNull();
    }

    @Test
    public void testIntercept_locked_communalOn_appEnabled_showWhenLockedOn_allowlistEnabled()
            throws RemoteException {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        mBinder.setCommunalViewShowing(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(CompatChanges.isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, TEST_PACKAGE_NAME,
                UserHandle.SYSTEM)).thenReturn(true);

        mAInfo.flags = FLAG_SHOW_WHEN_LOCKED;

        allowPackages(TEST_PACKAGE_NAME);
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNull();
    }

    @Test
    public void testIntercept_locked_communalOn_appEnabled_showWhenLockedOn_allowlistDisabled()
            throws RemoteException {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        mBinder.setCommunalViewShowing(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        when(CompatChanges.isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, TEST_PACKAGE_NAME,
                UserHandle.SYSTEM)).thenReturn(false);

        mAInfo.flags = FLAG_SHOW_WHEN_LOCKED;

        allowPackages(TEST_PACKAGE_NAME);
        assertThat(mActivityInterceptorCallback.intercept(buildActivityInfo(intent))).isNotNull();
    }
}
