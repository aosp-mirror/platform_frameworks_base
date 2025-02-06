/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.content;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.AppModeFull;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class FileSystemUtilsTest extends BaseHostJUnit4Test {
    private static final String PAGE_SIZE_COMPAT_ENABLED = "app_with_4kb_elf.apk";
    private static final String PAGE_SIZE_COMPAT_DISABLED = "page_size_compat_disabled_app.apk";
    private static final String PAGE_SIZE_COMPAT_ENABLED_COMPRESSED_ELF =
            "app_with_4kb_compressed_elf.apk";
    private static final String PAGE_SIZE_COMPAT_ENABLED_BY_PLATFORM =
            "app_with_4kb_elf_no_override.apk";

    @Test
    @AppModeFull
    public void runPunchedApp_embeddedNativeLibs() throws DeviceNotAvailableException {
        String appPackage = "android.test.embedded";
        String testName = "PunchEmbeddedLibTest";
        assertTrue(isPackageInstalled(appPackage));
        runDeviceTests(appPackage, appPackage + "." + testName);
    }

    @Test
    @AppModeFull
    public void runPunchedApp_extractedNativeLibs() throws DeviceNotAvailableException {
        String appPackage = "android.test.extract";
        String testName = "PunchExtractedLibTest";
        assertTrue(isPackageInstalled(appPackage));
        runDeviceTests(appPackage, appPackage + "." + testName);
    }

    private void runPageSizeCompatTest(String appName, String testMethodName)
            throws DeviceNotAvailableException, TargetSetupError {
        getDevice().enableAdbRoot();
        String result = getDevice().executeShellCommand("getconf PAGE_SIZE");
        assumeTrue("16384".equals(result.strip()));
        installPackage(appName, "-r");
        String appPackage = "android.test.pagesizecompat";
        String testName = "PageSizeCompatTest";
        assertTrue(isPackageInstalled(appPackage));
        assertTrue(runDeviceTests(appPackage, appPackage + "." + testName,
                testMethodName));
        uninstallPackage(appPackage);
    }

    @Test
    @AppModeFull
    public void runAppWith4KbLib_overrideCompatMode()
            throws DeviceNotAvailableException, TargetSetupError {
        runPageSizeCompatTest(PAGE_SIZE_COMPAT_ENABLED, "testPageSizeCompat_compatEnabled");
    }

    @Test
    @AppModeFull
    public void runAppWith4KbCompressedLib_overrideCompatMode()
            throws DeviceNotAvailableException, TargetSetupError {
        runPageSizeCompatTest(PAGE_SIZE_COMPAT_ENABLED_COMPRESSED_ELF,
                "testPageSizeCompat_compatEnabled");
    }

    @Test
    @AppModeFull
    public void runAppWith4KbLib_disabledCompatMode()
            throws DeviceNotAvailableException, TargetSetupError {
        // This test is expected to fail since compat is disabled in manifest
        runPageSizeCompatTest(PAGE_SIZE_COMPAT_DISABLED,
                "testPageSizeCompat_compatDisabled");
    }

    @Test
    @AppModeFull
    public void runAppWith4KbLib_compatByAlignmentChecks()
            throws DeviceNotAvailableException, TargetSetupError {
        // This test is expected to fail since compat is disabled in manifest
        runPageSizeCompatTest(PAGE_SIZE_COMPAT_ENABLED_BY_PLATFORM,
                "testPageSizeCompat_compatByAlignmentChecks");
    }
}
