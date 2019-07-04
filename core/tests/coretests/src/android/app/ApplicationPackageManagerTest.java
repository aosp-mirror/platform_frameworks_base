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

package android.app;

import static android.os.storage.VolumeInfo.STATE_MOUNTED;
import static android.os.storage.VolumeInfo.STATE_UNMOUNTED;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import androidx.test.filters.LargeTest;

import junit.framework.TestCase;

import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@LargeTest
public class ApplicationPackageManagerTest extends TestCase {
    private static final String sInternalVolPath = "/data";
    private static final String sAdoptedVolPath = "/mnt/expand/123";
    private static final String sPublicVolPath = "/emulated";
    private static final String sPrivateUnmountedVolPath = "/private";

    private static final String sInternalVolUuid = null; //StorageManager.UUID_PRIVATE_INTERNAL
    private static final String sAdoptedVolUuid = "adopted";
    private static final String sPublicVolUuid = "emulated";
    private static final String sPrivateUnmountedVolUuid = "private";

    private static final VolumeInfo sInternalVol = new VolumeInfo("private",
            VolumeInfo.TYPE_PRIVATE, null /*DiskInfo*/, null /*partGuid*/);

    private static final VolumeInfo sAdoptedVol = new VolumeInfo("adopted",
            VolumeInfo.TYPE_PRIVATE, null /*DiskInfo*/, null /*partGuid*/);

    private static final VolumeInfo sPublicVol = new VolumeInfo("public",
            VolumeInfo.TYPE_PUBLIC, null /*DiskInfo*/, null /*partGuid*/);

    private static final VolumeInfo sPrivateUnmountedVol = new VolumeInfo("private2",
            VolumeInfo.TYPE_PRIVATE, null /*DiskInfo*/, null /*partGuid*/);

    private static final List<VolumeInfo> sVolumes = new ArrayList<>();

    static {
        sInternalVol.path = sInternalVolPath;
        sInternalVol.state = STATE_MOUNTED;
        sInternalVol.fsUuid = sInternalVolUuid;

        sAdoptedVol.path = sAdoptedVolPath;
        sAdoptedVol.state = STATE_MOUNTED;
        sAdoptedVol.fsUuid = sAdoptedVolUuid;

        sPublicVol.state = STATE_MOUNTED;
        sPublicVol.path = sPublicVolPath;
        sPublicVol.fsUuid = sPublicVolUuid;

        sPrivateUnmountedVol.state = STATE_UNMOUNTED;
        sPrivateUnmountedVol.path = sPrivateUnmountedVolPath;
        sPrivateUnmountedVol.fsUuid = sPrivateUnmountedVolUuid;

        sVolumes.add(sInternalVol);
        sVolumes.add(sAdoptedVol);
        sVolumes.add(sPublicVol);
        sVolumes.add(sPrivateUnmountedVol);
    }

    private static final class MockedApplicationPackageManager extends ApplicationPackageManager {
        private boolean mForceAllowOnExternal = false;
        private boolean mAllow3rdPartyOnInternal = true;

        public MockedApplicationPackageManager() {
            super(null, null, null);
        }

        public void setForceAllowOnExternal(boolean forceAllowOnExternal) {
            mForceAllowOnExternal = forceAllowOnExternal;
        }

        public void setAllow3rdPartyOnInternal(boolean allow3rdPartyOnInternal) {
            mAllow3rdPartyOnInternal = allow3rdPartyOnInternal;
        }

        @Override
        public boolean isForceAllowOnExternal(Context context) {
            return mForceAllowOnExternal;
        }

        @Override
        public boolean isAllow3rdPartyOnInternal(Context context) {
            return mAllow3rdPartyOnInternal;
        }
    }

    private StorageManager getMockedStorageManager() {
        StorageManager storageManager = Mockito.mock(StorageManager.class);
        Mockito.when(storageManager.getVolumes()).thenReturn(sVolumes);
        Mockito.when(storageManager.findVolumeById(VolumeInfo.ID_PRIVATE_INTERNAL))
                .thenReturn(sInternalVol);
        Mockito.when(storageManager.findVolumeByUuid(sAdoptedVolUuid))
                .thenReturn(sAdoptedVol);
        Mockito.when(storageManager.findVolumeByUuid(sPublicVolUuid))
                .thenReturn(sPublicVol);
        Mockito.when(storageManager.findVolumeByUuid(sPrivateUnmountedVolUuid))
                .thenReturn(sPrivateUnmountedVol);
        return storageManager;
    }

    private void verifyReturnedVolumes(List<VolumeInfo> actualVols, VolumeInfo... exptectedVols) {
        boolean failed = false;
        if (actualVols.size() != exptectedVols.length) {
            failed = true;
        } else {
            for (VolumeInfo vol : exptectedVols) {
                if (!actualVols.contains(vol)) {
                    failed = true;
                    break;
                }
            }
        }

        if (failed) {
            fail("Wrong volumes returned.\n Expected: " + Arrays.toString(exptectedVols)
                    + "\n Actual: " + Arrays.toString(actualVols.toArray()));
        }
    }

