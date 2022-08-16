/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.os.storage.VolumeInfo.STATE_MOUNTED;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.platform.test.annotations.Presubmit;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.internal.content.InstallLocationUtils;

import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Presubmit
public class InstallLocationUtilsTests extends AndroidTestCase {
    private static final boolean localLOGV = true;
    public static final String TAG = "PackageHelperTests";

    private static final String sInternalVolPath = "/data";
    private static final String sAdoptedVolPath = "/mnt/expand/123";
    private static final String sPublicVolPath = "/emulated";

    private static final String sInternalVolUuid = StorageManager.UUID_PRIVATE_INTERNAL;
    private static final String sAdoptedVolUuid = "adopted";
    private static final String sPublicVolUuid = "emulated";

    private static final long sInternalSize = 20000;
    private static final long sAdoptedSize = 10000;
    private static final long sPublicSize = 1000000;

    private static StorageManager sStorageManager;

    private static StorageManager createStorageManagerMock() throws Exception {
        VolumeInfo internalVol = new VolumeInfo("private",
                VolumeInfo.TYPE_PRIVATE, null /*DiskInfo*/, null /*partGuid*/);
        internalVol.path = sInternalVolPath;
        internalVol.state = STATE_MOUNTED;
        internalVol.fsUuid = sInternalVolUuid;

        VolumeInfo adoptedVol = new VolumeInfo("adopted",
                VolumeInfo.TYPE_PRIVATE, null /*DiskInfo*/, null /*partGuid*/);
        adoptedVol.path = sAdoptedVolPath;
        adoptedVol.state = STATE_MOUNTED;
        adoptedVol.fsUuid = sAdoptedVolUuid;

        VolumeInfo publicVol = new VolumeInfo("public",
                VolumeInfo.TYPE_PUBLIC, null /*DiskInfo*/, null /*partGuid*/);
        publicVol.state = STATE_MOUNTED;
        publicVol.path = sPublicVolPath;
        publicVol.fsUuid = sPublicVolUuid;

        List<VolumeInfo> volumes = new ArrayList<>();
        volumes.add(internalVol);
        volumes.add(adoptedVol);
        volumes.add(publicVol);

        StorageManager storageManager = Mockito.mock(StorageManager.class);
        Mockito.when(storageManager.getVolumes()).thenReturn(volumes);

        File internalFile = new File(sInternalVolPath);
        File adoptedFile = new File(sAdoptedVolPath);
        File publicFile = new File(sPublicVolPath);
        UUID internalUuid = UUID.randomUUID();
        UUID adoptedUuid = UUID.randomUUID();
        UUID publicUuid = UUID.randomUUID();
        Mockito.when(storageManager.getStorageBytesUntilLow(internalFile))
                .thenReturn(sInternalSize);
        Mockito.when(storageManager.getStorageBytesUntilLow(adoptedFile)).thenReturn(sAdoptedSize);
        Mockito.when(storageManager.getStorageBytesUntilLow(publicFile)).thenReturn(sPublicSize);
        Mockito.when(storageManager.getUuidForPath(Mockito.eq(internalFile)))
                .thenReturn(internalUuid);
        Mockito.when(storageManager.getUuidForPath(Mockito.eq(adoptedFile)))
                .thenReturn(adoptedUuid);
        Mockito.when(storageManager.getUuidForPath(Mockito.eq(publicFile))).thenReturn(publicUuid);
        Mockito.when(storageManager.getAllocatableBytes(Mockito.eq(internalUuid), Mockito.anyInt()))
                .thenReturn(sInternalSize);
        Mockito.when(storageManager.getAllocatableBytes(Mockito.eq(adoptedUuid), Mockito.anyInt()))
                .thenReturn(sAdoptedSize);
        Mockito.when(storageManager.getAllocatableBytes(Mockito.eq(publicUuid), Mockito.anyInt()))
                .thenReturn(sPublicSize);
        return storageManager;
    }

