/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.om.hosttest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class InstallOverlayTests extends BaseHostJUnit4Test {
    private static final String SIG_OVERLAY_PACKAGE_NAME =
            "com.android.server.om.hosttest.signature_overlay";
    private static final String APP_OVERLAY_PACKAGE_NAME =
            "com.android.server.om.hosttest.app_overlay";
    private static final String FRAMEWORK_OVERLAY_PACKAGE_NAME =
            "com.android.server.om.hosttest.framework_overlay";
    private static final String[] ALL_PACKAGES = new String[] {
            SIG_OVERLAY_PACKAGE_NAME, APP_OVERLAY_PACKAGE_NAME, FRAMEWORK_OVERLAY_PACKAGE_NAME
    };

    private static final String DEVICE_TEST_PKG =
            "com.android.server.om.hosttest.update_overlay_test";
    private static final String DEVICE_TEST_CLS = DEVICE_TEST_PKG + ".UpdateOverlayTest";

    @Before
    public void ensureNoOverlays() throws Exception {
        // Make sure we're starting with a clean slate.
        for (String pkg : ALL_PACKAGES) {
            assertFalse(pkg + " should not be installed", isPackageInstalled(pkg));
            assertFalse(pkg + " should not be registered with overlay manager service",
                    overlayManagerContainsPackage(pkg));
        }
    }

    /*
    For some reason, SuiteApkInstaller is *not* uninstalling overlays, even though #installPackage()
    claims it will auto-clean.
    TODO(b/72877546): Remove when auto-clean is fixed.
     */
    @After
    public void uninstallOverlays() throws Exception {
        for (String pkg : ALL_PACKAGES) {
            uninstallPackage(pkg);
        }
    }

    @Test
    public void failToInstallNonPlatformSignedOverlay() throws Exception {
        try {
            installPackage("OverlayHostTests_BadSignatureOverlay.apk");
            fail("installed a non-platform signed overlay");
        } catch (Exception e) {
            // Expected.
        }
        assertFalse(overlayManagerContainsPackage(SIG_OVERLAY_PACKAGE_NAME));
    }

    @Test
    public void failToInstallPlatformSignedStaticOverlay() throws Exception {
        try {
            installPackage("OverlayHostTests_PlatformSignatureStaticOverlay.apk");
            fail("installed a static overlay");
        } catch (Exception e) {
            // Expected.
        }
        assertFalse(overlayManagerContainsPackage(SIG_OVERLAY_PACKAGE_NAME));
    }

    @Test
    public void installPlatformSignedOverlay() throws Exception {
        installPackage("OverlayHostTests_PlatformSignatureOverlay.apk");
        assertTrue(overlayManagerContainsPackage(SIG_OVERLAY_PACKAGE_NAME));
    }

    @Test
    public void installPlatformSignedAppOverlayAndUpdate() throws Exception {
        assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS, "expectAppResource"));

        installPackage("OverlayHostTests_AppOverlayV1.apk");
        setOverlayEnabled(APP_OVERLAY_PACKAGE_NAME, true);
        assertTrue(overlayManagerContainsPackage(APP_OVERLAY_PACKAGE_NAME));
        assertEquals("v1", getDevice()
                .getAppPackageInfo(APP_OVERLAY_PACKAGE_NAME)
                .getVersionName());
        assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS,
                "expectAppOverlayV1Resource"));

        installPackage("OverlayHostTests_AppOverlayV2.apk");
        assertTrue(overlayManagerContainsPackage(APP_OVERLAY_PACKAGE_NAME));
        assertEquals("v2", getDevice()
                .getAppPackageInfo(APP_OVERLAY_PACKAGE_NAME)
                .getVersionName());
        assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS,
                "expectAppOverlayV2Resource"));
    }

    @Test
    public void installPlatformSignedFrameworkOverlayAndUpdate() throws Exception {
        assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS, "expectFrameworkResource"));

        installPackage("OverlayHostTests_FrameworkOverlayV1.apk");
        setOverlayEnabled(FRAMEWORK_OVERLAY_PACKAGE_NAME, true);
        assertTrue(overlayManagerContainsPackage(FRAMEWORK_OVERLAY_PACKAGE_NAME));
        assertEquals("v1", getDevice()
                .getAppPackageInfo(FRAMEWORK_OVERLAY_PACKAGE_NAME)
                .getVersionName());
        assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS,
                "expectFrameworkOverlayV1Resource"));

        installPackage("OverlayHostTests_FrameworkOverlayV2.apk");
        assertTrue(overlayManagerContainsPackage(FRAMEWORK_OVERLAY_PACKAGE_NAME));
        assertEquals("v2", getDevice()
                .getAppPackageInfo(FRAMEWORK_OVERLAY_PACKAGE_NAME)
                .getVersionName());
        assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS,
                "expectFrameworkOverlayV2Resource"));
    }

    @Test
    public void enabledFrameworkOverlayMustAffectNewlyInstalledPackage() throws Exception {
        try {
            setPackageEnabled(DEVICE_TEST_PKG, false);

            installPackage("OverlayHostTests_FrameworkOverlayV1.apk");
            setOverlayEnabled(FRAMEWORK_OVERLAY_PACKAGE_NAME, true);
            assertTrue(overlayManagerContainsPackage(FRAMEWORK_OVERLAY_PACKAGE_NAME));

            setPackageEnabled(DEVICE_TEST_PKG, true);
            assertTrue(runDeviceTests(DEVICE_TEST_PKG, DEVICE_TEST_CLS,
                    "expectFrameworkOverlayV1Resource"));
        } finally {
            setPackageEnabled(DEVICE_TEST_PKG, true);
        }
    }

    private void delay() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }
    }

    private void installPackage(String pkg) throws Exception {
        super.installPackage(pkg);
        delay();
    }

    private void setPackageEnabled(String pkg, boolean enabled) throws Exception {
        getDevice().executeShellCommand("cmd package " + (enabled ? "enable " : "disable ") + pkg);
        delay();
    }

    private void setOverlayEnabled(String pkg, boolean enabled) throws Exception {
        getDevice().executeShellCommand("cmd overlay " + (enabled ? "enable " : "disable ") + pkg);
        delay();
    }

    private boolean overlayManagerContainsPackage(String pkg) throws Exception {
        return getDevice().executeShellCommand("cmd overlay list").contains(pkg);
    }
}
