/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.pm.dex;

import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.storage.StorageManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DexLoggerTests {
    private static final String PACKAGE_NAME = "package.name";
    private static final String VOLUME_UUID = "volUuid";
    private static final String DEX_PATH = "/bar/foo.jar";
    private static final int STORAGE_FLAGS = StorageManager.FLAG_STORAGE_DE;
    private static final int OWNER_UID = 43;
    private static final int OWNER_USER_ID = 44;

    // Obtained via: echo -n "foo.jar" | sha256sum
    private static final String DEX_FILENAME_HASH =
            "91D7B844D7CC9673748FF057D8DC83972280FC28537D381AA42015A9CF214B9F";

    private static final byte[] CONTENT_HASH_BYTES = new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };
    private static final String CONTENT_HASH =
            "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20";

    @Rule public MockitoRule mockito = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

    @Mock IPackageManager mPM;
    @Mock Installer mInstaller;
    private final Object mInstallLock = new Object();

    private DexManager.Listener mListener;

    private final ListMultimap<Integer, String> mMessagesForUid = ArrayListMultimap.create();

    @Before
    public void setup() {
        // For test purposes capture log messages as well as sending to the event log.
        mListener = new DexLogger(mPM, mInstaller, mInstallLock) {
                @Override
                void writeDclEvent(int uid, String message) {
                    super.writeDclEvent(uid, message);
                    mMessagesForUid.put(uid, message);
                }
            };
    }

    @Test
    public void testSingleAppWithFileHash() throws Exception {
        doReturn(CONTENT_HASH_BYTES).when(mInstaller).hashSecondaryDexFile(
            DEX_PATH, PACKAGE_NAME, OWNER_UID, VOLUME_UUID, STORAGE_FLAGS);

        runOnReconcile();

        assertThat(mMessagesForUid.keySet()).containsExactly(OWNER_UID);
        String expectedMessage = DEX_FILENAME_HASH + " " + CONTENT_HASH;
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, expectedMessage);
    }

    @Test
    public void testSingleAppNoFileHash() throws Exception {
        doReturn(new byte[] { }).when(mInstaller).hashSecondaryDexFile(
            DEX_PATH, PACKAGE_NAME, OWNER_UID, VOLUME_UUID, STORAGE_FLAGS);

        runOnReconcile();

        assertThat(mMessagesForUid.keySet()).containsExactly(OWNER_UID);
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, DEX_FILENAME_HASH);
    }

    @Test
    public void testSingleAppHashFails() throws Exception {
        doThrow(new InstallerException("Testing failure")).when(mInstaller).hashSecondaryDexFile(
            DEX_PATH, PACKAGE_NAME, OWNER_UID, VOLUME_UUID, STORAGE_FLAGS);

        runOnReconcile();

        assertThat(mMessagesForUid).isEmpty();
    }

    @Test
    public void testOtherApps() throws Exception {
        doReturn(CONTENT_HASH_BYTES).when(mInstaller).hashSecondaryDexFile(
            DEX_PATH, PACKAGE_NAME, OWNER_UID, VOLUME_UUID, STORAGE_FLAGS);

        // Simulate three packages from two different UIDs
        String packageName1 = "other1.package.name";
        String packageName2 = "other2.package.name";
        String packageName3 = "other3.package.name";
        int uid1 = 1001;
        int uid2 = 1002;

        doReturn(uid1).when(mPM).getPackageUid(packageName1, 0, OWNER_USER_ID);
        doReturn(uid2).when(mPM).getPackageUid(packageName2, 0, OWNER_USER_ID);
        doReturn(uid1).when(mPM).getPackageUid(packageName3, 0, OWNER_USER_ID);

        runOnReconcile(packageName1, packageName2, packageName3);

        assertThat(mMessagesForUid.keySet()).containsExactly(OWNER_UID, uid1, uid2);

        String expectedMessage = DEX_FILENAME_HASH + " " + CONTENT_HASH;
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, expectedMessage);
        assertThat(mMessagesForUid).containsEntry(uid1, expectedMessage);
        assertThat(mMessagesForUid).containsEntry(uid2, expectedMessage);
    }

    private void runOnReconcile(String... otherPackageNames) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = PACKAGE_NAME;
        appInfo.volumeUuid = VOLUME_UUID;
        appInfo.uid = OWNER_UID;

        boolean isUsedByOtherApps = otherPackageNames.length > 0;
        DexUseInfo dexUseInfo = new DexUseInfo(
            isUsedByOtherApps, OWNER_USER_ID, /* classLoaderContext */ null, /* loaderIsa */ null);
        dexUseInfo.getLoadingPackages().addAll(Arrays.asList(otherPackageNames));

        mListener.onReconcileSecondaryDexFile(appInfo, dexUseInfo, DEX_PATH, STORAGE_FLAGS);
    }
}
