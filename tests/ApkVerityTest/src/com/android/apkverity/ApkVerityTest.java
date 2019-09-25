/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.apkverity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.RootPermissionTest;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashSet;

/**
 * This test makes sure app installs with fs-verity signature, and on-access verification works.
 *
 * <p>When an app is installed, all or none of the files should have their corresponding .fsv_sig
 * signature file. Otherwise, install will fail.
 *
 * <p>Once installed, file protected by fs-verity is verified by kernel every time a block is loaded
 * from disk to memory. The file is immutable by design, enforced by filesystem.
 *
 * <p>In order to make sure a block of the file is readable only if the underlying block on disk
 * stay intact, the test needs to bypass the filesystem and tampers with the corresponding physical
 * address against the block device.
 *
 * <p>Requirements to run this test:
 * <ul>
 *   <li>Device is rootable</li>
 *   <li>The filesystem supports fs-verity</li>
 *   <li>The feature flag is enabled</li>
 * </ul>
 */
@RootPermissionTest
@RunWith(DeviceJUnit4ClassRunner.class)
public class ApkVerityTest extends BaseHostJUnit4Test {
    private static final String TARGET_PACKAGE = "com.android.apkverity";

    private static final String BASE_APK = "ApkVerityTestApp.apk";
    private static final String BASE_APK_DM = "ApkVerityTestApp.dm";
    private static final String SPLIT_APK = "ApkVerityTestAppSplit.apk";
    private static final String SPLIT_APK_DM = "ApkVerityTestAppSplit.dm";

    private static final String INSTALLED_BASE_APK = "base.apk";
    private static final String INSTALLED_BASE_DM = "base.dm";
    private static final String INSTALLED_SPLIT_APK = "split_feature_x.apk";
    private static final String INSTALLED_SPLIT_DM = "split_feature_x.dm";
    private static final String INSTALLED_BASE_APK_FSV_SIG = "base.apk.fsv_sig";
    private static final String INSTALLED_BASE_DM_FSV_SIG = "base.dm.fsv_sig";
    private static final String INSTALLED_SPLIT_APK_FSV_SIG = "split_feature_x.apk.fsv_sig";
    private static final String INSTALLED_SPLIT_DM_FSV_SIG = "split_feature_x.dm.fsv_sig";

    private static final String DAMAGING_EXECUTABLE = "/data/local/tmp/block_device_writer";
    private static final String CERT_PATH = "/data/local/tmp/ApkVerityTestCert.der";

    private static final String APK_VERITY_STANDARD_MODE = "2";

    /** Only 4K page is supported by fs-verity currently. */
    private static final int FSVERITY_PAGE_SIZE = 4096;

    private ITestDevice mDevice;
    private String mKeyId;

    @Before
    public void setUp() throws DeviceNotAvailableException {
        mDevice = getDevice();

        String apkVerityMode = mDevice.getProperty("ro.apk_verity.mode");
        assumeTrue(APK_VERITY_STANDARD_MODE.equals(apkVerityMode));

        mKeyId = expectRemoteCommandToSucceed(
                "mini-keyctl padd asymmetric fsv_test .fs-verity < " + CERT_PATH).trim();
        if (!mKeyId.matches("^\\d+$")) {
            String keyId = mKeyId;
            mKeyId = null;
            fail("Key ID is not decimal: " + keyId);
        }

        uninstallPackage(TARGET_PACKAGE);
    }

    @After
    public void tearDown() throws DeviceNotAvailableException {
        uninstallPackage(TARGET_PACKAGE);

        if (mKeyId != null) {
            expectRemoteCommandToSucceed("mini-keyctl unlink " + mKeyId + " .fs-verity");
        }
    }

    @Test
    public void testFsverityKernelSupports() throws DeviceNotAvailableException {
        ITestDevice.MountPointInfo mountPoint = mDevice.getMountPointInfo("/data");
        expectRemoteCommandToSucceed("test -f /sys/fs/" + mountPoint.type + "/features/verity");
    }