    private static final class MockedInterface extends InstallLocationUtils.TestableInterface {
        private boolean mForceAllowOnExternal = false;
        private boolean mAllow3rdPartyOnInternal = true;
        private ApplicationInfo mApplicationInfo = null;

        public void setMockValues(ApplicationInfo applicationInfo,
                boolean forceAllowOnExternal, boolean allow3rdPartyOnInternal) {
            mForceAllowOnExternal = forceAllowOnExternal;
            mAllow3rdPartyOnInternal = allow3rdPartyOnInternal;
            mApplicationInfo = applicationInfo;
        }

        @Override
        public StorageManager getStorageManager(Context context) {
            return sStorageManager;
        }

        @Override
        public boolean getForceAllowOnExternalSetting(Context context) {
            return mForceAllowOnExternal;
        }

        @Override
        public boolean getAllow3rdPartyOnInternalConfig(Context context) {
            return mAllow3rdPartyOnInternal;
        }

        @Override
        public ApplicationInfo getExistingAppInfo(Context context, String packagename) {
            return mApplicationInfo;
        }

        @Override
        public File getDataDirectory() {
            return new File(sInternalVolPath);
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sStorageManager = createStorageManagerMock();
        if (localLOGV) Log.i(TAG, "Cleaning out old test containers");
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        sStorageManager = null;
        if (localLOGV) Log.i(TAG, "Cleaning out old test containers");
    }

