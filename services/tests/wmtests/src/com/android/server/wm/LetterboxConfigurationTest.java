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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
import static com.android.server.wm.testing.Assert.assertThrows;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Tests for the {@link LetterboxConfiguration} class.
 *
 * Build/Install/Run:
 *  atest WmTests:LetterboxConfigurationTest
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

    @Test
    public void test_letterboxPositionWhenReachabilityEnabledIsReset() {
        // Check that horizontal reachability is set with correct arguments
        mLetterboxConfiguration.resetPersistentLetterboxPositionForHorizontalReachability();
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                false /* forBookMode */,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER);
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                true /* forBookMode */,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);

        // Check that vertical reachability is set with correct arguments
        mLetterboxConfiguration.resetPersistentLetterboxPositionForVerticalReachability();
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER);
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForVerticalReachability(
                true /* forTabletopMode */,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
    }

    @Test
    public void test_letterboxPositionWhenReachabilityEnabledIsSet() {
        // Check that horizontal reachability is set with correct arguments
        mLetterboxConfiguration.setPersistentLetterboxPositionForHorizontalReachability(
                false /* forBookMode */, LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                false /* forBookMode */,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);

        // Check that vertical reachability is set with correct arguments
        mLetterboxConfiguration.setPersistentLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */, LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        verify(mLetterboxConfigurationPersister).setLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
    }

    @Test
    public void test_setLetterboxHorizontalPositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(0);
        mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(0.5f);
        mLetterboxConfiguration.setLetterboxHorizontalPositionMultiplier(1);
    }

    @Test
    public void test_setLetterboxVerticalPositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0);
        mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);
        mLetterboxConfiguration.setLetterboxVerticalPositionMultiplier(1);
    }

    @Test
    public void test_setLetterboxBookModePositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxBookModePositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxBookModePositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mLetterboxConfiguration.setLetterboxBookModePositionMultiplier(0);
        mLetterboxConfiguration.setLetterboxBookModePositionMultiplier(0.5f);
        mLetterboxConfiguration.setLetterboxBookModePositionMultiplier(1);
    }

    @Test
    public void test_setLetterboxTabletopModePositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxTabletopModePositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mLetterboxConfiguration.setLetterboxTabletopModePositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mLetterboxConfiguration.setLetterboxTabletopModePositionMultiplier(0);
        mLetterboxConfiguration.setLetterboxTabletopModePositionMultiplier(0.5f);
        mLetterboxConfiguration.setLetterboxTabletopModePositionMultiplier(1);
    }

    @Test
    public void test_evaluateThinLetterboxWhenDensityChanges() {
        final Resources rs = mock(Resources.class);
        final DisplayMetrics dm = mock(DisplayMetrics.class);
        final LetterboxConfigurationPersister lp = mock(LetterboxConfigurationPersister.class);
        spyOn(mContext);
        when(rs.getDisplayMetrics()).thenReturn(dm);
        when(mContext.getResources()).thenReturn(rs);
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxWidthDp))
                .thenReturn(100);
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxHeightDp))
                .thenReturn(200);
        final LetterboxConfiguration configuration = new LetterboxConfiguration(mContext, lp);

        // Verify the values are the expected ones
        dm.density = 100;
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxWidthDp))
                .thenReturn(100);
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxHeightDp))
                .thenReturn(200);
        final int thinWidthPx = configuration.getThinLetterboxWidthPx();
        final int thinHeightPx = configuration.getThinLetterboxHeightPx();
        assertEquals(100, thinWidthPx);
        assertEquals(200, thinHeightPx);

        // We change the values in the resources but not the update condition (density) and the
        // result should not change
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxWidthDp))
                .thenReturn(300);
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxHeightDp))
                .thenReturn(400);
        final int thinWidthPx2 = configuration.getThinLetterboxWidthPx();
        final int thinHeightPx2 = configuration.getThinLetterboxHeightPx();
        assertEquals(100, thinWidthPx2);
        assertEquals(200, thinHeightPx2);

        // We update the condition (density) so the new resource values should be read
        dm.density = 150;
        final int thinWidthPx3 = configuration.getThinLetterboxWidthPx();
        final int thinHeightPx3 = configuration.getThinLetterboxHeightPx();
        assertEquals(300, thinWidthPx3);
        assertEquals(400, thinHeightPx3);
    }
}
