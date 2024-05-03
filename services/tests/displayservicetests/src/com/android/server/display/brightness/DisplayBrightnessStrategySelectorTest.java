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
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.display.brightness.strategy.AutoBrightnessFallbackStrategy;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy2;
import com.android.server.display.brightness.strategy.BoostBrightnessStrategy;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;
import com.android.server.display.brightness.strategy.DozeBrightnessStrategy;
import com.android.server.display.brightness.strategy.FollowerBrightnessStrategy;
import com.android.server.display.brightness.strategy.InvalidBrightnessStrategy;
import com.android.server.display.brightness.strategy.OffloadBrightnessStrategy;
import com.android.server.display.brightness.strategy.OverrideBrightnessStrategy;
import com.android.server.display.brightness.strategy.ScreenOffBrightnessStrategy;
import com.android.server.display.brightness.strategy.TemporaryBrightnessStrategy;
import com.android.server.display.feature.DisplayManagerFlags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayBrightnessStrategySelectorTest {
    private static final boolean DISALLOW_AUTO_BRIGHTNESS_WHILE_DOZING = false;
    private static final int DISPLAY_ID = 1;

    @Mock
    private ScreenOffBrightnessStrategy mScreenOffBrightnessModeStrategy;
    @Mock
    private DozeBrightnessStrategy mDozeBrightnessModeStrategy;
    @Mock
    private OverrideBrightnessStrategy mOverrideBrightnessStrategy;
    @Mock
    private TemporaryBrightnessStrategy mTemporaryBrightnessStrategy;
    @Mock
    private BoostBrightnessStrategy mBoostBrightnessStrategy;
    @Mock
    private InvalidBrightnessStrategy mInvalidBrightnessStrategy;
    @Mock
    private FollowerBrightnessStrategy mFollowerBrightnessStrategy;
    @Mock
    private AutomaticBrightnessStrategy mAutomaticBrightnessStrategy;
    @Mock
    private AutomaticBrightnessStrategy2 mAutomaticBrightnessStrategy2;
    @Mock
    private OffloadBrightnessStrategy mOffloadBrightnessStrategy;
    @Mock
    private AutoBrightnessFallbackStrategy mAutoBrightnessFallbackStrategy;
    @Mock
    private Resources mResources;
    @Mock
    private DisplayManagerFlags mDisplayManagerFlags;

    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;
    private Context mContext;
    private DisplayBrightnessStrategySelector.Injector mInjector =
            new DisplayBrightnessStrategySelector.Injector() {
                @Override
                ScreenOffBrightnessStrategy getScreenOffBrightnessStrategy() {
                    return mScreenOffBrightnessModeStrategy;
                }

                @Override
                DozeBrightnessStrategy getDozeBrightnessStrategy() {
                    return mDozeBrightnessModeStrategy;
                }

                @Override
                OverrideBrightnessStrategy getOverrideBrightnessStrategy() {
                    return mOverrideBrightnessStrategy;
                }

                @Override
                TemporaryBrightnessStrategy getTemporaryBrightnessStrategy() {
                    return mTemporaryBrightnessStrategy;
                }

                @Override
                BoostBrightnessStrategy getBoostBrightnessStrategy() {
                    return mBoostBrightnessStrategy;
                }

                @Override
                FollowerBrightnessStrategy getFollowerBrightnessStrategy(int displayId) {
                    return mFollowerBrightnessStrategy;
                }

                @Override
                InvalidBrightnessStrategy getInvalidBrightnessStrategy() {
                    return mInvalidBrightnessStrategy;
                }

                @Override
                AutomaticBrightnessStrategy getAutomaticBrightnessStrategy1(Context context,
                        int displayId) {
                    return mAutomaticBrightnessStrategy;
                }

                @Override
                AutomaticBrightnessStrategy2 getAutomaticBrightnessStrategy2(Context context,
                        int displayId) {
                    return mAutomaticBrightnessStrategy2;
                }

                @Override
                OffloadBrightnessStrategy getOffloadBrightnessStrategy(
                        DisplayManagerFlags displayManagerFlags) {
                    return mOffloadBrightnessStrategy;
                }

                @Override
                AutoBrightnessFallbackStrategy getAutoBrightnessFallbackStrategy() {
                    return mAutoBrightnessFallbackStrategy;
                }
            };

    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(contentResolver);
        when(mContext.getResources()).thenReturn(mResources);
        when(mInvalidBrightnessStrategy.getName()).thenReturn("InvalidBrightnessStrategy");
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);

    }

    @Test
    public void selectStrategySelectsDozeStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        displayPowerRequest.dozeScreenBrightness = 0.2f;
        when(mResources.getBoolean(R.bool.config_allowAutoBrightnessWhileDozing)).thenReturn(
                DISALLOW_AUTO_BRIGHTNESS_WHILE_DOZING);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_DOZE,
                                0.1f, false)),
                mDozeBrightnessModeStrategy);
    }

    @Test
    public void selectStrategyDoesNotSelectDozeStrategyWhenInvalidBrightness() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        displayPowerRequest.dozeScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        when(mResources.getBoolean(R.bool.config_allowAutoBrightnessWhileDozing)).thenReturn(
                DISALLOW_AUTO_BRIGHTNESS_WHILE_DOZING);
        assertNotEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_DOZE,
                                0.1f, false)),
                mDozeBrightnessModeStrategy);
    }

    @Test
    public void selectStrategySelectsScreenOffStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_OFF,
                                0.1f, false)),
                mScreenOffBrightnessModeStrategy);
    }

    @Test
    public void selectStrategySelectsOverrideStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = 0.4f;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mOverrideBrightnessStrategy);
    }

    @Test
    public void selectStrategySelectsTemporaryStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(0.3f);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mTemporaryBrightnessStrategy);
    }

    @Test
    public void selectStrategySelectsBoostStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.boostScreenBrightness = true;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(Float.NaN);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mBoostBrightnessStrategy);
    }

    @Test
    public void selectStrategySelectsInvalidStrategyWhenNoStrategyIsValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(Float.NaN);
        when(mOffloadBrightnessStrategy.getOffloadScreenBrightness()).thenReturn(Float.NaN);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mInvalidBrightnessStrategy);
    }

    @Test
    public void selectStrategySelectsFollowerStrategyWhenValid() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(0.3f);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mFollowerBrightnessStrategy);
    }

    @Test
    public void selectStrategySelectsOffloadStrategyWhenValid() {
        when(mDisplayManagerFlags.isDisplayOffloadEnabled()).thenReturn(true);
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(Float.NaN);
        when(mAutomaticBrightnessStrategy2.shouldUseAutoBrightness()).thenReturn(true);
        when(mOffloadBrightnessStrategy.getOffloadScreenBrightness()).thenReturn(0.3f);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mOffloadBrightnessStrategy);
    }

    @Test
    public void selectStrategy_selectsAutomaticStrategyWhenValid() {
        when(mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()).thenReturn(true);
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(Float.NaN);
        when(mAutomaticBrightnessStrategy.shouldUseAutoBrightness()).thenReturn(true);
        when(mAutomaticBrightnessStrategy.isAutoBrightnessValid()).thenReturn(true);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mAutomaticBrightnessStrategy);
        verifyZeroInteractions(mOffloadBrightnessStrategy);
        verify(mAutomaticBrightnessStrategy).setAutoBrightnessState(Display.STATE_ON,
                false, BrightnessReason.REASON_UNKNOWN,
                DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT, 0.1f, false);

    }

    @Test
    public void selectStrategy_selectsAutomaticFallbackStrategyWhenValid() {
        when(mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()).thenReturn(true);
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(Float.NaN);
        when(mAutomaticBrightnessStrategy.shouldUseAutoBrightness()).thenReturn(true);
        when(mAutomaticBrightnessStrategy.isAutoBrightnessValid()).thenReturn(false);
        when(mAutoBrightnessFallbackStrategy.isValid()).thenReturn(true);
        assertEquals(mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)),
                mAutoBrightnessFallbackStrategy);
    }

    @Test
    public void selectStrategyDoesNotSelectOffloadStrategyWhenFeatureFlagDisabled() {
        when(mDisplayManagerFlags.isDisplayOffloadEnabled()).thenReturn(false);
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.screenBrightnessOverride = Float.NaN;
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(Float.NaN);
        when(mTemporaryBrightnessStrategy.getTemporaryScreenBrightness()).thenReturn(Float.NaN);
        when(mOffloadBrightnessStrategy.getOffloadScreenBrightness()).thenReturn(0.3f);
        assertNotEquals(mOffloadBrightnessStrategy,
                mDisplayBrightnessStrategySelector.selectStrategy(
                        new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                                0.1f, false)));
    }

    @Test
    public void selectStrategyCallsPostProcessorForAllStrategies() {
        when(mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()).thenReturn(true);
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        when(mFollowerBrightnessStrategy.getBrightnessToFollow()).thenReturn(0.3f);

        mDisplayBrightnessStrategySelector.selectStrategy(
                new StrategySelectionRequest(displayPowerRequest, Display.STATE_ON,
                        0.1f, false));

        StrategySelectionNotifyRequest strategySelectionNotifyRequest =
                new StrategySelectionNotifyRequest(displayPowerRequest, Display.STATE_ON,
                        mFollowerBrightnessStrategy, 0.1f,
                        false, false, false);

        for (DisplayBrightnessStrategy displayBrightnessStrategy :
                mDisplayBrightnessStrategySelector.mDisplayBrightnessStrategies) {
            if (displayBrightnessStrategy != null) {
                verify(displayBrightnessStrategy).strategySelectionPostProcessor(
                        eq(strategySelectionNotifyRequest));
            }
        }
    }

    @Test
    public void getAutomaticBrightnessStrategy_getsAutomaticStrategy2IfRefactoringFlagIsNotSet() {
        assertEquals(mAutomaticBrightnessStrategy2,
                mDisplayBrightnessStrategySelector.getAutomaticBrightnessStrategy());
    }

    @Test
    public void getAutomaticBrightnessStrategy_getsAutomaticStrategyIfRefactoringFlagIsSet() {
        when(mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()).thenReturn(true);
        mDisplayBrightnessStrategySelector = new DisplayBrightnessStrategySelector(mContext,
                mInjector, DISPLAY_ID, mDisplayManagerFlags);
        assertEquals(mAutomaticBrightnessStrategy,
                mDisplayBrightnessStrategySelector.getAutomaticBrightnessStrategy());
    }
}
