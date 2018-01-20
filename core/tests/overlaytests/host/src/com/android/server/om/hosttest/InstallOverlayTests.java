/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.android.server.om.hosttest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class InstallOverlayTests extends BaseHostJUnit4Test {

    private static final String OVERLAY_PACKAGE_NAME =
            "com.android.server.om.hosttest.signature_overlay";

    @Test
    public void failToInstallNonPlatformSignedOverlay() throws Exception {
        try {
            installPackage("OverlayHostTests_BadSignatureOverlay.apk");
            fail("installed a non-platform signed overlay");
        } catch (Exception e) {
            // Expected.
        }
        assertFalse(overlayManagerContainsPackage());
    }

    @Test
    public void failToInstallPlatformSignedStaticOverlay() throws Exception {
        try {
            installPackage("OverlayHostTests_PlatformSignatureStaticOverlay.apk");
            fail("installed a static overlay");
        } catch (Exception e) {
            // Expected.
        }
        assertFalse(overlayManagerContainsPackage());
    }

    @Test
    public void succeedToInstallPlatformSignedOverlay() throws Exception {
        installPackage("OverlayHostTests_PlatformSignatureOverlay.apk");
        assertTrue(overlayManagerContainsPackage());
    }

    @Test
    public void succeedToInstallPlatformSignedOverlayAndUpdate() throws Exception {
        installPackage("OverlayHostTests_PlatformSignatureOverlay.apk");
        assertTrue(overlayManagerContainsPackage());
        assertEquals("v1", getDevice().getAppPackageInfo(OVERLAY_PACKAGE_NAME).getVersionName());

        installPackage("OverlayHostTests_PlatformSignatureOverlayV2.apk");
        assertTrue(overlayManagerContainsPackage());
        assertEquals("v2", getDevice().getAppPackageInfo(OVERLAY_PACKAGE_NAME).getVersionName());
    }

    private boolean overlayManagerContainsPackage() throws Exception {
        return getDevice().executeShellCommand("cmd overlay list")
                .contains(OVERLAY_PACKAGE_NAME);
    }
}
