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

package com.android.overlaytest;

import android.content.res.Flags;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.host.HostFlagsValueProvider;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
@AppModeFull
public class HandleConfigChangeHostTests extends BaseHostJUnit4Test {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            HostFlagsValueProvider.createCheckFlagsRule(this::getDevice);
    private static final String DEVICE_TEST_PKG1 = "com.android.overlaytest.overlayresapp";
    private static final String DEVICE_TEST_CLASS = "OverlayResTest";

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HANDLE_ALL_CONFIG_CHANGES)
    public void testOverlayRes() throws Exception {
        runDeviceTests(DEVICE_TEST_PKG1, DEVICE_TEST_PKG1 + "." + DEVICE_TEST_CLASS,
                "overlayRes_onConfigurationChanged");
    }
}
