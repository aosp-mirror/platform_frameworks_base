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

import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.function.Consumer;

@SmallTest
@Presubmit
public class LetterboxConfigurationTest {

    private LetterboxConfiguration mLetterboxConfiguration;
    private LetterboxConfigurationPersister mLetterboxConfigurationPersister;

    @Before
    public void setUp() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        mLetterboxConfigurationPersister = mock(LetterboxConfigurationPersister.class);
        mLetterboxConfiguration = new LetterboxConfiguration(context,
                mLetterboxConfigurationPersister);
    }

    @Test
    public void test_whenReadingValues_storeIsInvoked() {
        mLetterboxConfiguration.getLetterboxPositionForHorizontalReachability();
        verify(mLetterboxConfigurationPersister).getLetterboxPositionForHorizontalReachability();
        mLetterboxConfiguration.getLetterboxPositionForVerticalReachability();
        verify(mLetterboxConfigurationPersister).getLetterboxPositionForVerticalReachability();
    }

    @Test
    public void test_whenSettingValues_updateConfigurationIsInvoked() {
        mLetterboxConfiguration.movePositionForHorizontalReachabilityToNextRightStop();
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                anyInt());
        mLetterboxConfiguration.movePositionForVerticalReachabilityToNextBottomStop();
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForVerticalReachability(
                anyInt());
    }

    @Test
    public void test_whenMovedHorizontally_updatePositionAccordingly() {
        // Starting from center
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 1,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 1,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from left
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 2,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 1,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from right
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 2,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 2,
                LetterboxConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
    }

    @Test
    public void test_whenMovedVertically_updatePositionAccordingly() {
        // Starting from center
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 1,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 1,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from top
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 1,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 2,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from bottom
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 2,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 2,
                LetterboxConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
    }

    private void assertForHorizontalMove(int from, int expected, int expectedTime,
            Consumer<LetterboxConfiguration> move) {
        // We are in the current position
        when(mLetterboxConfiguration.getLetterboxPositionForHorizontalReachability())
                .thenReturn(from);
        move.accept(mLetterboxConfiguration);
        verify(mLetterboxConfigurationPersister,
                times(expectedTime)).setLetterboxPositionForHorizontalReachability(
                expected);
    }

    private void assertForVerticalMove(int from, int expected, int expectedTime,
            Consumer<LetterboxConfiguration> move) {
        // We are in the current position
        when(mLetterboxConfiguration.getLetterboxPositionForVerticalReachability())
                .thenReturn(from);
        move.accept(mLetterboxConfiguration);
        verify(mLetterboxConfigurationPersister,
                times(expectedTime)).setLetterboxPositionForVerticalReachability(
                expected);
    }
}