    @Test
    public void testInstallBase() throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));

        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG);
        verifyInstalledFilesHaveFsverity();
    }

    @Test
    public void testInstallBaseWithWrongSignature()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFile(BASE_APK)
                .addFile(SPLIT_APK_DM + ".fsv_sig",
                        BASE_APK + ".fsv_sig")
                .runExpectingFailure();
    }

    @Test
    public void testInstallBaseWithSplit()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .addFileAndSignature(SPLIT_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));

        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG,
                INSTALLED_SPLIT_APK,
                INSTALLED_SPLIT_APK_FSV_SIG);
        verifyInstalledFilesHaveFsverity();
    }

    @Test
    public void testInstallBaseWithDm() throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .addFileAndSignature(BASE_APK_DM)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));

        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG,
                INSTALLED_BASE_DM,
                INSTALLED_BASE_DM_FSV_SIG);
        verifyInstalledFilesHaveFsverity();
    }

    @Test
    public void testInstallEverything() throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .addFileAndSignature(BASE_APK_DM)
                .addFileAndSignature(SPLIT_APK)
                .addFileAndSignature(SPLIT_APK_DM)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));

        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG,
                INSTALLED_BASE_DM,
                INSTALLED_BASE_DM_FSV_SIG,
                INSTALLED_SPLIT_APK,
                INSTALLED_SPLIT_APK_FSV_SIG,
                INSTALLED_SPLIT_DM,
                INSTALLED_SPLIT_DM_FSV_SIG);
        verifyInstalledFilesHaveFsverity();
    }

    @Test
    public void testInstallSplitOnly()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));
        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG);

        new InstallMultiple()
                .inheritFrom(TARGET_PACKAGE)
                .addFileAndSignature(SPLIT_APK)
                .run();

        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG,
                INSTALLED_SPLIT_APK,
                INSTALLED_SPLIT_APK_FSV_SIG);
        verifyInstalledFilesHaveFsverity();
    }

    @Test
    public void testInstallSplitOnlyMissingSignature()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));
        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG);

        new InstallMultiple()
                .inheritFrom(TARGET_PACKAGE)
                .addFile(SPLIT_APK)
                .runExpectingFailure();
    }

    @Test
    public void testInstallSplitOnlyWithoutBaseSignature()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFile(BASE_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));
        verifyInstalledFiles(INSTALLED_BASE_APK);

        new InstallMultiple()
                .inheritFrom(TARGET_PACKAGE)
                .addFileAndSignature(SPLIT_APK)
                .run();
        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_SPLIT_APK,
                INSTALLED_SPLIT_APK_FSV_SIG);

    }

    @Test
    public void testInstallOnlyBaseHasFsvSig()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .addFile(BASE_APK_DM)
                .addFile(SPLIT_APK)
                .addFile(SPLIT_APK_DM)
                .runExpectingFailure();
    }

    @Test
    public void testInstallOnlyDmHasFsvSig()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFile(BASE_APK)
                .addFileAndSignature(BASE_APK_DM)
                .addFile(SPLIT_APK)
                .addFile(SPLIT_APK_DM)
                .runExpectingFailure();
    }

    @Test
    public void testInstallOnlySplitHasFsvSig()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFile(BASE_APK)
                .addFile(BASE_APK_DM)
                .addFileAndSignature(SPLIT_APK)
                .addFile(SPLIT_APK_DM)
                .runExpectingFailure();
    }

    @Test
    public void testInstallBaseWithFsvSigThenSplitWithout()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFileAndSignature(BASE_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));
        verifyInstalledFiles(
                INSTALLED_BASE_APK,
                INSTALLED_BASE_APK_FSV_SIG);

        new InstallMultiple()
                .addFile(SPLIT_APK)
                .runExpectingFailure();
    }

    @Test
    public void testInstallBaseWithoutFsvSigThenSplitWith()
            throws DeviceNotAvailableException, FileNotFoundException {
        new InstallMultiple()
                .addFile(BASE_APK)
                .run();
        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));
        verifyInstalledFiles(INSTALLED_BASE_APK);

        new InstallMultiple()
                .addFileAndSignature(SPLIT_APK)
                .runExpectingFailure();
    }

    @Test
    public void testFsverityFileIsImmutableAndReadable() throws DeviceNotAvailableException {
        new InstallMultiple().addFileAndSignature(BASE_APK).run();
        String apkPath = getApkPath(TARGET_PACKAGE);

        assertNotNull(getDevice().getAppPackageInfo(TARGET_PACKAGE));
        expectRemoteCommandToFail("echo -n '' >> " + apkPath);
        expectRemoteCommandToSucceed("cat " + apkPath + " > /dev/null");
    }

    @Test
    public void testFsverityFailToReadModifiedBlockAtFront() throws DeviceNotAvailableException {
        new InstallMultiple().addFileAndSignature(BASE_APK).run();
        String apkPath = getApkPath(TARGET_PACKAGE);

        long apkSize = getFileSizeInBytes(apkPath);
        long offsetFirstByte = 0;

        // The first two pages should be both readable at first.
        assertTrue(canReadByte(apkPath, offsetFirstByte));
        if (apkSize > offsetFirstByte + FSVERITY_PAGE_SIZE) {
            assertTrue(canReadByte(apkPath, offsetFirstByte + FSVERITY_PAGE_SIZE));
        }

        // Damage the file directly against the block device.
        damageFileAgainstBlockDevice(apkPath, offsetFirstByte);

        // Expect actual read from disk to fail but only at damaged page.
        dropCaches();
        assertFalse(canReadByte(apkPath, offsetFirstByte));
        if (apkSize > offsetFirstByte + FSVERITY_PAGE_SIZE) {
            long lastByteOfTheSamePage =
                    offsetFirstByte % FSVERITY_PAGE_SIZE + FSVERITY_PAGE_SIZE - 1;
            assertFalse(canReadByte(apkPath, lastByteOfTheSamePage));
            assertTrue(canReadByte(apkPath, lastByteOfTheSamePage + 1));
        }
    }

    @Test
    public void testFsverityFailToReadModifiedBlockAtBack() throws DeviceNotAvailableException {
        new InstallMultiple().addFileAndSignature(BASE_APK).run();
        String apkPath = getApkPath(TARGET_PACKAGE);

        long apkSize = getFileSizeInBytes(apkPath);
        long offsetOfLastByte = apkSize - 1;

        // The first two pages should be both readable at first.
        assertTrue(canReadByte(apkPath, offsetOfLastByte));
        if (offsetOfLastByte - FSVERITY_PAGE_SIZE > 0) {
            assertTrue(canReadByte(apkPath, offsetOfLastByte - FSVERITY_PAGE_SIZE));
        }

        // Damage the file directly against the block device.
        damageFileAgainstBlockDevice(apkPath, offsetOfLastByte);

        // Expect actual read from disk to fail but only at damaged page.
        dropCaches();
        assertFalse(canReadByte(apkPath, offsetOfLastByte));
        if (offsetOfLastByte - FSVERITY_PAGE_SIZE > 0) {
            long firstByteOfTheSamePage = offsetOfLastByte - offsetOfLastByte % FSVERITY_PAGE_SIZE;
            assertFalse(canReadByte(apkPath, firstByteOfTheSamePage));
            assertTrue(canReadByte(apkPath, firstByteOfTheSamePage - 1));
        }
    }

    private void verifyInstalledFilesHaveFsverity() throws DeviceNotAvailableException {
        // Verify that all files are protected by fs-verity
        String apkPath = getApkPath(TARGET_PACKAGE);
        String appDir = apkPath.substring(0, apkPath.lastIndexOf("/"));
        long kTargetOffset = 0;
        for (String basename : expectRemoteCommandToSucceed("ls " + appDir).split("\n")) {
            if (basename.endsWith(".apk") || basename.endsWith(".dm")) {
                String path = appDir + "/" + basename;
                damageFileAgainstBlockDevice(path, kTargetOffset);

                // Retry is sometimes needed to pass the test. Package manager may have FD leaks
                // (see b/122744005 as example) that prevents the file in question to be evicted
                // from filesystem cache. Forcing GC workarounds the problem.
                int retry = 5;
                for (; retry > 0; retry--) {
                    dropCaches();
                    if (!canReadByte(path, kTargetOffset)) {
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                        String pid = expectRemoteCommandToSucceed("pidof system_server");
                        mDevice.executeShellV2Command("kill -10 " + pid);  // force GC
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                assertTrue("Read from " + path + " should fail", retry > 0);
            }
        }
    }

    private void verifyInstalledFiles(String... filenames) throws DeviceNotAvailableException {
        String apkPath = getApkPath(TARGET_PACKAGE);
        String appDir = apkPath.substring(0, apkPath.lastIndexOf("/"));
        HashSet<String> actualFiles = new HashSet<>(Arrays.asList(
                expectRemoteCommandToSucceed("ls " + appDir).split("\n")));
        assertTrue(actualFiles.remove("lib"));
        assertTrue(actualFiles.remove("oat"));

        HashSet<String> expectedFiles = new HashSet<>(Arrays.asList(filenames));
        assertEquals(expectedFiles, actualFiles);
    }

    private void damageFileAgainstBlockDevice(String path, long offsetOfTargetingByte)
            throws DeviceNotAvailableException {
        assertTrue(path.startsWith("/data/"));
        ITestDevice.MountPointInfo mountPoint = mDevice.getMountPointInfo("/data");
        expectRemoteCommandToSucceed(String.join(" ", DAMAGING_EXECUTABLE,
                    mountPoint.filesystem, path, Long.toString(offsetOfTargetingByte)));
    }

    private String getApkPath(String packageName) throws DeviceNotAvailableException {
        String line = expectRemoteCommandToSucceed("pm path " + packageName + " | grep base.apk");
        int index = line.trim().indexOf(":");
        assertTrue(index >= 0);
        return line.substring(index + 1);
    }

    private long getFileSizeInBytes(String packageName) throws DeviceNotAvailableException {
        return Long.parseLong(expectRemoteCommandToSucceed("stat -c '%s' " + packageName).trim());
    }

    private void dropCaches() throws DeviceNotAvailableException {
        expectRemoteCommandToSucceed("sync && echo 1 > /proc/sys/vm/drop_caches");
    }

    private boolean canReadByte(String filePath, long offset) throws DeviceNotAvailableException {
        CommandResult result = mDevice.executeShellV2Command(
                "dd if=" + filePath + " bs=1 count=1 skip=" + Long.toString(offset));
        return result.getStatus() == CommandStatus.SUCCESS;
    }

    private String expectRemoteCommandToSucceed(String cmd) throws DeviceNotAvailableException {
        CommandResult result = mDevice.executeShellV2Command(cmd);
        assertEquals("`" + cmd + "` failed: " + result.getStderr(), CommandStatus.SUCCESS,
                result.getStatus());
        return result.getStdout();
    }

    private void expectRemoteCommandToFail(String cmd) throws DeviceNotAvailableException {
        CommandResult result = mDevice.executeShellV2Command(cmd);
        assertTrue("Unexpected success from `" + cmd + "`: " + result.getStderr(),
                result.getStatus() != CommandStatus.SUCCESS);
    }

    private class InstallMultiple extends BaseInstallMultiple<InstallMultiple> {
        InstallMultiple() {
            super(getDevice(), getBuild());
        }

        InstallMultiple addFileAndSignature(String filename) {
            try {
                addFile(filename);
                addFile(filename + ".fsv_sig");
            } catch (FileNotFoundException e) {
                fail("Missing test file: " + e);
            }
            return this;
        }
    }
}
