/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.LetterboxConfiguration.DEVICE_CONFIG_KEY_ENABLE_COMPAT_FAKE_FOCUS;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Tests for the {@link LetterboxConfiguration} class.
 *
 * Build/Install/Run:
 *  atest WmTests:LetterboxConfigurationTests
 */
@SmallTest
@Presubmit
public class LetterboxConfigurationTest {

    private Context mContext;
    private LetterboxConfiguration mLetterboxConfiguration;
    private LetterboxConfigurationPersister mLetterboxConfigurationPersister;

    @Before
    public void setUp() throws Exception {
        mContext = getInstrumentation().getTargetContext();
        mLetterboxConfigurationPersister = mock(LetterboxConfigurationPersister.class);
        mLetterboxConfiguration = new LetterboxConfiguration(mContext,
                mLetterboxConfigurationPersister);
    }

    @Test
    public void test_whenReadingValues_storeIsInvoked() {
        for (boolean halfFoldPose : Arrays.asList(false, true)) {
            mLetterboxConfiguration.getLetterboxPositionForHorizontalReachability(halfFoldPose);
            verify(mLetterboxConfigurationPersister).getLetterboxPositionForHorizontalReachability(
                    halfFoldPose);
            mLetterboxConfiguration.getLetterboxPositionForVerticalReachability(halfFoldPose);
            verify(mLetterboxConfigurationPersister).getLetterboxPositionForVerticalReachability(
                    halfFoldPose);
        }
    }

    @Test
    public void test_whenSettingValues_updateConfigurationIsInvoked() {
        for (boolean halfFoldPose : Arrays.asList(false, true)) {
            mLetterboxConfiguration.movePositionForHorizontalReachabilityToNextRightStop(
                    halfFoldPose);
            verify(mLetterboxConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                    eq(halfFoldPose), anyInt());
            mLetterboxConfiguration.movePositionForVerticalReachabilityToNextBottomStop(
                    halfFoldPose);
            verify(mLetterboxConfigurationPersister).setLetterboxPositionForVerticalReachability(
                    eq(halfFoldPose), anyInt());
        }
    }

    @Test
    public void test_whenMovedHorizontally_updatePositionAccordingly() {
        // Starting from center
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from left
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from right
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        // Starting from left - book mode
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from right - book mode
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
    }

    @Test
    public void test_whenMovedVertically_updatePositionAccordingly() {
        // Starting from center
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from top
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from bottom
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        // Starting from top - tabletop mode
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from bottom - tabletop mode
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
    }

    @Test
    public void testIsCompatFakeFocusEnabledOnDevice() {
        boolean wasFakeFocusEnabled = DeviceConfig
                .getBoolean(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                DEVICE_CONFIG_KEY_ENABLE_COMPAT_FAKE_FOCUS, false);

        // Set runtime flag to true and build time flag to false
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                DEVICE_CONFIG_KEY_ENABLE_COMPAT_FAKE_FOCUS, "true", false);
        mLetterboxConfiguration.setIsCompatFakeFocusEnabled(false);
        assertFalse(mLetterboxConfiguration.isCompatFakeFocusEnabledOnDevice());

        // Set runtime flag to false and build time flag to true
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                DEVICE_CONFIG_KEY_ENABLE_COMPAT_FAKE_FOCUS, "false", false);
        mLetterboxConfiguration.setIsCompatFakeFocusEnabled(true);
        assertFalse(mLetterboxConfiguration.isCompatFakeFocusEnabledOnDevice());

        // Set runtime flag to true so that both are enabled
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                DEVICE_CONFIG_KEY_ENABLE_COMPAT_FAKE_FOCUS, "true", false);
        assertTrue(mLetterboxConfiguration.isCompatFakeFocusEnabledOnDevice());

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                DEVICE_CONFIG_KEY_ENABLE_COMPAT_FAKE_FOCUS, Boolean.toString(wasFakeFocusEnabled),
                false);
    }

    private void assertForHorizontalMove(int from, int expected, int expectedTime,
            boolean halfFoldPose, BiConsumer<LetterboxConfiguration, Boolean> move) {
        // We are in the current position
        when(mLetterboxConfiguration.getLetterboxPositionForHorizontalReachability(halfFoldPose))
                .thenReturn(from);
        move.accept(mLetterboxConfiguration, halfFoldPose);
        verify(mLetterboxConfigurationPersister,
                times(expectedTime)).setLetterboxPositionForHorizontalReachability(halfFoldPose,
                expected);
    }

    private void assertForVerticalMove(int from, int expected, int expectedTime,
            boolean halfFoldPose, BiConsumer<LetterboxConfiguration, Boolean> move) {
        // We are in the current position
        when(mLetterboxConfiguration.getLetterboxPositionForVerticalReachability(halfFoldPose))
                .thenReturn(from);
        move.accept(mLetterboxConfiguration, halfFoldPose);
        verify(mLetterboxConfigurationPersister,
                times(expectedTime)).setLetterboxPositionForVerticalReachability(halfFoldPose,
                expected);
    }
}
