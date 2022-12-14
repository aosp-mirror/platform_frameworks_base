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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.PowerManager;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.BrightnessSetting;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;
import com.android.server.display.brightness.strategy.TemporaryBrightnessStrategy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayBrightnessControllerTest {
    private static final int DISPLAY_ID = 1;
    private static final float DEFAULT_BRIGHTNESS = 0.4f;

    @Mock
    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;
    @Mock
    private Context mContext;
    @Mock
    private BrightnessSetting mBrightnessSetting;
    @Mock
    private Runnable mOnBrightnessChangeRunnable;

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
                DISPLAY_ID, DEFAULT_BRIGHTNESS, mBrightnessSetting, mOnBrightnessChangeRunnable);
    }

    @Test
    public void testUpdateBrightness() {
        DisplayPowerRequest displayPowerRequest = mock(DisplayPowerRequest.class);
        DisplayBrightnessStrategy displayBrightnessStrategy = mock(DisplayBrightnessStrategy.class);
        int targetDisplayState = Display.STATE_DOZE;
        when(mDisplayBrightnessStrategySelector.selectStrategy(displayPowerRequest,
                targetDisplayState)).thenReturn(displayBrightnessStrategy);
        mDisplayBrightnessController.updateBrightness(displayPowerRequest, targetDisplayState);
        verify(displayBrightnessStrategy).updateBrightness(displayPowerRequest);
        assertEquals(mDisplayBrightnessController.getCurrentDisplayBrightnessStrategyLocked(),
                displayBrightnessStrategy);
    }

    @Test
    public void isAllowAutoBrightnessWhileDozingConfigDelegatesToDozeBrightnessStrategy() {
        mDisplayBrightnessController.isAllowAutoBrightnessWhileDozingConfig();
        verify(mDisplayBrightnessStrategySelector).isAllowAutoBrightnessWhileDozingConfig();
    }

    @Test
    public void setTemporaryBrightness() {
        float temporaryBrightness = 0.4f;
        TemporaryBrightnessStrategy temporaryBrightnessStrategy = mock(
                TemporaryBrightnessStrategy.class);
        when(mDisplayBrightnessStrategySelector.getTemporaryDisplayBrightnessStrategy()).thenReturn(
                temporaryBrightnessStrategy);
        mDisplayBrightnessController.setTemporaryBrightness(temporaryBrightness);
        verify(temporaryBrightnessStrategy).setTemporaryScreenBrightness(temporaryBrightness);
    }

    @Test
    public void setCurrentScreenBrightness() {
        // Current Screen brightness is set as expected when a different value than the current
        // is set
        float currentScreenBrightness = 0.4f;
        mDisplayBrightnessController.setCurrentScreenBrightness(currentScreenBrightness);
        assertEquals(mDisplayBrightnessController.getCurrentBrightness(),
                currentScreenBrightness, 0.0f);
        verify(mOnBrightnessChangeRunnable).run();

        // No change to the current screen brightness is same as the existing one
        mDisplayBrightnessController.setCurrentScreenBrightness(currentScreenBrightness);
        verifyNoMoreInteractions(mOnBrightnessChangeRunnable);
    }

    @Test
    public void setPendingScreenBrightnessSetting() {
        float pendingScreenBrightness = 0.4f;
        mDisplayBrightnessController.setPendingScreenBrightness(pendingScreenBrightness);
        assertEquals(mDisplayBrightnessController.getPendingScreenBrightness(),
                pendingScreenBrightness, 0.0f);
    }

    @Test
    public void updateUserSetScreenBrightness() {
        // No brightness is set if the pending brightness is invalid
        mDisplayBrightnessController.setPendingScreenBrightness(Float.NaN);
        assertFalse(mDisplayBrightnessController.updateUserSetScreenBrightness());

        // user set brightness is not set if the current and the pending brightness are same.
        float currentBrightness = 0.4f;
        TemporaryBrightnessStrategy temporaryBrightnessStrategy = mock(
                TemporaryBrightnessStrategy.class);
        when(mDisplayBrightnessStrategySelector.getTemporaryDisplayBrightnessStrategy()).thenReturn(
                temporaryBrightnessStrategy);
        mDisplayBrightnessController.setCurrentScreenBrightness(currentBrightness);
        mDisplayBrightnessController.setPendingScreenBrightness(currentBrightness);
        mDisplayBrightnessController.setTemporaryBrightness(currentBrightness);
        assertFalse(mDisplayBrightnessController.updateUserSetScreenBrightness());
        verify(temporaryBrightnessStrategy).setTemporaryScreenBrightness(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertEquals(mDisplayBrightnessController.getPendingScreenBrightness(),
                PowerManager.BRIGHTNESS_INVALID_FLOAT, 0.0f);

        // user set brightness is set as expected
        currentBrightness = 0.4f;
        float pendingScreenBrightness = 0.3f;
        float temporaryScreenBrightness = 0.2f;
        mDisplayBrightnessController.setCurrentScreenBrightness(currentBrightness);
        mDisplayBrightnessController.setPendingScreenBrightness(pendingScreenBrightness);
        mDisplayBrightnessController.setTemporaryBrightness(temporaryScreenBrightness);
        assertTrue(mDisplayBrightnessController.updateUserSetScreenBrightness());
        assertEquals(mDisplayBrightnessController.getCurrentBrightness(),
                pendingScreenBrightness, 0.0f);
        assertEquals(mDisplayBrightnessController.getLastUserSetScreenBrightness(),
                pendingScreenBrightness, 0.0f);
        verify(mOnBrightnessChangeRunnable, times(2)).run();
        verify(temporaryBrightnessStrategy, times(2))
                .setTemporaryScreenBrightness(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertEquals(mDisplayBrightnessController.getPendingScreenBrightness(),
                PowerManager.BRIGHTNESS_INVALID_FLOAT, 0.0f);
    }

    @Test
    public void registerBrightnessSettingChangeListener() {
        BrightnessSetting.BrightnessSettingListener brightnessSettingListener = mock(
                BrightnessSetting.BrightnessSettingListener.class);
        mDisplayBrightnessController.registerBrightnessSettingChangeListener(
                brightnessSettingListener);
        verify(mBrightnessSetting).registerListener(brightnessSettingListener);
        assertEquals(mDisplayBrightnessController.getBrightnessSettingListenerLocked(),
                brightnessSettingListener);
    }

    @Test
    public void getScreenBrightnessSetting() {
        // getScreenBrightnessSetting returns the value relayed by BrightnessSetting, if the
        // valid is valid and in range
        float brightnessSetting = 0.2f;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightnessSetting);
        assertEquals(mDisplayBrightnessController.getScreenBrightnessSetting(), brightnessSetting,
                0.0f);

        // getScreenBrightnessSetting value is clamped if BrightnessSetting returns value beyond max
        brightnessSetting = 1.1f;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightnessSetting);
        assertEquals(mDisplayBrightnessController.getScreenBrightnessSetting(), 1.0f,
                0.0f);

        // getScreenBrightnessSetting returns default value is BrightnessSetting returns invalid
        // value.
        brightnessSetting = Float.NaN;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightnessSetting);
        assertEquals(mDisplayBrightnessController.getScreenBrightnessSetting(), DEFAULT_BRIGHTNESS,
                0.0f);
    }

    @Test
    public void setBrightnessSetsInBrightnessSetting() {
        float brightnessValue = 0.3f;
        mDisplayBrightnessController.setBrightness(brightnessValue);
        verify(mBrightnessSetting).setBrightness(brightnessValue);
    }

    @Test
    public void updateScreenBrightnessSetting() {
        // This interaction happens in the constructor itself
        verify(mBrightnessSetting).getBrightness();

        // Sets the appropriate value when valid, and not equal to the current brightness
        float brightnessValue = 0.3f;
        mDisplayBrightnessController.updateScreenBrightnessSetting(brightnessValue);
        assertEquals(mDisplayBrightnessController.getCurrentBrightness(), brightnessValue,
                0.0f);
        verify(mOnBrightnessChangeRunnable).run();
        verify(mBrightnessSetting).setBrightness(brightnessValue);

        // Does nothing if the value is invalid
        mDisplayBrightnessController.updateScreenBrightnessSetting(Float.NaN);
        verifyNoMoreInteractions(mOnBrightnessChangeRunnable, mBrightnessSetting);

        // Does nothing if the value is same as the current brightness
        brightnessValue = 0.2f;
        mDisplayBrightnessController.setCurrentScreenBrightness(brightnessValue);
        verify(mOnBrightnessChangeRunnable, times(2)).run();
        mDisplayBrightnessController.updateScreenBrightnessSetting(brightnessValue);
        verifyNoMoreInteractions(mOnBrightnessChangeRunnable, mBrightnessSetting);
    }

    @Test
    public void stop() {
        BrightnessSetting.BrightnessSettingListener brightnessSettingListener = mock(
                BrightnessSetting.BrightnessSettingListener.class);
        mDisplayBrightnessController.registerBrightnessSettingChangeListener(
                brightnessSettingListener);
        mDisplayBrightnessController.stop();
        verify(mBrightnessSetting).unregisterListener(brightnessSettingListener);
    }
}
