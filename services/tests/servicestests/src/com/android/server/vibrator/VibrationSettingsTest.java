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

package com.android.server.vibrator;

import static android.os.VibrationAttributes.USAGE_ACCESSIBILITY;
import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;
import static android.os.Vibrator.VIBRATION_INTENSITY_HIGH;
import static android.os.Vibrator.VIBRATION_INTENSITY_LOW;
import static android.os.Vibrator.VIBRATION_INTENSITY_MEDIUM;
import static android.os.Vibrator.VIBRATION_INTENSITY_OFF;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.os.vibrator.VibrationConfig;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibrationSettings}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibrationSettingsTest
 */
@Presubmit
public class VibrationSettingsTest {

    private static final int UID = 1;
    private static final String SYSUI_PACKAGE_NAME = "sysui";
    private static final PowerSaveState NORMAL_POWER_STATE = new PowerSaveState.Builder().build();
    private static final PowerSaveState LOW_POWER_STATE = new PowerSaveState.Builder()
            .setBatterySaverEnabled(true).build();

    private static final int[] ALL_USAGES = new int[] {
            USAGE_UNKNOWN,
            USAGE_ACCESSIBILITY,
            USAGE_ALARM,
            USAGE_COMMUNICATION_REQUEST,
            USAGE_HARDWARE_FEEDBACK,
            USAGE_MEDIA,
            USAGE_NOTIFICATION,
            USAGE_PHYSICAL_EMULATION,
            USAGE_RINGTONE,
            USAGE_TOUCH,
    };

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock private VibrationSettings.OnVibratorSettingsChanged mListenerMock;
    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private VibrationConfig mVibrationConfigMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private AudioManager mAudioManager;
    private VibrationSettings mVibrationSettings;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        doAnswer(invocation -> {
            mRegisteredPowerModeListener = invocation.getArgument(0);
            return null;
        }).when(mPowerManagerInternalMock).registerLowPowerModeObserver(any());
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName(SYSUI_PACKAGE_NAME, ""));

        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);

        setDefaultIntensity(VIBRATION_INTENSITY_MEDIUM);
        mAudioManager = mContextSpy.getSystemService(AudioManager.class);
        mVibrationSettings = new VibrationSettings(mContextSpy,
                new Handler(mTestLooper.getLooper()), mVibrationConfigMock);

        // Simulate System defaults.
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 1);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        mVibrationSettings.onSystemReady();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    @Test
    public void addListener_settingsChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED, 0);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        verify(mListenerMock, times(10)).onChange();
    }

    @Test
    public void addListener_lowPowerModeChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE); // No change.

        verify(mListenerMock, times(2)).onChange();
    }

    @Test
    public void removeListener_noMoreCallbacksToListener() {
        mVibrationSettings.addListener(mListenerMock);

        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, 0);
        verify(mListenerMock).onChange();

        mVibrationSettings.removeListener(mListenerMock);

        verifyNoMoreInteractions(mListenerMock);
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
    }

    @Test
    public void shouldIgnoreVibration_fromBackground_doesNotIgnoreUsagesFromAllowlist() {
        int[] expectedAllowedVibrations = new int[] {
                USAGE_RINGTONE,
                USAGE_ALARM,
                USAGE_NOTIFICATION,
                USAGE_COMMUNICATION_REQUEST,
                USAGE_HARDWARE_FEEDBACK,
                USAGE_PHYSICAL_EMULATION,
        };

        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        for (int usage : expectedAllowedVibrations) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_fromBackground_ignoresUsagesNotInAllowlist() {
        int[] expectedIgnoredVibrations = new int[] {
                USAGE_TOUCH,
                USAGE_UNKNOWN,
        };

        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        for (int usage : expectedIgnoredVibrations) {
            assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_BACKGROUND);
        }
    }

    @Test
    public void shouldIgnoreVibration_fromForeground_allowsAnyUsage() {
        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND, 0, 0);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_inBatterySaverMode_doesNotIgnoreUsagesFromAllowlist() {
        int[] expectedAllowedVibrations = new int[] {
                USAGE_RINGTONE,
                USAGE_ALARM,
                USAGE_COMMUNICATION_REQUEST,
        };

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        for (int usage : expectedAllowedVibrations) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_inBatterySaverMode_ignoresUsagesNotInAllowlist() {
        int[] expectedIgnoredVibrations = new int[] {
                USAGE_NOTIFICATION,
                USAGE_HARDWARE_FEEDBACK,
                USAGE_PHYSICAL_EMULATION,
                USAGE_TOUCH,
                USAGE_UNKNOWN,
        };

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        for (int usage : expectedIgnoredVibrations) {
            assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_POWER);
        }
    }

    @Test
    public void shouldIgnoreVibration_notInBatterySaverMode_allowsAnyUsage() {
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeSilent_ignoresRingtoneAndNotification() {
        // Vibrating settings on are overruled by ringer mode.
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        setRingerMode(AudioManager.RINGER_MODE_SILENT);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_RINGTONE || usage == USAGE_NOTIFICATION) {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_RINGER_MODE);
            } else {
                assertVibrationNotIgnoredForUsage(usage);
            }
        }
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeSilentAndBypassFlag_allowsAllVibrations() {
        setRingerMode(AudioManager.RINGER_MODE_SILENT);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY);
        }
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeVibrate_allowsAllVibrations() {
        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_withRingerModeNormal_allowsAllVibrations() {
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }


    @Test
    public void shouldIgnoreVibration_vibrateOnDisabled_ignoresUsagesNotAccessibility() {
        setUserSetting(Settings.System.VIBRATE_ON, 0);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_ACCESSIBILITY) {
                assertVibrationNotIgnoredForUsage(usage);
            } else {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_SETTINGS);
            }
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_vibrateOnEnabledOrUnset_allowsAnyUsage() {
        deleteUserSetting(Settings.System.VIBRATE_ON);
        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }

        setUserSetting(Settings.System.VIBRATE_ON, 1);
        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_withRingSettingsOff_allowsAllVibrations() {
        // VIBRATE_WHEN_RINGING is deprecated and should have no effect on the ring vibration
        // setting. The ramping ringer is also independent now, instead of a 3-state setting.
        setUserSetting(Settings.System.VIBRATE_WHEN_RINGING, 0);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 0);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_withHapticFeedbackDisabled_ignoresTouchVibration() {
        // HAPTIC_FEEDBACK_ENABLED is deprecated but it was the only setting used to disable touch
        // feedback vibrations. Continue to apply this on top of the intensity setting.
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED, 0);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_TOUCH) {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_SETTINGS);
            } else {
                assertVibrationNotIgnoredForUsage(usage);
            }
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_withHapticFeedbackSettingsOff_ignoresTouchVibration() {
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_TOUCH) {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_SETTINGS);
            } else {
                assertVibrationNotIgnoredForUsage(usage);
            }
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_withHardwareFeedbackSettingsOff_ignoresHardwareVibrations() {
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_HARDWARE_FEEDBACK || usage == USAGE_PHYSICAL_EMULATION) {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_SETTINGS);
            } else {
                assertVibrationNotIgnoredForUsage(usage);
            }
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_withNotificationSettingsOff_ignoresNotificationVibrations() {
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_NOTIFICATION) {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_SETTINGS);
            } else {
                assertVibrationNotIgnoredForUsage(usage);
            }
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_withRingSettingsOff_ignoresRingtoneVibrations() {
        // Vibrating settings on are overruled by ring intensity setting.
        setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);

        for (int usage : ALL_USAGES) {
            if (usage == USAGE_RINGTONE) {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_SETTINGS);
            } else {
                assertVibrationNotIgnoredForUsage(usage);
            }
            assertVibrationNotIgnoredForUsageAndFlags(usage,
                    VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF);
        }
    }

    @Test
    public void shouldIgnoreVibration_updateTriggeredAfterInternalRingerModeChanged() {
        // Vibrating settings on are overruled by ringer mode.
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        setUserSetting(Settings.System.APPLY_RAMPING_RINGER, 1);
        setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        assertVibrationNotIgnoredForUsage(USAGE_RINGTONE);

        // Testing the broadcast flow manually.
        mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));

        assertVibrationIgnoredForUsage(USAGE_RINGTONE, Vibration.Status.IGNORED_FOR_RINGER_MODE);
    }

    @Test
    public void shouldVibrateInputDevices_returnsSettingsValue() {
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        assertTrue(mVibrationSettings.shouldVibrateInputDevices());

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        assertFalse(mVibrationSettings.shouldVibrateInputDevices());
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withNonSystemPackageAndUid_returnsAlwaysTrue() {
        for (int usage : ALL_USAGES) {
            assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(UID, "some.app", usage));
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withUidZero_returnsFalseForTouchAndHardware() {
        for (int usage : ALL_USAGES) {
            if (usage == USAGE_TOUCH || usage == USAGE_HARDWARE_FEEDBACK
                    || usage == USAGE_PHYSICAL_EMULATION) {
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        /* uid= */ 0, "", usage));
            } else {
                assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        /* uid= */ 0, "", usage));
            }
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withSystemUid_returnsFalseForTouchAndHardware() {
        for (int usage : ALL_USAGES) {
            if (usage == USAGE_TOUCH || usage == USAGE_HARDWARE_FEEDBACK
                    || usage == USAGE_PHYSICAL_EMULATION) {
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        Process.SYSTEM_UID, "", usage));
            } else {
                assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        Process.SYSTEM_UID, "", usage));
            }
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withSysUi_returnsFalseForTouchAndHardware() {
        for (int usage : ALL_USAGES) {
            if (usage == USAGE_TOUCH || usage == USAGE_HARDWARE_FEEDBACK
                    || usage == USAGE_PHYSICAL_EMULATION) {
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        UID, SYSUI_PACKAGE_NAME, usage));
            } else {
                assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        UID, SYSUI_PACKAGE_NAME, usage));
            }
        }
    }

    @Test
    public void getDefaultIntensity_returnsIntensityFromVibratorConfig() {
        setDefaultIntensity(VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);

        for (int usage : ALL_USAGES) {
            assertEquals(VIBRATION_INTENSITY_HIGH, mVibrationSettings.getDefaultIntensity(usage));
        }
    }

    @Test
    public void getCurrentIntensity_returnsIntensityFromSettings() {
        setDefaultIntensity(VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.ALARM_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.MEDIA_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);

        for (int usage : ALL_USAGES) {
            assertEquals(errorMessageForUsage(usage),
                    VIBRATION_INTENSITY_LOW,
                    mVibrationSettings.getCurrentIntensity(usage));
        }
    }

    @Test
    public void getCurrentIntensity_updateTriggeredAfterUserSwitched() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));

        // Switching user is not working with FakeSettingsProvider.
        // Testing the broadcast flow manually.
        Settings.System.putIntForUser(mContextSpy.getContentResolver(),
                Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW,
                UserHandle.USER_CURRENT);
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(Intent.ACTION_USER_SWITCHED));
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));
    }

    @Test
    public void getCurrentIntensity_noHardwareFeedbackValueUsesHapticFeedbackValue() {
        setDefaultIntensity(USAGE_HARDWARE_FEEDBACK, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        mVibrationSettings.update();
        assertEquals(VIBRATION_INTENSITY_OFF, mVibrationSettings.getCurrentIntensity(USAGE_TOUCH));
        // If haptic feedback is off, fallback to default value.
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_PHYSICAL_EMULATION));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
        mVibrationSettings.update();
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_TOUCH));
        // If haptic feedback is on, fallback to that value.
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_HIGH,
                mVibrationSettings.getCurrentIntensity(USAGE_PHYSICAL_EMULATION));
    }

    @Test
    public void getFallbackEffect_returnsEffectsFromSettings() {
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_TICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_TEXTURE_TICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_CLICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_HEAVY_CLICK));
        assertNotNull(mVibrationSettings.getFallbackEffect(VibrationEffect.EFFECT_DOUBLE_CLICK));
    }

    private void assertVibrationIgnoredForUsage(@VibrationAttributes.Usage int usage,
            Vibration.Status expectedStatus) {
        assertEquals(errorMessageForUsage(usage),
                expectedStatus,
                mVibrationSettings.shouldIgnoreVibration(UID,
                        VibrationAttributes.createForUsage(usage)));
    }

    private void assertVibrationNotIgnoredForUsage(@VibrationAttributes.Usage int usage) {
        assertVibrationNotIgnoredForUsageAndFlags(usage, /* flags= */ 0);
    }

    private void assertVibrationNotIgnoredForUsageAndFlags(@VibrationAttributes.Usage int usage,
            @VibrationAttributes.Flag int flags) {
        assertNull(errorMessageForUsage(usage),
                mVibrationSettings.shouldIgnoreVibration(UID,
                        new VibrationAttributes.Builder()
                                .setUsage(usage)
                                .setFlags(flags)
                                .build()));
    }

    private String errorMessageForUsage(int usage) {
        return "Error for usage " + VibrationAttributes.usageToString(usage);
    }

    private void setDefaultIntensity(@Vibrator.VibrationIntensity int intensity) {
        when(mVibrationConfigMock.getDefaultVibrationIntensity(anyInt())).thenReturn(intensity);
    }

    private void setDefaultIntensity(@VibrationAttributes.Usage int usage,
            @Vibrator.VibrationIntensity int intensity) {
        when(mVibrationConfigMock.getDefaultVibrationIntensity(eq(usage))).thenReturn(intensity);
    }

    private void deleteUserSetting(String settingName) {
        Settings.System.putStringForUser(
                mContextSpy.getContentResolver(), settingName, null, UserHandle.USER_CURRENT);
        // FakeSettingsProvider doesn't support testing triggering ContentObserver yet.
        mVibrationSettings.update();
    }

    private void setUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
        // FakeSettingsProvider doesn't support testing triggering ContentObserver yet.
        mVibrationSettings.update();
    }

    private void setRingerMode(int ringerMode) {
        mAudioManager.setRingerModeInternal(ringerMode);
        assertEquals(ringerMode, mAudioManager.getRingerModeInternal());
        mVibrationSettings.update();
    }
}
