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
    public void failToInstallNonPlatformSignedOverlayTargetPreQ() throws Exception {
        try {
            installPackage("OverlayHostTests_NonPlatformSignatureOverlay.apk");
            fail("installed a non-platform signed overlay with targetSdkVersion < Q");
        } catch (Exception e) {
            // Expected.
        }
        assertFalse(overlayManagerContainsPackage(SIG_OVERLAY_PACKAGE_NAME));
    }

    @Test
    public void installedIsStaticOverlayIsMutable() throws Exception {
        installPackage("OverlayHostTests_PlatformSignatureStaticOverlay.apk");
        assertTrue(isOverlayMutable(SIG_OVERLAY_PACKAGE_NAME));
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

    @Test
    public void instantAppsNotVisibleToOMS() throws Exception {
        installInstantPackage("OverlayHostTests_AppOverlayV1.apk");
        assertFalse(overlayManagerContainsPackage(APP_OVERLAY_PACKAGE_NAME));
        installConvertExistingInstantPackageToFull(APP_OVERLAY_PACKAGE_NAME);
        assertTrue(overlayManagerContainsPackage(APP_OVERLAY_PACKAGE_NAME));
    }

    @Test
    public void changesPersistedWhenUninstallingDisabledOverlay() throws Exception {
        getDevice().enableAdbRoot();
        assertFalse(getDevice().executeShellCommand("cat /data/system/overlays.xml")
                .contains(APP_OVERLAY_PACKAGE_NAME));
        installPackage("OverlayHostTests_AppOverlayV1.apk");
        assertTrue(getDevice().executeShellCommand("cat /data/system/overlays.xml")
                .contains(APP_OVERLAY_PACKAGE_NAME));
        uninstallPackage(APP_OVERLAY_PACKAGE_NAME);
        delay();
        assertFalse(getDevice().executeShellCommand("cat /data/system/overlays.xml")
                .contains(APP_OVERLAY_PACKAGE_NAME));
    }

    @Test
    public void testAdbShellOMSInterface() throws Exception {
        installPackage("OverlayHostTests_AppOverlayV1.apk");
        assertTrue(shell("cmd overlay list " + DEVICE_TEST_PKG).contains(DEVICE_TEST_PKG));
        assertTrue(shell("cmd overlay list " + DEVICE_TEST_PKG).contains(APP_OVERLAY_PACKAGE_NAME));
        assertEquals("[ ] " + APP_OVERLAY_PACKAGE_NAME,
                shell("cmd overlay list " + APP_OVERLAY_PACKAGE_NAME).trim());
        assertEquals("STATE_DISABLED",
                shell("cmd overlay dump state " + APP_OVERLAY_PACKAGE_NAME).trim());

        setOverlayEnabled(APP_OVERLAY_PACKAGE_NAME, true);
        assertEquals("[x] " + APP_OVERLAY_PACKAGE_NAME,
                shell("cmd overlay list " + APP_OVERLAY_PACKAGE_NAME).trim());
        assertEquals("STATE_ENABLED",
                shell("cmd overlay dump state " + APP_OVERLAY_PACKAGE_NAME).trim());
    }

    private void delay() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
    }

    private void installPackage(String pkg) throws Exception {
        super.installPackage(pkg);
        delay();
    }

    private void installInstantPackage(String pkg) throws Exception {
        super.installPackage(pkg, "--instant");
        delay();
    }

    private void installConvertExistingInstantPackageToFull(String pkg) throws Exception {
        shell("cmd package install-existing --wait --full " + pkg);
    }

    private void setPackageEnabled(String pkg, boolean enabled) throws Exception {
        shell("cmd package " + (enabled ? "enable " : "disable ") + pkg);
        delay();
    }

    private void setOverlayEnabled(String pkg, boolean enabled) throws Exception {
        shell("cmd overlay " + (enabled ? "enable " : "disable ") + pkg);
        delay();
    }

    private boolean overlayManagerContainsPackage(String pkg) throws Exception {
        return shell("cmd overlay list").contains(pkg);
    }

    private boolean isOverlayMutable(String pkg) throws Exception {
        return shell("cmd overlay dump ismutable " + pkg).contains("true");
    }

    private String shell(final String cmd) throws Exception {
        return getDevice().executeShellCommand(cmd);
    }
}
