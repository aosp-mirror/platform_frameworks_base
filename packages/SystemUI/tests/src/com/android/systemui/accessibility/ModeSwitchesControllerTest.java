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

import static org.mockito.Mockito.verify;

import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
/** Tests the ModeSwitchesController. */
public class ModeSwitchesControllerTest extends SysuiTestCase {

    private FakeSwitchSupplier mSupplier;
    @Mock
    private MagnificationModeSwitch mModeSwitch;
    private ModeSwitchesController mModeSwitchesController;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSupplier = new FakeSwitchSupplier(mContext.getSystemService(DisplayManager.class));
        mModeSwitchesController = new ModeSwitchesController(mSupplier);
    }

    @Test
    public void testShowButton() {
        mModeSwitchesController.showButton(Display.DEFAULT_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mModeSwitch).showButton(Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
    }

    @Test
    public void testRemoveButton() {
        mModeSwitchesController.showButton(Display.DEFAULT_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mModeSwitchesController.removeButton(Display.DEFAULT_DISPLAY);

        verify(mModeSwitch).removeButton();
    }

    @Test
    public void testControllerOnConfigurationChanged_notifyShowingButton() {
        mModeSwitchesController.showButton(Display.DEFAULT_DISPLAY,
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mModeSwitchesController.onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);

        verify(mModeSwitch).onConfigurationChanged(ActivityInfo.CONFIG_DENSITY);
    }

    private class FakeSwitchSupplier extends DisplayIdIndexSupplier<MagnificationModeSwitch> {

        FakeSwitchSupplier(DisplayManager displayManager) {
            super(displayManager);
        }

        @Override
        protected MagnificationModeSwitch createInstance(Display display) {
            return mModeSwitch;
        }
    }
}
