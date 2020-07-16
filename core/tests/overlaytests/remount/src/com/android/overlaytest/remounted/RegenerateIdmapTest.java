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
public class RegenerateIdmapTest extends OverlayRemountedTestBase {
    private static final String OVERLAY_SIGNATURE_APK =
            "OverlayRemountedTest_Overlay_SameCert.apk";
    private static final String TARGET_UPGRADE_APK = "OverlayRemountedTest_TargetUpgrade.apk";

    @Test
    public void testTargetUpgrade() throws Exception {
        final String targetOverlaid = resourceName(TARGET_PACKAGE, "bool", "target_overlaid");
        final String targetReference = resourceName(TARGET_PACKAGE, "bool", "target_reference");

        mPreparer.pushResourceFile(TARGET_APK, "/product/app/OverlayTarget.apk")
                .reboot()
                .installResourceApk(OVERLAY_APK, OVERLAY_PACKAGE)
                .setOverlayEnabled(OVERLAY_PACKAGE, true);

        assertResource(targetReference, "@" + 0x7f010000 + " -> true");
        assertResource(targetOverlaid, "true");

        mPreparer.installResourceApk(TARGET_UPGRADE_APK, TARGET_PACKAGE);

        assertResource(targetReference, "@" + 0x7f0100ff + " -> true");
        assertResource(targetOverlaid, "true");
    }

    @Test
    public void testTargetRelocated() throws Exception {
        final String targetOverlaid = resourceName(TARGET_PACKAGE, "bool", "target_overlaid");
        final String targetReference = resourceName(TARGET_PACKAGE, "bool", "target_reference");
        final String originalPath = "/product/app/OverlayTarget.apk";

        mPreparer.pushResourceFile(TARGET_APK, originalPath)
                .reboot()
                .installResourceApk(OVERLAY_APK, OVERLAY_PACKAGE)
                .setOverlayEnabled(OVERLAY_PACKAGE, true);

        assertResource(targetReference, "@" + 0x7f010000 + " -> true");
        assertResource(targetOverlaid, "true");

        mPreparer.remount();
        getDevice().deleteFile(originalPath);
        mPreparer.pushResourceFile(TARGET_UPGRADE_APK, "/product/app/OverlayTarget2.apk")
                .reboot();

        assertResource(targetReference, "@" + 0x7f0100ff + " -> true");
        assertResource(targetOverlaid, "true");
    }

    @Test
    public void testIdmapPoliciesChanged() throws Exception {
        final String targetResource = resourceName(TARGET_PACKAGE, "bool",
                "signature_policy_overlaid");

        mPreparer.pushResourceFile(TARGET_APK, "/product/app/OverlayTarget.apk")
                .pushResourceFile(OVERLAY_APK, "/product/overlay/TestOverlay.apk")
                .reboot()
                .setOverlayEnabled(OVERLAY_PACKAGE, false);

        assertResource(targetResource, "false");

        // The overlay is not signed with the same signature as the target.
        mPreparer.setOverlayEnabled(OVERLAY_PACKAGE, true);
        assertResource(targetResource, "false");

        // Replace the overlay with a version of the overlay that is signed with the same signature
        // as the target.
        mPreparer.pushResourceFile(OVERLAY_SIGNATURE_APK, "/product/overlay/TestOverlay.apk")
                .reboot();

        // The idmap should have been recreated with the signature policy fulfilled.
        assertResource(targetResource, "true");

        mPreparer.setOverlayEnabled(OVERLAY_PACKAGE, false);
        assertResource(targetResource, "false");
    }
}