    public void testGetCandidateVolumes_systemApp() throws Exception {
        ApplicationInfo sysAppInfo = new ApplicationInfo();
        sysAppInfo.flags = ApplicationInfo.FLAG_SYSTEM;

        StorageManager storageManager = getMockedStorageManager();
        IPackageManager pm = Mockito.mock(IPackageManager.class);

        MockedApplicationPackageManager appPkgMgr = new MockedApplicationPackageManager();

        appPkgMgr.setAllow3rdPartyOnInternal(true);
        appPkgMgr.setForceAllowOnExternal(true);
        List<VolumeInfo> candidates =
                appPkgMgr.getPackageCandidateVolumes(sysAppInfo, storageManager, pm);
        verifyReturnedVolumes(candidates, sInternalVol);

        appPkgMgr.setAllow3rdPartyOnInternal(true);
        appPkgMgr.setForceAllowOnExternal(false);
        candidates = appPkgMgr.getPackageCandidateVolumes(sysAppInfo, storageManager, pm);
        verifyReturnedVolumes(candidates, sInternalVol);

        appPkgMgr.setAllow3rdPartyOnInternal(false);
        appPkgMgr.setForceAllowOnExternal(false);
        candidates = appPkgMgr.getPackageCandidateVolumes(sysAppInfo, storageManager, pm);
        verifyReturnedVolumes(candidates, sInternalVol);

        appPkgMgr.setAllow3rdPartyOnInternal(false);
        appPkgMgr.setForceAllowOnExternal(true);
        candidates = appPkgMgr.getPackageCandidateVolumes(sysAppInfo, storageManager, pm);
        verifyReturnedVolumes(candidates, sInternalVol);
    }

    public void testGetCandidateVolumes_3rdParty_internalOnly() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        StorageManager storageManager = getMockedStorageManager();

        IPackageManager pm = Mockito.mock(IPackageManager.class);
        Mockito.when(pm.isPackageDeviceAdminOnAnyUser(Mockito.anyString())).thenReturn(false);

        MockedApplicationPackageManager appPkgMgr = new MockedApplicationPackageManager();

        // must allow 3rd party on internal, otherwise the app wouldn't have been installed before.
        appPkgMgr.setAllow3rdPartyOnInternal(true);

        // INSTALL_LOCATION_INTERNAL_ONLY AND INSTALL_LOCATION_UNSPECIFIED are treated the same.
        int[] locations = {PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED};

        for (int location : locations) {
            appInfo.installLocation = location;
            appPkgMgr.setForceAllowOnExternal(true);
            List<VolumeInfo> candidates = appPkgMgr.getPackageCandidateVolumes(
                    appInfo, storageManager, pm);
            verifyReturnedVolumes(candidates, sInternalVol, sAdoptedVol);

            appPkgMgr.setForceAllowOnExternal(false);
            candidates = appPkgMgr.getPackageCandidateVolumes(appInfo, storageManager, pm);
            verifyReturnedVolumes(candidates, sInternalVol);
        }
    }

    public void testGetCandidateVolumes_3rdParty_auto() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        StorageManager storageManager = getMockedStorageManager();

        IPackageManager pm = Mockito.mock(IPackageManager.class);

        MockedApplicationPackageManager appPkgMgr = new MockedApplicationPackageManager();
        appPkgMgr.setForceAllowOnExternal(true);

        // INSTALL_LOCATION_AUTO AND INSTALL_LOCATION_PREFER_EXTERNAL are treated the same.
        int[] locations = {PackageInfo.INSTALL_LOCATION_AUTO,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL};

        for (int location : locations) {
            appInfo.installLocation = location;
            appInfo.flags = 0;

            appInfo.volumeUuid = sInternalVolUuid;
            Mockito.when(pm.isPackageDeviceAdminOnAnyUser(Mockito.anyString())).thenReturn(false);
            appPkgMgr.setAllow3rdPartyOnInternal(true);
            List<VolumeInfo> candidates = appPkgMgr.getPackageCandidateVolumes(
                    appInfo, storageManager, pm);
            verifyReturnedVolumes(candidates, sInternalVol, sAdoptedVol);

            appInfo.volumeUuid = sInternalVolUuid;
            appPkgMgr.setAllow3rdPartyOnInternal(true);
            Mockito.when(pm.isPackageDeviceAdminOnAnyUser(Mockito.anyString())).thenReturn(true);
            candidates = appPkgMgr.getPackageCandidateVolumes(appInfo, storageManager, pm);
            verifyReturnedVolumes(candidates, sInternalVol);

            appInfo.flags = ApplicationInfo.FLAG_EXTERNAL_STORAGE;
            appInfo.volumeUuid = sAdoptedVolUuid;
            appPkgMgr.setAllow3rdPartyOnInternal(false);
            candidates = appPkgMgr.getPackageCandidateVolumes(appInfo, storageManager, pm);
            verifyReturnedVolumes(candidates, sAdoptedVol);
        }
    }
}
