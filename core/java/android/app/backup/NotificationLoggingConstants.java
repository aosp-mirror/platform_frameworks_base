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

    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_ZEN_CONFIG = KEY_NOTIFICATIONS + ":zen_config";
    @BackupRestoreEventLogger.BackupRestoreDataType
    public static final String DATA_TYPE_ZEN_RULES = KEY_NOTIFICATIONS + ":zen_rules";

    @BackupRestoreEventLogger.BackupRestoreError
    public static final String ERROR_XML_PARSING = KEY_NOTIFICATIONS + ":invalid_xml_parsing";
}
