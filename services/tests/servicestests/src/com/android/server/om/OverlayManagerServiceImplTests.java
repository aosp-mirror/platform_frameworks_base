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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.om.OverlayInfo;
import android.os.OverlayablePolicy;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerServiceImplTests extends OverlayManagerServiceImplTestsBase {

    private static final String OVERLAY = "com.dummy.overlay";
    private static final String TARGET = "com.dummy.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";
    private static final String TARGET2 = TARGET + "2";
    private static final int USER2 = USER + 1;

    private static final String OVERLAY3 = OVERLAY + "3";
    private static final int USER3 = USER2 + 1;

    @Test
    public void testGetOverlayInfo() {
        installNewPackage(overlay(OVERLAY, TARGET), USER);

        final OverlayManagerServiceImpl impl = getImpl();
        final OverlayInfo oi = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(oi);
        assertEquals(oi.packageName, OVERLAY);
        assertEquals(oi.targetPackageName, TARGET);
        assertEquals(oi.userId, USER);
    }

    @Test
    public void testGetOverlayInfosForTarget() {
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
    public void testGetOverlayInfosForUser() {
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
    public void testPriority() {
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        installNewPackage(overlay(OVERLAY2, TARGET), USER);
        installNewPackage(overlay(OVERLAY3, TARGET), USER);

        final OverlayManagerServiceImpl impl = getImpl();
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY2, USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY3, USER);

        assertOverlayInfoForTarget(TARGET, USER, o1, o2, o3);

        assertTrue(impl.setLowestPriority(OVERLAY3, USER));
        assertOverlayInfoForTarget(TARGET, USER, o3, o1, o2);

        assertTrue(impl.setHighestPriority(OVERLAY3, USER));
        assertOverlayInfoForTarget(TARGET, USER, o1, o2, o3);

        assertTrue(impl.setPriority(OVERLAY, OVERLAY2, USER));
        assertOverlayInfoForTarget(TARGET, USER, o2, o1, o3);
    }

    @Test
    public void testOverlayInfoStateTransitions() {
        final OverlayManagerServiceImpl impl = getImpl();
        assertNull(impl.getOverlayInfo(OVERLAY, USER));

        installNewPackage(overlay(OVERLAY, TARGET), USER);
        assertState(STATE_MISSING_TARGET, OVERLAY, USER);

        final DummyDeviceState.PackageBuilder target = target(TARGET);
        installNewPackage(target, USER);
        assertState(STATE_DISABLED, OVERLAY, USER);

        impl.setEnabled(OVERLAY, true, USER);
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
    public void testOnOverlayPackageUpgraded() {
        final DummyListener listener = getListener();
        final DummyDeviceState.PackageBuilder target = target(TARGET);
        final DummyDeviceState.PackageBuilder overlay = overlay(OVERLAY, TARGET);
        installNewPackage(target, USER);
        installNewPackage(overlay, USER);
        listener.count = 0;
        upgradePackage(overlay, USER);
        assertEquals(2, listener.count);

        // upgrade to a version where the overlay has changed its target
        // expect once for the old target package, once for the new target package
        listener.count = 0;
        final DummyDeviceState.PackageBuilder overlay2 = overlay(OVERLAY, "some.other.target");
        upgradePackage(overlay2, USER);
        assertEquals(3, listener.count);

        listener.count = 0;
        upgradePackage(overlay2, USER);
        assertEquals(2, listener.count);
    }

    @Test
    public void testListener() {
        final OverlayManagerServiceImpl impl = getImpl();
        final DummyListener listener = getListener();
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        assertEquals(1, listener.count);
        listener.count = 0;

        installNewPackage(target(TARGET), USER);
        assertEquals(1, listener.count);
        listener.count = 0;

        impl.setEnabled(OVERLAY, true, USER);
        assertEquals(1, listener.count);
        listener.count = 0;

        impl.setEnabled(OVERLAY, true, USER);
        assertEquals(0, listener.count);
    }

    @Test
    public void testConfigurator() {
        final DummyPackageManagerHelper packageManager = getPackageManager();
        packageManager.overlayableConfigurator = "actor";
        packageManager.overlayableConfiguratorTargets = new String[]{TARGET};
        reinitializeImpl();

        installNewPackage(target("actor").setCertificate("one"), USER);
        installNewPackage(target(TARGET)
                .addOverlayable("TestResources")
                .setCertificate("two"), USER);

        final DummyDeviceState.PackageBuilder overlay = overlay(OVERLAY, TARGET, "TestResources")
                .setCertificate("one");
        installNewPackage(overlay, USER);

        final DummyIdmapDaemon idmapDaemon = getIdmapDaemon();
        final DummyIdmapDaemon.IdmapHeader idmap = idmapDaemon.getIdmap(overlay.build().apkPath);
        assertNotNull(idmap);
        assertEquals(OverlayablePolicy.ACTOR_SIGNATURE,
                idmap.policies & OverlayablePolicy.ACTOR_SIGNATURE);
    }

    @Test
    public void testConfiguratorDifferentSignatures() {
        final DummyPackageManagerHelper packageManager = getPackageManager();
        packageManager.overlayableConfigurator = "actor";
        packageManager.overlayableConfiguratorTargets = new String[]{TARGET};
        reinitializeImpl();

        installNewPackage(target("actor").setCertificate("one"), USER);
        installNewPackage(target(TARGET)
                .addOverlayable("TestResources")
                .setCertificate("two"), USER);

        final DummyDeviceState.PackageBuilder overlay = overlay(OVERLAY, TARGET, "TestResources")
                .setCertificate("two");
        installNewPackage(overlay, USER);

        final DummyIdmapDaemon idmapDaemon = getIdmapDaemon();
        final DummyIdmapDaemon.IdmapHeader idmap = idmapDaemon.getIdmap(overlay.build().apkPath);
        assertNotNull(idmap);
        assertEquals(0, idmap.policies & OverlayablePolicy.ACTOR_SIGNATURE);
    }
}
