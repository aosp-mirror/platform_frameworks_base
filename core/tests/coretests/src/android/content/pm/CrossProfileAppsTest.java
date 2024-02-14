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

package android.content.pm;

import static android.app.admin.DevicePolicyResources.Strings.Core.SWITCH_TO_PERSONAL_LABEL;
import static android.app.admin.DevicePolicyResources.Strings.Core.SWITCH_TO_WORK_LABEL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyResourcesManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Build/Install/Run:
 * atest frameworks/base/core/tests/coretests/src/android/content/pm/CrossProfileAppsTest.java
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class CrossProfileAppsTest {
    private static final UserHandle PERSONAL_PROFILE = UserHandle.of(0);
    private static final UserHandle MANAGED_PROFILE = UserHandle.of(10);
    private static final String MY_PACKAGE = "my.package";

    private List<UserHandle> mTargetProfiles;

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private DevicePolicyResourcesManager mDevicePolicyResourcesManager;
    @Mock
    private ICrossProfileApps mService;
    @Mock
    private Resources mResources;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Drawable mDrawable;
    @Mock
    private PackageManager mPackageManager;

    @Mock private ApplicationInfo mApplicationInfo;

    private Configuration mConfiguration;

    private CrossProfileApps mCrossProfileApps;

    @Before
    public void initCrossProfileApps() {
        mCrossProfileApps = spy(new CrossProfileApps(mContext, mService));
    }

    @Before
    public void mockContext() {
        mConfiguration = new Configuration();
        when(mContext.getPackageName()).thenReturn(MY_PACKAGE);
        when(mContext.getSystemServiceName(UserManager.class)).thenReturn(Context.USER_SERVICE);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemServiceName(DevicePolicyManager.class)).thenReturn(
                Context.DEVICE_POLICY_SERVICE);
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mDevicePolicyManager);
        when(mDevicePolicyManager.getResources()).thenReturn(mDevicePolicyResourcesManager);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mResources.getConfiguration()).thenReturn(mConfiguration);
    }

    @Before
    public void mockResources() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getDrawable(anyInt(), nullable(Resources.Theme.class)))
                .thenReturn(mDrawable);
    }

    @Before
    public void initUsers() throws Exception {
        when(mUserManager.isManagedProfile(PERSONAL_PROFILE.getIdentifier())).thenReturn(false);
        when(mUserManager.isManagedProfile(MANAGED_PROFILE.getIdentifier())).thenReturn(true);
        when(mUserManager.isProfile(PERSONAL_PROFILE.getIdentifier())).thenReturn(false);
        when(mUserManager.isProfile(MANAGED_PROFILE.getIdentifier())).thenReturn(true);

        mTargetProfiles = new ArrayList<>();
        when(mService.getTargetUserProfiles(MY_PACKAGE)).thenReturn(mTargetProfiles);
    }

    @Test
    public void isProfile_managedProfile_returnsTrue() {
        setValidTargetProfile(MANAGED_PROFILE);

        boolean result = mCrossProfileApps.isProfile(MANAGED_PROFILE);

        assertTrue(result);
    }

    @Test
    public void isProfile_personalProfile_returnsFalse() {
        setValidTargetProfile(PERSONAL_PROFILE);

        boolean result = mCrossProfileApps.isProfile(PERSONAL_PROFILE);

        assertFalse(result);
    }

    @Test
    public void isManagedProfile_managedProfile_returnsTrue() {
        setValidTargetProfile(MANAGED_PROFILE);

        boolean result = mCrossProfileApps.isManagedProfile(MANAGED_PROFILE);

        assertTrue(result);
    }

    @Test
    public void isManagedProfile_personalProfile_returnsFalse() {
        setValidTargetProfile(PERSONAL_PROFILE);

        boolean result = mCrossProfileApps.isManagedProfile(PERSONAL_PROFILE);

        assertFalse(result);
    }

    @Test(expected = SecurityException.class)
    public void isManagedProfile_notValidTarget_throwsSecurityException() {
        mCrossProfileApps.isManagedProfile(PERSONAL_PROFILE);
    }

    @Test
    public void getProfileSwitchingLabel_managedProfile() {
        setValidTargetProfile(MANAGED_PROFILE);
        when(mApplicationInfo.loadSafeLabel(any(), anyFloat(), anyInt())).thenReturn("app");

        mCrossProfileApps.getProfileSwitchingLabel(MANAGED_PROFILE);
        verify(mDevicePolicyResourcesManager).getString(eq(SWITCH_TO_WORK_LABEL), any(), eq("app"));
    }

    @Test
    public void getProfileSwitchingLabel_personalProfile() {
        setValidTargetProfile(PERSONAL_PROFILE);
        when(mApplicationInfo.loadSafeLabel(any(), anyFloat(), anyInt())).thenReturn("app");

        mCrossProfileApps.getProfileSwitchingLabel(PERSONAL_PROFILE);
        verify(mDevicePolicyResourcesManager)
                .getString(eq(SWITCH_TO_PERSONAL_LABEL), any(), eq("app"));
    }

    @Test(expected = SecurityException.class)
    public void getProfileSwitchingLabel_securityException() {
        mCrossProfileApps.getProfileSwitchingLabel(PERSONAL_PROFILE);
    }

    @Test
    public void getProfileSwitchingIcon_managedProfile() {
        setValidTargetProfile(MANAGED_PROFILE);

        mCrossProfileApps.getProfileSwitchingIconDrawable(MANAGED_PROFILE);
        verify(mPackageManager).getUserBadgeForDensityNoBackground(
                MANAGED_PROFILE, /* density= */0);
    }

    @Test
    public void getProfileSwitchingIcon_personalProfile() {
        setValidTargetProfile(PERSONAL_PROFILE);

        mCrossProfileApps.getProfileSwitchingIconDrawable(PERSONAL_PROFILE);
        verify(mResources).getDrawable(R.drawable.ic_account_circle, null);
    }

    @Test(expected = SecurityException.class)
    public void getProfileSwitchingIcon_securityException() {
        mCrossProfileApps.getProfileSwitchingIconDrawable(PERSONAL_PROFILE);
    }

    private void setValidTargetProfile(UserHandle userHandle) {
        mTargetProfiles.add(userHandle);
    }
}
