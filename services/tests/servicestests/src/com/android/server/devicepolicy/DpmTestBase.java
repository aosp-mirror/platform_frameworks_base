/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.test.AndroidTestCase;

import java.io.File;
import java.util.List;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;

public abstract class DpmTestBase extends AndroidTestCase {
    public static final String TAG = "DpmTest";

    protected Context mRealTestContext;
    protected DpmMockContext mMockContext;

    public File dataDir;

    public ComponentName admin1;
    public ComponentName admin2;
    public ComponentName admin3;
    public ComponentName adminNoPerm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mRealTestContext = super.getContext();

        mMockContext = new DpmMockContext(
                mRealTestContext, new File(mRealTestContext.getCacheDir(), "test-data"));

        admin1 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin1.class);
        admin2 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin2.class);
        admin3 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin3.class);
        adminNoPerm = new ComponentName(mRealTestContext, DummyDeviceAdmins.AdminNoPerm.class);
    }

    @Override
    public DpmMockContext getContext() {
        return mMockContext;
    }

    protected void markPackageAsInstalled(String packageName, ApplicationInfo ai, int userId)
            throws Exception {
        final PackageInfo pi = DpmTestUtils.cloneParcelable(
                mRealTestContext.getPackageManager().getPackageInfo(
                        mRealTestContext.getPackageName(), 0));
        assertTrue(pi.applicationInfo.flags != 0);

        if (ai != null) {
            pi.applicationInfo = ai;
        }

        doReturn(pi).when(mMockContext.ipackageManager).getPackageInfo(
                eq(packageName),
                eq(0),
                eq(userId));
    }

    protected void setUpPackageManagerForAdmin(ComponentName admin, int packageUid)
            throws Exception {
        setUpPackageManagerForAdmin(admin, packageUid,
                /* enabledSetting =*/ null, /* appTargetSdk = */ null);
    }

    protected void setUpPackageManagerForAdmin(ComponentName admin, int packageUid,
            int enabledSetting) throws Exception {
        setUpPackageManagerForAdmin(admin, packageUid, enabledSetting, /* appTargetSdk = */ null);
    }

    protected void setUpPackageManagerForAdmin(ComponentName admin, int packageUid,
            Integer enabledSetting, Integer appTargetSdk) throws Exception {
        setUpPackageManagerForFakeAdmin(admin, packageUid, enabledSetting, appTargetSdk,
                admin);
    }

    /**
     * Set up a component in the mock package manager to be an active admin.
     *
     * @param admin ComponentName that's visible to the test code, which doesn't have to exist.
     * @param copyFromAdmin package information for {@code admin} will be built based on this
     *    component's information.
     */
    protected void setUpPackageManagerForFakeAdmin(ComponentName admin, int packageUid,
            Integer enabledSetting, Integer appTargetSdk, ComponentName copyFromAdmin)
            throws Exception {

        // Set up getApplicationInfo().

        final ApplicationInfo ai = DpmTestUtils.cloneParcelable(
                mRealTestContext.getPackageManager().getApplicationInfo(
                        copyFromAdmin.getPackageName(),
                        PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS));

        ai.enabledSetting = enabledSetting == null
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                : enabledSetting;
        if (appTargetSdk != null) {
            ai.targetSdkVersion = appTargetSdk;
        }
        ai.uid = packageUid;
        ai.packageName = admin.getPackageName();
        ai.name = admin.getClassName();

        doReturn(ai).when(mMockContext.ipackageManager).getApplicationInfo(
                eq(admin.getPackageName()),
                eq(PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS),
                eq(UserHandle.getUserId(packageUid)));

        // Set up queryBroadcastReceivers().

        final Intent resolveIntent = new Intent();
        resolveIntent.setComponent(copyFromAdmin);
        final List<ResolveInfo> realResolveInfo =
                mRealTestContext.getPackageManager().queryBroadcastReceivers(
                        resolveIntent,
                        PackageManager.GET_META_DATA);
        assertNotNull(realResolveInfo);
        assertEquals(1, realResolveInfo.size());

        // We need to change AI, so set a clone.
        realResolveInfo.set(0, DpmTestUtils.cloneParcelable(realResolveInfo.get(0)));

        // We need to rewrite the UID in the activity info.
        final ActivityInfo aci = realResolveInfo.get(0).activityInfo;
        aci.applicationInfo = ai;
        aci.packageName = admin.getPackageName();
        aci.name = admin.getClassName();

        // Note we don't set up queryBroadcastReceivers.  We don't use it in DPMS.

        doReturn(aci).when(mMockContext.ipackageManager).getReceiverInfo(
                eq(admin),
                anyInt(),
                eq(UserHandle.getUserId(packageUid)));

        // Set up getPackageInfo().
        markPackageAsInstalled(admin.getPackageName(), ai, UserHandle.getUserId(packageUid));
    }
}
