/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.display.brightness.strategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.BrightnessReason;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutomaticBrightnessStrategy2Test {
    private static final int DISPLAY_ID = 0;
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock
    private AutomaticBrightnessController mAutomaticBrightnessController;

    private BrightnessConfiguration mBrightnessConfiguration;
    private float mDefaultScreenAutoBrightnessAdjustment;
    private Context mContext;
    private AutomaticBrightnessStrategy2 mAutomaticBrightnessStrategy;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        final MockContentResolver resolver = mSettingsProviderRule.mockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(resolver);
        mDefaultScreenAutoBrightnessAdjustment = Settings.System.getFloat(
                mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, Float.NaN);
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.5f);
        mAutomaticBrightnessStrategy = new AutomaticBrightnessStrategy2(mContext, DISPLAY_ID);

        mBrightnessConfiguration = new BrightnessConfiguration.Builder(
                new float[]{0f, 1f}, new float[]{0, PowerManager.BRIGHTNESS_ON}).build();
        when(mAutomaticBrightnessController.hasUserDataPoints()).thenReturn(true);
        mAutomaticBrightnessStrategy.setAutomaticBrightnessController(
                mAutomaticBrightnessController);
        mAutomaticBrightnessStrategy.setBrightnessConfiguration(mBrightnessConfiguration,
                true);
    }

    @After
    public void after() {
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, mDefaultScreenAutoBrightnessAdjustment);
    }

    @Test
    public void testAutoBrightnessState_AutoBrightnessDisabled() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(false);
        int targetDisplayState = Display.STATE_ON;
        boolean allowAutoBrightnessWhileDozing = false;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, /* adjustment */ 0.5f,
                        /* userChangedAutoBrightnessAdjustment= */ false, policy,
                        targetDisplayState, /* shouldResetShortTermModel */ true);
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_DisplayIsOff() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_OFF;
        boolean allowAutoBrightnessWhileDozing = false;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, /* adjustment */ 0.5f,
                        /* userChangedAutoBrightnessAdjustment= */ false, policy,
                        targetDisplayState, /* shouldResetShortTermModel */ true);
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertTrue(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_DisplayIsInDoze_ConfigDoesNotAllow() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_DOZE;
        boolean allowAutoBrightnessWhileDozing = false;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, /* adjustment */ 0.5f,
                        /* userChangedAutoBrightnessAdjustment= */ false, policy,
                        targetDisplayState, /* shouldResetShortTermModel */ true);
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertTrue(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_BrightnessReasonIsOverride() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_ON;
        boolean allowAutoBrightnessWhileDozing = false;
        int brightnessReason = BrightnessReason.REASON_OVERRIDE;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, /* adjustment */ 0.5f,
                        /* userChangedAutoBrightnessAdjustment= */ false, policy,
                        targetDisplayState, /* shouldResetShortTermModel */ true);
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_DeviceIsInDoze_ConfigDoesAllow() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_DOZE;
        boolean allowAutoBrightnessWhileDozing = true;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.4f);
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, /* adjustment */ 0.4f,
                        /* userChangedAutoBrightnessAdjustment= */ true, policy,
                        targetDisplayState, /* shouldResetShortTermModel */ true);
        assertTrue(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_DeviceIsInDoze_ConfigDoesAllow_ScreenOff() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_OFF;
        boolean allowAutoBrightnessWhileDozing = true;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.4f);
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, /* adjustment */ 0.4f,
                        /* userChangedAutoBrightnessAdjustment= */ true, policy,
                        targetDisplayState, /* shouldResetShortTermModel */ true);
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertTrue(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_DisplayIsOn() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_ON;
        boolean allowAutoBrightnessWhileDozing = false;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        float pendingBrightnessAdjustment = 0.1f;
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, pendingBrightnessAdjustment);
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, pendingBrightnessAdjustment,
                        /* userChangedAutoBrightnessAdjustment= */ true, policy, targetDisplayState,
                        /* shouldResetShortTermModel */ true);
        assertTrue(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void testAutoBrightnessState_DisplayIsOn_PolicyIsDoze() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        int targetDisplayState = Display.STATE_ON;
        boolean allowAutoBrightnessWhileDozing = false;
        int brightnessReason = BrightnessReason.REASON_UNKNOWN;
        float lastUserSetBrightness = 0.2f;
        boolean userSetBrightnessChanged = true;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
        float pendingBrightnessAdjustment = 0.1f;
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, pendingBrightnessAdjustment);
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
        mAutomaticBrightnessStrategy.setAutoBrightnessState(targetDisplayState,
                allowAutoBrightnessWhileDozing, brightnessReason, policy, lastUserSetBrightness,
                userSetBrightnessChanged);
        verify(mAutomaticBrightnessController)
                .configure(AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                        mBrightnessConfiguration,
                        lastUserSetBrightness,
                        userSetBrightnessChanged, pendingBrightnessAdjustment,
                        /* userChangedAutoBrightnessAdjustment= */ true, policy, targetDisplayState,
                        /* shouldResetShortTermModel */ true);
        assertFalse(mAutomaticBrightnessStrategy.isAutoBrightnessEnabled());
        assertTrue(mAutomaticBrightnessStrategy.isAutoBrightnessDisabledDueToDisplayOff());
    }

    @Test
    public void accommodateUserBrightnessChangesWorksAsExpected() {
        // Verify the state if automaticBrightnessController is configured.
        assertFalse(mAutomaticBrightnessStrategy.isShortTermModelActive());
        boolean userSetBrightnessChanged = true;
        float lastUserSetScreenBrightness = 0.2f;
        int policy = DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
        int targetDisplayState = Display.STATE_ON;
        BrightnessConfiguration brightnessConfiguration = new BrightnessConfiguration.Builder(
                new float[]{0f, 1f}, new float[]{0, PowerManager.BRIGHTNESS_ON}).build();
        int autoBrightnessState = AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
        float temporaryAutoBrightnessAdjustments = 0.4f;
        mAutomaticBrightnessStrategy.setShouldResetShortTermModel(true);
        setTemporaryAutoBrightnessAdjustment(temporaryAutoBrightnessAdjustments);
        mAutomaticBrightnessStrategy.accommodateUserBrightnessChanges(userSetBrightnessChanged,
                lastUserSetScreenBrightness, policy, targetDisplayState, brightnessConfiguration,
                autoBrightnessState);
        verify(mAutomaticBrightnessController).configure(autoBrightnessState,
                brightnessConfiguration,
                lastUserSetScreenBrightness,
                userSetBrightnessChanged, temporaryAutoBrightnessAdjustments,
                /* userChangedAutoBrightnessAdjustment= */ false, policy, targetDisplayState,
                /* shouldResetShortTermModel= */ true);
        assertTrue(mAutomaticBrightnessStrategy.isTemporaryAutoBrightnessAdjustmentApplied());
        assertFalse(mAutomaticBrightnessStrategy.shouldResetShortTermModel());
        assertTrue(mAutomaticBrightnessStrategy.isShortTermModelActive());
        // Verify the state when automaticBrightnessController is not configured
        setTemporaryAutoBrightnessAdjustment(Float.NaN);
        mAutomaticBrightnessStrategy.setAutomaticBrightnessController(null);
        mAutomaticBrightnessStrategy.setShouldResetShortTermModel(true);
        mAutomaticBrightnessStrategy.accommodateUserBrightnessChanges(userSetBrightnessChanged,
                lastUserSetScreenBrightness, policy, targetDisplayState, brightnessConfiguration,
                autoBrightnessState);
        assertFalse(mAutomaticBrightnessStrategy.isTemporaryAutoBrightnessAdjustmentApplied());
        assertTrue(mAutomaticBrightnessStrategy.shouldResetShortTermModel());
        assertFalse(mAutomaticBrightnessStrategy.isShortTermModelActive());
    }

    @Test
    public void adjustAutomaticBrightnessStateIfValid() throws Settings.SettingNotFoundException {
        float brightnessState = 0.3f;
        float autoBrightnessAdjustment = 0.2f;
        when(mAutomaticBrightnessController.getAutomaticScreenBrightnessAdjustment()).thenReturn(
                autoBrightnessAdjustment);
        mAutomaticBrightnessStrategy.adjustAutomaticBrightnessStateIfValid(brightnessState);
        assertEquals(autoBrightnessAdjustment,
                mAutomaticBrightnessStrategy.getAutoBrightnessAdjustment(), 0.0f);
        assertEquals(autoBrightnessAdjustment, Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ,
                UserHandle.USER_CURRENT), 0.0f);
        assertEquals(BrightnessReason.ADJUSTMENT_AUTO,
                mAutomaticBrightnessStrategy.getAutoBrightnessAdjustmentReasonsFlags());
        float invalidBrightness = -0.5f;
        mAutomaticBrightnessStrategy
                .adjustAutomaticBrightnessStateIfValid(invalidBrightness);
        assertEquals(autoBrightnessAdjustment,
                mAutomaticBrightnessStrategy.getAutoBrightnessAdjustment(), 0.0f);
        assertEquals(0,
                mAutomaticBrightnessStrategy.getAutoBrightnessAdjustmentReasonsFlags());
    }

    @Test
    public void updatePendingAutoBrightnessAdjustments() {
        // Verify the state when the pendingAutoBrightnessAdjustments are not present
        setPendingAutoBrightnessAdjustment(Float.NaN);
        assertFalse(mAutomaticBrightnessStrategy.processPendingAutoBrightnessAdjustments());
        assertFalse(mAutomaticBrightnessStrategy.getAutoBrightnessAdjustmentChanged());
        // Verify the state when the pendingAutoBrightnessAdjustments are present, but
        // pendingAutoBrightnessAdjustments and autoBrightnessAdjustments are the same
        float autoBrightnessAdjustment = 0.3f;
        setPendingAutoBrightnessAdjustment(autoBrightnessAdjustment);
        setAutoBrightnessAdjustment(autoBrightnessAdjustment);
        assertFalse(mAutomaticBrightnessStrategy.processPendingAutoBrightnessAdjustments());
        assertFalse(mAutomaticBrightnessStrategy.getAutoBrightnessAdjustmentChanged());
        assertEquals(Float.NaN, mAutomaticBrightnessStrategy.getPendingAutoBrightnessAdjustment(),
                0.0f);
        // Verify the state when the pendingAutoBrightnessAdjustments are present, and
        // pendingAutoBrightnessAdjustments and autoBrightnessAdjustments are not the same
        float pendingAutoBrightnessAdjustment = 0.2f;
        setPendingAutoBrightnessAdjustment(pendingAutoBrightnessAdjustment);
        setTemporaryAutoBrightnessAdjustment(0.1f);
        assertTrue(mAutomaticBrightnessStrategy.processPendingAutoBrightnessAdjustments());
        assertTrue(mAutomaticBrightnessStrategy.getAutoBrightnessAdjustmentChanged());
        assertEquals(pendingAutoBrightnessAdjustment,
                mAutomaticBrightnessStrategy.getAutoBrightnessAdjustment(), 0.0f);
        assertEquals(Float.NaN, mAutomaticBrightnessStrategy.getPendingAutoBrightnessAdjustment(),
                0.0f);
        assertEquals(Float.NaN, mAutomaticBrightnessStrategy.getTemporaryAutoBrightnessAdjustment(),
                0.0f);
    }

    @Test
    public void setAutomaticBrightnessWorksAsExpected() {
        float automaticScreenBrightness = 0.3f;
        AutomaticBrightnessController automaticBrightnessController = mock(
                AutomaticBrightnessController.class);
        when(automaticBrightnessController.getAutomaticScreenBrightness(any(BrightnessEvent.class)))
                .thenReturn(automaticScreenBrightness);
        mAutomaticBrightnessStrategy.setAutomaticBrightnessController(
                automaticBrightnessController);
        assertEquals(automaticScreenBrightness,
                mAutomaticBrightnessStrategy.getAutomaticScreenBrightness(
                        new BrightnessEvent(DISPLAY_ID)), 0.0f);
    }

    @Test
    public void shouldUseAutoBrightness() {
        mAutomaticBrightnessStrategy.setUseAutoBrightness(true);
        assertTrue(mAutomaticBrightnessStrategy.shouldUseAutoBrightness());
    }

    @Test
    public void setPendingAutoBrightnessAdjustments() throws Settings.SettingNotFoundException {
        float pendingAutoBrightnessAdjustments = 0.3f;
        setPendingAutoBrightnessAdjustment(pendingAutoBrightnessAdjustments);
        assertEquals(pendingAutoBrightnessAdjustments,
                mAutomaticBrightnessStrategy.getPendingAutoBrightnessAdjustment(), 0.0f);
        assertEquals(pendingAutoBrightnessAdjustments, Settings.System.getFloatForUser(
                mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ,
                UserHandle.USER_CURRENT), 0.0f);
    }

    @Test
    public void setTemporaryAutoBrightnessAdjustment() {
        float temporaryAutoBrightnessAdjustment = 0.3f;
        mAutomaticBrightnessStrategy.setTemporaryAutoBrightnessAdjustment(
                temporaryAutoBrightnessAdjustment);
        assertEquals(temporaryAutoBrightnessAdjustment,
                mAutomaticBrightnessStrategy.getTemporaryAutoBrightnessAdjustment(), 0.0f);
    }

    @Test
    public void setAutoBrightnessApplied() {
        mAutomaticBrightnessStrategy.setAutoBrightnessApplied(true);
        assertTrue(mAutomaticBrightnessStrategy.hasAppliedAutoBrightness());
    }

    @Test
    public void testVerifyNoAutoBrightnessAdjustmentsArePopulatedForNonDefaultDisplay() {
        int newDisplayId = 1;
        mAutomaticBrightnessStrategy = new AutomaticBrightnessStrategy2(mContext, newDisplayId);
        mAutomaticBrightnessStrategy.putAutoBrightnessAdjustmentSetting(0.3f);
        assertEquals(0.5f, mAutomaticBrightnessStrategy.getAutoBrightnessAdjustment(),
                0.0f);
    }

    private void setPendingAutoBrightnessAdjustment(float pendingAutoBrightnessAdjustment) {
        Settings.System.putFloat(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, pendingAutoBrightnessAdjustment);
        mAutomaticBrightnessStrategy.updatePendingAutoBrightnessAdjustments();
    }

    private void setTemporaryAutoBrightnessAdjustment(float temporaryAutoBrightnessAdjustment) {
        mAutomaticBrightnessStrategy.setTemporaryAutoBrightnessAdjustment(
                temporaryAutoBrightnessAdjustment);
    }

    private void setAutoBrightnessAdjustment(float autoBrightnessAdjustment) {
        mAutomaticBrightnessStrategy.putAutoBrightnessAdjustmentSetting(autoBrightnessAdjustment);
    }
}
