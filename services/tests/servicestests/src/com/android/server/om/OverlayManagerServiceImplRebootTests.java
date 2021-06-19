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

package com.android.server.om;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerServiceImplRebootTests extends OverlayManagerServiceImplTestsBase {

    private static final String OVERLAY = "com.test.overlay";
    private static final OverlayIdentifier IDENTIFIER = new OverlayIdentifier(OVERLAY);
    private static final String TARGET = "com.test.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";
    private static final OverlayIdentifier IDENTIFIER2 = new OverlayIdentifier(OVERLAY2);

    @Test
    public void alwaysInitializeAllPackages() {
        final OverlayManagerServiceImpl impl = getImpl();
        final String otherTarget = "some.other.target";
        addPackage(target(TARGET), USER);
        addPackage(target(otherTarget), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);

        final Set<PackageAndUser> allPackages =
                Set.of(new PackageAndUser(TARGET, USER),
                        new PackageAndUser(otherTarget, USER),
                        new PackageAndUser(OVERLAY, USER));

        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
    }

    @Test
    public void testImmutableEnabledChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);

        final Set<PackageAndUser> allPackages =
                Set.of(new PackageAndUser(TARGET, USER), new PackageAndUser(OVERLAY, USER));

        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_DISABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o1);
        assertFalse(o1.isEnabled());
        assertFalse(o1.isMutable);

        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_ENABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o2);
        assertTrue(o2.isEnabled());
        assertFalse(o2.isMutable);

        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_DISABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertFalse(o3.isMutable);
    }

    @Test
    public void testMutableEnabledChangeHasNoEffect() {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);
        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 0 /* priority */);

        final Set<PackageAndUser> allPackages =
                Set.of(new PackageAndUser(TARGET, USER), new PackageAndUser(OVERLAY, USER));

        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o1);
        assertFalse(o1.isEnabled());
        assertTrue(o1.isMutable);

        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_ENABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o2);
        assertFalse(o2.isEnabled());
        assertTrue(o2.isMutable);

        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertTrue(o3.isMutable);
    }

    @Test
    public void testMutableEnabledToImmutableEnabled() {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);

        final Set<PackageAndUser> allPackages =
                Set.of(new PackageAndUser(TARGET, USER), new PackageAndUser(OVERLAY, USER));

        final Consumer<ConfigState> setOverlay = (state -> {
            configureSystemOverlay(OVERLAY, state, 0 /* priority */);
            assertEquals(allPackages, impl.updateOverlaysForUser(USER));
            final OverlayInfo o = impl.getOverlayInfo(IDENTIFIER, USER);
            assertNotNull(o);
            assertEquals(o.isEnabled(), state == ConfigState.IMMUTABLE_ENABLED
                    || state == ConfigState.MUTABLE_ENABLED);
            assertEquals(o.isMutable, state == ConfigState.MUTABLE_DISABLED
                    || state == ConfigState.MUTABLE_ENABLED);
        });

        // Immutable/enabled -> mutable/enabled
        setOverlay.accept(ConfigState.IMMUTABLE_ENABLED);
        setOverlay.accept(ConfigState.MUTABLE_ENABLED);

        // Mutable/enabled -> immutable/enabled
        setOverlay.accept(ConfigState.IMMUTABLE_ENABLED);

        // Immutable/enabled -> mutable/disabled
        setOverlay.accept(ConfigState.MUTABLE_DISABLED);

        // Mutable/disabled -> immutable/enabled
        setOverlay.accept(ConfigState.IMMUTABLE_ENABLED);

        // Immutable/enabled -> immutable/disabled
        setOverlay.accept(ConfigState.IMMUTABLE_DISABLED);

        // Immutable/disabled -> mutable/enabled
        setOverlay.accept(ConfigState.MUTABLE_ENABLED);

        // Mutable/enabled -> immutable/disabled
        setOverlay.accept(ConfigState.IMMUTABLE_DISABLED);

        // Immutable/disabled -> mutable/disabled
        setOverlay.accept(ConfigState.MUTABLE_DISABLED);

        // Mutable/disabled -> immutable/disabled
        setOverlay.accept(ConfigState.IMMUTABLE_DISABLED);
    }

    @Test
    public void testMutablePriorityChange() throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);
        addPackage(overlay(OVERLAY2, TARGET), USER);
        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 0 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.MUTABLE_DISABLED, 1 /* priority */);

        final Set<PackageAndUser> allPackages =
                Set.of(new PackageAndUser(TARGET, USER), new PackageAndUser(OVERLAY, USER),
                        new PackageAndUser(OVERLAY2, USER));

        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o1);
        assertEquals(0, o1.priority);
        assertFalse(o1.isEnabled());

        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER2, USER);
        assertNotNull(o2);
        assertEquals(1, o2.priority);
        assertFalse(o2.isEnabled());

        // Overlay priority changing between reboots should not affect enable state of mutable
        // overlays.
        impl.setEnabled(IDENTIFIER, true, USER);

        // Reorder the overlays
        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 1 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.MUTABLE_DISABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o3);
        assertEquals(1, o3.priority);
        assertTrue(o3.isEnabled());

        final OverlayInfo o4 = impl.getOverlayInfo(IDENTIFIER2, USER);
        assertNotNull(o4);
        assertEquals(0, o4.priority);
        assertFalse(o4.isEnabled());
    }

    @Test
    public void testImmutablePriorityChange() throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);
        addPackage(overlay(OVERLAY2, TARGET), USER);
        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_ENABLED, 0 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.IMMUTABLE_ENABLED, 1 /* priority */);

        final Set<PackageAndUser> allPackages =
                Set.of(new PackageAndUser(TARGET, USER), new PackageAndUser(OVERLAY, USER),
                        new PackageAndUser(OVERLAY2, USER));

        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o1);
        assertEquals(0, o1.priority);
        assertTrue(o1.isEnabled());

        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER2, USER);
        assertNotNull(o2);
        assertEquals(1, o2.priority);
        assertTrue(o2.isEnabled());

        // Reorder the overlays
        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_ENABLED, 1 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.IMMUTABLE_ENABLED, 0 /* priority */);
        assertEquals(allPackages, impl.updateOverlaysForUser(USER));
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        assertNotNull(o3);
        assertEquals(1, o3.priority);
        assertTrue(o3.isEnabled());

        final OverlayInfo o4 = impl.getOverlayInfo(IDENTIFIER2, USER);
        assertNotNull(o4);
        assertEquals(0, o4.priority);
        assertTrue(o4.isEnabled());
    }
}
