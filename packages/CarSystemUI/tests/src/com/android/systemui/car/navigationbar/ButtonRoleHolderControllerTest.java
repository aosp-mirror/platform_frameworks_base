/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.navigationbar;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import android.app.role.RoleManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ButtonRoleHolderControllerTest extends SysuiTestCase {
    private static final String TEST_VALID_PACKAGE_NAME = "foo";
    private static final String TEST_INVALID_PACKAGE_NAME = "bar";
    private static final UserHandle TEST_CURRENT_USER = UserHandle.of(100);
    private static final UserHandle TEST_NON_CURRENT_USER = UserHandle.of(101);

    private LinearLayout mTestView;
    private CarNavigationButton mNavButtonDefaultAppIconForRoleWithEnabled;
    private CarNavigationButton mNavButtonDefaultAppIconForRoleWithDisabled;
    private ButtonRoleHolderController mControllerUnderTest;
    private Drawable mAppIcon;

    @Mock
    private RoleManager mRoleManager;
    @Mock
    private CarDeviceProvisionedController mDeviceProvisionedController;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ApplicationInfo mApplicationInfo;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        mTestView = (LinearLayout) LayoutInflater.from(mContext).inflate(
                R.layout.button_role_holder_controller_test, /* root= */ null);
        mNavButtonDefaultAppIconForRoleWithEnabled = mTestView
                .findViewById(R.id.assistant_role_button);
        mNavButtonDefaultAppIconForRoleWithDisabled = mTestView
                .findViewById(R.id.assistant_role_disabled_button);
        mAppIcon = mContext.getDrawable(R.drawable.car_ic_apps);
        when(mApplicationInfo.loadIcon(any())).thenReturn(mAppIcon);
        doThrow(new PackageManager.NameNotFoundException()).when(mPackageManager)
                .getApplicationInfo(any(), anyInt());
        doReturn(mApplicationInfo).when(mPackageManager)
                .getApplicationInfo(eq(TEST_VALID_PACKAGE_NAME), anyInt());
        when(mDeviceProvisionedController
                .getCurrentUser())
                .thenReturn(TEST_CURRENT_USER.getIdentifier());
        mControllerUnderTest = new ButtonRoleHolderController(mContext,
                mPackageManager, mRoleManager, mDeviceProvisionedController);
    }

    @Test
    public void addAllButtonsWithRoleName_roleAssigned_appIconEnabled_useAssignedAppIcon() {
        when(mRoleManager.getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(List.of(TEST_VALID_PACKAGE_NAME));

        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);

        assertThat(mNavButtonDefaultAppIconForRoleWithEnabled.getAppIcon()).isEqualTo(mAppIcon);
    }

    @Test
    public void addAllButtonsWithRoleName_roleUnassigned_appIconEnabled_useDefaultIcon() {
        when(mRoleManager.getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(null);

        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);

        assertThat(mNavButtonDefaultAppIconForRoleWithEnabled.getAppIcon()).isNull();
    }

    @Test
    public void onRoleChanged_currentUser_appIconEnabled_useAssignedAppIcon() {
        when(mRoleManager.getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(null);
        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(List.of(TEST_VALID_PACKAGE_NAME));

        mControllerUnderTest.onRoleChanged(RoleManager.ROLE_ASSISTANT, TEST_CURRENT_USER);

        assertThat(mNavButtonDefaultAppIconForRoleWithEnabled.getAppIcon()).isEqualTo(mAppIcon);
    }

    @Test
    public void onRoleChanged_nonCurrentUser_appIconEnabled_iconIsNotUpdated() {
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(null);
        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);
        Drawable beforeIcon = mNavButtonDefaultAppIconForRoleWithEnabled.getAppIcon();
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(List.of(TEST_VALID_PACKAGE_NAME));

        mControllerUnderTest.onRoleChanged(RoleManager.ROLE_ASSISTANT, TEST_NON_CURRENT_USER);

        Drawable afterIcon = mNavButtonDefaultAppIconForRoleWithEnabled.getAppIcon();
        assertThat(afterIcon).isEqualTo(beforeIcon);
    }

    @Test
    public void onRoleChanged_invalidPackage_useDefaultIcon() {
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(List.of(TEST_INVALID_PACKAGE_NAME));

        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);

        assertThat(mNavButtonDefaultAppIconForRoleWithEnabled.getAppIcon()).isNull();
    }

    @Test
    public void addAllButtonsWithRoleName_appIconDisabled_useDefaultIcon() {
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(List.of(TEST_VALID_PACKAGE_NAME));

        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);

        assertThat(mNavButtonDefaultAppIconForRoleWithDisabled.getAppIcon()).isNull();
    }

    @Test
    public void onRoleChanged_roleAssigned_appIconDisabled_useDefaultIcon() {
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(null);
        mControllerUnderTest.addAllButtonsWithRoleName(mTestView);
        assertThat(mNavButtonDefaultAppIconForRoleWithDisabled.getAppIcon()).isNull();
        when(mRoleManager
                .getRoleHoldersAsUser(eq(RoleManager.ROLE_ASSISTANT), any()))
                .thenReturn(List.of(TEST_VALID_PACKAGE_NAME));

        mControllerUnderTest.onRoleChanged(RoleManager.ROLE_ASSISTANT, TEST_CURRENT_USER);

        assertThat(mNavButtonDefaultAppIconForRoleWithDisabled.getAppIcon()).isNull();
    }
}
