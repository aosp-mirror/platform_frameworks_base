/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.overlaytest.remounted;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class OverlayApexTest extends OverlayRemountedTestBase {
    private static final String OVERLAID_APEX = "com.android.overlaytest.overlaid.apex";
    private static final String OVERLAY_APEX = "com.android.overlaytest.overlay.apex";

    @Test
    public void testApkInApexCanBeOverlaid() throws Exception {
        final String targetResource = resourceName(TARGET_PACKAGE, "bool", "target_overlaid");

        // The target APK will be installed inside the overlaid APEX.
        mPreparer.pushResourceFile(OVERLAID_APEX,
                "/system/apex/com.android.overlaytest.overlaid.apex")
                .installResourceApk(OVERLAY_APK, OVERLAY_PACKAGE)
                .reboot()
                .setOverlayEnabled(OVERLAY_PACKAGE, false);

        // The resource is not currently overlaid.
        assertResource(targetResource, "false");

        // Overlay the resource.
        mPreparer.setOverlayEnabled(OVERLAY_PACKAGE, true);
        assertResource(targetResource, "true");
    }

    @Test
    public void testApkInApexCanOverlay() throws Exception {
        final String targetResource = resourceName(TARGET_PACKAGE, "bool", "target_overlaid");

        // The overlay APK will be installed inside the overlay APEX.
        mPreparer.pushResourceFile(OVERLAY_APEX,
                "/system/apex/com.android.overlaytest.overlay.apex")
                .installResourceApk(TARGET_APK, TARGET_PACKAGE)
                .reboot()
                .setOverlayEnabled(OVERLAY_PACKAGE, false);

        // The resource is not currently overlaid.
        assertResource(targetResource, "false");

        // Overlay the resource.
        mPreparer.setOverlayEnabled(OVERLAY_PACKAGE, true);
        assertResource(targetResource, "true");
    }
}
