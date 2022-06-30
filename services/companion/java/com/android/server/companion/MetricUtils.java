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

package com.android.server.companion;

import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.AssociationRequest.DEVICE_PROFILE_COMPUTER;
import static android.companion.AssociationRequest.DEVICE_PROFILE_WATCH;

import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__ACTION__CREATED;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__ACTION__REMOVED;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_APP_STREAMING;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_AUTO_PROJECTION;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_COMPUTER;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_NULL;
import static com.android.internal.util.FrameworkStatsLog.CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_WATCH;
import static com.android.internal.util.FrameworkStatsLog.write;

import static java.util.Collections.unmodifiableMap;

import android.util.ArrayMap;

import java.util.Map;

final class MetricUtils {

    private static final Map<String, Integer> METRIC_DEVICE_PROFILE;
    static {
        final Map<String, Integer> map = new ArrayMap<>();
        map.put(null, CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_NULL);
        map.put(
                DEVICE_PROFILE_WATCH,
                CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_WATCH
        );
        map.put(
                DEVICE_PROFILE_APP_STREAMING,
                CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_APP_STREAMING
        );
        map.put(
                DEVICE_PROFILE_AUTOMOTIVE_PROJECTION,
                CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_AUTO_PROJECTION
        );
        map.put(
                DEVICE_PROFILE_COMPUTER,
                CDM_ASSOCIATION_ACTION__DEVICE_PROFILE__DEVICE_PROFILE_COMPUTER
        );

        METRIC_DEVICE_PROFILE = unmodifiableMap(map);
    }

    static void logCreateAssociation(String profile) {
        write(CDM_ASSOCIATION_ACTION,
                CDM_ASSOCIATION_ACTION__ACTION__CREATED,
                METRIC_DEVICE_PROFILE.get(profile));
    }

    static void logRemoveAssociation(String profile) {
        write(CDM_ASSOCIATION_ACTION,
                CDM_ASSOCIATION_ACTION__ACTION__REMOVED,
                METRIC_DEVICE_PROFILE.get(profile));
    }
}
