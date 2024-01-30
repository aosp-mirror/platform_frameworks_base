/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.companiondevicemanager;

import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.AssociationRequest.DEVICE_PROFILE_COMPUTER;
import static android.companion.AssociationRequest.DEVICE_PROFILE_GLASSES;
import static android.companion.AssociationRequest.DEVICE_PROFILE_NEARBY_DEVICE_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_WATCH;
import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;

import android.os.Build;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class contains maps that have deviceProfile as the key and resourceId as the value
 * for the corresponding profile.
 */
final class CompanionDeviceResources {

    // Permission resources
    private static final int PERMISSION_NOTIFICATION_LISTENER_ACCESS = 0;
    private static final int PERMISSION_STORAGE = 1;
    private static final int PERMISSION_APP_STREAMING = 2;
    private static final int PERMISSION_PHONE = 3;
    private static final int PERMISSION_SMS = 4;
    private static final int PERMISSION_CONTACTS = 5;
    private static final int PERMISSION_CALENDAR = 6;
    private static final int PERMISSION_NEARBY_DEVICES = 7;
    private static final int PERMISSION_NEARBY_DEVICE_STREAMING = 8;
    private static final int PERMISSION_MICROPHONE = 9;
    private static final int PERMISSION_CALL_LOGS = 10;
    // Notification Listener Access & POST_NOTIFICATION permission
    private static final int PERMISSION_NOTIFICATIONS = 11;
    private static final int PERMISSION_CHANGE_MEDIA_OUTPUT = 12;