    public void testResolveInstallVolumeInternal_SystemApp() throws IOException {
        ApplicationInfo systemAppInfo = new ApplicationInfo();
        systemAppInfo.flags = ApplicationInfo.FLAG_SYSTEM;

        // All test cases for when the system app fits on internal.
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(systemAppInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        String volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            1 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(StorageManager.UUID_PRIVATE_INTERNAL, volume);

        mockedInterface.setMockValues(systemAppInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                1 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(StorageManager.UUID_PRIVATE_INTERNAL, volume);

        mockedInterface.setMockValues(systemAppInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                1 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(StorageManager.UUID_PRIVATE_INTERNAL, volume);

        mockedInterface.setMockValues(systemAppInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                1 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(StorageManager.UUID_PRIVATE_INTERNAL, volume);


        // All test cases for when the system app does not fit on internal.
        // Exception should be thrown.
        mockedInterface.setMockValues(systemAppInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location*/, 1000000 /*size bytes*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch(IOException e) {
            // expected
        }

        mockedInterface.setMockValues(systemAppInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location*/, 1000000 /*size bytes*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch(IOException e) {
            // expected
        }

        mockedInterface.setMockValues(systemAppInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location*/, 1000000 /*size bytes*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch(IOException e) {
            // expected
        }

        mockedInterface.setMockValues(systemAppInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location*/, 1000000 /*size bytes*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch(IOException e) {
            // expected
        }
    }

    public void testResolveInstallVolumeInternal_3rdParty_existing_not_too_big()
            throws IOException {
        // Existing apps always stay on the same volume.
        // Test cases for existing app on internal.
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.volumeUuid = sInternalVolUuid;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        String volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sInternalVolUuid, volume);

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sInternalVolUuid, volume);
    }

    public void testResolveInstallVolumeInternal_3rdParty_existing_not_too_big_adopted()
            throws IOException {
        // Test cases for existing app on the adopted media.
        ApplicationInfo appInfo = new ApplicationInfo();
        MockedInterface mockedInterface = new MockedInterface();
        String volume;
        appInfo.volumeUuid = sAdoptedVolUuid;
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            0 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);

        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);
    }

    public void testResolveInstallVolumeAdopted_3rdParty_existing_too_big() {
        // Test: update size too big, will throw exception.
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.volumeUuid = sAdoptedVolUuid;

        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    0 /*install location*/, 10000001 /*BIG size, won't fit*/, mockedInterface);
            fail("Expected exception was not thrown " + appInfo.volumeUuid);
        } catch (IOException e) {
            //expected
        }

        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    0 /*install location*/, 10000001 /*BIG size, won't fit*/, mockedInterface);
            fail("Expected exception was not thrown " + appInfo.volumeUuid);
        } catch (IOException e) {
            //expected
        }

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    0 /*install location*/, 10000001 /*BIG size, won't fit*/, mockedInterface);
            fail("Expected exception was not thrown " + appInfo.volumeUuid);
        } catch (IOException e) {
            //expected
        }

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        try {
            InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    0 /*install location*/, 10000001 /*BIG size, won't fit*/, mockedInterface);
            fail("Expected exception was not thrown " + appInfo.volumeUuid);
        } catch (IOException e) {
            //expected
        }
    }

    public void testResolveInstallVolumeInternal_3rdParty_auto() throws IOException {
        ApplicationInfo appInfo = null;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        String volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            0 /*install location auto*/, 1000 /*size bytes*/, mockedInterface);
        // Should return the volume with bigger available space.
        assertEquals(sInternalVolUuid, volume);

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location auto*/, 1000 /*size bytes*/, mockedInterface);
        // Should return the volume with bigger available space.
        assertEquals(sInternalVolUuid, volume);

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location auto*/, 1000 /*size bytes*/, mockedInterface);
        // Should return the volume with bigger available space.
        assertEquals(sAdoptedVolUuid, volume);

        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location auto*/, 1000 /*size bytes*/, mockedInterface);
        // Should return the volume with bigger available space.
        assertEquals(sAdoptedVolUuid, volume);


    }

    public void testResolveInstallVolumeInternal_3rdParty_internal_only() throws IOException {
        ApplicationInfo appInfo = null;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        String volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            1 /*install location internal ONLY*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sInternalVolUuid, volume);

        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                1 /*install location internal ONLY*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sInternalVolUuid, volume);

        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        try {
            volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location internal only*/, 1000 /*size bytes*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch (IOException e) {
            //expected
        }

        appInfo = null;
        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                1 /*install location internal only*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);
    }

    public void testResolveInstallVolumeInternal_3rdParty_not_allowed_on_internal()
        throws IOException {
        ApplicationInfo appInfo = null;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        String volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            0 /*install location auto*/, 1000 /*size bytes*/, mockedInterface);
        // Should return the non-internal volume.
        assertEquals(sAdoptedVolUuid, volume);

        appInfo = null;
        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                0 /*install location auto*/, 1000 /*size bytes*/, mockedInterface);
        // Should return the non-internal volume.
        assertEquals(sAdoptedVolUuid, volume);
    }

    public void testResolveInstallVolumeInternal_3rdParty_internal_only_too_big() {
        ApplicationInfo appInfo = null;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                true /*allow 3rd party on internal*/);
        String volume = null;
        try {
            volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location internal ONLY*/,
                    1000000 /*size too big*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch (IOException e) {
            //expected
        }
    }

    public void testResolveInstallVolumeInternal_3rdParty_internal_only_not_allowed()
        throws IOException {
        ApplicationInfo appInfo = null;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, false /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        String volume = null;
        try {
            volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
                    1 /*install location internal only*/, 1000 /*size bytes*/, mockedInterface);
            fail("Expected exception in resolveInstallVolume was not thrown");
        } catch (IOException e) {
            //expected
        }

        appInfo = null;
        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            1 /*install location internal only*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);

    }

    public void testResolveInstallVolumeInternal_3rdParty_internal_only_forced_to_external()
        throws IOException {
        // New/existing installation: New
        // app request location: Internal Only
        // 3rd party allowed on internal: False
        // Force allow external in setting: True
        // Size fit? Yes
        ApplicationInfo appInfo = null;
        MockedInterface mockedInterface = new MockedInterface();
        mockedInterface.setMockValues(appInfo, true /*force allow on external*/,
                false /*allow 3rd party on internal*/);
        String volume = null;
        volume = InstallLocationUtils.resolveInstallVolume(getContext(), "package.name",
            1 /*install location internal only*/, 1000 /*size bytes*/, mockedInterface);
        assertEquals(sAdoptedVolUuid, volume);
    }
}
