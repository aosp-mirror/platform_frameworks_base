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

package com.android.server.display.brightness;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayBrightnessControllerTest {
    private static final int DISPLAY_ID = 1;

    @Mock
    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;
    @Mock
    private Context mContext;

    private DisplayBrightnessController mDisplayBrightnessController;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        DisplayBrightnessController.Injector injector = new DisplayBrightnessController.Injector() {
            @Override
            DisplayBrightnessStrategySelector getDisplayBrightnessStrategySelector(
                    Context context, int displayId) {
                return mDisplayBrightnessStrategySelector;
            }
        };
        mDisplayBrightnessController = new DisplayBrightnessController(mContext, injector,
                DISPLAY_ID);
    }

    @Test
    public void updateBrightnessWorksAsExpected() {
        DisplayPowerRequest displayPowerRequest = mock(DisplayPowerRequest.class);
        DisplayBrightnessStrategy displayBrightnessStrategy = mock(DisplayBrightnessStrategy.class);
        int targetDisplayState = Display.STATE_DOZE;
        when(mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                targetDisplayState)).thenReturn(displayBrightnessStrategy);
        mDisplayBrightnessController.updateBrightness(displayPowerRequest, targetDisplayState);
        verify(displayBrightnessStrategy).updateBrightness(displayPowerRequest);
    }

    @Test
    public void isAllowAutoBrightnessWhileDozingConfigDelegatesToDozeBrightnessStrategy() {
        mDisplayBrightnessController.isAllowAutoBrightnessWhileDozingConfig();
        verify(mDisplayBrightnessStrategySelector).isAllowAutoBrightnessWhileDozingConfig();
    }
}
