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

package com.android.systemui.accessibility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
/** Tests the ModeSwitchesController. */
public class ModeSwitchesControllerTest extends SysuiTestCase {

    private WindowManager mWindowManager;
    private ModeSwitchesController mModeSwitchesController;

    @Before
    public void setUp() {
        mWindowManager = mock(WindowManager.class);
        Display display = mContext.getSystemService(WindowManager.class).getDefaultDisplay();
        when(mWindowManager.getDefaultDisplay()).thenReturn(display);
        WindowMetrics metrics = mContext.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        when(mWindowManager.getMaximumWindowMetrics()).thenReturn(metrics);
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        mModeSwitchesController = new ModeSwitchesController(mContext);
    }

    @Test
    public void testShowButton() {
        mModeSwitchesController.showButton(Display.DEFAULT_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        verify(mWindowManager).addView(any(), any());
    }

    @Test
    public void testRemoveButton() {
        mModeSwitchesController.showButton(Display.DEFAULT_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        ArgumentCaptor<View> captor = ArgumentCaptor.forClass(View.class);
        verify(mWindowManager).addView(captor.capture(), any(WindowManager.LayoutParams.class));

        mModeSwitchesController.removeButton(Display.DEFAULT_DISPLAY);

        verify(mWindowManager).removeView(eq(captor.getValue()));
    }
}
