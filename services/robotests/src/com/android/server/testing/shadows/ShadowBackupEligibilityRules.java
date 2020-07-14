/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.BackupEligibilityRules;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.HashSet;
import java.util.Set;

@Implements(BackupEligibilityRules.class)
public class ShadowBackupEligibilityRules {
    private static final Set<String> sAppsRunningAndEligibleForBackupWithTransport =
            new HashSet<>();
    private static final Set<String> sAppsEligibleForBackup = new HashSet<>();
    private static final Set<String> sAppsGetFullBackup = new HashSet<>();

    public static void setAppRunningAndEligibleForBackupWithTransport(String packageName) {
        sAppsEligibleForBackup.add(packageName);
        sAppsRunningAndEligibleForBackupWithTransport.add(packageName);
    }

    public static void setAppEligibleForBackup(String packageName) {
        sAppsEligibleForBackup.add(packageName);
    }

    /** By default the app will be key-value. */
    public static void setAppGetsFullBackup(String packageName) {
        sAppsGetFullBackup.add(packageName);
    }

    @Implementation
    protected boolean appIsRunningAndEligibleForBackupWithTransport(
            @Nullable TransportClient transportClient,
            String packageName) {
        return sAppsRunningAndEligibleForBackupWithTransport.contains(packageName);
    }

    @Implementation
    protected boolean appIsEligibleForBackup(ApplicationInfo app) {
        return sAppsEligibleForBackup.contains(app.packageName);
    }

    @Implementation
    protected boolean appGetsFullBackup(PackageInfo packageInfo) {
        return sAppsGetFullBackup.contains(packageInfo.packageName);
    }

    @Resetter
    public static void reset() {
        sAppsRunningAndEligibleForBackupWithTransport.clear();
        sAppsEligibleForBackup.clear();
        sAppsGetFullBackup.clear();
    }
}
