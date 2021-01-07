/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om;

import static android.content.om.OverlayInfo.STATE_DISABLED;
import static android.content.om.OverlayInfo.STATE_ENABLED;
import static android.content.om.OverlayInfo.STATE_MISSING_TARGET;
import static android.os.OverlayablePolicy.CONFIG_SIGNATURE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.content.om.OverlayInfo;
import android.util.Pair;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerServiceImplTests extends OverlayManagerServiceImplTestsBase {

    private static final String OVERLAY = "com.test.overlay";
    private static final String TARGET = "com.test.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";
    private static final String TARGET2 = TARGET + "2";
    private static final int USER2 = USER + 1;

    private static final String OVERLAY3 = OVERLAY + "3";
    private static final int USER3 = USER2 + 1;

    private static final String CONFIG_SIGNATURE_REFERENCE_PKG = "com.test.ref";
    private static final String CERT_CONFIG_OK = "config_certificate_ok";
    private static final String CERT_CONFIG_NOK = "config_certificate_nok";

    @Test
    public void testGetOverlayInfo() throws Exception {
        installNewPackage(overlay(OVERLAY, TARGET), USER);

        final OverlayManagerServiceImpl impl = getImpl();
        final OverlayInfo oi = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(oi);
        assertEquals(oi.packageName, OVERLAY);
        assertEquals(oi.targetPackageName, TARGET);
        assertEquals(oi.userId, USER);
    }

    @Test
    public void testGetOverlayInfosForTarget() throws Exception {
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        installNewPackage(overlay(OVERLAY2, TARGET), USER);
        installNewPackage(overlay(OVERLAY3, TARGET), USER2);

        final OverlayManagerServiceImpl impl = getImpl();
        final List<OverlayInfo> ois = impl.getOverlayInfosForTarget(TARGET, USER);
        assertEquals(ois.size(), 2);
        assertTrue(ois.contains(impl.getOverlayInfo(OVERLAY, USER)));
        assertTrue(ois.contains(impl.getOverlayInfo(OVERLAY2, USER)));

        final List<OverlayInfo> ois2 = impl.getOverlayInfosForTarget(TARGET, USER2);
        assertEquals(ois2.size(), 1);
        assertTrue(ois2.contains(impl.getOverlayInfo(OVERLAY3, USER2)));

        final List<OverlayInfo> ois3 = impl.getOverlayInfosForTarget(TARGET, USER3);
        assertNotNull(ois3);
        assertEquals(ois3.size(), 0);

        final List<OverlayInfo> ois4 = impl.getOverlayInfosForTarget("no.such.overlay", USER);
        assertNotNull(ois4);
        assertEquals(ois4.size(), 0);
    }

    @Test
    public void testGetOverlayInfosForUser() throws Exception {
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        installNewPackage(overlay(OVERLAY2, TARGET), USER);
        installNewPackage(overlay(OVERLAY3, TARGET2), USER);

        final OverlayManagerServiceImpl impl = getImpl();
        final Map<String, List<OverlayInfo>> everything = impl.getOverlaysForUser(USER);
        assertEquals(everything.size(), 2);

        final List<OverlayInfo> ois = everything.get(TARGET);
        assertNotNull(ois);
        assertEquals(ois.size(), 2);
        assertTrue(ois.contains(impl.getOverlayInfo(OVERLAY, USER)));
        assertTrue(ois.contains(impl.getOverlayInfo(OVERLAY2, USER)));

        final List<OverlayInfo> ois2 = everything.get(TARGET2);
        assertNotNull(ois2);
        assertEquals(ois2.size(), 1);
        assertTrue(ois2.contains(impl.getOverlayInfo(OVERLAY3, USER)));

        final Map<String, List<OverlayInfo>> everything2 = impl.getOverlaysForUser(USER2);
        assertNotNull(everything2);
        assertEquals(everything2.size(), 0);
    }

    @Test
    public void testPriority() throws Exception {
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        installNewPackage(overlay(OVERLAY2, TARGET), USER);
        installNewPackage(overlay(OVERLAY3, TARGET), USER);

        final OverlayManagerServiceImpl impl = getImpl();
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY2, USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY3, USER);

        assertOverlayInfoForTarget(TARGET, USER, o1, o2, o3);

        assertEquals(impl.setLowestPriority(OVERLAY3, USER),
                Optional.of(new PackageAndUser(TARGET, USER)));
        assertOverlayInfoForTarget(TARGET, USER, o3, o1, o2);

        assertEquals(impl.setHighestPriority(OVERLAY3, USER),
                Optional.of(new PackageAndUser(TARGET, USER)));
        assertOverlayInfoForTarget(TARGET, USER, o1, o2, o3);

        assertEquals(impl.setPriority(OVERLAY, OVERLAY2, USER),
                Optional.of(new PackageAndUser(TARGET, USER)));
        assertOverlayInfoForTarget(TARGET, USER, o2, o1, o3);
    }

    @Test
    public void testOverlayInfoStateTransitions() throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        assertNull(impl.getOverlayInfo(OVERLAY, USER));

        installNewPackage(overlay(OVERLAY, TARGET), USER);
        assertState(STATE_MISSING_TARGET, OVERLAY, USER);

        final FakeDeviceState.PackageBuilder target = target(TARGET);
        installNewPackage(target, USER);
        assertState(STATE_DISABLED, OVERLAY, USER);

        assertEquals(impl.setEnabled(OVERLAY, true, USER),
                Optional.of(new PackageAndUser(TARGET, USER)));
        assertState(STATE_ENABLED, OVERLAY, USER);

        // target upgrades do not change the state of the overlay
        upgradePackage(target, USER);
        assertState(STATE_ENABLED, OVERLAY, USER);

        uninstallPackage(TARGET, USER);
        assertState(STATE_MISSING_TARGET, OVERLAY, USER);

        installNewPackage(target, USER);
        assertState(STATE_ENABLED, OVERLAY, USER);
    }

    @Test
    public void testOnOverlayPackageUpgraded() throws Exception {
        final FakeDeviceState.PackageBuilder target = target(TARGET);
        final FakeDeviceState.PackageBuilder overlay = overlay(OVERLAY, TARGET);
        installNewPackage(target, USER);
        installNewPackage(overlay, USER);
        upgradePackage(overlay, USER);

        // upgrade to a version where the overlay has changed its target
        final FakeDeviceState.PackageBuilder overlay2 = overlay(OVERLAY, "some.other.target");
        final Pair<Optional<PackageAndUser>, Optional<PackageAndUser>> pair =
                upgradePackage(overlay2, USER);
        assertEquals(pair.first, Optional.of(new PackageAndUser(TARGET, USER)));
        assertEquals(pair.second, Optional.of(new PackageAndUser("some.other.target", USER)));
    }

    @Test
    public void testSetEnabledAtVariousConditions() throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        assertThrows(OverlayManagerServiceImpl.OperationFailedException.class,
                () -> impl.setEnabled(OVERLAY, true, USER));

        // request succeeded, and there was a change that needs to be
        // propagated to the rest of the system
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        assertEquals(impl.setEnabled(OVERLAY, true, USER),
                Optional.of(new PackageAndUser(TARGET, USER)));

        // request succeeded, but nothing changed
        assertFalse(impl.setEnabled(OVERLAY, true, USER).isPresent());
    }

    @Test
    public void testConfigSignaturePolicyOk() throws Exception {
        setConfigSignaturePackageName(CONFIG_SIGNATURE_REFERENCE_PKG);
        reinitializeImpl();

        addPackage(target(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_OK), USER);

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertTrue((CONFIG_SIGNATURE & idmap.policies) == CONFIG_SIGNATURE);
    }

    @Test
    public void testConfigSignaturePolicyCertNok() throws Exception {
        setConfigSignaturePackageName(CONFIG_SIGNATURE_REFERENCE_PKG);
        reinitializeImpl();

        addPackage(target(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER);

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertTrue((CONFIG_SIGNATURE & idmap.policies) == 0);
    }

    @Test
    public void testConfigSignaturePolicyNoConfig() throws Exception {
        addPackage(target(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER);

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertTrue((CONFIG_SIGNATURE & idmap.policies) == 0);
    }

    @Test
    public void testConfigSignaturePolicyNoRefPkg() throws Exception {
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER);

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertTrue((CONFIG_SIGNATURE & idmap.policies) == 0);
    }

    @Test
    public void testConfigSignaturePolicyRefPkgNotSystem() throws Exception {
        setConfigSignaturePackageName(CONFIG_SIGNATURE_REFERENCE_PKG);
        reinitializeImpl();

        addPackage(app(CONFIG_SIGNATURE_REFERENCE_PKG).setCertificate(CERT_CONFIG_OK), USER);
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET).setCertificate(CERT_CONFIG_NOK), USER);

        final FakeIdmapDaemon idmapd = getIdmapd();
        final FakeDeviceState state = getState();
        String overlayPath = state.select(OVERLAY, USER).apkPath;
        assertTrue(idmapd.idmapExists(overlayPath, USER));

        FakeIdmapDaemon.IdmapHeader idmap = idmapd.getIdmap(overlayPath);
        assertTrue((CONFIG_SIGNATURE & idmap.policies) == 0);
    }
}
