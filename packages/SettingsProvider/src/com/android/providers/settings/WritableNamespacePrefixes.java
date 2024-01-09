/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.providers.settings;

import android.util.ArraySet;

import java.util.Arrays;
import java.util.Set;

/**
 * Contains the list of prefixes for namespaces in which any flag can be written with adb.
 * <p>
 * A security review is required for any prefix that's added to this list. To add to
 * the list, create a change and tag the OWNER. In the change description, include a
 * description of the flag's functionality, and a justification for why it needs to be
 * allowlisted.
 */
final class WritableNamespacePrefixes {
    public static final Set<String> ALLOWLIST =
            new ArraySet<String>(Arrays.asList(
                "app_compat_overrides",
                "game_overlay",
                "namespace1",
                "accessibility",
                "activity_manager",
                "activity_manager_native_boot",
                "adaptive_charging",
                "adservices",
                "aiai_controlled_releases",
                "alarm_manager",
                "app_cloning",
                "app_compat",
                "app_compat_overrides",
                "app_hibernation",
                "app_standby",
                "appsearch",
                "arc_app_compat",
                "astrea_controlled_releases",
                "attention_manager_service",
                "auto_pin_confirmation",
                "autofill",
                "backup_and_restore",
                "base",
                "battery_saver",
                "biometrics",
                "bluetooth",
                "bluetooth_native",
                "camera_native",
                "captive_portal_login",
                "car",
                "cellular_security",
                "clipboard",
                "codegen_feature_flag_extractor",
                "companion",
                "configuration",
                "connectivity",
                "connectivity_thermal_power_manager",
                "constrain_display_apis",
                "content_capture",
                "credential_manager",
                "device_idle",
                "device_personalization_services",
                "device_policy_manager",
                "devicelock",
                "display_manager",
                "dropbox",
                "edgetpu_native",
                "exo",
                "flipendo",
                "game_driver",
                "game_overlay",
                "gantry",
                "halyard_demo",
                "haptics",
                "hdmi_control",
                "health_fitness",
                "input",
                "input_method",
                "input_native",
                "input_native_boot",
                "intelligence_bubbles",
                "interaction_jank_monitor",
                "ipsec",
                "jobscheduler",
                "kiwi",
                "latency_tracker",
                "launcher",
                "leaked_animator",
                "lmkd_native",
                "location",
                "logcat_manager",
                "low_power_standby",
                "media",
                "media_better_together",
                "media_native",
                "memory_safety_native",
                "memory_safety_native_boot",
                "mglru_native",
                "nearby",
                "netd_native",
                "nnapi_native",
                "notification_assistant",
                "odad",
                "on_device_abuse",
                "on_device_personalization",
                "oslo",
                "ota",
                "package_manager_service",
                "permissions",
                "privacy",
                "private_compute_services",
                "profcollect_native_boot",
                "remote_auth",
                "remote_key_provisioning_native",
                "rollback",
                "rollback_boot",
                "rotation_resolver",
                "runtime",
                "runtime_native",
                "runtime_native_boot",
                "sdk_sandbox",
                "settings_stats",
                "shared",
                "shared_native",
                "shared_native_boot",
                "statsd_java",
                "statsd_java_boot",
                "statsd_native",
                "statsd_native_boot",
                "storage_native_boot",
                "surface_flinger_native_boot",
                "swcodec_native",
                "system_scheduler",
                "system_server_watchdog",
                "system_time",
                "systemui",
                "tare",
                "telephony",
                "testing",
                "tethering",
                "text",
                "textclassifier",
                "touchflow_native",
                "tv_hdr_output_control",
                "twoshay_native",
                "uwb",
                "vcn",
                "vendor_system_native",
                "vendor_system_native_boot",
                "virtualization_framework_native",
                "vpn",
                "wallpaper_content",
                "wear",
                "wearable_sensing",
                "widget",
                "wifi",
                "window_manager",
                "window_manager_native_boot",
                "wrong"
            ));
}
