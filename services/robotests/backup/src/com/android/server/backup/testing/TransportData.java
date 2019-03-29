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

package com.android.server.backup.testing;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;

import com.android.server.backup.testing.TransportTestUtils.TransportStatus;

public class TransportData {
    // No constants since new Intent() can't be called in static context because of Robolectric
    public static TransportData backupTransport() {
        return new TransportData(
                "com.google.android.gms/.backup.BackupTransportService",
                "com.google.android.gms/.backup.BackupTransportService",
                "com.google.android.gms.backup.BackupTransportService",
                new Intent(),
                "user@gmail.com",
                new Intent(),
                "Google Account");
    }

    public static TransportData d2dTransport() {
        return new TransportData(
                "com.google.android.gms/.backup.migrate.service.D2dTransport",
                "com.google.android.gms/.backup.component.D2dTransportService",
                "d2dMigrateTransport",
                null,
                "Moving data to new device",
                null,
                "");
    }

    public static TransportData localTransport() {
        return new TransportData(
                "com.android.localtransport/.LocalTransport",
                "com.android.localtransport/.LocalTransportService",
                "com.android.localtransport.LocalTransport",
                null,
                "Backing up to debug-only private cache",
                null,
                "");
    }

    public static TransportData genericTransport(String packageName, String className) {
        return new TransportData(
                packageName + "/." + className,
                packageName + "/." + className + "Service",
                packageName + "." + className,
                new Intent(),
                "currentDestinationString",
                new Intent(),
                "dataManagementLabel");
    }

    @TransportStatus public int transportStatus;
    public final String transportName;
    private final String transportComponentShort;
    @Nullable public String transportDirName;
    @Nullable public Intent configurationIntent;
    @Nullable public String currentDestinationString;
    @Nullable public Intent dataManagementIntent;
    @Nullable public CharSequence dataManagementLabel;

    private TransportData(
            @TransportStatus int transportStatus,
            String transportName,
            String transportComponentShort,
            String transportDirName,
            Intent configurationIntent,
            String currentDestinationString,
            Intent dataManagementIntent,
            CharSequence dataManagementLabel) {
        this.transportStatus = transportStatus;
        this.transportName = transportName;
        this.transportComponentShort = transportComponentShort;
        this.transportDirName = transportDirName;
        this.configurationIntent = configurationIntent;
        this.currentDestinationString = currentDestinationString;
        this.dataManagementIntent = dataManagementIntent;
        this.dataManagementLabel = dataManagementLabel;
    }

    public TransportData(
            String transportName,
            String transportComponentShort,
            String transportDirName,
            Intent configurationIntent,
            String currentDestinationString,
            Intent dataManagementIntent,
            CharSequence dataManagementLabel) {
        this(
                TransportStatus.REGISTERED_AVAILABLE,
                transportName,
                transportComponentShort,
                transportDirName,
                configurationIntent,
                currentDestinationString,
                dataManagementIntent,
                dataManagementLabel);
    }

    /**
     * Not field because otherwise we'd have to call ComponentName::new in static context and
     * Robolectric does not like this.
     */
    public ComponentName getTransportComponent() {
        return ComponentName.unflattenFromString(transportComponentShort);
    }

    public TransportData unavailable() {
        return new TransportData(
                TransportStatus.REGISTERED_UNAVAILABLE,
                transportName,
                transportComponentShort,
                transportDirName,
                configurationIntent,
                currentDestinationString,
                dataManagementIntent,
                dataManagementLabel);
    }

    public TransportData unregistered() {
        return new TransportData(
                TransportStatus.UNREGISTERED,
                transportName,
                transportComponentShort,
                transportDirName,
                configurationIntent,
                currentDestinationString,
                dataManagementIntent,
                dataManagementLabel);
    }
}
