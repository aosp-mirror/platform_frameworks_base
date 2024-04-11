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

import android.platform.test.annotations.AppModeFull;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class FileSystemUtilsTest extends BaseHostJUnit4Test {

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
}
