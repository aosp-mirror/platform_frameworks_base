/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.fsverity;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.host.HostFlagsValueProvider;
import android.security.Flags;

import com.android.blockdevicewriter.BlockDeviceWriter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.DeviceTestRunOptions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * This test verifies fs-verity works end-to-end. There is a corresponding helper app.
 *
 * <p>The helper app uses a FileIntegrityManager API to enable fs-verity to a file. The host test
 * here * tampers with the file's backing storage, then tells the helper app to read and expect
 * success/failure on read.
 *
 * <p>In order to make sure a block of the file is readable only if the underlying block on disk
 * stay intact, the test needs to bypass the filesystem and tampers with the corresponding physical
 * address against the block device.
 */
@RootPermissionTest
@RunWith(DeviceJUnit4ClassRunner.class)
@RequiresFlagsEnabled(Flags.FLAG_FSVERITY_API)
public class FsVerityHostTest extends BaseHostJUnit4Test {
    private static final String TARGET_PACKAGE = "com.android.fsverity";

    private static final String BASENAME = "test.file";
    private static final String TARGET_PATH = "/data/data/" + TARGET_PACKAGE + "/files/" + BASENAME;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            HostFlagsValueProvider.createCheckFlagsRule(this::getDevice);

    @Test
    public void testFsVeritySmallFile() throws Exception {
        prepareTest(10000);

        ITestDevice device = getDevice();
        BlockDeviceWriter.damageFileAgainstBlockDevice(device, TARGET_PATH, 0);
        BlockDeviceWriter.damageFileAgainstBlockDevice(device, TARGET_PATH, 8192);
        BlockDeviceWriter.dropCaches(device);

        verifyRead(TARGET_PATH, "0,2");
    }

    @Test
    public void testFsVerityLargerFileWithOneMoreMerkleTreeLevel() throws Exception {
        prepareTest(128 * 4096 + 1);

        ITestDevice device = getDevice();
        BlockDeviceWriter.damageFileAgainstBlockDevice(device, TARGET_PATH, 4096);
        BlockDeviceWriter.damageFileAgainstBlockDevice(device, TARGET_PATH, 100 * 4096);
        BlockDeviceWriter.damageFileAgainstBlockDevice(device, TARGET_PATH, 128 * 4096 + 1);
        BlockDeviceWriter.dropCaches(device);

        verifyRead(TARGET_PATH, "1,100,128");
    }

    private void prepareTest(int fileSize) throws Exception {
        DeviceTestRunOptions options = new DeviceTestRunOptions(TARGET_PACKAGE);
        options.setTestClassName(TARGET_PACKAGE + ".Helper");
        options.setTestMethodName("prepareTest");
        options.addInstrumentationArg("basename", BASENAME);
        options.addInstrumentationArg("fileSize", String.valueOf(fileSize));
        assertThat(runDeviceTests(options)).isTrue();
    }

    private void verifyRead(String path, String indicesCsv) throws Exception {
        DeviceTestRunOptions options = new DeviceTestRunOptions(TARGET_PACKAGE);
        options.setTestClassName(TARGET_PACKAGE + ".Helper");
        options.setTestMethodName("verifyFileRead");
        options.addInstrumentationArg("brokenBlockIndicesCsv", indicesCsv);
        options.addInstrumentationArg("filePath", TARGET_PATH);
        assertThat(runDeviceTests(options)).isTrue();
    }
}
