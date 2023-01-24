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

package com.android.server.pm.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.blockdevicewriter.BlockDeviceWriter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;

/**
 * atest com.android.server.pm.test.SettingsTest
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class SettingsTest extends BaseHostJUnit4Test {
    private static final String DEVICE_TEST_PACKAGE_NAME =
            "com.android.server.pm.test.service.server";
    private static final String TEST_CLASS_NAME =
            "com.android.server.pm.PackageManagerSettingsDeviceTests";

    private static final String FSVERITY_EXECUTABLE = "/data/local/tmp/fsverity_multilib";
    private static final String DAMAGING_EXECUTABLE = "/data/local/tmp/block_device_writer";

    @Test
    public void testWriteCorruptHeaderBinaryXml() throws Exception {
        // Corrupt 1st page, this will trigger error in initial resolve* call.
        performTest("testWriteBinaryXmlSettings", 0);
    }

    @Test
    public void testWriteCorruptHeaderTextXml() throws Exception {
        // Corrupt 1st page, this will trigger error in initial resolve* call.
        performTest("testWriteTextXmlSettings", 0);
    }

    @Test
    public void testWriteCorruptDataBinaryXml() throws Exception {
        // Corrupt 2nd page, this will trigger error in the XML parser.
        performTest("testWriteBinaryXmlSettings", 1);
    }

    @Test
    public void testWriteCorruptDataTextXml() throws Exception {
        // Corrupt 2nd page, this will trigger error in the XML parser.
        performTest("testWriteTextXmlSettings", 1);
    }

    private void performTest(String writeTest, int pageToCorrupt) throws Exception {
        assertTrue(runDeviceTests(DEVICE_TEST_PACKAGE_NAME, TEST_CLASS_NAME,
                writeTest));

        int userId = getDevice().getCurrentUser();
        File filesDir = new File(
                "/data/user/" + userId + "/" + DEVICE_TEST_PACKAGE_NAME + "/files");
        File packagesXml = new File(filesDir, "system/packages.xml");

        // Make sure fs-verity is enabled.
        enableFsVerity(packagesXml.getAbsolutePath());

        // Damage the file directly against the block device.
        BlockDeviceWriter.damageFileAgainstBlockDevice(getDevice(), packagesXml.getAbsolutePath(),
                pageToCorrupt * 4096 + 1);
        BlockDeviceWriter.dropCaches(getDevice());

        assertTrue(runDeviceTests(DEVICE_TEST_PACKAGE_NAME, TEST_CLASS_NAME,
                "testReadSettings"));
    }

    private void enableFsVerity(String path) throws Exception {
        ITestDevice device = getDevice();
        ArrayList<String> args = new ArrayList<>();
        args.add(FSVERITY_EXECUTABLE);
        args.add("enable");
        args.add(path);

        String cmd = String.join(" ", args);
        CommandResult result = device.executeShellV2Command(cmd);
        assertEquals("`" + cmd + "` failed: " + result.getStderr(), CommandStatus.SUCCESS,
                result.getStatus());
    }

}
