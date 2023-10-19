/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.contentcapture;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.service.contentcapture.ContentCaptureServiceInfo;
import android.view.contentcapture.ContentCaptureEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.contentprotection.ContentProtectionBlocklistManager;
import com.android.server.contentprotection.ContentProtectionConsentManager;
import com.android.server.contentprotection.RemoteContentProtectionService;
import com.android.server.pm.UserManagerInternal;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link ContentCaptureManagerService}.
 *
 * <p>Run with: {@code atest
 * FrameworksServicesTests:com.android.server.contentcapture.ContentCaptureManagerServiceTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy") // Service not really running, no need to expose locks
public class ContentCaptureManagerServiceTest {

    private static final int USER_ID = 1234;

    private static final String PACKAGE_NAME = "com.test.package";

    private static final ComponentName COMPONENT_NAME =
            new ComponentName(PACKAGE_NAME, "TestClass");

    private static final ContentCaptureEvent EVENT =
            new ContentCaptureEvent(/* sessionId= */ 100, /* type= */ 200);

    private static final ParceledListSlice<ContentCaptureEvent> PARCELED_EVENTS =
            new ParceledListSlice<>(ImmutableList.of(EVENT));

    private static final Context sContext = ApplicationProvider.getApplicationContext();

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private UserManagerInternal mMockUserManagerInternal;

    @Mock private ContentProtectionBlocklistManager mMockContentProtectionBlocklistManager;

    @Mock private ContentCaptureServiceInfo mMockContentCaptureServiceInfo;

    @Mock private RemoteContentProtectionService mMockRemoteContentProtectionService;

    @Mock private ContentProtectionConsentManager mMockContentProtectionConsentManager;

    private boolean mDevCfgEnableContentProtectionReceiver;

    private int mContentProtectionBlocklistManagersCreated;

    private int mContentProtectionServiceInfosCreated;

    private int mRemoteContentProtectionServicesCreated;

    private int mContentProtectionConsentManagersCreated;

    private String mConfigDefaultContentProtectionService = COMPONENT_NAME.flattenToString();

    private boolean mContentProtectionServiceInfoConstructorShouldThrow;

    private ContentCaptureManagerService mContentCaptureManagerService;

    @Before
    public void setup() {
        when(mMockUserManagerInternal.getUserInfos()).thenReturn(new UserInfo[0]);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mMockUserManagerInternal);
        mContentCaptureManagerService = new TestContentCaptureManagerService();
    }

    @Test
    public void constructor_contentProtection_flagDisabled_noManagers() {
        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(0);
        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(0);
        assertThat(mContentProtectionConsentManagersCreated).isEqualTo(0);
        verifyZeroInteractions(mMockContentProtectionBlocklistManager);
        verifyZeroInteractions(mMockContentProtectionConsentManager);
    }

    @Test
    public void constructor_contentProtection_componentNameNull_noManagers() {
        mConfigDefaultContentProtectionService = null;

        mContentCaptureManagerService = new TestContentCaptureManagerService();

        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(0);
        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(0);
        assertThat(mContentProtectionConsentManagersCreated).isEqualTo(0);
        verifyZeroInteractions(mMockContentProtectionBlocklistManager);
        verifyZeroInteractions(mMockContentProtectionConsentManager);
    }

    @Test
    public void constructor_contentProtection_componentNameBlank_noManagers() {
        mConfigDefaultContentProtectionService = "   ";

        mContentCaptureManagerService = new TestContentCaptureManagerService();

        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(0);
        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(0);
        assertThat(mContentProtectionConsentManagersCreated).isEqualTo(0);
        verifyZeroInteractions(mMockContentProtectionBlocklistManager);
        verifyZeroInteractions(mMockContentProtectionConsentManager);
    }

    @Test
    public void constructor_contentProtection_enabled_createsManagers() {
        mDevCfgEnableContentProtectionReceiver = true;

        mContentCaptureManagerService = new TestContentCaptureManagerService();

        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(1);
        assertThat(mContentProtectionConsentManagersCreated).isEqualTo(1);
        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(0);
        verify(mMockContentProtectionBlocklistManager).updateBlocklist(anyInt());
        verifyZeroInteractions(mMockContentProtectionConsentManager);
    }

    @Test
    public void setFineTuneParamsFromDeviceConfig_doesNotUpdateContentProtectionBlocklist() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mDevCfgContentProtectionAppsBlocklistSize += 100;
        verify(mMockContentProtectionBlocklistManager).updateBlocklist(anyInt());

        mContentCaptureManagerService.setFineTuneParamsFromDeviceConfig();

        verifyNoMoreInteractions(mMockContentProtectionBlocklistManager);
    }

    @Test
    public void getOptions_contentCaptureDisabled_contentProtectionDisabled() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        ContentCaptureOptions actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.getOptions(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isNull();
        verify(mMockContentProtectionConsentManager).isConsentGranted(USER_ID);
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void getOptions_contentCaptureDisabled_contentProtectionEnabled() {
        when(mMockContentProtectionConsentManager.isConsentGranted(USER_ID)).thenReturn(true);
        when(mMockContentProtectionBlocklistManager.isAllowed(PACKAGE_NAME)).thenReturn(true);
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        ContentCaptureOptions actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.getOptions(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isNotNull();
        assertThat(actual.enableReceiver).isFalse();
        assertThat(actual.contentProtectionOptions).isNotNull();
        assertThat(actual.contentProtectionOptions.enableReceiver).isTrue();
        assertThat(actual.whitelistedComponents).isNull();
    }

    @Test
    public void getOptions_contentCaptureEnabled_contentProtectionDisabled() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mGlobalContentCaptureOptions.setWhitelist(
                USER_ID, ImmutableList.of(PACKAGE_NAME), /* components= */ null);

        ContentCaptureOptions actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.getOptions(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isNotNull();
        assertThat(actual.enableReceiver).isTrue();
        assertThat(actual.contentProtectionOptions).isNotNull();
        assertThat(actual.contentProtectionOptions.enableReceiver).isFalse();
        assertThat(actual.whitelistedComponents).isNull();
        verify(mMockContentProtectionConsentManager).isConsentGranted(USER_ID);
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void getOptions_contentCaptureEnabled_contentProtectionEnabled() {
        when(mMockContentProtectionConsentManager.isConsentGranted(USER_ID)).thenReturn(true);
        when(mMockContentProtectionBlocklistManager.isAllowed(PACKAGE_NAME)).thenReturn(true);
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mGlobalContentCaptureOptions.setWhitelist(
                USER_ID, ImmutableList.of(PACKAGE_NAME), /* components= */ null);

        ContentCaptureOptions actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.getOptions(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isNotNull();
        assertThat(actual.enableReceiver).isTrue();
        assertThat(actual.contentProtectionOptions).isNotNull();
        assertThat(actual.contentProtectionOptions.enableReceiver).isTrue();
        assertThat(actual.whitelistedComponents).isNull();
    }

    @Test
    public void isWhitelisted_packageName_contentCaptureDisabled_contentProtectionNotGranted() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionConsentManager).isConsentGranted(USER_ID);
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isWhitelisted_packageName_contentCaptureDisabled_contentProtectionDisabled() {
        when(mMockContentProtectionConsentManager.isConsentGranted(USER_ID)).thenReturn(true);
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionBlocklistManager).isAllowed(PACKAGE_NAME);
    }

    @Test
    public void isWhitelisted_packageName_contentCaptureDisabled_contentProtectionEnabled() {
        when(mMockContentProtectionConsentManager.isConsentGranted(USER_ID)).thenReturn(true);
        when(mMockContentProtectionBlocklistManager.isAllowed(PACKAGE_NAME)).thenReturn(true);
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isTrue();
    }

    @Test
    public void isWhitelisted_packageName_contentCaptureEnabled_contentProtectionNotChecked() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mGlobalContentCaptureOptions.setWhitelist(
                USER_ID, ImmutableList.of(PACKAGE_NAME), /* components= */ null);

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isTrue();
        verify(mMockContentProtectionConsentManager, never()).isConsentGranted(anyInt());
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isWhitelisted_componentName_contentCaptureDisabled_contentProtectionNotGranted() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, COMPONENT_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionConsentManager).isConsentGranted(USER_ID);
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isWhitelisted_componentName_contentCaptureDisabled_contentProtectionDisabled() {
        when(mMockContentProtectionConsentManager.isConsentGranted(USER_ID)).thenReturn(true);
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, COMPONENT_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionBlocklistManager).isAllowed(PACKAGE_NAME);
    }

    @Test
    public void isWhitelisted_componentName_contentCaptureDisabled_contentProtectionEnabled() {
        when(mMockContentProtectionConsentManager.isConsentGranted(USER_ID)).thenReturn(true);
        when(mMockContentProtectionBlocklistManager.isAllowed(PACKAGE_NAME)).thenReturn(true);
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, COMPONENT_NAME);

        assertThat(actual).isTrue();
    }

    @Test
    public void isWhitelisted_componentName_contentCaptureEnabled_contentProtectionNotChecked() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mGlobalContentCaptureOptions.setWhitelist(
                USER_ID, /* packageNames= */ null, ImmutableList.of(COMPONENT_NAME));

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, COMPONENT_NAME);

        assertThat(actual).isTrue();
        verify(mMockContentProtectionConsentManager, never()).isConsentGranted(anyInt());
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isContentProtectionReceiverEnabled_withoutManagers() {
        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionConsentManager, never()).isConsentGranted(anyInt());
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isContentProtectionReceiverEnabled_disabledWithFlag() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mDevCfgEnableContentProtectionReceiver = false;

        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionConsentManager, never()).isConsentGranted(anyInt());
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void onLoginDetected_disabledAfterConstructor() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();
        mContentCaptureManagerService.mDevCfgEnableContentProtectionReceiver = false;

        mContentCaptureManagerService
                .getContentCaptureManagerServiceStub()
                .onLoginDetected(PARCELED_EVENTS);

        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(0);
        assertThat(mRemoteContentProtectionServicesCreated).isEqualTo(0);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
    }

    @Test
    public void onLoginDetected_invalidPermissions() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentProtectionServiceInfoConstructorShouldThrow = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        mContentCaptureManagerService
                .getContentCaptureManagerServiceStub()
                .onLoginDetected(PARCELED_EVENTS);

        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(1);
        assertThat(mRemoteContentProtectionServicesCreated).isEqualTo(0);
        verifyZeroInteractions(mMockRemoteContentProtectionService);
    }

    @Test
    public void onLoginDetected_enabled() {
        mDevCfgEnableContentProtectionReceiver = true;
        mContentCaptureManagerService = new TestContentCaptureManagerService();

        mContentCaptureManagerService
                .getContentCaptureManagerServiceStub()
                .onLoginDetected(PARCELED_EVENTS);

        assertThat(mContentProtectionServiceInfosCreated).isEqualTo(1);
        assertThat(mRemoteContentProtectionServicesCreated).isEqualTo(1);
        verify(mMockRemoteContentProtectionService).onLoginDetected(PARCELED_EVENTS);
    }

    private class TestContentCaptureManagerService extends ContentCaptureManagerService {

        TestContentCaptureManagerService() {
            super(sContext);
            this.mDevCfgEnableContentProtectionReceiver =
                    ContentCaptureManagerServiceTest.this.mDevCfgEnableContentProtectionReceiver;
        }

        @Override
        protected boolean getEnableContentProtectionReceiverLocked() {
            return ContentCaptureManagerServiceTest.this.mDevCfgEnableContentProtectionReceiver;
        }

        @Override
        protected ContentProtectionBlocklistManager createContentProtectionBlocklistManager() {
            mContentProtectionBlocklistManagersCreated++;
            return mMockContentProtectionBlocklistManager;
        }

        @Override
        protected String getContentProtectionServiceFlatComponentName() {
            return mConfigDefaultContentProtectionService;
        }

        @Override
        protected ContentCaptureServiceInfo createContentProtectionServiceInfo(
                @NonNull ComponentName componentName) throws PackageManager.NameNotFoundException {
            mContentProtectionServiceInfosCreated++;
            if (mContentProtectionServiceInfoConstructorShouldThrow) {
                throw new RuntimeException("TEST RUNTIME EXCEPTION");
            }
            return mMockContentCaptureServiceInfo;
        }

        @Override
        protected RemoteContentProtectionService createRemoteContentProtectionService(
                @NonNull ComponentName componentName) {
            mRemoteContentProtectionServicesCreated++;
            return mMockRemoteContentProtectionService;
        }

        @Override
        protected ContentProtectionConsentManager createContentProtectionConsentManager() {
            mContentProtectionConsentManagersCreated++;
            return mMockContentProtectionConsentManager;
        }
    }
}
