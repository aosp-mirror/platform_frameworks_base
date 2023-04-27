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

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Color;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.annotation.ColorInt;
import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.statusbar.policy.BatteryController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class LightBarControllerTest extends SysuiTestCase {

    private static final GradientColors COLORS_LIGHT = makeColors(Color.WHITE);
    private static final GradientColors COLORS_DARK = makeColors(Color.BLACK);
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    private LightBarTransitionsController mLightBarTransitionsController;
    private LightBarTransitionsController mNavBarController;
    private SysuiDarkIconDispatcher mStatusBarIconController;
    private LightBarController mLightBarController;

    /** Allow testing with NEW_LIGHT_BAR_LOGIC flag in different states */
    protected boolean testNewLightBarLogic() {
        return false;
    }

    @Before
    public void setup() {
        mFeatureFlags.set(Flags.NEW_LIGHT_BAR_LOGIC, testNewLightBarLogic());
        mStatusBarIconController = mock(SysuiDarkIconDispatcher.class);
        mNavBarController = mock(LightBarTransitionsController.class);
        when(mNavBarController.supportsIconTintForNavMode(anyInt())).thenReturn(true);
        mLightBarTransitionsController = mock(LightBarTransitionsController.class);
        when(mStatusBarIconController.getTransitionsController()).thenReturn(
                mLightBarTransitionsController);
        mLightBarController = new LightBarController(
                mContext,
                mStatusBarIconController,
                mock(BatteryController.class),
                mock(NavigationModeController.class),
                mFeatureFlags,
                mock(DumpManager.class),
                new FakeDisplayTracker(mContext));
    }

    private static GradientColors makeColors(@ColorInt int bgColor) {
        GradientColors colors = new GradientColors();
        colors.setMainColor(bgColor);
        colors.setSecondaryColor(bgColor);
        colors.setSupportsDarkText(!ContrastColorUtil.isColorDark(bgColor));
        return colors;
    }

    @Test
    public void testOnStatusBarAppearanceChanged_multipleStacks_allStacksLight() {
        final Rect firstBounds = new Rect(0, 0, 1, 1);
        final Rect secondBounds = new Rect(1, 0, 2, 1);
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, firstBounds),
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, secondBounds)
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        verify(mStatusBarIconController).setIconsDarkArea(eq(null));
        verify(mLightBarTransitionsController).setIconsDark(eq(true), anyBoolean());
    }

    @Test
    public void testOnStatusBarAppearanceChanged_multipleStacks_oneStackLightOneStackDark() {
        final Rect firstBounds = new Rect(0, 0, 1, 1);
        final Rect secondBounds = new Rect(1, 0, 2, 1);
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, firstBounds),
                new AppearanceRegion(0 /* appearance */, secondBounds)
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        ArgumentCaptor<ArrayList<Rect>> captor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mStatusBarIconController).setIconsDarkArea(captor.capture());
        assertTrue(captor.getValue().contains(firstBounds));
        verify(mLightBarTransitionsController).setIconsDark(eq(true), anyBoolean());
    }

    @Test
    public void testOnStatusBarAppearanceChanged_multipleStacks_oneStackDarkOneStackLight() {
        final Rect firstBounds = new Rect(0, 0, 1, 1);
        final Rect secondBounds = new Rect(1, 0, 2, 1);
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(0 /* appearance */, firstBounds),
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, secondBounds)
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        ArgumentCaptor<ArrayList<Rect>> captor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mStatusBarIconController).setIconsDarkArea(captor.capture());
        assertTrue(captor.getValue().contains(secondBounds));
        verify(mLightBarTransitionsController).setIconsDark(eq(true), anyBoolean());
    }

    @Test
    public void testOnStatusBarAppearanceChanged_multipleStacks_oneStackLightMultipleStackDark() {
        final Rect firstBounds = new Rect(0, 0, 1, 1);
        final Rect secondBounds = new Rect(1, 0, 2, 1);
        final Rect thirdBounds = new Rect(2, 0, 3, 1);
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, firstBounds),
                new AppearanceRegion(0 /* appearance */, secondBounds),
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, thirdBounds)
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        ArgumentCaptor<ArrayList<Rect>> captor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mStatusBarIconController).setIconsDarkArea(captor.capture());
        assertTrue(captor.getValue().contains(firstBounds));
        assertTrue(captor.getValue().contains(thirdBounds));
        verify(mLightBarTransitionsController).setIconsDark(eq(true), anyBoolean());
    }

    @Test
    public void testOnStatusBarAppearanceChanged_multipleStacks_allStacksDark() {
        final Rect firstBounds = new Rect(0, 0, 1, 1);
        final Rect secondBounds = new Rect(1, 0, 2, 1);
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(0 /* appearance */, firstBounds),
                new AppearanceRegion(0 /* appearance */, secondBounds)
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        verify(mLightBarTransitionsController).setIconsDark(eq(false), anyBoolean());
    }

    @Test
    public void testOnStatusBarAppearanceChanged_singleStack_light() {
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, new Rect(0, 0, 1, 1))
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        verify(mStatusBarIconController).setIconsDarkArea(eq(null));
        verify(mLightBarTransitionsController).setIconsDark(eq(true), anyBoolean());
    }

    @Test
    public void testOnStatusBarAppearanceChanged_singleStack_dark() {
        final AppearanceRegion[] appearanceRegions = new AppearanceRegion[]{
                new AppearanceRegion(0, new Rect(0, 0, 1, 1))
        };
        mLightBarController.onStatusBarAppearanceChanged(
                appearanceRegions, true /* sbModeChanged */, MODE_TRANSPARENT,
                false /* navbarColorManagedByIme */);
        verify(mLightBarTransitionsController).setIconsDark(eq(false), anyBoolean());
    }

    @Test
    public void validateNavBarChangesUpdateIcons() {
        assumeTrue(testNewLightBarLogic());  // Only run in the new suite

        // On the launcher in dark mode buttons are light
        mLightBarController.setScrimState(ScrimState.UNLOCKED, 0f, COLORS_DARK);
        mLightBarController.onNavigationBarAppearanceChanged(
                0, /* nbModeChanged = */ true,
                MODE_TRANSPARENT, /* navbarColorManagedByIme = */ false);
        verifyNavBarIconsUnchanged(); // no changes yet; not attached

        // Initial state is set when controller is set
        mLightBarController.setNavigationBar(mNavBarController);
        verifyNavBarIconsDarkSetTo(false);

        // Changing the color of the transparent scrim has no effect
        mLightBarController.setScrimState(ScrimState.UNLOCKED, 0f, COLORS_LIGHT);
        verifyNavBarIconsUnchanged(); // still light

        // Showing the notification shade with white scrim requires dark icons
        mLightBarController.setScrimState(ScrimState.UNLOCKED, 1f, COLORS_LIGHT);
        verifyNavBarIconsDarkSetTo(true);

        // Expanded QS always provides a black background, so icons become light again
        mLightBarController.setQsExpanded(true);
        verifyNavBarIconsDarkSetTo(false);

        // Tapping the QS tile to change to dark theme has no effect in this state
        mLightBarController.setScrimState(ScrimState.UNLOCKED, 1f, COLORS_DARK);
        verifyNavBarIconsUnchanged(); // still light

        // collapsing QS in dark mode doesn't affect button color
        mLightBarController.setQsExpanded(false);
        verifyNavBarIconsUnchanged(); // still light

        // Closing the shade has no affect
        mLightBarController.setScrimState(ScrimState.UNLOCKED, 0f, COLORS_DARK);
        verifyNavBarIconsUnchanged(); // still light
    }

    private void verifyNavBarIconsUnchanged() {
        verify(mNavBarController, never()).setIconsDark(anyBoolean(), anyBoolean());
    }

    private void verifyNavBarIconsDarkSetTo(boolean iconsDark) {
        verify(mNavBarController).setIconsDark(eq(iconsDark), anyBoolean());
        verify(mNavBarController, never()).setIconsDark(eq(!iconsDark), anyBoolean());
        clearInvocations(mNavBarController);
    }
}
