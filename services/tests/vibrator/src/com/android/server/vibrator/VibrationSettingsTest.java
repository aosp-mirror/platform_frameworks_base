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

import static android.os.BatteryManager.BATTERY_PLUGGED_USB;
import static android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS;
import static android.os.BatteryManager.EXTRA_PLUGGED;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.os.vibrator.Flags;
import android.os.vibrator.VibrationConfig;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class VibrationSettingsTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int OLD_USER_ID = 123;
    private static final int NEW_USER_ID = 456;
    private static final int UID = 1;
    private static final int VIRTUAL_DEVICE_ID = 1;
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
    @Mock private VirtualDeviceManagerInternal mVirtualDeviceManagerInternalMock;
    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private AudioManager mAudioManagerMock;
    @Mock private VibrationConfig mVibrationConfigMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private VibrationSettings mVibrationSettings;
    private PowerManagerInternal.LowPowerModeListener mRegisteredPowerModeListener;
    private BroadcastReceiver mRegisteredBatteryBroadcastReceiver;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.AUDIO_SERVICE))).thenReturn(mAudioManagerMock);
        doAnswer(invocation -> {
            mRegisteredPowerModeListener = invocation.getArgument(0);
            return null;
        }).when(mPowerManagerInternalMock).registerLowPowerModeObserver(any());
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName(SYSUI_PACKAGE_NAME, ""));

        removeServicesForTest();
        addServicesForTest();

        setDefaultIntensity(VIBRATION_INTENSITY_MEDIUM);

        setIgnoreVibrationsOnWirelessCharger(false);
        createSystemReadyVibrationSettings();

        mockGoToSleep(/* goToSleepTime= */ 0, PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
    }

    private void createSystemReadyVibrationSettings() {
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

    private void removeServicesForTest() {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
    }

    private void addServicesForTest() {
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);
        LocalServices.addService(VirtualDeviceManagerInternal.class,
                mVirtualDeviceManagerInternalMock);
    }

    @After
    public void tearDown() throws Exception {
        removeServicesForTest();
    }

    @Test
    public void create_withOnlyRequiredSystemServices() {
        // The only core services that we depend on are PowerManager and PackageManager
        removeServicesForTest();
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);

        VibrationSettings minimalVibrationSettings = new VibrationSettings(mContextSpy,
                new Handler(mTestLooper.getLooper()), mVibrationConfigMock);
        minimalVibrationSettings.onSystemReady();
    }

    @Test
    public void addListener_switchUserTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        // Testing the broadcast flow manually.
        mVibrationSettings.mUserSwitchObserver.onUserSwitching(NEW_USER_ID);
        mVibrationSettings.mUserSwitchObserver.onUserSwitchComplete(NEW_USER_ID);

        verify(mListenerMock, times(2)).onChange();
    }

    @Test
    public void addListener_ringerModeChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        // Testing the broadcast flow manually.
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));

        verify(mListenerMock, times(2)).onChange();
    }

    @Test
    public void addListener_settingsChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        // Testing the broadcast flow manually.
        mVibrationSettings.mSettingObserver.onChange(false);
        mVibrationSettings.mSettingObserver.onChange(false);

        verify(mListenerMock, times(2)).onChange();
    }

    @Test
    public void addListener_lowPowerModeChangeTriggerListener() {
        mVibrationSettings.addListener(mListenerMock);

        // Testing the broadcast flow manually.
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE);
        mRegisteredPowerModeListener.onLowPowerModeChanged(NORMAL_POWER_STATE); // No change.

        verify(mListenerMock, times(2)).onChange();
    }

    @Test
    public void removeListener_noMoreCallbacksToListener() {
        mVibrationSettings.addListener(mListenerMock);

        mVibrationSettings.mSettingObserver.onChange(false);
        verify(mListenerMock).onChange();

        mVibrationSettings.removeListener(mListenerMock);

        // Trigger multiple observers manually.
        mVibrationSettings.mSettingObserver.onChange(false);
        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);
        mVibrationSettings.mUserSwitchObserver.onUserSwitchComplete(NEW_USER_ID);
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));

        verifyNoMoreInteractions(mListenerMock);
    }

    @Test
    public void shouldIgnoreVibration_fromBackground_doesNotIgnoreUsagesFromAllowlist() {
        Set<Integer> expectedAllowedVibrations = new HashSet<>(Arrays.asList(
                USAGE_RINGTONE,
                USAGE_ALARM,
                USAGE_NOTIFICATION,
                USAGE_COMMUNICATION_REQUEST,
                USAGE_HARDWARE_FEEDBACK,
                USAGE_PHYSICAL_EMULATION
        ));

        mVibrationSettings.mUidObserver.onUidStateChanged(
                UID, ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND, 0, 0);

        for (int usage : ALL_USAGES) {
            if (expectedAllowedVibrations.contains(usage)) {
                assertVibrationNotIgnoredForUsage(usage);
            } else {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_BACKGROUND);
            }
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
    public void wirelessChargingVibrationsEnabled_doesNotRegisterBatteryReceiver_allowsAnyUsage() {
        setBatteryReceiverRegistrationResult(getBatteryChangedIntent(BATTERY_PLUGGED_WIRELESS));
        setIgnoreVibrationsOnWirelessCharger(false);
        createSystemReadyVibrationSettings();

        assertNull(mRegisteredBatteryBroadcastReceiver);
        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_noBatteryIntentWhenSystemReady_allowsAnyUsage() {
        setBatteryReceiverRegistrationResult(null);
        setIgnoreVibrationsOnWirelessCharger(true);
        createSystemReadyVibrationSettings();

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_onNonWirelessChargerWhenSystemReady_allowsAnyUsage() {
        Intent nonWirelessChargingIntent = getBatteryChangedIntent(BATTERY_PLUGGED_USB);
        setBatteryReceiverRegistrationResult(nonWirelessChargingIntent);
        setIgnoreVibrationsOnWirelessCharger(true);
        createSystemReadyVibrationSettings();

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_onWirelessChargerWhenSystemReady_doesNotAllowFromAnyUsage() {
        Intent wirelessChargingIntent = getBatteryChangedIntent(BATTERY_PLUGGED_WIRELESS);
        setBatteryReceiverRegistrationResult(wirelessChargingIntent);
        setIgnoreVibrationsOnWirelessCharger(true);
        createSystemReadyVibrationSettings();

        for (int usage : ALL_USAGES) {
            assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_ON_WIRELESS_CHARGER);
        }
    }

    @Test
    public void shouldIgnoreVibration_receivesWirelessChargingIntent_doesNotAllowFromAnyUsage() {
        Intent nonWirelessChargingIntent = getBatteryChangedIntent(BATTERY_PLUGGED_USB);
        setBatteryReceiverRegistrationResult(nonWirelessChargingIntent);
        setIgnoreVibrationsOnWirelessCharger(true);
        createSystemReadyVibrationSettings();

        Intent wirelessChargingIntent = getBatteryChangedIntent(BATTERY_PLUGGED_WIRELESS);
        mRegisteredBatteryBroadcastReceiver.onReceive(mContextSpy, wirelessChargingIntent);

        for (int usage : ALL_USAGES) {
            assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_ON_WIRELESS_CHARGER);
        }
    }

    @Test
    public void shouldIgnoreVibration_receivesNonWirelessChargingIntent_allowsAnyUsage() {
        Intent wirelessChargingIntent = getBatteryChangedIntent(BATTERY_PLUGGED_WIRELESS);
        setBatteryReceiverRegistrationResult(wirelessChargingIntent);
        setIgnoreVibrationsOnWirelessCharger(true);
        createSystemReadyVibrationSettings();
        // Check that initially, all usages are ignored due to the wireless charging.
        for (int usage : ALL_USAGES) {
            assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_ON_WIRELESS_CHARGER);
        }

        Intent nonWirelessChargingIntent = getBatteryChangedIntent(BATTERY_PLUGGED_USB);
        mRegisteredBatteryBroadcastReceiver.onReceive(mContextSpy, nonWirelessChargingIntent);

        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsage(usage);
        }
    }

    @Test
    public void shouldIgnoreVibration_inBatterySaverMode_doesNotIgnoreUsagesFromAllowlist() {
        Set<Integer> expectedAllowedVibrations = new HashSet<>(Arrays.asList(
                USAGE_RINGTONE,
                USAGE_ALARM,
                USAGE_COMMUNICATION_REQUEST,
                USAGE_PHYSICAL_EMULATION,
                USAGE_HARDWARE_FEEDBACK
        ));

        mRegisteredPowerModeListener.onLowPowerModeChanged(LOW_POWER_STATE);

        for (int usage : ALL_USAGES) {
            if (expectedAllowedVibrations.contains(usage)) {
                assertVibrationNotIgnoredForUsage(usage);
            } else {
                assertVibrationIgnoredForUsage(usage, Vibration.Status.IGNORED_FOR_POWER);
            }
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
        when(mAudioManagerMock.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));

        assertVibrationIgnoredForUsage(USAGE_RINGTONE, Vibration.Status.IGNORED_FOR_RINGER_MODE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED)
    public void shouldIgnoreVibration_withKeyboardSettingsOff_shouldIgnoreKeyboardVibration() {
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.KEYBOARD_VIBRATION_ENABLED, 0 /* OFF*/);
        setHasFixedKeyboardAmplitudeIntensity(true);

        // Keyboard touch ignored.
        assertVibrationIgnoredForAttributes(
                new VibrationAttributes.Builder()
                        .setUsage(USAGE_TOUCH)
                        .setCategory(VibrationAttributes.CATEGORY_KEYBOARD)
                        .build(),
                Vibration.Status.IGNORED_FOR_SETTINGS);

        // General touch and keyboard touch with bypass flag not ignored.
        assertVibrationNotIgnoredForUsage(USAGE_TOUCH);
        assertVibrationNotIgnoredForAttributes(
                new VibrationAttributes.Builder()
                        .setUsage(USAGE_TOUCH)
                        .setCategory(VibrationAttributes.CATEGORY_KEYBOARD)
                        .setFlags(VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF)
                        .build());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED)
    public void shouldIgnoreVibration_withKeyboardSettingsOn_shouldNotIgnoreKeyboardVibration() {
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.KEYBOARD_VIBRATION_ENABLED, 1 /* ON */);
        setHasFixedKeyboardAmplitudeIntensity(true);

        // General touch ignored.
        assertVibrationIgnoredForUsage(USAGE_TOUCH, Vibration.Status.IGNORED_FOR_SETTINGS);

        // Keyboard touch not ignored.
        assertVibrationNotIgnoredForAttributes(
                new VibrationAttributes.Builder()
                        .setUsage(USAGE_TOUCH)
                        .setCategory(VibrationAttributes.CATEGORY_KEYBOARD)
                        .build());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_KEYBOARD_CATEGORY_ENABLED)
    public void shouldIgnoreVibration_noFixedKeyboardAmplitude_ignoresKeyboardTouchVibration() {
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        setUserSetting(Settings.System.KEYBOARD_VIBRATION_ENABLED, 1 /* ON */);
        setHasFixedKeyboardAmplitudeIntensity(false);

        // General touch ignored.
        assertVibrationIgnoredForUsage(USAGE_TOUCH, Vibration.Status.IGNORED_FOR_SETTINGS);

        // Keyboard touch ignored.
        assertVibrationIgnoredForAttributes(
                new VibrationAttributes.Builder()
                        .setUsage(USAGE_TOUCH)
                        .setCategory(VibrationAttributes.CATEGORY_KEYBOARD)
                        .build(),
                Vibration.Status.IGNORED_FOR_SETTINGS);
    }

    @Test
    public void shouldIgnoreVibrationFromVirtualDevices_defaultDevice_neverIgnored() {
        // Vibrations from the primary device is never ignored.
        for (int usage : ALL_USAGES) {
            assertVibrationNotIgnoredForUsageAndDevice(usage, Context.DEVICE_ID_DEFAULT);
        }
    }

    @Test
    public void shouldIgnoreVibrationFromVirtualDevices_virtualDevice_alwaysIgnored() {
        // Ignore the vibration when the coming device id represents a virtual device.
        for (int usage : ALL_USAGES) {
            assertVibrationIgnoredForUsageAndDevice(usage, VIRTUAL_DEVICE_ID,
                    Vibration.Status.IGNORED_FROM_VIRTUAL_DEVICE);
        }
    }

    @Test
    public void shouldVibrateInputDevices_returnsSettingsValue() {
        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 1);
        assertTrue(mVibrationSettings.shouldVibrateInputDevices());

        setUserSetting(Settings.System.VIBRATE_INPUT_DEVICES, 0);
        assertFalse(mVibrationSettings.shouldVibrateInputDevices());
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withEventBeforeVibration_returnsAlwaysFalse() {
        long vibrateStartTime = 100;
        mockGoToSleep(vibrateStartTime - 10, PowerManager.GO_TO_SLEEP_REASON_APPLICATION);

        for (int usage : ALL_USAGES) {
            // Non-system vibration
            assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(createCallerInfo(
                    UID, "some.app", usage), vibrateStartTime));
            // Vibration with UID zero
            assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                    createCallerInfo(/* uid= */ 0, "", usage), vibrateStartTime));
            // System vibration
            assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                    createCallerInfo(Process.SYSTEM_UID, "", usage), vibrateStartTime));
            // SysUI vibration
            assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                    createCallerInfo(UID, SYSUI_PACKAGE_NAME, usage), vibrateStartTime));
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withSleepReasonInAllowlist_returnsAlwaysFalse() {
        long vibrateStartTime = 100;
        int[] allowedSleepReasons = new int[]{
                PowerManager.GO_TO_SLEEP_REASON_TIMEOUT,
                PowerManager.GO_TO_SLEEP_REASON_INATTENTIVE,
        };

        for (int sleepReason : allowedSleepReasons) {
            mockGoToSleep(vibrateStartTime + 10, sleepReason);

            for (int usage : ALL_USAGES) {
                // Non-system vibration
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(UID, "some.app", usage), vibrateStartTime));
                // Vibration with UID zero
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(/* uid= */ 0, "", usage), vibrateStartTime));
                // System vibration
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(Process.SYSTEM_UID, "", usage), vibrateStartTime));
                // SysUI vibration
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(UID, SYSUI_PACKAGE_NAME, usage), vibrateStartTime));
            }
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withNonSystem_returnsTrueIfReasonNotInAllowlist() {
        long vibrateStartTime = 100;
        mockGoToSleep(vibrateStartTime + 10, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);

        for (int usage : ALL_USAGES) {
            assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                    createCallerInfo(UID, "some.app", usage), vibrateStartTime));
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withUidZero_returnsFalseForUsagesInAllowlist() {
        long vibrateStartTime = 100;
        mockGoToSleep(vibrateStartTime + 10, PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN);

        Set<Integer> expectedAllowedVibrations = new HashSet<>(Arrays.asList(
                USAGE_TOUCH,
                USAGE_ACCESSIBILITY,
                USAGE_PHYSICAL_EMULATION,
                USAGE_HARDWARE_FEEDBACK
        ));

        for (int usage : ALL_USAGES) {
            if (expectedAllowedVibrations.contains(usage)) {
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(/* uid= */ 0, "", usage), vibrateStartTime));
            } else {
                assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(/* uid= */ 0, "", usage), vibrateStartTime));
            }
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withSystemUid__returnsFalseForUsagesInAllowlist() {
        long vibrateStartTime = 100;
        mockGoToSleep(vibrateStartTime + 10, PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD);

        Set<Integer> expectedAllowedVibrations = new HashSet<>(Arrays.asList(
                USAGE_TOUCH,
                USAGE_ACCESSIBILITY,
                USAGE_PHYSICAL_EMULATION,
                USAGE_HARDWARE_FEEDBACK
        ));

        for (int usage : ALL_USAGES) {
            if (expectedAllowedVibrations.contains(usage)) {
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(Process.SYSTEM_UID, "", usage), vibrateStartTime));
            } else {
                assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(Process.SYSTEM_UID, "", usage), vibrateStartTime));
            }
        }
    }

    @Test
    public void shouldCancelVibrationOnScreenOff_withSysUiPkg_returnsFalseForUsagesInAllowlist() {
        long vibrateStartTime = 100;
        mockGoToSleep(vibrateStartTime + 10, PowerManager.GO_TO_SLEEP_REASON_HDMI);

        Set<Integer> expectedAllowedVibrations = new HashSet<>(Arrays.asList(
                USAGE_TOUCH,
                USAGE_ACCESSIBILITY,
                USAGE_PHYSICAL_EMULATION,
                USAGE_HARDWARE_FEEDBACK
        ));

        for (int usage : ALL_USAGES) {
            if (expectedAllowedVibrations.contains(usage)) {
                assertFalse(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(UID, SYSUI_PACKAGE_NAME, usage), vibrateStartTime));
            } else {
                assertTrue(mVibrationSettings.shouldCancelVibrationOnScreenOff(
                        createCallerInfo(UID, SYSUI_PACKAGE_NAME, usage), vibrateStartTime));
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

        // Test early update of settings based on new user id.
        putUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW,
                NEW_USER_ID);
        mVibrationSettings.mUserSwitchObserver.onUserSwitching(NEW_USER_ID);
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));

        // Test later update of settings for UserHandle.USER_CURRENT.
        putUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW,
                UserHandle.USER_CURRENT);
        mVibrationSettings.mUserSwitchObserver.onUserSwitchComplete(NEW_USER_ID);
        assertEquals(VIBRATION_INTENSITY_LOW,
                mVibrationSettings.getCurrentIntensity(USAGE_RINGTONE));
    }

    @Test
    public void getCurrentIntensity_noHardwareFeedbackValueUsesHapticFeedbackValue() {
        setDefaultIntensity(USAGE_HARDWARE_FEEDBACK, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        assertEquals(VIBRATION_INTENSITY_OFF, mVibrationSettings.getCurrentIntensity(USAGE_TOUCH));
        // If haptic feedback is off, fallback to default value.
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_HARDWARE_FEEDBACK));
        assertEquals(VIBRATION_INTENSITY_MEDIUM,
                mVibrationSettings.getCurrentIntensity(USAGE_PHYSICAL_EMULATION));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
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
        assertVibrationIgnoredForUsageAndDevice(usage, Context.DEVICE_ID_DEFAULT, expectedStatus);
    }

    private void assertVibrationIgnoredForUsageAndDevice(@VibrationAttributes.Usage int usage,
            int deviceId, Vibration.Status expectedStatus) {
        Vibration.CallerInfo callerInfo = new Vibration.CallerInfo(
                VibrationAttributes.createForUsage(usage), UID, deviceId, null, null);
        assertEquals(errorMessageForUsage(usage), expectedStatus,
                mVibrationSettings.shouldIgnoreVibration(callerInfo));
    }

    private void assertVibrationIgnoredForAttributes(VibrationAttributes attrs,
            Vibration.Status expectedStatus) {
        Vibration.CallerInfo callerInfo = new Vibration.CallerInfo(attrs, UID,
                Context.DEVICE_ID_DEFAULT, null, null);
        assertEquals(errorMessageForAttributes(attrs), expectedStatus,
                mVibrationSettings.shouldIgnoreVibration(callerInfo));
    }

    private void assertVibrationNotIgnoredForUsage(@VibrationAttributes.Usage int usage) {
        assertVibrationNotIgnoredForUsageAndFlags(usage, /* flags= */ 0);
    }

    private void assertVibrationNotIgnoredForUsageAndFlags(@VibrationAttributes.Usage int usage,
            @VibrationAttributes.Flag int flags) {
        assertVibrationNotIgnoredForUsageAndFlagsAndDevice(usage, Context.DEVICE_ID_DEFAULT, flags);
    }

    private void assertVibrationNotIgnoredForUsageAndDevice(@VibrationAttributes.Usage int usage,
            int deviceId) {
        assertVibrationNotIgnoredForUsageAndFlagsAndDevice(usage, deviceId, /* flags= */ 0);
    }

    private void assertVibrationNotIgnoredForUsageAndFlagsAndDevice(
            @VibrationAttributes.Usage int usage, int deviceId,
            @VibrationAttributes.Flag int flags) {
        Vibration.CallerInfo callerInfo = new Vibration.CallerInfo(
                new VibrationAttributes.Builder().setUsage(usage).setFlags(flags).build(), UID,
                deviceId, null, null);
        assertNull(errorMessageForUsage(usage),
                mVibrationSettings.shouldIgnoreVibration(callerInfo));
    }

    private void assertVibrationNotIgnoredForAttributes(VibrationAttributes attrs) {
        Vibration.CallerInfo callerInfo = new Vibration.CallerInfo(attrs, UID,
                Context.DEVICE_ID_DEFAULT, null, null);
        assertNull(errorMessageForAttributes(attrs),
                mVibrationSettings.shouldIgnoreVibration(callerInfo));
    }

    private String errorMessageForUsage(int usage) {
        return "Error for usage " + VibrationAttributes.usageToString(usage);
    }

    private String errorMessageForAttributes(VibrationAttributes attrs) {
        return "Error for attributes " + attrs;
    }

    private void setDefaultIntensity(@Vibrator.VibrationIntensity int intensity) {
        when(mVibrationConfigMock.getDefaultVibrationIntensity(anyInt())).thenReturn(intensity);
    }

    private void setDefaultIntensity(@VibrationAttributes.Usage int usage,
            @Vibrator.VibrationIntensity int intensity) {
        when(mVibrationConfigMock.getDefaultVibrationIntensity(eq(usage))).thenReturn(intensity);
    }

    private void setIgnoreVibrationsOnWirelessCharger(boolean ignore) {
        when(mVibrationConfigMock.ignoreVibrationsOnWirelessCharger()).thenReturn(ignore);
    }

    private void setHasFixedKeyboardAmplitudeIntensity(boolean hasFixedAmplitude) {
        when(mVibrationConfigMock.hasFixedKeyboardAmplitude()).thenReturn(hasFixedAmplitude);
    }

    private void deleteUserSetting(String settingName) {
        Settings.System.putStringForUser(
                mContextSpy.getContentResolver(), settingName, null, UserHandle.USER_CURRENT);
        // FakeSettingsProvider doesn't support testing triggering ContentObserver yet.
        mVibrationSettings.mSettingObserver.onChange(false);
    }

    private void setUserSetting(String settingName, int value) {
        putUserSetting(settingName, value, UserHandle.USER_CURRENT);
        // FakeSettingsProvider doesn't support testing triggering ContentObserver yet.
        mVibrationSettings.mSettingObserver.onChange(false);
    }

    private void putUserSetting(String settingName, int value, int userHandle) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, userHandle);
    }

    private void setRingerMode(int ringerMode) {
        when(mAudioManagerMock.getRingerModeInternal()).thenReturn(ringerMode);
        // Mock AudioManager broadcast of internal ringer mode change.
        mVibrationSettings.mSettingChangeReceiver.onReceive(mContextSpy,
                new Intent(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
    }

    private void mockGoToSleep(long sleepTime, int reason) {
        when(mPowerManagerInternalMock.getLastGoToSleep()).thenReturn(
                new PowerManager.SleepData(sleepTime, reason));
    }

    private Vibration.CallerInfo createCallerInfo(int uid, String opPkg,
            @VibrationAttributes.Usage int usage) {
        VibrationAttributes attrs = VibrationAttributes.createForUsage(usage);
        return new Vibration.CallerInfo(attrs, uid, VIRTUAL_DEVICE_ID, opPkg, null);
    }

    private void setBatteryReceiverRegistrationResult(Intent result) {
        doAnswer(invocation -> {
            mRegisteredBatteryBroadcastReceiver = invocation.getArgument(0);
            return result;
        }).when(mContextSpy).registerReceiver(any(BroadcastReceiver.class),
                argThat(filter -> filter.matchAction(Intent.ACTION_BATTERY_CHANGED)), anyInt());
    }

    private Intent getBatteryChangedIntent(int extraPluggedValue) {
        Intent batteryIntent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        batteryIntent.putExtra(EXTRA_PLUGGED, extraPluggedValue);
        return batteryIntent;
    }
}
