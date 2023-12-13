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

import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_APP_STREAMING;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_CALENDAR;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_CALL_LOGS;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_CONTACTS;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_MICROPHONE;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_NEARBY_DEVICES;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_NEARBY_DEVICE_STREAMING;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_NOTIFICATION;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_PHONE;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_SMS;
import static com.android.companiondevicemanager.PermissionListAdapter.PERMISSION_STORAGE;

import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;

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
    static final Map<String, Integer> TITLES;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_APP_STREAMING, R.string.title_app_streaming);
        map.put(DEVICE_PROFILE_AUTOMOTIVE_PROJECTION, R.string.title_automotive_projection);
        map.put(DEVICE_PROFILE_COMPUTER, R.string.title_computer);
        map.put(DEVICE_PROFILE_NEARBY_DEVICE_STREAMING, R.string.title_nearby_device_streaming);
        map.put(DEVICE_PROFILE_WATCH, R.string.confirmation_title);
        map.put(DEVICE_PROFILE_GLASSES, R.string.confirmation_title_glasses);
        map.put(null, R.string.confirmation_title);

        TITLES = unmodifiableMap(map);
    }

    static final Map<String, List<Integer>> PERMISSION_TYPES;
    static {
        final Map<String, List<Integer>> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_APP_STREAMING, Arrays.asList(PERMISSION_APP_STREAMING));
        map.put(DEVICE_PROFILE_COMPUTER, Arrays.asList(
                PERMISSION_NOTIFICATION, PERMISSION_STORAGE));
        map.put(DEVICE_PROFILE_NEARBY_DEVICE_STREAMING,
                Arrays.asList(PERMISSION_NEARBY_DEVICE_STREAMING));
        map.put(DEVICE_PROFILE_WATCH, Arrays.asList(PERMISSION_NOTIFICATION, PERMISSION_PHONE,
                PERMISSION_CALL_LOGS, PERMISSION_SMS, PERMISSION_CONTACTS, PERMISSION_CALENDAR,
                PERMISSION_NEARBY_DEVICES));
        map.put(DEVICE_PROFILE_GLASSES, Arrays.asList(PERMISSION_NOTIFICATION, PERMISSION_PHONE,
                PERMISSION_SMS, PERMISSION_CONTACTS, PERMISSION_MICROPHONE,
                PERMISSION_NEARBY_DEVICES));

        PERMISSION_TYPES = unmodifiableMap(map);
    }

    static final Map<String, Integer> SUMMARIES;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, R.string.summary_watch);
        map.put(DEVICE_PROFILE_GLASSES, R.string.summary_glasses);
        map.put(null, R.string.summary_generic);

        SUMMARIES = unmodifiableMap(map);
    }

    static final Map<String, Integer> PROFILES_NAME;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, R.string.profile_name_watch);
        map.put(DEVICE_PROFILE_GLASSES, R.string.profile_name_glasses);
        map.put(null, R.string.profile_name_generic);

        PROFILES_NAME = unmodifiableMap(map);
    }

    static final Map<String, Integer> PROFILES_NAME_MULTI;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_GLASSES, R.string.profile_name_generic);
        map.put(DEVICE_PROFILE_WATCH, R.string.profile_name_watch);
        map.put(null, R.string.profile_name_generic);

        PROFILES_NAME_MULTI = unmodifiableMap(map);
    }

    static final Map<String, Integer> PROFILE_ICON;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(DEVICE_PROFILE_WATCH, R.drawable.ic_watch);
        map.put(DEVICE_PROFILE_GLASSES, R.drawable.ic_glasses);
        map.put(null, R.drawable.ic_device_other);

        PROFILE_ICON = unmodifiableMap(map);
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
