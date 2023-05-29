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
import android.content.pm.UserInfo;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.contentprotection.ContentProtectionBlocklistManager;
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

    private static final Context sContext = ApplicationProvider.getApplicationContext();

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private UserManagerInternal mMockUserManagerInternal;

    @Mock private ContentProtectionBlocklistManager mMockContentProtectionBlocklistManager;

    private boolean mDevCfgEnableContentProtectionReceiver;

    private int mContentProtectionBlocklistManagersCreated;

    private ContentCaptureManagerService mContentCaptureManagerService;

    @Before
    public void setup() {
        when(mMockUserManagerInternal.getUserInfos()).thenReturn(new UserInfo[0]);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mMockUserManagerInternal);
        mContentCaptureManagerService = new TestContentCaptureManagerService();
    }

    @Test
    public void constructor_default_doesNotCreateContentProtectionBlocklistManager() {
        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(0);
        verifyZeroInteractions(mMockContentProtectionBlocklistManager);
    }

    @Test
    public void constructor_flagDisabled_doesNotContentProtectionBlocklistManager() {
        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(0);
        verifyZeroInteractions(mMockContentProtectionBlocklistManager);
    }

    @Test
    public void constructor_flagEnabled_createsContentProtectionBlocklistManager() {
        mDevCfgEnableContentProtectionReceiver = true;

        mContentCaptureManagerService = new TestContentCaptureManagerService();

        assertThat(mContentProtectionBlocklistManagersCreated).isEqualTo(1);
        verify(mMockContentProtectionBlocklistManager).updateBlocklist(anyInt());
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
        verify(mMockContentProtectionBlocklistManager).isAllowed(PACKAGE_NAME);
    }

    @Test
    public void getOptions_contentCaptureDisabled_contentProtectionEnabled() {
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
        verify(mMockContentProtectionBlocklistManager).isAllowed(PACKAGE_NAME);
    }

    @Test
    public void getOptions_contentCaptureEnabled_contentProtectionEnabled() {
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
    public void isWhitelisted_packageName_contentCaptureDisabled_contentProtectionDisabled() {
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
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isWhitelisted_componentName_contentCaptureDisabled_contentProtectionDisabled() {
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
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
    }

    @Test
    public void isContentProtectionReceiverEnabled_withoutBlocklistManager() {
        boolean actual =
                mContentCaptureManagerService.mGlobalContentCaptureOptions.isWhitelisted(
                        USER_ID, PACKAGE_NAME);

        assertThat(actual).isFalse();
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
        verify(mMockContentProtectionBlocklistManager, never()).isAllowed(anyString());
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
        protected ContentProtectionBlocklistManager createContentProtectionBlocklistManager(
                @NonNull Context context) {
            mContentProtectionBlocklistManagersCreated++;
            return mMockContentProtectionBlocklistManager;
        }
    }
}
