/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider.settings.validators;

import static android.media.AudioFormat.SURROUND_SOUND_ENCODING;
import static android.provider.settings.validators.SettingsValidators.ANY_INTEGER_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.ANY_STRING_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.BOOLEAN_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.NONE_NEGATIVE_LONG_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.PACKAGE_NAME_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.PERCENTAGE_INTEGER_VALIDATOR;
import static android.view.Display.HdrCapabilities.HDR_TYPES;

import android.os.BatteryManager;
import android.provider.Settings.Global;
import android.util.ArrayMap;

import java.util.Map;

/**
 * Validators for Global settings
 */
public class GlobalSettingsValidators {
    public static final Map<String, Validator> VALIDATORS = new ArrayMap<>();

    static {
        VALIDATORS.put(Global.APPLY_RAMPING_RINGER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.BUGREPORT_IN_POWER_MENU, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.STAY_ON_WHILE_PLUGGED_IN,
                value -> {
                    try {
                        int val = Integer.parseInt(value);
                        return (val == 0)
                                || (val == BatteryManager.BATTERY_PLUGGED_AC)
                                || (val == BatteryManager.BATTERY_PLUGGED_USB)
                                || (val == BatteryManager.BATTERY_PLUGGED_WIRELESS)
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
        VALIDATORS.put(Global.AUTO_TIME, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.AUTO_TIME_ZONE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.POWER_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.DOCK_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.CHARGING_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.USB_MASS_STORAGE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.NETWORK_RECOMMENDATIONS_ENABLED,
                new DiscreteValueValidator(new String[] {"-1", "0", "1"}));
        VALIDATORS.put(Global.WIFI_WAKEUP_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.USE_OPEN_WIFI_PACKAGE,
                value -> (value == null) || PACKAGE_NAME_VALIDATOR.validate(value));
        VALIDATORS.put(Global.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED, ANY_STRING_VALIDATOR);
        VALIDATORS.put(
                Global.EMERGENCY_TONE, new DiscreteValueValidator(new String[] {"0", "1", "2"}));
        VALIDATORS.put(Global.CALL_AUTO_RETRY, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.DOCK_AUDIO_MEDIA_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.ENABLE_AUTOMATIC_SYSTEM_SERVER_HEAP_DUMPS,
                new DiscreteValueValidator(new String[] {"0", "1"}));
        VALIDATORS.put(
                Global.ARE_USER_DISABLED_HDR_FORMATS_ALLOWED,
                new DiscreteValueValidator(new String[] {"0", "1"}));
        VALIDATORS.put(
                Global.USER_DISABLED_HDR_FORMATS,
                new DiscreteValueIntegerListValidator(",", HDR_TYPES));
        VALIDATORS.put(
                Global.ENCODED_SURROUND_OUTPUT,
                new DiscreteValueValidator(new String[] {"0", "1", "2", "3"}));
        VALIDATORS.put(
                Global.ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS,
                new DiscreteValueIntegerListValidator(",", SURROUND_SOUND_ENCODING));
        VALIDATORS.put(
                Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL,
                new InclusiveIntegerRangeValidator(0, 100));
        VALIDATORS.put(
                Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED,
                new DiscreteValueValidator(new String[] {"0", "1"}));
        VALIDATORS.put(Global.LOW_POWER_MODE_TRIGGER_LEVEL, PERCENTAGE_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.LOW_POWER_MODE_TRIGGER_LEVEL_MAX, PERCENTAGE_INTEGER_VALIDATOR);
        VALIDATORS.put(
                Global.AUTOMATIC_POWER_SAVE_MODE,
                new DiscreteValueValidator(new String[] {"0", "1"}));
        VALIDATORS.put(
                Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, PERCENTAGE_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.BLUETOOTH_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.PRIVATE_DNS_MODE, ANY_STRING_VALIDATOR);
        VALIDATORS.put(Global.PRIVATE_DNS_SPECIFIER, ANY_STRING_VALIDATOR);
        VALIDATORS.put(Global.SOFT_AP_TIMEOUT_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.WIFI_SCAN_THROTTLE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.APP_AUTO_RESTRICTION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.ZEN_DURATION, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.CHARGING_VIBRATION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.REQUIRE_PASSWORD_TO_DECRYPT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.DEVICE_DEMO_MODE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.AWARE_ALLOWED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.POWER_BUTTON_LONG_PRESS, new InclusiveIntegerRangeValidator(0, 5));
        VALIDATORS.put(
                Global.POWER_BUTTON_VERY_LONG_PRESS, new InclusiveIntegerRangeValidator(0, 1));
        VALIDATORS.put(Global.KEY_CHORD_POWER_VOLUME_UP, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(Global.CUSTOM_BUGREPORT_HANDLER_APP, ANY_STRING_VALIDATOR);
        VALIDATORS.put(Global.CUSTOM_BUGREPORT_HANDLER_USER, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.DEVELOPMENT_SETTINGS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.NOTIFICATION_FEEDBACK_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.RESTRICTED_NETWORKING_MODE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.ONE_HANDED_KEYGUARD_SIDE,
                new InclusiveIntegerRangeValidator(
                        /* first= */Global.ONE_HANDED_KEYGUARD_SIDE_LEFT,
                        /* last= */Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT));
        VALIDATORS.put(Global.DISABLE_WINDOW_BLURS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.DEVICE_CONFIG_SYNC_DISABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.POWER_BUTTON_LONG_PRESS_DURATION_MS, NONE_NEGATIVE_LONG_VALIDATOR);
    }
}

