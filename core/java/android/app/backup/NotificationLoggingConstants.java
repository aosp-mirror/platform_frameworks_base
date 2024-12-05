/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.app.backup;

/**
 * @hide
 */
public class NotificationLoggingConstants {

    // Key under which the payload blob is stored
    public static final String KEY_NOTIFICATIONS = "notifications";

    /**
     * Events for android.service.notification.ZenModeConfig - the configuration for all modes
     * settings for a single user
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_ZEN_CONFIG = KEY_NOTIFICATIONS + ":zen_config";
    /**
     * Events for android.service.notification.ZenModeConfig.ZenRule - a single mode within a
     * ZenModeConfig
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_ZEN_RULES = KEY_NOTIFICATIONS + ":zen_rules";
    /**
     * Events for globally stored notifications data that aren't stored in settings, like whether
     * to hide silent notification in the status bar
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_NOTIF_GLOBAL = KEY_NOTIFICATIONS + ":global";
    /**
     * Events for package specific notification settings, including app and
     * android.app.NotificationChannel level settings.
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_NOTIF_PACKAGES = KEY_NOTIFICATIONS + ":packages";
    /**
     * Events for approved ManagedServices (NotificationListenerServices,
     * NotificationAssistantService, ConditionProviderService).
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_MANAGED_SERVICE_PRIMARY_APPROVED = KEY_NOTIFICATIONS +
            ":managed_service_primary_approved";
    /**
     * Events for what types of notifications NotificationListenerServices cannot see
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_NLS_RESTRICTED = KEY_NOTIFICATIONS +
            ":nls_restricted";
    /**
     * Events for ManagedServices that are approved because they have a different primary
     * ManagedService (ConditionProviderService).
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_MANAGED_SERVICE_SECONDARY_APPROVED = KEY_NOTIFICATIONS +
            ":managed_service_secondary_approved";
    /**
     * Events for individual snoozed notifications.
     */
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_SNOOZED = KEY_NOTIFICATIONS + ":snoozed";


    @BackupRestoreEventLogger.BackupRestoreError
    public static final String ERROR_XML_PARSING = KEY_NOTIFICATIONS + ":invalid_xml_parsing";
}
