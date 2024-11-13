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
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
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
 * Tests for the {@link AppCompatConfiguration} class.
 *
 * Build/Install/Run:
 *  atest WmTests:AppCompatConfigurationTest
 */
@SmallTest
@Presubmit
public class AppCompatConfigurationTest {

    private Context mContext;
    private AppCompatConfiguration mAppCompatConfiguration;
    private AppCompatConfigurationPersister mAppCompatConfigurationPersister;

    @Before
    public void setUp() throws Exception {
        mContext = getInstrumentation().getTargetContext();
        mAppCompatConfigurationPersister = mock(AppCompatConfigurationPersister.class);
        mAppCompatConfiguration = new AppCompatConfiguration(mContext,
                mAppCompatConfigurationPersister);
    }

    @Test
    public void test_whenReadingValues_storeIsInvoked() {
        for (boolean halfFoldPose : Arrays.asList(false, true)) {
            mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(halfFoldPose);
            verify(mAppCompatConfigurationPersister).getLetterboxPositionForHorizontalReachability(
                    halfFoldPose);
            mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(halfFoldPose);
            verify(mAppCompatConfigurationPersister).getLetterboxPositionForVerticalReachability(
                    halfFoldPose);
        }
    }

    @Test
    public void test_whenSettingValues_updateConfigurationIsInvoked() {
        for (boolean halfFoldPose : Arrays.asList(false, true)) {
            mAppCompatConfiguration.movePositionForHorizontalReachabilityToNextRightStop(
                    halfFoldPose);
            verify(mAppCompatConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                    eq(halfFoldPose), anyInt());
            mAppCompatConfiguration.movePositionForVerticalReachabilityToNextBottomStop(
                    halfFoldPose);
            verify(mAppCompatConfigurationPersister).setLetterboxPositionForVerticalReachability(
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
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from left
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from right
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        // Starting from left - book mode
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        // Starting from right - book mode
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextRightStop);
        assertForHorizontalMove(
                /* from */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT,
                /* expected */ LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForHorizontalReachabilityToNextLeftStop);
    }

    @Test
    public void test_whenMovedVertically_updatePositionAccordingly() {
        // Starting from center
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from top
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 1,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from bottom
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 2,
                /* halfFoldPose */ false,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        // Starting from top - tabletop mode
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 1,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        // Starting from bottom - tabletop mode
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextTopStop);
        assertForVerticalMove(
                /* from */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expected */ LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM,
                /* expectedTime */ 2,
                /* halfFoldPose */ true,
                AppCompatConfiguration::movePositionForVerticalReachabilityToNextBottomStop);
    }

    private void assertForHorizontalMove(int from, int expected, int expectedTime,
            boolean halfFoldPose, BiConsumer<AppCompatConfiguration, Boolean> move) {
        // We are in the current position
        when(mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(halfFoldPose))
                .thenReturn(from);
        move.accept(mAppCompatConfiguration, halfFoldPose);
        verify(mAppCompatConfigurationPersister,
                times(expectedTime)).setLetterboxPositionForHorizontalReachability(halfFoldPose,
                expected);
    }

    private void assertForVerticalMove(int from, int expected, int expectedTime,
            boolean halfFoldPose, BiConsumer<AppCompatConfiguration, Boolean> move) {
        // We are in the current position
        when(mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(halfFoldPose))
                .thenReturn(from);
        move.accept(mAppCompatConfiguration, halfFoldPose);
        verify(mAppCompatConfigurationPersister,
                times(expectedTime)).setLetterboxPositionForVerticalReachability(halfFoldPose,
                expected);
    }

    @Test
    public void test_letterboxPositionWhenReachabilityEnabledIsReset() {
        // Check that horizontal reachability is set with correct arguments
        mAppCompatConfiguration.resetPersistentLetterboxPositionForHorizontalReachability();
        verify(mAppCompatConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                false /* forBookMode */,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER);
        verify(mAppCompatConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                true /* forBookMode */,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);

        // Check that vertical reachability is set with correct arguments
        mAppCompatConfiguration.resetPersistentLetterboxPositionForVerticalReachability();
        verify(mAppCompatConfigurationPersister).setLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER);
        verify(mAppCompatConfigurationPersister).setLetterboxPositionForVerticalReachability(
                true /* forTabletopMode */,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
    }

    @Test
    public void test_letterboxPositionWhenReachabilityEnabledIsSet() {
        // Check that horizontal reachability is set with correct arguments
        mAppCompatConfiguration.setPersistentLetterboxPositionForHorizontalReachability(
                false /* forBookMode */, LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        verify(mAppCompatConfigurationPersister).setLetterboxPositionForHorizontalReachability(
                false /* forBookMode */,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);

        // Check that vertical reachability is set with correct arguments
        mAppCompatConfiguration.setPersistentLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */, LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        verify(mAppCompatConfigurationPersister).setLetterboxPositionForVerticalReachability(
                false /* forTabletopMode */,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
    }

    @Test
    public void test_setLetterboxHorizontalPositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxHorizontalPositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxHorizontalPositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mAppCompatConfiguration.setLetterboxHorizontalPositionMultiplier(0);
        mAppCompatConfiguration.setLetterboxHorizontalPositionMultiplier(0.5f);
        mAppCompatConfiguration.setLetterboxHorizontalPositionMultiplier(1);
    }

    @Test
    public void test_setLetterboxVerticalPositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxVerticalPositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxVerticalPositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mAppCompatConfiguration.setLetterboxVerticalPositionMultiplier(0);
        mAppCompatConfiguration.setLetterboxVerticalPositionMultiplier(0.5f);
        mAppCompatConfiguration.setLetterboxVerticalPositionMultiplier(1);
    }

    @Test
    public void test_setLetterboxBookModePositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxBookModePositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxBookModePositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mAppCompatConfiguration.setLetterboxBookModePositionMultiplier(0);
        mAppCompatConfiguration.setLetterboxBookModePositionMultiplier(0.5f);
        mAppCompatConfiguration.setLetterboxBookModePositionMultiplier(1);
    }

    @Test
    public void test_setLetterboxTabletopModePositionMultiplier_validValues() {
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxTabletopModePositionMultiplier(-1));
        assertThrows(IllegalArgumentException.class,
                () -> mAppCompatConfiguration.setLetterboxTabletopModePositionMultiplier(2));

        // Does not throw an exception for values [0,1].
        mAppCompatConfiguration.setLetterboxTabletopModePositionMultiplier(0);
        mAppCompatConfiguration.setLetterboxTabletopModePositionMultiplier(0.5f);
        mAppCompatConfiguration.setLetterboxTabletopModePositionMultiplier(1);
    }

    @Test
    public void test_evaluateThinLetterboxWhenDensityChanges() {
        final Resources rs = mock(Resources.class);
        final DisplayMetrics dm = mock(DisplayMetrics.class);
        final AppCompatConfigurationPersister lp = mock(AppCompatConfigurationPersister.class);
        spyOn(mContext);
        when(rs.getDisplayMetrics()).thenReturn(dm);
        when(mContext.getResources()).thenReturn(rs);
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxWidthDp))
                .thenReturn(100);
        when(rs.getDimensionPixelSize(R.dimen.config_letterboxThinLetterboxHeightDp))
                .thenReturn(200);
        final AppCompatConfiguration configuration = new AppCompatConfiguration(mContext, lp);

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
