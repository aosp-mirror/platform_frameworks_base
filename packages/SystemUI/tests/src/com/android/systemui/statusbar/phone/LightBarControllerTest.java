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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.view.AppearanceRegion;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.policy.BatteryController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class LightBarControllerTest extends SysuiTestCase {

    private LightBarTransitionsController mLightBarTransitionsController;
    private SysuiDarkIconDispatcher mStatusBarIconController;
    private LightBarController mLightBarController;

    @Before
    public void setup() {
        mStatusBarIconController = mock(SysuiDarkIconDispatcher.class);
        mLightBarTransitionsController = mock(LightBarTransitionsController.class);
        when(mStatusBarIconController.getTransitionsController()).thenReturn(
                mLightBarTransitionsController);
        mLightBarController = new LightBarController(
                mContext,
                mStatusBarIconController,
                mock(BatteryController.class),
                mock(NavigationModeController.class),
                mock(DumpManager.class));
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
        verify(mStatusBarIconController).setIconsDarkArea(eq(firstBounds));
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
        verify(mStatusBarIconController).setIconsDarkArea(eq(secondBounds));
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
}
