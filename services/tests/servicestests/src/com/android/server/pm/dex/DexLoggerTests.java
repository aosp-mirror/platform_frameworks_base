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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.UserHandle;
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
import org.mockito.stubbing.Stubber;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DexLoggerTests {
    private static final String OWNING_PACKAGE_NAME = "package.name";
    private static final String VOLUME_UUID = "volUuid";
    private static final String FILE_PATH = "/bar/foo.jar";
    private static final int STORAGE_FLAGS = StorageManager.FLAG_STORAGE_DE;
    private static final int OWNER_UID = 43;
    private static final int OWNER_USER_ID = 44;

    // Obtained via: echo -n "foo.jar" | sha256sum
    private static final String FILENAME_HASH =
            "91D7B844D7CC9673748FF057D8DC83972280FC28537D381AA42015A9CF214B9F";

    private static final byte[] CONTENT_HASH_BYTES = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };
    private static final String CONTENT_HASH =
            "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20";
    private static final byte[] EMPTY_BYTES = {};

    private static final String EXPECTED_MESSAGE_WITHOUT_CONTENT_HASH =
            "dcl:" + FILENAME_HASH;
    private static final String EXPECTED_MESSAGE_WITH_CONTENT_HASH =
            EXPECTED_MESSAGE_WITHOUT_CONTENT_HASH + " " + CONTENT_HASH;
    private static final String EXPECTED_MESSAGE_NATIVE_WITH_CONTENT_HASH =
            "dcln:" + FILENAME_HASH + " " + CONTENT_HASH;

    @Rule public MockitoRule mockito = MockitoJUnit.rule().strictness(Strictness.LENIENT);

    @Mock IPackageManager mPM;
    @Mock Installer mInstaller;

    private DexLogger mDexLogger;

    private final ListMultimap<Integer, String> mMessagesForUid = ArrayListMultimap.create();
    private boolean mWriteTriggered = false;

    @Before
    public void setup() throws Exception {
        // Disable actually attempting to do file writes.
        PackageDynamicCodeLoading packageDynamicCodeLoading = new PackageDynamicCodeLoading() {
            @Override
            void maybeWriteAsync() {
                mWriteTriggered = true;
            }

            @Override
            protected void writeNow(Void data) {
                throw new AssertionError("These tests should never call this method.");
            }
        };

        // For test purposes capture log messages as well as sending to the event log.
        mDexLogger = new DexLogger(mPM, mInstaller, packageDynamicCodeLoading) {
            @Override
            void writeDclEvent(String subtag, int uid, String message) {
                super.writeDclEvent(subtag, uid, message);
                mMessagesForUid.put(uid, subtag + ":" + message);
            }
        };

        // Make the owning package exist in our mock PackageManager.
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.deviceProtectedDataDir = "/bar";
        appInfo.uid = OWNER_UID;
        appInfo.volumeUuid = VOLUME_UUID;
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = appInfo;

        doReturn(packageInfo).when(mPM)
                .getPackageInfo(OWNING_PACKAGE_NAME, /*flags*/ 0, OWNER_USER_ID);
    }

    @Test
    public void testOneLoader_ownFile_withFileHash() throws Exception {
        whenFileIsHashed(FILE_PATH, doReturn(CONTENT_HASH_BYTES));

        recordLoad(OWNING_PACKAGE_NAME, FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid.keys()).containsExactly(OWNER_UID);
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, EXPECTED_MESSAGE_WITH_CONTENT_HASH);

        assertThat(mWriteTriggered).isFalse();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading())
                .containsExactly(OWNING_PACKAGE_NAME);
    }

    @Test
    public void testOneLoader_ownFile_noFileHash() throws Exception {
        whenFileIsHashed(FILE_PATH, doReturn(EMPTY_BYTES));

        recordLoad(OWNING_PACKAGE_NAME, FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid.keys()).containsExactly(OWNER_UID);
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, EXPECTED_MESSAGE_WITHOUT_CONTENT_HASH);

        // File should be removed from the DCL list, since we can't hash it.
        assertThat(mWriteTriggered).isTrue();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading()).isEmpty();
    }

    @Test
    public void testOneLoader_ownFile_hashingFails() throws Exception {
        whenFileIsHashed(FILE_PATH,
                doThrow(new InstallerException("Intentional failure for test")));

        recordLoad(OWNING_PACKAGE_NAME, FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid.keys()).containsExactly(OWNER_UID);
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, EXPECTED_MESSAGE_WITHOUT_CONTENT_HASH);

        // File should be removed from the DCL list, since we can't hash it.
        assertThat(mWriteTriggered).isTrue();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading()).isEmpty();
    }

    @Test
    public void testOneLoader_ownFile_unknownPath() {
        recordLoad(OWNING_PACKAGE_NAME, "other/path");
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid).isEmpty();
        assertThat(mWriteTriggered).isTrue();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading()).isEmpty();
    }

    @Test
    public void testOneLoader_pathTraversal() throws Exception {
        String filePath = "/bar/../secret/foo.jar";
        whenFileIsHashed(filePath, doReturn(CONTENT_HASH_BYTES));
        setPackageUid(OWNING_PACKAGE_NAME, -1);

        recordLoad(OWNING_PACKAGE_NAME, filePath);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid).isEmpty();
    }

    @Test
    public void testOneLoader_differentOwner() throws Exception {
        whenFileIsHashed(FILE_PATH, doReturn(CONTENT_HASH_BYTES));
        setPackageUid("other.package.name", 1001);

        recordLoad("other.package.name", FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid.keys()).containsExactly(1001);
        assertThat(mMessagesForUid).containsEntry(1001, EXPECTED_MESSAGE_WITH_CONTENT_HASH);
        assertThat(mWriteTriggered).isFalse();
    }

    @Test
    public void testOneLoader_differentOwner_uninstalled() throws Exception {
        whenFileIsHashed(FILE_PATH, doReturn(CONTENT_HASH_BYTES));
        setPackageUid("other.package.name", -1);

        recordLoad("other.package.name", FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid).isEmpty();
        assertThat(mWriteTriggered).isFalse();
    }

    @Test
    public void testNativeCodeLoad() throws Exception {
        whenFileIsHashed(FILE_PATH, doReturn(CONTENT_HASH_BYTES));

        recordLoadNative(FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid.keys()).containsExactly(OWNER_UID);
        assertThat(mMessagesForUid)
                .containsEntry(OWNER_UID, EXPECTED_MESSAGE_NATIVE_WITH_CONTENT_HASH);

        assertThat(mWriteTriggered).isFalse();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading())
                .containsExactly(OWNING_PACKAGE_NAME);
    }

    @Test
    public void testMultipleLoadersAndFiles() throws Exception {
        String otherDexPath = "/bar/nosuchdir/foo.jar";
        whenFileIsHashed(FILE_PATH, doReturn(CONTENT_HASH_BYTES));
        whenFileIsHashed(otherDexPath, doReturn(EMPTY_BYTES));
        setPackageUid("other.package.name1", 1001);
        setPackageUid("other.package.name2", 1002);

        recordLoad("other.package.name1", FILE_PATH);
        recordLoad("other.package.name1", otherDexPath);
        recordLoad("other.package.name2", FILE_PATH);
        recordLoad(OWNING_PACKAGE_NAME, FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid.keys()).containsExactly(1001, 1001, 1002, OWNER_UID);
        assertThat(mMessagesForUid).containsEntry(1001, EXPECTED_MESSAGE_WITH_CONTENT_HASH);
        assertThat(mMessagesForUid).containsEntry(1001, EXPECTED_MESSAGE_WITHOUT_CONTENT_HASH);
        assertThat(mMessagesForUid).containsEntry(1002, EXPECTED_MESSAGE_WITH_CONTENT_HASH);
        assertThat(mMessagesForUid).containsEntry(OWNER_UID, EXPECTED_MESSAGE_WITH_CONTENT_HASH);

        assertThat(mWriteTriggered).isTrue();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading())
                .containsExactly(OWNING_PACKAGE_NAME);

        // Check the DexLogger caching is working
        verify(mPM, atMost(1)).getPackageInfo(OWNING_PACKAGE_NAME, /*flags*/ 0, OWNER_USER_ID);
    }

    @Test
    public void testUnknownOwner() {
        reset(mPM);
        recordLoad(OWNING_PACKAGE_NAME, FILE_PATH);
        mDexLogger.logDynamicCodeLoading("other.package.name");

        assertThat(mMessagesForUid).isEmpty();
        assertThat(mWriteTriggered).isFalse();
        verifyZeroInteractions(mPM);
    }

    @Test
    public void testUninstalledPackage() {
        reset(mPM);
        recordLoad(OWNING_PACKAGE_NAME, FILE_PATH);
        mDexLogger.logDynamicCodeLoading(OWNING_PACKAGE_NAME);

        assertThat(mMessagesForUid).isEmpty();
        assertThat(mWriteTriggered).isTrue();
        assertThat(mDexLogger.getAllPackagesWithDynamicCodeLoading()).isEmpty();
    }

    private void setPackageUid(String packageName, int uid) throws Exception {
        doReturn(uid).when(mPM).getPackageUid(packageName, /*flags*/ 0, OWNER_USER_ID);
    }

    private void whenFileIsHashed(String dexPath, Stubber stubber) throws Exception {
        stubber.when(mInstaller).hashSecondaryDexFile(
                dexPath, OWNING_PACKAGE_NAME, OWNER_UID, VOLUME_UUID, STORAGE_FLAGS);
    }

    private void recordLoad(String loadingPackageName, String dexPath) {
        mDexLogger.recordDex(OWNER_USER_ID, dexPath, OWNING_PACKAGE_NAME, loadingPackageName);
        mWriteTriggered = false;
    }

    private void recordLoadNative(String nativePath) throws Exception {
        int loadingUid = UserHandle.getUid(OWNER_USER_ID, OWNER_UID);
        String[] packageNames = { OWNING_PACKAGE_NAME };
        when(mPM.getPackagesForUid(loadingUid)).thenReturn(packageNames);

        mDexLogger.recordNative(loadingUid, nativePath);
        mWriteTriggered = false;
    }
}
