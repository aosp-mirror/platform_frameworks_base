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
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.navigationbar.NavigationBarController;

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
    private DisplayManager mDisplayManager;
    @Mock
    private KeyguardDisplayManager.KeyguardPresentation mKeyguardPresentation;

    private Executor mBackgroundExecutor = Runnable::run;
    private KeyguardDisplayManager mManager;

    // The default and secondary displays are both in the default group
    private Display mDefaultDisplay;
    private Display mSecondaryDisplay;

    // This display is in a different group from the default and secondary displays.
    private Display mDifferentGroupDisplay;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(DisplayManager.class, mDisplayManager);
        mManager = spy(new KeyguardDisplayManager(mContext, () -> mNavigationBarController,
                mKeyguardStatusViewComponentFactory, mBackgroundExecutor));
        doReturn(mKeyguardPresentation).when(mManager).createPresentation(any());

        mDefaultDisplay = new Display(DisplayManagerGlobal.getInstance(), Display.DEFAULT_DISPLAY,
                new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS);
        mSecondaryDisplay = new Display(DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY + 1,
                new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS);

        DisplayInfo differentGroupInfo = new DisplayInfo();
        differentGroupInfo.displayId = Display.DEFAULT_DISPLAY + 2;
        differentGroupInfo.displayGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
        mDifferentGroupDisplay = new Display(DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY,
                differentGroupInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    @Test
    public void testShow_defaultDisplayOnly() {
        when(mDisplayManager.getDisplays()).thenReturn(new Display[]{mDefaultDisplay});
        mManager.show();
        verify(mManager, never()).createPresentation(any());
    }

    @Test
    public void testShow_includeSecondaryDisplay() {
        when(mDisplayManager.getDisplays()).thenReturn(
                new Display[]{mDefaultDisplay, mSecondaryDisplay});
        mManager.show();
        verify(mManager, times(1)).createPresentation(eq(mSecondaryDisplay));
    }

    @Test
    public void testShow_includeNonDefaultGroupDisplay() {
        when(mDisplayManager.getDisplays()).thenReturn(
                new Display[]{mDefaultDisplay, mDifferentGroupDisplay});

        mManager.show();
        verify(mManager, never()).createPresentation(any());
    }

    @Test
    public void testShow_includeSecondaryAndNonDefaultGroupDisplays() {
        when(mDisplayManager.getDisplays()).thenReturn(
                new Display[]{mDefaultDisplay, mSecondaryDisplay, mDifferentGroupDisplay});

        mManager.show();
        verify(mManager, times(1)).createPresentation(eq(mSecondaryDisplay));
    }
}
