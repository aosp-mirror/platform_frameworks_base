/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard;

import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.hardware.display.DisplayManagerGlobal;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.settings.FakeDisplayTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardDisplayManagerTest extends SysuiTestCase {

    @Mock
    private NavigationBarController mNavigationBarController;
    @Mock
    private KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    @Mock
    private KeyguardDisplayManager.KeyguardPresentation mKeyguardPresentation;

    private Executor mMainExecutor = Runnable::run;
    private Executor mBackgroundExecutor = Runnable::run;
    private KeyguardDisplayManager mManager;
    private FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);

    // The default and secondary displays are both in the default group
    private Display mDefaultDisplay;
    private Display mSecondaryDisplay;

    // This display is in a different group from the default and secondary displays.
    private Display mAlwaysUnlockedDisplay;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mManager = spy(new KeyguardDisplayManager(mContext, () -> mNavigationBarController,
                mKeyguardStatusViewComponentFactory, mDisplayTracker, mMainExecutor,
                mBackgroundExecutor));
        doReturn(mKeyguardPresentation).when(mManager).createPresentation(any());

        mDefaultDisplay = new Display(DisplayManagerGlobal.getInstance(), Display.DEFAULT_DISPLAY,
                new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS);
        mSecondaryDisplay = new Display(DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY + 1,
                new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS);

        DisplayInfo alwaysUnlockedDisplayInfo = new DisplayInfo();
        alwaysUnlockedDisplayInfo.displayId = Display.DEFAULT_DISPLAY + 2;
        alwaysUnlockedDisplayInfo.flags = Display.FLAG_ALWAYS_UNLOCKED;
        mAlwaysUnlockedDisplay = new Display(DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY,
                alwaysUnlockedDisplayInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    @Test
    public void testShow_defaultDisplayOnly() {
        mDisplayTracker.setAllDisplays(new Display[]{mDefaultDisplay});
        mManager.show();
        verify(mManager, never()).createPresentation(any());
    }

    @Test
    public void testShow_includeSecondaryDisplay() {
        mDisplayTracker.setAllDisplays(new Display[]{mDefaultDisplay, mSecondaryDisplay});
        mManager.show();
        verify(mManager, times(1)).createPresentation(eq(mSecondaryDisplay));
    }

    @Test
    public void testShow_includeAlwaysUnlockedDisplay() {
        mDisplayTracker.setAllDisplays(new Display[]{mDefaultDisplay, mAlwaysUnlockedDisplay});

        mManager.show();
        verify(mManager, never()).createPresentation(any());
    }

    @Test
    public void testShow_includeSecondaryAndAlwaysUnlockedDisplays() {
        mDisplayTracker.setAllDisplays(
                new Display[]{mDefaultDisplay, mSecondaryDisplay, mAlwaysUnlockedDisplay});
        mManager.show();
        verify(mManager, times(1)).createPresentation(eq(mSecondaryDisplay));
    }
}
