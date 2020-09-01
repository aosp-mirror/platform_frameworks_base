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

import android.content.om.OverlayInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerServiceImplRebootTests extends OverlayManagerServiceImplTestsBase {

    private static final String OVERLAY = "com.dummy.overlay";
    private static final String TARGET = "com.dummy.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";

    @Test
    public void testUpdateOverlaysForUser() {
        final OverlayManagerServiceImpl impl = getImpl();
        addSystemPackage(target(TARGET), USER);
        addSystemPackage(target("some.other.target"), USER);;
        addSystemPackage(overlay(OVERLAY, TARGET), USER);

        // do nothing, expect no change
        final List<String> a = impl.updateOverlaysForUser(USER);
        assertEquals(1, a.size());
        assertTrue(a.contains(TARGET));

        // upgrade overlay, keep target
        addSystemPackage(overlay(OVERLAY, TARGET), USER);

        final List<String> b = impl.updateOverlaysForUser(USER);
        assertEquals(1, b.size());
        assertTrue(b.contains(TARGET));

        // do nothing, expect no change
        final List<String> c = impl.updateOverlaysForUser(USER);
        assertEquals(1, c.size());
        assertTrue(c.contains(TARGET));

        // upgrade overlay, switch to new target
        addSystemPackage(overlay(OVERLAY, "some.other.target"), USER);
        final List<String> d = impl.updateOverlaysForUser(USER);
        assertEquals(2, d.size());
        assertTrue(d.containsAll(Arrays.asList(TARGET, "some.other.target")));

        // do nothing, expect no change
        final List<String> f = impl.updateOverlaysForUser(USER);
        assertEquals(1, f.size());
        assertTrue(f.contains("some.other.target"));
    }

    @Test
    public void testImmutableEnabledChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);

        configureSystemOverlay(OVERLAY, false /* mutable */, false /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertFalse(o1.isEnabled());
        assertFalse(o1.isMutable);

        configureSystemOverlay(OVERLAY, false /* mutable */, true /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o2);
        assertTrue(o2.isEnabled());
        assertFalse(o2.isMutable);

        configureSystemOverlay(OVERLAY, false /* mutable */, false /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertFalse(o3.isMutable);
    }

    @Test
    public void testMutableEnabledChangeHasNoEffect() {
        final OverlayManagerServiceImpl impl = getImpl();
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        configureSystemOverlay(OVERLAY, true /* mutable */, false /* enabled */, 0 /* priority */);

        impl.updateOverlaysForUser(USER);
        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertFalse(o1.isEnabled());
        assertTrue(o1.isMutable);

        configureSystemOverlay(OVERLAY, true /* mutable */, true /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o2);
        assertFalse(o2.isEnabled());
        assertTrue(o2.isMutable);

        configureSystemOverlay(OVERLAY, true /* mutable */, false /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);
        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertFalse(o3.isEnabled());
        assertTrue(o3.isMutable);
    }

    @Test
    public void testMutableEnabledToImmutableEnabled() {
        final OverlayManagerServiceImpl impl = getImpl();
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);

        final BiConsumer<Boolean, Boolean> setOverlay = (mutable, enabled) -> {
            configureSystemOverlay(OVERLAY, mutable, enabled, 0 /* priority */);
            impl.updateOverlaysForUser(USER);
            final OverlayInfo o = impl.getOverlayInfo(OVERLAY, USER);
            assertNotNull(o);
            assertEquals(enabled, o.isEnabled());
            assertEquals(mutable, o.isMutable);
        };

        // Immutable/enabled -> mutable/enabled
        setOverlay.accept(false /* mutable */, true /* enabled */);
        setOverlay.accept(true /* mutable */, true /* enabled */);

        // Mutable/enabled -> immutable/enabled
        setOverlay.accept(false /* mutable */, true /* enabled */);

        // Immutable/enabled -> mutable/disabled
        setOverlay.accept(true /* mutable */, false /* enabled */);

        // Mutable/disabled -> immutable/enabled
        setOverlay.accept(false /* mutable */, true /* enabled */);

        // Immutable/enabled -> immutable/disabled
        setOverlay.accept(false /* mutable */, false /* enabled */);

        // Immutable/disabled -> mutable/enabled
        setOverlay.accept(true /* mutable */, true /* enabled */);

        // Mutable/enabled -> immutable/disabled
        setOverlay.accept(false /* mutable */, false /* enabled */);

        // Immutable/disabled -> mutable/disabled
        setOverlay.accept(true /* mutable */, false /* enabled */);

        // Mutable/disabled -> immutable/disabled
        setOverlay.accept(false /* mutable */, false /* enabled */);
    }

    @Test
    public void testMutablePriorityChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        installNewPackage(overlay(OVERLAY2, TARGET), USER);
        configureSystemOverlay(OVERLAY, true /* mutable */, false /* enabled */, 0 /* priority */);
        configureSystemOverlay(OVERLAY2, true /* mutable */, false /* enabled */, 1 /* priority */);
        impl.updateOverlaysForUser(USER);

        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertEquals(0, o1.priority);
        assertFalse(o1.isEnabled());

        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY2, USER);
        assertNotNull(o2);
        assertEquals(1, o2.priority);
        assertFalse(o2.isEnabled());

        // Overlay priority changing between reboots should not affect enable state of mutable
        // overlays.
        impl.setEnabled(OVERLAY, true, USER);

        // Reorder the overlays
        configureSystemOverlay(OVERLAY, true /* mutable */, false /* enabled */, 1 /* priority */);
        configureSystemOverlay(OVERLAY2, true /* mutable */, false /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);

        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertEquals(1, o3.priority);
        assertTrue(o3.isEnabled());

        final OverlayInfo o4 = impl.getOverlayInfo(OVERLAY2, USER);
        assertNotNull(o4);
        assertEquals(0, o4.priority);
        assertFalse(o4.isEnabled());
    }

    @Test
    public void testImmutablePriorityChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        installNewPackage(target(TARGET), USER);
        installNewPackage(overlay(OVERLAY, TARGET), USER);
        installNewPackage(overlay(OVERLAY2, TARGET), USER);
        configureSystemOverlay(OVERLAY, false /* mutable */, true /* enabled */, 0 /* priority */);
        configureSystemOverlay(OVERLAY2, false /* mutable */, true /* enabled */, 1 /* priority */);
        impl.updateOverlaysForUser(USER);

        final OverlayInfo o1 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o1);
        assertEquals(0, o1.priority);
        assertTrue(o1.isEnabled());

        final OverlayInfo o2 = impl.getOverlayInfo(OVERLAY2, USER);
        assertNotNull(o2);
        assertEquals(1, o2.priority);
        assertTrue(o2.isEnabled());

        // Reorder the overlays
        configureSystemOverlay(OVERLAY, false /* mutable */, true /* enabled */, 1 /* priority */);
        configureSystemOverlay(OVERLAY2, false /* mutable */, true /* enabled */, 0 /* priority */);
        impl.updateOverlaysForUser(USER);

        final OverlayInfo o3 = impl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(o3);
        assertEquals(1, o3.priority);
        assertTrue(o3.isEnabled());

        final OverlayInfo o4 = impl.getOverlayInfo(OVERLAY2, USER);
        assertNotNull(o4);
        assertEquals(0, o4.priority);
        assertTrue(o4.isEnabled());
    }
}