    static final Map<Integer, Integer> PERMISSION_TITLES;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(PERMISSION_NOTIFICATION_LISTENER_ACCESS, R.string.permission_notifications);
        map.put(PERMISSION_STORAGE, R.string.permission_storage);
        map.put(PERMISSION_APP_STREAMING, R.string.permission_app_streaming);
        map.put(PERMISSION_PHONE, R.string.permission_phone);
        map.put(PERMISSION_SMS, R.string.permission_sms);
        map.put(PERMISSION_CONTACTS, R.string.permission_contacts);
        map.put(PERMISSION_CALENDAR, R.string.permission_calendar);
        map.put(PERMISSION_NEARBY_DEVICES, R.string.permission_nearby_devices);
        map.put(PERMISSION_NEARBY_DEVICE_STREAMING, R.string.permission_nearby_device_streaming);
        map.put(PERMISSION_MICROPHONE, R.string.permission_microphone);
        map.put(PERMISSION_CALL_LOGS, R.string.permission_call_logs);
        map.put(PERMISSION_NOTIFICATIONS, R.string.permission_notifications);
        map.put(PERMISSION_CHANGE_MEDIA_OUTPUT, R.string.permission_media_routing_control);
        PERMISSION_TITLES = unmodifiableMap(map);
    }

    static final Map<Integer, Integer> PERMISSION_SUMMARIES;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(PERMISSION_NOTIFICATION_LISTENER_ACCESS,
                R.string.permission_notification_listener_access_summary);
        map.put(PERMISSION_STORAGE, R.string.permission_storage_summary);
        map.put(PERMISSION_APP_STREAMING, R.string.permission_app_streaming_summary);
        map.put(PERMISSION_PHONE, R.string.permission_phone_summary);
        map.put(PERMISSION_SMS, R.string.permission_sms_summary);
        map.put(PERMISSION_CONTACTS, R.string.permission_contacts_summary);
        map.put(PERMISSION_CALENDAR, R.string.permission_calendar_summary);
        map.put(PERMISSION_NEARBY_DEVICES, R.string.permission_nearby_devices_summary);
        map.put(PERMISSION_NEARBY_DEVICE_STREAMING,
                R.string.permission_nearby_device_streaming_summary);
        map.put(PERMISSION_MICROPHONE, R.string.permission_microphone_summary);
        map.put(PERMISSION_CALL_LOGS, R.string.permission_call_logs_summary);
        map.put(PERMISSION_NOTIFICATIONS, R.string.permission_notifications_summary);
        map.put(PERMISSION_CHANGE_MEDIA_OUTPUT, R.string.permission_media_routing_control_summary);
        PERMISSION_SUMMARIES = unmodifiableMap(map);
    }

    static final Map<Integer, Integer> PERMISSION_ICONS;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(PERMISSION_NOTIFICATION_LISTENER_ACCESS, R.drawable.ic_permission_notifications);
        map.put(PERMISSION_STORAGE, R.drawable.ic_permission_storage);
        map.put(PERMISSION_APP_STREAMING, R.drawable.ic_permission_app_streaming);
        map.put(PERMISSION_PHONE, R.drawable.ic_permission_phone);
        map.put(PERMISSION_SMS, R.drawable.ic_permission_sms);
        map.put(PERMISSION_CONTACTS, R.drawable.ic_permission_contacts);
        map.put(PERMISSION_CALENDAR, R.drawable.ic_permission_calendar);
        map.put(PERMISSION_NEARBY_DEVICES, R.drawable.ic_permission_nearby_devices);
        map.put(PERMISSION_NEARBY_DEVICE_STREAMING,
                R.drawable.ic_permission_nearby_device_streaming);
        map.put(PERMISSION_MICROPHONE, R.drawable.ic_permission_microphone);
        map.put(PERMISSION_CALL_LOGS, R.drawable.ic_permission_call_logs);
        map.put(PERMISSION_NOTIFICATIONS, R.drawable.ic_permission_notifications);
        map.put(PERMISSION_CHANGE_MEDIA_OUTPUT, R.drawable.ic_permission_media_routing_control);
        PERMISSION_ICONS = unmodifiableMap(map);
    }

    // Profile resources
    static final Map<String, Integer> PROFILE_TITLES;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_APP_STREAMING, R.string.title_app_streaming);
        map.put(DEVICE_PROFILE_AUTOMOTIVE_PROJECTION, R.string.title_automotive_projection);
        map.put(DEVICE_PROFILE_COMPUTER, R.string.title_computer);
        map.put(DEVICE_PROFILE_NEARBY_DEVICE_STREAMING, R.string.title_nearby_device_streaming);
        map.put(DEVICE_PROFILE_WATCH, R.string.confirmation_title);
        map.put(DEVICE_PROFILE_GLASSES, R.string.confirmation_title_glasses);
        map.put(null, R.string.confirmation_title);

        PROFILE_TITLES = unmodifiableMap(map);
    }

    static final Map<String, Integer> PROFILE_SUMMARIES;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, R.string.summary_watch);
        map.put(DEVICE_PROFILE_GLASSES, R.string.summary_glasses);
        map.put(null, R.string.summary_generic);

        PROFILE_SUMMARIES = unmodifiableMap(map);
    }

    static final Map<String, List<Integer>> PROFILE_PERMISSIONS;
    static {
        final Map<String, List<Integer>> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_APP_STREAMING, Arrays.asList(PERMISSION_APP_STREAMING));
        map.put(DEVICE_PROFILE_COMPUTER, Arrays.asList(
                PERMISSION_NOTIFICATION_LISTENER_ACCESS, PERMISSION_STORAGE));
        map.put(DEVICE_PROFILE_NEARBY_DEVICE_STREAMING,
                Arrays.asList(PERMISSION_NEARBY_DEVICE_STREAMING));
        if (Build.VERSION.SDK_INT > UPSIDE_DOWN_CAKE) {
            map.put(DEVICE_PROFILE_WATCH, Arrays.asList(PERMISSION_NOTIFICATIONS, PERMISSION_PHONE,
                    PERMISSION_CALL_LOGS, PERMISSION_SMS, PERMISSION_CONTACTS, PERMISSION_CALENDAR,
                    PERMISSION_NEARBY_DEVICES, PERMISSION_CHANGE_MEDIA_OUTPUT));
        } else {
            map.put(DEVICE_PROFILE_WATCH, Arrays.asList(PERMISSION_NOTIFICATION_LISTENER_ACCESS,
                    PERMISSION_PHONE, PERMISSION_CALL_LOGS, PERMISSION_SMS, PERMISSION_CONTACTS,
                    PERMISSION_CALENDAR, PERMISSION_NEARBY_DEVICES));
        }
        map.put(DEVICE_PROFILE_GLASSES, Arrays.asList(PERMISSION_NOTIFICATION_LISTENER_ACCESS,
                PERMISSION_PHONE, PERMISSION_SMS, PERMISSION_CONTACTS, PERMISSION_MICROPHONE,
                PERMISSION_NEARBY_DEVICES));

        PROFILE_PERMISSIONS = unmodifiableMap(map);
    }

    static final Map<String, Integer> PROFILE_NAMES;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, R.string.profile_name_watch);
        map.put(DEVICE_PROFILE_GLASSES, R.string.profile_name_glasses);
        map.put(null, R.string.profile_name_generic);

        PROFILE_NAMES = unmodifiableMap(map);
    }

    static final Map<String, Integer> PROFILE_ICONS;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, R.drawable.ic_watch);
        map.put(DEVICE_PROFILE_GLASSES, R.drawable.ic_glasses);
        map.put(null, R.drawable.ic_device_other);

        PROFILE_ICONS = unmodifiableMap(map);
    }

    static final Set<String> SUPPORTED_PROFILES;
    static {
        final Set<String> set = new ArraySet<>();
        set.add(DEVICE_PROFILE_WATCH);
        set.add(DEVICE_PROFILE_GLASSES);
        set.add(null);

        SUPPORTED_PROFILES = unmodifiableSet(set);
    }

    static final Set<String> SUPPORTED_SELF_MANAGED_PROFILES;
    static {
        final Set<String> set = new ArraySet<>();
        set.add(DEVICE_PROFILE_APP_STREAMING);
        set.add(DEVICE_PROFILE_COMPUTER);
        set.add(DEVICE_PROFILE_AUTOMOTIVE_PROJECTION);
        set.add(DEVICE_PROFILE_NEARBY_DEVICE_STREAMING);
        set.add(null);

        SUPPORTED_SELF_MANAGED_PROFILES = unmodifiableSet(set);
    }
}
