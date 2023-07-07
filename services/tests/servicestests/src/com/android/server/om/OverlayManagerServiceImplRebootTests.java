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

import static com.google.common.truth.Truth.assertThat;

import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.pm.UserPackage;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Rule;
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

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void alwaysInitializeAllPackages() {
        final OverlayManagerServiceImpl impl = getImpl();
        final String otherTarget = "some.other.target";
        addPackage(target(TARGET), USER);
        addPackage(target(otherTarget), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);

        final Set<UserPackage> allPackages = Set.of(UserPackage.of(USER, TARGET));

        // The result should be the same for every time
        assertThat(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        assertThat(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
    }

    @Test
    public void testImmutableEnabledChange() {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);

        final Set<UserPackage> allPackages = Set.of(UserPackage.of(USER, TARGET));

        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_DISABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o1).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o1.isEnabled()).isFalse();
        expect.that(o1.isMutable).isFalse();

        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_ENABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o2).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o2.isEnabled()).isTrue();
        expect.that(o2.isMutable).isFalse();

        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_DISABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o3).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o3.isEnabled()).isFalse();
        expect.that(o3.isMutable).isFalse();
    }

    @Test
    public void testMutableEnabledChangeHasNoEffect() {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);
        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 0 /* priority */);

        final Set<UserPackage> allPackages = Set.of(UserPackage.of(USER, TARGET));

        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o1).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o1.isEnabled()).isFalse();
        expect.that(o1.isMutable).isTrue();

        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_ENABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o2).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o2.isEnabled()).isFalse();
        expect.that(o2.isMutable).isTrue();

        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o3).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o3.isEnabled()).isFalse();
        expect.that(o3.isMutable).isTrue();
    }

    @Test
    public void testMutableEnabledToImmutableEnabled() {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);

        final Set<UserPackage> allPackages = Set.of(UserPackage.of(USER, TARGET));

        final Consumer<ConfigState> setOverlay = (state -> {
            configureSystemOverlay(OVERLAY, state, 0 /* priority */);
            expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
            final OverlayInfo o = impl.getOverlayInfo(IDENTIFIER, USER);
            expect.that(o).isNotNull();
            assertThat(expect.hasFailures()).isFalse();
            expect.that(o.isEnabled()).isEqualTo(state == ConfigState.IMMUTABLE_ENABLED
                    || state == ConfigState.MUTABLE_ENABLED);
            expect.that(o.isMutable).isEqualTo(state == ConfigState.MUTABLE_DISABLED
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

        final Set<UserPackage> allPackages = Set.of(UserPackage.of(USER, TARGET));

        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o1).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o1.priority).isEqualTo(0);
        expect.that(o1.isEnabled()).isFalse();

        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER2, USER);
        expect.that(o2).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o2.priority).isEqualTo(1);
        expect.that(o2.isEnabled()).isFalse();

        // Overlay priority changing between reboots should not affect enable state of mutable
        // overlays.
        impl.setEnabled(IDENTIFIER, true, USER);

        // Reorder the overlays
        configureSystemOverlay(OVERLAY, ConfigState.MUTABLE_DISABLED, 1 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.MUTABLE_DISABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o3).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o3.priority).isEqualTo(1);
        expect.that(o3.isEnabled()).isTrue();

        final OverlayInfo o4 = impl.getOverlayInfo(IDENTIFIER2, USER);
        expect.that(o4).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o4.priority).isEqualTo(0);
        expect.that(o4.isEnabled()).isFalse();
    }

    @Test
    public void testImmutablePriorityChange() throws Exception {
        final OverlayManagerServiceImpl impl = getImpl();
        addPackage(target(TARGET), USER);
        addPackage(overlay(OVERLAY, TARGET), USER);
        addPackage(overlay(OVERLAY2, TARGET), USER);
        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_ENABLED, 0 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.IMMUTABLE_ENABLED, 1 /* priority */);

        final Set<UserPackage> allPackages = Set.of(UserPackage.of(USER, TARGET));

        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o1 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o1).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o1.priority).isEqualTo(0);
        expect.that(o1.isEnabled()).isTrue();

        final OverlayInfo o2 = impl.getOverlayInfo(IDENTIFIER2, USER);
        expect.that(o2).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o2.priority).isEqualTo(1);
        expect.that(o2.isEnabled()).isTrue();

        // Reorder the overlays
        configureSystemOverlay(OVERLAY, ConfigState.IMMUTABLE_ENABLED, 1 /* priority */);
        configureSystemOverlay(OVERLAY2, ConfigState.IMMUTABLE_ENABLED, 0 /* priority */);
        expect.that(impl.updateOverlaysForUser(USER)).isEqualTo(allPackages);
        final OverlayInfo o3 = impl.getOverlayInfo(IDENTIFIER, USER);
        expect.that(o3).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o3.priority).isEqualTo(1);
        expect.that(o3.isEnabled()).isTrue();

        final OverlayInfo o4 = impl.getOverlayInfo(IDENTIFIER2, USER);
        expect.that(o4).isNotNull();
        assertThat(expect.hasFailures()).isFalse();
        expect.that(o4.priority).isEqualTo(0);
        expect.that(o4.isEnabled()).isTrue();
    }
}
