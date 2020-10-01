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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.annotation.RawRes;
import android.app.admin.DevicePolicyManager;
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

import java.io.InputStream;
import java.util.List;

public abstract class DpmTestBase extends AndroidTestCase {
    public static final String TAG = "DpmTest";

    protected Context mRealTestContext;
    protected DpmMockContext mMockContext;
    private MockSystemServices mServices;

    public ComponentName admin1;
    public ComponentName admin2;
    public ComponentName admin3;
    public ComponentName adminAnotherPackage;
    public ComponentName adminNoPerm;
    public ComponentName delegateCertInstaller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mRealTestContext = super.getContext();

        mServices = new MockSystemServices(mRealTestContext, "test-data");
        mMockContext = new DpmMockContext(mServices, mRealTestContext);

        admin1 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin1.class);
        admin2 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin2.class);
        admin3 = new ComponentName(mRealTestContext, DummyDeviceAdmins.Admin3.class);
        adminAnotherPackage = new ComponentName(DpmMockContext.ANOTHER_PACKAGE_NAME,
                "whatever.random.class");
        adminNoPerm = new ComponentName(mRealTestContext, DummyDeviceAdmins.AdminNoPerm.class);
        delegateCertInstaller = new ComponentName(DpmMockContext.DELEGATE_PACKAGE_NAME,
                "some.random.class");
        mockSystemPropertiesToReturnDefault();
    }

    @Override
    public DpmMockContext getContext() {
        return mMockContext;
    }

    public MockSystemServices getServices() {
        return mServices;
    }

    protected void sendBroadcastWithUser(DevicePolicyManagerServiceTestable dpms, String action,
            int userHandle) throws Exception {
        final Intent intent = new Intent(action);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
        getServices().injectBroadcast(getContext(), intent, userHandle);
        flushTasks(dpms);
    }

    protected void flushTasks(DevicePolicyManagerServiceTestable dpms) throws Exception {
        dpms.mHandler.runWithScissors(() -> { }, 0 /*now*/);
        dpms.mBackgroundHandler.runWithScissors(() -> { }, 0 /*now*/);

        // We can't let exceptions happen on the background thread. Throw them here if they happen
        // so they still cause the test to fail despite being suppressed.
        getServices().rethrowBackgroundBroadcastExceptions();
    }

    protected interface DpmRunnable {
        void run(DevicePolicyManager dpm) throws Exception;
    }

    /**
     * Simulate an RPC from {@param caller} to the service context ({@link #mMockContext}).
     *
     * The caller sees its own context. The server also sees its own separate context, with the
     * appropriate calling UID and calling permissions fields already set up.
     */
    protected void runAsCaller(DpmMockContext caller, DevicePolicyManagerServiceTestable dpms,
            DpmRunnable action) {
        final DpmMockContext serviceContext = mMockContext;

        // Save calling UID and PID before clearing identity so we don't run into aliasing issues.
        final int callingUid = caller.binder.callingUid;
        final int callingPid = caller.binder.callingPid;

        final long origId = serviceContext.binder.clearCallingIdentity();
        try {
            serviceContext.binder.callingUid = callingUid;
            serviceContext.binder.callingPid = callingPid;
            serviceContext.binder.callingPermissions.put(callingUid, caller.permissions);
            action.run(new DevicePolicyManagerTestable(caller, dpms));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            serviceContext.binder.restoreCallingIdentity(origId);
        }
    }

    private void markPackageAsInstalled(String packageName, ApplicationInfo ai, int userId)
            throws Exception {
        final PackageInfo pi = DpmTestUtils.cloneParcelable(
                mRealTestContext.getPackageManager().getPackageInfo(
                        mRealTestContext.getPackageName(), 0));
        assertTrue(pi.applicationInfo.flags != 0);

        if (ai != null) {
            pi.applicationInfo = ai;
        }

        doReturn(pi).when(mServices.ipackageManager).getPackageInfo(
                eq(packageName),
                eq(0),
                eq(userId));

        doReturn(ai.uid).when(mServices.packageManager).getPackageUidAsUser(
                eq(packageName),
                eq(userId));
    }

    protected void markDelegatedCertInstallerAsInstalled() throws Exception {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        ai.flags = ApplicationInfo.FLAG_HAS_CODE;
        // Mark the package as installed on the work profile.
        ai.uid = UserHandle.getUid(DpmMockContext.CALLER_USER_HANDLE,
                DpmMockContext.DELEGATE_CERT_INSTALLER_UID);
        ai.packageName = delegateCertInstaller.getPackageName();
        ai.name = delegateCertInstaller.getClassName();

        markPackageAsInstalled(delegateCertInstaller.getPackageName(), ai,
                DpmMockContext.CALLER_USER_HANDLE);
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

    protected void setUpPackageManagerForFakeAdmin(ComponentName admin, int packageUid,
            ComponentName copyFromAdmin)
            throws Exception {
        setUpPackageManagerForFakeAdmin(admin, packageUid,
                /* enabledSetting =*/ null, /* appTargetSdk = */ null, copyFromAdmin);
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

        doReturn(ai).when(mServices.ipackageManager).getApplicationInfo(
                eq(admin.getPackageName()),
                anyInt(),
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

        doReturn(aci).when(mServices.ipackageManager).getReceiverInfo(
                eq(admin),
                anyInt(),
                eq(UserHandle.getUserId(packageUid)));

        doReturn(new String[] {admin.getPackageName()}).when(mServices.ipackageManager)
            .getPackagesForUid(eq(packageUid));
        // Set up getPackageInfo().
        markPackageAsInstalled(admin.getPackageName(), ai, UserHandle.getUserId(packageUid));
    }

    /**
     * By default, system properties are mocked to return default value. Override the mock if you
     * want a specific value.
     */
    private void mockSystemPropertiesToReturnDefault() {
        when(getServices().systemProperties.get(
                anyString(), anyString())).thenAnswer(
                invocation -> invocation.getArguments()[1]
        );

        when(getServices().systemProperties.getBoolean(
                anyString(), anyBoolean())).thenAnswer(
                invocation -> invocation.getArguments()[1]
        );

        when(getServices().systemProperties.getLong(
                anyString(), anyLong())).thenAnswer(
                invocation -> invocation.getArguments()[1]
        );
    }

    protected InputStream getRawStream(@RawRes int id) {
        return mRealTestContext.getResources().openRawResource(id);
    }
}
