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
import static android.provider.settings.validators.SettingsValidators.NON_NEGATIVE_FLOAT_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR;
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
                                || (val == BatteryManager.BATTERY_PLUGGED_DOCK)
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_DOCK))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_DOCK))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_WIRELESS
                                                | BatteryManager.BATTERY_PLUGGED_DOCK))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_DOCK))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS
                                                | BatteryManager.BATTERY_PLUGGED_DOCK));
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
        VALIDATORS.put(Global.EMERGENCY_GESTURE_POWER_BUTTON_COOLDOWN_PERIOD_MS,
                NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.EMERGENCY_GESTURE_TAP_DETECTION_MIN_TIME_MS,
                NON_NEGATIVE_INTEGER_VALIDATOR);
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
        VALIDATORS.put(Global.LOW_POWER_MODE_REMINDER_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.AUTOMATIC_POWER_SAVE_MODE,
                new DiscreteValueValidator(new String[] {"0", "1"}));
        VALIDATORS.put(
                Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD, PERCENTAGE_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.BLUETOOTH_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.CLOCKWORK_HOME_READY, ANY_STRING_VALIDATOR);
        VALIDATORS.put(Global.ENABLE_TARE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.ENABLE_TARE_ALARM_MANAGER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.ENABLE_TARE_JOB_SCHEDULER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.TARE_ALARM_MANAGER_CONSTANTS, ANY_STRING_VALIDATOR);
        VALIDATORS.put(Global.TARE_JOB_SCHEDULER_CONSTANTS, ANY_STRING_VALIDATOR);
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
        VALIDATORS.put(Global.AUTOMATIC_POWER_SAVE_MODE, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.ADVANCED_BATTERY_USAGE_AMOUNT, PERCENTAGE_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.POWER_BUTTON_LONG_PRESS_DURATION_MS, NONE_NEGATIVE_LONG_VALIDATOR);
        VALIDATORS.put(Global.STYLUS_EVER_USED, BOOLEAN_VALIDATOR);

        VALIDATORS.put(Global.Wearable.HAS_PAY_TOKENS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.GMS_CHECKIN_TIMEOUT_MIN, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.HOTWORD_DETECTION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.SMART_REPLIES_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.OBTAIN_PAIRED_DEVICE_LOCATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.PHONE_PLAY_STORE_AVAILABILITY,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.PHONE_PLAY_STORE_AVAILABLE),
                            String.valueOf(Global.Wearable.PHONE_PLAY_STORE_UNAVAILABLE),
                            String.valueOf(Global.Wearable.PHONE_PLAY_STORE_AVAILABILITY_UNKNOWN)
                        }));
        VALIDATORS.put(
                Global.Wearable.BUG_REPORT,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.BUG_REPORT_ENABLED),
                            String.valueOf(Global.Wearable.BUG_REPORT_DISABLED)
                        }));
        VALIDATORS.put(Global.Wearable.SMART_ILLUMINATE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.CLOCKWORK_AUTO_TIME,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.SYNC_TIME_FROM_PHONE),
                            String.valueOf(Global.Wearable.SYNC_TIME_FROM_NETWORK),
                            String.valueOf(Global.Wearable.AUTO_TIME_OFF),
                            String.valueOf(Global.Wearable.INVALID_AUTO_TIME_STATE)
                        }));
        VALIDATORS.put(
                Global.Wearable.CLOCKWORK_AUTO_TIME_ZONE,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.SYNC_TIME_ZONE_FROM_PHONE),
                            String.valueOf(Global.Wearable.SYNC_TIME_ZONE_FROM_NETWORK),
                            String.valueOf(Global.Wearable.AUTO_TIME_ZONE_OFF),
                            String.valueOf(Global.Wearable.INVALID_AUTO_TIME_ZONE_STATE)
                        }));
        VALIDATORS.put(Global.Wearable.CLOCKWORK_24HR_TIME, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AUTO_WIFI, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.WIFI_POWER_SAVE, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.ALT_BYPASS_WIFI_REQUIREMENT_TIME_MILLIS,
                ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.SETUP_SKIPPED,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.SETUP_SKIPPED_YES),
                            String.valueOf(Global.Wearable.SETUP_SKIPPED_NO),
                            String.valueOf(Global.Wearable.SETUP_SKIPPED_UNKNOWN)
                        }));
        VALIDATORS.put(
                Global.Wearable.LAST_CALL_FORWARD_ACTION,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.CALL_FORWARD_ACTION_ON),
                            String.valueOf(Global.Wearable.CALL_FORWARD_ACTION_OFF),
                            String.valueOf(Global.Wearable.CALL_FORWARD_NO_LAST_ACTION)
                        }));
        VALIDATORS.put(
                Global.Wearable.STEM_1_TYPE,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.STEM_TYPE_UNKNOWN),
                            String.valueOf(Global.Wearable.STEM_TYPE_APP_LAUNCH),
                            String.valueOf(Global.Wearable.STEM_TYPE_CONTACT_LAUNCH)
                        }));
        VALIDATORS.put(
                Global.Wearable.STEM_2_TYPE,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.STEM_TYPE_UNKNOWN),
                            String.valueOf(Global.Wearable.STEM_TYPE_APP_LAUNCH),
                            String.valueOf(Global.Wearable.STEM_TYPE_CONTACT_LAUNCH)
                        }));
        VALIDATORS.put(
                Global.Wearable.STEM_3_TYPE,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.STEM_TYPE_UNKNOWN),
                            String.valueOf(Global.Wearable.STEM_TYPE_APP_LAUNCH),
                            String.valueOf(Global.Wearable.STEM_TYPE_CONTACT_LAUNCH)
                        }));
        VALIDATORS.put(Global.Wearable.MUTE_WHEN_OFF_BODY_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.SIDE_BUTTON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.BUTTON_SET, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.ANDROID_WEAR_VERSION, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.SYSTEM_CAPABILITIES, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.SYSTEM_EDITION, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.WEAR_PLATFORM_MR_NUMBER, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.MOBILE_SIGNAL_DETECTOR, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_TILT_TO_WAKE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_LOW_BIT_ENABLED_DEV, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_TOUCH_TO_WAKE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.DECOMPOSABLE_WATCHFACE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_FORCE_WHEN_DOCKED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_LOW_BIT_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_PLUGGED_TIMEOUT_MIN, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.AMBIENT_TILT_TO_BRIGHT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.PAIRED_DEVICE_OS_TYPE,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.PAIRED_DEVICE_OS_TYPE_UNKNOWN),
                            String.valueOf(Global.Wearable.PAIRED_DEVICE_OS_TYPE_ANDROID),
                            String.valueOf(Global.Wearable.PAIRED_DEVICE_OS_TYPE_IOS)
                        }));
        VALIDATORS.put(
                Global.Wearable.COMPANION_BLE_ROLE,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.BLUETOOTH_ROLE_CENTRAL),
                            String.valueOf(Global.Wearable.BLUETOOTH_ROLE_PERIPHERAL)
                        }));
        VALIDATORS.put(
                Global.Wearable.USER_HFP_CLIENT_SETTING,
                new DiscreteValueValidator(
                        new String[] {
                            String.valueOf(Global.Wearable.HFP_CLIENT_UNSET),
                            String.valueOf(Global.Wearable.HFP_CLIENT_ENABLED),
                            String.valueOf(Global.Wearable.HFP_CLIENT_DISABLED)
                        }));
        VALIDATORS.put(Global.Wearable.COMPANION_OS_VERSION, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.ENABLE_ALL_LANGUAGES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.OEM_SETUP_VERSION, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.Wearable.MASTER_GESTURES_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.UNGAZE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.BATTERY_SAVER_MODE,
                new DiscreteValueValidator(
                        new String[] {
                                String.valueOf(Global.Wearable.BATTERY_SAVER_MODE_NONE),
                                String.valueOf(Global.Wearable.BATTERY_SAVER_MODE_LIGHT),
                                String.valueOf(Global.Wearable.BATTERY_SAVER_MODE_TRADITIONAL_WATCH),
                                String.valueOf(Global.Wearable.BATTERY_SAVER_MODE_TIME_ONLY),
                                String.valueOf(Global.Wearable.BATTERY_SAVER_MODE_CUSTOM)
                        }));
        VALIDATORS.put(
                Global.Wearable.WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_MS,
                NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.WEAR_ACTIVITY_AUTO_RESUME_TIMEOUT_SET_BY_USER,
                BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.BURN_IN_PROTECTION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.COMBINED_LOCATION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.WRIST_ORIENTATION_MODE,
                       new DiscreteValueValidator(new String[] {"0", "1", "2", "3"}));
        VALIDATORS.put(Global.USER_PREFERRED_REFRESH_RATE, NON_NEGATIVE_FLOAT_VALIDATOR);
        VALIDATORS.put(Global.USER_PREFERRED_RESOLUTION_HEIGHT, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.USER_PREFERRED_RESOLUTION_WIDTH, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(Global.RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO_ENABLED,
                       new DiscreteValueValidator(new String[]{"0", "1"}));
        VALIDATORS.put(Global.Wearable.WET_MODE_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.COOLDOWN_MODE_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.GESTURE_TOUCH_AND_HOLD_WATCH_FACE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.RSB_WAKE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.SCREEN_UNLOCK_SOUND_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.CHARGING_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.BEDTIME_MODE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(Global.Wearable.BEDTIME_HARD_MODE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                Global.Wearable.EARLY_UPDATES_STATUS,
                new DiscreteValueValidator(
                        new String[] {
                                String.valueOf(Global.Wearable.EARLY_UPDATES_STATUS_NOT_STARTED),
                                String.valueOf(Global.Wearable.EARLY_UPDATES_STATUS_STARTED),
                                String.valueOf(Global.Wearable.EARLY_UPDATES_STATUS_SUCCESS),
                                String.valueOf(Global.Wearable.EARLY_UPDATES_STATUS_SKIPPED),
                                String.valueOf(Global.Wearable.EARLY_UPDATES_STATUS_ABORTED),
                          }));
        VALIDATORS.put(Global.Wearable.DYNAMIC_COLOR_THEME_ENABLED, BOOLEAN_VALIDATOR);
    }
}
