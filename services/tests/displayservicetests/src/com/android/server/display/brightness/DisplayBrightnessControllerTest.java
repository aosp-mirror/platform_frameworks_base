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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.BrightnessSetting;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;
import com.android.server.display.brightness.strategy.OffloadBrightnessStrategy;
import com.android.server.display.brightness.strategy.TemporaryBrightnessStrategy;
import com.android.server.display.feature.DisplayManagerFlags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayBrightnessControllerTest {
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final float DEFAULT_BRIGHTNESS = 0.15f;

    @Mock
    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private BrightnessSetting mBrightnessSetting;
    @Mock
    private Runnable mOnBrightnessChangeRunnable;
    @Mock
    private DisplayManagerFlags mDisplayManagerFlags;

    @Mock
    private HandlerExecutor mBrightnessChangeExecutor;

    private final DisplayBrightnessController.Injector mInjector = new
            DisplayBrightnessController.Injector() {
        @Override
        DisplayBrightnessStrategySelector getDisplayBrightnessStrategySelector(
                Context context, int displayId, DisplayManagerFlags flags) {
            return mDisplayBrightnessStrategySelector;
        }
    };

    private DisplayBrightnessController mDisplayBrightnessController;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        when(mBrightnessSetting.getBrightness()).thenReturn(Float.NaN);
        when(mBrightnessSetting.getBrightnessNitsForDefaultDisplay()).thenReturn(-1f);
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_persistBrightnessNitsForDefaultDisplay))
                .thenReturn(true);
        mDisplayBrightnessController = new DisplayBrightnessController(mContext, mInjector,
                DISPLAY_ID, DEFAULT_BRIGHTNESS, mBrightnessSetting, mOnBrightnessChangeRunnable,
                mBrightnessChangeExecutor, mDisplayManagerFlags);
    }

    @Test
    public void testIfFirstScreenBrightnessIsDefault() {
        assertEquals(DEFAULT_BRIGHTNESS, mDisplayBrightnessController.getCurrentBrightness(),
                /* delta= */ 0.0f);
    }

    @Test
    public void testUpdateBrightness() {
        DisplayPowerRequest displayPowerRequest = mock(DisplayPowerRequest.class);
        DisplayBrightnessStrategy displayBrightnessStrategy = mock(DisplayBrightnessStrategy.class);
        int targetDisplayState = Display.STATE_DOZE;
        when(mDisplayBrightnessStrategySelector.selectStrategy(
                any(StrategySelectionRequest.class))).thenReturn(displayBrightnessStrategy);
        mDisplayBrightnessController.updateBrightness(displayPowerRequest, targetDisplayState);
        verify(displayBrightnessStrategy).updateBrightness(
                eq(new StrategyExecutionRequest(displayPowerRequest, DEFAULT_BRIGHTNESS)));
        assertEquals(mDisplayBrightnessController.getCurrentDisplayBrightnessStrategy(),
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
        mDisplayBrightnessController.setAndNotifyCurrentScreenBrightness(currentScreenBrightness);
        assertEquals(mDisplayBrightnessController.getCurrentBrightness(),
                currentScreenBrightness, /* delta= */ 0.0f);
        verify(mBrightnessChangeExecutor).execute(mOnBrightnessChangeRunnable);

        // No change to the current screen brightness is same as the existing one
        mDisplayBrightnessController.setAndNotifyCurrentScreenBrightness(currentScreenBrightness);
        verifyNoMoreInteractions(mBrightnessChangeExecutor);
    }

    @Test
    public void setPendingScreenBrightnessSetting() {
        float pendingScreenBrightness = 0.4f;
        mDisplayBrightnessController.setPendingScreenBrightness(pendingScreenBrightness);
        assertEquals(mDisplayBrightnessController.getPendingScreenBrightness(),
                pendingScreenBrightness, /* delta= */ 0.0f);
    }

    @Test
    public void updateUserSetScreenBrightness() {
        // No brightness is set if the pending brightness is invalid
        mDisplayBrightnessController.setPendingScreenBrightness(Float.NaN);
        assertFalse(mDisplayBrightnessController.updateUserSetScreenBrightness());
        assertFalse(mDisplayBrightnessController.getIsUserSetScreenBrightnessUpdated());

        // user set brightness is not set if the current and the pending brightness are same.
        float currentBrightness = 0.4f;
        TemporaryBrightnessStrategy temporaryBrightnessStrategy = mock(
                TemporaryBrightnessStrategy.class);
        when(mDisplayBrightnessStrategySelector.getTemporaryDisplayBrightnessStrategy()).thenReturn(
                temporaryBrightnessStrategy);
        mDisplayBrightnessController.setAndNotifyCurrentScreenBrightness(currentBrightness);
        mDisplayBrightnessController.setPendingScreenBrightness(currentBrightness);
        mDisplayBrightnessController.setTemporaryBrightness(currentBrightness);
        assertFalse(mDisplayBrightnessController.updateUserSetScreenBrightness());
        assertFalse(mDisplayBrightnessController.getIsUserSetScreenBrightnessUpdated());
        verify(temporaryBrightnessStrategy).setTemporaryScreenBrightness(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertEquals(mDisplayBrightnessController.getPendingScreenBrightness(),
                PowerManager.BRIGHTNESS_INVALID_FLOAT, /* delta= */ 0.0f);

        // user set brightness is set as expected
        currentBrightness = 0.4f;
        float pendingScreenBrightness = 0.3f;
        float temporaryScreenBrightness = 0.2f;
        mDisplayBrightnessController.setAndNotifyCurrentScreenBrightness(currentBrightness);
        mDisplayBrightnessController.setPendingScreenBrightness(pendingScreenBrightness);
        mDisplayBrightnessController.setTemporaryBrightness(temporaryScreenBrightness);
        assertTrue(mDisplayBrightnessController.updateUserSetScreenBrightness());
        assertTrue(mDisplayBrightnessController.getIsUserSetScreenBrightnessUpdated());
        assertEquals(mDisplayBrightnessController.getCurrentBrightness(),
                pendingScreenBrightness, /* delta= */ 0.0f);
        assertEquals(mDisplayBrightnessController.getLastUserSetScreenBrightness(),
                pendingScreenBrightness, /* delta= */ 0.0f);
        verify(mBrightnessChangeExecutor, times(2))
                .execute(mOnBrightnessChangeRunnable);
        verify(temporaryBrightnessStrategy, times(2))
                .setTemporaryScreenBrightness(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertEquals(mDisplayBrightnessController.getPendingScreenBrightness(),
                PowerManager.BRIGHTNESS_INVALID_FLOAT, /* delta= */ 0.0f);
    }

    @Test
    public void registerBrightnessSettingChangeListener() {
        BrightnessSetting.BrightnessSettingListener brightnessSettingListener = mock(
                BrightnessSetting.BrightnessSettingListener.class);
        mDisplayBrightnessController.registerBrightnessSettingChangeListener(
                brightnessSettingListener);
        verify(mBrightnessSetting).registerListener(brightnessSettingListener);
        assertEquals(mDisplayBrightnessController.getBrightnessSettingListener(),
                brightnessSettingListener);
    }

    @Test
    public void getScreenBrightnessSetting() {
        // getScreenBrightnessSetting returns the value relayed by BrightnessSetting, if the
        // valid is valid and in range
        float brightnessSetting = 0.2f;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightnessSetting);
        assertEquals(mDisplayBrightnessController.getScreenBrightnessSetting(), brightnessSetting,
                /* delta= */ 0.0f);

        // getScreenBrightnessSetting value is clamped if BrightnessSetting returns value beyond max
        brightnessSetting = 1.1f;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightnessSetting);
        assertEquals(mDisplayBrightnessController.getScreenBrightnessSetting(), 1.0f,
                /* delta= */ 0.0f);

        // getScreenBrightnessSetting returns default value is BrightnessSetting returns invalid
        // value.
        brightnessSetting = Float.NaN;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightnessSetting);
        assertEquals(mDisplayBrightnessController.getScreenBrightnessSetting(), DEFAULT_BRIGHTNESS,
                /* delta= */ 0.0f);
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
        verify(mBrightnessChangeExecutor).execute(mOnBrightnessChangeRunnable);
        verify(mBrightnessSetting).setBrightness(brightnessValue);

        // Does nothing if the value is invalid
        mDisplayBrightnessController.updateScreenBrightnessSetting(Float.NaN);
        verifyNoMoreInteractions(mBrightnessChangeExecutor, mBrightnessSetting);

        // Does nothing if the value is same as the current brightness
        brightnessValue = 0.2f;
        mDisplayBrightnessController.setAndNotifyCurrentScreenBrightness(brightnessValue);
        verify(mBrightnessChangeExecutor, times(2))
                .execute(mOnBrightnessChangeRunnable);
        mDisplayBrightnessController.updateScreenBrightnessSetting(brightnessValue);
        verifyNoMoreInteractions(mBrightnessChangeExecutor, mBrightnessSetting);
    }

    @Test
    public void testConvertToNits() {
        final float brightness = 0.5f;
        final float nits = 300;
        final float adjustedNits = 200;

        // ABC is null
        assertEquals(-1f, mDisplayBrightnessController.convertToNits(brightness),
                /* delta= */ 0);
        assertEquals(-1f, mDisplayBrightnessController.convertToAdjustedNits(brightness),
                /* delta= */ 0);

        AutomaticBrightnessController automaticBrightnessController =
                mock(AutomaticBrightnessController.class);
        when(automaticBrightnessController.convertToNits(brightness)).thenReturn(nits);
        when(automaticBrightnessController.convertToAdjustedNits(brightness))
                .thenReturn(adjustedNits);
        mDisplayBrightnessController.setAutomaticBrightnessController(
                automaticBrightnessController);

        assertEquals(nits, mDisplayBrightnessController.convertToNits(brightness), /* delta= */ 0);
        assertEquals(adjustedNits, mDisplayBrightnessController.convertToAdjustedNits(brightness),
                /* delta= */ 0);
    }

    @Test
    public void testGetBrightnessFromNits() {
        float brightness = 0.5f;
        float nits = 300;

        // ABC is null
        assertEquals(PowerManager.BRIGHTNESS_INVALID_FLOAT,
                mDisplayBrightnessController.getBrightnessFromNits(nits), /* delta= */ 0);

        AutomaticBrightnessController automaticBrightnessController =
                mock(AutomaticBrightnessController.class);
        when(automaticBrightnessController.getBrightnessFromNits(nits)).thenReturn(brightness);
        mDisplayBrightnessController.setAutomaticBrightnessController(
                automaticBrightnessController);

        assertEquals(brightness, mDisplayBrightnessController.getBrightnessFromNits(nits),
                /* delta= */ 0);
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

    @Test
    public void testLoadNitBasedBrightnessSetting() {
        // When the nits value is valid, the brightness is set from the old default display nits
        // value
        float nits = 200f;
        float brightness = 0.3f;
        AutomaticBrightnessController automaticBrightnessController =
                mock(AutomaticBrightnessController.class);
        when(automaticBrightnessController.getBrightnessFromNits(nits)).thenReturn(brightness);
        when(mBrightnessSetting.getBrightnessNitsForDefaultDisplay()).thenReturn(nits);
        mDisplayBrightnessController.setAutomaticBrightnessController(
                automaticBrightnessController);
        verify(mBrightnessSetting).setBrightness(brightness);
        assertEquals(brightness, mDisplayBrightnessController.getCurrentBrightness(), 0.01f);
        clearInvocations(automaticBrightnessController, mBrightnessSetting);

        // When the nits value is invalid, the brightness is resumed from where it was last set
        nits = -1;
        brightness = 0.4f;
        when(automaticBrightnessController.getBrightnessFromNits(nits)).thenReturn(brightness);
        when(mBrightnessSetting.getBrightnessNitsForDefaultDisplay()).thenReturn(nits);
        when(mBrightnessSetting.getBrightness()).thenReturn(brightness);
        mDisplayBrightnessController.setAutomaticBrightnessController(
                automaticBrightnessController);
        verify(mBrightnessSetting, never()).setBrightness(brightness);
        assertEquals(brightness, mDisplayBrightnessController.getCurrentBrightness(), 0.01f);
        clearInvocations(automaticBrightnessController, mBrightnessSetting);

        // When the display is a non-default display, the brightness is resumed from where it was
        // last set
        int nonDefaultDisplayId = 1;
        mDisplayBrightnessController = new DisplayBrightnessController(mContext, mInjector,
                nonDefaultDisplayId, DEFAULT_BRIGHTNESS, mBrightnessSetting,
                mOnBrightnessChangeRunnable, mBrightnessChangeExecutor, mDisplayManagerFlags);
        brightness = 0.5f;
        when(mBrightnessSetting.getBrightness()).thenReturn(brightness);
        mDisplayBrightnessController.setAutomaticBrightnessController(
                automaticBrightnessController);
        assertEquals(brightness, mDisplayBrightnessController.getCurrentBrightness(), 0.01f);
        verifyZeroInteractions(automaticBrightnessController);
        verify(mBrightnessSetting, never()).getBrightnessNitsForDefaultDisplay();
        verify(mBrightnessSetting, never()).setBrightness(brightness);
    }

    @Test
    public void testChangeBrightnessNitsWhenUserChanges() {
        float brightnessValue1 = 0.3f;
        float nits1 = 200f;
        float brightnessValue2 = 0.5f;
        float nits2 = 300f;
        AutomaticBrightnessController automaticBrightnessController =
                mock(AutomaticBrightnessController.class);
        when(automaticBrightnessController.convertToNits(brightnessValue1)).thenReturn(nits1);
        when(automaticBrightnessController.convertToNits(brightnessValue2)).thenReturn(nits2);
        mDisplayBrightnessController.setAutomaticBrightnessController(
                automaticBrightnessController);

        mDisplayBrightnessController.setBrightness(brightnessValue1, 1 /* user-serial */);
        verify(mBrightnessSetting).setUserSerial(1);
        verify(mBrightnessSetting).setBrightness(brightnessValue1);
        verify(mBrightnessSetting).setBrightnessNitsForDefaultDisplay(nits1);

        mDisplayBrightnessController.setBrightness(brightnessValue2, 2 /* user-serial */);
        verify(mBrightnessSetting).setUserSerial(2);
        verify(mBrightnessSetting).setBrightness(brightnessValue2);
        verify(mBrightnessSetting).setBrightnessNitsForDefaultDisplay(nits2);
    }

    @Test
    public void setBrightnessFromOffload() {
        float brightness = 0.4f;
        OffloadBrightnessStrategy offloadBrightnessStrategy = mock(OffloadBrightnessStrategy.class);
        when(mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy()).thenReturn(
                offloadBrightnessStrategy);
        boolean brightnessUpdated =
                mDisplayBrightnessController.setBrightnessFromOffload(brightness);
        verify(offloadBrightnessStrategy).setOffloadScreenBrightness(brightness);
        assertTrue(brightnessUpdated);
    }

    @Test
    public void setBrightnessFromOffload_OffloadStrategyNull() {
        float brightness = 0.4f;
        when(mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy()).thenReturn(null);
        boolean brightnessUpdated =
                mDisplayBrightnessController.setBrightnessFromOffload(brightness);
        assertFalse(brightnessUpdated);
    }

    @Test
    public void setBrightnessFromOffload_BrightnessUnchanged() {
        float brightness = 0.4f;
        OffloadBrightnessStrategy offloadBrightnessStrategy = mock(OffloadBrightnessStrategy.class);
        when(offloadBrightnessStrategy.getOffloadScreenBrightness()).thenReturn(brightness);
        when(mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy()).thenReturn(
                offloadBrightnessStrategy);
        boolean brightnessUpdated =
                mDisplayBrightnessController.setBrightnessFromOffload(brightness);
        verify(offloadBrightnessStrategy, never()).setOffloadScreenBrightness(brightness);
        assertFalse(brightnessUpdated);
    }
}
