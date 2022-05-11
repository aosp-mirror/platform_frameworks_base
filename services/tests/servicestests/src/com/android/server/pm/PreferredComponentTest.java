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

package com.android.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import com.android.server.LocalServices;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@RunWith(JUnit4.class)
public class PreferredComponentTest {

    private PackageManagerInternal mMockPackageManagerInternal;

    @Before
    public void setUp() {
        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);
    }

    @Test
    public void testPreferredComponent_sameSet_withAppInstalledByDeviceSetup() {
        // Assume we have two ResolveInfos that handle the same Intent.
        final int userId = UserHandle.USER_SYSTEM;
        final List<ResolveInfo> query = new ArrayList<>();
        query.add(createResolveInfo(0, userId));
        query.add(createResolveInfo(1, userId));
        // ResolveInfo(0) is already set as the preferred one when only it exists.
        final ComponentName component = query.get(0).getComponentInfo().getComponentName();
        final PreferredActivity pa = new PreferredActivity(new IntentFilter("TEST_ACTION"),
                0 /* match */, new ComponentName[]{component}, component, true /* always */);

        // Assume ResolveInfo(0) is preinstalled, and ResolveInfo(1) is installed when device setup.
        final PackageUserState pkgUserState0 = new PackageUserStateImpl().setInstallReason(
                PackageManager.INSTALL_REASON_UNKNOWN);
        final PackageUserState pkgUserState1 = new PackageUserStateImpl().setInstallReason(
                PackageManager.INSTALL_REASON_DEVICE_SETUP);
        final PackageStateInternal psInt0 = mock(PackageStateInternal.class);
        final PackageStateInternal psInt1 = mock(PackageStateInternal.class);
        final SparseArray<Object> userStates0 = mock(SparseArray.class);
        final SparseArray<Object> userStates1 = mock(SparseArray.class);
        doReturn(psInt0).when(mMockPackageManagerInternal).getPackageStateInternal("foo_bar0");
        doReturn(psInt1).when(mMockPackageManagerInternal).getPackageStateInternal("foo_bar1");
        doReturn(userStates0).when(psInt0).getUserStates();
        doReturn(userStates1).when(psInt1).getUserStates();
        doReturn(pkgUserState0).when(userStates0).get(anyInt());
        doReturn(pkgUserState1).when(userStates1).get(anyInt());

        // Check if ResolveInfo(1) which is installed by device setup affects the preferred set and
        // this may trigger disambiguation dialog.
        assertTrue(pa.mPref.sameSet(query, true /* excludeSetupWizardPackage */, userId));
    }

    @Test
    public void testPreferredComponent_notSameSet_withAppNotInstalledByDeviceSetup() {
        // Assume we have two ResolveInfos that handle the same Intent.
        final int userId = UserHandle.USER_SYSTEM;
        final List<ResolveInfo> query = new ArrayList<>();
        query.add(createResolveInfo(0, userId));
        query.add(createResolveInfo(1, userId));
        // ResolveInfo(0) is already set as the preferred one when only it exists.
        final ComponentName component = query.get(0).getComponentInfo().getComponentName();
        final PreferredActivity pa = new PreferredActivity(new IntentFilter("TEST_ACTION"),
                0 /* match */, new ComponentName[]{component}, component, true /* always */);

        // Assume ResolveInfo(0) is preinstalled, and ResolveInfo(1) is installed by user.
        final PackageUserState pkgUserState0 = new PackageUserStateImpl().setInstallReason(
                PackageManager.INSTALL_REASON_UNKNOWN);
        final PackageUserState pkgUserState1 = new PackageUserStateImpl().setInstallReason(
                PackageManager.INSTALL_REASON_USER);
        final PackageStateInternal psInt0 = mock(PackageStateInternal.class);
        final PackageStateInternal psInt1 = mock(PackageStateInternal.class);
        final SparseArray<Object> userStates0 = mock(SparseArray.class);
        final SparseArray<Object> userStates1 = mock(SparseArray.class);
        doReturn(psInt0).when(mMockPackageManagerInternal).getPackageStateInternal("foo_bar0");
        doReturn(psInt1).when(mMockPackageManagerInternal).getPackageStateInternal("foo_bar1");
        doReturn(userStates0).when(psInt0).getUserStates();
        doReturn(userStates1).when(psInt1).getUserStates();
        doReturn(pkgUserState0).when(userStates0).get(anyInt());
        doReturn(pkgUserState1).when(userStates1).get(anyInt());

        // Check if ResolveInfo(1) which is installed by user affects the preferred set and
        // this may trigger disambiguation dialog.
        assertFalse(pa.mPref.sameSet(query, true /* excludeSetupWizardPackage */, userId));
    }

    private static ResolveInfo createResolveInfo(int i, int userId) {
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = createActivityInfo(i);
        resolveInfo.targetUserId = userId;
        return resolveInfo;
    }

    private static ActivityInfo createActivityInfo(int i) {
        final ActivityInfo ai = new ActivityInfo();
        ai.name = "activity_name" + i;
        ai.packageName = "foo_bar" + i;
        ai.enabled = true;
        ai.exported = true;
        ai.permission = null;
        ai.applicationInfo = createApplicationInfo(i, ai.packageName);
        return ai;
    }

    private static ApplicationInfo createApplicationInfo(int i, String packageName) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.name = "app_name" + i;
        ai.packageName = packageName;
        ai.enabled = true;
        return ai;
    }
}
