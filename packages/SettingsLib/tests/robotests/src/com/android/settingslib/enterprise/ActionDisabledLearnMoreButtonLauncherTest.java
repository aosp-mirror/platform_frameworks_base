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
package com.android.settingslib.enterprise;

import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ADMIN_COMPONENT;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCED_ADMIN;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCED_ADMIN_WITHOUT_COMPONENT;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCEMENT_ADMIN_USER;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.ENFORCEMENT_ADMIN_USER_ID;
import static com.android.settingslib.enterprise.ActionDisabledByAdminControllerTestUtils.URL;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)// NOTE: this test doesn't need RoboElectric...
public final class ActionDisabledLearnMoreButtonLauncherTest {

    private static final int CONTEXT_USER_ID = -ENFORCEMENT_ADMIN_USER_ID;
    private static final UserHandle CONTEXT_USER = UserHandle.of(CONTEXT_USER_ID);

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private Context mContext;

    @Mock
    private DevicePolicyManager mDevicePolicyManager;

    @Mock
    private UserManager mUserManager;

    @Spy
    private ActionDisabledLearnMoreButtonLauncher mLauncher;

    @Captor
    private ArgumentCaptor<Runnable> mLearnMoreActionCaptor;

    @Captor
    private ArgumentCaptor<Intent> mIntentCaptor;

    @Before
    public void setUp() {
        when(mContext.getUserId()).thenReturn(CONTEXT_USER_ID);
        when(mUserManager.getUserHandle()).thenReturn(CONTEXT_USER_ID);
        when(mContext.getSystemService(DevicePolicyManager.class)).thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_nullContext() {
        assertThrows(NullPointerException.class,
                () -> mLauncher.setupLearnMoreButtonToShowAdminPolicies(/* context= */ null,
                        ENFORCEMENT_ADMIN_USER_ID, ENFORCED_ADMIN));
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_nullEnforcedAdmin() {
        assertThrows(NullPointerException.class,
                () -> mLauncher.setupLearnMoreButtonToShowAdminPolicies(/* context= */ null,
                        ENFORCEMENT_ADMIN_USER_ID, /* enforcedAdmin= */ null));
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_differentProfileGroup_noDeviceOwner() {
        mockDifferentProfileGroup();
        mockEnforcementAdminIsNotDeviceOwner();

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(mContext, ENFORCEMENT_ADMIN_USER_ID,
                ENFORCED_ADMIN);

        verify(mLauncher, never()).setLearnMoreButton(any());
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_differentGroup_noSystemDeviceOwner() {
        mockDifferentProfileGroup();
        mockDeviceOwner(ENFORCEMENT_ADMIN_USER_ID);

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(mContext, ENFORCEMENT_ADMIN_USER_ID,
                ENFORCED_ADMIN);

        verify(mLauncher, never()).setLearnMoreButton(any());
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_differentGroup_systemDeviceOwner() {
        mockDifferentProfileGroup();
        mockDeviceOwner(UserHandle.USER_SYSTEM);

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(mContext, UserHandle.USER_SYSTEM,
                ENFORCED_ADMIN_WITHOUT_COMPONENT);
        tapLearnMore();

        verify(mLauncher, never()).launchShowAdminPolicies(any(), any(), any());
        verify(mLauncher).launchShowAdminSettings(mContext);
        verifyFinishSelf();
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_sameProfileGroup_noDeviceOwner() {
        mockSameProfileGroup();
        mockEnforcementAdminIsNotDeviceOwner();

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(mContext, ENFORCEMENT_ADMIN_USER_ID,
                ENFORCED_ADMIN_WITHOUT_COMPONENT);
        tapLearnMore();

        verify(mLauncher, never()).launchShowAdminPolicies(any(), any(), any());
        verify(mLauncher).launchShowAdminSettings(mContext);
        verifyFinishSelf();
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_sameProfileGroup_noSystemDeviceOwner() {
        mockSameProfileGroup();
        mockDeviceOwner(ENFORCEMENT_ADMIN_USER_ID);

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(mContext, ENFORCEMENT_ADMIN_USER_ID,
                ENFORCED_ADMIN_WITHOUT_COMPONENT);
        tapLearnMore();

        verify(mLauncher, never()).launchShowAdminPolicies(any(), any(), any());
        verify(mLauncher).launchShowAdminSettings(mContext);
        verifyFinishSelf();
    }

    @Test
    public void testSetupLearnMoreButtonToShowAdminPolicies_showsLearnMoreButton_withComponent() {
        mockSameProfileGroup();
        mockEnforcementAdminIsNotDeviceOwner();

        mLauncher.setupLearnMoreButtonToShowAdminPolicies(mContext, ENFORCEMENT_ADMIN_USER_ID,
                ENFORCED_ADMIN);
        tapLearnMore();

        verify(mLauncher).launchShowAdminPolicies(mContext, ENFORCEMENT_ADMIN_USER,
                ADMIN_COMPONENT);
        verify(mLauncher, never()).launchShowAdminSettings(any());
        verifyFinishSelf();
    }

    @Test
    public void testSetupLearnMoreButtonToLaunchHelpPage_nullContext() {
        assertThrows(NullPointerException.class,
                () -> mLauncher.setupLearnMoreButtonToLaunchHelpPage(
                        /* context= */ null, URL, CONTEXT_USER));
    }

    @Test
    public void testSetupLearnMoreButtonToLaunchHelpPage_nullUrl() {
        assertThrows(NullPointerException.class,
                () -> mLauncher.setupLearnMoreButtonToLaunchHelpPage(
                        mContext, /* url= */ null, CONTEXT_USER));
    }

    @Test
    public void testSetupLearnMoreButtonToLaunchHelpPage() {
        mLauncher.setupLearnMoreButtonToLaunchHelpPage(mContext, URL, CONTEXT_USER);
        tapLearnMore();

        verify(mContext).startActivityAsUser(mIntentCaptor.capture(), eq(CONTEXT_USER));
        Intent intent = mIntentCaptor.getValue();
        assertWithMessage("wrong url on intent %s", intent).that(intent.getData())
                .isEqualTo(Uri.parse(URL));
        verifyFinishSelf();
    }

    private void mockDifferentProfileGroup() {
        // No need to mock anything - isSameProfileGroup() will return false by default
    }

    private void mockSameProfileGroup() {
        when(mUserManager.isSameProfileGroup(ENFORCEMENT_ADMIN_USER_ID, CONTEXT_USER_ID))
                .thenReturn(true);
    }

    private void mockEnforcementAdminIsNotDeviceOwner() {
        when(mDevicePolicyManager.getDeviceOwnerUserId()).thenReturn(ENFORCEMENT_ADMIN_USER_ID + 1);
    }

    private void mockDeviceOwner(int userId) {
        when(mDevicePolicyManager.getDeviceOwnerUserId()).thenReturn(userId);
    }

    private void tapLearnMore() {
        verify(mLauncher).setLearnMoreButton(mLearnMoreActionCaptor.capture());
        mLearnMoreActionCaptor.getValue().run();
    }

    private void verifyFinishSelf() {
        verify(mLauncher).finishSelf();
    }
}
